#!/usr/bin/env bash
# Shared toolchain lookup for the check-in builds (auto/build.sh, module/build.sh).
#
# D:\build-tools\android-sdk used to be a link to the real SDK; when that link disappeared the
# builds stopped with "找不到程序包 java.lang" (an empty -bootclasspath), which is a confusing way
# to say "the SDK moved". So probe the known SDK roots and export the paths, instead of trusting
# one hard-coded address. Override with ANDROID_SDK_ROOT=D:\somewhere\else.
#
# A build script must set WANT_BT / WANT_PLATFORM before sourcing this file, because the probe and
# the module have historically pinned different build-tools versions.
pick_android_sdk() {
    for root in "${ANDROID_SDK_ROOT:-}" 'D:\Program\Android\SDK' 'D:\build-tools\android-sdk' 'D:\Projects\lsp模块\sdk'; do
        [ -n "$root" ] || continue
        # bash's own [ -f ] needs forward slashes; only the values exported below keep backslashes
        fwd="${root//\\//}"
        if [ -f "$fwd/build-tools/$WANT_BT/d8.bat" ] \
           && [ -f "$fwd/platforms/$WANT_PLATFORM/android.jar" ] \
           && [ -f "$fwd/build-tools/$WANT_BT/aapt2.exe" ] \
           && [ -f "$fwd/build-tools/$WANT_BT/zipalign.exe" ] \
           && [ -f "$fwd/build-tools/$WANT_BT/apksigner.bat" ]; then
            BT_WIN="${root}\\build-tools\\${WANT_BT}"
            PLATFORM_WIN="${root}\\platforms\\${WANT_PLATFORM}\\android.jar"
            echo "using SDK: $root (build-tools/$WANT_BT, platforms/$WANT_PLATFORM)"
            return 0
        fi
    done
    echo "no Android SDK found with build-tools/$WANT_BT + platforms/$WANT_PLATFORM;" >&2
    echo "install it or export ANDROID_SDK_ROOT" >&2
    return 1
}

KS_WIN='C:\Users\hou33\.android\debug.keystore'
