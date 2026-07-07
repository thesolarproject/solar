#!/usr/bin/env bash
# Y1 mechanical UI smoke/performance probe.
# Open a long Solar library list before running this script.
set -euo pipefail

PKG="com.solar.launcher"
WHEEL_DOWN_KEY=127
TICKS="${SOLAR_WHEEL_TICKS:-220}"
DELAY="${SOLAR_WHEEL_DELAY_SEC:-0.035}"

adb get-state >/dev/null
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 1

start_ms=$(date +%s%3N)
for ((i=0; i<TICKS; i++)); do
  adb shell input keyevent "$WHEEL_DOWN_KEY" >/dev/null
  sleep "$DELAY"
done
end_ms=$(date +%s%3N)

log_file="${TMPDIR:-/tmp}/solar-mechanical-ui-logcat.txt"
adb logcat -d >"$log_file"

gc_count=$(grep -c "GC_FOR_ALLOC" "$log_file" || true)
slow_count=$(grep -c "ScreenTransition.frameProbe.*slow frame" "$log_file" || true)
crash_count=$(grep -Ec "FATAL EXCEPTION|AndroidRuntime.*$PKG" "$log_file" || true)

echo "wheel_ticks=$TICKS elapsed_ms=$((end_ms-start_ms))"
echo "gc_for_alloc=$gc_count transition_gaps_over_20ms=$slow_count crashes=$crash_count"

if (( crash_count > 0 )); then
  echo "FAIL: crash detected; log=$log_file" >&2
  exit 1
fi
if (( gc_count > 0 )); then
  echo "FAIL: GC_FOR_ALLOC detected; log=$log_file" >&2
  exit 1
fi
if (( slow_count > 0 )); then
  echo "FAIL: transition frame gap detected; log=$log_file" >&2
  exit 1
fi

echo "PASS: mechanical UI smoke/performance probe"
