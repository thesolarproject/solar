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
}
