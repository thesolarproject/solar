package com.solar.launcher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-05 — Root UMS toggle; Y1 and Y2 use setprop+vdc shell scripts (not MountService IPC).
 * When changing: solar-enable/disable-ums.sh assets + ROM /system/etc/solar/ staging for both families.
 * Reversal: restore MountService app_process path if a future ROM fixes stability.
 */
public final class UsbMassStorageController {

    private static final String ENABLE_ASSET = "y1/solar-enable-ums.sh";
    private static final String DISABLE_ASSET = "y1/solar-disable-ums.sh";
    private static final String ENABLE_SYSTEM = "/system/etc/solar/solar-enable-ums.sh";
    private static final String DISABLE_SYSTEM = "/system/etc/solar/solar-disable-ums.sh";
    private static final String ENABLE_SYSTEM_LEGACY = "/system/etc/solar/y1-enable-ums.sh";
    private static final String DISABLE_SYSTEM_LEGACY = "/system/etc/solar/y1-disable-ums.sh";
    private static final String ENABLE_DATA = "/data/data/solar-enable-ums.sh";
    private static final String DISABLE_DATA = "/data/data/solar-disable-ums.sh";

    /**
     * 2026-07-15 — User armed UMS this host session (Turn on / auto-connect).
     * Layman: after you choose disk mode, do not silently tear it down.
     * Tech: sysprop + volatile so USB re-enum disconnects skip {@code disableIfExported}.
     * Reversal: clear callers; Unauthorized guard may treat mid-enable mass_storage as stale again.
     */
    public static final String SYSPROP_USER_SESSION = "sys.solar.ums.session";
    private static volatile boolean sUserSessionActive = false;
    private static volatile long sEnableArmedAtElapsedMs = 0L;

    /**
     * 2026-07-17 — TTL for kernel/LUN probes. Hot paths (changeScreen / BACK) used to hit
     * sysfs every call after UMS unplug while the volume remounted → jank for new users.
     * Invalidate on enable/disable/teardown so UI still sees a fresh state after toggle.
     */
    private static final long UMS_PROBE_TTL_MS = 750L;
    private static volatile long sProbeCacheAtMs = 0L;
    private static volatile boolean sCachedKernelUms = false;
    private static volatile boolean sCachedLunBound = false;
    private static volatile boolean sCachedKernelValid = false;
    private static volatile boolean sCachedLunValid = false;
    /** Coalesce concurrent confirmed-unplug teardowns (FocusHelper + WakeReceiver). */
    private static final java.util.concurrent.atomic.AtomicBoolean sTeardownInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private UsbMassStorageController() {}

    /** Drop probe TTL — call after enable/disable/session clear. */
    public static void invalidateProbeCache() {
        sProbeCacheAtMs = 0L;
        sCachedKernelValid = false;
        sCachedLunValid = false;
    }

    /** Enable USB mass storage (verified LUN bind) as root — Y2 gated on experiment (2026-07-05). */
    public static boolean enable(Context context) {
        return enable(context, "unknown");
    }

