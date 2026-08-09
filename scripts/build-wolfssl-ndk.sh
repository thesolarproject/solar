#!/usr/bin/env bash
# Build the embedded Wolfius TLS 1.3 proxy native library (libwolfssl.so) for Solar.
#
# Sources: solar-rom/vendor/wolfius/jni/  (wolfSSL submodule + wolfssl_bridge.c, GPLv3)
# Output : app/src/main/jniLibs/armeabi-v7a/libwolfssl.so  (packaged into the Solar APK)
#
# Usage:  scripts/build-wolfssl-ndk.sh
# Env:    NDK=/path/to/ndk  (default: $ANDROID_NDK_HOME or first dir in $ANDROID_HOME/ndk)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$REPO_ROOT/solar-rom/vendor/wolfius/jni"
OUT_ABI="armeabi-v7a"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs/$OUT_ABI"

# Locate NDK.
if [ -n "${NDK:-}" ] && [ -d "$NDK" ]; then
    :
elif [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -d "${ANDROID_HOME:-}/ndk" ]; then
    NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
else
    echo "NDK not found — set NDK= or ANDROID_NDK_HOME=" >&2
    exit 1
fi
echo "Using NDK: $NDK"
NDK_BUILD="$NDK/ndk-build"
if [ ! -x "$NDK_BUILD" ]; then
    echo "ndk-build not found at $NDK_BUILD" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
rm -rf "$JNI_DIR/obj" "$JNI_DIR/libs"

"$NDK_BUILD" NDK_PROJECT_PATH="$JNI_DIR" \
    NDK_APPLICATION_MK="$JNI_DIR/Application-solar.mk" \
    -j"$(nproc 2>/dev/null || echo 4)"

cp "$JNI_DIR/libs/$OUT_ABI/libwolfssl.so" "$OUT_DIR/libwolfssl.so"
echo "Installed: $OUT_DIR/libwolfssl.so"
