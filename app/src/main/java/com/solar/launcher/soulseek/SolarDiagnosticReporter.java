package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.solar.launcher.ConnectivityHelper;
import com.solar.launcher.Debug843b96Log;
import com.solar.launcher.ReachPolicy;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Silently ships new/changed diagnostic logs to developer accounts via -diag PM session.
 * Startup / first-internet passes prioritize crash+logcat snapshots even when manifest matches.
 * Failures retry later; never surfaces errors to the user.
 */
public final class SolarDiagnosticReporter {
    public static final String PREF_DIAG_AUTO_REPORT = "solar_diag_auto_report";
    public static final String PREF_DIAG_SENT_MANIFEST = "solar_diag_sent_manifest";

    private static final long MIN_SCAN_INTERVAL_MS = 5L * 60L * 1000L;
    /** Wi‑Fi bounce reconnect — shorter than routine, still throttled. */
    private static final long MIN_RECONNECT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long SESSION_RETRY_MS = 120_000L;
    private static final int MAX_CHUNK_CHARS = 4000;
    private static final int LOGCAT_LINES = 200;
    private static final long[] BOOT_RETRY_MS = {3000L, 15000L, 60000L, 180000L};

    enum ScanMode {
        /** Cold start / first internet this process — ship crash+logcat even if manifest matches. */
        STARTUP,
        /** Periodic background pass — only new/changed files. */
        ROUTINE,
        /** Solar Development thread opened — full fresh bundle every time. */
        SUPPORT_OPEN
    }

    private static final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private static volatile long lastScanMs;
    private static volatile boolean bootScanPending = true;
    private static volatile boolean firstInternetScanDone;
    private static final AtomicInteger retryGeneration = new AtomicInteger();

