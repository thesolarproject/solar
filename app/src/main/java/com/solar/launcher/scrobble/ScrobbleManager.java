package com.solar.launcher.scrobble;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;

import com.solar.launcher.PlayQueue;
import com.solar.launcher.net.SolarHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.FormBody;

/**
 * ponytail: standalone Last.fm & ListenBrainz scrobbling manager.
 * Tracks playback state, filters out podcasts & videos, maps YouTube audio titles when "Artist - Title",
 * submits now playing notifications, and scrobbles after 50% or 4 minutes of listening.
 */
public final class ScrobbleManager {
    private static final String TAG = "ScrobbleManager";
    private static final String PREFS_NAME = "SOLAR_SETTINGS";

    public static final String PREF_LASTFM_ENABLED = "scrobble_lastfm_enabled";
    public static final String PREF_LASTFM_USERNAME = "scrobble_lastfm_username";
    public static final String PREF_LASTFM_PASSWORD = "scrobble_lastfm_password";
    public static final String PREF_LASTFM_SK = "scrobble_lastfm_sk";
    public static final String PREF_LASTFM_API_KEY = "scrobble_lastfm_api_key";
    public static final String PREF_LASTFM_API_SECRET = "scrobble_lastfm_api_secret";

    public static final String PREF_LISTENBRAINZ_ENABLED = "scrobble_listenbrainz_enabled";
    public static final String PREF_LISTENBRAINZ_TOKEN = "scrobble_listenbrainz_token";

    public static final String DEFAULT_LASTFM_API_KEY = "c2fd5c517c27633e8ca770c06aefdebc";
    public static final String DEFAULT_LASTFM_API_SECRET = "fbd1d34cddbb2aa6d53dc9a3b6807834";

    private static final String LASTFM_API_URL = "https://ws.audioscrobbler.com/2.0/";
    private static final String LISTENBRAINZ_API_URL = "https://api.listenbrainz.org/1/submit-listens";

    private static String currentTitle = "";
    private static String currentArtist = "";
    private static String currentAlbum = "";
    private static long currentDurationMs = 0;
    private static long trackStartUtcSec = 0;
    private static long totalListenedMs = 0;
    private static long lastPlayResumeRealtimeMs = 0;
    private static boolean isCurrentlyPlaying = false;
    private static boolean hasScrobbledCurrent = false;
    private static boolean isExcluded = false;

    public interface AuthCallback {
        void onResult(boolean success, String message);
    }

    private ScrobbleManager() {}

    public static synchronized void onPlaybackStateChange(Context ctx, String title, String artist,
            String album, int durationMs, int positionMs, boolean playing, boolean trackChanged,
            boolean isPodcast, PlayQueue.QueueItem cur, boolean isVideo) {
        if (ctx == null) return;
        boolean wasPlaying = isCurrentlyPlaying;
        long oldListenedMs = totalListenedMs;
        boolean oldExcluded = isExcluded;
        boolean oldScrobbled = hasScrobbledCurrent;

        processStateChange(title, artist, album, durationMs, positionMs, playing, trackChanged, isPodcast, cur, isVideo);

        if (!oldScrobbled && hasScrobbledCurrent) {
            submitScrobble(ctx, currentTitle, currentArtist, currentAlbum, trackStartUtcSec, currentDurationMs / 1000);
        } else if (!wasPlaying && isCurrentlyPlaying && trackChanged) {
            submitNowPlaying(ctx, currentTitle, currentArtist, currentAlbum, currentDurationMs / 1000);
        }
    }

