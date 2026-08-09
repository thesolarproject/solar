package com.solar.launcher;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** LRCGET-in-Solar: LRCLIB JSON parsing + best-match scoring (no network). */
public class LrcGetClientTest {

    /** Captured real /api/get response (2026-08-05). */
    private static final String HOTEL_CALIFORNIA_JSON =
            "{\"id\":395772,\"name\":\"Hotel California\",\"trackName\":\"Hotel California\","
                    + "\"artistName\":\"Eagles\",\"albumName\":\"Hotel California\",\"duration\":391.0,"
                    + "\"instrumental\":false,\"plainLyrics\":\"On a dark desert highway\","
                    + "\"syncedLyrics\":\"[00:12.50]On a dark desert highway\"}";

    @Test
    public void parseMatchExtractsFields() throws Exception {
        LrcGetClient.Match m = LrcGetClient.parseMatch(new JSONObject(HOTEL_CALIFORNIA_JSON));
        if (m == null) throw new AssertionError("null match");
        if (m.id != 395772L) throw new AssertionError("id=" + m.id);
        if (!"Hotel California".equals(m.trackName)) throw new AssertionError("track");
        if (!"Eagles".equals(m.artistName)) throw new AssertionError("artist");
        if (!"Hotel California".equals(m.albumName)) throw new AssertionError("album");
        if (m.durationSec != 391.0d) throw new AssertionError("duration=" + m.durationSec);
        if (m.instrumental) throw new AssertionError("instrumental should be false");
        if (!m.plainLyrics.contains("highway")) throw new AssertionError("plain");
        if (!m.syncedLyrics.startsWith("[00:12.50]")) throw new AssertionError("synced");
    }

    @Test
    public void parseMatchHandlesInstrumentalAndMissingFields() throws Exception {
        LrcGetClient.Match m = LrcGetClient.parseMatch(new JSONObject(
                "{\"id\":1,\"trackName\":\"Interlude\",\"instrumental\":true,\"duration\":-1}"));
        if (m == null) throw new AssertionError("null");
        if (!m.instrumental) throw new AssertionError("instrumental");
        if (m.durationSec != 0d) throw new AssertionError("negative duration should clamp to 0");
        if (m.artistName.length() != 0) throw new AssertionError("missing artist should be empty");
        if (LrcGetClient.bestLyricsText(m) != null) throw new AssertionError("instrumental has no lyrics");
    }

    @Test
    public void bestLyricsPrefersSyncedOverPlain() throws Exception {
        LrcGetClient.Match m = LrcGetClient.parseMatch(new JSONObject(HOTEL_CALIFORNIA_JSON));
        String text = LrcGetClient.bestLyricsText(m);
        if (text == null || !text.startsWith("[00:12.50]")) {
            throw new AssertionError("synced should win, got: " + text);
        }
        LrcGetClient.Match plainOnly = LrcGetClient.parseMatch(new JSONObject(
                "{\"id\":2,\"trackName\":\"X\",\"plainLyrics\":\"Just plain words\",\"syncedLyrics\":\"\"}"));
        if (!"Just plain words".equals(LrcGetClient.bestLyricsText(plainOnly))) {
            throw new AssertionError("plain fallback");
        }
        LrcGetClient.Match empty = LrcGetClient.parseMatch(new JSONObject("{\"id\":3}"));
        if (LrcGetClient.bestLyricsText(empty) != null) throw new AssertionError("empty → null");
    }

    @Test
    public void pickBestPrefersExactTitleAndCloseDuration() throws Exception {
        List<LrcGetClient.Match> candidates = new ArrayList<LrcGetClient.Match>();
        candidates.add(match(10, "Wrong Song", "Someone", 400d));
        candidates.add(match(11, "Hotel California", "Eagles", 395d));
        candidates.add(match(12, "Hotel California", "Eagles", 300d));
        LrcGetClient.Match best = LrcGetClient.pickBest(candidates, "Hotel California", "Eagles", 391);
        if (best == null || best.id != 11L) throw new AssertionError("expected duration-closest exact title");
    }

    @Test
    public void pickBestRejectsUnrelatedRow() throws Exception {
        List<LrcGetClient.Match> candidates = new ArrayList<LrcGetClient.Match>();
        candidates.add(match(20, "Random Track", "Other Artist", 240d));
        LrcGetClient.Match best = LrcGetClient.pickBest(candidates, "Hotel California", "Eagles", 391);
        if (best != null) throw new AssertionError("unrelated row must be rejected");
    }

    @Test
    public void pickBestEmptyListIsNull() throws Exception {
        if (LrcGetClient.pickBest(new ArrayList<LrcGetClient.Match>(), "a", "b", 100) != null) {
            throw new AssertionError("empty → null");
        }
        if (LrcGetClient.pickBest(null, "a", "b", 100) != null) throw new AssertionError("null → null");
    }

    @Test
    public void normalizeCollapsesWhitespaceAndCase() throws Exception {
        if (!"hotel california".equals(LrcGetClient.normalize("  Hotel   California "))) {
            throw new AssertionError("normalize");
        }
        if (!"".equals(LrcGetClient.normalize(null))) throw new AssertionError("null normalize");
    }

    private static LrcGetClient.Match match(long id, String track, String artist, double duration) {
        try {
            return LrcGetClient.parseMatch(new JSONObject()
                    .put("id", id)
                    .put("trackName", track)
                    .put("artistName", artist)
                    .put("duration", duration));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
