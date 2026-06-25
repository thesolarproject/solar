#!/usr/bin/env bash
# Apply installed Y1 theme index via ADB (after Themes screen opens).
set -euo pipefail
IDX="${1:-0}"
adb wait-for-device
adb logcat -c
adb shell am force-stop com.solar.launcher
sleep 1
adb shell am start -S -n com.solar.launcher/.MainActivity \
  --ez solar_adb_apply_installed_theme true \
  --ei solar_adb_theme_apply_index "$IDX"
echo "Waiting for theme apply (index ${IDX})…"
sleep 20
echo "=== SolarAdbTest ==="
adb logcat -d -s SolarAdbTest:I 2>/dev/null | tail -10
echo "=== Fatal ==="
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|OutOfMemory|com.solar.launcher" | tail -8 || true
