#!/usr/bin/env bash
# Smoke-check Solar Development messaging wiring on a connected Y1.
# Usage: ./scripts/test_solar_dev_messaging.sh
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
adb wait-for-device
adb logcat -c
(cd "$REPO_ROOT" && ./gradlew installDebug -q)
adb shell monkey -p com.solar.launcher -c android.intent.category.LAUNCHER 1
sleep 3
echo "--- SolarDev experiment default / class load ---"
adb logcat -d 2>/dev/null | grep -E "SolarDeveloper|SolarDiag|SolarDevPM" | tail -20 || true
echo "--- Unit tests (host) ---"
(cd "$REPO_ROOT" && ./gradlew :app:testDebugUnitTest \
  --tests "com.solar.launcher.soulseek.SolarDeveloperAccountsTest" \
  --tests "com.solar.launcher.soulseek.SolarDeveloperMessagingTest" \
  --tests "com.solar.launcher.soulseek.ReachIntroMessageTest" -q)
echo "OK: solar dev messaging checks complete"
