package io.github.gohoski.wolfius;

/**
 * Created by Gleb on 22.06.2026.
 */
class WolfClient {
    static {
        System.loadLibrary("wolfssl");
        globalCtxPtr = nativeCreateContext();
    }
    static final long globalCtxPtr;// Points to the global native WOLFSSL_CTX client structure used for all outbound upgraded handshakes

    public static native long nativeCreateContext();
    public static native long nativeCreateSSL(long ctxPtr, int fd, String host);
    public static native long nativeCreateServerContext(String host, byte[] caCertDer, int sigType);
    public static native long nativeCreateServerSSLFromContext(int fd, long ctxPtr);
    public static native int nativeHandshake(long sslPtr);
    public static native int nativeAccept(long sslPtr);
    public static native int nativeRead(long sslPtr, byte[] b, int off, int len);
    public static native int nativeWrite(long sslPtr, byte[] b, int off, int len);
    public static native void nativeFreeSSL(long sslPtr);
    public static native void nativeFreeContext(long ctxPtr);
    public static native int nativeLoadKeys(byte[] caKeyDer, byte[] leafKeyPkcs1Der, byte[] leafKeyPkcs8Der);
    public static native byte[] nativeGenerateMitmCert(String host, byte[] caCertDer, int sigType);
    public static native int nativePeekFirstByte(int fd);
    public static native String nativePeekAndParseSNI(int fd);
    public static native String nativeGetOriginalDst(int fd);
}