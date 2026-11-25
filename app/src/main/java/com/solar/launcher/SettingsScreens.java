package com.solar.launcher;

/** 2026-07-05 — Stable settings screen keys; dual-pane preview allowed for toggles only, not browse. */
public final class SettingsScreens {
    public static final String APPEARANCE = "settings.appearance";
    public static final String APPEARANCE_STATUS = "settings.appearance.status";
    public static final String APPEARANCE_LAYOUT = "settings.appearance.layout";
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
    /** Full-screen GitHub Issues URL for bug reports (no in-app browser). */
    public static final String REPORT_ISSUE = "settings.report_issue";
    /** Full-screen Donations / Ko-fi URL (no in-app browser). */
    public static final String SUPPORT_DEVELOPER = "settings.support_developer";
    /** Online-only donor roll fetched from donators.xml on GitHub Pages. */
    public static final String DONORS_LIST = "settings.donors_list";
    public static final String CONNECTIONS = "settings.connections";
    public static final String USB = "settings.usb";
    public static final String DEVICE = "settings.device";
    /** Solar vs Rockbox HOME — user picks here instead of Android launcher dialogs. */
    public static final String HOME_LAUNCHER = "settings.device.home_launcher";
    public static final String PLAYBACK = "settings.playback";
    public static final String LIBRARY = "settings.library";
    public static final String MEDIA = "settings.media";
    public static final String POWER = "settings.power";
    public static final String RESET = "settings.reset";
    public static final String DEBUG = "settings.debug";
    public static final String FLOW = "settings.flow";
    /** Debug submenu — staged Xposed module toggles (reboot to apply). */
    public static final String XPOSED_MODULES = "settings.debug.xposed_modules";
    /** One installed hook — enable + inline / external config; extra = module label. */
    public static final String XPOSED_MODULE_DETAIL = "settings.debug.xposed_module_detail";
    /** Debug submenu — agent connection/test workflow for live fixes on Y1/Y2. */
    public static final String AGENT_DEVELOPER = "settings.debug.agent_developer";
    public static final String SYSTEM_UPDATE = "settings.system_update";
    public static final String HOME = "settings.home";
    public static final String HOME_ARRANGE = "settings.home.arrange";
    public static final String HOME_MORE = "settings.home.more";
    public static final String HOME_MORE_ARRANGE = "settings.home.more_arrange";
    public static final String BACKGROUND = "settings.background";
    public static final String NOW_PLAYING = "settings.now_playing";
    public static final String DATETIME = "settings.datetime";
    public static final String LANGUAGE = "settings.language";
    /** Y2 primary storage medium picker (MicroSD vs internal). */
    public static final String Y2_PRIMARY_STORAGE = "settings.y2_primary_storage";
    /** Theme variant picker: key + dynamic theme name in settingsSubScreenExtra. */
    public static final String THEME_VARIANT = "settings.theme_variant";
    /** EQ preset picker: key + preset name in settingsSubScreenExtra. */
    public static final String EQ = "settings.eq";
    public static final String LIBRARY_BROWSE = "settings.library_browse";
    public static final String NAVIDROME = "settings.navidrome";
    public static final String PLEX = "settings.plex";
    public static final String JELLYFIN = "settings.jellyfin";
    public static final String RADIO = "settings.radio";
    public static final String RADIO_FM = "settings.radio.fm";
    public static final String RADIO_FM_BAND = "settings.radio.fm_band";
    public static final String RADIO_INTERNET_COUNTRY = "settings.radio.internet_country";
    public static final String VIDEO = "settings.video";
    public static final String SCROBBLING = "settings.scrobbling";

