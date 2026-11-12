#!/usr/bin/env bash
# Build the read-only QQ check-in probe into a signed LSPosed module APK.
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
  --version-code 1 --version-name 1.0 \
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
"$BT_WIN\\apksigner.bat" sign --ks "$KS_WIN" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out build/QQSignProbe.apk build/aligned.apk
"$BT_WIN\\apksigner.bat" verify --print-certs build/QQSignProbe.apk | head -4
ls -la build/QQSignProbe.apk
