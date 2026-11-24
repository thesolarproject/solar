#!/system/bin/sh
# 2026-07-05 — Copies staged JNI libs to /data/data/org.rockbox/lib/ (ROM + adb + APK paths).
# Stale libs after org.rockbox APK update → silent playback; sync runs on boot and Rockbox switch.
# When changing: staged path /system/etc/solar/rockbox-libs/ in build-rom.sh; RockboxLibSync.java caller.
# Reversal: skip sync; user must reinstall Rockbox or reflash ROM to restore codecs.
# Copy staged JNI libs from /system/etc/solar/rockbox-libs/ (built at ROM compile time).
APK=
APK_TAG=
LIBDIR=/data/data/org.rockbox/lib
STAGED=/system/etc/solar/rockbox-libs
MARKER=/data/data/org.rockbox/.solar_lib_apk_path

resolve_apk() {
    APK_TAG=$(pm path org.rockbox 2>/dev/null)
    APK=${APK_TAG#package:}
    [ -z "$APK" ] && APK=/system/app/org.rockbox.apk
    [ -z "$APK_TAG" ] && APK_TAG="package:$APK"
    [ -f "$APK" ] || return 1
    return 0
}

# Follow app-lib symlink (pm install) without deleting the link itself.
resolve_lib_target() {
    if [ -L "$LIBDIR" ]; then
        _link=$(ls -l "$LIBDIR" 2>/dev/null)
        _target=${_link#* -> }
        [ -n "$_target" ] && LIBDIR="$_target"
    fi
}

resolve_apk || exit 0
resolve_lib_target

needs_sync=0
if [ ! -f "$LIBDIR/librockbox.so" ]; then
    needs_sync=1
elif [ ! -f "$MARKER" ] || [ "$(cat "$MARKER" 2>/dev/null)" != "$APK_TAG" ]; then
    needs_sync=1
fi

[ "$needs_sync" -eq 1 ] || exit 0

if [ ! -d "$STAGED" ] || [ ! -f "$STAGED/librockbox.so" ]; then
    # 2026-07-06 — Y1 has no staged tree; unzip lib/armeabi from org.rockbox APK (OTA parity).
    if resolve_apk && [ -f "$APK" ]; then
        _tmp="/data/local/tmp/solar-rb-lib-extract"
        rm -rf "$_tmp" 2>/dev/null
        mkdir -p "$_tmp"
        if unzip -o -q "$APK" "lib/armeabi/*.so" -d "$_tmp" 2>/dev/null; then
            mkdir -p "$LIBDIR"
            rm -f "$LIBDIR"/*.so 2>/dev/null
            cp -a "$_tmp/lib/armeabi/." "$LIBDIR/"
            chmod 755 "$LIBDIR"/*.so 2>/dev/null
            echo "$APK_TAG" > "$MARKER"
            rm -rf "$_tmp"
            log -p i -t SolarRockbox "synced native libs from APK unzip $APK"
            exit 0
        fi
        rm -rf "$_tmp" 2>/dev/null
    fi
    log -p w -t SolarRockbox "missing staged libs at $STAGED"
    exit 1
fi

mkdir -p "$LIBDIR"
rm -f "$LIBDIR"/*.so 2>/dev/null
cp -a "$STAGED/." "$LIBDIR/"
chmod 755 "$LIBDIR"/*.so 2>/dev/null
echo "$APK_TAG" > "$MARKER"
log -p i -t SolarRockbox "synced native libs from $STAGED"
