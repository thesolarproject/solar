package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Pad-owned gains, solo-after-fade, weighted 2:2 shuffle, dual-start 1%, silent dim.
 * 2026-07-21
 */
public class StemPadGainAndShuffleTest {

    /** Mid-jam shuffle keeps both songs — escapes stuck 4:0. 2026-07-21 */
    @Test
    public void midJamShuffleEscapesFourOhStuck() {
        // All pads on song A — old policy returned false forever. 2026-07-21
        int[] zones = new int[] { 0, 0, 0, 0 };
        assertTrue(StemControls.pickShufflePadSongs(zones, 2, new Random(7)));
        assertTrue(StemControls.songHasPad(zones, 0));
        assertTrue(StemControls.songHasPad(zones, 1));
        // All pads on song B also escapes. 2026-07-21
        int[] allB = new int[] { 1, 1, 1, 1 };
        assertTrue(StemControls.pickShufflePadSongs(allB, 2, new Random(9)));
        assertTrue(StemControls.songHasPad(allB, 0));
        assertTrue(StemControls.songHasPad(allB, 1));
        // Repeated shuffles never stick on 4:0. 2026-07-21
        int[] cur = new int[] { 0, 1, 0, 1 };
        for (int seed = 0; seed < 80; seed++) {
            assertTrue(StemControls.pickShufflePadSongs(cur, 2, new Random(seed)));
            assertTrue(StemControls.padCountForSong(cur, 0) >= 1);
            assertTrue(StemControls.padCountForSong(cur, 1) >= 1);
        }
    }

    /** Song B with zero pads is invented by mid-jam shuffle (both-songs policy). 2026-07-21 */
    @Test
    public void shuffleSkipsTrackWithZeroPads() {
        // Was: assertFalse — 4:0 stuck. Now invents B pads so mashup stays usable. 2026-07-21
        int[] zones = new int[] { 0, 0, 0, 0 };
        assertTrue(StemControls.pickShufflePadSongs(zones, 2, new Random(1)));
        assertTrue(StemControls.songHasPad(zones, 1));
    }

    /** Mid-jam + cold-start pools both keep both tracks (14 masks). 2026-07-21 */
    @Test
    public void shuffleMaskPoolAllowsUnevenAndFourOh() {
        // Legacy false pool still lists 16; live pickShuffle always forceBoth. 2026-07-21
        assertEquals(16, StemControls.shufflePadMaskPoolSize(false));
        assertEquals(14, StemControls.shufflePadMaskPoolSize(true));
        assertTrue(StemControls.shufflePadMaskEligible(0b0111, false));
        assertTrue(StemControls.shufflePadMaskEligible(0b0000, false));
        assertTrue(StemControls.shufflePadMaskEligible(0b1111, false));
        assertFalse(StemControls.shufflePadMaskEligible(0b0000, true));
        assertFalse(StemControls.shufflePadMaskEligible(0b1111, true));
        assertTrue(StemControls.shufflePadMaskEligible(0b0001, true));
    }

    /** 2:2 masks weigh more than skew — most common without being exclusive. 2026-07-21 */
    @Test
    public void shuffleWeightsPreferTwoTwo() {
        assertEquals(StemControls.SHUFFLE_PAIR_WEIGHT, StemControls.shufflePadMaskWeight(0b0011));
        assertEquals(StemControls.SHUFFLE_PAIR_WEIGHT, StemControls.shufflePadMaskWeight(0b0101));
        assertEquals(StemControls.SHUFFLE_SKEW_WEIGHT, StemControls.shufflePadMaskWeight(0b0111));
        assertEquals(StemControls.SHUFFLE_SKEW_WEIGHT, StemControls.shufflePadMaskWeight(0b0000));
        // 6 pair masks × 3 + 10 skew × 1 = 28 mid-jam. 2026-07-21
        assertEquals(28, StemControls.shufflePadMaskWeightTotal(false));
        // Cold-start drops 4:0/0:4 → 26. 2026-07-21
        assertEquals(26, StemControls.shufflePadMaskWeightTotal(true));
        float pairShare = (6f * StemControls.SHUFFLE_PAIR_WEIGHT)
                / StemControls.shufflePadMaskWeightTotal(false);
        assertTrue(pairShare > 0.5f);
    }

