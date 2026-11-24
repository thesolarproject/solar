package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.solar.launcher.ConnectivityHelper;
import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.SolarLog;
import com.solar.launcher.SolarLogPaths;
import com.solar.launcher.diag.SolarDiagClient;
import com.solar.launcher.diag.SolarDiagContextCollector;
import com.solar.launcher.diag.SolarDiagFeatureLog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2026-07-16 — Ships crash/error logs to the solar-diag worker (TLS 1.2).
 * 2026-07-19 — No boot/Wi‑Fi-connect/remote auto ships (flooded GitHub).
 * 2026-07-20 — Lifecycle flush: power / restart / Wi‑Fi-off + Report Issue.
 * Ship is always fail-open: collect what you can, skip the rest, never toast errors.
 * Power/Report Issue may silently wake Wi‑Fi; if online never arrives, skip and continue.
 * Was: USER_REPORT only after lean-down. Reversal: block POWER/RESTART/WIFI_OFF again in allowsShipMode.
 */
public final class SolarDiagnosticReporter {
    public static final String PREF_DIAG_AUTO_REPORT = "solar_diag_auto_report";
    public static final String PREF_DIAG_SENT_MANIFEST = "solar_diag_sent_manifest";
    public static final String PREF_FEATURE_COOLDOWN = "solar_diag_feature_cooldown";

    /** Min gap between Wi‑Fi-connect ships (frequent enough, not thrashy). */
    private static final long MIN_WIFI_SHIP_INTERVAL_MS = 45L * 60L * 1000L;
    private static final long SESSION_RETRY_MS = 30L * 60L * 1000L;
    private static final long FEATURE_ERROR_COOLDOWN_MS = 6L * 60L * 60L * 1000L;
    /** Silent radio wake budget before power ship (rest of ~12s wall is HTTPS). */
    private static final long POWER_WIFI_WAKE_WAIT_MS = 6_000L;
    /** HTTPS wait after optional wake on power path. */
    private static final long POWER_SHIP_AFTER_WAKE_MS = 6_000L;
    /** Silent wake budget for Report Issue when radio was off. */
    private static final long USER_REPORT_WIFI_WAKE_WAIT_MS = 10_000L;
    /** Max wait before Wi‑Fi radio is allowed to drop (keep UX snappy). */
    private static final long WIFI_OFF_SHIP_TIMEOUT_MS = 8_000L;
    private static final int LOGCAT_LINES_FULL = 200;
    private static final int MAX_FILE_BYTES = 48 * 1024;
    private static final int MAX_FILE_BYTES_FULL = 160 * 1024;
    private static final int MAX_TOTAL_BYTES = 200 * 1024;
    private static final int MAX_TOTAL_BYTES_FULL = 750 * 1024;
    /** Boot/startup ship retries while waiting for connectivity. */
    private static final long[] BOOT_RETRY_MS = {90_000L, 240_000L};

    public enum ScanMode {
        STARTUP,
        /** @deprecated unused — kept so old callers compile; no-ops if started. */
        ROUTINE,
        /** @deprecated no longer auto-shipped on thread open. */
        SUPPORT_OPEN,
        /** @deprecated 2026-07-19 — remote pull no longer ships; use Report Issue UI. */
        REMOTE_PULL,
        /** User typed a Report Issue message (only allowed ship path). */
        USER_REPORT,
        /** Wi‑Fi association while auto-report is on (light). */
        WIFI,
        /** Flush before radio off (user or auto sleep policy) — light, time-boxed. */
        WIFI_OFF,
        /** User chose Shut Down from on-device menus. */
        POWER_OFF,
        /** User chose Restart from on-device menus. */
        RESTART
    }

    public interface RemotePullCallback {
        void onComplete(boolean ok, int issueNumber, String htmlUrl, String error);
    }

    private static final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private static volatile long lastScanMs;
    private static volatile boolean bootScanPending = true;
    private static volatile boolean firstInternetScanDone;
    private static final AtomicInteger retryGeneration = new AtomicInteger();
    private static final Map<String, Long> featureCooldown = new HashMap<String, Long>();

    private SolarDiagnosticReporter() {}

    /**
     * 2026-07-19 — Pref-gated background auto-report always off.
     * Layman: no quiet phone-home on a schedule; Report Issue + power/Wi‑Fi-off flush only.
     * Was: default true. Reversal: return prefs.getBoolean(PREF, true).
     */
    public static boolean isEnabled(SharedPreferences prefs) {
        return false;
    }

    private static boolean isBackgroundShippingAllowed(SharedPreferences prefs) {
        return false;
    }

    /**
     * 2026-07-20 — Modes allowed to POST to solar-diag (online required at ship time).
     * Layman: you Send logs, or Solar flushes once before sleep/power/Wi‑Fi drop — not every boot.
     * Was: USER_REPORT only. Reversal: return mode == USER_REPORT.
     */
    static boolean allowsShipMode(ScanMode mode) {
        return mode == ScanMode.USER_REPORT
                || mode == ScanMode.POWER_OFF
                || mode == ScanMode.RESTART
                || mode == ScanMode.WIFI_OFF;
    }

    /**
     * 2026-07-20 — When offline, briefly power Wi‑Fi (silent UI) to try a ship.
     * Layman: turn the radio on quietly for shutdown / Report Issue; Wi‑Fi-off already has a link.
     * Was: only ship if already online. Reversal: return false always.
     */
    static boolean shouldAttemptSilentWifiWake(ScanMode mode) {
        return mode == ScanMode.USER_REPORT
                || mode == ScanMode.POWER_OFF
                || mode == ScanMode.RESTART;
    }

