package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class PlaylistManagerTest {
    @Test
    public void parseM3u_resolvesRelativePaths() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "solar_pl_test");
        dir.mkdirs();
        File track = new File(dir, "a.mp3");
        if (!track.exists()) track.createNewFile();
        File m3u = new File(dir, "test.m3u");
        java.io.FileWriter fw = new java.io.FileWriter(m3u);
        fw.write("#EXTM3U\n#EXTINF:-1,Track A\na.mp3\n");
        fw.close();
        PlaylistManager.Entry e = PlaylistManager.parse(m3u, dir);
        if (e.tracks.size() != 1) throw new AssertionError("track count");
        track.delete();
        m3u.delete();
        dir.delete();
    }
}
