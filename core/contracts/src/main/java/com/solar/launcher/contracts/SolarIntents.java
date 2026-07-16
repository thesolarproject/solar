package com.solar.launcher.contracts;

/** Cross-APK Intent actions for keyboard IME and quick-menu overlay. */
public final class SolarIntents {
    private SolarIntents() {}

    public static final String PKG_KEYBOARD = "com.solar.keyboard";
    public static final String PKG_QUICKMENU = "com.solar.quickmenu";

    public static final String ACTION_QUICKMENU_SHOW = "com.solar.quickmenu.SHOW";
    public static final String ACTION_QUICKMENU_DISMISS = "com.solar.quickmenu.DISMISS";
    public static final String EXTRA_QUICKMENU_TIER = "tier";

    public static final String ACTION_KEYBOARD_SHOW_COMPACT = "com.solar.keyboard.SHOW_COMPACT";
}
