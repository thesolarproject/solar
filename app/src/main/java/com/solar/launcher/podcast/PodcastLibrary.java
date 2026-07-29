package com.solar.launcher.podcast;

import com.solar.launcher.AudioTagWriter;
import com.solar.launcher.AudioTags;
import com.solar.launcher.net.SolarHttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Saved podcast episodes under Podcasts/ on each user volume.
 * 2026-07-15 — Index by ID3 album/title when present; folder/filename fall-open.
 * Layman: files you copied in still show under the right show name if tags exist.
 * Technical: recursive walk of getPodcastRoots; MediaMetadataRetriever via AudioTags.
 * Reversal: prior code listed only first-level show dirs and used basename only.
 */
public final class PodcastLibrary {
    /** Default save root when no Context — primary volume (legacy alias). */
    public static final File ROOT = new File(com.solar.launcher.DeviceFeatures.getPrimaryStorageRoot(), "Podcasts");

    /** 2026-07-15 — Loose files with no tags land here in the saved UI. */
    public static final String UNKNOWN_SHOW = "Unknown Show";

    private PodcastLibrary() {}

    /** All Podcasts/ folders across volumes — never empty. */
    public static List<File> getPodcastRootsSafe() {
        if (rootsOverrideForTest != null) return rootsOverrideForTest;
        List<File> roots = com.solar.launcher.DeviceFeatures.getPodcastRoots();
        if (roots == null || roots.isEmpty()) {
            roots = new ArrayList<File>();
            roots.add(ROOT);
        }
        return roots;
    }

    /** Preferred-volume write path for a new download. */
    public static File destFile(String showTitle, String episodeTitle, String audioUrl) {
        return destFile(null, showTitle, episodeTitle, audioUrl);
    }

    /** Honor Primary storage pref for new downloads; scans still use all roots. */
    public static File destFile(android.content.Context ctx, String showTitle, String episodeTitle,
            String audioUrl) {
        File root = ctx != null
                ? new File(com.solar.launcher.DeviceFeatures.getNewMediaRoot(ctx), "Podcasts")
                : ROOT;
        File showDir = new File(root, sanitize(showTitle, 60));
        String ext = extensionFromUrl(audioUrl);
        String base = sanitize(episodeTitle, 80);
        if (base.isEmpty()) base = "episode_" + Integer.toHexString(audioUrl.hashCode());
        return new File(showDir, base + ext);
    }

