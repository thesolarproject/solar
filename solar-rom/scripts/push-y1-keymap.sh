#!/usr/bin/env bash
# Push Y1-Rockbox.kl + switch scripts to a rooted Y1/Y2 (no full ROM reflash).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KL="$ROOT/solar-rom/scripts/Y1-Rockbox.kl"
SW="$ROOT/solar-rom/scripts/switch-to-stock.sh"
SW_RB="$ROOT/solar-rom/scripts/switch-to-rockbox.sh"
[ -f "$KL" ] || { echo "missing $KL" >&2; exit 1; }
adb wait-for-device
adb push "$KL" /data/local/tmp/Y1-Rockbox.kl
adb push "$SW" /data/local/tmp/switch-to-stock.sh
adb push "$SW_RB" /data/local/tmp/switch-to-rockbox.sh
adb shell "su -c 'mount -o remount,rw /system
for f in Generic.kl Stock.kl Rockbox.kl Y1-Rockbox.kl; do
  cp /data/local/tmp/Y1-Rockbox.kl /system/usr/keylayout/\$f
  chmod 644 /system/usr/keylayout/\$f
done
mkdir -p /system/etc/solar /data/data
cp /data/local/tmp/switch-to-stock.sh /system/etc/solar/switch-to-stock.sh
cp /data/local/tmp/switch-to-rockbox.sh /system/etc/solar/switch-to-rockbox.sh
cp /data/local/tmp/switch-to-stock.sh /data/data/switch-to-stock.sh
cp /data/local/tmp/switch-to-rockbox.sh /data/data/switch-to-rockbox.sh
chmod 755 /system/etc/solar/switch-to-stock.sh /system/etc/solar/switch-to-rockbox.sh
chmod 755 /data/data/switch-to-stock.sh /data/data/switch-to-rockbox.sh
echo \"=== Generic.kl Y1 lines ===\"
grep -E \"key (105|106|163|165)\" /system/usr/keylayout/Generic.kl | head -6
md5sum /system/usr/keylayout/Generic.kl /system/usr/keylayout/Stock.kl /system/usr/keylayout/Rockbox.kl'"
echo "Rebooting so InputReader reloads keylayout..."
adb reboot
