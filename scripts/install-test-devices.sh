#!/usr/bin/env bash
#
# Build Solar once, then install in parallel on the lab fleet:
#   2× Innioasis Y1 (MT6572) + 1× Y2 (MT6582).
#
# Usage:
#   ./scripts/install-test-devices.sh
#   ./scripts/install-test-devices.sh SL55E242BDA0 SLB68466BAFE SLCAFDFA9A42
#   ./scripts/install-test-devices.sh --dry-run
#   ./scripts/install-test-devices.sh --check-devices
#
# Options:
#   --dry-run         Classify devices and show plan; skip build + install.
#   --check-devices   List connected adb devices with detected family; exit.
#   --skip-build      Re-use existing release APK (must exist on disk).
#   --no-stay-awake   Skip svc power stayon / stay_on_while_plugged_in tweaks.
#   --xposed          After APK install, patch full Xposed framework via su (install-xposed-adb.sh).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
cd "$ROOT"

PKG="com.solar.launcher"
APK_PATH="$ROOT/app/build/outputs/apk/release/app-release.apk"
EXPECTED_Y1_COUNT=2
EXPECTED_Y2_COUNT=1
EXPECTED_TOTAL=$((EXPECTED_Y1_COUNT + EXPECTED_Y2_COUNT))

DRY_RUN=0
CHECK_DEVICES=0
SKIP_BUILD=0
STAY_AWAKE=1
INSTALL_XPOSED=0
CLI_SERIALS=()

usage() {
  cat <<USAGE
Usage: $0 [options] [SERIAL ...]

Build one release APK, verify each target is Y1 or Y2 via adb probes aligned with
DeviceFeatures.probeFamily, then install in parallel on 2× Y1 + 1× Y2.

Options:
  --dry-run         Show device report and planned actions only.
  --check-devices   Print adb device classification and exit (no build/install).
  --skip-build      Install existing $APK_PATH without rebuilding.
  --no-stay-awake   Do not run stay-awake adb convenience commands.
  --xposed          Patch Xposed framework on each device after APK install (requires root).
  -h, --help        Show this help.

When SERIAL arguments are omitted, all adb-connected devices in state "device" are
considered. Exactly three targets with two Y1 and one Y2 must be present.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --check-devices) CHECK_DEVICES=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --no-stay-awake) STAY_AWAKE=0; shift ;;
    --xposed) INSTALL_XPOSED=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; CLI_SERIALS+=("$@"); break ;;
    -*) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) CLI_SERIALS+=("$1"); shift ;;
  esac
done

require_tool() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: missing required tool '$1' (source scripts/env.sh first)" >&2
    exit 1
  }
}

require_tool adb

# --- adb helpers (API-17 safe: no fancy process substitution requirements) ---

adb_serial() {
  # Never read stdin — adb in a `while read` loop would steal the serial list.
  adb -s "$1" "${@:2}" </dev/null
}

# Target one adb endpoint when serials collide — use transport_id from `adb devices -l`.
adb_transport() {
  adb -t "$1" "${@:2}" </dev/null
}

# Strip CR/LF adb sometimes appends on shell getprop output.
adb_prop() {
  local serial="$1"
  local key="$2"
  adb_serial "$serial" shell getprop "$key" 2>/dev/null | tr -d '\r\n'
}

adb_prop_transport() {
  local transport="$1"
  local key="$2"
  adb_transport "$transport" shell getprop "$key" 2>/dev/null | tr -d '\r\n'
}

# Hardware line from /proc/cpuinfo — mirrors DeviceFeatures.readProcCpuHardware().
adb_cpu_hardware() {
  local serial="$1"
  local line
  line="$(adb_serial "$serial" shell cat /proc/cpuinfo 2>/dev/null | tr -d '\r' \
    | awk -F: '/^[Hh]ardware/ { sub(/^ +/, "", $2); print $2; exit }')"
  echo "${line:-}"
}

