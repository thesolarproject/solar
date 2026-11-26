package com.solar.input.policy;

/**
 * 2026-07-06 — Authoritative power/BACK hold eligibility for Solar + companion + Xposed.
 * Layman: one rulebook so sleep button, context menu, and alternate-HOME BACK never fight each other.
 * Technical: pure Java policy (~150 lines); compiled into bridge, companion, and Solar delegate.
 * Reversal: delete JAR; restore duplicated shouldOffer* in SystemServerHooks + GlobalOverlayPolicy.
 */
public final class GlobalInputPolicy {    public static final String POLICY_REV_PROPERTY = "sys.solar.input.policy_rev";
    /** Bump when hold tiers or fail-open paths change — adb: getprop sys.solar.input.policy_rev */
    /**
     * 2026-07-15 — Bump: Y2 short POWER force-sleep only when screen was on at POWER DOWN.
     * Layman: one press wakes a dark screen; the same short press sleeps a lit screen.
     * Was: POLICY_REV 22 always goToSleep on short UP (wake already lit screen → flash→sleep).
     * Reversal: POLICY_REV 22 + unguarded triggerGoToSleep on every short pass-through tap.
     */
    /**
     * 2026-07-17 — POLICY_REV 25: remove Wheel Sense Lab exclusive-capture exemption
     * ({@code com.solar.inputlogger} removed from product tree).
     * Reversal: POLICY_REV 24 + restore exclusive-capture early returns for lab package.
     */
    /**
     * 2026-07-18 — POLICY_REV 26: modal/context hold 350ms; POWER tap max 280ms (no overlap).
     * Was: POLICY_REV 25 with 420/380. Reversal: POLICY_REV 25 + 420L holds + 380L tap max.
     */
    /**
     * 2026-07-20 — POLICY_REV 27: modal/context hold ~280ms; POWER tap max 220ms (no overlap).
     * Layman: Options opens a bit sooner; short taps still stay taps.
     * Was: POLICY_REV 26 with 350/280. Reversal: POLICY_REV 26 + 350L holds + 280L tap max.
     */
    public static final int POLICY_REV = 27;
 
    /**
     * Cross-process — infinite list wrap opt-in for main Solar lists (not context/overlay modal).
     * 2026-07-11 — Modal chips need clamp edges; see {@link ListNavigationPolicy#appliesToContextModal()}.
     */
    public static final String PROP_INFINITE_SCROLL = "persist.solar.nav.infinite_scroll";
 
