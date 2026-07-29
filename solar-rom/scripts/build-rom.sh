#!/usr/bin/env bash
# 2026-07-05 — Assembles Solar ROM zips (a/b/y2/a5) from base firmware + Solar APK + platform bake.
# 2026-07-06 — Y2 ships desparsed ext4 system/userdata (MTKclient wo), bundles EBR2, scatter offsets in
#   solar-rom/config/y2-mtk-flash-manifest.txt (verify-y2-rom-flash.sh in CI).
# 2026-07-15 — TYPE=a5: MT6572 A5 ATA base → rom_a5.zip (A5 keylayouts, family pin, no Rockbox bake).
# APK/ROM parity: must call install-xposed-system.sh for all types; audit_rom_contents gates Xposed.
# When changing: verify-y1/y2/a5-rom-contents.sh + verify-xposed-rom-contents.sh; lib-xposed-install.sh paths.
# Reversal: skip Xposed install block; ROM ships without framework (APK prep cannot fully self-heal).
# Usage: build-rom.sh <a|b|y2|a5> --apk PATH [output.zip]
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
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-root.sh"
ensure_sudo_shim_when_root
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib-debug-log.sh"

usage() {
    cat >&2 <<EOF
usage: $0 <a|b|y2|a5> (--apk PATH | [--solar-tag TAG] [--solar-apk-url URL]) [output.zip]

  a|b|y2|a5           Y1 type A/B, Y2 ATA (MT6582), or A5 ATA (MT6572 touch)
  --apk PATH          Local signed app-release.apk (CI / local builds)
  --solar-tag         GitHub release tag on ${SOLAR_GITHUB_REPO} (default: latest)
  --solar-apk-url     Direct APK download URL (skips GitHub HTML lookup)
  output.zip          Output archive path
  Local base overrides (skip download when GitHub release not public yet):
    SOLAR_Y1A_BASE_ZIP / SOLAR_Y1B_BASE_ZIP / SOLAR_Y2_BASE_ZIP / SOLAR_A5_BASE_ZIP
    SOLAR_ROM_BASE_ZIP — generic override for the type being built
EOF
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        a|b|y2|a5)
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

# 2026-07-15 — Some A5 ATA system.img zips are a few KiB short of the ext4 block count.
# Layman: the image file is slightly truncated; pad zeros so Linux can loop-mount it.
# Tech: read s_blocks_count + s_log_block_size at superblock+1024; truncate -s if file < fs size.
# Reversal: remove call; short A5 system.img fails with "wrong fs type / bad superblock" on mount.
pad_ext4_image_if_short() {
    local img="$1"
    [ -f "$img" ] || return 0
    is_sparse_android_image "$img" && return 0
    local need
    need="$(python3 - "$img" <<'PY'
import struct, os, sys
path = sys.argv[1]
size = os.path.getsize(path)
with open(path, "rb") as f:
    f.seek(1024)
    sb = f.read(1024)
if len(sb) < 0x40 or struct.unpack_from("<H", sb, 0x38)[0] != 0xEF53:
    print(0)
    raise SystemExit(0)
blocks = struct.unpack_from("<I", sb, 0x04)[0]
log_bs = struct.unpack_from("<I", sb, 0x18)[0]
need = blocks * (1024 << log_bs)
print(need if need > size else 0)
PY
)"
    if [ "${need:-0}" -gt 0 ]; then
        echo "==> Padding $(basename "$img") to ${need} bytes (ext4 block count; was shorter than fs size)"
        truncate -s "$need" "$img"
    fi
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

# MTK SP Flash Tool rejects corrupt ext4 sparse images — fsck raw before img2simg repack.
verify_sparse_roundtrip() {
    local sparse="$1"
    is_sparse_android_image "$sparse" || return 0
    require_cmd simg2img
    local verify="${sparse}.verify.raw"
    simg2img "$sparse" "$verify"
    tune2fs -l "$verify" >/dev/null 2>&1 \
        || die "sparse round-trip failed for $(basename "$sparse")"
    rm -f "$verify"
}

finalize_image_raw() {
    local shipped_path="$1"
    local mount_src="$2"
    if [ "$mount_src" = "$shipped_path" ]; then
        return 0
    fi
    require_cmd e2fsck
    sync
    set +e
    e2fsck -f -y "$mount_src" >/dev/null 2>&1
    set -e
    echo "==> Ship desparsed ext4 $(basename "$shipped_path") ($(du -h "$mount_src" | awk '{print $1}'))"
    rm -f "$shipped_path"
    mv -f "$mount_src" "$shipped_path"
    if is_sparse_android_image "$shipped_path"; then
        die "$(basename "$shipped_path") is still sparse after desparse"
    fi
}

# 2026-07-06 — Y2 system+userdata must not share one ext4 UUID (init/vold confusion on MT6582).
assign_ext4_unique_uuid() {
    local img="$1"
    require_cmd tune2fs
    tune2fs -U random "$img" >/dev/null
    echo "==> Assigned unique ext4 UUID to $(basename "$img")" >&2
}

