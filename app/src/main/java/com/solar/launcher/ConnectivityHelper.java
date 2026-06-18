package com.solar.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.solar.launcher.podcast.PodcastLibrary;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/** ponytail: online vs local-network checks + home shortcut visibility rules. */
public final class ConnectivityHelper {
    private ConnectivityHelper() {}

    /** Any connected network (Wi‑Fi, mobile, Ethernet). */
    public static boolean isOnline(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo active = cm.getActiveNetworkInfo();
                if (active != null && active.isConnected()) return true;
                // ponytail: Y1 / API 17 often leaves active null while Wi‑Fi works — scan all interfaces
                NetworkInfo[] all = cm.getAllNetworkInfo();
                if (all != null) {
                    for (NetworkInfo ni : all) {
                        if (ni != null && ni.isConnected() && isInternetCapableType(ni.getType())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return isWifiAssociated(app);
    }

    /** LAN / Wi‑Fi — PC Upload does not need internet. */
    public static boolean hasLocalNetwork(Context context) {
        if (context == null) return false;
        if (localIpv4(context) != null) return true;
        return isWifiAssociated(context.getApplicationContext());
    }

    public static String localIpv4(Context context) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (isUsableIpv4(addr)) return addr.getHostAddress();
                }
            }
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Needs routable internet for online discovery (Reach, Themes catalog). */
    public static boolean itemNeedsInternetForDiscovery(String id) {
        if (id == null) return false;
        id = HomeMenuConfig.migrateIdStatic(id);
        if (HomeMenuConfig.ID_SOULSEEK.equals(id)) return true;
        if (HomeMenuConfig.ID_THEMES.equals(id)) return true;
        return false;
    }

    /** Needs routable internet before starting an online-only action. */
    public static boolean itemNeedsInternet(String id) {
        return itemNeedsInternetForDiscovery(id);
    }

    /** Needs local IP only (PC Upload web server on LAN). */
    public static boolean itemNeedsLocalNetwork(String id) {
        return id != null && HomeMenuConfig.ID_PC_UPLOAD.equals(HomeMenuConfig.migrateIdStatic(id));
    }

    /** @deprecated use {@link #itemNeedsInternet} or {@link #itemNeedsLocalNetwork} */
    public static boolean itemNeedsOnline(String id) {
        return itemNeedsInternet(id) || itemNeedsLocalNetwork(id);
    }

    public static boolean shouldShowHomeShortcut(Context context, String id) {
        if (context == null) return shouldShowHomeShortcut(id, false, false, false);
        return shouldShowHomeShortcut(id,
                isOnline(context),
                hasLocalNetwork(context),
                PodcastLibrary.hasSavedContent());
    }

    static boolean shouldShowHomeShortcut(String id, boolean internetAvailable,
            boolean localNetworkAvailable, boolean podcastsSaved) {
        if (id == null) return false;
        id = HomeMenuConfig.migrateIdStatic(id);
        if (HomeMenuConfig.ID_MORE.equals(id)) return true;
        if (itemNeedsInternetForDiscovery(id)) return internetAvailable;
        if (itemNeedsLocalNetwork(id)) return localNetworkAvailable;
        if (HomeMenuConfig.ID_PODCASTS.equals(id)) return internetAvailable || podcastsSaved;
        return true;
    }

    public static boolean shouldShowMenuItem(Context context, String id) {
        return shouldShowHomeShortcut(context, id);
    }

    /** Wi‑Fi associated (networkId set) — reliable on Y1 when ConnectivityManager lies. */
    static boolean isWifiAssociated(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;
            WifiInfo info = wm.getConnectionInfo();
            return info != null && info.getNetworkId() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInternetCapableType(int type) {
        return type == ConnectivityManager.TYPE_WIFI
                || type == ConnectivityManager.TYPE_MOBILE
                || type == ConnectivityManager.TYPE_ETHERNET;
    }

    private static boolean isUsableIpv4(InetAddress addr) {
        if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) return false;
        String ip = addr.getHostAddress();
        return ip != null && !"0.0.0.0".equals(ip);
    }
}
