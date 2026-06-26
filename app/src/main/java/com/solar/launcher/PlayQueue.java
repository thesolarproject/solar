package com.solar.launcher;

import com.solar.launcher.podcast.OpenRssClient;
import com.solar.launcher.radio.FmBandPlan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Unified playback queue — music files, podcast episodes, Reach streams in one order. */
public final class PlayQueue {
    public enum ItemKind {
        MUSIC_FILE, PODCAST_EPISODE, REACH_STREAM, DEEZER_STREAM,
        FM_STATION, INTERNET_RADIO_STATION
    }

    public static final class QueueItem {
        public final ItemKind kind;
        public final File file;
        public final OpenRssClient.Episode episode;
        public final String podcastShowTitle;
        public final boolean podcastFromSaved;
        public final String reachMeta;
        public final String reachPeerUsername;
        public final String deezerMeta;
        public final long deezerTrackId;
        /** FM: frequency in kHz (e.g. 98700 = 98.7 MHz). */
        public final int fmFreqKhz;
        public final String fmLabel;
        /** Internet radio: Radio Browser stationuuid. */
        public final String radioStationUuid;
        public final String radioName;
        public final String radioUrl;
        public final String radioSubtitle;
        public final String radioFavicon;

        private QueueItem(ItemKind kind, File file, OpenRssClient.Episode episode,
                String podcastShowTitle, boolean podcastFromSaved, String reachMeta,
                String reachPeerUsername, String deezerMeta, long deezerTrackId,
                int fmFreqKhz, String fmLabel,
                String radioStationUuid, String radioName, String radioUrl,
                String radioSubtitle, String radioFavicon) {
            this.kind = kind;
            this.file = file;
            this.episode = episode;
            this.podcastShowTitle = podcastShowTitle != null ? podcastShowTitle : "";
            this.podcastFromSaved = podcastFromSaved;
            this.reachMeta = reachMeta;
            this.reachPeerUsername = reachPeerUsername;
            this.deezerMeta = deezerMeta;
            this.deezerTrackId = deezerTrackId;
            this.fmFreqKhz = fmFreqKhz;
            this.fmLabel = fmLabel != null ? fmLabel : "";
            this.radioStationUuid = radioStationUuid != null ? radioStationUuid : "";
            this.radioName = radioName != null ? radioName : "";
            this.radioUrl = radioUrl != null ? radioUrl : "";
            this.radioSubtitle = radioSubtitle != null ? radioSubtitle : "";
            this.radioFavicon = radioFavicon != null ? radioFavicon : "";
        }

        public static QueueItem music(File f) {
            return new QueueItem(ItemKind.MUSIC_FILE, f, null, "", false, null, null, null, 0,
                    0, "", "", "", "", "", "");
        }

        public static QueueItem reach(File temp, String meta) {
            return reach(temp, meta, null);
        }

        public static QueueItem reach(File temp, String meta, String peerUsername) {
            return new QueueItem(ItemKind.REACH_STREAM, temp, null, "", false, meta, peerUsername,
                    null, 0, 0, "", "", "", "", "", "");
        }

        public static QueueItem deezer(File temp, String meta, long trackId) {
            return new QueueItem(ItemKind.DEEZER_STREAM, temp, null, "", false, null, null, meta,
                    trackId, 0, "", "", "", "", "", "");
        }

        public static QueueItem podcast(OpenRssClient.Episode ep, String showTitle, boolean fromSaved) {
            return new QueueItem(ItemKind.PODCAST_EPISODE, null, ep, showTitle, fromSaved, null,
                    null, null, 0, 0, "", "", "", "", "", "");
        }

        public static QueueItem fmStation(int freqKhz, String label) {
            return new QueueItem(ItemKind.FM_STATION, null, null, "", false, null, null, null, 0,
                    freqKhz, label, "", "", "", "", "");
        }

        public static QueueItem internetRadio(String uuid, String name, String url,
                String subtitle, String favicon) {
            return new QueueItem(ItemKind.INTERNET_RADIO_STATION, null, null, "", false, null,
                    null, null, 0, 0, "", uuid, name, url, subtitle, favicon);
        }

        /** Display title for stream / radio items. */
        public String streamMeta() {
            if (kind == ItemKind.DEEZER_STREAM && deezerMeta != null) return deezerMeta;
            if (kind == ItemKind.REACH_STREAM && reachMeta != null) return reachMeta;
            if (kind == ItemKind.FM_STATION) {
                if (fmLabel != null && !fmLabel.isEmpty()) return fmLabel;
                return FmBandPlan.formatMhz(fmFreqKhz / 1000f);
            }
            if (kind == ItemKind.INTERNET_RADIO_STATION) return radioName;
            return file != null ? file.getName() : "";
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

    public void replaceFileRef(File oldF, File newF, String reachMeta) {
        if (oldF == null || newF == null) return;
        for (int i = 0; i < items.size(); i++) {
            QueueItem q = items.get(i);
            if (q.file == null || !q.file.equals(oldF)) continue;
            if (q.kind == ItemKind.REACH_STREAM) {
                items.set(i, QueueItem.reach(newF, reachMeta != null ? reachMeta : newF.getName(),
                        q.reachPeerUsername));
            } else if (q.kind == ItemKind.DEEZER_STREAM) {
                items.set(i, QueueItem.deezer(newF, reachMeta != null ? reachMeta : newF.getName(),
                        q.deezerTrackId));
            } else if (q.kind == ItemKind.MUSIC_FILE) {
                items.set(i, QueueItem.music(newF));
            }
        }
    }

    /** After saving a stream temp file to the music library, re-tag as a local music track. */
    public void promoteStreamToMusic(File oldF, File libraryFile) {
        if (oldF == null || libraryFile == null) return;
        for (int i = 0; i < items.size(); i++) {
            QueueItem q = items.get(i);
            if (q.file == null || !q.file.equals(oldF)) continue;
            if (q.kind == ItemKind.REACH_STREAM || q.kind == ItemKind.DEEZER_STREAM
                    || q.kind == ItemKind.MUSIC_FILE) {
                items.set(i, QueueItem.music(libraryFile));
            }
            return;
        }
    }

    /** Insert after queue index {@code afterIndex}; use -1 to prepend when non-empty. Returns new index. */
    public int insertAfter(int afterIndex, QueueItem item) {
        if (item == null) return -1;
        if (items.isEmpty()) {
            items.add(item);
            index = 0;
            return 0;
        }
        int insertAt;
        if (afterIndex < 0) {
            insertAt = 0;
        } else {
            int pos = Math.min(afterIndex, items.size() - 1);
            insertAt = pos + 1;
        }
        items.add(insertAt, item);
        if (index >= insertAt) index++;
        clampIndex();
        return insertAt;
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

    public void swap(int a, int b) {
        if (a < 0 || b < 0 || a >= items.size() || b >= items.size() || a == b) return;
        QueueItem aItem = items.get(a);
        items.set(a, items.get(b));
        items.set(b, aItem);
        if (index == a) index = b;
        else if (index == b) index = a;
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
            if (q.kind == ItemKind.MUSIC_FILE || q.kind == ItemKind.REACH_STREAM
                    || q.kind == ItemKind.DEEZER_STREAM) {
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
