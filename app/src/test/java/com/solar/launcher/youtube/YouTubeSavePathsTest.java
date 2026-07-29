package com.solar.launcher.youtube;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class YouTubeSavePathsTest {

    @Test
    public void videoPathUnderVideosYouTube() {
        File root = new File("/storage/sdcard0");
        File dest = YouTubeSavePaths.destUnderRoot(root, "Videos", "Cool Clip", "mp4");
        assertEquals("/storage/sdcard0/Videos/YouTube/Cool Clip.mp4", portable(dest));
    }

    @Test
    public void audioPathUnderMusicYouTube() {
        File root = new File("/storage/sdcard1");
        File dest = YouTubeSavePaths.destUnderRoot(root, "Music", "Song Title", "m4a");
        assertEquals("/storage/sdcard1/Music/YouTube/Song Title.m4a", portable(dest));
    }

    @Test
    public void safeNameStripsIllegalChars() {
        String safe = YouTubeSavePaths.safeName("a/b:c*d?\"<>|");
        assertTrue(!safe.contains("/"));
        assertTrue(!safe.contains(":"));
    }

    /** 2026-07-15 — Basename match used by multi-root findSaved*. */
    @Test
    public void matchInDir_findsPrefixedFile() throws IOException {
        File dir = File.createTempFile("yt-dir", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        File hit = new File(dir, "Cool Clip.mp4");
        FileOutputStream out = new FileOutputStream(hit);
        byte[] pad = new byte[2048];
        out.write(pad);
        out.close();
        File found = YouTubeSavePaths.matchInDirForTest(dir, "Cool Clip");
        assertNotNull(found);
        assertEquals(hit.getAbsolutePath(), found.getAbsolutePath());
    }

    private static String portable(File file) {
        String path = file.getPath().replace('\\', '/');
        return path.startsWith("/") ? path : "/" + path;
    }
}
