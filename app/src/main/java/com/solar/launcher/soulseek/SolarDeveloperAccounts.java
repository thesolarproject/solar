package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.R;

import java.util.Locale;

/** Hidden Solar developer Soulseek identities and virtual support thread key. */
public final class SolarDeveloperAccounts {
    /** Wire spelling preserved; match case-insensitively. */
    public static final String SOLAR_DEV = "SolarDev";
    public static final String SOLAR_PHONE = "thesolarphone";
    public static final String SOLAR_Y1 = "ThesolarY1";

    private static final String[] DEV_USERNAMES = {
            SOLAR_DEV, SOLAR_PHONE, SOLAR_Y1
    };

    /** PM table peer key for the consolidated developer conversation. */
    public static final String VIRTUAL_PEER = "__solar_developer__";

    /** Debug gate — entire Solar Development messaging experiment. */
    public static final String PREF_DEV_SUPPORT_EXPERIMENT = "solar_dev_support_experiment";

    /** Prefix on auto diagnostic PM bodies (hidden from support thread UI). */
    public static final String DIAG_MARKER = "\u0001SOLAR_DIAG\u0001";

    /** Prefix on stored inbound developer PMs — preserves wire dev username in virtual thread. */
    public static final String DEV_FROM_MARKER = "\u0001SOLAR_DEV_FROM\u0001";

    public static final String DIAG_SUFFIX = "-diag";
    public static final int USERNAME_MAX_LEN = 20;

    private SolarDeveloperAccounts() {}

    public static boolean isExperimentEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_DEV_SUPPORT_EXPERIMENT, false);
    }

    public static boolean isDeveloper(String username) {
        if (username == null || username.isEmpty()) return false;
        for (String dev : DEV_USERNAMES) {
            if (dev.equalsIgnoreCase(username)) return true;
        }
        return false;
    }

    /** Secondary identity used for silent log shipping only. */
    public static boolean isDiagHandle(String username) {
        if (username == null) return false;
        String lower = username.toLowerCase(Locale.US);
        return lower.endsWith(DIAG_SUFFIX.toLowerCase(Locale.US));
    }

    /** Hide raw dev/diag wire accounts from browse — virtual peer is shown as Solar Development. */
    public static boolean hideFromReachUi(String username) {
        return isDeveloper(username) || isDiagHandle(username);
    }

    public static boolean isVirtualPeer(String peer) {
        return peer != null && VIRTUAL_PEER.equalsIgnoreCase(peer.trim());
    }

    /** User-facing label for inbox and conversation title. */
    public static String displayNameForPeer(Context ctx, String peer) {
        if (isVirtualPeer(peer) && ctx != null) {
            return ctx.getString(R.string.solar_development_display_name);
        }
        return peer != null ? peer : "";
    }

    /**
     * Map friendly new-message input to virtual peer, or null when not a developer contact.
     * Returns null for hidden dev wire names so callers can reject them.
     */
    public static String resolveContactInput(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty()) return null;
        if (isVirtualPeer(t)) return VIRTUAL_PEER;
        String lower = t.toLowerCase(Locale.US);
        String compact = lower.replace(" ", "");
        if ("solar development".equals(lower) || "solardevelopment".equals(compact)
                || "solar dev".equals(lower)) {
            return VIRTUAL_PEER;
        }
        if (isDeveloper(t) || isDiagHandle(t)) return null;
        return null;
    }

    /** True when Find User / new-message input targets the aggregated developer entity. */
    public static boolean isAggregatedDeveloperQuery(String typed) {
        return resolveContactInput(typed) != null;
    }

    public static String[] developerUsernames() {
        return DEV_USERNAMES.clone();
    }

    /** Wire fan-out targets — dev senders reach the other two inboxes only. */
    public static String[] wireRecipientsForSender(String senderUsername) {
        if (isDeveloper(senderUsername)) {
            return forwardTargets(senderUsername);
        }
        return developerUsernames();
    }

    /** Other dev accounts to forward a message to (excluding sender). */
    public static String[] forwardTargets(String fromDeveloper) {
        String[] out = new String[DEV_USERNAMES.length - 1];
        int j = 0;
        for (String dev : DEV_USERNAMES) {
            if (fromDeveloper != null && dev.equalsIgnoreCase(fromDeveloper)) continue;
            out[j++] = dev;
        }
        return out;
    }

    public static boolean isAutoDiagnosticText(String text) {
        return text != null && text.contains(DIAG_MARKER);
    }

    /** Stored inbound developer PM with wire sender attribution. */
    public static final class DevIncoming {
        public final String fromDev;
        public final String body;

        DevIncoming(String fromDev, String body) {
            this.fromDev = fromDev != null ? fromDev : "";
            this.body = body != null ? body : "";
        }
    }

    /** Prefix virtual-thread storage with the wire dev account name. */
    public static String packDevIncoming(String fromDev, String text) {
        String dev = fromDev != null ? fromDev.trim() : "";
        String body = text != null ? text : "";
        if (dev.isEmpty()) return body;
        return DEV_FROM_MARKER + dev + "\n" + body;
    }

    public static boolean isDevIncoming(String text) {
        return text != null && text.startsWith(DEV_FROM_MARKER);
    }

    /** Strip dev-from prefix for display; pass-through when not prefixed. */
    public static DevIncoming parseDevIncoming(String text) {
        if (!isDevIncoming(text)) {
            return new DevIncoming("", text != null ? text : "");
        }
        String rest = text.substring(DEV_FROM_MARKER.length());
        int nl = rest.indexOf('\n');
        if (nl < 0) return new DevIncoming(rest.trim(), "");
        return new DevIncoming(rest.substring(0, nl).trim(), rest.substring(nl + 1));
    }

    public static String displayBody(String text) {
        return parseDevIncoming(text).body;
    }

    /** Derive -diag username from main Reach username (max 20 chars). */
    public static String deriveDiagUsername(String mainUsername) {
        if (mainUsername == null || mainUsername.trim().isEmpty()) return "solar-diag";
        String base = mainUsername.trim();
        String candidate = base + DIAG_SUFFIX;
        if (candidate.length() <= USERNAME_MAX_LEN) return candidate;
        int keep = USERNAME_MAX_LEN - DIAG_SUFFIX.length();
        if (keep < 1) keep = 1;
        return base.substring(0, keep) + DIAG_SUFFIX;
    }

    /** Fallback candidates when primary -diag name is taken on the server. */
    public static String[] diagUsernameFallbacks(String mainUsername) {
        String primary = deriveDiagUsername(mainUsername);
        String base = mainUsername != null ? mainUsername.trim() : "solar";
        String[] suffixes = new String[] { "-dg", "-d", "d" };
        String[] out = new String[suffixes.length + 1];
        out[0] = primary;
        for (int i = 0; i < suffixes.length; i++) {
            String suf = suffixes[i];
            int keep = USERNAME_MAX_LEN - suf.length();
            if (keep < 1) keep = 1;
            String truncated = base.length() > keep ? base.substring(0, keep) : base;
            out[i + 1] = truncated + suf;
        }
        return out;
    }
}
