package com.solar.launcher.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** TLS 1.2+ (1.3 when Conscrypt supports it) with weak-cipher filtering on API 17. */
final class ModernTlsSocketFactory extends SSLSocketFactory {
    private static final String[] TLS_12_ONLY = new String[] {"TLSv1.2"};
    private static final String[] TLS_12_AND_13 = new String[] {"TLSv1.2", "TLSv1.3"};

    /** ponytail: prefer ECDHE+GCM; drop NULL/EXPORT/RC4/3DES on legacy stacks */
    private static final Set<String> WEAK_CIPHER_MARKERS = new HashSet<String>(Arrays.asList(
            "NULL", "EXPORT", "DES", "RC4", "MD5", "anon", "ADH", "AECDH"));

    private final SSLSocketFactory delegate;

    ModernTlsSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    private static void enableProtocols(SSLSocket ssl) {
        String[] supported = ssl.getSupportedProtocols();
        if (supported == null || supported.length == 0) {
            try {
                ssl.setEnabledProtocols(TLS_12_ONLY);
            } catch (Exception ignored) {}
            return;
        }
        List<String> pick = new ArrayList<String>();
        for (String want : TLS_12_AND_13) {
            for (String s : supported) {
                if (want.equals(s)) {
                    pick.add(want);
                    break;
                }
            }
        }
        try {
            ssl.setEnabledProtocols(pick.isEmpty()
                    ? TLS_12_ONLY : pick.toArray(new String[pick.size()]));
        } catch (Exception e) {
            try {
                ssl.setEnabledProtocols(TLS_12_ONLY);
            } catch (Exception ignored) {}
        }
    }

    private static void harden(SSLSocket ssl) {
        enableProtocols(ssl);
        String[] suites = ssl.getSupportedCipherSuites();
        if (suites == null || suites.length == 0) return;
        List<String> picked = new ArrayList<String>();
        for (String suite : suites) {
            if (suite == null) continue;
            String u = suite.toUpperCase(java.util.Locale.US);
            boolean weak = false;
            for (String mark : WEAK_CIPHER_MARKERS) {
                if (u.contains(mark)) { weak = true; break; }
            }
            if (!weak) picked.add(suite);
        }
        if (!picked.isEmpty()) {
            ssl.setEnabledCipherSuites(picked.toArray(new String[picked.size()]));
        }
    }

    private static Socket enable(Socket socket) {
        if (socket instanceof SSLSocket) harden((SSLSocket) socket);
        return socket;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return enable(delegate.createSocket()); }
    @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException {
        return enable(delegate.createSocket(s, h, p, a));
    }
    @Override public Socket createSocket(String h, int p) throws IOException { return enable(delegate.createSocket(h, p)); }
    @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
        return enable(delegate.createSocket(h, p, l, lp));
    }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { return enable(delegate.createSocket(h, p)); }
    @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int localPort) throws IOException {
        return enable(delegate.createSocket(h, p, l, localPort));
    }
}
