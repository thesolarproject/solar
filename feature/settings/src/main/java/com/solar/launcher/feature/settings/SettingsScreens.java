package com.solar.launcher.feature.settings;

/** Stable settings sub-screen keys (locale-independent navigation). */
public final class SettingsScreens {
    public static final String APPEARANCE = "settings.appearance";
    public static final String THEMES = "settings.themes";
    public static final String THEME_PICKER = "settings.theme_picker";
    public static final String SOULSEEK = "settings.soulseek";
    public static final String SOULSEEK_CONNECTION = "settings.soulseek.connection";
    public static final String SOULSEEK_ABOUT = "settings.soulseek.about";
    public static final String SOULSEEK_MESSAGES = "settings.soulseek.messages";
    public static final String SOULSEEK_MESSAGES_THREAD = "settings.soulseek.messages.thread";
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
    /** Last.fm / ListenBrainz scrobbling prefs (also configurable via Wi‑Fi transfer). */
    public static final String SCROBBLING = "settings.scrobbling";

    /** App maps keys to titles; module has no app R. */
    public static int titleResId(String key) {
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
