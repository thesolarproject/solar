package com.solar.home.policy;

/**
 * 2026-07-08 — Shared HOME target constants for helper, Solar client, and Xposed bridge.
 * Layman: one spelling for which app is home (Solar, JJ, Stock Innioasis, or custom).
 * Technical: pure Java policy JAR; no android imports — compiled into helper, Solar, bridge.
 * Reversal: restore rockbox target if alternate Rockbox HOME returns.
 */
public final class HomeTargetPolicy {

    public static final String PROP_HOME_TARGET = "persist.solar.home.target";
    public static final String PROP_HOME_COMPONENT = "persist.solar.home.component";
    public static final String PROP_HOME_APPLYING = "persist.solar.home.applying";
    /** 2026-07-06 — Uptime-ms deadline while switch/restart suppresses crash/ANR dialogs. */
    public static final String PROP_LAUNCHER_TRANSITION_UNTIL = "sys.solar.launcher.trans_until";

    /** Helper broadcast — commit HOME target (Settings, Apps, overlay). */
    public static final String ACTION_APPLY_HOME_TARGET =
            "com.solar.launcher.homehelper.action.APPLY_HOME_TARGET";
    /** Helper broadcast — force-stop + cold start active launcher only. */
    public static final String ACTION_RESTART_ACTIVE_LAUNCHER =
            "com.solar.launcher.homehelper.action.RESTART_ACTIVE_LAUNCHER";
    public static final String EXTRA_HOME_SOURCE = "source";

    public static final String TARGET_SOLAR = "solar";
    public static final String TARGET_JJ = "jj";
    /** 2026-07-08 — Factory Innioasis HOME (y1 on Y1 hardware, y2 on Y2). */
    public static final String TARGET_STOCK = "stock";
    public static final String TARGET_CUSTOM = "custom";

    public static final String HELPER_PKG = "com.solar.launcher.homehelper";
    public static final String HELPER_HOME_ACTIVITY = "com.solar.launcher.homehelper.LauncherHomeActivity";
    public static final String ACTION_SET_HOME_TARGET = "com.solar.launcher.homehelper.action.SET_HOME_TARGET";
    /** 2026-07-06 — BACK/POWER hold opens HOME picker when Solar overlay is unavailable. */
    public static final String ACTION_SHOW_LAUNCHER_PICKER =
            "com.solar.launcher.homehelper.action.SHOW_LAUNCHER_PICKER";
    public static final String EXTRA_HOME_TARGET = "target";
    public static final String EXTRA_HOME_COMPONENT = "component";

    public static final String SOLAR_PKG = "com.solar.launcher";
    public static final String SOLAR_ACTIVITY = "com.solar.launcher.MainActivity";
    public static final String JJ_PKG = "com.themoon.y1";
    public static final String JJ_ACTIVITY = "com.themoon.y1.MainActivity";
    /** 2026-07-08 — Stock firmware HOME packages — same MODE_JJ wheel inject as JJ. */
    public static final String INNIOASIS_Y1_PKG = "com.innioasis.y1";
    public static final String INNIOASIS_Y2_PKG = "com.innioasis.y2";
    public static final String INNIOASIS_Y1_ACTIVITY = "com.innioasis.y1.MainActivity";
    public static final String INNIOASIS_Y2_ACTIVITY = "com.innioasis.y2.MainActivity";

    public static final String COMPANION_PKG = "com.solar.launcher.globalcontext";
    /** 2026-07-07 — Cached HOME launcher packages (comma-separated) — shell disable loop reads this. */
    public static final String PROP_HOME_LAUNCHER_PKGS = "persist.solar.home.launch_pkgs";
    public static final String COMPANION_OVERLAY_KEEPALIVE =
            "com.solar.launcher.globalcontext.action.OVERLAY_KEEPALIVE";

    private HomeTargetPolicy() {}

    /**
     * 2026-07-08 — Coerce unknown target strings to solar/jj/stock/custom.
     * Rockbox is no longer a supported HOME target.
     */
    public static String normalizeTarget(String target) {
        if (TARGET_JJ.equals(target)) return TARGET_JJ;
        if (TARGET_STOCK.equals(target)) return TARGET_STOCK;
        if (TARGET_CUSTOM.equals(target)) return TARGET_CUSTOM;
        return TARGET_SOLAR;
    }

