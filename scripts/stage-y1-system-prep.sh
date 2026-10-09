#!/usr/bin/env bash
# Stage Y1 /system files required for Solar TLS (Conscrypt JNI + modern CA roots).
# Used by ROM builder (apply-y1-system-prep.sh) and adb install (push-y1-system-prep.sh).
# Boot init: solar-rom/system/99SolarInit.sh → /system/etc/init.d/
#
# Usage: stage-y1-system-prep.sh STAGING_DIR APK_PATH [REPO_ROOT]
# Creates:
#   STAGING_DIR/lib/libconscrypt_jni.so
#   STAGING_DIR/lib/libwolfssl.so, STAGING_DIR/lib/libc++_shared.so (when the APK carries them)
#   STAGING_DIR/etc/security/cacerts/*.0
set -euo pipefail

STAGING="${1:-}"
APK="${2:-}"
REPO_ROOT="${3:-$(cd "$(dirname "$0")/.." && pwd)}"
CERTS_DIR="$REPO_ROOT/app/src/main/assets/certs"

[[ -n "$STAGING" && -n "$APK" ]] || {
  echo "usage: $0 STAGING_DIR APK_PATH [REPO_ROOT]" >&2
  exit 1
}
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
[[ -d "$CERTS_DIR" ]] || { echo "Missing $CERTS_DIR" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip required" >&2; exit 1; }

LIB_DIR="$STAGING/lib"
CACERTS_DIR="$STAGING/etc/security/cacerts"
mkdir -p "$LIB_DIR" "$CACERTS_DIR"

echo "== Extract libconscrypt_jni.so from APK =="
unzip -p "$APK" lib/armeabi-v7a/libconscrypt_jni.so > "$LIB_DIR/libconscrypt_jni.so"
[[ -s "$LIB_DIR/libconscrypt_jni.so" ]] || {
  echo "Missing lib/armeabi-v7a/libconscrypt_jni.so in $APK" >&2
  exit 1
}

# 2026-10-05 — /system/app APKs get no lib extraction on API 17: libs System.loadLibrary'd by
# Solar must sit in /system/lib or Solar crashes on first launch after a ROM flash
# ("Unfortunately, Solar has stopped"). Layman: ship the two missing native pieces with the ROM.
# Reversal: drop this block (and the matching copies in apply-y1-system-prep.sh).
echo "== Extract libwolfssl.so + libc++_shared.so from APK =="
for so in libwolfssl.so libc++_shared.so; do
  if unzip -l "$APK" "lib/armeabi-v7a/$so" >/dev/null 2>&1; then
    unzip -p "$APK" "lib/armeabi-v7a/$so" > "$LIB_DIR/$so"
    echo "  $so"
  fi
done

echo "== Build cacerts .0 files (OpenSSL subject_hash_old — API 17) =="
cert_count=0
for pem in "$CERTS_DIR"/*.pem; do
  [[ -f "$pem" ]] || continue
  base="$(basename "$pem" .pem)"
  hash="$(openssl x509 -inform PEM -subject_hash_old -in "$pem" -noout)"
  out="${CACERTS_DIR}/${hash}.0"
  if [[ -f "$out" ]]; then
    n=1
    while [[ -f "${CACERTS_DIR}/${hash}.${n}" ]]; do n=$((n + 1)); done
    out="${CACERTS_DIR}/${hash}.${n}"
  fi
  cp "$pem" "$out"
  cert_count=$((cert_count + 1))
  echo "  $base -> $(basename "$out")"
done
[[ "$cert_count" -gt 0 ]] || { echo "No certs in $CERTS_DIR" >&2; exit 1; }

echo "== Staged: libconscrypt_jni.so + $cert_count cacert(s) in $STAGING =="
