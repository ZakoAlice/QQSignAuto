#!/usr/bin/env bash
# Build the QQ daily auto check-in LSPosed module into a signed APK.
#
# Every path handed to the Java tools stays relative + ASCII on purpose: this project lives
# under a Chinese directory, and javac reads @argfiles with the platform charset (GBK), which
# chokes on UTF-8 bytes.
set -eu
WANT_BT=36.0.0
WANT_PLATFORM=android-36
. "$(cd "$(dirname "$0")/.." && pwd)/sdk.sh"   # finds the Android SDK, exports BT_WIN/PLATFORM_WIN/KS_WIN
pick_android_sdk
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# 签名配置：优先加载同目录的 signing.env（专用 release 密钥，已在 .gitignore 中忽略）。
# 文件缺失时回退到原来的 debug 密钥，保证旧流程仍能构建。
SIGN_ENV="$HERE/signing.env"
if [ -f "$SIGN_ENV" ]; then
    . "$SIGN_ENV"
    echo "using release keystore: $KS_WIN (alias $KS_ALIAS)"
else
    KS_ALIAS='androiddebugkey'
    KS_PASS='android'
    KEY_PASS='android'
fi

rm -rf build
mkdir -p build/stubs-classes build/classes build/dex

echo "[1/5] javac xposed stubs (compile-only, never packaged)"
find stubs -name '*.java' > build/stubs.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM_WIN" \
  -d build/stubs-classes @build/stubs.txt

echo "[2/5] javac module sources"
find src -name '*.java' > build/src.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM_WIN" \
  -cp build/stubs-classes -d build/classes @build/src.txt

echo "[3/5] d8"
( cd build/classes && find . -name '*.class' | sed 's|^\./||' > ../../build/cls.txt )
CLASSES=()
while IFS= read -r c; do CLASSES+=("build/classes/$c"); done < build/cls.txt
"$BT_WIN\\d8.bat" --release --min-api 24 --lib "$PLATFORM_WIN" --output build/dex "${CLASSES[@]}"

echo "[4/5] aapt2 link + pack dex/assets"
"$BT_WIN\\aapt2.exe" compile --dir res -o build/res.zip
"$BT_WIN\\aapt2.exe" link -o build/unaligned.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM_WIN" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 11 --version-name 1.7 \
  build/res.zip

python - <<'PY'
import zipfile
zin = zipfile.ZipFile("build/unaligned.apk")
zout = zipfile.ZipFile("build/withdex.apk", "w", zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
zout.write("build/dex/classes.dex", "classes.dex")
zout.write("assets/xposed_init", "assets/xposed_init")
zout.close(); zin.close()
print("apk entries:", zipfile.ZipFile("build/withdex.apk").namelist())
PY

echo "[5/5] zipalign + sign"
"$BT_WIN\\zipalign.exe" -f 4 build/withdex.apk build/aligned.apk
"$BT_WIN\\apksigner.bat" sign --ks "$KS_WIN" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out build/QQSignAuto.apk build/aligned.apk
"$BT_WIN\\apksigner.bat" verify --print-certs build/QQSignAuto.apk | head -4
ls -la build/QQSignAuto.apk
