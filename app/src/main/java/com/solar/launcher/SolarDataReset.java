package com.solar.launcher;

import android.content.Context;

import com.solar.launcher.deezer.DeezerCache;
import com.solar.launcher.flow.AlbumArtCache;
import com.solar.launcher.flow.FlowThumbCache;
import com.solar.launcher.soulseek.ReachCache;
import com.solar.launcher.theme.ActiveThemeEngine;
import com.solar.launcher.theme.ThemeManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Destructive data reset helpers for Settings → Power → Reset.
 * ponytail: MicroSD wipe overwrites file bytes once before delete so recovery tools can still find ghosts.
 */
public final class SolarDataReset {

    /** Primary user volume — MicroSD on both Y1 and Y2. */
    public static final File STORAGE_ROOT = com.solar.launcher.DeviceFeatures.getMicroSdWipeRoot();
    /** Rockbox config — internal (sdcard0) on Y2, user SD on Y1. */
    public static final File ROCKBOX_DIR = new File(com.solar.launcher.DeviceFeatures.getRockboxRoot(), ".rockbox");

    public static final class Selection {
        public boolean rockboxConfig;
        public boolean solarPrefs;
        public boolean storedThemes;
        public boolean microSdContents;
        public boolean caches;
        public boolean flowCache;
        public boolean stems;
    }

    public static final class Result {
        public boolean ok = true;
        public String error;
        public boolean solarPrefsCleared;
        /** True when {@link #clearCaches} ran — album art must rebuild on disk. */
        public boolean cachesCleared;
        public boolean flowCacheCleared;
        public boolean stemsCleared;
    }

    private SolarDataReset() {}

    public static Result run(Context ctx, Selection sel) {
        Result out = new Result();
        if (ctx == null || sel == null) {
            out.ok = false;
            out.error = "missing context";
            return out;
        }
        Context app = ctx.getApplicationContext();
        try {
            if (sel.rockboxConfig) {
                deleteTree(ROCKBOX_DIR, false);
            }
            if (sel.storedThemes) {
                clearStoredThemes(app);
            }
            if (sel.caches) {
                clearCaches(app);
                clearLibraryDatabase(app);
                out.cachesCleared = true;
            }
            if (sel.flowCache) {
                clearDirectoryContents(AlbumArtCache.cacheDir(app), false);
                clearDirectoryContents(FlowThumbCache.cacheDir(app), false);
                out.flowCacheCleared = true;
            }
            if (sel.stems) {
                clearStems(app);
                out.stemsCleared = true;
            }
            if (sel.microSdContents) {
                wipeMicroSdRoot();
            }
            if (sel.solarPrefs) {
                clearLibraryDatabase(app);
                clearAllSharedPreferences(app);
                clearRecoveryState(app);
                out.solarPrefsCleared = true;
            }
        } catch (Exception e) {
            out.ok = false;
            out.error = e.getMessage() != null ? e.getMessage() : "reset failed";
        }
        return out;
    }

