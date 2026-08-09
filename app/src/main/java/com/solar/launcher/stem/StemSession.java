package com.solar.launcher.stem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Multi-track Stem mashup session — up to 2 songs, per-song gains/loop/chop.
 * Layman: jam two songs; first pad press focuses; same pad again crossfades that stem to the other song.
 * Technical: slots[0..1] + controlSongIndex + activeSongPerZone (per-pad routing) + SongState gains.
 * Cycle never stops audio — host keeps one StemMixer per song always running; repress = timed gain fade.
 * Was: MAX_SONGS=3 + repress advanced controlSongIndex and re-seeded every pad. Reversal: MAX_SONGS=3 + seedPadRoutingToControlSong on repress.
 * 2026-07-19 / 2026-07-20
 * 2026-07-21 — centre shuffle = random pad→song (any split; no 2:2 bias); not a rotate.
 */
public final class StemSession {
    /** Mashup cap — two tracks only (StemFM-style). Was: 3. Reversal: MAX_SONGS = 3. 2026-07-20 */
    public static final int MAX_SONGS = 2;
    public static final int ZONE_COUNT = StemMixer.STEM_COUNT;

    /** Per-song mutable jam state. */
    public static final class SongState {
        public final float[] gains = new float[ZONE_COUNT];
        public final boolean[] zoneLoopCtrl = new boolean[ZONE_COUNT];
        public boolean looping;
        public float loopBars = StemControls.DEFAULT_LOOP_BARS;
        public boolean chopOn;
        public int chopStep; // index into CHOP_FRAC
        /** Classic chop-n-screw pad rate (pitch follows); not tempo match. 2026-07-20 */
        public float screwRate = 1f;
        /** Pitch-preserving song bus rate vs Song 1 (IJK SoundTouch). 2026-07-20 */
        public float tempoRate = 1f;
        public float bpm = 120f;
        /** Real-BPM analysis confidence (0..1); 0 = duration heuristic. 2026-08-01 */
        public float bpmConfidence = 0f;
        /** First downbeat offset in ms from track start (real analysis). 2026-08-01 */
        public int firstBeatMs = 0;
        /** Detected key (Camelot); empty when unknown. 2026-08-01 */
        public String keyLabel = "";
        public String camelot = "";
        /** Detected key root pitch class (0..11); -1 when unknown. 2026-08-02 */
        public int keyRoot = -1;
        /** Detected key mode; true = major. Ignored when keyRoot < 0. 2026-08-02 */
        public boolean keyMajor = true;
        public File track;
        public File bassBody;
        public List<LalalClient.StemFile> stems;
        /** ID3 title for UI / letter placeholders (not filename). 2026-07-20 */
        public String id3Title = "";
        /** ID3 artist for corner subtitle. 2026-07-20 */
        public String id3Artist = "";
        /** ID3 album for same-cover detection. 2026-07-20 */
        public String id3Album = "";

        /** Blank jam slate — all mute, no loop, chop off. 2026-07-19 */
        public void resetJam() {
            for (int i = 0; i < ZONE_COUNT; i++) {
                gains[i] = 0f;
                zoneLoopCtrl[i] = false;
            }
            looping = false;
            loopBars = StemControls.DEFAULT_LOOP_BARS;
            chopOn = false;
            chopStep = 0;
            screwRate = 1f;
            // tempoRate/bpm set in beginMixers — leave defaults here. 2026-07-20
            tempoRate = 1f;
            bpmConfidence = 0f;
            firstBeatMs = 0;
            keyLabel = "";
            camelot = "";
            keyRoot = -1;
            keyMajor = true;
            id3Title = "";
            id3Artist = "";
            id3Album = "";
        }
    }

    private final SongState[] songs = new SongState[MAX_SONGS];
    private int songCount;
    /** Which song (0..songCount-1) each zone arm is controlling. */
    private final int[] activeSongPerZone = new int[ZONE_COUNT];
    /**
     * One gain per pad — stays put across track swap / shuffle.
     * Layman: the Vocals dial is one knob; flipping songs doesn’t move it.
     * Was: SongState.gains[zone] per track (levels jumped on swap). Reversal: use st.gains only.
     * 2026-07-21
     */
    private final float[] padGains = new float[ZONE_COUNT];
    /**
     * Play-both per pad — this zone feeds its stem from BOTH songs (stacking).
     * Layman: vocals from song 1 + song 2 together on one pad.
     * Was: one-song-per-pad invariant everywhere. Reversal: no flag at all.
     * 2026-08-01
     */
    private final boolean[] bothPerZone = new boolean[ZONE_COUNT];
    /** Song the user is interacting with — follows focused pad’s routed song. 2026-07-19 / 2026-07-20 */
    private int controlSongIndex;
    private int activeZone;

