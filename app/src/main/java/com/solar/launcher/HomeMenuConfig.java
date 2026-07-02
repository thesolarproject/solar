package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.theme.SolarTheming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ponytail: home shortcut catalog + visible order in prefs; settings always visible. */
public final class HomeMenuConfig {
    public static final String PREF_ORDER = "home_menu_order";
    public static final String PREF_MORE_ORDER = "home_more_order";
    public static final String PREF_MORE_ENABLED = "home_more_enabled";
    public static final String PREF_HOME_SCHEMA = "home_menu_schema";

    private static final int HOME_SCHEMA = 5;

    public static final String ID_NOW_PLAYING = "now_playing";
    public static final String ID_MUSIC = "music";
    public static final String ID_BLUETOOTH = "bluetooth";
    public static final String ID_SETTINGS = "settings";
    /** @deprecated use {@link #ID_RADIO} */
    public static final String ID_FM = "fm";
    public static final String ID_RADIO = "radio";
    public static final String ID_PC_UPLOAD = "pc_upload";
    public static final String ID_PODCASTS = "podcasts";
    public static final String ID_SOULSEEK = "soulseek";
    public static final String ID_DEEZER = "deezer";
    /** @deprecated migrated to {@link #ID_THEMES} */
    public static final String ID_GET_THEMES = "get_themes";
    public static final String ID_THEMES = "themes";
    public static final String ID_VIDEOS = "videos";
    public static final String ID_PHOTOS = "photos";
    public static final String ID_AUDIOBOOKS = "audiobooks";
    public static final String ID_APPS = "apps";
    public static final String ID_MORE = "more";

    private static final String LEGACY_GET_THEMES = "get_themes";

    /** Stock Y1 row order (theme homePageConfig keys align to these slots). */
    public static final List<String> STOCK_Y1_HOME_ORDER = Arrays.asList(
            ID_NOW_PLAYING, ID_MUSIC, ID_VIDEOS, ID_AUDIOBOOKS, ID_PHOTOS, ID_RADIO, ID_BLUETOOTH,
            ID_SETTINGS);

    /** Solar-only shortcuts after stock rows, in display order. */
    private static final List<String> SOLAR_HOME_EXTRAS = Arrays.asList(
            ID_PC_UPLOAD, ID_PODCASTS, ID_SOULSEEK, ID_THEMES, ID_APPS);

    /** Default enabled home shortcuts (coming-soon opt-in items omitted). */
    private static final String DEFAULT_ORDER = String.join(",",
            ID_NOW_PLAYING, ID_MUSIC, ID_RADIO, ID_BLUETOOTH, ID_SETTINGS,
            ID_PC_UPLOAD, ID_PODCASTS, ID_SOULSEEK);

    /** Fixed home / More menu order — not user-reorderable. */
    private static final List<String> FIXED_HOME_ORDER;
    static {
        List<String> order = new ArrayList<String>(STOCK_Y1_HOME_ORDER);
        order.addAll(SOLAR_HOME_EXTRAS);
        FIXED_HOME_ORDER = java.util.Collections.unmodifiableList(order);
    }

    /** Editor: all catalog shortcuts in fixed order (ignores live connectivity). */
    public static List<Entry> loadEditorCatalogEntries() {
        return loadEditorCatalogEntries(null);
    }

