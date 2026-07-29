package com.solar.launcher.overlay;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.UUID;

/**
 * 2026-07-14 — Public helper: open Solar ThemedContextMenu shell (Home parity).
 * Layman: one call for the decorated system options / quick menu over any app.
 * Technical: startService → OverlayShellRouter (Solar by default; Chip if companion_shell=1).
 * Was: companion Chip primary. Reversal: companion_shell=1.
 */
public final class OverlayMenuClient {

    private static final String TAG = "OverlayMenuClient";

    private OverlayMenuClient() {}

    /**
     * Opens power / quick menu on the one shell (Solar ThemedContextMenu by default).
     * @return true when startService accepted
     */
    public static boolean showPowerQuickMenu(Context ctx) {
        return startShell(ctx, OverlayMenuContract.ACTION_SHOW_OVERLAY_POWER, null);
    }

    public static boolean showUsbStoragePrompt(Context ctx) {
        warnIfMissingOptIn(ctx);
        return startShell(ctx, OverlayMenuContract.ACTION_SHOW_OVERLAY_USB_STORAGE, null);
    }

    public static boolean showUsbStorageLock(Context ctx) {
        warnIfMissingOptIn(ctx);
        Bundle extras = new Bundle();
        extras.putBoolean(OverlayMenuContract.EXTRA_USB_STORAGE_LOCK, true);
        return startShell(ctx, OverlayMenuContract.ACTION_SHOW_OVERLAY_USB_STORAGE_LOCK, extras);
    }

    /**
     * Shows a wheel-friendly options list with optional keep-open (submenu) flags.
     * Registers a one-shot result receiver for the generated session id.
     */
    public static boolean showContextMenu(Context ctx, String title, String[] labels,
            boolean[] keepOpen, OverlayMenuCallback callback) {
        if (ctx == null || labels == null || labels.length == 0) {
            return false;
        }
        if (callback == null) {
            Log.w(TAG, "showContextMenu without callback — fire-and-forget");
        }
        // Voluntary API — prefer callers that declare META_OVERLAY_OPT_IN (warn only).
        warnIfMissingOptIn(ctx);
        final String sessionId = "api_" + UUID.randomUUID().toString();
        final String caller = ctx.getPackageName();
        if (callback != null) {
            registerMenuReceiver(ctx, sessionId, callback);
        }
        Bundle extras = new Bundle();
        extras.putStringArray(OverlayMenuContract.EXTRA_MENU_TITLES, labels);
        extras.putString(OverlayMenuContract.EXTRA_MENU_SESSION_ID, sessionId);
        extras.putString(OverlayMenuContract.EXTRA_MENU_TITLE,
                title != null ? title : "Options");
        extras.putString(OverlayMenuContract.EXTRA_MENU_CALLER_PACKAGE, caller);
        if (keepOpen != null) {
            extras.putBooleanArray(OverlayMenuContract.EXTRA_MENU_HAS_SUBMENU, keepOpen);
        }
        return startShell(ctx, OverlayMenuContract.ACTION_SHOW_OVERLAY_APP_MENU, extras);
    }

    /**
     * Shows a native confirm dialog (title + body + button rows) on the global shell.
     */
    public static boolean showConfirmDialog(Context ctx, String title, String body,
            String[] buttonLabels, OverlayDialogCallback callback) {
        if (ctx == null || buttonLabels == null || buttonLabels.length == 0) {
            return false;
        }
        warnIfMissingOptIn(ctx);
        final String sessionId = "dlg_" + UUID.randomUUID().toString();
        if (callback != null) {
            registerDialogReceiver(ctx, sessionId, callback);
        }
        Bundle extras = new Bundle();
        extras.putString(OverlayMenuContract.EXTRA_MENU_TITLE,
                title != null ? title : "Alert");
        extras.putString(OverlayMenuContract.EXTRA_DIALOG_MESSAGE, body != null ? body : "");
        extras.putStringArray(OverlayMenuContract.EXTRA_DIALOG_BUTTONS, buttonLabels);
        extras.putString(OverlayMenuContract.EXTRA_MENU_SESSION_ID, sessionId);
        extras.putString(OverlayMenuContract.EXTRA_MENU_CALLER_PACKAGE, ctx.getPackageName());
        return startShell(ctx, OverlayMenuContract.ACTION_SHOW_OVERLAY_NATIVE_DIALOG, extras);
    }

