package com.solar.launcher.theme;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ponytail: unified installed + online theme rows — sort/filter in one pass. */
public final class ThemeBrowser {
    private ThemeBrowser() {}

    public static final int FILTER_ALL = 0;
    public static final int FILTER_INSTALLED = 1;
    public static final int FILTER_ONLINE = 2;
    public static final int FILTER_UPDATES = 3;

    public static final int SORT_NAME = 0;
    public static final int SORT_AUTHOR = 1;

    public static final int KIND_BACK = 0;
    public static final int KIND_FILTER = 1;
    public static final int KIND_SECTION = 2;
    public static final int KIND_STATUS = 3;
    public static final int KIND_INSTALLED = 4;
    public static final int KIND_CATALOG = 5;
    public static final int KIND_VARIANT = 6;
    public static final int KIND_GET_MORE = 7;

    /** User-visible copy for {@link #buildMainRows} / {@link #buildVariantRows}. */
    public static final class UiText {
        public String filterTitle = "All themes";
        public String sortSubtitle = "Sort: Name";
        public String installedSection = "Installed";
        public String onlineSection = "Available Themes";
        public String loading = "Loading available themes…";
        public String noInstalledMatch = "No installed themes match this filter.";
        public String noOnlineMatch = "No downloadable themes match this filter.";
        public String noInternet = "Connect to the internet to browse online themes.";
        public String noThemes = "No themes found.";
        public String noVariants = "No variants found.";
        public String getMore = "Get More";
    }

    public static final class Row {
        public final int kind;
        public final String title;
        public final String subtitle;
        public final String prefix;
        public final int themeIndex;
        public final ThemeDownloader.CatalogEntry catalog;
        public final ThemeDownloader.ThemeVariant variant;
        public final boolean active;
        public final boolean needsUpdate;

        Row(int kind, String title, String subtitle, String prefix,
            int themeIndex, ThemeDownloader.CatalogEntry catalog,
            ThemeDownloader.ThemeVariant variant, boolean active, boolean needsUpdate) {
            this.kind = kind;
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.prefix = prefix != null ? prefix : "";
            this.themeIndex = themeIndex;
            this.catalog = catalog;
            this.variant = variant;
            this.active = active;
            this.needsUpdate = needsUpdate;
        }

        static Row back() {
            return new Row(KIND_BACK, "", "", "", -1, null, null, false, false);
        }

        static Row filter(String title, String subtitle) {
            return new Row(KIND_FILTER, title, subtitle, "", -1, null, null, false, false);
        }

        static Row section(String title) {
            return new Row(KIND_SECTION, title, "", "", -1, null, null, false, false);
        }

        static Row status(String message) {
            return new Row(KIND_STATUS, message, "", "", -1, null, null, false, false);
        }
    }

    public static Row backRow() { return Row.back(); }
    public static Row filterRow(String title, String subtitle) { return Row.filter(title, subtitle); }
    public static Row sectionRow(String title) { return Row.section(title); }
    public static Row statusRow(String message) { return Row.status(message); }

    public static String installedAuthor(ThemeManager.ThemeEntry theme) {
        if (theme == null || theme.root == null) return "";
        JSONObject info = theme.root.optJSONObject("theme_info");
        if (info != null) {
            String a = info.optString("author", "").trim();
            if (!a.isEmpty()) return a;
        }
        return theme.root.optString("author", "").trim();
    }

    public static ThemeDownloader.CatalogEntry findCatalogForFolder(
            List<ThemeDownloader.CatalogEntry> catalog, String folderName) {
        if (catalog == null || folderName == null) return null;
        String lower = folderName.toLowerCase(Locale.US);
        for (ThemeDownloader.CatalogEntry e : catalog) {
            if (folderName.equalsIgnoreCase(e.folder)) return e;
            if (e.hasVariants()) {
                for (ThemeDownloader.ThemeVariant v : e.variants) {
                    if (v.installFolder(e.name).equalsIgnoreCase(folderName)) return e;
                }
            }
            String resolved = ThemeDownloader.resolvedInstallFolder(e, null);
            if (resolved != null && resolved.equalsIgnoreCase(folderName)) return e;
        }
        for (ThemeDownloader.CatalogEntry e : catalog) {
            if (lower.startsWith(e.folder.toLowerCase(Locale.US) + "/")) return e;
        }
        return null;
    }

