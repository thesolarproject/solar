package com.solar.launcher.xposed.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.solar.launcher.xposed.bridge.extract.AlertDialogExtract;
import com.solar.launcher.xposed.bridge.extract.ReflectFields;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * App-process hooks: replace stock AlertDialog / Dialog boxes with Solar overlay rows.
 * Plain confirms use scrollable message + buttons; simple item lists use app-menu overlay.
 * Multi-choice, adapter lists, progress, and custom layouts fail-open to stock Holo UI.
 */
final class DialogHooks {

    /** Must match {@link com.solar.launcher.OverlayTriggers#ACTION_DIALOG_RESULT}. */
    static final String ACTION_DIALOG_RESULT =
            "com.solar.launcher.action.DIALOG_RESULT";
    /** List pickers reuse app-menu result delivery. */
    static final String ACTION_LIST_RESULT = AppMenuHooks.ACTION_APP_MENU_RESULT;
    static final String EXTRA_MENU_SESSION_ID = "menu_session_id";
    static final String EXTRA_SELECTED_INDEX = "menu_selected_index";

    private static final ReflectFields FIELDS = ReflectFieldsXposed.INSTANCE;

    private static final ConcurrentHashMap<String, PendingDialog> PENDING =
            new ConcurrentHashMap<String, PendingDialog>();

    private static volatile boolean plainResultRegistered;
    private static volatile boolean listResultRegistered;

    /** Live dialog + how to replay the user's choice back into the app. */
    private static final class PendingDialog {
        final Object dialogRef;
        final Object controllerRef;
        final AlertDialogExtract.Kind kind;
        /** Holo button ids aligned with overlay action rows (PLAIN) or trailing Cancel (LIST). */
        final int[] buttonIds;
        /** Item count before optional Cancel row (LIST). */
        final int listItemCount;

        PendingDialog(Object dialogRef, Object controllerRef, AlertDialogExtract.Kind kind,
                int[] buttonIds, int listItemCount) {
            this.dialogRef = dialogRef;
            this.controllerRef = controllerRef;
            this.kind = kind;
            this.buttonIds = buttonIds;
            this.listItemCount = listItemCount;
        }
    }

    private DialogHooks() {}

    /** Hook AlertDialog.show and base Dialog.show in every non-Solar app process. */
    static void install(LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        // Xposed Installer UI is AlertDialog-heavy — intercept breaks module manager on Y1/Y2.
        if ("de.robv.android.xposed.installer".equals(lpparam.packageName)) {
            SolarContextBridge.log("skip AlertDialog hooks for Xposed Installer");
            return;
        }
        hookAlertDialogShow(lpparam);
        hookBaseDialogShow(lpparam);
    }

