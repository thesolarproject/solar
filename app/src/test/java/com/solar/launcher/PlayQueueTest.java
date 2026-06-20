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
    public void swap_withNowPlayingIndexFollowsTrack() {
        PlayQueue q = new PlayQueue();
        java.util.List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/bad.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/baby.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/carib.mp3")));
        q.setAll(items, 1);
        q.swap(2, 1);
        if (q.index() != 2) throw new AssertionError("np index follows baby");
        if (!"/carib.mp3".equals(q.items().get(1).file.getPath())) throw new AssertionError("carib slot");
        if (!"/baby.mp3".equals(q.items().get(2).file.getPath())) throw new AssertionError("baby slot");
    }
}
