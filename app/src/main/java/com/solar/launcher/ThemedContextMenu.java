package com.solar.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import com.solar.launcher.soulseek.SoulseekCountryFlags;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.ui.ListDrillTransition;
import com.solar.launcher.ui.ModalTransition;
import com.solar.launcher.ui.ScreenTransition;

import org.json.JSONObject;

/** Hold-Back context menu — quick bar, vertical list, slider/tier modes. */
public final class ThemedContextMenu {
    private static final int TAG_LABEL = 0x70ca0001;
    private static final int TAG_ARROW = 0x70ca0002;
    private static final int TAG_STATE = 0x70ca0003;
    private static final int TAG_HEADER = 0x70ca0004;
    private static final int TAG_SCROLL_HEADER = 0x70ca0005;
    private static final int TAG_DETAIL_BODY = 0x70ca0006;
    private static final int TAG_DETAIL_PANEL = 0x70ca0009;
    private static final int TAG_QUEUE_TITLE = 0x70ca0010;
    private static final int TAG_QUEUE_SUB = 0x70ca0011;
    private static final int TAG_QUEUE_GRIP = 0x70ca0012;
    private static final int TAG_QUEUE_PP = 0x70ca0013;
    private static final int TAG_QUEUE_CONFIRM = 0x70ca0015;
    private static final int TAG_RIBBON_SLOT = 0x70ca0014;
    private static final int TAG_QUEUE_DROP = 0x70ca0014;
    private static final int TAG_DECOR = 0x70ca0016;
    private static final int TAG_STATE_SPIN = 0x70ca0017;

