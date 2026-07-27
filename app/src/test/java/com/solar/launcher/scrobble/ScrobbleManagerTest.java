package com.solar.launcher.scrobble;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import com.solar.launcher.PlayQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScrobbleManagerTest {

    @Before
    public void setUp() throws Exception {
        // Reset internal state
        setField("isExcluded", false);
        setField("hasScrobbledCurrent", false);
        setField("currentTitle", "");
        setField("currentArtist", "");
        setField("currentAlbum", "");
        setField("currentDurationMs", 0L);
        setField("totalListenedMs", 0L);
        setField("isCurrentlyPlaying", false);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ScrobbleManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private Object getField(String name) throws Exception {
        Field f = ScrobbleManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    public void filtersPodcasts() throws Exception {
        ScrobbleManager.processStateChange("Podcast Title", "Author", "Album", 60000, 0, true, true, true, null, false);
        assertTrue((Boolean) getField("isExcluded"));
        assertFalse((Boolean) getField("isCurrentlyPlaying"));
    }

    @Test
    public void filtersVideos() throws Exception {
        ScrobbleManager.processStateChange("Video Title", "Creator", "Album", 60000, 0, true, true, false, null, true);
        assertTrue((Boolean) getField("isExcluded"));
        assertFalse((Boolean) getField("isCurrentlyPlaying"));
    }

    @Test
    public void filtersShortTracks() throws Exception {
        ScrobbleManager.processStateChange("Short Title", "Artist", "Album", 20000, 0, true, true, false, null, false);
        assertTrue((Boolean) getField("isExcluded"));
        assertFalse((Boolean) getField("isCurrentlyPlaying"));
    }

    @Test
    public void handlesYouTubeMapping() throws Exception {
        ScrobbleManager.processStateChange("Real Artist - Great Song", "YouTube", "Album", 60000, 0, false, true, false, null, false);

        assertFalse((Boolean) getField("isExcluded"));
        assertFalse((Boolean) getField("isCurrentlyPlaying")); // playing is false to avoid SystemClock
        assertEquals("Real Artist", getField("currentArtist"));
        assertEquals("Great Song", getField("currentTitle"));
    }

    @Test
    public void handlesUnknownYouTubeWithoutDash() throws Exception {
        ScrobbleManager.processStateChange("Generic YouTube Video Title", "YouTube", "Album", 60000, 0, false, true, false, null, false);
        assertTrue((Boolean) getField("isExcluded"));
        assertFalse((Boolean) getField("isCurrentlyPlaying"));
    }
}
