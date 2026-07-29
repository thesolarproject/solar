package com.solar.launcher;

import com.solar.launcher.podcast.OpenRssClient;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PlayQueueTest {

    @Test
    public void activatePlex_preservesPartKeyForDirectStream() throws Exception {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        java.util.List<com.solar.launcher.plex.PlexSong> songs =
                new java.util.ArrayList<com.solar.launcher.plex.PlexSong>();
        com.solar.launcher.plex.PlexSong s = new com.solar.launcher.plex.PlexSong();
        s.id = "99";
        s.title = "Song";
        s.artist = "Artist";
        s.album = "Album";
        s.coverArtId = "c";
        s.mediaPartKey = "/library/parts/1/file.mp3";
        s.container = "mp3";
        songs.add(s);
        pc.activatePlex(songs, 0, false, null);
        PlayQueue.QueueItem q = pc.unifiedQueue().current();
        if (q == null || q.kind != PlayQueue.ItemKind.PLEX_STREAM) {
            throw new AssertionError("kind");
        }
        if (!"/library/parts/1/file.mp3".equals(q.plexMediaPartKey)) {
            throw new AssertionError("part dropped: " + q.plexMediaPartKey);
        }
        if (!"mp3".equals(q.plexContainer)) {
            throw new AssertionError("container dropped: " + q.plexContainer);
        }
        org.json.JSONObject d = new org.json.JSONObject();
        d.put("partKeyLen", q.plexMediaPartKey.length());
        d.put("container", q.plexContainer);
        com.solar.launcher.debug.Debug2241b1Log.log(
                "PlayQueueTest.activatePlex", "queue keeps part meta", "A", "post-fix", d);
    }

    @Test
    public void mixedQueue_nextPrev() {
        PlayQueue q = new PlayQueue();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/b.mp3")));
        q.setAll(items, 0);
        if (q.nextIndex(false) != 1) throw new AssertionError("next");
        if (q.prevIndex(false) != -1) throw new AssertionError("prev at start");
        q.setIndex(1);
        if (q.nextIndex(false) != -1) throw new AssertionError("next at end");
    }

    @Test
    public void playbackCoordinator_unifiedPosition() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<File> pl = new ArrayList<File>();
        pl.add(new File("/x.mp3"));
        pl.add(new File("/y.mp3"));
        pc.activateMusic(pl, 1, false);
        if (!"02 / 02".equals(pc.formatActivePosition())) throw new AssertionError("position");
    }

    @Test
    public void playbackCoordinator_restoreMixedQueue() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.podcast(
                new OpenRssClient.Episode("ep", "http://x", ""), "Show", false));
        items.add(PlayQueue.QueueItem.music(new File("/b.mp3")));
        pc.restoreQueueState(items, 1);
        if (pc.unifiedQueue().size() != 3) throw new AssertionError("size");
        if (pc.unifiedQueue().index() != 1) throw new AssertionError("index");
        if (!pc.isPodcastActive()) throw new AssertionError("podcast active");
        if (pc.podcastQueue().size() != 1) throw new AssertionError("podcast subset");
        if (pc.musicPlaylist().size() != 2) throw new AssertionError("music subset");
    }

    @Test
    public void move_withNowPlayingIndexFollowsTrack() {
        PlayQueue q = new PlayQueue();
        java.util.List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/baby.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/c.mp3")));
        q.setAll(items, 1);
        q.move(1, 0);
        if (q.index() != 0) throw new AssertionError("np index follows track");
        if (!new File("/baby.mp3").equals(q.items().get(0).file)) {
            throw new AssertionError("baby slot");
        }
    }

    @Test
    public void insertAfter_currentIndex() {
        PlayQueue q = new PlayQueue();
        java.util.List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/b.mp3")));
        q.setAll(items, 0);
        int at = q.insertAfter(0, PlayQueue.QueueItem.reach(new File("/r.tmp"), "reach"));
        if (at != 1) throw new AssertionError("insert at 1");
        if (q.size() != 3) throw new AssertionError("size");
        if (!new File("/r.tmp").equals(q.items().get(1).file)) {
            throw new AssertionError("reach slot");
        }
    }

    @Test
    public void playbackCoordinator_playReachAfterCurrent() throws Exception {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<File> pl = new ArrayList<File>();
        pl.add(new File("/a.mp3"));
        pc.activateMusic(pl, 0, false);
        File tmp = File.createTempFile("stream", ".tmp");
        tmp.deleteOnExit();
        int at = pc.playReachAfterCurrent(tmp, "stream");
        if (at != 1) throw new AssertionError("after current");
        if (pc.unifiedQueue().index() != 1) throw new AssertionError("index on stream");
        if (pc.unifiedQueue().size() != 2) throw new AssertionError("size");
    }

    @Test
    public void playbackCoordinator_queueReachAfterCurrent_keepsPlayHead() throws Exception {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<File> pl = new ArrayList<File>();
        pl.add(new File("/a.mp3"));
        pl.add(new File("/b.mp3"));
        pc.activateMusic(pl, 0, false);
        File tmp = File.createTempFile("stream", ".tmp");
        tmp.deleteOnExit();
        int at = pc.queueReachAfterCurrent(tmp, "reach");
        if (at != 1) throw new AssertionError("insert after current");
        if (pc.unifiedQueue().index() != 0) throw new AssertionError("play head unchanged");
        if (pc.unifiedQueue().size() != 3) throw new AssertionError("size");
        if (pc.unifiedQueue().items().get(1).kind != PlayQueue.ItemKind.REACH_STREAM) {
            throw new AssertionError("reach kind");
        }
    }

    @Test
    public void replaceFileRef_updatesReachSlot() {
        PlayQueue q = new PlayQueue();
        File partial = new File("/cache/partial.tmp");
        File complete = new File("/cache/complete.mp3");
        q.append(PlayQueue.QueueItem.music(new File("/a.mp3")));
        q.append(PlayQueue.QueueItem.reach(partial, "Song"));
        q.replaceFileRef(partial, complete, "Song");
        if (!complete.equals(q.items().get(1).file)) throw new AssertionError("replaced file");
    }

    @Test
    public void replaceFileRef_preservesReachPeer() {
        PlayQueue q = new PlayQueue();
        File partial = new File("/cache/partial.tmp");
        File complete = new File("/cache/complete.mp3");
        q.append(PlayQueue.QueueItem.reach(partial, "Song", "sharer"));
        q.replaceFileRef(partial, complete, "Song");
        if (!"sharer".equals(q.items().get(0).reachPeerUsername)) throw new AssertionError("peer");
    }

    @Test
    public void promoteStreamToMusic_reachAndDeezer() {
        PlayQueue q = new PlayQueue();
        File partial = new File("/cache/partial.tmp");
        File library = new File("/storage/sdcard0/Music/song.mp3");
        q.append(PlayQueue.QueueItem.reach(partial, "Reach Song"));
        q.promoteStreamToMusic(partial, library);
        if (q.items().get(0).kind != PlayQueue.ItemKind.MUSIC_FILE) throw new AssertionError("reach promoted");
        if (!library.equals(q.items().get(0).file)) throw new AssertionError("library path");

        q.clear();
        File dzPartial = new File("/cache/deezer.part");
        q.append(PlayQueue.QueueItem.deezer(dzPartial, "Deezer Song", 42L));
        q.promoteStreamToMusic(dzPartial, library);
        if (q.items().get(0).kind != PlayQueue.ItemKind.MUSIC_FILE) throw new AssertionError("deezer promoted");
    }

    @Test
    public void playbackCoordinator_finishStreamFileInQueue_promotesToMusic() throws Exception {
        java.io.File musicRoot = java.io.File.createTempFile("music", "");
        musicRoot.delete();
        musicRoot.mkdirs();
        java.io.File cacheRoot = java.io.File.createTempFile("cache", "");
        cacheRoot.delete();
        cacheRoot.mkdirs();
        java.io.File partial = new java.io.File(cacheRoot, "reach/part.tmp");
        partial.getParentFile().mkdirs();
        partial.createNewFile();
        java.io.File library = new java.io.File(musicRoot, "saved.mp3");
        library.createNewFile();

        PlaybackCoordinator pc = new PlaybackCoordinator();
        pc.configureStreamPaths(musicRoot, cacheRoot);
        java.util.List<java.io.File> pl = new java.util.ArrayList<java.io.File>();
        pl.add(partial);
        pc.activateMusic(pl, 0, false);
        pc.finishStreamFileInQueue(partial, library, "saved.mp3");
        if (pc.unifiedQueue().items().get(0).kind != PlayQueue.ItemKind.MUSIC_FILE) {
            throw new AssertionError("promoted in coordinator");
        }
    }

    @Test
    public void playbackCoordinator_playPodcastAfterCurrent_mixesWithMusic() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        java.util.List<java.io.File> pl = new java.util.ArrayList<java.io.File>();
        pl.add(new java.io.File("/a.mp3"));
        pl.add(new java.io.File("/b.mp3"));
        pc.activateMusic(pl, 0, false);
        OpenRssClient.Episode ep = new OpenRssClient.Episode("ep1", "http://x", "");
        int at = pc.playPodcastAfterCurrent(ep, "Show", false);
        if (at != 1) throw new AssertionError("insert after current");
        if (pc.unifiedQueue().size() != 3) throw new AssertionError("mixed size");
        if (pc.unifiedQueue().index() != 1) throw new AssertionError("now playing podcast");
        if (!pc.isPodcastActive()) throw new AssertionError("podcast active");
    }

    @Test
    public void streamQueueHelper_libraryVsTemp() throws Exception {
        java.io.File cache = java.io.File.createTempFile("cache", "");
        cache.delete();
        cache.mkdirs();
        java.io.File music = java.io.File.createTempFile("music", "");
        music.delete();
        music.mkdirs();
        java.io.File reachPartial = new java.io.File(cache, "reach/foo.tmp");
        reachPartial.getParentFile().mkdirs();
        reachPartial.createNewFile();
        java.io.File lib = new java.io.File(music, "track.mp3");
        lib.createNewFile();
        if (!StreamQueueHelper.isStreamTempFile(cache, reachPartial)) throw new AssertionError("temp");
        if (!StreamQueueHelper.isLibraryMusicFile(music, cache, lib)) throw new AssertionError("library");
        if (StreamQueueHelper.isLibraryMusicFile(music, cache, reachPartial)) throw new AssertionError("not library");
    }
}
