package com.solar.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Wi-Fi scan list for the system overlay — open/saved connect stays in-overlay; secured nets toast.
 */
public final class OverlayWifiTierHelper {

    public static final class Row {
        public final String label;
        public final String stateText;
        public final boolean header;
        /** Non-null for SSID rows the user can activate. */
        public final String ssid;
        public final boolean toggleRow;

        Row(String label, String stateText, boolean header, String ssid, boolean toggleRow) {
            this.label = label;
            this.stateText = stateText;
            this.header = header;
            this.ssid = ssid;
            this.toggleRow = toggleRow;
        }
    }

    private static final int MAX_SCANNED = 20;

    private OverlayWifiTierHelper() {}

    @SuppressLint("MissingPermission")
    public static void requestScan(Context ctx) {
        try {
            WifiManager wm = wifi(ctx);
            if (wm != null && wm.isWifiEnabled()) {
                wm.startScan();
            }
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    public static List<Row> buildRows(Context ctx) {
        ArrayList<Row> rows = new ArrayList<Row>();
        WifiManager wm = wifi(ctx);
        // Silent NTP/diag wake: report Off so overlay quick Wi‑Fi matches main UI.
        boolean on = wm != null && wm.isWifiEnabled() && !SolarSilentWifi.isUiHidden();
        rows.add(new Row(ctx.getString(on ? R.string.context_wifi_on : R.string.context_wifi_off),
                null, false, null, true));
        if (wm == null || !on) {
            return rows;
        }
        TreeSet<String> connected = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> scanned = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        try {
            List<ScanResult> results = wm.getScanResults();
            String connectedSsid = connectedSsid(wm);
            if (results != null) {
                for (ScanResult result : results) {
                    if (result.SSID == null || result.SSID.isEmpty()
                            || WifiScanFilter.isHiddenSsid(result.SSID)) {
                        continue;
                    }
                    if (!connectedSsid.isEmpty() && result.SSID.equals(connectedSsid)) {
                        connected.add(result.SSID);
                    } else {
                        scanned.add(result.SSID);
                    }
                }
            }
            if (!connectedSsid.isEmpty() && connected.isEmpty()) {
                connected.add(connectedSsid);
            }
        } catch (Exception ignored) {}

        if (connected.isEmpty() && scanned.isEmpty()) {
            rows.add(new Row(ctx.getString(R.string.context_wifi_scanning), null, false, null, false));
            return rows;
        }
        if (!connected.isEmpty()) {
            rows.add(new Row(ctx.getString(R.string.context_wifi_connected), null, true, null, false));
            for (String ssid : connected) {
                rows.add(new Row(ssid, null, false, ssid, false));
            }
        }
        if (!scanned.isEmpty()) {
            rows.add(new Row(ctx.getString(R.string.status_wifi_networks), null, true, null, false));
            int count = 0;
            for (String ssid : scanned) {
                if (count >= MAX_SCANNED) break;
                rows.add(new Row(ssid, null, false, ssid, false));
                count++;
            }
        }
        return rows;
    }

    /** Connect from overlay list — {@code onPasswordHandoff} opens Solar keyboard when credentials are required. */
    public static void connect(Context ctx, final String ssid, final Runnable onRefresh,
            final Runnable onPasswordHandoff) {
        if (ctx == null || ssid == null || ssid.isEmpty()) return;
        final String capabilities = capabilitiesForNetwork(ctx, ssid);
        Toast.makeText(ctx, ctx.getString(R.string.toast_wifi_connecting, ssid), Toast.LENGTH_SHORT).show();
        WifiConnector.connectFromMenuDetailed(ctx.getApplicationContext(), ssid,
                capabilities, null, new WifiConnector.DetailedMenuCallback() {
                    @Override
                    public void onNeedPassword() {
                        if (onPasswordHandoff != null) {
                            onPasswordHandoff.run();
                        } else {
                            Toast.makeText(ctx, ctx.getString(R.string.overlay_wifi_need_password),
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onComplete(WifiConnector.ConnectionResult result) {
                        if (result != null && !result.success
                                && result.failure == WifiConnector.Failure.AUTHENTICATION_FAILED
                                && onPasswordHandoff != null) {
                            Toast.makeText(ctx,
                                    ctx.getString(WifiConnector.failureMessageResId(result.failure)),
                                    Toast.LENGTH_LONG).show();
                            onPasswordHandoff.run();
                            return;
                        }
                        if (result != null && !result.success
                                && result.failure != WifiConnector.Failure.CANCELED) {
                            Toast.makeText(ctx,
                                    ctx.getString(WifiConnector.failureMessageResId(result.failure)),
                                    Toast.LENGTH_LONG).show();
                        }
                        if (onRefresh != null) {
                            onRefresh.run();
                        }
                    }
                });
    }

    @SuppressLint("MissingPermission")
    private static String capabilitiesForNetwork(Context ctx, String ssid) {
        try {
            WifiManager wm = wifi(ctx);
            if (wm == null) return "";
            List<ScanResult> results = wm.getScanResults();
            if (results == null) return "";
            for (ScanResult r : results) {
                if (ssid.equals(r.SSID)) {
                    return r.capabilities != null ? r.capabilities : "";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Live association name — empty when the platform reports a phantom hex placeholder. */
    @SuppressLint("MissingPermission")
    private static String connectedSsid(WifiManager wm) {
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                return WifiScanFilter.displayableConnectedSsid(info.getSSID());
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static WifiManager wifi(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
}
