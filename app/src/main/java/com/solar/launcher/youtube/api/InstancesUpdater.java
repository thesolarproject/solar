package com.solar.launcher.youtube.api;

import android.content.Context;

import com.solar.launcher.SolarLog;
import com.solar.launcher.net.SolarHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 2026-07-15 — Refresh Solar YouTube instance pools from notPipe.json shape.
 * Layman: downloads a list of working YouTube frontends so one dead site does not kill browse.
 * Technical: keys invidious / piped / ytapilegacy; fail-open keeps previous seeds.
 * Reversal: delete; InstancesConfig seeds-only forever.
 */
public final class InstancesUpdater {

    private static final String TAG = "YtInstancesUpdater";
    /** Refresh at most once per day unless forced. */
    private static final long MIN_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);

    private InstancesUpdater() {}

    /**
     * Background-safe: may hit the network. Call from a worker thread.
     * @return true when lists were replaced from remote JSON
     */
    public static boolean updateIfStale(Context ctx, boolean force) {
        if (ctx == null) return false;
        InstancesConfig cfg = new InstancesConfig(ctx);
        cfg.ensureSeeds();
        long last = cfg.getLastUpdateMs();
        // Older builds stamped the built-in seeds as if they came from the remote
        // catalog. Treat that state as stale so existing devices recover on the
        // next YouTube request without requiring a data wipe.
        boolean refreshPending = cfg.isRemoteRefreshPending();
        if (!force && !refreshPending && last > 0
                && (System.currentTimeMillis() - last) < MIN_INTERVAL_MS) {
            return false;
        }
        return updateNow(ctx, cfg);
    }

    /** Parse notPipe.json body into four string lists (package-private for tests). */
    static ParsedInstances parseNotPipeJson(String body) throws Exception {
        JSONObject obj = new JSONObject(body);
        return new ParsedInstances(
                readStringArray(obj, "invidious"),
                readStringArray(obj, "piped"),
                readStringArray(obj, "ytapilegacy"),
                readStringArray(obj, "yt2009"));
    }

    private static boolean updateNow(Context ctx, InstancesConfig cfg) {
        try {
            // Keep catalog refresh bounded so a dead registry cannot consume the
            // entire YouTube search timeout budget on API-19 devices.
            String body = new String(SolarHttp.getBytesQuick(
                    cfg.getUpdateUrl(), "application/json", "SolarLauncher/YouTube", 3, 6),
                    "UTF-8");
            ParsedInstances parsed = parseNotPipeJson(body);
            if (parsed.invidious.isEmpty() && parsed.piped.isEmpty()
                    && parsed.ytapi.isEmpty() && parsed.yt2009.isEmpty()) {
                SolarLog.w(TAG, "remote instance JSON empty — keep seeds");
                return false;
            }
            // 2026-07-19 — Keep HTTP seeds after remote refresh.
            // Layman: if new HTTPS mirrors fail TLS on Y2/A5, old cleartext seeds still work.
            // Was: replace lists wholesale → HTTPS-only remote stranded API-19 devices.
            // Reversal: save parsed lists without mergeWithSeeds.
            List<String> inv = parsed.invidious.isEmpty()
                    ? cfg.getInvidious()
                    : mergeWithSeeds(parsed.invidious, InstancesConfig.seedInvidious());
            List<String> piped = parsed.piped.isEmpty()
                    ? cfg.getPiped() : parsed.piped;
            List<String> yt = parsed.ytapi.isEmpty()
                    ? cfg.getYtApiLegacy()
                    : mergeWithSeeds(parsed.ytapi, InstancesConfig.seedYtApi());
            List<String> y29 = parsed.yt2009.isEmpty()
                    ? cfg.getYt2009() : parsed.yt2009;
            cfg.saveLists(inv, piped, yt, y29);
            SolarLog.i(TAG, "updated instances inv=" + inv.size()
                    + " piped=" + piped.size() + " ytapi=" + yt.size()
                    + " yt2009=" + y29.size());
            return true;
        } catch (Exception e) {
            SolarLog.w(TAG, "instance update failed: " + e.getMessage());
            return false;
        }
    }

    private static List<String> readStringArray(JSONObject obj, String key) throws Exception {
        List<String> out = new ArrayList<String>();
        if (!obj.has(key)) return out;
        JSONArray arr = obj.getJSONArray(key);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    /**
     * 2026-07-19 — Append missing seed hosts onto a remote list (no duplicates).
     * Layman: keep the known-good backup frontends after a list refresh.
     * Package-visible for unit tests.
     */
    static List<String> mergeWithSeeds(List<String> remote, List<String> seeds) {
        List<String> out = new ArrayList<String>();
        if (remote != null) {
            for (int i = 0; i < remote.size(); i++) {
                String s = remote.get(i);
                if (s != null && s.length() > 0 && !out.contains(s)) out.add(s);
            }
        }
        if (seeds != null) {
            for (int i = 0; i < seeds.size(); i++) {
                String s = seeds.get(i);
                if (s != null && s.length() > 0 && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** Holder for unit tests / updater. */
    static final class ParsedInstances {
        final List<String> invidious;
        final List<String> piped;
        final List<String> ytapi;
        final List<String> yt2009;

        ParsedInstances(List<String> invidious, List<String> piped,
                        List<String> ytapi, List<String> yt2009) {
            this.invidious = invidious;
            this.piped = piped;
            this.ytapi = ytapi;
            this.yt2009 = yt2009;
        }
    }
}
