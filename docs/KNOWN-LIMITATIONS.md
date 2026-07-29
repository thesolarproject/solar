# Known limitations

## Private test status

This branch is a private test candidate, not a public release. The upstream
checkout has no root license, so public source/APK/ROM redistribution is not
cleared.

## Hardware evidence

No physical Y1 was attached during implementation. Wheel timing, memory,
Bluetooth routing/codecs, suspend/resume, codec reliability, USB storage,
Rockbox key maps, and Type A/Type B recovery still need the checklist in
[Y1-HARDWARE-TEST.md](Y1-HARDWARE-TEST.md).

Software cannot upgrade the Bluetooth radio or promise Bluetooth 5.x, AAC,
aptX, LDAC, absolute volume, or a negotiated codec the stock stack does not
expose.

## Conversion

Automatic audio/video conversion is unavailable. The checkout has no
reproducible, legally cleared API-17/armeabi-v7a encoder. The existing IJK
native libraries are playback-only and have unresolved FFmpeg/x264 provenance
for encoder redistribution. Unsupported imports fail with a precise reason;
Solar does not fake conversion by copying or renaming a file.

## YouTube

YouTube is official metadata plus a local Discover approximation. It is not the
YouTube Home algorithm and does not download, resolve, or play YouTube streams.
Live account/API testing requires owner-supplied Google credentials and quota.

Android 4.2 lacks modern hardware-backed application credential storage.
App-private token storage protects against ordinary apps, not root or offline
device-image access.

## Builds and firmware

- A signed release APK requires authorized platform key material.
- A ROM requires an audited immutable base, matching backup evidence, and a
  privileged Linux loop-mount host.
- No ROM artifact was produced without those inputs.
- Type A and Type B cannot be inferred safely from normal Android properties.
- Existing Rockbox switching remains hardware-gated and does not install or
  repair Rockbox media automatically.

## Inherited technical debt

The app lint baseline records 2,300 inherited findings: 1,256 errors and 1,044
warnings, dominated by 1,151 missing translations. Repository lint passes
against that baseline and new findings remain fatal. The baseline is a
regression gate, not a claim that the debt is fixed.

`MainActivity` remains very large because replacing the established coordinator
would be a high-risk rewrite. New behavior is kept in focused helper/store
classes where practical.

## External services

Soulseek availability depends on peers and queues. Podcast and creator-download
hosts may not support HTTP ranges. Google, Podcast Index, Deezer, LALAL, and
other configured services can change availability, quota, or terms.

