package com.solar.launcher.stem;

import java.util.Random;

/**
 * Pure Stem Player control math — gain steps + Gen1 bar-loop ladder + wheel polarity.
 * Layman: CW turns volume up / shortens the loop; CCW turns volume down / lengthens the loop.
 * Technical: raw wheelUp=+1 from MainActivity; volume uses negated steps; loop uses raw steps
 * so CW/CCW feel opposite across the two Center modes.
 * Was: same sign for gain and bars (both felt backwards on volume). Reversal: drop polarity helpers.
 * 2026-07-19 — none rung + Center toggles edit (wheel arms A–B).
 * 2026-07-20 — hold beat-roll delay 350ms (was 200; plan ≈ hold-Center family).
 * 2026-07-21 — mashup shuffle weighted 2:2-most-common; pad-hold Options (rapid);
 * short taps = pad focus only; silent pads dim on face; 2s idle defocus+shrink.
 */
public final class StemControls {
    // Was: 14 clicks 0→1. Now: 7 = twice as loud per wheel notch. Reversal: GAIN_CLICKS_FULL = 14.
    // 2026-07-20
    public static final int GAIN_CLICKS_FULL = 7;
    public static final float GAIN_STEP = 1f / GAIN_CLICKS_FULL;
    /** Sentinel — loop-edit "none" (no A–B). Wheel must leave this rung to start a loop. */
    public static final float LOOP_BARS_NONE = 0f;
    /**
     * Loop ladder: none then Gen1 bar lengths.
     * Was: {0.25…8} only — Center auto-started 1 bar. Reversal: drop index-0 none.
     * 2026-07-19
     */
    public static final float[] LOOP_BARS = {
            LOOP_BARS_NONE, 0.25f, 0.5f, 1f, 2f, 4f, 8f
    };
    public static final float DEFAULT_LOOP_BARS = 1f;
    // Was: 200L then 480L. Plan wants ~350ms hold-to-beat-roll. Reversal: STEM_STUTTER_HOLD_MS = 200L.
    // 2026-07-20
    public static final long STEM_STUTTER_HOLD_MS = 350L;
    public static final int DEFAULT_STUTTER_CHOP_STEP = 2;
    public static final float DEFAULT_HOLD_SCREW_RATE = 0.85f;
    public static final float TEMP_GAIN_THRESHOLD = 0.05f;
    public static final float TEMP_CHOP_GAIN = 0.75f;
    public static final long TEMP_GAIN_FADE_MS = 200L;
    /**
     * At/below this, pause the pad — setVolume(0) still leaks on IJK/MTK.
     * Layman: zero on the dial means truly silent, not a whisper.
     * 2026-07-19
     */
    public static final float SILENT_GAIN = 0.001f;

