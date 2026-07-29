package com.solar.launcher.youtube.official;

import android.content.Context;

import com.solar.launcher.BuildConfig;
import com.solar.launcher.net.TlsHelper;
import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Official YouTube Data API v3 metadata client. It has no stream endpoint. */
public final class YouTubeOfficialApi {

    private static final String API_ROOT = "https://www.googleapis.com/youtube/v3";
    private static final int PAGE_SIZE = 20;

    public static final class Page {
        public final List<YouTubeVideo> videos;
        public final String nextPageToken;

        Page(List<YouTubeVideo> videos, String nextPageToken) {
            this.videos = videos != null ? videos : new ArrayList<YouTubeVideo>();
            this.nextPageToken = nextPageToken != null ? nextPageToken : "";
        }
    }

    private final Context appContext;
    private final YouTubeDeviceAuth auth;
    private final OkHttpClient http;

    public YouTubeOfficialApi(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        appContext = context.getApplicationContext();
        auth = YouTubeDeviceAuth.getInstance(appContext);
        // Keep one attempt inside YouTubeClient's 22-second UI deadline.
        http = TlsHelper.client().newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public boolean isConfigured() {
        return apiKey().length() > 0 || auth.hasAccount();
    }

    public boolean hasAccount() {
        return auth.hasAccount();
    }

    public Page search(String query, String pageToken, String regionCode) throws Exception {
        String clean = query != null ? query.trim() : "";
        if (clean.length() == 0) return new Page(new ArrayList<YouTubeVideo>(), "");
        HttpUrl.Builder url = endpoint("search")
                .addQueryParameter("part", "snippet")
                .addQueryParameter("type", "video")
                .addQueryParameter("maxResults", String.valueOf(PAGE_SIZE))
                .addQueryParameter("q", clean)
                .addQueryParameter("safeSearch", "moderate");
        addOptional(url, "pageToken", pageToken);
        addOptional(url, "regionCode", normalizeRegion(regionCode));
        JSONObject search = getJson(url);
        List<String> ids = parseSearchIds(search);
        List<YouTubeVideo> videos = ids.isEmpty()
                ? new ArrayList<YouTubeVideo>()
                : fetchVideoDetails(ids);
        return new Page(videos, search.optString("nextPageToken", ""));
    }

    public Page popular(String regionCode, String pageToken) throws Exception {
        HttpUrl.Builder url = endpoint("videos")
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("chart", "mostPopular")
                .addQueryParameter("maxResults", String.valueOf(PAGE_SIZE))
                .addQueryParameter("regionCode", normalizeRegion(regionCode));
        addOptional(url, "pageToken", pageToken);
        JSONObject response = getJson(url);
        return new Page(parseVideoDetails(response, null),
                response.optString("nextPageToken", ""));
    }

    public List<YouTubeComment> comments(String videoId) throws Exception {
        HttpUrl.Builder url = endpoint("commentThreads")
                .addQueryParameter("part", "snippet")
                .addQueryParameter("videoId", nonNull(videoId))
                .addQueryParameter("maxResults", "20")
                .addQueryParameter("order", "relevance")
                .addQueryParameter("textFormat", "plainText");
        return parseComments(getJson(url));
    }

    /** First bounded page of channels followed by the connected read-only account. */
    public List<String> subscriptions() throws Exception {
        if (!auth.hasAccount()) return new ArrayList<String>();
        HttpUrl.Builder url = endpoint("subscriptions")
                .addQueryParameter("part", "snippet")
                .addQueryParameter("mine", "true")
                .addQueryParameter("maxResults", "50");
        return parseSubscriptions(getJson(url));
    }

    /** First bounded page of videos the connected account marked as liked. */
    public List<YouTubeVideo> likedVideos() throws Exception {
        if (!auth.hasAccount()) return new ArrayList<YouTubeVideo>();
        HttpUrl.Builder url = endpoint("videos")
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("myRating", "like")
                .addQueryParameter("maxResults", "50");
        return parseVideoDetails(getJson(url), null);
    }

    static List<String> parseSearchIds(JSONObject response) {
        List<String> ids = new ArrayList<String>();
        if (response == null) return ids;
        JSONArray items = response.optJSONArray("items");
        if (items == null) return ids;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject id = item != null ? item.optJSONObject("id") : null;
            String videoId = id != null ? id.optString("videoId", "") : "";
            if (videoId.length() > 0 && !ids.contains(videoId)) ids.add(videoId);
        }
        return ids;
    }

