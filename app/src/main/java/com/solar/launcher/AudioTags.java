package com.solar.launcher;

import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import com.solar.launcher.deezer.DeezerMetadata;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/** Read MP3/FLAC tags for library + player; prefs overlay and album-artist fallback. */
public final class AudioTags {
    public static final class Info {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String albumArtist = "";
        public String genre = "";
        public String durationMs = "";
        public byte[] embeddedArt;
        /** Debug: id3 | prefs | filename | albumArtist */
        public String artistSource = "";
    }

    private AudioTags() {}

    /** Read embedded tags, overlay saved fetch/Deezer metadata, resolve display artist. */
    public static Info read(File file, SharedPreferences prefs) {
        Info info = new Info();
        if (file == null || !file.isFile()) return info;

        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            // ponytail: path-based setDataSource reads ID3 more reliably than FileDescriptor on API 17.
            mmr.setDataSource(file.getAbsolutePath());
            info.title = safe(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            info.artist = safe(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            info.album = safe(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            info.genre = safe(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            info.durationMs = safe(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (Build.VERSION.SDK_INT >= 19) {
                info.albumArtist = safe(mmr.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            }
            info.embeddedArt = mmr.getEmbeddedPicture();
        } catch (Exception ignored) {
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Exception ignored) {}
            }
        }

        String path = file.getAbsolutePath();
        if (prefs != null && (DeezerMetadata.hasMetadata(prefs, path)
                || prefs.contains("meta_title_" + path)
                || prefs.contains("meta_artist_" + path))) {
            info.title = DeezerMetadata.title(prefs, path, info.title);
            info.artist = DeezerMetadata.artist(prefs, path, info.artist);
            info.album = DeezerMetadata.album(prefs, path, info.album);
        }

        if (info.title.isEmpty()) {
            info.title = titleFromFileName(file.getName());
        }
        resolveArtist(info, file.getName());

        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("file", file.getName());
            d.put("title", info.title);
            d.put("artist", info.artist);
            d.put("album", info.album);
            d.put("albumArtist", info.albumArtist);
            d.put("artistSource", info.artistSource);
            d.put("hasArt", info.embeddedArt != null && info.embeddedArt.length > 0);
            d.put("hasValidTags", hasValidTags(info));
            DebugAgentLog.log(null, "AudioTags.read", "tags resolved", "H-A", d);
        } catch (Exception ignored) {}
        // #endregion

        return info;
    }

    /** True when title and artist are usable for display and Deezer search. */
    public static boolean hasValidTags(Info info) {
        if (info == null) return false;
        String t = info.title != null ? info.title.trim() : "";
        String a = info.artist != null ? info.artist.trim() : "";
        return !t.isEmpty() && !a.isEmpty() && !"Unknown Artist".equalsIgnoreCase(a);
    }

    /** Fill empty artist from album artist, then "Artist - Title" filename patterns. */
    static void resolveArtist(Info info, String fileName) {
        if (info == null) return;
        if (!info.artist.isEmpty() && !isUnknownArtist(info.artist)) {
            info.artistSource = "id3";
            return;
        }
        if (!info.albumArtist.isEmpty() && !isUnknownArtist(info.albumArtist)) {
            info.artist = info.albumArtist;
            info.artistSource = "albumArtist";
            return;
        }
        ParsedName parsed = parseArtistTitleFromFileName(fileName);
        if (!parsed.artist.isEmpty()) {
            if (info.title.isEmpty() && !parsed.title.isEmpty()) info.title = parsed.title;
            info.artist = parsed.artist;
            info.artistSource = "filename";
        }
    }

    static ParsedName parseArtistTitleFromFileName(String fileName) {
        ParsedName out = new ParsedName();
        if (fileName == null) return out;
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replace('_', ' ').trim();
        // Strip leading track number: "01 - " or "01."
        base = base.replaceAll("^[0-9]+[\\s._-]+", "").trim();
        int sep = base.indexOf(" - ");
        if (sep > 0) {
            out.artist = base.substring(0, sep).trim();
            out.title = base.substring(sep + 3).trim();
        }
        return out;
    }

    static String titleFromFileName(String fileName) {
        ParsedName p = parseArtistTitleFromFileName(fileName);
        if (!p.title.isEmpty()) return p.title;
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static boolean isUnknownArtist(String s) {
        return s == null || s.trim().isEmpty()
                || "Unknown Artist".equalsIgnoreCase(s.trim());
    }

    private static String safe(String s) {
        return s != null ? s.trim() : "";
    }

    static final class ParsedName {
        String artist = "";
        String title = "";
    }
}
