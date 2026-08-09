#!/usr/bin/env bash
# 2026-07-20 — Launch API-17 AVD 480×800 (phone chrome smoke) WITHOUT family pin.
# Layman: starts a tall phone-sized emulator so Solar shows the click-wheel shell.
# Tech: same AVD machinery as run-y1-emulator.sh but 480×800 and no persist.solar.device_family.
# Expect chrome + wheel. Contrast: ./scripts/run-y1-emulator.sh must show NO chrome.
# Usage: ./scripts/run-phone-emulator.sh [--install] [--no-window] [--no-audio]
# 2026-07-20 — Audio ON by default (phone chrome needs audible playback smoke).
# Was: -no-audio like Y1/Y2/A5 lab scripts — Solar played but host heard silence.
# Reversal: pass --no-audio, or delete this script — phone chrome still runs on any non-Y1/A5 display.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
[[ -f "$ROOT/scripts/env.sh" ]] && source "$ROOT/scripts/env.sh" || true
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AVD_NAME="${SOLAR_PHONE_AVD:-solar_phone_480x800}"
SYS_IMG="system-images;android-17;default;x86"
EMU="$SDK/emulator/emulator"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
ADB="${ADB:-$SDK/platform-tools/adb}"
INSTALL=0
NO_WINDOW=0
NO_AUDIO=0
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=1 ;;
    --no-window) NO_WINDOW=1 ;;
    --no-audio) NO_AUDIO=1 ;;
  esac
done

echo "== Phone chrome emulator ($AVD_NAME) =="
if [[ ! -x "$EMU" ]]; then
  echo "emulator not found at $EMU" >&2
  exit 1
fi

if ! "$EMU" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
  echo "Creating AVD $AVD_NAME (480×800, API 17)…"
  if [[ ! -x "$AVDM" ]]; then
    echo "avdmanager not found; create AVD manually named $AVD_NAME" >&2
    exit 1
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SYS_IMG" >/dev/null 2>&1 || true
  echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$SYS_IMG" --force
  CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [[ -f "$CFG" ]]; then
    grep -q '^hw.lcd.width=' "$CFG" && sed -i 's/^hw.lcd.width=.*/hw.lcd.width=480/' "$CFG" \
      || echo 'hw.lcd.width=480' >> "$CFG"
    grep -q '^hw.lcd.height=' "$CFG" && sed -i 's/^hw.lcd.height=.*/hw.lcd.height=800/' "$CFG" \
      || echo 'hw.lcd.height=800' >> "$CFG"
    grep -q '^hw.lcd.density=' "$CFG" && sed -i 's/^hw.lcd.density=.*/hw.lcd.density=160/' "$CFG" \
      || echo 'hw.lcd.density=160' >> "$CFG"
    grep -q '^hw.keyboard=' "$CFG" && sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$CFG" \
      || echo 'hw.keyboard=yes' >> "$CFG"
    grep -q '^hw.ramSize=' "$CFG" && sed -i 's/^hw.ramSize=.*/hw.ramSize=1024/' "$CFG" \
      || echo 'hw.ramSize=1024' >> "$CFG"
    grep -q '^hw.cpu.ncore=' "$CFG" && sed -i 's/^hw.cpu.ncore=.*/hw.cpu.ncore=2/' "$CFG" \
      || echo 'hw.cpu.ncore=2' >> "$CFG"
    # Touch for the on-screen click wheel.
    grep -q '^hw.touchScreen=' "$CFG" && sed -i 's/^hw.touchScreen=.*/hw.touchScreen=yes/' "$CFG" \
      || echo 'hw.touchScreen=yes' >> "$CFG"
  fi
fi

# Host speakers: leave audio enabled unless --no-audio (quiet CI / shared desks).
EMU_ARGS=(-avd "$AVD_NAME" -no-snapshot-save -gpu swiftshader_indirect -memory 1024 -cores 2)
[[ "$NO_AUDIO" -eq 1 ]] && EMU_ARGS+=(-no-audio)
[[ "$NO_WINDOW" -eq 1 ]] && EMU_ARGS+=(-no-window)

if ! "$ADB" devices 2>/dev/null | grep -q emulator; then
  echo "Starting emulator…"
  "$EMU" "${EMU_ARGS[@]}" >/tmp/solar-phone-emulator.log 2>&1 &
  "$ADB" wait-for-device
  for i in $(seq 1 90); do
    booted="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
    sleep 2
  done
fi

# Target the emulator explicitly — a physical Y1/Y2/A5 may also be on adb.
EMU_SERIAL="$("$ADB" devices 2>/dev/null | awk '/emulator-[0-9]+/{print $1; exit}')" || EMU_SERIAL=""
export ANDROID_SERIAL="${EMU_SERIAL:-}"

# Critical: do NOT pin y1/y2/a5 — chrome activates on 480×800 without a family pin.
# Clear a leftover pin from a prior Y1 AVD session on this emulator instance.
"$ADB" shell "setprop persist.solar.device_family ''" 2>/dev/null || true
echo "persist.solar.device_family=$("$ADB" shell getprop persist.solar.device_family 2>/dev/null | tr -d '\r')"
echo "(empty pin expected — phone chrome should wrap Solar)"

if [[ "$INSTALL" -eq 1 ]]; then
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$APK" ]]; then
    (cd "$ROOT" && ./gradlew :app:assembleDebug) || exit 1
  fi
  # Data-preserving install: a plain install -r keeps prefs/databases/files.
  # ANDROID_SERIAL was exported above — every adb call targets this emulator.
  "$ROOT/scripts/install-preserve-data.sh" "$APK" || exit 1
  "$ADB" shell "setprop persist.solar.device_family ''" 2>/dev/null || true
  "$ADB" shell am force-stop com.solar.launcher || true
  "$ADB" shell am start -n com.solar.launcher/.MainActivity || true
fi

echo "Phone emulator ready. Confirm: Solar viewport + click wheel; (i) opens customize."
echo "No-chrome check: ./scripts/run-y1-emulator.sh (480×360 + y1 pin) must stay native."
echo "Log: /tmp/solar-phone-emulator.log"
