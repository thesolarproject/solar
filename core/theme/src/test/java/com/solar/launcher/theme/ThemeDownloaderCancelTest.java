package com.solar.launcher.theme;

import org.junit.Test;

public class ThemeDownloaderCancelTest {

    @Test
    public void cancelFlagThrowsOnCheck() throws Exception {
        ThemeDownloader.clearCancel();
        ThemeDownloader.checkDownloadCancel();
        ThemeDownloader.requestCancel();
        try {
            ThemeDownloader.checkDownloadCancel();
            throw new AssertionError("expected cancel");
        } catch (InterruptedException expected) {
            // ok
        } finally {
            ThemeDownloader.clearCancel();
        }
    }
}
