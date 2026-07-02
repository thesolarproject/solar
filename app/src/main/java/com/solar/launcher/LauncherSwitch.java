package com.solar.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * No-reboot launcher handoff between Solar and Rockbox via /data/data/switch-to-stock.sh.
 */
public final class LauncherSwitch {

    public static final String ROCKBOX_PACKAGE = "org.rockbox";
    private static final String SWITCH_SCRIPT = "sh /data/data/switch-to-stock.sh";
    private static final String SWITCH_TO_ROCKBOX = SWITCH_SCRIPT + " --rockbox";

    private LauncherSwitch() {}

    /** True when org.rockbox is installed (enabled or disabled). */
    public static boolean isRockboxInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(ROCKBOX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Solar ROM with co-installed Rockbox APK (package or /system/app blob). */
    public static boolean isRockboxAvailable(Context context) {
        if (isRockboxInstalled(context)) return true;
        return new File("/system/app/org.rockbox.apk").exists();
    }

    /** Launcher switch script shipped in Solar ROM (seeded to /data/data on boot). */
    public static boolean isSwitchScriptAvailable() {
        return new File("/data/data/switch-to-stock.sh").exists()
                || new File("/system/etc/solar/switch-to-stock.sh").exists();
    }

    /** True when Rockbox is installed but currently disabled (typical when Solar is home). */
    public static boolean isRockboxDisabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(ROCKBOX_PACKAGE, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0
                    && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when Rockbox is installed and currently enabled — should NOT be the case while Solar is home. */
    public static boolean isRockboxEnabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(ROCKBOX_PACKAGE, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Ensures org.rockbox is disabled while Solar is the active launcher.
     * Gracefully stops the Rockbox process first (am force-stop — no crash dialog),
     * then disables the package via pm disable.
     * Runs entirely on a background thread — safe to call from onCreate/onResume.
     */
    public static void ensureRockboxDisabled(final Context context) {
        assertRockboxDisabledWhileSolarHome(context);
    }

    /**
     * While Solar is the active launcher, Rockbox must stay disabled — onCreate and onResume.
     */
    public static void assertRockboxDisabledWhileSolarHome(final Context context) {
        if (context == null) return;
        if (!RockboxDisable.isSolarEnabled(context)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm enable com.android.systemui/com.android.systemui.usb.UsbStorageActivity"}).waitFor();
                } catch (Exception ignored) {}

                for (int attempt = 0; attempt < 30; attempt++) {
                    if (!isRockboxEnabled(context)) return;
                    if (attempt == 0) {
                        android.util.Log.w("LauncherSwitch",
                                "org.rockbox enabled while Solar is home — disabling");
                    }
                    try {
                        Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "am force-stop " + ROCKBOX_PACKAGE}).waitFor();
                        Thread.sleep(300);
                        int code = Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "pm disable " + ROCKBOX_PACKAGE}).waitFor();
                        if (code != 0) {
                            android.util.Log.w("LauncherSwitch",
                                    "pm disable org.rockbox exited " + code);
                        }
                    } catch (Exception e) {
                        android.util.Log.w("LauncherSwitch",
                                "disable Rockbox failed: " + e.getMessage());
                    }
                    if (!isRockboxEnabled(context)) return;
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {}
                }
                if (isRockboxEnabled(context)) {
                    android.util.Log.e("LauncherSwitch",
                            "org.rockbox still enabled after disable retries");
                }
            }
        }, "RockboxAutoDisable").start();
    }

    /** Switch to Rockbox — sync keymap/codecs, enable Rockbox, start it, then disable Solar. */
    public static boolean switchToRockbox(Context context) {
        if (context != null) {
            RockboxCoexistence.prepareForRockboxSwitch(context);
        }
        return runSu(SWITCH_TO_ROCKBOX);
    }

    private static boolean runSu(String command) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int code = proc.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
