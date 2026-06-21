package com.solar.launcher;

import com.solar.launcher.soulseek.SoulseekSearchSuggestions;

import org.junit.After;
import org.junit.Test;

import java.util.List;

public class SoulseekId3SuggestionsTest {

    @After
    public void tearDown() {
        ArtistSeparatorCatalog.resetForTests();
    }

    private static void loadCatalogWithChaseAndStatus() {
        try {
            ArtistSeparatorCatalog.install(ArtistSeparatorCatalog.parse(
                    "version,split_ampersand\n1,true\n\"Chase & Status\"\n"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void suggestionsFromId3_includesSplitArtistsTitleAlbum() {
        loadCatalogWithChaseAndStatus();
        List<String> out = SoulseekSearchSuggestions.suggestionsFromId3(
                "Baddadan", "Chase & Status, Bou, IRAH, Flowdan, Trigga", "Album X", "Drum & Bass");
        if (out.isEmpty()) throw new AssertionError("empty");
        if (!out.contains("Chase & Status")) throw new AssertionError("missing band: " + out);
        if (!out.contains("Bou")) throw new AssertionError("missing Bou: " + out);
        if (!out.contains("Baddadan")) throw new AssertionError("missing title: " + out);
        if (!out.contains("Album X")) throw new AssertionError("missing album: " + out);
    }

    @Test
    public void suggestionsFromId3_featSplit() {
        List<String> out = SoulseekSearchSuggestions.suggestionsFromId3(
                "Nuthin'", "Dr. Dre feat. Snoop Dogg", "2001", "Hip-Hop");
        if (!out.contains("Dr. Dre")) throw new AssertionError("missing Dre: " + out);
        if (!out.contains("Snoop Dogg")) throw new AssertionError("missing Snoop: " + out);
        if (!out.contains("Nuthin'")) throw new AssertionError("missing title: " + out);
        if (!out.contains("2001")) throw new AssertionError("missing album: " + out);
    }
}
