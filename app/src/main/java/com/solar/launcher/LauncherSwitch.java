package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.solar.home.policy.HomeTargetPolicy;

import java.io.File;
import java.util.List;

/**
 * 2026-07-05 — No-reboot Solar ↔ Rockbox handoff via /data/data/switch-to-stock.sh.
 * Rockbox APK invokes this script directly — must stay rebootless with no .kl swap on return.
 * When changing: assets/y1/switch-to-stock.sh + ROM /system/etc/solar/ copy; Y1RomPrep seeds.
 * Reversal: revert to stock rockbox-y1 script that reboots and swaps keylayouts.
 */
public final class LauncherSwitch {

    private static final String SWITCH_SCRIPT = "sh /data/data/switch-to-stock.sh";
    private static final String SWITCH_TO_JJ = SWITCH_SCRIPT + " --jj";
    /** 2026-07-08 — Factory Innioasis HOME via solar-launcher-exec stock target. */
    private static final String SWITCH_TO_INNIOASIS = SWITCH_SCRIPT + " --innioasis";

    private LauncherSwitch() {}

    /**
     * 2026-07-08 — Device-family stock Innioasis package (y1 on Y1, y2 on Y2).
     * Reversal: hardcode com.innioasis.y1 only.
     */
    public static String stockPackageForDevice(Context context) {
        if (context == null) return HomeTargetPolicy.INNIOASIS_Y1_PKG;
        boolean y2 = DeviceFeatures.isY2();
        String preferred = HomeTargetPolicy.stockPackageForDevice(y2);
        if (isPackagePresent(context, preferred)) return preferred;
        // Fail-open: other family APK may be sideloaded on this device.
        String other = y2 ? HomeTargetPolicy.INNIOASIS_Y1_PKG : HomeTargetPolicy.INNIOASIS_Y2_PKG;
        if (isPackagePresent(context, other)) return other;
        return preferred;
    }

    /** True when either factory Innioasis HOME package is registered with PM. */
    public static boolean isStockInstalled(Context context) {
        if (context == null) return false;
        return isPackagePresent(context, HomeTargetPolicy.INNIOASIS_Y1_PKG)
                || isPackagePresent(context, HomeTargetPolicy.INNIOASIS_Y2_PKG);
    }

    /** Offer Stock row when package installed — no OTA fetch (unlike JJ). */
    public static boolean isStockOfferVisible(Context context) {
        return isStockInstalled(context);
    }

    /** True when preferred stock pkg is installed but pm-disabled. */
    public static boolean isStockDisabled(Context context) {
        String pkg = stockPackageForDevice(context);
        if (pkg == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when stock Innioasis HOME owns the foreground task. */
    public static boolean isStockForeground(Context context) {
        String fg = ExternalInputHandoff.getForegroundPackageName(context);
        return HomeTargetPolicy.isInnioasisStockPackage(fg);
    }

    /**
     * 2026-07-08 — PM-resolved MAIN/LAUNCHER activity for stock pkg; policy default on miss.
     * Layman: find the real screen that opens when you pick factory home.
     * Reversal: return HomeTargetPolicy.stockActivityForPackage(pkg) only.
     */
    public static String resolveStockLaunchActivity(Context context, String pkg) {
        if (pkg == null) return null;
        String fallback = HomeTargetPolicy.stockActivityForPackage(pkg);
        if (context == null) return fallback;
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            main.setPackage(pkg);
            List<ResolveInfo> list = context.getPackageManager()
                    .queryIntentActivities(main, 0);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info != null && info.activityInfo != null) {
                        return info.activityInfo.name;
                    }
                }
            }
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setPackage(pkg);
            list = context.getPackageManager().queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info != null && info.activityInfo != null) {
                        return info.activityInfo.name;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean isPackagePresent(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when com.themoon.y1 (JJ Launcher) is registered with Package Manager. */
    public static boolean isJjInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(LauncherDefault.JJ_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** JJ on /system/app or already in PM — used for availability before OTA install. */
    public static boolean isJjAvailable(Context context) {
        if (isJjInstalled(context)) return true;
        return new File("/system/app/com.themoon.y1.apk").exists();
    }

    /** True when JJ is installed but pm-disabled (typical when Solar/Rockbox is HOME). */
    public static boolean isJjDisabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(LauncherDefault.JJ_PACKAGE, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when JJ Launcher owns the foreground task. */
    public static boolean isJjForeground(Context context) {
        return LauncherDefault.JJ_PACKAGE.equals(
                ExternalInputHandoff.getForegroundPackageName(context));
    }




    /** Launcher switch script shipped in Solar ROM (seeded to /data/data on boot). */
    public static boolean isSwitchScriptAvailable() {
        return new File("/data/data/switch-to-stock.sh").exists()
                || new File("/system/etc/solar/switch-to-stock.sh").exists();
    }




    /** Re-apply stored HOME target on boot — respects Switch to Rockbox / switch-to-stock script. */
    public static void ensurePreferredHome(final Context context) {
        if (context == null) return;
        final String target = LauncherPreference.getHomeTarget(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm enable com.android.systemui/com.android.systemui.usb.UsbStorageActivity"}).waitFor();
                } catch (Exception ignored) {}
                LauncherDefault.ensureBothLaunchersEnabled(context);
                LauncherDefault.ensureHomeTarget(context, target);
            }
        }, "SolarPreferredHome").start();
    }

    /**
     * While Solar MainActivity is active, prefer Solar HOME and pm-disable Rockbox.
     * 2026-07-05 — keep Solar HOME while MainActivity is active.
     */
    public static void ensureSolarPreferredHome(final Context context) {
        if (context == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm enable com.android.systemui/com.android.systemui.usb.UsbStorageActivity"}).waitFor();
                } catch (Exception ignored) {}
                LauncherDefault.ensureBothLaunchersEnabled(context);
                LauncherDefault.ensureHomeTarget(context, LauncherDefault.TARGET_SOLAR);
            }
        }, "SolarPreferredHome").start();
    }


    /**
     * 2026-07-06 — Only pm-disable alternate launchers while Solar is the saved HOME target.
     * Layman: respect JJ/Rockbox mode until the user picks Switch to Solar.
     */
    public static void assertInactiveLaunchersDisabledForHomeTarget(final Context context) {
    }


    /** Switch back to Solar from Rockbox — preferred HOME + start MainActivity. */
    public static boolean switchToSolar(Context context) {
        boolean ok = RootShell.run(SWITCH_SCRIPT);
        DebugSessionLog.logAlways("LauncherSwitch.switchToSolar", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }


    /** Switch to JJ Launcher — enable JJ, disable Rockbox, start JJ. */
    public static boolean switchToJj(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_JJ);
        DebugSessionLog.logAlways("LauncherSwitch.switchToJj", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    /**
     * 2026-07-08 — Switch to Stock Innioasis HOME — MODE_JJ inject while foreground.
     * Reversal: delete; use TARGET_CUSTOM + applyCustomHome only.
     */
    public static boolean switchToStock(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_INNIOASIS);
        DebugSessionLog.logAlways("LauncherSwitch.switchToStock", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    // #region agent log
    private static org.json.JSONObject debugState(Context context) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("canRunSu", RootShell.canRun());
            o.put("switchScript", isSwitchScriptAvailable());
            o.put("xbinSu", new File("/system/xbin/su").exists());
            o.put("binSu", new File("/system/bin/su").exists());
        } catch (Exception ignored) {}
        return o;
    }
    // #endregion
}
