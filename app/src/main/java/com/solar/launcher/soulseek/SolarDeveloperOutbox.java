package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persists developer-support PMs that failed socket send; flushed on Wi‑Fi with backoff.
 * ponytail: prefs JSON queue — upgrade path is a SQLite outbox if volume grows.
 */
public final class SolarDeveloperOutbox {
    static final String PREF_OUTBOX = "solar_dev_outbox_v1";
    private static final long INTERVAL_WIFI_MS = 60L * 60L * 1000L;
    private static final long INTERVAL_CHARGING_MS = 5L * 60L * 1000L;
    private static volatile long lastFlushMs;

    private SolarDeveloperOutbox() {}

    static void enqueue(Context ctx, String body) {
        if (ctx == null || body == null || body.trim().isEmpty()) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
            JSONArray arr = load(prefs);
            JSONObject item = new JSONObject();
            item.put("body", body);
            item.put("ts", System.currentTimeMillis());
            arr.put(item);
            prefs.edit().putString(PREF_OUTBOX, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Best-effort flush when on Wi‑Fi (hourly; every 5 min on charger). */
    public static void flushIfDue(final Context context, final SharedPreferences prefs,
            final SoulseekClient mainClient, final boolean onWifi, final boolean onCharger) {
        if (context == null || prefs == null || !onWifi) return;
        if (!SolarDeveloperAccounts.isExperimentEnabled(prefs)) return;
        long interval = onCharger ? INTERVAL_CHARGING_MS : INTERVAL_WIFI_MS;
        long now = System.currentTimeMillis();
        if (now - lastFlushMs < interval) return;
        JSONArray pending = load(prefs);
        if (pending.length() == 0) return;
        lastFlushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                flushAll(context.getApplicationContext(), prefs, mainClient);
            }
        }, "SolarDevOutbox").start();
    }

    static int flushAll(Context ctx, SharedPreferences prefs, SoulseekClient mainClient) {
        JSONArray pending = load(prefs);
        if (pending.length() == 0) return 0;
        JSONArray remaining = new JSONArray();
        int delivered = 0;
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String body = item.optString("body", "");
            if (body.isEmpty()) continue;
            boolean sent = SolarDeveloperMessaging.sendWireFanOut(ctx, prefs, mainClient,
                    SolarDeveloperAccounts.developerUsernames(), body);
            if (sent) {
                delivered++;
            } else {
                remaining.put(item);
            }
        }
        prefs.edit().putString(PREF_OUTBOX, remaining.toString()).apply();
        return delivered;
    }

    private static JSONArray load(SharedPreferences prefs) {
        try {
            String raw = prefs.getString(PREF_OUTBOX, "[]");
            return new JSONArray(raw != null ? raw : "[]");
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
