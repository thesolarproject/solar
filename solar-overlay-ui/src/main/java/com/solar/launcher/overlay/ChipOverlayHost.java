package com.solar.launcher.overlay;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-08 — Companion modes for ChipContextMenu (power / app-menu / dialog / volume / toast).
 * Layman: decides which chips and rows show, and what OK does.
 * Technical: replaces TextView shell for POWER + APP_MENU; dialogs stay detail+buttons.
 * Was: GlobalContextOverlayService plain list. Now: chip chrome via shared library.
 * Reversal: remove host; paint TextViews again in companion service.
 */
public final class ChipOverlayHost {

    /**
     * 2026-07-15 — Sleep rightmost after Volume (match MainActivity / OverlayModalHost).
     * Was: Lock=1, Volume=7. Reversal: restore Lock=1 and Volume=7.
     */
    public static final int QUICK_HOME = 0;
    public static final int QUICK_WIFI = 1;
    public static final int QUICK_BT = 2;
    public static final int QUICK_POWER = 3;
    public static final int QUICK_NOW_PLAYING = 4;
    public static final int QUICK_BRIGHTNESS = 5;
    public static final int QUICK_VOLUME = 6;
    public static final int QUICK_SLEEP = 7;

    private static final int VOLUME_DISMISS_MS = 1400;
    private static final int BRIGHTNESS_MAX = 255;
    /** Passive volume HUD mode name (not in OverlayTierNames sysprop set). */
    private static final String MODE_VOLUME = "volume";
    private static final String MODE_TOAST = "toast";

    private final Context context;
    private final ViewGroup overlayRoot;
    private final ChipContextMenu menu;
    private final ChipHostActions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String activeMode = OverlayTierNames.TIER_NONE;
    private String sessionId;
    private String callerPackage;
    private boolean[] hasSubmenu;
    private boolean showNowPlayingChip;
    private long subTierChangedAt;
    private boolean powerMode;
    private boolean appMenuMode;
    private boolean dialogMode;
    private boolean networkTier;
    private boolean mediaSliderTier;
    private boolean usbLockMode;
    /** True while interactive brightness/volume strip is volume (not brightness). */
    private boolean sliderIsVolume;
    /** True when list rows came from Solar power snapshot (dispatch all via binder). */
    private boolean solarPowerRows;

    private final Runnable volumeDismiss = new Runnable() {
        @Override
        public void run() {
            if (MODE_VOLUME.equals(activeMode)) {
                actions.dismissOverlay(true);
            }
        }
    };

    private final Runnable toastDismiss = new Runnable() {
        @Override
        public void run() {
            if (MODE_TOAST.equals(activeMode)) {
                actions.dismissOverlay(true);
            }
        }
    };

    public ChipOverlayHost(Context context, ViewGroup overlayRoot, ChipHostActions actions) {
        this.context = context.getApplicationContext();
        this.overlayRoot = overlayRoot;
        this.actions = actions;
        this.menu = new ChipContextMenu(context);
        menu.setMediaSliderQuickIndices(QUICK_VOLUME, QUICK_BRIGHTNESS);
    }

    public ChipContextMenu menu() {
        return menu;
    }

    public String activeMode() {
        return activeMode;
    }

    public boolean isShowing() {
        return menu.isShowing();
    }

    /** Mark sub-tier paint so center UP grace can swallow the open gesture. */
    public void markSubTierChanged() {
        subTierChangedAt = SystemClock.uptimeMillis();
    }

    /** Optional: show Now Playing chip when Solar binder says music is active. */
    public void setNowPlayingVisible(boolean visible) {
        showNowPlayingChip = visible;
    }

    /**
     * 2026-07-08 — Global quick menu: chips + Restart/Shutdown (+ optional Solar power rows).
     * This is the system-wide shell Y2 Power-hold opens.
     */
    public void showPowerMode(String title, String[] labels, boolean[] states) {
        powerMode = true;
        appMenuMode = false;
        dialogMode = false;
        networkTier = false;
        mediaSliderTier = false;
        usbLockMode = false;
        // 2026-07-10 — Old Solar IPC duplicated quick-bar chip labels as list rows.
        if (isQuickBarMisSnapshot(labels)) {
            labels = null;
            states = null;
        }
        solarPowerRows = labels != null && labels.length > 0;
        activeMode = OverlayTierNames.TIER_POWER;
        sessionId = "power";
        callerPackage = null;
        hasSubmenu = null;
        markSubTierChanged();
        String[] rows = solarPowerRows ? labels : defaultPowerLabels();
        menu.show(overlayRoot, title != null ? title : str(R.string.overlay_power_title),
                null, rows, null, states, listListenerPower(), buildQuickBar(),
                quickListener(), true, false);
        menu.setQuickReturnIndex(QUICK_POWER);
        menu.focusList();
    }

