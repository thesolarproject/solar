package com.solar.launcher.flow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlowCarouselFocusTest {

    private static List<FlowItem> threeAlbums() {
        return Arrays.asList(
                FlowItem.album("Happier Than Ever", "", "happier|billie", Collections.<java.io.File>emptyList(), ""),
                FlowItem.album("Back To Jungle Classics", "", "jungle|x", Collections.<java.io.File>emptyList(), ""),
                FlowItem.album("Wamp 2 Dem", "", "wamp|x", Collections.<java.io.File>emptyList(), ""));
    }

    @Test
    public void homeEntryUsesRackHeadNotSavedSession() {
        FlowEngine engine = new FlowEngine();
        int idx = FlowCarouselFocus.resolveIndex(threeAlbums(), FlowMode.ALBUM, null,
                false, null, "wamp|x", 2, engine);
        assertEquals(0, idx);
    }

    @Test
    public void librarySectionUsesExplicitFocusKey() {
        FlowEngine engine = new FlowEngine();
        int idx = FlowCarouselFocus.resolveIndex(threeAlbums(), FlowMode.ALBUM, "jungle|x",
                true, null, "happier|billie", 0, engine);
        assertEquals(1, idx);
    }

    @Test
    public void sectionResumeUsesSavedKeyWhenNoExplicitKey() {
        FlowEngine engine = new FlowEngine();
        int idx = FlowCarouselFocus.resolveIndex(threeAlbums(), FlowMode.ALBUM, null,
                true, null, "wamp|x", 0, engine);
        assertEquals(2, idx);
    }
}
