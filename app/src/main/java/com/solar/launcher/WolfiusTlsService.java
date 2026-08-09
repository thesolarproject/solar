package com.solar.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.solar.launcher.tlsproxy.DnsForwarder;
import com.solar.launcher.tlsproxy.MitmKeyStoreManager;
import com.solar.launcher.tlsproxy.ShellUtils;
import com.solar.launcher.tlsproxy.TlsProxy;
import com.solar.launcher.tlsproxy.WolfiusProxy;

/**
 * Embedded Wolfius TLS 1.3 proxy service for Solar — automatic system-wide mode.
 *
 * On the rooted Y1 (API 17) / Y2 (API 19) targets this service performs all system-level
 * setup itself, with no user interaction:
 * <ol>
 *   <li>installs the bundled MITM CA into the system trust store (root remount of /system);</li>
 *   <li>starts the wolfSSL MITM proxy ({@link TlsProxy} on 127.0.0.1:7998) + DNS forwarder (:5353);</li>
 *   <li>applies iptables DNAT rules so the whole device's TCP 443 / DNS flows through the proxy,
 *       upgrading TLS 1.0/1.1 connections to TLS 1.3 on the wire.</li>
 * </ol>
 *
 * Capture is only applied once the CA is verifiably in the system trust store — otherwise the
 * service stays listener-only so existing TLS (OkHttp/Conscrypt) is never broken. All root
 * shell work (CA install, iptables) runs off the main thread; CA install retries are capped
 * and converge via connectivity changes or the next boot.
 *
 * GPLv3 — wolfSSL core ported from gohoski/Wolfius (see solar-rom/vendor/wolfius/README-SOLAR.md).
 */
public final class WolfiusTlsService extends Service {
    private static final String TAG = "WolfiusTlsService";
    private static final int NOTIFICATION_ID = 4201;
    private static final String CHAIN_NAME = "SOLAR_TLS_OUT";
    private static final String CHANNEL_ID = "solar_tls_proxy";
    private static final int CA_RETRY_DELAY_MS = 30_000;
    private static final int CA_RETRY_MAX = 3;

    private TlsProxy proxy;
    private DnsForwarder dnsForwarder;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean caTrusted;
    private volatile boolean iptablesApplied;
    private volatile boolean iptablesApplying;
    private volatile int caInstallAttempts;
    private BroadcastReceiver connectivityReceiver;
    private final Handler handler = new Handler();

