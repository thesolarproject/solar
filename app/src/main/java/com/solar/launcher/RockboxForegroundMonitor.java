package com.solar.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 2026-07-06 — Tracks Rockbox/JJ foreground; pauses Solar playback and arms wheel handoff.
 * Layman: when another HOME app is on screen, Solar stops music and translates wheel keys.
 * Technical: polls fg package; Rockbox suspends playback, JJ arms MODE_JJ without MainActivity.
 * Reversal: stop ensureStarted; Solar may keep playing under alternate HOME launchers.
 */
public final class RockboxForegroundMonitor implements Runnable {

    private static final long POLL_MS = 400L;
    private static final long POLL_MS_DEFAULT = 500L;
    /** Solar owns screen — handoff idle; avoid AMS probes during USB modal (2026-07-06). */
    private static final long POLL_MS_SOLAR_FOREGROUND = 5000L;
    private static volatile boolean started;
    private static volatile String lastForeground;

    private final Context appContext;
    private final Handler handler;

    private RockboxForegroundMonitor(Context context) {
        this.appContext = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Start once from main-process bootstrap. */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        if (started) return;
        // 2026-10-03 — SolarApplication bootstrap also runs in :watchdog, where MainActivity.instance
        // is always null: the solar-focus idle never applied and it hit getRunningTasks every 500ms
        // (~2 AMS probes/s on Y1 with Solar on screen). The main-process monitor covers handoff.
        if (!isMainProcess()) return;
        synchronized (RockboxForegroundMonitor.class) {
            if (started) return;
            started = true;
        }
        RockboxForegroundMonitor monitor = new RockboxForegroundMonitor(context);
        monitor.handler.post(monitor);
    }

    /** 2026-07-06 — Detect main-thread fg probe storms (session 86bbe0). */
    private long lastPollAtMs;

    @Override
    public void run() {
        // 2026-07-06 — Overlay owns keys — skip AMS fg probes (session 86bbe0 hang fix).
        if (OverlayKeyGate.isOverlayKeysActive()) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("skipped", true);
                d.put("overlayActive", true);
                d.put("nextDelayMs", POLL_MS_SOLAR_FOREGROUND);
                Debug86bbe0Log.log("RockboxForegroundMonitor.run", "overlay idle", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            handler.postDelayed(this, POLL_MS_SOLAR_FOREGROUND);
            return;
        }
        // Skip fg probes only when USB Mass Storage is actively running (kernel mode).
        if (UsbMassStorageController.isKernelMassStorageMode()) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("skipped", true);
                d.put("usbSessionIdle", true);
                d.put("nextDelayMs", POLL_MS_SOLAR_FOREGROUND);
                Debug86bbe0Log.log("RockboxForegroundMonitor.run", "usb idle", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            handler.postDelayed(this, POLL_MS_SOLAR_FOREGROUND);
            return;
        }
        long pollStart = System.currentTimeMillis();
        MainActivity solar = MainActivity.instance;
        // 2026-07-06 — Solar home + USB modal: skip JJ/Rockbox handoff polls entirely.
        if (solar != null && solar.hasWindowFocus()) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("skipped", true);
                d.put("solarHasFocus", true);
                d.put("nextDelayMs", POLL_MS_SOLAR_FOREGROUND);
                Debug86bbe0Log.log("RockboxForegroundMonitor.run", "solar fg idle", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            lastForeground = "com.solar.launcher";
            handler.postDelayed(this, POLL_MS_SOLAR_FOREGROUND);
            return;
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("sinceLastMs", lastPollAtMs > 0L ? pollStart - lastPollAtMs : 0L);
            d.put("mainActivityAlive", solar != null);
            d.put("solarHome", LauncherPreference.isSolarHome(appContext));
            Debug86bbe0Log.log("RockboxForegroundMonitor.run", "poll tick", "H2", d);
        } catch (Exception ignored) {}
        lastPollAtMs = pollStart;
        // #endregion
        String fg = ExternalInputHandoff.getForegroundPackageName(appContext);
        boolean fgChanged = fg != null && !fg.equals(lastForeground);
        if (fgChanged) {
            if (LauncherSwitch.ROCKBOX_PACKAGE.equals(fg)) {
                MainActivity activity = MainActivity.instance;
                if (activity != null) {
                    activity.suspendForRockboxForeground();
                }
            }
        }
        // 2026-07-08 — JJ + Stock Innioasis share MODE_JJ; reclaim MEDIA_BUTTON each poll (H2).
        // Was: JJ_PACKAGE.equals only — Innioasis relied on chance armForForegroundPackage.
        if (com.solar.input.policy.GlobalInputPolicy.isJjKeylayoutLauncher(fg)) {
            MediaButtonRegistrar.ensureRegistered(appContext);
            ExternalInputHandoff.armJjShim(appContext);
            // #region agent log
            if (fgChanged) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("fg", fg);
                    d.put("dpadMode", ExternalInputHandoff.getDpadMode());
                    DebugE93bdbLog.log("RockboxForegroundMonitor.run",
                            "JJ-keylayout fg armed handoff", "H3", d);
                } catch (Exception ignored) {}
            }
            // #endregion
        } else if (LauncherSwitch.ROCKBOX_PACKAGE.equals(fg)) {
            MediaButtonRegistrar.ensureRegistered(appContext);
            ExternalInputHandoff.armForForegroundPackage(appContext);
        }
        lastForeground = fg;
        long delay = isAlternateHomeTarget(appContext) ? POLL_MS : POLL_MS_DEFAULT;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("fg", fg);
            d.put("fgChanged", fgChanged);
            d.put("pollCostMs", System.currentTimeMillis() - pollStart);
            d.put("nextDelayMs", delay);
            Debug86bbe0Log.log("RockboxForegroundMonitor.run", "poll done", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        handler.postDelayed(this, delay);
    }

    /** Faster poll when JJ/Rockbox/Stock is effective HOME — catch first wheel ticks sooner. */
    private static boolean isAlternateHomeTarget(Context context) {
        String target = LauncherPreference.getHomeTarget(context);
        return LauncherDefault.TARGET_JJ.equals(target)
                || LauncherDefault.TARGET_ROCKBOX.equals(target)
                || LauncherDefault.TARGET_STOCK.equals(target);
    }

    /** 2026-10-03 — Main app process has no ':' suffix; /proc read avoids an AMS round-trip. */
    private static boolean isMainProcess() {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return true;
            for (int i = 0; i < n && buf[i] != 0; i++) {
                if (buf[i] == ':') return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Test hook — reset poll state between cases. */
    static void resetForTest() {
        lastForeground = null;
    }
}
