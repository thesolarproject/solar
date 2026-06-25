package com.solar.launcher;

import android.content.Context;

/**
 * Keymap + codec prep before Solar ↔ Rockbox handoff (see rockbox-y1-coexistence.mdc).
 */
public final class RockboxCoexistence {
    private RockboxCoexistence() {}

    /** Run before Settings → Switch to Rockbox (keymap + native libs). */
    public static void prepareForRockboxSwitch(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Y1KeymapSync.ensureUnified(app);
        RockboxLibSync.syncBeforeRockboxSwitch(app);
    }

    /** Solar boot: seed ROM scripts from assets and sync keymap/libs if needed. */
    public static void ensureOnSolarStart(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Y1KeymapSync.ensureUnified(app);
        RockboxLibSync.ensureOnSolarStart(app);
    }
}