adb_cpu_hardware_transport() {
  local transport="$1"
  local line
  line="$(adb_transport "$transport" shell cat /proc/cpuinfo 2>/dev/null | tr -d '\r' \
    | awk -F: '/^[Hh]ardware/ { sub(/^ +/, "", $2); print $2; exit }')"
  echo "${line:-}"
}

# Lowercase probe aligned with DeviceFeatures.probeFamily().
# Prints: y1 | y2 | unknown (unknown = no confident signal — install must abort).
probe_device_family() {
  local cpu="$1"
  local board="$2"
  local sdk="$3"
  local model="$4"

  cpu="$(printf '%s' "$cpu" | tr '[:upper:]' '[:lower:]')"
  board="$(printf '%s' "$board" | tr '[:upper:]' '[:lower:]')"
  model="$(printf '%s' "$model" | tr '[:upper:]' '[:lower:]')"

  if [[ "$cpu" == *mt6582* || "$board" == *mt6582* ]]; then
    echo "y2"
    return 0
  fi
  if [[ "$cpu" == *mt6572* || "$board" == *mt6572* ]]; then
    echo "y1"
    return 0
  fi
  if [[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 19 ]]; then
    echo "y2"
    return 0
  fi
  if [[ "$sdk" =~ ^[0-9]+$ && "$sdk" -le 16 ]]; then
    echo "y1"
    return 0
  fi
  if [[ "$model" == *y2* ]]; then
    echo "y2"
    return 0
  fi
  if [[ "$model" == *y1* ]]; then
    echo "y1"
    return 0
  fi
  # Java falls back to y1 here; for install safety we refuse ambiguous targets.
  echo "unknown"
}

# Collect probe inputs for one serial and print "family|model|sdk|cpu|board".
classify_serial() {
  local serial="$1"
  local hw board sdk model cpu family

  if ! adb_serial "$serial" get-state >/dev/null 2>&1; then
    echo "offline|$serial|0|||"
    return 0
  fi

  hw="$(adb_prop "$serial" ro.hardware)"
  board="$(adb_prop "$serial" ro.product.board)"
  sdk="$(adb_prop "$serial" ro.build.version.sdk)"
  model="$(adb_prop "$serial" ro.product.model)"
  cpu="$(adb_cpu_hardware "$serial")"
  cpu="$(printf '%s' "$cpu" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  family="$(probe_device_family "$cpu" "$hw $board" "$sdk" "$model")"
  printf '%s|%s|%s|%s|%s\n' "$family" "$model" "$sdk" "$cpu" "$hw/$board"
}

# One row per transport_id from `adb devices -l` — safe when serials duplicate.
classify_transport() {
  local transport="$1"
  local serial="${2:-}"
  local hw board sdk model cpu family

  if ! adb_transport "$transport" get-state >/dev/null 2>&1; then
    echo "offline|${serial:-?}|0|||"
    return 0
  fi

  hw="$(adb_prop_transport "$transport" ro.hardware)"
  board="$(adb_prop_transport "$transport" ro.product.board)"
  sdk="$(adb_prop_transport "$transport" ro.build.version.sdk)"
  model="$(adb_prop_transport "$transport" ro.product.model)"
  cpu="$(adb_cpu_hardware_transport "$transport")"
  cpu="$(printf '%s' "$cpu" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  family="$(probe_device_family "$cpu" "$hw $board" "$sdk" "$model")"
  printf '%s|%s|%s|%s|%s\n' "$family" "$model" "$sdk" "$cpu" "$hw/$board"
}

# Parse adb devices -l → serial|transport_id|model per connected device.
list_adb_transport_entries() {
  adb devices -l 2>/dev/null | awk '
    $2 == "device" && $1 !~ /^emulator-/ {
      serial=$1; transport=""; model=""
      for (i=3; i<=NF; i++) {
        if ($i ~ /^transport_id:/) { split($i,a,":"); transport=a[2] }
        if ($i ~ /^model:/) { split($i,a,":"); model=a[2] }
      }
      if (transport != "") print serial "|" transport "|" model
    }'
}

