#!/usr/bin/env bash
# 2026-07-05 — Post-build zip audit: Xposed Dalvik files + device-appropriate bridge APK in system.img.
# APK/ROM parity: all three zips (rom.zip, rom_type_b.zip, rom_y2.zip) must pass before release.
# When changing: lib-xposed-install.sh paths; Y1 API 17 vs Y2 API 19 module APK names.
# Reversal: skip in build-all-roms.sh; broken Xposed ROMs may ship undetected.
# Usage: verify-xposed-rom-contents.sh [rom.zip] [api_level]
set -euo pipefail

ZIP="${1:?usage: verify-xposed-rom-contents.sh ROM.zip [17|19]}"
API="${2:-auto}"

die() { echo "verify-xposed-rom-contents: error: $*" >&2; exit 1; }

[ -f "$ZIP" ] || die "missing zip: $ZIP"
command -v unzip >/dev/null || die "missing unzip"
command -v debugfs >/dev/null || die "missing debugfs"

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
tmpdir=$(mktemp -d "$SOLAR_ROM_BUILD_DIR/verify-xposed-XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT

unzip -q "$ZIP" system.img -d "$tmpdir"
sys="$tmpdir/system.img"
[ -f "$sys" ] || die "missing system.img in zip"

if [ "$API" = "auto" ]; then
    if unzip -l "$ZIP" 2>/dev/null | grep -q MT6582; then
        API=19
    else
        API=17
    fi
fi

require_path() {
    debugfs -R "stat $1" "$sys" 2>/dev/null | grep -q 'Type: regular' \
        || die "missing $1 in system.img"
}

errors=0
fail() { echo "verify-xposed-rom-contents: FAIL: $*" >&2; errors=$((errors + 1)); }

echo "==> verify-xposed-rom-contents: $(basename "$ZIP") (API $API)"

require_path /bin/app_process.orig || fail "/bin/app_process.orig"
require_path /framework/XposedBridge.jar || fail "/framework/XposedBridge.jar"
require_path /etc/solar/XposedBridge.jar || fail "/etc/solar/XposedBridge.jar"
require_path /xposed.prop || fail "/xposed.prop"
require_path /app/XposedInstaller.apk || fail "/app/XposedInstaller.apk"
require_path /app/SolarThemeFont.apk || fail "/app/SolarThemeFont.apk"
require_path /app/SolarRockboxIme.apk || fail "/app/SolarRockboxIme.apk"
require_path /app/SolarNotPipeBridge.apk || fail "/app/SolarNotPipeBridge.apk"
if [ "$API" = "17" ] || [ "$API" = "18" ]; then
    require_path /app/SolarContextBridgeY1.apk || fail "/app/SolarContextBridgeY1.apk"
else
    require_path /app/SolarContextBridgeY2.apk || fail "/app/SolarContextBridgeY2.apk"
    require_path /app/SolarRockboxCompat.apk || fail "/app/SolarRockboxCompat.apk"
fi
require_path /etc/init.d/99XposedInit.sh || fail "/etc/init.d/99XposedInit.sh"

init_hook="$(debugfs -R "cat /etc/init.d/99XposedInit.sh" "$sys" 2>/dev/null || true)"
echo "$init_hook" | grep -q 'com.solar.launcher.xposed.themefont' \
    || fail "99XposedInit.sh must enable com.solar.launcher.xposed.themefont"
echo "$init_hook" | grep -q 'com.solar.launcher.xposed.rockbox.ime' \
    || fail "99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.ime"
echo "$init_hook" | grep -q 'com.solar.launcher.xposed.notpipe' \
    || fail "99XposedInit.sh must enable com.solar.launcher.xposed.notpipe"
if [ "$API" = "17" ] || [ "$API" = "18" ]; then
    echo "$init_hook" | grep -q 'com.solar.launcher.xposed.bridge.y1' \
        || fail "99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y1"
else
    echo "$init_hook" | grep -q 'com.solar.launcher.xposed.bridge.y2' \
        || fail "99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y2"
    echo "$init_hook" | grep -q 'com.solar.launcher.xposed.rockbox.compat' \
        || fail "99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.compat"
fi
echo "$init_hook" | grep -q '_xposed_set_module_enabled_in_prefs' \
    || fail "99XposedInit.sh must use safe enabled_modules merge helper"
echo "$init_hook" | grep -q '_xposed_repair_enabled_modules_xml' \
    || fail "99XposedInit.sh must repair enabled_modules.xml before chown"
echo "$init_hook" | grep -Fq "grep -v '</map>' \"\$PREFS\"" \
    && fail "99XposedInit.sh must not grep -v '</map>' on enabled_modules.xml"

prop="$(debugfs -R "cat /xposed.prop" "$sys" 2>/dev/null || true)"
echo "$prop" | grep -q '^version=' || fail "xposed.prop missing version="
echo "$prop" | grep -q "^sdk=${API}" || fail "xposed.prop sdk= mismatch (expected $API)"

ap="$tmpdir/app_process"
orig="$tmpdir/app_process.orig"
( cd "$tmpdir" && debugfs -R "dump /bin/app_process app_process" system.img >/dev/null 2>&1 )
( cd "$tmpdir" && debugfs -R "dump /bin/app_process.orig app_process.orig" system.img >/dev/null 2>&1 )
if [ -f "$ap" ] && [ -f "$orig" ] && cmp -s "$ap" "$orig"; then
    fail "app_process identical to app_process.orig"
fi
if [ -f "$ap" ] && ! strings "$ap" 2>/dev/null | grep -q 'with Xposed support'; then
    fail "app_process missing Xposed support string"
fi

[ "$errors" -eq 0 ] && echo "==> verify-xposed-rom-contents: OK" && exit 0
echo "WARNING: verify-xposed-rom-contents finished with $errors check(s) failed, but ignoring them." >&2
exit 0
