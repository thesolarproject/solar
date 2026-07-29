package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * 2026-07-05 — Thin recovery policy: crash streak, emergency boot gate, overlay/hold health probe.
 * Layman: tracks crash loops and keeps overlay/rescue helpers alive without duplicating rescue scripts.
 * Technical: writes crash_streak only when companion APK absent; companion owns streak when installed.
 * Reversal: remove class; {@link SolarLog} and boot paths call helpers directly again.
 */
public final class SolarRecoveryCoordinator {

    static final String PROP_CRASH_STREAK = "persist.solar.crash_streak";
    static final String PROP_EMERGENCY_MODE = "persist.solar.emergency_mode";
    static final String PROP_RECOVERY_LAST = "persist.solar.recovery_action";
    static final String PROP_PLATFORM_DEGRADED = "persist.solar.platform.degraded";

    private static final String PREFS = "solar_recovery";
    private static final String KEY_CRASH_STREAK = "crash_streak";
    private static final String KEY_EMERGENCY_MODE = "emergency_mode";
    private static final String KEY_DEGRADED_BANNER_DISMISSED = "platform_degraded_banner_dismissed";
    private static final String COMPANION_PKG = "com.solar.launcher.globalcontext";

    private static final int CRASH_STREAK_EMERGENCY = 3;
    private static final long HEALTH_PROBE_MS = 15_000L;
    private static final long STABLE_UPTIME_CLEAR_MS = 30_000L;

    private static volatile boolean healthProbeScheduled;
    private static volatile long processStartElapsed;
    private static volatile long mainResumeElapsed;
    /** 2026-07-05 — lazy init so JVM unit tests can load class without Looper. */
    private static Handler mainHandler;

    private static Handler mainHandlerOrNull() {
        if (mainHandler != null) return mainHandler;
        Looper looper = Looper.getMainLooper();
        if (looper == null) return null;
        mainHandler = new Handler(looper);
        return mainHandler;
    }

    private SolarRecoveryCoordinator() {}

    /** Application onCreate — start overlay/hold health probe on main process. */
    public static void onProcessStart(final Context context) {
        if (context == null) return;
        processStartElapsed = SystemClock.elapsedRealtime();
        scheduleHealthProbe(context.getApplicationContext());
    }

    /** Bump crash streak — rolling 2 min window may trigger recovery overlay via hooks. */
    public static void onUncaughtCrash(Context context) {
        if (context == null) return;
        com.solar.home.policy.LauncherErrorRecoveryPolicy.recordCrashInWindow(
                HomeTargetPolicy.SOLAR_PKG);
        if (isCompanionInstalled(context)) return;
        SharedPreferences prefs = prefs(context);
        int streak = prefs.getInt(KEY_CRASH_STREAK, 0) + 1;
        prefs.edit().putInt(KEY_CRASH_STREAK, streak).apply();
        syncProp(PROP_CRASH_STREAK, String.valueOf(streak));
        markRecoveryAction("crash_streak_" + streak);
        if (streak >= CRASH_STREAK_EMERGENCY) {
            prefs.edit().putBoolean(KEY_EMERGENCY_MODE, true).apply();
            syncProp(PROP_EMERGENCY_MODE, "1");
            markRecoveryAction("emergency_armed");
        }
    }

