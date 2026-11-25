package com.solar.launcher.youtube;

import android.content.Context;

import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.net.SolarHttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import android.os.Handler;
import android.os.Looper;

/**
 * 2026-07-06 — Saves YouTube streams to Videos/ or Music/ on user storage.
 * Layman: downloads a picked video or audio-only track into the right library folder.
 * Technical: YouTubeClient resolve then SolarHttp.downloadToFile on worker thread.
 * 2026-07-15 — Was NotPipe RESOLVE_STREAM IPC; now native backends.
 * 2026-07-15 — Byte progress + resolve-phase callbacks (was binary 0/1 only).
 * Reversal: delete; context save actions have no backend.
 */
public final class YouTubeDownloader {

    /**
     * 2026-07-15 — Save UI progress.
     * Layman: finding-stream vs downloading with a percent when the server tells size.
     * Was: onProgress(done,total) only 0/1 after download already started.
     */
    public interface Callback {
        /** phase "resolve" | "download"; percent 0–100 or -1 if unknown total. */
        void onProgress(String phase, int percent, long doneBytes, long totalBytes);
        void onComplete(File savedFile);
        void onError(String message);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private YouTubeDownloader() {}

    public static void saveVideo(final Context ctx, final YouTubeVideo video, final Callback cb) {
        save(ctx, video, false, false, cb);
    }

    public static void saveAudio(final Context ctx, final YouTubeVideo video, final Callback cb) {
        save(ctx, video, true, false, cb);
    }

    /**
     * 2026-07-16 — Play-only audio: app cache, not Music/YouTube library.
     * Layman: tapping Play on a search hit buffers for the queue; Save keeps a permanent copy.
     * Reversal: call {@link #saveAudio} from playYouTubeAudio again.
     */
    public static void cacheAudioForPlay(final Context ctx, final YouTubeVideo video,
            final Callback cb) {
        save(ctx, video, true, true, cb);
    }

