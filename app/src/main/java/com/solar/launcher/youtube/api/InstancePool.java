package com.solar.launcher.youtube.api;

import android.content.Context;

import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-07-16 — Failover across YouTube backends, aligned with notPipe Manager.
 * 2026-07-19 — Metadata (search/popular): prefer YtApi first; short HTTP + cooldown on dead hosts.
 * Layman: try a known-good search host first; skip frontends that just timed out.
 * Was: shuffle all hosts with 20s OkHttp — one dead site burned the 12s search budget.
 * Reversal: plain shuffled() for runMetadata without cooldown / YtApi-first.
 */
public final class InstancePool {

    private static final Random RANDOM = new Random();
    /** 2026-07-19 — Skip hosts that failed recently so search can reach a live one. */
    private static final long HOST_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final ConcurrentHashMap<String, Long> sCoolUntilElapsed =
            new ConcurrentHashMap<String, Long>();

    private final Object lock = new Object();
    private List<YoutubeBackend> all = new ArrayList<YoutubeBackend>();

    public InstancePool(Context ctx) {
        reload(ctx);
    }

    /** Rebuild backends from prefs (after InstancesUpdater). */
    public void reload(Context ctx) {
        if (ctx == null) return;
        InstancesConfig cfg = new InstancesConfig(ctx);
        cfg.ensureSeeds();
        List<YoutubeBackend> next = new ArrayList<YoutubeBackend>();
        List<String> inv = cfg.getInvidious();
        for (int i = 0; i < inv.size(); i++) {
            next.add(new InvidiousBackend(inv.get(i)));
        }
        List<String> piped = cfg.getPiped();
        for (int i = 0; i < piped.size(); i++) {
            // Piped entries may be "api,proxy" like notPipe Config (parsed in PipedBackend).
            String raw = piped.get(i);
            if (raw == null || raw.length() == 0) continue;
            next.add(new PipedBackend(raw));
        }
        // 2026-08-01 — yt2009 video-stream backends (no metadata/search).
        List<String> y29 = cfg.getYt2009();
        for (int i = 0; i < y29.size(); i++) {
            String raw = y29.get(i);
            if (raw == null || raw.length() == 0) continue;
            next.add(new Yt2009Backend(raw));
        }
        List<String> yt = cfg.getYtApiLegacy();
        for (int i = 0; i < yt.size(); i++) {
            next.add(new YtApiLegacyBackend(yt.get(i)));
        }
        synchronized (lock) {
            all = next;
        }
    }

    public int size() {
        synchronized (lock) {
            return all.size();
        }
    }

    public List<YouTubeVideo> getPopularVideos() throws IOException {
        return runMetadata(new MetaOp() {
            @Override
            public List<YouTubeVideo> run(YoutubeBackend b) throws IOException {
                return b.getPopularVideos();
            }
        });
    }

    public List<YouTubeVideo> search(final String query) throws IOException {
        return runMetadata(new MetaOp() {
            @Override
            public List<YouTubeVideo> run(YoutubeBackend b) throws IOException {
                return b.search(query);
            }
        });
    }

    public List<YouTubeComment> getComments(final String videoId) throws IOException {
        IOException last = null;
        for (YoutubeBackend b : failoverOrder(false)) {
            try {
                List<YouTubeComment> c = b.getComments(videoId);
                if (c != null) {
                    markHostOk(b);
                    return c;
                }
            } catch (IOException e) {
                markHostFailed(b);
                last = e;
            }
        }
        if (last != null) throw last;
        return new ArrayList<YouTubeComment>();
    }

    public String getVideoUrl(String videoId, String quality) throws IOException {
        StreamPick pick = getVideoUrlPick(videoId, quality);
        return pick.url;
    }

