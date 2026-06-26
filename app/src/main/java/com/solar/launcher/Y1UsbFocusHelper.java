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
 * Y1 shows systemui UsbStorageActivity when USB is connected — it steals wheel focus.
 * ponytail: CLOSE_SYSTEM_DIALOGS + requestFocus keeps Solar usable while tethered for ADB.
 */
public final class Y1UsbFocusHelper {
    /** API 17 UsbManager constants — not all are public on older SDK stubs. */
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private boolean registered;

    public Y1UsbFocusHelper(Activity activity) {
        this.activity = activity;
    }

    public void onResume() {
        // ponytail: defer until after first layout — CLOSE_SYSTEM_DIALOGS during onResume breaks HOME window on API 17.
        handler.post(new Runnable() {
            @Override
            public void run() {
                reclaimInputFocus("onResume");
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

    private void registerIfNeeded() {
        if (registered) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return;
                if (intent.getBooleanExtra(EXTRA_USB_CONNECTED, false)) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reclaimInputFocus("usbState");
                        }
                    }, 400);
                }
            }
        };
        activity.registerReceiver(receiver, new IntentFilter(ACTION_USB_STATE));
        registered = true;
    }

    void reclaimInputFocus(String reason) {
        try {
            activity.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {}
        try {
            activity.getWindow().getDecorView().requestFocus();
        } catch (Exception ignored) {}
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("reason", reason);
            d.put("hasWindowFocus", activity.hasWindowFocus());
            DebugAgentLog.log(activity, "Y1UsbFocusHelper.reclaimInputFocus",
                    "usb focus reclaim", "H-USB-FOCUS", d);
        } catch (Exception ignored) {}
        // #endregion
    }
}
