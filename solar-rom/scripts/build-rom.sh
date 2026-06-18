#!/usr/bin/env bash
# Build a Solar launcher ROM from stock Rockbox-Y1 type-A or type-B base firmware.
# Usage: build-rom.sh <a|b> --apk PATH [output.zip]
set -euo pipefail

TYPE=""
SOLAR_APK=""
SOLAR_TAG=""
SOLAR_APK_URL=""
OUTPUT=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK_DIR=""
MOUNT_SYS=""
MOUNT_USER=""
SYSTEM_APK_NAME="com.solar.launcher.apk"

# shellcheck source=/dev/null
source "$SCRIPT_DIR/solar-repo.sh"

usage() {
    cat >&2 <<EOF
usage: $0 <a|b> (--apk PATH | [--solar-tag TAG] [--solar-apk-url URL]) [output.zip]

  a|b                 Type A (firmware 2.0.0+) or Type B (firmware before 2.0.0)
  --apk PATH          Local signed app-release.apk (CI / local builds)
  --solar-tag         GitHub release tag on ${SOLAR_GITHUB_REPO} (default: latest)
  --solar-apk-url     Direct APK download URL (skips GitHub HTML lookup)
  output.zip          Output archive path
EOF
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        a|b)
            TYPE="$1"
            shift
            ;;
        --apk)
            SOLAR_APK="${2:-}"
            [ -n "$SOLAR_APK" ] || usage
            shift 2
            ;;
        --solar-tag)
            SOLAR_TAG="${2:-}"
            [ -n "$SOLAR_TAG" ] || usage
            shift 2
            ;;
        --solar-apk-url)
            SOLAR_APK_URL="${2:-}"
            [ -n "$SOLAR_APK_URL" ] || usage
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            if [ -z "$OUTPUT" ]; then
                OUTPUT="$1"
                shift
            else
                usage
            fi
            ;;
    esac
done

[ -n "$TYPE" ] || usage

cleanup() {
    if [ -n "$MOUNT_SYS" ] && mountpoint -q "$MOUNT_SYS" 2>/dev/null; then
        sudo umount "$MOUNT_SYS" || true
    fi
    if [ -n "$MOUNT_USER" ] && mountpoint -q "$MOUNT_USER" 2>/dev/null; then
        sudo umount "$MOUNT_USER" || true
    fi
    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

die() {
    echo "error: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

if [ "$TYPE" = "a" ]; then
    BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-a-base/rom.zip"
    OUTPUT="${OUTPUT:-$REPO_ROOT/rom.zip}"
else
    BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-b-base/rom.zip"
    OUTPUT="${OUTPUT:-$REPO_ROOT/rom_type_b.zip}"
fi

require_cmd curl
require_cmd unzip
require_cmd zip
require_cmd cmp
require_cmd sudo

resolve_latest_solar_tag() {
    local release_url tag
    release_url="$(
        curl -fsSIL -A 'solar-rom-build/1.0' \
            "${SOLAR_GITHUB_URL}/releases/latest" \
        | awk -F': ' 'tolower($1) ~ /^location$/ { print $2 }' \
        | tr -d '\r' \
        | tail -1
    )"
    [ -n "$release_url" ] || die "could not resolve latest Solar release URL"
    tag="${release_url##*/}"
    [ -n "$tag" ] || die "could not parse latest Solar release tag"
    printf '%s' "$tag"
}

download_solar_apk() {
    local dest="$1"
    local tag="${2:-}"
    local apk_url="${3:-}"

    if [ -n "$apk_url" ]; then
        echo "Downloading $(basename "$apk_url")"
        curl -fsSL -o "$dest" "$apk_url"
        return
    fi

    if [ -z "$tag" ]; then
        tag="$(resolve_latest_solar_tag)"
    fi

    apk_url="$(
        curl -fsSL -A 'solar-rom-build/1.0' \
            "${SOLAR_GITHUB_URL}/releases/expanded_assets/${tag}" \
        | grep -Eo "href=\"/${SOLAR_GITHUB_REPO}/releases/download/[^\"]+app-release[^\"]*\\.apk\"" \
        | head -1 \
        | sed 's/^href="//; s/"$//'
    )"
    [ -n "$apk_url" ] || die "could not find app-release APK for release ${tag}"

    apk_url="https://github.com${apk_url}"
    echo "Downloading $(basename "$apk_url") from release ${tag}"
    curl -fsSL -o "$dest" "$apk_url"
}

