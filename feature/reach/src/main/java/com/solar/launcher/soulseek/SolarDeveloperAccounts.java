package com.solar.launcher.soulseek;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @SolarDeveloper support peer + diagnostic stripping.
 * Conversation peer key is always {@link #SOLAR_DEVELOPER}; legacy wire names fold into it.
 */
public final class SolarDeveloperAccounts {
    public static final String SOLAR_DEVELOPER = "SolarDeveloper";
    public static final String SOLAR_DEV = "SolarDev";
    public static final String SOLAR_PHONE = "thesolarphone";
    public static final String SOLAR_Y1 = "ThesolarY1";
    /** Legacy virtual peer from earlier experiment. */
    public static final String LEGACY_VIRTUAL_PEER = "__solar_developer__";

    public static final String DIAG_MARKER = "\u0001SOLAR_DIAG\u0001";
    public static final String DIAG_SUFFIX = "-diag";
    private static final Pattern SOLAR_DIAG_TOKEN =
            Pattern.compile("(?i)\\bsolar_diag(?:_[A-Za-z0-9_-]+)?\\b");
    private static final String[] DEV_WIRE = {
            SOLAR_DEVELOPER, SOLAR_DEV, SOLAR_PHONE, SOLAR_Y1
    };

    private SolarDeveloperAccounts() {}

    public static boolean isCanonicalPeer(String username) {
        return username != null && SOLAR_DEVELOPER.equalsIgnoreCase(username.trim());
    }

    public static boolean isLegacyVirtualPeer(String peer) {
        return peer != null && LEGACY_VIRTUAL_PEER.equalsIgnoreCase(peer.trim());
    }

    public static boolean isDeveloper(String username) {
        if (username == null || username.isEmpty()) return false;
        for (String d : DEV_WIRE) {
            if (d.equalsIgnoreCase(username.trim())) return true;
        }
        return isLegacyVirtualPeer(username);
    }

    public static boolean isDiagHandle(String username) {
        if (username == null) return false;
        String lower = username.toLowerCase(Locale.US);
        return lower.endsWith(DIAG_SUFFIX) || lower.endsWith("-dg")
                || (lower.endsWith("-d") && lower.length() > 2);
    }

    public static boolean hideFromReachUi(String username) {
        return isDiagHandle(username) || isLegacyVirtualPeer(username);
    }

    /** Map any developer wire name / virtual peer → canonical storage peer. */
    public static String mapToConversationPeer(String fromUser) {
        if (fromUser == null) return "";
        if (isDeveloper(fromUser) || isDiagHandle(fromUser) || isLegacyVirtualPeer(fromUser)) {
            return SOLAR_DEVELOPER;
        }
        return fromUser.trim();
    }

    public static String displayNameForPeer(String peer) {
        if (isCanonicalPeer(peer) || isDeveloper(peer) || isLegacyVirtualPeer(peer)) {
            return "@" + SOLAR_DEVELOPER;
        }
        return peer != null ? peer : "";
    }

    public static boolean isAutoDiagnosticText(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains(DIAG_MARKER)) return true;
        String t = text.trim();
        if (t.isEmpty()) return false;
        Matcher m = SOLAR_DIAG_TOKEN.matcher(t);
        String without = m.replaceAll("").trim();
        return without.isEmpty();
    }

    /**
     * Strip diagnostic markers and solar_diag tokens; returns empty if nothing user-visible remains.
     */
    public static String stripDiagnosticText(String text) {
        if (text == null) return "";
        String out = text;
        int idx;
        while ((idx = out.indexOf(DIAG_MARKER)) >= 0) {
            int end = out.indexOf('\u0001', idx + DIAG_MARKER.length());
            if (end < 0) {
                out = out.substring(0, idx);
                break;
            }
            out = out.substring(0, idx) + out.substring(end + 1);
        }
        out = SOLAR_DIAG_TOKEN.matcher(out).replaceAll("");
        // Collapse leftover blank lines
        String[] lines = out.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.replaceAll("\\s+$", ""));
        }
        return sb.toString().trim();
    }

    public static String welcomeMessageBody() {
        return "Welcome to Solar! If you have any issues, be sure to report them at "
                + "github.com/thesolarproject/solar - Have fun! We look forward to hearing about "
                + "your experience, and if you're enjoying it, be sure to spread the word on "
                + "r/innioasis and r/innioasismodders";
    }

    public static String[] developerUsernames() {
        return new String[] { SOLAR_DEVELOPER };
    }
}
