package com.solar.launcher;

import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * ponytail: 5-slot move ribbon for library playlist reorder — O(1) views per wheel step,
 * variable mover slot + bottom padding; ticker animation model matches queue move ribbon.
 */
final class LibraryMoveRibbon {
    private static final int TAG_RIBBON_SLOT = 0x70cc0020;

    static final int ANIM_MS = 130;
    static final int ENTER_MS = 150;
    static final int SLOT_SHIFT_MS = 150;
    static final int CONFIRM_SHOW_MS = 120;
    static final int CONFIRM_HOLD_MS = 450;

    interface Binder {
        void bindRow(FrameLayout row, int dataIndex, int ribbonSlot, boolean empty);

        int itemCount();

        int rowHeightPx();

        int rowWidthPx();
    }

    private final LinearLayout host;
    private final ScrollView scrollParent;
    private final Binder binder;
    private final android.app.Activity activity;
    private int moveFrom = -1;
    private int enterBrowseSlot = PlaylistMoveWindow.SLOT_1;
    private int hostPadTopPx = 0;
    private int lastMoveSlot = -1;
    private boolean ribbonActive;
    private boolean animating;
    private Runnable confirmClearTask;

    LibraryMoveRibbon(LinearLayout host, ScrollView scrollParent, Binder binder,
            android.app.Activity activity) {
        this.host = host;
        this.scrollParent = scrollParent;
        this.binder = binder;
        this.activity = activity;
    }

    int moveFrom() {
        return moveFrom;
    }

    boolean isAnimating() {
        return animating;
    }

    void enter(int pickIndex, int browseSlot, int padTopPx) {
        moveFrom = pickIndex;
        enterBrowseSlot = browseSlot;
        hostPadTopPx = Math.max(0, padTopPx);
        lastMoveSlot = PlaylistMoveWindow.moveSlot(pickIndex, binder.itemCount());
        ribbonActive = false;
        animating = false;
        enterRibbon();
        animateRibbonEnter();
    }

    void teardown() {
        if (confirmClearTask != null && host != null) {
            host.removeCallbacks(confirmClearTask);
            confirmClearTask = null;
        }
        animating = false;
        ribbonActive = false;
        moveFrom = -1;
        lastMoveSlot = -1;
        if (host != null) host.removeAllViews();
    }

    /** Wheel step after backing list order was updated. */
    void onMoveIndexChanged(int newIndex, int wheelDelta) {
        int count = binder.itemCount();
        int newSlot = PlaylistMoveWindow.moveSlot(newIndex, count);
        int prevSlot = lastMoveSlot >= 0 ? lastMoveSlot : PlaylistMoveWindow.moveSlot(moveFrom, count);
        moveFrom = newIndex;
        if (!ribbonActive) {
            enterRibbon();
            populateRibbon();
            lastMoveSlot = newSlot;
            return;
        }
        Runnable bind = new Runnable() {
            @Override public void run() {
                populateRibbon();
                lastMoveSlot = newSlot;
            }
        };
        if (newSlot != prevSlot && !animating) {
            animateSlotShift(prevSlot, newSlot, bind);
        } else if (wheelDelta != 0 && !animating) {
            animateRibbonStrip(wheelDelta, bind);
        } else if (!animating) {
            bind.run();
        }
    }

