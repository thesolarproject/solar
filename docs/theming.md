# Solar theming (Y1 themes + solarConfig)

Solar runs on Innioasis Y1/Y2 hardware and reuses the stock Y1 theme format (`config.json` + PNG assets). Solar adds an optional **`solarConfig`** block for features that do not exist on stock Y1 firmware.

Reference gallery themes: [InnioasisY1Themes](https://github.com/thesolarproject/InnioasisY1Themes) — same `homePageConfig`, `settingConfig`, `itemConfig`, etc.

## Precedence

For any icon or color Solar supports in both places:

1. **Active theme `solarConfig`** (if the key is set and the asset file exists in that theme folder)
2. **Stock Y1 block** in the **same** theme (`homePageConfig`, `settingConfig`, …)
3. **Nothing** — hide the preview icon (no Android drawable, no bundled Aura, no sibling-theme files)

Never use unrelated Y1 keys (e.g. `shuffleQuick`) as a stand-in for Solar-only shortcuts like Wi‑Fi Transfer.

`solarConfig` from the bundled Aura default theme is **never** merged into third-party themes for **icons** (only non-icon keys like colors may merge on Default upgrade).

## Open-ended keys

Theme authors add keys freely. Solar derives lookup names from **English UI labels** so keys stay stable when the user changes device language.

| Pattern | Used for | Example label | Key |
|---------|----------|---------------|-----|
| `app{Name}` | Home screen shortcut preview / editor | `Music` | `appMusic` |
| `app{Name}` | Solar-only shortcuts | `Get Music` | `appGet_Music` |
| `settings{Name}` | Settings right-pane preview | `About` | `settingsAbout` |

**Sanitization:** non-alphanumeric runs become `_`, leading/trailing `_` trimmed. Legacy app keys without underscores (e.g. `appPCUpload`) are still accepted as a fallback.

**Value:** filename of a PNG (or other image) in the theme folder, same as Y1 keys.

### Stock home shortcuts

Stock rows (Music, Bluetooth, Settings, …) check `solarConfig` **before** `homePageConfig`. Example: `appMusic` overrides `homePageConfig.music`.

### Solar-only shortcuts (explicit Y1 fallbacks only)

| Shortcut | solarConfig | homePageConfig fallback |
|----------|-------------|-------------------------|
| Get Music | `appGet_Music` | `music` |
| Deezer | `appDeezer` | `music` |
| Podcasts | `appPodcasts` | `audiobooks` |
| Photos | `appPhotos` | `photos` |
| Wi‑Fi Transfer | `appPC_Upload` / `appPCUpload` | *(none)* |

### Settings rows

Right-pane icons use `settings{Name}` from the **English** row title (`SolarTheming.englishSettingsRowLabel`), then Reach-specific keys (`soulseekSearch`, …), then `app{Name}` for app rows (e.g. Reach → `appReach`), then `settingConfig`.

## Other solarConfig keys

Colors (`homeUnselectedColor`, `settingSelectedColor`, …), `button_radius`, `nowPlayingInfoBars`, status bar Wi‑Fi/battery frame arrays, gallery install metadata — see `ThemeManager.solarBlock()` and bundled `app/src/main/assets/themes/default/config.json`.

## Adding a new Solar feature

When you add a user-visible home shortcut or settings row:

1. Add the shortcut to `HomeMenuConfig` or row to `RowKeys` / settings builder.
2. Wire icon via `ThemeManager.getHomeMenuIcon()` or `ThemeManager.getSettingsRowIcon()` (do not hard-code drawable paths).
3. Document the English label → `app*` / `settings*` key in this file.
4. Optionally add a sample asset to Aura `themes/default/config.json`.
5. Add a unit test in `ThemeManagerTest` or `SolarTheming.selfCheck()` if the key rule is non-obvious.

## Implementation map

| Concern | Code |
|---------|------|
| Key derivation | `ThemeManager.solarAppConfigKey`, `solarSettingsConfigKey` |
| English labels | `SolarTheming.englishString`, `englishSettingsRowLabel` |
| Home icons | `ThemeManager.getHomeMenuIcon` |
| Settings preview icons | `ThemeManager.getSettingsRowIcon` |
| Y1 stock home | `homePageConfig` via `ThemeManager.getHomeIcon` |
| Y1 stock settings | `settingConfig` via `ThemeManager.getSettingIcon` |
