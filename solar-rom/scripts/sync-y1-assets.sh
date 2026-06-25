#!/usr/bin/env bash
# Keep app-bundled Y1 assets identical to ROM sources (switch scripts for runtime root patch).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/solar-rom/scripts"
DST="$ROOT/app/src/main/assets/y1"
mkdir -p "$DST"
for f in switch-to-stock.sh switch-to-rockbox.sh sync-rockbox-libs.sh sync-y1-keymap.sh Y1-Rockbox.kl; do
    cp "$SRC/$f" "$DST/$f"
done
