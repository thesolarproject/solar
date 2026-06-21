package com.solar.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ScrollView;

/**
 * Full vertical list reorder with paper-strip slide animations.
 * Used by home/More editors and playlist track lists — not the context-menu 3-slot ribbon.
 */
public final class MoveStripController {
    public static final int TAG_DATA_INDEX = 0x70cc0010;

    public static final int STRIP_ANIM_MS = 130;
    public static final int STRIP_ENTER_MS = 150;
    public static final int CONFIRM_SHOW_MS = 120;
    public static final int CONFIRM_HOLD_MS = 450;

    public interface Adapter {
        View createRow();

        void bindRow(View row, int dataIndex, boolean moving, boolean confirming);

        int itemCount();

        int rowSlotHeight();
    }

    private final ViewGroup host;
    private final Adapter adapter;
    private ScrollView scrollParent;
    private int moveFrom = -1;
    private boolean listBuilt;
    private boolean animating;
    private Runnable confirmClearTask;

    public MoveStripController(ViewGroup host, Adapter adapter) {
        this.host = host;
        this.adapter = adapter;
    }

    public void setScrollParent(ScrollView scroll) {
        scrollParent = scroll;
    }

    public int moveFrom() {
        return moveFrom;
    }

    public boolean isActive() {
        return moveFrom >= 0;
    }

    public boolean isAnimating() {
        return animating;
    }

    public void setMoveFrom(int index) {
        moveFrom = index;
    }

    /** First show — build all strips and lift the picked row. */
    public void enter(int pickIndex) {
        moveFrom = pickIndex;
        listBuilt = false;
        buildList();
        animatePickEnter();
    }

    /** Refresh bindings without animation (after external data sync). */
    public void refreshAllRows() {
        if (!listBuilt || host == null) return;
        for (int c = 0; c < host.getChildCount(); c++) {
            View row = host.getChildAt(c);
            int idx = dataIndexOf(row);
            if (idx < 0) continue;
            adapter.bindRow(row, idx, idx == moveFrom, false);
        }
    }

    /** Slide adjacent strips; run {@code onComplete} after views settle (apply data there). */
    public void animateStep(int fromIndex, int toIndex, final Runnable onComplete) {
        if (host == null || animating || fromIndex == toIndex) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!listBuilt) buildList();
        final View fromRow = findRowByDataIndex(fromIndex);
        final View toRow = findRowByDataIndex(toIndex);
        if (fromRow == null || toRow == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        animating = true;
        int slotH = adapter.rowSlotHeight();
        float dy = toIndex > fromIndex ? (float) slotH : (float) -slotH;

        fromRow.animate().cancel();
        toRow.animate().cancel();
        fromRow.setTranslationY(0f);
        toRow.setTranslationY(0f);

        final int[] remaining = new int[] { 2 };
        android.animation.AnimatorListenerAdapter endListener = new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                remaining[0]--;
                if (remaining[0] > 0) return;
                fromRow.setTranslationY(0f);
                toRow.setTranslationY(0f);
                reorderChild(fromIndex, toIndex);
                moveFrom = toIndex;
                refreshAllRows();
                scrollRowIntoView(toIndex);
                animating = false;
                if (onComplete != null) onComplete.run();
            }
        };

        fromRow.animate()
                .translationY(dy)
                .setDuration(STRIP_ANIM_MS)
                .setListener(endListener)
                .start();
        toRow.animate()
                .translationY(-dy)
                .setDuration(STRIP_ANIM_MS)
                .setListener(endListener)
                .start();
    }

    /** Visual-only steps back toward pick index before cancel restore. */
    public void animateCancelReturn(int currentIdx, int homeIdx, final Runnable onComplete) {
        if (animating) return;
        if (currentIdx == homeIdx) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int next = currentIdx + (currentIdx > homeIdx ? -1 : 1);
        animateStep(currentIdx, next, new Runnable() {
            @Override
            public void run() {
                animateCancelReturn(next, homeIdx, onComplete);
            }
        });
    }

    public void flashConfirm(int index, final Runnable onComplete) {
        if (host == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        View row = findRowByDataIndex(index);
        if (row == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        adapter.bindRow(row, index, true, true);
        final ImageView confirm = (ImageView) row.findViewWithTag(MoveRibbonRows.TAG_CONFIRM);
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
            @Override
            public void run() {
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

    public void teardown() {
        if (confirmClearTask != null && host != null) {
            host.removeCallbacks(confirmClearTask);
            confirmClearTask = null;
        }
        animating = false;
        listBuilt = false;
        moveFrom = -1;
        if (host != null) {
            host.removeAllViews();
        }
    }

    private void buildList() {
        if (host == null) return;
        host.removeAllViews();
        int count = adapter.itemCount();
        for (int i = 0; i < count; i++) {
            View row = adapter.createRow();
            row.setTag(TAG_DATA_INDEX, Integer.valueOf(i));
            host.addView(row);
            adapter.bindRow(row, i, i == moveFrom, false);
        }
        listBuilt = true;
    }

    private void animatePickEnter() {
        View mover = findRowByDataIndex(moveFrom);
        if (mover == null) {
            scrollRowIntoView(moveFrom);
            return;
        }
        animating = true;
        mover.setAlpha(0.65f);
        mover.setTranslationY(6f);
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        mover.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(STRIP_ENTER_MS)
                .setInterpolator(ease)
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        mover.animate().setListener(null);
                        animating = false;
                    }
                })
                .start();
        scrollRowIntoView(moveFrom);
    }

    private void reorderChild(int fromIndex, int toIndex) {
        if (host == null) return;
        View row = findRowByDataIndex(fromIndex);
        if (row == null) return;
        host.removeView(row);
        host.addView(row, toIndex);
        for (int c = 0; c < host.getChildCount(); c++) {
            host.getChildAt(c).setTag(TAG_DATA_INDEX, Integer.valueOf(c));
        }
    }

    private View findRowByDataIndex(int dataIndex) {
        if (host == null) return null;
        for (int c = 0; c < host.getChildCount(); c++) {
            View row = host.getChildAt(c);
            if (dataIndexOf(row) == dataIndex) return row;
        }
        return null;
    }

    private static int dataIndexOf(View row) {
        Object tag = row != null ? row.getTag(TAG_DATA_INDEX) : null;
        return tag instanceof Integer ? ((Integer) tag).intValue() : -1;
    }

    private void scrollRowIntoView(int index) {
        if (scrollParent == null || host == null) return;
        View row = findRowByDataIndex(index);
        if (row == null) return;
        scrollParent.post(new Runnable() {
            @Override
            public void run() {
                int rowTop = row.getTop();
                int rowBottom = row.getBottom();
                int scrollY = scrollParent.getScrollY();
                int height = scrollParent.getHeight();
                if (rowTop < scrollY) {
                    scrollParent.smoothScrollTo(0, rowTop);
                } else if (rowBottom > scrollY + height) {
                    scrollParent.smoothScrollTo(0, rowBottom - height);
                }
            }
        });
    }
}
