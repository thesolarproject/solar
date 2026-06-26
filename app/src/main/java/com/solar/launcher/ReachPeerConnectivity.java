package com.solar.launcher;

import android.content.Context;
import android.os.SystemClock;

import com.solar.launcher.soulseek.SoulseekNatpmp;

/**
 * Reach/Soulseek peer transfer availability — server login can succeed while peer TCP
 * (downloads, browse, sharing uploads) fails on CG-NAT, mobile, or locked-down Wi‑Fi.
 * Uses NAT-PMP outcome as the primary signal; PierceFirewall is not relied on here.
 */
public final class ReachPeerConnectivity {
    public enum State {
        /** Server not probed or NAT probe still running. */
        UNKNOWN,
        /** Listen port mapped — peer transfers should work. */
        AVAILABLE,
        /** No port map after retries — peer transfers unlikely. */
        UNAVAILABLE
    }

    public static final String REASON_NAT_OK = "nat_ok";
    public static final String REASON_NAT_FAILED = "nat_failed";
    public static final String REASON_MOBILE = "mobile";
    public static final String REASON_NO_GATEWAY = "no_gateway";
    public static final String REASON_PORT_LOST = "port_lost";

    private static final long MAP_LOST_GRACE_MS = 90_000;

    private static volatile State state = State.UNKNOWN;
    private static volatile String reason = "";
    private static volatile long lastMappedAtMs = 0;
    private static volatile Callback callback;

    public interface Callback {
        void onReachPeerStateChanged(State state, String reason);
    }

    private ReachPeerConnectivity() {}

    public static void setCallback(Callback cb) {
        callback = cb;
    }

    public static State state() {
        return state;
    }

    public static String reason() {
        return reason;
    }

    public static boolean peersAvailable() {
        return state == State.AVAILABLE;
    }

    public static void reset() {
        state = State.UNKNOWN;
        reason = "";
        lastMappedAtMs = 0;
    }

    public static void onServerLoginOk(Context ctx, SoulseekNatpmp.Result nat) {
        applyNatResult(ctx, nat, false);
    }

    /** NAT-PMP retry loop finished without a successful map. */
    public static void onNatRetriesExhausted(Context ctx, SoulseekNatpmp.Result nat) {
        if (nat != null && nat.mapped()) return;
        applyNatResult(ctx, nat != null ? nat : failedPlaceholder(), true);
    }

    public static void onNatRenewal(Context ctx, SoulseekNatpmp.Result nat) {
        if (nat == null) return;
        if (nat.mapped()) {
            lastMappedAtMs = nowMs();
            if (state != State.AVAILABLE) {
                setState(State.AVAILABLE, REASON_NAT_OK);
            }
            return;
        }
        if (state == State.AVAILABLE && lastMappedAtMs > 0
                && nowMs() - lastMappedAtMs >= MAP_LOST_GRACE_MS) {
            applyNatResult(ctx, nat, true);
        } else if (state == State.UNKNOWN) {
            applyNatResult(ctx, nat, false);
        }
    }

    private static long nowMs() {
        try {
            return SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static void applyNatResult(Context ctx, SoulseekNatpmp.Result nat, boolean finalProbe) {
        if (nat.mapped()) {
            lastMappedAtMs = nowMs();
            setState(State.AVAILABLE, REASON_NAT_OK);
            return;
        }
        if (!finalProbe && state == State.AVAILABLE) {
            return;
        }
        String r = classifyFailure(ctx, nat);
        setState(State.UNAVAILABLE, r);
    }

    private static String classifyFailure(Context ctx, SoulseekNatpmp.Result nat) {
        if (ConnectivityHelper.isMobileActive(ctx)) {
            return REASON_MOBILE;
        }
        if (nat != null && "no_gateway".equals(nat.status)) {
            return REASON_NO_GATEWAY;
        }
        if (state == State.AVAILABLE && lastMappedAtMs > 0) {
            return REASON_PORT_LOST;
        }
        return REASON_NAT_FAILED;
    }

    private static SoulseekNatpmp.Result failedPlaceholder() {
        return new SoulseekNatpmp.Result(0, 0, null, null, "map_failed");
    }

    private static void setState(State next, String reasonCode) {
        if (state == next && reason.equals(reasonCode != null ? reasonCode : "")) {
            return;
        }
        state = next;
        reason = reasonCode != null ? reasonCode : "";
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("state", next.name());
            d.put("reason", reason);
            DebugAgentLog.log(null, "ReachPeerConnectivity.setState", "peer state", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        Callback cb = callback;
        if (cb != null) {
            cb.onReachPeerStateChanged(next, reason);
        }
    }
}