    /** Flatten ComponentName as pkg/activity for persist prop storage. */
    public static String flattenComponent(String pkg, String activityClass) {
        if (pkg == null || activityClass == null) return "";
        if (activityClass.contains("/")) return activityClass;
        return pkg + "/" + activityClass;
    }

    /** Parse pkg/activity or pkg.activity from prop string — returns null when invalid. */
    public static String[] parseComponent(String flat) {
        if (flat == null || flat.length() == 0) return null;
        String s = flat.trim();
        int slash = s.indexOf('/');
        if (slash > 0 && slash < s.length() - 1) {
            return new String[] { s.substring(0, slash), s.substring(slash + 1) };
        }
        return null;
    }

    /**
     * 2026-07-08 — True when target needs an alternate launcher package enabled.
     * Reversal: rockbox||jj only.
     */
    public static boolean isAlternateHomeTarget(String target) {
        String t = normalizeTarget(target);
        return TARGET_JJ.equals(t) || TARGET_STOCK.equals(t) || TARGET_CUSTOM.equals(t);
    }

    /**
     * 2026-07-08 — Resolve pkg/activity for the user's effective HOME (not the helper).
     * Layman: which real app opens after the middle-man Home press.
     * Technical: stock prefers PROP_HOME_COMPONENT then Y1 defaults; custom uses prop; unknown → Solar.
     * Reversal: remove TARGET_STOCK branch.
     */
    /**
     * Resolve pkg/activity for target. Stock without customComponent defaults to Y1
     * for back-compat; prefer {@link #resolveLaunchComponent(String, String, boolean)}.
     */
    public static String[] resolveLaunchComponent(String target, String customComponentFlat) {
        return resolveLaunchComponent(target, customComponentFlat, false);
    }

    /**
     * 2026-07-16 — Family-aware stock fallback (Y2 → innioasis.y2).
     * Layman: stock Home opens the real factory app for this player, not the other model.
     * Reversal: always Y1 when customComponentFlat empty.
     */
    public static String[] resolveLaunchComponent(String target, String customComponentFlat,
            boolean y2Device) {
        String normalized = normalizeTarget(target);
        if (TARGET_CUSTOM.equals(normalized) || TARGET_STOCK.equals(normalized)) {
            String[] parsed = parseComponent(customComponentFlat);
            if (parsed != null) return parsed;
            if (TARGET_STOCK.equals(normalized)) {
                String pkg = stockPackageForDevice(y2Device);
                return new String[] { pkg, stockActivityForPackage(pkg) };
            }
            return new String[] { SOLAR_PKG, SOLAR_ACTIVITY };
        }
        if (TARGET_JJ.equals(normalized)) {
            return new String[] { JJ_PKG, JJ_ACTIVITY };
        }
        return new String[] { SOLAR_PKG, SOLAR_ACTIVITY };
    }

    /**
     * 2026-07-08 — Stock Innioasis pkg for device family — callers pass y2 from DeviceFeatures.
     * Reversal: always return INNIOASIS_Y1_PKG.
     */
    public static String stockPackageForDevice(boolean y2Device) {
        return y2Device ? INNIOASIS_Y2_PKG : INNIOASIS_Y1_PKG;
    }

    /**
     * 2026-07-08 — Default MainActivity for stock pkg — PM may override via PROP_HOME_COMPONENT.
     * Reversal: delete; shell monkey -p only.
     */
    public static String stockActivityForPackage(String pkg) {
        if (INNIOASIS_Y2_PKG.equals(pkg)) return INNIOASIS_Y2_ACTIVITY;
        return INNIOASIS_Y1_ACTIVITY;
    }

    /** True when package is factory Innioasis HOME (exact y1/y2). */
    public static boolean isInnioasisStockPackage(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        return INNIOASIS_Y1_PKG.equals(pkg) || INNIOASIS_Y2_PKG.equals(pkg);
    }

    /** Flatten {@link #resolveLaunchComponent} as pkg/activity for shell am start -n. */
    public static String flattenLaunchComponent(String target, String customComponentFlat) {
        String[] parts = resolveLaunchComponent(target, customComponentFlat);
        return flattenComponent(parts[0], parts[1]);
    }
}
