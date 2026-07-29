#!/usr/bin/env bash
# 2026-07-05 — Bundles Xposed vendor trees + production modules into APK assets and emits manifest.json.
# APK/ROM parity: mirrors install-xposed-system.sh paths; SolarPlatformPrep reads manifest at runtime.
# When changing: bump prepVersion below if ladder/deprecated/modules change; update XposedModuleRegistry;
#   install-xposed-system.sh + 99XposedInit.sh + verify-xposed-rom-contents.sh; run verify-platform-assets.sh.
# prepVersion bump checklist:
#   1) New module row in modules[] (y1/y2/both)  2) deprecated[] for removed /system APKs
#   3) files[] for new init.d hooks  4) rebuild APK via scripts/build.sh (always runs this script)
# Reversal: delete script; APK ships without platform repair kit (ROM-only Xposed again).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENDOR="$ROOT/solar-rom/vendor/xposed"
DST="$ROOT/app/src/main/assets/platform"
INIT_SRC="$ROOT/solar-rom/system/99XposedInit.sh"
REUSE_EXISTING="${SOLAR_PLATFORM_REUSE_EXISTING:-0}"

die() { echo "sync-platform-assets: $*" >&2; exit 1; }

# Build production module APKs when missing (same preflight as ROM build).
build_if_missing() {
    local path="$1" script="$2"
    if [ -f "$path" ]; then return 0; fi
    [ -x "$SCRIPT_DIR/$script" ] || die "missing $path and $script"
    "$SCRIPT_DIR/$script"
    [ -f "$path" ] || die "build failed: $path"
}

if [ "$REUSE_EXISTING" != "1" ]; then
    build_if_missing "$VENDOR/XposedInstaller.apk" "build-xposed-installer-apk.sh"
    build_if_missing "$VENDOR/solar-context-bridge/SolarContextBridgeY1.apk" "build-context-bridge-apk.sh"
    build_if_missing "$VENDOR/solar-context-bridge/SolarContextBridgeY2.apk" "build-context-bridge-apk.sh"
    build_if_missing "$VENDOR/solar-theme-font/SolarThemeFont.apk" "build-theme-font-apk.sh"
    build_if_missing "$VENDOR/solar-rockbox-ime/SolarRockboxIme.apk" "build-rockbox-xposed-apks.sh"
    build_if_missing "$VENDOR/solar-rockbox-compat/SolarRockboxCompat.apk" "build-rockbox-xposed-apks.sh"
fi
# 2026-07-15 — YouTube is native in Solar APK; notPipe + SolarNotPipeBridge no longer bundled.
# A5 ROM still bakes upstream notPipe via build-rom.sh install_notpipe_system (touch UI).

# 2026-07-05 — Companion global context modal APK (Phase 1); bundled for platform self-heal.
COMPANION_APK="${SOLAR_COMPANION_APK:-$ROOT/global-context-modal/build/outputs/apk/debug/global-context-modal-debug.apk}"
if [ ! -f "$COMPANION_APK" ]; then
    (cd "$ROOT" && ./gradlew :global-context-modal:assembleDebug -q)
fi
[ -f "$COMPANION_APK" ] || die "missing companion APK — run ./gradlew :global-context-modal:assembleDebug"

# 2026-07-06 — Solar Home Helper middle-man APK; permanent PM preferred HOME on Y1/Y2.
HELPER_APK="${SOLAR_HELPER_APK:-$ROOT/launcher-helper/build/outputs/apk/debug/launcher-helper-debug.apk}"
if [ ! -f "$HELPER_APK" ]; then
    (cd "$ROOT" && ./gradlew :launcher-helper:assembleDebug -q)
fi
[ -f "$HELPER_APK" ] || die "missing SolarHomeHelper APK — run ./gradlew :launcher-helper:assembleDebug"

if [ "$REUSE_EXISTING" != "1" ]; then
    [ -d "$VENDOR/api17-arm" ] || die "missing $VENDOR/api17-arm"
    [ -d "$VENDOR/api19-arm" ] || die "missing $VENDOR/api19-arm"
fi
[ -f "$INIT_SRC" ] || die "missing $INIT_SRC"

