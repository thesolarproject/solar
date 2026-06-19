package com.solar.launcher;

/** ponytail: stop background work when leaving a feature screen. */
public final class SessionLifecycle {

    private SessionLifecycle() {}

    public static void onLeaveScreen(MainActivity activity, int from, int to) {
        if (from == to) return;

        if (from == MainActivity.STATE_SOULSEEK && to != MainActivity.STATE_SOULSEEK) {
            if (activity.keepReachStreamHandoffForScreen(to)) {
                activity.pauseSoulseekUiOnly();
            } else if (activity.shouldPreserveSoulseekSharingOnReachExit()) {
                activity.teardownReachUiOnly();
            } else {
                activity.teardownSoulseekSession();
            }
        }
        if (from == MainActivity.STATE_BLUETOOTH && to != MainActivity.STATE_BLUETOOTH) {
            activity.teardownBluetoothSession();
        }
        if (from == MainActivity.STATE_WIFI && to != MainActivity.STATE_WIFI) {
            activity.teardownWifiSession();
        }
        if (from == MainActivity.STATE_PODCASTS && to != MainActivity.STATE_PODCASTS
                && to != MainActivity.STATE_PLAYER) {
            activity.teardownPodcastSession();
        }
        if (from == MainActivity.STATE_SETTINGS && to != MainActivity.STATE_SETTINGS
                && to != MainActivity.STATE_WIFI_KEYBOARD) {
            activity.teardownSettingsSession();
        }
        if (from == MainActivity.STATE_BROWSER && to != MainActivity.STATE_BROWSER) {
            activity.teardownBrowserSession();
        }
        activity.sessionSweepHandlers(from, to);
    }
}