    private SolarDiagnosticReporter() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_DIAG_AUTO_REPORT, true);
    }

    /** Background boot/Wi‑Fi shipping — experiment on and user toggle on. */
    private static boolean isBackgroundShippingAllowed(SharedPreferences prefs) {
        return isEnabled(prefs) && SolarDeveloperAccounts.isExperimentEnabled(prefs);
    }

    /**
     * Solar Development thread opened — always ship a full diagnostic bundle.
     * Independent of {@link #PREF_DIAG_AUTO_REPORT} (background-only toggle).
     */
    public static void shipOnDeveloperSupportOpen(final Context context, final SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        if (!SolarDeveloperAccounts.isExperimentEnabled(prefs)) return;
        if (!ReachPolicy.isMasterEnabled(prefs)) return;
        startScan(context, prefs, ScanMode.SUPPORT_OPEN);
    }

    /** Called from {@link com.solar.launcher.SolarApplication} on every process start. */
    public static void onProcessStart(final Context context) {
        bootScanPending = true;
        firstInternetScanDone = false;
        if (context == null) return;
        final Context app = context.getApplicationContext();
        for (final long delayMs : BOOT_RETRY_MS) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {}
                    if (!bootScanPending) return;
                    SharedPreferences prefs = app.getSharedPreferences("SOLAR_SETTINGS",
                            Context.MODE_PRIVATE);
                    if (!isBackgroundShippingAllowed(prefs) || !ReachPolicy.isMasterEnabled(prefs)) return;
                    if (!ConnectivityHelper.isOnline(app)) return;
                    onReachInternetAvailable(app, prefs);
                }
            }, "SolarDiagBoot").start();
        }
    }

    /**
     * Reach master on + internet available — start -diag session and ship logs.
     * First call each process run uses startup priority (post-crash log gather).
     */
    public static void onReachInternetAvailable(final Context context, final SharedPreferences prefs) {
        if (context == null || prefs == null || !isBackgroundShippingAllowed(prefs)) return;
        if (!ReachPolicy.isMasterEnabled(prefs)) return;
        SolarDiagSessionManager.ensureSession(context, prefs);
        if (bootScanPending || !firstInternetScanDone) {
            bootScanPending = false;
            firstInternetScanDone = true;
            startScan(context, prefs, ScanMode.STARTUP);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanMs < MIN_RECONNECT_INTERVAL_MS) return;
        startScan(context, prefs, ScanMode.ROUTINE);
    }

    /** @deprecated use {@link #onReachInternetAvailable} */
    public static void onWifiAvailable(final Context context, final SharedPreferences prefs) {
        onReachInternetAvailable(context, prefs);
    }

    /** Schedule a background scan when Reach is on Wi‑Fi (PM-only — no NAT peer gate). */
    public static void scheduleIfNeeded(final Context context, final SharedPreferences prefs) {
        if (context == null || prefs == null || !isBackgroundShippingAllowed(prefs)) return;
        if (!ReachPolicy.isMasterEnabled(prefs)) return;
        long now = System.currentTimeMillis();
        if (now - lastScanMs < MIN_SCAN_INTERVAL_MS) return;
        startScan(context, prefs, ScanMode.ROUTINE);
    }

    /** Force startup scan (e.g. after new crash). */
    public static void scheduleUrgent(final Context context, final SharedPreferences prefs) {
        if (context == null || prefs == null || !isBackgroundShippingAllowed(prefs)) return;
        lastScanMs = 0;
        startScan(context, prefs, ScanMode.STARTUP);
    }

    private static void startScan(final Context context, final SharedPreferences prefs,
            final ScanMode mode) {
        if (!scanRunning.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runScan(context.getApplicationContext(), prefs, mode);
                } finally {
                    scanRunning.set(false);
                    lastScanMs = System.currentTimeMillis();
                }
            }
        }, "SolarDiagReport").start();
    }

    static void runScan(Context context, SharedPreferences prefs, ScanMode mode) {
        SoulseekAccount main = SoulseekAccount.load(prefs, context);
        SolarDiagAccount diag = SolarDiagAccount.load(prefs, context);
        List<LogSource> sources = collectSources(context);
        JSONObject manifest = loadManifest(prefs);
        JSONObject updated = new JSONObject();
        if (!SolarDiagSessionManager.ensureSessionSync(context, prefs)) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("diagUser", diag.username);
                d.put("mode", mode.name());
                Debug843b96Log.log(context, "SolarDiagnosticReporter.runScan",
                        "diag session unavailable", "G-H", d);
            } catch (Exception ignored) {}
            // #endregion
            scheduleSessionRetry(context, prefs, mode);
            return;
        }
        try {
            Iterator<String> keys = manifest.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                updated.put(k, manifest.optLong(k, 0));
            }
            if (mode == ScanMode.STARTUP) {
                sendSessionBanner(context, prefs, main.username, diag.username, "session_start");
            } else if (mode == ScanMode.SUPPORT_OPEN) {
                sendSessionBanner(context, prefs, main.username, diag.username, "support_thread_open");
            }
            int shipped = 0;
            for (LogSource src : sources) {
                long mtime = src.file.lastModified();
                String key = src.file.getAbsolutePath();
                if (!shouldShipSource(src.label, manifest, key, mtime, mode)) continue;
                if (!sendSource(context, prefs, main.username, diag.username, src)) {
                    // Log the failure but do NOT abort sending other files
                    try {
                        JSONObject d = new JSONObject();
                        d.put("file", src.label);
                        Debug843b96Log.log(context, "SolarDiagnosticReporter.runScan", "sendSource failed", "H", d);
                    } catch (Exception ignored) {}
                }
                updated.put(key, mtime);
                shipped++;
            }
            prefs.edit().putString(PREF_DIAG_SENT_MANIFEST, updated.toString()).apply();
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("shipped", shipped);
                d.put("sources", sources.size());
                d.put("diagUser", diag.username);
                d.put("mode", mode.name());
                Debug843b96Log.log(context, "SolarDiagnosticReporter.runScan", "scan done", "H", d);
            } catch (Exception ignored) {}
            // #endregion
        } catch (Exception e) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                d.put("mode", mode.name());
                Debug843b96Log.log(context, "SolarDiagnosticReporter.runScan", "scan fail", "H", d);
            } catch (Exception ignored) {}
            // #endregion
            scheduleSessionRetry(context, prefs, mode);
        }
    }

    static boolean shouldShipSource(String label, JSONObject manifest, String path, long mtime,
            ScanMode mode) {
        if (mode == ScanMode.SUPPORT_OPEN) return true;
        if (mode == ScanMode.STARTUP && isPriorityStartupSource(label)) return true;
        return manifest.optLong(path, -1) != mtime;
    }

    /** Crash/logcat/debug files always ship on session startup — post-crash recovery path. */
    static boolean isPriorityStartupSource(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase(Locale.US);
        if (lower.contains("crash.log") || lower.contains("error.log")) return true;
        if (lower.contains("logcat") || lower.contains("snapshot")) return true;
        if (lower.startsWith("solar/debug-")) return true;
        return false;
    }

    private static void sendSessionBanner(Context context, SharedPreferences prefs,
            String mainUser, String diagUser, String event) {
        String body = SolarDeveloperAccounts.DIAG_MARKER
                + "event: " + event + "\n"
                + "user: " + (mainUser != null ? mainUser : "?") + "\n"
                + "diag: " + (diagUser != null ? diagUser : "?") + "\n"
                + "sdk: " + Build.VERSION.SDK_INT + "\n"
                + "model: " + Build.MODEL + "\n"
                + "time: " + System.currentTimeMillis() + "\n";
        String[] devs = SolarDeveloperAccounts.developerUsernames();
        com.solar.launcher.soulseek.SoulseekClient mainClient = null;
        try {
            mainClient = com.solar.launcher.MainActivity.getActiveSoulseekClient();
        } catch (Throwable ignored) {}
        if (mainClient != null && mainClient.isLoggedIn()) {
            SolarDeveloperMessaging.FanOutResult result =
                    SolarDeveloperMessaging.sendViaMainClient(mainClient, devs, body);
            if (result.allSucceeded()) return;
            devs = result.failedRecipients();
        }
        SolarDiagSessionManager.sendToRecipients(context, prefs, devs, body);
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
                if (!isEnabled(prefs) || !ReachPolicy.isMasterEnabled(prefs)) return;
                if (!ConnectivityHelper.isOnline(context)) return;
                startScan(context, prefs, mode);
            }
        }, "SolarDiagRetry").start();
    }

    private static boolean sendSource(Context context, SharedPreferences prefs, String mainUser,
            String diagUser, LogSource src) {
        String content;
        try {
            content = readFileTail(src.file, 256 * 1024);
        } catch (Exception e) {
            return false;
        }
        if (content == null || content.isEmpty()) return true;
        List<String> chunks = splitContent(content, MAX_CHUNK_CHARS);
        String pathLabel = src.file != null ? src.file.getName() : src.label;
        for (int i = 0; i < chunks.size(); i++) {
            String body = SolarDeveloperAccounts.DIAG_MARKER
                    + "user: " + mainUser + "\n"
                    + "diag: " + diagUser + "\n"
                    + "file: " + src.label + " (" + (i + 1) + "/" + chunks.size() + ")\n"
                    + "path: " + pathLabel + "\n"
                    + chunks.get(i);
            if (!sendDiagChunk(context, prefs, body)) {
                return false;
            }
        }
        return true;
    }

    /** Fan-out one diagnostic chunk; retry failed dev inboxes once. */
    private static boolean sendDiagChunk(Context context, SharedPreferences prefs, String body) {
        String[] devs = SolarDeveloperAccounts.developerUsernames();
        com.solar.launcher.soulseek.SoulseekClient mainClient = null;
        try {
            mainClient = com.solar.launcher.MainActivity.getActiveSoulseekClient();
        } catch (Throwable ignored) {}
        if (mainClient != null && mainClient.isLoggedIn()) {
            SolarDeveloperMessaging.FanOutResult result =
                    SolarDeveloperMessaging.sendViaMainClient(mainClient, devs, body);
            if (result.anySucceeded()) return true;
            devs = result.failedRecipients();
        }
        SolarDeveloperMessaging.FanOutResult result =
                SolarDiagSessionManager.sendToRecipients(context, prefs, devs, body);
        if (result.anySucceeded()) return true;
        String[] failed = result.failedRecipients();
        if (failed.length == 0) return false;
        SolarDeveloperMessaging.FanOutResult retry =
                SolarDiagSessionManager.sendToRecipients(context, prefs, failed, body);
        return retry.anySucceeded();
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
            byte[] buf = new byte[(int) Math.min(maxBytes, len)];
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
        List<LogSource> out = new ArrayList<LogSource>();
        addDebugLogs(new File("/storage/sdcard0"), out);
        addDebugLogs(new File("/storage/sdcard0/solar"), out);
        if (context != null) {
            addDebugLogs(context.getFilesDir(), out);
        }
        File solarLogs = new File("/storage/sdcard0/solar/logs");
        addIfFile(out, "SolarLog/crash.log", new File(solarLogs, "crash.log"));
        addIfFile(out, "SolarLog/error.log", new File(solarLogs, "error.log"));
        collectRockboxLogs(new File("/storage/sdcard0/.rockbox"), out, "Rockbox");
        addDeviceSnapshot(out);
        addLogcatSnapshot(out);
        return out;
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

    private static void collectRockboxLogs(File dir, List<LogSource> out, String prefix) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile() && dir.getName().endsWith(".log")) {
            out.add(new LogSource(prefix + "/" + dir.getName(), dir));
            return;
        }
        if (!dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            collectRockboxLogs(f, out, prefix + "/" + f.getName());
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
            w.write(sb.toString());
            w.close();
            out.add(new LogSource("Device/snapshot.txt", tmp));
        } catch (Exception ignored) {}
    }

    private static void addLogcatSnapshot(List<LogSource> out) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-d", "-t", String.valueOf(LOGCAT_LINES)
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
            w.write(sb.toString());
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
