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
        long existing = resumeFromBytes > 0 ? resumeFromBytes : (dest.isFile() ? dest.length() : 0L);
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA);
        if (existing > 0) rb.header("Range", "bytes=" + existing + "-");
        Response resp = executeDownload(rb.build());
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (resp.body() == null) throw new IOException("Empty body for " + urlStr);
            int code = resp.code();
            boolean append = code == 206 && existing > 0;
            if (code == 200 && existing > 0) {
                existing = 0;
                append = false;
            }
            in = resp.body().byteStream();
            out = new FileOutputStream(dest, append);
            long total = resp.body().contentLength();
            if (append && total > 0) total += existing;
            else if (total <= 0 && existing > 0 && append) total = existing;
            byte[] buf = new byte[8192];
            long read = append ? existing : 0;
            int n;
            boolean partialFired = append && read >= readyAfterBytes;
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
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (resp.body() != null) resp.body().close();
        }
    }

    private static Response executeDownload(Request req) throws IOException {
        OkHttpClient base = longReadClient();
        Response resp = base.newCall(req).execute();
        int code = resp.code();
        if (code == 206 || resp.isSuccessful()) return resp;
        resp.close();
        throw new IOException("HTTP " + code + " for " + req.url());
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
        if (urls == null || urls.length == 0) return false;
        OkHttpClient probe = TlsHelper.client().newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
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
        return isUrlReachable(url, 5, 8);
    }

    /** 2026-07-24 — Short-timeout reachability for YtApiLegacy direct_url failover. */
    public static boolean isUrlReachable(String url, int connectSec, int readSec) {
        if (url == null || url.isEmpty()) return false;
        OkHttpClient probe = TlsHelper.client().newBuilder()
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        return probeReachable(probe, url);
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
