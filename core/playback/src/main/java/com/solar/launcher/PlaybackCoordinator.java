package com.solar.launcher;

import com.solar.launcher.contracts.QueueEpisode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ponytail: unified queue for playback order; legacy music/podcast views synced from PlayQueue. */
public final class PlaybackCoordinator {
    public enum Mode { NONE, MUSIC, PODCAST }

    private final PlayQueue queue = new PlayQueue();
    private Mode activeMode = Mode.NONE;
    private final List<File> musicOriginal = new ArrayList<File>();
    private String podcastShowTitle = "";
    private boolean podcastFromSavedLibrary = false;
    private File musicInitiator;
    private String musicActivePlaylistName;

    public PlayQueue unifiedQueue() {
        return queue;
    }

    public Mode activeMode() {
        return activeMode;
    }

    public boolean isPodcastActive() {
        PlayQueue.QueueItem c = queue.current();
        return activeMode == Mode.PODCAST || (c != null && c.kind == PlayQueue.ItemKind.PODCAST_EPISODE);
    }

    public boolean isMusicActive() {
        PlayQueue.QueueItem c = queue.current();
        return activeMode == Mode.MUSIC
                || (c != null && (c.kind == PlayQueue.ItemKind.MUSIC_FILE
                || c.kind == PlayQueue.ItemKind.REACH_STREAM));
    }

    public boolean hasAnyQueue() {
        return queue.size() > 0;
    }

    public List<File> musicPlaylist() {
        return queue.musicFiles();
    }

    public List<File> musicOriginal() {
        return musicOriginal;
    }

    public int musicIndex() {
        syncLegacyMusicIndex();
        int idx = 0;
        PlayQueue.QueueItem cur = queue.current();
        if (cur == null || cur.file == null) return 0;
        for (PlayQueue.QueueItem q : queue.items()) {
            if (q.kind == PlayQueue.ItemKind.MUSIC_FILE || q.kind == PlayQueue.ItemKind.REACH_STREAM) {
                if (q.file.equals(cur.file)) return idx;
                idx++;
            }
        }
        return 0;
    }