    private static void save(final Context ctx, final YouTubeVideo video,
            final boolean audioOnly, final boolean playCacheOnly, final Callback cb) {
        if (video == null || video.id == null || video.id.isEmpty()) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("audioOnly", audioOnly);
                d.put("playCacheOnly", playCacheOnly);
                d.put("nullVideo", video == null);
                com.solar.launcher.Debug88eea4Log.log(ctx, "YouTubeDownloader.save",
                        "reject no video", "E", d);
            } catch (Exception ignored) {}
            // #endregion
            postError(cb, "no video");
            return;
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("audioOnly", audioOnly);
            d.put("playCacheOnly", playCacheOnly);
            d.put("videoId", video.id);
            d.put("titleLen", video.title != null ? video.title.length() : 0);
            com.solar.launcher.Debug88eea4Log.log(ctx, "YouTubeDownloader.save",
                    "save start", "A,D", d);
            // 2026-07-20 — 0b8f80 H3/H4: Save vs Play-cache for Hello hunt.
            String t = video.title != null ? video.title : "";
            boolean helloish = t.toLowerCase(java.util.Locale.US).indexOf("hello") >= 0
                    || t.toLowerCase(java.util.Locale.US).indexOf("lionel") >= 0
                    || t.toLowerCase(java.util.Locale.US).indexOf("ritchie") >= 0;
            com.solar.launcher.Debug0b8f80Log.log("YouTubeDownloader.save", "save start", "H3,H4",
                    "{\"audioOnly\":" + audioOnly
                            + ",\"playCacheOnly\":" + playCacheOnly
                            + ",\"helloish\":" + helloish
                            + ",\"titleLen\":" + t.length()
                            + ",\"id\":\"" + video.id.replace("\"", "") + "\"}");
        } catch (Exception ignored) {}
        // #endregion
        // Permanent library hits only for explicit Save — never promote Play into Music/YouTube.
        if (!playCacheOnly) {
            File existing = audioOnly
                    ? YouTubeSavePaths.findSavedAudio(ctx, video)
                    : YouTubeSavePaths.findSavedVideo(ctx, video);
            if (existing != null && existing.length() > 1024L) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("audioOnly", audioOnly);
                    d.put("path", existing.getAbsolutePath());
                    d.put("len", existing.length());
                    com.solar.launcher.Debug88eea4Log.log(ctx, "YouTubeDownloader.save",
                            "already on disk", "C", d);
                } catch (Exception ignored) {}
                // #endregion
                main.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onComplete(existing);
                    }
                });
                return;
            }
        } else {
            // Reuse existing play-cache file for this video id if still present.
            File playDir = YouTubePlayCache.dir(ctx);
            if (playDir != null) {
                File[] cached = playDir.listFiles();
                String idPrefix = video.id.replaceAll("[^A-Za-z0-9_-]", "") + "_";
                if (cached != null) {
                    for (int i = 0; i < cached.length; i++) {
                        File f = cached[i];
                        if (f.isFile() && f.length() > 1024L && f.getName().startsWith(idPrefix)) {
                            final File hit = f;
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (cb != null) cb.onComplete(hit);
                                }
                            });
                            return;
                        }
                    }
                }
            }
        }
        // 2026-07-15 — Show "Finding stream…" before resolve so Save doesn't look stuck.
        postProgress(cb, "resolve", 0, 0L, 0L);
        YouTubeClient client = YouTubeClient.getInstance(ctx);
        YouTubeClient.Callback resolveCb = new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                try {
                    YouTubeResultJson.StreamResult stream =
                            YouTubeResultJson.parseStreamResult(payloadJson);
                    if (stream == null || stream.url == null || stream.url.isEmpty()) {
                        // #region agent log
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("audioOnly", audioOnly);
                            d.put("payloadLen", payloadJson != null ? payloadJson.length() : 0);
                            com.solar.launcher.Debug88eea4Log.log(ctx,
                                    "YouTubeDownloader.resolveCb", "empty stream", "A", d);
                        } catch (Exception ignored) {}
                        // #endregion
                        postError(cb, "no stream");
                        return;
                    }
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("audioOnly", audioOnly);
                        d.put("playCacheOnly", playCacheOnly);
                        d.put("ext", stream.ext != null ? stream.ext : "");
                        d.put("urlPrefix", stream.url.length() > 80
                                ? stream.url.substring(0, 80) : stream.url);
                        com.solar.launcher.Debug88eea4Log.log(ctx,
                                "YouTubeDownloader.resolveCb", "resolve ok", "A,B", d);
                        com.solar.launcher.debug.Debug2241b1Log.log(
                                "YouTubeDownloader.resolveCb", "resolve ok", "YT1", "post-fix", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    final File dest;
                    if (playCacheOnly && audioOnly) {
                        dest = YouTubePlayCache.destAudioFile(ctx, video, stream.ext);
                    } else if (audioOnly) {
                        dest = YouTubeSavePaths.destAudioFile(ctx, video, stream.ext);
                    } else {
                        dest = YouTubeSavePaths.destVideoFile(ctx, video, stream.ext);
                    }
                    if (dest == null) {
                        postError(cb, "no dest");
                        return;
                    }
                    String quality = YouTubeClient.preferredVideoQuality();
                    downloadResolved(ctx, stream.url, dest, cb, video.id, quality, audioOnly, playCacheOnly);
                } catch (Exception e) {
                    postError(cb, e.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("audioOnly", audioOnly);
                    d.put("err", message != null ? message : "");
                    com.solar.launcher.Debug88eea4Log.log(ctx,
                            "YouTubeDownloader.resolveCb", "resolve error", "A,D", d);
                } catch (Exception ignored) {}
                // #endregion
                postError(cb, message);
            }
        };
        if (audioOnly) {
            client.resolveAudioStream(video.id, resolveCb);
        } else {
            client.resolveStream(video.id, resolveCb);
        }
    }

    private static void downloadResolved(final Context ctx, final String url, final File dest,
            final Callback cb, final String videoId, final String quality,
            final boolean audioOnly, final boolean playCacheOnly) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    postProgress(cb, "download", 0, 0L, 0L);
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("dest", dest.getName());
                        d.put("audioOnly", audioOnly);
                        d.put("playCacheOnly", playCacheOnly);
                        d.put("urlPrefix", url != null && url.length() > 80
                                ? url.substring(0, 80) : url);
                        com.solar.launcher.Debug88eea4Log.log(ctx,
                                "YouTubeDownloader.downloadResolved", "http start", "B", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    if (!audioOnly) {
                        // Permanent video save: use progressive cache download, then copy to Videos/YouTube.
                        final AtomicLong lastUiMs = new AtomicLong(0L);
                        File cached = YouTubeProgressiveCache.download(ctx, url, videoId, quality, null,
                                new YouTubeProgressiveCache.Progress() {
                                    @Override
                                    public void onProgress(int percent, long done, long total) {
                                        long now = System.currentTimeMillis();
                                        if (now - lastUiMs.get() < 200L && done < total) return;
                                        lastUiMs.set(now);
                                        postProgress(cb, "download", Math.max(0, Math.min(100, percent)), done, total);
                                    }
                                });
                        if (cached == null || !cached.isFile()) {
                            throw new IOException("progressive cache failed");
                        }
                        copyFile(cached, dest);
                    } else {
                        final AtomicLong lastUiMs = new AtomicLong(0L);
                        SolarHttp.downloadToFile(url, dest, new SolarHttp.DownloadProgress() {
                            @Override
                            public void onProgress(long bytesRead, long totalBytes) {
                                long now = System.currentTimeMillis();
                                if (now - lastUiMs.get() < 200L && bytesRead < totalBytes) return;
                                lastUiMs.set(now);
                                int pct = totalBytes > 0
                                        ? (int) Math.min(100L, (bytesRead * 100L) / totalBytes)
                                        : -1;
                                postProgress(cb, "download", pct, bytesRead, totalBytes);
                            }
                        });
                    }
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("dest", dest.getName());
                        d.put("len", dest.length());
                        d.put("runId", "post-fix");
                        com.solar.launcher.Debug88eea4Log.log(ctx,
                                "YouTubeDownloader.downloadResolved", "http done", "B", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    // #region agent log
                    try {
                        String p = dest.getAbsolutePath();
                        boolean underMusicYt = p.indexOf("/Music/YouTube/") >= 0;
                        boolean underPlayCache = p.indexOf("/" + YouTubePlayCache.DIR_NAME + "/") >= 0
                                || p.indexOf("youtube") >= 0 && !underMusicYt;
                        com.solar.launcher.Debug0b8f80Log.log(
                                "YouTubeDownloader.downloadResolved", "http done", "H3,H4",
                                "{\"len\":" + dest.length()
                                        + ",\"underMusicYt\":" + underMusicYt
                                        + ",\"underPlayCache\":" + underPlayCache
                                        + ",\"name\":\"" + dest.getName().replace("\"", "") + "\"}");
                    } catch (Exception ignored) {}
                    // #endregion
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (cb != null) {
                                cb.onProgress("download", 100, dest.length(), dest.length());
                                cb.onComplete(dest);
                            }
                        }
                    });
                } catch (Exception e) {
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("err", e.getMessage() != null ? e.getMessage() : "");
                        com.solar.launcher.Debug88eea4Log.log(ctx,
                                "YouTubeDownloader.downloadResolved", "http fail", "B", d);
                        com.solar.launcher.Debug0b8f80Log.log(
                                "YouTubeDownloader.downloadResolved", "http fail", "H3",
                                "{\"err\":\"" + (e.getMessage() != null
                                        ? e.getMessage().replace("\"", "") : "") + "\"}");
                    } catch (Exception ignored) {}
                    // #endregion
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static void postProgress(final Callback cb, final String phase, final int percent,
            final long done, final long total) {
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onProgress(phase, percent, done, total);
            }
        });
    }

    private static void postError(final Callback cb, final String msg) {
        // #region agent log
        // 2026-07-20 — 0b8f80 H3: resolve/download error surface (Hello may never land).
        try {
            com.solar.launcher.Debug0b8f80Log.log("YouTubeDownloader.postError", "save error", "H3",
                    "{\"err\":\"" + (msg != null ? msg.replace("\"", "") : "error") + "\"}");
        } catch (Exception ignored) {}
        // #endregion
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onError(msg != null ? msg : "error");
            }
        });
    }

    /** Sanitize title for filesystem — shared with save path helpers. */
    static String safeName(String raw) {
        return YouTubeSavePaths.safeName(raw);
    }
}
