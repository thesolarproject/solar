package com.solar.launcher.mix;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.solar.launcher.StemOrMixSession;
import com.solar.launcher.stem.LalalAccount;
import com.solar.launcher.stem.LalalClient;
import com.solar.launcher.stem.StemBpm;
import com.solar.launcher.stem.StemControls;
import com.solar.launcher.stem.StemMixSoftScrub;
import com.solar.launcher.stem.StemMixer;
import com.solar.launcher.stem.StemTempoSync;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mix Player — two floating discs (Prev/Next); dig opens four stem pads for that deck.
 * Layman: layer two songs; second Prev/Next on a stemmed disc digs into Vocals/Drums/Bass/Melody.
 * Technical: MixDeck×2 + MixDiscFaceView; optional StemMixer dig; exclusive StemOrMixSession.
 * Was: MixDeck×3 + MixFaderFaceView. Reversal: DECK_COUNT=3 + fader face; drop dig.
 * 2026-07-19 · 2026-07-20 fader · 2026-07-21 Stems/Mix sanity discs+dig
 */
public final class MixPlayerHost {
    public interface HostCallbacks {
        SharedPreferences prefs();
        android.content.Context appContext();
        void setStatusTitle(String title);
        void onExitMixPlayer();
        /** BACK while playing — open library to reassign focused/last deck. */
        void onRequestReassign(int deckIndex);
        void pauseMainMusic();
        void stopCompetingAudio();
        void toast(String msg);
        void onMixSessionVolumeEnter();
        void onMixSessionVolumeExit();
        /** Unified queue for Next-up / triple advance. 2026-07-21 */
        com.solar.launcher.PlayQueue stemMixPlayQueue();
        void openStemMixPlayQueue();
        /**
         * Jam Options — slot ≥0 = deck context; −1 = session.
         * Must not pause decks. 2026-07-21
         */
        void openStemMixContextMenu(int slotDeckIndex);
        /** Advance finished deck from Next-up; return next file or null. 2026-07-21 */
        java.io.File applyMixTripleAdvance(int finishedDeck);
    }

    private static final long EXIT_HOLD_MS = 600L;
    private static final long SLOT_HOLD_MS = StemControls.STEM_TRANSITION_HOLD_MS;
    private static final long SCRUB_HOLD_MS = MixAssignSlots.HOLD_PLAY_START_MS;
    private static final int SCRUB_STEP_MS = 5000;
    private static final float FADE_EPS = MixSession.SCRUB_GAIN_EPS;
    /** First-open Mix face tip — highlight pads + wheel moves lit fader. 2026-07-20 */
    public static final String PREF_MIX_FADER_ONBOARDING_SEEN = "mix_fader_onboarding_seen";
    /** First-open Mix assign tip — Prev/Next/Play bind. 2026-07-20 */
    public static final String PREF_MIX_ASSIGN_ONBOARDING_SEEN = "mix_assign_onboarding_seen";

    private static volatile boolean sessionActive;

    private final HostCallbacks host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MixSession session = new MixSession();
    private final MixDeck[] decks = new MixDeck[MixSession.DECK_COUNT];
    private final AtomicInteger readyCount = new AtomicInteger();
    private final AtomicInteger loadGen = new AtomicInteger();

    private FrameLayout root;
    private MixDiscFaceView face;
    private TextView statusLine;
    private boolean attached;
    private boolean ready;
    private boolean volumeMode;
    private boolean scrubArmed;
    /** True while first-open glyph tip owns the status line. 2026-07-20 */
    private boolean showingOnboarding;
    private int scrubDeck = -1;
    private int scrubCursorMs;
    private boolean prevDown;
    private boolean nextDown;
    private boolean playDown;
    private boolean exitHoldFired;
    private boolean slotHoldFired;
    /** Back held for scrub arm. 2026-07-21 */
    private boolean backDown;
    private boolean backHoldFired;
    private long transitionMs = StemControls.TRANSITION_DEFAULT_MS;
    /**
     * After fadeReplaceDeck load, swell deck back to this gain (−1 = none).
     * Layman: remember how loud the disc was before the swap.
     * 2026-07-21
     */
    private final float[] pendingReplaceFadeIn = new float[MixSession.DECK_COUNT];
    {
        for (int i = 0; i < pendingReplaceFadeIn.length; i++) pendingReplaceFadeIn[i] = -1f;
    }
    private final boolean[] padDown = new boolean[MixSession.DECK_COUNT];
    private final long[] padDownAt = new long[MixSession.DECK_COUNT];
    private final boolean[] padScrubHold = new boolean[MixSession.DECK_COUNT];
    private boolean centerDown;
    private boolean centerHoldVol;
    /**
     * Stem dig — four pads for one Mix deck; sibling MixDeck keeps playing (lockstep).
     * Was: no dig. Reversal: digMode always false; drop digMixer.
     * 2026-07-21 Stems/Mix sanity
     */
    private boolean digMode;
    private int digDeck = -1;
    private int digZone;
    private StemMixer digMixer;
    private final float[] digGains = new float[] { 1f, 1f, 1f, 1f };
    private final AtomicInteger digGen = new AtomicInteger();
    private final ExecutorService digIo = Executors.newSingleThreadExecutor();

