# Solar ROM builder (Innioasis Y1 + Y2)

Builds flashable **Y1 type A**, **Y1 type B**, and **Y2 ATA** firmware images from stock bases, with **Solar** (`com.solar.launcher`) as the system launcher.

Release source: [github.com/thatwitchgirl/solar](https://github.com/thatwitchgirl/solar)

## Branches

| Branch | Releases | Versioning |
|--------|----------|------------|
| `nightly` | Every push | Tags `nightly-{N}` — **CI assigns N** as latest git tag + 1 (local `app/build.gradle` is only a dev hint) |
| `main` | Stable | Tags `v0.1`, `v0.2`, … — **CI assigns** next +0.1 from latest `v0.*` release tag |

Day-to-day development targets **`nightly`**. Merge to **`main`** when cutting a stable release.

## Local build

```bash
./scripts/build.sh                    # signed app-release.apk
./solar-rom/scripts/build-rom.sh a --apk app/build/outputs/apk/release/app-release.apk rom.zip
./solar-rom/scripts/build-rom.sh b --apk app/build/outputs/apk/release/app-release.apk rom_type_b.zip
./solar-rom/scripts/build-rom.sh y2 --apk app/build/outputs/apk/release/app-release.apk rom_y2.zip
```

Requires `curl`, `unzip`, `zip`, `openssl`, `sudo`, and loop-mount support for ext4 (`e2fsprogs`).

Each ROM includes, on the **system** partition:

| Path | Purpose |
|------|---------|
| `/system/app/com.solar.launcher.apk` | Solar launcher (platform-signed) |
| `/system/media/bootanimation.zip` | Solar boot animation |
| `/system/bin/bootanimation` | Boot animation player binary |
| `/system/lib/libconscrypt_jni.so` | Conscrypt JNI for TLS 1.2+ (Reach, podcasts, themes via OkHttp) |
| `/system/etc/security/cacerts/*.0` | Modern CA roots (Let's Encrypt, etc.) for **MediaPlayer** HTTPS and all apps |
| `/system/etc/init.d/99SolarInit.sh` | Boot: create `Music` / `Podcasts` / `Themes` on SD; log if TLS prep missing |

The ROM zip root also ships **`boot.img`** and **`logo.bin`** from `solar-rom/system/`.

These match what `./scripts/clean_install_system.sh` applies on a rooted device. Shared staging: `scripts/stage-y1-system-prep.sh` → `apply-y1-system-prep.sh` (ROM) or `push-y1-system-prep.sh` (adb). `SolarApplication` loads Conscrypt at boot; system cacerts are still required for stock HTTPS stacks (podcast streaming via MediaPlayer).

## Device install (rooted, without full ROM flash)

```bash
./scripts/build.sh
./scripts/clean_install_system.sh    # full: remove old launchers + APK + TLS prep
# or quick update:
./scripts/install.sh --system
./scripts/install_modern_cacerts.sh  # cacerts only
```

Override release repo for ROM downloads: `SOLAR_GITHUB_REPO=thatwitchgirl/solar` (default).

## CI

`.github/workflows/build-release.yml` runs on pushes to **`main`** and **`nightly`**: signs the release APK, builds Y1 ROM zips (`rom.zip`, `rom_type_b.zip`), and publishes a GitHub release.

**Y2 ATA** (`build-rom.sh y2` → `rom_y2.zip`) is intentionally **not** built in CI until the Innioasis Y2 device ships. The script and firmware URL remain in the repo for future support.

Required secrets: `SOLAR_PLATFORM_KEY_PK8_B64`, `SOLAR_PLATFORM_KEY_PEM_B64` (base64-encoded AOSP test platform key material).
