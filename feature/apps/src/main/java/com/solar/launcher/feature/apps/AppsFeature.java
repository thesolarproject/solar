package com.solar.launcher.feature.apps;

import android.view.KeyEvent;

import com.solar.launcher.contracts.FeatureBrowserHost;
import com.solar.launcher.contracts.FeatureIds;
import com.solar.launcher.contracts.SolarFeature;
import com.solar.launcher.contracts.SolarHost;
import com.solar.launcher.contracts.SolarScreen;

/** Installed-apps launcher feature module. */
public final class AppsFeature implements SolarFeature {
    private final AppsController controller = new AppsController();
    private FeatureBrowserHost browserHost;
    private AppsController.UiDelegate uiDelegate;
    private int loadGen;

    public void bind(FeatureBrowserHost host, AppsController.UiDelegate delegate) {
        browserHost = host;
        uiDelegate = delegate;
    }

    public AppsController getController() {
        return controller;
    }

    @Override public String id() { return FeatureIds.APPS; }

    @Override public SolarScreen createScreen(SolarHost host) {
        return new AppsScreen(host);
    }

    @Override public void onEnter() {
        if (browserHost == null || uiDelegate == null) return;
        loadGen = controller.beginLoad();
        controller.show(browserHost, uiDelegate, loadGen);
    }

    @Override public void onLeave(String toFeatureId) {}

    @Override public boolean handlesKey(KeyEvent event) { return false; }

    @Override public boolean needsInternet() { return false; }

    static final class AppsScreen implements SolarScreen {
        private final SolarHost host;
        AppsScreen(SolarHost host) { this.host = host; }
        @Override public android.view.View getRootView() {
            return new android.view.View(host.getContext());
        }
        @Override public void onShow() {}
        @Override public void onHide() {}
    }
}
