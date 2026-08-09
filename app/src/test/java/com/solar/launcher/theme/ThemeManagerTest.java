package com.solar.launcher.theme;

import com.solar.launcher.HomeMenuConfig;

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
    public void applySavedThemeSelection_prefersPathThenFolderThenIndex() throws Exception {
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/internal/Themes/Default", "Default", "Aura", new JSONObject()));
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry(
                "/internal/Themes/Melody", "Melody", "Melody", new JSONObject()));
        ThemeManager.applySavedThemeSelection("/old/sd/Themes/Melody", null, 0);
        if (ThemeManager.getCurrentThemeIndex() != 1) {
            throw new AssertionError("folder name should resolve Melody");
        }
        ThemeManager.applySavedThemeSelection(null, "Melody", 0);
        if (ThemeManager.getCurrentThemeIndex() != 1) {
            throw new AssertionError("folder pref should select Melody");
        }
        ThemeManager.applySavedThemeSelection(null, null, 0);
        if (ThemeManager.getCurrentThemeIndex() != 0) {
            throw new AssertionError("index fallback");
        }
    }

    @Test
    public void looksLikeThemeBitmapRef_filtersColoursAndUrls() {
        if (ThemeManager.looksLikeThemeBitmapRef("#FF00AA")) {
            throw new AssertionError("colour hash is not a bitmap");
        }
        if (!ThemeManager.looksLikeThemeBitmapRef("item_selected.png")) {
            throw new AssertionError("png ref");
        }
    }

    @Test
    public void internalThemesDir_nullContextUsesTempFallback() {
        java.io.File dir = ThemeManager.internalThemesDir(null);
        if (!dir.getPath().endsWith("Themes")) {
            throw new AssertionError("expected Themes suffix: " + dir);
        }
    }

    @Test
    public void themeFolderNeedsCopy_missingDestOrConfigMismatch() throws Exception {
        java.io.File tmp = new java.io.File(System.getProperty("java.io.tmpdir"),
                "solar-theme-sync-" + System.nanoTime());
        java.io.File src = new java.io.File(tmp, "src/Melody");
        java.io.File dest = new java.io.File(tmp, "dest/Melody");
        src.mkdirs();
        dest.mkdirs();
        java.io.File srcCfg = new java.io.File(src, "config.json");
        writeBytes(srcCfg, "{\"name\":\"Melody\"}".getBytes("UTF-8"));
        if (!ThemeManager.themeFolderNeedsCopy(src, dest)) {
            throw new AssertionError("missing dest config should need copy");
        }
        java.io.File destCfg = new java.io.File(dest, "config.json");
        writeBytes(destCfg, "{\"name\":\"Melody\"}".getBytes("UTF-8"));
        destCfg.setLastModified(srcCfg.lastModified());
        if (ThemeManager.themeFolderNeedsCopy(src, dest)) {
            throw new AssertionError("matching config should skip copy");
        }
        writeBytes(destCfg, "{\"name\":\"OLD\"}".getBytes("UTF-8"));
        if (!ThemeManager.themeFolderNeedsCopy(src, dest)) {
            throw new AssertionError("size mismatch should need copy");
        }
        deleteTree(tmp);
    }

    @Test
    public void samePath_matchesAbsolute() {
        java.io.File a = new java.io.File("/storage/sdcard0/Themes");
        java.io.File b = new java.io.File("/storage/sdcard0/Themes");
        if (!ThemeManager.samePath(a, b)) {
            throw new AssertionError("same path");
        }
        if (ThemeManager.samePath(a, new java.io.File("/storage/sdcard1/Themes"))) {
            throw new AssertionError("different path");
        }
    }

    private static void writeBytes(java.io.File file, byte[] data) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
        fos.write(data);
        fos.close();
    }

    private static void deleteTree(java.io.File root) {
        if (root == null || !root.exists()) return;
        if (root.isDirectory()) {
            String[] kids = root.list();
            if (kids != null) {
                for (String k : kids) deleteTree(new java.io.File(root, k));
            }
        }
        root.delete();
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
    public void parseBatteryFrameArray_preservesFourFrameThemeOrder() throws Exception {
        JSONObject root = new JSONObject().put("battery", new org.json.JSONArray()
                .put("bt1.png").put("bt2.png").put("bt3.png").put("bt4.png"));
        String[] frames = ThemeManager.parseBatteryFrameArray(root, "battery");
        if (!"bt1.png".equals(frames[0]) || !"bt4.png".equals(frames[3])
                || frames[4] != null) {
            throw new AssertionError("four-frame battery parsing");
        }
    }

    @Test
    public void parseBatteryFrameArray_acceptsFiveFrameThemeOrder() throws Exception {
        JSONObject root = new JSONObject().put("battery", new org.json.JSONArray()
                .put("b0.png").put("b1.png").put("b2.png").put("b3.png").put("b4.png"));
        String[] frames = ThemeManager.parseBatteryFrameArray(root, "battery");
        if (!"b0.png".equals(frames[0]) || !"b4.png".equals(frames[4])) {
            throw new AssertionError("five-frame battery parsing");
        }
    }

    @Test
    public void batteryAssetIndex_supportsFourAndFiveFrameThemes() {
        if (ThemeManager.batteryAssetIndex(0, 5) != 0
                || ThemeManager.batteryAssetIndex(19, 5) != 0
                || ThemeManager.batteryAssetIndex(20, 5) != 1
                || ThemeManager.batteryAssetIndex(99, 5) != 4
                || ThemeManager.batteryAssetIndex(100, 5) != 4) {
            throw new AssertionError("five-frame battery mapping");
        }
        if (ThemeManager.batteryAssetIndex(0, 4) != 0
                || ThemeManager.batteryAssetIndex(24, 4) != 0
                || ThemeManager.batteryAssetIndex(25, 4) != 1
                || ThemeManager.batteryAssetIndex(100, 4) != 3) {
            throw new AssertionError("four-frame battery mapping");
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
    public void connectionsSettingsKeys() {
        if (!"settingsWi_Fi".equals(ThemeManager.solarSettingsConfigKey("Wi-Fi"))) {
            throw new AssertionError("settingsWi_Fi");
        }
        if (!"settingsBluetooth".equals(ThemeManager.solarSettingsConfigKey("Bluetooth"))) {
            throw new AssertionError("settingsBluetooth");
        }
        if (!"settingsConnections".equals(ThemeManager.solarSettingsConfigKey("Connections"))) {
            throw new AssertionError("settingsConnections");
        }
        if (!"settingsDevice".equals(ThemeManager.solarSettingsConfigKey("Device"))) {
            throw new AssertionError("settingsDevice");
        }
        if (!"settingsLibrary".equals(ThemeManager.solarSettingsConfigKey("Library"))) {
            throw new AssertionError("settingsLibrary");
        }
        if (!"settingsMedia".equals(ThemeManager.solarSettingsConfigKey("Media"))) {
            throw new AssertionError("settingsMedia");
        }
        if (!"settingsPower".equals(ThemeManager.solarSettingsConfigKey("Power"))) {
            throw new AssertionError("settingsPower");
        }
        if (!"settingsUSB".equals(ThemeManager.solarSettingsConfigKey("USB"))) {
            throw new AssertionError("settingsUSB");
        }
        if (!"settingsPlayback".equals(ThemeManager.solarSettingsConfigKey("Playback"))) {
            throw new AssertionError("settingsPlayback");
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
    public void getMusicFallbackFromSameTheme() throws Exception {
        JSONObject root = new JSONObject();
        root.put("homePageConfig", new JSONObject().put("music", "theme_music.png"));
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry("/tmp", "t", "t", root));
        ThemeManager.setThemeIndex(0);
        HomeMenuConfig.Entry e = HomeMenuConfig.find(HomeMenuConfig.ID_SOULSEEK);
        if (ThemeManager.getHomeMenuIcon(null, e) != null) {
            throw new AssertionError("no file on disk — expect null bitmap");
        }
        if (!"music".equals(HomeMenuConfig.y1HomeIconFallbackKey(HomeMenuConfig.ID_SOULSEEK))) {
            throw new AssertionError("get music fallback key");
        }
        if (HomeMenuConfig.y1HomeIconFallbackKey(HomeMenuConfig.ID_PC_UPLOAD) != null) {
            throw new AssertionError("pc upload must not fallback");
        }
    }

    @Test
    public void appMusicOverridesStock() throws Exception {
        JSONObject root = new JSONObject();
        root.put("homePageConfig", new JSONObject().put("music", "stock_music.png"));
        root.put("solarConfig", new JSONObject().put("appMusic", "solar_music.png"));
        ThemeManager.availableThemes.clear();
        ThemeManager.availableThemes.add(new ThemeManager.ThemeEntry("/tmp", "t", "t", root));
        ThemeManager.setThemeIndex(0);
        if (!ThemeManager.hasThemeSolarConfigKey("appMusic")) {
            throw new AssertionError("appMusic set");
        }
    }

    @Test
    public void touchOverlayThemeForShow_nullContextIsNoOp() {
        ThemeManager.resetOverlayThemeBootstrapForTest();
        ThemeManager.touchOverlayThemeForShow(null);
        if (ThemeManager.isOverlayThemeBootstrappedForTest()) {
            throw new AssertionError("null ctx must not bootstrap overlay theme");
        }
    }

    @Test
    public void touchOverlayThemeForShow_warmPathKeepsBootstrap() {
        ThemeManager.markOverlayThemeBootstrappedForTest("Default");
        ThemeManager.touchOverlayThemeForShow(null);
        if (!ThemeManager.isOverlayThemeBootstrappedForTest()) {
            throw new AssertionError("warm path must not reset bootstrap");
        }
    }

    @Test
    public void ensureOverlayPaintableMinimum_nullContextIsNoOp() {
        ThemeManager.resetOverlayThemeBootstrapForTest();
        ThemeManager.ensureOverlayPaintableMinimum(null);
    }

    @Test
    public void overlayRamCacheLoaded_defaultsFalse() {
        ThemeManager.resetOverlayThemeBootstrapForTest();
        if (ThemeManager.isOverlayRamCacheLoaded()) {
            throw new AssertionError("RAM cache should start false in unit tests");
        }
    }

    @Test
    public void ensureReadableOnBackground_invertsClash() {
        // 2026-07-11 — White-on-white / black-on-dark must auto-fix for About/USB/donation.
        int whiteOnWhite = ThemeManager.ensureReadableOnBackground(0xFFFFFFFF, 0xFFFFFFF0);
        if (ThemeManager.contrastRatio(whiteOnWhite, 0xFFFFFFF0) < 3.0) {
            throw new AssertionError("white-on-white should invert");
        }
        int blackOnDark = ThemeManager.ensureReadableOnBackground(0xFF111111, 0xFF1A1A1A);
        if (ThemeManager.contrastRatio(blackOnDark, 0xFF1A1A1A) < 3.0) {
            throw new AssertionError("black-on-dark should invert");
        }
        int keep = ThemeManager.ensureReadableOnBackground(0xFFE8E8E8, 0xFF252528);
        if (keep != 0xFFE8E8E8) {
            throw new AssertionError("readable light-on-dark must keep fill");
        }
    }

    @Test
    public void selfCheck() {
        com.solar.launcher.theme.SolarTheming.selfCheck();
        ThemeManager.selfCheck();
    }
}