    static List<YouTubeVideo> parseVideoDetails(JSONObject response, List<String> order) {
        Map<String, YouTubeVideo> byId = new LinkedHashMap<String, YouTubeVideo>();
        JSONArray items = response != null ? response.optJSONArray("items") : null;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                JSONObject snippet = item.optJSONObject("snippet");
                JSONObject details = item.optJSONObject("contentDetails");
                if (id.length() == 0 || snippet == null) continue;
                String title = decodeEntities(snippet.optString("title", ""));
                String channel = decodeEntities(snippet.optString("channelTitle", ""));
                String description = decodeEntities(
                        snippet.optString("description", ""));
                String duration = formatIsoDuration(details != null
                        ? details.optString("duration", "") : "");
                byId.put(id, new YouTubeVideo(
                        id, title, channel, duration, description));
            }
        }
        List<YouTubeVideo> out = new ArrayList<YouTubeVideo>();
        if (order == null) {
            out.addAll(byId.values());
        } else {
            for (String id : order) {
                YouTubeVideo video = byId.get(id);
                if (video != null) out.add(video);
            }
        }
        return out;
    }

    static List<YouTubeComment> parseComments(JSONObject response) {
        List<YouTubeComment> out = new ArrayList<YouTubeComment>();
        JSONArray items = response != null ? response.optJSONArray("items") : null;
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject thread = items.optJSONObject(i);
            JSONObject snippet = thread != null ? thread.optJSONObject("snippet") : null;
            JSONObject top = snippet != null ? snippet.optJSONObject("topLevelComment") : null;
            JSONObject comment = top != null ? top.optJSONObject("snippet") : null;
            if (comment == null) continue;
            String author = decodeEntities(comment.optString("authorDisplayName", ""));
            String text = comment.optString("textOriginal",
                    comment.optString("textDisplay", ""));
            out.add(new YouTubeComment(author, decodeEntities(text)));
        }
        return out;
    }

    static List<String> parseSubscriptions(JSONObject response) {
        List<String> out = new ArrayList<String>();
        JSONArray items = response != null ? response.optJSONArray("items") : null;
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject snippet = item != null ? item.optJSONObject("snippet") : null;
            String title = snippet != null
                    ? decodeEntities(snippet.optString("title", "")).trim() : "";
            if (title.length() > 0 && !out.contains(title)) out.add(title);
        }
        return out;
    }

    static String formatIsoDuration(String value) {
        if (value == null || value.length() < 3 || !value.startsWith("PT")) return "";
        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        String digits = "";
        for (int i = 2; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits += ch;
                continue;
            }
            if (digits.length() == 0) continue;
            long number;
            try {
                number = Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return "";
            }
            if (ch == 'H') hours = number;
            else if (ch == 'M') minutes = number;
            else if (ch == 'S') seconds = number;
            digits = "";
        }
        long totalMinutes = hours * 60L + minutes;
        if (totalMinutes > 0) {
            return String.format(Locale.US, "%d:%02d", totalMinutes, seconds);
        }
        return String.format(Locale.US, "0:%02d", seconds);
    }

    private List<YouTubeVideo> fetchVideoDetails(List<String> ids) throws Exception {
        StringBuilder joined = new StringBuilder();
        for (String id : ids) {
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        HttpUrl.Builder url = endpoint("videos")
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("id", joined.toString())
                .addQueryParameter("maxResults", String.valueOf(ids.size()));
        return parseVideoDetails(getJson(url), ids);
    }

    private JSONObject getJson(HttpUrl.Builder builder) throws Exception {
        String accessToken = "";
        try {
            accessToken = auth.accessTokenForApi();
        } catch (IOException refreshFailure) {
            if (apiKey().length() == 0) throw refreshFailure;
        }
        if (accessToken.length() == 0) {
            if (apiKey().length() == 0) throw new IOException("youtube_setup_required");
            builder.addQueryParameter("key", apiKey());
        }
        Request.Builder request = new Request.Builder()
                .url(builder.build())
                .header("User-Agent", "SolarLauncher/1.0")
                .header("Accept", "application/json");
        if (accessToken.length() > 0) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        TlsHelper.ensureSecurityProvider();
        Response response = http.newCall(request.build()).execute();
        String body;
        int responseCode = response.code();
        boolean successful = response.isSuccessful();
        try {
            body = response.body() != null ? response.body().string() : "";
        } finally {
            response.close();
        }
        if (!successful) {
            throw new IOException(safeApiError(responseCode, body));
        }
        return new JSONObject(body);
    }

    private static HttpUrl.Builder endpoint(String resource) {
        HttpUrl parsed = HttpUrl.parse(API_ROOT + "/" + resource);
        if (parsed == null) throw new IllegalStateException("invalid YouTube API root");
        return parsed.newBuilder();
    }

    private static String normalizeRegion(String value) {
        String clean = value != null ? value.trim().toUpperCase(Locale.US) : "";
        return clean.matches("[A-Z]{2}") ? clean : "US";
    }

    private static void addOptional(HttpUrl.Builder url, String name, String value) {
        if (value != null && value.length() > 0) url.addQueryParameter(name, value);
    }

    private static String safeApiError(int code, String body) {
        try {
            JSONObject root = new JSONObject(nonNull(body));
            JSONObject error = root.optJSONObject("error");
            JSONArray errors = error != null ? error.optJSONArray("errors") : null;
            if (errors != null && errors.length() > 0) {
                String reason = errors.optJSONObject(0).optString("reason", "");
                if (reason.length() > 0) return reason;
            }
        } catch (Exception ignored) {}
        return "youtube_http_" + code;
    }

    private static String decodeEntities(String value) {
        if (value == null || value.length() == 0) return "";
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String apiKey() {
        return nonNull(BuildConfig.YOUTUBE_DATA_API_KEY).trim();
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