    /** Context-menu row: muted label + indeterminate spinner (Wi‑Fi/BT scan footer). */
    public static final String ICON_ROW_LOADING = "__loading__";
    /** Right-column state sentinel — indeterminate spinner while BT A2DP is connecting. */
    public static final String STATE_CONNECTING = "__connecting__";

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
        /** Wheel left from the first quick toggle — highlight Back chip. */
        void onFocusBackChip();
        /** Center on Back — dismiss at root or return from a sub-tier. */
        void onBackActivated();
    }

    private final Activity activity;
    private FrameLayout overlay;
    private LinearLayout panel;
    private LinearLayout titleRow;
    private FrameLayout titleChip;
    private ImageView titleBackIcon;
    private HorizontalScrollView quickBarScroll;
    private LinearLayout quickBarHost;
    private ScrollView itemsScroll;
    private LinearLayout itemsHost;
    private LinearLayout sliderRow;
    private LinearLayout volumeSliderBlock;
    private LinearLayout brightnessSliderBlock;
    private ProgressBar sliderBar;
    private TextView sliderLabel;
    private ProgressBar brightnessBar;
    private TextView brightnessLabel;
    private int brightnessMax = 255;
    private int brightnessValue = 255;
  /** Match MainActivity CONTEXT_QUICK_VOLUME_INDEX / CONTEXT_QUICK_BRIGHTNESS_INDEX (home chip at 0). */
    private int volumeQuickIndex = 7;
    private int brightnessQuickIndex = 6;
    private int volumeQuickIconRes = R.drawable.ic_volume_mid;
    private int brightnessQuickIconRes = R.drawable.ic_brightness_half;
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
    /** Non-interactive status line (e.g. launcher switch in progress) — no list, no slider. */
    private boolean hintOnlyMode;
    /** Volume/brightness tab — quick bar + one slider only; list hidden until user leaves. */
    private boolean mediaSliderExclusive = false;
    private boolean optionsListVisible = true;
    private boolean submenuTierOpen = false;
    private boolean scrollableDetailHeader = false;
    private String detailHeaderText = "";
    /** Dynamic cap so at least one action row peeks below scrollable message body. */
    private int detailHeaderMaxHeightPx = 0;
    private int queueRowHeightPx;
    private int quickReturnIndex = -1;
    /** Last quick-bar chip — scroll-right opens this tier (queue/music) instead of root list. */
    private int primaryEndQuickIndex = -1;
    private static final int QUEUE_MOVE_VISIBLE_ROWS = QueueMoveWindow.VISIBLE_ROWS;
    private Drawable cachedQueueRowBg;
    private Drawable cachedQueueRowBgFocused;
    private int cachedQueueRowBgWidth;
    private static final int QUEUE_MOVE_RIBBON_ANIM_MS = 130;
    private static final int QUEUE_MOVE_RIBBON_ENTER_MS = 150;
    /** Wi-Fi scan row refresh — staggered fade/slide like queue move ribbon. */
    private static final int WIFI_TICKER_STAGGER_MS = 28;
    /** Rows below this index never ticker-animate (toggle + connected / paired sections). */
    private int networkTierAnimateFromIndex = Integer.MAX_VALUE;
    private boolean queueMoveRibbonActive = false;
    private boolean queueMoveRibbonAnimating = false;
    private int queueBrowseWindowStart = 0;
    private int queueConfirmAtIndex = -1;
    private Runnable queueConfirmClearTask;
    private static final int QUEUE_CONFIRM_SHOW_MS = 220;
    private static final int QUEUE_CONFIRM_HOLD_MS = 750;
    /** Bumped in dismiss() so posted layout runnables skip after the menu is torn down. */
    private int dismissGeneration = 0;
    /** True while modal shell enter/exit animation runs. */
    private boolean modalEnterExitAnimating = false;

    public ThemedContextMenu(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    public boolean isEnterExitAnimating() {
        return modalEnterExitAnimating || ModalTransition.isAnimating();
    }

    private void addOverlayWithPresentAnim(ViewGroup root, Runnable afterPresent) {
        if (ModalTransition.enabled(activity)) {
            ScreenTransition.prepareModalPresent(overlay, panel);
        }
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        runModalPresentAfterAdd(afterPresent);
    }

    /** Present animation after overlay is on screen — rest state first, anim on next frame. */
    private void runModalPresentAnimation() {
        if (overlay == null || panel == null) return;
        if (!ModalTransition.enabled(activity)) {
            overlay.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setAlpha(1f);
            return;
        }
        modalEnterExitAnimating = true;
        ScreenTransition.prepareModalPresent(overlay, panel);
        ModalTransition.animatePresent(overlay, panel, new Runnable() {
            @Override
            public void run() {
                modalEnterExitAnimating = false;
            }
        });
    }

    private void runModalPresentAfterAdd(final Runnable after) {
        runModalPresentAnimation();
        if (overlay == null) {
            if (after != null) after.run();
            return;
        }
        overlay.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || overlay == null) return;
                overlay.requestFocus();
                if (after != null) after.run();
            }
        });
    }

    private boolean menuAlive(int generation) {
        return generation == dismissGeneration && isShowing();
    }

    /** Post a runnable that no-ops if dismiss() ran before it executes (USB HOME / screen change). */
    private void postSafe(View anchor, final Runnable action) {
        if (anchor == null || !isShowing()) return;
        final int gen = dismissGeneration;
        anchor.post(new Runnable() {
            @Override
            public void run() {
                if (!menuAlive(gen) || anchor == null) return;
                action.run();
            }
        });
    }

    public FocusZone focusZone() {
        return focusZone;
    }

    public boolean hasVisibleSlider() {
        return isMediaSliderStripVisible();
    }

    public void setMediaSliderQuickIndices(int volumeIndex, int brightnessIndex) {
        boolean changed = volumeQuickIndex != volumeIndex || brightnessQuickIndex != brightnessIndex;
        volumeQuickIndex = volumeIndex;
        brightnessQuickIndex = brightnessIndex;
        if (changed && isShowing() && !volumeOnlyMode) {
            refreshQuickChipIcons();
        }
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
        hideMediaSliderStripViews();
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
    }

    private void collapseOptionsListPanel() {
        if (itemsScroll == null || queueMode) return;
        itemsScroll.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            itemsScroll.setLayoutParams(lp);
        }
    }

    private boolean hasBackChip() {
        return titleChip != null;
    }

    /** Context modal top bar always includes Back (unless dialog-style confirm). */
    private boolean shouldShowContextBackChip() {
        return !dialogStyle && !volumeOnlyMode && !hintOnlyMode;
    }

    public boolean isDialogStyle() {
        return dialogStyle;
    }

    public boolean isHintOnlyMode() {
        return hintOnlyMode;
    }

    private void ensureContextTitleBar(boolean hasQuick) {
        if (panel == null || !shouldShowContextBackChip()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        if (titleRow == null) {
            titleRow = new LinearLayout(activity);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            populateTitleRow(true, hasQuick, density);
            LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleRowLp.bottomMargin = labels != null && labels.length > 0 ? (int) (4 * density) : 0;
            panel.addView(titleRow, 0);
        } else {
            titleRow.setVisibility(View.VISIBLE);
            if (!hasBackChip()) {
                titleRow.removeAllViews();
                populateTitleRow(true, hasQuick, density);
            }
        }
    }

    private void setContextTitleBarVisible(boolean visible) {
        if (titleRow == null) return;
        titleRow.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Highlight Back chip without collapsing tier content. */
    public void focusBackChip() {
        if (titleChip == null) return;
        focusZone = FocusZone.OPTIONS_TITLE;
        scrollQuickBarToStart();
        refreshAll();
        requestOverlayFocus();
    }

    /** True when focus is on the Back chip (not the tier title row). */
    public boolean isBackChipFocused() {
        return focusZone == FocusZone.OPTIONS_TITLE && hasBackChip();
    }

    /** Collapse contextual action rows and highlight Back. */
    public void focusOptionsTitle() {
        if (!hasBackChip()) return;
        if (queueMode) {
            focusBackChip();
            return;
        }
        if (labels == null || labels.length == 0) {
            focusBackChip();
            return;
        }
        optionsListVisible = false;
        collapseOptionsListPanel();
        focusZone = FocusZone.OPTIONS_TITLE;
        refreshAll();
        requestOverlayFocus();
    }

    public void setSubmenuTierOpen(boolean open) {
        submenuTierOpen = open;
    }

    /** First list row is a capped scrollable message/detail block (Reach tiers). */
    public void setScrollableDetailHeader(boolean enabled) {
        scrollableDetailHeader = enabled;
        if (!enabled) {
            detailHeaderText = "";
            detailHeaderMaxHeightPx = 0;
        }
    }

    public boolean isScrollableDetailHeader() {
        return scrollableDetailHeader;
    }

    public boolean isSubmenuTierOpen() {
        return submenuTierOpen;
    }

    /** Expand list panel after quick-bar tab open (may have been collapsed to height 0). */
    public void ensureSubmenuListVisible() {
        if (labels == null || labels.length == 0) return;
        optionsListVisible = true;
        if (itemsScroll != null) {
            itemsScroll.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams lp = itemsScroll.getLayoutParams();
            if (lp != null && lp.height == 0) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                itemsScroll.setLayoutParams(lp);
            }
        }
        updateListHeightToContent();
    }

    /** Enter a quick-bar tab's list — wheel scrolls rows, not the tab row (iPod / STB style). */
    public void focusSubmenuList() {
        if (labels == null || labels.length == 0) return;
        submenuTierOpen = true;
        optionsListVisible = true;
        ensureSubmenuListVisible();
        enterMenuListFocus(firstFocusableIndex(0));
        if (overlay != null) {
            postSafe(overlay, new Runnable() {
                @Override
                public void run() {
                    if (overlay == null) return;
                    ensureSubmenuListVisible();
                    updateListHeightToContent();
                    scrollFocusIntoView();
                    requestOverlayFocus();
                }
            });
        }
    }

    /** Focus a specific tier list row (queue tutorial "Got it" below scrollable intro). */
    public void focusTierRow(int index) {
        if (labels == null || labels.length == 0) return;
        submenuTierOpen = true;
        optionsListVisible = true;
        enterMenuListFocus(index);
        focusZone = FocusZone.TIER_CONTENT;
        ensureSubmenuListVisible();
        refreshAll();
        scrollFocusIntoView();
        requestOverlayFocus();
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
        if (titleRow != null) setContextTitleBarVisible(true);
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
            enterOptionsListFromTitle();
            return;
        }
        int idx = focusIndex >= 0 ? clampFocusableIndex(focusIndex) : firstFocusableIndex(0);
        enterMenuListFocus(idx);
    }

    /** Wheel down from last quick chip — enter the action list below. */
    public void enterListFromLastQuickChip() {
        if (focusZone != FocusZone.QUICK_BAR) return;
        if (quickFocusIndex != lastVisibleQuickIndex()) return;
        enterListFromQuickBar();
    }

    public boolean isOnLastVisibleQuickChip() {
        return focusZone == FocusZone.QUICK_BAR && quickFocusIndex == lastVisibleQuickIndex();
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

    /** Hide slider and land focus on the volume quick chip. */
    public void leaveVolumeSliderTier(int quickIndex) {
        hideSlider();
        leaveVolumeSliderToQuickBar(quickIndex);
    }

    public boolean isVolumeSliderVisible() {
        return isMediaSliderStripVisible();
    }

    public boolean isMediaSliderStripVisible() {
        return sliderRow != null && sliderRow.getVisibility() == View.VISIBLE;
    }

    private void refreshTitleRow() {
        if (titleChip == null) return;
        boolean titleFocused = focusZone == FocusZone.OPTIONS_TITLE;
        int w = titleChip.getWidth() > 0 ? titleChip.getWidth()
                : (int) (rowHeightPx * 1.05f);
        titleChip.setBackground(rowBackground(titleFocused, w, rowHeightPx));
        if (titleBackIcon != null) {
            int iconColor = titleFocused ? textSelected() : textNormal();
            titleBackIcon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private void scrollQuickBarToStart() {
        if (quickBarScroll == null) return;
        quickBarScroll.scrollTo(0, 0);
    }

    private void clampQuickBarScroll() {
        if (quickBarScroll == null || quickBarHost == null) return;
        postSafe(quickBarScroll, new Runnable() {
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
    private void populateTitleRow(boolean showBackChip, boolean hasQuick, float density) {
        titleChip = null;
        titleBackIcon = null;
        quickBarScroll = null;
        quickBarHost = null;
        if (showBackChip) {
            int iconSize = (int) (rowHeightPx * 0.42f);
            titleChip = new FrameLayout(activity);
            titleBackIcon = new ImageView(activity);
            titleBackIcon.setImageResource(R.drawable.ic_back);
            titleBackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
            iconLp.gravity = Gravity.CENTER;
            titleChip.addView(titleBackIcon, iconLp);
            titleChip.setContentDescription(activity.getString(R.string.context_back));
            titleChip.setMinimumWidth((int) (rowHeightPx * 1.05f));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    hasQuick ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx, 0f);
            if (hasQuick) chipLp.rightMargin = (int) (2 * density);
            titleRow.addView(titleChip, chipLp);
        }
        if (hasQuick) {
            quickBarScroll = new HorizontalScrollView(activity);
            quickBarScroll.setHorizontalScrollBarEnabled(false);
            quickBarScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            quickBarScroll.setFillViewport(!showBackChip);
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
                    showBackChip ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx, showBackChip ? 1f : 0f);
            titleRow.addView(quickBarScroll, qLp);
        }
    }

    private void hideMediaSliderStripViews() {
        if (sliderRow != null) sliderRow.setVisibility(View.GONE);
    }

    private void addMediaSliderRowToPanel(LinearLayout targetPanel, float density) {
        int sliderH = (int) (24 * density);
        sliderRow = new LinearLayout(activity);
        sliderRow.setOrientation(LinearLayout.VERTICAL);
        sliderRow.setVisibility(View.GONE);

        volumeSliderBlock = new LinearLayout(activity);
        volumeSliderBlock.setOrientation(LinearLayout.VERTICAL);
        sliderLabel = new TextView(activity);
        sliderLabel.setGravity(Gravity.CENTER);
        sliderLabel.setSingleLine(true);
        sliderLabel.setEllipsize(TextUtils.TruncateAt.END);
        ThemeManager.applyThemedTextStyle(sliderLabel,
                ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        volumeSliderBlock.addView(sliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sliderBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        sliderBar.setMax(sliderMax);
        volumeSliderBlock.addView(sliderBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, sliderH));

        brightnessSliderBlock = new LinearLayout(activity);
        brightnessSliderBlock.setOrientation(LinearLayout.VERTICAL);
        brightnessLabel = new TextView(activity);
        brightnessLabel.setGravity(Gravity.CENTER);
        brightnessLabel.setSingleLine(true);
        brightnessLabel.setEllipsize(TextUtils.TruncateAt.END);
        ThemeManager.applyThemedTextStyle(brightnessLabel,
                ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
        brightnessSliderBlock.addView(brightnessLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        brightnessBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        brightnessBar.setMax(brightnessMax);
        brightnessSliderBlock.addView(brightnessBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, sliderH));

        sliderRow.addView(volumeSliderBlock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sliderRow.addView(brightnessSliderBlock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        targetPanel.addView(sliderRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void syncMediaSliderBlockVisibility() {
        if (sliderRow == null || sliderRow.getVisibility() != View.VISIBLE) return;
        boolean volActive;
        boolean brightActive;
        if (mediaSliderExclusive) {
            volActive = quickFocusIndex == volumeQuickIndex;
            brightActive = quickFocusIndex == brightnessQuickIndex;
        } else {
            volActive = focusZone == FocusZone.SLIDER
                    || (focusZone == FocusZone.QUICK_BAR && quickFocusIndex == volumeQuickIndex);
            brightActive = focusZone == FocusZone.QUICK_BAR && quickFocusIndex == brightnessQuickIndex;
        }
        if (volumeSliderBlock != null) {
            volumeSliderBlock.setVisibility(volActive ? View.VISIBLE : View.GONE);
        }
        if (brightnessSliderBlock != null) {
            brightnessSliderBlock.setVisibility(brightActive ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshSliderChrome() {
        if (!isMediaSliderStripVisible() || sliderBar == null || brightnessBar == null) return;
        syncMediaSliderBlockVisibility();
        boolean volActive = focusZone == FocusZone.SLIDER
                || (focusZone == FocusZone.QUICK_BAR && quickFocusIndex == volumeQuickIndex);
        boolean brightActive = focusZone == FocusZone.QUICK_BAR && quickFocusIndex == brightnessQuickIndex;
        sliderBar.setAlpha(1f);
        brightnessBar.setAlpha(1f);
        if (sliderLabel != null) {
            ThemeManager.applyThemedTextStyle(sliderLabel,
                    volActive ? textSelected()
                            : ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
        }
        if (brightnessLabel != null) {
            ThemeManager.applyThemedTextStyle(brightnessLabel,
                    brightActive ? textSelected()
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

    /** Move highlight to the last quick-bar icon (Volume) when leaving the top of the list. */
    private void focusQuickBarFromListTopExit() {
        if (quickBarHost == null || quickBarHost.getChildCount() == 0) {
            ensureContextTitleBar(quickItems.length > 0);
            if ((quickBarHost == null || quickBarHost.getChildCount() == 0) && quickItems.length > 0) {
                replaceQuickBar(quickItems);
            }
        }
        if (quickBarHost == null || quickBarHost.getChildCount() == 0) {
            if (hasBackChip()) focusBackChip();
            return;
        }
        if (queueMode) {
            focusQuickBarLeavingQueueList(quickBarReturnIndex());
            return;
        }
        if (submenuTierOpen && labels != null && labels.length > 0) {
            optionsListVisible = true;
            if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
            updateListHeightToContent();
        }
        focusZone = FocusZone.QUICK_BAR;
        quickFocusIndex = lastVisibleQuickIndex();
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    /** Move highlight to the quick toggle that opened this tier — list stays visible. */
    private void focusQuickBarFromListExit() {
        if (quickBarHost == null || quickBarHost.getChildCount() == 0) return;
        if (queueMode) {
            focusQuickBarLeavingQueueList(quickBarReturnIndex());
            return;
        }
        focusQuickBarFromListTopExit();
    }

    private int queueRowSlotHeight() {
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        return rowH + 2;
    }

    /** Fixed 3-row queue panel — browse fills all slots; move mode uses center slot until first/last. */
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

    /** Browse mode: anchor first/last; center-lock middle rows only when queue has more than 4 tracks. */
    private int queueBrowseViewportSlot(int index, int count) {
        return QueueBrowseWindow.browseViewportSlot(index, count, QUEUE_MOVE_VISIBLE_ROWS);
    }

    private void applyQueueViewportPadding() {
        if (itemsHost == null || !queueMode || isQueueMoveActive()) return;
        int topPad = QueueBrowseWindow.shortListTopPadding(
                focusIndex, queueRows.length, queueViewportHeight(), queueRowSlotHeight());
        if (itemsHost.getPaddingTop() != topPad) {
            itemsHost.setPadding(0, topPad, 0, 0);
        }
    }

    private int queueScrollMaxY() {
        int slotH = queueRowSlotHeight();
        if (slotH <= 0) return 0;
        int viewport = itemsScroll != null && itemsScroll.getHeight() > 0
                ? itemsScroll.getHeight() : queueViewportHeight();
        int topPad = itemsHost != null ? itemsHost.getPaddingTop() : 0;
        int contentH = topPad + queueRows.length * slotH;
        return Math.max(0, contentH - viewport);
    }

    private int queueScrollViewportSlot(int index, int count) {
        if (queueMode && !isQueueMoveActive()) return queueBrowseViewportSlot(index, count);
        return queueViewportSlotForIndex(index, count);
    }

    /** Scroll offset from absolute queue index — stable before row layout completes. */
    /**
     * Fallback scroll offset from queue index — used before row views are laid out.
     * Prefer {@link #queueBrowseScrollY(int, int, int)} once {@link #findQueueRowByIndex} resolves.
     */
    private int queueScrollTargetY(int index, int count, int viewport) {
        if (index < 0 || count <= 0 || viewport <= 0) return 0;
        int slotH = queueRowSlotHeight();
        if (slotH <= 0) return 0;
        applyQueueViewportPadding();
        int topPad = itemsHost != null ? itemsHost.getPaddingTop() : 0;
        int slot = queueScrollViewportSlot(index, count);
        int maxScroll = queueScrollMaxY();
        return Math.min(Math.max(0, topPad + index * slotH - slot * slotH), maxScroll);
    }

    /**
     * Browse-mode scroll — snap the focused row into its viewport slot using laid-out row tops
     * so three consecutive tracks always fill the panel (no blank slot between neighbours).
     * Move mode uses the 3-slot ribbon instead; do not call this while {@link #isQueueMoveActive()}.
     */
    private int queueBrowseScrollY(int index, int count, int viewport) {
        applyQueueViewportPadding();
        int slot = queueScrollViewportSlot(index, count);
        int slotH = queueRowSlotHeight();
        View row = findQueueRowByIndex(index);
        if (row != null && row.getHeight() > 0) {
            return Math.min(Math.max(0, row.getTop() - slot * slotH), queueScrollMaxY());
        }
        return queueScrollTargetY(index, count, viewport);
    }

    private void scrollQueueRowToViewportSlotNow(int index) {
        if (itemsScroll == null || itemsHost == null) return;
        if (index < 0) return;
        if (queueMode && useQueueBrowseVirtual()) {
            ensureQueueBrowseWindowForFocus();
        }
        int count = queueMode ? queueRows.length : itemsHost.getChildCount();
        if (index >= count) return;
        int viewport = itemsScroll.getHeight();
        if (viewport <= 0) return;
        if (queueMode && !isQueueMoveActive()) {
            int scrollY = queueBrowseScrollY(index, count, viewport);
            itemsScroll.scrollTo(0, scrollY);
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("runId", "post-fix");
                d.put("index", index);
                d.put("count", count);
                d.put("viewport", viewport);
                d.put("scrollY", scrollY);
                d.put("topPad", itemsHost.getPaddingTop());
                d.put("childCount", itemsHost.getChildCount());
                d.put("ribbonActive", queueMoveRibbonActive);
                d.put("slot", queueScrollViewportSlot(index, count));
                View row = findQueueRowByIndex(index);
                d.put("rowTop", row != null ? row.getTop() : -1);
                QueueDebugLog.log("ThemedContextMenu.scrollQueueRowToViewportSlotNow",
                        "scrolled", "H3", d);
            } catch (Exception ignored) {}
            // #endregion
            SolarAdbTest.queueScroll(focusIndex, count,
                    itemsHost.getPaddingTop(), itemsScroll.getScrollY(), queueScrollMaxY(),
                    queueScrollViewportSlot(index, count), queueNowPlayingIndex());
            return;
        }
        View row = queueMode ? findQueueRowByIndex(index) : null;
        if (row == null && index < itemsHost.getChildCount()) {
            row = itemsHost.getChildAt(index);
        }
        if (row == null) return;
        int rowTop = row.getTop();
        int maxScroll = queueScrollMaxY();
        int slot = queueScrollViewportSlot(index, count);
        int slotH = queueRowSlotHeight();
        int target = rowTop - slot * slotH;
        itemsScroll.scrollTo(0, Math.min(Math.max(0, target), maxScroll));
    }

    private void scrollQueueRowToViewportSlotImmediate(final int index) {
        scrollQueueRowToViewportSlotNow(index);
        if (itemsScroll == null || itemsHost == null) return;
        if (queueMode && !isQueueMoveActive()) {
            // Browse: padding / virtual window updates shift row tops — re-scroll after layout.
            postSafe(itemsHost, new Runnable() {
                @Override
                public void run() {
                    scrollQueueRowToViewportSlotNow(index);
                }
            });
            return;
        }
        postSafe(itemsHost, new Runnable() {
            @Override
            public void run() {
                scrollQueueRowToViewportSlotNow(index);
                postSafe(itemsScroll, new Runnable() {
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
        itemsHost.setPadding(0, 0, 0, 0);
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
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("wheelDelta", wheelDelta);
            d.put("ribbonActive", queueMoveRibbonActive);
            d.put("ribbonAnimating", queueMoveRibbonAnimating);
            d.put("moveFrom", queueMoveFrom);
            DebugAgentLog.log(activity, "ThemedContextMenu.bindQueueMoveRibbon",
                    "bindQueueMoveRibbon entry", "H1-H2", d);
        } catch (Exception ignored) {}
        // #endregion
        if (!queueMoveRibbonActive) {
            enterQueueMoveRibbon();
            animateRibbonEnter(queueMoveFrom);
            return;
        }
        final int moveIdx = queueMoveFrom;
        Runnable bind = new Runnable() {
            @Override
            public void run() {
                populateQueueMoveRibbon();
            }
        };
        if (wheelDelta != 0 && !queueMoveRibbonAnimating) {
            animateRibbonStrip(wheelDelta, bind);
        } else if (!queueMoveRibbonAnimating) {
            bind.run();
        }
    }

    /** Fill the 3-slot ribbon for the current mover index. */
    private void populateQueueMoveRibbon() {
        if (!queueMode || itemsHost == null || !isQueueMoveActive()) return;
        final int moveIdx = queueMoveFrom;
        final int count = queueRows.length;
        final int aboveIdx = QueueMoveWindow.ribbonAboveIndex(moveIdx);
        final int belowIdx = QueueMoveWindow.ribbonBelowIndex(moveIdx, count);
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_ABOVE), aboveIdx);
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_CENTER), moveIdx);
        populateRibbonRow(findRibbonSlotRow(QueueMoveWindow.RIBBON_BELOW), belowIdx);
        if (itemsScroll != null) itemsScroll.scrollTo(0, 0);
    }

    /**
     * Ease into move ribbon from browse scroll position — first/last rows slide to center
     * instead of snapping like wheel-driven reorder steps.
     */
    private void animateRibbonEnter(int moveIdx) {
        populateQueueMoveRibbon();
        int browseSlot = queueViewportSlotForIndex(moveIdx, queueRows.length);
        int slotH = queueRowSlotHeight();
        float startTy = (browseSlot - QueueMoveWindow.RIBBON_CENTER) * (float) slotH;
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("moveIdx", moveIdx);
            d.put("count", queueRows.length);
            d.put("browseSlot", browseSlot);
            d.put("slotH", slotH);
            d.put("startTy", startTy);
            d.put("path", Math.abs(startTy) < 0.5f ? "fade" : "slide");
            DebugAgentLog.log(activity, "ThemedContextMenu.animateRibbonEnter",
                    "ribbon enter animation", "H3-H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (Math.abs(startTy) < 0.5f) {
            animateRibbonEnterFade();
            return;
        }
        queueMoveRibbonAnimating = true;
        android.view.animation.DecelerateInterpolator ease =
                new android.view.animation.DecelerateInterpolator(1.35f);
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
                    .setDuration(QUEUE_MOVE_RIBBON_ENTER_MS)
                    .setInterpolator(ease)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                queueMoveRibbonAnimating = false;
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            queueMoveRibbonAnimating = false;
        }
    }

    private void animateRibbonEnterFade() {
        queueMoveRibbonAnimating = true;
        final int[] remaining = new int[] { 0 };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            Object slotTag = row.getTag(TAG_RIBBON_SLOT);
            int ribbonSlot = slotTag instanceof Integer ? ((Integer) slotTag).intValue()
                    : QueueMoveWindow.RIBBON_CENTER;
            final float targetAlpha = ribbonSlot == QueueMoveWindow.RIBBON_CENTER ? 1f : 0.82f;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.setAlpha(0.45f);
            row.animate()
                    .alpha(targetAlpha)
                    .setDuration(QUEUE_MOVE_RIBBON_ENTER_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                queueMoveRibbonAnimating = false;
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            queueMoveRibbonAnimating = false;
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
            ImageView confirm = (ImageView) row.findViewWithTag(TAG_QUEUE_CONFIRM);
            if (confirm != null) confirm.setVisibility(View.GONE);
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

    public boolean isQueueMoveRibbonAnimating() {
        return queueMoveRibbonAnimating;
    }

    /**
     * Cancel move: slide the ribbon back toward the pick index (reverse of enter), then finish.
     */
    public void animateQueueMoveCancelReturn(int currentIdx, int homeIdx, final Runnable onComplete) {
        if (!queueMode || itemsHost == null || !isQueueMoveActive()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (currentIdx == homeIdx) {
            exitQueueMoveRibbon();
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!queueMoveRibbonActive) {
            enterQueueMoveRibbon();
            populateQueueMoveRibbon();
        }
        final int count = queueRows.length;
        int browseSlot = queueViewportSlotForIndex(homeIdx, count);
        int slotH = queueRowSlotHeight();
        float targetTy = (browseSlot - QueueMoveWindow.RIBBON_CENTER) * (float) slotH;
        int steps = Math.abs(currentIdx - homeIdx);
        final int duration = Math.min(200, QUEUE_MOVE_RIBBON_ENTER_MS + steps * 14);
        if (Math.abs(targetTy) < 0.5f) {
            animateQueueMoveCancelFade(onComplete);
            return;
        }
        queueMoveRibbonAnimating = true;
        android.view.animation.DecelerateInterpolator ease =
                new android.view.animation.DecelerateInterpolator(1.35f);
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
                                queueMoveRibbonAnimating = false;
                                exitQueueMoveRibbon();
                                if (onComplete != null) onComplete.run();
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            queueMoveRibbonAnimating = false;
            exitQueueMoveRibbon();
            if (onComplete != null) onComplete.run();
        }
    }

    private void animateQueueMoveCancelFade(final Runnable onComplete) {
        queueMoveRibbonAnimating = true;
        final int[] remaining = new int[] { 0 };
        for (int slot = QueueMoveWindow.RIBBON_ABOVE; slot <= QueueMoveWindow.RIBBON_BELOW; slot++) {
            final View row = findRibbonSlotRow(slot);
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            remaining[0]++;
            row.animate().cancel();
            row.setTranslationY(0f);
            row.animate()
                    .alpha(0.35f)
                    .setDuration(QUEUE_MOVE_RIBBON_ANIM_MS)
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            row.animate().setListener(null);
                            remaining[0]--;
                            if (remaining[0] <= 0) {
                                queueMoveRibbonAnimating = false;
                                exitQueueMoveRibbon();
                                if (onComplete != null) onComplete.run();
                            }
                        }
                    });
        }
        if (remaining[0] <= 0) {
            queueMoveRibbonAnimating = false;
            exitQueueMoveRibbon();
            if (onComplete != null) onComplete.run();
        }
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
        if (!dialogStyle) {
            titleRow = new LinearLayout(activity);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            populateTitleRow(true, hasQuick, density);
            LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleRowLp.bottomMargin = labels.length > 0 ? (int) (4 * density) : 0;
            panel.addView(titleRow, titleRowLp);
        } else if (title != null && title.length() > 0) {
            TextView tit = new TextView(activity);
            tit.setText(title);
            tit.setTypeface(ThemeManager.getCustomFont());
            tit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.y1_menu_text_size) * 0.95f);
            ThemeManager.applyThemedTextStyle(tit,
                    ThemeManager.ensureReadableOnBackground(ThemeManager.getDialogTextColor(), panelBgColor));
            tit.setPadding(0, 0, 0, (int) (4 * density));
            panel.addView(tit, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (subtitle != null && subtitle.length() > 0 && dialogStyle) {
            int subMaxH = VerticalTextMarqueeHelper.defaultMaxHeightPx(activity);
            FrameLayout subPanel = VerticalTextMarqueeHelper.createCappedPanel(activity, subtitle, subMaxH);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.bottomMargin = (int) (4 * density);
            panel.addView(subPanel, subLp);
        }

        itemsScroll = new Y1SafeScrollView(activity);
        itemsScroll.setFillViewport(false);
        Y1ScrollIndicators.applyVerticalScrollView(itemsScroll);
        maxListHeightPx = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        rebuildListRows(itemIconKeys, itemStateTexts);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(itemsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateListHeightToContent();

        addMediaSliderRowToPanel(panel, density);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER;
        overlay.addView(panel, panelLp);

        addOverlayWithPresentAnim(root, new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || overlay == null) return;
                refreshAll();
                if (itemsHost == null || itemsHost.getChildCount() == 0 || !isMenuListZone()) {
                    return;
                }
                scrollFocusIntoView();
            }
        });
        syncMediaQuickIconResFromItems();
        refreshQuickChipIcons();
    }

    private void rebuildListRows(String[] itemIconKeys, String[] itemStateTexts) {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            boolean header = rowHeaders != null && i < rowHeaders.length && rowHeaders[i];
            if (header && scrollableDetailHeader && i == 0) {
                detailHeaderText = labels[i] != null ? labels[i] : "";
                itemsHost.addView(createScrollableDetailHeaderRow());
            } else if (header) {
                String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
                itemsHost.addView(createHeaderRow(labels[i], iconKey));
            } else {
                String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
                String stateText = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
                if (ICON_ROW_LOADING.equals(iconKey)) {
                    itemsHost.addView(createLoadingRow(labels[i]));
                } else {
                    itemsHost.addView(createRow(labels[i], iconKey, stateText));
                }
            }
        }
    }

    public static final int LIST_DRILL_NONE = 0;
    public static final int LIST_DRILL_FORWARD = 1;
    public static final int LIST_DRILL_BACK = 2;

    public void replaceListContent(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener) {
        replaceListContent(title, itemLabels, itemIconKeys, itemStateTexts, itemHeaders, listener, true,
                LIST_DRILL_NONE);
    }

    public void replaceListContent(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus) {
        replaceListContent(title, itemLabels, itemIconKeys, itemStateTexts, itemHeaders, listener,
                resetFocus, LIST_DRILL_NONE);
    }

    public void replaceListContent(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus,
            int listDrill) {
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                applyListContentReplace(title, itemLabels, itemIconKeys, itemStateTexts, itemHeaders,
                        listener, resetFocus);
            }
        };
        if (itemsScroll != null && ModalTransition.enabled(activity)) {
            if (listDrill == LIST_DRILL_FORWARD) {
                ListDrillTransition.push((ViewGroup) itemsScroll, apply);
                return;
            }
            if (listDrill == LIST_DRILL_BACK) {
                ListDrillTransition.pop((ViewGroup) itemsScroll, apply);
                return;
            }
        }
        apply.run();
    }

    private void applyListContentReplace(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus) {
        mediaSliderExclusive = false;
        queueMode = false;
        if (!scrollableDetailHeader) {
            detailHeaderText = "";
        }
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
        if (titleRow != null) setContextTitleBarVisible(true);
        prepareListPanelVisible();
        rebuildListRows(itemIconKeys, itemStateTexts);
        refreshAll();
        updateListHeightToContent();
        if (focusZone == FocusZone.TIER_CONTENT) {
            scrollFocusIntoView();
        }
        requestOverlayFocus();
    }

    /** Update state text on existing rows when label order is unchanged — avoids list flicker on Wi-Fi scans. */
    public boolean refreshListStatesIfSameStructure(String[] itemLabels, String[] itemStateTexts,
            boolean[] itemHeaders) {
        if (itemsHost == null || labels == null || itemLabels == null
                || labels.length != itemLabels.length) {
            return false;
        }
        for (int i = 0; i < labels.length; i++) {
            if (!labels[i].equals(itemLabels[i])) return false;
            boolean hdr = isHeaderRow(i);
            boolean newHdr = itemHeaders != null && i < itemHeaders.length && itemHeaders[i];
            if (hdr != newHdr) return false;
        }
        for (int i = 0; i < itemsHost.getChildCount() && i < labels.length; i++) {
            if (isHeaderRow(i)) continue;
            View row = itemsHost.getChildAt(i);
            if (row == null) continue;
            String st = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
            String prior = rowStateTextFromView(row);
            String next = st != null ? st : "";
            if (!prior.equals(next)) {
                applyRowState(row, next);
            }
        }
        refreshAll();
        return true;
    }

  private View buildListRowView(int i, String[] itemIconKeys, String[] itemStateTexts) {
        boolean header = rowHeaders != null && i < rowHeaders.length && rowHeaders[i];
        if (header && scrollableDetailHeader && i == 0) {
            detailHeaderText = labels[i] != null ? labels[i] : "";
            return createScrollableDetailHeaderRow();
        }
        if (header) {
            String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
            return createHeaderRow(labels[i], iconKey);
        }
        String iconKey = itemIconKeys != null && i < itemIconKeys.length ? itemIconKeys[i] : null;
        String stateText = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
        if (ICON_ROW_LOADING.equals(iconKey)) {
            return createLoadingRow(labels[i]);
        }
        return createRow(labels[i], iconKey, stateText);
    }

    private void animateWifiTickerRowIn(final View row, int delayMs) {
        if (row == null || itemsHost == null) return;
        row.setAlpha(0.38f);
        row.setTranslationY(rowHeightPx > 0 ? rowHeightPx * 0.22f : 8f);
        itemsHost.postDelayed(new Runnable() {
            @Override
            public void run() {
                row.animate().cancel();
                row.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(QUEUE_MOVE_RIBBON_ENTER_MS)
                        .start();
            }
        }, delayMs);
    }

    private void pulseWifiTickerRow(View row) {
        if (row == null) return;
        row.animate().cancel();
        row.setTranslationY(0f);
        row.animate()
                .alpha(0.48f)
                .setDuration(70)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        row.animate()
                                .alpha(1f)
                                .setDuration(QUEUE_MOVE_RIBBON_ENTER_MS)
                                .start();
                    }
                })
                .start();
    }

    /**
     * Wi-Fi tier scan — same row order: update signal/state with a soft timetable pulse (no full rebuild).
     */
    public boolean refreshWifiTierInPlace(String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders) {
        if (itemsHost == null || labels == null || itemLabels == null
                || labels.length != itemLabels.length) {
            return false;
        }
        for (int i = 0; i < labels.length; i++) {
            if (!labels[i].equals(itemLabels[i])) {
                // ponytail: toggle label (On/Off) may change while scan rows stay put.
                if (i != 0 || isHeaderRow(0)) return false;
            }
            boolean hdr = isHeaderRow(i);
            boolean newHdr = itemHeaders != null && i < itemHeaders.length && itemHeaders[i];
            if (hdr != newHdr) return false;
        }
        this.labels = itemLabels;
        this.rowHeaders = itemHeaders;
        for (int i = 0; i < itemsHost.getChildCount() && i < labels.length; i++) {
            if (isHeaderRow(i)) continue;
            if (isStableNetworkRow(i)) continue;
            View row = itemsHost.getChildAt(i);
            if (row == null) continue;
            String st = itemStateTexts != null && i < itemStateTexts.length ? itemStateTexts[i] : null;
            boolean changed = false;
            String prior = rowStateTextFromView(row);
            String next = st != null ? st : "";
            if (!prior.equals(next)) {
                changed = true;
                applyRowState(row, next);
            }
            if (changed) pulseWifiTickerRow(row);
        }
        // Toggle row label (Wi-Fi On/Off) may change without row-count churn.
        if (labels.length > 0 && itemLabels.length > 0
                && !labels[0].equals(itemLabels[0]) && itemsHost.getChildCount() > 0) {
            updateRowLabelText(itemsHost.getChildAt(0), itemLabels[0]);
        }
        refreshAll();
        return true;
    }

    /** Toggle, connected/paired blocks, and section headers above the scan list stay still. */
    public void setNetworkTierAnimateFromIndex(int index) {
        networkTierAnimateFromIndex = index >= 0 ? index : Integer.MAX_VALUE;
    }

    private boolean isStableNetworkRow(int index) {
        return index < networkTierAnimateFromIndex;
    }

    private String rowStateTextFromView(View row) {
        if (row == null) return "";
        ProgressBar spin = (ProgressBar) row.findViewWithTag(TAG_STATE_SPIN);
        if (spin != null && spin.getVisibility() == View.VISIBLE) {
            return STATE_CONNECTING;
        }
        Object tag = row.getTag(TAG_STATE);
        if (tag instanceof TextView) {
            TextView state = (TextView) tag;
            return state.getVisibility() == View.VISIBLE ? state.getText().toString() : "";
        }
        TextView state = (TextView) row.findViewWithTag(TAG_STATE);
        if (state == null) return "";
        return state.getVisibility() == View.VISIBLE ? state.getText().toString() : "";
    }

    private void updateRowLabelText(View row, String text) {
        if (row == null || text == null) return;
        Object tag = row.getTag(TAG_LABEL);
        if (tag instanceof TextView) {
            ((TextView) tag).setText(text);
        }
    }

    private boolean isDecorRow(View row) {
        return row != null && Boolean.TRUE.equals(row.getTag(TAG_DECOR));
    }

    private int findOldRowForLabel(String label, boolean header, boolean[] used, String[] oldLabels,
            boolean[] oldHeaders) {
        if (oldLabels == null) return -1;
        for (int j = 0; j < oldLabels.length; j++) {
            if (used[j]) continue;
            if (!oldLabels[j].equals(label)) continue;
            boolean oldHdr = oldHeaders != null && j < oldHeaders.length && oldHeaders[j];
            if (oldHdr != header) continue;
            return j;
        }
        return -1;
    }

    /**
     * Wi-Fi / BT tier — reuse stable rows; ticker only for new or materially changed scan rows.
     */
    public void mergeNetworkTierListDiff(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus) {
        String preserveLabel = null;
        if (!resetFocus && labels != null && focusIndex >= 0 && focusIndex < labels.length
                && !isHeaderRow(focusIndex)) {
            preserveLabel = labels[focusIndex];
        }
        FocusZone priorZone = focusZone;
        String[] oldLabels = labels != null ? labels : new String[0];
        boolean[] oldHeaders = rowHeaders;
        int oldChildCount = itemsHost != null ? itemsHost.getChildCount() : 0;
        View[] oldViews = new View[oldChildCount];
        for (int i = 0; i < oldChildCount; i++) {
            oldViews[i] = itemsHost.getChildAt(i);
        }
        boolean[] oldUsed = new boolean[oldChildCount];

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
        if (titleRow != null) setContextTitleBarVisible(true);
        prepareListPanelVisible();
        if (itemsHost != null) {
            itemsHost.removeAllViews();
            int stagger = 0;
            for (int i = 0; i < labels.length; i++) {
                boolean hdr = itemHeaders != null && i < itemHeaders.length && itemHeaders[i];
                String label = labels[i];
                String newState = itemStateTexts != null && i < itemStateTexts.length
                        && itemStateTexts[i] != null ? itemStateTexts[i] : "";
                String iconKey = itemIconKeys != null && i < itemIconKeys.length
                        ? itemIconKeys[i] : null;

                View row = null;
                boolean animateIn = false;

                if (!resetFocus && i == 0 && !hdr && oldChildCount > 0 && oldViews[0] != null) {
                    row = oldViews[0];
                    oldUsed[0] = true;
                    updateRowLabelText(row, label);
                    updateRowStateQuiet(row, newState);
                } else if (!resetFocus) {
                    int oldIdx = findOldRowForLabel(label, hdr, oldUsed, oldLabels, oldHeaders);
                    if (oldIdx >= 0 && oldIdx < oldChildCount) {
                        row = oldViews[oldIdx];
                        oldUsed[oldIdx] = true;
                        String oldState = rowStateTextFromView(row);
                        boolean labelSame = oldLabels[oldIdx].equals(label);
                        boolean stateSame = newState.equals(oldState);
                        if (labelSame && stateSame) {
                            animateIn = false;
                        } else if (isStableNetworkRow(i) || hdr || isDecorRow(row)) {
                            updateRowLabelText(row, label);
                            updateRowStateQuiet(row, newState);
                            animateIn = false;
                        } else if (labelSame && !stateSame) {
                            updateRowStateQuiet(row, newState);
                            pulseWifiTickerRow(row);
                            animateIn = false;
                        } else {
                            row = buildListRowView(i, itemIconKeys, itemStateTexts);
                            animateIn = true;
                        }
                    } else {
                        row = buildListRowView(i, itemIconKeys, itemStateTexts);
                        animateIn = !hdr && !isStableNetworkRow(i) && !isDecorRow(row);
                    }
                }
                if (row == null) {
                    row = buildListRowView(i, itemIconKeys, itemStateTexts);
                    animateIn = !resetFocus && !hdr && !isStableNetworkRow(i) && !isDecorRow(row);
                }
                if (row.getParent() instanceof ViewGroup) {
                    ((ViewGroup) row.getParent()).removeView(row);
                }
                itemsHost.addView(row);
                if (!resetFocus && animateIn) {
                    animateWifiTickerRowIn(row, stagger);
                    stagger += WIFI_TICKER_STAGGER_MS;
                }
            }
        }
        refreshAll();
        updateListHeightToContent();
        if (focusZone == FocusZone.TIER_CONTENT) {
            scrollFocusIntoView();
        }
        requestOverlayFocus();
    }

    private void updateRowStateQuiet(View row, String stateText) {
        applyRowState(row, stateText);
    }

    /** Right column: muted text, checkmark, or connecting spinner. */
    private void applyRowState(View row, String stateText) {
        if (row == null || !(row instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) row;
        Object tag = row.getTag(TAG_STATE);
        TextView state = tag instanceof TextView ? (TextView) tag
                : (TextView) row.findViewWithTag(TAG_STATE);
        ProgressBar spin = (ProgressBar) row.findViewWithTag(TAG_STATE_SPIN);
        float density = activity.getResources().getDisplayMetrics().density;
        int arrowW = 0;
        Object arrowTag = row.getTag(TAG_ARROW);
        if (arrowTag instanceof ImageView) {
            ImageView arrow = (ImageView) arrowTag;
            if (arrow.getDrawable() != null) arrowW = arrow.getWidth();
        }
        int spinSize = (int) (rowHeightPx * 0.55f);
        int spinMarginEnd = arrowW + (int) (16 * density);

        if (STATE_CONNECTING.equals(stateText)) {
            if (state != null) {
                state.setText("");
                state.setVisibility(View.GONE);
            }
            if (spin == null) {
                spin = new ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
                spin.setTag(TAG_STATE_SPIN);
                FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(spinSize, spinSize);
                spinLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
                spinLp.rightMargin = spinMarginEnd;
                frame.addView(spin, spinLp);
            } else {
                spin.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (spin != null) spin.setVisibility(View.GONE);
        if (state == null) return;
        if (stateText != null && stateText.length() > 0) {
            state.setText(stateText);
            state.setVisibility(View.VISIBLE);
        } else {
            state.setText("");
            state.setVisibility(View.GONE);
        }
    }

    /** @deprecated use {@link #mergeNetworkTierListDiff} */
    public void replaceWifiTierWithTicker(String title, String[] itemLabels, String[] itemIconKeys,
            String[] itemStateTexts, boolean[] itemHeaders, Listener listener, boolean resetFocus) {
        mergeNetworkTierListDiff(title, itemLabels, itemIconKeys, itemStateTexts, itemHeaders,
                listener, resetFocus);
    }

    public void replaceQueueContent(String title, QueueRowSpec[] rows, int focusIndex, int moveFrom) {
        queueMode = true;
        scrollableDetailHeader = false;
        detailHeaderText = "";
        submenuTierOpen = true;
        optionsListVisible = true;
        ensureContextTitleBar(quickItems.length > 0);
        queueRows = rows != null ? rows : new QueueRowSpec[0];
        if (moveFrom < 0) {
            exitQueueMoveRibbon();
            queueBrowseWindowStart = 0;
        }
        queueMoveFrom = moveFrom;
        labels = new String[queueRows.length];
        for (int i = 0; i < queueRows.length; i++) labels[i] = queueRows[i].title;
        rowHeaders = null;
        this.focusIndex = Math.max(0, Math.min(focusIndex, Math.max(0, queueRows.length - 1)));
        focusZone = FocusZone.TIER_CONTENT;
        queueRowHeightPx = rowHeightPx;
        invalidateQueueRowBgCache();
        rebuildQueueList();
        refreshAll();
        ensureQueueListVisible();
        scrollFocusIntoView();
        requestOverlayFocus();
        final int rowCount = queueRows.length;
        int scrollH = itemsScroll != null ? itemsScroll.getHeight() : 0;
        int childCount = itemsHost != null ? itemsHost.getChildCount() : 0;
        SolarAdbTest.queueOpen(queueRows.length, optionsListVisible, scrollH, childCount);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("rowCount", rowCount);
            d.put("childCount", childCount);
            d.put("scrollH", scrollH);
            d.put("listVisible", optionsListVisible);
            d.put("virtual", useQueueBrowseVirtual());
            QueueDebugLog.log("ThemedContextMenu.replaceQueueContent", "content replaced", "H4-H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (rowCount > 0 && itemsHost != null && childCount == 0) {
            postSafe(itemsHost, new Runnable() {
                @Override
                public void run() {
                    if (!queueMode || queueRows.length == 0 || itemsHost == null) return;
                    if (itemsHost.getChildCount() > 0) return;
                    rebuildQueueList();
                    ensureQueueListVisible();
                    refreshAll();
                    scrollFocusIntoView();
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("rowCount", queueRows.length);
                        d.put("childCount", itemsHost.getChildCount());
                        d.put("scrollH", itemsScroll != null ? itemsScroll.getHeight() : 0);
                        QueueDebugLog.log("ThemedContextMenu.replaceQueueContent", "post-layout repair", "H5", d);
                    } catch (Exception ignored) {}
                    // #endregion
                }
            });
        } else if (rowCount > 0 && itemsScroll != null && scrollH <= 0) {
            postSafe(itemsScroll, new Runnable() {
                @Override
                public void run() {
                    if (!queueMode) return;
                    ensureQueueListVisible();
                    scrollFocusIntoView();
                }
            });
        }
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

    public int queueRowCount() {
        return queueRows != null ? queueRows.length : 0;
    }

    public int queueListChildCount() {
        return itemsHost != null ? itemsHost.getChildCount() : 0;
    }

    public int queueListScrollHeight() {
        return itemsScroll != null ? itemsScroll.getHeight() : 0;
    }

    /** Keep the queue list panel visible — never collapse while in queue tier. */
    public void ensureQueueListVisible() {
        if (!queueMode) return;
        optionsListVisible = true;
        if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
        updateListHeightToContent();
    }

    /** Update title/subtitle text on visible queue rows without a full rebuild. */
    public void refreshQueueRowTitles(QueueRowSpec[] rows) {
        if (!queueMode || rows == null || rows.length != queueRows.length) return;
        queueRows = rows;
        if (labels == null || labels.length != rows.length) {
            labels = new String[rows.length];
        }
        int start = 0;
        int end = queueRows.length;
        if (useQueueBrowseVirtual()) {
            start = queueBrowseWindowStart;
            end = queueBrowseWindowEnd();
        }
        for (int i = start; i < end; i++) {
            labels[i] = rows[i].title;
            View row = findQueueRowByIndex(i);
            if (row == null) continue;
            TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
            TextView sub = (TextView) row.findViewWithTag(TAG_QUEUE_SUB);
            if (title != null) title.setText(rows[i].displayLine());
            if (sub != null) {
                if (rows[i].subtitle.isEmpty()) {
                    sub.setVisibility(View.GONE);
                } else {
                    sub.setText(rows[i].subtitle);
                    sub.setVisibility(View.VISIBLE);
                }
            }
        }
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
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("prevMove", prevMove);
            d.put("moveFrom", moveFrom);
            d.put("ribbonActive", queueMoveRibbonActive);
            d.put("ribbonAnimating", queueMoveRibbonAnimating);
            DebugAgentLog.log(activity, "ThemedContextMenu.setQueueMoveFrom",
                    "setQueueMoveFrom", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
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
            bindQueueMoveRibbon(0);
        } else if (moveFrom >= 0) {
            bindQueueMoveRibbon(0);
        } else if (prevMove >= 0 && moveFrom < 0) {
            clearQueueConfirm();
            exitQueueMoveRibbon();
            queueBrowseWindowStart = 0;
            rebuildQueueList();
            updateListHeightToContent();
            scrollQueueViewportForIndex(focusIndex);
        }
    }

    /** Drop move — restore full list, keep focus, flash checkmark on placed row. */
    public void finishQueueMove(int placedIndex) {
        flashQueueConfirm(placedIndex);
    }

    /** Confirm flash after move — restore browse list (MainActivity also rebuilds; belt-and-braces). */
    public void flashQueueConfirm(int placedIndex) {
        if (!queueMode || itemsHost == null) {
            queueMoveFrom = -1;
            return;
        }
        clearQueueConfirm();
        queueMoveFrom = -1;
        exitQueueMoveRibbon();
        focusIndex = Math.max(0, Math.min(placedIndex, Math.max(0, queueRows.length - 1)));
        focusZone = FocusZone.TIER_CONTENT;
        queueConfirmAtIndex = focusIndex;
        queueBrowseWindowStart = 0;
        rebuildQueueList();
        updateListHeightToContent();
        scrollQueueViewportForIndex(focusIndex);
        refreshAll();
        animateQueueConfirmCheckmark(focusIndex);
    }

    private void clearQueueConfirm() {
        queueConfirmAtIndex = -1;
        if (queueConfirmClearTask != null && itemsHost != null) {
            itemsHost.removeCallbacks(queueConfirmClearTask);
            queueConfirmClearTask = null;
        }
    }

    private void animateQueueConfirmCheckmark(final int queueIndex) {
        final View row = findQueueRowByIndex(queueIndex);
        if (row == null) return;
        final ImageView confirm = (ImageView) row.findViewWithTag(TAG_QUEUE_CONFIRM);
        if (confirm == null) return;
        confirm.animate().cancel();
        confirm.setAlpha(0f);
        confirm.setVisibility(View.VISIBLE);
        confirm.animate()
                .alpha(1f)
                .setDuration(QUEUE_CONFIRM_SHOW_MS)
                .start();
        queueConfirmClearTask = new Runnable() {
            @Override
            public void run() {
                queueConfirmClearTask = null;
                queueConfirmAtIndex = -1;
                confirm.animate()
                        .alpha(0f)
                        .setDuration(QUEUE_CONFIRM_SHOW_MS)
                        .setListener(new android.animation.AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(android.animation.Animator animation) {
                                confirm.animate().setListener(null);
                                confirm.setVisibility(View.GONE);
                                confirm.setAlpha(1f);
                                refreshQueueRowAt(queueIndex);
                            }
                        })
                        .start();
            }
        };
        itemsHost.postDelayed(queueConfirmClearTask, QUEUE_CONFIRM_HOLD_MS);
    }

    public void focusQuickBar(int index) {
        if (quickBarHost == null || quickItems.length == 0) return;
        quickFocusIndex = Math.max(0, Math.min(index, quickItems.length - 1));
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    /** Focus the quick chip that opened the current tier (Wi-Fi, BT, queue, …). */
    public void focusQuickBarAtReturn() {
        focusQuickBar(quickBarReturnIndex());
    }

    public int quickBarReturnIndexForNavigation() {
        return quickBarReturnIndex();
    }

    /** Queue tier: move focus to a quick chip without collapsing the queue list. */
    public void focusQuickBarLeavingQueueList(int quickIndex) {
        ensureContextTitleBar(quickItems.length > 0);
        if (queueMode) {
            optionsListVisible = true;
            if (itemsScroll != null) itemsScroll.setVisibility(View.VISIBLE);
            updateListHeightToContent();
        }
        focusQuickBar(quickIndex);
    }

    /** Hide list/queue tier; keep Back + quick toggles visible and focused (no Options rows). */
    public void showQuickBarOnly(int quickIndex) {
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        labels = new String[0];
        rowHeaders = null;
        focusIndex = 0;
        optionsListVisible = false;
        boolean hasQuick = quickItems.length > 0;
        ensureContextTitleBar(hasQuick);
        if (itemsHost != null) itemsHost.removeAllViews();
        hideMediaSliderStripViews();
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
        postSafe(itemsHost, new Runnable() {
            @Override
            public void run() {
                scrollQueueRowIntoViewNow(index);
                postSafe(itemsScroll, new Runnable() {
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
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("itemsHostNull", itemsHost == null);
            d.put("queueMode", queueMode);
            d.put("moveActive", isQueueMoveActive());
            d.put("moveFrom", queueMoveFrom);
            d.put("ribbonActive", queueMoveRibbonActive);
            d.put("rowCount", queueRows != null ? queueRows.length : 0);
            d.put("childBefore", itemsHost != null ? itemsHost.getChildCount() : -1);
            QueueDebugLog.log("ThemedContextMenu.rebuildQueueList", "entry", "H2-H4", d);
        } catch (Exception ignored) {}
        // #endregion
        if (itemsHost == null || !queueMode || isQueueMoveActive()) return;
        if (useQueueBrowseVirtual()) rebuildQueueBrowseWindow();
        else rebuildQueueRows();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("virtual", useQueueBrowseVirtual());
            d.put("childAfter", itemsHost.getChildCount());
            d.put("scrollH", itemsScroll != null ? itemsScroll.getHeight() : 0);
            if (queueRows.length > 0 && itemsHost.getChildCount() > 0) {
                View first = null;
                for (int c = 0; c < itemsHost.getChildCount(); c++) {
                    View row = itemsHost.getChildAt(c);
                    if (row.getTag() instanceof Integer
                            && ((Integer) row.getTag()).intValue() >= 0) {
                        first = row;
                        break;
                    }
                }
                if (first != null) {
                    TextView t = (TextView) first.findViewWithTag(TAG_QUEUE_TITLE);
                    d.put("firstIdx", first.getTag());
                    d.put("firstText", t != null ? t.getText().toString() : "");
                    d.put("firstTextColor", t != null ? t.getCurrentTextColor() : 0);
                }
            }
            QueueDebugLog.log("ThemedContextMenu.rebuildQueueList", "done", "H2-H4-H5", d);
        } catch (Exception ignored) {}
        // #endregion
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
        int count = queueRows.length;
        int visible = queueBrowseVisibleRows();
        int buffer = QueueBrowseWindow.ROW_BUFFER;
        int windowSize = QueueBrowseWindow.windowSize(visible, buffer);
        if (count <= windowSize) {
            if (queueBrowseWindowStart != 0 || itemsHost.getChildCount() == 0) {
                rebuildQueueBrowseWindow();
            }
            return;
        }
        int end = queueBrowseWindowStart + windowSize;
        int maxStart = count - windowSize;
        if (focusIndex < queueBrowseWindowStart || focusIndex >= end) {
            rebuildQueueBrowseWindow();
            return;
        }
        int newStart = queueBrowseWindowStart;
        if (focusIndex >= end - buffer && newStart < maxStart) {
            newStart++;
        } else if (focusIndex < queueBrowseWindowStart + buffer && newStart > 0) {
            newStart--;
        }
        if (newStart != queueBrowseWindowStart) {
            if (newStart >= maxStart || focusIndex >= count - 3) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("focusIndex", focusIndex);
                    d.put("count", count);
                    d.put("newStart", newStart);
                    d.put("maxStart", maxStart);
                    DebugAgentLog.log(activity, "ThemedContextMenu.ensureQueueBrowseWindowForFocus",
                            "rebuild near end", "H6", d);
                } catch (Exception ignored) {}
                // #endregion
                rebuildQueueBrowseWindow();
            } else {
                slideQueueBrowseWindow(newStart);
            }
        } else if (findQueueRowByIndex(focusIndex) == null) {
            rebuildQueueBrowseWindow();
        }
    }

    private void slideQueueBrowseWindow(int newStart) {
        if (itemsHost == null || !useQueueBrowseVirtual()) return;
        int oldStart = queueBrowseWindowStart;
        if (newStart == oldStart) return;
        int count = queueRows.length;
        int visible = queueBrowseVisibleRows();
        int buffer = QueueBrowseWindow.ROW_BUFFER;
        int windowSize = QueueBrowseWindow.windowSize(visible, buffer);
        newStart = Math.max(0, Math.min(newStart, count - windowSize));
        if (newStart == oldStart) return;

        int slotH = queueRowSlotHeight();
        float density = activity.getResources().getDisplayMetrics().density;
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        int oldEnd = oldStart + windowSize;
        int newEnd = newStart + windowSize;

        if (newStart > oldStart) {
            int drop = newStart - oldStart;
            int child = 0;
            if (oldStart > 0) {
                View topSpacer = itemsHost.getChildAt(0);
                if (topSpacer != null) {
                    ViewGroup.LayoutParams lp = topSpacer.getLayoutParams();
                    lp.height = newStart * slotH;
                    topSpacer.setLayoutParams(lp);
                }
                child = 1;
            }
            for (int i = 0; i < drop && child < itemsHost.getChildCount(); i++) {
                itemsHost.removeViewAt(child);
            }
            int insertBefore = itemsHost.getChildCount();
            if (newEnd < count) insertBefore--;
            for (int i = oldEnd; i < newEnd && i < count; i++) {
                itemsHost.addView(createQueueRow(queueRows[i], i, rowH, density), insertBefore++);
            }
            if (newEnd < count) {
                View bottomSpacer = itemsHost.getChildAt(itemsHost.getChildCount() - 1);
                if (bottomSpacer != null && bottomSpacer.getTag() == null) {
                    ViewGroup.LayoutParams lp = bottomSpacer.getLayoutParams();
                    lp.height = (count - newEnd) * slotH;
                    bottomSpacer.setLayoutParams(lp);
                }
            }
        } else {
            int drop = oldStart - newStart;
            int child = 0;
            if (newStart > 0) {
                if (oldStart > 0) {
                    View topSpacer = itemsHost.getChildAt(0);
                    if (topSpacer != null) {
                        ViewGroup.LayoutParams lp = topSpacer.getLayoutParams();
                        lp.height = newStart * slotH;
                        topSpacer.setLayoutParams(lp);
                    }
                } else {
                    View topSpacer = new View(activity);
                    topSpacer.setFocusable(false);
                    itemsHost.addView(topSpacer, 0, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, newStart * slotH));
                }
                child = 1;
            } else if (oldStart > 0) {
                itemsHost.removeViewAt(0);
            }
            for (int i = 0; i < drop; i++) {
                int idx = itemsHost.getChildCount() - 1;
                if (newEnd < count) idx--;
                if (idx >= child) itemsHost.removeViewAt(idx);
            }
            for (int i = newStart; i < oldStart; i++) {
                itemsHost.addView(createQueueRow(queueRows[i], i, rowH, density), child + (i - newStart));
            }
            if (newEnd < count) {
                View bottomSpacer = itemsHost.getChildAt(itemsHost.getChildCount() - 1);
                if (bottomSpacer != null && bottomSpacer.getTag() == null) {
                    ViewGroup.LayoutParams lp = bottomSpacer.getLayoutParams();
                    lp.height = (count - newEnd) * slotH;
                    bottomSpacer.setLayoutParams(lp);
                }
            }
        }
        queueBrowseWindowStart = newStart;
        for (int i = queueBrowseWindowStart; i < queueBrowseWindowEnd(); i++) {
            refreshQueueRowAt(i);
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
        refreshQueueRows();
    }

    private void rebuildQueueRows() {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;
        int rowH = queueRowHeightPx > 0 ? queueRowHeightPx : rowHeightPx;
        for (int i = 0; i < queueRows.length; i++) {
            itemsHost.addView(createQueueRow(queueRows[i], i, rowH, density));
        }
        refreshQueueRows();
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

        ImageView confirm = new ImageView(activity);
        confirm.setTag(TAG_QUEUE_CONFIRM);
        confirm.setScaleType(ImageView.ScaleType.FIT_CENTER);
        confirm.setImageResource(R.drawable.ic_check);
        confirm.setVisibility(View.GONE);
        rightSlot.addView(confirm, new android.widget.FrameLayout.LayoutParams(ppSz, ppSz, Gravity.CENTER));

        row.addView(rightSlot);
        return row;
    }

    private void updateListHeightToContent() {
        if (itemsScroll == null || itemsHost == null) return;
        computeDetailHeaderMaxHeight();
        if (scrollableDetailHeader && itemsHost.getChildCount() > 0) {
            refreshScrollableDetailHeader();
        }
        int padBefore = itemsHost.getPaddingTop();
        // Browse: keep viewport padding stable through measure+scroll (clearing it caused slot gaps).
        // Move ribbon: padding must stay zero — the 3-slot ribbon owns the whole viewport.
        if (queueMode && !isQueueMoveActive()) {
            applyQueueViewportPadding();
        } else if (queueMode && itemsHost.getPaddingTop() != 0) {
            itemsHost.setPadding(0, 0, 0, 0);
        }
        // #region agent log
        if (queueMode) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("padBefore", padBefore);
                d.put("padAfter", itemsHost.getPaddingTop());
                d.put("childCount", itemsHost.getChildCount());
                d.put("scrollY", itemsScroll.getScrollY());
                d.put("scrollH", itemsScroll.getHeight());
                d.put("rowCount", queueRows != null ? queueRows.length : 0);
                QueueDebugLog.log("ThemedContextMenu.updateListHeightToContent", "queue layout", "H5", d);
            } catch (Exception ignored) {}
        }
        // #endregion
        itemsHost.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentH = itemsHost.getMeasuredHeight();
        int target;
        if (queueMode) {
            optionsListVisible = true;
            target = queueViewportHeight();
        } else {
            target = maxListHeightPx > 0 ? Math.min(contentH, maxListHeightPx) : contentH;
        }
        if (target <= 0) {
            if (queueMode) {
                target = queueRowSlotHeight() * Math.max(1, QUEUE_MOVE_VISIBLE_ROWS);
            } else if (labels != null && labels.length > 0 && optionsListVisible) {
                // ponytail: first OK on a quick tab can run before row layout — estimate from label count.
                int rowSlot = rowHeightPx > 0 ? rowHeightPx + 2 : 0;
                if (rowSlot > 0) {
                    int rows = 0;
                    for (int i = 0; i < labels.length; i++) {
                        if (!isHeaderRow(i)) rows++;
                    }
                    target = Math.max(rowSlot, rows * rowSlot);
                    if (maxListHeightPx > 0) target = Math.min(target, maxListHeightPx);
                }
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
        }
        if (!optionsListVisible && !queueMode && quickBarHost != null) {
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

    /** Compact hint overlay — same chrome as volume-only, without slider (launcher switch wait). */
    public void showHintOnly(ViewGroup root, String hintText, int rowHeightPx, int panelWidthPx) {
        dismiss();
        hintOnlyMode = true;
        volumeOnlyMode = false;
        dialogStyle = false;
        queueMode = false;
        labels = new String[0];
        quickItems = new QuickItem[0];
        this.rowHeightPx = rowHeightPx;
        this.panelWidthPx = panelWidthPx;
        this.menuRows = false;
        this.panelBgColor = ThemeManager.getContextMenuPanelColor();

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
            panel.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER;
        overlay.addView(panel, panelLp);
        addOverlayWithPresentAnim(root, null);
        focusZone = FocusZone.TIER_CONTENT;
    }

    /** Compact volume overlay — hint line, no quick toggles or list. */
    public void showVolumeOnly(ViewGroup root, String hintText, String sliderTitle, int max, int value,
            int rowHeightPx, int panelWidthPx) {
        dismiss();
        hintOnlyMode = false;
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
        addOverlayWithPresentAnim(root, null);
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
        int savedVol = sliderValue;
        int savedVolMax = sliderMax;
        for (int i = panel.getChildCount() - 1; i >= 0; i--) {
            panel.removeViewAt(i);
        }
        sliderRow = null;
        volumeSliderBlock = null;
        brightnessSliderBlock = null;
        sliderBar = null;
        sliderLabel = null;
        brightnessBar = null;
        brightnessLabel = null;

        boolean hasQuick = quickItems.length > 0;
        titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        populateTitleRow(true, hasQuick, density);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleRowLp.bottomMargin = labels.length > 0 ? (int) (4 * density) : 0;
        panel.addView(titleRow, titleRowLp);

        itemsScroll = new Y1SafeScrollView(activity);
        itemsScroll.setFillViewport(false);
        Y1ScrollIndicators.applyVerticalScrollView(itemsScroll);
        itemsScroll.setVisibility(View.GONE);
        maxListHeightPx = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        itemsHost = new LinearLayout(activity);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        rebuildListRows(itemIconKeys, itemStateTexts);
        itemsScroll.addView(itemsHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(itemsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addMediaSliderRowToPanel(panel, density);
        updateVolumeSlider(savedVol, savedVolMax);
        updateBrightnessSlider(brightnessValue, 255);
        if (sliderRow != null) sliderRow.setVisibility(View.VISIBLE);
        if (sliderLabel != null) {
            sliderLabel.setText(activity.getString(R.string.context_quick_volume));
        }
        if (brightnessLabel != null) {
            brightnessLabel.setText(activity.getString(R.string.context_quick_brightness));
        }
        optionsListVisible = false;
        collapseOptionsListPanel();

        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        quickFocusIndex = volumeQuickIndex;
        quickReturnIndex = volumeQuickIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        syncMediaQuickIconResFromItems();
        refreshQuickChipIcons();
        requestOverlayFocus();
    }

    public boolean isVolumeOnlyMode() {
        return volumeOnlyMode;
    }

    private void syncMediaQuickIconResFromItems() {
        if (volumeQuickIndex >= 0 && volumeQuickIndex < quickItems.length) {
            volumeQuickIconRes = quickItems[volumeQuickIndex].iconResId;
        }
        if (brightnessQuickIndex >= 0 && brightnessQuickIndex < quickItems.length) {
            brightnessQuickIconRes = quickItems[brightnessQuickIndex].iconResId;
        }
    }

    public static int volumeIconResForLevel(int value, int max) {
        if (value <= 0) return R.drawable.ic_volume_mute;
        if (max <= 0) return R.drawable.ic_volume_high;
        float pct = (float) value / (float) max;
        if (pct <= 0.33f) return R.drawable.ic_volume_low;
        if (pct <= 0.66f) return R.drawable.ic_volume_mid;
        return R.drawable.ic_volume_high;
    }

    public static int brightnessIconResForLevel(int value, int max) {
        if (max <= 0) return R.drawable.ic_brightness;
        float pct = (float) value / (float) max;
        if (pct <= 0.33f) return R.drawable.ic_brightness_empty;
        if (pct <= 0.66f) return R.drawable.ic_brightness_half;
        return R.drawable.ic_brightness;
    }

    public void updateVolumeSlider(int value, int max) {
        sliderMax = Math.max(1, max);
        sliderValue = Math.max(0, Math.min(value, sliderMax));
        if (sliderBar != null) {
            sliderBar.setMax(sliderMax);
            sliderBar.setProgress(sliderValue);
        }
        int iconRes = volumeIconResForLevel(sliderValue, sliderMax);
        if (iconRes != volumeQuickIconRes) {
            volumeQuickIconRes = iconRes;
            updateQuickChipIconRes(volumeQuickIndex, iconRes);
        }
    }

    public void updateBrightnessSlider(int value, int max) {
        brightnessMax = Math.max(1, max);
        brightnessValue = Math.max(0, Math.min(value, brightnessMax));
        if (brightnessBar != null) {
            brightnessBar.setMax(brightnessMax);
            brightnessBar.setProgress(brightnessValue);
        }
        int iconRes = brightnessIconResForLevel(brightnessValue, brightnessMax);
        if (iconRes != brightnessQuickIconRes) {
            brightnessQuickIconRes = iconRes;
            updateQuickChipIconRes(brightnessQuickIndex, iconRes);
        }
    }

    private void updateQuickChipIconRes(int quickIndex, int iconResId) {
        if (quickBarHost == null || quickIndex < 0 || quickIndex >= quickItems.length) return;
        QuickItem prior = quickItems[quickIndex];
        quickItems[quickIndex] = new QuickItem(prior.iconKey, iconResId, prior.label, prior.visible);
        for (int i = 0; i < quickBarHost.getChildCount(); i++) {
            View chip = quickBarHost.getChildAt(i);
            Object tag = chip.getTag();
            if (!(tag instanceof Integer) || ((Integer) tag) != quickIndex) continue;
            ImageView icon = (ImageView) chip.findViewWithTag("quick_icon");
            if (icon != null) icon.setImageResource(iconResId);
            break;
        }
        refreshQuickBar();
    }

    public void showSlider(String label, int max, int value) {
        showMediaSlidersWithQuickBarFocus(quickReturnIndex >= 0
                ? quickReturnIndex : volumeQuickIndex,
                label, max, value,
                activity.getString(R.string.context_quick_brightness), 255, brightnessValue);
    }

    /** Full context menu with volume + brightness sliders; quick-bar item pre-highlighted. */
    public void showMediaSlidersWithQuickBarFocus(int quickIndex, String volumeLabel, int volumeMax,
            int volumeValue, String brightnessLabelText, int brightnessMaxVal, int brightnessValueVal) {
        volumeOnlyMode = false;
        mediaSliderExclusive = true;
        updateVolumeSlider(volumeValue, volumeMax);
        updateBrightnessSlider(brightnessValueVal, brightnessMaxVal);
        if (sliderLabel != null) {
            sliderLabel.setText(volumeLabel != null ? volumeLabel : "");
        }
        if (brightnessLabel != null) {
            brightnessLabel.setText(brightnessLabelText != null ? brightnessLabelText : "");
        }
        if (sliderRow != null) sliderRow.setVisibility(View.VISIBLE);
        syncMediaSliderBlockVisibility();
        optionsListVisible = false;
        collapseOptionsListPanel();
        quickFocusIndex = quickIndex;
        quickReturnIndex = quickIndex;
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
        requestOverlayFocus();
    }

    /** @deprecated use {@link #showMediaSlidersWithQuickBarFocus} */
    public void showSliderWithQuickBarFocus(String label, int max, int value, int quickIndex) {
        showMediaSlidersWithQuickBarFocus(quickIndex, label, max, value,
                activity.getString(R.string.context_quick_brightness), 255, brightnessValue);
    }

    public void hideSlider() {
        exitMediaSliderTab();
    }

    /** Leave volume/brightness tab — hide sliders and restore list when appropriate. */
    public void exitMediaSliderTab() {
        hideMediaSliderStripViews();
        if (quickBarScroll != null) quickBarScroll.setAlpha(1f);
        boolean wasExclusive = mediaSliderExclusive;
        mediaSliderExclusive = false;
        if (wasExclusive) {
            restorePanelAfterMediaSlider();
        }
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

    private void restorePanelAfterMediaSlider() {
        if (queueMode) {
            optionsListVisible = true;
            if (itemsScroll != null) {
                itemsScroll.setVisibility(View.VISIBLE);
                updateListHeightToContent();
            }
            return;
        }
        if (labels != null && labels.length > 0) {
            optionsListVisible = true;
            prepareListPanelVisible();
            updateListHeightToContent();
        }
    }

    public int brightnessSliderValue() {
        return brightnessValue;
    }

    public void adjustBrightnessSlider(int delta) {
        brightnessValue = Math.max(0, Math.min(brightnessMax, brightnessValue + delta));
        if (brightnessBar != null) brightnessBar.setProgress(brightnessValue);
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
        addOverlayWithPresentAnim(root, null);
        focusZone = FocusZone.SLIDER;
    }

    public void updateProgressOverlay(int value, String statusText) {
        sliderValue = Math.max(0, Math.min(sliderMax, value));
        if (sliderBar != null) sliderBar.setProgress(sliderValue);
        if (sliderLabel != null && statusText != null) sliderLabel.setText(statusText);
    }

    /** OTA system install — full-screen status, no dismiss, no progress bar. */
    public void showInstallStatusOverlay(ViewGroup root, String title, String message) {
        showProgressOverlay(root, title, message, 100);
        sliderValue = 100;
        if (sliderBar != null) {
            sliderBar.setProgress(sliderMax);
            sliderBar.setVisibility(View.GONE);
        }
    }

    public void dismiss() {
        dismissInternal(null, false);
    }

    /** Animate modal out, then tear down. */
    public void dismissAnimated(final Runnable onComplete) {
        dismissInternal(onComplete, true);
    }

    private void dismissInternal(final Runnable onComplete, boolean animated) {
        if (isEnterExitAnimating() && overlay != null) {
            ScreenTransition.resetView(overlay);
            ScreenTransition.resetView(panel);
            modalEnterExitAnimating = false;
        }
        dismissGeneration++;
        final FrameLayout scrim = overlay;
        final LinearLayout panelView = panel;
        if (!animated || scrim == null || scrim.getParent() == null
                || !ModalTransition.enabled(activity)) {
            removeOverlayFromParent(scrim);
            clearOverlayState();
            if (onComplete != null) onComplete.run();
            return;
        }
        modalEnterExitAnimating = true;
        panelView.setPivotX(panelView.getWidth() * 0.5f);
        panelView.setPivotY(panelView.getHeight() * 0.5f);
        ModalTransition.animateDismiss(scrim, panelView, new Runnable() {
            @Override
            public void run() {
                modalEnterExitAnimating = false;
                removeOverlayFromParent(scrim);
                clearOverlayState();
                if (onComplete != null) onComplete.run();
            }
        });
    }

    private void removeOverlayFromParent(FrameLayout scrim) {
        if (scrim != null && scrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) scrim.getParent()).removeView(scrim);
        }
    }

    private void clearOverlayState() {
        overlay = null;
        panel = null;
        titleRow = null;
        titleChip = null;
        titleBackIcon = null;
        quickBarScroll = null;
        quickBarHost = null;
        itemsScroll = null;
        itemsHost = null;
        sliderBar = null;
        sliderLabel = null;
        sliderRow = null;
        volumeSliderBlock = null;
        brightnessSliderBlock = null;
        brightnessBar = null;
        brightnessLabel = null;
        labels = null;
        rowHeaders = null;
        listener = null;
        quickListener = null;
        quickItems = new QuickItem[0];
        queueMode = false;
        queueRows = new QueueRowSpec[0];
        queueMoveFrom = -1;
        exitQueueMoveRibbon();
        clearQueueConfirm();
        volumeOnlyMode = false;
        hintOnlyMode = false;
        dialogStyle = false;
        mediaSliderExclusive = false;
        optionsListVisible = true;
        submenuTierOpen = false;
        quickReturnIndex = -1;
        scrollableDetailHeader = false;
        detailHeaderText = "";
    }

    private void recoverFocusFromCollapsedList() {
        if (queueMode) {
            ensureQueueListVisible();
            enterMenuListFocus(clampFocusableIndex(focusIndex));
            return;
        }
        if (quickBarHost != null && quickBarHost.getChildCount() > 0) {
            focusQuickBarFromListExit();
        } else if (hasBackChip() && labels != null && labels.length > 0) {
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
                quickFocusIndex = firstVisibleQuickIndex();
                refreshAll();
                scrollQuickFocusIntoView();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("quickIdx", quickFocusIndex);
                    d.put("chipCount", quickBarHost.getChildCount());
                    DebugAgentLog.log(activity, "ThemedContextMenu.handleKeyHorizontal",
                            "back→quick bar", "H-NAV", d);
                } catch (Exception ignored) {}
                // #endregion
                return true;
            }
            if (keyCode == 21 && quickBarHost != null && quickBarHost.getChildCount() > 0) {
                return true;
            }
            if (keyCode == 21) return true;
            return false;
        }
        if (keyCode == 21) {
            if (isMenuListZone() && !isQueueMoveActive() && focusIndex == firstFocusableIndex(0)) {
                focusQuickBarFromListTopExit();
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
            if (delta > 0) {
                if (quickBarHost != null && quickBarHost.getChildCount() > 0) {
                    focusZone = FocusZone.QUICK_BAR;
                    quickFocusIndex = firstVisibleQuickIndex();
                    refreshAll();
                    scrollQuickFocusIntoView();
                } else if (labels != null && labels.length > 0) {
                    enterOptionsListFromTitle();
                }
            } else if (delta < 0) {
                Y1ScrollIndicators.edgeGlowAtLimit(itemsScroll, delta);
            }
            return;
        }
        if (queueMode && focusZone == FocusZone.TIER_CONTENT && isQueueMoveActive()) {
            return;
        }
        if (focusZone == FocusZone.QUICK_BAR) {
            // ponytail: only enter the list from the last visible quick chip (moveQuickFocus), not on every wheel tick.
            moveQuickFocus(delta);
            return;
        }
        if (isMenuListZone()) {
            if (queueMode && !optionsListVisible) {
                ensureQueueListVisible();
            }
            if (!optionsListVisible && !queueMode) {
                rerouteWhenListCollapsed(delta);
                return;
            }
            if (labels == null || labels.length == 0) return;
            if (scrollableDetailHeader && focusIndex == 0 && delta != 0) {
                if (handleScrollableDetailWheel(delta)) return;
            }
            if (delta < 0 && focusIndex == firstFocusableIndex(0)) {
                focusQuickBarFromListTopExit();
                return;
            }
            focusZone = FocusZone.TIER_CONTENT;
            int prev = focusIndex;
            int next = nextFocusableIndex(focusIndex, delta);
            if (next < 0 || next == focusIndex) {
                if (delta != 0) Y1ScrollIndicators.edgeGlowAtLimit(itemsScroll, delta);
                return;
            }
            if (scrollableDetailHeader && prev == 0 && next > 0) {
                resetScrollableDetailScroll();
            }
            if (scrollableDetailHeader && prev > 0 && next == 0) {
                resetScrollableDetailScroll();
            }
            focusIndex = next;
            if (queueMode) {
                ensureQueueBrowseWindowForFocus();
                // Rebuild via rebuildQueueList — picks full list vs virtual window by queue size.
                if (findQueueRowByIndex(focusIndex) == null) {
                    rebuildQueueList();
                }
                applyQueueViewportPadding();
                if (prev >= 0 && prev != focusIndex) refreshQueueRowAt(prev);
                refreshQueueRowAt(focusIndex);
            } else {
                refreshAll();
            }
            scrollFocusIntoView();
        }
    }

    /** Wheel / track keys on the horizontal quick-bar row (Wi‑Fi, BT, queue, …). */
    public void moveQuickBarFocus(int delta) {
        moveQuickFocus(delta);
    }

    private void moveQuickFocus(int delta) {
        int[] vis = visibleQuickIndices();
        if (vis.length == 0) return;
        int pos = 0;
        for (int i = 0; i < vis.length; i++) {
            if (vis[i] == quickFocusIndex) { pos = i; break; }
        }
        if (delta < 0 && pos == 0) {
            if (hasBackChip()) {
                focusBackChip();
                return;
            }
            if (hasBackChip() && labels != null && labels.length > 0) {
                focusOptionsTitle();
            }
            return;
        }
        int nextPos = pos + delta;
        if (nextPos >= vis.length && delta > 0 && labels != null && labels.length > 0) {
            enterListFromQuickBar();
            return;
        }
        if (nextPos < 0) {
            Y1ScrollIndicators.edgeGlowAtLimit(itemsScroll, -1);
            return;
        }
        if (nextPos >= vis.length) {
            Y1ScrollIndicators.edgeGlowAtLimit(itemsScroll, 1);
            return;
        }
        quickFocusIndex = vis[nextPos];
        focusZone = FocusZone.QUICK_BAR;
        refreshAll();
        scrollQuickFocusIntoView();
    }

    public void activateFocused() {
        if (focusZone == FocusZone.OPTIONS_TITLE) {
            if (hasBackChip() && quickListener != null) {
                quickListener.onBackActivated();
            } else if (labels != null && labels.length > 0) {
                enterOptionsListFromTitle();
            }
            return;
        }
        if (focusZone == FocusZone.QUICK_BAR) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("quickIdx", quickFocusIndex);
                d.put("hasListener", quickListener != null);
                d.put("submenuOpen", submenuTierOpen);
                d.put("queueMode", queueMode);
                d.put("labelCount", labels != null ? labels.length : 0);
                DebugAgentLog.log(activity, "ThemedContextMenu.activateFocused",
                        "quick bar activate", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            if (quickListener != null) quickListener.onQuickSelected(quickFocusIndex);
            // ponytail: tier rows may rebuild while focus stays on the chip until layout runs — enter list now.
            if (focusZone == FocusZone.QUICK_BAR && submenuTierOpen
                    && labels != null && labels.length > 0
                    && !isMediaSliderStripVisible()) {
                focusSubmenuList();
            }
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
        if (isScrollableDetailHeaderIndex(index)) return false;
        return rowHeaders != null && index >= 0 && index < rowHeaders.length && rowHeaders[index];
    }

    /** Menu row slot height for scrollable message blocks (two rows minimum). */
    private int menuRowSlotPx() {
        if (rowHeightPx > 0) return rowHeightPx + 2;
        int itemH = (int) activity.getResources().getDimension(R.dimen.y1_menu_item_height);
        return itemH + 2;
    }

    /** Reserve list height so the first action row peeks below a scrollable message header. */
    private void computeDetailHeaderMaxHeight() {
        detailHeaderMaxHeightPx = 0;
        if (!scrollableDetailHeader || labels == null || labels.length == 0) return;
        int actionRows = 0;
        for (int i = 0; i < labels.length; i++) {
            if (!isHeaderRow(i) && !isDecorRow(i)) actionRows++;
        }
        int defaultMax = VerticalTextMarqueeHelper.defaultMaxHeightPx(activity);
        int rowSlot = menuRowSlotPx();
        int twoRowMin = rowSlot * 2;
        if (actionRows < 1) {
            detailHeaderMaxHeightPx = Math.max(defaultMax, twoRowMin);
            return;
        }
        int reserved = rowSlot;
        if (actionRows >= 2 && maxListHeightPx >= rowSlot * 2 + defaultMax / 2) {
            reserved = rowSlot * 2;
        }
        int cap = maxListHeightPx > 0 ? maxListHeightPx - reserved : defaultMax;
        detailHeaderMaxHeightPx = Math.max((int) (defaultMax * 0.45f), Math.min(defaultMax, cap));
        detailHeaderMaxHeightPx = Math.max(detailHeaderMaxHeightPx, twoRowMin);
    }

    private int scrollableDetailHeaderHeightPx() {
        if (detailHeaderMaxHeightPx > 0) return detailHeaderMaxHeightPx;
        return VerticalTextMarqueeHelper.defaultMaxHeightPx(activity);
    }

    private boolean isScrollableDetailHeaderIndex(int index) {
        return scrollableDetailHeader && index == 0;
    }

    private void refreshScrollableDetailHeader() {
        if (itemsHost == null || itemsHost.getChildCount() == 0) return;
        View row = itemsHost.getChildAt(0);
        if (row != null && row.getTag(TAG_SCROLL_HEADER) instanceof Boolean) {
            bindScrollableDetailHeaderRow(row, isMenuListZone() && focusIndex == 0);
        }
    }

    private void bindScrollableDetailHeaderRow(View row, boolean focused) {
        FrameLayout panel = row.findViewWithTag(TAG_DETAIL_PANEL);
        if (panel == null) return;
        int maxH = scrollableDetailHeaderHeightPx();
        int w = panelWidthPx > 0 ? panelWidthPx : row.getWidth();
        VerticalTextMarqueeHelper.updateCappedPanel(activity, panel, detailHeaderText, maxH, w);
        int panelH = panel.getLayoutParams() != null ? panel.getLayoutParams().height : maxH;
        int rowSlot = menuRowSlotPx();
        panelH = Math.max(panelH, rowSlot * 2);
        if (panel.getLayoutParams() != null) {
            panel.getLayoutParams().height = panelH;
        }
        if (row.getLayoutParams() != null) {
            row.getLayoutParams().height = panelH;
        }
        row.setBackground(rowBackground(focused, w, panelH));
    }

    private FrameLayout createScrollableDetailHeaderRow() {
        int maxH = scrollableDetailHeaderHeightPx();
        FrameLayout panel = VerticalTextMarqueeHelper.createCappedPanel(activity, detailHeaderText, maxH);
        panel.setTag(TAG_DETAIL_PANEL);

        FrameLayout row = new FrameLayout(activity);
        row.setTag(TAG_SCROLL_HEADER, Boolean.TRUE);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        int panelH = panel.getLayoutParams() != null ? panel.getLayoutParams().height : maxH;
        // ponytail: message tiers reserve two list rows so author + preview lines stay visible; wheel scrolls longer bodies.
        int rowSlot = menuRowSlotPx();
        panelH = Math.max(panelH, rowSlot * 2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, panelH);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);
        row.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, panelH));
        bindScrollableDetailHeaderRow(row, isMenuListZone() && focusIndex == 0);
        return row;
    }

    private int lastFocusableIndex() {
        if (labels == null) return 0;
        for (int i = labels.length - 1; i >= 0; i--) {
            if (!isHeaderRow(i)) return i;
        }
        return 0;
    }

    private int firstFocusableIndex(int start) {
        if (labels == null) return 0;
        for (int i = start; i < labels.length; i++) {
            if (!isHeaderRow(i) && !isDecorRow(i)) return i;
        }
        return start;
    }

    private int nextFocusableIndex(int from, int delta) {
        if (labels == null || labels.length == 0) return -1;
        int i = from + delta;
        while (i >= 0 && i < labels.length) {
            if (!isHeaderRow(i) && !isDecorRow(i)) return i;
            i += delta;
        }
        return from;
    }

    private boolean isDecorRow(int index) {
        if (itemsHost == null || index < 0 || index >= itemsHost.getChildCount()) return false;
        View row = itemsHost.getChildAt(index);
        return row != null && Boolean.TRUE.equals(row.getTag(TAG_DECOR));
    }

    private int clampFocusableIndex(int idx) {
        if (labels == null || labels.length == 0) return 0;
        idx = Math.max(0, Math.min(idx, labels.length - 1));
        if (!isHeaderRow(idx)) return idx;
        int next = nextFocusableIndex(idx, 1);
        return next >= 0 ? next : firstFocusableIndex(0);
    }

    private void scrollFocusIntoView() {
        if (itemsScroll == null || itemsHost == null || !isMenuListZone()) return;
        if (queueMode) {
            scrollQueueViewportForIndex(queueScrollIndex());
            return;
        }
        int scrollIdx = focusIndex;
        if (scrollIdx < 0 || scrollIdx >= itemsHost.getChildCount()) return;
        if (scrollableDetailHeader && scrollIdx == 0) {
            itemsScroll.scrollTo(0, 0);
            return;
        }
        scrollQueueRowIntoViewImmediate(scrollIdx);
    }

    private FrameLayout scrollableDetailPanel() {
        if (itemsHost == null || itemsHost.getChildCount() == 0) return null;
        View row = itemsHost.getChildAt(0);
        if (row == null) return null;
        return (FrameLayout) row.findViewWithTag(TAG_DETAIL_PANEL);
    }

    private boolean handleScrollableDetailWheel(int delta) {
        FrameLayout panel = scrollableDetailPanel();
        if (panel == null) return false;
        if (delta > 0) {
            if (!VerticalTextMarqueeHelper.isPanelScrolledToBottom(panel)) {
                VerticalTextMarqueeHelper.scrollPanelByStep(activity, panel, 1);
                return true;
            }
            int next = nextFocusableIndex(focusIndex, 1);
            if (next >= 0 && next != focusIndex) {
                resetScrollableDetailScroll();
                focusIndex = next;
                refreshAll();
                scrollFocusIntoView();
            }
            return true;
        }
        if (!VerticalTextMarqueeHelper.isPanelScrolledToTop(panel)) {
            VerticalTextMarqueeHelper.scrollPanelByStep(activity, panel, -1);
            return true;
        }
        focusQuickBarFromListTopExit();
        return true;
    }

    private void resetScrollableDetailScroll() {
        FrameLayout panel = scrollableDetailPanel();
        if (panel != null) VerticalTextMarqueeHelper.resetPanelScroll(panel);
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
                postSafe(quickBarScroll, new Runnable() {
                    @Override
                    public void run() {
                        if (quickBarScroll == null || quickBarHost == null) return;
                        int viewW = quickBarScroll.getWidth();
                        int maxScroll = Math.max(0, quickBarHost.getWidth() - viewW);
                        if (maxScroll <= 0) {
                            scrollQuickBarToStart();
                            return;
                        }
                        int chipLeft = target.getLeft();
                        int chipRight = target.getRight();
                        int scrollX = quickBarScroll.getScrollX();
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
        int iconRes = iconResForQuickIndex(index, item.iconResId);
        android.graphics.Bitmap bmp = item.iconKey != null ? ThemeManager.getSettingIcon(item.iconKey) : null;
        if (bmp != null) {
            icon.setImageBitmap(bmp);
        } else if (iconRes != 0) {
            icon.setImageResource(iconRes);
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
                int quickNormal = textNormal();
                int quickSelected = textSelected();
                icon.setColorFilter(focused ? quickSelected : quickNormal, PorterDuff.Mode.SRC_ATOP);
            }
        }
    }

    /** Swap quick-bar icons/labels without closing the menu (e.g. queue ↔ music library). */
    public void replaceQuickBar(QuickItem[] items) {
        if (volumeOnlyMode || !isShowing()) return;
        QuickItem[] next = items != null ? items : new QuickItem[0];
        if (quickItemsEqual(quickItems, next)) {
            refreshQuickChipIcons();
            return;
        }
        quickItems = next;
        if (quickBarHost == null) return;
        quickBarHost.removeAllViews();
        for (int i = 0; i < quickItems.length; i++) {
            if (!quickItems[i].visible) continue;
            quickBarHost.addView(createQuickChip(quickItems[i], rowHeightPx, i));
        }
        syncMediaQuickIconResFromItems();
        refreshQuickChipIcons();
    }

    /** ponytail: skip removeAllViews when chip set unchanged — BT scan was rebuilding every tick. */
    private static boolean quickItemsEqual(QuickItem[] a, QuickItem[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            QuickItem x = a[i];
            QuickItem y = b[i];
            if (x == y) continue;
            if (x == null || y == null) return false;
            if (x.visible != y.visible || x.iconResId != y.iconResId) return false;
            if (x.iconKey != null ? !x.iconKey.equals(y.iconKey) : y.iconKey != null) return false;
            if (x.label != null ? !x.label.equals(y.label) : y.label != null) return false;
        }
        return true;
    }

    private Drawable buildPanelBackground() {
        return ThemeManager.buildContextMenuPanelDrawable(activity);
    }

    private int iconResForQuickIndex(int index, int fallbackResId) {
        if (index == volumeQuickIndex) return volumeQuickIconRes;
        if (index == brightnessQuickIndex) return brightnessQuickIconRes;
        return fallbackResId;
    }

    /** Re-apply chip drawables after slider-index fix or replaceQuickBar no-op (BT scan ticks). */
    private void refreshQuickChipIcons() {
        if (quickBarHost == null) return;
        for (int i = 0; i < quickBarHost.getChildCount(); i++) {
            View chip = quickBarHost.getChildAt(i);
            Object tag = chip.getTag();
            if (!(tag instanceof Integer)) continue;
            int idx = (Integer) tag;
            if (idx < 0 || idx >= quickItems.length) continue;
            QuickItem item = quickItems[idx];
            ImageView icon = (ImageView) chip.findViewWithTag("quick_icon");
            if (icon == null) continue;
            int iconRes = iconResForQuickIndex(idx, item.iconResId);
            android.graphics.Bitmap bmp = item.iconKey != null
                    ? ThemeManager.getSettingIcon(item.iconKey) : null;
            if (bmp != null) {
                icon.setImageBitmap(bmp);
            } else if (iconRes != 0) {
                icon.setImageResource(iconRes);
            }
        }
        refreshQuickBar();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("volIdx", volumeQuickIndex);
            d.put("brightIdx", brightnessQuickIndex);
            d.put("chipCount", quickBarHost.getChildCount());
            QueueDebugLog.log("ThemedContextMenu.refreshQuickChipIcons", "icons synced", "H-QBAR", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private android.graphics.Bitmap resolveContextMenuIcon(String iconKey) {
        if (iconKey == null || iconKey.isEmpty()) return null;
        if (iconKey.startsWith("wifi.sig.")) {
            try {
                int idx = Integer.parseInt(iconKey.substring(9));
                android.graphics.Bitmap themed = ThemeManager.getWifiIcon(idx);
                if (themed != null) return themed;
                int res = R.drawable.ic_wifi_signal_1;
                if (idx >= 2) res = R.drawable.ic_wifi_signal_3;
                else if (idx == 1) res = R.drawable.ic_wifi_signal_2;
                return tintedDrawableBitmap(res);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if ("wifi.lock".equals(iconKey)) {
            android.graphics.Bitmap themed = ThemeManager.getSettingIcon("lock");
            if (themed != null) return themed;
            return tintedDrawableBitmap(R.drawable.ic_lock);
        }
        if (iconKey.startsWith("shuffle") || iconKey.startsWith("repeat")) {
            return ThemeManager.getPlaybackModeIcon(iconKey);
        }
        if (iconKey.startsWith("flag.")) {
            Bitmap flag = SoulseekCountryFlags.loadFlag(activity, iconKey.substring(5));
            if (flag == null) return null;
            int w = (int) (rowHeightPx * 0.5f);
            int h = (int) (rowHeightPx * 0.33f);
            return Bitmap.createScaledBitmap(flag, w, h, true);
        }
        return ThemeManager.getSettingIcon(iconKey);
    }

    private android.graphics.Bitmap tintedDrawableBitmap(int resId) {
        Drawable d = activity.getResources().getDrawable(resId);
        if (d == null) return null;
        int size = (int) (rowHeightPx * 0.72f);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.setColorFilter(ThemeManager.getStatusBarTextColor(), PorterDuff.Mode.SRC_IN);
        d.draw(c);
        return bmp;
    }

    private TextView createHeaderRow(String text) {
        return createHeaderRow(text, null);
    }

    private TextView createHeaderRow(String text, String iconKey) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float density = activity.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(activity);
        tv.setTag(TAG_HEADER, Boolean.TRUE);
        tv.setFocusable(false);
        tv.setText(text);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.85f);
        ThemeManager.applyThemedTextStyle(tv, ThemeManager.ensureReadableOnBackground(
                ThemeManager.getSectionHeaderTextColor(), panelBgColor));
        android.graphics.Bitmap iconBmp = resolveContextMenuIcon(iconKey);
        if (iconBmp != null) {
            int pad = (int) (4 * density);
            android.graphics.drawable.BitmapDrawable d =
                    new android.graphics.drawable.BitmapDrawable(activity.getResources(), iconBmp);
            d.setBounds(0, 0, iconBmp.getWidth(), iconBmp.getHeight());
            tv.setCompoundDrawables(d, null, null, null);
            tv.setCompoundDrawablePadding(pad);
        }
        tv.setPadding(textPadLeft, (int) (8 * density), textPadLeft,
                VerticalTextMarqueeHelper.messagePadBottomExtraPx(activity) + (int) (4 * density));
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
        boolean hasState = stateText != null && stateText.length() > 0;
        boolean connectingState = STATE_CONNECTING.equals(stateText);
        if (connectingState) hasState = true;
        boolean stackHint = hasState && !connectingState
                && (stateText.length() > 20
                        || (panelWidthPx > 0 && panelWidthPx < (int) (400 * density)));

        android.graphics.Bitmap iconBmp = resolveContextMenuIcon(iconKey);
        int iconW = iconBmp != null ? iconSize : 0;
        int labelLeft = textPadLeft + (iconW > 0 ? iconW + iconGap : 0);
        int rowH = stackHint ? rowHeightPx + (int) (menuTextPx * 0.95f) : rowHeightPx;

        FrameLayout row = new FrameLayout(activity);
        row.setFocusable(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH);
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

        if (stackHint) {
            LinearLayout stack = new LinearLayout(activity);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            stackLp.leftMargin = labelLeft;
            stackLp.rightMargin = arrowW + arrowMarginEnd + (int) (6 * density);
            stack.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView state = new TextView(activity);
            state.setText(stateText);
            state.setTypeface(ThemeManager.getCustomFont());
            state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.82f);
            state.setMaxLines(2);
            ThemeManager.applyThemedTextStyle(state,
                    ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
            stack.addView(state, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(stack, stackLp);
            row.setTag(TAG_STATE, state);
            applyRowState(row, stateText);
        } else {
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            labelLp.leftMargin = labelLeft;
            labelLp.rightMargin = arrowW + arrowMarginEnd + (hasState ? (int) (48 * density) : 0);
            row.addView(label, labelLp);
            if (hasState) {
                TextView state = new TextView(activity);
                state.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.9f);
                state.setSingleLine(true);
                state.setEllipsize(TextUtils.TruncateAt.END);
                ThemeManager.applyThemedTextStyle(state,
                        ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
                FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
                stateLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
                stateLp.rightMargin = arrowW + arrowMarginEnd + (int) (6 * density);
                row.addView(state, stateLp);
                row.setTag(TAG_STATE, state);
                applyRowState(row, stateText);
            }
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

    /** Non-interactive scan/status row — spinner on the right, skipped by wheel focus. */
    private FrameLayout createLoadingRow(String text) {
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float density = activity.getResources().getDisplayMetrics().density;
        int spinSize = (int) (rowHeightPx * 0.55f);

        FrameLayout row = new FrameLayout(activity);
        row.setTag(TAG_DECOR, Boolean.TRUE);
        row.setFocusable(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTypeface(ThemeManager.getCustomFont());
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.9f);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        ThemeManager.applyThemedTextStyle(label,
                ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        labelLp.leftMargin = textPadLeft;
        labelLp.rightMargin = spinSize + (int) (12 * density);
        row.addView(label, labelLp);

        ProgressBar spin = new ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(spinSize, spinSize);
        spinLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        spinLp.rightMargin = (int) (10 * density);
        row.addView(spin, spinLp);
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
            if (row.getTag(TAG_SCROLL_HEADER) instanceof Boolean) {
                bindScrollableDetailHeaderRow(row, isMenuListZone() && focusIndex == 0);
                continue;
            }
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
            if (!queueMoveRibbonAnimating) {
                bindQueueMoveRibbon(0);
            }
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
        boolean confirming = queueConfirmAtIndex >= 0 && queueIndex == queueConfirmAtIndex;
        boolean focused = focusZone == FocusZone.TIER_CONTENT && queueIndex == focusIndex;
        boolean highlighted = focused || moving || confirming;
        int w = panelWidthPx > 0 ? panelWidthPx : row.getWidth();
        row.setBackground(rowBackground(highlighted, w, queueRowHeightPx));
        TextView title = (TextView) row.findViewWithTag(TAG_QUEUE_TITLE);
        TextView grip = (TextView) row.findViewWithTag(TAG_QUEUE_GRIP);
        ImageView pp = (ImageView) row.findViewWithTag(TAG_QUEUE_PP);
        ImageView confirm = (ImageView) row.findViewWithTag(TAG_QUEUE_CONFIRM);
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
        if (confirm != null) {
            if (!confirming) {
                confirm.setVisibility(View.GONE);
                confirm.setAlpha(1f);
            }
            if (confirm.getVisibility() == View.VISIBLE) {
                confirm.setColorFilter(textSelected(), PorterDuff.Mode.SRC_ATOP);
            }
        }
        if (pp != null) {
            if (moving || confirming) {
                pp.setVisibility(View.GONE);
            } else if (queueRows[queueIndex].nowPlaying) {
                pp.setImageResource(queuePlaybackStateIcon(queueRows[queueIndex].playing));
                pp.setColorFilter(textNormal(), PorterDuff.Mode.SRC_ATOP);
                pp.setVisibility(View.VISIBLE);
                if (queueIndex == focusIndex) {
                    SolarAdbTest.queuePlaybackState(queueRows[queueIndex].playing);
                }
            } else {
                pp.setVisibility(View.GONE);
            }
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
        int themeNormal = menuRows ? ThemeManager.getSettingMenuTextColorNormal()
                : ThemeManager.getItemTextColorNormal();
        int themeSelected = menuRows ? ThemeManager.getSettingMenuTextColorSelected()
                : ThemeManager.getItemTextColorSelected();
        return ThemeManager.contextMenuTextNormal(themeNormal, themeSelected, panelBgColor, menuRows);
    }

    private int textSelected() {
        int themeSelected = menuRows ? ThemeManager.getSettingMenuTextColorSelected()
                : ThemeManager.getItemTextColorSelected();
        return ThemeManager.contextMenuTextSelected(themeSelected, menuRows);
    }
}