    /**
     * 2026-07-06 — UMS only with user consent or explicit Settings → Auto-Connect.
     * Callers starting with {@code user.} are manual UI confirms; {@code auto.} needs auto-connect pref.
     */
    public static boolean enable(Context context, String caller) {
        if (!UsbMassStorageExperiment.isEnabled(context)) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("caller", caller);
                d.put("a5", DeviceFeatures.isA5());
                d.put("y2", DeviceFeatures.isY2());
                Debug1fc727Log.log(context, "UsbMassStorageController.enable",
                        "experiment off", "H4", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        if (!isEnablePermitted(context, caller)) {
            logEnableDenied(context, caller);
            // #region agent log
            try {
                org.json.JSONObject d = Debug266f21Log.usbSnapshot();
                d.put("caller", caller);
                d.put("autoConnect", context != null && UsbStorageSessionFlags.isAutoConnectEnabled(context));
                Debug266f21Log.log(context, "UsbMassStorageController.enable", "denied", "H1,H4", d);
                Debug1fc727Log.log(context, "UsbMassStorageController.enable", "denied", "H4", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        logEnablePermitted(context, caller);
        // #region agent log
        try {
            org.json.JSONObject d = Debug266f21Log.usbSnapshot();
            d.put("caller", caller);
            d.put("autoConnect", context != null && UsbStorageSessionFlags.isAutoConnectEnabled(context));
            d.put("a5", DeviceFeatures.isA5());
            d.put("rootCanRun", DeviceFeatures.canRunRootShell());
            Debug266f21Log.log(context, "UsbMassStorageController.enable", "permitted", "H1,H4", d);
            Debug1fc727Log.log(context, "UsbMassStorageController.enable", "permitted→toggle", "H1,H3", d);
        } catch (Exception ignored) {}
        // #endregion
        // 2026-07-15 — Arm before setprop so USB_STATE re-enum cannot tear LUN mid-enable.
        markUserSessionActive();
        boolean ok = runUmsToggle(context, true);
        if (!ok) {
            // Failed enable — drop session so Unauthorized can still clear stuck mass_storage.
            clearUserSession();
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("caller", caller);
            d.put("ok", ok);
            d.put("exported", isMassStorageExported());
            d.put("kernelUms", isKernelMassStorageMode());
            d.put("usbConfig", readSysUsbConfig());
            d.put("a5", DeviceFeatures.isA5());
            d.put("userSession", isUserSessionActive());
            Debug1fc727Log.log(context, "UsbMassStorageController.enable", "toggle done", "H1,H3", d);
        } catch (Exception ignored) {}
        // #endregion
        return ok;
    }

    /**
     * 2026-07-20 — After confirmed unplug, ignore residual sysfs export for Solar eject UI.
     * Layman: unplug must not bounce the “USB storage on” screen while the kernel finishes.
     * Tech: disable-ums re-enum leaves mass_storage briefly; changeScreen used to re-lock.
     * Reversal: clear flag helpers; restore live {@code isMassStorageExported} as always UI-active.
     */
    private static volatile boolean sIgnoreExportForUiAfterUnplug = false;

    /**
     * 2026-07-15 — Remember user asked for disk mode (survives USB re-enum disconnect blip).
     * Layman: Turn on sticks until Turn Off or real unplug.
     */
    public static void markUserSessionActive() {
        // Fresh Turn on / auto-connect owns export again — stop ignoring residual unplug state.
        sIgnoreExportForUiAfterUnplug = false;
        sUserSessionActive = true;
        sEnableArmedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
        writeSysprop(SYSPROP_USER_SESSION, "1");
        invalidateProbeCache();
    }

    /** Drop user UMS session — Turn Off, sustained unplug, or failed enable. */
    public static void clearUserSession() {
        sUserSessionActive = false;
        sEnableArmedAtElapsedMs = 0L;
        writeSysprop(SYSPROP_USER_SESSION, "0");
        invalidateProbeCache();
    }

    /**
     * 2026-07-20 — Confirmed cable out: do not re-paint eject UI from leftover LUN/export.
     * Layman: after unplug, menus stay usable while disk mode shuts down in the background.
     */
    public static void markExportUiIgnoredAfterUnplug() {
        sIgnoreExportForUiAfterUnplug = true;
    }

    /** Host reconnect / tests — allow export probes to drive eject UI again. */
    public static void clearExportUiIgnoreAfterUnplug() {
        sIgnoreExportForUiAfterUnplug = false;
    }

    /** True while residual kernel UMS must not force Solar’s USB lock screen. */
    public static boolean shouldIgnoreExportForUi() {
        return sIgnoreExportForUiAfterUnplug;
    }

    /**
     * 2026-07-20 — Whether Solar should treat UMS as locking menus / eject screen.
     * Layman: only Solar’s Turn on / eject screen blocks menus; Android owns the stock USB dialog.
     * Tech: never lock from kernel export alone; discharging forces inactive (cable power gone).
     * Was: cached/live export could re-lock after unplug. Reversal: restore export|| branch.
     *
     * @param discharging battery not charging/full — USB host power is gone
     */
    static boolean isUiActiveForTest(boolean locked, boolean onUsbScreen, boolean userSession,
            boolean cachedExported, boolean liveExported, boolean ignoreExportAfterUnplug,
            boolean discharging) {
        // Discharging = not on USB power → Solar must never keep the eject wall up.
        if (discharging) return false;
        // Solar-owned disk mode only (Turn on / auto-connect arm / painted eject screen).
        if (locked || onUsbScreen || userSession) return true;
        // ignoreExportAfterUnplug kept for call-site clarity; export alone never locks.
        if (ignoreExportAfterUnplug) return false;
        // Android SystemUI owns residual mass_storage — do not steal the UI.
        return false;
    }

    /** Overload without discharging — assumes still possibly on USB power (tests / hot paths). */
    static boolean isUiActiveForTest(boolean locked, boolean onUsbScreen, boolean userSession,
            boolean cachedExported, boolean liveExported, boolean ignoreExportAfterUnplug) {
        return isUiActiveForTest(locked, onUsbScreen, userSession, cachedExported, liveExported,
                ignoreExportAfterUnplug, false);
    }

    /**
     * True when user/auto armed UMS so transient USB_STATE disconnect must not disable.
     * Layman: we are intentionally in (or entering) disk mode.
     */
    public static boolean isUserSessionActive() {
        if (sUserSessionActive) return true;
        return "1".equals(readSysUsbConfigProp(SYSPROP_USER_SESSION));
    }

    /** Test hook — grace window without Android clock (2026-07-15). */
    static boolean isWithinDisconnectGraceForTest(boolean sessionActive,
            long armedAtElapsedMs, long nowElapsedMs, long graceMs) {
        if (!sessionActive || graceMs <= 0L) return false;
        if (armedAtElapsedMs <= 0L) return false;
        return (nowElapsedMs - armedAtElapsedMs) < graceMs;
    }

    /** Grace after arm/enable — disconnects inside this window are USB re-enum, not unplug. */
    public static final long DISCONNECT_REENUM_GRACE_MS = 4000L;
    /**
     * 2026-07-16 — How long {@code connected=false} must stick before we treat cable as unplugged.
     * Layman: brief USB blips while entering disk mode must not turn disk mode off or clear the eject screen.
     * Was: 2s — Y1 mass_storage re-enum often stays disconnected longer; teardown cleared the eject UI
     * and ran disable before the host finished enumerating the disk.
     * Reversal: 2000L if real unplug feels sluggish on a future ROM with faster re-enum.
     */
    public static final long DISCONNECT_CONFIRM_MS = 10000L;

    /**
     * True when a disconnect should skip {@code disableIfExported} (re-enum / whole armed session).
     * 2026-07-16 — Was: only 4s after arm. That let mid-session re-enums tear UMS down and unlock UI.
     * Layman: once the user turns on storage, keep disks + eject screen until a real cable unplug.
     * Reversal: restore age &lt; DISCONNECT_REENUM_GRACE_MS check if host eject must free LUNs early.
     */
    public static boolean shouldIgnoreDisconnectDisable() {
        return isUserSessionActive();
    }

    /**
     * 2026-07-16 — True while Turn on / auto-connect owns the USB cable session.
     * Layman: do not clear the blocking eject screen on a one-frame "unplug" during re-enum.
     */
    public static boolean shouldDeferDisconnectTeardown() {
        return isUserSessionActive();
    }

    /**
     * 2026-07-16 — Confirmed cable unplug: drop session then tear down kernel UMS.
     * 2026-07-19 — If Solar never armed UMS and Android owns USB, leave kernel alone (SystemUI path).
     * Layman: only Solar’s Enable Now / Auto-Connect disk mode is torn down by Solar on unplug.
     */
    public static boolean teardownAfterConfirmedUnplug(Context context) {
        // FocusHelper + manifest WakeReceiver both confirm unplug — one disable script is enough.
        if (!sTeardownInFlight.compareAndSet(false, true)) {
            return true;
        }
        try {
            boolean solarOwned = isUserSessionActive();
            // 2026-07-20 — Arm before clear so MainActivity cannot re-lock from residual export.
            markExportUiIgnoredAfterUnplug();
            clearUserSession();
            if (context == null) return false;
            // Stock SystemUI may have shared storage — do not su-disable on every unplug blip.
            if (!solarOwned && UsbStorageSessionFlags.preferStockUsbUi(context)) {
                return true;
            }
            return disableIfExported(context, true);
        } finally {
            sTeardownInFlight.set(false);
            invalidateProbeCache();
        }
    }

    /** User tapped Turn on / overlay confirm, or auto-connect pref is explicitly on. */
    private static boolean isEnablePermitted(Context context, String caller) {
        if (caller != null && caller.startsWith("user.")) return true;
        if (context != null && UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            return caller != null && caller.startsWith("auto.");
        }
        return false;
    }

    private static void logEnablePermitted(Context context, String caller) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("caller", caller);
            d.put("autoConnect", context != null && UsbStorageSessionFlags.isAutoConnectEnabled(context));
            d.put("usbConfig", readSysUsbConfig());
            DebugAf054eLog.log(context, "UsbMassStorageController.enable",
                    "enable permitted", "USB-CONSENT", d);
        } catch (Exception ignored) {}
    }

    private static void logEnableDenied(Context context, String caller) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("caller", caller);
            d.put("autoConnect", context != null && UsbStorageSessionFlags.isAutoConnectEnabled(context));
            d.put("usbConfig", readSysUsbConfig());
            DebugAf054eLog.log(context, "UsbMassStorageController.enable",
                    "enable denied — no consent", "USB-CONSENT", d);
        } catch (Exception ignored) {}
    }

