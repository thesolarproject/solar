package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.net.SolarHttp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Artist names and separator rules that do not fit generic heuristics.
 * Bundled in assets; refreshed from solar-update GitHub Pages when online.
 */
public final class ArtistSeparatorCatalog {
    public static final String REMOTE_URL =
            "https://thesolarproject.github.io/solar-update/artist-separators.csv";

    private static final long REFRESH_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String PREFS = "artist_separator_catalog";
    private static final String PREF_LAST_FETCH = "last_fetch_ms";
    private static final String CACHE_FILE = "artist-separators.csv";
    private static final String ASSET_FILE = "artist-separators.csv";

    private static volatile ArtistSeparatorCatalog active;
    private static volatile boolean refreshInFlight;

    private final int version;
    private final boolean splitAmpersand;
    private final Set<String> noSplitKeys;

    private ArtistSeparatorCatalog(int version, boolean splitAmpersand, Set<String> noSplitKeys) {
        this.version = version;
        this.splitAmpersand = splitAmpersand;
        this.noSplitKeys = noSplitKeys == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(noSplitKeys);
    }

    public static ArtistSeparatorCatalog get() {
        ArtistSeparatorCatalog c = active;
        if (c != null) return c;
        synchronized (ArtistSeparatorCatalog.class) {
            if (active == null) {
                try {
                    active = parse("version,split_ampersand\n1,true\n\"AC/DC\"");
                } catch (Exception e) {
                    active = new ArtistSeparatorCatalog(1, true,
                            Collections.singleton("ac/dc"));
                }
            }
            return active;
        }
    }

    public boolean splitAmpersand() {
        return splitAmpersand;
    }

    public boolean isNoSplit(String raw) {
        if (raw == null) return false;
        String key = normalizeKey(raw);
        return !key.isEmpty() && noSplitKeys.contains(key);
    }

    /** Load bundled assets (and disk cache if present). Safe on main thread. */
    public static void ensureLoaded(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        File cache = cacheFile(app);
        if (cache.isFile() && cache.length() > 0) {
            try {
                install(parse(readUtf8(new FileInputStream(cache))));
                return;
            } catch (Exception ignored) {}
        }
        try {
            InputStream in = app.getAssets().open(ASSET_FILE);
            try {
                install(parse(readUtf8(in)));
            } finally {
                in.close();
            }
        } catch (Exception ignored) {}
    }

    /** Background refresh when stale and online — e.g. during album art lookup. */
    public static void maybeRefreshAsync(final Context ctx) {
        if (ctx == null) return;
        synchronized (ArtistSeparatorCatalog.class) {
            if (refreshInFlight) return;
            refreshInFlight = true;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    maybeRefresh(app);
                } finally {
                    synchronized (ArtistSeparatorCatalog.class) {
                        refreshInFlight = false;
                    }
                }
            }
        }).start();
    }

    static void maybeRefresh(Context app) {
        ensureLoaded(app);
        if (!ConnectivityHelper.isOnline(app)) return;
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(PREF_LAST_FETCH, 0L);
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) return;
        try {
            String csv = SolarHttp.getText(REMOTE_URL);
            ArtistSeparatorCatalog parsed = parse(csv);
            install(parsed);
            File cache = cacheFile(app);
            File parent = cache.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(cache);
            try {
                fos.write(csv.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            prefs.edit().putLong(PREF_LAST_FETCH, System.currentTimeMillis()).commit();
        } catch (Exception ignored) {}
    }

    static ArtistSeparatorCatalog parse(String csv) throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(csv));
        String header = reader.readLine();
        if (header == null || !header.trim().equalsIgnoreCase("version,split_ampersand")) {
            throw new IllegalArgumentException("invalid header");
        }
        String meta = reader.readLine();
        if (meta == null) throw new IllegalArgumentException("missing version line");
        String[] metaParts = meta.split(",", 2);
        int version = Integer.parseInt(metaParts[0].trim());
        boolean splitAmp = metaParts.length > 1 && Boolean.parseBoolean(metaParts[1].trim());
        Set<String> noSplit = new HashSet<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            String name = parseQuotedCsvField(line);
            if (name != null && !name.isEmpty()) {
                noSplit.add(normalizeKey(name));
            }
        }
        return new ArtistSeparatorCatalog(version, splitAmp, noSplit);
    }

    /** Parse a single quoted CSV field (one artist name per line). */
    static String parseQuotedCsvField(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;
        if (t.charAt(0) != '"') return t;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') {
                if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    static void install(ArtistSeparatorCatalog catalog) {
        if (catalog == null) return;
        active = catalog;
    }

    static void resetForTests() {
        active = null;
        refreshInFlight = false;
    }

    private static File cacheFile(Context app) {
        return new File(app.getFilesDir(), CACHE_FILE);
    }

    private static String normalizeKey(String raw) {
        return raw.trim().toLowerCase(Locale.US);
    }

    private static String readUtf8(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }
}
