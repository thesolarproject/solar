package com.solar.launcher;

import org.junit.Test;

import java.io.File;

public class PlayQueueStoreTest {

    @Test
    public void roundTrip_reachStream_keepsItemWhenFileAbsent() throws Exception {
        File dir = File.createTempFile("playqueue", "");
        if (!dir.delete() || !dir.mkdir()) throw new AssertionError("tmpdir");
        PlayQueue q = new PlayQueue();
        File missing = new File(dir, "reach-missing.mp3");
        q.append(PlayQueue.QueueItem.reach(missing, "Stream Title", "peer1"));
        q.setIndex(0);
        PlayQueueStore.saveToDir(dir, q);

        PlayQueue restored = new PlayQueue();
        if (!PlayQueueStore.restoreFromDir(dir, restored)) {
            throw new AssertionError("restore failed");
        }
        if (restored.size() != 1) {
            throw new AssertionError("size=" + restored.size());
        }
        PlayQueue.QueueItem item = restored.current();
        if (item == null || item.kind != PlayQueue.ItemKind.REACH_STREAM) {
            throw new AssertionError("kind");
        }
        if (!"Stream Title".equals(item.reachMeta)) {
            throw new AssertionError("meta");
        }
        if (!missing.getAbsolutePath().equals(item.file.getAbsolutePath())) {
            throw new AssertionError("path");
        }
        if (item.file.isFile()) {
            throw new AssertionError("file should not exist yet");
        }
    }
}
