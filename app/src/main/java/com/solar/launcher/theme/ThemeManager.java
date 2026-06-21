package com.solar.launcher.theme;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ponytail: one file — Y1 config.json themes only. Bundled Default from assets.
 */
public class ThemeManager {

    /** Legacy Y1 path; use {@link #themesRoot()} after init. */
    public static final String PATH_THEMES = "/storage/sdcard0/Themes";
    public static final String BUILTIN_DEFAULT_FOLDER = "Default";
    private static final String BUNDLED_ASSET_DIR = "themes/default";

    private static String themesRootPath = PATH_THEMES;
    private static ThemeEntry bundledFallback;
    private static Context assetContext;

    public static class ThemeEntry {
        public final String folderPath;
        public final String folderName;
        public final String displayName;
        public final String name;
        public final JSONObject root;

        ThemeEntry(String folderPath, String folderName, String displayName, JSONObject root) {
            this.folderPath = folderPath;
            this.folderName = folderName;
            this.displayName = displayName;
            this.name = displayName;
            this.root = root;
        }
    }

    public static List<ThemeEntry> availableThemes = new ArrayList<>();
    private static int currentThemeIndex = 0;
    private static final Map<String, Bitmap> bitmapCache = new HashMap<>();
    /** ponytail: cache scaled row tiles — focus scroll was re-scaling on every D-pad step (Y1 jank) */
    private static final Map<String, Bitmap> scaledRowBitmapCache = new HashMap<>();
    private static Typeface cachedFont;
    private static String cachedFontKey = "";
    private static boolean statusBarMatchItemText = true;

