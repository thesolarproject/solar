package com.solar.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Prevents the system's UsbStorageActivity from ever appearing while Solar is active.
 *
 * ponytail: previous approaches tried to dismiss the system dialog after it appeared:
 *  - ACTION_CLOSE_SYSTEM_DIALOGS → crashed SystemUI on API 17
 *  - HOME key injection → launched Rockbox instead of Solar (both are launchers)
 *  - startActivity race → timing-dependent, still lost the race sometimes
 *
 * Current approach: disable the system component entirely via pm disable on startup.
 * The system simply cannot launch what's disabled — no racing, no crashes.
 * Solar handles USB storage mode itself via its own UI.
 */
public final class Y1UsbFocusHelper {

    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";

    /** SystemUI components to suppress while Solar is the active launcher. */
    private static final String[] SUPPRESSED_COMPONENTS = {
            "com.android.systemui/com.android.systemui.usb.UsbStorageActivity",
    };

    public interface UsbListener {
        void onUsbStateChanged(boolean connected);
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private boolean registered;
    private final UsbListener listener;
    private boolean usbConnected = false;

    public Y1UsbFocusHelper(Activity activity, UsbListener listener) {
        this.activity = activity;
        this.listener = listener;
        // Disable system USB dialog on construction — before it ever has a chance to appear
        suppressSystemComponents();
    }

    public void onResume() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (usbConnected) {
                    try {
                        activity.getWindow().getDecorView().requestFocus();
                    } catch (Exception ignored) {}
                }
                registerIfNeeded();
            }
        });
    }

    public void onDestroy() {
        if (registered && receiver != null) {
            try {
                activity.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            registered = false;
        }
    }

    private Boolean lastConnectedState = null;

    private void registerIfNeeded() {
        if (registered) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return;
                final boolean connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false);
                if (lastConnectedState != null && lastConnectedState == connected) return;
                lastConnectedState = connected;
                usbConnected = connected;
                if (connected) {
                    if (listener != null) listener.onUsbStateChanged(true);
                } else {
                    if (listener != null) listener.onUsbStateChanged(false);
                }
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("connected", connected);
                    DebugAgentLog.log(activity, "Y1UsbFocusHelper.onUsbState",
                            "USB state changed", "H-USB-STATE", d);
                } catch (Exception ignored) {}
                // #endregion
            }
        };
        activity.registerReceiver(receiver, new IntentFilter(ACTION_USB_STATE));
        registered = true;
    }

    /** Returns true if USB cable is currently connected. */
    public boolean isUsbConnected() {
        return usbConnected;
    }

    /**
     * Disable system components that interfere with Solar's USB handling.
     * Uses pm disable via root — the component simply can't launch, no crash.
     * This is the "guided access" approach: prevent rather than dismiss.
     */
    private void suppressSystemComponents() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String component : SUPPRESSED_COMPONENTS) {
                    try {
                        Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "pm disable " + component}).waitFor();
                    } catch (Exception ignored) {}
                }
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("components", SUPPRESSED_COMPONENTS.length);
                    DebugAgentLog.log(activity, "Y1UsbFocusHelper.suppressSystemComponents",
                            "system USB dialog disabled", "H-USB-SUPPRESS", d);
                } catch (Exception ignored) {}
                // #endregion
            }
        }, "SuppressSystemUI").start();
    }

    /**
     * Re-enable suppressed system components. Call this when Solar is about to
     * relinquish its launcher role (e.g. switching to Rockbox).
     */
    public static void restoreSystemComponents() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String component : SUPPRESSED_COMPONENTS) {
                    try {
                        Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "pm enable " + component}).waitFor();
                    } catch (Exception ignored) {}
                }
            }
        }, "RestoreSystemUI").start();
    }
}
