package com.solar.launcher.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * ponytail: one file — fetch themes.json, walk config.json for asset paths, download to PATH_THEMES.
 */
public class ThemeDownloader {

    private static final String TAG = "ThemeDownloader";
    /** adb pull Themes/.cache/get_themes.log (path follows {@link ThemeManager#themesRoot()}). */
    public static String downloadLogPath() {
        return new File(ThemeManager.themesRoot(), ".cache/get_themes.log").getAbsolutePath();
    }
    private static final int LOG_MAX_BYTES = 256 * 1024;
    private static final Object LOG_LOCK = new Object();
    private static String activeSession = "";

    public static final String CATALOG_URL = "http://themes.innioasis.app/themes.json";
    public static final String OPT_OUT_URL = "http://themes.innioasis.app/opt_out.json";
    public static final String BASE_URL = "http://themes.innioasis.app/";

    private static final Pattern ASSET_EXT = Pattern.compile(
            ".+\\.(png|jpg|jpeg|gif|webp|ttf|otf|woff)$", Pattern.CASE_INSENSITIVE);
    private static final long DOWNLOAD_PACE_MS = 120L;
    private static final java.util.concurrent.atomic.AtomicBoolean downloadCancel =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void requestCancel() {
        downloadCancel.set(true);
    }

    public static void clearCancel() {
        downloadCancel.set(false);
    }

    static void checkDownloadCancel() throws InterruptedException {
        if (downloadCancel.get()) throw new InterruptedException("Download cancelled");
    }

    public static class ThemeVariant {
        /** User-facing variant label (e.g. Robin, Dark, Will). */
        public final String label;
        /** Gallery path under catalog folder; empty = base theme config. */
        public final String gallerySubpath;
        /** Folder slug for install path; may differ from label when enriched from config. */
        public final String folderSlug;

        public ThemeVariant(String label, String gallerySubpath) {
            this(label, gallerySubpath, label);
        }

        public ThemeVariant(String label, String gallerySubpath, String folderSlug) {
            this.label = label != null ? label : "";
            this.gallerySubpath = gallerySubpath != null ? gallerySubpath : "";
            this.folderSlug = (folderSlug != null && !folderSlug.isEmpty()) ? folderSlug : this.label;
        }

        public String installFolder(String themeName) {
            return installFolderName(themeName, folderSlug);
        }

        public String displayName(String themeName) {
            return variantDisplayName(themeName, label);
        }

        public String galleryCatalogFolder(String parentFolder) {
            if (gallerySubpath.isEmpty()) return parentFolder;
            return parentFolder + "/" + gallerySubpath;
        }

        public File themeDir(String themeName) {
            return new File(ThemeManager.themesRoot(), installFolder(themeName));
        }

        public boolean isInstalled(String themeName) {
            return new File(themeDir(themeName), "config.json").isFile();
        }