    /**
     * TRANSITION menu presets — timed gain crossfade between Song1/Song2 mixers.
     * Layman: LONG = slow blend · ∞ = long overlap · wave = quick snap.
     * Was: instant pad song swap (routing only). Reversal: ignore these; call swapPadStem.
     * 2026-07-20
     */
    public static final int TRANSITION_PRESET_LONG = 0;
    public static final int TRANSITION_PRESET_OVERLAP = 1; // ∞
    public static final int TRANSITION_PRESET_WAVE = 2;
    public static final int TRANSITION_PRESET_INSTANT = 3;
    public static final long TRANSITION_LONG_MS = 4000L;
    public static final long TRANSITION_OVERLAP_MS = 8000L;
    public static final long TRANSITION_WAVE_MS = 400L;
    public static final long TRANSITION_INSTANT_MS = 0L;
    /** Default mashup song-replace blend (LONG). Pad repress uses WAVE. 2026-07-20 / 2026-07-21 */
    public static final long TRANSITION_DEFAULT_MS = TRANSITION_LONG_MS;
    /**
     * Pad repress / shuffle zone swap — snappy “instant” feel (WAVE).
     * Layman: flipping a pad to the other song blends in a blink.
     * Was: used full TRANSITION_DEFAULT_MS (4s) for repress. Reversal: return transitionMs.
     * 2026-07-21
     */
    public static long padRepressTransitionMs() {
        return TRANSITION_WAVE_MS;
    }
    /**
     * Two-track jam cold start — every pad at 50% before initial shuffle.
     * Layman: pads wake at half volume so you can hear the mix and turn them up/down.
     * Was: 0.01f (~1%). Reversal: MASHUP_START_PAD_GAIN = 0.01f.
     * 2026-07-21
     */
    public static final float MASHUP_START_PAD_GAIN = 0.5f;
    /**
     * Near-silent floor for auto-bump on track switch (not cold-start level).
     * Layman: only bump a basically-muted pad when its song flips.
     * Was: compared against MASHUP_START_PAD_GAIN (broke when start became 50%).
     * Reversal: padGainAtOrBelowStartFloor uses MASHUP_START_PAD_GAIN again.
     * 2026-07-21
     */
    public static final float PAD_SWITCH_FLOOR = 0.01f;
    /**
     * Auto-bump when track-switching a silent / start-floor pad.
     * Layman: flip a quiet pad → new stem is quietly audible (~10%) without a wheel nudge.
     * Was: silent pad stayed silent after swap. Reversal: always keep padGainAfterTrackSwitch = current.
     * 2026-07-21
     */
    public static final float PAD_SWITCH_AUDIBLE_GAIN = 0.10f;
    /** Tick while running a zone/song gain crossfade (32ms ≈ 30Hz smooth transition). 2026-07-21 */
    public static final long TRANSITION_TICK_MS = 32L;
    /** Minimum interval between pad face repaints to prevent UI thread thrashing (~30Hz max). 2026-07-21 */
    public static final long FACE_INVALIDATE_MIN_MS = 32L;
    /**
     * Legacy TRANSITION hold length (kept for dual-exit / Mix parity callers).
     * Stem pads Options use {@link #STEM_OPTIONS_HOLD_MS} (faster).
     * 2026-07-20 / 2026-07-21
     */
    public static final long STEM_TRANSITION_HOLD_MS = 480L;
    /**
     * Mashup pads face: hold any pad key this long → Options (intentional hold).
     * Layman: keep Prev/Next/Back/Play down a beat for the replace menu — taps stay taps.
     * Was: 280L (felt like Options on every press). Reversal: STEM_OPTIONS_HOLD_MS = 280L.
     * 2026-07-21
     */
    public static final long STEM_OPTIONS_HOLD_MS = 520L;
    /**
     * Shuffle weight for 2:2 (1:1 pad split) vs other masks.
     * Layman: balanced mixes come up most often; wild splits still happen.
     * Was: uniform 1 for every mask. Reversal: SHUFFLE_PAIR_WEIGHT = 1.
     * 2026-07-21
     */
    public static final int SHUFFLE_PAIR_WEIGHT = 3;
    /** Non-2:2 mask weight (3:1, 4:0, …). 2026-07-21 */
    public static final int SHUFFLE_SKEW_WEIGHT = 1;

    private StemControls() {}

    /**
     * Hold delay for Stem mashup Options (pads face only).
     * Layman: how long to keep a pad button down before the menu pops.
     * 2026-07-21
     */
    public static long mashupOptionsHoldMs() {
        return STEM_OPTIONS_HOLD_MS;
    }

    /**
     * Hold Center this long on a focused pad → circular face scrub (not shuffle).
     * Layman: keep OK down a beat to scrub that pad’s song; a quick click still shuffles.
     * Same intentional length as pad Options so short≠hold stays consistent.
     * Was: Center hold only screw-peek during beat-roll. Reversal: return 0 / never arm.
     * 2026-07-21
     */
    public static long mashupCenterScrubHoldMs() {
        return STEM_OPTIONS_HOLD_MS;
    }

    /**
     * Hold OK may arm pad scrub when a pad is focused and stems are ready.
     * Layman: you need a lit bubble before Hold OK opens the seek dial.
     * Was: no Hold-OK scrub. Reversal: return false.
     * 2026-07-21
     */
    public static boolean centerHoldArmsPadScrub(boolean ready, int activeZone) {
        return ready && activeZone >= 0 && activeZone < StemMixer.STEM_COUNT;
    }

    /**
     * Quiet pause before pads defocus + shrink (no keys / wheel / scrub).
     * Layman: after two seconds of hands-off, bubbles go small so bumps don’t flip songs.
     * Was: focus stayed forever. Reversal: PAD_IDLE_DEFOCUS_MS = Long.MAX_VALUE / never schedule.
     * 2026-07-21
     */
    public static final long PAD_IDLE_DEFOCUS_MS = 2000L;
    /**
     * Draw scale while idle-defocused (visual only — mixer gains unchanged).
     * Layman: pads look a bit smaller so the face reads “asleep”.
     * Was: always 1f. Reversal: PAD_IDLE_SHRINK_SCALE = 1f.
     * 2026-07-21
     */
    public static final float PAD_IDLE_SHRINK_SCALE = 0.78f;

    /**
     * True when idle timer should clear pad focus.
     * Layman: enough quiet time passed since the last pad poke.
     * 2026-07-21
     */
    public static boolean padIdleShouldDefocus(long lastInteractUptimeMs, long nowUptimeMs) {
        if (lastInteractUptimeMs <= 0L) return false;
        return (nowUptimeMs - lastInteractUptimeMs) >= PAD_IDLE_DEFOCUS_MS;
    }

    /**
     * Face draw scale for idle shrink (1 = normal).
     * Audio path unchanged — paint only.
     * 2026-07-21
     */
    public static float padIdleDrawScale(boolean padsIdle) {
        return padsIdle ? PAD_IDLE_SHRINK_SCALE : 1f;
    }

