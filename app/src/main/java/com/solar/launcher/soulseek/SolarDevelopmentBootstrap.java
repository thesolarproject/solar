package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.ReachPolicy;

/**
 * One-shot per process: ping developer inboxes when Reach comes online and ensure
 * the virtual Solar Development inbox row exists locally.
 */
public final class SolarDevelopmentBootstrap {
    private static volatile boolean startupDone;

    private SolarDevelopmentBootstrap() {}

    /** After Soulseek login — marker-only Reach intro PM + diagnostic batch (once per app start). */
    public static void onSoulseekConnected(final Context context, final SharedPreferences prefs,
            final SoulseekClient client) {
        if (context == null || prefs == null) return;
        ensureVirtualInboxPlaceholder(context, prefs);
        if (!ReachPolicy.isMasterEnabled(prefs)) return;
        if (startupDone) return;
        startupDone = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendDeveloperOnlinePing(context.getApplicationContext(), prefs, client);
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException ignored) {}
                SolarDiagnosticReporter.onReachInternetAvailable(context.getApplicationContext(), prefs);
            }
        }, "SolarDevBootstrap").start();
    }

    /** Marker-only wire PM — legacy clients see Reach intro; Reach UI hides it. */
    static void sendDeveloperOnlinePing(Context context, SharedPreferences prefs,
            SoulseekClient client) {
        String ping = ReachIntroMessage.MARKER;
        SolarDeveloperMessaging.sendWireFanOut(context, prefs, client,
                SolarDeveloperAccounts.developerUsernames(), ping);
    }

    /** Empty local thread row so every user sees Solar Development in Messages. */
    public static void ensureVirtualInboxPlaceholder(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        String peer = SolarDeveloperAccounts.VIRTUAL_PEER;
        java.util.List<SoulseekMessaging.Message> existing =
                SoulseekMessaging.thread(context, prefs, peer);
        if (existing != null && !existing.isEmpty()) return;
        SoulseekMessaging.append(context, prefs, new SoulseekMessaging.Message(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                (int) (System.currentTimeMillis() / 1000L),
                peer, "", false));
    }

    /** Test hook — reset process guard. */
    static void resetForTest() {
        startupDone = false;
    }
}
