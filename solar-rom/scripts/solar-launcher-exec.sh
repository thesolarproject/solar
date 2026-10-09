#!/system/bin/sh
# 2026-07-06 — Root executor for HOME switch, restart-active, and competitor disable.
# Layman: one script does pm disable/enable + force-stop so Java and Solar stay thin clients.
# Technical: subcommands called from SolarHomeHelper + switch-to-stock.sh delegate.
# Reversal: restore inline switch-to-stock.sh pm logic only.

SOLAR_PKG="com.solar.launcher"
ROCKBOX_PKG="org.rockbox"
JJ_PKG="com.themoon.y1"
HELPER_PKG="com.solar.launcher.homehelper"
SOLAR_ACTIVITY="com.solar.launcher/.MainActivity"
ROCKBOX_ACTIVITY="org.rockbox/.RockboxActivity"
JJ_ACTIVITY="com.themoon.y1/.MainActivity"
SET_HOME_ACTION="com.solar.launcher.action.SET_PREFERRED_HOME"
HOME_RECEIVER="com.solar.launcher/.LauncherHomeReceiver"
APPLY_HOME_ACTION="com.solar.launcher.homehelper.action.APPLY_HOME_TARGET"
HELPER_ENFORCER="com.solar.launcher.homehelper/.LauncherEnforcerService"
PROP_HOME_TARGET="persist.solar.home.target"
PROP_HOME_COMPONENT="persist.solar.home.component"
PROP_HOME_LAUNCHER_PKGS="persist.solar.home.launch_pkgs"
PROP_HOME_APPLYING="persist.solar.home.applying"
PROP_TRANSITION_UNTIL="sys.solar.launcher.trans_until"
PROP_PENDING_KILL="sys.solar.launcher.pending_kill"
PROP_KILL_REASON="sys.solar.launcher.kill_reason"
PROP_PENDING_KILL_UNTIL="sys.solar.launcher.kill_until"
PENDING_KILL_TTL_MS=12000
TRANSITION_MS=8000
OVERLAY_KEEPALIVE="com.solar.launcher.action.OVERLAY_KEEPALIVE"
OVERLAY_SERVICE="com.solar.launcher/.SolarOverlayService"
COMPANION_OVERLAY="com.solar.launcher.globalcontext/.GlobalContextOverlayService"
PLATFORM_DAEMON="/system/etc/solar/solar-platform-daemon.sh"

# #region agent log
DEBUG_LOG="/storage/sdcard0/solar/debug-d68c5c.log"
debug_log() {
    _hyp="$1"; _loc="$2"; _msg="$3"; _extra="$4"
    _ts=$(date +%s)000
    mkdir -p /storage/sdcard0/solar 2>/dev/null
    printf '{"sessionId":"d68c5c","hypothesisId":"%s","location":"%s","message":"%s","data":{"extra":"%s"},"timestamp":%s}\n' \
        "$_hyp" "$_loc" "$_msg" "$_extra" >> "$DEBUG_LOG" 2>/dev/null || true
}
# #endregion

arm_transition() {
    setprop "$PROP_HOME_APPLYING" 1
    _now=$(cat /proc/uptime 2>/dev/null | awk '{print int($1*1000)}')
    if [ -z "$_now" ]; then _now=0; fi
    _until=$((_now + TRANSITION_MS))
    setprop "$PROP_TRANSITION_UNTIL" "$_until"
}

clear_transition() {
    setprop "$PROP_HOME_APPLYING" 0
    setprop "$PROP_TRANSITION_UNTIL" 0
    clear_pending_kill
}

# 2026-07-14 — Arm pending_kill + kill_until (≤31-char props). Hardcoded pending_kill_until
# was 37 chars → setprop rejects → TTL never armed → crash UI can stay suppressed after a switch.
# Reversal: literal pending_kill_until writes (fails Android prop name limit).
announce_pending_kill() {
    _p="$1"
    _reason="$2"
    [ -n "$_p" ] || return
    setprop "$PROP_PENDING_KILL" "$_p"
    setprop "$PROP_KILL_REASON" "${_reason:-switch}"
    _now=$(cat /proc/uptime 2>/dev/null | awk '{print int($1*1000)}')
    [ -z "$_now" ] && _now=0
    setprop "$PROP_PENDING_KILL_UNTIL" "$((_now + PENDING_KILL_TTL_MS))"
}

# 2026-07-14 — Clear the same short props LauncherErrorRecoveryPolicy reads.
clear_pending_kill() {
    setprop "$PROP_PENDING_KILL" ""
    setprop "$PROP_KILL_REASON" ""
    setprop "$PROP_PENDING_KILL_UNTIL" 0
}

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

