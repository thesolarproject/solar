#!/system/bin/sh
# Thin wrapper for 99Y1ButtonScript / Rockbox update.sh compatibility.
exec sh "$(dirname "$0")/switch-to-stock.sh" --rockbox
