package com.solar.launcher;

/**
 * 2026-07-14 — Hold policy: Solar-only POWER menu; HOLD BACK outside Solar returns to Solar Home.
 * Layman: ~420ms Back-hold jumps home from apps; Power menu only inside Solar; third-party keeps BACK.
 * Technical: delegates to {@link com.solar.input.policy.GlobalInputPolicy} (policy_rev 22).
 * Ultra-long BACK/power arms rescue (7s HUD, 10s exec). Mutex: OverlayKeyGate / IME arbiter.
 * Reversal: POLICY_REV 21 system-wide WM quick menu for 3P POWER/BACK.
 */
public final class GlobalOverlayPolicy {

    private GlobalOverlayPolicy() {}

    /**
     * 2026-07-06 — Stock/third-party + nav-owned launchers for center-long OK path only.
     * BACK/POWER 4s modal uses {@link #shouldOfferBackLongGlobalModal} (includes third-party/JJ).
     */
    public static boolean shouldOfferGlobalModalForPackage(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        // 2026-07-08 — JJ / Innioasis HOME use BACK-long via shouldOfferBackLongGlobalModal (300ms tier).
        if (com.solar.input.policy.GlobalInputPolicy.isJjKeylayoutLauncher(pkg)) return false;
        if ("com.solar.launcher".equals(pkg)) return false;
        // Other com.innioasis.* (music/fm) stay denylisted for center-long path.
        if (com.solar.input.policy.GlobalInputPolicy.isInnioasisNonLauncherPackage(pkg)) return false;
        return true;
    }

    /**
     * 2026-07-14 — Y2 power-hold / GlobalActions — Solar Home only (stock menu elsewhere).
     * Y1 has no hardware power key; BACK-long uses {@link #shouldOfferBackLongGlobalModal}.
     */
    public static boolean shouldOfferPowerLongGlobalModalForPackage(String pkg) {
        return shouldOfferPowerLongGlobalModalForTest(pkg, DeviceFeatures.isY2());
    }

    /** Test hook — Y2 Solar-only power menu without Robolectric device model. */
    static boolean shouldOfferPowerLongGlobalModalForTest(String pkg, boolean y2Device) {
        return com.solar.input.policy.GlobalInputPolicy.shouldOfferPowerLongModal(pkg, y2Device);
    }

    /**
     * When {@code sys.solar.overlay.active=1}, block hardware keys from reaching this foreground app.
     * third-party is included so an overlay opened elsewhere does not leak wheel/BACK into third-party.
     */
    public static boolean shouldBlockForegroundKeysWhileOverlayActive(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (isSolarForegroundPackage(pkg)) return true;
        // 2026-07-08 — JJ + Innioasis HOME: overlay owns wheel while quick menu is up.
        if (com.solar.input.policy.GlobalInputPolicy.isJjKeylayoutLauncher(pkg)) return true;
        // Other innioasis vendor apps — leave keys alone (prior denylist).
        if (com.solar.input.policy.GlobalInputPolicy.isInnioasisNonLauncherPackage(pkg)) return false;
        return true;
    }

    /**
     * 2026-07-05 — Ultra-long BACK/power rescue hold — third-party, bare WM, and system shells included.
     * Layman: hold Back/Power ~10s to restart Solar from almost any screen except Solar itself.
     * Technical: Solar foreground uses {@link MainActivity} native handler; null/android fg allowed.
     */
  /** Back-key rescue hold — any fg except Solar (in-app wheel owns BACK). */
    public static boolean shouldArmRescueHoldForPackage(String pkg) {
        return shouldArmRescueHoldForPackage(pkg, false);
    }

    /**
     * 2026-07-06 — Y2 power 10s rescue includes Solar fg; BACK still excluded on Solar.
     * Layman: hold sleep in Solar can cold-restart; hold Back in Solar does not.
     * Technical: mirrors {@link com.solar.input.policy.GlobalInputPolicy#shouldArmRescueHoldForPackage}.
     */
    public static boolean shouldArmRescueHoldForPackage(String pkg, boolean powerKey) {
        return shouldArmRescueHoldForPackageForTest(pkg, powerKey, DeviceFeatures.isY2());
    }

    /** Test hook — Y2 power rescue on Solar without Robolectric device model. */
    static boolean shouldArmRescueHoldForPackageForTest(String pkg, boolean powerKey, boolean y2Device) {
        if ("com.solar.launcher".equals(pkg)) {
            return powerKey && y2Device;
        }
        return true;
    }

    /**
     * 2026-07-14 — BACK-long → Solar Home eligibility (IME may escape third-party).
     * Name kept; action is launch Solar, not WM quick menu.
     */
    public static boolean shouldOfferBackLongGlobalModal(String pkg, boolean imeActive) {
        return com.solar.input.policy.GlobalInputPolicy.shouldOfferBackLongModal(
                pkg, DeviceFeatures.isY2(), imeActive, isEmergencyMode());
    }

    /** 2026-07-05 — Companion sets persist.solar.emergency_mode after crash loop. */
    private static boolean isEmergencyMode() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.solar.emergency_mode", "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @deprecated IME-active path uses {@link #shouldOfferBackLongGlobalModal(String, boolean)}.
     */
    public static boolean shouldBlockBackLongWhileImeActive() {
        return false;
    }

    /**
     * IME tray and global overlay are mutually exclusive — IME cannot arm while overlay is up.
     */
    public static boolean canArmImeTray() {
        return SolarImeRouteArbiter.canArm();
    }

    /** Solar HOME / in-app UI — native wheel/back/center, never stock-app inject. */
    public static boolean isSolarForegroundPackage(String pkg) {
        return "com.solar.launcher".equals(pkg);
    }

    /**
     * SystemUI / keyguard / IME — not a user app. Task probes often return these while
     * a stock app sits underneath (e.g. SystemUI {@code UsbStorageActivity} on plug-in).
     */
    public static boolean isSystemShellPackage(String pkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if ("android".equals(pkg)) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.keyguard")) return true;
        if (pkg.startsWith("com.android.inputmethod")) return true;
        return false;
    }

    /** @deprecated use {@link #shouldOfferGlobalModalForPackage} */
    public static boolean shouldOfferForPackage(String pkg) {
        return shouldOfferGlobalModalForPackage(pkg);
    }
}
