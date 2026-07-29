package com.solar.launcher.globalcontext;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.solar.input.policy.GlobalInputPolicy;

/**
 * 2026-07-05 — Monitors Solar crash streak; sets emergency_mode after reboot loop.
 * Layman: if Solar keeps crashing, flip a flag so HOME opens recovery instead of Solar.
 * Technical: reads persist.solar.crash_streak (SolarRecoveryCoordinator writes); :watchdog process.
 * Reversal: delete; SolarRecoveryCoordinator alone owns streak + emergency UX.
 */
public final class SolarCrashWatchdog extends Service {

    public static final String PROP_CRASH_STREAK = "persist.solar.crash_streak";
    public static final String PROP_EMERGENCY_MODE = "persist.solar.emergency_mode";
    /** Streak threshold before emergency_mode arms on next boot. */
    public static final int STREAK_THRESHOLD = 3;

    private static final long POLL_MS = 30000L;
    private static final long STABLE_CLEAR_MS = 120000L;

    private HandlerThread workerThread;
    private Handler worker;
    private long solarAliveSince;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollSolarHealth();
            if (worker != null) {
                worker.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("SolarCrashWatchdog");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.removeCallbacks(pollRunnable);
        }
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

    /** Layman: check if Solar process is up; clear streak after stable window. */
    private void pollSolarHealth() {
        boolean running = isSolarProcessRunning();
        long now = android.os.SystemClock.elapsedRealtime();
        if (running) {
            if (solarAliveSince == 0L) {
                solarAliveSince = now;
            } else if (now - solarAliveSince >= STABLE_CLEAR_MS) {
                clearCrashStreak();
            }
        } else {
            solarAliveSince = 0L;
        }
        applyEmergencyFromStreak();
    }

    /** After reboot, streak >= threshold → emergency_mode until user clears. */
    private void applyEmergencyFromStreak() {
        int streak = readStreak();
        if (streak >= STREAK_THRESHOLD) {
            SysPropHelper.set(PROP_EMERGENCY_MODE, "1");
        }
    }

    private void clearCrashStreak() {
        SysPropHelper.set(PROP_CRASH_STREAK, "0");
    }

    private int readStreak() {
        try {
            return Integer.parseInt(SysPropHelper.get(PROP_CRASH_STREAK, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isSolarProcessRunning() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "pidof " + GlobalInputPolicy.SOLAR_PKG});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            return line != null && line.trim().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
