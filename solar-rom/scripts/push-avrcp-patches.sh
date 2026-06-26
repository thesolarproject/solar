#!/usr/bin/env bash
# Push AVRCP stack patches to a connected Y1 via adb + device su (live test before ROM bake).
# Reboots by default so mtkbt + keylayout reload. Does NOT modify hardware keylayouts.
#
# Usage: ./push-avrcp-patches.sh [--no-reboot]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOENSAYR_PATCHES="$REPO_ROOT/reference/koensayr-main/src/patches"
Y1_BRIDGE_SRC="$REPO_ROOT/reference/koensayr rom contents/system/app/Y1Bridge.apk"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

REBOOT=1
if [ "${1:-}" = "--no-reboot" ]; then REBOOT=0; fi

die() { echo "push-avrcp-patches: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not in PATH"
[ -d "$KOENSAYR_PATCHES" ] || die "missing $KOENSAYR_PATCHES"

adb wait-for-device
echo "==> preflight: device su + /system rw"
if ! adb shell "su -c id" 2>/dev/null | grep -q uid=0; then
    die "device su unavailable — need root shell for /system writes"
fi
adb root 2>/dev/null || true
sleep 1
adb remount 2>/dev/null || true
adb shell "su -c 'mount -o remount,rw /system'" 2>/dev/null || \
    die "could not remount /system rw"

echo "==> pre-create y1-track-info path (Solar ROM has no com.innioasis.y1 app)"
adb shell "su -c 'mkdir -p /data/data/com.innioasis.y1/files && chmod 711 /data/data/com.innioasis.y1 /data/data/com.innioasis.y1/files'" \
    || die "mkdir com.innioasis.y1/files failed"

stop_mtkbt() {
    adb shell "su -c 'stop mtkbt; killall mtkbt'" 2>/dev/null || true
    sleep 1
}

push_to_system() {
    local local_file="$1" device_path="$2" mode="$3"
    local tmp="/data/local/tmp/solar-avrcp-$(basename "$device_path")"
    adb wait-for-device
    adb push "$local_file" "$tmp" >/dev/null || die "adb push $tmp"
    adb shell "su -c \"cp '$tmp' '$device_path' && chmod $mode '$device_path' && rm -f '$tmp'\"" \
        || die "su cp to $device_path failed"
}

verify_on_device() {
    local local_file="$1" device_path="$2"
    local local_md5 dev_md5
    local_md5="$(md5sum "$local_file" | awk '{print $1}')"
    dev_md5="$(adb shell "su -c 'md5sum $device_path'" 2>/dev/null | awk '{print $1}')"
    if [ "$local_md5" != "$dev_md5" ]; then
        die "verify failed: $device_path (local=$local_md5 device=$dev_md5)"
    fi
    echo "  verified $device_path"
}

patch_and_push() {
    local device_path="$1" script="$2" mode="$3"
    local base
    base="$(basename "$device_path")"
    echo "==> $device_path"
    adb pull "$device_path" "$STAGE/${base}.stock" || die "pull $device_path"
    if ! python3 "$KOENSAYR_PATCHES/$script" "$STAGE/${base}.stock" --output "$STAGE/${base}.patched" 2>/dev/null; then
        if ! python3 "$KOENSAYR_PATCHES/$script" "$STAGE/${base}.stock" --skip-md5 --output "$STAGE/${base}.patched"; then
            die "$script failed"
        fi
        echo "  WARN: stock MD5 mismatch — patched with --skip-md5" >&2
    fi
    if [ -f "$STAGE/${base}.patched" ]; then
        push_to_system "$STAGE/${base}.patched" "$device_path" "$mode" || die "push $device_path"
        verify_on_device "$STAGE/${base}.patched" "$device_path"
    else
        echo "  already patched (no output file)"
    fi
}

# mtkbt holds binaries open during BT audio — stop before any replace.
stop_mtkbt

patch_and_push /system/app/MtkBt.odex patch_mtkbt_odex.py 644
stop_mtkbt
patch_and_push /system/bin/mtkbt patch_mtkbt.py 755
patch_and_push /system/lib/libextavrcp_jni.so patch_libextavrcp_jni.py 644
patch_and_push /system/lib/libextavrcp.so patch_libextavrcp.py 644
patch_and_push /system/lib/libaudio.a2dp.default.so patch_libaudio_a2dp.py 644
patch_and_push /system/usr/keylayout/AVRCP.kl patch_avrcp_kl.py 644

if [ -f "$Y1_BRIDGE_SRC" ]; then
    echo "==> Y1Bridge.apk"
    adb push "$Y1_BRIDGE_SRC" /data/local/tmp/Y1Bridge.apk >/dev/null
    adb shell "su -c 'cp /data/local/tmp/Y1Bridge.apk /system/app/Y1Bridge.apk && chmod 644 /system/app/Y1Bridge.apk'" \
        || die "Y1Bridge push failed"
    verify_on_device "$Y1_BRIDGE_SRC" /system/app/Y1Bridge.apk
    adb shell "pm install -r /system/app/Y1Bridge.apk" 2>/dev/null || true
else
    echo "WARN: Y1Bridge.apk missing — skip" >&2
fi

echo ""
echo "==> AVRCP patches applied. Input devices after reboot:"
echo "  adb shell getevent -lp | grep -E 'AVRCP|mtk-tpd|mtk-kpd'"
if [ "$REBOOT" -eq 1 ]; then
    echo "==> rebooting device (required for mtkbt + keylayout)"
    adb reboot
    adb wait-for-device
    echo "Device back — run: $SCRIPT_DIR/verify-avrcp-metadata.sh"
else
    echo "Skipped reboot (--no-reboot). Run: adb reboot"
fi
