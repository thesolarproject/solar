package com.solar.launcher;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.ScrollView;

/** Animated scroll-to-focus for single-axis hardware lists. */
public final class FocusScrollHelper {
    private static final int LIST_IDLE_FOCUS_DELAY_MS = 80;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.WeakHashMap<ListView, ListFocusRequest> REQUESTS =
            new java.util.WeakHashMap<ListView, ListFocusRequest>();

    private FocusScrollHelper() {}

    public static void smoothScrollListToPosition(final ListView list, final int position) {
        if (list == null || position < 0) return;
        ListFocusRequest request = requestFor(list);
        request.begin(position);
        list.smoothScrollToPosition(position);
        list.setOnScrollListener(request);
        MAIN.postDelayed(request, 220);
    }

    public static void focusListPosition(ListView list, int position) {
        if (list == null || position < 0) return;
        requestFor(list).focus(position);
    }

    public static void scrollToChildBottom(ScrollView scroll, View child) {
        if (scroll == null || child == null) return;
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int y = child.getBottom() - scroll.getHeight();
                if (y < 0) y = 0;
                scroll.smoothScrollTo(0, y);
            }
        });
    }

    private static ListFocusRequest requestFor(ListView list) {
        synchronized (REQUESTS) {
            ListFocusRequest request = REQUESTS.get(list);
            if (request == null) {
                request = new ListFocusRequest(list);
                REQUESTS.put(list, request);
            }
            return request;
        }
    }

    private static final class ListFocusRequest implements AbsListView.OnScrollListener, Runnable {
        private final ListView list;
        private int position;
        private boolean scrolling;
        private boolean retry;

        ListFocusRequest(ListView list) {
            this.list = list;
        }

        void begin(int position) {
            MAIN.removeCallbacks(this);
            this.position = position;
            scrolling = false;
            retry = false;
        }

        void focus(int position) {
            begin(position);
            run();
        }

        @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
            if (scrollState == SCROLL_STATE_IDLE) {
                if (scrolling) {
                    scrolling = false;
                    list.setOnScrollListener(null);
                    run();
                }
            } else {
                scrolling = true;
            }
        }

        @Override public void onScroll(AbsListView view, int firstVisibleItem,
                int visibleItemCount, int totalItemCount) {}

        @Override public void run() {
            MAIN.removeCallbacks(this);
            list.setSelection(position);
            View child = list.getChildAt(position - list.getFirstVisiblePosition());
            if (child != null) {
                retry = false;
                child.requestFocus();
            } else if (!retry) {
                retry = true;
                list.postOnAnimation(this);
            }
        }
    }
}