    /**
     * 2026-07-20 — Short POWER tap window must stay under modal hold (see GLOBAL_MODAL_HOLD_MS).
     * Was 280L with 350ms modal; lowered with 280ms modal so tap and hold never overlap.
     * Reversal: 280L only if modal returns to ≥350L.
     */
    public static final long POWER_TAP_MAX_MS = 220L;
    /**
     * 2026-07-20 — Global modal tier (~280ms): SystemUI shells, stock apps, third-party apps.
     * Layman: hold Back/Power just under a third of a second for the quick menu — taps stay taps.
     * Technical: warmOverlayProcess + postDelayed in SystemServerHooks BACK/POWER paths.
     * Was 350L (felt laggy on 256MB devices); Android long-press default is ~500ms — we sit under it.
     * Best practice: 500ms platform default; 250–300ms for snappy remote holds; &lt;250ms false-fires.
     * Must stay above {@link #POWER_TAP_MAX_MS}. Reversal: 350L + POWER_TAP_MAX 280L.
     */
    public static final long GLOBAL_MODAL_HOLD_MS = 280L;
    /** @deprecated use {@link #GLOBAL_MODAL_HOLD_MS}. */
    public static final long THIRD_PARTY_MODAL_HOLD_MS = GLOBAL_MODAL_HOLD_MS;
    /**
     * 2026-07-08 — Nav-owned HOME (JJ) BACK/POWER passthrough + JJ modal tier (~300 ms).
     * Layman: short Back/Power still reaches JJ; ~0.3 s opens quick menu on JJ.
     * Was 200L under the 130ms fast-open experiment; restore documented accidental-hold guard.
     * Reversal: 200L if JJ needs snappier POWER path again.
     */
    public static final long THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS = 300L;
    /** @deprecated use {@link #THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS}. */
    public static final long NAV_OWNED_LAUNCHER_MODAL_HOLD_MS = THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
    /** @deprecated use {@link #THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS}. */
    public static final long NAV_OWNED_BACK_MODAL_MS = THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
    /** @deprecated use {@link #backModalHoldMsForPackage}. */
    public static final long MODAL_HOLD_MS = GLOBAL_MODAL_HOLD_MS;
    /** 2026-07-20 — OK/center long-press in stock apps — opens row context menu (~280ms). */
    public static final long CENTER_MENU_HOLD_MS = 280L;
    /**
     * 2026-07-20 — Solar HOME BACK hold — in-app quick/options menu (~280ms).
     * Layman: hold Back ~¼s on Solar for Options; tap still goes back.
     * Was 350L; matched GLOBAL_MODAL_HOLD_MS dial-back for snappier feel.
     * Reversal: 350L if Options opens on clumsy taps.
     */
    public static final long SOLAR_BACK_CONTEXT_HOLD_MS = 280L;
    /**
     * 2026-07-08 — Rescue HUD 3..2..1 arms here; finger must still be down.
     * Layman: after ~7s of holding Back, on-screen countdown begins.
     * Was 4900 (−30% from 7s); restore to 7000 so restart stays a full 10s hold.
     * Reversal: set 4900L + RESCUE_EXECUTE_MS=7000L for quicker rescue lab builds.
     */
    public static final long HUD_COUNTDOWN_START_MS = 7000L;
    /** Alias — rescue countdown HUD tier (~7s from hold DOWN). */
    public static final long RESCUE_COUNTDOWN_START_MS = HUD_COUNTDOWN_START_MS;
    /**
     * 2026-07-08 — Continuous BACK/POWER hold before Solar soft-restart or rescue reboot.
     * Layman: keep holding ~10s to restart Solar / force home; shorter holds only open the quick menu.
     * Technical: shared by Xposed PWM, root evdev, companion FSM, MainActivity force-quit.
     * Was 7000 (−30%); user contract is 10s continuous hold — not fire on early release.
     * Reversal: 7000L if 10s feels too long on-device.
     */
    public static final long RESCUE_EXECUTE_MS = 10000L;
    /** Alias — total BACK/POWER hold before solar-rescue-exec / Solar soft-restart. */
    public static final long RESCUE_HOLD_MS = RESCUE_EXECUTE_MS;

    public static final String SOLAR_PKG = "com.solar.launcher";
    /** JJ Launcher (MO-ON) — third-party HOME; longer hold tier, BACK-long modal allowed. */
    public static final String JJ_PKG = "com.themoon.y1";
    /** Stock Innioasis HOME — same wheel keylayout scancodes as JJ (2026-07-08). */
    public static final String INNIOASIS_Y1_PKG = "com.innioasis.y1";
    public static final String INNIOASIS_Y2_PKG = "com.innioasis.y2";
    public static final String INNIOASIS_PREFIX = "com.innioasis.";
    /** android.view.KeyEvent.KEYCODE_BACK — avoid android import in policy-only javac. */
    public static final int KEYCODE_BACK = 4;

    /** android.view.KeyEvent.KEYCODE_POWER — avoid android import in policy-only javac. */
    public static final int KEYCODE_POWER = 26;

    /**
     * 2026-07-08 — JJ + Innioasis stock HOME — same hold tier / MODE_JJ inject.
     * Reversal: SPECIAL list = { JJ_PKG } only; blanket INNIOASIS_PREFIX block restores prior denylist.
     */
    private static final String[] SPECIAL_INPUT_HOLD_LAUNCHER_PKGS = {
            JJ_PKG, INNIOASIS_Y1_PKG, INNIOASIS_Y2_PKG
    };
    /** @deprecated use {@link #SPECIAL_INPUT_HOLD_LAUNCHER_PKGS}. */
    private static final String[] THIRD_PARTY_LAUNCHER_PKGS = SPECIAL_INPUT_HOLD_LAUNCHER_PKGS;

    private GlobalInputPolicy() {}

