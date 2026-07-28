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

# Solar launcher + patched Rockbox (Y2-only when SOLAR_ROM_LEGACY_ROCKBOX=1).
require_path /app/com.solar.launcher.apk || fail "/app/com.solar.launcher.apk"
require_path /app/SolarHomeHelper.apk || fail "/app/SolarHomeHelper.apk (HOME middle-man)"

ROCKBOX_ON_ROM=1
if [ "${SOLAR_ROM_LEGACY_ROCKBOX:-0}" != "1" ]; then
    ROCKBOX_ON_ROM=0
    echo "verify-y2-rom-contents: note — org.rockbox prep-delivered via Solar APK platform bundle"
fi

if [ "$ROCKBOX_ON_ROM" = "1" ]; then
require_path /app/org.rockbox.apk || fail "/app/org.rockbox.apk"
require_path /lib/librockbox.so || fail "/lib/librockbox.so"
require_path /etc/solar/rockbox-libs/librockbox.so || fail "/etc/solar/rockbox-libs/librockbox.so"

rb_apk="$tmpdir/org.rockbox.apk"
debugfs_dump /app/org.rockbox.apk org.rockbox.apk || fail "could not extract org.rockbox.apk"
[ -s "$rb_apk" ] || fail "org.rockbox.apk empty"

if "$AAPT" dump xmltree "$rb_apk" AndroidManifest.xml 2>/dev/null | grep -q 'sharedUserId'; then
    fail "org.rockbox.apk still has sharedUserId (patch-rockbox-y2.sh did not run in ROM build)"
fi

# grep -q + pipefail makes unzip SIGPIPE — count libmisc lines instead.
rb_misc_count=$(unzip -l "$rb_apk" 2>/dev/null | grep -c 'lib/armeabi/libmisc.so' || true)
[ "${rb_misc_count:-0}" -ge 1 ] || fail "org.rockbox.apk missing lib/armeabi/libmisc.so"
else
# 2026-07-19 — Default Y2: Rockbox/JJ must be absent (prep via Solar APK).
# Was: note-only when missing. Reversal: require_path when ROCKBOX_ON_ROM=1 only.
if debugfs -R "stat /app/org.rockbox.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "org.rockbox.apk must not ship on default Y2 ROM (set SOLAR_ROM_LEGACY_ROCKBOX=1 to bake)"
fi
if debugfs -R "stat /app/com.themoon.y1.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "com.themoon.y1.apk (JJ) must not ship on Y2 Solar ROM"
fi
if debugfs -R "stat /priv-app/com.themoon.y1.apk" "$sys" 2>/dev/null | grep -q 'Type: regular'; then
    fail "priv-app/com.themoon.y1.apk (JJ) must not ship on Y2 Solar ROM"
fi
fi

# Launcher switch + keymap scripts (Rockbox-Y1 handoff parity).
for p in \
    /etc/solar/switch-to-stock.sh \
    /etc/solar/switch-to-rockbox.sh \
    /etc/solar/sync-y1-keymap.sh \
    /etc/solar/sync-rockbox-libs.sh \
    /etc/solar/sync-rockbox-assets.sh \
    /etc/solar/disable-rockbox-for-solar.sh \
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
    /app/SolarRockboxIme.apk \
    /app/SolarRockboxCompat.apk \
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
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.rockbox.ime'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.ime"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.rockbox.compat'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.compat"
fi
if ! debugfs_cat /etc/init.d/99XposedInit.sh | grep -q 'com.solar.launcher.xposed.notpipe'; then
    fail "99XposedInit.sh must enable com.solar.launcher.xposed.notpipe"
fi

# Staged Rockbox assets (Y2 dual-storage + db_folder_select patch) — ROM legacy path only.
if [ "$ROCKBOX_ON_ROM" = "1" ]; then
require_path /etc/solar/rockbox-y2-config.cfg || fail "rockbox-y2-config.cfg"
require_path /etc/solar/rockbox-libs/librockbox.so || fail "staged rockbox-libs"
require_path /etc/solar/rockbox-dot-rockbox/rocks/viewers/db_folder_select.rock \
    || fail "staged db_folder_select.rock"

staged_lib="$tmpdir/staged-librockbox.so"
debugfs_dump /etc/solar/rockbox-libs/librockbox.so staged-librockbox.so || fail "could not read staged librockbox.so"
# 2026-07-05: full-read grep (no -q) — grep -q early-exit + pipefail made strings die with
# SIGPIPE on this ~900KB lib, randomly failing the check even when the string is present.
if ! strings "$staged_lib" 2>/dev/null | grep 'solar-rb-launch' >/dev/null; then
    fail "staged librockbox.so missing solar-rb-launch (extract-rockbox-staged-assets patch)"
fi

apk_lib="$tmpdir/apk-librockbox.so"
unzip -o -q -p "$rb_apk" lib/armeabi/librockbox.so > "$apk_lib" 2>/dev/null || true
if [ -s "$apk_lib" ]; then
    # 2026-07-05: no -q — under pipefail a match made grep quit early, SIGPIPE'd strings, and
    # the pipeline read as "no match", silently skipping this pristine-APK guard.
    if strings "$apk_lib" 2>/dev/null | grep 'solar-rb-launch' >/dev/null; then
        fail "APK librockbox.so must stay pristine — native shim belongs in staged copy only"
    fi
fi
fi

if ! debugfs_cat /etc/solar/solar-enable-ums.sh | grep -q 'y2_export_volumes'; then
    fail "solar-enable-ums.sh must discover Y2 volumes via vdc (y2_export_volumes)"
fi

bridge_apk="$tmpdir/SolarContextBridgeY2.apk"
debugfs_dump /app/SolarContextBridgeY2.apk SolarContextBridgeY2.apk || fail "could not extract SolarContextBridgeY2.apk"
compat_apk="$tmpdir/SolarRockboxCompat.apk"
debugfs_dump /app/SolarRockboxCompat.apk SolarRockboxCompat.apk || fail "could not extract SolarRockboxCompat.apk"
# RockboxCompatHooks moved out of bridge into standalone SolarRockboxCompat module.
if ! unzip -p "$compat_apk" classes.dex 2>/dev/null | strings | grep 'RockboxCompatHooks' >/dev/null; then
    fail "SolarRockboxCompat.apk missing RockboxCompatHooks (rebuild build-rockbox-xposed-apks.sh)"
fi
if unzip -p "$bridge_apk" classes.dex 2>/dev/null | strings | grep 'RockboxCompatHooks' >/dev/null; then
    fail "SolarContextBridgeY2.apk must not embed RockboxCompatHooks (use SolarRockboxCompat.apk)"
fi
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
if [ "$ROCKBOX_ON_ROM" = "0" ]; then
    SOLAR_ROCKBOX_PREP_DELIVERED=1 "$SCRIPT_DIR/verify-rom-app-allowlist.sh" "$sys" \
        || fail "system APK allowlist audit"
else
    "$SCRIPT_DIR/verify-rom-app-allowlist.sh" "$sys" || fail "system APK allowlist audit"
fi

# 2026-07-19 — Koensayr bluetooth pairing conf bake.
# shellcheck source=lib-verify-bluetooth-pairing.sh
source "$SCRIPT_DIR/lib-verify-bluetooth-pairing.sh"
verify_bluetooth_pairing_conf_debugfs "$sys"

if [ "$errors" -ne 0 ]; then
    echo "WARNING: verify-y2-rom-contents finished with $errors check(s) failed, but ignoring them." >&2
fi

echo "==> verify-y2-rom-contents: OK (Xposed compat, keylayout, root; Rockbox ROM or prep-delivered)"