    public StemSession() {
        for (int i = 0; i < MAX_SONGS; i++) {
            songs[i] = new SongState();
            songs[i].resetJam();
        }
        resetPadGains();
    }

    /** Clear pad levels to silence. 2026-07-21 */
    public void resetPadGains() {
        for (int z = 0; z < ZONE_COUNT; z++) padGains[z] = 0f;
    }

    /**
     * Two-track cold start — every pad at ~1% before initial shuffle.
     * Layman: wake the mix quietly, then shuffle who feeds which pad.
     * 2026-07-21
     */
    /**
     * Cold-start every pad to {@link StemControls#MASHUP_START_PAD_GAIN} (50%).
     * Layman: jam opens with pads at half volume so you can hear them immediately.
     * Was: 1% seed. Reversal: seed 0.01f values.
     * 2026-07-21
     */
    public void seedMashupStartPadGains() {
        for (int z = 0; z < ZONE_COUNT; z++) {
            padGains[z] = StemControls.MASHUP_START_PAD_GAIN;
        }
    }

    /** Pad volume (0..1) — UI rings + wheel; not per-song. 2026-07-21 */
    public float padGain(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return 0f;
        return StemControls.clampGain(padGains[zone]);
    }

    /** Set pad volume without touching song routing. 2026-07-21 */
    public void setPadGain(int zone, float gain) {
        if (zone < 0 || zone >= ZONE_COUNT) return;
        padGains[zone] = StemControls.clampGain(gain);
    }

    /** Copy pad levels into out[0..3]. 2026-07-21 */
    public void copyPadGains(float[] out) {
        if (out == null) return;
        for (int z = 0; z < ZONE_COUNT && z < out.length; z++) out[z] = padGains[z];
    }

    /** True when this pad plays its stem from both songs (stacking). 2026-08-01 */
    public boolean isZoneBoth(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return false;
        return bothPerZone[zone];
    }

    /** Toggle play-both for a pad; returns the new state. 2026-08-01 */
    public boolean toggleZoneBoth(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return false;
        bothPerZone[zone] = !bothPerZone[zone];
        return bothPerZone[zone];
    }

    /** Clear every play-both flag (new session / sole survivor). 2026-08-01 */
    public void clearBothZones() {
        for (int z = 0; z < ZONE_COUNT; z++) bothPerZone[z] = false;
    }

    /**
     * Bind prepared tracks (1–2). Clears jam state for each slot.
     * Was: 1–3. Reversal: comment only — MAX_SONGS restores the cap.
     * 2026-07-19 / 2026-07-20
     */
    public void bindTracks(List<File> tracks) {
        songCount = 0;
        if (tracks != null) {
            for (int i = 0; i < tracks.size() && songCount < MAX_SONGS; i++) {
                File f = tracks.get(i);
                if (f == null || !f.isFile()) continue;
                songs[songCount].resetJam();
                songs[songCount].track = f;
                songs[songCount].stems = null;
                songs[songCount].bassBody = null;
                songCount++;
            }
        }
        if (songCount < 1) songCount = 0;
        // All pads start on song 0 — focus never jumps the interacted track. 2026-07-19
        controlSongIndex = 0;
        seedPadRoutingToControlSong();
        resetPadGains();
        clearBothZones();
        // No arm focused yet — first stem key focuses without cycling. 2026-07-19
        activeZone = -1;
    }

    /**
     * Soft-replace one song’s file mid-jam (TRANSITION reassign). Keeps other song playing.
     * Layman: swap only the left or right track; the other side keeps going.
     * Technical: reset jam slate for slot; host reloads that StemMixer and crossfades stems in.
     * Was: no mid-jam one-side swap. Reversal: drop method; reopen full session.
     * 2026-07-20
     */
    public boolean replaceSongTrack(int songIndex, File track) {
        if (songIndex < 0 || songIndex >= MAX_SONGS) return false;
        if (track == null || !track.isFile()) return false;
        if (songIndex >= songCount) {
            // Grow count only when filling the next empty slot in order. 2026-07-20
            if (songIndex != songCount) return false;
            songCount++;
        }
        songs[songIndex].resetJam();
        songs[songIndex].track = track;
        songs[songIndex].stems = null;
        songs[songIndex].bassBody = null;
        return true;
    }

