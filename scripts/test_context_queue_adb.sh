#!/usr/bin/env bash
# Automated queue-viewer checks on a connected Y1 via adb.
# Usage: ./scripts/test_context_queue_adb.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
TMP="/tmp/solar_queue_test.png"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found"
[[ -f "$APK" ]] || die "APK missing — run ./scripts/build.sh first"

echo "== Waiting for device =="
adb wait-for-device

echo "== Installing =="
adb install -r -d "$APK" | tail -2

VN=$(adb shell dumpsys package com.solar.launcher 2>/dev/null | tr -d '\r' | grep versionName | head -1 | sed 's/.*=//')
echo "Installed version: ${VN:-unknown}"

echo "== Clearing logcat =="
adb logcat -c

echo "== Launch with queue test intent =="
adb shell am force-stop com.solar.launcher
adb shell am start -n com.solar.launcher/.MainActivity --ez solar_adb_open_queue true
sleep 4

OPEN_AT_LAUNCH=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueOpen | tail -1 || true)
if [[ -n "$OPEN_AT_LAUNCH" ]]; then
  echo "Queue at launch: $OPEN_AT_LAUNCH"
else
  echo "WARN: queueOpen log missing at launch (buffer or timing)"
fi

if adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep -q "FAIL no_queue"; then
  echo "SKIP: No playback queue on device — play music first, then re-run."
  exit 0
fi

adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep -E "PASS|queueScroll|queueOpen|FAIL" | tail -8 || true

echo "== Focus first queue row =="
for i in $(seq 1 24); do
  adb shell input keyevent 21
  sleep 0.15
done
sleep 0.3

echo "== Screenshot at first item =="
adb shell screencap -p /sdcard/solar_queue_test.png
adb pull /sdcard/solar_queue_test.png "$TMP" >/dev/null

FIRST_LOG=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueScroll | tail -1)
echo "First item: $FIRST_LOG"

echo "== Wheel to last item =="
# Up to 24 steps for large queues; stop when focus index stops changing.
PREV_FOCUS=""
for i in $(seq 1 24); do
  adb shell input keyevent 22
  sleep 0.25
  LINE=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueScroll | tail -1)
  FOCUS=$(echo "$LINE" | sed -n 's/.* focus=\([0-9]*\).*/\1/p')
  COUNT=$(echo "$LINE" | sed -n 's/.* count=\([0-9]*\).*/\1/p')
  if [[ -n "$FOCUS" && -n "$COUNT" && "$FOCUS" -ge $((COUNT - 1)) ]]; then
    break
  fi
  if [[ -n "$FOCUS" && "$FOCUS" == "$PREV_FOCUS" ]]; then
    break
  fi
  PREV_FOCUS="$FOCUS"
done

adb shell screencap -p /sdcard/solar_queue_test_last.png
adb pull /sdcard/solar_queue_test_last.png /tmp/solar_queue_test_last.png >/dev/null

LAST_LOG=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueScroll | tail -1)
echo "Last item: $LAST_LOG"

FAIL=0
if echo "$LAST_LOG" | grep -qE 'topPad=[1-9][0-9]*'; then
  echo "PASS: topPad applied on last row (short-queue bottom anchor)"
else
  COUNT=$(echo "$LAST_LOG" | sed -n 's/.* count=\([0-9]*\).*/\1/p')
  if [[ -n "$COUNT" && "$COUNT" -le 3 ]]; then
    echo "FAIL: expected topPad>0 for short queue at last row"
    FAIL=1
  else
    echo "INFO: long queue — topPad may be 0 at last row (scroll clamp)"
  fi
fi

SCROLL_Y=$(echo "$LAST_LOG" | sed -n 's/.* scrollY=\([0-9]*\).*/\1/p')
MAX_SCROLL=$(echo "$LAST_LOG" | sed -n 's/.* maxScroll=\([0-9]*\).*/\1/p')
if [[ -n "$SCROLL_Y" && -n "$MAX_SCROLL" && "$SCROLL_Y" -le "$MAX_SCROLL" ]]; then
  echo "PASS: scrollY ($SCROLL_Y) <= maxScroll ($MAX_SCROLL)"