mkdir -p "$DST/xposed/api17-arm" "$DST/xposed/api19-arm" "$DST/init" "$DST/scripts" "$DST/companion" "$DST/thirdparty" "$DST/bluetooth"

# 2026-07-19 — AirPods RTP proxy for APK self-heal (Y1 MD5-gated installer).
RTP_PROXY="$ROOT/solar-rom/patches/bluetooth-rtp/prebuilt/libbluetoothdrv.so"
if [ ! -f "$RTP_PROXY" ]; then
    chmod +x "$ROOT/solar-rom/patches/bluetooth-rtp/build-airpods-rtp-proxy.sh" || true
    "$ROOT/solar-rom/patches/bluetooth-rtp/build-airpods-rtp-proxy.sh" || true
fi
if [ -f "$RTP_PROXY" ]; then
    cp "$RTP_PROXY" "$DST/bluetooth/libbluetoothdrv.so"
    echo "==> Bundled AirPods RTP proxy for platform self-heal"
else
    echo "==> WARN: AirPods RTP proxy not built — self-heal will soft-skip" >&2
    rm -f "$DST/bluetooth/libbluetoothdrv.so"
fi

# 2026-07-19 — Do not bundle org.rockbox APK/libs into Solar APK (Solar-only; launch-if-present).
# Was: sync_rockbox_platform_assets fetched/patched Rockbox into assets/platform/rockbox/.
# Reversal: restore sync_rockbox_platform_assets function + call + rockbox manifest block.
rm -rf "$DST/rockbox"
echo "==> Rockbox platform APK bundle skipped (Solar-only — no self-heal install)"

# Vendor framework trees (app_process, XposedBridge.jar, xposed.prop).
if [ "$REUSE_EXISTING" = "1" ]; then
    for rel in \
        xposed/api17-arm/app_process xposed/api17-arm/XposedBridge.jar xposed/api17-arm/xposed.prop \
        xposed/api19-arm/app_process xposed/api19-arm/XposedBridge.jar xposed/api19-arm/xposed.prop \
        xposed/XposedInstaller.apk xposed/SolarContextBridgeY1.apk \
        xposed/SolarContextBridgeY2.apk xposed/SolarThemeFont.apk \
        xposed/SolarRockboxIme.apk xposed/SolarRockboxCompat.apk; do
        [ -f "$DST/$rel" ] || die "reuse requested but existing asset is missing: $rel"
    done
else
    for api in api17-arm api19-arm; do
        for f in app_process XposedBridge.jar xposed.prop; do
            cp "$VENDOR/$api/$f" "$DST/xposed/$api/$f"
        done
    done
    cp "$VENDOR/XposedInstaller.apk" "$DST/xposed/XposedInstaller.apk"
    cp "$VENDOR/solar-context-bridge/SolarContextBridgeY1.apk" "$DST/xposed/SolarContextBridgeY1.apk"
    cp "$VENDOR/solar-context-bridge/SolarContextBridgeY2.apk" "$DST/xposed/SolarContextBridgeY2.apk"
    cp "$VENDOR/solar-theme-font/SolarThemeFont.apk" "$DST/xposed/SolarThemeFont.apk"
    cp "$VENDOR/solar-rockbox-ime/SolarRockboxIme.apk" "$DST/xposed/SolarRockboxIme.apk"
    cp "$VENDOR/solar-rockbox-compat/SolarRockboxCompat.apk" "$DST/xposed/SolarRockboxCompat.apk"
fi
# 2026-07-15 — Drop legacy NotPipe self-heal assets if a previous sync left them.
rm -f "$DST/xposed/SolarNotPipeBridge.apk" "$DST/thirdparty/notPipe-0.3.0-release.apk"
cp "$COMPANION_APK" "$DST/companion/SolarGlobalContextModal.apk"
cp "$HELPER_APK" "$DST/companion/SolarHomeHelper.apk"
cp "$INIT_SRC" "$DST/init/99XposedInit.sh"
chmod 755 "$DST/init/99XposedInit.sh"

