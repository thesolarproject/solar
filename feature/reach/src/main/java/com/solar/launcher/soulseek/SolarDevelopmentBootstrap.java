package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

/** Seed @SolarDeveloper welcome + migrate legacy peer keys. */
public final class SolarDevelopmentBootstrap {
    private static final String PREF_WELCOME_SEEDED = "solar_developer_welcome_seeded_v1";
    private static final String PREF_WELCOME_CLOCK_FIXED = "solar_developer_welcome_clock_fixed_v1";
    private static final int EPOCH_VALID_AFTER = 946684800; // 2000-01-01 UTC

    private SolarDevelopmentBootstrap() {}

    public static void ensureReady(Context context, SharedPreferences prefs) {
        if (context == null) return;
        SharedPreferences p = prefs != null ? prefs
                : context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        SoulseekMessaging.migrateDeveloperPeers(context);
        seedWelcomeIfNeeded(context, p);
        maybeFixWelcomeClock(context, p);
    }

    static void seedWelcomeIfNeeded(Context context, SharedPreferences prefs) {
        if (prefs.getBoolean(PREF_WELCOME_SEEDED, false)) return;
        int ts = (int) (System.currentTimeMillis() / 1000L);
        if (ts < 0) ts = 0;
        SoulseekMessaging.append(context, new SoulseekMessaging.Message(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                ts,
                SolarDeveloperAccounts.SOLAR_DEVELOPER,
                SolarDeveloperAccounts.welcomeMessageBody(),
                true));
        prefs.edit().putBoolean(PREF_WELCOME_SEEDED, true).apply();
    }

    /** If welcome was stored with 1970-era clock, rewrite once when real time is available. */
    static void maybeFixWelcomeClock(Context context, SharedPreferences prefs) {
        if (prefs.getBoolean(PREF_WELCOME_CLOCK_FIXED, false)) return;
        int now = (int) (System.currentTimeMillis() / 1000L);
        if (now < EPOCH_VALID_AFTER) return;
        SoulseekMessaging.Message last = SoulseekMessaging.lastMessageForPeer(
                context, SolarDeveloperAccounts.SOLAR_DEVELOPER);
        // Only rewrite if we only have welcome (or first welcome has bad ts)
        java.util.List<SoulseekMessaging.Message> thread =
                SoulseekMessaging.thread(context, SolarDeveloperAccounts.SOLAR_DEVELOPER);
        for (SoulseekMessaging.Message m : thread) {
            if (m.incoming && SolarDeveloperAccounts.welcomeMessageBody().equals(m.text)
                    && m.timestamp < EPOCH_VALID_AFTER) {
                SoulseekMessaging.updateWelcomeTimestamp(context, now);
                break;
            }
        }
        prefs.edit().putBoolean(PREF_WELCOME_CLOCK_FIXED, true).apply();
    }
}
