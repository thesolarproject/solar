package com.solar.launcher.media;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.solar.launcher.MainActivity;
import com.solar.launcher.PlaybackCoordinator;
import com.solar.launcher.media.MediaTransportBar;

/** Bridges {@link MediaSuiteHost} to {@link MainActivity} without bloating the activity file. */
public final class MediaSuiteHostAdapter implements MediaSuiteHost.Host {
    private final MainActivity act;

    public MediaSuiteHostAdapter(MainActivity activity) {
        act = activity;
    }

    @Override public Context context() { return act; }
    @Override public Activity activity() { return act; }
    @Override public SharedPreferences prefs() { return act.getPrefs(); }
    @Override public PlaybackCoordinator playback() { return act.getPlayback(); }

    @Override public Button createListButton(String label) { return act.mediaCreateListButton(label); }
    @Override public void clickFeedback() { act.mediaClickFeedback(); }
    @Override public boolean requireInternet(int toastRes) { return act.mediaRequireInternet(toastRes); }
    @Override public void runOnUiThread(Runnable r) { act.runOnUiThread(r); }

    @Override public void changeScreen(int state) { act.mediaChangeScreen(state); }
    @Override public int getCurrentScreenState() { return act.getCurrentScreenState(); }
    @Override public void setBrowserStatusTitle(String title) { act.setBrowserStatusTitle(title); }

    @Override public View layoutBrowserMode() { return act.findViewById(com.solar.launcher.R.id.layout_browser_mode); }
    @Override public View layoutPlayerMode() { return act.findViewById(com.solar.launcher.R.id.layout_player_mode); }
    @Override public View layoutMainMenu() { return act.findViewById(com.solar.launcher.R.id.layout_main_menu); }
    @Override public View layoutSettingsMode() { return act.findViewById(com.solar.launcher.R.id.layout_settings_mode); }
    @Override public LinearLayout containerBrowserItems() {
        return (LinearLayout) act.findViewById(com.solar.launcher.R.id.container_browser_items);
    }
    @Override public ListView listVirtualSongs() { return act.getListVirtualSongs(); }

    @Override public int getScreenWidthPx() { return act.getScreenWidthPx(); }
    @Override public int y1RowHeightPx() { return act.mediaY1RowHeightPx(); }
    @Override public int messagingRowWidthPx() { return act.mediaMessagingRowWidthPx(); }

    @Override public void applyReachBrowseLayoutMode() { act.mediaApplyReachBrowseLayoutMode(); }
    @Override public void showReachBrowseList(boolean show) { act.mediaDelegateShowReachBrowseList(show); }

    @Override public void pauseMusicPlayback() { act.mediaPauseMusicPlayback(); }
    @Override public void stopMusicPlayback() { act.mediaStopMusicPlayback(); }
    @Override public MediaTransportBar playerTransportBar() { return act.getPlayerTransport(); }
    @Override public MediaTransportBar videoTransportBar() { return act.getVideoTransport(); }
    @Override public void resetBrowserListHost() { act.resetBrowserListHost(); }
    @Override public void showVirtualSongList(boolean virtual) { act.showVirtualSongList(virtual); }
    @Override public void setStatusBarVisible(boolean visible) { act.mediaSetStatusBarVisible(visible); }
    @Override public void refreshPlayerUi() { act.mediaRefreshPlayerUi(); }
    @Override public void exitToHomeMenu() { act.mediaExitToHomeMenu(); }

    @Override public String getString(int resId) { return act.getString(resId); }
    @Override public String getString(int resId, Object arg) { return act.getString(resId, arg); }
    @Override public android.content.res.Resources getResources() { return act.getResources(); }

    @Override public <T extends View> T findViewById(int id) { return act.findViewById(id); }
}
