#!/system/bin/sh
# Legacy name — same as solar-enable-ums.sh (2026-07-05).
MODEL="$(getprop ro.product.model 2>/dev/null)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/solar-enable-ums.sh" ]; then
  exec sh "$SCRIPT_DIR/solar-enable-ums.sh" "$@"
fi
# Fallback when only this file was staged (inline copy).
# Dual volume: share every present card (internal + MicroSD).
VOLS=""
for cand in /storage/sdcard0 /storage/sdcard1; do
  if [ -d "$cand" ] || grep -q " $cand " /proc/mounts 2>/dev/null; then
    VOLS="$VOLS $cand"
  fi
done
VOLS="$(echo $VOLS | sed 's/^ *//')"
[ -n "$VOLS" ] || VOLS="/storage/sdcard0 /storage/sdcard1"
PROP="$(getprop sys.usb.config 2>/dev/null)"
KERN="$(cat /sys/class/android_usb/android0/functions 2>/dev/null)"
case "$KERN" in
  *mtp*) case "$PROP" in
    *mass_storage*) setprop sys.usb.config mtp,adb; sleep 2 ;;
  esac ;;
esac
setprop sys.usb.config mass_storage,adb
sleep 3
for vol in $VOLS; do vdc volume share "$vol" ums; sleep 2; done
line="$(dumpsys usb 2>/dev/null | grep 'Mass storage backing file:')"
path="${line#*:}"
path="${path#"${path%%[![:space:]]*}"}"
if [ -n "$path" ]; then echo "UMS enabled lun=$path"; exit 0; fi
exit 2
