package com.solar.launcher.soulseek;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ReachCacheTest {
    @Test
    public void isTempFile_underReachDir() throws Exception {
        File root = File.createTempFile("reachcache", "");
        root.delete();
        root.mkdirs();
        try {
            File cached = new File(ReachCache.dir(root), "song.mp3");
            cached.createNewFile();
            File other = new File(root, "song.mp3");
            other.createNewFile();
            if (!ReachCache.isTempFile(root, cached)) {
                throw new AssertionError("expected temp under reach/");
            }
            if (ReachCache.isTempFile(root, other)) {
                throw new AssertionError("file outside reach/ is not temp");
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void purgeUnreferenced_deletesOrphansKeepsQueue() throws Exception {
        File root = File.createTempFile("reachcache", "");
        root.delete();
        root.mkdirs();
        try {
            File keep = new File(ReachCache.dir(root), "keep.mp3");
            keep.createNewFile();
            File orphan = new File(ReachCache.dir(root), "orphan.mp3");
            writeBytes(orphan, new byte[] {1});
            List<File> queue = new ArrayList<File>(Arrays.asList(keep));
            ReachCache.purgeUnreferenced(root, queue);
            if (!keep.isFile()) throw new AssertionError("keep deleted");
            if (orphan.isFile()) throw new AssertionError("orphan not deleted");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void purgeUnreferenced_keepsResumeMetadataBesidePartial() throws Exception {
        File root = File.createTempFile("reachcache", "");
        root.delete();
        root.mkdirs();
        try {
            File partial = new File(ReachCache.dir(root), "keep.mp3.part");
            File metadata = new File(ReachCache.dir(root), "keep.mp3.part.meta");
            writeBytes(partial, new byte[] {1});
            writeBytes(metadata, new byte[] {2});
            File orphanMetadata = new File(ReachCache.dir(root), "old.mp3.part.meta");
            writeBytes(orphanMetadata, new byte[] {3});

            ReachCache.purgeUnreferenced(root, Arrays.asList(partial));

            if (!partial.isFile()) throw new AssertionError("partial deleted");
            if (!metadata.isFile()) throw new AssertionError("resume metadata deleted");
            if (orphanMetadata.isFile()) throw new AssertionError("orphan metadata kept");
        } finally {
            deleteTree(root);
        }
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
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
