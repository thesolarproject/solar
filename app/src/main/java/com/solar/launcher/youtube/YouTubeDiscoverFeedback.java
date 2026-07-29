package com.solar.launcher.youtube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, device-local feedback for the transparent Discover ranker. */
public final class YouTubeDiscoverFeedback {

    private static final String PREFS = "solar_youtube_discover";
    private static final String KEY_FEEDBACK = "feedback_v1";
    private static final int MAX_BLOCKED = 100;
    private static final int MAX_KEYS = 80;
    private static final int MAX_WEIGHT = 5;

    private final SharedPreferences prefs;

    public YouTubeDiscoverFeedback(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void notInterested(YouTubeVideo video) {
        if (video == null || video.id.length() == 0) return;
        State state = read();
        state.blocked.remove(video.id);
        state.blocked.add(0, video.id);
        trimList(state.blocked, MAX_BLOCKED);
        write(state);
    }

    public synchronized void moreLike(YouTubeVideo video) {
        if (video == null) return;
        State state = read();
        increment(state.boostedChannels,
                YouTubeDiscoverRanker.normalizeKey(video.author));
        for (String term : YouTubeDiscoverRanker.terms(video.title)) {
            increment(state.boostedTerms, term);
        }
        trimMap(state.boostedChannels, MAX_KEYS);
        trimMap(state.boostedTerms, MAX_KEYS);
        write(state);
    }

    public synchronized void lessFromChannel(YouTubeVideo video) {
        if (video == null) return;
        State state = read();
        increment(state.reducedChannels,
                YouTubeDiscoverRanker.normalizeKey(video.author));
        trimMap(state.reducedChannels, MAX_KEYS);
        write(state);
    }

    public synchronized YouTubeDiscoverRanker.Feedback snapshot() {
        State state = read();
        return new YouTubeDiscoverRanker.Feedback(
                new HashSet<String>(state.blocked),
                state.boostedChannels,
                state.reducedChannels,
                state.boostedTerms);
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_FEEDBACK).commit();
    }

    private State read() {
        State state = new State();
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_FEEDBACK, "{}"));
            JSONArray blocked = root.optJSONArray("blocked");
            if (blocked != null) {
                for (int i = 0; i < blocked.length() && i < MAX_BLOCKED; i++) {
                    String id = blocked.optString(i, "");
                    if (id.length() > 0 && !state.blocked.contains(id)) {
                        state.blocked.add(id);
                    }
                }
            }
            readMap(root.optJSONObject("boostedChannels"), state.boostedChannels);
            readMap(root.optJSONObject("reducedChannels"), state.reducedChannels);
            readMap(root.optJSONObject("boostedTerms"), state.boostedTerms);
        } catch (Exception ignored) {
            // Corrupt local feedback becomes an empty state on the next write.
        }
        return state;
    }

    private void write(State state) {
        try {
            JSONObject root = new JSONObject();
            root.put("blocked", new JSONArray(state.blocked));
            root.put("boostedChannels", mapJson(state.boostedChannels));
            root.put("reducedChannels", mapJson(state.reducedChannels));
            root.put("boostedTerms", mapJson(state.boostedTerms));
            prefs.edit().putString(KEY_FEEDBACK, root.toString()).commit();
        } catch (Exception ignored) {}
    }

    private static void readMap(JSONObject object, Map<String, Integer> target) {
        if (object == null) return;
        Iterator<String> keys = object.keys();
        while (keys.hasNext() && target.size() < MAX_KEYS) {
            String key = keys.next();
            int value = Math.max(0, Math.min(MAX_WEIGHT, object.optInt(key, 0)));
            if (key.length() > 0 && value > 0) target.put(key, value);
        }
    }

    private static JSONObject mapJson(Map<String, Integer> values) throws Exception {
        JSONObject object = new JSONObject();
        int count = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (count++ >= MAX_KEYS) break;
            object.put(entry.getKey(),
                    Math.max(1, Math.min(MAX_WEIGHT, entry.getValue())));
        }
        return object;
    }

    private static void increment(Map<String, Integer> target, String key) {
        if (key == null || key.length() == 0) return;
        Integer current = target.get(key);
        target.put(key, Math.min(MAX_WEIGHT, current != null ? current + 1 : 1));
    }

    private static void trimList(List<String> values, int max) {
        while (values.size() > max) values.remove(values.size() - 1);
    }

    private static void trimMap(Map<String, Integer> values, int max) {
        if (values.size() <= max) return;
        List<String> keys = new ArrayList<String>(values.keySet());
        while (values.size() > max && !keys.isEmpty()) {
            values.remove(keys.remove(0));
        }
    }

    private static final class State {
        final List<String> blocked = new ArrayList<String>();
        final Map<String, Integer> boostedChannels =
                new LinkedHashMap<String, Integer>();
        final Map<String, Integer> reducedChannels =
                new LinkedHashMap<String, Integer>();
        final Map<String, Integer> boostedTerms =
                new LinkedHashMap<String, Integer>();
    }
}
