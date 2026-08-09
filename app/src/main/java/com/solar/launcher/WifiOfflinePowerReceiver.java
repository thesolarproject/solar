package com.solar.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

/**
 * Process-independent fallback for Solar's offline Wi-Fi power policy.
 * MainActivity owns the richer active-stream/transfer guards while alive; this receiver keeps
 * the policy working after the launcher process has been reclaimed or before its UI starts.
 */
public final class WifiOfflinePowerReceiver extends BroadcastReceiver {
    private static volatile boolean uiPolicyActive;
    private static final String PREFS = "SOLAR_SETTINGS";
    private static final String PREF_OFFLINE_SINCE_WALL_MS = "wifi_offline_since_wall_ms";
    public static final String ACTION_OFFLINE_ALARM =
            "com.solar.launcher.action.WIFI_OFFLINE_POWER_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        reconcile(context.getApplicationContext(),
                intent != null ? intent.getAction() : null);
    }

    /** MainActivity owns richer active-use checks while its process is alive. */
    public static void setUiPolicyActive(boolean active) {
        uiPolicyActive = active;
    }

    /** Called by MainActivity so a process restart can resume the same three-minute window. */
    public static void sync(Context context) {
        if (context == null) return;
        reconcile(context.getApplicationContext(), null);
    }

    /** Clear the fallback timer after explicit user Wi-Fi interaction. */
    public static void onUserWifiIntent(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        clearAnchor(app);
        cancelAlarm(app);
    }

    private static void reconcile(Context app, String action) {
        if (app == null) return;
        if (isExcludedDevice(app)) {
            clearAnchor(app);
            cancelAlarm(app);
            return;
        }
        WifiManager wm = wifi(app);
        if (wm == null || !isWifiEnabled(wm)) {
            clearAnchor(app);
            cancelAlarm(app);
            return;
        }
        if (ConnectivityHelper.isWifiAssociated(app)) {
            clearAnchor(app);
            cancelAlarm(app);
            return;
        }

        long now = System.currentTimeMillis();
        long since = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(PREF_OFFLINE_SINCE_WALL_MS, 0L);
        if (since <= 0L || now < since) {
            since = now;
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(PREF_OFFLINE_SINCE_WALL_MS, since).apply();
        }
        long remaining = WifiOfflinePowerPolicy.remainingDelayMs(since, now);
        boolean alarm = ACTION_OFFLINE_ALARM.equals(action);
        // While MainActivity is alive it has the active-use guards and owns the immediate timer.
        // Keep a retry alarm so the fallback takes over if the process is reclaimed later.
        if (uiPolicyActive) {
            scheduleAlarm(app, Math.max(WifiOfflinePowerPolicy.RETRY_DELAY_MS, remaining));
            return;
        }
        if (!alarm || remaining > 0L) {
            scheduleAlarm(app, Math.max(1L, remaining));
            return;
        }
        try {
            boolean accepted = wm.setWifiEnabled(false);
            if (accepted) {
                // The next WIFI_STATE_DISABLED broadcast clears the anchor. Keep a short retry
                // in case the legacy framework accepts but delays/rejects the request.
                scheduleAlarm(app, WifiOfflinePowerPolicy.RETRY_DELAY_MS);
            } else {
                scheduleAlarm(app, WifiOfflinePowerPolicy.RETRY_DELAY_MS);
            }
        } catch (Exception ignored) {
            scheduleAlarm(app, WifiOfflinePowerPolicy.RETRY_DELAY_MS);
        }
    }

    private static boolean isExcludedDevice(Context app) {
        try {
            if (DeviceFeatures.isEmulator()) return true;
            // Phone Chrome and unknown devices stay safe; only native Solar families are eligible.
            if (com.solar.launcher.phone.PhoneChromePolicy.active(app)) return true;
            return !(DeviceFeatures.isY1() || DeviceFeatures.isY2() || DeviceFeatures.isA5());
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static WifiManager wifi(Context app) {
        try {
            return (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWifiEnabled(WifiManager wm) {
        try {
            return wm != null && wm.isWifiEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PendingIntent alarmIntent(Context app) {
        Intent intent = new Intent(app, WifiOfflinePowerReceiver.class);
        intent.setAction(ACTION_OFFLINE_ALARM);
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void scheduleAlarm(Context app, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + Math.max(1L, delayMs), alarmIntent(app));
            }
        } catch (Exception ignored) {}
    }

    private static void cancelAlarm(Context app) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(app));
        } catch (Exception ignored) {}
    }

    private static void clearAnchor(Context app) {
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(PREF_OFFLINE_SINCE_WALL_MS).apply();
        } catch (Exception ignored) {}
    }
}
