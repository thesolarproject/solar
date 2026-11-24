package com.solar.launcher;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

/**
 * Snapshots the foreground task when the global overlay arms and restores it on dismiss
 * unless the user explicitly chose Go Home / Now Playing / Switch to Solar.
 */
public final class OverlayForegroundGuard {

    private static volatile int snapshotTaskId = -1;
    private static volatile String snapshotPackage;
    private static volatile boolean userRequestedSolarNav;

    private OverlayForegroundGuard() {}

    /** Record top task before overlay steals keys — called from {@link OverlayKeyGate#arm}. */
    public static void snapshotOnArm(Context ctx) {
        userRequestedSolarNav = false;
        snapshotTaskId = -1;
        snapshotPackage = null;
        if (ctx == null) return;
        RunningTaskSnapshot snap = readTopTask(ctx);
        if (snap == null) return;
        if (GlobalOverlayPolicy.isSolarForegroundPackage(snap.packageName)) return;
        snapshotTaskId = snap.taskId;
        snapshotPackage = snap.packageName;
    }

    /** Allowlisted Solar navigation — skip foreground restore on overlay dismiss. */
    public static void markUserRequestedSolarNavigation() {
        userRequestedSolarNav = true;
    }

    /** After overlay teardown — return focus to the app that was foreground when modal opened. */
    @SuppressWarnings("deprecation")
    public static void restoreIfNeeded(Context ctx) {
        if (userRequestedSolarNav || ctx == null || snapshotTaskId < 0) {
            clearSnapshot();
            return;
        }
        String fg = ExternalInputHandoff.getForegroundPackageName(ctx);
        if (!GlobalOverlayPolicy.isSolarForegroundPackage(fg)) {
            clearSnapshot();
            return;
        }
        if (GlobalOverlayPolicy.isSolarForegroundPackage(snapshotPackage)) {
            clearSnapshot();
            return;
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(snapshotTaskId, ActivityManager.MOVE_TASK_WITH_HOME);
            }
        } catch (Exception ignored) {}
        clearSnapshot();
    }

    /** Test hook — whether restore would be skipped after explicit nav. */
    static boolean isUserRequestedSolarNavigationForTest() {
        return userRequestedSolarNav;
    }

    /** Test hook — last snapshotted package before overlay. */
    static String snapshotPackageForTest() {
        return snapshotPackage;
    }

    /** Package captured at overlay arm — avoids getRunningTasks during modal paint. */
    public static String snapshottedForegroundPackage() {
        return snapshotPackage;
    }

    /** True when the snapshotted foreground task is Rockbox (overlay launcher rows). */
    public static boolean isRockboxSnapshottedForeground() {
        return false;
    }

    static void resetForTest() {
        clearSnapshot();
        userRequestedSolarNav = false;
    }

    private static void clearSnapshot() {
        snapshotTaskId = -1;
        snapshotPackage = null;
        userRequestedSolarNav = false;
    }

    @SuppressWarnings("deprecation")
    private static RunningTaskSnapshot readTopTask(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo top = tasks.get(0);
            String pkg = null;
            if (top.baseActivity != null) pkg = top.baseActivity.getPackageName();
            else if (top.topActivity != null) pkg = top.topActivity.getPackageName();
            if (pkg == null) return null;
            return new RunningTaskSnapshot(top.id, pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class RunningTaskSnapshot {
        final int taskId;
        final String packageName;

        RunningTaskSnapshot(int taskId, String packageName) {
            this.taskId = taskId;
            this.packageName = packageName;
        }
    }
}
