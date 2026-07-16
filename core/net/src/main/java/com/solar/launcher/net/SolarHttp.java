package com.solar.launcher.net;

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

    /**
     * Result of a completed file download — byte counts for integrity checks.
     * {@code expectedTotal} is &lt;= 0 when the server omitted Content-Length.
     */
    public static final class DownloadResult {
        public final long bytesWritten;
        public final long expectedTotal;

        public DownloadResult(long bytesWritten, long expectedTotal) {
            this.bytesWritten = bytesWritten;
            this.expectedTotal = expectedTotal;
        }
    }

    /** @param resumeFromBytes append with Range when &gt; 0 and dest already has data */
    public static void downloadToFile(String urlStr, File dest, DownloadProgress progress,
            long readyAfterBytes, PartialReadyListener partialReady,
            java.util.concurrent.atomic.AtomicBoolean cancel, long resumeFromBytes) throws IOException {
        downloadToFileResult(urlStr, dest, progress, readyAfterBytes, partialReady, cancel, resumeFromBytes);
    }

    /**
     * Long downloads (OTA APK). Always requests identity encoding so Content-Length
     * matches the raw body (gzip mismatch used to stick progress near 98%).
     */
    public static DownloadResult downloadToFileResult(String urlStr, File dest,
            DownloadProgress progress, long readyAfterBytes, PartialReadyListener partialReady,
            java.util.concurrent.atomic.AtomicBoolean cancel, long resumeFromBytes) throws IOException {
        TlsHelper.ensureSecurityProvider();
        long existing = resumeFromBytes > 0 ? resumeFromBytes : (dest.isFile() ? dest.length() : 0L);
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept-Encoding", "identity")
                .header("Accept", "application/vnd.android.package-archive,*/*");
        if (existing > 0) rb.header("Range", "bytes=" + existing + "-");
        Response resp = executeDownload(rb.build());
        InputStream in = null;
        FileOutputStream out = null;
        long total = -1L;
        long read = 0L;
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
            total = resp.body().contentLength();
            if (append && total > 0) total += existing;
            else if (total <= 0 && existing > 0 && append) total = existing;
            byte[] buf = new byte[8192];
            read = append ? existing : 0;
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
            out.flush();
            return new DownloadResult(read, total);
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
        Call call = base.newCall(req);
        Response resp = call.execute();
        if (!resp.isSuccessful()) {
            int code = resp.code();
            resp.close();
            throw new IOException("HTTP " + code + " for " + req.url());
        }
        return resp;
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

    /** Last.fm auth / scrobble form posts. */
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

    /** ListenBrainz (and similar) JSON posts. {@code authToken} → Authorization: Token …. */
    public static String postJson(String urlStr, String json, String authToken) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", DEFAULT_UA)
                .post(okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), json));
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