    /** Disable USB mass storage and return to MTP+adb — always allowed so Y2 can recover stock MTP. */
    public static boolean disable(Context context) {
        if (context == null) return false;
        // 2026-07-16 — Also clear orphan LUN binds when kernel already left mass_storage mode.
        // Layman: host must not keep seeing a disk after disk mode is "off".
        if (!isKernelMassStorageMode()) {
            if (probeLunBackingBoundCached()) {
                // Async LUN clear — caller may be main thread after a race; never block on su.
                RootShell.runAsync("for f in /sys/class/android_usb/android0/f_mass_storage/lun/file "
                        + "/sys/class/android_usb/android0/f_mass_storage/lun0/file "
                        + "/sys/class/android_usb/android0/f_mass_storage/lun1/file; do "
                        + "[ -e \"$f\" ] && echo >\"$f\"; done");
            }
            clearUserSession();
            clearStickyMassStoragePersist();
            // Volumes may already be Idle-Unmounted after a prior unshare without remount.
            remountExportVolumesAsync();
            return true;
        }
        boolean ok = runUmsToggle(context, false);
        clearUserSession();
        clearStickyMassStoragePersist();
        // Script + command append remount; this covers async races and stale /system scripts.
        remountExportVolumesAsync();
        return ok;
    }

    /**
     * 2026-07-17 — After UMS unshare, volumes sit Idle-Unmounted until explicit vdc mount.
     * Layman: turning disk mode off must put Music back on the player.
     * Tech: never block the UI thread — RootShell async; MEDIA_MOUNTED will refresh library.
     */
    static void remountExportVolumesAsync() {
        RootShell.runAsync(
                "sleep 0.2; "
                        + "vdc volume mount /storage/sdcard0 2>/dev/null; "
                        + "vdc volume mount /storage/sdcard1 2>/dev/null");
    }

