package com.solar.launcher.soulseek;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class SoulseekSearchVariantsTest {

    private static boolean hasIgnoreCase(List<String> variants, String want) {
        for (String v : variants) {
            if (v.equalsIgnoreCase(want)) return true;
        }
        return false;
    }

    @Test
    public void cafeAndCafeAccentAreBothSearched() {
        List<String> fromAscii = SoulseekSearchVariants.expand("Cafe");
        assertTrue(hasIgnoreCase(fromAscii, "Cafe"));
        assertTrue(hasIgnoreCase(fromAscii, "Café"));

        List<String> fromAccent = SoulseekSearchVariants.expand("café");
        assertTrue(hasIgnoreCase(fromAccent, "café"));
        assertTrue(hasIgnoreCase(fromAccent, "cafe"));
    }

    @Test
    public void acronymDotFormsExpand() {
        List<String> dotted = SoulseekSearchVariants.expand("p.y.t");
        assertTrue(hasIgnoreCase(dotted, "p.y.t"));
        assertTrue(hasIgnoreCase(dotted, "pyt"));
        assertTrue(hasIgnoreCase(dotted, "PYT"));

        List<String> upper = SoulseekSearchVariants.expand("PYT");
        assertTrue(hasIgnoreCase(upper, "PYT"));
        assertTrue(hasIgnoreCase(upper, "pyt"));
        assertTrue(hasIgnoreCase(upper, "p.y.t"));

        List<String> plain = SoulseekSearchVariants.expand("pyt");
        assertTrue(hasIgnoreCase(plain, "pyt"));
        assertTrue(hasIgnoreCase(plain, "PYT"));
        assertTrue(hasIgnoreCase(plain, "p.y.t"));
    }

    @Test
    public void expandIsBoundedAndDeduped() {
        List<String> v = SoulseekSearchVariants.expand("pyt");
        assertTrue(v.size() <= SoulseekSearchVariants.MAX_VARIANTS);
        Set<String> keys = new HashSet<String>();
        for (String q : v) {
            String key = q.toLowerCase(Locale.US);
            assertTrue(keys.add(key));
        }
    }

    @Test
    public void stripAccentsNormalizes() {
        assertTrue("cafe".equals(SoulseekSearchVariants.stripAccents("café")));
    }
}
