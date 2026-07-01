package com.solar.launcher.flow;

import com.solar.launcher.PlayQueue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NpToFlowMorphPolicyTest {

    @Test
    public void libraryTrack_withMatchKey_isEligible() throws IOException {
        File track = File.createTempFile("np-flow", ".mp3");
        track.deleteOnExit();
        assertTrue(NpToFlowMorphPolicy.isCarouselMorphEligible(
                true, false, track, PlayQueue.QueueItem.music(track),
                true, false, false, "alb|a"));
    }

    @Test
    public void reachStream_withoutLibraryRow_crossfadesOnly() throws IOException {
        File temp = File.createTempFile("reach", ".mp3");
        temp.deleteOnExit();
        PlayQueue.QueueItem q = PlayQueue.QueueItem.reach(temp, "meta");
        assertFalse(NpToFlowMorphPolicy.isCarouselMorphEligible(
                true, false, temp, q, false, true, false, "alb|a"));
    }

    @Test
    public void savedReach_inLibrary_canMorph() throws IOException {
        File saved = File.createTempFile("saved", ".mp3");
        saved.deleteOnExit();
        PlayQueue.QueueItem q = PlayQueue.QueueItem.reach(saved, "meta");
        assertTrue(NpToFlowMorphPolicy.isCarouselMorphEligible(
                true, false, saved, q, true, false, false, "alb|a"));
    }

    @Test
    public void podcast_neverMorphs() throws IOException {
        File ep = File.createTempFile("episode", ".mp3");
        ep.deleteOnExit();
        assertFalse(NpToFlowMorphPolicy.isCarouselMorphEligible(
                true, true, ep, null, false, false, true, "show|ep"));
    }
}
