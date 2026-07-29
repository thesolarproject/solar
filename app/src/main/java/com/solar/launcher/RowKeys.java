package com.solar.launcher;

import com.solar.launcher.media.MediaSuiteHost;

/** Stable row identifiers for settings UI (locale-independent). */
public final class RowKeys {
    public static final String SHUFFLE = "settings.shuffle";
    public static final String REPEAT = "settings.repeat";
    public static final String EQ = "settings.eq";
    public static final String PLAYBACK = "settings.playback";
    public static final String BUTTON_SOUND = "settings.button_sound";
    public static final String BUTTON_VIBRATE = "settings.button_vibrate";
    /** When on, cap headphone volume at 80% hardware max with Solar warnings. */
    public static final String HEARING_SAFETY = "settings.hearing_safety";
    public static final String SCREEN_OFF_CTRL = "settings.screen_off_control";
    /** Xposed skin — paint stock apps (Settings, etc.) with active Solar theme. */
    public static final String PAINT_SYSTEM_APPS = "settings.paint_system_apps";
    public static final String APP_THEME = "settings.app_theme";
    public static final String GET_THEMES = "settings.get_themes";
    public static final String THEMES = "settings.themes";
    public static final String HOME_MORE = "settings.home_more";
    public static final String HOME_MANAGE_MORE = "settings.home_manage_more";
    public static final String APPEARANCE = "settings.appearance";
    public static final String APPEARANCE_STATUS = "settings.appearance.status";
    public static final String APPEARANCE_LAYOUT = "settings.appearance.layout";
    public static final String STATUS_BAR_LEFT = "settings.status_bar_left";
    public static final String STATUS_BAR_MATCH_FONT = "settings.status_bar_match_font";
    public static final String NOW_PLAYING_MATCH_FONT = "settings.now_playing_match_font";
    public static final String NOW_PLAYING_BACKDROP = "settings.now_playing_backdrop";
    /** 2026-07-11 — Home right-pane title/artist under Now Playing preview art (opt-in). */
    public static final String SHOW_NOW_PLAYING_INFO = "settings.show_now_playing_info";
    public static final String NOW_PLAYING_LCD_ART = "settings.now_playing_lcd_art";
    public static final String NOW_PLAYING_3D_ALBUM_ART = "settings.now_playing_3d_album_art";
    public static final String SCREEN_TIMEOUT = "settings.screen_timeout";
    public static final String FULL_WIDTH = "settings.full_width";
    /** 2026-07-11 — Keep 9dp left gutter on home/settings menu rows (off = flush to screen edge). */
    public static final String MENU_ITEM_PADDING = "settings.menu_item_padding";
    /** 2026-07-06 — Wrap wheel focus at list ends (Solar + global overlay). */
    public static final String INFINITE_SCROLL = "settings.infinite_scroll";
    public static final String MENU_TRANSITIONS = "settings.menu_transitions";
    public static final String POWER_OFF = "settings.power_off";
    public static final String POWER_RESTART = "settings.power_restart";
    public static final String POWER_SAVING_SHUTDOWN = "settings.power_saving_shutdown";
    public static final String WIFI_SLEEP_POWER_OFF = "settings.wifi_sleep_power_off";
    public static final String DEVICE = "settings.device";
    public static final String LIBRARY = "settings.library";
    public static final String MEDIA = "settings.media";
    public static final String DOWNLOAD_AUTO_RESUME_WIFI =
            "settings.download_auto_resume_wifi";
    public static final String YOUTUBE_REGION = "settings.youtube.region";
    public static final String YOUTUBE_DISCOVER_DURATION =
            "settings.youtube.discover_duration";
    public static final String YOUTUBE_CACHE_SIZE = "settings.youtube.cache_size";
    public static final String YOUTUBE_CLEAR_CACHE = "settings.youtube.clear_cache";
    public static final String YOUTUBE_CLEAR_HISTORY = "settings.youtube.clear_history";
    public static final String POWER = "settings.power";
    public static final String USB = "settings.usb";
    public static final String USB_AUTO_CONNECT = "settings.usb_auto_connect";
    public static final String USB_SUPPRESS_PROMPT = "settings.usb_suppress_prompt";
    public static final String USB_TURN_ON = "settings.usb_turn_on";
    public static final String SWITCH_ROCKBOX = "settings.switch_rockbox";
    public static final String HOME_LAUNCHER = "settings.home_launcher";
    public static final String HOME_LAUNCHER_STATUS = "settings.home_launcher.status";
    public static final String HOME_LAUNCHER_TO_SOLAR = "settings.home_launcher.to_solar";
    public static final String HOME_LAUNCHER_TO_ROCKBOX = "settings.home_launcher.to_rockbox";
    public static final String HOME_LAUNCHER_TO_JJ = "settings.home_launcher.to_jj";
    /** 2026-07-08 — Settings row for Stock (Innioasis) HOME. */
    public static final String HOME_LAUNCHER_TO_STOCK = "settings.home_launcher.to_stock";
    public static final String RESET = "settings.reset";
    public static final String RESET_ROCKBOX = "settings.reset.rockbox";
    public static final String RESET_SOLAR = "settings.reset.solar";
    public static final String RESET_THEMES = "settings.reset.themes";
    public static final String RESET_MICROSD = "settings.reset.microsd";
    public static final String RESET_CACHES = "settings.reset.caches";
    public static final String RESET_FLOW = "settings.reset.flow";
    public static final String RESET_STEMS = "settings.reset.stems";
    public static final String RESET_EVERYTHING = "settings.reset.everything";
    public static final String RESET_CONTINUE = "settings.reset.continue";
    public static final String WEB_SERVER = "settings.web_server";
    public static final String CONNECTIONS = "settings.connections";
    public static final String WIFI_SETUP = "settings.wifi_setup";
    public static final String WIFI_AUTO_CONNECT = "settings.wifi_auto_connect";
    public static final String REACH = "settings.reach";
    public static final String SOULSEEK = "settings.soulseek";
    public static final String DEEZER = "settings.deezer";
    public static final String AUTO_FETCH = "settings.auto_fetch";
    /** 2026-07-18 — Lalal.ai license key for Stem Player. */
    public static final String LALAL = "settings.lalal";
    /**
     * 2026-07-19 — Enable Stem features (demo key path; default off).
     * Was: no toggle — user key only. Reversal: remove row + PREF_STEM_FEATURES_ENABLED.
     */
    public static final String STEM_FEATURES = "settings.stem_features";
    /** 2026-07-19 — Experimental Melody premix (default off = live multi-player). */
    public static final String STEM_PREMIX = "settings.stem_premix";
    public static final String LIBRARY_BROWSE = "settings.library_browse";
    public static final String LIB_SPLIT_CREDITS = "library.split_credits";
    public static final String LIB_NORM_ALBUM = "library.norm_album";
    public static final String LIB_GUEST_MODE = "library.guest_mode";
    public static final String LIB_ARTIST_FILTER = "library.artist_filter";
    public static final String LIB_ARTIST_SORT = "library.artist_sort";
    public static final String LIB_SONG_SORT = "library.song_sort";
    public static final String LIB_ALBUM_SONG_SORT = "library.album_song_sort";
    public static final String LIB_ALBUM_RACK_SORT = "library.album_rack_sort";
    public static final String LIB_ALBUM_SUB = "library.album_sub";
    public static final String LIB_GUEST_SUB = "library.guest_sub";
    /** Library browse — append Reach online hits in on-device search (default off). */
    public static final String LIB_SEARCH_REACH = "library.search_reach";
    public static final String LIST_WRAPAROUND = "library.list_wraparound";
    public static final String NAVIDROME_URL = "settings.navidrome.url";
    public static final String NAVIDROME_USER = "settings.navidrome.user";
    public static final String NAVIDROME_PASS = "settings.navidrome.pass";
    public static final String NAVIDROME_TEST = "settings.navidrome.test";
    public static final String PLEX_URL = "settings.plex.url";
    public static final String PLEX_TOKEN = "settings.plex.token";
    public static final String PLEX_TEST = "settings.plex.test";
    // 2026-07-14 — Jellyfin settings rows.
    public static final String JELLYFIN_URL = "settings.jellyfin.url";
    public static final String JELLYFIN_USER = "settings.jellyfin.user";
    public static final String JELLYFIN_PASS = "settings.jellyfin.pass";
    public static final String SCROBBLING_MENU = "settings.scrobbling";
    public static final String LASTFM_ENABLE = "settings.scrobbling.lastfm.enable";
    public static final String LISTENBRAINZ_ENABLE = "settings.scrobbling.listenbrainz.enable";
    public static final String LISTENBRAINZ_TOKEN = "settings.scrobbling.listenbrainz.token";
    public static final String JELLYFIN_TEST = "settings.jellyfin.test";
    public static final String ABOUT = "settings.about";
    public static final String DIAG_AUTO_REPORT = "settings.diag_auto_report";
    /** Root Settings — GitHub Issues URL screen. */
    public static final String REPORT_ISSUE = "settings.report_issue";
    /** Report Issue sub-row → optional message keyboard + one-shot USER_REPORT (2026-07-19). */
    public static final String REPORT_FROM_DEVICE = "settings.report_from_device";
    /** Dev-support experiment — in-app report thread (2026-07-05). */
    public static final String REPORT_PROBLEM = "settings.report_problem";
    public static final String CONTACT_DEVELOPER = "settings.contact_developer";
    public static final String DEBUG_DEV_SUPPORT_EXPERIMENT = "settings.debug.dev_support_experiment";
    /** Root Settings — Donations (Ko-fi URL) screen. */
    public static final String SUPPORT_DEVELOPER = "settings.support_developer";
    /** Donations sub-screen — online donor roll from donators.xml. */
    public static final String OUR_DONORS = "settings.our_donors";
    public static final String DEBUG = "settings.debug";
    public static final String DEBUG_JJ_THEMES = "settings.debug.jj_themes";
    public static final String DEBUG_WIRELESS_ADB = "settings.debug.wireless_adb";
    public static final String DEBUG_SHOW_ERROR_TOASTS = "settings.debug.show_error_toasts";
    public static final String DEBUG_BLUETOOTH_EXPERIMENT = "settings.debug.bluetooth_experiment";
    public static final String DEBUG_RADIO_EXPERIMENT = "settings.debug.radio_experiment";
    /** 2026-07-14 — YouTube hub kill switch (default on); Debug can hide Videos → YouTube. */
    public static final String DEBUG_YOUTUBE_EXPERIMENT = "settings.debug.youtube_experiment";
    /** 2026-07-11 — Rockbox-Y1 install + HOME switch; off by default (Y2 uses Y1 APK + compat module). */
    public static final String DEBUG_ROCKBOX_EXPERIMENT = "settings.debug.rockbox_experiment";
    public static final String DEBUG_PLEX_EXPERIMENT = "settings.debug.plex_experiment";
    /** 2026-07-14 — Jellyfin music client; off by default until enabled in Debug. */
    public static final String DEBUG_JELLYFIN_EXPERIMENT = "settings.debug.jellyfin_experiment";
    /** 2026-07-14 — A5-only: Y1/Y2 landscape chrome scaled to 240p (320×240); off by default. */
    public static final String DEBUG_A5_LANDSCAPE_EXPERIMENT = "settings.debug.a5_landscape_experiment";
    /** 2026-07-14 — Y1/Y2-only: tall portrait chrome + remapped wheel/side keys; off by default. */
    public static final String DEBUG_Y1_PORTRAIT_EXPERIMENT = "settings.debug.y1_portrait_experiment";
    /** Y2-only — USB mass storage (UMS) lab toggle; off by default until MT6582 path works (2026-07-05). */
    public static final String DEBUG_USB_MASS_STORAGE_EXPERIMENT = "settings.debug.usb_mass_storage_experiment";
    public static final String DEBUG_FLOW_ENABLED = "settings.debug.flow_enabled";
    public static final String DEBUG_FLOW_OK_LIBRARY = "settings.debug.flow_ok_library";
    public static final String DEBUG_FLOW_THEME = "settings.debug.flow_theme";
    public static final String DEBUG_FLOW_NO_REFLECTIONS = "settings.debug.flow_no_reflections";
    /** Debug → Xposed modules submenu entry (matches {@link SettingsScreens#XPOSED_MODULES}). */
    public static final String DEBUG_XPOSED_MODULES = "settings.debug.xposed_modules";
    public static final String DEBUG_XPOSED_APPLY = "settings.debug.xposed_apply";
    public static final String XPOSED_MODULE_ROW_PREFIX = "settings.debug.xposed.pkg.";
    /** Inline config toggle on module detail — suffix is option key. */
    public static final String XPOSED_MODULE_CONFIG_ROW_PREFIX = "settings.debug.xposed.cfg.";
    /** Open external MODULE_SETTINGS activity for one package. */
    public static final String XPOSED_MODULE_OPEN_SETTINGS_PREFIX = "settings.debug.xposed.open.";
    /** Root Settings — master Flow on/off (experimental toggles stay under Debug → Flow). */
    public static final String FLOW = "settings.flow_enabled";
    public static final String FLOW_SETTINGS = "settings.flow";
    public static final String FLOW_MULTI_TRACK_ALBUMS = "settings.flow.multi_track_albums";
    public static final String ARTWORK_PERSPECTIVE = "settings.artwork_perspective";
    public static final String SYSTEM_UPDATE = "settings.system_update";
    public static final String BLUETOOTH_SETUP = "settings.bluetooth_setup";
    public static final String BLUETOOTH_PAIRING_PIN = "settings.bluetooth_pairing_pin";
    public static final String BLUETOOTH_AUTO_RECONNECT = "settings.bluetooth_auto_reconnect";
    public static final String BRIGHTNESS = "settings.brightness";
    public static final String STORAGE = "settings.storage";
    /** Legacy — prefer Y2_PRIMARY_STORAGE; scans always include every mounted volume. */
    public static final String Y2_INTERNAL_MEDIA = "settings.y2_internal_media";
    /** Y2 only — long OK sleeps screen instead of opening quick menu (legacy behaviour). */
    public static final String Y2_HOLD_OK_TO_SLEEP = "settings.y2_hold_ok_to_sleep";
    public static final String KEYBOARD_LAYOUT = "settings.keyboard_layout";
    /** 2026-07-11 — A5: face vs side buttons navigate menus. */
    public static final String A5_MENU_NAV = "settings.a5_menu_nav";
    /** 2026-07-11 — A5: portrait 240×320 vs landscape 240p-scaled. */
    public static final String A5_ORIENTATION = "settings.a5_orientation";
    /** Pick MicroSD vs Internal as primary save target for new media (all families). */
    public static final String Y2_PRIMARY_STORAGE = "settings.y2_primary_storage";
    public static final String Y2_PRIMARY_MICROSD = "settings.y2_primary.microsd";
    public static final String Y2_PRIMARY_INTERNAL = "settings.y2_primary.internal";
    /** Debug experiment — format/repair MicroSD (root on Y1, system UI elsewhere). */
    public static final String DEBUG_MICROSD_FORMAT = "settings.debug.microsd_format";
    public static final String BACKGROUND = "settings.background";
    public static final String NOW_PLAYING = "settings.now_playing";
    public static final String NOW_PLAYING_ALBUM_BLUR = "settings.now_playing.album_blur";
    public static final String CLEAR_CACHE = "settings.clear_cache";
    public static final String DATETIME = "settings.datetime";
    public static final String DT_AUTO_INTERNET = "datetime.auto_internet";
    public static final String DT_TIMEZONE = "datetime.timezone";
    /** Manual daylight-saving observation (works fully offline). */
    public static final String DT_OBSERVE_DST = "datetime.observe_dst";
    public static final String DT_SYNC_NOW = "datetime.sync_now";
    public static final String LANGUAGE = "settings.language";
    public static final String RADIO = "settings.radio";
    public static final String RADIO_FM = "settings.radio.fm";
    public static final String VIDEO = "settings.video";
    public static final String HOME_SCREEN = "settings.home_screen";
    public static final String SOULSEEK_SEARCH = "soulseek.search";
    public static final String SOULSEEK_ACCOUNT = "soulseek.account";
    public static final String SOULSEEK_CONNECTION = "soulseek.connection";
    public static final String SOULSEEK_ABOUT = "soulseek.about";
    public static final String SOULSEEK_REGENERATE = "soulseek.regenerate";
    public static final String SOULSEEK_HIDE_HIGH_BITRATE = "soulseek.hide_high_bitrate";
    public static final String SOULSEEK_SHARING = "soulseek.sharing";
    public static final String SOULSEEK_REACH_ENABLED = "soulseek.reach_enabled";
    public static final String SOULSEEK_ENABLED = "soulseek.enabled";
    // 2026-07-19 — Dead SOULSEEK_INCLUDE_GET_MUSIC removed (no UI writer). Reversal: restore const + title map.
    public static final String SOULSEEK_MESSAGING = "soulseek.messaging";
    public static final String SOULSEEK_FIND_USER = "soulseek.find_user";
    public static final String SOULSEEK_FIND_REACH = "soulseek.find_reach";
    public static final String SOULSEEK_MESSAGES = "soulseek.messages";
    public static final String SOULSEEK_CHAT_ROOMS = "soulseek.chat_rooms";
    public static final String SOULSEEK_INTERESTS = "soulseek.interests";
    public static final String DEEZER_SEARCH = "deezer.search";
    public static final String DEEZER_ACCOUNT = "deezer.account";
    public static final String DEEZER_CONNECTION = "deezer.connection";
    public static final String DEEZER_ENABLED = "deezer.enabled";
    public static final String DEEZER_INCLUDE_GET_MUSIC = "deezer.include_get_music";
    public static final String DEEZER_CLEAR = "deezer.clear";
    public static final String DEEZER_SETUP_PC = "deezer.setup_pc";
    public static final String DEEZER_QUALITY = "deezer.quality";
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

