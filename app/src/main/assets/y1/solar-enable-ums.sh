#!/system/bin/sh
# Solar USB mass storage enable — setprop + vdc + LUN bind fallback (2026-07-05).
# Layman: switch USB to disk mode, share the right SD volume(s), wait until the PC can see a drive.
# Tech: MountService/app_process is flaky on Y1 API17; Y2 uses UmsEnabler when APK present, then vdc.
# All families: share every healthy volume so the host sees internal + MicroSD.
# Y1 props: external_sd_path=/storage/sdcard0, internal_sd_path=/storage/sdcard1.
# Y2: internal + slot MicroSD. A5: same dual layout when both mounts exist.
# Reversal: restore single-vol Y1 path if a device only has one LUN.

MODEL="$(getprop ro.product.model 2>/dev/null)"
VOLS=""
for cand in /storage/sdcard0 /storage/sdcard1 /mnt/media_rw/sdcard0 /mnt/media_rw/sdcard1; do
  if [ -d "$cand" ] || grep -q " $cand " /proc/mounts 2>/dev/null; then
    case " $VOLS " in
      *" $cand "*) ;;
      *) VOLS="$VOLS $cand" ;;
    esac
  fi
done
VOLS="$(echo $VOLS | sed 's/^ *//')"
[ -n "$VOLS" ] || VOLS="/storage/sdcard0 /storage/sdcard1"

# Session 705932 — append one NDJSON line for debug-mode analysis.
dbg_705932() {
  loc="$1"
  msg="$2"
  hyp="$3"
  extra="$4"
  ts="$(date +%s)"
  ts="${ts}000"
  usb="$(getprop sys.usb.config 2>/dev/null)"
  kern="$(cat /sys/class/android_usb/android0/functions 2>/dev/null)"
  line="{\"sessionId\":\"705932\",\"timestamp\":${ts},\"location\":\"${loc}\",\"message\":\"${msg}\",\"hypothesisId\":\"${hyp}\",\"data\":{\"usbConfig\":\"${usb}\",\"kernelFn\":\"${kern}\",${extra}}}"
  echo "$line" >>/data/local/tmp/debug-705932.log 2>/dev/null
  mkdir -p /storage/sdcard0/.solar 2>/dev/null
  echo "$line" >>/storage/sdcard0/.solar/debug-705932.log 2>/dev/null
}

# Session a3510d — append one NDJSON line for debug-mode analysis.
dbg_a3510d() {
  loc="$1"
  msg="$2"
  hyp="$3"
  extra="$4"
  ts="$(date +%s)"
  ts="${ts}000"
  line="{\"sessionId\":\"a3510d\",\"timestamp\":${ts},\"location\":\"${loc}\",\"message\":\"${msg}\",\"hypothesisId\":\"${hyp}\",\"data\":{${extra}}}"
  echo "$line" >>/data/local/tmp/debug-a3510d.log 2>/dev/null
  mkdir -p /storage/sdcard1/.solar 2>/dev/null
  echo "$line" >>/storage/sdcard1/.solar/debug-a3510d.log 2>/dev/null
}

# Read non-empty mass-storage backing path from dumpsys usb.
ums_backing_path() {
  line="$(dumpsys usb 2>/dev/null | grep 'Mass storage backing file:')"
  path="${line#*:}"
  path="${path#"${path%%[![:space:]]*}"}"
  echo "$path"
}

# True when any lun/file sysfs node lists a backing block device.
lun_bound() {
  for f in \
      /sys/class/android_usb/android0/f_mass_storage/lun/file \
      /sys/class/android_usb/android0/f_mass_storage/lun0/file \
      /sys/class/android_usb/android0/f_mass_storage/lun1/file; do
    if [ -r "$f" ]; then
      line="$(cat "$f" 2>/dev/null)"
      if [ -n "$line" ]; then
        return 0
      fi
    fi
  done
  return 1
}

# Wait until kernel USB functions include mass_storage — fast poll first (2026-07-05).
wait_kernel_ums() {
  i=0
  while [ "$i" -lt 24 ]; do
    KERN="$(cat /sys/class/android_usb/android0/functions 2>/dev/null)"
    echo "$KERN" | grep -q mass_storage && return 0
    if [ "$i" -lt 8 ]; then
      sleep 0.25
    else
      sleep 0.5
    fi
    i=$((i + 1))
  done
  return 1
}