    /**
     * 2026-07-10 — Solar Home options on the EXACT Power-hold shell (not a second APP_MENU look).
     * Layman: same chips/theme/input as Power-hold; list rows are Home's context actions.
     * Technical: powerMode chrome + app-menu result path (solar_home_* session).
     * Reversal: call {@link #showAppMenuMode} for Home again.
     */
    public void showHomeOnPowerShell(String title, String[] labels, boolean[] submenuFlags,
            String sid, String caller) {
        powerMode = true;
        appMenuMode = true;
        dialogMode = false;
        networkTier = false;
        mediaSliderTier = false;
        usbLockMode = false;
        solarPowerRows = true;
        activeMode = OverlayTierNames.TIER_POWER;
        sessionId = sid != null ? sid : "solar_home";
        callerPackage = caller;
        hasSubmenu = submenuFlags;
        markSubTierChanged();
        String[] rows = labels != null && labels.length > 0 ? labels : defaultPowerLabels();
        String t = title != null && title.length() > 0
                ? title : str(R.string.overlay_power_title);
        menu.show(overlayRoot, t, null, rows, null, null, listListenerHomeOnPower(),
                buildQuickBar(), quickListener(), true, false);
        menu.setQuickReturnIndex(QUICK_POWER);
        menu.focusList();
    }

    /** Morph power rows after async Solar snapshot. */
    public void morphPowerRows(String title, String[] labels, boolean[] states) {
        if (!powerMode || !menu.isShowing()) return;
        if (labels == null || labels.length == 0) return;
        // 2026-07-10 — Ignore stale IPC that painted Wi‑Fi/BT as top-level list rows.
        if (isQuickBarMisSnapshot(labels)) return;
        // Do not clobber Home list with async power snapshot while solar_home_* is live.
        if (appMenuMode && sessionId != null && sessionId.startsWith("solar_home_")) {
            return;
        }
        solarPowerRows = true;
        menu.replaceListContent(labels, null, states, null, listListenerPower(), true);
        menu.focusList();
    }

    /**
     * 2026-07-10 — Third-party options list. Solar Home uses {@link #showHomeOnPowerShell}.
     */
    public void showAppMenuMode(String title, String[] labels, boolean[] submenuFlags,
            String sid, String caller) {
        // Solar Home must use the Power-hold shell — never a separate APP_MENU presentation.
        if (sid != null && sid.startsWith("solar_home_")) {
            showHomeOnPowerShell(title, labels, submenuFlags, sid, caller);
            return;
        }
        powerMode = false;
        appMenuMode = true;
        dialogMode = false;
        networkTier = false;
        mediaSliderTier = false;
        usbLockMode = false;
        activeMode = OverlayTierNames.TIER_APP_MENU;
        sessionId = sid;
        callerPackage = caller;
        hasSubmenu = submenuFlags;
        markSubTierChanged();
        String t = title != null && title.length() > 0 ? title : str(R.string.context_menu_title);
        menu.show(overlayRoot, t, null, labels, null, null, listListenerAppMenu(),
                buildQuickBar(), quickListener(), true, false);
        menu.focusList();
    }

    /** In-place app-menu / Home refresh (submenu drill). */
    public void refreshAppMenu(String title, String[] labels, boolean[] submenuFlags) {
        if (sessionId != null && sessionId.startsWith("solar_home_")) {
            if (!menu.isShowing() || !powerMode) {
                showHomeOnPowerShell(title, labels, submenuFlags, sessionId, callerPackage);
                return;
            }
            hasSubmenu = submenuFlags;
            menu.replaceListContent(labels, null, null, null, listListenerHomeOnPower(), true);
            menu.focusList();
            return;
        }
        if (!appMenuMode || !menu.isShowing()) {
            showAppMenuMode(title, labels, submenuFlags, sessionId, callerPackage);
            return;
        }
        hasSubmenu = submenuFlags;
        menu.replaceListContent(labels, null, null, null, listListenerAppMenu(), true);
        menu.focusList();
    }