    public static int titleResId(String key) {
        if (key == null) return 0;
        if (APPEARANCE.equals(key)) return R.string.settings_sub_appearance;
        if (APPEARANCE_STATUS.equals(key)) return R.string.settings_sub_appearance_status;
        if (APPEARANCE_LAYOUT.equals(key)) return R.string.settings_sub_appearance_layout;
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
        if (REPORT_ISSUE.equals(key)) return R.string.settings_sub_report_issue;
        if (SUPPORT_DEVELOPER.equals(key)) return R.string.settings_sub_support_developer;
        if (DONORS_LIST.equals(key)) return R.string.settings_sub_our_donors;
        if (CONNECTIONS.equals(key)) return R.string.settings_sub_connections;
        if (USB.equals(key)) return R.string.settings_sub_usb;
        if (DEVICE.equals(key)) return R.string.settings_sub_device;
        if (HOME_LAUNCHER.equals(key)) return R.string.settings_home_launcher;
        if (PLAYBACK.equals(key)) return R.string.settings_sub_playback;
        if (LIBRARY.equals(key)) return R.string.settings_sub_library;
        if (MEDIA.equals(key)) return R.string.settings_sub_media;
        if (POWER.equals(key)) return R.string.settings_sub_power;
        if (RESET.equals(key)) return R.string.settings_sub_reset;
        if (DEBUG.equals(key)) return R.string.settings_sub_debug;
        if (FLOW.equals(key)) return R.string.settings_sub_flow;
        if (XPOSED_MODULES.equals(key)) return R.string.settings_debug_xposed_modules;
        if (XPOSED_MODULE_DETAIL.equals(key)) return R.string.settings_debug_xposed_module_detail_title;
        if (AGENT_DEVELOPER.equals(key)) return R.string.settings_debug_agent_developer;
        if (SYSTEM_UPDATE.equals(key)) return R.string.settings_sub_system_update;
        if (HOME.equals(key)) return R.string.settings_sub_home;
        if (HOME_ARRANGE.equals(key)) return R.string.settings_sub_home_arrange;
        if (HOME_MORE.equals(key)) return R.string.settings_sub_home_more_arrange;
        if (HOME_MORE_ARRANGE.equals(key)) return R.string.settings_sub_home_more_arrange;
        if (BACKGROUND.equals(key)) return R.string.settings_sub_background;
        if (NOW_PLAYING.equals(key)) return R.string.settings_sub_now_playing;
        if (DATETIME.equals(key)) return R.string.settings_sub_datetime;
        if (LANGUAGE.equals(key)) return R.string.settings_sub_language;
        if (Y2_PRIMARY_STORAGE.equals(key)) return R.string.settings_sub_y2_primary_storage;
        if (EQ.equals(key)) return R.string.settings_sub_eq;
        if (THEME_VARIANT.equals(key)) return R.string.settings_sub_theme_variant;
        if (LIBRARY_BROWSE.equals(key)) return R.string.settings_sub_library_browse;
        if (NAVIDROME.equals(key)) return R.string.settings_navidrome;
        if (PLEX.equals(key)) return R.string.settings_plex;
        if (JELLYFIN.equals(key)) return R.string.settings_jellyfin;
        if (RADIO.equals(key)) return R.string.settings_sub_radio;
        if (RADIO_FM.equals(key)) return R.string.settings_sub_fm;
        if (RADIO_FM_BAND.equals(key)) return R.string.settings_sub_radio_fm_band;
        if (RADIO_INTERNET_COUNTRY.equals(key)) return R.string.settings_sub_radio_internet_country;
        if (VIDEO.equals(key)) return R.string.settings_sub_video;
        if (SCROBBLING.equals(key)) return R.string.settings_scrobbling;
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
        if (HOME_LAUNCHER.equals(key)) return false;
        return key != null && key.startsWith("settings.home");
    }

    public static boolean isAppearance(String key) {
        return APPEARANCE.equals(key) || APPEARANCE_STATUS.equals(key) || APPEARANCE_LAYOUT.equals(key)
                || HOME.equals(key) || HOME_ARRANGE.equals(key)
                || HOME_MORE.equals(key) || HOME_MORE_ARRANGE.equals(key)
                || BACKGROUND.equals(key) || NOW_PLAYING.equals(key) || THEME_PICKER.equals(key) || THEMES.equals(key)
                || THEME_VARIANT.equals(key);
    }

