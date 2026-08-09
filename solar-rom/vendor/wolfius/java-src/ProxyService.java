package io.github.gohoski.wolfius;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Created by Gleb on 23.06.2026.
 */

public class ProxyService extends Service {
    private static final String TAG = "ProxyService";
    private static final int NOTIFICATION_ID = 1001;
    public static volatile boolean isServiceRunning = false;
    private TlsProxy proxy;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Method mStartForeground;
    private Method mStopForeground;
    private Method mSetForeground;
    private final Object[] mStartForegroundArgs = new Object[2];
    private final Object[] mStopForegroundArgs = new Object[1];
    public static volatile String currentMethod = null;
    private static final String CHAIN_NAME = "WOLFIUS_OUT";
    private DnsForwarder dnsForwarder;
    public static volatile String originalDns = "8.8.8.8";

    private SharedPreferences getPrefs() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        originalDns = getSystemDns();
        Log.i(TAG, "Captured system DNS " + originalDns);

        SharedPreferences prefs = getPrefs();
        currentMethod = prefs.getString(SettingsActivity.KEY_METHOD, SettingsActivity.METHOD_ROOT);

        try {
            mStartForeground = getClass().getMethod("startForeground", new Class[] { int.class, Notification.class });
            mStopForeground = getClass().getMethod("stopForeground", new Class[] { boolean.class });
        } catch (NoSuchMethodException e) {
            mStartForeground = mStopForeground = null;
        }

        try {
            mSetForeground = getClass().getMethod("setForeground", new Class[] { boolean.class });
        } catch (NoSuchMethodException e) {
            mSetForeground = null;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WolfiusProxyWakeLock");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "WolfiusProxyWifiLock");
        wifiLock.acquire();

        try {
            MitmKeyStoreManager.init(getApplicationContext());
            proxy = new TlsProxy(7998);
            proxy.start();
            dnsForwarder = new DnsForwarder();
            dnsForwarder.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start proxy in service", e);
        }

        if (SettingsActivity.METHOD_ROOT.equals(currentMethod))
            applyIptables();
        else if (SettingsActivity.METHOD_PPTP.equals(currentMethod))
            startPptpVpn();
        else if (SettingsActivity.METHOD_VPNSERVICE.equals(currentMethod))
            VpnCompatHelper.startVpnService(this);
    }

