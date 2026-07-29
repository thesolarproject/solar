# Performance findings and test targets

No physical Y1 was attached, so this document distinguishes code-level findings
from device acceptance targets.

## Implemented performance protections

- Wheel events are coalesced and list chrome does not require a full list
  rebuild for each detent.
- Search and provider I/O stays on background threads; result lists can update
  incrementally.
- The library reconciler fingerprints changed files and performs targeted
  indexing after a completed import/download.
- Metadata and artwork work is lazy/budgeted; the library cache has explicit
  memory budgets.
- Transfer state is journaled in compact JSON and network recovery resumes the
  existing job rather than creating a duplicate worker.
- YouTube metadata uses bounded caches, quota tracking, pagination, and
  exponential retry with jitter.
- Soulseek and HTTP downloads use partial files, bounded concurrency, wake-lock
  cleanup, and explicit pause/cancel paths.
- Playback routes compatible fallback formats through the existing IJK decoder
  instead of an unnecessary conversion pass.

Useful instrumentation includes the in-app diagnostics, `SolarLog`,
`SolarDiagProbes`, library reconciliation timing, `LibraryMemoryBudget`,
transfer speed/ETA fields, and wheel-layout benchmark tests.

## Provisional Y1 acceptance targets

These are gates to measure, not claimed results:

| Scenario | Target |
| --- | --- |
| Warm launcher to usable home | <= 2 seconds |
| Cold launcher to usable home | <= 4 seconds |
| Wheel-to-focus response | <= 50 ms p95, no unexplained jumps |
| Normal list transition | <= 100 ms p95 after data is available |
| Network/database work on UI thread | none |
| Navigation-time artwork decode | none |
| Long scan/download/conversion ANR | none |
| Steady launcher Java heap | below 72 MiB in a representative large library |
| Short peak Java heap | below the 96 MiB growth limit |
| Interrupted supported transfer | one recoverable job, no duplicate output |

Adjust thresholds only after recording a representative Type A and Type B run.

## Measurement procedure

1. Reboot, wait for storage mount, then capture `adb logcat -c`.
2. Cold-launch Solar and record first visible/home-ready markers.
3. Scroll 500 mixed-speed wheel detents in a 1,000+ item list.
4. Run an incremental add, metadata refresh, and full library scan separately.
5. Download a large authorized file, toggle Wi-Fi, reboot mid-transfer, and
   confirm one resumed job.
6. Capture:

```bash
adb shell dumpsys meminfo com.solar.launcher
adb shell dumpsys gfxinfo com.solar.launcher
adb shell cat /proc/meminfo
adb logcat -d -v threadtime > solar-y1-performance.log
```

Record device variant, firmware version, library size, storage type, Bluetooth
route, and test media alongside every result.

