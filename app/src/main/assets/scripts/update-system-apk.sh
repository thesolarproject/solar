#!/system/bin/sh
# Root: replace Solar system APKs without touching app data.
# Usage: update-system-apk.sh LAUNCHER.apk [KEYBOARD.apk] [QUICKMENU.apk]
set -e
LAUNCHER="$1"
KEYBOARD="$2"
QUICKMENU="$3"
[ -n "$LAUNCHER" ] && [ -f "$LAUNCHER" ] || exit 1
mount -o remount,rw /system 2>/dev/null || true
cp "$LAUNCHER" /system/app/com.solar.launcher.apk || exit 1
chmod 644 /system/app/com.solar.launcher.apk || exit 1
if [ -n "$KEYBOARD" ] && [ -f "$KEYBOARD" ]; then
  cp "$KEYBOARD" /system/app/com.solar.keyboard.apk || exit 1
  chmod 644 /system/app/com.solar.keyboard.apk || exit 1
fi
if [ -n "$QUICKMENU" ] && [ -f "$QUICKMENU" ]; then
  cp "$QUICKMENU" /system/app/com.solar.quickmenu.apk || exit 1
  chmod 644 /system/app/com.solar.quickmenu.apk || exit 1
fi
sync
reboot
