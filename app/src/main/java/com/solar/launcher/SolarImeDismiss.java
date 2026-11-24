package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.EditorInfo;

/**
 * 2026-07-05 — Central IME dismiss + boot recovery for ghost WM overlay after crash.
 * Clears sys.solar.ime.* via SolarImeRouteArbiter.disarm(); called from BootReceiver and Application.
 * When changing: keep recoverOnBoot before MainActivity paints to avoid stuck tray chrome.
 * Reversal: remove recoverOnBoot calls; stale IME overlay may block Rockbox navigation.
 */
public final class SolarImeDismiss {

    private SolarImeDismiss() {}

    /** Hide tray — works from main, :overlay, Xposed, or root daemon. */
    public static void request(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            Intent svc = new Intent(app, SolarInputMethodService.class);
            svc.setAction(OverlayTriggers.ACTION_IME_DISMISS);
            app.startService(svc);
        } catch (Exception ignored) {}
        SolarImeRouteArbiter.disarm();
    }

    /** Boot / Solar HOME — drop stale WM overlay left from crash or prior session. */
    public static void recoverOnBoot(Context ctx) {
        // Always poke dismiss — sysprops alone miss WM orphans when :overlay survived a crash.
        request(ctx);
    }

    /**
     * System IME must not paint over Solar HOME — MainActivity uses in-app keyboard.
     * Third-party fields only; must look like a real text entry target.
     */
    public static boolean shouldShowSystemImeTray(EditorInfo info) {
        if (info == null) return false;
        String pkg = info.packageName;
        if (pkg == null || pkg.length() == 0) return false;
        if ("com.solar.launcher".equals(pkg)) return false;
        if (pkg.startsWith("com.innioasis.")) return false;
        // 2026-07-06 — Rockbox KbdInput often has inputType 0; SolarRockboxIme sets TYPE_CLASS_TEXT.
        if (false) {
            return isTextLikeInput(info.inputType)
                    || (info.imeOptions & EditorInfo.IME_ACTION_DONE) != 0;
        }
        boolean textLike = isTextLikeInput(info.inputType);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("pkg", pkg);
            d.put("fieldId", info.fieldId);
            d.put("imeOptions", info.imeOptions);
            d.put("label", info.label != null ? info.label.toString() : "");
            d.put("hint", info.hintText != null ? info.hintText.toString() : "");
            d.put("textLike", textLike);
            d.put("inputType", DebugImeLog.inputTypeFields(info.inputType));
            DebugImeLog.log(null, "SolarImeDismiss.shouldShowSystemImeTray", "gate", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
        return textLike;
    }

    /** EditorInfo carries a text/password/number class — not a no-op focus. */
    public static boolean isTextLikeInput(int inputType) {
        int cls = inputType & EditorInfo.TYPE_MASK_CLASS;
        return cls == EditorInfo.TYPE_CLASS_TEXT
                || cls == EditorInfo.TYPE_CLASS_NUMBER
                || cls == EditorInfo.TYPE_CLASS_PHONE
                || cls == EditorInfo.TYPE_CLASS_DATETIME;
    }
}