    /** Initial mashup shuffle invents B pads; any uneven split OK (not forced 2:2). 2026-07-21 */
    @Test
    public void initialShuffleForcesBothTracksAnySplit() {
        int[] zones = new int[] { 0, 0, 0, 0 };
        assertTrue(StemControls.pickInitialMashupPadSongs(zones, 2, new Random(42)));
        int c0 = StemControls.padCountForSong(zones, 0);
        int c1 = StemControls.padCountForSong(zones, 1);
        assertTrue(c0 >= 1 && c1 >= 1);
        assertEquals(4, c0 + c1);
    }

    /** Over many seeds, mid-jam shuffle can land uneven (proves not 2:2-only). 2026-07-21 */
    @Test
    public void midJamShuffleCanLandUnevenSplit() {
        boolean sawUneven = false;
        boolean sawThreeOne = false;
        int twoTwo = 0;
        int total = 0;
        for (int seed = 0; seed < 200; seed++) {
            int[] zones = new int[] { 0, 0, 1, 1 };
            if (!StemControls.pickShufflePadSongs(zones, 2, new Random(seed))) continue;
            total++;
            int c0 = StemControls.padCountForSong(zones, 0);
            int c1 = StemControls.padCountForSong(zones, 1);
            if (c0 == 2 && c1 == 2) twoTwo++;
            if (c0 != c1) sawUneven = true;
            if ((c0 == 3 && c1 == 1) || (c0 == 1 && c1 == 3)) sawThreeOne = true;
        }
        assertTrue(sawUneven);
        assertTrue(sawThreeOne);
        // Weighted bias: 2:2 should be plurality / majority over this sweep. 2026-07-21
        assertTrue(total > 0);
        assertTrue(twoTwo * 2 >= total);
        assertTrue(StemControls.shufflePadMaskEligible(0, false));
    }

    /** Silent pads dim on the Stem face (visual policy only). 2026-07-21 */
    @Test
    public void silentPadFaceDimPolicy() {
        assertTrue(StemControls.padFaceShouldDim(0f));
        assertTrue(StemControls.padFaceShouldDim(StemControls.SILENT_GAIN));
        assertFalse(StemControls.padFaceShouldDim(0.1f));
        assertEquals(0.45f, StemControls.padSilentVisualMul(0f), 0.001f);
        assertEquals(1f, StemControls.padSilentVisualMul(0.5f), 0.001f);
        assertTrue(StemControls.padSilentDimOverlayAlpha(0f) > 0);
        assertEquals(0, StemControls.padSilentDimOverlayAlpha(0.2f));
    }

    /** After fade settle, only one song has non-zero gain on a zone. 2026-07-21 */
    @Test
    public void padZoneSoloFinalMutesOutgoing() {
        float[] g = StemControls.padZoneSoloFinalGains(0.55f);
        assertEquals(0f, g[0], 0.0001f);
        assertEquals(0.55f, g[1], 0.0001f);
        assertFalse(StemControls.violatesPadZoneSolo(g[0], g[1]));
        assertTrue(StemControls.violatesPadZoneSolo(0.2f, 0.3f));
    }

