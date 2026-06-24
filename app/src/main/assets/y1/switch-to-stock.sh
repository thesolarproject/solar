#!/system/bin/sh
# Solar Y1 launcher switch — no reboot, no keylayout swap (unified Rockbox.kl).
# Rockbox APK calls bare script → Solar. Solar calls with --rockbox → Rockbox.

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
    echo "Disabling Solar"
    pm disable "$SOLAR_PKG"
    am force-stop "$SOLAR_PKG"
    am start -n "$ROCKBOX_ACTIVITY"
}

switch_to_stock() {
    echo "Enabling Solar"
    pm enable "$SOLAR_PKG"
    echo "Disabling Rockbox"
    pm disable "$ROCKBOX_PKG"
    am force-stop "$ROCKBOX_PKG"
    am start -n "$SOLAR_ACTIVITY"
}

if [ "$TARGET" = "rockbox" ]; then
    switch_to_rockbox
else
    switch_to_stock
fi
