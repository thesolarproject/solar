package com.solar.launcher.plex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlexCacheStoreTest {

    @Test
    public void testParseArtistsJson_NullRoot() throws Exception {
        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(null);
        assertNotNull(artists);
        assertTrue(artists.isEmpty());
    }

    @Test
    public void testParseArtistsJson_NoMediaContainer() throws Exception {
        JSONObject root = new JSONObject();
        root.put("OtherKey", "Value");

        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(root);
        assertNotNull(artists);
        assertTrue(artists.isEmpty());
    }

    @Test
    public void testParseArtistsJson_NoMetadata() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject mc = new JSONObject();
        mc.put("size", 0);
        root.put("MediaContainer", mc);

        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(root);
        assertNotNull(artists);
        assertTrue(artists.isEmpty());
    }

    @Test
    public void testParseArtistsJson_EmptyMetadata() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject mc = new JSONObject();
        mc.put("Metadata", new JSONArray());
        root.put("MediaContainer", mc);

        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(root);
        assertNotNull(artists);
        assertTrue(artists.isEmpty());
    }

    @Test
    public void testParseArtistsJson_ValidArtists() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject mc = new JSONObject();
        JSONArray metadata = new JSONArray();

        JSONObject artist1 = new JSONObject();
        artist1.put("ratingKey", "1001");
        artist1.put("title", "Adele");
        artist1.put("childCount", 4);

        JSONObject artist2 = new JSONObject();
        artist2.put("ratingKey", "1002");
        artist2.put("title", "beatles");
        artist2.put("childCount", 12);

        metadata.put(artist1);
        metadata.put(artist2);
        mc.put("Metadata", metadata);
        root.put("MediaContainer", mc);

        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(root);
        assertNotNull(artists);
        assertEquals(2, artists.size());

        PlexArtist a1 = artists.get(0);
        assertEquals("1001", a1.id);
        assertEquals("Adele", a1.name);
        assertEquals(4, a1.albumCount);
        assertEquals("1001", a1.coverArtId);
        assertEquals("A", a1.indexLetter);

        PlexArtist a2 = artists.get(1);
        assertEquals("1002", a2.id);
        assertEquals("beatles", a2.name);
        assertEquals(12, a2.albumCount);
        assertEquals("1002", a2.coverArtId);
        assertEquals("B", a2.indexLetter); // lower case 'b' -> 'B'
    }

    @Test
    public void testParseArtistsJson_EdgeCasesIndexLetter() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject mc = new JSONObject();
        JSONArray metadata = new JSONArray();

        JSONObject artist1 = new JSONObject();
        artist1.put("ratingKey", "2001");
        // No title

        JSONObject artist2 = new JSONObject();
        artist2.put("ratingKey", "2002");
        artist2.put("title", "10cc"); // Starts with number

        JSONObject artist3 = new JSONObject();
        artist3.put("ratingKey", "2003");
        artist3.put("title", "!ndigo"); // Starts with special char

        JSONObject artist4 = new JSONObject();
        artist4.put("ratingKey", "2004");
        artist4.put("title", ""); // Empty title

        metadata.put(artist1);
        metadata.put(artist2);
        metadata.put(artist3);
        metadata.put(artist4);

        mc.put("Metadata", metadata);
        root.put("MediaContainer", mc);

        List<PlexArtist> artists = PlexCacheStore.parseArtistsJson(root);
        assertNotNull(artists);
        assertEquals(4, artists.size());

        // No title -> #
        assertEquals("#", artists.get(0).indexLetter);
        // Starts with number -> #
        assertEquals("#", artists.get(1).indexLetter);
        // Starts with special char -> #
        assertEquals("#", artists.get(2).indexLetter);
        // Empty title -> #
        assertEquals("#", artists.get(3).indexLetter);
    }
}
