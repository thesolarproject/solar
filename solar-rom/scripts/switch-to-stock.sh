#!/system/bin/sh
# Solar Y1 launcher switch — no full restart; unified Y1-Rockbox.kl + codec lib sync.
# Rockbox APK calls bare script → Solar. Solar calls with --rockbox → Rockbox.
# ponytail: enable + start target launcher, then force-stop + disable the other (no clash).

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

switch_to_rockbox() {
    echo "Enabling Rockbox"
    pm enable "$ROCKBOX_PKG"
    echo "Starting Rockbox"
    am start -n "$ROCKBOX_ACTIVITY"
    echo "Stopping and disabling Solar"
    am force-stop "$SOLAR_PKG"
    pm disable "$SOLAR_PKG"
}

switch_to_stock() {
    echo "Enabling Solar"
    pm enable "$SOLAR_PKG"
    echo "Starting Solar"
    am start -n "$SOLAR_ACTIVITY"
    echo "Stopping and disabling Rockbox"
    am force-stop "$ROCKBOX_PKG"
    pm disable "$ROCKBOX_PKG"
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
