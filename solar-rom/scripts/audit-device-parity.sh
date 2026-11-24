#!/usr/bin/env bash
# 2026-07-05 — Post-flash adb parity gate; run on Y1 and Y2 hardware after ROM flash or adb patch.
# APK/ROM parity: confirms /system deliverables + Xposed modules.list match ROM zip contract.
# When changing: lib-xposed-install.sh paths; XposedModuleRegistry required packages.
# Reversal: skip after flash; device drift goes undetected until manual inspection.
# Usage: audit-device-parity.sh [adb_serial]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-debug-log.sh"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-adb-system.sh"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-xposed-install.sh"

adb_system_init
SERIAL="${1:-${ANDROID_SERIAL:-}}"
ADB=("${SOLAR_ADB[@]}")

adb_sh() { "${ADB[@]}" shell "$@"; }

check_file() {
    local path="$1" tag="$2"
    if adb_sh "[ -f $path ] && echo yes || echo no" 2>/dev/null | tr -d '\r' | grep -q yes; then
        debug_rom_log "D" "audit-device-parity.sh:check_file" "present" "path=$path" "tag=$tag"
        echo "  OK $path"
        return 0
    fi
    debug_rom_log "D" "audit-device-parity.sh:check_file" "MISSING" "path=$path" "tag=$tag"
    echo "  MISSING $path"
    return 1
}

echo "==> audit-device-parity (serial=${SERIAL:-default} transport=${SOLAR_ADB_TRANSPORT:-default})"
timeout 8 "${ADB[@]}" get-state >/dev/null 2>&1 || { echo "error: no adb device" >&2; exit 1; }

