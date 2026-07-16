package com.solar.launcher.contracts;

import android.view.KeyEvent;

/** Feature "app" contract — one implementation per home-menu destination. */
public interface SolarFeature {
    String id();

    SolarScreen createScreen(SolarHost host);

    void onEnter();

    void onLeave(String toFeatureId);

    boolean handlesKey(KeyEvent event);

    boolean needsInternet();
}
