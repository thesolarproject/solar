package io.github.gohoski.wolfius;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Gleb on 12.07.2026.
 */

public class WolfiusVpnService extends VpnService {
    private static final String TAG = "WolfiusVpnService";
    public static final String ACTION_DISCONNECT = "io.github.gohoski.wolfius.ACTION_DISCONNECT";

    private Thread mThread;
    private Thread mLwipThread;
    private ParcelFileDescriptor mInterface;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;

    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private TlsProxy proxy;
    private DnsForwarder dnsForwarder;

    private static volatile boolean isLwipActive = false;
    private static volatile int activeSessionId = 0;
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService dnsExecutor = Executors.newFixedThreadPool(8);

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WolfiusVpnService onCreate called");

        // Dynamically resolve system DNS upstream
        ProxyService.originalDns = ProxyService.getSystemDns();
        Log.i(TAG, "Captured system DNS in VPN process: " + ProxyService.originalDns);

        VpnCompatHelper.registerProtector(new VpnCompatHelper.SocketProtector() {
            @Override
            public void protect(Socket socket) {
                WolfiusVpnService.this.protect(socket);
            }

            @Override
            public void protect(DatagramSocket socket) {
                WolfiusVpnService.this.protect(socket);
            }

            @Override
            public void protect(int fd) {
                WolfiusVpnService.this.protect(fd);
            }
        });

