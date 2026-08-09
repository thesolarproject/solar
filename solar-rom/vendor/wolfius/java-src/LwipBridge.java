package io.github.gohoski.wolfius;

/**
 * Created by Gleb on 12.07.2026.
 */

class LwipBridge {
    private static boolean isLoaded = false;

    static synchronized void loadLibrary() {
        if (!isLoaded) {
            System.loadLibrary("lwip");
            isLoaded = true;
        }
    }

    public static synchronized boolean isLoaded() {
        return isLoaded;
    }

    public static native void nativeInitLwIP(int writeFd);
    public static native void nativeInputPacket(byte[] data, int len);
    public static native void nativeProcessLwIPPackets();
    public static native void nativeStopLwIP();
}