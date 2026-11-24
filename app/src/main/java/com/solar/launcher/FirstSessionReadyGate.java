package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.platform.PlatformPrepManifest;
import com.solar.launcher.platform.PlatformPrepState;

import java.io.File;

/**
 * 2026-07-16 — When first boot / self-heal should show full-screen “Getting things ready…”.
 * Layman: users saw a blank screen while Solar set HOME, hooks, and library — this gate says wait.
 * Technical: prep version + ROM home marker + one-shot UI-ready pref.
 * Reversal: always return false (silent blank cold start again).
 */
public final class FirstSessionReadyGate {

    private static final String PREFS = "solar_first_session";
    private static final String KEY_UI_READY = "first_ui_ready_complete";
    /** Intent extra: MainActivity should keep the ready overlay until home is usable. */
    public static final String EXTRA_KEEP_READY_OVERLAY = "solar_keep_ready_overlay";

    private FirstSessionReadyGate() {}

    /** True while first-time setup or incomplete self-heal still has work for the user to wait on. */
    public static boolean shouldShowGettingReady(Context ctx) {
        if (ctx == null) return false;
        if (DeviceFeatures.isA5()) {
            // 2026-07-16 — A5 skips platform prep ladder; still cover first UI paint.
            return !isUiReadyComplete(ctx);
        }
        try {
            PlatformPrepManifest manifest = PlatformPrepManifest.load(ctx);
            if (PlatformPrepState.needsSilentPrep(ctx, manifest)) return true;
            if (PlatformPrepState.isRebootPending(ctx)) return true;
        } catch (Exception ignored) {}
        return !isUiReadyComplete(ctx);
    }

    /**
     * 2026-07-16 — True when platform prep is still behind the bundled ladder (show wizard).
     * 2026-07-20 — Rooted phones (PhoneChromePolicy) use this same gate — only A5 skips.
     * Layman: phones with root still get the “Getting things ready” prep steps.
     * Tech: no A5-style early return for chrome phones; ladder runs when su works.
     */
    public static boolean shouldShowPrepWizard(Context ctx) {
        if (ctx == null || DeviceFeatures.isA5()) return false;
        try {
            PlatformPrepManifest manifest = PlatformPrepManifest.load(ctx);
            return PlatformPrepState.needsSilentPrep(ctx, manifest)
                    || PlatformPrepState.isRebootPending(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isUiReadyComplete(Context ctx) {
        if (ctx == null) return true;
        return prefs(ctx).getBoolean(KEY_UI_READY, false);
    }

    /**
     * 2026-07-16 — USB plug-in prompts may run once home has been painted once.
     * Layman: ask about disk mode after Solar home is up — not over the first-wait face.
     * Tech: only {@link #isUiReadyComplete} (set when home list is ready). Do <b>not</b> call
     * {@link #shouldShowGettingReady} here — that stays true for Rockbox marker / silent prep
     * forever and was blocking all USB Connection prompts after a normal home launch.
     * MainActivity still defers while first-ready overlay or library scan is active.
     * Reversal: also require !shouldShowGettingReady (broken forever-block path).
     */
    public static boolean isHomeReadyForUsbPrompt(Context ctx) {
        if (ctx == null) return false;
        return isUiReadyComplete(ctx);
    }

    /** Call once home menu is painted and first library pass is idle. */
    public static void markUiReadyComplete(Context ctx) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_UI_READY, true).apply();
    }

    /** Debug / factory reset path. */
    public static void clearUiReady(Context ctx) {
        if (ctx == null) return;
        prefs(ctx).edit().remove(KEY_UI_READY).commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
