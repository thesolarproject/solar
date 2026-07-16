package com.solar.launcher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;

/**
 * Root UMS toggle via solar-enable/disable-ums.sh.
 * Enable only with user.* consent or auto.* when Auto-Connect is on.
 * Never arm disk mode on bare cable plug without consent.
 */
public final class UsbMassStorageController {

    private static final String ENABLE_ASSET = "y1/solar-enable-ums.sh";
    private static final String DISABLE_ASSET = "y1/solar-disable-ums.sh";
    private static final String ENABLE_SYSTEM = "/system/etc/solar/solar-enable-ums.sh";
    private static final String DISABLE_SYSTEM = "/system/etc/solar/solar-disable-ums.sh";
    private static final String ENABLE_SYSTEM_LEGACY = "/system/etc/solar/y1-enable-ums.sh";
    private static final String DISABLE_SYSTEM_LEGACY = "/system/etc/solar/y1-disable-ums.sh";
    private static final String ENABLE_DATA = "/data/local/tmp/solar-enable-ums.sh";
    private static final String DISABLE_DATA = "/data/local/tmp/solar-disable-ums.sh";

    public static final String SYSPROP_USER_SESSION = "sys.solar.ums.session";
    public static final long DISCONNECT_REENUM_GRACE_MS = 4000L;

    private static volatile boolean sUserSessionActive = false;
    private static volatile long sEnableArmedAtElapsedMs = 0L;

    private UsbMassStorageController() {}

    public static boolean enable(Context context) {
        return enable(context, "unknown");
    }

    public static boolean enable(Context context, String caller) {
        if (context == null) return false;
        if (!UsbMassStorageExperiment.isEnabled(context)) return false;
        if (!isEnablePermitted(context, caller)) return false;
        markUserSessionActive();
        boolean ok = runUmsToggle(context, true);
        if (!ok) clearUserSession();
        return ok;
    }

    public static boolean disable(Context context) {
        if (context == null) return false;
        if (!isKernelMassStorageMode() && !probeLunBackingBound()) {
            clearUserSession();
            return true;
        }
        boolean ok = runUmsToggle(context, false);
        clearUserSession();
        return ok;
    }

    /** Unplug teardown — only when user session is not holding disk mode / re-enum grace. */
    public static boolean disableIfExported(Context context) {
        if (context == null) return false;
        if (shouldIgnoreDisconnectDisable()) return false;
        if (!isKernelMassStorageMode() && !probeLunBackingBound()) {
            clearUserSession();
            return true;
        }
        return disable(context);
    }

    public static void markUserSessionActive() {
        sUserSessionActive = true;
        sEnableArmedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
        writeSysprop(SYSPROP_USER_SESSION, "1");
    }

    public static void clearUserSession() {
        sUserSessionActive = false;
        sEnableArmedAtElapsedMs = 0L;
        writeSysprop(SYSPROP_USER_SESSION, "0");
    }

    public static boolean isUserSessionActive() {
        if (sUserSessionActive) return true;
        return "1".equals(readSysprop(SYSPROP_USER_SESSION));
    }

    public static boolean shouldIgnoreDisconnectDisable() {
        if (!isUserSessionActive()) return false;
        long armed = sEnableArmedAtElapsedMs;
        if (armed <= 0L) return false;
        return (android.os.SystemClock.elapsedRealtime() - armed) < DISCONNECT_REENUM_GRACE_MS;
    }

    private static boolean isEnablePermitted(Context context, String caller) {
        if (caller != null && caller.startsWith("user.")) return true;
        if (caller != null && caller.startsWith("auto.")
                && UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            return true;
        }
        return false;
    }

    public static boolean isMassStorageExported() {
        if (!isKernelMassStorageMode()) return false;
        return probeLunBackingBound();
    }

    public static boolean isKernelMassStorageMode() {
        String config = readSysUsbConfig();
        if (config.contains("mass_storage")) return true;
        return readSysfsFirstLine("/sys/class/android_usb/android0/functions").contains("mass_storage");
    }

    private static boolean runUmsToggle(Context context, boolean enable) {
        String script = resolveUmsScript(context, enable);
        if (script == null) return false;
        boolean rootOk = RootShell.run("sh " + shellQuote(script));
        if (!enable) {
            // Also clear LUNs if script left them
            RootShell.run(
                    "echo > /sys/class/android_usb/android0/f_mass_storage/lun/file 2>/dev/null; "
                            + "echo > /sys/class/android_usb/android0/f_mass_storage/lun1/file 2>/dev/null; "
                            + "vdc volume unshare /storage/sdcard0 ums 2>/dev/null; "
                            + "vdc volume unshare /storage/sdcard1 ums 2>/dev/null; "
                            + "setprop sys.usb.config adb");
            return !isKernelMassStorageMode() || !probeLunBackingBound() || rootOk;
        }
        return rootOk || isMassStorageExported();
    }

    private static String resolveUmsScript(Context context, boolean enable) {
        String system = enable ? ENABLE_SYSTEM : DISABLE_SYSTEM;
        if (new File(system).isFile()) return system;
        String legacy = enable ? ENABLE_SYSTEM_LEGACY : DISABLE_SYSTEM_LEGACY;
        if (new File(legacy).isFile()) return legacy;
        String data = enable ? ENABLE_DATA : DISABLE_DATA;
        if (new File(data).isFile()) return data;
        String asset = enable ? ENABLE_ASSET : DISABLE_ASSET;
        String name = enable ? "solar-enable-ums.sh" : "solar-disable-ums.sh";
        File cached = new File(context.getCacheDir(), name);
        if (!extractAsset(context, asset, cached)) return null;
        RootShell.run("cp " + shellQuote(cached.getAbsolutePath()) + " " + shellQuote(data)
                + " && chmod 755 " + shellQuote(data));
        if (new File(data).isFile()) return data;
        RootShell.run("chmod 755 " + shellQuote(cached.getAbsolutePath()));
        return cached.getAbsolutePath();
    }

    private static boolean extractAsset(Context context, String assetPath, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = context.getAssets().open(assetPath);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) fos.write(buf, 0, n);
            }
            fos.flush();
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    static boolean probeLunBackingBound() {
        String[] paths = {
                "/sys/class/android_usb/android0/f_mass_storage/lun/file",
                "/sys/class/android_usb/android0/f_mass_storage/lun0/file",
                "/sys/class/android_usb/android0/f_mass_storage/lun1/file",
                "/sys/devices/platform/mt_usb/gadget/lun0/file",
                "/sys/devices/platform/mt_usb/gadget/lun1/file"
        };
        for (String p : paths) {
            String v = readSysfsFirstLine(p);
            if (v != null && v.trim().length() > 0) return true;
        }
        return false;
    }

    private static String readSysUsbConfig() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return String.valueOf(sp.getMethod("get", String.class, String.class)
                    .invoke(null, "sys.usb.config", ""));
        } catch (Exception e) {
            return readSysfsFirstLine("/sys/class/android_usb/android0/functions");
        }
    }

    private static String readSysprop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return String.valueOf(sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "0"));
        } catch (Exception e) {
            return "0";
        }
    }

    private static void writeSysprop(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
        } catch (Exception ignored) {}
        RootShell.run("setprop " + key + " " + value);
    }

    private static String readSysfsFirstLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
    }
}
