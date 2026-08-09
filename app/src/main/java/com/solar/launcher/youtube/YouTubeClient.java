package com.solar.launcher.youtube;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.Debug712c71Log;
import com.solar.launcher.youtube.api.InstancePool;
import com.solar.launcher.youtube.api.InstancesConfig;
import com.solar.launcher.youtube.api.InstancesUpdater;
import com.solar.launcher.youtube.api.YoutubeBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-15 — Async native YouTube façade (replaces NotPipeClient broadcast IPC).
 * Layman: Solar asks its own backends for search, popular, comments, and stream links.
 * Technical: worker pool + main-thread Callback; JSON payloads match YouTubeResultJson.
 * Reversal: restore NotPipeClient + SolarNotPipeBridge + bundled notPipe APK.
 */
public final class YouTubeClient {

    public interface Callback {
        void onSuccess(String payloadJson);
        void onError(String message);
    }

    private static final long DEFAULT_TIMEOUT_MS = 22000L;
    private static final long RESOLVE_TIMEOUT_MS = 28000L;
    private static final long PROBE_TIMEOUT_MS = 3000L;

    private static volatile YouTubeClient sInstance;

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final InstancePool pool;

    private YouTubeClient(Context ctx) {
        appCtx = ctx.getApplicationContext();
        pool = new InstancePool(appCtx);
        // 2026-07-15 — Refresh instance list in background; fail-open to seeds.
        executor.execute(new Runnable() {
            @Override
            public void run() {
                InstancesUpdater.updateIfStale(appCtx, false);
                pool.reload(appCtx);
            }
        });
    }

