package com.solar.launcher;

import android.content.Context;
import android.os.PowerManager;

import java.io.File;
import java.util.List;

/**
 * 2026-07-05 — Boot-time repair for required Xposed modules; respects user override file.
 * APK/ROM parity: runs 99XposedInit.sh chain; wizard owns reboot UX vs scheduleReboot() here.
 * When changing: XposedModuleRegistry required list; 99XposedInit.sh seeds; PowerMenuTest force-off.
 * Reversal: remove isUserDisabled checks — modules always forced on again every boot.
 */
public final class XposedModuleEnsurer {

    private static final String INIT_HOOK = "/system/etc/init.d/99XposedInit.sh";
    private static final String RUNTIME_JAR = XposedModuleStore.XPOSED_DATA + "/bin/XposedBridge.jar";
    private static final String PKG_POWERMENU_TEST = "com.solar.launcher.xposed.powermenu";
    /**
     * 2026-07-20 — Lab A/B: skip all Xposed self-heal when set (Solar-only perf check).
     * Layman: turns off “put Xposed back” so we can feel plain Solar speed.
     * Technical: persist.solar.lab.noxposed=1; adb: setprop + uninstall-xposed-adb.sh.
     * Reversal: setprop persist.solar.lab.noxposed 0; re-run install-xposed-adb.sh.
     */
    public static final String PROP_LAB_NO_XPOSED = "persist.solar.lab.noxposed";
    /** Min gap between resume-time repair passes — avoids root storms during wheel nav. */
    private static final long RESUME_ENSURE_MIN_MS = 120_000L;

    private static volatile boolean repairScheduled;
    private static volatile long lastResumeEnsureAt;

    private XposedModuleEnsurer() {}

