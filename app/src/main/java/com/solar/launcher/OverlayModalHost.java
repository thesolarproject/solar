package com.solar.launcher;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.Toast;

import com.solar.launcher.theme.ThemeManager;
import com.solar.home.policy.HomeTargetPolicy;
import com.solar.launcher.stem.StemControls;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and drives {@link ThemedContextMenu} on a system overlay root — power tier and app-menu modes.
 * All quick-bar tiers stay in the overlay; never launch {@link MainActivity} except Go Home.
 */
public final class OverlayModalHost {

    /**
     * 2026-07-15 — Quick-bar indices (Sleep rightmost after Volume).
     * Was: Lock at 1, Volume at 7. Now: Home/Wi‑Fi/BT/Power/NP/Brightness/Volume/Sleep.
     * Reversal: restore QUICK_LOCK=1 and QUICK_VOLUME=7.
     * Keep aligned with MainActivity CONTEXT_QUICK_* constants.
     */
    private static final int QUICK_HOME = 0;
    private static final int QUICK_WIFI = 1;
    private static final int QUICK_BT = 2;
    private static final int QUICK_POWER = 3;
    /** Queue slot — Now Playing over third-party; hidden over Rockbox / when idle. */
    private static final int QUICK_NOW_PLAYING = 4;
    private static final int QUICK_BRIGHTNESS = 5;
    private static final int QUICK_VOLUME = 6;
    /** Sleep/Zzz — right end on Y1/A5 (hidden on Y2). */
    private static final int QUICK_SLEEP = 7;
    private static final int VOLUME_DISMISS_MS = 1400;
    /** 2026-07-07 — Passive toast HUD — maps from Toast.LENGTH_SHORT / LENGTH_LONG. */
    private static final int TOAST_DISMISS_SHORT_MS = 2000;
    private static final int TOAST_DISMISS_LONG_MS = 3500;
    /** Public for SolarOverlayService default extra. */
    public static final int TOAST_DISMISS_SHORT_MS_PUBLIC = TOAST_DISMISS_SHORT_MS;
    /** Ignore BACK dismiss until long-press open gesture finishes (tier matches fg hold time). */
    private long backDismissGraceMs =
            com.solar.input.policy.GlobalInputPolicy.GLOBAL_MODAL_HOLD_MS;
    /** First BACK up after hold-open must not dismiss — finger lift is not "go back". */
    private boolean suppressBackDismissUntilLift;
    /** Fresh BACK hold after open release closes overlay; quick taps are ignored. */
    private static final long BACK_HOLD_DISMISS_MS = 150L;
    /** Block center/OK activate briefly after long-press opened app-menu overlay (release key). */
    private static final long APP_MENU_CENTER_GRACE_MS =
            OverlayCenterActivation.SUB_TIER_CENTER_GRACE_MS;

    public interface DismissListener {
        void onDismissOverlay();
    }

