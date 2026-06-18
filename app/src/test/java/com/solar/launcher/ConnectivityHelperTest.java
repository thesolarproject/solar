package com.solar.launcher;

import org.junit.Test;

public class ConnectivityHelperTest {
    @Test
    public void itemNeedsInternetForDiscovery_reachAndThemesOnly() {
        if (!ConnectivityHelper.itemNeedsInternetForDiscovery(HomeMenuConfig.ID_SOULSEEK)) {
            throw new AssertionError("reach");
        }
        if (!ConnectivityHelper.itemNeedsInternetForDiscovery(HomeMenuConfig.ID_THEMES)) {
            throw new AssertionError("themes");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery(HomeMenuConfig.ID_PODCASTS)) {
            throw new AssertionError("podcasts not discovery-gated");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery(HomeMenuConfig.ID_MUSIC)) {
            throw new AssertionError("music offline ok");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery(HomeMenuConfig.ID_PC_UPLOAD)) {
            throw new AssertionError("pc upload is local network");
        }
    }

    @Test
    public void itemNeedsInternet_matchesDiscovery() {
        if (ConnectivityHelper.itemNeedsInternet(HomeMenuConfig.ID_PODCASTS)) {
            throw new AssertionError("podcasts action uses requireInternet directly");
        }
        if (!ConnectivityHelper.itemNeedsInternet(HomeMenuConfig.ID_SOULSEEK)) {
            throw new AssertionError("reach");
        }
    }

    @Test
    public void itemNeedsLocalNetwork_pcUploadOnly() {
        if (!ConnectivityHelper.itemNeedsLocalNetwork(HomeMenuConfig.ID_PC_UPLOAD)) {
            throw new AssertionError("pc upload");
        }
        if (ConnectivityHelper.itemNeedsLocalNetwork(HomeMenuConfig.ID_PODCASTS)) {
            throw new AssertionError("podcasts not local-only");
        }
    }

    @Test
    public void shouldShowHomeShortcut_podcastsOfflineWithSaved() {
        if (!ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_PODCASTS, false, false, true)) {
            throw new AssertionError("podcasts with saved offline");
        }
        if (ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_PODCASTS, false, false, false)) {
            throw new AssertionError("podcasts without saved offline");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_PODCASTS, true, true, false)) {
            throw new AssertionError("podcasts online");
        }
    }

    @Test
    public void shouldShowHomeShortcut_reachAndPcUpload() {
        if (ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_SOULSEEK, false, true, false)) {
            throw new AssertionError("reach offline");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_SOULSEEK, true, false, false)) {
            throw new AssertionError("reach online");
        }
        if (ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_PC_UPLOAD, true, false, false)) {
            throw new AssertionError("pc upload needs lan");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut(HomeMenuConfig.ID_PC_UPLOAD, false, true, false)) {
            throw new AssertionError("pc upload on lan");
        }
    }

    @Test
    public void shouldShowMenuItem_nullId() {
        if (ConnectivityHelper.shouldShowMenuItem(null, null)) {
            throw new AssertionError("null id");
        }
    }
}
