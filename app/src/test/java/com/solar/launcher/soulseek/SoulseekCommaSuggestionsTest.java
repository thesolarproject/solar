package com.solar.launcher.soulseek;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

public class SoulseekCommaSuggestionsTest {

    private static boolean hasIgnoreCase(List<String> list, String want) {
        for (String s : list) {
            if (s.equalsIgnoreCase(want)) return true;
        }
        return false;
    }

    @Test
    public void commaSeparatedArtistsExpand() {
        List<String> out = SoulseekSearchSuggestions.suggestionsFromId3(
                "Heavy Is The Crown",
                "Chase & Status, bou, irah, flowdan, trigga, takura",
                null, null);
        if (!hasIgnoreCase(out, "Chase & Status")) throw new AssertionError("missing Chase & Status");
        if (!hasIgnoreCase(out, "Chase")) throw new AssertionError("missing Chase");
        if (!hasIgnoreCase(out, "Status")) throw new AssertionError("missing Status");
        if (!hasIgnoreCase(out, "Flowdan")) throw new AssertionError("missing Flowdan");
        if (!hasIgnoreCase(out, "Irah")) throw new AssertionError("missing Irah");
        if (!hasIgnoreCase(out, "Bou")) throw new AssertionError("missing Bou");
        if (!hasIgnoreCase(out, "Trigga")) throw new AssertionError("missing Trigga");
        if (!hasIgnoreCase(out, "Takura")) throw new AssertionError("missing Takura");
    }

    @Test
    public void semicolonSeparatedAlsoSplits() {
        List<String> out = SoulseekSearchSuggestions.suggestionsFromId3(
                "Track", "Artist A; Artist B", null, null);
        if (!hasIgnoreCase(out, "Artist A")) throw new AssertionError("missing A");
        if (!hasIgnoreCase(out, "Artist B")) throw new AssertionError("missing B");
    }
}
