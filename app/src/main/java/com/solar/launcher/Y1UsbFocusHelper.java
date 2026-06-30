package com.solar.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Prevents the system's UsbStorageActivity from staying visible while Solar is active.
 *
 * ponytail: approach history:
 *  - ACTION_CLOSE_SYSTEM_DIALOGS → crashed SystemUI on API 17
 *  - HOME key injection → launched Rockbox (also a launcher)
 *  - startActivity REORDER_TO_FRONT → no effect across different tasks
 *  - pm disable component → SystemUI crash loop (system retries endlessly)
 *
 * Current approach: moveTaskToFront() — a system-level API that moves Solar's
 * entire task above the system's UsbStorageActivity. Works across tasks, doesn't
 * crash anything, and Solar is a system app so it has REORDER_TASKS permission.
 * Fired at staggered intervals to catch the system dialog regardless of when it appears.
 */
public final class Y1UsbFocusHelper {

    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";

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
                    // ponytail: staggered moveTaskToFront calls. The system's
                    // UsbStorageActivity may appear at different times depending on
                    // device state. Each attempt checks hasWindowFocus — if Solar
                    // already has focus, the call is a no-op (no scroll reset).
                    final int[] delays = {300, 600, 1000, 1500};
                    for (final int delay : delays) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!activity.hasWindowFocus() && usbConnected) {
                                    bringToFront("usbState-" + delay);
                                }
                            }
                        }, delay);
                    }
                    // Notify listener after last attempt
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onUsbStateChanged(true);
                        }
                    }, delays[delays.length - 1] + 100);
                } else {
                    if (listener != null) listener.onUsbStateChanged(false);
                }
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
     * Bring Solar's task to the front of the task stack using moveTaskToFront().
     * This is a system-level API that works across tasks — it moves Solar above
     * the system's UsbStorageActivity without crashing SystemUI.
     *
     * Does NOT use:
     *  - ACTION_CLOSE_SYSTEM_DIALOGS (crashes SystemUI)
     *  - HOME key injection (launches Rockbox)
     *  - pm disable (SystemUI crash loop)
     */
    private void bringToFront(String reason) {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(activity.getTaskId(), 0);
            }
        } catch (Exception ignored) {}

        try {
            activity.getWindow().getDecorView().requestFocus();
        } catch (Exception ignored) {}

        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("reason", reason);
            d.put("hasWindowFocus", activity.hasWindowFocus());
            d.put("taskId", activity.getTaskId());
            DebugAgentLog.log(activity, "Y1UsbFocusHelper.bringToFront",
                    "usb focus reclaim (moveTaskToFront)", "H-USB-FOCUS", d);
        } catch (Exception ignored) {}
        // #endregion
    }
}
