#!/usr/bin/env bash
# Opens Themes → Get More and scrolls the online catalog via ADB (Y1 wheel = key 127).
set -euo pipefail
SCROLL="${1:-25}"
adb wait-for-device
adb logcat -c
adb shell am force-stop com.solar.launcher
sleep 1
adb shell am start -S -n com.solar.launcher/.MainActivity \
  --ez solar_adb_open_themes_get_more true \
  --ei solar_adb_theme_scroll_steps "$SCROLL"
echo "Waiting for theme scroll test (${SCROLL} steps)…"
sleep 22
echo "=== SolarAdbTest ==="
adb logcat -d -s SolarAdbTest:I 2>/dev/null | tail -10
echo "=== Fatal ==="
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime.*com.solar" | tail -5 || true
echo "=== Theme debug ==="
adb logcat -d -s SolarNetDbg:I 2>/dev/null | grep -E "H-OOM|H-IOOB|H-SEL" | tail -15 || true
adb pull /storage/sdcard0/debug-5f8a90.log .cursor/debug-5f8a90.log 2>/dev/null || true
