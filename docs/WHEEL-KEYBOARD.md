# Wheel keyboard

Solar keeps its existing five-slot wheel keyboard presentation and now uses one
editing model across the launcher, Wi-Fi overlay, Bluetooth PIN overlay, and
Solar IME.

## Controls

- Wheel: move through the active character page.
- Center: insert the focused character or run the focused edit action.
- Previous: backspace. Holding it repeats backspace.
- Next: insert a space.
- Play/Pause: confirm. Hold to cycle Lowercase, Uppercase, Numbers, and Symbols.
- Back: cancel without submitting.
- `[<]` / `[>]`: move the edit cursor without deleting text.
- `[WD]`: delete the word or punctuation run before the cursor.
- `[VIS]` / `[HID]`: explicitly reveal or mask a password.
- `[SUG]`: accept the most-recent local-history prefix completion on supported
  search keyboards.
- `[ENT]`: submit.

Password, token, API-key, Wi-Fi-password, and pairing-PIN previews start masked.
Prediction is never loaded for those purposes. Solar's IME also redacts typed
characters from its diagnostic events when the focused Android field is a
password field.

The Symbols page includes all 32 printable ASCII punctuation characters, so
URLs, filenames, and generated Wi-Fi passwords do not require another keyboard.
Text editing uses Android-compatible UTF-16 cursor offsets but moves and
backspaces by complete Unicode code point.

## Layout evaluation

`WheelKeyboardLayoutBenchmark` is a deterministic interaction-cost harness, not
a claim of physical-device timing. It compares:

1. the original single alphabet ring; and
2. shorter Lowercase, Uppercase, Numbers, and Symbols pages selected by a held
   Play/Pause action.

The cost model counts a wheel notch or center press as one action and a held page
change as three actions. On this fixed representative corpus:

```text
the beatles
wifi password 2026
https://example.com/music
Miles Davis - Kind of Blue
artist_album.flac
```

the harness covered 97 characters and measured 967 weighted actions for grouped
pages versus 1,445 for the alphabet ring. Grouped pages are therefore the
default for new settings. The original ring remains selectable under
Settings → Device → Wheel keyboard layout, preserving the established behavior
for users who prefer it.

The benchmark and state-machine tests run in `testDebugUnitTest`. Physical Y1,
Y2, and A5 validation is still required for hold timing, legibility of the
five-slot strip, and error rate with real wheel hardware.