    /** ponytail: pure logic extracted for testing without Context or AsyncTask mocks. */
    static synchronized void processStateChange(String title, String artist,
            String album, int durationMs, int positionMs, boolean playing, boolean trackChanged,
            boolean isPodcast, PlayQueue.QueueItem cur, boolean isVideo) {

        // 1. Filter out videos & podcasts explicitly
        if (isVideo || isPodcast || (cur != null && cur.kind == PlayQueue.ItemKind.PODCAST_EPISODE)) {
            isExcluded = true;
            isCurrentlyPlaying = false;
            return;
        }

        String cleanedTitle = title != null ? title.trim() : "";
        String cleanedArtist = artist != null ? artist.trim() : "";
        String cleanedAlbum = album != null ? album.trim() : "";

        // Strip common placeholders
        if ("Loading...".equals(cleanedTitle) || "Loading…".equals(cleanedTitle)
                || "Buffering...".equals(cleanedTitle) || "Buffering…".equals(cleanedTitle)
                || "null".equalsIgnoreCase(cleanedTitle)) {
            return;
        }

        // 2. Handle YouTube Audio mapping (exclude generic videos unless cleanly mapped to Artist & Title)
        boolean isYouTube = (cur != null && cur.file != null && cur.file.getAbsolutePath().contains("/youtube/"))
                || (cur != null && cur.kind == PlayQueue.ItemKind.REACH_STREAM && cur.streamMeta() != null && cur.streamMeta().contains("YouTube"))
                || "YouTube".equalsIgnoreCase(cleanedArtist);

        if (isYouTube) {
            if (cleanedArtist.isEmpty() || "Unknown".equalsIgnoreCase(cleanedArtist)
                    || "Unknown Artist".equalsIgnoreCase(cleanedArtist) || "YouTube".equalsIgnoreCase(cleanedArtist)) {
                if (cleanedTitle.contains(" - ")) {
                    int idx = cleanedTitle.indexOf(" - ");
                    cleanedArtist = cleanedTitle.substring(0, idx).trim();
                    cleanedTitle = cleanedTitle.substring(idx + 3).trim();
                }
            }
            if (cleanedArtist.isEmpty() || "Unknown".equalsIgnoreCase(cleanedArtist)
                    || "Unknown Artist".equalsIgnoreCase(cleanedArtist) || "YouTube".equalsIgnoreCase(cleanedArtist)) {
                // Cannot map YouTube item to valid artist & title -> exclude
                isExcluded = true;
                isCurrentlyPlaying = false;
                return;
            }
        }

        if (cleanedTitle.isEmpty() || "Unknown".equalsIgnoreCase(cleanedTitle) || durationMs < 30000) {
            if (trackChanged) {
                isExcluded = true;
                isCurrentlyPlaying = false;
            }
            return;
        }

        boolean trackIdentityChanged = trackChanged || !cleanedTitle.equals(currentTitle) || !cleanedArtist.equals(currentArtist);

        if (trackIdentityChanged) {
            // Check if previous track qualifies for scrobble right before changing
            if (!hasScrobbledCurrent && !isExcluded && currentDurationMs >= 30000 && isCurrentlyPlaying) {
                long accumulated = totalListenedMs + (SystemClock.elapsedRealtime() - lastPlayResumeRealtimeMs);
                if (accumulated >= currentDurationMs / 2 || accumulated >= 240_000 || positionMs >= currentDurationMs / 2) {
                    hasScrobbledCurrent = true;
                }
            }

            // Reset state for new track
            currentTitle = cleanedTitle;
            currentArtist = cleanedArtist;
            currentAlbum = cleanedAlbum;
            currentDurationMs = durationMs;
            trackStartUtcSec = System.currentTimeMillis() / 1000;
            totalListenedMs = 0;
            hasScrobbledCurrent = false;
            isExcluded = false;
            isCurrentlyPlaying = playing;
            if (playing) {
                lastPlayResumeRealtimeMs = SystemClock.elapsedRealtime();
            }
        } else {
            // Same track, update listening accumulation
            if (isCurrentlyPlaying && !playing) {
                totalListenedMs += (SystemClock.elapsedRealtime() - lastPlayResumeRealtimeMs);
            } else if (!isCurrentlyPlaying && playing) {
                lastPlayResumeRealtimeMs = SystemClock.elapsedRealtime();
            }
            isCurrentlyPlaying = playing;

            if (!hasScrobbledCurrent && !isExcluded && currentDurationMs >= 30000) {
                long accumulated = totalListenedMs;
                if (isCurrentlyPlaying) {
                    accumulated += (SystemClock.elapsedRealtime() - lastPlayResumeRealtimeMs);
                }
                if (accumulated >= currentDurationMs / 2 || accumulated >= 240_000 || positionMs >= currentDurationMs / 2 || positionMs >= 240_000) {
                    hasScrobbledCurrent = true;
                }
            }
        }
    }

