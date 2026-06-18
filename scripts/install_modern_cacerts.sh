#!/usr/bin/env bash
# Push Let's Encrypt / modern roots into /system/etc/security/cacerts on rooted Y1.
# Fixes "Trust anchor not found" for ALL apps (MediaPlayer HTTPS, stock URLConnection, etc.).
# Does NOT add TLS 1.3 to system OpenSSL — Solar still needs Conscrypt/OkHttp for that.
#
# Usage: install_modern_cacerts.sh [--no-reboot]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
CERTS_DIR="$ROOT/app/src/main/assets/certs"
SYSTEM_CACERTS="/system/etc/security/cacerts"
NO_REBOOT=0
[[ "${1:-}" == "--no-reboot" ]] && NO_REBOOT=1

[[ -d "$CERTS_DIR" ]] || { echo "Missing $CERTS_DIR" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl required" >&2; exit 1; }

echo "== Waiting for device (120s) =="
timeout 120 adb wait-for-device
sleep 2

run_su() {
  adb shell "su -c '$*'" 2>/dev/null || adb shell "$*"
}

echo "== Root + remount /system =="
adb root 2>/dev/null || true
sleep 1
adb remount 2>/dev/null || run_su "mount -o remount,rw /system" || true

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

echo "== Building cacerts .0 files (OpenSSL subject_hash_old — API 17) =="
for pem in "$CERTS_DIR"/*.pem; do
  [[ -f "$pem" ]] || continue
  base="$(basename "$pem" .pem)"
  hash="$(openssl x509 -inform PEM -subject_hash_old -in "$pem" -noout)"
  out="${STAGING}/${hash}.0"
  if [[ -f "$out" ]]; then
    n=1
    while [[ -f "${STAGING}/${hash}.${n}" ]]; do n=$((n + 1)); done
    out="${STAGING}/${hash}.${n}"
  fi
  cp "$pem" "$out"
  echo "  $base -> $(basename "$out")"
done

count="$(find "$STAGING" -maxdepth 1 -type f | wc -l)"
[[ "$count" -gt 0 ]] || { echo "No certs in $CERTS_DIR" >&2; exit 1; }

echo "== Installing $count cert(s) to $SYSTEM_CACERTS =="
for f in "$STAGING"/*; do
  name="$(basename "$f")"
  adb push "$f" "${SYSTEM_CACERTS}/${name}"
  run_su "chmod 644 ${SYSTEM_CACERTS}/${name}"
done

echo "== Installed =="
adb shell "ls -la ${SYSTEM_CACERTS}/6187b673.0 2>/dev/null" || true

if [[ "$NO_REBOOT" -eq 1 ]]; then
  echo "DONE: modern roots in $SYSTEM_CACERTS (no reboot — caller will reboot)"
else
  echo "== Reboot required for all processes to reload trust store =="
  adb reboot
  echo "DONE: modern roots in $SYSTEM_CACERTS — rebooting"
fi
