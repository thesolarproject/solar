package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LRCGET-in-Solar: matches library tracks to LRCLIB lyrics and writes .lrc sidecars.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #kickOffLibraryMatch} — background pass after each library scan. Walks the
 *       cached library (no MediaMetadataRetriever), skips tracks that already have sidecar
 *       lyrics or were attempted before, and fetches a capped batch. Remembered "attempted"
 *       paths keep LRCLIB fair-use (never re-query the same track on every scan).</li>
 *   <li>{@link #fetchTrack} — on-demand fetch for one file (Now Playing → Lyrics with no
 *       lyrics present). Resolves rich metadata via {@link AudioTags} (incl. Deezer overlays)
 *       and bypasses the attempted-memory so a user can always retry.</li>
 * </ul>
 *
 * <p>2026-08-05 — Reimplements LRCGET's matching powers inside Solar against the same LRCLIB
 * service; downloaded files land as {@code <track>.lrc} next to the audio (the sidecar
 * {@link TrackLyrics} already reads).</p>
 */
public final class LyricsLibraryMatcher {
    private static final String PREF_ATTEMPTED = "lrcget_attempted_paths";

    /** Max tracks attempted per background run — slow MTK devices + LRCLIB fair use. */
    private static final int MAX_PER_RUN = 50;
    /** Delay between background requests (LRCLIB asks clients to throttle). */
    private static final long REQUEST_DELAY_MS = 250L;
    /** Stop the run after this many consecutive network failures (offline wall). */
    private static final int MAX_CONSECUTIVE_FAILURES = 4;

    private static volatile boolean libraryMatchRunning = false;
    /** Dedupe concurrent on-demand fetches for the same file. */
    private static volatile String fetchInProgressPath;

    // fetchTrack results
    public static final int RESULT_FETCHED = 1;
    public static final int RESULT_NONE = 2;
    public static final int RESULT_INSTRUMENTAL = 3;
    public static final int RESULT_OFFLINE = 4;
    public static final int RESULT_IN_PROGRESS = 5;
    public static final int RESULT_NO_METADATA = 6;

    private LyricsLibraryMatcher() {}

    /** Start the post-scan background pass once; no-op when one is already running. */
    public static synchronized void kickOffLibraryMatch(final Context ctx, final SharedPreferences prefs) {
        if (ctx == null || libraryMatchRunning) return;
        final Context app = ctx.getApplicationContext();
        final SharedPreferences p = prefs != null ? prefs
                : app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        libraryMatchRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int attempted = 0;
                int consecutiveFailures = 0;
                try {
                    if (!ConnectivityHelper.isOnline(app)) return;
                    MusicLibraryStore store = MusicLibraryStore.getInstance(app);
                    // Paged walk (SEGMENTED discipline) — never materialize the whole library
                    // for at most MAX_PER_RUN lookups.
                    final int page = 64;
                    int total = store.countTracks();
                    for (int offset = 0; offset < total && attempted < MAX_PER_RUN; offset += page) {
                        List<MusicLibraryStore.Track> tracks = store.loadRangeByMtimeDesc(offset, page);
                        if (tracks == null || tracks.isEmpty()) break;
                        for (MusicLibraryStore.Track t : tracks) {
                            if (attempted >= MAX_PER_RUN) break;
                            if (t == null || t.path == null || t.path.isEmpty()) continue;
                            File f = new File(t.path);
                            if (!f.isFile()) continue;
                            if (TrackLyrics.hasSidecar(f)) continue;
                            if (t.path.equals(fetchInProgressPath)) continue;
                            if (wasAttempted(p, t.path)) continue;
                            // DB rows carry scan-time tags — no MediaMetadataRetriever needed here.
                            if (t.title == null || t.title.trim().isEmpty()
                                    || t.artist == null || t.artist.trim().isEmpty()
                                    || "Unknown Artist".equalsIgnoreCase(t.artist.trim())) {
                                // Not blacklisted: re-tags + rescan should allow a later attempt.
                                attempted++;
                                continue;
                            }
                            int result = fetchWithMetadata(p, f, t.title, t.artist, t.album, t.durationSec());
                            if (result == RESULT_OFFLINE) {
                                consecutiveFailures++;
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break;
                            } else {
                                consecutiveFailures = 0;
                            }
                            attempted++;
                            try {
                                Thread.sleep(REQUEST_DELAY_MS);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Background matcher never crashes the app.
                } finally {
                    libraryMatchRunning = false;
                }
            }
        }).start();
    }

