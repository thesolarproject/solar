package com.solar.launcher.contracts;

import android.view.View;

/** Browser-mode UI host callbacks — shared by music, apps, podcasts, Reach list screens. */
public interface FeatureBrowserHost extends SolarHost {
    void clearBrowserItems();

    void addBrowserRow(View row);

    void setBrowserPath(String path);

    void setBrowserScrollVisible(boolean visible);

    void setVirtualListVisible(boolean visible);

    void requestBrowserFocus(int childIndex);

    void applyBrowserLayout();

    void clearPreviewPane();

    String getString(int resId);

    void runOnUiThread(Runnable r);

    void startBackground(Runnable work);

    boolean isFeatureActive(String featureId);

    void goHome();

    boolean launchPackage(String packageName);

    String getSelfPackageName();
}
