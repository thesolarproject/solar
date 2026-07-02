package com.solar.launcher;

import android.view.View;
import android.widget.ListView;
import android.widget.ScrollView;

/**
 * Vertical scroll chrome for Y1 lists — Holo edge glow when wheel hits limits.
 * ponytail: API 17 MT6572 crashes in ScrollBarDrawable.setAlpha — never enable scrollbars on Y1.
 */
public final class Y1ScrollIndicators {

    private static final int EDGE_PULL_PX = 40;

    private Y1ScrollIndicators() {}

    public static void applyVerticalScrollView(ScrollView scroll) {
        if (scroll == null) return;
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setScrollbarFadingEnabled(false);
    }

    public static void applyVerticalListView(ListView list) {
        if (list == null) return;
        list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        list.setVerticalScrollBarEnabled(false);
        list.setHorizontalScrollBarEnabled(false);
        list.setScrollbarFadingEnabled(false);
    }

    /** Wheel hit top/bottom — brief overscroll bounce (API 19+; Jelly Bean edge glow can crash). */
    public static void edgeGlowAtLimit(ScrollView scroll, int delta) {
        if (scroll == null || delta == 0) return;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) return;
        final int pull = delta < 0 ? -EDGE_PULL_PX : EDGE_PULL_PX;
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int y = scroll.getScrollY();
                int max = 0;
                if (scroll.getChildCount() > 0) {
                    max = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
                }
                if (delta < 0 && y > 0) return;
                if (delta > 0 && y < max) return;
                scroll.smoothScrollBy(0, pull);
                final int scrollMax = max;
                scroll.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (delta < 0) scroll.smoothScrollTo(0, 0);
                        else scroll.smoothScrollTo(0, scrollMax);
                    }
                }, 90);
            }
        });
    }

    public static void edgeGlowAtLimit(ListView list, int delta) {
        if (list == null || delta == 0 || list.getAdapter() == null) return;
        int count = list.getAdapter().getCount();
        if (count <= 0) return;
        int pos = list.getSelectedItemPosition();
        if (pos < 0) pos = list.getFirstVisiblePosition();
        if (delta < 0 && pos > 0) return;
        if (delta > 0 && pos < count - 1) return;
        list.post(new Runnable() {
            @Override
            public void run() {
                if (delta < 0) {
                    list.smoothScrollBy(-EDGE_PULL_PX, 100);
                    list.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            list.smoothScrollToPosition(0);
                        }
                    }, 90);
                } else {
                    list.smoothScrollBy(EDGE_PULL_PX, 100);
                    list.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            list.smoothScrollToPosition(count - 1);
                        }
                    }, 90);
                }
            }
        });
    }

    public static boolean listAtScrollLimit(ListView list, int delta) {
        if (list == null || list.getAdapter() == null) return false;
        int count = list.getAdapter().getCount();
        if (count <= 0) return true;
        int pos = list.getSelectedItemPosition();
        if (pos < 0) pos = list.getFirstVisiblePosition();
        return (delta < 0 && pos <= 0) || (delta > 0 && pos >= count - 1);
    }
}
