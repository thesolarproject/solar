package com.solar.launcher;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import com.solar.launcher.service.ProcessManagerService;

import com.solar.launcher.net.TlsHelper;

import com.solar.ota.net.OtaTlsHelper;
import com.solar.launcher.overlay.OverlayThemeProvider;
import com.solar.launcher.theme.AppOverlayThemeAdapter;
import com.solar.launcher.theme.ThemeManager;

import com.solar.launcher.soulseek.SolarDiagnosticReporter;

import com.solar.launcher.platform.PlatformPrepLauncher;

/**
 * 2026-07-05 — Application bootstrap: prep wizard gate, parallel platform repair, overlay process split.
 * APK/ROM parity: wizard blocks UX when gaps detected; background thread runs Y1RomPrep + ensurer always.
 * Bootstrap order: prep wizard (if required) → overlay hosts → background Y1RomPrep + XposedModuleEnsurer.
 * Mutex: overlay :overlay process skips HOME/bootstrap that could steal Rockbox focus.
 * When changing: SolarPlatformPrep ladder; keep heavy I/O off main thread.
 * Reversal: remove PlatformPrepLauncher call; remove bootstrap thread platform repair steps.
 */
public class SolarApplication extends Application {
    private static volatile Application sApp;

    /** Application context for background helpers (diag pings, etc.). */
    public static android.content.Context getAppContext() {
        return sApp;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        // #region agent log
        // 2026-07-20 — Tag wheel samples with family + short serial so 3-device A/B is readable.
        try {
            String serial = android.os.Build.SERIAL;
            if (serial == null) serial = "?";
            String shortSerial = serial.length() > 6 ? serial.substring(serial.length() - 6) : serial;
            String fam = DeviceFeatures.isY2() ? "Y2" : (DeviceFeatures.isA5() ? "A5" : "Y1");
            String tag = fam + "-" + shortSerial;
            DebugFb1dc1Log.setDeviceTag(tag);
            // #region agent log
            // 2026-07-20 — a177c4 scroll hunt: same device tag + poison-logger snapshot.
            DebugA177c4Log.setDeviceTag(tag);
            DebugA177c4Log.logPoisonFlags();
            // 2026-07-20 — 0705ff large-lib dial hunt: residency + applyMs vs empty lib.
            Debug0705ffLog.setDeviceTag(tag);
            Debug0705ffLog.logPoisonFlags();
            // 2026-07-20 — 9d0a7a: long-list hop / refresh (segment notify + section index).
            Debug9d0a7aLog.setDeviceTag(tag);
            Debug9d0a7aLog.logBoot();
            // #endregion
        } catch (Throwable ignored) {}
        // #endregion
        // 2026-07-16/20 — RAM gate + real MemoryRelease eviction ladder.
        // Reversal: remove LowMemoryGate + MemoryRelease; drop onTrimMemory/onLowMemory overrides.
        try {
            LowMemoryGate.init(this);
            LowMemoryGate.noteProcessStart();
            // Warm solar-audio looper early so solo fades never wait on first get(). 2026-07-20
            try {
                com.solar.launcher.audio.SolarTransport.get();
            } catch (Throwable ignored) {}
            registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    LowMemoryGate.onSystemTrim(level);
                    // 2026-07-20 — Free rebuildable caches (was pressure-gen only).
                    try {
                        MemoryRelease.release(level);
                    } catch (Throwable ignored) {}
                    if (level >= TRIM_MEMORY_RUNNING_LOW
                            || level == TRIM_MEMORY_MODERATE
                            || level == TRIM_MEMORY_COMPLETE) {
                        try {
                            com.solar.launcher.diag.SolarDiagFeatureLog.warn("app",
                                    "onTrimMemory level=" + level + " "
                                            + LowMemoryGate.snapshotOneLine(SolarApplication.this));
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onConfigurationChanged(Configuration newConfig) {}

                @Override
                public void onLowMemory() {
                    LowMemoryGate.onLowMemory();
                    try {
                        MemoryRelease.release(TRIM_MEMORY_COMPLETE);
                    } catch (Throwable ignored) {}
                    try {
                        com.solar.launcher.diag.SolarDiagFeatureLog.warn("app",
                                "onLowMemory " + LowMemoryGate.snapshotOneLine(SolarApplication.this));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        // 2026-07-16 — Auto internet clock (rooted): brief Wi‑Fi wake if needed, restore radio.
        // Geo soft-defaults (TZ / locale / podcast / YouTube region) run on online after wake.
        if (!isOverlayProcess()) {
            try {
                SolarAutoTime.onProcessStart(this);
            } catch (Throwable ignored) {}
            try {
                SolarGeoRegion.onInternetAvailable(this);
            } catch (Throwable ignored) {}
        }
        // #region agent log
        try {
            boolean a5 = DeviceFeatures.isA5();
            String prop = "";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Object v = sp.getMethod("get", String.class, String.class)
                        .invoke(null, DeviceFeatures.PROP_DEVICE_FAMILY, "");
                prop = v != null ? String.valueOf(v) : "";
            } catch (Throwable ignoredProp) {}
            android.util.Log.e("SolarDebugB4208e", "Application.onCreate isA5=" + a5
                    + " isY1=" + DeviceFeatures.isY1()
                    + " prop=" + prop
                    + " overlay=" + isOverlayProcess());
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("isA5", a5);
            d.put("isY1", DeviceFeatures.isY1());
            d.put("propValue", prop);
            d.put("overlayProcess", isOverlayProcess());
            DebugB4208eLog.log("SolarApplication.onCreate", "boot family probe", "A,F", d);
            // #region agent log
            org.json.JSONObject boot = new org.json.JSONObject();
            boot.put("isY1", DeviceFeatures.isY1());
            boot.put("isY2", DeviceFeatures.isY2());
            boot.put("overlayProcess", isOverlayProcess());
            boot.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
            DebugA1f293Log.log(this, "SolarApplication.onCreate", "app boot", "A,D", boot);
            // #endregion
        } catch (Throwable ignored) {}
        // #endregion
        // 2026-07-14 — A5: lock system rotation to natural portrait before heavy Application I/O.
        // Was: waited for Activity.onCreate (never reached — App.onCreate stalls on theme sync).
        // Now: Settings USER_ROTATION=0 + accel off while panel init is 240×320.
        // Reversal: delete this block; restore Activity-only LandscapeOrientationGuard.
        if (!isOverlayProcess() && DeviceFeatures.isA5()) {
            try {
                android.provider.Settings.System.putInt(getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
                android.provider.Settings.System.putInt(getContentResolver(),
                        android.provider.Settings.System.USER_ROTATION, 0);
                android.util.Log.e("SolarDebugB4208e", "A5 system rotation locked to natural(0)");
            } catch (Throwable t) {
                RootShell.run("settings put system accelerometer_rotation 0; settings put system user_rotation 0");
                android.util.Log.e("SolarDebugB4208e", "A5 system rotation via root");
            }
        }
        SolarLog.installUncaughtHandler(this);
        // 2026-07-08 — Bridge ThemedContextMenu to ThemeManager without a direct TCM link.
        // Was: TCM called ThemeManager.*; Now: OverlayThemeProvider → AppOverlayThemeAdapter.
        // Reversal: remove install; restore ThemeManager. calls in ThemedContextMenu.
        OverlayThemeProvider.install(new AppOverlayThemeAdapter());
        // 2026-07-14 — Enforce portrait/landscape as soon as any Activity is created.
        // Was: only MainActivity.onCreate (too late / stalls on A5). Now: lifecycle callback.
        // Reversal: unregister; rely on per-activity enforce only.
        if (!isOverlayProcess()) {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(android.app.Activity a, Bundle b) {
                    try {
                        LandscapeOrientationGuard.enforceForDevice(a);
                        android.util.Log.e("SolarDebugB4208e",
                                "lifecycleCreated " + a.getClass().getSimpleName()
                                        + " isA5=" + DeviceFeatures.isA5()
                                        + " req=" + a.getRequestedOrientation());
                    } catch (Throwable ignored) {}
                }
                @Override public void onActivityStarted(android.app.Activity a) {}
                @Override public void onActivityResumed(android.app.Activity a) {
                    try { LandscapeOrientationGuard.enforceForDevice(a); } catch (Throwable ignored) {}
                }
                @Override public void onActivityPaused(android.app.Activity a) {}
                @Override public void onActivityStopped(android.app.Activity a) {}
                @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle o) {}
                @Override public void onActivityDestroyed(android.app.Activity a) {}
            });
        }
        if (!isOverlayProcess()) {
            SolarRecoveryCoordinator.onProcessStart(this);
            // 2026-07-08 — Clear stale handler on main cold start; MainActivity re-registers IPC.
            // State service now lives in main (not :overlay) so this handler is the live one.
            SolarOverlayStateService.registerActionHandler(null);
        }
        com.solar.launcher.theme.ActiveThemeEngine.init(this);
        // 2026-07-14 — A5: do not block Application main on theme snapshot (stalls launch).
        // Was: always loadOverlayRamCacheSync here. Now: async on A5; sync elsewhere.
        // Reversal: always call loadOverlayRamCacheSync on main for all families.
        if (DeviceFeatures.isA5()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ThemeManager.loadOverlayRamCacheSync(SolarApplication.this);
                    ThemeManager.preferInternalCacheForActiveTheme(SolarApplication.this);
                }
            }, "A5ThemeRamWarm").start();
        } else {
            ThemeManager.loadOverlayRamCacheSync(this);
            ThemeManager.preferInternalCacheForActiveTheme(this);
        }
        NavigationPreferences.syncPropertyFromPrefs(this);
        // 2026-07-11 — Rockbox experiment sysprop for companion launcher picker gate.
        RockboxExperiment.syncSyspropFromPrefs(this);
        // Overlay process only hosts WM modal — skip HOME/bootstrap that could touch Rockbox focus.
        if (isOverlayProcess()) {
            // Theme I/O off Application main thread — startService can return while cache warms.
            if (OverlayKeyGate.isOverlayKeysActive()) {
                OverlayKeyGate.disarm();
            }
            SolarImeDismiss.recoverOnBoot(this);
            LargeFontAccessibilitySuppressor.ensureNormalFontScale(this);
            GraphicsPerformancePolicy.ensureAsync(this);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ThemeManager.ensureOverlayThemeReady(SolarApplication.this);
                    HearingSafetyVolume.syncFromPrefs(SolarApplication.this);
                }
            }, "OverlayThemeBoot").start();
            return;
        }
        // 2026-07-14 — A5: return ASAP so SolarLaunchActivity can paint; defer IME/overlay/host.
        // Was: SolarImeBootstrap + overlay hosts on main blocked Activity.onCreate (ANR splash).
        // Reversal: remove this if/return; keep runMainProcessBootstrap() call only.
        if (DeviceFeatures.isA5()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runMainProcessBootstrap();
                }
            }, "A5AppBootstrap").start();
            android.util.Log.e("SolarDebugB4208e", "Application.onCreate A5 EARLY RETURN");
            return;
        }
        runMainProcessBootstrap();
    }

    /**
     * 2026-07-14 — Main process bootstrap after theme warm (IME, overlay hosts, theme loader thread).
     * Layman: finish starting Solar helpers without holding the first screen hostage on A5.
     */
    private void runMainProcessBootstrap() {
        // 2026-07-05 — Main process: clear ghost IME WM overlay before MainActivity paints.
        SolarImeDismiss.recoverOnBoot(this);
        SolarImeBootstrap.ensureDefaultIme(this);
        SolarLog.scrubExistingLogs(this);
        // 2026-07-16 — Lightweight diag init; heavy MicroSD probe deferred off the boot path.
        try {
            com.solar.launcher.diag.SolarDiagFeatureLog.init(this);
            com.solar.launcher.diag.SolarDiagFeatureLog.event("app",
                    "bootstrap family=" + DeviceFeatures.deviceFamily()
                            + " sdk=" + android.os.Build.VERSION.SDK_INT);
        } catch (Throwable ignored) {}
        SolarDiagnosticReporter.onProcessStart(this);
        // Storage capacity check after UI is up (6h cooldown inside probe).
        final android.content.Context app = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(45_000L);
                } catch (InterruptedException ignored) {}
                try {
                    SolarLogPaths.probeMicroSdCapacityAndLog(app);
                } catch (Throwable ignored) {}
            }
        }, "SolarStorageProbe").start();
        HearingSafetyVolume.syncFromPrefs(this);
        LargeFontAccessibilitySuppressor.ensureNormalFontScale(this);
        GraphicsPerformancePolicy.ensureAsync(this);
        // 2026-07-05 — Silent platform prep when prepVersion ahead; no blocking wizard on ROM-ready devices.
        // 2026-07-14 — A5 stock: skip (su/Xposed ladder not for Timmkoo; SuperSU overlays UI).
        if (!DeviceFeatures.isA5()) {
            PlatformPrepLauncher.ensureAsync(this);
        }
        MediaVolumeControl.ensureAlertStreamsSilent(this);
        // Warm :overlay immediately — do not wait for background bootstrap thread.
        SolarOverlayHost.ensureStarted(this);
        SolarRescueHoldHost.ensureStarted(this);
        LauncherWatchdogService.ensureStarted(this);
        startService(new Intent(this, ProcessManagerService.class));
        // 2026-07-08 — JJ/Rockbox/Stock HOME: claim MEDIA_BUTTON before bootstrap I/O (H2).
        String earlyHomeTarget = LauncherPreference.getHomeTarget(this);
        if (LauncherDefault.TARGET_JJ.equals(earlyHomeTarget)
                || LauncherDefault.TARGET_ROCKBOX.equals(earlyHomeTarget)
                || LauncherDefault.TARGET_STOCK.equals(earlyHomeTarget)) {
            MediaButtonRegistrar.ensureRegistered(this);
            ExternalInputHandoff.warmInjector(this);
            RockboxForegroundMonitor.ensureStarted(this);
            if (LauncherDefault.TARGET_JJ.equals(earlyHomeTarget)
                    || LauncherDefault.TARGET_STOCK.equals(earlyHomeTarget)) {
                ExternalInputHandoff.armJjShim(this);
            }
            try {
                startService(new Intent(this, SolarHandoffService.class));
            } catch (Exception ignored) {}
        }
        // 2026-07-06 — Rescue setprop may land before prefs; reconcile before async bootstrap re-applies rockbox.
        LauncherPreference.reconcileHomeTargetFromProperty(this);
        // 2026-07-06 — Record USB boot host before Application bootstrap thread races USB_STATE.
        UsbHostSessionPolicy.onBootCompleted(this);
        // 2026-07-11 — Kill adb/session instrumentation by default (observer-effect lag).
        // User-facing Debug menu + experiment prefs remain; opt-in via *Log.ENABLED = true for sessions.
        DebugPerfLog.ENABLED = false;
        DebugSessionLog.ENABLED = false;
        DebugImeLog.ENABLED = false;
        Debug62b1bbLog.ENABLED = false;
        // #region agent log
        if (DebugPerfLog.ENABLED) {
            DebugPerfLog.markStart();
            final android.os.Handler perfHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable perfSampler = new Runnable() {
                @Override
                public void run() {
                    DebugPerfLog.flushSample("SolarApplication");
                    if (DebugBee1b8Log.ENABLED) {
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("tier", "main-process");
                            DebugBee1b8Log.log("SolarApplication.perfSampler",
                                    "perf sample", "bee1b8-H-D", d);
                        } catch (Exception ignored) {}
                    }
                    perfHandler.postDelayed(this, 3000L);
                }
            };
            perfHandler.postDelayed(perfSampler, 3000L);
        }
        // #endregion
        HearingSafetyVolume.probeAbsoluteMaxAsync(this);
        // 2026-07-05 — Heavy I/O off main thread so home menu paints first on cold start.
        new Thread(new Runnable() {
            @Override
            public void run() {
                TlsHelper.init(SolarApplication.this);
                // 2026-07-06 — OTA catalog (SolarUpdateClient) shares Conscrypt + bundled LE roots with TlsHelper.
                OtaTlsHelper.init(SolarApplication.this);
                Y1InputKeys.selfCheckWheelMapping();
                // 2026-07-14 — A5 stock: skip ROM switch scripts / su prep (SuperSU steals UI).
                // Was: always ensureSwitchScripts + RockboxDisable. Now: Y1/Y2 only.
                // Reversal: remove isA5 continue; always run ensureSwitchScripts.
                if (!DeviceFeatures.isA5()) {
                    SolarBootPacing.pauseBetweenBootstrapSteps();
                    RockboxDisable.ensureOnce(SolarApplication.this);
                    SolarBootPacing.pauseBetweenBootstrapSteps();
                }
                LargeFontAccessibilitySuppressor.ensureNormalFontScale(SolarApplication.this);
                GraphicsPerformancePolicy.ensureAsync(SolarApplication.this);
                WifiMacRandomizer.ensureOnce(SolarApplication.this);
                WirelessAdbEnabler.ensureWirelessAdb(SolarApplication.this);
                SolarBootPacing.pauseBetweenBootstrapSteps();
                ThemeManager.ensureThemesRootReady(SolarApplication.this);
                ThemeManager.ensureBundledDefault(SolarApplication.this);
                ThemeManager.loadAllThemes(SolarApplication.this);
                ThemeManager.restoreSavedThemeFromPrefs(SolarApplication.this);
                ThemeManager.ensureActiveThemeOrFallback(SolarApplication.this);
                ThemeManager.applySolarConfigPrefs(SolarApplication.this);
                ThemeManager.cacheActiveTheme(SolarApplication.this);
                ThemeManager.preferInternalCacheForActiveTheme(SolarApplication.this);
                ThemeManager.warmOverlayThemeCache(SolarApplication.this);
                ThemeManager.syncSavedThemeToPrefs(SolarApplication.this);
                SolarBootPacing.pauseBetweenBootstrapSteps();
                LauncherPreference.reconcileHomeTargetFromProperty(SolarApplication.this);
                // 2026-07-08 — JJ/Rockbox/Stock HOME: prewarm inject before first wheel tick.
                String homeTarget = LauncherPreference.getHomeTarget(SolarApplication.this);
                if (LauncherDefault.TARGET_JJ.equals(homeTarget)
                        || LauncherDefault.TARGET_ROCKBOX.equals(homeTarget)
                        || LauncherDefault.TARGET_STOCK.equals(homeTarget)) {
                    ExternalInputHandoff.warmInjector(SolarApplication.this);
                    if (LauncherDefault.TARGET_JJ.equals(homeTarget)
                            || LauncherDefault.TARGET_STOCK.equals(homeTarget)) {
                        ExternalInputHandoff.armJjShim(SolarApplication.this);
                    } else {
                        ExternalInputHandoff.armForForegroundPackage(SolarApplication.this);
                    }
                    MediaButtonRegistrar.ensureRegistered(SolarApplication.this);
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("homeTarget", homeTarget);
                        d.put("mainActivityAlive", MainActivity.instance != null);
                        d.put("dpadMode", ExternalInputHandoff.getDpadMode());
                        DebugE93bdbLog.log("SolarApplication.bootstrap",
                                "alternate HOME boot arm", "H2", d);
                    } catch (Exception ignored) {}
                    // #endregion
                }
                LauncherSwitch.ensurePreferredHome(SolarApplication.this);
                // 2026-07-15 — YouTube is native Invidious/Piped; no NotPipe PM register.
                SolarBootPacing.pauseBetweenBootstrapSteps();
                SolarBootPacing.schedule(SolarApplication.this, 5_000L, new Runnable() {
                    @Override
                    public void run() {
                        GlobalOverlayTrigger.ensureStarted(SolarApplication.this);
                    }
                });
                UsbMassStorageExperiment.syncExperimentSysprop(SolarApplication.this);
                UsbMassStorageController.ensureStockMtpWhenExperimentOff(SolarApplication.this);
                UsbStorageSessionFlags.syncUsbSessionSysprops(SolarApplication.this);
                // #region agent log
                if (DeviceFeatures.isY1()) {
                    try {
                        org.json.JSONObject d = Debug705932Log.usbSnapshot();
                        d.put("experimentOn", UsbMassStorageExperiment.isEnabled(SolarApplication.this));
                        Debug705932Log.log("SolarApplication.bootstrap", "Y1 boot usb state", "H5", d);
                    } catch (Exception ignored) {}
                }
                // #endregion
                SolarBootPacing.pauseBetweenBootstrapSteps();
                PmInstallPolicy.enforceInternalInstallLocation();
                XposedModuleEnsurer.ensureRequiredModulesAsync(SolarApplication.this);
                // 2026-07-06 — Monitor may already run from onCreate; idempotent ensureStarted.
                RockboxForegroundMonitor.ensureStarted(SolarApplication.this);
                LauncherWatchdogService.ensureStarted(SolarApplication.this);
                SolarImeAccessibilityBootCheck.ensureEnabled(SolarApplication.this);
                // Clear stale overlay key gate (persist prop survives crash / force-stop).
                OverlayKeyGate.disarmStaleIfNeeded(SolarApplication.this);
                ThemeManager.markAppThemeBootstrapComplete();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("bootstrapMs", System.currentTimeMillis());
                    Debug86bbe0Log.log("SolarApplication.bootstrap", "complete", "H3", d);
                } catch (Exception ignored) {}
                // #endregion
            }
        }, "SolarAppBootstrap").start();
    }

    /** True when running in {@code :overlay} — separate from main Solar / MainActivity process. */
    private boolean isOverlayProcess() {
        try {
            String name = currentProcessName();
            return name != null && name.endsWith(":overlay");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String currentProcessName() {
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo info
                        : am.getRunningAppProcesses()) {
                    if (info.pid == pid) return info.processName;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
