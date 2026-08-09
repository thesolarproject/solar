#!/usr/bin/env bash
# Pre-install the Wolfius TLS 1.3 proxy CA into a Solar ROM system image.
#
# Usage:  inject-wolfius-ca.sh <mounted-or-extracted-system-dir>
#
# Installs app/src/main/assets/ca_cert.pem into <dir>/etc/security/cacerts/<subject_hash>.0
# so the device trusts the embedded proxy's certs without a runtime /system remount.
# The subject hash is the OpenSSL "old" (MD5) subject hash used by Android < 7.
set -euo pipefail

SYS_DIR="${1:-}"
if [ -z "$SYS_DIR" ] || [ ! -d "$SYS_DIR" ]; then
    echo "usage: $0 <system-dir>" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CA_PEM="$REPO_ROOT/app/src/main/assets/ca_cert.pem"
CACERTS="$SYS_DIR/etc/security/cacerts"

if [ ! -f "$CA_PEM" ]; then
    echo "CA asset missing: $CA_PEM" >&2
    exit 1
fi
if [ ! -d "$CACERTS" ]; then
    echo "no cacerts dir: $CACERTS" >&2
    exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl required to compute the subject hash" >&2
    exit 1
fi

HASH="$(openssl x509 -inform PEM -in "$CA_PEM" -subject_hash_old -noout)"
if [ -z "$HASH" ]; then
    echo "failed to compute subject hash" >&2
    exit 1
fi

cp "$CA_PEM" "$CACERTS/$HASH.0"
chmod 644 "$CACERTS/$HASH.0"
echo "Installed Wolfius CA as $CACERTS/$HASH.0"