finalize_image_after_mount() {
    local shipped_path="$1"
    local mount_src="$2"
    if [ "$mount_src" != "$shipped_path" ]; then
        require_cmd img2simg
        require_cmd e2fsck
        sync
        echo "==> Fsck $(basename "$shipped_path") before sparse repack"
        set +e
        e2fsck -f -y "$mount_src" >/dev/null 2>&1
        local fsck_rc=$?
        set -e
        # e2fsck returns 1 when it auto-fixed bitmap padding — that is OK for MTK sparse repack.
        if [ "$fsck_rc" -gt 1 ]; then
            die "e2fsck failed on $(basename "$mount_src") (exit $fsck_rc)"
        fi
        echo "==> Repacking $(basename "$shipped_path") to Android sparse"
        local tmp="${shipped_path}.repack.$$"
        # Y2 MTK DA is picky about sparse layout — -s matches vendor chunk style more closely.
        if [ "$TYPE" = "y2" ] && [ "$(basename "$shipped_path")" = "system.img" ]; then
            require_cmd resize2fs
            resize2fs -M "$mount_src" >/dev/null 2>&1 || true
            img2simg -s "$mount_src" "$tmp" 4096
        else
            img2simg "$mount_src" "$tmp" 4096
        fi
        mv -f "$tmp" "$shipped_path"
        rm -f "$mount_src"
        verify_sparse_roundtrip "$shipped_path"
    fi
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# 2026-07-05 — Y2 ATA base omits MediaTek FM; bake FMRadio.apk + libfm* from Y1 type-A (Settings FM entry).
install_fm_from_y1_base() {
    local mount_sys="$1"
    local cache so base
    chmod +x "$SCRIPT_DIR/fetch-fm-mtk-from-y1-base.sh"
    cache="$("$SCRIPT_DIR/fetch-fm-mtk-from-y1-base.sh")"
    [ -f "$cache/FMRadio.apk" ] && [ -f "$cache/libfmjni.so" ] \
        || die "fetch-fm-mtk-from-y1-base.sh did not populate FMRadio.apk + libfmjni.so"
    if [ -f "$mount_sys/app/FMRadio.apk" ] || [ -f "$mount_sys/priv-app/FMRadio.apk" ]; then
        echo "==> MediaTek FMRadio.apk already on /system — refreshing libfm* only"
    else
        echo "==> Installing FMRadio.apk from Y1 base (Settings → FM Radio + Solar FmEngine bind)"
        sudo cp "$cache/FMRadio.apk" "$mount_sys/app/FMRadio.apk"
        sudo chmod 644 "$mount_sys/app/FMRadio.apk"
        sudo chown root:root "$mount_sys/app/FMRadio.apk"
        if [ -f "$cache/FMRadio.odex" ]; then
            sudo cp "$cache/FMRadio.odex" "$mount_sys/app/FMRadio.odex"
            sudo chmod 644 "$mount_sys/app/FMRadio.odex"
            sudo chown root:root "$mount_sys/app/FMRadio.odex"
        fi
    fi
    for so in "$cache"/libfm*.so; do
        [ -f "$so" ] || continue
        base="$(basename "$so")"
        echo "  installing /system/lib/$base"
        sudo cp "$so" "$mount_sys/lib/$base"
        sudo chmod 644 "$mount_sys/lib/$base"
        sudo chown root:root "$mount_sys/lib/$base"
    done
}

# Y2 ATA stock base has no Rockbox; fetch org.rockbox.apk + librockbox.so from rockbox-y1 type-A base.
install_notpipe_system() {
    local mount_sys="$1"
    local cache apk_name
    chmod +x "$SCRIPT_DIR/fetch-notpipe-apk.sh"
    cache="$("$SCRIPT_DIR/fetch-notpipe-apk.sh")"
    apk_name="notPipe-0.3.0-release.apk"
    [ -f "$cache/$apk_name" ] || die "fetch-notpipe-apk.sh did not populate $apk_name"
    echo "==> Installing notPipe YouTube client (io.github.gohoski.notpipe)"
    sudo cp "$cache/$apk_name" "$mount_sys/app/io.github.gohoski.notpipe.apk"
    sudo chmod 644 "$mount_sys/app/io.github.gohoski.notpipe.apk"
    sudo chown root:root "$mount_sys/app/io.github.gohoski.notpipe.apk"
}

# 2026-07-06 — Permanent PM preferred HOME middle-man; routes to Solar/Rockbox/JJ via persist prop.
install_launcher_helper_system() {
    local mount_sys="$1"
    local helper_apk="$REPO_ROOT/launcher-helper/build/outputs/apk/debug/launcher-helper-debug.apk"
    if [ ! -f "$helper_apk" ]; then
        echo "==> Build SolarHomeHelper.apk (first run)"
        (cd "$REPO_ROOT" && ./gradlew :launcher-helper:assembleDebug -q)
    fi
    [ -f "$helper_apk" ] || die "missing $helper_apk — run ./gradlew :launcher-helper:assembleDebug"
    echo "==> Installing Solar Home Helper (com.solar.launcher.homehelper)"
    sudo cp "$helper_apk" "$mount_sys/app/SolarHomeHelper.apk"
    sudo chmod 644 "$mount_sys/app/SolarHomeHelper.apk"
    sudo chown root:root "$mount_sys/app/SolarHomeHelper.apk"
}

# 2026-07-15 — Y1 ATA base may omit Rockbox; pull stock APK+so from rockbox-y1 cache (no Y2 resign).
# Was: Y1 rockbox always came from type-a/b base zip. Now: heal if missing after ATA switch.
install_rockbox_stock_y1() {
    local mount_sys="$1"
    local cache
    chmod +x "$SCRIPT_DIR/fetch-rockbox-y1-y2-assets.sh"
    cache="$("$SCRIPT_DIR/fetch-rockbox-y1-y2-assets.sh")"
    [ -f "$cache/org.rockbox.apk" ] && [ -f "$cache/librockbox.so" ] \
        || die "fetch-rockbox-y1-y2-assets.sh did not populate org.rockbox.apk + librockbox.so"
    echo "==> Installing stock org.rockbox.apk + librockbox.so (Y1 from rockbox-y1 asset cache)"
    sudo cp "$cache/org.rockbox.apk" "$mount_sys/app/org.rockbox.apk"
    sudo cp "$cache/librockbox.so" "$mount_sys/lib/librockbox.so"
    sudo chmod 644 "$mount_sys/app/org.rockbox.apk" "$mount_sys/lib/librockbox.so"
    sudo chown root:root "$mount_sys/app/org.rockbox.apk" "$mount_sys/lib/librockbox.so"
}

install_rockbox_from_y1_base() {
    local mount_sys="$1"
    local cache patched staged_libs staged_rb
    chmod +x "$SCRIPT_DIR/fetch-rockbox-y1-y2-assets.sh" \
        "$SCRIPT_DIR/build-rockbox-solar.sh" \
        "$SCRIPT_DIR/fetch-rockbox-y1-source.sh" \
        "$SCRIPT_DIR/patch-rockbox-y2.sh" \
        "$SCRIPT_DIR/extract-rockbox-staged-assets.sh"
    cache="$("$SCRIPT_DIR/fetch-rockbox-y1-y2-assets.sh")"
    [ -f "$cache/org.rockbox.apk" ] && [ -f "$cache/librockbox.so" ] \
        || die "fetch-rockbox-y1-y2-assets.sh did not populate org.rockbox.apk + librockbox.so"
    patched="$WORK_DIR/org.rockbox-y2.apk"
    staged_libs="$WORK_DIR/rockbox-staged-libs"
    staged_rb="$WORK_DIR/rockbox-staged-dot-rockbox"
    echo "==> Y2 Rockbox — manifest-only resign + Xposed RockboxCompatHooks + staged lib patch"
    "$SCRIPT_DIR/build-rockbox-solar.sh" "$patched" y2
    "$SCRIPT_DIR/extract-rockbox-staged-assets.sh" "$patched" "$staged_libs" "$staged_rb"
    echo "==> Installing patched org.rockbox.apk + staged libs/.rockbox"
    sudo cp "$patched" "$mount_sys/app/org.rockbox.apk"
    sudo cp "$cache/librockbox.so" "$mount_sys/lib/librockbox.so"
    sudo mkdir -p "$mount_sys/etc/solar/rockbox-libs" "$mount_sys/etc/solar/rockbox-dot-rockbox"
    sudo cp -a "$staged_libs/." "$mount_sys/etc/solar/rockbox-libs/"
    sudo cp -a "$staged_rb/." "$mount_sys/etc/solar/rockbox-dot-rockbox/"
    sudo chmod 644 "$mount_sys/app/org.rockbox.apk" "$mount_sys/lib/librockbox.so"
    sudo chmod -R 755 "$mount_sys/etc/solar/rockbox-libs" "$mount_sys/etc/solar/rockbox-dot-rockbox"
    sudo chown -R root:root "$mount_sys/app/org.rockbox.apk" "$mount_sys/lib/librockbox.so" \
        "$mount_sys/etc/solar/rockbox-libs" "$mount_sys/etc/solar/rockbox-dot-rockbox"
    # #region agent log
    debug_rom_log "A" "build-rom.sh:install_rockbox" "Y2 Rockbox installed" \
        "patched_apk=$patched" "has_libmisc=$(unzip -l "$patched" 2>/dev/null | grep -c libmisc.so || echo 0)"
    # #endregion
}

# 2026-07-15 — ATA bases (Y1 A/B, Y2, A5) keep SP Flash Tool tree for download-and-flash UX.
# Was: zip -j ./* (flat firmware; dropped Flash Tool folders). Now: recursive pack minus build intermediates.
pack_ata_rom_zip() {
    local out="$1" listing
    rm -f rom.zip ./*.mount.raw ./*.repack.* ./*.verify.raw 2>/dev/null || true
    rm -rf pristine mount_sys mount_user 2>/dev/null || true
    zip -r -q "$out" . \
        -x 'rom.zip' -x '*.mount.raw' -x '*.repack.*' -x '*.verify.raw' \
        -x 'pristine/*' -x 'mount_sys/*' -x 'mount_user/*'
    # Avoid unzip|grep -q SIGPIPE under pipefail (false "missing Flash Tool").
    listing="$(unzip -l "$out")"
    printf '%s\n' "$listing" | grep -qiE 'flash_tool\.exe|FlashToolLib' \
        || die "$(basename "$out") missing SP Flash Tool (expected flash_tool.exe from ATA base)"
    printf '%s\n' "$listing" | grep -q 'system.img' \
        || die "$(basename "$out") missing system.img"
}

# 2026-07-16 — Firmware-only ATA Y2 bases (y2_ata_exdac_*) omit Windows SP Flash Tool.
# Merge toolkit from SOLAR_Y2_FLASH_TOOLS_ZIP or a prior full rom_y2.zip so local builds pass pack_ata.
ensure_y2_sp_flash_tools() {
    local dir="$1"
    local tools_zip candidate
    if [ -f "$dir/flash_tool.exe" ] || [ -f "$dir/FlashToolLib.dll" ]; then
        return 0
    fi
    tools_zip="${SOLAR_Y2_FLASH_TOOLS_ZIP:-}"
    if [ -z "$tools_zip" ] || [ ! -f "$tools_zip" ]; then
        for candidate in \
            "$REPO_ROOT/dist/rom_y2.zip" \
            "$REPO_ROOT/rom_y2.zip" \
            "$HOME/Downloads/rom_y2.zip" \
            "$HOME/Downloads/rom_y2 (3).zip"; do
            if [ -f "$candidate" ] && unzip -l "$candidate" 2>/dev/null | grep -qiE 'flash_tool\.exe|FlashToolLib'; then
                tools_zip="$candidate"
                break
            fi
        done
    fi
    [ -n "$tools_zip" ] && [ -f "$tools_zip" ] \
        || die "Y2 base has no SP Flash Tool; set SOLAR_Y2_FLASH_TOOLS_ZIP to a full rom_y2.zip that includes flash_tool.exe"
    echo "==> Injecting SP Flash Tool tree from $tools_zip (firmware-only ATA base)"
    # Extract only toolkit paths — never overwrite system.img / userdata.img / stock scatter.
    unzip -q -o "$tools_zip" \
        'flash_tool.exe' 'FlashToolLib*' 'Flashtoollib*' 'DA_*.bin' 'MTK_AllInOne_DA.bin' \
        'Qt*.dll' 'msvc*.dll' 'phonon4.dll' 'SLA_Challenge.dll' \
        'codecs/*' 'imageformats/*' 'sqldrivers/*' 'Driver/*' \
        'assistant.exe' 'flashtool.qch' 'flashtool.qhc' \
        '*.ini' '*.xml' '*.xsd' 'Credits.txt' \
        -d "$dir" 2>/dev/null || true
    # Fallback: extract all then restore our critical images if partial unzip skipped globs.
    if [ ! -f "$dir/flash_tool.exe" ]; then
        local tmp
        tmp=$(mktemp -d)
        unzip -q -o "$tools_zip" -d "$tmp"
        # Copy tool files not present; never clobber baked firmware images.
        (
            cd "$tmp"
            find . -type f ! -name 'system.img' ! -name 'userdata.img' ! -name 'boot.img' \
                ! -name 'recovery.img' ! -name 'cache.img' ! -name 'secro.img' \
                ! -name 'MT6582_Android_scatter.txt' ! -name 'logo.bin' ! -name 'EBR2' \
                ! -name 'original_system.img' -print0
        ) | while IFS= read -r -d '' rel; do
            rel="${rel#./}"
            [ -n "$rel" ] || continue
            mkdir -p "$dir/$(dirname "$rel")"
            if [ ! -f "$dir/$rel" ]; then
                cp -a "$tmp/$rel" "$dir/$rel"
            fi
        done
        rm -rf "$tmp"
    fi
    [ -f "$dir/flash_tool.exe" ] \
        || die "failed to inject flash_tool.exe from $tools_zip"
}

case "$TYPE" in
    a)
        # 2026-07-15 — Y1 type A ATA (SP Flash Tool + firmware) from y1-community; was rockbox-y1 type-a-base.
        BASE_URL="https://github.com/y1-community/y1-ata-rom/releases/download/0.1/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom.zip}"
        SCATTER_FILE="MT6572_Android_scatter.txt"
        ;;
    b)
        # 2026-07-15 — Y1 type B ATA; was rockbox-y1 type-b-base/rom.zip.
        BASE_URL="https://github.com/y1-community/y1-ata-rom/releases/download/0.1/rom_type_b.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_type_b.zip}"
        SCATTER_FILE="MT6572_Android_scatter.txt"
        ;;
    y2)
        # 2026-07-19 — Default Y2 base is jj_auto y2-test (Rockbox/JJ stripped in pack).
        # Was: y1-community/y2-ata-rom …/y2-ata/rom_y2.zip. Override: SOLAR_Y2_BASE_ZIP.
        # Reversal: restore y1-community URL below.
        BASE_URL="https://github.com/ryan-specter/jj_auto/releases/download/y2-test/rom_y2.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_y2.zip}"
        SCATTER_FILE="MT6582_Android_scatter.txt"
        # Fail fast before downloading Y2 base if vendored Y1 su is missing.
        [ -f "$REPO_ROOT/solar-rom/vendor/y1-su/su" ] \
            || die "missing solar-rom/vendor/y1-su/su — run solar-rom/scripts/extract-y1-su-vendor.sh"
        [ -f "$REPO_ROOT/solar-rom/vendor/y2-flash/EBR2" ] \
            || die "missing solar-rom/vendor/y2-flash/EBR2 (SP Flash index 14 / MTK Y2 flash bundle)"
        ;;
    a5)
        # 2026-07-15 — Timmkoo A5 ATA (MT6572 / API 17). Same SoC family as Y1, not Y2.
        # Base ships firmware + Windows SP Flash Tool — keep both in rom_a5.zip for download-and-flash.
        # Override: SOLAR_A5_BASE_ZIP or SOLAR_ROM_BASE_ZIP until y1-community/a5-ata-rom is public.
        BASE_URL="https://github.com/y1-community/a5-ata-rom/releases/download/0.1/rom_a5.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_a5.zip}"
        SCATTER_FILE="MT6572_Android_scatter.txt"
        [ -f "$REPO_ROOT/solar-rom/vendor/y1-su/su" ] \
            || die "missing solar-rom/vendor/y1-su/su — run solar-rom/scripts/extract-y1-su-vendor.sh"
        [ -f "$SCRIPT_DIR/A5-mtk.kl" ] && [ -f "$SCRIPT_DIR/A5.kl" ] \
            || die "missing A5-mtk.kl / A5.kl in $SCRIPT_DIR (sync from app assets)"
        ;;
    *)
        die "unknown type: $TYPE"
        ;;
esac

# Resolve OUTPUT to absolute path before cd into WORK_DIR (relative paths would land in tmpdir).
OUTPUT="$(realpath -m "$OUTPUT" 2>/dev/null || echo "$(pwd)/$(basename "$OUTPUT")")"

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

SOLAR_SYS="$REPO_ROOT/solar-rom/system"

install_solar_boot_assets() {
    local base_dir="$1"
    local sys_mount="$2"

    # 2026-07-16 — A5: never touch logo.bin, boot.img, or bootanimation.
    # Y1 Solar logo/boot assets can brick or brick-loop g368_nyx (wrong panel timing / kernel).
    if [ "$TYPE" = "a5" ]; then
        echo "==> A5: keeping stock boot assets (logo.bin, boot.img, bootanimation) — no Solar overlays"
        return 0
    fi

    if [ -f "$SOLAR_SYS/media/bootanimation.zip" ]; then
        echo "==> Install Solar boot animation (system/media/bootanimation.zip)"
        sudo mkdir -p "$sys_mount/media"
        sudo cp "$SOLAR_SYS/media/bootanimation.zip" "$sys_mount/media/bootanimation.zip"
        sudo chmod 644 "$sys_mount/media/bootanimation.zip"
        sudo chown root:root "$sys_mount/media/bootanimation.zip"
    else
        die "missing $SOLAR_SYS/media/bootanimation.zip"
    fi

    if [ -f "$SOLAR_SYS/bin/bootanimation" ]; then
        echo "==> Install Solar bootanimation binary (system/bin/bootanimation)"
        sudo mkdir -p "$sys_mount/bin"
        sudo cp "$SOLAR_SYS/bin/bootanimation" "$sys_mount/bin/bootanimation"
        sudo chmod 755 "$sys_mount/bin/bootanimation"
        sudo chown root:root "$sys_mount/bin/bootanimation"
    else
        die "missing $SOLAR_SYS/bin/bootanimation"
    fi

    if [ -f "$SOLAR_SYS/boot.img" ]; then
        # Y2 ATA = MT6582 stock kernel — never overlay Y1 rockbox boot.img.
        # Only Y1 type a/b get Solar boot.img.
        if [ "$TYPE" = "y2" ]; then
            echo "==> Keeping y2 stock boot.img (Solar boot animation is on /system)"
        else
            echo "==> Replace boot.img in ROM archive"
            cp "$SOLAR_SYS/boot.img" "$base_dir/boot.img"
        fi
    else
        die "missing $SOLAR_SYS/boot.img"
    fi

    if [ -f "$SOLAR_SYS/logo.bin" ]; then
        echo "==> Replace logo.bin in ROM archive"
        cp "$SOLAR_SYS/logo.bin" "$base_dir/logo.bin"
    else
        die "missing $SOLAR_SYS/logo.bin"
    fi
}

# ponytail: stock Y2 ships zh-CN — force English default for Solar UI and stock apps.
apply_english_build_prop() {
    local sys_mount="$1"
    local prop="$sys_mount/build.prop"
    [ -f "$prop" ] || { echo "warn: missing $prop (locale not patched)" >&2; return 0; }
    echo "==> Set default locale to English (build.prop)"
    sudo sed -i 's/^ro\.product\.locale\.language=.*/ro.product.locale.language=en/' "$prop"
    sudo sed -i 's/^ro\.product\.locale\.region=.*/ro.product.locale.region=US/' "$prop"
    sudo sed -i 's/^ro\.product\.locale=.*/ro.product.locale=en-US/' "$prop"
    sudo sed -i 's/^persist\.sys\.language=.*/persist.sys.language=en/' "$prop"
    sudo sed -i 's/^persist\.sys\.country=.*/persist.sys.country=US/' "$prop"
    sudo grep -q '^ro\.product\.locale\.language=' "$prop" \
        || echo 'ro.product.locale.language=en' | sudo tee -a "$prop" >/dev/null
    sudo grep -q '^ro\.product\.locale\.region=' "$prop" \
        || echo 'ro.product.locale.region=US' | sudo tee -a "$prop" >/dev/null
    sudo grep -q '^ro\.product\.locale=' "$prop" \
        || echo 'ro.product.locale=en-US' | sudo tee -a "$prop" >/dev/null
    sudo grep -q '^persist\.sys\.language=' "$prop" \
        || echo 'persist.sys.language=en' | sudo tee -a "$prop" >/dev/null
    sudo grep -q '^persist\.sys\.country=' "$prop" \
        || echo 'persist.sys.country=US' | sudo tee -a "$prop" >/dev/null
}

# 2026-07-15 — Stock A5 ATA reports model=Y1 → DeviceFeatures would pick y1 without this.
# Layman: stamp the ROM so Solar knows this is an A5, not a wheel Y1.
# Tech: build.prop identity + persist.solar.device_family=a5 (99SolarInit re-sets on boot).
# Reversal: drop pins; A5 APK falls back to Y1 keymap/layout until adb push-a5-keymap.
apply_a5_device_identity() {
    local sys_mount="$1"
    local prop="$sys_mount/build.prop"
    [ -f "$prop" ] || { echo "warn: missing $prop (A5 identity not patched)" >&2; return 0; }
    echo "==> Pin A5 device family (build.prop model/manufacturer + persist.solar.device_family)"
    if sudo grep -q '^ro\.product\.model=' "$prop"; then
        sudo sed -i 's/^ro\.product\.model=.*/ro.product.model=A5/' "$prop"
    else
        echo 'ro.product.model=A5' | sudo tee -a "$prop" >/dev/null
    fi
    if sudo grep -q '^ro\.product\.manufacturer=' "$prop"; then
        sudo sed -i 's/^ro\.product\.manufacturer=.*/ro.product.manufacturer=Timmkoo/' "$prop"
    else
        echo 'ro.product.manufacturer=Timmkoo' | sudo tee -a "$prop" >/dev/null
    fi
    if sudo grep -q '^persist\.solar\.device_family=' "$prop"; then
        sudo sed -i 's/^persist\.solar\.device_family=.*/persist.solar.device_family=a5/' "$prop"
    else
        echo 'persist.solar.device_family=a5' | sudo tee -a "$prop" >/dev/null
    fi
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

    if [ "$TYPE" = "y2" ]; then
        for required in MBR EBR1 EBR2 preloader_eastaeon82_wet_kk.bin; do
            if [ ! -f "$base_dir/$required" ]; then
                echo "audit fail: missing $required in ROM archive (MTK/SP Flash Y2 bundle)" >&2
                errors=$((errors + 1))
            fi
        done
    fi
    # 2026-07-15 — A5 ATA flash members (g368_nyx preloader); drop Windows tool junk via pack step.
    if [ "$TYPE" = "a5" ]; then
        for required in MBR EBR1 EBR2 preloader_g368_nyx.bin; do
            if [ ! -f "$base_dir/$required" ]; then
                echo "audit fail: missing $required in ROM archive (MTK/SP Flash A5 bundle)" >&2
                errors=$((errors + 1))
            fi
        done
    fi

    if find "$sys_mount/app" "$sys_mount/priv-app" -iname '*innioasis*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock launcher APK still present under /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ "$TYPE" = "y2" ] && find "$sys_mount/priv-app" -iname '*factorylauncher*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock Y2 factory launcher still present in /system/priv-app" >&2
        errors=$((errors + 1))
    fi

    if [ "$TYPE" = "y2" ]; then
        boot_bytes=$(stat -c%s "$base_dir/boot.img" 2>/dev/null || echo 0)
        # ponytail: Y1 boot.img is ~4.7 MiB; Y2 MT6582 stock is ~5.5 MiB — wrong kernel bricks SP Flash writes.
        if [ "$boot_bytes" -lt 5700000 ] || [ "$boot_bytes" -gt 5900000 ]; then
            echo "audit fail: Y2 boot.img is ${boot_bytes} bytes (expected MT6582 stock ~5773312)" >&2
            errors=$((errors + 1))
        fi
    fi

    # Y2/A5 ATA bases get Y1 permissive su baked in; Y1 rockbox base already ships /system/xbin/su.
    if [ "$TYPE" = "y2" ] || [ "$TYPE" = "a5" ]; then
        if [ ! -x "$sys_mount/xbin/su" ]; then
            echo "audit fail: /system/xbin/su missing (${TYPE} permissive root)" >&2
            errors=$((errors + 1))
        fi
        if [ ! -x "$sys_mount/xbin/daemonsu" ]; then
            echo "audit fail: /system/xbin/daemonsu missing (${TYPE} permissive root)" >&2
            errors=$((errors + 1))
        fi
        if [ ! -x "$sys_mount/bin/.ext/.su" ]; then
            echo "audit fail: /system/bin/.ext/.su missing (${TYPE} permissive root)" >&2
            errors=$((errors + 1))
        fi
        if [ ! -u "$sys_mount/xbin/su" ]; then
            echo "audit fail: /system/xbin/su not setuid (${TYPE} permissive root)" >&2
            errors=$((errors + 1))
        fi
        if [ ! -x "$sys_mount/etc/init.d/99SuperSUDaemon" ]; then
            echo "audit fail: /system/etc/init.d/99SuperSUDaemon missing (SuperSU daemon boot)" >&2
            errors=$((errors + 1))
        fi
        # Superuser.apk would re-enable grant prompts — Y1-style root omits it on purpose.
        if [ -f "$sys_mount/app/Superuser.apk" ]; then
            echo "audit fail: /system/app/Superuser.apk present (${TYPE} should use permissive su only)" >&2
            errors=$((errors + 1))
        fi
    fi
    # 2026-07-19 — solar-rb-launch retired (no Rockbox bake on any TYPE).
    if [ -x "$sys_mount/xbin/solar-rb-launch" ]; then
        echo "audit fail: /system/xbin/solar-rb-launch must not ship (Solar-only)" >&2
        errors=$((errors + 1))
    fi
    if [ "$TYPE" = "a5" ]; then
        if ! grep -q '^persist\.solar\.device_family=a5' "$sys_mount/build.prop" 2>/dev/null; then
            echo "audit fail: build.prop missing persist.solar.device_family=a5" >&2
            errors=$((errors + 1))
        fi
        if ! grep -qE '^ro\.product\.model=A5' "$sys_mount/build.prop" 2>/dev/null; then
            echo "audit fail: build.prop ro.product.model must be A5" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ ! -f "$sys_mount/app/$SYSTEM_APK_NAME" ]; then
        echo "audit fail: $SYSTEM_APK_NAME missing from /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/app/SolarHomeHelper.apk" ]; then
        echo "audit fail: SolarHomeHelper.apk missing from /system/app (HOME middle-man)" >&2
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
    elif ! grep -q 'apply-preferred-home-boot.sh' "$sys_mount/etc/init.d/99SolarInit.sh" 2>/dev/null; then
        echo "audit fail: 99SolarInit must run apply-preferred-home-boot.sh on boot" >&2
        errors=$((errors + 1))
    elif ! grep -q 'disable-large-font-accessibility.sh' "$sys_mount/etc/init.d/99SolarInit.sh" 2>/dev/null; then
        echo "audit fail: 99SolarInit must reset large-font accessibility on boot" >&2
        errors=$((errors + 1))
    elif ! grep -q 'enable-gpu-performance.sh' "$sys_mount/etc/init.d/99SolarInit.sh" 2>/dev/null; then
        echo "audit fail: 99SolarInit must enable GPU rendering + disable HW overlays on boot" >&2
        errors=$((errors + 1))
    elif ! grep -q 'SolarInputMethodService' "$sys_mount/etc/init.d/99SolarInit.sh" 2>/dev/null; then
        echo "audit fail: 99SolarInit must set default SolarInputMethodService" >&2
        errors=$((errors + 1))
    fi

    # Solar install-recovery runs init.d + early Xposed app_process apply on all ROM variants.
    if [ ! -x "$sys_mount/etc/install-recovery.sh" ]; then
        echo "audit fail: /system/etc/install-recovery.sh missing (init.rc flash_recovery boot chain)" >&2
        errors=$((errors + 1))
    elif ! grep -q 'app_process.xposed.staged' "$sys_mount/etc/install-recovery.sh" 2>/dev/null; then
        echo "audit fail: install-recovery.sh missing early Xposed app_process apply" >&2
        errors=$((errors + 1))
    fi
    if [ ! -x "$sys_mount/etc/install-recovery-2.sh" ]; then
        echo "audit fail: /system/etc/install-recovery-2.sh missing (Solar init.d boot hooks)" >&2
        errors=$((errors + 1))
    fi
    if [ ! -x "$sys_mount/etc/init.d/99SuperSUDaemon" ]; then
        echo "audit fail: /system/etc/init.d/99SuperSUDaemon missing (daemonsu boot)" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$sys_mount/etc/init.d/99Y1LauncherInit.sh" ]; then
        echo "audit fail: legacy 99Y1LauncherInit.sh still present" >&2
        errors=$((errors + 1))
    fi

    # 2026-07-19 — No ROM bakes org.rockbox (Solar-only). Was: Y1 required; Y2 LEGACY optional.
    rockbox_on_rom=0
    echo "audit note: org.rockbox omitted on all Solar ROM packs (launch-if-present only)" >&2
    if [ -f "$sys_mount/app/org.rockbox.apk" ] || [ -f "$sys_mount/lib/librockbox.so" ]; then
        echo "audit fail: org.rockbox must not ship on Solar ROM (TYPE=$TYPE)" >&2
        errors=$((errors + 1))
    fi
    if [ -f "$sys_mount/app/com.themoon.y1.apk" ] || [ -f "$sys_mount/priv-app/com.themoon.y1.apk" ]; then
        echo "audit fail: com.themoon.y1 (JJ) must not ship on Solar ROM" >&2
        errors=$((errors + 1))
    fi
    if [ -f "$sys_mount/xbin/solar-rb-launch" ]; then
        echo "audit fail: solar-rb-launch must not ship (no Rockbox bake)" >&2
        errors=$((errors + 1))
    fi

    # FM radio — warn when mtk FM stack is absent (Solar FM browse still opens; tune may fail).
    if ! find "$sys_mount/lib" -maxdepth 1 -name 'libfm*.so' 2>/dev/null | grep -q . \
            && ! find "$sys_mount" \( -iname '*mediatek*fm*' -o -iname '*FMRadio*' \) 2>/dev/null | grep -q .; then
        echo "audit warn: no libfm* or MediaTek FM package found on /system (hardware FM may be unavailable)" >&2
    fi
    if [ ! -f "$sys_mount/lib/libfmjni.so" ]; then
        echo "audit fail: /system/lib/libfmjni.so missing (MediaTek FM JNI for FMRadio.apk)" >&2
        errors=$((errors + 1))
    fi
    if [ ! -f "$sys_mount/app/FMRadio.apk" ] && [ ! -f "$sys_mount/priv-app/FMRadio.apk" ]; then
        echo "audit fail: FMRadio.apk stripped (Solar FM binds com.mediatek.FMRadio service)" >&2
        errors=$((errors + 1))
    fi

    # 2026-07-05 — Every surviving APK basename must be on curated allowlist (debloat regression guard).
    allowlist="$REPO_ROOT/solar-rom/config/system-app-allowlist.txt"
    if [ ! -f "$allowlist" ]; then
        echo "audit fail: missing $allowlist" >&2
        errors=$((errors + 1))
    else
        declare -A _audit_keep=()
        while IFS= read -r _line || [ -n "$_line" ]; do
            _line="${_line%%#*}"
            _line="$(echo "$_line" | tr -d '[:space:]')"
            [ -n "$_line" ] || continue
            _audit_keep["$_line"]=1
        done < "$allowlist"
        for _apkdir in app priv-app; do
            [ -d "$sys_mount/$_apkdir" ] || continue
            for _apk in "$sys_mount/$_apkdir"/*.apk; do
                [ -e "$_apk" ] || continue
                _base="$(basename "$_apk")"
                if [ -z "${_audit_keep[$_base]+x}" ]; then
                    echo "audit fail: non-allowlisted APK /system/$_apkdir/$_base" >&2
                    errors=$((errors + 1))
                fi
            done
        done
        if [ ! -f "$sys_mount/app/LatinIME.apk" ]; then
            echo "audit fail: LatinIME.apk missing (future Solar IME fallback)" >&2
            errors=$((errors + 1))
        fi
    fi

    # ponytail: codec plugins ship inside org.rockbox.apk (lib/armeabi/*.so) — must survive ROM build.
    if [ "$rockbox_on_rom" = "1" ] && [ -f "$sys_mount/app/org.rockbox.apk" ]; then
        rb_so_count=$(unzip -l "$sys_mount/app/org.rockbox.apk" 2>/dev/null \
            | grep -c 'lib/armeabi/.*\.so' || true)
        if [ "${rb_so_count:-0}" -lt 35 ]; then
            echo "audit fail: org.rockbox.apk has ${rb_so_count:-0} native libs (expected >=35 from rockbox-y1 base)" >&2
            errors=$((errors + 1))
        fi
        # grep -q closes the pipe early; with pipefail unzip gets SIGPIPE and falsely fails — use -c.
        rb_misc_count=$(unzip -l "$sys_mount/app/org.rockbox.apk" 2>/dev/null \
            | grep -c 'lib/armeabi/libmisc.so' || true)
        if [ "${rb_misc_count:-0}" -lt 1 ]; then
            echo "audit fail: org.rockbox.apk missing lib/armeabi/libmisc.so (asset bootstrap)" >&2
            errors=$((errors + 1))
        fi
        # Y2: unpatched rockbox-y1 sharedUserId blocks PM install on MT6582 — mandatory in CI.
        if [ "$TYPE" = "y2" ]; then
            # shellcheck source=lib-android-tools.sh
            source "$SCRIPT_DIR/lib-android-tools.sh"
            AAPT="$(find_android_build_tool aapt)" || {
                echo "audit fail: aapt required for Y2 org.rockbox.apk sharedUserId audit (set ANDROID_HOME)" >&2
                errors=$((errors + 1))
                AAPT=""
            }
            if [ -n "$AAPT" ] && "$AAPT" dump xmltree "$sys_mount/app/org.rockbox.apk" AndroidManifest.xml 2>/dev/null \
                    | grep -q 'sharedUserId'; then
                echo "audit fail: org.rockbox.apk still has sharedUserId (run patch-rockbox-y2.sh)" >&2
                errors=$((errors + 1))
            fi
        fi
    fi

    if [ ! -f "$sys_mount/etc/solar/sync-rockbox-assets.sh" ]; then
        echo "audit fail: /system/etc/solar/sync-rockbox-assets.sh missing (Rockbox plugin sync)" >&2
        errors=$((errors + 1))
    fi

    if [ "$TYPE" = "y2" ] && [ "$rockbox_on_rom" = "1" ]; then
        if [ ! -f "$sys_mount/etc/solar/rockbox-y2-config.cfg" ]; then
            echo "audit fail: /system/etc/solar/rockbox-y2-config.cfg missing (Y2 dual-storage)" >&2
            errors=$((errors + 1))
        fi
        if [ ! -f "$sys_mount/etc/solar/rockbox-dot-rockbox/rocks/viewers/db_folder_select.rock" ]; then
            echo "audit fail: staged rockbox-dot-rockbox missing db_folder_select.rock" >&2
            errors=$((errors + 1))
        fi
        if [ ! -f "$sys_mount/etc/solar/rockbox-libs/librockbox.so" ]; then
            echo "audit fail: staged rockbox-libs missing librockbox.so" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ ! -f "$sys_mount/etc/solar/apply-preferred-home-boot.sh" ]; then
        echo "audit fail: /system/etc/solar/apply-preferred-home-boot.sh missing" >&2
        errors=$((errors + 1))
    elif ! grep -q 'persist.solar.home.target' "$sys_mount/etc/solar/apply-preferred-home-boot.sh" 2>/dev/null; then
        echo "audit fail: apply-preferred-home-boot.sh must read persist.solar.home.target" >&2
        errors=$((errors + 1))
    elif ! grep -q 'SET_PREFERRED_HOME' "$sys_mount/etc/solar/apply-preferred-home-boot.sh" 2>/dev/null; then
        echo "audit fail: apply-preferred-home-boot.sh must broadcast preferred HOME" >&2
        errors=$((errors + 1))
    fi
    if [ ! -f "$sys_mount/etc/solar/disable-large-font-accessibility.sh" ]; then
        echo "audit fail: /system/etc/solar/disable-large-font-accessibility.sh missing" >&2
        errors=$((errors + 1))
    elif ! grep -q 'font_scale' "$sys_mount/etc/solar/disable-large-font-accessibility.sh" 2>/dev/null; then
        echo "audit fail: disable-large-font-accessibility.sh must reset font_scale" >&2
        errors=$((errors + 1))
    fi
    if [ ! -f "$sys_mount/etc/solar/enable-gpu-performance.sh" ]; then
        echo "audit fail: /system/etc/solar/enable-gpu-performance.sh missing" >&2
        errors=$((errors + 1))
    elif ! grep -q 'force_gpu_rendering' "$sys_mount/etc/solar/enable-gpu-performance.sh" 2>/dev/null; then
        echo "audit fail: enable-gpu-performance.sh must set force_gpu_rendering" >&2
        errors=$((errors + 1))
    elif ! grep -q 'SurfaceFlinger' "$sys_mount/etc/solar/enable-gpu-performance.sh" 2>/dev/null; then
        echo "audit fail: enable-gpu-performance.sh must disable HW overlays via SurfaceFlinger" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/solar/disable-rockbox-for-solar.sh" ]; then
        echo "audit fail: /system/etc/solar/disable-rockbox-for-solar.sh missing (legacy forwarder)" >&2
        errors=$((errors + 1))
    fi

    # 2026-07-19 — switch-to-rockbox no longer packed; keep switch-to-stock for Solar HOME.
    if [ -f "$sys_mount/etc/solar/switch-to-rockbox.sh" ]; then
        echo "audit fail: switch-to-rockbox.sh must not ship (Solar-only HOME)" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/solar/switch-to-stock.sh" ]; then
        echo "audit fail: /system/etc/solar/switch-to-stock.sh missing" >&2
        errors=$((errors + 1))
    elif grep -qiE '(^|[[:space:]]|/)reboot\b|reboot -p|/system/bin/reboot' \
            "$sys_mount/etc/solar/switch-to-stock.sh" 2>/dev/null; then
        echo "audit fail: switch-to-stock.sh must not reboot (unified keymap)" >&2
        errors=$((errors + 1))
    # 2026-07-08 — Prefer solar-launcher-exec delegate; legacy inline uses apply_competition.
    # Was: require set_preferred_home symbol (removed when helper HOME enforcement landed).
    elif ! grep -qE 'solar-launcher-exec\.sh|apply_competition|set_preferred_home' \
            "$sys_mount/etc/solar/switch-to-stock.sh" 2>/dev/null; then
        echo "audit fail: switch-to-stock.sh must delegate to solar-launcher-exec or apply HOME" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/solar/sync-rockbox-libs.sh" ]; then
        echo "audit fail: /system/etc/solar/sync-rockbox-libs.sh missing (Rockbox codec sync)" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/solar/sync-y1-keymap.sh" ]; then
        echo "audit fail: /system/etc/solar/sync-y1-keymap.sh missing (unified keymap sync)" >&2
        errors=$((errors + 1))
    elif [ "$TYPE" = "a5" ]; then
        if [ ! -f "$sys_mount/etc/solar/A5-mtk.kl" ] || [ ! -f "$sys_mount/etc/solar/A5.kl" ]; then
            echo "audit fail: /system/etc/solar/A5-mtk.kl or A5.kl missing" >&2
            errors=$((errors + 1))
        fi
    elif [ ! -f "$sys_mount/etc/solar/Y1-Rockbox.kl" ] \
            && [ ! -f "$sys_mount/etc/solar/Y2-Rockbox.kl" ]; then
        echo "audit fail: /system/etc/solar/Y1-Rockbox.kl or Y2-Rockbox.kl missing" >&2
        errors=$((errors + 1))
    fi

    if [ "$TYPE" = "a5" ]; then
        # 2026-07-15 — A5 face DPAD + volume; never install Y1-Rockbox.kl as Generic.
        mtk_kl="$sys_mount/etc/solar/A5-mtk.kl"
        gen_kl="$sys_mount/etc/solar/A5.kl"
        if [ ! -f "$sys_mount/usr/keylayout/Generic.kl" ] || [ ! -f "$sys_mount/usr/keylayout/mtk-kpd.kl" ]; then
            echo "audit fail: A5 keylayout files missing (Generic.kl / mtk-kpd.kl)" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/Generic.kl" "$gen_kl"; then
            echo "audit fail: Generic.kl must match A5.kl" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/mtk-kpd.kl" "$mtk_kl"; then
            echo "audit fail: mtk-kpd.kl must match A5-mtk.kl" >&2
            errors=$((errors + 1))
        elif [ -f "$sys_mount/usr/keylayout/A5.kl" ] && ! cmp -s "$sys_mount/usr/keylayout/A5.kl" "$gen_kl"; then
            echo "audit fail: usr/keylayout/A5.kl must match etc/solar/A5.kl" >&2
            errors=$((errors + 1))
        elif [ -f "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" ] \
                && ! cmp -s "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" "$mtk_kl"; then
            echo "audit fail: mtk-tpd-kpd.kl must match A5-mtk.kl" >&2
            errors=$((errors + 1))
        elif ! grep -qE '^key[[:space:]]+116[[:space:]]+MEDIA_STOP' "$mtk_kl" 2>/dev/null; then
            echo "audit fail: A5-mtk.kl missing MEDIA_STOP on power scancode 116" >&2
            errors=$((errors + 1))
        elif ! grep -qE '^key[[:space:]]+114[[:space:]]+VOLUME_DOWN' "$mtk_kl" 2>/dev/null; then
            echo "audit fail: A5-mtk.kl missing VOLUME_DOWN on scancode 114" >&2
            errors=$((errors + 1))
        elif [ -f "$sys_mount/usr/keylayout/Y1-Rockbox.kl" ] || [ -f "$sys_mount/etc/solar/Y1-Rockbox.kl" ]; then
            echo "audit fail: Y1-Rockbox.kl must not ship on A5 (would steal A5 keys on sync)" >&2
            errors=$((errors + 1))
        fi
    else
        if [ -f "$sys_mount/etc/solar/Y2-Rockbox.kl" ]; then
            canon_kl="$sys_mount/etc/solar/Y2-Rockbox.kl"
            canon_name="Y2-Rockbox.kl"
        else
            canon_kl="$sys_mount/etc/solar/Y1-Rockbox.kl"
            canon_name="Y1-Rockbox.kl"
        fi

        if [ ! -f "$sys_mount/usr/keylayout/Generic.kl" ] || [ ! -f "$sys_mount/usr/keylayout/Rockbox.kl" ]; then
            echo "audit fail: keylayout files missing" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/Generic.kl" "$canon_kl"; then
            echo "audit fail: Generic.kl is not identical to $canon_name (wheel 126/127)" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/Stock.kl" "$canon_kl"; then
            echo "audit fail: Stock.kl must match $canon_name (unified keymap)" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/Rockbox.kl" "$canon_kl"; then
            echo "audit fail: Rockbox.kl must match $canon_name (unified keymap)" >&2
            errors=$((errors + 1))
        elif [ ! -f "$sys_mount/usr/keylayout/$canon_name" ]; then
            echo "audit fail: /system/usr/keylayout/$canon_name missing" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/$canon_name" "$canon_kl"; then
            echo "audit fail: usr/keylayout/$canon_name must match etc/solar copy" >&2
            errors=$((errors + 1))
        elif [ -f "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" ] && [ -f "$sys_mount/usr/keylayout/mtk-kpd.kl" ] \
                && ! cmp -s "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" "$sys_mount/usr/keylayout/mtk-kpd.kl"; then
            echo "audit fail: mtk-tpd-kpd.kl must match mtk-kpd.kl" >&2
            errors=$((errors + 1))
        elif [ "$TYPE" = "y2" ] && [ -f "$sys_mount/usr/keylayout/mtk-kpd.kl" ] \
                && ! cmp -s "$sys_mount/usr/keylayout/mtk-kpd.kl" "$canon_kl"; then
            echo "audit fail: Y2 mtk-kpd.kl must match $canon_name (same playbook as Generic/Stock/Rockbox)" >&2
            errors=$((errors + 1))
        elif [ "$TYPE" = "y2" ] && [ -f "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" ] \
                && ! cmp -s "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl" "$canon_kl"; then
            echo "audit fail: Y2 mtk-tpd-kpd.kl must match $canon_name (wheel device on Y2)" >&2
            errors=$((errors + 1))
        fi

        # Y2 GPIO map: 103/108 wheel, 105/106 side — reject accidental Y1 wheel lines on 105/106.
        if [ "$TYPE" = "y2" ] && [ -f "$canon_kl" ]; then
            if grep -qE '^key[[:space:]]+105[[:space:]]+MEDIA_PLAY' "$canon_kl" 2>/dev/null; then
                echo "audit fail: Y2 $canon_name has Y1 wheel map on scancode 105 (expected MEDIA_PREVIOUS)" >&2
                errors=$((errors + 1))
            fi
            if ! grep -qE '^key[[:space:]]+105[[:space:]]+MEDIA_PREVIOUS' "$canon_kl" 2>/dev/null; then
                echo "audit fail: Y2 $canon_name missing MEDIA_PREVIOUS on scancode 105" >&2
                errors=$((errors + 1))
            fi
        fi
    fi

    if [ "$TYPE" = "a5" ]; then
        if [ -f "$sys_mount/etc/init.d/99Y1ButtonScript" ]; then
            echo "audit fail: 99Y1ButtonScript must not ship on A5 (Y1 wheel gesture)" >&2
            errors=$((errors + 1))
        fi
    elif [ ! -f "$sys_mount/etc/init.d/99Y1ButtonScript" ]; then
        echo "audit fail: 99Y1ButtonScript missing (Back+Play Rockbox gesture)" >&2
        errors=$((errors + 1))
    fi

    # A5 keeps stock bootanimation/logo — do not require Solar boot assets.
    if [ "$TYPE" != "a5" ]; then
        if [ ! -f "$sys_mount/media/bootanimation.zip" ]; then
            echo "audit fail: /system/media/bootanimation.zip missing" >&2
            errors=$((errors + 1))
        fi
        if [ ! -f "$sys_mount/bin/bootanimation" ]; then
            echo "audit fail: /system/bin/bootanimation missing" >&2
            errors=$((errors + 1))
        fi
    else
        echo "audit note: A5 keeps stock logo.bin / boot.img / bootanimation (no Solar overlay)"
    fi

    if [ ! -f "$base_dir/boot.img" ]; then
        echo "audit fail: boot.img missing from ROM archive" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$base_dir/logo.bin" ]; then
        echo "audit fail: logo.bin missing from ROM archive" >&2
        errors=$((errors + 1))
    fi

    # Xposed Dalvik framework (all Solar ROM variants).
    for xp in \
        /bin/app_process.orig \
        /framework/XposedBridge.jar \
        /etc/solar/XposedBridge.jar \
        /xposed.prop \
        /app/XposedInstaller.apk \
        /etc/init.d/99XposedInit.sh; do
        if [ ! -f "$sys_mount$xp" ]; then
            echo "audit fail: Xposed path missing: $xp" >&2
            errors=$((errors + 1))
        fi
    done
    if [ -f "$sys_mount/bin/app_process" ] && [ -f "$sys_mount/bin/app_process.orig" ] \
            && cmp -s "$sys_mount/bin/app_process" "$sys_mount/bin/app_process.orig"; then
        echo "audit fail: app_process identical to app_process.orig (Xposed not installed)" >&2
        errors=$((errors + 1))
    fi
    if [ -f "$sys_mount/bin/app_process" ] && ! strings "$sys_mount/bin/app_process" 2>/dev/null | grep -q 'with Xposed support'; then
        echo "audit fail: app_process missing Xposed support string" >&2
        errors=$((errors + 1))
    fi
    if [ "$TYPE" = "y2" ]; then
        [ -f "$sys_mount/app/SolarContextBridgeY2.apk" ] || { echo "audit fail: SolarContextBridgeY2.apk missing" >&2; errors=$((errors + 1)); }
    else
        [ -f "$sys_mount/app/SolarContextBridgeY1.apk" ] || { echo "audit fail: SolarContextBridgeY1.apk missing" >&2; errors=$((errors + 1)); }
    fi
    [ -f "$sys_mount/app/SolarThemeFont.apk" ] || { echo "audit fail: SolarThemeFont.apk missing" >&2; errors=$((errors + 1)); }
    [ -f "$sys_mount/app/SolarRockboxIme.apk" ] || { echo "audit fail: SolarRockboxIme.apk missing" >&2; errors=$((errors + 1)); }
    [ -f "$sys_mount/app/SolarNotPipeBridge.apk" ] || { echo "audit fail: SolarNotPipeBridge.apk missing" >&2; errors=$((errors + 1)); }
    [ -f "$sys_mount/app/io.github.gohoski.notpipe.apk" ] || { echo "audit fail: io.github.gohoski.notpipe.apk missing" >&2; errors=$((errors + 1)); }
    if [ "$TYPE" = "y2" ]; then
        [ -f "$sys_mount/app/SolarRockboxCompat.apk" ] || { echo "audit fail: SolarRockboxCompat.apk missing" >&2; errors=$((errors + 1)); }
    fi
    if [ -f "$sys_mount/etc/init.d/99XposedInit.sh" ]; then
        if ! grep -q 'com.solar.launcher.xposed.themefont' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.themefont" >&2
            errors=$((errors + 1))
        fi
        if ! grep -q 'com.solar.launcher.xposed.rockbox.ime' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.ime" >&2
            errors=$((errors + 1))
        fi
        if ! grep -q 'com.solar.launcher.xposed.notpipe' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.notpipe" >&2
            errors=$((errors + 1))
        fi
        if [ "$TYPE" = "y2" ]; then
            if ! grep -q 'com.solar.launcher.xposed.bridge.y2' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
                echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y2" >&2
                errors=$((errors + 1))
            fi
            if ! grep -q 'com.solar.launcher.xposed.rockbox.compat' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
                echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.rockbox.compat" >&2
                errors=$((errors + 1))
            fi
        else
            if ! grep -q 'com.solar.launcher.xposed.bridge.y1' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
                echo "audit fail: 99XposedInit.sh must enable com.solar.launcher.xposed.bridge.y1" >&2
                errors=$((errors + 1))
            fi
        fi
        if ! grep -q '_xposed_set_module_enabled_in_prefs' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh missing safe enabled_modules merge helper" >&2
            errors=$((errors + 1))
        fi
        if ! grep -q '_xposed_repair_enabled_modules_xml' "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh must repair enabled_modules.xml" >&2
            errors=$((errors + 1))
        fi
        if grep -Fq "grep -v '</map>' \"\$PREFS\"" "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null; then
            echo "audit fail: 99XposedInit.sh uses unsafe enabled_modules merge" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ -n "$(find "$user_mount" -maxdepth 1 -name '*_launcher.apk' ! -name 'com.solar.launcher.apk' -print -quit 2>/dev/null)" ]; then
        echo "audit fail: legacy launcher APK in userdata" >&2
        errors=$((errors + 1))
    fi

    if find "$user_mount" -maxdepth 1 -name 'com.innioasis.*.apk' 2>/dev/null | grep -q .; then
        echo "audit fail: stock Innioasis launcher APK present in userdata" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$user_mount/data/switch-to-stock.sh" ]; then
        echo "audit fail: userdata/data/switch-to-stock.sh missing (Rockbox launcher handoff)" >&2
        errors=$((errors + 1))
    elif ! cmp -s "$user_mount/data/switch-to-stock.sh" "$sys_mount/etc/solar/switch-to-stock.sh"; then
        echo "audit fail: userdata switch-to-stock.sh must match /system/etc/solar copy" >&2
        errors=$((errors + 1))
    elif grep -qiE '(^|[[:space:]]|/)reboot\b|reboot -p|/system/bin/reboot' \
            "$user_mount/data/switch-to-stock.sh" 2>/dev/null; then
        echo "audit fail: userdata switch-to-stock.sh must not reboot" >&2
        errors=$((errors + 1))
    fi

    if [ "$errors" -ne 0 ]; then
        die "ROM audit failed with $errors error(s)"
    fi

    echo "==> ROM audit passed"
}

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
mkdir -p "$SOLAR_ROM_BUILD_DIR"
WORK_DIR="$(mktemp -d "$SOLAR_ROM_BUILD_DIR/work-XXXXXX")"
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

# 2026-07-16 — SOLAR_*_BASE_ZIP may be a local path OR a URL (CI vars often set the public URL).
# Only treat existing files as local copies; URLs fall through to curl (default BASE_URL or override).
LOCAL_BASE_ZIP="${SOLAR_ROM_BASE_ZIP:-}"
case "$TYPE" in
    a)  [ -n "${SOLAR_Y1A_BASE_ZIP:-}" ] && LOCAL_BASE_ZIP="$SOLAR_Y1A_BASE_ZIP" ;;
    b)  [ -n "${SOLAR_Y1B_BASE_ZIP:-}" ] && LOCAL_BASE_ZIP="$SOLAR_Y1B_BASE_ZIP" ;;
    y2) [ -n "${SOLAR_Y2_BASE_ZIP:-}" ] && LOCAL_BASE_ZIP="$SOLAR_Y2_BASE_ZIP" ;;
    a5) [ -n "${SOLAR_A5_BASE_ZIP:-}" ] && LOCAL_BASE_ZIP="$SOLAR_A5_BASE_ZIP" ;;
esac
if [ -n "$LOCAL_BASE_ZIP" ] && [ -f "$LOCAL_BASE_ZIP" ]; then
    echo "==> Using local type-${TYPE} base firmware: $LOCAL_BASE_ZIP"
    cp "$LOCAL_BASE_ZIP" "$BASE_DIR/rom.zip"
elif [ -n "$LOCAL_BASE_ZIP" ] && [[ "$LOCAL_BASE_ZIP" == http://* || "$LOCAL_BASE_ZIP" == https://* ]]; then
    echo "==> Downloading type-${TYPE} base firmware from SOLAR_*_BASE_ZIP URL"
    curl -fsSL -o "$BASE_DIR/rom.zip" "$LOCAL_BASE_ZIP" \
        || die "SOLAR_*_BASE_ZIP URL not readable: $LOCAL_BASE_ZIP"
else
    if [ -n "$LOCAL_BASE_ZIP" ]; then
        echo "==> Ignoring non-file SOLAR_*_BASE_ZIP ($LOCAL_BASE_ZIP); using default URL"
    fi
    echo "==> Downloading type-${TYPE} base firmware"
    curl -fsSL -o "$BASE_DIR/rom.zip" "$BASE_URL"
fi

# Solar Classic safety gate: Y1 A/B properties are indistinguishable while their
# boot-critical payloads differ.  Pin the selected input archive before unzip or
# loop mount; an unknown/custom base must be audited and added to the registry.
if [ "$TYPE" = "a" ] || [ "$TYPE" = "b" ]; then
    require_cmd python3
    python3 "$SCRIPT_DIR/y1-firmware-preflight.py" verify-base \
        --variant "$TYPE" --archive "$BASE_DIR/rom.zip" \
        || die "Y1 type-${TYPE} base failed audited-archive preflight"
fi

unzip -q "$BASE_DIR/rom.zip" -d "$BASE_DIR"
normalize_firmware_layout
# A5 ATA bases (and any trimmed raw ext4) need a full block-device length before loop mount.
pad_ext4_image_if_short "$BASE_DIR/system.img"
pad_ext4_image_if_short "$BASE_DIR/userdata.img"

if [ "$TYPE" = "y2" ]; then
    if [ ! -f "$BASE_DIR/EBR2" ]; then
        # 2026-07-16 — File only for SP Flash kits; must NOT rewrite scatter to invent an EBR2
        # partition that shifts ANDROID (0x6500000→0x6580000) — that bootloops Y2 eMMC.
        echo "==> Bundling EBR2 file (missing from Y2 ATA base; scatter stays stock)"
        cp "$REPO_ROOT/solar-rom/vendor/y2-flash/EBR2" "$BASE_DIR/EBR2"
    fi
    # 2026-07-16 — Stock ATA linear_start_addr only (ANDROID 0x6500000, USRDATA 0x41700000).
    # Was: vendored scatter that inserted EBR2 @ SYS14 and shifted system/userdata +0x80000
    # (remote CI rom_y2.zip bootloop). Local builds that kept base scatter still booted.
    # Always overwrite with stock-canonical scatter so CI and laptop match.
    Y2_SCATTER_VENDOR="$REPO_ROOT/solar-rom/vendor/y2-flash/MT6582_Android_scatter.txt"
    [ -f "$Y2_SCATTER_VENDOR" ] \
        || die "missing $Y2_SCATTER_VENDOR (stock MT6582 scatter for flash tools)"
    echo "==> Installing stock MT6582 scatter (ANDROID @ 0x6500000 — no EBR2 partition shift)"
    cp "$Y2_SCATTER_VENDOR" "$BASE_DIR/MT6582_Android_scatter.txt"
    # Fail fast if someone re-introduces the shifted map.
    if ! grep -q 'linear_start_addr: 0x6500000' "$BASE_DIR/MT6582_Android_scatter.txt"; then
        die "Y2 scatter missing ANDROID @ 0x6500000 (refusing bootloop layout)"
    fi
    if grep -q 'partition_name: EBR2' "$BASE_DIR/MT6582_Android_scatter.txt"; then
        die "Y2 scatter must not declare EBR2 as a download partition (shifts ANDROID — bootloop)"
    fi
    # 2026-07-16 — Firmware-only ATA bases need Windows SP Flash Tool from a full kit zip.
    ensure_y2_sp_flash_tools "$BASE_DIR"
fi

[ -f "$BASE_DIR/system.img" ] || die "system.img not found under $BASE_DIR after unzip"
[ -f "$BASE_DIR/userdata.img" ] || die "userdata.img not found under $BASE_DIR after unzip"

# Y2: keep pristine sparse copies of partitions MTK DA is sensitive about (userdata repack, boot mix-ups).
PRISTINE_DIR="$WORK_DIR/pristine"
if [ "$TYPE" = "y2" ]; then
    mkdir -p "$PRISTINE_DIR"
    for _pristine in boot.img recovery.img cache.img secro.img lk.bin MBR EBR1 EBR2 \
            preloader_eastaeon82_wet_kk.bin userdata.img system.img; do
        [ -f "$BASE_DIR/$_pristine" ] && cp -a "$BASE_DIR/$_pristine" "$PRISTINE_DIR/$_pristine"
    done
fi

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

# 2026-07-05 — Aggressive debloat before Solar/Xposed APKs land; allowlist keeps FM, Settings, BT, Rockbox path.
ALLOWLIST="$REPO_ROOT/solar-rom/config/system-app-allowlist.txt"
[ -f "$ALLOWLIST" ] || die "missing $ALLOWLIST (system APK allowlist)"
echo "==> Strip non-essential system APKs (allowlist)"
chmod +x "$SCRIPT_DIR/strip-nonessential-system-apps.sh"
sudo "$SCRIPT_DIR/strip-nonessential-system-apps.sh" "$MOUNT_SYS" "$TYPE" "$ALLOWLIST"

# 2026-07-05 — Y2 ATA has no MTK FM; Y1 keeps base FMRadio via allowlist — repair if strip ever drops it.
if [ "$TYPE" = "y2" ] \
        || { [ ! -f "$MOUNT_SYS/app/FMRadio.apk" ] && [ ! -f "$MOUNT_SYS/priv-app/FMRadio.apk" ]; }; then
    install_fm_from_y1_base "$MOUNT_SYS"
fi

# 2026-07-19 — Solar-only: strip org.rockbox + JJ from every ROM type (no LEGACY bake).
# Was: Y1 heal install_rockbox_stock_y1; Y2 LEGACY_ROCKBOX=1 bake. Reversal: restore those branches.
strip_rockbox_and_jj_from_pack() {
    local mount_sys="$1"
    echo "==> Force-remove org.rockbox + com.themoon.y1 (JJ) from Solar pack (TYPE=$TYPE)"
    sudo rm -f "$mount_sys/app/org.rockbox.apk" "$mount_sys/lib/librockbox.so"
    sudo rm -f "$mount_sys/app/com.themoon.y1.apk" "$mount_sys/priv-app/com.themoon.y1.apk"
    sudo rm -f "$mount_sys/xbin/solar-rb-launch" 2>/dev/null || true
    while IFS= read -r _jj; do
        [ -n "$_jj" ] || continue
        echo "  removing $(basename "$_jj")"
        sudo rm -f "$_jj"
    done < <(find "$mount_sys/app" "$mount_sys/priv-app" -maxdepth 1 \
        \( -iname '*themoon*' -o -iname '*jj*launcher*' -o -iname 'com.themoon.y1.apk' \
           -o -iname 'org.rockbox.apk' \) \
        2>/dev/null || true)
    if [ -n "${MOUNT_USER:-}" ] && [ -d "$MOUNT_USER" ]; then
        sudo rm -rf "$MOUNT_USER/data/org.rockbox" "$MOUNT_USER/data/com.themoon.y1" \
            "$MOUNT_USER/app/org.rockbox" "$MOUNT_USER/app/com.themoon.y1" 2>/dev/null || true
        while IFS= read -r _uapk; do
            [ -n "$_uapk" ] || continue
            echo "  removing userdata $(basename "$_uapk")"
            sudo rm -f "$_uapk"
        done < <(find "$MOUNT_USER" \( -iname 'com.themoon.y1.apk' -o -iname 'org.rockbox.apk' \
            -o -iname '*_launcher.apk' \) 2>/dev/null || true)
    fi
}

if [ "$TYPE" = "y2" ]; then
    : # FM already handled above for y2
fi
strip_rockbox_and_jj_from_pack "$MOUNT_SYS"

# Legacy launcher-init removed; Rockbox no longer baked for switch.
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1LauncherInit.sh"

sudo mkdir -p "$MOUNT_SYS/app" "$MOUNT_SYS/usr/keylayout"
sudo cp "$STAGING_APK" "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chmod 644 "$MOUNT_SYS/app/$SYSTEM_APK_NAME"
sudo chown root:root "$MOUNT_SYS/app/$SYSTEM_APK_NAME"

UPDATER_APK="${SOLAR_UPDATER_APK:-$REPO_ROOT/solar-updater/build/outputs/apk/release/solar-updater-release.apk}"
if [ -f "$UPDATER_APK" ]; then
    echo "==> Install Solar Versions updater ($UPDATER_APK)"
    sudo cp "$UPDATER_APK" "$MOUNT_SYS/app/com.solar.updater.apk"
    sudo chmod 644 "$MOUNT_SYS/app/com.solar.updater.apk"
    sudo chown root:root "$MOUNT_SYS/app/com.solar.updater.apk"
else
    echo "==> Skip com.solar.updater.apk (not built — run ./gradlew :solar-updater:assembleRelease)" >&2
fi

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

echo "==> Install launcher switch scripts + unified Rockbox keymap"
sudo mkdir -p "$MOUNT_SYS/etc/solar"
sudo cp "$SCRIPT_DIR/solar-rescue-hud-watch.sh" "$MOUNT_SYS/etc/solar/solar-rescue-hud-watch.sh"
sudo cp "$SCRIPT_DIR/solar-rescue-exec.sh" "$MOUNT_SYS/etc/solar/solar-rescue-exec.sh"
sudo cp "$SCRIPT_DIR/solar-rescue-daemon.sh" "$MOUNT_SYS/etc/solar/solar-rescue-daemon.sh"
sudo cp "$SCRIPT_DIR/solar-launcher-exec.sh" "$MOUNT_SYS/etc/solar/solar-launcher-exec.sh"
sudo cp "$SCRIPT_DIR/solar-platform-daemon.sh" "$MOUNT_SYS/etc/solar/solar-platform-daemon.sh"
sudo chmod 755 "$MOUNT_SYS/etc/solar/solar-rescue-hud-watch.sh" \
    "$MOUNT_SYS/etc/solar/solar-rescue-exec.sh" "$MOUNT_SYS/etc/solar/solar-rescue-daemon.sh" \
    "$MOUNT_SYS/etc/solar/solar-launcher-exec.sh" "$MOUNT_SYS/etc/solar/solar-platform-daemon.sh"
sudo chown root:root "$MOUNT_SYS/etc/solar/solar-rescue-hud-watch.sh" \
    "$MOUNT_SYS/etc/solar/solar-rescue-exec.sh" "$MOUNT_SYS/etc/solar/solar-rescue-daemon.sh" \
    "$MOUNT_SYS/etc/solar/solar-launcher-exec.sh" "$MOUNT_SYS/etc/solar/solar-platform-daemon.sh"
sudo cp "$SCRIPT_DIR/switch-to-stock.sh" "$MOUNT_SYS/etc/solar/switch-to-stock.sh"
# 2026-07-19 — Do not pack switch-to-rockbox.sh (Solar-only HOME). Reversal: restore cp below.
sudo rm -f "$MOUNT_SYS/etc/solar/switch-to-rockbox.sh"
sudo cp "$SCRIPT_DIR/sync-rockbox-libs.sh" "$MOUNT_SYS/etc/solar/sync-rockbox-libs.sh"
sudo cp "$SCRIPT_DIR/sync-rockbox-assets.sh" "$MOUNT_SYS/etc/solar/sync-rockbox-assets.sh"
sudo cp "$REPO_ROOT/solar-rom/system/rockbox-y2-config.cfg" "$MOUNT_SYS/etc/solar/rockbox-y2-config.cfg"
sudo cp "$SCRIPT_DIR/sync-y1-keymap.sh" "$MOUNT_SYS/etc/solar/sync-y1-keymap.sh"
sudo cp "$SCRIPT_DIR/disable-rockbox-for-solar.sh" "$MOUNT_SYS/etc/solar/disable-rockbox-for-solar.sh"
sudo cp "$SCRIPT_DIR/apply-preferred-home-boot.sh" "$MOUNT_SYS/etc/solar/apply-preferred-home-boot.sh"
sudo cp "$SCRIPT_DIR/disable-large-font-accessibility.sh" "$MOUNT_SYS/etc/solar/disable-large-font-accessibility.sh"
sudo cp "$SCRIPT_DIR/enable-gpu-performance.sh" "$MOUNT_SYS/etc/solar/enable-gpu-performance.sh"
sudo cp "$SCRIPT_DIR/solar-usb-recovery-agent.sh" "$MOUNT_SYS/etc/solar/solar-usb-recovery-agent.sh"
sudo cp "$REPO_ROOT/app/src/main/assets/y1/solar-enable-ums.sh" "$MOUNT_SYS/etc/solar/solar-enable-ums.sh"
sudo cp "$REPO_ROOT/app/src/main/assets/y1/solar-disable-ums.sh" "$MOUNT_SYS/etc/solar/solar-disable-ums.sh"
sudo cp "$REPO_ROOT/app/src/main/assets/y1/y1-enable-ums.sh" "$MOUNT_SYS/etc/solar/y1-enable-ums.sh"
sudo cp "$REPO_ROOT/app/src/main/assets/y1/y1-disable-ums.sh" "$MOUNT_SYS/etc/solar/y1-disable-ums.sh"
sudo chmod 755 "$MOUNT_SYS/etc/solar/switch-to-stock.sh" \
    "$MOUNT_SYS/etc/solar/sync-rockbox-libs.sh" "$MOUNT_SYS/etc/solar/sync-rockbox-assets.sh" \
    "$MOUNT_SYS/etc/solar/sync-y1-keymap.sh" \
    "$MOUNT_SYS/etc/solar/disable-rockbox-for-solar.sh" \
    "$MOUNT_SYS/etc/solar/apply-preferred-home-boot.sh" \
    "$MOUNT_SYS/etc/solar/disable-large-font-accessibility.sh" \
    "$MOUNT_SYS/etc/solar/enable-gpu-performance.sh" \
    "$MOUNT_SYS/etc/solar/solar-usb-recovery-agent.sh" \
    "$MOUNT_SYS/etc/solar/solar-enable-ums.sh" "$MOUNT_SYS/etc/solar/solar-disable-ums.sh" \
    "$MOUNT_SYS/etc/solar/y1-enable-ums.sh" "$MOUNT_SYS/etc/solar/y1-disable-ums.sh"
sudo chmod 644 "$MOUNT_SYS/etc/solar/rockbox-y2-config.cfg"
sudo chown root:root "$MOUNT_SYS/etc/solar/switch-to-stock.sh" \
    "$MOUNT_SYS/etc/solar/sync-rockbox-libs.sh" "$MOUNT_SYS/etc/solar/sync-rockbox-assets.sh" \
    "$MOUNT_SYS/etc/solar/rockbox-y2-config.cfg" \
    "$MOUNT_SYS/etc/solar/sync-y1-keymap.sh" \
    "$MOUNT_SYS/etc/solar/disable-rockbox-for-solar.sh" \
    "$MOUNT_SYS/etc/solar/apply-preferred-home-boot.sh" \
    "$MOUNT_SYS/etc/solar/disable-large-font-accessibility.sh" \
    "$MOUNT_SYS/etc/solar/enable-gpu-performance.sh" \
    "$MOUNT_SYS/etc/solar/solar-usb-recovery-agent.sh" \
    "$MOUNT_SYS/etc/solar/solar-enable-ums.sh" "$MOUNT_SYS/etc/solar/solar-disable-ums.sh" \
    "$MOUNT_SYS/etc/solar/y1-enable-ums.sh" "$MOUNT_SYS/etc/solar/y1-disable-ums.sh"

# 2026-07-15 — A5 skips Y1 wheel Back+Play Rockbox gesture daemon.
if [ "$TYPE" != "a5" ]; then
    sudo cp "$REPO_ROOT/solar-rom/system/99Y1ButtonScript" "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
    sudo chmod 755 "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
    sudo chown root:root "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
else
    sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
fi

if [ "$TYPE" = "a5" ]; then
    # Same playbook as scripts/push-a5-keymap.sh — face DPAD / volume / MEDIA_STOP power.
    A5_MTK="$SCRIPT_DIR/A5-mtk.kl"
    A5_GEN="$SCRIPT_DIR/A5.kl"
    [ -f "$A5_MTK" ] && [ -f "$A5_GEN" ] || die "missing A5 keylayouts in $SCRIPT_DIR"
    sudo cp "$A5_MTK" "$MOUNT_SYS/etc/solar/A5-mtk.kl"
    sudo cp "$A5_GEN" "$MOUNT_SYS/etc/solar/A5.kl"
    sudo chmod 644 "$MOUNT_SYS/etc/solar/A5-mtk.kl" "$MOUNT_SYS/etc/solar/A5.kl"
    sudo chown root:root "$MOUNT_SYS/etc/solar/A5-mtk.kl" "$MOUNT_SYS/etc/solar/A5.kl"
    # Drop Y1/Y2 Rockbox canon so sync-y1-keymap cannot silently overwrite A5 maps.
    sudo rm -f "$MOUNT_SYS/etc/solar/Y1-Rockbox.kl" "$MOUNT_SYS/etc/solar/Y2-Rockbox.kl" \
        "$MOUNT_SYS/usr/keylayout/Y1-Rockbox.kl" "$MOUNT_SYS/usr/keylayout/Y2-Rockbox.kl"
    sudo cp "$A5_MTK" "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
    if [ -e "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl" ]; then
        sudo cp "$A5_MTK" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chmod 644 "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chown root:root "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
    fi
    for _kl in Stock.kl Rockbox.kl Generic.kl A5.kl; do
        sudo cp "$A5_GEN" "$MOUNT_SYS/usr/keylayout/$_kl"
    done
    sudo chmod 644 "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Rockbox.kl" \
        "$MOUNT_SYS/usr/keylayout/Generic.kl" "$MOUNT_SYS/usr/keylayout/A5.kl" \
        "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
    sudo chown root:root "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Rockbox.kl" \
        "$MOUNT_SYS/usr/keylayout/Generic.kl" "$MOUNT_SYS/usr/keylayout/A5.kl" \
        "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
    # Strip any Rockbox APK leftovers from A5 ATA / merged paths.
    sudo rm -f "$MOUNT_SYS/app/org.rockbox.apk" "$MOUNT_SYS/lib/librockbox.so"
    CANONICAL_KL="$A5_GEN"
    CANONICAL_KL_NAME="A5.kl"
else
    [ -f "$SCRIPT_DIR/Y1-Rockbox.kl" ] || die "missing $SCRIPT_DIR/Y1-Rockbox.kl"
    # ponytail: Y1 uses Y1-Rockbox.kl; Y2 uses Y2-Rockbox.kl (same wheel/side map, Y2 volume on 114/115).
    if [ "$TYPE" = "y2" ]; then
        CANONICAL_KL="$SCRIPT_DIR/Y2-Rockbox.kl"
        CANONICAL_KL_NAME="Y2-Rockbox.kl"
        [ -f "$CANONICAL_KL" ] || die "missing $CANONICAL_KL"
    else
        CANONICAL_KL="$SCRIPT_DIR/Y1-Rockbox.kl"
        CANONICAL_KL_NAME="Y1-Rockbox.kl"
    fi
    sudo cp "$CANONICAL_KL" "$MOUNT_SYS/etc/solar/$CANONICAL_KL_NAME"
    sudo chmod 644 "$MOUNT_SYS/etc/solar/$CANONICAL_KL_NAME"
    sudo chown root:root "$MOUNT_SYS/etc/solar/$CANONICAL_KL_NAME"
    if [ "$TYPE" != "y2" ] && [ -f "$SCRIPT_DIR/mtk-kpd.y1.stock.kl" ]; then
        sudo cp "$SCRIPT_DIR/mtk-kpd.y1.stock.kl" "$MOUNT_SYS/etc/solar/mtk-kpd.y1.stock.kl"
        sudo chmod 644 "$MOUNT_SYS/etc/solar/mtk-kpd.y1.stock.kl"
        sudo chown root:root "$MOUNT_SYS/etc/solar/mtk-kpd.y1.stock.kl"
    fi
    for _kl in Stock.kl Rockbox.kl Generic.kl "$CANONICAL_KL_NAME"; do
        sudo cp "$CANONICAL_KL" "$MOUNT_SYS/usr/keylayout/$_kl"
    done
    sudo chmod 644 "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Rockbox.kl" \
        "$MOUNT_SYS/usr/keylayout/Generic.kl" "$MOUNT_SYS/usr/keylayout/$CANONICAL_KL_NAME"
    sudo chown root:root "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Rockbox.kl" \
        "$MOUNT_SYS/usr/keylayout/Generic.kl" "$MOUNT_SYS/usr/keylayout/$CANONICAL_KL_NAME"
    # Y1: patch wheel lines in stock mtk-kpd; Y2: always install canonical on GPIO + tpd (parity with Generic trio).
    if [ "$TYPE" = "y2" ]; then
        sudo cp "$CANONICAL_KL" "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
        sudo cp "$CANONICAL_KL" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chmod 644 "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chown root:root "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
    elif [ -f "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" ]; then
        sudo sed -i 's/^key 105[[:space:]].*/key 105   MEDIA_PLAY/' "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
        sudo sed -i 's/^key 106[[:space:]].*/key 106   MEDIA_PAUSE/' "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl"
        sudo cp "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chmod 644 "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
        sudo chown root:root "$MOUNT_SYS/usr/keylayout/mtk-kpd.kl" "$MOUNT_SYS/usr/keylayout/mtk-tpd-kpd.kl"
    fi
