package com.solar.launcher;

import com.solar.launcher.flow.FlowCatalog;
import com.solar.launcher.flow.FlowItem;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    /** Policy tracks mirror library rows — album rack filter uses the same artist universe. */
    private static List<ArtistBrowsePolicy.Track> policyFromRows(List<FlowCatalog.SongRow> rows) {
        List<ArtistBrowsePolicy.Track> out = new ArrayList<ArtistBrowsePolicy.Track>();
        for (FlowCatalog.SongRow row : rows) {
            out.add(new ArtistBrowsePolicy.Track(row.artist, row.album, row.albumArtist, row.lastModified));
        }
        return out;
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
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows));
        assertFalse(albums.isEmpty());
        assertEquals("Doggystyle", albums.get(0).title);
    }

    @Test
    public void artlessAlbumIncludedInCatalog() throws IOException {
        File f = File.createTempFile("flow_noart", ".mp3");
        f.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f, "Track", "Band", "No Art Album", "Band", 1L));
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows));
        assertEquals(1, albums.size());
        assertEquals("No Art Album", albums.get(0).title);
        assertFalse(albums.get(0).coverKey.isEmpty());
        assertEquals(1, albums.get(0).tracks.size());
    }

    @Test
    public void sortTracksByTrackNumberThenTitle() throws IOException {
        File f1 = File.createTempFile("flow_sort_03", ".mp3");
        File f2 = File.createTempFile("flow_sort_01", ".mp3");
        File f3 = File.createTempFile("flow_sort_02", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> library = Arrays.asList(
                new FlowCatalog.SongRow(f1, "Three", "Artist", "Album", "Artist", 1L, 3),
                new FlowCatalog.SongRow(f2, "One", "Artist", "Album", "Artist", 1L, 1),
                new FlowCatalog.SongRow(f3, "Two", "Artist", "Album", "Artist", 1L, 2));
        LibraryBrowsePrefsTest.MemSharedPreferences mem =
                new LibraryBrowsePrefsTest.MemSharedPreferences();
        mem.edit().putInt("lib_song_sort", LibraryBrowsePrefs.SONG_SORT_ALBUM).commit();
        LibraryBrowsePrefs albumSortPrefs = new LibraryBrowsePrefs(mem);
        List<FlowItem> albums = FlowCatalog.buildAlbums(library, albumSortPrefs, policyFromRows(library));
        assertEquals(1, albums.size());
        List<File> sorted = albums.get(0).tracks;
        assertEquals(3, sorted.size());
        assertEquals(f2, sorted.get(0));
        assertEquals(f3, sorted.get(1));
        assertEquals(f1, sorted.get(2));
    }

    @Test
    public void albumSongSortIndependentOfGlobalSongSort() throws IOException {
        File f1 = File.createTempFile("flow_alb_sort_c", ".mp3");
        File f2 = File.createTempFile("flow_alb_sort_a", ".mp3");
        File f3 = File.createTempFile("flow_alb_sort_b", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> library = Arrays.asList(
                new FlowCatalog.SongRow(f1, "Three", "Artist", "Album", "Artist", 1L, 3),
                new FlowCatalog.SongRow(f2, "One", "Artist", "Album", "Artist", 1L, 1),
                new FlowCatalog.SongRow(f3, "Two", "Artist", "Album", "Artist", 1L, 2));
        LibraryBrowsePrefsTest.MemSharedPreferences mem =
                new LibraryBrowsePrefsTest.MemSharedPreferences();
        mem.edit().putInt("lib_song_sort", LibraryBrowsePrefs.SONG_SORT_TITLE).commit();
        mem.edit().putInt("lib_album_song_sort", LibraryBrowsePrefs.SONG_SORT_ALBUM).commit();
        LibraryBrowsePrefs albumPrefs = new LibraryBrowsePrefs(mem);
        List<FlowItem> albums = FlowCatalog.buildAlbums(library, albumPrefs, policyFromRows(library));
        List<File> sorted = albums.get(0).tracks;
        assertEquals(f2, sorted.get(0));
        assertEquals(f3, sorted.get(1));
        assertEquals(f1, sorted.get(2));
    }

    @Test
    public void multiTrackOnlySkipsSingleTrackAlbums() throws IOException {
        File f1 = File.createTempFile("flow_solo", ".mp3");
        File f2 = File.createTempFile("flow_full_a", ".mp3");
        File f3 = File.createTempFile("flow_full_b", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "Solo", "Artist", "Solo Album", "Artist", 1L),
                new FlowCatalog.SongRow(f2, "One", "Band", "Full Album", "Band", 1L),
                new FlowCatalog.SongRow(f3, "Two", "Band", "Full Album", "Band", 1L));
        List<FlowItem> all = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows), false);
        assertEquals(2, all.size());
        List<FlowItem> filtered = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows), true);
        assertEquals(1, filtered.size());
        assertEquals("Full Album", filtered.get(0).title);
    }

    @Test
    public void primaryArtistFromVotesPicksDominantTag() {
        java.util.Map<String, Integer> votes = new java.util.HashMap<String, Integer>();
        votes.put("Artist A", 3);
        votes.put("Artist B", 1);
        assertEquals("Artist A", FlowCatalog.primaryArtistFromVotes(votes, "Artist B"));
    }

    @Test
    public void multiTrackOnlyFiltersSingletonAlbums() throws IOException {
        File f1 = File.createTempFile("flow_mt_1", ".mp3");
        File f2 = File.createTempFile("flow_mt_2", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "a", "Artist", "Solo", "Artist", 1L),
                new FlowCatalog.SongRow(f2, "b", "Artist", "Duo", "Artist", 1L),
                new FlowCatalog.SongRow(f2, "c", "Artist", "Duo", "Artist", 1L));
        List<FlowItem> all = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows), false);
        assertEquals(2, all.size());
        List<FlowItem> multi = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows), true);
        assertEquals(1, multi.size());
        assertEquals("Duo", multi.get(0).title);
    }

    @Test
    public void albumArtistUsesDominantTag() throws IOException {
        File f1 = File.createTempFile("flow_dom_1", ".mp3");
        File f2 = File.createTempFile("flow_dom_2", ".mp3");
        File f3 = File.createTempFile("flow_dom_3", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "t1", "Feat Artist", "Shared", "Shared", 1L),
                new FlowCatalog.SongRow(f2, "t2", "Main Artist", "Shared", "Shared", 1L),
                new FlowCatalog.SongRow(f3, "t3", "Main Artist", "Shared", "Shared", 1L));
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows));
        assertEquals(1, albums.size());
        assertTrue(albums.get(0).coverKey.toLowerCase(java.util.Locale.US).contains("main artist"));
    }

    @Test
    public void albumsSortByArtistThenTitle() throws IOException {
        LibraryBrowsePrefsTest.MemSharedPreferences mem =
                new LibraryBrowsePrefsTest.MemSharedPreferences();
        mem.edit().putInt("lib_album_rack_sort",
                LibraryBrowsePrefs.ALBUM_RACK_SORT_ARTIST_THEN_TITLE).commit();
        LibraryBrowsePrefs artistRackPrefs = new LibraryBrowsePrefs(mem);
        File f1 = File.createTempFile("flow_gravy", ".mp3");
        File f2 = File.createTempFile("flow_kanye", ".mp3");
        File f3 = File.createTempFile("flow_gorillaz", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "t", "Baby Gravy", "Goodness Gracious", "Baby Gravy", 1L),
                new FlowCatalog.SongRow(f2, "t", "Kanye West", "Graduation", "Kanye West", 1L),
                new FlowCatalog.SongRow(f3, "t", "Gorillaz", "Gorillaz", "Gorillaz", 1L));
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, artistRackPrefs, policyFromRows(rows));
        assertEquals(3, albums.size());
        assertEquals("Goodness Gracious", albums.get(0).title);
        assertEquals("Gorillaz", albums.get(1).title);
        assertEquals("Graduation", albums.get(2).title);
    }

    @Test
    public void albumsDefaultSortTitleAz() throws IOException {
        File f1 = File.createTempFile("flow_zed", ".mp3");
        File f2 = File.createTempFile("flow_abc", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "t", "Zed", "Z Album", "Zed", 1L),
                new FlowCatalog.SongRow(f2, "t", "Amy", "A Album", "Amy", 1L));
        List<FlowItem> albums = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows));
        assertEquals("A Album", albums.get(0).title);
        assertEquals("Z Album", albums.get(1).title);
    }

    @Test
    public void libraryAlbumTitlesMatchFlowCatalogOrder() throws IOException {
        File f1 = File.createTempFile("flow_lib_a", ".mp3");
        File f2 = File.createTempFile("flow_lib_b", ".mp3");
        File f3 = File.createTempFile("flow_lib_c", ".mp3");
        f1.deleteOnExit();
        f2.deleteOnExit();
        f3.deleteOnExit();
        List<FlowCatalog.SongRow> rows = Arrays.asList(
                new FlowCatalog.SongRow(f1, "t", "Zed", "Z Album", "Zed", 1L),
                new FlowCatalog.SongRow(f2, "t", "Amy", "A Album", "Amy", 1L),
                new FlowCatalog.SongRow(f3, "t", "Mid", "M Album", "Mid", 1L));
        List<String> libraryTitles = LibraryAlbumRack.albumTitles(rows, prefs, policyFromRows(rows));
        List<FlowItem> flowItems = FlowCatalog.buildAlbums(rows, prefs, policyFromRows(rows));
        assertEquals(flowItems.size(), libraryTitles.size());
        for (int i = 0; i < flowItems.size(); i++) {
            assertEquals(flowItems.get(i).title, libraryTitles.get(i));
        }
    }

    @Test
    public void catalogOptionsKeyChangesWithAlbumRackSort() {
        LibraryBrowsePrefsTest.MemSharedPreferences mem =
                new LibraryBrowsePrefsTest.MemSharedPreferences();
        LibraryBrowsePrefs titlePrefs = new LibraryBrowsePrefs(mem);
        int keyTitle = FlowCatalog.catalogOptionsKey(titlePrefs, false);
        mem.edit().putInt("lib_album_rack_sort",
                LibraryBrowsePrefs.ALBUM_RACK_SORT_ARTIST_THEN_TITLE).commit();
        LibraryBrowsePrefs artistPrefs = new LibraryBrowsePrefs(mem);
        int keyArtist = FlowCatalog.catalogOptionsKey(artistPrefs, false);
        assertTrue(keyTitle != keyArtist);
    }

    @Test
    public void trackDisplayLabelUsesId3TitleAndNumber() {
        File f = new File("/tmp/whatever.mp3");
        List<FlowCatalog.SongRow> library = Arrays.asList(
                new FlowCatalog.SongRow(f, "Bad Guy", "Billie Eilish", "Album", "Artist", 1L, 5));
        assertEquals("05 Bad Guy", FlowCatalog.trackDisplayLabel(f, library));
    }
}
