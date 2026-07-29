package com.solar.launcher.net;

import com.solar.launcher.debug.AgentDebugLog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** ponytail: all app HTTPS via TlsHelper OkHttp — podcasts, themes, OTA, art lookup. */
public final class SolarHttp {
    private static final String DEFAULT_UA = "SolarLauncher/1.0";

    private SolarHttp() {}

    public static byte[] getBytes(String urlStr) throws IOException {
        return getBytes(urlStr, null, DEFAULT_UA);
    }

    public static byte[] getBytes(String urlStr, String accept, String userAgent) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request.Builder b = new Request.Builder().url(urlStr);
        b.header("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
        if (accept != null && !accept.isEmpty()) b.header("Accept", accept);
        Response resp = execute(b.build());
        try {
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    /**
     * 2026-07-19 — Short connect/read for YouTube instance failover.
     * Layman: give up on a dead frontend quickly so the next one can answer search.
     * Tech: default TlsHelper client is 20s/60s — one dead host ate YouTubeClient's 12s budget.
     * Reversal: call {@link #getBytes(String, String, String)} only.
     */
    public static byte[] getBytesQuick(String urlStr, String accept, String userAgent,
            int connectTimeoutSec, int readTimeoutSec) throws IOException {
        TlsHelper.ensureSecurityProvider();
        int connect = connectTimeoutSec > 0 ? connectTimeoutSec : 3;
        int read = readTimeoutSec > 0 ? readTimeoutSec : 6;
        OkHttpClient client = TlsHelper.client().newBuilder()
                .connectTimeout(connect, TimeUnit.SECONDS)
                .readTimeout(read, TimeUnit.SECONDS)
                .writeTimeout(connect, TimeUnit.SECONDS)
                .build();
        Request.Builder b = new Request.Builder().url(urlStr);
        b.header("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
        if (accept != null && !accept.isEmpty()) b.header("Accept", accept);
        Response resp = client.newCall(b.build()).execute();
        try {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " for " + urlStr);
            }
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
            else resp.close();
        }
    }

    /** Theme gallery assets — many small files in one session; use extended read timeout. */
    public static byte[] getBytesTheme(String urlStr, String accept, String userAgent) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request.Builder b = new Request.Builder().url(urlStr);
        b.header("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
        if (accept != null && !accept.isEmpty()) b.header("Accept", accept);
        OkHttpClient client = longReadClient();
        Response resp = client.newCall(b.build()).execute();
        if (!resp.isSuccessful()) {
            int code = resp.code();
            resp.close();
            throw new IOException("HTTP " + code + " for " + urlStr);
        }
        try {
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    /** Try URLs in order — https first, then http fallback for legacy feeds. */
    public static byte[] getBytesFirstOk(String[] urls, String accept, String userAgent) throws IOException {
        IOException last = null;
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            try {
                return getBytes(url, accept, userAgent);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("All URLs failed");
    }

    public interface DownloadProgress {
        void onProgress(long bytesRead, long totalBytes);
    }

    /** Fired once when {@code bytesRead} first reaches {@code readyAfterBytes}. File still growing. */
    public interface PartialReadyListener {
        void onPartialReady(File dest, long bytesRead);
    }

    public static void downloadToFile(String urlStr, File dest) throws IOException {
        downloadToFile(urlStr, dest, null, 0L, null, null);
    }

    public static void downloadToFile(String urlStr, File dest, DownloadProgress progress) throws IOException {
        downloadToFile(urlStr, dest, progress, 0L, null, null);
    }

    public static void downloadToFile(String urlStr, File dest, DownloadProgress progress,
            long readyAfterBytes, PartialReadyListener partialReady,
            java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        downloadToFile(urlStr, dest, progress, readyAfterBytes, partialReady, cancel, 0L);
    }

    /** @param resumeFromBytes append with Range when &gt; 0 and dest already has data */
    public static void downloadToFile(String urlStr, File dest, DownloadProgress progress,
            long readyAfterBytes, PartialReadyListener partialReady,
            java.util.concurrent.atomic.AtomicBoolean cancel, long resumeFromBytes) throws IOException {
        TlsHelper.ensureSecurityProvider();
        if (dest == null) throw new IOException("Download destination is unavailable");
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create download directory");
        }
        if (cancel != null && cancel.get()) throw new IOException("Download cancelled");

        // FileOutputStream append always writes at EOF. Never request an offset that differs from
        // the actual durable length, even if a stale caller supplied one.
        long existing = dest.isFile() ? dest.length() : 0L;
        Response resp = executeDownload(buildDownloadRequest(urlStr, existing));
        InputStream in = null;
        FileOutputStream out = null;
        try {
            int code = resp.code();
            ContentRange contentRange = parseContentRange(resp.header("Content-Range"));

            if (code == 416 && existing > 0 && contentRange != null
                    && contentRange.unsatisfied && contentRange.total == existing) {
                if (progress != null) progress.onProgress(existing, existing);
                if (partialReady != null && readyAfterBytes > 0 && existing >= readyAfterBytes) {
                    partialReady.onPartialReady(dest, existing);
                }
                return;
            }

            boolean validResume = code == 206 && existing > 0 && contentRange != null
                    && !contentRange.unsatisfied && contentRange.start == existing;
            boolean usableFreshResponse = code == 200
                    || (code == 206 && existing == 0 && contentRange != null
                    && !contentRange.unsatisfied && contentRange.start == 0);
            if (existing > 0 && !validResume && code != 200) {
                // A mismatched/missing Content-Range would splice unrelated bytes. Retry once
                // without Range and overwrite only after a valid fresh response is open.
                resp.close();
                resp = executeDownload(buildDownloadRequest(urlStr, 0L));
                code = resp.code();
                contentRange = parseContentRange(resp.header("Content-Range"));
                usableFreshResponse = code == 200
                        || (code == 206 && contentRange != null
                        && !contentRange.unsatisfied && contentRange.start == 0);
                if (!usableFreshResponse) {
                    throw new IOException("Server did not provide a safe resumable response");
                }
                existing = 0;
            }
            if (existing == 0 && !usableFreshResponse) {
                throw new IOException("Server did not provide a complete download response");
            }
            if (resp.body() == null) throw new IOException("Download response has no body");

            boolean append = validResume && existing > 0;
            if (!append) existing = 0;
            in = resp.body().byteStream();
            out = new FileOutputStream(dest, append);
            long bodyLength = resp.body().contentLength();
            long total = contentRange != null && !contentRange.unsatisfied
                    && contentRange.total > 0 ? contentRange.total : -1L;
            if (total <= 0 && bodyLength >= 0) {
                total = append ? safeAdd(existing, bodyLength) : bodyLength;
            }
            byte[] buf = new byte[8192];
            long read = append ? existing : 0;
            int n;
            boolean partialFired = false;
            if (progress != null) progress.onProgress(read, total);
            if (partialReady != null && readyAfterBytes > 0 && read >= readyAfterBytes) {
                partialFired = true;
                partialReady.onPartialReady(dest, read);
            }
            while ((n = in.read(buf)) != -1) {
                if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
                out.write(buf, 0, n);
                read += n;
                if (progress != null) progress.onProgress(read, total);
                if (!partialFired && partialReady != null && readyAfterBytes > 0 && read >= readyAfterBytes) {
                    partialFired = true;
                    partialReady.onPartialReady(dest, read);
                }
            }
            out.flush();
            if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
            if (total >= 0 && read != total) {
                throw new IOException("Incomplete download: " + read + " of " + total
                        + " bytes saved");
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            resp.close();
        }
    }

    private static Request buildDownloadRequest(String urlStr, long existing) {
        Request.Builder builder = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept-Encoding", "identity");
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-");
        return builder.build();
    }

    private static Response executeDownload(Request req) throws IOException {
        OkHttpClient base = longReadClient();
        Response resp = base.newCall(req).execute();
        int code = resp.code();
        if (code == 416 || code == 206 || resp.isSuccessful()) return resp;
        resp.close();
        throw new IOException("HTTP " + code + " while downloading");
    }

    static final class ContentRange {
        final long start;
        final long end;
        final long total;
        final boolean unsatisfied;

        ContentRange(long start, long end, long total, boolean unsatisfied) {
            this.start = start;
            this.end = end;
            this.total = total;
            this.unsatisfied = unsatisfied;
        }
    }

    static ContentRange parseContentRange(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.regionMatches(true, 0, "bytes ", 0, 6)) return null;
        String rangeAndTotal = value.substring(6).trim();
        int slash = rangeAndTotal.indexOf('/');
        if (slash <= 0 || slash == rangeAndTotal.length() - 1) return null;
        String range = rangeAndTotal.substring(0, slash).trim();
        long total = parseNonNegativeLong(rangeAndTotal.substring(slash + 1).trim());
        if (total < 0) return null;
        if ("*".equals(range)) return new ContentRange(-1L, -1L, total, true);
        int dash = range.indexOf('-');
        if (dash <= 0 || dash == range.length() - 1) return null;
        long start = parseNonNegativeLong(range.substring(0, dash).trim());
        long end = parseNonNegativeLong(range.substring(dash + 1).trim());
        if (start < 0 || end < start || (total > 0 && end >= total)) return null;
        return new ContentRange(start, end, total, false);
    }

    private static long parseNonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static long safeAdd(long a, long b) throws IOException {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) {
            throw new IOException("Download size overflow");
        }
        return a + b;
    }

    public static String getText(String urlStr) throws IOException {
        return new String(getBytes(urlStr), "UTF-8");
    }

    public static byte[] postJson(String urlStr, String jsonBody, String userAgent,
            String registryToken) throws IOException {
        // 2026-07-15 — Reach directory still uses X-Reach-Token; diag uses postJsonAuth.
        return postJsonAuth(urlStr, jsonBody, userAgent, "X-Reach-Token", registryToken);
    }

    /**
     * 2026-07-15 — JSON POST with a named auth header (Reach token vs Solar diag ingest token).
     * Layman: same secure pipe as other Solar HTTPS, just a different password sticker.
     */
    public static byte[] postJsonAuth(String urlStr, String jsonBody, String userAgent,
            String authHeaderName, String authHeaderValue) throws IOException {
        TlsHelper.ensureSecurityProvider();
        okhttp3.MediaType json = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json, jsonBody != null ? jsonBody : "{}");
        Request.Builder b = new Request.Builder().url(urlStr).post(body);
        b.header("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
        b.header("Content-Type", "application/json; charset=utf-8");
        if (authHeaderName != null && !authHeaderName.isEmpty()
                && authHeaderValue != null && !authHeaderValue.isEmpty()) {
            b.header(authHeaderName, authHeaderValue);
        }
        Response resp = execute(b.build());
        try {
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    public static byte[] getBytes(String urlStr, String accept, String userAgent,
            String registryToken) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request.Builder b = new Request.Builder().url(urlStr);
        b.header("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
        if (accept != null && !accept.isEmpty()) b.header("Accept", accept);
        if (registryToken != null && !registryToken.isEmpty()) {
            b.header("X-Reach-Token", registryToken);
        }
        Response resp = execute(b.build());
        try {
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    /** HEAD then tiny ranged GET — true if any URL variant is reachable (TLS/HTTP). */
    public static boolean probeAnyReachable(String[] urls) {
        return probeAnyReachableQuick(urls, 5, 8);
    }

    /**
     * Bounded endpoint probe for an explicit diagnostics action. Callers choose short limits;
     * normal download/read clients keep their existing timeouts.
     */
    public static boolean probeAnyReachableQuick(String[] urls,
            int connectTimeoutSeconds, int readTimeoutSeconds) {
        if (urls == null || urls.length == 0) return false;
        int connectSeconds = Math.max(1, connectTimeoutSeconds);
        int readSeconds = Math.max(1, readTimeoutSeconds);
        OkHttpClient probe = TlsHelper.client().newBuilder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .writeTimeout(connectSeconds, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            if (probeReachable(probe, url)) return true;
        }
        return false;
    }

    /** True if a single URL answers HEAD/ranged GET (YouTube CDN stream preflight). */
    public static boolean isUrlReachable(String url) {
        if (url == null || url.isEmpty()) return false;
        return probeAnyReachable(new String[]{url});
    }

    private static boolean probeReachable(OkHttpClient client, String urlStr) {
        TlsHelper.ensureSecurityProvider();
        Request head = new Request.Builder().url(urlStr).head()
                .header("User-Agent", DEFAULT_UA).build();
        try {
            Response resp = client.newCall(head).execute();
            try {
                if (isReachableStatus(resp.code())) return true;
            } finally {
                resp.close();
            }
        } catch (IOException ignored) {}
        Request get = new Request.Builder().url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .header("Range", "bytes=0-1")
                .build();
        try {
            Response resp = client.newCall(get).execute();
            try {
                return isReachableStatus(resp.code());
            } finally {
                resp.close();
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static boolean isReachableStatus(int code) {
        return (code >= 200 && code < 400) || code == 416;
    }

    private static Response execute(Request req) throws IOException {
        OkHttpClient base = TlsHelper.client();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("url", req.url().toString());
            d.put("scheme", req.url().scheme());
            d.put("connectionSpecs", "RESTRICTED_TLS-only via TlsHelper");
            AgentDebugLog.log("SolarHttp.execute", "A", "OkHttp execute", d);
        } catch (Exception ignored) {}
        // #endregion
        Call call = base.newCall(req);
        try {
            Response resp = call.execute();
            if (!resp.isSuccessful()) {
                int code = resp.code();
                resp.close();
                throw new IOException("HTTP " + code + " for " + req.url());
            }
            return resp;
        } catch (IOException e) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("url", req.url().toString());
                d.put("scheme", req.url().scheme());
                d.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                AgentDebugLog.log("SolarHttp.execute", "A", "OkHttp execute failed", d);
            } catch (Exception ignored) {}
            // #endregion
            throw e;
        }
    }

    /** Long downloads (OTA APK) with extended read timeout. */
    public static OkHttpClient longReadClient() {
        return TlsHelper.client().newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    public static InputStream openStream(String urlStr) throws IOException {
        OkHttpClient c = longReadClient();
        Request req = new Request.Builder().url(urlStr).header("User-Agent", DEFAULT_UA).build();
        Response resp = c.newCall(req).execute();
        if (!resp.isSuccessful() || resp.body() == null) {
            int code = resp.code();
            resp.close();
            throw new IOException("HTTP " + code + " for " + urlStr);
        }
        return resp.body().byteStream();
    }

    public static String postForm(String urlStr, okhttp3.FormBody body) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request req = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .post(body)
                .build();
        Response resp = TlsHelper.client().newCall(req).execute();
        String bodyStr = "";
        try {
            bodyStr = resp.body() != null ? resp.body().string() : "";
        } finally {
            if (resp.body() != null) resp.body().close();
        }
        if (!resp.isSuccessful()) {
            throw new IOException("HTTP " + resp.code() + " for " + urlStr + ": " + bodyStr);
        }
        return bodyStr;
    }

    public static String postJson(String urlStr, String json, String authToken) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), json));
        if (authToken != null && !authToken.isEmpty()) {
            rb.header("Authorization", "Token " + authToken);
        }
        Response resp = TlsHelper.client().newCall(rb.build()).execute();
        String bodyStr = "";
        try {
            bodyStr = resp.body() != null ? resp.body().string() : "";
        } finally {
            if (resp.body() != null) resp.body().close();
        }
        if (!resp.isSuccessful()) {
            throw new IOException("HTTP " + resp.code() + " for " + urlStr + ": " + bodyStr);
        }
        return bodyStr;
    }
}
