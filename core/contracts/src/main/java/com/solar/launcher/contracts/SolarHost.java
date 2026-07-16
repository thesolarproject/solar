package com.solar.launcher.contracts;

import android.content.Context;

/** Shell callbacks exposed to feature modules. */
public interface SolarHost {
    Context getContext();

    SolarSkin getSkin();

    void changeFeature(String featureId);

    void showKeyboard(KeyboardRequest request);

    void dismissKeyboard();

    void updateStatusBarTitle(String title);

    void clickFeedback();
}