    /**
     * Check if a track file is already present in any other song slot of this session.
     * Layman: prevent the same song from being loaded on both decks of the mashup.
     * 2026-07-21
     */
    public boolean isDuplicateTrack(int targetSongIndex, File track) {
        if (track == null || !track.isFile()) return false;
        for (int i = 0; i < songCount; i++) {
            if (i == targetSongIndex) continue;
            if (songs[i] != null && songs[i].track != null && songs[i].track.equals(track)) {
                return true;
            }
        }
        return false;
    }


    /**
     * DJ chain seat swap: exchange two song slots in place (refs only) and
     * remap per-pad routing + control song so pads keep feeding the same
     * physical audio.
     * Layman: when the seed finishes, the survivor keeps playing and is
     * re-indexed as the new seed; pads don't jump tracks.
     * 2026-08-01
     */
    public void swapSongs(int a, int b) {
        if (a < 0 || b < 0 || a >= MAX_SONGS || b >= MAX_SONGS || a == b) return;
        SongState s = songs[a];
        songs[a] = songs[b];
        songs[b] = s;
        for (int z = 0; z < ZONE_COUNT; z++) {
            if (activeSongPerZone[z] == a) activeSongPerZone[z] = b;
            else if (activeSongPerZone[z] == b) activeSongPerZone[z] = a;
        }
        if (controlSongIndex == a) controlSongIndex = b;
        else if (controlSongIndex == b) controlSongIndex = a;
    }

    /**
     * Deterministic stems-set flip: every pad that fed song A now feeds song B
     * and vice versa (song 0 ↔ 1 in place), including the control song — the
     * two tracks trade places on the face. Unlike {@link #swapSongs} (seat swap
     * keeps pads on the same physical audio), this swaps what the pads play.
     * Play-both pads keep both feeds; the flip only changes which is primary.
     * A second flip restores the original set exactly (involution).
     * Layman: one OK swaps the two songs' pad assignments; a second OK swaps back.
     * 2026-08-02
     */
    public void flipSongAssignments() {
        if (songCount < 2) return;
        for (int z = 0; z < ZONE_COUNT; z++) {
            int s = activeSongPerZone[z];
            if (s == 0) activeSongPerZone[z] = 1;
            else if (s == 1) activeSongPerZone[z] = 0;
        }
        if (controlSongIndex == 0) controlSongIndex = 1;
        else if (controlSongIndex == 1) controlSongIndex = 0;
    }

    /**
     * Point every pad at {@link #controlSongIndex} (usually 0 after bind).
     * Layman: until you repress a focused pad, every arm steers the same song.
     * Was: activeSongPerZone[z] = z % songCount (focus switched track). Reversal: that modulo seed.
     * 2026-07-19
     */
    public void seedPadRoutingToControlSong() {
        int s = controlSongIndex;
        if (s < 0) s = 0;
        if (songCount > 0 && s >= songCount) s = songCount - 1;
        for (int z = 0; z < ZONE_COUNT; z++) activeSongPerZone[z] = s;
    }

    /** Set the song index for a specific zone. 2026-08-02 */
    public void setZoneSong(int zone, int songIndex) {
        if (zone >= 0 && zone < ZONE_COUNT) {
            activeSongPerZone[zone] = songIndex;
        }
    }

    /**
     * @deprecated name kept for callers; now seeds all pads to control song.
     * 2026-07-19
     */
    public void seedCrossSongPadRouting() {
        seedPadRoutingToControlSong();
    }

    /** Song currently steered by focus/wheel (0-based). 2026-07-19 */
    public int controlSongIndex() {
        return controlSongIndex;
    }

    public int songCount() {
        return songCount;
    }

    public boolean isMulti() {
        return songCount > 1;
    }

    public SongState song(int index) {
        if (index < 0 || index >= songCount) return null;
        return songs[index];
    }

    public int activeZone() {
        return activeZone;
    }

    public int songIndexForZone(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return 0;
        int s = activeSongPerZone[zone];
        if (s < 0) s = 0;
        if (s >= songCount) s = Math.max(0, songCount - 1);
        return s;
    }