fi
# #region agent log
debug_rom_log "C" "build-rom.sh:keylayout" "canonical keylayout installed" \
    "type=$TYPE" "canon=$CANONICAL_KL_NAME" "generic=$(cmp -s "$CANONICAL_KL" "$MOUNT_SYS/usr/keylayout/Generic.kl" && echo match || echo differ)"
# #endregion

apply_english_build_prop "$MOUNT_SYS"
if [ "$TYPE" = "a5" ]; then
    apply_a5_device_identity "$MOUNT_SYS"
fi
# #region agent log
debug_rom_log "E" "build-rom.sh:apply_english" "build.prop locale patched" \
    "type=$TYPE" "locale=$(grep -m1 '^ro.product.locale=' "$MOUNT_SYS/build.prop" 2>/dev/null || echo missing)"
# #endregion

# Koensayr AVRCP patches target Y1 MT6572 MtkBt.odex/mtkbt layout. Y2 ATA (MT6582)
# ships a different BT stack (517 KiB odex, no libextavrcp_jni.so) — patch sites do not match.
# A5 is also MT6572 — try patches; soft-skip on mismatch (stock A5 BT may differ from rockbox-y1).
if [ "$TYPE" = "y2" ]; then
    echo "==> Skipping AVRCP stack patches (Y2 base — Y1-only mtkbt/MtkBt.odex layout)"