    public static boolean isThemes(String key) {
        return THEMES.equals(key) || THEME_VARIANT.equals(key);
    }

    /**
     * 2026-07-14 — Portrait bottom strip (= Y1 dual-pane preview stand-in) only for simple prefs.
     * Layman: toggle lists keep a hint strip; About / chat / catalogs stay full height.
     * Tech: denylist document & browse keys; allowlist preference roots; unknown → false.
     * Reversal: always return true so every SETTINGS sub-screen shows the strip.
     */
    public static boolean allowsPortraitPreviewStrip(String key) {
        if (key == null) return false;
        // Document / legal / donor — never a second pane.
        if (ABOUT.equals(key) || REPORT_ISSUE.equals(key) || SUPPORT_DEVELOPER.equals(key)
                || DONORS_LIST.equals(key) || SOULSEEK_ABOUT.equals(key)) {
            return false;
        }
        // Catalogs / threads / browse — full width, no strip.
        if (THEME_PICKER.equals(key) || LIBRARY_BROWSE.equals(key)) return false;
        if (isSoulseek(key) && !SOULSEEK_CONNECTION.equals(key) && !SOULSEEK.equals(key)) {
            return false;
        }
        if (SOULSEEK_FIND_USER.equals(key) || SOULSEEK_FIND_REACH.equals(key)
                || SOULSEEK_MESSAGES.equals(key) || SOULSEEK_MESSAGES_THREAD.equals(key)
                || SOULSEEK_CHAT_ROOMS.equals(key) || SOULSEEK_CHAT_ROOM_THREAD.equals(key)
                || SOULSEEK_CHAT_ROOM_WALL.equals(key) || SOULSEEK_INTERESTS.equals(key)
                || SOULSEEK_USER_PROFILE.equals(key)) {
            return false;
        }
        // Simple preference / toggle surfaces (Y1 right-pane hints).
        if (APPEARANCE.equals(key) || APPEARANCE_STATUS.equals(key) || APPEARANCE_LAYOUT.equals(key)
                || THEMES.equals(key) || THEME_VARIANT.equals(key)
                || CONNECTIONS.equals(key) || USB.equals(key) || DEVICE.equals(key)
                || HOME_LAUNCHER.equals(key) || PLAYBACK.equals(key) || LIBRARY.equals(key)
                || MEDIA.equals(key) || POWER.equals(key) || RESET.equals(key)
                || DEBUG.equals(key) || FLOW.equals(key) || XPOSED_MODULES.equals(key)
                || XPOSED_MODULE_DETAIL.equals(key) || AGENT_DEVELOPER.equals(key)
                || SYSTEM_UPDATE.equals(key)
                || HOME.equals(key) || HOME_ARRANGE.equals(key) || HOME_MORE.equals(key)
                || HOME_MORE_ARRANGE.equals(key) || BACKGROUND.equals(key)
                || NOW_PLAYING.equals(key) || DATETIME.equals(key) || LANGUAGE.equals(key)
                || Y2_PRIMARY_STORAGE.equals(key)
                || REACH.equals(key) || SOULSEEK.equals(key) || SOULSEEK_CONNECTION.equals(key)
                || DEEZER.equals(key) || DEEZER_ACCOUNT.equals(key) || DEEZER_CONNECTION.equals(key)
                || NAVIDROME.equals(key) || PLEX.equals(key) || JELLYFIN.equals(key) || RADIO.equals(key)
                || RADIO_FM.equals(key) || RADIO_FM_BAND.equals(key)
                || RADIO_INTERNET_COUNTRY.equals(key) || VIDEO.equals(key)) {
            return true;
        }
        return false;
    }

    private SettingsScreens() {}
}