    /** Y2 short POWER tap — never consume; stock sleep/wake owns the gesture. */
    public static boolean shouldPassthroughPowerTap(long holdMs) {
        return holdMs < POWER_TAP_MAX_MS;
    }

    /**
     * 2026-07-15 — Y2 Xposed may call goToSleep on short POWER when MTK misses sleep.
     * Layman: only force sleep if the screen was already lit when the finger went down.
     * Tech: hold under POWER_TAP_MAX_MS AND screenWasOnAtDown — UP-time isScreenOn is true after wake.
     * Reversal: call goToSleep on every shouldPassthroughPowerTap without DOWN-time screen check.
     */
    public static boolean shouldForcePowerTapSleep(long holdMs, boolean screenWasOnAtDown) {
        return screenWasOnAtDown && shouldPassthroughPowerTap(holdMs);
    }

    /**
     * 2026-07-08 — BACK-long modal delay: ~420 ms apps + SystemUI; ~300 ms JJ passthrough ceiling.
     * Reversal: shorten GLOBAL_MODAL_HOLD_MS / THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS for faster open labs.
     */
    public static long backModalHoldMsForPackage(String pkg) {
        if (isNavOwnedHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        if (isThirdPartyHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        return GLOBAL_MODAL_HOLD_MS;
    }

    /**
     * 2026-07-08 — Y2 POWER long-hold — ~300 ms passthrough on JJ; ~420 ms elsewhere.
     */
    public static long powerModalHoldMsForPackage(String pkg) {
        if (isNavOwnedHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        if (isThirdPartyHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        return GLOBAL_MODAL_HOLD_MS;
    }

    /**
     * 2026-07-08 — Finger-lift grace after modal open — ignore early UP so open hold won't dismiss.
     * Layman: keep holding a moment after the menu appears; lifting right away won't close it.
     * Nav-owned grace matches that tier; other apps use half-open (~210 ms).
     */
    public static long overlayDismissGraceMsForPackage(String pkg) {
        if (isNavOwnedHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        if (isThirdPartyHomeLauncher(pkg)) return THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
        // 2026-07-18 — Half of GLOBAL_MODAL_HOLD_MS (was 210 when hold was 420).
        return GLOBAL_MODAL_HOLD_MS / 2L;
    }

    /**
     * 2026-07-14 — Y2 POWER-hold quick menu only while Solar is foreground (in-app sheet).
     * Layman: hold sleep in Solar for Solar's menu; any other app keeps the stock Android power menu.
     * Was: WM/companion modal over JJ/stock/SystemUI (heavy PWM + startService per hold).
     * Reversal: restore 3P/JJ/fail-open offer branches from POLICY_REV 21.
     */
    public static boolean shouldOfferPowerLongModal(String pkg, boolean y2Device) {
        if (!y2Device) return false;
        // 2026-11-26 — Power-hold must still reach Solar when the PWM foreground probe misses
        // (empty / systemui / shell) and when it reports one of Solar's own processes such as
        // com.solar.launcher:overlay. Was: strict SOLAR_PKG.equals(pkg), so one probe lie made
        // the hold do nothing at all.
        // Reversal: drop the fail-open + sub-process match.
        if (shouldFailOpenPowerFg(pkg)) return true;
        if (SOLAR_PKG.equals(pkg)) return true;
        return pkg.startsWith(SOLAR_PKG + ":");
    }

    /**
     * 2026-07-14 — HOLD BACK outside Solar jumps straight to Solar Home (no global quick menu).
     * Layman: holding Back in Settings or another app brings you home to Solar.
     * Technical: Solar Home uses in-app menu; IME over nav-owned launchers may escape home.
     * Reversal: delete callers; restore showPowerOverlay for third-party BACK-long.
     */
    public static boolean shouldLaunchSolarOnBackLong(String pkg, boolean imeActive,
            boolean emergencyMode) {
        if (SOLAR_PKG.equals(pkg)) return false;
        if (imeActive) {
            if (pkg == null || pkg.length() == 0) return false;
            if (isInnioasisNonLauncherPackage(pkg)) return false;
            if (isSystemShellPackage(pkg)) return false;
            return true;
        }
        if (isJjKeylayoutLauncher(pkg)) return true;
        if (shouldFailOpenBackFg(pkg)) return true;
        if (pkg == null || pkg.length() == 0) return false;
        if (isInnioasisNonLauncherPackage(pkg)) return false;
        if (isSystemShellPackage(pkg)) return false;
        return true;
    }

    /** Y2 systemui/null/android fg — PWM probe lie; still offer POWER modal. */
    public static boolean shouldFailOpenPowerFg(String pkg) {
        if (pkg == null || pkg.length() == 0) return true;
        return isSystemShellPackage(pkg);
    }

    /**
     * 2026-07-06 — SystemUI/keyguard fg on BACK-long — same fail-open as POWER (USB dialog fast path).
     * Reversal: remove; BACK-long blocked when WM reports com.android.systemui.
     */
    public static boolean shouldFailOpenBackFg(String pkg) {
        return shouldFailOpenPowerFg(pkg);
    }

    /**
     * 2026-07-06 — BACK/POWER hold opens full global context modal (launcher switch is a chip inside).
     * Reversal: restore 600ms {@code SHOW_OVERLAY_LAUNCHER_PICKER}-only path in bridge hooks.
     */
    public static boolean shouldOfferLauncherPickerOnBackHold(String pkg, boolean y2Device,
            boolean imeActive, boolean emergencyMode) {
        return shouldOfferBackLongModal(pkg, y2Device, imeActive, emergencyMode);
    }

    /** 2026-07-06 — Y2 POWER hold opens global quick menu (includes Solar/JJ fg). */
    public static boolean shouldOfferLauncherPickerOnPowerHold(String pkg, boolean y2Device) {
        return shouldOfferPowerLongModal(pkg, y2Device);
    }

    /**
     * 2026-07-14 — BACK-long arm eligibility (launch Solar home — not a WM quick menu).
     * Layman: same packages that used to get the floating quick menu now just jump to Solar.
     * Name kept for bridge/companion call sites; action is {@link #shouldLaunchSolarOnBackLong}.
     * Reversal: POLICY_REV 21 body that meant showPowerOverlay for third-party fg.
     */
    public static boolean shouldOfferBackLongModal(String pkg, boolean y2Device,
            boolean imeActive, boolean emergencyMode) {
        return shouldLaunchSolarOnBackLong(pkg, imeActive, emergencyMode);
    }

    /** 2026-07-06 — JJ BACK pass through until modal tier; overlay owns keys after. */
    public static boolean shouldPassthroughNavOwnedLauncherBack(long holdMs, boolean y2Device,
            boolean emergencyMode, boolean imeActive) {
        if (imeActive) return false;
        return holdMs < THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
    }

    /** Y2 POWER on nav-owned HOME — JJ passthrough until 300 ms tier. */
    public static boolean shouldPassthroughNavOwnedLauncherPower(long holdMs, String pkg,
            boolean imeActive) {
        if (imeActive) return false;
        long ceiling = isNavOwnedHomeLauncher(pkg)
                ? THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS
                : GLOBAL_MODAL_HOLD_MS;
        return holdMs < ceiling;
    }

    /** @deprecated use {@link #shouldPassthroughNavOwnedLauncherPower(long, String, boolean)}. */
    public static boolean shouldPassthroughNavOwnedLauncherPower(long holdMs, boolean imeActive) {
        return holdMs < THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS;
    }

    /** True for JJ-style HOME — overlay key capture + BACK passthrough ceiling. */
    public static boolean isNavOwnedHomeLauncher(String pkg) {
        return isJjKeylayoutLauncher(pkg);
    }

    /**
     * 2026-07-07 — Nav-owned hold tier — JJ/Innioasis BACK-long modal; JJ passthrough ceiling.
     * Generic Android CATEGORY_HOME launchers use {@link #GLOBAL_MODAL_HOLD_MS} like stock apps.
     * Nav-owned launchers use BACK passthrough tier rules.
     */
    public static boolean isThirdPartyHomeLauncher(String pkg) {
        return isSpecialInputHoldLauncher(pkg);
    }

    /** PM scan cache — comma-separated HOME packages (see LauncherDiscovery.sync). */
    public static final String PROP_HOME_LAUNCHER_PKGS = "persist.solar.home.launch_pkgs";

    /**
     * 2026-07-08 — JJ + stock Innioasis Y1/Y2 HOME — same keylayout/scancodes → MODE_JJ inject.
     * Layman: wheel left/right works the same in JJ and the factory Innioasis launcher.
     * Reversal: return JJ_PKG.equals(pkg) only.
     */
    public static boolean isJjKeylayoutLauncher(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (JJ_PKG.equals(pkg)) return true;
        return isInnioasisStockLauncher(pkg);
    }

    /**
     * 2026-07-08 — HOME targets that need the JJ wheel remap even when Solar is disabled.
     * Layman: if the saved home is JJ or the factory launcher, the wheel must still scroll.
     * Technical: matches persist.solar.home.target values "jj"/"stock" (HomeTargetPolicy);
     * read by JjInputHooks as a root-prop fallback when Solar can't set sys.solar.handoff.jj.
     * Reversal: return false — remap arms only via Solar's sys.solar.handoff.jj runtime prop.
     */
    public static boolean isJjKeylayoutHomeTarget(String target) {
        return "jj".equals(target) || "stock".equals(target);
    }

    /**
     * 2026-07-08 — Factory Innioasis HOME packages (y1/y2 under com.innioasis.).
     * Reversal: always return false — blanket INNIOASIS_PREFIX denylist returns.
     */
    public static boolean isInnioasisStockLauncher(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (INNIOASIS_Y1_PKG.equals(pkg) || INNIOASIS_Y2_PKG.equals(pkg)) return true;
        if (!pkg.startsWith(INNIOASIS_PREFIX)) return false;
        String rest = basePackageName(pkg).substring(INNIOASIS_PREFIX.length());
        // Only y1/y2 HOME (and process suffixes) — not music/fm/other vendor apps.
        return "y1".equals(rest) || "y2".equals(rest)
                || rest.startsWith("y1.") || rest.startsWith("y2.");
    }

    /**
     * 2026-07-08 — Other vendor apps under com.innioasis.* (music, fm) — keep prior overlay denylist.
     * Layman: only the home screen app gets quick menu; other stock apps stay as before.
     */
    public static boolean isInnioasisNonLauncherPackage(String pkg) {
        if (pkg == null || !pkg.startsWith(INNIOASIS_PREFIX)) return false;
        return !isInnioasisStockLauncher(pkg);
    }

    /** JJ + Innioasis stock HOME — not generic PM-discovered HOME launchers. */
    public static boolean isSpecialInputHoldLauncher(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (isJjKeylayoutLauncher(pkg)) return true;
        for (int i = 0; i < SPECIAL_INPUT_HOLD_LAUNCHER_PKGS.length; i++) {
            if (SPECIAL_INPUT_HOLD_LAUNCHER_PKGS[i].equals(pkg)) return true;
        }
        return false;
    }

    /**
     * 2026-07-07 — True when package appears in PM-synced HOME list — uses 420 ms modal tier.
     * Layman: generic alternate launchers behave like Settings, not like JJ.
     */
    public static boolean isPersistedHomeLauncher(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (isNavOwnedHomeLauncher(pkg) || SOLAR_PKG.equals(pkg)) return false;
        String raw = readSysProp(PROP_HOME_LAUNCHER_PKGS, "");
        if (raw.length() == 0) return false;
        String base = basePackageName(pkg);
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (base.equals(basePackageName(parts[i].trim()))) return true;
        }
        return false;
    }

    private static String basePackageName(String procOrPkg) {
        if (procOrPkg == null) return "";
        int colon = procOrPkg.indexOf(':');
        if (colon > 0) return procOrPkg.substring(0, colon);
        return procOrPkg;
    }

    private static String readSysProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v != null ? String.valueOf(v) : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Ultra-long rescue HUD — any fg except Solar on BACK; Y2 POWER includes Solar fg; null fg OK at boot. */
    public static boolean shouldArmRescueHoldForPackage(String pkg, int keyCode) {
        if (SOLAR_PKG.equals(pkg)) {
            return keyCode == KEYCODE_POWER;
        }
        return true;
    }

    public static boolean isSystemShellPackage(String pkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if ("android".equals(pkg)) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.keyguard")) return true;
        if (pkg.startsWith("com.android.inputmethod")) return true;
        return false;
    }
}
