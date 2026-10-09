package com.solar.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * API 17 MT6572 crashes in View.onDrawScrollBars / ScrollBarDrawable.setAlpha on ListView draw.
 * ponytail: same workaround as {@link Y1SafeScrollView} — dispatchDraw only on Jelly Bean.
 * 2026-07-11 — Edge-only requestChildRectangleOnScreen (iPod / STB EPG): focus walks the
 * viewport; list only scrolls when the focused row would leave the screen.
 * Was: platform scrolled on every focus change (felt locked to one slot). Reversal: super.*.
 * 2026-07-11 — suppressChildRectScroll: FocusScrollHelper arms this around edge sticky
 * setSelectionFromTop + requestFocus so focus handoff cannot fight the pin (bounce/dual chrome).
 * 2026-07-11 — Sticky Y comes from natural last/first fully-visible tops (GIF), not pad re-pin.
 */
public class Y1SafeListView extends ListView {

    private static final float EDGE_PAD_DP = 2f;
    /** 2026-07-11 — When true, ignore focus-driven scroll (edge sticky owns Y). */
    private boolean suppressChildRectScroll;

    public Y1SafeListView(Context context) {
        super(context);
        init();
    }

    public Y1SafeListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Y1SafeListView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        Y1ScrollIndicators.applyVerticalListView(this);
        // 2026-07-05: API 17 MT6572 NPE in View.onDrawScrollBars when smooth scrollbars draw.
        setSmoothScrollbarEnabled(false);
    }

    /**
     * 2026-07-11 — Block requestChildRectangleOnScreen during edge sticky select/focus.
     * Layman: while we park the highlight in a fixed slot, do not let focus nudge the list.
     * Technical: mutex for FocusScrollHelper edge path; reversal: leave false (default).
     */
    public void setSuppressChildRectScroll(boolean suppress) {
        suppressChildRectScroll = suppress;
    }

    /** 2026-07-11 — True while edge sticky path owns scrolling. */
    public boolean isSuppressChildRectScroll() {
        return suppressChildRectScroll;
    }

    /**
     * 2026-07-11 — Only scroll when focused row clips past top/bottom of this ListView.
     * Layman: highlight can sit anywhere on screen; page moves at the edges only.
     * Technical: ListView kids are direct — use getTop()+rect (offsetDescendant is a no-op).
     * Suppresses AbsListView focus-scroll that pinned selection to one Y slot.
     */
    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        // 2026-07-11 — Edge sticky already setSelectionFromTop; focus must not re-scroll.
        if (suppressChildRectScroll) return false;
        if (child == null || rect == null) return false;
        int viewport = getHeight();
        if (viewport <= 0) return false;
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.max(1, (int) (EDGE_PAD_DP * density));
        // Direct ListView children: top/bottom already in viewport space.
        int top = child.getTop() + rect.top;
        int bottom = child.getTop() + rect.bottom;
        if (top >= 0 && bottom <= viewport) {
            return false;
        }
        int delta = 0;
        if (top < 0) {
            delta = top - pad;
        } else if (bottom > viewport) {
            delta = bottom - viewport + pad;
        }
        if (delta == 0) return false;
        // 2026-07-11 — Instant ticker only (no smoothScrollBy); FocusScrollHelper owns sticky.
        // Was: smoothScrollBy(delta, duration) which shunted highlight during glide.
        scrollListByCompat(delta);
        return true;
    }

    /** 2026-07-11 — scrollListBy is API 19; API 17 uses smoothScrollBy(distance, 0). */
    private void scrollListByCompat(int delta) {
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            scrollListBy(delta);
        } else {
            smoothScrollBy(delta, 0);
        }
    }

    /** 2026-07-05: Every adapter row must use AbsListView.LayoutParams — wrap to coerce at source. */
    @Override
    public void setAdapter(ListAdapter adapter) {
        if (adapter != null && !(adapter instanceof AbsListViewRowParamsWrapper)) {
            adapter = new AbsListViewRowParamsWrapper(adapter);
        }
        super.setAdapter(adapter);
    }

    /**
     * 2026-10-03 — Callers get the adapter they set, not the row-params wrapper.
     * Layman: code asking "is this the song list?" or "refresh the list" works again.
     * Technical: the wrapper is a plain ListAdapter, so `getAdapter() instanceof BaseAdapter /
     * SongListAdapter / CategoryListAdapter` checks were always false — e.g. SEGMENTED All Songs
     * never called notifyDataSetChanged when a page landed, leaving the first rows blank.
     * ListView itself keeps using the wrapper (mAdapter); observers already go to the delegate.
     */
    @Override
    public ListAdapter getAdapter() {
        ListAdapter a = super.getAdapter();
        return a instanceof AbsListViewRowParamsWrapper
                ? ((AbsListViewRowParamsWrapper) a).delegate() : a;
    }

    /** 2026-07-05: Header rows from createListButton also need ListView-safe params. */
    @Override
    public void addHeaderView(View v, Object data, boolean isSelectable) {
        ListViewRowParams.ensure(v);
        super.addHeaderView(v, data, isSelectable);
    }

    @Override
    public void draw(Canvas canvas) {
        try {
            super.draw(canvas);
        } catch (Throwable t) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("sdk", android.os.Build.VERSION.SDK_INT);
                d.put("w", getWidth());
                d.put("h", getHeight());
                DebugLibraryMenuLog.logError("Y1SafeListView.draw", "draw fallback", "H1", t, d);
            } catch (Exception ignored) {}
            // #endregion
            final int scrollX = getScrollX();
            final int scrollY = getScrollY();
            canvas.save();
            canvas.clipRect(scrollX, scrollY, scrollX + getWidth(), scrollY + getHeight());
            super.dispatchDraw(canvas);
            canvas.restore();
        }
    }
}
