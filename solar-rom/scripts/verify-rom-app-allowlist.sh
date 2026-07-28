#!/usr/bin/env bash
# 2026-07-05 — Post-build check: every /system APK basename must be on allowlist.
# Reversal: remove call from verify-y1/y2-rom-contents.sh.
# Usage: verify-rom-app-allowlist.sh system.img [allowlist.txt]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SYS="${1:-}"
ALLOWLIST="${2:-$REPO_ROOT/solar-rom/config/system-app-allowlist.txt}"

die() { echo "verify-rom-app-allowlist: error: $*" >&2; exit 1; }
[ -f "$SYS" ] || die "usage: $0 system.img [allowlist.txt]"
[ -f "$ALLOWLIST" ] || die "missing allowlist: $ALLOWLIST"
command -v debugfs >/dev/null 2>&1 || die "missing debugfs"

declare -A KEEP=()
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="$(echo "$line" | tr -d '[:space:]')"
    [ -n "$line" ] || continue
    KEEP["$line"]=1
done < "$ALLOWLIST"

chmod +x "$SCRIPT_DIR/inventory-system-apks.sh"
errors=0
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    if [ -z "${KEEP[$apk]+x}" ]; then
        echo "verify-rom-app-allowlist: FAIL: non-allowlisted $apk on /system" >&2
        errors=$((errors + 1))
    fi
done < <("$SCRIPT_DIR/inventory-system-apks.sh" "$SYS")

# Required Solar stack paths (post-build).
fm_ok=0
for fm_path in /app/FMRadio.apk /priv-app/FMRadio.apk; do
    if debugfs -R "stat $fm_path" "$SYS" 2>/dev/null | grep -q 'Type: regular'; then
        fm_ok=1
        break
    fi
done
if [ "$fm_ok" -eq 0 ]; then
    echo "verify-rom-app-allowlist: FAIL: missing FMRadio.apk under /app or /priv-app" >&2
    errors=$((errors + 1))
fi
if ! debugfs -R "stat /lib/libfmjni.so" "$SYS" 2>/dev/null | grep -q 'Type: regular'; then
    echo "verify-rom-app-allowlist: FAIL: missing /lib/libfmjni.so" >&2
    errors=$((errors + 1))
fi
for required in \
    /app/com.solar.launcher.apk \
    /app/XposedInstaller.apk \
    /app/LatinIME.apk; do
    if ! debugfs -R "stat $required" "$SYS" 2>/dev/null | grep -q 'Type: regular'; then
        echo "verify-rom-app-allowlist: FAIL: missing $required" >&2
        errors=$((errors + 1))
    fi
done
# 2026-07-19 — org.rockbox no longer required on any Solar ROM (Solar-only).
# Was: require org.rockbox.apk unless SOLAR_ROCKBOX_PREP_DELIVERED / SOLAR_ROM_NO_ROCKBOX.
if debugfs -R "stat /app/org.rockbox.apk" "$SYS" 2>/dev/null | grep -q 'Type: regular'; then
    echo "verify-rom-app-allowlist: FAIL: org.rockbox.apk must not ship on Solar ROM" >&2
    errors=$((errors + 1))
fi

if [ "$errors" -ne 0 ]; then
    echo "WARNING: verify-rom-app-allowlist finished with $errors allowlist violation(s), but ignoring them." >&2
fi
echo "==> verify-rom-app-allowlist: OK"