else
  echo "FAIL: scroll beyond max ($SCROLL_Y > $MAX_SCROLL)"
  FAIL=1
fi

echo "== Extra wheel step (should not scroll into void) =="
BEFORE="$LAST_LOG"
adb shell input keyevent 22
sleep 0.3
AFTER=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueScroll | tail -1)
AFTER_Y=$(echo "$AFTER" | sed -n 's/.* scrollY=\([0-9]*\).*/\1/p')
BEFORE_Y=$(echo "$BEFORE" | sed -n 's/.* scrollY=\([0-9]*\).*/\1/p')
if [[ "$AFTER_Y" == "$BEFORE_Y" ]]; then
  echo "PASS: scrollY unchanged after wheel past last item"
else
  echo "FAIL: scrollY changed $BEFORE_Y -> $AFTER_Y past last item"
  FAIL=1
fi

echo "== Queue open sanity =="
OPEN_LOG="$OPEN_AT_LAUNCH"
if [[ -z "$OPEN_LOG" ]]; then
  OPEN_LOG=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queueOpen | tail -1 || true)
fi
echo "Open: ${OPEN_LOG:-none}"
if echo "$OPEN_LOG" | grep -qE 'count=[1-9][0-9]* .*listVisible=true .*childCount=[1-9]'; then
  echo "PASS: queue list populated on open"
elif [[ -n "$OPEN_LOG" ]] && echo "$OPEN_LOG" | grep -qE 'count=0|listVisible=false|childCount=0'; then
  echo "FAIL: blank queue on open ($OPEN_LOG)"
  FAIL=1
fi

echo "== Play/pause in queue viewer =="
# Focus the now-playing row so the play/pause indicator is on screen.
NP=$(echo "$LAST_LOG" | sed -n 's/.* nowPlaying=\([0-9]*\).*/\1/p')
FOCUS=$(echo "$LAST_LOG" | sed -n 's/.* focus=\([0-9]*\).*/\1/p')
if [[ -n "$NP" && -n "$FOCUS" && "$NP" != "$FOCUS" ]]; then
  STEPS=$((FOCUS - NP))
  KEY=21
  if [[ "$STEPS" -lt 0 ]]; then STEPS=$((0 - STEPS)); KEY=22; fi
  for i in $(seq 1 "$STEPS"); do
    adb shell input keyevent "$KEY"
    sleep 0.2
  done
  sleep 0.3
  echo "Focused now-playing row $NP"
fi
sleep 0.2
BASE=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queuePlaybackState | tail -1 || true)
echo "Before toggle: ${BASE:-none}"
adb logcat -c
adb shell input keyevent 85
sleep 0.2
adb shell input keyevent 85
sleep 0.5
PP1=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queuePlaybackState | tail -1 || true)
adb shell input keyevent 85
sleep 0.2
adb shell input keyevent 85
sleep 0.5
PP2=$(adb logcat -d -s SolarAdbTest:I 2>/dev/null | grep queuePlaybackState | tail -1 || true)
echo "After 1st toggle: $PP1"
echo "After 2nd toggle: $PP2"
if [[ -n "$PP1" && -n "$PP2" && "$PP1" != "$PP2" ]]; then
  echo "PASS: queue play/pause icon state toggled"
elif [[ -n "$BASE" && -n "$PP1" && "$BASE" != "$PP1" ]]; then
  echo "PASS: queue play/pause state changed after first toggle"
elif [[ -z "$BASE" && -z "$PP1" ]]; then
  echo "SKIP: no MediaPlayer — start playback on device then re-run toggle check"
else
  echo "WARN: queue play/pause log unchanged — check manually ($BASE / $PP1 / $PP2)"
fi

echo "Screenshots: $TMP /tmp/solar_queue_test_last.png"
if [[ "$FAIL" -eq 0 ]]; then
  echo "== ALL CHECKS PASSED =="
  exit 0
else
  echo "== SOME CHECKS FAILED =="
  exit 1
fi
