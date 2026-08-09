#!/usr/bin/env bash
# 2026-07-11 — Launch API-19 AVD pinned as Y2 (480×360) for Solar volume/HUD + input parity.
# Layman: starts a Y2-sized emulator so Solar volume menus work without real hardware.
# Tech: persist.solar.device_family=y2; prefers API 19 x86 if present else API 17.
# Usage: ./scripts/run-y2-emulator.sh [--install] [--no-window]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
[[ -f "$ROOT/scripts/env.sh" ]] && source "$ROOT/scripts/env.sh" || true
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AVD_NAME="${SOLAR_Y2_AVD:-solar_y2_480x360}"
SYS_IMG="system-images;android-19;default;x86"
# Fall back to API 17 if KitKat image missing — family prop still forces Y2 in Solar.
ALT_IMG="system-images;android-17;default;x86"
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

echo "== Y2 emulator ($AVD_NAME) =="
if [[ ! -x "$EMU" ]]; then
  echo "emulator not found at $EMU" >&2
  exit 1
fi

if ! "$EMU" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
  echo "Creating AVD $AVD_NAME (480×360)…"
  if [[ ! -x "$AVDM" ]]; then
    echo "avdmanager not found; create AVD manually named $AVD_NAME" >&2
    exit 1
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SYS_IMG" >/dev/null 2>&1 || true
  if ! echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$SYS_IMG" --force 2>/dev/null; then
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$ALT_IMG" >/dev/null 2>&1 || true
    echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$ALT_IMG" --force
  fi
  CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [[ -f "$CFG" ]]; then
    grep -q '^hw.lcd.width=' "$CFG" && sed -i 's/^hw.lcd.width=.*/hw.lcd.width=480/' "$CFG" \
      || echo 'hw.lcd.width=480' >> "$CFG"
    grep -q '^hw.lcd.height=' "$CFG" && sed -i 's/^hw.lcd.height=.*/hw.lcd.height=360/' "$CFG" \
      || echo 'hw.lcd.height=360' >> "$CFG"
    grep -q '^hw.lcd.density=' "$CFG" && sed -i 's/^hw.lcd.density=.*/hw.lcd.density=160/' "$CFG" \
      || echo 'hw.lcd.density=160' >> "$CFG"
    grep -q '^hw.keyboard=' "$CFG" && sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$CFG" \
      || echo 'hw.keyboard=yes' >> "$CFG"
    grep -q '^hw.ramSize=' "$CFG" && sed -i 's/^hw.ramSize=.*/hw.ramSize=1024/' "$CFG" \
      || echo 'hw.ramSize=1024' >> "$CFG"
    grep -q '^hw.cpu.ncore=' "$CFG" && sed -i 's/^hw.cpu.ncore=.*/hw.cpu.ncore=2/' "$CFG" \
      || echo 'hw.cpu.ncore=2' >> "$CFG"
  fi
fi

EMU_ARGS=(-avd "$AVD_NAME" -no-snapshot-save -gpu swiftshader_indirect -memory 1024 -cores 2 -no-audio)
[[ "$NO_WINDOW" -eq 1 ]] && EMU_ARGS+=(-no-window)

if ! "$ADB" devices 2>/dev/null | grep -q emulator; then
  echo "Starting emulator…"
  "$EMU" "${EMU_ARGS[@]}" >/tmp/solar-y2-emulator.log 2>&1 &
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

"$ADB" shell setprop persist.solar.device_family y2 2>/dev/null || true
echo "persist.solar.device_family=$("$ADB" shell getprop persist.solar.device_family 2>/dev/null | tr -d '\r')"

if [[ "$INSTALL" -eq 1 ]]; then
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$APK" ]]; then
    (cd "$ROOT" && ./gradlew :app:assembleDebug) || exit 1
  fi
  # Data-preserving install: a plain install -r keeps prefs/databases/files.
  # ANDROID_SERIAL was exported above — every adb call targets this emulator.
  "$ROOT/scripts/install-preserve-data.sh" "$APK" || exit 1
  "$ADB" shell setprop persist.solar.device_family y2 || true
  "$ADB" shell am force-stop com.solar.launcher || true
  "$ADB" shell am start -n com.solar.launcher/.MainActivity || true
fi

echo "Y2 emulator ready. Family pin forces overlay vol/lock chips on emulator."
echo "Log: /tmp/solar-y2-emulator.log"