    /** ponytail: external Themes/ with filesDir fallback when sdcard missing (emulator). */
    public static String resolveThemesRoot(Context ctx) {
        if (ctx != null) {
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null) {
                    return new File(ext, "Themes").getAbsolutePath();
                }
            } catch (Exception ignored) {}
            return new File(ctx.getFilesDir(), "Themes").getAbsolutePath();
        }
        return PATH_THEMES;
    }

    public static String themesRoot() {
        return themesRootPath;
    }

    /** Extract bundled Default → themes root; load in-memory fallback if copy fails. */
    public static void ensureBundledDefault(Context ctx) {
        assetContext = ctx.getApplicationContext();
        themesRootPath = resolveThemesRoot(ctx);
        try {
            File dest = new File(themesRootPath, BUILTIN_DEFAULT_FOLDER);
            File config = new File(dest, "config.json");
            if (!config.isFile() || config.length() == 0) {
                if (!dest.exists()) dest.mkdirs();
                copyAssetTree(ctx.getAssets(), BUNDLED_ASSET_DIR, dest);
            } else if (isLegacyStockDefaultTheme(readConfigJson(config))) {
                copyAssetTree(ctx.getAssets(), BUNDLED_ASSET_DIR, dest);
            } else {
                syncBundledSolarAssets(ctx, dest);
                syncBundledThemeBlocks(ctx, dest);
                copyMissingBundledAssets(ctx, dest);
            }
        } catch (Exception ignored) {}
        ensureBundledFallback(ctx);
    }

    /** Copy solarConfig assets + keys into an existing Default folder (legacy Circular installs). */
    private static void syncBundledSolarAssets(Context ctx, File destDir) {
        try {
            byte[] raw = readAllFromAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/config.json");
            JSONObject bundled = new JSONObject(new String(raw, "UTF-8"));
            JSONObject solar = bundled.optJSONObject("solarConfig");
            if (solar == null) return;
            java.util.Iterator<String> keys = solar.keys();
            while (keys.hasNext()) {
                String ref = solar.optString(keys.next(), "").trim();
                if (ref.isEmpty() || ref.contains("://") || ref.startsWith("#")) continue;
                String base = new File(ref.replace('\\', '/')).getName();
                File out = new File(destDir, base);
                if (!out.isFile() || out.length() == 0) {
                    try {
                        copyAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/" + ref.replace('\\', '/'), out);
                    } catch (Exception e) {
                        copyAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/" + base, out);
                    }
                }
            }
            File config = new File(destDir, "config.json");
            JSONObject destRoot = new JSONObject(new String(readAll(config), "UTF-8"));
            JSONObject destSolar = destRoot.optJSONObject("solarConfig");
            boolean changed = false;
            if (destSolar == null) {
                destSolar = new JSONObject();
                changed = true;
            }
            keys = solar.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!destSolar.has(k)) {
                    destSolar.put(k, solar.opt(k));
                    changed = true;
                }
            }
            if (changed) {
                destRoot.put("solarConfig", destSolar);
                OutputStream fos = new FileOutputStream(config);
                fos.write(destRoot.toString(2).getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception ignored) {}
    }

    /** Merge missing Y1 config blocks from bundled Aura into an existing Default folder. */
    private static void syncBundledThemeBlocks(Context ctx, File destDir) {
        try {
            byte[] raw = readAllFromAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/config.json");
            JSONObject bundled = new JSONObject(new String(raw, "UTF-8"));
            File config = new File(destDir, "config.json");
            JSONObject destRoot = new JSONObject(new String(readAll(config), "UTF-8"));
            boolean changed = false;
            for (String block : new String[]{"itemConfig", "menuConfig", "homePageConfig", "statusConfig"}) {
                JSONObject src = bundled.optJSONObject(block);
                if (src == null) continue;
                JSONObject dst = destRoot.optJSONObject(block);
                if (dst == null) {
                    destRoot.put(block, new JSONObject(src.toString()));
                    changed = true;
                    continue;
                }
                java.util.Iterator<String> keys = src.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = src.optString(k, "").trim();
                    if (v.isEmpty()) continue;
                    if (!dst.has(k) || dst.optString(k, "").trim().isEmpty()) {
                        dst.put(k, src.opt(k));
                        changed = true;
                    }
                }
            }
            if (changed) {
                OutputStream fos = new FileOutputStream(config);
                fos.write(destRoot.toString(2).getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception ignored) {}
    }

    /** Copy bundled theme files that are missing or empty on disk (post-OTA / partial SD). */
    private static void copyMissingBundledAssets(Context ctx, File destDir) {
        try {
            copyMissingAssetTree(ctx.getAssets(), BUNDLED_ASSET_DIR, destDir);
        } catch (Exception ignored) {}
    }

    private static void copyMissingAssetTree(AssetManager am, String assetDir, File destDir)
            throws Exception {
        String[] names = am.list(assetDir);
        if (names == null) return;
        for (String name : names) {
            String childAsset = assetDir + "/" + name;
            File out = new File(destDir, name);
            String[] sub = am.list(childAsset);
            if (sub != null && sub.length > 0) {
                if (!out.exists()) out.mkdirs();
                copyMissingAssetTree(am, childAsset, out);
            } else if (!out.isFile() || out.length() == 0) {
                copyAsset(am, childAsset, out);
            }
        }
    }

    private static void ensureBundledFallback(Context ctx) {
        if (bundledFallback != null) return;
        try {
            byte[] data = readAllFromAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/config.json");
            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            String title = "Aura";
            JSONObject info = json.optJSONObject("theme_info");
            if (info != null) {
                String t = info.optString("title", "").trim();
                if (!t.isEmpty()) title = t;
            }
            bundledFallback = new ThemeEntry("asset://" + BUNDLED_ASSET_DIR,
                    BUILTIN_DEFAULT_FOLDER, title, json);
        } catch (Exception ignored) {}
    }

    private static byte[] readAllFromAsset(AssetManager am, String path) throws Exception {
        InputStream in = am.open(path);
        byte[] buf = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void copyAssetTree(AssetManager am, String assetDir, File destDir) throws Exception {
        String[] names = am.list(assetDir);
        if (names == null) return;
        for (String name : names) {
            String childAsset = assetDir + "/" + name;
            String[] sub = am.list(childAsset);
            if (sub != null && sub.length > 0) {
                File subDir = new File(destDir, name);
                if (!subDir.exists()) subDir.mkdirs();
                copyAssetTree(am, childAsset, subDir);
            } else {
                copyAsset(am, childAsset, new File(destDir, name));
            }
        }
    }

    private static void copyAsset(AssetManager am, String assetPath, File out) throws Exception {
        InputStream in = am.open(assetPath);
        OutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    public static void loadAllThemes() {
        loadAllThemes(null);
    }

    public static void loadAllThemes(Context ctx) {
        if (ctx != null) themesRootPath = resolveThemesRoot(ctx);
        availableThemes.clear();
        bitmapCache.clear();
        cachedFont = null;
        cachedFontKey = "";
        availableThemes.addAll(scanDiscoveredThemes());
        if (currentThemeIndex >= availableThemes.size()) currentThemeIndex = 0;
    }

    /** Rescan theme folders; clears stale bitmap cache so home icons match the active theme. */
    public static void rescanInstalled(Context ctx) {
        bitmapCache.clear();
        if (ctx != null) themesRootPath = resolveThemesRoot(ctx);
        String prevFolder = availableThemes.isEmpty() ? BUILTIN_DEFAULT_FOLDER
                : availableThemes.get(Math.min(currentThemeIndex, availableThemes.size() - 1)).folderName;
        availableThemes.clear();
        availableThemes.addAll(scanDiscoveredThemes());
        currentThemeIndex = 0;
        for (int i = 0; i < availableThemes.size(); i++) {
            if (prevFolder.equalsIgnoreCase(availableThemes.get(i).folderName)) {
                currentThemeIndex = i;
                return;
            }
        }
        if (currentThemeIndex >= availableThemes.size()) currentThemeIndex = 0;
    }

    private static List<ThemeEntry> scanDiscoveredThemes() {
        List<ThemeEntry> out = new ArrayList<ThemeEntry>();
        Set<String> seen = new HashSet<String>();
        File root = new File(themesRootPath);
        File defaultDir = new File(root, BUILTIN_DEFAULT_FOLDER);
        ThemeEntry def = parseFolder(defaultDir);
        if (def == null && bundledFallback != null) {
            def = bundledFallback;
        }
        if (def != null) {
            out.add(def);
            seen.add(BUILTIN_DEFAULT_FOLDER.toLowerCase(Locale.US));
        }
        scanRoot(root, seen, out);
        if (out.isEmpty() && bundledFallback != null) {
            out.add(bundledFallback);
        }
        return out;
    }

    /** @deprecated use loadAllThemes */
    public static void loadThemesFromStorage(File ignored) {
        loadAllThemes();
    }

    public static boolean isBuiltInDefault(ThemeEntry entry) {
        return entry != null && BUILTIN_DEFAULT_FOLDER.equalsIgnoreCase(entry.folderName);
    }

    private static void scanRoot(File root, Set<String> seen, List<ThemeEntry> out) {
        if (!root.exists()) {
            root.mkdirs();
            return;
        }
        File[] folders = root.listFiles();
        if (folders == null) return;
        for (File sub : folders) {
            if (!sub.isDirectory()) continue;
            String name = sub.getName();
            if (name.startsWith(".")) continue;
            if (seen.contains(name.toLowerCase(Locale.US))) continue;
            ThemeEntry entry = parseFolder(sub);
            if (entry != null) {
                out.add(entry);
                seen.add(name.toLowerCase(Locale.US));
            }
        }
    }

    private static JSONObject readConfigJson(File config) {
        try {
            if (config == null || !config.isFile()) return new JSONObject();
            return new JSONObject(new String(readAll(config), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Stock Y1 ROM shipped Circular as Themes/Default before Solar Aura. */
    static boolean isLegacyStockDefaultTheme(JSONObject json) {
        if (json == null) return true;
        JSONObject info = json.optJSONObject("theme_info");
        String title = info != null ? info.optString("title", "").trim() : "";
        return isLegacyStockDefaultTitle(title);
    }

    static boolean isLegacyStockDefaultTitle(String title) {
        if (title == null || title.isEmpty()) return true;
        return "Circular".equalsIgnoreCase(title) || "Default".equalsIgnoreCase(title);
    }

    private static String displayNameForBuiltinDefault(JSONObject json, String parsedDisplay) {
        JSONObject info = json != null ? json.optJSONObject("theme_info") : null;
        String title = info != null ? info.optString("title", "").trim() : "";
        if (title.isEmpty() && parsedDisplay != null) title = parsedDisplay.trim();
        if (!isLegacyStockDefaultTitle(title)) {
            return !title.isEmpty() ? title : BUILTIN_DEFAULT_FOLDER;
        }
        if (bundledFallback != null && bundledFallback.displayName != null
                && !bundledFallback.displayName.isEmpty()) {
            return bundledFallback.displayName;
        }
        return "Aura";
    }

    private static ThemeEntry parseFolder(File folder) {
        try {
            byte[] data = readAll(new File(folder, "config.json"));
            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            if (!hasY1Blocks(json)) return null;

            String display = json.optString("name", "");
            if (display.isEmpty() && json.has("theme_info")) {
                JSONObject info = json.optJSONObject("theme_info");
                if (info != null) display = info.optString("title", "");
            }
            if (display.isEmpty()) display = folder.getName();
            if (BUILTIN_DEFAULT_FOLDER.equalsIgnoreCase(folder.getName())) {
                display = displayNameForBuiltinDefault(json, display);
            }
            return new ThemeEntry(folder.getAbsolutePath(), folder.getName(), display, json);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasY1Blocks(JSONObject json) {
        return json.has("homePageConfig") || json.has("itemConfig") || json.has("menuConfig")
                || json.has("statusConfig") || json.has("desktopWallpaper");
    }

    public static void setThemeIndex(int index) {
        if (index >= 0 && index < availableThemes.size()) {
            currentThemeIndex = index;
            bitmapCache.clear();
            scaledRowBitmapCache.clear();
            cachedFont = null;
            cachedFontKey = "";
        } else {
            currentThemeIndex = 0;
        }
    }

    public static void setThemeByFolderPath(String path) {
        int idx = findThemeIndexForPath(path);
        if (idx >= 0) setThemeIndex(idx);
    }

    /** Resolve saved path, asset alias, or folder name to a theme list index. */
    public static int findThemeIndexForPath(String path) {
        if (path == null || path.isEmpty()) return -1;
        String normalized = path.trim();
        if ("default".equalsIgnoreCase(normalized) || normalized.startsWith("asset://")) {
            return findBuiltInDefaultIndex();
        }
        for (int i = 0; i < availableThemes.size(); i++) {
            if (normalized.equals(availableThemes.get(i).folderPath)) return i;
        }
        String folder = new File(normalized).getName();
        if (!folder.isEmpty()) {
            for (int i = 0; i < availableThemes.size(); i++) {
                if (folder.equalsIgnoreCase(availableThemes.get(i).folderName)) return i;
            }
        }
        return -1;
    }

    public static int findBuiltInDefaultIndex() {
        for (int i = 0; i < availableThemes.size(); i++) {
            if (isBuiltInDefault(availableThemes.get(i))) return i;
        }
        return availableThemes.isEmpty() ? -1 : 0;
    }

    /** Disk path to persist when applying a theme (Default always uses Themes/Default). */
    public static String persistPathForTheme(ThemeEntry theme) {
        if (theme == null) return "";
        if (isBuiltInDefault(theme)) {
            return new File(themesRootPath, BUILTIN_DEFAULT_FOLDER).getAbsolutePath();
        }
        return theme.folderPath != null ? theme.folderPath : "";
    }

    public static void ensureActiveThemeOrFallback(Context ctx) {
        if (ctx != null) themesRootPath = resolveThemesRoot(ctx);
        ThemeEntry cur = getCurrentTheme();
        if (cur == null) {
            resetToBuiltinDefault();
            return;
        }
        if (cur.folderPath != null && cur.folderPath.startsWith("asset://")) return;
        File cfg = new File(cur.folderPath, "config.json");
        if (!cfg.isFile() || cfg.length() == 0) {
            resetToBuiltinDefault();
        }
    }

    public static void resetToBuiltinDefault() {
        for (int i = 0; i < availableThemes.size(); i++) {
            if (isBuiltInDefault(availableThemes.get(i))) {
                setThemeIndex(i);
                return;
            }
        }
        setThemeIndex(0);
    }

    public static int getCurrentThemeIndex() {
        return currentThemeIndex;
    }

    public static ThemeEntry getCurrentTheme() {
        if (availableThemes.isEmpty()) {
            if (bundledFallback != null) return bundledFallback;
            try {
                JSONObject stub = new JSONObject();
                stub.put("homePageConfig", new JSONObject());
                return new ThemeEntry("", BUILTIN_DEFAULT_FOLDER, "Aura", stub);
            } catch (Exception e) {
                throw new IllegalStateException("no theme");
            }
        }
        int idx = currentThemeIndex;
        if (idx < 0 || idx >= availableThemes.size()) idx = 0;
        return availableThemes.get(idx);
    }

    // --- colors ---

    public static int getTextColorPrimary() {
        ThemeEntry t = getCurrentTheme();
        JSONObject item = t.root.optJSONObject("itemConfig");
        if (item != null) {
            int c = parseColorOpt(item, "itemTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        JSONObject menu = t.root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuItemTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xFFFFFFFF;
    }

    public static int getTextColorSecondary() {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog != null) {
            int c = parseColorOpt(dialog, "dialogTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xFF888888;
    }

    public static int getOverlayBackgroundColor() {
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuBackgroundColor");
            if (c != Integer.MIN_VALUE) return withAlpha(c, 0x88);
        }
        return 0x88000000;
    }

    public static void setStatusBarMatchItemText(boolean match) {
        statusBarMatchItemText = match;
    }

    public static boolean isStatusBarMatchItemText() {
        return statusBarMatchItemText;
    }

    /** ponytail: statusConfig.statusBarColor with alpha; dark fallback when unset on non-Y1 themes */
    public static int getStatusBarBackgroundColor() {
        JSONObject status = getCurrentTheme().root.optJSONObject("statusConfig");
        if (status != null) {
            int c = parseColorOpt(status, "statusBarColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        if (hasY1Blocks(getCurrentTheme().root)) return 0x00000000;
        return 0xE6121212;
    }

    /** solarConfig.statusBarTextColor if set; else item text when match on, else Y1 statusConfig chain. */
    public static int getStatusBarTextColor() {
        JSONObject solar = solarBlock();
        if (solar != null) {
            int solarBar = parseColorOpt(solar, "statusBarTextColor");
            if (solarBar != Integer.MIN_VALUE) return solarBar;
        }
        if (statusBarMatchItemText) {
            return getItemTextColorNormal();
        }
        JSONObject home = getCurrentTheme().root.optJSONObject("homePageConfig");
        if (home != null) {
            int c = parseColorOpt(home, "statusBarTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        JSONObject status = getCurrentTheme().root.optJSONObject("statusConfig");
        if (status != null) {
            int sc = parseColorOpt(status, "statusTextColor");
            if (sc != Integer.MIN_VALUE) return sc;
        }
        return 0xFFFFFFFF;
    }

    /** Passive hints, tooltips, helper lines — same chain as status bar clock text. */
    public static int getHintTextColor() {
        return getStatusBarTextColor();
    }

    /** Section headings / de-emphasized labels — status bar hue at reduced alpha. */
    public static int getSectionHeaderTextColor() {
        return (getStatusBarTextColor() & 0x00FFFFFF) | 0xBB000000;
    }

    /** Status bar hue at arbitrary alpha (e.g. keyboard dim keys, disabled values). */
    public static int getDimmedTextColor(int alpha) {
        return (getStatusBarTextColor() & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** ponytail: menuBackgroundColor is overlay tint only — never an unselected row fill on Y1 themes */
    public static int getListButtonNormalBg() {
        if (hasY1Blocks(getCurrentTheme().root)) return 0x00000000;
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuBackgroundColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0x15FFFFFF;
    }

    /** ponytail: Melody/Holo empty row art — progressColor before itemSelectedTextColor */
    public static int getRowSelectionFillColor() {
        JSONObject player = getCurrentTheme().root.optJSONObject("playerConfig");
        if (player != null) {
            int c = parseColorOpt(player, "progressColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item != null) {
            int c = parseColorOpt(item, "itemSelectedTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xDDFFFFFF;
    }

    public static int getListButtonFocusedBg() {
        return getRowSelectionFillColor();
    }

    public static int getListButtonFocusedTextColor() {
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item != null) {
            int c = parseColorOpt(item, "itemSelectedTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xFF000000;
    }

    public static int getProgressColor() {
        JSONObject player = getCurrentTheme().root.optJSONObject("playerConfig");
        if (player != null) {
            int c = parseColorOpt(player, "progressColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getListButtonFocusedBg();
    }

    public static int getProgressBackgroundColor() {
        JSONObject player = getCurrentTheme().root.optJSONObject("playerConfig");
        if (player != null) {
            int c = parseColorOpt(player, "progressBackgroundColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0x44FFFFFF;
    }

    private static JSONObject solarBlock(JSONObject root) {
        return root != null ? root.optJSONObject("solarConfig") : null;
    }

    private static JSONObject solarBlock() {
        JSONObject solar = solarBlock(getCurrentTheme().root);
        if (bundledFallback != null) {
            JSONObject bundled = solarBlock(bundledFallback.root);
            if (bundled != null) {
                try {
                    if (solar == null) return bundled;
                    JSONObject merged = new JSONObject(solar.toString());
                    java.util.Iterator<String> keys = bundled.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (!merged.has(k)) merged.put(k, bundled.opt(k));
                    }
                    return merged;
                } catch (Exception ignored) {}
            }
        }
        return solar;
    }

    private static Integer colorFromSolarThen(String solarKey, JSONObject legacy, String legacyKey) {
        JSONObject solar = solarBlock();
        if (solar != null) {
            int c = parseColorOpt(solar, solarKey);
            if (c != Integer.MIN_VALUE) return c;
        }
        if (legacy != null && legacyKey != null) {
            int c = parseColorOpt(legacy, legacyKey);
            if (c != Integer.MIN_VALUE) return c;
        }
        return null;
    }

    /** ponytail: solarConfig.nowPlayingInfoBars — default | none | item */
    public static final int NOW_PLAYING_BARS_DEFAULT = 0;
    public static final int NOW_PLAYING_BARS_NONE = 1;
    public static final int NOW_PLAYING_BARS_ITEM = 2;

    public static int getNowPlayingInfoBarMode() {
        JSONObject root = getCurrentTheme().root;
        Integer solarMode = parseNowPlayingInfoBarMode(root.optJSONObject("solarConfig"));
        if (solarMode != null) return solarMode;
        Integer player = parseNowPlayingInfoBarMode(root.optJSONObject("playerConfig"));
        if (player != null) return player;
        return NOW_PLAYING_BARS_DEFAULT;
    }

    private static Integer parseNowPlayingInfoBarMode(JSONObject block) {
        if (block == null || !block.has("nowPlayingInfoBars")) return null;
        String mode = block.optString("nowPlayingInfoBars", "default").trim().toLowerCase(Locale.US);
        if ("none".equals(mode) || "off".equals(mode) || "disabled".equals(mode) || "false".equals(mode)) {
            return NOW_PLAYING_BARS_NONE;
        }
        if ("item".equals(mode) || "matchitem".equals(mode) || "match_item".equals(mode)) {
            return NOW_PLAYING_BARS_ITEM;
        }
        return NOW_PLAYING_BARS_DEFAULT;
    }

    /** solarConfig / playerConfig nowPlayingTextColour|Color — null if unset */
    public static Integer getNowPlayingTextColorOpt() {
        JSONObject root = getCurrentTheme().root;
        Integer c = parseNowPlayingTextColorOpt(root.optJSONObject("solarConfig"));
        if (c != null) return c;
        return parseNowPlayingTextColorOpt(root.optJSONObject("playerConfig"));
    }

    private static Integer parseNowPlayingTextColorOpt(JSONObject block) {
        if (block == null) return null;
        if (block.has("nowPlayingTextColour")) {
            int c = parseColorOpt(block, "nowPlayingTextColour");
            return c != Integer.MIN_VALUE ? c : null;
        }
        if (block.has("nowPlayingTextColor")) {
            int c = parseColorOpt(block, "nowPlayingTextColor");
            return c != Integer.MIN_VALUE ? c : null;
        }
        return null;
    }

    public static int getNowPlayingTextColor() {
        Integer c = getNowPlayingTextColorOpt();
        if (c != null) return c;
        if (getNowPlayingInfoBarMode() == NOW_PLAYING_BARS_ITEM) return getItemTextColorSelected();
        return 0xFFFFFFFF;
    }

    public static int getProgressTextColor() {
        JSONObject player = getCurrentTheme().root.optJSONObject("playerConfig");
        if (player != null) {
            int c = parseColorOpt(player, "progressTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        Integer np = getNowPlayingTextColorOpt();
        if (np != null) return np;
        return 0xFFFFFFFF;
    }

    public static Drawable getNowPlayingInfoBarBackground(android.content.res.Resources res, int widthPx, int heightPx) {
        int mode = getNowPlayingInfoBarMode();
        if (mode == NOW_PLAYING_BARS_NONE) {
            return new android.graphics.drawable.ColorDrawable(0);
        }
        if (mode == NOW_PLAYING_BARS_ITEM) {
            Drawable d = getItemRowBackgroundScaled(res, true, widthPx, heightPx);
            if (d != null) return d;
        }
        return new android.graphics.drawable.ColorDrawable(0x1affffff);
    }

    /** solarConfig.highContrastText — outline via shadow layer (Solar-only) */
    public static boolean isHighContrastTextEnabled() {
        JSONObject solar = solarBlock();
        return solar != null && solar.optBoolean("highContrastText", false);
    }

    public static float getTextStrokeWidthDp() {
        JSONObject solar = solarBlock();
        if (solar != null && solar.has("textStrokeWidthDp")) {
            return (float) solar.optDouble("textStrokeWidthDp", 1.5);
        }
        return 1.5f;
    }

    public static Integer getTextStrokeColorOpt() {
        JSONObject solar = solarBlock();
        if (solar == null) return null;
        if (solar.has("textStrokeColour")) {
            int c = parseColorOpt(solar, "textStrokeColour");
            return c != Integer.MIN_VALUE ? c : null;
        }
        if (solar.has("textStrokeColor")) {
            int c = parseColorOpt(solar, "textStrokeColor");
            return c != Integer.MIN_VALUE ? c : null;
        }
        return null;
    }

    /** Auto black/white stroke from fill luminance */
    public static int resolveTextStrokeColor(int fillColor) {
        Integer opt = getTextStrokeColorOpt();
        if (opt != null) return opt;
        int r = (fillColor >> 16) & 0xFF;
        int g = (fillColor >> 8) & 0xFF;
        int b = fillColor & 0xFF;
        double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        return lum > 140 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Fixed neutral panel for hold-Back context menus — not theme dialogConfig art. */
    public static int getContextMenuPanelColor() {
        return 0xEE252528;
    }

    /** WCAG relative luminance (sRGB), 0..1 */
    public static double relativeLuminance(int argb) {
        double r = channelLinear((argb >> 16) & 0xFF);
        double g = channelLinear((argb >> 8) & 0xFF);
        double b = channelLinear(argb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channelLinear(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    public static double contrastRatio(int fg, int bg) {
        double l1 = relativeLuminance(fg);
        double l2 = relativeLuminance(bg);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** Representative brand orange from colour logotype — contrast probe for wordmark variant. */
    public static final int SOLAR_BRAND_ORANGE = 0xFFF5A020;

    /** Pick landscape SOLAR logotype asset path for inline attribution on {@code backgroundArgb}. */
    public static String pickSolarLogotypeAsset(int backgroundArgb) {
        int bg = backgroundArgb | 0xFF000000;
        if (contrastRatio(SOLAR_BRAND_ORANGE, bg) >= 3.0) {
            return "logo/solar_logotype_colour_full_res.png";
        }
        return relativeLuminance(bg) > 0.45
                ? "logo/solar_logotype_black_full_res.png"
                : "logo/solar_logotype_white_full_res.png";
    }

    /** ponytail: min 3:1 for menu labels on neutral panel; fix light-on-light themes */
    public static int ensureReadableOnBackground(int textColor, int backgroundColor) {
        int fg = textColor | 0xFF000000;
        int bg = (backgroundColor | 0xFF000000);
        if (contrastRatio(fg, bg) >= 3.0) return textColor;
        return relativeLuminance(bg) > 0.45 ? 0xFF1A1A1A : 0xFFE8E8E8;
    }

    /** Selected row text — only adjust when selection uses a solid fill, not a theme bitmap. */
    public static int textOnRowSelection(int selectedColor) {
        return textOnRowSelection(selectedColor, false);
    }

    public static int textOnRowSelection(int selectedColor, boolean menuRows) {
        if (usesThemedSelectionBitmap(menuRows)) return selectedColor;
        if (hasY1Blocks(getCurrentTheme().root)) return selectedColor;
        return ensureReadableOnBackground(selectedColor, getRowSelectionFillColor());
    }

    /** Y1 themes decorate selected rows with PNG assets, not solid progressColor fills. */
    public static boolean usesThemedSelectionBitmap(boolean menuRows) {
        ThemeEntry t = getCurrentTheme();
        if (menuRows) {
            JSONObject menu = t.root.optJSONObject("menuConfig");
            if (menu != null) {
                String path = menu.optString("menuItemSelectedBackground", "").trim();
                if (!path.isEmpty() && getThemeBitmap(path) != null) return true;
            }
        }
        JSONObject item = t.root.optJSONObject("itemConfig");
        if (item != null) {
            String path = item.optString("itemSelectedBackground", "").trim();
            if (!path.isEmpty() && getThemeBitmap(path) != null) return true;
        }
        return false;
    }

    public static int contextMenuMutedText(int themeHintColor) {
        int muted = withAlpha(themeHintColor, 0xBB);
        return ensureReadableOnBackground(muted, getContextMenuPanelColor());
    }

    /** Greyscale / neutral theme colours (black, white, grey) with negligible hue. */
    public static boolean isAchromaticColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return (max - min) <= 24;
    }

    /**
     * Context quick menu without row PNG decoration: selected/unselected theme colours
     * can collapse to the same grey/white on the neutral panel — dim unselected from selected.
     */
    public static boolean needsContextMenuUnselectedDimming(int themeNormal, int themeSelected,
            boolean menuRows) {
        if (usesThemedSelectionBitmap(menuRows)) return false;
        int normal = themeNormal | 0xFF000000;
        int selected = themeSelected | 0xFF000000;
        if (!isAchromaticColor(normal) || !isAchromaticColor(selected)) return false;
        return contrastRatio(normal, selected) < 2.0;
    }

    /** Unselected label/icon colour on the hold-Back context panel. */
    public static int contextMenuTextNormal(int themeNormal, int themeSelected, int panelBg,
            boolean menuRows) {
        int readableNormal = ensureReadableOnBackground(themeNormal, panelBg);
        if (!needsContextMenuUnselectedDimming(themeNormal, themeSelected, menuRows)) {
            return readableNormal;
        }
        int selectedOnPanel = ensureReadableOnBackground(themeSelected | 0xFF000000, panelBg);
        for (float mix = 0.45f; mix <= 0.72f; mix += 0.09f) {
            int dimmed = ensureReadableOnBackground(blendTowardBackground(selectedOnPanel, panelBg, mix),
                    panelBg);
            if (contrastRatio(dimmed, selectedOnPanel) >= 1.5) return dimmed;
        }
        return readableNormal;
    }

    public static int contextMenuTextSelected(int themeSelected, boolean menuRows) {
        return textOnRowSelection(themeSelected, menuRows);
    }

    private static int blendTowardBackground(int color, int background, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int ca = color | 0xFF000000;
        int ba = background | 0xFF000000;
        int a = (color >>> 24) & 0xFF;
        int cr = (ca >> 16) & 0xFF;
        int cg = (ca >> 8) & 0xFF;
        int cb = ca & 0xFF;
        int br = (ba >> 16) & 0xFF;
        int bg = (ba >> 8) & 0xFF;
        int bb = ba & 0xFF;
        int r = (int) (cr + (br - cr) * amount);
        int g = (int) (cg + (bg - cg) * amount);
        int b = (int) (cb + (bb - cb) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void applyThemedTextStyle(TextView tv, int fillColor) {
        if (tv == null) return;
        tv.setTextColor(fillColor);
        if (!isHighContrastTextEnabled()) {
            tv.setShadowLayer(0, 0, 0, 0);
            return;
        }
        float density = tv.getResources().getDisplayMetrics().density;
        float radius = getTextStrokeWidthDp() * density;
        tv.setShadowLayer(radius, 0, 0, resolveTextStrokeColor(fillColor));
    }

    public static int getButtonRadius() {
        ThemeEntry t = getCurrentTheme();
        JSONObject solar = solarBlock(t.root);
        if (solar != null && solar.has("button_radius")) return solar.optInt("button_radius", 10);
        if (t.root.has("button_radius")) return t.root.optInt("button_radius", 10);
        return 10;
    }

    public static boolean hasThemeWallpaper() {
        return hasThemeWallpaper(getCurrentTheme());
    }

    public static boolean hasThemeWallpaper(ThemeEntry entry) {
        if (entry == null) return false;
        return !entry.root.optString("desktopWallpaper", "").isEmpty()
                || !entry.root.optString("globalWallpaper", "").isEmpty();
    }

    public static Bitmap getSettingMask() {
        JSONObject setting = getCurrentTheme().root.optJSONObject("settingConfig");
        if (setting == null) return null;
        return getThemeBitmap(setting.optString("settingMask", ""));
    }

    public static Bitmap getSettingIcon(String configKey) {
        if (configKey == null || configKey.isEmpty()) return null;
        JSONObject setting = getCurrentTheme().root.optJSONObject("settingConfig");
        if (setting == null) return null;
        return getThemeBitmap(setting.optString(configKey, ""));
    }

    public static int getDialogTextColor() {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog != null) {
            int c = parseColorOpt(dialog, "dialogTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getTextColorPrimary();
    }

    public static int getDialogHighlightColor() {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog != null) {
            int c = parseColorOpt(dialog, "dialogOptionSelectedTextColor");
            if (c != Integer.MIN_VALUE) return c;
            c = parseColorOpt(dialog, "dialogHighlightColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getItemTextColorSelected();
    }

    public static int getDialogOptionTextColorNormal() {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog != null) {
            int c = parseColorOpt(dialog, "dialogOptionTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getDialogTextColor();
    }

    public static int getDialogOptionTextColorSelected() {
        return getDialogHighlightColor();
    }

    public static Drawable getDialogBackground(android.content.res.Resources res) {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog == null) return null;
        Bitmap bmp = getThemeBitmap(dialog.optString("dialogBackground", ""));
        if (bmp == null) return null;
        return new BitmapDrawable(res, bmp);
    }

    public static int getDialogBackgroundColor() {
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog != null) {
            int c = parseColorOpt(dialog, "dialogBackgroundColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xE6000000;
    }

    public static Drawable getDialogOptionRowBackgroundScaled(android.content.res.Resources res,
            boolean selected, int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return null;
        JSONObject dialog = getCurrentTheme().root.optJSONObject("dialogConfig");
        if (dialog == null) return null;
        String path = selected
                ? dialog.optString("dialogOptionSelectedBackground", "")
                : dialog.optString("dialogOptionBackground", "");
        if (path.isEmpty()) return null;
        Bitmap bmp = getThemeBitmap(path);
        if (bmp == null) return null;
        Bitmap scaled = cachedScaledRowBitmap("dialog", selected, widthPx, heightPx, bmp);
        return new BitmapDrawable(res, scaled);
    }

    public static Bitmap centerCropBitmap(Bitmap src, int targetW, int targetH) {
        if (src == null || targetW <= 0 || targetH <= 0) return src;
        float scale = Math.max(targetW / (float) src.getWidth(), targetH / (float) src.getHeight());
        int scaledW = Math.max(1, (int) (src.getWidth() * scale));
        int scaledH = Math.max(1, (int) (src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        int x = Math.max(0, (scaledW - targetW) / 2);
        int y = Math.max(0, (scaledH - targetH) / 2);
        int cropW = Math.min(targetW, scaledW - x);
        int cropH = Math.min(targetH, scaledH - y);
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, cropW, cropH);
        if (scaled != src && scaled != cropped) scaled.recycle();
        return cropped;
    }

    public static Typeface getCustomFont() {
        ThemeEntry t = getCurrentTheme();
        String fontKey = t.folderPath;
        String fontFile = t.root.optString("fontFamily", "");
        if (fontFile.isEmpty()) return Typeface.DEFAULT;
        String cacheKey = fontKey + ":" + fontFile;
        if (cachedFont != null && cacheKey.equals(cachedFontKey)) return cachedFont;
        File f = new File(t.folderPath, fontFile);
        if (f.isFile()) {
            try {
                cachedFont = Typeface.createFromFile(f);
                cachedFontKey = cacheKey;
                return cachedFont;
            } catch (Exception ignored) {}
        }
        return Typeface.DEFAULT;
    }

    // --- assets ---

    /** ponytail: themes use @filename prefix; try exact path then stripped */
    static File resolveThemeAssetFile(String folderPath, String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || folderPath == null) return null;
        String path = relativePath.trim();
        File themeDir = new File(folderPath);
        String catalogFolder = ThemeDownloader.catalogFolderFor(themeDir);
        String gallery = ThemeDownloader.canonicalGalleryPath(catalogFolder, path);
        if (gallery != null) {
            File resolved = ThemeDownloader.resolveAssetFile(
                    new File(themesRootPath), themeDir, catalogFolder, gallery);
            if (resolved.isFile()) return resolved;
        }
        File exact = new File(folderPath, path);
        if (exact.isFile()) return exact;
        if (path.startsWith("@")) {
            File stripped = new File(folderPath, path.substring(1));
            if (stripped.isFile()) return stripped;
        }
        File parent = exact.getParentFile();
        if (parent != null && parent.isDirectory()) {
            String want = exact.getName();
            File[] files = parent.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().equalsIgnoreCase(want)) return f;
                }
            }
        }
        File dir = new File(folderPath);
        if (dir.isDirectory()) {
            String want = new File(path).getName();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().equalsIgnoreCase(want)) return f;
                }
            }
        }
        return null;
    }

    /** Max decode side for solarConfig icons (matches y1_setting_icon_max at ~2x density). */
    private static final int SOLAR_CONFIG_ICON_MAX_PX = 292;

    static Bitmap decodeBitmapFileMaxSide(String path, int maxSide) {
        if (path == null || maxSide <= 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeBundledThemeAsset(String relativePath, int maxSide) {
        if (assetContext == null || relativePath == null || relativePath.isEmpty()) return null;
        String norm = relativePath.trim().replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        String[] candidates = {
                BUNDLED_ASSET_DIR + "/" + norm,
                BUNDLED_ASSET_DIR + "/" + new File(norm).getName()
        };
        AssetManager am = assetContext.getAssets();
        for (String assetPath : candidates) {
            Bitmap bmp = decodeAssetStreamMaxSide(am, assetPath, maxSide);
            if (bmp != null) return bmp;
        }
        return null;
    }

    private static Bitmap decodeAssetStreamMaxSide(AssetManager am, String assetPath, int maxSide) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = am.open(assetPath);
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            if (maxSide > 0) {
                while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = am.open(assetPath);
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static Bitmap decodeBitmapFile(String path) {
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** Disk path, asset:// theme folder, or bundled Aura fallback for built-in Default. */
    private static Bitmap decodeThemeBitmapForEntry(ThemeEntry entry, String relativePath, int maxSide) {
        if (entry == null || relativePath == null || relativePath.isEmpty()) return null;
        Bitmap bmp = null;
        String folderPath = entry.folderPath;
        if (folderPath != null && folderPath.startsWith("asset://")) {
            bmp = decodeBundledThemeAsset(relativePath, maxSide);
        } else {
            File f = resolveThemeAssetFile(folderPath, relativePath);
            if (f != null && f.isFile()) {
                bmp = maxSide > 0
                        ? decodeBitmapFileMaxSide(f.getAbsolutePath(), maxSide)
                        : decodeBitmapFile(f.getAbsolutePath());
            }
        }
        if (bmp == null && isBuiltInDefault(entry)) {
            bmp = decodeBundledThemeAsset(relativePath, maxSide);
        }
        return bmp;
    }

    public static Bitmap getThemeBitmap(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        ThemeEntry t = getCurrentTheme();
        String cacheKey = t.folderPath + ":" + relativePath;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        Bitmap bmp = decodeThemeBitmapForEntry(t, relativePath, 0);
        if (bmp != null) bitmapCache.put(cacheKey, bmp);
        return bmp;
    }

    public static Bitmap getCustomIcon(String iconFileName, Context context, int defaultResId) {
        Bitmap themed = getThemeBitmap(iconFileName);
        if (themed != null) return themed;
        ThemeEntry t = getCurrentTheme();
        if (t.folderPath != null && !t.folderPath.startsWith("asset://")) {
            File iconFile = new File(t.folderPath, iconFileName);
            if (iconFile.isFile()) {
                try {
                    return BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                } catch (Exception ignored) {}
            }
        }
        return BitmapFactory.decodeResource(context.getResources(), defaultResId);
    }

    /** solarConfig key for a Solar-only app label, e.g. Podcasts → appPodcasts, PC Upload → appPC_Upload */
    public static String solarAppConfigKey(String appName) {
        if (appName == null) return null;
        String suffix = appName.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (suffix.isEmpty()) return null;
        return "app" + suffix;
    }

    /** Legacy key before underscores replaced stripped separators (appPCUpload). */
    private static String solarAppConfigKeyLegacy(String appName) {
        if (appName == null) return null;
        String suffix = appName.replaceAll("[^a-zA-Z0-9]", "");
        if (suffix.isEmpty()) return null;
        return "app" + suffix;
    }

    /** True when the active theme's config.json sets {@code solarConfig.{key}} (not bundled merge). */
    public static boolean hasThemeSolarConfigKey(String key) {
        if (key == null || key.isEmpty()) return false;
        JSONObject solar = solarBlock(getCurrentTheme().root);
        if (solar == null) return false;
        return !solar.optString(key, "").trim().isEmpty();
    }

    /**
     * solarConfig icon by key — theme asset filename; null if unset.
     * Soulseek keys: {@code appSoulseek} (home + Settings → Soulseek row),
     * {@code soulseekSearch}, {@code soulseekAccount}, {@code soulseekRegenerate}.
     * App labels use {@link #solarAppConfigKey}: e.g. "PC Upload" → {@code appPC_Upload}.
     */
    public static Bitmap getSolarConfigIcon(String key) {
        JSONObject solar = solarBlock();
        if (solar == null || key == null || key.isEmpty()) return null;
        String path = solar.optString(key, "").trim();
        if (path.isEmpty()) return null;
        ThemeEntry t = getCurrentTheme();
        String cacheKey = t.folderPath + ":solar:" + key;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        Bitmap bmp = null;
        File f = resolveThemeAssetFile(t.folderPath, path);
        if (f != null) {
            bmp = decodeBitmapFileMaxSide(f.getAbsolutePath(), SOLAR_CONFIG_ICON_MAX_PX);
        }
        if (bmp == null) {
            bmp = decodeBundledThemeAsset(path, SOLAR_CONFIG_ICON_MAX_PX);
        }
        if (bmp != null) bitmapCache.put(cacheKey, bmp);
        return bmp;
    }

    /** solarConfig app{Name} — theme asset for Solar-only apps; null if unset */
    public static Bitmap getSolarAppIcon(String appName) {
        JSONObject solar = solarBlock();
        if (solar == null) return null;
        String key = solarAppConfigKey(appName);
        if (key == null) return null;
        String path = solar.optString(key, "").trim();
        if (path.isEmpty()) {
            String legacy = solarAppConfigKeyLegacy(appName);
            if (legacy != null && !legacy.equals(key)) {
                path = solar.optString(legacy, "").trim();
            }
        }
        if (path.isEmpty()) return null;
        return getThemeBitmap(path);
    }

    /** homePageConfig key with stock drawable fallback */
    public static Bitmap getHomeIcon(Context context, String y1Key, int defaultResId) {
        JSONObject home = getCurrentTheme().root.optJSONObject("homePageConfig");
        if (home != null) {
            String path = home.optString(y1Key, "");
            if (!path.isEmpty()) {
                Bitmap bmp = getThemeBitmap(path);
                if (bmp != null) return bmp;
            }
        }
        return BitmapFactory.decodeResource(context.getResources(), defaultResId);
    }

    public static Bitmap getWallpaper(boolean desktop) {
        ThemeEntry t = getCurrentTheme();
        return getWallpaper(t, desktop);
    }

    public static Bitmap getWallpaper(ThemeEntry entry, boolean desktop) {
        if (entry == null) return null;
        String path = desktop
                ? entry.root.optString("desktopWallpaper", "")
                : entry.root.optString("globalWallpaper", "");
        if (path.isEmpty() && desktop) path = entry.root.optString("globalWallpaper", "");
        if (path.isEmpty()) return null;
        return getThemeEntryBitmap(entry, path);
    }

    public static Bitmap getThemeEntryBitmap(ThemeEntry entry, String relativePath) {
        if (entry == null || relativePath == null || relativePath.isEmpty()) return null;
        return decodeThemeBitmapForEntry(entry, relativePath, 0);
    }

    /** Installed-theme wallpaper candidates (480×360 Y1 assets). */
    public static final class WallpaperPick {
        public static final String KEY_DESKTOP = "desktopWallpaper";
        public static final String KEY_GLOBAL = "globalWallpaper";

        public final String themeFolder;
        public final String configKey;
        public final String themeName;
        public final String assetPath;

        WallpaperPick(String themeFolder, String configKey, String themeName, String assetPath) {
            this.themeFolder = themeFolder;
            this.configKey = configKey;
            this.themeName = themeName;
            this.assetPath = assetPath;
        }

        public String prefToken() {
            return themeFolder + "\0" + configKey;
        }

        public static WallpaperPick fromPrefToken(String token) {
            if (token == null) return null;
            int sep = token.indexOf('\0');
            if (sep <= 0 || sep >= token.length() - 1) return null;
            return new WallpaperPick(token.substring(0, sep), token.substring(sep + 1), "", "");
        }
    }

    public static List<WallpaperPick> listWallpaperPicks() {
        List<WallpaperPick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ThemeEntry t : availableThemes) {
            addWallpaperPick(out, seen, t, WallpaperPick.KEY_DESKTOP);
            addWallpaperPick(out, seen, t, WallpaperPick.KEY_GLOBAL);
        }
        return out;
    }

    private static void addWallpaperPick(List<WallpaperPick> out, Set<String> seen,
                                         ThemeEntry t, String configKey) {
        String rel = t.root.optString(configKey, "");
        if (rel.isEmpty()) return;
        File f = resolveThemeAssetFile(t.folderPath, rel);
        if (f == null || !f.isFile()) return;
        String abs = f.getAbsolutePath();
        if (seen.contains(abs)) return;
        seen.add(abs);
        out.add(new WallpaperPick(t.folderName, configKey, t.displayName, rel));
    }

    public static Bitmap loadWallpaperPick(String prefToken) {
        WallpaperPick pick = WallpaperPick.fromPrefToken(prefToken);
        if (pick == null) return null;
        for (ThemeEntry t : availableThemes) {
            if (pick.themeFolder.equalsIgnoreCase(t.folderName)) {
                String rel = t.root.optString(pick.configKey, "");
                if (!rel.isEmpty()) return getThemeEntryBitmap(t, rel);
            }
        }
        return null;
    }

    public static Bitmap getDesktopMask() {
        String path = getCurrentTheme().root.optString("desktopMask", "");
        if (path.isEmpty()) return null;
        return getThemeBitmap(path);
    }

    public static Bitmap getThemeCover(ThemeEntry entry) {
        if (entry == null) return null;
        String path = entry.root.optString("themeCover", "");
        if (path.isEmpty()) return null;
        File f = resolveThemeAssetFile(entry.folderPath, path);
        if (f == null) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    public static Drawable getItemRowBackgroundScaled(android.content.res.Resources res, boolean selected, int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return null;
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item == null) return null;
        String path = selected
                ? item.optString("itemSelectedBackground", "")
                : item.optString("itemBackground", "");
        if (path.isEmpty()) return null;
        Bitmap bmp = getThemeBitmap(path);
        if (bmp == null) return null;
        int w = widthPx > 0 ? widthPx : bmp.getWidth();
        Bitmap scaled = cachedScaledRowBitmap("item", selected, w, heightPx, bmp);
        return new BitmapDrawable(res, scaled);
    }

    /** ponytail: follow Y1 config semantics for both selected and unselected menu rows */
    public static Drawable getMenuRowBackgroundScaled(android.content.res.Resources res, boolean selected, int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return null;
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu == null) return getItemRowBackgroundScaled(res, selected, widthPx, heightPx);
        String path = selected
                ? menu.optString("menuItemSelectedBackground", "")
                : menu.optString("menuItemBackground", "");
        if (path.isEmpty()) return getItemRowBackgroundScaled(res, selected, widthPx, heightPx);
        Bitmap bmp = getThemeBitmap(path);
        if (bmp == null) return getItemRowBackgroundScaled(res, selected, widthPx, heightPx);
        Bitmap scaled = cachedScaledRowBitmap("menu", selected, widthPx, heightPx, bmp);
        return new BitmapDrawable(res, scaled);
    }

    private static Bitmap cachedScaledRowBitmap(String kind, boolean selected, int w, int h, Bitmap source) {
        String key = kind + (selected ? 's' : 'n') + '@' + currentThemeIndex + 'x' + w + 'x' + h;
        Bitmap cached = scaledRowBitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        scaledRowBitmapCache.put(key, scaled);
        return scaled;
    }

    /** ponytail: Y1 firmware — menu rows vs list rows use separate config blocks */
    public static boolean y1UnselectedRowTransparent(boolean menuRows) {
        ThemeEntry t = getCurrentTheme();
        if (!hasY1Blocks(t.root)) return false;
        if (menuRows) {
            JSONObject menu = t.root.optJSONObject("menuConfig");
            if (menu != null && !menu.optString("menuItemBackground", "").trim().isEmpty()) return false;
            return true;
        }
        JSONObject item = t.root.optJSONObject("itemConfig");
        if (item != null && !item.optString("itemBackground", "").trim().isEmpty()) return false;
        return true;
    }

    public static int getSettingMenuTextColorNormal() {
        JSONObject setting = getCurrentTheme().root.optJSONObject("settingConfig");
        Integer c = colorFromSolarThen("settingUnselectedColor", setting, "unselectedColor");
        if (c != null) return c;
        return getItemTextColorNormal();
    }

    public static int getSettingMenuTextColorSelected() {
        JSONObject setting = getCurrentTheme().root.optJSONObject("settingConfig");
        Integer c = colorFromSolarThen("settingSelectedColor", setting, "selectedColor");
        if (c != null) return c;
        return getItemTextColorSelected();
    }

    /** ponytail: stock menuItemSetTextColor — menuConfig only; home/settings use itemSetTextColor */
    public static int getMenuItemTextColorNormal() {
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuItemTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getItemTextColorNormal();
    }

    public static int getMenuItemTextColorSelected() {
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuItemSelectedTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getItemTextColorSelected();
    }

    /** ponytail: stock Y1 only stretches menuItemBackground for the column; never itemBackground or menuBackgroundColor */
    public static Drawable getMenuPanelBackgroundScaled(android.content.res.Resources res, int widthPx, int heightPx) {
        if (widthPx < 1 || heightPx < 1) return null;
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu == null) return null;
        if (menu.optString("menuItemBackground", "").trim().isEmpty()) return null;
        return getMenuRowBackgroundScaled(res, false, widthPx, heightPx);
    }

    /** ponytail: stock home — itemConfig text; solarConfig overrides home menu colours */
    public static int getHomeMenuTextColorNormal() {
        JSONObject home = getCurrentTheme().root.optJSONObject("homePageConfig");
        Integer c = colorFromSolarThen("homeUnselectedColor", home, "unselectedColor");
        if (c != null) return c;
        return getItemTextColorNormal();
    }

    public static int getHomeMenuTextColorSelected() {
        JSONObject home = getCurrentTheme().root.optJSONObject("homePageConfig");
        Integer c = colorFromSolarThen("homeSelectedColor", home, "selectedColor");
        if (c != null) return c;
        return getItemTextColorSelected();
    }

    /** @deprecated use getMenuPanelBackgroundScaled; menuBackgroundColor is not a panel drawable on stock Y1 */
    public static Drawable getHomeMenuPanelBackground(android.content.res.Resources res) {
        return null;
    }

    public static Bitmap getItemRightArrow() {
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item == null) return null;
        return getThemeBitmap(item.optString("itemRightArrow", ""));
    }

    public static int batteryLevelIndex(int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        // Stock Y1 quarters: [0,25) [25,50) [50,75) [75,100]
        if (pct < 25) return 0;
        if (pct < 50) return 1;
        if (pct < 75) return 2;
        return 3;
    }

    static final int BATTERY_FRAME_COUNT = 4;
    public static final int WIFI_FRAME_COUNT = 3;
    private static final int WIFI_RSSI_MIN = -100;
    private static final int WIFI_RSSI_MAX = -55;

    /** Parse solarConfig/statusConfig wifi[] — index 0 weak … 2 strong (3 bars). */
    static String[] parseWifiFrameArray(JSONObject obj) {
        String[] out = new String[WIFI_FRAME_COUNT];
        if (obj == null) return out;
        org.json.JSONArray arr = obj.optJSONArray("wifi");
        if (arr != null && arr.length() > 0) {
            int n = Math.min(arr.length(), WIFI_FRAME_COUNT);
            for (int i = 0; i < n; i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out[i] = s;
            }
        }
        return out;
    }

    /** Parse statusConfig/solarConfig battery[] — array index is the quarter (0 → 0–24%). */
    static String[] parseBatteryFrameArray(JSONObject obj, String primaryKey, String... altKeys) {
        String[] out = new String[BATTERY_FRAME_COUNT];
        if (obj == null) return out;
        org.json.JSONArray arr = obj.optJSONArray(primaryKey);
        if ((arr == null || arr.length() == 0) && altKeys != null) {
            for (String k : altKeys) {
                arr = obj.optJSONArray(k);
                if (arr != null && arr.length() > 0) break;
            }
        }
        if (arr != null && arr.length() > 0) {
            int n = Math.min(arr.length(), BATTERY_FRAME_COUNT);
            for (int i = 0; i < n; i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out[i] = s;
            }
            return out;
        }
        JSONObject map = obj.optJSONObject(primaryKey);
        if (map == null && altKeys != null) {
            for (String k : altKeys) {
                map = obj.optJSONObject(k);
                if (map != null) break;
            }
        }
        if (map != null) {
            for (int i = 0; i < BATTERY_FRAME_COUNT; i++) {
                String s = map.optString(String.valueOf(i), "").trim();
                if (s.isEmpty()) s = map.optString(String.valueOf(i + 1), "").trim();
                if (!s.isEmpty()) out[i] = s;
            }
        }
        return out;
    }

    private static boolean batteryFramesEmpty(String[] frames) {
        if (frames == null) return true;
        for (String f : frames) {
            if (f != null && !f.isEmpty()) return false;
        }
        return true;
    }

    private static String pickBatteryFrame(String[] frames, int quarterIndex) {
        if (frames == null || quarterIndex < 0) return null;
        if (quarterIndex >= BATTERY_FRAME_COUNT) quarterIndex = BATTERY_FRAME_COUNT - 1;
        for (int i = quarterIndex; i >= 0; i--) {
            if (frames[i] != null && !frames[i].isEmpty()) return frames[i];
        }
        for (int i = quarterIndex + 1; i < BATTERY_FRAME_COUNT; i++) {
            if (frames[i] != null && !frames[i].isEmpty()) return frames[i];
        }
        return null;
    }

    private static String[] readBatteryFrames(JSONObject root, boolean charging) {
        String key = charging ? "batteryCharging" : "battery";
        String[] alt = charging ? new String[] {"batteryCharge", "battery_charging"} : new String[0];
        JSONObject solar = root.optJSONObject("solarConfig");
        if (solar != null) {
            String[] solarFrames = parseBatteryFrameArray(solar, key, alt);
            if (!batteryFramesEmpty(solarFrames)) return solarFrames;
        }
        JSONObject status = root.optJSONObject("statusConfig");
        return parseBatteryFrameArray(status, key, alt);
    }

    public static Bitmap getScaledThemeCover(ThemeEntry entry, int heightPx) {
        Bitmap cover = getThemeCover(entry);
        if (cover == null || heightPx <= 0) return null;
        int w = (int) (cover.getWidth() * (heightPx / (float) cover.getHeight()));
        if (w < 1) w = 1;
        return Bitmap.createScaledBitmap(cover, w, heightPx, true);
    }

    public static int getItemTextColorSelected() {
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item != null) {
            int c = parseColorOpt(item, "itemSelectedTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return 0xFFFFFFFF;
    }

    public static int getItemTextColorNormal() {
        JSONObject item = getCurrentTheme().root.optJSONObject("itemConfig");
        if (item != null) {
            int c = parseColorOpt(item, "itemTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        return getTextColorPrimary();
    }

    public static Bitmap getScaledItemRightArrow(int maxHeightPx) {
        Bitmap arrow = getItemRightArrow();
        if (arrow == null || maxHeightPx <= 0) return null;
        int maxW = Math.max(1, maxHeightPx / 3);
        float scale = Math.min(maxHeightPx / (float) arrow.getHeight(), maxW / (float) arrow.getWidth());
        int w = Math.max(1, (int) (arrow.getWidth() * scale));
        int h = Math.max(1, (int) (arrow.getHeight() * scale));
        return Bitmap.createScaledBitmap(arrow, w, h, true);
    }

    public static Bitmap getStatusIcon(String key) {
        JSONObject status = getCurrentTheme().root.optJSONObject("statusConfig");
        if (status == null) return null;
        return getThemeBitmap(status.optString(key, ""));
    }

    /** Y1 playback mode glyphs (shuffle/repeat) — statusConfig first, settingConfig fallback. */
    public static Bitmap getPlaybackModeIcon(String key) {
        Bitmap bmp = getStatusIcon(key);
        if (bmp != null) return bmp;
        return getSettingIcon(key);
    }

    /** ponytail: themes mix battery.001 / battery_001 naming; never rewrite file extensions */
    private static Bitmap resolveThemeBitmapLoose(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        String norm = ThemeDownloader.normalizeAssetPath(relativePath);
        if (norm != null) {
            Bitmap bmp = getThemeBitmap(norm);
            if (bmp != null) return bmp;
        }
        Bitmap bmp = getThemeBitmap(relativePath.trim());
        if (bmp != null) return bmp;
        String name = new File(relativePath.trim()).getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".png";
        if (stem.indexOf('_') >= 0) {
            bmp = getThemeBitmap(stem.replace('_', '.') + ext);
            if (bmp != null) return bmp;
        }
        if (stem.indexOf('.') >= 0) {
            bmp = getThemeBitmap(stem.replace('.', '_') + ext);
            if (bmp != null) return bmp;
        }
        File f = resolveThemeAssetFile(getCurrentTheme().folderPath, relativePath.trim());
        if (f == null && norm != null) {
            f = resolveThemeAssetFile(getCurrentTheme().folderPath, norm);
        }
        if (f == null) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap getBatteryIcon(int levelIndex, boolean charging) {
        JSONObject root = getCurrentTheme().root;
        String[] frames = readBatteryFrames(root, charging);
        if (batteryFramesEmpty(frames) && charging) {
            frames = readBatteryFrames(root, false);
        }
        if (batteryFramesEmpty(frames)) return null;
        String path = pickBatteryFrame(frames, levelIndex);
        if (path == null || path.isEmpty()) return null;
        return resolveThemeBitmapLoose(path);
    }

    private static String[] readWifiFrames(JSONObject root) {
        JSONObject solar = root.optJSONObject("solarConfig");
        if (solar != null) {
            String[] solarFrames = parseWifiFrameArray(solar);
            if (!batteryFramesEmpty(solarFrames)) return solarFrames;
        }
        JSONObject status = root.optJSONObject("statusConfig");
        return parseWifiFrameArray(status);
    }

    /** ponytail: AOSP-style RSSI → 0..2 bar index for status bar Wi-Fi glyph */
    public static int wifiSignalIndex(int rssi) {
        if (rssi <= WIFI_RSSI_MIN) return 0;
        if (rssi >= WIFI_RSSI_MAX) return WIFI_FRAME_COUNT - 1;
        float inputRange = (WIFI_RSSI_MAX - WIFI_RSSI_MIN);
        float outputRange = (WIFI_FRAME_COUNT - 1);
        return (int) ((rssi - WIFI_RSSI_MIN) * outputRange / inputRange);
    }

    public static Bitmap getWifiIcon(int signalIndex) {
        String[] frames = readWifiFrames(getCurrentTheme().root);
        if (batteryFramesEmpty(frames)) return null;
        String path = pickBatteryFrame(frames, signalIndex);
        if (path == null || path.isEmpty()) return null;
        return resolveThemeBitmapLoose(path);
    }

    public static void selfCheck() {
        boolean savedMatch = statusBarMatchItemText;
        try {
            setStatusBarMatchItemText(false);
            JSONObject y1 = new JSONObject();
            y1.put("homePageConfig", new JSONObject());
            if (!hasY1Blocks(y1)) throw new AssertionError("y1 blocks");
            JSONObject root = new JSONObject();
            root.put("solarConfig", new JSONObject().put("button_radius", 30));
            root.put("menuConfig", new JSONObject()
                    .put("menuItemBackground", "")
                    .put("menuItemTextColor", "#ffffff")
                    .put("menuItemSelectedTextColor", "#ffffff"));
            root.put("itemConfig", new JSONObject()
                    .put("itemBackground", "x.png")
                    .put("itemTextColor", "#e4fc3f")
                    .put("itemSelectedTextColor", "#cc0f9d"));
            root.put("homePageConfig", new JSONObject());
            availableThemes.clear();
            availableThemes.add(new ThemeEntry("/tmp", "t", "t", root));
            setThemeIndex(0);
            if (getButtonRadius() != 30) throw new AssertionError("solarConfig radius");
            if (!y1UnselectedRowTransparent(true)) throw new AssertionError("menu transparent");
            if (y1UnselectedRowTransparent(false)) throw new AssertionError("item not transparent");
            root.put("solarConfig", new JSONObject()
                    .put("nowPlayingTextColour", "#aabbcc")
                    .put("nowPlayingInfoBars", "none"));
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if (getNowPlayingInfoBarMode() != NOW_PLAYING_BARS_NONE) throw new AssertionError("nowPlayingInfoBars");
            if (!"#aabbcc".equals(getCurrentTheme().root.optJSONObject("solarConfig")
                    .optString("nowPlayingTextColour"))) {
                throw new AssertionError("nowPlayingTextColour key");
            }
            root.getJSONObject("menuConfig").put("menuItemTextColor", "#ffffff");
            root.getJSONObject("menuConfig").put("menuItemSelectedTextColor", "#ffffff");
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if ((getMenuItemTextColorNormal() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("menuItemTextColor");
            if ((getMenuItemTextColorSelected() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("menu selected text");
            root.getJSONObject("menuConfig").remove("menuItemTextColor");
            root.getJSONObject("menuConfig").remove("menuItemSelectedTextColor");
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if ((getMenuItemTextColorNormal() & 0xFFFFFF) != 0xE4FC3F) throw new AssertionError("menu falls back to itemTextColor");
            if ((getMenuItemTextColorSelected() & 0xFFFFFF) != 0xCC0F9D) throw new AssertionError("menu falls back to itemSelectedTextColor");
            if ((getSettingMenuTextColorNormal() & 0xFFFFFF) != 0xE4FC3F) throw new AssertionError("settings uses itemTextColor");
            if ((getSettingMenuTextColorSelected() & 0xFFFFFF) != 0xCC0F9D) throw new AssertionError("settings uses itemSelectedTextColor");
            root.getJSONObject("menuConfig").put("menuItemBackground", "menu.png");
            root.getJSONObject("menuConfig").put("menuItemSelectedBackground", "menu_sel.png");
            root.getJSONObject("menuConfig").put("menuItemTextColor", "#ffffff");
            root.getJSONObject("menuConfig").put("menuItemSelectedTextColor", "#ffffff");
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if ((getMenuItemTextColorNormal() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("explicit menuItemTextColor");
            if ((getMenuItemTextColorSelected() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("explicit menu selected text");
            root.getJSONObject("solarConfig").put("homeUnselectedColor", "#e4fc3f");
            root.getJSONObject("solarConfig").put("homeSelectedColor", "#cc0f9d");
            root.getJSONObject("solarConfig").put("statusBarTextColor", "#00ff00");
            root.getJSONObject("solarConfig").put("settingUnselectedColor", "#e4fc3f");
            root.getJSONObject("solarConfig").put("settingSelectedColor", "#cc0f9d");
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if ((getHomeMenuTextColorNormal() & 0xFFFFFF) != 0xE4FC3F) throw new AssertionError("homePage normal");
            if ((getHomeMenuTextColorSelected() & 0xFFFFFF) != 0xCC0F9D) throw new AssertionError("homePage selected");
            if ((getStatusBarTextColor() & 0xFFFFFF) != 0x00FF00) throw new AssertionError("statusBarTextColor");
            if ((getHintTextColor() & 0xFFFFFF) != 0x00FF00) throw new AssertionError("hintTextColor");
            if ((getSectionHeaderTextColor() >>> 24) != 0xBB) throw new AssertionError("sectionHeader alpha");
            if ((getDimmedTextColor(0x55) >>> 24) != 0x55) throw new AssertionError("dimmed alpha");
            root.getJSONObject("solarConfig").remove("statusBarTextColor");
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            setStatusBarMatchItemText(true);
            if ((getHintTextColor() & 0xFFFFFF) != 0xE4FC3F) throw new AssertionError("hint follows item text when match on");
            root.getJSONObject("solarConfig").put("highContrastText", true);
            availableThemes.set(0, new ThemeEntry("/tmp", "t", "t", root));
            if (!isHighContrastTextEnabled()) throw new AssertionError("highContrastText");
            if (!"appPodcasts".equals(solarAppConfigKey("Podcasts"))) throw new AssertionError("appPodcasts key");
            if (!"appSoulseek".equals(solarAppConfigKey("Soulseek"))) throw new AssertionError("appSoulseek key");
            if (!"appPC_Upload".equals(solarAppConfigKey("PC Upload"))) throw new AssertionError("appPC_Upload key");
            JSONObject ipod = new JSONObject();
            ipod.put("menuConfig", new JSONObject()
                    .put("menuBackgroundColor", "#000000")
                    .put("menuItemBackground", "")
                    .put("menuItemTextColor", "#ffffff")
                    .put("menuItemSelectedBackground", "1.png")
                    .put("menuItemSelectedTextColor", "#ffffff"));
            ipod.put("itemConfig", new JSONObject()
                    .put("itemBackground", "1_copy.png")
                    .put("itemTextColor", "#000000")
                    .put("itemSelectedTextColor", "#ffffff")
                    .put("itemSelectedBackground", "1.png"));
            ipod.put("homePageConfig", new JSONObject());
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if ((getHomeMenuTextColorNormal() & 0xFFFFFF) != 0x000000) throw new AssertionError("ipod-ish home text");
            if ((getHomeMenuTextColorSelected() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("ipod-ish home selected");
            JSONObject mc = new JSONObject();
            mc.put("menuConfig", new JSONObject().put("menuBackgroundColor", "#000000"));
            mc.put("itemConfig", new JSONObject().put("itemBackground", ""));
            availableThemes.set(0, new ThemeEntry("/tmp", "mc", "mc", mc));
            if (getMenuPanelBackgroundScaled(null, 200, 300) != null) {
                throw new AssertionError("no row art = transparent panel");
            }
            JSONObject holo = new JSONObject();
            holo.put("itemConfig", new JSONObject()
                    .put("itemBackground", "")
                    .put("itemSelectedBackground", "1.png")
                    .put("itemTextColor", "#ffffff"));
            holo.put("playerConfig", new JSONObject().put("progressColor", "#ff00aa"));
            availableThemes.set(0, new ThemeEntry("/tmp", "holo", "holo", holo));
            if (!y1UnselectedRowTransparent(false)) throw new AssertionError("holo transparent unselected");
            if ((getRowSelectionFillColor() & 0xFFFFFF) != 0xFF00AA) {
                throw new AssertionError("holo progressColor fill");
            }
            JSONObject melody = new JSONObject();
            melody.put("itemConfig", new JSONObject().put("itemBackground", "").put("itemSelectedBackground", ""));
            melody.put("playerConfig", new JSONObject().put("progressColor", "#336699"));
            melody.put("menuConfig", new JSONObject().put("menuItemBackground", ""));
            availableThemes.set(0, new ThemeEntry("/tmp", "melody", "melody", melody));
            if ((getRowSelectionFillColor() & 0xFFFFFF) != 0x336699) throw new AssertionError("melody progressColor");
            if (getMenuPanelBackgroundScaled(null, 200, 300) != null) throw new AssertionError("melody panel transparent");
            JSONObject ac = new JSONObject();
            ac.put("itemConfig", new JSONObject()
                    .put("itemBackground", "")
                    .put("itemSelectedBackground", "sel.png")
                    .put("itemTextColor", "#2ABAAA")
                    .put("itemSelectedTextColor", "#374063"));
            ac.put("menuConfig", new JSONObject().put("menuBackgroundColor", "#3F4142").put("menuItemBackground", ""));
            availableThemes.set(0, new ThemeEntry("/tmp", "ac", "ac", ac));
            if (getListButtonNormalBg() != 0) throw new AssertionError("acmp3 unselected not menuBackgroundColor");
            if (!y1UnselectedRowTransparent(false)) throw new AssertionError("acmp3 home transparent");
            ThemeManager.setStatusBarMatchItemText(true);
            if ((getStatusBarTextColor() & 0xFFFFFF) != 0x2ABAAA) throw new AssertionError("statusBarTextColor match item");
            ThemeManager.setStatusBarMatchItemText(false);
            if ((getStatusBarTextColor() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("statusBarTextColor y1 default");
            ThemeManager.setStatusBarMatchItemText(true);
            if (batteryLevelIndex(0) != 0 || batteryLevelIndex(24) != 0) throw new AssertionError("battery q0");
            if (batteryLevelIndex(25) != 1 || batteryLevelIndex(49) != 1) throw new AssertionError("battery q1");
            if (batteryLevelIndex(50) != 2 || batteryLevelIndex(74) != 2) throw new AssertionError("battery q2");
            if (batteryLevelIndex(75) != 3 || batteryLevelIndex(100) != 3) throw new AssertionError("battery q3");
            JSONObject acBat = new JSONObject();
            acBat.put("statusConfig", new JSONObject()
                    .put("battery", new org.json.JSONArray()
                            .put("bt1.png").put("bt2.png").put("bt3.png").put("bt4.png"))
                    .put("batteryCharging", new org.json.JSONArray()
                            .put("btc1.png").put("btc2.png").put("btc3.png").put("btc4.png")));
            String[] bat = parseBatteryFrameArray(acBat.getJSONObject("statusConfig"), "battery");
            String[] chg = parseBatteryFrameArray(acBat.getJSONObject("statusConfig"), "batteryCharging");
            if (!"bt1.png".equals(bat[0]) || !"bt4.png".equals(bat[3])) throw new AssertionError("battery array order");
            if (!"btc2.png".equals(chg[1])) throw new AssertionError("batteryCharging array order");
            JSONObject solarWifi = new JSONObject().put("wifi", new org.json.JSONArray()
                    .put("wifi0.png").put("wifi1.png").put("wifi2.png"));
            String[] wf = parseWifiFrameArray(solarWifi);
            if (!"wifi0.png".equals(wf[0]) || !"wifi2.png".equals(wf[2])) throw new AssertionError("wifi array order");
            if (wifiSignalIndex(-100) != 0 || wifiSignalIndex(-55) != 2) throw new AssertionError("wifi rssi");
            if (wifiSignalIndex(-77) != 1) throw new AssertionError("wifi rssi mid");
            JSONObject elsieStatus = new JSONObject().put("statusBarColor", "#747a60").put("statusTextColor", "#ffffff");
            ac.put("statusConfig", elsieStatus);
            availableThemes.set(0, new ThemeEntry("/tmp", "elsie", "elsie", ac));
            if (getStatusBarBackgroundColor() != 0xFF747A60 && (getStatusBarBackgroundColor() & 0xFFFFFF) != 0x747A60) {
                throw new AssertionError("elsie statusBarColor");
            }
            if ((getStatusBarTextColor() & 0xFFFFFF) != 0x2ABAAA) throw new AssertionError("elsie statusTextColor match item");
            ThemeManager.setStatusBarMatchItemText(false);
            if ((getStatusBarTextColor() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("elsie statusTextColor");
            ThemeManager.setStatusBarMatchItemText(true);
            JSONObject settingSel = new JSONObject();
            settingSel.put("settingConfig", new JSONObject()
                    .put("selectedColor", "#ff00aa")
                    .put("unselectedColor", "#00aaff"));
            settingSel.put("menuConfig", new JSONObject()
                    .put("menuItemBackground", "m.png")
                    .put("menuItemSelectedTextColor", "#ffffff")
                    .put("menuItemTextColor", "#eeeeee"));
            availableThemes.set(0, new ThemeEntry("/tmp", "set", "set", settingSel));
            if ((getMenuItemTextColorSelected() & 0xFFFFFF) != 0xFFFFFF) throw new AssertionError("settingConfig should not affect menuItem");
            if ((getMenuItemTextColorNormal() & 0xFFFFFF) != 0xEEEEEE) throw new AssertionError("menuItemTextColor explicit");
            if ((getSettingMenuTextColorSelected() & 0xFFFFFF) != 0xFF00AA) throw new AssertionError("settingConfig selectedColor");
            if ((getSettingMenuTextColorNormal() & 0xFFFFFF) != 0x00AAFF) throw new AssertionError("settingConfig unselectedColor");
            JSONObject npPlayer = new JSONObject();
            npPlayer.put("playerConfig", new JSONObject()
                    .put("nowPlayingTextColor", "#aabbcc")
                    .put("nowPlayingInfoBars", "none"));
            availableThemes.set(0, new ThemeEntry("/tmp", "np", "np", npPlayer));
            if (getNowPlayingInfoBarMode() != NOW_PLAYING_BARS_NONE) throw new AssertionError("playerConfig nowPlayingInfoBars");
            Integer npCol = getNowPlayingTextColorOpt();
            if (npCol == null || (npCol & 0xFFFFFF) != 0xAABBCC) throw new AssertionError("playerConfig nowPlayingTextColor");
            JSONObject noBar = new JSONObject();
            noBar.put("itemConfig", new JSONObject().put("itemBackground", ""));
            noBar.put("menuConfig", new JSONObject());
            availableThemes.set(0, new ThemeEntry("/tmp", "nob", "nob", noBar));
            if (getStatusBarBackgroundColor() != 0) throw new AssertionError("unset statusBar transparent");
            JSONObject alphaBar = new JSONObject();
            alphaBar.put("statusConfig", new JSONObject().put("statusBarColor", "#80747a60"));
            availableThemes.set(0, new ThemeEntry("/tmp", "alpha", "alpha", alphaBar));
            if ((getStatusBarBackgroundColor() >>> 24) != 0x80) throw new AssertionError("alpha statusBar");
            JSONObject alphaText = new JSONObject();
            alphaText.put("statusConfig", new JSONObject().put("statusTextColor", "#80ffffff"));
            availableThemes.set(0, new ThemeEntry("/tmp", "alphat", "alphat", alphaText));
            setStatusBarMatchItemText(false);
            if ((getStatusBarTextColor() >>> 24) != 0x80) throw new AssertionError("alpha statusTextColor");
            JSONObject alphaProgress = new JSONObject();
            alphaProgress.put("playerConfig", new JSONObject().put("progressColor", "#8044ff00"));
            availableThemes.set(0, new ThemeEntry("/tmp", "alphap", "alphap", alphaProgress));
            if ((getProgressColor() >>> 24) != 0x80) throw new AssertionError("alpha progressColor");
            int panel = getContextMenuPanelColor();
            if (ensureReadableOnBackground(0xFFE8E8E8, panel) != 0xFFE8E8E8) {
                throw new AssertionError("context menu keep readable text");
            }
            int fixed = ensureReadableOnBackground(0xFF555555, panel);
            if (contrastRatio(fixed, panel) < 3.0) throw new AssertionError("context menu fix contrast");
            if ((fixed & 0xFFFFFF) == 0x555555) throw new AssertionError("context menu fix applied");
            if (!isAchromaticColor(0xFFFFFFFF) || !isAchromaticColor(0xFF888888)) {
                throw new AssertionError("achromatic detect");
            }
            if (isAchromaticColor(0xFFFF0000)) throw new AssertionError("achromatic red");
            if (!needsContextMenuUnselectedDimming(0xFFFFFFFF, 0xFFFEFEFE, false)) {
                throw new AssertionError("near-white needs dimming");
            }
            if (needsContextMenuUnselectedDimming(0xFFFF0000, 0xFF0000FF, false)) {
                throw new AssertionError("colour theme skip dimming");
            }
            int dim = contextMenuTextNormal(0xFFFFFFFF, 0xFFFFFFFF, panel, false);
            int selOnPanel = ensureReadableOnBackground(0xFFFFFFFF, panel);
            if (contrastRatio(dim, selOnPanel) < 1.5) {
                throw new AssertionError("context menu dim contrast");
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            availableThemes.clear();
            currentThemeIndex = 0;
            setStatusBarMatchItemText(savedMatch);
        }
    }

    private static int parseColor(JSONObject j, String key, int fallback) {
        try {
            if (!j.has(key)) return fallback;
            String s = j.getString(key);
            if (s.isEmpty()) return fallback;
            return parseColorString(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseColorOpt(JSONObject j, String key) {
        try {
            if (!j.has(key)) return Integer.MIN_VALUE;
            String s = j.getString(key);
            if (s.isEmpty()) return Integer.MIN_VALUE;
            return parseColorString(s);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** ponytail: #RRGGBB and #AARRGGBB; JVM fallback when Color.parseColor stubs fail */
    private static int parseColorString(String s) {
        try {
            return Color.parseColor(s);
        } catch (Throwable t) {
            if (s.length() == 7 && s.charAt(0) == '#') {
                return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
            }
            if (s.length() == 9 && s.charAt(0) == '#') {
                return (int) Long.parseLong(s.substring(1), 16);
            }
            throw t;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        fis.read(data);
        fis.close();
        return data;
    }
}
