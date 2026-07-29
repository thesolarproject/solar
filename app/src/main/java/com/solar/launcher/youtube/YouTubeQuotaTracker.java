package com.solar.launcher.youtube;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/** Local estimate of official YouTube Data API quota consumed by Solar. */
public final class YouTubeQuotaTracker {

    public enum Operation {
        POPULAR(1),
        SEARCH(101),
        COMMENTS(1),
        SUBSCRIPTIONS(1),
        LIKED_VIDEOS(1);

        final int estimatedUnits;

        Operation(int estimatedUnits) {
            this.estimatedUnits = estimatedUnits;
        }
    }

    private static final String PREFS = "solar_youtube_quota";
    private static final String KEY_DAY = "day";
    private static final String KEY_TOTAL = "total";
    private final SharedPreferences prefs;

    public YouTubeQuotaTracker(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void record(Operation operation) {
        if (operation == null) return;
        resetIfNeeded(System.currentTimeMillis());
        String key = operation.name().toLowerCase(Locale.US);
        int nextCount = prefs.getInt(key, 0) + 1;
        int nextTotal = prefs.getInt(KEY_TOTAL, 0) + estimateCost(operation);
        prefs.edit()
                .putInt(key, nextCount)
                .putInt(KEY_TOTAL, nextTotal)
                .commit();
    }

    public synchronized int todayTotal() {
        resetIfNeeded(System.currentTimeMillis());
        return prefs.getInt(KEY_TOTAL, 0);
    }

    public synchronized int todayCount(Operation operation) {
        resetIfNeeded(System.currentTimeMillis());
        if (operation == null) return 0;
        return prefs.getInt(operation.name().toLowerCase(Locale.US), 0);
    }

    public synchronized void clear() {
        prefs.edit().clear().commit();
    }

    static int estimateCost(Operation operation) {
        return operation != null ? operation.estimatedUnits : 0;
    }

    static int dayStamp(long epochMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(epochMs);
        return calendar.get(Calendar.YEAR) * 10_000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    private void resetIfNeeded(long nowMs) {
        int day = dayStamp(nowMs);
        if (prefs.getInt(KEY_DAY, 0) == day) return;
        prefs.edit().clear().putInt(KEY_DAY, day).commit();
    }
}
