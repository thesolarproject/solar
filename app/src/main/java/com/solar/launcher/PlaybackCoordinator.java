package com.solar.launcher;

import com.solar.launcher.jellyfin.JellyfinSong;
import com.solar.launcher.navidrome.NavidromeSong;
import com.solar.launcher.plex.PlexSong;
import com.solar.launcher.podcast.OpenRssClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ponytail: unified queue for playback order; legacy music/podcast views synced from PlayQueue. */
public final class PlaybackCoordinator {
    public enum Mode { NONE, MUSIC, PODCAST, RADIO }

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
                || c.kind == PlayQueue.ItemKind.REACH_STREAM
                || c.kind == PlayQueue.ItemKind.DEEZER_STREAM
                || c.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                || c.kind == PlayQueue.ItemKind.PLEX_STREAM
                || c.kind == PlayQueue.ItemKind.JELLYFIN_STREAM));
    }

    public boolean isFmActive() {
        PlayQueue.QueueItem c = queue.current();
        return activeMode == Mode.RADIO
                && c != null && c.kind == PlayQueue.ItemKind.FM_STATION;
    }

    public boolean isInternetRadioActive() {
        PlayQueue.QueueItem c = queue.current();
        return activeMode == Mode.RADIO
                && c != null && c.kind == PlayQueue.ItemKind.INTERNET_RADIO_STATION;
    }

    public boolean isRadioActive() {
        return isFmActive() || isInternetRadioActive();
    }

    /** Clear queue and play a single FM or internet station. */
    public void startRadioStation(PlayQueue.QueueItem station) {
        if (station == null) return;
        musicOriginal.clear();
        musicInitiator = null;
        musicActivePlaylistName = null;
        podcastShowTitle = "";
        podcastFromSavedLibrary = false;
        List<PlayQueue.QueueItem> one = new ArrayList<PlayQueue.QueueItem>();
        one.add(station);
        queue.setAll(one, 0);
        activeMode = Mode.RADIO;
    }

    /**
     * 2026-07-06 — FM station queue from saved presets; immune to shuffle/repeat prefs.
     * Layman: every saved station is a row in Now Playing queue order.
     */
    public void syncFmQueue(List<PlayQueue.QueueItem> stations, int playIndex) {
        if (stations == null || stations.isEmpty()) return;
        musicOriginal.clear();
        musicInitiator = null;
        musicActivePlaylistName = null;
        podcastShowTitle = "";
        podcastFromSavedLibrary = false;
        int idx = Math.max(0, Math.min(playIndex, stations.size() - 1));
        queue.setAll(stations, idx);
        activeMode = Mode.RADIO;
    }

    /** 2026-07-06 — RDS PS or tune commit updates Now Playing queue title. */
    public void updateCurrentFmMeta(int freqKhz, String label) {
        if (!isFmActive()) return;
        queue.replaceFmAt(queue.index(), freqKhz, label != null ? label : "");
    }

    /** FM prev/next — wrap at ends; ignores music shuffle/repeat. */
    public int fmWrappedIndex(int delta) {
        if (!isFmActive() || queue.size() <= 1) return -1;
        int next = queue.index() + delta;
        if (next < 0) next = queue.size() - 1;
        if (next >= queue.size()) next = 0;
        return next;
    }

    public PlayQueue.QueueItem fmItemAtWrappedIndex(int delta) {
        int idx = fmWrappedIndex(delta);
        if (idx < 0) return null;
        queue.setIndex(idx);
        return queue.current();
    }

    /** 2026-07-06 — FM power down clears radio queue head (JJ music/radio mutex). */
    public void stopRadio() {
        if (!isRadioActive()) return;
        queue.clear();
        activeMode = Mode.NONE;
        musicInitiator = null;
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

    /**
     * Music-like ordinal of the play-head (0-based).
     * Layman: which song slot is “now” in the music list, even if two rows are the same file.
     * Technical: count music-like rows up to queue.index() — not first musicLikeSame match.
     * Was: first equal file won → skip to a twin kept showing/preparing the earlier row. 2026-07-20
     * Reversal: restore musicLikeSame(cur) scan below.
     */
    public int musicIndex() {
        syncLegacyMusicIndex();
        int qi = queue.index();
        int musicIdx = 0;
        java.util.List<PlayQueue.QueueItem> items = queue.items();
        for (int i = 0; i < items.size(); i++) {
            PlayQueue.QueueItem q = items.get(i);
            if (!isMusicLike(q)) continue;
            if (i == qi) return musicIdx;
            musicIdx++;
        }
        return 0;
    }

    public void setMusicIndex(int index) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (isMusicLike(q)) {
                if (idx == index) {
                    queue.setIndex(i);
                    return;
                }
                idx++;
            }
        }
        queue.clampIndex();
    }

    /** 2026-07-06: Track position denominator — includes Navidrome rows without Files. */
    public int musicSlotCount() {
        return queue.musicLikeCount();
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

    /** Now Playing line 4 — track position without leading zeros (e.g. "4 / 12"). */
    public static String formatTrackPositionPlain(int index, int total) {
        if (total <= 0) return "— / —";
        int pos = index + 1;
        if (pos < 1) pos = 1;
        if (pos > total) pos = total;
        return String.format(java.util.Locale.US, "%d / %d", pos, total);
    }

    public String formatActivePosition() {
        return formatTrackPosition(queue.index(), queue.size());
    }

    public List<OpenRssClient.Episode> podcastQueue() {
        return queue.podcastEpisodes();
    }

    /**
     * Podcast ordinal of the play-head (0-based), or -1 if head is not an episode.
     * Layman: which episode number is playing when the queue mixes music + shows.
     * Technical: slot-based like musicIndex — duplicate episode refs must not pin to first. 2026-07-20
     * Reversal: restore episode==cur.episode first-match loop.
     */
    public int podcastIndex() {
        PlayQueue.QueueItem cur = queue.current();
        if (cur == null || cur.kind != PlayQueue.ItemKind.PODCAST_EPISODE) return -1;
        int qi = queue.index();
        int idx = 0;
        java.util.List<PlayQueue.QueueItem> items = queue.items();
        for (int i = 0; i < items.size(); i++) {
            PlayQueue.QueueItem q = items.get(i);
            if (q.kind != PlayQueue.ItemKind.PODCAST_EPISODE) continue;
            if (i == qi) return idx;
            idx++;
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
        // 2026-07-20 — Slot index wins when unshuffled; equals()-last-match jumped to a twin.
        // Layman: tap song #1 of two identical files — play that row, not the later copy.
        // Technical: qStart = clamped when !shuffle; shuffle uses first equals match.
        // Reversal: loop equals without break setting qStart = i each hit.
        int clamped = Math.max(0, Math.min(startIndex, playlist.size() - 1));
        File currentSong = playlist.get(clamped);
        musicInitiator = currentSong;
        if (shuffle) {
            java.util.Collections.shuffle(order);
        }
        for (int i = 0; i < order.size(); i++) {
            items.add(PlayQueue.QueueItem.music(order.get(i)));
        }
        int qStart = clamped;
        if (shuffle) {
            qStart = 0;
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).equals(currentSong)) {
                    qStart = i;
                    break;
                }
            }
        }
        queue.setAll(items, qStart);
    }

    /**
     * 2026-07-06: Replace queue with Navidrome album/playlist/all-songs — mirrors activateMusic.
     * Reversal: prior parallel navidromePlaybackQueue in MainActivity (removed).
     */
    public void activateNavidrome(List<NavidromeSong> songs, int startIndex, boolean shuffle,
            String playlistLabel) {
        musicOriginal.clear();
        musicInitiator = null;
        if (songs == null || songs.isEmpty()) {
            queue.clear();
            activeMode = Mode.NONE;
            musicActivePlaylistName = null;
            return;
        }
        activeMode = Mode.MUSIC;
        musicActivePlaylistName = (playlistLabel != null && !playlistLabel.isEmpty()) ? playlistLabel : null;
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        List<NavidromeSong> order = new ArrayList<NavidromeSong>(songs);
        // 2026-07-20 — Same twin-id trap as activateMusic; slot when !shuffle.
        int clamped = Math.max(0, Math.min(startIndex, songs.size() - 1));
        NavidromeSong currentSong = songs.get(clamped);
        if (shuffle) {
            java.util.Collections.shuffle(order);
        }
        for (int i = 0; i < order.size(); i++) {
            NavidromeSong s = order.get(i);
            items.add(PlayQueue.QueueItem.navidrome(
                    s.id, s.title, s.artist, s.album, s.coverArtId));
        }
        int qStart = clamped;
        if (shuffle) {
            qStart = 0;
            for (int i = 0; i < order.size(); i++) {
                NavidromeSong s = order.get(i);
                if (s.id != null && s.id.equals(currentSong.id)) {
                    qStart = i;
                    break;
                }
            }
        }
        queue.setAll(items, qStart);
    }

    /** 2026-07-14: Plex album/playlist queue — mirrors activateNavidrome. */
    public void activatePlex(List<PlexSong> songs, int startIndex, boolean shuffle,
            String playlistLabel) {
        musicOriginal.clear();
        musicInitiator = null;
        if (songs == null || songs.isEmpty()) {
            queue.clear();
            activeMode = Mode.NONE;
            musicActivePlaylistName = null;
            return;
        }
        activeMode = Mode.MUSIC;
        musicActivePlaylistName = (playlistLabel != null && !playlistLabel.isEmpty()) ? playlistLabel : null;
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        List<PlexSong> order = new ArrayList<PlexSong>(songs);
        // 2026-07-20 — Slot when !shuffle; avoid last-id-match twin jump.
        int clamped = Math.max(0, Math.min(startIndex, songs.size() - 1));
        PlexSong currentSong = songs.get(clamped);
        if (shuffle) {
            java.util.Collections.shuffle(order);
        }
        for (int i = 0; i < order.size(); i++) {
            PlexSong s = order.get(i);
            // 2026-07-15: Keep Part.key/container so preparePlexStream can direct-play mp3/m4a.
            items.add(PlayQueue.QueueItem.plex(s.id, s.title, s.artist, s.album, s.coverArtId,
                    s.mediaPartKey, s.container));
        }
        int qStart = clamped;
        if (shuffle) {
            qStart = 0;
            for (int i = 0; i < order.size(); i++) {
                PlexSong s = order.get(i);
                if (s.id != null && s.id.equals(currentSong.id)) {
                    qStart = i;
                    break;
                }
            }
        }
        // #region agent log
        try {
            PlexSong probe = songs.get(clamped);
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("count", songs.size());
            d.put("partKeyLen", probe.mediaPartKey != null ? probe.mediaPartKey.length() : 0);
            d.put("container", probe.container != null ? probe.container : "");
            d.put("queuePartLen", items.get(qStart).plexMediaPartKey != null
                    ? items.get(qStart).plexMediaPartKey.length() : 0);
            com.solar.launcher.debug.Debug2241b1Log.log(
                    "PlaybackCoordinator.activatePlex", "queue carries part meta", "A", "post-fix", d);
        } catch (Exception ignored) {}
        // #endregion
        queue.setAll(items, qStart);
    }

    /** 2026-07-14: Jellyfin album/playlist queue — mirrors activateNavidrome. */
    public void activateJellyfin(List<JellyfinSong> songs, int startIndex, boolean shuffle,
            String playlistLabel) {
        musicOriginal.clear();
        musicInitiator = null;
        if (songs == null || songs.isEmpty()) {
            queue.clear();
            activeMode = Mode.NONE;
            musicActivePlaylistName = null;
            return;
        }
        activeMode = Mode.MUSIC;
        musicActivePlaylistName = (playlistLabel != null && !playlistLabel.isEmpty()) ? playlistLabel : null;
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        List<JellyfinSong> order = new ArrayList<JellyfinSong>(songs);
        // 2026-07-20 — Slot when !shuffle; avoid last-id-match twin jump.
        int clamped = Math.max(0, Math.min(startIndex, songs.size() - 1));
        JellyfinSong currentSong = songs.get(clamped);
        if (shuffle) {
            java.util.Collections.shuffle(order);
        }
        for (int i = 0; i < order.size(); i++) {
            JellyfinSong s = order.get(i);
            items.add(PlayQueue.QueueItem.jellyfin(s.id, s.title, s.artist, s.album, s.coverArtId));
        }
        int qStart = clamped;
        if (shuffle) {
            qStart = 0;
            for (int i = 0; i < order.size(); i++) {
                JellyfinSong s = order.get(i);
                if (s.id != null && s.id.equals(currentSong.id)) {
                    qStart = i;
                    break;
                }
            }
        }
        queue.setAll(items, qStart);
    }

    /** Shuffle or restore music/stream slots in the unified queue; preserves Reach/Deezer item kinds. */
    public void reshuffleMusic(boolean shuffle) {
        java.util.List<PlayQueue.QueueItem> all = new java.util.ArrayList<PlayQueue.QueueItem>(queue.items());
        if (all.isEmpty()) return;
        PlayQueue.QueueItem current = queue.current();
        java.util.List<PlayQueue.QueueItem> musicLike = new java.util.ArrayList<PlayQueue.QueueItem>();
        for (PlayQueue.QueueItem q : all) {
            if (isMusicLike(q)) musicLike.add(q);
        }
        if (musicLike.size() <= 1) return;

        java.util.List<PlayQueue.QueueItem> ordered;
        if (shuffle) {
            ordered = new java.util.ArrayList<PlayQueue.QueueItem>(musicLike);
            java.util.Collections.shuffle(ordered);
        } else {
            ordered = restoreMusicQueueOrder(musicLike);
        }
        int mi = 0;
        for (int i = 0; i < all.size(); i++) {
            if (isMusicLike(all.get(i))) {
                all.set(i, ordered.get(mi++));
            }
        }
        int newIndex = resolveCurrentQueueIndex(all, current);
        queue.setAll(all, newIndex);
    }

    private static boolean isMusicLike(PlayQueue.QueueItem q) {
        return q != null && (q.kind == PlayQueue.ItemKind.MUSIC_FILE
                || q.kind == PlayQueue.ItemKind.REACH_STREAM
                || q.kind == PlayQueue.ItemKind.DEEZER_STREAM
                || q.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                || q.kind == PlayQueue.ItemKind.PLEX_STREAM
                || q.kind == PlayQueue.ItemKind.JELLYFIN_STREAM);
    }

    private static boolean musicLikeSame(PlayQueue.QueueItem a, PlayQueue.QueueItem b) {
        if (a == null || b == null || a.kind != b.kind) return false;
        if (a.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                || a.kind == PlayQueue.ItemKind.PLEX_STREAM
                || a.kind == PlayQueue.ItemKind.JELLYFIN_STREAM) {
            return a.navidromeSongId != null && a.navidromeSongId.equals(b.navidromeSongId);
        }
        if (a.file != null && b.file != null) return a.file.equals(b.file);
        return a == b;
    }

    private java.util.List<PlayQueue.QueueItem> restoreMusicQueueOrder(
            java.util.List<PlayQueue.QueueItem> items) {
        java.util.Map<File, PlayQueue.QueueItem> byFile = new java.util.HashMap<File, PlayQueue.QueueItem>();
        for (PlayQueue.QueueItem q : items) {
            if (q.file != null) byFile.put(q.file, q);
        }
        java.util.List<PlayQueue.QueueItem> out = new java.util.ArrayList<PlayQueue.QueueItem>();
        java.util.Set<File> used = new java.util.HashSet<File>();
        for (File f : musicOriginal) {
            PlayQueue.QueueItem q = byFile.get(f);
            if (q != null) {
                out.add(q);
                used.add(f);
            }
        }
        for (PlayQueue.QueueItem q : items) {
            if (q.file != null && !used.contains(q.file)) out.add(q);
        }
        return out;
    }

    private static int resolveCurrentQueueIndex(java.util.List<PlayQueue.QueueItem> items,
                                                PlayQueue.QueueItem current) {
        if (current == null || items.isEmpty()) return 0;
        for (int i = 0; i < items.size(); i++) {
            PlayQueue.QueueItem q = items.get(i);
            if (current.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
                if (q.kind == current.kind && q.episode == current.episode) return i;
            } else if (current.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                    || current.kind == PlayQueue.ItemKind.PLEX_STREAM
                    || current.kind == PlayQueue.ItemKind.JELLYFIN_STREAM) {
                if (q.kind == current.kind && musicLikeSame(q, current)) return i;
            } else if (current.file != null && q.file != null
                    && current.file.equals(q.file) && q.kind == current.kind) {
                return i;
            }
        }
        return 0;
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
        appendReachToQueue(temp, meta, null);
    }

    public void appendReachToQueue(File temp, String meta, String peerUsername) {
        if (temp == null || !temp.isFile()) return;
        queue.append(PlayQueue.QueueItem.reach(temp, meta, peerUsername));
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        if (activeMode == Mode.NONE) activeMode = Mode.MUSIC;
    }

    /** Insert Reach stream after the current queue item and select it for playback. */
    public int playReachAfterCurrent(File temp, String meta) {
        return playReachAfterCurrent(temp, meta, null);
    }

    public int playReachAfterCurrent(File temp, String meta, String peerUsername) {
        if (temp == null || !temp.isFile()) return -1;
        int after = queue.isEmpty() ? -1 : queue.index();
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.reach(temp, meta, peerUsername));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        activeMode = Mode.MUSIC;
        musicInitiator = temp;
        queue.setIndex(insertAt);
        return insertAt;
    }

    /** Insert Reach after now-playing without moving the play head or starting playback. */
    public int queueReachAfterCurrent(File temp, String meta) {
        return queueReachAfterCurrent(temp, meta, null);
    }

    public int queueReachAfterCurrent(File temp, String meta, String peerUsername) {
        if (temp == null || !temp.isFile()) return -1;
        int playHead = queue.index();
        int after = queue.isEmpty() ? -1 : playHead;
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.reach(temp, meta, peerUsername));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        if (activeMode == Mode.NONE && !queue.isEmpty()) activeMode = Mode.MUSIC;
        if (queue.index() != playHead && !queue.isEmpty()) {
            queue.setIndex(Math.max(0, Math.min(playHead, queue.size() - 1)));
        }
        return insertAt;
    }

    private File streamMusicRoot;
    private File streamAppCacheRoot;

    public void configureStreamPaths(File musicRoot, File appCacheRoot) {
        streamMusicRoot = musicRoot;
        streamAppCacheRoot = appCacheRoot;
    }

    public void replaceReachFileInQueue(File oldF, File newF, String meta) {
        finishStreamFileInQueue(oldF, newF, meta);
    }

    public void finishStreamFileInQueue(File oldF, File newF, String meta) {
        if (oldF == null || newF == null) return;
        if (StreamQueueHelper.isLibraryMusicFile(streamMusicRoot, streamAppCacheRoot, newF)) {
            queue.promoteStreamToMusic(oldF, newF);
        } else {
            queue.replaceFileRef(oldF, newF, meta);
        }
        for (int i = 0; i < musicOriginal.size(); i++) {
            if (oldF.equals(musicOriginal.get(i))) musicOriginal.set(i, newF);
        }
    }

    public int playDeezerAfterCurrent(File temp, String meta, long trackId) {
        if (temp == null || !temp.isFile()) return -1;
        int after = queue.isEmpty() ? -1 : queue.index();
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.deezer(temp, meta, trackId));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        activeMode = Mode.MUSIC;
        musicInitiator = temp;
        queue.setIndex(insertAt);
        return insertAt;
    }

    public int queueDeezerAfterCurrent(File temp, String meta, long trackId) {
        if (temp == null || !temp.isFile()) return -1;
        int playHead = queue.index();
        int after = queue.isEmpty() ? -1 : playHead;
        int insertAt = queue.insertAfter(after, PlayQueue.QueueItem.deezer(temp, meta, trackId));
        if (insertAt < 0) return -1;
        if (!musicOriginal.contains(temp)) musicOriginal.add(temp);
        if (activeMode == Mode.NONE && !queue.isEmpty()) activeMode = Mode.MUSIC;
        if (queue.index() != playHead && !queue.isEmpty()) {
            queue.setIndex(Math.max(0, Math.min(playHead, queue.size() - 1)));
        }
        return insertAt;
    }

    public void replaceDeezerFileInQueue(File oldF, File newF, String meta) {
        finishStreamFileInQueue(oldF, newF, meta);
    }

    public int playPodcastAfterCurrent(OpenRssClient.Episode ep, String showTitle, boolean fromSaved) {
        if (ep == null) return -1;
        int after = queue.isEmpty() ? -1 : queue.index();
        int insertAt = queue.insertAfter(after,
                PlayQueue.QueueItem.podcast(ep, showTitle != null ? showTitle : "", fromSaved));
        if (insertAt < 0) return -1;
        activeMode = Mode.PODCAST;
        podcastShowTitle = showTitle != null ? showTitle : "";
        podcastFromSavedLibrary = fromSaved;
        musicInitiator = null;
        queue.setIndex(insertAt);
        return insertAt;
    }

    public int queuePodcastAfterCurrent(OpenRssClient.Episode ep, String showTitle, boolean fromSaved) {
        if (ep == null) return -1;
        int playHead = queue.index();
        int after = queue.isEmpty() ? -1 : playHead;
        int insertAt = queue.insertAfter(after,
                PlayQueue.QueueItem.podcast(ep, showTitle != null ? showTitle : "", fromSaved));
        if (insertAt < 0) return -1;
        if (activeMode == Mode.NONE && !queue.isEmpty()) activeMode = Mode.PODCAST;
        if (queue.index() != playHead && !queue.isEmpty()) {
            queue.setIndex(Math.max(0, Math.min(playHead, queue.size() - 1)));
        }
        return insertAt;
    }

    public void removeMusicTrackAt(int index) {
        int idx = 0;
        for (int i = 0; i < queue.items().size(); i++) {
            PlayQueue.QueueItem q = queue.items().get(i);
            if (isMusicLike(q)) {
                if (idx == index) {
                    File removed = q.file;
                    queue.removeAt(i);
                    if (removed != null) musicOriginal.remove(removed);
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
            if (isMusicLike(q)) {
                if (idx == slot) return i;
                idx++;
            }
        }
        return -1;
    }

    public void activatePodcast(List<OpenRssClient.Episode> episodes, int index, String showTitle,
            boolean fromSavedLibrary) {
        activeMode = Mode.PODCAST;
        podcastFromSavedLibrary = fromSavedLibrary;
        podcastShowTitle = showTitle != null ? showTitle : "";
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        if (episodes != null) {
            for (OpenRssClient.Episode ep : episodes) {
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

    /** Cold start — restore unified order without rebuilding music/podcast subsets. */
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
        } else if (c.kind == PlayQueue.ItemKind.MUSIC_FILE || c.kind == PlayQueue.ItemKind.REACH_STREAM
                || c.kind == PlayQueue.ItemKind.DEEZER_STREAM
                || c.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                || c.kind == PlayQueue.ItemKind.PLEX_STREAM
                || c.kind == PlayQueue.ItemKind.JELLYFIN_STREAM) {
            activeMode = Mode.MUSIC;
            musicInitiator = c.file;
            podcastShowTitle = "";
            podcastFromSavedLibrary = false;
        } else if (c.kind == PlayQueue.ItemKind.FM_STATION
                || c.kind == PlayQueue.ItemKind.INTERNET_RADIO_STATION) {
            activeMode = Mode.RADIO;
            musicInitiator = null;
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

    /**
     * Stem/Mix session start — wipe play queue and seed jam tracks (unified spine).
     * Layman: when a jam begins, the play queue becomes those jam songs.
     * Technical: {@link com.solar.launcher.stem.StemMixQueuePolicy#clearAndSeed} + MUSIC mode.
     * Was: jam slots only; music queue left untouched. Reversal: drop call at openStem/openMix.
     * 2026-07-21
     */
    public void seedStemMixJam(java.util.List<java.io.File> tracks) {
        musicInitiator = null;
        musicActivePlaylistName = null;
        podcastShowTitle = "";
        podcastFromSavedLibrary = false;
        com.solar.launcher.stem.StemMixQueuePolicy.clearAndSeed(queue, tracks);
        musicOriginal.clear();
        musicOriginal.addAll(queue.musicFiles());
        activeMode = queue.isEmpty() ? Mode.NONE : Mode.MUSIC;
        if (!queue.isEmpty() && queue.current() != null && queue.current().file != null) {
            musicInitiator = queue.current().file;
        }
    }

    /**
     * Hold-replace reorder on the unified queue (live slot ↔ pick).
     * Layman: put the chosen song on that pad’s seat in the shared queue.
     * 2026-07-21
     */
    public boolean applyStemMixHoldReplace(int liveSlot, int pickIndex, boolean mixMode) {
        int win = com.solar.launcher.stem.StemMixQueuePolicy.liveWindow(mixMode);
        boolean ok = com.solar.launcher.stem.StemMixQueuePolicy.applyHoldReplaceOrder(
                queue, liveSlot, pickIndex, win);
        if (ok) {
            musicOriginal.clear();
            musicOriginal.addAll(queue.musicFiles());
        }
        return ok;
    }

    /**
     * Pair/triple advance: swap finished live seat with Next-up source.
     * 2026-07-21
     */
    public boolean applyStemMixAdvance(int liveSlot, int sourceIndex, boolean mixMode) {
        int win = com.solar.launcher.stem.StemMixQueuePolicy.liveWindow(mixMode);
        boolean ok = com.solar.launcher.stem.StemMixQueuePolicy.applyAdvanceOrder(
                queue, liveSlot, sourceIndex, win);
        if (ok) {
            musicOriginal.clear();
            musicOriginal.addAll(queue.musicFiles());
        }
        return ok;
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
