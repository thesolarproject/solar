package com.solar.launcher.soulseek;

/**
 * Charger / Reach-UI gates for Soulseek library sharing.
 * Charging + Wi‑Fi shares in the background (Reach closed, music, idle).
 * On battery, sharing is limited to Reach UI; leaving drains uploads quickly.
 */
public final class SoulseekSharePolicy {
    public enum State { OFF, ACTIVE, DRAINING }

    // ponytail: 30s drain cap; tune on hardware if uploads linger
    static final long DRAIN_TIMEOUT_MS = 30_000;

    private volatile State state = State.OFF;
    private volatile long drainStartedAt;
    private volatile boolean userEnabled = true;

    public State state() {
        return state;
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean enabled) {
        userEnabled = enabled;
    }

    public void update(boolean charging, boolean wifi, boolean reachUi) {
        if (!userEnabled || !wifi) {
            state = State.OFF;
            return;
        }
        if (charging || reachUi) {
            state = State.ACTIVE;
            drainStartedAt = 0;
            return;
        }
        if (state == State.ACTIVE) {
            state = State.DRAINING;
            drainStartedAt = System.currentTimeMillis();
            return;
        }
        if (state == State.DRAINING && drainStartedAt > 0
                && System.currentTimeMillis() - drainStartedAt >= DRAIN_TIMEOUT_MS) {
            state = State.OFF;
        }
    }

    public void onUploadQueueEmpty() {
        if (state == State.DRAINING) {
            state = State.OFF;
            drainStartedAt = 0;
        }
    }

    public boolean announceShares() {
        return state == State.ACTIVE || state == State.DRAINING;
    }

    public boolean acceptNewUploads() {
        return state == State.ACTIVE;
    }

    public boolean processUploadQueue() {
        return state == State.ACTIVE || state == State.DRAINING;
    }
}