force_stop_pkg() {
    _p="$1"
    _reason="$2"
    [ -n "$_p" ] || return
    pkg_installed "$_p" || return
    [ -n "$_reason" ] && announce_pending_kill "$_p" "$_reason"
    am force-stop "$_p" 2>/dev/null
}

ensure_overlay_host() {
    am startservice -a "$OVERLAY_KEEPALIVE" -n "$OVERLAY_SERVICE" 2>/dev/null
    am startservice -a com.solar.launcher.globalcontext.action.OVERLAY_KEEPALIVE \
        -n "$COMPANION_OVERLAY" 2>/dev/null
    am startservice -n "$HELPER_ENFORCER" 2>/dev/null
}

pm_disable_pkg() {
    _p="$1"
    [ -n "$_p" ] && pkg_installed "$_p" && pm disable "$_p" 2>/dev/null
}

pm_enable_pkg() {
    _p="$1"
    [ -n "$_p" ] && pkg_installed "$_p" && pm enable "$_p" 2>/dev/null
}

read_target() {
    getprop "$PROP_HOME_TARGET" solar
}

activity_for_target() {
    case "$1" in
        rockbox) echo "$ROCKBOX_ACTIVITY" ;;
        jj) echo "$JJ_ACTIVITY" ;;
        custom)
            _c=$(getprop "$PROP_HOME_COMPONENT")
            if [ -n "$_c" ]; then echo "$_c"; else echo "$SOLAR_ACTIVITY"; fi
            ;;
        *) echo "$SOLAR_ACTIVITY" ;;
    esac
}

pkg_for_target() {
    case "$1" in
        rockbox) echo "$ROCKBOX_PKG" ;;
        jj) echo "$JJ_PKG" ;;
        custom)
            _c=$(getprop "$PROP_HOME_COMPONENT")
            _pkg="${_c%%/*}"
            if [ -n "$_pkg" ]; then echo "$_pkg"; else echo "$SOLAR_PKG"; fi
            ;;
        *) echo "$SOLAR_PKG" ;;
    esac
}

# 2026-07-07 — PM-discovered HOME packages (comma-separated) — disable all except active + platform.
disable_competitors_for() {
    _t="$1"
    _active=$(pkg_for_target "$_t")
    pm_enable_pkg "$SOLAR_PKG"
    pm_enable_pkg "$HELPER_PKG"
    pm_enable_pkg "$_active"
    
    # Always explicitly disable inactive alternate launchers
    [ "$_active" != "$ROCKBOX_PKG" ] && pm_disable_pkg "$ROCKBOX_PKG" && force_stop_pkg "$ROCKBOX_PKG" "switch"
    [ "$_active" != "$JJ_PKG" ] && pm_disable_pkg "$JJ_PKG" && force_stop_pkg "$JJ_PKG" "switch"

    _list=$(getprop "$PROP_HOME_LAUNCHER_PKGS")
    if [ -n "$_list" ]; then
        OLDIFS="$IFS"
        IFS=','
        for _pkg in $_list; do
            [ -z "$_pkg" ] && continue
            [ "$_pkg" = "$_active" ] && continue
            [ "$_pkg" = "$HELPER_PKG" ] && continue
            [ "$_pkg" = "com.solar.launcher.globalcontext" ] && continue
            pm_disable_pkg "$_pkg"
            force_stop_pkg "$_pkg" "switch"
        done
        IFS="$OLDIFS"
    fi
    # Legacy fallback when prop empty — Rockbox/JJ matrix.
    if [ -z "$_list" ]; then
        case "$_t" in
            rockbox)
                pm_enable_pkg "$ROCKBOX_PKG"
                pm_disable_pkg "$JJ_PKG"
                force_stop_pkg "$JJ_PKG" "switch"
                ;;
            jj)
                pm_enable_pkg "$JJ_PKG"
                pm_disable_pkg "$ROCKBOX_PKG"
                force_stop_pkg "$ROCKBOX_PKG" "switch"
                ;;
            custom)
                pm_disable_pkg "$ROCKBOX_PKG"
                pm_disable_pkg "$JJ_PKG"
                force_stop_pkg "$ROCKBOX_PKG" "switch"
                force_stop_pkg "$JJ_PKG" "switch"
                ;;
            *)
                pm_disable_pkg "$ROCKBOX_PKG"
                pm_disable_pkg "$JJ_PKG"
                force_stop_pkg "$ROCKBOX_PKG" "switch"
                force_stop_pkg "$JJ_PKG" "switch"
                ;;
        esac
    fi
}

