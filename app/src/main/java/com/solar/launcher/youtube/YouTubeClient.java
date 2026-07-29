package com.solar.launcher.youtube;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.ConnectivityHelper;
import com.solar.launcher.youtube.official.YouTubeOfficialApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async facade for official YouTube Data API v3 metadata.
 *
 * Solar deliberately exposes no YouTube audiovisual stream resolver. Metadata
 * results lead only to bookmarks, authorized-provider searches, and a displayed
 * canonical URL.
 */
public final class YouTubeClient {

    public interface Callback {
        void onSuccess(String payloadJson);
        void onError(String message);
    }

    private static final long DEFAULT_TIMEOUT_MS = 22_000L;
    private static final long PROBE_TIMEOUT_MS = 3_000L;
    private static final long SEARCH_FRESH_MS = 6L * 60L * 60L * 1000L;
    private static final long POPULAR_FRESH_MS = 2L * 60L * 60L * 1000L;
    private static final long COMMENTS_FRESH_MS = 30L * 60L * 1000L;
    private static final long ACCOUNT_SIGNALS_FRESH_MS = 12L * 60L * 60L * 1000L;
    private static final long DISCOVER_TIMEOUT_MS = 38_000L;
    private static final long MAX_STALE_MS = 30L * 24L * 60L * 60L * 1000L;
    public static final String ACQUISITION_BLOCKED = "metadata_only";

    private static volatile YouTubeClient instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    // Bounded for the Y1: at most two API requests can consume heap/sockets.
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final YouTubeOfficialApi api;
    private final YouTubeMetadataCache cache;
    private final YouTubeQuotaTracker quota;
    private final Random retryJitter = new Random();
    private final Context appContext;
    private final SharedPreferences settings;

    private YouTubeClient(Context context) {
        appContext = context.getApplicationContext();
        settings = appContext.getSharedPreferences(
                YouTubeDiscoverSettings.PREFS_NAME, Context.MODE_PRIVATE);
        api = new YouTubeOfficialApi(appContext);
        cache = new YouTubeMetadataCache(appContext);
        cache.setMaxBytes(YouTubeDiscoverSettings.cacheBytes(settings));
        quota = new YouTubeQuotaTracker(appContext);
    }

    public static YouTubeClient getInstance(Context context) {
        if (instance == null) {
            synchronized (YouTubeClient.class) {
                if (instance == null) instance = new YouTubeClient(context);
            }
        }
        return instance;
    }

    /** Retained for source compatibility with the established video player. */
    public static String preferredVideoQuality() {
        return YouTubeQuality.preferredVideoQuality();
    }

    /** Retained for local-video callers; remote YouTube resolution is disabled. */
    public static String fallbackVideoQuality(String failedQuality) {
        return YouTubeQuality.fallbackVideoQuality(failedQuality);
    }

    public void probe(final Callback callback) {
        runTimed(PROBE_TIMEOUT_MS, callback, new Work() {
            @Override
            public String call() throws Exception {
                if (!api.isConfigured()) throw new Exception("youtube_setup_required");
                JSONObject result = new JSONObject();
                result.put("version", "official-data-api-v3");
                result.put("metadataOnly", true);
                return result.toString();
            }
        });
    }

    public void fetchPopular(Callback callback) {
        fetchPopular("", callback);
    }

    public void fetchPopular(final String pageToken, final Callback callback) {
        runTimed(DEFAULT_TIMEOUT_MS, callback, new Work() {
            @Override
            public String call() throws Exception {
                String region = deviceRegion();
                return cached("popular:" + region + ":" + nonNull(pageToken),
                        POPULAR_FRESH_MS, YouTubeQuotaTracker.Operation.POPULAR,
                        new Work() {
                            @Override
                            public String call() throws Exception {
                                return pageToJson(api.popular(deviceRegion(), pageToken));
                            }
                        });
            }
        });
    }

    public void search(String query, Callback callback) {
        search(query, "", callback);
    }

    public void search(final String query, final String pageToken,
            final Callback callback) {
        runTimed(DEFAULT_TIMEOUT_MS, callback, new Work() {
            @Override
            public String call() throws Exception {
                String region = deviceRegion();
                String normalized = nonNull(query).trim().toLowerCase(Locale.US);
                return cached("search:" + region + ":" + normalized + ":"
                                + nonNull(pageToken),
                        SEARCH_FRESH_MS, YouTubeQuotaTracker.Operation.SEARCH,
                        new Work() {
                            @Override
                            public String call() throws Exception {
                                return pageToJson(api.search(query, pageToken, deviceRegion()));
                            }
                        });
            }
        });
    }

    /**
     * Explicit policy boundary. The official Data API does not expose media
     * streams, and Solar will not fall back to scraping/front-end instances.
     */
    public void resolveStream(String videoId, Callback callback) {
        postPolicyError(callback);
    }

    public void resolveStream(String videoId, String quality, Callback callback) {
        postPolicyError(callback);
    }

    public void resolveAudioStream(String videoId, Callback callback) {
        postPolicyError(callback);
    }

