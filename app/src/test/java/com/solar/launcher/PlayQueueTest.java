package com.solar.launcher;

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
    public void insertAt_bumpsIndexWhenInsertingBeforeCurrent() {
        PlayQueue q = new PlayQueue();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.music(new File("/a.mp3")));
        items.add(PlayQueue.QueueItem.music(new File("/b.mp3")));
        q.setAll(items, 1);
        q.insertAt(1, PlayQueue.QueueItem.reach(new File("/x.mp3"), "x"));
        if (q.index() != 2) throw new AssertionError("index should bump to new item");
        if (q.size() != 3) throw new AssertionError("size");
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
}
