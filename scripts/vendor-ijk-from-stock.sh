#!/usr/bin/env bash
# Extract stock Y1 IJK native libs (armeabi-v7a) into Solar jniLibs.
# ponytail: Java must stay paired with these 0.8.8-era .so — do not mix next/FFmpeg4 builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/reference/com.innioasis.y1_3.0.7_patched (2).apk"
OUT="$ROOT/app/src/main/jniLibs/armeabi-v7a"
LIBS=(libijkffmpeg.so libijkplayer.so libijksdl.so libc++_shared.so)

if [[ ! -f "$APK" ]]; then
  echo "ERROR: reference APK not found: $APK" >&2
  exit 1
fi

mkdir -p "$OUT"
for lib in "${LIBS[@]}"; do
  unzip -oj "$APK" "lib/armeabi-v7a/$lib" -d "$OUT"
  echo "extracted $lib"
done
echo "IJK libs -> $OUT"