    public void fetchComments(final String videoId, final Callback callback) {
        runTimed(DEFAULT_TIMEOUT_MS, callback, new Work() {
            @Override
            public String call() throws Exception {
                return cached("comments:" + nonNull(videoId), COMMENTS_FRESH_MS,
                        YouTubeQuotaTracker.Operation.COMMENTS, new Work() {
                            @Override
                            public String call() throws Exception {
                                return commentsToJson(api.comments(videoId));
                            }
                        });
            }
        });
    }

    /**
     * Fetches only read-only account metadata used by Solar's local ranker.
     * Regional candidates are loaded separately so the feed can render while
     * these two low-cost account requests finish.
     */
    public void fetchDiscoverSignals(final Callback callback) {
        runTimed(DISCOVER_TIMEOUT_MS, callback, new Work() {
            @Override
            public String call() throws Exception {
                if (!api.hasAccount()) {
                    return discoverSignalsToJson(null, null, false,
                            false, false);
                }
                List<String> channels = new java.util.ArrayList<String>();
                List<YouTubeVideo> liked = new java.util.ArrayList<YouTubeVideo>();
                boolean stale = false;
                boolean partial = false;

                try {
                    String subscriptions = cachedSingleAttempt("discover:subscriptions",
                            ACCOUNT_SIGNALS_FRESH_MS,
                            YouTubeQuotaTracker.Operation.SUBSCRIPTIONS,
                            new Work() {
                                @Override
                                public String call() throws Exception {
                                    return subscriptionsToJson(api.subscriptions());
                                }
                            });
                    channels.addAll(parseSubscriptionsJson(subscriptions));
                    stale |= YouTubeResultJson.parseCacheState(subscriptions).stale;
                } catch (Exception ignored) {
                    partial = true;
                }

                try {
                    String likedPayload = cachedSingleAttempt("discover:liked",
                            ACCOUNT_SIGNALS_FRESH_MS,
                            YouTubeQuotaTracker.Operation.LIKED_VIDEOS,
                            new Work() {
                                @Override
                                public String call() throws Exception {
                                    return videosToJson(api.likedVideos());
                                }
                            });
                    liked.addAll(YouTubeResultJson.parseVideos(likedPayload));
                    stale |= YouTubeResultJson.parseCacheState(likedPayload).stale;
                } catch (Exception ignored) {
                    partial = true;
                }
                return discoverSignalsToJson(channels, liked, true, stale, partial);
            }
        });
    }

    public int estimatedQuotaToday() {
        return quota.todayTotal();
    }

    public void clearMetadataCache() {
        cache.clear();
    }

    public void setMetadataCacheBytes(long bytes) {
        cache.setMaxBytes(bytes);
    }

