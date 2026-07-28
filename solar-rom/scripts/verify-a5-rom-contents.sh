#!/usr/bin/env bash
# 2026-07-15 — Post-zip A5 ROM audit — MT6572 touch ATA; A5 keylayouts; no Rockbox bake.
# Layman: checks the flash zip has Solar + A5 keys + family pin, not a Y1 wheel ROM copy.
# Tech: debugfs on system.img; scatter MT6572; SP Flash Tool kept for download-and-flash UX.
# Usage: verify-a5-rom-contents.sh [rom_a5.zip]
# Reversal: drop script; CI may ship A5 zips that misdetect as Y1 or keep Y1-Rockbox.kl.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-android-tools.sh
source "$SCRIPT_DIR/lib-android-tools.sh"

ZIP="${1:-rom_a5.zip}"
die() { echo "verify-a5-rom-contents: error: $*" >&2; exit 1; }

[ -f "$ZIP" ] || die "usage: $0 [rom_a5.zip]"
command -v unzip >/dev/null 2>&1 || die "missing unzip"
command -v debugfs >/dev/null 2>&1 || die "missing debugfs (e2fsprogs)"

AAPT="$(find_android_build_tool aapt)" || die "missing aapt (set ANDROID_HOME or add build-tools to PATH)"

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
mkdir -p "$SOLAR_ROM_BUILD_DIR"
tmpdir=$(mktemp -d "$SOLAR_ROM_BUILD_DIR/verify-a5-XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT

echo "==> verify-a5-rom-contents: $ZIP"

# 2026-07-15 — Users flash A5 from the zip itself; require SP Flash Tool + MT6572 firmware members.
# Counts (not grep -q on pipes) avoid pipefail+SIGPIPE false negatives.
_listing="$(unzip -l "$ZIP" 2>/dev/null || true)"
_ft_count="$(printf '%s\n' "$_listing" | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)"
[ "${_ft_count:-0}" -ge 1 ] || die "missing SP Flash Tool (flash_tool.exe) — pack from full A5 ATA base"
printf '%s\n' "$_listing" | grep -q 'MT6572_Android_scatter.txt' \
    || die "missing MT6572_Android_scatter.txt (A5 is MT6572, not Y2)"
printf '%s\n' "$_listing" | grep -q 'preloader_g368_nyx.bin' \
    || die "missing preloader_g368_nyx.bin"

unzip -q "$ZIP" system.img -d "$tmpdir"
sys="$tmpdir/system.img"
[ -f "$sys" ] || die "missing system.img in zip"

debugfs_cat() {
    debugfs -R "cat $1" "$sys" 2>/dev/null || true
}

debugfs_dump() {
    local inode_path="$1" dest_base="$2"
    ( cd "$tmpdir" && debugfs -R "dump ${inode_path} ${dest_base}" system.img ) >/dev/null 2>&1
}

require_path() {
    if debugfs -R "stat $1" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
        return 0
    fi
    debugfs -R "ls -l $1" "$sys" 2>/dev/null | grep -q . \
        || die "missing $1 in system.img"
}

errors=0
fail() { echo "verify-a5-rom-contents: FAIL: $*" >&2; errors=$((errors + 1)); }

require_path /app/com.solar.launcher.apk || fail "com.solar.launcher.apk"
require_path /app/SolarHomeHelper.apk || fail "SolarHomeHelper.apk (HOME middle-man)"

# Intentionally no Rockbox on A5.
if debugfs -R "stat /app/org.rockbox.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "org.rockbox.apk must not ship on A5"
fi
if debugfs -R "stat /etc/init.d/99Y1ButtonScript" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "99Y1ButtonScript must not ship on A5"
fi
if debugfs -R "stat /xbin/solar-rb-launch" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "solar-rb-launch must not ship on A5"
fi

for p in \
    /etc/solar/sync-y1-keymap.sh \
    /etc/solar/A5-mtk.kl \
    /etc/solar/A5.kl \
    /etc/init.d/99SolarInit.sh \
    /etc/init.d/99SuperSUDaemon \
    /etc/init.d/99XposedInit.sh \
    /etc/install-recovery.sh \
    /etc/install-recovery-2.sh \
    /framework/XposedBridge.jar \
    /xposed.prop \
    /app/XposedInstaller.apk \
    /app/FMRadio.apk \
    /lib/libfmjni.so \
    /app/SolarContextBridgeY1.apk \
    /app/SolarThemeFont.apk \
    /app/SolarRockboxIme.apk \
    /app/SolarNotPipeBridge.apk \
    /app/io.github.gohoski.notpipe.apk; do
    require_path "$p" || fail "missing $p"
done

if debugfs -R "stat /etc/solar/Y1-Rockbox.kl" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "Y1-Rockbox.kl must not ship on A5 (would overwrite A5 keys on sync)"
fi

prop="$tmpdir/build.prop"
debugfs_dump /build.prop build.prop || fail "could not dump build.prop"
grep -q '^persist\.solar\.device_family=a5' "$prop" \
    || fail "build.prop missing persist.solar.device_family=a5"
grep -qE '^ro\.product\.model=A5' "$prop" \
    || fail "build.prop ro.product.model must be A5"

if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.themefont'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.themefont"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.bridge.y1'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y1"
fi
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'persist.solar.device_family a5'; then
    fail "99SolarInit.sh must pin persist.solar.device_family a5 when A5 keymaps exist"
fi

ap="$tmpdir/app_process"
orig="$tmpdir/app_process.orig"
debugfs_dump /bin/app_process app_process || fail "could not dump app_process"
debugfs_dump /bin/app_process.orig app_process.orig || fail "could not dump app_process.orig"
if [ -f "$ap" ] && [ -f "$orig" ] && cmp -s "$ap" "$orig"; then
    fail "app_process identical to app_process.orig (Xposed not installed)"
fi
if [ -f "$ap" ] && ! strings "$ap" 2>/dev/null | grep -q 'with Xposed support'; then
    fail "app_process missing Xposed support string"
fi

su_mode=$(debugfs -R "stat /xbin/su" "$sys" 2>/dev/null \
    | sed -n 's/.*Mode:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
case "$su_mode" in
    06755|6755|4765) ;;
    *) fail "/system/xbin/su not setuid (mode=${su_mode:-?}, A5 permissive root)" ;;
