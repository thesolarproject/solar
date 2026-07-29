# Innioasis Y1 physical test checklist

Nothing in this file is marked passed until it is observed on a real player.
Test Type A and Type B separately.

## Evidence to record

- device label and proven variant;
- stock/current firmware version and Android build fingerprint;
- APK commit, version, file size, and SHA-256;
- internal/removable storage sizes and free space;
- `/proc/meminfo` and `dumpsys meminfo com.solar.launcher`;
- headset/speaker model and Bluetooth behavior;
- logs for every failure, with credentials redacted.

## 1. Recovery gate

1. Capture the GPT, named partitions, full flash, and preloader as described in
   [RECOVERY.md](RECOVERY.md).
2. Create and then verify the backup manifest from the offline copy.
3. Confirm at least two boot-critical fingerprints identify one variant.
4. Demonstrate boot-ROM/recovery connectivity after a normal reboot.
5. Do not continue to ROM testing if any hash or partition is missing.

Expected: the preflight identifies exactly one variant and never modifies a
partition.

## 2. APK boot and stability

1. Install/update without clearing data.
2. Cold boot 10 times; warm-launch 20 times.
3. Sleep/wake repeatedly and rotate through every top-level menu.
4. Leave playback running for two hours, including screen-off time.

Expected: no launcher loop, ANR, stuck overlay, duplicate worker, or lost
recovery/stock-launcher route.

## 3. Wheel, keys, and keyboard

- Traverse 1,000+ rows slowly and with accelerated rotation.
- Verify Back, long Back, Center, long Center/Menu, Play/Pause, Previous, Next.
- Enter a Wi-Fi password containing upper/lowercase, numbers, spaces, and
  symbols; toggle visibility and cancel without saving.
- Enter searches with ring and grouped-page layouts; test delete word, cursor
  movement, hold acceleration, confirm, and cancel.
- Confirm password fields never show predictions.

Expected: one focus move per intended detent at low speed, bounded acceleration,
clear focus chrome, no double activation, and no lost query after Back.

## 4. Wi-Fi

- Scan and connect to open and WPA2 networks.
- Enter a wrong password, then correct it without rebooting.
- Verify saved indication, forget, disconnect/reconnect, DHCP/IP, signal, DNS,
  and API-connectivity diagnostics.
- Disable Wi-Fi during Soulseek, podcast, and direct HTTP downloads; re-enable.
- Reboot during a supported resumable download.
- Toggle Wi-Fi auto-connect and auto-resume settings.

Expected: useful authentication/timeout errors, no password disclosure, one
paused job on loss, and one resumed job after recovery when enabled.

## 5. Bluetooth

Test AirPods 4, one SBC-only earbud/headset, one common earbud set, and one
speaker.

- Discover, pair, reject/cancel pairing, unpair, and pair again.
- Verify legacy PIN entry, six-digit passkey entry, numeric-match confirmation,
  Just Works consent, and display-only PIN/passkey prompts where supported by
  the available accessories. Confirm no credential-entry path guesses `0000`
  or accepts a user-confirmation request.
- Route audio, use AVRCP play/pause/previous/next, and change volume.
- Walk out of range and return; reboot with the device available/unavailable.
- Toggle Bluetooth auto-reconnect.
- Force a stale bond/service restart if a safe lab method exists.
- Open diagnostics after each failure.

Expected: no claim beyond exposed A2DP/AVRCP/SBC behavior; diagnostics show
adapter, bond, route, profiles, obtainable codec/reason, and reconnect attempts.
Volume must not jump dangerously on reconnect.

## 6. Downloads and providers

- Soulseek: account, incremental search, peer browse, queued download,
  pause/resume/cancel/retry, duplicate, low-storage, and process restart.
- Podcast: download and stream, pause/resume, restart, completed indexing.
- Direct URL: valid authorized audio, redirect, bad MIME, bad status, range
  resume, duplicate target, cancel cleanup.
- Local import: supported file, malformed tag, duplicate, removable storage,
  unsupported extension.

Expected: correct shared job states, `.part` preservation only when recoverable,
atomic final name, clear safe error, and immediate targeted library visibility.

## 7. YouTube metadata and Discover

- Complete device authorization, pending/slow-down/denied/expired/revoked flows.
- Search, paginate, go offline, and reopen cached results.
- Check quota and retry messages.
- Verify reasons, Not interested, More like this, Less from channel, and clear
  history.
- Open creator-provided links and reject non-audio/unsafe URLs.

Expected: text metadata only; no thumbnail requirement, stream resolver, or
YouTube download action. Local listening signals remain on device.

## 8. Library and playback

- Libraries of 100, 1,000, and the largest practical track count.
- MP3, FLAC, WAV, OGG, M4A/AAC plus IJK fallback samples (Opus/WebM/APE/WMA).
- Missing/corrupt tags, large artwork, missing file, USB/removable media.
- Artist/album/track, folder, playlist, favorite, shuffle/repeat, queue,
  reboot-resume, previous/next, wired and Bluetooth output.
- Play local, cached, and proxy-backed videos. Verify the elapsed/progress/
  duration strip stays visible; short streaming skip seeks ±5 seconds; holding
  Previous/Next moves the scrub marker and commits rewind/fast-forward on
  release; Center/wheel fine scrub, pause, resume, end-of-file, and saved
  position all seek without a permanent “Buffering to” state.

Expected: incremental additions without a complete rebuild, bounded memory,
correct queue, and no navigation-time artwork stalls.

## 9. Rockbox and ROM

Do not test Rockbox switching until the exact variant key map and existing
Rockbox installation are backed up. Verify switch and return without reboot,
all physical keys, media preservation, and recovery when Rockbox is absent.

A ROM candidate additionally needs selective install and rollback drills on one
spare Type A and one spare Type B. Never select Format All or guess a variant.

## Failure bundle

```bash
adb shell getprop > y1-getprop.txt
adb shell cat /proc/meminfo > y1-meminfo.txt
adb shell dumpsys meminfo com.solar.launcher > y1-solar-meminfo.txt
adb shell dumpsys wifi > y1-wifi.txt
adb shell dumpsys bluetooth_manager > y1-bluetooth.txt
adb logcat -d -v threadtime > y1-logcat.txt
```

Before sharing, remove tokens, passwords, private SSIDs, device identifiers,
peer-private data, and personal media paths.