    public void setMusicIndex(int index) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (q.kind == PlayQueue.ItemKind.MUSIC_FILE || q.kind == PlayQueue.ItemKind.REACH_STREAM) {
                if (idx == index) {
                    queue.setIndex(i);
                    return;
                }
                idx++;
            }
        }
        queue.clampIndex();
    }

    void clampMusicIndex() {
        queue.clampIndex();
    }

    public static String formatTrackPosition(int index, int total) {
        if (total <= 0) return "— / —";
        int pos = index + 1;
        if (pos < 1) pos = 1;
        if (pos > total) pos = total;
        return String.format(java.util.Locale.US, "%02d / %02d", pos, total);
    }

    public String formatActivePosition() {
        return formatTrackPosition(queue.index(), queue.size());
    }

    public List<QueueEpisode> podcastQueue() {
        return queue.podcastEpisodes();
    }

    public int podcastIndex() {
        PlayQueue.QueueItem cur = queue.current();
        if (cur == null || cur.kind != PlayQueue.ItemKind.PODCAST_EPISODE) return -1;
        int idx = 0;
        for (PlayQueue.QueueItem q : queue.items()) {
            if (q.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
                if (q.episode == cur.episode) return idx;
                idx++;
            }
        }
        return -1;
    }

    public void setPodcastIndex(int index) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (q.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
                if (idx == index) {
                    queue.setIndex(i);
                    return;
                }
                idx++;
            }
        }
    }

    public String podcastShowTitle() {
        PlayQueue.QueueItem c = queue.current();
        if (c != null && c.kind == PlayQueue.ItemKind.PODCAST_EPISODE) return c.podcastShowTitle;
        return podcastShowTitle;
    }

    public boolean podcastFromSavedLibrary() {
        PlayQueue.QueueItem c = queue.current();
        if (c != null && c.kind == PlayQueue.ItemKind.PODCAST_EPISODE) return c.podcastFromSaved;
        return podcastFromSavedLibrary;
    }

    public File musicInitiator() {
        return musicInitiator;
    }

    public String musicActivePlaylistName() {
        return musicActivePlaylistName;
    }

    public void setMusicActivePlaylistName(String name) {
        musicActivePlaylistName = (name != null && !name.isEmpty()) ? name : null;
    }

    private void syncLegacyMusicIndex() {
        queue.clampIndex();
    }

    public void activateMusic(List<File> playlist, int startIndex, boolean shuffle) {
        musicOriginal.clear();
        musicInitiator = null;
        if (playlist == null || playlist.isEmpty()) {
            queue.clear();
            activeMode = Mode.NONE;
            return;
        }
        activeMode = Mode.MUSIC;
        musicOriginal.addAll(playlist);
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        List<File> order = new ArrayList<File>(playlist);
        File currentSong = playlist.get(Math.max(0, Math.min(startIndex, playlist.size() - 1)));
        musicInitiator = currentSong;
        if (shuffle) {
            java.util.Collections.shuffle(order);
        }
        int qStart = 0;
        for (int i = 0; i < order.size(); i++) {
            items.add(PlayQueue.QueueItem.music(order.get(i)));
            if (order.get(i).equals(currentSong)) qStart = i;
        }
        queue.setAll(items, qStart);
    }

    public void reshuffleMusic(boolean shuffle) {
        if (musicOriginal.isEmpty()) return;
        File current = queue.current() != null ? queue.current().file : null;
        List<File> order = new ArrayList<File>(musicOriginal);
        if (shuffle) java.util.Collections.shuffle(order);
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        int qStart = 0;
        for (int i = 0; i < order.size(); i++) {
            items.add(PlayQueue.QueueItem.music(order.get(i)));
            if (current != null && order.get(i).equals(current)) qStart = i;
        }
        queue.setAll(items, qStart);
    }

    public void appendToMusicQueue(List<File> tracks) {
        if (tracks == null) return;
        for (File f : tracks) {
            if (f != null && f.isFile()) {
                queue.append(PlayQueue.QueueItem.music(f));
                if (!musicOriginal.contains(f)) musicOriginal.add(f);
            }
        }
        if (activeMode == Mode.NONE && !queue.isEmpty()) activeMode = Mode.MUSIC;
    }

    public void appendReachToQueue(File temp, String meta) {
        if (temp == null || !temp.isFile()) return;
        queue.append(PlayQueue.QueueItem.reach(temp, meta));
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        if (activeMode == Mode.NONE) activeMode = Mode.MUSIC;
    }

    public int playReachAfterCurrent(File temp, String meta) {
        if (temp == null || !temp.isFile()) return -1;
        int after = queue.isEmpty() ? -1 : queue.index();
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.reach(temp, meta));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        activeMode = Mode.MUSIC;
        musicInitiator = temp;
        queue.setIndex(insertAt);
        return insertAt;
    }

    public int queueReachAfterCurrent(File temp, String meta) {
        if (temp == null || !temp.isFile()) return -1;
        int playHead = queue.index();
        int after = queue.isEmpty() ? -1 : playHead;
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.reach(temp, meta));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        if (activeMode == Mode.NONE && !queue.isEmpty()) activeMode = Mode.MUSIC;
        if (queue.index() != playHead && !queue.isEmpty()) {
            queue.setIndex(Math.max(0, Math.min(playHead, queue.size() - 1)));
        }
        return insertAt;
    }

    public void replaceReachFileInQueue(File oldF, File newF, String meta) {
        if (oldF == null || newF == null) return;
        queue.replaceFileRef(oldF, newF, meta);
        for (int i = 0; i < musicOriginal.size(); i++) {
            if (oldF.equals(musicOriginal.get(i))) musicOriginal.set(i, newF);
        }
    }

    public void removeMusicTrackAt(int index) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (q.kind == PlayQueue.ItemKind.MUSIC_FILE || q.kind == PlayQueue.ItemKind.REACH_STREAM) {
                if (idx == index) {
                    File removed = q.file;
                    queue.removeAt(i);
                    musicOriginal.remove(removed);
                    return;
                }
                idx++;
            }
        }
    }

    public void moveMusicTrack(int from, int to) {
        int fromQ = musicSlotToQueueIndex(from);
        int toQ = musicSlotToQueueIndex(to);
        if (fromQ < 0 || toQ < 0) return;
        queue.move(fromQ, toQ);
        musicOriginal.clear();
        musicOriginal.addAll(queue.musicFiles());
    }

    private int musicSlotToQueueIndex(int slot) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (q.kind == PlayQueue.ItemKind.MUSIC_FILE || q.kind == PlayQueue.ItemKind.REACH_STREAM) {
                if (idx == slot) return i;
                idx++;
            }
        }
        return -1;
    }

    public void activatePodcast(List<QueueEpisode> episodes, int index, String showTitle,
            boolean fromSavedLibrary) {
        activeMode = Mode.PODCAST;
        podcastFromSavedLibrary = fromSavedLibrary;
        podcastShowTitle = showTitle != null ? showTitle : "";
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        if (episodes != null) {
            for (QueueEpisode ep : episodes) {
                items.add(PlayQueue.QueueItem.podcast(ep, podcastShowTitle, fromSavedLibrary));
            }
        }
        int start = items.isEmpty() ? 0 : Math.max(0, Math.min(index, items.size() - 1));
        queue.setAll(items, start);
    }

    public void moveQueueItem(int from, int to) {
        if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size() || from == to) return;
        queue.move(from, to);
        musicOriginal.clear();
        musicOriginal.addAll(queue.musicFiles());
    }

    public void removeQueueItemAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        queue.removeAt(index);
        musicOriginal.clear();
        musicOriginal.addAll(queue.musicFiles());
        if (queue.isEmpty()) activeMode = Mode.NONE;
    }

    public void restoreQueueState(List<PlayQueue.QueueItem> items, int index) {
        if (items == null || items.isEmpty()) {
            queue.clear();
            musicOriginal.clear();
            activeMode = Mode.NONE;
            musicInitiator = null;
            return;
        }
        queue.setAll(items, index);
        musicOriginal.clear();
        musicOriginal.addAll(queue.musicFiles());
        PlayQueue.QueueItem c = queue.current();
        if (c == null) {
            activeMode = Mode.NONE;
            musicInitiator = null;
            return;
        }
        if (c.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
            activeMode = Mode.PODCAST;
            podcastShowTitle = c.podcastShowTitle;
            podcastFromSavedLibrary = c.podcastFromSaved;
            musicInitiator = null;
        } else if (c.kind == PlayQueue.ItemKind.MUSIC_FILE || c.kind == PlayQueue.ItemKind.REACH_STREAM) {
            activeMode = Mode.MUSIC;
            musicInitiator = c.file;
            podcastShowTitle = "";
            podcastFromSavedLibrary = false;
        } else {
            activeMode = Mode.NONE;
            musicInitiator = null;
        }
    }

    public void clearQueue() {
        queue.clear();
        musicOriginal.clear();
        activeMode = Mode.NONE;
    }

    public int nextIndex(boolean repeatAll) {
        return queue.nextIndex(repeatAll);
    }

    public int prevIndex(boolean repeatAll) {
        return queue.prevIndex(repeatAll);
    }

    public void setQueueIndex(int i) {
        queue.setIndex(i);
    }

    public PlayQueue.QueueItem currentItem() {
        return queue.current();
    }
}
