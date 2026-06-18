package com.solar.launcher;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;

/** Hold-Back context menu — theme row chrome, wheel nav, center to select. */
public final class ThemedContextMenu {
    private static final int TAG_LABEL = 0x70ca0001;
    private static final int TAG_ARROW = 0x70ca0002;
    private static final int TAG_STATE = 0x70ca0003;
    private static final int TAG_HEADER = 0x70ca0004;

    public interface Listener {
        void onSelected(int index);
    }

    private final Activity activity;
    private FrameLayout overlay;
    private ScrollView itemsScroll;
    private LinearLayout itemsHost;
    private String[] labels;
    private boolean[] rowHeaders;
    private int focusIndex;
    private Listener listener;
    private int rowHeightPx;
    private int panelWidthPx;
    private boolean menuRows;

    public ThemedContextMenu(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    public void show(ViewGroup root, String title, String[] itemLabels, String[] itemIconKeys,
                     String[] itemStateTexts, boolean[] itemHeaders, Listener listener,
                     int rowHeightPx, int panelWidthPx, boolean menuStyleRows) {
        dismiss();
        if (itemLabels == null || itemLabels.length == 0) return;
        this.labels = itemLabels;
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        this.rowHeightPx = rowHeightPx;
        this.panelWidthPx = panelWidthPx;
        this.menuRows = menuStyleRows;
        this.focusIndex = firstFocusableIndex(0);

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(buildPanelBackground());

        if (title != null && title.length() > 0) {
            TextView tv = new TextView(activity);
            tv.setText(title);
            tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size));
            ThemeManager.applyThemedTextStyle(tv, textNormal());
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            tv.setMarqueeRepeatLimit(-1);
            tv.setPadding(0, 0, 0, (int) (4 * density));
            panel.addView(tv);
        }

