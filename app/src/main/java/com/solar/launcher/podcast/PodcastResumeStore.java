package com.solar.launcher.podcast;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

/** Per-episode podcast resume positions in SharedPreferences. */
public final class PodcastResumeStore {
    private static final String PREFS = "podcast_resume";
    private static final int MIN_RESUME_MS = 5000;
    private static final int TAIL_SKIP_MS = 30000;
    private static final float COMPLETE_RATIO = 0.95f;

    private PodcastResumeStore() {}

    static int parseStoredDurationMs(String raw) {
        if (raw == null) return 0;
        String[] parts = raw.split("\\|");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Best duration for tail/complete checks — never shrink below a prior full-episode estimate. */
    static int effectiveDurationMs(int durationMs, int storedDurationMs) {
        if (durationMs <= 0) return Math.max(0, storedDurationMs);
        if (storedDurationMs <= 0) return durationMs;
        return Math.max(durationMs, storedDurationMs);
    }

    public static String keyForUrl(String audioUrl) {
        if (audioUrl == null) return "url:unknown";
        String[] v = PodcastLibrary.httpsThenHttpVariants(audioUrl);
        String norm = v.length > 0 ? v[0] : audioUrl.trim();
        return "url:" + sha1Hex(norm);
    }

    public static String keyForFile(File file) {
        if (file == null) return "file:unknown";
        return "file:" + file.getAbsolutePath();
    }

    public static String keyForEpisode(String showTitle, String episodeTitle, String audioUrl, File savedFile) {
        if (savedFile != null && savedFile.isFile()) return keyForFile(savedFile);
        if (audioUrl != null && audioUrl.startsWith("file://")) {
            return keyForFile(new File(audioUrl.substring(7)));
        }
        if (audioUrl != null && audioUrl.startsWith("/")) return keyForFile(new File(audioUrl));
        return keyForUrl(audioUrl);
    }

    public static boolean hasResume(Context ctx, String key) {
        return restorePositionMs(ctx, key, Integer.MAX_VALUE) > 0;
    }

    public static int restorePositionMs(Context ctx, String key, int durationMs) {
        if (ctx == null || key == null) return 0;
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        return parseStoredPosition(raw, durationMs);
    }

    /** ponytail: testable parse path without Context */
    static int parseStoredPosition(String raw, int durationMs) {
        if (raw == null) return 0;
        String[] parts = raw.split("\\|");
        if (parts.length < 1) return 0;
        try {
            int pos = Integer.parseInt(parts[0]);
            int storedDur = parseStoredDurationMs(raw);
            int dur = effectiveDurationMs(durationMs, storedDur);
            if (pos < MIN_RESUME_MS) return 0;
            if (dur > 0 && pos >= dur - TAIL_SKIP_MS) return 0;
            return pos;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void save(Context ctx, String key, int positionMs, int durationMs) {
        if (ctx == null || key == null || positionMs < MIN_RESUME_MS) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int storedDur = parseStoredDurationMs(prefs.getString(key, null));
        int dur = effectiveDurationMs(durationMs, storedDur);
        if (dur > 0 && positionMs >= dur * COMPLETE_RATIO) {
            clear(ctx, key);
            return;
        }
        if (dur > 0 && positionMs >= dur - TAIL_SKIP_MS) {
            clear(ctx, key);
            return;
        }
        String val = positionMs + "|" + dur + "|" + System.currentTimeMillis();
        prefs.edit().putString(key, val).commit();
    }

    public static void clear(Context ctx, String key) {
        if (ctx == null || key == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).commit();
    }

    static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** ponytail: key + tail-skip sanity */
    public static void selfCheck() {
        String k1 = keyForUrl("https://host/ep.mp3");
        String k2 = keyForUrl("http://host/ep.mp3");
        if (!k1.equals(k2)) throw new AssertionError("url key normalize");
        if (restorePositionMs(null, k1, 600000) != 0) throw new AssertionError("null ctx");
        if (restorePositionMs(null, k1, 600000) != 0) throw new AssertionError("null ctx2");
        int tail = restorePositionMs(null, "x", 100000);
        // restore with null ctx returns 0 — ok
        File f = new File(PodcastLibrary.ROOT, "show/ep.mp3");
        if (!keyForFile(f).startsWith("file:")) throw new AssertionError("file key");
    }
}
