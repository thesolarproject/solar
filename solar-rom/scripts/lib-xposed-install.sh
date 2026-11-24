#!/usr/bin/env bash
# 2026-07-05 — Canonical /system Xposed paths shared by ROM mount, adb install, and APK manifest.
# APK/ROM parity: constants below must match manifest.json systemPaths and PlatformPrepManifest.
# When changing: sync-platform-assets.sh, install-xposed-system.sh, XposedFrameworkInstaller.java.
# Dalvik-era Xposed embeds hooks in app_process; no separate libxposed_dalvik.so on /system.
# Reversal: revert path constants; ROM and APK prep drift until next sync-platform-assets.sh run.
set -euo pipefail

# Canonical /system paths required for a working framework (ROM + adb must match).
XPOSED_SYSTEM_BIN_APP_PROCESS="/system/bin/app_process"
XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG="/system/bin/app_process.orig"
XPOSED_SYSTEM_FRAMEWORK_JAR="/system/framework/XposedBridge.jar"
XPOSED_SYSTEM_SOLAR_JAR="/system/etc/solar/XposedBridge.jar"
XPOSED_SYSTEM_PROP="/system/xposed.prop"
XPOSED_SYSTEM_INSTALLER_APK="/system/app/XposedInstaller.apk"
XPOSED_SYSTEM_INIT_HOOK="/system/etc/init.d/99XposedInit.sh"

# Runtime path zygote reads (seeded from /system/framework on boot + adb install).
XPOSED_RUNTIME_JAR="/data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar"

# Solar context bridge — Y1 (BACK-long), Y2 (BACK-long + power-hold + volume) microservices.
XPOSED_CONTEXT_BRIDGE_Y1_PKG="com.solar.launcher.xposed.bridge.y1"
XPOSED_CONTEXT_BRIDGE_Y2_PKG="com.solar.launcher.xposed.bridge.y2"
XPOSED_SYSTEM_CONTEXT_BRIDGE_Y1_APK="/system/app/SolarContextBridgeY1.apk"
XPOSED_SYSTEM_CONTEXT_BRIDGE_Y2_APK="/system/app/SolarContextBridgeY2.apk"
# Legacy single-package name (adb scripts may still reference).
XPOSED_CONTEXT_BRIDGE_PKG="$XPOSED_CONTEXT_BRIDGE_Y2_PKG"
XPOSED_SYSTEM_CONTEXT_BRIDGE_APK="$XPOSED_SYSTEM_CONTEXT_BRIDGE_Y2_APK"

# Solar theme font — shared Y1/Y2 module (sidecar path differs by device in Solar app).
XPOSED_THEME_FONT_PKG="com.solar.launcher.xposed.themefont"
XPOSED_SYSTEM_THEME_FONT_APK="/system/app/SolarThemeFont.apk"

# Rockbox hook modules — split from context bridge for Debug menu toggles.
XPOSED_ROCKBOX_IME_PKG="com.solar.launcher.xposed.rockbox.ime"
XPOSED_ROCKBOX_COMPAT_PKG="com.solar.launcher.xposed.rockbox.compat"
XPOSED_SYSTEM_ROCKBOX_IME_APK="/system/app/SolarRockboxIme.apk"
XPOSED_SYSTEM_ROCKBOX_COMPAT_APK="/system/app/SolarRockboxCompat.apk"

# Solar notPipe bridge — YouTube IPC + wheel player hooks (Y1 + Y2).
XPOSED_NOTPIPE_BRIDGE_PKG="com.solar.launcher.xposed.notpipe"
XPOSED_SYSTEM_NOTPIPE_BRIDGE_APK="/system/app/SolarNotPipeBridge.apk"

# #region agent log
# Debug-session NDJSON sink (host path — written by adb install/verify scripts).
XPOSED_AGENT_DEBUG_LOG="${XPOSED_AGENT_DEBUG_LOG:-/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-88eb84.log}"

xposed_agent_debug_log() {
    local hypothesis_id="$1" location="$2" message="$3" data_json="${4:-{}}"
    local ts
    ts="$(date +%s000 2>/dev/null || echo 0)"
    printf '{"sessionId":"88eb84","hypothesisId":"%s","location":"%s","message":"%s","data":%s,"timestamp":%s}\n' \
        "$hypothesis_id" "$location" "$message" "$data_json" "$ts" >> "$XPOSED_AGENT_DEBUG_LOG" 2>/dev/null || true
}

# InstallerFragment greps app_process for "with Xposed support (version N)" — stock MTK binary lacks it.
xposed_app_process_active_via_adb() {
    adb_su_sh "grep -aq 'with Xposed support' '$XPOSED_SYSTEM_BIN_APP_PROCESS' && echo yes || echo no" \
        | tr -d '\r' | grep -q yes
}

