#!/usr/bin/env bash
# Install platform-signed release. Use --system when adb root + remount works (needed for BLUETOOTH_PRIVILEGED).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
SYSTEM=false
for arg in "$@"; do
  [[ "$arg" == "--system" ]] && SYSTEM=true
done
[[ -f "$APK" ]] || {
  echo "Missing $APK — run ./scripts/build.sh first" >&2
  exit 1
}
if $SYSTEM; then
  adb root
  adb remount
  adb push "$APK" /system/app/com.solar.launcher.apk
  adb shell chmod 644 /system/app/com.solar.launcher.apk
  adb reboot
  echo "Pushed to /system/app/com.solar.launcher.apk — rebooting"
else
  if adb shell pm path com.solar.launcher 2>/dev/null | tr -d '\r' | grep -q '/system/'; then
    echo "ERROR: Solar runs from /system/app — adb install does not update it." >&2
    echo "Use: ./scripts/clean_install_system.sh   or   ./scripts/install.sh --system" >&2
    exit 1
  fi
  adb install -r "$APK"
  echo "Installed: $APK"
fi
