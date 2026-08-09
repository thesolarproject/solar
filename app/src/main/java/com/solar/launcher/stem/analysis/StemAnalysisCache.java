package com.solar.launcher.stem.analysis;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.stem.LalalClient;

import org.json.JSONObject;

import java.io.File;

/**
 * Persistent per-track stem analysis cache (SharedPreferences JSON).
 * Layman: remember the real tempo / key / beat position of a song so the next jam
 * session is instant instead of re-analysing from scratch.
 * Technical: keyed by {@link LalalClient#cacheKeyStable} (basename|size) so remounts and
 * path drift keep the entry; the source file length is stored as an invalidation check.
 * 2026-08-01
 */
public final class StemAnalysisCache {
    private static final String PREFS = "stem_analysis";
    private static final int VERSION = 1;

    private StemAnalysisCache() {}

    /** Cached result for a track, or null when absent / stale / corrupt. */
    public static StemAnalysisCore.Result lookup(Context ctx, File track) {
        if (ctx == null || track == null || !track.isFile()) return null;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            String json = p.getString(keyFor(track), "");
            if (json.isEmpty()) return null;
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != VERSION) return null;
            long srcLen = o.optLong("srcLen", -1L);
            if (srcLen > 0 && srcLen != track.length()) return null;
            StemAnalysisCore.Result r = new StemAnalysisCore.Result();
            r.bpm = (float) o.optDouble("bpm", 120.0);
            r.confidence = (float) o.optDouble("conf", 0f);
            r.phaseMs = o.optInt("phase", 0);
            r.firstBeatMs = o.optInt("firstBeat", 0);
            r.keyRoot = o.optInt("keyRoot", -1);
            r.keyMajor = o.optBoolean("keyMajor", true);
            r.keyLabel = o.optString("keyLabel", "");
            r.camelot = o.optString("camelot", "");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** Persist an analysis result for a track (off-main only — commit is synchronous). */
    public static void store(Context ctx, File track, StemAnalysisCore.Result r) {
        if (ctx == null || track == null || r == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("srcLen", track.length());
            o.put("bpm", Math.round(r.bpm * 10f) / 10f);
            o.put("conf", Math.round(r.confidence * 100f) / 100f);
            o.put("phase", r.phaseMs);
            o.put("firstBeat", r.firstBeatMs);
            o.put("keyRoot", r.keyRoot);
            o.put("keyMajor", r.keyMajor);
            o.put("keyLabel", r.keyLabel != null ? r.keyLabel : "");
            o.put("camelot", r.camelot != null ? r.camelot : "");
            ctx.getSharedPreferences(PREFS, 0).edit().putString(keyFor(track), o.toString()).commit();
        } catch (Exception ignored) {}
    }

    private static String keyFor(File track) {
        return LalalClient.cacheKeyStable(track);
    }
}
