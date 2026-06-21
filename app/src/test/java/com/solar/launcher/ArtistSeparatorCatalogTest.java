package com.solar.launcher;

import org.junit.After;
import org.junit.Test;

public class ArtistSeparatorCatalogTest {

    @After
    public void tearDown() {
        ArtistSeparatorCatalog.resetForTests();
    }

    private static String sampleCsv() {
        return "version,split_ampersand\n"
                + "2,true\n"
                + "\"AC/DC\"\n"
                + "\"Chase & Status\"\n"
                + "\"Tyler, The Creator\"\n";
    }

    @Test
    public void parse_noSplitAndAmpersandFlag() throws Exception {
        ArtistSeparatorCatalog catalog = ArtistSeparatorCatalog.parse(sampleCsv());
        ArtistSeparatorCatalog.install(catalog);
        if (!catalog.isNoSplit("ac/dc")) throw new AssertionError("case insensitive");
        if (!catalog.isNoSplit("Chase & Status")) throw new AssertionError("band exception");
        if (!catalog.isNoSplit("Tyler, The Creator")) throw new AssertionError("comma in name");
        if (catalog.isNoSplit("Kanye West")) throw new AssertionError("normal artist");
        if (!catalog.splitAmpersand()) throw new AssertionError("flag");
    }

    @Test
    public void parseQuotedCsvField_embeddedQuote() {
        String name = ArtistSeparatorCatalog.parseQuotedCsvField("\"Lil \"\"Wayne\"\"\"");
        if (!"Lil \"Wayne\"".equals(name)) throw new AssertionError(name);
    }

    @Test
    public void splitArtists_respectsCatalogAmpersand() throws Exception {
        ArtistSeparatorCatalog.install(ArtistSeparatorCatalog.parse(
                "version,split_ampersand\n1,true\n\"Chase & Status\"\n"));
        java.util.List<String> duo = ArtistParser.splitArtists("Dr. Dre & Snoop Dogg");
        if (duo.size() != 2) throw new AssertionError("size " + duo);
        java.util.List<String> band = ArtistParser.splitArtists("Chase & Status");
        if (band.size() != 1 || !"Chase & Status".equals(band.get(0))) {
            throw new AssertionError(band.toString());
        }
    }

    @Test
    public void splitArtists_tylerTheCreator() throws Exception {
        ArtistSeparatorCatalog.install(ArtistSeparatorCatalog.parse(
                "version,split_ampersand\n1,true\n\"Tyler, The Creator\"\n"));
        java.util.List<String> out = ArtistParser.splitArtists("Tyler, The Creator");
        if (out.size() != 1 || !"Tyler, The Creator".equals(out.get(0))) {
            throw new AssertionError(out.toString());
        }
    }
}
