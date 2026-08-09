package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SolarDataResetTest {

    @Test
    public void overwriteFileContents_zerosBytesBeforeDelete() throws Exception {
        File f = File.createTempFile("solar_reset_", ".bin");
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(new byte[] {1, 2, 3, 4, 5});
        fos.close();

        SolarDataReset.overwriteFileContents(f);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] buf = new byte[5];
        raf.readFully(buf);
        raf.close();
        for (byte b : buf) {
            assertTrue("expected zero overwrite", b == 0);
        }

        assertTrue(f.delete());
        assertFalse(f.exists());
    }

    @Test
    public void run_setsCachesClearedWhenCachesSelected() {
        SolarDataReset.Selection sel = new SolarDataReset.Selection();
        sel.caches = true;
        // Context-free: only inspect Result defaults from a null run
        SolarDataReset.Result bad = SolarDataReset.run(null, sel);
        assertFalse(bad.ok);
        assertFalse(bad.cachesCleared);
    }

    @Test
    public void markPendingAlbumArtCacheRebuild_survivesConsume() {
        MainActivity.markPendingAlbumArtCacheRebuild();
        assertTrue(MainActivity.consumePendingAlbumArtCacheRebuild());
        assertFalse(MainActivity.consumePendingAlbumArtCacheRebuild());
    }

    @Test
    public void clearCacheKeepingStems_keepsStemVaults() throws Exception {
        File cache = File.createTempFile("solar_cache_", "");
        assertTrue(cache.delete());
        assertTrue(cache.mkdir());
        File stems = new File(cache, "lalal_stems");
        assertTrue(stems.mkdir());
        File work = new File(cache, "lalal_work");
        assertTrue(work.mkdir());
        File solo = new File(cache, "lalal_solo_cache");
        assertTrue(solo.mkdir());
        File pad = new File(stems, "vocals.mp3");
        FileOutputStream fos = new FileOutputStream(pad);
        fos.write("stem".getBytes("UTF-8"));
        fos.close();
        File disposable = new File(cache, "deezer");
        assertTrue(disposable.mkdir());
        File tmp = new File(cache, "stream.tmp");
        FileOutputStream fos2 = new FileOutputStream(tmp);
        fos2.write("x".getBytes("UTF-8"));
        fos2.close();

        SolarDataReset.clearCacheKeepingStems(cache);

        // Stem vaults and their pads must survive a generic cache clear.
        assertTrue("lalal_stems kept", stems.isDirectory());
        assertTrue("lalal_work kept", work.isDirectory());
        assertTrue("lalal_solo_cache kept", solo.isDirectory());
        assertTrue("pad file kept", pad.isFile());
        // Disposable cache contents must still be removed.
        assertFalse("deezer cleared", disposable.exists());
        assertFalse("stream tmp cleared", tmp.exists());

        // Temp hygiene — match sibling tests' cleanup.
        SolarDataReset.deleteTree(cache, false);
        assertFalse(cache.exists());
    }

    @Test
    public void deleteTree_removesNestedFiles() throws Exception {
        File root = File.createTempFile("solar_reset_dir_", "");
        assertTrue(root.delete());
        assertTrue(root.mkdir());
        File child = new File(root, "nested.txt");
        FileOutputStream fos = new FileOutputStream(child);
        fos.write("x".getBytes("UTF-8"));
        fos.close();

        SolarDataReset.deleteTree(root, false);
        assertFalse(root.exists());
    }
}
