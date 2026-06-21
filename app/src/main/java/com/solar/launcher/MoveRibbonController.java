package com.solar.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Fixed 3-slot move ribbon for the context-menu playback queue only.
 * Home/More editors and playlist lists use {@link MoveStripController} instead.
 */
public final class MoveRibbonController {
    public static final int TAG_RIBBON_SLOT = 0x70ca0014;

    public static final int RIBBON_ANIM_MS = 130;
    public static final int RIBBON_ENTER_MS = 150;

    public interface Adapter {
        /** Empty slot shell tagged with {@link #TAG_RIBBON_SLOT}. */
        View createEmptySlot(int ribbonSlot);

        /** Bind item at dataIndex; ribbonSlot is {@link QueueMoveWindow#RIBBON_ABOVE} etc. */
        void bindItem(View row, int dataIndex, int ribbonSlot);

        /** Clear/hide an empty edge slot. */
        void bindEmptySlot(View row, int ribbonSlot);

        int itemCount();

        int rowSlotHeight();

        /** Browse viewport slot (0=top, 1=center, 2=bottom) for enter/cancel slide. */
        int browseViewportSlot(int index);

        /** Called after ribbon views are removed from host (restore browse list). */
        void onRibbonExited();
    }

    private final ViewGroup host;
    private final Adapter adapter;
    private int moveFrom = -1;
    private boolean ribbonActive;
    private boolean animating;

    public MoveRibbonController(ViewGroup host, Adapter adapter) {
        this.host = host;
        this.adapter = adapter;
    }

    public int moveFrom() {
        return moveFrom;
    }

    public boolean isActive() {
        return moveFrom >= 0;
    }

    public boolean isRibbonActive() {
        return ribbonActive;
    }

    public boolean isAnimating() {
        return animating;
    }

    public void setMoveFrom(int index) {
        moveFrom = index;
    }

    public void clearMove() {
        moveFrom = -1;
    }

    /** Enter or step the ribbon; wheelDelta 0 refreshes in place. */
    public void bindRibbon(int wheelDelta) {
        if (host == null || !isActive()) return;
        if (!ribbonActive) {
            enterRibbon();
            animateRibbonEnter(moveFrom);
            return;
        }
        Runnable bind = new Runnable() {
            @Override
            public void run() {
                populateRibbon();
            }
        };
        if (wheelDelta != 0 && !animating) {
            animateRibbonStrip(wheelDelta, bind);
        } else if (!animating) {
            bind.run();
        }
    }

    public void exitRibbon() {
        ribbonActive = false;
        animating = false;
    }

    /** Tear down ribbon and notify adapter. */
    public void teardownRibbon() {
        exitRibbon();
        if (host != null) {
            host.setPadding(0, 0, 0, 0);
            host.removeAllViews();
        }
        adapter.onRibbonExited();
    }

