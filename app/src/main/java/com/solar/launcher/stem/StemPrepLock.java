package com.solar.launcher.stem;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/** Prevent background prefetch and foreground queue preparation racing on one track. */
public final class StemPrepLock {
    private static final ConcurrentHashMap<String, Object> LOCKS =
            new ConcurrentHashMap<String, Object>();
    /** One extraction/publish operation at a time across foreground and background hosts. */
    private static final Object EXTRACTION_LOCK = new Object();

    private StemPrepLock() {}

    public static Object global() {
        return EXTRACTION_LOCK;
    }

    public static Object forTrack(File track) {
        String path = track != null ? track.getAbsolutePath() : "null";
        Object fresh = new Object();
        Object existing = LOCKS.putIfAbsent(path, fresh);
        return existing != null ? existing : fresh;
    }
}