# Collect live framework state for debug hypotheses H1–H5.
xposed_probe_framework_state_via_adb() {
    local ap_size orig_size staged runtime disabled init_hook xposed_str
    ap_size="$(adb_su_sh "ls -l '$XPOSED_SYSTEM_BIN_APP_PROCESS' 2>/dev/null" | tr -d '\r' | awk '{print $4}')"
    orig_size="$(adb_su_sh "ls -l '$XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG' 2>/dev/null" | tr -d '\r' | awk '{print $4}')"
    if adb_su_sh "test -f /system/bin/app_process.xposed.staged && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        staged="yes"
    else
        staged="no"
    fi
    if adb_su_sh "test -f '$XPOSED_RUNTIME_JAR' && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        runtime="yes"
    else
        runtime="no"
    fi
    if adb_su_sh "test -f /data/data/de.robv.android.xposed.installer/conf/disabled && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        disabled="yes"
    else
        disabled="no"
    fi
    local modules_list="no"
    if adb_su_sh "test -f /data/data/de.robv.android.xposed.installer/conf/modules.list && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        modules_list="yes"
    fi
    if adb_su_sh "test -x '$XPOSED_SYSTEM_INIT_HOOK' && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        init_hook="yes"
    else
        init_hook="no"
    fi
    if xposed_app_process_active_via_adb; then
        xposed_str="yes"
    else
        xposed_str="no"
    fi
    xposed_agent_debug_log "H1-H5" "lib-xposed-install.sh:xposed_probe_framework_state_via_adb" "framework probe" \
        "{\"app_process_bytes\":\"${ap_size:-unknown}\",\"orig_bytes\":\"${orig_size:-unknown}\",\"xposed_string\":\"${xposed_str}\",\"staged\":\"${staged}\",\"runtime_jar\":\"${runtime}\",\"disabled_flag\":\"${disabled}\",\"modules_list\":\"${modules_list}\",\"init_hook\":\"${init_hook}\",\"device\":\"${XPOSED_PROBE_DEVICE:-unknown}\"}"
    echo "  probe: app_process=${ap_size:-?}B orig=${orig_size:-?}B xposed_string=${xposed_str} staged=${staged} runtime=${runtime} modules.list=${modules_list} disabled=${disabled}"
}
# #endregion

xposed_list_system_paths() {
    printf '%s\n' \
        "$XPOSED_SYSTEM_BIN_APP_PROCESS" \
        "$XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG" \
        "$XPOSED_SYSTEM_FRAMEWORK_JAR" \
        "$XPOSED_SYSTEM_SOLAR_JAR" \
        "$XPOSED_SYSTEM_PROP" \
        "$XPOSED_SYSTEM_INSTALLER_APK" \
        "$XPOSED_SYSTEM_INIT_HOOK"
}

# Resolve vendored arm tree for Jelly Bean / KitKat (same app_process binary for SDK 16-19).
xposed_vendor_dir() {
    local api_level="${1:?api level}"
    local script_dir="${2:?script dir}"
    case "$api_level" in
        17|18|19) echo "$script_dir/../vendor/xposed/api${api_level}-arm" ;;
        *) echo "unsupported Xposed API $api_level (need 17, 18, or 19)" >&2; return 1 ;;
    esac
}

# Build helpers print progress on stdout; when resolving APK paths via $(...),
# redirect that noise to stderr so only the path is captured.
xposed_installer_apk() {
    local script_dir="${1:?script dir}"
    local apk="$script_dir/../vendor/xposed/XposedInstaller.apk"
    if [ -f "$apk" ]; then
        echo "$apk"
        return 0
    fi
    if [ -x "$script_dir/build-xposed-installer-apk.sh" ]; then
        "$script_dir/build-xposed-installer-apk.sh" >&2
        [ -f "$apk" ] && echo "$apk" && return 0
    fi
    echo "missing $apk (run solar-rom/scripts/build-xposed-installer-apk.sh)" >&2
    return 1
}

xposed_context_bridge_apk() {
    local script_dir="${1:?script dir}"
    local api_level="${2:-19}"
    local variant=y2
    local out="$script_dir/../vendor/xposed/solar-context-bridge/SolarContextBridgeY2.apk"
    if [ "$api_level" = "17" ] || [ "$api_level" = "18" ]; then
        variant=y1
        out="$script_dir/../vendor/xposed/solar-context-bridge/SolarContextBridgeY1.apk"
    fi
    if [ -f "$out" ]; then
        echo "$out"
        return 0
    fi
    if [ -x "$script_dir/build-context-bridge-apk.sh" ]; then
        "$script_dir/build-context-bridge-apk.sh" >&2
        [ -f "$out" ] && echo "$out" && return 0
    fi
    echo "missing $out (run solar-rom/scripts/build-context-bridge-apk.sh)" >&2
    return 1
}