    /**
     * Y2 product is MTP-only — force mtp,adb if a prior session left mass_storage (2026-07-10).
     * Layman: Y2 always does normal phone-to-PC file transfer, never disk mode.
     * Tech: boot + experiment sync call {@link #disable} when kernel still lists mass_storage.
     * Reversal: gate again if Y2 UMS is productized later.
     */
    public static void ensureStockMtpWhenExperimentOff(Context context) {
        if (context == null || !DeviceFeatures.isY2()) return;
        if (!isKernelMassStorageMode() && !probeLunBackingBound()) return;
        disable(context);
    }

    /**
     * 2026-07-16 — Boot: clear Solar-armed UMS session leftovers.
     * 2026-07-19 — When Android owns USB (no Auto-Connect), do not disable kernel UMS or rewrite
     * persist.sys.usb.config — that fought SystemUI and stalled Y1 startups while PC-tethered.
     * Layman: reboot with a PC cable → Android can show its USB dialog; Solar stays out.
     * Tech: stock path = clearUserSession only; Auto-Connect path leaves kernel for later enable.
     * Reversal: restore clearSticky + disable for all non-auto boots.
     */
    public static void ensureNoStickyAutoUmsOnBoot(Context context) {
        if (context == null) return;
        clearUserSession();
        if (UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            // Auto-connect may re-enable after home is ready — leave kernel alone here.
            return;
        }
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) {
            // Android / SystemUI owns mass storage — no su disable storm on cold start.
            return;
        }
        // Legacy Solar-owned prompt path only.
        clearStickyMassStoragePersist();
        if (isKernelMassStorageMode() || probeLunBackingBound()) {
            disable(context);
            clearStickyMassStoragePersist();
        }
    }

    /**
     * 2026-07-16 — Drop mass_storage from persist.sys.usb.config (and property file).
     * Layman: forget “always show as a USB disk” so the next cable connect is charge/adb only.
     * Call from boot / disable only — never mid-enable (persist must stay mass_storage while exported).
     */
    public static void clearStickyMassStoragePersist() {
        // Do not clear while user has armed disk mode — re-enum would snap back to adb.
        if (isUserSessionActive()) return;
        String safe = DeviceFeatures.isY2() ? "mtp,adb" : "adb";
        String persist = readSysUsbConfigProp("persist.sys.usb.config");
        if (persist != null && persist.contains("mass_storage")) {
            writeSysprop("persist.sys.usb.config", safe);
            // Async only — never block UI/unplug path on su + property file write.
            RootShell.runAsync("setprop persist.sys.usb.config " + safe
                    + "; if [ -d /data/property ]; then echo -n " + safe
                    + " > /data/property/persist.sys.usb.config; chmod 600 "
                    + "/data/property/persist.sys.usb.config; fi");
        }
    }

    /**
     * Cable unplug teardown — drop kernel UMS when LUN is still bound (2026-07-05).
     * Layman: unplugging the cable should turn off disk mode so the next plug asks again.
     * Tech: idempotent no-op when sysfs LUN nodes are already empty.
     * 2026-07-16 — No-op while user session armed (re-enum blips); use
     * {@link #teardownAfterConfirmedUnplug} or {@link #disableIfExported(Context, boolean)} force.
     * Reversal: remove callers and rely on stock UsbDeviceManager disconnect if regressions appear.
     */
    public static boolean disableIfExported(Context context) {
        return disableIfExported(context, false);
    }

    /**
     * @param force when true, ignore armed user session (confirmed unplug / stale recovery only).
     */
    public static boolean disableIfExported(Context context, boolean force) {
        if (context == null) return false;
        if (!force && shouldIgnoreDisconnectDisable()) {
            return false;
        }
        if (!isKernelMassStorageMode() && !probeLunBackingBound()) return true;
        return disable(context);
    }

    /**
     * True when USB is in mass_storage mode and a LUN is bound (2026-07-05).
     * Layman: PC can see the SD as a disk — not just a stale sysfs path after MTP restore.
     * Tech: requires {@code sys.usb.config}/kernel functions mass_storage plus non-empty lun/file.
     * Reversal: revert to lun-only probe if a future ROM clears lun nodes synchronously on disable.
     */
    public static boolean isMassStorageExported() {
        if (!isKernelMassStorageMode()) return false;
        return probeLunBackingBoundCached();
    }

    /** Test hook — kernel USB mode from config + functions strings without sysfs. */
    static boolean isKernelMassStorageModeFromStrings(String usbConfig, String kernelFunctions) {
        if (usbConfig != null && usbConfig.contains("mass_storage")) return true;
        return kernelFunctions != null && kernelFunctions.contains("mass_storage");
    }

    /** Test hook — full export state without device I/O. */
    static boolean isMassStorageActiveState(String usbConfig, String kernelFunctions,
            boolean lunBackingBound) {
        return isKernelMassStorageModeFromStrings(usbConfig, kernelFunctions) && lunBackingBound;
    }

    /** Build the legacy Y2 UmsEnabler su command — test hook only. */
    static String buildUmsCommand(String apkPath, boolean enable, String volumePath) {
        if (apkPath == null || apkPath.length() == 0) return "";
        String escaped = apkPath.replace("'", "'\\''");
        StringBuilder cmd = new StringBuilder();
        cmd.append("export CLASSPATH='").append(escaped).append("'");
        cmd.append(" && app_process /system/bin com.solar.launcher.UmsEnabler ");
        cmd.append(enable ? "1" : "0");
        if (volumePath != null && volumePath.length() > 0) {
            cmd.append(" '").append(volumePath.replace("'", "'\\''")).append("'");
        }
        return cmd.toString();
    }

    /**
     * Shell UMS toggle command — test hook without running root shell.
     * 2026-07-17 — Disable always appends volume remount. Stale /system solar-disable-ums.sh
     * often only unshares (Idle-Unmounted) so All Songs goes empty until reboot.
     */
    static String buildUmsShellCommand(String scriptPath, boolean enable) {
        if (scriptPath == null || scriptPath.length() == 0) return "";
        String base = "sh " + shellQuote(scriptPath);
        if (!enable) {
            base += "; sleep 0.2"
                    + "; vdc volume mount /storage/sdcard0 2>/dev/null"
                    + "; vdc volume mount /storage/sdcard1 2>/dev/null";
        }
        return base;
    }

    /** Legacy alias for Y1 shell command tests. */
    static String buildY1UmsShellCommand(String scriptPath, boolean enable, String volumePath) {
        return buildUmsShellCommand(scriptPath, enable);
    }

    /**
     * Y1/Y2 both use solar-enable/disable shell scripts (2026-07-05).
     * 2026-07-08 — Do not dismiss global overlay here.
     * Was: startService DISMISS_OVERLAY on every toggle — tore down USB lock as soon as UMS enabled.
     * Now: callers dismiss/morph (lock holds keys until Turn Off or unplug).
     * Reversal: restore companion dismiss Intent before script if lock-tier paint regresses.
     */
    private static boolean runUmsToggle(Context context, boolean enable) {
        if (context == null) return false;
        String script = resolveUmsScript(context, enable);
        if (script == null) return false;
        String cmd = buildUmsShellCommand(script, enable);
        if (cmd.length() == 0) return false;
        logUmsAttempt(enable, script);
        boolean rootOk = RootShell.run(cmd);
        invalidateProbeCache();
        boolean result;
        if (!enable) {
            result = rootOk && !isKernelMassStorageMode();
        } else {
            // Enable script exits non-zero unless LUN bind succeeded — trust root exit (2026-07-05).
            result = rootOk;
        }
        logUmsResult(enable, rootOk, result);
        return result;
    }

    /** Resolve baked, cached, or asset-extracted UMS script path. */
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

    /** Copy one asset shell helper to cache for su execution. */
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
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {}
        }
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    // #region agent log
    private static void logUmsAttempt(boolean enable, String script) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("enable", enable);
            d.put("script", script);
            d.put("y1", DeviceFeatures.isY1());
            d.put("y2", DeviceFeatures.isY2());
            d.put("exportVolumes", joinPaths(DeviceFeatures.getUmsExportVolumePaths()));
            d.put("usbConfigBefore", readSysUsbConfig());
            DebugB6af9fLog.log("UsbMassStorageController.runUmsToggle", "before root", "E", d);
            DebugA3510dLog.log("UsbMassStorageController.runUmsToggle", "before root", "H2", d);
            Debug705932Log.log("UsbMassStorageController.runUmsToggle", "before root", "H2", d);
        } catch (Exception ignored) {}
    }

    private static void logUmsResult(boolean enable, boolean rootOk, boolean result) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("enable", enable);
            d.put("rootOk", rootOk);
            d.put("result", result);
            d.put("lunBound", probeLunBackingBound());
            d.put("kernelUms", isKernelMassStorageMode());
            d.put("usbConfigAfter", readSysUsbConfig());
            DebugB6af9fLog.log("UsbMassStorageController.runUmsToggle", "after root", "E", d);
            DebugA3510dLog.log("UsbMassStorageController.runUmsToggle", "after root", "H2,H3", d);
            Debug705932Log.log("UsbMassStorageController.runUmsToggle", "after root", "H2,H3", d);
        } catch (Exception ignored) {}
    }

    private static String joinPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) out.append(',');
            out.append(paths.get(i));
        }
        return out.toString();
    }
    // #endregion

    /** True when {@code sys.usb.config} or kernel functions include mass_storage (2026-07-05). */
    static boolean isKernelMassStorageMode() {
        long now = probeNowMs();
        if (sCachedKernelValid && (now - sProbeCacheAtMs) < UMS_PROBE_TTL_MS) {
            return sCachedKernelUms;
        }
        boolean ums = probeKernelMassStorageModeLive();
        sCachedKernelUms = ums;
        sCachedKernelValid = true;
        sProbeCacheAtMs = now;
        return ums;
    }

    private static boolean probeKernelMassStorageModeLive() {
        String config = readSysUsbConfig();
        if (config.contains("mass_storage")) return true;
        return readSysfsFirstLine("/sys/class/android_usb/android0/functions").contains("mass_storage");
    }

    private static boolean probeLunBackingBoundCached() {
        long now = probeNowMs();
        if (sCachedLunValid && (now - sProbeCacheAtMs) < UMS_PROBE_TTL_MS) {
            return sCachedLunBound;
        }
        boolean bound = probeLunBackingBound();
        sCachedLunBound = bound;
        sCachedLunValid = true;
        sProbeCacheAtMs = now;
        return bound;
    }

    /** Wall clock for TTL — works on device and host unit tests (no SystemClock). */
    private static long probeNowMs() {
        return System.currentTimeMillis();
    }

    /** Read {@code sys.usb.config} via SystemProperties (no getprop fork). */
    private static String readSysUsbConfig() {
        return readSysUsbConfigProp("sys.usb.config");
    }

    /** Read any sysprop via reflection; empty on miss. */
    private static String readSysUsbConfigProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v != null ? v.toString().trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Best-effort SystemProperties.set (A5/Y1 without su for short flags). */
    private static void writeSysprop(String key, String val) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, val);
            return;
        } catch (Throwable ignored) {}
        try {
            // Host unit tests have no Looper for RootShellAsync — never throw into callers.
            RootShell.runAsync("setprop " + key + " " + val);
        } catch (Throwable ignored) {}
    }

    private static String readSysfsFirstLine(String path) {
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

    /** Non-empty LUN backing path on any mass-storage lun node. */
    private static boolean probeLunBackingBound() {
        String[] paths = new String[]{
                "/sys/class/android_usb/android0/f_mass_storage/lun/file",
                "/sys/class/android_usb/android0/f_mass_storage/lun0/file",
                "/sys/class/android_usb/android0/f_mass_storage/lun1/file"
        };
        for (String path : paths) {
            File file = new File(path);
            if (!file.exists() || !file.canRead()) continue;
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && line.trim().length() > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }
}
