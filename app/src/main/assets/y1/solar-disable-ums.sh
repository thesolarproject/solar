#!/system/bin/sh
# Solar USB mass storage disable (2026-07-10).
# Layman: stop sharing disks with the PC.
# Y1: unshare + USB=adb only (no MTP — PC uses disk mode when user turns UMS on).
# Y2: unshare + USB=mtp,adb (product uses MTP; UMS not supported).

MODEL="$(getprop ro.product.model 2>/dev/null)"
VOLS="/storage/sdcard0"
USB_AFTER="adb"
case "$MODEL" in
  Y2|*Y2*)
    VOLS="/storage/sdcard0 /storage/sdcard1"
    USB_AFTER="mtp,adb"
    ;;
esac

for vol in $VOLS; do
  vdc volume unshare "$vol" ums 2>/dev/null
done
sleep 0.5
# Always clear LUN nodes before mode switch — stale lun/file caused false “USB storage on”.
for lun in \
    /sys/class/android_usb/android0/f_mass_storage/lun/file \
    /sys/class/android_usb/android0/f_mass_storage/lun0/file \
    /sys/class/android_usb/android0/f_mass_storage/lun1/file; do
  if [ -w "$lun" ]; then
    echo >"$lun" 2>/dev/null
  fi
done
setprop sys.usb.config "$USB_AFTER"
echo "UMS disabled usb=$USB_AFTER"
exit 0
