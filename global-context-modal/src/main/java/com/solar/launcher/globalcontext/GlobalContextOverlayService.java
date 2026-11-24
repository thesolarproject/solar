package com.solar.launcher.globalcontext;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.home.policy.HomeTargetPolicy;
import com.solar.input.policy.StaleOverlayGate;
import com.solar.launcher.overlay.ChipHostActions;
import com.solar.launcher.overlay.ChipOverlayHost;
import com.solar.launcher.overlay.OverlayModalTransition;
import com.solar.launcher.overlay.OverlayShellRouter;
import com.solar.launcher.overlay.OverlayThemeProvider;
import com.solar.launcher.overlay.OverlayTierNames;

import java.io.File;
import java.util.ArrayList;

/**
 * 2026-07-08 — THE one system overlay shell: power / app-menu / ANR / USB / toast tiers.
 * Layman: single dim-free system menu window over any app — wheel steers it.
 * 2026-07-10 — Interactive shell is FOCUSABLE and dispatches keys itself (does not require
 * Xposed key-forward to work). Xposed + CompanionOverlayKeyGate remain a second path.
 * Was: TYPE_SYSTEM_ERROR NOT_FOCUSABLE → keys only via Xposed IPC (often silent fail).
 * 2026-07-08 — POWER/APP_MENU paint ChipOverlayHost chrome; USB/ANR stay TextView dialogs.
 * Reversal: restore NOT_FOCUSABLE-only + Xposed-only keys if focus steals from media apps.
 */
public final class GlobalContextOverlayService extends Service {

    private static final String TAG = "GlobalContextOverlay";
    /** Paint-or-teardown — never leave active=1 without ui. */
    private static final long PAINT_WATCHDOG_MS = 2500L;
    private static final long TOAST_DEFAULT_MS = 2500L;
    private static final String SOLAR_PKG = "com.solar.launcher";
    private static final String EXTRA_OPEN_NOW_PLAYING = "solar.extra.open_now_playing";

    private WindowManager windowManager;
    private KeyCapturingRoot overlayRoot;
    private LinearLayout panel;
    private TextView titleView;
    private TextView detailView;
    /** Scroll host for USB lock body — taller than ANR tiers (two menu rows). */
    private ScrollView detailScroll;
    private LinearLayout rowsHost;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChipOverlayHost chipHost;
    private boolean chipMode;

    private String[] rowLabels = new String[0];
    private boolean[] rowVisible;
    private int focusedIndex;
    private String activeTier = OverlayTierNames.TIER_NONE;
    private String sessionId;
    private String callerPackage;
    private String dialogMessage;
    private boolean openInFlight;
    private boolean interactive;
    /**
     * 2026-07-08 — Parallel HOME targets for launcher_picker rows (same length as rowLabels).
     * Layman: remembers which home app each row opens when you press OK.
     * Technical: HomeTargetPolicy tokens; null/empty for Cancel.
     * Reversal: clear field; stub picker had no real targets.
     */
    private String[] launcherPickerTargets = new String[0];

    private final ChipHostActions chipActions = new ChipHostActions() {
        @Override
        public void launchHome() {
            // Prefer helper APPLY_HOME with saved preference via Solar if alive; else Solar MAIN.
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setPackage(SOLAR_PKG);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
            } catch (Exception e) {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(SOLAR_PKG);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void screenSleep() {
            // Match PowerActions: KEYCODE_POWER via root input.
            runRootShell("input keyevent 26");
        }

        @Override
        public void restartDevice() {
            runRootShell("reboot");
        }

        @Override
        public void shutdownDevice() {
            runRootShell("reboot -p");
        }

        @Override
        public void openNowPlaying() {
            try {
                Intent launch = new Intent();
                launch.setClassName(SOLAR_PKG, SOLAR_PKG + ".MainActivity");
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launch.putExtra(EXTRA_OPEN_NOW_PLAYING, true);
                startActivity(launch);
            } catch (Exception ignored) {}
        }

        @Override
        public void dispatchPowerRow(int index) {
            SolarOverlayStateClient.get().dispatchActionBound(
                    GlobalContextOverlayService.this, "power", index);
        }

        @Override
        public boolean onAppMenuSelected(String sid, String caller, int index,
                boolean opensSubmenu) {
            boolean solarHome = sid != null && sid.startsWith("solar_home_");
            if (index >= 0 && sid != null) {
                SolarOverlayStateClient client = SolarOverlayStateClient.get();
                if (client.dispatchActionBound(GlobalContextOverlayService.this, sid, index)) {
                    return opensSubmenu || solarHome;
                }
            }
            if (sid != null) {
                Intent result = new Intent(CompanionOverlayActions.ACTION_APP_MENU_RESULT);
                result.putExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID, sid);
                result.putExtra(CompanionOverlayActions.EXTRA_SELECTED_INDEX, index);
                if (caller != null && caller.length() > 0) {
                    result.setPackage(caller);
                }
                try {
                    sendBroadcast(result);
                } catch (Exception ignored) {}
            }
            return opensSubmenu || solarHome;
        }

        @Override
        public void dispatchDialogSelection(String tier, int index) {
            focusedIndex = index;
            activateFocusedRow();
        }

        @Override
        public void dismissOverlay(boolean stopService) {
            tearDownOverlay(stopService);
        }
    };

    private final Runnable paintWatchdog = new Runnable() {
        @Override
        public void run() {
            // 2026-07-10 — In-memory shell truth first.
            // Was: require sys.solar.overlay.ui=1 — setprop fails on many Y2 installs →
            // watchdog tore down a live ChipOverlayHost after 2.5s (dead menu / no input).
            // Reversal: restore prop-only check if false positive stuck shells return.
            if (overlayRoot != null && interactive) {
                if (chipMode && chipHost != null && chipHost.isShowing()) {
                    CompanionOverlayKeyGate.refreshLiveGate();
                    return;
                }
                if (!chipMode && panel != null) {
                    CompanionOverlayKeyGate.refreshLiveGate();
                    return;
                }
            }
            if (overlayRoot != null && interactive
                    && "1".equals(SysPropHelper.get(CompanionOverlayKeyGate.UI_PROPERTY, "0"))) {
                return;
            }
            Log.w(TAG, "paint watchdog — disarm without live shell");
            tearDownOverlay(true);
        }
    };