    /**
     * notPipe Manager.getVideoUrl: for "360" use all 360 backends (YtApi + Inv + Piped);
     * for 480/720 use only HQ backends (YtApi). Return first successful URL — no HEAD check.
     */
    public StreamPick getVideoUrlPick(String videoId, String quality) throws IOException {
        final String q = (quality != null && quality.length() > 0) ? quality : "360";
        boolean lowTier = "360".equals(q) || "240".equals(q) || "144".equals(q);
        List<YoutubeBackend> order = streamOrder(lowTier);
        IOException last = null;
        for (int i = 0; i < order.size(); i++) {
            YoutubeBackend b = order.get(i);
            try {
                // Invidious ignores quality (always ~360 muxed); YtApi honors quality= param.
                String ask = lowTier && "YtApiLegacy".equals(b.getName()) ? q : q;
                String url = b.getVideoUrl(videoId, ask);
                if (url != null && url.length() > 0) {
                    markHostOk(b);
                    android.util.Log.i("SolarYouTube", "stream ok backend=" + b.getName()
                            + " q=" + q + " url=" + (url.length() > 96 ? url.substring(0, 96) : url));
                    return new StreamPick(url, b.getName(), q);
                }
            } catch (IOException e) {
                markHostFailed(b);
                android.util.Log.w("SolarYouTube", "stream fail backend=" + b.getName()
                        + " q=" + q + " err=" + e.getMessage());
                last = e;
            }
        }
        // HQ quality failed — notPipe falls through only on HQ list; we also try 360 once.
        if (!lowTier) {
            try {
                return getVideoUrlPick(videoId, "360");
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no stream backends");
    }

    /**
     * notPipe: 360 → videoInstances (all); HQ → hqInstances only.
     * Always put YtApiLegacy first (deterministic progressive MP4 that works on Y1).
     */
    private List<YoutubeBackend> streamOrder(boolean lowTier) {
        List<YoutubeBackend> ytapi = new ArrayList<YoutubeBackend>();
        List<YoutubeBackend> others = new ArrayList<YoutubeBackend>();
        synchronized (lock) {
            for (int i = 0; i < all.size(); i++) {
                YoutubeBackend b = all.get(i);
                if (b == null) continue;
                if ("YtApiLegacy".equals(b.getName())) {
                    ytapi.add(b);
                } else if (lowTier) {
                    // 360 pool: any that supports progressive 360
                    if (b.supportsVideo360() || b.supportsHqVideo()) others.add(b);
                } else {
                    // HQ pool: only progressive-quality backends (YtApi / rich hosts)
                    if (b.supportsHqVideo() && !"Invidious".equals(b.getName())) {
                        // Invidious is 360-muxed only in notPipe (Manager never puts it in hqInstances).
                        others.add(b);
                    } else if (b.supportsHqVideo() && "Invidious".equals(b.getName())) {
                        // skip Invidious for 480/720
                    } else if (b.supportsHqVideo()) {
                        others.add(b);
                    }
                }
            }
        }
        Collections.shuffle(ytapi, RANDOM);
        Collections.shuffle(others, RANDOM);
        List<YoutubeBackend> order = new ArrayList<YoutubeBackend>(ytapi);
        order.addAll(others);
        return order;
    }

    public static final class StreamPick {
        public final String url;
        public final String backend;
        public final String qualityUsed;

        public StreamPick(String url, String backend, String qualityUsed) {
            this.url = url;
            this.backend = backend != null ? backend : "";
            this.qualityUsed = qualityUsed != null ? qualityUsed : "";
        }
    }

    public YoutubeBackend.AudioStream resolveAudio(String videoId) throws IOException {
        IOException last = null;
        // Prefer Invidious/Piped (YtApi has no audio); skip cooling hosts first.
        for (YoutubeBackend b : failoverOrder(true)) {
            if ("YtApiLegacy".equals(b.getName())) continue;
            try {
                YoutubeBackend.AudioStream a = b.resolveAudio(videoId);
                if (a != null && a.url != null && a.url.length() > 0) {
                    markHostOk(b);
                    return a;
                }
            } catch (IOException e) {
                markHostFailed(b);
                last = e;
            }
        }
        throw last != null ? last : new IOException("no audio backends");
    }

    /**
     * 2026-07-19 — Search/popular order: YtApi first (stable Adele/Hello ranking), then others.
     * Cooling (recently failed) hosts go last so one dead Invidious does not eat the budget.
     * Package-visible for unit tests.
     */
    List<YoutubeBackend> metadataOrder() {
        return failoverOrder(true);
    }

    /**
     * @param preferYtApiFirst true for search/popular/audio failover; false = shuffle-only with cool last
     */
    private List<YoutubeBackend> failoverOrder(boolean preferYtApiFirst) {
        List<YoutubeBackend> ytapi = new ArrayList<YoutubeBackend>();
        List<YoutubeBackend> warm = new ArrayList<YoutubeBackend>();
        List<YoutubeBackend> cool = new ArrayList<YoutubeBackend>();
        synchronized (lock) {
            for (int i = 0; i < all.size(); i++) {
                YoutubeBackend b = all.get(i);
                if (b == null) continue;
                if (preferYtApiFirst && "YtApiLegacy".equals(b.getName())) {
                    if (isHostCooling(b)) cool.add(b);
                    else ytapi.add(b);
                } else if (isHostCooling(b)) {
                    cool.add(b);
                } else {
                    warm.add(b);
                }
            }
        }
        Collections.shuffle(ytapi, RANDOM);
        Collections.shuffle(warm, RANDOM);
        Collections.shuffle(cool, RANDOM);
        List<YoutubeBackend> order = new ArrayList<YoutubeBackend>(ytapi.size() + warm.size() + cool.size());
        order.addAll(ytapi);
        order.addAll(warm);
        order.addAll(cool);
        return order;
    }

    private interface MetaOp {
        List<YouTubeVideo> run(YoutubeBackend b) throws IOException;
    }

    private List<YouTubeVideo> runMetadata(MetaOp op) throws IOException {
        IOException last = null;
        // #region agent log
        long metaT0 = System.currentTimeMillis();
        int tried = 0;
        int emptyN = 0;
        int failN = 0;
        // #endregion
        for (YoutubeBackend b : metadataOrder()) {
            // #region agent log
            tried++;
            long oneT0 = System.currentTimeMillis();
            boolean cooling = isHostCooling(b);
            // #endregion
            try {
                List<YouTubeVideo> v = op.run(b);
                if (v != null && !v.isEmpty()) {
                    markHostOk(b);
                    android.util.Log.i("SolarYouTube", "meta ok backend=" + b.getName()
                            + " host=" + b.getHost() + " n=" + v.size());
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("backend", b.getName());
                        d.put("host", b.getHost() != null ? b.getHost() : "");
                        d.put("n", v.size());
                        d.put("ms", System.currentTimeMillis() - oneT0);
                        d.put("tried", tried);
                        d.put("coolingAtStart", cooling);
                        d.put("totalMs", System.currentTimeMillis() - metaT0);
                        com.solar.launcher.Debug712c71Log.log(
                                "InstancePool.runMetadata", "meta ok", "B", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    return v;
                }
                // #region agent log
                emptyN++;
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("backend", b.getName());
                    d.put("host", b.getHost() != null ? b.getHost() : "");
                    d.put("n", v == null ? -1 : v.size());
                    d.put("ms", System.currentTimeMillis() - oneT0);
                    d.put("tried", tried);
                    d.put("coolingAtStart", cooling);
                    com.solar.launcher.Debug712c71Log.log(
                            "InstancePool.runMetadata", "meta empty skip", "B", d);
                } catch (Exception ignored) {}
                // #endregion
            } catch (IOException e) {
                markHostFailed(b);
                android.util.Log.w("SolarYouTube", "meta fail backend=" + b.getName()
                        + " host=" + b.getHost() + " err=" + e.getMessage());
                // #region agent log
                failN++;
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("backend", b.getName());
                    d.put("host", b.getHost() != null ? b.getHost() : "");
                    d.put("err", e.getMessage() != null ? e.getMessage() : "");
                    d.put("ms", System.currentTimeMillis() - oneT0);
                    d.put("tried", tried);
                    d.put("coolingAtStart", cooling);
                    com.solar.launcher.Debug712c71Log.log(
                            "InstancePool.runMetadata", "meta fail", "A", d);
                } catch (Exception ignored) {}
                // #endregion
                last = e;
            }
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("tried", tried);
            d.put("emptyN", emptyN);
            d.put("failN", failN);
            d.put("totalMs", System.currentTimeMillis() - metaT0);
            d.put("willThrow", last != null);
            d.put("lastErr", last != null && last.getMessage() != null
                    ? last.getMessage() : "");
            com.solar.launcher.Debug712c71Log.log(
                    "InstancePool.runMetadata", "meta exhausted", "A", d);
        } catch (Exception ignored) {}
        // #endregion
        if (last != null) throw last;
        return new ArrayList<YouTubeVideo>();
    }

    /** 2026-07-19 — True while this host should be tried only after warm ones. */
    static boolean isHostCooling(YoutubeBackend b) {
        if (b == null) return false;
        String host = b.getHost();
        if (host == null || host.length() == 0) return false;
        Long until = sCoolUntilElapsed.get(host);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until.longValue()) {
            sCoolUntilElapsed.remove(host);
            return false;
        }
        return true;
    }