xposed_context_bridge_pkg_for_api() {
    local api_level="${1:-19}"
    if [ "$api_level" = "17" ] || [ "$api_level" = "18" ]; then
        echo "$XPOSED_CONTEXT_BRIDGE_Y1_PKG"
    else
        echo "$XPOSED_CONTEXT_BRIDGE_Y2_PKG"
    fi
}

xposed_context_bridge_system_apk_for_api() {
    local api_level="${1:-19}"
    if [ "$api_level" = "17" ] || [ "$api_level" = "18" ]; then
        echo "$XPOSED_SYSTEM_CONTEXT_BRIDGE_Y1_APK"
    else
        echo "$XPOSED_SYSTEM_CONTEXT_BRIDGE_Y2_APK"
    fi
}

# Resolve or build SolarThemeFont.apk for ROM bake-in (minSdk 17 — Y1 and Y2).
xposed_theme_font_apk() {
    local script_dir="${1:?script dir}"
    local out="$script_dir/../vendor/xposed/solar-theme-font/SolarThemeFont.apk"
    if [ -f "$out" ]; then
        echo "$out"
        return 0
    fi
    if [ -x "$script_dir/build-theme-font-apk.sh" ]; then
        "$script_dir/build-theme-font-apk.sh" >&2
        [ -f "$out" ] && echo "$out" && return 0
    fi
    echo "missing $out (run solar-rom/scripts/build-theme-font-apk.sh)" >&2
    return 1
}

# Resolve or build SolarRockboxIme.apk (Y1 + Y2).
xposed_rockbox_ime_apk() {
    local script_dir="${1:?script dir}"
    local out="$script_dir/../vendor/xposed/solar-rockbox-ime/SolarRockboxIme.apk"
    if [ -f "$out" ]; then
        echo "$out"
        return 0
    fi
    if [ -x "$script_dir/build-rockbox-xposed-apks.sh" ]; then
        "$script_dir/build-rockbox-xposed-apks.sh" >&2
        [ -f "$out" ] && echo "$out" && return 0
    fi
    echo "missing $out (run solar-rom/scripts/build-rockbox-xposed-apks.sh)" >&2
    return 1
}

# Resolve or build SolarRockboxCompat.apk (Y2 only).
xposed_rockbox_compat_apk() {
    local script_dir="${1:?script dir}"
    local out="$script_dir/../vendor/xposed/solar-rockbox-compat/SolarRockboxCompat.apk"
    if [ -f "$out" ]; then
        echo "$out"
        return 0
    fi
    if [ -x "$script_dir/build-rockbox-xposed-apks.sh" ]; then
        "$script_dir/build-rockbox-xposed-apks.sh" >&2
        [ -f "$out" ] && echo "$out" && return 0
    fi
    echo "missing $out (run solar-rom/scripts/build-rockbox-xposed-apks.sh)" >&2
    return 1
}

# Resolve or build SolarNotPipeBridge.apk (Y1 + Y2).
# Note: NotPipe bridge is ROM-bake only (not in app self-heal assets); CI always builds on demand.
xposed_notpipe_bridge_apk() {
    local script_dir="${1:?script dir}"
    local out="$script_dir/../vendor/xposed/solar-notpipe-bridge/SolarNotPipeBridge.apk"
    if [ -f "$out" ]; then
        echo "$out"
        return 0
    fi
    if [ -x "$script_dir/build-notpipe-bridge-apk.sh" ]; then
        # Must not capture build progress on stdout — install would receive multi-line garbage.
        "$script_dir/build-notpipe-bridge-apk.sh" >&2
        [ -f "$out" ] && echo "$out" && return 0
    fi
    echo "missing $out (run solar-rom/scripts/build-notpipe-bridge-apk.sh)" >&2
    return 1
}