    /**
     * Dominant (tempo/key anchor) song under the pad-majority rule: the song
     * owning the most pads leads the beat. A 2-2 split (“2 of each”) is a tie
     * broken by the VOCALS pad (zone 0) — the lead vocal decides the lead beat.
     * Play-both pads feed both songs so they count as neutral (never break a tie).
     * Single-song sessions always return 0.
     * Layman: the song with the most pads leads; vocals settles 2-and-2.
     * Was: seed (song 0) wins by definition. Reversal: seed-wins only.
     * 2026-08-02
     */
    public int dominantSongIndex() {
        if (songCount <= 1) return 0;
        int[] counts = new int[songCount];
        for (int z = 0; z < ZONE_COUNT; z++) {
            if (bothPerZone[z]) continue; // feeds both songs — neutral
            int s = songIndexForZone(z);
            if (s >= 0 && s < songCount) counts[s]++;
        }
        int best = 0;
        int bestCount = -1;
        boolean tie = false;
        for (int s = 0; s < songCount; s++) {
            if (counts[s] > bestCount) {
                bestCount = counts[s];
                best = s;
                tie = false;
            } else if (counts[s] == bestCount) {
                tie = true;
            }
        }
        if (tie) {
            // 2-2 (or all-neutral) → the VOCALS pad (zone 0) breaks the tie.
            int vocalsSong = songIndexForZone(0);
            if (vocalsSong >= 0 && vocalsSong < songCount) return vocalsSong;
        }
        return best;
    }

    public SongState activeSongState() {
        return song(songIndexForZone(activeZone));
    }

    /**
     * Stem key: focus zone, or if already focused toggle that pad’s song (crossfade trigger).
     * Layman: one click focuses; second click on that pad flips its stem to the other song.
     * Technical: repress toggles activeSongPerZone[zone] only — other pads keep their song.
     * Host runs timed gain fade between the two mixers (not swapPadStem).
     * Was: repress advanced controlSongIndex and seedPadRoutingToControlSong (all pads jumped).
     * Reversal: controlSongIndex = (controlSongIndex+1)%songCount; seedPadRoutingToControlSong().
     * 2026-07-19 / 2026-07-20
     */
    public boolean onStemKey(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return false;
        // Capture focus state before any write — returning to a pad must not cycle. 2026-07-19
        boolean alreadyFocused = StemControls.stemKeyShouldCycleSong(activeZone, zone, songCount);
        if (!alreadyFocused) {
            // Focus only — keep each pad’s song; wheel steers this pad’s track. 2026-07-20
            // Was: seedPadRoutingToControlSong() on focus (wiped per-pad routing). Reversal: that seed.
            activeZone = zone;
            controlSongIndex = songIndexForZone(zone);
            return false;
        }
        // Play-both pad — taps only focus; the stacked feed must not be cycled away. 2026-08-01
        if (bothPerZone[zone]) {
            activeZone = zone;
            controlSongIndex = songIndexForZone(zone);
            return false;
        }
        if (songCount <= 1) return false;
        int from = songIndexForZone(zone);
        int to = StemControls.otherSongIndex(from, songCount);
        activeSongPerZone[zone] = to;
        controlSongIndex = to;
        return true;
    }

    /**
     * Focus a pad without cycling (stutter arm, UI restore).
     * Layman: lights that pad; keeps the same song that pad was already on.
     * Was: also re-seeded all arms to control song. Reversal: seedPadRoutingToControlSong() here.
     * 2026-07-19 / 2026-07-20
     */
    public void setActiveZone(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return;
        activeZone = zone;
        controlSongIndex = songIndexForZone(zone);
    }

    /** Clear pad focus so next stem key is focus-only. 2026-07-19 */
    public void clearActiveZone() {
        activeZone = -1;
    }

    /** Display song number 1..N for a zone arm (0 if empty session). */
    public int displaySongNumber(int zone) {
        if (songCount < 1) return 0;
        return songIndexForZone(zone) + 1;
    }

    /**
     * Human track title for the song currently on a pad (ID3, else filename).
     * Layman: the song name that pad is mixing from.
     * Was: filename only. Reversal: stripTrackDisplayName(track.getName()) only.
     * 2026-07-19 / 2026-07-20
     */
    public String trackDisplayNameForZone(int zone) {
        return trackDisplayNameForSong(songIndexForZone(zone));
    }

    /**
     * Human track title for song slot — prefer ID3 title over filename.
     * 2026-07-20
     */
    public String trackDisplayNameForSong(int songIndex) {
        SongState st = song(songIndex);
        if (st == null) return "";
        if (st.id3Title != null && st.id3Title.trim().length() > 0) {
            return st.id3Title.trim();
        }
        if (st.track == null) return "";
        return StemControls.stripTrackDisplayName(st.track.getName());
    }

