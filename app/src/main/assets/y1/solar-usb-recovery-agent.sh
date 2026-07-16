#!/system/bin/sh
# Re-home Solar when SystemUI UsbStorageActivity wins while UMS is off.
# ponytail: started by com.solar.launcher while its process lives; exits when Solar stops.
# Only runs when org.rockbox is disabled (Solar is the active launcher).

SOLAR_PKG="com.solar.launcher"
SOLAR_ACT="${SOLAR_PKG}/.MainActivity"
ROCKBOX_PKG="org.rockbox"
PIDFILE="/data/data/.solar_usb_recovery.pid"
INTERVAL=5
COOLDOWN=5

ums_exported() {
    line=$(dumpsys usb 2>/dev/null | grep 'Mass storage backing file:')
    [ -z "$line" ] && return 1
    path="${line#*:}"
    path="${path#"${path%%[![:space:]]*}"}"
    [ -n "$path" ]
}

systemui_usb_on_top() {
    dumpsys activity top 2>/dev/null | grep -q 'UsbStorageActivity' && return 0
    return 1
}

solar_enabled() {
    pm list packages -d 2>/dev/null | grep -q "^package:${SOLAR_PKG}$" && return 1
    pm path "$SOLAR_PKG" >/dev/null 2>&1
}

rockbox_disabled() {
    pm list packages -d 2>/dev/null | grep -q "^package:${ROCKBOX_PKG}$"
}

bring_solar_home() {
    # #region agent log
    ts=$(date +%s%3N 2>/dev/null || date +%s)
    echo "{\"sessionId\":\"317b34\",\"timestamp\":${ts},\"location\":\"solar-usb-recovery-agent.sh:bring_solar_home\",\"message\":\"recovery dismiss\",\"hypothesisId\":\"H2\",\"data\":{}}" \
        >>/storage/sdcard0/solar/debug-317b34.log 2>/dev/null
    echo "{\"sessionId\":\"317b34\",\"timestamp\":${ts},\"location\":\"solar-usb-recovery-agent.sh:bring_solar_home\",\"message\":\"recovery dismiss\",\"hypothesisId\":\"H2\",\"data\":{}}" \
        >>/data/data/com.solar.launcher/files/debug-317b34.log 2>/dev/null
    # #endregion
    # ponytail: BACK finishes UsbStorageActivity; one HOME brings Solar — no dumpsys storm.
    input keyevent 4
    sleep 0.15
    am start -a android.intent.action.MAIN -c android.intent.category.HOME \
        -n "$SOLAR_ACT" -f 0x34000000 >/dev/null 2>&1
}

# Singleton — one agent per device while Solar is running.
if [ -f "$PIDFILE" ]; then
    oldpid=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ >"$PIDFILE"

while pidof "$SOLAR_PKG" >/dev/null 2>&1; do
    # Tier-2 only — Xposed UsbStorageHooks owns plug-in UX when bridge is on system (2026-07-06).
    if pm path com.solar.launcher.xposed.bridge.y1 >/dev/null 2>&1 \
        || pm path com.solar.launcher.xposed.bridge.y2 >/dev/null 2>&1; then
        sleep 60
        continue
    fi
    if solar_enabled && rockbox_disabled && systemui_usb_on_top && ! ums_exported; then
        bring_solar_home
        sleep "$COOLDOWN"
    else
        sleep "$INTERVAL"
    fi
done

rm -f "$PIDFILE"