    void flashConfirm(int index, final Runnable onComplete) {
        if (host == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        View row = findMoverRow();
        if (!(row instanceof FrameLayout)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        FrameLayout fl = (FrameLayout) row;
        int moverSlot = PlaylistMoveWindow.moveSlot(index, binder.itemCount());
        binder.bindRow(fl, index, moverSlot, false);
        android.widget.ImageView confirm = (android.widget.ImageView) fl.findViewWithTag(
                MoveRibbonRows.TAG_CONFIRM);
        if (confirm == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (confirmClearTask != null) {
            host.removeCallbacks(confirmClearTask);
            confirmClearTask = null;
        }
        confirm.animate().cancel();
        confirm.setAlpha(0f);
        confirm.setVisibility(View.VISIBLE);
        confirm.animate().alpha(1f).setDuration(CONFIRM_SHOW_MS).start();
        confirmClearTask = new Runnable() {
            @Override public void run() {
                confirmClearTask = null;
                confirm.animate()
                        .alpha(0f)
                        .setDuration(CONFIRM_SHOW_MS)
                        .setListener(new android.animation.AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(android.animation.Animator animation) {
                                confirm.animate().setListener(null);
                                confirm.setVisibility(View.GONE);
                                confirm.setAlpha(1f);
                                if (onComplete != null) onComplete.run();
                            }
                        })
                        .start();
            }
        };
        host.postDelayed(confirmClearTask, CONFIRM_HOLD_MS);
    }

    /**
     * Timetable-style handoff after confirm checkmark — outer rows ticker away, mover slides
     * to browse slot alignment before the library list crossfades in underneath.
     */
    void animateConfirmHandoff(int placedIndex, int browseSlot, final Runnable onComplete) {
        if (host == null || !ribbonActive) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int count = binder.itemCount();
        int moverSlot = PlaylistMoveWindow.moveSlot(placedIndex, count);
        int slotH = binder.rowHeightPx() + 2;
        float exitTy = (browseSlot - moverSlot) * (float) slotH;
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        int animCount = 0;
        if (scrollParent != null) animCount++;
        final View mover = findMoverRow();
        if (mover != null && Math.abs(exitTy) >= 0.5f) animCount++;
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            if (slot == moverSlot) continue;
            View v = findRibbonSlotRow(slot);
            if (v != null && v.getVisibility() == View.VISIBLE) animCount++;
        }
        if (animCount == 0) {
            animating = false;
            if (onComplete != null) onComplete.run();
            return;
        }
        final int[] remaining = new int[] { animCount };
        final Runnable finish = new Runnable() {
            @Override public void run() {
                remaining[0]--;
                if (remaining[0] <= 0) {
                    animating = false;
                    if (onComplete != null) onComplete.run();
                }
            }
        };
        if (mover != null && Math.abs(exitTy) >= 0.5f) {
            mover.animate().cancel();
            mover.animate()
                    .translationY(exitTy)
                    .setDuration(ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            mover.animate().setListener(null);
                            finish.run();
                        }
                    });
        }
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            if (slot == moverSlot) continue;
            final View v = findRibbonSlotRow(slot);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            final float dy = exitTy >= 0 ? slotH * 0.18f : -slotH * 0.18f;
            v.animate().cancel();
            v.setTranslationY(0f);
            v.animate()
                    .translationY(dy)
                    .alpha(0.38f)
                    .setDuration(ANIM_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            v.animate().setListener(null);
                            finish.run();
                        }
                    });
        }
        if (scrollParent != null) {
            scrollParent.animate().cancel();
            scrollParent.animate()
                    .alpha(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            scrollParent.animate().setListener(null);
                            finish.run();
                        }
                    });
        }
    }

    private void enterRibbon() {
        if (host == null) return;
        ribbonActive = true;
        animating = false;
        host.removeAllViews();
        applyHostPadding();
        int rowH = binder.rowHeightPx();
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            host.addView(createSlotRow(slot, rowH));
        }
        if (scrollParent != null) scrollParent.scrollTo(0, 0);
    }

    private void applyHostPadding() {
        if (host == null) return;
        int slotH = binder.rowHeightPx() + 2;
        int bottom = PlaylistMoveWindow.bottomPaddingPx(moveFrom, binder.itemCount(), slotH);
        host.setPadding(0, hostPadTopPx, 0, bottom);
    }

    private FrameLayout createSlotRow(int ribbonSlot, int rowH) {
        FrameLayout row = MoveRibbonRows.createLibraryMoveRow(activity, rowH);
        row.setTag(TAG_RIBBON_SLOT, Integer.valueOf(ribbonSlot));
        return row;
    }

    private View findRibbonSlotRow(int ribbonSlot) {
        if (host == null) return null;
        for (int c = 0; c < host.getChildCount(); c++) {
            View row = host.getChildAt(c);
            Object tag = row.getTag(TAG_RIBBON_SLOT);
            if (tag instanceof Integer && ((Integer) tag).intValue() == ribbonSlot) {
                return row;
            }
        }
        return null;
    }

    private View findMoverRow() {
        int ms = PlaylistMoveWindow.moveSlot(moveFrom, binder.itemCount());
        return findRibbonSlotRow(ms);
    }

    private void populateRibbon() {
        if (!ribbonActive || host == null) return;
        applyHostPadding();
        final int count = binder.itemCount();
        final int moveIdx = moveFrom;
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            populateRibbonRow(findRibbonSlotRow(slot),
                    PlaylistMoveWindow.slotDataIndex(moveIdx, slot, count), slot, moveIdx);
        }
        if (scrollParent != null) scrollParent.scrollTo(0, 0);
    }

    private void populateRibbonRow(View row, int dataIndex, int ribbonSlot, int moveIdx) {
        if (!(row instanceof FrameLayout)) return;
        FrameLayout fl = (FrameLayout) row;
        boolean empty = dataIndex < 0 || dataIndex >= binder.itemCount();
        row.setTranslationY(0f);
        if (empty) {
            row.setVisibility(View.INVISIBLE);
            row.setAlpha(0.35f);
            MoveRibbonRows.bindEmptySlot(row);
            return;
        }
        row.setVisibility(View.VISIBLE);
        boolean moving = dataIndex == moveIdx;
        row.setAlpha(moving ? 1f : 0.82f);
        binder.bindRow(fl, dataIndex, ribbonSlot, false);
    }

    private void animateRibbonEnter() {
        populateRibbon();
        int count = binder.itemCount();
        int slotH = binder.rowHeightPx() + 2;
        float startTy = PlaylistMoveWindow.enterTranslationSlots(moveFrom, count, enterBrowseSlot)
                * (float) slotH;
        if (Math.abs(startTy) < 0.5f) {
            animateRibbonEnterFade();
            return;
        }
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        final int[] remaining = new int[] { 0 };
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            row.animate().cancel();
            row.setTranslationY(startTy);
            row.animate()
                    .translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) animating = false;
                        }
                    });
        }
        if (remaining[0] <= 0) animating = false;
    }

    private void animateRibbonEnterFade() {
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        final int[] remaining = new int[] { 0 };
        int moveIdx = moveFrom;
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            int dataIdx = PlaylistMoveWindow.slotDataIndex(moveIdx, slot, binder.itemCount());
            final float targetAlpha = dataIdx == moveIdx ? 1f : 0.82f;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.setAlpha(0.45f);
            row.animate()
                    .alpha(targetAlpha)
                    .setDuration(ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) animating = false;
                        }
                    });
        }
        if (remaining[0] <= 0) animating = false;
    }

    /** Boundary cross — mover slot changes (e.g. into last-two positions). */
    private void animateSlotShift(int fromSlot, int toSlot, final Runnable onEnd) {
        if (host == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        int slotH = binder.rowHeightPx() + 2;
        final float dy = (toSlot - fromSlot) * (float) slotH;
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        host.animate().cancel();
        host.setTranslationY(0f);
        host.animate()
                .translationY(-dy)
                .setDuration(SLOT_SHIFT_MS)
                .setInterpolator(ease)
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        host.animate().setListener(null);
                        host.setTranslationY(0f);
                        animating = false;
                        if (onEnd != null) onEnd.run();
                    }
                });
    }

    private void animateRibbonStrip(final int wheelDelta, final Runnable onEnd) {
        if (wheelDelta == 0) {
            if (onEnd != null) onEnd.run();
            return;
        }
        int count = binder.itemCount();
        int moverSlot = PlaylistMoveWindow.moveSlot(moveFrom, count);
        View mover = findRibbonSlotRow(moverSlot);
        if (mover == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        mover.animate().cancel();
        mover.setTranslationY(0f);
        mover.setAlpha(1f);
        int slotH = binder.rowHeightPx() + 2;
        final float dy = wheelDelta > 0 ? -slotH : slotH;
        int animCount = 0;
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            if (slot == moverSlot) continue;
            View v = findRibbonSlotRow(slot);
            if (v != null && v.getVisibility() == View.VISIBLE) animCount++;
        }
        if (animCount == 0) {
            if (onEnd != null) onEnd.run();
            return;
        }
        animating = true;
        final int[] remaining = new int[] { animCount };
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            if (slot == moverSlot) continue;
            final View v = findRibbonSlotRow(slot);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.setTranslationY(0f);
            v.animate()
                    .translationY(dy)
                    .alpha(0.42f)
                    .setDuration(ANIM_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            v.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) finishStripAnim(onEnd);
                        }
                    });
        }
    }

    private void finishStripAnim(Runnable onEnd) {
        animating = false;
        for (int slot = PlaylistMoveWindow.SLOT_0; slot <= PlaylistMoveWindow.LAST_SLOT; slot++) {
            View row = findRibbonSlotRow(slot);
            if (row == null) continue;
            row.animate().cancel();
            row.setTranslationY(0f);
        }
        if (onEnd != null) onEnd.run();
    }
}
