package com.solar.launcher;

/** User-controlled gate for Solar's explicit {@code WifiManager.reconnect()} requests. */
public final class WifiAutoConnectPolicy {
    public static final String PREF_ENABLED = "wifi_auto_connect";

    private WifiAutoConnectPolicy() {}

    public static boolean shouldRequestReconnect(boolean enabled, boolean wifiEnabled) {
        return enabled && wifiEnabled;
    }
}
