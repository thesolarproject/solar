package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.DeviceFeatures;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consolidated developer support thread: one local conversation, three wire recipients.
 * Auto diagnostic PMs are never stored here.
 */
public final class SolarDeveloperMessaging {

    private SolarDeveloperMessaging() {}

    public static List<SoulseekMessaging.Message> thread(Context ctx, SharedPreferences prefs) {
        List<SoulseekMessaging.Message> raw = SoulseekMessaging.thread(
                ctx, prefs, SolarDeveloperAccounts.VIRTUAL_PEER);
        if (raw == null) return raw;
        java.util.ArrayList<SoulseekMessaging.Message> out = new java.util.ArrayList<SoulseekMessaging.Message>();
        for (SoulseekMessaging.Message m : raw) {
            if (m == null || SolarDeveloperAccounts.isAutoDiagnosticText(m.text)) continue;
            out.add(m);
        }
        return out;
    }

    public static void appendIncoming(Context ctx, SharedPreferences prefs, String fromDev,
            int msgId, int timestamp, String text) {
        if (ctx == null || text == null || SolarDeveloperAccounts.isAutoDiagnosticText(text)) return;
        String stored = SolarDeveloperAccounts.packDevIncoming(fromDev, text);
        SoulseekMessaging.append(ctx, prefs, new SoulseekMessaging.Message(
                msgId, timestamp, SolarDeveloperAccounts.VIRTUAL_PEER, stored, true));
    }

    /** Every Solar Development thread open — fresh hidden diagnostic bundle (not debounced). */
    public static void onThreadOpened(Context ctx, SharedPreferences prefs) {
        if (ctx == null || prefs == null) return;
        if (!SolarDeveloperAccounts.isExperimentEnabled(prefs)) return;
        SolarDiagnosticReporter.shipOnDeveloperSupportOpen(ctx, prefs);
    }

    public static void sendUserMessage(final Context ctx, final SharedPreferences prefs,
            final SoulseekClient client, final String text,
            final SoulseekClient.MessageSendCallback callback) {
        if (ctx == null || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError("Unavailable");
            return;
        }
        final String trimmed = text.trim();
        final String body = formatUserSupportWireBody(ctx, prefs, trimmed);
        SoulseekMessaging.append(ctx, prefs, new SoulseekMessaging.Message(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                (int) (System.currentTimeMillis() / 1000L),
                SolarDeveloperAccounts.VIRTUAL_PEER, trimmed, false));
        SoulseekAccount acct = SoulseekAccount.load(prefs, ctx);
        final String[] devs = SolarDeveloperAccounts.wireRecipientsForSender(
                acct != null ? acct.username : null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean sent = sendWireFanOut(ctx, prefs, client, devs, body);
                if (!sent) {
                    SolarDeveloperOutbox.enqueue(ctx, body);
                }
                if (callback == null) return;
                // Local thread already has the message — queue silently, never toast.
                callback.onSent();
            }
        }, "SolarDevPM").start();
    }

    /** Every wire PM attributes the Reach user — not only the first message in a session. */
    static String formatUserSupportWireBody(Context ctx, SharedPreferences prefs, String text) {
        SoulseekAccount acct = SoulseekAccount.load(prefs, ctx);
        String model = DeviceFeatures.deviceModelLabel();
        String user = acct.username != null ? acct.username : "?";
        return "[Solar support] user=" + user + " device=" + model + "\n" + text;
    }

    /** Main Reach client when logged in; otherwise -diag session for silent relay. */
    static boolean sendWireFanOut(Context ctx, SharedPreferences prefs, SoulseekClient client,
            String[] recipients, String body) {
        if (recipients == null || recipients.length == 0 || body == null || body.isEmpty()) {
            return false;
        }
        if (client != null && client.isLoggedIn()) {
            if (sendViaMainClient(client, recipients, body)) return true;
        }
        return sendViaDiagSession(ctx, prefs, recipients, body);
    }

    /** Parallel PM fan-out on the already-connected Reach session. */
    static boolean sendViaMainClient(final SoulseekClient client, String[] recipients,
            final String body) {
        if (client == null || !client.isLoggedIn() || recipients == null || body.isEmpty()) {
            return false;
        }
        final AtomicBoolean anyOk = new AtomicBoolean(false);
        Thread[] workers = new Thread[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            final String to = recipients[i];
            if (to == null || to.isEmpty()) continue;
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.sendPrivateMessageSync(to, body);
                        anyOk.set(true);
                    } catch (Exception ignored) {}
                }
            }, "SolarDevPM-" + to);
            workers[i].start();
        }
        for (Thread t : workers) {
            if (t == null) continue;
            try {
                t.join(15000L);
            } catch (InterruptedException ignored) {}
        }
        return anyOk.get();
    }

    /**
     * PM fan-out via -diag session (Nicotine+ client id 160 — server relays to legacy inboxes).
     */
    static boolean sendViaDiagSession(Context ctx, SharedPreferences prefs,
            String[] recipients, String body) {
        if (ctx == null || prefs == null || body == null || body.isEmpty()) return false;
        return SolarDiagSessionManager.sendToAll(ctx, prefs, recipients, body);
    }

    /** Silent cross-forward so all three dev inboxes stay in sync (via diag session). */
    public static void forwardToOtherDevs(Context ctx, SharedPreferences prefs,
            String fromDev, String text) {
        if (ctx == null || text == null || fromDev == null) return;
        String body = "Forwarded from " + fromDev + ": " + text;
        sendViaDiagSession(ctx, prefs, SolarDeveloperAccounts.forwardTargets(fromDev), body);
    }

    public static SoulseekMessaging.Message lastVisibleMessage(Context ctx, SharedPreferences prefs) {
        List<SoulseekMessaging.Message> t = thread(ctx, prefs);
        if (t == null || t.isEmpty()) return null;
        return t.get(t.size() - 1);
    }

}
