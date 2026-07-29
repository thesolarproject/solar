package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.R;

import java.util.Locale;

/** Solar Development Soulseek identities and virtual support thread key. */
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

    public static final String PREF_DEV_SUPPORT_EXPERIMENT = "solar_dev_support_experiment";

    /**
     * Legacy invisible marker (still accepted when filtering).
     * New automated wire text uses {@link #AUTO_MSG_PREFIX} at the start so any client
     * can exclude these lines by header.
     */
    public static final String DIAG_MARKER = "\u0001SOLAR_DIAG\u0001";

    /**
     * Human-readable start-of-message tag for all automated Solar → developer PMs.
     * Conversation UIs hide any message whose body (after trim) starts with this
     * (case-insensitive), for Solar Development or any other peer.
     * Example: {@code solar diag - alice@Y1: youtube fail id=…}
     */
    public static final String AUTO_MSG_PREFIX = "solar diag - ";

    /** Prefix on stored inbound developer PMs — preserves wire dev username in virtual thread. */
    public static final String DEV_FROM_MARKER = "\u0001SOLAR_DEV_FROM\u0001";

    public static final String DIAG_SUFFIX = "-diag";
    public static final int USERNAME_MAX_LEN = 20;

    private SolarDeveloperAccounts() {}

    /**
     * Solar Development / Report Issue support is always on.
     * Pref remains for Debug UI visibility but does not gate the feature.
     */
    public static boolean isExperimentEnabled(SharedPreferences prefs) {
        return true;
    }

    public static boolean isDeveloper(String username) {
        if (username == null || username.isEmpty()) return false;
        for (String dev : DEV_USERNAMES) {
            if (dev.equalsIgnoreCase(username)) return true;
        }
        return false;
    }

    public static boolean isDiagHandle(String username) {
        if (username == null) return false;
        String lower = username.toLowerCase(Locale.US);
        return lower.endsWith(DIAG_SUFFIX.toLowerCase(Locale.US));
    }

    /** Wire dev accounts map to the virtual Solar Development peer in UI. */
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
        if (isDiagHandle(t)) return VIRTUAL_PEER;
        return null;
    }

    /** True when Find User / new-message input targets the aggregated developer entity. */
    public static boolean isAggregatedDeveloperQuery(String typed) {
        return matchesDeveloperSearchQuery(typed);
    }

    /** Find User search — friendly names and hidden wire dev accounts map to Solar Development. */
    public static boolean matchesDeveloperSearchQuery(String typed) {
        if (typed == null) return false;
        String t = typed.trim();
        if (t.isEmpty()) return false;
        if (resolveContactInput(t) != null) return true;
        return isDeveloper(t);
    }

    /** Inbox/thread preview — unpack dev-from prefix before Reach formatting. */
    public static String previewText(String text) {
        return ReachMessageFormat.previewText(displayBody(text));
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

    /**
     * True for automated diagnostic / impact PMs — omitted from conversation views.
     * {@code solar_diag} / {@code solar_diag_*} command and acknowledgement lines are hidden,
     * including polite command wrappers such as "Please run solar_diag now".
     */
    public static boolean isAutoDiagnosticText(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains(DIAG_MARKER)) return true;
        String lower = text.trim().toLowerCase(Locale.US);
        // Primary: human-readable header at start of message (wire-visible for any client).
        if (lower.startsWith("solar diag -") || lower.startsWith("solar diag-")) return true;
        // Legacy confirmations / acks only (colon form).
        if (lower.startsWith("solar_diag:")) return true;
        // Commands can be wrapped in natural-language instructions from support.
        SolarDiagProbes.Parsed p = SolarDiagProbes.parse(text);
        return p.isCommandOnly() || SolarDiagProbes.hasBarePull(text)
                || SolarDiagProbes.hasProbe(text);
    }

    /**
     * Developer requested a <b>full</b> remote log pull → GitHub issue.
     * Only the bare {@code solar_diag} token (not {@code solar_diag_*} probes).
     * Never true for auto-prefix acks / impact pings.
     */
    public static boolean isDiagRemotePullCommand(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains(DIAG_MARKER)) return false;
        String lower = text.toLowerCase(Locale.US).trim();
        // Auto pings / acks start with the header — never re-trigger a pull.
        if (lower.startsWith("solar diag -") || lower.startsWith("solar diag-")) return false;
        if (lower.startsWith("solar_diag:")) return false;
        return SolarDiagProbes.hasBarePull(text);
    }

    /** Developer sent one or more {@code solar_diag_*} recon probes (no GitHub ship). */
    public static boolean isDiagProbeCommand(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains(DIAG_MARKER)) return false;
        String lower = text.toLowerCase(Locale.US).trim();
        if (lower.startsWith("solar diag -") || lower.startsWith("solar diag-")) return false;
        if (lower.startsWith("solar_diag:")) return false;
        return SolarDiagProbes.hasProbe(text);
    }

    /** Strip bare {@code solar_diag} and {@code solar_diag_*} tokens for display. */
    public static String stripDiagCommands(String text) {
        return SolarDiagProbes.stripDiagTokens(text);
    }

    /** Wrap body with the wire-visible auto-message header (and legacy marker for older clients). */
    public static String formatAutoMessage(String body) {
        String b = body != null ? body.trim() : "";
        if (b.isEmpty()) b = "event";
        // Readable prefix first so startsWith filters work on the wire text.
        return AUTO_MSG_PREFIX + b;
    }

    /** Soulseek reply after a remote pull; filtered from local conversation history. */
    public static String formatDiagConfirmation(boolean ok, int issueNumber) {
        String body;
        if (ok) {
            body = issueNumber > 0
                    ? ("sent (issue #" + issueNumber + ")")
                    : "sent";
        } else {
            body = "failed (retry later)";
        }
        return formatAutoMessage(body);
    }

    /**
     * Silent notice to developer accounts on user power-off/restart.
     * Starts with {@link #AUTO_MSG_PREFIX} so conversation UIs omit it.
     */
    public static String formatPoweredOffNotice(String username, boolean restart) {
        String who = username != null ? username.trim() : "";
        if (who.isEmpty()) who = "user";
        String body = restart ? (who + " is restarting") : (who + " has powered off");
        return formatAutoMessage(body);
    }

    /**
     * Silent one-line impact ping (Wi‑Fi / media).
     * Format: {@code solar diag - user@device: message}
     */
    public static String formatImpactPing(String username, String deviceLabel, String oneLine) {
        String who = username != null ? username.trim() : "";
        if (who.isEmpty()) who = "user";
        String dev = deviceLabel != null ? deviceLabel.trim() : "";
        if (dev.isEmpty()) dev = "?";
        String msg = oneLine != null ? oneLine.trim().replace('\n', ' ') : "";
        if (msg.isEmpty()) msg = "event";
        // Allow rich media one-liners (title/artist/id) without multi-line dumps.
        if (msg.length() > 200) msg = msg.substring(0, 199) + "…";
        return formatAutoMessage(who + "@" + dev + ": " + msg);
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
