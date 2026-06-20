package com.solar.launcher;

import android.content.Context;

import com.solar.launcher.podcast.OpenRssClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/** Cold-start queue restore — small JSON in app files dir. */
public final class PlayQueueStore {
    private static final String FILE = "play_queue.json";

    private PlayQueueStore() {}

    public static void save(Context ctx, PlayQueue queue) {
        if (ctx == null || queue == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (PlayQueue.QueueItem q : queue.items()) {
                JSONObject o = new JSONObject();
                o.put("kind", q.kind.name());
                if (q.file != null) o.put("path", q.file.getAbsolutePath());
                if (q.episode != null) {
                    o.put("epTitle", q.episode.title);
                    o.put("epUrl", q.episode.audioUrl);
                    o.put("epShow", q.podcastShowTitle);
                    o.put("epSaved", q.podcastFromSaved);
                }
                if (q.reachMeta != null) o.put("reachMeta", q.reachMeta);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("index", queue.index());
            root.put("items", arr);
            File dir = ctx.getFilesDir();
            File f = new File(dir, FILE);
            File tmp = new File(dir, FILE + ".tmp");
            BufferedWriter w = new BufferedWriter(new FileWriter(tmp));
            w.write(root.toString());
            w.close();
            if (f.exists() && !f.delete()) return;
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public static boolean restore(Context ctx, PlayQueue queue) {
        if (ctx == null || queue == null) return false;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return false;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("items");
            if (arr == null || arr.length() == 0) return false;
            int savedIndex = root.optInt("index", 0);
            java.util.ArrayList<PlayQueue.QueueItem> items = new java.util.ArrayList<PlayQueue.QueueItem>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String kind = o.optString("kind", "");
                if ("PODCAST_EPISODE".equals(kind)) {
                    OpenRssClient.Episode ep = new OpenRssClient.Episode(
                            o.optString("epTitle", ""),
                            o.optString("epUrl", ""),
                            "");
                    items.add(PlayQueue.QueueItem.podcast(ep,
                            o.optString("epShow", ""), o.optBoolean("epSaved", false)));
                } else {
                    String path = o.optString("path", "");
                    if (path.isEmpty()) {
                        if (i < savedIndex) savedIndex--;
                        continue;
                    }
                    File file = new File(path);
                    if (!file.isFile()) {
                        if (i < savedIndex) savedIndex--;
                        continue;
                    }
                    if ("REACH_STREAM".equals(kind)) {
                        items.add(PlayQueue.QueueItem.reach(file, o.optString("reachMeta", file.getName())));
                    } else {
                        items.add(PlayQueue.QueueItem.music(file));
                    }
                }
            }
            if (items.isEmpty()) return false;
            savedIndex = Math.max(0, Math.min(savedIndex, items.size() - 1));
            queue.setAll(items, savedIndex);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.exists()) f.delete();
    }
}
