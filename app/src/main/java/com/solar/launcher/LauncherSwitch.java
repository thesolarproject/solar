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
