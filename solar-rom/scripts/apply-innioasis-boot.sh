#!/usr/bin/env bash
# Replace Rockbox boot splash with stock Innioasis boot.img, logo.bin, and system bootanimation.
# Usage: apply-innioasis-boot.sh <rom_base_dir> <mounted_system>
#
# rom_base_dir: directory containing boot.img, logo.bin, ... (from extracted rom.zip)
# mounted_system: loop-mounted system.img mount point
set -euo pipefail

BASE_DIR="${1:-}"
MOUNT_SYS="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS_DIR="${SOLAR_BOOT_ASSETS:-$SCRIPT_DIR/../assets/innioasis-boot}"

die() { echo "error: $*" >&2; exit 1; }

[ -n "$BASE_DIR" ] && [ -d "$BASE_DIR" ] || die "usage: $0 <rom_base_dir> <mounted_system>"
[ -n "$MOUNT_SYS" ] && [ -d "$MOUNT_SYS" ] || die "usage: $0 <rom_base_dir> <mounted_system>"
[ -d "$ASSETS_DIR" ] || die "boot assets missing: $ASSETS_DIR"

for f in boot.img bootanimation bootanimation.zip logo.bin; do
    [ -f "$ASSETS_DIR/$f" ] || die "missing $ASSETS_DIR/$f"
done

echo "==> Innioasis boot assets (from $ASSETS_DIR)"

echo "  boot.img → $BASE_DIR/boot.img"
cp "$ASSETS_DIR/boot.img" "$BASE_DIR/boot.img"

echo "  logo.bin → $BASE_DIR/logo.bin"
cp "$ASSETS_DIR/logo.bin" "$BASE_DIR/logo.bin"

echo "  /system/media/bootanimation.zip"
sudo cp "$ASSETS_DIR/bootanimation.zip" "$MOUNT_SYS/media/bootanimation.zip"
sudo chmod 644 "$MOUNT_SYS/media/bootanimation.zip"
sudo chown root:root "$MOUNT_SYS/media/bootanimation.zip"

echo "  /system/bin/bootanimation"
sudo cp "$ASSETS_DIR/bootanimation" "$MOUNT_SYS/bin/bootanimation"
sudo chmod 755 "$MOUNT_SYS/bin/bootanimation"
sudo chown root:root "$MOUNT_SYS/bin/bootanimation"

echo "==> Innioasis boot assets applied"
