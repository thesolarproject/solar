#!/usr/bin/env bash
# Fast iteration install by default: update Solar in /data (no reboot).
# Pass --system to force legacy /system overwrite flow.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
MODE="${1:-fast}"

[[ -f "$APK" ]] || {
  echo "Missing $APK — run ./scripts/build.sh first" >&2
  exit 1
}

echo "== Waiting for device (120s) =="
if ! timeout 120 adb wait-for-device; then
  echo "No device — plug in Y1 with USB debugging enabled" >&2
  exit 1
fi
sleep 2

run_su() {
  adb shell "su -c '$*'" 2>/dev/null || adb shell "$*"
}

if [[ "$MODE" != "--system" ]]; then
  echo "== Fast iteration install (userdata overlay, no reboot) =="
  echo "== Installing with adb install -r -d =="
  if adb install -r -d "$APK"; then
    adb shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
    sleep 1
    adb shell am start -S -n com.solar.launcher/.MainActivity >/dev/null 2>&1 || true
    VER="$(aapt dump badging "$APK" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"
    echo "DONE: installed iteration APK (versionName=${VER:-unknown}) — launcher cold-started"
    exit 0
  fi
  echo "Fast install failed; retrying once with pm install via root..." >&2
  adb push "$APK" /data/local/tmp/solar-iteration.apk >/dev/null
  if run_su "pm install -r -d /data/local/tmp/solar-iteration.apk"; then
    adb shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
    sleep 1
    adb shell am start -S -n com.solar.launcher/.MainActivity >/dev/null 2>&1 || true
    echo "DONE: installed iteration APK via pm install — launcher cold-started"
    exit 0
  fi
  echo "ERROR: fast install failed. Run with --system for full /system reinstall." >&2
  exit 1
fi

echo "== Root + remount /system =="
adb root 2>/dev/null || true
sleep 1
adb remount 2>/dev/null || run_su "mount -o remount,rw /system" || true

echo "== Launcher packages (before) =="
adb shell pm list packages 2>/dev/null | grep -iE 'solar|launcher' || true

echo "== System APK dir =="
adb shell "ls -la /system/app/ 2>/dev/null" | grep -iE 'solar|launcher' || echo "(none matched)"

# User uninstall (data partition); ponytail: any non-Solar launcher package on device
while IFS= read -r pkg; do
  [[ -z "$pkg" ]] && continue
  if adb shell pm path "$pkg" 2>/dev/null | grep -q .; then
    echo "== pm uninstall $pkg =="
    adb shell pm uninstall "$pkg" 2>/dev/null || adb shell pm clear "$pkg" 2>/dev/null || true
  fi
done < <(adb shell pm list packages 2>/dev/null | sed 's/package://' | tr -d '\r' \
  | grep -iE 'launcher|innioasis' | grep -v '^com\.solar\.launcher$' || true)

echo "== Remove launcher APKs from /system/app =="
adb shell "ls /system/app/ 2>/dev/null" | tr -d '\r' | while read -r f; do
  [[ -z "$f" ]] && continue
  case "$f" in
    *[Ss]olar*|*[Ll]auncher*|*innioasis*|*[Yy]1*)
      echo "rm /system/app/$f"
      run_su "rm -rf /system/app/$f" || true
      ;;
  esac
done

echo "== Remove from /system/priv-app (if any) =="
adb shell "ls /system/priv-app/ 2>/dev/null" | tr -d '\r' | while read -r f; do
  [[ -z "$f" ]] && continue
  case "$f" in
    *[Ss]olar*|*[Ll]auncher*|*innioasis*|*[Yy]1*)
      echo "rm /system/priv-app/$f"
      run_su "rm -f /system/priv-app/$f" || true
      ;;
  esac
done

echo "== Install Solar system APK + TLS prep (Conscrypt JNI + modern CA roots) =="
# ponytail: Y1 PackageManager only scans flat /system/app/*.apk — JNI must live in /system/lib
TLS_STAGING="$(mktemp -d)"
trap 'rm -rf "$TLS_STAGING"' EXIT
chmod +x "$ROOT/scripts/stage-y1-system-prep.sh" "$ROOT/scripts/push-y1-system-prep.sh" "$ROOT/scripts/apply-y1-system-prep.sh"
"$ROOT/scripts/stage-y1-system-prep.sh" "$TLS_STAGING" "$APK" "$ROOT"

adb push "$APK" /system/app/com.solar.launcher.apk
run_su "chmod 644 /system/app/com.solar.launcher.apk"

if [[ "${SOLAR_SKIP_CACERTS:-0}" == "1" ]]; then
  echo "== Push Conscrypt only (SOLAR_SKIP_CACERTS=1) =="
  adb push "$TLS_STAGING/lib/libconscrypt_jni.so" /system/lib/libconscrypt_jni.so
  run_su "chmod 644 /system/lib/libconscrypt_jni.so"
else
  "$ROOT/scripts/push-y1-system-prep.sh" "$TLS_STAGING" || echo "WARN: TLS prep push failed" >&2
fi

echo "== After =="
adb shell "ls -la /system/app/com.solar.launcher.apk /system/lib/libconscrypt_jni.so"
adb shell pm list packages 2>/dev/null | grep -iE 'solar|launcher' || true

echo "== Rebooting =="
adb reboot
echo "DONE: Solar + TLS prep + boot init — device rebooting"
