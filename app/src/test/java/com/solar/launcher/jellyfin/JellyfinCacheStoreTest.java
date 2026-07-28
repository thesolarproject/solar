package com.solar.launcher.jellyfin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JellyfinCacheStoreTest {

    @Test
    public void parseArtistsJson() throws Exception {
        String json = "{\"Items\": ["
                + "{\"Id\": \"1\", \"Name\": \"Abba\", \"AlbumCount\": 2}"
                + "]}";
        List<JellyfinArtist> artists = JellyfinCacheStore.parseArtistsJson(new JSONObject(json));
        assertFalse(artists.isEmpty());
        assertEquals("1", artists.get(0).id);
        assertEquals("Abba", artists.get(0).name);
        assertEquals(2, artists.get(0).albumCount);
        assertEquals("1", artists.get(0).coverArtId);
        assertEquals("A", artists.get(0).indexLetter);
    }

    @Test
    public void parseArtistsJson_empty() throws Exception {
        String json = "{\"Items\": []}";
        List<JellyfinArtist> artists = JellyfinCacheStore.parseArtistsJson(new JSONObject(json));
        assertTrue(artists.isEmpty());
    }

    @Test
    public void parseArtistsJson_nullItems() throws Exception {
        String json = "{}";
        List<JellyfinArtist> artists = JellyfinCacheStore.parseArtistsJson(new JSONObject(json));
        assertTrue(artists.isEmpty());
    }

    @Test
    public void parseArtistsJson_nonAlphaIndex() throws Exception {
        String json = "{\"Items\": ["
                + "{\"Id\": \"1\", \"Name\": \"123 Band\", \"AlbumCount\": 1}"
                + "]}";
        List<JellyfinArtist> artists = JellyfinCacheStore.parseArtistsJson(new JSONObject(json));
        assertFalse(artists.isEmpty());
        assertEquals("#", artists.get(0).indexLetter);
    }
}