    /**
     * Re-publish capture props while shell is live.
     * 2026-07-11 — Y2 Power-hold: first ticks often arrive before async su setprop sticks.
     * Burst refresh at 80/250/800ms then every 2s so wheel works immediately.
     */
    private int gateRefreshBurst;
    private final Runnable gateRefresh = new Runnable() {
        @Override
        public void run() {
            if (overlayRoot == null || !interactive) return;
            CompanionOverlayKeyGate.refreshLiveGate();
            requestOverlayFocus();
            long delay = 2000L;
            if (gateRefreshBurst == 0) delay = 80L;
            else if (gateRefreshBurst == 1) delay = 250L;
            else if (gateRefreshBurst == 2) delay = 800L;
            if (gateRefreshBurst < 10) gateRefreshBurst++;
            mainHandler.postDelayed(this, delay);
        }
    };

    private final CompanionOverlayKeyGate.Handler keyHandler =
            new CompanionOverlayKeyGate.Handler() {
                @Override
                public boolean onKeyDown(int keyCode) {
                    return handleKeyDown(keyCode);
                }

                @Override
                public boolean onKeyUp(int keyCode) {
                    return handleKeyUp(keyCode);
                }
            };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 2026-07-08 — Fresh process has no WM view; heal crash-stale shell_visible=1.
     * Layman: new process start means the old painted menu is gone.
     * Technical: clear shell_visible before any addView so stuck BACK won’t false-fire.
     * Reversal: remove onCreate; rely on tearDown/boot setprop only.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        CompanionOverlayKeyGate.setShellVisible(false);
        // 2026-07-08 — Feed TCM/chip paint via ThemeReader colours (no ThemeManager in companion).
        refreshCompanionTheme();
        // #region agent log
        AgentDebugLog.log("H-B", "GlobalContextOverlayService.onCreate",
                "overlay_process_create", "{}");
        // #endregion
    }

    /**
     * 2026-07-10 — Reload Solar theme sidecars and rebind OverlayTheme before every paint.
     * Layman: global menu matches the user’s current Solar theme (panel, selection strip, text).
     * Was: ThemeReader only on cold create — missed mid-session theme changes + first-open race.
     */
    private void refreshCompanionTheme() {
        ThemeReader.refresh(getApplicationContext());
        OverlayThemeProvider.install(new CompanionOverlayTheme());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (CompanionOverlayTriggers.ACTION_DISMISS_OVERLAY.equals(action)) {
            tearDownOverlay(true);
            notifySolarUsbLocked(false, false);
            return START_NOT_STICKY;
        }
        if (CompanionOverlayTriggers.ACTION_OVERLAY_KEEPALIVE.equals(action)) {
            ThemeReader.refresh(getApplicationContext());
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_OVERLAY_KEY.equals(action)) {
            deliverKeyIntent(intent);
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_TOAST.equals(action)) {
            showToastTier(intent);
            return START_NOT_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_VOLUME.equals(action)) {
            showVolumeTier();
            return START_STICKY;
        }
        if (OverlayTierNames.TIER_USB_LOCK.equals(activeTier)
                && !CompanionOverlayActions.ACTION_SHOW_OVERLAY_USB_STORAGE.equals(action)
                && !CompanionOverlayActions.ACTION_SHOW_OVERLAY_USB_STORAGE_LOCK.equals(action)) {
            Log.i(TAG, "ignore " + action + " — usb_lock active");
            return START_STICKY;
        }
        StaleOverlayGate.clearIfNeeded();
        if (CompanionOverlayTriggers.ACTION_SHOW_OVERLAY_POWER.equals(action)) {
            // 2026-07-14 — Sole shell is Solar ThemedContextMenu; redirect chip process away.
            if (CompanionOverlayRouter.shouldDelegatePaintToSolar(this)) {
                // Drop any chip chrome already up, then hand Solar the paint.
                if (overlayRoot != null) {
                    tearDownOverlay(false);
                }
                CompanionOverlayRouter.startSolarOverlayPower(this);
                stopSelf();
                return START_NOT_STICKY;
            }
            // Chip opt-in path — never leave Solar themed shell underneath.
            OverlayShellRouter.dismissPeerOverlayShell(this);
            AgentDebugLog.log("H-C", "GlobalContextOverlayService.onStartCommand",
                    "SHOW_POWER", "{\"pid\":" + android.os.Process.myPid() + "}");
            // Optional solar_home_* extras → Home options on the same Power-hold shell.
            showOrRefreshPower(intent);
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_APP_MENU.equals(action)) {
            // Solar Home: route to Power-hold shell (one modal). Third-party keeps APP_MENU.
            String sid = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID);
            if (sid != null && sid.startsWith("solar_home_")) {
                showOrRefreshPower(intent);
                return START_STICKY;
            }
            showAppMenuTier(intent);
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_NATIVE_DIALOG.equals(action)) {
            showNativeDialogTier(intent);
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_USB_STORAGE.equals(action)
                || CompanionOverlayActions.ACTION_SHOW_OVERLAY_USB_STORAGE_LOCK.equals(action)) {
            // 2026-07-10 — Never paint USB here; hand off to Solar only (kills dual-handler race).
            showUsbTier(intent);
            return START_NOT_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_BT_PAIRING.equals(action)) {
            showBtPairingTier(intent);
            return START_STICKY;
        }
        if (CompanionOverlayActions.ACTION_SHOW_OVERLAY_LAUNCHER_PICKER.equals(action)
                || CompanionOverlayActions.ACTION_SHOW_OVERLAY_LAUNCHER_RECOVERY.equals(action)) {
            showLauncherPickerTier(intent);
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        tearDownOverlay(true);
        super.onDestroy();
    }

    /** Route ACTION_OVERLAY_KEY into the live gate handler. */
    private void deliverKeyIntent(Intent intent) {
        // 2026-07-14 — Same dual-extra accept as CompanionOverlayKeyReceiver.
        int keyCode = intent.getIntExtra(CompanionOverlayActions.EXTRA_KEY_CODE, 0);
        if (keyCode == 0) {
            keyCode = intent.getIntExtra(CompanionOverlayTriggers.EXTRA_KEY_CODE, 0);
        }
        if (keyCode == 0) {
            // #region agent log
            DebugSession083511.log("H1", "GlobalContextOverlayService.deliverKeyIntent",
                    "drop_keyCode0", "{}");
            // #endregion
            return;
        }
        final int code = keyCode;
        final int action = intent.getIntExtra(CompanionOverlayActions.EXTRA_KEY_ACTION,
                intent.getIntExtra("key_action", KeyEvent.ACTION_DOWN));
        // #region agent log
        DebugSession083511.log("H1", "GlobalContextOverlayService.deliverKeyIntent",
                "queued", "{\"keyCode\":" + code + ",\"action\":" + action + "}");
        // #endregion
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (action == KeyEvent.ACTION_UP) {
                    CompanionOverlayKeyGate.deliverUp(code);
                } else {
                    CompanionOverlayKeyGate.deliver(code);
                }
            }
        });
    }

    /**
     * 2026-07-10 — Paint power menu (ChipOverlayHost); optional Solar Home rows via intent extras.
     * Layman: same shell as Y2 Power-hold; Home may fill the list with context options.
     * Was: power-only snapshot. Now: extras menu_titles + solar_home_* → Home-on-power shell.
     * Reversal: ignore extras; always pure power snapshot.
     */
    private void showOrRefreshPower() {
        showOrRefreshPower(null);
    }

    private void showOrRefreshPower(Intent homeExtras) {
        long tOpen = android.os.SystemClock.uptimeMillis();
        if (openInFlight) {
            AgentDebugLog.log("H-E", "GlobalContextOverlayService.showOrRefreshPower",
                    "skipped_openInFlight", "{}");
            return;
        }
        openInFlight = true;
        try {
            refreshCompanionTheme();
            CompanionOverlayKeyGate.setOverlayOpening(true);

            // Solar Home: use Power-hold shell with Home option rows (not a second APP_MENU look).
            if (homeExtras != null) {
                String[] homeLabels = homeExtras.getStringArrayExtra(
                        CompanionOverlayActions.EXTRA_MENU_TITLES);
                String sid = homeExtras.getStringExtra(
                        CompanionOverlayActions.EXTRA_MENU_SESSION_ID);
                if (homeLabels != null && homeLabels.length > 0
                        && sid != null && sid.startsWith("solar_home_")) {
                    String title = homeExtras.getStringExtra(
                            CompanionOverlayActions.EXTRA_MENU_TITLE);
                    String caller = homeExtras.getStringExtra(
                            CompanionOverlayActions.EXTRA_MENU_CALLER_PACKAGE);
                    boolean[] submenu = homeExtras.getBooleanArrayExtra(
                            CompanionOverlayActions.EXTRA_MENU_HAS_SUBMENU);
                    paintChipHomeOnPower(title, homeLabels, submenu, sid, caller);
                    AgentDebugLog.log("H-A", "GlobalContextOverlayService.showOrRefreshPower",
                            "home_on_power", "{\"labelCount\":" + homeLabels.length + "}");
                    return;
                }
            }

            long tSnap = android.os.SystemClock.uptimeMillis();
            SolarOverlayStateClient client = SolarOverlayStateClient.get();
            Bundle snap = client.tryHotPowerSnapshot(this);
            long snapMs = android.os.SystemClock.uptimeMillis() - tSnap;
            String title = "Quick menu";
            String[] labels = null;
            boolean[] states = null;
            boolean usedFallback = false;
            boolean hadSnap = false;
            if (snap != null) {
                title = snap.getString("title", "Quick menu");
                labels = snap.getStringArray("labels");
                states = snap.getBooleanArray("states");
                hadSnap = labels != null && labels.length > 0;
            }
            if (!hadSnap) {
                labels = null;
                states = null;
                usedFallback = true;
            }
            long tPaint = android.os.SystemClock.uptimeMillis();
            paintChipPower(title, labels, states);
            AgentDebugLog.log("H-A", "GlobalContextOverlayService.showOrRefreshPower",
                    "painted", "{\"snapMs\":" + snapMs
                            + ",\"paintMs\":"
                            + (android.os.SystemClock.uptimeMillis() - tPaint)
                            + ",\"totalMs\":"
                            + (android.os.SystemClock.uptimeMillis() - tOpen)
                            + ",\"usedFallback\":" + usedFallback
                            + ",\"labelCount\":" + (labels != null ? labels.length : 0)
                            + ",\"hadSnap\":" + hadSnap
                            + ",\"asyncPending\":" + usedFallback
                            + ",\"chip\":true}");
            if (usedFallback) {
                schedulePowerSnapshotMorph(client, tOpen);
            }
        } finally {
            openInFlight = false;
        }
    }

    private void schedulePowerSnapshotMorph(SolarOverlayStateClient client, final long tOpen) {
        if (client == null) return;
        client.fetchPowerSnapshotAsync(this, new SolarOverlayStateClient.PowerSnapshotCallback() {
            @Override
            public void onPowerSnapshot(final Bundle snap, final long elapsedMs) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyAsyncPowerSnapshot(snap, elapsedMs, tOpen);
                    }
                });
            }
        });
    }

    private void applyAsyncPowerSnapshot(Bundle snap, long binderMs, long tOpen) {
        if (overlayRoot == null || !OverlayTierNames.TIER_POWER.equals(activeTier)) {
            AgentDebugLog.log("H-A", "GlobalContextOverlayService.applyAsyncPowerSnapshot",
                    "skipped_tier_gone", "{\"binderMs\":" + binderMs + "}");
            return;
        }
        if (snap == null) {
            AgentDebugLog.log("H-A", "GlobalContextOverlayService.applyAsyncPowerSnapshot",
                    "null_snap", "{\"binderMs\":" + binderMs + "}");
            return;
        }
        String title = snap.getString("title", "Quick menu");
        String[] labels = snap.getStringArray("labels");
        boolean[] states = snap.getBooleanArray("states");
        if (labels == null || labels.length == 0) {
            AgentDebugLog.log("H-A", "GlobalContextOverlayService.applyAsyncPowerSnapshot",
                    "empty_labels", "{\"binderMs\":" + binderMs + "}");
            return;
        }
        if (chipMode && chipHost != null) {
            chipHost.morphPowerRows(title, labels, states);
        } else if (titleView != null) {
            titleView.setText(title);
            applyRows(labels, states);
            rebuildRowViews();
            focusRow(0);
        }
        AgentDebugLog.log("H-A", "GlobalContextOverlayService.applyAsyncPowerSnapshot",
                "morphed", "{\"binderMs\":" + binderMs
                        + ",\"totalMs\":"
                        + (android.os.SystemClock.uptimeMillis() - tOpen)
                        + ",\"labelCount\":" + labels.length
                        + ",\"chip\":" + chipMode + "}");
    }

    private void showAppMenuTier(Intent intent) {
        refreshCompanionTheme();
        String[] labels = intent.getStringArrayExtra(CompanionOverlayActions.EXTRA_MENU_TITLES);
        if (labels == null || labels.length == 0) return;
        String title = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_TITLE);
        if (title == null) title = "Options";
        String sid = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID);
        String caller = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_CALLER_PACKAGE);
        boolean[] submenu = intent.getBooleanArrayExtra(
                CompanionOverlayActions.EXTRA_MENU_HAS_SUBMENU);
        // Solar Home / same-session refresh on the Power-hold shell.
        if (overlayRoot != null && sid != null && sid.equals(sessionId)
                && chipMode && chipHost != null
                && (OverlayTierNames.TIER_APP_MENU.equals(activeTier)
                || (OverlayTierNames.TIER_POWER.equals(activeTier)
                && sid.startsWith("solar_home_")))) {
            chipHost.refreshAppMenu(title, labels, submenu);
            return;
        }
        paintChipAppMenu(title, labels, submenu, sid, caller);
    }

    private void showNativeDialogTier(Intent intent) {
        String msg = intent.getStringExtra(CompanionOverlayActions.EXTRA_DIALOG_MESSAGE);
        String[] buttons = intent.getStringArrayExtra(CompanionOverlayActions.EXTRA_DIALOG_BUTTONS);
        String title = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_TITLE);
        if (title == null) title = "Alert";
        String sid = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID);
        String caller = intent.getStringExtra(CompanionOverlayActions.EXTRA_MENU_CALLER_PACKAGE);
        if (buttons == null || buttons.length == 0) buttons = new String[] { "OK" };
        paintTextTier(OverlayTierNames.TIER_NATIVE_ERROR, title, msg, buttons, null, sid, caller);
    }

    /**
     * 2026-07-10 — USB lives on the same ChipContextMenu shell as Wi‑Fi / BT / power dialogs.
     * Layman: one quick modal — Turn on first, then stay up with eject message until Turn Off.
     * Uses {@link ChipOverlayHost#showNativeDialogMode} (not a separate TextView path).
     */
    private void showUsbTier(Intent intent) {
        boolean lock = CompanionOverlayActions.ACTION_SHOW_OVERLAY_USB_STORAGE_LOCK
                .equals(intent != null ? intent.getAction() : null)
                || (intent != null && intent.getBooleanExtra(
                        CompanionOverlayActions.EXTRA_USB_STORAGE_LOCK, false));
        if (!lock && CompanionTierScheduler.shouldDeferUsbSpawn()) {
            CompanionTierScheduler.queuePendingUsbPrompt();
            Log.i(TAG, "USB deferred — native_error tier");
            return;
        }
        if (lock) {
            paintUsbLockOnChipShell();
            notifySolarUsbLocked(true, false);
            return;
        }
        paintUsbPromptOnChipShell();
    }

    /** Enable prompt — same themed chip modal as other system dialogs. */
    private void paintUsbPromptOnChipShell() {
        refreshCompanionTheme();
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        activeTier = OverlayTierNames.TIER_USB;
        sessionId = "usb";
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_USB);
        String title = getString(R.string.usb_connection_title);
        String detail = getString(R.string.usb_mass_storage_detail);
        String[] labels = new String[] {
                getString(R.string.usb_mass_storage_turn_on),
                getString(R.string.usb_mass_storage_dismiss)
        };
        // lockBack=true so selecting Turn on does not dismiss the shell (morphs to lock).
        chipHost.showNativeDialogMode(title, detail, labels, "usb", null,
                OverlayTierNames.TIER_USB, true);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    /** Eject message — same modal, stays until Turn Off or cable unplug. */
    private void paintUsbLockOnChipShell() {
        refreshCompanionTheme();
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        activeTier = OverlayTierNames.TIER_USB_LOCK;
        sessionId = "usb_lock";
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_USB_LOCK);
        String model = CompanionDeviceFeatures.productModelLabel();
        String title = getString(R.string.usb_storage_mode_title);
        String detail = getString(R.string.usb_storage_mode_body, model);
        String[] labels = new String[] { getString(R.string.usb_storage_mode_turn_off) };
        chipHost.showNativeDialogMode(title, detail, labels, "usb_lock", null,
                OverlayTierNames.TIER_USB_LOCK, true);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    private void showBtPairingTier(Intent intent) {
        String name = intent.getStringExtra(CompanionOverlayActions.EXTRA_BT_PAIRING_NAME);
        String title = "Bluetooth pairing";
        String detail = name != null && name.length() > 0
                ? ("Pair with " + name + "?") : "Confirm Bluetooth pairing";
        String[] labels = new String[] { "Pair", "Cancel" };
        paintTextTier(OverlayTierNames.TIER_BT, title, detail, labels, null, "bt", null);
    }

    private void showLauncherPickerTier(Intent intent) {
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<String> targets = new ArrayList<String>();
        labels.add("Solar");
        targets.add(HomeTargetPolicy.TARGET_SOLAR);
        if (isPackageInstalled(HomeTargetPolicy.JJ_PKG)) {
            labels.add("JJ Launcher");
            targets.add(HomeTargetPolicy.TARGET_JJ);
        }
        if (isPackageInstalled(HomeTargetPolicy.INNIOASIS_Y1_PKG)
                || isPackageInstalled(HomeTargetPolicy.INNIOASIS_Y2_PKG)) {
            labels.add("Stock Innioasis");
            targets.add(HomeTargetPolicy.TARGET_STOCK);
        }
        labels.add("Cancel");
        targets.add("");
        launcherPickerTargets = targets.toArray(new String[targets.size()]);
        // Keep TextView path so activateFocusedRow can applyLauncherPickerSelection.
        paintTextTier(OverlayTierNames.TIER_APP_MENU, "Choose HOME", null,
                labels.toArray(new String[labels.size()]), null,
                "launcher_picker", null);
    }

    private boolean isPackageInstalled(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        try {
            return getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyLauncherPickerSelection(String target) {
        if (target == null || target.length() == 0) {
            tearDownOverlay(true);
            return;
        }
        final String mode = HomeTargetPolicy.normalizeTarget(target);
        final boolean helperOk = isPackageInstalled(HomeTargetPolicy.HELPER_PKG);
        if (helperOk) {
            try {
                Intent apply = new Intent(HomeTargetPolicy.ACTION_APPLY_HOME_TARGET);
                apply.setPackage(HomeTargetPolicy.HELPER_PKG);
                apply.putExtra(HomeTargetPolicy.EXTRA_HOME_TARGET, mode);
                apply.putExtra(HomeTargetPolicy.EXTRA_HOME_SOURCE, "companion_picker");
                sendBroadcast(apply);
            } catch (Exception ignored) {}
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String script = resolveLauncherExecScript();
                    if (script == null) return;
                    try {
                        Runtime.getRuntime().exec(new String[] {
                                "su", "-c", "sh " + script + " switch " + mode
                        });
                    } catch (Exception e) {
                        try {
                            Runtime.getRuntime().exec(new String[] {
                                    "sh", "-c", "sh " + script + " switch " + mode
                            });
                        } catch (Exception ignored) {}
                    }
                }
            }, "CompanionHomeSwitch").start();
        }
        tearDownOverlay(true);
    }

    private static String resolveLauncherExecScript() {
        String[] paths = new String[] {
                "/system/etc/solar/solar-launcher-exec.sh",
                "/data/data/com.solar.launcher/solar-launcher-exec.sh",
                "/data/data/solar-launcher-exec.sh"
        };
        for (int i = 0; i < paths.length; i++) {
            if (new File(paths[i]).canRead()) return paths[i];
        }
        return null;
    }

    private void showToastTier(Intent intent) {
        String text = intent.getStringExtra(CompanionOverlayActions.EXTRA_TOAST_TEXT);
        if (text == null || text.length() == 0) return;
        long duration = intent.getLongExtra(CompanionOverlayActions.EXTRA_TOAST_DURATION_MS,
                TOAST_DEFAULT_MS);
        if (interactive && overlayRoot != null) {
            Log.i(TAG, "toast skipped — interactive tier up");
            return;
        }
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = false;
        chipMode = true;
        CompanionOverlayKeyGate.setOverlayActive(false);
        CompanionOverlayKeyGate.setOverlayUiVisible(false);
        chipHost.showToastMode(text, duration);
    }

    private void showVolumeTier() {
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        activeTier = "volume";
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_NONE);
        CompanionOverlayKeyGate.arm(keyHandler);
        chipHost.showVolumeMode();
    }

    /** POWER via ChipOverlayHost (chips + list). */
    private void paintChipPower(String title, String[] labels, boolean[] states) {
        refreshCompanionTheme();
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        activeTier = OverlayTierNames.TIER_POWER;
        sessionId = OverlayMenuSnapshotBuilderKeys.KIND_POWER;
        callerPackage = null;
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_POWER);
        chipHost.showPowerMode(title, labels, states);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    /**
     * 2026-07-10 — Solar Home context on the Power-hold ChipOverlayHost (one system modal).
     * Layman: chips + theme + input identical to Power-hold; list is Home options.
     */
    private void paintChipHomeOnPower(String title, String[] labels, boolean[] submenu,
            String sid, String caller) {
        refreshCompanionTheme();
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        // Stay on POWER tier so gate/Xposed treat this like the working Power-hold shell.
        activeTier = OverlayTierNames.TIER_POWER;
        sessionId = sid;
        callerPackage = caller;
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_POWER);
        chipHost.showHomeOnPowerShell(title, labels, submenu, sid, caller);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    /** APP_MENU via ChipOverlayHost (third-party; Solar Home redirects to power shell). */
    private void paintChipAppMenu(String title, String[] labels, boolean[] submenu,
            String sid, String caller) {
        if (sid != null && sid.startsWith("solar_home_")) {
            paintChipHomeOnPower(title, labels, submenu, sid, caller);
            return;
        }
        refreshCompanionTheme();
        ensureShell();
        clearTextPanel();
        ensureChipHost();
        interactive = true;
        chipMode = true;
        activeTier = OverlayTierNames.TIER_APP_MENU;
        sessionId = sid;
        callerPackage = caller;
        CompanionTierScheduler.setActiveTier(OverlayTierNames.TIER_APP_MENU);
        chipHost.showAppMenuMode(title, labels, submenu, sid, caller);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    /**
     * USB / ANR / BT / launcher_picker — TextView detail+actions (no quick bar).
     * Was: shared with power. Now: chip chrome owns POWER/APP_MENU only.
     */
    private void paintTextTier(String tier, String title, String detail,
            String[] labels, boolean[] states, String sid, String caller) {
        if (labels == null) labels = new String[0];
        ensureShell();
        if (chipHost != null) {
            chipHost.dismiss();
        }
        chipMode = false;
        ensureTextPanel();
        interactive = true;
        activeTier = tier;
        sessionId = sid;
        callerPackage = caller;
        dialogMessage = detail;
        CompanionTierScheduler.setActiveTier(tier);
        titleView.setText(title != null ? title : "");
        if (detail != null && detail.length() > 0) {
            detailView.setVisibility(View.VISIBLE);
            detailView.setText(detail);
        } else {
            detailView.setVisibility(View.GONE);
        }
        applyRows(labels, states);
        rebuildRowViews();
        focusRow(0);
        CompanionOverlayKeyGate.arm(keyHandler);
        requestOverlayFocus();
        mainHandler.removeCallbacks(paintWatchdog);
        scheduleGateRefreshBurst();
    }

    /** Start rapid then steady re-arm of capture props + focus (Y2 Power-hold wheel). */
    private void scheduleGateRefreshBurst() {
        mainHandler.removeCallbacks(gateRefresh);
        gateRefreshBurst = 0;
        mainHandler.post(gateRefresh);
    }

    private void applyRows(String[] labels, boolean[] states) {
        rowLabels = labels;
        rowVisible = states;
        if (rowVisible != null && rowVisible.length != labels.length) {
            rowVisible = null;
        }
        focusedIndex = firstVisibleIndex();
    }

    private int firstVisibleIndex() {
        if (rowLabels == null) return 0;
        for (int i = 0; i < rowLabels.length; i++) {
            if (isRowVisible(i)) return i;
        }
        return 0;
    }

    private boolean isRowVisible(int i) {
        if (rowLabels == null || i < 0 || i >= rowLabels.length) return false;
        if (rowVisible == null) return true;
        return i < rowVisible.length && rowVisible[i];
    }

    private void rebuildRowViews() {
        if (rowsHost == null) return;
        rowsHost.removeAllViews();
        if (rowLabels == null) return;
        for (int i = 0; i < rowLabels.length; i++) {
            if (!isRowVisible(i)) continue;
            TextView row = new TextView(this);
            row.setText(rowLabels[i]);
            row.setTextSize(16f);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setTag(Integer.valueOf(i));
            final int rowIndex = i;
            // 2026-07-14 — TextView fallback tier: swipe via ScrollView parent; two-tap confirm.
            attachCompanionRowTap(row, rowIndex);
            rowsHost.addView(row);
        }
        refreshRowChrome();
    }

    /**
     * 2026-07-14 — Companion TextView rows: first tap focuses, second activates.
     * Layman: same poke-twice as Solar menus. Touchscreen feature gate (no app DeviceFeatures).
     * Reversal: remove listener; keys only.
     */
    private void attachCompanionRowTap(final View row, final int index) {
        if (row == null || index < 0) return;
        boolean touch;
        try {
            touch = getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
        } catch (Exception e) {
            touch = false;
        }
        if (!touch) return;
        row.setClickable(true);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (focusedIndex == index) {
                    activateFocusedRow();
                } else {
                    focusRow(index);
                }
            }
        });
    }

    private void refreshRowChrome() {
        if (rowsHost == null) return;
        int childCount = rowsHost.getChildCount();
        for (int c = 0; c < childCount; c++) {
            View v = rowsHost.getChildAt(c);
            if (!(v instanceof TextView)) continue;
            Object tag = v.getTag();
            int idx = tag instanceof Integer ? ((Integer) tag).intValue() : -1;
            TextView tv = (TextView) v;
            boolean focused = idx == focusedIndex;
            tv.setTextColor(focused ? ThemeReader.backgroundColor() : ThemeReader.foregroundColor());
            tv.setBackgroundColor(focused ? ThemeReader.accentColor() : Color.TRANSPARENT);
        }
    }

    private void focusRow(int index) {
        if (rowLabels == null || rowLabels.length == 0) return;
        if (!isRowVisible(index)) {
            index = firstVisibleIndex();
        }
        focusedIndex = index;
        refreshRowChrome();
    }

    private void moveFocus(int delta) {
        if (rowLabels == null || rowLabels.length == 0) return;
        int n = rowLabels.length;
        int i = focusedIndex;
        for (int step = 0; step < n; step++) {
            i = (i + delta + n) % n;
            if (isRowVisible(i)) {
                focusRow(i);
                return;
            }
        }
    }

    private boolean handleKeyDown(int keyCode) {
        // #region agent log
        DebugSession083511.log("H4", "GlobalContextOverlayService.handleKeyDown",
                "enter", "{\"keyCode\":" + keyCode
                        + ",\"interactive\":" + interactive
                        + ",\"chipMode\":" + chipMode
                        + ",\"chipShowing\":" + (chipHost != null && chipHost.isShowing())
                        + ",\"hasRoot\":" + (overlayRoot != null) + "}");
        // #endregion
        if (!interactive || overlayRoot == null) return false;
        if (chipMode && chipHost != null && chipHost.isShowing()) {
            return chipHost.onKeyDown(keyCode);
        }
        if (isWheelUp(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            moveFocus(-1);
            return true;
        }
        if (isWheelDown(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            moveFocus(1);
            return true;
        }
        if (isConfirmKey(keyCode) || isBackKey(keyCode)) {
            return true;
        }
        return false;
    }

    private boolean handleKeyUp(int keyCode) {
        // #region agent log
        DebugSession083511.log("H4", "GlobalContextOverlayService.handleKeyUp",
                "enter", "{\"keyCode\":" + keyCode
                        + ",\"interactive\":" + interactive
                        + ",\"chipMode\":" + chipMode
                        + ",\"chipShowing\":" + (chipHost != null && chipHost.isShowing())
                        + ",\"hasRoot\":" + (overlayRoot != null) + "}");
        // #endregion
        if (!interactive || overlayRoot == null) return false;
        if (chipMode && chipHost != null && chipHost.isShowing()) {
            return chipHost.onKeyUp(keyCode);
        }
        if (isConfirmKey(keyCode)) {
            activateFocusedRow();
            return true;
        }
        if (isBackKey(keyCode)) {
            if (OverlayTierNames.TIER_USB_LOCK.equals(activeTier)) {
                Log.i(TAG, "usb_lock BACK swallowed — use Turn Off");
                return true;
            }
            dismissWithCancel();
            return true;
        }
        return false;
    }

    private void activateFocusedRow() {
        int idx = focusedIndex;
        if (!isRowVisible(idx)) return;
        final String tier = activeTier;
        final String sid = sessionId;
        final String caller = callerPackage;
        if (OverlayTierNames.TIER_USB_LOCK.equals(tier)) {
            if (idx == 0) {
                turnOffUsbStorageLock();
            }
            return;
        }
        if (OverlayTierNames.TIER_USB.equals(tier)) {
            if (idx == 0) {
                enableUsbAndShowLock();
                return;
            }
            markUsbSessionDismissed();
            tearDownOverlay(true);
            return;
        }
        if (OverlayTierNames.TIER_BT.equals(tier)) {
            tearDownOverlay(true);
            return;
        }
        if (OverlayTierNames.TIER_NATIVE_ERROR.equals(tier)) {
            broadcastDialogResult(sid, caller, idx + 1);
            tearDownOverlay(false);
            if (CompanionTierScheduler.tryConsumePendingUsbPrompt()) {
                showUsbTier(new Intent());
            }
            return;
        }
        if ("launcher_picker".equals(sid)) {
            String target = (launcherPickerTargets != null
                    && idx >= 0 && idx < launcherPickerTargets.length)
                    ? launcherPickerTargets[idx] : "";
            applyLauncherPickerSelection(target);
            return;
        }
        broadcastAppMenuResult(sid, caller, idx);
        boolean keepOpen = sid != null && sid.startsWith("solar_home_");
        if (!keepOpen) {
            tearDownOverlay(true);
        }
    }

    private void dismissWithCancel() {
        final String tier = activeTier;
        final String sid = sessionId;
        final String caller = callerPackage;
        if (OverlayTierNames.TIER_NATIVE_ERROR.equals(tier)) {
            broadcastDialogResult(sid, caller, -1);
            tearDownOverlay(false);
            if (CompanionTierScheduler.tryConsumePendingUsbPrompt()) {
                showUsbTier(new Intent());
                return;
            }
            return;
        }
        if (OverlayTierNames.TIER_APP_MENU.equals(tier) && sid != null) {
            broadcastAppMenuResult(sid, caller, -1);
        }
        if (OverlayTierNames.TIER_USB_LOCK.equals(tier)) {
            return;
        }
        if (OverlayTierNames.TIER_USB.equals(tier)) {
            markUsbSessionDismissed();
        }
        tearDownOverlay(true);
    }

    private void broadcastAppMenuResult(String sid, String caller, int index) {
        if (sid == null) return;
        if (index >= 0) {
            SolarOverlayStateClient client = SolarOverlayStateClient.get();
            if (client.dispatchActionBound(this, sid, index)) {
                return;
            }
        }
        Intent result = new Intent(CompanionOverlayActions.ACTION_APP_MENU_RESULT);
        result.putExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID, sid);
        result.putExtra(CompanionOverlayActions.EXTRA_SELECTED_INDEX, index);
        if (caller != null && caller.length() > 0) {
            result.setPackage(caller);
        }
        try {
            sendBroadcast(result);
        } catch (Exception ignored) {}
    }

    private void broadcastDialogResult(String sid, String caller, int index) {
        if (sid == null) return;
        Intent result = new Intent(CompanionOverlayActions.ACTION_DIALOG_RESULT);
        result.putExtra(CompanionOverlayActions.EXTRA_MENU_SESSION_ID, sid);
        result.putExtra(CompanionOverlayActions.EXTRA_SELECTED_INDEX, index);
        if (caller != null && caller.length() > 0) {
            result.setPackage(caller);
        }
        try {
            sendBroadcast(result);
        } catch (Exception ignored) {}
    }

    /**
     * User chose Turn on — enable UMS then morph the same shell to the eject message.
     * Layman: prompt stays on screen and becomes the “eject your Y1/Y2…” notice.
     * Does not tear down the overlay (that was the race with MainActivity).
     */
    private void enableUsbAndShowLock() {
        // Keep shell up with a brief busy state if needed — re-arm keys after enable.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = CompanionUmsShell.enable();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Log.w(TAG, "ums enable failed — keep enable prompt");
                            if (overlayRoot == null
                                    || !OverlayTierNames.TIER_USB.equals(activeTier)) {
                                paintUsbPromptOnChipShell();
                            }
                            return;
                        }
                        // Same shell morphs to eject message — do not tear down.
                        paintUsbLockOnChipShell();
                        notifySolarUsbLocked(true, false);
                    }
                });
            }
        }, "CompanionUmsEnable").start();
    }

    /** Turn Off USB storage — disable UMS, unlock Solar, dismiss this shell. */
    private void turnOffUsbStorageLock() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                CompanionUmsShell.disable();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        markUsbSessionDismissed();
                        notifySolarUsbLocked(false, true);
                        tearDownOverlay(true);
                    }
                });
            }
        }, "CompanionUmsDisable").start();
    }

    private void notifySolarUsbLocked(boolean locked, boolean forceOff) {
        try {
            Intent i = new Intent(locked
                    ? CompanionOverlayActions.ACTION_USB_STORAGE_LOCKED
                    : CompanionOverlayActions.ACTION_USB_STORAGE_UNLOCKED);
            i.setPackage(SOLAR_PKG);
            if (!locked && forceOff) {
                i.putExtra(CompanionOverlayActions.EXTRA_USB_FORCE_OFF, true);
            }
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void markUsbSessionDismissed() {
        try {
            SysPropHelper.set("sys.solar.usb.session_dismissed", "1");
        } catch (Exception ignored) {}
    }

    private void ensureChipHost() {
        if (chipHost == null) {
            chipHost = new ChipOverlayHost(this, overlayRoot, chipActions);
        }
    }

    /** Remove TextView panel so chip chrome owns the shell. */
    private void clearTextPanel() {
        if (panel != null && overlayRoot != null) {
            try {
                overlayRoot.removeView(panel);
            } catch (Exception ignored) {}
        }
        panel = null;
        titleView = null;
        detailView = null;
        detailScroll = null;
        rowsHost = null;
    }

    /** Ensure WM shell exists — key-capturing root for chip or TextView children. */
    private void ensureShell() {
        long t0 = android.os.SystemClock.uptimeMillis();
        // Theme already refreshed by paint callers; keep a cheap re-read for cold open.
        if (overlayRoot == null) {
            refreshCompanionTheme();
        }
        if (overlayRoot != null && windowManager != null) {
            // Re-focus after tier swap so wheel still hits this window.
            requestOverlayFocus();
            AgentDebugLog.log("H-D", "GlobalContextOverlayService.ensureShell",
                    "reuse", "{\"themeMs\":"
                            + (android.os.SystemClock.uptimeMillis() - t0) + "}");
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            CompanionOverlayKeyGate.setOverlayOpening(false);
            return;
        }
        overlayRoot = new KeyCapturingRoot(this);
        overlayRoot.setBackgroundColor(0x00000000);
        overlayRoot.setClickable(true);
        overlayRoot.setFocusable(true);
        overlayRoot.setFocusableInTouchMode(true);

        // 2026-07-10 — FOCUSABLE system window so hardware keys arrive without Xposed.
        // Primary input path remains Xposed/MainActivity OVERLAY_KEY IPC (gate props).
        // Soft-input / alt path: CompanionOverlayKeyGate + broadcast still armed.
        // Was: FLAG_NOT_FOCUSABLE → only Xposed IPC (broke when props/hooks missed).
        // Reversal: add FLAG_NOT_FOCUSABLE and drop KeyCapturingRoot if focus steals media.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
        try {
            windowManager.addView(overlayRoot, lp);
            CompanionOverlayKeyGate.setShellVisible(true);
            // Arm key gate immediately so MainActivity/Xposed can forward before paint finishes.
            CompanionOverlayKeyGate.setOverlayActive(true);
            CompanionOverlayKeyGate.setOverlayUiVisible(true);
            requestOverlayFocus();
            mainHandler.removeCallbacks(paintWatchdog);
            mainHandler.postDelayed(paintWatchdog, PAINT_WATCHDOG_MS);
            AgentDebugLog.log("H-B", "GlobalContextOverlayService.ensureShell",
                    "cold_addView", "{\"elapsedMs\":"
                            + (android.os.SystemClock.uptimeMillis() - t0)
                            + ",\"reuse\":false}");
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            overlayRoot = null;
            CompanionOverlayKeyGate.setShellVisible(false);
            CompanionOverlayKeyGate.setOverlayOpening(false);
            AgentDebugLog.log("H-B", "GlobalContextOverlayService.ensureShell",
                    "addView_fail", "{\"err\":\""
                            + (e.getMessage() != null
                                    ? e.getMessage().replace("\"", "'") : "ex")
                            + "\"}");
        }
    }

    /** Claim hardware focus so wheel/Back/OK hit KeyCapturingRoot.dispatchKeyEvent. */
    private void requestOverlayFocus() {
        if (overlayRoot == null) return;
        try {
            overlayRoot.setFocusable(true);
            overlayRoot.setFocusableInTouchMode(true);
            overlayRoot.requestFocus();
            // Second pass after layout — some MTK builds ignore first requestFocus.
            overlayRoot.post(new Runnable() {
                @Override
                public void run() {
                    if (overlayRoot != null) {
                        overlayRoot.requestFocus();
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * 2026-07-10 — Root that eats wheel / Back / OK for the global menu.
     * Layman: when the menu is up, the scroll wheel talks to it — not the app under it.
     * Technical: dispatchKeyEvent → handleKeyDown/Up; Xposed path still feeds the same handlers.
     */
    private final class KeyCapturingRoot extends FrameLayout {
        KeyCapturingRoot(android.content.Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event == null) return super.dispatchKeyEvent(event);
            int code = event.getKeyCode();
            int action = event.getAction();
            // Drop auto-repeat center (held OK must not chain Power → Restart).
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == 23 || code == 66
                    || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == 85)) {
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                if (handleKeyDown(code)) return true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (handleKeyUp(code)) return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public View focusSearch(View focused, int direction) {
            return focused != null ? focused : this;
        }
    }

    /** Build classic TextView panel into overlayRoot (USB/ANR/BT/picker). */
    private void ensureTextPanel() {
        if (panel != null && panel.getParent() == overlayRoot) return;
        if (overlayRoot == null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(ThemeReader.backgroundColor());
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));

        titleView = new TextView(this);
        titleView.setTextColor(ThemeReader.foregroundColor());
        titleView.setTextSize(18f);
        panel.addView(titleView);

        detailView = new TextView(this);
        detailView.setTextColor(ThemeReader.accentColor());
        detailView.setTextSize(14f);
        detailView.setPadding(0, dp(6), 0, dp(6));
        detailView.setVisibility(View.GONE);
        detailScroll = new ScrollView(this);
        detailScroll.addView(detailView);
        LinearLayout.LayoutParams dscLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
        panel.addView(detailScroll, dscLp);

        rowsHost = new LinearLayout(this);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rowsHost);

        FrameLayout.LayoutParams lpPanel = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lpPanel.gravity = Gravity.CENTER;
        overlayRoot.addView(panel, lpPanel);
        OverlayModalTransition.prepareModalPresentPanelOnly(panel);
        OverlayModalTransition.animatePresentPanelOnly(panel, null);
    }

    private void tearDownOverlay(boolean stopService) {
        mainHandler.removeCallbacks(paintWatchdog);
        mainHandler.removeCallbacks(gateRefresh);
        CompanionOverlayKeyGate.disarm();
        CompanionTierScheduler.onOverlayTeardown();
        interactive = false;
        chipMode = false;
        if (chipHost != null) {
            // Instant detach on service teardown (avoid racing removeView with dismiss anim).
            chipHost.dismiss(false);
            chipHost = null;
        }
        activeTier = OverlayTierNames.TIER_NONE;
        sessionId = null;
        callerPackage = null;
        // 2026-07-18 — Animate panel out before remove (was instant tear-down).
        // Reversal: removeView immediately without OverlayModalTransition.animateDismissPanelOnly.
        final android.view.WindowManager winMgr = windowManager;
        final FrameLayout root = overlayRoot;
        final View panelView = panel;
        final boolean stop = stopService;
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (winMgr != null && root != null) {
                    try {
                        winMgr.removeView(root);
                    } catch (Exception ignored) {}
                }
                CompanionOverlayKeyGate.setShellVisible(false);
                overlayRoot = null;
                panel = null;
                titleView = null;
                detailView = null;
                detailScroll = null;
                rowsHost = null;
                if (stop) {
                    stopSelf();
                }
            }
        };
        if (panelView != null && OverlayModalTransition.enabled(this)) {
            OverlayModalTransition.animateDismissPanelOnly(panelView, finish);
        } else {
            finish.run();
        }
    }

    /**
     * 2026-07-15 — Fire-and-forget root shell (reboot / Sleep/Zzz power key).
     * Was: plain "su" only. Now: try setuid paths first (A5 Solar ROM / Y1).
     * Reversal: exec new String[]{"su","-c",cmd} only.
     */
    private static void runRootShell(final String cmd) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] sus = new String[] {"/system/xbin/su", "/system/bin/su", "su"};
                for (int i = 0; i < sus.length; i++) {
                    try {
                        Process p = Runtime.getRuntime().exec(new String[] {sus[i], "-c", cmd});
                        if (p.waitFor() == 0) return;
                    } catch (Exception ignored) {}
                }
            }
        }, "ChipHostRoot").start();
    }

    private static boolean isWheelUp(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == 126
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88;
    }

    private static boolean isWheelDown(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == 127
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87;
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == 66
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == 79;
    }

    private static boolean isBackKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    /** Snapshot key constants — avoid depending on Solar app class. */
    static final class OverlayMenuSnapshotBuilderKeys {
        static final String KIND_POWER = "power";
        private OverlayMenuSnapshotBuilderKeys() {}
    }
}
