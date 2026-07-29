package com.solar.launcher.globalcontext;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import com.solar.input.policy.GlobalInputPolicy;
import com.solar.input.policy.StaleOverlayGate;

/**
 * 2026-07-05 — Sole Y2 POWER hold FSM; Xposed forwards HOLD_DOWN/UP here.
 * Layman: decides when sleep tap passes through vs when quick menu or rescue HUD arms.
 * Technical: uses shared {@link GlobalInputPolicy} JAR; fg-dependent modal hold delays.
 * Reversal: delete; PWM keeps full FSM inside SystemServerHooks.
 */
public final class GlobalInputCoordinatorService extends Service {

    private static final String TAG = "GlobalInputCoordinator";

    private HandlerThread workerThread;
    private Handler worker;

    private int activeKeyCode;
    private String foregroundPkg;
    private long holdDownAt;
    private boolean modalFired;
    private boolean rescueArmed;
    private Runnable modalRunnable;
    private Runnable rescueTickRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread(TAG);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        SysPropHelper.set(GlobalInputPolicy.POLICY_REV_PROPERTY,
                String.valueOf(GlobalInputPolicy.POLICY_REV));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }
        final String action = intent.getAction();
        if (CompanionOverlayTriggers.ACTION_HOLD_DOWN.equals(action)) {
            handleHoldDown(intent);
        } else if (CompanionOverlayTriggers.ACTION_HOLD_UP.equals(action)) {
            handleHoldUp();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelRunnables();
        if (workerThread != null) {
            if (android.os.Build.VERSION.SDK_INT >= 18) workerThread.quitSafely();
            else workerThread.quit();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void handleHoldDown(Intent intent) {
        activeKeyCode = intent.getIntExtra(CompanionOverlayTriggers.EXTRA_KEY_CODE, 0);
        foregroundPkg = intent.getStringExtra(CompanionOverlayTriggers.EXTRA_FOREGROUND_PKG);
        boolean y2 = intent.getBooleanExtra(CompanionOverlayTriggers.EXTRA_Y2_DEVICE,
                CompanionDeviceFeatures.isY2());
        // 2026-07-10 — HOLD_DOWN is always finger-down arming; passthrough is UP-only in PWM.
        // Was: shouldPassthroughPowerTap(0) on first DOWN → early return → no modal/rescue ever
        // when companion owned Y2 POWER (SystemServerHooks skipped PWM runnable).
        // Reversal: restore passthrough-on-DOWN only if duplicate DOWN storms need filtering.

        if (holdDownAt == 0L) {
            holdDownAt = SystemClock.uptimeMillis();
            modalFired = false;
            rescueArmed = false;
            StaleOverlayGate.clearIfNeeded();
            scheduleModalAtHoldMs(y2);
            scheduleRescueArm(y2);
        }
    }

    private void handleHoldUp() {
        cancelRunnables();
        if (rescueArmed && CompanionRescueHoldState.isHoldActive()) {
            CompanionRescueHoldState.disarm();
        }
        holdDownAt = 0L;
        modalFired = false;
        rescueArmed = false;
        activeKeyCode = 0;
        foregroundPkg = null;
    }

    private void scheduleModalAtHoldMs(final boolean y2) {
        cancelModalRunnable();
        final long holdDelayMs = activeKeyCode == GlobalInputPolicy.KEYCODE_POWER
                ? GlobalInputPolicy.powerModalHoldMsForPackage(foregroundPkg)
                : GlobalInputPolicy.backModalHoldMsForPackage(foregroundPkg);
        modalRunnable = new Runnable() {
            @Override
            public void run() {
                if (holdDownAt == 0L || modalFired) return;
                long held = SystemClock.uptimeMillis() - holdDownAt;
                long thresholdMs = activeKeyCode == GlobalInputPolicy.KEYCODE_POWER
                        ? GlobalInputPolicy.powerModalHoldMsForPackage(foregroundPkg)
                        : GlobalInputPolicy.backModalHoldMsForPackage(foregroundPkg);
                if (held < thresholdMs) return;
                modalFired = true;
                String fgAtFire = resolveForegroundAtFire(foregroundPkg);
                if (activeKeyCode == GlobalInputPolicy.KEYCODE_POWER) {
                    // 2026-07-14 — Companion hold FSM retired for system-wide modal; Solar uses PWM.
                    if (!GlobalInputPolicy.shouldOfferPowerLongModal(fgAtFire, y2)) return;
                    // Solar-only: do not paint WM shell — MainActivity / PWM owns in-app menu.
                    return;
                }
                boolean emergency = EmergencyRockboxMode.isEmergencyMode();
                boolean ime = SysPropHelper.isTrue("sys.solar.ime.active");
                if (!GlobalInputPolicy.shouldLaunchSolarOnBackLong(fgAtFire, ime, emergency)) {
                    return;
                }
                // #region agent log
                AgentDebugLog.log("c54726-H3", "GlobalInputCoordinator.modalRunnable",
                        "launch_solar_home", "{\"heldMs\":" + held
                                + ",\"thresholdMs\":" + thresholdMs
                                + ",\"keyCode\":" + activeKeyCode
                                + ",\"fg\":\"" + (fgAtFire != null
                                        ? fgAtFire.replace("\"", "'") : "")
                                + "\"}");
                // #endregion
                // 2026-07-14 — HOLD BACK → Solar Home (was openPowerOverlay WM shell).
                launchSolarHome();
            }
        };
        worker.postDelayed(modalRunnable, holdDelayMs);
    }

    private void scheduleRescueArm(final boolean y2) {
        cancelRescueTick();
        rescueTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (holdDownAt == 0L) return;
                long held = SystemClock.uptimeMillis() - holdDownAt;
                if (!rescueArmed && held >= GlobalInputPolicy.HUD_COUNTDOWN_START_MS) {
                    if (GlobalInputPolicy.shouldArmRescueHoldForPackage(foregroundPkg, activeKeyCode)) {
                        rescueArmed = true;
                        String kind = activeKeyCode == GlobalInputPolicy.KEYCODE_POWER
                                ? CompanionRescueHoldState.KIND_POWER
                                : CompanionRescueHoldState.KIND_BACK;
                        CompanionRescueHoldState.armFromHoldStart(kind, holdDownAt);
                        ensureHoldService();
                    }
                }
                if (rescueArmed && held >= GlobalInputPolicy.RESCUE_EXECUTE_MS) {
                    CompanionRescueHoldState.signalRestarting();
                    RescueExecutor.execute(getApplicationContext(), foregroundPkg);
                    handleHoldUp();
                    return;
                }
                if (holdDownAt > 0L) {
                    worker.postDelayed(this, 100L);
                }
            }
        };
        worker.postDelayed(rescueTickRunnable, GlobalInputPolicy.HUD_COUNTDOWN_START_MS);
    }

    /**
     * 2026-07-10 — Re-resolve fg at fire; DOWN-time probe may lie (systemui over app).
     * Layman: when the menu is about to open, check who is really on screen.
     */
    private static String resolveForegroundAtFire(String cached) {
        String live = ForegroundProbe.topPackage();
        if (live != null && live.length() > 0
                && !GlobalInputPolicy.isSystemShellPackage(live)) {
            return live;
        }
        if (GlobalInputPolicy.shouldFailOpenPowerFg(live)) {
            return live != null ? live : cached;
        }
        if (cached != null && cached.length() > 0
                && !GlobalInputPolicy.isSystemShellPackage(cached)) {
            return cached;
        }
        return live != null ? live : cached;
    }

    /**
     * 2026-07-14 — HOLD BACK returns to Solar MainActivity (no companion WM quick menu).
     * Layman: Back-hold from this leftover FSM still goes home, not into a floating shell.
     */
    private void launchSolarHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(
                    "com.solar.launcher", "com.solar.launcher.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {
            AgentDebugLog.log("c54726-H3", "GlobalInputCoordinator.launchSolarHome",
                    "failed", "{\"err\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** @deprecated 2026-07-14 — system-wide quick menu retired; keep for volume/USB escape hatches. */
    private void openPowerOverlay() {
        StaleOverlayGate.clearIfNeeded();
        if (StaleOverlayGate.isActiveOrOpening()) {
            return;
        }
        CompanionOverlayKeyGate.setOverlayOpening(true);
        // #region agent log
        AgentDebugLog.log("H-C", "GlobalInputCoordinator.openPowerOverlay",
                "start_route", "{\"fg\":\""
                        + (foregroundPkg != null ? foregroundPkg.replace("\"", "'") : "")
                        + "\"}");
        // #endregion
        if (CompanionOverlayRouter.startSolarOverlayPower(this)) {
            return;
        }
        CompanionOverlayRouter.startCompanionPowerOverlay(this);
        Intent keep = new Intent(CompanionOverlayTriggers.ACTION_OVERLAY_KEEPALIVE);
        keep.setComponent(new ComponentName(this, GlobalContextOverlayService.class));
        try {
            startService(keep);
        } catch (Exception ignored) {}
    }

    private void ensureHoldService() {
        Intent hold = new Intent(CompanionOverlayTriggers.ACTION_RESCUE_HOLD_KEEPALIVE);
        hold.setComponent(new ComponentName(this, RescueHoldService.class));
        try {
            startService(hold);
        } catch (Exception ignored) {}
    }

    private void cancelRunnables() {
        cancelModalRunnable();
        cancelRescueTick();
    }

    private void cancelModalRunnable() {
        if (worker != null && modalRunnable != null) {
            worker.removeCallbacks(modalRunnable);
        }
        modalRunnable = null;
    }

    private void cancelRescueTick() {
        if (worker != null && rescueTickRunnable != null) {
            worker.removeCallbacks(rescueTickRunnable);
        }
        rescueTickRunnable = null;
    }
}
