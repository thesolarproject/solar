package com.solar.launcher.contracts;

import android.view.View;

/** Root view for a feature screen inside the launcher shell. */
public interface SolarScreen {
    View getRootView();

    void onShow();

    void onHide();
}
