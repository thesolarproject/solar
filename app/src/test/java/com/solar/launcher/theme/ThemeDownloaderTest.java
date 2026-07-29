package com.solar.launcher.theme;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class ThemeDownloaderTest {
    @Test
    public void selfCheck() throws Exception {
        ThemeDownloader.selfCheck();
    }

    /** 2026-07-11 — Leading @ must stay in gallery paths (Habbo @Be_*, Cupertino @Cup_*). */
    @Test
    public void preservesLeadingAtInGalleryPaths() {
        assertEquals("Habbo/@Be_itemBackground.png",
                ThemeDownloader.canonicalGalleryPath("Habbo", "@Be_itemBackground.png"));
        assertEquals("Habbo/@Be_Now Playing.png",
                ThemeDownloader.canonicalGalleryPath("Habbo", "@Be_Now Playing.png"));
        assertEquals("Habbo/Shutdown@1x.png",
                ThemeDownloader.canonicalGalleryPath("Habbo", "Shutdown@1x.png"));
        // 2026-07-11 — Cupertino uses the same @-prefix filename convention.
        assertEquals("Cupertino/@Cup_itemBackground.png",
                ThemeDownloader.canonicalGalleryPath("Cupertino", "@Cup_itemBackground.png"));
        assertEquals("Cupertino/@Cup_desktop_wallpaper.png",
                ThemeDownloader.canonicalGalleryPath("Cupertino", "@Cup_desktop_wallpaper.png"));
        assertEquals("Habbo/Be_itemBackground.png",
                ThemeDownloader.alternateLeadingAtPath("Habbo/@Be_itemBackground.png"));
        assertEquals("Habbo/@Be_itemBackground.png",
                ThemeDownloader.alternateLeadingAtPath("Habbo/Be_itemBackground.png"));
        assertEquals("Cupertino/Cup_itemBackground.png",
                ThemeDownloader.alternateLeadingAtPath("Cupertino/@Cup_itemBackground.png"));
        String url = ThemeDownloader.themeFileUrlForGallery("Habbo/@Be_Now Playing.png");
        if (!url.contains("%40Be_Now") || !url.contains("%20Playing.png")) {
            throw new AssertionError("expected encoded @ and space in " + url);
        }
    }

    @Test
    public void subfolderOutputPath() throws Exception {
        File themeDir = new File(System.getProperty("java.io.tmpdir"), "solar-theme-subfolder-test");
        deleteDir(themeDir);
        themeDir.mkdirs();
        try {
            File out = ThemeDownloader.resolveAssetOutputFile(themeDir, "ACNH",
                    "ACNH/all other images/folder view/foldericon.png");
            if (out == null) throw new AssertionError("null output");
            String path = out.getAbsolutePath().replace('\\', '/');
            if (!path.contains("/all other images/folder view/foldericon.png")) {
                throw new AssertionError("bad subfolder path: " + path);
            }
        } finally {
            deleteDir(themeDir);
        }
    }

    @Test
    public void eddieVariantPlanLocalNames() throws Exception {
        JSONObject config = new JSONObject(
                "{\"solarConfig\":{\"assetDirs\":[\"images\"]},"
                        + "\"background\":\"images/background.png\","
                        + "\"icons\":[\"images/play.png\",\"images/pause.png\"]}");
        String catalog = "Stranger Things/Variants/Eddie";
        Set<String> assets = ThemeDownloader.collectAssetPaths(config, catalog);
        assets.remove(catalog + "/config.json");
        Map<String, String> mapped = ThemeDownloader.planLocalNames(assets);
        if (mapped.isEmpty()) throw new AssertionError("no mapped assets");
        if (mapped.size() != assets.size()) throw new AssertionError("map size mismatch");
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteDir(k);
        }
        f.delete();
    }
}