    /**
     * 2026-07-20 — True when lab wants Solar without Xposed framework/modules.
     * Layman: performance A/B switch — no hooks in zygote.
     * Technical: SystemProperties persist.solar.lab.noxposed == 1.
     */
    public static boolean isLabNoXposed() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_LAB_NO_XPOSED, "0");
            return "1".equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Boot-time check — no-op without root; runs even before zygote hook so installer can seed framework. */
    public static void ensureRequiredModulesAsync(final Context ctx) {
        ensureRequiredModulesAsync(ctx, true);
    }

    /**
     * 2026-07-06 — Resume-time repair: register PM + enable prefs without surprise reboot.
     * Layman: fixes missing modules while you use Solar; reboot only when boot repair demands it.
     */
    public static void ensureRequiredModulesOnResume(final Context ctx) {
        if (ctx == null || !RootShell.canRun()) return;
        // 2026-07-20 — Lab Solar-only: never re-stage Xposed after uninstall-xposed-adb.sh.
        if (isLabNoXposed()) return;
        long now = System.currentTimeMillis();
        if (now - lastResumeEnsureAt < RESUME_ENSURE_MIN_MS) return;
        lastResumeEnsureAt = now;
        ensureRequiredModulesAsync(ctx, false);
    }

    private static void ensureRequiredModulesAsync(final Context ctx, final boolean allowReboot) {
        if (ctx == null || repairScheduled) return;
        if (!RootShell.canRun()) return;
        // 2026-07-20 — Same lab gate as onResume (boot bootstrap path).
        if (isLabNoXposed()) return;
        repairScheduled = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RepairResult result = repairModules(ctx.getApplicationContext(), allowReboot);
                    if (allowReboot && result.rebootRequired) {
                        scheduleReboot(ctx.getApplicationContext());
                    }
                } catch (Exception ignored) {
                } finally {
                    repairScheduled = false;
                }
            }
        }, "XposedModuleEnsurer").start();
    }

    /** True when XposedBridge runtime jar is seeded (framework active or pending reboot). */
    public static boolean isXposedPresent() {
        return new File(RUNTIME_JAR).isFile()
                || new File("/system/framework/XposedBridge.jar").isFile();
    }

    /** Packages Solar requires enabled — registry plus any installed Solar hook APKs. */
    public static List<String> requiredModulePackages(Context ctx) {
        if (ctx != null) {
            return XposedModuleCatalog.requiredPackages(ctx);
        }
        return XposedModuleRegistry.requiredModulePackages();
    }

    /** Packages Solar requires enabled — delegates to registry when no Context. */
    public static List<String> requiredModulePackages() {
        return XposedModuleRegistry.requiredModulePackages();
    }

    /** Test hook — repair without reboot side effects. */
    static RepairResult repairModulesForTest(Context ctx) {
        return repairModules(ctx, false);
    }

    /** True when ensurer would skip forced enable for this package. */
    static boolean shouldSkipForcedEnable(String pkg) {
        return XposedModuleStore.isUserDisabled(pkg);
    }

    /** Blocking repair — platform prep owns reboot UX when allowReboot false. */
    public static boolean repairModulesBlocking(Context ctx) {
        // 2026-07-20 — Lab Solar-only: prep ladder must not re-enable modules.
        if (isLabNoXposed()) return false;
        RepairResult r = repairModules(ctx, true);
        return r.rebootRequired;
    }

    private static RepairResult repairModules(Context ctx, boolean allowReboot) {
        RepairResult out = new RepairResult();
        if (!RootShell.canRun()) return out;
        if (isLabNoXposed()) return out;

        XposedModuleStore.bindResolveContext(ctx);
        try {
            com.solar.launcher.platform.PlatformPrepManifest manifest =
                    com.solar.launcher.platform.PlatformPrepManifest.load(ctx);
            com.solar.launcher.platform.XposedModuleInstaller.installRequiredModules(
                    ctx, manifest, true);
            com.solar.launcher.platform.XposedFrameworkInstaller.ensureInstallerPm(ctx, manifest);
        } catch (Exception ignored) {}

        registerBakedModulesWithPm(ctx, out);

        RootShell.run("test -f " + INIT_HOOK + " && sh " + INIT_HOOK + " || true");
        XposedModuleStore.fixInstallerOwnership();

        for (String pkg : requiredModulePackagesForRepair(ctx)) {
            if (XposedModuleStore.isUserDisabled(pkg)) {
                out.skippedUserDisabled.add(pkg);
                continue;
            }
            if (XposedModuleStore.isModuleEnabled(pkg)) continue;
            if (XposedModuleStore.setModuleEnabled(pkg)) {
                out.rebootRequired = true;
                out.enabled.add(pkg);
            }
        }
        if (XposedModuleStore.isModuleEnabled(PKG_POWERMENU_TEST)) {
            if (XposedModuleStore.setModuleDisabled(PKG_POWERMENU_TEST)) {
                out.rebootRequired = true;
                out.disabled.add(PKG_POWERMENU_TEST);
            }
        }
        if (out.rebootRequired) {
            XposedModuleStore.fixInstallerOwnership();
            SolarLog.i("XposedModuleEnsurer", "repaired modules enabled="
                    + out.enabled + " disabled=" + out.disabled
                    + " registered=" + out.registered
                    + " skippedUserDisabled=" + out.skippedUserDisabled
                    + " allowReboot=" + allowReboot);
        }
        if (!allowReboot) {
            out.rebootRequired = false;
        }
        return out;
    }

    private static void registerBakedModulesWithPm(Context ctx, RepairResult out) {
        if (out == null) return;
        for (String pkg : requiredModulePackagesForRepair(ctx)) {
            registerOneWithPm(ctx, out, pkg, XposedModuleRegistry.systemApkPathForPackage(pkg), null);
        }
        try {
            com.solar.launcher.platform.PlatformPrepManifest manifest =
                    com.solar.launcher.platform.PlatformPrepManifest.load(ctx);
            boolean y2 = DeviceFeatures.isY2();
            for (com.solar.launcher.platform.PlatformPrepManifest.ModuleEntry m
                    : manifest.requiredModulesForDevice(y2)) {
                registerOneWithPm(ctx, out, m.pkg, m.systemApk, m.asset);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 2026-07-06 — pm install -r when /system APK exists but PM never registered (KitKat gap).
     * Layman: teaches Android the module exists; falls back to bundled asset when /system empty.
     */
    private static void registerOneWithPm(Context ctx, RepairResult out, String pkg,
            String systemApk, String assetPath) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean registered = com.solar.launcher.platform.PlatformProbe.packageRegisteredInPm(pkg);
        boolean onData = registered
                && com.solar.launcher.platform.PlatformProbe.packageRegisteredOnDataApp(pkg);
        if (registered && !onData) return;

        if (systemApk != null && com.solar.launcher.platform.PlatformProbe.fileExists(systemApk)) {
            if (PmInstallPolicy.installSystemApp(systemApk, pkg)) {
                out.registered.add(pkg);
                SolarLog.i("XposedModuleEnsurer", "pm registered " + pkg + " from " + systemApk);
            }
            return;
        }
        if (ctx == null || assetPath == null) return;
        java.io.File apk = com.solar.launcher.platform.PlatformAssetExtractor.extractAsset(ctx, assetPath);
        if (apk == null || !apk.isFile()) return;
        if (systemApk != null) {
            String sh = ""
                    + "mount -o remount,rw /system 2>/dev/null; "
                    + "cp " + com.solar.launcher.platform.PlatformProbe.shellQuote(apk.getAbsolutePath())
                    + " " + com.solar.launcher.platform.PlatformProbe.shellQuote(systemApk)
                    + " && chmod 644 "
                    + com.solar.launcher.platform.PlatformProbe.shellQuote(systemApk) + " && sync";
            RootShell.run(sh);
            if (com.solar.launcher.platform.PlatformProbe.fileExists(systemApk)
                    && PmInstallPolicy.installSystemApp(systemApk, pkg)) {
                out.registered.add(pkg);
                SolarLog.i("XposedModuleEnsurer", "pm registered " + pkg + " staged to " + systemApk);
            }
            return;
        }
        if (PmInstallPolicy.installInternal(apk.getAbsolutePath())) {
            out.registered.add(pkg);
            SolarLog.i("XposedModuleEnsurer", "pm registered " + pkg + " from asset " + assetPath);
        }
    }

    /** Enable loop uses registry + installed catalog — catches modules that failed PM register. */
    private static java.util.List<String> requiredModulePackagesForRepair(Context ctx) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<String>();
        for (String pkg : XposedModuleRegistry.requiredModulePackages()) {
            all.add(pkg);
        }
        if (ctx != null) {
            for (String pkg : XposedModuleCatalog.requiredPackages(ctx)) {
                all.add(pkg);
            }
        }
        return new java.util.ArrayList<String>(all);
    }

    private static void scheduleReboot(Context ctx) {
        SolarLog.i("XposedModuleEnsurer", "modules need reboot — showing user reboot wizard");
        com.solar.launcher.platform.PlatformPrepState.setRebootPending(ctx, true);
        com.solar.launcher.platform.PlatformPrepLauncher.launchRebootWizard(ctx);
    }

    static final class RepairResult {
        boolean rebootRequired;
        final java.util.List<String> enabled = new java.util.ArrayList<String>();
        final java.util.List<String> disabled = new java.util.ArrayList<String>();
        final java.util.List<String> registered = new java.util.ArrayList<String>();
        final java.util.List<String> skippedUserDisabled = new java.util.ArrayList<String>();
    }
}
