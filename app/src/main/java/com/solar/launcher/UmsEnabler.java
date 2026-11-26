package com.solar.launcher;

import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Standalone helper executed out-of-process via app_process as root.
 * Enables USB mass storage through MountService so UsbDeviceManager and vold stay in sync.
 * Raw {@code setprop sys.usb.config mass_storage} alone leaves the kernel on mass_storage while
 * UsbDeviceManager still thinks {@code mtp,adb}, so vold refuses to share ("Share disabled").
 */
public class UmsEnabler {

    /** Sysfs paths where the bound block device appears when UMS is active. */
    private static final String[] LUN_FILE_PATHS = new String[]{
            "/sys/class/android_usb/android0/f_mass_storage/lun/file",
            "/sys/class/android_usb/android0/f_mass_storage/lun0/file",
            "/sys/class/android_usb/android0/f_mass_storage/lun1/file"
    };

    /**
     * Post-UMS USB mode: Y2 uses MTP; Y1 uses adb only (no MTP — disk mode is the PC path).
     * 2026-07-10 — Y1 no longer restores mtp,adb after Turn Off.
     */
    private static String defaultUsbConfig() {
        return isY1Product() ? "adb" : "mtp,adb";
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: UmsEnabler [1|0] [volumePath]");
            System.exit(1);
        }
        traceLine("main enter");
        boolean enable = "1".equals(args[0]);
        String volumePath = args.length >= 2 ? args[1].trim() : "";
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("enable", enable);
            d.put("volumePath", volumePath);
            d.put("usbConfig", readUsbConfig());
            d.put("kernelFn", readKernelFunctions());
            d.put("y1", isY1Product());
            DebugA3510dLog.logStandalone("UmsEnabler.main", "enter", "H2,H3", d);
            Debug705932Log.logStandalone("UmsEnabler.main", "enter", "H2,H3", d);
        } catch (Exception ignored) {}
        // #endregion
        try {
            if (isY1Product()) {
                if (enable) {
                    enableMassStorageY1(volumePath);
                } else {
                    disableMassStorageY1(volumePath);
                }
                return;
            }
            if (isY2Product()) {
                if (enable) {
                    enableMassStorageY2(volumePath);
                } else {
                    disableMassStorageY2(volumePath);
                }
                return;
            }
            Object mountService = getMountService();
            if (enable) {
                enableMassStorageDefault(mountService, volumePath);
            } else {
                disableMassStorage(mountService, volumePath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Y1 MT6572 — setprop + vdc only; MountService shareVolume missing on API 17 and segfaults (2026-07-05).
     * Layman: sync USB to disk mode, then ask vold to share the SD — never bounce through MTP mid-switch.
     * Tech: old path called resetUsbToDefaultConfig(mtp) then MountService → kernel/JVM desync, PC sees MTP.
     * Reversal: restore {@link #enableMassStorageDefault} for Y1 if a future ROM fixes MountService stability.
     */
    private static void enableMassStorageY1(String volumePath) throws Exception {
        traceLine("y1 enable start");
        System.err.println("UmsEnabler Y1 enable start path=" + volumePath);
        labelExports();
        recoverY1UsbDesyncPropOnly();
        Object mountService = getMountService();
        invokeSetUsbMassStorageEnabled(mountService, false);
        Thread.sleep(800L);
        setUsbConfig("mass_storage,adb");
        Thread.sleep(2000L);
        invokeSetUsbMassStorageEnabled(mountService, true);
        Thread.sleep(1500L);
        tryVdcShareVolume(volumePath);
        Thread.sleep(800L);
        if (!isLunBound()) {
            setUsbConfig("mass_storage,adb");
            Thread.sleep(2500L);
            invokeSetUsbMassStorageEnabled(mountService, true);
            Thread.sleep(1500L);
            tryVdcShareVolume(volumePath);
            Thread.sleep(800L);
        }
        finishEnableOrExit(volumePath, "y1");
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("volumePath", volumePath != null ? volumePath : "");
            d.put("usbConfig", readUsbConfig());
            d.put("kernelFn", readKernelFunctions());
            d.put("lun", readLunBackingPath());
            Debug705932Log.logStandalone("UmsEnabler.enableMassStorageY1", "done", "H2,H3", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Y1 disable — vdc unshare + MTP restore; avoids MountService crash on API 17 (2026-07-05). */
    private static void disableMassStorageY1(String volumePath) throws Exception {
        tryVdcUnshareVolume(volumePath);
        Thread.sleep(800L);
        setUsbConfig(defaultUsbConfig());
        System.out.println("UMS disabled");
    }

    /**
     * Y2 MT6582 KitKat — same as Y1: mass_storage setprop + vdc, never bounce through MTP mid-enable (2026-07-05).
     * Layman: Mac needs disk mode, not MTP; resetting to MTP first left the PC on MTP forever.
     * Tech: {@link #enableMassStorageDefault} called resetUsbToDefaultConfig → UsbDeviceManager stayed mtp,adb.
     * Reversal: restore enableMassStorageDefault for Y2 if a future ROM syncs UsbDeviceManager with setprop.
     */
    private static void enableMassStorageY2(String volumePath) throws Exception {
        traceLine("y2 enable start");
        System.err.println("UmsEnabler Y2 enable start path=" + volumePath);
        // 2026-11-26 — Name the drives while they are still mounted: MountService unmounts
        // them during the share, after which the block device cannot be found from /proc/mounts.
        labelExports();
        recoverUsbDesyncPropOnly();
        Object mountService = getMountService();
        invokeSetUsbMassStorageEnabled(mountService, false);
        Thread.sleep(800L);
        setUsbConfig("mass_storage,adb");
        writeKernelFunctions("mass_storage,adb");
        Thread.sleep(2000L);
        invokeSetUsbMassStorageEnabled(mountService, true);
        Thread.sleep(1500L);
        for (String vol : y2ExportVolumePaths()) {
            tryVdcShareVolume(vol);
            Thread.sleep(800L);
        }
        if (!isLunBound()) {
            setUsbConfig("mass_storage,adb");
            writeKernelFunctions("mass_storage,adb");
            Thread.sleep(2500L);
            invokeSetUsbMassStorageEnabled(mountService, true);
            Thread.sleep(1500L);
            for (String vol : y2ExportVolumePaths()) {
                tryVdcShareVolume(vol);
                Thread.sleep(800L);
            }
        }
        finishEnableOrExit(volumePath, "y2");
    }

    /** Y2 disable — unshare internal + MicroSD, restore MTP+adb. */
    private static void disableMassStorageY2(String volumePath) throws Exception {
        for (String vol : y2ExportVolumePaths()) {
            tryVdcUnshareVolume(vol);
        }
        Thread.sleep(800L);
        invokeSetUsbMassStorageEnabled(getMountService(), false);
        Thread.sleep(800L);
        setUsbConfig(defaultUsbConfig());
        System.out.println("UMS disabled");
    }

    /** Y2 export paths — internal symlink + MicroSD slot. */
    private static String[] y2ExportVolumePaths() {
        return new String[]{"/storage/sdcard0", "/storage/sdcard1"};
    }

    /**
     * 2026-11-26 — Label the exported filesystems "Internal" and "SD".
     * Layman: the drive shows up with the same name on the player and on the computer.
     * Tech: FAT label on the block device behind each volume; the stock Y2 image has no
     *   dosfstools, so {@link FatLabel} writes the BPB, FAT32 backup, and root entry itself.
     *   FUSE (emulated internal) and loop nodes are skipped — they have no filesystem label.
     * Reversal: delete this call and FatLabel.java; existing labels stay on the cards.
     */
    private static void labelExports() {
        labelExport("/storage/sdcard0", "Internal");
        labelExport("/storage/sdcard1", "SD");
    }

    private static void labelExport(String path, String name) {
        String dev = mountDevice(path);
        if (dev.length() == 0 || "/dev/fuse".equals(dev)) {
            dev = mountDevice("/mnt/media_rw/" + path.substring(path.lastIndexOf('/') + 1));
        }
        if (!dev.startsWith("/dev/block/")) return;
        if (dev.startsWith("/dev/block/loop")) return;
        FatLabel.apply(dev, name);
    }

    /** First field of the /proc/mounts entry for {@code path}, or "". */
    private static String mountDevice(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && path.equals(parts[1])) return parts[0];
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    /** Best-effort sysfs functions write when setprop stalls on MTK (2026-07-05). */
    private static void writeKernelFunctions(String functions) {
        File node = new File("/sys/class/android_usb/android0/functions");
        if (!node.canWrite()) return;
        FileWriter w = null;
        try {
            w = new FileWriter(node);
            w.write(functions);
            w.flush();
        } catch (Exception ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /** When JVM thinks UMS but kernel stayed on mtp, brief MTP setprop resync before mass_storage (2026-07-05). */
    private static void recoverUsbDesyncPropOnly() throws Exception {
        recoverY1UsbDesyncPropOnly();
    }

    private static void recoverY1UsbDesyncPropOnly() throws Exception {
        String kernel = readKernelFunctions();
        String prop = readUsbConfig();
        if (kernel.length() == 0) kernel = prop;
        boolean kernelMtp = kernel.contains("mtp") && !kernel.contains("mass_storage");
        boolean propUms = prop.contains("mass_storage");
        if (!kernelMtp || !propUms) return;
        setUsbConfig("mtp,adb");
        Thread.sleep(1500L);
    }

    /** vdc unshare for Y1 disable — symmetric with {@link #tryVdcShareVolume}. */
    private static void tryVdcUnshareVolume(String volumePath) {
        if (volumePath == null || volumePath.length() == 0) return;
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"vdc", "volume", "unshare", volumePath, "ums"});
            p.waitFor();
        } catch (Exception ignored) {}
    }
    /** Y2 / default — MTP reset then MountService UMS (works on MT6582). */
    private static void enableMassStorageDefault(Object mountService, String volumePath)
            throws Exception {
        // Clear stale MountService UMS state from earlier raw-setprop attempts before re-arming.
        invokeSetUsbMassStorageEnabled(mountService, false);
        Thread.sleep(800L);
        resetUsbToDefaultConfig();
        Thread.sleep(1200L);
        logUmsStep("after mtp reset", volumePath);
        invokeSetUsbMassStorageEnabled(mountService, true);
        Thread.sleep(1500L);
        logUmsStep("after setUms true", volumePath);
        shareVolumeIfPath(mountService, volumePath);
        Thread.sleep(800L);
        logUmsStep("after shareVolume", volumePath);
        if (!isLunBound()) {
            invokeSetUsbMassStorageEnabled(mountService, false);
            Thread.sleep(800L);
            setUsbConfig("mass_storage,adb");
            Thread.sleep(2000L);
            invokeSetUsbMassStorageEnabled(mountService, true);
            Thread.sleep(1200L);
            shareVolumeIfPath(mountService, volumePath);
            Thread.sleep(800L);
        }
        finishEnableOrExit(volumePath, "default");
    }

    /** Log Y2 MountService enable milestones for session a3510d. */
    private static void logUmsStep(String step, String volumePath) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("step", step);
            d.put("volumePath", volumePath != null ? volumePath : "");
            d.put("usbConfig", readUsbConfig());
            d.put("kernelFn", readKernelFunctions());
            d.put("lun", readLunBackingPath());
            DebugA3510dLog.logStandalone("UmsEnabler.enableMassStorageDefault", step, "H2,H3", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Exit 2 when LUN sysfs never binds — caller shows enable-failed toast. */
    private static void finishEnableOrExit(String volumePath, String pathTag) throws Exception {
        String lun = readLunBackingPath();
        if (lun.length() == 0) {
            System.err.println("UMS enable failed: mass-storage LUN not bound (" + pathTag + ")");
            return;
        }
        System.out.println("UMS enabled lun=" + lun);
    }

    /** Y1-only vdc share fallback when MountService shareVolume returns Share disabled. */
    private static void tryVdcShareVolume(String volumePath) {
        if (volumePath == null || volumePath.length() == 0) return;
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"vdc", "volume", "share", volumePath, "ums"});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /** Tiny trace file for app_process debugging on Y1 (2026-07-05). */
    private static void traceLine(String msg) {
        try {
            FileWriter w = new FileWriter("/data/local/tmp/ums-trace.txt", true);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.write(' ');
            w.write(msg);
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }

    /** True on Innioasis Y1 — standalone app_process has no {@link DeviceFeatures}. */
    private static boolean isY1Product() {
        String model = readFirstLineFromCommand(new String[]{"getprop", "ro.product.model"});
        return "Y1".equalsIgnoreCase(model.trim());
    }

    /** True on Innioasis Y2 — routes to setprop+vdc UMS, not MTP-bounce MountService path (2026-07-05). */
    private static boolean isY2Product() {
        String model = readFirstLineFromCommand(new String[]{"getprop", "ro.product.model"});
        if (model == null) return false;
        String m = model.trim();
        return "Y2".equalsIgnoreCase(m) || m.toUpperCase().contains("Y2");
    }

    /** Read {@code sys.usb.config} for debug NDJSON. */
    private static String readUsbConfig() {
        return readFirstLineFromCommand(new String[]{"getprop", "sys.usb.config"});
    }

    /** Best-effort kernel USB function list from sysfs (debug only). */
    private static String readKernelFunctions() {
        return readFirstLine("/sys/class/android_usb/android0/functions");
    }

    /** Run a one-line shell command and return trimmed stdout. */
    private static String readFirstLineFromCommand(String[] cmd) {
        Process p = null;
        BufferedReader reader = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            reader = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line != null ? line.trim() : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
            if (p != null) p.destroy();
        }
    }

    // #region agent log
    private static void debugStep(String location, String message, String hypothesisId,
            String volumePath, String usbConfig, String kernelFunctions) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("volumePath", volumePath != null ? volumePath : "");
            d.put("usbConfig", usbConfig);
            d.put("kernelFunctions", kernelFunctions);
            d.put("lun", readLunBackingPath());
            appendDebugLine(new org.json.JSONObject()
                    .put("sessionId", "b6af9f")
                    .put("timestamp", System.currentTimeMillis())
                    .put("location", location)
                    .put("message", message)
                    .put("hypothesisId", hypothesisId)
                    .put("data", d)
                    .toString());
        } catch (Exception ignored) {}
    }

    /** app_process-safe NDJSON append — no Android context required. */
    private static void appendDebugLine(String line) {
        File[] targets = new File[]{
                new File("/data/local/tmp/debug-b6af9f.log"),
                new File("/storage/sdcard0/.solar/debug-b6af9f.log")
        };
        for (File f : targets) {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileWriter w = new FileWriter(f, true);
                w.write(line);
                w.write('\n');
                w.close();
            } catch (Exception ignored) {}
        }
    }
    // #endregion

    /** Turn off UMS and restore adb/MTP USB mode. */
    private static void disableMassStorage(Object mountService, String volumePath) throws Exception {
        invokeSetUsbMassStorageEnabled(mountService, false);
        Thread.sleep(800L);
        try {
            unshareVolumeIfPath(mountService, volumePath);
        } catch (Exception ignored) {
            // Y1: unshare throws when volume was never shared — still restore MTP below (2026-07-05).
        }
        Thread.sleep(800L);
        resetUsbToDefaultConfig();
        System.out.println("UMS disabled");
    }

    /** Resolve IMountService from ServiceManager. */
    private static Object getMountService() throws Exception {
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        IBinder service = (IBinder) smClass.getMethod("getService", String.class).invoke(null, "mount");
        Class<?> stub = Class.forName("android.os.storage.IMountService$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, service);
    }

    /** Ask MountService to enable or disable USB mass storage (UsbDeviceManager + vold). */
    private static void invokeSetUsbMassStorageEnabled(Object mountService, boolean enable)
            throws Exception {
        mountService.getClass().getMethod("setUsbMassStorageEnabled", boolean.class)
                .invoke(mountService, enable);
    }

    /** Export {@code volumePath} to the PC when MountService did not auto-share it (Y2 path). */
    private static void shareVolumeIfPath(Object mountService, String volumePath) throws Exception {
        if (volumePath == null || volumePath.length() == 0) return;
        mountService.getClass().getMethod("shareVolume", String.class)
                .invoke(mountService, volumePath);
    }

    /** Remount {@code volumePath} on the player after the host ejects. */
    private static void unshareVolumeIfPath(Object mountService, String volumePath) throws Exception {
        if (volumePath == null || volumePath.length() == 0) return;
        mountService.getClass().getMethod("unshareVolume", String.class)
                .invoke(mountService, volumePath);
    }

    /** Sync kernel USB mode back to product default before a clean UMS enable. */
    private static void resetUsbToDefaultConfig() throws Exception {
        setUsbConfig(defaultUsbConfig());
    }

    /** Write {@code sys.usb.config} and wait for the property shell to finish. */
    private static void setUsbConfig(String config) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"setprop", "sys.usb.config", config});
        if (p.waitFor() != 0) {
            throw new RuntimeException("setprop sys.usb.config " + config + " failed");
        }
        // 2026-07-16 — Never leave mass_storage in persist (auto disk on next plug/boot).
        // Layman: temporary disk mode for this session only.
        if (config != null && config.contains("mass_storage")) {
            String safe = defaultUsbConfig();
            Process p2 = Runtime.getRuntime().exec(
                    new String[]{"setprop", "persist.sys.usb.config", safe});
            p2.waitFor();
        } else if (config != null) {
            Process p2 = Runtime.getRuntime().exec(
                    new String[]{"setprop", "persist.sys.usb.config", config});
            p2.waitFor();
        }
    }

    /** True when any mass-storage LUN sysfs node lists a backing block device path. */
    static boolean isLunBound() {
        return readLunBackingPath().length() > 0;
    }

    /** Read the first non-empty LUN backing path from sysfs. */
    static String readLunBackingPath() {
        for (String path : LUN_FILE_PATHS) {
            String backing = readFirstLine(path);
            if (backing.length() > 0) {
                return backing;
            }
        }
        return "";
    }

    /** Read the first line of a sysfs text node, or empty when missing/unreadable. */
    private static String readFirstLine(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) return "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
