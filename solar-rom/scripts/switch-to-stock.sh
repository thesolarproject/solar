#!/system/bin/sh
# 2026-07-08 — Disable job control so background pm/am children do not hold the script open.
# Reversal: remove set +m (script then waits for force-stop/disable children on exit).
set +m 2>/dev/null || true
# 2026-07-08 — Solar launcher switch that cooperates with helper HOME enforcement.
# Bare script (no args) returns to Solar. Settings pass --jj / --stock|--innioasis.
# Prefer solar-launcher-exec.sh (transition gates, competition disable, helper start).

# 2026-07-08 — Flag matrix for alternate HOME targets.
case "$1" in
    --jj|-jj)
        TARGET="jj"
        ;;
    # 2026-07-08 — Factory Innioasis HOME; bare "--stock" must never fall through to Solar.
    --stock|-stock|--innioasis|-innioasis)
        TARGET="stock"
        ;;
    --custom|-custom)
        TARGET="custom"
        ;;
    --solar|-solar|"")
        TARGET="solar"
        ;;
    *)
        # 2026-07-08 — Unknown flag → Solar so a typo never arms an alternate HOME by accident.
        TARGET="solar"
        ;;
esac

# 2026-07-08 — Prefer /data/data executor (Y1RomPrep seed); system copy may lag after adb stage.
# Reversal: /system/etc/solar first (ROM bake is then always authoritative).
EXEC=""
for _cand in \
    /data/data/solar-launcher-exec.sh \
    /system/etc/solar/solar-launcher-exec.sh \
    /data/data/com.solar.launcher/solar-launcher-exec.sh
do
    if [ -f "$_cand" ]; then
        EXEC="$_cand"
        break
    fi
done

if [ -n "$EXEC" ]; then
    sh "$EXEC" switch "$TARGET"
    exit $?
fi

# --- Legacy inline fallback (exec not staged yet) — must still honour enforcer contract ---
# 2026-07-08 — Was: enable/disable two pkgs + start MainActivity; fought helper HOME + watchdog.
# Now: arm applying/trans gates, keep Solar+helper enabled, disable competitors, start via helper.
# Reversal: restore pre-helper set_preferred_home that only toggled alt launchers + am start MainActivity.

SOLAR_PKG="com.solar.launcher"
JJ_PKG="com.themoon.y1"
INNIOASIS_Y1_PKG="com.innioasis.y1"
INNIOASIS_Y2_PKG="com.innioasis.y2"
HELPER_PKG="com.solar.launcher.homehelper"
SOLAR_ACTIVITY="com.solar.launcher/.MainActivity"
HELPER_HOME="com.solar.launcher.homehelper/.LauncherHomeActivity"
JJ_ACTIVITY="com.themoon.y1/.MainActivity"
SET_HOME_ACTION="com.solar.launcher.action.SET_PREFERRED_HOME"
HOME_RECEIVER="com.solar.launcher/.LauncherHomeReceiver"
HELPER_ENFORCER="com.solar.launcher.homehelper/.LauncherEnforcerService"
OVERLAY_KEEPALIVE="com.solar.launcher.action.OVERLAY_KEEPALIVE"
OVERLAY_SERVICE="com.solar.launcher/.SolarOverlayService"
COMPANION_OVERLAY="com.solar.launcher.globalcontext/.GlobalContextOverlayService"
PROP_HOME_TARGET="persist.solar.home.target"
PROP_HOME_COMPONENT="persist.solar.home.component"
PROP_JJ_HANDOFF="sys.solar.handoff.jj"
PROP_HOME_APPLYING="persist.solar.home.applying"
PROP_TRANSITION_UNTIL="sys.solar.launcher.trans_until"
TRANSITION_MS=8000

# 2026-07-08 — Pause PreferredLauncherEnforcer / helper watchdog while packages flip.
arm_transition() {
    /system/bin/setprop "$PROP_HOME_APPLYING" 1 2>/dev/null || true
    _now=$(cat /proc/uptime 2>/dev/null | awk '{print int($1*1000)}')
    [ -z "$_now" ] && _now=0
    /system/bin/setprop "$PROP_TRANSITION_UNTIL" "$((_now + TRANSITION_MS))" 2>/dev/null || true
}

clear_transition() {
    /system/bin/setprop "$PROP_HOME_APPLYING" 0 2>/dev/null || true
    /system/bin/setprop "$PROP_TRANSITION_UNTIL" 0 2>/dev/null || true
}

# 2026-07-08 — Wheel remap for JJ/stock when Solar process is not the writer.
sync_jj_handoff_flag() {
    case "$1" in
        jj|stock) /system/bin/setprop "$PROP_JJ_HANDOFF" 1  2>/dev/null || true;;
        *) /system/bin/setprop "$PROP_JJ_HANDOFF" 0  2>/dev/null || true;;
    esac
}

