# Y1-Rockbox.kl — unified Generic.kl / Stock.kl / Rockbox.kl on Solar ROM

Based on rockbox-y1 `Rockbox.kl` with **four Y1 hardware patches** (vanilla file maps wheel
to 88/87 so RockboxFramebuffer scroll keys 126/127 never fire on mtk-tpd-kpd):

| Scancode | Vanilla Rockbox.kl | Y1-Rockbox.kl | Android | Role |
|----------|-------------------|---------------|---------|------|
| 105 wheel CCW | MEDIA_PREVIOUS | **MEDIA_PLAY** | **126** | Rockbox + Solar scroll up |
| 106 wheel CW | MEDIA_NEXT | **MEDIA_PAUSE** | **127** | Rockbox + Solar scroll down |
| 165 prev btn | DPAD_LEFT (21) | **MEDIA_PREVIOUS** | **88** | Track previous |
| 163 next btn | DPAD_RIGHT (22) | **MEDIA_NEXT** | **87** | Track next |

Unchanged from reference: Back 4, Center 66, Play/Pause 85.

ROM build installs the same file as `Generic.kl`, `Stock.kl`, `Rockbox.kl`, and `Y1-Rockbox.kl`.
Test without reflash: `solar-rom/scripts/push-y1-keymap.sh`
