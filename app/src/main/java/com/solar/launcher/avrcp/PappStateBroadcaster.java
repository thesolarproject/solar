package com.solar.launcher.avrcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.FileObserver;

/** Repeat/shuffle prefs ↔ y1-track-info + CT-initiated y1-papp-set round-trip. */
public final class PappStateBroadcaster {
    public interface Listener {
        void onCtRepeatShuffle(int repeatMode, boolean shuffle);
    }

    private static FileObserver pappObserver;
    private static Listener listener;

    static void register(Context context, Listener l) {
        listener = l;
        AvrcpPaths.ensureFilesDir();
        try {
            java.io.File f = new java.io.File(AvrcpPaths.PAPP_SET);
            if (!f.exists()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                raf.setLength(2);
                raf.close();
                f.setReadable(true, false);
                f.setWritable(true, true);
            }
        } catch (Throwable ignored) {}
        pushFromPrefs(context);
        if (pappObserver != null) return;
        pappObserver = new FileObserver(AvrcpPaths.FILES_DIR, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (!"y1-papp-set".equals(path)) return;
                applyPappFromFile();
            }
        };
        try {
            pappObserver.startWatching();
        } catch (Throwable ignored) {}
    }

    static void unregister() {
        if (pappObserver != null) {
            try { pappObserver.stopWatching(); } catch (Throwable ignored) {}
            pappObserver = null;
        }
        listener = null;
    }

    public static void pushFromPrefs(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
            int repeat = prefs.getInt("repeat_mode", 0);
            boolean shuffle = prefs.getBoolean("shuffle", false);
            TrackInfoWriter.INSTANCE.setRepeatShuffle(repeat, shuffle);
        } catch (Throwable ignored) {}
    }

    private static void applyPappFromFile() {
        Listener l = listener;
        if (l == null) return;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(AvrcpPaths.PAPP_SET, "r");
            byte repeatAvrcp = raf.readByte();
            byte shuffleAvrcp = raf.readByte();
            raf.close();
            int repeatMode = repeatAvrcp == 0x02 ? 1 : repeatAvrcp == 0x03 ? 2 : 0;
            boolean shuffle = shuffleAvrcp == 0x02;
            l.onCtRepeatShuffle(repeatMode, shuffle);
        } catch (Throwable ignored) {}
    }
}
