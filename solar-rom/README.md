# Solar ROM builder (Innioasis Y1)

Builds flashable **type A** and **type B** firmware images from the [Rockbox-Y1](https://github.com/rockbox-y1/rockbox) stock bases, with **Solar** (`com.solar.launcher`) as the system launcher.

## Local build

```bash
./scripts/build.sh                    # signed app-release.apk
./solar-rom/scripts/build-rom.sh a --apk app/build/outputs/apk/release/app-release.apk rom.zip
./solar-rom/scripts/build-rom.sh b --apk app/build/outputs/apk/release/app-release.apk rom_type_b.zip
```

Requires `curl`, `unzip`, `zip`, `sudo`, and loop-mount support for ext4 (`e2fsprogs`).

## CI

`.github/workflows/build-release.yml` runs on pushes to `master`: signs the release APK (platform keys in repo secrets), builds both ROM zips, and publishes a GitHub release with `app-release.apk`, `rom.zip`, and `rom_type_b.zip`.

Required secrets: `SOLAR_PLATFORM_KEY_PK8_B64`, `SOLAR_PLATFORM_KEY_PEM_B64` (base64-encoded AOSP test platform key material).