# Copy framework files into a loop-mounted /system tree (offline ROM build).
xposed_install_to_mount() {
    local mount="$1" api_level="$2" script_dir="$3"
    local vendor
    vendor="$(xposed_vendor_dir "$api_level" "$script_dir")" || return 1
    local installer_apk
    installer_apk="$(xposed_installer_apk "$script_dir")" || return 1

    [ -d "$mount/bin" ] || { echo "xposed: not a system mount: $mount" >&2; return 1; }
    [ -f "$vendor/app_process" ] || { echo "xposed: missing $vendor/app_process" >&2; return 1; }
    [ -f "$vendor/XposedBridge.jar" ] || { echo "xposed: missing $vendor/XposedBridge.jar" >&2; return 1; }
    [ -f "$vendor/xposed.prop" ] || { echo "xposed: missing $vendor/xposed.prop" >&2; return 1; }

    # shellcheck source=/dev/null
    source "$script_dir/lib-root.sh"
    ensure_sudo_shim_when_root

    echo "==> Xposed: install Dalvik framework (API $api_level) into $mount"

    sudo mkdir -p "$mount/framework" "$mount/etc/solar" "$mount/app" "$mount/etc/init.d"
    # Docker build hosts may lack Android's shell group — fall back to root:root (device images use root:shell).
    if getent group shell >/dev/null 2>&1; then
        sudo install -m 755 -o root -g shell "$vendor/app_process" "$mount/bin/app_process"
    else
        sudo install -m 755 -o root -g root "$vendor/app_process" "$mount/bin/app_process"
    fi
    sudo install -m 644 -o root -g root "$vendor/XposedBridge.jar" "$mount/framework/XposedBridge.jar"
    sudo install -m 644 -o root -g root "$vendor/XposedBridge.jar" "$mount/etc/solar/XposedBridge.jar"
    sudo install -m 644 -o root -g root "$vendor/xposed.prop" "$mount/xposed.prop"
    sudo install -m 644 -o root -g root "$installer_apk" "$mount/app/XposedInstaller.apk"

    local init_hook="$script_dir/../system/99XposedInit.sh"
    [ -f "$init_hook" ] || { echo "xposed: missing $init_hook" >&2; return 1; }
    sudo install -m 755 -o root -g root "$init_hook" "$mount/etc/init.d/99XposedInit.sh"

    local bridge_apk bridge_system
    bridge_apk="$(xposed_context_bridge_apk "$script_dir" "$api_level")" || return 1
    bridge_system="$(xposed_context_bridge_system_apk_for_api "$api_level")"
    echo "==> Xposed: install Solar context bridge module (API $api_level)"
    sudo install -m 644 -o root -g root "$bridge_apk" "$mount${bridge_system#/system}"
    # Remove opposite-family module if present from an older ROM bake.
    if [ "$api_level" = "17" ] || [ "$api_level" = "18" ]; then
        sudo rm -f "$mount/app/SolarContextBridgeY2.apk" "$mount/app/SolarContextBridge.apk"
    else
        sudo rm -f "$mount/app/SolarContextBridgeY1.apk"
    fi

    local theme_apk
    theme_apk="$(xposed_theme_font_apk "$script_dir")" || return 1
    echo "==> Xposed: install Solar theme font module (API $api_level)"
    sudo install -m 644 -o root -g root "$theme_apk" "$mount/app/SolarThemeFont.apk"

    local rockbox_ime_apk
    rockbox_ime_apk="$(xposed_rockbox_ime_apk "$script_dir")" || return 1
    echo "==> Xposed: install Solar Rockbox IME module (API $api_level)"
    sudo install -m 644 -o root -g root "$rockbox_ime_apk" "$mount/app/SolarRockboxIme.apk"

    if [ "$api_level" != "17" ] && [ "$api_level" != "18" ]; then
        local rockbox_compat_apk
        rockbox_compat_apk="$(xposed_rockbox_compat_apk "$script_dir")" || return 1
        echo "==> Xposed: install Solar Rockbox compat module (API $api_level)"
        sudo install -m 644 -o root -g root "$rockbox_compat_apk" "$mount/app/SolarRockboxCompat.apk"
    else
        sudo rm -f "$mount/app/SolarRockboxCompat.apk"
    fi

    local notpipe_bridge_apk
    notpipe_bridge_apk="$(xposed_notpipe_bridge_apk "$script_dir")" || return 1
    echo "==> Xposed: install Solar NotPipe bridge module (API $api_level)"
    sudo install -m 644 -o root -g root "$notpipe_bridge_apk" "$mount/app/SolarNotPipeBridge.apk"
}

# Seed /data/data/de.robv.android.xposed.installer paths on a live device (su).
# Resolve Installer app uid so seeded files are not root-owned (app cannot write modules.list otherwise).
xposed_installer_app_uid_via_adb() {
    local uid
    # dumpsys is reliable on KitKat+; packages.list fallback when dumpsys is slow or empty.
    uid="$("${SOLAR_ADB[@]}" shell "dumpsys package de.robv.android.xposed.installer 2>/dev/null | grep userId=" 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)"
    if [ -n "$uid" ]; then
        echo "$uid"
        return 0
    fi
    uid="$(adb_su_sh "grep '^de.robv.android.xposed.installer ' /data/system/packages.list 2>/dev/null | head -1" \
        | tr -d '\r' | cut -d' ' -f2)"
    [ -n "$uid" ] && echo "$uid"
}

