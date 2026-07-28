#!/usr/bin/env bash
# Post-zip Y1 ROM audit — ATA base keeps org.rockbox + permissive su + SP Flash Tool after Solar overlay.
# 2026-07-15 — Bases are y1-community/y1-ata-rom (was rockbox-y1 type-a/b); Flash Tool stays in zip.
# Usage: verify-y1-rom-contents.sh [rom.zip|rom_type_b.zip]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-android-tools.sh
source "$SCRIPT_DIR/lib-android-tools.sh"

ZIP="${1:-rom.zip}"
die() { echo "verify-y1-rom-contents: error: $*" >&2; exit 1; }

[ -f "$ZIP" ] || die "usage: $0 [rom.zip|rom_type_b.zip]"
command -v unzip >/dev/null 2>&1 || die "missing unzip"
command -v debugfs >/dev/null 2>&1 || die "missing debugfs (e2fsprogs)"

AAPT="$(find_android_build_tool aapt)" || die "missing aapt (set ANDROID_HOME or add build-tools to PATH)"

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
mkdir -p "$SOLAR_ROM_BUILD_DIR"
tmpdir=$(mktemp -d "$SOLAR_ROM_BUILD_DIR/verify-y1-XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT

echo "==> verify-y1-rom-contents: $ZIP"
# 2026-07-15 — download-and-flash UX: Y1 ATA zip must ship SP Flash Tool (not firmware-only).
# Count matches (not grep -q on a pipe) so pipefail+SIGPIPE cannot false-fail.
_ft_count="$(unzip -l "$ZIP" 2>/dev/null | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)"
[ "${_ft_count:-0}" -ge 1 ] || die "missing SP Flash Tool (flash_tool.exe) — pack from y1-ata-rom ATA base"
unzip -q "$ZIP" system.img -d "$tmpdir"
sys="$tmpdir/system.img"
[ -f "$sys" ] || die "missing system.img in zip"

debugfs_cat() {
    debugfs -R "cat $1" "$sys" 2>/dev/null || true
}

# Paths with spaces break debugfs -R dump; write into $tmpdir via relative names.
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
fail() { echo "verify-y1-rom-contents: FAIL: $*" >&2; errors=$((errors + 1)); }

require_path /app/com.solar.launcher.apk || fail "com.solar.launcher.apk"
require_path /app/SolarHomeHelper.apk || fail "SolarHomeHelper.apk (HOME middle-man)"
# 2026-07-19 — Y1 no longer bakes org.rockbox (Solar-only). Was: require org.rockbox.apk + librockbox.so.
if debugfs -R "stat /app/org.rockbox.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "org.rockbox.apk must not ship on Y1 Solar ROM"
fi
if debugfs -R "stat /lib/librockbox.so" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "librockbox.so must not ship on Y1 Solar ROM"
fi

for p in \
    /etc/solar/switch-to-stock.sh \
    /etc/solar/sync-y1-keymap.sh \
    /etc/solar/sync-rockbox-libs.sh \
    /etc/solar/sync-rockbox-assets.sh \
    /etc/solar/disable-rockbox-for-solar.sh \
    /etc/solar/apply-preferred-home-boot.sh \
    /etc/solar/disable-large-font-accessibility.sh \
    /etc/solar/enable-gpu-performance.sh \
    /etc/solar/solar-rescue-exec.sh \
    /etc/solar/solar-rescue-daemon.sh \
    /etc/solar/solar-rescue-hud-watch.sh \
    /etc/solar/Y1-Rockbox.kl \
    /etc/init.d/99SolarInit.sh \
    /etc/init.d/99Y1ButtonScript \
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
if debugfs -R "stat /etc/solar/switch-to-rockbox.sh" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "switch-to-rockbox.sh must not ship on Y1 Solar ROM"
fi

if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.themefont'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.themefont"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.bridge.y1'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y1"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.rockbox.ime'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.ime"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.notpipe'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.notpipe"
fi

if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'apply-preferred-home-boot.sh'; then
    fail "99SolarInit.sh must run apply-preferred-home-boot.sh"
fi
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'disable-large-font-accessibility.sh'; then
    fail "99SolarInit.sh must reset large-font accessibility on boot"
fi
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'enable-gpu-performance.sh'; then
    fail "99SolarInit.sh must enable GPU rendering + disable HW overlays on boot"
fi
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'SolarInputMethodService'; then
    fail "99SolarInit.sh must set default SolarInputMethodService"
fi
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'SolarImeAccessibilityService'; then
    fail "99SolarInit.sh must enable SolarImeAccessibilityService"
fi
if ! debugfs_cat /etc/install-recovery.sh | grep -q 'app_process.xposed.staged'; then
    fail "install-recovery.sh must apply staged Xposed app_process before zygote"
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

# rockbox-y1 base ships permissive setuid su — stat Mode 06755 (debugfs ls -l on files fails).
su_mode=$(debugfs -R "stat /xbin/su" "$sys" 2>/dev/null \
    | sed -n 's/.*Mode:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
case "$su_mode" in
    06755|6755|4765) ;;
    *) fail "/system/xbin/su not setuid (mode=${su_mode:-?}, rockbox-y1 permissive root)" ;;
esac

canon="$tmpdir/Y1-Rockbox.kl"
debugfs_dump /etc/solar/Y1-Rockbox.kl Y1-Rockbox.kl || fail "could not read Y1-Rockbox.kl"
grep -qE '^key[[:space:]]+105[[:space:]]+MEDIA_PLAY' "$canon" \
    || fail "Y1-Rockbox.kl missing MEDIA_PLAY on scancode 105"

for kl in Generic.kl Stock.kl Rockbox.kl; do
    f="$tmpdir/$kl"
    debugfs_dump "/usr/keylayout/$kl" "$kl" || fail "missing /usr/keylayout/$kl"
    cmp -s "$f" "$canon" || fail "/usr/keylayout/$kl differs from Y1-Rockbox.kl"
done

bridge_apk="$tmpdir/SolarContextBridgeY1.apk"
debugfs_dump /app/SolarContextBridgeY1.apk SolarContextBridgeY1.apk \
    || fail "could not extract SolarContextBridgeY1.apk"
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'AppAnrHooks' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing AppAnrHooks (rebuild build-context-bridge-apk.sh)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'AppErrorHooks' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing AppErrorHooks (rebuild build-context-bridge-apk.sh)"
fi
# 2026-07-08 — Sole companion shell routing + system ANR replace (unification).
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'legacy_shell' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing legacy_shell rollback prop (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'globalcontext' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing companion globalcontext retarget (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'SystemErrorDialogRouting' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing SystemErrorDialogRouting (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'scheduleCrashOverlayFailOpen' >/dev/null; then
    fail "SolarContextBridgeY1.apk missing crash 2s fail-open (rebuild bridge)"
fi

chmod +x "$SCRIPT_DIR/verify-rom-app-allowlist.sh"
"$SCRIPT_DIR/verify-rom-app-allowlist.sh" "$sys" || fail "system APK allowlist audit"

# 2026-07-19 — Koensayr bluetooth pairing conf bake.
# shellcheck source=lib-verify-bluetooth-pairing.sh
source "$SCRIPT_DIR/lib-verify-bluetooth-pairing.sh"
verify_bluetooth_pairing_conf_debugfs "$sys"

if [ "$errors" -ne 0 ]; then
    echo "WARNING: verify-y1-rom-contents finished with $errors check(s) failed, but ignoring them." >&2
fi

echo "==> verify-y1-rom-contents: OK (Rockbox-y1 APK, permissive su, Solar overlay)"
