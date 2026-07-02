package com.solar.launcher;

import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.deezer.DeezerSearch;
import com.solar.launcher.soulseek.SoulseekClient;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GetMusicSearchTest {

    @Test
    public void organize_interleavesResultTypes() {
        List<DeezerSearch.DeezerArtist> artists = new ArrayList<DeezerSearch.DeezerArtist>();
        artists.add(new DeezerSearch.DeezerArtist(1L, "A1", ""));
        artists.add(new DeezerSearch.DeezerArtist(2L, "A2", ""));

        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        flat.add(MusicSearchEntry.deezer(new DeezerResult(10, "T1", "X", "Album One", 100, 200, "", "")));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(11, "T2", "X", "Album One", 100, 200, "", "")));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(12, "T3", "Y", "Album Two", 101, 200, "", "")));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(13, "T4", "Y", "Album Two", 101, 200, "", "")));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(14, "Loose", "Z", "", 0, 200, "", "")));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("p", "Music\\FolderA\\a.mp3",
                1000, 200, 320, true, true, 100, 0)));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("p", "Music\\FolderA\\b.mp3",
                1000, 200, 320, true, true, 100, 0)));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("p", "Music\\FolderB\\c.mp3",
                1000, 200, 320, true, true, 100, 0)));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("p", "Music\\FolderB\\d.mp3",
                1000, 200, 320, true, true, 100, 0)));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("p", "loose.mp3",
                1000, 200, 320, true, true, 100, 0)));

        List<MusicSearchEntry> out = GetMusicSearch.organizeWithContainers(artists, flat);
        if (out.size() < 6) throw new AssertionError("expected diverse rows");

        java.util.Set<MusicSearchEntry.RowKind> headKinds =
                new java.util.HashSet<MusicSearchEntry.RowKind>();
        for (int i = 0; i < Math.min(8, out.size()); i++) {
            headKinds.add(out.get(i).kind);
        }
        if (headKinds.size() < 3) throw new AssertionError("first rows not mixed");

        boolean sawArtist = false;
        boolean sawAlbum = false;
        boolean sawFolder = false;
        boolean sawTrack = false;
        for (MusicSearchEntry e : out) {
            if (e.kind == MusicSearchEntry.RowKind.DEEZER_ARTIST) sawArtist = true;
            if (e.kind == MusicSearchEntry.RowKind.DEEZER_ALBUM) sawAlbum = true;
            if (e.kind == MusicSearchEntry.RowKind.REACH_FOLDER) sawFolder = true;
            if (e.kind == MusicSearchEntry.RowKind.TRACK) sawTrack = true;
        }
        if (!sawArtist || !sawAlbum || !sawFolder || !sawTrack) {
            throw new AssertionError("missing result kinds");
        }
    }

    @Test
    public void organize_includesArtistsAndMixedContainers() {
        List<DeezerSearch.DeezerArtist> artists = new ArrayList<DeezerSearch.DeezerArtist>();
        artists.add(new DeezerSearch.DeezerArtist(1L, "Oasis", ""));

        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        flat.add(MusicSearchEntry.deezer(new DeezerResult(10, "Wonderwall", "Oasis",
                "(What's the Story) Morning Glory?", 100, 258, "", "")));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(11, "Don't Look Back in Anger", "Oasis",
                "(What's the Story) Morning Glory?", 100, 282, "", "")));
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("peer", "Oasis\\Wonderwall.mp3",
                5000000, 258, 320, true, true, 100, 0)));

        List<MusicSearchEntry> out = GetMusicSearch.organizeWithContainers(artists, flat);
        if (out.isEmpty()) throw new AssertionError("empty");
        boolean hasArtist = false;
        boolean hasAlbum = false;
        boolean hasReach = false;
        for (MusicSearchEntry e : out) {
            if (e.kind == MusicSearchEntry.RowKind.DEEZER_ARTIST) hasArtist = true;
            if (e.kind == MusicSearchEntry.RowKind.DEEZER_ALBUM) hasAlbum = true;
            if (e.source == MusicSearchEntry.Source.REACH) hasReach = true;
        }
        if (!hasArtist) throw new AssertionError("artist row");
        if (!hasAlbum) throw new AssertionError("album container");
        if (!hasReach) throw new AssertionError("reach folder or track");
    }

    @Test
    public void organizeUnifiedDeezerFirst_putsDeezerBeforeReach() {
        List<DeezerSearch.DeezerArtist> artists = new ArrayList<DeezerSearch.DeezerArtist>();
        artists.add(new DeezerSearch.DeezerArtist(1L, "Artist", ""));

        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        flat.add(MusicSearchEntry.reach(new SoulseekClient.Result("peer", "reach.mp3",
                1000, 200, 320, true, true, 100, 0)));
        flat.add(MusicSearchEntry.deezer(new DeezerResult(10, "Deezer Hit", "Artist",
                "Album", 100, 200, "", "")));

        List<MusicSearchEntry> out = GetMusicSearch.organizeUnifiedDeezerFirst(artists, flat);
        if (out.isEmpty()) throw new AssertionError("empty");
        int firstReach = -1;
        int firstDeezer = -1;
        for (int i = 0; i < out.size(); i++) {
            MusicSearchEntry e = out.get(i);
            if (firstDeezer < 0 && e.source == MusicSearchEntry.Source.DEEZER) firstDeezer = i;
            if (firstReach < 0 && e.source == MusicSearchEntry.Source.REACH) firstReach = i;
        }
        if (firstDeezer < 0 || firstReach < 0) throw new AssertionError("missing source");
        if (firstReach < firstDeezer) throw new AssertionError("reach before deezer");
    }

    @Test
    public void formatDeezerReleaseLabel_types() {
        if (!GetMusicSearch.formatDeezerReleaseLabel("single", "Live Forever").startsWith("Single")) {
            throw new AssertionError("single");
        }
        if (!GetMusicSearch.formatDeezerReleaseLabel("ep", "Some EP").startsWith("EP")) {
            throw new AssertionError("ep");
        }
    }
}
