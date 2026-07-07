package com.solar.launcher.flow;

import android.os.Looper;

final class ArtworkThreads {
    private ArtworkThreads() {}

    static boolean isMainThread() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && Looper.myLooper() == main;
        } catch (RuntimeException androidStub) {
            return false;
        }
    }
}
