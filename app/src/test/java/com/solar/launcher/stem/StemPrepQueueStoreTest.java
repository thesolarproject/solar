package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class StemPrepQueueStoreTest {
    @Test
    public void roundTrip_preservesFifoAndMissingPaths() throws Exception {
        File dir = tempDir("stem-prep-store");
        try {
            File first = new File(dir, "first.mp3");
            File missing = new File(dir, "temporarily-unmounted.mp3");
            if (!first.createNewFile()) throw new AssertionError("first");

            ArrayList<File> queued = new ArrayList<File>();
            queued.add(first);
            queued.add(first);
            queued.add(missing);
            File state = new File(dir, "queue.json");
            StemPrepQueueStore.save(state, queued);

            List<File> restored = StemPrepQueueStore.load(state);
            assertEquals(2, restored.size());
            assertEquals(first.getAbsolutePath(), restored.get(0).getAbsolutePath());
            assertEquals(missing.getAbsolutePath(), restored.get(1).getAbsolutePath());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void corruptStateFailsOpen() throws Exception {
        File dir = tempDir("stem-prep-corrupt");
        try {
            File state = new File(dir, "queue.json");
            java.io.FileWriter writer = new java.io.FileWriter(state);
            writer.write("not-json");
            writer.close();
            assertTrue(StemPrepQueueStore.load(state).isEmpty());
        } finally {
            deleteTree(dir);
        }
    }

    private static File tempDir(String prefix) throws Exception {
        File dir = File.createTempFile(prefix, "");
        if (!dir.delete() || !dir.mkdirs()) throw new AssertionError("temp dir");
        return dir;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
