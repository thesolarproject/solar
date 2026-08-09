package com.solar.launcher.stem;

/**
 * Slot / session context row labels for Stem/Mix jam holds.
 * Layman: hold Prev/Next → options for that track; Play → jam menu; Pause / Home leave.
 * Was: parallel transitionPanel modal. Reversal: showContextRows in StemPlayerHost.
 * Technical: labels only — MainActivity wires ThemedContextMenu actions.
 * 2026-07-21 — Pause + Home rows (Exit was Home chip only).
 */
public final class StemMixContextRows {
    /** Hold Prev/Next slot — Replace / Queue / Start next / Play both / Scrub. 2026-07-21 / 2026-08-01 */
    public static final int SLOT_REPLACE = 0;
    public static final int SLOT_PLAY_QUEUE = 1;
    public static final int SLOT_START_NEXT = 2;
    /** Play-both stacking — this pad feeds its stem from both songs. 2026-08-01 */
    public static final int SLOT_PLAY_BOTH = 3;
    public static final int SLOT_SCRUB = 4;
    /** StemFM pacing modes — Full Tracks / Balanced Mix / Short & Punchy / Instant. 2026-08-01 */
    public static final int SLOT_TRANSITION_FULL = 5;
    public static final int SLOT_TRANSITION_BALANCED = 6;
    public static final int SLOT_TRANSITION_SHORT = 7;
    public static final int SLOT_TRANSITION_INSTANT = 8;
    public static final int SLOT_ROW_COUNT = 9;

    /**
     * Session / Play context — queue, Pause, TRANSITION, Home exit.
     * Was: queue + TRANSITION only (Exit via Home chip). Reversal: drop PAUSE/HOME indices.
     * 2026-07-21
     */
    public static final int SESSION_PLAY_QUEUE = 0;
    public static final int SESSION_PAUSE = 1;
    /** StemFM pacing modes — Full Tracks / Balanced Mix / Short & Punchy / Instant. 2026-08-01 */
    public static final int SESSION_TRANSITION_FULL = 2;
    public static final int SESSION_TRANSITION_BALANCED = 3;
    public static final int SESSION_TRANSITION_SHORT = 4;
    public static final int SESSION_HOME = 5;
    public static final int SESSION_TRANSITION_INSTANT = 6;
    /** StemFM Quantize Performance — pad crossfades snap to beat/bar. 2026-08-01 */
    public static final int SESSION_QUANTIZE_BEAT = 7;
    public static final int SESSION_QUANTIZE_HALF_BAR = 8;
    public static final int SESSION_QUANTIZE_BAR = 9;
    public static final int SESSION_QUANTIZE_OFF = 10;
    public static final int SESSION_ROW_COUNT = 11;

    private StemMixContextRows() {}

    /**
     * Track context rows for hold pad → song Options.
     * Layman: swap the track on this pad, manage queue, jump next, or soft-scrub.
     * Was: Replace Track N only. Reversal: slotRows with "Replace Track " + n.
     * 2026-07-21
     */
    public static String[] slotRows(int trackOneBased) {
        return new String[] {
                "Replace focused track",
                "Play queue",
                "Start next track",
                "Play both",
                "Scrub",
                "TRANSITION · FULL TRACKS (~4s)",
                "TRANSITION · BALANCED (~2s)",
                "TRANSITION · SHORT & PUNCHY (~0.4s)",
                "TRANSITION · INSTANT"
        };
    }

    /**
     * Session / Play context — Pause interrupt + Home leave + TRANSITION.
     * Layman: stop the jam, go home, or pick blend length.
     * Was: no Pause/Home rows. Reversal: sessionRows without those two strings.
     * 2026-07-21
     */
    public static String[] sessionRows(boolean mixMode) {
        return new String[] {
                "Play queue",
                "Pause",
                "TRANSITION · FULL TRACKS (~4s)",
                "TRANSITION · BALANCED (~2s)",
                "TRANSITION · SHORT & PUNCHY (~0.4s)",
                "Home",
                "TRANSITION · INSTANT",
                "QUANTIZE · BEAT",
                "QUANTIZE · HALF BAR",
                "QUANTIZE · BAR",
                "QUANTIZE · OFF"
        };
    }

    public static int transitionPresetForSlotRow(int row) {
        if (row == SLOT_TRANSITION_FULL) return StemControls.TRANSITION_PRESET_FULL;
        if (row == SLOT_TRANSITION_BALANCED) return StemControls.TRANSITION_PRESET_BALANCED;
        if (row == SLOT_TRANSITION_SHORT) return StemControls.TRANSITION_PRESET_SHORT;
        if (row == SLOT_TRANSITION_INSTANT) return StemControls.TRANSITION_PRESET_INSTANT;
        return -1;
    }

    /** True when a slot row toggles play-both stacking for that pad. 2026-08-01 */
    public static boolean isSlotPlayBothRow(int row) {
        return row == SLOT_PLAY_BOTH;
    }

    /** Map session row → transition preset (−1 = not a preset). 2026-07-21 */
    public static int transitionPresetForSessionRow(int row) {
        if (row == SESSION_TRANSITION_FULL) return StemControls.TRANSITION_PRESET_FULL;
        if (row == SESSION_TRANSITION_BALANCED) return StemControls.TRANSITION_PRESET_BALANCED;
        if (row == SESSION_TRANSITION_SHORT) return StemControls.TRANSITION_PRESET_SHORT;
        if (row == SESSION_TRANSITION_INSTANT) return StemControls.TRANSITION_PRESET_INSTANT;
        return -1;
    }

    /** True when session row pauses the jam (all decks/songs). 2026-07-21 */
    public static boolean isSessionPauseRow(int row) {
        return row == SESSION_PAUSE;
    }

    /** Map a session row to a StemQuantizePolicy mode (−1 = not a quantize row). 2026-08-01 */
    public static int quantizeModeForSessionRow(int row) {
        if (row == SESSION_QUANTIZE_BEAT) return com.solar.launcher.stem.analysis.StemQuantizePolicy.BEAT;
        if (row == SESSION_QUANTIZE_HALF_BAR) return com.solar.launcher.stem.analysis.StemQuantizePolicy.HALF_BAR;
        if (row == SESSION_QUANTIZE_BAR) return com.solar.launcher.stem.analysis.StemQuantizePolicy.BAR;
        if (row == SESSION_QUANTIZE_OFF) return com.solar.launcher.stem.analysis.StemQuantizePolicy.OFF;
        return -1;
    }

    /** True when session row exits to Solar Home. 2026-07-21 */
    public static boolean isSessionHomeRow(int row) {
        return row == SESSION_HOME;
    }
}
