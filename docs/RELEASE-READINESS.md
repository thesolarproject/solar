# Release readiness

Status: **ready for private APK testing; not ready for public release or
firmware flashing**.

Audit snapshot: 2026-07-29, branch `feature/solar-classic-y1`.

## Verified in the host environment

- Repository-wide `testDebugUnitTest`: 1,887 tests, 0 failures, 0 errors,
  1 skipped.
- Repository-wide `lintDebug`: passed against the committed inherited baseline.
- Repository-wide `assembleDebug`: passed.
- Main app, global-context companion, launcher helper, and updater debug APKs
  were produced.
- Y1 and Y2 context-bridge APKs were rebuilt from the shared interactive
  Bluetooth-pairing policy.
- Platform asset sync and hash/DEX-content verification passed.
- Y1 firmware preflight unit tests: 4 passed.
- Missing Y1 base archive: correctly refused with exit code 2.

## Feature readiness

| Area | Host status | Physical gate |
| --- | --- | --- |
| Soulseek search/browse/queue/resume/history | implemented and unit-covered | live peer/network/reboot test |
| Podcast download resume | implemented and unit-covered | enclosure/range/reboot test |
| Direct authorized download/local import | implemented and unit-covered | storage/provider/device test |
| Durable Downloads journal | implemented and unit-covered | process-kill/reboot endurance |
| Official YouTube metadata/OAuth/Discover | implemented with fake transports/tests | owner credentials and live quota |
| Wi-Fi UI, diagnostics, reconnect/recovery | implemented and lint-clean | WPA/DHCP/captive/network-loss matrix |
| Bluetooth pairing prompts, reconnect, and diagnostics | implemented and unit-covered | PIN/passkey/headset/speaker/AirPods matrix |
| Wheel keyboard and list behavior | implemented and unit-covered | real wheel timing/usability |
| Incremental library and IJK fallback | implemented and unit-covered | large library and codec matrix |
| Video progress and direct seek/hold scrub | implemented and unit-covered | local/proxy/cached video matrix |
| Y1 Type A/B preflight and recovery docs | implemented and tested read-only | backup/restore drill |
| Automatic conversion | unavailable, fails closed | cleared encoder plus device validation |

## Blocking a signed/ROM release

- No authorized platform signing keys are available.
- No physical Y1 test result or recovery drill exists.
- No verified local Type A/Type B base is present for this build.
- The current host is not a privileged Linux ROM builder.
- The project and vendored/native asset redistribution license inventory is not
  complete; the repository has no root license.
- Conversion has no cleared reproducible encoder.

## Private tester handoff

1. Use the debug APK first; do not flash.
2. Verify the APK SHA-256 reported with the handoff.
3. Follow [INSTALL.md](INSTALL.md).
4. Run [Y1-HARDWARE-TEST.md](Y1-HARDWARE-TEST.md), beginning with backup and
   recovery evidence.
5. Return the exact failing step, safe logs, device variant, firmware, and media
   sample properties.

Do not publish artifacts or call this firmware-complete until every blocking
item is closed.
