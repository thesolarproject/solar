package com.solar.launcher.podcast;

import com.solar.launcher.podcast.BuildConfig;
import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * PodcastIndex.org search — same directory as rss.com/tools/find-my-feed.
 * ponytail: optional; set PODCAST_INDEX_API_KEY + PODCAST_INDEX_API_SECRET in gradle.properties.
 */
final class PodcastIndexClient {
    private static final String API = "https://api.podcastindex.org/api/1.0";

    private PodcastIndexClient() {}

    static boolean isConfigured() {
        return !isEmpty(BuildConfig.PODCAST_INDEX_API_KEY)
                && !isEmpty(BuildConfig.PODCAST_INDEX_API_SECRET);
    }

    static List<OpenRssClient.Podcast> searchByTerm(String term, int limit) throws Exception {
        List<OpenRssClient.Podcast> out = new ArrayList<OpenRssClient.Podcast>();
        if (!isConfigured()) return out;
        String q = term == null ? "" : term.trim();
        if (q.isEmpty()) return out;
        if (limit < 1) limit = 10;
        if (limit > 50) limit = 50;
        String path = "/search/byterm?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&max=" + limit;
        byte[] raw = apiGet(path);
        JSONObject root = new JSONObject(new String(raw, "UTF-8"));
        JSONArray feeds = root.optJSONArray("feeds");
        if (feeds == null) return out;
        for (int i = 0; i < feeds.length() && out.size() < limit; i++) {
            JSONObject f = feeds.optJSONObject(i);
            if (f == null) continue;
            String title = f.optString("title", "").trim();
            String author = f.optString("author", "").trim();
            String url = f.optString("url", "").trim();
            String image = f.optString("image", "").trim();
            if (title.isEmpty() || url.isEmpty()) continue;
            out.add(new OpenRssClient.Podcast(title, author, url, image.isEmpty() ? null : image));
        }
        return out;
    }

    /** SHA-1 hex(apiKey + apiSecret + epochSec) — PodcastIndex auth. */
    static String authHash(String apiKey, String apiSecret, long epochSec) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest((apiKey + apiSecret + epochSec).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] apiGet(String path) throws IOException {
        TlsHelper.ensureSecurityProvider();
        long epoch = System.currentTimeMillis() / 1000L;
        String hash;
        try {
            hash = authHash(BuildConfig.PODCAST_INDEX_API_KEY, BuildConfig.PODCAST_INDEX_API_SECRET, epoch);
        } catch (Exception e) {
            throw new IOException("PodcastIndex auth failed", e);
        }
        Request req = new Request.Builder()
                .url(API + path)
                .header("User-Agent", OpenRssClient.UA)
                .header("X-Auth-Date", Long.toString(epoch))
                .header("X-Auth-Key", BuildConfig.PODCAST_INDEX_API_KEY)
                .header("Authorization", hash)
                .build();
        OkHttpClient client = TlsHelper.client();
        Response resp = client.newCall(req).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                int code = resp.code();
                throw new IOException("PodcastIndex HTTP " + code);
            }
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
