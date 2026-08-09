package com.solar.launcher.tlsproxy;

import android.content.Context;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * VpnService integration point (port of Wolfius {@code VpnCompatHelper}, GPLv3).
 *
 * Currently a no-op: the Solar default capture method is the root iptables DNAT route
 * (Wolfius method 1), which needs no VPN protection. The lwIP VpnService stage (method 3)
 * will implement {@link #protect} and {@link #startVpnService} when it lands.
 */
final class VpnCompatHelper {
    static void protect(Socket socket) {
        // No-op while VpnService capture is not active.
    }

    static void protect(DatagramSocket socket) {
        // No-op while VpnService capture is not active.
    }

    static void startVpnService(Context context) {
        // Later stage — lwIP TUN capture. See solar-rom/vendor/wolfius/README-SOLAR.md.
    }

    static void stopVpnService(Context context) {
        // Later stage.
    }
}
