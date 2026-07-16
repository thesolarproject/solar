package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Path-keyed audiobook resume positions (JJ launcher 0.11 parity).
 * Stores position, duration (for list progress), and chapter index.
 */
public final class AudiobookBookmarkStore {
    private static final String PREFS = "solar_audiobook_bookmarks";
    private static final int MIN_RESUME_MS = 5000;
    private static final int TAIL_SKIP_MS = 30000;
    private static final float COMPLETE_RATIO = 0.95f;

    private AudiobookBookmarkStore() {}

    public static List<File> audiobookRoots() {
        List<File> roots = new ArrayList<File>();
        File sd0 = new File("/storage/sdcard0/Audiobooks");
        roots.add(sd0);
        File sd1 = new File("/storage/sdcard1/Audiobooks");
        if (sd1.isDirectory() || new File("/storage/sdcard1").isDirectory()) {
            roots.add(sd1);
        }
        return roots;
    }

    public static File primaryRoot() {
        return new File("/storage/sdcard0/Audiobooks");
    }

    public static void ensureRootsExist() {
        for (File r : audiobookRoots()) {
            if (!r.exists()) {
                // noinspection ResultOfMethodCallIgnored
                r.mkdirs();
            }
        }
    }

    public static boolean isUnderAudiobooks(File file) {
        if (file == null) return false;
        String path = file.getAbsolutePath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("/audiobooks/") || lower.endsWith("/audiobooks");
    }

    public static boolean isUnderAudiobooks(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("/audiobooks/") || lower.endsWith("/audiobooks");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context ctx, String filePath, int positionMs, int durationMs, int chapterIdx) {
        if (ctx == null || filePath == null || filePath.isEmpty()) return;
        if (positionMs < MIN_RESUME_MS) return;
        if (durationMs > 0) {
            if (positionMs >= durationMs * COMPLETE_RATIO) {
                clear(ctx, filePath);
                return;
            }
            if (positionMs >= durationMs - TAIL_SKIP_MS) {
                clear(ctx, filePath);
                return;
            }
        }
        prefs(ctx).edit()
                .putInt("POS_" + filePath, positionMs)
                .putInt("DUR_" + filePath, Math.max(0, durationMs))
                .putInt("CHAP_" + filePath, Math.max(0, chapterIdx))
                .apply();
    }

    public static int getPositionMs(Context ctx, String filePath) {
        if (ctx == null || filePath == null) return 0;
        int pos = prefs(ctx).getInt("POS_" + filePath, 0);
        int dur = prefs(ctx).getInt("DUR_" + filePath, 0);
        if (pos < MIN_RESUME_MS) return 0;
        if (dur > 0 && pos >= dur - TAIL_SKIP_MS) return 0;
        return pos;
    }

    public static int getDurationMs(Context ctx, String filePath) {
        if (ctx == null || filePath == null) return 0;
        return prefs(ctx).getInt("DUR_" + filePath, 0);
    }

    public static int getChapterIndex(Context ctx, String filePath) {
        if (ctx == null || filePath == null) return 0;
        return prefs(ctx).getInt("CHAP_" + filePath, 0);
    }

    public static void clear(Context ctx, String filePath) {
        if (ctx == null || filePath == null) return;
        prefs(ctx).edit()
                .remove("POS_" + filePath)
                .remove("DUR_" + filePath)
                .remove("CHAP_" + filePath)
                .apply();
    }

    /** Unit-testable complete/tail rules without Context. */
    static int clampResumePosition(int positionMs, int durationMs) {
        if (positionMs < MIN_RESUME_MS) return 0;
        if (durationMs > 0 && positionMs >= durationMs - TAIL_SKIP_MS) return 0;
        if (durationMs > 0 && positionMs >= durationMs * COMPLETE_RATIO) return 0;
        return positionMs;
    }
}
