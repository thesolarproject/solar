package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Stem mashup session — max 2 + per-zone repress crossfade routing. 2026-07-19 / 2026-07-20
 */
public class StemSessionTest {

    /** Cap rejects a third track. Was: bind 3. Reversal: MAX_SONGS=3 + assertEquals(3). 2026-07-20 */
    @Test
    public void maxSongsIsTwo() {
        assertEquals(2, StemSession.MAX_SONGS);
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(3));
        assertEquals(2, s.songCount());
        assertTrue(s.isMulti());
    }

    /** First press focuses; second on same arm toggles that zone’s song only. 2026-07-19 / 2026-07-20 */
    @Test
    public void focusThenCrossfadeOtherSongOnZone() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertEquals(2, s.songCount());
        assertFalse(s.onStemKey(0)); // focus vocals
        assertEquals(0, s.activeZone());
        assertEquals(1, s.displaySongNumber(0));
        assertTrue(s.onStemKey(0)); // crossfade → song 2 on this pad only
        assertEquals(2, s.displaySongNumber(0));
        assertEquals(1, s.controlSongIndex());
        // Other pads stay on song 1 until they repress. Was: all pads seeded. 2026-07-20
        assertEquals(1, s.displaySongNumber(1));
        assertEquals(1, s.displaySongNumber(2));
        assertEquals(1, s.displaySongNumber(3));
        assertTrue(s.onStemKey(0)); // wrap back to song 1
        assertEquals(1, s.displaySongNumber(0));
    }

    /** Repress toggles only the pressed pad; focus of another pad does not flip. 2026-07-20 */
    @Test
    public void perZoneRoutingIndependent() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.onStemKey(0); // focus vocals
        s.onStemKey(0); // vocals → song 2
        assertEquals(1, s.songIndexForZone(0));
        assertEquals(0, s.songIndexForZone(1));
        assertFalse(s.onStemKey(1)); // focus drums — still song 1
        assertEquals(0, s.songIndexForZone(1));
        assertEquals(1, s.songIndexForZone(0)); // vocals still on song 2
        assertTrue(s.onStemKey(1)); // drums → song 2
        assertEquals(1, s.songIndexForZone(1));
    }

    /** Switching arms does not cycle; interacted song follows focused pad. 2026-07-19 */
    @Test
    public void otherArmFocusDoesNotCycle() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertEquals(1, s.displaySongNumber(1));
        s.onStemKey(1); // focus drums
        assertTrue(s.onStemKey(1)); // drums → song 2
        assertEquals(2, s.displaySongNumber(1));
        assertFalse(s.onStemKey(2)); // focus bass — no cycle
        assertEquals(2, s.activeZone());
        assertEquals(0, s.controlSongIndex()); // bass still song 1
        assertEquals(1, s.displaySongNumber(2));
    }

    /**
     * Focus A → focus B → press A again = re-focus only (no cycle).
     * 2026-07-19
     */
    @Test
    public void focusOtherThenBackDoesNotCycle() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertFalse(s.onStemKey(0));
        assertEquals(1, s.displaySongNumber(0));
        assertFalse(s.onStemKey(1));
        assertFalse(s.onStemKey(0)); // back to A — focus only
        assertEquals(0, s.activeZone());
        assertEquals(1, s.displaySongNumber(0));
        assertTrue(s.onStemKey(0));
        assertEquals(2, s.displaySongNumber(0));
    }

    /** Multi bind keeps all pads on song 0. 2026-07-19 */
    @Test
    public void multiBindSeedsAllPadsOnControlSong() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertEquals(0, s.controlSongIndex());
        assertEquals(0, s.songIndexForZone(0));
        assertEquals(0, s.songIndexForZone(1));
        assertEquals(0, s.songIndexForZone(2));
        assertEquals(0, s.songIndexForZone(3));
    }

    /** Cycle one pad does not rewrite other pads’ gains. 2026-07-19 / 2026-07-20 */
    @Test
    public void multiPadsIndependentSongIndicesActive() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.song(0).gains[0] = 0.6f;
        s.song(1).gains[1] = 0.4f;
        assertFalse(s.onStemKey(0));
        assertTrue(s.onStemKey(0)); // vocals → song 2
        assertEquals(1, s.songIndexForZone(0));
        assertEquals(0.6f, s.song(0).gains[0], 0.001f);
        assertEquals(0.4f, s.song(1).gains[1], 0.001f);
        assertEquals(0f, s.song(1).gains[0], 0.001f);
    }

    /** Focused pad’s track display name updates when crossfading. 2026-07-19 */
    @Test
    public void trackDisplayNameFollowsPadSong() {
        StemSession s = new StemSession();
        List<File> tracks = fakeTracks(2);
        s.bindTracks(tracks);
        assertFalse(s.onStemKey(0));
        String n1 = s.trackDisplayNameForZone(0);
        assertTrue(n1.length() > 0);
        assertTrue(s.onStemKey(0));
        String n2 = s.trackDisplayNameForZone(0);
        assertTrue(n2.length() > 0);
        assertFalse(n1.equals(n2));
    }

    /** Cold bind — all gains 0, no loop. 2026-07-19 */
    @Test
    public void coldStartMuteNoLoop() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(1));
        StemSession.SongState st = s.song(0);
        assertEquals(0f, st.gains[0], 0.001f);
        assertEquals(0f, st.gains[2], 0.001f);
        assertFalse(st.looping);
        assertFalse(st.zoneLoopCtrl[0]);
    }

    /**
     * Raise Song1 Vocals, crossfade Vocals → Song2: Song2 stays mute / no loop;
     * Song1 keeps its own gain (host fades). 2026-07-19 / 2026-07-20
     */
    @Test
    public void cycleDoesNotInheritGainsOrLoop() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertFalse(s.onStemKey(0));
        StemSession.SongState song1 = s.song(0);
        song1.gains[0] = 0.75f;
        song1.zoneLoopCtrl[0] = true;
        song1.looping = true;
        assertTrue(s.onStemKey(0));
        StemSession.SongState song2 = s.song(1);
        assertEquals(2, s.displaySongNumber(0));
        assertEquals(0f, song2.gains[0], 0.001f);
        assertFalse(song2.zoneLoopCtrl[0]);
        assertFalse(song2.looping);
        assertEquals(0.75f, song1.gains[0], 0.001f);
        assertTrue(song1.zoneLoopCtrl[0]);
        assertTrue(song1.looping);
        assertTrue(s.onStemKey(0)); // back to song 1
        assertEquals(1, s.displaySongNumber(0));
        assertEquals(0.75f, s.song(0).gains[0], 0.001f);
    }

    @Test
    public void stemKeyShouldCycleHelper() {
        assertFalse(StemControls.stemKeyShouldCycleSong(0, 1, 2));
        assertTrue(StemControls.stemKeyShouldCycleSong(1, 1, 2));
        assertFalse(StemControls.stemKeyShouldCycleSong(1, 1, 1));
        assertFalse(StemControls.stemKeyShouldCycleSong(-1, 0, 2));
        assertFalse(StemControls.faceShowsLoopBars(false, false));
        assertTrue(StemControls.faceShowsLoopBars(true, false));
        assertFalse(StemControls.faceShowsLoopBars(true, true));
    }

    /** Single track — repress never cycles. 2026-07-19 */
    @Test
    public void singleTrackFocusOnlyNoCycle() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(1));
        assertFalse(s.isMulti());
        assertFalse(s.onStemKey(0));
        assertFalse(s.onStemKey(0));
        assertEquals(0, s.activeZone());
        assertEquals(1, s.displaySongNumber(0));
    }

    /** otherSongIndex flips 0↔1. 2026-07-20 */
    @Test
    public void otherSongIndexRouting() {
        assertEquals(1, StemControls.otherSongIndex(0, 2));
        assertEquals(0, StemControls.otherSongIndex(1, 2));
        assertEquals(0, StemControls.otherSongIndex(0, 1));
    }

    /** ID3 title preferred over filename for display + letter. 2026-07-20 */
    @Test
    public void id3TitleBeatsFilename() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.song(0).id3Title = "Lost & Found";
        s.song(0).id3Artist = "Lianne La Havas";
        assertEquals("Lost & Found", s.trackDisplayNameForSong(0));
        assertEquals('L', StemControls.placeholderLetter(s.trackDisplayNameForSong(0)));
    }

    /** Centre shuffle randomises pad→song (any split) and advances focus. 2026-07-20 / 2026-07-21 */
    @Test
    public void shuffleRotatesPadSongsAndFocus() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.onStemKey(0); // focus vocals
        s.onStemKey(0); // flip vocals → song 1
        assertEquals(1, s.songIndexForZone(0));
        assertEquals(0, s.songIndexForZone(1));
        float g0 = 0.55f;
        s.setPadGain(0, g0);
        int prev = s.shufflePadAssignments(new java.util.Random(11));
        assertEquals(0, prev);
        assertEquals(1, s.activeZone()); // next pad clockwise
        // Pad gains stay put across shuffle. 2026-07-21
        assertEquals(g0, s.padGain(0), 0.0001f);
        int c0 = StemControls.padCountForSong(copyZones(s), 0);
        int c1 = StemControls.padCountForSong(copyZones(s), 1);
        // Both live songs keep ≥1 pad (no stuck 4:0). 2026-07-21
        assertTrue(c0 >= 1 && c1 >= 1);
        assertEquals(4, c0 + c1);
    }

    private static int[] copyZones(StemSession s) {
        int[] z = new int[4];
        s.copyZoneSongs(z);
        return z;
    }

    /** Same album key detects shared cover case. 2026-07-20 */
    @Test
    public void songsShareAlbumWhenTagsMatch() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.song(0).id3Album = "Donda";
        s.song(0).id3Artist = "Kanye";
        s.song(1).id3Album = "Donda";
        s.song(1).id3Artist = "Kanye";
        assertTrue(s.songsShareAlbum());
        s.song(1).id3Album = "Other";
        assertFalse(s.songsShareAlbum());
    }

    /** Play-both: toggling stacks the stem from both songs; tap only focuses. 2026-08-01 */
    @Test
    public void playBothTogglesAndFocusOnly() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        assertFalse(s.isZoneBoth(0));
        assertTrue(s.toggleZoneBoth(0));
        assertTrue(s.isZoneBoth(0));
        assertFalse(s.isZoneBoth(1));
        // Repress on a both-zone pad focuses only — stacked feed must not cycle. 2026-08-01
        assertFalse(s.onStemKey(0));
        assertFalse(s.onStemKey(0));
        assertEquals(0, s.songIndexForZone(0));
        // Toggling back un-stacks. 2026-08-01
        assertFalse(s.toggleZoneBoth(0));
        assertFalse(s.isZoneBoth(0));
        // Routing still works once un-stacked. 2026-08-01
        assertTrue(s.onStemKey(0));
        assertEquals(1, s.songIndexForZone(0));
    }

    /** Play-both resets on new bind and on sole-survivor handoff. 2026-08-01 */
    @Test
    public void playBothClearedOnBindAndSoleSurvivor() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.toggleZoneBoth(1);
        s.toggleZoneBoth(3);
        assertTrue(s.isZoneBoth(1));
        s.bindTracks(fakeTracks(2));
        assertFalse(s.isZoneBoth(1));
        assertFalse(s.isZoneBoth(3));
        s.toggleZoneBoth(2);
        assertTrue(s.isZoneBoth(2));
        s.routeAllPadsToSong(0);
        assertFalse(s.isZoneBoth(2));
    }

    /** Track-end handoff routes every pad onto the survivor. 2026-07-20 */
    @Test
    public void routeAllPadsToSongCoversEveryZone() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        s.onStemKey(0);
        s.onStemKey(0); // vocals → song 1
        assertEquals(1, s.songIndexForZone(0));
        s.routeAllPadsToSong(0);
        for (int z = 0; z < StemSession.ZONE_COUNT; z++) {
            assertEquals(0, s.songIndexForZone(z));
        }
        assertEquals(0, s.controlSongIndex());
    }

    /** Pad-majority: the song owning more pads leads. 2026-08-02 */
    @Test
    public void dominantSongIsPadMajority() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        // Cold start: every pad on song 0 → dominant 0.
        assertEquals(0, s.dominantSongIndex());
        // Route drums/bass/melody → song 1 → 3-1 majority → dominant 1.
        for (int z = 1; z < 4; z++) {
            s.onStemKey(z); // focus
            s.onStemKey(z); // cycle → song 1
        }
        assertEquals(1, s.dominantSongIndex());
        // Back to all-song-0 → dominant 0.
        s.routeAllPadsToSong(0);
        assertEquals(0, s.dominantSongIndex());
    }

    /** 2-2 split (“2 of each”) → vocals pad (zone 0) breaks the tie. 2026-08-02 */
    @Test
    public void dominantTieBrokenByVocalsPad() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        // Explicit 2-2 routing: vocals(0) + bass(2) on song 0; drums(1) + melody(3) on song 1.
        s.setZoneSong(1, 1);
        s.setZoneSong(3, 1);
        assertEquals(0, s.songIndexForZone(0));
        assertEquals(1, s.songIndexForZone(1));
        assertEquals(0, s.songIndexForZone(2));
        assertEquals(1, s.songIndexForZone(3));
        // Vocals on song 0 → dominant 0 (tie broken by the vocal pad).
        assertEquals(0, s.dominantSongIndex());
        // Rebalance to 2-2 with vocals on song 1 → dominant flips to song 1.
        s.setZoneSong(0, 1);
        s.setZoneSong(1, 0);
        assertEquals(1, s.dominantSongIndex());
        // Back to vocals-on-song-0 → dominant 0 again.
        s.setZoneSong(0, 0);
        s.setZoneSong(1, 1);
        assertEquals(0, s.dominantSongIndex());
    }

    /** Play-both pads feed both songs → neutral; vocals still breaks a tie. 2026-08-02 */
    @Test
    public void dominantBothPadsNeutralVocalsBreaks() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(2));
        // Every pad stacked on both songs → no pad breaks the tie → vocals decides.
        for (int z = 0; z < 4; z++) s.toggleZoneBoth(z);
        assertEquals(0, s.dominantSongIndex());
        // Route vocals' primary to song 1 → dominant 1.
        s.setZoneSong(0, 1);
        assertEquals(1, s.dominantSongIndex());
        s.setZoneSong(0, 0);
        assertEquals(0, s.dominantSongIndex());
    }

    /** Single-song session always reports the only song. 2026-08-02 */
    @Test
    public void dominantSingleSongIsZero() {
        StemSession s = new StemSession();
        s.bindTracks(fakeTracks(1));
        assertEquals(1, s.songCount());
        assertEquals(0, s.dominantSongIndex());
    }

    private static List<File> fakeTracks(int n) {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < n; i++) {
            try {
                File f = File.createTempFile("stem-song-" + i + "-", ".mp3");
                f.deleteOnExit();
                out.add(f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return out;
    }
}
