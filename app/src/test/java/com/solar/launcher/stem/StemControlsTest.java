package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Stem Player control math — gain + Gen1 bar ladder + none rung. 2026-07-19 */
public class StemControlsTest {

    @Test
    public void gainFullScaleWithinOneWheelTurn() {
        float g = 0f;
        for (int i = 0; i < StemControls.GAIN_CLICKS_FULL; i++) {
            g = StemControls.nudgeGain(g, 1);
        }
        assertEquals(1f, g, 0.001f);
        assertTrue(StemControls.GAIN_CLICKS_FULL <= 16);
    }

    @Test
    public void gainNudgeDownClamps() {
        assertEquals(0f, StemControls.nudgeGain(0f, -3), 0.001f);
        assertEquals(1f, StemControls.nudgeGain(1f, 5), 0.001f);
    }

    @Test
    public void loopBarLadderIncludesNoneThenGen1Steps() {
        assertTrue(StemControls.isLoopBarsNone(StemControls.LOOP_BARS[0]));
        assertEquals(0.25f, StemControls.LOOP_BARS[1], 0.001f);
        assertEquals(8f, StemControls.LOOP_BARS[StemControls.LOOP_BARS.length - 1], 0.001f);
        float b = StemControls.LOOP_BARS_NONE;
        for (int i = 0; i < 6; i++) {
            b = StemControls.nudgeLoopBars(b, 1);
        }
        assertEquals(8f, b, 0.001f);
    }

    @Test
    public void loopIndexFindsClosestAndNone() {
        assertEquals(0, StemControls.loopIndexForBars(0f));
        assertEquals(3, StemControls.loopIndexForBars(1f));
        assertEquals(1, StemControls.loopIndexForBars(0.2f));
    }

    @Test
    public void dotsForGainAndLoop() {
        assertEquals(0, StemControls.dotsForGain(0f, 8));
        assertEquals(8, StemControls.dotsForGain(1f, 8));
        assertEquals(0, StemControls.dotsForLoopBars(StemControls.LOOP_BARS_NONE, 8));
        assertTrue(StemControls.dotsForLoopBars(1f, 8) >= 1);
        assertEquals(8, StemControls.dotsForLoopBars(8f, 8));
    }

    @Test
    public void sessionInactiveByDefault() {
        assertFalse(StemPlayerHost.isSessionActive());
    }

    @Test
    public void wheelPolarityCwLouderShorterLoop() {
        assertEquals(1, StemControls.volumeStepsFromWheel(-1));
        assertEquals(-1, StemControls.volumeStepsFromWheel(1));
        assertEquals(0.5f, StemControls.nudgeLoopBars(1f, StemControls.loopStepsFromWheel(-1)), 0.001f);
        assertEquals(2f, StemControls.nudgeLoopBars(1f, StemControls.loopStepsFromWheel(1)), 0.001f);
    }

    @Test
    public void focusAlwaysVolumeCenterTogglesEdit() {
        assertFalse(StemControls.wheelLoopModeForStem(true, true));
        assertTrue(StemControls.centerShouldLeaveLoopEdit(true));
        assertFalse(StemControls.centerShouldLeaveLoopEdit(false));
        assertTrue(StemControls.wheelUsesVolume(false));
        assertFalse(StemControls.wheelUsesVolume(true));
        assertTrue(StemControls.faceShowsLoopBars(true));
        // Center / beat-roll user entry disarmed (mute hygiene). 2026-07-21
        assertFalse(StemControls.centerEntersLoopEdit());
        assertFalse(StemControls.userMayArmBeatRoll(false));
        assertFalse(StemControls.userMayArmBeatRoll(true));
    }

    @Test
    public void tempRollGainAndFade() {
        assertTrue(StemControls.needsTempRollGain(0f));
        assertFalse(StemControls.needsTempRollGain(0.5f));
        assertEquals(0.375f, StemControls.fadeGainStep(0.75f, 0f, 4, 8), 0.001f);
        assertEquals(350L, StemControls.STEM_STUTTER_HOLD_MS);
    }

