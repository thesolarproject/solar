package com.solar.launcher;

/** Stable settings sub-screen keys (locale-independent navigation). */
public final class SettingsScreens {
    public static final String APPEARANCE = "settings.appearance";
    public static final String THEMES = "settings.themes";
    public static final String THEME_PICKER = "settings.theme_picker";
    public static final String SOULSEEK = "settings.soulseek";
    public static final String SOULSEEK_CONNECTION = "settings.soulseek.connection";
    public static final String SOULSEEK_ABOUT = "settings.soulseek.about";
    public static final String ABOUT = "settings.about";
    public static final String SYSTEM_UPDATE = "settings.system_update";
    public static final String HOME = "settings.home";
    public static final String HOME_ARRANGE = "settings.home.arrange";
    public static final String HOME_MORE = "settings.home.more";
    public static final String HOME_MORE_ARRANGE = "settings.home.more_arrange";
    public static final String BACKGROUND = "settings.background";
    public static final String NOW_PLAYING = "settings.now_playing";
    public static final String DATETIME = "settings.datetime";
    public static final String LANGUAGE = "settings.language";
    /** Theme variant picker: key + dynamic theme name in settingsSubScreenExtra. */
    public static final String THEME_VARIANT = "settings.theme_variant";
    /** EQ preset picker: key + preset name in settingsSubScreenExtra. */
    public static final String EQ = "settings.eq";
    public static final String LIBRARY_BROWSE = "settings.library_browse";

    public static int titleResId(String key) {
        if (key == null) return 0;
        if (APPEARANCE.equals(key)) return R.string.settings_sub_appearance;
        if (THEMES.equals(key)) return R.string.settings_sub_themes;
        if (THEME_PICKER.equals(key)) return R.string.settings_sub_theme_picker;
        if (SOULSEEK.equals(key)) return R.string.settings_sub_soulseek;
        if (SOULSEEK_CONNECTION.equals(key)) return R.string.settings_sub_soulseek_connection;
        if (SOULSEEK_ABOUT.equals(key)) return R.string.settings_sub_soulseek_about;
        if (ABOUT.equals(key)) return R.string.settings_sub_about;
        if (SYSTEM_UPDATE.equals(key)) return R.string.settings_sub_system_update;
        if (HOME.equals(key)) return R.string.settings_sub_home;
        if (HOME_ARRANGE.equals(key)) return R.string.settings_sub_home_arrange;
        if (HOME_MORE.equals(key)) return R.string.settings_sub_home_more_arrange;
        if (HOME_MORE_ARRANGE.equals(key)) return R.string.settings_sub_home_more_arrange;
        if (BACKGROUND.equals(key)) return R.string.settings_sub_background;
        if (NOW_PLAYING.equals(key)) return R.string.settings_sub_now_playing;
        if (DATETIME.equals(key)) return R.string.settings_sub_datetime;
        if (LANGUAGE.equals(key)) return R.string.settings_sub_language;
        if (EQ.equals(key)) return R.string.settings_sub_eq;
        if (THEME_VARIANT.equals(key)) return R.string.settings_sub_theme_variant;
        if (LIBRARY_BROWSE.equals(key)) return R.string.settings_sub_library_browse;
        return 0;
    }

    public static boolean isSoulseek(String key) {
        return key != null && key.startsWith("settings.soulseek");
    }

    public static boolean isHome(String key) {
        return key != null && key.startsWith("settings.home");
    }

    public static boolean isAppearance(String key) {
        return APPEARANCE.equals(key) || HOME.equals(key) || HOME_ARRANGE.equals(key)
                || HOME_MORE.equals(key) || HOME_MORE_ARRANGE.equals(key)
                || BACKGROUND.equals(key) || NOW_PLAYING.equals(key) || THEME_PICKER.equals(key) || THEMES.equals(key)
                || THEME_VARIANT.equals(key);
    }

    public static boolean isThemes(String key) {
        return THEMES.equals(key) || THEME_VARIANT.equals(key);
    }

    private SettingsScreens() {}
}