# Recover JVM/kernel desync: UsbDeviceManager thinks UMS but kernel stayed on mtp.
recover_desync() {
  KERN="$(cat /sys/class/android_usb/android0/functions 2>/dev/null)"
  PROP="$(getprop sys.usb.config 2>/dev/null)"
  case "$KERN" in
    *mtp*) case "$PROP" in
      *mass_storage*)
        setprop sys.usb.config mtp,adb
        sleep 1
        i=0
        while [ "$i" -lt 8 ]; do
          KERN="$(cat /sys/class/android_usb/android0/functions 2>/dev/null)"
          echo "$KERN" | grep -q mtp && break
          sleep 0.5
          i=$((i + 1))
        done
        ;;
    esac ;;
  esac
}

# Push mass_storage,adb through setprop and optional sysfs write when setprop stalls.
apply_kernel_ums() {
  setprop sys.usb.config mass_storage,adb
  if ! wait_kernel_ums; then
    if [ -w /sys/class/android_usb/android0/functions ]; then
      echo mass_storage,adb >/sys/class/android_usb/android0/functions 2>/dev/null
    fi
  fi
  wait_kernel_ums
}

# Resolve block node for one volume path via mounts, vdc list, or vold nodes.
block_for_volume() {
  vol="$1"
  blk=""
  while read -r dev mnt _rest; do
    if [ "$mnt" = "$vol" ]; then
      blk="$dev"
      break
    fi
  done < /proc/mounts 2>/dev/null
  # Y2 MicroSD is FUSE at /storage/sdcard1 — use media_rw block node (2026-07-05).
  if [ "$blk" = "/dev/fuse" ] || [ -z "$blk" ]; then
    case "$vol" in
      /storage/sdcard1)
        while read -r dev mnt _rest; do
          if [ "$mnt" = "/mnt/media_rw/sdcard1" ]; then
            blk="$dev"
            break
          fi
        done < /proc/mounts 2>/dev/null
        ;;
      /storage/sdcard0)
        while read -r dev mnt _rest; do
          if [ "$mnt" = "/mnt/media_rw/sdcard0" ]; then
            blk="$dev"
            break
          fi
        done < /proc/mounts 2>/dev/null
        ;;
    esac
  fi
  if [ -n "$blk" ] && [ -e "$blk" ]; then
    echo "$blk"
    return 0
  fi
  list="$(vdc volume list 2>/dev/null)"
  line="$(echo "$list" | grep "$vol" 2>/dev/null)"
  line="${line%%$'\n'*}"
  disk="$(echo "$line" | sed -n 's/.*disk:\([0-9]*,[0-9]*\).*/\1/p')"
  disk="${disk%%$'\n'*}"
  if [ -z "$disk" ]; then
    majmin="$(echo "$line" | sed -n 's/.*\/dev\/block\/vold\/\([0-9]*:[0-9]*\).*/\1/p')"
    majmin="${majmin%%$'\n'*}"
    if [ -n "$majmin" ]; then
      node="/dev/block/vold/$majmin"
      [ -e "$node" ] && echo "$node" && return 0
    fi
  fi
  if [ -n "$disk" ]; then
    node="/dev/block/vold/${disk/,/:}"
    [ -e "$node" ] && echo "$node" && return 0
    node="/dev/block/${disk/,/:}"
    [ -e "$node" ] && echo "$node" && return 0
  fi
  return 1
}

# Bind a block device to the first free mass-storage LUN sysfs node.
# Y1 has lun (→lun0) + lun1; vdc share often does NOT write these — sysfs bind required.
bind_block_to_lun() {
  blk="$1"
  for lun in \
      /sys/class/android_usb/android0/f_mass_storage/lun/file \
      /sys/class/android_usb/android0/f_mass_storage/lun0/file \
      /sys/class/android_usb/android0/f_mass_storage/lun1/file \
      /sys/devices/platform/mt_usb/gadget/lun0/file \
      /sys/devices/platform/mt_usb/gadget/lun1/file; do
    if [ -e "$lun" ]; then
      cur="$(cat "$lun" 2>/dev/null)"
      if [ -n "$cur" ]; then
        # Already bound to this block — count as success.
        [ "$cur" = "$blk" ] && return 0
        continue
      fi
      if echo "$blk" >"$lun" 2>/dev/null; then
        # Verify write stuck (lun1 may be root-only on some builds).
        got="$(cat "$lun" 2>/dev/null)"
        [ "$got" = "$blk" ] && return 0
      fi
    fi
  done
  return 1
}

