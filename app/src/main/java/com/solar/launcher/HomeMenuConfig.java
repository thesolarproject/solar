package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

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

    public static final String ID_NOW_PLAYING = "now_playing";
    public static final String ID_MUSIC = "music";
    public static final String ID_BLUETOOTH = "bluetooth";
    public static final String ID_SETTINGS = "settings";
    public static final String ID_FM = "fm";
    public static final String ID_PC_UPLOAD = "pc_upload";
    public static final String ID_PODCASTS = "podcasts";
    public static final String ID_SOULSEEK = "soulseek";
    /** @deprecated migrated to {@link #ID_THEMES} */
    public static final String ID_GET_THEMES = "get_themes";
    public static final String ID_THEMES = "themes";
    public static final String ID_VIDEOS = "videos";
    public static final String ID_PHOTOS = "photos";
    public static final String ID_APPS = "apps";
    public static final String ID_MORE = "more";

    private static final String LEGACY_GET_THEMES = "get_themes";

    /** Stock Y1 home order for equivalent items, then Solar-only shortcuts. */
    private static final String DEFAULT_ORDER = String.join(",",
            ID_NOW_PLAYING, ID_MUSIC, ID_VIDEOS, ID_PHOTOS, ID_FM, ID_BLUETOOTH, ID_SETTINGS,
            ID_PC_UPLOAD, ID_PODCASTS, ID_SOULSEEK);

    /** Editor / arrange: all enabled shortcuts as if online (ignores live connectivity). */
    public static List<Entry> loadEditorHomeEntries(SharedPreferences prefs) {
        List<String> ids = loadHomeOrderIds(prefs);
        List<Entry> out = new ArrayList<Entry>();
        for (String id : ids) {
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
            ID_APPS, ID_THEMES));

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
    }

    private static final Entry[] CATALOG = {
            new Entry(ID_NOW_PLAYING, R.string.home_menu_now_playing, "nowPlaying", R.drawable.music_circle, null, false),
            new Entry(ID_MUSIC, R.string.home_menu_music, "music", R.drawable.music_list, null, false),
            new Entry(ID_BLUETOOTH, R.string.home_menu_bluetooth, "bluetooth", R.drawable.bluetooth_circle, null, false),
            new Entry(ID_SETTINGS, R.string.home_menu_settings, "settings", R.drawable.setting_circle, null, true),
            new Entry(ID_FM, R.string.home_menu_fm, "fm", R.drawable.radio_circle, null, false),
            new Entry(ID_PC_UPLOAD, R.string.home_menu_pc_upload, null, R.drawable.file_sync, "PC Upload", false),
            new Entry(ID_PODCASTS, R.string.home_menu_podcasts, null, R.drawable.music_list, "Podcasts", false),
            new Entry(ID_SOULSEEK, R.string.home_menu_soulseek, null, R.drawable.music_list, "Reach", false),
            new Entry(ID_THEMES, R.string.home_menu_themes, "theme", R.drawable.setting_circle, "Themes", false),
            new Entry(ID_VIDEOS, R.string.home_menu_videos, "video", R.drawable.music_list, null, false),
            new Entry(ID_PHOTOS, R.string.home_menu_photos, "photos", R.drawable.music_list, null, false),
            new Entry(ID_APPS, R.string.home_menu_apps, null, R.drawable.setting_circle, "Apps", false),
    };

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

    /** @param internetAvailable gates Reach / Themes; @param localNetworkAvailable gates PC Upload */
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
            if (!ConnectivityHelper.shouldShowHomeShortcut(id, internetAvailable,
                    localNetworkAvailable, podcastsSaved)) continue;
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
            if (!ConnectivityHelper.shouldShowHomeShortcut(id, internetAvailable,
                    localNetworkAvailable, podcastsSaved)) continue;
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

    public static void move(SharedPreferences prefs, int from, int to) {
        List<String> ids = new ArrayList<String>(loadHomeOrderIds(prefs));
        if (from < 0 || from >= ids.size() || to < 0 || to >= ids.size() || from == to) return;
        if (ID_MORE.equals(ids.get(from))) return;
        String item = ids.remove(from);
        ids.add(to, item);
        saveOrder(prefs, ids);
    }

    public static void moveMore(SharedPreferences prefs, int from, int to) {
        List<String> ids = new ArrayList<String>(loadMoreOrderIds(prefs));
        if (from < 0 || from >= ids.size() || to < 0 || to >= ids.size() || from == to) return;
        String item = ids.remove(from);
        ids.add(to, item);
        saveMoreOrder(prefs, ids);
    }

    static List<String> parseRawOrder(String raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(Arrays.asList(raw.split(",")));
    }

    static List<String> normalizeOrder(List<String> order) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        if (order != null) {
            for (String id : order) {
                if (id == null) continue;
                id = migrateId(id.trim());
                if (id.isEmpty() || ID_MORE.equals(id) || seen.contains(id) || find(id) == null) continue;
                seen.add(id);
                out.add(id);
            }
        }
        if (!out.contains(ID_SETTINGS)) {
            out.add(ID_SETTINGS);
        }
        if (out.isEmpty()) {
            out.add(ID_SETTINGS);
        }
        return out;
    }

    static List<String> normalizeMoreOrder(List<String> order) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        if (order != null) {
            for (String id : order) {
                if (id == null) continue;
                id = migrateId(id.trim());
                if (id.isEmpty() || ID_MORE.equals(id) || ID_SETTINGS.equals(id)
                        || seen.contains(id) || find(id) == null) continue;
                seen.add(id);
                out.add(id);
            }
        }
        return out;
    }

    static String migrateId(String id) {
        if (LEGACY_GET_THEMES.equals(id)) return ID_THEMES;
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
