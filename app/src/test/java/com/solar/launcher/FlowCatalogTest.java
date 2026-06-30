package com.solar.launcher;

import com.solar.launcher.flow.FlowCatalog;
import com.solar.launcher.flow.FlowItem;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class FlowCatalogTest {

    private LibraryBrowsePrefs prefs;
    private List<ArtistBrowsePolicy.Track> tracks;

    @Before
    public void setUp() {
        prefs = new LibraryBrowsePrefs(new LibraryBrowsePrefsTest.MemSharedPreferences());
        tracks = Arrays.asList(
                new ArtistBrowsePolicy.Track("Dr. Dre feat. Snoop Dogg", "2001", "Dr. Dre", 100L),
                new ArtistBrowsePolicy.Track("Snoop Dogg", "Doggystyle", "Snoop Dogg", 300L));
    }

    @Test
    public void artistOrderMatchesPolicy() {
        List<FlowItem> flow = FlowCatalog.buildArtists(tracks, prefs);
        List<String> policy = ArtistBrowsePolicy.collectArtists(tracks, prefs);
        assertEquals(policy.size(), flow.size());
        for (int i = 0; i < policy.size(); i++) {
            assertEquals(policy.get(i), flow.get(i).title);
        }
    }

    @Test
    public void albumsFromLibraryRows() {
        File f = new File("/tmp/a.mp3");
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f, "t", "Snoop Dogg", "Doggystyle", "Snoop Dogg", 1L));
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, prefs, tracks);
        assertFalse(albums.isEmpty());
        assertEquals("Doggystyle", albums.get(0).title);
    }
}