    @Test
    public void stemKeyCycleOnlyWhenAlreadyFocusedMulti() {
        assertFalse(StemControls.stemKeyShouldCycleSong(-1, 0, 2));
        assertTrue(StemControls.stemKeyShouldCycleSong(1, 1, 2));
        assertFalse(StemControls.stemKeyShouldCycleSong(1, 1, 1));
    }

    /** TRANSITION hold is one side; dual-hold exit is single-track only. 2026-07-20 */
    @Test
    public void transitionHoldVsDualExit() {
        assertTrue(StemControls.stemTransitionHoldOneSide(true, false));
        assertFalse(StemControls.stemExitBothSidesHeld(true, false));
        assertTrue(StemControls.stemExitBothSidesHeld(true, true));
        assertEquals(4000L, StemControls.TRANSITION_LONG_MS);
        assertEquals(8000L, StemControls.TRANSITION_OVERLAP_MS);
        assertEquals(400L, StemControls.TRANSITION_WAVE_MS);
    }

    /** Zero dial = hard mute; tiny residual still silent. 2026-07-19 */
    @Test
    public void gainSilentAtZeroAndBelowEps() {
        assertTrue(StemControls.isGainSilent(0f));
        assertTrue(StemControls.isGainSilent(StemControls.SILENT_GAIN));
        assertFalse(StemControls.isGainSilent(StemControls.GAIN_STEP));
        assertFalse(StemControls.isGainSilent(1f));
    }

    /** Letter skips leading digits in filenames; prefers A–Z from ID3 titles. 2026-07-20 */
    @Test
    public void placeholderLetterSkipsLeadingDigits() {
        assertEquals('L', StemControls.placeholderLetter("1-01 Lost & Found"));
        assertEquals('L', StemControls.placeholderLetter("Lost & Found"));
        assertEquals('D', StemControls.placeholderLetter("donda"));
        assertEquals('#', StemControls.placeholderLetter(""));
        assertTrue(StemControls.sameAlbumKey("Donda", "Ye", "Donda", "Ye"));
        assertFalse(StemControls.sameAlbumKey("Donda", "Ye", "Late", "Ye"));
    }

    /** Hold OK scrub policy — short OK still shuffle; hold arms; confirm explicit. 2026-07-21 */
    @Test
    public void centerHoldPadScrubPolicy() {
        assertTrue(StemControls.centerHoldArmsPadScrub(true, 0));
        assertFalse(StemControls.centerHoldArmsPadScrub(true, -1));
        assertFalse(StemControls.centerHoldArmsPadScrub(false, 1));
        assertEquals(StemControls.STEM_OPTIONS_HOLD_MS, StemControls.mashupCenterScrubHoldMs());
        assertTrue(StemControls.centerReleaseKeepsFaceScrub(true));
        assertFalse(StemControls.centerReleaseKeepsFaceScrub(false));
        assertTrue(StemControls.centerTapCommitsFaceScrub(true, false));
        assertFalse(StemControls.centerTapCommitsFaceScrub(true, true));
        assertFalse(StemControls.centerTapCommitsFaceScrub(false, false));
    }

    /** Hold Open/Dismiss jam Options — Center never counts. 2026-07-21 */
    @Test
    public void jamOptionsHoldKeysExcludeCenter() {
        assertTrue(StemControls.isJamOptionsHoldKey(true, false, false, false, false));
        assertTrue(StemControls.isJamOptionsHoldKey(false, true, false, false, false));
        assertTrue(StemControls.isJamOptionsHoldKey(false, false, true, false, false));
        assertTrue(StemControls.isJamOptionsHoldKey(false, false, false, true, false));
        assertFalse(StemControls.isJamOptionsHoldKey(false, false, false, false, true));
        assertFalse(StemControls.isJamOptionsHoldKey(true, false, false, false, true));
    }

