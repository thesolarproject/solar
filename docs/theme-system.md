# Solar theme system

Solar launchers read Y1-style theme packs from `Themes/` on SD (or app files dir). Each pack is a folder with `config.json` and image assets.

## config.json blocks

| Block | Purpose |
|-------|---------|
| `itemConfig` | List row backgrounds, text colors, arrows |
| `menuConfig` | Settings/home menu overlay tint |
| `dialogConfig` | Modal panels (confirm dialogs) |
| `statusConfig` | Status bar color and text |
| `homePageConfig` | Home-specific text colors |
| `playerConfig` | Now Playing progress and accents |
| `solarConfig` | Solar extensions — `button_radius`, `statusBarTextColor` |

## Wallpapers and masks

- `desktopWallpaper` / `globalWallpaper` — home vs other screens
- `desktopMask` — home overlay dim
- `settingMask` — settings overlay dim

Appearance → Background lets you pick per-screen wallpapers (home, library, settings, now playing) from installed themes or a custom image.

## Button radius

Corner radius comes only from `solarConfig.button_radius` or root `button_radius` in the theme JSON — not from an in-app override.
