package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.solar.launcher.AlbumCoverPipeline;
import com.solar.launcher.AlbumNames;
import com.solar.launcher.ArtistNames;
import com.solar.launcher.AudioTags;
import com.solar.launcher.deezer.DeezerMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/** Resolve album / artist / playlist / podcast cover bitmaps for Flow. */
public final class FlowCoverResolver {

    public interface Host {
        File coverFileForTrack(File track);
        File getCoversFolder();
        /** Internal {@code filesDir/Solar_AlbumArt} — 240px ingest cache. */
        File getAlbumArtCacheDir();
        /** Internal {@code filesDir/Solar_FlowThumb} — exact carousel thumb JPEGs. */
        File getFlowThumbCacheDir();
        android.content.SharedPreferences prefs();
        Typeface labelFont();
    }

    private FlowCoverResolver() {}

    /**
     * Synchronous disk-cache hit only — FlowThumb JPEG, then 240px AlbumArt.
     * Rockbox .pfraw: read display-size slide on UI thread; no decode queue for hits.
     */
    public static Bitmap resolveDiskCached(FlowItem item, Host host, int thumbPx) {
        if (item == null || host == null) return null;
        switch (item.kind) {
            case ALBUM:
            case PLAYLIST: {
                File flowDir = host.getFlowThumbCacheDir();
                if (flowDir != null && item.coverKey != null) {
                    File flowFile = FlowThumbCache.fileForKey(flowDir, item.coverKey, thumbPx);
                    if (flowFile != null && flowFile.isFile()
                            && AlbumArtCache.isLikelyPlaceholderFile(flowFile)) {
                        flowFile.delete();
                    } else {
                        Bitmap flowThumb = FlowThumbCache.get(flowDir, item.coverKey, thumbPx);
                        if (flowThumb != null) return flowThumb;
                    }
                }
                File artDir = host.getAlbumArtCacheDir();
                if (artDir != null && item.coverKey != null) {
                    File artFile = AlbumArtCache.fileForKey(artDir, item.coverKey);
                    if (artFile != null && artFile.isFile()
                            && AlbumArtCache.isLikelyPlaceholderFile(artFile)) {
                        artFile.delete();
                    } else {
                        Bitmap disk = AlbumArtCache.get(artDir, item.coverKey);
                        if (disk != null) {
                            Bitmap scaled = scale(disk, thumbPx);
                            maybeCacheFlowThumb(flowDir, item.coverKey, scaled, thumbPx);
                            return scaled;
                        }
                    }
                }
                return null;
            }
            case ARTIST: {
                Bitmap cached = ArtistImageCache.get(host.getCoversFolder(), item.coverKey);
                if (cached != null) return scale(cached, thumbPx);
                return null;
            }
            default:
                return null;
        }
    }

    public static Bitmap resolve(FlowItem item, Host host, int thumbPx) {
        if (item == null || host == null) return placeholder(thumbPx, "?", 0xFF444444);
        switch (item.kind) {
            case ALBUM:
            case PLAYLIST:
                Bitmap diskHit = resolveDiskCached(item, host, thumbPx);
                if (diskHit != null) return diskHit;
                File artDir = host.getAlbumArtCacheDir();
                File flowDir = host.getFlowThumbCacheDir();
                return resolveFromTracks(item.tracks, host, thumbPx, item.coverKey, artDir, flowDir);
            case ARTIST:
                Bitmap cached = ArtistImageCache.get(host.getCoversFolder(), item.coverKey);
                if (cached != null) return scale(cached, thumbPx);
                return artistInitials(item.title, thumbPx, host.labelFont());
            case PODCAST:
                if (item.podcastArtUrl != null && !item.podcastArtUrl.isEmpty()) {
                    Bitmap net = downloadBitmap(item.podcastArtUrl, thumbPx);
                    if (net != null) return net;
                }
                return artistInitials(item.title, thumbPx, host.labelFont());
            default:
                return placeholder(thumbPx, "?", 0xFF444444);
        }
    }

    public static Bitmap resolveFromTracks(List<File> tracks, Host host, int thumbPx) {
        return resolveFromTracks(tracks, host, thumbPx, null,
                host != null ? host.getAlbumArtCacheDir() : null,
                host != null ? host.getFlowThumbCacheDir() : null);
    }

    /** @param albumKey when set, successful resolve is written to {@link AlbumArtCache}. */
    public static Bitmap resolveFromTracks(List<File> tracks, Host host, int thumbPx,
            String albumKey, File artDir) {
        return resolveFromTracks(tracks, host, thumbPx, albumKey, artDir,
                host != null ? host.getFlowThumbCacheDir() : null);
    }

