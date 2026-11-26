#!/system/bin/sh
# 2026-11-26 — Solar USB drive labels: "Internal" and "SD".
# Layman: the two drives the PC shows are named the same on the player and on the computer.
# Tech: best-effort filesystem label on the block device behind each export path — fatlabel
#   (vfat) first, e2label (ext4) second, fsck.fat -L last. Every step is non-fatal: a failure
#   only leaves the old drive name, it never blocks the disk-mode enable.
# Reversal: delete this asset and the labelExportVolumesAsync() call in
#   UsbMassStorageController.enable(); existing labels stay until the next relabel.
label_one() {
  path="$1"
  name="$2"
  [ -e "$path" ] || return 0
  dev="$(awk -v p="$path" '$2 == p { print $1; exit }' /proc/mounts 2>/dev/null)"
  [ -n "$dev" ] || return 0
  fatlabel "$dev" "$name" >/dev/null 2>&1 && return 0
  e2label "$dev" "$name" >/dev/null 2>&1 && return 0
  fsck.fat -L "$name" "$dev" >/dev/null 2>&1
  return 0
}

# Internal first, then the MicroSD slot: the LUN order the PC enumerates.
label_one /storage/sdcard0 Internal
label_one /storage/sdcard1 SD
exit 0
