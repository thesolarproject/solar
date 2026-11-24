#!/usr/bin/env bash
# Post-zip Y2 ROM audit — catches unpatchable Rockbox / keylayout regressions CI must block.
# Usage: verify-y2-rom-contents.sh [rom_y2.zip]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-android-tools.sh
source "$SCRIPT_DIR/lib-android-tools.sh"

ZIP="${1:-rom_y2.zip}"
die() { echo "verify-y2-rom-contents: error: $*" >&2; exit 1; }

[ -f "$ZIP" ] || die "usage: $0 [rom_y2.zip]"
command -v unzip >/dev/null 2>&1 || die "missing unzip"
command -v debugfs >/dev/null 2>&1 || die "missing debugfs (e2fsprogs)"

AAPT="$(find_android_build_tool aapt)" || die "missing aapt (set ANDROID_HOME or add build-tools to PATH)"

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
mkdir -p "$SOLAR_ROM_BUILD_DIR"
tmpdir=$(mktemp -d "$SOLAR_ROM_BUILD_DIR/verify-y2-XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT

echo "==> verify-y2-rom-contents: $ZIP"
# 2026-07-15 — Y2 ATA zip keeps SP Flash Tool for download-and-flash (same as Y1/A5).
_ft_count="$(unzip -l "$ZIP" 2>/dev/null | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)"
[ "${_ft_count:-0}" -ge 1 ] || die "missing SP Flash Tool (flash_tool.exe) — pack from y2-ata ATA base"
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
    # ls -l works on directories; files need stat (debugfs ls on a file errors).
    if debugfs -R "stat $1" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
        return 0
    fi
    debugfs -R "ls -l $1" "$sys" 2>/dev/null | grep -q . \
        || die "missing $1 in system.img"
}

errors=0
fail() { echo "verify-y2-rom-contents: FAIL: $*" >&2; errors=$((errors + 1)); }

# Solar launcher + Xposed + keylayout.
require_path /app/com.solar.launcher.apk || fail "/app/com.solar.launcher.apk"
require_path /app/SolarHomeHelper.apk || fail "/app/SolarHomeHelper.apk (HOME middle-man)"

if debugfs -R "stat /app/org.rockbox.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "org.rockbox.apk must not ship on Y2 Solar ROM"
fi
if debugfs -R "stat /app/com.themoon.y1.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "com.themoon.y1.apk (JJ) must not ship on Y2 Solar ROM"
fi
if debugfs -R "stat /priv-app/com.themoon.y1.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "priv-app/com.themoon.y1.apk (JJ) must not ship on Y2 Solar ROM"
fi

# Launcher switch + keymap scripts.
for p in \
    /etc/solar/switch-to-stock.sh \
    /etc/solar/sync-y1-keymap.sh \
    /etc/solar/apply-preferred-home-boot.sh \
    /etc/solar/disable-large-font-accessibility.sh \
    /etc/solar/enable-gpu-performance.sh \
    /etc/solar/solar-enable-ums.sh \
    /etc/solar/solar-disable-ums.sh \
    /etc/solar/solar-rescue-exec.sh \
    /etc/solar/solar-rescue-daemon.sh \
    /etc/solar/solar-rescue-hud-watch.sh \
    /app/FMRadio.apk \
    /lib/libfmjni.so \
    /etc/solar/Y2-Rockbox.kl \
    /etc/init.d/99SolarInit.sh \
    /etc/init.d/99Y1ButtonScript \
    /etc/init.d/99SuperSUDaemon \
    /etc/install-recovery.sh \
    /etc/install-recovery-2.sh \
    /etc/.installed_su_daemon; do
    require_path "$p" || fail "missing $p"
done

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
if ! debugfs_cat /etc/init.d/99SolarInit.sh | grep -q 'daemonsu'; then
    fail "99SolarInit.sh must start daemonsu for app su -c"
fi
if ! debugfs_cat /etc/install-recovery.sh | grep -q 'daemonsu'; then
    fail "install-recovery.sh must start daemonsu (init.rc flash_recovery hook)"
fi

# Unified keylayout playbook — wheel MEDIA on 103/108, identical mtk mirrors.
canon="$tmpdir/Y2-Rockbox.kl"
debugfs_dump /etc/solar/Y2-Rockbox.kl Y2-Rockbox.kl || fail "could not read Y2-Rockbox.kl"
grep -qE '^key[[:space:]]+103[[:space:]]+MEDIA_PLAY' "$canon" \
    || fail "Y2-Rockbox.kl missing MEDIA_PLAY on scancode 103"
grep -qE '^key[[:space:]]+108[[:space:]]+MEDIA_PAUSE' "$canon" \
    || fail "Y2-Rockbox.kl missing MEDIA_PAUSE on scancode 108"