    public static List<Row> buildVariantRows(ThemeDownloader.CatalogEntry entry,
            List<ThemeDownloader.ThemeVariant> variants, UiText text) {
        UiText t = text != null ? text : new UiText();
        List<Row> out = new ArrayList<Row>();
        out.add(Row.back());
        if (entry == null) return out;
        out.add(Row.section(entry.name));
        if (variants == null || variants.isEmpty()) {
            out.add(Row.status(t.noVariants));
            return out;
        }
        for (ThemeDownloader.ThemeVariant v : variants) {
            String prefix = "↓ ";
            boolean needsUpdate = false;
            try {
                if (v.isComplete(entry.name)) prefix = "✔ ";
                else if (v.isInstalled(entry.name)) {
                    prefix = "↻ ";
                    needsUpdate = true;
                }
            } catch (Exception ignored) {}
            out.add(new Row(KIND_VARIANT, v.displayName(entry.name), entry.author, prefix,
                    -1, entry, v, false, needsUpdate));
        }
        return out;
    }

    public static List<Row> buildMainRows(List<ThemeManager.ThemeEntry> installed,
            List<ThemeDownloader.CatalogEntry> catalog,
            int currentThemeIndex, int filter, int sort,
            boolean catalogLoading, boolean catalogAvailable, String catalogError,
            UiText text) {
        return buildMainRows(installed, catalog, currentThemeIndex, filter, sort,
                catalogLoading, catalogAvailable, catalogError, null, text);
    }

    public static List<Row> buildMainRows(List<ThemeManager.ThemeEntry> installed,
            List<ThemeDownloader.CatalogEntry> catalog,
            int currentThemeIndex, int filter, int sort,
            boolean catalogLoading, boolean catalogAvailable, String catalogError,
            List<Row> onlineRowsPrebuilt, UiText text) {
        UiText t = text != null ? text : new UiText();
        List<Row> out = new ArrayList<Row>();
        out.add(Row.back());
        out.add(Row.filter(t.filterTitle, t.sortSubtitle));

        boolean catalogReady = catalog != null && catalogAvailable && !catalogLoading;

        List<Row> installedRows = new ArrayList<Row>();
        if (installed != null) {
            for (int i = 0; i < installed.size(); i++) {
                ThemeManager.ThemeEntry theme = installed.get(i);
                if (theme.folderName == null) continue;
                ThemeDownloader.CatalogEntry cat = catalogReady
                        ? findCatalogForFolder(catalog, theme.folderName) : null;
                boolean needsUpdate = false;
                if (cat != null) {
                    ThemeDownloader.ThemeVariant v = ThemeDownloader.findVariantForInstalledFolder(cat, theme.folderName);
                    if (v != null) {
                        try {
                            needsUpdate = v.isInstalled(cat.name) && !v.isComplete(cat.name);
                        } catch (Exception ignored) {}
                    } else {
                        needsUpdate = cat.isInstalled() && !cat.isComplete();
                    }
                }
                if (filter == FILTER_UPDATES && !needsUpdate) continue;
                if (filter == FILTER_ONLINE) continue;
                String author = installedAuthor(theme);
                String prefix = (i == currentThemeIndex) ? "✔ " : "   ";
                if (needsUpdate) prefix = "↻ ";
                installedRows.add(new Row(KIND_INSTALLED, theme.name, author, prefix, i, cat, null,
                        i == currentThemeIndex, needsUpdate));
            }
        }
        sortRows(installedRows, sort);

        List<Row> onlineRows = onlineRowsPrebuilt;
        if (onlineRows == null && catalogReady
                && filter != FILTER_INSTALLED && filter != FILTER_UPDATES) {
            Set<String> installedFolders = new HashSet<String>();
            if (installed != null) {
                for (ThemeManager.ThemeEntry theme : installed) {
                    if (theme.folderName != null) {
                        installedFolders.add(theme.folderName.toLowerCase(Locale.US));
                    }
                }
            }
            onlineRows = buildOnlineRows(catalog, installedFolders, filter, sort);
        }

        if (filter == FILTER_ALL || filter == FILTER_INSTALLED || filter == FILTER_UPDATES) {
            if (!installedRows.isEmpty()) {
                out.add(Row.section(t.installedSection));
                out.addAll(installedRows);
            } else if (filter == FILTER_INSTALLED || filter == FILTER_UPDATES) {
                out.add(Row.status(t.noInstalledMatch));
            }
        }
        if (filter == FILTER_ALL || filter == FILTER_ONLINE) {
            boolean showAvailable = catalogLoading || catalogAvailable
                    || (catalogError != null && !catalogError.isEmpty());
            if (showAvailable) {
                out.add(Row.section(t.onlineSection));
                if (catalogLoading && (onlineRows == null || onlineRows.isEmpty())) {
                    out.add(Row.status(t.loading));
                } else if (catalogError != null && !catalogError.isEmpty()
                        && (onlineRows == null || onlineRows.isEmpty())) {
                    out.add(Row.status(catalogError));
                } else if (onlineRows != null && !onlineRows.isEmpty()) {
                    out.addAll(onlineRows);
                } else if (!catalogLoading) {
                    out.add(Row.status(catalogAvailable ? t.noOnlineMatch : t.noInternet));
                }
            } else if (filter == FILTER_ONLINE) {
                out.add(Row.status(t.noInternet));
            }
        }
        if (out.size() <= 2 && !catalogLoading) {
            out.add(Row.status(t.noThemes));
        }
        return out;
    }

