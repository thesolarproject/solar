#!/system/bin/sh
# Solar Y1 launcher switch — no full restart; unified Y1-Rockbox.kl + codec lib sync.
# Rockbox APK calls bare script → Solar. Solar calls with --rockbox → Rockbox.
# ponytail: disable outgoing launcher before starting incoming — no dual-HOME race.

SOLAR_PKG="com.solar.launcher"
ROCKBOX_PKG="org.rockbox"
SOLAR_ACTIVITY="com.solar.launcher/.MainActivity"
ROCKBOX_ACTIVITY="org.rockbox/.RockboxActivity"

case "$1" in
    --rockbox|-rockbox)
        TARGET="rockbox"
        ;;
    *)
        TARGET="stock"
        ;;
esac

verify_rockbox_disabled() {
    pm list packages -d 2>/dev/null | grep -q "^package:${ROCKBOX_PKG}$"
}

verify_solar_disabled() {
    pm list packages -d 2>/dev/null | grep -q "^package:${SOLAR_PKG}$"
}

switch_to_rockbox() {
    echo "Disabling Solar"
    am force-stop "$SOLAR_PKG"
    pm disable "$SOLAR_PKG"
    echo "Enabling Rockbox"
    pm enable "$ROCKBOX_PKG"
    echo "Starting Rockbox"
    am start -n "$ROCKBOX_ACTIVITY"
    if ! verify_solar_disabled; then
        pm disable "$SOLAR_PKG" 2>/dev/null
        log -p w -t SolarRockbox "Solar still enabled after switch to Rockbox — retried disable"
    fi
}

switch_to_stock() {
    if [ -f /system/etc/solar/sync-y1-keymap.sh ]; then
        sh /system/etc/solar/sync-y1-keymap.sh
    fi
    echo "Disabling Rockbox"
    i=0
    while [ "$i" -lt 30 ]; do
        am force-stop "$ROCKBOX_PKG" 2>/dev/null
        pm disable "$ROCKBOX_PKG" 2>/dev/null
        verify_rockbox_disabled && break
        sleep 1
        i=$((i + 1))
    done
    if ! verify_rockbox_disabled; then
        log -p e -t SolarRockbox "Rockbox still enabled — refusing to enable Solar"
        exit 1
    fi
    echo "Enabling Solar"
    pm enable "$SOLAR_PKG"
    echo "Starting Solar"
    am start -n "$SOLAR_ACTIVITY"
}

if [ "$TARGET" = "rockbox" ]; then
    if [ -f /system/etc/solar/sync-y1-keymap.sh ]; then
        sh /system/etc/solar/sync-y1-keymap.sh
    fi
    if [ -f /system/etc/solar/sync-rockbox-libs.sh ]; then
        sh /system/etc/solar/sync-rockbox-libs.sh
    fi
    switch_to_rockbox
else
    switch_to_stock
fi
