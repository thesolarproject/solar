package com.solar.launcher.youtube.api;

import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-15 — YtAPILegacy host (github.com/ZendoMusic/yt-api-legacy).
 * Layman: older PHP-style frontend that can hand out 480p (and higher) direct URLs.
 * Technical: /get_search_videos.php, /direct_url?quality=, comments via get-ytvideo-info.
 * Reversal: drop HQ ladder; stay on Invidious/Piped 360 only.
 */
public final class YtApiLegacyBackend implements YoutubeBackend {

    private static final String UA = "SolarLauncher/YouTube";
    private final String baseUrl;

    public YtApiLegacyBackend(String baseUrl) {
        this.baseUrl = YoutubeApiUtil.trimSlash(baseUrl);
    }

    @Override
    public String getName() {
        return "YtApiLegacy";
    }

    @Override
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    @Override
    public boolean supportsVideo360() {
        return true;
    }

    @Override
    public boolean supportsHqVideo() {
        return true;
    }

    @Override
    public List<YouTubeVideo> getPopularVideos() throws IOException {
        String body = httpGet(baseUrl + "/get_top_videos.php");
        try {
            JSONArray arr = new JSONArray(body);
            List<YouTubeVideo> out = new ArrayList<YouTubeVideo>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                out.add(new YouTubeVideo(
                        j.optString("video_id", ""),
                        j.optString("title", ""),
                        j.optString("author", ""),
                        j.optString("duration", "")));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("YtApiLegacy popular parse: " + e.getMessage(), e);
        }
    }

    @Override
    public List<YouTubeVideo> search(String query) throws IOException {
        String url = baseUrl + "/get_search_videos.php?query="
                + YoutubeApiUtil.urlEncode(query);
        String body = httpGet(url);
        try {
            JSONArray json = new JSONArray(body);
            List<YouTubeVideo> out = new ArrayList<YouTubeVideo>();
            for (int i = 0; i < json.length(); i++) {
                JSONObject j = json.getJSONObject(i);
                String id = j.optString("video_id", "");
                if (id.length() == 0) continue;
                out.add(new YouTubeVideo(
                        id,
                        j.optString("title", ""),
                        j.optString("author", ""),
                        j.optString("duration", "")));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("YtApiLegacy search parse: " + e.getMessage(), e);
        }
    }

    @Override
    public List<YouTubeComment> getComments(String videoId) throws IOException {
        String url = baseUrl + "/get-ytvideo-info.php?video_id="
                + YoutubeApiUtil.urlEncode(videoId);
        try {
            String body = httpGet(url);
            JSONArray arr = new JSONObject(body).optJSONArray("comments");
            List<YouTubeComment> out = new ArrayList<YouTubeComment>();
            if (arr == null) return out;
            int max = Math.min(arr.length(), 80);
            for (int i = 0; i < max; i++) {
                JSONObject j = arr.getJSONObject(i);
                out.add(new YouTubeComment(
                        j.optString("author", ""),
                        j.optString("text", "")));
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<YouTubeComment>();
        }
    }

    @Override
    public String getVideoUrl(String videoId, String quality) throws IOException {
        String q = (quality != null && quality.length() > 0) ? quality : "360";
        // 2026-07-15 — Server redirects to progressive file; SolarHttp/IJK follow later.
        String url = baseUrl + "/direct_url?video_id=" + YoutubeApiUtil.urlEncode(videoId)
                + "&quality=" + YoutubeApiUtil.urlEncode(q);
        // 2026-07-24 — Constructed URL is not proof host is alive; dead ytapilegacy seeds
        // caused resolve to "succeed" then download fail. Probe before returning.
        if (!SolarHttp.isUrlReachable(url, 4, 6)) {
            throw new IOException("YtApiLegacy direct_url unreachable: " + baseUrl);
        }
        return url;
    }

    @Override
    public AudioStream resolveAudio(String videoId) {
        return null;
    }

    /** 2026-07-19 — Quick timeouts so search failover stays under YouTubeClient budget. */
    private static String httpGet(String url) throws IOException {
        return new String(SolarHttp.getBytesQuick(url, "application/json", UA, 3, 6), "UTF-8");
    }
}
