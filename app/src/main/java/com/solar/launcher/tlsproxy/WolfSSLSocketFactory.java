package com.solar.launcher.tlsproxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;

/**
 * SSLSocketFactory that hands out {@link WolfSSLSocket}s (port of Wolfius
 * {@code WolfSSLSocketFactory}, GPLv3). Not currently wired into Solar's OkHttp clients —
 * those keep the direct Conscrypt TLS 1.2/1.3 path; this exists for the proxy internals
 * and for future platform-API consumers of the Wolfius stack.
 */
class WolfSSLSocketFactory extends SSLSocketFactory {
    @Override
    public String[] getDefaultCipherSuites() { return new String[0]; }

    @Override
    public String[] getSupportedCipherSuites() { return new String[0]; }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return new WolfSSLSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return new WolfSSLSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return new WolfSSLSocket(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return new WolfSSLSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return new WolfSSLSocket(address.getHostAddress(), port);
    }
}
