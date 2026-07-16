package com.solar.launcher.podcast;

import com.solar.launcher.net.SolarHttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Saved podcast episodes on MicroSD: /storage/sdcard0/Podcasts/{show}/{episode}.ext */
public final class PodcastLibrary {
    public static final File ROOT = new File("/storage/sdcard0/Podcasts");

    private PodcastLibrary() {}

    public static File destFile(String showTitle, String episodeTitle, String audioUrl) {
        File showDir = new File(ROOT, sanitize(showTitle, 60));
        String ext = extensionFromUrl(audioUrl);
        String base = sanitize(episodeTitle, 80);
        if (base.isEmpty()) base = "episode_" + Integer.toHexString(audioUrl.hashCode());
        return new File(showDir, base + ext);
    }

    public static File findSaved(String showTitle, String episodeTitle, String audioUrl) {
        File f = destFile(showTitle, episodeTitle, audioUrl);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    private static final String[] AUDIO_EXT = {".mp3", ".m4a", ".ogg", ".opus", ".aac", ".wav", ".flac"};

    public static boolean isAudioFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String ext : AUDIO_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** Any show folder under {@link #ROOT} with at least one audio file. */
    public static boolean hasSavedContent() {
        if (savedContentTestOverride != null) return savedContentTestOverride.booleanValue();
        return !listSavedShows().isEmpty();
    }

    /** ponytail: unit tests only — null restores live scan. */
    static Boolean savedContentTestOverride = null;

    public static List<String> listSavedShows() {
        List<String> out = new ArrayList<String>();
        if (!ROOT.isDirectory()) return out;
        File[] dirs = ROOT.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (d.isDirectory() && hasAudioFiles(d)) out.add(d.getName());
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static List<File> listSavedEpisodes(String showFolderName) {
        List<File> out = new ArrayList<File>();
        if (showFolderName == null) return out;
        File dir = new File(ROOT, showFolderName);
        if (!dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && isAudioFileName(f.getName())) out.add(f);
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

    public static OpenRssClient.Episode episodeFromSavedFile(File file, String showFolderName) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
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

    private static boolean hasAudioFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && isAudioFileName(f.getName())) return true;
        }
        return false;
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
                SolarHttp.downloadToFile(tryUrl, tmp, progress);
                if (dest.isFile()) dest.delete();
                if (!tmp.renameTo(dest)) {
                    copyFile(tmp, dest);
                    tmp.delete();
                }
                return;
            } catch (Exception e) {
                last = e;
                if (tmp.isFile()) tmp.delete();
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
            String ext = path.substring(dot).toLowerCase();
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

    /** ponytail: filename + url-variant sanity only */
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
    }
}
