#!/usr/bin/env bash
# Apply Y1 ROM build-rom.sh /system overlay to a rooted Y1 via adb (parity with apply-y2-rom-patches-adb.sh).
# Mirrors build-rom.sh type=a|b /system steps — skips userdata wipe.
#
# Usage: apply-y1-rom-patches-adb.sh [--no-reboot] [--apk PATH] [--with-avrcp]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SOLAR_SYS="$REPO_ROOT/solar-rom/system"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-adb-system.sh"

REBOOT=1
APK="${SOLAR_APK:-$REPO_ROOT/app/build/outputs/apk/release/app-release.apk}"
WITH_AVRCP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --no-reboot) REBOOT=0; shift ;;
        --apk) APK="${2:?--apk needs path}"; shift 2 ;;
        --with-avrcp) WITH_AVRCP=1; shift ;;
        *) echo "usage: $0 [--no-reboot] [--apk PATH] [--with-avrcp]" >&2; exit 1 ;;
    esac
done

die() { echo "apply-y1-rom-patches-adb: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not in PATH"
[ -f "$APK" ] || die "Solar APK missing: $APK (build with ./scripts/build.sh or pass --apk PATH)"

# 2026-07-06 — Refuse wrong device family; duplicate USB serials need ANDROID_SERIAL set.
export SOLAR_EXPECT_MODEL="${SOLAR_EXPECT_MODEL:-y1}"

adb_system_init
adb_system_preflight
# 2026-07-06 — Child scripts (install-xposed-adb) read ANDROID_SERIAL, not SOLAR_ADB_SERIAL alone.
if [ -n "${SOLAR_ADB_SERIAL:-}" ]; then
    export ANDROID_SERIAL="$SOLAR_ADB_SERIAL"
fi

# 2026-07-06 — Fail fast when platform scripts missing from tree (recent solar-launcher-exec addition).
for _req in solar-launcher-exec.sh solar-platform-daemon.sh; do
    [ -f "$SCRIPT_DIR/$_req" ] || die "missing $SCRIPT_DIR/$_req — sync solar-rom/scripts from repo"
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/y1-adb-patch-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

MODEL="$("${SOLAR_ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
SDK="$("${SOLAR_ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
echo "==> apply-y1-rom-patches-adb (model=$MODEL sdk=$SDK)"

# --- Solar boot recovery chain (parity with Y2 + ROM build-rom.sh) ---
echo "==> Solar boot recovery chain (install-recovery + daemonsu + init.d)"
SU="$SCRIPT_DIR/../vendor/y1-su/su"
DAEMON="$SOLAR_SYS/99SuperSUDaemon"
[ -f "$SU" ] || { echo "error: missing $SU" >&2; exit 1; }
[ -f "$DAEMON" ] || { echo "error: missing $DAEMON" >&2; exit 1; }
adb_push_to_system "$SU" /system/xbin/su 6755
adb_push_to_system "$SU" /system/xbin/daemonsu 6755
adb_push_to_system "$SU" /system/bin/su 6755
adb_push_to_system "$SU" /system/bin/.ext/.su 6755
adb_push_to_system "$DAEMON" /system/etc/init.d/99SuperSUDaemon 755
adb_su_sh "echo 1 > /system/etc/.installed_su_daemon && chmod 644 /system/etc/.installed_su_daemon" 2>/dev/null || true
adb_push_to_system "$SOLAR_SYS/install-recovery.sh" /system/etc/install-recovery.sh 755
adb_push_to_system "$SOLAR_SYS/install-recovery-2.sh" /system/etc/install-recovery-2.sh 755
adb_su_sh "cp /system/etc/install-recovery.sh /system/bin/install-recovery.sh 2>/dev/null; chmod 755 /system/bin/install-recovery.sh 2>/dev/null; true" \
    || true

# --- Xposed Dalvik (API 17) — full /system + runtime seed via su ---
echo "==> Xposed framework (API 17)"
ANDROID_ADB_TRANSPORT="${ANDROID_ADB_TRANSPORT:-}" \
    "$SCRIPT_DIR/install-xposed-adb.sh" --api 17 --no-reboot

# --- Solar APK + TLS prep ---
echo "==> Solar APK + TLS (Conscrypt JNI + CA roots)"
adb_push_to_system "$APK" /system/app/com.solar.launcher.apk 644
# 2026-07-06 — Truncated USB push yields INSTALL_FAILED_INVALID_APK at pm install.
_local_apk_bytes=$(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK")
_device_apk_bytes=$("${SOLAR_ADB[@]}" shell "su -c 'wc -c < /system/app/com.solar.launcher.apk'" 2>/dev/null | tr -d '\r
 ')
if [ -z "$_device_apk_bytes" ] || [ "$_local_apk_bytes" != "$_device_apk_bytes" ]; then
    die "Solar APK size mismatch local=${_local_apk_bytes} device=${_device_apk_bytes:-missing} — retry push (avoid parallel adb runs)"
fi
TLS_STAGE="$WORK/system-tls"
chmod +x "$REPO_ROOT/scripts/stage-y1-system-prep.sh"
"$REPO_ROOT/scripts/stage-y1-system-prep.sh" "$TLS_STAGE" "$APK" "$REPO_ROOT"
adb_push_to_system "$TLS_STAGE/lib/libconscrypt_jni.so" /system/lib/libconscrypt_jni.so 644
# 2026-10-05 — Same native libs as the ROM build (apply-y1-system-prep.sh); libc++ only if absent.
[ -s "$TLS_STAGE/lib/libwolfssl.so" ] \
    && adb_push_to_system "$TLS_STAGE/lib/libwolfssl.so" /system/lib/libwolfssl.so 644
if [ -s "$TLS_STAGE/lib/libc++_shared.so" ] \
    && ! "${SOLAR_ADB[@]}" shell "[ -f /system/lib/libc++_shared.so ] && echo present" 2>/dev/null \
        | grep -q present; then
    adb_push_to_system "$TLS_STAGE/lib/libc++_shared.so" /system/lib/libc++_shared.so 644
fi
for cert in "$TLS_STAGE/etc/security/cacerts"/*; do
    [ -f "$cert" ] || continue
    adb_push_to_system "$cert" "/system/etc/security/cacerts/$(basename "$cert")" 644
done

# --- Solar init + etc/solar scripts ---
echo "==> Solar init scripts + launcher switch helpers"
adb_push_to_system "$SOLAR_SYS/99SolarInit.sh" /system/etc/init.d/99SolarInit.sh 755
adb_push_to_system "$SOLAR_SYS/99Y1ButtonScript" /system/etc/init.d/99Y1ButtonScript 755
adb_su_sh "rm -f /system/etc/init.d/99Y1LauncherInit.sh" 2>/dev/null || true

mkdir -p "$WORK/etc-solar"
# 2026-07-05: rescue trio added — build-rom.sh bakes them into /etc/solar, so the adb overlay
# must too (lab devices stay in lockstep with flashed ROMs; emergency-eject + HUD tick scripts).
for f in switch-to-stock.sh switch-to-rockbox.sh sync-rockbox-libs.sh sync-rockbox-assets.sh \
    sync-y1-keymap.sh disable-rockbox-for-solar.sh apply-preferred-home-boot.sh disable-large-font-accessibility.sh enable-gpu-performance.sh solar-usb-recovery-agent.sh \
    solar-rescue-exec.sh solar-rescue-daemon.sh solar-rescue-hud-watch.sh \
    solar-launcher-exec.sh solar-platform-daemon.sh; do
    cp "$SCRIPT_DIR/$f" "$WORK/etc-solar/$f"
    chmod 755 "$WORK/etc-solar/$f"
done
for f in solar-enable-ums.sh solar-disable-ums.sh y1-enable-ums.sh y1-disable-ums.sh; do
    cp "$REPO_ROOT/app/src/main/assets/y1/$f" "$WORK/etc-solar/$f"
    chmod 755 "$WORK/etc-solar/$f"
done
cp "$SCRIPT_DIR/Y1-Rockbox.kl" "$WORK/etc-solar/Y1-Rockbox.kl"
cp "$SCRIPT_DIR/mtk-kpd.y1.stock.kl" "$WORK/etc-solar/mtk-kpd.y1.stock.kl"
chmod 644 "$WORK/etc-solar/Y1-Rockbox.kl" "$WORK/etc-solar/mtk-kpd.y1.stock.kl"
for f in "$WORK/etc-solar"/*; do
    base=$(basename "$f")
    mode=755
    [[ "$base" == *.kl ]] && mode=644
    adb_push_to_system "$f" "/system/etc/solar/$base" "$mode"
done

echo "==> Y1 unified keylayout"
adb_su_sh "sh /system/etc/solar/sync-y1-keymap.sh" || { echo "error: sync-y1-keymap failed" >&2; exit 1; }

if [ -f "$SOLAR_SYS/media/bootanimation.zip" ] && [ -f "$SOLAR_SYS/bin/bootanimation" ]; then
    echo "==> Solar boot animation"
    adb_push_to_system "$SOLAR_SYS/media/bootanimation.zip" /system/media/bootanimation.zip 644
    adb_push_to_system "$SOLAR_SYS/bin/bootanimation" /system/bin/bootanimation 755
fi

if [ "$WITH_AVRCP" -eq 1 ]; then
    echo "==> AVRCP stack patches (Y1 only)"
    ANDROID_ADB_TRANSPORT="${ANDROID_ADB_TRANSPORT:-}" \
        "$SCRIPT_DIR/push-avrcp-patches.sh" --no-reboot
fi

echo "==> pm refresh Solar"
adb_su_sh "pm install -r /system/app/com.solar.launcher.apk" 2>/dev/null || true
adb_su_sh "sh /system/etc/solar/sync-rockbox-libs.sh" 2>/dev/null || true
adb_su_sh "sh /system/etc/solar/disable-rockbox-for-solar.sh" 2>/dev/null || true
adb_su_sh "sync"

if [ "$REBOOT" -eq 1 ]; then
    echo "==> rebooting"
    "${SOLAR_ADB[@]}" reboot
    "${SOLAR_ADB[@]}" wait-for-device
    sleep 8
    ANDROID_SERIAL="${SOLAR_ADB_SERIAL:-}" ANDROID_ADB_TRANSPORT="${SOLAR_ADB_TRANSPORT:-}" \
        "$SCRIPT_DIR/audit-device-parity.sh" || true
    "$SCRIPT_DIR/install-xposed-adb.sh" --verify-only --api 17 || true
else
    echo "Skipped reboot (--no-reboot)"
fi

echo "==> Y1 ROM /system overlay applied via adb"
