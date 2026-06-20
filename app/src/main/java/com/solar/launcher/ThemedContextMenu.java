package com.solar.launcher;

import android.app.Activity;
import android.graphics.PorterDuff;
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
    private static final int TAG_QUEUE_TITLE = 0x70ca0010;
    private static final int TAG_QUEUE_SUB = 0x70ca0011;
    private static final int TAG_QUEUE_GRIP = 0x70ca0012;
    private static final int TAG_QUEUE_PP = 0x70ca0013;
    private static final int TAG_RIBBON_SLOT = 0x70ca0014;
    private static final int TAG_QUEUE_DROP = 0x70ca0014;

    public static final class QueueRowSpec {
        public final String title;
        public final String subtitle;
        public final boolean nowPlaying;
        public final boolean playing;

        public QueueRowSpec(String title, String subtitle, boolean nowPlaying, boolean playing) {
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.nowPlaying = nowPlaying;
            this.playing = playing;
        }

        public String displayLine() {
            if (subtitle.isEmpty()) return title;
            return title + " · " + subtitle;
        }
    }

    public enum FocusZone { OPTIONS_TITLE, QUICK_BAR, LIST, SLIDER, TIER_CONTENT }

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
        /** Wheel left from the first quick toggle — highlight Options title (center opens list). */
        void onFocusOptionsTitle();
    }

    private final Activity activity;
    private FrameLayout overlay;
    private LinearLayout panel;
    private LinearLayout titleRow;
    private FrameLayout titleChip;
    private TextView titleView;
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
    private int maxListHeightPx;
    private boolean queueMode;
    private QueueRowSpec[] queueRows = new QueueRowSpec[0];
    private int queueMoveFrom = -1;
    private boolean volumeOnlyMode;
    private boolean optionsListVisible = true;
    private boolean submenuTierOpen = false;
    private int queueRowHeightPx;
    private int quickReturnIndex = -1;
    /** Last quick-bar chip — scroll-right opens this tier (queue/music) instead of root list. */
    private int primaryEndQuickIndex = -1;
    private static final int QUEUE_MOVE_VISIBLE_ROWS = QueueMoveWindow.VISIBLE_ROWS;
    private Drawable cachedQueueRowBg;
    private Drawable cachedQueueRowBgFocused;
    private int cachedQueueRowBgWidth;
    private static final int QUEUE_MOVE_RIBBON_ANIM_MS = 130;
    private boolean queueMoveRibbonActive = false;
    private boolean queueMoveRibbonAnimating = false;
    private int queueBrowseWindowStart = 0;

    public ThemedContextMenu(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    public FocusZone focusZone() {
        return focusZone;
    }

    public boolean hasVisibleSlider() {
        return sliderBar != null && sliderBar.getVisibility() == View.VISIBLE;
    }

    /** Quick-bar chip to restore when leaving a tier list upward (e.g. Wi-Fi → index 1). */
    public void setQuickReturnIndex(int index) {
        quickReturnIndex = index;
    }

    public void setPrimaryEndQuickIndex(int index) {
        primaryEndQuickIndex = index;
    }

    private boolean isMenuListZone() {
        return focusZone == FocusZone.LIST || focusZone == FocusZone.TIER_CONTENT;
    }

    private void prepareListPanelVisible() {
        if (optionsListVisible || queueMode) {
            if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
        } else {
            collapseOptionsListPanel();
        }
        if (sliderBar != null) sliderBar.setVisibility(View.GONE);
        if (sliderLabel != null) sliderLabel.setVisibility(View.GONE);
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
    }

    private void collapseOptionsListPanel() {
        if (itemsScroll == null) return;
        itemsScroll.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            itemsScroll.setLayoutParams(lp);
        }
    }

    /** Hide contextual action rows; highlight the Options title for center to expand. */
    public void focusOptionsTitle() {
        if (titleView == null || labels == null || labels.length == 0) return;
        optionsListVisible = false;
        collapseOptionsListPanel();
        focusZone = FocusZone.OPTIONS_TITLE;
        scrollQuickBarToStart();
        refreshAll();
        requestOverlayFocus();
    }

    public void setSubmenuTierOpen(boolean open) {
        submenuTierOpen = open;
    }

    /** Reload root action rows but keep the list collapsed on the Options title. */
    public void replaceRootContentCollapsed(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener) {
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        submenuTierOpen = false;
        this.labels = itemLabels != null ? itemLabels : new String[0];
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        this.focusIndex = firstFocusableIndex(0);
        setPanelTitle(title);
        rebuildListRows(itemIconKeys, itemStateTexts);
        optionsListVisible = false;
        collapseOptionsListPanel();
        focusZone = FocusZone.OPTIONS_TITLE;
        refreshAll();
        requestOverlayFocus();
    }

    private boolean openEndQuickTierInsteadOfList() {
        if (primaryEndQuickIndex < 0 || quickFocusIndex != primaryEndQuickIndex) return false;
        if (quickListener == null) return false;
        quickListener.onQuickSelected(quickFocusIndex);
        return true;
    }

    private void enterListFromQuickBar() {
        if (openEndQuickTierInsteadOfList()) return;
        if (labels == null || labels.length == 0) return;
        if (!optionsListVisible && !submenuTierOpen && !queueMode) {
            focusOptionsTitle();
            return;
        }
        int idx = focusIndex >= 0 ? clampFocusableIndex(focusIndex) : firstFocusableIndex(0);
        enterMenuListFocus(idx);
    }

    /** Keep key events on the overlay after panel morphs (tiers, volume expand, Options title). */
    public void requestOverlayFocus() {
        if (overlay != null) overlay.requestFocus();
    }

    /** Open the contextual action list (from Options title or quick-bar return). */
    public void enterOptionsListFromTitle() {
        if (labels == null || labels.length == 0) return;
        optionsListVisible = true;
        prepareListPanelVisible();
        enterMenuListFocus(firstFocusableIndex(0));
    }

    /** Volume slider visible but inactive; quick toggle owns focus. */
    public void leaveVolumeSliderToQuickBar(int quickIndex) {
        if (submenuTierOpen || queueMode) {
            if (labels != null && labels.length > 0) {
                optionsListVisible = true;
                if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
            }
        } else {
            optionsListVisible = false;
            collapseOptionsListPanel();
        }
        quickFocusIndex = Math.max(0, Math.min(quickIndex, Math.max(0, quickItems.length - 1)));
        quickReturnIndex = quickFocusIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        if (optionsListVisible) updateListHeightToContent();
        requestOverlayFocus();
    }

    private void refreshTitleRow() {
        if (titleView == null) return;
        boolean titleFocused = focusZone == FocusZone.OPTIONS_TITLE;
        ThemeManager.applyThemedTextStyle(titleView, titleFocused ? textSelected() : textNormal());
        titleView.setSelected(titleFocused);
        if (titleFocused) {
            titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            titleView.setHorizontallyScrolling(true);
        } else {
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setHorizontallyScrolling(false);
        }
        if (titleChip != null) {
            int w = titleChip.getWidth() > 0 ? titleChip.getWidth()
                    : (panelWidthPx > 0 ? panelWidthPx / 2 : rowHeightPx * 4);
            titleChip.setBackground(rowBackground(titleFocused, w, rowHeightPx));
        }
        if (titleFocused) {
            scrollQuickBarToStart();
        }
    }

    private void scrollQuickBarToStart() {
        if (quickBarScroll == null) return;
        quickBarScroll.scrollTo(0, 0);
    }

    private void clampQuickBarScroll() {
        if (quickBarScroll == null || quickBarHost == null) return;
        quickBarScroll.post(new Runnable() {
            @Override
            public void run() {
                if (quickBarScroll == null || quickBarHost == null) return;
                int viewW = quickBarScroll.getWidth();
                int maxScroll = Math.max(0, quickBarHost.getWidth() - viewW);
                int x = quickBarScroll.getScrollX();
                if (x < 0) quickBarScroll.scrollTo(0, 0);
                else if (x > maxScroll) quickBarScroll.scrollTo(maxScroll, 0);
            }
        });
    }

    private boolean isFirstVisibleQuickIndex(int index) {
        int[] vis = visibleQuickIndices();
        return vis.length > 0 && vis[0] == index;
    }

    /** Title chip + quick-bar row at top of panel. */
    private void populateTitleRow(String title, boolean hasQuick, float density) {
        titleChip = null;
        titleView = null;
        quickBarScroll = null;
        quickBarHost = null;
        boolean hasTitle = title != null && title.length() > 0;
        if (hasTitle) {
            int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
            titleChip = new FrameLayout(activity);
            titleView = new TextView(activity);
            titleView.setText(title);
            titleView.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size));
            ThemeManager.applyThemedTextStyle(titleView, textNormal());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            titleView.setMarqueeRepeatLimit(-1);
            titleView.setHorizontallyScrolling(true);
            titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            titleLp.leftMargin = textPadLeft;
            titleLp.rightMargin = textPadLeft;
            titleLp.gravity = Gravity.CENTER_VERTICAL;
            titleChip.addView(titleView, titleLp);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    hasQuick ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx, hasQuick ? 1f : 0f);
            if (hasQuick) chipLp.rightMargin = (int) (4 * density);
            titleRow.addView(titleChip, chipLp);
        }
        if (hasQuick) {
            quickBarScroll = new HorizontalScrollView(activity);
            quickBarScroll.setHorizontalScrollBarEnabled(false);
            quickBarScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            quickBarScroll.setFillViewport(!hasTitle);
            quickBarHost = new LinearLayout(activity);
            quickBarHost.setOrientation(LinearLayout.HORIZONTAL);
            quickBarHost.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            for (int i = 0; i < quickItems.length; i++) {
                if (!quickItems[i].visible) continue;
                quickBarHost.addView(createQuickChip(quickItems[i], rowHeightPx, i));
            }
            quickBarScroll.addView(quickBarHost, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, rowHeightPx));
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                    hasTitle ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx);
            titleRow.addView(quickBarScroll, qLp);
        }
    }

    private void refreshSliderChrome() {
        if (sliderBar == null || sliderBar.getVisibility() != View.VISIBLE) return;
        boolean sliderActive = focusZone == FocusZone.SLIDER;
        if (quickBarScroll != null) {
            quickBarScroll.setAlpha(sliderActive ? 0.35f : 1f);
        }
        sliderBar.setAlpha(sliderActive ? 1f : 0.45f);
        if (sliderLabel != null) {
            ThemeManager.applyThemedTextStyle(sliderLabel,
                    sliderActive ? textSelected()
                            : ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
        }
    }

    private void enterMenuListFocus(int index) {
        optionsListVisible = true;
        prepareListPanelVisible();
        focusZone = FocusZone.TIER_CONTENT;
        focusIndex = clampFocusableIndex(index);
        refreshAll();
        updateListHeightToContent();
        scrollFocusIntoView();
        requestOverlayFocus();
    }

    private int quickBarReturnIndex() {
        if (quickReturnIndex >= 0 && quickReturnIndex < quickItems.length
                && quickItems[quickReturnIndex].visible) {
            return quickReturnIndex;
        }
        return lastVisibleQuickIndex();
    }

    /** Move highlight to the quick toggle that opened this tier — list stays visible. */
    private void focusQuickBarFromListExit() {
        if (quickBarHost == null || quickBarHost.getChildCount() == 0) return;
        if ((submenuTierOpen || queueMode) && labels != null && labels.length > 0) {
            optionsListVisible = true;
            if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
            updateListHeightToContent();
        }
        focusZone = FocusZone.QUICK_BAR;
        quickFocusIndex = quickBarReturnIndex();
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    private int queueRowSlotHeight() {
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        return rowH + 2;
    }

    private int queueViewportHeight() {
        return queueRowSlotHeight() * QUEUE_MOVE_VISIBLE_ROWS;
    }

    private boolean isQueueMoveActive() {
        return queueMode && queueMoveFrom >= 0;
    }

    /**
     * Viewport slot during move: mover stays in the middle row when possible so
     * now-playing above/below remains visible while reordering.
     */
    private int queueViewportSlotForIndex(int index, int count) {
        if (count <= 0 || index < 0 || index >= count) return 1;
        int np = queueNowPlayingIndex();
        if (index == 0 && np != 0) return 0;
        if (index == count - 1) return 2;
        return 1;
    }

    private void scrollQueueRowToViewportSlotNow(int index) {
        if (itemsScroll == null || itemsHost == null) return;
        if (index < 0) return;
        if (queueMode && useQueueBrowseVirtual()) {
            ensureQueueBrowseWindowForFocus();
        }
        View row = queueMode ? findQueueRowByIndex(index) : null;
        if (row == null && index < itemsHost.getChildCount()) {
            row = itemsHost.getChildAt(index);
        }
        if (row == null) return;
        int count = queueMode ? queueRows.length : itemsHost.getChildCount();
        if (index >= count) return;
        int rowTop = row.getTop();
        int viewport = itemsScroll.getHeight();
        if (viewport <= 0) return;
        int maxScroll = Math.max(0, itemsHost.getHeight() - viewport);
        int slot = queueViewportSlotForIndex(index, count);
        int slotH = queueRowSlotHeight();
        int target = rowTop - slot * slotH;
        itemsScroll.scrollTo(0, Math.min(Math.max(0, target), maxScroll));
    }

    private void scrollQueueRowToViewportSlotImmediate(final int index) {
        scrollQueueRowToViewportSlotNow(index);
        if (itemsScroll == null || itemsHost == null) return;
        itemsHost.post(new Runnable() {
            @Override
            public void run() {
                scrollQueueRowToViewportSlotNow(index);
                itemsScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollQueueRowToViewportSlotNow(index);
                    }
                });
            }
        });
    }

    private int queueScrollIndex() {
        return focusIndex;
    }

    private View findQueueRowByIndex(int queueIndex) {
        if (itemsHost == null) return null;
        for (int c = 0; c < itemsHost.getChildCount(); c++) {
            View row = itemsHost.getChildAt(c);
            Object tag = row.getTag();
            if (tag instanceof Integer && ((Integer) tag).intValue() == queueIndex) {
                return row;
            }
        }
        return null;
    }

    /** Move mode: fixed 3-slot ribbon — center is always the track being moved. */
    private void enterQueueMoveRibbon() {
        if (itemsHost == null) return;
        queueMoveRibbonActive = true;
        queueMoveRibbonAnimating = false;
        itemsHost.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        itemsHost.addView(createRibbonSlotRow(QueueMoveWindow.RIBBON_ABOVE, rowH, density));
        itemsHost.addView(createRibbonSlotRow(QueueMoveWindow.RIBBON_CENTER, rowH, density));
        itemsHost.addView(createRibbonSlotRow(QueueMoveWindow.RIBBON_BELOW, rowH, density));
        if (itemsScroll != null) itemsScroll.scrollTo(0, 0);
    }

    private FrameLayout createRibbonSlotRow(int ribbonSlot, int rowH, float density) {
        QueueRowSpec placeholder = new QueueRowSpec("", "", false, false);
        FrameLayout row = createQueueRow(placeholder, -1, rowH, density);
        row.setTag(TAG_RIBBON_SLOT, Integer.valueOf(ribbonSlot));
        return row;
    }

    private View findRibbonSlotRow(int ribbonSlot) {
        if (itemsHost == null) return null;
        for (int c = 0; c < itemsHost.getChildCount(); c++) {
            View row = itemsHost.getChildAt(c);
            Object tag = row.getTag(TAG_RIBBON_SLOT);
            if (tag instanceof Integer && ((Integer) tag).intValue() == ribbonSlot) {
                return row;
            }
        }
        return null;
    }

    private void bindQueueMoveRibbon(int wheelDelta) {
        if (!queueMode || itemsHost == null || !isQueueMoveActive()) return;
        if (!queueMoveRibbonActive) {
            enterQueueMoveRibbon();
            wheelDelta = 0;
        }
        final int moveIdx = queueMoveFrom;
        final int count = queueRows.length;
        final int aboveIdx = QueueMoveWindow.ribbonAboveIndex(moveIdx);
        final int belowIdx = QueueMoveWindow.ribbonBelowIndex(moveIdx, count);
        Runnable bind = new Runnable() {
            @Override
            public void run() {
                populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_ABOVE), aboveIdx);
                populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_CENTER), moveIdx);
                populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_BELOW), belowIdx);
                if (itemsScroll != null) itemsScroll.scrollTo(0, 0);
            }
        };
        if (wheelDelta != 0 && !queueMoveRibbonAnimating) {
            animateRibbonStrip(wheelDelta, bind);
        } else {
            bind.run();
        }
    }

    private void populateRibbonRow(View row, int queueIndex) {
        if (row == null) return;
        row.setTranslationY(0f);
        Object slotTag = row.getTag(TAG_RIBBON_SLOT);
        int ribbonSlot = slotTag instanceof Integer ? ((Integer) slotTag).intValue() : QueueMoveWindow.RIBBON_CENTER;
        boolean empty = queueIndex < 0 || queueIndex >= queueRows.length;
        if (empty) {
            row.setTag(Integer.valueOf(-1));
            row.setVisibility(View.INVISIBLE);
            row.setAlpha(0.35f);
            TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
            if (title != null) title.setText("");
            TextView grip = (TextView) row.findViewWithTag(TAG_QUEUE_GRIP);
            if (grip != null) grip.setVisibility(View.GONE);
            ImageView pp = (ImageView) row.findViewWithTag(TAG_QUEUE_PP);
            if (pp != null) pp.setVisibility(View.GONE);
            return;
        }
        row.setTag(Integer.valueOf(queueIndex));
        row.setVisibility(View.VISIBLE);
        row.setAlpha(ribbonSlot == QueueMoveWindow.RIBBON_CENTER ? 1f : 0.82f);
        QueueRowSpec spec = queueRows[queueIndex];
        TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
        if (title != null) title.setText(spec.displayLine());
        refreshQueueRowAt(queueIndex);
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
        int slotH = queueRowSlotHeight();
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
        queueMoveRibbonAnimating = true;
        final int[] remaining = new int[] { animCount };
        for (final View v : outers) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.setTranslationY(0f);
            v.animate()
                    .translationY(dy)
                    .alpha(0.42f)
                    .setDuration(QUEUE_MOVE_RIBBON_ANIM_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            v.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) finishRibbonStripAnim(onEnd);
                        }
                    });
        }
    }

    private void finishRibbonStripAnim(Runnable onEnd) {
        queueMoveRibbonAnimating = false;
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            View row = findRibbonSlotRow(slot);
            if (row == null) continue;
            row.animate().cancel();
            row.setTranslationY(0f);
        }
        if (onEnd != null) onEnd.run();
    }

    private void exitQueueMoveRibbon() {
        queueMoveRibbonActive = false;
        queueMoveRibbonAnimating = false;
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
        optionsListVisible = labels.length > 0;
        this.focusZone = labels.length == 0 ? FocusZone.QUICK_BAR : FocusZone.TIER_CONTENT;

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (8 * density);
        panel.setPadding(padH, (int) (8 * density), padH, (int) (6 * density));
        panel.setBackground(buildPanelBackground());

        boolean hasQuick = quickItems.length > 0 && !dialogStyle;
        boolean hasTitle = title != null && title.length() > 0;
        if (hasQuick || hasTitle) {
            titleRow = new LinearLayout(activity);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            populateTitleRow(title, hasQuick, density);
            LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleRowLp.bottomMargin = labels.length > 0 ? (int) (4 * density) : 0;
            panel.addView(titleRow, titleRowLp);
        }

        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = new TextView(activity);
            sub.setText(subtitle);
            sub.setTypeface(ThemeManager.getCustomFont());
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size) * 0.82f);
            ThemeManager.applyThemedTextStyle(sub, ThemeManager.getHintTextColor());
            sub.setMaxLines(4);
            sub.setPadding(0, 0, 0, (int) (8 * density));
            panel.addView(sub);
        }

        itemsScroll = new ScrollView(activity);
        itemsScroll.setFillViewport(false);
        itemsScroll.setVerticalScrollBarEnabled(false);
        maxListHeightPx = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        rebuildListRows(itemIconKeys, itemStateTexts);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(itemsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateListHeightToContent();

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
                refreshAll();
                scrollFocusIntoView();
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
        replaceListContent(title, itemLabels, itemIconKeys, itemStateTexts, itemHeaders, listener, true);
    }

    public void replaceListContent(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus) {
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        String preserveLabel = null;
        if (!resetFocus && labels != null && focusIndex >= 0 && focusIndex < labels.length
                && !isHeaderRow(focusIndex)) {
            preserveLabel = labels[focusIndex];
        }
        FocusZone priorZone = focusZone;
        this.labels = itemLabels != null ? itemLabels : new String[0];
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        optionsListVisible = this.labels.length > 0;
        if (resetFocus) {
            this.focusIndex = firstFocusableIndex(0);
            focusZone = FocusZone.TIER_CONTENT;
        } else if (preserveLabel != null) {
            int idx = indexForFocusableLabel(preserveLabel);
            this.focusIndex = idx >= 0 ? idx : clampFocusableIndex(focusIndex);
            if (priorZone == FocusZone.QUICK_BAR || priorZone == FocusZone.OPTIONS_TITLE) {
                focusZone = priorZone;
            } else {
                focusZone = FocusZone.TIER_CONTENT;
            }
        } else {
            this.focusIndex = clampFocusableIndex(focusIndex);
            if (priorZone == FocusZone.QUICK_BAR || priorZone == FocusZone.OPTIONS_TITLE) {
                focusZone = priorZone;
            } else {
                focusZone = FocusZone.TIER_CONTENT;
            }
        }
        setPanelTitle(title);
        prepareListPanelVisible();
        rebuildListRows(itemIconKeys, itemStateTexts);
        refreshAll();
        updateListHeightToContent();
        if (focusZone == FocusZone.TIER_CONTENT) {
            scrollFocusIntoView();
        }
        requestOverlayFocus();
    }

    public void replaceQueueContent(String title, QueueRowSpec[] rows, int focusIndex, int moveFrom) {
        queueMode = true;
        submenuTierOpen = true;
        optionsListVisible = true;
        queueRows = rows != null ? rows : new QueueRowSpec[0];
        queueMoveFrom = moveFrom;
        labels = new String[queueRows.length];
        for (int i = 0; i < queueRows.length; i++) labels[i] = queueRows[i].title;
        rowHeaders = null;
        this.focusIndex = Math.max(0, Math.min(focusIndex, Math.max(0, queueRows.length - 1)));
        focusZone = FocusZone.TIER_CONTENT;
        setPanelTitle(title);
        queueRowHeightPx = rowHeightPx;
        invalidateQueueRowBgCache();
        rebuildQueueList();
        refreshAll();
        updateListHeightToContent();
        scrollFocusIntoView();
        requestOverlayFocus();
    }

    private int queueNowPlayingIndex() {
        for (int i = 0; i < queueRows.length; i++) {
            if (queueRows[i].nowPlaying) return i;
        }
        return -1;
    }

    private static int queuePlaybackStateIcon(boolean playing) {
        return playing ? R.drawable.ic_play : R.drawable.ic_pause;
    }

    public boolean isQueueMode() {
        return queueMode;
    }

    /** Update now-playing indicators without rebuilding row views. */
    public void refreshQueuePlayingState(QueueRowSpec[] rows) {
        if (!queueMode || rows == null) return;
        queueRows = rows;
        int start = 0;
        int end = queueRows.length;
        if (isQueueMoveActive() && queueMoveRibbonActive) {
            int moveIdx = queueMoveFrom;
            int[] indices = {
                    QueueMoveWindow.ribbonAboveIndex(moveIdx),
                    moveIdx,
                    QueueMoveWindow.ribbonBelowIndex(moveIdx, queueRows.length)
            };
            for (int idx : indices) {
                if (idx < 0) continue;
                View row = findQueueRowByIndex(idx);
                if (row == null) continue;
                ImageView pp = (ImageView) row.findViewWithTag(TAG_QUEUE_PP);
                if (pp == null) continue;
                if (queueRows[idx].nowPlaying) {
                    pp.setImageResource(queuePlaybackStateIcon(queueRows[idx].playing));
                    pp.setColorFilter(textNormal(), PorterDuff.Mode.SRC_ATOP);
                    pp.setVisibility(View.VISIBLE);
                } else {
                    pp.setVisibility(View.GONE);
                }
            }
            return;
        }
        if (useQueueBrowseVirtual()) {
            start = queueBrowseWindowStart;
            end = queueBrowseWindowEnd();
        }
        for (int i = start; i < end; i++) {
            View row = findQueueRowByIndex(i);
            if (row == null) continue;
            ImageView pp = (ImageView) row.findViewWithTag(TAG_QUEUE_PP);
            if (pp == null) continue;
            if (queueRows[i].nowPlaying) {
                pp.setImageResource(queuePlaybackStateIcon(queueRows[i].playing));
                pp.setColorFilter(textNormal(), PorterDuff.Mode.SRC_ATOP);
                pp.setVisibility(View.VISIBLE);
            } else {
                pp.setVisibility(View.GONE);
            }
        }
    }

    public int queueMoveFrom() {
        return queueMoveFrom;
    }

    public int focusIndex() {
        return focusIndex;
    }

    public int quickFocusIndex() {
        return quickFocusIndex;
    }

    /** Label of the focused list row, or null for headers. */
    public String focusItemLabel() {
        if (labels == null || focusIndex < 0 || focusIndex >= labels.length) return null;
        if (isHeaderRow(focusIndex)) return null;
        return labels[focusIndex];
    }

    private int indexForFocusableLabel(String label) {
        if (labels == null || label == null) return -1;
        for (int i = 0; i < labels.length; i++) {
            if (!isHeaderRow(i) && label.equals(labels[i])) return i;
        }
        return -1;
    }

    public void setQueueMoveFrom(int moveFrom) {
        int prevMove = queueMoveFrom;
        queueMoveFrom = moveFrom;
        if (moveFrom >= 0) {
            focusIndex = moveFrom;
            focusZone = FocusZone.TIER_CONTENT;
        }
        if (!queueMode || itemsHost == null) {
            refreshAll();
            return;
        }
        if (prevMove < 0 && moveFrom >= 0) {
            updateListHeightToContent();
            enterQueueMoveRibbon();
            bindQueueMoveRibbon(0);
        } else if (moveFrom >= 0) {
            bindQueueMoveRibbon(0);
        } else if (prevMove >= 0 && moveFrom < 0) {
            exitQueueMoveRibbon();
            rebuildQueueList();
            updateListHeightToContent();
            scrollQueueViewportForIndex(focusIndex);
        }
    }

    public void focusQuickBar(int index) {
        if (quickBarHost == null || quickItems.length == 0) return;
        quickFocusIndex = Math.max(0, Math.min(index, quickItems.length - 1));
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    /** Hide list/queue tier; keep quick toggles visible and focused (no Options rows). */
    public void showQuickBarOnly(int quickIndex) {
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        labels = new String[0];
        rowHeaders = null;
        focusIndex = 0;
        if (itemsHost != null) itemsHost.removeAllViews();
        if (sliderBar != null) sliderBar.setVisibility(View.GONE);
        if (sliderLabel != null) sliderLabel.setVisibility(View.GONE);
        if (itemsScroll != null) {
            itemsScroll.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                itemsScroll.setLayoutParams(lp);
            }
        }
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        quickFocusIndex = Math.max(0, Math.min(quickIndex, Math.max(0, quickItems.length - 1)));
        quickReturnIndex = quickFocusIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    /** Fast wheel reorder — reorder specs in place, animate ribbon. */
    public void applyQueueReorderLive(int from, int to, int nowPlayingIndex, boolean playing) {
        if (!queueMode || itemsHost == null || from < 0 || to < 0
                || from >= queueRows.length || to >= queueRows.length || from == to) {
            return;
        }
        reorderQueueSpecs(from, to);
        syncQueueNowPlayingFlags(nowPlayingIndex, playing);
        queueMoveFrom = to;
        focusIndex = to;
        focusZone = FocusZone.TIER_CONTENT;
        bindQueueMoveRibbon(to - from);
    }

    private void reorderQueueSpecs(int from, int to) {
        QueueRowSpec[] next = new QueueRowSpec[queueRows.length];
        QueueRowSpec moved = queueRows[from];
        if (from < to) {
            System.arraycopy(queueRows, 0, next, 0, from);
            System.arraycopy(queueRows, from + 1, next, from, to - from);
            next[to] = moved;
            System.arraycopy(queueRows, to + 1, next, to + 1, queueRows.length - to - 1);
        } else {
            System.arraycopy(queueRows, 0, next, 0, to);
            next[to] = moved;
            System.arraycopy(queueRows, to, next, to + 1, from - to);
            System.arraycopy(queueRows, from + 1, next, from + 1, queueRows.length - from - 1);
        }
        queueRows = next;
    }

    private void syncQueueNowPlayingFlags(int nowPlayingIndex, boolean playing) {
        for (int i = 0; i < queueRows.length; i++) {
            QueueRowSpec o = queueRows[i];
            boolean np = nowPlayingIndex >= 0 && i == nowPlayingIndex;
            boolean pl = playing && np;
            if (o.nowPlaying != np || o.playing != pl) {
                queueRows[i] = new QueueRowSpec(o.title, o.subtitle, np, pl);
            }
        }
    }

    /** Reorder queue row views in place — keeps scroll position stable while moving. */
    public void applyQueueReorder(QueueRowSpec[] rows, int from, int to) {
        if (!queueMode || rows == null || from < 0 || to < 0 || from >= rows.length || to >= rows.length) {
            return;
        }
        queueRows = rows;
        queueMoveFrom = to;
        focusIndex = to;
        focusZone = FocusZone.TIER_CONTENT;
        if (isQueueMoveActive()) {
            bindQueueMoveRibbon(0);
        } else {
            rebuildQueueList();
            refreshAll();
            updateListHeightToContent();
            scrollQueueRowToViewportSlotImmediate(to);
        }
    }

    private void scrollQueueRowIntoViewImmediate(final int index) {
        scrollQueueRowIntoViewNow(index);
        if (itemsScroll == null || itemsHost == null) return;
        itemsHost.post(new Runnable() {
            @Override
            public void run() {
                scrollQueueRowIntoViewNow(index);
                itemsScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollQueueRowIntoViewNow(index);
                    }
                });
            }
        });
    }

    private void scrollQueueRowToCenterImmediate(final int index) {
        scrollQueueRowToViewportSlotImmediate(index);
    }

    private void scrollQueueRowToCenterNow(int index) {
        scrollQueueRowToViewportSlotNow(index);
    }

    private void scrollQueueRowIntoViewNow(int index) {
        if (itemsScroll == null || itemsHost == null) return;
        if (queueMode) {
            if (isQueueMoveActive()) return;
            if (index < 0 || index >= queueRows.length) return;
            scrollQueueRowToViewportSlotNow(index);
            return;
        }
        if (index < 0 || index >= itemsHost.getChildCount()) return;
        View row = itemsHost.getChildAt(index);
        int rowTop = row.getTop();
        int rowBottom = row.getBottom();
        int scrollY = itemsScroll.getScrollY();
        int viewport = itemsScroll.getHeight();
        if (viewport <= 0) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.max(1, (int) (2 * density));
        if (rowTop < scrollY + pad) {
            itemsScroll.scrollTo(0, Math.max(0, rowTop - pad));
        } else if (rowBottom > scrollY + viewport - pad) {
            int target = rowBottom - viewport + pad;
            int maxScroll = Math.max(0, itemsHost.getHeight() - viewport);
            itemsScroll.scrollTo(0, Math.min(Math.max(0, target), maxScroll));
        }
    }

    private void bindQueueRowIndicesAndText() {
        if (itemsHost == null) return;
        for (int i = 0; i < itemsHost.getChildCount(); i++) {
            View row = itemsHost.getChildAt(i);
            row.setTag(Integer.valueOf(i));
            if (i < queueRows.length) {
                TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
                if (title != null) title.setText(queueRows[i].displayLine());
            }
        }
    }

    public void setQueueFocusIndex(int index) {
        if (queueRows.length == 0) return;
        int prev = focusIndex;
        int next = Math.max(0, Math.min(index, queueRows.length - 1));
        if (next == prev && focusZone == FocusZone.TIER_CONTENT) return;
        focusIndex = next;
        focusZone = FocusZone.TIER_CONTENT;
        if (queueMode) ensureQueueBrowseWindowForFocus();
        if (queueMode && itemsHost != null) {
            if (prev >= 0 && prev != focusIndex) refreshQueueRowAt(prev);
            refreshQueueRowAt(focusIndex);
            if (queueMoveFrom >= 0 && queueMoveFrom != focusIndex && queueMoveFrom != prev) {
                refreshQueueRowAt(queueMoveFrom);
            }
        } else {
            refreshAll();
        }
        scrollFocusIntoView();
    }

    private void rebuildQueueList() {
        if (itemsHost == null || !queueMode || isQueueMoveActive()) return;
        if (useQueueBrowseVirtual()) rebuildQueueBrowseWindow();
        else rebuildQueueRows();
    }

    private int queueBrowseVisibleRows() {
        int slotH = queueRowSlotHeight();
        if (slotH <= 0) return 5;
        return Math.max(3, (queueViewportHeight() + slotH - 1) / slotH);
    }

    private boolean useQueueBrowseVirtual() {
        return queueMode && !isQueueMoveActive()
                && queueRows.length >= QueueBrowseWindow.VIRTUAL_MIN_ROWS;
    }

    private int queueBrowseWindowEnd() {
        return QueueBrowseWindow.windowEnd(queueBrowseWindowStart, queueRows.length,
                queueBrowseVisibleRows(), QueueBrowseWindow.ROW_BUFFER);
    }

    private void ensureQueueBrowseWindowForFocus() {
        if (!useQueueBrowseVirtual()) return;
        int end = queueBrowseWindowEnd();
        if (focusIndex < queueBrowseWindowStart || focusIndex >= end) {
            rebuildQueueBrowseWindow();
        }
    }

    private void rebuildQueueBrowseWindow() {
        if (itemsHost == null) return;
        int count = queueRows.length;
        int visible = queueBrowseVisibleRows();
        int buffer = QueueBrowseWindow.ROW_BUFFER;
        queueBrowseWindowStart = QueueBrowseWindow.windowStart(focusIndex, count, visible, buffer);
        int end = QueueBrowseWindow.windowEnd(queueBrowseWindowStart, count, visible, buffer);
        float density = activity.getResources().getDisplayMetrics().density;
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        int slotH = queueRowSlotHeight();
        itemsHost.removeAllViews();
        if (queueBrowseWindowStart > 0) {
            View topSpacer = new View(activity);
            topSpacer.setFocusable(false);
            itemsHost.addView(topSpacer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, queueBrowseWindowStart * slotH));
        }
        for (int i = queueBrowseWindowStart; i < end; i++) {
            itemsHost.addView(createQueueRow(queueRows[i], i, rowH, density));
        }
        if (end < count) {
            View bottomSpacer = new View(activity);
            bottomSpacer.setFocusable(false);
            itemsHost.addView(bottomSpacer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (count - end) * slotH));
        }
    }

    private void rebuildQueueRows() {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        for (int i = 0; i < queueRows.length; i++) {
            itemsHost.addView(createQueueRow(queueRows[i], i, rowH, density));
        }
    }

    private FrameLayout createQueueRow(QueueRowSpec spec, int index, int rowH, float density) {
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        int slotW = (int) (rowHeightPx * 0.55f);

        FrameLayout row = new FrameLayout(activity);
        row.setTag(Integer.valueOf(index));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);

        TextView title = new TextView(activity);
        title.setTag(TAG_QUEUE_TITLE);
        title.setText(spec.displayLine());
        title.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        titleLp.leftMargin = textPadLeft;
        titleLp.rightMargin = slotW + (int) (8 * density);
        titleLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(title, titleLp);

        android.widget.FrameLayout rightSlot = new android.widget.FrameLayout(activity);
        FrameLayout.LayoutParams slotLp = new FrameLayout.LayoutParams(slotW, rowH);
        slotLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rightSlot.setLayoutParams(slotLp);

        TextView grip = new TextView(activity);
        grip.setTag(TAG_QUEUE_GRIP);
        grip.setText(activity.getString(R.string.home_screen_move_grip));
        grip.setGravity(Gravity.CENTER);
        grip.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        grip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        grip.setVisibility(View.GONE);
        rightSlot.addView(grip, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView pp = new ImageView(activity);
        pp.setTag(TAG_QUEUE_PP);
        pp.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pp.setVisibility(View.GONE);
        int ppSz = (int) (rowHeightPx * 0.42f);
        rightSlot.addView(pp, new android.widget.FrameLayout.LayoutParams(ppSz, ppSz, Gravity.CENTER));

        row.addView(rightSlot);
        return row;
    }

    private void updateListHeightToContent() {
        if (itemsScroll == null || itemsHost == null) return;
        if (queueMode && itemsHost.getPaddingTop() != 0) {
            itemsHost.setPadding(0, 0, 0, 0);
        }
        itemsHost.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentH = itemsHost.getMeasuredHeight();
        int target;
        if (queueMode) {
            target = queueViewportHeight();
        } else {
            target = maxListHeightPx > 0 ? Math.min(contentH, maxListHeightPx) : contentH;
        }
        if (target <= 0) {
            itemsScroll.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                itemsScroll.setLayoutParams(lp);
            }
            return;
        }
        if (!optionsListVisible && quickBarHost != null) {
            collapseOptionsListPanel();
            return;
        }
        itemsScroll.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
        if (lp != null && lp.height != target) {
            lp.height = target;
            itemsScroll.setLayoutParams(lp);
        }
        if (queueMode && !isQueueMoveActive()) {
            scrollQueueViewportForIndex(queueScrollIndex());
        }
    }

    private void scrollQueueViewportForIndex(int index) {
        scrollQueueRowToViewportSlotImmediate(index);
    }

    /** Compact volume overlay — hint line, no quick toggles or list. */
    public void showVolumeOnly(ViewGroup root, String hintText, String sliderTitle, int max, int value,
            int rowHeightPx, int panelWidthPx) {
        dismiss();
        volumeOnlyMode = true;
        queueMode = false;
        labels = new String[0];
        quickItems = new QuickItem[0];
        this.rowHeightPx = rowHeightPx;
        this.panelWidthPx = panelWidthPx;
        this.menuRows = false;
        this.panelBgColor = ThemeManager.getContextMenuPanelColor();
        sliderMax = Math.max(1, max);
        sliderValue = Math.max(0, Math.min(value, sliderMax));

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (8 * density);
        panel.setPadding(padH, (int) (8 * density), padH, (int) (6 * density));
        panel.setBackground(buildPanelBackground());

        if (hintText != null && hintText.length() > 0) {
            TextView hint = new TextView(activity);
            hint.setText(hintText);
            hint.setTypeface(ThemeManager.getCustomFont());
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size) * 0.82f);
            hint.setGravity(Gravity.CENTER);
            ThemeManager.applyThemedTextStyle(hint,
                    ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
            hint.setPadding(0, 0, 0, (int) (6 * density));
            panel.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        sliderLabel = new TextView(activity);
        sliderLabel.setText(sliderTitle != null ? sliderTitle : "");
        sliderLabel.setGravity(Gravity.CENTER);
        ThemeManager.applyThemedTextStyle(sliderLabel,
                ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        panel.addView(sliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sliderBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        sliderBar.setMax(sliderMax);
        sliderBar.setProgress(sliderValue);
        panel.addView(sliderBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (24 * density)));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER;
        overlay.addView(panel, panelLp);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        focusZone = FocusZone.SLIDER;
    }

    /**
     * Morph volume-only overlay into full context menu without tearing down the panel.
     * Keeps the volume slider visible; quick bar lands on {@code volumeQuickIndex}.
     */
    public void expandFromVolumeOnly(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener,
            QuickItem[] quickBar, QuickBarListener quickListener, boolean menuStyleRows,
            int volumeQuickIndex) {
        if (!volumeOnlyMode || panel == null || sliderBar == null) return;
        volumeOnlyMode = false;
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        this.labels = itemLabels != null ? itemLabels : new String[0];
        this.rowHeaders = itemHeaders;
        this.listener = listener;
        this.quickListener = quickListener;
        this.quickItems = quickBar != null ? quickBar : new QuickItem[0];
        this.menuRows = menuStyleRows;
        this.dialogStyle = false;
        this.focusIndex = firstFocusableIndex(0);

        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = panel.getChildCount() - 1; i >= 0; i--) {
            View child = panel.getChildAt(i);
            if (child != sliderLabel && child != sliderBar) {
                panel.removeViewAt(i);
            }
        }

        boolean hasQuick = quickItems.length > 0;
        boolean hasTitle = title != null && title.length() > 0;
        if (hasQuick || hasTitle) {
            titleRow = new LinearLayout(activity);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            populateTitleRow(title, hasQuick, density);
            LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleRowLp.bottomMargin = labels.length > 0 ? (int) (4 * density) : 0;
            panel.addView(titleRow, 0, titleRowLp);
        }

        itemsScroll = new ScrollView(activity);
        itemsScroll.setFillViewport(false);
        itemsScroll.setVerticalScrollBarEnabled(false);
        itemsScroll.setVisibility(View.GONE);
        maxListHeightPx = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        rebuildListRows(itemIconKeys, itemStateTexts);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int sliderIdx = panel.indexOfChild(sliderLabel);
        if (sliderIdx < 0) sliderIdx = panel.getChildCount();
        panel.addView(itemsScroll, sliderIdx, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        optionsListVisible = false;
        collapseOptionsListPanel();

        if (sliderLabel != null) {
            sliderLabel.setVisibility(View.VISIBLE);
            ThemeManager.applyThemedTextStyle(sliderLabel,
                    ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        }
        sliderBar.setVisibility(View.VISIBLE);
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        quickFocusIndex = volumeQuickIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    public boolean isVolumeOnlyMode() {
        return volumeOnlyMode;
    }

    public void updateVolumeSlider(int value, int max) {
        sliderMax = Math.max(1, max);
        sliderValue = Math.max(0, Math.min(value, sliderMax));
        if (sliderBar != null) {
            sliderBar.setMax(sliderMax);
            sliderBar.setProgress(sliderValue);
        }
    }

    public void showSlider(String label, int max, int value) {
        showSliderWithQuickBarFocus(label, max, value, quickReturnIndex >= 0
                ? quickReturnIndex : CONTEXT_QUICK_VOLUME_FALLBACK);
    }

    private static final int CONTEXT_QUICK_VOLUME_FALLBACK = 3;

    /** Full context menu with slider visible and a quick-bar item pre-highlighted. */
    public void showSliderWithQuickBarFocus(String label, int max, int value, int quickIndex) {
        volumeOnlyMode = false;
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
        optionsListVisible = false;
        collapseOptionsListPanel();
        quickFocusIndex = quickIndex;
        quickReturnIndex = quickIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    public void hideSlider() {
        if (sliderBar != null) sliderBar.setVisibility(View.GONE);
        if (sliderLabel != null) sliderLabel.setVisibility(View.GONE);
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        if (focusZone == FocusZone.SLIDER) {
            focusZone = (optionsListVisible || queueMode)
                    ? FocusZone.TIER_CONTENT : FocusZone.QUICK_BAR;
        }
        refreshAll();
        if (isMenuListZone() && optionsListVisible) {
            scrollFocusIntoView();
        }
        requestOverlayFocus();
    }

    /** Focus the root Options action list (hides an open volume slider if needed). */
    public void focusOptionsList() {
        if (labels == null || labels.length == 0) return;
        enterOptionsListFromTitle();
    }

    public int sliderValue() {
        return sliderValue;
    }

    public void adjustSlider(int delta) {
        sliderValue = Math.max(0, Math.min(sliderMax, sliderValue + delta));
        if (sliderBar != null) sliderBar.setProgress(sliderValue);
    }

    /** Full-screen progress overlay for OTA download (non-dismissible). */
    public void showProgressOverlay(ViewGroup root, String title, String subtitle, int max) {
        dismiss();
        sliderMax = Math.max(1, max);
        sliderValue = 0;
        panelBgColor = ThemeManager.getContextMenuPanelColor();
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
            panel.addView(tv);
        }
        sliderLabel = new TextView(activity);
        sliderLabel.setGravity(Gravity.CENTER);
        sliderLabel.setPadding(0, (int) (8 * density), 0, (int) (4 * density));
        sliderLabel.setText(subtitle != null ? subtitle : "");
        ThemeManager.applyThemedTextStyle(sliderLabel,
                ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        panel.addView(sliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sliderBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        sliderBar.setMax(sliderMax);
        sliderBar.setProgress(0);
        panel.addView(sliderBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (24 * density)));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER;
        panelLp.leftMargin = (int) (10 * density);
        panelLp.rightMargin = panelLp.leftMargin;
        overlay.addView(panel, panelLp);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        focusZone = FocusZone.SLIDER;
    }

    public void updateProgressOverlay(int value, String statusText) {
        sliderValue = Math.max(0, Math.min(sliderMax, value));
        if (sliderBar != null) sliderBar.setProgress(sliderValue);
        if (sliderLabel != null && statusText != null) sliderLabel.setText(statusText);
    }

    public void dismiss() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
        panel = null;
        titleRow = null;
        titleChip = null;
        titleView = null;
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
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        exitQueueMoveRibbon();
        volumeOnlyMode = false;
        optionsListVisible = true;
        submenuTierOpen = false;
        quickReturnIndex = -1;
    }

    private void recoverFocusFromCollapsedList() {
        if (quickBarHost != null && quickBarHost.getChildCount() > 0) {
            focusQuickBarFromListExit();
        } else if (titleView != null && labels != null && labels.length > 0) {
            focusOptionsTitle();
        }
    }

    private void rerouteWhenListCollapsed(int delta) {
        if (delta > 0 && labels != null && labels.length > 0) {
            enterListFromQuickBar();
        } else if (delta < 0) {
            recoverFocusFromCollapsedList();
        }
    }

    public boolean handleKeyHorizontal(int keyCode) {
        if (!isShowing()) return false;
        if (isMenuListZone() && !optionsListVisible && !queueMode) {
            recoverFocusFromCollapsedList();
        }
        boolean hasListItems = labels != null && labels.length > 0;
        if (focusZone == FocusZone.OPTIONS_TITLE) {
            if (keyCode == 22 && quickBarHost != null && quickBarHost.getChildCount() > 0) {
                focusZone = FocusZone.QUICK_BAR;
                quickFocusIndex = quickBarReturnIndex();
                refreshAll();
                scrollQuickFocusIntoView();
                return true;
            }
            if (keyCode == 21) return true;
            return false;
        }
        if (keyCode == 21) {
            if (isMenuListZone() && !isQueueMoveActive() && focusIndex == firstFocusableIndex(0)) {
                focusQuickBarFromListExit();
                return true;
            }
            if (focusZone == FocusZone.QUICK_BAR) {
                moveQuickFocus(-1);
                return true;
            }
            if (focusZone == FocusZone.SLIDER) return false;
        }
        if (keyCode == 22) {
            if (focusZone == FocusZone.QUICK_BAR) {
                int last = lastVisibleQuickIndex();
                if (quickFocusIndex >= last && hasListItems) {
                    enterListFromQuickBar();
                } else {
                    moveQuickFocus(1);
                }
                return true;
            }
            if (focusZone == FocusZone.SLIDER) return false;
        }
        return false;
    }

    public void moveFocus(int delta) {
        if (delta == 0) return;
        if (focusZone == FocusZone.SLIDER) {
            if (!volumeOnlyMode && quickBarHost != null && quickBarHost.getChildCount() > 0) {
                focusZone = FocusZone.QUICK_BAR;
                quickFocusIndex = quickBarReturnIndex();
                refreshAll();
                scrollQuickFocusIntoView();
            }
            return;
        }
        if (focusZone == FocusZone.OPTIONS_TITLE) {
            if (delta > 0 && labels != null && labels.length > 0) {
                enterOptionsListFromTitle();
            } else if (delta < 0 && quickBarHost != null && quickBarHost.getChildCount() > 0) {
                focusZone = FocusZone.QUICK_BAR;
                quickFocusIndex = quickBarReturnIndex();
                refreshAll();
                scrollQuickFocusIntoView();
            }
            return;
        }
        if (queueMode && focusZone == FocusZone.TIER_CONTENT && isQueueMoveActive()) {
            return;
        }
        if (focusZone == FocusZone.QUICK_BAR) {
            if (delta > 0 && labels != null && labels.length > 0) {
                enterListFromQuickBar();
                return;
            }
            moveQuickFocus(delta);
            return;
        }
        if (isMenuListZone()) {
            if (!optionsListVisible && !queueMode) {
                rerouteWhenListCollapsed(delta);
                return;
            }
            if (labels == null || labels.length == 0) return;
            if (delta < 0 && focusIndex == firstFocusableIndex(0)
                    && quickBarHost != null && quickBarHost.getChildCount() > 0) {
                focusQuickBarFromListExit();
                return;
            }
            focusZone = FocusZone.TIER_CONTENT;
            int next = nextFocusableIndex(focusIndex, delta);
            if (next < 0 || next == focusIndex) return;
            focusIndex = next;
            if (queueMode) ensureQueueBrowseWindowForFocus();
            refreshAll();
            scrollFocusIntoView();
        }
    }

    private void moveQuickFocus(int delta) {
        int[] vis = visibleQuickIndices();
        if (vis.length == 0) return;
        int pos = 0;
        for (int i = 0; i < vis.length; i++) {
            if (vis[i] == quickFocusIndex) { pos = i; break; }
        }
        if (delta < 0 && pos == 0) {
            if (quickListener != null) {
                quickListener.onFocusOptionsTitle();
            } else if (titleView != null && labels != null && labels.length > 0) {
                focusOptionsTitle();
            }
            return;
        }
        int nextPos = pos + delta;
        if (nextPos < 0 || nextPos >= vis.length) return;
        quickFocusIndex = vis[nextPos];
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
    }

    public void activateFocused() {
        if (focusZone == FocusZone.OPTIONS_TITLE) {
            enterOptionsListFromTitle();
            return;
        }
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

    private int clampFocusableIndex(int idx) {
        if (labels == null || labels.length == 0) return 0;
        idx = Math.max(0, Math.min(idx, labels.length - 1));
        if (!isHeaderRow(idx)) return idx;
        int next = nextFocusableIndex(idx, 1);
        return next >= 0 ? next : firstFocusableIndex(0);
    }

    private void setPanelTitle(String title) {
        if (titleView == null || title == null) return;
        titleView.setText(title);
    }

    private void scrollFocusIntoView() {
        if (itemsScroll == null || itemsHost == null || !isMenuListZone()) return;
        if (queueMode) {
            scrollQueueViewportForIndex(queueScrollIndex());
            return;
        }
        int scrollIdx = focusIndex;
        if (scrollIdx < 0 || scrollIdx >= itemsHost.getChildCount()) return;
        scrollQueueRowIntoViewImmediate(scrollIdx);
    }

    private void scrollQueueRowIntoView(int index) {
        scrollQueueRowIntoViewImmediate(index);
    }

    private void scrollQuickFocusIntoView() {
        if (quickBarHost == null || quickBarScroll == null || focusZone != FocusZone.QUICK_BAR) return;
        if (isFirstVisibleQuickIndex(quickFocusIndex)) {
            scrollQuickBarToStart();
            return;
        }
        for (int i = 0; i < quickBarHost.getChildCount(); i++) {
            View chip = quickBarHost.getChildAt(i);
            Object tag = chip.getTag();
            if (tag instanceof Integer && ((Integer) tag).intValue() == quickFocusIndex) {
                final View target = chip;
                quickBarScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        if (quickBarScroll == null || quickBarHost == null) return;
                        int chipLeft = target.getLeft();
                        int chipRight = target.getRight();
                        int scrollX = quickBarScroll.getScrollX();
                        int viewW = quickBarScroll.getWidth();
                        int maxScroll = Math.max(0, quickBarHost.getWidth() - viewW);
                        int targetScroll = scrollX;
                        if (chipLeft < scrollX) {
                            targetScroll = chipLeft;
                        } else if (chipRight > scrollX + viewW) {
                            targetScroll = chipRight - viewW;
                        }
                        targetScroll = Math.max(0, Math.min(targetScroll, maxScroll));
                        quickBarScroll.scrollTo(targetScroll, 0);
                    }
                });
                return;
            }
        }
        clampQuickBarScroll();
    }

    private FrameLayout createQuickChip(QuickItem item, int heightPx, int index) {
        float density = activity.getResources().getDisplayMetrics().density;
        int iconSize = (int) (heightPx * 0.62f);
        int chipW = (int) (heightPx * 1.05f);
        FrameLayout chip = new FrameLayout(activity);
        chip.setTag(index);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(chipW, heightPx);
        lp.setMargins((int) (1 * density), 0, (int) (1 * density), 0);
        chip.setLayoutParams(lp);

        ImageView icon = new ImageView(activity);
        icon.setTag("quick_icon");
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
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
        refreshTitleRow();
        refreshSliderChrome();
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
            if (focused) {
                int w = chip.getWidth() > 0 ? chip.getWidth() : (int) (rowHeightPx * 1.05f);
                chip.setBackground(rowBackground(true, w, rowHeightPx));
            } else {
                chip.setBackground(null);
            }
            ImageView icon = (ImageView) chip.findViewWithTag("quick_icon");
            if (icon != null && icon.getDrawable() != null) {
                int quickNormal = ThemeManager.getStatusBarTextColor();
                int quickSelected = textSelected();
                // Keep quick toggles minimalist: only icon tint changes when unfocused.
                icon.setColorFilter(focused ? quickSelected : quickNormal, PorterDuff.Mode.SRC_ATOP);
            }
        }
    }

    /** Swap quick-bar icons/labels without closing the menu (e.g. queue ↔ music library). */
    public void replaceQuickBar(QuickItem[] items) {
        if (volumeOnlyMode || !isShowing()) return;
        quickItems = items != null ? items : new QuickItem[0];
        if (quickBarHost == null) return;
        quickBarHost.removeAllViews();
        for (int i = 0; i < quickItems.length; i++) {
            if (!quickItems[i].visible) continue;
            quickBarHost.addView(createQuickChip(quickItems[i], rowHeightPx, i));
        }
        refreshQuickBar();
    }

    private Drawable buildPanelBackground() {
        float density = activity.getResources().getDisplayMetrics().density;
        float r = ThemeManager.getButtonRadius() * 2f * density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE0202022);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, (int) density), 0x66FFFFFF);
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
        ThemeManager.applyThemedTextStyle(label, textNormal());
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
        if (queueMode) {
            refreshQueueRows();
            return;
        }
        for (int i = 0; i < itemsHost.getChildCount(); i++) {
            View row = itemsHost.getChildAt(i);
            if (row.getTag(TAG_HEADER) instanceof Boolean) continue;
            boolean focused = isMenuListZone() && !queueMode && i == focusIndex;
            TextView label = (TextView) row.getTag(TAG_LABEL);
            ImageView arrow = (ImageView) row.getTag(TAG_ARROW);
            TextView state = (TextView) row.getTag(TAG_STATE);
            int w = panelWidthPx > 0 ? panelWidthPx : row.getWidth();
            row.setBackground(rowBackground(focused, w, rowHeightPx));
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

    private void refreshQueueRows() {
        if (isQueueMoveActive() && queueMoveRibbonActive) {
            bindQueueMoveRibbon(0);
            return;
        }
        if (useQueueBrowseVirtual()) {
            int end = queueBrowseWindowEnd();
            for (int i = queueBrowseWindowStart; i < end; i++) {
                refreshQueueRowAt(i);
            }
            return;
        }
        for (int i = 0; i < queueRows.length; i++) {
            refreshQueueRowAt(i);
        }
    }

    private void refreshQueueRowAt(int queueIndex) {
        if (itemsHost == null || queueIndex < 0 || queueIndex >= queueRows.length) return;
        View row = findQueueRowByIndex(queueIndex);
        if (row == null) return;
        boolean moving = queueMoveFrom >= 0 && queueIndex == queueMoveFrom;
        boolean focused = focusZone == FocusZone.TIER_CONTENT && queueIndex == focusIndex;
        boolean highlighted = focused || moving;
        int w = panelWidthPx > 0 ? panelWidthPx : row.getWidth();
        row.setBackground(rowBackground(highlighted, w, queueRowHeightPx));
        TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
        TextView grip = (TextView) row.findViewWithTag(TAG_QUEUE_GRIP);
        ImageView pp = (ImageView) row.findViewWithTag(TAG_QUEUE_PP);
        int titleColor = highlighted ? textSelected() : textNormal();
        if (title != null) {
            ThemeManager.applyThemedTextStyle(title, titleColor);
            title.setSelected(highlighted);
            if (highlighted) {
                title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                title.setHorizontallyScrolling(true);
            } else {
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setHorizontallyScrolling(false);
            }
        }
        if (grip != null) {
            grip.setVisibility(moving ? View.VISIBLE : View.GONE);
            ThemeManager.applyThemedTextStyle(grip, textSelected());
        }
        if (pp != null && queueRows[queueIndex].nowPlaying) {
            pp.setImageResource(queuePlaybackStateIcon(queueRows[queueIndex].playing));
            pp.setColorFilter(textNormal(), PorterDuff.Mode.SRC_ATOP);
            pp.setVisibility(View.VISIBLE);
        } else if (pp != null) {
            pp.setVisibility(View.GONE);
        }
        ensureQueueDropLine((FrameLayout) row, moving);
    }

    private void ensureQueueDropLine(FrameLayout row, boolean visible) {
        View drop = row.findViewWithTag(TAG_QUEUE_DROP);
        if (visible) {
            if (drop == null) {
                drop = new View(activity);
                drop.setTag(TAG_QUEUE_DROP);
                float density = activity.getResources().getDisplayMetrics().density;
                int h = Math.max(1, (int) (2 * density));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, h);
                lp.gravity = Gravity.BOTTOM;
                row.addView(drop, lp);
            }
            drop.setBackgroundColor(textSelected());
            drop.setVisibility(View.VISIBLE);
        } else if (drop != null) {
            drop.setVisibility(View.GONE);
        }
    }

    private void invalidateQueueRowBgCache() {
        cachedQueueRowBg = null;
        cachedQueueRowBgFocused = null;
        cachedQueueRowBgWidth = 0;
    }

    private Drawable rowBackground(boolean focused, int widthPx, int heightPx) {
        if (!focused) {
            if (queueMode && cachedQueueRowBg != null && widthPx == cachedQueueRowBgWidth) {
                return cachedQueueRowBg;
            }
            GradientDrawable none = new GradientDrawable();
            none.setColor(0x00000000);
            none.setCornerRadius(ThemeManager.getButtonRadius() * activity.getResources().getDisplayMetrics().density);
            if (queueMode) {
                cachedQueueRowBg = none;
                cachedQueueRowBgWidth = widthPx;
            }
            return none;
        }
        if (queueMode && cachedQueueRowBgFocused != null && widthPx == cachedQueueRowBgWidth) {
            return cachedQueueRowBgFocused;
        }
        Drawable scaled = menuRows
                ? ThemeManager.getMenuRowBackgroundScaled(
                        activity.getResources(), true, widthPx, heightPx)
                : ThemeManager.getItemRowBackgroundScaled(
                        activity.getResources(), true, widthPx, heightPx);
        if (scaled != null) {
            if (queueMode) {
                cachedQueueRowBgFocused = scaled;
                cachedQueueRowBgWidth = widthPx;
            }
            return scaled;
        }
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(ThemeManager.getButtonRadius() * activity.getResources().getDisplayMetrics().density);
        g.setColor(ThemeManager.getRowSelectionFillColor());
        if (queueMode) {
            cachedQueueRowBgFocused = g;
            cachedQueueRowBgWidth = widthPx;
        }
        return g;
    }

    private int textNormal() {
        int c = menuRows ? ThemeManager.getSettingMenuTextColorNormal()
                : ThemeManager.getItemTextColorNormal();
        return ThemeManager.ensureReadableOnBackground(c, panelBgColor);
    }

    private int textSelected() {
        int c = menuRows ? ThemeManager.getSettingMenuTextColorSelected()
                : ThemeManager.getItemTextColorSelected();
        return ThemeManager.textOnRowSelection(c, menuRows);
    }
}
