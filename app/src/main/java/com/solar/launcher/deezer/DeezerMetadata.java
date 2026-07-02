package com.solar.launcher.deezer;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.solar.launcher.net.TlsHelper;

/** Persists Deezer API metadata for temp and saved files (keyed by absolute path). */
public final class DeezerMetadata {
    private static final String KEY_TITLE = "meta_title_";
    private static final String KEY_ARTIST = "meta_artist_";
    private static final String KEY_ALBUM = "meta_album_";
    private static final String KEY_COVER_URL = "meta_cover_url_";

    private DeezerMetadata() {}

    public static void saveForResult(Context ctx, File file, DeezerResult r) {
        if (ctx == null || file == null || r == null) return;
        saveMerged(ctx, file, r.title, r.artist, r.album, r.coverUrl);
    }

    public static void saveForTrackData(Context ctx, File file, DeezerTrackData track) {
        if (ctx == null || file == null || track == null) return;
        saveMerged(ctx, file, track.title, track.artist, track.album, track.albumPicture);
    }

    /** After download: merge scraped track fields with search result (non-empty wins). */
    public static void saveForTrackComplete(Context ctx, File file, DeezerResult result,
            DeezerTrackData track) {
        if (ctx == null || file == null) return;
        String title = pick(result != null ? result.title : "", track != null ? track.title : "");
        String artist = pick(result != null ? result.artist : "", track != null ? track.artist : "");
        String album = pick(result != null ? result.album : "", track != null ? track.album : "");
        String cover = "";
        if (track != null && track.albumPicture != null && !track.albumPicture.isEmpty()) {
            cover = track.albumPicture;
        } else if (result != null && result.coverUrl != null) {
            cover = result.coverUrl;
        }
        saveMerged(ctx, file, title, artist, album, cover);
    }

    private static void saveMerged(Context ctx, File file, String title, String artist,
            String album, String coverOrHash) {
        SharedPreferences prefs = prefs(ctx);
        String path = file.getAbsolutePath();
        String existingCover = prefs.getString(KEY_COVER_URL + path, "");
        String cover = DeezerCoverArt.bestCoverUrl(coverOrHash);
        if (cover.isEmpty()) cover = DeezerCoverArt.bestCoverUrl(existingCover);
        prefs.edit()
                .putString(KEY_TITLE + path, title != null ? title : "")
                .putString(KEY_ARTIST + path, artist != null ? artist : "")
                .putString(KEY_ALBUM + path, album != null ? album : "")
                .putString(KEY_COVER_URL + path, cover)
                .commit();
    }

    private static String pick(String fromResult, String fromTrack) {
        if (fromTrack != null && !fromTrack.trim().isEmpty()) return fromTrack.trim();
        if (fromResult != null && !fromResult.trim().isEmpty()) return fromResult.trim();
        return "";
    }

    /** Overlay Deezer prefs onto ID3-derived strings for library / browse. */
    public static void overlayFromPrefs(SharedPreferences prefs, String absolutePath,
            String[] titleArtistAlbum) {
        if (prefs == null || absolutePath == null || titleArtistAlbum == null
                || titleArtistAlbum.length < 3) return;
        if (!hasMetadata(prefs, absolutePath)) return;
        String t = title(prefs, absolutePath, "");
        String a = artist(prefs, absolutePath, "");
        String al = album(prefs, absolutePath, "");
        if (!t.isEmpty()) titleArtistAlbum[0] = t;
        if (!a.isEmpty()) titleArtistAlbum[1] = a;
        if (!al.isEmpty()) titleArtistAlbum[2] = al;
    }

    public static boolean hasMetadata(SharedPreferences prefs, String absolutePath) {
        if (prefs == null || absolutePath == null) return false;
        return prefs.contains(KEY_TITLE + absolutePath)
                || prefs.contains(KEY_ARTIST + absolutePath);
    }

    public static String title(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_TITLE + path, fallback);
    }

    public static String artist(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_ARTIST + path, fallback);
    }

    public static String album(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_ALBUM + path, fallback);
    }

    public static String coverUrl(SharedPreferences prefs, String path) {
        if (prefs == null || path == null) return "";
        return DeezerCoverArt.bestCoverUrl(prefs.getString(KEY_COVER_URL + path, ""));
    }

    /** Drop prefs overlay after tags are embedded in the audio file. */
    public static void clearOverlay(Context ctx, String absolutePath) {
        if (ctx == null || absolutePath == null) return;
        SharedPreferences prefs = prefs(ctx);
        prefs.edit()
                .remove(KEY_TITLE + absolutePath)
                .remove(KEY_ARTIST + absolutePath)
                .remove(KEY_ALBUM + absolutePath)
                .remove(KEY_COVER_URL + absolutePath)
                .commit();
    }

    /** Cache high-res cover next to other album art for library + player. */
    public static void prefetchCoverFile(SharedPreferences prefs, File track, File coverFile) {
        if (prefs == null || track == null || coverFile == null) return;
        String url = coverUrl(prefs, track.getAbsolutePath());
        if (url.isEmpty()) return;
        if (coverFile.isFile() && coverFile.length() > 4096) return;
        try {
            File parent = coverFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            TlsHelper.ensureSecurityProvider();
            HttpURLConnection conn = (HttpURLConnection) new URL(url.replace("https://", "http://"))
                    .openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", DeezerClient.USER_AGENT);
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(coverFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            in.close();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(DeezerAccount.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
