package com.solar.media;

import com.solar.launcher.net.SolarHttp;

import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 2026-07-29: Shared media-server utilities — consolidated from Navidrome/Jellyfin/Plex clients.
 *
 * Each utility was duplicated 2–3× across the three client classes.  Extracting them here
 * keeps the per-server Clients focused on API-specific logic (auth, endpoint construction, JSON parsing).
 */
public final class MediaServerUtils {

    private MediaServerUtils() {}

    // -- URL helpers --------------------------------------------------------

    /**
     * Normalize a user-entered server URL: add http:// when missing, default to the
     * given LAN port when the host is a private IP without a port in the URL.
     */
    public static String normalizeServerUrl(String raw, int defaultLanPort) {
        if (raw == null) return "";
        String u = raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return "";
        if (!u.contains("://")) u = "http://" + u;
        try {
            URL parsed = new URL(u);
            String protocol = parsed.getProtocol().toLowerCase(Locale.US);
            String host = parsed.getHost();
            int port = parsed.getPort();
            if (port == -1 && host != null && isLanHost(host)) {
                String file = parsed.getFile();
                if (file == null) file = "";
                u = new URL(protocol, host, defaultLanPort, file).toExternalForm();
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            }
        } catch (Exception ignored) {}
        return u;
    }

    /** True when the host is a private/LAN address where default server ports are safe. */
    private static boolean isLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h)) return true;
        if (h.matches("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        if (h.matches("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        return h.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d{1,3}\\.\\d{1,3}$");
    }

    // -- Encoding -----------------------------------------------------------

    /** URL-encode a string (UTF-8), never throws. */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Path-segment-safe URL encode — replaces + with %20 for UUID slashes. */
    public static String encPath(String s) {
        return enc(s).replace("+", "%20");
    }

    // -- HTTP helpers -------------------------------------------------------

    /** Fetch a URL and parse the response as JSON. */
    public static JSONObject fetchJson(String urlStr) throws Exception {
        byte[] bytes = SolarHttp.getBytes(urlStr, "application/json", "SolarLauncher/1.0");
        return new JSONObject(new String(bytes, "UTF-8"));
    }

    // -- String helpers -----------------------------------------------------

    /** First-letter index for A-Z artist grouping. */
    public static String indexLetter(String name) {
        if (name == null || name.isEmpty()) return "#";
        char c = Character.toUpperCase(name.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    /** Sanitize a file-system name for download folders. */
    public static String safeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown";
        return raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
