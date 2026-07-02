package com.solar.launcher;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeMenuConfigTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new MemPrefs();
    }

    @Test
    public void stockHomeOrder_matchesY1Layout() {
        List<String> stock = HomeMenuConfig.STOCK_Y1_HOME_ORDER;
        if (stock.size() != 8) throw new AssertionError("stock size " + stock.size());
        if (!HomeMenuConfig.ID_NOW_PLAYING.equals(stock.get(0))) throw new AssertionError("now playing");
        if (!HomeMenuConfig.ID_MUSIC.equals(stock.get(1))) throw new AssertionError("music");
        if (!HomeMenuConfig.ID_VIDEOS.equals(stock.get(2))) throw new AssertionError("videos");
        if (!HomeMenuConfig.ID_AUDIOBOOKS.equals(stock.get(3))) throw new AssertionError("audiobooks");
        if (!HomeMenuConfig.ID_PHOTOS.equals(stock.get(4))) throw new AssertionError("photos");
        if (!HomeMenuConfig.ID_RADIO.equals(stock.get(5))) throw new AssertionError("radio");
        if (!HomeMenuConfig.ID_BLUETOOTH.equals(stock.get(6))) throw new AssertionError("bluetooth");
        if (!HomeMenuConfig.ID_SETTINGS.equals(stock.get(7))) throw new AssertionError("settings");
    }

    @Test
    public void defaultOrder_matchesY1StockLayout() {
        prefs.edit().putBoolean(com.solar.launcher.radio.RadioExperiment.PREF_RADIO_EXPERIMENT, true).commit();
        List<HomeMenuConfig.Entry> visible = HomeMenuConfig.loadVisible(prefs);
        if (visible.size() != 8) throw new AssertionError("default size " + visible.size());
        if (!HomeMenuConfig.ID_NOW_PLAYING.equals(visible.get(0).id)) {
            throw new AssertionError("now playing position");
        }
        if (!HomeMenuConfig.ID_MUSIC.equals(visible.get(1).id)) {
            throw new AssertionError("music position");
        }
        if (!HomeMenuConfig.ID_RADIO.equals(visible.get(2).id)) {
            throw new AssertionError("radio position");
        }
        if (!HomeMenuConfig.ID_BLUETOOTH.equals(visible.get(3).id)) {
            throw new AssertionError("bluetooth position");
        }
        if (!HomeMenuConfig.ID_SETTINGS.equals(visible.get(4).id)) {
            throw new AssertionError("settings position");
        }
    }

    @Test
    public void migrateHomePrefs_renormalizesStockOrder() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_SOULSEEK, HomeMenuConfig.ID_FM, HomeMenuConfig.ID_MUSIC));
        HomeMenuConfig.migrateHomePrefsIfNeeded(prefs);
        List<String> home = HomeMenuConfig.loadHomeOrderIds(prefs);
        if (!HomeMenuConfig.ID_MUSIC.equals(home.get(0))) {
            throw new AssertionError("music should lead stock block");
        }
        if (!home.contains(HomeMenuConfig.ID_RADIO)) {
            throw new AssertionError("radio should be present after fm migrate");
        }
        if (home.indexOf(HomeMenuConfig.ID_RADIO) <= home.indexOf(HomeMenuConfig.ID_MUSIC)) {
            throw new AssertionError("radio should follow music");
        }
        if (!HomeMenuConfig.ID_SOULSEEK.equals(home.get(home.size() - 1))) {
            throw new AssertionError("solar extras trail");
        }
    }

    @Test
    public void comingSoonOffByDefault() {
        List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
        if (ids.contains(HomeMenuConfig.ID_VIDEOS)) throw new AssertionError("videos default on");
        if (ids.contains(HomeMenuConfig.ID_PHOTOS)) throw new AssertionError("photos default on");
        if (ids.contains(HomeMenuConfig.ID_AUDIOBOOKS)) throw new AssertionError("audiobooks default on");
        List<HomeMenuConfig.Entry> editor = HomeMenuConfig.loadEditorCatalogEntries();
        boolean hasVideos = false;
        for (HomeMenuConfig.Entry e : editor) {
            if (HomeMenuConfig.ID_VIDEOS.equals(e.id)) hasVideos = true;
        }
        if (!hasVideos) throw new AssertionError("videos missing from editor catalog");
    }

    @Test
    public void migrateHomePrefs_enablesVideosAndPhotosOnSchema5() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_MUSIC, HomeMenuConfig.ID_VIDEOS, HomeMenuConfig.ID_SETTINGS));
        HomeMenuConfig.migrateHomePrefsIfNeeded(prefs);
        if (!HomeMenuConfig.isShortcutEnabled(prefs, HomeMenuConfig.ID_VIDEOS)) {
            throw new AssertionError("videos should be on after schema 5 migrate");
        }
        if (!HomeMenuConfig.isShortcutEnabled(prefs, HomeMenuConfig.ID_PHOTOS)) {
            throw new AssertionError("photos should be on after schema 5 migrate");
        }
    }

    @Test
    public void normalizeOrder_enforcesFixedLayout() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_SOULSEEK, HomeMenuConfig.ID_MUSIC, HomeMenuConfig.ID_SETTINGS));
        List<String> home = HomeMenuConfig.loadHomeOrderIds(prefs);
        if (!HomeMenuConfig.ID_MUSIC.equals(home.get(0))) {
            throw new AssertionError("music should lead in fixed order");
        }
        if (!HomeMenuConfig.ID_SOULSEEK.equals(home.get(home.size() - 1))) {
            throw new AssertionError("soulseek should trail solar extras");
        }
    }

    @Test
    public void settingsForcedWhenMissing() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_MUSIC, HomeMenuConfig.ID_SOULSEEK));
        List<String> ids = HomeMenuConfig.loadVisibleIds(prefs);
        if (!ids.contains(HomeMenuConfig.ID_SETTINGS)) {
            throw new AssertionError("settings missing");
        }
    }

    @Test
    public void unknownIdsStripped() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_MUSIC, "bogus", HomeMenuConfig.ID_SETTINGS));
        List<String> ids = HomeMenuConfig.loadVisibleIds(prefs);
        if (ids.contains("bogus")) throw new AssertionError("bogus kept");
        if (ids.size() != 2) throw new AssertionError("size " + ids.size());
    }

    @Test
    public void migrateGetThemesToThemes() {
        HomeMenuConfig.saveOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_MUSIC, HomeMenuConfig.ID_GET_THEMES));
        List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
        if (!ids.contains(HomeMenuConfig.ID_THEMES)) {
            throw new AssertionError("themes migration");
        }
    }

    @Test
    public void hideFromHomeMovesToMore() {
        HomeMenuConfig.hideFromHome(prefs, HomeMenuConfig.ID_FM);
        if (HomeMenuConfig.isVisible(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm still on home");
        }
        if (!HomeMenuConfig.isInMore(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm not in more");
        }
        if (!HomeMenuConfig.isShortcutEnabled(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm should stay enabled");
        }
    }

    @Test
    public void moreTileHiddenWhenMoreOrderEmpty() {
        HomeMenuConfig.setMoreEnabled(prefs, true);
        HomeMenuConfig.saveMoreOrder(prefs, new ArrayList<String>());
        if (HomeMenuConfig.shouldShowMoreTile(prefs, false, false)) {
            throw new AssertionError("more tile should hide when order empty");
        }
    }

    @Test
    public void moreAllEnabledIncludesGatedItems() {
        HomeMenuConfig.saveMoreOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_PODCASTS, HomeMenuConfig.ID_PC_UPLOAD));
        List<HomeMenuConfig.Entry> more = HomeMenuConfig.loadMoreAllEnabled(prefs);
        if (more.size() != 2) throw new AssertionError("expected 2, got " + more.size());
    }

    @Test
    public void moveLastItemHomeDisablesMoreTile() {
        HomeMenuConfig.hideFromHome(prefs, HomeMenuConfig.ID_FM);
        HomeMenuConfig.setVisible(prefs, HomeMenuConfig.ID_FM, true);
        if (HomeMenuConfig.isMoreEnabled(prefs)) {
            throw new AssertionError("more should auto-disable when order empty");
        }
    }

    @Test
    public void disableShortcutRemovesFromHomeAndMore() {
        HomeMenuConfig.setShortcutEnabled(prefs, HomeMenuConfig.ID_FM, false);
        if (HomeMenuConfig.isVisible(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm still on home");
        }
        if (HomeMenuConfig.isInMore(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm still in more");
        }
        if (HomeMenuConfig.isShortcutEnabled(prefs, HomeMenuConfig.ID_FM)) {
            throw new AssertionError("fm should be off");
        }
    }

    @Test
    public void optInDefaultsOff() {
        List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
        if (ids.contains(HomeMenuConfig.ID_APPS)) throw new AssertionError("apps default");
        if (ids.contains(HomeMenuConfig.ID_THEMES)) throw new AssertionError("themes default");
        if (ids.contains(HomeMenuConfig.ID_VIDEOS)) throw new AssertionError("videos default");
        HomeMenuConfig.setVisible(prefs, HomeMenuConfig.ID_THEMES, true);
        if (!HomeMenuConfig.isVisible(prefs, HomeMenuConfig.ID_THEMES)) {
            throw new AssertionError("themes toggle");
        }
    }

    @Test
    public void offlineFiltersInternetAndLocalItems() {
        List<HomeMenuConfig.Entry> offline = HomeMenuConfig.loadVisibleForDisplay(prefs, false, false);
        for (HomeMenuConfig.Entry e : offline) {
            if (ConnectivityHelper.itemNeedsInternetForDiscovery(e.id)) {
                throw new AssertionError("internet item shown offline: " + e.id);
            }
            if (ConnectivityHelper.itemNeedsLocalNetwork(e.id)) {
                throw new AssertionError("local-network item shown offline: " + e.id);
            }
            if (HomeMenuConfig.ID_PODCASTS.equals(e.id)) {
                throw new AssertionError("podcasts without saved offline");
            }
        }
    }

    @Test
    public void offlinePodcastsVisibleWhenSaved() {
        List<HomeMenuConfig.Entry> offline = HomeMenuConfig.loadVisibleForDisplay(
                prefs, false, false, true);
        boolean podcasts = false;
        for (HomeMenuConfig.Entry e : offline) {
            if (HomeMenuConfig.ID_PODCASTS.equals(e.id)) podcasts = true;
        }
        if (!podcasts) throw new AssertionError("podcasts missing with saved content");
    }

    @Test
    public void loadMoreVisible_excludesReachWhenOffline() {
        HomeMenuConfig.saveMoreOrder(prefs, Arrays.asList(
                HomeMenuConfig.ID_SOULSEEK, HomeMenuConfig.ID_MUSIC));
        List<HomeMenuConfig.Entry> more = HomeMenuConfig.loadMoreVisible(prefs, false, true);
        for (HomeMenuConfig.Entry e : more) {
            if (HomeMenuConfig.ID_SOULSEEK.equals(e.id)) {
                throw new AssertionError("reach in more offline");
            }
        }
        if (more.size() != 1 || !HomeMenuConfig.ID_MUSIC.equals(more.get(0).id)) {
            throw new AssertionError("expected music only, got " + more.size());
        }
    }

    @Test
    public void moreTileHiddenWhenAllMoreItemsFiltered() {
        HomeMenuConfig.setMoreEnabled(prefs, true);
        HomeMenuConfig.saveMoreOrder(prefs, Arrays.asList(HomeMenuConfig.ID_SOULSEEK));
        if (HomeMenuConfig.shouldShowMoreTile(prefs, false, true)) {
            throw new AssertionError("more tile should hide when filtered list empty");
        }
    }

    @Test
    public void lanWithoutInternetShowsPcUploadOnly() {
        List<HomeMenuConfig.Entry> onLan = HomeMenuConfig.loadVisibleForDisplay(prefs, false, true);
        boolean pc = false;
        for (HomeMenuConfig.Entry e : onLan) {
            if (HomeMenuConfig.ID_PC_UPLOAD.equals(e.id)) pc = true;
            if (ConnectivityHelper.itemNeedsInternetForDiscovery(e.id)) {
                throw new AssertionError("internet item on lan-only: " + e.id);
            }
        }
        if (!pc) throw new AssertionError("pc upload missing on lan");
    }

    @Test
    public void editorCatalogEntries_followFixedOrder() {
        List<HomeMenuConfig.Entry> editor = HomeMenuConfig.loadEditorCatalogEntries();
        if (editor.size() < 11) throw new AssertionError("catalog too small");
        if (!HomeMenuConfig.ID_AUDIOBOOKS.equals(editor.get(3).id)) {
            throw new AssertionError("audiobooks slot in editor");
        }
    }

    @Test
    public void radioHiddenUntilExperimentEnabled() {
        List<HomeMenuConfig.Entry> hidden = HomeMenuConfig.loadVisibleForDisplay(prefs, true, true);
        for (HomeMenuConfig.Entry e : hidden) {
            if (HomeMenuConfig.ID_RADIO.equals(e.id) || HomeMenuConfig.ID_FM.equals(e.id)) {
                throw new AssertionError("radio visible while experiment off");
            }
        }
        prefs.edit().putBoolean(com.solar.launcher.radio.RadioExperiment.PREF_RADIO_EXPERIMENT, true)
                .commit();
        boolean hasRadio = false;
        for (HomeMenuConfig.Entry e : HomeMenuConfig.loadVisibleForDisplay(prefs, true, true)) {
            if (HomeMenuConfig.ID_RADIO.equals(e.id)) hasRadio = true;
        }
        if (!hasRadio) throw new AssertionError("radio missing when experiment on");
        boolean editorHasRadio = false;
        for (HomeMenuConfig.Entry e : HomeMenuConfig.loadEditorCatalogEntries(prefs)) {
            if (HomeMenuConfig.ID_RADIO.equals(e.id)) editorHasRadio = true;
        }
        if (!editorHasRadio) throw new AssertionError("editor missing radio when experiment on");
        prefs.edit().putBoolean(com.solar.launcher.radio.RadioExperiment.PREF_RADIO_EXPERIMENT, false)
                .commit();
        for (HomeMenuConfig.Entry e : HomeMenuConfig.loadEditorCatalogEntries(prefs)) {
            if (HomeMenuConfig.ID_RADIO.equals(e.id)) {
                throw new AssertionError("editor shows radio while experiment off");
            }
        }
    }

    @Test
    public void toggleThemesOnHome() {
        HomeMenuConfig.setVisible(prefs, HomeMenuConfig.ID_THEMES, true);
        if (!HomeMenuConfig.isVisible(prefs, HomeMenuConfig.ID_THEMES)) {
            throw new AssertionError("themes not visible");
        }
        HomeMenuConfig.setVisible(prefs, HomeMenuConfig.ID_SETTINGS, false);
        if (!HomeMenuConfig.isVisible(prefs, HomeMenuConfig.ID_SETTINGS)) {
            throw new AssertionError("settings must stay visible");
        }
    }

    @Test
    public void loadHomeDisplayIds_matchesVisibleHomeOrderWhenOnline() {
        prefs.edit().putBoolean(com.solar.launcher.radio.RadioExperiment.PREF_RADIO_EXPERIMENT, true).commit();
        HomeMenuConfig.setMoreEnabled(prefs, true);
        HomeMenuConfig.hideFromHome(prefs, HomeMenuConfig.ID_FM);
        List<String> display = HomeMenuConfig.loadHomeDisplayIds(prefs, true, true, true);
        List<HomeMenuConfig.Entry> visible = HomeMenuConfig.loadVisibleForDisplay(prefs, true, true);
        if (display.size() != visible.size() + 1) {
            throw new AssertionError("display size " + display.size() + " visible " + visible.size());
        }
        for (int i = 0; i < visible.size(); i++) {
            if (!visible.get(i).id.equals(display.get(i))) {
                throw new AssertionError("order mismatch at " + i);
            }
        }
        if (!HomeMenuConfig.ID_MORE.equals(display.get(display.size() - 1))) {
            throw new AssertionError("more tile last");
        }
    }

    private static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<String, Object>();

        @Override public Map<String, ?> getAll() { return map; }
        @Override public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }
        @Override public int getInt(String key, int def) {
            Object v = map.get(key);
            return v instanceof Integer ? (Integer) v : def;
        }
        @Override public long getLong(String key, long def) { return def; }
        @Override public float getFloat(String key, float def) { return def; }
        @Override public boolean getBoolean(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }
        @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) { map.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { map.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { map.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { map.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { map.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> values) { return this; }
                @Override public Editor remove(String key) { map.remove(key); return this; }
                @Override public Editor clear() { map.clear(); return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() { commit(); }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    }
}