    private final Context context;
    private final ThemedContextMenu menu;
    private final ViewGroup overlayRoot;
    private final DismissListener dismissListener;
    private final int rowHeightPx;
    private final int panelWidthPx;
    private boolean powerTierVisible;
    private boolean volumeOnlyVisible;
    private boolean launcherPickerVisible;
    private boolean mediaSlidersActive;
    private boolean simpleNetworkTier;
    private boolean queueTierVisible;
    private long powerTierOpenedAt;
    /**
     * 2026-07-08 — Uptime when quick bar opened a sub-tier (Power/Wi‑Fi/BT/confirm).
     * Blocks the same-press KEY_UP from activating Restart (row 0) after chip select.
     * Was: no stamp — KEY_DOWN + focusSubmenuList could chain into reboot.
     * Reversal: leave at 0 and activate on DOWN again.
     */
    private long subTierChangedAt;
    private int queueFocusIndex;
    private int queueMoveFrom = -1;
    private PlayQueue overlayQueue;
    private long centerKeyDownAt;
    private boolean centerMovePickHandled;
    /** Drops rapid wheel repeats during overlay queue move — same as in-app context menu. */
    private final QueueMoveWheelFilter queueMoveWheelFilter = new QueueMoveWheelFilter();
    private static final long CENTER_QUEUE_MOVE_MS = 450L;
    private final Runnable centerQueueMoveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!queueTierVisible || queueMoveFrom >= 0 || centerKeyDownAt == 0) return;
            if (!isOverlayQueueListFocused()) return;
            centerMovePickHandled = true;
            handleOverlayQueueActivate(true);
        }
    };
    private int systemBrightness = SystemBrightnessControl.MAX;
    /** Session callback — result broadcast targets the hooked app package, not Solar. */
    private String appMenuSessionId;
    private String appMenuCallerPackage;
    /** AlertDialog replacement session — same delivery pattern as app menu hooks. */
    private String dialogSessionId;
    private String dialogCallerPackage;
    /** True while native confirm dialog tier is showing (quick bar must not cancel session). */
    private boolean dialogTierVisible;
    /** True while third-party app menu tier is showing. */
    private boolean appMenuTierVisible;
    /** Uptime when app-menu tier painted — suppress open-gesture center UP from picking row 0. */
    private long appMenuOpenedAt;
    /** Wi‑Fi/BT/volume opened from modal quick bar — Back returns to dialog/menu not power tier. */
    private boolean modalQuickSubTier;
    private Runnable dialogTierRestore;
    private Runnable appMenuTierRestore;
    private Runnable usbStorageTierRestore;
    private String savedDialogTitle;
    private String savedDialogMessage;
    private String[] savedDialogButtons;
    private String savedAppMenuTitle;
    private String[] savedAppMenuLabels;
    private boolean[] savedAppMenuHasSubmenu;
    /** SystemUI USB enable prompt — result stays in Solar, not a hooked third-party app. */
    private boolean usbStoragePromptVisible;
    /**
     * 2026-07-08 — UMS lock overlay fail-open (companion preferred).
     * Layman: “disk mode on” block screen — only Turn Off or unplug closes it.
     * Was: MainActivity.STATE_USB_STORAGE. Now: same shell as enable prompt / :overlay lock tier.
     * Reversal: drop field; lock only on companion GlobalContextOverlayService.
     */
    private boolean usbStorageLockVisible;
    /** Bluetooth passkey match / consent tier — Solar-native, not hooked-app session. */
    private boolean bluetoothPairingPromptVisible;
    private Runnable bluetoothPairingTierRestore;
    private OverlayWifiPasswordKeyboard wifiPasswordKeyboard;
    /** 2026-07-19 — Digit PIN keyboard when silent Bluetooth pairing needs user digits. */
    private OverlayBtPinKeyboard btPinKeyboard;
    private String btPairingAddress;
    private int savedBtPairingMode;
    private int savedBtPairingPasskey;
    private String savedBtPairingName;
    private String savedBtPairingPinPrefill;
    private final Handler dismissHandler = new Handler(Looper.getMainLooper());
    /** 2026-07-06 — BACK hold tracking while USB/other overlay tiers are visible. */
    private long overlayBackDownAt;
    private boolean overlayBackRescueFired;
    private final Runnable overlayBackRescueRunnable = new Runnable() {
        @Override
        public void run() {
            overlayBackRescueFired = true;
            SolarRescue.executeAfterHudFlash(context,
                    ExternalInputHandoff.getForegroundPackageName(context), dismissHandler);
        }
    };
    private final Runnable volumeDismissRunnable = new Runnable() {
        @Override
        public void run() {
            if (volumeOnlyVisible) dismissListener.onDismissOverlay();
        }
    };
    /** 2026-07-07 — Auto-hide passive toast tier — no buttons, no key gate. */
    private final Runnable toastDismissRunnable = new Runnable() {
        @Override
        public void run() {
            if (menu.isHintOnlyMode()) dismissListener.onDismissOverlay();
        }
    };
    /** 2026-07-06 — Paint rescue countdown inside modal while user browses quick menu. */
    private final Runnable rescueBannerPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!menu.isShowing()) {
                menu.setRescueCountdownBanner(null);
                return;
            }
            String text = SolarRescueHoldState.hudText(context);
            menu.setRescueCountdownBanner(text);
            if (SolarRescueHoldState.isHoldActive() || SolarRescueHoldState.isHudRestarting()) {
                dismissHandler.postDelayed(this, 100L);
            }
        }
    };

    public OverlayModalHost(Context context, ThemedContextMenu menu, ViewGroup overlayRoot,
            DismissListener dismissListener) {
        this.context = context.getApplicationContext();
        this.menu = menu;
        this.overlayRoot = overlayRoot;
        this.dismissListener = dismissListener;
        menu.setSystemOverlayMode(true);
        menu.setOverlayKeyHandler(new ThemedContextMenu.OverlayKeyHandler() {
            @Override
            public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                return handleOverlayKeyDown(keyCode);
            }
        });
        menu.setMediaSliderQuickIndices(QUICK_VOLUME, QUICK_BRIGHTNESS);
        // 2026-07-15 — Touch queue reorder (OK-hold + wheel unchanged).
        menu.setQueueTouchMoveListener(new ThemedContextMenu.QueueTouchMoveListener() {
            @Override
            public void onQueueTouchLift(int index) {
                if (queueMoveFrom >= 0) return;
                queueFocusIndex = index;
                handleOverlayQueueActivate(true);
            }

            @Override
            public void onQueueTouchStep(int delta) {
                if (queueMoveFrom < 0 || delta == 0) return;
                moveOverlayQueueFocus(delta);
            }

            @Override
            public void onQueueTouchConfirm() {
                if (queueMoveFrom >= 0) handleOverlayQueueActivate(false);
            }
        });
        float density = context.getResources().getDisplayMetrics().density;
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int margin = (int) (10 * density);
        rowHeightPx = (int) context.getResources().getDimension(R.dimen.y1_menu_item_height);
        panelWidthPx = screenW > margin * 2 ? screenW - margin * 2 : screenW;
    }

    /** Y2 power-hold / Y1 back-hold — full quick bar with power tier focused. */
    public void showPowerMode() {
        String fg = ExternalInputHandoff.getForegroundPackageName(context);
        backDismissGraceMs = com.solar.input.policy.GlobalInputPolicy
                .overlayDismissGraceMsForPackage(fg);
        suppressBackDismissUntilLift = true;
        powerTierOpenedAt = SystemClock.uptimeMillis();
        // 2026-07-08 — Open gesture may still be holding OK/BACK; block until release settles.
        markSubTierChanged();
        powerTierVisible = true;
        launcherPickerVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        clearAppMenuSession();
        String[] empty = new String[0];
        menu.show(overlayRoot, null, null, empty, null, null, null,
                createListListener(), rowHeightPx, panelWidthPx, false, false,
                buildQuickBar(), createQuickBarListener());
        refreshPowerTier(true);
        menu.setQuickReturnIndex(QUICK_POWER);
        menu.focusSubmenuList();
        startRescueBannerPoll();
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_POWER);
    }

    /** 2026-07-08 — Sync in-modal rescue strip with sysprop HUD (7s..10s continuous hold). */
    private void startRescueBannerPoll() {
        dismissHandler.removeCallbacks(rescueBannerPollRunnable);
        dismissHandler.post(rescueBannerPollRunnable);
    }

    /** Stop rescue banner updates when overlay tears down. */
    void stopRescueBannerPoll() {
        dismissHandler.removeCallbacks(rescueBannerPollRunnable);
        menu.setRescueCountdownBanner(null);
    }

    /** Stock volume panel replacement — compact slider matching in-app context menu. */
    public void showVolumeMode() {
        showVolumeMode(OverlayKeyGate.isOverlayKeysActive());
    }

    /**
     * @param requestFocus true when opened from interactive global modal (volume chip);
     *                     false for passive HUD over Rockbox / third-party apps.
     */
    public void showVolumeMode(boolean requestFocus) {
        powerTierVisible = false;
        launcherPickerVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        clearAppMenuSession();
        volumeOnlyVisible = true;
        int max = MediaVolumeControl.getDisplayMaxVolume(context);
        int cur = MediaVolumeControl.getMediaDisplayVolume(context);
        menu.showVolumeOnly(overlayRoot, "",
                context.getString(R.string.context_quick_volume),
                max, cur, rowHeightPx, panelWidthPx, false);
        scheduleVolumeDismiss();
    }

    /**
     * Passive volume HUD — sync, or morph an open context shell into compact volume.
     * 2026-07-15 — Was: power/queue/Wi‑Fi kept full tiers (in-place slider only).
     * Reversal: restore in-place updateVolumeSlider for powerTierVisible branch.
     */
    public void refreshVolumeSlider() {
        int max = MediaVolumeControl.getDisplayMaxVolume(context);
        int cur = MediaVolumeControl.getMediaDisplayVolume(context);
        if (volumeOnlyVisible) {
            menu.updateVolumeSlider(cur, max);
            scheduleVolumeDismiss();
            return;
        }
        // Media slider strip already visible (volume+brightness from quick chips) — sync in place.
        if (mediaSlidersActive) {
            menu.updateVolumeSlider(cur, max);
            return;
        }
        // Power / queue / network / any other shell → clear activity, show compact volume only.
        showVolumeMode(false);
    }

    /**
     * 2026-07-07 — Stock Toast replacement — compact themed hint bar (passive, no key capture).
     * Layman: brief message floats on the global overlay layer like the volume HUD.
     * Technical: fail-open caller shows stock Toast when overlay delivery misses.
     */
    public void showToastMode(String text, long durationMs) {
        powerTierVisible = false;
        launcherPickerVisible = false;
        volumeOnlyVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        clearAppMenuSession();
        dialogTierVisible = false;
        long ms = durationMs > 0 ? durationMs : TOAST_DISMISS_SHORT_MS;
        menu.showHintOnly(overlayRoot, text != null ? text : "", rowHeightPx, panelWidthPx);
        dismissHandler.removeCallbacks(toastDismissRunnable);
        dismissHandler.postDelayed(toastDismissRunnable, ms);
    }

    private void scheduleVolumeDismiss() {
        dismissHandler.removeCallbacks(volumeDismissRunnable);
        dismissHandler.postDelayed(volumeDismissRunnable, VOLUME_DISMISS_MS);
    }

    /**
     * HOME resolver replacement — wheel-friendly picker; applies preferred HOME immediately
     * (no Android Always / Just once sheet).
     */
    public void showLauncherPickerMode() {
        powerTierVisible = false;
        volumeOnlyVisible = false;
        launcherPickerVisible = true;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        clearAppMenuSession();
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<String> targets = new ArrayList<String>();
        final ArrayList<Boolean> isRestart = new ArrayList<Boolean>();
        String current = LauncherPreference.getHomeTarget(context);
        String marker = context.getString(R.string.settings_home_launcher_current_marker);
        appendLauncherPickerRow(labels, targets, isRestart, current, marker,
                LauncherDefault.TARGET_SOLAR,
                R.string.settings_home_launcher_restart_solar,
                R.string.settings_home_launcher_switch_solar);
        // 2026-07-19 — Solar-only HOME picker: no Rockbox/JJ chips (PowerActions kept).
        // Was: list Rockbox when available + JJ when offer visible. Reversal: restore appendLauncherPickerRow.
        // 2026-07-08 — Stock (Innioasis) in crash/hold HOME picker.
        if (LauncherSwitch.isStockOfferVisible(context)) {
            appendLauncherPickerRow(labels, targets, isRestart, current, marker,
                    LauncherDefault.TARGET_STOCK,
                    R.string.settings_home_launcher_restart_stock,
                    R.string.settings_home_launcher_switch_stock);
        }
        int focusIndex = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).equals(current)) {
                focusIndex = i;
                break;
            }
        }
        Boolean[] headers = new Boolean[labels.size()];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = Boolean.FALSE;
        }
        menu.show(overlayRoot, context.getString(R.string.launcher_picker_title), null,
                labels.toArray(new String[labels.size()]), null, null, toPrimitive(headers),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index < 0 || index >= targets.size()) return;
                        if (isRestart.get(index).booleanValue()) {
                            dismissOverlayForLauncherSelection("overlay_picker_restart",
                                    targets.get(index));
                            LauncherHelperClient.restartActiveLauncher(context, "overlay_picker");
                        } else {
                            applyLauncherPickerSelection(targets.get(index));
                        }
                    }
                }, rowHeightPx, panelWidthPx, false, true, null, null);
        menu.focusSubmenuList();
        for (int i = 0; i < focusIndex; i++) {
            menu.moveFocus(1);
        }
    }

    /**
     * 2026-07-10 — Solar-only crash recovery — restart Solar or dismiss (no alternate launchers).
     * Layman: if Solar keeps stopping, try again or close the message — no Rockbox/JJ picker.
     */
    public void showLauncherCrashRecoveryMode(String crashedProcess) {
        powerTierVisible = false;
        volumeOnlyVisible = false;
        launcherPickerVisible = true;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        clearAppMenuSession();
        final String[] labels = new String[] {
                context.getString(R.string.launcher_crash_recovery_use_solar),
                context.getString(R.string.launcher_crash_recovery_keep_solar),
        };
        Boolean[] headers = new Boolean[] { Boolean.FALSE, Boolean.FALSE };
        String detail = context.getString(R.string.launcher_crash_recovery_body,
                crashedProcess != null ? crashedProcess : context.getString(R.string.app_name));
        menu.show(overlayRoot, context.getString(R.string.launcher_crash_recovery_title),
                detail, labels, null, null, toPrimitive(headers),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index == 0) {
                            LauncherRecoveryHelper.clearRecoveryState(context);
                            PowerActions.switchToSolar(context);
                        } else {
                            LauncherRecoveryHelper.clearRecoveryState(context);
                            dismissListener.onDismissOverlay();
                        }
                    }
                }, rowHeightPx, panelWidthPx, true, true);
        menu.focusOptionsList();
    }

    /** Recovery picker chose PM-discovered custom HOME — apply + clear streak (legacy). */
    private void applyCustomLauncherRecoverySelection(String flatComponent) {
        dismissOverlayForLauncherSelection("overlay_recovery_custom",
                HomeTargetPolicy.TARGET_CUSTOM);
        android.content.ComponentName cn =
                android.content.ComponentName.unflattenFromString(flatComponent);
        if (cn != null) {
            LauncherHomeApply.applyCustomHome(context, cn, "crash_recovery_overlay");
        }
        LauncherRecoveryHelper.clearRecoveryState(context);
    }

    private void appendLauncherPickerRow(ArrayList<String> labels, ArrayList<String> targets,
            ArrayList<Boolean> isRestart, String current, String marker, String target,
            int restartRes, int useRes) {
        boolean currentRow = target.equals(current);
        String label = context.getString(currentRow ? restartRes : useRes);
        if (currentRow) label = label + marker;
        labels.add(label);
        targets.add(target);
        isRestart.add(Boolean.valueOf(currentRow));
    }

    /** App/widget options — same item-row chrome as in-app Solar context menu (rect + theme arrow). */
    public void showAppMenuMode(String title, String[] itemLabels,
            String sessionId, String callerPackage) {
        showAppMenuMode(title, itemLabels, null, sessionId, callerPackage);
    }

    /** App menu with optional submenu flags — quick bar matches in-app Solar context menu. */
    public void showAppMenuMode(String title, String[] itemLabels, boolean[] hasSubmenu,
            String sessionId, String callerPackage) {
        ThemeManager.ensureOverlayThemeReady(context);
        powerTierVisible = false;
        dialogTierVisible = false;
        launcherPickerVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        modalQuickSubTier = false;
        clearModalSessions();
        appMenuTierVisible = true;
        appMenuOpenedAt = SystemClock.uptimeMillis();
        appMenuSessionId = sessionId;
        appMenuCallerPackage = callerPackage;
        savedAppMenuTitle = title;
        savedAppMenuLabels = itemLabels;
        savedAppMenuHasSubmenu = hasSubmenu;
        OverlayMenuSessionRegistry.put(sessionId, title, itemLabels, hasSubmenu, callerPackage);
        appMenuTierRestore = buildAppMenuRestoreRunnable();
        if (itemLabels == null || itemLabels.length == 0) {
            sendAppMenuResult(-1);
            dismissListener.onDismissOverlay();
            return;
        }
        String dialogTitle = title != null && title.length() > 0
                ? title : context.getString(R.string.context_menu_title);
        Boolean[] headers = new Boolean[itemLabels.length];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = Boolean.FALSE;
        }
        menu.show(overlayRoot, dialogTitle, null, itemLabels, null, null, toPrimitive(headers),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        handleAppMenuSelection(index);
                    }
                }, rowHeightPx, panelWidthPx, true, false,
                buildQuickBar(), createModalQuickBarListener());
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("itemCount", itemLabels.length);
            d.put("quickVisible", countVisibleQuickItems(buildQuickBar()));
            d.put("themeReady", ThemeManager.isOverlayThemeReady());
            d.put("themeFolder", ThemeManager.getCurrentTheme() != null
                    ? ThemeManager.getCurrentTheme().folderName : "");
            d.put("menuShowing", menu.isShowing());
            DebugAgentLog.log(context, "OverlayModalHost.showAppMenuMode", "menu shown", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        menu.focusSubmenuList();
    }

    /** Route app-menu pick — keep overlay up when row opens a submenu. */
    private void handleAppMenuSelection(int index) {
        boolean opensSubmenu = savedAppMenuHasSubmenu != null && index >= 0
                && index < savedAppMenuHasSubmenu.length && savedAppMenuHasSubmenu[index];
        // 2026-07-08 — Solar Home owns dismiss; every pick may drill to another overlay tier.
        // Was: non-submenu rows tore down :overlay before MainActivity could refresh APP_MENU.
        // Now: solar_home_* sessions stay up until MainActivity sends DISMISS_OVERLAY.
        // Reversal: remove solarHomeSession branch — third-party one-shot menus only.
        boolean solarHomeSession = appMenuSessionId != null
                && appMenuSessionId.startsWith("solar_home_");
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("index", index);
            d.put("opensSubmenu", opensSubmenu);
            d.put("solarHomeSession", solarHomeSession);
            d.put("sessionId", appMenuSessionId != null ? appMenuSessionId.substring(0,
                    Math.min(8, appMenuSessionId.length())) : "");
            d.put("caller", appMenuCallerPackage);
            DebugAgentLog.log(context, "OverlayModalHost.handleAppMenuSelection",
                    "row picked", "H3", d);
        } catch (Exception ignored) {}
        // #endregion
        sendAppMenuResult(index);
        if (opensSubmenu || solarHomeSession) {
            if (opensSubmenu && !solarHomeSession) {
                appMenuSessionId = null;
            }
            return;
        }
        appMenuTierVisible = false;
        dismissListener.onDismissOverlay();
    }

    private Runnable buildAppMenuRestoreRunnable() {
        final String title = savedAppMenuTitle;
        final String[] labels = savedAppMenuLabels;
        final boolean[] hasSubmenu = savedAppMenuHasSubmenu;
        final String sessionId = appMenuSessionId;
        final String callerPackage = appMenuCallerPackage;
        return new Runnable() {
            @Override
            public void run() {
                modalQuickSubTier = false;
                mediaSlidersActive = false;
                simpleNetworkTier = false;
                menu.hideSlider();
                showAppMenuMode(title, labels, hasSubmenu, sessionId, callerPackage);
            }
        };
    }

    /** Stock AlertDialog replacement — scrollable metadata body + dialog-style action rows. */
    public void showNativeDialogMode(String title, String message, String[] buttons,
            String sessionId, String callerPackage) {
        // 2026-07-06 — ANR/crash preempts USB; re-offer USB after user acts on error tier.
        if (usbStoragePromptVisible) {
            OverlayTierScheduler.queuePendingUsbPrompt();
        }
        powerTierVisible = false;
        appMenuTierVisible = false;
        launcherPickerVisible = false;
        volumeOnlyVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        modalQuickSubTier = false;
        clearModalSessions();
        dialogTierVisible = true;
        appMenuOpenedAt = SystemClock.uptimeMillis();
        dialogSessionId = sessionId;
        dialogCallerPackage = callerPackage;
        savedDialogTitle = title;
        savedDialogMessage = message;
        savedDialogButtons = buttons;
        dialogTierRestore = buildDialogRestoreRunnable();
        if (buttons == null || buttons.length == 0) {
            sendDialogResult(-1);
            if (!tryAdvanceToPendingUsbTier()) {
                dismissListener.onDismissOverlay();
            }
            return;
        }
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Boolean> headers = new ArrayList<Boolean>();
        labels.add(message != null ? message : "");
        headers.add(Boolean.TRUE);
        for (String label : buttons) {
            labels.add(label);
            headers.add(Boolean.FALSE);
        }
        String dialogTitle = title != null && title.length() > 0
                ? title : context.getString(R.string.context_menu_title);
        menu.setScrollableDetailHeader(true);
        menu.show(overlayRoot, dialogTitle, null, labels.toArray(new String[labels.size()]),
                null, null, toPrimitive(headers.toArray(new Boolean[headers.size()])),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index < 1) return;
                        finishNativeDialogSelection(index);
                    }
                }, rowHeightPx, panelWidthPx, true, false,
                buildQuickBar(), createModalQuickBarListener());
        menu.focusSubmenuList();
        menu.moveFocus(1);
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_NATIVE_ERROR);
    }

    /** 2026-07-06 — Deliver ANR/crash pick then advance to deferred USB tier in-place when queued. */
    private void finishNativeDialogSelection(int index) {
        dialogTierVisible = false;
        sendDialogResult(index);
        if (tryAdvanceToPendingUsbTier()) {
            return;
        }
        dismissListener.onDismissOverlay();
    }

    /** True while native ANR/crash/AlertDialog tier is painted — USB spawn must defer. */
    public boolean isNativeDialogVisible() {
        return dialogTierVisible;
    }

    /**
     * 2026-07-06 — Paint queued USB tier without overlay tear-down — same WM shell, key gate stays armed.
     * @return true when USB prompt replaced native tier in-place
     */
    public boolean tryAdvanceToPendingUsbTier() {
        if (!OverlayTierScheduler.tryConsumePendingUsbPrompt(context)) {
            OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_NONE);
            return false;
        }
        showUsbStoragePromptMode();
        return true;
    }

    /** Re-paint native dialog after quick-bar Wi‑Fi/volume sub-tier Back. */
    private Runnable buildDialogRestoreRunnable() {
        final String title = savedDialogTitle;
        final String message = savedDialogMessage;
        final String[] buttons = savedDialogButtons;
        final String sessionId = dialogSessionId;
        final String callerPackage = dialogCallerPackage;
        return new Runnable() {
            @Override
            public void run() {
                showNativeDialogMode(title, message, buttons, sessionId, callerPackage);
            }
        };
    }

    /**
     * 2026-07-08 — UMS exported lock — legacy Solar :overlay fail-open paint.
     * Layman: block screen saying disk mode is on; only Turn Off ends it (unplug also dismisses).
     * Technical: opaque themed detail + one action row; BACK swallowed (see finishOverlayBackHold).
     * Solar ThemedContextMenu is the preferred host; Chip path only if companion_shell=1.
     * Reversal: delete; restore launchSolarUsbHandoff → MainActivity.STATE_USB_STORAGE.
     */
    public void showUsbStorageLockMode() {
        powerTierVisible = false;
        appMenuTierVisible = false;
        dialogTierVisible = false;
        launcherPickerVisible = false;
        volumeOnlyVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        modalQuickSubTier = false;
        clearModalSessions();
        usbStoragePromptVisible = false;
        usbStorageLockVisible = true;
        usbStorageTierRestore = new Runnable() {
            @Override
            public void run() {
                showUsbStorageLockMode();
            }
        };
        ThemeManager.prepareThemeForUsbStorage(context);
        // Opaque root — covers Rockbox / third-party under the lock tier.
        if (overlayRoot != null) {
            overlayRoot.setBackgroundColor(ThemeManager.getOverlayBackgroundColor());
        }
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Boolean> headers = new ArrayList<Boolean>();
        String model = DeviceFeatures.productModelLabel();
        labels.add(context.getString(R.string.usb_storage_mode_body, model));
        headers.add(Boolean.TRUE);
        labels.add(context.getString(R.string.usb_storage_mode_turn_off));
        headers.add(Boolean.FALSE);
        menu.setScrollableDetailHeader(true);
        menu.show(overlayRoot, context.getString(R.string.usb_storage_mode_title), null,
                labels.toArray(new String[labels.size()]),
                null, null, toPrimitive(headers.toArray(new Boolean[headers.size()])),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index == 1) {
                            handleUsbStorageOverlayTurnOff();
                        }
                    }
                }, rowHeightPx, panelWidthPx, true, false,
                buildQuickBar(), createModalQuickBarListener());
        menu.focusSubmenuList();
        menu.moveFocus(1);
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_USB_LOCK);
        UsbStorageOverlayReceiver.notifyMainUsbLocked(context);
    }

    /**
     * 2026-07-08 — Turn Off from Solar :overlay lock fail-open path.
     * Layman: ends disk mode and closes block screen; idle until next USB plug.
     */
    private void handleUsbStorageOverlayTurnOff() {
        usbStorageLockVisible = false;
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_NONE);
        OverlayTierScheduler.clearPendingUsbPrompt();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UsbMassStorageController.disable(app);
            }
        }, "UsbUmsDisableLock").start();
        // markUserDismissed runs in UsbStorageLockSuspendReceiver via EXTRA_USB_FORCE_OFF.
        UsbStorageOverlayReceiver.notifyMainUsbUnlocked(app, true);
        dismissListener.onDismissOverlay();
    }

    /** SystemUI USB enable prompt — scrollable PC-connected notice + Turn on / Dismiss rows. */
    public void showUsbStoragePromptMode() {
        if (!UsbStorageSessionFlags.shouldOfferUsbConnectPromptAfterBootSettle(context)) {
            usbStoragePromptVisible = false;
            usbStorageTierRestore = null;
            OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_NONE);
            return;
        }
        // 2026-07-06 — Never stomp native error tier — queue USB for post-ANR handoff instead.
        if (dialogTierVisible) {
            OverlayTierScheduler.queuePendingUsbPrompt();
            return;
        }
        powerTierVisible = false;
        appMenuTierVisible = false;
        dialogTierVisible = false;
        launcherPickerVisible = false;
        volumeOnlyVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        modalQuickSubTier = false;
        clearModalSessions();
        usbStoragePromptVisible = true;
        usbStorageTierRestore = buildUsbStorageRestoreRunnable();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Boolean> headers = new ArrayList<Boolean>();
        labels.add(context.getString(R.string.usb_mass_storage_title));
        headers.add(Boolean.TRUE);
        labels.add(context.getString(R.string.usb_mass_storage_turn_on));
        headers.add(Boolean.FALSE);
        labels.add(context.getString(R.string.soulseek_pm_dismiss));
        headers.add(Boolean.FALSE);
        menu.setScrollableDetailHeader(true);
        menu.show(overlayRoot, context.getString(R.string.usb_connection_title), null,
                labels.toArray(new String[labels.size()]),
                null, null, toPrimitive(headers.toArray(new Boolean[headers.size()])),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        // #region agent log
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("index", index);
                            d.put("fg", ExternalInputHandoff.getForegroundPackageName(context));
                            Debug266f21Log.log(context, "OverlayModalHost.usbPrompt.onSelected",
                                    "overlay row selected", "H5", d);
                        } catch (Exception ignored) {}
                        // #endregion
                        if (index == 1) {
                            handleUsbStorageOverlayEnable();
                        } else if (index == 2) {
                            handleUsbStorageOverlayDismiss();
                        }
                    }
                }, rowHeightPx, panelWidthPx, true, false,
                buildQuickBar(), createModalQuickBarListener());
        menu.focusSubmenuList();
        menu.moveFocus(1);
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_USB);
    }

    private Runnable buildUsbStorageRestoreRunnable() {
        return new Runnable() {
            @Override
            public void run() {
                showUsbStoragePromptMode();
            }
        };
    }

    /** Bluetooth pairing — PIN keyboard or passkey/consent rows from PAIRING_REQUEST / Xposed hook. */
    public void showBluetoothPairingMode(int mode, String address, String name,
            int passkey, String pinPrefill) {
        powerTierVisible = false;
        appMenuTierVisible = false;
        dialogTierVisible = false;
        launcherPickerVisible = false;
        volumeOnlyVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        modalQuickSubTier = false;
        clearModalSessions();
        btPairingAddress = address;
        savedBtPairingMode = mode;
        savedBtPairingPasskey = passkey;
        savedBtPairingName = name;
        savedBtPairingPinPrefill = pinPrefill;
        bluetoothPairingPromptVisible = true;
        bluetoothPairingTierRestore = buildBluetoothPairingRestoreRunnable();
        if (wifiPasswordKeyboard != null) wifiPasswordKeyboard.dismiss();
        if (btPinKeyboard != null) btPinKeyboard.dismiss();
        // PIN and passkey entry use the digit wheel; display/confirmation variants use rows.
        if (mode == BluetoothPairingCoordinator.MODE_PIN
                || mode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY) {
            showBluetoothPinKeyboard(mode, address, name, pinPrefill);
            return;
        }
        showBluetoothPasskeyMenu(mode, name, passkey);
    }

    /** Immediate overlay digit keyboard for legacy PIN and numeric-passkey requests. */
    private void showBluetoothPinKeyboard(
            int mode, String address, String name, String pinPrefill) {
        if (btPinKeyboard == null) {
            btPinKeyboard = new OverlayBtPinKeyboard(context, overlayRoot, new Runnable() {
                @Override
                public void run() {
                    bluetoothPairingPromptVisible = false;
                    dismissListener.onDismissOverlay();
                }
            });
        }
        if (menu != null && menu.isShowing()) menu.dismiss();
        btPinKeyboard.show(mode, address, name, pinPrefill);
    }

    /** Passkey display, passkey match, or Just Works consent — scrollable detail + action rows. */
    private void showBluetoothPasskeyMenu(int mode, String name, int passkey) {
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Boolean> headers = new ArrayList<Boolean>();
        String deviceLabel = name != null && name.length() > 0 ? name : btPairingAddress;
        String body;
        String dialogTitle;
        if (mode == BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY) {
            dialogTitle = context.getString(R.string.bt_pairing_passkey_title, deviceLabel);
            body = context.getString(R.string.bt_pairing_passkey_body,
                    BluetoothPairingCoordinator.formatPasskey(passkey));
        } else if (mode == BluetoothPairingCoordinator.MODE_PIN_DISPLAY) {
            dialogTitle = context.getString(R.string.bt_pairing_passkey_title, deviceLabel);
            body = context.getString(R.string.bt_pairing_passkey_body,
                    BluetoothPairingCoordinator.formatDisplayPin(passkey));
        } else if (mode == BluetoothPairingCoordinator.MODE_PASSKEY_CONFIRM) {
            dialogTitle = context.getString(R.string.bt_pairing_match_title, deviceLabel);
            body = context.getString(R.string.bt_pairing_match_body,
                    BluetoothPairingCoordinator.formatPasskey(passkey));
        } else {
            dialogTitle = context.getString(R.string.bt_pairing_consent_title, deviceLabel);
            body = context.getString(R.string.bt_pairing_consent_body, deviceLabel);
        }
        labels.add(body);
        headers.add(Boolean.TRUE);
        if (mode == BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY
                || mode == BluetoothPairingCoordinator.MODE_PIN_DISPLAY) {
            labels.add(context.getString(R.string.bt_pairing_passkey_ok));
            headers.add(Boolean.FALSE);
        } else {
            labels.add(context.getString(R.string.bt_pairing_pair));
            headers.add(Boolean.FALSE);
            labels.add(context.getString(R.string.soulseek_pm_dismiss));
            headers.add(Boolean.FALSE);
        }
        menu.setScrollableDetailHeader(true);
        menu.show(overlayRoot, dialogTitle, null, labels.toArray(new String[labels.size()]),
                null, null, toPrimitive(headers.toArray(new Boolean[headers.size()])),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        handleBluetoothPairingRowPick(mode, index);
                    }
                }, rowHeightPx, panelWidthPx, true, false,
                null, null);
        menu.focusSubmenuList();
        menu.moveFocus(1);
    }

    private void handleBluetoothPairingRowPick(int mode, int index) {
        boolean submitted = true;
        if (mode == BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY
                || mode == BluetoothPairingCoordinator.MODE_PIN_DISPLAY) {
            if (index == 1) {
                BluetoothPairingCoordinator.dismissPasskeyDisplaySession();
            }
        } else if (index == 1) {
            if (mode == BluetoothPairingCoordinator.MODE_OOB_CONSENT) {
                submitted = BluetoothPairingCoordinator.submitOobConsentFromOverlay(
                        context, btPairingAddress, true);
            } else {
                submitted = BluetoothPairingCoordinator.submitConfirmationFromOverlay(
                        context, btPairingAddress, true);
            }
        } else if (index == 2) {
            BluetoothPairingCoordinator.cancelPairing(context, btPairingAddress);
        }
        if (!submitted) {
            Toast.makeText(context, R.string.bt_pairing_submit_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        bluetoothPairingPromptVisible = false;
        dismissListener.onDismissOverlay();
    }

    /** Re-paint BT pairing tier after quick-bar sub-tier Back. */
    private Runnable buildBluetoothPairingRestoreRunnable() {
        final int mode = savedBtPairingMode;
        final String name = savedBtPairingName;
        final int passkey = savedBtPairingPasskey;
        final String pinPrefill = savedBtPairingPinPrefill;
        final String address = btPairingAddress;
        return new Runnable() {
            @Override
            public void run() {
                if (mode == BluetoothPairingCoordinator.MODE_PIN
                        || mode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY) {
                    showBluetoothPinKeyboard(mode, address, name, pinPrefill);
                } else {
                    showBluetoothPasskeyMenu(mode, name, passkey);
                }
            }
        };
    }

    /**
     * 2026-07-08 — User chose Turn on USB storage — enable UMS then paint lock overlay.
     * 2026-07-10 — Enable first; lock only when LUN is exported (no sticky false lock).
     * Was: dismiss + lock immediately while enable ran async.
     * Reversal: paint lock before enable if UX needs instant block (risks false lock).
     */
    private void handleUsbStorageOverlayEnable() {
        if (!UsbMassStorageExperiment.isEnabled(context)) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("a5", DeviceFeatures.isA5());
                Debug1fc727Log.log(context, "OverlayModalHost.handleUsbStorageOverlayEnable",
                        "experiment off → dismiss", "H2,H4", d);
            } catch (Exception ignored) {}
            // #endregion
            handleUsbStorageOverlayDismiss();
            return;
        }
        usbStoragePromptVisible = false;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("action", "enable");
            d.put("a5", DeviceFeatures.isA5());
            DebugEdc27bLog.log("OverlayModalHost.handleUsbStorageOverlayEnable",
                    "user confirmed USB enable", "USB-F2", d);
            Debug1fc727Log.log(context, "OverlayModalHost.handleUsbStorageOverlayEnable",
                    "user confirmed USB enable", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        final Context app = context.getApplicationContext();
        ThemeManager.prepareThemeForUsbStorage(app);
        // 2026-07-16 — Hand off to Solar eject screen first (blocks menus + stops media), then enable.
        // Was: only launch after enable succeeded — re-enum blips left user without a lock UI.
        UsbStorageOverlayReceiver.launchSolarUsbHandoff(app, false, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = UsbMassStorageController.enable(app, "user.overlay.confirm");
                final boolean exported = UsbMassStorageController.isMassStorageExported();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("ok", ok);
                    d.put("exported", exported);
                    d.put("a5", DeviceFeatures.isA5());
                    Debug1fc727Log.log(app, "OverlayModalHost.handleUsbStorageOverlayEnable",
                            "enable thread result", "H1,H3", d);
                } catch (Exception ignored) {}
                // #endregion
                if (ok || exported || UsbMassStorageController.isKernelMassStorageMode()) {
                    // Re-assert Solar lock after USB re-enum.
                    UsbStorageOverlayReceiver.launchSolarUsbHandoff(app, false, true);
                } else {
                    UsbMassStorageController.clearUserSession();
                }
            }
        }, "UsbUmsEnableEarly").start();
        // Tear down enable prompt; Solar owns STATE_USB_STORAGE for the rest of the cable session.
        dismissListener.onDismissOverlay();
    }

    /** User dismissed enable prompt — stay in third-party app; no recovery HOME storm. */
    private void handleUsbStorageOverlayDismiss() {
        usbStoragePromptVisible = false;
        OverlayTierScheduler.setActiveTier(OverlayTierScheduler.TIER_NONE);
        OverlayTierScheduler.clearPendingUsbPrompt();
        UsbStorageSessionFlags.markOverlayDismissPending(context);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("action", "dismiss");
            DebugEdc27bLog.log("OverlayModalHost.handleUsbStorageOverlayDismiss",
                    "overlay dismiss only", "USB-F3", d);
        } catch (Exception ignored) {}
        // #endregion
        dismissListener.onDismissOverlay();
    }

    public ThemedContextMenu getMenu() {
        return menu;
    }

    /**
     * Y2 hardware volume (and volume while power tier is open) — jump to global volume slider.
     * Rockbox / third-party apps get here via VolumePanelHooks; keys via OverlayKeyForwarder.
     */
    private boolean handleGlobalOverlayVolumeKey(int keyCode) {
        if (!Y1InputKeys.isVolumeDownKey(keyCode) && !Y1InputKeys.isVolumeUpKey(keyCode)) {
            return false;
        }
        boolean up = Y1InputKeys.isVolumeUpKey(keyCode);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("up", up);
            d.put("keyCode", keyCode);
            d.put("fg", ExternalInputHandoff.getForegroundPackageName(context));
            d.put("volumeOnly", volumeOnlyVisible);
            d.put("mediaSliders", mediaSlidersActive);
            Debug6d1aeeLog.log("OverlayModalHost.handleGlobalOverlayVolumeKey", "entry", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
        MediaVolumeControl.adjustMedia(context, up);
        // 2026-07-15 — Any open context shell → replace with compact volume (not in-place chip sync).
        // Was: power/queue/network kept full tiers and only moved the slider. Reversal: restore elif sync.
        if (volumeOnlyVisible || mediaSlidersActive) {
            MediaVolumeControl.syncVolumeSliderUi(context, menu);
            if (volumeOnlyVisible) scheduleVolumeDismiss();
        } else {
            showVolumeMode(false);
        }
        return true;
    }

    public boolean handleOverlayKeyDown(int keyCode) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("keyCode", keyCode);
            d.put("menuShowing", menu != null && menu.isShowing());
            DebugEdc27bLog.log("OverlayModalHost.handleOverlayKeyDown", "entry", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
        if (wifiPasswordKeyboard != null && wifiPasswordKeyboard.isShowing()) {
            return wifiPasswordKeyboard.handleKeyDown(keyCode);
        }
        if (btPinKeyboard != null && btPinKeyboard.isShowing()) {
            return btPinKeyboard.handleKeyDown(keyCode);
        }
        if (menu == null || !menu.isShowing()) return false;
        if (handleOverlayBackgroundTransportKeyDown(keyCode)) return true;
        if (handleGlobalOverlayVolumeKey(keyCode)) return true;
        if (queueTierVisible && menu.isQueueMode()) {
            if (handleOverlayQueueKeyDown(keyCode)) return true;
        }
        if (volumeOnlyVisible) {
            if (Y1InputKeys.isWheelKey(keyCode) || Y1InputKeys.isVolumeDownKey(keyCode)
                    || Y1InputKeys.isVolumeUpKey(keyCode)) {
                // ponytail: wheel down = raise — same as MainActivity.adjustVolume(isWheelDown).
                boolean up = Y1InputKeys.isVolumeUpKey(keyCode)
                        || (Y1InputKeys.isWheelKey(keyCode) && Y1InputKeys.isWheelDown(keyCode));
                MediaVolumeControl.adjustMedia(context, up);
                MediaVolumeControl.syncVolumeSliderUi(context, menu);
                scheduleVolumeDismiss();
                return true;
            }
        }
        if (mediaSlidersActive && handleMediaSliderKeys(keyCode)) {
            return true;
        }
        if (Y1InputKeys.isWheelKey(keyCode)) {
            if (menu.handleKeyHorizontal(keyCode)) return true;
        }
        if (Y1InputKeys.isWheelUp(keyCode)) {
            if (menu.focusZone() == ThemedContextMenu.FocusZone.QUICK_BAR) {
                menu.moveQuickBarFocus(-1);
            } else {
                menu.moveFocus(-1);
            }
            return true;
        }
        if (Y1InputKeys.isWheelDown(keyCode)) {
            if (menu.focusZone() == ThemedContextMenu.FocusZone.QUICK_BAR) {
                if (menu.isOnLastVisibleQuickChip() && !menu.isMediaSliderStripVisible()) {
                    menu.enterListFromLastQuickChip();
                } else {
                    menu.moveQuickBarFocus(1);
                }
            } else {
                menu.moveFocus(1);
            }
            return true;
        }
        if (Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isPlayPauseKey(keyCode)) {
            if (appMenuTierVisible || dialogTierVisible) {
                // Long OK/MENU open gesture — activate on key-up after grace, never on down.
                return true;
            }
            if (queueTierVisible && menu.isQueueMode()) {
                if (queueMoveFrom >= 0) {
                    return true;
                }
                // 2026-07-08 — Long-press move only when list focused; quick-bar chips use KEY_UP activate.
                if (isOverlayQueueListFocused()) {
                    centerKeyDownAt = System.currentTimeMillis();
                    centerMovePickHandled = false;
                    dismissHandler.removeCallbacks(centerQueueMoveRunnable);
                    dismissHandler.postDelayed(centerQueueMoveRunnable, CENTER_QUEUE_MOVE_MS);
                    return true;
                }
                // Quick bar over queue — fall through to KEY_UP activateFocused (Power / Wi‑Fi chips).
                return true;
            }
            // 2026-07-08 — Quick bar / Power / Wi‑Fi activate on KEY_UP only.
            // Was: menu.activateFocused() here — held OK could auto-repeat into Restart → reboot.
            // Now: swallow DOWN; handleOverlayKeyUp + OverlayCenterActivation fire the tap.
            // Reversal: restore menu.activateFocused() here (hold Power chip may restart again).
            return true;
        }
        if (Y1InputKeys.isBackKey(keyCode)) {
            if (powerTierVisible
                    && (suppressBackDismissUntilLift
                    || SystemClock.uptimeMillis() - powerTierOpenedAt < backDismissGraceMs)) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("graceMs", backDismissGraceMs);
                    d.put("suppressLift", suppressBackDismissUntilLift);
                    DebugInputLog.log("OverlayModalHost.handleOverlayKeyDown",
                            "back open-gesture grace", "H-BACK-OPEN", d);
                } catch (Exception ignored) {}
                // #endregion
                return true;
            }
            beginOverlayBackHold();
            if (mediaSlidersActive || simpleNetworkTier || queueTierVisible) {
                cancelOverlayBackHold();
                leaveSubTier();
                return true;
            }
            if (powerTierVisible) {
                return true;
            }
            if (launcherPickerVisible) {
                return true;
            }
            return true;
        }
        return true;
    }

    /** Center key-up — short tap activates; long hold starts queue move pick (matches MainActivity). */
    public boolean handleOverlayKeyUp(int keyCode) {
        if (wifiPasswordKeyboard != null && wifiPasswordKeyboard.isShowing()) {
            return wifiPasswordKeyboard.handleKeyUp(keyCode);
        }
        if (btPinKeyboard != null && btPinKeyboard.isShowing()) {
            return btPinKeyboard.handleKeyUp(keyCode);
        }
        if (menu == null || !menu.isShowing()) return false;
        if ((appMenuTierVisible || dialogTierVisible)
                && (Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isPlayPauseKey(keyCode))) {
            if (SystemClock.uptimeMillis() - appMenuOpenedAt < APP_MENU_CENTER_GRACE_MS) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("keyCode", keyCode);
                    d.put("graceMs", APP_MENU_CENTER_GRACE_MS);
                    DebugAgentLog.log(context, "OverlayModalHost.handleOverlayKeyUp",
                            "app menu center grace", "H-MENU", d);
                } catch (Exception ignored) {}
                // #endregion
                return true;
            }
            menu.activateFocused();
            return true;
        }
        if (queueTierVisible && menu.isQueueMode()
                && (Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isPlayPauseKey(keyCode))) {
            dismissHandler.removeCallbacks(centerQueueMoveRunnable);
            centerKeyDownAt = 0;
            if (centerMovePickHandled) {
                centerMovePickHandled = false;
                return true;
            }
            // 2026-07-08 — List row play on UP; quick-bar chips fall through to grace + activateFocused.
            if (isOverlayQueueListFocused()) {
                handleOverlayQueueActivate(false);
                return true;
            }
        }
        // 2026-07-08 — Global quick / Power / Wi‑Fi / BT: activate on release like in-app menu.
        // Was: KEY_DOWN in handleOverlayKeyDown. Now: KEY_UP + sub-tier grace blocks Restart auto-fire.
        // Reversal: delete this branch; move activateFocused() back to KEY_DOWN.
        if (Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isPlayPauseKey(keyCode)) {
            long now = SystemClock.uptimeMillis();
            if (!OverlayCenterActivation.shouldActivateOnEvent(true, false, now, subTierChangedAt,
                    OverlayCenterActivation.SUB_TIER_CENTER_GRACE_MS)) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("keyCode", keyCode);
                    d.put("sinceSubTierMs", now - subTierChangedAt);
                    d.put("graceMs", OverlayCenterActivation.SUB_TIER_CENTER_GRACE_MS);
                    DebugAgentLog.log(context, "OverlayModalHost.handleOverlayKeyUp",
                            "sub-tier center grace", "H-POWER", d);
                } catch (Exception ignored) {}
                // #endregion
                return true;
            }
            menu.activateFocused();
            return true;
        }
        if (handleOverlayBackgroundTransportKeyUp(keyCode)) return true;
        if (Y1InputKeys.isBackKey(keyCode)) {
            return finishOverlayBackHoldAndMaybeDismiss();
        }
        return false;
    }

    /**
     * 2026-07-08 — Arm continuous 10s rescue on BACK down while global overlay owns keys.
     * Layman: keeping Back down after the quick menu opens can still restart Solar at 10s.
     * Technical: deadline = DOWN + RESCUE_HOLD_MS; UP calls cancelOverlayBackHold → disarm.
     */
    private void beginOverlayBackHold() {
        overlayBackDownAt = SystemClock.uptimeMillis();
        overlayBackRescueFired = false;
        dismissHandler.removeCallbacks(overlayBackRescueRunnable);
        SolarRescueHoldState.armFromHoldStart(SolarRescueHoldState.KIND_BACK, overlayBackDownAt);
        SolarRescueHoldHost.ping(context);
        dismissHandler.postDelayed(overlayBackRescueRunnable, SolarRescueHoldState.RESCUE_HOLD_MS);
    }

    /** 2026-07-08 — Finger up before rescue — disarm HUD/sysprops unless restart already fired. */
    private void cancelOverlayBackHold() {
        dismissHandler.removeCallbacks(overlayBackRescueRunnable);
        if (!overlayBackRescueFired) {
            SolarRescueHoldState.disarm();
        }
        overlayBackDownAt = 0L;
    }

    /**
     * 2026-07-07 — First lift after open is ignored; a fresh 150ms BACK hold closes the overlay.
     * Layman: release-to-open never dismisses, quick taps do nothing, deliberate hold closes.
     */
    private boolean finishOverlayBackHoldAndMaybeDismiss() {
        if (overlayBackDownAt == 0L) {
            if (suppressBackDismissUntilLift) {
                suppressBackDismissUntilLift = false;
                return true;
            }
            return false;
        }
        if (overlayBackRescueFired) {
            overlayBackDownAt = 0L;
            return true;
        }
        long held = SystemClock.uptimeMillis() - overlayBackDownAt;
        cancelOverlayBackHold();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("heldMs", held);
            d.put("powerTier", powerTierVisible);
            d.put("sinceOpenMs", SystemClock.uptimeMillis() - powerTierOpenedAt);
            d.put("suppressLift", suppressBackDismissUntilLift);
            DebugE93bdbLog.log("OverlayModalHost.finishOverlayBackHoldAndMaybeDismiss",
                    "back up", "H4", d);
        } catch (Exception ignored) {}
        // #endregion
        if (suppressBackDismissUntilLift) {
            suppressBackDismissUntilLift = false;
            return true;
        }
        if (held < BACK_HOLD_DISMISS_MS) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("heldMs", held);
                d.put("needMs", BACK_HOLD_DISMISS_MS);
                d.put("powerTier", powerTierVisible);
                DebugE0de2eLog.log(context, "OverlayModalHost.finishOverlayBackHoldAndMaybeDismiss",
                        "short Back ignored (need hold)", "H5", d);
            } catch (Exception ignored) {}
            // #endregion
            return true;
        }
        // 2026-07-08 — USB lock ignores BACK dismiss (must Turn Off or unplug).
        if (usbStorageLockVisible) {
            return true;
        }
        if (powerTierVisible) {
            if (SystemClock.uptimeMillis() - powerTierOpenedAt < backDismissGraceMs) {
                return true;
            }
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("elapsed", SystemClock.uptimeMillis() - powerTierOpenedAt);
                DebugEdc27bLog.log("OverlayModalHost.finishOverlayBackHoldAndMaybeDismiss",
                        "back dismiss power tier", "BACK-FLASH", d);
            } catch (Exception ignored) {}
            // #endregion
            dismissListener.onDismissOverlay();
            return true;
        }
        if (launcherPickerVisible) {
            launcherPickerVisible = false;
            dismissListener.onDismissOverlay();
            return true;
        }
        sendOverlayModalCancelResult();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("tier", powerTierVisible ? "power" : "modal");
            DebugEdc27bLog.log("OverlayModalHost.finishOverlayBackHoldAndMaybeDismiss",
                    "back dismiss overlay", "BACK-F1", d);
        } catch (Exception ignored) {}
        // #endregion
        dismissListener.onDismissOverlay();
        return true;
    }

    /** Queue list rows — not quick bar / slider while queue tier is open. */
    private boolean isOverlayQueueListFocused() {
        return menu.focusZone() == ThemedContextMenu.FocusZone.TIER_CONTENT;
    }

    /** 2026-07-08 — Record when a submenu paints so the opening key's UP is ignored. */
    private void markSubTierChanged() {
        subTierChangedAt = SystemClock.uptimeMillis();
    }

    /**
     * Side prev/next and dedicated play/pause — Solar transport while modal is up over Rockbox/other apps.
     * Wheel (126/127) stays list navigation; center (66) stays OK in queue tier.
     */
    private boolean handleOverlayBackgroundTransportKeyDown(int keyCode) {
        if (volumeOnlyVisible) return false;
        // Rockbox owns playback — do not drive Solar transport from global overlay.
        if (LauncherSwitch.isRockboxForeground(context)) return false;
        // Side skip + dedicated play/pause always hit background transport (even in queue tier).
        if (Y1InputKeys.isTrackNextKey(keyCode)) {
            OverlayPlaybackClient.skipNext(context);
            return true;
        }
        if (Y1InputKeys.isTrackPreviousKey(keyCode)) {
            OverlayPlaybackClient.skipPrevious(context);
            return true;
        }
        if (isDedicatedPlayPauseKey(keyCode)) {
            OverlayPlaybackClient.togglePlayPause(context);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("queueTier", queueTierVisible);
                DebugInputLog.log("OverlayModalHost.handleOverlayBackgroundTransportKeyDown",
                        "play/pause transport", "H-PP", d);
            } catch (Exception ignored) {}
            // #endregion
            return true;
        }
        // Queue list rows — wheel scroll only; transport handled above.
        if (queueTierVisible && menu.isQueueMode()) {
            if (isOverlayQueueListFocused()) return false;
            if (Y1InputKeys.isWheelKey(keyCode)) return false;
        }
        return false;
    }

    private boolean handleOverlayBackgroundTransportKeyUp(int keyCode) {
        return Y1InputKeys.isTrackNextKey(keyCode) || Y1InputKeys.isTrackPreviousKey(keyCode)
                || isDedicatedPlayPauseKey(keyCode);
    }

    private boolean handleOverlayQueueKeyDown(int keyCode) {
        if (Y1InputKeys.isWheelUp(keyCode)) {
            moveOverlayQueueFocus(-1);
            return true;
        }
        if (Y1InputKeys.isWheelDown(keyCode)) {
            moveOverlayQueueFocus(1);
            return true;
        }
        if (Y1InputKeys.isBackKey(keyCode)) {
            if (queueMoveFrom >= 0) {
                menu.setQueueMoveFrom(-1);
                queueMoveFrom = -1;
                queueMoveWheelFilter.reset();
                refreshOverlayQueueTier(false);
            } else {
                leaveSubTier();
            }
            return true;
        }
        return false;
    }

    private void moveOverlayQueueFocus(int delta) {
        int size = overlayQueueSize();
        if (size <= 0) return;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("delta", delta);
            d.put("moveFrom", queueMoveFrom);
            d.put("focus", queueFocusIndex);
            DebugInputLog.log("OverlayModalHost.moveOverlayQueueFocus", "wheel queue scroll", "H4", d);
        } catch (Exception ignored) {}
        // #endregion
        if (queueMoveFrom >= 0) {
            if (menu.isQueueMoveRibbonAnimating()) return;
            if (!queueMoveWheelFilter.accept()) return;
            int next = QueueMoveWindow.nextMoveIndex(queueMoveFrom, delta, size);
            if (next != queueMoveFrom) {
                int from = queueMoveFrom;
                OverlayPlaybackClient.moveQueueItem(context, from, next);
                overlayQueue = OverlayQueueHelper.loadQueue(context);
                menu.applyQueueReorderLive(from, next, overlayQueue.index(), false);
                queueMoveFrom = next;
                queueFocusIndex = next;
            }
            return;
        }
        queueFocusIndex = Math.max(0, Math.min(queueFocusIndex + delta, size - 1));
        menu.replaceQueueContent(context.getString(R.string.context_quick_queue),
                OverlayQueueHelper.buildRowSpecs(overlayQueue), queueFocusIndex, queueMoveFrom);
    }

    private void handleOverlayQueueActivate(boolean longPress) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("longPress", longPress);
            d.put("moveFrom", queueMoveFrom);
            d.put("focus", queueFocusIndex);
            DebugInputLog.log("OverlayModalHost.handleOverlayQueueActivate", "center activate", "H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (queueMoveFrom >= 0) {
            int idx = queueFocusIndex;
            if (queueMoveFrom == idx) {
                menu.finishQueueMove(idx);
                queueMoveFrom = -1;
                queueMoveWheelFilter.reset();
                overlayQueue = OverlayQueueHelper.loadQueue(context);
            } else {
                OverlayPlaybackClient.moveQueueItem(context, queueMoveFrom, idx);
                overlayQueue = OverlayQueueHelper.loadQueue(context);
                menu.applyQueueReorderLive(queueMoveFrom, idx, overlayQueue.index(), false);
                queueMoveFrom = idx;
                queueMoveWheelFilter.reset();
            }
            return;
        }
        if (longPress) {
            if (queueFocusIndex >= 0 && queueFocusIndex < overlayQueueSize()) {
                queueMoveFrom = queueFocusIndex;
                menu.setQueueMoveFrom(queueFocusIndex);
                queueMoveWheelFilter.reset();
            }
            return;
        }
        OverlayPlaybackClient.playQueueIndex(context, queueFocusIndex);
        overlayQueue = OverlayQueueHelper.loadQueue(context);
        refreshOverlayQueueTier(false);
    }

    private int overlayQueueSize() {
        return overlayQueue != null ? overlayQueue.size() : 0;
    }
    private boolean handleMediaSliderKeys(int keyCode) {
        if (!Y1InputKeys.isWheelKey(keyCode)
                && !Y1InputKeys.isVolumeDownKey(keyCode)
                && !Y1InputKeys.isVolumeUpKey(keyCode)) {
            return false;
        }
        // ponytail: wheel down = raise — matches MainActivity context slider tiers.
        boolean up = Y1InputKeys.isVolumeUpKey(keyCode)
                || (Y1InputKeys.isWheelKey(keyCode) && Y1InputKeys.isWheelDown(keyCode));
        if (isBrightnessChipFocused()) {
            systemBrightness = SystemBrightnessControl.adjustAndApply(context, systemBrightness, up);
            menu.updateBrightnessSlider(systemBrightness, SystemBrightnessControl.MAX);
            return true;
        }
        if (isVolumeChipFocused()
                || menu.focusZone() == ThemedContextMenu.FocusZone.SLIDER) {
            MediaVolumeControl.adjustMedia(context, up);
            MediaVolumeControl.syncVolumeSliderUi(context, menu);
            return true;
        }
        return false;
    }

    private boolean isVolumeChipFocused() {
        return menu.focusZone() == ThemedContextMenu.FocusZone.QUICK_BAR
                && menu.quickFocusIndex() == QUICK_VOLUME;
    }

    private boolean isBrightnessChipFocused() {
        return menu.focusZone() == ThemedContextMenu.FocusZone.QUICK_BAR
                && menu.quickFocusIndex() == QUICK_BRIGHTNESS;
    }

    private void showOverlayQueueTier() {
        powerTierVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        queueTierVisible = true;
        menu.setQuickReturnIndex(QUICK_NOW_PLAYING);
        overlayQueue = OverlayQueueHelper.loadQueue(context);
        if (overlayQueue == null || overlayQueue.isEmpty()) {
            menu.replaceListContent(context.getString(R.string.context_quick_queue),
                    new String[] {context.getString(R.string.library_queue_empty)},
                    null, null, new boolean[] {false},
                    new ThemedContextMenu.Listener() {
                        @Override
                        public void onSelected(int index) { }
                    }, true);
            menu.focusSubmenuList();
            return;
        }
        queueFocusIndex = overlayQueue.index();
        queueMoveFrom = -1;
        queueMoveWheelFilter.reset();
        refreshOverlayQueueTier(true);
    }

    private void refreshOverlayQueueTier(boolean resetFocus) {
        overlayQueue = OverlayQueueHelper.loadQueue(context);
        if (overlayQueue == null || overlayQueue.isEmpty()) {
            showOverlayQueueTier();
            return;
        }
        if (resetFocus) {
            queueFocusIndex = overlayQueue.index();
        }
        menu.replaceQueueContent(context.getString(R.string.context_quick_queue),
                OverlayQueueHelper.buildRowSpecs(overlayQueue), queueFocusIndex, queueMoveFrom);
        menu.focusSubmenuList();
    }

    private void leaveSubTierToPowerList() {
        queueTierVisible = false;
        queueMoveFrom = -1;
        mediaSlidersActive = false;
        simpleNetworkTier = false;
        menu.hideSlider();
        refreshPowerTier(false);
        menu.setQuickReturnIndex(QUICK_POWER);
        menu.focusSubmenuList();
        menu.requestOverlayFocus();
    }

    /** Back from quick-bar sub-tier — restore dialog/app menu when open, else power list. */
    private void leaveSubTier() {
        modalQuickSubTier = false;
        // 2026-07-08 — Leaving Power list opened from APP_MENU must restore Solar Home options.
        // Was: powerTier stayed sticky; Back jumped to dismiss. Now: clear power, run appMenuTierRestore.
        if (powerTierVisible && appMenuTierVisible && appMenuTierRestore != null) {
            powerTierVisible = false;
            mediaSlidersActive = false;
            simpleNetworkTier = false;
            menu.hideSlider();
            appMenuTierRestore.run();
            return;
        }
        if (dialogTierVisible && dialogTierRestore != null) {
            mediaSlidersActive = false;
            simpleNetworkTier = false;
            menu.hideSlider();
            dialogTierRestore.run();
            return;
        }
        if (appMenuTierVisible && appMenuTierRestore != null) {
            mediaSlidersActive = false;
            simpleNetworkTier = false;
            menu.hideSlider();
            appMenuTierRestore.run();
            return;
        }
        if (usbStoragePromptVisible && usbStorageTierRestore != null) {
            mediaSlidersActive = false;
            simpleNetworkTier = false;
            menu.hideSlider();
            usbStorageTierRestore.run();
            return;
        }
        if (bluetoothPairingPromptVisible && bluetoothPairingTierRestore != null) {
            mediaSlidersActive = false;
            simpleNetworkTier = false;
            menu.hideSlider();
            bluetoothPairingTierRestore.run();
            return;
        }
        leaveSubTierToPowerList();
    }

    private void showOverlayMediaSliders(int quickIndex) {
        // 2026-07-08 — Chip open → list focus; grace stops same-press UP from firing slider confirm.
        markSubTierChanged();
        powerTierVisible = false;
        mediaSlidersActive = true;
        simpleNetworkTier = false;
        modalQuickSubTier = dialogTierVisible || appMenuTierVisible || usbStoragePromptVisible
                || bluetoothPairingPromptVisible;
        systemBrightness = SystemBrightnessControl.read(context);
        int volMax = MediaVolumeControl.getDisplayMaxVolume(context);
        int volCur = MediaVolumeControl.getMediaDisplayVolume(context);
        menu.setQuickReturnIndex(quickIndex);
        menu.showMediaSlidersWithQuickBarFocus(quickIndex,
                context.getString(R.string.context_quick_volume), volMax, volCur,
                context.getString(R.string.context_quick_brightness),
                SystemBrightnessControl.MAX, systemBrightness);
        menu.requestOverlayFocus();
    }

    private void showOverlayWifiTier() {
        // 2026-07-08 — Same grace as Power chip: release must not auto-pick first Wi‑Fi row.
        markSubTierChanged();
        powerTierVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = true;
        queueTierVisible = false;
        modalQuickSubTier = dialogTierVisible || appMenuTierVisible || usbStoragePromptVisible
                || bluetoothPairingPromptVisible;
        menu.setQuickReturnIndex(QUICK_WIFI);
        OverlayWifiTierHelper.requestScan(context);
        refreshOverlayWifiTier(true);
    }

    private void refreshOverlayWifiTier(boolean resetFocus) {
        final List<OverlayWifiTierHelper.Row> rows = OverlayWifiTierHelper.buildRows(context);
        if (rows.isEmpty()) return;
        String[] labels = new String[rows.size()];
        String[] states = new String[rows.size()];
        boolean[] headers = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            OverlayWifiTierHelper.Row row = rows.get(i);
            labels[i] = row.label;
            states[i] = row.stateText;
            headers[i] = row.header;
        }
        final WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        final boolean wifiOn = wifi != null && wifi.isWifiEnabled();
        menu.replaceListContent(context.getString(R.string.context_tier_wifi),
                labels, null, states, headers,
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index < 0 || index >= rows.size()) return;
                        OverlayWifiTierHelper.Row row = rows.get(index);
                        if (row.toggleRow) {
                            if (wifi != null) {
                                wifi.setWifiEnabled(!wifiOn);
                            }
                            dismissHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    OverlayWifiTierHelper.requestScan(context);
                                    refreshOverlayWifiTier(false);
                                }
                            }, 600);
                            return;
                        }
                        if (row.ssid != null) {
                            final String ssid = row.ssid;
                            OverlayWifiTierHelper.connect(context, ssid, new Runnable() {
                                @Override
                                public void run() {
                                    refreshOverlayWifiTier(false);
                                }
                            }, new Runnable() {
                                @Override
                                public void run() {
                                    if (wifiPasswordKeyboard == null) {
                                        wifiPasswordKeyboard = new OverlayWifiPasswordKeyboard(
                                                context, overlayRoot, new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        refreshOverlayWifiTier(false);
                                                    }
                                                });
                                    }
                                    wifiPasswordKeyboard.show(ssid);
                                }
                            });
                        }
                    }
                }, resetFocus);
        menu.focusSubmenuList();
    }

    private void showOverlayBluetoothTier() {
        // 2026-07-08 — Grace after BT chip so UP does not toggle Bluetooth immediately.
        markSubTierChanged();
        powerTierVisible = false;
        mediaSlidersActive = false;
        simpleNetworkTier = true;
        modalQuickSubTier = dialogTierVisible || appMenuTierVisible || usbStoragePromptVisible
                || bluetoothPairingPromptVisible;
        menu.setQuickReturnIndex(QUICK_BT);
        refreshOverlayBluetoothTier(true);
    }

    private void refreshOverlayBluetoothTier(boolean resetFocus) {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        final boolean on = bt != null && bt.isEnabled();
        String status = context.getString(on ? R.string.context_bluetooth_on : R.string.context_bluetooth_off);
        String toggle = context.getString(on ? R.string.overlay_bt_toggle_off : R.string.overlay_bt_toggle_on);
        menu.replaceListContent(context.getString(R.string.home_menu_bluetooth),
                new String[] {status, toggle}, null, null,
                new boolean[] {true, false},
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index != 1 || bt == null) return;
                        if (on) {
                            bt.disable();
                        } else {
                            bt.enable();
                        }
                        refreshOverlayBluetoothTier(false);
                    }
                }, resetFocus);
        menu.focusSubmenuList();
    }

    private final ArrayList<Runnable> powerRowActions = new ArrayList<Runnable>();

    private ThemedContextMenu.Listener createListListener() {
        return new ThemedContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                if (!powerTierVisible || index < 0 || index >= powerRowActions.size()) return;
                Runnable action = powerRowActions.get(index);
                if (action != null) action.run();
            }
        };
    }

    private void showPowerConfirm(String title, String message, String confirmLabel,
            final Runnable onConfirm) {
        // 2026-07-08 — Confirm list opens with focus on message/actions; grace blocks chained OK.
        markSubTierChanged();
        String[] labels = new String[] {message, confirmLabel, context.getString(R.string.common_cancel)};
        Boolean[] headers = new Boolean[] {Boolean.TRUE, Boolean.FALSE, Boolean.FALSE};
        menu.setScrollableDetailHeader(true);
        menu.replaceListContent(title, labels, null, null, toPrimitive(headers),
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        if (index == 1) {
                            onConfirm.run();
                            dismissListener.onDismissOverlay();
                        } else if (index == 2) {
                            refreshPowerTier(false);
                        }
                    }
                }, false);
        menu.focusSubmenuList();
    }

    private void refreshPowerTier(boolean resetFocus) {
        // 2026-07-08 — Power chip / list refresh focuses Restart; stamp so KEY_UP cannot reboot.
        markSubTierChanged();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Boolean> headers = new ArrayList<Boolean>();
        powerRowActions.clear();

        // 2026-07-18 — PC host only: Turn on USB storage at top of Power list (not charger-only).
        // Reversal: remove this block — Power stays Restart/Shutdown (+ launcher rows).
        if (UsbHostPresence.shouldOfferEnableUsbStorageInPowerMenu(context)) {
            headers.add(Boolean.FALSE);
            labels.add(context.getString(R.string.usb_mass_storage_turn_on));
            powerRowActions.add(new Runnable() {
                @Override public void run() {
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("from", "overlay_power");
                        com.solar.launcher.Debug0f5debLog.log(context,
                                "OverlayModalHost.refreshPowerTier",
                                "enable USB from power", "USB-PWR", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    UsbStorageOverlayReceiver.launchSolarUsbHandoff(context, true, false);
                    dismissListener.onDismissOverlay();
                }
            });
        }

        headers.add(Boolean.FALSE);
        labels.add(context.getString(R.string.context_restart_confirm));
        powerRowActions.add(new Runnable() {
            @Override public void run() {
                showPowerConfirm(
                        context.getString(R.string.context_restart_title),
                        context.getString(R.string.context_restart_message),
                        context.getString(R.string.context_restart_confirm),
                        new Runnable() {
                            @Override public void run() { PowerActions.restart(context); }
                        });
            }
        });

        headers.add(Boolean.FALSE);
        labels.add(context.getString(R.string.context_shutdown_confirm));
        powerRowActions.add(new Runnable() {
            @Override public void run() {
                showPowerConfirm(
                        context.getString(R.string.context_shutdown_title),
                        context.getString(R.string.context_shutdown_message),
                        context.getString(R.string.context_shutdown_confirm),
                        new Runnable() {
                            @Override public void run() { PowerActions.shutdown(context); }
                        });
            }
        });

        // 2026-07-15 — Power tier HOME switch rows when switch script is present (unit-test gate).
        if (LauncherSwitch.isSwitchScriptAvailable()) {
            String homeTarget = LauncherPreference.getHomeTarget(context);
            String marker = context.getString(R.string.settings_home_launcher_current_marker);
            appendPowerLauncherRow(labels, headers, powerRowActions, homeTarget, marker,
                    LauncherDefault.TARGET_SOLAR,
                    R.string.settings_home_launcher_restart_solar,
                    R.string.settings_home_launcher_switch_solar);
            // 2026-07-19 — Power tier: Solar (+ Stock below) only — no Rockbox/JJ switch chips.
            // Was: appendPowerLauncherRow Rockbox + JJ. Reversal: restore those two appends.
        }

        menu.setScrollableDetailHeader(false);
        menu.replaceListContent(context.getString(R.string.context_quick_power),
                labels.toArray(new String[labels.size()]), null, null,
                toPrimitive(headers.toArray(new Boolean[headers.size()])),
                createListListener(), resetFocus);
        powerTierVisible = true;
    }

    /**
     * 2026-07-08 — Add third-party HOME apps to Power tier (skip Solar/Rockbox/JJ + provision).
     * Layman: any other home screen app you installed also shows in the Power menu.
     * Technical: PM CATEGORY_HOME via discoverExtraHomeLaunchers; filter com.android.provision.
     */
    private void appendExtraHomeLauncherPowerRows(ArrayList<String> labels, ArrayList<Boolean> headers,
            ArrayList<Runnable> powerRowActions, final String currentTarget, String marker) {
        java.util.List<android.content.pm.ResolveInfo> extras =
                LauncherHomeApply.discoverExtraHomeLaunchers(context);
        if (extras == null || extras.isEmpty()) return;
        for (android.content.pm.ResolveInfo info : extras) {
            if (info == null || info.activityInfo == null) continue;
            final String pkg = info.activityInfo.packageName;
            // Setup wizard is not a user launcher — hide from Power / picker.
            if ("com.android.provision".equals(pkg)) continue;
            // Always list extras (including current custom HOME with marker) — do not skip current.
            final android.content.ComponentName cn = new android.content.ComponentName(
                    pkg, info.activityInfo.name);
            final String flat = LauncherDiscovery.flattenComponent(cn);
            String name = LauncherHomeApply.labelForPackage(context, pkg);
            String savedFlat = LauncherPreference.readHomeComponentProperty();
            final boolean current = LauncherDefault.TARGET_CUSTOM.equals(currentTarget)
                    && flat != null && flat.equals(savedFlat);
            String label = current
                    ? (name + marker)
                    : context.getString(R.string.launcher_crash_recovery_use_custom, name);
            headers.add(Boolean.FALSE);
            labels.add(label);
            powerRowActions.add(new Runnable() {
                @Override public void run() {
                    dismissOverlayForLauncherSelection("overlay_power_custom", flat);
                    LauncherHomeApply.applyCustomHome(context, cn, "overlay_power");
                }
            });
        }
    }

    /** Quick bar over dialog/app menu — sub-tiers must not cancel pending hook session. */
    private ThemedContextMenu.QuickBarListener createModalQuickBarListener() {
        return new ThemedContextMenu.QuickBarListener() {
            @Override
            public void onQuickSelected(int index) {
                switch (index) {
                    case QUICK_HOME:
                        OverlayForegroundGuard.markUserRequestedSolarNavigation();
                        dismissListener.onDismissOverlay();
                        launchPreferredHome();
                        break;
                    case QUICK_WIFI:
                        showOverlayWifiTier();
                        break;
                    case QUICK_BT:
                        showOverlayBluetoothTier();
                        break;
                    case QUICK_NOW_PLAYING:
                        if (LauncherSwitch.isRockboxForeground(context)) break;
                        if (!SolarUiState.isPlaybackActive()) break;
                        OverlayForegroundGuard.markUserRequestedSolarNavigation();
                        dismissListener.onDismissOverlay();
                        launchSolarNowPlaying();
                        break;
                    case QUICK_BRIGHTNESS:
                        showOverlayMediaSliders(QUICK_BRIGHTNESS);
                        break;
                    case QUICK_VOLUME:
                        showOverlayMediaSliders(QUICK_VOLUME);
                        break;
                    case QUICK_SLEEP:
                        // 2026-07-15 — Right-end Zzz sleep (was Lock at index 1).
                        dismissListener.onDismissOverlay();
                        PowerActions.screenSleep(context);
                        break;
                    case QUICK_POWER:
                        // 2026-07-08 — APP_MENU / dialog quick bar used to no-op Power; launchers never appeared.
                        // Was: break. Now: same power list as global quick (Restart / Shutdown / HOME rows).
                        // Reversal: restore empty break — Power chip does nothing over Solar Home menus.
                        refreshPowerTier(true);
                        menu.setQuickReturnIndex(QUICK_POWER);
                        menu.focusSubmenuList();
                        modalQuickSubTier = true;
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onFocusBackChip() {}

            @Override
            public void onBackActivated() {
                if (mediaSlidersActive || simpleNetworkTier || powerTierVisible) {
                    leaveSubTier();
                } else {
                    sendOverlayModalCancelResult();
                    dismissListener.onDismissOverlay();
                }
            }
        };
    }

    private ThemedContextMenu.QuickBarListener createQuickBarListener() {
        return new ThemedContextMenu.QuickBarListener() {
            @Override
            public void onQuickSelected(int index) {
                switch (index) {
                    case QUICK_HOME:
                        OverlayForegroundGuard.markUserRequestedSolarNavigation();
                        dismissListener.onDismissOverlay();
                        launchPreferredHome();
                        break;
                    case QUICK_WIFI:
                        showOverlayWifiTier();
                        break;
                    case QUICK_BT:
                        showOverlayBluetoothTier();
                        break;
                    case QUICK_NOW_PLAYING:
                        if (LauncherSwitch.isRockboxForeground(context)) break;
                        if (!SolarUiState.isPlaybackActive()) break;
                        OverlayForegroundGuard.markUserRequestedSolarNavigation();
                        dismissListener.onDismissOverlay();
                        launchSolarNowPlaying();
                        break;
                    case QUICK_BRIGHTNESS:
                        showOverlayMediaSliders(QUICK_BRIGHTNESS);
                        break;
                    case QUICK_VOLUME:
                        showOverlayMediaSliders(QUICK_VOLUME);
                        break;
                    case QUICK_SLEEP:
                        // 2026-07-15 — Right-end Zzz sleep (was Lock at index 1).
                        dismissListener.onDismissOverlay();
                        PowerActions.screenSleep(context);
                        break;
                    case QUICK_POWER:
                        refreshPowerTier(true);
                        menu.setQuickReturnIndex(QUICK_POWER);
                        menu.focusSubmenuList();
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onFocusBackChip() {}

            @Override
            public void onBackActivated() {
                if (powerTierVisible || mediaSlidersActive || simpleNetworkTier || queueTierVisible) {
                    if (mediaSlidersActive || simpleNetworkTier || queueTierVisible) {
                        leaveSubTier();
                    } else {
                        dismissListener.onDismissOverlay();
                    }
                } else {
                    sendOverlayModalCancelResult();
                    dismissListener.onDismissOverlay();
                }
            }
        };
    }

    /** Opens the user's saved HOME app (Solar, Rockbox, or JJ). */
    private void launchPreferredHome() {
        LauncherPreference.launchHome(context);
    }

    /** @deprecated use {@link #launchPreferredHome()} */
    private void launchSolarHome() {
        launchPreferredHome();
    }

    /** Explicit allowlist — jump to Now Playing over a third-party app. */
    private void launchSolarNowPlaying() {
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launch.putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true);
        context.startActivity(launch);
    }

    /** Test hook — Now Playing chip replaces queue over third-party; hidden over Rockbox. */
    static boolean isNowPlayingChipVisible(boolean rockboxForeground, boolean playbackActive) {
        return !rockboxForeground && playbackActive;
    }

    /** Test hook — Rockbox switch row only when Solar owns the foreground task. */
    static boolean isRockboxSwitchRowVisible(boolean solarForeground, boolean switchScriptAvailable,
            boolean rockboxAvailable) {
        return solarForeground && switchScriptAvailable && rockboxAvailable;
    }

    /** Test hook — Back to Solar when a third-party app or Rockbox is foreground. */
    static boolean isBackToSolarRowVisible(boolean solarForeground, boolean switchScriptAvailable,
            boolean rockboxAvailable) {
        return !solarForeground && switchScriptAvailable && rockboxAvailable;
    }

    private ThemedContextMenu.QuickItem[] buildQuickBar() {
        int volMax = MediaVolumeControl.getDisplayMaxVolume(context);
        int volCur = MediaVolumeControl.getMediaDisplayVolume(context);
        int volIcon = ThemedContextMenu.volumeIconResForLevel(volCur, volMax);
        int brightIcon = ThemedContextMenu.brightnessIconResForLevel(
                SystemBrightnessControl.read(context), SystemBrightnessControl.MAX);
        // 2026-07-15 — Power chip: hasRootAccess (ROM expects su), not canRunRootShell (A5 RootShell skips).
        // Was: canRunRootShell() hid Power on A5. Reversal: restore canRunRootShell gate.
        boolean rooted = DeviceFeatures.hasRootAccess();
        // 2026-07-11 — A5 volume/sleep via overlay (no dedicated hard buttons off NP).
        boolean showVolSleep = DeviceFeatures.showsOverlayVolumeLockChips();
        boolean rockboxFg = OverlayForegroundGuard.isRockboxSnapshottedForeground();
        if (!rockboxFg) {
            rockboxFg = LauncherSwitch.isRockboxForeground(context);
        }
        boolean showNowPlaying = isNowPlayingChipVisible(rockboxFg, SolarUiState.isPlaybackActive());
        // Stem/Mix jam: hide Wi‑Fi/BT mid-jam (same gate as MainActivity). 2026-07-21
        // Was: always true. Reversal: showConn = true.
        boolean showConn = StemControls.jamQuickBarShowsConnectivity(
                StemOrMixSession.isActive());
        // 2026-07-15 — Order ends Volume then Sleep/Zzz (was Lock near left).
        return new ThemedContextMenu.QuickItem[] {
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_home,
                    context.getString(R.string.context_go_to_home), true),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_wifi,
                    context.getString(R.string.context_tier_wifi), showConn),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_bluetooth,
                    context.getString(R.string.home_menu_bluetooth), showConn),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_power,
                    context.getString(R.string.context_quick_power), rooted),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_play,
                    context.getString(R.string.context_go_to_now_playing), showNowPlaying),
            new ThemedContextMenu.QuickItem(null, brightIcon,
                    context.getString(R.string.context_quick_brightness), true),
            new ThemedContextMenu.QuickItem(null, volIcon,
                    context.getString(R.string.context_quick_volume), showVolSleep),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_zzz,
                    context.getString(R.string.context_action_sleep), showVolSleep)
        };
    }

    /** Power tier launcher row — Restart {current} or Use {other} with checkmark. */
    private void appendPowerLauncherRow(ArrayList<String> labels, ArrayList<Boolean> headers,
            ArrayList<Runnable> powerRowActions, final String currentTarget, String marker,
            final String rowTarget, int restartRes, int useRes) {
        if (LauncherDefault.TARGET_ROCKBOX.equals(rowTarget)
                && !LauncherSwitch.isRockboxAvailable(context)) {
            return;
        }
        if (LauncherDefault.TARGET_JJ.equals(rowTarget)
                && !JjLauncherAvailability.isOfferVisible(context)) {
            return;
        }
        // 2026-07-08 — Stock Innioasis HOME only when installed.
        if (LauncherDefault.TARGET_STOCK.equals(rowTarget)
                && !LauncherSwitch.isStockOfferVisible(context)) {
            return;
        }
        final boolean current = rowTarget.equals(currentTarget);
        String label = context.getString(current ? restartRes : useRes);
        if (current) label = label + marker;
        headers.add(Boolean.FALSE);
        labels.add(label);
        powerRowActions.add(new Runnable() {
            @Override public void run() {
                dismissOverlayForLauncherSelection("overlay_power", rowTarget);
                if (current) {
                    LauncherHelperClient.restartActiveLauncher(context, "overlay_power");
                } else if (LauncherDefault.TARGET_ROCKBOX.equals(rowTarget)) {
                    LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_ROCKBOX);
                    PowerActions.switchToRockbox(context);
                } else if (LauncherDefault.TARGET_JJ.equals(rowTarget)) {
                    LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_JJ);
                    PowerActions.switchToJj(context);
                } else if (LauncherDefault.TARGET_STOCK.equals(rowTarget)) {
                    // 2026-07-08 — Factory home via Power tier (was missing from Switch path).
                    PowerActions.switchToStock(context);
                } else {
                    OverlayForegroundGuard.markUserRequestedSolarNavigation();
                    LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_SOLAR);
                    PowerActions.switchToSolar(context);
                }
            }
        });
    }

    /**
     * 2026-07-06 — Tear down global modal before HOME switch/restart side effects.
     * Layman: close the quick menu as soon as user picks a launcher row.
     * Technical: dismiss listener + companion DISMISS broadcast; clears tier flags first.
     */
    private void dismissOverlayForLauncherSelection(String source, String target) {
        powerTierVisible = false;
        launcherPickerVisible = false;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("source", source);
            d.put("target", target != null ? target : "");
            DebugE93bdbLog.log("OverlayModalHost.dismissOverlayForLauncherSelection",
                    "dismiss for launcher pick", "H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (dismissListener != null) {
            dismissListener.onDismissOverlay();
        }
        try {
            android.content.Intent dismiss = new android.content.Intent(
                    OverlayTriggers.ACTION_DISMISS_OVERLAY);
            dismiss.setComponent(new android.content.ComponentName(context,
                    SolarOverlayService.class));
            context.startService(dismiss);
        } catch (Exception ignored) {}
        try {
            android.content.Intent companionDismiss = new android.content.Intent(
                    OverlayTriggers.ACTION_DISMISS_OVERLAY);
            companionDismiss.setComponent(new android.content.ComponentName(
                    "com.solar.launcher.globalcontext",
                    "com.solar.launcher.globalcontext.GlobalContextOverlayService"));
            context.startService(companionDismiss);
        } catch (Exception ignored) {}
    }

    /** Wheel-friendly HOME picker — dismiss modal first, then apply HOME + switch script. */
    private void applyLauncherPickerSelection(String target) {
        dismissOverlayForLauncherSelection("overlay_picker_switch", target);
        LauncherPreference.applyHomeTarget(context, target);
        if (LauncherDefault.TARGET_SOLAR.equals(target)) {
            OverlayForegroundGuard.markUserRequestedSolarNavigation();
        }
        if (LauncherSwitch.isSwitchScriptAvailable()) {
            if (LauncherDefault.TARGET_ROCKBOX.equals(target)) {
                PowerActions.switchToRockbox(context);
            } else if (LauncherDefault.TARGET_JJ.equals(target)) {
                PowerActions.switchToJj(context);
            } else if (LauncherDefault.TARGET_STOCK.equals(target)) {
                PowerActions.switchToStock(context);
            } else {
                PowerActions.switchToSolar(context);
            }
            return;
        }
        LauncherPreference.launchHomeForTarget(context, target);
    }

    private void clearModalSessions() {
        appMenuSessionId = null;
        appMenuCallerPackage = null;
        dialogSessionId = null;
        dialogCallerPackage = null;
        dialogTierVisible = false;
        appMenuTierVisible = false;
        modalQuickSubTier = false;
        dialogTierRestore = null;
        appMenuTierRestore = null;
        usbStorageTierRestore = null;
        usbStoragePromptVisible = false;
        usbStorageLockVisible = false;
        bluetoothPairingTierRestore = null;
        bluetoothPairingPromptVisible = false;
        btPairingAddress = null;
        if (wifiPasswordKeyboard != null) wifiPasswordKeyboard.dismiss();
        if (btPinKeyboard != null) btPinKeyboard.dismiss();
    }

    /** Back/dismiss without a row pick — route to app menu, native dialog, USB, or BT pairing tier. */
    private void sendOverlayModalCancelResult() {
        // 2026-07-08 — USB lock ignores Back cancel (Turn Off / unplug only).
        if (usbStorageLockVisible) {
            return;
        }
        if (usbStoragePromptVisible) {
            handleUsbStorageOverlayDismiss();
            return;
        }
        if (bluetoothPairingPromptVisible) {
            BluetoothPairingCoordinator.cancelPairing(context, btPairingAddress);
            bluetoothPairingPromptVisible = false;
            dismissListener.onDismissOverlay();
            return;
        }
        if (dialogSessionId != null) {
            finishNativeDialogSelection(-1);
            return;
        } else {
            sendAppMenuResult(-1);
        }
    }

    private void clearAppMenuSession() {
        if (appMenuSessionId != null) {
            OverlayMenuSessionRegistry.remove(appMenuSessionId);
        }
        clearModalSessions();
    }

    private void sendAppMenuResult(int index) {
        if (appMenuSessionId == null) return;
        // 2026-07-08 — Solar Home keeps session while drilling overlay tiers.
        // Was: clearModalSessions() always wiped flags before MainActivity refresh.
        // Now: solar_home_* keeps appMenuTierVisible + restore runnable.
        // Reversal: always clearModalSessions after broadcast.
        final boolean solarHomeSession = appMenuSessionId.startsWith("solar_home_");
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("index", index);
            d.put("caller", appMenuCallerPackage);
            d.put("solarHomeSession", solarHomeSession);
            DebugAgentLog.log(context, "OverlayModalHost.sendAppMenuResult", "broadcast result", "H3", d);
        } catch (Exception ignored) {}
        // #endregion
        Intent result = new Intent(OverlayTriggers.ACTION_APP_MENU_RESULT);
        result.putExtra(OverlayTriggers.EXTRA_MENU_SESSION_ID, appMenuSessionId);
        result.putExtra(OverlayTriggers.EXTRA_SELECTED_INDEX, index);
        if (appMenuCallerPackage != null && appMenuCallerPackage.length() > 0) {
            result.setPackage(appMenuCallerPackage);
        }
        context.sendBroadcast(result);
        if (solarHomeSession) {
            OverlayMenuSessionRegistry.remove(appMenuSessionId);
            return;
        }
        clearModalSessions();
    }

    /** Deliver AlertDialog button index to the hooked app (index 1 = first button). */
    private void sendDialogResult(int index) {
        if (dialogSessionId == null) return;
        Intent result = new Intent(OverlayTriggers.ACTION_DIALOG_RESULT);
        result.putExtra(OverlayTriggers.EXTRA_MENU_SESSION_ID, dialogSessionId);
        result.putExtra(OverlayTriggers.EXTRA_SELECTED_INDEX, index);
        if (dialogCallerPackage != null && dialogCallerPackage.length() > 0) {
            result.setPackage(dialogCallerPackage);
        }
        context.sendBroadcast(result);
        clearModalSessions();
    }

    private static boolean[] toPrimitive(Boolean[] boxed) {
        if (boxed == null) return null;
        boolean[] out = new boolean[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            out[i] = boxed[i] != null && boxed[i];
        }
        return out;
    }

    /** Dedicated play/pause key (85) — not wheel 126/127 which navigate lists. */
    private static boolean isDedicatedPlayPauseKey(int keyCode) {
        return Y1InputKeys.isPlayPauseKey(keyCode);
    }

    /** Count visible quick-bar chips for debug traces. */
    private static int countVisibleQuickItems(ThemedContextMenu.QuickItem[] items) {
        if (items == null) return 0;
        int n = 0;
        for (ThemedContextMenu.QuickItem q : items) {
            if (q != null && q.visible) n++;
        }
        return n;
    }

    /** True while USB enable or lock tier is painted on this shell. */
    public boolean isUsbStoragePromptVisible() {
        return usbStoragePromptVisible || usbStorageLockVisible;
    }
}
