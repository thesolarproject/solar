package com.solar.launcher;

import com.solar.launcher.podcast.OpenRssClient;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PlayQueueTest {

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
        if (!"/baby.mp3".equals(q.items().get(0).file.getPath())) throw new AssertionError("baby slot");
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
        if (!"/r.tmp".equals(q.items().get(1).file.getPath())) throw new AssertionError("reach slot");
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
    public void move_nowPlayingIndexFollowsTrack() {
        PlayQueue q = new PlayQueue();
        java.util.List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/b.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/c.mp3")));
        q.setAll(items, 1);
        q.move(1, 0);
        if (q.index() != 0) throw new AssertionError("np index follows moved track");
        if (!"/b.mp3".equals(q.items().get(0).file.getPath())) throw new AssertionError("b at 0");
    }
}
