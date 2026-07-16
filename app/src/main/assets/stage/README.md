# Solar APK self-staging (`assets/stage/`)

Optional binaries and APKs extracted by `SolarDeviceStaging` on first run (rooted Y1).

| Path | Purpose |
|------|---------|
| `keyboard.apk` / `com.solar.keyboard.apk` | Solar IME helper |
| `quickmenu.apk` / `com.solar.quickmenu.apk` | Quick-menu overlay helper |
| `su` or `su.bin` | Permissive su (only if device already has root to place it) |
| `xposed/*.apk` | Input/key modules — enable in Xposed/LSPosed after install |

Build packaging can copy signed helper APKs here from `keyboard-app` / `quickmenu-app` outputs.

**Never place Rockbox or JJ launcher packages here.** First-run staging is Solar-only.
