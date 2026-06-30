package com.solar.launcher;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;

/** Compact capped scrollable text panel for context-menu message / dialog bodies. */
public final class VerticalTextMarqueeHelper {
    public static final int TAG_SCROLL = 0x70cc0002;
    public static final int TAG_BODY = 0x70cc0003;

    /** Total vertical padding added to measured content height (testable constant). */
    public static final int PANEL_VERTICAL_PAD_PX = 24;

    private VerticalTextMarqueeHelper() {}

    public static int measureTextHeight(Activity activity, CharSequence text, float textPx, int widthPx) {
        if (activity == null || widthPx <= 0) return 0;
        TextView measure = new TextView(activity);
        measure.setTypeface(ThemeManager.getCustomFont());
        measure.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        measure.setLineSpacing(messageLineSpacingPx(activity), 1f);
        measure.setText(text != null ? text : "");
        int specW = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
        int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        measure.measure(specW, specH);
        return measure.getMeasuredHeight();
    }

    /** Max height ~3 menu lines (matches context-menu detail cap). */
    public static int defaultMaxHeightPx(Activity activity) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float linePx = menuTextPx * 0.85f;
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (linePx * 1.18f * 3 + 14 * density);
    }

    public static float defaultLineTextPx(Activity activity) {
        return activity.getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f;
    }

    public static int messagePadVerticalPx(Activity activity) {
        return (int) activity.getResources().getDimension(R.dimen.y1_context_message_pad_v);
    }

    public static int messagePadBottomExtraPx(Activity activity) {
        return (int) activity.getResources().getDimension(R.dimen.y1_context_message_pad_bottom_extra);
    }

    public static float messageLineSpacingPx(Activity activity) {
        return activity.getResources().getDimension(R.dimen.y1_context_message_line_spacing);
    }

    /** Shrink-to-fit capped row height (testable without Activity). */
    public static int computeCappedRowHeight(int contentH, int pad, int maxHeightPx, int minH) {
        return Math.min(maxHeightPx, Math.max(minH, contentH + pad));
    }

    public static int computePanelHeight(Activity activity, CharSequence text, int maxHeightPx, int innerWidthPx) {
        float linePx = defaultLineTextPx(activity);
        int pad = messagePadVerticalPx(activity) + messagePadBottomExtraPx(activity);
        int minH = (int) (linePx * 1.18f + pad);
        int contentH = measureTextHeight(activity, text, linePx, innerWidthPx);
        return computeCappedRowHeight(contentH, pad, maxHeightPx, minH);
    }

    public static FrameLayout createCappedPanel(Activity activity, CharSequence text, int maxHeightPx) {
        float density = activity.getResources().getDisplayMetrics().density;
        int textPad = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int innerW = Math.max(1, screenW - textPad * 2 - (int) (8 * density));
        int rowH = computePanelHeight(activity, text, maxHeightPx, innerW);

        FrameLayout row = new FrameLayout(activity);
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(TAG_SCROLL);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        int padV = messagePadVerticalPx(activity);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, rowH);
        scrollLp.leftMargin = textPad;
        scrollLp.rightMargin = textPad;
        scrollLp.topMargin = padV / 2;
        scrollLp.bottomMargin = padV / 2;
        row.addView(scroll, scrollLp);

        TextView body = new TextView(activity);
        body.setTag(TAG_BODY);
        bindBodyText(activity, body, text, innerW);
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        row.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, rowH));
        return row;
    }

    public static void updateCappedPanel(Activity activity, FrameLayout row, CharSequence text,
            int maxHeightPx, int panelWidthPx) {
        if (activity == null || row == null) return;
        ScrollView scroll = row.findViewWithTag(TAG_SCROLL);
        TextView body = row.findViewWithTag(TAG_BODY);
        if (scroll == null || body == null) return;

        float density = activity.getResources().getDisplayMetrics().density;
        int textPad = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        int innerW = panelWidthPx > 0
                ? Math.max(1, panelWidthPx - textPad * 2 - (int) (8 * density))
                : Math.max(1, activity.getResources().getDisplayMetrics().widthPixels
                        - textPad * 2 - (int) (8 * density));
        int rowH = computePanelHeight(activity, text, maxHeightPx, innerW);

        bindBodyText(activity, body, text, innerW);
        FrameLayout.LayoutParams scrollLp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        scrollLp.height = rowH;
        scroll.setLayoutParams(scrollLp);
        ViewGroup.LayoutParams rowLp = row.getLayoutParams();
        if (rowLp != null) {
            rowLp.height = rowH;
            row.setLayoutParams(rowLp);
        }
    }

    private static void bindBodyText(Activity activity, TextView body, CharSequence text, int innerW) {
        float linePx = defaultLineTextPx(activity);
        int hPad = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        int topPad = messagePadVerticalPx(activity) / 2;
        int bottomPad = topPad + messagePadBottomExtraPx(activity);
        body.setTypeface(ThemeManager.getCustomFont());
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, linePx);
        body.setGravity(Gravity.START | Gravity.TOP);
        body.setLineSpacing(messageLineSpacingPx(activity), 1f);
        body.setPadding(hPad, topPad, hPad, bottomPad);
        body.setText(text != null ? text : "");
        ThemeManager.applyThemedTextStyle(body, ThemeManager.getTextColorPrimary());
        // Wheel-scroll only — no horizontal or auto vertical marquee.
        body.setSingleLine(false);
        body.setEllipsize(null);
        body.setHorizontallyScrolling(false);
        body.setSelected(false);
    }

    public static void stopPanel(FrameLayout row) {
        resetPanelScroll(row);
    }

    public static void resetPanelScroll(FrameLayout panel) {
        if (panel == null) return;
        ScrollView scroll = panel.findViewWithTag(TAG_SCROLL);
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    public static boolean isPanelScrolledToTop(FrameLayout panel) {
        ScrollView scroll = panel != null ? panel.findViewWithTag(TAG_SCROLL) : null;
        return scroll == null || scroll.getScrollY() <= 0;
    }

    public static boolean isPanelScrolledToBottom(FrameLayout panel) {
        ScrollView scroll = panel != null ? panel.findViewWithTag(TAG_SCROLL) : null;
        if (scroll == null) return true;
        View child = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
        if (child == null) return true;
        int max = Math.max(0, child.getHeight() - scroll.getHeight());
        return scroll.getScrollY() >= max - 2;
    }

    public static boolean scrollPanelByStep(Activity activity, FrameLayout panel, int direction) {
        if (panel == null || direction == 0) return false;
        ScrollView scroll = panel.findViewWithTag(TAG_SCROLL);
        if (scroll == null) return false;
        int step = (int) (defaultLineTextPx(activity) * 0.9f);
        if (step < 8) step = 8;
        int y = scroll.getScrollY() + (direction > 0 ? step : -step);
        View child = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
        int max = child != null ? Math.max(0, child.getHeight() - scroll.getHeight()) : 0;
        if (y < 0) y = 0;
        if (y > max) y = max;
        scroll.scrollTo(0, y);
        return true;
    }

}
