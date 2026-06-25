package com.solar.launcher.deezer;

import android.content.SharedPreferences;

import com.solar.launcher.PlaylistManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
                final AtomicBoolean cancel = new AtomicBoolean(false);
                try {
                    DeezerClient client = new DeezerClient(prefs);
                    if (!client.isSessionValid()) client.initSession();
                    DeezerDownloader downloader = new DeezerDownloader(client);
                    String ext = client.fileExtension();
                    List<File> saved = new ArrayList<File>();
                    int total = tracks.size();
                    for (int i = 0; i < total; i++) {
                        if (cancel.get()) return;
                        final DeezerResult track = tracks.get(i);
                        if (track == null || track.id <= 0) continue;
                        final int done = i;
                        if (listener != null) {
                            listener.onProgress(done, total, track.title);
                        }
                        final Object lock = new Object();
                        final boolean[] ok = {false};
                        final String[] err = {null};
                        final File[] dest = {null};
                        downloader.download(track, musicRoot, ext, new DeezerDownloader.Listener() {
                            @Override public void onProgress(long doneBytes, long totalBytes) {}
                            @Override public void onPartialReady(File d, long bytesRead) {}
                            @Override public void onComplete(File d, DeezerTrackData t) {
                                dest[0] = d;
                                ok[0] = true;
                                synchronized (lock) { lock.notifyAll(); }
                            }
                            @Override public void onError(String message) {
                                err[0] = message;
                                synchronized (lock) { lock.notifyAll(); }
                            }
                        });
                        synchronized (lock) {
                            while (!ok[0] && err[0] == null) {
                                lock.wait(300000);
                            }
                        }
                        if (err[0] != null) {
                            if (listener != null) listener.onError(err[0]);
                            return;
                        }
                        if (dest[0] != null && dest[0].isFile()) saved.add(dest[0]);
                    }
                    if (saved.isEmpty()) {
                        if (listener != null) listener.onError("No tracks saved");
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
}
