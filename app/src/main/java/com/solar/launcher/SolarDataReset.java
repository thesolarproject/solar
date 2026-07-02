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

    /** Y1 primary volume — user-facing "Internal Storage" / MicroSD mount. */
    public static final File STORAGE_ROOT = new File("/storage/sdcard0");
    public static final File ROCKBOX_DIR = new File(STORAGE_ROOT, ".rockbox");

    public static final class Selection {
        public boolean rockboxConfig;
        public boolean solarPrefs;
        public boolean storedThemes;
        public boolean microSdContents;
        public boolean caches;
    }

    public static final class Result {
        public boolean ok = true;
        public String error;
        public boolean solarPrefsCleared;
        /** True when {@link #clearCaches} ran — album art must rebuild on disk. */
        public boolean cachesCleared;
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
            if (sel.microSdContents) {
                wipeMicroSdRoot();
            }
            if (sel.solarPrefs) {
                clearLibraryDatabase(app);
                clearAllSharedPreferences(app);
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
        clearDirectoryContents(new File(ThemeManager.PATH_THEMES), false);
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
        clearDirectoryContents(new File(STORAGE_ROOT, "Solar_Covers"), false);
        clearDirectoryContents(AlbumArtCache.cacheDir(app), false);
        clearDirectoryContents(FlowThumbCache.cacheDir(app), false);
        File streamRoot = StreamCacheRoot.resolve(app);
        clearDirectoryContents(streamRoot, false);
        clearDirectoryContents(DeezerCache.dir(streamRoot), false);
        clearDirectoryContents(ReachCache.dir(streamRoot), false);
        clearDirectoryContents(new File(streamRoot, "podcast"), false);
        clearDirectoryContents(app.getCacheDir(), false);
        File extCache = app.getExternalCacheDir();
        if (extCache != null) clearDirectoryContents(extCache, false);
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
        return lines;
    }
}
