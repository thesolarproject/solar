package com.solar.launcher.theme;

import org.junit.Test;

public class ThemeManagerTest {
    @Test
    public void getCurrentTheme_neverEmpty() {
        ThemeManager.availableThemes.clear();
        ThemeManager.ThemeEntry t = ThemeManager.getCurrentTheme();
        if (t == null || t.displayName == null || t.displayName.isEmpty()) {
            throw new AssertionError("fallback theme");
        }
    }

    @Test
    public void wallpaperPick_prefTokenRoundtrip() {
        ThemeManager.WallpaperPick pick = new ThemeManager.WallpaperPick(
                "Default", ThemeManager.WallpaperPick.KEY_DESKTOP, "Default", "desktop_wallpaper.png");
        ThemeManager.WallpaperPick parsed = ThemeManager.WallpaperPick.fromPrefToken(pick.prefToken());
        if (parsed == null || !pick.themeFolder.equals(parsed.themeFolder)
                || !pick.configKey.equals(parsed.configKey)) {
            throw new AssertionError("pref token roundtrip");
        }
    }

    @Test
    public void wifiSignalIndex_mapsThreeBars() {
        if (ThemeManager.wifiSignalIndex(-100) != 0) throw new AssertionError("weak");
        if (ThemeManager.wifiSignalIndex(-77) != 1) throw new AssertionError("mid");
        if (ThemeManager.wifiSignalIndex(-55) != 2) throw new AssertionError("strong");
    }

    @Test
    public void selfCheck() {
        ThemeManager.selfCheck();
    }
}