    private final Runnable exitHoldRunnable = new Runnable() {
        @Override
        public void run() {
            // Dual-hold → jam session Options (Home chip exits). 2026-07-21
            // Was: openStemMixPlayQueue / onExitMixPlayer. Reversal: host.openStemMixPlayQueue().
            if (prevDown && nextDown && !exitHoldFired) {
                exitHoldFired = true;
                try {
                    host.openStemMixContextMenu(-1);
                } catch (Exception e) {
                    try {
                        host.openStemMixPlayQueue();
                    } catch (Exception e2) {
                        host.onExitMixPlayer();
                    }
                }
            }
        }
    };

    /** Hold Prev/Next → deck Options (Replace / Scrub…). 2026-07-21 */
    private final Runnable slotHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (exitHoldFired) return;
            int deck = -1;
            if (StemControls.stemSlotHoldOneSide(prevDown, nextDown)) {
                deck = prevDown ? 0 : 1;
            }
            // Was: Play-alone → deck 2. Reversal: mixSlotHoldPlayAlone → deck 2.
            // 2026-07-21 Stems/Mix sanity — two discs only.
            if (deck < 0) return;
            slotHoldFired = true;
            try {
                host.openStemMixContextMenu(deck);
            } catch (Exception e) {
                try {
                    host.onRequestReassign(deck);
                } catch (Exception ignored) {}
            }
        }
    };

    private final Runnable scrubHoldRunnable = new Runnable() {
        @Override
        public void run() {
            int d = pendingScrubDeck;
            if (d < 0 || d >= MixSession.DECK_COUNT) return;
            if (!padDown[d]) return;
            MixSession.DeckState st = session.deck(d);
            if (st == null || st.gain > FADE_EPS) return;
            MixDeck deck = decks[d];
            if (deck == null || !deck.isPrepared()) return;
            padScrubHold[d] = true;
            scrubArmed = true;
            scrubDeck = d;
            scrubCursorMs = deck.getPositionMs();
            volumeMode = false;
            paintFace();
            host.toast("Scrub");
        }
    };
    private int pendingScrubDeck = -1;

    /**
     * Hold Back → jam session Options (same as Play tap / dual-hold).
     * Layman: keep Back down to open or (via MainActivity) dismiss Options.
     * Was: hold Back → soft scrub. Reversal: armJamScrub in this runnable.
     * Scrub remains via slot Options “Scrub” row. 2026-07-21
     */
    private final Runnable backHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (!backDown || !attached || !ready) return;
            backHoldFired = true;
            try {
                host.openStemMixContextMenu(-1);
            } catch (Exception ignored) {}
        }
    };

    private final Runnable centerHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (!centerDown || !attached || !ready) return;
            // Hold OK → circular scrub on focused deck (whole-track seek). Was: volume peek.
            // Reversal: centerHoldVol = true; volumeMode = true.
            // 2026-07-21
            int d = session.activeDeck();
            if (d < 0 || d >= MixSession.DECK_COUNT) d = 0;
            armJamScrub(d);
            centerHoldVol = true;
            paintFace();
        }
    };

    private final Runnable driftNudge = new Runnable() {
        @Override
        public void run() {
            if (!attached || !ready || scrubArmed) return;
            // Soft nudge: keep decks looping; no hard seek war (scrub owns playhead).
            main.postDelayed(this, 2000L);
        }
    };

    public MixPlayerHost(HostCallbacks host) {
        this.host = host;
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    public MixSession session() {
        return session;
    }

    /**
     * Attach Mix UI and load 1–2 tracks (null slots skipped).
     * Was: 1–3 tracks + MixFaderFaceView. Reversal: that attach block.
     * 2026-07-19 / 2026-07-21 Stems/Mix sanity
     */
    public void attach(FrameLayout container, File[] tracks) {
        detach();
        sessionActive = true;
        StemOrMixSession.setActive(true);
        try {
            host.onMixSessionVolumeEnter();
        } catch (Exception ignored) {}
        try {
            host.stopCompetingAudio();
        } catch (Exception ignored) {}
        try {
            host.pauseMainMusic();
        } catch (Exception ignored) {}

        root = container;
        root.removeAllViews();
        android.content.Context ctx = host.appContext();
        // Was: MixFaderFaceView three faders. Reversal: face = new MixFaderFaceView(ctx).
        // 2026-07-21 Stems/Mix sanity — two floating discs.
        face = new MixDiscFaceView(ctx);
        statusLine = new TextView(ctx);
        statusLine.setTextColor(0xFFCCCCCC);
        statusLine.setTextSize(12f);
        statusLine.setPadding(12, 8, 12, 4);
        // Allow multi-line first-open tip with glyphs. 2026-07-20
        statusLine.setMaxLines(4);
        android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0A0A0C);
        col.addView(statusLine, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(face, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        attached = true;
        ready = false;
        volumeMode = true;
        showingOnboarding = false;
        session.bindTracks(tracks);
        host.setStatusTitle("Mix");
        paintFace();
        maybeShowFaderOnboarding(ctx);
        if (!showingOnboarding) {
            statusLine.setText(buildStatusText());
        }
        beginLoad();
    }

    public void detach() {
        main.removeCallbacks(exitHoldRunnable);
        main.removeCallbacks(slotHoldRunnable);
        main.removeCallbacks(scrubHoldRunnable);
        main.removeCallbacks(centerHoldRunnable);
        main.removeCallbacks(driftNudge);
        loadGen.incrementAndGet();
        digGen.incrementAndGet();
        releaseDigMixerOnly();
        digMode = false;
        digDeck = -1;
        boolean was = sessionActive;
        sessionActive = false;
        if (!com.solar.launcher.stem.StemPlayerHost.isSessionActive()) {
            StemOrMixSession.setActive(false);
        }
        if (was) {
            try {
                host.onMixSessionVolumeExit();
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < decks.length; i++) {
            if (decks[i] != null) {
                decks[i].release();
                decks[i] = null;
            }
        }
        ready = false;
        attached = false;
        scrubArmed = false;
        if (root != null) {
            root.removeAllViews();
            root = null;
        }
        face = null;
        statusLine = null;
        showingOnboarding = false;
    }

    /**
     * Focused Mix deck for queue OK soft-replace (0..1).
     * Layman: which floating disc gets the song you pick in the queue.
     * 2026-07-21 Stems/Mix sanity
     */
    public int focusedDeckIndex() {
        int d = session.activeDeck();
        if (d < 0 || d >= MixSession.DECK_COUNT) return 0;
        return d;
    }

    /** True while stem dig face is open. 2026-07-21 */
    public boolean isDigMode() {
        return digMode;
    }

    /**
     * Leave stem dig → two-disc Mix (Back). Sibling deck never touched.
     * Layman: Back steps out of the four stem discs to the two Mix discs.
     * 2026-07-21 Stems/Mix sanity
     */
    public boolean exitDigIfOpen() {
        if (!digMode) return false;
        exitDigRestoreDeck();
        return true;
    }

    /**
     * First-open Mix tip moved to ContextFeatureTip modal (MainActivity).
     * Was: statusLine glyph wall. Reversal: restore showingOnboarding + bindGlyphText.
     * 2026-07-20 / 2026-07-21
     */
    private void maybeShowFaderOnboarding(android.content.Context ctx) {
        // No-op — context modal owns Mix fader teaching. 2026-07-21
        showingOnboarding = false;
    }

    /**
     * Clear first-open tip after the user moves a fader or taps a deck.
     * 2026-07-20
     */
    private void dismissFaderOnboardingIfNeeded() {
        if (!showingOnboarding) return;
        showingOnboarding = false;
        try {
            SharedPreferences p = host.prefs();
            if (p != null) {
                p.edit().putBoolean(PREF_MIX_FADER_ONBOARDING_SEEN, true).apply();
            }
        } catch (Exception ignored) {}
        if (statusLine != null) {
            statusLine.setText(buildStatusText());
        }
    }

    /**
     * True until assign tip has been acknowledged (first pad bind).
     * Layman: have we already shown the Mix assign button lesson?
     * 2026-07-20
     */
    public static boolean needsAssignOnboarding(SharedPreferences prefs) {
        return prefs == null || !prefs.getBoolean(PREF_MIX_ASSIGN_ONBOARDING_SEEN, false);
    }

    /**
     * Mark Mix assign tip seen after the first successful bind.
     * 2026-07-20
     */
    public static void markAssignOnboardingSeen(SharedPreferences prefs) {
        if (prefs == null) return;
        try {
            prefs.edit().putBoolean(PREF_MIX_ASSIGN_ONBOARDING_SEEN, true).apply();
        } catch (Exception ignored) {}
    }

    public boolean onKey(int keyCode, KeyEvent event) {
        if (event == null || !attached) return false;
        int action = event.getAction();
        // Stem dig owns Prev/Next/Play/Center/wheel; Back exits dig. 2026-07-21
        if (digMode && handleDigKey(keyCode, event)) {
            return true;
        }
        // #region agent log
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("isPlayKey", isPlayKey(keyCode));
                d.put("isWheel", keyCode == 126 || keyCode == 127
                        || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                        || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE);
                d.put("activeDeck", session.activeDeck());
                com.solar.launcher.Debug8b0481Log.log("MixPlayerHost.onKey", "mix key", "H5", d);
            } catch (Exception ignored) {}
        }
        // #endregion

        if (isPrevKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                prevDown = true;
                exitHoldFired = false;
                slotHoldFired = false;
                main.removeCallbacks(exitHoldRunnable);
                main.removeCallbacks(slotHoldRunnable);
                if (prevDown && nextDown) {
                    cancelPadHold(0);
                    cancelPadHold(1);
                    main.postDelayed(exitHoldRunnable, EXIT_HOLD_MS);
                } else {
                    beginPadHold(0);
                    main.postDelayed(slotHoldRunnable, SLOT_HOLD_MS);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                prevDown = false;
                main.removeCallbacks(exitHoldRunnable);
                main.removeCallbacks(slotHoldRunnable);
                boolean scrubbed = endPadHold(0);
                if (!exitHoldFired && !slotHoldFired && !scrubbed && !nextDown) {
                    onDeckTap(0);
                }
                exitHoldFired = false;
                slotHoldFired = false;
                return true;
            }
            return true;
        }
        if (isNextKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                nextDown = true;
                exitHoldFired = false;
                slotHoldFired = false;
                main.removeCallbacks(exitHoldRunnable);
                main.removeCallbacks(slotHoldRunnable);
                if (prevDown && nextDown) {
                    cancelPadHold(0);
                    cancelPadHold(1);
                    main.postDelayed(exitHoldRunnable, EXIT_HOLD_MS);
                } else {
                    beginPadHold(1);
                    main.postDelayed(slotHoldRunnable, SLOT_HOLD_MS);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                nextDown = false;
                main.removeCallbacks(exitHoldRunnable);
                main.removeCallbacks(slotHoldRunnable);
                boolean scrubbed = endPadHold(1);
                if (!exitHoldFired && !slotHoldFired && !scrubbed && !prevDown) {
                    onDeckTap(1);
                }
                exitHoldFired = false;
                slotHoldFired = false;
                return true;
            }
            return true;
        }
        if (isPlayKey(keyCode)) {
            // BT transport only when AVRCP — don’t steal wheel. 2026-07-21
            if (event != null
                    && com.solar.launcher.Y1BluetoothInput.isBluetoothTransportKey(event)) {
                return false;
            }
            // Play tap → jam session Options (no pause). 2026-07-21
            // Was: Play = deck 2 pad. Reversal: beginPadHold(2) / onDeckTap(2).
            if (action == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                try {
                    host.openStemMixContextMenu(-1);
                } catch (Exception ignored) {}
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                playDown = true;
                return true;
            }
            return true;
        }
        if (isCenterKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                centerDown = true;
                centerHoldVol = false;
                main.removeCallbacks(centerHoldRunnable);
                // Same intentional hold as Stem pad Options — short OK stays volume/dismiss. 2026-07-21
                main.postDelayed(centerHoldRunnable, StemControls.mashupCenterScrubHoldMs());
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(centerHoldRunnable);
                if (!centerHoldVol) {
                    dismissFaderOnboardingIfNeeded();
                    volumeMode = true;
                    scrubArmed = false;
                    scrubDeck = -1;
                    paintFace();
                }
                // Hold just armed scrub — release keeps scrub; wheel seeks whole deck. 2026-07-21
                centerDown = false;
                centerHoldVol = false;
                return true;
            }
            return true;
        }
        // Back: exit stem dig first; else jam context. 2026-07-21 Stems/Mix sanity
        // Was: tap → Options always. Reversal: drop dig exit branch.
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (digMode) {
                if (action == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                    exitDigRestoreDeck();
                }
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                backDown = true;
                backHoldFired = false;
                main.removeCallbacks(backHoldRunnable);
                main.postDelayed(backHoldRunnable, SLOT_HOLD_MS);
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(backHoldRunnable);
                if (backDown && !backHoldFired) {
                    try {
                        host.openStemMixContextMenu(-1);
                    } catch (Exception ignored) {}
                }
                backDown = false;
                backHoldFired = false;
                return true;
            }
            return true;
        }
        return false;
    }

    public void onWheel(int rawSteps) {
        if (!attached || !ready) return;
        dismissFaderOnboardingIfNeeded();
        // Dig: wheel nudges focused stem pad gain (other Mix deck untouched). 2026-07-21
        if (digMode && digMixer != null) {
            int steps = StemControls.volumeStepsFromWheel(rawSteps);
            float g = StemControls.nudgeGain(digGains[digZone], steps);
            digGains[digZone] = g;
            digMixer.setGain(digZone, g);
            paintFace();
            return;
        }
        if (scrubArmed && scrubDeck >= 0 && scrubDeck < MixSession.DECK_COUNT) {
            MixDeck deck = decks[scrubDeck];
            if (deck == null) return;
            int volSteps = StemControls.volumeStepsFromWheel(rawSteps);
            // Wheel advances whole-deck playhead (sibling deck untouched). 2026-07-19 / 2026-07-21
            scrubCursorMs = MixAssignSlots.scrubWrap(
                    scrubCursorMs, deck.getDurationMs(), volSteps * SCRUB_STEP_MS);
            deck.seekTo(scrubCursorMs);
            statusLine.setText(formatScrub(scrubCursorMs, deck.getDurationMs()));
            paintFace();
            return;
        }
        int d = session.activeDeck();
        if (d < 0 || d >= MixSession.DECK_COUNT) return;
        MixSession.DeckState st = session.deck(d);
        MixDeck deck = decks[d];
        if (st == null || deck == null) return;
        int steps = StemControls.volumeStepsFromWheel(rawSteps);
        float g = StemControls.nudgeGain(st.gain, steps);
        st.gain = g;
        deck.setGain(g);
        // LED-only — avoid full status rebuild every tick. 2026-07-19
        paintFace();
    }

    /**
     * Fade out then swap file on a live deck (mid-mix reassign) — fade back in after load.
     * Layman: that disc dissolves, the new song loads, then swells back up.
     * Was: fade-out only then hard start at gain 0. Reversal: drop pendingReplaceFadeIn.
     * 2026-07-19 / 2026-07-21
     */
    /**
     * DJ chain advance: when the LEAD deck (0) finishes, the survivor deck (1)
     * is re-indexed as the new lead — its deck keeps playing, no reload — and
     * the incoming track loads into the partner deck. Non-lead decks keep the
     * plain fade-replace. Mirrors StemMixQueuePolicy.applyAdvanceOrder's chain
     * reorder so the live pair always reads [survivor, incoming].
     * Layman: the deck that was the end of pair 1 becomes the start of pair 2.
     * 2026-08-01
     */
    public void chainAdvanceDeck(int deckIndex, File track) {
        if (deckIndex == 0 && decks != null && decks.length > 1 && decks[1] != null) {
            swapDeckSeats(0, 1);
            fadeReplaceDeck(1, track);
        } else {
            fadeReplaceDeck(deckIndex, track);
        }
    }

    /** Exchange two deck seats in place (refs only — audio keeps playing). 2026-08-01 */
    private void swapDeckSeats(int a, int b) {
        if (decks == null || a < 0 || b < 0 || a >= decks.length || b >= decks.length) return;
        if (a == b) return;
        MixDeck d = decks[a];
        decks[a] = decks[b];
        decks[b] = d;
        session.swapDecks(a, b);
    }

    public void fadeReplaceDeck(final int deckIndex, final File track) {
        if (!attached || deckIndex < 0 || deckIndex >= MixSession.DECK_COUNT) return;
        if (track == null || !track.isFile()) return;
        final MixDeck old = decks[deckIndex];
        final MixSession.DeckState st = session.deck(deckIndex);
        final float restore = (st != null && st.gain > FADE_EPS) ? st.gain : 1f;
        pendingReplaceFadeIn[deckIndex] = restore;
        if (statusLine != null) {
            statusLine.setText("Loading · "
                    + StemControls.stripTrackDisplayName(track.getName()));
        }
        Runnable swap = new Runnable() {
            @Override
            public void run() {
                if (old != null) old.release();
                session.setSlot(deckIndex, track);
                loadOneDeck(deckIndex, loadGen.get());
            }
        };
        if (old != null && MixAssignSlots.needsFadeBeforeReplace(st != null ? st.gain : 0f, FADE_EPS)) {
            old.fadeTo(0f, swap);
            if (st != null) st.gain = 0f;
        } else {
            if (st != null) st.gain = 0f;
            swap.run();
        }
    }

    /**
     * Arm soft scrub on a Mix deck from jam Options / hold Back.
     * Layman: wheel now slides that song’s playhead.
     * 2026-07-21
     */
    public void armJamScrub(int deckIndex) {
        if (!attached || !ready) return;
        int d = deckIndex;
        if (d < 0 || d >= MixSession.DECK_COUNT) d = Math.max(0, session.activeDeck());
        MixDeck deck = decks[d];
        if (deck == null || !deck.isPrepared()) {
            host.toast("Track not ready");
            return;
        }
        scrubArmed = true;
        scrubDeck = d;
        scrubCursorMs = deck.getPositionMs();
        volumeMode = false;
        paintFace();
        host.toast("Scrub");
    }

    /**
     * Apply TRANSITION preset from jam session Options.
     * Layman: pick how long deck swaps blend.
     * 2026-07-21
     */
    public void applyJamTransitionPreset(int preset) {
        if (preset < 0) return;
        transitionMs = StemControls.transitionMsForPreset(preset);
    }

    /**
     * Pause all Mix decks (session Options → Pause). Dig mixer too if open.
     * Layman: interrupt the mix without releasing players.
     * Was: no Mix pause. Reversal: delete method.
     * 2026-07-21
     */
    public void pauseJamPlayback() {
        if (!attached) return;
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            MixDeck d = decks[i];
            if (d == null) continue;
            try {
                d.pause();
            } catch (Exception ignored) {}
        }
        if (digMixer != null) {
            try {
                digMixer.pause();
            } catch (Exception ignored) {}
        }
        scrubArmed = false;
        paintFace();
        if (statusLine != null) statusLine.setText("Paused");
    }

    public boolean isDeckPlaying(int index) {
        if (!attached || decks == null || index < 0 || index >= MixSession.DECK_COUNT) return false;
        MixDeck d = decks[index];
        return d != null && d.isPlaying();
    }

    public void toggleDeckPlayPause(int index) {
        if (!attached || decks == null || index < 0 || index >= MixSession.DECK_COUNT) return;
        MixDeck d = decks[index];
        if (d == null) return;
        try {
            if (d.isPlaying()) {
                d.pause();
            } else {
                d.play();
            }
            paintFace();
        } catch (Exception ignored) {}
    }

    private void beginLoad() {
        final int gen = loadGen.incrementAndGet();
        readyCount.set(0);
        int need = 0;
        float masterBpm = StemBpm.DEFAULT_BPM;
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            MixSession.DeckState st = session.deck(i);
            if (st == null || !st.hasTrack()) continue;
            need++;
        }
        if (need == 0) {
            host.toast("Mix needs a track");
            host.onExitMixPlayer();
            return;
        }
        // Estimate master BPM from first filled slot after prepare — provisional here. 2026-07-19
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            loadOneDeck(i, gen);
        }
        final int needFinal = need;
        // Wait for ready via listeners; if none prepared, exit.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != loadGen.get()) return;
                if (readyCount.get() <= 0) {
                    host.toast("Mix could not load");
                    host.onExitMixPlayer();
                }
            }
        }, 15000L);
    }

    private void loadOneDeck(final int index, final int gen) {
        final MixSession.DeckState st = session.deck(index);
        if (st == null || !st.hasTrack()) return;
        final MixDeck deck = new MixDeck(host.appContext());
        decks[index] = deck;

        // Background analysis for true BPM and Key matching
        digIo.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != loadGen.get() || !attached) return;
                // Analyzer decodes the original file when stems are null (safe fallback).
                final com.solar.launcher.stem.analysis.StemAnalysisCore.Result res =
                        com.solar.launcher.stem.analysis.StemTrackAnalyzer.analyze(
                                host.appContext(), st.track, null, null);
                if (res != null && gen == loadGen.get()) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != loadGen.get()) return;
                            st.bpm = res.bpm;
                            st.keyRoot = res.keyRoot;
                            st.keyMajor = res.keyMajor;
                            st.camelot = res.camelot;
                            st.analyzed = true;
                            applyRates();
                        }
                    });
                }
            }
        });

        deck.setListener(new MixDeck.Listener() {
            @Override
            public void onReady(MixDeck d) {
                if (gen != loadGen.get()) return;
                MixSession.DeckState s = session.deck(index);
                if (s != null && !s.analyzed) {
                    s.bpm = StemBpm.estimateFromDurationMs(d.getDurationMs());
                }
                applyRates();
                int n = readyCount.incrementAndGet();
                d.setGain(0f);
                if (s != null) s.gain = 0f;
                
                // Stem.FM style Phase Alignment: snap to the master deck's downbeat
                int masterIdx = -1;
                for (int i = 0; i < MixSession.DECK_COUNT; i++) {
                    if (i != index && decks[i] != null && decks[i].isPlaying()) {
                        masterIdx = i;
                        break;
                    }
                }
                if (masterIdx >= 0) {
                    MixSession.DeckState masterS = session.deck(masterIdx);
                    if (masterS != null && s != null) {
                        int startPos = StemTempoSync.phaseAlignedStartMs(
                            decks[masterIdx].getPositionMs(),
                            masterS.rate,
                            0, // survivorFirstBeatMs — Mix decks carry no downbeat analysis
                            s.rate,
                            0, // myFirstBeatMs
                            s.bpm);
                        try {
                            d.seekTo(startPos);
                        } catch (Exception ignored) {}
                    }
                }
                
                d.play();
                // Mid-jam replace: fade back to prior level (not hard silence). 2026-07-21
                float restore = -1f;
                if (index >= 0 && index < pendingReplaceFadeIn.length) {
                    restore = pendingReplaceFadeIn[index];
                    pendingReplaceFadeIn[index] = -1f;
                }
                if (restore > FADE_EPS) {
                    final float target = restore;
                    d.fadeTo(target, new Runnable() {
                        @Override
                        public void run() {
                            MixSession.DeckState ds = session.deck(index);
                            if (ds != null) ds.gain = target;
                            paintFace();
                        }
                    });
                    if (statusLine != null && !showingOnboarding) {
                        statusLine.setText(buildStatusText());
                    }
                }
                maybeAllReady(n);
            }

            @Override
            public void onError(MixDeck d, String message) {
                if (gen != loadGen.get()) return;
                host.toast(message != null ? message : "Mix error");
            }

            @Override
            public void onComplete(MixDeck d) {
                // Triple advance from unified queue when Next-up exists; else loop deck. 2026-07-21
                // Was: MixDeck auto-looped before notify. Reversal: seek/start inside MixDeck only.
                int finished = -1;
                for (int i = 0; i < decks.length; i++) {
                    if (decks[i] == d) {
                        finished = i;
                        break;
                    }
                }
                if (finished < 0) return;
                File next = null;
                try {
                    next = host.applyMixTripleAdvance(finished);
                } catch (Exception ignored) {}
                if (next != null && next.isFile()) {
                    if (statusLine != null) {
                        statusLine.setText("Next up · "
                                + StemControls.stripTrackDisplayName(next.getName()));
                    }
                    // DJ chain: lead deck finish keeps the survivor as the new lead
                    // (end of pair 1 → start of pair 2); other decks plain-replace.
                    chainAdvanceDeck(finished, next);
                    return;
                }
                try {
                    d.seekTo(0);
                    d.play();
                } catch (Exception ignored) {}
            }
        });
        try {
            float rate = 1f;
            deck.load(st.track, rate);
        } catch (Exception e) {
            host.toast(e.getMessage() != null ? e.getMessage() : "Mix load failed");
        }
    }

    private void applyRates() {
        MixSession.DeckState mState = session.deck(0);
        if (mState == null || !mState.hasTrack()) mState = session.deck(1);
        
        float masterBpm = mState != null ? mState.bpm : StemBpm.DEFAULT_BPM;
        if (masterBpm <= 30f) masterBpm = StemBpm.DEFAULT_BPM;
        
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            MixSession.DeckState s = session.deck(i);
            MixDeck d = decks[i];
            if (s == null || d == null || !s.hasTrack()) continue;
            
            float rate = StemTempoSync.rateToMatchMaster(masterBpm, s.bpm);
            s.rate = rate;
            d.setRate(rate);
            
            // Native pitch shifting (Camelot key matching) — shared helper so the DJ Mix
            // and stem mashup paths agree on the same wheel math. 2026-08-02
            float pitchFactor = mState != null && mState != s
                    ? com.solar.launcher.stem.StemSoundTouch.pitchFactorForKeys(
                            mState.keyRoot, mState.keyMajor, s.keyRoot, s.keyMajor)
                    : 1f;
            d.setPitch(pitchFactor);
        }
    }

    private void maybeAllReady(int n) {
        int filled = session.filledCount();
        if (n < filled) return;
        ready = true;
        if (session.activeDeck() < 0) {
            for (int i = 0; i < MixSession.DECK_COUNT; i++) {
                if (session.deck(i) != null && session.deck(i).hasTrack()) {
                    session.setActiveDeck(i);
                    break;
                }
            }
        }
        paintFace();
        // Keep first-open tip until wheel/deck dismisses it. 2026-07-20
        if (!showingOnboarding && statusLine != null) {
            statusLine.setText(buildStatusText());
        }
        host.setStatusTitle("Mix");
        main.removeCallbacks(driftNudge);
        main.postDelayed(driftNudge, 2000L);
    }

    private void onDeckTap(int deck) {
        dismissFaderOnboardingIfNeeded();
        if (!session.onDeckKey(deck)) {
            // Already focused — dig into stems when pads exist on disk. 2026-07-21
            // Was: no-op (hold does scrub). Reversal: paintFace only; drop tryEnterDig.
            tryEnterStemDig(deck);
            paintFace();
            return;
        }
        MixSession.DeckState st = session.deck(deck);
        if (st != null && st.displayName != null && st.displayName.length() > 0) {
            host.toast(st.displayName);
        }
        paintFace();
        if (!showingOnboarding && statusLine != null) {
            statusLine.setText(buildStatusText());
        }
    }

    /**
     * Dig keys while four-stem face is open — focus zone / nudge via wheel path.
     * Layman: Prev/Next/Play/OK light Vocals/Drums/Bass/Melody; other Mix disc keeps playing.
     * @return true when consumed
     * 2026-07-21 Stems/Mix sanity
     */
    private boolean handleDigKey(int keyCode, KeyEvent event) {
        if (!digMode || event == null) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_UP || event.getRepeatCount() != 0) {
            // Consume DOWN so Mix deck focus does not fight dig. 2026-07-21
            return isPrevKey(keyCode) || isNextKey(keyCode) || isPlayKey(keyCode)
                    || isCenterKey(keyCode);
        }
        int zone = -1;
        if (isPrevKey(keyCode)) zone = 0; // Vocals
        else if (isNextKey(keyCode)) zone = 1; // Drums
        else if (isPlayKey(keyCode)) zone = 2; // Bass
        else if (isCenterKey(keyCode)) zone = 3; // Melody
        if (zone < 0) return false;
        digZone = zone;
        paintFace();
        return true;
    }

    /**
     * Second Prev/Next on focused deck → open stem dig when pads ready.
     * Layman: dig only when stems already sit beside the song — never cook mid-Mix.
     * Audio: mute that MixDeck; StemMixer plays pads; sibling MixDeck untouched (lockstep).
     * 2026-07-21 Stems/Mix sanity
     */
    private void tryEnterStemDig(final int deck) {
        if (deck < 0 || deck >= MixSession.DECK_COUNT) return;
        MixSession.DeckState st = session.deck(deck);
        if (st == null || st.track == null || !st.track.isFile()) {
            host.toast("No track on this disc");
            return;
        }
        final File track = st.track;
        final android.content.Context ctx = host.appContext();
        final boolean premix = LalalAccount.isPremixExperimental(host.prefs());
        if (!LalalClient.trackStemsReady(ctx, track, premix, ctx.getCacheDir())) {
            host.toast("No stems for dig — prepare first");
            return;
        }
        final int gen = digGen.incrementAndGet();
        digMode = true;
        digDeck = deck;
        digZone = 0;
        if (statusLine != null) statusLine.setText("Stem dig…");
        paintFace();
        // Mute this MixDeck only — sibling keeps playing. 2026-07-21
        MixDeck md = decks[deck];
        if (md != null) {
            try {
                md.setGain(0f);
            } catch (Exception ignored) {}
        }
        if (st != null) st.gain = 0f;
        digIo.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != digGen.get()) return;
                try {
                    File readyDir = LalalClient.findReadyStemDir(
                            ctx, track, premix, ctx.getCacheDir());
                    List<LalalClient.StemFile> stems = null;
                    if (readyDir != null) {
                        stems = LalalClient.resolveStemsFromReadyDir(ctx, track, premix, readyDir);
                    }
                    if (stems == null || stems.isEmpty()) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (gen != digGen.get()) return;
                                host.toast("Could not open stem dig");
                                exitDigRestoreDeck();
                            }
                        });
                        return;
                    }
                    final StemMixer mixer = new StemMixer(ctx);
                    mixer.loadPads(stems);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != digGen.get()) {
                                mixer.release();
                                return;
                            }
                            releaseDigMixerOnly();
                            digMixer = mixer;
                            for (int z = 0; z < digGains.length; z++) {
                                digGains[z] = 1f;
                                mixer.setGain(z, 1f);
                            }
                            try {
                                mixer.play();
                            } catch (Exception ignored) {}
                            paintFace();
                            if (statusLine != null) {
                                statusLine.setText("Stem dig · Back exits");
                            }
                        }
                    });
                } catch (Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != digGen.get()) return;
                            host.toast("Stem dig failed");
                            exitDigRestoreDeck();
                        }
                    });
                }
            }
        });
    }

    /** Release dig StemMixer without restoring MixDeck. 2026-07-21 */
    private void releaseDigMixerOnly() {
        if (digMixer != null) {
            try {
                digMixer.release();
            } catch (Exception ignored) {}
            digMixer = null;
        }
    }

    /**
     * Close dig and restore MixDeck playback on that seat.
     * Layman: leave the four stem discs; that Mix song comes back under its disc.
     * 2026-07-21 Stems/Mix sanity
     */
    private void exitDigRestoreDeck() {
        digGen.incrementAndGet();
        releaseDigMixerOnly();
        final int deck = digDeck;
        digMode = false;
        digDeck = -1;
        digZone = 0;
        if (deck >= 0 && deck < MixSession.DECK_COUNT) {
            MixSession.DeckState st = session.deck(deck);
            MixDeck md = decks[deck];
            if (st != null && md != null) {
                float g = st.gain > FADE_EPS ? st.gain : 1f;
                st.gain = g;
                try {
                    md.setGain(g);
                    if (!md.isPlaying()) md.play();
                } catch (Exception ignored) {}
            }
        }
        paintFace();
        if (statusLine != null) statusLine.setText(buildStatusText());
    }

    private void beginPadHold(int deck) {
        padDown[deck] = true;
        padDownAt[deck] = android.os.SystemClock.uptimeMillis();
        padScrubHold[deck] = false;
        pendingScrubDeck = deck;
        main.removeCallbacks(scrubHoldRunnable);
        main.postDelayed(scrubHoldRunnable, SCRUB_HOLD_MS);
    }

    private boolean endPadHold(int deck) {
        padDown[deck] = false;
        main.removeCallbacks(scrubHoldRunnable);
        boolean scrubbed = padScrubHold[deck];
        padScrubHold[deck] = false;
        return scrubbed;
    }

    private void cancelPadHold(int deck) {
        padDown[deck] = false;
        padScrubHold[deck] = false;
        main.removeCallbacks(scrubHoldRunnable);
    }

    private void paintFace() {
        if (face == null) return;
        if (digMode) {
            boolean[] has = new boolean[] { true, true, true, true };
            face.setDigState(digGains, has, digZone, digMixer == null);
            return;
        }
        float[] g = new float[MixSession.DECK_COUNT];
        boolean[] has = new boolean[MixSession.DECK_COUNT];
        // Direct deck→disc map (0=Prev, 1=Next). 2026-07-21 Stems/Mix sanity
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            MixSession.DeckState st = session.deck(i);
            if (st != null && st.hasTrack()) {
                g[i] = st.gain;
                has[i] = true;
            }
        }
        int focus = session.activeDeck() >= 0 ? session.activeDeck() : 0;
        float frac = 0f;
        if (scrubArmed && scrubDeck >= 0 && scrubDeck < MixSession.DECK_COUNT
                && decks[scrubDeck] != null) {
            int dur = decks[scrubDeck].getDurationMs();
            frac = StemMixSoftScrub.thumbFrac(scrubCursorMs, dur);
        }
        face.setDeckState(g, has, focus, !ready, scrubArmed, frac);
    }

    private String buildStatusText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MixSession.DECK_COUNT; i++) {
            MixSession.DeckState st = session.deck(i);
            if (i > 0) sb.append("  ·  ");
            if (st == null || !st.hasTrack()) {
                sb.append((i + 1)).append(": -");
            } else {
                sb.append((i + 1)).append(": ").append(st.displayName);
                if (session.activeDeck() == i) sb.append(" ◀");
            }
        }
        if (scrubArmed) sb.append("  [scrub]");
        return sb.toString();
    }

    private static String formatScrub(int pos, int dur) {
        return formatMs(pos) + " / " + formatMs(dur);
    }

    private static String formatMs(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private static boolean isPrevKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == 21;
    }

    private static boolean isNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == 22;
    }

    private static boolean isPlayKey(int keyCode) {
        // Never treat Y1 wheel MEDIA_PLAY/PAUSE as deck 3. 2026-07-19
        return com.solar.launcher.SolarPadKeys.isPadPlayKey(keyCode);
    }

    private static boolean isCenterKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == 66;
    }
}
