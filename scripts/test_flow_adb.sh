#!/usr/bin/env bash
# Flow carousel automated input — wheel scroll + fan-out geometry on connected Y1.
# Usage: ./scripts/test_flow_adb.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

adb wait-for-device
adb logcat -c

echo "==> Install debug APK"
(cd "$REPO_ROOT" && ./gradlew installDebug -q)

echo "==> Launch Flow carousel harness (file flag + monkey — HOME launcher strips am start extras)"
adb shell run-as com.solar.launcher touch files/adb_flow_carousel.flag
adb shell monkey -p com.solar.launcher -c android.intent.category.LAUNCHER 1

echo "==> Waiting for wheel probe (open Flow + 3 scroll steps)..."
sleep 18

echo "==> SolarAdbTest"
adb logcat -d -s SolarAdbTest:I 2>/dev/null | tail -20

if adb logcat -d -s SolarAdbTest 2>&1 | grep -q "PASS flow_carousel_wheel"; then
    echo "FLOW_HARNESS PASS"
    exit 0
fi

if adb logcat -d -s SolarAdbTest 2>&1 | grep -q "FAIL neighbor_peek_too_small"; then
    echo "FLOW_HARNESS FAIL — cover fan-out spacing still too tight"
    exit 1
fi

echo "FLOW_HARNESS FAIL — see SolarAdbTest logcat above"
exit 1