    /** Filesystem-heavy — call off the UI thread when the catalog is large. */
    public static List<Row> buildOnlineRows(List<ThemeDownloader.CatalogEntry> catalog,
            List<ThemeManager.ThemeEntry> installed, int filter, int sort) {
        Set<String> installedFolders = new HashSet<String>();
        if (installed != null) {
            for (ThemeManager.ThemeEntry theme : installed) {
                if (theme.folderName != null) {
                    installedFolders.add(theme.folderName.toLowerCase(Locale.US));
                }
            }
        }
        return buildOnlineRows(catalog, installedFolders, filter, sort);
    }

    public static List<Row> buildOnlineRows(List<ThemeDownloader.CatalogEntry> catalog,
            Set<String> installedFolders, int filter, int sort) {
        List<Row> onlineRows = new ArrayList<Row>();
        if (catalog == null || filter == FILTER_INSTALLED || filter == FILTER_UPDATES) {
            return onlineRows;
        }
        for (ThemeDownloader.CatalogEntry e : catalog) {
            if (e.hasVariants()) {
                boolean anyOnline = false;
                for (ThemeDownloader.ThemeVariant v : e.variants) {
                    String folder = v.installFolder(e.name);
                    if (installedFolders.contains(folder.toLowerCase(Locale.US)) && v.isComplete(e.name)) {
                        continue;
                    }
                    anyOnline = true;
                    break;
                }
                if (!anyOnline && e.isComplete()) continue;
                String prefix = e.isComplete() ? "✔ " : (e.isInstalled() ? "↻ " : "↓ ");
                onlineRows.add(new Row(KIND_CATALOG,
                        e.name + " (" + e.variants.size() + " variants)",
                        e.author, prefix, -1, e, null, false, e.isInstalled() && !e.isComplete()));
            } else {
                String folder = ThemeDownloader.resolvedInstallFolder(e, null);
                if (folder != null && installedFolders.contains(folder.toLowerCase(Locale.US))
                        && e.isComplete()) {
                    continue;
                }
                if (filter == FILTER_ONLINE && e.isComplete()) continue;
                String prefix = e.isComplete() ? "✔ " : (e.isInstalled() ? "↻ " : "↓ ");
                onlineRows.add(new Row(KIND_CATALOG, e.name, e.author, prefix, -1, e, null,
                        false, e.isInstalled() && !e.isComplete()));
            }
        }
        sortRows(onlineRows, sort);
        return onlineRows;
    }

