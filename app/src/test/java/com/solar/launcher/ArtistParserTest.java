package com.solar.launcher;

import org.junit.Test;

import java.util.List;

public class ArtistParserTest {

    @Test
    public void splitArtists_commaList() {
        List<String> out = ArtistParser.splitArtists("Chase & Status, Bou, IRAH, Flowdan, Trigga");
        if (out.size() != 5) throw new AssertionError("size " + out);
        if (!"Chase & Status".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Bou".equals(out.get(1))) throw new AssertionError(out.toString());
        if (!"IRAH".equals(out.get(2))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_feat() {
        List<String> out = ArtistParser.splitArtists("Dr. Dre feat. Snoop Dogg");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Dr. Dre".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Snoop Dogg".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_ftAndSemicolon() {
        List<String> out = ArtistParser.splitArtists("Artist A ft. B; Artist C");
        if (out.size() != 3) throw new AssertionError("size " + out);
    }

    @Test
    public void containsArtist_matchesSplitCredit() {
        if (!ArtistParser.containsArtist("Dr. Dre feat. Snoop Dogg", "Snoop Dogg")) {
            throw new AssertionError("expected Snoop match");
        }
        if (ArtistParser.containsArtist("Chase & Status, Bou", "Chase")) {
            throw new AssertionError("Chase alone should not match band name");
        }
        if (!ArtistParser.containsArtist("Chase & Status, Bou", "Chase & Status")) {
            throw new AssertionError("expected band match");
        }
    }
}
