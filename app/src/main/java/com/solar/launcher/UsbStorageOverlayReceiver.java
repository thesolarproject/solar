package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * USB storage UI router → Solar MainActivity (monolithic, July-2 style).
 * 2026-07-10 — Restored from reference/Better State/solar-20260702-1521 after companion
 * split races broke enable/lock. Companion is not the USB host.
 * Layman: cable events wake Solar; Turn on → STATE_USB_STORAGE eject screen until Turn Off.
 * Reversal: routeToCompanionModal + OverlayMenuClient (broken dual-handler path).
 */
public final class UsbStorageOverlayReceiver extends BroadcastReceiver {

    private static android.os.Handler sHandler;
    private static Runnable sPendingDismiss = null;
    private static long sLastRouteMs = 0L;
    private static boolean sLastRouteLock = false;
    private static volatile boolean sHandoffInFlight = false;

    private static final long ROUTE_DEBOUNCE_MS = 600L;

    private static android.os.Handler handler() {
        if (sHandler == null) {
            sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return sHandler;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (OverlayTriggers.ACTION_SHOW_OVERLAY_USB_STORAGE_LOCK.equals(action)) {
            routeToSolar(context, false, true, "receiver.lock");
            return;
        }
        if (!OverlayTriggers.ACTION_SHOW_OVERLAY_USB_STORAGE.equals(action)) return;
        boolean lock = intent.getBooleanExtra(OverlayTriggers.EXTRA_USB_STORAGE_LOCK, false);
        routeToSolar(context, !lock, lock, "receiver.usb");
    }

    /** Cable unplug — dismiss any leftover overlay shells + unlock MainActivity flags. */
    static void dismissGlobalOverlayIfActive(final Context context) {
        if (context == null) return;
        if (sPendingDismiss != null) {
            handler().removeCallbacks(sPendingDismiss);
        }
        sPendingDismiss = new Runnable() {
            @Override
            public void run() {
                sPendingDismiss = null;
                OverlayTierScheduler.clearPendingUsbPrompt();
                ExternalInputHandoff.restoreAfterOverlayDismiss(context.getApplicationContext());
                dismissCompanionShell(context);
                notifyMainUsbUnlocked(context, false);
            }
        };
        handler().postDelayed(sPendingDismiss, 400L);
    }

    /**
     * Launch or re-deliver extras into Solar MainActivity (single USB UI owner).
     * @param enable true → evaluate / show enable prompt or auto-connect
     * @param lockOnly true → lock screen only (UMS already on)
     */
    static void routeToSolar(Context context, boolean enable, boolean lockOnly, String caller) {
        if (context == null) return;
        if (!UsbMassStorageExperiment.isEnabled(context)) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastRouteMs < ROUTE_DEBOUNCE_MS && sLastRouteLock == lockOnly) {
            return;
        }
        sLastRouteMs = now;
        sLastRouteLock = lockOnly;

        // #region agent log
        try {
            org.json.JSONObject d = Debug266f21Log.usbSnapshot();
            d.put("caller", caller);
            d.put("enable", enable);
            d.put("lockOnly", lockOnly);
            d.put("fg", ExternalInputHandoff.getForegroundPackageName(context));
            d.put("exported", UsbMassStorageController.isMassStorageExported());
            d.put("stockUi", UsbStorageSessionFlags.preferStockUsbUi(context));
            d.put("auto", UsbStorageSessionFlags.isAutoConnectEnabled(context));
            d.put("offerPrompt", UsbStorageSessionFlags.shouldOfferUsbConnectPrompt(context));
            Debug266f21Log.log(context, "UsbStorageOverlayReceiver.routeToSolar",
                    "MainActivity USB handoff", "USB-SOLAR", d);
            Debug050a40Log.log(context, "UsbStorageOverlayReceiver.routeToSolar",
                    "handoff entry", "H2,H3", d);
        } catch (Exception ignored) {}
        // #endregion

        if (lockOnly) {
            if (!UsbMassStorageController.isMassStorageExported()
                    && !UsbMassStorageController.isKernelMassStorageMode()) {
                // Spurious lock — clear sticky flags if MainActivity alive.
                notifyMainUsbUnlocked(context, false);
                return;
            }
            launchSolarUsbHandoff(context, false, true);
            return;
        }

        if (UsbHostSessionPolicy.hasUserDismissedThisSession(context)) {
            // #region agent log
            try {
                Debug050a40Log.log(context, "UsbStorageOverlayReceiver.routeToSolar",
                        "skip dismissed session", "H3", new org.json.JSONObject());
            } catch (Exception ignored) {}
            // #endregion
            return;
        }
        if (!UsbStorageSessionFlags.shouldOfferUsbConnectPrompt(context)) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("stockUi", UsbStorageSessionFlags.preferStockUsbUi(context));
                d.put("offerPrompt", false);
                Debug050a40Log.log(context, "UsbStorageOverlayReceiver.routeToSolar",
                        "skip — Solar prompt off (stock expected)", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            return;
        }
        if (!UsbHostSessionPolicy.isPromptAllowedAfterBootSettle(context)) {
            // Boot settle — arm MainActivity to flush later if cable still in.
            MainActivity settleAlive = MainActivity.peekForOverlay();
            if (settleAlive != null) {
                settleAlive.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        settleAlive.deferUsbConnectPromptForSetup();
                    }
                });
            }
            return;
        }
        // 2026-07-16 — During first-wait / library scan only (not permanent prep flags).
        // Layman: wait until home is usable; never mark this plug “done” before the sheet shows.
        MainActivity alive = MainActivity.peekForOverlay();
        if (alive != null && alive.shouldDeferUsbConnectPromptNow()) {
            alive.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    alive.deferUsbConnectPromptForSetup();
                }
            });
            return;
        }
        if (!FirstSessionReadyGate.isHomeReadyForUsbPrompt(context)) {
            if (alive != null) {
                alive.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        alive.deferUsbConnectPromptForSetup();
                    }
                });
            } else {
                // Cold start with cable — hand off evaluate extra; do not mark evaluated yet.
                launchSolarUsbHandoff(context, false, false);
            }
            return;
        }

        if (UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            UsbHostSessionPolicy.markPromptEvaluated(context);
            launchSolarUsbHandoff(context, true, true);
            return;
        }

        // Unauthorized kernel UMS without consent — clear before prompt.
        if (UsbMassStorageController.isKernelMassStorageMode()
                && !UsbMassStorageController.isMassStorageExported()) {
            final Context app = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    UsbMassStorageController.disable(app);
                }
            }, "UsbUnauthorizedTearDown").start();
        }

        // Mark evaluated only as we paint the enable path (not when deferred).
        UsbHostSessionPolicy.markPromptEvaluated(context);
        launchSolarUsbHandoff(context, true, false);
    }

    /** @deprecated companion path removed — use {@link #routeToSolar} */
    static void routeToCompanionModal(Context context, boolean lockOnly, String caller) {
        routeToSolar(context, !lockOnly, lockOnly, caller != null ? caller : "routeToCompanionModal");
    }

    static void routeUsbStorageOverlay(Context context, boolean massStorageLockOnly) {
        routeToSolar(context, !massStorageLockOnly, massStorageLockOnly,
                massStorageLockOnly ? "route.lock" : "route.enable");
    }

    static void startUsbLockShell(Context context) {
        // Prefer in-process lock when MainActivity is already alive (no activity restart).
        MainActivity activity = MainActivity.peekForOverlay();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.enterUsbMassStorageLockFromExternal();
                }
            });
            return;
        }
        routeToSolar(context, false, true, "startUsbLockShell");
    }

    static void routeEnablePromptOverlay(Context context, String caller) {
        routeToSolar(context, true, false, caller != null ? caller : "routeEnablePrompt");
    }

    static void routeUsbConnectPromptIfNeeded(Context context) {
        routeToSolar(context, true, false, "routeUsbConnectPromptIfNeeded");
    }

    static void dismissCompanionShell(Context context) {
        if (context == null) return;
        try {
            Intent companion = new Intent(OverlayTriggers.ACTION_DISMISS_OVERLAY);
            companion.setComponent(new ComponentName(
                    com.solar.launcher.overlay.OverlayShellRouter.COMPANION_PKG,
                    com.solar.launcher.overlay.OverlayShellRouter.COMPANION_OVERLAY_SERVICE));
            context.startService(companion);
        } catch (Exception ignored) {}
        try {
            Intent solar = new Intent(OverlayTriggers.ACTION_DISMISS_OVERLAY);
            solar.setComponent(new ComponentName(
                    com.solar.launcher.overlay.OverlayShellRouter.SOLAR_PKG,
                    com.solar.launcher.overlay.OverlayShellRouter.SOLAR_OVERLAY_SERVICE));
            context.startService(solar);
        } catch (Exception ignored) {}
    }

    /** @deprecated alias */
    static void dismissAllUsbOverlayShells(Context context) {
        dismissCompanionShell(context);
    }

    static void notifyMainUsbLocked(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(OverlayTriggers.ACTION_USB_STORAGE_LOCKED);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    static void notifyMainUsbUnlocked(Context context, boolean forceOff) {
        if (context == null) return;
        try {
            Intent i = new Intent(OverlayTriggers.ACTION_USB_STORAGE_UNLOCKED);
            i.setPackage(context.getPackageName());
            if (forceOff) {
                i.putExtra(OverlayTriggers.EXTRA_USB_FORCE_OFF, true);
            }
            context.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    static void notifyMainUsbUnlocked(Context context) {
        notifyMainUsbUnlocked(context, true);
    }

    /**
     * Solar owns USB UX (July-2 monlith). Global overlay prompt is never preferred.
     * Kept for Xposed/tests that still query the flag.
     */
    static boolean shouldUseGlobalOverlayPrompt(Context context) {
        return false;
    }

    static boolean shouldUseGlobalOverlayPromptForTest(String foregroundPkg) {
        return false;
    }

    static boolean shouldPaintUsbLockShellForTest(boolean autoConnect, boolean massStorageExported) {
        return massStorageExported || autoConnect;
    }

    static boolean isEnablePromptOverlayPreferred(Context context) {
        return false;
    }

    /**
     * Start MainActivity with USB handoff extras (evaluate prompt / lock / auto enable).
     * Matches July-2 single-owner model; extras consumed by routeUsbOverlayLaunchExtras.
     */
    static void launchSolarUsbHandoff(Context context, boolean enableStorage, boolean lockOnly) {
        if (context == null) return;
        if (!UsbMassStorageExperiment.isEnabled(context)) return;

        // Alive MainActivity — apply in-process (no extra activity race).
        MainActivity activity = MainActivity.peekForOverlay();
        if (activity != null) {
            final boolean en = enableStorage;
            final boolean lk = lockOnly;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.applyUsbHandoffFromExternal(en, lk);
                }
            });
            return;
        }

        sHandoffInFlight = true;
        try {
            Intent home = new Intent(context, MainActivity.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if (lockOnly) {
                home.putExtra(MainActivity.EXTRA_USB_OVERLAY_LOCK, true);
            }
            if (enableStorage) {
                home.putExtra(MainActivity.EXTRA_USB_OVERLAY_ENABLE, true);
            }
            if (!lockOnly && !enableStorage) {
                home.putExtra(MainActivity.EXTRA_USB_EVALUATE_HOST, true);
            }
            // enable+lock → auto-connect path inside MainActivity
            if (enableStorage && lockOnly) {
                home.putExtra(MainActivity.EXTRA_USB_OVERLAY_ENABLE, true);
                home.putExtra(MainActivity.EXTRA_USB_OVERLAY_LOCK, true);
            }
            context.startActivity(home);
        } catch (Exception e) {
            sHandoffInFlight = false;
            android.util.Log.w("UsbStorageOverlay", "launchSolarUsbHandoff failed", e);
        }
    }

    static void clearHandoffInFlight() {
        sHandoffInFlight = false;
    }

    static boolean isHandoffInFlight() {
        return sHandoffInFlight;
    }
}
