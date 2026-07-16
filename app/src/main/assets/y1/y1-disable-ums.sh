#!/system/bin/sh
# Y1 wrapper — delegates to shared Solar UMS disable script (2026-07-05).
exec sh "$(dirname "$0")/solar-disable-ums.sh" "$@"
