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
    private static final long INTERVAL_WIFI_MS = 15L * 60L * 1000L;
    private static final long INTERVAL_CHARGING_MS = 2L * 60L * 1000L;
    private static final long FLUSH_SOON_DEBOUNCE_MS = 300_000L;
    private static volatile long lastFlushMs;
    private static volatile long lastSoonMs;
    private static volatile boolean soonScheduled;

    private SolarDeveloperOutbox() {}

    /** Queue a body for all three dev accounts (legacy full fan-out). */
    static void enqueue(Context ctx, String body) {
        enqueue(ctx, body, SolarDeveloperAccounts.developerUsernames());
    }

    /** Queue for specific failed recipients only. */
    static void enqueue(Context ctx, String body, String[] recipients) {
        if (ctx == null || body == null || body.trim().isEmpty()) return;
        if (recipients == null || recipients.length == 0) {
            recipients = SolarDeveloperAccounts.developerUsernames();
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
            JSONArray arr = load(prefs);
            JSONObject item = new JSONObject();
            item.put("body", body);
            item.put("ts", System.currentTimeMillis());
            JSONArray rec = new JSONArray();
            for (String r : recipients) {
                if (r != null && !r.isEmpty()) rec.put(r);
            }
            if (rec.length() > 0) item.put("recipients", rec);
            arr.put(item);
            prefs.edit().putString(PREF_OUTBOX, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Kick an async flush soon after a partial send failure (debounced). */
    static void flushSoon(final Context context, final SharedPreferences prefs,
            final SoulseekClient mainClient) {
        if (context == null || prefs == null) return;
        if (!SolarDeveloperAccounts.isExperimentEnabled(prefs)) return;
        long now = System.currentTimeMillis();
        if (now - lastSoonMs < FLUSH_SOON_DEBOUNCE_MS) return;
        lastSoonMs = now;
        if (soonScheduled) return;
        soonScheduled = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException ignored) {}
                try {
                    flushAll(context.getApplicationContext(), prefs, mainClient);
                } finally {
                    soonScheduled = false;
                }
            }
        }, "SolarDevOutboxSoon").start();
    }

    /** Best-effort flush when on Wi‑Fi (15 min; every 2 min on charger). */
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
            if (i > 0) {
                try { Thread.sleep(6000L); } catch (InterruptedException ignored) {}
            }
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String body = item.optString("body", "");
            if (body.isEmpty()) continue;
            String[] recipients = parseRecipients(item);
            SolarDeveloperMessaging.FanOutResult result =
                    SolarDeveloperMessaging.sendWireFanOut(ctx, prefs, mainClient, recipients, body);
            if (result.allSucceeded()) {
                delivered++;
            } else {
                try {
                    String[] failed = result.failedRecipients();
                    if (failed.length > 0) {
                        JSONArray rec = new JSONArray();
                        for (String r : failed) rec.put(r);
                        item.put("recipients", rec);
                    }
                } catch (Exception ignored) {}
                remaining.put(item);
                for (int j = i + 1; j < pending.length(); j++) {
                    if (pending.optJSONObject(j) != null) remaining.put(pending.optJSONObject(j));
                }
                break;
            }
        }
        prefs.edit().putString(PREF_OUTBOX, remaining.toString()).apply();
        return delivered;
    }

    private static String[] parseRecipients(JSONObject item) {
        JSONArray rec = item.optJSONArray("recipients");
        if (rec == null || rec.length() == 0) {
            return SolarDeveloperAccounts.developerUsernames();
        }
        String[] out = new String[rec.length()];
        for (int i = 0; i < rec.length(); i++) {
            out[i] = rec.optString(i, "");
        }
        return out;
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
