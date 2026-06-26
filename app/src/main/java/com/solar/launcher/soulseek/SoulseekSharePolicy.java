package com.solar.launcher.soulseek;

/**
 * Wi‑Fi + NAT-PMP gates for Soulseek sharing and peer profile/browse exchange.
 * When both are OK, sharing stays active on battery (Reach open or closed).
 * Without Wi‑Fi or a mapped listen port, sharing and uploads are off so Reach
 * does not keep the Soulseek client running.
 */
public final class SoulseekSharePolicy {
    public enum State { OFF, ACTIVE, DRAINING }

    static final long DRAIN_TIMEOUT_MS = 30_000;

    private volatile State state = State.OFF;
    private volatile long drainStartedAt;
    private volatile boolean userEnabled = true;
    private volatile boolean reachMasterEnabled = true;
    private volatile boolean wifi;
    private volatile boolean peerConnectivityOk;

    public State state() {
        return state;
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean enabled) {
        userEnabled = enabled;
    }

    public boolean isReachMasterEnabled() {
        return reachMasterEnabled;
    }

    public void setReachMasterEnabled(boolean enabled) {
        reachMasterEnabled = enabled;
    }

    /**
     * @param peerConnectivityOk true when NAT-PMP mapped the listen port ({@code ReachPeerConnectivity.AVAILABLE})
     * @param uploadActive true while an outbound upload transfer is in progress
     */
    public void update(boolean wifi, boolean peerConnectivityOk, boolean uploadActive) {
        this.wifi = wifi;
        this.peerConnectivityOk = peerConnectivityOk;
        if (!reachMasterEnabled || !userEnabled || !wifi) {
            state = State.OFF;
            drainStartedAt = 0;
            return;
        }
        if (!peerConnectivityOk) {
            if (uploadActive) {
                state = State.DRAINING;
                if (drainStartedAt == 0) {
                    drainStartedAt = System.currentTimeMillis();
                }
            } else if (state == State.DRAINING && drainStartedAt > 0
                    && System.currentTimeMillis() - drainStartedAt >= DRAIN_TIMEOUT_MS) {
                state = State.OFF;
                drainStartedAt = 0;
            } else if (!uploadActive) {
                state = State.OFF;
                drainStartedAt = 0;
            }
            return;
        }
        state = State.ACTIVE;
        drainStartedAt = 0;
    }

    public void onUploadQueueEmpty() {
        if (state == State.DRAINING) {
            state = State.OFF;
            drainStartedAt = 0;
        }
    }

    /** Publish share counts on the server when Wi‑Fi + NAT-PMP are OK. */
    public boolean announceShares() {
        return reachMasterEnabled && userEnabled && wifi && peerConnectivityOk;
    }

    /**
     * Serve indexed files on inbound peer browse (code 4/36) when sharing is enabled.
     * Unlike {@link #announceShares()}, does not require NAT — if a peer reached our listen
     * socket (LAN, pierce, or mapped port), return the share list.
     */
    public boolean serveSharesToPeer() {
        return reachMasterEnabled && userEnabled && wifi;
    }

    public boolean acceptNewUploads() {
        return state == State.ACTIVE;
    }

    public boolean processUploadQueue() {
        return state == State.ACTIVE || state == State.DRAINING;
    }

    private volatile boolean messagingEnabled;

    public void setMessagingEnabled(boolean enabled) {
        messagingEnabled = enabled;
    }

    /** Keep server session on Wi‑Fi; messaging needs server only, not NAT-PMP. */
    public boolean shouldKeepClientAlive() {
        if (!reachMasterEnabled || !wifi) return false;
        if (messagingEnabled) return true;
        // Sharing needs a login + listen socket to probe NAT and answer browse requests.
        if (userEnabled || state == State.DRAINING) return true;
        return false;
    }

    /** True while NAT-PMP probe has not finished (client may connect once to test). */
    public boolean isPeerConnectivityOk() {
        return peerConnectivityOk;
    }
}