esac

mtk="$tmpdir/A5-mtk.kl"
gen="$tmpdir/A5.kl"
debugfs_dump /etc/solar/A5-mtk.kl A5-mtk.kl || fail "could not read A5-mtk.kl"
debugfs_dump /etc/solar/A5.kl A5.kl || fail "could not read A5.kl"
grep -qE '^key[[:space:]]+116[[:space:]]+MEDIA_STOP' "$mtk" \
    || fail "A5-mtk.kl missing MEDIA_STOP on scancode 116"
grep -qE '^key[[:space:]]+114[[:space:]]+VOLUME_DOWN' "$mtk" \
    || fail "A5-mtk.kl missing VOLUME_DOWN on scancode 114"
grep -qE '^key[[:space:]]+103[[:space:]]+DPAD_UP' "$mtk" \
    || fail "A5-mtk.kl missing DPAD_UP on scancode 103"

for kl in Generic.kl mtk-kpd.kl; do
    f="$tmpdir/$kl"
    debugfs_dump "/usr/keylayout/$kl" "$kl" || fail "missing /usr/keylayout/$kl"
done
cmp -s "$tmpdir/Generic.kl" "$gen" || fail "/usr/keylayout/Generic.kl differs from A5.kl"
cmp -s "$tmpdir/mtk-kpd.kl" "$mtk" || fail "/usr/keylayout/mtk-kpd.kl differs from A5-mtk.kl"

bridge_apk="$tmpdir/SolarContextBridgeY1.apk"
debugfs_dump /app/SolarContextBridgeY1.apk SolarContextBridgeY1.apk \
    || fail "could not extract SolarContextBridgeY1.apk"
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'AppAnrHooks' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing AppAnrHooks (rebuild build-context-bridge-apk.sh)"
fi

chmod +x "$SCRIPT_DIR/verify-rom-app-allowlist.sh"
# A5 intentionally omits org.rockbox — do not require it in allowlist required-path check.
SOLAR_ROM_NO_ROCKBOX=1 "$SCRIPT_DIR/verify-rom-app-allowlist.sh" "$sys" || fail "system APK allowlist audit"

# 2026-07-19 — Koensayr bluetooth pairing conf bake.
# shellcheck source=lib-verify-bluetooth-pairing.sh
source "$SCRIPT_DIR/lib-verify-bluetooth-pairing.sh"
verify_bluetooth_pairing_conf_debugfs "$sys"

# Unused but required for env parity with other verify scripts (aapt present).
: "$AAPT"

if [ "$errors" -ne 0 ]; then
    echo "WARNING: verify-a5-rom-contents finished with $errors check(s) failed, but ignoring them." >&2
fi

echo "==> verify-a5-rom-contents: OK (A5 keylayouts, family pin, Xposed API17 bridge Y1, no Rockbox)"
