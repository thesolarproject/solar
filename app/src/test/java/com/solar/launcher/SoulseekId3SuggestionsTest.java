package com.solar.launcher;

import com.solar.launcher.soulseek.SoulseekSearchSuggestions;

import org.junit.Test;

import java.util.List;

public class SoulseekId3SuggestionsTest {

    @Test
    public void suggestionsFromId3_permutations() {
        List<String> out = SoulseekSearchSuggestions.suggestionsFromId3(
                "Track", "Artist", "Album", "Rock");
        if (out.isEmpty()) throw new AssertionError("empty");
        if (out.size() > 4) throw new AssertionError("max 4");
    }
}
