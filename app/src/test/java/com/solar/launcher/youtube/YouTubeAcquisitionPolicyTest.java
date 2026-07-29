package com.solar.launcher.youtube;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class YouTubeAcquisitionPolicyTest {

    @Test
    public void youtubeIsAlwaysMetadataOnly() {
        assertFalse(YouTubeAcquisitionPolicy.remoteStreamsAllowed());
    }

    @Test
    public void canonicalUrlAcceptsOnlySafeVideoIds() {
        assertEquals("https://www.youtube.com/watch?v=Abc_123-Z",
                YouTubeAcquisitionPolicy.canonicalUrl(
                        new YouTubeVideo("Abc_123-Z", "", "", "")));
        assertEquals("", YouTubeAcquisitionPolicy.canonicalUrl(
                new YouTubeVideo("bad?id=1", "", "", "")));
    }

    @Test
    public void soulseekQueryUsesVisibleMetadataOnly() {
        assertEquals("Song Title Artist Name",
                YouTubeAcquisitionPolicy.soulseekQuery(
                        new YouTubeVideo("abc123", "Song\nTitle", "Artist Name", "")));
    }
}