elif [ "$TYPE" = "a5" ]; then
    echo "==> AVRCP Bluetooth stack (A5 soft-skip on mismatch)"
    chmod +x "$SCRIPT_DIR/apply-avrcp-patches.sh"
    if ! sudo "$SCRIPT_DIR/apply-avrcp-patches.sh" "$MOUNT_SYS"; then
        echo "==> WARN: AVRCP patches skipped on A5 (BT stack mismatch — Solar music still works)" >&2
    fi
else
    echo "==> AVRCP Bluetooth stack (Y1Bridge + mtkbt patches; hardware keylayout unchanged)"
    chmod +x "$SCRIPT_DIR/apply-avrcp-patches.sh"
    sudo "$SCRIPT_DIR/apply-avrcp-patches.sh" "$MOUNT_SYS"
fi

# 2026-07-19 — Koensayr pairing conf (all families when etc/bluetooth exists).
echo "==> Bluetooth pairing conf (audio.conf / auto_pairing / CoD props)"
chmod +x "$SCRIPT_DIR/install-bluetooth-pairing-conf.sh"
sudo "$SCRIPT_DIR/install-bluetooth-pairing-conf.sh" "$MOUNT_SYS"

# 2026-07-19 — AirPods RTP proxy (Y1 3.0.7 stock MD5 only; soft-skip elsewhere).
if [ "$TYPE" = "a" ] || [ "$TYPE" = "b" ]; then
    echo "==> AirPods RTP libbluetoothdrv proxy (Y1)"
    chmod +x "$SCRIPT_DIR/install-airpods-rtp-proxy.sh"
    # Ensure prebuilt exists when NDK is available.
    if [ ! -f "$SCRIPT_DIR/../patches/bluetooth-rtp/prebuilt/libbluetoothdrv.so" ]; then
        chmod +x "$SCRIPT_DIR/../patches/bluetooth-rtp/build-airpods-rtp-proxy.sh" || true
        "$SCRIPT_DIR/../patches/bluetooth-rtp/build-airpods-rtp-proxy.sh" || \
            echo "==> WARN: RTP proxy build skipped — install will soft-skip" >&2
    fi
    sudo "$SCRIPT_DIR/install-airpods-rtp-proxy.sh" "$MOUNT_SYS"
