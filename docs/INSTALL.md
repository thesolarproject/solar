# Install a private test APK

Prefer APK testing before any firmware flash.

## Before installing

1. Preserve all music and any irreplaceable data.
2. Complete the backup steps in [RECOVERY.md](RECOVERY.md) before modifying a
   system-app installation.
3. Confirm the APK came from the expected commit and record its SHA-256.
4. Keep the stock launcher/recovery path available.

## Install or update

With Android debugging enabled and the device visible in `adb devices`:

```bash
adb install -r /absolute/path/to/app-debug.apk
```

This preserves app data only when the installed package has the same signing
identity. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop. Do not
remove the only working system launcher merely to force a debug install. Use a
spare device, emulator, or a build signed with the device's authorized platform
key.

Launch Solar normally and test the non-privileged flows first:

- wheel navigation and keyboard;
- library browse and playback;
- Wi-Fi UI and diagnostics;
- Soulseek search/queue with content you may lawfully acquire;
- podcast and authorized direct-download resume;
- YouTube metadata/Discover, if owner-supplied API credentials are configured.

Privileged global overlays, protected settings, system Bluetooth behavior, and
launcher switching require a matching system installation. The separately
built `global-context-modal-debug.apk` is not silently installed by the main
APK.

## Uninstall/rollback

For an ordinary user-installed test APK:

```bash
adb uninstall com.solar.launcher
```

Do not run that command on a device where Solar is the only working launcher.
For a system/ROM installation, use the selective rollback procedure in
[RECOVERY.md](RECOVERY.md).

