package com.solar.launcher.youtube;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Read-only account metadata used by the local Discover ranker. */
public final class YouTubeDiscoverSignals {

    public final List<String> subscribedChannels;
    public final List<YouTubeVideo> likedVideos;
    public final boolean accountConnected;
    public final boolean stale;
    public final boolean partial;

    public YouTubeDiscoverSignals(List<String> subscribedChannels,
            List<YouTubeVideo> likedVideos, boolean accountConnected, boolean stale) {
        this(subscribedChannels, likedVideos, accountConnected, stale, false);
    }

    public YouTubeDiscoverSignals(List<String> subscribedChannels,
            List<YouTubeVideo> likedVideos, boolean accountConnected, boolean stale,
            boolean partial) {
        this.subscribedChannels = subscribedChannels != null
                ? new ArrayList<String>(subscribedChannels)
                : new ArrayList<String>();
        this.likedVideos = likedVideos != null
                ? new ArrayList<YouTubeVideo>(likedVideos)
                : new ArrayList<YouTubeVideo>();
        this.accountConnected = accountConnected;
        this.stale = stale;
        this.partial = partial;
    }

    public static YouTubeDiscoverSignals parse(String json) throws Exception {
        JSONObject root = new JSONObject(json != null ? json : "{}");
        List<String> channels = new ArrayList<String>();
        JSONArray channelArray = root.optJSONArray("subscribedChannels");
        if (channelArray != null) {
            for (int i = 0; i < channelArray.length(); i++) {
                String value = channelArray.optString(i, "").trim();
                if (value.length() > 0 && !channels.contains(value)) channels.add(value);
            }
        }
        List<YouTubeVideo> liked = new ArrayList<YouTubeVideo>();
        JSONArray items = root.optJSONArray("likedVideos");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.length() == 0) continue;
                liked.add(new YouTubeVideo(id,
                        item.optString("title", ""),
                        item.optString("author", ""),
                        item.optString("length", ""),
                        item.optString("description", "")));
            }
        }
        return new YouTubeDiscoverSignals(channels, liked,
                root.optBoolean("accountConnected", false),
                root.optBoolean("stale", false),
                root.optBoolean("partial", false));
    }
}
