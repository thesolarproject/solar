#!/system/bin/sh
# 2026-07-05 — ROM boot: seed XposedBridge.jar, enable production modules, apply staged app_process.
# APK/ROM parity: same enablement chain as XposedModuleEnsurer; runs before Solar app on every boot.
# When changing: XposedModuleRegistry required packages; lib-xposed-install.sh paths; user-disable file.
# Reversal: remove from 99SolarInit.sh call chain; modules may stay disabled after OTA.
# Seed XposedBridge.jar + apply staged app_process if adb hit ETXTBUSY on live zygote.

# Pending app_process from install-xposed-adb.sh (live zygote holds /system/bin/app_process open).
if [ -f /system/bin/app_process.xposed.staged ]; then
    cat /system/bin/app_process.xposed.staged > /system/bin/app_process 2>/dev/null \
        || cp -f /system/bin/app_process.xposed.staged /system/bin/app_process
    chmod 755 /system/bin/app_process
    chown root:shell /system/bin/app_process 2>/dev/null || chown root.root /system/bin/app_process
    rm -f /system/bin/app_process.xposed.staged
fi

if [ ! -f /system/framework/XposedBridge.jar ]; then
    log -p w -t XposedInit "missing /system/framework/XposedBridge.jar"
    exit 0
fi

mkdir -p /data/data/de.robv.android.xposed.installer/bin
mkdir -p /data/data/de.robv.android.xposed.installer/conf
mkdir -p /data/data/de.robv.android.xposed.installer/log
chmod 771 /data/data/de.robv.android.xposed.installer 2>/dev/null
chmod 771 /data/data/de.robv.android.xposed.installer/bin 2>/dev/null
chmod 771 /data/data/de.robv.android.xposed.installer/conf 2>/dev/null
chmod 771 /data/data/de.robv.android.xposed.installer/log 2>/dev/null

cp -f /system/framework/XposedBridge.jar /data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar
chmod 644 /data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar
rm -f /data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar.newversion
rm -f /data/data/de.robv.android.xposed.installer/conf/disabled
# Empty modules.list — XposedBridge v54 aborts zygote init if this file is missing.
if [ ! -f /data/data/de.robv.android.xposed.installer/conf/modules.list ]; then
    touch /data/data/de.robv.android.xposed.installer/conf/modules.list
fi

# Rebuild enabled_modules.xml when merge dropped the opening map wrapper on single-line prefs files.
_xposed_repair_enabled_modules_xml() {
    PREFS="/data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml"
    mkdir -p "$(dirname "$PREFS")"
    if [ -f "$PREFS" ] && grep -q '<?xml' "$PREFS" 2>/dev/null \
        && grep -q '<map>' "$PREFS" 2>/dev/null \
        && grep -q '^</map>' "$PREFS" 2>/dev/null; then
        return 0
    fi
    TMP="${PREFS}.repair.$$"
    {
        echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        echo "<map>"
        grep '<int name=' "$PREFS" 2>/dev/null || true
        echo "</map>"
    } > "$TMP"
    mv "$TMP" "$PREFS"
}

# Write one module toggle into enabled_modules.xml without stripping the root <map> wrapper.
_xposed_set_module_enabled_in_prefs() {
    local pkg="$1"
    local val="${2:-1}"
    PREFS="/data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml"
    _xposed_repair_enabled_modules_xml
    if [ ! -f "$PREFS" ]; then
        cat > "$PREFS" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
</map>
EOF
    fi
    if grep -q "name=\"$pkg\"" "$PREFS" 2>/dev/null; then
        sed -i "s|<int name=\"$pkg\" value=\"[^\"]*\"|<int name=\"$pkg\" value=\"$val\"|" "$PREFS" 2>/dev/null || true
    else
        # Only drop the closing map tag line — never strip every line containing that tag.
        grep -v '^</map>$' "$PREFS" > "${PREFS}.tmp"
        echo "    <int name=\"$pkg\" value=\"$val\" />" >> "${PREFS}.tmp"
        echo "</map>" >> "${PREFS}.tmp"
        mv "${PREFS}.tmp" "$PREFS"
    fi
}

# 2026-07-05 — Solar Debug UI writes /data/local/solar/xposed-user-disabled (one pkg per line).
_xposed_user_disabled() {
    local pkg="$1"
    [ -f /data/local/solar/xposed-user-disabled ] \
        && grep -qxF "$pkg" /data/local/solar/xposed-user-disabled 2>/dev/null
}

# Enable baked Xposed modules on first boot — context bridge (Y1 or Y2) + shared theme font.
_xposed_enable_baked_module() {
    local pkg="$1"
    local system_apk="$2"
    # User chose to keep a required module off — do not force-enable on boot.
    if _xposed_user_disabled "$pkg"; then
        return 0
    fi
    local live_apk resolved_apk=""
    # head(1) missing on Y2 stock shell — sed first line only.
    live_apk="$(pm path "$pkg" 2>/dev/null | sed -n '1s/package://p')"
    if [ -n "$live_apk" ] && [ -f "$live_apk" ]; then
        resolved_apk="$live_apk"
    elif [ -f "$system_apk" ]; then
        resolved_apk="$system_apk"
    fi
    [ -n "$resolved_apk" ] || return 0
    grep -qxF "$resolved_apk" /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null \
        || echo "$resolved_apk" >> /data/data/de.robv.android.xposed.installer/conf/modules.list
    _xposed_set_module_enabled_in_prefs "$pkg" 1
}

