# Build Solar

## Requirements

- JDK 17 or 21
- Android SDK platform 35 and build-tools 35
- Git Bash on Windows, or Bash on Linux
- Python 3 for the Y1 firmware preflight
- For ROMs only: a privileged Linux host with `curl`, `unzip`, `zip`,
  `openssl`, `e2fsprogs`, loop-mount support, and the exact audited Y1 base

Keep credentials, platform keys, ROM bases, backups, and personal media outside
the checkout.

## Debug build

Linux/macOS:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export SOURCE_DATE_EPOCH="$(date -u +%s)"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_SDK_ROOT = "C:\path\to\android-sdk"
$env:SOURCE_DATE_EPOCH = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The main artifact is `app/build/outputs/apk/debug/app-debug.apk`. Companion
artifacts are produced under the corresponding module's
`build/outputs/apk/debug/` directory.

On OneDrive or another directory with aggressive file locking, keep build
outputs outside the checkout:

```powershell
$env:SOLAR_BUILD_ROOT = "C:\build-cache\solar"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The resulting main APK is then
`$env:SOLAR_BUILD_ROOT\app\outputs\apk\debug\app-debug.apk`.

## Refresh embedded platform companions

The main APK contains verified platform-repair assets. After changing
`global-context-modal` or `launcher-helper`, rebuild and resync them before
packaging the app:

```bash
./gradlew :global-context-modal:assembleDebug :launcher-helper:assembleDebug
./solar-rom/scripts/sync-platform-assets.sh
```

For an out-of-tree build, provide the exact artifacts:

```bash
SOLAR_COMPANION_APK=/build/solar/global-context-modal/outputs/apk/debug/global-context-modal-debug.apk \
SOLAR_HELPER_APK=/build/solar/launcher-helper/outputs/apk/debug/launcher-helper-debug.apk \
SOLAR_PLATFORM_REUSE_EXISTING=1 \
  ./solar-rom/scripts/sync-platform-assets.sh
```

`SOLAR_PLATFORM_REUSE_EXISTING=1` retains the already tracked Xposed assets
while updating the two Gradle-built companions. The verifier still checks the
complete resulting asset set and regenerates its SHA-256 manifest.

## Release APK

Release/platform signing intentionally fails closed when keys are absent. Pass
keys by ignored local properties or environment:

```bash
export SOLAR_PLATFORM_KEY_PK8=/secure/path/platform.pk8
export SOLAR_PLATFORM_KEY_PEM=/secure/path/platform.x509.pem
./gradlew :app:assembleRelease
```

The build creates an ignored local keystore under `.gradle/`; never commit it.
A debug-signed APK is not a substitute for a production/platform-signed
release.

## Y1 ROM

First verify the immutable base:

```bash
python3 solar-rom/scripts/y1-firmware-preflight.py verify-base \
  --variant a --archive /immutable/bases/rom.zip
```

Then build only the variant proven by the physical player's backup:

```bash
SOLAR_Y1A_BASE_ZIP=/immutable/bases/rom.zip \
  ./solar-rom/scripts/build-rom.sh a \
  --apk app/build/outputs/apk/release/app-release.apk \
  dist/y1-a/rom.zip
```

Use `b`, `SOLAR_Y1B_BASE_ZIP`, and `rom_type_b.zip` for Type B. Do not build or
flash a guessed variant. Follow [RECOVERY.md](RECOVERY.md) before any write.