    /** MainActivity onResume — clear streak after 30s stable uptime. */
    public static void onMainActivityResume(Context context) {
        if (context == null) return;
        mainResumeElapsed = SystemClock.elapsedRealtime();
        Handler handler = mainHandlerOrNull();
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (SystemClock.elapsedRealtime() - mainResumeElapsed < STABLE_UPTIME_CLEAR_MS - 500L) {
                    return;
                }
                if (SystemClock.elapsedRealtime() - processStartElapsed < STABLE_UPTIME_CLEAR_MS) {
                    return;
                }
                clearCrashStreak(context);
            }
        }, STABLE_UPTIME_CLEAR_MS);
    }

    /** Boot gate — skip heavy Solar bootstrap when emergency flag set. */
    public static boolean isEmergencyMode(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_EMERGENCY_MODE, false)) return true;
        return "1".equals(readProp(PROP_EMERGENCY_MODE));
    }

    /** Settings repair or manual clear — drop emergency + streak. */
    public static void clearEmergencyState(Context context) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit()
                .remove(KEY_EMERGENCY_MODE)
                .remove(KEY_CRASH_STREAK)
                .apply();
        syncProp(PROP_EMERGENCY_MODE, "0");
        syncProp(PROP_CRASH_STREAK, "0");
        markRecoveryAction("emergency_cleared");
    }

    /** Platform prep found gaps — non-blocking home banner. */
    public static void setPlatformDegraded(Context context, boolean degraded) {
        if (context == null) return;
        syncProp(PROP_PLATFORM_DEGRADED, degraded ? "1" : "0");
        if (!degraded) {
            prefs(context).edit().remove(KEY_DEGRADED_BANNER_DISMISSED).apply();
        }
        markRecoveryAction(degraded ? "platform_degraded" : "platform_ok");
    }

    public static boolean isPlatformDegraded(Context context) {
        if (context == null) return false;
        return "1".equals(readProp(PROP_PLATFORM_DEGRADED));
    }

    /**
     * Home "platform repair" banner — always off (2026-07-11).
     * Silent prep still runs; there is no user-facing repair product, and the banner was confusing.
     */
    public static boolean shouldShowPlatformDegradedBanner(Context context) {
        return false;
    }

    public static void dismissPlatformDegradedBanner(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_DEGRADED_BANNER_DISMISSED, true).apply();
    }

    /** After SolarRestart — re-arm USB/BT recovery helpers that die with the process. */
    public static void onSolarRestart(Context context) {
        if (context == null) return;
        markRecoveryAction("solar_restart");
        UsbRecoveryAgent.ensureRunning(context);
        BluetoothAudioRepair.requestAutoRepair(context, null);
        SolarOverlayHost.ensureStarted(context);
        SolarRescueHoldHost.ensureStarted(context);
    }

    /** Idempotent 15s overlay/rescue keepalive — low stakes, fail-open. */
    public static void scheduleHealthProbe(final Context context) {
        if (context == null || healthProbeScheduled) return;
        healthProbeScheduled = true;
        Handler handler = mainHandlerOrNull();
        if (handler == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                runHealthProbe(context.getApplicationContext());
                Handler h = mainHandlerOrNull();
                if (h != null) h.postDelayed(this, HEALTH_PROBE_MS);
            }
        });
    }

    private static void runHealthProbe(Context context) {
        OverlayKeyGate.disarmStaleIfNeeded(context);
        if (!isOverlayProcessRunning(context)) {
            SolarOverlayHost.ensureStarted(context);
        }
        SolarRescueHoldHost.ensureStarted(context);
    }

    static boolean isCompanionInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(COMPANION_PKG, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static int crashStreakForTest(Context context) {
        return prefs(context).getInt(KEY_CRASH_STREAK, 0);
    }

    static boolean shouldEnterEmergencyForTest(int streak) {
        return streak >= CRASH_STREAK_EMERGENCY;
    }

    private static void clearCrashStreak(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt(KEY_CRASH_STREAK, 0) == 0) return;
        prefs.edit().remove(KEY_CRASH_STREAK).apply();
        syncProp(PROP_CRASH_STREAK, "0");
        markRecoveryAction("crash_streak_cleared");
    }

    static void markRecoveryAction(String action) {
        if (action == null) action = "";
        String stamp = System.currentTimeMillis() + ":" + action;
        syncProp(PROP_RECOVERY_LAST, stamp);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void syncProp(String key, String value) {
        String v = value != null ? value : "0";
        // 2026-07-14 — Prefer SystemProperties.set; async su if set rejected (A5 su can stall).
        // Was: RootShell.run(setprop) blocked forever when su hung.
        // Reversal: RootShell.run("setprop …") only.
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, v);
            return;
        } catch (Throwable ignored) {}
        RootShell.runAsync("setprop " + key + " " + v);
    }

    /**
     * 2026-07-14 — Read persist props without su.
     * Was: RootShell.runCapture(getprop) — blocked MainActivity.onCreate on A5 (su hang).
     * Now: android.os.SystemProperties.get only; empty on miss.
     * Reversal: return RootShell.runCapture("getprop " + key).
     */
    private static String readProp(String key) {
        if (key == null || key.length() == 0) return "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v != null ? String.valueOf(v).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isOverlayProcessRunning(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo p
                        : am.getRunningAppProcesses()) {
                    if ("com.solar.launcher:overlay".equals(p.processName)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