    private static void sortRows(List<Row> rows, int sort) {
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (sort == SORT_AUTHOR) {
                    int c = a.subtitle.compareToIgnoreCase(b.subtitle);
                    if (c != 0) return c;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });
    }

    public static String filterLabel(int filter) {
        switch (filter) {
            case FILTER_INSTALLED: return "Installed only";
            case FILTER_ONLINE: return "Online only";
            case FILTER_UPDATES: return "Updates available";
            default: return "All themes";
        }
    }

    public static String sortLabel(int sort) {
        return sort == SORT_AUTHOR ? "Sort: Author" : "Sort: Name";
    }

    public static int nextFilter(int filter) {
        return (filter + 1) % 4;
    }

    public static int toggleSort(int sort) {
        return sort == SORT_NAME ? SORT_AUTHOR : SORT_NAME;
    }

    /** Main themes screen: back + installed only; optional Get More row when online. */
    public static List<Row> buildInstalledRows(List<ThemeManager.ThemeEntry> installed,
            int currentThemeIndex, boolean online, int pageCapacityRows, UiText text) {
        UiText t = text != null ? text : new UiText();
        List<Row> out = new ArrayList<Row>();
        out.add(Row.back());

        List<Row> installedRows = new ArrayList<Row>();
        if (installed != null) {
            for (int i = 0; i < installed.size(); i++) {
                ThemeManager.ThemeEntry theme = installed.get(i);
                if (theme.folderName == null) continue;
                String author = installedAuthor(theme);
                String prefix = (i == currentThemeIndex) ? "✔ " : "   ";
                installedRows.add(new Row(KIND_INSTALLED, theme.name, author, prefix, i, null, null,
                        i == currentThemeIndex, false));
            }
        }
        sortRows(installedRows, SORT_NAME);

        boolean showGetMore = online;
        // ponytail: Get More at top only when Back + all installed rows overflow one page
        boolean installedOverflowsPage = 1 + installedRows.size() > pageCapacityRows;
        Row getMore = showGetMore ? new Row(KIND_GET_MORE, t.getMore, "", "→ ", -1, null, null, false, false) : null;

        if (showGetMore && getMore != null && installedOverflowsPage) out.add(getMore);
        if (!installedRows.isEmpty()) {
            out.addAll(installedRows);
        } else {
            out.add(Row.status(t.noInstalledMatch));
        }
        if (showGetMore && getMore != null && !installedOverflowsPage) out.add(getMore);
        return out;
    }

    /** Nested Get More screen: back + filter + online catalog rows. */
    public static List<Row> buildGetMoreRows(List<ThemeDownloader.CatalogEntry> catalog,
            List<ThemeManager.ThemeEntry> installed,
            int filter, int sort,
            boolean catalogLoading, boolean catalogAvailable, String catalogError,
            List<Row> onlineRowsPrebuilt, UiText text) {
        UiText t = text != null ? text : new UiText();
        List<Row> out = new ArrayList<Row>();
        out.add(Row.back());
        out.add(Row.filter(t.filterTitle, t.sortSubtitle));

        boolean catalogReady = catalog != null && catalogAvailable && !catalogLoading;
        List<Row> onlineRows = onlineRowsPrebuilt;
        if (onlineRows == null && catalogReady
                && filter != FILTER_INSTALLED && filter != FILTER_UPDATES) {
            onlineRows = buildOnlineRows(catalog, installed, filter, sort);
        }

        if (catalogLoading && (onlineRows == null || onlineRows.isEmpty())) {
            out.add(Row.status(t.loading));
        } else if (catalogError != null && !catalogError.isEmpty()
                && (onlineRows == null || onlineRows.isEmpty())) {
            out.add(Row.status(catalogError));
        } else if (onlineRows != null && !onlineRows.isEmpty()) {
            out.add(Row.section(t.onlineSection));
            out.addAll(onlineRows);
        } else if (!catalogLoading) {
            out.add(Row.status(catalogAvailable ? t.noOnlineMatch : t.noInternet));
        }
        if (out.size() <= 2 && !catalogLoading) {
            out.add(Row.status(t.noThemes));
        }
        return out;
    }
}
