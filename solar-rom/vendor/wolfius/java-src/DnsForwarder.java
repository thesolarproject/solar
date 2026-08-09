package io.github.gohoski.wolfius;

import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by Gleb on 07.07.2026.
 */
class DnsForwarder implements Runnable {
    private static final String TAG = "DnsForwarder";
    private static final int LISTEN_PORT = 5353;
    private DatagramSocket serverSocket;
    private boolean isRunning = true;
    private final String upstreamDns;

    private static final int CACHE_SIZE = 500;
    private static final Map<String, String> ipToHostMap = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            }
    );

    private static int dnsNextPort = 24000;
    private static synchronized int getNextDnsPort() {
        int port = dnsNextPort;
        dnsNextPort++;
        if (dnsNextPort > 24999) {
            dnsNextPort = 24000;
        }
        return port;
    }

    DnsForwarder() {
        this.upstreamDns = getSystemDns();
    }

    static String getHostForIp(String ip) {
        return ipToHostMap.get(ip);
    }

    public void start() {
        new Thread(this, "DnsForwarder").start();
    }

    public void stop() {
        isRunning = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new DatagramSocket(null);
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), LISTEN_PORT));

            byte[] buffer = new byte[1024];
            Log.i(TAG, "DNS forwarder listening on 0.0.0.0:" + LISTEN_PORT + ", upstream " + upstreamDns);

            while (isRunning) {
                DatagramPacket clientPacket = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(clientPacket);

                byte[] queryData = new byte[clientPacket.getLength()];
                System.arraycopy(buffer, 0, queryData, 0, clientPacket.getLength());

                new Thread(new DnsHandler(clientPacket, queryData)).start();
            }
        } catch (IOException e) {
            if (isRunning) {
                Log.e(TAG, "DNS socket exception!!", e);
            }
        }
    }

    private class DnsHandler implements Runnable {
        private final DatagramPacket clientPacket;
        private final byte[] queryData;

        DnsHandler(DatagramPacket clientPacket, byte[] queryData) {
            this.clientPacket = clientPacket;
            this.queryData = queryData;
        }

        @Override
        public void run() {
            DatagramSocket upstreamSocket = null;
            final String domain = parseDomain(queryData);
            try {
                int safePort = getNextDnsPort();
                upstreamSocket = new DatagramSocket(null);
                upstreamSocket.setReuseAddress(true);

                InetAddress bindAddr = null;
                if (!"pptp".equals(ProxyService.currentMethod)) {
                    String physicalIp = ProxyService.getActiveLocalIpAddress();
                    if (physicalIp != null) {
                        try {
                            bindAddr = InetAddress.getByName(physicalIp);
                        } catch (Exception ignored) {}
                    }
                }
                upstreamSocket.bind(new InetSocketAddress(bindAddr, safePort));
                VpnCompatHelper.protect(upstreamSocket);
                upstreamSocket.setSoTimeout(2000);
                java.util.List<InetAddress> targets = new java.util.ArrayList<InetAddress>();
                if (upstreamDns != null && !upstreamDns.equals("127.0.0.1") && !upstreamDns.equals("0.0.0.0")) {
                    try {
                        targets.add(InetAddress.getByName(upstreamDns));
                    } catch (Exception ignored) {}
                }
                try { targets.add(InetAddress.getByName("8.8.8.8")); } catch (Exception ignored) {}
                try { targets.add(InetAddress.getByName("8.8.4.4")); } catch (Exception ignored) {}
                try { targets.add(InetAddress.getByName("1.1.1.1")); } catch (Exception ignored) {}
                try { targets.add(InetAddress.getByName("1.0.0.1")); } catch (Exception ignored) {}
                for (InetAddress target : targets) {
                    DatagramPacket upstreamPacket = new DatagramPacket(queryData, queryData.length, target, 53);
                    upstreamSocket.send(upstreamPacket);
                }
                byte[] responseBuffer = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                upstreamSocket.receive(responsePacket);
                cacheDns(responseBuffer, responsePacket.getLength(), domain);
                DatagramPacket clientResponse = new DatagramPacket(
                        responseBuffer, responsePacket.getLength(),
                        clientPacket.getAddress(), clientPacket.getPort()
                );
                serverSocket.send(clientResponse);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (upstreamSocket != null) {
                    upstreamSocket.close();
                }
            }
        }
    }

    static String parseDomain(byte[] data) {
        if (data.length < 13) return null;
        try {
            int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            if (qdCount <= 0) return null;

            int pos = 12;
            StringBuilder domain = new StringBuilder();
            int jumps = 0;

            while (pos < data.length) {
                int len = data[pos++] & 0xFF;
                if (len == 0) break;

                if ((len & 0xC0) == 0xC0) {
                    if (pos >= data.length) return null;
                    int offset = ((len & 0x3F) << 8) | (data[pos++] & 0xFF);
                    pos = offset;
                    jumps++;
                    if (jumps > 5) return null;
                    continue;
                }

                if (pos + len > data.length) return null;
                if (domain.length() != 0) {
                    domain.append(".");
                }
                for (int i = 0; i < len; i++) {
                    domain.append((char) data[pos++]);
                }
            }
            return domain.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static void cacheDns(byte[] data, int length, String domain) {
        if (domain == null || domain.length() == 0 || length < 12) return;
        try {
            int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int anCount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            if (anCount <= 0) return;

            int pos = 12;
            for (int q = 0; q < qdCount; q++) {
                while (pos < length) {
                    int len = data[pos++] & 0xFF;
                    if (len == 0) break;
                    if ((len & 0xC0) == 0xC0) {
                        pos++;
                        break;
                    }
                    pos += len;
                }
                pos += 4;
            }

            for (int a = 0; a < anCount; a++) {
                if (pos >= length) break;
                if ((data[pos] & 0xC0) == 0xC0) {
                    pos += 2;
                } else {
                    while (pos < length) {
                        int len = data[pos++] & 0xFF;
                        if (len == 0) break;
                        if ((len & 0xC0) == 0xC0) {
                            pos++;
                            break;
                        }
                        pos += len;
                    }
                }

                if (pos + 10 > length) break;
                int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdLength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                pos += 10;

                if (pos + rdLength > length) break;

                if (type == 1 && rdLength == 4) {
                    int ip1 = data[pos] & 0xFF;
                    int ip2 = data[pos + 1] & 0xFF;
                    int ip3 = data[pos + 2] & 0xFF;
                    int ip4 = data[pos + 3] & 0xFF;
                    String ip = ip1 + "." + ip2 + "." + ip3 + "." + ip4;
                    ipToHostMap.put(ip, domain);
                    Log.i(TAG, "Cached DNS mapping " + ip + " -> " + domain);
                }
                pos += rdLength;
            }
        } catch (Exception ignored) {}
    }

    private String getSystemDns() {
        String dns = "8.8.8.8";
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            String prop = (String) get.invoke(null, "net.dns1");
            if (prop != null && prop.length() != 0 && !prop.equals("127.0.0.1") && !prop.equals("0.0.0.0") && !prop.equals("10.0.0.1")) {
                dns = prop;
            } else {
                String prop2 = (String) get.invoke(null, "net.dns2");
                if (prop2 != null && prop2.length() != 0 && !prop2.equals("127.0.0.1") && !prop2.equals("0.0.0.0") && !prop2.equals("10.0.0.1")) {
                    dns = prop2;
                }
            }
        } catch (Exception ignored) {}
        return dns;
    }
}