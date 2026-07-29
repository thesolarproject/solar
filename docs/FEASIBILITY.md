# Solar Classic feasibility and platform audit

Status: host implementation verified; physical/release gates remain

Audit date: 2026-07-28
Target: Innioasis Y1 first; Y2 and A5 are out of scope unless explicitly stated

This document records facts that were verified before Solar Classic code was
changed. It intentionally separates observed facts from assumptions. A physical
Y1 was not attached during this audit, so no hardware-only result is presented
as verified.

## Executive decision

Solar Classic is feasible as an API 17 launcher/application and as a set of
carefully separated Y1 Type A and Type B ROM builds. The existing repository
already contains substantial library, playback, Soulseek, Wi-Fi, Bluetooth,
wheel-input, updater, and ROM-building work that should be adapted rather than
replaced.

The following release gates remain:

1. The repository has no root `LICENSE` or `COPYING` file and GitHub reports no
   detected license. Source changes can be developed and tested locally, but a
   public fork, APK, or ROM redistribution needs permission or a repository
   license from the copyright holder.
2. The bundled IJK/FFmpeg native library exposes x264 strings, while the
   repository contains neither the corresponding source/configuration nor a
   written-source offer. It must not be treated as a legally cleared
   transcoder. Solar Classic conversion must remain disabled until a
   reproducible, ABI-compatible, legally distributable encoder is supplied.
3. Y1 Type A and Type B cannot be distinguished safely from Android build
   properties. Their boot-critical images differ. Flashing must require an
   explicit variant and a verified backup; it must never guess or use
   “try A first.”
4. Exact RAM, negotiated Bluetooth behavior, decoder reliability, wheel timing,
   suspend/resume behavior, and safe media profiles still require physical Y1
   testing.
5. JDK and Android SDK tooling were bootstrapped outside the checkout. The
   repository-wide unit tests, lint gate, debug APKs, platform-asset verifier,
   and firmware-preflight tests now pass. There is still no attached physical
   Y1, authorized platform signing material, audited local base archive, or
   privileged Linux loop-mount runtime. A signed release/ROM therefore remains
   correctly unavailable.

## Repository audit

Audited revision:

```text
repository: https://github.com/thesolarproject/solar
branch created for this work: feature/solar-classic-y1
starting commit: 8c912ca
```

The Gradle project contains:

- `app`: the main Solar launcher/player
- `launcher-helper`
- `global-context-modal`
- `solar-overlay-ui`
- `solar-ota-lib`
- `solar-updater`
- `solar-rom`: ROM construction, system overlays, and verification scripts

The main application is a Java/Android Views project:

| Property | Verified value |
| --- | --- |
| Compile SDK | 35 |
| Minimum SDK | 17 |
| Target SDK | 35 |
| Android Gradle Plugin | 8.6.1 |
| Gradle wrapper | 8.9 |
| Java compatibility | Java 8 bytecode |
| Primary device ABI | `armeabi-v7a` |
| HTTP client | OkHttp 3.12.13, the API-17-compatible line |
| TLS provider | Conscrypt Android 2.5.3 |
| Metadata library | jaudiotagger 3.0.1 |

The release workflow uses JDK 21, installs Android platforms 35 and 19, builds
the application modules, and invokes the ROM builder on Linux. Release signing
material is expected from CI secrets. Private keys must remain outside source
control.

The source tree already has extensive functionality and tests. In particular:

- `MainActivity` coordinates the existing hierarchical launcher, library,
  playback, Get Music, Wi-Fi, Bluetooth, wheel focus, and overlays.
- `SoulseekClient` and the Reach UI implement real Soulseek account, search,
  browse, queue, download, and history behavior.
- `MusicLibraryStore` and related scanners implement local library persistence.
- `SolarWheelKeyboardController` provides a wheel-controlled character ring or
  shorter grouped pages, backed by shared cursor/editing primitives. See
  `docs/WHEEL-KEYBOARD.md` for the deterministic layout comparison.
- The ROM builder supports separately named Y1 Type A and Type B inputs and
  also contains Y2/A5 paths.

This favors narrow interfaces and adapters around working code. Moving all
existing behavior into a new framework would add risk on a 96–128 MiB Dalvik
heap and is not justified.

### GitHub state considered

Open work was inspected before implementation. Relevant items at audit time
included:

- Issue #17: Get Music cannot scroll beyond the first results.
- Issue #25: Bluetooth volume jumps after reconnect.
- Issue #31: Rockbox is missing; the maintainer states that dual boot is being
  removed because the key layout is not reliable.
- Issues #6, #7, #9, #12, #13, #19, #20, #24, #30, #32, and #33 cover library,
  playback, Rockbox, lyrics, scrobbling, AutoEQ, Y2, server sources, and home
  navigation.
