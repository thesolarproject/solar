# Innioasis Y1 backup, installation, and recovery

Status: procedure implemented; physical Type A and Type B recovery drill still
required before release

This guide is intentionally conservative. The Y1 Type A and Type B Android
properties are the same, while their bootloader, kernel, recovery, system, and
key-layout payloads differ. Never identify a device by trying one ROM and then
the other.

The repository's preflight tool is read-only. It cannot flash or erase a device:

```bash
python3 solar-rom/scripts/y1-firmware-preflight.py --help
```

## Stop conditions

Do not flash when any of these is true:

- the player is not positively identified as Innioasis Y1;
- Type A/Type B is unknown or the backup preflight cannot identify it;
- the selected ROM type differs from the backup manifest;
- a backup file fails its SHA-256 check;
- the scatter layout differs from the audited MT6572 layout;
- the battery is low or USB is unreliable;
- recovery/boot-ROM connection has not been demonstrated;
- the ROM archive or its base fails `y1-firmware-preflight.py`;
- the archive contains unreviewed preloader/NVRAM images;
- the host is about to use “Format All + Download.”

“Format All” can destroy NVRAM/calibration/identity data. It is not part of the
Solar Classic install or recovery procedure.

## 1. Prepare a dedicated backup directory

Use a new directory for one physical player. Never reuse or overwrite an older
backup.

Linux/macOS:

```bash
backup_root="$PWD/y1-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 "$backup_root"
```

PowerShell:

```powershell
$Y1BackupRoot = Join-Path $PWD ("y1-backup-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))
New-Item -ItemType Directory -Path $Y1BackupRoot
```

Keep this directory outside the Solar repository. It contains private user data,
Wi-Fi state, account state, device identifiers, and possibly personal media.
Store a second encrypted offline copy.

## 2. Install and inspect MTKClient

