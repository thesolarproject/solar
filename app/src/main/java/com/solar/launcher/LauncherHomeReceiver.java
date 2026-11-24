package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * Shell / switch-script entry: set preferred HOME and sync persist.solar.home.target.
 * 2026-07-08 — Honour stock/custom (was: unknown targets silently became Solar and undid the pick).
 * 2026-07-08 — shell_applied=1: prefs + helper PM pin only — avoids nested su while switch script holds root.
 * Reversal: always call applyHomeTarget (nested RootShell deadlocks am broadcast under su -c).
 */
public final class LauncherHomeReceiver extends BroadcastReceiver {

    /** Extra from solar-launcher-exec / switch-to-stock — props + pm already applied in shell. */
    public static final String EXTRA_SHELL_APPLIED = "shell_applied";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!LauncherDefault.ACTION_SET_PREFERRED_HOME.equals(intent.getAction())) return;
        String target = intent.getStringExtra(LauncherDefault.EXTRA_HOME_TARGET);
        // 2026-07-08 — Normalize aliases so shell "innioasis" / null still land on a known target.
        target = HomeTargetPolicy.normalizeTarget(target);
        // 2026-07-08 — Root scripts already flipped pm + persist props; only mirror prefs / PM pin here.
        if (intent.getBooleanExtra(EXTRA_SHELL_APPLIED, false)
                || "shell".equals(intent.getStringExtra(HomeTargetPolicy.EXTRA_HOME_SOURCE))) {
            applyShellMirroredPrefs(context, target, intent);
            return;
        }
        if ("rockbox".equals(target)) {
            LauncherPreference.applyHomeTarget(context, "rockbox");
        } else if (LauncherDefault.TARGET_JJ.equals(target)) {
            LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_JJ);
        } else if (LauncherDefault.TARGET_STOCK.equals(target)) {
            // 2026-07-08 — Factory Innioasis HOME; previously fell through to Solar and undid the switch.
            LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_STOCK);
        } else if (LauncherDefault.TARGET_CUSTOM.equals(target)) {
            // 2026-07-08 — PM-discovered third-party HOME; keep component prop already set by shell/helper.
            String component = intent.getStringExtra(HomeTargetPolicy.EXTRA_HOME_COMPONENT);
            if (component == null || component.length() == 0) {
                component = LauncherPreference.readHomeComponentProperty();
            }
            LauncherPreference.applyHomeTargetWithComponent(
                    context, LauncherDefault.TARGET_CUSTOM, component);
        } else {
            LauncherPreference.applyHomeTarget(context, LauncherDefault.TARGET_SOLAR);
        }
    }

    /**
     * 2026-07-08 — Mirror shell HOME choice into prefs and keep helper as preferred HOME activity.
     * Skips RootShell setprop/pm — the caller (switch script) already owns those under one su session.
     */
    private static void applyShellMirroredPrefs(Context context, String target, Intent intent) {
        Context app = context.getApplicationContext();
        LauncherPreference.writeHomeTargetPrefsOnly(app, target);
        // 2026-07-08 — PM pin only (binder); script already started overlay/enforcer — do not startService here.
        // Reversal: also call SolarOverlayHost / LauncherWatchdogService.ensureStarted (can stall am broadcast).
        LauncherDefault.applyPreferredHelperHome(app);
    }
}
