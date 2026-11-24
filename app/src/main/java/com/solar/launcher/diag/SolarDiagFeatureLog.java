package com.solar.launcher.diag;

import android.content.Context;
import android.util.Log;

import com.solar.launcher.SolarLog;
import com.solar.launcher.SolarLogPaths;
import com.solar.launcher.soulseek.SolarDiagnosticReporter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 2026-07-16 — Lightweight feature breadcrumbs for diagnostics.
 * Hot path: in-memory ring only. Disk writes are throttled and primary-dir only.
 */
public final class SolarDiagFeatureLog {
    private static final String TAG = "SolarDiagFeature";
    /** @deprecated use {@link SolarLogPaths#preferredFeatureLogDir} */
    public static final String FEATURE_LOG_DIR = SolarLogPaths.LEGACY_LOG_DIR + "/features";
    private static final int MAX_RING = 80;
    private static final long MAX_FILE_BYTES = 128 * 1024;
    /** At most one feature-file disk write every 2s (all features share budget). */
    private static final long DISK_MIN_INTERVAL_MS = 2000L;
    private static final Object LOCK = new Object();
    private static final ConcurrentLinkedQueue<String> RING = new ConcurrentLinkedQueue<String>();
    private static final AtomicLong lastDiskWriteMs = new AtomicLong(0);
    private static volatile Context appCtx;

    private SolarDiagFeatureLog() {}

    public static void init(Context context) {
        if (context != null) appCtx = context.getApplicationContext();
    }

    /** Feature lifecycle — memory ring only (no disk). */
    public static void event(String feature, String message) {
        write("INFO", feature, message, null, false);
    }

    /** Non-fatal problem — ring only. */
    public static void warn(String feature, String message) {
        write("WARN", feature, message, null, false);
    }

    /** Feature failure — ring + throttled disk + optional ship. */
    public static void error(String feature, String message, Throwable t) {
        write("ERROR", feature, message, t, true);
        SolarLog.eQuiet("Diag/" + (feature != null ? feature : "unknown"), message, t);
        SolarDiagnosticReporter.reportFeatureError(appCtx, feature, message, t);
    }

    public static void error(Context context, String feature, String message, Throwable t) {
        if (context != null) appCtx = context.getApplicationContext();
        write("ERROR", feature, message, t, true);
        SolarLog.eQuiet("Diag/" + (feature != null ? feature : "unknown"), message, t);
        SolarDiagnosticReporter.reportFeatureError(context, feature, message, t);
    }

    /**
     * From SolarLog.e — memory ring only (never disk on the error hot path).
     */
    public static void trailFromSolarLog(String level, String tag, String message) {
        String feature = featureFromTag(tag);
        write(level != null ? level : "INFO", feature, message, null, false);
    }

    private static String featureFromTag(String tag) {
        if (tag == null || tag.isEmpty()) return "app";
        String t = tag.trim();
        if (t.startsWith("Diag/")) t = t.substring(5);
        String lower = t.toLowerCase(Locale.US);
        if (lower.contains("soulseek") || lower.contains("reach")) return "reach";
        if (lower.contains("deezer")) return "deezer";
        if (lower.contains("podcast")) return "podcasts";
        if (lower.contains("wifi") || lower.contains("wlan")) return "wifi";
        if (lower.contains("playback") || lower.contains("media") || lower.contains("player")) {
            return "playback";
        }
        if (lower.contains("theme")) return "theme";
        if (lower.contains("radio") || lower.contains("fm")) return "radio";
        if (lower.contains("youtube") || lower.contains("yt")) return "youtube";
        if (lower.contains("ota") || lower.contains("update")) return "ota";
        if (lower.contains("overlay")) return "overlay";
        if (lower.contains("platform") || lower.contains("xposed")) return "platform";
        if (t.length() > 24) return t.substring(0, 24);
        return t;
    }

    private static void write(String level, String feature, String message, Throwable t,
            boolean allowDisk) {
        String feat = feature != null ? feature.trim() : "app";
        if (feat.isEmpty()) feat = "app";
        String msg = message != null ? message : "";
        msg = SolarLog.scrub(msg);
        String line = ts() + " " + level + " [" + feat + "] " + msg;
        if (t != null) {
            String em = t.getMessage();
            if (em != null) line += " :: " + SolarLog.scrub(em);
            line += " :: " + t.getClass().getSimpleName();
        }
        // Logcat only at debug-ish volume — use Log.d to avoid flooding main logcat with INFO.
        if ("ERROR".equals(level)) {
            Log.e(TAG, line);
        } else if ("WARN".equals(level)) {
            Log.w(TAG, line);
        } else {
            Log.d(TAG, line);
        }
        pushRing(line);
        if (allowDisk) {
            maybeAppendDisk(feat, line);
        }
        if (t != null && allowDisk) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                String stack = SolarLog.scrub(sw.toString());
                if (stack.length() > 800) stack = stack.substring(0, 800);
                pushRing(stack);
                maybeAppendDisk(feat, stack);
            } catch (Exception ignored) {}
        }
    }

    private static void pushRing(String line) {
        RING.add(line);
        while (RING.size() > MAX_RING) {
            RING.poll();
        }
    }

    private static void maybeAppendDisk(String feature, String line) {
        long now = System.currentTimeMillis();
        long last = lastDiskWriteMs.get();
        if (now - last < DISK_MIN_INTERVAL_MS) return;
        if (!lastDiskWriteMs.compareAndSet(last, now)) return;
        synchronized (LOCK) {
            try {
                String safe = feature.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (safe.length() > 40) safe = safe.substring(0, 40);
                SolarLogPaths.appendFeaturePrimary(appCtx, safe + ".log", line, MAX_FILE_BYTES);
            } catch (Exception ex) {
                Log.w(TAG, "append failed: " + ex.getMessage());
            }
        }
    }

    public static String dumpRing() {
        StringBuilder sb = new StringBuilder();
        for (String s : RING) {
            if (s != null) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static List<File> listFeatureLogFiles() {
        List<File> out = new ArrayList<File>();
        File dir = SolarLogPaths.preferredFeatureLogDir(appCtx);
        if (dir == null || !dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            if (f != null && f.isFile() && f.getName().endsWith(".log")) out.add(f);
        }
        return out;
    }

    private static String ts() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
