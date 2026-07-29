package com.solar.launcher;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.solar.launcher.net.SolarHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Read-only Wi-Fi/DHCP snapshot and explicit, user-triggered endpoint probes. */
public final class WifiDiagnostics {

    private static final String[] INTERNET_PROBES = {
            "https://www.google.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
    };
    private static final String[] GOOGLE_API_PROBES = {
            "https://www.googleapis.com/discovery/v1/apis/youtube/v3/rest"
    };

    public static final class Snapshot {
        public final boolean adapterEnabled;
        public final boolean associated;
        public final String ssid;
        public final String supplicant;
        public final int rssi;
        public final int linkSpeedMbps;
        public final int networkId;
        public final String ip;
        public final String gateway;
        public final String dns1;
        public final String dns2;
        public final int leaseSeconds;

        Snapshot(boolean adapterEnabled, boolean associated, String ssid, String supplicant,
                int rssi, int linkSpeedMbps, int networkId, String ip, String gateway,
                String dns1, String dns2, int leaseSeconds) {
            this.adapterEnabled = adapterEnabled;
            this.associated = associated;
            this.ssid = safe(ssid);
            this.supplicant = safe(supplicant);
            this.rssi = rssi;
            this.linkSpeedMbps = linkSpeedMbps;
            this.networkId = networkId;
            this.ip = safe(ip);
            this.gateway = safe(gateway);
            this.dns1 = safe(dns1);
            this.dns2 = safe(dns2);
            this.leaseSeconds = Math.max(0, leaseSeconds);
        }
    }

    public static final class TestResult {
        public final boolean associated;
        public final boolean internetReachable;
        public final boolean solarServiceReachable;
        public final boolean googleApiReachable;
        public final long elapsedMs;

        TestResult(boolean associated, boolean internetReachable,
                boolean solarServiceReachable, boolean googleApiReachable, long elapsedMs) {
            this.associated = associated;
            this.internetReachable = internetReachable;
            this.solarServiceReachable = solarServiceReachable;
            this.googleApiReachable = googleApiReachable;
            this.elapsedMs = Math.max(0L, elapsedMs);
        }
    }

    public interface Callback {
        void onComplete(TestResult result);
    }

    private WifiDiagnostics() {}

    public static Snapshot capture(Context rawContext) {
        boolean enabled = false;
        String ssid = "";
        String supplicant = "";
        int rssi = -127;
        int linkSpeed = -1;
        int networkId = -1;
        int ip = 0;
        int gateway = 0;
        int dns1 = 0;
        int dns2 = 0;
        int lease = 0;
        try {
            Context context = rawContext != null ? rawContext.getApplicationContext() : null;
            WifiManager wm = context != null
                    ? (WifiManager) context.getSystemService(Context.WIFI_SERVICE) : null;
            if (wm != null) {
                enabled = wm.isWifiEnabled() && !SolarSilentWifi.isUiHidden();
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    ssid = WifiScanFilter.displayableConnectedSsid(info.getSSID());
                    SupplicantState state = info.getSupplicantState();
                    supplicant = state != null ? state.name() : "";
                    rssi = info.getRssi();
                    linkSpeed = info.getLinkSpeed();
                    networkId = info.getNetworkId();
                    ip = info.getIpAddress();
                }
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null) {
                    if (ip == 0) ip = dhcp.ipAddress;
                    gateway = dhcp.gateway;
                    dns1 = dhcp.dns1;
                    dns2 = dhcp.dns2;
                    lease = dhcp.leaseDuration;
                }
            }
        } catch (Exception e) {
            SolarLog.w("WifiDiagnostics", "capture: " + e.getMessage());
        }
        boolean associated = enabled && networkId >= 0 && !ssid.isEmpty();
        return new Snapshot(enabled, associated, ssid, supplicant, rssi, linkSpeed,
                networkId, ipv4(ip), ipv4(gateway), ipv4(dns1), ipv4(dns2), lease);
    }

    /** Run only after a user activates "Test connection"; never a passive/background probe. */
    public static void runConnectionTest(final Context rawContext, final Callback callback) {
        final Context context = rawContext != null ? rawContext.getApplicationContext() : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long started = SystemClock.elapsedRealtime();
                Snapshot snapshot = capture(context);
                boolean internet = false;
                boolean solar = false;
                boolean googleApi = false;
                if (snapshot.associated) {
                    boolean[] probes = runEndpointProbes(BuildConfig.OTA_UPDATES_URL);
                    internet = probes[0];
                    solar = probes[1];
                    googleApi = probes[2];
                }
                final TestResult result = new TestResult(snapshot.associated,
                        internet, solar, googleApi,
                        SystemClock.elapsedRealtime() - started);
                if (callback == null) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onComplete(result);
                    }
                });
            }
        }, "WifiConnectionTest").start();
    }

    /** Three independent probes run together; the user-facing test stays bounded on dead DNS. */
    private static boolean[] runEndpointProbes(final String otaUrl) {
        final boolean[] result = new boolean[3];
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Callable<Boolean>> tasks = new ArrayList<Callable<Boolean>>(3);
        tasks.add(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return SolarHttp.probeAnyReachableQuick(INTERNET_PROBES, 3, 4);
            }
        });
        tasks.add(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return otaUrl != null && !otaUrl.isEmpty()
                        && SolarHttp.probeAnyReachableQuick(new String[] {otaUrl}, 3, 4);
            }
        });
        tasks.add(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return SolarHttp.probeAnyReachableQuick(GOOGLE_API_PROBES, 3, 4);
            }
        });
        try {
            List<Future<Boolean>> futures = executor.invokeAll(tasks, 30L, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size() && i < result.length; i++) {
                Future<Boolean> future = futures.get(i);
                if (!future.isCancelled()) {
                    try {
                        result[i] = Boolean.TRUE.equals(future.get());
                    } catch (Exception ignored) {}
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        return result;
    }

    static String ipv4(int littleEndian) {
        if (littleEndian == 0) return "—";
        return String.format(Locale.US, "%d.%d.%d.%d",
                littleEndian & 0xff,
                (littleEndian >> 8) & 0xff,
                (littleEndian >> 16) & 0xff,
                (littleEndian >> 24) & 0xff);
    }

    static String signalLabel(int rssi) {
        if (rssi <= -120) return "—";
        if (rssi >= -60) return "Strong";
        if (rssi >= -75) return "Good";
        if (rssi >= -88) return "Weak";
        return "Very weak";
    }

    static String leaseLabel(int seconds) {
        if (seconds <= 0) return "—";
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1, minutes) + "m";
    }

    private static String safe(String value) {
        return value != null && !value.isEmpty() ? value : "—";
    }
}
