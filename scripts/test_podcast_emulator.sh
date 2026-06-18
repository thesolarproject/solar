#!/usr/bin/env bash
# Run podcast HTTPS instrumented tests on a 480x360 API 17 emulator.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

AVD_NAME="${SOLAR_TEST_AVD:-solar_y1_480x360}"
API="${SOLAR_TEST_API:-17}"
SYS_IMG="system-images;android-${API};default;x86"

echo "== Ensure emulator AVD: $AVD_NAME (${API}, 480x360) =="
if ! avdmanager list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
  sdkmanager --install "$SYS_IMG" >/dev/null 2>&1 || sdkmanager --install "$SYS_IMG"
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "$SYS_IMG" -d "4.7in WXGA" \
    -c 512M --force 2>/dev/null || true
  AVD_INI="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [[ -f "$AVD_INI" ]]; then
    sed -i 's/^hw.lcd.width=.*/hw.lcd.width=480/' "$AVD_INI" 2>/dev/null || true
    sed -i 's/^hw.lcd.height=.*/hw.lcd.height=360/' "$AVD_INI" 2>/dev/null || true
    grep -q '^hw.lcd.width=' "$AVD_INI" || echo 'hw.lcd.width=480' >> "$AVD_INI"
    grep -q '^hw.lcd.height=' "$AVD_INI" || echo 'hw.lcd.height=360' >> "$AVD_INI"
  fi
fi

echo "== Build debug APK + test APK =="
cd "$ROOT"
./gradlew assembleDebug assembleDebugAndroidTest

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "== Start emulator =="
adb devices | grep -q emulator || {
  emulator -avd "$AVD_NAME" -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
  adb wait-for-device
  for i in $(seq 1 90); do
    boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [[ "$boot" == "1" ]] && break
    sleep 2
  done
}

"$ROOT/scripts/ensure_emulator_network.sh"

echo "== Install + run device tests =="
adb install -r -d "$APK" >/dev/null
adb install -r -d "$TEST_APK" >/dev/null
adb shell am instrument -w -r \
  -e class com.solar.launcher.podcast.OpenRssClientDeviceTest,com.solar.launcher.HomeMenuScrollDeviceTest \
  com.solar.launcher.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/solar-instrument.log
grep -q "OK (2 tests)" /tmp/solar-instrument.log || grep -q "OK (1 test)" /tmp/solar-instrument.log || {
  echo "Instrumented tests failed — see /tmp/solar-instrument.log" >&2
  tail -20 /tmp/solar-instrument.log
  exit 1
}

echo "DONE: podcast device tests passed on emulator"