- PR #35 changes overlay-shell routing and targets `main`.
- PR #29 changes USB mass-storage remount behavior and targets `nightly`; GitHub
  reports it as dirty.

Solar Classic must therefore retain Rockbox switching only after a physical
Y1 proves that the selected Type A/B key map is reliable. It must not promise
the switch merely because scripts exist.

## Verified Y1 platform

Two published Y1 base archives were downloaded outside the repository and
inspected read-only. They were never used as user backups and were not modified.

### Archive identity

| Archive | SHA-256 |
| --- | --- |
| Y1 Type A `rom.zip` | `D3B79CA460A3D3EC2C41BA57893127A0F9A90278E374DD33CDB5DE5BB46E367E` |
| Y1 Type B `rom_type_b.zip` | `B7956FDDD768F5FF4AE9A84C4F33820A0EAC9919D02FE4F315C1E83785E7CB88` |

Both images report:

| Property | Verified value |
| --- | --- |
| Android | 4.2.2 |
| API level | 17 |
| Build family | `JDQ39`, `ALPS.JB3.MP.V1` |
| SoC/board | MediaTek MT6572 / Y1 |
| CPU ABI | `armeabi-v7a`, secondary `armeabi` |
| OpenGL ES | 2.0 |
| Dalvik heap growth limit | 96 MiB |
| Dalvik maximum heap | 128 MiB |
| Wi-Fi interface | `wlan0` |
| Internal media path | `/storage/sdcard0` |
| Removable media path | `/storage/sdcard1` |

The heap settings are not the amount of installed RAM. Total RAM and normal
free memory remain unverified until `/proc/meminfo`, `dumpsys meminfo`, and
runtime heap telemetry are collected on a real device. Existing Solar budgeting
code models a roughly 256 MiB device with about 64 MiB normally available; that
is a conservative implementation assumption, not a measured result.

The official Y1 pages describe an unlocked bootloader and disagree on Bluetooth
marketing version (4.0 versus 4.2). Software cannot upgrade the radio. Solar
Classic reports only profiles/codecs actually exposed by the platform and does
not advertise a Bluetooth generation.

Official references:

