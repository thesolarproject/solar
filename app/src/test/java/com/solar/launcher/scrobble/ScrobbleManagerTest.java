package com.solar.launcher.scrobble;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public class ScrobbleManagerTest {

    @Test
    public void testApiKeysNotHardcoded() {
        // Ensure that DEFAULT_LASTFM_API_KEY does not contain the hardcoded vulnerability value
        assertNotNull(ScrobbleManager.DEFAULT_LASTFM_API_KEY);
        assertNotEquals("c2fd5c517c27633e8ca770c06aefdebc", ScrobbleManager.DEFAULT_LASTFM_API_KEY);

        // Ensure that DEFAULT_LASTFM_API_SECRET does not contain the hardcoded vulnerability value
        assertNotNull(ScrobbleManager.DEFAULT_LASTFM_API_SECRET);
        assertNotEquals("fbd1d34cddbb2aa6d53dc9a3b6807834", ScrobbleManager.DEFAULT_LASTFM_API_SECRET);
    }
}
