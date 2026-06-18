#!/usr/bin/env bash
# Enable network on a booted Android emulator (API 17+). Mobile/eth0 is the reliable path on x86 AVDs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
elif "${ADB[@]}" devices | awk '/^emulator-/{print $1; exit}' | grep -q .; then
  SERIAL="$("${ADB[@]}" devices | awk '/^emulator-/{print $1; exit}')"
  ADB=(adb -s "$SERIAL")
else
  echo "No emulator serial — pass one or start an AVD first" >&2
  exit 1
fi

echo "== Enable network on $SERIAL =="
"${ADB[@]}" shell settings put global airplane_mode_on 0
"${ADB[@]}" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
"${ADB[@]}" shell svc data enable
"${ADB[@]}" shell svc wifi enable

for i in $(seq 1 30); do
  if "${ADB[@]}" shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; then
    echo "Network OK on $SERIAL (ping 8.8.8.8)"
    exit 0
  fi
  sleep 1
done

echo "ERROR: emulator has no outbound connectivity after 30s" >&2
exit 1