    private void postPolicyError(final Callback callback) {
        if (callback == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(ACQUISITION_BLOCKED);
            }
        });
    }

    private String cached(String key, long freshForMs,
            YouTubeQuotaTracker.Operation operation, Work network) throws Exception {
        YouTubeMetadataCache.Hit hit = cache.get(key, freshForMs, MAX_STALE_MS);
        if (hit != null && !hit.stale) {
            return annotateCache(hit.payload, true, false, hit.ageMs);
        }
        if (hit != null && !ConnectivityHelper.isOnline(appContext)) {
            return annotateCache(hit.payload, true, true, hit.ageMs);
        }
        if (hit == null && !ConnectivityHelper.isOnline(appContext)) {
            throw new Exception("network_unavailable");
        }
        try {
            // One refresh attempt is enough when a usable stale result exists.
            String payload = hit != null
                    ? callOnce(operation, network)
                    : callWithRetry(operation, network);
            cache.put(key, payload);
            return annotateCache(payload, false, false, 0L);
        } catch (Exception networkFailure) {
            if (hit != null) {
                return annotateCache(hit.payload, true, true, hit.ageMs);
            }
            throw networkFailure;
        }
    }

    private String cachedSingleAttempt(String key, long freshForMs,
            YouTubeQuotaTracker.Operation operation, Work network) throws Exception {
        YouTubeMetadataCache.Hit hit = cache.get(key, freshForMs, MAX_STALE_MS);
        if (hit != null && !hit.stale) {
            return annotateCache(hit.payload, true, false, hit.ageMs);
        }
        if (hit != null && !ConnectivityHelper.isOnline(appContext)) {
            return annotateCache(hit.payload, true, true, hit.ageMs);
        }
        if (hit == null && !ConnectivityHelper.isOnline(appContext)) {
            throw new Exception("network_unavailable");
        }
        try {
            String payload = callOnce(operation, network);
            cache.put(key, payload);
            return annotateCache(payload, false, false, 0L);
        } catch (Exception networkFailure) {
            if (hit != null) {
                return annotateCache(hit.payload, true, true, hit.ageMs);
            }
            throw networkFailure;
        }
    }

    private String callWithRetry(YouTubeQuotaTracker.Operation operation,
            Work network) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= YouTubeRetryPolicy.MAX_ATTEMPTS; attempt++) {
            quota.record(operation);
            try {
                return network.call();
            } catch (Exception error) {
                last = error;
                if (!YouTubeRetryPolicy.shouldRetry(error, attempt)) throw error;
                try {
                    Thread.sleep(YouTubeRetryPolicy.delayMs(attempt, retryJitter));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Exception("network_interrupted");
                }
            }
        }
        throw last != null ? last : new Exception("youtube_error");
    }

    private String callOnce(YouTubeQuotaTracker.Operation operation,
            Work network) throws Exception {
        quota.record(operation);
        return network.call();
    }

    static String annotateCache(String payload, boolean cached, boolean stale,
            long ageMs) throws Exception {
        String clean = payload != null ? payload.trim() : "";
        JSONObject root;
        if (clean.startsWith("[")) {
            root = new JSONObject();
            root.put("items", new JSONArray(clean));
        } else {
            root = clean.length() > 0 ? new JSONObject(clean) : new JSONObject();
        }
        JSONObject source = new JSONObject();
        source.put("cached", cached);
        source.put("stale", stale);
        source.put("ageMs", Math.max(0L, ageMs));
        root.put("_solarCache", source);
        return root.toString();
    }

    private void runTimed(final long timeoutMs, final Callback callback,
            final Work work) {
        if (callback == null) return;
        final Object gate = new Object();
        final boolean[] done = new boolean[] { false };
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (gate) {
                    if (done[0]) return;
                    done[0] = true;
                }
                callback.onError("timeout");
            }
        }, timeoutMs);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String payload = work.call();
                    synchronized (gate) {
                        if (done[0]) return;
                        done[0] = true;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(payload);
                        }
                    });
                } catch (Exception error) {
                    final String message = safeMessage(error);
                    synchronized (gate) {
                        if (done[0]) return;
                        done[0] = true;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(message);
                        }
                    });
                }
            }
        });
    }

    static String pageToJson(YouTubeOfficialApi.Page page) throws Exception {
        JSONObject result = new JSONObject();
        result.put("items", videosArray(page != null ? page.videos : null));
        result.put("nextPageToken", page != null ? page.nextPageToken : "");
        return result.toString();
    }

    /** Legacy array shape retained for migration tests and cached old results. */
    static String videosToJson(List<YouTubeVideo> videos) throws Exception {
        return videosArray(videos).toString();
    }

    private static JSONArray videosArray(List<YouTubeVideo> videos) throws Exception {
        JSONArray array = new JSONArray();
        if (videos == null) return array;
        for (int i = 0; i < videos.size(); i++) {
            YouTubeVideo video = videos.get(i);
            if (video == null) continue;
            JSONObject item = new JSONObject();
            item.put("id", nonNull(video.id));
            item.put("title", nonNull(video.title));
            item.put("author", nonNull(video.author));
            item.put("length", nonNull(video.duration));
            item.put("description", nonNull(video.description));
            array.put(item);
        }
        return array;
    }

    static String commentsToJson(List<YouTubeComment> comments) throws Exception {
        JSONArray array = new JSONArray();
        if (comments == null) return array.toString();
        for (int i = 0; i < comments.size(); i++) {
            YouTubeComment comment = comments.get(i);
            if (comment == null) continue;
            JSONObject item = new JSONObject();
            item.put("author", nonNull(comment.author));
            item.put("content", nonNull(comment.content));
            array.put(item);
        }
        return array.toString();
    }

    static String subscriptionsToJson(List<String> channels) throws Exception {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        if (channels != null) {
            for (String channel : channels) {
                String clean = nonNull(channel).trim();
                if (clean.length() > 0) array.put(clean);
            }
        }
        root.put("channels", array);
        return root.toString();
    }

    static List<String> parseSubscriptionsJson(String payload) throws Exception {
        List<String> out = new java.util.ArrayList<String>();
        JSONObject root = new JSONObject(nonNull(payload));
        JSONArray array = root.optJSONArray("channels");
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (value.length() > 0 && !out.contains(value)) out.add(value);
        }
        return out;
    }

    static String discoverSignalsToJson(List<String> channels,
            List<YouTubeVideo> liked, boolean accountConnected, boolean stale,
            boolean partial) throws Exception {
        JSONObject root = new JSONObject();
        JSONArray subscriptions = new JSONArray();
        if (channels != null) {
            for (String channel : channels) subscriptions.put(nonNull(channel));
        }
        root.put("subscribedChannels", subscriptions);
        root.put("likedVideos", videosArray(liked));
        root.put("accountConnected", accountConnected);
        root.put("stale", stale);
        root.put("partial", partial);
        return root.toString();
    }

    private String deviceRegion() {
        return YouTubeDiscoverSettings.effectiveRegion(
                settings, Locale.getDefault().getCountry());
    }

    private static String safeMessage(Exception error) {
        String message = error != null ? error.getMessage() : "";
        if (message == null || message.trim().length() == 0) return "youtube_error";
        // API helpers return only stable reason codes; never surface response bodies/tokens.
        return message.length() <= 80 ? message : "youtube_error";
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private interface Work {
        String call() throws Exception;
    }
}
