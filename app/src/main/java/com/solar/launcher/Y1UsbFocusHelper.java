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
                    // ponytail: fire BOTH moveTaskToFront + HOME key at staggered intervals.
                    // Immediate (0ms) preempts the system dialog before it appears.
                    // Later intervals catch it if it slips through.
                    // HOME key is safe — Rockbox is disabled on startup, Solar is the only launcher.
                    bringToFront("usbState-immediate");
                    final int[] delays = {200, 500, 1000};
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
     * Bring Solar to front using BOTH mechanisms simultaneously:
     * 1. moveTaskToFront() — system API, moves Solar's task above UsbStorageActivity
     * 2. HOME key — system-level, always brings the default launcher to front
     *
     * Rockbox is disabled on startup so HOME always resolves to Solar.
     */
    private void bringToFront(String reason) {
        // 1. moveTaskToFront — instant, no process spawn
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(activity.getTaskId(), 0);
            }
        } catch (Exception ignored) {}

        // 2. HOME key via root — handled at low level by ActivityManager
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(
                            new String[]{"su", "-c", "input keyevent 3"}).waitFor();
                } catch (Exception ignored) {}
            }
        }, "HomeKey").start();

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
                    "usb focus reclaim (moveTaskToFront+HOME)", "H-USB-FOCUS", d);
        } catch (Exception ignored) {}
        // #endregion
    }
}
