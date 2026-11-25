package com.solar.launcher.youtube.api;

import com.solar.launcher.net.SolarHttp;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * 2026-11-25 — NewPipeExtractor downloader backed by SolarHttp/OkHttp3.
 * Layman: lets NewPipe fetch YouTube pages/APIs using the app's network stack.
 */
public final class SolarNewPipeDownloader extends Downloader {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static volatile SolarNewPipeDownloader instance;

    private SolarNewPipeDownloader() {}

    public static SolarNewPipeDownloader getInstance() {
        if (instance == null) {
            synchronized (SolarNewPipeDownloader.class) {
                if (instance == null) instance = new SolarNewPipeDownloader();
            }
        }
        return instance;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String method = request.httpMethod();
        String url = request.url();
        Map<String, List<String>> headers = request.headers();
        byte[] data = request.dataToSend();

        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url);
        rb.header("User-Agent", UA);
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String name = e.getKey();
                if ("User-Agent".equalsIgnoreCase(name)) {
                    rb.removeHeader(name);
                } else {
                    rb.removeHeader(name);
                }
                for (int i = 0; i < e.getValue().size(); i++) {
                    String v = e.getValue().get(i);
                    if (v != null) rb.addHeader(name, v);
                }
            }
        }

        RequestBody body = null;
        if (data != null) {
            String ct = headerFirst(headers, "Content-Type");
            MediaType mt = ct != null ? MediaType.parse(ct) : MediaType.parse("application/octet-stream");
            body = RequestBody.create(mt, data);
        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            body = RequestBody.create(MediaType.parse("application/octet-stream"), new byte[0]);
        }

        if ("POST".equalsIgnoreCase(method)) rb.post(body);
        else if ("PUT".equalsIgnoreCase(method)) rb.put(body);
        else if ("DELETE".equalsIgnoreCase(method)) rb.delete(body);
        else if ("HEAD".equalsIgnoreCase(method)) rb.head();
        else rb.get();

        okhttp3.Response resp = SolarHttp.longReadClient().newCall(rb.build()).execute();
        try {
            String respBody = resp.body() != null ? resp.body().string() : "";
            String latestUrl = resp.request().url().toString();
            Map<String, List<String>> respHeaders = resp.headers().toMultimap();
            int code = resp.code();
            String msg = resp.message();
            if (code == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }
            return new Response(code, msg, respHeaders, respBody, latestUrl);
        } finally {
            resp.close();
        }
    }

    private static String headerFirst(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }
}