audit_rom_contents() {
    local base_dir="$1"
    local sys_mount="$2"
    local user_mount="$3"
    local errors=0

    echo "==> Auditing ROM contents"

    for required in boot.img lk.bin logo.bin recovery.img system.img userdata.img MT6572_Android_scatter.txt; do
        if [ ! -f "$base_dir/$required" ]; then
            echo "audit fail: missing $required in ROM archive" >&2
            errors=$((errors + 1))
        fi
    done

    if find "$sys_mount/app" "$sys_mount/priv-app" -iname '*innioasis*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock launcher APK still present under /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/app/$SYSTEM_APK_NAME" ]; then
        echo "audit fail: $SYSTEM_APK_NAME missing from /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$sys_mount/etc/init.d/99Y1ButtonScript" ] || [ -f "$sys_mount/etc/init.d/99Y1LauncherInit.sh" ]; then
        echo "audit fail: legacy init.d scripts still present" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$sys_mount/app/org.rockbox.apk" ]; then
        echo "audit fail: org.rockbox.apk still present" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/usr/keylayout/Generic.kl" ] || [ ! -f "$sys_mount/usr/keylayout/Stock.kl" ]; then
        echo "audit fail: keylayout files missing" >&2
        errors=$((errors + 1))
    elif ! cmp -s "$sys_mount/usr/keylayout/Generic.kl" "$sys_mount/usr/keylayout/Stock.kl"; then
        echo "audit fail: Generic.kl is not identical to Stock.kl" >&2
        errors=$((errors + 1))
    fi

    if [ -n "$(find "$user_mount" -maxdepth 1 -name '*_launcher.apk' ! -name 'com.solar.launcher.apk' -print -quit 2>/dev/null)" ]; then
        echo "audit fail: legacy launcher APK in userdata" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$user_mount/com.innioasis.y1.apk" ]; then
        echo "audit fail: com.innioasis.y1.apk present in userdata" >&2
        errors=$((errors + 1))
    fi

    if [ -d "$user_mount/org.rockbox" ]; then
        echo "audit fail: /data/org.rockbox still present" >&2
        errors=$((errors + 1))
    fi

    if [ "$errors" -ne 0 ]; then
        die "ROM audit failed with $errors error(s)"
    fi

    echo "==> ROM audit passed"
}

WORK_DIR="$(mktemp -d)"
BASE_DIR="$WORK_DIR/base"
MOUNT_SYS="$BASE_DIR/mount_sys"
MOUNT_USER="$BASE_DIR/mount_user"
STAGING_APK="$WORK_DIR/solar.apk"

mkdir -p "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER"

if [ -n "$SOLAR_APK" ]; then
    [ -f "$SOLAR_APK" ] || die "APK not found: $SOLAR_APK"
    cp "$SOLAR_APK" "$STAGING_APK"
    echo "==> Using local Solar APK: $SOLAR_APK"
elif [ -n "$SOLAR_APK_URL" ]; then
    echo "==> Downloading Solar APK from release metadata"
    download_solar_apk "$STAGING_APK" "$SOLAR_TAG" "$SOLAR_APK_URL"
elif [ -n "$SOLAR_TAG" ]; then
    echo "==> Downloading Solar APK for tag ${SOLAR_TAG}"
    download_solar_apk "$STAGING_APK" "$SOLAR_TAG"
else
    echo "==> Downloading latest Solar release APK"
    download_solar_apk "$STAGING_APK"
fi

echo "==> Downloading type-${TYPE} base firmware"
curl -fsSL -o "$BASE_DIR/rom.zip" "$BASE_URL"
unzip -q "$BASE_DIR/rom.zip" -d "$BASE_DIR"

echo "==> Mounting system.img and userdata.img"
sudo modprobe loop 2>/dev/null || true
sudo mount -t ext4 -o loop "$BASE_DIR/system.img" "$MOUNT_SYS"
sudo mount -t ext4 -o loop "$BASE_DIR/userdata.img" "$MOUNT_USER"

echo "==> Patching system partition"
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    echo "  removing $apk"
    sudo rm -f "$apk"
done < <(find "$MOUNT_SYS/priv-app" -iname '*innioasis*' 2>/dev/null || true)

for apk in "$MOUNT_SYS/app"/com.*.apk; do
    [ -e "$apk" ] || continue
    base=$(basename "$apk")
    [ "$base" = "$SYSTEM_APK_NAME" ] && continue
    echo "  removing system/app/$base"
    sudo rm -f "$apk"
done

sudo rm -f "$MOUNT_SYS/app/org.rockbox.apk"
sudo rm -f "$MOUNT_SYS/lib/librockbox.so"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1LauncherInit.sh"
sudo rm -f "$MOUNT_SYS/etc/install-recovery.sh"

sudo mkdir -p "$MOUNT_SYS/app" "$MOUNT_SYS/usr/keylayout"
sudo cp "$STAGING_APK" "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chmod 644 "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chown root:root "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Stock.kl"
sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
sudo chmod 644 "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
sudo chown root:root "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"

echo "==> Patching userdata partition"
sudo rm -rf "$MOUNT_USER/org.rockbox"
sudo rm -f "$MOUNT_USER/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER/data/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER"/*_launcher.apk
sudo rm -f "$MOUNT_USER/data/*_launcher_initialized"
sudo rm -f "$MOUNT_USER/data/initialized"

audit_rom_contents "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER"

echo "==> Unmounting images"
sudo umount "$MOUNT_SYS"
sudo umount "$MOUNT_USER"
MOUNT_SYS=""
MOUNT_USER=""

rm -f "$BASE_DIR/rom.zip"
rm -rf "$MOUNT_SYS" "$MOUNT_USER"

mkdir -p "$(dirname "$OUTPUT")"
echo "==> Creating $OUTPUT"
rm -f "$OUTPUT"
(
    cd "$BASE_DIR"
    zip -j -q "$OUTPUT" ./*
)

echo "==> Built $OUTPUT ($(du -h "$OUTPUT" | awk '{print $1}'))"