    /** Editor catalog; hides Radio until {@link com.solar.launcher.radio.RadioExperiment} is on. */
    public static List<Entry> loadEditorCatalogEntries(SharedPreferences prefs) {
        List<Entry> out = new ArrayList<Entry>();
        for (String id : FIXED_HOME_ORDER) {
            if ((ID_RADIO.equals(id) || ID_FM.equals(id))
                    && !com.solar.launcher.radio.RadioExperiment.isEnabled(prefs)) {
                continue;
            }
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** @deprecated use {@link #loadEditorCatalogEntries} */
    public static List<Entry> loadEditorHomeEntries(SharedPreferences prefs) {
        List<Entry> out = new ArrayList<Entry>();
        for (String id : loadHomeOrderIds(prefs)) {
            if (ID_MORE.equals(id)) continue;
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public static List<Entry> loadEditorMoreEntries(SharedPreferences prefs) {
        List<String> ids = loadMoreOrderIds(prefs);
        List<Entry> out = new ArrayList<Entry>();
        for (String id : ids) {
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    private static final Set<String> OPT_IN = new HashSet<String>(Arrays.asList(
            ID_APPS, ID_THEMES, ID_AUDIOBOOKS));

    private static final Set<String> COMING_SOON = new HashSet<String>(Arrays.asList(
            ID_AUDIOBOOKS));

    public static boolean isComingSoon(String id) {
        return id != null && COMING_SOON.contains(migrateId(id));
    }

    /** One-time migrations for home shortcut prefs. */
    public static void migrateHomePrefsIfNeeded(SharedPreferences prefs) {
        if (prefs == null) return;
        int schema = prefs.getInt(PREF_HOME_SCHEMA, 1);
        if (schema < 2) {
            for (String id : COMING_SOON) {
                setShortcutEnabled(prefs, id, false);
            }
            schema = 2;
        }
        if (schema < 3) {
            saveOrder(prefs, loadHomeOrderIds(prefs));
            saveMoreOrder(prefs, loadMoreOrderIds(prefs));
            schema = 3;
        }
        // ponytail: schema 4 removed Deezer home tile; schema 5 adds Radio + enables Videos/Photos.
        if (schema < 4) {
            List<String> home = new ArrayList<String>(loadHomeOrderIds(prefs));
            List<String> more = new ArrayList<String>(loadMoreOrderIds(prefs));
            home.remove(ID_DEEZER);
            more.remove(ID_DEEZER);
            saveOrder(prefs, home);
            saveMoreOrder(prefs, more);
            schema = 4;
        }
        if (schema < HOME_SCHEMA) {
            List<String> home = new ArrayList<String>(loadHomeOrderIds(prefs));
            List<String> more = new ArrayList<String>(loadMoreOrderIds(prefs));
            int fmIdx = home.indexOf(ID_FM);
            if (fmIdx >= 0) home.set(fmIdx, ID_RADIO);
            else if (!home.contains(ID_RADIO)) home.add(ID_RADIO);
            home.remove(ID_FM);
            int fmMore = more.indexOf(ID_FM);
            if (fmMore >= 0) more.set(fmMore, ID_RADIO);
            more.remove(ID_FM);
            saveOrder(prefs, home);
            saveMoreOrder(prefs, more);
            setShortcutEnabled(prefs, ID_VIDEOS, true);
            setShortcutEnabled(prefs, ID_PHOTOS, true);
            schema = HOME_SCHEMA;
        }
        if (prefs.getInt(PREF_HOME_SCHEMA, 1) < schema) {
            prefs.edit().putInt(PREF_HOME_SCHEMA, schema).commit();
        }
    }

    public static final class Entry {
        public final String id;
        public final int labelResId;
        public final String stockIconKey;
        public final int defaultResId;
        public final String solarAppName;
        public final boolean required;

        Entry(String id, int labelResId, String stockKey, int defaultResId,
              String solarAppName, boolean required) {
            this.id = id;
            this.labelResId = labelResId;
            this.stockIconKey = stockKey;
            this.defaultResId = defaultResId;
            this.solarAppName = solarAppName;
            this.required = required;
        }

        /** English menu title — stable input for {@code solarConfig.app*} keys. */
        public String englishLabel(Context context) {
            return SolarTheming.englishString(context, labelResId);
        }
    }

    private static final Entry[] CATALOG = {
            new Entry(ID_NOW_PLAYING, R.string.home_menu_now_playing, "nowPlaying", R.drawable.music_circle, null, false),
            new Entry(ID_MUSIC, R.string.home_menu_music, "music", R.drawable.music_list, null, false),
            new Entry(ID_BLUETOOTH, R.string.home_menu_bluetooth, "bluetooth", R.drawable.bluetooth_circle, null, false),
            new Entry(ID_SETTINGS, R.string.home_menu_settings, "settings", R.drawable.setting_circle, null, true),
            new Entry(ID_RADIO, R.string.home_menu_radio, "fm", R.drawable.radio_circle, "Radio", false),
            new Entry(ID_PC_UPLOAD, R.string.home_menu_pc_upload, null, R.drawable.file_sync, "PC Upload", false),
            new Entry(ID_PODCASTS, R.string.home_menu_podcasts, null, R.drawable.music_list, "Podcasts", false),
            new Entry(ID_SOULSEEK, R.string.home_menu_soulseek, null, R.drawable.music_list, "Get Music", false),
            new Entry(ID_THEMES, R.string.home_menu_themes, "theme", R.drawable.setting_circle, "Themes", false),
            new Entry(ID_VIDEOS, R.string.home_menu_videos, "video", R.drawable.music_list, null, false),
            new Entry(ID_PHOTOS, R.string.home_menu_photos, "photos", R.drawable.music_list, null, false),
            new Entry(ID_AUDIOBOOKS, R.string.home_menu_audiobooks, "audiobooks", R.drawable.music_list, null, false),
            new Entry(ID_APPS, R.string.home_menu_apps, null, R.drawable.setting_circle, "Apps", false),
    };

    /**
     * Optional {@code homePageConfig} key for Solar-only shortcuts when {@code solarConfig.app*}
     * is unset. Never maps unrelated Y1 keys (e.g. shuffleQuick).
     */
    public static String y1HomeIconFallbackKey(String id) {
        if (id == null) return null;
        id = migrateId(id);
        if (ID_SOULSEEK.equals(id)) return "music";
        if (ID_PODCASTS.equals(id) || ID_AUDIOBOOKS.equals(id)) return "audiobooks";
        if (ID_PHOTOS.equals(id)) return "photos";
        return null;
    }

    private HomeMenuConfig() {}

    public static boolean isOptIn(String id) {
        return id != null && OPT_IN.contains(migrateId(id));
    }

    public static Entry find(String id) {
        if (id == null) return null;
        String normalized = migrateId(id);
        for (Entry e : CATALOG) {
            if (e.id.equals(normalized)) return e;
        }
        return null;
    }

    public static List<Entry> catalog() {
        return Arrays.asList(CATALOG);
    }

    public static List<Entry> loadVisible(SharedPreferences prefs) {
        return loadVisibleForDisplay(prefs, true, true);
    }

    public static List<Entry> loadVisibleForDisplay(SharedPreferences prefs, boolean online) {
        return loadVisibleForDisplay(prefs, online, online);
    }

    /** @param internetAvailable gates Reach; @param localNetworkAvailable gates PC Upload */
    public static List<Entry> loadVisibleForDisplay(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable) {
        return loadVisibleForDisplay(prefs, internetAvailable, localNetworkAvailable,
                com.solar.launcher.podcast.PodcastLibrary.hasSavedContent());
    }

    static List<Entry> loadVisibleForDisplay(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable, boolean podcastsSaved) {
        List<String> ids = loadHomeOrderIds(prefs);
        List<Entry> out = new ArrayList<Entry>();
        for (String id : ids) {
            if (ID_MORE.equals(id)) continue;
            if ((ID_RADIO.equals(id) || ID_FM.equals(id))
                    && !com.solar.launcher.radio.RadioExperiment.isEnabled(prefs)) {
                continue;
            }
            if (ID_SOULSEEK.equals(id)) {
                if (!ConnectivityHelper.isGetMusicShortcutAvailable(prefs)) continue;
                if (!internetAvailable) continue;
            } else if (!ConnectivityHelper.shouldShowHomeShortcut(id, internetAvailable,
                    localNetworkAvailable, podcastsSaved)) {
                continue;
            }
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public static List<Entry> loadVisibleForDisplay(SharedPreferences prefs, Context context) {
        return loadVisibleForDisplay(prefs,
                ConnectivityHelper.isOnline(context),
                ConnectivityHelper.hasLocalNetwork(context));
    }

    public static List<Entry> loadMoreVisible(SharedPreferences prefs, boolean online) {
        return loadMoreVisible(prefs, online, online);
    }

    public static List<Entry> loadMoreVisible(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable) {
        return loadMoreVisible(prefs, internetAvailable, localNetworkAvailable,
                com.solar.launcher.podcast.PodcastLibrary.hasSavedContent());
    }

    static List<Entry> loadMoreVisible(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable, boolean podcastsSaved) {
        List<String> ids = loadMoreOrderIds(prefs);
        List<Entry> out = new ArrayList<Entry>();
        for (String id : ids) {
            if ((ID_RADIO.equals(id) || ID_FM.equals(id))
                    && !com.solar.launcher.radio.RadioExperiment.isEnabled(prefs)) {
                continue;
            }
            if (!ConnectivityHelper.shouldShowHomeShortcut(id, internetAvailable,
                    localNetworkAvailable, podcastsSaved)) continue;
            if (ID_SOULSEEK.equals(id)
                    && !ConnectivityHelper.isGetMusicShortcutAvailable(prefs)) {
                continue;
            }
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** More menu lists all shortcut-enabled items in More order, regardless of connectivity. */
    public static List<Entry> loadMoreAllEnabled(SharedPreferences prefs) {
        List<String> ids = loadMoreOrderIds(prefs);
        List<Entry> out = new ArrayList<Entry>();
        for (String id : ids) {
            Entry e = find(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public static List<Entry> loadMoreVisible(SharedPreferences prefs, Context context) {
        return loadMoreVisible(prefs,
                ConnectivityHelper.isOnline(context),
                ConnectivityHelper.hasLocalNetwork(context));
    }

    public static List<String> loadVisibleIds(SharedPreferences prefs) {
        List<String> out = new ArrayList<String>();
        for (Entry e : loadVisible(prefs)) out.add(e.id);
        return out;
    }

    public static List<String> loadHomeOrderIds(SharedPreferences prefs) {
        String raw = prefs != null ? prefs.getString(PREF_ORDER, DEFAULT_ORDER) : DEFAULT_ORDER;
        return normalizeOrder(parseRawOrder(raw));
    }

    public static List<String> loadMoreOrderIds(SharedPreferences prefs) {
        String raw = prefs != null ? prefs.getString(PREF_MORE_ORDER, "") : "";
        return normalizeMoreOrder(parseRawOrder(raw));
    }

    public static boolean isMoreEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_MORE_ENABLED, false);
    }

    public static void setMoreEnabled(SharedPreferences prefs, boolean enabled) {
        if (prefs == null) return;
        prefs.edit().putBoolean(PREF_MORE_ENABLED, enabled).commit();
    }

    public static boolean shouldShowMoreTile(SharedPreferences prefs, boolean online) {
        return shouldShowMoreTile(prefs, online, online);
    }

    public static boolean shouldShowMoreTile(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable) {
        return isMoreEnabled(prefs) && !loadMoreVisible(prefs, internetAvailable, localNetworkAvailable).isEmpty();
    }

    public static boolean shouldShowMoreTile(SharedPreferences prefs, Context context) {
        return shouldShowMoreTile(prefs,
                ConnectivityHelper.isOnline(context),
                ConnectivityHelper.hasLocalNetwork(context));
    }

    public static boolean isVisible(SharedPreferences prefs, String id) {
        return loadHomeOrderIds(prefs).contains(migrateId(id));
    }

    public static boolean isInMore(SharedPreferences prefs, String id) {
        return loadMoreOrderIds(prefs).contains(migrateId(id));
    }

    /** On home or in More — editor shows a single On/Off toggle. */
    public static boolean isShortcutEnabled(SharedPreferences prefs, String id) {
        return isVisible(prefs, id) || isInMore(prefs, id);
    }

    public static void setShortcutEnabled(SharedPreferences prefs, String id, boolean enabled) {
        if (prefs == null || id == null) return;
        id = migrateId(id);
        Entry e = find(id);
        if (e == null || e.required) return;
        if (enabled) {
            setVisible(prefs, id, true);
            return;
        }
        List<String> home = new ArrayList<String>(loadHomeOrderIds(prefs));
        List<String> more = new ArrayList<String>(loadMoreOrderIds(prefs));
        home.remove(id);
        more.remove(id);
        saveOrder(prefs, home);
        saveMoreOrder(prefs, more);
    }

    /** Hide from home screen; item stays enabled in More. */
    public static void hideFromHome(SharedPreferences prefs, String id) {
        setVisible(prefs, id, false);
    }

    public static void setVisible(SharedPreferences prefs, String id, boolean visible) {
        if (prefs == null || id == null) return;
        id = migrateId(id);
        Entry e = find(id);
        if (e == null || e.required) return;
        List<String> home = new ArrayList<String>(loadHomeOrderIds(prefs));
        List<String> more = new ArrayList<String>(loadMoreOrderIds(prefs));
        if (visible) {
            more.remove(id);
            if (!home.contains(id)) home.add(id);
        } else {
            home.remove(id);
            if (!more.contains(id)) {
                more.add(id);
                setMoreEnabled(prefs, true);
            }
        }
        saveOrder(prefs, home);
        saveMoreOrder(prefs, more);
        if (visible && more.isEmpty()) {
            setMoreEnabled(prefs, false);
        }
    }

    public static void saveOrder(SharedPreferences prefs, List<String> order) {
        if (prefs == null) return;
        List<String> cleaned = normalizeOrder(order);
        prefs.edit().putString(PREF_ORDER, joinIds(cleaned)).commit();
    }

    public static void saveMoreOrder(SharedPreferences prefs, List<String> order) {
        if (prefs == null) return;
        List<String> cleaned = normalizeMoreOrder(order);
        prefs.edit().putString(PREF_MORE_ORDER, joinIds(cleaned)).commit();
    }

    /**
     * Home-menu row order: connectivity-filtered shortcuts plus More tile when shown.
     */
    public static List<String> loadHomeDisplayIds(SharedPreferences prefs,
            boolean internetAvailable, boolean localNetworkAvailable, boolean showNowPlaying) {
        List<String> out = new ArrayList<String>();
        for (Entry e : loadVisibleForDisplay(prefs, internetAvailable, localNetworkAvailable)) {
            if (ID_NOW_PLAYING.equals(e.id) && !showNowPlaying) continue;
            out.add(e.id);
        }
        if (shouldShowMoreTile(prefs, internetAvailable, localNetworkAvailable)) {
            out.add(ID_MORE);
        }
        return out;
    }

    static List<String> parseRawOrder(String raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(Arrays.asList(raw.split(",")));
    }

    static List<String> normalizeOrder(List<String> order) {
        Set<String> enabled = new HashSet<String>();
        if (order != null) {
            for (String id : order) {
                if (id == null) continue;
                id = migrateId(id.trim());
                if (id.isEmpty() || ID_MORE.equals(id)) continue;
                if (find(id) == null) continue;
                enabled.add(id);
            }
        }
        if (enabled.isEmpty()) {
            for (String id : parseRawOrder(DEFAULT_ORDER)) {
                enabled.add(migrateId(id));
            }
        }
        enabled.add(ID_SETTINGS);
        List<String> out = new ArrayList<String>();
        for (String id : FIXED_HOME_ORDER) {
            if (enabled.contains(id)) out.add(id);
        }
        return out;
    }

    static List<String> normalizeMoreOrder(List<String> order) {
        Set<String> inMore = new HashSet<String>();
        if (order != null) {
            for (String id : order) {
                if (id == null) continue;
                id = migrateId(id.trim());
                if (id.isEmpty() || ID_MORE.equals(id) || ID_SETTINGS.equals(id)) continue;
                if (find(id) == null) continue;
                inMore.add(id);
            }
        }
        List<String> out = new ArrayList<String>();
        for (String id : FIXED_HOME_ORDER) {
            if (inMore.contains(id)) out.add(id);
        }
        return out;
    }

    static String migrateId(String id) {
        if (LEGACY_GET_THEMES.equals(id)) return ID_THEMES;
        if (ID_FM.equals(id)) return ID_RADIO;
        return id;
    }

    /** Public alias for {@link #migrateId}. */
    public static String migrateIdStatic(String id) {
        return migrateId(id);
    }

    private static String joinIds(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
