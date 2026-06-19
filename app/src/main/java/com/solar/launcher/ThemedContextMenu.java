package com.solar.launcher;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;

/** Hold-Back context menu — quick bar, vertical list, slider/tier modes. */
public final class ThemedContextMenu {
    private static final int TAG_LABEL = 0x70ca0001;
    private static final int TAG_ARROW = 0x70ca0002;
    private static final int TAG_STATE = 0x70ca0003;
    private static final int TAG_HEADER = 0x70ca0004;

    public enum FocusZone { QUICK_BAR, LIST, SLIDER, TIER_CONTENT }

    public static final class QuickItem {
        public final String iconKey;
        public final int iconResId;
        public final String label;
        public final boolean visible;

        public QuickItem(String iconKey, int iconResId, String label, boolean visible) {
            this.iconKey = iconKey;
            this.iconResId = iconResId;
            this.label = label;
            this.visible = visible;
        }
    }

    public interface Listener {
        void onSelected(int index);
    }

    public interface QuickBarListener {
        void onQuickSelected(int index);
    }

    private final Activity activity;
    private FrameLayout overlay;
    private LinearLayout panel;
    private HorizontalScrollView quickBarScroll;
    private LinearLayout quickBarHost;
    private ScrollView itemsScroll;
    private LinearLayout itemsHost;
    private ProgressBar sliderBar;
    private TextView sliderLabel;
    private String[] labels;
    private boolean[] rowHeaders;
    private int focusIndex;
    private int quickFocusIndex;
    private FocusZone focusZone = FocusZone.LIST;
    private Listener listener;
    private QuickBarListener quickListener;
    private QuickItem[] quickItems = new QuickItem[0];
    private int rowHeightPx;
    private int panelWidthPx;
    private boolean menuRows;
    private boolean dialogStyle;
    private int panelBgColor;
    private int sliderMax = 15;
    private int sliderValue;