- [Innioasis Y1 product specifications](https://www.innioasis.com/products/y1)
- [Innioasis Y1 overview](https://www.innioasis.com/pages/y1)
- [Innioasis Y1 flashing tutorial](https://support.innioasis.com/download/flashing_tutorial/Flashing_tutorial-Y1_EN%20v2.0.7-20241021.pdf)

### Type A and Type B are not interchangeable

The two archives use the same partition map and expose the same inspected
`build.prop` values, but their boot-critical payloads differ:

| Image | Type A SHA-256 | Type B SHA-256 |
| --- | --- | --- |
| `boot.img` | `A289B10E8154AEA80252CB517C29FE8A598EE657B12A6E460DA0FB2CCB7C534B` | `9EF0851F4686263F3DA153B0E61AF37639ACF8A56AB93D6F5372B81B00FCF5D5` |
| `lk.bin` | `BDA26D8DB9C8D9097547B815205EB65B351562B3EDEAA7810884731BF5D857ED` | `47FBB6E07E0BAF8893F2D42057AD28047402120B0C0FC7678ACDAB98C3CEB0C8` |
| `preloader.bin` | `F2A8A8B72C944C2BB7356C9162B7A40F76F48006E96E021EBB4BB214EB6C4C80` | `2F5F421D78A108024BD686B80EF473E147097311C5A61E2FA6389ED6B868AF6A` |
| `recovery.img` | `70760BC5B91B33B7F517C9F5C26AB65F9E3573659F4F980A0F608EDF490E53E4` | `0C5D9B8E5054DC4A8680F5578D1324639A04831C35D422C8C9265A788B09C993` |
| `system.img` | `A71CD2F7A976A4BA1B89F819FE66989E079F507BDE283628ADB5FDF6EE8E78D8` | `06DB70A963FB97B65217B12AB41FF3000FC7D0625BFC5C780ADF8C3D9178DBB9` |
| scatter file | `29390D04D90368A2FDFDF28913E8DCFF8BA139B4487AFA0E92ED2B9B1EC26866` | `29390D04D90368A2FDFDF28913E8DCFF8BA139B4487AFA0E92ED2B9B1EC26866` |

The kernel/bootloader/system differences and a differing `Generic.kl` show that
“try one and then the other” is unsafe advice. The existing root README
instruction to try Type A first must be replaced by explicit identification and
backup requirements.

### Audio and video capability

The stock codec registry declares decoders for:

- Audio: MP3, AMR-NB/WB, ADPCM, PCM, AAC, G.711, Vorbis, FLAC, WMA, and APE.
- Video: MPEG-4, H.263, AVC/H.264, VP8, VC-1/WMV, DivX/DivX3, Xvid, and MJPEG.

The stock encoder registry declares MPEG-4/H.263/AVC video and
AMR/AWB/AAC/Vorbis/ADPCM/FLAC audio encoders. A registry entry is not proof that
all bitrates, profiles, containers, tags, artwork sizes, or malformed files work
reliably.

The official product page lists a wider set of containers and advertises 720p
video. For Solar Classic, the conservative verified-by-software audio target is
MP3, 44.1 kHz, stereo, with simple ID3 metadata. A final bitrate, ID3 version,
artwork limit, and any video conversion profile remain behind a hardware-test
gate. Compatible originals should be retained without needless conversion.

The stock audio policy exposes 16-bit stereo 44.1 kHz primary and A2DP outputs.
Its Bluetooth configuration enables one SBC source and disables MPEG-1/2
sources. Only A2DP/SBC and AVRCP controls can currently be claimed. AAC, aptX,
LDAC, negotiated-codec reporting, absolute volume, and reconnect behavior need
device/headset testing.

### TLS and networking

Android 4.2.2's system TLS and trust store are too old for many current
services. The app already uses an API-17-compatible OkHttp release and Conscrypt,
which should remain the shared connection stack. All network providers need:

- bounded executors and connection reuse,
- explicit connect/read/write timeouts,
- no UI-thread I/O,
- exponential backoff with jitter,
- cancellable requests,
- redacted release logging,
- offline cache behavior where permitted.

Do not globally disable certificate validation or hostname verification.

### Media scanner behavior

Internal and removable storage are distinct volumes. Job completion must use an
atomic rename on the destination volume and request a targeted media scan for
the completed file. A full library rebuild after each job is unnecessary.
Cross-volume “rename” is not atomic and must be implemented as verified copy,
sync, then rename/delete according to the user's original-retention setting.

## Existing features to reuse

### Soulseek / Reach

Existing Soulseek behavior is substantial and should be wrapped in a provider
adapter. The audit found account setup, connection state, search, incremental
results, peer folder browsing, transfer queue/history, partial files, and
download plumbing. The adapter must add a stable provider contract, shared job
state, normalized errors, bounded concurrency, filename/storage policy, and
release-log redaction without creating a second protocol client.

### Library and playback

Existing playback, queues, hierarchical library data, media scanning, wheel
focus, Bluetooth media handling, and local persistence should be retained.
Solar Classic additions should submit targeted index operations and avoid
blocking the UI on tag or artwork work.

### Wi-Fi and Bluetooth

Solar already has custom Wi-Fi and Bluetooth UI/state handling. Improvements
belong behind testable state presenters and diagnostics rather than routing
users to raw Android settings. Passwords, tokens, SSIDs marked private, and
Soulseek credentials must be excluded from diagnostic bundles.

## YouTube compliance finding

The existing `YouTubeClient`, `YouTubeDownloader`, and related UI use
third-party Invidious/Piped instances to resolve and download audiovisual stream
URLs. That path conflicts with Solar Classic's authorized-acquisition rules and
must be disabled for remote YouTube items.

Solar Classic's YouTube provider is metadata-only:

- use Google's OAuth 2.0 Device Authorization flow for TVs/limited-input devices;
- request the minimum YouTube read-only scope;
- use the official YouTube Data API for permitted metadata;
- store bookmarks and cached metadata locally;
- offer a Soulseek-title search or user/creator-supplied direct import as
  separate providers;
- never resolve, play, save, transcode, or expose extracted YouTube media URLs.

Official design references:

- [OAuth 2.0 for TVs and limited-input devices](https://developers.google.com/youtube/v3/guides/auth/devices)
- [YouTube Data API search](https://developers.google.com/youtube/v3/docs/search/list)
- [YouTube Data API subscriptions](https://developers.google.com/youtube/v3/docs/subscriptions/list)
- [YouTube Data API getting started and quota](https://developers.google.com/youtube/v3/getting-started)

The distributed device contains an OAuth client ID, not a client secret treated
as confidential. The owner supplies the ID through ignored local configuration
or CI secret. Device-flow states must explicitly handle pending, slow-down,
denied, expired, network failure, refresh, revocation, and sign-out.

Android 4.2 does not provide the modern AES Android Keystore facilities used on
new devices. A platform-signed private app sandbox can prevent ordinary apps
from reading credentials, but it does not protect against root or offline
firmware access. The UI and setup guide must disclose this limitation. Tokens
must never be logged or included in backups/diagnostic bundles.

Discover is a deterministic local ranking layer over cached public/account
metadata and private on-device library signals. It is not and must not be called
the YouTube Home feed. Local listening history stays on device unless the user
separately opts into a future service.

## Transcoding and dependency licensing

No cleared conversion engine was found.

The application bundles `libijkffmpeg.so`, `libijkplayer.so`, and
`libijksdl.so`. The current notice describes the native payload as Apache-2.0,
but a binary string audit exposes x264 and the repository lacks a matching
FFmpeg configuration, source tree, or source offer. Because x264 is GPL-licensed
and FFmpeg licensing depends on its build configuration, these binaries cannot
be repurposed as a Solar Classic encoder based only on the existing notice.

Before conversion can ship:

1. Select or build an API-17/armeabi-v7a encoder with known patent and software
   licensing consequences.
2. Record every configure flag, source revision, patch, and toolchain version.
3. Provide all notices/source obligations and reproduce the binary.
4. Measure peak RSS, thermal behavior, battery use, cancellation, and output
   playback on both Y1 variants.
5. Enable conversion profiles only after physical compatibility tests.

Until then, compatibility decisions may preserve or reject/import files, but a
“Convert” request must fail closed with a precise unavailable reason. It must
not silently copy or pretend to convert.

Other direct dependencies also need a release bill of materials. In particular,
jaudiotagger's LGPL obligations and every vendored APK/JAR/SO need provenance
and redistribution review. Absence of a root project license is the first
release blocker.

## Solar Classic architecture

The implementation uses small Java interfaces compatible with API 17:

```text
wheel-first Get Music UI
        |
        v
ProviderRegistry ----> Soulseek adapter
        |              Official YouTube metadata provider
        |              Direct HTTP / user-owned import provider
        |              Local-file ingest provider
        v
Shared persistent JobStore -> download/import -> verify -> compatibility
        |                                             |
        +---------------- retry/pause -----------------+
                                                      v
                                      targeted media-library index
```

Key boundaries:

- Provider metadata and authentication stay outside activities.
- Acquisition options are explicit capabilities; a metadata result cannot
  accidentally acquire media.
- One persistent job state machine owns downloads, imports, verification,
  future conversion, and indexing.
- A compatibility policy is separate from a transcoder implementation.
- UI presenters consume immutable snapshots and publish incremental updates.
- Existing Soulseek/library services are adapted, not duplicated.
- API clients accept clocks, transports, and stores so unit tests use fakes.

The first UI increment remains text-only, recycler/list based, and wheel-first.
It must preserve query/focus state, coalesce wheel events, and never decode
artwork while navigating.

## Performance budgets pending device profiling

Initial engineering budgets, to be revised from device telemetry:

| Operation | Initial budget |
| --- | --- |
| Warm home screen usable | under 1.0 s |
| Wheel-to-focus feedback | under 50 ms |
| Normal menu transition | under 100 ms |
| Incremental first search page | render each received batch; no wait for all pages |
| UI-thread disk/network work | 0 operations |
| Active network jobs | default 1, configurable only after profiling |
| In-memory result page | bounded; discard/reload distant pages |
| Artwork during list navigation | none |

Diagnostics must measure process start, first usable frame, wheel dispatch,
incremental scan/database batches, provider latency, job transitions, Java heap,
and native RSS. These are targets, not measured results.

## Build and test environment

Observed local environment:

- Windows PowerShell and Git for Windows are available.
- No JDK, Android SDK, or `adb` is currently on `PATH`.
- WSL is installed only as a launcher; no usable distribution/kernel is
  available.
- Docker Desktop is installed but its Linux daemon is not running.
- The ROM builder requires a Linux host with root/loop-mount support.
- No Y1 is attached.

Therefore:

- Pure JVM-compatible code can be reviewed and tested after bootstrapping the
  documented Android/JDK toolchain.
- Debug/release APKs can be produced without flashing.
- ROM images must be built on a clean privileged Linux runner from pinned,
  hash-verified Type A/B bases.
- No ROM or hardware claim is release-ready until the recovery preflight and
  physical checklist pass.

## Required follow-up evidence

Before a public release, collect and attach to the release checklist:

- full partition backup manifest and hashes from each physical Y1;
- positive hardware variant identification evidence;
- `/proc/meminfo`, `dumpsys meminfo`, and startup/scroll traces;
- 100+ reboot/suspend/resume and interrupted-job trials;
- codec matrix using known-good and adversarial media;
- A2DP/AVRCP matrix including SBC-only devices, AirPods 4, a speaker, and common
  earbuds;
- Wi-Fi authentication, DHCP, reconnect, TLS, and captive/failure cases;
- wheel-key map and long-press tests for each variant;
- rollback from both a working build and an intentionally non-booting system
  image using preserved recovery/bootloader paths;
- license/redistribution permission and a complete dependency/native-code SBOM.

Until those records exist, documentation and UI must label the corresponding
features “hardware test required,” not “verified.”