MODEL=$(timeout 8 "${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
LANG=$(timeout 8 "${ADB[@]}" shell getprop persist.sys.language 2>/dev/null | tr -d '\r')
SOLAR_VER=$(timeout 12 "${ADB[@]}" shell dumpsys package com.solar.launcher 2>/dev/null | grep versionName | head -1 | tr -d '\r' || true)

debug_rom_log "D" "audit-device-parity.sh:boot" "device snapshot" \
    "model=$MODEL" "language=$LANG" "solar=$SOLAR_VER"

errors=0
check_file /system/app/com.solar.launcher.apk solar_apk || errors=$((errors + 1))
check_file /system/xbin/su su_xbin || errors=$((errors + 1))
check_file /system/etc/init.d/99SuperSUDaemon su_daemon || errors=$((errors + 1))
check_file /system/etc/install-recovery.sh install_recovery || errors=$((errors + 1))

# Xposed Dalvik framework (all Solar ROMs).
check_file /system/xposed.prop xposed_prop || errors=$((errors + 1))
check_file /system/framework/XposedBridge.jar xposed_jar || errors=$((errors + 1))
check_file /system/bin/app_process.orig app_process_orig || errors=$((errors + 1))
check_file /system/app/XposedInstaller.apk xposed_installer || errors=$((errors + 1))
check_file /system/etc/init.d/99XposedInit.sh xposed_init || errors=$((errors + 1))
if [ "$MODEL" = "Y2" ]; then
    check_file /system/app/SolarContextBridgeY2.apk context_bridge_y2 || errors=$((errors + 1))
    BRIDGE_PKG="$XPOSED_CONTEXT_BRIDGE_Y2_PKG"
else
    check_file /system/app/SolarContextBridgeY1.apk context_bridge_y1 || errors=$((errors + 1))
    BRIDGE_PKG="$XPOSED_CONTEXT_BRIDGE_Y1_PKG"
fi
check_file /system/app/SolarThemeFont.apk theme_font || errors=$((errors + 1))
check_file /system/app/SolarNotPipeBridge.apk notpipe_bridge || errors=$((errors + 1))
check_file /system/app/io.github.gohoski.notpipe.apk notpipe_apk || errors=$((errors + 1))

xposed_app_process_ok=0
if xposed_app_process_active_via_adb 2>/dev/null; then
    echo "  OK app_process contains Xposed support string"
    xposed_app_process_ok=1
elif adb_sh "su -c 'grep -aq \"with Xposed support\" /system/bin/app_process && echo yes'" 2>/dev/null | tr -d '\r' | grep -q yes; then
    echo "  OK app_process contains Xposed support string"
    xposed_app_process_ok=1
elif adb_sh "[ -f /system/bin/app_process.xposed.staged ] && echo yes" 2>/dev/null | tr -d '\r' | grep -q yes; then
    echo "  WARN app_process still stock — staged binary waits for reboot" >&2
    errors=$((errors + 1))
else
    echo "  FAIL app_process missing Xposed support string — run ensure-xposed-framework-adb.sh" >&2
    errors=$((errors + 1))
fi

# When Xposed zygote is active, production modules must be enabled (parity with 99XposedInit.sh).
if [ "$xposed_app_process_ok" -eq 1 ]; then
    if adb_sh "su -c 'grep -q \"$BRIDGE_PKG\" /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml 2>/dev/null && echo yes'" \
            2>/dev/null | tr -d '\r' | grep -q yes; then
        echo "  OK Xposed bridge module enabled ($BRIDGE_PKG)"
    else
        echo "  FAIL Xposed bridge module not enabled — run 99XposedInit.sh or XposedModuleEnsurer" >&2
        errors=$((errors + 1))
    fi
    if adb_sh "su -c 'grep -q \"$XPOSED_THEME_FONT_PKG\" /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml 2>/dev/null && echo yes'" \
            2>/dev/null | tr -d '\r' | grep -q yes; then
        echo "  OK Xposed theme font module enabled ($XPOSED_THEME_FONT_PKG)"
    else
        echo "  FAIL Xposed theme font module not enabled — run 99XposedInit.sh or XposedModuleEnsurer" >&2
        errors=$((errors + 1))
    fi
    if adb_sh "su -c 'grep -q \"$XPOSED_NOTPIPE_BRIDGE_PKG\" /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml 2>/dev/null && echo yes'" \
            2>/dev/null | tr -d '\r' | grep -q yes; then
        echo "  OK Xposed NotPipe bridge module enabled ($XPOSED_NOTPIPE_BRIDGE_PKG)"
    else
        echo "  FAIL Xposed NotPipe bridge module not enabled — run 99XposedInit.sh or XposedModuleEnsurer" >&2
        errors=$((errors + 1))
    fi
    if adb_sh "su -c 'grep -q \"SolarContextBridge\" /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null && echo yes'" \
            2>/dev/null | tr -d '\r' | grep -q yes; then
        echo "  OK context bridge listed in modules.list"
    else
        echo "  FAIL context bridge missing from modules.list" >&2
        errors=$((errors + 1))
    fi
    if adb_sh "su -c 'grep -q \"SolarThemeFont\" /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null && echo yes'" \
            2>/dev/null | tr -d '\r' | grep -q yes; then
        echo "  OK theme font listed in modules.list"
    else
        echo "  FAIL theme font missing from modules.list" >&2
        errors=$((errors + 1))
    fi
fi

if ! adb_sh "ps" 2>/dev/null | tr -d '\r' | grep -q daemonsu; then
    debug_rom_log "A" "audit-device-parity.sh:daemonsu" "daemonsu not running — app su -c and RootKeyInjector will fail"
    if [ "$MODEL" = "Y2" ]; then
        echo "  WARN daemonsu not running (install-recovery.sh missing or boot hook failed)"
        errors=$((errors + 1))
    else
        echo "  NOTE daemonsu not running (Y1 permissive root — su -c works without daemon)"
    fi
fi

# Y2 uses Y2-Rockbox.kl; Y1 uses Y1-Rockbox.kl — accept either canonical name.
if check_file /system/etc/solar/Y2-Rockbox.kl y2_kl 2>/dev/null; then
    :
elif check_file /system/etc/solar/Y1-Rockbox.kl y1_kl; then
    :
else
    errors=$((errors + 1))
fi

if [ "$LANG" != "en" ]; then
    debug_rom_log "E" "audit-device-parity.sh:locale" "default language not English" "language=$LANG"
    echo "  WARN persist.sys.language=$LANG (expected en after Y2 ROM build)"
    errors=$((errors + 1))
fi

debug_rom_log "D" "audit-device-parity.sh:done" "audit finished" "errors=$errors"
[ "$errors" -eq 0 ] && echo "==> audit-device-parity: OK" && exit 0
echo "==> audit-device-parity: $errors check(s) failed — run apply-y1-rom-patches-adb.sh or apply-y2-rom-patches-adb.sh" >&2
exit 1
