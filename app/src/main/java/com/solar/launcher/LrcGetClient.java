package com.solar.launcher;

import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * LRCGET-style lyrics client — talks to the LRCLIB service (the API LRCGET uses) so library
 * songs without a local .lrc sidecar can still show lyrics. Two-step match like LRCGET:
 * 1) exact {@code /api/get} with title/artist/album/duration, then 2) {@code /api/search}
 * fallback scored by duration proximity.
 *
 * <p>2026-08-05 — Reimplemented inside Solar (not the LRCGET binary); API only. Fair-use:
 * callers must not re-query the same track repeatedly — {@link LyricsLibraryMatcher} remembers
 * attempts and throttles background runs.</p>
 */
public final class LrcGetClient {
    private static final String API_BASE = "https://lrclib.net/api";
    private static final String USER_AGENT =
            "SolarLauncher/1.0 (LRCGET-compatible lyrics client; thesolarproject)";

    private LrcGetClient() {}

    /** One LRCLIB song record. */
    public static final class Match {
        public final long id;
        public final String trackName;
        public final String artistName;
        public final String albumName;
        /** Whole seconds when LRCLIB reported one; 0 when absent. */
        public final double durationSec;
        public final boolean instrumental;
        public final String plainLyrics;
        public final String syncedLyrics;

        Match(long id, String trackName, String artistName, String albumName,
                double durationSec, boolean instrumental, String plainLyrics, String syncedLyrics) {
            this.id = id;
            this.trackName = trackName != null ? trackName : "";
            this.artistName = artistName != null ? artistName : "";
            this.albumName = albumName != null ? albumName : "";
            this.durationSec = durationSec;
            this.instrumental = instrumental;
            this.plainLyrics = plainLyrics != null ? plainLyrics : "";
            this.syncedLyrics = syncedLyrics != null ? syncedLyrics : "";
        }
    }

    /**
     * Resolve lyrics for one track: exact /api/get first, search fallback when that 404s.
     * Null when LRCLIB has no record (or instrumental-only caller decides).
     * Network failures propagate as IOException so the caller can stop a background run.
     */
    public static Match fetch(String trackName, String artistName, String albumName, int durationSec)
            throws IOException {
        Match exact = fetchExact(trackName, artistName, albumName, durationSec);
        if (exact != null) return exact;
        List<Match> results = search(trackName, artistName, albumName, durationSec);
        return pickBest(results, trackName, artistName, durationSec);
    }

    /** GET /api/get — single record or null on 404 (no match). */
    static Match fetchExact(String trackName, String artistName, String albumName, int durationSec)
            throws IOException {
        StringBuilder url = new StringBuilder(API_BASE).append("/get?");
        url.append("track_name=").append(enc(trackName));
        url.append("&artist_name=").append(enc(artistName));
        if (albumName != null && !albumName.trim().isEmpty()) {
            url.append("&album_name=").append(enc(albumName));
        }
        if (durationSec > 0) url.append("&duration=").append(durationSec);
        Response resp = doGet(url.toString());
        try {
            int code = resp.code();
            if (code == 404) return null;
            if (code != 200 || resp.body() == null) {
                throw new IOException("LRCLIB /get HTTP " + code);
            }
            try {
                return parseMatch(new JSONObject(resp.body().string()));
            } catch (org.json.JSONException e) {
                throw new IOException("LRCLIB /get bad JSON", e);
            }
        } finally {
            closeQuietly(resp);
        }
    }