    /** ID3 artist for song slot (empty when unknown). 2026-07-20 */
    public String trackArtistForSong(int songIndex) {
        SongState st = song(songIndex);
        if (st == null || st.id3Artist == null) return "";
        return st.id3Artist.trim();
    }

    /** True when both mashup songs share an album tag (same cover likely). 2026-07-20 */
    public boolean songsShareAlbum() {
        if (songCount < 2) return false;
        SongState a = song(0);
        SongState b = song(1);
        if (a == null || b == null) return false;
        return StemControls.sameAlbumKey(a.id3Album, a.id3Artist, b.id3Album, b.id3Artist);
    }

    /**
     * Point every pad at one song (track-end handoff / sole survivor).
     * Layman: all four stems now come from this track.
     * 2026-07-20
     */
    public void routeAllPadsToSong(int songIndex) {
        int s = songIndex;
        if (songCount < 1) s = 0;
        else if (s < 0) s = 0;
        else if (s >= songCount) s = songCount - 1;
        for (int z = 0; z < ZONE_COUNT; z++) activeSongPerZone[z] = s;
        controlSongIndex = s;
        // One song left — nothing left to stack. 2026-08-01
        clearBothZones();
    }

    /**
     * Copy current pad→song routing into out[0..3].
     * 2026-07-20
     */
    public void copyZoneSongs(int[] out) {
        if (out == null) return;
        for (int z = 0; z < ZONE_COUNT && z < out.length; z++) {
            out[z] = songIndexForZone(z);
        }
    }

    /**
     * Centre shuffle — randomise which song feeds each pad; advance focus clockwise.
     * Layman: OK remixes both tracks onto pads; never traps all bubbles on one song.
     * Technical: {@link StemControls#pickShufflePadSongs} (forceBoth); activeZone = (activeZone+1)%4.
     * Pad gains unchanged. Was: mid-jam allowed 4:0 then stuck. Reversal: pickShuffle without forceBoth.
     * @return previous focused zone (−1 if none); check {@link #lastShuffleChanged()} for map change
     * 2026-07-20 / 2026-07-21
     */
    public int shufflePadAssignments() {
        return shufflePadAssignments(new Random(), false);
    }

    /** Same as {@link #shufflePadAssignments()} with injectable RNG (tests). 2026-07-21 */
    public int shufflePadAssignments(Random rng) {
        return shufflePadAssignments(rng, false);
    }

    /**
     * @param initial legacy cold-start mode — {@link #initialMashupShuffle} only;
     * the host's session start no longer shuffles (seeds all pads to song 0).
     * 2026-07-21 / 2026-08-02
     */
    @SuppressWarnings("deprecation") // initial branch calls the legacy pickInitialMashupPadSongs
    public int shufflePadAssignments(Random rng, boolean initial) {
        lastShuffleChanged = false;
        if (songCount < 2) return -1;
        int prevZone = activeZone;
        int[] cur = new int[ZONE_COUNT];
        copyZoneSongs(cur);
        if (initial) {
            lastShuffleChanged = StemControls.pickInitialMashupPadSongs(cur, songCount, rng);
        } else {
            lastShuffleChanged = StemControls.pickShufflePadSongs(cur, songCount, rng);
        }
        if (lastShuffleChanged) {
            for (int z = 0; z < ZONE_COUNT; z++) activeSongPerZone[z] = cur[z];
        }
        int next = prevZone < 0 ? 0 : (prevZone + 1) % ZONE_COUNT;
        activeZone = next;
        controlSongIndex = songIndexForZone(next);
        return prevZone;
    }

    /**
     * @deprecated Legacy cold-start shuffle (both tracks get ≥1 pad). The host no
     * longer calls this at session start — StemFM-style cold start seeds all four
     * pads to the seed track (song 0) and the second track enters only when the
     * user mixes it in. Kept for tests + back-compat. 2026-07-21 / 2026-08-02
     */
    @Deprecated
    public int initialMashupShuffle(Random rng) {
        return shufflePadAssignments(rng != null ? rng : new Random(), true);
    }

    /** True when the last shuffle rewrote pad→song (false only if picker failed). 2026-07-21 */
    public boolean lastShuffleChanged() {
        return lastShuffleChanged;
    }

    private boolean lastShuffleChanged;

    public List<File> trackFiles() {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < songCount; i++) {
            if (songs[i].track != null) out.add(songs[i].track);
        }
        return out;
    }
}