# Count how many LUN file nodes currently have a backing path.
count_bound_luns() {
  n=0
  for lun in \
      /sys/class/android_usb/android0/f_mass_storage/lun/file \
      /sys/class/android_usb/android0/f_mass_storage/lun1/file \
      /sys/devices/platform/mt_usb/gadget/lun0/file \
      /sys/devices/platform/mt_usb/gadget/lun1/file; do
    if [ -r "$lun" ]; then
      cur="$(cat "$lun" 2>/dev/null)"
      if [ -n "$cur" ]; then
        n=$((n + 1))
      fi
    fi
  done
  # de-dupe alias paths (lun vs lun0) — max 2 meaningful
  [ "$n" -gt 2 ] && n=2
  echo "$n"
}

# Share every export volume through vold once kernel is on mass_storage.
# On Y1 API17, vdc often reports success but leaves lun/file empty — always follow with sysfs bind.
share_all_volumes() {
  for vol in $VOLS; do
    vdc volume share "$vol" ums 2>/dev/null
    sleep 0.5
  done
}

# Poll until dumpsys or lun sysfs shows a backing block device — fast early ticks (2026-07-05).
wait_lun_bound() {
  i=0
  while [ "$i" -lt 24 ]; do
    backing="$(ums_backing_path)"
    if [ -n "$backing" ] || lun_bound; then
      [ -n "$backing" ] && echo "UMS enabled lun=$backing"
      [ -z "$backing" ] && echo "UMS enabled lun=sysfs"
      return 0
    fi
    if [ "$i" -lt 6 ]; then
      sleep 0.25
    else
      sleep 0.5
    fi
    i=$((i + 1))
  done
  return 1
}

# Bind EVERY export volume to a free LUN (do not stop after the first).
# 2026-07-16 — Y1 root cause: bind_fallback returned after first LUN so MicroSD never exported.
bind_all_volumes_to_luns() {
  bound=0
  for vol in $VOLS; do
    blk="$(block_for_volume "$vol")"
    if [ -z "$blk" ]; then
      echo "UMS skip $vol (no block device)" >&2
      continue
    fi
    if bind_block_to_lun "$blk"; then
      bound=$((bound + 1))
      echo "UMS bound $vol -> $blk"
      sleep 0.3
    else
      echo "UMS bind failed $vol ($blk)" >&2
    fi
  done
  [ "$bound" -gt 0 ]
}

# Direct LUN bind when vdc share never populated lun/file (Y1 API17 fallback).
bind_fallback() {
  bind_all_volumes_to_luns
}

# Resolve Solar APK for app_process — pm/data first; stale /system copy last (2026-07-05).
resolve_solar_apk() {
  line="$(pm path com.solar.launcher 2>/dev/null)"
  case "$line" in
    package:*) echo "${line#package:}"; return 0 ;;
  esac
  for p in \
      /data/app/com.solar.launcher-2.apk \
      /data/app/com.solar.launcher-1.apk \
      /system/app/com.solar.launcher.apk; do
    [ -f "$p" ] && echo "$p" && return 0
  done
  return 1
}

