package com.solar.launcher;

/** ponytail: hide phantom MTK scan SSIDs from Wi-Fi setup list. */
public final class WifiScanFilter {
    private static final String NVRAM_PREFIX = "NVRAM WARNING:";

    private WifiScanFilter() {}

    public static boolean isHiddenSsid(String ssid) {
        return ssid != null && ssid.startsWith(NVRAM_PREFIX);
    }
}
