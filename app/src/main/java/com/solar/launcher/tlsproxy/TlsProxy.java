package com.solar.launcher.tlsproxy;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/**
 * Local TLS 1.0/1.1 → TLS 1.3 MITM proxy listener (port of Wolfius {@code TlsProxy}, GPLv3).
 *
 * Accepts redirected connections (iptables DNAT / HTTP CONNECT), peeks the ClientHello for
 * the SNI host, terminates the old client's TLS with a freshly generated per-domain leaf cert
 * signed by the bundled CA, then re-encrypts upstream with a wolfSSL TLS 1.3 client context.
 */
public final class TlsProxy implements Runnable {
    private static final String TAG = "TlsProxy";

    public static final int LISTEN_PORT = 7998;

    private final int listenPort;
    private ServerSocket serverSocket;
    private boolean isRunning = true;

    private final ExecutorService connectionPool = Executors.newCachedThreadPool();

    public TlsProxy(int listenPort) {
        this.listenPort = listenPort;
    }

    public void start() {
        Log.i(TAG, "Starting on port " + listenPort);
        new Thread(this, "ProxyListener").start();
    }

    public void stop() {
        Log.i(TAG, "Stopping proxy server");
        isRunning = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) { e.printStackTrace(); }
        }
        connectionPool.shutdownNow();
    }

    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listenPort));
            try {
                serverSocket.setReceiveBufferSize(32768);
            } catch (SocketException ignored) {}

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                try {
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setSendBufferSize(16384);
                    clientSocket.setReceiveBufferSize(16384);
                    clientSocket.setSoTimeout(30000);
                } catch (SocketException ignored) {}

                connectionPool.execute(new ConnectionHandler(clientSocket, connectionPool, listenPort));
            }
        } catch (IOException e) {
            if (isRunning) Log.e(TAG, "Server socket exception!!", e);
        }
    }

    private static String queryPptpDst(int clientLocalPort) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(1000);

            byte[] buf = new byte[2];
            buf[0] = (byte) ((clientLocalPort >> 8) & 0xFF);
            buf[1] = (byte) (clientLocalPort & 0xFF);

            DatagramPacket packet = new DatagramPacket(buf, buf.length, InetAddress.getByName("127.0.0.1"), 7999);
            socket.send(packet);

            byte[] recvBuf = new byte[64];
            DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPacket);

            String result = new String(recvPacket.getData(), 0, recvPacket.getLength(), "UTF-8");
            if ("0.0.0.0:0".equals(result)) {
                return null;
            }
            return result;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static class ConnectionHandler implements Runnable {
        private final Socket clientSocket;
        private final ExecutorService threadPool;
        private final int listenPort;

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

        ConnectionHandler(Socket clientSocket, ExecutorService threadPool, int listenPort) {
            this.clientSocket = clientSocket;
            this.threadPool = threadPool;
            this.listenPort = listenPort;
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

        public void run() {
            SSLSocket clientSslSocket = null;
            WolfSSLSocket upstreamSslSocket = null;
            Socket targetSocket = null;

            try {
                int fd = extractNativeFd(clientSocket);
                int firstByte = WolfClient.nativePeekFirstByte(fd);
                if (firstByte == -1) {
                    closeSockets(clientSocket, null);
                    return;
                }

                boolean isTls = (firstByte == 0x16) || ((firstByte & 0x80) != 0);
                if (isTls) {
                    String host = WolfClient.nativePeekAndParseSNI(fd);
                    int port = 443;
                    if (host == null) {
                        String origDst = queryPptpDst(clientSocket.getPort());
                        if (origDst == null)
                            origDst = WolfClient.nativeGetOriginalDst(fd);
                        if (origDst != null) {
                            String[] parts = origDst.split(":");
                            String tempHost = parts[0];
                            int tempPort = 443;
                            if (parts.length > 1) {
                                tempPort = Integer.parseInt(parts[1]);
                            }
                            if (tempPort != listenPort) {
                                host = tempHost;
                                port = tempPort;
                            }
                        }
                    }
                    String originalIp = host;
                    if (host != null && host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                        String resolvedHost = DnsForwarder.getHostForIp(host);
                        if (resolvedHost != null) {
                            Log.i(TAG, "Restored missing SNI client IP " + host + " to hostname: " + resolvedHost);
                            host = resolvedHost;
                        }
                    }
                    if (host == null) {
                        Log.e(TAG, "Transparent redirect target could not be resolved.");
                        closeSockets(clientSocket, null);
                        return;
                    }

                    MitmKeyStoreManager.currentHost.set(host);

                    try {
                        long ctxPtr = MitmKeyStoreManager.getInstance().getServerContext(host);
                        long serverSslPtr = 0;
                        if (ctxPtr != 0) {
                            serverSslPtr = WolfClient.nativeCreateServerSSLFromContext(fd, ctxPtr);
                        }
                        if (serverSslPtr == 0) {
                            Log.e(TAG, "Failed to create native Server SSL context for " + host);
                            closeSockets(clientSocket, null);
                            return;
                        }
                        int ret = WolfClient.nativeAccept(serverSslPtr);
                        if (ret != 1) {
                            if (ret == -308) {
                                Log.i(TAG, "Native server-side connection aborted by client (EOF/RST)");
                            } else {
                                Log.e(TAG, "Native server-side handshake failed with error " + ret);
                            }
                            WolfClient.nativeFreeSSL(serverSslPtr);
                            closeSockets(clientSocket, null);
                            return;
                        }
                        clientSslSocket = new WolfSSLSocket(clientSocket, serverSslPtr, host, port, true);
                        upstreamSslSocket = new WolfSSLSocket(host, originalIp, port);
                        upstreamSslSocket.startHandshake();
                        bridgeData(clientSslSocket, upstreamSslSocket);
                    } finally {
                        MitmKeyStoreManager.currentHost.remove();
                    }
                } else {
                    String origDst = WolfClient.nativeGetOriginalDst(fd);
                    String transHost = null;
                    int transPort = 80;
                    boolean isTransparent = false;
                    if (origDst != null) {
                        String[] parts = origDst.split(":");
                        transHost = parts[0];
                        if (parts.length > 1)
                            transPort = Integer.parseInt(parts[1]);
                        if (transPort != listenPort)
                            isTransparent = true;
                    }
                    if (isTransparent) {
                        Log.i(TAG, "Transparent non-TLS bypass connection to " + transHost + ":" + transPort);
                        targetSocket = WolfSSLSocket.createBoundPlainSocket(transHost, transHost, transPort);
                        try {
                            targetSocket.setSoTimeout(30000);
                        } catch (SocketException ignored) {}
                        targetSocket.setTcpNoDelay(true);
                        bridgeData(clientSocket, targetSocket);
                    } else {
                        InputStream in = clientSocket.getInputStream();
                        OutputStream out = clientSocket.getOutputStream();
                        String requestLine = readLine(in);
                        if (requestLine == null) {
                            closeSockets(clientSocket, null);
                            return;
                        }
                        if (requestLine.toUpperCase().startsWith("CONNECT")) {
                            String[] parts = requestLine.split(" ");
                            String target = parts[1];
                            String[] hostPort = target.split(":");
                            String host = hostPort[0];
                            int port = (hostPort.length > 1) ? Integer.parseInt(hostPort[1]) : 443;
                            while (true) {
                                String line = readLine(in);
                                if (line == null || line.length() == 0) break;
                            }
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
                            out.flush();
                            String originalIp = host;
                            long startWait = System.currentTimeMillis();
                            while (System.currentTimeMillis() - startWait < 3000) {
                                firstByte = WolfClient.nativePeekFirstByte(fd);
                                if (firstByte != -1) {
                                    break;
                                }
                                try { Thread.sleep(10); } catch (Exception ignored) {}
                            }
                            boolean isTargetTls = (firstByte == 0x16) || (firstByte != -1 && (firstByte & 0x80) != 0);
                            if (isTargetTls) {
                                String sniHost = WolfClient.nativePeekAndParseSNI(fd);
                                if (sniHost != null && sniHost.trim().length() != 0) {
                                    Log.i(TAG, "CONNECT : extracted SNI directly from Client Hello " + sniHost);
                                    host = sniHost;
                                } else {
                                    if (host != null && host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                                        String resolvedHost = DnsForwarder.getHostForIp(host);
                                        if (resolvedHost != null) {
                                            Log.i(TAG, "CONNECT : Restored missing SNI client IP " + host + " to hostname " + resolvedHost);
                                            host = resolvedHost;
                                        }
                                    }
                                }
                                MitmKeyStoreManager.currentHost.set(host);
                                try {
                                    long ctxPtr = MitmKeyStoreManager.getInstance().getServerContext(host);
                                    long serverSslPtr = 0;
                                    if (ctxPtr != 0) {
                                        serverSslPtr = WolfClient.nativeCreateServerSSLFromContext(fd, ctxPtr);
                                    }
                                    if (serverSslPtr == 0) {
                                        Log.e(TAG, "Failed to create native Server SSL context for " + host);
                                        closeSockets(clientSocket, null);
                                        return;
                                    }
                                    int ret = WolfClient.nativeAccept(serverSslPtr);
                                    if (ret != 1) {
                                        if (ret == -308) {
                                            Log.i(TAG, "Native server-side connection aborted by client (EOF/RST)");
                                        } else {
                                            Log.e(TAG, "Native server-side handshake failed with error " + ret);
                                        }
                                        WolfClient.nativeFreeSSL(serverSslPtr);
                                        closeSockets(clientSocket, null);
                                        return;
                                    }
                                    clientSslSocket = new WolfSSLSocket(clientSocket, serverSslPtr, host, port, true);
                                    upstreamSslSocket = new WolfSSLSocket(host, originalIp, port);
                                    upstreamSslSocket.startHandshake();
                                    bridgeData(clientSslSocket, upstreamSslSocket);
                                } finally {
                                    MitmKeyStoreManager.currentHost.remove();
                                }
                            } else {
                                // Plain text (HTTP port 80)
                                Log.i(TAG, "CONNECT non-TLS bypass connection to " + host + ":" + port);
                                targetSocket = WolfSSLSocket.createBoundPlainSocket(host, originalIp, port);
                                try {
                                    targetSocket.setSoTimeout(30000);
                                } catch (SocketException ignored) {}
                                targetSocket.setTcpNoDelay(true);
                                bridgeData(clientSocket, targetSocket);
                            }
                        } else if (requestLine.toUpperCase().startsWith("GET") || requestLine.toUpperCase().startsWith("POST")) {
                            String[] parts = requestLine.split(" ");
                            if (parts.length >= 2) {
                                String uri = parts[1];
                                String host = null;
                                int port = 80;
                                String path = "/";
                                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                                    int schemeLen = uri.startsWith("http://") ? 7 : 8;
                                    int slashIdx = uri.indexOf('/', schemeLen);
                                    String hostPort;
                                    if (slashIdx != -1) {
                                        hostPort = uri.substring(schemeLen, slashIdx);
                                        path = uri.substring(slashIdx);
                                    } else {
                                        hostPort = uri.substring(schemeLen);
                                        path = "/";
                                    }
                                    int colonIdx = hostPort.indexOf(':');
                                    if (colonIdx != -1) {
                                        host = hostPort.substring(0, colonIdx);
                                        try {
                                            port = Integer.parseInt(hostPort.substring(colonIdx + 1));
                                        } catch (NumberFormatException e) {
                                            port = uri.startsWith("https://") ? 443 : 80;
                                        }
                                    } else {
                                        host = hostPort;
                                        port = uri.startsWith("https://") ? 443 : 80;
                                    }
                                }
                                if (host != null) {
                                    Log.i(TAG, "HTTP Proxy non-TLS bypass connection to " + host + ":" + port);
                                    targetSocket = WolfSSLSocket.createBoundPlainSocket(host, port);
                                    try {
                                        targetSocket.setSoTimeout(30000);
                                    } catch (SocketException ignored) {}
                                    targetSocket.setTcpNoDelay(true);
                                    String httpVersion = parts.length > 2 ? parts[2] : "HTTP/1.1";
                                    String newRequestLine = parts[0] + " " + path + " " + httpVersion + "\r\n";
                                    targetSocket.getOutputStream().write(newRequestLine.getBytes("UTF-8"));
                                    bridgeData(clientSocket, targetSocket);
                                } else closeSockets(clientSocket, null);
                            }
                        } else {
                            closeSockets(clientSocket, null);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection handler error", e);
                closeSockets(clientSslSocket, upstreamSslSocket);
                closeSockets(targetSocket, null);
                closeSockets(clientSocket, null);
            }
        }

        private void bridgeData(final Socket client, final Socket server) {
            final Object lock = new Object();
            final boolean[] finished = new boolean[2];

            threadPool.execute(new Runnable() {
                public void run() {
                    try {
                        InputStream in = client.getInputStream();
                        OutputStream out = server.getOutputStream();
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    } catch (IOException e) {
                        closeSockets(client, server);
                        return;
                    }

                    synchronized (lock) {
                        finished[0] = true;
                        if (finished[1]) {
                            closeSockets(client, server);
                        } else {
                            try {
                                server.shutdownOutput();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            });

            try {
                InputStream in = server.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                closeSockets(client, server);
                return;
            }

            synchronized (lock) {
                finished[1] = true;
                if (finished[0]) {
                    closeSockets(client, server);
                } else {
                    try {
                        client.shutdownOutput();
                    } catch (Throwable ignored) {}
                }
            }
        }

        private String readLine(InputStream is) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') break;
                sb.append((char) c);
            }
            if (sb.length() == 0 && c == -1) return null;
            return sb.toString();
        }

        private void closeSockets(Socket s1, Socket s2) {
            if (s1 != null) { try { s1.close(); } catch (IOException ignored) {} }
            if (s2 != null) { try { s2.close(); } catch (IOException ignored) {} }
        }
    }
}
