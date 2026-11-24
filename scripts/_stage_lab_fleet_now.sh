#!/usr/bin/env bash
# 2026-07-20 — Lab fleet stage: normalize Xposed/helpers, install Solar, pm clear, wipe stems.
# Uses adb -t transport_id (duplicate serials). Reversal: delete this helper; reflash ROM.
# Layman: put the same Solar + modules on every plugged Y1/Y2 and wipe app junk, keep songs.
set -u
set +e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# 2026-07-20 — Prefer SDK aapt so APK badging matches what devices install.
if [[ -d "$HOME/Android/Sdk/build-tools" ]]; then
  AAPT_DIR="$(ls -d "$HOME"/Android/Sdk/build-tools/*/ 2>/dev/null | sort | tail -1)"
  [[ -n "$AAPT_DIR" ]] && export PATH="${AAPT_DIR}:$PATH"
fi
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
REMOTE="/data/local/tmp/solar-test.apk"
PKG="com.solar.launcher"
ASSETS="$ROOT/app/src/main/assets/platform"

# Prefer aapt badging (truth in the APK) over build.gradle (can drift after sync).
EXP_NAME=""
EXP_CODE=""
if command -v aapt >/dev/null 2>&1; then
  BADGING="$(aapt dump badging "$APK" 2>/dev/null | head -1)"
  EXP_NAME="$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
  EXP_CODE="$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
fi
if [[ -z "$EXP_NAME" || -z "$EXP_CODE" ]]; then
  EXP_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
  EXP_CODE="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
fi

[[ -f "$APK" ]] || { echo "ERROR: missing $APK" >&2; exit 1; }
echo "EXPECTED versionName=$EXP_NAME versionCode=$EXP_CODE"
echo "APK=$APK ($(wc -c < "$APK") bytes)"

# --- adb transport helpers ---
adb_t() {
  local tid="$1"; shift
  timeout "${ADB_TIMEOUT:-60}" adb -t "$tid" "$@" </dev/null
}

# Refresh transport map: print "tid|family|model" lines (y1/y2 only).
discover_targets() {
  adb devices -l 2>/dev/null | awk '
    $2 == "device" {
      tid=""; model=""
      for (i=3;i<=NF;i++) {
        if ($i ~ /^transport_id:/) { split($i,a,":"); tid=a[2] }
        if ($i ~ /^model:/) { split($i,a,":"); model=a[2] }
      }
      if (tid != "") {
        if ((ENVIRON["TARGET_TID"] == "" || tid == ENVIRON["TARGET_TID"]) && (ENVIRON["TARGET_MODEL"] == "" || toupper(model) ~ toupper(ENVIRON["TARGET_MODEL"]))) {
          print tid "|" model
        }
      }
    }'
}

family_of() {
  local tid="$1" model="$2" cpu fam=unknown
  cpu="$(ADB_TIMEOUT=8 adb_t "$tid" shell cat /proc/cpuinfo 2>/dev/null | tr -d '\r' \
    | awk -F: '/^[Hh]ardware/{gsub(/^ +/,"",$2); print $2; exit}')"
  printf '%s' "$cpu" | grep -qi mt6582 && fam=y2
  printf '%s' "$cpu" | grep -qi mt6572 && fam=y1
  if [[ "$fam" == "unknown" ]]; then
    case "$(printf '%s' "$model" | tr '[:upper:]' '[:lower:]')" in
      *y2*) fam=y2 ;;
      *y1*) fam=y1 ;;
    esac
  fi
  echo "$fam"
}

# Run a shell snippet as root. Caller passes one string (may contain spaces).
su_sh() {
  local tid="$1"
  local cmd="$2"
  ADB_TIMEOUT="${ADB_TIMEOUT:-90}" adb_t "$tid" shell "su -c \"$cmd\"" 2>/dev/null
}

push_file() {
  local tid="$1" local_path="$2" remote_path="$3"
  ADB_TIMEOUT=120 adb_t "$tid" push "$local_path" "$remote_path" >/tmp/solar-push-$tid.log 2>&1
}