# ls -l field 3/4 without awk — Y2 stock shell has no awk.
_xposed_ls_field_via_adb() {
    local file_path="$1" field="$2"
    adb_su_sh "ls -l '$file_path' 2>/dev/null" | tr -d '\r' | tr -s ' ' | cut -d' ' -f"$field" | head -1
}

# Probe modules.list ownership — root-owned 664 blocks Installer UI writes (H1).
xposed_probe_installer_writable_via_adb() {
    local uid owner group mode writable
    uid="$(xposed_installer_app_uid_via_adb)"
    owner="$(_xposed_ls_field_via_adb /data/data/de.robv.android.xposed.installer/conf/modules.list 3)"
    group="$(_xposed_ls_field_via_adb /data/data/de.robv.android.xposed.installer/conf/modules.list 4)"
    mode="$(adb_su_sh "stat -c %a /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null" \
        | tr -d '\r' | head -1)"
    if [ "$owner" = "root" ]; then
        writable="no"
    elif [ -n "$owner" ] && [ "$owner" != "?" ]; then
        writable="yes"
    else
        writable="unknown"
    fi
    xposed_agent_debug_log "H1" "lib-xposed-install.sh:xposed_probe_installer_writable_via_adb" \
        "modules.list ownership" \
        "{\"app_uid\":\"${uid:-unknown}\",\"owner\":\"${owner:-?}\",\"group\":\"${group:-?}\",\"mode\":\"${mode:-?}\",\"installer_writable\":\"${writable}\"}"
    echo "  probe: modules.list owner=${owner:-?} mode=${mode:-?} app_uid=${uid:-?} writable=${writable}"
}

xposed_fix_installer_data_ownership_via_adb() {
    local uid
    uid="$(xposed_installer_app_uid_via_adb)"
    # #region agent log
    xposed_agent_debug_log "H2" "lib-xposed-install.sh:xposed_fix_installer_data_ownership_via_adb" \
        "chown attempt" "{\"resolved_uid\":\"${uid:-empty}\"}"
    # #endregion
    [ -n "$uid" ] || return 0
    local data="/data/data/de.robv.android.xposed.installer"
    adb_su_sh "mkdir -p $data/bin $data/conf $data/log $data/shared_prefs"
    # JB KitKat su shell often lacks find in PATH — chown known tree explicitly.
    adb_su_sh "chown ${uid}:${uid} $data $data/bin $data/conf $data/log $data/shared_prefs $data/cache 2>/dev/null; true"
    adb_su_sh "chown ${uid}:${uid} $data/conf/modules.list $data/shared_prefs/enabled_modules.xml 2>/dev/null; true"
    adb_su_sh "for f in $data/shared_prefs/* $data/conf/* $data/bin/* $data/log/*; do [ -e \"\$f\" ] && chown ${uid}:${uid} \"\$f\"; done 2>/dev/null; true"
    adb_su_sh "chmod 771 $data $data/bin $data/conf $data/log $data/shared_prefs $data/cache 2>/dev/null || true"
    adb_su_sh "chmod 644 $data/bin/XposedBridge.jar 2>/dev/null || true"
    adb_su_sh "chmod 664 $data/conf/modules.list 2>/dev/null || true"
    adb_su_sh "chmod 660 $data/shared_prefs/*.xml 2>/dev/null || true"
    # #region agent log
    xposed_probe_installer_writable_via_adb
    # #endregion
}

# Merge one package into enabled_modules.xml on the host — avoids Y2 adb/su quote breakage.
_xposed_merge_enabled_module_prefs_via_adb() {
    local pkg="${1:?package name required}"
    local remote="/data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml"
    local tmp
    tmp="$(mktemp)"

    if ! "${SOLAR_ADB[@]}" shell "su -c cat $remote" 2>/dev/null | tr -d '\r' > "$tmp"; then
        : > "$tmp"
    fi
    if [ ! -s "$tmp" ] || ! grep -q '<?xml' "$tmp" || ! grep -q '<map>' "$tmp" || ! grep -q '^</map>' "$tmp"; then
        {
            echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
            echo "<map>"
            grep '<int name=' "$tmp" 2>/dev/null || true
            echo "</map>"
        } > "${tmp}.repair"
        mv "${tmp}.repair" "$tmp"
    fi
    if grep -q "name=\"$pkg\"" "$tmp"; then
        sed -i "s|<int name=\"$pkg\" value=\"[^\"]*\"|<int name=\"$pkg\" value=\"1\"|" "$tmp"
    else
        sed -i "/<\\/map>/i\\    <int name=\"$pkg\" value=\"1\" />" "$tmp"
    fi

    "${SOLAR_ADB[@]}" push "$tmp" /data/local/tmp/enabled_modules.xml >/dev/null \
        || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
    adb_su_sh "cp /data/local/tmp/enabled_modules.xml $remote && chmod 660 $remote && rm -f /data/local/tmp/enabled_modules.xml"
}

