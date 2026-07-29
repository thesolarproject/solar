package com.solar.launcher.youtube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Small, bounded metadata-only research list stored locally on the Y1. */
public final class YouTubeBookmarks {

    private static final String PREFS = "solar_youtube_bookmarks";
    private static final String KEY_ITEMS = "items_v1";
    private static final int MAX_ITEMS = 200;

    private final SharedPreferences prefs;

    public YouTubeBookmarks(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return true when the item is now saved, false when it was removed. */
    public synchronized boolean toggle(YouTubeVideo video) {
        if (video == null || video.id.length() == 0) return false;
        List<Entry> entries = readEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (video.id.equals(entries.get(i).video.id)) {
                entries.remove(i);
                writeEntries(entries);
                return false;
            }
        }
        entries.add(0, new Entry(video, System.currentTimeMillis()));
        if (entries.size() > MAX_ITEMS) {
            entries = new ArrayList<Entry>(entries.subList(0, MAX_ITEMS));
        }
        writeEntries(entries);
        return true;
    }

    public synchronized boolean contains(String videoId) {
        if (videoId == null || videoId.length() == 0) return false;
        List<Entry> entries = readEntries();
        for (Entry entry : entries) {
            if (videoId.equals(entry.video.id)) return true;
        }
        return false;
    }

    public synchronized List<YouTubeVideo> list() {
        List<Entry> entries = readEntries();
        List<YouTubeVideo> videos = new ArrayList<YouTubeVideo>(entries.size());
        for (Entry entry : entries) videos.add(entry.video);
        return videos;
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_ITEMS).commit();
    }

    private List<Entry> readEntries() {
        List<Entry> entries = new ArrayList<Entry>();
        String raw = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.length() == 0) continue;
                entries.add(new Entry(new YouTubeVideo(
                        id,
                        item.optString("title", ""),
                        item.optString("author", ""),
                        item.optString("duration", ""),
                        item.optString("description", "")),
                        item.optLong("savedAt", 0L)));
            }
        } catch (Exception ignored) {
            // Corrupt local metadata is reset on the next write, never fatal to browse.
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                if (left.savedAt == right.savedAt) return 0;
                return left.savedAt > right.savedAt ? -1 : 1;
            }
        });
        return entries;
    }

    private void writeEntries(List<Entry> entries) {
        JSONArray array = new JSONArray();
        int count = Math.min(entries != null ? entries.size() : 0, MAX_ITEMS);
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("id", entry.video.id);
                item.put("title", entry.video.title);
                item.put("author", entry.video.author);
                item.put("duration", entry.video.duration);
                item.put("description",
                        CreatorDownloadLinkExtractor.compactForBookmark(
                                entry.video.description));
                item.put("savedAt", entry.savedAt);
                array.put(item);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).commit();
    }

    private static final class Entry {
        final YouTubeVideo video;
        final long savedAt;

        Entry(YouTubeVideo video, long savedAt) {
            this.video = video;
            this.savedAt = savedAt;
        }
    }
}
