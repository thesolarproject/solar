package com.solar.launcher;

/**
 * 2026-07-20 — Row highlight chrome for index-driven wheel menus (home gold path).
 * Layman: the blue selection bar follows the chosen row with the arrow — even while focus catches up later.
 * Technical: StateListDrawable keys on {@code state_selected} (+ pressed); callers {@code setSelected}.
 * Was: {@code state_focused} only — home mid-spin moved arrow via visibility, decoration stuck on old focus.
 * Reversal: add state_focused again; requestFocus every detent (loses mid-spin paint split).
 */
public final class Y1RowChromePolicy {

    private Y1RowChromePolicy() {}

    /**
     * 2026-07-20 — Attr arrays for {@code StateListDrawable.addState} (selected decoration).
     * Layman: selected or finger-down shows the highlight art; plain focus does not.
     */
    public static int[][] selectedChromeStates() {
        return new int[][] {
                new int[] { android.R.attr.state_selected },
                new int[] { android.R.attr.state_pressed },
        };
    }

    /**
     * 2026-10-03 — Library ListView rows: selected, pressed, or focused.
     * Layman: in Artists/Albums lists the blue bar must also follow focus.
     * Technical: with itemsCanFocus, ListView.layoutChildren gives the selected child focus and
     * then calls setSelected(false) on it, so selected-only chrome never painted (no bar at all).
     * Home/settings keep {@link #selectedChromeStates()} (no stale-focus double bar mid-spin).
     */
    public static int[][] listRowChromeStates() {
        return new int[][] {
                new int[] { android.R.attr.state_selected },
                new int[] { android.R.attr.state_pressed },
                new int[] { android.R.attr.state_focused },
        };
    }

    /**
     * 2026-07-20 — Whether View flags should show selected-row decoration.
     * Layman: blue bar on when the row is marked selected (or pressed).
     */
    public static boolean showsSelectedChrome(boolean selected, boolean pressed) {
        return selected || pressed;
    }

    /**
     * 2026-07-20 — Focus alone must not own decoration (home defers requestFocus while spinning).
     * Layman: turning the dial paints the bar now; real Android focus can wait for a pause.
     */
    public static boolean focusOwnsDecoration() {
        return false;
    }
}
