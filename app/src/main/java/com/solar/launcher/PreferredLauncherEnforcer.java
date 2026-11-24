package com.solar.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 2026-07-08 — Poll saved HOME and relaunch when Rockbox/JJ/Stock drops foreground.
 * Layman: if you picked JJ, Rockbox, or Stock as home and it hiccups, this quietly reopens it.
 * Technical: 2.5s poll in :watchdog; skips solar HOME and home.applying window.
 * Reversal: stop LauncherWatchdogService; rely on RockboxRestartGrace crash window only.
 */
public final class PreferredLauncherEnforcer implements Runnable {

    private static final long POLL_MS = 2500L;

    private final Context appContext;
    private final Handler handler;

    PreferredLauncherEnforcer(Context context) {
        this.appContext = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    void start() {
        handler.postDelayed(this, POLL_MS);
    }

    void stop() {
        handler.removeCallbacks(this);
    }

    @Override
    public void run() {
        try {
            maybeEnforcePreferredHome();
        } finally {
            handler.postDelayed(this, POLL_MS);
        }
    }

    private void maybeEnforcePreferredHome() {
        String target = LauncherPreference.getHomeTarget(appContext);
        // 2026-07-06 — target=solar: JJ/Rockbox opened from Apps menu stay optional; Back exits normally.
        if (LauncherDefault.TARGET_SOLAR.equals(target)) return;
        if (isHomeApplyInProgress()) return;
        if (com.solar.home.policy.LauncherTransitionGuard.shouldPauseHomeEnforcement()) return;
        if (isPreferredForeground(appContext, target)) return;
        if (LauncherDefault.TARGET_JJ.equals(target)) {
            if (LauncherSwitch.isJjDisabled(appContext)) {
                return;
            }
        } else if (LauncherDefault.TARGET_STOCK.equals(target)) {
            if (LauncherSwitch.isStockDisabled(appContext)) {
                return;
            }
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("target", target);
            d.put("homeProp", LauncherPreference.readHomeTargetProperty());
            DebugD68c5cLog.log("PreferredLauncherEnforcer.maybeEnforcePreferredHome",
                    "relaunch", "B", d);
        } catch (Exception ignored) {}
        // #endregion
        LauncherPreference.launchHomeForTarget(appContext, target);
    }

    private static boolean isPreferredForeground(Context context, String target) {
        if (LauncherDefault.TARGET_JJ.equals(target)) {
            return LauncherSwitch.isJjForeground(context);
        }
        if (LauncherDefault.TARGET_STOCK.equals(target)) {
            return LauncherSwitch.isStockForeground(context);
        }
        return false;
    }

    private static boolean isHomeApplyInProgress() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, LauncherPreference.PROP_HOME_APPLYING, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }
}
