package com.solar.launcher.theme;

import org.json.JSONObject;
import org.junit.Test;

public class ThemeManagerTest {
    @Test
    public void setThemeByFolderPath_resolvesAssetAliasToDefault() throws Exception {
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/storage/sdcard0/Themes/Default", "Default", "Aura", new JSONObject()));
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/storage/sdcard0/Themes/Other", "Other", "Other", new JSONObject()));
        ThemeManager.setThemeByFolderPath("asset://themes/default");
        if (ThemeManager.getCurrentThemeIndex() != 0) {
            throw new AssertionError("asset alias should select Default");
        }
    }

    @Test
    public void setThemeByFolderPath_resolvesFolderNameWhenPathMoved() throws Exception {
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/mnt/new/Themes/Default", "Default", "Aura", new JSONObject()));
        ThemeManager.setThemeByFolderPath("/storage/sdcard0/Themes/Default");
        if (ThemeManager.getCurrentThemeIndex() != 0) {
            throw new AssertionError("folder name should match Default");
        }
    }

    @Test
    public void legacyStockDefaultTitle_detectsCircular() {
        if (!ThemeManager.isLegacyStockDefaultTitle("Circular")) {
            throw new AssertionError("Circular is legacy stock Default");
        }
        if (ThemeManager.isLegacyStockDefaultTitle("Aura")) {
            throw new AssertionError("Aura is not legacy");
        }
    }

    @Test
    public void persistPathForTheme_builtinUsesThemesDefaultDir() {
        ThemeManager.ThemeEntry builtIn = new ThemeManager.ThemeEntry(
                "asset://themes/default", "Default", "Aura", new JSONObject());
        String path = ThemeManager.persistPathForTheme(builtIn);
        if (path.isEmpty() || path.indexOf("Default") < 0) {
            throw new AssertionError("persist path: " + path);
        }
    }

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
    public void pickSolarLogotypeAsset_contrastAware() {
        String onWhite = ThemeManager.pickSolarLogotypeAsset(0xFFFFFFFF);
        if (!onWhite.contains("colour") && !onWhite.contains("black")) {
            throw new AssertionError("white bg: " + onWhite);
        }
        String onDark = ThemeManager.pickSolarLogotypeAsset(0xFF050505);
        if (!onDark.contains("colour")) throw new AssertionError("dark bg: " + onDark);
        String onOrange = ThemeManager.pickSolarLogotypeAsset(0xFFF0A830);
        if (!onOrange.contains("black")) throw new AssertionError("clash bg: " + onOrange);
    }

    @Test
    public void getMusicHomeIconUsesSolarConfigKey() throws Exception {
        if (!"appGet_Music".equals(ThemeManager.solarAppConfigKey("Get Music"))) {
            throw new AssertionError("Get Music key");
        }
        HomeMenuConfig.Entry e = HomeMenuConfig.find(HomeMenuConfig.ID_SOULSEEK);
        if (e == null || !"Get Music".equals(e.solarAppName)) {
            throw new AssertionError("soulseek home entry should use Get Music solarAppName");
        }
    }

    @Test
    public void settingsAboutUsesSolarConfigKey() {
        if (!"settingsAbout".equals(ThemeManager.solarSettingsConfigKey("About"))) {
            throw new AssertionError("settingsAbout key");
        }
    }

    @Test
    public void podcastsHomeIconPrefersSolarConfig() throws Exception {
        JSONObject root = new JSONObject();
        root.put("homePageConfig", new JSONObject().put("audiobooks", "Audiobooks_YS.png"));
        root.put("solarConfig", new JSONObject().put("appPodcasts", "custom_podcasts.png"));
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry("/tmp", "t", "t", root));
        if (!ThemeManager.hasThemeSolarConfigKey("appPodcasts")) {
            throw new AssertionError("appPodcasts should be set");
        }
        root.getJSONObject("solarConfig").remove("appPodcasts");
        ThemeManager.availableThemes.set(0, new ThemeManager.ThemeEntry("/tmp", "t", "t", root));
        if (ThemeManager.getSolarAppIcon("Podcasts") != null) {
            throw new AssertionError("unset appPodcasts should not resolve solar icon");
        }
        if (ThemeManager.hasThemeSolarConfigKey("appPodcasts")) {
            throw new AssertionError("empty appPodcasts should not count as set");
        }
    }

    @Test
    public void solarAppIconNotMergedFromOtherThemes() throws Exception {
        JSONObject club = new JSONObject();
        club.put("theme_info", new JSONObject().put("title", "Club Penguin"));
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/tmp/club", "Club", "Club Penguin", club));
        ThemeManager.setThemeIndex(0);
        if (ThemeManager.getSolarAppIcon("Get Music") != null) {
            throw new AssertionError("third-party theme must not inherit bundled appGet_Music");
        }
        if (ThemeManager.getSolarAppHomeIcon(null, "Get Music", 0) != null) {
            throw new AssertionError("no right-pane icon when theme omits solarConfig");
        }
    }

    @Test
    public void selfCheck() {
        ThemeManager.selfCheck();
    }
}
