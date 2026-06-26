#!/usr/bin/env bash
# Post-reboot AVRCP metadata diagnostic — run while A2DP connected and Solar is playing.
#
# Usage: ./verify-avrcp-metadata.sh [expected_bt_name_substring]
# Default BT name filter: "Living Room TV"
set -euo pipefail

BT_NAME_FILTER="${1:-Living Room TV}"
TRACK_INFO="/data/data/com.innioasis.y1/files/y1-track-info"
FAIL=0

warn() { echo "verify-avrcp: WARN: $*" >&2; }
fail() { echo "verify-avrcp: FAIL: $*" >&2; FAIL=1; }
pass() { echo "verify-avrcp: OK: $*"; }

command -v adb >/dev/null || { fail "adb not in PATH"; exit 1; }
adb wait-for-device

echo "==> 1. A2DP connection"
if adb shell dumpsys bluetooth_a2dp 2>/dev/null | grep -qi "$BT_NAME_FILTER"; then
    pass "A2DP dump mentions '$BT_NAME_FILTER'"
else
    # Fallback: connected devices list
    if adb shell dumpsys bluetooth_manager 2>/dev/null | grep -qi "$BT_NAME_FILTER"; then
        pass "bluetooth_manager mentions '$BT_NAME_FILTER'"
    else
        fail "no A2DP connection matching '$BT_NAME_FILTER' — connect TV first"
    fi
fi

echo "==> 2. Y1Bridge"
if adb shell "su -c 'test -f /system/app/Y1Bridge.apk'" 2>/dev/null; then
    pass "/system/app/Y1Bridge.apk present"
else
    fail "Y1Bridge.apk missing from /system/app"
fi
if adb shell pm path com.koensayr.y1.bridge 2>/dev/null | grep -q package; then
    pass "com.koensayr.y1.bridge installed"
else
    warn "com.koensayr.y1.bridge not in pm path (may need reboot or pm install)"
fi

echo "==> 3. Patched stack markers"
JNI_OK=0
if adb shell "su -c 'strings /system/lib/libextavrcp_jni.so'" 2>/dev/null | grep -q y1-track-info; then
    pass "libextavrcp_jni references y1-track-info"
    JNI_OK=1
else
    fail "libextavrcp_jni missing y1-track-info trampoline"
fi
if adb shell "su -c 'strings /system/app/MtkBt.odex'" 2>/dev/null | grep -qE 'metachanged|playstatechanged'; then
    pass "MtkBt.odex references music broadcast intents"
elif adb shell "su -c 'strings /system/bin/mtkbt'" 2>/dev/null | grep -q y1-track-info; then
    pass "mtkbt references y1-track-info"
elif [ "$JNI_OK" -eq 1 ]; then
    warn "mtkbt/MtkBt.odex string probe inconclusive — libextavrcp_jni OK"
else
    fail "no patched AVRCP markers found — run push-avrcp-patches.sh"
fi

echo "==> 4. y1-track-info file"
size="$(adb shell "su -c 'wc -c < $TRACK_INFO'" 2>/dev/null | tr -d ' \r\n' || echo 0)"
if [ "${size:-0}" = "2213" ]; then
    pass "y1-track-info size 2213"
else
    fail "y1-track-info size=$size (expected 2213) — play a track in Solar"
fi

# Title lives in inactive slot at offset 4 + slot_size + 8; read first 32 bytes of title field via dd.
title_hex="$(adb shell "su -c 'dd if=$TRACK_INFO bs=1 skip=12 count=32 2>/dev/null | xxd -p'" 2>/dev/null | tr -d '\r\n' || true)"
if [ -n "$title_hex" ] && [ "$title_hex" != "$(printf '%0*s' 64 | tr ' ' '0')" ]; then
  if echo "$title_hex" | grep -qv '^00*$'; then
    pass "y1-track-info title field non-empty"
  else
    fail "y1-track-info title field all zeros — start playback in Solar"
  fi
else
    warn "could not read title bytes (xxd missing?) — check logcat SolarAvrcp"
fi

echo "==> 5. SolarAvrcp logcat (last 20 lines)"
adb logcat -d -s SolarAvrcp 2>/dev/null | tail -20 || true
if adb logcat -d -s SolarAvrcp 2>/dev/null | grep -qi "ensureFiles\|flush:"; then
    warn "SolarAvrcp reported file errors — check su path permissions"
fi

echo "==> 6. MediaBridgeService (optional)"
if adb shell dumpsys activity services 2>/dev/null | grep -q MediaBridgeService; then
    pass "MediaBridgeService registered"
else
    warn "MediaBridgeService not in dumpsys — may start on next A2DP connect"
fi

if [ "$FAIL" -ne 0 ]; then
    echo ""
    echo "Some checks failed. Fix push/reboot, connect '$BT_NAME_FILTER', play a track, re-run."
    exit 1
fi
echo ""
echo "All critical checks passed. Confirm title/artist on the TV display."
