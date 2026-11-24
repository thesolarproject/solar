package com.solar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.solar.home.policy.HomeTargetPolicy;
import com.solar.home.policy.LauncherCompetitionPolicy;

import org.json.JSONObject;

/**
 * 2026-07-06 — Preferred HOME control via Solar Home Helper middle-man.
 * Layman: Android always thinks the tiny helper owns Home; Solar picks which app really opens.
 * Technical: PM preferred activity is always LauncherHomeActivity; target lives in persist prop + prefs.
 * Reversal: point applyPreferredHome back at Solar/Rockbox/JJ activities directly.
 */
public final class LauncherDefault {
    private static final String TAG = "LauncherDefault";

    public static final String ACTION_SET_PREFERRED_HOME =
            "com.solar.launcher.action.SET_PREFERRED_HOME";
    public static final String EXTRA_HOME_TARGET = "target";
    public static final String TARGET_SOLAR = HomeTargetPolicy.TARGET_SOLAR;
    /** 2026-07-06 — JJ Launcher (MO-ON) third HOME option — package from reference/jj_launcher. */
    public static final String TARGET_JJ = HomeTargetPolicy.TARGET_JJ;
    /** 2026-07-08 — Factory Innioasis HOME (com.innioasis.y1 / .y2) — MODE_JJ inject. */
    public static final String TARGET_STOCK = HomeTargetPolicy.TARGET_STOCK;
    /** 2026-07-07 — Any PM-resolved CATEGORY_HOME launcher (no Solar manifest extension). */
    public static final String TARGET_CUSTOM = HomeTargetPolicy.TARGET_CUSTOM;
    public static final String JJ_PACKAGE = HomeTargetPolicy.JJ_PKG;
    public static final String JJ_ACTIVITY = HomeTargetPolicy.JJ_ACTIVITY;

    private LauncherDefault() {}

    /** Apply persisted HOME choice — helper is always PM preferred; prop names effective launcher. */
    public static void ensureDefaultHome(Context context) {
        ensureHomeTarget(context, LauncherPreference.getHomeTarget(context));
    }

    /** Enable/disable alternate launchers for target, then pin PM HOME to helper activity. */
    public static void ensureHomeTarget(Context context, String target) {
        if (context == null || target == null) return;
        if (false) {
        } else if (TARGET_JJ.equals(target) && LauncherSwitch.isJjInstalled(context)) {
            setPreferredJjHome(context);
        } else if (TARGET_STOCK.equals(target) && LauncherSwitch.isStockInstalled(context)) {
            setPreferredStockHome(context);
        } else if (TARGET_CUSTOM.equals(HomeTargetPolicy.normalizeTarget(target))) {
            setPreferredCustomHome(context);
        } else {
            setPreferredSolarHome(context);
        }
    }

    /** Solar effective HOME — helper owns PM; alternates pm-disabled per competition policy. */
    public static void setPreferredSolarHome(Context context) {
        if (context == null) return;
        if (!isPackageEnabled(context, context.getPackageName())) {
            JSONObject d = new JSONObject();
            try { d.put("solarEnabled", false); } catch (Exception ignored) {}
            DebugAgentLog.log(context, "LauncherDefault.setPreferredSolarHome", "skip disabled", "H-L1", d);
            return;
        }
        try {
            enablePackageIfNeeded(context, context.getPackageName());
            enableHelperIfNeeded(context);
            applyCompetitionPolicy(context, TARGET_SOLAR);
            applyPreferredHelperHome(context);
            Log.i(TAG, "Solar target — helper set as PM preferred HOME");
        } catch (Exception e) {
            Log.w(TAG, "set preferred Solar HOME failed: " + e.getMessage());
        }
    }


