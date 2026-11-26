package com.solar.launcher.ebook;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 2026-11-26 — Last page read per book.
 * Layman: closing a book and opening it again resumes where you stopped.
 * Tech: prefs value "chapter:page" (EPUB) or "pdf:page"; keyed by absolute path and
 *   invalidated when the file's size/mtime change (re-downloaded book = new content).
 */
public final class EbookProgressStore {
    private static final String PREFS = "SOLAR_EBOOK_PROGRESS";

    private EbookProgressStore() {}

    public static final class Progress {
        public final int chapter;
        public final int page;

        Progress(int chapter, int page) {
            this.chapter = chapter < 0 ? 0 : chapter;
            this.page = page < 0 ? 0 : page;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    public static Progress load(Context ctx, File f) {
        if (ctx == null || f == null) return new Progress(0, 0);
        String raw = prefs(ctx).getString(key(f), null);
        if (raw == null) return new Progress(0, 0);
        return parse(raw);
    }

    public static void save(Context ctx, File f, int chapter, int page) {
        if (ctx == null || f == null) return;
        // 2026-11-26 — apply(), not commit(): committing on every page turn blocked the
        // UI thread on disk. Reversal: .commit().
        prefs(ctx).edit().putString(key(f), chapter + ":" + page).apply();
    }

    static Progress parse(String raw) {
        if (raw == null) return new Progress(0, 0);
        int colon = raw.indexOf(':');
        if (colon < 0) return new Progress(0, 0);
        try {
            return new Progress(Integer.parseInt(raw.substring(0, colon)),
                    Integer.parseInt(raw.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return new Progress(0, 0);
        }
    }

    /** ponytail: string round-trip only. */
    public static void selfCheck() {
        Progress p = parse("2:11");
        if (p.chapter != 2 || p.page != 11) throw new AssertionError("parse");
        Progress bad = parse("oops");
        if (bad.chapter != 0 || bad.page != 0) throw new AssertionError("bad");
    }
}
