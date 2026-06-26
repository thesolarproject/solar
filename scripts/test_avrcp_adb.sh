#!/usr/bin/env bash
# AVRCP + A2DP automated harness on connected Y1.
# Usage: ./scripts/test_avrcp_adb.sh [bt_mac]
set -euo pipefail

BT_ADDR="${1:-50:BA:02:58:D7:39}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

adb wait-for-device
adb logcat -c

echo "==> Launch AVRCP harness (Living Room TV = $BT_ADDR)"
adb shell am force-stop com.solar.launcher
sleep 1
adb shell am start -n com.solar.launcher/.MainActivity \
    --ez solar_adb_avrcp_harness true \
    --es solar_adb_bt_addr "$BT_ADDR"

echo "==> Waiting for harness (BT on + A2DP + playback)..."
sleep 35

echo "==> SolarAdbTest / SolarNetDbg logcat"
adb logcat -d -s SolarAdbTest SolarNetDbg SolarAvrcp 2>&1 | tail -50

echo "==> A2DP state"
adb shell dumpsys bluetooth_a2dp 2>&1 || true

echo "==> y1-track-info title sample"
adb shell "su -c 'dd if=/data/data/com.innioasis.y1/files/y1-track-info bs=1 skip=12 count=48 2>/dev/null'" 2>&1 | strings || true

if adb pull /storage/sdcard0/debug-fd0a2f.log "$REPO_ROOT/.cursor/debug-fd0a2f.log" 2>/dev/null; then
    echo "==> Pulled $REPO_ROOT/.cursor/debug-fd0a2f.log"
    tail -20 "$REPO_ROOT/.cursor/debug-fd0a2f.log"
fi

if adb logcat -d -s SolarAdbTest 2>&1 | grep -q "PASS playing_a2dp"; then
    echo "HARNESS PASS"
    echo "==> BT settings stress (A2DP must stay connected 35s)"
    adb shell am start -n com.solar.launcher/.MainActivity \
        --ez solar_adb_goto true --ei solar_adb_screen 5 \
        -f 0x20000000
    sleep 35
    adb logcat -d -s SolarNetDbg 2>&1 | grep -E "skip_discovery_a2dp|H-BT5" | tail -5 || true
    adb shell "dumpsys bluetooth_a2dp 2>&1 | grep 50:BA"
    if adb shell "dumpsys bluetooth_a2dp 2>&1" | grep -q "50:BA:02:58:D7:39 connected"; then
        echo "BT_STRESS PASS a2dp_stable_on_bt_screen"
        exit 0
    fi
    echo "BT_STRESS FAIL a2dp_dropped_on_bt_screen"
    exit 1
fi
echo "HARNESS FAIL — check logcat above"
exit 1
