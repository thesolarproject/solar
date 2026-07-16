# Solar — contributor and agent guide

This file is the **single source of truth** for how we build and maintain Solar. It applies to human contributors and to AI coding assistants alike. Symlinks from other IDE rule locations (Cursor, Claude Code, Codex, Copilot, Antigravity, Kilo, Cline, Windsurf) all point here — edit this file, not the copies.

Our north star is **maintainability and long-term reliability**: code that a person can read, reason about, and fix on real hardware years from now.

---

## 1. Philosophy

### Humans first

Solar runs on decade-old MP3-player hardware. Every change should be understandable without opening a chat transcript. Prefer plain language, explicit control flow, and comments that explain *why* — especially Y1 hardware quirks.

### AI is a tool, not an authority

AI assistance is welcome in this project. It is not a substitute for reading the code, testing on device, or exercising judgment.

- Review every generated diff before committing.
- Never merge code you cannot explain in your own words.
- Do not commit or push unless explicitly asked.
- Timestamp comments you add (see [Code conventions](#7-code-conventions)).

### Remove cruft, resist monoliths

When replacing behavior, delete the old path. Do not leave parallel implementations "just in case." Extract logic into the module that owns the domain when a split clearly pays off — not as reflexive refactoring.

---

## 2. What Solar is

**Solar** (`com.solar.launcher`) is a replacement system launcher for the **Innioasis Y1** — a wheel-driven MP3 player on Android ~4.x (minSdk 17). It provides a themed, offline-capable home screen, local music library, podcasts, settings, and **Reach** (in-app Soulseek client).

| | |
|---|---|
| **Repo** | https://github.com/thesolarproject/solar |
| **OTA catalog** | https://thesolarproject.github.io/solar-update/updates.xml |
| **Branches** | `nightly` (day-to-day) · `main` (stable, semver +0.1 per release) |
| **On device** | Platform-signed APK at `/system/app/com.solar.launcher.apk` + Conscrypt JNI in `/system/lib` |
| **Dev builds** | AOSP test platform keys via `scripts/env.sh` |

### Product goals

1. **Best Y1 daily driver** — fast wheel UI, theme fidelity (Y1 theme packs), predictable offline behavior.
2. **Offline-first** — music, settings, saved podcasts, and installed themes work without internet; online features hide or degrade gracefully.
3. **Reach (Soulseek)** — search, play/save/queue from the network; early streaming from temp cache; library sharing so sharer-only peers are reachable.
4. **Community themes** — install/switch Y1 theme folders; online catalog ("Get More") is optional, not required.
5. **Shippable firmware** — CI builds signed APK plus Y1 type A, type B, and Y2 ATA ROM zips.

### Shipped (update when scope changes)

- Full launcher rewrite; boot receiver, status bar, themed rows, home menu editor.
- Music browser (folders, artists/albums/genres, playlists, queue editor).
- Podcasts via Open RSS; saved library usable offline.
- Reach: Soulseek login, search, play/save/queue, early play from growing cache, re-search on stall, NAT-PMP, account persistence.
- Library sharing: `/Music` + `/Podcasts` announced when charging+Wi‑Fi or while Reach UI is open.
- Themes: installed list + Get More catalog; offline Themes menu always available.
- Wi‑Fi / Bluetooth / brightness / storage / PC upload web server / apps launcher.
- Platform TLS: Conscrypt JNI + modern CA roots on `/system`; boot init `99SolarInit.sh` for SD folders.
- Release pipeline: push to `nightly` → `nightly-{N}`; merge to `main` → `v0.x` stable.
- Context menu quick bar (hold Back): lock, Wi‑Fi, BT, volume, power, queue.
- Unified mixed queue: music, podcasts, Reach streams in one order with disk persistence.
- OTA: themed download overlay; system APK install + reboot modal on Y1.

### Not done yet

- Polish Reach UX (search quality, upload reliability on real NAT, share drain tuning).
- Podcast prebuffer / resume polish; broader locale (e.g. Korean keyboard).
- Incremental MainActivity slimming only when a split clearly pays off.
- Hardware validation on every release (wheel focus, theme scroll, sharing while charging).

---

## 3. Ponytail methodology

"Ponytail" is our name for pragmatic, minimal engineering suited to constrained hardware. You will see `ponytail:` markers in comments throughout the codebase — follow the same spirit.

| Principle | Meaning |
|-----------|---------|
| **Minimal diff** | Smallest change that solves the problem. No drive-by refactors or unrelated cleanup. |
| **No new dependencies** | Unless clearly necessary for Y1 constraints. Every dependency is a long-term liability on API 17. |
| **Hardware-honest code** | Comment Y1/API-17 quirks: ConnectivityManager lies, wheel key remaps, recycled ListView rows, toolbox vs toybox `date` formats. |
| **Teardown over leak** | Background work stops when leaving a screen. See `SessionLifecycle.onLeaveScreen`. |
| **Repair over crash** | Migrate prefs on upgrade, repair missing theme assets, purge temp caches on low storage. |
| **Extract when it pays off** | Shrink monoliths incrementally. Do not refactor for its own sake. |

---

## 4. User interface principles

Design for **iPod Classic / Innioasis Y1 / Zune** — vertical wheel lists, predictable focus, no desktop or phone patterns.

### Navigation and layout

- Primary navigation is **up/down scrolling** with a physical wheel and a center/select button.
- `layoutMainMenu` must be `View.GONE` when not on the home screen. Forgetting this causes stacked UI — a common bug.
- Home rows use **item** art; settings and menu lists use **menu** art; file/library lists use **item** art (stock Y1 convention).

### Offline-first UI

| Feature | Offline behavior |
|---------|------------------|
| Music | Fully available from SD |
| Saved podcasts | Available without internet |
| Installed themes | Always usable; "Get More" needs internet |
| Reach | Hidden when offline or login fails |
| PC Upload | Needs LAN only (not internet) |
| Settings | Fully available |

Connectivity rules live in `ConnectivityHelper` and `HomeMenuConfig`. Only Reach needs internet for *discovery* on the home screen.

### ListView and focus

- ListView **recycles rows**. Tag shell views in `getView`; never assume child state persists between binds.
- Ensure list focus after navigation (`ensureBrowserListFocus` pattern).
- Y1 wheel OK is often `KEYCODE_MEDIA_PLAY_PAUSE` — treat as center while context menu is open.

### Input and locale

- Hold prev or center on `[DEL]` for repeat backspace on keyboard screens.
- Korean locale is hidden until wheel keyboard supports Hangul input.

### Theming

- Button radius comes from theme JSON only — not hardcoded overrides.
- On theme apply, repair missing assets from catalog when Wi‑Fi is available.
- Per-screen background picks; status bar contrast must remain readable.

---

## 5. Architecture

Most UI is **Java, single-activity, programmatic views** — not Compose. Strings live in `res/values` (+ `values-ko` where applicable).

### Module layout

```
app/              MainActivity orchestration, wiring, thin bridges
core/
  contracts/      Feature interfaces and shared IDs
  net/            TLS, OkHttp, Conscrypt helpers
  keyboard/       Keyboard engine
  theme/          ThemeManager, catalog download
  ui/             Shared UI helpers
  playback/       PlayQueue, PlaybackCoordinator, PlaylistManager
  library/        ConnectivityHelper, artist parsing
feature/
  home/           HomeMenuConfig, home shortcuts
  apps/           App launcher
  podcasts/       Open RSS, library on SD
  reach/          Soulseek wire protocol, search, download, share
  settings/       Settings screens
  storage/        Storage views
keyboard-app/     Companion system keyboard APK
quickmenu-app/    Companion quick menu APK
solar-rom/        ROM builder and release scripts
```

### Dependency boundaries

Enforced by `gradle/arch-rules.gradle`:

- `feature:*` must **not** depend on `:app`.
- `feature:*` must **not** depend on other `feature:*` modules, except `:feature:reach` → `:feature:podcasts`.
- Do not bypass boundaries with reflection or clever classpath tricks.

`MainActivity` is the navigation hub today. New domain logic belongs in the module that owns it — not another 500 lines in MainActivity unless truly unavoidable.

### Key files by area

| Area | Location |
|------|----------|
| UI / navigation | `MainActivity.java` (screen state machine) |
| Session teardown | `SessionLifecycle.java` |
| Playback / queue | `core/playback/` — `PlayQueue`, `PlayQueueStore`, `PlaybackCoordinator` |
| Reach / Soulseek | `feature/reach/soulseek/` |
| Podcasts | `feature/podcasts/podcast/` |
| Themes | `core/theme/` |
| TLS / HTTP | `core/net/` |
| Home shortcuts | `feature/home/HomeMenuConfig.java`, `core/library/ConnectivityHelper.java` |
| ROM builder | `solar-rom/scripts/build-rom.sh` |
| CI releases | `.github/workflows/build-release.yml` |

---

## 6. Self-healing and housekeeping

When you change behavior, walk this checklist. Skipping steps is how regressions accumulate silently.

| You changed… | Also update… |
|--------------|--------------|
| New screen with background work | `SessionLifecycle.onLeaveScreen` teardown path |
| New home shortcut | `HomeMenuConfig` + `ConnectivityHelper.shouldShowHomeShortcut` + pref migration if IDs change |
| Renamed prefs / menu IDs | `migrateLegacyPrefs`, `migrateHomePrefsIfNeeded`, `HomeMenuConfig.migrateIdStatic` |
| Theme assets or apply path | `ThemeManager.ensure*`, `ThemeDownloader.repairThemeFolderIfInCatalog`, integrity check on apply |
| Reach streams / temp files | `StreamTempCache.purge`, `SessionLifecycle` handoff rules |
| System partition (TLS, init, APK paths) | `scripts/stage-y1-system-prep.sh` **and** `solar-rom/scripts/build-rom.sh` **and** `app/src/main/assets/scripts/update-system-apk.sh` |
| New system APK slot | ROM builder + OTA update script + CI workflow |
| Module dependency | `gradle/arch-rules.gradle` and `settings.gradle` |
| Material scope change | **Achieved / Not done yet** sections in this file |

### Session lifecycle pattern

`SessionLifecycle` stops Reach search, Bluetooth discovery, Wi‑Fi setup threads, podcast fetches, and browser scans when leaving their screens. Reach stream handoff to the player is the main exception — do not tear down an active partial download unless the user leaves playback entirely.

### Preference migration pattern

On upgrade, old prefs must map to new keys without data loss. Follow existing `migrate*` methods in `MainActivity` and `HomeMenuConfig`. Add a migration when renaming or renumbering menu IDs.

### System prep single source of truth

`scripts/stage-y1-system-prep.sh` stages Conscrypt JNI, modern CA roots, and init scripts for **ROM builds** and adb push scripts.

**APK self-staging (first run):** `SolarDeviceStaging` extracts the same class of files from the main APK (`libconscrypt_jni`, `assets/certs`, optional `assets/stage/*` helpers) when root allows. This is the recovery path when a ROM pipeline step was missed or the user installs Solar alone over Rockbox-y1 / JJ / Koensayr. First-run must **never** install or progress-label Rockbox or JJ — only Solar steps with graceful skip when no root.

If you add anything under `/system` for Solar to work, it must flow through the shell stage script **and** the corresponding `SolarDeviceStaging` step when practical.

---

## 7. Code conventions

### Readability

- A maintainer should understand a change in one pass without external context.
- Comments explain **why**, not what the next line obviously does.
- Prefer explicit code over clever abstractions.

### Timestamped comments

**All substantive comments** — whether written by a human or an AI assistant — must include a date so changes can be traced chronologically.

```java
// 2026-07-05: Y1 ConnectivityManager often returns null active network — scan all interfaces.
/** 2026-07-05 — ponytail: stop Soulseek search when leaving Reach unless stream handoff active. */
```

Use `YYYY-MM-DD` (ISO 8601 date). Update or extend the comment when significantly revising the logic — do not stack undated edits on top of old notes.

### Legacy removal

When replacing behavior, remove the old implementation. Do not keep dead code paths, unused imports, or commented-out blocks "for reference."

### Strings and localization

User-visible text goes in `res/values/strings.xml` (+ `values-ko` when adding Korean). No hardcoded UI strings in Java unless unavoidable (e.g. debug logs).

### Tests

Add tests when behavior is non-trivial and testable without hardware. Skip tests that only assert the obvious (getters, constant values).

---

## 8. ROM, Rockbox lockstep, and releases

### Scatter files — never mix

| Output | Device | Scatter file |
|--------|--------|--------------|
| `rom.zip` | Innioasis Y1 type A (MT6572, Android 4.2) | `MT6572_Android_scatter.txt` |
| `rom_type_b.zip` | Innioasis Y1 type B (MT6572, Android 4.2) | `MT6572_Android_scatter.txt` |
| `rom_y2.zip` | Innioasis Y2 ATA (MT6582, Android 4.4) | `MT6582_Android_scatter.txt` |

CI builds all three from `solar-rom/scripts/build-rom.sh`.

### Rockbox-y1 alignment

- **Do not patch APKs.** Download upstream Rockbox-y1 releases bit-for-bit and install them unmodified. Do not use a pre-built Rockbox-y1 ROM as the Solar base firmware.
- **Use Xposed modules** for keycode/input remapping and other runtime hooks — not smali edits, repacked APKs, or embedded patches.
- Watch upstream for keycode, install-script, and base-ROM changes. Y1 hardware should send the same scan codes as Rockbox-y1, not stock `com.innioasis.y1`.
- ROM prep steps live in `solar-rom/scripts/`. Keep them in lockstep with Rockbox-y1 stable releases.
- A new stable Rockbox-y1 release should trigger review of our ROM build process for required accommodations.

### Versioning

- Bump `versionName` / `versionCode` in `app/build.gradle` with each commit so local builds show a distinct version on the About screen.
- **GitHub release tags are assigned by CI**, not from whatever number happens to be in `app/build.gradle`. `prepare-release.sh` reads the latest `nightly-*` or `v0.*` tag and assigns the next sequential version.
- After each release, CI commits the next placeholder version back to the branch.

### Build and install

```bash
# Local APK
./scripts/build.sh

# ROM zips
./solar-rom/scripts/build-rom.sh a --apk app/build/outputs/apk/release/app-release.apk rom.zip
./solar-rom/scripts/build-rom.sh b --apk app/build/outputs/apk/release/app-release.apk rom_type_b.zip
./solar-rom/scripts/build-rom.sh y2 --apk app/build/outputs/apk/release/app-release.apk rom_y2.zip

# Device install (rooted Y1 — not plain adb install)
./scripts/build.sh
./scripts/clean_install_system.sh
```

### System partition contents (ROM and adb install)

| Path | Purpose |
|------|---------|
| `/system/app/com.solar.launcher.apk` | Solar launcher (platform-signed) |
| `/system/app/com.solar.keyboard.apk` | Solar keyboard (optional companion) |
| `/system/app/com.solar.quickmenu.apk` | Solar quick menu (optional companion) |
| `/system/lib/libconscrypt_jni.so` | Conscrypt JNI for TLS 1.2+ |
| `/system/etc/security/cacerts/*.0` | Modern CA roots |
| `/system/etc/init.d/99SolarInit.sh` | Boot: create Music/Podcasts/Themes on SD |

---

## 9. Git and contribution discipline

### Branches

- **`nightly`** — default target for day-to-day work and pushes.
- **`main`** — stable releases only, when explicitly ready.

### Commit identity

| Identity | When |
|----------|------|
| `Anonymous <anonymous@local>` | Default for local/agent commits |
| `thesolarproject <anonymous@local>` | Pushes to GitHub, release bumps, OTA publish |

Pass identity per command — do not set global `git config`:

```bash
git -c user.name="Anonymous" -c user.email="anonymous@local" commit ...
```

Never commit or push as personal accounts or `github-actions[bot]`.

### Credentials

GitHub tokens and other secrets live in **local-only** rules (`.cursor/rules/local/`) or environment variables — never in tracked files.

### Safety rules

- Do not commit or push unless explicitly asked.
- Do not force-push to `main`.
- Do not amend commits that have already been pushed unless explicitly asked.
- Do not commit credentials, PATs, or keys.

---

## 10. Working with AI assistants

If you are an AI assistant reading this file:

1. **Read this entire file** before making changes.
2. **Propose minimal diffs.** Explain trade-offs in plain language.
3. **Never trust generated code blindly** — especially lifecycle, threading, Soulseek wire protocol, and MediaPlayer code.
4. **Timestamp every comment you add** (see Section 7).
5. **Walk the housekeeping checklist** (Section 6) after any behavioral change.
6. **Do not commit or push** unless the human explicitly asks.
7. **Do not add dependencies** without clear justification for Y1 constraints.
8. **Match existing conventions** — naming, module boundaries, ponytail comment style, Y1 UI patterns.
9. **Update this file's Achieved / Not done yet sections** when a change materially shifts project scope.

Humans remain responsible for review, hardware testing, and merge decisions. Your job is to accelerate understanding and implementation — not to substitute for either.

---

## Quick reference

| Question | Answer |
|----------|--------|
| Where does Reach teardown live? | `SessionLifecycle.onLeaveScreen` → `teardownSoulseekSession` |
| Which scatter for Y2? | `MT6582_Android_scatter.txt` → `rom_y2.zip` |
| System TLS staging? | `scripts/stage-y1-system-prep.sh` |
| Offline home shortcut rules? | `ConnectivityHelper.shouldShowHomeShortcut` |
| Module boundary enforcement? | `gradle/arch-rules.gradle` |
| Default push branch? | `nightly` |
| Canonical rules file? | This file — `AGENTS.md` |