# Parse classify_serial pipe output (field 1=family … 5=hw/board).
classify_field() {
  local info="$1"
  local index="$2"
  printf '%s\n' "$info" | head -1 | awk -F'|' -v n="$index" '
    n <= NF {
      gsub(/^[ \t\r]+|[ \t\r]+$/, "", $n)
      print $n
    }'
}

# List adb serials in "device" state (ready), one per line.
list_adb_device_serials() {
  adb devices 2>/dev/null | awk '$2 == "device" && $1 !~ /^emulator-/ { print $1 }'
}

# Read ready serials into an array before any per-device adb probes (stdin-safe).
collect_adb_serials() {
  local _serial
  ADB_DEVICE_SERIALS=()
  while IFS= read -r _serial; do
    [[ -n "$_serial" ]] && ADB_DEVICE_SERIALS+=("$_serial")
  done < <(list_adb_device_serials)
}

# Staging path for push+pm fallback when `adb install` URI fails over WiFi adb.
# --- version helpers ---

read_apk_version() {
  local apk="$1"
  local badging name code

  if [[ ! -f "$apk" ]]; then
    echo "ERROR: APK not found: $apk" >&2
    return 1
  fi

  if command -v aapt >/dev/null 2>&1; then
    badging="$(aapt dump badging "$apk" 2>/dev/null || true)"
    name="$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    code="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
  fi

  # Fallback: Gradle file after build.sh stamps it.
  if [[ -z "${name:-}" || -z "${code:-}" ]]; then
    name="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle" | head -1)"
    code="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' "$ROOT/app/build.gradle" | head -1)"
  fi

  if [[ -z "${name:-}" || -z "${code:-}" ]]; then
    echo "ERROR: could not read versionName/versionCode from $apk" >&2
    return 1
  fi

  EXPECTED_VERSION_NAME="$name"
  EXPECTED_VERSION_CODE="$code"
}

read_installed_version() {
  local serial="$1"
  local dump name code

  dump="$(adb_serial "$serial" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
  name="$(printf '%s\n' "$dump" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"
  code="$(printf '%s\n' "$dump" | sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' | head -1)"

  INSTALLED_VERSION_NAME="${name:-}"
  INSTALLED_VERSION_CODE="${code:-}"
}

# --- reporting ---

print_device_row() {
  # serial, slot, family, model, install_status, version_status, detail
  printf '  %-22s %-6s %-7s %-18s %-8s %-10s %s\n' "$@"
}

print_check_row() {
  # serial, transport_id, family, model, detail (for --check-devices)
  printf '  %-22s %-4s %-7s %-18s %s\n' "$@"
}

print_header() {
  echo ""
  echo "==> Device report"
  print_device_row "SERIAL" "SLOT" "FAMILY" "MODEL" "INSTALL" "VERSION" "DETAIL"
  print_device_row "----------------------" "------" "-------" "------------------" "--------" "----------" "------"
}

print_check_header() {
  echo ""
  echo "==> Connected adb devices (use adb -t TRANSPORT when serials duplicate)"
  print_check_row "SERIAL" "TID" "FAMILY" "MODEL" "DETAIL"
  print_check_row "----------------------" "----" "-------" "------------------" "------"
}

# --- target selection ---

declare -a TARGET_SERIALS=()
declare -a TARGET_SLOTS=()      # y1-1, y1-2, y2-1
declare -a TARGET_FAMILIES=()
declare -a TARGET_MODELS=()
declare -a CANDIDATE_SERIALS=() # every serial we inspected (for final report)
declare -a CANDIDATE_FAMILIES=()
declare -a CANDIDATE_MODELS=()
declare -a CANDIDATE_SLOTS=()   # slot label or "-" when not assigned

