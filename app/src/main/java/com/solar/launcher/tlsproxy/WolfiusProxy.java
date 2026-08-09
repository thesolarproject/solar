package com.solar.launcher.tlsproxy;

import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Solar facade for the Wolfius-style TLS proxy runtime.
 *
 * Holds the shared static state the ported proxy classes need: the current connection
 * method and the active physical interface IP used for source-port binding and DNAT rules.
 * Ported from Wolfius {@code ProxyService} static helpers (GPLv3, gohoski/Wolfius).
 */
public final class WolfiusProxy {
    private static final String TAG = "WolfiusProxy";

    /** Connection method: {@link #METHOD_IPTABLES}, or {@link #METHOD_PPTP}/{@link #METHOD_VPN} when those stages land. */
    public static volatile String currentMethod = null;

    public static final String METHOD_IPTABLES = "iptables";
    public static final String METHOD_PPTP = "pptp";
    public static final String METHOD_VPN = "vpns";

    public static boolean isRunning() {
        return currentMethod != null;
    }

    public static String getActiveLocalIpAddress() {
        try {
            List<java.net.NetworkInterface> interfaces =
                    Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                // Skip loopback and virtual/VPN interfaces.
                if (name.startsWith("ppp") || name.startsWith("tun")
                        || name.startsWith("tap") || name.startsWith("lo")) {
                    continue;
                }
                List<java.net.InetAddress> addrs = Collections.list(intf.getInetAddresses());
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
}
