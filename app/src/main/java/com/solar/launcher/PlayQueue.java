package com.solar.launcher;

import com.solar.launcher.podcast.OpenRssClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Unified playback queue — music files, podcast episodes, Reach streams in one order. */
public final class PlayQueue {
    public enum ItemKind { MUSIC_FILE, PODCAST_EPISODE, REACH_STREAM }

    public static final class QueueItem {
        public final ItemKind kind;
        public final File file;
        public final OpenRssClient.Episode episode;
        public final String podcastShowTitle;
        public final boolean podcastFromSaved;
        public final String reachMeta;

        private QueueItem(ItemKind kind, File file, OpenRssClient.Episode episode,
                String podcastShowTitle, boolean podcastFromSaved, String reachMeta) {
            this.kind = kind;
            this.file = file;
            this.episode = episode;
            this.podcastShowTitle = podcastShowTitle != null ? podcastShowTitle : "";
            this.podcastFromSaved = podcastFromSaved;
            this.reachMeta = reachMeta;
        }

        public static QueueItem music(File f) {
            return new QueueItem(ItemKind.MUSIC_FILE, f, null, "", false, null);
        }

        public static QueueItem reach(File temp, String meta) {
            return new QueueItem(ItemKind.REACH_STREAM, temp, null, "", false, meta);
        }

        public static QueueItem podcast(OpenRssClient.Episode ep, String showTitle, boolean fromSaved) {
            return new QueueItem(ItemKind.PODCAST_EPISODE, null, ep, showTitle, fromSaved, null);
        }
    }

    private final List<QueueItem> items = new ArrayList<QueueItem>();
    private int index = 0;

    public List<QueueItem> items() {
        return items;
    }

    public int index() {
        clampIndex();
        return index;
    }

    public void setIndex(int i) {
        index = i;
        clampIndex();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public QueueItem current() {
        if (items.isEmpty()) return null;
        clampIndex();
        return items.get(index);
    }

    void clampIndex() {
        if (items.isEmpty()) {
            index = 0;
            return;
        }
        if (index < 0 || index >= items.size()) {
            index = Math.max(0, Math.min(index, items.size() - 1));
        }
    }

    public void clear() {
        items.clear();
        index = 0;
    }

    public void setAll(List<QueueItem> next, int startIndex) {
        items.clear();
        if (next != null) items.addAll(next);
        index = items.isEmpty() ? 0 : Math.max(0, Math.min(startIndex, items.size() - 1));
    }

    public void append(QueueItem item) {
        if (item == null) return;
        items.add(item);
    }

    public void insertAt(int i, QueueItem item) {
        if (item == null || i < 0 || i > items.size()) return;
        items.add(i, item);
        if (index >= i) index++;
        clampIndex();
    }

    public void appendFiles(List<File> tracks, boolean reachTemp) {
        if (tracks == null) return;
        for (File f : tracks) {
            if (f == null || !f.isFile()) continue;
            items.add(reachTemp ? QueueItem.reach(f, f.getName()) : QueueItem.music(f));
        }
    }

    public void removeAt(int i) {
        if (i < 0 || i >= items.size()) return;
        items.remove(i);
        if (index > i) index--;
        else if (index == i && index >= items.size()) index = Math.max(0, items.size() - 1);
        clampIndex();
    }

    public void move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) return;
        QueueItem f = items.remove(from);
        items.add(to, f);
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        clampIndex();
    }

    public int nextIndex(boolean repeatAll) {
        if (items.isEmpty()) return -1;
        if (items.size() == 1) return repeatAll ? 0 : -1;
        int next = index + 1;
        if (next >= items.size()) return repeatAll ? 0 : -1;
        return next;
    }

    public int prevIndex(boolean repeatAll) {
        if (items.isEmpty()) return -1;
        if (items.size() == 1) return repeatAll ? 0 : -1;
        int prev = index - 1;
        if (prev < 0) return repeatAll ? items.size() - 1 : -1;
        return prev;
    }

    /** ponytail: O(n) filter — fine for Y1 queue sizes */
    public List<File> musicFiles() {
        List<File> out = new ArrayList<File>();
        for (QueueItem q : items) {
            if (q.kind == ItemKind.MUSIC_FILE || q.kind == ItemKind.REACH_STREAM) {
                if (q.file != null) out.add(q.file);
            }
        }
        return out;
    }

    public List<OpenRssClient.Episode> podcastEpisodes() {
        List<OpenRssClient.Episode> out = new ArrayList<OpenRssClient.Episode>();
        for (QueueItem q : items) {
            if (q.kind == ItemKind.PODCAST_EPISODE && q.episode != null) out.add(q.episode);
        }
        return out;
    }

    public ItemKind activeKind() {
        QueueItem c = current();
        return c != null ? c.kind : ItemKind.MUSIC_FILE;
    }
}
