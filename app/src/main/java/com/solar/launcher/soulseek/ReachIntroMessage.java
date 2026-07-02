package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

/** Auto-intro PM/room message visible only to non-Reach Soulseek clients. */
public final class ReachIntroMessage {
    public static final String MARKER = "using the Reach client. They may write replies slowly";
    public static final String OLD_MARKER = "\u0001REACH_INTRO\u0001";

    private ReachIntroMessage() {}

    public static String build(Context ctx, String username, String modelLabel) {
        if (ctx == null || username == null || username.isEmpty()) return "";
        return ctx.getString(
                com.solar.launcher.R.string.reach_intro_message, username, modelLabel);
    }

    public static boolean isIntro(String text) {
        return text != null && (text.contains(MARKER) || text.contains(OLD_MARKER));
    }

    public static String strip(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.contains(MARKER) || line.contains(OLD_MARKER)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    public static String stripFromQuote(String quote) {
        return strip(quote != null ? quote : "");
    }

    public static boolean shouldPersistForReachClient(String text, String sender, String selfUsername,
            SharedPreferences prefs) {
        // Reach clients never store auto-intro lines — wire send to legacy clients unchanged.
        if (isIntro(text)) return false;
        return true;
    }

    public static boolean shouldHideIncoming(String text, String sender, String selfUsername,
            SharedPreferences prefs) {
        if (!isIntro(text)) return false;
        if (sender != null && selfUsername != null
                && sender.equalsIgnoreCase(selfUsername)) {
            return true;
        }
        return sender != null && ReachPeerCapabilities.isReach(prefs, sender);
    }

    public static boolean shouldHideOutgoing(String text) {
        return isIntro(text);
    }
}
