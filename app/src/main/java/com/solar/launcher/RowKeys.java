package com.solar.launcher;

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
    public static final String SOULSEEK_REGENERATE = "soulseek.regenerate";
    public static final String SOULSEEK_HIDE_FLAC = "soulseek.hide_flac";
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
    public static final String UPDATE_LATEST = "update.latest";
    public static final String DT_YEAR = "datetime.year";
    public static final String DT_MONTH = "datetime.month";
    public static final String DT_DAY = "datetime.day";
    public static final String DT_HOUR = "datetime.hour";
    public static final String DT_MINUTE = "datetime.minute";
    public static final String LANG_SYSTEM = "language.system";
    public static final String LANG_EN = "language.en";
    public static final String LANG_KO = "language.ko";

    public static String homeShortcut(String id) {
        return "home.shortcut." + id;
    }

    public static int labelResId(String rowKey) {
        if (rowKey == null) return 0;
        if (rowKey.startsWith("home.shortcut.")) {
            String id = rowKey.substring("home.shortcut.".length());
            HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
            return e != null ? e.labelResId : 0;
        }
        if (SHUFFLE.equals(rowKey)) return R.string.settings_shuffle_mode;
        if (REPEAT.equals(rowKey)) return R.string.settings_repeat_mode;
        if (EQ.equals(rowKey)) return R.string.settings_equalizer;
        if (BUTTON_SOUND.equals(rowKey)) return R.string.settings_button_sound;
        if (BUTTON_VIBRATE.equals(rowKey)) return R.string.settings_button_vibrate;
        if (SCREEN_OFF_CTRL.equals(rowKey)) return R.string.settings_screen_off_control;
        if (APP_THEME.equals(rowKey)) return R.string.settings_app_theme;
        if (GET_THEMES.equals(rowKey) || THEMES.equals(rowKey)) return R.string.settings_themes;
        if (STATUS_BAR_LEFT.equals(rowKey)) return R.string.settings_status_bar_text;
        if (APPEARANCE.equals(rowKey)) return R.string.settings_appearance;
        if (STATUS_BAR_MATCH_FONT.equals(rowKey)) return R.string.settings_status_bar_match_font;
        if (SCREEN_TIMEOUT.equals(rowKey)) return R.string.settings_screen_timeout;
        if (FULL_WIDTH.equals(rowKey)) return R.string.settings_full_width_menus;
        if (POWER_OFF.equals(rowKey)) return R.string.settings_power_off;
        if (WEB_SERVER.equals(rowKey)) return R.string.settings_web_server;
        if (WIFI_SETUP.equals(rowKey)) return R.string.settings_wifi_setup;
        if (SOULSEEK.equals(rowKey)) return R.string.settings_soulseek;
        if (AUTO_FETCH.equals(rowKey)) return R.string.settings_auto_fetch;
        if (SYSTEM_UPDATE.equals(rowKey)) return R.string.settings_system_update;
        if (BLUETOOTH_SETUP.equals(rowKey)) return R.string.settings_bluetooth_setup;
        if (BRIGHTNESS.equals(rowKey)) return R.string.settings_display_brightness;
        if (STORAGE.equals(rowKey)) return R.string.settings_storage_info;
        if (BACKGROUND.equals(rowKey)) return R.string.settings_background;
        if (NOW_PLAYING.equals(rowKey)) return R.string.settings_now_playing;
        if (NOW_PLAYING_ALBUM_BLUR.equals(rowKey)) return R.string.settings_player_album_blur;
        if (CLEAR_CACHE.equals(rowKey)) return R.string.settings_clear_cache;
        if (DATETIME.equals(rowKey)) return R.string.settings_datetime;
        if (LANGUAGE.equals(rowKey)) return R.string.settings_language;
        if (HOME_SCREEN.equals(rowKey)) return R.string.home_screen_editor;
        if (SOULSEEK_SEARCH.equals(rowKey)) return R.string.soulseek_search_row;
        if (SOULSEEK_ACCOUNT.equals(rowKey)) return R.string.soulseek_account_row;
        if (SOULSEEK_CONNECTION.equals(rowKey)) return R.string.soulseek_menu_connection;
        if (SOULSEEK_ABOUT.equals(rowKey)) return R.string.soulseek_menu_about;
        if (SOULSEEK_REGENERATE.equals(rowKey)) return R.string.soulseek_regenerate_account;
        if (SOULSEEK_HIDE_FLAC.equals(rowKey)) return R.string.soulseek_hide_flac;
        if (WIDGET_CLOCK.equals(rowKey)) return R.string.widget_clock;
        if (WIDGET_BATTERY.equals(rowKey)) return R.string.widget_battery;
        if (WIDGET_ALBUM.equals(rowKey)) return R.string.widget_album;
        if (HOME_ARRANGE.equals(rowKey)) return R.string.home_screen_arrange;
        if (HOME_MORE.equals(rowKey)) return R.string.home_screen_more;
        if (MORE_MENU.equals(rowKey)) return R.string.home_menu_more;
        if (BG_SOURCE.equals(rowKey)) return R.string.settings_background_source;
        if (BG_SELECT.equals(rowKey)) return R.string.settings_select_background;
        if (BG_CLEAR.equals(rowKey)) return R.string.settings_clear_background;
        if (BT_POWER.equals(rowKey)) return R.string.bluetooth_power;
        if (WIFI_POWER.equals(rowKey)) return R.string.wifi_power;
        if (UPDATE_CURRENT.equals(rowKey)) return R.string.update_current_version;
        if (UPDATE_LATEST.equals(rowKey)) return R.string.update_latest_version;
        if (DT_YEAR.equals(rowKey)) return R.string.datetime_year;
        if (DT_MONTH.equals(rowKey)) return R.string.datetime_month;
        if (DT_DAY.equals(rowKey)) return R.string.datetime_day;
        if (DT_HOUR.equals(rowKey)) return R.string.datetime_hour;
        if (DT_MINUTE.equals(rowKey)) return R.string.datetime_minute;
        if (LANG_SYSTEM.equals(rowKey)) return R.string.language_system;
        if (LANG_EN.equals(rowKey)) return R.string.language_english;
        if (LANG_KO.equals(rowKey)) return R.string.language_korean;
        return 0;
    }

    private RowKeys() {}
}
