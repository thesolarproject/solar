package com.solar.launcher;

/**
 * 2026-07-21 — What chrome to paint on one dual-line / subtitle ListView wheel detent.
 * Layman: while you spin, the highlight still tracks the dial on screen; focus and covers wait for a pause.
 * Technical: ensureVisible always; requestFocus + preview only when idle (unlike {@link MenuWheelChromePolicy}).
 * Was: every flush did requestFocus → onFocusChange → full bindLibraryMoveRow (dual-line tax).
 * Reversal: plan(spinning) with requestFocus=true mid-spin; or delete this class and always focus.
 */
public final class ListWheelChromePolicy {

    /** Which follow-up paints run for this detent. 2026-07-21 */
    public static final class PaintPlan {
        /**
         * Whether paint paths may call ensure-visible / setSelection pin.
         * Always true for long lists — multi-step flush must not leave selection off-screen.
         */
        public final boolean ensureVisible;
        /** Mid-spin: false — skip requestFocus / focusAfterSelect post. Idle: true. */
        public final boolean requestFocus;
        /** Mid-spin: false — defer dual-pane art. Idle: true. */
        public final boolean preview;

        public PaintPlan(boolean ensureVisible, boolean requestFocus, boolean preview) {
            this.ensureVisible = ensureVisible;
            this.requestFocus = requestFocus;
            this.preview = preview;
        }
    }

    private ListWheelChromePolicy() {}

    /**
     * 2026-07-21 — Spin: pin viewport, skip focus/preview. Idle: full chrome.
     * Layman: dial moves the blue bar now; title polish and covers catch up when you stop.
     */
    public static PaintPlan plan(boolean spinning) {
        if (spinning) {
            return new PaintPlan(true, false, false);
        }
        return new PaintPlan(true, true, true);
    }

    /**
     * 2026-07-21 — Dual-line row paint: selected OR focused counts as highlighted.
     * Layman: the bar can light up from ListView selection while Android focus catches up later.
     * Technical: mid-spin skips requestFocus; bind must not key only on hasFocus.
     * Reversal: return focused only.
     */
    public static boolean rowHighlighted(boolean listSelected, boolean focused) {
        return listSelected || focused;
    }
}
