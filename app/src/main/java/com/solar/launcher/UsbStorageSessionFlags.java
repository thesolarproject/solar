package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Cross-process USB plug-in prefs and overlay dismiss handoff for {@link MainActivity}.
 * 2026-07-19 — Android owns USB unless Auto-Connect or explicit Enable USB storage.
 * Layman: Solar does not fight the PC cable; SystemUI can show its own disk dialog.
 * Was: optional Solar connect prompt via suppress-toggle. Reversal: restore skip-pref gate.
 */
public final class UsbStorageSessionFlags {

    /** Same store as {@link MainActivity} settings — readable from Solar :overlay process. */
    static final String PREFS = "SOLAR_SETTINGS";
    /** Legacy pref — always forced true; Solar no longer offers a plug-in prompt (2026-07-19). */
    static final String PREF_USB_SUPPRESS_CONNECT_PROMPT = "usb_suppress_connect_prompt";
    static final String PREF_USB_AUTO_CONNECT = "usb_auto_connect";
    static final String PREF_USB_MANUAL_DISABLE = "usb_manual_disable";
    /** Written when user dismisses the global USB overlay — consumed on MainActivity resume. */
    static final String KEY_OVERLAY_DISMISS_PENDING = "usb_overlay_dismiss_pending";

    private UsbStorageSessionFlags() {}

    /** Settings → skip Solar prompt — sysprop + prefs; Xposed reads sysprop (2026-07-05). */
    public static final String SYSPROP_SKIP_PROMPT = "sys.solar.usb.skip_prompt";
    /** Settings → auto-connect — Xposed concierge reads without stale SP (2026-07-06). */
    public static final String SYSPROP_AUTO_CONNECT = "sys.solar.usb.auto_connect";
    /**
     * 1 = leave stock UsbStorageActivity alone (no finish / no Solar wake).
     * Set whenever Auto-Connect is off. 2026-07-19
     */
    public static final String SYSPROP_STOCK_UI = "sys.solar.usb.stock_ui";

    /**
     * Always skip Solar plug-in prompt — Android SystemUI owns the cable dialog.
     * Was: user toggle. Reversal: DEFAULT false + Settings row for Solar prompt.
     * 2026-07-19
     */
    public static final boolean DEFAULT_SKIP_SOLAR_PROMPT = true;

    /**
     * True — Solar must not steal USB UI or tear down stock UMS dialogs.
     * Layman: PC cable → Android handles the USB screen; Solar never fights it.
     * Tech: always stock (2026-07-19). Auto-Connect uses silent enable without finishing SystemUI.
     * Was: stock iff Auto-Connect off.
     */
    public static boolean preferStockUsbUi(Context ctx) {
        return true;
    }

    /** Test hook — always stock after 2026-07-19 policy. */
    static boolean preferStockUsbUiFromPrefs(boolean autoConnect) {
        return true;
    }

    /**
     * @deprecated kept for call sites; always false — no Solar plug-in prompt (2026-07-19).
     */
    public static boolean shouldOfferUsbConnectPrompt(Context ctx) {
        // Auto-Connect uses silent enable; Enable Now is explicit menu — never nag on plug.
        return false;
    }

    /**
     * 2026-07-06 — Settings skip + boot-settle gate for all USB enable prompts.
     * 2026-07-16 — Also wait until Solar home is ready (no setup / prep face).
     * 2026-07-19 — Plug-in prompt removed; this stays false. Auto-Connect uses
     * {@link #isAutoConnectAllowedAfterBootSettle} instead.
     */
    public static boolean shouldOfferUsbConnectPromptAfterBootSettle(Context ctx) {
        if (!shouldOfferUsbConnectPrompt(ctx)) return false;
        if (!UsbHostSessionPolicy.isPromptAllowedAfterBootSettle(ctx)) return false;
        return FirstSessionReadyGate.isHomeReadyForUsbPrompt(ctx);
    }

    /**
     * 2026-07-19 — Auto-Connect may enable UMS after boot settle + home ready.
     * Layman: car-stereo auto disk mode waits until Solar finished waking up.
     * Tech: same settle gates as the old prompt, without requiring Solar UI.
     */
    public static boolean isAutoConnectAllowedAfterBootSettle(Context ctx) {
        if (!isAutoConnectEnabled(ctx)) return false;
        if (!UsbHostSessionPolicy.isPromptAllowedAfterBootSettle(ctx)) return false;
        return FirstSessionReadyGate.isHomeReadyForUsbPrompt(ctx);
    }