    static void markHostFailed(YoutubeBackend b) {
        if (b == null) return;
        String host = b.getHost();
        if (host == null || host.length() == 0) return;
        sCoolUntilElapsed.put(host, Long.valueOf(System.currentTimeMillis() + HOST_COOLDOWN_MS));
    }

    static void markHostOk(YoutubeBackend b) {
        if (b == null) return;
        String host = b.getHost();
        if (host == null || host.length() == 0) return;
        sCoolUntilElapsed.remove(host);
    }

    /** Test hook — clear cooldowns between JVM unit tests. */
    static void clearHostCooldownsForTest() {
        sCoolUntilElapsed.clear();
    }

    /**
     * Test hook — YtApi hosts appear before others when not cooling.
     * Layman: Hello search should hit the stable PHP frontend before random Invidious.
     */
    static boolean metadataOrderPutsYtApiFirstForTest(List<String> namesInOrder) {
        if (namesInOrder == null || namesInOrder.isEmpty()) return false;
        int firstYt = -1;
        int firstOther = -1;
        for (int i = 0; i < namesInOrder.size(); i++) {
            String n = namesInOrder.get(i);
            if ("YtApiLegacy".equals(n)) {
                if (firstYt < 0) firstYt = i;
            } else if (firstOther < 0) {
                firstOther = i;
            }
        }
        if (firstYt < 0) return true; // no ytapi in pool — N/A pass
        if (firstOther < 0) return true;
        return firstYt < firstOther;
    }
}