        // Initialize Wakelocks inside the VPN process
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WolfiusVpnWakeLock");
            wakeLock.acquire();
        }

        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "WolfiusVpnWifiLock");
            wifiLock.acquire();
        }

        // Initialize Proxy components locally within the VPN process
        try {
            MitmKeyStoreManager.init(getApplicationContext());
            proxy = new TlsProxy(7998);
            proxy.start();
            dnsForwarder = new DnsForwarder();
            dnsForwarder.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start proxy structures locally inside WolfiusVpnService", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "WolfiusVpnService onStartCommand called");
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnectVpn();
            return START_NOT_STICKY;
        }

        // Displays the "Proxy service running" status notification
        showNotification();

        if (mThread == null || !mThread.isAlive()) {
            startVpn();
        }
        return START_STICKY;
    }

    private void showNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

            android.app.Notification.Builder builder = new android.app.Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentTitle(getString(R.string.service))
                    .setContentText(getString(R.string.service_desc))
                    .setTicker(getString(R.string.service_active))
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pendingIntent);

            android.app.Notification notification;
            if (SettingsActivity.SDK >= 16) {
                notification = builder.build();
            } else {
                notification = builder.getNotification();
            }

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Error displaying foreground notification", e);
        }
    }

    private void startVpn() {
        Log.i(TAG, "Starting VPN background worker thread");
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final int currentSession;
                synchronized (WolfiusVpnService.class) {
                    activeSessionId++;
                    currentSession = activeSessionId;
                }
                Log.i(TAG, "Starting VPN session #" + currentSession);

                try {
                    VpnService.Builder builder = new VpnService.Builder();
                    builder.setSession("Wolfius");
                    builder.setMtu(1500);

                    builder.addAddress("10.0.0.2", 24);
                    builder.addRoute("0.0.0.0", 0);
                    builder.addDnsServer("10.0.0.1");

                    mInterface = builder.establish();
                    if (mInterface == null) {
                        Log.e(TAG, "Failed to establish VPN interface (establish() returned null)");
                        return;
                    }

                    Log.i(TAG, "VPN interface successfully established!");

                    int writeFd = mInterface.getFd();
                    LwipBridge.loadLibrary();

                    // Synchronize and verify that this session is still active before initializing LwIP
                    synchronized (WolfiusVpnService.class) {
                        if (currentSession != activeSessionId) {
                            Log.w(TAG, "Session #" + currentSession + " superseded before LwIP init, aborting.");
                            return;
                        }
                        Log.i(TAG, "Initializing native LwIP with write FD " + writeFd + "...");
                        LwipBridge.nativeInitLwIP(writeFd);
                        isLwipActive = true;
                        Log.i(TAG, "Native LwIP initialized successfully!");
                    }

                    final int sessionForLwip = currentSession;
                    mLwipThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Log.i(TAG, "LwIP worker thread started for session #" + sessionForLwip);
                            while (mInterface != null && sessionForLwip == activeSessionId) {
                                LwipBridge.nativeProcessLwIPPackets();
                            }
                            Log.i(TAG, "LwIP worker thread stopping for session #" + sessionForLwip);
                        }
                    }, "WolfiusLwipWorker");
                    mLwipThread.start();

                    mInputStream = new FileInputStream(mInterface.getFileDescriptor());
                    mOutputStream = new FileOutputStream(mInterface.getFileDescriptor());

                    byte[] buffer = new byte[32768];
                    Log.i(TAG, "Entering packet read loop...");
                    while (mInterface != null && currentSession == activeSessionId) {
                        int length = mInputStream.read(buffer);
                        if (length > 0) {
                            handlePacket(buffer, length, mOutputStream, currentSession);
                        } else if (length == 0) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                break;
                            }
                        } else {
                            Log.w(TAG, "TUN file descriptor reached EOF, breaking loop");
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in VPN packet loop for session #" + currentSession, e);
                } finally {
                    Log.i(TAG, "VPN worker thread stopping, terminating lwIP for session #" + currentSession);
                    synchronized (WolfiusVpnService.class) {
                        // Only let the active, non-superseded session stop the LwIP stack
                        if (currentSession == activeSessionId && isLwipActive && LwipBridge.isLoaded()) {
                            try {
                                LwipBridge.nativeStopLwIP();
                            } catch (Exception e) {
                                Log.e(TAG, "Error stopping LwIP in finally", e);
                            }
                            isLwipActive = false;
                        }
                    }
                }
            }
        }, "WolfiusVpnLoop");
        mThread.start();
    }

    private void handlePacket(byte[] packet, int length, final FileOutputStream out, final int currentSession) {
        if (currentSession != activeSessionId) return;
        if (length < 20) return;

        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) return;

        int ihl = (packet[0] & 0x0F) * 4;
        int protocol = packet[9] & 0xFF;

        if (protocol == 17) { // UDP
            final int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
            final int destPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            int udpLen = ((packet[ihl + 4] & 0xFF) << 8) | (packet[ihl + 5] & 0xFF);

            if (destPort == 53) { // DNS Query
                int payloadLen = udpLen - 8;
                if (payloadLen <= 0 || ihl + 8 + payloadLen > length) return;

                final byte[] dnsPayload = new byte[payloadLen];
                System.arraycopy(packet, ihl + 8, dnsPayload, 0, payloadLen);

                final byte[] ipSrc = new byte[4];
                final byte[] ipDst = new byte[4];
                System.arraycopy(packet, 12, ipDst, 0, 4);
                System.arraycopy(packet, 16, ipSrc, 0, 4);

                dnsExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (currentSession != activeSessionId) return;
                        byte[] response = resolveDns(dnsPayload);
                        if (response != null) {
                            if (currentSession != activeSessionId) return;
                            try {
                                String domain = DnsForwarder.parseDomain(dnsPayload);
                                if (domain != null) {
                                    DnsForwarder.cacheDns(response, response.length, domain);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed parsing/caching DNS response inside VPN Service", e);
                            }

                            byte[] responsePacket = buildUdpIpPacket(ipSrc, ipDst, (short) 53, (short) srcPort, response);
                            synchronized (out) {
                                try {
                                    if (currentSession == activeSessionId) {
                                        out.write(responsePacket);
                                        out.flush();
                                    }
                                } catch (IOException e) {
                                    Log.e(TAG, "Failed to write DNS response back to TUN", e);
                                }
                            }
                        }
                    }
                });
            }
        } else if (protocol == 6) { // TCP
            if (currentSession == activeSessionId) {
                LwipBridge.nativeInputPacket(packet, length);
            }
        }
    }

    private byte[] resolveDns(byte[] query) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(3000);

            InetAddress upstreamDns = InetAddress.getByName(ProxyService.originalDns);
            DatagramPacket outPacket = new DatagramPacket(query, query.length, upstreamDns, 53);
            socket.send(outPacket);

            byte[] buffer = new byte[1024];
            DatagramPacket inPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(inPacket);

            byte[] response = new byte[inPacket.getLength()];
            System.arraycopy(buffer, 0, response, 0, inPacket.getLength());
            return response;
        } catch (Exception e) {
            Log.e(TAG, "DNS resolution failed inside VPNService using DNS server: " + ProxyService.originalDns, e);
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private byte[] buildUdpIpPacket(byte[] srcIp, byte[] destIp, short srcPort, short destPort, byte[] payload) {
        int ipLength = 20 + 8 + payload.length;
        byte[] packet = new byte[ipLength];

        packet[0] = 0x45;
        packet[1] = 0x00;
        packet[2] = (byte) ((ipLength >> 8) & 0xFF);
        packet[3] = (byte) (ipLength & 0xFF);
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x40;
        packet[7] = 0x00;
        packet[8] = 0x40;
        packet[9] = 17;
        packet[10] = 0x00;
        packet[11] = 0x00;
        System.arraycopy(srcIp, 0, packet, 12, 4);
        System.arraycopy(destIp, 0, packet, 16, 4);

        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int checksum = ~sum & 0xFFFF;
        packet[10] = (byte) ((checksum >> 8) & 0xFF);
        packet[11] = (byte) (checksum & 0xFF);

        int udpOffset = 20;
        packet[udpOffset] = (byte) ((srcPort >> 8) & 0xFF);
        packet[udpOffset + 1] = (byte) (srcPort & 0xFF);
        packet[udpOffset + 2] = (byte) ((destPort >> 8) & 0xFF);
        packet[udpOffset + 3] = (byte) (destPort & 0xFF);
        int udpLen = 8 + payload.length;
        packet[udpOffset + 4] = (byte) ((udpLen >> 8) & 0xFF);
        packet[udpOffset + 5] = (byte) (udpLen & 0xFF);
        packet[udpOffset + 6] = 0x00;
        packet[udpOffset + 7] = 0x00;

        System.arraycopy(payload, 0, packet, 28, payload.length);
        return packet;
    }

    private void disconnectVpn() {
        Log.i(TAG, "disconnectVpn called");

        boolean stopLwip = false;
        synchronized (WolfiusVpnService.class) {
            // Invalidate the current session immediately to stop old threads from executing cleanup
            activeSessionId++;
            if (isLwipActive) {
                stopLwip = true;
                isLwipActive = false;
            }
        }

        // 1. Terminate the native LwIP stack strictly ONCE if active (prevents double-stop native hang)
        if (stopLwip && LwipBridge.isLoaded()) {
            try {
                LwipBridge.nativeStopLwIP();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping LwIP inside disconnectVpn", e);
            }
        }

        // 2. Shut down proxy components
        if (proxy != null) {
            try { proxy.stop(); } catch (Exception ignored) {}
            proxy = null;
        }
        if (dnsForwarder != null) {
            try { dnsForwarder.stop(); } catch (Exception ignored) {}
            dnsForwarder = null;
        }

        // 3. Release Locks held within the VPN process
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }

        // 4. Explicitly close Java Streams to release Java-level FD references
        if (mInputStream != null) {
            try {
                mInputStream.close();
            } catch (IOException ignored) {}
            mInputStream = null;
        }
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (IOException ignored) {}
            mOutputStream = null;
        }

        // 5. Join the LwIP thread to make sure it is fully dead
        if (mLwipThread != null) {
            mLwipThread.interrupt();
            try {
                mLwipThread.join(1000);
            } catch (InterruptedException ignored) {}
            mLwipThread = null;
        }

        // 6. Join the packet read loop thread to make sure it is fully dead
        if (mThread != null) {
            mThread.interrupt();
            try {
                mThread.join(1000);
            } catch (InterruptedException ignored) {}
            mThread = null;
        }

        // 7. Close the interface descriptor
        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            mInterface = null;
        }

        // 8. Stop the foreground notification status
        stopForeground(true);

        stopSelf();
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "onRevoke called by system");
        disconnectVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "WolfiusVpnService onDestroy called");
        disconnectVpn();
        dnsExecutor.shutdownNow();
        VpnCompatHelper.registerProtector(null);
        super.onDestroy();

        // Clean termination of the separate :vpn process to clear dirty native LwIP state.
        // MainActivity runs in the default process and is completely unaffected.
        Log.i(TAG, "Exiting separate :vpn process to clear native memory state...");
        System.exit(0);
    }
}