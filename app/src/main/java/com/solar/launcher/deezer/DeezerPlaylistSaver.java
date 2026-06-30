package com.solar.launcher.deezer;

import android.content.SharedPreferences;

import com.solar.launcher.PlaylistManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Sequential Deezer playlist download + relative M3U write. */
public final class DeezerPlaylistSaver {
    public interface Listener {
        void onProgress(int done, int total, String trackTitle);
        void onComplete(File m3uFile, List<File> tracks);
        void onError(String message);
    }

    private DeezerPlaylistSaver() {}

    public static void save(final SharedPreferences prefs, final File musicRoot,
            final DeezerPlaylist playlist, final List<DeezerResult> tracks,
            final Listener listener) {
        if (musicRoot == null || playlist == null || tracks == null || tracks.isEmpty()) {
            if (listener != null) listener.onError("No tracks");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String ext = "mp3";
                    try {
                        DeezerClient probe = new DeezerClient(prefs);
                        if (!probe.isSessionValid()) probe.initSession();
                        ext = probe.fileExtension();
                    } catch (Exception ignored) {}
                    List<File> saved = new ArrayList<File>();
                    int total = tracks.size();
                    int skipped = 0;
                    String lastErr = null;
                    for (int i = 0; i < total; i++) {
                        final DeezerResult track = tracks.get(i);
                        if (track == null || track.id <= 0) {
                            skipped++;
                            continue;
                        }
                        if (listener != null) {
                            listener.onProgress(i, total, track.title);
                        }
                        File dest = uniqueTrackFile(musicRoot, track, ext);
                        DeezerDownloadRunner.Progress progress = new DeezerDownloadRunner.Progress() {
                            @Override public void onProgress(int percent, long done, long totalBytes) {}
                        };
                        String err = DeezerDownloadRunner.downloadWithFallback(
                                prefs, track, dest, ext, progress);
                        if (err != null) {
                            lastErr = err;
                            skipped++;
                            continue;
                        }
                        if (dest.isFile()) saved.add(dest);
                    }
                    if (saved.isEmpty()) {
                        String msg = lastErr != null ? lastErr : "No tracks saved";
                        if (listener != null) listener.onError(msg);
                        return;
                    }
                    File playlistsDir = PlaylistManager.playlistsDir(musicRoot);
                    if (!playlistsDir.exists()) playlistsDir.mkdirs();
                    File m3u = new File(playlistsDir, playlist.safeFileName() + ".m3u");
                    int n = 1;
                    while (m3u.exists()) {
                        m3u = new File(playlistsDir, playlist.safeFileName() + " (" + n + ").m3u");
                        n++;
                    }
                    PlaylistManager.saveM3uRelative(
                            PlaylistManager.fromTracks(playlist.title, saved), m3u);
                    if (listener != null) listener.onComplete(m3u, saved);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError(e.getMessage() != null ? e.getMessage() : "Save failed");
                    }
                }
            }
        }, "DeezerPlaylistSave").start();
    }

    /** Unique destination path under music root for a single Deezer track download. */
    public static File uniqueTrackPath(File musicRoot, DeezerResult result, String ext) {
        return uniqueTrackFile(musicRoot, result, ext);
    }

    private static File uniqueTrackFile(File destDir, DeezerResult result, String ext) {
        String safeName = result.filenameBase() + "." + ext;
        File dest = new File(destDir, safeName);
        int n = 1;
        while (dest.exists()) {
            dest = new File(destDir, result.filenameBase() + " (" + n + ")." + ext);
            n++;
        }
        return dest;
    }
}