        public boolean isComplete(String themeName) {
            if (!isInstalled(themeName)) return false;
            try {
                return missingAssets(themeDir(themeName)).isEmpty();
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static class CatalogEntry {
        public final String name;
        public final String folder;
        public final String screenshot;
        public final String author;
        public final List<ThemeVariant> variants;

        CatalogEntry(String name, String folder, String screenshot, String author, List<ThemeVariant> variants) {
            this.name = name;
            this.folder = folder;
            this.screenshot = screenshot;
            this.author = author != null ? author : "";
            this.variants = variants != null ? variants : new ArrayList<ThemeVariant>();
        }

        public boolean hasVariants() {
            return !variants.isEmpty();
        }

        public boolean isInstalled() {
            if (hasVariants()) {
                for (ThemeVariant v : variants) {
                    if (v.isInstalled(name)) return true;
                }
                return false;
            }
            if (folder.contains("/Variants/")) {
                ThemeVariant orphan = orphanVariantFromGalleryFolder(folder, name);
                String parentName = parentThemeNameFromGalleryFolder(folder);
                return orphan.isInstalled(parentName);
            }
            return new File(ThemeManager.themesRoot(), folder + "/config.json").isFile();
        }

        public boolean isComplete() {
            if (hasVariants()) {
                for (ThemeVariant v : variants) {
                    if (v.isComplete(name)) return true;
                }
                return false;
            }
            if (folder.contains("/Variants/")) {
                ThemeVariant orphan = orphanVariantFromGalleryFolder(folder, name);
                String parentName = parentThemeNameFromGalleryFolder(folder);
                return orphan.isComplete(parentName);
            }
            if (!isInstalled()) return false;
            try {
                return missingAssets(themeDir()).isEmpty();
            } catch (Exception e) {
                return false;
            }
        }

        public File themeDir() {
            return new File(ThemeManager.themesRoot(), folder);
        }

        public String localFolderPath() {
            return themeDir().getAbsolutePath();
        }

        public String coverUrl() {
            if (screenshot == null || screenshot.isEmpty()) {
                return BASE_URL + folder + "/cover.png";
            }
            String path = screenshot.trim();
            if (path.startsWith("https://")) path = "http://" + path.substring(8);
            if (path.startsWith("http://")) return path;
            while (path.startsWith("./")) path = path.substring(2);
            if (path.startsWith("/")) path = path.substring(1);
            return BASE_URL + path;
        }

        public String coverUrlForVariant(ThemeVariant variant) {
            if (variant == null || variant.gallerySubpath.isEmpty()) return coverUrl();
            String sub = folder + "/" + variant.gallerySubpath + "/cover.png";
            return themeFileUrlForGallery(sub);
        }
    }

    public static List<CatalogEntry> fetchCatalog() throws Exception {
        beginSession("fetchCatalog", "");
        try {
            List<CatalogEntry> out = loadCatalogFromNetwork();
            saveCatalogCache(out);
            dlLog("catalog ok themes=" + out.size());
            endSession(true, out.size() + " themes");
            return out;
        } catch (Exception e) {
            dlLogError("fetchCatalog", e);
            endSession(false, e.getMessage());
            throw e;
        }
    }

    /** Last successful themes.json — instant online section on reopen. */
    public static List<CatalogEntry> loadCachedCatalog() {
        try {
            File cache = catalogCacheFile();
            if (!cache.isFile() || cache.length() == 0) return null;
            return parseCatalog(readUtf8(cache));
        } catch (Exception e) {
            return null;
        }
    }

    private static File catalogCacheFile() {
        return new File(ThemeManager.themesRoot(), ".cache/themes.json");
    }

    private static void saveCatalogCache(List<CatalogEntry> entries) {
        if (entries == null) return;
        try {
            File cache = catalogCacheFile();
            File parent = cache.getParentFile();
            if (parent != null) parent.mkdirs();
            JSONArray arr = new JSONArray();
            for (CatalogEntry e : entries) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("folder", e.folder);
                o.put("author", e.author);
                if (e.screenshot != null) o.put("screenshot", e.screenshot);
                if (e.hasVariants()) {
                    JSONArray variantFolders = new JSONArray();
                    for (ThemeVariant v : e.variants) {
                        if (v.gallerySubpath.isEmpty()) {
                            if (!v.label.isEmpty()) o.put("defaultVariantLabel", v.label);
                        } else {
                            String sub = v.gallerySubpath;
                            if (sub.startsWith("Variants/")) sub = sub.substring("Variants/".length());
                            if (sub.contains("/")) sub = sub.substring(0, sub.indexOf('/'));
                            variantFolders.put(sub);
                        }
                    }
                    if (variantFolders.length() > 0) o.put("variantFolders", variantFolders);
                }
                arr.put(o);
            }
            JSONObject root = new JSONObject().put("themes", arr);
            writeUtf8(cache, root.toString());
        } catch (Exception ignored) {}
    }

    private static String readUtf8(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return new String(n > 0 ? java.util.Arrays.copyOf(buf, n) : buf, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeUtf8(File f, String text) throws java.io.IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(text.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    /** Remove installed theme folder (not Default). */
    public static boolean deleteInstalledTheme(String folderName) {
        if (folderName == null || folderName.isEmpty()) return false;
        if (ThemeManager.BUILTIN_DEFAULT_FOLDER.equalsIgnoreCase(folderName)) return false;
        File dir = new File(ThemeManager.themesRoot(), folderName);
        if (!dir.isDirectory()) return false;
        deleteRecursive(dir);
        return true;
    }

    private static List<CatalogEntry> loadCatalogFromNetwork() throws Exception {
        byte[] raw = httpGet(CATALOG_URL);
        List<CatalogEntry> all = parseCatalog(new String(raw, "UTF-8"));
        OptOutFilter optOut = fetchOptOutFilter();
        return filterCatalog(all, optOut);
    }

    public static List<CatalogEntry> parseCatalog(String json) throws Exception {
        List<CatalogEntry> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray themes = root.getJSONArray("themes");
        for (int i = 0; i < themes.length(); i++) {
            JSONObject t = themes.getJSONObject(i);
            String folder = t.optString("folder", "").trim();
            if (folder.isEmpty()) continue;
            String name = t.optString("name", folder);
            String shot = t.optString("screenshot", "");
            String author = t.optString("author", "");
            List<ThemeVariant> variants = parseVariantList(t, name);
            if (folder.contains("/Variants/")) {
                out.add(new CatalogEntry(name, folder, shot, author, new ArrayList<ThemeVariant>()));
            } else {
                out.add(new CatalogEntry(name, folder, shot, author, variants));
            }
        }
        return out;
    }

    /** Y1 gallery: variantFolders + optional defaultVariantLabel on base config. */
    public static List<ThemeVariant> parseVariantList(JSONObject catalogTheme, String themeName) {
        List<ThemeVariant> out = new ArrayList<ThemeVariant>();
        JSONArray variantFolders = catalogTheme.optJSONArray("variantFolders");
        if (variantFolders == null) variantFolders = catalogTheme.optJSONArray("variants");
        String defaultLabel = catalogTheme.optString("defaultVariantLabel", "").trim();
        boolean hasSubfolders = variantFolders != null && variantFolders.length() > 0;
        if (!hasSubfolders && defaultLabel.isEmpty()) return out;

        String baseLabel = !defaultLabel.isEmpty() ? defaultLabel : themeName;
        out.add(new ThemeVariant(baseLabel, ""));
        if (variantFolders != null) {
            for (int i = 0; i < variantFolders.length(); i++) {
                String sub = variantFolders.optString(i, "").trim();
                if (sub.isEmpty()) continue;
                out.add(new ThemeVariant(sub, "Variants/" + sub));
            }
        }
        return out;
    }

    public static String installedFolderForEntry(CatalogEntry entry) {
        if (entry.folder.contains("/Variants/") && !entry.hasVariants()) {
            ThemeVariant orphan = orphanVariantFromGalleryFolder(entry.folder, entry.name);
            return orphan.installFolder(parentThemeNameFromGalleryFolder(entry.folder));
        }
        return entry.folder;
    }

    static ThemeVariant orphanVariantFromGalleryFolder(String galleryFolder, String fallbackLabel) {
        int idx = galleryFolder.indexOf("/Variants/");
        String variantLabel = fallbackLabel;
        if (idx >= 0) {
            variantLabel = galleryFolder.substring(idx + "/Variants/".length());
            if (variantLabel.contains("/")) variantLabel = variantLabel.substring(0, variantLabel.indexOf('/'));
        }
        return new ThemeVariant(variantLabel, "");
    }

    static String parentThemeNameFromGalleryFolder(String galleryFolder) {
        int idx = galleryFolder.indexOf("/Variants/");
        String parent = idx >= 0 ? galleryFolder.substring(0, idx) : galleryFolder;
        if (parent.contains("/")) parent = parent.substring(parent.lastIndexOf('/') + 1);
        return parent;
    }

    public static String installFolderName(String themeName, String variantLabel) {
        return sanitizeThemeFolder(themeName + " - " + variantLabel);
    }

    /** Install folder name for gallery entry + variant (orphan rows use parent theme name). */
    public static String resolvedInstallFolder(CatalogEntry entry, ThemeVariant variant) {
        if (entry == null) return "";
        if (variant == null) {
            if (entry.folder.contains("/Variants/") && !entry.hasVariants()) {
                ThemeVariant orphan = orphanVariantFromGalleryFolder(entry.folder, entry.name);
                return orphan.installFolder(parentThemeNameFromGalleryFolder(entry.folder));
            }
            return entry.folder;
        }
        if (entry.folder.contains("/Variants/") && variant.gallerySubpath.isEmpty()) {
            return installFolderName(parentThemeNameFromGalleryFolder(entry.folder), variant.folderSlug);
        }
        return variant.installFolder(entry.name);
    }

    public static String variantDisplayName(String themeName, String variantLabel) {
        if (variantLabel.equals(themeName)) return themeName;
        return themeName + " (" + variantLabel + ")";
    }

    /** theme_info.title → root name → fallback */
    public static String readThemeTitleFromConfig(JSONObject config, String fallback) {
        if (config == null) return fallback != null ? fallback : "";
        JSONObject info = config.optJSONObject("theme_info");
        if (info != null) {
            String t = info.optString("title", "").trim();
            if (!t.isEmpty()) return t;
        }
        String n = config.optString("name", "").trim();
        if (!n.isEmpty()) return n;
        return fallback != null ? fallback : "";
    }

    static boolean shouldEnrichVariantLabel(String label, String themeName) {
        if (label == null || label.isEmpty()) return true;
        return label.equals(themeName);
    }

    static ThemeVariant enrichVariantLabel(ThemeVariant v, String themeName, JSONObject config) {
        if (config == null || v == null) return v;
        if (shouldEnrichVariantLabel(v.label, themeName)) {
            String enriched = readThemeTitleFromConfig(config, v.label);
            if (!enriched.isEmpty() && !enriched.equals(v.label)) {
                return new ThemeVariant(enriched, v.gallerySubpath, v.folderSlug);
            }
        }
        return v;
    }

    public static boolean hasVariantSubfolders(CatalogEntry entry) {
        if (entry == null) return false;
        for (ThemeVariant v : entry.variants) {
            if (!v.gallerySubpath.isEmpty()) return true;
        }
        return false;
    }

    /** Orphan catalog row (folder is …/Variants/X): label from remote config.json when possible. */
    public static String fetchOrphanGalleryLabel(CatalogEntry entry) {
        if (entry == null || !entry.folder.contains("/Variants/")) return entry != null ? entry.name : "";
        try {
            byte[] bytes = httpGet(themeFileUrlForGallery(entry.folder + "/config.json"));
            JSONObject config = new JSONObject(new String(bytes, "UTF-8"));
            return readThemeTitleFromConfig(config, entry.name);
        } catch (Exception e) {
            dlLog("orphan label fetch " + entry.folder + ": " + e.getMessage());
            return entry.name;
        }
    }

    static String sanitizeThemeFolder(String raw) {
        if (raw == null) return "Theme";
        String s = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "Theme" : s;
    }

    public static ThemeVariant findVariantForInstalledFolder(CatalogEntry entry, String installedFolder) {
        if (entry == null || installedFolder == null) return null;
        for (ThemeVariant v : entry.variants) {
            if (v.installFolder(entry.name).equalsIgnoreCase(installedFolder)) return v;
        }
        return null;
    }

    public static CatalogEntry findCatalogEntryForInstalledFolder(String installedFolder, List<CatalogEntry> catalog) {
        if (installedFolder == null) return null;
        for (CatalogEntry e : catalog) {
            if (e.folder.equalsIgnoreCase(installedFolder)) return e;
            if (e.hasVariants()) {
                for (ThemeVariant v : e.variants) {
                    if (v.installFolder(e.name).equalsIgnoreCase(installedFolder)) return e;
                }
            }
        }
        return null;
    }

    static class OptOutFilter {
        final Set<String> authors = new HashSet<>();
        final Set<String> folders = new HashSet<>();
    }

    static OptOutFilter parseOptOut(String json) throws Exception {
        OptOutFilter out = new OptOutFilter();
        JSONObject root = new JSONObject(json);
        JSONObject authors = root.optJSONObject("authors");
        if (authors != null) {
            JSONArray names = authors.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String a = names.optString(i, "").trim();
                    if (!a.isEmpty()) out.authors.add(a.toLowerCase(Locale.US));
                }
            }
        }
        addStringArray(out.folders, root.optJSONArray("folders"));
        addStringArray(out.folders, root.optJSONArray("themes"));
        addStringArray(out.folders, root.optJSONArray("blocked"));
        return out;
    }

    private static void addStringArray(Set<String> dest, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) dest.add(s.toLowerCase(Locale.US));
        }
    }

