package com.solar.launcher.transfer;

/**
 * Network gating shared by transfer workers and their UI hosts.
 *
 * <p>The policy is deliberately Android-free so pause/resume decisions remain deterministic and
 * can be exercised by local unit tests. A Wi-Fi-only job must have both an internet-capable route
 * and an associated Wi-Fi network; another active route must not silently bypass the user's
 * Wi-Fi-only choice.</p>
 */
public final class TransferNetworkPolicy {
    public static final String PREF_AUTO_RESUME_WIFI = "downloads_auto_resume_wifi";

    private TransferNetworkPolicy() {}

    public static boolean shouldPause(
            boolean wifiOnly, boolean internetAvailable, boolean wifiAssociated) {
        return !internetAvailable || (wifiOnly && !wifiAssociated);
    }

    public static boolean shouldAutoResume(
            boolean enabled,
            boolean wifiOnly,
            boolean internetAvailable,
            boolean wifiAssociated) {
        return enabled && !shouldPause(wifiOnly, internetAvailable, wifiAssociated);
    }

    /**
     * Only direct HTTP has enough durable information to restart without reconstructing a
     * provider login, peer session, playback queue, or podcast screen.
     */
    public static boolean isSafeRestartAutoResumeCandidate(TransferJobStore.Job job) {
        return job != null
                && job.provider == TransferJobStore.Provider.DIRECT
                && job.state == TransferJobStore.State.PAUSED
                && TransferJobStore.DETAIL_PAUSED_AFTER_RESTART.equals(job.detail)
                && job.remoteId != null
                && job.remoteId.length() > 0
                && job.targetPath != null
                && job.targetPath.length() > 0;
    }
}