    /** Start the service (idempotent). Safe on all API levels; failures are swallowed. */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context, WolfiusTlsService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureStarted failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Context app = getApplicationContext();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarTlsProxyWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "SolarTlsProxyWifiLock");
            wifiLock.acquire();
        }

        try {
            // Fast, non-blocking: loads the CA keys into wolfSSL natively.
            MitmKeyStoreManager.init(app);
            proxy = new TlsProxy(TlsProxy.LISTEN_PORT);
            proxy.start();
            dnsForwarder = new DnsForwarder();
            dnsForwarder.start();
            WolfiusProxy.currentMethod = WolfiusProxy.METHOD_IPTABLES;
            // Root shell work (CA install / iptables) happens off the main thread.
            runAutoSetup();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start TLS proxy in service", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        WolfiusProxy.currentMethod = null;
        handler.removeCallbacksAndMessages(null);
        if (connectivityReceiver != null) {
            try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) {}
            connectivityReceiver = null;
        }
        if (proxy != null) proxy.stop();
        if (dnsForwarder != null) dnsForwarder.stop();
        // iptables teardown shells out to root — never block the main thread.
        if (iptablesApplied) {
            final boolean applied = iptablesApplied;
            iptablesApplied = false;
            new Thread(new Runnable() {
                @Override public void run() {
                    if (applied) removeIptables();
                }
            }, "WolfiusIptablesTeardown").start();
        }
        stopForegroundCompat();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Worker: verify/install the CA, then enable capture once trusted.
     * All root shell work lives on this thread — never the main thread.
     */
    private void runAutoSetup() {
        new Thread(new Runnable() {
            @Override public void run() {
                ensureCaTrusted();
                if (caTrusted) {
                    applyIptables();
                    registerConnectivityRetry();
                    Log.i(TAG, "CA trusted — iptables DNAT capture active");
                } else {
                    scheduleCaRetry();
                    Log.w(TAG, "CA not yet in system trust store — listener-only until root install succeeds");
                }
            }
        }, "WolfiusCaSetup").start();
    }

    /** Install the CA if missing; only then may traffic capture be applied. Called off-main. */
    private void ensureCaTrusted() {
        caTrusted = MitmKeyStoreManager.isSystemCaInstalled(this);
        if (!caTrusted && caInstallAttempts < CA_RETRY_MAX) {
            caInstallAttempts++;
            boolean ok = MitmKeyStoreManager.installRootCa(this);
            caTrusted = ok || MitmKeyStoreManager.isSystemCaInstalled(this);
            if (ok) Log.i(TAG, "CA installed into system trust store");
        }
    }

    /** Capped retry: converges when boot races /system mount or WiFi association. */
    private void scheduleCaRetry() {
        if (caInstallAttempts >= CA_RETRY_MAX) {
            Log.w(TAG, "CA install retries exhausted — will retry on connectivity change or next boot");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (WolfiusProxy.currentMethod == null) return; // service stopped
                runAutoSetup();
            }
        }, CA_RETRY_DELAY_MS);
    }

    private void registerConnectivityRetry() {
        try {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            connectivityReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // onReceive is main-thread; re-dispatch the root work to a worker.
                    new Thread(new Runnable() {
                        @Override public void run() {
                            if (WolfiusProxy.currentMethod == null) return; // service stopped
                            if (!caTrusted) ensureCaTrusted();
                            if (caTrusted && !iptablesApplied) {
                                applyIptables();
                            }
                        }
                    }, "WolfiusConnectivityRetry").start();
                }
            };
            registerReceiver(connectivityReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "connectivity retry receiver unavailable", e);
        }
    }

    private void applyIptables() {
        if (iptablesApplying) return;
        iptablesApplying = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    removeIptables();
                    iptablesApplied = false;
                    String localIp = WolfiusProxy.getActiveLocalIpAddress();
                    if (localIp == null) {
                        Log.e(TAG, "Cannot apply DNAT rules: No active local network IP found!");
                        return;
                    }
                    executeRoot("iptables -t nat -N " + CHAIN_NAME);
                    executeRoot("iptables -t nat -A " + CHAIN_NAME + " -p 17 --dport 53 ! --sport 24000:24999 -j DNAT --to-destination " + localIp + ":5353");
                    executeRoot("iptables -t nat -A " + CHAIN_NAME + " -p 6 --dport 443 ! --sport 15000:25000 -j DNAT --to-destination " + localIp + ":7998");
                    executeRoot("iptables -t nat -I OUTPUT -j " + CHAIN_NAME);
                    iptablesApplied = true;
                    Log.i(TAG, "iptables DNAT applied for " + localIp);
                } finally {
                    iptablesApplying = false;
                }
            }
        }).start();
    }

    public static synchronized void removeIptables() {
        executeRoot("iptables -t nat -D OUTPUT -j " + CHAIN_NAME);
        executeRoot("iptables -t nat -F " + CHAIN_NAME);
        executeRoot("iptables -t nat -X " + CHAIN_NAME);
    }

    public static synchronized boolean executeRoot(String command) {
        return ShellUtils.executeRoot(command + "\n");
    }

    private void startForegroundCompat() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "startForeground unavailable", e);
        }
    }

    private Notification buildNotification() {
        Context ctx = getApplicationContext();
        String title = "Solar TLS 1.3 proxy";
        String text = caTrusted
                ? "Upgrading old TLS to TLS 1.3 system-wide"
                : "TLS 1.3 setup in progress…";
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Solar TLS 1.3 proxy", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true);
            return b.build();
        }
        Notification n = new Notification(android.R.drawable.stat_sys_upload, title, System.currentTimeMillis());
        try {
            // setLatestEventInfo was removed from the compile SDK (API 33+); reflect like Wolfius.
            java.lang.reflect.Method m = Notification.class.getMethod("setLatestEventInfo",
                    Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
            m.invoke(n, ctx, title, text, pendingIntent);
        } catch (Exception ignored) {}
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.w(TAG, "stopForeground unavailable", e);
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }
}
