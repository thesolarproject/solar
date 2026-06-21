package com.solar.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class AlbumNamesTest {

    @Test
    public void chooseCanonical_majorityWins() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?", 3);
        counts.put("When We All Fall Asleep, Where Do We Go?", 9);
        String chosen = AlbumNames.chooseCanonical(counts);
        if (!"When We All Fall Asleep, Where Do We Go?".equals(chosen)) {
            throw new AssertionError(chosen);
        }
    }

    @Test
    public void chooseCanonical_tieUsesTitleCase() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?", 3);
        counts.put("When We All Fall Asleep, Where Do We Go?", 3);
        String chosen = AlbumNames.chooseCanonical(counts);
        if (!"When We All Fall Asleep, Where Do We Go?".equals(chosen)) {
            throw new AssertionError(chosen);
        }
    }

    @Test
    public void chooseCanonical_manyVariantsOneEachUsesTitleCase() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?", 1);
        counts.put("When We All Fall Asleep, Where Do We Go?", 1);
        counts.put("when we all fall asleep, where do we go?", 1);
        String chosen = AlbumNames.chooseCanonical(counts);
        if (!"When We All Fall Asleep, Where Do We Go?".equals(chosen)) {
            throw new AssertionError(chosen);
        }
    }

    @Test
    public void chooseCanonical_twoWayTopTieUsesTitleCase() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("happier than ever", 4);
        counts.put("HAPPIER THAN EVER", 4);
        counts.put("Happier Than Ever", 2);
        String chosen = AlbumNames.chooseCanonical(counts);
        if (!"Happier Than Ever".equals(chosen)) {
            throw new AssertionError(chosen);
        }
    }

    @Test
    public void canonicalTitles_mergesCaseVariants() {
        Map<String, String> canon = AlbumNames.canonicalTitles(Arrays.asList(
                "DAMN.",
                "Damn.",
                "Damn.",
                "damn."));
        String key = AlbumNames.matchKey("DAMN.");
        if (!"Damn.".equals(canon.get(key))) {
            throw new AssertionError(String.valueOf(canon));
        }
    }

    @Test
    public void equals_caseInsensitive() {
        if (!AlbumNames.equals("OK Computer", "ok computer")) throw new AssertionError("match");
        if (AlbumNames.equals("Kid A", "Amnesiac")) throw new AssertionError("different");
    }

    @Test
    public void toTitleCase_preservesPunctuation() {
        String out = AlbumNames.toTitleCase("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?");
        if (!"When We All Fall Asleep, Where Do We Go?".equals(out)) {
            throw new AssertionError(out);
        }
    }
}