# Shell companion for bulk root copies (Java invokes after asset extract).
PREP_SH="$ROOT/app/src/main/assets/scripts/solar-platform-prep.sh"
mkdir -p "$(dirname "$PREP_SH")"
cat > "$PREP_SH" <<'EOF'
#!/system/bin/sh
# 2026-07-05 — Idempotent /system file staging from extracted platform-prep cache.
# Usage: solar-platform-prep.sh copy <src> <dest> <mode>
# Emits: SOLAR_PREP_PROGRESS pct message
progress() { echo "SOLAR_PREP_PROGRESS $1 $2"; }
case "$1" in
copy)
  SRC="$2"; DEST="$3"; MODE="${4:-644}"
  [ -f "$SRC" ] || exit 1
  mount -o remount,rw /system 2>/dev/null || true
  mkdir -p "$(dirname "$DEST")"
  cp "$SRC" "$DEST" || exit 1
  chmod "$MODE" "$DEST" 2>/dev/null || true
  progress 100 "copied $(basename "$DEST")"
  ;;
*) exit 1 ;;
esac
EOF
chmod +x "$PREP_SH"

sha256_file() {
    sha256sum "$1" | awk '{print $1}'
}

apk_version_code() {
    local apk="$1"
    local BT=""
    ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    for v in 35.0.0 34.0.0 36.0.0 28.0.3; do
        [ -x "$ANDROID_HOME/build-tools/$v/aapt" ] && BT="$ANDROID_HOME/build-tools/$v/aapt" && break
    done
    if [ -n "$BT" ]; then
        "$BT" dump badging "$apk" 2>/dev/null | sed -n "s/^.*versionCode='\([0-9]*\)'.*/\1/p" | head -1
        return 0
    fi
    echo "1"
}

Y1_VC="$(apk_version_code "$DST/xposed/SolarContextBridgeY1.apk")"
Y2_VC="$(apk_version_code "$DST/xposed/SolarContextBridgeY2.apk")"
TF_VC="$(apk_version_code "$DST/xposed/SolarThemeFont.apk")"
RB_IME_VC="$(apk_version_code "$DST/xposed/SolarRockboxIme.apk")"
RB_COMPAT_VC="$(apk_version_code "$DST/xposed/SolarRockboxCompat.apk")"
GC_VC="$(apk_version_code "$DST/companion/SolarGlobalContextModal.apk")"
HELPER_VC="$(apk_version_code "$DST/companion/SolarHomeHelper.apk")"