    static OptOutFilter fetchOptOutFilter() {
        try {
            byte[] raw = httpGet(OPT_OUT_URL);
            return parseOptOut(new String(raw, "UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "opt_out.json unavailable: " + e.getMessage());
            return new OptOutFilter();
        }
    }

    static List<CatalogEntry> filterCatalog(List<CatalogEntry> in, OptOutFilter optOut) {
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry e : in) {
            if (optOut.folders.contains(e.folder.toLowerCase(Locale.US))) continue;
            out.add(e);
        }
        return out;
    }

    public static CatalogEntry findCatalogEntry(String folder, List<CatalogEntry> catalog) {
        if (folder == null) return null;
        String key = folder.toLowerCase(Locale.US);
        for (CatalogEntry e : catalog) {
            if (e.folder.equalsIgnoreCase(key)) return e;
        }
        return null;
    }

    /** ponytail: compare config.json asset refs to files on disk */
    public static Set<String> missingAssets(File themeDir) throws Exception {
        File configFile = new File(themeDir, "config.json");
        if (!configFile.isFile()) throw new Exception("No config.json in " + themeDir);
        JSONObject config = readConfig(configFile);
        JSONObject solar = config.optJSONObject("solarConfig");
        if (solar != null && solar.has("installedAssetSources")) {
            return missingFlatInstalledAssets(themeDir, config);
        }
        String catalogFolder = catalogFolderFor(themeDir);
        Set<String> expected = collectAssetPaths(config, catalogFolder);
        Set<String> missing = new HashSet<String>();
        File themesRoot = new File(ThemeManager.themesRoot());
        for (String rel : expected) {
            File f = resolveAssetFile(themesRoot, themeDir, catalogFolder, rel);
            if (!f.isFile() || f.length() == 0) missing.add(rel);
        }
        return missing;
    }

    static Set<String> missingFlatInstalledAssets(File themeDir, JSONObject config) throws Exception {
        String catalogFolder = themeDir.getName();
        List<String> assetDirs = readAssetDirs(config);
        Set<String> missing = new HashSet<String>();
        Set<String> locals = new HashSet<String>();
        collectLocalAssetFilenames(config, catalogFolder, assetDirs, locals);
        for (String local : locals) {
            File f = new File(themeDir, local);
            if (!f.isFile() || f.length() == 0) missing.add(local);
        }
        return missing;
    }

    static void collectLocalAssetFilenames(Object node, String catalogFolder, List<String> assetDirs,
                                           Set<String> locals) {
        if (node instanceof String) {
            String s = normalizeAssetPath((String) node, catalogFolder, assetDirs);
            if (s == null) return;
            String local = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
            locals.add(local);
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (key.startsWith("COMMENT_") || key.startsWith("_comment")) continue;
                if ("assetDirs".equals(key) || "installedAssetSources".equals(key)
                        || "installedFromGallery".equals(key)) continue;
                collectLocalAssetFilenames(obj.opt(key), catalogFolder, assetDirs, locals);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectLocalAssetFilenames(arr.opt(i), catalogFolder, assetDirs, locals);
            }
        }
    }

    static File resolveAssetFile(File themesRoot, File themeDir, String catalogFolder, String galleryRel) {
        if (catalogFolder != null && !catalogFolder.isEmpty()
                && galleryRel.startsWith(catalogFolder + "/")) {
            return new File(themeDir, galleryRel.substring(catalogFolder.length() + 1));
        }
        if (catalogFolder == null || catalogFolder.isEmpty()) {
            return new File(themeDir, galleryRel);
        }
        return new File(themesRoot, galleryRel);
    }

    public static Set<String> missingAssets(String folderName) throws Exception {
        return missingAssets(new File(ThemeManager.themesRoot(), folderName));
    }

    static JSONObject readConfig(File configFile) throws Exception {
        FileInputStream in = new FileInputStream(configFile);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        in.close();
        return new JSONObject(new String(buf.toByteArray(), "UTF-8"));
    }

