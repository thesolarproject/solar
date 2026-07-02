#!/system/bin/sh
# One-shot: Solar ROM first boot or first Solar launch — disable Rockbox until user switches.
# ponytail: marker survives reboot; Switch to Rockbox enables org.rockbox via switch-to-stock.sh --rockbox.

SOLAR_PKG="com.solar.launcher"
ROCKBOX_PKG="org.rockbox"
MARKER="/data/data/.solar_rom_home_ready"

# Marker means we succeeded once — skip only when Rockbox is still disabled.
if [ -f "$MARKER" ] && pm list packages -d 2>/dev/null | grep -q "^package:${ROCKBOX_PKG}$"; then
    exit 0
fi
pm path "$ROCKBOX_PKG" >/dev/null 2>&1 || exit 0

try_disable() {
    pm enable "$SOLAR_PKG" 2>/dev/null
    am force-stop "$ROCKBOX_PKG" 2>/dev/null
    pm disable "$ROCKBOX_PKG" 2>/dev/null
}

is_disabled() {
    pm list packages -d 2>/dev/null | grep -q "^package:${ROCKBOX_PKG}$"
}

# ponytail: try immediately at init.d; pm may not be ready until boot progresses — retry without blocking 90s first.
i=0
while [ "$i" -lt 120 ]; do
    try_disable
    is_disabled && break
    sleep 1
    i=$((i + 1))
done

if ! is_disabled; then
    log -p w -t SolarRockbox "Rockbox disable failed; Solar app will retry on first launch"
    exit 1
fi

touch "$MARKER"
log -p i -t SolarRockbox "Rockbox disabled for Solar (one-shot); Switch to Rockbox re-enables"
