package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One place for Wi-Fi connect/forget: background work, validated configuration, bounded
 * association wait, and credential-safe errors.
 */
public final class WifiConnector {

    private static final String TAG = "WifiConnector";
    private static final AtomicInteger WORK_GEN = new AtomicInteger();
    private static final long ASSOCIATION_TIMEOUT_MS = 20_000L;
    private static final long ASSOCIATION_POLL_MS = 400L;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern WPA_HEX_KEY = Pattern.compile("[0-9a-fA-F]{64}");

    public enum Failure {
        NONE,
        INVALID_SSID,
        INVALID_PASSWORD,
        WIFI_UNAVAILABLE,
        NETWORK_NOT_FOUND,
        UNSUPPORTED_SECURITY,
        CONFIG_REJECTED,
        ENABLE_REJECTED,
        AUTHENTICATION_FAILED,
        TIMEOUT,
        CANCELED,
        SYSTEM_ERROR
    }

    /** Security modes Solar can identify from an API-17 scan row. */
    public enum Security {
        OPEN,
        WPA_PERSONAL,
        WEP,
        ENTERPRISE,
        MODERN_UNSUPPORTED
    }

    /** Structured result so the wheel UI can explain failures without exposing credentials. */
    public static final class ConnectionResult {
        public final boolean success;
        public final Failure failure;

        private ConnectionResult(boolean success, Failure failure) {
            this.success = success;
            this.failure = failure != null ? failure : Failure.SYSTEM_ERROR;
        }

        public static ConnectionResult success() {
            return new ConnectionResult(true, Failure.NONE);
        }

        public static ConnectionResult failure(Failure failure) {
            return new ConnectionResult(false, failure);
        }
    }

    public interface Callback {
        void onComplete(boolean success);
    }

    public interface DetailedCallback {
        void onComplete(ConnectionResult result);
    }

    /** Quick Controls / Settings row: saved lookup runs off the UI thread. */
    public interface MenuCallback {
        /** Secured network with no password yet: open keyboard on UI thread. */
        void onNeedPassword();
        void onComplete(boolean success);
    }

    public interface DetailedMenuCallback {
        void onNeedPassword();
        void onComplete(ConnectionResult result);
    }

    private WifiConnector() {}

    /** Backward-compatible boolean callback for legacy/overlay callers. */
    public static void connect(final Context context, final String ssid, final String password,
            final boolean open, final Callback callback) {
        connectDetailed(context, ssid, password, open, new DetailedCallback() {
            @Override
            public void onComplete(ConnectionResult result) {
                notifyCallback(callback, result != null && result.success);
            }
        });
    }

