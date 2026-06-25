# Y1-Rockbox.kl — unified Generic.kl / Stock.kl / Rockbox.kl on Solar ROM

Based on rockbox-y1 `Rockbox.kl` with **two Y1 hardware patches** only:

| Scancode | Vanilla Rockbox.kl | Y1-Rockbox.kl | Android | Role |
|----------|-------------------|---------------|---------|------|
| 105 wheel CCW | MEDIA_PREVIOUS (88) | **MEDIA_PLAY** | **126** | Rockbox + Solar scroll up |
| 106 wheel CW | MEDIA_NEXT (87) | **MEDIA_PAUSE** | **127** | Rockbox + Solar scroll down |
| 165 prev btn | DPAD_LEFT | (unchanged) | **21** | Rockbox transport / Solar prev |
| 163 next btn | DPAD_RIGHT | (unchanged) | **22** | Rockbox transport / Solar next |

**Do not** map 165/163 to 88/87 — Rockbox `buttonHandler` treats MEDIA_PREVIOUS/NEXT as
skip-track, causing immediate track advance on play.

ROM build keeps `org.rockbox.apk` + `librockbox.so` from the rockbox-y1 base image
(codec `.so` files live inside the APK under `lib/armeabi/`). Audit requires ≥35 native libs.

Test without reflash: `solar-rom/scripts/push-y1-keymap.sh`

**Codec lib sync:** `sync-rockbox-libs.sh` re-extracts `lib/armeabi/*.so` from `org.rockbox.apk` into `/data/data/org.rockbox/lib/` when stale. Solar runs this on boot and before every Switch to Rockbox (`RockboxLibSync.java`). See `.cursor/rules/rockbox-y1-coexistence.mdc`.