    /**
     * Center OK while no pad focused directly triggers shuffle without requiring a pad focus first.
     * ponytail: User requested that center tap triggers shuffle without needing to focus a pad.
     */
    public static boolean centerTapWhilePadIdleIsWakeOnly(boolean padsIdle, int activeZone) {
        return false;
    }

    /**
     * Center UP after Hold-OK armed scrub: stay in scrub (confirm is a later OK tap).
     * Layman: letting go after the hold starts scrub — does not jump yet.
     * Explicit confirm: next short OK commits; Back cancels.
     * Was: any Center UP shuffled. Reversal: return false.
     * 2026-07-21
     */
    public static boolean centerReleaseKeepsFaceScrub(boolean holdFiredThisPress) {
        return holdFiredThisPress;
    }

    /**
     * Center UP while already scrubbing (not the arming hold) → commit seek.
     * Layman: tap OK again to land the needle; Back backs out.
     * 2026-07-21
     */
    public static boolean centerTapCommitsFaceScrub(boolean faceScrubArmed,
            boolean holdFiredThisPress) {
        return faceScrubArmed && !holdFiredThisPress;
    }

    /**
     * Pad zone for a jam Options hold key — Back Vocals, Prev Drums, Next Bass, Play Melody.
     * Layman: which bubble that button belongs to.
     * Technical: zone 0..3; −1 if not a pad Options key.
     * Was: Prev/Next mapped to live song 0/1 only. Reversal: return prev?0:1 style.
     * 2026-07-21
     */
    public static int padZoneForOptionsHoldKey(boolean isBack, boolean isPrev, boolean isNext,
            boolean isPadPlay) {
        if (isBack) return 0;
        if (isPrev) return 1;
        if (isNext) return 2;
        if (isPadPlay) return 3;
        return -1;
    }

    /**
     * Short taps must never open jam Options — hold-only.
     * Layman: a quick click only focuses or flips the pad.
     * Was: short Play opened Options. Reversal: return true for Play.
     * 2026-07-21
     */
    public static boolean shortTapOpensJamContext() {
        return false;
    }

    /** True when bars is the none rung (no A–B). 2026-07-19 */
    public static boolean isLoopBarsNone(float bars) {
        return bars <= 0.001f;
    }

    public static float clampGain(float g) {
        if (g < 0f) return 0f;
        if (g > 1f) return 1f;
        return g;
    }

    /** True when pad must hard-mute (pause), not rely on setVolume(0). 2026-07-19 */
    public static boolean isGainSilent(float g) {
        return clampGain(g) <= SILENT_GAIN;
    }

    public static int volumeStepsFromWheel(int rawWheelSteps) {
        return -rawWheelSteps;
    }

    public static int loopStepsFromWheel(int rawWheelSteps) {
        return rawWheelSteps;
    }

    public static float nudgeGain(float current, int steps) {
        return clampGain(current + steps * GAIN_STEP);
    }

