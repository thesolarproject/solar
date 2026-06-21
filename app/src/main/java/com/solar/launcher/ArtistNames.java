package com.solar.launcher;

import java.util.Locale;
import java.util.regex.Pattern;

/** Display normalization and fuzzy equality for artist names after tag splitting. */
public final class ArtistNames {
    /** Dr + space (not Dr.) — e.g. Dr Dre → Dr. Dre; DrDriller unchanged. */
    private static final Pattern DR_ABBR = Pattern.compile("(?i)\\bDr(?=\\s)(?!\\.)");

    private ArtistNames() {}

    public static String normalizeDisplay(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        return DR_ABBR.matcher(t).replaceAll("Dr.");
    }

    public static String matchKey(String raw) {
        return normalizeDisplay(raw).toLowerCase(Locale.US);
    }

    public static boolean equals(String a, String b) {
        if (a == null || b == null) return false;
        String ka = matchKey(a);
        String kb = matchKey(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    /** Pick canonical spelling when merging Dr Dre / Dr. Dre in artist lists. */
    public static String preferCanonical(String a, String b) {
        String na = normalizeDisplay(a);
        String nb = normalizeDisplay(b);
        if (na.contains(".") && !nb.contains(".")) return na;
        if (nb.contains(".") && !na.contains(".")) return nb;
        return na.length() >= nb.length() ? na : nb;
    }
}
