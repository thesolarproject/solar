#!/usr/bin/env bash
# Remove third-party launchers from Y1 (root) and install Solar as /system/app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"

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

echo "== Root + remount /system =="
adb root 2>/dev/null || true
sleep 1
adb remount 2>/dev/null || run_su "mount -o remount,rw /system" || true

echo "== Launcher packages (before) =="
adb shell pm list packages 2>/dev/null | grep -iE 'slide|solar|jj|themoon|y1|launcher' || true

echo "== System APK dir =="
adb shell "ls -la /system/app/ 2>/dev/null" | grep -iE 'slide|solar|jj|launcher|themoon|y1' || echo "(none matched)"

# User uninstall (data partition installs)
for pkg in com.slide.launcher com.solar.launcher com.themoon.y1 com.jj.launcher com.innioasis.launcher; do
  if adb shell pm path "$pkg" 2>/dev/null | grep -q .; then
    echo "== pm uninstall $pkg =="
    adb shell pm uninstall "$pkg" 2>/dev/null || adb shell pm clear "$pkg" 2>/dev/null || true
  fi
done

echo "== Remove launcher APKs from /system/app =="
adb shell "ls /system/app/ 2>/dev/null" | tr -d '\r' | while read -r f; do
  [[ -z "$f" ]] && continue
  case "$f" in
    *[Ss]lide*|*[Ss]olar*|*[Jj][Jj]*|*[Ll]auncher*|*themoon*|*Y1*|*y1*)
      echo "rm /system/app/$f"
      run_su "rm -rf /system/app/$f" || true
      ;;
  esac
done

echo "== Remove from /system/priv-app (if any) =="
adb shell "ls /system/priv-app/ 2>/dev/null" | tr -d '\r' | while read -r f; do
  [[ -z "$f" ]] && continue
  case "$f" in
    *[Ss]lide*|*[Ss]olar*|*[Jj][Jj]*|*[Ll]auncher*|*themoon*|*Y1*|*y1*)
      echo "rm /system/priv-app/$f"
      run_su "rm -f /system/priv-app/$f" || true
      ;;
  esac
done

echo "== Install Solar system APK + Conscrypt native lib =="
# ponytail: Y1 PackageManager only scans flat /system/app/*.apk — JNI must live in /system/lib
CONSCRYPT_SO="$(mktemp)"
trap 'rm -f "$CONSCRYPT_SO"' EXIT
unzip -p "$APK" lib/armeabi-v7a/libconscrypt_jni.so > "$CONSCRYPT_SO"
[[ -s "$CONSCRYPT_SO" ]] || { echo "Missing libconscrypt_jni.so in APK" >&2; exit 1; }

adb push "$APK" /system/app/com.solar.launcher.apk
adb push "$CONSCRYPT_SO" /system/lib/libconscrypt_jni.so
run_su "chmod 644 /system/app/com.solar.launcher.apk /system/lib/libconscrypt_jni.so"

echo "== Modern CA roots (system trust for MediaPlayer + all apps) =="
if [[ "${SOLAR_SKIP_CACERTS:-0}" != "1" ]]; then
  "$ROOT/scripts/install_modern_cacerts.sh" --no-reboot || echo "WARN: cacerts install failed — run ./scripts/install_modern_cacerts.sh manually" >&2
else
  echo "Skipped (SOLAR_SKIP_CACERTS=1)"
fi

echo "== After =="
adb shell "ls -la /system/app/com.solar.launcher.apk /system/lib/libconscrypt_jni.so"
adb shell pm list packages 2>/dev/null | grep -iE 'slide|solar|jj|launcher' || true

echo "== Rebooting =="
adb reboot
echo "DONE: Solar at /system/app/com.solar.launcher.apk + /system/lib/libconscrypt_jni.so — device rebooting"
