#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
cd "$ROOT"
CATALOG="$ROOT/catalog/artist-separators.csv"
ASSET="$ROOT/app/src/main/assets/artist-separators.csv"
if [[ -f "$CATALOG" ]]; then
  cp "$CATALOG" "$ASSET"
fi
chmod +x gradlew
./gradlew assembleRelease "$@"

UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED="$ROOT/app/build/outputs/apk/release/app-release.apk"
KEY_PK8="$SOLAR_PLATFORM_KEY_PK8"
KEY_PEM="$SOLAR_PLATFORM_KEY_PEM"

APKSIGNER="${ANDROID_HOME}/build-tools/35.0.0/apksigner"
if [[ ! -x "$APKSIGNER" && -d "${ANDROID_HOME}/build-tools" ]]; then
  APKSIGNER="$(ls -1 "${ANDROID_HOME}/build-tools"/*/apksigner 2>/dev/null | tail -1)"
fi
[[ -n "$APKSIGNER" && -x "$APKSIGNER" ]] || {
  echo "ERROR: apksigner not found under ANDROID_HOME/build-tools" >&2
  exit 1
}
[[ -f "$KEY_PK8" && -f "$KEY_PEM" ]] || {
  echo "ERROR: platform keys missing (set SOLAR_PLATFORM_KEY_PK8 / SOLAR_PLATFORM_KEY_PEM)" >&2
  exit 1
}
[[ -f "$UNSIGNED" ]] || {
  echo "ERROR: missing $UNSIGNED" >&2
  exit 1
}

echo "== Sign release APK with AOSP platform key =="
"$APKSIGNER" sign --key "$KEY_PK8" --cert "$KEY_PEM" --out "$SIGNED" "$UNSIGNED"
"$APKSIGNER" verify --verbose "$SIGNED" >/dev/null
echo "Signed: $SIGNED"
