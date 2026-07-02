package com.solar.launcher;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Write fetched metadata + cover into tag-capable audio files.
 * ponytail: WAV/APE/WMA skip — sidecar + SQLite remain the source of truth there.
 */
public final class AudioTagWriter {

    public enum EmbedResult {
        EMBEDDED,
        UNSUPPORTED_FORMAT,
        FAILED
    }

    private AudioTagWriter() {}

    /** Sidecar basename — strips known audio extensions from the file name. */
    public static String sidecarBaseName(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.US);
        String[] ext = {".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".ape", ".wma"};
        String base = fileName;
        for (String e : ext) {
            if (lower.endsWith(e)) {
                base = fileName.substring(0, fileName.length() - e.length());
                break;
            }
        }
        return base;
    }

    /** Gate before touching jaudiotagger — plain WAV has no reliable embed path. */
    public static boolean supportsEmbedding(File track) {
        if (track == null) return false;
        String name = track.getName().toLowerCase(Locale.US);
        if (name.endsWith(".wav") || name.endsWith(".ape") || name.endsWith(".wma")) return false;
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a")
                || name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".opus");
    }

    /** Test helper — UNSUPPORTED_FORMAT when {@link #supportsEmbedding} is false. */
    public static EmbedResult capabilityFor(File track) {
        return supportsEmbedding(track) ? EmbedResult.EMBEDDED : EmbedResult.UNSUPPORTED_FORMAT;
    }

    /** Embed title/artist/album and optional JPEG cover; atomic replace via temp file. */
    public static EmbedResult tryEmbed(File track, AudioTags.Info meta, byte[] coverJpeg) {
        if (!supportsEmbedding(track)) {
            return EmbedResult.UNSUPPORTED_FORMAT;
        }
        if (meta == null) return EmbedResult.FAILED;
        File temp = null;
        try {
            TagOptionSingleton.getInstance().setAndroid(true);
            temp = new File(track.getParentFile(),
                    track.getName() + ".solar-tag-" + System.currentTimeMillis());
            copyFile(track, temp);
            AudioFile audio = AudioFileIO.read(temp);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            if (meta.title != null && !meta.title.trim().isEmpty()) {
                tag.setField(FieldKey.TITLE, meta.title.trim());
            }
            if (meta.artist != null && !meta.artist.trim().isEmpty()) {
                tag.setField(FieldKey.ARTIST, meta.artist.trim());
            }
            if (meta.album != null && !meta.album.trim().isEmpty()) {
                tag.setField(FieldKey.ALBUM, meta.album.trim());
            }
            if (meta.albumArtist != null && !meta.albumArtist.trim().isEmpty()) {
                tag.setField(FieldKey.ALBUM_ARTIST, meta.albumArtist.trim());
            }
            if (meta.genre != null && !meta.genre.trim().isEmpty()) {
                tag.setField(FieldKey.GENRE, meta.genre.trim());
            }
            if (meta.trackNumber > 0) {
                tag.setField(FieldKey.TRACK, String.valueOf(meta.trackNumber));
            }
            if (coverJpeg != null && coverJpeg.length > 0) {
                Artwork art = ArtworkFactory.getNew();
                art.setBinaryData(coverJpeg);
                art.setMimeType("image/jpeg");
                tag.deleteArtworkField();
                tag.setField(art);
            }
            audio.commit();
            if (!track.delete()) return EmbedResult.FAILED;
            if (!temp.renameTo(track)) {
                copyFile(temp, track);
                return EmbedResult.FAILED;
            }
            temp = null;
            return EmbedResult.EMBEDDED;
        } catch (Exception ignored) {
            return EmbedResult.FAILED;
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }
}