# Enable one module with live pm path — survives pm install -r suffix rotation (-1 → -2).
# Merges enabled_modules.xml (does not wipe other modules). Purges stale modules.list paths.
xposed_ensure_module_enabled_via_adb() {
    local pkg="${1:?package name required}"
    local apk_path
    apk_path="$("${SOLAR_ADB[@]}" shell "pm path $pkg" 2>/dev/null | tr -d '\r' | grep -m1 '^package:' | cut -d: -f2-)"
    if [ -z "$apk_path" ]; then
        echo "xposed_ensure_module: package not installed: $pkg" >&2
        return 1
    fi

    local data="/data/data/de.robv.android.xposed.installer"
    local prefs="$data/shared_prefs/enabled_modules.xml"
    local list="$data/conf/modules.list"

    adb_su_sh "mkdir -p $data/conf $data/shared_prefs"
    adb_su_sh "touch $list"
    adb_su_sh "rm -f $data/conf/disabled"

    # Drop every modules.list line for this package (stale -1.apk after pm install -r).
    adb_su_sh "grep -v '$pkg' $list 2>/dev/null > ${list}.tmp || true"
    adb_su_sh "grep -qxF '$apk_path' ${list}.tmp 2>/dev/null || echo '$apk_path' >> ${list}.tmp"
    adb_su_sh "mv ${list}.tmp $list"

    _xposed_merge_enabled_module_prefs_via_adb "$pkg"

    xposed_fix_installer_data_ownership_via_adb
    echo "==> enabled $pkg ($apk_path)"
}

xposed_seed_runtime_via_adb() {
    # Requires lib-adb-system.sh sourced and adb_su_sh defined.
    adb_su_sh "mkdir -p /data/data/de.robv.android.xposed.installer/bin /data/data/de.robv.android.xposed.installer/conf /data/data/de.robv.android.xposed.installer/log"
    adb_su_sh "cp -f $XPOSED_SYSTEM_FRAMEWORK_JAR $XPOSED_RUNTIME_JAR"
    adb_su_sh "chmod 771 /data/data/de.robv.android.xposed.installer /data/data/de.robv.android.xposed.installer/bin /data/data/de.robv.android.xposed.installer/conf /data/data/de.robv.android.xposed.installer/log"
    adb_su_sh "chmod 644 $XPOSED_RUNTIME_JAR"
    adb_su_sh "rm -f /data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar.newversion /data/data/de.robv.android.xposed.installer/conf/disabled"
    # XposedBridge v54 crashes zygote if modules.list is absent — seed empty file before first boot.
    adb_su_sh "touch /data/data/de.robv.android.xposed.installer/conf/modules.list"
    xposed_fix_installer_data_ownership_via_adb
    # Run boot hook now so runtime jar exists before reboot.
    adb_su_sh "[ -x $XPOSED_SYSTEM_INIT_HOOK ] && sh $XPOSED_SYSTEM_INIT_HOOK || true"
    xposed_fix_installer_data_ownership_via_adb
}

# Verify all /system + runtime paths on connected device (su test -f).
xposed_verify_device_via_adb() {
    local errors=0 path
    local -a paths
    mapfile -t paths < <(xposed_list_system_paths)
    for path in "${paths[@]}"; do
        [ -n "$path" ] || continue
        if [ "$path" = "$XPOSED_SYSTEM_BIN_APP_PROCESS" ]; then
            if adb_su_sh "test -f ${path} && echo ok || echo missing" | tr -d '\r' | grep -q ok; then
                echo "  OK $path"
            elif adb_su_sh "test -f /system/bin/app_process.xposed.staged && echo staged || echo missing" | tr -d '\r' | grep -q staged; then
                echo "  OK $path (staged for next reboot)"
            else
                echo "  MISSING $path" >&2
                errors=$((errors + 1))
            fi
            continue
        fi
        if adb_su_sh "test -f ${path} && echo ok || echo missing" | tr -d '\r' | grep -q ok; then
            echo "  OK $path"
        else
            echo "  MISSING $path" >&2
            errors=$((errors + 1))
        fi
    done
    if adb_su_sh "test -f ${XPOSED_RUNTIME_JAR} && echo ok || echo missing" | tr -d '\r' | grep -q ok; then
        echo "  OK $XPOSED_RUNTIME_JAR"
    else
        echo "  MISSING $XPOSED_RUNTIME_JAR (run seed or reboot after pm install)" >&2
        errors=$((errors + 1))
    fi
    if adb_su_sh "test -f /data/data/de.robv.android.xposed.installer/conf/modules.list && echo ok || echo missing" | tr -d '\r' | grep -q ok; then
        echo "  OK conf/modules.list"
    else
        echo "  MISSING conf/modules.list (XposedBridge aborts without it)" >&2
        errors=$((errors + 1))
    fi
    # #region agent log
    xposed_probe_framework_state_via_adb
    # #endregion
    if xposed_app_process_active_via_adb; then
        echo "  OK app_process contains Xposed support string"
    elif adb_su_sh "test -f /system/bin/app_process.xposed.staged && echo yes || echo no" | tr -d '\r' | grep -q yes; then
        echo "  OK app_process staged for next reboot (/system/bin/app_process.xposed.staged)"
    else
        echo "  FAIL app_process missing Xposed support string (stock zygote binary)" >&2
        errors=$((errors + 1))
    fi
    return "$errors"
}

