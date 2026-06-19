#!/usr/bin/env bash
# Fast local dev install — copy signed release APK in place, restart launcher, no reboot.
# Use after ./scripts/build.sh for day-to-day Y1 testing (system app at /system/app).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

[[ -f "$APK" ]] || { echo "Missing $APK — run ./scripts/build.sh first" >&2; exit 1; }

run_su() {
  "${ADB[@]}" shell "su -c '$*'" 2>/dev/null || "${ADB[@]}" shell "$*"
}

echo "== Waiting for device =="
"${ADB[@]}" wait-for-device

DEVICE_APK="/data/local/tmp/solar-update.apk"
"${ADB[@]}" push "$APK" "$DEVICE_APK"

if "${ADB[@]}" shell pm path com.solar.launcher 2>/dev/null | tr -d '\r' | grep -q '^package:/system/'; then
  echo "== In-place /system/app update (no reboot) =="
  run_su "mount -o remount,rw /system"
  run_su "cp $DEVICE_APK /system/app/com.solar.launcher.apk"
  run_su "chmod 644 /system/app/com.solar.launcher.apk"
  run_su "sync"
  run_su "am force-stop com.solar.launcher"
  sleep 1
  run_su "am start -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10200000" || true
  echo "DONE: Solar restarted with updated APK"
else
  echo "== User install — pm install -r =="
  run_su "pm install -r $DEVICE_APK" || "${ADB[@]}" install -r "$APK"
  run_su "am force-stop com.solar.launcher" || true
  run_su "am start -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10200000" || true
  echo "DONE: installed and restarted"
fi