# Install APK as system app path (overwrite) + pm install -r when needed.
install_system_apk() {
  local tid="$1" local_apk="$2" system_path="$3" pkg="$4"
  local base remote="/data/local/tmp/$(basename "$system_path")"
  [[ -f "$local_apk" ]] || { echo "  SKIP missing $local_apk"; return 1; }
  echo "  install system $(basename "$system_path") ($pkg)"
  push_file "$tid" "$local_apk" "$remote" || return 1
  su_sh "$tid" "mount -o remount,rw /system; cp -f $remote $system_path; chmod 644 $system_path; sync"
  # Refresh PM registration without dropping the system app entry.
  ADB_TIMEOUT=180 adb_t "$tid" shell "su -c 'pm install -r -d -t $remote'" >/tmp/solar-pmod-$tid.log 2>&1
  if ! grep -q Success /tmp/solar-pmod-$tid.log 2>/dev/null; then
    ADB_TIMEOUT=180 adb_t "$tid" shell "pm install -r -d -t $remote" >/tmp/solar-pmod-$tid.log 2>&1
  fi
  grep -q Success /tmp/solar-pmod-$tid.log 2>/dev/null && echo "    pm Success" || {
    echo "    WARN pm:"; tr -d '\r' </tmp/solar-pmod-$tid.log | tail -3 | sed 's/^/      /'
  }
}

# Remove deprecated / lab packages (NotPipe, PowerMenuTest, wrong-family bridge).
clean_deprecated() {
  local tid="$1" fam="$2"
  echo "  clean deprecated / lab helpers ($fam)"
  # One su script — avoids N× hung pm uninstall timeouts on missing packages.
  local wrong_rm="" wrong_pm=""
  if [[ "$fam" == "y1" ]]; then
    wrong_pm="com.solar.launcher.xposed.bridge.y2 com.solar.launcher.xposed.rockbox.compat"
  else
    wrong_rm="/system/app/SolarContextBridgeY1.apk"
    wrong_pm="com.solar.launcher.xposed.bridge.y1"
  fi
  su_sh "$tid" "mount -o remount,rw /system; rm -f /system/app/io.github.gohoski.notpipe.apk /system/app/SolarNotPipeBridge.apk /system/app/PowerMenuTest.apk /system/app/SolarVersions.apk $wrong_rm; sync; for p in io.github.gohoski.notpipe com.solar.launcher.xposed.notpipe com.solar.launcher.xposed.powermenu com.solar.launcher.xposed.bridge $wrong_pm; do pm path \$p >/dev/null 2>&1 && pm uninstall \$p; done; true"
}

# Seed enabled_modules.xml + modules.list for production set.
enable_production_modules() {
  local tid="$1" fam="$2"
  local bridge_pkg bridge_apk theme_apk ime_apk
  local data="/data/data/de.robv.android.xposed.installer"
  local prefs="$data/shared_prefs/enabled_modules.xml"
  local list="$data/conf/modules.list"

  if [[ "$fam" == "y2" ]]; then
    bridge_pkg="com.solar.launcher.xposed.bridge.y2"
    bridge_apk="/system/app/SolarContextBridgeY2.apk"
  else
    bridge_pkg="com.solar.launcher.xposed.bridge.y1"
    bridge_apk="/system/app/SolarContextBridgeY1.apk"
  fi
  theme_apk="/system/app/SolarThemeFont.apk"

  echo "  seed Xposed production modules ($bridge_pkg)"
  # Build enabled prefs + modules.list from scratch for consistency.
  local xml list_body
  xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
  xml+="    <int name=\"$bridge_pkg\" value=\"1\" />\n"
  xml+="    <int name=\"com.solar.launcher.xposed.themefont\" value=\"1\" />\n"
  xml+="    <int name=\"com.solar.launcher.xposed.rockbox.ime\" value=\"1\" />\n"
  list_body="$bridge_apk\n$theme_apk\n$ime_apk\n"
  if [[ "$fam" == "y2" ]]; then
    xml+="    <int name=\"com.solar.launcher.xposed.rockbox.compat\" value=\"1\" />\n"
  fi
  xml+="</map>\n"

  su_sh "$tid" "mkdir -p $data/conf $data/shared_prefs"
  # Write via tmp + cat (busybox-friendly).
  printf '%b' "$xml" >"/tmp/solar-enabled-$tid.xml"
  printf '%b' "$list_body" >"/tmp/solar-modules-$tid.list"
  push_file "$tid" "/tmp/solar-enabled-$tid.xml" "/data/local/tmp/enabled_modules.xml"
  push_file "$tid" "/tmp/solar-modules-$tid.list" "/data/local/tmp/modules.list"
  su_sh "$tid" "cp -f /data/local/tmp/enabled_modules.xml $prefs; cp -f /data/local/tmp/modules.list $list"
  # Fix ownership to installer app uid when resolvable.
  local uid
  uid="$(ADB_TIMEOUT=15 adb_t "$tid" shell dumpsys package de.robv.android.xposed.installer 2>/dev/null \
    | tr -d '\r' | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -1)"
  if [[ -n "${uid:-}" ]]; then
    su_sh "$tid" "chown $uid:$uid $prefs $list; chmod 664 $prefs $list"
  fi
  # Strip NotPipe / PowerMenu from any leftover lists.
  su_sh "$tid" "sed -i '/notpipe/Id; /NotPipe/d; /powermenu/Id; /PowerMenu/d' $list $prefs 2>/dev/null || true"
}

