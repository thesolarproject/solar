#!/usr/bin/env bash
# Build release APK + Solar ROM zips: Y1 type A/B, Y2 ATA, A5 ATA — mirrors CI.
# 2026-07-15 — Y1 A/B + A5 use y1-community ATA bases (SP Flash Tool included); soft-skip when URL 404
# and no SOLAR_*_BASE_ZIP unless REQUIRE_*_ROM=1.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"
cd "$ROOT"

export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date -u +%s)}"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
DIST="$ROOT/dist"
BUILD_ROM="$ROOT/solar-rom/scripts/build-rom.sh"
SKIP_APK="${SKIP_APK:-0}"

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing $1" >&2; exit 1; }
}

require_cmd curl
require_cmd unzip

chmod +x "$BUILD_ROM" "$ROOT/solar-rom/scripts/"*.sh "$ROOT/scripts/build.sh"

if [ "$SKIP_APK" != "1" ]; then
    echo "==> Building release APK (as $(id -un))"
    "$ROOT/scripts/build.sh"
fi

[[ -f "$APK" ]] || { echo "ERROR: missing $APK" >&2; exit 1; }

# Platform keystore needed for Y2 patch-rockbox-y2.sh even when SKIP_APK=1.
chmod +x "$ROOT/scripts/ensure-platform-keystore.sh" "$ROOT/solar-rom/scripts/sync-y1-assets.sh"
if [ -f "$ROOT/.gradle/platform.keystore" ]; then
    echo "==> Using existing platform.keystore (from APK build or prior run)"
else
    "$ROOT/scripts/ensure-platform-keystore.sh"
fi
"$ROOT/solar-rom/scripts/sync-y1-assets.sh"

mkdir -p "$DIST"

# ROM loop-mount needs root; APK/Gradle must run as the repo owner (git safe.directory).
ROM_SCRIPT="$DIST/.build-roms-inner.sh"
cat >"$ROM_SCRIPT" <<EOF
#!/usr/bin/env bash
set -euo pipefail
export HOME="\${HOME:-/root}"
export JAVA_HOME="${JAVA_HOME:-}"
export ANDROID_HOME="${ANDROID_HOME:-}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
export PATH="/usr/sbin:/sbin:${PATH:-/usr/bin:/bin}"
ROOT="$ROOT"
require_cmd() { command -v "\$1" >/dev/null 2>&1 || { echo "ERROR: missing \$1" >&2; exit 1; }; }
# Docker --privileged runs as root without sudo package — shim before require_cmd sudo.
if [ -f "\$ROOT/solar-rom/scripts/lib-root.sh" ]; then
    # shellcheck source=/dev/null
    source "\$ROOT/solar-rom/scripts/lib-root.sh"
    ensure_sudo_shim_when_root
fi
require_cmd simg2img img2simg e2fsck tune2fs sudo curl unzip zip cmp file
BUILD_ROM="$BUILD_ROM"
APK="$APK"
DIST="$DIST"
# Propagate ATA local bases into root/systemd inner script (env may be stripped).
export SOLAR_Y1A_BASE_ZIP="${SOLAR_Y1A_BASE_ZIP:-}"
export SOLAR_Y1B_BASE_ZIP="${SOLAR_Y1B_BASE_ZIP:-}"
export SOLAR_Y2_BASE_ZIP="${SOLAR_Y2_BASE_ZIP:-}"
export SOLAR_A5_BASE_ZIP="${SOLAR_A5_BASE_ZIP:-}"
export SOLAR_ROM_BASE_ZIP="${SOLAR_ROM_BASE_ZIP:-}"
export REQUIRE_Y1A_ROM="${REQUIRE_Y1A_ROM:-0}"
export REQUIRE_Y1B_ROM="${REQUIRE_Y1B_ROM:-0}"
export REQUIRE_A5_ROM="${REQUIRE_A5_ROM:-0}"
export REQUIRE_ALL_ROMS="${REQUIRE_ALL_ROMS:-0}"
chmod +x "\$BUILD_ROM" "\$ROOT/solar-rom/scripts/"*.sh

# Decide whether each ROM type can build (local zip and/or public URL).
Y1A_URL="https://github.com/y1-community/y1-ata-rom/releases/download/0.1/rom.zip"
Y1B_URL="https://github.com/y1-community/y1-ata-rom/releases/download/0.1/rom_type_b.zip"
A5_URL="https://github.com/y1-community/a5-ata-rom/releases/download/0.1/rom_a5.zip"
url_ok() { curl -fsSIL -o /dev/null "\$1" 2>/dev/null; }

