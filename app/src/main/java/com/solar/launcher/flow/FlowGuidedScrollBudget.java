package com.solar.launcher.flow;

/**
 * NP-back guided carousel scroll — fit distance into a max duration by widening step size.
 */
public final class FlowGuidedScrollBudget {

    public static final long MAX_MS = 2000L;
    /** NP-back zip path uses {@link #handoffStepDelta} — one wheel anim at the end. */
    public static final long HANDOFF_SCROLL_MS = 360L;
    /** Measured Rockbox step on Y1 — budget uses this to decide jump vs wheel scroll. */
    public static final long MS_PER_WHEEL_STEP = 380L;

    private FlowGuidedScrollBudget() {}

    /**
     * @param remaining albums still between focus and target
     * @param budgetLeftMs time left in the 2s cap
     * @return albums to advance this tick (1 = normal scroll anim, &gt;1 = instant index jump)
     */
    public static int stepDelta(int remaining, long budgetLeftMs) {
        if (remaining <= 0) return 0;
        // Last albums always wheel-scroll — smooth handoff into forward NP morph.
        if (remaining <= 2) return 1;
        if (budgetLeftMs <= 0) return remaining;
        int animStepsFit = (int) Math.max(1, budgetLeftMs / MS_PER_WHEEL_STEP);
        if (remaining <= animStepsFit) return 1;
        return Math.max(1, (remaining + animStepsFit - 1) / animStepsFit);
    }

    /**
     * NP-back handoff zip — jump to neighbor, one animated wheel step into morph.
     * @return albums to advance this tick (1 = scroll anim, &gt;1 = instant index jump)
     */
    public static int handoffStepDelta(int remaining) {
        if (remaining <= 0) return 0;
        if (remaining == 1) return 1;
        return remaining - 1;
    }
}
