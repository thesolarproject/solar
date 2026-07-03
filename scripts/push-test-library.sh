#!/bin/bash
# Push the generated test library to an Innioasis Y1 sdcard1 for the perftest APK.
# Usage: ./scripts/push-test-library.sh [LOCAL_DIR]
set -euo pipefail

LOCAL_DIR="${1:-$PWD/test-library}"
if [ ! -d "$LOCAL_DIR" ]; then
    echo "Run ./scripts/generate-test-library.sh first." >&2
    exit 1
fi

adb shell "mkdir -p /storage/sdcard1/Music" || true
adb push "$LOCAL_DIR"/*.mp3 /storage/sdcard1/Music/
echo "Pushed $(ls -1 "$LOCAL_DIR"/*.mp3 | wc -l) tracks to /storage/sdcard1/Music"
