package com.solar.launcher.xposed.bridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * App-process fallback when PWM hooks miss keys — common with Dialog context menus and MTK media keys.
 * Forwards wheel/back/OK to Solar while sys.solar.overlay.active=1.
 * Short BACK is never blocked here — long BACK global menu lives in system_server hooks.
 */
final class ActivityOverlayKeyHooks {

    private ActivityOverlayKeyHooks() {}

    /** Install in every third-party process (paired with {@link AppMenuHooks}). */
    static void install(LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (lpparam.packageName.startsWith("com.solar.launcher")) return;
        // 2026-07-08 — Stock Innioasis HOME needs overlay key forward; other innioasis.* still skip.
        // Reversal: blanket startsWith("com.innioasis.") return.
        if (com.solar.input.policy.GlobalInputPolicy.isInnioasisNonLauncherPackage(
                lpparam.packageName)) return;
        hookActivityDispatch(lpparam);
        hookDialogDispatch(lpparam);
        hookActivityOnKey(lpparam);
    }

    /** Activity window — catches keys before app onKeyDown handlers. */
    private static void hookActivityDispatch(LoadPackageParam lpparam) {
        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader);
            XposedHookKit.hookAll(activity, "dispatchKeyEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    consumeIfOverlayOwnsKey((Activity) param.thisObject, (KeyEvent) param.args[0], param);
                }
            });
            SolarContextBridge.log("hooked Activity.dispatchKeyEvent in " + lpparam.packageName);
        } catch (Throwable t) {
            SolarContextBridge.log("Activity.dispatchKeyEvent skip " + lpparam.packageName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    /** Stock / Holo context menus are Dialogs — they keep focus while our overlay is NOT_FOCUSABLE. */
    private static void hookDialogDispatch(LoadPackageParam lpparam) {
        try {
            Class<?> dialog = XposedHelpers.findClass("android.app.Dialog", lpparam.classLoader);
            XposedHookKit.hookAll(dialog, "dispatchKeyEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Dialog dlg = (Dialog) param.thisObject;
                    consumeIfOverlayOwnsKey(dlg != null ? dlg.getContext() : null,
                            (KeyEvent) param.args[0], param);
                }
            });
            SolarContextBridge.log("hooked Dialog.dispatchKeyEvent in " + lpparam.packageName);
        } catch (Throwable t) {
            SolarContextBridge.log("Dialog.dispatchKeyEvent skip " + lpparam.packageName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    /** Apps that bypass dispatchKeyEvent and override onKeyDown/onKeyUp directly. */
    private static void hookActivityOnKey(LoadPackageParam lpparam) {
        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader);
            XposedHookKit.hookAll(activity, "onKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int keyCode = (Integer) param.args[0];
                    if (OverlayKeyForwarder.tryForwardFromAppContext(
                            (Activity) param.thisObject, keyCode, KeyEvent.ACTION_DOWN)) {
                        param.setResult(true);
                        return;
                    }
                    if (ImeKeyForwarder.tryForwardFromAppContext(
                            (Activity) param.thisObject, keyCode, KeyEvent.ACTION_DOWN)) {
                        param.setResult(true);
                    }
                }
            });
            XposedHookKit.hookAll(activity, "onKeyUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int keyCode = (Integer) param.args[0];
                    if (OverlayKeyForwarder.tryForwardFromAppContext(
                            (Activity) param.thisObject, keyCode, KeyEvent.ACTION_UP)) {
                        param.setResult(true);
                        return;
                    }
                    if (ImeKeyForwarder.tryForwardFromAppContext(
                            (Activity) param.thisObject, keyCode, KeyEvent.ACTION_UP)) {
                        param.setResult(true);
                    }
                }
            });
            SolarContextBridge.log("hooked Activity onKeyDown/Up in " + lpparam.packageName);
        } catch (Throwable t) {
            SolarContextBridge.log("Activity onKey skip " + lpparam.packageName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    /** Swallow and forward when global overlay prop is armed for this package. */
    private static void consumeIfOverlayOwnsKey(Context ctx, KeyEvent event,
            XC_MethodHook.MethodHookParam param) {
        if (event != null && Y1InputKeysBridge.isBackKey(event.getKeyCode())
                && SystemServerHooks.isPostOverlayCooldown()) {
            param.setResult(true);
            return;
        }
        if (OverlayKeyForwarder.tryForwardFromAppContext(ctx, event)) {
            param.setResult(true);
            return;
        }
        if (ImeKeyForwarder.tryForwardFromAppContext(ctx, event)) {
            param.setResult(true);
        }
    }
}
