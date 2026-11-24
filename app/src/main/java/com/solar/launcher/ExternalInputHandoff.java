package com.solar.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 2026-07-05 — Re-emits wheel/side keys as DPAD to third-party apps when Solar hands off focus.
 * Mutex: paused while IME tray or global overlay owns keys (check SolarImeRouteArbiter first).
 * Hardware keylayout lines 105/106/165/163 must stay untouched — remap only via this handoff path.
 * When changing: Y1BluetoothInput guard must run before wheel transport logic in dispatchers.
 * Reversal: set MODE_OFF everywhere; stock Android delivers raw key events to foreground app.
 */
public final class ExternalInputHandoff {
    private static final String TAG = "ExternalInputHandoff";
    private static volatile boolean loggedInjectFailure;

    public static final int MODE_OFF = 0;
    public static final int MODE_FM = 1;
    public static final int MODE_ANDROID = 2;
    /** 2026-07-06 — JJ Launcher wheel remap: PLAY/PAUSE→LEFT/RIGHT; stock media keys pass through. */
    public static final int MODE_JJ = 3;

    static final String FM_RADIO_PACKAGE = "com.mediatek.FMRadio";
    private static final String SOLAR_PACKAGE = "com.solar.launcher";
    /** Read by solar-context-bridge — skip center swallow+re-inject while wheel handoff injects. */
    public static final String HANDOFF_ACTIVE_PROPERTY = "sys.solar.handoff.active";
    /** 2026-07-06 — JJ in-process Xposed shim gate (persist.solar.jj.xposed_shim=1). */
    public static final String JJ_HANDOFF_PROPERTY = "sys.solar.handoff.jj";

    /** INJECT_INPUT_EVENT_MODE_ASYNC — reference MediaButtonReceiver line 141. */
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private ExternalInputHandoff() {}

    /**
     * Wheel handoff equivalent. Static so the manifest MediaBtnReceiver keeps remapping even
     * when MainActivity was destroyed (LMK while a stock app is foreground).
     */
    private static volatile int dpadMode = MODE_OFF;

    /** Saved handoff mode while global overlay owns keys — restored on overlay dismiss. */
    private static volatile int dpadModeBeforeOverlay = MODE_OFF;

    /** Saved handoff mode while Solar IME tray owns keys — restored on IME dismiss. */
    private static volatile int dpadModeBeforeIme = MODE_OFF;

    /** Saved handoff mode while Stem/Mix jam owns keys — restored when jam ends. 2026-07-19 */
    private static volatile int dpadModeBeforeStemMix = MODE_OFF;

    /** Arm/disarm from MainActivity.onWindowFocusChanged — the Rockbox setDpadMode twin. */
    public static void setDpadMode(int mode) {
        dpadMode = mode;
        syncHandoffActiveProperty();
        syncJjHandoffProperty(mode == MODE_JJ);
    }

    /** Publish handoff state to system_server so OK is not re-injected during wheel inject. */
    private static void syncHandoffActiveProperty() {
        final int effective = getDpadMode();
        if (effective == lastSyncedHandoffMode) return;
        lastSyncedHandoffMode = effective;
        final String cmd = "setprop " + HANDOFF_ACTIVE_PROPERTY + " "
                + (effective != MODE_OFF ? "1" : "0");
        try {
            RootShell.runAsync(cmd);
        } catch (RuntimeException ignored) {
            // JVM unit tests — HandlerThread not mocked; handoff mode still round-trips in-process.
        }
    }

    private static volatile int lastSyncedHandoffMode = Integer.MIN_VALUE;

    /** True when Solar global context modal is up — block inject + MEDIA_BUTTON remap. */
    public static boolean isBlockedByGlobalOverlay() {
        return OverlayKeyGate.isOverlayKeysActive();
    }

    /** True when Solar system IME tray is up — block inject + MEDIA_BUTTON remap. */
    public static boolean isBlockedByIme() {
        return SolarImeRouteArbiter.isActive();
    }

    /** True when Solar Stem/Mix jam owns keys — block inject + MEDIA_BUTTON remap. 2026-07-19 */
    public static boolean isBlockedByStemMix() {
        return StemOrMixSession.isActive();
    }

    /** True when overlay or IME owns keys — shared inject gate. */
    public static boolean isBlockedBySolarInputCapture() {
        return isBlockedByGlobalOverlay() || isBlockedByIme() || isBlockedByStemMix();
    }

