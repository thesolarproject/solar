package com.solar.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.solar.home.policy.HomeTargetPolicy;
import com.solar.home.policy.LauncherCompetitionPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-07-07 — Discover Android HOME launchers via PackageManager CATEGORY_HOME scan.
 * Layman: find every app Android treats as a home screen — no Solar-specific manifest needed.
 * Technical: queryIntentActivities(MAIN+HOME); filters platform middle-man packages.
 * Reversal: delete; restore hardcoded Rockbox/JJ-only competition lists.
 */
public final class LauncherDiscovery {

    /** Companion emergency HOME is infrastructure, never a user-selectable launcher target. */
    private static final String GLOBAL_CONTEXT_COMPANION_PKG = "com.solar.launcher.globalcontext";

    private LauncherDiscovery() {}

    /** All resolveable HOME activities — excludes helper middle-man only. */
    public static List<ResolveInfo> queryHomeLaunchers(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        if (pm == null) return out;
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> matches = pm.queryIntentActivities(probe, PackageManager.GET_DISABLED_COMPONENTS);
        if (matches == null) return out;
        for (ResolveInfo info : matches) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (HomeTargetPolicy.HELPER_PKG.equals(pkg)) continue;
            if (GLOBAL_CONTEXT_COMPANION_PKG.equals(pkg)) continue;
            out.add(info);
        }
        return out;
    }

    /** Distinct package names declaring CATEGORY_HOME — sorted for stable persist prop. */
    public static List<String> homeLauncherPackages(PackageManager pm) {
        Set<String> seen = new LinkedHashSet<String>();
        List<ResolveInfo> launchers = queryHomeLaunchers(pm);
        for (ResolveInfo info : launchers) {
            if (info.activityInfo != null && info.activityInfo.packageName != null) {
                seen.add(info.activityInfo.packageName);
            }
        }
        List<String> out = new ArrayList<String>(seen);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Default MAIN/HOME activity for a package — first resolve match. */
    public static ComponentName defaultHomeComponent(PackageManager pm, String packageName) {
        if (pm == null || packageName == null) return null;
        List<ResolveInfo> launchers = queryHomeLaunchers(pm);
        for (ResolveInfo info : launchers) {
            if (info.activityInfo != null
                    && packageName.equals(info.activityInfo.packageName)) {
                return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            }
        }
        return null;
    }

    /** pkg/activity flat string for persist.solar.home.component. */
    public static String flattenComponent(ComponentName cn) {
        if (cn == null) return "";
        return HomeTargetPolicy.flattenComponent(cn.getPackageName(), cn.getClassName());
    }

    /** True when package resolves as Android HOME launcher (standard MAIN+HOME intent). */
    public static boolean isHomeLauncherPackage(PackageManager pm, String packageName) {
        if (pm == null || packageName == null) return false;
        if (LauncherCompetitionPolicy.targetForPackage(packageName) != null) return true;
        List<String> pkgs = homeLauncherPackages(pm);
        int size = pkgs.size();
        for (int i = 0; i < size; i++) {
            if (packageName.equals(pkgs.get(i))) return true;
        }
        return false;
    }

    /** Write persist.solar.home.launcher_pkgs for root shell disable-competitors loop. */
    public static void syncHomeLauncherListProperty(PackageManager pm) {
        List<String> pkgs = homeLauncherPackages(pm);
        String joined = LauncherCompetitionPolicy.joinLauncherPkgsProperty(
                pkgs.toArray(new String[pkgs.size()]));
        RootShell.run("setprop " + HomeTargetPolicy.PROP_HOME_LAUNCHER_PKGS + " " + joined);
    }
}
