#!/system/bin/sh
# 2026-07-06 — Every boot: re-apply saved effective HOME; PM preferred always Solar Home Helper.
# Reads persist.solar.home.target (solar, rockbox, or jj), broadcasts to Solar for PM pin,
# and keeps Solar's overlay service warm so the global quick menu works over alternate HOME apps.

SOLAR_PKG="com.solar.launcher"
ROCKBOX_PKG="org.rockbox"
JJ_PKG="com.themoon.y1"
# 2026-07-08 — Factory Innioasis HOME packages (Y1 hardware ships y1; Y2 ships y2).
INNIOASIS_Y1_PKG="com.innioasis.y1"
INNIOASIS_Y2_PKG="com.innioasis.y2"
HELPER_PKG="com.solar.launcher.homehelper"

# 2026-10-05 — Is a package installed? Plain-text grep instead of pm (Dalvik VM, ~1 s on MT6572).
# Layman: don't spend seconds enabling/disabling apps that aren't there (Rockbox/JJ are optional).
# Unreadable list → pm path (old cost, same answer). Reversal: call pm path directly again.
pkg_installed() {
    if [ -r /data/system/packages.list ]; then
        grep -q "^$1 " /data/system/packages.list
    else
        pm path "$1" >/dev/null 2>&1
    fi
}
PROP_HOME_TARGET="persist.solar.home.target"
PROP_HOME_COMPONENT="persist.solar.home.component"
# 2026-07-08 — Wheel remap flag read by the JJ/Innioasis Xposed shim (see JjInputHooks).
PROP_JJ_HANDOFF="sys.solar.handoff.jj"
SET_HOME_ACTION="com.solar.launcher.action.SET_PREFERRED_HOME"
HOME_RECEIVER="com.solar.launcher/.LauncherHomeReceiver"
OVERLAY_KEEPALIVE="com.solar.launcher.action.OVERLAY_KEEPALIVE"
OVERLAY_SERVICE="com.solar.launcher/.SolarOverlayService"
WATCHDOG_SERVICE="com.solar.launcher/.LauncherWatchdogService"
FIRST_BOOT_MARKER="/data/data/.solar_rom_home_ready"

ensure_helper_registered() {
    if [ -f /system/app/SolarHomeHelper.apk ] && ! pm path "$HELPER_PKG" >/dev/null 2>&1; then
        if ! pm install -r /system/app/SolarHomeHelper.apk >/dev/null 2>&1; then
            log -p e -t SolarHome "pm install SolarHomeHelper failed — run platform repair"
        fi
    fi
}

ensure_rockbox_registered() {
    if [ -f /system/app/org.rockbox.apk ] && ! pm path "$ROCKBOX_PKG" >/dev/null 2>&1; then
        if ! pm install -r /system/app/org.rockbox.apk >/dev/null 2>&1; then
            log -p e -t SolarHome "pm install org.rockbox failed — reflash Solar Y2 ROM"
        fi
    fi
}

ensure_jj_registered() {
    if [ -f /system/app/com.themoon.y1.apk ] && ! pm path "$JJ_PKG" >/dev/null 2>&1; then
        if ! pm install -r /system/app/com.themoon.y1.apk >/dev/null 2>&1; then
            log -p e -t SolarHome "pm install JJ failed — install from Settings when online"
        fi
    fi
}

ensure_notpipe_registered() {
    NOTPIPE_PKG="io.github.gohoski.notpipe"
    if [ -f /system/app/io.github.gohoski.notpipe.apk ] && ! pm path "$NOTPIPE_PKG" >/dev/null 2>&1; then
        if ! pm install -r /system/app/io.github.gohoski.notpipe.apk >/dev/null 2>&1; then
            log -p e -t SolarHome "pm install notPipe failed — run platform repair in Settings"
        fi
    fi
}

# 2026-07-08 — Now honours stock (factory Innioasis HOME) and custom targets.
# Previously coerced stock/custom to solar every boot, silently undoing the user's launcher pick.
# Reversal: restore rockbox|jj|*→solar case list.
read_home_target() {
    TARGET="$(getprop "$PROP_HOME_TARGET")"
    case "$TARGET" in
        rockbox) echo rockbox ;;
        jj) echo jj ;;
        stock) echo stock ;;
        custom) echo custom ;;
        *) echo solar ;;
    esac
}

# 2026-07-08 — Pick whichever factory launcher is installed (persist component wins).
# Layman: find the built-in Innioasis home app on this device family.
stock_pkg() {
    _c="$(getprop "$PROP_HOME_COMPONENT")"
    if [ -n "$_c" ]; then
        echo "${_c%%/*}"
        return
    fi
    if pm path "$INNIOASIS_Y1_PKG" >/dev/null 2>&1; then
        echo "$INNIOASIS_Y1_PKG"
        return
    fi
    echo "$INNIOASIS_Y2_PKG"
}

