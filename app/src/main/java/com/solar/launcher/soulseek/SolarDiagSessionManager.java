package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.Debug843b96Log;

import org.json.JSONObject;

/**
 * Persistent -diag Soulseek session (Nicotine+ 160) for PM relay and log shipping.
 * ponytail: server-only login — no listen/NAT on Y1; concurrent listen caused EOF
 * reconnect loops that starved the main Reach client.
 */
public final class SolarDiagSessionManager {
    private static final Object LOCK = new Object();
    private static final long CONNECT_BACKOFF_MS = 90_000L;

    private static SoulseekMessagingSession session;
    private static String sessionUser;
    private static volatile boolean connectRunning;
    private static volatile long lastConnectFailMs;

    private SolarDiagSessionManager() {}

    /** Start or refresh the diag session on a worker thread when a send/scan is due. */
    public static void ensureSession(final Context context, final SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        if (connectRunning) return;
        synchronized (LOCK) {
            if (session != null && session.isLoggedIn()) return;
            if (lastConnectFailMs > 0
                    && System.currentTimeMillis() - lastConnectFailMs < CONNECT_BACKOFF_MS) {
                return;
            }
        }
        connectRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureSessionSync(context.getApplicationContext(), prefs);
                } finally {
                    connectRunning = false;
                }
            }
        }, "SolarDiagSession").start();
    }

    /** Blocking connect — call from PM/diag worker threads only. */
    public static boolean ensureSessionSync(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return false;
        SolarDiagAccount diag = SolarDiagAccount.load(prefs, context);
        synchronized (LOCK) {
            if (session != null && diag.username.equals(sessionUser) && session.isLoggedIn()) {
                return true;
            }
            if (lastConnectFailMs > 0
                    && System.currentTimeMillis() - lastConnectFailMs < CONNECT_BACKOFF_MS) {
                return false;
            }
            if (session != null) {
                session.shutdown();
                session = null;
                sessionUser = null;
            }
            try {
                // Server-only PM — Nicotine+ 160 for relay to legacy dev inboxes.
                session = new SoulseekMessagingSession(context, diag.username, diag.password, false);
                session.ensureConnected();
                sessionUser = diag.username;
                lastConnectFailMs = 0;
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("diagUser", diag.username);
                    Debug843b96Log.log(context, "SolarDiagSessionManager.ensureSessionSync",
                            "session up", "G", d);
                } catch (Exception ignored) {}
                // #endregion
                return true;
            } catch (Exception e) {
                lastConnectFailMs = System.currentTimeMillis();
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("diagUser", diag.username);
                    d.put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    Debug843b96Log.log(context, "SolarDiagSessionManager.ensureSessionSync",
                            "session fail", "G", d);
                } catch (Exception ignored) {}
                // #endregion
                if (session != null) {
                    session.shutdown();
                    session = null;
                    sessionUser = null;
                }
                return false;
            }
        }
    }

    /** Fan-out PM via diag session; per-recipient success flags. */
    public static SolarDeveloperMessaging.FanOutResult sendToRecipients(Context context,
            SharedPreferences prefs, String[] recipients, String text) {
        if (recipients == null || recipients.length == 0 || text == null || text.isEmpty()) {
            return SolarDeveloperMessaging.FanOutResult.allFailed(recipients);
        }
        if (!ensureSessionSync(context, prefs)) {
            return SolarDeveloperMessaging.FanOutResult.allFailed(recipients);
        }
        synchronized (LOCK) {
            if (session == null) {
                return SolarDeveloperMessaging.FanOutResult.allFailed(recipients);
            }
            boolean[] per = session.sendToRecipients(recipients, text);
            boolean allOk = true;
            for (boolean ok : per) {
                if (!ok) {
                    allOk = false;
                    break;
                }
            }
            if (!allOk && session != null && !session.isLoggedIn()) {
                session.shutdown();
                session = null;
                sessionUser = null;
            }
            return SolarDeveloperMessaging.FanOutResult.from(recipients, per);
        }
    }

    /** True only when every recipient succeeded. */
    public static boolean sendToAll(Context context, SharedPreferences prefs,
            String[] recipients, String text) {
        return sendToRecipients(context, prefs, recipients, text).allSucceeded();
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (session != null) {
                session.shutdown();
                session = null;
                sessionUser = null;
            }
            lastConnectFailMs = 0;
        }
    }
}
