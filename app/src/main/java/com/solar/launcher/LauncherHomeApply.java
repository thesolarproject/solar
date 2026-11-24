package com.solar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * 2026-07-07 — Apply HOME target for any Android CATEGORY_HOME launcher (PM discovery).
 * Layman: pick a home app the normal Android way — no Solar-only manifest tags required.
 * Technical: syncs launcher_pkgs prop, sets custom component + target, broadcasts to helper.
 * Reversal: delete; Settings only offers Solar/Rockbox/JJ rows again.
 */
public final class LauncherHomeApply {

    private LauncherHomeApply() {}

    /** Persist custom HOME from PM-resolved launcher component. */
    public static void applyCustomHome(Context context, ComponentName component, String source) {
        if (context == null || component == null) return;
        PackageManager pm = context.getPackageManager();
        LauncherDiscovery.syncHomeLauncherListProperty(pm);
        String flat = LauncherDiscovery.flattenComponent(component);
        RootShell.run("setprop " + HomeTargetPolicy.PROP_HOME_COMPONENT + " " + flat);
        LauncherPreference.applyHomeTargetWithComponent(context, HomeTargetPolicy.TARGET_CUSTOM, flat);
        LauncherHelperClient.applyHomeTarget(context, HomeTargetPolicy.TARGET_CUSTOM, source);
    }

    /** Label for settings row — app name from PM. */
    public static String labelForPackage(Context context, String packageName) {
        if (context == null || packageName == null) return packageName;
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0));
            return label != null ? label.toString() : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    /** Packages to show as extra HOME rows — excludes Solar/Rockbox/JJ (dedicated rows). */
    public static java.util.List<ResolveInfo> discoverExtraHomeLaunchers(Context context) {
        java.util.List<ResolveInfo> out = new java.util.ArrayList<ResolveInfo>();
        if (context == null) return out;
        PackageManager pm = context.getPackageManager();
        java.util.List<ResolveInfo> all = LauncherDiscovery.queryHomeLaunchers(pm);
        for (ResolveInfo info : all) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (HomeTargetPolicy.SOLAR_PKG.equals(pkg)) continue;
            if (HomeTargetPolicy.JJ_PKG.equals(pkg)) continue;
            // 2026-07-08 — Stock Innioasis has a dedicated Settings/Power row — skip Custom dup.
            if (HomeTargetPolicy.isInnioasisStockPackage(pkg)) continue;
            if (HomeTargetPolicy.HELPER_PKG.equals(pkg)) continue;
            // 2026-07-08 — Setup wizard is not a real home app; never offer it as launcher.
            if ("com.android.provision".equals(pkg)) continue;
            out.add(info);
        }
        return out;
    }

    /** True when persist target is custom and component matches package. */
    public static boolean isCustomHomePackage(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        String target = LauncherPreference.getHomeTarget(context);
        if (!HomeTargetPolicy.TARGET_CUSTOM.equals(target)) return false;
        String flat = LauncherPreference.readHomeComponentProperty();
        String[] parts = HomeTargetPolicy.parseComponent(flat);
        return parts != null && packageName.equals(parts[0]);
    }
}