    private static void writeSysprop(String key, String val) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, val);
        } catch (Exception e) {
            if (RootShell.canRun()) {
                RootShell.run("setprop " + key + " " + val);
            }
        }
    }

    /**
     * Pin skip=1 + stock=(!auto) so Xposed never finishes UsbStorageActivity unless Auto-Connect.
     * 2026-07-19 — Also migrates legacy suppress pref to always-on.
     */
    public static void syncSkipPromptSysprop(Context ctx) {
        if (ctx == null) return;
        // Force legacy pref so old builds/readers stay hands-off.
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_USB_SUPPRESS_CONNECT_PROMPT, DEFAULT_SKIP_SOLAR_PROMPT)) {
            prefs.edit().putBoolean(PREF_USB_SUPPRESS_CONNECT_PROMPT, true).commit();
        }
        writeSysprop(SYSPROP_SKIP_PROMPT, "1");
        boolean stock = preferStockUsbUi(ctx);
        writeSysprop(SYSPROP_STOCK_UI, stock ? "1" : "0");
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("skip", true);
            d.put("stock", stock);
            d.put("auto", isAutoConnectEnabled(ctx));
            Debug543e15Log.log("UsbStorageSessionFlags.syncSkipPromptSysprop",
                    "usb sysprops synced", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Sync skip + auto-connect sysprops for Xposed USB concierge (2026-07-06). */
    public static void syncUsbSessionSysprops(Context ctx) {
        syncSkipPromptSysprop(ctx);
        syncAutoConnectSysprop(ctx);
        UsbHostSessionPolicy.syncSysprops(ctx);
    }

    /**
     * 2026-07-06 — Auto-connect implies skip plug-in prompts (user opted into silent attach).
     * 2026-07-19 — Turning Auto-Connect off restores stock UI ownership (no Solar steal).
     * Layman: auto = Solar mounts the disk for car stereos; off = Android owns the cable.
     */
    public static void applyAutoConnectSideEffects(Context ctx, boolean autoConnect) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Always leave suppress on — Solar never shows a plug-in nag dialog.
        prefs.edit().putBoolean(PREF_USB_SUPPRESS_CONNECT_PROMPT, true).commit();
        syncUsbSessionSysprops(ctx);
        if (!autoConnect) {
            // Drop Solar-armed session so stock SystemUI can own the next plug.
            UsbMassStorageController.clearUserSession();
        }
    }

    /** Root setprop — bridge reads auto-connect; unset pref = off (2026-07-06). */
    public static void syncAutoConnectSysprop(Context ctx) {
        if (ctx == null) return;
        boolean autoConnect = isAutoConnectEnabled(ctx);
        writeSysprop(SYSPROP_AUTO_CONNECT, autoConnect ? "1" : "0");
    }

    /** Test hook — sysprop skip without shell (2026-07-05). */
    static boolean isSkipPromptFromSyspropForTest(String propValue) {
        return "1".equals(propValue);
    }

    /** getprop — no su; readable from main and :overlay (2026-07-05). */
    private static String readSysprop(String key) {
        // ponytail: reflection-based get is much faster than exec("getprop")
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            if (v != null && !v.toString().isEmpty()) {
                return v.toString().trim();
            }
        } catch (Exception ignored) {}
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"getprop", key});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line = r.readLine();
            proc.waitFor();
            return line != null ? line.trim() : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    /**
     * 2026-11-26 — Wired disk mode without a prompt: enable Auto-Connect from Settings.
     * Layman: plugging the cable in shares Internal + SD straight away, no menu first.
     * Tech: pref + sysprop (+ side effects) so UsbHostWakeReceiver arms UMS on connect.
     * Reversal: drop callers; Auto-Connect goes back to the USB submenu only.
     */
    public static void setAutoConnect(Context ctx, boolean enabled) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_USB_AUTO_CONNECT, enabled)
                .putBoolean(PREF_USB_MANUAL_DISABLE, !enabled)
                .commit();
        applyAutoConnectSideEffects(ctx, enabled);
        syncAutoConnectSysprop(ctx);
    }

    /** Settings → auto-connect — opt-in only; never default on (2026-07-06). */
    public static boolean isAutoConnectEnabled(Context ctx) {
        // ponytail: check system property first to bypass stale SharedPreferences cache in :overlay process
        String sysval = readSysprop(SYSPROP_AUTO_CONNECT);
        if ("1".equals(sysval)) {
            return true;
        } else if ("0".equals(sysval)) {
            return false;
        }
        if (ctx == null) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_USB_AUTO_CONNECT, false)
                && !prefs.getBoolean(PREF_USB_MANUAL_DISABLE, false);
    }

    /** Overlay dismiss — persist until MainActivity applies {@code onUsbStorageEnableDialogDismissed}. */
    public static void markOverlayDismissPending(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_OVERLAY_DISMISS_PENDING, true)
                .commit();
    }

    /** Consume one-shot dismiss flag written by the global USB overlay tier. */
    public static boolean consumeOverlayDismissPending(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_OVERLAY_DISMISS_PENDING, false)) {
            return false;
        }
        prefs.edit().remove(KEY_OVERLAY_DISMISS_PENDING).apply();
        return true;
    }

    /** Test hook — pref booleans without Android context. */
    static boolean shouldOfferFromPrefs(boolean suppressPrompt) {
        return !suppressPrompt;
    }

    /** Test hook — auto-connect gated on manual-disable flag. */
    static boolean isAutoConnectFromPrefs(boolean autoConnect, boolean manualDisable) {
        return autoConnect && !manualDisable;
    }
}