# Build export list from vdc — only share volumes vold knows about (2026-07-05).
y2_export_volumes() {
  listed=""
  vdc volume list 2>/dev/null | while read -r line; do
    case "$line" in
      *Volumes\ listed*) continue ;;
    esac
    set -- $line
    path="$3"
    case "$path" in
      /storage/*)
        case "$path" in
          *usbotg*) ;;
          /storage/emulated/0|/storage/emulated/0/*) ;;
          *) echo "$path" ;;
        esac
        ;;
    esac
  done > /data/local/tmp/solar-y2-vols.$$
  if [ -s /data/local/tmp/solar-y2-vols.$$ ]; then
    listed="$(cat /data/local/tmp/solar-y2-vols.$$)"
    rm -f /data/local/tmp/solar-y2-vols.$$
    echo "$listed"
    return 0
  fi
  rm -f /data/local/tmp/solar-y2-vols.$$
  for p in /storage/sdcard0 /storage/emulated/legacy /storage/sdcard1; do
    [ -e "$p" ] && listed="$listed $p"
  done
  echo "$listed"
}

# Share every vold-listed Y2 volume — internal + MicroSD when both mounted.
share_y2_volumes() {
  for vol in $(y2_export_volumes); do
    vdc volume share "$vol" ums 2>/dev/null
    sleep 1
  done
}

# Bind block devices for each export volume to free LUN nodes (Mac dual-disk fallback).
bind_y2_dual_lun() {
  for vol in $(y2_export_volumes); do
    blk="$(block_for_volume "$vol")"
    if [ -n "$blk" ]; then
      bind_block_to_lun "$blk"
      sleep 0.5
    fi
  done
  wait_lun_bound
}

# Y2 fallback: UmsEnabler enable only — no disable pass that forces MTP (2026-07-05).
enable_y2_ums_enabler() {
  dbg_a3510d "solar-enable-ums.enable_y2" "start" "H2" "\"model\":\"$MODEL\""
  APK="$(resolve_solar_apk)" || return 1
  PRIMARY="/storage/sdcard1"
  for vol in $(y2_export_volumes); do
    PRIMARY="$vol"
    break
  done
  export CLASSPATH="$APK"
  app_process /system/bin com.solar.launcher.UmsEnabler 1 "$PRIMARY" 2>/dev/null
  sleep 4
  share_y2_volumes
  if wait_lun_bound; then
    dbg_a3510d "solar-enable-ums.enable_y2" "ok enabler" "H2" "\"usb\":\"$(getprop sys.usb.config)\""
    return 0
  fi
  bind_y2_dual_lun && return 0
  dbg_a3510d "solar-enable-ums.enable_y2" "fail" "H2,H3,H4" "\"usb\":\"$(getprop sys.usb.config)\""
  return 1
}

# Y1 / fallback: setprop + vdc share + ALWAYS sysfs multi-LUN bind.
# 2026-07-16 — vdc "success" often leaves lun/file empty; never treat vdc-only as done.
# Only call this after the user confirms "Turn on USB Storage" (never on bare cable plug).
enable_setprop_vdc() {
  dbg_705932 "solar-enable-ums.enable_setprop_vdc" "start" "H3" "\"model\":\"$MODEL\""
  recover_desync
  if ! apply_kernel_ums; then
    dbg_705932 "solar-enable-ums.enable_setprop_vdc" "kernel fail" "H3" "\"model\":\"$MODEL\""
    echo "UMS enable failed: kernel never reached mass_storage ($MODEL)" >&2
    return 2
  fi
  share_all_volumes
  # Always bind every volume to free LUNs (internal + MicroSD). Early return after first
  # LUN was why PCs only saw Internal Storage.
  if bind_all_volumes_to_luns && wait_lun_bound; then
    n="$(count_bound_luns)"
    echo "UMS multi-LUN bound count=$n vols=$VOLS"
    dbg_705932 "solar-enable-ums.enable_setprop_vdc" "ok multi_bind" "H3" "\"bound\":$n"
    return 0
  fi
  dbg_705932 "solar-enable-ums.enable_setprop_vdc" "fail" "H3" "\"model\":\"$MODEL\""
  return 1
}

case "$MODEL" in
  Y2|*Y2*)
    recover_desync
    # Primary: UmsEnabler mass_storage path + vdc — uses pm-installed APK, syncs MountService (2026-07-05).
    if enable_y2_ums_enabler; then
      exit 0
    fi
    if enable_setprop_vdc; then
      exit 0
    fi
    ;;
  *)
    enable_setprop_vdc
    ;;
esac

if wait_lun_bound || lun_bound; then
  exit 0
fi

echo "UMS enable failed: mass-storage LUN not bound ($MODEL vols=$VOLS)" >&2
exit 2