    /** 2026-07-06 — JJ effective HOME — helper owns PM; Rockbox disabled via policy JAR. */
    public static void setPreferredJjHome(Context context) {
        if (context == null) return;
        try {
            enablePackageIfNeeded(context, context.getPackageName());
            enablePackageIfNeeded(context, JJ_PACKAGE);
            enableHelperIfNeeded(context);
            applyCompetitionPolicy(context, TARGET_JJ);
            applyPreferredHelperHome(context);
            Log.i(TAG, "JJ target — helper set as PM preferred HOME");
        } catch (Exception e) {
            Log.w(TAG, "set preferred JJ HOME failed: " + e.getMessage());
        }
    }

    /**
     * 2026-07-08 — Stock Innioasis effective HOME — helper owns PM; MODE_JJ inject while fg.
     * Reversal: remove; route users through TARGET_CUSTOM only.
     */
    public static void setPreferredStockHome(Context context) {
        if (context == null) return;
        try {
            enablePackageIfNeeded(context, context.getPackageName());
            enableHelperIfNeeded(context);
            String pkg = LauncherSwitch.stockPackageForDevice(context);
            if (pkg != null) {
                enablePackageIfNeeded(context, pkg);
            }
            applyCompetitionPolicy(context, TARGET_STOCK);
            applyPreferredHelperHome(context);
            Log.i(TAG, "Stock Innioasis target — helper set as PM preferred HOME");
        } catch (Exception e) {
            Log.w(TAG, "set preferred stock HOME failed: " + e.getMessage());
        }
    }

    /** 2026-07-07 — Custom PM HOME — enable chosen pkg; disable other discovered launchers. */
    public static void setPreferredCustomHome(Context context) {
        if (context == null) return;
        try {
            enablePackageIfNeeded(context, context.getPackageName());
            enableHelperIfNeeded(context);
            String[] parts = HomeTargetPolicy.parseComponent(
                    LauncherPreference.readHomeComponentProperty());
            if (parts != null) {
                enablePackageIfNeeded(context, parts[0]);
            }
            applyCompetitionPolicyForActive(context);
            applyPreferredHelperHome(context);
            Log.i(TAG, "Custom HOME target — helper set as PM preferred HOME");
        } catch (Exception e) {
            Log.w(TAG, "set preferred custom HOME failed: " + e.getMessage());
        }
    }

    /** pm-disable packages that compete with effective HOME — never disables active target or Solar. */
    private static void applyCompetitionPolicy(Context context, String target) {
        applyCompetitionPolicyForTarget(context, target);
    }

    /** 2026-07-07 — PM scan drives disable list — not hardcoded Rockbox/JJ only. */
    private static void applyCompetitionPolicyForActive(Context context) {
        String target = LauncherPreference.getHomeTarget(context);
        applyCompetitionPolicyForTarget(context, target);
    }

    /**
     * 2026-07-08 — One batched su for enable/disable instead of N× RootShell.run (UI freeze).
     * Layman: flip all the competing home apps in one go so the screen does not stall.
     * Technical: builds pm enable active + pm disable others; falls back to per-pkg on failure.
     * Reversal: restore per-package disablePackageIfEnabled loop.
     */
    private static void applyCompetitionPolicyForTarget(Context context, String target) {
        LauncherDiscovery.syncHomeLauncherListProperty(context.getPackageManager());
        String activePkg = LauncherCompetitionPolicy.packageForTarget(
                HomeTargetPolicy.normalizeTarget(target));
        if (TARGET_STOCK.equals(HomeTargetPolicy.normalizeTarget(target))) {
            String stock = LauncherSwitch.stockPackageForDevice(context);
            if (stock != null) activePkg = stock;
        }
        String[] discovered = LauncherDiscovery.homeLauncherPackages(context.getPackageManager())
                .toArray(new String[0]);
        String[] disable = LauncherCompetitionPolicy.packagesToDisableForActiveHome(
                activePkg, discovered);
        StringBuilder batch = new StringBuilder();
        if (activePkg != null && activePkg.length() > 0) {
            batch.append("pm enable ").append(activePkg).append("; ");
        }
        batch.append("pm enable ").append(context.getPackageName()).append("; ");
        batch.append("pm enable ").append(HomeTargetPolicy.HELPER_PKG);
        for (int i = 0; i < disable.length; i++) {
            String pkg = disable[i];
            if (LauncherCompetitionPolicy.isPlatformHomePackage(pkg)) continue;
            if (!isInstalledPackage(context, pkg)) continue;
            batch.append("; pm disable ").append(pkg);
        }
        if (!RootShell.run(batch.toString())) {
            for (int i = 0; i < disable.length; i++) {
                String pkg = disable[i];
                if (LauncherCompetitionPolicy.isPlatformHomePackage(pkg)) continue;
                disablePackageIfEnabled(context, pkg, isInstalledPackage(context, pkg));
            }
        }
    }

