package com.solar.launcher;

/**
 * Filters bogus MTK / platform Wi-Fi names from lists and connected-state labels.
 * Phantom SSIDs (NVRAM warnings, hex placeholders like {@code 0x…}) appear in scans
 * or as {@link android.net.wifi.WifiInfo#getSSID()} when nothing real is connected.
 */
public final class WifiScanFilter {
    private static final String NVRAM_PREFIX = "NVRAM WARNING:";

    private WifiScanFilter() {}

    /** True when an SSID must not appear in Wi-Fi pickers or "Connected" rows. */
    public static boolean isHiddenSsid(String ssid) {
        if (ssid == null || ssid.isEmpty()) return true;
        if (ssid.startsWith(NVRAM_PREFIX)) return true;
        // MTK often reports a hex placeholder when Wi-Fi is on but not associated.
        return ssid.startsWith("0x") || ssid.startsWith("0X");
    }

    /**
     * Normalizes {@link android.net.wifi.WifiInfo#getSSID()} for display and matching.
     * Returns empty when the platform reports a phantom or unknown association.
     */
    public static String displayableConnectedSsid(String rawSsid) {
        if (rawSsid == null) return "";
        String ssid = WifiConnector.unquoteWifiString(rawSsid);
        if (ssid.isEmpty() || isHiddenSsid(ssid)) return "";
        if ("<unknown ssid>".equalsIgnoreCase(ssid)) return "";
        return ssid;
    }
}