    /** Native ANR / USB / BT — no quick bar. */
    public void showNativeDialogMode(String title, String detail, String[] buttons,
            String sid, String caller, String tierName, boolean lockBack) {
        powerMode = false;
        appMenuMode = false;
        dialogMode = true;
        networkTier = false;
        mediaSliderTier = false;
        usbLockMode = lockBack;
        activeMode = tierName != null ? tierName : OverlayTierNames.TIER_NATIVE_ERROR;
        sessionId = sid;
        callerPackage = caller;
        hasSubmenu = null;
        markSubTierChanged();
        menu.show(overlayRoot, title, detail, buttons, null, null, listListenerDialog(),
                null, null, false, true);
        menu.focusList();
    }

    /** Passive volume HUD — auto dismiss. */
    public void showVolumeMode() {
        powerMode = false;
        appMenuMode = false;
        dialogMode = false;
        activeMode = MODE_VOLUME;
        sliderIsVolume = true;
        // 2026-07-18 — 0–100 display (Hearing Safety maps 80% HW → 100% bar).
        // Was: raw AudioManager max/cur — bar stuck ~80% when OS/HS capped. Reversal: raw AM.
        int max = OverlayVolumeDisplay.getDisplayMax();
        int cur = OverlayVolumeDisplay.getDisplayVolume(context);
        menu.show(overlayRoot, null, null, new String[0], null, null, null,
                null, null, false, true);
        menu.showSlider(str(R.string.context_quick_volume), max, cur, true);
        handler.removeCallbacks(volumeDismiss);
        handler.postDelayed(volumeDismiss, VOLUME_DISMISS_MS);
    }

    /** Passive toast — no keys. */
    public void showToastMode(String text, long durationMs) {
        powerMode = false;
        appMenuMode = false;
        dialogMode = true;
        activeMode = MODE_TOAST;
        menu.show(overlayRoot, null, text != null ? text : "", new String[0], null, null, null,
                null, null, false, true);
        handler.removeCallbacks(toastDismiss);
        handler.postDelayed(toastDismiss, Math.max(800L, durationMs));
    }

    public void dismiss() {
        dismiss(true);
    }

    /** 2026-07-18 — animated=false for service teardown (sync detach). */
    public void dismiss(boolean animated) {
        handler.removeCallbacks(volumeDismiss);
        handler.removeCallbacks(toastDismiss);
        menu.dismiss(animated);
        activeMode = OverlayTierNames.TIER_NONE;
        powerMode = false;
        appMenuMode = false;
        dialogMode = false;
        networkTier = false;
        mediaSliderTier = false;
        usbLockMode = false;
    }

    /**
     * 2026-07-08 — Key gate entry — DOWN moves focus; UP activates with center grace.
     */
    public boolean onKeyDown(int keyCode) {
        if (!menu.isShowing()) return false;
        if (ChipContextMenu.isConfirmKey(keyCode) || ChipContextMenu.isBackKey(keyCode)) {
            return true;
        }
        // Passive volume: wheel nudges stream then re-arms dismiss.
        if (MODE_VOLUME.equals(activeMode)) {
            if (ChipContextMenu.isWheelUp(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || ChipContextMenu.isSideLeft(keyCode)) {
                nudgeMusicVolume(-1);
                return true;
            }
            if (ChipContextMenu.isWheelDown(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || ChipContextMenu.isSideRight(keyCode)) {
                nudgeMusicVolume(1);
                return true;
            }
        }
        // Interactive slider: apply system volume/brightness while focused.
        if (menu.focusZone() == ChipContextMenu.FocusZone.SLIDER && mediaSliderTier) {
            boolean handled = menu.handleKeyDown(keyCode);
            if (handled) {
                applySliderToSystem();
            }
            return handled;
        }
        return menu.handleKeyDown(keyCode);
    }

    public boolean onKeyUp(int keyCode) {
        if (!menu.isShowing()) return false;
        if (ChipContextMenu.isBackKey(keyCode)) {
            if (usbLockMode) return true;
            return menu.handleKeyUp(keyCode);
        }
        if (ChipContextMenu.isConfirmKey(keyCode)) {
            long now = SystemClock.uptimeMillis();
            if (!OverlayCenterGrace.shouldActivateOnEvent(true, false, now, subTierChangedAt,
                    OverlayCenterGrace.SUB_TIER_CENTER_GRACE_MS)) {
                return true;
            }
            menu.activateFocused();
            return true;
        }
        return false;
    }

    private ChipContextMenu.Listener listListenerPower() {
        return new ChipContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                if (solarPowerRows) {
                    actions.dispatchPowerRow(index);
                    actions.dismissOverlay(true);
                    return;
                }
                if (index == 0) {
                    actions.restartDevice();
                    actions.dismissOverlay(true);
                } else if (index == 1) {
                    actions.shutdownDevice();
                    actions.dismissOverlay(true);
                } else {
                    actions.dispatchPowerRow(index);
                    actions.dismissOverlay(true);
                }
            }
        };
    }

