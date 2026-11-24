#!/usr/bin/env bash
# Proof-of-concept: apply Y2 ROM build-rom.sh /system overlay to a rooted Y2 via adb (no SP Flash).
# Mirrors build-rom.sh type=y2 steps that touch /system only — skips boot.img, userdata wipe, AVRCP.
#
# Usage: apply-y2-rom-patches-adb.sh [--no-reboot] [--apk PATH]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SOLAR_SYS="$REPO_ROOT/solar-rom/system"
# #region agent log
DEBUG_LOG="${SOLAR_DEBUG_LOG:-$REPO_ROOT/.cursor/debug-40acef.log}"
debug_log() {
    local hyp="$1" loc="$2" msg="$3"
    shift 3
    local extra=""
    for kv in "$@"; do extra="${extra}\"$(printf '%s' "$kv" | sed 's/"/\\"/g')\", "; done
    extra="${extra%, }"
    printf '{"sessionId":"40acef","hypothesisId":"%s","location":"%s","message":"%s","data":{%s},"timestamp":%s}\n' \
        "$hyp" "$loc" "$msg" "$extra" "$(date +%s000)" >> "$DEBUG_LOG" 2>/dev/null || true
}
# #endregion

REBOOT=1
APK="${SOLAR_APK:-$REPO_ROOT/app/build/outputs/apk/release/app-release.apk}"
while [ $# -gt 0 ]; do
    case "$1" in
        --no-reboot) REBOOT=0; shift ;;
        --apk) APK="${2:?--apk needs path}"; shift 2 ;;
        *) echo "usage: $0 [--no-reboot] [--apk PATH]" >&2; exit 1 ;;
    esac
done