    /** Re-download missing assets when theme is still in the public catalog. */
    public static void repairThemeFolderIfInCatalog(String folderName, ProgressListener listener) throws Exception {
        beginSession("repair", folderName);
        File dir = new File(ThemeManager.themesRoot(), folderName);
        if (!new File(dir, "config.json").isFile()) {
            dlLog("repair skip — no config.json");
            endSession(false, "no config");
            return;
        }
        Set<String> missing = missingAssets(dir);
        if (missing.isEmpty()) {
            dlLog("repair skip — complete");
            endSession(true, "already complete");
            return;
        }
        dlLog("repair missing " + missing.size() + ": " + missing);
        try {
            List<CatalogEntry> catalog = loadCatalogFromNetwork();
            CatalogEntry entry = findCatalogEntryForInstalledFolder(folderName, catalog);
            if (entry == null) {
                entry = findCatalogEntry(folderName, catalog);
            }
            if (entry == null) {
                dlLog("repair skip — not in catalog");
                endSession(false, "not in catalog");
                return;
            }
            ThemeVariant variant = findVariantForInstalledFolder(entry, folderName);
            if (variant == null && entry.folder.contains("/Variants/")) {
                variant = orphanVariantFromGalleryFolder(entry.folder, entry.name);
            }
            Set<String> skipped404 = new HashSet<>();
            if (variant != null) {
                downloadMissingVariantAssets(entry, variant, dir, missing, skipped404, listener);
            } else {
                downloadMissingAssets(entry, missing, skipped404, listener);
            }
            Set<String> still = missingAssets(dir);
            still.removeAll(skipped404);
            for (String rel : still) {
                String lower = rel.toLowerCase(Locale.US);
                if (lower.contains("battery") || lower.startsWith("bt") || lower.startsWith("btc")) {
                    dlLog("repair still missing battery asset: " + rel);
                }
            }
            if (!still.isEmpty()) {
                throw new Exception("Incomplete theme after repair: " + folderName + " missing " + still);
            }
            endSession(true, "repaired " + missing.size() + " files");
        } catch (Exception e) {
            dlLogError("repair", e);
            endSession(false, e.getMessage());
            throw e;
        }
    }

    /** Recursively collect gallery-relative asset paths from a theme config.json root. */
    public static Set<String> collectAssetPaths(JSONObject root) {
        return collectAssetPaths(root, "");
    }

    public static Set<String> collectAssetPaths(JSONObject root, String catalogFolder) {
        List<String> assetDirs = readAssetDirs(root);
        Set<String> paths = new HashSet<String>();
        walkJson(root, paths, catalogFolder, assetDirs);
        if (catalogFolder == null || catalogFolder.isEmpty()) {
            paths.add("config.json");
        } else {
            paths.add(catalogFolder + "/config.json");
        }
        return paths;
    }

    static List<String> readAssetDirs(JSONObject root) {
        List<String> dirs = new ArrayList<String>();
        if (root == null) return dirs;
        JSONObject solar = root.optJSONObject("solarConfig");
        if (solar == null) return dirs;
        JSONArray arr = solar.optJSONArray("assetDirs");
        if (arr == null) return dirs;
        for (int i = 0; i < arr.length(); i++) {
            String d = arr.optString(i, "").trim().replace('\\', '/');
            while (d.startsWith("./")) d = d.substring(2);
            if (d.endsWith("/")) d = d.substring(0, d.length() - 1);
            if (!d.isEmpty()) dirs.add(d);
        }
        return dirs;
    }

