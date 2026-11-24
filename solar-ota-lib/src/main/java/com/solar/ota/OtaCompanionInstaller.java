package com.solar.ota;

import android.content.Context;

import java.io.File;

/**
 * 2026-07-19 — Companion Rockbox/JJ install retired (Solar-only product).
 * Layman: Solar updates no longer download or stage Rockbox or JJ launchers.
 * Was: downloadAll + installDownloaded staged /system/app companions.
 * Reversal: restore fetch/install bodies from git history before this commit.
 * Technical: prepareAndInstall / install*Latest are no-ops returning false.
 */
public final class OtaCompanionInstaller {

    private OtaCompanionInstaller() {}

    public interface Progress {
        void onPhase(String label);
    }

    /** Outcome flags — Solar install must proceed regardless. */
    public static final class Result {
        public final boolean jjOk;

        public Result(boolean jjOk) {
            this.jjOk = jjOk;
        }
    }

    /** 2026-07-19 — No-op: companions are not installed with Solar OTA. */
    public static Result prepareAndInstall(Context context, File workDir, Progress progress) {
        return new Result(false);
    }

    /** 2026-07-19 — No-op download. */
    public static void downloadAll(Context context, File workDir, Progress progress) {
        // intentionally empty
    }

    /** 2026-07-19 — No-op install. */
    public static Result installDownloaded(Context context, File workDir, Progress progress) {
        return new Result(false);
    }

    /** 2026-07-19 — JJ install retired; returns false. */
    public static boolean installJjLatest(Context context, File workDir, Progress progress) {
        return false;
    }

    /** 2026-07-19 — Rockbox install retired; returns empty Result. */
    public static Result installRockboxLatest(Context context, File workDir, Progress progress) {
        return new Result(false);
    }

    /** 2026-07-19 — Presence-only: true if JJ already installed, never downloads. */
    public static boolean installJjIfNeeded(Context context, File workDir) {
        if (context == null) return false;
        return isPackageInstalled(context, OtaCompanionUrls.JJ_PACKAGE);
    }

    /** True when pm must not read apkPath directly (other apps' private storage). */
    static boolean needsPmStage(String apkPath) {
        return apkPath != null
                && (apkPath.startsWith("/data/data/") || apkPath.startsWith("/data/user/"));
    }

    private static boolean isPackageInstalled(Context context, String pkg) {
        if (context == null || pkg == null) return false;
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
