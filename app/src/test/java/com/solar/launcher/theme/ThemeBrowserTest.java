package com.solar.launcher.theme;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThemeBrowserTest {

    private static ThemeBrowser.UiText labels() {
        ThemeBrowser.UiText t = new ThemeBrowser.UiText();
        t.filterTitle = "All";
        t.sortSubtitle = "Name";
        t.installedSection = "Installed";
        t.onlineSection = "Available Themes";
        return t;
    }

    @Test
    public void buildMainRows_sortsByName() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "z", "Zeta", new JSONObject());
        ThemeManager.ThemeEntry b = new ThemeManager.ThemeEntry("/b", "a", "Alpha", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildMainRows(
                Arrays.asList(a, b), null, 0,
                ThemeBrowser.FILTER_ALL, ThemeBrowser.SORT_NAME,
                false, false, null, labels());
        List<String> titles = new ArrayList<String>();
        for (ThemeBrowser.Row r : rows) {
            if (r.kind == ThemeBrowser.KIND_INSTALLED) titles.add(r.title);
        }
        assertEquals(Arrays.asList("Alpha", "Zeta"), titles);
    }

    @Test
    public void buildMainRows_filterInstalledSkipsOnlineSection() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "one", "One", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildMainRows(
                Arrays.asList(a), new ArrayList<ThemeDownloader.CatalogEntry>(), 0,
                ThemeBrowser.FILTER_INSTALLED, ThemeBrowser.SORT_NAME,
                false, true, null, labels());
        boolean hasOnline = false;
        for (ThemeBrowser.Row r : rows) {
            if (r.kind == ThemeBrowser.KIND_SECTION && "Available Themes".equals(r.title)) hasOnline = true;
            if (r.kind == ThemeBrowser.KIND_CATALOG) hasOnline = true;
        }
        assertTrue(!hasOnline);
    }

    @Test
    public void buildMainRows_installedFirstWhileCatalogLoading() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "one", "One", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildMainRows(
                Arrays.asList(a), null, 0,
                ThemeBrowser.FILTER_ALL, ThemeBrowser.SORT_NAME,
                true, false, null, labels());
        int installedIdx = -1;
        int availableIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            ThemeBrowser.Row r = rows.get(i);
            if (r.kind == ThemeBrowser.KIND_INSTALLED && "One".equals(r.title)) installedIdx = i;
            if (r.kind == ThemeBrowser.KIND_SECTION && "Available Themes".equals(r.title)) availableIdx = i;
        }
        assertTrue(installedIdx >= 0);
        assertTrue(availableIdx > installedIdx);
    }

    @Test
    public void buildInstalledRows_noCatalogRows() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "one", "One", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildInstalledRows(
                Arrays.asList(a), 0, true, 8, labels());
        for (ThemeBrowser.Row r : rows) {
            if (r.kind == ThemeBrowser.KIND_CATALOG) {
                throw new AssertionError("catalog row on installed screen");
            }
        }
    }

    @Test
    public void buildInstalledRows_offlineNoGetMore() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "one", "One", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildInstalledRows(
                Arrays.asList(a), 0, false, 8, labels());
        for (ThemeBrowser.Row r : rows) {
            if (r.kind == ThemeBrowser.KIND_GET_MORE) {
                throw new AssertionError("get more when offline");
            }
        }
    }

    @Test
    public void buildInstalledRows_getMoreTopWhenScrollNeeded() {
        List<ThemeManager.ThemeEntry> installed = new ArrayList<ThemeManager.ThemeEntry>();
        for (int i = 0; i < 10; i++) {
            installed.add(new ThemeManager.ThemeEntry("/" + i, "f" + i, "T" + i, new JSONObject()));
        }
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildInstalledRows(installed, 0, true, 5, labels());
        int getMoreIdx = -1;
        int firstInstalledIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind == ThemeBrowser.KIND_GET_MORE) getMoreIdx = i;
            if (firstInstalledIdx < 0 && rows.get(i).kind == ThemeBrowser.KIND_INSTALLED) {
                firstInstalledIdx = i;
            }
        }
        assertTrue(getMoreIdx >= 0);
        assertTrue(getMoreIdx < firstInstalledIdx);
    }

    @Test
    public void buildInstalledRows_getMoreBottomWhenFitsOnePage() {
        ThemeManager.ThemeEntry a = new ThemeManager.ThemeEntry("/a", "one", "One", new JSONObject());
        List<ThemeBrowser.Row> rows = ThemeBrowser.buildInstalledRows(
                Arrays.asList(a), 0, true, 20, labels());
        int getMoreIdx = -1;
        int lastInstalledIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind == ThemeBrowser.KIND_GET_MORE) getMoreIdx = i;
            if (rows.get(i).kind == ThemeBrowser.KIND_INSTALLED) lastInstalledIdx = i;
        }
        assertTrue(getMoreIdx > lastInstalledIdx);
    }

    @Test
    public void nextFilterAndToggleSort() {
        assertEquals(ThemeBrowser.FILTER_INSTALLED, ThemeBrowser.nextFilter(ThemeBrowser.FILTER_ALL));
        assertEquals(ThemeBrowser.SORT_AUTHOR, ThemeBrowser.toggleSort(ThemeBrowser.SORT_NAME));
    }
}
