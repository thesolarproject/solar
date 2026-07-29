# Upgrade from an existing Solar installation

Solar's package name and established data model are retained. This branch does
not rename the application or require a replacement architecture.

## APK-first upgrade

1. Record the current Solar version and signing identity.
2. Preserve media directories and download `.part` files.
3. Build or obtain the private test APK and verify its hash.
4. Run `adb install -r app-debug.apk`.
5. Open Solar and let the library reconciliation finish before starting a
   second scan.
6. Check Downloads. Jobs that were active at process death should return as
   paused/recoverable; eligible Soulseek, podcast, and direct-download jobs can
   then resume.

The upgrade keeps existing preferences and library data when Android accepts
the signing identity. New settings use safe defaults, including enabled Wi-Fi
auto-connect, enabled Bluetooth auto-reconnect, Wi-Fi-only transfer recovery,
and metadata-only YouTube behavior.

## Verification after upgrade

- Existing local tracks and playlists remain visible.
- Now Playing and queue state are coherent.
- A newly imported or downloaded track appears through targeted indexing.
- Wi-Fi and Bluetooth diagnostics open without exposing credentials.
- Search/listening history can be cleared.
- Rockbox is only launched if the existing installation is present and the
  hardware key-map test has passed.

## ROM upgrade

Do not use a ROM merely to test application behavior. A ROM upgrade additionally
requires:

- a complete verified backup from this physical player;
- proven Type A or Type B identity;
- an audited base archive;
- authorized platform signing keys;
- a successful physical recovery drill.

See [RECOVERY.md](RECOVERY.md) and
[Y1-HARDWARE-TEST.md](Y1-HARDWARE-TEST.md).