    private ChipContextMenu.Listener listListenerAppMenu() {
        return new ChipContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                boolean opensSub = hasSubmenu != null && index >= 0
                        && index < hasSubmenu.length && hasSubmenu[index];
                boolean keep = actions.onAppMenuSelected(sessionId, callerPackage, index,
                        opensSub);
                if (!keep) {
                    actions.dismissOverlay(true);
                }
            }
        };
    }

    /** Home rows on power shell — same result path as APP_MENU (broadcast / binder). */
    private ChipContextMenu.Listener listListenerHomeOnPower() {
        return listListenerAppMenu();
    }

    private ChipContextMenu.Listener listListenerDialog() {
        return new ChipContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                if (OverlayTierNames.TIER_NATIVE_ERROR.equals(activeMode)) {
                    broadcastDialogResult(index + 1);
                    actions.dismissOverlay(false);
                    return;
                }
                // USB prompt + lock stay on this shell until Turn Off / Dismiss (2026-07-10).
                // Was: !usbLockMode → dismiss after Turn on → lost eject message.
                if (OverlayTierNames.TIER_USB.equals(activeMode)
                        || OverlayTierNames.TIER_USB_LOCK.equals(activeMode)) {
                    actions.dispatchDialogSelection(activeMode, index);
                    return;
                }
                // BT / other dialogs — companion handles, then dismiss unless lockBack.
                actions.dispatchDialogSelection(activeMode, index);
                broadcastDialogResult(index);
                if (!usbLockMode) {
                    actions.dismissOverlay(true);
                }
            }
        };
    }

    private ChipContextMenu.QuickBarListener quickListener() {
        return new ChipContextMenu.QuickBarListener() {
            @Override
            public void onQuickSelected(int index) {
                switch (index) {
                    case QUICK_HOME:
                        actions.launchHome();
                        actions.dismissOverlay(true);
                        break;
                    case QUICK_WIFI:
                        openWifiTier();
                        break;
                    case QUICK_BT:
                        openBtTier();
                        break;
                    case QUICK_POWER:
                        openLocalPowerTier();
                        break;
                    case QUICK_NOW_PLAYING:
                        actions.openNowPlaying();
                        actions.dismissOverlay(true);
                        break;
                    case QUICK_BRIGHTNESS:
                        openBrightnessSlider();
                        break;
                    case QUICK_VOLUME:
                        openVolumeSlider();
                        break;
                    case QUICK_SLEEP:
                        // 2026-07-15 — Right-end Zzz (was Lock at index 1).
                        actions.screenSleep();
                        actions.dismissOverlay(true);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onBackActivated() {
                if (networkTier || mediaSliderTier) {
                    leaveSubTier();
                    return;
                }
                if (appMenuMode) {
                    actions.onAppMenuSelected(sessionId, callerPackage,
                            OverlayMenuContract.RESULT_CANCELLED, false);
                } else if (dialogMode && OverlayTierNames.TIER_NATIVE_ERROR.equals(activeMode)) {
                    broadcastDialogResult(OverlayMenuContract.RESULT_CANCELLED);
                }
                actions.dismissOverlay(true);
            }
        };
    }

    private void leaveSubTier() {
        networkTier = false;
        mediaSliderTier = false;
        menu.hideSlider();
        if (powerMode) {
            openLocalPowerTier();
        } else if (appMenuMode) {
            // Restore empty list until companion re-paints — focus quick Power/Home.
            menu.replaceListContent(new String[0], null, null, null, null, true);
            menu.focusQuick(QUICK_HOME);
        } else {
            actions.dismissOverlay(true);
        }
    }

    private void openLocalPowerTier() {
        markSubTierChanged();
        powerMode = true;
        networkTier = false;
        mediaSliderTier = false;
        solarPowerRows = false;
        activeMode = OverlayTierNames.TIER_POWER;
        String[] labels = defaultPowerLabels();
        menu.replaceListContent(labels, null, null, null, listListenerPower(), true);
        menu.setQuickReturnIndex(QUICK_POWER);
        menu.focusList();
    }

    private void openWifiTier() {
        markSubTierChanged();
        networkTier = true;
        mediaSliderTier = false;
        menu.setQuickReturnIndex(QUICK_WIFI);
        final WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        final boolean on = wifi != null && wifi.isWifiEnabled();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<String> states = new ArrayList<String>();
        labels.add(on ? str(R.string.context_wifi_off) : str(R.string.context_wifi_on));
        states.add(on ? str(R.string.context_wifi_connected) : "");
        if (on && wifi != null) {
            try {
                wifi.startScan();
                List<ScanResult> scan = wifi.getScanResults();
                if (scan != null) {
                    int n = Math.min(8, scan.size());
                    for (int i = 0; i < n; i++) {
                        ScanResult r = scan.get(i);
                        if (r == null || r.SSID == null || r.SSID.length() == 0) continue;
                        labels.add(r.SSID);
                        states.add("");
                    }
                }
            } catch (SecurityException denied) {
                labels.add(str(R.string.context_wifi_scanning));
                states.add("");
            } catch (Exception ignored) {
                labels.add(str(R.string.context_wifi_scanning));
                states.add("");
            }
        }
        final String[] lab = labels.toArray(new String[labels.size()]);
        final String[] st = states.toArray(new String[states.size()]);
        menu.replaceListContent(lab, null, null, st, new ChipContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                if (index == 0 && wifi != null) {
                    try {
                        wifi.setWifiEnabled(!on);
                    } catch (Exception ignored) {}
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            openWifiTier();
                        }
                    }, 600);
                }
            }
        }, true);
        menu.focusList();
    }

    private void openBtTier() {
        markSubTierChanged();
        networkTier = true;
        mediaSliderTier = false;
        menu.setQuickReturnIndex(QUICK_BT);
        final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        final boolean on = bt != null && bt.isEnabled();
        String[] labels = new String[] {
                on ? str(R.string.overlay_bt_toggle_off) : str(R.string.overlay_bt_toggle_on)
        };
        String[] states = new String[] {
                on ? str(R.string.context_bluetooth_on) : str(R.string.context_bluetooth_off)
        };
        menu.replaceListContent(labels, null, null, states, new ChipContextMenu.Listener() {
            @Override
            public void onSelected(int index) {
                if (index != 0 || bt == null) return;
                try {
                    if (on) bt.disable();
                    else bt.enable();
                } catch (SecurityException denied) {
                } catch (Exception ignored) {}
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        openBtTier();
                    }
                }, 600);
            }
        }, true);
        menu.focusList();
    }

    private void openVolumeSlider() {
        markSubTierChanged();
        mediaSliderTier = true;
        networkTier = false;
        sliderIsVolume = true;
        menu.setQuickReturnIndex(QUICK_VOLUME);
        // 2026-07-18 — Display 0–100 (was raw stream max → 80% bar with Hearing Safety).
        int max = OverlayVolumeDisplay.getDisplayMax();
        int cur = OverlayVolumeDisplay.getDisplayVolume(context);
        menu.showSlider(str(R.string.context_quick_volume), max, cur, true);
    }

    private void openBrightnessSlider() {
        markSubTierChanged();
        mediaSliderTier = true;
        networkTier = false;
        sliderIsVolume = false;
        menu.setQuickReturnIndex(QUICK_BRIGHTNESS);
        int cur = readBrightness();
        menu.showSlider(str(R.string.context_quick_brightness), BRIGHTNESS_MAX, cur, false);
    }

    private void applySliderToSystem() {
        int v = menu.sliderValue();
        if (menu.focusZone() != ChipContextMenu.FocusZone.SLIDER) return;
        if (sliderIsVolume) {
            // 2026-07-18 — Slider is 0–100 display; map to HW index (Hearing Safety aware).
            OverlayVolumeDisplay.setFromDisplay(context, v);
        } else {
            writeBrightness(v);
        }
    }

    private void nudgeMusicVolume(int delta) {
        try {
            // 2026-07-18 — Step in display space; HS temp unlock at ear via OverlayVolumeDisplay.
            int display = OverlayVolumeDisplay.adjustDisplay(context, delta > 0);
            int max = OverlayVolumeDisplay.getDisplayMax();
            menu.updateSlider(display, max);
            handler.removeCallbacks(volumeDismiss);
            handler.postDelayed(volumeDismiss, VOLUME_DISMISS_MS);
        } catch (Exception ignored) {}
    }

    private ChipContextMenu.QuickItem[] buildQuickBar() {
        // 2026-07-18 — Quick-bar volume chip uses 0–100 display (ear-level icon at HS max).
        int volMax = OverlayVolumeDisplay.getDisplayMax();
        int volCur = OverlayVolumeDisplay.getDisplayVolume(context);
        int bright = readBrightness();
        // 2026-07-15 — Y1-like (incl. A5) show Volume + right-end Sleep; Y2 hides both.
        boolean showVolSleep = isY1();
        return new ChipContextMenu.QuickItem[] {
                new ChipContextMenu.QuickItem(R.drawable.ic_home,
                        str(R.string.context_go_to_home), true),
                new ChipContextMenu.QuickItem(R.drawable.ic_wifi,
                        str(R.string.context_tier_wifi), true),
                new ChipContextMenu.QuickItem(R.drawable.ic_bluetooth,
                        str(R.string.home_menu_bluetooth), true),
                new ChipContextMenu.QuickItem(R.drawable.ic_power,
                        str(R.string.context_quick_power), true),
                new ChipContextMenu.QuickItem(R.drawable.ic_play,
                        str(R.string.context_go_to_now_playing), showNowPlayingChip),
                new ChipContextMenu.QuickItem(
                        ChipContextMenu.brightnessIconResForLevel(bright, BRIGHTNESS_MAX),
                        str(R.string.context_quick_brightness), true),
                new ChipContextMenu.QuickItem(
                        ChipContextMenu.volumeIconResForLevel(volCur, volMax),
                        str(R.string.context_quick_volume), showVolSleep),
                new ChipContextMenu.QuickItem(R.drawable.ic_zzz,
                        str(R.string.context_action_sleep), showVolSleep)
        };
    }

    private String[] defaultPowerLabels() {
        return new String[] {
                str(R.string.context_restart_confirm),
                str(R.string.context_shutdown_confirm)
        };
    }

    /**
     * 2026-07-10 — Detect legacy Solar IPC that sent quick-bar chip labels as list rows.
     * Layman: if the list looks like Home/Wi‑Fi/BT chips, ignore it — chips already show those.
     */
    static boolean isQuickBarMisSnapshot(String[] labels) {
        if (labels == null || labels.length < 4) return false;
        int hits = 0;
        for (String label : labels) {
            if (label == null) continue;
            String t = label.trim();
            if (t.length() == 0) continue;
            // English + Korean resource strings are compared at runtime on device;
            // substring match catches "Wi-Fi" / "Bluetooth" tier titles in legacy bundles.
            if (t.contains("Wi") && t.contains("Fi")) hits++;
            else if (t.toLowerCase().contains("bluetooth") || t.contains("블루투스")) hits++;
            else if (t.toLowerCase().contains("home") || t.contains("홈")) hits++;
        }
        return hits >= 2;
    }

    private void broadcastDialogResult(int index) {
        if (sessionId == null) return;
        Intent result = new Intent(OverlayMenuContract.ACTION_DIALOG_RESULT);
        result.putExtra(OverlayMenuContract.EXTRA_MENU_SESSION_ID, sessionId);
        result.putExtra(OverlayMenuContract.EXTRA_SELECTED_INDEX, index);
        if (callerPackage != null && callerPackage.length() > 0) {
            result.setPackage(callerPackage);
        }
        try {
            context.sendBroadcast(result);
        } catch (Exception ignored) {}
    }

    private String str(int id) {
        return context.getString(id);
    }

    private boolean isY1() {
        return ChipContextMenu.isY1Device();
    }

    private int readBrightness() {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
        } catch (Exception e) {
            return 128;
        }
    }

    private void writeBrightness(int value) {
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(BRIGHTNESS_MAX, value)));
        } catch (Exception ignored) {}
    }
}
