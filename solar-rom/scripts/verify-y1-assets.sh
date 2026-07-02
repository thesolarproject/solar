#!/usr/bin/env bash
# APK assets must match solar-rom/scripts — ROM copies scripts from scripts/; APK bundles assets/y1/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/solar-rom/scripts"
DST="$ROOT/app/src/main/assets/y1"
FILES=(
    switch-to-stock.sh
    switch-to-rockbox.sh
    sync-rockbox-libs.sh
    sync-y1-keymap.sh
    disable-rockbox-for-solar.sh
    solar-usb-recovery-agent.sh
    Y1-Rockbox.kl
)
errors=0
for f in "${FILES[@]}"; do
    if [ ! -f "$SRC/$f" ]; then
        echo "verify-y1-assets: missing ROM source $SRC/$f" >&2
        errors=$((errors + 1))
        continue
    fi
    if [ ! -f "$DST/$f" ]; then
        echo "verify-y1-assets: missing APK asset $DST/$f (run sync-y1-assets.sh)" >&2
        errors=$((errors + 1))
        continue
    fi
    if ! cmp -s "$SRC/$f" "$DST/$f"; then
        echo "verify-y1-assets: drift in $f — run solar-rom/scripts/sync-y1-assets.sh" >&2
        errors=$((errors + 1))
    fi
done
if [ "$errors" -ne 0 ]; then
    exit 1
fi
echo "verify-y1-assets: ${#FILES[@]} files match (solar-rom/scripts ↔ app/src/main/assets/y1)"
