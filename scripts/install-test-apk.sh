#!/usr/bin/env bash
# Install a Solar test APK without creating a /data/app overlay on rooted Y1/Y2.
# Usage: install-test-apk.sh [--serial SERIAL] [--no-reboot] [--no-launch] APK
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-}"
REBOOT=1
LAUNCH=1
APK=""

usage() {
  cat <<USAGE
Usage: $0 [--serial SERIAL] [--no-reboot] [--no-launch] APK

Rooted Y1/Y2 devices:
  Replaces /system/app/com.solar.launcher.apk, removes any /data/app overlay,
  reboots for Package Manager to rescan /system, and verifies system ownership.

Other devices:
  Uses the normal adb install -r/-d/-t flow.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial|-s)
      [[ $# -ge 2 ]] || { echo "ERROR: --serial requires a value" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --no-reboot) REBOOT=0; shift ;;
    --no-launch) LAUNCH=0; shift ;;
    --help|-h) usage; exit 0 ;;
    -*) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      [[ -z "$APK" ]] || { echo "ERROR: only one APK may be supplied" >&2; exit 2; }
      APK="$1"
      shift
      ;;
  esac
done

[[ -n "$APK" ]] || { usage >&2; exit 2; }
[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=( -s "$SERIAL" )

shell() { "${ADB[@]}" shell "$@"; }
su_shell() { shell su -c "$1"; }

model="$(shell getprop ro.product.model | tr -d '\r\n')"
model_lc="$(printf '%s' "$model" | tr '[:upper:]' '[:lower:]')"
hardware="$(shell getprop ro.hardware | tr -d '\r\n' | tr '[:upper:]' '[:lower:]')"
board="$(shell getprop ro.product.board | tr -d '\r\n' | tr '[:upper:]' '[:lower:]')"
cpu="$(shell cat /proc/cpuinfo 2>/dev/null | awk -F: '/^[Hh]ardware/ {gsub(/^ +/, "", $2); print tolower($2); exit}')"
is_y_device=0
if [[ "$model_lc" == *y1* || "$model_lc" == *y2* \
    || "$hardware" == *mt6572* || "$hardware" == *mt6582* \
    || "$board" == *mt6572* || "$board" == *mt6582* \
    || "$cpu" == *mt6572* || "$cpu" == *mt6582* ]]; then
  is_y_device=1
fi

system_apk="/system/app/com.solar.launcher.apk"
has_system_apk=0
shell "[ -f '$system_apk' ]" >/dev/null 2>&1 && has_system_apk=1
has_root=0
if su_shell 'id' 2>/dev/null | grep -q 'uid=0'; then
  has_root=1
fi

if [[ "$is_y_device" -eq 1 ]]; then
  [[ "$has_root" -eq 1 ]] || {
    echo "ERROR: $model is a Y device but su/root is unavailable; refusing user APK install" >&2
    exit 1
  }
  [[ "$has_system_apk" -eq 1 ]] || {
    echo "ERROR: $model has no $system_apk; refusing user APK install" >&2
    exit 1
  }
fi

if [[ "$is_y_device" -eq 1 && "$has_root" -eq 1 && "$has_system_apk" -eq 1 ]]; then
  remote="/data/local/tmp/solar-system.apk"
  echo "==> Replacing Solar system app on $model"
  shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
  "${ADB[@]}" push "$APK" "$remote"

  # Remove the user-installed update before Package Manager rescans /system.
  # Keep app data; only the APK overlay and obsolete oat artifacts are removed.
  su_shell "mount -o remount,rw /system"
  su_shell "rm -rf /data/app/com.solar.launcher* /data/app-lib/com.solar.launcher*"
  su_shell "cp -f '$remote' '$system_apk' && chmod 644 '$system_apk' && sync"
  su_shell "rm -f '$remote'"

  # KitKat system apps don't extract APK-embedded native libs — deploy the IJK
  # .so set to /system/lib so SoundTouch tempo match / video keep working on Y.
  # libc++_shared.so is only pushed when the system lacks one, so we never clobber
  # a different-version copy that other ROM components may link against. 2026-08-01
  ijk_lib_dir="$ROOT/app/src/main/jniLibs/armeabi-v7a"
  if [[ -d "$ijk_lib_dir" ]]; then
    for lib in libijkffmpeg.so libijkplayer.so libijksdl.so; do
      if [[ -f "$ijk_lib_dir/$lib" ]]; then
        "${ADB[@]}" push "$ijk_lib_dir/$lib" "/data/local/tmp/$lib" >/dev/null
        su_shell "cp -f /data/local/tmp/$lib /system/lib/$lib && chmod 644 /system/lib/$lib && rm -f /data/local/tmp/$lib"
      fi
    done
    if [[ -f "$ijk_lib_dir/libc++_shared.so" ]] \
        && ! shell "[ -f /system/lib/libc++_shared.so ]" >/dev/null 2>&1; then
      "${ADB[@]}" push "$ijk_lib_dir/libc++_shared.so" "/data/local/tmp/libc++_shared.so" >/dev/null
      su_shell "cp -f /data/local/tmp/libc++_shared.so /system/lib/libc++_shared.so && chmod 644 /system/lib/libc++_shared.so && rm -f /data/local/tmp/libc++_shared.so"
    fi
    su_shell "sync"
    echo "==> Deployed IJK native libs to /system/lib"
  fi

  if [[ "$REBOOT" -eq 1 ]]; then
    echo "==> Rebooting for system APK rescan"
    shell reboot
    "${ADB[@]}" wait-for-device
    for _ in $(seq 1 90); do
      booted="$(shell getprop sys.boot_completed | tr -d '\r\n' || true)"
      [[ "$booted" == "1" ]] && break
      sleep 1
    done
    [[ "${booted:-}" == "1" ]] || {
      echo "ERROR: device did not report sys.boot_completed=1" >&2
      exit 1
    }
  else
    echo "WARN: --no-reboot requested; Package Manager may still report the old APK until reboot"
  fi
else
  echo "==> Standard user APK install (non-Y device without system Solar APK)"
  sdk="$(shell getprop ro.build.version.sdk | tr -d '\r\n' || true)"
  flags=(-r -d -t)
  [[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 23 ]] && flags+=(-g)
  "${ADB[@]}" install "${flags[@]}" "$APK"
fi

if [[ "$LAUNCH" -eq 1 ]]; then
  shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
  shell monkey -p com.solar.launcher -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || shell am start -n com.solar.launcher/.MainActivity >/dev/null
fi

if [[ "$is_y_device" -eq 1 && "$has_root" -eq 1 && "$has_system_apk" -eq 1 ]]; then
  paths="$(shell pm path com.solar.launcher 2>/dev/null | tr -d '\r')"
  printf '%s\n' "$paths" | grep -q '^package:/system/app/com.solar.launcher.apk$' || {
    echo "ERROR: Solar is not registered from /system/app after replacement:" >&2
    printf '%s\n' "$paths" >&2
    exit 1
  }
  if printf '%s\n' "$paths" | grep -q '/data/app/com.solar.launcher'; then
    echo "ERROR: /data/app Solar overlay still exists:" >&2
    printf '%s\n' "$paths" >&2
    exit 1
  fi
fi

echo "==> Solar test APK install verified: model=$model apk=$APK"
