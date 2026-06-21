package com.solar.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Static helpers mirroring guest-only artist browse heuristics. */
final class ArtistBrowseLogic {
    static boolean hasOwnAlbum(String artist, Iterable<String[]> tracks) {
        if (artist == null || artist.trim().isEmpty()) return false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String[] t : tracks) {
            String album = t[0];
            String tag = t[1];
            if (!ArtistParser.containsArtist(tag, artist)) continue;
            String albumKey = AlbumNames.matchKey(album);
            if (albumKey.isEmpty() || !seen.add(albumKey)) continue;
            if (albumOwner(album, artist, tracks) == null) return true;
        }
        return false;
    }

    static String albumOwner(String album, String browsedArtist, Iterable<String[]> tracks) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, String> displayByKey = new HashMap<String, String>();
        String browsedKey = ArtistNames.matchKey(browsedArtist);
        for (String[] t : tracks) {
            if (!album.equals(t[0])) continue;
            if (!ArtistParser.containsArtist(t[1], browsedArtist)) continue;
            String primary = ArtistNames.normalizeDisplay(ArtistParser.primaryArtist(t[1]));
            if (primary.isEmpty()) continue;
            String key = ArtistNames.matchKey(primary);
            counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            if (!displayByKey.containsKey(key)) displayByKey.put(key, primary);
        }
        if (counts.isEmpty()) return null;
        String bestKey = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (bestKey == null || bestKey.equals(browsedKey)) return null;
        Integer browsedCount = counts.get(browsedKey);
        if (browsedCount != null && browsedCount >= bestCount) return null;
        return displayByKey.get(bestKey);
    }

    private ArtistBrowseLogic() {}
}

public class ArtistBrowseLogicTest {

    @Test
    public void guestOnlyArtist_noOwnAlbum() {
        java.util.List<String[]> tracks = Arrays.asList(
                new String[] {"2001", "Dr. Dre feat. Snoop Dogg"},
                new String[] {"2001", "Dr. Dre ft. Snoop Dogg"});
        if (ArtistBrowseLogic.hasOwnAlbum("Snoop Dogg", tracks)) {
            throw new AssertionError("Snoop should be guest-only");
        }
        if (!"Dr. Dre".equals(ArtistBrowseLogic.albumOwner("2001", "Snoop Dogg", tracks))) {
            throw new AssertionError("owner");
        }
    }

    @Test
    public void primaryArtist_hasOwnAlbum() {
        java.util.List<String[]> tracks = Arrays.asList(
                new String[] {"Doggystyle", "Snoop Dogg"},
                new String[] {"2001", "Dr. Dre feat. Snoop Dogg"});
        if (!ArtistBrowseLogic.hasOwnAlbum("Snoop Dogg", tracks)) {
            throw new AssertionError("Snoop has Doggystyle");
        }
    }
}
