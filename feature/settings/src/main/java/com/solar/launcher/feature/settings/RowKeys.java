package com.solar.launcher.feature.settings;

import com.solar.launcher.feature.home.HomeMenuConfig;

/** Stable row identifiers for settings UI (locale-independent). */
public final class RowKeys {
    public static final String SHUFFLE = "settings.shuffle";
    public static final String REPEAT = "settings.repeat";
    public static final String EQ = "settings.eq";
    public static final String BUTTON_SOUND = "settings.button_sound";
    public static final String BUTTON_VIBRATE = "settings.button_vibrate";
    public static final String SCREEN_OFF_CTRL = "settings.screen_off_control";
    public static final String APP_THEME = "settings.app_theme";
    public static final String GET_THEMES = "settings.get_themes";
    public static final String THEMES = "settings.themes";
    public static final String HOME_MORE = "settings.home_more";
    public static final String HOME_MANAGE_MORE = "settings.home_manage_more";
    public static final String APPEARANCE = "settings.appearance";
    public static final String STATUS_BAR_LEFT = "settings.status_bar_left";
    public static final String STATUS_BAR_MATCH_FONT = "settings.status_bar_match_font";
    public static final String SCREEN_TIMEOUT = "settings.screen_timeout";
    public static final String FULL_WIDTH = "settings.full_width";
    public static final String POWER_OFF = "settings.power_off";
    public static final String WEB_SERVER = "settings.web_server";
    public static final String WIFI_SETUP = "settings.wifi_setup";
    public static final String SOULSEEK = "settings.soulseek";
    public static final String AUTO_FETCH = "settings.auto_fetch";
    public static final String LIBRARY_BROWSE = "settings.library_browse";
    public static final String LIB_SPLIT_CREDITS = "library.split_credits";
    public static final String LIB_NORM_ALBUM = "library.norm_album";
    public static final String LIB_GUEST_MODE = "library.guest_mode";
    public static final String LIB_ARTIST_FILTER = "library.artist_filter";
    public static final String LIB_ARTIST_SORT = "library.artist_sort";
    public static final String LIB_SONG_SORT = "library.song_sort";
    public static final String LIB_ALBUM_SUB = "library.album_sub";
    public static final String LIB_GUEST_SUB = "library.guest_sub";
    public static final String ABOUT = "settings.about";
    public static final String SYSTEM_UPDATE = "settings.system_update";
    public static final String BLUETOOTH_SETUP = "settings.bluetooth_setup";
    public static final String BRIGHTNESS = "settings.brightness";
    public static final String STORAGE = "settings.storage";
    public static final String BACKGROUND = "settings.background";
    public static final String NOW_PLAYING = "settings.now_playing";
    public static final String NOW_PLAYING_ALBUM_BLUR = "settings.now_playing.album_blur";
    public static final String CLEAR_CACHE = "settings.clear_cache";
    public static final String DATETIME = "settings.datetime";
    public static final String LANGUAGE = "settings.language";
    public static final String HOME_SCREEN = "settings.home_screen";
    public static final String SOULSEEK_SEARCH = "soulseek.search";
    public static final String SOULSEEK_ACCOUNT = "soulseek.account";
    public static final String SOULSEEK_CONNECTION = "soulseek.connection";
    public static final String SOULSEEK_ABOUT = "soulseek.about";
    public static final String SOULSEEK_MESSAGES = "soulseek.messages";
    public static final String SOULSEEK_REGENERATE = "soulseek.regenerate";
    public static final String SOULSEEK_HIDE_HIGH_BITRATE = "soulseek.hide_high_bitrate";
    public static final String SOULSEEK_SHARING = "soulseek.sharing";
    public static final String WIDGET_CLOCK = "widget.clock";
    public static final String WIDGET_BATTERY = "widget.battery";
    public static final String WIDGET_ALBUM = "widget.album";
    public static final String HOME_ARRANGE = "home.arrange";
    public static final String MORE_MENU = "settings.more_menu";
    public static final String BG_SOURCE = "background.source";
    public static final String BG_SELECT = "background.select";
    public static final String BG_CLEAR = "background.clear";
    public static final String BT_POWER = "bluetooth.power";
    public static final String WIFI_POWER = "wifi.power";
    public static final String UPDATE_CURRENT = "update.current";
    public static final String UPDATE_STABLE = "update.stable";
    public static final String UPDATE_NIGHTLY = "update.nightly";
    public static final String UPDATE_LATEST = "update.latest";
    public static final String DT_YEAR = "datetime.year";
    public static final String DT_MONTH = "datetime.month";
    public static final String DT_DAY = "datetime.day";
    public static final String DT_HOUR = "datetime.hour";
    public static final String DT_MINUTE = "datetime.minute";
    public static final String LANG_SYSTEM = "language.system";
    public static final String LANG_EN = "language.en";
    public static final String LANG_KO = "language.ko";

    /** Settings → Scrobbling (Last.fm / ListenBrainz). */
    public static final String SCROBBLING = "settings.scrobbling";
    public static final String LASTFM_ENABLE = "settings.scrobbling.lastfm.enable";
    public static final String LASTFM_USER = "settings.scrobbling.lastfm.user";
    public static final String LASTFM_PASS = "settings.scrobbling.lastfm.pass";
    public static final String LASTFM_AUTH = "settings.scrobbling.lastfm.auth";
    public static final String LISTENBRAINZ_ENABLE = "settings.scrobbling.listenbrainz.enable";
    public static final String LISTENBRAINZ_TOKEN = "settings.scrobbling.listenbrainz.token";

    public static String homeShortcut(String id) {
        return "home.shortcut." + id;
    }

    /**
     * Label res for home shortcuts only. Other rows pass R.string from app at call sites —
     * settings module cannot depend on app R without a circular dependency.
     */
    public static int labelResId(String rowKey) {
        if (rowKey == null) return 0;
        if (rowKey.startsWith("home.shortcut.")) {
            String id = rowKey.substring("home.shortcut.".length());
            HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
            return e != null ? e.labelResId : 0;
        }
        return 0;
    }

    private RowKeys() {}
}