die() { debug_log "X" "apply-y2-rom-patches-adb.sh" "fatal" "error=$*"; echo "apply-y2-rom-patches-adb: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not in PATH"
[ -f "$APK" ] || die "Solar APK missing: $APK (build with ./scripts/build.sh or pass --apk)"

SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

WORK="$(mktemp -d "${TMPDIR:-/tmp}/y2-adb-patch-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

"${ADB[@]}" wait-for-device
MODEL="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
debug_log "POC" "apply-y2-rom-patches-adb.sh:preflight" "device connected" "model=$MODEL" "serial=${SERIAL:-default}"

echo "==> preflight: device su + /system rw"
if ! "${ADB[@]}" shell "su -c id" 2>/dev/null | grep -q uid=0; then
    die "device su unavailable — need root for /system writes"
fi
"${ADB[@]}" root 2>/dev/null || true
sleep 1
"${ADB[@]}" remount 2>/dev/null || true
"${ADB[@]}" shell "su -c 'mount -o remount,rw /system'" 2>/dev/null \
    || die "could not remount /system rw"

push_to_system() {
    local local_file="$1" device_path="$2" mode="$3"
    local tmp="/data/local/tmp/solar-y2-$(basename "$device_path")"
    local parent="${device_path%/*}"
    "${ADB[@]}" push "$local_file" "$tmp" >/dev/null || die "adb push $local_file"
    # Y2 /system shell lacks dirname — mkdir parent explicitly when not a top-level path.
    if [ "$parent" != "$device_path" ] && [ -n "$parent" ]; then
        "${ADB[@]}" shell "su -c \"mkdir -p '$parent'\"" || die "mkdir $parent"
    fi
    "${ADB[@]}" shell "su -c \"cp '$tmp' '$device_path' && chmod $mode '$device_path' && rm -f '$tmp'\"" \
        || die "su cp to $device_path failed"
    debug_log "POC" "apply-y2-rom-patches-adb.sh:push" "installed" "path=$device_path" "mode=$mode"
}

push_dir_to_system() {
    local local_dir="$1" device_path="$2"
    # Re-assert rw — /system may remount ro after reboot mid-run.
    "${ADB[@]}" shell "su -c 'mount -o remount,rw /system'" 2>/dev/null || true
    # adb push copies a directory tree; device has no tar.
    "${ADB[@]}" shell "su -c \"mkdir -p '$device_path' && rm -rf '$device_path'/*\"" 2>/dev/null || true
    "${ADB[@]}" push "$local_dir/." "/data/local/tmp/solar-y2-dir/" >/dev/null \
        || die "adb push dir $local_dir"
    "${ADB[@]}" shell "su -c \"mkdir -p '$device_path' && cp -a /data/local/tmp/solar-y2-dir/. '$device_path/' && chmod -R 755 '$device_path' && rm -rf /data/local/tmp/solar-y2-dir\"" \
        || die "cp tree to $device_path failed"
    debug_log "POC" "apply-y2-rom-patches-adb.sh:push_dir" "installed" "path=$device_path"
}

# --- Y1 permissive su (install-y1-su-system.sh equivalent) ---
echo "==> Install Y1 permissive su + 99SuperSUDaemon"
SU="$SCRIPT_DIR/../vendor/y1-su/su"
[ -f "$SU" ] || die "missing $SU"
DAEMON="$REPO_ROOT/solar-rom/system/99SuperSUDaemon"
[ -f "$DAEMON" ] || die "missing $DAEMON"

push_to_system "$SU" /system/xbin/su 6755
push_to_system "$SU" /system/xbin/daemonsu 6755
push_to_system "$SU" /system/bin/su 6755
push_to_system "$SU" /system/bin/.ext/.su 6755
push_to_system "$REPO_ROOT/solar-rom/system/solar-rb-launch" /system/xbin/solar-rb-launch 755
push_to_system "$DAEMON" /system/etc/init.d/99SuperSUDaemon 755
"${ADB[@]}" shell "su -c 'echo 1 > /system/etc/.installed_su_daemon && chmod 644 /system/etc/.installed_su_daemon'" \
    || die "write .installed_su_daemon failed"

# MTK init.rc flash_recovery — starts daemonsu + Solar init.d on every boot.
push_to_system "$REPO_ROOT/solar-rom/system/install-recovery.sh" /system/etc/install-recovery.sh 755
push_to_system "$REPO_ROOT/solar-rom/system/install-recovery-2.sh" /system/etc/install-recovery-2.sh 755
"${ADB[@]}" shell "su -c 'cp /system/etc/install-recovery.sh /system/bin/install-recovery.sh 2>/dev/null; chmod 755 /system/bin/install-recovery.sh 2>/dev/null; true'" \
    || true
debug_log "POC" "apply-y2-rom-patches-adb.sh:recovery" "install-recovery hooked" \
    "init_rc=flash_recovery"

# --- Xposed Dalvik framework (API 19) ---
echo "==> Xposed framework (API 19)"
ANDROID_ADB_TRANSPORT="${ANDROID_ADB_TRANSPORT:-}" \
    "$SCRIPT_DIR/install-xposed-adb.sh" --api 19 --no-reboot

# --- Solar APK + TLS prep ---
echo "==> Solar APK + TLS (Conscrypt JNI + CA roots)"
push_to_system "$APK" /system/app/com.solar.launcher.apk 644
TLS_STAGE="$WORK/system-tls"
chmod +x "$REPO_ROOT/scripts/stage-y1-system-prep.sh"
"$REPO_ROOT/scripts/stage-y1-system-prep.sh" "$TLS_STAGE" "$APK" "$REPO_ROOT"
push_to_system "$TLS_STAGE/lib/libconscrypt_jni.so" /system/lib/libconscrypt_jni.so 644
for cert in "$TLS_STAGE/etc/security/cacerts"/*; do
    [ -f "$cert" ] || continue
    push_to_system "$cert" "/system/etc/security/cacerts/$(basename "$cert")" 644
done

# --- Solar init + etc/solar scripts ---
echo "==> Solar init scripts + launcher switch helpers"
push_to_system "$REPO_ROOT/solar-rom/system/99SolarInit.sh" /system/etc/init.d/99SolarInit.sh 755
push_to_system "$REPO_ROOT/solar-rom/system/99Y1ButtonScript" /system/etc/init.d/99Y1ButtonScript 755
"${ADB[@]}" shell "su -c 'rm -f /system/etc/init.d/99Y1LauncherInit.sh'" 2>/dev/null || true

mkdir -p "$WORK/etc-solar"
# 2026-07-05: rescue trio added — build-rom.sh bakes them into /etc/solar, so the adb overlay
# must too (lab devices stay in lockstep with flashed ROMs; emergency-eject + HUD tick scripts).
for f in switch-to-stock.sh \
    sync-y1-keymap.sh apply-preferred-home-boot.sh disable-large-font-accessibility.sh enable-gpu-performance.sh solar-usb-recovery-agent.sh \
    solar-rescue-exec.sh solar-rescue-daemon.sh solar-rescue-hud-watch.sh; do
    cp "$SCRIPT_DIR/$f" "$WORK/etc-solar/$f"
    chmod 755 "$WORK/etc-solar/$f"
done
for f in solar-enable-ums.sh solar-disable-ums.sh y1-enable-ums.sh y1-disable-ums.sh; do
    cp "$REPO_ROOT/app/src/main/assets/y1/$f" "$WORK/etc-solar/$f"
    chmod 755 "$WORK/etc-solar/$f"
done
cp "$SCRIPT_DIR/Y2-Rockbox.kl" "$WORK/etc-solar/Y2-Rockbox.kl"
chmod 644 "$WORK/etc-solar/Y2-Rockbox.kl"
for f in "$WORK/etc-solar"/*; do
    base=$(basename "$f")
    mode=755
    [[ "$base" == *.cfg || "$base" == *.kl ]] && mode=644
    push_to_system "$f" "/system/etc/solar/$base" "$mode"
done

# --- Keylayouts (Y2 canonical on Generic/Stock/Rockbox/mtk-*) ---
echo "==> Y2 unified keylayout"
"${ADB[@]}" shell "su -c 'sh /system/etc/solar/sync-y1-keymap.sh'" || die "sync-y1-keymap failed"

# --- Boot animation on /system (Y2 keeps stock boot.img kernel) ---
if [ -f "$SOLAR_SYS/media/bootanimation.zip" ] && [ -f "$SOLAR_SYS/bin/bootanimation" ]; then
    echo "==> Solar boot animation (system/media + system/bin)"
    push_to_system "$SOLAR_SYS/media/bootanimation.zip" /system/media/bootanimation.zip 644
    push_to_system "$SOLAR_SYS/bin/bootanimation" /system/bin/bootanimation 755
fi

# --- English default locale (apply_english_build_prop) — patch locally; device has no sed ---
echo "==> Set default locale English (build.prop)"
PROP_LOCAL="$WORK/build.prop"
"${ADB[@]}" pull /system/build.prop "$PROP_LOCAL" >/dev/null 2>&1 || die "pull build.prop failed"
sed -i 's/^ro\.product\.locale\.language=.*/ro.product.locale.language=en/' "$PROP_LOCAL"
sed -i 's/^ro\.product\.locale\.region=.*/ro.product.locale.region=US/' "$PROP_LOCAL"
sed -i 's/^ro\.product\.locale=.*/ro.product.locale=en-US/' "$PROP_LOCAL"
sed -i 's/^persist\.sys\.language=.*/persist.sys.language=en/' "$PROP_LOCAL"
sed -i 's/^persist\.sys\.country=.*/persist.sys.country=US/' "$PROP_LOCAL"
grep -q '^ro\.product\.locale\.language=' "$PROP_LOCAL" || echo 'ro.product.locale.language=en' >> "$PROP_LOCAL"
grep -q '^ro\.product\.locale\.region=' "$PROP_LOCAL" || echo 'ro.product.locale.region=US' >> "$PROP_LOCAL"
grep -q '^ro\.product\.locale=' "$PROP_LOCAL" || echo 'ro.product.locale=en-US' >> "$PROP_LOCAL"
grep -q '^persist\.sys\.language=' "$PROP_LOCAL" || echo 'persist.sys.language=en' >> "$PROP_LOCAL"
grep -q '^persist\.sys\.country=' "$PROP_LOCAL" || echo 'persist.sys.country=US' >> "$PROP_LOCAL"
push_to_system "$PROP_LOCAL" /system/build.prop 644

# --- Package manager refresh ---
echo "==> pm install Solar from /system/app"
"${ADB[@]}" shell "su -c 'pm install -r /system/app/com.solar.launcher.apk'" 2>/dev/null || true

# --- First-boot helpers (idempotent on live device) ---
echo "==> Run install-recovery boot chain (daemonsu + init.d)"
"${ADB[@]}" shell "su -c 'sh /system/etc/install-recovery.sh'" 2>/dev/null || true
sleep 3

# --- Post-apply verification snapshot ---
KL105="$("${ADB[@]}" shell "su -c 'grep \"^key 105\" /system/usr/keylayout/Generic.kl'" 2>/dev/null | tr -d '\r' || true)"
SU_MODE="$("${ADB[@]}" shell "su -c 'stat -c %a /system/xbin/su'" 2>/dev/null | tr -d '\r' || true)"
LANG="$("${ADB[@]}" shell getprop persist.sys.language 2>/dev/null | tr -d '\r' || true)"
debug_log "POC" "apply-y2-rom-patches-adb.sh:verify" "post-apply snapshot" \
    "key105=$KL105" "su_mode=$SU_MODE" "language=$LANG"

echo ""
echo "==> Y2 ROM /system overlay applied via adb"
echo "  key 105: $KL105"
echo "  su mode: $SU_MODE"
echo "  language prop: ${LANG:-<empty>}"

if [ "$REBOOT" -eq 1 ]; then
    echo "==> rebooting"
    "${SOLAR_ADB[@]}" reboot
    "${SOLAR_ADB[@]}" wait-for-device
    sleep 8
    ANDROID_SERIAL="${SOLAR_ADB_SERIAL:-}" ANDROID_ADB_TRANSPORT="${ANDROID_ADB_TRANSPORT:-}" \
        "$SCRIPT_DIR/audit-device-parity.sh" || true
    "$SCRIPT_DIR/install-xposed-adb.sh" --verify-only --api 19 || true
else
    echo "Skipped reboot (--no-reboot). Run: install-xposed-adb.sh --verify-only --api 19"
fi
