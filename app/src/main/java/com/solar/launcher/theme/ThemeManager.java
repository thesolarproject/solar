package com.solar.launcher.theme;

import com.solar.launcher.HomeMenuConfig;
import com.solar.launcher.SolarOverlayHost;
import com.solar.launcher.A5NavigationMode;
import com.solar.launcher.DeviceFeatures;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ponytail: one file — Y1 config.json themes only. Bundled Default from assets.
 */
public class ThemeManager {

    /** Legacy Y1 path; use {@link #themesRoot()} after init. */
    public static final String PATH_THEMES = "/storage/sdcard0/Themes";
    public static final String BUILTIN_DEFAULT_FOLDER = "Default";
    /** SharedPreferences keys — same store as {@link com.solar.launcher.MainActivity} settings. */
    public static final String PREF_THEME_INDEX = "app_theme_index";
    public static final String PREF_THEME_PATH = "app_theme_path";
    /** Stable folder name — survives theme list reorder and internal/SD path moves. */
    public static final String PREF_THEME_FOLDER = "app_theme_folder";
    private static final String PREFS_NAME = "SOLAR_SETTINGS";
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
    /**
     * 2026-07-20 — Theme PNG RAM via ByteBudgetLruMap (was unbounded HashMap).
     * Layman: remember theme pictures, but forget old ones when RAM gets full.
     * Reversal: HashMap&lt;String,Bitmap&gt; bitmapCache / scaledRowBitmapCache.
     */
    private static final int THEME_BITMAP_BUDGET_BYTES = 8 * 1024 * 1024;
    private static final int SCALED_ROW_BUDGET_BYTES = 4 * 1024 * 1024;
    private static final ByteBudgetLruMap.SizeOf<Bitmap> BITMAP_BYTES =
            new ByteBudgetLruMap.SizeOf<Bitmap>() {
                @Override
                public int sizeOf(Bitmap value) {
                    if (value == null || value.isRecycled()) return 0;
                    try {
                        return value.getByteCount();
                    } catch (Throwable t) {
                        return value.getRowBytes() * value.getHeight();
                    }
                }
            };
    private static final ByteBudgetLruMap<String, Bitmap> bitmapCache =
            new ByteBudgetLruMap<String, Bitmap>(THEME_BITMAP_BUDGET_BYTES, BITMAP_BYTES);
    /** Scaled row tiles — focus scroll was re-scaling on every D-pad step (Y1 jank). */
    private static final ByteBudgetLruMap<String, Bitmap> scaledRowBitmapCache =
            new ByteBudgetLruMap<String, Bitmap>(SCALED_ROW_BUDGET_BYTES, BITMAP_BYTES);
    private static Typeface cachedFont;
    private static String cachedFontKey = "";
    /** :overlay process finished full theme bootstrap — skip heavy I/O on every modal open. */
    private static volatile boolean overlayThemeBootstrapped;
    /** Last theme folder warmed for overlay — cheap stale check vs prefs. */
    private static volatile String lastOverlayThemeFolder = "";
    /** :overlay bootstrap latch — finishShowOverlay waits up to 500ms then fail-open paints. */
    private static volatile CountDownLatch overlayThemeReadyLatch = new CountDownLatch(1);
    /** 2026-07-05 — Snapshot restored theme entry before bitmap warm — instant overlay text/font. */
    private static volatile boolean overlayRamCacheLoaded;
    /** While UMS exports SD card, avoid mmap on theme files so vold can unmount without killing us. */
    private static volatile boolean blockSdcardThemeAssets = false;
    /** 2026-07-05 — SolarApplication bootstrap finished — MainActivity skips duplicate theme I/O. */
    private static volatile boolean appThemeBootstrapComplete;
    private static boolean statusBarMatchItemText = false;
    /** User pref: Now Playing title/artist use list item text colour (default off — Y1 white). */
    private static boolean nowPlayingMatchItemText = false;
    /** User pref: semi-translucent meta bars (default off — theme engine / Y1 original). */
    private static boolean nowPlayingBackdropEnabled = true;
    /** User pref: dithered LCD-style album art tinted to theme font colour. */
    private static boolean nowPlayingLcdArtEnabled = false;

    /**
     * Canonical theme <b>load/store</b> root (always on MMC when possible).
     * 2026-07-17 — Order: public Internal Themes/ → app-private filesDir/Themes → MicroSD last.
     * Layman: Solar loads themes from internal storage for speed; MicroSD is a 1:1 manage mirror.
     * Was: MicroSD preferred when Internal missing, so SD eject broke theming and mirror never ran.
     * Reversal: prefer MicroSD when Internal/filesDir unusable only.
     */
    public static String resolveThemesRoot(Context ctx) {
        File store = canonicalThemesStore(ctx);
        if (store != null) {
            return store.getAbsolutePath();
        }
        File micro = com.solar.launcher.DeviceFeatures.getMicroSdThemesDir();
        if (micro != null) {
            return micro.getAbsolutePath();
        }
        return PATH_THEMES;
    }

    /**
     * Actual theme store on eMMC: public Internal Themes/ if present, else filesDir/Themes.
     * Never MicroSD — that is the peer mirror only.
     */
    public static File canonicalThemesStore(Context ctx) {
        File internalPublic = com.solar.launcher.DeviceFeatures.getInternalPublicThemesDir();
        if (internalPublic != null) {
            // Prefer public Internal even if empty — mkdir on ensure.
            return internalPublic;
        }
        if (ctx != null) {
            return internalThemesDir(ctx);
        }
        if (assetContext != null) {
            return internalThemesDir(assetContext);
        }
        return null;
    }

    public static String themesRoot() {
        return themesRootPath;
    }

    /** Min gap between full library mirror passes (was syncing on every ensure/load → multi-second I/O storms). */
    private static final long THEME_LIBRARY_SYNC_MIN_MS = 45_000L;
    private static volatile long lastThemeLibrarySyncAtMs;
    private static final java.util.concurrent.atomic.AtomicBoolean themeLibrarySyncInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Ensures MMC theme store exists and is writable.
     * 2026-07-17 — Does <b>not</b> run full MicroSD mirror on the caller thread (that froze
     * cold start ~16s and hung adb input). Mirror is scheduled async.
     */
    public static boolean ensureThemesRootReady(Context ctx) {
        Context app = ctx != null ? ctx.getApplicationContext() : assetContext;
        if (app != null) {
            assetContext = app;
            themesRootPath = resolveThemesRoot(app);
        }
        File root = new File(themesRootPath);
        if (!root.isDirectory()) {
            root.mkdirs();
        }
        if (root.isDirectory() && root.canWrite()) {
            new File(root, ".cache/covers").mkdirs();
            scheduleThemeLibrarySync(app, false);
            return true;
        }
        // Public Internal not writable — fall back to app-private eMMC Themes.
        if (app == null) return false;
        themesRootPath = internalThemesDir(app).getAbsolutePath();
        root = new File(themesRootPath);
        if (!root.mkdirs() && !root.isDirectory()) return false;
        new File(root, ".cache/covers").mkdirs();
        scheduleThemeLibrarySync(app, false);
        return root.canWrite();
    }

