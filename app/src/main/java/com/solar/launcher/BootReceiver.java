package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.solar.home.policy.HomeTargetPolicy;
import com.solar.launcher.platform.PlatformPrepLauncher;

/**
 * 2026-07-05 — BOOT_COMPLETED handler: disarm stale gates, repair platform, gate prep wizard.
 * 2026-07-06 — Stagger non-critical helpers; USB boot-settle recorded before any prompt path.
 * APK/ROM parity: ensurer runs every boot; prep wizard runs before MainActivity when gaps remain.
 * Boot order: overlay/IME disarm → USB session record → HOME restore → staggered daemons → MainActivity.
 * When changing: align with SolarApplication bootstrap; 99SolarInit.sh runs earlier via init.d.
 * Reversal: remove PlatformPrepLauncher gate; MainActivity opens immediately on every boot.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (SolarRecoveryCoordinator.isEmergencyMode(context)) {
            if (CompanionContextMenuLauncher.isCompanionInstalled(context)) {
                Intent recovery = new Intent();
                recovery.setComponent(new android.content.ComponentName(
                        "com.solar.launcher.globalcontext",
                        "com.solar.launcher.globalcontext.EmergencyRecoveryActivity"));
                recovery.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(recovery);
                    return;
                } catch (Exception ignored) {}
            }
            Intent recovery = new Intent(context, EmergencyRecoveryActivity.class);
            recovery.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(recovery);
            return;
        }
        // Stale overlay gate breaks Rockbox/back/OK — persist survives overlay process death.
        OverlayKeyGate.disarm();
        SolarImeRouteArbiter.disarm();
        SolarRescueHoldState.disarm();
        dismissStaleOverlays(context);
        SolarImeDismiss.recoverOnBoot(context);
        SolarImeBootstrap.ensureDefaultIme(context);
        RockboxDisable.ensureOnce(context);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("imeActive", SolarImeRouteArbiter.isActive());
            d.put("imeUi", SolarImeRouteArbiter.isTrayUiVisible());
            DebugImeLog.log(context, "BootReceiver.onReceive", "boot IME disarm", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
        LargeFontAccessibilitySuppressor.ensureNormalFontScale(context);
        GraphicsPerformancePolicy.ensureAsync(context);
        LauncherPreference.reconcileHomeTargetFromProperty(context);
        LauncherDiscovery.syncHomeLauncherListProperty(context.getPackageManager());
        LauncherSwitch.ensurePreferredHome(context);
        // 2026-07-06 — Record boot USB host state before any prompt/auto-connect path runs.
        UsbHostSessionPolicy.onBootCompleted(context);
        PmInstallPolicy.enforceInternalInstallLocation();
        UsbMassStorageExperiment.syncExperimentSysprop(context);
        // 2026-07-16 — Clear sticky persist mass_storage so PC never sees disks without consent.
        UsbMassStorageController.ensureNoStickyAutoUmsOnBoot(context);
        UsbMassStorageController.ensureStockMtpWhenExperimentOff(context);
        UsbStorageSessionFlags.syncUsbSessionSysprops(context);
        // Overlay/rescue immediate — modal paint must stay warm (2026-07-06).
        SolarOverlayHost.ensureStarted(context);
        SolarRescueHoldHost.ensureStarted(context);
        LauncherWatchdogService.ensureStarted(context);
        // Stagger heavier helpers so first boot minute stays calm (2026-07-06).
        SolarBootPacing.schedule(context, 3_000L, new Runnable() {
            @Override
            public void run() {
                WifiMacRandomizer.ensureOnce(context);
                WirelessAdbEnabler.ensureWirelessAdb(context);
            }
        });
        SolarBootPacing.schedule(context, 6_000L, new Runnable() {
            @Override
            public void run() {
                GlobalOverlayTrigger.ensureStarted(context);
            }
        });
        SolarBootPacing.schedule(context, 10_000L, new Runnable() {
            @Override
            public void run() {
                XposedModuleEnsurer.ensureRequiredModulesAsync(context);
                BluetoothAudioRepair.requestAutoRepair(context, null);
            }
        });
        // #region agent log
        try {
            org.json.JSONObject d = DebugAf054eLog.bootSnapshot();
            d.put("autoConnect", UsbStorageSessionFlags.isAutoConnectEnabled(context));
            d.put("y1", DeviceFeatures.isY1());
            android.os.PowerManager pm = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            d.put("screenOn", pm != null && pm.isScreenOn());
            DebugAf054eLog.log(context, "BootReceiver.onReceive", "boot snapshot", "H1,H2,H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (!LauncherPreference.isSolarHome(context)) return;
        try {
            if (!context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).enabled) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        SolarBootPacing.schedule(context, 4_000L, new Runnable() {
            @Override
            public void run() {
                PlatformPrepLauncher.ensureAsync(context);
            }
        });
        if (SolarRecoveryCoordinator.isEmergencyMode(context)) return;
        // 2026-07-06 — Never silently enable UMS at boot; reset stale disk mode unless auto-connect on.
        UsbUnauthorizedUmsGuard.teardownIfUnauthorizedAsync(context);
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /** 2026-07-05 — Boot sweep: dismiss orphan WM overlays from prior session (H-A/H-B). */
    private static void dismissStaleOverlays(Context context) {
        if (context == null) return;
        Intent dismiss = new Intent(OverlayTriggers.ACTION_DISMISS_OVERLAY);
        dismiss.setComponent(new android.content.ComponentName(context, SolarOverlayService.class));
        try {
            context.startService(dismiss);
        } catch (Exception ignored) {}
        Intent companionDismiss = new Intent(OverlayTriggers.ACTION_DISMISS_OVERLAY);
        companionDismiss.setComponent(new android.content.ComponentName(
                "com.solar.launcher.globalcontext",
                "com.solar.launcher.globalcontext.GlobalContextOverlayService"));
        try {
            context.startService(companionDismiss);
        } catch (Exception ignored) {}
    }
}