Use a pinned revision of [bkerler/mtkclient](https://github.com/bkerler/mtkclient)
and record its commit in `tool-versions.txt`. Follow its platform-specific driver
setup. Its current official usage guide documents:

- `python mtk.py printgpt` to display the partition table;
- `python mtk.py rl out` to dump all named partitions;
- `python mtk.py rf flash.bin` to dump full flash;
- `python mtk.py r ...` for selected partitions;
- `python mtk.py w ...` and `wl` for restore.

Source: [MTKClient usage reference](https://github.com/bkerler/mtkclient/blob/main/README-USAGE.md).

Power the Y1 off before connecting it in boot-ROM/preloader mode. Save the exact
console output. Do not continue if MTKClient reports a different SoC or an
unexpected partition map.

## 3. Capture the partition map and two independent backups

The examples below assume the current directory is the pinned MTKClient checkout
and the new backup directory is available as `$Y1_BACKUP_ROOT` (shell) or
`$Y1BackupRoot` (PowerShell).

Linux/macOS:

```bash
python3 mtk.py printgpt 2>&1 | tee "$Y1_BACKUP_ROOT/printgpt.txt"
python3 mtk.py rl "$Y1_BACKUP_ROOT/partitions"
python3 mtk.py rf "$Y1_BACKUP_ROOT/flash.bin"
python3 mtk.py r preloader "$Y1_BACKUP_ROOT/preloader.bin" --parttype=boot1
```

PowerShell:

```powershell
python mtk.py printgpt *>&1 | Tee-Object -FilePath (Join-Path $Y1BackupRoot "printgpt.txt")
python mtk.py rl (Join-Path $Y1BackupRoot "partitions")
python mtk.py rf (Join-Path $Y1BackupRoot "flash.bin")
python mtk.py r preloader (Join-Path $Y1BackupRoot "preloader.bin") --parttype=boot1
```

The full-flash dump is independent recovery evidence. The individual partition
set is easier and safer to restore selectively. Both are required.

Confirm that the named dump contains at least:

```text
preloader  MBR  EBR1  PRO_INFO  NVRAM  PROTECT_F  PROTECT_S
SECCFG     UBOOT/LOADER  BOOTIMG  RECOVERY  SEC_RO  MISC
LOGO       EXPDB  ANDROID/SYSTEM  CACHE  USRDATA  FAT
```

Do not “fill in” a missing unique partition from an internet ROM.

## 4. Identify the variant and seal the backup

The current audited Type A/Type B fingerprints are stored in
`solar-rom/config/y1-supported-bases.json`. They differ in `boot`, `uboot/lk`,
and `recovery`. The preflight requires at least two independent matches and
refuses mixed evidence.

Run once with the variant stated by the player's original firmware/source:

```bash
python3 /path/to/solar/solar-rom/scripts/y1-firmware-preflight.py \
  create-backup-manifest \
  --variant a \
  --backup-dir "$Y1_BACKUP_ROOT" \
  --output "$Y1_BACKUP_ROOT/manifest.json" \
  --device-note "physical label only; no serial or credentials"
```

Use `--variant b` only for a known Type B player. The command refuses to
overwrite an existing manifest. If it cannot prove the selected variant, stop;
the device is unsupported until its stock images are audited.

Verify the backup again from the offline copy:

```bash
python3 /path/to/solar/solar-rom/scripts/y1-firmware-preflight.py \
  verify-backup \
  --manifest /offline/y1-backup/manifest.json \
  --backup-dir /offline/y1-backup
```

Keep `manifest.json`, `printgpt.txt`, the MTKClient commit, and the full console
log with both copies.

## 5. Prefer APK development

Launcher/UI work does not need a ROM flash on every iteration.

1. Build a debug APK.
2. Enable USB debugging on a development Y1.
3. Install/update only `com.solar.launcher` with `adb install -r`.
4. Keep the stock launcher and recovery available until the build passes the
   hardware checklist.
5. Do not automatically install from a normal Gradle build.

Platform-signed or privileged-only features may need the ROM or a rooted
system-app installation, but ordinary UI, parsing, persistence, and most
network behavior should be tested through the APK path first.

## 6. Verify the selected ROM before installation

The ROM builder now pins Y1 bases automatically:

```bash
python3 solar-rom/scripts/y1-firmware-preflight.py verify-base \
  --variant a \
  --archive /immutable/bases/rom.zip

python3 solar-rom/scripts/y1-firmware-preflight.py verify-base \
  --variant b \
  --archive /immutable/bases/rom_type_b.zip
```

Build only the proven variant:

```bash
SOLAR_Y1A_BASE_ZIP=/immutable/bases/rom.zip \
  ./solar-rom/scripts/build-rom.sh a \
  --apk app/build/outputs/apk/release/app-release.apk \
  dist/y1-a/rom.zip
```

or:

```bash
SOLAR_Y1B_BASE_ZIP=/immutable/bases/rom_type_b.zip \
  ./solar-rom/scripts/build-rom.sh b \
  --apk app/build/outputs/apk/release/app-release.apk \
  dist/y1-b/rom_type_b.zip
```

Unknown/custom base archives fail closed. Audit and add a new pinned entry
instead of bypassing the check.

Before flashing, extract into a new directory, hash every image, compare its
scatter to the captured device map, and confirm:

- build variant equals backup-manifest variant;
- no release file will write `NVRAM`, `PRO_INFO`, `PROTECT_F`, `PROTECT_S`,
  `SECCFG`, or the device's unique FAT/media partition;
- preloader is not selected for a normal Solar update;
- recovery remains present;
- the original updater-compatible archive is preserved unchanged.

## 7. Installation policy

Use the official [Innioasis Y1 flashing
tutorial](https://support.innioasis.com/download/flashing_tutorial/Flashing_tutorial-Y1_EN%20v2.0.7-20241021.pdf)
for the physical connection and vendor tool. Select “Download Only,” the exact
audited scatter, and only the partitions intended by the reviewed release.

Do not select preloader for a routine Solar update. Do not cross-flash Type A
and Type B. Do not disconnect until the tool reports completion.

The Innioasis Updater path may be used only after the exact Solar Classic
archive has passed an updater install/rollback drill on the same variant.

## 8. Selective rollback

Use the individual files from the verified backup, never files from the other
variant or another player.

1. Re-run `verify-backup`.
2. Re-run `printgpt` and compare the partition map with the saved copy.
3. Start with the smallest restore that can fix the problem:
   - application/UI regression: restore/install the prior APK;
   - bad system image: restore the backed-up `android/system` partition;
   - bad kernel: restore the backed-up `boot/bootimg` partition;
   - damaged recovery: restore the backed-up `recovery` partition;
   - bad logo only: restore the backed-up `logo` partition.
4. Use MTKClient's `w partition file` syntax with the exact partition/file pair
   from this device.
5. Reset, disconnect, and boot-test before widening the restore.

Example shape (names must match the saved GPT and dump):

```bash
python3 mtk.py w bootimg "$Y1_BACKUP_ROOT/partitions/bootimg.bin"
python3 mtk.py reset
```

Do not paste a multi-partition write command until every argument has been
matched to the manifest. Do not write preloader, NVRAM, calibration, or identity
partitions during a normal rollback.

## 9. Last-resort restore

The verified `flash.bin` exists for catastrophic recovery, but full-flash writes
are the highest-risk path because they include device-unique and boot-critical
regions. Use `wf` only when:

- selective recovery is impossible;
- the full dump has just passed manifest verification;
- it was captured from this exact player;
- the partition map and eMMC size match;
- boot-ROM connectivity is stable;
- a physical Type A/Type B full-restore drill has validated the process.

This project has not yet completed that physical drill, so the full-flash path
is a recovery asset, not a release-verified promise.

## 10. Post-install verification

Before accepting a build:

- boot to Solar ten times from cold power-off;
- confirm recovery/boot-ROM access still works;
- confirm wheel, Back, Select, Play/Pause, Previous, and Next;
- verify internal and removable storage without formatting either;
- play known-good MP3 and FLAC locally;
- scan a large library and add one file incrementally;
- connect Wi-Fi, test TLS/API diagnostics, then test offline startup;
- pair an SBC-only A2DP device and verify AVRCP/volume/reconnect;
- interrupt and resume a download;
- install the previous APK without reflashing;
- perform the selective rollback drill and verify user media remains intact.

Record exact build hashes and results in `docs/Y1-HARDWARE-TEST.md`. A test that
has not been run must be marked “not run,” never inferred from emulator or source
inspection.