    /**
     * Schedule 1:1 MMC ↔ MicroSD theme mirror on a background thread.
     * @param force true to ignore throttle (download complete / media mounted)
     */
    public static void scheduleThemeLibrarySync(final Context ctx, boolean force) {
        final Context app = ctx != null ? ctx.getApplicationContext() : assetContext;
        if (app == null) return;
        long now = System.currentTimeMillis();
        if (!force && lastThemeLibrarySyncAtMs > 0L
                && (now - lastThemeLibrarySyncAtMs) < THEME_LIBRARY_SYNC_MIN_MS) {
            return;
        }
        if (!themeLibrarySyncInFlight.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    syncPublicThemesBidirectional(app);
                    lastThemeLibrarySyncAtMs = System.currentTimeMillis();
                } catch (Throwable ignored) {
                } finally {
                    themeLibrarySyncInFlight.set(false);
                }
            }
        }, "ThemeLibrarySync").start();
    }

    /**
     * 1:1 theme library mirror: MMC store ↔ MicroSD Themes/ (both directions).
     * Also keeps filesDir/Themes in step with the store for UMS-safe active loads.
     * Layman: drop themes on either card; Solar loads from internal; both folders stay identical.
     * Call from background only — never the UI/wheel path.
     */
    public static void syncPublicThemesBidirectional(Context ctx) {
        Context app = ctx != null ? ctx.getApplicationContext() : assetContext;
        File store = canonicalThemesStore(app);
        if (store == null) return;
        if (!store.exists()) store.mkdirs();
        if (!store.isDirectory()) return;

        File micro = com.solar.launcher.DeviceFeatures.getMicroSdThemesDir();
        if (micro != null) {
            if (!micro.exists()) micro.mkdirs();
            if (micro.isDirectory()) {
                // MicroSD → MMC first (user may have added themes on the card)
                syncThemeLibraryOneWay(micro, store);
                // MMC → MicroSD (downloads / MMC-managed themes)
                syncThemeLibraryOneWay(store, micro);
            }
        }

        // App-private eMMC cache: full library when store is public Internal.
        if (app != null) {
            File filesThemes = internalThemesDir(app);
            if (filesThemes != null
                    && !samePath(store, filesThemes)
                    && (filesThemes.isDirectory() || filesThemes.mkdirs())) {
                syncThemeLibraryOneWay(store, filesThemes);
            }
        }
    }

    /** True when paths resolve to the same directory. */
    static boolean samePath(File a, File b) {
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    /**
     * Whether {@code src} theme folder should replace {@code dest}.
     * Uses config.json length + mtime (FAT 2s skew); never parent-dir mtime.
     */
    static boolean themeFolderNeedsCopy(File src, File dest) {
        if (src == null || !src.isDirectory()) return false;
        File srcCfg = new File(src, "config.json");
        if (!srcCfg.isFile() || srcCfg.length() <= 0) return false;
        if (dest == null) return true;
        File destCfg = new File(dest, "config.json");
        if (!destCfg.isFile() || destCfg.length() <= 0) return true;
        if (srcCfg.length() != destCfg.length()) return true;
        // Source strictly newer (2s FAT skew) → copy
        if (srcCfg.lastModified() > destCfg.lastModified() + 2000L) return true;
        return false;
    }

    /** Copy theme folders from srcRoot → destRoot when missing or older at dest. */
    private static void syncThemeLibraryOneWay(File srcRoot, File destRoot) {
        if (srcRoot == null || destRoot == null) return;
        if (samePath(srcRoot, destRoot)) return;
        File[] kids = srcRoot.listFiles();
        if (kids == null) return;
        for (File src : kids) {
            if (!src.isDirectory() || src.getName().startsWith(".")) continue;
            if (!new File(src, "config.json").isFile()) continue;
            File dest = new File(destRoot, src.getName());
            if (!themeFolderNeedsCopy(src, dest)) continue;
            try {
                if (!dest.exists() && !dest.mkdirs()) continue;
                copyDirectory(src, dest);
                // Align config mtime so reverse pass does not thrash.
                File destCfg = new File(dest, "config.json");
                File srcCfg = new File(src, "config.json");
                if (destCfg.isFile() && srcCfg.isFile()) {
                    destCfg.setLastModified(srcCfg.lastModified());
                }
                dest.setLastModified(src.lastModified());
            } catch (Exception ignored) {}
        }
    }

    /**
     * Delete a theme from public Internal, MicroSD, and filesDir UMS cache.
     * 2026-07-15 — Context-menu Remove must clear the triple-copy, not just themesRoot().
     */
    public static boolean deleteThemeEverywhere(Context ctx, String folderName) {
        if (folderName == null || folderName.isEmpty()) return false;
        if (BUILTIN_DEFAULT_FOLDER.equalsIgnoreCase(folderName)) return false;
        boolean any = false;
        File internal = com.solar.launcher.DeviceFeatures.getInternalPublicThemesDir();
        if (internal != null) any |= deleteThemeFolder(new File(internal, folderName));
        File micro = com.solar.launcher.DeviceFeatures.getMicroSdThemesDir();
        if (micro != null) any |= deleteThemeFolder(new File(micro, folderName));
        if (ctx != null) {
            any |= deleteThemeFolder(new File(internalThemesDir(ctx), folderName));
        }
        // Also drop install root copy if distinct.
        any |= deleteThemeFolder(new File(themesRootPath, folderName));
        return any;
    }

    private static boolean deleteThemeFolder(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        try {
            deleteRecursive(dir);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Recursively delete a directory tree (theme Remove / prune). */
    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }

    /**
     * 2026-07-15 — Asset-only Aura for first paint before disk seed finishes.
     * Was: load bundledFallback JSON only (availableThemes stayed empty → stub home icons).
     * Now: also park Aura in availableThemes so Music_YS / chrome decode on cold start.
     * Reversal: drop availableThemes seed; first frame used empty homePageConfig stub again.
     */
    public static void ensureFastStartupTheme(Context ctx) {
        if (ctx != null) assetContext = ctx.getApplicationContext();
        ensureBundledFallback(ctx);
        // Park asset:// Aura so getCurrentTheme() is never an empty homePageConfig stub.
        if (bundledFallback != null && availableThemes.isEmpty()) {
            availableThemes.add(bundledFallback);
            currentThemeIndex = 0;
        }
        if (PATH_THEMES.equals(themesRootPath)) {
            ensureThemesRootReady(ctx);
        }
    }

    /** Called from SolarApplication bootstrap thread when full theme ladder completes. */
    public static void markAppThemeBootstrapComplete() {
        appThemeBootstrapComplete = true;
    }

    /** MainActivity defers duplicate ensureBundledDefault when Application already ran. */
    public static boolean isAppThemeBootstrapComplete() {
        return appThemeBootstrapComplete;
    }

    /**
     * Release SD-backed theme mmap before UMS export/unmount.
     * ponytail: vold kills processes holding open filemaps on the exported volume.
     */
    public static void releaseSdcardFileHandles() {
        bitmapCache.clear();
        scaledRowBitmapCache.clear();
        cachedFont = null;
        cachedFontKey = "";
        auraFont = null;
    }

    /**
     * 2026-07-20 — MemoryRelease ladder: drop rebuildable theme tiles under pressure.
     * Layman: forget scaled row pictures first; on hard squeeze, clear theme PNGs too (reload on next paint).
     * Technical: soft → scaledRow evictAll + bitmap trim half; severe → both evictAll. Never touches NP art.
     * Reversal: no-op; HashMap clear only on theme switch.
     */
    public static void releaseMemoryCaches(boolean severe) {
        try {
            scaledRowBitmapCache.evictAll();
            if (severe) {
                bitmapCache.evictAll();
            } else {
                bitmapCache.trimToBytes(THEME_BITMAP_BUDGET_BYTES / 2);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 2026-07-20 — Alias for {@link #releaseMemoryCaches} (MemoryRelease ladder name).
     * Layman: free warm theme pictures when Android asks for RAM.
     */
    public static void releaseWarmBitmaps(boolean severe) {
        releaseMemoryCaches(severe);
    }

    /** App-private MMC copy of themes — survives USB mass-storage export of MicroSD. */
    public static File internalThemesDir(Context ctx) {
        if (ctx == null) return new File("/data/local/tmp/Themes");
        return new File(ctx.getApplicationContext().getFilesDir(), "Themes");
    }

    private static boolean isPathOnExternalSd(String path) {
        if (path == null || path.isEmpty() || path.startsWith("asset://")) return false;
        if (assetContext != null
                && path.startsWith(assetContext.getFilesDir().getAbsolutePath())) {
            return false;
        }
        try {
            File ext = com.solar.launcher.DeviceFeatures.getPrimaryStorageRoot();
            if (ext != null && path.startsWith(ext.getAbsolutePath())) return true;
        } catch (Exception ignored) {}
        for (File root : com.solar.launcher.DeviceFeatures.getStorageRoots()) {
            if (path.startsWith(root.getAbsolutePath())) return true;
        }
        return path.startsWith("/storage/sdcard") || path.startsWith(PATH_THEMES);
    }

    private static boolean shouldSkipExternalThemeFile(String absolutePath) {
        return blockSdcardThemeAssets && isPathOnExternalSd(absolutePath);
    }

    /**
     * Sync active theme → filesDir UMS cache and point the in-memory entry at that cache.
     * 2026-07-15 — Source is public Themes (Internal preferred); filesDir survives eject/UMS.
     * ponytail: call on theme apply, boot, and before UMS so USB lock screen keeps the user's theme.
     */
    public static void cacheActiveTheme(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        assetContext = app;
        // Mkdir store; full library mirror is async (do not block theme apply / boot).
        ensureThemesRootReady(app);
        ThemeEntry active = getCurrentTheme();
        if (active == null || active == bundledFallback
                || (active.folderPath != null && active.folderPath.startsWith("asset://"))) {
            finishThemeCachePipeline(app);
            return;
        }
        // Prefer MMC store path for runtime loads (fast + works when MicroSD ejected).
        File store = canonicalThemesStore(app);
        if (store != null) {
            File storeDir = new File(store, active.folderName);
            if (new File(storeDir, "config.json").isFile()) {
                switchThemeEntryToInternal(app, active, storeDir);
                // Also keep filesDir UMS cache current for export safety.
                File filesDir = new File(internalThemesDir(app), active.folderName);
                if (!samePath(storeDir, filesDir) && themeFolderNeedsCopy(storeDir, filesDir)) {
                    try {
                        if (!filesDir.exists()) filesDir.mkdirs();
                        copyDirectory(storeDir, filesDir);
                    } catch (Exception ignored) {}
                }
                finishThemeCachePipeline(app);
                return;
            }
        }
        File cacheDir = new File(internalThemesDir(app), active.folderName);
        // Already on filesDir UMS cache — still refresh sidecars.
        if (active.folderPath != null
                && active.folderPath.startsWith(app.getFilesDir().getAbsolutePath())) {
            finishThemeCachePipeline(app);
            return;
        }
        File sourceDir = new File(active.folderPath);
        if (!sourceDir.isDirectory()) {
            File pub = com.solar.launcher.DeviceFeatures.getInternalPublicThemesDir();
            if (pub != null) {
                File alt = new File(pub, active.folderName);
                if (new File(alt, "config.json").isFile()) sourceDir = alt;
            }
        }
        if (!sourceDir.isDirectory()) return;
        if (!themeFolderNeedsCopy(sourceDir, cacheDir)) {
            switchThemeEntryToInternal(app, active, cacheDir);
            finishThemeCachePipeline(app);
            return;
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;
        try {
            copyDirectory(sourceDir, cacheDir);
        } catch (Exception ignored) {}
        switchThemeEntryToInternal(app, active, cacheDir);
        finishThemeCachePipeline(app);
    }

    /** 2026-07-05 — Mirror active theme to every .solar root, then publish sidecars + snapshot. */
    private static void finishThemeCachePipeline(Context app) {
        ThemeEntry active = getCurrentTheme();
        if (active != null && active.folderName != null) {
            File src = new File(internalThemesDir(app), active.folderName);
            if (!new File(src, "config.json").isFile() && active.folderPath != null
                    && !active.folderPath.startsWith("asset://")) {
                src = new File(active.folderPath);
            }
            if (new File(src, "config.json").isFile()) {
                ThemeMirrorHelper.mirrorActiveTheme(app, src, active.folderName);
            }
        }
        SystemFontBridge.publish(app);
        ThemeColorBridge.publish(app);
        ThemeSnapshotBridge.publish(app);
        ThemeSkinBridge.publish(app);
    }

    /** Prefer MMC cache for the active theme when config.json is present (faster + UMS-safe). */
    public static void preferInternalCacheForActiveTheme(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        ThemeEntry cur = getCurrentTheme();
        if (cur != null && cur.folderName != null) {
            File cacheDir = new File(internalThemesDir(app), cur.folderName);
            File cachedCfg = new File(cacheDir, "config.json");
            if (cachedCfg.isFile() && cachedCfg.length() > 0) {
                switchThemeEntryToInternal(app, cur, cacheDir);
            }
        }
        finishThemeCachePipeline(app);
    }

    /**
     * 2026-07-05 — Synchronous RAM warm from dual-storage snapshot at Application onCreate.
     * Layman: read the tiny theme cheat sheet before any overlay or modal paints.
     */
    public static void loadOverlayRamCacheSync(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        assetContext = app;
        ActiveThemeEngine.init(app);
        overlayRamCacheLoaded = ThemeSnapshotBridge.loadIntoThemeManager(app);
    }

    /** True when snapshot restored a theme entry — colors/font ready before bitmap warm. */
    public static boolean isOverlayRamCacheLoaded() {
        return overlayRamCacheLoaded;
    }

    /** 2026-07-05 — Overlay-only parse wrapper for {@link ThemeSnapshotBridge}. */
    static ThemeEntry parseFolderForOverlay(File folder) {
        return parseFolder(folder);
    }

    /** 2026-07-05 — Install one theme entry from snapshot without full Themes/ scan. */
    static void installOverlayRamEntry(ThemeEntry entry, String folderName) {
        if (entry == null) return;
        availableThemes.clear();
        availableThemes.add(entry);
        currentThemeIndex = 0;
        bitmapCache.clear();
        scaledRowBitmapCache.clear();
        cachedFont = null;
        cachedFontKey = "";
        auraFont = null;
        lastOverlayThemeFolder = folderName != null ? folderName : entry.folderName;
        overlayRamCacheLoaded = true;
    }

    /** 2026-07-05 — Decode theme font into RAM after snapshot entry install. */
    static void preloadOverlayFontFromRam() {
        getCustomFont();
    }

    /**
     * 2026-07-05 — Instant overlay paint when theme snapshot is missing at cold boot.
     * Layman: show the menu shell right away with bundled colors; PNG chrome warms in the background.
     * Technical: asset config.json only — no Themes/ rescan or MMC copy (that blocked paint for minutes).
     * Reversal: old loadOverlayAndFinish worker path waited on ensureOverlayThemeReady before finishShowOverlay.
     */
    public static void ensureOverlayPaintableMinimum(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        assetContext = app;
        if (!overlayRamCacheLoaded) {
            loadOverlayRamCacheSync(app);
        }
        if (overlayRamCacheLoaded) {
            preloadOverlayFontFromRam();
            return;
        }
        ensureBundledFallback(app);
        if (availableThemes.isEmpty() && bundledFallback != null) {
            availableThemes.add(bundledFallback);
            currentThemeIndex = 0;
        }
        preloadOverlayFontFromRam();
    }

    /**
     * Restore user's theme from prefs — required in :overlay process (separate from MainActivity).
     * Path to folder name to index, in that order.
     */
    public static void restoreSavedThemeFromPrefs(Context ctx) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        applySavedThemeSelection(
                prefs.getString(PREF_THEME_PATH, null),
                prefs.getString(PREF_THEME_FOLDER, null),
                prefs.getInt(PREF_THEME_INDEX, 0));
    }

    /** Test hook — selection logic without SharedPreferences / Robolectric. */
    static void applySavedThemeSelection(String savedPath, String savedFolder, int savedIndex) {
        if (savedPath != null && savedPath.length() > 0) {
            int idx = findThemeIndexForPath(savedPath);
            if (idx >= 0) {
                setThemeIndex(idx);
                return;
            }
        }
        if (savedFolder != null && savedFolder.length() > 0) {
            int idx = findThemeIndexForPath(savedFolder);
            if (idx >= 0) {
                setThemeIndex(idx);
                return;
            }
        }
        setThemeIndex(savedIndex);
    }

    /** Persist active theme path/folder/index after apply — readable from :overlay process. */
    public static void syncSavedThemeToPrefs(Context ctx) {
        if (ctx == null) return;
        ThemeEntry active = getCurrentTheme();
        if (active == null) return;
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit()
                .putInt(PREF_THEME_INDEX, getCurrentThemeIndex())
                .putString(PREF_THEME_FOLDER, active.folderName);
        String persistPath = persistPathForTheme(active, ctx);
        if (active.folderPath != null && !active.folderPath.startsWith("asset://")) {
            ed.putString(PREF_THEME_PATH, persistPath);
        }
        ed.apply();
    }

    /**
     * Overlay theme bootstrap — load only the saved theme folder + decode menu chrome into RAM.
     * Skips {@link #loadAllThemes} and {@link #cacheActiveTheme} (full home/settings scan).
     */
    public static void ensureOverlayThemeReady(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        assetContext = app;
        if (!overlayRamCacheLoaded) {
            loadOverlayRamCacheSync(app);
        }
        reloadSolarSettingsPrefsFromDisk(app);
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String folder = prefs.getString(PREF_THEME_FOLDER, "");
        if (overlayRamCacheLoaded && folder.length() > 0 && folder.equals(lastOverlayThemeFolder)) {
            preferInternalCacheForActiveTheme(app);
        } else {
            ensureOverlayActiveThemeOnly(app);
        }
        warmOverlayThemeCache(app);
        ThemeEntry active = getCurrentTheme();
        lastOverlayThemeFolder = active != null ? active.folderName : "";
        overlayThemeBootstrapped = true;
        overlayThemeReadyLatch.countDown();
    }

    /** Block overlay paint until :overlay warms theme chrome — fail-open after timeoutMs. */
    public static void awaitOverlayThemeReady(long timeoutMs) {
        if (overlayThemeBootstrapped) return;
        if (overlayRamCacheLoaded && timeoutMs > 0) {
            long ramWait = Math.min(timeoutMs, 1200L);
            CountDownLatch latch = overlayThemeReadyLatch;
            try {
                latch.await(ramWait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
            if (overlayThemeBootstrapped || overlayRamCacheLoaded) return;
        }
        CountDownLatch latch = overlayThemeReadyLatch;
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    /** Drop overlay RAM cache — next show reloads saved theme folder. */
    public static void invalidateOverlayThemeCache() {
        overlayThemeBootstrapped = false;
        overlayRamCacheLoaded = false;
        lastOverlayThemeFolder = "";
        overlayThemeReadyLatch = new CountDownLatch(1);
    }

    /** Main process theme pick — invalidate overlay cache and warm :overlay again. */
    public static void notifyOverlayThemeChanged(Context ctx) {
        invalidateOverlayThemeCache();
        SolarOverlayHost.reloadOverlayTheme(ctx);
    }

    /**
     * :overlay runs in a separate process — reload SOLAR_SETTINGS from disk so theme picks
     * from the main Solar process are visible before painting global overlays.
     */
    public static void reloadSolarSettingsPrefsFromDisk(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            java.lang.reflect.Method reload = prefs.getClass().getDeclaredMethod("startLoadFromDisk");
            reload.setAccessible(true);
            reload.invoke(prefs);
        } catch (Exception ignored) {}
    }

    /**
     * Resolve the user's saved theme for :overlay — internal MMC cache first, then one folder lookup.
     */
    private static void ensureOverlayActiveThemeOnly(Context ctx) {
        reloadSolarSettingsPrefsFromDisk(ctx);
        ensureThemesRootReady(ctx);
        ensureBundledDefault(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String folder = prefs.getString(PREF_THEME_FOLDER, "");
        String savedPath = prefs.getString(PREF_THEME_PATH, "");
        ThemeEntry cur = getCurrentTheme();
        if (cur != null && folder.length() > 0 && folder.equals(cur.folderName)) {
            preferInternalCacheForActiveTheme(ctx);
            ensureActiveThemeOrFallback(ctx);
            return;
        }
        availableThemes.clear();
        bitmapCache.clear();
        scaledRowBitmapCache.clear();
        cachedFont = null;
        cachedFontKey = "";
        auraFont = null;
        ThemeEntry loaded = resolveOverlayThemeEntry(ctx, folder, savedPath);
        if (loaded != null) {
            availableThemes.add(loaded);
            currentThemeIndex = 0;
        } else if (bundledFallback != null) {
            availableThemes.add(bundledFallback);
            currentThemeIndex = 0;
        }
        preferInternalCacheForActiveTheme(ctx);
        ensureActiveThemeOrFallback(ctx);
    }

    /** Pick one theme dir for overlay paint — no full Themes/ rescan. */
    private static ThemeEntry resolveOverlayThemeEntry(Context ctx, String folder, String savedPath) {
        if (folder != null && folder.length() > 0) {
            File cached = new File(internalThemesDir(ctx), folder);
            ThemeEntry fromCache = parseFolder(cached);
            if (fromCache != null) return fromCache;
            File mirrored = ThemeMirrorHelper.findMirroredThemeDir(ctx, folder);
            if (mirrored != null) {
                ThemeEntry fromMirror = parseFolder(mirrored);
                if (fromMirror != null) return fromMirror;
            }
        }
        if (savedPath != null && savedPath.length() > 0) {
            if (savedPath.startsWith("asset://") || "default".equalsIgnoreCase(savedPath.trim())) {
                if (bundledFallback != null) return bundledFallback;
            }
            File pathFile = new File(savedPath);
            File dir = pathFile.isFile() ? pathFile.getParentFile() : pathFile;
            if (dir != null) {
                ThemeEntry fromPath = parseFolder(dir);
                if (fromPath != null) return fromPath;
            }
        }
        if (folder != null && folder.length() > 0) {
            File underRoot = new File(themesRootPath, folder);
            ThemeEntry fromRoot = parseFolder(underRoot);
            if (fromRoot != null) return fromRoot;
            for (File themeRoot : com.solar.launcher.DeviceFeatures.getThemeRoots()) {
                ThemeEntry fromSd = parseFolder(new File(themeRoot, folder));
                if (fromSd != null) return fromSd;
            }
        }
        File defaultDir = new File(themesRootPath, BUILTIN_DEFAULT_FOLDER);
        ThemeEntry def = parseFolder(defaultDir);
        if (def != null) return def;
        return bundledFallback;
    }

    /**
     * Fast path before painting global overlay — no loadAllThemes/cache copy when already warm.
     * When bootstrapped and the saved theme folder is unchanged, this is a no-op so row bitmaps
     * stay in RAM (restoreSavedThemeFromPrefs would clear bitmapCache every open).
     */
    public static void touchOverlayThemeForShow(Context ctx) {
        if (ctx == null) return;
        reloadSolarSettingsPrefsFromDisk(ctx);
        if (!overlayThemeBootstrapped) {
            ensureOverlayThemeReady(ctx);
            return;
        }
        Context app = ctx.getApplicationContext();
        assetContext = app;
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String folder = prefs.getString(PREF_THEME_FOLDER, "");
        if (folder.length() > 0 && !folder.equals(lastOverlayThemeFolder)) {
            overlayThemeBootstrapped = false;
            overlayThemeReadyLatch = new CountDownLatch(1);
            ensureOverlayThemeReady(ctx);
        }
    }

    /** Test hook — simulate warm overlay bootstrap without Android context. */
    static void markOverlayThemeBootstrappedForTest(String folder) {
        overlayThemeBootstrapped = true;
        lastOverlayThemeFolder = folder != null ? folder : "";
    }

    /** Test hook — overlay bootstrap flag without Android context. */
    static void resetOverlayThemeBootstrapForTest() {
        overlayThemeBootstrapped = false;
        overlayRamCacheLoaded = false;
        lastOverlayThemeFolder = "";
        overlayThemeReadyLatch = new CountDownLatch(1);
    }

    static boolean isOverlayThemeBootstrappedForTest() {
        return overlayThemeBootstrapped;
    }

    /** True when :overlay has warmed menu chrome — skip theme I/O on passive volume HUD. */
    public static boolean isOverlayThemeReady() {
        return overlayThemeBootstrapped;
    }

    /**
     * Decode row/menu bitmaps and font into RAM — avoids Aura pop-in while overlay paints.
     * Safe during USB mass-storage (uses internal MMC cache only).
     */
    public static void warmOverlayThemeCache(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        assetContext = app;
        ThemeEntry active = getCurrentTheme();
        if (active == null) return;
        preloadOverlayEssentialBitmaps(active);
        getCustomFont();
        android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
        float density = dm.density;
        int screenW = dm.widthPixels > 0 ? dm.widthPixels : 480;
        int margin = (int) (10f * density);
        int panelW = screenW > margin * 2 ? screenW - margin * 2 : screenW;
        int rowH = (int) (45f * density);
        if (rowH < 1) rowH = 45;
        android.content.res.Resources res = app.getResources();
        getItemRowBackgroundScaled(res, false, panelW, rowH);
        getItemRowBackgroundScaled(res, true, panelW, rowH);
        getMenuRowBackgroundScaled(res, false, panelW, rowH);
        getMenuRowBackgroundScaled(res, true, panelW, rowH);
        getDialogOptionRowBackgroundScaled(res, false, panelW, rowH);
        getDialogOptionRowBackgroundScaled(res, true, panelW, rowH);
        getScaledItemRightArrow(rowH);
        getContextMenuPanelColor();
        getRowSelectionFillColor();
        buildContextMenuPanelDrawable(app);
        ThemeSnapshotBridge.publish(app);
    }

    /** Preload only row/menu chrome used by {@link com.solar.launcher.ThemedContextMenu} overlay tiers. */
    private static void preloadOverlayEssentialBitmaps(ThemeEntry entry) {
        if (entry == null || entry.root == null) return;
        JSONObject item = entry.root.optJSONObject("itemConfig");
        if (item != null) {
            preloadBitmapRef(item.optString("itemBackground", ""));
            preloadBitmapRef(item.optString("itemSelectedBackground", ""));
        }
        JSONObject menu = entry.root.optJSONObject("menuConfig");
        if (menu != null) {
            preloadBitmapRef(menu.optString("menuItemBackground", ""));
            preloadBitmapRef(menu.optString("menuItemSelectedBackground", ""));
        }
        JSONObject dialog = entry.root.optJSONObject("dialogConfig");
        if (dialog != null) {
            preloadBitmapRef(dialog.optString("dialogOptionBackground", ""));
            preloadBitmapRef(dialog.optString("dialogOptionSelectedBackground", ""));
        }
        preloadBitmapRef(entry.root.optString("fontFamily", ""));
    }

    /** Load one theme asset path into {@link #bitmapCache} when it looks like a bitmap file. */
    private static void preloadBitmapRef(String ref) {
        if (ref == null || ref.length() == 0) return;
        if (looksLikeThemeBitmapRef(ref)) {
            getThemeBitmap(ref);
        }
    }

    /** Walk config blocks and mmap theme PNGs into {@link #bitmapCache} while paths are readable. */
    private static void preloadThemeAssetBitmaps(ThemeEntry entry) {
        if (entry == null || entry.root == null) return;
        for (String block : new String[]{
                "itemConfig", "menuConfig", "homePageConfig", "settingConfig", "dialogConfig", "solarConfig"
        }) {
            JSONObject obj = entry.root.optJSONObject(block);
            if (obj == null) continue;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String val = obj.optString(key, "").trim();
                if (val.length() > 0 && looksLikeThemeBitmapRef(val)) {
                    getThemeBitmap(val);
                }
            }
        }
    }

    /** True for relative theme asset paths (not colours or http URLs). */
    static boolean looksLikeThemeBitmapRef(String ref) {
        if (ref.contains("://") || ref.startsWith("#")) return false;
        String lower = ref.toLowerCase(Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** Copy active theme to MMC, switch entry off SD, then drop SD mmap before export. */
    public static void prepareThemeForUsbStorage(Context ctx) {
        if (ctx == null) return;
        assetContext = ctx.getApplicationContext();
        cacheActiveTheme(ctx);
        preferInternalCacheForActiveTheme(ctx);
        ensureActiveThemeOrFallback(ctx);
        blockSdcardThemeAssets = true;
        releaseSdcardFileHandles();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            ThemeEntry t = getCurrentTheme();
            d.put("folderPath", t != null ? t.folderPath : "");
            d.put("folderName", t != null ? t.folderName : "");
            com.solar.launcher.DebugSessionLog.log(
                    "ThemeManager.prepareThemeForUsbStorage", "mmc cache ready", "H-THEME-MMC", d);
        } catch (Exception ignored) {}
        // #endregion
        warmOverlayThemeCache(ctx);
    }

    public static void setBlockSdcardThemeAssets(boolean block) {
        setBlockSdcardThemeAssets(block, assetContext);
    }

    public static void setBlockSdcardThemeAssets(boolean block, Context ctx) {
        if (block) {
            if (ctx != null) {
                prepareThemeForUsbStorage(ctx);
            } else {
                blockSdcardThemeAssets = true;
                releaseSdcardFileHandles();
            }
            return;
        }
        blockSdcardThemeAssets = false;
        releaseSdcardFileHandles();
    }

    public static boolean isBlockSdcardThemeAssets() {
        return blockSdcardThemeAssets;
    }

    /**
     * 2026-07-15 — Extract bundled Aura → themes root; refresh cover/Music after asset fixes.
     * Was: only fill missing/empty files (stale Spotify cover_YS.png stayed forever).
     * Now: always overwrite known Aura gallery/preview files from APK assets on seed.
     * Reversal: remove refreshBundledAuraShowcaseAssets; keep copy-missing-only behaviour.
     */
    public static void ensureBundledDefault(Context ctx) {
        assetContext = ctx.getApplicationContext();
        ensureThemesRootReady(ctx);
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
            // Push fresh cover + Music even when Default already existed (OTA / first-boot seed).
            refreshBundledAuraShowcaseAssets(ctx, dest);
        } catch (Exception ignored) {}
        ensureBundledFallback(ctx);
    }

    /**
     * 2026-07-15 — Overwrite Aura gallery cover + Music icon from bundled assets.
     * Was: leave existing cover_YS.png on disk (Spotify placeholder / stale Music palette PNG).
     * Now: always copy APK copies so Theme gallery + home preview match Solar branding.
     * Reversal: delete method + call; disk files stop updating on seed.
     */
    private static void refreshBundledAuraShowcaseAssets(Context ctx, File destDir) {
        if (ctx == null || destDir == null) return;
        // cover = theme gallery thumbnail; Music = home right-pane for Music row.
        String[] names = {"cover_YS.png", "Music_YS.png"};
        for (String name : names) {
            try {
                File out = new File(destDir, name);
                copyAsset(ctx.getAssets(), BUNDLED_ASSET_DIR + "/" + name, out);
            } catch (Exception ignored) {}
        }
        // Drop cached bitmaps so next paint reloads the replaced files.
        bitmapCache.clear();
        scaledRowBitmapCache.clear();
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

    /** Point active theme at internal cache so assets load when SD is USB-exported. */
    private static void switchThemeEntryToInternal(Context ctx, ThemeEntry active, File cacheDir) {
        File cachedCfg = new File(cacheDir, "config.json");
        if (!cachedCfg.isFile() || cachedCfg.length() == 0) return;
        try {
            byte[] data = readAll(cachedCfg);
            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            ThemeEntry cached = new ThemeEntry(
                    cacheDir.getAbsolutePath(), active.folderName, active.name, json);
            int idx = currentThemeIndex;
            if (idx >= 0 && idx < availableThemes.size()) {
                availableThemes.set(idx, cached);
            }
        } catch (Exception ignored) {}
    }

    private static void copyDirectory(File sourceLocation, File targetLocation) throws Exception {
        if (sourceLocation.isDirectory()) {
            if (!targetLocation.exists()) {
                targetLocation.mkdirs();
            }
            String[] children = sourceLocation.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(sourceLocation, child), new File(targetLocation, child));
                }
            }
        } else {
            InputStream in = new java.io.FileInputStream(sourceLocation);
            if (targetLocation.isFile()
                    && targetLocation.length() == sourceLocation.length()
                    && targetLocation.lastModified() >= sourceLocation.lastModified()) {
                in.close();
                return;
            }
            OutputStream out = new java.io.FileOutputStream(targetLocation);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
        }
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
        // Mkdir store + async mirror; never block scan on full tree copy.
        if (ctx != null) {
            ensureThemesRootReady(ctx);
        } else if (assetContext != null) {
            ensureThemesRootReady(assetContext);
        } else {
            themesRootPath = resolveThemesRoot(null);
        }
        String prevFolder = availableThemes.isEmpty() ? null
                : availableThemes.get(Math.min(currentThemeIndex, availableThemes.size() - 1)).folderName;
        availableThemes.clear();
        bitmapCache.clear();
        scaledRowBitmapCache.clear();
        cachedFont = null;
        cachedFontKey = "";
        auraFont = null;
        availableThemes.addAll(scanDiscoveredThemes());
        if (prevFolder != null) {
            for (int i = 0; i < availableThemes.size(); i++) {
                if (prevFolder.equalsIgnoreCase(availableThemes.get(i).folderName)) {
                    currentThemeIndex = i;
                    return;
                }
            }
        }
        if (currentThemeIndex >= availableThemes.size()) currentThemeIndex = 0;
    }

    /** Rescan theme folders; clears stale bitmap cache so home icons match the active theme. */
    public static void rescanInstalled(Context ctx) {
        bitmapCache.clear();
        if (ctx != null) {
            ensureThemesRootReady(ctx);
        } else {
            themesRootPath = resolveThemesRoot(null);
        }
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

    /**
     * Discover themes: scan MMC store first (load paths), then MicroSD only for names
     * not already on the store (should be empty after a successful 1:1 sync).
     */
    private static List<ThemeEntry> scanDiscoveredThemes() {
        List<ThemeEntry> out = new ArrayList<ThemeEntry>();
        Set<String> seen = new HashSet<String>();
        File storeRoot = new File(themesRootPath);
        File defaultDir = new File(storeRoot, BUILTIN_DEFAULT_FOLDER);
        ThemeEntry def = parseFolder(defaultDir);
        if (def == null && bundledFallback != null) {
            def = bundledFallback;
        }
        if (def != null) {
            out.add(def);
            seen.add(BUILTIN_DEFAULT_FOLDER.toLowerCase(Locale.US));
        }
        // 1) Canonical MMC store — primary load paths
        scanRoot(storeRoot, seen, out);
        // 2) filesDir UMS cache (same names skipped)
        if (assetContext != null) {
            File filesThemes = internalThemesDir(assetContext);
            if (filesThemes != null && !samePath(storeRoot, filesThemes)) {
                scanRoot(filesThemes, seen, out);
            }
        }
        // 3) MicroSD — only themes not yet on MMC (fail-open if sync could not write store)
        File micro = com.solar.launcher.DeviceFeatures.getMicroSdThemesDir();
        if (micro != null && !samePath(storeRoot, micro)) {
            scanRoot(micro, seen, out);
        }
        // 4) Any other volume Themes/ (A5 dual paths, etc.)
        for (File themeRoot : com.solar.launcher.DeviceFeatures.getThemeRoots()) {
            if (themeRoot == null) continue;
            if (samePath(storeRoot, themeRoot)) continue;
            if (micro != null && samePath(micro, themeRoot)) continue;
            scanRoot(themeRoot, seen, out);
        }
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
            auraFont = null;
        } else {
            currentThemeIndex = 0;
        }
    }

    /**
     * 2026-07-11 — Push solarConfig enable/disable/set* keys into SOLAR_SETTINGS.
     * Call after theme switch so Match NP / Backdrop / etc. follow the theme author.
     * Direct keys like settingsShow_Now_Playing_Info are read at runtime (not written here).
     * 2026-07-18 — Menu Item Padding is runtime solarConfig only (not sticky); clear legacy global.
     * Reversal: stop calling; prefs stay user-only.
     */
    public static void applySolarConfigPrefs(Context ctx) {
        if (ctx == null) return;
        // 2026-07-18 — Theme change: drop bare menu_item_padding so gutter reverts to default/theme.
        clearLegacyGlobalMenuItemPaddingPref(ctx);
        JSONObject solar = solarBlock();
        if (solar == null) return;
        Map<String, Object> overrides = new HashMap<String, Object>();
        java.util.Iterator<String> keys = solar.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k == null) continue;
            // Only SettingLookup prefixes — skip asset paths and settings* direct bools.
            if (k.regionMatches(true, 0, "enable", 0, 6)
                    || k.regionMatches(true, 0, "disable", 0, 7)
                    || k.regionMatches(true, 0, "set", 0, 3)) {
                overrides.put(k, solar.opt(k));
            }
        }
        SettingLookup.applySolarConfigOverrides(ctx, overrides);
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
        return persistPathForTheme(theme, null);
    }

    public static String persistPathForTheme(ThemeEntry theme, Context ctx) {
        if (theme == null) return "";
        if (isBuiltInDefault(theme)) {
            return new File(themesRootPath, BUILTIN_DEFAULT_FOLDER).getAbsolutePath();
        }
        if (ctx != null && theme.folderName != null) {
            File cachedDir = new File(internalThemesDir(ctx), theme.folderName);
            File cachedCfg = new File(cachedDir, "config.json");
            if (cachedCfg.isFile() && cachedCfg.length() > 0) {
                return cachedDir.getAbsolutePath();
            }
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
            // ponytail: SD card may be unmounted (USB storage mode). Try internal cache
            // before falling back to the builtin default theme.
            if (ctx != null && cur.folderName != null) {
                File cachedDir = new File(internalThemesDir(ctx), cur.folderName);
                File cachedCfg = new File(cachedDir, "config.json");
                if (cachedCfg.isFile() && cachedCfg.length() > 0) {
                    // Switch to cached copy — same theme, internal storage path
                    try {
                        byte[] data = readAll(cachedCfg);
                        JSONObject json = new JSONObject(new String(data, "UTF-8"));
                        ThemeEntry cached = new ThemeEntry(
                                cachedDir.getAbsolutePath(), cur.folderName, cur.name, json);
                        // Replace in available list or add it
                        int idx = currentThemeIndex;
                        if (idx >= 0 && idx < availableThemes.size()) {
                            availableThemes.set(idx, cached);
                        } else {
                            availableThemes.add(cached);
                            currentThemeIndex = availableThemes.size() - 1;
                        }
                        return;
                    } catch (Exception ignored) {}
                }
            }
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
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getTextColorPrimary();
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
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getTextColorSecondary();
        // ponytail: artist/subtitle/hint lines follow unselected list text — not dialog white on light themes
        return getItemTextColorNormal();
    }

    public static int getOverlayBackgroundColor() {
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getOverlayBackgroundColor();
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuBackgroundColor");
            if (c != Integer.MIN_VALUE) return withAlpha(c, 0x88);
        }
        return 0x88000000;
    }

    /** Solid home-menu column fill — never stretch menuItemBackground (paints stock row labels). */
    public static int getMenuPanelSolidColor() {
        JSONObject menu = getCurrentTheme().root.optJSONObject("menuConfig");
        if (menu != null) {
            int c = parseColorOpt(menu, "menuBackgroundColor");
            if (c != Integer.MIN_VALUE) return c | 0xFF000000;
        }
        return 0xFF000000;
    }

    public static void setStatusBarMatchItemText(boolean match) {
        statusBarMatchItemText = match;
    }

    public static boolean isStatusBarMatchItemText() {
        return statusBarMatchItemText;
    }

    public static void setNowPlayingMatchItemText(boolean match) {
        nowPlayingMatchItemText = match;
    }

    public static boolean isNowPlayingMatchItemText() {
        return nowPlayingMatchItemText;
    }

    public static void setNowPlayingBackdropEnabled(boolean enabled) {
        nowPlayingBackdropEnabled = enabled;
    }

    public static boolean isNowPlayingBackdropEnabled() {
        return nowPlayingBackdropEnabled;
    }

    public static void setNowPlayingLcdArtEnabled(boolean enabled) {
        nowPlayingLcdArtEnabled = enabled;
    }

    public static boolean isNowPlayingLcdArtEnabled() {
        return nowPlayingLcdArtEnabled;
    }

    /** solarConfig.enableLCD_album_art — theme default; null when unset or both album-art keys set. */
    public static final String SOLAR_LCD_ALBUM_ART = "enableLCD_album_art";
    public static final String SOLAR_3D_ALBUM_ART = "enableNowPlaying3d_album_art";
    /** Theme string override — "Flat" or "3D" (default); syncs now_playing_3d_album_art pref. */
    public static final String SOLAR_ARTWORK_PERSPECTIVE = "setArtwork_Perspective";

    /** When theme sets both LCD and 3D keys, ignore theme and follow user prefs only. */
    public static boolean themeSetsBothAlbumArtKeys() {
        return hasThemeSolarConfigKey(SOLAR_LCD_ALBUM_ART)
                && hasThemeSolarConfigKey(SOLAR_3D_ALBUM_ART);
    }

    /** Theme default for LCD album art; null = leave user pref. */
    public static Boolean themeDefaultLcdAlbumArt() {
        if (themeSetsBothAlbumArtKeys()) return null;
        JSONObject solar = solarBlock();
        if (solar == null || !hasThemeSolarConfigKey(SOLAR_LCD_ALBUM_ART)) return null;
        return parseSolarBool(solar, SOLAR_LCD_ALBUM_ART);
    }

    /** Theme default for 3D album art; null = leave user pref. */
    public static Boolean themeDefault3dAlbumArt() {
        if (themeSetsBothAlbumArtKeys()) return null;
        JSONObject solar = solarBlock();
        if (solar == null || !hasThemeSolarConfigKey(SOLAR_3D_ALBUM_ART)) return null;
        return parseSolarBool(solar, SOLAR_3D_ALBUM_ART);
    }

  private static boolean parseSolarBool(JSONObject solar, String key) {
        if (solar == null || key == null) return false;
        String raw = solar.optString(key, "").trim();
        if (!raw.isEmpty()) {
            return "true".equalsIgnoreCase(raw) || "1".equals(raw);
        }
        return solar.optBoolean(key, false);
    }

    /**
     * 2026-07-18 — Bool parse that accepts true/false/1/0/on/off/yes/no (menu padding & theme flags).
     * Layman: theme authors can write “on” or true either way.
     */
    private static boolean parseSolarBoolOnOff(JSONObject solar, String key) {
        if (solar == null || key == null) return false;
        Object rawObj = solar.opt(key);
        if (rawObj instanceof Boolean) {
            return ((Boolean) rawObj).booleanValue();
        }
        String raw = solar.optString(key, "").trim();
        if (raw.isEmpty()) return solar.optBoolean(key, false);
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw)
                || "on".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw)
                || "off".equalsIgnoreCase(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        return solar.optBoolean(key, false);
    }

    /** Tint for LCD album-art filter — same source as Now Playing match text. */
    public static int getAlbumArtTintColor() {
        return getNowPlayingTextColor();
    }

    /** ponytail: statusConfig.statusBarColor with alpha; dark fallback when unset on non-Y1 themes */
    public static int getStatusBarBackgroundColor() {
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getStatusBarBackgroundColor();
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

    /** Passive hints, tooltips, helper lines — unselected item text (not status bar white on light themes). */
    public static int getHintTextColor() {
        return getItemTextColorNormal();
    }

    /** Second lines on list rows — artist, preview, Reach subtitles. */
    public static int getSubtitleTextColor() {
        return getItemTextColorNormal();
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
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getListButtonNormalBg();
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
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getListButtonFocusedBg();
        return getRowSelectionFillColor();
    }

    public static int getListButtonFocusedTextColor() {
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getListButtonFocusedTextColor();
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
        if (nowPlayingMatchItemText) {
            return getItemTextColorNormal();
        }
        if (getNowPlayingInfoBarMode() == NOW_PLAYING_BARS_ITEM) return getItemTextColorSelected();
        return 0xFFFFFFFF;
    }

    public static int getProgressTextColor() {
        JSONObject player = getCurrentTheme().root.optJSONObject("playerConfig");
        if (player != null) {
            int c = parseColorOpt(player, "progressTextColor");
            if (c != Integer.MIN_VALUE) return c;
        }
        if (nowPlayingMatchItemText) {
            return getItemTextColorNormal();
        }
        Integer np = getNowPlayingTextColorOpt();
        if (np != null) return np;
        return 0xFFFFFFFF;
    }

    public static Drawable getNowPlayingInfoBarBackground(android.content.res.Resources res, int widthPx, int heightPx) {
        if (!nowPlayingBackdropEnabled) {
            return new android.graphics.drawable.ColorDrawable(0);
        }
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

    /**
     * 2026-07-11 — Page/wallpaper bg for clash checks (About, donation, USB body copy).
     * Samples global wallpaper centre; falls back to inverse of primary text luminance.
     * Reversal: hardcode panel colour at each call site.
     */
    public static int sampleThemePageBackground() {
        try {
            Bitmap wall = getWallpaper(false);
            if (wall == null || wall.isRecycled()) wall = getWallpaper(true);
            if (wall != null && !wall.isRecycled() && wall.getWidth() > 0 && wall.getHeight() > 0) {
                return wall.getPixel(wall.getWidth() / 2, wall.getHeight() / 2) | 0xFF000000;
            }
        } catch (Exception ignored) {}
        return relativeLuminance(getTextColorPrimary()) > 0.45 ? 0xFF333333 : 0xFFCCCCCC;
    }

    /**
     * 2026-07-11 — Theme fill + auto-invert when it clashes with {@code backgroundColor}.
     * Use for body copy on wallpaper / panels (About, USB prompts, donation); keep
     * {@link #applyThemedTextStyle(TextView, int)} for selection rows that already own contrast.
     */
    public static void applyReadableThemedTextStyle(TextView tv, int fillColor, int backgroundColor) {
        applyThemedTextStyle(tv, ensureReadableOnBackground(fillColor, backgroundColor));
    }

    /** Same as {@link #applyReadableThemedTextStyle(TextView, int, int)} against page wallpaper. */
    public static void applyReadableThemedTextStyle(TextView tv, int fillColor) {
        applyReadableThemedTextStyle(tv, fillColor, sampleThemePageBackground());
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
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getButtonRadius();
        ThemeEntry t = getCurrentTheme();
        JSONObject solar = solarBlock(t.root);
        if (solar != null && solar.has("button_radius")) return solar.optInt("button_radius", 10);
        if (t.root.has("button_radius")) return t.root.optInt("button_radius", 10);
        return 10;
    }

    /** Rounded panel + external stroke — shared by context menu and video transport overlay. */
    public static android.graphics.drawable.Drawable buildContextMenuPanelDrawable(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        float r = getButtonRadius() * 2f * density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xE0202022);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, (int) density), 0x66FFFFFF);
        return g;
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

    /**
     * 2026-07-11 — solarConfig settingsMenu_Item_Padding / enableMenu_Item_Padding:
     * false/off = flush menu chrome; true/on = keep left gutter.
     * 2026-07-18 — Default ON when unset. Theme change re-reads solarConfig (no sticky global pref).
     * Forms: settingsMenu_Item_Padding, settingMenu_Item_Padding (alias),
     * enable|disable|set Menu_Item_Padding (English label → SettingLookup).
     * Per-theme user override only: SharedPreferences {@code menu_item_padding.<folder>}.
     * Masks force pad when theme did not author a value (user can still override).
     * Reversal: drop keys + prefs prefix; always use {@code y1_menu_left} + stock heights.
     */
    public static final String SOLAR_MENU_ITEM_PADDING = "settingsMenu_Item_Padding";
    /** Typo / short alias authors may use instead of {@link #SOLAR_MENU_ITEM_PADDING}. */
    public static final String SOLAR_MENU_ITEM_PADDING_ALIAS = "settingMenu_Item_Padding";
    /**
     * 2026-07-11 — solarConfig.settingsShow_Now_Playing_Info: home right-pane title/artist under art.
     * Opt-in (default off). Menu label "Show Now Playing Info" → this key.
     * Reversal: drop key; always hide titles unless user pref set.
     */
    public static final String SOLAR_SHOW_NOW_PLAYING_INFO = "settingsShow_Now_Playing_Info";
    /** Global user override for home NP title/artist under preview art. */
    public static final String PREF_SHOW_NOW_PLAYING_INFO = "show_now_playing_info";
    /** Pref prefix; suffix is active theme folderName. true = keep gutter, false = flush left. */
    public static final String PREF_MENU_ITEM_PADDING_PREFIX = "menu_item_padding.";
    /**
     * 2026-07-18 — Legacy bare key written by old SettingLookup enable/disableMenu_Item_Padding.
     * Never use for runtime; cleared on theme apply so theme switches revert to default.
     */
    public static final String PREF_MENU_ITEM_PADDING_LEGACY_GLOBAL = "menu_item_padding";

    /** Theme declares a home desktopMask path (may still fail to decode). */
    public static boolean themeDeclaresDesktopMask() {
        // 2026-07-11 / 2026-07-14 — Tall chrome (A5 or Y1/Y2 portrait) never uses dual-pane masks.
        // Was: isA5 ∧ forcePortraitThemeRules — Y1 portrait still declared masks. Reversal: restore isA5().
        if (assetContext != null && A5NavigationMode.forcePortraitThemeRules(assetContext)) {
            return false;
        }
        ThemeEntry t = getCurrentTheme();
        return t != null && t.root != null
                && !t.root.optString("desktopMask", "").trim().isEmpty();
    }

    /** Theme declares settingConfig.settingMask (settings dual-pane frame). */
    public static boolean themeDeclaresSettingMask() {
        // 2026-07-14 — Same tall-chrome gate as desktopMask (Y1/Y2 portrait parity).
        if (assetContext != null && A5NavigationMode.forcePortraitThemeRules(assetContext)) {
            return false;
        }
        ThemeEntry t = getCurrentTheme();
        if (t == null || t.root == null) return false;
        JSONObject setting = t.root.optJSONObject("settingConfig");
        return setting != null && !setting.optString("settingMask", "").trim().isEmpty();
    }

    /** Pref key for this theme's menu-gutter override; null folder → {@code default}. */
    public static String menuItemPaddingPrefKey(String folderName) {
        String folder = (folderName == null || folderName.isEmpty()) ? "default" : folderName;
        return PREF_MENU_ITEM_PADDING_PREFIX + folder;
    }

    /**
     * Theme-authored default for left menu gutter (runtime-only — not a sticky pref).
     * {@code false} = flush; {@code true} = pad; null = unset → effective default ON.
     * 2026-07-18 — Reads settings* bools and enable/disable/set Menu_Item_Padding label forms.
     * Layman: theme can say “pad” or “flush”; if silent, Solar pads.
     * Technical: no SharedPreferences write; theme change re-evaluates solarConfig of new theme.
     */
    public static Boolean themeDefaultMenuItemPadding() {
        JSONObject solar = solarBlock();
        if (solar == null) return null;
        // Direct settings* keys (bool / "true" / "on" / "false" / "off").
        if (solar.has(SOLAR_MENU_ITEM_PADDING)) {
            return Boolean.valueOf(parseSolarBoolOnOff(solar, SOLAR_MENU_ITEM_PADDING));
        }
        if (solar.has(SOLAR_MENU_ITEM_PADDING_ALIAS)) {
            return Boolean.valueOf(parseSolarBoolOnOff(solar, SOLAR_MENU_ITEM_PADDING_ALIAS));
        }
        // enableMenu_Item_Padding / disableMenu_Item_Padding / setMenu_Item_Padding
        java.util.Iterator<String> keys = solar.keys();
        while (keys.hasNext()) {
            String rawKey = keys.next();
            if (rawKey == null) continue;
            String operation = null;
            String remainder = rawKey;
            if (rawKey.length() > 6 && rawKey.regionMatches(true, 0, "enable", 0, 6)) {
                operation = "enable";
                remainder = rawKey.substring(6);
            } else if (rawKey.length() > 7 && rawKey.regionMatches(true, 0, "disable", 0, 7)) {
                operation = "disable";
                remainder = rawKey.substring(7);
            } else if (rawKey.length() > 3 && rawKey.regionMatches(true, 0, "set", 0, 3)) {
                operation = "set";
                remainder = rawKey.substring(3);
            } else {
                continue;
            }
            String label = remainder.replace('_', ' ');
            if (!"menu_item_padding".equals(SettingLookup.prefKeyForLabel(label))) continue;
            if ("enable".equals(operation)) return Boolean.TRUE;
            if ("disable".equals(operation)) return Boolean.FALSE;
            // setMenu_Item_Padding: value true/false/on/off
            return Boolean.valueOf(parseSolarBoolOnOff(solar, rawKey));
        }
        return null;
    }

    /**
     * Effective left gutter for home or settings list host.
     * Order: per-theme user Appearance override → theme solarConfig → default ON.
     * 2026-07-18 — Theme change does not carry prior theme gutter; only this theme’s override key.
     * Layman: new theme starts padded unless the author or you chose flush for that look.
     */
    public static boolean isMenuItemPaddingEnabled(android.content.SharedPreferences prefs,
            boolean forSettings) {
        ThemeEntry t = getCurrentTheme();
        String folder = t != null ? t.folderName : "";
        String key = menuItemPaddingPrefKey(folder);
        // User Appearance toggle for this theme folder only.
        if (prefs != null && prefs.contains(key)) {
            return prefs.getBoolean(key, true);
        }
        Boolean themeDef = themeDefaultMenuItemPadding();
        if (themeDef != null) {
            return themeDef.booleanValue();
        }
        // 2026-07-11 / 2026-07-18 — Unset → pad (masks also need gutter; default ON either way).
        return true;
    }

    /**
     * True when Appearance cannot flush because a mask is present and the theme did not
     * author a pad value (and the user has not overridden).
     */
    public static boolean menuItemPaddingForcedByMask(boolean forSettings) {
        if (themeDefaultMenuItemPadding() != null) return false;
        return forSettings ? themeDeclaresSettingMask() : themeDeclaresDesktopMask();
    }

    /**
     * Save user choice for the active theme folder only.
     * Layman: “remember my padding pick for this look.” Other themes keep their own.
     */
    public static void setMenuItemPaddingOverride(android.content.SharedPreferences prefs,
            boolean paddingEnabled) {
        if (prefs == null) return;
        ThemeEntry t = getCurrentTheme();
        String folder = t != null ? t.folderName : "";
        prefs.edit().putBoolean(menuItemPaddingPrefKey(folder), paddingEnabled).commit();
    }

    /**
     * 2026-07-18 — Drop legacy global menu_item_padding so theme switches start from default/theme.
     * Layman: changing theme forgets a sticky old on/off that was not tied to one look.
     * Technical: remove bare key; keep menu_item_padding.&lt;folder&gt; user overrides.
     */
    public static void clearLegacyGlobalMenuItemPaddingPref(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.content.SharedPreferences prefs =
                    ctx.getApplicationContext().getSharedPreferences("SOLAR_SETTINGS",
                            android.content.Context.MODE_PRIVATE);
            if (prefs.contains(PREF_MENU_ITEM_PADDING_LEGACY_GLOBAL)) {
                prefs.edit().remove(PREF_MENU_ITEM_PADDING_LEGACY_GLOBAL).apply();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 2026-07-11 — Theme default for home NP title/artist under preview art.
     * {@code true} = show; {@code false}/missing = hide (opt-in).
     */
    public static Boolean themeDefaultShowNowPlayingInfo() {
        JSONObject solar = solarBlock();
        if (solar == null || !solar.has(SOLAR_SHOW_NOW_PLAYING_INFO)) return null;
        return parseSolarBool(solar, SOLAR_SHOW_NOW_PLAYING_INFO);
    }

    /**
     * Effective home right-pane NP title/artist visibility.
     * Order: user pref → solarConfig.settingsShow_Now_Playing_Info → false (opt-in).
     */
    public static boolean isShowNowPlayingInfoEnabled(android.content.SharedPreferences prefs) {
        if (prefs != null && prefs.contains(PREF_SHOW_NOW_PLAYING_INFO)) {
            return prefs.getBoolean(PREF_SHOW_NOW_PLAYING_INFO, false);
        }
        Boolean themeDef = themeDefaultShowNowPlayingInfo();
        if (themeDef != null) return themeDef.booleanValue();
        return false;
    }

    /** Persist Appearance → Show Now Playing Info. */
    public static void setShowNowPlayingInfoEnabled(android.content.SharedPreferences prefs,
            boolean enabled) {
        if (prefs == null) return;
        prefs.edit().putBoolean(PREF_SHOW_NOW_PLAYING_INFO, enabled).commit();
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

    private static Typeface auraFont = null;

    public static Typeface getThemeFont(ThemeEntry t) {
        if (t == null) return Typeface.DEFAULT;
        String fontKey = t.folderPath;
        String fontFile = t.root.optString("fontFamily", "");
        if (fontFile.isEmpty()) return Typeface.DEFAULT;
        String cacheKey = fontKey + ":" + fontFile;
        if (cachedFont != null && cacheKey.equals(cachedFontKey)) return cachedFont;
        File f = new File(t.folderPath, fontFile);
        if (!f.isFile() && assetContext != null && t.folderName != null) {
            File mmc = new File(internalThemesDir(assetContext), t.folderName + "/" + fontFile);
            if (mmc.isFile()) f = mmc;
        }
        if (shouldSkipExternalThemeFile(f.getAbsolutePath())) return Typeface.DEFAULT;
        if (f.isFile()) {
            try {
                return Typeface.createFromFile(f);
            } catch (Exception ignored) {}
        }
        return Typeface.DEFAULT;
    }

    public static Typeface getCustomFont() {
        if (ActiveThemeEngine.isJjMode()) return JjThemeManager.getCustomFont();
        ThemeEntry t = getCurrentTheme();
        // ponytail: cache both custom AND default results to avoid repeated JSON/file lookups
        String key = t.folderPath + ":" + t.root.optString("fontFamily", "");
        if (key.equals(cachedFontKey) && cachedFont != null) return cachedFont;
        Typeface tf = getThemeFont(t);
        cachedFont = tf;
        cachedFontKey = key;
        return tf;
    }

    public static Typeface getAuraThemeFont() {
        if (auraFont != null) return auraFont;
        ThemeEntry aura = null;
        for (ThemeEntry t : availableThemes) {
            if (isBuiltInDefault(t)) {
                aura = t;
                break;
            }
        }
        if (aura == null && bundledFallback != null) {
            aura = bundledFallback;
        }
        if (aura == null) {
            try {
                JSONObject stub = new JSONObject();
                stub.put("homePageConfig", new JSONObject());
                aura = new ThemeEntry("", BUILTIN_DEFAULT_FOLDER, "Aura", stub);
            } catch (Exception ignored) {}
        }
        auraFont = getThemeFont(aura);
        return auraFont;
    }

    public static Typeface getFlowFont(boolean isDebugTheme) {
        if (isDebugTheme) {
            return getCustomFont();
        }
        return getAuraThemeFont();
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
    /** 2026-07-05: @1x settingConfig icons — cap decode so Y2 never upscales blocky rasters in preview pane. */
    private static final int SETTING_CONFIG_ICON_MAX_PX = 146;

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
        // #region agent log
        String via = "none";
        String diskPath = "";
        // #endregion
        if (folderPath != null && folderPath.startsWith("asset://")) {
            bmp = decodeBundledThemeAsset(relativePath, maxSide);
            // #region agent log
            via = bmp != null ? "asset" : "asset_miss";
            // #endregion
        } else {
            File f = resolveThemeAssetFile(folderPath, relativePath);
            if (f != null && f.isFile()) {
                // #region agent log
                diskPath = f.getAbsolutePath();
                // #endregion
                bmp = maxSide > 0
                        ? decodeBitmapFileMaxSide(f.getAbsolutePath(), maxSide)
                        : decodeBitmapFile(f.getAbsolutePath());
                // #region agent log
                via = bmp != null ? "disk" : "disk_decode_fail";
                // #endregion
            } else {
                // #region agent log
                via = "disk_miss";
                // #endregion
            }
        }
        if (bmp == null && isBuiltInDefault(entry)) {
            bmp = decodeBundledThemeAsset(relativePath, maxSide);
            // #region agent log
            if (bmp != null) via = via + "+bundled";
            else via = via + "+bundled_miss";
            // #endregion
        }
        // #region agent log
        try {
            if (relativePath != null && (relativePath.contains("Music")
                    || relativePath.contains("Videos")
                    || relativePath.contains("Photos")
                    || relativePath.contains("Settings")
                    || relativePath.contains("Now Playing")
                    || relativePath.contains("FM")
                    || relativePath.contains("Bluetooth")
                    || relativePath.contains("Audiobooks"))) {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("rel", relativePath);
                d.put("via", via);
                d.put("diskPath", diskPath);
                d.put("folder", entry.folderName);
                d.put("folderPath", folderPath);
                d.put("ok", bmp != null && !bmp.isRecycled());
                d.put("assetCtx", assetContext != null);
                d.put("fileLen", diskPath.length() > 0 ? new File(diskPath).length() : -1L);
                com.solar.launcher.Debug6a626eLog.log(
                        "ThemeManager.decodeThemeBitmapForEntry", "decode", "B,C,E", d);
            }
        } catch (Exception ignored) {}
        // #endregion
        return bmp;
    }

    public static Bitmap getThemeBitmap(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        ThemeEntry t = getCurrentTheme();
        String cacheKey = (t != null ? t.folderPath : "") + ":" + relativePath;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        if (t != null && shouldSkipExternalThemeFile(t.folderPath)) {
            preferInternalCacheForActiveTheme(assetContext);
            t = getCurrentTheme();
            cacheKey = (t != null ? t.folderPath : "") + ":" + relativePath;
            if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        }
        if (t == null) return null;
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
            if (shouldSkipExternalThemeFile(iconFile.getAbsolutePath())) {
                return BitmapFactory.decodeResource(context.getResources(), defaultResId);
            }
            if (iconFile.isFile()) {
                try {
                    return BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                } catch (Exception ignored) {}
            }
        }
        return BitmapFactory.decodeResource(context.getResources(), defaultResId);
    }

    /**
     * 2026-07-15 — Home shortcut icon from the active theme only.
     * Was: solarConfig.app* → homePageConfig → null (cold stub showed no Music / wrong preview).
     * Now: same chain, then bundled Aura Music_YS when Default theme still lacks a decode.
     * Reversal: remove bundled Music_YS arm; null again when disk/asset miss.
     */
    public static Bitmap getHomeMenuIcon(Context context, HomeMenuConfig.Entry entry) {
        if (context == null || entry == null) return null;
        // #region agent log
        String arm = "start";
        // #endregion
        if (HomeMenuConfig.ID_THEMES.equals(entry.id)
                || HomeMenuConfig.ID_GET_THEMES.equals(entry.id)) {
            Bitmap themes = getSolarAppIcon("Themes");
            if (themes != null) {
                // #region agent log
                arm = "themes_solar";
                debugHomeIcon(entry, arm, themes);
                // #endregion
                return themes;
            }
            Bitmap setting = getThemeSettingBitmap("theme");
            // #region agent log
            arm = "themes_setting";
            debugHomeIcon(entry, arm, setting);
            // #endregion
            return setting;
        }
        String enLabel = entry.englishLabel(context);
        Bitmap solar = getSolarAppIcon(enLabel);
        if (solar != null) {
            // #region agent log
            arm = "solar_en";
            debugHomeIcon(entry, arm, solar);
            // #endregion
            return solar;
        }
        if (entry.solarAppName != null && !entry.solarAppName.equals(enLabel)) {
            solar = getSolarAppIcon(entry.solarAppName);
            if (solar != null) {
                // #region agent log
                arm = "solar_app";
                debugHomeIcon(entry, arm, solar);
                // #endregion
                return solar;
            }
        }
        if (entry.stockIconKey != null) {
            Bitmap stock = getThemeHomeBitmap(entry.stockIconKey);
            if (stock != null) {
                // #region agent log
                arm = "stock_" + entry.stockIconKey;
                debugHomeIcon(entry, arm, stock);
                // #endregion
                return stock;
            }
        }
        String fallbackKey = HomeMenuConfig.y1HomeIconFallbackKey(entry.id);
        if (fallbackKey != null) {
            Bitmap fb = getThemeHomeBitmap(fallbackKey);
            if (fb != null) {
                // #region agent log
                arm = "fb_" + fallbackKey;
                debugHomeIcon(entry, arm, fb);
                // #endregion
                return fb;
            }
        }
        // Cold-start / partial seed: never leave Music on stock drawable placeholder.
        if (HomeMenuConfig.ID_MUSIC.equals(entry.id) && isBuiltInDefault(getCurrentTheme())) {
            Bitmap bundled = decodeBundledThemeAsset("Music_YS.png", 0);
            // #region agent log
            arm = "bundled_music";
            debugHomeIcon(entry, arm, bundled);
            // #endregion
            return bundled;
        }
        // #region agent log
        arm = "null";
        debugHomeIcon(entry, arm, null);
        // #endregion
        return null;
    }

    /**
     * 2026-07-20 — Debug 6a626e: log home-icon resolution arm + active theme path.
     * Layman: writes which lookup found (or missed) the Music/Videos picture.
     * Reversal: delete with Debug6a626eLog after fix verified.
     */
    private static void debugHomeIcon(HomeMenuConfig.Entry entry, String arm, Bitmap bmp) {
        try {
            ThemeEntry t = getCurrentTheme();
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("entryId", entry != null ? entry.id : "");
            d.put("stockKey", entry != null ? entry.stockIconKey : "");
            d.put("arm", arm != null ? arm : "");
            d.put("ok", bmp != null && !bmp.isRecycled());
            d.put("w", bmp != null ? bmp.getWidth() : 0);
            d.put("h", bmp != null ? bmp.getHeight() : 0);
            d.put("folder", t != null ? t.folderName : "");
            d.put("folderPath", t != null ? t.folderPath : "");
            d.put("assetCtx", assetContext != null);
            d.put("themesN", availableThemes.size());
            d.put("blockSd", blockSdcardThemeAssets);
            JSONObject home = t != null && t.root != null
                    ? t.root.optJSONObject("homePageConfig") : null;
            d.put("homeKeys", home != null ? home.length() : -1);
            if (entry != null && entry.stockIconKey != null && home != null) {
                d.put("homePath", home.optString(entry.stockIconKey, ""));
            }
            com.solar.launcher.Debug6a626eLog.log(
                    "ThemeManager.getHomeMenuIcon", "icon resolve", "A,B,C", d);
        } catch (Exception ignored) {}
    }

    /** {@code homePageConfig} asset from active theme only; null when unset or missing file. */
    public static Bitmap getThemeHomeBitmap(String y1Key) {
        if (y1Key == null || y1Key.isEmpty()) return null;
        JSONObject home = getCurrentTheme().root.optJSONObject("homePageConfig");
        if (home == null) return null;
        String path = home.optString(y1Key, "").trim();
        if (path.isEmpty()) return null;
        return getThemeBitmapFromActiveThemeOnly(path);
    }

    /** {@code settingConfig} asset from active theme only. */
    public static Bitmap getThemeSettingBitmap(String key) {
        if (key == null || key.isEmpty()) return null;
        // ponytail: check cache before JSON parse — settings rows re-query on every scroll
        ThemeEntry t = getCurrentTheme();
        String cacheKey = t.folderPath + ":setting:" + key;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        JSONObject setting = t.root.optJSONObject("settingConfig");
        if (setting == null) return null;
        String path = setting.optString(key, "").trim();
        if (path.isEmpty()) return null;
        Bitmap bmp = decodeThemeBitmapForEntry(t, path, SETTING_CONFIG_ICON_MAX_PX);
        if (bmp != null) bitmapCache.put(cacheKey, bmp);
        return bmp;
    }

    /** Decode a theme asset path without bundled-default or Android drawable fallbacks. */
    static Bitmap getThemeBitmapFromActiveThemeOnly(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        ThemeEntry t = getCurrentTheme();
        String cacheKey = t.folderPath + ":only:" + relativePath;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        Bitmap bmp = decodeThemeBitmapForEntry(t, relativePath, 0);
        if (bmp != null) bitmapCache.put(cacheKey, bmp);
        return bmp;
    }

    /**
     * Settings right-pane icon: {@code solarConfig.settings*} (English label) → Reach extras →
     * {@code solarConfig.app*} → {@code settingConfig} → home shortcut preview.
     */
    public static Bitmap getSettingsRowIcon(Context context, String rowKey, String englishLabel,
            String soulseekSolarKey, String settingIconKey, String solarAppName) {
        Bitmap icon = getSolarSettingsIcon(englishLabel);
        if (icon == null && soulseekSolarKey != null) {
            icon = getSolarConfigIcon(soulseekSolarKey);
        }
        if (icon == null && solarAppName != null) {
            icon = getSolarAppIcon(solarAppName);
        }
        if (icon == null && settingIconKey != null) {
            icon = getThemeSettingBitmap(settingIconKey);
        }
        if (icon == null && rowKey != null && rowKey.startsWith("home.shortcut.")) {
            HomeMenuConfig.Entry e = HomeMenuConfig.find(
                    rowKey.substring("home.shortcut.".length()));
            if (e != null) icon = getHomeMenuIcon(context, e);
        }
        return icon;
    }

    /** solarConfig key for a Solar-only app label, e.g. Podcasts → appPodcasts, Get Music → appGet_Music */
    public static String solarAppConfigKey(String appName) {
        if (appName == null) return null;
        String suffix = appName.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (suffix.isEmpty()) return null;
        return "app" + suffix;
    }

    /** solarConfig key for a settings row label, e.g. About → settingsAbout */
    public static String solarSettingsConfigKey(String settingsLabel) {
        if (settingsLabel == null) return null;
        String suffix = settingsLabel.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (suffix.isEmpty()) return null;
        return "settings" + suffix;
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
        if (key == null || key.isEmpty()) return null;
        // ponytail: cache check first — icons are per-theme only, never merge bundled Aura solarConfig into third-party themes.
        ThemeEntry t = getCurrentTheme();
        String cacheKey = t.folderPath + ":solar:" + key;
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache.get(cacheKey);
        JSONObject solar = solarBlock(t.root);
        if (solar == null) return null;
        String path = solar.optString(key, "").trim();
        if (path.isEmpty()) return null;
        Bitmap bmp = null;
        File f = resolveThemeAssetFile(t.folderPath, path);
        if (f != null) {
            bmp = decodeBitmapFileMaxSide(f.getAbsolutePath(), SOLAR_CONFIG_ICON_MAX_PX);
        }
        if (bmp == null && isBuiltInDefault(t)) {
            bmp = decodeBundledThemeAsset(path, SOLAR_CONFIG_ICON_MAX_PX);
        }
        if (bmp != null) bitmapCache.put(cacheKey, bmp);
        return bmp;
    }

    /** solarConfig app{Name} — theme asset for Solar-only apps; null if unset */
    public static Bitmap getSolarAppIcon(String appName) {
        String key = solarAppConfigKey(appName);
        if (key == null) return null;
        Bitmap bmp = getSolarConfigIcon(key);
        if (bmp != null) return bmp;
        String legacy = solarAppConfigKeyLegacy(appName);
        if (legacy != null && !legacy.equals(key)) {
            return getSolarConfigIcon(legacy);
        }
        return null;
    }

    /** solarConfig settings{Name} — right-pane icon for settings rows; null if unset */
    public static Bitmap getSolarSettingsIcon(String settingsLabel) {
        String key = solarSettingsConfigKey(settingsLabel);
        return key != null ? getSolarConfigIcon(key) : null;
    }

    /**
     * @deprecated use {@link #getHomeMenuIcon(Context, HomeMenuConfig.Entry)} — theme assets only.
     */
    public static Bitmap getSolarAppHomeIcon(Context context, String appName, int defaultResId) {
        Bitmap solar = getSolarAppIcon(appName);
        if (solar != null) return solar;
        if ("Podcasts".equals(appName)) return getThemeHomeBitmap("audiobooks");
        return null;
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

    /**
     * 2026-07-15 — Theme gallery / installed-theme preview cover.
     * Was: disk-only decodeFile (asset:// Aura + missing SD → null / blank gallery).
     * Now: same decodeThemeBitmapForEntry path as home icons (asset + bundled fallback).
     * Reversal: restore resolveThemeAssetFile + decodeFile only.
     */
    public static Bitmap getThemeCover(ThemeEntry entry) {
        if (entry == null) return null;
        String path = entry.root.optString("themeCover", "").trim();
        if (path.isEmpty()) return null;
        return decodeThemeBitmapForEntry(entry, path, 0);
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
        if (cover.getHeight() <= heightPx) return cover;
        int w = (int) (cover.getWidth() * (heightPx / (float) cover.getHeight()));
        if (w < 1) w = 1;
        Bitmap scaled = Bitmap.createScaledBitmap(cover, w, heightPx, true);
        if (scaled != cover && !cover.isRecycled()) cover.recycle();
        return scaled;
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
        if (!batteryFramesEmpty(frames)) {
            String path = pickBatteryFrame(frames, levelIndex);
            if (path != null && !path.isEmpty()) {
                Bitmap bmp = resolveThemeBitmapLoose(path);
                if (bmp != null) return bmp;
            }
        }
        return getDefaultBatteryIcon(levelIndex, charging);
    }

    private static final java.util.Map<String, Bitmap> defaultBatteryCache = new java.util.concurrent.ConcurrentHashMap<String, Bitmap>();

    public static Bitmap getDefaultBatteryIcon(int levelIndex, boolean charging) {
        int idx = levelIndex;
        if (idx < 0) idx = 0;
        if (idx > 4) idx = 4;
        String key = (charging ? "c_" : "n_") + idx;
        Bitmap cached = defaultBatteryCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        String fileName = charging
                ? "icon_statusbar_battery_" + idx + "_c.png"
                : "icon_statusbar_battery_" + idx + ".png";
        Bitmap bmp = resolveThemeBitmapLoose(fileName);
        if (bmp == null) {
            String altName = charging
                    ? "batterycharge.00" + (idx + 1) + ".png"
                    : "battery.00" + (idx + 1) + ".png";
            bmp = resolveThemeBitmapLoose(altName);
        }
        if (bmp == null && assetContext != null) {
            try {
                java.io.InputStream is = assetContext.getAssets().open("themes/default/" + fileName);
                bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception ignored) {}
        }
        if (bmp == null && assetContext != null) {
            String altName = charging
                    ? "batterycharge.00" + (idx + 1) + ".png"
                    : "battery.00" + (idx + 1) + ".png";
            try {
                java.io.InputStream is = assetContext.getAssets().open("themes/default/" + altName);
                bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception ignored) {}
        }
        if (bmp != null) {
            defaultBatteryCache.put(key, bmp);
        }
        return bmp;
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
            if ((getSubtitleTextColor() & 0xFFFFFF) != 0xE4FC3F) throw new AssertionError("subtitleTextColor");
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
            if (!"appGet_Music".equals(solarAppConfigKey("Get Music"))) throw new AssertionError("appGet_Music key");
            if (!"appPC_Upload".equals(solarAppConfigKey("PC Upload"))) throw new AssertionError("appPC_Upload key");
            if (!"settingsAbout".equals(solarSettingsConfigKey("About"))) throw new AssertionError("settingsAbout key");
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
            // 2026-07-11 / 2026-07-18 — Menu gutter: unset=on; solarConfig false=flush; enable form works.
            if (themeDefaultMenuItemPadding() != null) throw new AssertionError("padding unset");
            if (!isMenuItemPaddingEnabled(null, false)) throw new AssertionError("default padding on");
            ipod.put("desktopMask", "@mask.png");
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (!themeDeclaresDesktopMask()) throw new AssertionError("desktopMask declared");
            if (!isMenuItemPaddingEnabled(null, false)) throw new AssertionError("mask keeps pad when unset");
            if (!menuItemPaddingForcedByMask(false)) throw new AssertionError("mask lock when unset");
            ipod.put("solarConfig", new JSONObject().put(SOLAR_MENU_ITEM_PADDING, false));
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (Boolean.TRUE.equals(themeDefaultMenuItemPadding())) throw new AssertionError("theme wants flush");
            if (isMenuItemPaddingEnabled(null, false)) throw new AssertionError("author false wins over mask");
            if (menuItemPaddingForcedByMask(false)) throw new AssertionError("no mask lock when author set");
            // enableMenu_Item_Padding / on alias / typo settingMenu_Item_Padding
            ipod.put("solarConfig", new JSONObject().put("enableMenu_Item_Padding", "on"));
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (!Boolean.TRUE.equals(themeDefaultMenuItemPadding())) {
                throw new AssertionError("enableMenu_Item_Padding on");
            }
            ipod.put("solarConfig", new JSONObject().put("disableMenu_Item_Padding", true));
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (isMenuItemPaddingEnabled(null, false)) throw new AssertionError("disableMenu_Item_Padding");
            ipod.put("solarConfig", new JSONObject().put(SOLAR_MENU_ITEM_PADDING_ALIAS, "true"));
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (!Boolean.TRUE.equals(themeDefaultMenuItemPadding())) {
                throw new AssertionError("settingMenu_Item_Padding alias");
            }
            ipod.remove("solarConfig");
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (themeDefaultMenuItemPadding() != null) throw new AssertionError("cleared solar → unset");
            if (!isMenuItemPaddingEnabled(null, false)) throw new AssertionError("default on after clear");
            if (!"menu_item_padding.Cupertino".equals(menuItemPaddingPrefKey("Cupertino"))) {
                throw new AssertionError("pref key folder");
            }
            // 2026-07-11 — settingsShow_Now_Playing_Info opt-in default.
            if (isShowNowPlayingInfoEnabled(null)) throw new AssertionError("NP info default off");
            ipod.put("solarConfig", new JSONObject().put(SOLAR_SHOW_NOW_PLAYING_INFO, true));
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
            if (!Boolean.TRUE.equals(themeDefaultShowNowPlayingInfo())) {
                throw new AssertionError("theme wants NP info");
            }
            if (!isShowNowPlayingInfoEnabled(null)) throw new AssertionError("theme enables NP info");
            ipod.remove("desktopMask");
            ipod.remove("solarConfig");
            availableThemes.set(0, new ThemeEntry("/tmp", "ipod", "ipod", ipod));
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
            // 2026-07-11 — Page clash cases used by applyReadableThemedTextStyle (About/USB/donation).
            int wow = ensureReadableOnBackground(0xFFFFFFFF, 0xFFFFFFF0);
            if (contrastRatio(wow, 0xFFFFFFF0) < 3.0) throw new AssertionError("white-on-white invert");
            int bod = ensureReadableOnBackground(0xFF101010, 0xFF181818);
            if (contrastRatio(bod, 0xFF181818) < 3.0) throw new AssertionError("black-on-dark invert");
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

    /**
     * Persists a user's overridden theme preference back to the active theme's config.json.
     * Only modifies the theme if the prefKey maps to a valid solarConfig label.
     */
    public static void writeSolarConfigOverride(Context ctx, String prefKey, Object newValue) {
        if (ctx == null || prefKey == null) return;
        
        String newKey = SettingLookup.getSolarConfigKeyForPref(prefKey, newValue);
        if (newKey == null) return;
        
        ThemeEntry activeTheme = getCurrentTheme();
        if (activeTheme == null || activeTheme.folderPath == null) return;
        
        // Target the config.json file
        File configJsonFile = null;
        if (activeTheme.folderPath.startsWith("asset://")) {
            // Built-in theme: write to the internal cache copy instead
            File cacheDir = new File(internalThemesDir(ctx), activeTheme.folderName);
            configJsonFile = new File(cacheDir, "config.json");
        } else {
            configJsonFile = new File(activeTheme.folderPath, "config.json");
        }
        
        if (configJsonFile == null || !configJsonFile.isFile()) return;
        
        try {
            String jsonStr = new String(readAll(configJsonFile), "UTF-8");
            JSONObject root = new JSONObject(jsonStr);
            
            JSONObject solarConfig = root.optJSONObject("solarConfig");
            if (solarConfig == null) {
                solarConfig = new JSONObject();
                root.put("solarConfig", solarConfig);
            }
            
            // Check if contradictory keys exist
            java.util.List<String> allKeys = SettingLookup.getAllSolarConfigKeysForPref(prefKey);
            boolean needsChange = false;
            for (String k : allKeys) {
                if (!k.equals(newKey) && solarConfig.has(k)) {
                    solarConfig.remove(k);
                    needsChange = true;
                }
            }
            
            // Check if the new key is already set correctly
            if (newValue instanceof Boolean) {
                if (!solarConfig.has(newKey)) {
                    solarConfig.put(newKey, true);
                    needsChange = true;
                }
            } else {
                if (!solarConfig.has(newKey) || !newValue.equals(solarConfig.opt(newKey))) {
                    solarConfig.put(newKey, newValue);
                    needsChange = true;
                }
            }
            
            if (!needsChange) return;
            
            // Write back to disk
            FileOutputStream fos = new FileOutputStream(configJsonFile);
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.close();
            
            // Update in-memory active theme
            // In-memory active theme is final, so we rely on the sync/reload to update it or restart
            // to apply the new config completely.
            
            // Trigger sync to ensure changes mirror to SD card
            scheduleThemeLibrarySync(ctx, true);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