    /** AlertDialog entry — most third-party confirms and list pickers. */
    private static void hookAlertDialogShow(LoadPackageParam lpparam) {
        try {
            Class<?> alertDialog = XposedHelpers.findClass("android.app.AlertDialog", lpparam.classLoader);
            XposedHookKit.hookAll(alertDialog, "show", dialogShowHook());
            SolarContextBridge.log("hooked AlertDialog.show in " + lpparam.packageName);
        } catch (Throwable t) {
            SolarContextBridge.log("AlertDialog.show skip " + lpparam.packageName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * Base Dialog.show — catches AlertDialog subclasses that only inherit Dialog.show, and
     * custom Dialog shells that still embed AlertController under {@code mAlert}.
     */
    private static void hookBaseDialogShow(LoadPackageParam lpparam) {
        try {
            Class<?> dialogClass = XposedHelpers.findClass("android.app.Dialog", lpparam.classLoader);
            XposedHookKit.hookAll(dialogClass, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object dialog = param.thisObject;
                    if (dialog == null) return;
                    try {
                        if ("android.app.AlertDialog".equals(dialog.getClass().getName())) return;
                    } catch (Throwable ignored) {}
                    if (tryInterceptDialog(dialog)) {
                        XposedHookKit.skipMethod(param);
                    }
                }
            });
            SolarContextBridge.log("hooked Dialog.show in " + lpparam.packageName);
        } catch (Throwable t) {
            SolarContextBridge.log("Dialog.show skip " + lpparam.packageName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    private static XC_MethodHook dialogShowHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (tryInterceptDialog(param.thisObject)) {
                    XposedHookKit.skipMethod(param);
                }
            }
        };
    }

    /** Classify dialog, open Solar overlay, register pending session — false to fail-open. */
    static boolean tryInterceptDialog(Object dialog) {
        if (dialog == null) return false;
        try {
            AlertDialogExtract.Snapshot snap = AlertDialogExtract.fromDialog(dialog, FIELDS);
            if (snap.kind == AlertDialogExtract.Kind.EMPTY
                    || snap.kind == AlertDialogExtract.Kind.MULTI_CHOICE
                    || snap.kind == AlertDialogExtract.Kind.PROGRESS
                    || snap.kind == AlertDialogExtract.Kind.CUSTOM_VIEW) {
                return false;
            }
            Object controller = XposedHelpers.getObjectField(dialog, "mAlert");
            if (controller == null) return false;

            Context ctx = (Context) XposedHelpers.callMethod(dialog, "getContext");
            if (ctx == null || !SolarOverlayClient.canDeliverOverlay(ctx)) return false;

            String sessionId = UUID.randomUUID().toString();
            if (snap.kind == AlertDialogExtract.Kind.LIST) {
                return interceptListDialog(dialog, controller, snap, ctx, sessionId);
            }
            return interceptPlainDialog(dialog, controller, snap, ctx, sessionId);
        } catch (Throwable t) {
            SolarContextBridge.log("dialog intercept error: " + t);
            return false;
        }
    }

    /** Message + button confirm — scrollable native-dialog overlay. */
    private static boolean interceptPlainDialog(Object dialog, Object controller,
            AlertDialogExtract.Snapshot snap, Context ctx, String sessionId) {
        String body = snap.message != null ? snap.message : "";
        String[] buttons = snap.buttonLabels;
        int[] ids = snap.buttonIds;
        if (buttons.length == 0) {
            buttons = new String[] {"OK"};
            ids = new int[] {DialogInterface.BUTTON_POSITIVE};
        }
        PENDING.put(sessionId, new PendingDialog(dialog, controller, snap.kind, ids, 0));
        ensurePlainResultReceiver(ctx.getApplicationContext());
        String dialogTitle = snap.title;
        if (!SolarOverlayClient.showNativeDialog(ctx, dialogTitle, body, buttons, sessionId,
                ctx.getPackageName())) {
            PENDING.remove(sessionId);
            return false;
        }
        SolarContextBridge.log("dialog overlay pkg=" + ctx.getPackageName()
                + " buttons=" + buttons.length);
        return true;
    }

    /** Simple CharSequence[] list — wheel-friendly app-menu overlay. */
    private static boolean interceptListDialog(Object dialog, Object controller,
            AlertDialogExtract.Snapshot snap, Context ctx, String sessionId) {
        String[] items = snap.listItems;
        if (items == null || items.length == 0) return false;
        int listCount = items.length;
        int[] buttonIds = snap.buttonIds;
        // Optional Holo Cancel row after list items.
        boolean hasCancel = false;
        for (int id : buttonIds) {
            if (id == DialogInterface.BUTTON_NEGATIVE) {
                hasCancel = true;
                break;
            }
        }
        String[] labels;
        boolean[] submenus;
        if (hasCancel) {
            String cancelLabel = null;
            for (int i = 0; i < buttonIds.length; i++) {
                if (buttonIds[i] == DialogInterface.BUTTON_NEGATIVE) {
                    cancelLabel = snap.buttonLabels[i];
                    break;
                }
            }
            if (cancelLabel == null) cancelLabel = "Cancel";
            labels = new String[listCount + 1];
            submenus = new boolean[listCount + 1];
            System.arraycopy(items, 0, labels, 0, listCount);
            labels[listCount] = cancelLabel;
        } else {
            labels = items;
            submenus = new boolean[items.length];
        }
        PENDING.put(sessionId, new PendingDialog(dialog, controller, snap.kind, buttonIds, listCount));
        ensureListResultReceiver(ctx.getApplicationContext());
        String title = snap.title;
        if (!SolarOverlayClient.showAppMenu(ctx, title, labels, submenus, sessionId,
                ctx.getPackageName())) {
            PENDING.remove(sessionId);
            return false;
        }
        SolarContextBridge.log("list dialog overlay pkg=" + ctx.getPackageName()
                + " items=" + listCount);
        return true;
    }

    /** Deliver plain-dialog overlay selection back into the hooked app. */
    private static void ensurePlainResultReceiver(Context appCtx) {
        if (plainResultRegistered) return;
        synchronized (DialogHooks.class) {
            if (plainResultRegistered) return;
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !ACTION_DIALOG_RESULT.equals(intent.getAction())) return;
                    deliverPlainResult(intent);
                }
            }, new IntentFilter(ACTION_DIALOG_RESULT));
            plainResultRegistered = true;
        }
    }

    /** Deliver list-dialog overlay selection via the shared app-menu result channel. */
    private static void ensureListResultReceiver(Context appCtx) {
        if (listResultRegistered) return;
        synchronized (DialogHooks.class) {
            if (listResultRegistered) return;
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !ACTION_LIST_RESULT.equals(intent.getAction())) return;
                    deliverListResult(intent);
                }
            }, new IntentFilter(ACTION_LIST_RESULT));
            listResultRegistered = true;
        }
    }

    private static void deliverPlainResult(Intent intent) {
        String sessionId = intent.getStringExtra(EXTRA_MENU_SESSION_ID);
        if (sessionId == null) return;
        int index = intent.getIntExtra(EXTRA_SELECTED_INDEX, -1);
        PendingDialog pending = PENDING.remove(sessionId);
        if (pending == null || pending.kind != AlertDialogExtract.Kind.PLAIN) return;
        try {
            if (index < 0) {
                XposedHelpers.callMethod(pending.dialogRef, "cancel");
                return;
            }
            int buttonIndex = index - 1;
            if (buttonIndex < 0 || buttonIndex >= pending.buttonIds.length) {
                XposedHelpers.callMethod(pending.dialogRef, "dismiss");
                return;
            }
            fireAlertButton(pending, pending.buttonIds[buttonIndex]);
        } catch (Throwable t) {
            SolarContextBridge.log("dialog result failed: " + t);
        }
    }

    private static void deliverListResult(Intent intent) {
        String sessionId = intent.getStringExtra(EXTRA_MENU_SESSION_ID);
        if (sessionId == null) return;
        int index = intent.getIntExtra(EXTRA_SELECTED_INDEX, -1);
        PendingDialog pending = PENDING.remove(sessionId);
        if (pending == null || pending.kind != AlertDialogExtract.Kind.LIST) return;
        try {
            if (index < 0) {
                XposedHelpers.callMethod(pending.dialogRef, "cancel");
                return;
            }
            if (index >= pending.listItemCount) {
                fireAlertButton(pending, DialogInterface.BUTTON_NEGATIVE);
                return;
            }
            fireListSelection(pending, index);
        } catch (Throwable t) {
            SolarContextBridge.log("list dialog result failed: " + t);
        }
    }

    /** Replay list pick through AlertController's OnClickListener — same path as Holo ListView. */
    private static void fireListSelection(PendingDialog pending, int itemIndex) {
        Object listener = XposedHelpers.getObjectField(pending.controllerRef, "mOnClickListener");
        Object dialogInterface = XposedHelpers.getObjectField(pending.controllerRef, "mDialogInterface");
        if (listener != null && dialogInterface != null) {
            XposedHelpers.callMethod(listener, "onClick", dialogInterface, itemIndex);
        }
        try {
            java.lang.reflect.Field checked = pending.controllerRef.getClass().getDeclaredField("mCheckedItem");
            checked.setAccessible(true);
            checked.setInt(pending.controllerRef, itemIndex);
        } catch (Throwable ignored) {}
        // Single-choice with an explicit OK button — fire positive after row pick (Holo confirm path).
        Object positiveMsg = XposedHelpers.getObjectField(pending.controllerRef, "mButtonPositiveMessage");
        if (positiveMsg instanceof Message) {
            try {
                java.lang.reflect.Field single = pending.controllerRef.getClass()
                        .getDeclaredField("mIsSingleChoice");
                single.setAccessible(true);
                if (single.getBoolean(pending.controllerRef)) {
                    Message msg = Message.obtain((Message) positiveMsg);
                    if (msg != null) {
                        msg.sendToTarget();
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        XposedHelpers.callMethod(pending.dialogRef, "dismiss");
    }

    /** Route through AlertController's Message handler — same path as tapping the stock button. */
    private static void fireAlertButton(PendingDialog pending, int buttonId) {
        String msgField;
        if (buttonId == DialogInterface.BUTTON_POSITIVE) {
            msgField = "mButtonPositiveMessage";
        } else if (buttonId == DialogInterface.BUTTON_NEGATIVE) {
            msgField = "mButtonNegativeMessage";
        } else {
            msgField = "mButtonNeutralMessage";
        }
        Object msgObj = XposedHelpers.getObjectField(pending.controllerRef, msgField);
        if (msgObj instanceof Message) {
            Message msg = Message.obtain((Message) msgObj);
            if (msg != null) {
                msg.sendToTarget();
                return;
            }
        }
        XposedHelpers.callMethod(pending.dialogRef, "dismiss");
    }
}