broadcast_home_target() {
    _mode="$1"
    setprop "$PROP_HOME_TARGET" "$_mode"
    am broadcast -a "$SET_HOME_ACTION" -n "$HOME_RECEIVER" --es target "$_mode"
}

cmd_switch() {
    _raw="$1"
    case "$_raw" in
        rockbox|--rockbox|-rockbox) _t=rockbox ;;
        jj|--jj|-jj) _t=jj ;;
        custom|--custom|-custom) _t=custom ;;
        stock|solar|--solar|-solar) _t=solar ;;
        *) _t=solar ;;
    esac
    arm_transition
    disable_competitors_for "$_t"
    broadcast_home_target "$_t"
    ensure_overlay_host
    if [ "$_t" = "rockbox" ]; then
        if [ -f /system/etc/solar/sync-rockbox-libs.sh ]; then
            sh /system/etc/solar/sync-rockbox-libs.sh 2>/dev/null
        fi
    fi
    _act=$(activity_for_target "$_t")
    if [ "$_t" = "solar" ]; then
        # 2026-07-06 — HOME via helper middle-man; MainActivity is not a HOME handler.
        am start -n "com.solar.launcher.homehelper/.LauncherHomeActivity" 2>/dev/null \
            || am start -n "$_act" 2>/dev/null
    else
        am start -n "$_act" 2>/dev/null
    fi
    sleep 1
    clear_transition
}

cmd_restart_active() {
    _t=$(read_target)
    case "$_t" in
        rockbox|jj|solar|custom) ;;
        *) _t=solar ;;
    esac
    _pkg=$(pkg_for_target "$_t")
    arm_transition
    disable_competitors_for "$_t"
    force_stop_pkg "$_pkg" "restart"
    sleep 0.3
    _act=$(activity_for_target "$_t")
    am start -n "$_act" 2>/dev/null
    ensure_overlay_host
    sleep 1
    clear_transition
}

cmd_disable_competitors() {
    _t=$(read_target)
    case "$_t" in
        rockbox|jj|solar|custom) ;;
        *) _t=solar ;;
    esac
    disable_competitors_for "$_t"
}

cmd_enforce_foreground() {
    _t=$(read_target)
    case "$_t" in
        rockbox|jj|custom) ;;
        *) return 0 ;;
    esac
    # 2026-07-06 — overlay hold owns fg; do not force-stop Rockbox/JJ while modal is up.
    _overlay=$(getprop sys.solar.overlay.active 2>/dev/null)
    _opening=$(getprop sys.solar.overlay.opening 2>/dev/null)
    if [ "$_overlay" = "1" ] || [ "$_opening" = "1" ]; then
        # #region agent log
        debug_log "A" "cmd_enforce_foreground" "skip overlay" "target=$_t overlay=$_overlay opening=$_opening"
        # #endregion
        return 0
    fi
    _pkg=$(pkg_for_target "$_t")
    _disabled=$(pm list packages -d "$_pkg" 2>/dev/null)
    if [ -n "$_disabled" ]; then
        # #region agent log
        debug_log "A" "cmd_enforce_foreground" "skip disabled" "target=$_t pkg=$_pkg"
        # #endregion
        return 0
    fi
    _fg=$(dumpsys activity activities 2>/dev/null | grep mResumedActivity | head -1)
    echo "$_fg" | grep -q "$_pkg" && return 0
    # #region agent log
    debug_log "A" "cmd_enforce_foreground" "force-stop relaunch" "target=$_t pkg=$_pkg fg=$_fg"
    # #endregion
    force_stop_pkg "$SOLAR_PKG" "recover"
    force_stop_pkg "$ROCKBOX_PKG" "recover"
    force_stop_pkg "$JJ_PKG" "recover"
    pm_enable_pkg "$_pkg"
    am start -n "$(activity_for_target "$_t")" 2>/dev/null
}

case "$1" in
    switch)
        # #region agent log
        debug_log "C" "main" "switch cmd" "arg=$2 target=$(read_target)"
        # #endregion
        cmd_switch "$2"
        ;;
    restart-active)
        cmd_restart_active
        ;;
    disable-competitors)
        cmd_disable_competitors
        ;;
    enforce-foreground)
        cmd_enforce_foreground
        ;;
    *)
        echo "usage: $0 switch solar|rockbox|jj|custom | restart-active | disable-competitors | enforce-foreground" >&2
        exit 1
        ;;
esac
