package com.solar.launcher;

import org.junit.Test;

public class ConnectivityHelperTest {
    @Test
    public void itemNeedsInternetForDiscovery_reachOnly() {
        if (!ConnectivityHelper.itemNeedsInternetForDiscovery("soulseek")) {
            throw new AssertionError("reach");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery("themes")) {
            throw new AssertionError("themes works offline");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery("podcasts")) {
            throw new AssertionError("podcasts not discovery-gated");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery("music")) {
            throw new AssertionError("music offline ok");
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery("pc_upload")) {
            throw new AssertionError("pc upload is local network");
        }
    }

    @Test
    public void itemNeedsInternet_matchesDiscovery() {
        if (ConnectivityHelper.itemNeedsInternet("podcasts")) {
            throw new AssertionError("podcasts action uses requireInternet directly");
        }
        if (!ConnectivityHelper.itemNeedsInternet("soulseek")) {
            throw new AssertionError("reach");
        }
    }

    @Test
    public void itemNeedsLocalNetwork_pcUploadOnly() {
        if (!ConnectivityHelper.itemNeedsLocalNetwork("pc_upload")) {
            throw new AssertionError("pc upload");
        }
        if (ConnectivityHelper.itemNeedsLocalNetwork("podcasts")) {
            throw new AssertionError("podcasts not local-only");
        }
    }

    @Test
    public void shouldShowHomeShortcut_podcastsOfflineWithSaved() {
        if (!ConnectivityHelper.shouldShowHomeShortcut("podcasts", false, false, true)) {
            throw new AssertionError("podcasts with saved offline");
        }
        if (ConnectivityHelper.shouldShowHomeShortcut("podcasts", false, false, false)) {
            throw new AssertionError("podcasts without saved offline");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut("podcasts", true, true, false)) {
            throw new AssertionError("podcasts online");
        }
    }

    @Test
    public void shouldShowHomeShortcut_themesOffline() {
        if (!ConnectivityHelper.shouldShowHomeShortcut("themes", false, false, false)) {
            throw new AssertionError("themes offline");
        }
    }

    @Test
    public void shouldShowHomeShortcut_reachAndPcUpload() {
        if (ConnectivityHelper.shouldShowHomeShortcut("soulseek", false, true, false)) {
            throw new AssertionError("reach offline");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut("soulseek", true, false, false)) {
            throw new AssertionError("reach online");
        }
        if (ConnectivityHelper.shouldShowHomeShortcut("pc_upload", true, false, false)) {
            throw new AssertionError("pc upload needs lan");
        }
        if (!ConnectivityHelper.shouldShowHomeShortcut("pc_upload", false, true, false)) {
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
