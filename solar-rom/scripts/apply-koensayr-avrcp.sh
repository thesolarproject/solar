#!/usr/bin/env bash
# Apply Koensayr AVRCP 1.3 + Bluetooth profile patches to a mounted system image.
# Usage: apply-koensayr-avrcp.sh /path/to/mounted/system
set -euo pipefail

MOUNT_SYS="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOENSAYR_DIR="${KOENSAYR_DIR:-$REPO_ROOT/solar-rom/koensayr}"
Y1_BRIDGE_APK="Y1Bridge.apk"
STAGE_DIR=""

die() { echo "error: $*" >&2; exit 1; }

cleanup() {
    if [ -n "$STAGE_DIR" ] && [ -d "$STAGE_DIR" ]; then
        rm -rf "$STAGE_DIR"
    fi
}
trap cleanup EXIT

[ -n "$MOUNT_SYS" ] && [ -d "$MOUNT_SYS" ] || die "usage: $0 /path/to/mounted/system"
[ -d "$KOENSAYR_DIR/src/patches" ] || die "Koensayr not found at $KOENSAYR_DIR — clone into solar-rom/koensayr or set KOENSAYR_DIR"
command -v python3 >/dev/null 2>&1 || die "python3 required for Koensayr patchers"

STAGE_DIR="$(mktemp -d)"

patch_in_place_bytes() {
    local mount_rel="$1"
    local script="$2"
    local mode="${3:-644}"
    local stage="${STAGE_DIR}/$(basename "$mount_rel")"
    local stock="${stage}/stock"
    local patched="${stage}/patched"
    mkdir -p "$stage"
    echo "  ${mount_rel}: ${script}"
    sudo cp "$MOUNT_SYS/$mount_rel" "$stock"
    sudo chown "$(id -u):$(id -g)" "$stock"
    if ! python3 "$KOENSAYR_DIR/src/patches/$script" "$stock" --output "$patched" --skip-md5; then
        die "${script} failed for ${mount_rel}"
    fi
    if [ -f "$patched" ]; then
        sudo cp "$patched" "$MOUNT_SYS/$mount_rel"
        sudo chmod "$mode" "$MOUNT_SYS/$mount_rel"
        sudo chown root:root "$MOUNT_SYS/$mount_rel"
    fi
}

echo "==> Koensayr AVRCP patches (from $KOENSAYR_DIR)"

BRIDGE_SRC="$KOENSAYR_DIR/src/Y1Bridge/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$BRIDGE_SRC" ]; then
    echo "==> Building Y1Bridge.apk"
    chmod +x "$KOENSAYR_DIR/src/Y1Bridge/gradlew" 2>/dev/null || true
    (cd "$KOENSAYR_DIR/src/Y1Bridge" && ./gradlew --stop >/dev/null 2>&1 || true)
    (cd "$KOENSAYR_DIR/src/Y1Bridge" && ./gradlew assembleDebug)
fi
[ -f "$BRIDGE_SRC" ] || die "Y1Bridge build missing: $BRIDGE_SRC"

BRIDGE_INSTALL="$STAGE_DIR/Y1Bridge.apk"
PK8="${SOLAR_PLATFORM_KEY_PK8:-}"
PEM="${SOLAR_PLATFORM_KEY_PEM:-}"
APKSIGNER="${ANDROID_HOME:-}/build-tools/35.0.0/apksigner"
if [[ ! -x "$APKSIGNER" && -d "${ANDROID_HOME:-}/build-tools" ]]; then
    APKSIGNER="$(ls -1 "${ANDROID_HOME}/build-tools"/*/apksigner 2>/dev/null | tail -1)"
fi
if [[ -f "$PK8" && -f "$PEM" && -n "$APKSIGNER" && -x "$APKSIGNER" ]]; then
    echo "==> Signing Y1Bridge with platform key"
    "$APKSIGNER" sign --key "$PK8" --cert "$PEM" --out "$BRIDGE_INSTALL" "$BRIDGE_SRC"
else
    echo "==> Installing Y1Bridge (debug-signed; set SOLAR_PLATFORM_KEY_* to platform-sign)"
    cp "$BRIDGE_SRC" "$BRIDGE_INSTALL"
fi

sudo mkdir -p "$MOUNT_SYS/app"
sudo install -m 644 -o root -g root "$BRIDGE_INSTALL" "$MOUNT_SYS/app/$Y1_BRIDGE_APK"

patch_in_place_bytes "app/MtkBt.odex" "patch_mtkbt_odex.py" 644
patch_in_place_bytes "bin/mtkbt" "patch_mtkbt.py" 755
patch_in_place_bytes "lib/libextavrcp_jni.so" "patch_libextavrcp_jni.py" 644
patch_in_place_bytes "lib/libextavrcp.so" "patch_libextavrcp.py" 644
patch_in_place_bytes "lib/libaudio.a2dp.default.so" "patch_libaudio_a2dp.py" 644
patch_in_place_bytes "usr/keylayout/AVRCP.kl" "patch_avrcp_kl.py" 644

echo "==> Bluetooth profile build.prop + audio.conf"
if [ -f "$MOUNT_SYS/etc/bluetooth/audio.conf" ]; then
    sudo sed -i 's/^Enable=.*/Enable=Source,Control,Target/' "$MOUNT_SYS/etc/bluetooth/audio.conf" || true
    sudo sed -i 's/^Master=.*/Master=true/' "$MOUNT_SYS/etc/bluetooth/audio.conf" || true
fi
if [ -f "$MOUNT_SYS/etc/bluetooth/auto_pairing.conf" ]; then
    sudo sed -i 's/^AddressBlacklist=.*/AddressBlacklist=/' "$MOUNT_SYS/etc/bluetooth/auto_pairing.conf" || true
    sudo sed -i 's/^ExactNameBlacklist=.*/ExactNameBlacklist=/' "$MOUNT_SYS/etc/bluetooth/auto_pairing.conf" || true
    sudo sed -i 's/^PartialNameBlacklist=.*/PartialNameBlacklist=/' "$MOUNT_SYS/etc/bluetooth/auto_pairing.conf" || true
fi
if [ -f "$MOUNT_SYS/etc/bluetooth/blacklist.conf" ]; then
    sudo sed -i '/^scoSocket/d' "$MOUNT_SYS/etc/bluetooth/blacklist.conf" || true
fi
if [ -f "$MOUNT_SYS/build.prop" ]; then
    if ! grep -q 'ro.bluetooth.profiles.avrcp.target.enabled=true' "$MOUNT_SYS/build.prop" 2>/dev/null; then
        sudo tee -a "$MOUNT_SYS/build.prop" >/dev/null <<'EOF'

# Solar / Koensayr AVRCP target profile
ro.bluetooth.class=10486812
ro.bluetooth.profiles.a2dp.source.enabled=true
ro.bluetooth.profiles.avrcp.target.enabled=true
EOF
    fi
fi

echo "==> Koensayr AVRCP patches applied"