    /** Tears down the active overlay shell (Solar or Chip escape). */
    public static boolean dismiss(Context ctx) {
        return startShell(ctx, OverlayMenuContract.ACTION_DISMISS_OVERLAY, null);
    }

    /** True when companion package is installed (opt-in Chip escape hatch). */
    public static boolean isCompanionInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getApplicationInfo(OverlayShellRouter.COMPANION_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean startShell(Context ctx, String action, Bundle extras) {
        if (ctx == null || action == null) return false;
        // 2026-07-14 — DISMISS hits both shells; SHOW clears the peer so chrome never stacks.
        if (OverlayMenuContract.ACTION_DISMISS_OVERLAY.equals(action)) {
            OverlayShellRouter.dismissAllOverlayShells(ctx);
            return true;
        }
        OverlayShellRouter.dismissPeerOverlayShell(ctx);
        Intent svc = new Intent(action);
        // One component from OverlayShellRouter (Solar primary).
        svc.setComponent(OverlayShellRouter.overlayComponent());
        if (extras != null) {
            svc.putExtras(extras);
        }
        try {
            ctx.startService(svc);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startShell primary failed " + action + ": " + e.getMessage());
        }
        // Was: fail-open paint on the other shell → two menus. Now: no second paint.
        // Reversal: restore companion startService fallback below.
        return false;
    }

    private static void registerMenuReceiver(final Context ctx, final String sessionId,
            final OverlayMenuCallback callback) {
        final Context app = ctx.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String sid = intent.getStringExtra(OverlayMenuContract.EXTRA_MENU_SESSION_ID);
                if (sessionId == null || !sessionId.equals(sid)) return;
                int index = intent.getIntExtra(OverlayMenuContract.EXTRA_SELECTED_INDEX,
                        OverlayMenuContract.RESULT_CANCELLED);
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {}
                callback.onMenuResult(index);
            }
        };
        registerResultReceiver(app, receiver,
                new IntentFilter(OverlayMenuContract.ACTION_APP_MENU_RESULT));
    }

    private static void registerDialogReceiver(final Context ctx, final String sessionId,
            final OverlayDialogCallback callback) {
        final Context app = ctx.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String sid = intent.getStringExtra(OverlayMenuContract.EXTRA_MENU_SESSION_ID);
                if (sessionId == null || !sessionId.equals(sid)) return;
                int index = intent.getIntExtra(OverlayMenuContract.EXTRA_SELECTED_INDEX,
                        OverlayMenuContract.RESULT_CANCELLED);
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {}
                callback.onDialogResult(index);
            }
        };
        registerResultReceiver(app, receiver,
                new IntentFilter(OverlayMenuContract.ACTION_DIALOG_RESULT));
    }

    /**
     * Results can come from Solar's separately installed companion, so the
     * receiver is exported on Android 13+ but restricted to Solar's
     * signature/system permission on every supported API.
     */
    private static void registerResultReceiver(Context app, BroadcastReceiver receiver,
            IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter,
                    OverlayMenuContract.PERMISSION_OVERLAY_TRIGGER, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter,
                    OverlayMenuContract.PERMISSION_OVERLAY_TRIGGER, null);
        }
    }

    /** Soft policy — unsigned hooks still work; voluntary API should opt in. */
    private static void warnIfMissingOptIn(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(ctx.getPackageName(),
                    PackageManager.GET_META_DATA);
            if (ai.metaData == null
                    || !ai.metaData.getBoolean(OverlayMenuContract.META_OVERLAY_OPT_IN, false)) {
                Log.i(TAG, "META_OVERLAY_OPT_IN unset on " + ctx.getPackageName()
                        + " — ok for Solar hooks; set for voluntary OverlayMenuClient");
            }
        } catch (Exception ignored) {}
    }
}
