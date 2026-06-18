package com.solar.launcher.podcast;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/** Runs on device/emulator — validates HTTPS podcast stack outside the UI. */
@RunWith(AndroidJUnit4.class)
public class OpenRssClientDeviceTest {
    @Test
    public void device_searchFeaturedFeedsAndDownload() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.solar.launcher.net.TlsHelper.init(ctx.getApplicationContext());

        List<OpenRssClient.Podcast> shows = OpenRssClient.searchPodcasts("technology", 5);
        if (shows.isEmpty()) throw new AssertionError("iTunes search returned 0 on device");

        for (OpenRssClient.Podcast p : PodcastCatalog.FEATURED) {
            List<OpenRssClient.Episode> eps = OpenRssClient.fetchEpisodes(p.feedUrl, 3);
            if (eps.isEmpty()) throw new AssertionError("No episodes for " + p.title);
            OpenRssClient.Episode ep = eps.get(0);
            if (ep.audioUrl == null || ep.audioUrl.isEmpty()) {
                throw new AssertionError("Episode missing audio URL for " + p.title);
            }
            File cache = new File(ctx.getCacheDir(), "podcast_test");
            File audio = OpenRssClient.downloadAudio(cache, ep.audioUrl);
            if (!audio.isFile() || audio.length() < 1024) {
                throw new AssertionError("Download too small for " + p.title + ": " + audio.length());
            }
        }
    }
}