# Echoes: local | url | required | skip  (always exit 0 so set -e is safe)
# SOLAR_*_BASE_ZIP may be a local path or a URL (CI vars); only real files count as local.
rom_base_src() {
    local url="\$1" local_zip="\$2" require="\$3"
    if [ -n "\$local_zip" ] && [ -f "\$local_zip" ]; then
        echo "local"
        return 0
    fi
    # If override is a URL, prefer it when reachable.
    if [ -n "\$local_zip" ] && [[ "\$local_zip" == http://* || "\$local_zip" == https://* ]]; then
        if url_ok "\$local_zip"; then
            echo "url"
            return 0
        fi
    fi
    if url_ok "\$url"; then
        echo "url"
        return 0
    fi
    if [ "\$require" = "1" ] || [ "\${REQUIRE_ALL_ROMS:-0}" = "1" ]; then
        echo "required"
        return 0
    fi
    echo "skip"
}

BUILD_Y1A=0
BUILD_Y1B=0
BUILD_A5=0
Y1A_SRC=\$(rom_base_src "\$Y1A_URL" "\${SOLAR_Y1A_BASE_ZIP:-}" "\${REQUIRE_Y1A_ROM:-0}")
Y1B_SRC=\$(rom_base_src "\$Y1B_URL" "\${SOLAR_Y1B_BASE_ZIP:-}" "\${REQUIRE_Y1B_ROM:-0}")
A5_SRC=\$(rom_base_src "\$A5_URL" "\${SOLAR_A5_BASE_ZIP:-}" "\${REQUIRE_A5_ROM:-0}")
[ "\$Y1A_SRC" != "skip" ] && BUILD_Y1A=1 || echo "==> SKIP Y1 type A (set SOLAR_Y1A_BASE_ZIP or wait for y1-ata-rom 0.1)"
[ "\$Y1B_SRC" != "skip" ] && BUILD_Y1B=1 || echo "==> SKIP Y1 type B (set SOLAR_Y1B_BASE_ZIP or wait for y1-ata-rom 0.1)"
[ "\$A5_SRC" != "skip" ] && BUILD_A5=1 || echo "==> SKIP A5 ROM (set SOLAR_A5_BASE_ZIP or wait for a5-ata-rom 0.1)"

if [ "\${REQUIRE_ALL_ROMS:-0}" = "1" ]; then
    [ "\$BUILD_Y1A" = "1" ] && [ "\$BUILD_Y1B" = "1" ] && [ "\$BUILD_A5" = "1" ] \
        || { echo "ERROR: REQUIRE_ALL_ROMS=1 but a Y1/A5 base is unavailable — provide SOLAR_Y1A/B_BASE_ZIP + SOLAR_A5_BASE_ZIP" >&2; exit 1; }
fi

scatter_ok() { [ "\$(unzip -l "\$1" 2>/dev/null | grep -c "\$2" || true)" -ge 1 ]; }

if [ "\$BUILD_Y1A" = "1" ]; then
    echo "==> Building Y1 type A ROM (dist/rom.zip) [\$Y1A_SRC]"
    sudo modprobe loop max_loop=64 2>/dev/null || true
    "\$BUILD_ROM" a --apk "\$APK" "\$DIST/rom.zip"
fi
if [ "\$BUILD_Y1B" = "1" ]; then
    echo "==> Building Y1 type B ROM (dist/rom_type_b.zip) [\$Y1B_SRC]"
    sudo modprobe loop max_loop=64 2>/dev/null || true
    "\$BUILD_ROM" b --apk "\$APK" "\$DIST/rom_type_b.zip"
fi
if [ -n "\${SOLAR_Y2_BASE_ZIP:-}" ] && [ -f "\${SOLAR_Y2_BASE_ZIP}" ]; then
    echo "==> Building Y2 ATA ROM (dist/rom_y2.zip) [local \$SOLAR_Y2_BASE_ZIP]"
else
    echo "==> Building Y2 ATA ROM (dist/rom_y2.zip) [public y2-ata-rom]"
fi
sudo modprobe loop max_loop=64 2>/dev/null || true
"\$BUILD_ROM" y2 --apk "\$APK" "\$DIST/rom_y2.zip"
if [ "\$BUILD_A5" = "1" ]; then
    echo "==> Building A5 ATA ROM (dist/rom_a5.zip) [\$A5_SRC]"
    sudo modprobe loop max_loop=64 2>/dev/null || true
    "\$BUILD_ROM" a5 --apk "\$APK" "\$DIST/rom_a5.zip"
fi

echo "==> ROM archive sanity checks"
[[ -f "\$DIST/rom_y2.zip" ]] || { echo "ERROR: missing \$DIST/rom_y2.zip" >&2; exit 1; }
ls -lh "\$DIST/rom_y2.zip"
scatter_ok "\$DIST/rom_y2.zip" MT6582_Android_scatter.txt \
    || { echo "ERROR: rom_y2.zip missing MT6582 scatter" >&2; exit 1; }
_ft=\$(unzip -l "\$DIST/rom_y2.zip" 2>/dev/null | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)
[ "\${_ft:-0}" -ge 1 ] || { echo "ERROR: rom_y2.zip missing SP Flash Tool" >&2; exit 1; }
# Flash geometry first (scatter offsets) — contents/xposed are heavier secondary checks.
"\$ROOT/solar-rom/scripts/verify-y2-rom-flash.sh" "\$DIST/rom_y2.zip"
"\$ROOT/solar-rom/scripts/verify-y2-rom-contents.sh" "\$DIST/rom_y2.zip"
# Xposed paths already audited inside build-rom; keep one lightweight API-level check only.
"\$ROOT/solar-rom/scripts/verify-xposed-rom-contents.sh" "\$DIST/rom_y2.zip" 19
cp -f "\$DIST/rom_y2.zip" "\$ROOT/rom_y2.zip"

if [ "\$BUILD_Y1A" = "1" ]; then
    [[ -f "\$DIST/rom.zip" ]] || { echo "ERROR: missing \$DIST/rom.zip" >&2; exit 1; }
    ls -lh "\$DIST/rom.zip"
    scatter_ok "\$DIST/rom.zip" MT6572_Android_scatter.txt \
        || { echo "ERROR: rom.zip missing MT6572 scatter" >&2; exit 1; }
    _ft=\$(unzip -l "\$DIST/rom.zip" 2>/dev/null | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)
    [ "\${_ft:-0}" -ge 1 ] || { echo "ERROR: rom.zip missing SP Flash Tool" >&2; exit 1; }
    "\$ROOT/solar-rom/scripts/verify-y1-rom-contents.sh" "\$DIST/rom.zip"
    "\$ROOT/solar-rom/scripts/verify-xposed-rom-contents.sh" "\$DIST/rom.zip" 17
    cp -f "\$DIST/rom.zip" "\$ROOT/rom.zip"
fi
if [ "\$BUILD_Y1B" = "1" ]; then
    [[ -f "\$DIST/rom_type_b.zip" ]] || { echo "ERROR: missing \$DIST/rom_type_b.zip" >&2; exit 1; }
    ls -lh "\$DIST/rom_type_b.zip"
    scatter_ok "\$DIST/rom_type_b.zip" MT6572_Android_scatter.txt \
        || { echo "ERROR: rom_type_b.zip missing MT6572 scatter" >&2; exit 1; }
    _ft=\$(unzip -l "\$DIST/rom_type_b.zip" 2>/dev/null | grep -ciE 'flash_tool\.exe|FlashToolLib' || true)
    [ "\${_ft:-0}" -ge 1 ] || { echo "ERROR: rom_type_b.zip missing SP Flash Tool" >&2; exit 1; }
    "\$ROOT/solar-rom/scripts/verify-y1-rom-contents.sh" "\$DIST/rom_type_b.zip"
    "\$ROOT/solar-rom/scripts/verify-xposed-rom-contents.sh" "\$DIST/rom_type_b.zip" 17
    cp -f "\$DIST/rom_type_b.zip" "\$ROOT/rom_type_b.zip"
fi
if [ "\$BUILD_A5" = "1" ]; then
    [[ -f "\$DIST/rom_a5.zip" ]] || { echo "ERROR: missing \$DIST/rom_a5.zip" >&2; exit 1; }
    ls -lh "\$DIST/rom_a5.zip"
    scatter_ok "\$DIST/rom_a5.zip" MT6572_Android_scatter.txt \
        || { echo "ERROR: rom_a5.zip missing MT6572 scatter" >&2; exit 1; }
    "\$ROOT/solar-rom/scripts/verify-a5-rom-contents.sh" "\$DIST/rom_a5.zip"
    "\$ROOT/solar-rom/scripts/verify-xposed-rom-contents.sh" "\$DIST/rom_a5.zip" 17
    cp -f "\$DIST/rom_a5.zip" "\$ROOT/rom_a5.zip"
fi
echo "==> All ROM builds complete (built: y1a=\$BUILD_Y1A y1b=\$BUILD_Y1B y2=1 a5=\$BUILD_A5)"
EOF
chmod +x "$ROM_SCRIPT"

if [ "$(id -u)" -eq 0 ]; then
    echo "==> Building ROMs (already root)"
    bash "$ROM_SCRIPT"
elif sudo -n true 2>/dev/null; then
    echo "==> Building ROMs (sudo)"
    sudo bash "$ROM_SCRIPT"
elif command -v systemd-run >/dev/null 2>&1; then
    echo "==> Building ROMs (systemd-run — loop mount needs root)"
    systemd-run --wait --collect bash "$ROM_SCRIPT"
else
    echo "ERROR: ROM build needs root (sudo, docker --privileged, or systemd-run) for loop-mount" >&2
    exit 1
fi

echo "ROM outputs:"
[ -f "$DIST/rom.zip" ] && echo "    $DIST/rom.zip"
[ -f "$DIST/rom_type_b.zip" ] && echo "    $DIST/rom_type_b.zip"
echo "    $DIST/rom_y2.zip"
[ -f "$DIST/rom_a5.zip" ] && echo "    $DIST/rom_a5.zip"
