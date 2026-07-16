package com.solar.launcher.soulseek;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

public class StreamTempCacheTest {
    @Test
    public void purgePodcastStream_dropsOrphanPart() throws Exception {
        File root = File.createTempFile("streamcache", "");
        root.delete();
        root.mkdirs();
        try {
            File dir = new File(root, StreamTempCache.PODCAST_DIR);
            dir.mkdirs();
            File keep = new File(dir, "pod_abc.part");
            keep.createNewFile();
            File orphan = new File(dir, "pod_old.part");
            writeByte(orphan);
            StreamTempCache.purgePodcastStream(root, keep, null);
            if (!keep.isFile()) throw new AssertionError("keep deleted");
            if (orphan.isFile()) throw new AssertionError("orphan not deleted");
        } finally {
            deleteTree(root);
        }
    }

    private static void writeByte(File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(1);
        out.close();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }
}