# Full adb install — push every /system file + seed runtime (mirrors xposed_install_to_mount).
xposed_install_full_via_adb() {
    local api_level="$1" script_dir="$2"
    local vendor installer
    vendor="$(xposed_vendor_dir "$api_level" "$script_dir")" || return 1
    installer="$(xposed_installer_apk "$script_dir")" || return 1

    echo "==> Xposed adb: install all system files (API $api_level)"

    if ! adb_su_sh "[ -f '$XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG' ] && echo yes || echo no" \
            | tr -d '\r' | grep -q yes; then
        echo "==> Backup $XPOSED_SYSTEM_BIN_APP_PROCESS -> app_process.orig"
        adb_su_sh "cp -a $XPOSED_SYSTEM_BIN_APP_PROCESS $XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG && chmod 755 $XPOSED_SYSTEM_BIN_APP_PROCESS_ORIG" \
            || return 1
    fi

    adb_push_to_system "$vendor/app_process" "$XPOSED_SYSTEM_BIN_APP_PROCESS" 755
    adb_su_sh "chown root:shell $XPOSED_SYSTEM_BIN_APP_PROCESS" || true
    adb_push_to_system "$vendor/XposedBridge.jar" "$XPOSED_SYSTEM_FRAMEWORK_JAR" 644
    adb_push_to_system "$vendor/XposedBridge.jar" "$XPOSED_SYSTEM_SOLAR_JAR" 644
    adb_push_to_system "$vendor/xposed.prop" "$XPOSED_SYSTEM_PROP" 644
    adb_push_to_system "$installer" "$XPOSED_SYSTEM_INSTALLER_APK" 644
    adb_push_to_system "$script_dir/../system/99XposedInit.sh" "$XPOSED_SYSTEM_INIT_HOOK" 755

    echo "==> Register XposedInstaller with PackageManager"
    # Never pm uninstall — that drops the system app from PM (launcher shows nothing / Activity not found).
    adb_su_sh "rm -rf /data/app/de.robv.android.xposed.installer* /data/app-lib/de.robv.android.xposed.installer*" 2>/dev/null || true
    if ! adb_pm_install_internal "$XPOSED_SYSTEM_INSTALLER_APK" "de.robv.android.xposed.installer" 2>/dev/null | tr -d '\r' | grep -q Success; then
        echo "WARN: pm install -r -f XposedInstaller failed — try reboot or manual" >&2
    fi
    sleep 1

    local bridge_apk bridge_system bridge_pkg
    bridge_apk="$(xposed_context_bridge_apk "$script_dir" "$api_level")" || return 1
    bridge_system="$(xposed_context_bridge_system_apk_for_api "$api_level")"
    bridge_pkg="$(xposed_context_bridge_pkg_for_api "$api_level")"
    echo "==> push + pm install SolarContextBridge ($bridge_pkg)"
    if adb_push_to_system "$bridge_apk" "$bridge_system" 644; then
        adb_su_sh "rm -rf /data/app/${bridge_pkg}* /data/app-lib/${bridge_pkg}*" 2>/dev/null || true
        adb_pm_install_internal "$bridge_system" "$bridge_pkg" 2>/dev/null || true
        sleep 1
        xposed_ensure_module_enabled_via_adb "$bridge_pkg" 2>/dev/null || true
    else
        echo "WARN: bridge APK push failed (device rebooting?) — retry after boot: enable-xposed-module-adb.sh $bridge_pkg on" >&2
    fi

    local theme_apk
    theme_apk="$(xposed_theme_font_apk "$script_dir")" || return 1
    echo "==> push + pm install SolarThemeFont ($XPOSED_THEME_FONT_PKG)"
    if adb_push_to_system "$theme_apk" "$XPOSED_SYSTEM_THEME_FONT_APK" 644; then
        adb_su_sh "rm -rf /data/app/${XPOSED_THEME_FONT_PKG}* /data/app-lib/${XPOSED_THEME_FONT_PKG}*" 2>/dev/null || true
        adb_pm_install_internal "$XPOSED_SYSTEM_THEME_FONT_APK" "$XPOSED_THEME_FONT_PKG" 2>/dev/null || true
        sleep 1
        xposed_ensure_module_enabled_via_adb "$XPOSED_THEME_FONT_PKG" 2>/dev/null || true
    else
        echo "WARN: theme font APK push failed — retry: enable-xposed-module-adb.sh $XPOSED_THEME_FONT_PKG on" >&2
    fi

    local rockbox_ime_apk
    rockbox_ime_apk="$(xposed_rockbox_ime_apk "$script_dir")" || return 1
    echo "==> push + pm install SolarRockboxIme ($XPOSED_ROCKBOX_IME_PKG)"
    if adb_push_to_system "$rockbox_ime_apk" "$XPOSED_SYSTEM_ROCKBOX_IME_APK" 644; then
        adb_su_sh "rm -rf /data/app/${XPOSED_ROCKBOX_IME_PKG}* /data/app-lib/${XPOSED_ROCKBOX_IME_PKG}*" 2>/dev/null || true
        adb_pm_install_internal "$XPOSED_SYSTEM_ROCKBOX_IME_APK" "$XPOSED_ROCKBOX_IME_PKG" 2>/dev/null || true
        sleep 1
        xposed_ensure_module_enabled_via_adb "$XPOSED_ROCKBOX_IME_PKG" 2>/dev/null || true
    else
        echo "WARN: Rockbox IME APK push failed — retry: enable-xposed-module-adb.sh $XPOSED_ROCKBOX_IME_PKG on" >&2
    fi

    if [ "$api_level" != "17" ] && [ "$api_level" != "18" ]; then
        local rockbox_compat_apk
        rockbox_compat_apk="$(xposed_rockbox_compat_apk "$script_dir")" || return 1
        echo "==> push + pm install SolarRockboxCompat ($XPOSED_ROCKBOX_COMPAT_PKG)"
        if adb_push_to_system "$rockbox_compat_apk" "$XPOSED_SYSTEM_ROCKBOX_COMPAT_APK" 644; then
            adb_su_sh "rm -rf /data/app/${XPOSED_ROCKBOX_COMPAT_PKG}* /data/app-lib/${XPOSED_ROCKBOX_COMPAT_PKG}*" 2>/dev/null || true
            adb_pm_install_internal "$XPOSED_SYSTEM_ROCKBOX_COMPAT_APK" "$XPOSED_ROCKBOX_COMPAT_PKG" 2>/dev/null || true
            sleep 1
            xposed_ensure_module_enabled_via_adb "$XPOSED_ROCKBOX_COMPAT_PKG" 2>/dev/null || true
        else
            echo "WARN: Rockbox compat APK push failed — retry: enable-xposed-module-adb.sh $XPOSED_ROCKBOX_COMPAT_PKG on" >&2
        fi
    fi

    local notpipe_bridge_apk
    notpipe_bridge_apk="$(xposed_notpipe_bridge_apk "$script_dir")" || return 1
    echo "==> push + pm install SolarNotPipeBridge ($XPOSED_NOTPIPE_BRIDGE_PKG)"
    if adb_push_to_system "$notpipe_bridge_apk" "$XPOSED_SYSTEM_NOTPIPE_BRIDGE_APK" 644; then
        adb_su_sh "rm -rf /data/app/${XPOSED_NOTPIPE_BRIDGE_PKG}* /data/app-lib/${XPOSED_NOTPIPE_BRIDGE_PKG}*" 2>/dev/null || true
        adb_pm_install_internal "$XPOSED_SYSTEM_NOTPIPE_BRIDGE_APK" "$XPOSED_NOTPIPE_BRIDGE_PKG" 2>/dev/null || true
        sleep 1
        xposed_ensure_module_enabled_via_adb "$XPOSED_NOTPIPE_BRIDGE_PKG" 2>/dev/null || true
    else
        echo "WARN: NotPipe bridge APK push failed — retry: enable-xposed-module-adb.sh $XPOSED_NOTPIPE_BRIDGE_PKG on" >&2
    fi

    echo "==> Seed runtime jar + run 99XposedInit.sh"
    xposed_seed_runtime_via_adb

    adb_su_sh "sync"

    echo "==> Verify Xposed paths on device"
    xposed_verify_device_via_adb || return 1
}
