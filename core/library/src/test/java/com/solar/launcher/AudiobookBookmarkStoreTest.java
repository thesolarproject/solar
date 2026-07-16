package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudiobookBookmarkStoreTest {

    @Test
    public void isUnderAudiobooks_detectsPath() {
        assertTrue(AudiobookBookmarkStore.isUnderAudiobooks(
                new java.io.File("/storage/sdcard0/Audiobooks/Book/ch01.mp3")));
        assertFalse(AudiobookBookmarkStore.isUnderAudiobooks(
                new java.io.File("/storage/sdcard0/Music/album/track.mp3")));
    }

    @Test
    public void clampResumePosition_skipsTinyAndTail() {
        assertEquals(0, AudiobookBookmarkStore.clampResumePosition(1000, 600000));
        assertEquals(120000, AudiobookBookmarkStore.clampResumePosition(120000, 600000));
        assertEquals(0, AudiobookBookmarkStore.clampResumePosition(590000, 600000));
    }

    @Test
    public void primaryRoot_isSdcard0() {
        assertEquals("/storage/sdcard0/Audiobooks",
                AudiobookBookmarkStore.primaryRoot().getAbsolutePath());
    }
}
