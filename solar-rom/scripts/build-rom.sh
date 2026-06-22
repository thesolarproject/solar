#!/usr/bin/env bash
# Build a Solar launcher ROM from Y1 type-A/B or Y2 ATA base firmware.
# Usage: build-rom.sh <a|b|y2> --apk PATH [output.zip]
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
usage: $0 <a|b|y2> (--apk PATH | [--solar-tag TAG] [--solar-apk-url URL]) [output.zip]

  a|b|y2              Y1 type A (2.0.0+), Y1 type B (pre-2.0.0), or Y2 ATA (MT6582)
  --apk PATH          Local signed app-release.apk (CI / local builds)
  --solar-tag         GitHub release tag on ${SOLAR_GITHUB_REPO} (default: latest)
  --solar-apk-url     Direct APK download URL (skips GitHub HTML lookup)
  output.zip          Output archive path
EOF
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        a|b|y2)
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

# Y1 rockbox bases unpack flat; Y2 ATA ships images under a versioned subfolder.
normalize_firmware_layout() {
    if [ -f "$BASE_DIR/system.img" ]; then
        return 0
    fi
    local fw_sub
    fw_sub=$(find "$BASE_DIR" -mindepth 1 -maxdepth 3 -name system.img -printf '%h\n' 2>/dev/null | head -1)
    if [ -z "$fw_sub" ] || [ "$fw_sub" = "$BASE_DIR" ]; then
        die "base firmware missing system.img (expected flat zip or single subfolder)"
    fi
    echo "==> Normalizing nested base firmware layout ($(basename "$fw_sub") -> base)"
    shopt -s dotglob nullglob
    mv "$fw_sub"/* "$BASE_DIR/"
    shopt -u dotglob nullglob
    rmdir "$fw_sub" 2>/dev/null || true
}

# Y2 ships Android sparse images; loop mount needs raw ext4 (Y1 bases are already raw).
is_sparse_android_image() {
    local img="$1"
    local magic
    magic=$(dd if="$img" bs=1 count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')
    [ "$magic" = "3aff26ed" ]
}

prepare_image_for_mount() {
    local img="$1"
    if is_sparse_android_image "$img"; then
        require_cmd simg2img
        local raw="${img%.img}.mount.raw"
        echo "==> Converting sparse $(basename "$img") to raw for loop mount" >&2
        simg2img "$img" "$raw"
        printf '%s\n' "$raw"
    else
        printf '%s\n' "$img"
    fi
}

finalize_image_after_mount() {
    local shipped_path="$1"
    local mount_src="$2"
    if [ "$mount_src" != "$shipped_path" ]; then
        require_cmd img2simg
        echo "==> Repacking $(basename "$shipped_path") to Android sparse"
        img2simg "$mount_src" "$shipped_path"
        rm -f "$mount_src"
    fi
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

case "$TYPE" in
    a)
        BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-a-base/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom.zip}"
        SCATTER_FILE="MT6572_Android_scatter.txt"
        ;;
    b)
        BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-b-base/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_type_b.zip}"
        SCATTER_FILE="MT6572_Android_scatter.txt"
        ;;
    y2)
        BASE_URL="https://github.com/y1-community/y2-ata-rom/releases/download/y2-ata/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_y2.zip}"
        SCATTER_FILE="MT6582_Android_scatter.txt"
        ;;
    *)
        die "unknown type: $TYPE"
        ;;
esac

require_cmd curl
require_cmd unzip
require_cmd zip
require_cmd cmp
require_cmd openssl
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

    echo "==> Auditing ROM contents (type ${TYPE}, scatter ${SCATTER_FILE})"

    for required in boot.img lk.bin logo.bin recovery.img system.img userdata.img "$SCATTER_FILE"; do
        if [ ! -f "$base_dir/$required" ]; then
            echo "audit fail: missing $required in ROM archive" >&2
            errors=$((errors + 1))
        fi
    done

    if find "$sys_mount/app" "$sys_mount/priv-app" -iname '*innioasis*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock launcher APK still present under /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ "$TYPE" = "y2" ] && find "$sys_mount/priv-app" -iname '*factorylauncher*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock Y2 factory launcher still present in /system/priv-app" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/app/$SYSTEM_APK_NAME" ]; then
        echo "audit fail: $SYSTEM_APK_NAME missing from /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/lib/libconscrypt_jni.so" ]; then
        echo "audit fail: libconscrypt_jni.so missing from /system/lib (OkHttp/Reach TLS)" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/security/cacerts/6187b673.0" ]; then
        echo "audit fail: ISRG Root X1 cacert missing (MediaPlayer/podcast HTTPS)" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/init.d/99SolarInit.sh" ]; then
        echo "audit fail: 99SolarInit.sh missing (SD Music/Podcasts/Themes + TLS sanity)" >&2
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

    if find "$user_mount" -maxdepth 1 -name 'com.innioasis.*.apk' 2>/dev/null | grep -q .; then
        echo "audit fail: stock Innioasis launcher APK present in userdata" >&2
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
normalize_firmware_layout

[ -f "$BASE_DIR/system.img" ] || die "system.img not found under $BASE_DIR after unzip"
[ -f "$BASE_DIR/userdata.img" ] || die "userdata.img not found under $BASE_DIR after unzip"

SYSTEM_MOUNT_SRC="$(prepare_image_for_mount "$BASE_DIR/system.img")"
USERDATA_MOUNT_SRC="$(prepare_image_for_mount "$BASE_DIR/userdata.img")"

echo "==> Mounting system.img and userdata.img"
sudo modprobe loop 2>/dev/null || true
sudo mount -t ext4 -o loop "$SYSTEM_MOUNT_SRC" "$MOUNT_SYS"
sudo mount -t ext4 -o loop "$USERDATA_MOUNT_SRC" "$MOUNT_USER"

echo "==> Patching system partition"
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    echo "  removing $apk"
    sudo rm -f "$apk"
done < <(find "$MOUNT_SYS/priv-app" -iname '*innioasis*' 2>/dev/null || true)

if [ "$TYPE" = "y2" ]; then
    while IFS= read -r apk; do
        [ -n "$apk" ] || continue
        echo "  removing $apk"
        sudo rm -f "$apk" "${apk%.apk}.odex"
    done < <(find "$MOUNT_SYS/priv-app" -iname '*factorylauncher*' 2>/dev/null || true)
else
    for apk in "$MOUNT_SYS/app"/com.*.apk; do
        [ -e "$apk" ] || continue
        base=$(basename "$apk")
        [ "$base" = "$SYSTEM_APK_NAME" ] && continue
        echo "  removing system/app/$base"
        sudo rm -f "$apk"
    done
fi

sudo rm -f "$MOUNT_SYS/app/org.rockbox.apk"
sudo rm -f "$MOUNT_SYS/lib/librockbox.so"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1LauncherInit.sh"
sudo rm -f "$MOUNT_SYS/etc/install-recovery.sh"

sudo mkdir -p "$MOUNT_SYS/app" "$MOUNT_SYS/usr/keylayout"
sudo cp "$STAGING_APK" "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chmod 644 "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chown root:root "$MOUNT_SYS/app/$SYSTEM_APK_NAME"

echo "==> Install TLS prep (Conscrypt JNI + modern CA roots)"
TLS_STAGE="$WORK_DIR/system-tls"
chmod +x "$REPO_ROOT/scripts/stage-y1-system-prep.sh" "$REPO_ROOT/scripts/apply-y1-system-prep.sh"
"$REPO_ROOT/scripts/stage-y1-system-prep.sh" "$TLS_STAGE" "$STAGING_APK" "$REPO_ROOT"
sudo "$REPO_ROOT/scripts/apply-y1-system-prep.sh" "$TLS_STAGE" "$MOUNT_SYS"
sudo chown root:root "$MOUNT_SYS/lib/libconscrypt_jni.so"
sudo chown root:root "$MOUNT_SYS/etc/security/cacerts"/*.0 2>/dev/null || true

echo "==> Install Solar boot init (SD library folders + TLS sanity)"
sudo mkdir -p "$MOUNT_SYS/etc/init.d"
sudo cp "$REPO_ROOT/solar-rom/system/99SolarInit.sh" "$MOUNT_SYS/etc/init.d/99SolarInit.sh"
sudo chmod 755 "$MOUNT_SYS/etc/init.d/99SolarInit.sh"
sudo chown root:root "$MOUNT_SYS/etc/init.d/99SolarInit.sh"

sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Stock.kl"
sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
sudo chmod 644 "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
sudo chown root:root "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"

echo "==> Patching userdata partition"
sudo rm -rf "$MOUNT_USER/org.rockbox"
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    echo "  removing userdata/$(basename "$apk")"
    sudo rm -f "$apk"
done < <(find "$MOUNT_USER" -maxdepth 1 -name 'com.innioasis.*.apk' 2>/dev/null || true)
sudo rm -f "$MOUNT_USER/data/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER/data/com.innioasis.y2.apk"
sudo rm -f "$MOUNT_USER"/*_launcher.apk
sudo rm -f "$MOUNT_USER/data/*_launcher_initialized"
sudo rm -f "$MOUNT_USER/data/initialized"

audit_rom_contents "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER"

echo "==> Unmounting images"
sudo umount "$MOUNT_SYS"
sudo umount "$MOUNT_USER"
MOUNT_SYS=""
MOUNT_USER=""

finalize_image_after_mount "$BASE_DIR/system.img" "$SYSTEM_MOUNT_SRC"
finalize_image_after_mount "$BASE_DIR/userdata.img" "$USERDATA_MOUNT_SRC"

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
