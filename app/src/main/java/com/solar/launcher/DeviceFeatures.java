package com.solar.launcher;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Runtime Innioasis Y1/Y2 detection (MT6572 / MT6582) for a single universal APK. */
public final class DeviceFeatures {
    private static final String TAG = "DeviceFeatures";
    private static volatile String cachedFamily;

    private DeviceFeatures() {}

    public static String deviceFamily() {
        return detectFamily();
    }

    public static String deviceModel() {
        return isY2() ? "Y2" : "Y1";
    }

    public static String deviceModelLabel() {
        return isY2() ? "Y2" : "Y1";
    }

    public static int soulseekClientMinor() {
        // Reach-specific minor under experimental major 177 (not Nicotine+ 160.x).
        return isY2() ? 102 : 101;
    }

    public static String reachClientName() {
        return "Reach for Innioasis " + deviceModelLabel();
    }

    /** Soulseek profile description peers see — reflects Sharing / Messaging toggles. */
    public static String reachUserBio(android.content.Context ctx, boolean sharingEnabled,
            boolean messagingEnabled) {
        if (ctx == null) return reachClientName();
        String intro = ctx.getString(com.solar.launcher.R.string.reach_user_bio_intro, deviceModelLabel());
        String sharingState = ctx.getString(sharingEnabled
                ? com.solar.launcher.R.string.common_on : com.solar.launcher.R.string.common_off);
        String messagingState = ctx.getString(messagingEnabled
                ? com.solar.launcher.R.string.common_on : com.solar.launcher.R.string.common_off);
        String status = ctx.getString(com.solar.launcher.R.string.reach_user_bio_status,
                sharingState, messagingState);
        String shareNote = ctx.getString(sharingEnabled
                ? com.solar.launcher.R.string.reach_user_bio_note_share_on
                : com.solar.launcher.R.string.reach_user_bio_note_share_off);
        String msgNote = ctx.getString(messagingEnabled
                ? com.solar.launcher.R.string.reach_user_bio_note_msg_on
                : com.solar.launcher.R.string.reach_user_bio_note_msg_off);
        return intro + "\n\n" + status + "\n\n" + shareNote + " " + msgNote;
    }

    public static boolean isY2() {
        return "y2".equals(detectFamily());
    }

    public static boolean isY1() {
        return !isY2();
    }

    static void resetCacheForTest() {
        cachedFamily = null;
    }

    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt, String model) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model);
    }

    private static String detectFamily() {
        if (cachedFamily != null) return cachedFamily;
        synchronized (DeviceFeatures.class) {
            if (cachedFamily != null) return cachedFamily;
            String board = (Build.HARDWARE != null ? Build.HARDWARE : "")
                    + " " + (Build.BOARD != null ? Build.BOARD : "");
            cachedFamily = probeFamily(readProcCpuHardware(), board, Build.VERSION.SDK_INT,
                    Build.MODEL != null ? Build.MODEL : "");
            try {
                Log.i(TAG, "detected device family: " + cachedFamily
                        + " hw=" + Build.HARDWARE + " board=" + Build.BOARD
                        + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
            } catch (Throwable ignored) {}
            return cachedFamily;
        }
    }

    private static String probeFamily(String cpuHardware, String boardHardware, int sdkInt, String model) {
        String cpu = cpuHardware != null ? cpuHardware.toLowerCase(Locale.US) : "";
        String board = boardHardware != null ? boardHardware.toLowerCase(Locale.US) : "";
        if (cpu.contains("mt6582") || board.contains("mt6582")) return "y2";
        if (cpu.contains("mt6572") || board.contains("mt6572")) return "y1";
        if (sdkInt >= 19) return "y2";
        if (sdkInt <= 16) return "y1";
        String m = model != null ? model.toLowerCase(Locale.US) : "";
        if (m.contains("y2")) return "y2";
        if (m.contains("y1")) return "y1";
        return "y1";
    }

    private static String readProcCpuHardware() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("hardware")) {
                    int colon = trimmed.indexOf(':');
                    if (colon >= 0 && colon + 1 < trimmed.length()) {
                        return trimmed.substring(colon + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