    public static String homeShortcut(String id) {
        return "home.shortcut." + id;
    }

    /** Row key for one Xposed module toggle in Debug → Xposed modules. */
    public static String xposedModuleRowKey(String packageName) {
        return XPOSED_MODULE_ROW_PREFIX + (packageName != null ? packageName : "");
    }

    /** True for staged module rows — not persisted until Apply + reboot. */
    public static boolean isXposedModuleRow(String rowKey) {
        return rowKey != null && rowKey.startsWith(XPOSED_MODULE_ROW_PREFIX)
                && !rowKey.startsWith(XPOSED_MODULE_CONFIG_ROW_PREFIX)
                && !rowKey.startsWith(XPOSED_MODULE_OPEN_SETTINGS_PREFIX);
    }

    /** Row to launch a hook module's own settings activity. */
    public static String xposedModuleOpenSettingsKey(String packageName) {
        return XPOSED_MODULE_OPEN_SETTINGS_PREFIX + (packageName != null ? packageName : "");
    }

    /** True for inline module config toggles on detail screen. */
    public static boolean isXposedModuleConfigRow(String rowKey) {
        return rowKey != null && rowKey.startsWith(XPOSED_MODULE_CONFIG_ROW_PREFIX);
    }

    public static int labelResId(String rowKey) {
        if (rowKey == null) return 0;
        if (isXposedModuleRow(rowKey)) {
            String pkg = rowKey.substring(XPOSED_MODULE_ROW_PREFIX.length());
            XposedModuleRegistry.Entry e = XposedModuleRegistry.findByPackage(pkg);
            return e != null ? e.labelResId : 0;
        }
        if (rowKey.startsWith("home.shortcut.")) {
            String id = rowKey.substring("home.shortcut.".length());
            HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
            return e != null ? e.labelResId : 0;
        }
        if (SHUFFLE.equals(rowKey)) return R.string.settings_shuffle_mode;
        if (REPEAT.equals(rowKey)) return R.string.settings_repeat_mode;
        if (EQ.equals(rowKey)) return R.string.settings_equalizer;
        if (PLAYBACK.equals(rowKey)) return R.string.settings_playback;
        if (BUTTON_SOUND.equals(rowKey)) return R.string.settings_button_sound;
        if (BUTTON_VIBRATE.equals(rowKey)) return R.string.settings_button_vibrate;
        if (HEARING_SAFETY.equals(rowKey)) return R.string.settings_hearing_safety;
        if (SCREEN_OFF_CTRL.equals(rowKey)) return R.string.settings_screen_off_control;
        if (PAINT_SYSTEM_APPS.equals(rowKey)) return R.string.settings_paint_system_apps;
        if (APP_THEME.equals(rowKey)) return R.string.settings_app_theme;
        if (GET_THEMES.equals(rowKey) || THEMES.equals(rowKey)) return R.string.settings_themes;
        if (STATUS_BAR_LEFT.equals(rowKey)) return R.string.settings_status_bar_text;
        if (APPEARANCE.equals(rowKey)) return R.string.settings_appearance;
        if (APPEARANCE_STATUS.equals(rowKey)) return R.string.settings_appearance_status;
        if (APPEARANCE_LAYOUT.equals(rowKey)) return R.string.settings_appearance_layout;
        if (STATUS_BAR_MATCH_FONT.equals(rowKey)) return R.string.settings_status_bar_match_font;
        if (NOW_PLAYING_MATCH_FONT.equals(rowKey)) return R.string.settings_now_playing_match_font;
        if (NOW_PLAYING_BACKDROP.equals(rowKey)) return R.string.settings_now_playing_backdrop;
        if (SHOW_NOW_PLAYING_INFO.equals(rowKey)) return R.string.settings_show_now_playing_info;
        if (NOW_PLAYING_LCD_ART.equals(rowKey)) return R.string.settings_now_playing_lcd_art;
        if (ARTWORK_PERSPECTIVE.equals(rowKey)) return R.string.settings_artwork_perspective;
        if (NOW_PLAYING_3D_ALBUM_ART.equals(rowKey)) return R.string.settings_now_playing_3d_album_art;
        if (SCREEN_TIMEOUT.equals(rowKey)) return R.string.settings_screen_timeout;
        if (FULL_WIDTH.equals(rowKey)) return R.string.settings_full_width_menus;
        if (MENU_ITEM_PADDING.equals(rowKey)) return R.string.settings_menu_item_padding;
        if (INFINITE_SCROLL.equals(rowKey)) return R.string.settings_infinite_scroll;
        if (MENU_TRANSITIONS.equals(rowKey)) return R.string.settings_menu_transitions;
        if (POWER_OFF.equals(rowKey)) return R.string.settings_power_off;
        if (POWER_RESTART.equals(rowKey)) return R.string.settings_power_restart;
        if (POWER_SAVING_SHUTDOWN.equals(rowKey)) return R.string.settings_power_saving_shutdown;
        if (WIFI_SLEEP_POWER_OFF.equals(rowKey)) return R.string.settings_wifi_sleep_power_off;
        if (DEVICE.equals(rowKey)) return R.string.settings_device;
        if (LIBRARY.equals(rowKey)) return R.string.settings_library;
        if (MEDIA.equals(rowKey)) return R.string.settings_media;
        if (DOWNLOAD_AUTO_RESUME_WIFI.equals(rowKey)) {
            return R.string.settings_download_auto_resume_wifi;
        }
        if (POWER.equals(rowKey)) return R.string.settings_power;
        if (USB.equals(rowKey)) return R.string.settings_usb;
        if (USB_AUTO_CONNECT.equals(rowKey)) return R.string.settings_usb_auto_connect;
        if (USB_SUPPRESS_PROMPT.equals(rowKey)) return R.string.settings_usb_suppress_prompt;
        if (USB_TURN_ON.equals(rowKey)) return R.string.settings_usb_turn_on;
        if (SWITCH_ROCKBOX.equals(rowKey)) return R.string.settings_switch_rockbox;
        if (HOME_LAUNCHER.equals(rowKey)) return R.string.settings_home_launcher;
        if (HOME_LAUNCHER_TO_SOLAR.equals(rowKey)) return R.string.settings_home_launcher_switch_solar;
        if (HOME_LAUNCHER_TO_ROCKBOX.equals(rowKey)) return R.string.settings_home_launcher_switch_rockbox;
        if (HOME_LAUNCHER_TO_JJ.equals(rowKey)) return R.string.settings_home_launcher_switch_jj;
        if (HOME_LAUNCHER_TO_STOCK.equals(rowKey)) return R.string.settings_home_launcher_switch_stock;
        if (RESET.equals(rowKey)) return R.string.settings_reset;
        if (RESET_ROCKBOX.equals(rowKey)) return R.string.settings_reset_rockbox;
        if (RESET_SOLAR.equals(rowKey)) return R.string.settings_reset_solar;
        if (RESET_THEMES.equals(rowKey)) return R.string.settings_reset_themes;
        if (RESET_MICROSD.equals(rowKey)) return R.string.settings_reset_microsd;
        if (RESET_CACHES.equals(rowKey)) return R.string.settings_reset_caches;
        if (RESET_FLOW.equals(rowKey)) return R.string.settings_reset_flow;
        if (RESET_STEMS.equals(rowKey)) return R.string.settings_reset_stems;
        if (RESET_EVERYTHING.equals(rowKey)) return R.string.settings_reset_everything;
        if (RESET_CONTINUE.equals(rowKey)) return R.string.settings_reset_continue;
        if (WEB_SERVER.equals(rowKey)) return R.string.settings_web_server;
        if (CONNECTIONS.equals(rowKey)) return R.string.settings_connections;
        if (WIFI_SETUP.equals(rowKey)) return R.string.settings_wifi_setup;
        if (WIFI_AUTO_CONNECT.equals(rowKey)) return R.string.settings_wifi_auto_connect;
        if (REACH.equals(rowKey)) return R.string.settings_reach;
        if (SOULSEEK.equals(rowKey)) return R.string.settings_soulseek;
        if (DEEZER.equals(rowKey)) return R.string.settings_deezer;
        if (LALAL.equals(rowKey)) return R.string.settings_lalal;
        if (STEM_FEATURES.equals(rowKey)) return R.string.settings_stem_features;
        if (STEM_PREMIX.equals(rowKey)) return R.string.settings_stem_premix;
        if (AUTO_FETCH.equals(rowKey)) return R.string.settings_auto_fetch;
        if (LIBRARY_BROWSE.equals(rowKey)) return R.string.settings_library_browse;
        if (LIB_SPLIT_CREDITS.equals(rowKey)) return R.string.lib_split_credits;
        if (LIB_NORM_ALBUM.equals(rowKey)) return R.string.lib_norm_album_case;
        if (LIB_GUEST_MODE.equals(rowKey)) return R.string.lib_guest_browse;
        if (LIB_ARTIST_FILTER.equals(rowKey)) return R.string.lib_artist_filter;
        if (LIB_ARTIST_SORT.equals(rowKey)) return R.string.lib_artist_sort;
        if (LIB_SONG_SORT.equals(rowKey)) return R.string.lib_song_sort;
        if (LIB_ALBUM_SONG_SORT.equals(rowKey)) return R.string.lib_album_song_sort;
        if (LIB_ALBUM_SUB.equals(rowKey)) return R.string.lib_album_owner_sub;
        if (LIB_GUEST_SUB.equals(rowKey)) return R.string.lib_guest_song_sub;
        if (LIB_SEARCH_REACH.equals(rowKey)) return R.string.lib_search_include_reach;
        if (LIST_WRAPAROUND.equals(rowKey)) return R.string.settings_list_wraparound;
        if (NAVIDROME_URL.equals(rowKey)) return R.string.navidrome_settings_url;
        if (NAVIDROME_USER.equals(rowKey)) return R.string.navidrome_settings_user;
        if (NAVIDROME_PASS.equals(rowKey)) return R.string.navidrome_settings_pass;
        if (NAVIDROME_TEST.equals(rowKey)) return R.string.navidrome_settings_test;
        if (PLEX_URL.equals(rowKey)) return R.string.plex_settings_url;
        if (PLEX_TOKEN.equals(rowKey)) return R.string.plex_settings_token;
        if (PLEX_TEST.equals(rowKey)) return R.string.plex_settings_test;
        if (JELLYFIN_URL.equals(rowKey)) return R.string.jellyfin_settings_url;
        if (JELLYFIN_USER.equals(rowKey)) return R.string.jellyfin_settings_user;
        if (JELLYFIN_PASS.equals(rowKey)) return R.string.jellyfin_settings_pass;
        if (JELLYFIN_TEST.equals(rowKey)) return R.string.jellyfin_settings_test;
        if (ABOUT.equals(rowKey)) return R.string.settings_about;
        if (DIAG_AUTO_REPORT.equals(rowKey)) return R.string.settings_diag_auto_report;
        if (REPORT_ISSUE.equals(rowKey)) return R.string.settings_report_issue;
        if (REPORT_FROM_DEVICE.equals(rowKey)) return R.string.report_issue_send_logs;
        if (SUPPORT_DEVELOPER.equals(rowKey)) return R.string.settings_support_developer;
        if (OUR_DONORS.equals(rowKey)) return R.string.settings_our_donors;
        if (DEBUG.equals(rowKey)) return R.string.settings_sub_debug;
        if (DEBUG_JJ_THEMES.equals(rowKey)) return R.string.settings_debug_jj_themes;
        if (DEBUG_WIRELESS_ADB.equals(rowKey)) return R.string.settings_debug_wireless_adb;
        if (DEBUG_SHOW_ERROR_TOASTS.equals(rowKey)) return R.string.settings_debug_show_error_toasts;
        if (DEBUG_BLUETOOTH_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_bluetooth_experiment;
        if (DEBUG_RADIO_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_radio_experiment;
        if (DEBUG_YOUTUBE_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_youtube_experiment;
        if (DEBUG_ROCKBOX_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_rockbox_experiment;
        if (DEBUG_PLEX_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_plex_experiment;
        if (DEBUG_JELLYFIN_EXPERIMENT.equals(rowKey)) return R.string.settings_debug_jellyfin_experiment;
        if (DEBUG_A5_LANDSCAPE_EXPERIMENT.equals(rowKey)) {
            return R.string.settings_debug_a5_landscape_experiment;
        }
        if (DEBUG_Y1_PORTRAIT_EXPERIMENT.equals(rowKey)) {
            return R.string.settings_debug_y1_portrait_experiment;
        }
        if (DEBUG_USB_MASS_STORAGE_EXPERIMENT.equals(rowKey)) {
            return R.string.settings_debug_usb_mass_storage_experiment;
        }
        if (DEBUG_FLOW_ENABLED.equals(rowKey)) return R.string.settings_debug_flow_enabled;
        if (DEBUG_FLOW_OK_LIBRARY.equals(rowKey)) return R.string.settings_debug_flow_ok_library;
        if (DEBUG_FLOW_THEME.equals(rowKey)) return R.string.settings_debug_flow_theme;
        if (DEBUG_FLOW_NO_REFLECTIONS.equals(rowKey)) return R.string.settings_debug_flow_no_reflections;
        if (DEBUG_XPOSED_MODULES.equals(rowKey)) return R.string.settings_debug_xposed_modules;
        if (DEBUG_XPOSED_APPLY.equals(rowKey)) return R.string.settings_debug_xposed_apply;
        if (FLOW.equals(rowKey)) return R.string.settings_flow;
        if (FLOW_SETTINGS.equals(rowKey)) return R.string.settings_sub_flow;
        if (FLOW_MULTI_TRACK_ALBUMS.equals(rowKey)) return R.string.settings_flow_multi_track_albums;
        if (SYSTEM_UPDATE.equals(rowKey)) return R.string.settings_app_version;
        if (BLUETOOTH_SETUP.equals(rowKey)) return R.string.settings_bluetooth_setup;
        if (BLUETOOTH_PAIRING_PIN.equals(rowKey)) return R.string.settings_bluetooth_pairing_pin;
        if (BLUETOOTH_AUTO_RECONNECT.equals(rowKey)) return R.string.settings_bluetooth_auto_reconnect;
        if (BRIGHTNESS.equals(rowKey)) return R.string.settings_display_brightness;
        if (STORAGE.equals(rowKey)) return R.string.settings_storage_info;
        if (Y2_INTERNAL_MEDIA.equals(rowKey)) return R.string.settings_y2_internal_media;
        if (Y2_HOLD_OK_TO_SLEEP.equals(rowKey)) return R.string.settings_y2_hold_ok_to_sleep;
        if (KEYBOARD_LAYOUT.equals(rowKey)) return R.string.settings_keyboard_layout;
        if (A5_MENU_NAV.equals(rowKey)) return R.string.settings_a5_menu_nav;
        if (A5_ORIENTATION.equals(rowKey)) return R.string.settings_a5_orientation;
        if (Y2_PRIMARY_STORAGE.equals(rowKey)) return R.string.settings_y2_primary_storage;
        if (Y2_PRIMARY_MICROSD.equals(rowKey)) return R.string.settings_y2_primary_microsd;
        if (Y2_PRIMARY_INTERNAL.equals(rowKey)) return R.string.settings_y2_primary_internal;
        if (DEBUG_MICROSD_FORMAT.equals(rowKey)) return R.string.settings_debug_microsd_format;
        if (BACKGROUND.equals(rowKey)) return R.string.settings_background;
        if (NOW_PLAYING.equals(rowKey)) return R.string.settings_now_playing;
        if (NOW_PLAYING_ALBUM_BLUR.equals(rowKey)) return R.string.settings_player_album_blur;
        if (CLEAR_CACHE.equals(rowKey)) return R.string.settings_clear_cache;
        if (DATETIME.equals(rowKey)) return R.string.settings_datetime;
        if (LANGUAGE.equals(rowKey)) return R.string.settings_language;
        if (RADIO.equals(rowKey)) return R.string.settings_sub_radio;
        if (RADIO_FM.equals(rowKey)) return R.string.settings_sub_fm;
        if (VIDEO.equals(rowKey)) return R.string.settings_sub_video;
        if (MediaSuiteHost.ROW_AUTO_DETECT.equals(rowKey)) return R.string.radio_settings_auto_region;
        if (MediaSuiteHost.ROW_BUFFER_SD.equals(rowKey)) return R.string.radio_settings_buffer_sd;
        if (MediaSuiteHost.ROW_VIDEO_SLEEP.equals(rowKey)) return R.string.video_settings_sleep_during_playback;
        if (MediaSuiteHost.ROW_VIDEO_CROP.equals(rowKey)) return R.string.video_settings_crop_mode;
        if (HOME_SCREEN.equals(rowKey)) return R.string.home_screen_editor;
        if (SOULSEEK_SEARCH.equals(rowKey)) return R.string.soulseek_search_row;
        if (SOULSEEK_ACCOUNT.equals(rowKey)) return R.string.soulseek_account_row;
        if (SOULSEEK_CONNECTION.equals(rowKey)) return R.string.soulseek_menu_connection;
        if (SOULSEEK_ABOUT.equals(rowKey)) return R.string.soulseek_menu_about;
        if (SOULSEEK_REGENERATE.equals(rowKey)) return R.string.soulseek_regenerate_account;
        if (SOULSEEK_HIDE_HIGH_BITRATE.equals(rowKey)) return R.string.soulseek_hide_high_bitrate;
        if (SOULSEEK_SHARING.equals(rowKey)) return R.string.soulseek_share_library;
        if (SOULSEEK_REACH_ENABLED.equals(rowKey)) return R.string.soulseek_reach_enabled;
        if (SOULSEEK_ENABLED.equals(rowKey)) return R.string.soulseek_enabled;
        if (SOULSEEK_MESSAGING.equals(rowKey)) return R.string.soulseek_allow_messaging;
        if (SOULSEEK_FIND_USER.equals(rowKey)) return R.string.soulseek_find_user;
        if (SOULSEEK_FIND_REACH.equals(rowKey)) return R.string.soulseek_find_reach_users;
        if (SOULSEEK_MESSAGES.equals(rowKey)) return R.string.soulseek_messages;
        if (SOULSEEK_CHAT_ROOMS.equals(rowKey)) return R.string.soulseek_chat_rooms;
        if (DEEZER_SEARCH.equals(rowKey)) return R.string.deezer_search_row;
        if (DEEZER_ACCOUNT.equals(rowKey)) return R.string.deezer_account_row;
        if (DEEZER_CONNECTION.equals(rowKey)) return R.string.deezer_connection_row;
        if (DEEZER_ENABLED.equals(rowKey)) return R.string.deezer_enabled;
        if (DEEZER_INCLUDE_GET_MUSIC.equals(rowKey)) return R.string.get_music_include_deezer;
        if (DEEZER_CLEAR.equals(rowKey)) return R.string.deezer_clear_account;
        if (DEEZER_SETUP_PC.equals(rowKey)) return R.string.deezer_setup_pc;
        if (DEEZER_QUALITY.equals(rowKey)) return R.string.deezer_quality;
        if (WIDGET_CLOCK.equals(rowKey)) return R.string.widget_clock;
        if (WIDGET_BATTERY.equals(rowKey)) return R.string.widget_battery;
        if (WIDGET_ALBUM.equals(rowKey)) return R.string.widget_album;
        if (HOME_ARRANGE.equals(rowKey)) return R.string.home_screen_arrange;
        if (HOME_MORE.equals(rowKey)) return R.string.home_screen_more;
        if (HOME_MANAGE_MORE.equals(rowKey)) return R.string.home_screen_manage_more;
        if (MORE_MENU.equals(rowKey)) return R.string.home_menu_more;
        if (BG_SOURCE.equals(rowKey)) return R.string.settings_background_source;
        if (BG_SELECT.equals(rowKey)) return R.string.settings_select_background;
        if (BG_CLEAR.equals(rowKey)) return R.string.settings_clear_background;
        if (BT_POWER.equals(rowKey)) return R.string.bluetooth_power;
        if (WIFI_POWER.equals(rowKey)) return R.string.wifi_power;
        if (UPDATE_CURRENT.equals(rowKey)) return R.string.update_current_version;
        if (UPDATE_STABLE.equals(rowKey)) return R.string.update_latest_stable;
        if (UPDATE_NIGHTLY.equals(rowKey)) return R.string.update_latest_nightly;
        if (UPDATE_LATEST.equals(rowKey)) return R.string.update_latest_version;
        if (DT_AUTO_INTERNET.equals(rowKey)) return R.string.datetime_auto_internet;
        if (DT_TIMEZONE.equals(rowKey)) return R.string.datetime_timezone;
        if (DT_OBSERVE_DST.equals(rowKey)) return R.string.datetime_observe_dst;
        if (DT_SYNC_NOW.equals(rowKey)) return R.string.datetime_sync_now;
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