# 2026-07-08 — Root-side arming of the JJ/Innioasis wheel remap at boot.
# Layman: tell the system "translate wheel keys" before any app (or Solar) starts.
# Technical: JJ + factory launchers expect DPAD 21/22; canonical .kl emits MEDIA 126/127.
# Setting sys.solar.handoff.jj here means the Xposed in-process shim works with zero
# dependency on the Solar app process (which may be disabled, crashed, or LMK-killed).
# Reversal: delete this function — arming falls back to Solar's ExternalInputHandoff only.
sync_jj_handoff_flag() {
    case "$1" in
        jj|stock) setprop "$PROP_JJ_HANDOFF" 1 ;;
        *) setprop "$PROP_JJ_HANDOFF" 0 ;;
    esac
}

apply_home_target() {
    TARGET="$1"
    pkg_installed "$SOLAR_PKG" && pm enable "$SOLAR_PKG" 2>/dev/null
    pkg_installed "$HELPER_PKG" && pm enable "$HELPER_PKG" 2>/dev/null
    case "$TARGET" in
        rockbox)
            if pkg_installed "$ROCKBOX_PKG"; then
                pkg_installed "$ROCKBOX_PKG" && pm enable "$ROCKBOX_PKG" 2>/dev/null
                pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" rockbox
            else
                TARGET=solar
                pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
                pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" solar
            fi
            ;;
        jj)
            if pkg_installed "$JJ_PKG"; then
                pkg_installed "$JJ_PKG" && pm enable "$JJ_PKG" 2>/dev/null
                pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" jj
            else
                TARGET=solar
                pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
                pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" solar
            fi
            ;;
        # 2026-07-08 — Factory Innioasis HOME: enable it, park Rockbox/JJ, keep target.
        # Reversal: delete this case — stock fell through to solar (launcher pick lost on boot).
        stock)
            _stock="$(stock_pkg)"
            if pkg_installed "$_stock"; then
                pkg_installed "$_stock" && pm enable "$_stock" 2>/dev/null
                pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
                pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" stock
            else
                TARGET=solar
                pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
                pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
                setprop "$PROP_HOME_TARGET" solar
            fi
            ;;
        # 2026-07-08 — Custom PM-discovered HOME: trust persisted component; park known alternates.
        custom)
            pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
            pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
            setprop "$PROP_HOME_TARGET" custom
            ;;
        *)
            TARGET=solar
            pkg_installed "$ROCKBOX_PKG" && pm disable "$ROCKBOX_PKG" 2>/dev/null
            pkg_installed "$JJ_PKG" && pm disable "$JJ_PKG" 2>/dev/null
            setprop "$PROP_HOME_TARGET" solar
            ;;
    esac
    # 2026-07-08 — Arm/clear the wheel-remap flag for the final target (Solar-independent).
    sync_jj_handoff_flag "$TARGET"
    # 2026-07-08 — shell_applied: boot already did pm/props; avoid nested su on broadcast.
    am broadcast -a "$SET_HOME_ACTION" -n "$HOME_RECEIVER" \
        --es target "$TARGET" --ez shell_applied 1 --es source shell
    sleep 1
}

ensure_overlay_host() {
    am startservice -a "$OVERLAY_KEEPALIVE" -n "$OVERLAY_SERVICE" 2>/dev/null
    am startservice -n com.solar.launcher.homehelper/.LauncherEnforcerService 2>/dev/null
    am startservice -n com.solar.launcher.globalcontext/.GlobalInputCoordinatorService 2>/dev/null
    if [ -f /system/etc/solar/solar-platform-daemon.sh ]; then
        sh /system/etc/solar/solar-platform-daemon.sh &
    fi
}

# 2026-07-19 — Do not pm-install Rockbox/JJ from system APK (Solar-only).
# Was: ensure_rockbox_registered; ensure_jj_registered. Reversal: restore those calls.
ensure_notpipe_registered
ensure_helper_registered

i=0
while [ "$i" -lt 45 ]; do
    pm path "$SOLAR_PKG" >/dev/null 2>&1 && break
    sleep 1
    i=$((i + 1))
done
pm path "$SOLAR_PKG" >/dev/null 2>&1 || exit 0

TARGET="$(read_home_target)"

if [ ! -f "$FIRST_BOOT_MARKER" ]; then
    if [ -z "$(getprop "$PROP_HOME_TARGET")" ]; then
        TARGET=solar
    fi
    touch "$FIRST_BOOT_MARKER"
    log -p i -t SolarHome "first boot HOME target=$TARGET"
fi

apply_home_target "$TARGET"
ensure_overlay_host
log -p i -t SolarHome "boot HOME applied target=$TARGET"