        itemsScroll = new ScrollView(activity);
        itemsScroll.setFillViewport(false);
        itemsScroll.setVerticalScrollBarEnabled(false);
        int maxListH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < itemLabels.length; i++) {
            boolean header = itemHeaders != null && i < itemHeaders.length && itemHeaders[i];
            if (header) {
                itemsHost.addView(createHeaderRow(itemLabels[i]));
            } else {
                String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
                String stateText = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
                itemsHost.addView(createRow(itemLabels[i], iconKey, stateText));
            }
        }

        panel.addView(itemsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListH));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER;
        overlay.addView(panel, panelLp);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.post(new Runnable() {
            @Override
            public void run() {
                overlay.requestFocus();
            }
        });
        refreshRows();
        scrollFocusIntoView();
    }

    public void dismiss() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
        itemsScroll = null;
        itemsHost = null;
        labels = null;
        rowHeaders = null;
        listener = null;
    }

    public void moveFocus(int delta) {
        if (labels == null || labels.length == 0 || delta == 0) return;
        int next = nextFocusableIndex(focusIndex, delta);
        if (next < 0 || next == focusIndex) return;
        focusIndex = next;
        refreshRows();
        scrollFocusIntoView();
    }

    public void activateFocused() {
        if (isHeaderRow(focusIndex)) return;
        if (listener != null && labels != null && focusIndex >= 0 && focusIndex < labels.length) {
            listener.onSelected(focusIndex);
        }
    }

    private boolean isHeaderRow(int index) {
        return rowHeaders != null && index >= 0 && index < rowHeaders.length && rowHeaders[index];
    }

    private int firstFocusableIndex(int start) {
        if (labels == null) return 0;
        for (int i = start; i < labels.length; i++) {
            if (!isHeaderRow(i)) return i;
        }
        return start;
    }

    private int nextFocusableIndex(int from, int delta) {
        if (labels == null || labels.length == 0) return -1;
        int i = from + delta;
        while (i >= 0 && i < labels.length) {
            if (!isHeaderRow(i)) return i;
            i += delta;
        }
        return from;
    }

    private void scrollFocusIntoView() {
        if (itemsScroll == null || itemsHost == null) return;
        if (focusIndex < 0 || focusIndex >= itemsHost.getChildCount()) return;
        final View row = itemsHost.getChildAt(focusIndex);
        if (row == null) return;
        itemsScroll.post(new Runnable() {
            @Override
            public void run() {
                itemsScroll.requestChildFocus(itemsHost, row);
            }
        });
    }

    private Drawable buildPanelBackground() {
        Drawable themeDlg = ThemeManager.getDialogBackground(activity.getResources());
        float r = ThemeManager.getButtonRadius() * 2f * activity.getResources().getDisplayMetrics().density;
        if (themeDlg != null) {
            GradientDrawable tint = new GradientDrawable();
            tint.setColor(0xCC000000);
            tint.setCornerRadius(r);
            android.graphics.drawable.LayerDrawable layer =
                    new android.graphics.drawable.LayerDrawable(new Drawable[] {tint, themeDlg});
            return layer;
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xCC222222);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, (int) (1 * activity.getResources().getDisplayMetrics().density)),
                ThemeManager.getRowSelectionFillColor());
        return g;
    }

    private static android.graphics.Bitmap contextMenuIcon(String iconKey) {
        if (iconKey == null || iconKey.isEmpty()) return null;
        if (iconKey.startsWith("shuffle") || iconKey.startsWith("repeat")) {
            return ThemeManager.getPlaybackModeIcon(iconKey);
        }
        return ThemeManager.getSettingIcon(iconKey);
    }

    private TextView createHeaderRow(String text) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        TextView tv = new TextView(activity);
        tv.setTag(TAG_HEADER, Boolean.TRUE);
        tv.setFocusable(false);
        tv.setText(text);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.85f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tv.setMarqueeRepeatLimit(-1);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        ThemeManager.applyThemedTextStyle(tv, ThemeManager.getSectionHeaderTextColor());
        float density = activity.getResources().getDisplayMetrics().density;
        tv.setPadding(textPadLeft, (int) (8 * density), textPadLeft, (int) (2 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, (int) (4 * density), 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private FrameLayout createRow(String text, String iconKey, String stateText) {
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        android.graphics.Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(rowHeightPx);
        int arrowW = arrowBmp != null ? arrowBmp.getWidth() : 0;
        int arrowH = arrowBmp != null ? arrowBmp.getHeight() : 0;
        int arrowMarginEnd = (int) activity.getResources().getDimension(R.dimen.y1_arrow_margin_end);
        float density = activity.getResources().getDisplayMetrics().density;
        int iconSize = (int) (rowHeightPx * 0.72f);
        int iconGap = (int) (4 * density);

        android.graphics.Bitmap iconBmp = contextMenuIcon(iconKey);
        int iconW = iconBmp != null ? iconSize : 0;
        int labelLeft = textPadLeft + (iconW > 0 ? iconW + iconGap : 0);

        FrameLayout row = new FrameLayout(activity);
        row.setFocusable(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);

        if (iconBmp != null) {
            ImageView icon = new ImageView(activity);
            icon.setFocusable(false);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageBitmap(iconBmp);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
            iconLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            iconLp.leftMargin = textPadLeft;
            row.addView(icon, iconLp);
        }

        TextView label = new TextView(activity);
        label.setText(text);
        label.setFocusable(false);
        label.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        label.setIncludeFontPadding(false);
        label.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        labelLp.leftMargin = labelLeft;
        labelLp.rightMargin = arrowW + arrowMarginEnd;
        row.addView(label, labelLp);

        TextView state = null;
        if (stateText != null && stateText.length() > 0) {
            state = new TextView(activity);
            state.setText(stateText);
            state.setFocusable(false);
            state.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
            state.setSingleLine(true);
            state.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            state.setMarqueeRepeatLimit(-1);
            state.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            ThemeManager.applyThemedTextStyle(state, ThemeManager.getHintTextColor());
            FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
            stateLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            stateLp.rightMargin = arrowW + arrowMarginEnd + (int) (6 * density);
            row.addView(state, stateLp);
        }

        ImageView arrow = new ImageView(activity);
        arrow.setFocusable(false);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (arrowBmp != null) arrow.setImageBitmap(arrowBmp);
        arrow.setVisibility(View.GONE);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(arrowW, arrowH);
        arrowLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        arrowLp.rightMargin = arrowMarginEnd;
        row.addView(arrow, arrowLp);

        row.setTag(TAG_LABEL, label);
        row.setTag(TAG_ARROW, arrow);
        row.setTag(TAG_STATE, state);
        return row;
    }

    private void refreshRows() {
        if (itemsHost == null) return;
        for (int i = 0; i < itemsHost.getChildCount(); i++) {
            View row = itemsHost.getChildAt(i);
            if (row.getTag(TAG_HEADER) instanceof Boolean) continue;
            boolean focused = i == focusIndex;
            TextView label = (TextView) row.getTag(TAG_LABEL);
            ImageView arrow = (ImageView) row.getTag(TAG_ARROW);
            TextView state = (TextView) row.getTag(TAG_STATE);
            int w = panelWidthPx > 0 ? panelWidthPx : row.getWidth();
            row.setBackground(rowBackground(focused, w));
            if (label != null) {
                ThemeManager.applyThemedTextStyle(label, focused ? textSelected() : textNormal());
                label.setSelected(focused);
            }
            if (state != null) {
                ThemeManager.applyThemedTextStyle(state, focused
                        ? textSelected() : ThemeManager.getHintTextColor());
                state.setSelected(focused);
            }
            if (arrow != null) arrow.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
    }

    private Drawable rowBackground(boolean focused, int widthPx) {
        Drawable scaled = menuRows
                ? ThemeManager.getMenuRowBackgroundScaled(
                        activity.getResources(), focused, widthPx, rowHeightPx)
                : ThemeManager.getItemRowBackgroundScaled(
                        activity.getResources(), focused, widthPx, rowHeightPx);
        if (scaled != null) return scaled;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(ThemeManager.getButtonRadius() * activity.getResources().getDisplayMetrics().density);
        g.setColor(focused ? ThemeManager.getRowSelectionFillColor() : 0x00000000);
        return g;
    }

    private int textNormal() {
        return menuRows ? ThemeManager.getSettingMenuTextColorNormal() : ThemeManager.getItemTextColorNormal();
    }

    private int textSelected() {
        return menuRows ? ThemeManager.getSettingMenuTextColorSelected() : ThemeManager.getItemTextColorSelected();
    }
}
