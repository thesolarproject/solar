#!/usr/bin/env bash
# 2026-07-11 — Create/launch a 240×320 API-17 AVD for Timmkoo A5 Solar testing.
# Layman: starts a tiny phone-sized emulator so we can try touch + portrait Solar.
# Tech: avdmanager + emulator; pins persist.solar.device_family=a5 for DeviceFeatures.
# Usage: ./scripts/run-a5-emulator.sh [--install] [--no-window]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
[[ -f "$ROOT/scripts/env.sh" ]] && source "$ROOT/scripts/env.sh" || true
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AVD_NAME="${SOLAR_A5_AVD:-solar_a5_240x320}"
SYS_IMG="system-images;android-17;default;x86"
EMU="$SDK/emulator/emulator"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
ADB="${ADB:-$SDK/platform-tools/adb}"
INSTALL=0
NO_WINDOW=0
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=1 ;;
    --no-window) NO_WINDOW=1 ;;
  esac
done

echo "== A5 emulator ($AVD_NAME) =="
if [[ ! -x "$EMU" ]]; then
  echo "emulator not found at $EMU" >&2
  exit 1
fi

if ! "$EMU" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
  echo "Creating AVD $AVD_NAME (240×320, API 17)…"
  if [[ ! -x "$AVDM" ]]; then
    echo "avdmanager not found; create AVD manually named $AVD_NAME" >&2
    exit 1
  fi
  # Package may already be installed; ignore failure.
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SYS_IMG" >/dev/null 2>&1 || true
  echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$SYS_IMG" -d "2.7in QVGA" --force \
    || echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$SYS_IMG" --force
  CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [[ -f "$CFG" ]]; then
    # Force 240×320 portrait (QVGA).
    grep -q '^hw.lcd.width=' "$CFG" && sed -i 's/^hw.lcd.width=.*/hw.lcd.width=240/' "$CFG" \
      || echo 'hw.lcd.width=240' >> "$CFG"
    grep -q '^hw.lcd.height=' "$CFG" && sed -i 's/^hw.lcd.height=.*/hw.lcd.height=320/' "$CFG" \
      || echo 'hw.lcd.height=320' >> "$CFG"
    grep -q '^hw.lcd.density=' "$CFG" && sed -i 's/^hw.lcd.density=.*/hw.lcd.density=120/' "$CFG" \
      || echo 'hw.lcd.density=120' >> "$CFG"
    grep -q '^hw.keyboard=' "$CFG" && sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$CFG" \
      || echo 'hw.keyboard=yes' >> "$CFG"
    # Keep RAM modest — Deck/Steam hosts often sit near OOM with 2G guests.
    grep -q '^hw.ramSize=' "$CFG" && sed -i 's/^hw.ramSize=.*/hw.ramSize=1024/' "$CFG" \
      || echo 'hw.ramSize=1024' >> "$CFG"
    grep -q '^hw.cpu.ncore=' "$CFG" && sed -i 's/^hw.cpu.ncore=.*/hw.cpu.ncore=2/' "$CFG" \
      || echo 'hw.cpu.ncore=2' >> "$CFG"
  fi
fi

EMU_ARGS=(-avd "$AVD_NAME" -no-snapshot-save -gpu swiftshader_indirect -memory 1024 -cores 2 -no-audio)
[[ "$NO_WINDOW" -eq 1 ]] && EMU_ARGS+=(-no-window)

# Launch if not already running an A5-sized emulator.
if ! "$ADB" devices 2>/dev/null | grep -q emulator; then
  echo "Starting emulator…"
  "$EMU" "${EMU_ARGS[@]}" >/tmp/solar-a5-emulator.log 2>&1 &
  echo "Waiting for boot…"
  "$ADB" wait-for-device
  # boot_completed can take a while on API 17 ARM.
  for i in $(seq 1 90); do
    booted="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
    sleep 2
  done
fi

# Target the emulator explicitly — a physical Y1/Y2/A5 may also be on adb.
EMU_SERIAL="$("$ADB" devices 2>/dev/null | awk '/emulator-[0-9]+/{print $1; exit}')" || EMU_SERIAL=""
export ANDROID_SERIAL="${EMU_SERIAL:-}"

# Pin family so Solar treats the AVD as A5 even without Timmkoo build props.
"$ADB" shell setprop persist.solar.device_family a5 2>/dev/null \
  || "$ADB" shell "setprop persist.solar.device_family a5" || true
echo "persist.solar.device_family=$("$ADB" shell getprop persist.solar.device_family 2>/dev/null | tr -d '\r')"

if [[ "$INSTALL" -eq 1 ]]; then
  echo "Installing debug APK (data-preserving — no uninstall, so prefs/downloads survive)…"
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$APK" ]]; then
    (cd "$ROOT" && ./gradlew :app:assembleDebug) || exit 1
  fi
  AAPT=$(ls -d "${SDK}/build-tools/"*/aapt 2>/dev/null | sort -V | tail -1 || true)
  if [[ -n "$AAPT" ]]; then
    "$AAPT" dump badging "$APK" 2>/dev/null | grep -E 'versionName|versionCode' | head -2 || true
  fi
  # Data-preserving install: a plain install -r keeps prefs/databases/files.
  # ANDROID_SERIAL was exported above — every adb call targets this emulator.
  "$ROOT/scripts/install-preserve-data.sh" "$APK" || {
    echo "install failed; try: ./gradlew :app:installDebug" >&2
    exit 1
  }
  "$ADB" shell setprop persist.solar.device_family a5 || true
  "$ADB" shell am force-stop com.solar.launcher || true
  "$ADB" shell am start -n com.solar.launcher/.MainActivity || true
  echo "Installed + launched. Confirm versionName via: adb shell dumpsys package com.solar.launcher | grep versionName"
fi

echo "A5 emulator ready. Smoke: menus (touch+keys), Flow flick, NP tap/swipe/hold/dismiss."
echo "Log: /tmp/solar-a5-emulator.log"
