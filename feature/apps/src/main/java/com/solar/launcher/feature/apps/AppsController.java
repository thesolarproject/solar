package com.solar.launcher.feature.apps;

import android.view.View;

import com.solar.launcher.contracts.FeatureBrowserHost;
import com.solar.launcher.contracts.FeatureIds;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Apps launcher screen logic — UI rows supplied by shell delegate. */
public final class AppsController {
    public interface UiDelegate {
        String appsStatusTitle();

        String appsPathLabel();

        String loadingLabel();

        String emptyLabel();

        View createBackButton(Runnable onBack);

        View createLoadingRow();

        View createEmptyRow();

        View createAppRow(AppLauncher.Entry app);

        void onListPopulated(List<AppLauncher.Entry> apps);

        void applyBrowserLayout();

        void setBrowserPath(String path);

        void setBrowserScrollVisible(boolean visible);

        void setVirtualListVisible(boolean visible);
    }

    private final AtomicInteger generation = new AtomicInteger();

    public int beginLoad() {
        return generation.incrementAndGet();
    }

    public boolean isStale(int gen) {
        return gen != generation.get();
    }

    public void show(FeatureBrowserHost host, UiDelegate ui, int gen) {
        ui.setBrowserScrollVisible(true);
        ui.setVirtualListVisible(false);
        host.clearBrowserItems();
        host.updateStatusBarTitle(ui.appsStatusTitle());
        ui.setBrowserPath(ui.appsPathLabel());
        host.addBrowserRow(ui.createBackButton(new Runnable() {
            @Override public void run() { host.goHome(); }
        }));
        host.addBrowserRow(ui.createLoadingRow());
        ui.applyBrowserLayout();
        host.startBackground(new Runnable() {
            @Override public void run() {
                final List<AppLauncher.Entry> apps = AppLauncher.load(
                        host.getContext().getPackageManager(), host.getSelfPackageName());
                host.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!host.isFeatureActive(FeatureIds.APPS) || isStale(gen)) return;
                        populate(host, ui, apps);
                    }
                });
            }
        });
    }

    public void populate(FeatureBrowserHost host, UiDelegate ui, List<AppLauncher.Entry> apps) {
        host.clearBrowserItems();
        host.addBrowserRow(ui.createBackButton(new Runnable() {
            @Override public void run() { host.goHome(); }
        }));
        if (apps == null || apps.isEmpty()) {
            host.addBrowserRow(ui.createEmptyRow());
            host.clearPreviewPane();
        } else {
            for (AppLauncher.Entry app : apps) {
                host.addBrowserRow(ui.createAppRow(app));
            }
        }
        ui.applyBrowserLayout();
        ui.onListPopulated(apps);
        host.requestBrowserFocus(1);
    }
}
