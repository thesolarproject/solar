#!/system/bin/sh
# ponytail: Rockbox-y1 keeps codec .so under /data/data/org.rockbox/lib/ — must match org.rockbox.apk.
# After Solar ROM updates the system APK, stale libs break decode (silent / no playback).
APK=/system/app/org.rockbox.apk
LIBDIR=/data/data/org.rockbox/lib
MARKER="$LIBDIR/.apk_md5"

[ -f "$APK" ] || exit 0
APK_MD5=$(md5sum "$APK" 2>/dev/null | awk '{print $1}')
[ -n "$APK_MD5" ] || exit 0

needs_sync=0
if [ ! -f "$LIBDIR/librockbox.so" ]; then
    needs_sync=1
elif [ ! -f "$MARKER" ] || [ "$(cat "$MARKER" 2>/dev/null)" != "$APK_MD5" ]; then
    needs_sync=1
else
  APK_RB=$(unzip -p "$APK" lib/armeabi/librockbox.so 2>/dev/null | md5sum 2>/dev/null | awk '{print $1}')
  DEV_RB=$(md5sum "$LIBDIR/librockbox.so" 2>/dev/null | awk '{print $1}')
  [ -n "$APK_RB" ] && [ "$APK_RB" != "$DEV_RB" ] && needs_sync=1
fi

[ "$needs_sync" -eq 1 ] || exit 0

mkdir -p "$LIBDIR"
rm -rf "$LIBDIR"/*
cd "$LIBDIR" || exit 1
unzip -o "$APK" 'lib/armeabi/*' >/dev/null 2>&1 || exit 1
if [ -d lib/armeabi ]; then
    mv lib/armeabi/*.so . 2>/dev/null
    rmdir lib/armeabi 2>/dev/null
    rmdir lib 2>/dev/null
fi
chmod 755 "$LIBDIR"/*.so 2>/dev/null
echo "$APK_MD5" > "$MARKER"
log -p i -t SolarRockbox "synced native libs from $APK"