stock_activity() {
    _c=$(/system/bin/getprop "$PROP_HOME_COMPONENT")
    if [ -n "$_c" ]; then
        echo "$_c"
        return
    fi
    if pm path "$INNIOASIS_Y1_PKG" >/dev/null 2>&1; then
        echo "$INNIOASIS_Y1_PKG/.MainActivity"
        return
    fi
    echo "$INNIOASIS_Y2_PKG/.MainActivity"
}

# 2026-07-08 — Competition policy: Solar + helper always on; only the chosen alternate stays enabled.
# Never clear preferred helper HOME — PM pin stays on LauncherHomeActivity forever.
apply_competition() {
    _mode="$1"
    pm enable "$SOLAR_PKG" 2>/dev/null
    pm enable "$HELPER_PKG" 2>/dev/null
    case "$_mode" in
        jj)
            pm enable "$JJ_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y1_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y2_PKG" 2>/dev/null
            am force-stop "$INNIOASIS_Y1_PKG" >/dev/null 2>&1 &
            am force-stop "$INNIOASIS_Y2_PKG" >/dev/null 2>&1 &
            ;;
        stock)
            # 2026-07-08 — Enable only the installed family HOME; disable siblings as competitors.
            _stock=$(stock_activity)
            _stock_pkg="${_stock%%/*}"
            pm enable "$_stock_pkg" 2>/dev/null
            [ "$_stock_pkg" != "$INNIOASIS_Y1_PKG" ] && pm disable "$INNIOASIS_Y1_PKG" 2>/dev/null
            [ "$_stock_pkg" != "$INNIOASIS_Y2_PKG" ] && pm disable "$INNIOASIS_Y2_PKG" 2>/dev/null
            pm disable "$JJ_PKG" 2>/dev/null
            am force-stop "$JJ_PKG" >/dev/null 2>&1 &
            ;;
        custom)
            pm disable "$JJ_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y1_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y2_PKG" 2>/dev/null
            am force-stop "$JJ_PKG" >/dev/null 2>&1 &
            ;;
        *)
            _mode=solar
            pm disable "$JJ_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y1_PKG" 2>/dev/null
            pm disable "$INNIOASIS_Y2_PKG" 2>/dev/null
            am force-stop "$JJ_PKG" >/dev/null 2>&1 &
            am force-stop "$INNIOASIS_Y1_PKG" >/dev/null 2>&1 &
            am force-stop "$INNIOASIS_Y2_PKG" >/dev/null 2>&1 &
            ;;
    esac
    /system/bin/setprop "$PROP_HOME_TARGET" "$_mode" 2>/dev/null || true
    sync_jj_handoff_flag "$_mode"
    # 2026-07-08 — background broadcast; shell already owns props/pm.
    am broadcast -a "$SET_HOME_ACTION" -n "$HOME_RECEIVER" \
        --es target "$_mode" --ez shell_applied 1 --es source shell >/dev/null 2>&1 &
}

# 2026-07-08 — Same fire-and-forget overlay warm-up as solar-launcher-exec.
ensure_overlay_host() {
    am startservice -a "$OVERLAY_KEEPALIVE" -n "$OVERLAY_SERVICE" >/dev/null 2>&1 &
    am startservice -a com.solar.launcher.globalcontext.action.OVERLAY_KEEPALIVE \
        -n "$COMPANION_OVERLAY" >/dev/null 2>&1 &
    am startservice -n "$HELPER_ENFORCER" >/dev/null 2>&1 &
}

arm_transition
apply_competition "$TARGET"
ensure_overlay_host

case "$TARGET" in
    jj)
        am start -n "$JJ_ACTIVITY" 2>/dev/null
        ;;
    stock)
        am start -n "$(stock_activity)" 2>/dev/null
        ;;
    custom)
        _c=$(/system/bin/getprop "$PROP_HOME_COMPONENT")
        if [ -n "$_c" ]; then
            am start -n "$_c" 2>/dev/null
        else
            am start -n "$HELPER_HOME" 2>/dev/null || am start -n "$SOLAR_ACTIVITY" 2>/dev/null
        fi
        ;;
    *)
        # 2026-07-08 — Return via helper middle-man HOME; do not force-stop Solar (overlay host).
        am start -n "$HELPER_HOME" 2>/dev/null || am start -n "$SOLAR_ACTIVITY" 2>/dev/null
        ;;
esac

# 2026-07-08 — Integer settle (toybox); was sleep 0.25.
sleep 1
clear_transition
