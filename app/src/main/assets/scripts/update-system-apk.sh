#!/system/bin/sh
# Root: install or downgrade Solar at /system/app (PackageManager cannot downgrade system apps).
# Usage: update-system-apk.sh /path/to/downloaded.apk
APK="$1"
DEST="/system/app/com.solar.launcher.apk"
[ -n "$APK" ] && [ -f "$APK" ] || exit 1
mount -o remount,rw /system || exit 1
cp "$APK" "$DEST" || exit 1
chmod 644 "$DEST" || exit 1
sync
touch /data/local/tmp/solar-ota-pending-reboot 2>/dev/null || true
exit 0