    /** Pad gain unchanged across song swap / shuffle. 2026-07-21 */
    @Test
    public void padGainPreservedAcrossShuffle() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.setPadGain(0, 0.4f);
        s.setPadGain(1, 0.5f);
        s.setPadGain(2, 0.6f);
        s.setPadGain(3, 0.7f);
        s.onStemKey(0);
        s.onStemKey(0); // flip vocals → song B so both in mix
        float[] before = new float[4];
        s.copyPadGains(before);
        s.shufflePadAssignments(new Random(7));
        for (int z = 0; z < 4; z++) {
            assertEquals(before[z], s.padGain(z), 0.0001f);
        }
    }

    /** Silent / 1% pad bumps to ~10% on track-switch; louder pads keep level. 2026-07-21 */
    @Test
    public void silentPadBumpsToTenPercentOnTrackSwitch() {
        assertEquals(StemControls.PAD_SWITCH_AUDIBLE_GAIN,
                StemControls.padGainAfterTrackSwitch(0f), 0.0001f);
        assertEquals(StemControls.PAD_SWITCH_AUDIBLE_GAIN,
                StemControls.padGainAfterTrackSwitch(StemControls.PAD_SWITCH_FLOOR), 0.0001f);
        // Cold-start 50% is not the silent floor — stay put. 2026-07-21
        assertEquals(StemControls.MASHUP_START_PAD_GAIN,
                StemControls.padGainAfterTrackSwitch(StemControls.MASHUP_START_PAD_GAIN), 0.0001f);
        assertEquals(0.4f, StemControls.padGainAfterTrackSwitch(0.4f), 0.0001f);
        assertEquals(0.10f, StemControls.padGainAfterTrackSwitch(0.10f), 0.0001f);
        assertTrue(StemControls.padGainAtOrBelowStartFloor(0f));
        assertTrue(StemControls.padGainAtOrBelowStartFloor(0.01f));
        assertFalse(StemControls.padGainAtOrBelowStartFloor(0.02f));
    }

    /** Two-track start seeds 50% on every pad. 2026-07-21 */
    @Test
    public void dualStartSeedsFiftyPercent() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.seedMashupStartPadGains();
        for (int z = 0; z < StemSession.ZONE_COUNT; z++) {
            assertEquals(StemControls.MASHUP_START_PAD_GAIN, s.padGain(z), 0.0001f);
        }
        s.initialMashupShuffle(new Random(3));
        // Levels stay at 50% after initial shuffle. 2026-07-21
        for (int z = 0; z < StemSession.ZONE_COUNT; z++) {
            assertEquals(StemControls.MASHUP_START_PAD_GAIN, s.padGain(z), 0.0001f);
        }
        assertTrue(StemControls.songHasPad(copyZones(s), 0));
        assertTrue(StemControls.songHasPad(copyZones(s), 1));
    }

    /** Connectivity chips hidden while jam active. 2026-07-21 */
    @Test
    public void jamQuickBarHidesWifiBt() {
        assertFalse(StemControls.jamQuickBarShowsConnectivity(true));
        assertTrue(StemControls.jamQuickBarShowsConnectivity(false));
    }

    /** Session context has Pause + Home; no loop/chop rows. 2026-07-21 */
    @Test
    public void contextRowsHavePauseHomeNoLoop() {
        String[] slot = StemMixContextRows.slotRows(1);
        assertEquals(StemMixContextRows.SLOT_ROW_COUNT, slot.length);
        assertTrue(slot[0].toLowerCase().contains("replace"));
        assertTrue(slot[0].toLowerCase().contains("focused"));
        String[] session = StemMixContextRows.sessionRows(false);
        assertEquals(StemMixContextRows.SESSION_ROW_COUNT, session.length);
        assertTrue(StemMixContextRows.isSessionPauseRow(StemMixContextRows.SESSION_PAUSE));
        assertTrue(StemMixContextRows.isSessionHomeRow(StemMixContextRows.SESSION_HOME));
        boolean sawPause = false;
        boolean sawHome = false;
        for (int i = 0; i < session.length; i++) {
            String low = session[i].toLowerCase();
            assertFalse(low.contains("loop"));
            assertFalse(low.contains("chop"));
            assertFalse(low.contains("roll"));
            if (low.contains("pause")) sawPause = true;
            if (low.equals("home")) sawHome = true;
        }
        assertTrue(sawPause);
        assertTrue(sawHome);
        assertEquals(-1, StemMixContextRows.transitionPresetForSessionRow(
                StemMixContextRows.SESSION_PLAY_QUEUE));
    }

    /** Pad repress uses WAVE (~0.4s), not LONG. 2026-07-21 */
    @Test
    public void padRepressIsWaveDuration() {
        assertEquals(StemControls.TRANSITION_WAVE_MS, StemControls.padRepressTransitionMs());
    }

    private static int[] copyZones(StemSession s) {
        int[] z = new int[4];
        s.copyZoneSongs(z);
        return z;
    }

    private static List<File> fakeTracks(int n) {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < n; i++) {
            try {
                File f = File.createTempFile("stem-pad-" + i + "-", ".mp3");
                f.deleteOnExit();
                out.add(f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return out;
    }
}