else
    echo "==> Skipping AirPods RTP proxy (TYPE=$TYPE — Y1 A/B only)"
fi

install_solar_boot_assets "$BASE_DIR" "$MOUNT_SYS"

# Solar install-recovery + daemonsu boot chain — Y1 rockbox base has su but stock install-recovery
# skips Solar init.d and early Xposed staged app_process apply; Y2 ATA base is unrooted.
echo "==> Install Solar boot recovery chain (install-recovery + init.d hooks)"
chmod +x "$SCRIPT_DIR/install-y1-su-system.sh"
sudo "$SCRIPT_DIR/install-y1-su-system.sh" "$MOUNT_SYS"
# 2026-07-19 — Do not install solar-rb-launch (no Rockbox bake). Reversal: restore Y2 install block.
if [ "$TYPE" = "y2" ]; then
    # #region agent log
    debug_rom_log "D" "build-rom.sh:install_su" "Y1 permissive su baked" \
        "su_mode=$(stat -c%a "$MOUNT_SYS/xbin/su" 2>/dev/null || echo missing)"
    # #endregion
fi

# Xposed Dalvik — API 17 for Y1 type A/B + A5, API 19 for Y2 (same arm app_process binary).
case "$TYPE" in
    a|b|a5) XPOSED_API=17 ;;
    y2)     XPOSED_API=19 ;;