# manifest.json — parsed by PlatformPrepManifest.java at runtime.
# 2026-07-19 — prepVersion 22: AirPods RTP proxy self-heal + bluetooth pairing conf (20).
# Was 20: bluetooth pairing conf only. Was 19: drop rockbox APK bundle.
cat > "$DST/manifest.json" <<EOF
{
  "prepVersion": 22,
  "framework": {
    "api17": {
      "appProcess": "xposed/api17-arm/app_process",
      "bridgeJar": "xposed/api17-arm/XposedBridge.jar",
      "xposedProp": "xposed/api17-arm/xposed.prop"
    },
    "api19": {
      "appProcess": "xposed/api19-arm/app_process",
      "bridgeJar": "xposed/api19-arm/XposedBridge.jar",
      "xposedProp": "xposed/api19-arm/xposed.prop"
    },
    "installerApk": "xposed/XposedInstaller.apk",
    "initHook": "init/99XposedInit.sh",
    "systemPaths": {
      "appProcess": "/system/bin/app_process",
      "appProcessOrig": "/system/bin/app_process.orig",
      "bridgeJarFramework": "/system/framework/XposedBridge.jar",
      "bridgeJarSolar": "/system/etc/solar/XposedBridge.jar",
      "xposedProp": "/system/xposed.prop",
      "installerApk": "/system/app/XposedInstaller.apk",
      "initHook": "/system/etc/init.d/99XposedInit.sh"
    }
  },
  "modules": [
    {
      "pkg": "com.solar.launcher.xposed.bridge.y1",
      "asset": "xposed/SolarContextBridgeY1.apk",
      "systemApk": "/system/app/SolarContextBridgeY1.apk",
      "required": true,
      "device": "y1",
      "versionCode": ${Y1_VC:-1},
      "sha256": "$(sha256_file "$DST/xposed/SolarContextBridgeY1.apk")"
    },
    {
      "pkg": "com.solar.launcher.xposed.bridge.y2",
      "asset": "xposed/SolarContextBridgeY2.apk",
      "systemApk": "/system/app/SolarContextBridgeY2.apk",
      "required": true,
      "device": "y2",
      "versionCode": ${Y2_VC:-1},
      "sha256": "$(sha256_file "$DST/xposed/SolarContextBridgeY2.apk")"
    },
    {
      "pkg": "com.solar.launcher.xposed.themefont",
      "asset": "xposed/SolarThemeFont.apk",
      "systemApk": "/system/app/SolarThemeFont.apk",
      "required": true,
      "device": "both",
      "versionCode": ${TF_VC:-1},
      "sha256": "$(sha256_file "$DST/xposed/SolarThemeFont.apk")"
    },
    {
      "pkg": "com.solar.launcher.xposed.rockbox.ime",
      "asset": "xposed/SolarRockboxIme.apk",
      "systemApk": "/system/app/SolarRockboxIme.apk",
      "required": true,
      "device": "both",
      "versionCode": ${RB_IME_VC:-1},
      "sha256": "$(sha256_file "$DST/xposed/SolarRockboxIme.apk")"
    },
    {
      "pkg": "com.solar.launcher.xposed.rockbox.compat",
      "asset": "xposed/SolarRockboxCompat.apk",
      "systemApk": "/system/app/SolarRockboxCompat.apk",
      "required": true,
      "device": "y2",
      "versionCode": ${RB_COMPAT_VC:-1},
      "sha256": "$(sha256_file "$DST/xposed/SolarRockboxCompat.apk")"
    },
    {
      "pkg": "com.solar.launcher.globalcontext",
      "asset": "companion/SolarGlobalContextModal.apk",
      "systemApk": "/system/app/SolarGlobalContextModal.apk",
      "required": true,
      "device": "both",
      "versionCode": ${GC_VC:-1},
      "sha256": "$(sha256_file "$DST/companion/SolarGlobalContextModal.apk")"
    },
    {
      "pkg": "com.solar.launcher.homehelper",
      "asset": "companion/SolarHomeHelper.apk",
      "systemApk": "/system/app/SolarHomeHelper.apk",
      "required": true,
      "device": "both",
      "versionCode": ${HELPER_VC:-1},
      "sha256": "$(sha256_file "$DST/companion/SolarHomeHelper.apk")"
    }
  ],
  "files": [
    {"asset": "init/99XposedInit.sh", "dest": "/system/etc/init.d/99XposedInit.sh", "mode": "755"}
  ],
  "deprecated": [
    {
      "systemApk": "/system/app/SolarContextBridge.apk",
      "pkg": "com.solar.launcher.xposed.bridge",
      "device": "both",
      "reason": "legacy unified bridge before Y1/Y2 split"
    },
    {
      "systemApk": "/system/app/SolarContextBridgeY1.apk",
      "pkg": "com.solar.launcher.xposed.bridge.y1",
      "device": "y2",
      "reason": "wrong-family bridge on Y2"
    },
    {
      "systemApk": "/system/app/SolarContextBridgeY2.apk",
      "pkg": "com.solar.launcher.xposed.bridge.y2",
      "device": "y1",
      "reason": "wrong-family bridge on Y1"
    },
    {
      "systemApk": "/system/app/SolarNotPipeBridge.apk",
      "pkg": "com.solar.launcher.xposed.notpipe",
      "device": "both",
      "reason": "native Solar YouTube replaces NotPipe IPC bridge"
    },
    {
      "systemApk": "/system/app/io.github.gohoski.notpipe.apk",
      "pkg": "io.github.gohoski.notpipe",
      "device": "both",
      "reason": "native Solar YouTube; A5 ROM touch NotPipe skipped in cleaner"
    }
  ]
}
EOF

chmod +x "$ROOT/solar-rom/scripts/verify-platform-assets.sh"
"$ROOT/solar-rom/scripts/verify-platform-assets.sh"
echo "sync-platform-assets: OK -> $DST"
