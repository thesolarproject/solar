package com.solar.launcher;

/** Stable settings sub-screen keys (locale-independent navigation). */
public final class SettingsScreens {
    public static final String APPEARANCE = "settings.appearance";
    public static final String THEMES = "settings.themes";
    public static final String THEME_PICKER = "settings.theme_picker";
    public static final String REACH = "settings.reach";
    public static final String SOULSEEK = "settings.soulseek";
    public static final String SOULSEEK_CONNECTION = "settings.soulseek.connection";
    public static final String SOULSEEK_ABOUT = "settings.soulseek.about";
    public static final String SOULSEEK_FIND_USER = "settings.soulseek.find_user";
    public static final String SOULSEEK_FIND_REACH = "settings.soulseek.find_reach";
    public static final String SOULSEEK_MESSAGES = "settings.soulseek.messages";
    public static final String SOULSEEK_MESSAGES_THREAD = "settings.soulseek.messages.thread";
    public static final String SOULSEEK_CHAT_ROOMS = "settings.soulseek.chat_rooms";
    public static final String SOULSEEK_CHAT_ROOM_THREAD = "settings.soulseek.chat_room.thread";
    public static final String SOULSEEK_CHAT_ROOM_WALL = "settings.soulseek.chat_room.wall";
    public static final String SOULSEEK_INTERESTS = "settings.soulseek.interests";
    public static final String SOULSEEK_USER_PROFILE = "settings.soulseek.user_profile";
    public static final String DEEZER = "settings.deezer";
    public static final String DEEZER_ACCOUNT = "settings.deezer.account";
    public static final String DEEZER_CONNECTION = "settings.deezer.connection";
    public static final String ABOUT = "settings.about";
    public static final String DEBUG = "settings.debug";
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
    public static final String RADIO = "settings.radio";
    public static final String RADIO_FM_BAND = "settings.radio.fm_band";
    public static final String RADIO_INTERNET_COUNTRY = "settings.radio.internet_country";
    public static final String VIDEO = "settings.video";

    public static int titleResId(String key) {
        if (key == null) return 0;
        if (APPEARANCE.equals(key)) return R.string.settings_sub_appearance;
        if (THEMES.equals(key)) return R.string.settings_sub_themes;
        if (THEME_PICKER.equals(key)) return R.string.settings_sub_theme_picker;
        if (REACH.equals(key)) return R.string.settings_sub_reach;
        if (SOULSEEK.equals(key)) return R.string.settings_sub_soulseek;
        if (SOULSEEK_CONNECTION.equals(key)) return R.string.settings_sub_soulseek_connection;
        if (SOULSEEK_ABOUT.equals(key)) return R.string.settings_sub_soulseek_about;
        if (SOULSEEK_FIND_USER.equals(key)) return R.string.settings_sub_soulseek_find_user;
        if (SOULSEEK_FIND_REACH.equals(key)) return R.string.settings_sub_soulseek_find_reach;
        if (SOULSEEK_MESSAGES.equals(key)) return R.string.settings_sub_soulseek_messages;
        if (SOULSEEK_MESSAGES_THREAD.equals(key)) return R.string.settings_sub_soulseek_messages_thread;
        if (SOULSEEK_CHAT_ROOMS.equals(key)) return R.string.settings_sub_soulseek_chat_rooms;
        if (SOULSEEK_CHAT_ROOM_THREAD.equals(key)) return R.string.settings_sub_soulseek_chat_room;
        if (SOULSEEK_CHAT_ROOM_WALL.equals(key)) return R.string.settings_sub_soulseek_chat_room_wall;
        if (SOULSEEK_INTERESTS.equals(key)) return R.string.settings_sub_soulseek_interests;
        if (SOULSEEK_USER_PROFILE.equals(key)) return R.string.settings_sub_soulseek_user_profile;
        if (DEEZER.equals(key)) return R.string.settings_sub_deezer;
        if (DEEZER_ACCOUNT.equals(key)) return R.string.settings_sub_deezer_account;
        if (DEEZER_CONNECTION.equals(key)) return R.string.settings_sub_deezer_connection;
        if (ABOUT.equals(key)) return R.string.settings_sub_about;
        if (DEBUG.equals(key)) return R.string.settings_sub_debug;
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
        if (RADIO.equals(key)) return R.string.settings_sub_radio;
        if (RADIO_FM_BAND.equals(key)) return R.string.settings_sub_radio_fm_band;
        if (RADIO_INTERNET_COUNTRY.equals(key)) return R.string.settings_sub_radio_internet_country;
        if (VIDEO.equals(key)) return R.string.settings_sub_video;
        return 0;
    }

    public static boolean isReach(String key) {
        return REACH.equals(key);
    }

    public static boolean isSoulseek(String key) {
        return key != null && key.startsWith("settings.soulseek");
    }

    public static boolean isDeezer(String key) {
        return key != null && key.startsWith("settings.deezer");
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
