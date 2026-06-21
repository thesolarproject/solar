package com.solar.launcher;

import org.junit.After;
import org.junit.Test;

import java.util.List;

public class ArtistParserTest {

    @After
    public void tearDown() {
        ArtistSeparatorCatalog.resetForTests();
    }

    private static void loadDefaultCatalog() {
        try {
            ArtistSeparatorCatalog.install(ArtistSeparatorCatalog.parse(
                    "version,split_ampersand\n1,true\n\"AC/DC\"\n\"Chase & Status\"\n"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void splitArtists_ampersandDuo() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Madonna & Justin Timberlake");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Madonna".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Justin Timberlake".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_commaList() {
        try {
            ArtistSeparatorCatalog.install(ArtistSeparatorCatalog.parse(
                    "version,split_ampersand\n1,true\n\"Chase & Status\"\n"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        List<String> out = ArtistParser.splitArtists("Chase & Status, Bou, IRAH, Flowdan, Trigga");
        if (out.size() != 5) throw new AssertionError("size " + out);
        if (!"Chase & Status".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Bou".equals(out.get(1))) throw new AssertionError(out.toString());
        if (!"IRAH".equals(out.get(2))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_slashNoSpaces() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Kanye West/Syleena Johnson");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Kanye West".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Syleena Johnson".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_slashPreservesBandName() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("AC/DC");
        if (out.size() != 1) throw new AssertionError("size " + out);
        if (!"AC/DC".equals(out.get(0))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_wSlash() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Madonna w/ Justin Timberlake");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Madonna".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Justin Timberlake".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_withWordNotSeparator() {
        loadDefaultCatalog();
        List<String> band = ArtistParser.splitArtists("Sleeping With Sirens");
        if (band.size() != 1 || !"Sleeping With Sirens".equals(band.get(0))) {
            throw new AssertionError("band name: " + band);
        }
        List<String> collab = ArtistParser.splitArtists("Madonna with Justin Timberlake");
        if (collab.size() != 1 || !"Madonna with Justin Timberlake".equals(collab.get(0))) {
            throw new AssertionError("with collab kept whole: " + collab);
        }
    }

    @Test
    public void splitArtists_parentheticalFeat() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Justin Bieber (feat. Ludacris)");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Justin Bieber".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Ludacris".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void containsArtist_slashCredit() {
        loadDefaultCatalog();
        if (!ArtistParser.containsArtist("Kanye West/Syleena Johnson", "Syleena Johnson")) {
            throw new AssertionError("expected Syleena match");
        }
        if (!ArtistParser.containsArtist("Kanye West/Syleena Johnson", "Kanye West")) {
            throw new AssertionError("expected Kanye match");
        }
    }

    @Test
    public void splitArtists_feat() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Dr. Dre feat. Snoop Dogg");
        if (out.size() != 2) throw new AssertionError("size " + out);
        if (!"Dr. Dre".equals(out.get(0))) throw new AssertionError(out.toString());
        if (!"Snoop Dogg".equals(out.get(1))) throw new AssertionError(out.toString());
    }

    @Test
    public void splitArtists_ftAndSemicolon() {
        loadDefaultCatalog();
        List<String> out = ArtistParser.splitArtists("Artist A ft. B; Artist C");
        if (out.size() != 3) throw new AssertionError("size " + out);
    }

    @Test
    public void containsArtist_matchesSplitCredit() {
        loadDefaultCatalog();
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
