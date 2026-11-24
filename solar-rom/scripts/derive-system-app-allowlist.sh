#!/usr/bin/env bash
# 2026-07-05 — Build curated allowlist: rockbox-y1 inventory minus known bloat + Solar hard-keep merge.
# Reversal: commit hand-edited solar-rom/config/system-app-allowlist.txt only; drop this generator.
# Usage: derive-system-app-allowlist.sh [--write CONFIG_PATH]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WRITE_PATH=""
if [ "${1:-}" = "--write" ]; then
    WRITE_PATH="${2:-$REPO_ROOT/solar-rom/config/system-app-allowlist.txt}"
fi

# Bloat aligned with plan "Expected Y1 removals" — safe on Y1/Y2 media players.
BLOAT=(
    ApplicationGuide.apk Exchange2.apk CalendarImporter.apk CalendarProvider.apk
    Protips.apk MemClear.apk MtkWorldClockWidget.apk SchedulePowerOnOff.apk Stk1.apk
    LocationEM.apk CDS_INFO.apk DataTransfer.apk HTMLViewer.apk Tag.apk
    MTKAndroidSuiteDaemon.apk MTKThermalManager.apk BatteryWarning.apk
    Galaxy4.apk HoloSpiralWallpaper.apk LiveWallpapers.apk LiveWallpapersPicker.apk
    MagicSmokeWallpapers.apk NoiseField.apk PhaseBeam.apk PhotoTable.apk
    VisualizationWallpapers.apk MtkVideoLiveWallpaper.apk
    EngineerCode.apk EngineerMode.apk EngineerModeSim.apk
    Calculator.apk DeskClock.apk DocumentsUI.apk FileManager.apk Gallery2.apk
    Music.apk MusicFX.apk Videos.apk VideoEditor.apk VideoFavorites.apk
    SoundRecorder.apk QuickSearchBox.apk Todos.apk Bluetooth.apk
    CellConnService.apk DataUsageLockScreenClient.apk MTKLogger.apk
    PacProcessor.apk PermissionControl.apk PrintSpooler.apk VoiceCommand.apk VoiceUnlock.apk
    Contacts.apk Dialer.apk Mms.apk MyFactoryLauncher.apk
)

HARD_KEEP=(
    com.solar.launcher.apk org.rockbox.apk XposedInstaller.apk
    SolarContextBridgeY1.apk SolarContextBridgeY2.apk SolarThemeFont.apk \
    Y1Bridge.apk
    FMRadio.apk MtkBt.apk Settings.apk SettingsProvider.apk SystemUI.apk
    PackageInstaller.apk TelephonyProvider.apk DownloadProvider.apk DownloadProviderUi.apk
    KeyChain.apk LatinIME.apk Provision.apk ApplicationsProvider.apk CertInstaller.apk
    BackupRestoreConfirmation.apk DefaultContainerService.apk DrmProvider.apk FusedLocation.apk
    UserDictionaryProvider.apk SharedStorageBackup.apk VpnDialogs.apk PicoTts.apk BasicDreams.apk
    ExternalStorageProvider.apk InputDevices.apk Keyguard.apk MediaProvider.apk Shell.apk
    TeleService.apk OneTimeInitializer.apk ProxyHandler.apk ContactsProvider.apk
)

is_bloat() {
    local base="$1" b
    for b in "${BLOAT[@]}"; do
        [ "$base" = "$b" ] && return 0
    done
    return 1
}

SOLAR_ROM_BUILD_DIR="${SOLAR_ROM_BUILD_DIR:-$HOME/.cache/solar-rom-build}"
WORK="$(mktemp -d "$SOLAR_ROM_BUILD_DIR/derive-allowlist-XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-a-base/rom.zip"
curl -fsSL -o "$WORK/rom.zip" "$BASE_URL"
unzip -q "$WORK/rom.zip" system.img -d "$WORK" 2>/dev/null \
    || unzip -q "$WORK/rom.zip" '*/system.img' -d "$WORK"
SYSIMG="$(find "$WORK" -name system.img | head -1)"
[ -f "$SYSIMG" ] || { echo "error: type-A base missing system.img" >&2; exit 1; }

chmod +x "$SCRIPT_DIR/inventory-system-apks.sh"
mapfile -t BASE_APKS < <("$SCRIPT_DIR/inventory-system-apks.sh" "$SYSIMG")

declare -A ALLOW=()
for apk in "${BASE_APKS[@]}"; do
    is_bloat "$apk" && continue
    ALLOW["$apk"]=1
done
for apk in "${HARD_KEEP[@]}"; do
    ALLOW["$apk"]=1
done

OUTPUT="$WORK/allowlist.txt"
{
    echo "# Generated $(date -u +%Y-%m-%d) — review before replacing committed allowlist."
    printf '%s\n' "${!ALLOW[@]}" | sort
} > "$OUTPUT"

if [ -n "$WRITE_PATH" ]; then
    cp "$OUTPUT" "$WRITE_PATH"
    echo "==> Wrote $WRITE_PATH"
else
    cat "$OUTPUT"
fi
