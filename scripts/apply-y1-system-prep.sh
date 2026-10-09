#!/usr/bin/env bash
# Copy staged Y1 TLS prep into a /system tree (ROM mount or local staging).
# Usage: apply-y1-system-prep.sh STAGING_DIR TARGET_SYS_ROOT
set -euo pipefail

STAGING="${1:-}"
TARGET="${2:-}"

[[ -n "$STAGING" && -n "$TARGET" ]] || {
  echo "usage: $0 STAGING_DIR TARGET_SYS_ROOT" >&2
  exit 1
}
[[ -f "$STAGING/lib/libconscrypt_jni.so" ]] || {
  echo "Missing $STAGING/lib/libconscrypt_jni.so — run stage-y1-system-prep.sh first" >&2
  exit 1
}
[[ -d "$STAGING/etc/security/cacerts" ]] || {
  echo "Missing $STAGING/etc/security/cacerts" >&2
  exit 1
}

LIB_DIR="$TARGET/lib"
CACERTS_DIR="$TARGET/etc/security/cacerts"
mkdir -p "$LIB_DIR" "$CACERTS_DIR"

cp "$STAGING/lib/libconscrypt_jni.so" "$LIB_DIR/libconscrypt_jni.so"
chmod 644 "$LIB_DIR/libconscrypt_jni.so"

# 2026-10-05 — Solar's own TLS proxy lib: always ours. libc++_shared.so: only when the base
# lacks one (stock IJK libs may depend on the system copy) — same rule as install-test-apk.sh.
if [[ -s "$STAGING/lib/libwolfssl.so" ]]; then
  cp "$STAGING/lib/libwolfssl.so" "$LIB_DIR/libwolfssl.so"
  chmod 644 "$LIB_DIR/libwolfssl.so"
fi
if [[ -s "$STAGING/lib/libc++_shared.so" && ! -f "$LIB_DIR/libc++_shared.so" ]]; then
  cp "$STAGING/lib/libc++_shared.so" "$LIB_DIR/libc++_shared.so"
  chmod 644 "$LIB_DIR/libc++_shared.so"
fi

count=0
for cert in "$STAGING/etc/security/cacerts"/*; do
  [[ -f "$cert" ]] || continue
  base=$(basename "$cert")
  cp "$cert" "$CACERTS_DIR/$base"
  chmod 644 "$CACERTS_DIR/$base"
  count=$((count + 1))
done
[[ "$count" -gt 0 ]] || { echo "No cacerts staged" >&2; exit 1; }

echo "Applied libconscrypt_jni.so + $count cacert(s) under $TARGET"