    public static YouTubeClient getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (YouTubeClient.class) {
                if (sInstance == null) {
                    sInstance = new YouTubeClient(ctx);
                }
            }
        }
        return sInstance;
    }

    /** Preferred progressive quality for this device. */
    public static String preferredVideoQuality() {
        return YouTubeQuality.preferredVideoQuality();
    }

    /** Next lower quality after a failed resolve / IJK error, or null if none. */
    public static String fallbackVideoQuality(String failedQuality) {
        return YouTubeQuality.fallbackVideoQuality(failedQuality);
    }

    /** Liveness — pool has at least one backend (optionally force instance refresh). */
    public void probe(final Callback cb) {
        runTimed(PROBE_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                InstancesUpdater.updateIfStale(appCtx, false);
                pool.reload(appCtx);
                if (pool.size() < 1) throw new Exception("no instances");
                return "{\"version\":\"native\"}";
            }
        });
    }

    public void fetchPopular(final Callback cb) {
        runTimed(DEFAULT_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                return videosToJson(pool.getPopularVideos());
            }
        });
    }

    public void search(final String query, final Callback cb) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("query", query != null ? query : "");
            d.put("poolSize", pool.size());
            d.put("timeoutMs", DEFAULT_TIMEOUT_MS);
            Debug712c71Log.log(appCtx, "YouTubeClient.search", "search start", "A", d);
        } catch (Exception ignored) {}
        // #endregion
        runTimed(DEFAULT_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                String q = query != null ? query : "";
                // Do not let the first search race the background catalog refresh.
                // This is especially important on existing Y2 installs whose old
                // build stamped the built-in seed list as a completed refresh.
                InstancesConfig cfg = new InstancesConfig(appCtx);
                cfg.ensureSeeds();
                if (cfg.isRemoteRefreshPending()) {
                    InstancesUpdater.updateIfStale(appCtx, false);
                    pool.reload(appCtx);
                }
                List<YouTubeVideo> videos = pool.search(q);
                String json = videosToJson(videos);
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("query", query != null ? query : "");
                    d.put("n", videos != null ? videos.size() : -1);
                    d.put("jsonLen", json != null ? json.length() : 0);
                    Debug712c71Log.log(appCtx, "YouTubeClient.search",
                            "search work done", "B", d);
                } catch (Exception ignored) {}
                // #endregion
                return json;
            }
        });
    }

    public void resolveStream(String videoId, Callback cb) {
        resolveStream(videoId, preferredVideoQuality(), cb);
    }

    public void resolveStream(final String videoId, final String quality, final Callback cb) {
        final String q = (quality != null && quality.length() > 0)
                ? quality : preferredVideoQuality();
        runTimed(RESOLVE_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                com.solar.launcher.youtube.api.InstancePool.StreamPick pick =
                        pool.getVideoUrlPick(videoId, q);
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("videoId", videoId != null ? videoId : "");
                    d.put("reqQuality", q);
                    d.put("backend", pick.backend);
                    d.put("qualityUsed", pick.qualityUsed);
                    d.put("urlPrefix", pick.url != null && pick.url.length() > 96
                            ? pick.url.substring(0, 96) : pick.url);
                    d.put("isDirectUrlApi", pick.url != null
                            && pick.url.indexOf("/direct_url") >= 0);
                    d.put("isVideoplayback", pick.url != null
                            && pick.url.indexOf("videoplayback") >= 0);
                    d.put("isRelative", pick.url != null && pick.url.startsWith("/"));
                    com.solar.launcher.Debug9d82a5Log.log(appCtx,
                            "YouTubeClient.resolveStream", "stream resolved", "A", d);
                } catch (Exception ignored) {}
                // #endregion
                return streamJson(pick.url, videoId, guessExt(pick.url, "mp4"));
            }
        });
    }

    public void resolveAudioStream(final String videoId, final Callback cb) {
        runTimed(RESOLVE_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                YoutubeBackend.AudioStream a = pool.resolveAudio(videoId);
                return streamJson(a.url, videoId, a.ext);
            }
        });
    }

    public void fetchComments(final String videoId, final Callback cb) {
        runTimed(DEFAULT_TIMEOUT_MS, cb, new Work() {
            @Override
            public String call() throws Exception {
                return commentsToJson(pool.getComments(videoId));
            }
        });
    }

    private void runTimed(final long timeoutMs, final Callback cb, final Work work) {
        if (cb == null) return;
        final Object gate = new Object();
        final boolean[] done = new boolean[] { false };
        // #region agent log
        final long timedT0 = System.currentTimeMillis();
        // #endregion
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (gate) {
                    if (done[0]) return;
                    done[0] = true;
                }
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("timeoutMs", timeoutMs);
                    d.put("elapsedMs", System.currentTimeMillis() - timedT0);
                    Debug712c71Log.log(appCtx, "YouTubeClient.runTimed",
                            "client timeout fired", "A", d);
                } catch (Exception ignored) {}
                // #endregion
                cb.onError("timeout");
            }
        }, timeoutMs);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String json = work.call();
                    synchronized (gate) {
                        if (done[0]) {
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("jsonLen", json != null ? json.length() : 0);
                                d.put("elapsedMs", System.currentTimeMillis() - timedT0);
                                Debug712c71Log.log(appCtx, "YouTubeClient.runTimed",
                                        "success discarded after timeout", "D", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            return;
                        }
                        done[0] = true;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSuccess(json);
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : "error";
                    synchronized (gate) {
                        if (done[0]) {
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("err", msg);
                                d.put("elapsedMs", System.currentTimeMillis() - timedT0);
                                Debug712c71Log.log(appCtx, "YouTubeClient.runTimed",
                                        "error discarded after timeout", "D", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            return;
                        }
                        done[0] = true;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(msg);
                        }
                    });
                }
            }
        });
    }

    static String videosToJson(List<YouTubeVideo> videos) throws Exception {
        JSONArray arr = new JSONArray();
        if (videos != null) {
            for (int i = 0; i < videos.size(); i++) {
                YouTubeVideo v = videos.get(i);
                if (v == null) continue;
                JSONObject o = new JSONObject();
                o.put("id", v.id != null ? v.id : "");
                o.put("title", v.title != null ? v.title : "");
                o.put("author", v.author != null ? v.author : "");
                o.put("length", v.duration != null ? v.duration : "");
                arr.put(o);
            }
        }
        return arr.toString();
    }

    static String commentsToJson(List<YouTubeComment> comments) throws Exception {
        JSONArray arr = new JSONArray();
        if (comments != null) {
            for (int i = 0; i < comments.size(); i++) {
                YouTubeComment c = comments.get(i);
                if (c == null) continue;
                JSONObject o = new JSONObject();
                o.put("author", c.author != null ? c.author : "");
                o.put("content", c.content != null ? c.content : "");
                arr.put(o);
            }
        }
        return arr.toString();
    }

    private static String streamJson(String url, String videoId, String ext) throws Exception {
        JSONObject o = new JSONObject();
        o.put("url", url != null ? url : "");
        o.put("videoId", videoId != null ? videoId : "");
        o.put("ext", ext != null ? ext : "mp4");
        return o.toString();
    }

    private static String guessExt(String url, String fallback) {
        if (url == null) return fallback;
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) return fallback;
        String ext = path.substring(dot + 1).toLowerCase();
        return ext.length() > 6 ? fallback : ext;
    }

    private interface Work {
        String call() throws Exception;
    }
}