    static String catalogFolderFor(File themeDir) {
        File root = new File(ThemeManager.themesRoot());
        String abs = themeDir.getAbsolutePath();
        String rootAbs = root.getAbsolutePath();
        if (!abs.startsWith(rootAbs)) return themeDir.getName();
        String rel = abs.substring(rootAbs.length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        return rel;
    }

    /** Gallery-relative path (under PATH_THEMES) for a config asset reference. */
    static String canonicalGalleryPath(String catalogFolder, String ref) {
        if (ref == null) return null;
        String p = ref.trim();
        if (p.isEmpty() || p.contains("://")) return null;
        if (p.startsWith("@")) p = p.substring(1);
        while (p.startsWith("./")) p = p.substring(2);
        p = p.replace('\\', '/');
        if (p.startsWith("/")) p = p.substring(1);

        String[] baseParts = catalogFolder == null || catalogFolder.isEmpty()
                ? new String[0] : catalogFolder.split("/");
        String[] refParts = p.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (String bp : baseParts) {
            if (bp.length() > 0) out.add(bp);
        }
        for (String part : refParts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        if (out.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    static boolean allowedByAssetDirs(String galleryPath, String catalogFolder, List<String> assetDirs) {
        if (assetDirs == null || assetDirs.isEmpty()) return true;
        if (catalogFolder == null || catalogFolder.isEmpty()) {
            if (!galleryPath.contains("/")) return true;
            String first = galleryPath.substring(0, galleryPath.indexOf('/'));
            for (String d : assetDirs) {
                if (galleryPath.startsWith(d + "/") || first.equals(d)) return true;
            }
            return false;
        }
        if (!galleryPath.startsWith(catalogFolder)) return true;
        String rel = galleryPath.substring(catalogFolder.length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || !rel.contains("/")) return true;
        for (String d : assetDirs) {
            if (rel.equals(d) || rel.startsWith(d + "/")) return true;
        }
        return false;
    }

    private static void walkJson(Object node, Set<String> paths, String catalogFolder, List<String> assetDirs) {
        if (node instanceof String) {
            String s = normalizeAssetPath((String) node, catalogFolder, assetDirs);
            if (s != null) paths.add(s);
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (key.startsWith("COMMENT_") || key.startsWith("_comment")) continue;
                if ("assetDirs".equals(key)) continue;
                Object val = obj.opt(key);
                if (val instanceof String) {
                    String s = normalizeAssetPath((String) val, catalogFolder, assetDirs);
                    if (s != null) paths.add(s);
                } else {
                    walkJson(val, paths, catalogFolder, assetDirs);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                walkJson(arr.opt(i), paths, catalogFolder, assetDirs);
            }
        }
    }

    static String normalizeAssetPath(String value) {
        return normalizeAssetPath(value, "", readAssetDirs(null));
    }

    static String normalizeAssetPath(String value, String catalogFolder, List<String> assetDirs) {
        if (value == null) return null;
        String gallery = canonicalGalleryPath(catalogFolder, value);
        if (gallery == null) return null;
        String filePart = gallery.contains("/")
                ? gallery.substring(gallery.lastIndexOf('/') + 1) : gallery;
        if (ASSET_EXT.matcher(filePart).matches()) {
            if (!allowedByAssetDirs(gallery, catalogFolder, assetDirs)) return null;
            return gallery;
        }
        if (filePart.matches("(?i)(battery|batterycharge)([._]\\d{3})+")) {
            String withExt = gallery + ".png";
            if (!allowedByAssetDirs(withExt, catalogFolder, assetDirs)) return null;
            return withExt;
        }
        return null;
    }

    public static File coverCacheFile(String cacheKey) {
        File dir = new File(ThemeManager.themesRoot(), ".cache/covers");
        if (!dir.exists()) dir.mkdirs();
        String safe = cacheKey.replace('/', '_').replace('\\', '_');
        return new File(dir, safe + ".png");
    }

    public static Bitmap loadCoverBitmap(CatalogEntry entry, int maxHeightPx) {
        return loadCoverBitmap(entry, null, maxHeightPx);
    }

    public static Bitmap loadCoverBitmap(CatalogEntry entry, ThemeVariant variant, int maxHeightPx) {
        String cacheKey = entry.folder;
        String url = entry.coverUrl();
        if (variant != null && !variant.gallerySubpath.isEmpty()) {
            cacheKey = entry.folder + "__" + variant.gallerySubpath.replace('/', '_');
            url = entry.coverUrlForVariant(variant);
        }
        File cache = coverCacheFile(cacheKey);
        if (cache.isFile() && cache.length() > 0) {
            try {
                return scaleBitmap(BitmapFactory.decodeFile(cache.getAbsolutePath()), maxHeightPx);
            } catch (Exception ignored) {}
        }
        try {
            byte[] data = httpGet(url);
            if (data == null || data.length == 0) return null;
            try {
                FileOutputStream fos = new FileOutputStream(cache);
                fos.write(data);
                fos.close();
            } catch (Exception ignored) {}
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            return scaleBitmap(bmp, maxHeightPx);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxHeightPx) {
        if (src == null || maxHeightPx <= 0) return src;
        int h = src.getHeight();
        if (h <= maxHeightPx) return src;
        float scale = maxHeightPx / (float) h;
        int w = Math.max(1, (int) (src.getWidth() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, maxHeightPx, true);
        if (scaled != src && !src.isRecycled()) {
            src.recycle();
        }
        return scaled;
    }

    public static void downloadTheme(CatalogEntry entry, ProgressListener listener) throws Exception {
        if (entry.folder.contains("/Variants/") && !entry.hasVariants()) {
            ThemeVariant orphan = orphanVariantFromGalleryFolder(entry.folder, entry.name);
            downloadThemeVariant(entry, orphan, listener);
            return;
        }
        beginSession("download", entry.folder + " (" + entry.name + ")");
        File dest = entry.themeDir();
        try {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new Exception("Cannot create " + dest.getAbsolutePath());
            }

            byte[] configBytes = httpGet(themeFileUrl(entry.folder, "config.json"));
            JSONObject config = new JSONObject(new String(configBytes, "UTF-8"));
            writeFile(new File(dest, "config.json"), configBytes);
            dlLog("config.json ok (" + configBytes.length + " bytes)");

            Set<String> assets = collectAssetPaths(config, entry.folder);
            Set<String> skipped404 = new HashSet<String>();
            int total = assets.size();
            int done = 0;
            int skipped = 0;
            dlLog("asset pass 1/2 count=" + total);
            final File themesRoot = new File(ThemeManager.themesRoot());
            for (String rel : assets) {
                checkDownloadCancel();
                if (rel.endsWith("/config.json") || "config.json".equals(rel)) {
                    done++;
                    if (listener != null) listener.onProgress(done, total, rel);
                    continue;
                }
                File out = resolveAssetFile(themesRoot, dest, entry.folder, rel);
                if (out.isFile() && out.length() > 0) {
                    skipped++;
                    dlLog("skip exists " + rel + " (" + out.length() + "b)");
                    done++;
                    if (listener != null) listener.onProgress(done, total, rel);
                    continue;
                }
                String url = themeFileUrlForGallery(rel);
                dlLog("GET " + rel + " <- " + url);
                try {
                    byte[] fileBytes = httpGet(url);
                    writeFile(out, fileBytes);
                    dlLog("saved " + rel + " (" + fileBytes.length + "b)");
                } catch (HttpStatusException hs) {
                    if (hs.statusCode == 404) {
                        skipped404.add(rel);
                        dlLog("skip 404 " + rel);
                    } else {
                        throw hs;
                    }
                }
                paceDownloads();
                done++;
                if (listener != null) listener.onProgress(done, total, rel);
            }

            Set<String> missing = missingAssets(dest);
            missing.removeAll(skipped404);
            if (!missing.isEmpty()) {
                dlLog("recovery pass missing " + missing.size() + ": " + missing);
                downloadMissingAssets(entry, missing, skipped404, listener);
            }
            Set<String> still = missingAssets(dest);
            still.removeAll(skipped404);
            if (!still.isEmpty()) {
                throw new Exception("Download incomplete: missing " + still);
            }
            endSession(true, total + " assets, " + skipped + " skipped, " + skipped404.size() + " 404-skipped");
        } catch (Exception e) {
            dlLogError("downloadTheme", e);
            endSession(false, e.getMessage());
            throw e;
        }
    }

    public static void downloadThemeVariant(CatalogEntry entry, ThemeVariant variant, ProgressListener listener)
            throws Exception {
        String catalogFolder = variant.galleryCatalogFolder(entry.folder);
        if (entry.folder.contains("/Variants/") && variant.gallerySubpath.isEmpty()) {
            catalogFolder = entry.folder;
        }
        String installFolder = resolvedInstallFolder(entry, variant);
        String displayName = variant.displayName(entry.name);
        if (entry.folder.contains("/Variants/") && variant.gallerySubpath.isEmpty()) {
            displayName = variantDisplayName(parentThemeNameFromGalleryFolder(entry.folder), variant.label);
        }

        beginSession("downloadVariant", installFolder + " (" + displayName + ")");
        File dest = new File(ThemeManager.themesRoot(), installFolder);
        try {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new Exception("Cannot create " + dest.getAbsolutePath());
            }

            byte[] configBytes = httpGet(themeFileUrlForGallery(catalogFolder + "/config.json"));
            JSONObject config = new JSONObject(new String(configBytes, "UTF-8"));
            List<String> assetDirs = readAssetDirs(config);
            Set<String> galleryAssets = collectAssetPaths(config, catalogFolder);
            galleryAssets.remove(catalogFolder + "/config.json");
            galleryAssets.remove("config.json");

            Map<String, String> galleryToLocal;
            try {
                galleryToLocal = planLocalNames(galleryAssets);
            } catch (Throwable t) {
                throw t;
            }
            int total = galleryAssets.size() + 1;
            int done = 0;
            Set<String> skipped404 = new HashSet<String>();
            dlLog("variant asset pass count=" + galleryAssets.size() + " catalog=" + catalogFolder);

            for (String galleryRel : galleryAssets) {
                checkDownloadCancel();
                String localName = galleryToLocal.get(galleryRel);
                if (localName == null) continue;
                File out = new File(dest, localName);
                if (out.isFile() && out.length() > 0) {
                    dlLog("skip exists " + localName);
                    done++;
                    if (listener != null) listener.onProgress(done, total, localName);
                    continue;
                }
                String url = themeFileUrlForGallery(galleryRel);
                dlLog("GET " + galleryRel + " -> " + localName);
                try {
                    writeFile(out, httpGet(url));
                } catch (HttpStatusException hs) {
                    if (hs.statusCode == 404) {
                        skipped404.add(galleryRel);
                        dlLog("skip 404 " + galleryRel);
                    } else {
                        throw hs;
                    }
                }
                paceDownloads();
                done++;
                if (listener != null) listener.onProgress(done, total, localName);
            }

            JSONObject patched = patchVariantConfigForInstall(config, displayName, catalogFolder,
                    assetDirs, galleryToLocal);
            writeFile(new File(dest, "config.json"), patched.toString().getBytes("UTF-8"));
            done++;
            if (listener != null) listener.onProgress(done, total, "config.json");

            Set<String> missing = missingAssets(dest);
            missing.removeAll(skipped404);
            if (!missing.isEmpty()) {
                downloadMissingVariantAssets(entry, variant, dest, missing, skipped404, listener);
            }
            Set<String> still = missingAssets(dest);
            still.removeAll(skipped404);
            if (!still.isEmpty()) {
                throw new Exception("Variant download incomplete: missing " + still);
            }
            endSession(true, installFolder);
        } catch (Exception e) {
            dlLogError("downloadThemeVariant", e);
            endSession(false, e.getMessage());
            throw e;
        }
    }

    static Map<String, String> planLocalNames(Set<String> galleryAssets) {
        Map<String, Integer> basenameCounts = new HashMap<String, Integer>();
        for (String galleryRel : galleryAssets) {
            String base = localBasename(galleryRel);
            String key = base.toLowerCase(Locale.US);
            Integer n = basenameCounts.get(key);
            basenameCounts.put(key, n == null ? 1 : n + 1);
        }
        Map<String, String> galleryToLocal = new LinkedHashMap<String, String>();
        for (String galleryRel : galleryAssets) {
            String base = localBasename(galleryRel);
            String key = base.toLowerCase(Locale.US);
            Integer n = basenameCounts.get(key);
            if (n != null && n > 1) {
                int slash = galleryRel.lastIndexOf('/');
                String parent = slash > 0 ? galleryRel.substring(0, slash) : "";
                if (parent.contains("/")) parent = parent.substring(parent.lastIndexOf('/') + 1);
                base = sanitizeThemeFolder(parent + "_" + base);
            }
            galleryToLocal.put(galleryRel, base);
        }
        return galleryToLocal;
    }

    static String localBasename(String galleryRel) {
        return galleryRel.contains("/")
                ? galleryRel.substring(galleryRel.lastIndexOf('/') + 1) : galleryRel;
    }

    static JSONObject patchVariantConfigForInstall(JSONObject config, String displayName, String catalogFolder,
                                                   List<String> assetDirs, Map<String, String> galleryToLocal)
            throws Exception {
        JSONObject patched = new JSONObject(config.toString());
        setVariantDisplayName(patched, displayName);
        rewriteJsonAssetPaths(patched, catalogFolder, assetDirs, galleryToLocal);
        JSONObject solar = patched.optJSONObject("solarConfig");
        if (solar == null) solar = new JSONObject();
        solar.put("installedFromGallery", catalogFolder);
        JSONObject sources = new JSONObject();
        for (Map.Entry<String, String> e : galleryToLocal.entrySet()) {
            sources.put(e.getValue(), e.getKey());
        }
        solar.put("installedAssetSources", sources);
        patched.put("solarConfig", solar);
        patched.put("name", displayName);
        return patched;
    }

    static void setVariantDisplayName(JSONObject config, String displayName) {
        if (config.has("theme_info")) {
            try {
                config.getJSONObject("theme_info").put("title", displayName);
            } catch (Exception ignored) {}
        }
    }

    static void rewriteJsonAssetPaths(Object node, String catalogFolder, List<String> assetDirs,
                                      Map<String, String> galleryToLocal) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if ("installedAssetSources".equals(key) || "installedFromGallery".equals(key)) continue;
                Object val = obj.opt(key);
                if (val instanceof String) {
                    String rewritten = rewriteAssetRef((String) val, catalogFolder, assetDirs, galleryToLocal);
                    if (rewritten != null) obj.put(key, rewritten);
                } else {
                    rewriteJsonAssetPaths(val, catalogFolder, assetDirs, galleryToLocal);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object val = arr.opt(i);
                if (val instanceof String) {
                    String rewritten = rewriteAssetRef((String) val, catalogFolder, assetDirs, galleryToLocal);
                    if (rewritten != null) arr.put(i, rewritten);
                } else {
                    rewriteJsonAssetPaths(val, catalogFolder, assetDirs, galleryToLocal);
                }
            }
        }
    }

    static String rewriteAssetRef(String value, String catalogFolder, List<String> assetDirs,
                                    Map<String, String> galleryToLocal) {
        String gallery = normalizeAssetPath(value, catalogFolder, assetDirs);
        if (gallery == null) return null;
        String local = galleryToLocal.get(gallery);
        return local != null ? local : value;
    }

    static void downloadMissingVariantAssets(CatalogEntry entry, ThemeVariant variant, File dest,
                                             Set<String> missing, Set<String> skipped404,
                                             ProgressListener listener) throws Exception {
        JSONObject config = readConfig(new File(dest, "config.json"));
        JSONObject solar = config.optJSONObject("solarConfig");
        JSONObject sources = solar != null ? solar.optJSONObject("installedAssetSources") : null;
        if (sources == null) {
            downloadMissingAssets(entry, missing, skipped404, listener);
            return;
        }
        int total = missing.size();
        int done = 0;
        for (String localName : missing) {
            String galleryRel = sources.optString(localName, "");
            if (galleryRel.isEmpty()) {
                dlLog("recovery skip unknown local " + localName);
                done++;
                continue;
            }
            String url = themeFileUrlForGallery(galleryRel);
            dlLog("variant recovery GET " + localName + " <- " + galleryRel);
            try {
                writeFile(new File(dest, localName), httpGet(url));
            } catch (HttpStatusException hs) {
                if (hs.statusCode == 404) {
                    skipped404.add(localName);
                    dlLog("recovery skip 404 " + localName);
                } else {
                    throw hs;
                }
            }
            paceDownloads();
            done++;
            if (listener != null) listener.onProgress(done, total, localName);
        }
    }

    /** Filter variant list to those with a reachable config.json; fallback to catalog labels on failure. */
    public static List<ThemeVariant> fetchReachableVariants(CatalogEntry entry) {
        List<ThemeVariant> choices = new ArrayList<ThemeVariant>(entry.variants);
        if (choices.isEmpty()) return choices;
        List<ThemeVariant> ok = new ArrayList<ThemeVariant>();
        for (ThemeVariant v : choices) {
            String catalogFolder = v.galleryCatalogFolder(entry.folder);
            try {
                byte[] configBytes = httpGet(themeFileUrlForGallery(catalogFolder + "/config.json"));
                JSONObject config = new JSONObject(new String(configBytes, "UTF-8"));
                ok.add(enrichVariantLabel(v, entry.name, config));
            } catch (Exception e) {
                dlLog("variant unreachable " + catalogFolder + ": " + e.getMessage());
            }
        }
        return ok.isEmpty() ? choices : ok;
    }

    static void downloadMissingAssets(CatalogEntry entry, Set<String> missing, Set<String> skipped404, ProgressListener listener)
            throws Exception {
        int total = missing.size();
        int done = 0;
        for (String rel : missing) {
            String url = themeFileUrlForGallery(rel);
            dlLog("recovery GET " + rel + " <- " + url);
            if (rel.endsWith("/config.json") || "config.json".equals(rel)) {
                byte[] configBytes = httpGet(url);
                File cfgOut = new File(new File(ThemeManager.themesRoot(), entry.folder), "config.json");
                writeFile(cfgOut, configBytes);
                dlLog("recovery saved config.json (" + configBytes.length + "b)");
            } else {
                try {
                    byte[] fileBytes = httpGet(url);
                    writeFile(resolveAssetFile(new File(ThemeManager.themesRoot()), entry.themeDir(), entry.folder, rel), fileBytes);
                    dlLog("recovery saved " + rel + " (" + fileBytes.length + "b)");
                } catch (HttpStatusException hs) {
                    if (hs.statusCode == 404) {
                        skipped404.add(rel);
                        dlLog("recovery skip 404 " + rel);
                    } else {
                        throw hs;
                    }
                }
            }
            paceDownloads();
            done++;
            if (listener != null) listener.onProgress(done, total, rel);
        }
    }

    public static String themeFileUrl(String folder, String relativePath) {
        String gallery = relativePath;
        if (!relativePath.contains("/") && folder != null && !folder.isEmpty()) {
            gallery = folder + "/" + relativePath;
        }
        return themeFileUrlForGallery(gallery);
    }

    public static String themeFileUrlForGallery(String galleryRelativePath) {
        String rel = galleryRelativePath;
        while (rel.startsWith("./")) rel = rel.substring(2);
        if (rel.startsWith("/")) rel = rel.substring(1);
        try {
            String[] parts = rel.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(java.net.URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            }
            rel = sb.toString();
        } catch (Exception ignored) {}
        return BASE_URL + rel;
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
        } catch (Exception e) {
            dlLogError("writeFile " + file.getAbsolutePath(), e);
            throw e;
        }
    }

    static byte[] httpGet(String urlStr) throws Exception {
        Exception last = null;
        String httpsUrl = urlStr.startsWith("http://") ? "https://" + urlStr.substring(7) : urlStr;
        String httpUrl = urlStr.startsWith("https://") ? "http://" + urlStr.substring(8) : urlStr;
        for (String tryUrl : new String[] {httpsUrl, httpUrl}) {
            if (tryUrl == null || tryUrl.isEmpty()) continue;
            try {
                byte[] data = com.solar.launcher.net.SolarHttp.getBytes(tryUrl, "*/*", "SolarLauncher/1.0");
                dlLog("GET ok " + tryUrl + " (" + data.length + " bytes)");
                Log.i(TAG, "GET ok " + tryUrl + " (" + data.length + " bytes)");
                return data;
            } catch (Exception e) {
                last = e;
                dlLog("GET failed " + tryUrl + ": " + e.getMessage());
                Log.w(TAG, "GET failed " + tryUrl + ": " + e.getMessage());
            }
        }
        dlLogError("httpGet exhausted " + urlStr, last);
        throw last != null ? last : new Exception("HTTP GET failed");
    }

    private static void paceDownloads() throws InterruptedException {
        checkDownloadCancel();
        try {
            Thread.sleep(DOWNLOAD_PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    static class HttpStatusException extends Exception {
        final int statusCode;
        HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // --- download log (pull via adb) ---

    private static void beginSession(String kind, String detail) {
        activeSession = kind + (detail.isEmpty() ? "" : " " + detail);
        dlLog("=== BEGIN " + activeSession + " ===");
    }

    private static void endSession(boolean ok, String detail) {
        dlLog("=== END " + activeSession + " " + (ok ? "OK" : "FAIL") + (detail != null ? ": " + detail : "") + " ===");
        activeSession = "";
    }

    static void dlLog(String msg) {
        String line = ts() + " " + msg;
        Log.i(TAG, line);
        appendLogLine(line);
    }

    public static void dlLogError(String ctx, Exception e) {
        String line = ts() + " ERROR " + ctx + ": " + (e != null ? e.getMessage() : "null");
        Log.e(TAG, line, e);
        appendLogLine(line);
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            appendLogLine(sw.toString().trim());
        }
    }

    private static String ts() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static void appendLogLine(String line) {
        synchronized (LOG_LOCK) {
            try {
                File log = new File(downloadLogPath());
                File dir = log.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                if (log.isFile() && log.length() > LOG_MAX_BYTES) {
                    File rotated = new File(dir, "get_themes.log.old");
                    log.renameTo(rotated);
                    appendLogLine(ts() + " log rotated (previous -> get_themes.log.old)");
                }
                FileOutputStream fos = new FileOutputStream(log, true);
                fos.write((line + "\n").getBytes("UTF-8"));
                fos.close();
            } catch (Exception ignored) {}
        }
    }

    public interface ProgressListener {
        void onProgress(int done, int total, String fileName);
    }

    // ponytail self-check
    public static void selfCheck() throws Exception {
        String sample = "{\"themeCover\":\"cover.png\",\"fontFamily\":\"a.ttf\",\"COMMENT_fontFamily\":\"missing.ttf\",\"homePageConfig\":{\"music\":\"Music.png\"},\"note\":\"skip me\"}";
        Set<String> paths = collectAssetPaths(new JSONObject(sample));
        if (!paths.contains("cover.png") || !paths.contains("Music.png") || !paths.contains("a.ttf")) {
            throw new AssertionError("collectAssetPaths");
        }
        if (paths.contains("missing.ttf")) throw new AssertionError("comment asset skipped");
        if (paths.contains("skip me")) throw new AssertionError("non-asset string");
        Set<String> bat = collectAssetPaths(new JSONObject(
                "{\"statusConfig\":{\"battery\":[\"battery.001\",\"battery.002.png\"]}}"));
        if (!bat.contains("battery.001.png") || !bat.contains("battery.002.png")) {
            throw new AssertionError("battery array assets");
        }

        Set<String> sub = collectAssetPaths(new JSONObject(
                "{\"homePageConfig\":{\"music\":\"icons/Music.png\"}}"), "MyTheme");
        if (!sub.contains("MyTheme/icons/Music.png")) throw new AssertionError("subfolder asset");
        if (!collectAssetPaths(new JSONObject("{\"themeCover\":\"@cover.png\"}"), "T").contains("T/cover.png")) {
            throw new AssertionError("@ prefix");
        }
        if (!"Stranger Things/cover.png".equals(
                canonicalGalleryPath("Stranger Things/Variants/Robin", "../../cover.png"))) {
            throw new AssertionError("parent ref");
        }
        Set<String> dirFilter = collectAssetPaths(new JSONObject(
                "{\"solarConfig\":{\"assetDirs\":[\"icons\"]},\"homePageConfig\":{\"music\":\"icons/a.png\",\"x\":\"secret/b.png\"}}"), "T");
        if (!dirFilter.contains("T/icons/a.png") || dirFilter.contains("T/secret/b.png")) {
            throw new AssertionError("assetDirs filter");
        }

        List<CatalogEntry> cat = parseCatalog("{\"themes\":[{\"name\":\"A\",\"folder\":\"A\",\"author\":\"u/x\",\"screenshot\":\"./A/cover.png\"}]}");
        if (cat.size() != 1 || !"A".equals(cat.get(0).folder)) throw new AssertionError("parseCatalog");
        if (cat.get(0).hasVariants()) throw new AssertionError("no variants");

        List<CatalogEntry> st = parseCatalog("{\"themes\":[{\"name\":\"Stranger Things\",\"folder\":\"Stranger Things\","
                + "\"defaultVariantLabel\":\"Will\",\"variantFolders\":[\"Robin\"]}]}");
        if (!st.get(0).hasVariants() || st.get(0).variants.size() != 2) throw new AssertionError("variant parse count");
        if (!"Will".equals(st.get(0).variants.get(0).label)) throw new AssertionError("default variant");
        if (!"Variants/Robin".equals(st.get(0).variants.get(1).gallerySubpath)) throw new AssertionError("subpath");
        if (!"Stranger Things - Robin".equals(installFolderName("Stranger Things", "Robin"))) {
            throw new AssertionError("install folder");
        }
        if (!"Stranger Things (Robin)".equals(variantDisplayName("Stranger Things", "Robin"))) {
            throw new AssertionError("display name");
        }

        JSONObject titleCfg = new JSONObject("{\"theme_info\":{\"title\":\"Robin Edition\"},\"name\":\"ignored\"}");
        if (!"Robin Edition".equals(readThemeTitleFromConfig(titleCfg, "fb"))) {
            throw new AssertionError("theme_info.title");
        }
        if (!"RootName".equals(readThemeTitleFromConfig(new JSONObject("{\"name\":\"RootName\"}"), "fb"))) {
            throw new AssertionError("root name");
        }
        if (!"fb".equals(readThemeTitleFromConfig(new JSONObject("{}"), "fb"))) {
            throw new AssertionError("fallback title");
        }
        ThemeVariant base = new ThemeVariant("Stranger Things", "");
        ThemeVariant enriched = enrichVariantLabel(base, "Stranger Things",
                new JSONObject("{\"theme_info\":{\"title\":\"Will\"}}"));
        if (!"Will".equals(enriched.label)) throw new AssertionError("enrich base label");
        if (!hasVariantSubfolders(st.get(0))) throw new AssertionError("has subfolders");

        JSONObject robinCfg = new JSONObject("{\"themeCover\":\"../../cover.png\","
                + "\"homePageConfig\":{\"music\":\"../../Music.png\"},\"desktopMask\":\"mask.png\"}");
        Set<String> robinAssets = collectAssetPaths(robinCfg, "Stranger Things/Variants/Robin");
        Map<String, String> names = planLocalNames(robinAssets);
        JSONObject patched = patchVariantConfigForInstall(robinCfg, "Stranger Things (Robin)",
                "Stranger Things/Variants/Robin", readAssetDirs(robinCfg), names);
        if (!"Stranger Things (Robin)".equals(patched.optString("name"))) throw new AssertionError("patched name");
        if (patched.optString("themeCover", "").contains("/")) throw new AssertionError("flattened cover path");

        if (!cat.get(0).coverUrl().equals(BASE_URL + "A/cover.png")) throw new AssertionError("coverUrl");
        String httpCatalog = "http://themes.innioasis.app/x";
        String httpsFirst = httpCatalog.startsWith("http://")
                ? "https://" + httpCatalog.substring(7) : httpCatalog;
        if (!"https://themes.innioasis.app/x".equals(httpsFirst)) {
            throw new AssertionError("httpsFirst");
        }

        OptOutFilter opt = parseOptOut("{\"authors\":{\"u/x\":\"gone\"},\"folders\":[\"Blocked\"]}");
        List<CatalogEntry> filtered = filterCatalog(cat, opt);
        if (filtered.isEmpty()) throw new AssertionError("author not blocked");
        cat = parseCatalog("{\"themes\":[{\"name\":\"B\",\"folder\":\"Blocked\",\"author\":\"y\"}]}");
        filtered = filterCatalog(cat, opt);
        if (!filtered.isEmpty()) throw new AssertionError("blocked folder");
        if (!"Foo-Bar".equals(sanitizeThemeFolder("Foo/Bar"))) throw new AssertionError("sanitize folder");
        ThemeVariant slug = new ThemeVariant("Foo/Bar Edition", "Variants/Robin", "Robin");
        if (!"Stranger Things - Robin".equals(slug.installFolder("Stranger Things"))) {
            throw new AssertionError("variant install slug");
        }
        ThemeVariant enrichedOrphan = new ThemeVariant("Foo/Bar Edition", "", "Robin");
        if (!"Parent - Robin".equals(resolvedInstallFolder(
                parseCatalog("{\"themes\":[{\"name\":\"Orphan\",\"folder\":\"Parent/Variants/Robin\"}]}").get(0),
                enrichedOrphan))) {
            throw new AssertionError("resolvedInstallFolder orphan");
        }
        if (!"Simple".equals(resolvedInstallFolder(
                parseCatalog("{\"themes\":[{\"name\":\"Simple\",\"folder\":\"Simple\"}]}").get(0), null))) {
            throw new AssertionError("resolvedInstallFolder base");
        }

        File tmp = File.createTempFile("solar-theme-", "");
        tmp.delete();
        tmp.mkdirs();
        try {
            JSONObject cfg = new JSONObject(sample);
            writeFile(new File(tmp, "config.json"), cfg.toString().getBytes("UTF-8"));
            writeFile(new File(tmp, "cover.png"), new byte[]{1, 2, 3});
            Set<String> miss = missingAssets(tmp);
            String folder = catalogFolderFor(tmp);
            if (!miss.contains(folder + "/a.ttf") || !miss.contains(folder + "/Music.png")) {
                throw new AssertionError("missingAssets " + miss);
            }
            if (miss.contains("missing.ttf")) throw new AssertionError("missing comment asset");
        } finally {
            deleteRecursive(tmp);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