    /** Connect an open or WPA-personal network and report a precise outcome. */
    public static void connectDetailed(final Context context, final String ssid,
            final String password, final boolean open, final DetailedCallback callback) {
        Failure validation = validateInput(ssid, password,
                open ? Security.OPEN : Security.WPA_PERSONAL);
        if (context == null) validation = Failure.WIFI_UNAVAILABLE;
        if (validation != Failure.NONE) {
            notifyDetailedCallback(callback, ConnectionResult.failure(validation));
            return;
        }
        final int gen = WORK_GEN.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConnectionResult result = ConnectionResult.failure(Failure.WIFI_UNAVAILABLE);
                AuthFailureMonitor authMonitor = null;
                try {
                    WifiManager wm = wifiManager(context);
                    if (wm != null) {
                        authMonitor = AuthFailureMonitor.start(context);
                        result = connectBlockingResult(wm, ssid, password,
                                open ? Security.OPEN : Security.WPA_PERSONAL,
                                gen, authMonitor);
                    }
                } catch (SecurityException denied) {
                    SolarLog.e(TAG, "connectDetailed permission denied", denied);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } catch (Exception e) {
                    SolarLog.e(TAG, "connect " + ssid, e);
                    com.solar.launcher.diag.SolarDiagFeatureLog.warn("wifi",
                            "connect_exception ssid=" + ssid);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } finally {
                    if (authMonitor != null) authMonitor.close();
                }
                if (gen != WORK_GEN.get()) return;
                if (!result.success) {
                    SolarLog.e(TAG, "connect failed ssid=" + ssid
                            + " reason=" + result.failure, null);
                }
                postDetailedResult(callback, result);
            }
        }, "WifiConnect").start();
    }

    /**
     * Legacy menu entry point. A non-open row is treated as WPA-personal because old callers did
     * not supply scan capabilities.
     */
    public static void connectFromMenu(final Context context, final String ssid,
            final boolean openNetwork, final String password, final MenuCallback callback) {
        connectFromMenuDetailed(context, ssid, openNetwork ? "" : "[WPA-PSK]",
                password, new DetailedMenuCallback() {
                    @Override
                    public void onNeedPassword() {
                        postMenuNeedPassword(callback);
                    }

                    @Override
                    public void onComplete(ConnectionResult result) {
                        postMenuComplete(callback, result != null && result.success);
                    }
                });
    }

    /**
     * Connect a scan row with its actual capabilities. Unsupported WEP/EAP/SAE networks fail
     * explicitly instead of being misconfigured as open or WPA-PSK networks.
     */
    public static void connectFromMenuDetailed(final Context context, final String ssid,
            final String capabilities, final String password,
            final DetailedMenuCallback callback) {
        Failure validation = validateSsid(ssid);
        if (context == null) validation = Failure.WIFI_UNAVAILABLE;
        if (validation != Failure.NONE) {
            postDetailedMenuComplete(callback, ConnectionResult.failure(validation));
            return;
        }
        final int gen = WORK_GEN.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConnectionResult result = ConnectionResult.failure(Failure.WIFI_UNAVAILABLE);
                boolean needPassword = false;
                AuthFailureMonitor authMonitor = null;
                try {
                    WifiManager wm = wifiManager(context);
                    if (wm != null) {
                        int netId = findSavedNetId(wm.getConfiguredNetworks(), ssid);
                        if (netId >= 0) {
                            authMonitor = AuthFailureMonitor.start(context);
                            result = enableSavedBlockingResult(
                                    wm, netId, ssid, gen, authMonitor);
                        } else {
                            Security security = securityForCapabilities(capabilities);
                            if (!isSupportedSecurity(security)) {
                                result = ConnectionResult.failure(Failure.UNSUPPORTED_SECURITY);
                            } else if (security == Security.OPEN) {
                                authMonitor = AuthFailureMonitor.start(context);
                                result = connectBlockingResult(
                                        wm, ssid, "", security, gen, authMonitor);
                            } else if (password != null && !password.isEmpty()) {
                                Failure inputFailure = validateInput(ssid, password, security);
                                if (inputFailure != Failure.NONE) {
                                    result = ConnectionResult.failure(inputFailure);
                                } else {
                                    authMonitor = AuthFailureMonitor.start(context);
                                    result = connectBlockingResult(
                                            wm, ssid, password, security, gen, authMonitor);
                                }
                            } else {
                                needPassword = true;
                            }
                        }
                    }
                } catch (SecurityException denied) {
                    SolarLog.e(TAG, "connectSaved permission denied", denied);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } catch (Exception e) {
                    SolarLog.e(TAG, "connectFromMenu " + ssid, e);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } finally {
                    if (authMonitor != null) authMonitor.close();
                }
                if (gen != WORK_GEN.get()) return;
                if (needPassword) {
                    postDetailedMenuNeedPassword(callback);
                } else {
                    if (!result.success) {
                        SolarLog.e(TAG, "connectFromMenu failed ssid=" + ssid
                                + " reason=" + result.failure, null);
                    }
                    postDetailedMenuComplete(callback, result);
                }
            }
        }, "WifiConnectMenu").start();
    }

    /** Enable a previously saved network by SSID. */
    public static void connectSaved(final Context context, final String ssid,
            final Callback callback) {
        if (context == null || validateSsid(ssid) != Failure.NONE) {
            notifyCallback(callback, false);
            return;
        }
        final int gen = WORK_GEN.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConnectionResult result = ConnectionResult.failure(Failure.NETWORK_NOT_FOUND);
                AuthFailureMonitor authMonitor = null;
                try {
                    WifiManager wm = wifiManager(context);
                    if (wm != null) {
                        int netId = findSavedNetId(wm.getConfiguredNetworks(), ssid);
                        if (netId >= 0) {
                            authMonitor = AuthFailureMonitor.start(context);
                            result = enableSavedBlockingResult(
                                    wm, netId, ssid, gen, authMonitor);
                        }
                    }
                } catch (SecurityException denied) {
                    SolarLog.e(TAG, "connectSaved permission denied", denied);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } catch (Exception e) {
                    SolarLog.e(TAG, "connectSaved " + ssid, e);
                    result = ConnectionResult.failure(Failure.SYSTEM_ERROR);
                } finally {
                    if (authMonitor != null) authMonitor.close();
                }
                if (gen != WORK_GEN.get()) return;
                if (!result.success) {
                    SolarLog.e(TAG, "connectSaved failed ssid=" + ssid
                            + " reason=" + result.failure, null);
                }
                postResult(callback, result.success);
            }
        }, "WifiConnectSaved").start();
    }

    /** Remove a saved network configuration and cancel any in-flight join. */
    public static void forget(final Context context, final String ssid,
            final Callback callback) {
        if (context == null || validateSsid(ssid) != Failure.NONE) {
            notifyCallback(callback, false);
            return;
        }
        WORK_GEN.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    WifiManager wm = wifiManager(context);
                    if (wm != null) {
                        int netId = findSavedNetId(wm.getConfiguredNetworks(), ssid);
                        if (netId >= 0) {
                            ok = wm.removeNetwork(netId);
                            saveConfigurationQuiet(wm);
                        }
                    }
                } catch (SecurityException denied) {
                    SolarLog.e(TAG, "forget permission denied", denied);
                } catch (Exception e) {
                    SolarLog.e(TAG, "forget " + ssid, e);
                }
                if (!ok) SolarLog.e(TAG, "forget failed ssid=" + ssid, null);
                postResult(callback, ok);
            }
        }, "WifiForget").start();
    }

    public static void cancelPending() {
        WORK_GEN.incrementAndGet();
    }

    static int findSavedNetId(List<WifiConfiguration> configs, String ssid) {
        if (configs == null || ssid == null) return -1;
        String quoted = quotedSsid(ssid);
        for (WifiConfiguration conf : configs) {
            if (conf.SSID != null && (conf.SSID.equals(quoted)
                    || ssid.equals(unquoteWifiString(conf.SSID)))) {
                return conf.networkId;
            }
        }
        return -1;
    }

    static String quotedSsid(String ssid) {
        return quoteWifiString(ssid);
    }

    static String quoteWifiString(String value) {
        String raw = value != null ? value : "";
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String unquoteWifiString(String value) {
        if (value == null) return "";
        String raw = value;
        boolean quoted = raw.length() >= 2 && raw.charAt(0) == '"'
                && raw.charAt(raw.length() - 1) == '"';
        if (!quoted) return raw;
        raw = raw.substring(1, raw.length() - 1);
        StringBuilder out = new StringBuilder(raw.length());
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else {
                out.append(ch);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    static String encodedWpaKey(String password) {
        String raw = password != null ? password : "";
        return WPA_HEX_KEY.matcher(raw).matches() ? raw : quoteWifiString(raw);
    }

    static Security securityForCapabilities(String capabilities) {
        String caps = capabilities != null
                ? capabilities.toUpperCase(Locale.US) : "";
        if (caps.contains("EAP") || caps.contains("IEEE8021X")) {
            return Security.ENTERPRISE;
        }
        if (caps.contains("SAE") || caps.contains("OWE") || caps.contains("WAPI")) {
            return Security.MODERN_UNSUPPORTED;
        }
        if (caps.contains("WEP")) return Security.WEP;
        if (caps.contains("PSK") || caps.contains("WPA")) return Security.WPA_PERSONAL;
        return Security.OPEN;
    }

    static boolean isSupportedSecurity(Security security) {
        return security == Security.OPEN || security == Security.WPA_PERSONAL;
    }

    static Failure validateSsid(String ssid) {
        if (ssid == null || ssid.length() == 0 || ssid.getBytes(UTF_8).length > 32) {
            return Failure.INVALID_SSID;
        }
        return Failure.NONE;
    }

    static Failure validateInput(String ssid, String password, Security security) {
        Failure ssidFailure = validateSsid(ssid);
        if (ssidFailure != Failure.NONE) return ssidFailure;
        if (!isSupportedSecurity(security)) return Failure.UNSUPPORTED_SECURITY;
        if (security == Security.WPA_PERSONAL) {
            String raw = password != null ? password : "";
            if (!WPA_HEX_KEY.matcher(raw).matches()
                    && (raw.length() < 8 || raw.length() > 63)) {
                return Failure.INVALID_PASSWORD;
            }
        }
        return Failure.NONE;
    }

    /** User-facing, credential-safe explanation shared by in-app and overlay Wi-Fi screens. */
    public static int failureMessageResId(Failure failure) {
        if (failure == null) return R.string.toast_wifi_connect_failed;
        switch (failure) {
            case INVALID_SSID:
                return R.string.toast_wifi_invalid_ssid;
            case INVALID_PASSWORD:
                return R.string.toast_wifi_invalid_password;
            case WIFI_UNAVAILABLE:
                return R.string.toast_wifi_unavailable;
            case NETWORK_NOT_FOUND:
                return R.string.toast_wifi_network_not_found;
            case UNSUPPORTED_SECURITY:
                return R.string.toast_wifi_security_unsupported;
            case CONFIG_REJECTED:
                return R.string.toast_wifi_config_rejected;
            case ENABLE_REJECTED:
                return R.string.toast_wifi_enable_rejected;
            case AUTHENTICATION_FAILED:
                return R.string.toast_wifi_auth_failed;
            case TIMEOUT:
                return R.string.toast_wifi_connect_timeout;
            case CANCELED:
                return R.string.toast_wifi_connect_canceled;
            case NONE:
            case SYSTEM_ERROR:
            default:
                return R.string.toast_wifi_connect_failed;
        }
    }

    static boolean connectBlocking(WifiManager wm, String ssid, String password,
            boolean open) throws Exception {
        return connectBlockingResult(wm, ssid, password,
                open ? Security.OPEN : Security.WPA_PERSONAL,
                -1, null).success;
    }

    private static ConnectionResult connectBlockingResult(WifiManager wm, String ssid,
            String password, Security security, int gen, AuthFailureMonitor authMonitor)
            throws Exception {
        Failure validation = validateInput(ssid, password, security);
        if (validation != Failure.NONE) return ConnectionResult.failure(validation);
        if (wm == null || !wm.isWifiEnabled()) {
            return ConnectionResult.failure(Failure.WIFI_UNAVAILABLE);
        }
        int netId;
        try {
            netId = findSavedNetId(wm.getConfiguredNetworks(), ssid);
        } catch (SecurityException denied) {
            return ConnectionResult.failure(Failure.SYSTEM_ERROR);
        }
        if (netId >= 0) {
            WifiConfiguration update = new WifiConfiguration();
            update.networkId = netId;
            update.SSID = quotedSsid(ssid);
            update.allowedKeyManagement.clear();
            if (security == Security.OPEN) {
                update.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                update.preSharedKey = null;
            } else {
                update.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                update.preSharedKey = encodedWpaKey(password);
            }
            int updatedNetId = wm.updateNetwork(update);
            if (updatedNetId < 0) {
                return ConnectionResult.failure(Failure.CONFIG_REJECTED);
            }
            netId = updatedNetId;
        } else {
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = quotedSsid(ssid);
            if (security == Security.OPEN) {
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                conf.preSharedKey = encodedWpaKey(password);
            }
            netId = wm.addNetwork(conf);
        }
        if (netId < 0) return ConnectionResult.failure(Failure.CONFIG_REJECTED);
        return enableSavedBlockingResult(wm, netId, ssid, gen, authMonitor);
    }

    static boolean enableSavedBlocking(WifiManager wm, int netId) throws Exception {
        return enableSavedBlockingResult(wm, netId, null, -1, null).success;
    }

    private static ConnectionResult enableSavedBlockingResult(WifiManager wm, int netId,
            String expectedSsid, int gen, AuthFailureMonitor authMonitor) throws Exception {
        if (wm == null || netId < 0 || !wm.isWifiEnabled()) {
            return ConnectionResult.failure(Failure.WIFI_UNAVAILABLE);
        }
        wm.disconnect();
        boolean enabled = wm.enableNetwork(netId, true);
        if (!enabled) return ConnectionResult.failure(Failure.ENABLE_REJECTED);
        boolean reconnectAccepted = wm.reconnect();
        saveConfigurationQuiet(wm);
        if (!reconnectAccepted) return ConnectionResult.failure(Failure.ENABLE_REJECTED);
        return awaitAssociation(wm, netId, expectedSsid, gen, authMonitor);
    }

    private static ConnectionResult awaitAssociation(WifiManager wm, int netId,
            String expectedSsid, int gen, AuthFailureMonitor authMonitor) {
        long deadline = SystemClock.elapsedRealtime() + ASSOCIATION_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (gen >= 0 && gen != WORK_GEN.get()) {
                return ConnectionResult.failure(Failure.CANCELED);
            }
            if (authMonitor != null && authMonitor.failed.get()) {
                return ConnectionResult.failure(Failure.AUTHENTICATION_FAILED);
            }
            try {
                WifiInfo info = wm.getConnectionInfo();
                if (isExpectedAssociation(info, netId, expectedSsid)) {
                    return ConnectionResult.success();
                }
            } catch (Exception ignored) {}
            SystemClock.sleep(ASSOCIATION_POLL_MS);
        }
        if (authMonitor != null && authMonitor.failed.get()) {
            return ConnectionResult.failure(Failure.AUTHENTICATION_FAILED);
        }
        return ConnectionResult.failure(Failure.TIMEOUT);
    }

    static boolean isExpectedAssociation(int actualNetId, String actualSsid,
            String supplicantState, int expectedNetId, String expectedSsid) {
        if (actualNetId != expectedNetId) return false;
        if (expectedSsid != null && expectedSsid.length() > 0
                && !expectedSsid.equals(unquoteWifiString(actualSsid))) {
            return false;
        }
        return "COMPLETED".equals(supplicantState);
    }

    private static boolean isExpectedAssociation(WifiInfo info, int expectedNetId,
            String expectedSsid) {
        if (info == null) return false;
        SupplicantState state = info.getSupplicantState();
        return isExpectedAssociation(info.getNetworkId(), info.getSSID(),
                state != null ? state.name() : "", expectedNetId, expectedSsid);
    }

    private static void saveConfigurationQuiet(WifiManager wm) {
        try {
            wm.saveConfiguration();
        } catch (Exception e) {
            SolarLog.w(TAG, "saveConfiguration: " + e.getMessage());
        }
    }

    private static WifiManager wifiManager(Context context) {
        return (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    private static void postResult(final Callback callback, final boolean ok) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                notifyCallback(callback, ok);
            }
        });
    }

    private static void postDetailedResult(final DetailedCallback callback,
            final ConnectionResult result) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                notifyDetailedCallback(callback, result);
            }
        });
    }

    private static void postMenuComplete(final MenuCallback callback, final boolean ok) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onComplete(ok);
            }
        });
    }

    private static void postMenuNeedPassword(final MenuCallback callback) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onNeedPassword();
            }
        });
    }

    private static void postDetailedMenuComplete(final DetailedMenuCallback callback,
            final ConnectionResult result) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onComplete(result);
            }
        });
    }

    private static void postDetailedMenuNeedPassword(final DetailedMenuCallback callback) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onNeedPassword();
            }
        });
    }

    private static void notifyCallback(Callback callback, boolean ok) {
        if (callback != null) callback.onComplete(ok);
    }

    private static void notifyDetailedCallback(DetailedCallback callback,
            ConnectionResult result) {
        if (callback != null) callback.onComplete(result);
    }

    /** Temporary receiver gives a precise auth error while the bounded association wait runs. */
    private static final class AuthFailureMonitor {
        final AtomicBoolean failed = new AtomicBoolean(false);
        private final Context context;
        private final BroadcastReceiver receiver;
        private boolean registered;

        private AuthFailureMonitor(Context context) {
            this.context = context;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (intent == null
                            || !WifiManager.SUPPLICANT_STATE_CHANGED_ACTION
                            .equals(intent.getAction())) {
                        return;
                    }
                    int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                    if (error == WifiManager.ERROR_AUTHENTICATING) failed.set(true);
                }
            };
        }

        static AuthFailureMonitor start(Context rawContext) {
            if (rawContext == null) return null;
            Context app = rawContext.getApplicationContext();
            AuthFailureMonitor monitor = new AuthFailureMonitor(app);
            try {
                IntentFilter filter =
                        new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(monitor.receiver, filter,
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(monitor.receiver, filter);
                }
                monitor.registered = true;
            } catch (Exception e) {
                SolarLog.w(TAG, "auth monitor unavailable: " + e.getMessage());
            }
            return monitor;
        }

        void close() {
            if (!registered) return;
            registered = false;
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
        }
    }

    static void selfCheck() {
        if (!"\"Cafe\"".equals(quotedSsid("Cafe"))) throw new AssertionError("quotedSsid");
        if (findSavedNetId(null, "x") != -1) throw new AssertionError("null configs");
        if (securityForCapabilities("[WPA2-PSK-CCMP][ESS]")
                != Security.WPA_PERSONAL) {
            throw new AssertionError("wpa security");
        }
    }
}
