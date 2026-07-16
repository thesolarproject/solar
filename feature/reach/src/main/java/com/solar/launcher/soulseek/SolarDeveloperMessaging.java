package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * @SolarDeveloper conversation helpers — normal PM to a single peer with diag filtering.
 */
public final class SolarDeveloperMessaging {

    private SolarDeveloperMessaging() {}

    public static List<SoulseekMessaging.Message> thread(Context ctx) {
        List<SoulseekMessaging.Message> raw =
                SoulseekMessaging.thread(ctx, SolarDeveloperAccounts.SOLAR_DEVELOPER);
        List<SoulseekMessaging.Message> out = new ArrayList<SoulseekMessaging.Message>();
        for (SoulseekMessaging.Message m : raw) {
            if (m == null) continue;
            if (SolarDeveloperAccounts.isAutoDiagnosticText(m.text)) continue;
            String visible = SolarDeveloperAccounts.stripDiagnosticText(m.text);
            if (visible.isEmpty()) continue;
            if (!visible.equals(m.text)) {
                out.add(new SoulseekMessaging.Message(
                        m.id, m.timestamp, m.peer, visible, m.incoming));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    public static void appendIncoming(Context ctx, int msgId, int timestamp, String fromDev,
            String text) {
        if (ctx == null || text == null) return;
        if (SolarDeveloperAccounts.isDiagHandle(fromDev)) return;
        if (SolarDeveloperAccounts.isAutoDiagnosticText(text)) return;
        String visible = SolarDeveloperAccounts.stripDiagnosticText(text);
        if (visible.isEmpty()) return;
        SoulseekMessaging.append(ctx, new SoulseekMessaging.Message(
                msgId, timestamp, SolarDeveloperAccounts.SOLAR_DEVELOPER, visible, true));
    }

    public static void sendUserMessage(final Context ctx, final SharedPreferences prefs,
            final SoulseekClient client, final String text,
            final SoulseekClient.MessageSendCallback callback) {
        if (ctx == null || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError("Empty message");
            return;
        }
        final String trimmed = SolarDeveloperAccounts.stripDiagnosticText(text.trim());
        if (trimmed.isEmpty()) {
            if (callback != null) callback.onError("Empty message");
            return;
        }
        final int ts = (int) (System.currentTimeMillis() / 1000L);
        SoulseekMessaging.append(ctx, new SoulseekMessaging.Message(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                ts, SolarDeveloperAccounts.SOLAR_DEVELOPER, trimmed, false));
        if (client == null) {
            if (callback != null) callback.onError("Offline");
            return;
        }
        client.sendPrivateMessage(SolarDeveloperAccounts.SOLAR_DEVELOPER, trimmed, callback);
    }
}
