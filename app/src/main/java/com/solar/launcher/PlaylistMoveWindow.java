package com.solar.launcher;

/** Fixed 5-slot ribbon for library playlist reorder — mover slot varies by position. */
final class PlaylistMoveWindow {
    static final int VISIBLE_ROWS = 5;
    static final int SLOT_0 = 0;
    static final int SLOT_1 = 1;
    static final int SLOT_2 = 2;
    static final int SLOT_3 = 3;
    static final int SLOT_4 = 4;
    static final int LAST_SLOT = VISIBLE_ROWS - 1;

    private PlaylistMoveWindow() {}

    /** Which ribbon slot holds the moving track (0..4). */
    static int moveSlot(int moveIdx, int count) {
        if (count <= 0 || moveIdx < 0 || moveIdx >= count) return SLOT_0;
        if (count < VISIBLE_ROWS) return Math.min(moveIdx, count - 1);
        if (moveIdx == 0) return SLOT_0;
        if (moveIdx == 1) return SLOT_1;
        if (moveIdx == count - 1) return SLOT_4;
        if (moveIdx == count - 2) return SLOT_3;
        if (moveIdx == count - 3) return SLOT_2;
        return SLOT_2;
    }

    /** Data index bound to a ribbon slot, or -1 when empty. */
    static int slotDataIndex(int moveIdx, int slot, int count) {
        if (count <= 0 || moveIdx < 0 || moveIdx >= count) return -1;
        if (slot < SLOT_0 || slot >= VISIBLE_ROWS) return -1;
        int idx = moveIdx + (slot - moveSlot(moveIdx, count));
        if (idx < 0 || idx >= count) return -1;
        return idx;
    }

    static int viewportHeightPx(int slotH) {
        if (slotH <= 0) return 0;
        return VISIBLE_ROWS * slotH;
    }

    /**
     * Bottom padding under the 5-row viewport — short lists and bottom-anchored mover only.
     * Empty space sits beneath the highlighted row so hierarchy stays readable.
     */
    static int bottomPaddingPx(int moveIdx, int count, int slotH) {
        if (slotH <= 0 || count <= 0) return 0;
        int viewportH = viewportHeightPx(slotH);
        if (count < VISIBLE_ROWS) {
            int contentH = count * slotH;
            int rowsBelowMover = (count - 1) - moveIdx;
            int padFromContent = viewportH - contentH;
            if (padFromContent <= 0) return 0;
            return Math.max(0, padFromContent - rowsBelowMover * slotH);
        }
        if (moveIdx == count - 1) {
            return slotH;
        }
        return 0;
    }

    /** Slot delta from browse position to mover slot for enter/cancel animations. */
    static int enterTranslationSlots(int moveIdx, int count, int browseSlot) {
        return browseSlot - moveSlot(moveIdx, count);
    }
}