# Wipe solo stems + Solar caches; never delete ordinary music files.
wipe_stems_and_caches() {
  local tid="$1"
  echo "  wipe .instrumentals / .acapellas / stem caches (keep original tracks)"
  # Push a tiny on-device wipe script (avoids quote hell through su -c).
  cat >"/tmp/solar-wipe-stems-$tid.sh" <<'WIPE'
#!/system/bin/sh
# 2026-07-20 — Delete sibling solo dirs only; leave Song.mp3 alone.
for root in /storage/sdcard0 /storage/sdcard1 /storage/emulated/0 /sdcard /mnt/sdcard; do
  [ -d "$root" ] || continue
  find "$root" -type d \( -name .instrumentals -o -name .acapellas \) 2>/dev/null | while read d; do
    rm -rf "$d"
  done
done
rm -rf /data/data/com.solar.launcher/cache/stem_solo \
  /data/data/com.solar.launcher/cache/lalal_work \
  /data/data/com.solar.launcher/cache/lalal_solo \
  /data/data/com.solar.launcher/files/stem_solo 2>/dev/null
exit 0
WIPE
  push_file "$tid" "/tmp/solar-wipe-stems-$tid.sh" "/data/local/tmp/solar-wipe-stems.sh"
  su_sh "$tid" "chmod 755 /data/local/tmp/solar-wipe-stems.sh; sh /data/local/tmp/solar-wipe-stems.sh"
}