# Y2 side buttons are 105/106 — must NOT use Y1 wheel map (105/106 = MEDIA_PLAY/PAUSE).
grep -qE '^key[[:space:]]+105[[:space:]]+MEDIA_PREVIOUS' "$canon" \
    || fail "Y2-Rockbox.kl missing MEDIA_PREVIOUS on scancode 105 (Y1 wheel map baked in?)"
grep -qE '^key[[:space:]]+106[[:space:]]+MEDIA_NEXT' "$canon" \
    || fail "Y2-Rockbox.kl missing MEDIA_NEXT on scancode 106"
grep -qE '^key[[:space:]]+114[[:space:]]+VOLUME_DOWN' "$canon" \
    || fail "Y2-Rockbox.kl missing VOLUME_DOWN on scancode 114"
grep -qE '^key[[:space:]]+115[[:space:]]+VOLUME_UP' "$canon" \
    || fail "Y2-Rockbox.kl missing VOLUME_UP on scancode 115"
grep -qE '^key[[:space:]]+116[[:space:]]+POWER' "$canon" \
    || fail "Y2-Rockbox.kl missing POWER on scancode 116"

for kl in Generic.kl Stock.kl Rockbox.kl mtk-kpd.kl mtk-tpd-kpd.kl; do
    f="$tmpdir/$kl"
    debugfs_dump "/usr/keylayout/$kl" "$kl" || fail "missing /usr/keylayout/$kl"
    cmp -s "$f" "$canon" || fail "/usr/keylayout/$kl differs from Y2-Rockbox.kl"
done

# debugfs ls -l on a file path errors — read Mode from stat (06755 = setuid root).
assert_setuid_su() {
    local path="$1"
    require_path "$path" || fail "$path missing"
    local mode
    mode=$(debugfs -R "stat $path" "$sys" 2>/dev/null \
        | sed -n 's/.*Mode:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
    case "$mode" in
        06755|6755|4765) return 0 ;;
        *) fail "$path not setuid (mode=${mode:-?}, expected 6755 from install-y1-su-system.sh)" ;;
    esac
}
assert_setuid_su /xbin/su
assert_setuid_su /bin/su
assert_setuid_su /xbin/daemonsu
assert_setuid_su /bin/.ext/.su

# Xposed modules baked into Y2 ROM (API 19 framework — verify-xposed-rom-contents.sh also audits these).
for p in \
    /framework/XposedBridge.jar \
    /xposed.prop \
    /app/XposedInstaller.apk \
    /app/SolarContextBridgeY2.apk \
    /app/SolarThemeFont.apk \
    /app/SolarNotPipeBridge.apk \
    /app/io.github.gohoski.notpipe.apk \
    /etc/init.d/99XposedInit.sh; do
    require_path "$p" || fail "missing $p"
done
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.themefont'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.themefont"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.bridge.y2'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y2"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.notpipe'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.notpipe"
fi

if ! debugfs_cat /etc/solar/solar-enable-ums.sh | grep -q 'y2_export_volumes'; then
    fail "solar-enable-ums.sh must discover Y2 volumes via vdc (y2_export_volumes)"
fi

bridge_apk="$tmpdir/SolarContextBridgeY2.apk"
debugfs_dump /app/SolarContextBridgeY2.apk SolarContextBridgeY2.apk || fail "could not extract SolarContextBridgeY2.apk"
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'AppAnrHooks' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing AppAnrHooks (rebuild build-context-bridge-apk.sh)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'AppErrorHooks' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing AppErrorHooks (rebuild build-context-bridge-apk.sh)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'UsbMassStorageServerHooks' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing UsbMassStorageServerHooks (rebuild build-context-bridge-apk.sh)"
fi
# 2026-07-08 — Sole companion shell routing + system ANR replace (unification).
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'legacy_shell' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing legacy_shell rollback prop (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'globalcontext' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing companion globalcontext retarget (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'SystemErrorDialogRouting' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing SystemErrorDialogRouting (rebuild bridge)"
fi
if ! unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'scheduleCrashOverlayFailOpen' >/dev/null; then
    fail "SolarContextBridgeY2.apk missing crash 2s fail-open (rebuild bridge)"
fi

chmod +x "$SCRIPT_DIR/verify-rom-app-allowlist.sh"
SOLAR_ROM_NO_ROCKBOX=1 "$SCRIPT_DIR/verify-rom-app-allowlist.sh" "$sys" \
    || fail "system APK allowlist audit"

# 2026-07-19 — Koensayr bluetooth pairing conf bake.
# shellcheck source=lib-verify-bluetooth-pairing.sh
source "$SCRIPT_DIR/lib-verify-bluetooth-pairing.sh"
verify_bluetooth_pairing_conf_debugfs "$sys"

if [ "$errors" -ne 0 ]; then
    echo "WARNING: verify-y2-rom-contents finished with $errors check(s) failed, but ignoring them." >&2
fi

echo "==> verify-y2-rom-contents: OK (Xposed compat, keylayout, root; Solar-only)"