    /**
     * Exact Solar layout under every Podcasts/ root (preferred-path sibling volumes).
     * Does not ID3-search — use list APIs for tag-diverged files.
     */
    public static File findSaved(String showTitle, String episodeTitle, String audioUrl) {
        for (File root : getPodcastRootsSafe()) {
            File showDir = new File(root, sanitize(showTitle, 60));
            String ext = extensionFromUrl(audioUrl);
            String base = sanitize(episodeTitle, 80);
            if (base.isEmpty()) base = "episode_" + Integer.toHexString(audioUrl.hashCode());
            File f = new File(showDir, base + ext);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    private static final String[] AUDIO_EXT = {".mp3", ".m4a", ".ogg", ".opus", ".aac", ".wav", ".flac"};

    public static boolean isAudioFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        for (String ext : AUDIO_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** Any show under Podcasts/ with at least one audio file. */
    public static boolean hasSavedContent() {
        if (savedContentTestOverride != null) return savedContentTestOverride.booleanValue();
        return !listSavedShows().isEmpty();
    }

    /** ponytail: unit tests only — null restores live scan. */
    static Boolean savedContentTestOverride = null;

    /** Unit tests — pin Podcasts roots to temp dirs. */
    static List<File> rootsOverrideForTest = null;

    /**
     * Unit tests — inject title/album/albumArtist/artist without MediaMetadataRetriever.
     * Map key = absolute path; value = AudioTags.Info.
     */
    static Map<String, AudioTags.Info> metaOverrideForTest = null;

    /** Distinct show names (ID3 album preferred), sorted case-insensitively. */
    public static List<String> listSavedShows() {
        Map<String, String> byLower = new HashMap<String, String>();
        for (SavedEpisode se : collectAllEpisodes()) {
            String key = se.showKey;
            if (key == null || key.isEmpty()) continue;
            String lower = key.toLowerCase(Locale.US);
            if (!byLower.containsKey(lower)) byLower.put(lower, key);
        }
        List<String> out = new ArrayList<String>(byLower.values());
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Audio files for one show key across all volumes (newest mtime first).
     * showKey matches {@link #listSavedShows()} entries (folder or ID3 album).
     */
    public static List<File> listSavedEpisodes(String showKey) {
        List<File> out = new ArrayList<File>();
        if (showKey == null) return out;
        String want = showKey.toLowerCase(Locale.US);
        Set<String> seen = new HashSet<String>();
        for (SavedEpisode se : collectAllEpisodes()) {
            if (!want.equals(se.showKey.toLowerCase(Locale.US))) continue;
            String dedupe = se.file.getName().toLowerCase(Locale.US);
            if (!seen.add(dedupe)) continue;
            out.add(se.file);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long da = a.lastModified();
                long db = b.lastModified();
                return da == db ? 0 : (da > db ? -1 : 1);
            }
        });
        return out;
    }

    /** Episode title from ID3 when present, else filename without extension. */
    public static OpenRssClient.Episode episodeFromSavedFile(File file, String showFolderName) {
        String title = episodeTitleFor(file);
        return new OpenRssClient.Episode(title, "file://" + file.getAbsolutePath(), "");
    }

    public static List<OpenRssClient.Episode> episodesFromSavedFiles(String showFolderName) {
        List<OpenRssClient.Episode> out = new ArrayList<OpenRssClient.Episode>();
        for (File f : listSavedEpisodes(showFolderName)) {
            out.add(episodeFromSavedFile(f, showFolderName));
        }
        return out;
    }

    public static File resolveEpisodeFile(OpenRssClient.Episode ep, String showTitle) {
        if (ep == null || ep.audioUrl == null) return null;
        if (ep.audioUrl.startsWith("file://")) {
            File f = new File(ep.audioUrl.substring(7));
            return f.isFile() && f.length() > 0 ? f : null;
        }
        if (showTitle != null) return findSaved(showTitle, ep.title, ep.audioUrl);
        return null;
    }

    /**
     * 2026-07-15 — Best-effort ID3 write after download so layout may diverge later.
     * Layman: stamps show + episode into the file itself.
     * Technical: AudioTagWriter title/album/artist; never throws to caller.
     * Reversal: skip call — path-only metadata again.
     */
    public static void tryEmbedSaveTags(File dest, String showTitle, String episodeTitle) {
        if (dest == null || !dest.isFile()) return;
        try {
            AudioTags.Info meta = new AudioTags.Info();
            String show = showTitle != null ? showTitle.trim() : "";
            String ep = episodeTitle != null ? episodeTitle.trim() : "";
            if (ep.isEmpty()) ep = titleFromFileName(dest.getName());
            if (show.isEmpty()) show = UNKNOWN_SHOW;
            meta.title = ep;
            meta.album = show;
            meta.artist = show;
            meta.albumArtist = show;
            AudioTagWriter.tryEmbed(dest, meta, null);
        } catch (Throwable ignored) {
            // Fail-open: keep the downloaded file even if embed crashes on API 17.
        }
    }

    /** Display title for a saved episode file (tags → basename). */
    public static String episodeTitleFor(File file) {
        if (file == null) return "";
        AudioTags.Info info = readMeta(file);
        if (info != null && info.title != null && !info.title.trim().isEmpty()) {
            return info.title.trim();
        }
        return titleFromFileName(file.getName());
    }

    /** Show label for a saved episode file (tags → first folder under Podcasts/ → Unknown). */
    public static String showKeyFor(File file, File podcastRoot) {
        AudioTags.Info info = readMeta(file);
        if (info != null) {
            if (info.album != null && !info.album.trim().isEmpty()) return info.album.trim();
            if (info.albumArtist != null && !info.albumArtist.trim().isEmpty()) {
                return info.albumArtist.trim();
            }
            if (info.artist != null && !info.artist.trim().isEmpty()) return info.artist.trim();
        }
        String folderShow = firstSegmentUnderRoot(file, podcastRoot);
        if (folderShow != null && !folderShow.isEmpty()) return folderShow;
        return UNKNOWN_SHOW;
    }

    private static List<SavedEpisode> collectAllEpisodes() {
        List<SavedEpisode> out = new ArrayList<SavedEpisode>();
        for (File root : getPodcastRootsSafe()) {
            if (root == null || !root.isDirectory()) continue;
            collectRecursive(root, root, out);
        }
        return out;
    }

    private static void collectRecursive(File podcastRoot, File dir, List<SavedEpisode> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (f.isDirectory()) {
                collectRecursive(podcastRoot, f, out);
            } else if (f.isFile() && f.length() > 0 && isAudioFileName(f.getName())) {
                SavedEpisode se = new SavedEpisode();
                se.file = f;
                se.showKey = showKeyFor(f, podcastRoot);
                out.add(se);
            }
        }
    }

    /** First directory name under Podcasts/ for path-based show identity. */
    static String firstSegmentUnderRoot(File file, File podcastRoot) {
        if (file == null || podcastRoot == null) return null;
        String rootPath = podcastRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (!path.startsWith(rootPath)) return null;
        String rel = path.substring(rootPath.length());
        while (rel.startsWith(File.separator)) rel = rel.substring(1);
        if (rel.isEmpty()) return null;
        int slash = rel.indexOf(File.separatorChar);
        if (slash < 0) {
            // File sits directly under Podcasts/ — no show folder.
            return null;
        }
        return rel.substring(0, slash);
    }

    private static AudioTags.Info readMeta(File file) {
        if (file == null) return new AudioTags.Info();
        if (metaOverrideForTest != null) {
            AudioTags.Info ov = metaOverrideForTest.get(file.getAbsolutePath());
            if (ov != null) return ov;
        }
        return AudioTags.read(file, null, AudioTags.READ_SKIP_EMBEDDED_ART);
    }

    static String titleFromFileName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class SavedEpisode {
        File file;
        String showKey;
    }

    public static void downloadTo(File dest, String urlStr, SolarHttp.DownloadProgress progress) throws Exception {
        if (dest == null) throw new IllegalArgumentException("dest");
        if (urlStr == null || urlStr.trim().isEmpty()) throw new IllegalArgumentException("url");
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Cannot create " + parent.getAbsolutePath());
        }
        File tmp = new File(dest.getAbsolutePath() + ".part");
        Exception last = null;
        for (String tryUrl : httpsThenHttpVariants(urlStr)) {
            try {
                long resumeFrom = tmp.isFile() ? tmp.length() : 0L;
                SolarHttp.downloadToFile(tryUrl, tmp, progress, 0L, null, null, resumeFrom);
                if (!tmp.isFile() || tmp.length() <= 0) {
                    throw new Exception("Podcast download is empty");
                }
                if (dest.exists()) throw new Exception("Podcast destination already exists");
                if (!tmp.renameTo(dest)) {
                    throw new Exception("Could not publish podcast; partial file was kept");
                }
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("Download failed");
    }

    static String sanitize(String name, int maxLen) {
        if (name == null) return "unknown";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        if (s.isEmpty()) return "unknown";
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        return s;
    }

    static String extensionFromUrl(String url) {
        if (url == null) return ".mp3";
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot > path.lastIndexOf('/')) {
            String ext = path.substring(dot).toLowerCase(Locale.US);
            if (ext.length() <= 5 && ext.matches("\\.[a-z0-9]+")) return ext;
        }
        return ".mp3";
    }

    public static String[] httpsThenHttpVariants(String url) {
        if (url == null) return new String[0];
        if (url.startsWith("https://")) return new String[] {url, "http://" + url.substring(8)};
        if (url.startsWith("http://")) return new String[] {"https://" + url.substring(7), url};
        return new String[] {url};
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /** Clear test hooks — call from @After. */
    static void resetTestHooks() {
        rootsOverrideForTest = null;
        metaOverrideForTest = null;
        savedContentTestOverride = null;
    }

    /** ponytail: filename + url-variant + multi-root index sanity. */
    public static void selfCheck() {
        File f = destFile("Show/Name", "Ep:1?", "https://x.test/a.mp3?token=1");
        if (!f.getParentFile().getName().equals("Show_Name")) {
            throw new AssertionError("show sanitize");
        }
        if (!f.getName().endsWith(".mp3")) throw new AssertionError("ext");
        String[] v = httpsThenHttpVariants("https://host/x");
        if (v.length != 2 || !v[1].startsWith("http://")) throw new AssertionError("variants");
        List<String> shows = listSavedShows();
        if (shows == null) throw new AssertionError("shows null");
        if (hasSavedContent() != !shows.isEmpty()) throw new AssertionError("hasSavedContent");
        if (firstSegmentUnderRoot(new File(ROOT, "MyShow/ep.mp3"), ROOT) == null
                || !"MyShow".equals(firstSegmentUnderRoot(new File(ROOT, "MyShow/ep.mp3"), ROOT))) {
            throw new AssertionError("firstSegment");
        }
    }
}