    private static boolean isInstalledPackage(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** @deprecated use {@link #applyCompetitionPolicy} — kept for shell script parity tests. */

    /** @deprecated use {@link #applyCompetitionPolicy}. */
    static void disableJjIfEnabled(Context context) {
        disablePackageIfEnabled(context, JJ_PACKAGE, LauncherSwitch.isJjInstalled(context));
    }

    /** Solar must stay enabled for overlay; alternates enabled only when they are effective HOME. */
    public static void ensureBothLaunchersEnabled(Context context) {
        if (context == null) return;
        enablePackageIfNeeded(context, context.getPackageName());
        enableHelperIfNeeded(context);
        String target = LauncherPreference.getHomeTarget(context);
        if (LauncherSwitch.isJjInstalled(context) && TARGET_JJ.equals(target)) {
            enablePackageIfNeeded(context, JJ_PACKAGE);
        }
        if (LauncherSwitch.isStockInstalled(context) && TARGET_STOCK.equals(target)) {
            String stock = LauncherSwitch.stockPackageForDevice(context);
            if (stock != null) enablePackageIfNeeded(context, stock);
        }
    }

    /** ComponentName for PM preferred HOME — always the helper middle-man activity. */
    public static ComponentName helperHomeComponent() {
        return new ComponentName(HomeTargetPolicy.HELPER_PKG, HomeTargetPolicy.HELPER_HOME_ACTIVITY);
    }

    private static void enableHelperIfNeeded(Context context) {
        enablePackageIfNeeded(context, HomeTargetPolicy.HELPER_PKG);
    }

    private static void enablePackageIfNeeded(Context context, String pkg) {
        if (isPackageEnabled(context, pkg)) return;
        try {
            RootShell.run("pm enable " + pkg);
        } catch (Exception ignored) {}
    }

    /**
     * Pin PackageManager preferred HOME to helper — never Rockbox/JJ/Solar activities directly.
     * 2026-07-08 — Public for shell_applied broadcast path (prefs mirror without Nested RootShell).
     */
    public static void applyPreferredHelperHome(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.clearPackagePreferredActivities(context.getPackageName());
            pm.clearPackagePreferredActivities(HomeTargetPolicy.HELPER_PKG);
            if (false) {
            }
            if (LauncherSwitch.isJjInstalled(context)) {
                pm.clearPackagePreferredActivities(JJ_PACKAGE);
            }
            if (LauncherSwitch.isStockInstalled(context)) {
                String stock = LauncherSwitch.stockPackageForDevice(context);
                if (stock != null) {
                    pm.clearPackagePreferredActivities(stock);
                }
            }
            ComponentName helper = helperHomeComponent();
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY,
                    new ComponentName[] { helper }, helper);
        } catch (Throwable t) {
            android.util.Log.e("LauncherDefault", "applyPreferredHelperHome failed", t);
        }
    }

    private static void disablePackageIfEnabled(Context context, String pkg, boolean installed) {
        if (context == null || !installed) return;
        if (!isPackageEnabled(context, pkg)) return;
        try {
            RootShell.run("pm disable " + pkg);
        } catch (Exception ignored) {}
    }

    private static boolean isPackageEnabled(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
