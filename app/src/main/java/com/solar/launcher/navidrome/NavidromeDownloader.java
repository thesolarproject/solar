package com.solar.launcher.navidrome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.net.SolarHttp;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-06: Download Navidrome album tracks to local Navidrome/Artist/Album folder.
 */
public final class NavidromeDownloader {

    public interface Callback {
        void onProgress(int done, int total, long bytesRead, long totalBytes);
        void onComplete(int saved);
        void onError(String message);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private NavidromeDownloader() {}

    /** Deterministic destination for a downloaded track; shared by Save + Stems UI. */
    public static File destinationFor(Context ctx, NavidromeArtist artist,
            NavidromeAlbum album, NavidromeSong song) {
        File root = new File(DeviceFeatures.getNewMediaRoot(ctx), "Navidrome");
        String artistDir = safeName(artist != null ? artist.name : (album != null ? album.artist : ""));
        String albumDir = safeName(album != null ? album.name : (song != null ? song.album : ""));
        String ext = song != null && song.suffix != null && !song.suffix.isEmpty()
                ? song.suffix : "mp3";
        return new File(new File(new File(root, artistDir), albumDir),
                safeName(song != null ? song.title : "track") + "." + ext.toLowerCase(Locale.US));
    }

    public static void downloadAlbum(final Context ctx, final NavidromeArtist artist,
            final NavidromeAlbum album, final List<NavidromeSong> songs, final Callback cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (songs == null || songs.isEmpty()) {
                    postError(cb, "No songs");
                    return;
                }
                File root = new File(DeviceFeatures.getNewMediaRoot(ctx), "Navidrome");
                String artistDir = safeName(artist != null ? artist.name : album.artist);
                String albumDir = safeName(album.name);
                File dir = new File(new File(root, artistDir), albumDir);
                if (!dir.exists()) dir.mkdirs();
                int saved = 0;
                final int totalTracks = songs.size();
                for (int i = 0; i < songs.size(); i++) {
                    NavidromeSong s = songs.get(i);
                    final int trackNum = i + 1;
                    try {
                        String ext = s.suffix != null && !s.suffix.isEmpty() ? s.suffix : "mp3";
                        File out = destinationFor(ctx, artist, album, s);
                        if (out.exists() && out.length() > 1024) {
                            // Already offline-ready — count as saved and report full-byte progress.
                            saved++;
                            final long len = out.length();
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (cb != null) cb.onProgress(trackNum, totalTracks, len, len);
                                }
                            });
                        } else {
                            downloadUrl(NavidromeClient.getInstance().getDownloadUrl(s.id), out,
                                    cb, trackNum, totalTracks);
                            saved++;
                        }
                    } catch (Exception e) {
                        postError(cb, e.getMessage());
                        return;
                    }
                }
                final int count = saved;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onComplete(count);
                    }
                });
            }
        });
    }

    /** 2026-07-06: Same TLS/HTTP stack as NavidromeClient — avoids refused/wrong-port downloads. */
    private static void downloadUrl(String urlStr, File out, final Callback cb,
            final int trackNum, final int totalTracks) throws Exception {
        SolarHttp.downloadToFile(urlStr, out, new SolarHttp.DownloadProgress() {
            @Override
            public void onProgress(final long bytesRead, final long totalBytes) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onProgress(trackNum, totalTracks, bytesRead, totalBytes);
                    }
                });
            }
        });
    }

    static String safeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown";
        return raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void postError(final Callback cb, final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onError(msg != null ? msg : "error");
            }
        });
    }
}
