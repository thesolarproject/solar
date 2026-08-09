package com.solar.launcher.mix;

import java.io.File;

/**
 * Two-deck Mix jam state — full tracks, optional stem dig per disc.
 * Layman: two songs floating as discs; Prev/Next own a deck; dig opens four stem pads.
 * Technical: slots[0..1] file/gain/bpm/rate; activeDeck for wheel/scrub.
 * Was: DECK_COUNT=3 faders. Reversal: DECK_COUNT = 3.
 * 2026-07-19 / 2026-07-21 Stems/Mix sanity
 */
public final class MixSession {
    public static final int DECK_COUNT = 2;
    /** Gain near mute — hold pad opens scrub. */
    public static final float SCRUB_GAIN_EPS = 0.02f;

    public static final class DeckState {
        public File track;
        public float gain;
        public float bpm = 120f;
        public float rate = 1f;
        public String displayName = "";
        
        // Key matching fields
        public int keyRoot = -1;
        public boolean keyMajor = true;
        public String camelot = null;
        public boolean analyzed = false;

        public void clear() {
            track = null;
            gain = 0f;
            bpm = 120f;
            rate = 1f;
            displayName = "";
            keyRoot = -1;
            keyMajor = true;
            camelot = null;
            analyzed = false;
        }

        public boolean hasTrack() {
            return track != null && track.isFile();
        }
    }

    private final DeckState[] decks = new DeckState[DECK_COUNT];
    private int activeDeck = -1;
    private int filledCount;

    public MixSession() {
        for (int i = 0; i < DECK_COUNT; i++) decks[i] = new DeckState();
    }

    /** Bind up to {@link #DECK_COUNT} files; clears empty slots. 2026-07-19 / 2026-07-21 */
    public void bindTracks(File[] tracks) {
        filledCount = 0;
        activeDeck = -1;
        for (int i = 0; i < DECK_COUNT; i++) {
            decks[i].clear();
            if (tracks != null && i < tracks.length && tracks[i] != null && tracks[i].isFile()) {
                decks[i].track = tracks[i];
                decks[i].displayName = stripExt(tracks[i].getName());
                filledCount++;
            }
        }
    }

    public void setSlot(int index, File track) {
        if (index < 0 || index >= DECK_COUNT) return;
        decks[index].clear();
        if (track != null && track.isFile()) {
            decks[index].track = track;
            decks[index].displayName = stripExt(track.getName());
        }
        recount();
    }

    /**
     * DJ chain seat swap: exchange two decks in place so the survivor keeps
     * playing and becomes the new lead deck.
     * Layman: when deck 1 (the end of pair 1) survives, it takes the start seat.
     * 2026-08-01
     */
    public void swapDecks(int a, int b) {
        if (a < 0 || b < 0 || a >= DECK_COUNT || b >= DECK_COUNT || a == b) return;
        DeckState s = decks[a];
        decks[a] = decks[b];
        decks[b] = s;
        if (activeDeck == a) activeDeck = b;
        else if (activeDeck == b) activeDeck = a;
    }

    private void recount() {
        filledCount = 0;
        for (int i = 0; i < DECK_COUNT; i++) {
            if (decks[i].hasTrack()) filledCount++;
        }
    }

    public int filledCount() {
        return filledCount;
    }

    public DeckState deck(int index) {
        if (index < 0 || index >= DECK_COUNT) return null;
        return decks[index];
    }

    public int activeDeck() {
        return activeDeck;
    }

    public void setActiveDeck(int index) {
        if (index < 0 || index >= DECK_COUNT) return;
        activeDeck = index;
    }

    public void clearActiveDeck() {
        activeDeck = -1;
    }

    /**
     * Short pad: focus deck, or no-op if already focused (scrub is hold).
     * @return true if focus changed
     */
    public boolean onDeckKey(int deck) {
        if (deck < 0 || deck >= DECK_COUNT) return false;
        if (activeDeck == deck) return false;
        activeDeck = deck;
        return true;
    }

    public static String stripExt(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name;
    }
}