    /** Index of closest LOOP_BARS entry; none (≤0) → 0. 2026-07-19 */
    public static int loopIndexForBars(float bars) {
        if (isLoopBarsNone(bars)) return 0;
        int best = 1;
        float bestD = Math.abs(LOOP_BARS[1] - bars);
        for (int i = 2; i < LOOP_BARS.length; i++) {
            float d = Math.abs(LOOP_BARS[i] - bars);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    public static float nudgeLoopBars(float currentBars, int steps) {
        int idx = loopIndexForBars(currentBars) + steps;
        if (idx < 0) idx = 0;
        if (idx >= LOOP_BARS.length) idx = LOOP_BARS.length - 1;
        return LOOP_BARS[idx];
    }

    public static int dotsForGain(float gain, int maxDots) {
        if (maxDots < 1) return 0;
        float g = clampGain(gain);
        if (g <= 0.001f) return 0;
        int n = Math.round(g * maxDots);
        if (n < 1) n = 1;
        if (n > maxDots) n = maxDots;
        return n;
    }

    /** none → 0 dots. 2026-07-19 */
    public static int dotsForLoopBars(float bars, int maxDots) {
        if (maxDots < 1) return 0;
        if (isLoopBarsNone(bars)) return 0;
        int idx = loopIndexForBars(bars);
        int barRungs = LOOP_BARS.length - 1;
        int n = 1 + ((idx - 1) * (maxDots - 1)) / Math.max(1, barRungs - 1);
        if (n < 1) n = 1;
        if (n > maxDots) n = maxDots;
        return n;
    }

    /** Focusing a pad always opens volume mode. 2026-07-19 */
    public static boolean wheelLoopModeForStem(boolean audioLooping, boolean stemInLoopCtrl) {
        return false;
    }

    public static boolean centerShouldLeaveLoopEdit(boolean wheelLoopMode) {
        return wheelLoopMode;
    }

    /**
     * Center no longer opens loop-edit (leftover seek/restart feel on mute).
     * Layman: OK does not start a loop — wheel only changes pad volume.
     * Was: true (onCenterTap → enterLoopEdit). Reversal: return true.
     * 2026-07-21
     */
    public static boolean centerEntersLoopEdit() {
        return false;
    }

    /**
     * Hold no longer arms beat-roll / chop (mixer seek stutter).
     * Layman: holding a pad does not chop the song.
     * Was: mashupAllowsBeatRoll for single-track. Reversal: return !multiSong.
     * 2026-07-21
     */
    public static boolean userMayArmBeatRoll(boolean multiSong) {
        return false;
    }

    public static boolean wheelUsesVolume(boolean wheelLoopMode) {
        return !wheelLoopMode;
    }

    public static boolean wheelUsesVolume(boolean wheelLoopMode, boolean centerHoldVolumeActive) {
        return wheelUsesVolume(wheelLoopMode);
    }

    public static boolean faceShowsLoopBars(boolean wheelLoopMode) {
        return wheelLoopMode;
    }

    /** Hold-Center volume peek hides loop beads. 2026-07-19 */
    public static boolean faceShowsLoopBars(boolean wheelLoopMode, boolean centerHoldVolumeActive) {
        if (centerHoldVolumeActive) return false;
        return faceShowsLoopBars(wheelLoopMode);
    }

    /**
     * Dual Prev+Next hold.
     * Single-track: exit. Mashup/Mix: session context (queue / TRANSITION / Exit).
     * Was: mashup dual unused (one-side TRANSITION). Reversal: mashup dual → exit only.
     * 2026-07-19 / 2026-07-20 / 2026-07-21
     */
    public static boolean stemExitBothSidesHeld(boolean prevDown, boolean nextDown) {
        return prevDown && nextDown;
    }

    /**
     * Mashup/Mix: dual-hold opens session context (not immediate exit).
     * Alias of both-sides — host routes to modal vs exit by mode.
     * 2026-07-21
     */
    public static boolean stemSessionContextBothSidesHeld(boolean prevDown, boolean nextDown) {
        return prevDown && nextDown;
    }

    /**
     * Exactly one side key held — arm slot context (Replace / Scrub).
     * Layman: hold Prev or Next alone to swap that track or soft-scrub it.
     * Was: one-side → TRANSITION menu. Reversal: openTransitionMenu on one-side again.
     * 2026-07-20 / 2026-07-21
     */
    public static boolean stemTransitionHoldOneSide(boolean prevDown, boolean nextDown) {
        return prevDown != nextDown;
    }

    /**
     * Same as {@link #stemTransitionHoldOneSide} — renamed for slot-context intent.
     * 2026-07-21
     */
    public static boolean stemSlotHoldOneSide(boolean prevDown, boolean nextDown) {
        return stemTransitionHoldOneSide(prevDown, nextDown);
    }

    /**
     * Mix: hold Play alone arms deck-3 slot context.
     * Layman: on Mix, hold the play pad to swap or scrub the third song.
     * 2026-07-21
     */
    public static boolean mixSlotHoldPlayAlone(boolean playDown, boolean prevDown, boolean nextDown) {
        return playDown && !prevDown && !nextDown;
    }

    /**
     * Mashup: short Play must never open Options — Melody pad focus/blend only.
     * Layman: a quick Play tap flips the bottom pad, not the jam menu.
     * Was: return multiSong (tap opened context). Reversal: return multiSong.
     * 2026-07-21
     */
    public static boolean mashupPlayOpensContext(boolean multiSong) {
        return false;
    }

    /**
     * Mashup: hold Play opens pad Options for Melody’s song (short tap stays pad focus).
     * Layman: keep Play down for the replace menu; tap still arms the Melody pad.
     * Was: tap opened context via mashupPlayOpensContext. Reversal: return false.
     * 2026-07-21
     */
    public static boolean mashupPlayHoldOpensContext(boolean multiSong) {
        return multiSong;
    }

    /**
     * Mid-jam replace browse: OK commits the focused song; Prev/Next is the other-song shortcut.
     * Layman: Center swaps the lit track; Prev or Next swaps the other one in one press.
     * Was: Prev=Track1 / Next=Track2 assign ritual. Reversal: return focused for every key.
     * @return song index to soft-replace (−1 invalid)
     * 2026-07-21
     */
    public static int stemReplaceTargetSong(int focusedSong, int songCount,
            boolean okConfirm, boolean prevOrNextShortcut) {
        if (songCount < 1) return -1;
        int focus = clampSongIndex(focusedSong, songCount);
        if (okConfirm) return focus;
        if (prevOrNextShortcut) {
            if (songCount <= 1) return focus;
            return (focus + 1) % songCount;
        }
        return -1;
    }


    /**
     * Mashup pads face: hold any pad key opens slot Options for that pad’s song.
     * Layman: hold the button under a bubble to swap the track feeding it.
     * Dual Prev+Next still opens session Options (caller). Short taps never open.
     * Was: only Prev/Next → song 0/1; Back/Play → session −1. Reversal: that mapping.
     * 2026-07-21
     */
    public static boolean mashupPadHoldOpensSlotContext(boolean multiSong) {
        return multiSong;
    }

    /**
     * Hold Prev / Next / Play / Back opens or dismisses jam Options — never Center.
     * Layman: keep any side button down to show or hide the menu; OK stays for picking rows.
     * Was: only Prev/Next holds + Play tap; Back ignored for Options. Reversal: return false.
     * 2026-07-21
     */
    public static boolean isJamOptionsHoldKey(boolean isBack, boolean isPrev, boolean isNext,
            boolean isPadPlay, boolean isCenter) {
        if (isCenter) return false;
        return isBack || isPrev || isNext || isPadPlay;
    }

    /**
     * Relative pick weight for a 4-bit pad mask (2:2 heaviest).
     * Layman: balanced splits win the lottery more often.
     * Technical: bitCount==2 → {@link #SHUFFLE_PAIR_WEIGHT}; else skew.
     * Was: always 1 (uniform). Reversal: return 1.
     * 2026-07-21
     */
    public static int shufflePadMaskWeight(int mask) {
        int bits = Integer.bitCount(mask & 0xF);
        return bits == 2 ? SHUFFLE_PAIR_WEIGHT : SHUFFLE_SKEW_WEIGHT;
    }

    /**
     * Mashup jam: no loop / beat-roll / chop — levels + crossfade only.
     * Layman: keep the mashup simple; leave Gen1 stutter for single-track Stem.
     * Was: mashup shared beginStemHold stutter. Reversal: return false.
     * 2026-07-21
     */
    public static boolean mashupAllowsBeatRoll(boolean multiSong) {
        return !multiSong;
    }

    /**
     * How many pads currently play from songIndex.
     * Layman: count bubbles sitting on that track.
     * 2026-07-21
     */
    public static int padCountForSong(int[] zoneSongs, int songIndex) {
        if (zoneSongs == null) return 0;
        int n = 0;
        for (int i = 0; i < zoneSongs.length; i++) {
            if (zoneSongs[i] == songIndex) n++;
        }
        return n;
    }

    /**
     * True when at least one pad is already on that song (eligible for shuffle-in).
     * Layman: if you never flipped a pad to song B, B is not in the mix yet.
     * 2026-07-21
     */
    public static boolean songHasPad(int[] zoneSongs, int songIndex) {
        return padCountForSong(zoneSongs, songIndex) > 0;
    }

    /**
     * Randomise which song feeds each pad — always ≥1 pad per live song (never sticks on 4:0).
     * Layman: OK always remixes both tracks; one song never eats every bubble forever.
     * Technical: forceBoth=true so 4:0/0:4 are illegal and a prior all-A map can escape.
     * Was: mid-jam allowed 4:0 then songHasPad(B)==false blocked further shuffles.
     * Reversal: pickPadSongMask(..., false) + songHasPad gate.
     * @param zoneSongsInOut length ≥4; rewritten on success
     * @return true when the map changed
     * 2026-07-21
     */
    public static boolean pickShufflePadSongs(int[] zoneSongsInOut, int songCount, Random rng) {
        if (zoneSongsInOut == null || zoneSongsInOut.length < 4 || songCount < 2) return false;
        if (rng == null) rng = new Random();
        // Always keep both live songs audible — escapes stuck 4:0. 2026-07-21
        return pickPadSongMask(zoneSongsInOut, rng, true);
    }

    /**
     * Cold-start mashup shuffle — may invent pads for B; keeps ≥1 pad per live track.
     * Layman: first jam spin mixes both tracks in (any uneven split OK — not forced 2:2).
     * Was: pickShufflePadSongs (blocked when B had 0 pads) + 2:2 bias. Reversal: that call.
     * 2026-07-21
     */
    public static boolean pickInitialMashupPadSongs(int[] zoneSongsInOut, int songCount, Random rng) {
        if (zoneSongsInOut == null || zoneSongsInOut.length < 4 || songCount < 2) return false;
        if (rng == null) rng = new Random();
        return pickPadSongMask(zoneSongsInOut, rng, true);
    }

    /**
     * How many pad↔song masks a shuffle may pick from (unweighted count).
     * Layman: count legal mixes — both mid-jam and cold-start keep both songs audible.
     * forceBoth → 14 (exclude all-A / all-B); else → 16 (legacy mid-jam 4:0 pool).
     * 2026-07-21
     */
    public static int shufflePadMaskPoolSize(boolean forceBothSongs) {
        int n = 0;
        for (int mask = 0; mask < 16; mask++) {
            if (shufflePadMaskEligible(mask, forceBothSongs)) n++;
        }
        return n;
    }

    /**
     * True when physical hold is long enough for jam Options (not a focus tap).
     * Layman: a quick click stays a click even if the menu timer already queued.
     * Was: any fired timer counted as hold. Reversal: return holdFired only.
     * 2026-07-21
     */
    public static boolean isIntentionalPadOptionsHold(boolean holdFired, long physicalHoldMs) {
        if (!holdFired) return false;
        // Some Y1 KeyEvent paths omit usable event times. The fired hold timer is the
        // authoritative fallback; otherwise a real hold is dismissed as a quick tap.
        if (physicalHoldMs <= 0L) return true;
        return physicalHoldMs >= mashupOptionsHoldMs();
    }

    /**
     * Timer opened Options but the finger was a short tap — undo menu, treat as focus.
     * Layman: Options popped by accident on a quick press — close it and light the pad.
     * 2026-07-21
     */
    public static boolean shouldUndoSpuriousPadOptions(boolean holdFired, boolean menuShowing,
            long physicalHoldMs) {
        return holdFired && menuShowing && physicalHoldMs > 0L
                && physicalHoldMs < mashupOptionsHoldMs();
    }

    /**
     * Physical hold length from KeyEvent times (fail-open 0).
     * Layman: how long the button was really down, not how busy the UI thread was.
     * 2026-07-21
     */
    public static long physicalKeyHoldMs(long downTimeMs, long eventTimeMs) {
        if (downTimeMs <= 0L || eventTimeMs < downTimeMs) return 0L;
        return eventTimeMs - downTimeMs;
    }

    /**
     * Prefer local uptime hold; fall back to KeyEvent times (MTK-safe).
     * Layman: measure how long the button was really down even if Android’s clock lies.
     * Was: KeyEvent-only (often 0 on MT6572 → Options always dismissed). Reversal: event-only.
     * 2026-07-21
     */
    public static long bestPhysicalHoldMs(long localDownUptimeMs, long nowUptimeMs,
            long eventDownTimeMs, long eventTimeMs) {
        // 2026-07-21 — Trust kernel interrupt timestamps first when valid (MTK dual/quad-core safe).
        // If UI thread lagged between DOWN and UP, nowUptimeMs - localDownUptimeMs is artificially inflated.
        long physical = physicalKeyHoldMs(eventDownTimeMs, eventTimeMs);
        if (physical > 0L) {
            return physical;
        }
        if (localDownUptimeMs > 0L && nowUptimeMs >= localDownUptimeMs) {
            return nowUptimeMs - localDownUptimeMs;
        }
        return 0L;
    }

    /**
     * Sum of {@link #shufflePadMaskWeight} over eligible masks (for tests).
     * Layman: total lottery tickets in the shuffle hat.
     * 2026-07-21
     */
    public static int shufflePadMaskWeightTotal(boolean forceBothSongs) {
        int sum = 0;
        for (int mask = 0; mask < 16; mask++) {
            if (!shufflePadMaskEligible(mask, forceBothSongs)) continue;
            sum += shufflePadMaskWeight(mask);
        }
        return sum;
    }

    /**
     * True when this 4-bit pad mask is a legal shuffle outcome.
     * Bit z=1 → song B on that pad; 0 → song A.
     * 2026-07-21
     */
    public static boolean shufflePadMaskEligible(int mask, boolean forceBothSongs) {
        int c0 = 0;
        int c1 = 0;
        for (int z = 0; z < 4; z++) {
            if (((mask >> z) & 1) == 0) c0++;
            else c1++;
        }
        // Cold-start: both live tracks stay audible. Mid-jam: 4:0 / 0:4 allowed. 2026-07-21
        if (forceBothSongs && (c0 < 1 || c1 < 1)) return false;
        return true;
    }

    /**
     * Shared mask picker — weighted random; 2:2 most common, other splits allowed.
     * {@code forceBoth} skips the “B already in mix” gate and excludes 4:0 / 0:4.
     * Was: uniform over eligible; then pair-first pool. Reversal: uniform nextInt(poolN).
     * 2026-07-21
     */
    private static boolean pickPadSongMask(int[] zoneSongsInOut, Random rng, boolean forceBoth) {
        if (!forceBoth && !songHasPad(zoneSongsInOut, 1)) return false;
        int[] before = new int[4];
        for (int i = 0; i < 4; i++) before[i] = zoneSongsInOut[i];
        int[] pool = new int[16];
        int[] weights = new int[16];
        int poolN = 0;
        int weightSum = 0;
        for (int mask = 0; mask < 16; mask++) {
            if (!shufflePadMaskEligible(mask, forceBoth)) continue;
            pool[poolN] = mask;
            int w = shufflePadMaskWeight(mask);
            weights[poolN] = w;
            weightSum += w;
            poolN++;
        }
        if (poolN < 1 || weightSum < 1) return false;
        int curMask = 0;
        for (int z = 0; z < 4; z++) {
            if (before[z] != 0) curMask |= (1 << z);
        }
        int pick = pickWeightedMask(pool, weights, poolN, weightSum, rng);
        if (poolN > 1) {
            int guard = 0;
            while (pick == curMask && guard++ < 8) {
                pick = pickWeightedMask(pool, weights, poolN, weightSum, rng);
            }
        }
        for (int z = 0; z < 4; z++) {
            zoneSongsInOut[z] = ((pick >> z) & 1);
        }
        for (int z = 0; z < 4; z++) {
            if (zoneSongsInOut[z] != before[z]) return true;
        }
        return false;
    }

    /**
     * Draw one mask from a weighted pool (roulette wheel).
     * Layman: spin a wheel where 2:2 wedges are bigger.
     * 2026-07-21
     */
    private static int pickWeightedMask(int[] pool, int[] weights, int poolN, int weightSum,
            Random rng) {
        int ticket = rng.nextInt(weightSum);
        int acc = 0;
        for (int i = 0; i < poolN; i++) {
            acc += weights[i];
            if (ticket < acc) return pool[i];
        }
        return pool[poolN - 1];
    }

    /**
     * Silent pad (~0 gain) should look dimmer on the Stem face.
     * Layman: muted pads look quieter so you can spot them at a glance.
     * Visual only — audio stays gain mute (not stop).
     * Was: silent pads drew at full chrome brightness. Reversal: return false.
     * 2026-07-21
     */
    public static boolean padFaceShouldDim(float padGain) {
        return isGainSilent(padGain);
    }

    /**
     * Alpha multiplier for silent pad chrome (1 = full, ~0.45 = calm dim).
     * Layman: turn the pad’s paint down a bit when it’s muted.
     * 2026-07-21
     */
    public static float padSilentVisualMul(float padGain) {
        return padFaceShouldDim(padGain) ? 0.45f : 1f;
    }

    /**
     * Dark wash alpha (0..255) drawn over a silent Stem pad.
     * Layman: a soft shadow so mute reads clearly without flashy effects.
     * Was: no overlay. Reversal: return 0.
     * 2026-07-21
     */
    public static int padSilentDimOverlayAlpha(float padGain) {
        return padFaceShouldDim(padGain) ? 0x70 : 0;
    }

    /**
     * True when repress of focused pad should crossfade that zone to the other song.
     * Name kept for callers; behaviour is per-zone toggle + host timed fade.
     * 2026-07-19 / 2026-07-20
     */
    public static boolean stemKeyShouldCycleSong(int activeZone, int pressedZone, int songCount) {
        return songCount > 1 && activeZone == pressedZone;
    }

    /**
     * Steady-state bug: both songs audible on the same pad zone.
     * Layman: Vocals must be one track only after the blend finishes.
     * Dual audible is OK only mid-crossfade; after that outgoing must be silent.
     * 2026-07-21
     */
    public static boolean violatesPadZoneSolo(float gainSongA, float gainSongB) {
        return !isGainSilent(gainSongA) && !isGainSilent(gainSongB);
    }

    /**
     * Outgoing pad gain after crossfade completes — always hard mute.
     * Layman: the old track’s stem on this pad goes fully off.
     * 2026-07-21
     */
    public static float padZoneSoloOutgoingGain() {
        return 0f;
    }

    /**
     * Post-crossfade gains for one zone — incoming keeps level, outgoing = 0.
     * Layman: after the blend, only the new song’s pad is heard.
     * Technical: returns [outgoingGain, incomingGain] for assert / host finalize.
     * 2026-07-21
     */
    public static float[] padZoneSoloFinalGains(float incomingTargetGain) {
        return new float[] { padZoneSoloOutgoingGain(), clampGain(incomingTargetGain) };
    }

    /**
     * True when pad is silent or still at dual-start floor (~1%).
     * Layman: dial is off or barely on — safe to auto-nudge on a track flip.
     * 2026-07-21
     */
    public static boolean padGainAtOrBelowStartFloor(float padGain) {
        return clampGain(padGain) <= PAD_SWITCH_FLOOR;
    }

    /**
     * Pad level after a track-switch or shuffle — bump silent/1% pads to ~10%.
     * Layman: quiet pad gets a gentle lift so you hear the new stem; loud pads stay put.
     * Technical: ≤ {@link #PAD_SWITCH_FLOOR} → {@link #PAD_SWITCH_AUDIBLE_GAIN}; else unchanged.
     * Applies to track switches (`startZoneCrossfade`) and centre shuffle (`shuffleMashupPads`).
     * 2026-07-21
     */
    public static float padGainAfterTrackSwitch(float currentPadGain) {
        float g = clampGain(currentPadGain);
        if (padGainAtOrBelowStartFloor(g)) return PAD_SWITCH_AUDIBLE_GAIN;
        return g;
    }

    /**
     * Jam quick bar: hide Wi‑Fi / Bluetooth while StemOrMixSession is active.
     * Layman: don’t open radio menus mid-jam (stalls the pads).
     * Was: always show Wi‑Fi/BT chips. Reversal: return true always.
     * 2026-07-21
     */
    public static boolean jamQuickBarShowsConnectivity(boolean stemOrMixActive) {
        return !stemOrMixActive;
    }

    /**
     * Other mashup song index (0↔1 when two tracks).
     * Layman: flip to the only other song.
     * 2026-07-20
     */
    public static int otherSongIndex(int songIndex, int songCount) {
        if (songCount < 2) return clampSongIndex(songIndex, songCount);
        return songIndex == 0 ? 1 : 0;
    }

    /** Clamp song index into 0..songCount-1 (0 when empty). 2026-07-20 */
    public static int clampSongIndex(int songIndex, int songCount) {
        if (songCount < 1) return 0;
        if (songIndex < 0) return 0;
        if (songIndex >= songCount) return songCount - 1;
        return songIndex;
    }

    /**
     * Resolve TRANSITION preset → fade duration ms.
     * Layman: pick how long the blend lasts.
     * 2026-07-20
     */
    public static long transitionMsForPreset(int preset) {
        if (preset == TRANSITION_PRESET_INSTANT) return TRANSITION_INSTANT_MS;
        if (preset == TRANSITION_PRESET_OVERLAP) return TRANSITION_OVERLAP_MS;
        if (preset == TRANSITION_PRESET_WAVE) return TRANSITION_WAVE_MS;
        return TRANSITION_LONG_MS;
    }

    /**
     * How many fade ticks for a duration (at least 1).
     * Technical: duration / TRANSITION_TICK_MS.
     * 2026-07-20
     */
    public static int transitionFadeSteps(long durationMs) {
        if (durationMs <= 0L) return 1;
        int n = (int) (durationMs / TRANSITION_TICK_MS);
        if (n < 1) n = 1;
        if (n > 400) n = 400; // ceiling — ~16s at 40ms
        return n;
    }

    /**
     * Strip folder + extension for face / status titles.
     * Layman: show the song name, not the file path.
     * 2026-07-20
     */
    public static String stripTrackDisplayName(String raw) {
        if (raw == null) return "";
        String n = raw;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    /**
     * First letter for mashup pad placeholder — prefer A–Z from ID3 title.
     * Layman: skip leading track numbers in filenames so “1-01 Lost” → L, not 1.
     * Was: first char including digits. Reversal: charAt(0) digit path.
     * 2026-07-20
     */
    public static char placeholderLetter(String title) {
        if (title == null || title.length() == 0) return '#';
        for (int i = 0; i < title.length(); i++) {
            char c = Character.toUpperCase(title.charAt(i));
            if (c >= 'A' && c <= 'Z') return c;
        }
        // No letters — last resort digit / hash. 2026-07-20
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c >= '0' && c <= '9') return c;
        }
        return '#';
    }

    /**
     * Same-album mashup pads need a letter overlay when covers match.
     * Layman: two songs from one album share a picture — tint + letter keeps them apart.
     * 2026-07-20
     */
    public static boolean sameAlbumKey(String album0, String artist0, String album1, String artist1) {
        String a0 = album0 != null ? album0.trim() : "";
        String a1 = album1 != null ? album1.trim() : "";
        if (a0.length() == 0 || a1.length() == 0) return false;
        if (!a0.equalsIgnoreCase(a1)) return false;
        String r0 = artist0 != null ? artist0.trim() : "";
        String r1 = artist1 != null ? artist1.trim() : "";
        if (r0.length() == 0 || r1.length() == 0) return true;
        return r0.equalsIgnoreCase(r1);
    }

    /**
     * Mute / near-mute pads need a temporary gain so beat roll is audible.
     * Was: needsTempChopGain. Reversal: rename back.
     * 2026-07-20
     */
    public static boolean needsTempRollGain(float gain) {
        return clampGain(gain) < TEMP_GAIN_THRESHOLD;
    }

    /** @deprecated Prefer {@link #needsTempRollGain}. 2026-07-20 */
    public static boolean needsTempChopGain(float gain) {
        return needsTempRollGain(gain);
    }

    public static float fadeGainStep(float from, float to, int stepIndex, int totalSteps) {
        if (totalSteps < 1) return to;
        if (stepIndex >= totalSteps) return to;
        float t = stepIndex / (float) totalSteps;
        return from + (to - from) * t;
    }
}
