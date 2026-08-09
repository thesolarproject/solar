#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
cd "$ROOT"

BUILD_TYPE="debug"
SERIAL="${ANDROID_SERIAL:-}"
GRADLE_ARGS=()

usage() {
  cat <<USAGE
Usage: $0 [debug|release] [--serial DEVICE_SERIAL] [--no-launch] [--] [gradle-args...]

Builds Solar, installs the APK on a connected Android device, and launches it.
Rooted Y1/Y2 devices replace /system/app/com.solar.launcher.apk and reboot so
Package Manager does not leave a /data/app overlay. Phones/emulators use adb install.
Defaults to debug.
USAGE
}

LAUNCH=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    debug|--debug|-d)
      BUILD_TYPE="debug"
      shift
      ;;
    release|--release|-r)
      BUILD_TYPE="release"
      shift
      ;;
    --serial|-s)
      [[ $# -ge 2 ]] || { echo "ERROR: --serial requires a value" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --no-launch)
      LAUNCH=0
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      GRADLE_ARGS+=("$@")
      break
      ;;
    *)
      GRADLE_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ "$BUILD_TYPE" == "release" ]]; then
  GRADLE_TASK="assembleRelease"
  APK_PATH="$ROOT/app/build/outputs/apk/release/app-release.apk"
else
  GRADLE_TASK="assembleDebug"
  APK_PATH="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

require_tool() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: missing required tool '$1' after sourcing scripts/env.sh" >&2
    exit 1
  }
}

require_tool java
require_tool adb

export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date -u +%s)}"

CATALOG="$ROOT/catalog/artist-separators.csv"
ASSET="$ROOT/app/src/main/assets/artist-separators.csv"
if [[ -f "$CATALOG" ]]; then
  cp "$CATALOG" "$ASSET"
fi

echo "==> Checking ADB device..."
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  if [[ -n "$SERIAL" ]]; then
    echo "Waiting for device $SERIAL..."
  else
    echo "Waiting for any connected device..."
  fi
  "${ADB[@]}" wait-for-device
fi
"${ADB[@]}" devices -l | sed 's/^/    /'

echo "==> Preparing signing keys and Y1 assets..."
chmod +x gradlew scripts/ensure-platform-keystore.sh \
  solar-rom/scripts/sync-y1-assets.sh solar-rom/scripts/verify-y1-assets.sh
./scripts/ensure-platform-keystore.sh
./solar-rom/scripts/sync-y1-assets.sh

echo "==> Building $BUILD_TYPE APK with $GRADLE_TASK..."
./gradlew "$GRADLE_TASK" "${GRADLE_ARGS[@]}"

[[ -f "$APK_PATH" ]] || {
  echo "ERROR: APK was not created at $APK_PATH" >&2
  exit 1
}

echo "==> Installing $APK_PATH..."
bash "$ROOT/scripts/install-test-apk.sh" --serial "${SERIAL:-$("${ADB[@]}" get-serialno | tr -d '\r\n')}" --no-launch "$APK_PATH"

if [[ "$LAUNCH" -eq 1 ]]; then
  echo "==> Stopping existing Solar Launcher process..."
  "${ADB[@]}" shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
  echo "==> Launching Solar Launcher..."
  "${ADB[@]}" shell monkey -p com.solar.launcher -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || "${ADB[@]}" shell am start -n com.solar.launcher/.MainActivity >/dev/null
fi

echo "==> Installed $BUILD_TYPE APK successfully: $APK_PATH"