    public static Bitmap resolveFromTracks(List<File> tracks, Host host, int thumbPx,
            String albumKey, File artDir, File flowThumbDir) {
        if (tracks == null || tracks.isEmpty()) {
            return cachePlaceholder(thumbPx, albumKey, artDir, flowThumbDir);
        }
        for (File track : tracks) {
            if (track == null || !track.isFile()) continue;
            File sidecar = host.coverFileForTrack(track);
            if (sidecar != null && sidecar.isFile()) {
                Bitmap b = decodeFile(sidecar, thumbPx);
                if (b != null) {
                    maybeCacheAlbumArt(artDir, albumKey, b);
                    maybeCacheFlowThumb(flowThumbDir, albumKey, b, thumbPx);
                    return b;
                }
            }
            Bitmap embedded = readEmbeddedArt(track);
            if (embedded != null) {
                Bitmap out = AlbumCoverPipeline.scaleForFlow(embedded, thumbPx, thumbPx);
                if (embedded != out && !embedded.isRecycled()) embedded.recycle();
                maybeCacheAlbumArt(artDir, albumKey, out);
                maybeCacheFlowThumb(flowThumbDir, albumKey, out, thumbPx);
                return out;
            }
            android.content.SharedPreferences prefs = host.prefs();
            if (prefs != null) {
                String url = DeezerMetadata.coverUrl(prefs, track.getAbsolutePath());
                if (url != null && !url.isEmpty()) {
                    Bitmap net = downloadBitmap(url, thumbPx);
                    if (net != null) {
                        maybeCacheAlbumArt(artDir, albumKey, net);
                        maybeCacheFlowThumb(flowThumbDir, albumKey, net, thumbPx);
                        return net;
                    }
                }
            }
        }
        return cachePlaceholder(thumbPx, albumKey, artDir, flowThumbDir);
    }

    /** ♪ tile for art-less albums — memory only; never persist (blocks real embed on next open). */
    private static Bitmap cachePlaceholder(int thumbPx, String albumKey, File artDir, File flowThumbDir) {
        return placeholder(thumbPx, "♪", 0xFF333333);
    }

    private static void maybeCacheAlbumArt(File artDir, String albumKey, Bitmap bmp) {
        if (artDir == null || albumKey == null || albumKey.isEmpty() || bmp == null) return;
        AlbumArtCache.put(artDir, albumKey, bmp);
    }

    private static void maybeCacheFlowThumb(File flowDir, String coverKey, Bitmap bmp, int thumbPx) {
        if (flowDir == null || coverKey == null || coverKey.isEmpty() || bmp == null) return;
        FlowThumbCache.put(flowDir, coverKey, bmp, thumbPx);
    }

    public static Bitmap artistInitials(String name, int sizePx, Typeface font) {
        String label = initialsFor(name);
        int color = 0xFF000000 | (ArtistNames.matchKey(name).hashCode() & 0x00FFFFFF);
        if ((color & 0xFFFFFF) < 0x404040) color |= 0x606060;
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(color);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        paint.setTextSize(sizePx * (label.length() > 1 ? 0.34f : 0.48f));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = sizePx * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, sizePx * 0.5f, y, paint);
        return finalizeThumb(bmp, sizePx);
    }

    static String initialsFor(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return ("" + trimmed.charAt(0)).toUpperCase();
    }

    private static Bitmap placeholder(int sizePx, String glyph, int bg) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bg);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(sizePx * 0.45f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = sizePx * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(glyph, sizePx * 0.5f, y, paint);
        return finalizeThumb(bmp, sizePx);
    }

    /** Scale + LCD dither (when enabled) for generated placeholder tiles. */
    private static Bitmap finalizeThumb(Bitmap raw, int thumbPx) {
        if (raw == null) return null;
        Bitmap out = AlbumCoverPipeline.scaleForFlow(raw, thumbPx, thumbPx);
        if (out != raw && !raw.isRecycled()) raw.recycle();
        return out;
    }

    private static Bitmap decodeFile(File f, int thumbPx) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sampleSize(f, thumbPx);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = android.graphics.BitmapFactory.decodeStream(new FileInputStream(f), null, opts);
            if (raw == null) return null;
            Bitmap out = AlbumCoverPipeline.scaleForFlow(raw, thumbPx, thumbPx);
            if (raw != out && !raw.isRecycled()) raw.recycle();
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int sampleSize(File f, int target) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(new FileInputStream(f), null, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return 1;
            int max = Math.max(w, h);
            int sample = 1;
            while (max / sample > target * 2) sample *= 2;
            return sample;
        } catch (Exception e) {
            return 2;
        }
    }

    private static Bitmap scale(Bitmap src, int thumbPx) {
        Bitmap working = src;
        if (src.getWidth() != thumbPx || src.getHeight() != thumbPx) {
            working = Bitmap.createScaledBitmap(src, thumbPx, thumbPx, false);
            if (working != src && !src.isRecycled()) src.recycle();
        }
        return finalizeThumb(working, thumbPx);
    }

    private static Bitmap downloadBitmap(String url, int thumbPx) {
        try {
            byte[] raw = com.solar.launcher.net.SolarHttp.getBytes(url);
            if (raw == null || raw.length == 0) return null;
            Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp == null) return null;
            return AlbumCoverPipeline.scaleForFlow(bmp, thumbPx, thumbPx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap readEmbeddedArt(File track) {
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(track.getAbsolutePath());
            byte[] raw = mmr.getEmbeddedPicture();
            mmr.release();
            if (raw == null || raw.length == 0) return null;
            return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Album match key helper for catalog. */
    public static String albumMatchKey(String album, String artist) {
        return AlbumNames.matchKey(album) + "|" + ArtistNames.matchKey(artist != null ? artist : "");
    }
}
