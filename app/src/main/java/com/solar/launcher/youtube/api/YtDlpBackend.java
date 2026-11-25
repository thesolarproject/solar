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
 * 2026-11-25 — yt-dlp sidecar backend.
 * Layman: asks a user-run yt-dlp HTTP server for playable URLs and downloads.
 * Technical: small JSON-over-HTTP contract; falls back to other backends for search/comments if unsupported.
 * Reversal: remove from InstancesConfig/InstancePool and use only Invidious/Piped/YtApi backends.
 */
public final class YtDlpBackend implements YoutubeBackend {

    private static final String UA = "SolarLauncher/yt-dlp";
    private final String baseUrl;

    public YtDlpBackend(String baseUrl) {
        this.baseUrl = YoutubeApiUtil.trimSlash(baseUrl);
    }

    @Override
    public String getName() {
        return "YtDlp";
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
        String body = tryGetJson(baseUrl + "/popular");
        if (body == null) body = tryGetJson(baseUrl + "/api/popular");
        if (body == null) throw new IOException("YtDlp popular unsupported");
        return parseVideoList(body);
    }

    @Override
    public List<YouTubeVideo> search(String query) throws IOException {
        String q = YoutubeApiUtil.urlEncode(query != null ? query : "");
        String body = tryGetJson(baseUrl + "/search?q=" + q);
        if (body == null) body = tryGetJson(baseUrl + "/api/search?q=" + q);
        if (body == null) throw new IOException("YtDlp search unsupported");
        return parseVideoList(body);
    }

    @Override
    public List<YouTubeComment> getComments(String videoId) throws IOException {
        String id = YoutubeApiUtil.urlEncode(videoId != null ? videoId : "");
        String body = tryGetJson(baseUrl + "/comments?videoId=" + id);
        if (body == null) body = tryGetJson(baseUrl + "/api/comments?videoId=" + id);
        if (body == null) throw new IOException("YtDlp comments unsupported");
        try {
            JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body)
                    : new JSONObject(body).optJSONArray("comments");
            List<YouTubeComment> out = new ArrayList<YouTubeComment>();
            if (arr == null) return out;
            int max = Math.min(arr.length(), 80);
            for (int i = 0; i < max; i++) {
                JSONObject j = arr.getJSONObject(i);
                out.add(new YouTubeComment(j.optString("author", ""), j.optString("text", "")));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("YtDlp comments parse: " + e.getMessage(), e);
        }
    }

    @Override
    public String getVideoUrl(String videoId, String quality) throws IOException {
        String q = (quality != null && quality.length() > 0) ? quality : "360";
        String id = YoutubeApiUtil.urlEncode(videoId != null ? videoId : "");
        String[] urls = new String[] {
                baseUrl + "/resolve?videoId=" + id + "&quality=" + YoutubeApiUtil.urlEncode(q),
                baseUrl + "/api/resolve?videoId=" + id + "&quality=" + YoutubeApiUtil.urlEncode(q),
                baseUrl + "/stream?videoId=" + id + "&quality=" + YoutubeApiUtil.urlEncode(q)
        };
        IOException last = null;
        for (int i = 0; i < urls.length; i++) {
            try {
                String body = httpGet(urls[i]);
                String url = parseResolvedUrl(body);
                if (url != null && url.length() > 0) return url;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("YtDlp no video url");
    }

    @Override
    public AudioStream resolveAudio(String videoId) throws IOException {
        String id = YoutubeApiUtil.urlEncode(videoId != null ? videoId : "");
        String[] urls = new String[] {
                baseUrl + "/resolve?videoId=" + id + "&audioOnly=1",
                baseUrl + "/api/resolve?videoId=" + id + "&audioOnly=1",
                baseUrl + "/audio?videoId=" + id
        };
        IOException last = null;
        for (int i = 0; i < urls.length; i++) {
            try {
                String body = httpGet(urls[i]);
                AudioStream a = parseResolvedAudio(body);
                if (a != null && a.url != null && a.url.length() > 0) return a;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("YtDlp no audio url");
    }

    private static List<YouTubeVideo> parseVideoList(String body) throws IOException {
        try {
            JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body)
                    : new JSONObject(body).optJSONArray("videos");
            if (arr == null) arr = new JSONObject(body).optJSONArray("items");
            List<YouTubeVideo> out = new ArrayList<YouTubeVideo>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                String id = j.optString("video_id", j.optString("id", j.optString("videoId", "")));
                if (id.length() == 0) continue;
                out.add(new YouTubeVideo(
                        id,
                        j.optString("title", ""),
                        j.optString("author", j.optString("uploader", j.optString("channel", ""))),
                        j.optString("duration", j.optString("length", ""))));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("YtDlp list parse: " + e.getMessage(), e);
        }
    }

    private static String parseResolvedUrl(String body) throws IOException {
        try {
            JSONObject o = new JSONObject(body);
            String url = o.optString("url", "");
            if (url.length() == 0) url = o.optString("videoUrl", "");
            if (url.length() == 0) url = o.optString("streamUrl", "");
            if (url.length() == 0) {
                JSONObject fmt = o.optJSONObject("format");
                if (fmt != null) url = fmt.optString("url", "");
            }
            return url;
        } catch (Exception e) {
            throw new IOException("YtDlp resolve parse: " + e.getMessage(), e);
        }
    }

    private static AudioStream parseResolvedAudio(String body) throws IOException {
        try {
            JSONObject o = new JSONObject(body);
            String url = o.optString("url", "");
            if (url.length() == 0) url = o.optString("audioUrl", "");
            if (url.length() == 0) url = o.optString("streamUrl", "");
            String ext = o.optString("ext", "m4a");
            if (url.length() == 0) return null;
            return new AudioStream(url, ext);
        } catch (Exception e) {
            throw new IOException("YtDlp audio parse: " + e.getMessage(), e);
        }
    }

    private static String httpGet(String url) throws IOException {
        return new String(SolarHttp.getBytesQuick(url, "application/json", UA, 10, 20), "UTF-8");
    }

    private static String tryGetJson(String url) {
        try {
            return httpGet(url);
        } catch (Exception ignored) {
            return null;
        }
    }
}