esac
if [ ! -f "$SCRIPT_DIR/../vendor/xposed/XposedInstaller.apk" ]; then
    echo "==> Build XposedInstaller.apk (first run)"
    chmod +x "$SCRIPT_DIR/build-xposed-installer-apk.sh"
    "$SCRIPT_DIR/build-xposed-installer-apk.sh"
fi
echo "==> Install Xposed framework (API $XPOSED_API) — required for a, b, y2, and a5 ROM zips"
chmod +x "$SCRIPT_DIR/install-xposed-system.sh"
sudo "$SCRIPT_DIR/install-xposed-system.sh" "$MOUNT_SYS" "$XPOSED_API"
install_notpipe_system "$MOUNT_SYS"
install_launcher_helper_system "$MOUNT_SYS"

echo "==> Patching userdata partition"
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    echo "  removing userdata/$(basename "$apk")"
    sudo rm -f "$apk"
done < <(find "$MOUNT_USER" -maxdepth 1 -name 'com.innioasis.*.apk' 2>/dev/null || true)
sudo rm -f "$MOUNT_USER/data/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER/data/com.innioasis.y2.apk"
sudo rm -f "$MOUNT_USER"/*_launcher.apk
# 2026-07-19 — Y2 jj_auto base may leave Rockbox/JJ userdata trees; wipe always (safe on Y1).
sudo rm -rf "$MOUNT_USER/data/org.rockbox" "$MOUNT_USER/data/com.themoon.y1" 2>/dev/null || true
sudo rm -f "$MOUNT_USER/data/com.themoon.y1.apk" 2>/dev/null || true
sudo rm -f "$MOUNT_USER/data/*_launcher_initialized"
sudo rm -f "$MOUNT_USER/data/.solar_rom_home_ready"
sudo rm -f "$MOUNT_USER/data/initialized"

echo "==> Seed Solar HOME scripts in userdata (no Rockbox switch)"
sudo mkdir -p "$MOUNT_USER/data"
# 2026-07-19 — Drop switch-to-rockbox userdata seed. Was: cp switch-to-rockbox.sh.
sudo cp "$SCRIPT_DIR/switch-to-stock.sh" "$MOUNT_USER/data/switch-to-stock.sh"
sudo rm -f "$MOUNT_USER/data/switch-to-rockbox.sh"
sudo cp "$SCRIPT_DIR/solar-launcher-exec.sh" "$MOUNT_USER/data/solar-launcher-exec.sh"
sudo chmod 755 "$MOUNT_USER/data/switch-to-stock.sh" \
    "$MOUNT_USER/data/solar-launcher-exec.sh"
sudo chown root:root "$MOUNT_USER/data/switch-to-stock.sh" \
    "$MOUNT_USER/data/solar-launcher-exec.sh"

# Final strip after any late installs (su / xposed / userdata seeds).
strip_rockbox_and_jj_from_pack "$MOUNT_SYS"

audit_rom_contents "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER"

echo "==> Unmounting images"
sync
sudo umount "$MOUNT_SYS"
sudo umount "$MOUNT_USER"
MOUNT_SYS=""
MOUNT_USER=""

if [ "$TYPE" = "y2" ]; then
    # 2026-07-06 — Duplicate UUID on system+userdata broke Y2 boot (/data mount + adb shell).
    if [ -f "$SYSTEM_MOUNT_SRC" ] && [ "$SYSTEM_MOUNT_SRC" != "$BASE_DIR/system.img" ]; then
        assign_ext4_unique_uuid "$SYSTEM_MOUNT_SRC"
    fi
    if [ -f "$USERDATA_MOUNT_SRC" ] && [ "$USERDATA_MOUNT_SRC" != "$BASE_DIR/userdata.img" ]; then
        assign_ext4_unique_uuid "$USERDATA_MOUNT_SRC"
    fi
    # MTK SP Flash on MT6582: desparsed ext4 images flash slower but avoid sparse chunk DA failures.
    finalize_image_raw "$BASE_DIR/system.img" "$SYSTEM_MOUNT_SRC"
    finalize_image_raw "$BASE_DIR/userdata.img" "$USERDATA_MOUNT_SRC"
    for _restore in boot.img recovery.img cache.img secro.img lk.bin MBR EBR1 EBR2 \
            preloader_eastaeon82_wet_kk.bin; do
        [ -f "$PRISTINE_DIR/$_restore" ] && cp -a "$PRISTINE_DIR/$_restore" "$BASE_DIR/$_restore"
    done
    # 2026-07-16 — Re-assert stock scatter after pristine restore (ANDROID @ 0x6500000).
    # Was: comment claimed “never ship base ATA 0x6500000” — that inverted truth and bootlooped CI.
    Y2_SCATTER_VENDOR="$REPO_ROOT/solar-rom/vendor/y2-flash/MT6582_Android_scatter.txt"
    if [ -f "$Y2_SCATTER_VENDOR" ]; then
        cp "$Y2_SCATTER_VENDOR" "$BASE_DIR/MT6582_Android_scatter.txt"
    fi
else
    finalize_image_after_mount "$BASE_DIR/system.img" "$SYSTEM_MOUNT_SRC"
    finalize_image_after_mount "$BASE_DIR/userdata.img" "$USERDATA_MOUNT_SRC"
fi

rm -f "$BASE_DIR/rom.zip"
rm -rf "$MOUNT_SYS" "$MOUNT_USER"

mkdir -p "$(dirname "$OUTPUT")"
echo "==> Creating $OUTPUT"
rm -f "$OUTPUT"
(
    cd "$BASE_DIR"
    # 2026-07-15 — All ATA types (Y1 A/B, Y2, A5): keep SP Flash Tool + firmware tree.
    # Was: Y2 zip -j ./* (dropped flash_tool.exe / codecs/). Now: pack_ata_rom_zip for a|b|y2|a5.
    case "$TYPE" in
        a|b|y2|a5) pack_ata_rom_zip "$OUTPUT" ;;
        *)         zip -j -q "$OUTPUT" ./* ;;
    esac
)

if [ -n "${SUDO_UID:-}" ] && [ -n "${SUDO_GID:-}" ]; then
    chown "$SUDO_UID:$SUDO_GID" "$OUTPUT"
fi

echo "==> Built $OUTPUT ($(du -h "$OUTPUT" | awk '{print $1}'))"