    /** Delete children of {@link #STORAGE_ROOT} with a single zero overwrite pass (recoverable wipe). */
    static void wipeMicroSdRoot() {
        File[] kids = STORAGE_ROOT.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            deleteTree(kid, true);
        }
    }

    static void clearStoredThemes(Context app) {
        ThemeManager.releaseSdcardFileHandles();
        clearDirectoryContents(new File(ThemeManager.themesRoot()), false);
        clearDirectoryContents(new File(ActiveThemeEngine.jjThemesRoot()), false);
        clearDirectoryContents(ThemeManager.internalThemesDir(app), false);
    }

    /** Drop SQLite music index so the next scan rebuilds from disk. */
    static void clearLibraryDatabase(Context app) {
        if (app == null) return;
        MusicLibraryStore.getInstance(app).clearAll();
    }

    static void clearCaches(Context app) {
        ThemeManager.releaseSdcardFileHandles();
        for (File root : com.solar.launcher.DeviceFeatures.getStorageRoots()) {
            clearDirectoryContents(new File(root, "Solar_Covers"), false);
        }
        clearDirectoryContents(AlbumArtCache.cacheDir(app), false);
        clearDirectoryContents(FlowThumbCache.cacheDir(app), false);
        File streamRoot = StreamCacheRoot.resolve(app);
        File appCache = app.getCacheDir();
        // When stream overflow resolves to the app cache (the common emulator path), the
        // stem vault lives inside it — stems are durable user data with their own reset
        // toggle, so a generic cache clear must keep lalal_stems/lalal_work intact.
        if (streamRoot != null && appCache != null
                && streamRoot.getAbsolutePath().equals(appCache.getAbsolutePath())) {
            clearCacheKeepingStems(streamRoot);
        } else {
            clearDirectoryContents(streamRoot, false);
        }
        clearDirectoryContents(DeezerCache.dir(streamRoot), false);
        clearDirectoryContents(ReachCache.dir(streamRoot), false);
        clearDirectoryContents(new File(streamRoot, "podcast"), false);
        clearCacheKeepingStems(appCache);
        File extCache = app.getExternalCacheDir();
        if (extCache != null) clearCacheKeepingStems(extCache);
    }

    /**
     * Wipe an app cache dir but keep the stem vaults — stems are durable user data
     * (own reset toggle), never collateral of a generic "Clear caches".
     */
    static void clearCacheKeepingStems(File cacheDir) {
        if (cacheDir == null || !cacheDir.isDirectory()) return;
        File[] kids = cacheDir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            String n = kid.getName();
            if (n != null && ("lalal_stems".equals(n) || "lalal_work".equals(n)
                    || "lalal_solo_cache".equals(n))) {
                continue;
            }
            deleteTree(kid, false);
        }
    }

    static void clearStems(Context app) {
        if (app == null) return;
        File appCache = app.getCacheDir();
        if (appCache != null) {
            clearDirectoryContents(new File(appCache, "lalal_stems"), false);
            clearDirectoryContents(new File(appCache, "lalal_work"), false);
            clearDirectoryContents(new File(appCache, "lalal_solo_cache"), false);
        }
        File extCache = app.getExternalCacheDir();
        if (extCache != null) {
            clearDirectoryContents(new File(extCache, "lalal_stems"), false);
            clearDirectoryContents(new File(extCache, "lalal_work"), false);
            clearDirectoryContents(new File(extCache, "lalal_solo_cache"), false);
        }
    }

    /** Remove every XML store under shared_prefs — all Solar preference namespaces. */
    static void clearAllSharedPreferences(Context app) {
        File spDir = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = spDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) f.delete();
        }
    }

    /** 2026-07-05 — Clear crash streak / emergency / degraded recovery flags on full prefs wipe. */
    static void clearRecoveryState(Context app) {
        if (app == null) return;
        SolarRecoveryCoordinator.clearEmergencyState(app);
        SolarRecoveryCoordinator.setPlatformDegraded(app, false);
        // 2026-07-16 — Factory wipe must re-show first-ready wait on next cold start.
        FirstSessionReadyGate.clearUiReady(app);
    }

    static void clearDirectoryContents(File dir, boolean overwriteFiles) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            deleteTree(kid, overwriteFiles);
        }
    }

    static void deleteTree(File target, boolean overwriteFiles) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] kids = target.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteTree(kid, overwriteFiles);
            }
        } else if (overwriteFiles) {
            overwriteFileContents(target);
        }
        target.delete();
    }

    /** One zero pass — enough for photorec-style recovery, not secure erase. */
    static void overwriteFileContents(File file) {
        if (file == null || !file.isFile()) return;
        RandomAccessFile raf = null;
        try {
            long len = file.length();
            if (len <= 0) len = 4096;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(len);
            byte[] zeros = new byte[8192];
            long pos = 0;
            while (pos < len) {
                int chunk = (int) Math.min(zeros.length, len - pos);
                raf.write(zeros, 0, chunk);
                pos += chunk;
            }
        } catch (Exception ignored) {
            // ponytail: best-effort overwrite — still delete below
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                fos.write(new byte[4096]);
                fos.close();
            } catch (Exception ignored2) {}
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /** Human-readable summary for the confirm dialog. */
    public static List<String> selectedLabels(Context ctx, Selection sel) {
        ArrayList<String> lines = new ArrayList<String>();
        if (ctx == null || sel == null) return lines;
        if (sel.rockboxConfig) lines.add(ctx.getString(R.string.settings_reset_rockbox));
        if (sel.solarPrefs) lines.add(ctx.getString(R.string.settings_reset_solar));
        if (sel.storedThemes) lines.add(ctx.getString(R.string.settings_reset_themes));
        if (sel.microSdContents) lines.add(ctx.getString(R.string.settings_reset_microsd));
        if (sel.caches) lines.add(ctx.getString(R.string.settings_reset_caches));
        if (sel.flowCache) lines.add(ctx.getString(R.string.settings_reset_flow));
        if (sel.stems) lines.add(ctx.getString(R.string.settings_reset_stems));
        return lines;
    }
}
