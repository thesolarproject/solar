package com.solar.launcher;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArtistBrowsePolicyTest {

    private LibraryBrowsePrefs prefs;
    private List<ArtistBrowsePolicy.Track> guestTracks;
    private List<ArtistBrowsePolicy.Track> ownerTracks;

    @Before
    public void setUp() {
        prefs = new LibraryBrowsePrefs(new LibraryBrowsePrefsTest.MemSharedPreferences());
        guestTracks = Arrays.asList(
                new ArtistBrowsePolicy.Track("Dr. Dre feat. Snoop Dogg", "2001", "Dr. Dre", 100L),
                new ArtistBrowsePolicy.Track("Dr. Dre ft. Snoop Dogg", "2001", "Dr. Dre", 200L));
        ownerTracks = Arrays.asList(
                new ArtistBrowsePolicy.Track("Snoop Dogg", "Doggystyle", "Snoop Dogg", 300L),
                new ArtistBrowsePolicy.Track("Dr. Dre feat. Snoop Dogg", "2001", "Dr. Dre", 100L));
    }

    @Test
    public void tpe2AlbumArtistWinsOverTrackPrimary() {
        String owner = ArtistBrowsePolicy.albumOwnerForBrowse("2001", "Snoop Dogg", guestTracks, prefs);
        assertEquals("Dr. Dre", owner);
    }

    @Test
    public void guestOnlyArtist_autoModeSkipsAlbumPicker() {
        assertFalse(ArtistBrowsePolicy.hasOwnAlbum("Snoop Dogg", guestTracks, prefs));
        assertTrue(ArtistBrowsePolicy.shouldSkipAlbumPicker("Snoop Dogg", prefs, guestTracks));
    }

    @Test
    public void primaryArtist_hasOwnAlbum() {
        assertTrue(ArtistBrowsePolicy.hasOwnAlbum("Snoop Dogg", ownerTracks, prefs));
        assertFalse(ArtistBrowsePolicy.shouldSkipAlbumPicker("Snoop Dogg", prefs, ownerTracks));
    }

    @Test
    public void filterHideGuestOnly() {
        prefs.cycleArtistFilter();
        prefs.cycleArtistFilter();
        List<String> artists = ArtistBrowsePolicy.collectArtists(guestTracks, prefs);
        assertEquals(Collections.singletonList("Dr. Dre"), artists);
    }

    @Test
    public void splitCreditsOffUsesPrimaryOnly() {
        prefs.setSplitCredits(false);
        List<String> artists = ArtistBrowsePolicy.collectArtists(guestTracks, prefs);
        assertTrue(artists.contains("Dr. Dre"));
        assertFalse(artists.contains("Snoop Dogg"));
    }

    @Test
    public void guestBrowseAlwaysSongs() {
        prefs.cycleGuestBrowseMode();
        prefs.cycleGuestBrowseMode();
        assertTrue(ArtistBrowsePolicy.shouldSkipAlbumPicker("Snoop Dogg", prefs, ownerTracks));
    }
}