    public View findRibbonSlotRow(int ribbonSlot) {
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

    public View findRowByDataIndex(int dataIndex) {
        if (host == null) return null;
        for (int c = 0; c < host.getChildCount(); c++) {
            View row = host.getChildAt(c);
            Object tag = row.getTag();
            if (tag instanceof Integer && ((Integer) tag).intValue() == dataIndex) {
                return row;
            }
        }
        return null;
    }

    public void animateCancelReturn(int currentIdx, int homeIdx, final Runnable onComplete) {
        if (host == null || !isActive()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (currentIdx == homeIdx) {
            exitRibbon();
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!ribbonActive) {
            enterRibbon();
            populateRibbon();
        }
        int browseSlot = adapter.browseViewportSlot(homeIdx);
        int slotH = adapter.rowSlotHeight();
        float targetTy = (browseSlot - QueueMoveWindow.RIBBON_CENTER) * (float) slotH;
        int steps = Math.abs(currentIdx - homeIdx);
        final int duration = Math.min(200, RIBBON_ENTER_MS + steps * 14);
        if (Math.abs(targetTy) < 0.5f) {
            animateCancelFade(onComplete);
            return;
        }
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        final int[] remaining = new int[] { 0 };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            Object slotTag = row.getTag(TAG_RIBBON_SLOT);
            int ribbonSlot = slotTag instanceof Integer ? ((Integer) slotTag).intValue()
                    : QueueMoveWindow.RIBBON_CENTER;
            final float endAlpha = ribbonSlot == QueueMoveWindow.RIBBON_CENTER ? 0.5f : 0.3f;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.animate()
                    .translationY(targetTy)
                    .alpha(endAlpha)
                    .setDuration(duration)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                animating = false;
                                exitRibbon();
                                if (onComplete != null) onComplete.run();
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            animating = false;
            exitRibbon();
            if (onComplete != null) onComplete.run();
        }
    }

    private void enterRibbon() {
        if (host == null) return;
        ribbonActive = true;
        animating = false;
        host.setPadding(0, 0, 0, 0);
        host.removeAllViews();
        host.addView(adapter.createEmptySlot(QueueMoveWindow.RIBBON_ABOVE));
        host.addView(adapter.createEmptySlot(QueueMoveWindow.RIBBON_CENTER));
        host.addView(adapter.createEmptySlot(QueueMoveWindow.RIBBON_BELOW));
    }

    private void populateRibbon() {
        if (host == null || !isActive()) return;
        final int moveIdx = moveFrom;
        final int count = adapter.itemCount();
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_ABOVE),
                QueueMoveWindow.ribbonAboveIndex(moveIdx));
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_CENTER), moveIdx);
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_BELOW),
                QueueMoveWindow.ribbonBelowIndex(moveIdx, count));
    }

    private void populateRibbonRow(View row, int dataIndex) {
        if (row == null) return;
        row.setTranslationY(0f);
        Object slotTag = row.getTag(TAG_RIBBON_SLOT);
        int ribbonSlot = slotTag instanceof Integer ? ((Integer) tagToInt(slotTag))
                : QueueMoveWindow.RIBBON_CENTER;
        boolean empty = dataIndex < 0 || dataIndex >= adapter.itemCount();
        if (empty) {
            row.setTag(Integer.valueOf(-1));
            adapter.bindEmptySlot(row, ribbonSlot);
            return;
        }
        row.setTag(Integer.valueOf(dataIndex));
        row.setVisibility(View.VISIBLE);
        row.setAlpha(ribbonSlot == QueueMoveWindow.RIBBON_CENTER ? 1f : 0.82f);
        adapter.bindItem(row, dataIndex, ribbonSlot);
    }

    private static int tagToInt(Object tag) {
        return tag instanceof Integer ? ((Integer) tag).intValue() : QueueMoveWindow.RIBBON_CENTER;
    }

    private void animateRibbonEnter(int moveIdx) {
        populateRibbon();
        int browseSlot = adapter.browseViewportSlot(moveIdx);
        int slotH = adapter.rowSlotHeight();
        float startTy = (browseSlot - QueueMoveWindow.RIBBON_CENTER) * (float) slotH;
        if (Math.abs(startTy) < 0.5f) {
            animateRibbonEnterFade();
            return;
        }
        animating = true;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.35f);
        final int[] remaining = new int[] { QueueMoveWindow.VISIBLE_ROWS };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null) {
                remaining[0]--;
                continue;
            }
            row.animate().cancel();
            row.setTranslationY(startTy);
            row.animate()
                    .translationY(0f)
                    .setDuration(RIBBON_ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                animating = false;
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            animating = false;
        }
    }

    private void animateRibbonEnterFade() {
        animating = true;
        final int[] remaining = new int[] { 0 };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            Object slotTag = row.getTag(TAG_RIBBON_SLOT);
            int ribbonSlot = tagToInt(slotTag);
            final float targetAlpha = ribbonSlot == QueueMoveWindow.RIBBON_CENTER ? 1f : 0.82f;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.setAlpha(0.45f);
            row.animate()
                    .alpha(targetAlpha)
                    .setDuration(RIBBON_ENTER_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                animating = false;
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            animating = false;
        }
    }

    private void animateRibbonStrip(final int wheelDelta, final Runnable onEnd) {
        if (wheelDelta == 0) {
            if (onEnd != null) onEnd.run();
            return;
        }
        View above = findRibbonSlotRow(QueueMoveWindow.RIBBON_ABOVE);
        View below = findRibbonSlotRow(QueueMoveWindow.RIBBON_BELOW);
        View center = findRibbonSlotRow(QueueMoveWindow.RIBBON_CENTER);
        if (center == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        center.animate().cancel();
        center.setTranslationY(0f);
        center.setAlpha(1f);
        int slotH = adapter.rowSlotHeight();
        final float dy = wheelDelta > 0 ? -slotH : slotH;
        final View[] outers = new View[] { above, below };
        int animCount = 0;
        for (View v : outers) {
            if (v != null && v.getVisibility() == View.VISIBLE) animCount++;
        }
        if (animCount == 0) {
            if (onEnd != null) onEnd.run();
            return;
        }
        animating = true;
        final int[] remaining = new int[] { animCount };
        for (final View v : outers) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.setTranslationY(0f);
            v.animate()
                    .translationY(dy)
                    .alpha(0.42f)
                    .setDuration(RIBBON_ANIM_MS)
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
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            View row = findRibbonSlotRow(slot);
            if (row == null) continue;
            row.animate().cancel();
            row.setTranslationY(0f);
        }
        if (onEnd != null) onEnd.run();
    }

    private void animateCancelFade(final Runnable onComplete) {
        animating = true;
        final int[] remaining = new int[] { 0 };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.animate()
                    .alpha(0.35f)
                    .setDuration(RIBBON_ANIM_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                animating = false;
                                exitRibbon();
                                if (onComplete != null) onComplete.run();
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            animating = false;
            exitRibbon();
            if (onComplete != null) onComplete.run();
        }
    }
}