warn() {
  echo "WARN: $*" >&2
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

# Populate TARGET_* arrays; sets global SELECTION_ERRORS count.
SELECTION_ERRORS=0

note_selection_error() {
  SELECTION_ERRORS=$((SELECTION_ERRORS + 1))
}

resolve_targets() {
  local -a candidates=()
  local -a families=()
  local -a models=()
  local serial family model info
  local y1_found=0 y2_found=0
  local -a y1_serials=()
  local -a y2_serials=()
  local -a offline=()
  local -a unknown=()
  local -a dup_check=()

  TARGET_SERIALS=()
  TARGET_SLOTS=()
  TARGET_FAMILIES=()
  TARGET_MODELS=()
  CANDIDATE_SERIALS=()
  CANDIDATE_FAMILIES=()
  CANDIDATE_MODELS=()
  CANDIDATE_SLOTS=()
  SELECTION_ERRORS=0

  if [[ ${#CLI_SERIALS[@]} -gt 0 ]]; then
    # Explicit serial list: require three unique entries.
    if [[ ${#CLI_SERIALS[@]} -ne $EXPECTED_TOTAL ]]; then
      note_selection_error
      fail "expected $EXPECTED_TOTAL serial arguments (2× Y1 + 1× Y2), got ${#CLI_SERIALS[@]}"
    fi
    for serial in "${CLI_SERIALS[@]}"; do
      if [[ -z "$serial" ]]; then
        note_selection_error
        fail "empty serial in argument list"
      fi
      for d in "${dup_check[@]}"; do
        if [[ "$d" == "$serial" ]]; then
          note_selection_error
          fail "duplicate serial: $serial"
        fi
      done
      dup_check+=("$serial")
      candidates+=("$serial")
    done
  else
    # Auto-discover every adb device in ready state (array first — stdin-safe).
    collect_adb_serials
    candidates=("${ADB_DEVICE_SERIALS[@]}")

    if [[ ${#candidates[@]} -eq 0 ]]; then
      note_selection_error
      fail "no adb devices connected (state 'device')"
    fi
    if [[ ${#candidates[@]} -ne $EXPECTED_TOTAL ]]; then
      warn "connected device count is ${#candidates[@]}; expected $EXPECTED_TOTAL (2× Y1 + 1× Y2)"
    fi
  fi

  echo "==> Classifying ${#candidates[@]} adb target(s)..."
  for serial in "${candidates[@]}"; do
    info="$(classify_serial "$serial")"
    family="$(classify_field "$info" 1)"
    model="$(classify_field "$info" 2)"

    CANDIDATE_SERIALS+=("$serial")
    CANDIDATE_FAMILIES+=("$family")
    CANDIDATE_MODELS+=("$model")
    CANDIDATE_SLOTS+=("-")

    if [[ "$family" == "offline" ]]; then
      offline+=("$serial")
      families+=("offline")
      models+=("$serial")
      continue
    fi

    families+=("$family")
    models+=("$model")

    case "$family" in
      y1) y1_serials+=("$serial"); y1_found=$((y1_found + 1)) ;;
      y2) y2_serials+=("$serial"); y2_found=$((y2_found + 1)) ;;
      unknown) unknown+=("$serial") ;;
      *) unknown+=("$serial") ;;
    esac
  done

  if [[ ${#offline[@]} -gt 0 ]]; then
    note_selection_error
    warn "offline or unreachable: ${offline[*]}"
  fi
  if [[ ${#unknown[@]} -gt 0 ]]; then
    note_selection_error
    warn "unclassified (install will be skipped): ${unknown[*]}"
  fi
  if [[ "$y1_found" -ne $EXPECTED_Y1_COUNT ]]; then
    note_selection_error
    warn "found $y1_found Y1 device(s); expected $EXPECTED_Y1_COUNT"
  fi
  if [[ "$y2_found" -ne $EXPECTED_Y2_COUNT ]]; then
    note_selection_error
    warn "found $y2_found Y2 device(s); expected $EXPECTED_Y2_COUNT"
  fi

  # Record slot labels on candidate rows for reporting.
  mark_candidate_slot() {
    local want_serial="$1"
    local want_slot="$2"
    local ci
    for ci in "${!CANDIDATE_SERIALS[@]}"; do
      if [[ "${CANDIDATE_SERIALS[$ci]}" == "$want_serial" ]]; then
        CANDIDATE_SLOTS[$ci]="$want_slot"
        return 0
      fi
    done
    return 1
  }

  # Assign slots in stable order: first two Y1, first Y2.
  local idx=0
  for serial in "${y1_serials[@]}"; do
    idx=$((idx + 1))
    [[ "$idx" -le $EXPECTED_Y1_COUNT ]] || break
    TARGET_SERIALS+=("$serial")
    TARGET_SLOTS+=("y1-$idx")
    TARGET_FAMILIES+=("y1")
    mark_candidate_slot "$serial" "y1-$idx"
    for i in "${!candidates[@]}"; do
      if [[ "${candidates[$i]}" == "$serial" ]]; then
        TARGET_MODELS+=("${models[$i]}")
        break
      fi
    done
  done

  if [[ ${#y2_serials[@]} -gt 0 ]]; then
    serial="${y2_serials[0]}"
    TARGET_SERIALS+=("$serial")
    TARGET_SLOTS+=("y2-1")
    TARGET_FAMILIES+=("y2")
    mark_candidate_slot "$serial" "y2-1"
    for i in "${!candidates[@]}"; do
      if [[ "${candidates[$i]}" == "$serial" ]]; then
        TARGET_MODELS+=("${models[$i]}")
        break
      fi
    done
  fi

  # Extra connected devices not assigned to a slot (auto mode only).
  if [[ ${#CLI_SERIALS[@]} -eq 0 && ${#candidates[@]} -gt $EXPECTED_TOTAL ]]; then
    warn "${#candidates[@]} devices connected; only first matching 2× Y1 + 1× Y2 will be used"
  fi

  # Candidates that were classified but not assigned (wrong family surplus).
  for serial in "${candidates[@]}"; do
    local assigned=0
    for t in "${TARGET_SERIALS[@]}"; do
      [[ "$t" == "$serial" ]] && assigned=1 && break
    done
    if [[ "$assigned" -eq 0 ]]; then
      for i in "${!candidates[@]}"; do
        if [[ "${candidates[$i]}" == "$serial" ]]; then
          warn "skipping unassigned serial $serial (family=${families[$i]})"
          break
        fi
      done
    fi
  done
}

show_classification_only() {
  local serial transport list_model info family model sdk cpu board
  local found=0
  while IFS='|' read -r serial transport list_model; do
    [[ -n "$transport" ]] && found=1 && break
  done < <(list_adb_transport_entries)
  if [[ "$found" -eq 0 ]]; then
    echo "==> Connected adb devices"
    echo "  (none)"
    exit 0
  fi
  print_check_header
  while IFS='|' read -r serial transport list_model; do
    [[ -z "$transport" ]] && continue
    info="$(classify_transport "$transport" "$serial")"
    family="$(classify_field "$info" 1)"
    model="$(classify_field "$info" 2)"
    sdk="$(classify_field "$info" 3)"
    cpu="$(classify_field "$info" 4)"
    board="$(classify_field "$info" 5)"
    print_check_row "$serial" "$transport" "$family" "$model" \
      "sdk=$sdk cpu=$cpu $board; export ANDROID_ADB_TRANSPORT=$transport"
  done < <(list_adb_transport_entries)
  exit 0
}

# --- per-device install worker (runs in subshell for parallel jobs) ---

# Install rooted Y1/Y2 test APKs by replacing /system/app, never by creating a
# /data/app updated-system overlay. The helper reboots each device and verifies
# Package Manager ownership before returning.
install_apk_to_device() {
  local serial="$1"
  local apk="$2"
  local out rc=0

  out="$(bash "$ROOT/scripts/install-test-apk.sh" --serial "$serial" --no-launch "$apk" 2>&1)" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    INSTALL_DETAIL="system APK replacement verified"
    return 0
  fi
  INSTALL_DETAIL="system APK replacement failed: ${out:-exit $rc}"
  return 1
}

install_one_device() {
  local serial="$1"
  local slot="$2"
  local family="$3"
  local model="$4"
  local apk="$5"
  local exp_name="$6"
  local exp_code="$7"
  local stay_awake="$8"
  local install_xposed="$9"
  local result_file="$10"

  local install_status="skipped"
  local version_status="-"
  local detail=""
  local INSTALL_DETAIL=""

  # Family must match slot prefix (y1-* → y1, y2-* → y2).
  local expected_family="${slot%%-*}"
  if [[ "$family" != "$expected_family" ]]; then
    detail="family mismatch (slot expects $expected_family, detected $family)"
    write_result "$result_file" "$install_status" "$version_status" "$detail"
    return 0
  fi
  if [[ "$family" == "unknown" || "$family" == "offline" ]]; then
    detail="unclassified or offline — install aborted"
    write_result "$result_file" "$install_status" "$version_status" "$detail"
    return 0
  fi

  if [[ "$stay_awake" == "1" ]]; then
    adb_serial "$serial" shell svc power stayon true >/dev/null 2>&1 || true
    adb_serial "$serial" shell settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1 || true
  fi

  if install_apk_to_device "$serial" "$apk"; then
    install_status="ok"
    detail="$INSTALL_DETAIL"
  else
    install_status="failed"
    detail="$INSTALL_DETAIL"
  fi

  if [[ "$install_status" == "ok" ]]; then
    read_installed_version "$serial"
    if [[ "$INSTALLED_VERSION_NAME" == "$exp_name" && "$INSTALLED_VERSION_CODE" == "$exp_code" ]]; then
      version_status="ok"
      detail="$detail; verified $exp_name ($exp_code)"
    else
      version_status="mismatch"
      detail="$detail; device has ${INSTALLED_VERSION_NAME:-?} (${INSTALLED_VERSION_CODE:-?}), expected $exp_name ($exp_code)"
    fi
  fi

  # Optional: full Xposed /system patch via su (lab devices with root).
  if [[ "$install_status" == "ok" && "$install_xposed" == "1" ]]; then
    local xposed_api=17
    [[ "$family" == "y2" ]] && xposed_api=19
    if adb_serial "$serial" shell "su -c id" 2>/dev/null | grep -q uid=0; then
      if ANDROID_SERIAL="$serial" "$ROOT/solar-rom/scripts/install-xposed-adb.sh" --api "$xposed_api" --no-reboot; then
        detail="$detail; xposed API $xposed_api patched (reboot device to activate)"
      else
        detail="$detail; xposed patch FAILED (see install-xposed-adb.sh output)"
        version_status="mismatch"
      fi
    else
      detail="$detail; xposed skipped (no su)"
    fi
  fi

  write_result "$result_file" "$install_status" "$version_status" "$detail"
}

write_result() {
  local file="$1"
  printf 'install=%s\nversion=%s\ndetail=%s\n' "$2" "$3" "$4" >"$file"
}

read_result_field() {
  local file="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "$file" | head -1
}

# --- main ---

if [[ "$CHECK_DEVICES" -eq 1 ]]; then
  show_classification_only
fi

resolve_targets

print_header
for i in "${!CANDIDATE_SERIALS[@]}"; do
  print_device_row "${CANDIDATE_SERIALS[$i]}" "${CANDIDATE_SLOTS[$i]}" \
    "${CANDIDATE_FAMILIES[$i]}" "${CANDIDATE_MODELS[$i]}" "pending" "-" \
    "awaiting build/install"
done

# Also show skipped candidates when selection had issues.
if [[ "$SELECTION_ERRORS" -gt 0 && ${#TARGET_SERIALS[@]} -lt $EXPECTED_TOTAL ]]; then
  warn "target selection incomplete — some installs will be skipped"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo ""
  echo "==> Dry run: skipping build and install."
  echo "    APK path: $APK_PATH"
  echo "    Targets: ${#TARGET_SERIALS[@]} / $EXPECTED_TOTAL"
  exit 0
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo ""
  echo "==> Building release APK (single build for all targets)..."
  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date -u +%s)}"
  "$ROOT/scripts/build.sh"
else
  echo ""
  echo "==> Skipping build (--skip-build)."
fi

[[ -f "$APK_PATH" ]] || fail "release APK missing: $APK_PATH"

read_apk_version "$APK_PATH"
echo "==> Built APK: $APK_PATH"
echo "    versionName=$EXPECTED_VERSION_NAME versionCode=$EXPECTED_VERSION_CODE"

RESULT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solar-install.XXXXXX")"
trap 'rm -rf "$RESULT_DIR"' EXIT

declare -a INSTALL_PIDS=()
for i in "${!TARGET_SERIALS[@]}"; do
  serial="${TARGET_SERIALS[$i]}"
  result_file="$RESULT_DIR/$serial"
  (
    install_one_device "$serial" "${TARGET_SLOTS[$i]}" "${TARGET_FAMILIES[$i]}" \
      "${TARGET_MODELS[$i]}" "$APK_PATH" "$EXPECTED_VERSION_NAME" "$EXPECTED_VERSION_CODE" \
      "$STAY_AWAKE" "$INSTALL_XPOSED" "$result_file"
  ) &
  INSTALL_PIDS+=($!)
done

echo ""
echo "==> Installing in parallel on ${#INSTALL_PIDS[@]} device(s)..."
install_failures=0
for pid in "${INSTALL_PIDS[@]}"; do
  if ! wait "$pid"; then
    install_failures=$((install_failures + 1))
  fi
done

echo ""
echo "==> Final report"
print_header
overall_ok=1
for i in "${!CANDIDATE_SERIALS[@]}"; do
  serial="${CANDIDATE_SERIALS[$i]}"
  result_file="$RESULT_DIR/$serial"
  inst="skipped"
  ver="-"
  detail="not selected for install"
  if [[ -f "$result_file" ]]; then
    inst="$(read_result_field "$result_file" install)"
    ver="$(read_result_field "$result_file" version)"
    detail="$(read_result_field "$result_file" detail)"
  elif [[ "${CANDIDATE_SLOTS[$i]}" == "-" ]]; then
    case "${CANDIDATE_FAMILIES[$i]}" in
      unknown) detail="unclassified — install aborted" ;;
      offline) detail="offline — install aborted" ;;
      y1|y2) detail="surplus ${CANDIDATE_FAMILIES[$i]} — not in 2× Y1 + 1× Y2 set" ;;
    esac
  fi
  print_device_row "$serial" "${CANDIDATE_SLOTS[$i]}" "${CANDIDATE_FAMILIES[$i]}" \
    "${CANDIDATE_MODELS[$i]}" "$inst" "$ver" "$detail"
  if [[ "${CANDIDATE_SLOTS[$i]}" != "-" && ( "$inst" != "ok" || "$ver" != "ok" ) ]]; then
    overall_ok=0
  fi
  if [[ "${CANDIDATE_SLOTS[$i]}" == "-" && "${CANDIDATE_FAMILIES[$i]}" != "offline" ]]; then
    # Explicit fleet requires exactly 2× Y1 + 1× Y2; extras count as failure.
    overall_ok=0
  fi
done

echo ""
if [[ "$overall_ok" -eq 1 && "$install_failures" -eq 0 ]]; then
  echo "==> All target installs verified successfully."
  exit 0
fi

echo "==> Completed with failures (some devices may have succeeded)." >&2
exit 1