    public static String getSystemDns() {
        String dns = "8.8.8.8";
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            String prop = (String) get.invoke(null, "net.dns1");
            if (prop != null && prop.length() != 0 && !prop.equals("127.0.0.1") && !prop.equals("0.0.0.0") && !prop.equals("10.0.0.1"))
                dns = prop;
            else {
                String prop2 = (String) get.invoke(null, "net.dns2");
                if (prop2 != null && prop2.length() != 0 && !prop2.equals("127.0.0.1") && !prop2.equals("0.0.0.0") && !prop2.equals("10.0.0.1"))
                    dns = prop2;
            }
        } catch (Exception ignored) {}
        return dns;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleStart(intent, startId);
        return START_STICKY;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        handleStart(intent, startId);
    }

    private void handleStart(Intent intent, int startId) {
        Notification notification = new Notification(
                android.R.drawable.stat_sys_phone_call,
                getString(R.string.service_active),
                System.currentTimeMillis()
        );
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        try {
            Method setLatestEventInfo = Notification.class.getMethod("setLatestEventInfo",
                    Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
            setLatestEventInfo.invoke(notification, this, getString(R.string.service), getString(R.string.service_desc), pendingIntent);
        } catch (Exception e) {
            Log.e(TAG, "Could not set notification details", e);
        }

        startForegroundCompat(NOTIFICATION_ID, notification);
    }

    private void startForegroundCompat(int id, Notification notification) {
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = id;
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (Exception e) {
                Log.e(TAG, "Error invoking startForeground", e);
            }
        } else {
            if (mSetForeground != null) {
                try {
                    mSetForeground.invoke(this, new Object[] { Boolean.TRUE });
                } catch (Exception e) {
                    Log.e(TAG, "Error invoking setForeground", e);
                }
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id, notification);
        }
    }

    private void stopForegroundCompat(boolean removeNotification) {
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = removeNotification;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (Exception e) {
                Log.e(TAG, "Error invoking stopForeground", e);
            }
        } else {
            if (mSetForeground != null) {
                try {
                    mSetForeground.invoke(this, new Object[] { Boolean.FALSE });
                } catch (Exception e) {
                    Log.e(TAG, "Error invoking setForeground", e);
                }
            }
            if (removeNotification) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(NOTIFICATION_ID);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        stopForegroundCompat(true);
        if (proxy != null) proxy.stop();
        if (dnsForwarder != null) dnsForwarder.stop();
        if (SettingsActivity.METHOD_ROOT.equals(currentMethod))
            removeIptables();
        else if (SettingsActivity.METHOD_PPTP.equals(currentMethod))
            stopPptpBinary();
        else if (SettingsActivity.METHOD_VPNSERVICE.equals(currentMethod))
            VpnCompatHelper.stopVpnService(this);
        currentMethod = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static String getActiveLocalIpAddress() {
        try {
            java.util.List<java.net.NetworkInterface> interfaces =
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                // Skip loopback and virtual/VPN interfaces
                if (name.startsWith("ppp") || name.startsWith("tun") || name.startsWith("tap") || name.startsWith("lo")) {
                    continue;
                }
                java.util.List<java.net.InetAddress> addrs =
                        java.util.Collections.list(intf.getInetAddresses());
                for (java.net.InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr.indexOf(':') < 0) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving active local IP address", e);
        }
        return null;
    }

    private void applyIptables() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                removeIptables();
                String localIp = getActiveLocalIpAddress();
                if (localIp == null) {
                    Log.e(TAG, "Cannot apply DNAT rules: No active local network IP found!");
                    return;
                }
                executeRoot("iptables -t nat -N " + CHAIN_NAME);
                executeRoot("iptables -t nat -A " + CHAIN_NAME + " -p 17 --dport 53 ! --sport 24000:24999 -j DNAT --to-destination " + localIp + ":5353");
                executeRoot("iptables -t nat -A " + CHAIN_NAME + " -p 6 --dport 443 ! --sport 15000:25000 -j DNAT --to-destination " + localIp + ":7998");
                executeRoot("iptables -t nat -I OUTPUT -j " + CHAIN_NAME);
            }
        }).start();
    }

    public static synchronized void removeIptables() {
        executeRoot("iptables -t nat -D OUTPUT -j " + CHAIN_NAME);
        executeRoot("iptables -t nat -F " + CHAIN_NAME);
        executeRoot("iptables -t nat -X " + CHAIN_NAME);
    }

    private void startPptpVpn() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File baseDataDir = getFilesDir().getParentFile();
                java.io.File libDir = new java.io.File(baseDataDir, "lib");
                java.io.File binaryFile = new java.io.File(libDir, "libpptp_vpn.so");

                if (binaryFile.exists()) {
                    String binaryPath = binaryFile.getAbsolutePath();

                    // Disable reverse path filtering globally across all interfaces
                    executeRoot("for f in /proc/sys/net/ipv4/conf/*/rp_filter; do echo 0 > $f; done");

                    executeRoot("chmod 755 " + binaryPath);
                    executeRoot(binaryPath + " &");
                    Log.i(TAG, "Started pptp_vpn from " + binaryPath);
                } else {
                    Log.e(TAG, "Unable to initiate pptp_vpn. Native binary not found at: " + binaryFile.getAbsolutePath());
                }
            }
        }).start();
    }

    private void stopPptpBinary() {
        executeRoot("for p in /proc/[0-9]*; do " +
                "  cmd=$(cat \"$p/cmdline\" 2>/dev/null); " +
                "  case \"$cmd\" in " +
                "    *pptp_vpn*) " +
                "      pid=${p##*/}; " +
                "      kill -9 \"$pid\" 2>/dev/null; " +
                "      ;; " +
                "  esac; " +
                "done");
    }

    public static synchronized boolean executeRoot(String command) {
        return ShellUtils.executeRoot(command + "\n");
    }
}