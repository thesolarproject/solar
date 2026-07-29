#!/usr/bin/env bash
# 2026-07-05 — Verify app/src/main/assets/platform matches vendor sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DST="$ROOT/app/src/main/assets/platform"
VENDOR="$ROOT/solar-rom/vendor/xposed"

die() { echo "verify-platform-assets: $*" >&2; exit 1; }

printable_strings() {
    if command -v strings >/dev/null 2>&1; then
        strings
        return
    fi
    local python_cmd=""
    if command -v python3 >/dev/null 2>&1; then
        python_cmd="python3"
    elif command -v python >/dev/null 2>&1; then
        python_cmd="python"
    else
        die "verification needs strings, python3, or python"
    fi
    "$python_cmd" -c 'import re, sys
data = sys.stdin.buffer.read()
for value in re.findall(rb"[\x20-\x7e]{4,}", data):
    print(value.decode("ascii"))'
}

[ -f "$DST/manifest.json" ] || die "missing manifest — run sync-platform-assets.sh"
[ -f "$DST/xposed/XposedInstaller.apk" ] || die "missing XposedInstaller.apk asset"
[ -f "$DST/xposed/SolarContextBridgeY1.apk" ] || die "missing SolarContextBridgeY1.apk asset"
[ -f "$DST/xposed/SolarContextBridgeY2.apk" ] || die "missing SolarContextBridgeY2.apk asset"
[ -f "$DST/xposed/SolarThemeFont.apk" ] || die "missing SolarThemeFont.apk asset"
[ -f "$DST/xposed/SolarRockboxIme.apk" ] || die "missing SolarRockboxIme.apk asset"
[ -f "$DST/xposed/SolarRockboxCompat.apk" ] || die "missing SolarRockboxCompat.apk asset"
# 2026-07-15 — Native Solar YouTube; NotPipe + bridge no longer in APK self-heal kit.
# Was: require SolarNotPipeBridge.apk + notPipe-0.3.0-release.apk (sync now deletes them).
# A5 ROM may still bake upstream notPipe separately — not an APK platform asset.
[ ! -f "$DST/xposed/SolarNotPipeBridge.apk" ] || die "stale SolarNotPipeBridge.apk — re-run sync-platform-assets.sh"
[ ! -f "$DST/thirdparty/notPipe-0.3.0-release.apk" ] || die "stale notPipe APK asset — re-run sync-platform-assets.sh"
[ -f "$DST/companion/SolarGlobalContextModal.apk" ] || die "missing SolarGlobalContextModal.apk asset"
[ -f "$DST/companion/SolarHomeHelper.apk" ] || die "missing SolarHomeHelper.apk asset"
# 2026-07-08 — Companion must ship interactive sole-shell host (not text placeholder only).
if ! unzip -p "$DST/companion/SolarGlobalContextModal.apk" classes.dex 2>/dev/null \
        | printable_strings | grep -E 'CompanionOverlayKeyGate|CompanionTierScheduler' >/dev/null; then
    die "SolarGlobalContextModal.apk missing companion overlay gate/tier (rebuild :global-context-modal)"
fi
# 2026-07-08 — Bridge assets must retain companion retarget + system ANR/crash fail-open.
for bridge in SolarContextBridgeY1.apk SolarContextBridgeY2.apk; do
    dex_strings="$(unzip -p "$DST/xposed/$bridge" classes.dex 2>/dev/null \
        | printable_strings || true)"
    echo "$dex_strings" | grep 'legacy_shell' >/dev/null \
        || die "$bridge missing legacy_shell rollback prop"
    echo "$dex_strings" | grep 'globalcontext' >/dev/null \
        || die "$bridge missing companion globalcontext retarget"
    echo "$dex_strings" | grep 'SystemErrorDialogRouting' >/dev/null \
        || die "$bridge missing SystemErrorDialogRouting"
    echo "$dex_strings" | grep 'scheduleCrashOverlayFailOpen' >/dev/null \
        || die "$bridge missing crash 2s fail-open"
    echo "$dex_strings" | grep 'BluetoothPairingVariantPolicy' >/dev/null \
        || die "$bridge missing shared interactive pairing policy"
    if echo "$dex_strings" | grep -E \
            'BluetoothPairingDialog silent PIN|BluetoothPairingDialog silent confirm' >/dev/null; then
        die "$bridge still contains silent Bluetooth pairing acceptance"
    fi
done
# 2026-07-08 — Manifest prepVersion must match sync-platform-assets.sh source of truth.
SYNC_PREP="$(grep -E '^\s*"prepVersion":' "$ROOT/solar-rom/scripts/sync-platform-assets.sh" | head -1 | grep -oE '[0-9]+' || true)"
MAN_PREP="$(grep -E '"prepVersion"' "$DST/manifest.json" | head -1 | grep -oE '[0-9]+' || true)"
[ -n "$SYNC_PREP" ] && [ -n "$MAN_PREP" ] || die "could not read prepVersion from sync/manifest"
[ "$SYNC_PREP" = "$MAN_PREP" ] || die "prepVersion mismatch sync=$SYNC_PREP manifest=$MAN_PREP — re-run sync-platform-assets.sh"
[ -f "$DST/init/99XposedInit.sh" ] || die "missing 99XposedInit.sh asset"
for api in api17-arm api19-arm; do
    for f in app_process XposedBridge.jar xposed.prop; do
        [ -f "$DST/xposed/$api/$f" ] || die "missing xposed/$api/$f"
    done
done
[ -f "$ROOT/app/src/main/assets/scripts/solar-platform-prep.sh" ] || die "missing solar-platform-prep.sh"
# 2026-07-19 — Rockbox APK bundle must not ship in platform assets (Solar-only).
# Was: verify-platform-rockbox-assets.sh required org.rockbox-*.apk. Reversal: restore that call.
if [ -d "$DST/rockbox" ]; then
    die "stale platform/rockbox/ present — re-run sync-platform-assets.sh (Solar-only)"
fi
if grep -q '"rockbox"' "$DST/manifest.json"; then
    die "manifest still has rockbox block — re-run sync-platform-assets.sh"
fi
echo "verify-platform-assets: OK"
