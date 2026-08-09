#!/usr/bin/env bash
# Install a Solar APK while preserving all app data (prefs, databases, downloads).
# Why: the old emulator scripts did `adb uninstall` before `adb install -r`, which
# wiped shared_prefs / databases / files on every reinstall. A plain `adb install -r`
# keeps app data as long as the signing key is unchanged — which is always true for
# the debug-keystore emulator flow (and for normal phone installs).
# Only when the key DID change (INSTALL_FAILED_UPDATE_INCOMPATIBLE) do we fall back to
# a best-effort run-as backup -> uninstall -> reinstall -> restore. Debug builds allow
# run-as, so prefs/DBs survive even that case.
# Usage: install-preserve-data.sh [--serial SERIAL] [APK]
#   APK defaults to app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.solar.launcher"
SERIAL="${ANDROID_SERIAL:-}"
APK=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial|-s)
      [[ $# -ge 2 ]] || { echo "ERROR: --serial requires a value" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--serial SERIAL] [APK]"
      echo "  APK defaults to app/build/outputs/apk/debug/app-debug.apk"
      exit 0
      ;;
    *)
      [[ -z "$APK" ]] || { echo "ERROR: only one APK may be supplied" >&2; exit 2; }
      APK="$1"
      shift
      ;;
  esac
done
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

echo "==> Installing $APK (data-preserving)"
if "${ADB[@]}" install -r -d -t "$APK" >/tmp/solar-install.log 2>&1; then
  echo "==> Installed. App data preserved (reinstall kept prefs/databases/files)."
  exit 0
fi

# Install failed — decide why. Signature mismatch is the only case where we must
# uninstall; anything else is a real error and should be reported as-is.
if ! grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' /tmp/solar-install.log; then
  echo "ERROR: install failed:" >&2
  tail -5 /tmp/solar-install.log >&2 || true
  exit 1
fi

echo "WARN: signing key changed (old install had a different key)."
echo "      Backing up app data before uninstall…"

# Best-effort backup under /sdcard (writable by apps on API 17; no runtime perms).
# `cp -r` only: API 17 toolbox cp has no `-a` archive flag. Files copied back via
# run-as are created by the app uid, so ownership is correct without -p.
BACKUP=/sdcard/.solar-install-backup-$$
cleanup_backup() {
  "${ADB[@]}" shell "rm -rf '$BACKUP'" >/dev/null 2>&1 || true
}
trap cleanup_backup EXIT

# Copy one app-private dir (if present) into the backup. Best-effort: a missing dir
# (fresh install) must not fail the whole backup.
backup_dir() {
  local rel="$1"
  "${ADB[@]}" shell run-as "$PKG" sh -c \
      "d='$rel'; if [ -d \"\$d\" ]; then mkdir -p '$BACKUP'; cp -r \"\$d\" '$BACKUP/'; fi" \
      >/tmp/solar-backup.log 2>&1 || true
}
backup_dir shared_prefs
backup_dir databases
backup_dir files
# Stem vault — downloaded stems are durable user data, not disposable cache.
# Live stems live under cache/lalal_stems; the sibling copies to $BACKUP/lalal_stems.
backup_dir cache/lalal_stems
# External app files may hold the phone-chrome storage root (downloads).
"${ADB[@]}" shell "mkdir -p '$BACKUP' && cp -r /sdcard/Android/data/$PKG/files '$BACKUP/external' 2>/dev/null || true" \
    >/dev/null 2>&1 || true

if ! "${ADB[@]}" shell run-as "$PKG" sh -c \
    "[ -d '$BACKUP/shared_prefs' ] || [ -d '$BACKUP/databases' ] || [ -d '$BACKUP/files' ] \
     || [ -d '$BACKUP/external' ] || [ -d '$BACKUP/lalal_stems' ]" \
    >/dev/null 2>&1; then
  echo "ERROR: no usable backup produced (run-as unavailable, /sdcard read-only, or empty app data)." >&2
  echo "       Refusing to uninstall — app data would be lost. Resolve /sdcard writability first." >&2
  exit 1
fi
echo "==> App data backed up"

"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true

if ! "${ADB[@]}" install -r -d -t "$APK" >/tmp/solar-install.log 2>&1; then
  echo "ERROR: reinstall failed:" >&2
  tail -5 /tmp/solar-install.log >&2 || true
  exit 1
fi

# Restore backed-up data into the fresh install (run-as, as the app uid).
"${ADB[@]}" shell run-as "$PKG" sh -c \
    "d='$BACKUP'; if [ -d \"\$d/shared_prefs\" ]; then cp -r \"\$d/shared_prefs\" .; fi; \
     if [ -d \"\$d/databases\" ]; then cp -r \"\$d/databases\" .; fi; \
     if [ -d \"\$d/files\" ]; then cp -r \"\$d/files\" .; fi; \
     if [ -d \"\$d/lalal_stems\" ]; then mkdir -p cache; cp -r \"\$d/lalal_stems\" cache/; fi; true" \
    >/tmp/solar-restore.log 2>&1 || true
# External app files restore back to the real path (files, not a stray 'external' dir).
"${ADB[@]}" shell "mkdir -p /sdcard/Android/data/$PKG && cp -r '$BACKUP/external' /sdcard/Android/data/$PKG/files 2>/dev/null || true" \
    >/dev/null 2>&1 || true
# Verify the restore actually landed in the NEW app data dir (not just that a backup existed).
# Broad check: any of the restored dirs proves restore ran; not every app holds prefs,
# and the phone-chrome downloads root lives under external storage.
if "${ADB[@]}" shell run-as "$PKG" sh -c \
    "[ -d shared_prefs ] || [ -d databases ] || [ -d files ] \
     || [ -d /sdcard/Android/data/$PKG/files ] || [ -d cache/lalal_stems ]" \
    >/dev/null 2>&1; then
  echo "==> App data restored (verified in new install)"
else
  echo "WARN: restore could not be verified (app dirs empty after restore)." >&2
fi

"${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
echo "==> Install complete (data-preserving path)."