    /** GET /api/search — ranked candidate list (may be empty). */
    static List<Match> search(String trackName, String artistName, String albumName, int durationSec)
            throws IOException {
        StringBuilder url = new StringBuilder(API_BASE).append("/search?");
        url.append("track_name=").append(enc(trackName));
        if (artistName != null && !artistName.trim().isEmpty()) {
            url.append("&artist_name=").append(enc(artistName));
        }
        if (albumName != null && !albumName.trim().isEmpty()) {
            url.append("&album_name=").append(enc(albumName));
        }
        if (durationSec > 0) url.append("&duration=").append(durationSec);
        Response resp = doGet(url.toString());
        try {
            int code = resp.code();
            if (code == 404 || code == 204) return new ArrayList<Match>();
            if (code != 200 || resp.body() == null) {
                throw new IOException("LRCLIB /search HTTP " + code);
            }
            try {
                JSONArray arr = new JSONArray(resp.body().string());
                List<Match> out = new ArrayList<Match>(arr.length());
                for (int i = 0; i < arr.length(); i++) out.add(parseMatch(arr.getJSONObject(i)));
                return out;
            } catch (org.json.JSONException e) {
                throw new IOException("LRCLIB /search bad JSON", e);
            }
        } finally {
            closeQuietly(resp);
        }
    }

    /**
     * Pick the best search candidate — LRCGET-style: prefer the smallest duration delta
     * when durations exist, then normalized title equality; LRCLIB ranking breaks ties.
     */
    static Match pickBest(List<Match> candidates, String trackName, String artistName, int durationSec) {
        if (candidates == null || candidates.isEmpty()) return null;
        Match best = null;
        double bestDelta = Double.MAX_VALUE;
        boolean bestTitleExact = false;
        String wantTitle = normalize(trackName);
        String wantArtist = normalize(artistName);
        for (Match m : candidates) {
            double delta = durationSec > 0 && m.durationSec > 0
                    ? Math.abs(m.durationSec - durationSec) : Double.MAX_VALUE;
            boolean titleExact = wantTitle.length() > 0 && wantTitle.equals(normalize(m.trackName));
            if (best == null
                    || (titleExact && !bestTitleExact)
                    || (titleExact == bestTitleExact && delta < bestDelta)) {
                best = m;
                bestDelta = delta;
                bestTitleExact = titleExact;
            }
        }
        // Title/artist sanity: LRCLIB search can return unrelated rows for short queries.
        if (best == null) return null;
        if (wantTitle.length() > 0 && wantArtist.length() > 0) {
            String gotTitle = normalize(best.trackName);
            String gotArtist = normalize(best.artistName);
            boolean titleMatch = wantTitle.equals(gotTitle)
                    || gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle);
            boolean artistMatch = wantArtist.equals(gotArtist)
                    || gotArtist.contains(wantArtist) || wantArtist.contains(gotArtist);
            if (!titleMatch && !artistMatch) return null;
            if (!titleMatch || !artistMatch) {
                // Allow one-sided match only when duration lines up closely.
                if (durationSec <= 0 || best.durationSec <= 0
                        || Math.abs(best.durationSec - durationSec) > 5.0) {
                    return null;
                }
            }
        }
        return best;
    }

    /** Synced LRC text when present, plain text otherwise; null when nothing usable. */
    public static String bestLyricsText(Match match) {
        if (match == null || match.instrumental) return null;
        if (match.syncedLyrics != null && !match.syncedLyrics.trim().isEmpty()) {
            return match.syncedLyrics;
        }
        if (match.plainLyrics != null && !match.plainLyrics.trim().isEmpty()) {
            return match.plainLyrics;
        }
        return null;
    }

    /** Pure JSON → Match (unit-tested with captured LRCLIB payloads). */
    static Match parseMatch(JSONObject o) throws org.json.JSONException {
        if (o == null) return null;
        double duration = o.optDouble("duration", 0d);
        if (duration < 0) duration = 0;
        return new Match(
                o.optLong("id", 0L),
                o.optString("trackName", ""),
                o.optString("artistName", ""),
                o.optString("albumName", ""),
                duration,
                o.optBoolean("instrumental", false),
                o.optString("plainLyrics", ""),
                o.optString("syncedLyrics", ""));
    }

    /** lowercase + trim + collapse whitespace, for title/artist sanity checks. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }

    private static Response doGet(String url) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();
        // Short-ish timeouts: this is a small JSON lookup, not a stream.
        okhttp3.OkHttpClient c = TlsHelper.client().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
        return c.newCall(req).execute();
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void closeQuietly(Response resp) {
        if (resp == null) return;
        try {
            if (resp.body() != null) resp.body().close();
            else resp.close();
        } catch (Exception ignored) {}
    }
}
