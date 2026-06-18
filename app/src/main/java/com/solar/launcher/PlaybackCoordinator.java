package com.solar.launcher;

import com.solar.launcher.podcast.OpenRssClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ponytail: exclusive music vs podcast queues; inactive session kept in memory. */
public final class PlaybackCoordinator {
    public enum Mode { NONE, MUSIC, PODCAST }

    private Mode activeMode = Mode.NONE;
    private final List<File> musicOriginal = new ArrayList<File>();
    private final List<File> musicActive = new ArrayList<File>();
    private int musicIndex = 0;
    private final List<OpenRssClient.Episode> podcastQueue = new ArrayList<OpenRssClient.Episode>();
    private int podcastIndex = -1;
    private String podcastShowTitle = "";
    private boolean podcastFromSavedLibrary = false;

    public Mode activeMode() {
        return activeMode;
    }

    public boolean isPodcastActive() {
        return activeMode == Mode.PODCAST;
    }

    public boolean isMusicActive() {
        return activeMode == Mode.MUSIC;
    }

    public boolean hasAnyQueue() {
        return !musicActive.isEmpty() || !podcastQueue.isEmpty();
    }

    public List<File> musicPlaylist() {
        return musicActive;
    }

    public List<File> musicOriginal() {
        return musicOriginal;
    }

    public int musicIndex() {
        return musicIndex;
    }

    public void setMusicIndex(int index) {
        musicIndex = index;
    }

    public List<OpenRssClient.Episode> podcastQueue() {
        return podcastQueue;
    }

    public int podcastIndex() {
        return podcastIndex;
    }

    public void setPodcastIndex(int index) {
        podcastIndex = index;
    }

    public String podcastShowTitle() {
        return podcastShowTitle;
    }

    public boolean podcastFromSavedLibrary() {
        return podcastFromSavedLibrary;
    }

    private File musicInitiator;
    private String musicActivePlaylistName;

    public File musicInitiator() {
        return musicInitiator;
    }

    public String musicActivePlaylistName() {
        return musicActivePlaylistName;
    }

    public void setMusicActivePlaylistName(String name) {
        musicActivePlaylistName = (name != null && !name.isEmpty()) ? name : null;
    }

    public void activateMusic(List<File> playlist, int startIndex, boolean shuffle) {
        activeMode = Mode.MUSIC;
        musicOriginal.clear();
        musicActive.clear();
        musicInitiator = null;
        if (playlist == null || playlist.isEmpty()) {
            musicIndex = 0;
            return;
        }
        musicOriginal.addAll(playlist);
        musicActive.addAll(playlist);
        File currentSong = musicOriginal.get(Math.max(0, Math.min(startIndex, musicOriginal.size() - 1)));
        musicInitiator = currentSong;
        if (shuffle) {
            java.util.Collections.shuffle(musicActive);
            musicIndex = musicActive.indexOf(currentSong);
            if (musicIndex < 0) musicIndex = 0;
        } else {
            musicIndex = Math.max(0, Math.min(startIndex, musicActive.size() - 1));
        }
    }

    public void reshuffleMusic(boolean shuffle) {
        if (musicOriginal.isEmpty()) return;
        File current = musicActive.isEmpty() ? null : musicActive.get(musicIndex);
        if (shuffle) {
            java.util.Collections.shuffle(musicActive);
        } else {
            musicActive.clear();
            musicActive.addAll(musicOriginal);
        }
        if (current != null) {
            musicIndex = musicActive.indexOf(current);
            if (musicIndex < 0) musicIndex = 0;
        }
    }

    public void appendToMusicQueue(List<File> tracks) {
        if (tracks == null) return;
        for (File f : tracks) {
            if (f != null && f.isFile()) {
                musicActive.add(f);
                if (!musicOriginal.contains(f)) musicOriginal.add(f);
            }
        }
        if (activeMode == Mode.NONE && !musicActive.isEmpty()) activeMode = Mode.MUSIC;
    }

    public void removeMusicTrackAt(int index) {
        if (index < 0 || index >= musicActive.size()) return;
        File removed = musicActive.remove(index);
        if (musicIndex >= musicActive.size()) musicIndex = Math.max(0, musicActive.size() - 1);
        if (musicIndex > index) musicIndex--;
        else if (musicIndex == index && musicIndex >= musicActive.size()) musicIndex = Math.max(0, musicActive.size() - 1);
        musicOriginal.remove(removed);
    }

    public void moveMusicTrack(int from, int to) {
        if (from < 0 || from >= musicActive.size() || to < 0 || to >= musicActive.size() || from == to) return;
        File f = musicActive.remove(from);
        musicActive.add(to, f);
        if (musicIndex == from) musicIndex = to;
        else if (from < musicIndex && to >= musicIndex) musicIndex--;
        else if (from > musicIndex && to <= musicIndex) musicIndex++;
        // ponytail: manual queue order is authoritative; shuffle-off restores this order
        musicOriginal.clear();
        musicOriginal.addAll(musicActive);
    }

    public void activatePodcast(List<OpenRssClient.Episode> episodes, int index, String showTitle,
            boolean fromSavedLibrary) {
        activeMode = Mode.PODCAST;
        podcastFromSavedLibrary = fromSavedLibrary;
        podcastShowTitle = showTitle != null ? showTitle : "";
        podcastQueue.clear();
        if (episodes != null) podcastQueue.addAll(episodes);
        podcastIndex = podcastQueue.isEmpty()
                ? -1
                : Math.max(0, Math.min(index, podcastQueue.size() - 1));
    }
}
