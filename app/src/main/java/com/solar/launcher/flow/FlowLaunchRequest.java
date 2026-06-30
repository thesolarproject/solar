package com.solar.launcher.flow;

/** Entry context for Flow — drives picker skip and scroll target. */
public final class FlowLaunchRequest {

    public final FlowMode mode;
    /** Case-normalized lookup key; null opens at saved/0 index. */
    public final String focusKey;
    public final int returnScreen;
    public final int returnBrowserMode;
    public final int returnPodcastUiMode;
    /** True when user entered from a library section (Back exits Flow, not picker). */
    public final boolean enteredFromSection;

    public FlowLaunchRequest(FlowMode mode, String focusKey, int returnScreen,
            int returnBrowserMode, int returnPodcastUiMode, boolean enteredFromSection) {
        this.mode = mode != null ? mode : FlowMode.UNSPECIFIED;
        this.focusKey = focusKey;
        this.returnScreen = returnScreen;
        this.returnBrowserMode = returnBrowserMode;
        this.returnPodcastUiMode = returnPodcastUiMode;
        this.enteredFromSection = enteredFromSection;
    }

    public static FlowLaunchRequest picker(int returnScreen) {
        return new FlowLaunchRequest(FlowMode.UNSPECIFIED, null, returnScreen,
                0, -1, false);
    }

    public static FlowLaunchRequest direct(FlowMode mode, String focusKey,
            int returnScreen, int returnBrowserMode, int returnPodcastUiMode) {
        return new FlowLaunchRequest(mode, focusKey, returnScreen, returnBrowserMode,
                returnPodcastUiMode, true);
    }

    public boolean skipPicker() {
        return mode != FlowMode.UNSPECIFIED;
    }

    /** Restore last carousel index when no focus key (prefs key per mode). */
    public String lastIndexPrefKey() {
        return "flow_last_index_" + mode.name().toLowerCase(java.util.Locale.US);
    }
}