    /** Short Play never opens Options; hold Play does on mashup. 2026-07-21 */
    @Test
    public void mashupPlayShortVsHoldPolicy() {
        assertFalse(StemControls.mashupPlayOpensContext(true));
        assertFalse(StemControls.mashupPlayOpensContext(false));
        assertFalse(StemControls.shortTapOpensJamContext());
        assertTrue(StemControls.mashupPlayHoldOpensContext(true));
        assertFalse(StemControls.mashupPlayHoldOpensContext(false));
        assertTrue(StemControls.mashupPadHoldOpensSlotContext(true));
        assertFalse(StemControls.mashupPadHoldOpensSlotContext(false));
        assertEquals(520L, StemControls.mashupOptionsHoldMs());
        assertTrue(StemControls.mashupOptionsHoldMs() >= StemControls.STEM_TRANSITION_HOLD_MS);
        assertFalse(StemControls.isIntentionalPadOptionsHold(true, 200L));
        assertTrue(StemControls.isIntentionalPadOptionsHold(true, 520L));
        assertTrue(StemControls.shouldUndoSpuriousPadOptions(true, true, 180L));
        assertFalse(StemControls.shouldUndoSpuriousPadOptions(true, true, 600L));
        assertEquals(100L, StemControls.physicalKeyHoldMs(1000L, 1100L));
    }

    /** Pad hold keys map to Vocals/Drums/Bass/Melody zones. 2026-07-21 */
    @Test
    public void padZoneForOptionsHoldKeys() {
        assertEquals(0, StemControls.padZoneForOptionsHoldKey(true, false, false, false));
        assertEquals(1, StemControls.padZoneForOptionsHoldKey(false, true, false, false));
        assertEquals(2, StemControls.padZoneForOptionsHoldKey(false, false, true, false));
        assertEquals(3, StemControls.padZoneForOptionsHoldKey(false, false, false, true));
        assertEquals(-1, StemControls.padZoneForOptionsHoldKey(false, false, false, false));
    }

    /** Replace browse: OK = focused; Prev/Next = other song shortcut. 2026-07-21 */
    @Test
    public void stemReplaceTargetSongOkVsShortcut() {
        assertEquals(0, StemControls.stemReplaceTargetSong(0, 2, true, false));
        assertEquals(1, StemControls.stemReplaceTargetSong(0, 2, false, true));
        assertEquals(0, StemControls.stemReplaceTargetSong(1, 2, false, true));
        assertEquals(1, StemControls.stemReplaceTargetSong(1, 2, true, false));
        assertEquals(0, StemControls.stemReplaceTargetSong(0, 1, true, false));
    }

    /** Idle pad shrink math + wake-only Center when no focus. 2026-07-21 */
    @Test
    public void padIdleDefocusAfterTwoSeconds() {
        assertEquals(2000L, StemControls.PAD_IDLE_DEFOCUS_MS);
        assertFalse(StemControls.padIdleShouldDefocus(0L, 5000L));
        assertFalse(StemControls.padIdleShouldDefocus(1000L, 2999L));
        assertTrue(StemControls.padIdleShouldDefocus(1000L, 3000L));
        assertEquals(1f, StemControls.padIdleDrawScale(false), 0.001f);
        assertTrue(StemControls.padIdleDrawScale(true) < 1f);
        // First pad press after clearActiveZone is focus-only (no cycle). 2026-07-21
        assertFalse(StemControls.stemKeyShouldCycleSong(-1, 2, 2));
        assertTrue(StemControls.stemKeyShouldCycleSong(2, 2, 2));
        assertFalse(StemControls.centerTapWhilePadIdleIsWakeOnly(true, 0));
        assertFalse(StemControls.centerTapWhilePadIdleIsWakeOnly(false, -1));
        assertFalse(StemControls.centerTapWhilePadIdleIsWakeOnly(false, 1));
    }
}
