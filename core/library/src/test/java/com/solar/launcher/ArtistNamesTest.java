package com.solar.launcher;

import org.junit.Test;

public class ArtistNamesTest {

    @Test
    public void normalizeDisplay_drWithSpace() {
        if (!"Dr. Dre".equals(ArtistNames.normalizeDisplay("Dr Dre"))) {
            throw new AssertionError(ArtistNames.normalizeDisplay("Dr Dre"));
        }
        if (!"Dr. Dre".equals(ArtistNames.normalizeDisplay("Dr. Dre"))) {
            throw new AssertionError("already dotted");
        }
    }

    @Test
    public void normalizeDisplay_drWithoutSpaceUnchanged() {
        if (!"DrDriller".equals(ArtistNames.normalizeDisplay("DrDriller"))) {
            throw new AssertionError("embedded Dr");
        }
        if (!"DrHook".equals(ArtistNames.normalizeDisplay("DrHook"))) {
            throw new AssertionError("DrHook");
        }
    }

    @Test
    public void equals_drVariants() {
        if (!ArtistNames.equals("Dr Dre", "Dr. Dre")) throw new AssertionError("same artist");
        if (!ArtistNames.equals("Dr. Hook", "Dr Hook")) throw new AssertionError("hook");
        if (ArtistNames.equals("Dr. Dre", "Dr. Hook")) throw new AssertionError("different artists");
        if (ArtistNames.equals("Dr Dog", "Dr. Dre")) throw new AssertionError("dog vs dre");
    }

    @Test
    public void containsArtist_honorsDrNormalization() {
        if (!ArtistParser.containsArtist("Dr. Dre feat. Snoop Dogg", "Dr Dre")) {
            throw new AssertionError("browse Dr Dre against Dr. Dre tag");
        }
    }
}
