package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PlaybackCoordinatorTest {

    private static List<File> files(String... names) {
        List<File> out = new ArrayList<File>();
        for (String n : names) out.add(new File("/music/" + n));
        return out;
    }

    @Test
    public void moveMusicTrack_updatesIndexAndSyncsOriginalOrder() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<File> playlist = files("a", "b", "c", "d");
        pc.activateMusic(playlist, 2, false);
        assertEquals(2, pc.musicIndex());

        pc.moveMusicTrack(2, 0);
        assertEquals(0, pc.musicIndex());
        assertSame(playlist.get(2), pc.musicPlaylist().get(0));
        assertEquals(pc.musicPlaylist(), pc.musicOriginal());

        pc.moveMusicTrack(0, 3);
        assertEquals(3, pc.musicIndex());
        assertEquals(pc.musicPlaylist(), pc.musicOriginal());
    }
}