    private static void submitNowPlaying(final Context ctx, final String title, final String artist,
            final String album, final long durationSec) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (prefs.getBoolean(PREF_LASTFM_ENABLED, false)) {
                    sendLastFmNowPlaying(prefs, title, artist, album, durationSec);
                }
                if (prefs.getBoolean(PREF_LISTENBRAINZ_ENABLED, false)) {
                    sendListenBrainzListen(prefs, title, artist, album, 0, true);
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static void submitScrobble(final Context ctx, final String title, final String artist,
            final String album, final long timestampSec, final long durationSec) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (prefs.getBoolean(PREF_LASTFM_ENABLED, false)) {
                    sendLastFmScrobble(prefs, title, artist, album, timestampSec, durationSec);
                }
                if (prefs.getBoolean(PREF_LISTENBRAINZ_ENABLED, false)) {
                    sendListenBrainzListen(prefs, title, artist, album, timestampSec, false);
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static void sendLastFmNowPlaying(SharedPreferences prefs, String title, String artist,
            String album, long durationSec) {
        String sk = prefs.getString(PREF_LASTFM_SK, "");
        if (sk == null || sk.trim().isEmpty()) return;
        String apiKey = prefs.getString(PREF_LASTFM_API_KEY, DEFAULT_LASTFM_API_KEY);
        String apiSecret = prefs.getString(PREF_LASTFM_API_SECRET, DEFAULT_LASTFM_API_SECRET);

        Map<String, String> params = new HashMap<String, String>();
        params.put("method", "track.updateNowPlaying");
        params.put("track", title);
        params.put("artist", artist);
        if (album != null && !album.isEmpty()) params.put("album", album);
        if (durationSec > 0) params.put("duration", String.valueOf(durationSec));
        params.put("api_key", apiKey);
        params.put("sk", sk);

        String sig = createLastFmSignature(params, apiSecret);
        params.put("api_sig", sig);
        params.put("format", "json");

        FormBody.Builder fb = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            fb.add(entry.getKey(), entry.getValue());
        }
        try {
            SolarHttp.postForm(LASTFM_API_URL, fb.build());
            Log.d(TAG, "Last.fm NowPlaying submitted: " + artist + " - " + title);
        } catch (Exception e) {
            Log.w(TAG, "Last.fm NowPlaying failed: " + e.getMessage());
        }
    }

    private static void sendLastFmScrobble(SharedPreferences prefs, String title, String artist,
            String album, long timestampSec, long durationSec) {
        String sk = prefs.getString(PREF_LASTFM_SK, "");
        if (sk == null || sk.trim().isEmpty()) return;
        String apiKey = prefs.getString(PREF_LASTFM_API_KEY, DEFAULT_LASTFM_API_KEY);
        String apiSecret = prefs.getString(PREF_LASTFM_API_SECRET, DEFAULT_LASTFM_API_SECRET);

        Map<String, String> params = new HashMap<String, String>();
        params.put("method", "track.scrobble");
        params.put("track", title);
        params.put("artist", artist);
        params.put("timestamp", String.valueOf(timestampSec));
        if (album != null && !album.isEmpty()) params.put("album", album);
        if (durationSec > 0) params.put("duration", String.valueOf(durationSec));
        params.put("api_key", apiKey);
        params.put("sk", sk);

        String sig = createLastFmSignature(params, apiSecret);
        params.put("api_sig", sig);
        params.put("format", "json");

        FormBody.Builder fb = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            fb.add(entry.getKey(), entry.getValue());
        }
        try {
            SolarHttp.postForm(LASTFM_API_URL, fb.build());
            Log.d(TAG, "Last.fm Scrobble submitted: " + artist + " - " + title);
        } catch (Exception e) {
            Log.w(TAG, "Last.fm Scrobble failed: " + e.getMessage());
        }
    }

    private static void sendListenBrainzListen(SharedPreferences prefs, String title, String artist,
            String album, long timestampSec, boolean isNowPlaying) {
        String token = prefs.getString(PREF_LISTENBRAINZ_TOKEN, "");
        if (token == null || token.trim().isEmpty()) return;

        try {
            JSONObject trackMeta = new JSONObject();
            trackMeta.put("artist_name", artist);
            trackMeta.put("track_name", title);
            if (album != null && !album.isEmpty()) {
                trackMeta.put("release_name", album);
            }

            JSONObject item = new JSONObject();
            item.put("track_metadata", trackMeta);
            if (!isNowPlaying && timestampSec > 0) {
                item.put("listened_at", timestampSec);
            }

            JSONArray payloadArr = new JSONArray();
            payloadArr.put(item);

            JSONObject root = new JSONObject();
            root.put("listen_type", isNowPlaying ? "playing_now" : "single");
            root.put("payload", payloadArr);

            SolarHttp.postJson(LISTENBRAINZ_API_URL, root.toString(), token.trim());
            Log.d(TAG, "ListenBrainz submitted (" + (isNowPlaying ? "playing_now" : "single") + "): " + artist + " - " + title);
        } catch (Exception e) {
            Log.w(TAG, "ListenBrainz submission failed: " + e.getMessage());
        }
    }

    public static String getSessionLastFmSync(Context ctx, String token) {
        if (ctx == null || token == null || token.trim().isEmpty()) {
            return "Token required";
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_LASTFM_API_KEY, DEFAULT_LASTFM_API_KEY);
        String apiSecret = prefs.getString(PREF_LASTFM_API_SECRET, DEFAULT_LASTFM_API_SECRET);

        Map<String, String> params = new HashMap<String, String>();
        params.put("method", "auth.getSession");
        params.put("token", token.trim());
        params.put("api_key", apiKey);

        String sig = createLastFmSignature(params, apiSecret);
        params.put("api_sig", sig);
        params.put("format", "json");

        FormBody.Builder fb = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            fb.add(entry.getKey(), entry.getValue());
        }
        try {
            String respJson = SolarHttp.postForm(LASTFM_API_URL, fb.build());
            JSONObject json = new JSONObject(respJson);
            if (json.has("session")) {
                JSONObject session = json.getJSONObject("session");
                String sk = session.getString("key");
                String name = session.optString("name", "");
                prefs.edit()
                        .putString(PREF_LASTFM_SK, sk)
                        .putString(PREF_LASTFM_USERNAME, name)
                        .putBoolean(PREF_LASTFM_ENABLED, true)
                        .apply();
                return "Connected to Last.fm (" + name + ")";
            } else if (json.has("message")) {
                return json.getString("message");
            } else {
                return "Failed to parse Last.fm session";
            }
        } catch (Exception e) {
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static String createLastFmSignature(Map<String, String> params, String secret) {
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if ("format".equalsIgnoreCase(k) || "callback".equalsIgnoreCase(k)) continue;
            sb.append(k).append(params.get(k));
        }
        sb.append(secret);
        return md5Hex(sb.toString());
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