# Context bridge — device-specific APK (Y1 BACK-long vs Y2 power-hold + BACK-long).
BRIDGE_PKG=""
if [ -f /system/app/SolarContextBridgeY1.apk ]; then
    BRIDGE_PKG="com.solar.launcher.xposed.bridge.y1"
elif [ -f /system/app/SolarContextBridgeY2.apk ]; then
    BRIDGE_PKG="com.solar.launcher.xposed.bridge.y2"
elif [ -f /system/app/SolarContextBridge.apk ]; then
    BRIDGE_PKG="com.solar.launcher.xposed.bridge.y2"
fi
if [ -n "$BRIDGE_PKG" ]; then
    # Purge stale bridge APK paths (legacy single-package + old pm install suffixes).
    grep -v 'com.solar.launcher.xposed.bridge' \
        /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null \
        > /data/data/de.robv.android.xposed.installer/conf/modules.list.tmp \
        && mv /data/data/de.robv.android.xposed.installer/conf/modules.list.tmp \
            /data/data/de.robv.android.xposed.installer/conf/modules.list
    if [ -f /system/app/SolarContextBridgeY1.apk ]; then
        _xposed_enable_baked_module "$BRIDGE_PKG" "/system/app/SolarContextBridgeY1.apk"
    elif [ -f /system/app/SolarContextBridgeY2.apk ]; then
        _xposed_enable_baked_module "$BRIDGE_PKG" "/system/app/SolarContextBridgeY2.apk"
    elif [ -f /system/app/SolarContextBridge.apk ]; then
        _xposed_enable_baked_module "$BRIDGE_PKG" "/system/app/SolarContextBridge.apk"
    fi
fi

# Theme font — shared module; fail-open when no sidecar exists on primary storage.
if [ -f /system/app/SolarThemeFont.apk ]; then
    _xposed_enable_baked_module "com.solar.launcher.xposed.themefont" "/system/app/SolarThemeFont.apk"
fi

# Rockbox IME — Y1+Y2 wheel OK confirms Rockbox search/rename dialogs (standalone module).
if [ -f /system/app/SolarRockboxIme.apk ]; then
    _xposed_enable_baked_module "com.solar.launcher.xposed.rockbox.ime" "/system/app/SolarRockboxIme.apk"
fi

# Rockbox Y2 compat — shell/exec bridge for staged lib; absent on Y1 ROMs.
if [ -f /system/app/SolarRockboxCompat.apk ]; then
    _xposed_enable_baked_module "com.solar.launcher.xposed.rockbox.compat" "/system/app/SolarRockboxCompat.apk"
fi

# Solar YouTube — notPipe IPC + wheel player hooks (Y1 + Y2).
if [ -f /system/app/SolarNotPipeBridge.apk ]; then
    _xposed_enable_baked_module "com.solar.launcher.xposed.notpipe" "/system/app/SolarNotPipeBridge.apk"
fi

# Dev powermenu test APK must not stay enabled — it suppresses GlobalActions without Solar overlay.
if grep -q 'com.solar.launcher.xposed.powermenu' \
    /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml 2>/dev/null; then
    sed -i 's|<int name="com.solar.launcher.xposed.powermenu" value="1"|<int name="com.solar.launcher.xposed.powermenu" value="0"|' \
        /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml 2>/dev/null || true
    grep -v 'com.solar.launcher.xposed.powermenu' \
        /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null \
        > /data/data/de.robv.android.xposed.installer/conf/modules.list.tmp \
        && mv /data/data/de.robv.android.xposed.installer/conf/modules.list.tmp \
            /data/data/de.robv.android.xposed.installer/conf/modules.list
fi

# su-created files are root-owned; Installer app must own conf/ so it can rewrite modules.list.
_xposed_installer_uid() {
    grep '^de.robv.android.xposed.installer ' /data/system/packages.list 2>/dev/null | while read pkg uid rest; do
        echo "$uid"
    done
}
INSTALLER_UID="$(_xposed_installer_uid)"
_xposed_repair_enabled_modules_xml

if [ -n "$INSTALLER_UID" ]; then
    mkdir -p /data/data/de.robv.android.xposed.installer/shared_prefs
    # JB chown has no -R — walk tree (chown -R fails with "No such user '-R'").
    find /data/data/de.robv.android.xposed.installer -exec chown "$INSTALLER_UID:$INSTALLER_UID" {} \; 2>/dev/null
    chmod 771 /data/data/de.robv.android.xposed.installer \
        /data/data/de.robv.android.xposed.installer/bin \
        /data/data/de.robv.android.xposed.installer/conf \
        /data/data/de.robv.android.xposed.installer/log \
        /data/data/de.robv.android.xposed.installer/shared_prefs 2>/dev/null
    chmod 644 /data/data/de.robv.android.xposed.installer/bin/XposedBridge.jar 2>/dev/null
    chmod 664 /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null
    chmod 660 /data/data/de.robv.android.xposed.installer/shared_prefs/*.xml 2>/dev/null
fi