    public ThemedContextMenu(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    public FocusZone focusZone() {
        return focusZone;
    }

    public void show(ViewGroup root, String title, String[] itemLabels, String[] itemIconKeys,
                     String[] itemStateTexts, boolean[] itemHeaders, Listener listener,
                     int rowHeightPx, int panelWidthPx, boolean menuStyleRows) {
        show(root, title, null, itemLabels, itemIconKeys, itemStateTexts, itemHeaders, listener,
                rowHeightPx, panelWidthPx, menuStyleRows, false, null, null);
    }

    public void show(ViewGroup root, String title, String subtitle, String[] itemLabels,
                     String[] itemIconKeys, String[] itemStateTexts, boolean[] itemHeaders,
                     Listener listener, int rowHeightPx, int panelWidthPx, boolean menuStyleRows,
                     boolean dialogStyleRows) {
        show(root, title, subtitle, itemLabels, itemIconKeys, itemStateTexts, itemHeaders, listener,
                rowHeightPx, panelWidthPx, menuStyleRows, dialogStyleRows, null, null);
    }

    public void show(ViewGroup root, String title, String subtitle, String[] itemLabels,
                     String[] itemIconKeys, String[] itemStateTexts, boolean[] itemHeaders,
                     Listener listener, int rowHeightPx, int panelWidthPx, boolean menuStyleRows,
                     boolean dialogStyleRows, QuickItem[] quickBar, QuickBarListener quickListener) {
        dismiss();
        if ((itemLabels == null || itemLabels.length == 0) && (quickBar == null || quickBar.length == 0)) return;
        this.labels = itemLabels != null ? itemLabels : new String[0];
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        this.quickListener = quickListener;
        this.quickItems = quickBar != null ? quickBar : new QuickItem[0];
        this.rowHeightPx = rowHeightPx;
        this.panelWidthPx = panelWidthPx;
        this.menuRows = menuStyleRows;
        this.dialogStyle = dialogStyleRows;
        this.panelBgColor = ThemeManager.getContextMenuPanelColor();
        this.focusIndex = firstFocusableIndex(0);
        this.quickFocusIndex = firstVisibleQuickIndex();
        this.focusZone = FocusZone.LIST;

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (12 * density);
        panel.setPadding(padH, (int) (12 * density), padH, (int) (10 * density));
        panel.setBackground(buildPanelBackground());

        if (title != null && title.length() > 0) {
            TextView tv = new TextView(activity);
            tv.setText(title);
            tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size));
            ThemeManager.applyThemedTextStyle(tv,
                    ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            tv.setMarqueeRepeatLimit(-1);
            tv.setPadding(0, 0, 0, (int) (4 * density));
            panel.addView(tv);
        }

        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = new TextView(activity);
            sub.setText(subtitle);
            sub.setTypeface(ThemeManager.getCustomFont());
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size) * 0.82f);
            ThemeManager.applyThemedTextStyle(sub,
                    ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
            sub.setMaxLines(4);
            sub.setPadding(0, 0, 0, (int) (8 * density));
            panel.addView(sub);
        }

        if (quickItems.length > 0 && !dialogStyleRows) {
            int quickH = (int) (rowHeightPx * 0.7f);
            quickBarScroll = new HorizontalScrollView(activity);
            quickBarScroll.setHorizontalScrollBarEnabled(false);
            quickBarHost = new LinearLayout(activity);
            quickBarHost.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < quickItems.length; i++) {
                if (!quickItems[i].visible) continue;
                quickBarHost.addView(createQuickChip(quickItems[i], quickH, i));
            }
            quickBarScroll.addView(quickBarHost, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, quickH));
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, quickH);
            qLp.bottomMargin = (int) (6 * density);
            panel.addView(quickBarScroll, qLp);
        }

        itemsScroll = new ScrollView(activity);
        itemsScroll.setFillViewport(false);
        itemsScroll.setVerticalScrollBarEnabled(false);
        int maxListH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.52f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        rebuildListRows(itemIconKeys, itemStateTexts);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(itemsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListH));

        sliderBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        sliderBar.setMax(sliderMax);
        sliderBar.setVisibility(View.GONE);
        sliderLabel = new TextView(activity);
        sliderLabel.setVisibility(View.GONE);
        sliderLabel.setGravity(Gravity.CENTER);
        ThemeManager.applyThemedTextStyle(sliderLabel,
                ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        panel.addView(sliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(sliderBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (24 * density)));

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
        refreshAll();
        scrollFocusIntoView();
    }

    private void rebuildListRows(String[] itemIconKeys, String[] itemStateTexts) {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            boolean header = rowHeaders != null && i < rowHeaders.length && rowHeaders[i];
            if (header) {
                itemsHost.addView(createHeaderRow(labels[i]));
            } else {
                String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
                String stateText = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
                itemsHost.addView(createRow(labels[i], iconKey, stateText));
            }
        }
    }

    public void replaceListContent(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener) {
        this.labels = itemLabels != null ? itemLabels : new String[0];
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        this.focusIndex = firstFocusableIndex(0);
        this.focusZone = FocusZone.TIER_CONTENT;
        rebuildListRows(itemIconKeys, itemStateTexts);
        refreshAll();
        scrollFocusIntoView();
    }

    public void showSlider(String label, int max, int value) {
        sliderMax = Math.max(1, max);
        sliderValue = Math.max(0, Math.min(value, sliderMax));
        if (sliderBar != null) {
            sliderBar.setMax(sliderMax);
            sliderBar.setProgress(sliderValue);
            sliderBar.setVisibility(View.VISIBLE);
        }
        if (sliderLabel != null) {
            sliderLabel.setText(label != null ? label : "");
            sliderLabel.setVisibility(View.VISIBLE);
        }
        if (itemsScroll != null) itemsScroll.setVisibility(View.GONE);
        if (quickBarScroll != null) quickBarScroll.setAlpha(0.35f);
        focusZone = FocusZone.SLIDER;
        refreshAll();
    }

    public void hideSlider() {
        if (sliderBar != null) sliderBar.setVisibility(View.GONE);
        if (sliderLabel != null) sliderLabel.setVisibility(View.GONE);
        if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        focusZone = FocusZone.LIST;
        refreshAll();
    }

    public int sliderValue() {
        return sliderValue;
    }

    public void adjustSlider(int delta) {
        sliderValue = Math.max(0, Math.min(sliderMax, sliderValue + delta));
        if (sliderBar != null) sliderBar.setProgress(sliderValue);
    }

    public void dismiss() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
        panel = null;
        quickBarScroll = null;
        quickBarHost = null;
        itemsScroll = null;
        itemsHost = null;
        sliderBar = null;
        sliderLabel = null;
        labels = null;
        rowHeaders = null;
        listener = null;
        quickListener = null;
        quickItems = new QuickItem[0];
    }

    public boolean handleKeyHorizontal(int keyCode) {
        if (!isShowing()) return false;
        if (keyCode == 21) {
            if (focusZone == FocusZone.LIST && focusIndex == firstFocusableIndex(0)) {
                focusZone = FocusZone.QUICK_BAR;
                quickFocusIndex = lastVisibleQuickIndex();
                refreshAll();
                return true;
            }
            if (focusZone == FocusZone.QUICK_BAR) {
                moveQuickFocus(-1);
                return true;
            }
            if (focusZone == FocusZone.SLIDER) return true;
        }
        if (keyCode == 22) {
            if (focusZone == FocusZone.QUICK_BAR) {
                int last = lastVisibleQuickIndex();
                if (quickFocusIndex >= last) {
                    focusZone = FocusZone.LIST;
                    focusIndex = firstFocusableIndex(0);
                } else {
                    moveQuickFocus(1);
                }
                refreshAll();
                return true;
            }
            if (focusZone == FocusZone.SLIDER) return true;
        }
        return false;
    }

    public void moveFocus(int delta) {
        if (labels == null || labels.length == 0 || delta == 0) return;
        if (focusZone == FocusZone.QUICK_BAR) {
            moveQuickFocus(delta);
            return;
        }
        if (focusZone == FocusZone.SLIDER) return;
        focusZone = FocusZone.LIST;
        int next = nextFocusableIndex(focusIndex, delta);
        if (next < 0 || next == focusIndex) return;
        focusIndex = next;
        refreshAll();
        scrollFocusIntoView();
    }

    private void moveQuickFocus(int delta) {
        int[] vis = visibleQuickIndices();
        if (vis.length == 0) return;
        int pos = 0;
        for (int i = 0; i < vis.length; i++) {
            if (vis[i] == quickFocusIndex) { pos = i; break; }
        }
        pos = (pos + delta + vis.length) % vis.length;
        quickFocusIndex = vis[pos];
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
    }

    public void activateFocused() {
        if (focusZone == FocusZone.QUICK_BAR) {
            if (quickListener != null) quickListener.onQuickSelected(quickFocusIndex);
            return;
        }
        if (focusZone == FocusZone.SLIDER) return;
        if (isHeaderRow(focusIndex)) return;
        if (listener != null && labels != null && focusIndex >= 0 && focusIndex < labels.length) {
            listener.onSelected(focusIndex);
        }
    }

    private int firstVisibleQuickIndex() {
        for (int i = 0; i < quickItems.length; i++) {
            if (quickItems[i].visible) return i;
        }
        return 0;
    }

    private int lastVisibleQuickIndex() {
        for (int i = quickItems.length - 1; i >= 0; i--) {
            if (quickItems[i].visible) return i;
        }
        return 0;
    }

    private int[] visibleQuickIndices() {
        int n = 0;
        for (QuickItem q : quickItems) if (q.visible) n++;
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < quickItems.length; i++) {
            if (quickItems[i].visible) out[j++] = i;
        }
        return out;
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
        if (itemsScroll == null || itemsHost == null || focusZone != FocusZone.LIST) return;
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

    private FrameLayout createQuickChip(QuickItem item, int heightPx, int index) {
        float density = activity.getResources().getDisplayMetrics().density;
        int iconSize = (int) (heightPx * 0.65f);
        int pad = (int) (6 * density);
        FrameLayout chip = new FrameLayout(activity);
        chip.setTag(index);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(heightPx + pad * 2, heightPx + pad);
        lp.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        chip.setLayoutParams(lp);
        chip.setPadding(pad, pad / 2, pad, pad / 2);

        ImageView icon = new ImageView(activity);
        android.graphics.Bitmap bmp = item.iconKey != null ? ThemeManager.getSettingIcon(item.iconKey) : null;
        if (bmp != null) {
            icon.setImageBitmap(bmp);
        } else if (item.iconResId != 0) {
            icon.setImageResource(item.iconResId);
        }
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        chip.addView(icon, iconLp);
        return chip;
    }

    private void refreshAll() {
        refreshQuickBar();
        refreshRows();
    }

    private void refreshQuickBar() {
        if (quickBarHost == null) return;
        for (int i = 0; i < quickBarHost.getChildCount(); i++) {
            View chip = quickBarHost.getChildAt(i);
            Object tag = chip.getTag();
            if (!(tag instanceof Integer)) continue;
            int idx = (Integer) tag;
            boolean focused = focusZone == FocusZone.QUICK_BAR && idx == quickFocusIndex;
            int w = chip.getWidth() > 0 ? chip.getWidth() : panelWidthPx / 6;
            chip.setBackground(rowBackground(focused, w));
        }
    }

    private Drawable buildPanelBackground() {
        float density = activity.getResources().getDisplayMetrics().density;
        float r = ThemeManager.getButtonRadius() * 2f * density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(panelBgColor);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, (int) density), 0x55FFFFFF);
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
        ThemeManager.applyThemedTextStyle(tv, ThemeManager.ensureReadableOnBackground(
                ThemeManager.getSectionHeaderTextColor(), panelBgColor));
        float density = activity.getResources().getDisplayMetrics().density;
        tv.setPadding(textPadLeft, (int) (8 * density), textPadLeft, (int) (2 * density));
        return tv;
    }

    private FrameLayout createRow(String text, String iconKey, String stateText) {
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        android.graphics.Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(rowHeightPx);
        int arrowW = arrowBmp != null ? arrowBmp.getWidth() : 0;
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
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageBitmap(iconBmp);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
            iconLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            iconLp.leftMargin = textPadLeft;
            row.addView(icon, iconLp);
        }

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        labelLp.leftMargin = labelLeft;
        labelLp.rightMargin = arrowW + arrowMarginEnd + (stateText != null ? (int) (48 * density) : 0);
        row.addView(label, labelLp);

        if (stateText != null && stateText.length() > 0) {
            TextView state = new TextView(activity);
            state.setText(stateText);
            state.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.9f);
            state.setSingleLine(true);
            ThemeManager.applyThemedTextStyle(state,
                    ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
            FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
            stateLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            stateLp.rightMargin = arrowW + arrowMarginEnd + (int) (6 * density);
            row.addView(state, stateLp);
            row.setTag(TAG_STATE, state);
        }

        ImageView arrow = new ImageView(activity);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (arrowBmp != null) arrow.setImageBitmap(arrowBmp);
        arrow.setVisibility(View.GONE);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(
                arrowBmp != null ? arrowW : 0, arrowBmp != null ? arrowBmp.getHeight() : 0);
        arrowLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        arrowLp.rightMargin = arrowMarginEnd;
        row.addView(arrow, arrowLp);

        row.setTag(TAG_LABEL, label);
        row.setTag(TAG_ARROW, arrow);
        return row;
    }

    private void refreshRows() {
        if (itemsHost == null) return;
        for (int i = 0; i < itemsHost.getChildCount(); i++) {
            View row = itemsHost.getChildAt(i);
            if (row.getTag(TAG_HEADER) instanceof Boolean) continue;
            boolean focused = focusZone == FocusZone.LIST && i == focusIndex;
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
                ThemeManager.applyThemedTextStyle(state, focused ? textSelected()
                        : ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
            }
            if (arrow != null) arrow.setVisibility(focused && !dialogStyle ? View.VISIBLE : View.GONE);
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
        int theme = menuRows ? ThemeManager.getSettingMenuTextColorNormal()
                : ThemeManager.getItemTextColorNormal();
        return ThemeManager.ensureReadableOnBackground(theme, panelBgColor);
    }

    private int textSelected() {
        return menuRows ? ThemeManager.getSettingMenuTextColorSelected()
                : ThemeManager.getItemTextColorSelected();
    }
}
