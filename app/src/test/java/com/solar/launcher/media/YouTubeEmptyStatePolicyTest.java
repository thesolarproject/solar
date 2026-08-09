package com.solar.launcher.media;

import com.solar.launcher.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Empty search results must use song language for Music-origin YouTube searches. */
public class YouTubeEmptyStatePolicyTest {

    @Test
    public void musicOriginUsesSongsLabel() {
        assertEquals(R.string.youtube_empty_songs,
                MediaSuiteHost.youtubeEmptyLabelRes(true));
    }

    @Test
    public void videoOriginUsesVideosLabel() {
        assertEquals(R.string.youtube_empty,
                MediaSuiteHost.youtubeEmptyLabelRes(false));
    }
}
