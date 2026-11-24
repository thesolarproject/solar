# Solar ROM builder (Innioasis Y1 + Y2 + Timmkoo A5)

Builds flashable **Y1 type A**, **Y1 type B**, **Y2 ATA**, and **A5 ATA** firmware images from stock bases, with **Solar** (`com.solar.launcher`) as the system launcher.

Release source: [github.com/thatwitchgirl/solar](https://github.com/thatwitchgirl/solar)

## Branches

| Branch | Releases | Versioning |
|--------|----------|------------|
| `nightly` | Every push | Tags `nightly-YYYYMMDD-HHMM` (UTC) — pre-release on GitHub |
| `main` | Every push | Tags `YYYYMMDD-HHMM` (same timestamp as nightly for the same commit; no prefix) — latest release on GitHub |

Both branches share the same `versionCode` (minutes since 2020-01-01 UTC). Day-to-day development targets **`nightly`**; **`main`** will become the stable channel later — for now both are timestamped unstable builds.

## Local build

```bash
./scripts/build.sh                    # signed app-release.apk

# All four Solar ROMs (Y1 A/B + Y2 + A5) — same entry point as CI:
./scripts/build-all-roms.sh

# Or build one type (ATA bases include preconfigured SP Flash Tool):
./solar-rom/scripts/build-rom.sh a --apk app/build/outputs/apk/release/app-release.apk dist/rom.zip
./solar-rom/scripts/build-rom.sh b --apk app/build/outputs/apk/release/app-release.apk dist/rom_type_b.zip
./solar-rom/scripts/build-rom.sh y2 --apk app/build/outputs/apk/release/app-release.apk dist/rom_y2.zip
./solar-rom/scripts/build-rom.sh a5 --apk app/build/outputs/apk/release/app-release.apk dist/rom_a5.zip

# Until ATA releases are public / correct, point at local base zips:
SOLAR_Y1A_BASE_ZIP=/path/to/rom.zip \
SOLAR_Y1B_BASE_ZIP=/path/to/rom_type_b.zip \
SOLAR_Y2_BASE_ZIP=/path/to/rom_y2.zip \
SOLAR_A5_BASE_ZIP=/path/to/rom_a5.zip \
  ./scripts/build-all-roms.sh
# Or REQUIRE_ALL_ROMS=1 to fail if any Y1/A5 ATA base is missing.
```

**Base URLs** (downloaded by `build-rom.sh` when no `SOLAR_*_BASE_ZIP` override):

| Type | Output | Base |
|------|--------|------|
| Y1 A | `rom.zip` | `y1-community/y1-ata-rom` … `/0.1/rom.zip` |
| Y1 B | `rom_type_b.zip` | `y1-community/y1-ata-rom` … `/0.1/rom_type_b.zip` |
| Y2 | `rom_y2.zip` | `ryan-specter/jj_auto` … `/y2-test/rom_y2.zip` (override `SOLAR_Y2_BASE_ZIP`) |
| A5 | `rom_a5.zip` | `y1-community/a5-ata-rom` … `/0.1/rom_a5.zip` |

Requires `curl`, `unzip`, `zip`, `openssl`, `sudo`, and loop-mount support for ext4 (`e2fsprogs`).

Each ROM includes, on the **system** partition:

| Path | Purpose |
|------|---------|
| `/system/app/com.solar.launcher.apk` | Solar launcher (platform-signed) |
| `/system/media/bootanimation.zip` | Solar boot animation |
| `/system/bin/bootanimation` | Boot animation player binary |
| `/system/lib/libconscrypt_jni.so` | Conscrypt JNI for TLS 1.2+ (Reach, podcasts, themes via OkHttp) |
| `/system/etc/security/cacerts/*.0` | Modern CA roots (Let's Encrypt, etc.) for **MediaPlayer** HTTPS and all apps |
| `/system/etc/solar/disable-rockbox-for-solar.sh` | One-shot `pm disable org.rockbox` on first boot (marker wiped on flash) |
| `/system/etc/init.d/99SolarInit.sh` | Boot: SD folders; default Solar IME + accessibility fallback; switch scripts + keymap/codec sync |
| `/system/bin/app_process` | Xposed-modified zygote (Dalvik framework) |
| `/system/bin/app_process.orig` | Stock zygote backup |
| `/system/framework/XposedBridge.jar` | Xposed bridge (persistent on /system) |
| `/system/xposed.prop` | Xposed version label |
| `/system/app/XposedInstaller.apk` | Xposed module manager |
| `/system/etc/init.d/99XposedInit.sh` | Boot: seed runtime jar into installer data dir |

**Xposed** is installed on **all ROM variants** (Y1 type A/B + A5 API 17, Y2 API 19) via `install-xposed-system.sh`. Lab devices without reflash: `solar-rom/scripts/install-xposed-adb.sh` or full overlay `apply-y1-rom-patches-adb.sh` / `apply-y2-rom-patches-adb.sh`. See [`solar-rom/patches/xposed/README.md`](patches/xposed/README.md).

**Y1 / Y2 / A5 ATA** — Y1 permissive `su` is baked into `/system` during `build-rom.sh` for ATA bases that need it (`install-y1-su-system.sh`). No Superuser.apk grant dialog. **`org.rockbox.apk` + `librockbox.so`** are not vendored in git — `fetch-rockbox-y1-y2-assets.sh` still downloads them from the rockbox-y1 type-A base (cached under `~/.cache/solar-rom-build/rockbox-y1-y2/`) for Y1 heal-if-missing and Y2 prep. **A5 omits Rockbox bake**. **AVRCP** runs on Y1 A/B (hard) and A5 (soft-skip on mismatch); Y2 skips.

Y1 type A/B also replace zip-root **`boot.img`** / **`logo.bin`** from `solar-rom/system/` (Solar branding). Y2 and A5 keep stock kernels/logos.

**Y1 A/B + A5 output zips** keep the full ATA tree including **SP Flash Tool** so users can download and flash without a separate tool pack.

### A5 intentional divergences (`rom_a5.zip`)

| Concern | A5 | vs Y1 |
|---------|----|-------|
| SoC / API | MT6572 / API 17 | Same family as Y1 (not Y2 MT6582) |
| Keylayouts | `A5-mtk.kl` → `mtk-kpd.kl`, `A5.kl` → Generic | `Y1-Rockbox.kl` wheel maps |
| Family pin | `persist.solar.device_family=a5` + `ro.product.model=A5` | Stock ATA may report `model=Y1` without pin |
| Rockbox / `99Y1ButtonScript` / `solar-rb-launch` | Skipped | Required on Y1 |
| Output zip | Full ATA tree + SP Flash Tool | Same (Y1 A/B also ship Flash Tool) |
| Base override | `SOLAR_A5_BASE_ZIP` | `SOLAR_Y1A_BASE_ZIP` / `SOLAR_Y1B_BASE_ZIP` |

These match what `./scripts/clean_install_system.sh` applies on a rooted device. Shared staging: `scripts/stage-y1-system-prep.sh` → `apply-y1-system-prep.sh` (ROM) or `push-y1-system-prep.sh` (adb). `SolarApplication` loads Conscrypt at boot; system cacerts are still required for stock HTTPS stacks (podcast streaming via MediaPlayer).

**First Solar system IME release:** ship all three ROM zips with a ROM-only OTA catalog row (`scripts/rom-only-entries.json` → `romOnly="true"` in `updates.xml`) so users on pre-IME builds must flash ROM; subsequent releases may ship APK + ROM normally. See [`.cursor/rules/layered-fallback.mdc`](../.cursor/rules/layered-fallback.mdc).

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

`.github/workflows/build-release.yml` runs on pushes to **`main`** and **`nightly`**: signs the release APK and builds **all four** ROM types via `scripts/build-all-roms.sh` — Y1 A (`rom.zip`), Y1 B (`rom_type_b.zip`), Y2 (`rom_y2.zip`), A5 (`rom_a5.zip`). Y2 is always required. Y1 A/B and A5 soft-skip only while their ATA base URLs 404 and no `SOLAR_*_BASE_ZIP` is set; once `y1-ata-rom` / `a5-ata-rom` release `0.1` is public, CI builds all four every time.

Required secrets: `SOLAR_PLATFORM_KEY_PK8_B64`, `SOLAR_PLATFORM_KEY_PEM_B64` (base64-encoded AOSP test platform key material).

**Y2 root:** vendored Y1 permissive `su` under `solar-rom/vendor/y1-su/` (~74 KiB). Commit for CI reproducibility. Regenerate:

```bash
./solar-rom/scripts/extract-y1-su-vendor.sh
```

After flashing `rom_y2.zip`, verify root: `adb shell su -c id` (expect `uid=0`, no permission dialog).

## Y2 roadmap

| Phase | Status | Work |
|-------|--------|------|
| **1 — Y1 permissive system root** | Done | Y1 rockbox `su` baked into loop-mounted `system.img` via `install-y1-su-system.sh` (no Superuser.apk) |
| **2 — APK input parity** | Done | Y2 Java keeps DPAD compensation as safety net; external-app input handoff enabled on both devices |
| **3 — Y2 keylayout unification** | Done | `Y2-Rockbox.kl` (wheel/side match Y1; volume on scancodes 114/115) installed in `build-rom.sh y2`; `mtk-kpd.kl` patched for wheel + side keys |

**Intentional Y1/Y2 app divergence:** Y2 quick menu hides Volume and Lock/sleep chips (hardware volume + sleep/lock buttons). Everything else (Rockbox switch, power/reboot/shutdown, dual storage) is shared.

**Non-goals for Y2:** AVRCP native patches (different MT6582 `MtkBt.odex`).
