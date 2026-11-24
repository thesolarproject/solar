#!/usr/bin/env bash
# Keep app-bundled Y1 assets identical to ROM sources (switch scripts for runtime root patch).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/solar-rom/scripts"
DST="$ROOT/app/src/main/assets/y1"
mkdir -p "$DST"
# 2026-07-08 — solar-launcher-exec.sh added; it had drifted (stock/Innioasis switch support
# existed only in the APK asset copy, so fresh ROM flashes shipped the older script).
for f in switch-to-stock.sh sync-y1-keymap.sh apply-preferred-home-boot.sh solar-launcher-exec.sh disable-large-font-accessibility.sh enable-gpu-performance.sh solar-usb-recovery-agent.sh Y1-Rockbox.kl Y2-Rockbox.kl mtk-kpd.y1.stock.kl; do
    cp "$SRC/$f" "$DST/$f"
done
# 2026-07-15 — A5 keylayouts: app assets are hardware canon (push-a5-keymap.sh); sync into ROM scripts.
# Was: A5.kl only in APK — ROM build had no source. Now: assets → solar-rom/scripts before verify.
for f in A5-mtk.kl A5.kl; do
    [ -f "$DST/$f" ] || { echo "sync-y1-assets: missing $DST/$f" >&2; exit 1; }
    cp "$DST/$f" "$SRC/$f"
done
chmod +x "$ROOT/solar-rom/scripts/verify-y1-assets.sh"
"$ROOT/solar-rom/scripts/verify-y1-assets.sh"
