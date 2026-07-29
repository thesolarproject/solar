# Changelog

## Unreleased - private Y1 test candidate

### Safety and builds

- Added read-only, fail-closed Type A/Type B firmware and backup preflight.
- Allowed normal local debug/test builds without platform keys while keeping
  release signing fail-closed.
- Added an app lint baseline so inherited findings are visible without allowing
  new lint regressions.
- Made API-17 companion shutdown and overlay permission handling safe.
- Rebuilt and hash-pinned the embedded global-context and home-helper APKs.

### Get Music and transfers

- Added a durable shared transfer journal and Downloads detail/actions.
- Restored Soulseek and podcast recovery after process/network interruption.
- Added resumable, authorized direct audio downloads and safe local import.
- Added duplicate/storage/filename policies, history cleanup, and targeted
  post-completion library indexing.
- Added Soulseek responsible-use acknowledgement, share-index state, and
  diagnostics without exposing credentials.

### YouTube metadata and Discover

- Replaced scraping/acquisition behavior with official metadata and OAuth device
  authorization.
- Added pagination, quota tracking, bounded cache, offline reuse, revoked-token
  handling, and exponential retry with jitter.
- Added explainable deterministic Discover ranking with account, search, and
  local-library signals plus feedback controls.
- Added validated creator-provided audio-link handoff to the separate direct
  download path. YouTube streams remain unavailable by design.

### Device experience

- Hardened custom Wi-Fi joins, saved-network operations, diagnostics,
  configurable auto-connect, and transfer resume after Wi-Fi recovery.
- Hardened Bluetooth reconnect/service recovery, added text diagnostics, and
  replaced silent `0000`/auto-confirm pairing with immediate wheel PIN,
  passkey, confirmation, consent, and stock-dialog fallback paths.
- Improved ring/grouped wheel keyboard editing, hold acceleration, password
  privacy, suggestions, and path-independent tests.
- Kept the video elapsed/progress/duration strip visible and made short seeks,
  hold-to-fast-forward/rewind, and resume jumps submit directly to the active
  player with seek-complete and bounded timeout handling.
- Fixed incremental library reconciliation and routed fallback audio through
  the existing IJK playback path.
- Corrected wheel chrome, empty playback position, stem hold semantics, melody
  selection, and Windows-portable host tests.
