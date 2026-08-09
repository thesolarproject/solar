package com.solar.launcher.tlsproxy;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;

/**
 * SSLSocket backed by wolfSSL native handles (port of Wolfius {@code WolfSSLSocket}, GPLv3).
 *
 * Two roles:
 * <ul>
 *   <li>upstream client side — connects to the real server, {@link #startHandshake()} runs
 *       {@code wolfSSL_connect} (TLS 1.2/1.3);</li>
 *   <li>downstream server side — wraps an accepted socket whose fd already ran
 *       {@code wolfSSL_accept} (terminates the old client's TLS).</li>
 * </ul>
 */
class WolfSSLSocket extends javax.net.ssl.SSLSocket {
    private static final String TAG = "WolfSSLSocket";

    private Socket socket;
    private final String host;
    private final int port;
    private final boolean autoClose;

    private long sslPtr = 0;
    private boolean handshakeDone = false;

    private InputStream is;
    private OutputStream os;

    private final Object stateLock = new Object();
    private boolean isClosed = false;
    private int activeIoThreads = 0;

    private static final int START_PORT = 15000;
    private static final int END_PORT = 25000;
    private static int nextPort = START_PORT;

    private static java.lang.reflect.Field socketImplField;
    private static java.lang.reflect.Field socketFdField;
    private static java.lang.reflect.Field fdDescriptorField;

    static {
        try {
            socketImplField = Socket.class.getDeclaredField("impl");
            socketImplField.setAccessible(true);

            Class<?> implClass = Class.forName("java.net.SocketImpl");
            try {
                socketFdField = implClass.getDeclaredField("fd");
            } catch (NoSuchFieldException e) {
                socketFdField = implClass.getSuperclass().getDeclaredField("fd");
            }
            socketFdField.setAccessible(true);

            try {
                fdDescriptorField = java.io.FileDescriptor.class.getDeclaredField("fd");
            } catch (NoSuchFieldException e) {
                fdDescriptorField = java.io.FileDescriptor.class.getDeclaredField("descriptor");
            }
            fdDescriptorField.setAccessible(true);
        } catch (Exception e) {
            Log.e(TAG, "Static lookup caching of native FD fields failed", e);
        }
    }

    WolfSSLSocket(String host, int port) throws IOException {
        this(host, null, port);
    }

    WolfSSLSocket(String host, String ip, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.autoClose = true;

        initSocketAndConnect(ip != null ? ip : host, port);
        initWolfSSL();
    }

    WolfSSLSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        this.socket = socket;
        this.host = host;
        this.port = port;
        this.autoClose = autoClose;
        initWolfSSL();
    }

    WolfSSLSocket(Socket socket, long sslPtr, String host, int port, boolean autoClose) {
        this.socket = socket;
        this.sslPtr = sslPtr;
        this.host = host;
        this.port = port;
        this.autoClose = autoClose;
        this.handshakeDone = true;
    }

    private static InetAddress getBindAddress() {
        if ("pptp".equals(WolfiusProxy.currentMethod)) {
            return null;
        }
        String physicalIp = WolfiusProxy.getActiveLocalIpAddress();
        if (physicalIp != null) {
            try {
                return InetAddress.getByName(physicalIp);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void initSocketAndConnect(String connectTarget, int port) throws IOException {
        boolean connected = false;
        int attempts = 0;
        IOException lastException = null;

        while (!connected && attempts < 50) {
            this.socket = new Socket();
            try {
                this.socket.setReuseAddress(true);
            } catch (SocketException ignored) {}

            int localPort = getNextSourcePort();
            try {
                if ("pptp".equals(WolfiusProxy.currentMethod)) {
                    this.socket.connect(new InetSocketAddress("127.0.0.1", 7996), 30000);
                    String connectReq = "CONNECT " + connectTarget + ":" + port + " HTTP/1.1\r\n\r\n";
                    this.socket.getOutputStream().write(connectReq.getBytes("UTF-8"));
                    this.socket.getOutputStream().flush();

                    InputStream in = this.socket.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        if (c == '\r') continue;
                        if (c == '\n') {
                            if (sb.length() == 0) break;
                            sb.setLength(0);
                        } else {
                            sb.append((char) c);
                        }
                    }
                } else {
                    this.socket.bind(new InetSocketAddress(getBindAddress(), localPort));
                    VpnCompatHelper.protect(this.socket);
                    this.socket.connect(new InetSocketAddress(connectTarget, port), 30000);
                }
                connected = true;
            } catch (IOException e) {
                lastException = e;
                if (e instanceof java.net.UnknownHostException) {
                    attempts = 50;
                } else {
                    attempts++;
                }
                try {
                    this.socket.close();
                } catch (IOException ignored) {}
            }
        }

        if (!connected) {
            if (lastException != null) {
                throw lastException;
            } else {
                throw new IOException("Failed to establish bound socket after " + attempts + " attempts.");
            }
        }
    }

    private void initWolfSSL() throws IOException {
        if (!socket.isConnected()) {
            throw new IOException("Underlying socket must be connected.");
        }

        try {
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(16384);
            socket.setSendBufferSize(16384);
            socket.setSoTimeout(30000);
        } catch (SocketException ignored) {}

        int fd = extractNativeFd(socket);

        sslPtr = WolfClient.nativeCreateSSL(WolfClient.globalCtxPtr, fd, host);
        if (sslPtr == 0) {
            throw new IOException("Failed to initialize wolfSSL Instance handle!!");
        }
    }

    private int extractNativeFd(Socket socket) throws IOException {
        try {
            if (socketImplField == null || socketFdField == null || fdDescriptorField == null) {
                throw new IOException("Socket fields not cached properly.");
            }
            Object impl = socketImplField.get(socket);
            java.io.FileDescriptor fdObj = (java.io.FileDescriptor) socketFdField.get(impl);
            return fdDescriptorField.getInt(fdObj);
        } catch (Exception e) {
            throw new IOException("Reflection extraction of OS file descriptor failed: " + e.getMessage());
        }
    }

    private void enterIo() throws IOException {
        synchronized (stateLock) {
            if (isClosed) {
                throw new SocketException("Socket is closed");
            }
            activeIoThreads++;
        }
    }

    private void exitIo() {
        synchronized (stateLock) {
            activeIoThreads--;
            if (isClosed && activeIoThreads == 0) {
                stateLock.notifyAll();
                if (sslPtr != 0) {
                    WolfClient.nativeFreeSSL(sslPtr);
                    sslPtr = 0;
                }
            }
        }
    }

    private static synchronized int getNextSourcePort() {
        int port = nextPort;
        nextPort++;
        if (nextPort > END_PORT) {
            nextPort = START_PORT;
        }
        return port;
    }

    static Socket createBoundPlainSocket(String host, int port) throws IOException {
        return createBoundPlainSocket(host, null, port);
    }

    static Socket createBoundPlainSocket(String host, String ip, int port) throws IOException {
        Socket socket = null;
        boolean connected = false;
        int attempts = 0;
        String connectTarget = (ip != null) ? ip : host;
        IOException lastException = null;

        while (!connected && attempts < 50) {
            socket = new Socket();
            try {
                socket.setReuseAddress(true);
            } catch (SocketException ignored) {}

            int localPort = getNextSourcePort();
            try {
                if ("pptp".equals(WolfiusProxy.currentMethod)) {
                    socket.connect(new InetSocketAddress("127.0.0.1", 7996), 30000);
                    String connectReq = "CONNECT " + connectTarget + ":" + port + " HTTP/1.1\r\n\r\n";
                    socket.getOutputStream().write(connectReq.getBytes("UTF-8"));
                    socket.getOutputStream().flush();

                    InputStream in = socket.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        if (c == '\r') continue;
                        if (c == '\n') {
                            if (sb.length() == 0) break;
                            sb.setLength(0);
                        } else {
                            sb.append((char) c);
                        }
                    }
                } else {
                    socket.bind(new InetSocketAddress(getBindAddress(), localPort));
                    VpnCompatHelper.protect(socket);
                    socket.connect(new InetSocketAddress(connectTarget, port), 30000);
                }
                connected = true;
            } catch (IOException e) {
                lastException = e;
                attempts++;
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }

        if (!connected) {
            if (lastException != null) {
                throw lastException;
            } else {
                throw new IOException("Failed to establish plain bound socket after " + attempts + " attempts.");
            }
        }
        return socket;
    }

    @Override
    public void startHandshake() throws IOException {
        if (handshakeDone) return;
        enterIo();
        try {
            int ret = WolfClient.nativeHandshake(sslPtr);
            if (ret != 1) {
                throw new IOException("wolfSSL native TLS handshake failed error code: " + ret);
            }
            handshakeDone = true;
        } finally {
            exitIo();
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!handshakeDone) startHandshake();
        if (is == null) {
            is = new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] singleByte = new byte[1];
                    int readBytes = read(singleByte, 0, 1);
                    if (readBytes <= 0) return -1;
                    return singleByte[0] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    enterIo();
                    try {
                        int res = WolfClient.nativeRead(sslPtr, b, off, len);
                        if (res <= 0) {
                            return -1;
                        }
                        return res;
                    } finally {
                        exitIo();
                    }
                }
            };
        }
        return is;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        if (!handshakeDone) startHandshake();
        if (os == null) {
            os = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    byte[] singleByte = new byte[]{(byte) b};
                    write(singleByte, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return;
                    enterIo();
                    try {
                        int res = WolfClient.nativeWrite(sslPtr, b, off, len);
                        if (res <= 0) throw new IOException("Native wolfSSL write failure: " + res);
                    } finally {
                        exitIo();
                    }
                }
            };
        }
        return os;
    }

    @Override
    public void close() throws IOException {
        synchronized (stateLock) {
            if (isClosed) return;
            isClosed = true;
        }
        if (autoClose) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
        synchronized (stateLock) {
            if (activeIoThreads == 0) {
                if (sslPtr != 0) {
                    WolfClient.nativeFreeSSL(sslPtr);
                    sslPtr = 0;
                }
            }
        }
    }

    @Override
    public void shutdownOutput() throws IOException {
        if (socket != null) {
            socket.shutdownOutput();
        }
    }

    @Override
    public void shutdownInput() throws IOException {
        if (socket != null) {
            socket.shutdownInput();
        }
    }

    @Override public String[] getSupportedCipherSuites() { return new String[0]; }
    @Override public String[] getEnabledCipherSuites() { return new String[0]; }
    @Override public void setEnabledCipherSuites(String[] suites) {}
    @Override public String[] getSupportedProtocols() { return new String[0]; }
    @Override public String[] getEnabledProtocols() { return new String[0]; }
    @Override public void setEnabledProtocols(String[] protocols) {}
    @Override
    public SSLSession getSession() {
        return new SSLSession() {
            public byte[] getId() { return new byte[0]; }
            public javax.net.ssl.SSLSessionContext getSessionContext() { return null; }
            public long getCreationTime() { return 0; }
            public long getLastAccessedTime() { return 0; }
            public void invalidate() {}
            public boolean isValid() { return true; }
            public void putValue(String n, Object v) {}
            public Object getValue(String n) { return null; }
            public void removeValue(String n) {}
            public String[] getValueNames() { return new String[0]; }
            public java.security.cert.Certificate[] getPeerCertificates() throws javax.net.ssl.SSLPeerUnverifiedException {
                return new java.security.cert.Certificate[0];
            }
            public java.security.cert.Certificate[] getLocalCertificates() { return null; }
            public javax.security.cert.X509Certificate[] getPeerCertificateChain() throws javax.net.ssl.SSLPeerUnverifiedException {
                return new javax.security.cert.X509Certificate[0];
            }
            public java.security.Principal getPeerPrincipal() throws javax.net.ssl.SSLPeerUnverifiedException { return null; }
            public java.security.Principal getLocalPrincipal() { return null; }
            public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
            public String getProtocol() { return "TLSv1.3"; }
            public String getPeerHost() { return host; }
            public int getPeerPort() { return port; }
            public int getPacketBufferSize() { return 16384; }
            public int getApplicationBufferSize() { return 16384; }
        };
    }
    @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) {}
    @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) {}
    @Override public void setUseClientMode(boolean mode) {}
    @Override public boolean getUseClientMode() { return true; }
    @Override public void setNeedClientAuth(boolean need) {}
    @Override public boolean getNeedClientAuth() { return false; }
    @Override public void setWantClientAuth(boolean want) {}
    @Override public boolean getWantClientAuth() { return false; }
    @Override public void setEnableSessionCreation(boolean flag) {}
    @Override public boolean getEnableSessionCreation() { return true; }
}