    /**
     * On-demand fetch for one audio file. Resolves metadata via {@link AudioTags} (honors
     * Deezer/prefs overlays) and writes the sidecar when LRCLIB has lyrics.
     * Safe on any thread; UI callers should post back for toasts.
     */
    public static int fetchTrack(Context ctx, SharedPreferences prefs, File audio) {
        if (ctx == null || audio == null || !audio.isFile()) return RESULT_NO_METADATA;
        String path = audio.getAbsolutePath();
        if (TrackLyrics.hasSidecar(audio)) return RESULT_FETCHED;
        if (path.equals(fetchInProgressPath)) return RESULT_IN_PROGRESS;
        if (!ConnectivityHelper.isOnline(ctx)) return RESULT_OFFLINE;

        AudioTags.Info tags = AudioTags.read(audio, prefs);
        String title = tags.title != null ? tags.title.trim() : "";
        String artist = tags.artist != null ? tags.artist.trim() : "";
        if (title.isEmpty() || artist.isEmpty()
                || "Unknown Artist".equalsIgnoreCase(artist)) {
            return RESULT_NO_METADATA;
        }
        int durationSec = MusicLibraryStore.parseDurationMs(tags.durationMs) / 1000;
        fetchInProgressPath = path;
        try {
            return fetchWithMetadata(prefs, audio, title, artist, tags.album, durationSec);
        } finally {
            if (path.equals(fetchInProgressPath)) fetchInProgressPath = null;
        }
    }

    /** Fetch + persist for known metadata; the shared core for background + on-demand. */
    private static int fetchWithMetadata(SharedPreferences prefs, File audio,
            String title, String artist, String album, int durationSec) {
        String path = audio.getAbsolutePath();
        try {
            LrcGetClient.Match match = LrcGetClient.fetch(title, artist, album, durationSec);
            if (match == null) {
                markAttempted(prefs, path);
                return RESULT_NONE;
            }
            if (match.instrumental) {
                markAttempted(prefs, path);
                return RESULT_INSTRUMENTAL;
            }
            String text = LrcGetClient.bestLyricsText(match);
            if (text == null || text.trim().isEmpty()) {
                markAttempted(prefs, path);
                return RESULT_NONE;
            }
            if (writeSidecar(audio, text) != null) {
                markAttempted(prefs, path);
                return RESULT_FETCHED;
            }
            return RESULT_NONE;
        } catch (Exception e) {
            // Network/TLS failure — do NOT mark attempted (retry next scan when online).
            return RESULT_OFFLINE;
        }
    }

    /** Write {@code <track>.lrc} next to the audio (tmp + rename, mirrors PodcastLibrary). */
    public static File writeSidecar(File audio, String content) {
        if (audio == null || !audio.isFile() || content == null) return null;
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = audio.getParentFile();
        if (parent == null || !parent.isDirectory()) return null;
        File dest = new File(parent, base + ".lrc");
        File tmp = new File(parent, base + ".lrc.part");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            if (tmp.isFile()) tmp.delete();
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
        if (dest.isFile()) dest.delete();
        if (!tmp.renameTo(dest)) {
            // rename can fail across virtual mounts — fall back to copy.
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                out = new java.io.FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.close();
                tmp.delete();
            } catch (Exception e) {
                if (dest.isFile()) dest.delete();
                if (tmp.isFile()) tmp.delete();
                return null;
            }
        }
        return dest;
    }

    static boolean wasAttempted(SharedPreferences prefs, String path) {
        if (prefs == null || path == null || path.isEmpty()) return false;
        Set<String> attempted = prefs.getStringSet(PREF_ATTEMPTED, null);
        return attempted != null && attempted.contains(path);
    }

    static void markAttempted(SharedPreferences prefs, String path) {
        if (prefs == null || path == null || path.isEmpty()) return;
        Set<String> attempted = new HashSet<String>(
                prefs.getStringSet(PREF_ATTEMPTED, new HashSet<String>()));
        attempted.add(path);
        prefs.edit().putStringSet(PREF_ATTEMPTED, attempted).apply();
    }
}