    /**
     * 2026-07-20 — Ship errors stay in the feature ring; never toast the user.
     * Layman: a missed upload must not pop an error while shutting down or sleeping Wi‑Fi.
     */
    static boolean shipFailsSilently(ScanMode mode) {
        return mode == ScanMode.USER_REPORT
                || mode == ScanMode.POWER_OFF
                || mode == ScanMode.RESTART
                || mode == ScanMode.WIFI_OFF
                || mode == ScanMode.WIFI
                || mode == ScanMode.STARTUP
                || mode == ScanMode.ROUTINE
                || mode == ScanMode.SUPPORT_OPEN
                || mode == ScanMode.REMOTE_PULL;
    }

    /**
     * 2026-07-20 — Toast while power prep runs (ship if link comes up, then reboot/power-off).
     * Layman: screen says Restarting or Shutting down — not a vague "getting ready".
     */
    static int powerPrepToastRes(boolean restart) {
        return restart
                ? com.solar.launcher.R.string.diag_restarting
                : com.solar.launcher.R.string.diag_shutting_down;
    }

    /**
     * @deprecated Opening the Solar Development thread no longer ships diagnostics.
     * Prefer {@link #shipUserReport} when the user actually sends a message.
     */
    public static void shipOnDeveloperSupportOpen(final Context context, final SharedPreferences prefs) {
        // No-op: ship-on-open caused routine-like storms when users browsed the thread.
    }

    /**
     * User Report Issue — full diagnostics + optional quoted text.
     * Always allowed (does not require auto-report pref); needs network.
     */
    public static void shipUserReport(final Context context, final SharedPreferences prefs,
            final String userMessage, final RemotePullCallback callback) {
        if (context == null) {
            if (callback != null) callback.onComplete(false, 0, "", "no_context");
            return;
        }
        final Context app = context.getApplicationContext();
        final SharedPreferences p = prefs != null ? prefs
                : app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        startScan(app, p, ScanMode.USER_REPORT, null, callback,
                userMessage != null ? userMessage : "");
    }

    /**
     * 2026-07-19 — Remote pull no longer creates GitHub issues (opt-in Report Issue only).
     * Layman: developers cannot force an upload by messaging the device.
     * Reversal: restore startScan(REMOTE_PULL, …).
     */
    public static void shipOnRemoteDiagCommand(final Context context, final SharedPreferences prefs,
            final String replyToDev, final RemotePullCallback callback) {
        if (callback != null) {
            callback.onComplete(false, 0, "", "remote_pull_disabled");
        }
        try {
            SolarDiagFeatureLog.event("diag", "remote_pull rejected — user opt-in only");
        } catch (Throwable ignored) {}
    }

    public static void reportFeatureError(Context context, String feature, String message,
            Throwable t) {
        if (feature == null || feature.trim().isEmpty()) feature = "app";
        final String key = feature.trim().toLowerCase(Locale.US);
        long now = System.currentTimeMillis();
        synchronized (featureCooldown) {
            Long last = featureCooldown.get(key);
            if (last != null && now - last < FEATURE_ERROR_COOLDOWN_MS) return;
            featureCooldown.put(key, now);
        }
        // Feature errors only log locally; ships on next event (startup/wifi/power/user report).
        if (context == null) return;
        SolarDiagFeatureLog.event("diag", "feature_error queued " + key);
    }

    public static void onProcessStart(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        // Cheap local ring only — no network, no boot scan retries (2026-07-19 opt-in only).
        try {
            SolarDiagFeatureLog.event("app", "process_start sdk=" + Build.VERSION.SDK_INT
                    + " model=" + Build.MODEL
                    + (hasRecentCrashLog() ? " crash_pending=1" : "")
                    + " online=" + ConnectivityHelper.isOnline(app)
                    + " auto_ship=off");
        } catch (Throwable ignored) {}
    }

    public static void onReachInternetAvailable(final Context context, final SharedPreferences prefs) {
        // 2026-07-19 — No auto STARTUP/WIFI ship.
        if (context == null) return;
        if (!firstInternetScanDone) firstInternetScanDone = true;
    }

    public static void onWifiAvailable(final Context context, final SharedPreferences prefs) {
        if (context == null) return;
        // Silent one-liner to SolarDev removed from auto path with diag lean-down;
        // ImpactPing can stay for Reach presence without GitHub.
        SolarDeveloperImpactPing.wifiConnected(context);
    }

    /**
     * @deprecated Periodic routine ships removed — use event hooks only.
     * Kept as a no-op so older call sites do not reintroduce spam.
     */
    public static void scheduleIfNeeded(final Context context, final SharedPreferences prefs) {
        // Intentionally empty: diagnostics are event-bundled only.
    }

    /** 2026-07-19 — Urgent startup ship disabled; use Report Issue. */
    public static void scheduleUrgent(final Context context, final SharedPreferences prefs) {
        try {
            SolarDiagFeatureLog.event("diag", "scheduleUrgent ignored — user opt-in only");
        } catch (Throwable ignored) {}
    }

