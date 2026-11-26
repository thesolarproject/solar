package com.solar.launcher.xposed.bridge;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 2026-07-05 — Y2 system_server UMS sync — MountService + UsbDeviceManager stay aligned with vold.
 * Layman: when Solar turns on USB disk mode, the system actually shares Internal + MicroSD to the Mac.
 * Tech: hooks {@code setUsbMassStorageEnabled}/{@code shareVolume}; retries after kernel mass_storage desync.
 * Reversal: remove installY2 call — stock MTP-only path returns; root shell enable may fail with Share disabled.
 */
final class UsbMassStorageServerHooks {

    /** Y2 export candidates — internal symlink + MicroSD; vold may only list mounted paths (2026-07-05). */
    private static final String[] Y2_EXPORT_CANDIDATES = new String[]{
            "/storage/sdcard0",
            "/storage/emulated/legacy",
            "/storage/sdcard1"
    };

    private static final String DEFAULT_USB_CONFIG = "mtp,adb";
    private static volatile boolean installed;
    private static Handler mainHandler;
    private static Object mountServiceRef;
    private static Object usbDeviceManagerRef;
    private static ClassLoader systemClassLoader;

    private UsbMassStorageServerHooks() {}

    /** Y2-only — API 19 MountService + UsbDeviceManager on MT6582. */
    static void installY2(LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;
        systemClassLoader = lpparam.classLoader;
        ensureMainHandler();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("pkg", lpparam.packageName);
            BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.installY2", "enter", "H1", d);
        } catch (Throwable ignored) {}
        // #endregion
        if (!isY2UmsExperimentActive()) {
            SolarContextBridge.log("UsbMassStorageServerHooks skipped: Y2 UMS experiment off");
            return;
        }
        installMountService(lpparam);
        installUsbDeviceManager(lpparam);
        installUsbServiceCapture(lpparam);
    }

    /**
     * Solar app sets {@code sys.solar.ums.experiment=1} when the experiment is on (2026-07-05).
     * 2026-11-26 — Also accept {@code persist.sys.solar.ums.experiment}. The volatile prop is
     * gone after a reboot, and this gate is only read once, at system_server startup, so an APK
     * that set the volatile prop could never arm the hooks again after the player rebooted.
     */
    private static boolean isY2UmsExperimentActive() {
        return "1".equals(readSysProp("sys.solar.ums.experiment", "0"))
                || "1".equals(readSysProp("persist.sys.solar.ums.experiment", "0"));
    }

    private static String readSysProp(String key, String def) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            Object v = XposedHelpers.callStaticMethod(sp, "get", key, def);
            return v != null ? String.valueOf(v) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Capture UsbService at registration — binder queryLocalInterface often fails on MTK (2026-07-05). */
    private static void installUsbServiceCapture(LoadPackageParam lpparam) {
        try {
            final Class<?> udmClass = XposedHelpers.findClass(
                    "com.android.server.usb.UsbDeviceManager", lpparam.classLoader);
            Class<?> smClass = XposedHelpers.findClass("android.os.ServiceManager", null);
            XposedHookKit.hookAll(smClass, "addService", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2) return;
                    if (!"usb".equals(String.valueOf(param.args[0]))) return;
                    Object service = param.args[1];
                    captureUdmFromHost(service, udmClass, "addService");
                    if (usbDeviceManagerRef == null && service != null) {
                        try {
                            Object local = XposedHelpers.callMethod(service, "queryLocalInterface",
                                    "android.hardware.usb.IUsbManager");
                            captureUdmFromHost(local, udmClass, "addService.local");
                        } catch (Throwable ignored) {}
                    }
                }
            });
            // UsbService may already be registered before hooks install — probe once (2026-07-05).
            if (mainHandler != null) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        resolveUsbDeviceManagerInstance();
                    }
                }, 3000L);
            }
            SolarContextBridge.log("UsbMassStorageServerHooks ServiceManager.addService hooked");
        } catch (Throwable t) {
            SolarContextBridge.log("installUsbServiceCapture skip: " + t.getClass().getSimpleName());
        }
    }

    private static void captureUdmFromHost(Object host, Class<?> udmClass, String tag) {
        Object udm = scanUsbDeviceManagerField(host, udmClass);
        if (udm != null) {
            usbDeviceManagerRef = udm;
            logResolveUdm(tag, udm);
        }
    }

    private static void ensureMainHandler() {
        if (mainHandler != null) return;
        Looper looper = Looper.getMainLooper();
        if (looper != null) {
            mainHandler = new Handler(looper);
        }
    }

    private static void installMountService(LoadPackageParam lpparam) {
        try {
            Class<?> msClass = XposedHelpers.findClass(
                    "com.android.server.MountService", lpparam.classLoader);
            mountServiceRef = null;

            XposedHookKit.hookAll(msClass, "setUsbMassStorageEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mountServiceRef = param.thisObject;
                    if (param.args == null || param.args.length < 1) return;
                    if (!Boolean.TRUE.equals(param.args[0])) return;
                    // 2026-11-26 — Solar's manual Turn on calls MountService directly and its
                    // root-shell setprop is denied on Y2 SELinux, so the gadget stayed on mtp,adb
                    // and the PC never saw the disk. Move UsbDeviceManager here, before vold shares.
                    // Only when not already mass_storage so shareY2Volumes -> enable cannot loop.
                    if (!UsbMassStorageProbe.readUsbConfig().contains("mass_storage")) {
                        syncUsbDeviceManagerFunctions("mass_storage,adb");
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mountServiceRef = param.thisObject;
                    if (param.args == null || param.args.length < 1) return;
                    boolean enable = Boolean.TRUE.equals(param.args[0]);
                    SolarContextBridge.log("MountService.setUsbMassStorageEnabled(" + enable + ")");
                    if (enable) {
                        // 2026-11-26 — 400 ms raced the gadget re-enum started above; wait for the
                        // new function set to settle before re-binding any LUN it cleared.
                        scheduleShareY2Volumes(param.thisObject, 1200L);
                    }
                }
            });

            XposedHookKit.hookAll(msClass, "shareVolume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mountServiceRef = param.thisObject;
                    if (param.args != null && param.args.length > 0) {
                        String vol = String.valueOf(param.args[0]);
                        SolarContextBridge.log("MountService.shareVolume " + vol);
                        if (isY2BlockedExportPath(vol)) {
                            SolarContextBridge.log("shareVolume skip emulated " + vol);
                            param.setResult(null);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() == null) return;
                    String vol = param.args != null && param.args.length > 0
                            ? String.valueOf(param.args[0]) : "";
                    Throwable t = param.getThrowable();
                    String msg = t.getMessage() != null ? t.getMessage() : "";
                    SolarContextBridge.log("shareVolume failed vol=" + vol + " "
                            + t.getClass().getSimpleName() + " " + msg);
                    if (!shouldRecoverShareFailure(msg)) return;
                    param.setThrowable(null);
                    tryRecoverAndShare(param.thisObject, vol);
                }
            });

            XposedHookKit.hookAll(msClass, "unshareVolume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0) {
                        SolarContextBridge.log("MountService.unshareVolume " + param.args[0]);
                    }
                }
            });

            SolarContextBridge.log("UsbMassStorageServerHooks MountService hooked");
            // #region agent log
            BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.installMountService", "ok", "H1", null);
            // #endregion
        } catch (Throwable t) {
            SolarContextBridge.log("MountService hook skip: " + t.getClass().getSimpleName());
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("err", t.getClass().getSimpleName());
                BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.installMountService", "skip", "H1", d);
            } catch (Throwable ignored) {}
            // #endregion
        }
    }

    /** When setprop reaches mass_storage before MountService, nudge share after UsbDeviceManager updates. */
    private static void installUsbDeviceManager(LoadPackageParam lpparam) {
        try {
            Class<?> usbServiceClass = XposedHelpers.findClass(
                    "com.android.server.usb.UsbService", lpparam.classLoader);
            Class<?> udmClass = XposedHelpers.findClass(
                    "com.android.server.usb.UsbDeviceManager", lpparam.classLoader);

            XposedHookKit.hookAll(usbServiceClass, "<init>", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object udm = scanUsbDeviceManagerField(param.thisObject, udmClass);
                    if (udm != null) {
                        usbDeviceManagerRef = udm;
                        SolarContextBridge.log("UsbService init captured UsbDeviceManager");
                        logResolveUdm("UsbService init", udm);
                    }
                }
            });

            XposedHookKit.hookAll(udmClass, "<init>", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            usbDeviceManagerRef = param.thisObject;
                            SolarContextBridge.log("UsbDeviceManager constructed");
                        }
                    });

            XposedHookKit.hookAll(udmClass, "setCurrentFunctions", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    usbDeviceManagerRef = param.thisObject;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    String functions = String.valueOf(param.args[0]);
                    if (!functions.contains("mass_storage")) return;
                    SolarContextBridge.log("UsbDeviceManager.setCurrentFunctions mass_storage");
                    Object ms = mountServiceRef;
                    if (ms == null) ms = resolveMountService();
                    if (ms != null) {
                        scheduleShareY2Volumes(ms, 1200L);
                    }
                }
            });

            // KitKat may route config via setEnabledFunctions instead of setCurrentFunctions.
            try {
                XposedHookKit.hookAll(udmClass, "setEnabledFunctions", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        usbDeviceManagerRef = param.thisObject;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 1) return;
                        String functions = String.valueOf(param.args[0]);
                        if (!functions.contains("mass_storage")) return;
                        SolarContextBridge.log("UsbDeviceManager.setEnabledFunctions mass_storage");
                        Object ms = mountServiceRef;
                        if (ms == null) ms = resolveMountService();
                        if (ms != null) {
                            scheduleShareY2Volumes(ms, 1200L);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Capture UsbDeviceManager instance on any entry — needed for setCurrentFunctions sync (2026-07-05).
            XposedHookKit.hookAll(udmClass, "updateUsbState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    usbDeviceManagerRef = param.thisObject;
                }
            });

            SolarContextBridge.log("UsbMassStorageServerHooks UsbDeviceManager hooked");
        } catch (Throwable t) {
            SolarContextBridge.log("UsbDeviceManager hook skip: " + t.getClass().getSimpleName());
        }
    }

    private static Object resolveMountService() {
        try {
            Class<?> smClass = XposedHelpers.findClass("android.os.ServiceManager", null);
            Object binder = XposedHelpers.callStaticMethod(smClass, "getService", "mount");
            if (binder == null) return null;
            Class<?> stub = XposedHelpers.findClass("android.os.storage.IMountService$Stub", null);
            return XposedHelpers.callStaticMethod(stub, "asInterface", binder);
        } catch (Throwable t) {
            SolarContextBridge.log("resolveMountService failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static void scheduleShareY2Volumes(final Object mountService, long delayMs) {
        if (mountService == null) return;
        Runnable work = new Runnable() {
            @Override
            public void run() {
                boolean kernelUms = UsbMassStorageProbe.probeKernelMassStorageMode();
                boolean lunBound = UsbMassStorageProbe.probeLunBackingBound();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("kernelUms", kernelUms);
                    d.put("lunBound", lunBound);
                    d.put("usbConfig", UsbMassStorageProbe.readUsbConfig());
                    d.put("kernelFn", UsbMassStorageProbe.readKernelFunctions());
                    BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.scheduleShare",
                            "scheduled run", "H3,H5", d);
                } catch (Throwable ignored) {}
                // #endregion
                if (!kernelUms) return;
                if (lunBound) return;
                shareY2Volumes(mountService, true);
            }
        };
        if (mainHandler != null && delayMs > 0) {
            mainHandler.postDelayed(work, delayMs);
        } else {
            work.run();
        }
    }

    /** Share every Y2 volume vold reports mounted — skip missing internal when only MicroSD is listed. */
    private static void shareY2Volumes(Object mountService, boolean ensureUmsEnabled) {
        if (mountService == null) return;
        try {
            syncUsbDeviceManagerFunctions("mass_storage,adb");
            if (ensureUmsEnabled) {
                XposedHelpers.callMethod(mountService, "setUsbMassStorageEnabled", true);
            }
            for (String vol : resolveY2ExportVolumes()) {
                try {
                    XposedHelpers.callMethod(mountService, "shareVolume", vol);
                    SolarContextBridge.log("shareY2Volumes ok " + vol);
                } catch (Throwable t) {
                    SolarContextBridge.log("shareY2Volumes miss " + vol + ": "
                            + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            SolarContextBridge.log("shareY2Volumes failed: " + t.getClass().getSimpleName());
        }
    }

    /** Prefer vdc volume list paths; fall back to sdcard0 + sdcard1 candidates. */
    private static String[] resolveY2ExportVolumes() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        Process p = null;
        BufferedReader reader = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"vdc", "volume", "list"});
            reader = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.contains("Volumes listed")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3 && parts[2].startsWith("/storage/")) {
                    if (parts[2].contains("usbotg")) continue;
                    if (isY2BlockedExportPath(parts[2])) continue;
                    seen.add(parts[2]);
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {}
            }
            if (p != null) p.destroy();
        }
        if (seen.isEmpty()) {
            for (String candidate : Y2_EXPORT_CANDIDATES) {
                seen.add(candidate);
            }
        } else {
            for (String candidate : Y2_EXPORT_CANDIDATES) {
                if (new File(candidate).exists() && !isY2BlockedExportPath(candidate)) {
                    seen.add(candidate);
                }
            }
        }
        out.addAll(seen);
        if (out.isEmpty()) {
            out.add("/storage/sdcard1");
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("volumes", out.toString());
            BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.resolveY2ExportVolumes",
                    "resolved", "H4", d);
        } catch (Throwable ignored) {}
        // #endregion
        return out.toArray(new String[out.size()]);
    }

    /** vold Share disabled — resync to mass_storage,adb; never MTP mid-enable (2026-07-05). */
    private static void tryRecoverAndShare(Object mountService, String volumePath) {
        if (mountService == null) return;
        if (!UsbMassStorageProbe.probeKernelMassStorageMode()
                && !UsbMassStorageProbe.readUsbConfig().contains("mass_storage")) {
            return;
        }
        try {
            XposedHelpers.callMethod(mountService, "setUsbMassStorageEnabled", false);
            setUsbConfigProperty("mass_storage,adb");
            syncUsbDeviceManagerFunctions("mass_storage,adb");
            XposedHelpers.callMethod(mountService, "setUsbMassStorageEnabled", true);
            if (volumePath != null && volumePath.length() > 0) {
                XposedHelpers.callMethod(mountService, "shareVolume", volumePath);
            }
            shareY2Volumes(mountService, false);
        } catch (Throwable t) {
            SolarContextBridge.log("tryRecoverAndShare failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    /** Skip raw emulated/0 — Y2 UMS uses /storage/sdcard0 symlink + MicroSD (2026-07-05). */
    private static boolean isY2BlockedExportPath(String path) {
        if (path == null) return true;
        return "/storage/emulated/0".equals(path) || path.startsWith("/storage/emulated/0/");
    }

    /**
     * KitKat UsbDeviceManager sync — prop/kernel on mass_storage but Current Functions still mtp (2026-07-05).
     * Layman: tell the USB stack to actually use disk mode, not pretend MTP for the Mac.
     * Reversal: remove if UmsEnabler setprop path alone keeps framework aligned on Y2.
     */
    private static void syncUsbDeviceManagerFunctions(String functions) {
        if (functions == null) return;
        Object udm = usbDeviceManagerRef;
        if (udm == null) {
            udm = resolveUsbDeviceManagerInstance();
        }
        if (udm == null) {
            SolarContextBridge.log("syncUsbDeviceManager skip: no UsbDeviceManager ref");
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("functions", functions);
                d.put("usbConfig", UsbMassStorageProbe.readUsbConfig());
                BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.syncUdm", "skip no ref", "H4", d);
            } catch (Throwable ignored) {}
            // #endregion
            return;
        }
        try {
            String beforeFn = UsbMassStorageProbe.readUsbConfig();
            try {
                XposedHelpers.callMethod(udm, "setCurrentFunctions", functions);
                SolarContextBridge.log("syncUsbDeviceManager setCurrentFunctions " + functions);
                logSyncUdmResult(functions, beforeFn, "setCurrentFunctions");
                return;
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.callMethod(udm, "setEnabledFunctions", functions, false);
                SolarContextBridge.log("syncUsbDeviceManager setEnabledFunctions2 " + functions);
                logSyncUdmResult(functions, beforeFn, "setEnabledFunctions2");
                return;
            } catch (Throwable ignored) {}
            XposedHelpers.callMethod(udm, "setEnabledFunctions", functions, false, false);
            SolarContextBridge.log("syncUsbDeviceManager setEnabledFunctions3 " + functions);
            logSyncUdmResult(functions, beforeFn, "setEnabledFunctions3");
        } catch (Throwable t) {
            SolarContextBridge.log("syncUsbDeviceManager fail: " + t.getClass().getSimpleName());
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("err", t.getClass().getSimpleName());
                d.put("functions", functions);
                BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.syncUdm", "fail", "H4", d);
            } catch (Throwable ignored) {}
            // #endregion
        }
    }

    private static void logSyncUdmResult(String functions, String beforeFn, String method) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("method", method);
            d.put("requested", functions);
            d.put("usbBefore", beforeFn);
            d.put("usbAfter", UsbMassStorageProbe.readUsbConfig());
            d.put("kernelAfter", UsbMassStorageProbe.readKernelFunctions());
            BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.syncUdm", "ok", "H1,H4", d);
        } catch (Throwable ignored) {}
        // #endregion
    }

    /** Last resort — scan UsbService fields; MTK KitKat may rename mUsbDeviceManager (2026-07-05). */
    private static Object resolveUsbDeviceManagerInstance() {
        if (systemClassLoader == null) return null;
        try {
            Class<?> usbServiceClass = XposedHelpers.findClass(
                    "com.android.server.usb.UsbService", systemClassLoader);
            Class<?> udmClass = XposedHelpers.findClass(
                    "com.android.server.usb.UsbDeviceManager", systemClassLoader);
            Class<?> smClass = XposedHelpers.findClass("android.os.ServiceManager", null);
            Object binder = XposedHelpers.callStaticMethod(smClass, "getService", "usb");
            if (binder == null) {
                logResolveUdm("no usb binder", null);
                return null;
            }
            Object local = XposedHelpers.callMethod(binder, "queryLocalInterface",
                    "android.hardware.usb.IUsbManager");
            SolarContextBridge.log("resolveUdm localClass="
                    + (local != null ? local.getClass().getName() : "null"));
            if (local != null) {
                Object udm = scanUsbDeviceManagerField(local, udmClass);
                if (udm != null) {
                    usbDeviceManagerRef = udm;
                    logResolveUdm("scan local UsbService", udm);
                    return udm;
                }
            }
            // Binder may not expose local interface — unwrap via IUsbManager.Stub.asInterface + scan.
            try {
                Class<?> stub = XposedHelpers.findClass("android.hardware.usb.IUsbManager$Stub", null);
                Object iface = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
                if (iface != null) {
                    Object udm = scanUsbDeviceManagerField(iface, udmClass);
                    if (udm != null) {
                        usbDeviceManagerRef = udm;
                        logResolveUdm("scan IUsbManager impl", udm);
                        return udm;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logResolveUdm(t.getClass().getSimpleName(), null);
        }
        return null;
    }

    /** Walk declared fields — match by type or live instance class name (MTK obfuscation) (2026-07-05). */
    private static Object scanUsbDeviceManagerField(Object host, Class<?> udmClass) {
        if (host == null) return null;
        Class<?> c = host.getClass();
        while (c != null) {
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                java.lang.reflect.Field f = fields[i];
                try {
                    f.setAccessible(true);
                    Object val = f.get(host);
                    if (val == null) continue;
                    String cn = val.getClass().getName();
                    if (cn.indexOf("UsbDeviceManager") >= 0) {
                        SolarContextBridge.log("scanUdm hit " + c.getSimpleName() + "." + f.getName()
                                + " -> " + cn);
                        return val;
                    }
                    if (udmClass != null && udmClass.isInstance(val)) {
                        SolarContextBridge.log("scanUdm typed " + c.getSimpleName() + "." + f.getName());
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void logResolveUdm(String msg, Object udm) {
        SolarContextBridge.log("resolveUsbDeviceManager " + msg);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("result", msg);
            d.put("found", udm != null);
            d.put("usbConfig", UsbMassStorageProbe.readUsbConfig());
            d.put("kernelFn", UsbMassStorageProbe.readKernelFunctions());
            BridgeDebugA3510dLog.log("UsbMassStorageServerHooks.resolveUdm", msg, "H4", d);
        } catch (Throwable ignored) {}
        // #endregion
    }

    private static void setUsbConfigProperty(String config) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            XposedHelpers.callStaticMethod(sp, "set", "sys.usb.config", config);
        } catch (Throwable ignored) {}
    }

    /** Retry only on vold/MountService desync strings — not missing-volume errors. */
    private static boolean shouldRecoverShareFailure(String message) {
        if (message == null || message.length() == 0) return true;
        String lower = message.toLowerCase();
        if (lower.contains("share disabled")) return true;
        if (lower.contains("resource busy")) return true;
        if (lower.contains("device or resource busy")) return true;
        return lower.contains("not mounted");
    }
}
