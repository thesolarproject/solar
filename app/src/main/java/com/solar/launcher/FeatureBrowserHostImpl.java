package com.solar.launcher;

import android.content.Context;
import android.view.View;

import com.solar.launcher.contracts.FeatureBrowserHost;
import com.solar.launcher.contracts.FeatureIds;
import com.solar.launcher.contracts.KeyboardRequest;
import com.solar.launcher.contracts.SolarSkin;
import com.solar.launcher.theme.ThemeSolarSkin;

/** MainActivity browser-mode host for feature modules. */
public class FeatureBrowserHostImpl implements FeatureBrowserHost {
    private final MainActivity activity;

    public FeatureBrowserHostImpl(MainActivity activity) {
        this.activity = activity;
    }

    @Override public Context getContext() { return activity; }
    @Override public SolarSkin getSkin() { return ThemeSolarSkin.INSTANCE; }
    @Override public void changeFeature(String featureId) { activity.changeFeatureById(featureId); }
    @Override public void showKeyboard(KeyboardRequest request) { activity.showKeyboardRequest(request); }
    @Override public void dismissKeyboard() { activity.dismissKeyboardRequest(); }
    @Override public void updateStatusBarTitle(String title) { activity.setStatusBarTitleExternal(title); }
    @Override public void clickFeedback() { activity.hostClickFeedback(); }

    @Override public void clearBrowserItems() { activity.hostClearBrowserItems(); }
    @Override public void addBrowserRow(View row) { activity.hostAddBrowserRow(row); }
    @Override public void setBrowserPath(String path) { activity.hostSetBrowserPath(path); }
    @Override public void setBrowserScrollVisible(boolean visible) { activity.hostSetBrowserScrollVisible(visible); }
    @Override public void setVirtualListVisible(boolean visible) { activity.hostSetVirtualListVisible(visible); }
    @Override public void requestBrowserFocus(int childIndex) { activity.hostRequestBrowserFocus(childIndex); }
    @Override public void applyBrowserLayout() { activity.applyPodcastBrowserLayout(); }
    @Override public void clearPreviewPane() { activity.clearPodcastPreviewPane(); }
    @Override public String getString(int resId) { return activity.getString(resId); }
    @Override public void runOnUiThread(Runnable r) { activity.runOnUiThread(r); }
    @Override public void startBackground(Runnable work) { new Thread(work).start(); }
    @Override public boolean isFeatureActive(String featureId) {
        if (FeatureIds.APPS.equals(featureId)) return activity.currentScreenState == MainActivity.STATE_APPS;
        return false;
    }
    @Override public void goHome() { activity.changeScreen(MainActivity.STATE_MENU); }
    @Override public boolean launchPackage(String packageName) { return AppLauncherBridge.launch(activity, packageName); }
    @Override public String getSelfPackageName() { return activity.getPackageName(); }
}