stage_one() {
  local tid="$1" fam="$2" model="$3"
  echo ""
  echo "======== Staging tid=$tid family=$fam model=$model ========"

  ADB_TIMEOUT=20 adb_t "$tid" shell am force-stop "$PKG" >/dev/null 2>&1 || true

  clean_deprecated "$tid" "$fam"

  # Push/update production Xposed + companions from bundled assets.
  if [[ "$fam" == "y2" ]]; then
    install_system_apk "$tid" "$ASSETS/xposed/SolarContextBridgeY2.apk" \
      /system/app/SolarContextBridgeY2.apk com.solar.launcher.xposed.bridge.y2
  else
    install_system_apk "$tid" "$ASSETS/xposed/SolarContextBridgeY1.apk" \
      /system/app/SolarContextBridgeY1.apk com.solar.launcher.xposed.bridge.y1
  fi
  install_system_apk "$tid" "$ASSETS/xposed/SolarThemeFont.apk" \
    /system/app/SolarThemeFont.apk com.solar.launcher.xposed.themefont
  install_system_apk "$tid" "$ASSETS/companion/SolarHomeHelper.apk" \
    /system/app/SolarHomeHelper.apk com.solar.launcher.homehelper
  install_system_apk "$tid" "$ASSETS/companion/SolarGlobalContextModal.apk" \
    /system/app/SolarGlobalContextModal.apk com.solar.launcher.globalcontext

  enable_production_modules "$tid" "$fam"

  # Install Solar main APK — overwrite /system copy when present, then pm install -r.
  echo "  push Solar APK..."
  if ! push_file "$tid" "$APK" "$REMOTE"; then
    echo "  FAIL push:"; tail -5 /tmp/solar-push-$tid.log | sed 's/^/    /'
    return 1
  fi
  echo "  overwrite system Solar APK if present..."
  su_sh "$tid" "if [ -f /system/app/com.solar.launcher.apk ]; then mount -o remount,rw /system; cp -f $REMOTE /system/app/com.solar.launcher.apk; chmod 644 /system/app/com.solar.launcher.apk; sync; echo SYSTEM_OK; else echo NO_SYSTEM; fi" \
    | tr -d '\r' | sed 's/^/    /'
  echo "  pm install Solar..."
  ADB_TIMEOUT=240 adb_t "$tid" shell "su -c 'pm install -r -d -t $REMOTE'" >/tmp/solar-pm-$tid.log 2>&1
  if ! grep -q Success /tmp/solar-pm-$tid.log 2>/dev/null; then
    ADB_TIMEOUT=240 adb_t "$tid" shell "pm install -r -d -t $REMOTE" >/tmp/solar-pm-$tid.log 2>&1
  fi
  if ! grep -q Success /tmp/solar-pm-$tid.log 2>/dev/null; then
    echo "  FAIL install:"
    tr -d '\r' </tmp/solar-pm-$tid.log | tail -8 | sed 's/^/    /'
    return 1
  fi
  echo "  install Success"
  sleep 2

  echo "  pm clear Solar..."
  ADB_TIMEOUT=60 adb_t "$tid" shell "su -c 'pm clear $PKG'" >/tmp/solar-clear-$tid.log 2>&1
  if ! grep -q Success /tmp/solar-clear-$tid.log 2>/dev/null; then
    ADB_TIMEOUT=60 adb_t "$tid" shell "pm clear $PKG" >/tmp/solar-clear-$tid.log 2>&1
  fi
  if grep -q Success /tmp/solar-clear-$tid.log 2>/dev/null; then
    echo "  pm clear Success"
  else
    echo "  WARN pm clear:"; tr -d '\r' </tmp/solar-clear-$tid.log | tail -3 | sed 's/^/    /'
  fi

  wipe_stems_and_caches "$tid"

  ADB_TIMEOUT=25 adb_t "$tid" shell am start -n com.solar.launcher/.MainActivity >/dev/null 2>&1 || true
  echo "  launched"
  sleep 2

  local dump name code
  dump="$(ADB_TIMEOUT=40 adb_t "$tid" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
  name="$(printf '%s\n' "$dump" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"
  code="$(printf '%s\n' "$dump" | sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' | head -1)"
  echo "  installed versionName=$name versionCode=$code"
  if [[ "$name" == "$EXP_NAME" && "$code" == "$EXP_CODE" ]]; then
    echo "  VERIFY OK"
    return 0
  fi
  echo "  VERIFY FAIL (want $EXP_NAME / $EXP_CODE)"
  return 1
}

# --- main: sequential per transport (parallel adb flaky with dup serials) ---
echo "==> Discovering targets..."
mapfile -t ENTRIES < <(discover_targets)
[[ ${#ENTRIES[@]} -gt 0 ]] || { echo "ERROR: no devices" >&2; exit 1; }

FAIL=0
declare -a STAGED_TIDS=()
for entry in "${ENTRIES[@]}"; do
  tid="${entry%%|*}"
  model="${entry##*|}"
  fam="$(family_of "$tid" "$model")"
  echo "target tid=$tid model=$model family=$fam"
  if [[ "$fam" != "y1" && "$fam" != "y2" ]]; then
    echo "  SKIP unclassified"
    FAIL=1
    continue
  fi
  if ! stage_one "$tid" "$fam" "$model"; then
    FAIL=1
  else
    STAGED_TIDS+=("$tid")
  fi
done

echo ""
echo "==> Post-stage snapshot"
for entry in "${ENTRIES[@]}"; do
  tid="${entry%%|*}"
  model="${entry##*|}"
  fam="$(family_of "$tid" "$model")"
  echo "---- tid=$tid $fam ----"
  ADB_TIMEOUT=15 adb_t "$tid" shell pm list packages 2>/dev/null | tr -d '\r' \
    | grep -Ei 'notpipe|powermenu|gohoski|solar\.launcher' | sort || true
  ADB_TIMEOUT=10 adb_t "$tid" shell "su -c 'cat /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml'" 2>/dev/null | tr -d '\r' || true
done

echo ""
if [[ "$FAIL" -eq 0 ]]; then
  echo "==> All devices staged + reset + verified ($EXP_NAME)."
  echo "    Reboot each device once so Xposed module hooks reload."
  exit 0
fi
echo "==> Completed with failures." >&2
exit 1
