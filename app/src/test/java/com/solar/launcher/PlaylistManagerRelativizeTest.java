package com.solar.launcher;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaylistManagerRelativizeTest {

    @Test
    public void relativizeFromPlaylistsSubfolder() throws Exception {
        File root = new File("/music");
        File playlists = new File(root, "Playlists");
        File track = new File(root, "Artist/song.mp3");
        String rel = PlaylistManager.relativize(playlists, track);
        assertTrue(rel.contains("song.mp3"));
        assertFalse(rel.startsWith("/"));
    }
}