    /**
     * StemOrMixSession.setActive(true) — pause stock-app inject while jam owns pads.
     * 2026-07-19
     */
    public static void pauseForStemMix() {
        if (dpadMode != MODE_OFF && dpadModeBeforeStemMix == MODE_OFF) {
            dpadModeBeforeStemMix = dpadMode;
        }
        dpadMode = MODE_OFF;
        syncHandoffActiveProperty();
    }

    /** StemOrMixSession.setActive(false) — restore prior handoff after jam. 2026-07-19 */
    public static void resumeFromStemMix() {
        if (StemOrMixSession.isActive()) return;
        if (OverlayKeyGate.isOverlayKeysActive()) return;
        if (SolarImeRouteArbiter.isActive()) return;
        if (dpadModeBeforeStemMix != MODE_OFF) {
            dpadMode = dpadModeBeforeStemMix;
        }
        dpadModeBeforeStemMix = MODE_OFF;
        syncHandoffActiveProperty();
    }

    /** OverlayKeyGate.arm — pause stock-app inject until modal closes (:overlay process). */
    public static void pauseForGlobalOverlay() {
        if (dpadMode != MODE_OFF && dpadModeBeforeOverlay == MODE_OFF) {
            dpadModeBeforeOverlay = dpadMode;
        }
        dpadMode = MODE_OFF;
        syncHandoffActiveProperty();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("savedMode", dpadModeBeforeOverlay);
            DebugInputLog.log("ExternalInputHandoff.pauseForGlobalOverlay",
                    "handoff paused for overlay", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** SolarImeRouteArbiter.arm — pause stock-app inject until IME tray closes. */
    public static void pauseForIme() {
        if (dpadMode != MODE_OFF && dpadModeBeforeIme == MODE_OFF) {
            dpadModeBeforeIme = dpadMode;
        }
        dpadMode = MODE_OFF;
        syncHandoffActiveProperty();
    }

    /** SolarImeRouteArbiter.disarm — restore prior handoff after IME dismiss. */
    public static void resumeFromIme() {
        if (SolarImeRouteArbiter.isActive()) return;
        if (dpadModeBeforeIme != MODE_OFF) {
            dpadMode = dpadModeBeforeIme;
        }
        dpadModeBeforeIme = MODE_OFF;
        syncHandoffActiveProperty();
    }

    /** OverlayKeyGate.disarm — restore prior handoff if user still in a stock app. */
    public static void resumeFromGlobalOverlay() {
        if (OverlayKeyGate.isOverlayKeysActive()) return;
        if (dpadModeBeforeIme != MODE_OFF) {
            dpadMode = dpadModeBeforeIme;
            dpadModeBeforeIme = MODE_OFF;
        } else if (dpadModeBeforeOverlay != MODE_OFF) {
            dpadMode = dpadModeBeforeOverlay;
        }
        dpadModeBeforeOverlay = MODE_OFF;
        syncHandoffActiveProperty();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("restoredMode", dpadMode);
            DebugInputLog.log("ExternalInputHandoff.resumeFromGlobalOverlay",
                    "handoff resume after overlay", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /**
     * Main-process restore after :overlay dismiss — re-arm wheel inject for the foreground stock app.
     * Overlay and main are separate processes; disarm in :overlay alone leaves inject off in main.
     */
    public static void restoreAfterOverlayDismiss(Context context) {
        // #region agent log
        try {
            JSONObject d = DebugOverlayStuckLog.overlayPropSnapshot();
            d.put("fg", context != null ? getForegroundPackageName(context) : null);
            d.put("dpadModeBefore", dpadModeBeforeOverlay);
            DebugOverlayStuckLog.log("ExternalInputHandoff.restoreAfterOverlayDismiss",
                    "entry", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
        OverlayKeyGate.disarm();
        resumeFromGlobalOverlay();
        int mode = restoreHandoffModeForPackage(
                context != null ? getForegroundPackageName(context) : null);
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("mode", mode);
            d.put("overlayActive", OverlayKeyGate.isOverlayKeysActive());
            d.put("fg", context != null ? getForegroundPackageName(context) : null);
            DebugAgentLog.log(context, "ExternalInputHandoff.restoreAfterOverlayDismiss",
                    "handoff restored", "H1-H3", d);
            JSONObject d2 = DebugOverlayStuckLog.overlayPropSnapshot();
            d2.put("mode", mode);
            d2.put("fg", context != null ? getForegroundPackageName(context) : null);
            DebugOverlayStuckLog.log("ExternalInputHandoff.restoreAfterOverlayDismiss",
                    "handoff restored", "H-C", d2);
        } catch (Exception ignored) {}
        // #endregion
        if (mode != MODE_OFF && context != null) {
            armFastInjector(context);
        }
    }

    /** Pick handoff mode after overlay — saved pre-overlay mode, else probe foreground package. */
    static int restoreHandoffModeForPackage(String foregroundPackage) {
        if (OverlayKeyGate.isOverlayKeysActive()) {
            return MODE_OFF;
        }
        if (isSolarForegroundPackage(foregroundPackage) || shouldKeepHandoffOffForSolarHome()) {
            forceDisarmForSolarFocus();
            return MODE_OFF;
        }
        if (dpadModeBeforeOverlay != MODE_OFF) {
            dpadMode = dpadModeBeforeOverlay;
        } else {
            int resolved = resolveModeForForegroundPackage(foregroundPackage);
            if (resolved != MODE_OFF) {
                dpadMode = resolved;
            }
        }
        dpadModeBeforeOverlay = MODE_OFF;
        syncHandoffActiveProperty();
        return getDpadMode();
    }

    /** Cheap per-key gate — no binder calls, mirrors Rockbox "dpad_mode > 0". */
    public static int getDpadMode() {
        // :overlay arms sys.solar.overlay.active / sys.solar.ime.active; main honors props without broadcast.
        if (isBlockedByGlobalOverlay()) return MODE_OFF;
        if (isBlockedByIme()) return MODE_OFF;
        return dpadMode;
    }

    /** Solar HOME regained focus — clear armed handoff even when overlay/IME masked getDpadMode(). */
    public static void forceDisarmForSolarFocus() {
        dpadMode = MODE_OFF;
        dpadModeBeforeOverlay = MODE_OFF;
        dpadModeBeforeIme = MODE_OFF;
        syncHandoffActiveProperty();
    }

    /** True when Solar Activity is foreground — do not re-arm stock-app wheel inject. */
    private static boolean shouldKeepHandoffOffForSolarHome() {
        MainActivity activity = MainActivity.instance;
        if (activity != null && activity.hasWindowFocus()) return true;
        if (activity != null && isSolarForegroundPackage(getForegroundPackageName(activity))) {
            return true;
        }
        return false;
    }

    /**
     * Full receiver-side remap: MEDIA key → synthetic DPAD tap while a stock app is focused.
     * Activity-independent (MediaButtonReceiver parity). Returns true when the event was
     * consumed by the handoff and the ordered broadcast should be aborted.
     */
    public static boolean handleMediaButton(Context context, KeyEvent event, boolean activityAlive) {
        if (event == null) return false;
        // Solar IME tray owns wheel — swallow before context probe (receiver may lack Context).
        if (isBlockedByIme()) {
            if (isMediaNavigationKey(event.getKeyCode())
                    || wouldRemapLegacyHardware(event.getKeyCode())) {
                return true;
            }
        }
        if (context == null) return false;
        // Live Solar UI with window focus — native wheel routing, never MEDIA_BUTTON inject.
        MainActivity activity = MainActivity.instance;
        if (activityAlive && activity != null && activity.hasWindowFocus()) {
            return false;
        }
        if (isBlockedByGlobalOverlay()) {
            OverlayKeyGate.disarmStaleIfNeeded(context);
            if (!isBlockedByGlobalOverlay()) {
                // Stale gate cleared — retry once so wheel reaches the stock app.
                return handleMediaButton(context, event, activityAlive);
            }
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("keyCode", event.getKeyCode());
                d.put("action", event.getAction());
                DebugInputLog.log("ExternalInputHandoff.handleMediaButton",
                        "consumed overlay block", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            return true;
        }
        // Brief cooldown after overlay dismiss — swallow stale wheel keys, never inject.
        if (OverlayKeyGate.isInPostOverlayCooldown()) {
            if (isMediaNavigationKey(event.getKeyCode())
                    || wouldRemapLegacyHardware(event.getKeyCode())) {
                return true;
            }
        }
        // Solar foreground handles wheel/back/center natively — never inject while HOME is up.
        if (isSolarForegroundPackage(getForegroundPackageName(context))) {
            return false;
        }
        int mode = getDpadMode();
        String fgPkg = getForegroundPackageName(context);
        if (mode == MODE_FM
                || FM_RADIO_PACKAGE.equals(fgPkg)
                || com.solar.launcher.radio.fm.FmAirplaneModeHelper.isFmLikePackage(fgPkg)) {
            // Keep airplane off while any FM-like app is foreground (sticky MTK need).
            com.solar.launcher.radio.fm.FmAirplaneModeHelper.ensureSessionForForegroundPackage(
                    context, fgPkg);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", event.getKeyCode());
                d.put("action", event.getAction());
                d.put("mode", mode);
                d.put("fg", fgPkg);
                d.put("overlayBlock", isBlockedByGlobalOverlay());
                d.put("cooldown", OverlayKeyGate.isInPostOverlayCooldown());
                d.put("activityAlive", activityAlive);
                DebugE47f4cLog.log("ExternalInputHandoff.handleMediaButton", "fm path", "H2-H4", d);
            } catch (Exception ignored) {}
            // #endregion
        }
        if (mode == MODE_OFF) {
            // Solar focused — native wheel routing; skip inject even if process is alive.
            if (activityAlive && activity != null && activity.hasWindowFocus()) {
                return false;
            }
            // Solar alive in background (e.g. JJ HOME) — still arm when another app is foreground.
            if (activityAlive && isSolarForegroundPackage(getForegroundPackageName(context))) {
                return false;
            }
            // Standalone recovery: activity destroyed while a stock app owns the screen.
            if (!isScreenOn(context)) return false;
            mode = resolveModeForForegroundPackage(getForegroundPackageName(context));
            if (mode == MODE_OFF) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("fg", getForegroundPackageName(context));
                    d.put("activityAlive", activityAlive);
                    DebugE93bdbLog.log("ExternalInputHandoff.handleMediaButton",
                            "MODE_OFF bail", "H3", d);
                } catch (Exception ignored) {}
                // #endregion
                return false;
            }
            setDpadMode(mode); // cache — subsequent keys skip the top-task probe
            armFastInjector(context); // 2026-07-06 — cold-start inject daemon before first wheel UP (H4).
            Log.i(TAG, "receiver standalone remap key=" + event.getKeyCode() + " mode=" + mode);
        } else {
            // #region agent log
            DebugPerfLog.incHandoffCacheHit();
            // #endregion
        }
        if (!isScreenOn(context)) {
            // Armed but screen off — screen-off transport (BT AVRCP) must win, not the remap.
            return false;
        }
        int keyCode = event.getKeyCode();
        int dpadCode = isMediaNavigationKey(keyCode)
                ? mediaToDpad(keyCode, event.getRepeatCount(), mode)
                : legacyHardwareToDpad(keyCode, mode);
        if (dpadCode <= 0) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("mode", mode);
                d.put("fg", getForegroundPackageName(context));
                DebugE93bdbLog.log("ExternalInputHandoff.handleMediaButton",
                        "no dpad map", "H3", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        // Verbatim: inject synthetic DOWN+UP only when the hardware key is released.
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.getRepeatCount() > 0) {
                return true;
            }
            boolean injected = injectClick(context, dpadCode);
            Log.i(TAG, "handoff media=" + keyCode + " dpad=" + dpadCode + " ok=" + injected);
            if (mode == MODE_FM || FM_RADIO_PACKAGE.equals(getForegroundPackageName(context))) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("keyCode", keyCode);
                    d.put("dpadCode", dpadCode);
                    d.put("injected", injected);
                    d.put("injectReady", RootKeyInjector.isReady());
                    DebugE47f4cLog.log("ExternalInputHandoff.handleMediaButton",
                            injected ? "fm inject ok" : "fm inject fail", "H4-H5", d);
                } catch (Exception ignored) {}
                // #endregion
            }
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("dpadCode", dpadCode);
                d.put("mode", mode);
                d.put("injected", injected);
                d.put("injectReady", RootKeyInjector.isReady());
                DebugE93bdbLog.log("ExternalInputHandoff.handleMediaButton",
                        injected ? "injected click" : "inject failed", "H4", d);
            } catch (Exception ignored) {}
            // #endregion
        }
        return true;
    }

    /** API 17-safe screen check that works from a receiver context (no Activity). */
    private static boolean isScreenOn(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm.isScreenOn();
        } catch (Exception e) {
            return true; // fail-open: worst case we remap while dozing, never lose input
        }
    }

    /**
     * Foreground package for USB overlay routing and handoff — prefers topActivity, skips
     * SystemUI shells, walks a few tasks when UsbStorageActivity sits on top (Y1/Y2 KitKat).
     */
    /** 2026-07-06 — Sample fg-probe rate for hang session 86bbe0. */
    private static volatile long lastFgProbeLogMs;
    private static volatile int fgProbeSinceLog;
    /** Dedupe expensive AMS binder IPC — multiple pollers hit this on the main looper (2026-07-06). */
    private static volatile String cachedFgPkg;
    private static volatile long cachedFgAtMs;
    private static final long FG_CACHE_MS = 450L;
    /**
     * 2026-07-19 — Phase C: longer TTL while handoff remaps third-party wheel (fewer AMS probes).
     * Was: always FG_CACHE_MS (450) when Solar lacks window focus. Reversal: drop HANDOFF branch.
     */
    private static final long FG_CACHE_HANDOFF_MS = 1200L;
    /** Longer TTL while Solar home owns the wheel — USB modal must stay responsive (2026-07-06). */
    private static final long FG_CACHE_SOLAR_FOCUS_MS = 2500L;
    /** Overlay modal active — avoid getRunningTasks IPC storms on :overlay/main looper (2026-07-06). */
    private static final long FG_CACHE_OVERLAY_MS = 3000L;

    @SuppressWarnings("deprecation")
    public static String getForegroundPackageName(Context context) {
        if (context == null) return null;
        long now = System.currentTimeMillis();
        long t0 = android.os.SystemClock.uptimeMillis();
        MainActivity solar = MainActivity.instance;
        long ttl;
        if (OverlayKeyGate.isOverlayKeysActive()) {
            ttl = FG_CACHE_OVERLAY_MS;
        } else if (solar != null && solar.hasWindowFocus()) {
            ttl = FG_CACHE_SOLAR_FOCUS_MS;
        } else if (dpadMode != MODE_OFF) {
            // Phase C: third-party handoff path — cache FG longer across wheel bursts.
            ttl = FG_CACHE_HANDOFF_MS;
        } else {
            ttl = FG_CACHE_MS;
        }
        // #region agent log
        DebugPerfLog.incFgProbe();
        fgProbeSinceLog++;
        if (Debug86bbe0Log.ENABLED && (now - lastFgProbeLogMs >= 2000L || fgProbeSinceLog >= 8)) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("callsSinceLog", fgProbeSinceLog);
                d.put("windowMs", lastFgProbeLogMs > 0L ? now - lastFgProbeLogMs : 0L);
                d.put("onMainThread", android.os.Looper.myLooper()
                        == android.os.Looper.getMainLooper());
                d.put("cacheAgeMs", cachedFgAtMs > 0L ? now - cachedFgAtMs : -1L);
                Debug86bbe0Log.log("ExternalInputHandoff.getForegroundPackageName",
                        "fg probe burst", "H2", d);
            } catch (Exception ignored) {}
            lastFgProbeLogMs = now;
            fgProbeSinceLog = 0;
        }
        // #endregion
        if (cachedFgPkg != null && (now - cachedFgAtMs) < ttl) {
            return cachedFgPkg;
        }
        // Fast path — Solar has window focus; skip getRunningTasks storm during USB modal (2026-07-06).
        if (solar != null && solar.hasWindowFocus()) {
            cachedFgPkg = SOLAR_PACKAGE;
            cachedFgAtMs = now;
            return SOLAR_PACKAGE;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
            if (tasks != null) {
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    String pkg = packageFromTask(task);
                    if (pkg != null && !GlobalOverlayPolicy.isSystemShellPackage(pkg)) {
                        cachedFgPkg = pkg;
                        cachedFgAtMs = now;
                        return pkg;
                    }
                }
            }
            // getRunningTasks can return empty on KitKat — fall back to foreground processes.
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (proc.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        continue;
                    }
                    if (proc.pkgList == null) continue;
                    for (String candidate : proc.pkgList) {
                        if (candidate != null && candidate.length() > 0
                                && !GlobalOverlayPolicy.isSystemShellPackage(candidate)) {
                            cachedFgPkg = candidate;
                            cachedFgAtMs = now;
                            return candidate;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        long tookMs = android.os.SystemClock.uptimeMillis() - t0;
        if (tookMs >= 12L) {
            try {
                JSONObject d = new JSONObject();
                d.put("tookMs", tookMs);
                d.put("cacheAgeMs", cachedFgAtMs > 0L ? now - cachedFgAtMs : -1L);
                d.put("ttlMs", ttl);
                DebugSessionLog.logAlways("ExternalInputHandoff.getForegroundPackageName",
                        "latency sample", "H-LAT-FG", d);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Bust fg cache after task/focus transitions — optional; TTL usually enough (2026-07-06). */
    public static void invalidateForegroundPackageCache() {
        cachedFgAtMs = 0L;
        cachedFgPkg = null;
    }

    /** Resolve package from a running task — topActivity first (actual resumed UI). */
    private static String packageFromTask(ActivityManager.RunningTaskInfo task) {
        if (task == null) return null;
        if (task.topActivity != null) {
            return task.topActivity.getPackageName();
        }
        if (task.baseActivity != null) {
            return task.baseActivity.getPackageName();
        }
        return null;
    }

    /** Pick FM vs generic Android remap when Solar loses focus to another app. */
    /** True when Solar is the foreground app — skip stock-app inject paths. */
    public static boolean isSolarForegroundPackage(String packageName) {
        return GlobalOverlayPolicy.isSolarForegroundPackage(packageName);
    }

    public static int resolveModeForForegroundPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return MODE_OFF;
        if (SOLAR_PACKAGE.equals(packageName)) return MODE_OFF;
        // 2026-07-08 — JJ + stock Innioasis HOME share Y1 wheel keylayout → MODE_JJ wheel inject.
        // Reversal: blanket com.innioasis.* → MODE_OFF; MODE_JJ for com.themoon.y1 only.
        if (com.solar.input.policy.GlobalInputPolicy.isJjKeylayoutLauncher(packageName)) {
            return MODE_JJ;
        }
        // 2026-07-15 — FM packages (including com.innioasis.fm) before generic innioasis OFF.
        if (FM_RADIO_PACKAGE.equals(packageName)
                || com.solar.launcher.radio.fm.FmAirplaneModeHelper.isFmLikePackage(packageName)) {
            return MODE_FM;
        }
        // Other vendor apps under com.innioasis.* (music) — no stock inject remap.
        if (com.solar.input.policy.GlobalInputPolicy.isInnioasisNonLauncherPackage(packageName)) {
            return MODE_OFF;
        }
        return MODE_ANDROID;
    }

    /**
     * 2026-07-06 — Probe foreground and arm wheel inject (JJ or stock) when Solar loses focus.
     * 2026-07-15 — Also force airplane off when FG is any FM-named third-party app.
     * Layman: turn on key translation for whichever app is on screen now; clear flight mode for FM.
     * Technical: sets static dpadMode + root injector from resolveModeForForegroundPackage.
     */
    public static void armForForegroundPackage(Context context) {
        if (context == null || isBlockedBySolarInputCapture()) return;
        if (shouldKeepHandoffOffForSolarHome()) return;
        String fg = getForegroundPackageName(context);
        if (isSolarForegroundPackage(fg)) return;
        // Airplane off for stock MTK FM or any third-party FM package name.
        com.solar.launcher.radio.fm.FmAirplaneModeHelper.ensureSessionForForegroundPackage(
                context, fg);
        int mode = resolveModeForForegroundPackage(fg);
        if (mode == MODE_OFF) return;
        setDpadMode(mode);
        armFastInjector(context);
    }

    /**
     * 2026-07-08 — Immediate JJ-style wheel remap for JJ or Innioasis stock HOME.
     * Layman: turn on scroll-wheel translation before the first wheel tick.
     * Technical: MODE_JJ + MEDIA_BUTTON register + root inject prewarm.
     */
    public static void armJjShim(Context context) {
        if (context == null || isBlockedBySolarInputCapture()) return;
        setDpadMode(MODE_JJ);
        MediaButtonRegistrar.ensureRegistered(context);
        armFastInjector(context);
        syncJjHandoffProperty(true);
    }

    private static void syncJjHandoffProperty(boolean jj) {
        final String cmd = "setprop " + JJ_HANDOFF_PROPERTY + " " + (jj ? "1" : "0");
        try {
            RootShell.runAsync(cmd);
        } catch (RuntimeException ignored) {}
    }

    /** True when legacy DPAD or mtk-kpd wheel lines would remap under any handoff mode. */
    static boolean wouldRemapLegacyHardware(int keyCode) {
        return legacyHardwareToDpad(keyCode, MODE_ANDROID) > 0
                || legacyHardwareToDpad(keyCode, MODE_JJ) > 0;
    }

    public static boolean isMediaNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    /**
     * When mtk-tpd-kpd still maps wheel to DPAD (103/108→19/20), remap for the target app.
     * JJ uses horizontal wheel (21/22); generic stock apps keep vertical DPAD (19/20).
     */
    public static int legacyHardwareToDpad(int keyCode, int mode) {
        if (mode == MODE_OFF) return 0;
        if (mode == MODE_JJ) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return KeyEvent.KEYCODE_DPAD_LEFT;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return KeyEvent.KEYCODE_DPAD_RIGHT;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return keyCode;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return KeyEvent.KEYCODE_DPAD_CENTER;
                default:
                    return 0;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            default:
                return 0;
        }
    }

    public static int mediaToDpad(int keyCode, int repeatCount, int mode) {
        if (mode == MODE_FM) return mediaToFmDpad(keyCode, repeatCount);
        if (mode == MODE_JJ) return mediaToJjDpad(keyCode);
        if (mode == MODE_ANDROID) return mediaToAndroidDpad(keyCode);
        return 0;
    }

    /**
     * 2026-07-08 — JJ + stock Innioasis HOME expect DPAD 21/22 for wheel.
     * Layman: turn Play/Pause wheel ticks into left/right for factory and JJ menus.
     * Technical: Y1 wheel keylayout maps wheel 105/106→MEDIA_PLAY/PAUSE; stock mtk-kpd often used
     * DPAD_UP/DOWN — legacyHardwareToDpad covers that. Do not edit .kl scancode lines.
     * Note (hardware): stock Y2 wheel axis (LEFT/RIGHT vs UP/DOWN) still needs a device check
     * before splitting MODE_JJ by family — until then Y1+Y2 share this table.
     * Reversal: MEDIA_PLAY/PAUSE passthrough under MODE_JJ (breaks JJ/Innioasis scroll).
     */
    private static int mediaToJjDpad(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return 0;
            default:
                return 0;
        }
    }

    private static int mediaToAndroidDpad(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            default:
                return 0;
        }
    }

    private static int mediaToFmDpad(int keyCode, int repeatCount) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return KeyEvent.KEYCODE_VOLUME_DOWN;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return KeyEvent.KEYCODE_VOLUME_UP;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return repeatCount > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            default:
                return 0;
        }
    }

    /**
     * Synthetic DPAD tap to the focused (stock) window. Three tiers, fastest first:
     *   1. Direct InputManager inject — direct inject path, microseconds, but only when INJECT_EVENTS is
     *      actually granted (platform-signed Y1 ROM). ASYNC reports success even when denied, so we
     *      must gate on canInjectEvents up front rather than trusting the return.
     *   2. Session-scoped root injector daemon — Y2 fast path (VM already booted, ~ms per key).
     *   3. One-shot forked su + /system/bin/input — last resort (fresh VM, ~0.5-1s), rate-limited.
     */
    public static boolean injectClick(Context context, int keyCode) {
        if (context == null || keyCode <= 0) return false;
        if (isBlockedByGlobalOverlay()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("keyCode", keyCode);
                DebugInputLog.log("ExternalInputHandoff.injectClick",
                        "inject blocked overlay", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        if (OverlayKeyGate.isInPostOverlayCooldown()) {
            return false;
        }
        // Tier 1: privileged direct inject (Y1 platform-signed builds).
        if (canInjectEvents(context)) {
            long now = SystemClock.uptimeMillis();
            injectKeyEvent(context, syntheticDpadKeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode));
            injectKeyEvent(context, syntheticDpadKeyEvent(now + 20, now + 20, KeyEvent.ACTION_UP, keyCode));
            Log.d(TAG, "simulating dpad: " + keyCode);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("overlay", isBlockedByGlobalOverlay());
                DebugInputLog.log("ExternalInputHandoff.injectClick",
                        "inject ok", "H-SCROLL", d);
            } catch (Exception ignored) {}
            // #endregion
            return true;
        }
        // Tier 2: stream the keycode to the persistent root daemon (armed on handoff).
        if (RootKeyInjector.inject(keyCode)) {
            return true;
        }
        // Tier 3: forked su + /system/bin/input — never on the UI thread, one in flight, drop bursts.
        if (suInjectBusy) {
            Log.w(TAG, "su inject busy — dropped keyCode=" + keyCode);
            return false;
        }
        suInjectBusy = true;
        final int code = keyCode;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = RootShell.run("/system/bin/input keyevent " + code);
                    Log.i("SolarDbga4dee8", "inject via su fallback keyCode=" + code + " ok=" + ok);
                } finally {
                    suInjectBusy = false;
                }
            }
        }, "HandoffSuInject").start();
        return true;
    }

    /** One in-flight su inject max — wheel bursts must not queue seconds of forked VMs. */
    private static volatile boolean suInjectBusy;

    /** Cached INJECT_EVENTS check: 0=unknown, 1=granted, -1=denied. Probed once per process. */
    private static volatile int injectEventsGrant;

    /**
     * INJECT_EVENTS is signature-protected — granted only when the APK is platform-signed AND the
     * installed update matches the /system copy's signature. On the Y2 test flow (adb install of a
     * dev-signed build over the system app) it is silently missing, so direct ASYNC inject is
     * dropped by InputDispatcher while still returning success — hence the up-front gate.
     */
    private static boolean canInjectEvents(Context context) {
        int cached = injectEventsGrant;
        if (cached != 0) return cached > 0;
        boolean granted = false;
        try {
            granted = context.checkCallingOrSelfPermission("android.permission.INJECT_EVENTS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception ignored) {}
        injectEventsGrant = granted ? 1 : -1;
        Log.i("SolarDbga4dee8", "INJECT_EVENTS granted=" + granted);
        return granted;
    }

    /**
     * Boot-time prewarm: probe INJECT_EVENTS, cache InputManager reflection, and start the root
     * inject daemon when direct inject is denied (Y2 adb overlay). Idempotent — safe from
     * Application bootstrap and MainActivity. Avoids ~0.5–1s cold VM on the first wheel tick.
     */
    public static void warmInjector(Context context) {
        if (context == null) return;
        canInjectEvents(context);
        ensureInjectReflectionCached();
        if (injectEventsGrant > 0) return; // platform-signed Y1 — InputManager inject is already fast
        RootKeyInjector.start(context);
    }

    /** Reflection lookup once at boot — not on the first handoff keypress. */
    private static void ensureInjectReflectionCached() {
        if (injectInputEventMethod != null) return;
        try {
            injectInputEventMethod = InputManager.class.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
        } catch (Exception ignored) {}
    }

    /**
     * Handoff entry — ensure daemon is up (no-op if {@link #warmInjector} already ran at boot).
     * Platform-signed Y1 ROMs inject directly and skip the root VM entirely.
     */
    public static void armFastInjector(Context context) {
        warmInjector(context);
    }

    /** Daemon stays alive for the process lifetime — stopping it caused first-app handoff lag. */
    public static void disarmFastInjector() {
        // intentional no-op — RootKeyInjector is prewarmed at boot, not per handoff session
    }

    /** Match legacy synthetic DPAD tap shape. */
    private static KeyEvent syntheticDpadKeyEvent(long downTime, long eventTime, int action, int keyCode) {
        return new KeyEvent(downTime, eventTime, action, keyCode, 0);
    }

    /** Reflection Method cached after first lookup — no per-event getMethod cost. */
    private static volatile Method injectInputEventMethod;

    /** injectKeyEvent — getSystemService(INPUT_SERVICE), ASYNC mode, try/catch only. */
    private static void injectKeyEvent(Context context, KeyEvent event) {
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            Method injectInputEvent = injectInputEventMethod;
            if (injectInputEvent == null) {
                injectInputEvent = InputManager.class.getMethod(
                        "injectInputEvent", InputEvent.class, int.class);
                injectInputEventMethod = injectInputEvent;
            }
            injectInputEvent.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            if (!loggedInjectFailure) {
                loggedInjectFailure = true;
                Log.e(TAG, "Failed to inject key event", e);
            }
        }
    }
}
