package com.solar.launcher;

/**
 * Pure priority rules for the center/OK hold release path.
 *
 * A completed move is terminal for that physical press: releasing OK must not
 * activate the row, open a context menu, or put the player to sleep.
 */
public final class CenterHoldPolicy {
    public static final int RELEASE_ACTIVATE = 0;
    public static final int RELEASE_CONTEXT = 1;
    public static final int RELEASE_SLEEP = 2;
    public static final int RELEASE_CONSUME_MOVE = 3;

    private CenterHoldPolicy() {}

    public static int releaseAction(boolean moveHandled, boolean contextOpens,
            boolean contextHandled, boolean shouldSleep, long heldMs, long contextHoldMs) {
        if (moveHandled) return RELEASE_CONSUME_MOVE;
        if (contextOpens && (contextHandled || heldMs >= contextHoldMs)) {
            return RELEASE_CONTEXT;
        }
        if (shouldSleep) return RELEASE_SLEEP;
        return RELEASE_ACTIVATE;
    }
}