    /**
     * User chose Shut Down / Restart: toast, time-boxed Cloudflare ship (may silent-wake Wi‑Fi), then powerAction.
     * 2026-07-20 — Always attempt ship: wake radio if offline; if link never comes up, skip silently and power.
     * Layman: say Restarting/Shutting down, quietly try Wi‑Fi + upload, then power — never stuck on failure.
     * Was: online-only ship (no wake). Reversal: if (!isOnline) skip awaitShip; no SolarSilentWifi.
     */
    public static void runWithPowerDiagPrep(final Context context, final boolean restart,
            final Runnable powerAction) {
        if (context == null) {
            if (powerAction != null) powerAction.run();
            return;
        }
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs =
                app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        final int toastRes = powerPrepToastRes(restart);
        try {
            // Toast on main — overlay confirm may already be main; keep safe from other callers.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(app, toastRes,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
        final ScanMode mode = restart ? ScanMode.RESTART : ScanMode.POWER_OFF;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Silent wake if radio off; ship if online; never toast ship errors. 2026-07-20
                    com.solar.launcher.SolarSilentWifi.runWithOptionalWake(app,
                            POWER_WIFI_WAKE_WAIT_MS, new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (ConnectivityHelper.isOnline(app)) {
                                            awaitShip(app, prefs, mode, POWER_SHIP_AFTER_WAKE_MS);
                                        } else {
                                            SolarDiagFeatureLog.event("diag",
                                                    mode.name().toLowerCase(Locale.US)
                                                            + "_offline_skip");
                                        }
                                    } catch (Throwable shipErr) {
                                        try {
                                            SolarDiagFeatureLog.warn("diag", "power_prep ship "
                                                    + shipErr.getMessage());
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            });
                    // 2026-07-20 — Do not call notifyDevelopersPoweredOff here: sync Soulseek PMs
                    // could hang and leave the device stuck on Restarting… forever.
                    // Reversal: notifyDevelopersPoweredOff(app, prefs, restart) before powerAction.
                } catch (Throwable e) {
                    try {
                        SolarDiagFeatureLog.warn("diag", "power_prep " + e.getMessage());
                    } catch (Throwable ignored) {}
                } finally {
                    if (powerAction != null) {
                        try {
                            powerAction.run();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }, restart ? "SolarPowerRestartDiag" : "SolarPowerOffDiag");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * Before Wi‑Fi radio off (user toggle or auto sleep): light Cloudflare flush while online.
     * 2026-07-20 — Time-boxed ship then drop radio. Was: ImpactPing only (lean-down).
     * Layman: last chance to send logs before Wi‑Fi sleeps or you turn it off.
     * Reversal: disableWifi immediately after toast; no awaitShip.
     *
     * @param userVisible when true, show a neutral "Disconnecting…" toast
     */
    public static void runBeforeWifiDisable(final Context context, final boolean userVisible,
            final Runnable disableWifi) {
        if (context == null) {
            if (disableWifi != null) disableWifi.run();
            return;
        }
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs =
                app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        SolarDeveloperImpactPing.wifiDisconnecting(app);
        if (userVisible) {
            try {
                android.widget.Toast.makeText(app,
                        com.solar.launcher.R.string.toast_wifi_disconnecting,
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }
        if (!ConnectivityHelper.isOnline(app)) {
            if (disableWifi != null) disableWifi.run();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    awaitShip(app, prefs, ScanMode.WIFI_OFF, WIFI_OFF_SHIP_TIMEOUT_MS);
                } catch (Throwable e) {
                    // 2026-07-20 — Fail silent; radio still drops in finally.
                    try {
                        SolarDiagFeatureLog.warn("diag", "wifi_off_prep " + e.getMessage());
                    } catch (Throwable ignored) {}
                } finally {
                    if (disableWifi != null) {
                        try {
                            disableWifi.run();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }, "SolarWifiOffDiag");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** Time-boxed ship for power / wifi-off prep (does not block forever). */
    private static void awaitShip(Context app, SharedPreferences prefs, ScanMode mode,
            long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        startScan(app, prefs, mode, null, new RemotePullCallback() {
            @Override
            public void onComplete(boolean ok, int issueNumber, String htmlUrl, String error) {
                latch.countDown();
            }
        }, null);
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    /** Silent Soulseek PMs to developer accounts — never stored in virtual conversation. */
    static void notifyDevelopersPoweredOff(Context context, SharedPreferences prefs,
            boolean restart) {
        if (context == null || prefs == null) return;
        // 2026-07-17 — Soulseek off: no power-off PM sockets (opt-in only).
        if (!com.solar.launcher.ReachPolicy.allowsBackgroundSoulseekWork(prefs)) return;
        try {
            SoulseekAccount acct = SoulseekAccount.load(prefs, context);
            String username = acct != null ? acct.username : "";
            String body = SolarDeveloperAccounts.formatPoweredOffNotice(username, restart);
            String[] devs = SolarDeveloperAccounts.developerUsernames();
            SoulseekClient client = null;
            try {
                client = com.solar.launcher.MainActivity.getActiveSoulseekClient();
            } catch (Throwable ignored) {}
            if (client != null && client.isLoggedIn()) {
                for (int i = 0; i < devs.length; i++) {
                    if (devs[i] == null || devs[i].isEmpty()) continue;
                    try {
                        client.sendPrivateMessageSync(devs[i], body);
                    } catch (Exception ignored) {}
                    if (i + 1 < devs.length) {
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                    }
                }
                return;
            }
            // Fallback: -diag session (no local thread append).
            SolarDiagSessionManager.sendToRecipients(context, prefs, devs, body);
        } catch (Exception e) {
            SolarDiagFeatureLog.warn("diag", "power_notice " + e.getMessage());
        }
    }

    private static void scheduleBootScan(final Context context, final SharedPreferences prefs) {
        if (!bootScanPending) return;
        bootScanPending = false;
        if (!isBackgroundShippingAllowed(prefs)) return;
        final int gen = retryGeneration.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (long delay : BOOT_RETRY_MS) {
                    if (gen != retryGeneration.get()) return;
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!ConnectivityHelper.isOnline(context)) continue;
                    // If first-online already shipped STARTUP, skip duplicate.
                    if (firstInternetScanDone && !hasRecentCrashLog()) return;
                    startScan(context, prefs, ScanMode.STARTUP, null, null, null);
                    return;
                }
            }
        }, "SolarDiagBoot").start();
    }

    private static void startScan(final Context context, final SharedPreferences prefs,
            final ScanMode mode, final String replyToDev, final RemotePullCallback callback,
            final String userMessage) {
        if (context == null || prefs == null) {
            if (callback != null) callback.onComplete(false, 0, "", "bad_args");
            return;
        }
        if (!scanRunning.compareAndSet(false, true)) {
            if (callback != null) callback.onComplete(false, 0, "", "busy");
            return;
        }
        lastScanMs = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 2026-07-20 — Catch Throwable: uncaught on this thread hits SolarLog → process death.
                    // Layman: a bad log upload must fail quietly, not kill Solar or toast.
                    // Was: bare try/finally — RuntimeException/Error crashed the app on ship.
                    // Reversal: drop catch; only finally scanRunning=false.
                    runScan(context, prefs, mode, replyToDev, callback, userMessage);
                } catch (Throwable t) {
                    try {
                        SolarDiagFeatureLog.warn("diag", "scan_crash mode=" + mode
                                + " " + (t.getMessage() != null ? t.getMessage()
                                : t.getClass().getSimpleName()));
                    } catch (Throwable ignored) {}
                    if (callback != null) {
                        try {
                            String err = t.getMessage() != null ? t.getMessage()
                                    : t.getClass().getSimpleName();
                            callback.onComplete(false, 0, "", err);
                        } catch (Throwable ignored) {}
                    }
                } finally {
                    scanRunning.set(false);
                }
            }
        }, "SolarDiagScan");
        // Background event ships yield CPU to UI/audio; user/remote keep default priority.
        if (mode != ScanMode.USER_REPORT && mode != ScanMode.REMOTE_PULL) {
            t.setPriority(Thread.MIN_PRIORITY);
        }
        t.start();
    }

    static void runScan(Context context, SharedPreferences prefs, ScanMode mode,
            String replyToDev, RemotePullCallback callback, String userMessage) {
        if (!SolarDiagClient.isConfigured()) {
            if (callback != null) callback.onComplete(false, 0, "", "not_configured");
            return;
        }
        // 2026-07-20 — USER_REPORT + power / Wi‑Fi-off flush; boot/wifi-connect/remote stay blocked.
        // Was: USER_REPORT only (isUserOptInShip). Reversal: if (mode != USER_REPORT) return disabled.
        if (!allowsShipMode(mode)) {
            if (callback != null) callback.onComplete(false, 0, "", "disabled");
            return;
        }

        // Full bundle for Report Issue / remote; light env for power and Wi‑Fi-off.
        boolean full = mode == ScanMode.USER_REPORT
                || mode == ScanMode.REMOTE_PULL
                || mode == ScanMode.SUPPORT_OPEN;
        // Never toggle Wi‑Fi here for WIFI_OFF (already online). Power / Report Issue may silent-wake.
        if (!ConnectivityHelper.isOnline(context)) {
            if (shouldAttemptSilentWifiWake(mode)) {
                // 2026-07-20 — Quiet radio on; if DHCP never lands, skip ship (no toast).
                // Was: immediate offline return. Reversal: drop wake; callback offline immediately.
                final boolean fullFinal = full;
                com.solar.launcher.SolarSilentWifi.runWithOptionalWake(context,
                        mode == ScanMode.USER_REPORT
                                ? USER_REPORT_WIFI_WAKE_WAIT_MS
                                : POWER_WIFI_WAKE_WAIT_MS,
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (ConnectivityHelper.isOnline(context)) {
                                        runScanOnline(context, prefs, mode, replyToDev,
                                                callback, fullFinal, userMessage);
                                    } else {
                                        SolarDiagFeatureLog.warn("diag", mode.name()
                                                + " offline_after_wake — ship skipped");
                                        if (callback != null) {
                                            callback.onComplete(false, 0, "", "offline");
                                        }
                                    }
                                } catch (Throwable t) {
                                    try {
                                        SolarDiagFeatureLog.warn("diag", "wake_ship "
                                                + t.getMessage());
                                    } catch (Throwable ignored) {}
                                    if (callback != null) {
                                        try {
                                            callback.onComplete(false, 0, "", "offline");
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        });
                return;
            }
            if (callback != null) callback.onComplete(false, 0, "", "offline");
            SolarDiagFeatureLog.warn("diag", mode.name() + " offline — ship deferred");
            return;
        }
        runScanOnline(context, prefs, mode, replyToDev, callback, full, userMessage);
    }

    private static void runScanOnline(Context context, SharedPreferences prefs, ScanMode mode,
            String replyToDev, RemotePullCallback callback, boolean full, String userMessage) {
        try {
            runScanOnlineBody(context, prefs, mode, replyToDev, callback, full, userMessage);
        } catch (Throwable t) {
            // 2026-07-20 — Any unexpected throw: log + silent callback; never toast / never kill.
            try {
                SolarDiagFeatureLog.warn("diag", "scan_online_crash mode=" + mode.name()
                        + " " + (t.getMessage() != null ? t.getMessage()
                        : t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            if (callback != null) {
                try {
                    callback.onComplete(false, 0, "", "scan_error");
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Collect whatever we can and POST; individual probes already fail-open. 2026-07-20 */
    private static void runScanOnlineBody(Context context, SharedPreferences prefs, ScanMode mode,
            String replyToDev, RemotePullCallback callback, boolean full, String userMessage) {
        SoulseekAccount main = null;
        try {
            main = SoulseekAccount.load(prefs, context);
        } catch (Throwable ignored) {}
        List<LogSource> sources;
        try {
            sources = collectSources(context, prefs, full);
        } catch (Throwable t) {
            sources = new ArrayList<LogSource>();
            try {
                SolarDiagFeatureLog.warn("diag", "collectSources " + t.getMessage());
            } catch (Throwable ignored) {}
        }
        JSONObject manifest = loadManifest(prefs);
        JSONObject updated = new JSONObject();
        try {
            Iterator<String> keys = manifest.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                updated.put(k, manifest.optLong(k, 0));
            }
        } catch (Exception ignored) {}

        int maxTotal = full ? MAX_TOTAL_BYTES_FULL : MAX_TOTAL_BYTES;
        int maxFile = full ? MAX_FILE_BYTES_FULL : MAX_FILE_BYTES;
        List<SolarDiagClient.FilePart> parts = new ArrayList<SolarDiagClient.FilePart>();
        int budget = maxTotal;

        String userMsg = mode == ScanMode.USER_REPORT ? userMessage : null;
        if (userMsg != null && !userMsg.isEmpty()) {
            parts.add(new SolarDiagClient.FilePart("Diag/user-message.txt", userMsg));
            budget -= userMsg.length();
        }

        String env;
        try {
            // 2026-07-20 — Env dump fail-open: ship logs even if a probe throws.
            // Was: bare collectEnvironment → BT getType Error aborted whole USER_REPORT.
            // Reversal: call collect* directly without try/catch stub.
            env = full
                    ? SolarDiagContextCollector.collectEnvironment(context)
                    : SolarDiagContextCollector.collectEnvironmentLight(context);
        } catch (Throwable t) {
            env = "=== Solar diagnostic environment ===\n"
                    + "detail: unavailable\n"
                    + "skip_reason: " + t.getClass().getSimpleName()
                    + ": " + (t.getMessage() != null ? t.getMessage() : "") + "\n";
        }
        parts.add(new SolarDiagClient.FilePart("Diag/environment.txt", env));
        budget -= env.length();
        // Full ARL dump only on user report / remote pull / crash — routine gets redacted.
        String account;
        try {
            account = full
                    ? SolarDiagContextCollector.collectAccountContext(context, prefs)
                    : SolarDiagContextCollector.collectAccountContextLight(context, prefs);
        } catch (Throwable t) {
            account = "=== Account / ARL context (diagnostic) ===\n"
                    + "detail: unavailable\n"
                    + "skip_reason: " + t.getClass().getSimpleName() + "\n";
        }
        parts.add(new SolarDiagClient.FilePart("Diag/account-context.txt", account));
        budget -= account.length();
        try {
            String ring = SolarDiagFeatureLog.dumpRing();
            if (ring != null && !ring.isEmpty()) {
                parts.add(new SolarDiagClient.FilePart("Diag/feature-ring.txt", ring));
                budget -= ring.length();
            }
        } catch (Throwable ignored) {}

        boolean forceAll = mode == ScanMode.REMOTE_PULL
                || mode == ScanMode.USER_REPORT
                || mode == ScanMode.SUPPORT_OPEN
                || mode == ScanMode.POWER_OFF
                || mode == ScanMode.RESTART
                || mode == ScanMode.WIFI_OFF;
        int shippedFiles = 0;
        boolean shippedCrashPriority = false;
        for (LogSource src : sources) {
            if (src == null || src.file == null || !src.file.isFile()) continue;
            long mtime = src.file.lastModified();
            String key = src.file.getAbsolutePath();
            int cap = Math.min(maxFile, Math.max(0, budget));
            if (cap < 1024) break;
            String content;
            try {
                content = readFileTail(src.file, cap);
            } catch (Throwable e) {
                continue;
            }
            if (content == null || content.isEmpty()) {
                try {
                    updated.put(key, mtime);
                } catch (Exception ignored) {}
                continue;
            }
            try {
                content = SolarLog.scrub(context, content);
            } catch (Throwable ignored) {}
            String fp = contentFingerprint(content);
            if (!forceAll && !shouldShipSource(src.label, manifest, key, mtime, mode, fp)) {
                continue;
            }
            parts.add(new SolarDiagClient.FilePart(src.label, content));
            budget -= content.length();
            shippedFiles++;
            if (isPriorityStartupSource(src.label)
                    && src.label.toLowerCase(Locale.US).contains("crash.log")) {
                shippedCrashPriority = true;
            }
            try {
                updated.put(key, mtime);
                if (fp != null && !fp.isEmpty()) updated.put(fpKey(key), fp);
            } catch (Exception ignored) {}
        }

        // Connect/startup with nothing new: skip HTTPS (env-only issues were flooding solar-diag).
        // 2026-07-16 — Also skip when crash.log exists but fingerprint already shipped
        // (was: hasRecentCrashLog forced empty crash issues every boot for 48h).
        // WIFI_OFF still ships light env/ring so pre-sleep flush always has a heartbeat.
        if ((mode == ScanMode.ROUTINE || mode == ScanMode.WIFI || mode == ScanMode.STARTUP)
                && shippedFiles == 0) {
            SolarDiagFeatureLog.event("diag", mode.name().toLowerCase(Locale.US) + "_skip no_new_logs");
            if (callback != null) callback.onComplete(true, 0, "", "skipped_empty");
            return;
        }

        String type = typeForMode(mode, sources);
        String feature = "";
        String trigger = triggerForMode(mode);
        String usernameForIssue = null;
        String titleHint = null;
        if (mode == ScanMode.REMOTE_PULL) {
            type = "diag_pull";
            trigger = "remote_pull";
            usernameForIssue = main != null ? main.username : null;
        } else if (mode == ScanMode.USER_REPORT) {
            type = "user_report";
            trigger = "user_message";
            usernameForIssue = main != null ? main.username : null;
            titleHint = titleFromUserMessage(userMsg);
        } else if (mode == ScanMode.STARTUP && hasRecentCrashLog()) {
            type = "crash";
            trigger = "crash";
        } else if (mode == ScanMode.WIFI) {
            type = "wifi";
            trigger = "wifi_connect";
        } else if (mode == ScanMode.WIFI_OFF) {
            type = "wifi";
            trigger = "wifi_off";
        } else if (mode == ScanMode.POWER_OFF) {
            type = "power";
            trigger = "power_off";
        } else if (mode == ScanMode.RESTART) {
            type = "power";
            trigger = "restart";
        }

        String summary = "mode=" + mode.name() + " files=" + shippedFiles
                + " sdk=" + Build.VERSION.SDK_INT
                + " model=" + DeviceFeatures.deviceModelLabel()
                + " family=" + DeviceFeatures.deviceFamily();
        if (userMsg != null && !userMsg.isEmpty()) {
            String oneLine = userMsg.replace('\n', ' ').trim();
            if (oneLine.length() > 200) oneLine = oneLine.substring(0, 200) + "…";
            summary = summary + "\nuser_message: " + oneLine;
        }
        JSONObject device;
        try {
            device = SolarDiagContextCollector.deviceJson(context);
        } catch (Throwable t) {
            device = new JSONObject();
        }
        SolarDiagClient.Result result;
        try {
            result = SolarDiagClient.submit(
                    type, feature, trigger, usernameForIssue, device, summary, titleHint,
                    userMsg, parts);
        } catch (Throwable t) {
            result = SolarDiagClient.Result.fail(t.getMessage() != null
                    ? t.getMessage() : "submit_error");
        }

        if (result.ok) {
            try {
                prefs.edit().putString(PREF_DIAG_SENT_MANIFEST, updated.toString()).apply();
            } catch (Throwable ignored) {}
            SolarDiagFeatureLog.event("diag", "shipped issue=" + result.issueNumber
                    + " mode=" + mode.name());
            // 2026-07-16 — After a successful crash payload ship, rotate crash.log so
            // hasRecentCrashLog() + priority re-upload do not flood every boot for 48h.
            // Reversal: delete rotate call; keep crash.log forever until user clears logs.
            if (shippedCrashPriority
                    || (mode == ScanMode.STARTUP && "crash".equals(type))) {
                try {
                    rotateShippedCrashLog();
                } catch (Throwable t) {
                    SolarDiagFeatureLog.warn("diag", "crash_rotate " + t.getMessage());
                }
            }
        } else {
            // shipFailsSilently — ring only; power/wifi-off/user never toast. 2026-07-20
            SolarDiagFeatureLog.warn("diag", "ship_failed mode=" + mode.name()
                    + " err=" + result.error
                    + (shipFailsSilently(mode) ? " silent=1" : ""));
            if (mode != ScanMode.REMOTE_PULL && mode != ScanMode.USER_REPORT
                    && mode != ScanMode.WIFI_OFF && mode != ScanMode.POWER_OFF
                    && mode != ScanMode.RESTART) {
                scheduleSessionRetry(context, prefs, mode);
            }
        }

        if (callback != null) {
            callback.onComplete(result.ok, result.issueNumber, result.htmlUrl, result.error);
        }

        if (mode == ScanMode.REMOTE_PULL && replyToDev != null && !replyToDev.isEmpty()) {
            sendDiagConfirmation(replyToDev, result);
        }
    }

    private static String titleFromUserMessage(String msg) {
        if (msg == null) return null;
        String t = msg.trim().replace('\n', ' ');
        if (t.isEmpty()) return null;
        if (t.length() > 80) t = t.substring(0, 80) + "…";
        return t;
    }

    private static void sendDiagConfirmation(String replyToDev, SolarDiagClient.Result result) {
        try {
            SoulseekClient client = null;
            try {
                client = com.solar.launcher.MainActivity.getActiveSoulseekClient();
            } catch (Throwable ignored) {}
            if (client == null || !client.isLoggedIn()) return;
            boolean ok = result != null && result.ok;
            int num = result != null ? result.issueNumber : 0;
            String text = SolarDeveloperAccounts.formatDiagConfirmation(ok, num);
            client.sendPrivateMessageSync(replyToDev, text);
        } catch (Exception ignored) {}
    }

    private static String typeForMode(ScanMode mode, List<LogSource> sources) {
        if (mode == ScanMode.STARTUP) return hasRecentCrashLog() ? "crash" : "startup";
        if (mode == ScanMode.REMOTE_PULL) return "diag_pull";
        if (mode == ScanMode.USER_REPORT) return "user_report";
        if (mode == ScanMode.WIFI || mode == ScanMode.WIFI_OFF) return "wifi";
        if (mode == ScanMode.POWER_OFF || mode == ScanMode.RESTART) return "power";
        if (mode == ScanMode.SUPPORT_OPEN) return "other";
        return "other";
    }

    private static String triggerForMode(ScanMode mode) {
        if (mode == ScanMode.REMOTE_PULL) return "remote_pull";
        if (mode == ScanMode.USER_REPORT) return "user_message";
        if (mode == ScanMode.WIFI) return "wifi_connect";
        if (mode == ScanMode.WIFI_OFF) return "wifi_off";
        if (mode == ScanMode.POWER_OFF) return "power_off";
        if (mode == ScanMode.RESTART) return "restart";
        if (mode == ScanMode.STARTUP) return hasRecentCrashLog() ? "crash" : "startup";
        return "event";
    }

    private static boolean hasRecentCrashLog() {
        long window = 48L * 60L * 60L * 1000L; // 48h — recent enough to care, not forever
        long now = System.currentTimeMillis();
        File dir = SolarLogPaths.preferredLogDir(null);
        File crash = new File(dir, "crash.log");
        return crash.isFile() && now - crash.lastModified() < window;
    }

    /**
     * Whether this log file should go out on this scan.
     * 2026-07-16 — Priority crash/error no longer force-reship every STARTUP when the
     * content fingerprint is unchanged (stops solar-diag issue floods).
     * Reversal: STARTUP + priority always return true.
     */
    static boolean shouldShipSource(String label, JSONObject manifest, String path, long mtime,
            ScanMode mode) {
        return shouldShipSource(label, manifest, path, mtime, mode, null);
    }

    static boolean shouldShipSource(String label, JSONObject manifest, String path, long mtime,
            ScanMode mode, String contentFingerprint) {
        if (mode == ScanMode.SUPPORT_OPEN || mode == ScanMode.REMOTE_PULL
                || mode == ScanMode.USER_REPORT || mode == ScanMode.WIFI_OFF
                || mode == ScanMode.POWER_OFF || mode == ScanMode.RESTART) {
            return true;
        }
        if (mode == ScanMode.STARTUP && isPriorityStartupSource(label)) {
            if (contentFingerprint != null && !contentFingerprint.isEmpty() && manifest != null) {
                String prev = manifest.optString(fpKey(path), "");
                if (contentFingerprint.equals(prev)) return false;
                return true;
            }
            // No fingerprint yet: fall back to mtime (changed = ship).
            return manifest == null || manifest.optLong(path, -1) != mtime;
        }
        return manifest == null || manifest.optLong(path, -1) != mtime;
    }

    static String fpKey(String path) {
        return (path != null ? path : "") + "#fp";
    }

    /** Length + rolling hash of content — stable for identical crash tails. */
    static String contentFingerprint(String content) {
        if (content == null || content.isEmpty()) return "0:0";
        int len = content.length();
        int h = 0;
        // Sample head + tail so huge rotated logs still change when a new crash is prepended/appended.
        int step = Math.max(1, len / 256);
        for (int i = 0; i < len; i += step) {
            h = 31 * h + content.charAt(i);
        }
        h = 31 * h + content.charAt(len - 1);
        return len + ":" + Integer.toHexString(h);
    }

    /**
     * Move crash.log → crash.log.shipped after a successful crash ship.
     * Keeps forensic tail on disk without re-triggering hasRecentCrashLog every boot.
     */
    static void rotateShippedCrashLog() {
        File dir = SolarLogPaths.preferredLogDir(null);
        if (dir == null) return;
        File crash = new File(dir, "crash.log");
        if (!crash.isFile()) return;
        File shipped = new File(dir, "crash.log.shipped");
        if (shipped.exists()) {
            // Keep previous shipped as .old once.
            File old = new File(dir, "crash.log.shipped.old");
            if (old.exists()) old.delete();
            shipped.renameTo(old);
        }
        if (!crash.renameTo(shipped)) {
            // Fallback: truncate in place so mtime/window clears.
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(crash, false);
                out.close();
            } catch (Exception ignored) {}
        }
    }

    static boolean isPriorityStartupSource(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase(Locale.US);
        // Keep startup priority tight — crash/error only (not full logcat every boot).
        if (lower.contains("crash.log") || lower.contains("error.log")) return true;
        if (lower.contains("storage.log")) return true;
        return false;
    }

    private static void scheduleSessionRetry(final Context context, final SharedPreferences prefs,
            final ScanMode mode) {
        final int gen = retryGeneration.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SESSION_RETRY_MS);
                } catch (InterruptedException ignored) {}
                if (gen != retryGeneration.get()) return;
                if (mode == ScanMode.ROUTINE && !isEnabled(prefs)) return;
                if (!ConnectivityHelper.isOnline(context)) return;
                startScan(context, prefs, mode, null, null, null);
            }
        }, "SolarDiagRetry").start();
    }

    static List<String> splitContent(String content, int maxChars) {
        List<String> out = new ArrayList<String>();
        if (content == null || content.isEmpty()) return out;
        if (content.length() <= maxChars) {
            out.add(content);
            return out;
        }
        for (int i = 0; i < content.length(); i += maxChars) {
            out.add(content.substring(i, Math.min(content.length(), i + maxChars)));
        }
        return out;
    }

    private static JSONObject loadManifest(SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString(PREF_DIAG_SENT_MANIFEST, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String readFileTail(File f, int maxBytes) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            long len = f.length();
            long skip = len > maxBytes ? len - maxBytes : 0;
            if (skip > 0) in.skip(skip);
            byte[] buf = new byte[(int) Math.min(maxBytes, len > 0 ? len : maxBytes)];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    static final class LogSource {
        final String label;
        final File file;

        LogSource(String label, File file) {
            this.label = label;
            this.file = file;
        }
    }

    static List<LogSource> collectSources(Context context) {
        return collectSources(context, null, false);
    }

    static List<LogSource> collectSources(Context context, SharedPreferences prefs) {
        return collectSources(context, prefs, false);
    }

    static List<LogSource> collectSources(Context context, SharedPreferences prefs, boolean full) {
        List<LogSource> out = new ArrayList<LogSource>();
        // Preferred log dir first (app-private) — avoid walking every volume on light ships.
        File preferred = SolarLogPaths.preferredLogDir(context);
        addIfFile(out, "SolarLog/crash.log", new File(preferred, "crash.log"));
        addIfFile(out, "SolarLog/error.log", new File(preferred, "error.log"));
        addIfFile(out, "SolarLog/storage.log", new File(preferred, "storage.log"));
        addLogTree(new File(preferred, "features"), out, "SolarLog/features");
        if (full) {
            addIfFile(out, "SolarLog/crash.log.old", new File(preferred, "crash.log.old"));
            addIfFile(out, "SolarLog/error.log.old", new File(preferred, "error.log.old"));
            int vol = 1;
            for (File logDir : SolarLogPaths.logDirs(context)) {
                if (logDir.getAbsolutePath().equals(preferred.getAbsolutePath())) continue;
                String prefix = "SolarLog/vol" + vol;
                addIfFile(out, prefix + "/crash.log", new File(logDir, "crash.log"));
                addIfFile(out, prefix + "/error.log", new File(logDir, "error.log"));
                vol++;
            }
            for (File root : DeviceFeatures.getStorageRoots()) {
                if (root == null) continue;
            }
            addDeviceSnapshot(out);
            // logcat -d is expensive on KitKat; only full ships (user report / pull / crash).
            addLogcatSnapshot(out, LOGCAT_LINES_FULL);
        }
        // Light/routine: no logcat snapshot — ring + crash/error tails are enough.
        return out;
    }

    private static void addLogTree(File dir, List<LogSource> out, String prefix) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile() && dir.getName().endsWith(".log")) {
            out.add(new LogSource(prefix + "/" + dir.getName(), dir));
            return;
        }
        if (!dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f == null) continue;
            if (f.isDirectory()) {
                addLogTree(f, out, prefix + "/" + f.getName());
            } else if (f.isFile() && (f.getName().endsWith(".log")
                    || f.getName().endsWith(".log.old")
                    || f.getName().endsWith(".txt"))) {
                out.add(new LogSource(prefix + "/" + f.getName(), f));
            }
        }
    }

    private static void addDebugLogs(File dir, List<LogSource> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isFile() && f.getName().startsWith("debug-") && f.getName().endsWith(".log")) {
                out.add(new LogSource("Solar/" + f.getName(), f));
            }
        }
    }

    private static void addIfFile(List<LogSource> out, String label, File f) {
        if (f != null && f.isFile()) out.add(new LogSource(label, f));
    }

    private static void addDeviceSnapshot(List<LogSource> out) {
        StringBuilder sb = new StringBuilder();
        sb.append("model: ").append(Build.MODEL).append('\n');
        sb.append("device: ").append(Build.DEVICE).append('\n');
        sb.append("brand: ").append(Build.BRAND).append('\n');
        sb.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("release: ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        sb.append("proc: ").append(readOneLine("/proc/version")).append('\n');
        File tmp = new File("/data/data/com.solar.launcher/cache/solar_device_snapshot.txt");
        try {
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            w.write(SolarLog.scrub(sb.toString()));
            w.close();
            out.add(new LogSource("Device/snapshot.txt", tmp));
        } catch (Exception ignored) {}
    }

    private static void addLogcatSnapshot(List<LogSource> out, int lines) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-d", "-t", String.valueOf(lines > 0 ? lines : LOGCAT_LINES_FULL)
            });
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            File tmp = new File("/data/data/com.solar.launcher/cache/solar_logcat_snapshot.txt");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            w.write(SolarLog.scrub(sb.toString()));
            w.close();
            out.add(new LogSource("Android/logcat.txt", tmp));
        } catch (Exception ignored) {}
    }

    private static String readOneLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
            String line = br.readLine();
            br.close();
            return line != null ? line : "";
        } catch (Exception e) {
            return "";
        }
    }
}
