package com.solar.launcher;

import org.junit.Test;

import java.util.Locale;

/** JVM-safe checks for music library cache helpers. */
public class MusicLibraryStoreTest {

    @Test
    public void durationSecFromMsHelper() {
        if (MusicLibraryStore.durationSecFromMs("180000") != 180) {
            throw new AssertionError("durationSecFromMs");
        }
        if (MusicLibraryStore.durationSecFromMs("") != 0) {
            throw new AssertionError("empty duration");
        }
    }

    /** 2026-07-20 — Length sort comparator + mm:ss formatting for song rows. */
    @Test
    public void formatDurationMmSsAndCompareAscending() {
        if (!"3:00".equals(MusicLibraryStore.formatDurationMmSs("180000"))) {
            throw new AssertionError("3:00 got " + MusicLibraryStore.formatDurationMmSs("180000"));
        }
        if (!"1:01:01".equals(MusicLibraryStore.formatDurationMmSs("3661000"))) {
            throw new AssertionError("hour format");
        }
        if (!"".equals(MusicLibraryStore.formatDurationMmSs(""))) {
            throw new AssertionError("empty format");
        }
        if (!"".equals(MusicLibraryStore.formatDurationMmSs(null))) {
            throw new AssertionError("null format");
        }
        // Short before long.
        if (MusicLibraryStore.compareDurationAscending("60000", "120000") >= 0) {
            throw new AssertionError("short should precede long");
        }
        // Unknown after known.
        if (MusicLibraryStore.compareDurationAscending("", "60000") <= 0) {
            throw new AssertionError("unknown should follow known");
        }
        if (MusicLibraryStore.compareDurationAscending("60000", "") >= 0) {
            throw new AssertionError("known should precede unknown");
        }
        if (MusicLibraryStore.compareDurationAscending("", "") != 0) {
            throw new AssertionError("both unknown equal");
        }
        if (MusicLibraryStore.parseDurationMs("90500") != 90500) {
            throw new AssertionError("parseDurationMs");
        }
    }

    @Test
    public void durationSecFromMs() {
        MusicLibraryStore.Track t = new MusicLibraryStore.Track(
                "/music/a.mp3", 1L, 100L, "T", "A", "Al", "G", "AA", "180000", 0);
        if (t.durationSec() != 180) throw new AssertionError("durationSec=" + t.durationSec());
        MusicLibraryStore.Track empty = new MusicLibraryStore.Track(
                "/b.mp3", 0L, 0L, "", "", "", "", "", "", 0);
        if (empty.durationSec() != 0) throw new AssertionError("expected zero duration");
    }

    /** 2026-07-31 — Legacy stem-pad DB rows must never be treated as library songs. */
    @Test
    public void stemArtifactPathClassification() {
        if (!MusicLibraryStore.isStemLibraryArtifactPath(
                "/storage/sdcard0/Music/Track.mp3.stems/vocals.mp3")) {
            throw new AssertionError("user stem sidecar should be excluded");
        }
        if (!MusicLibraryStore.isStemLibraryArtifactPath(
                "/data/data/com.solar.launcher/cache/lalal_stems/v6_live_abc/drum.mp3")) {
            throw new AssertionError("Lalal cache pad should be excluded");
        }
        if (MusicLibraryStore.isStemLibraryArtifactPath("/storage/sdcard0/Music/Track.mp3")) {
            throw new AssertionError("source track must remain in library");
        }
    }

    @Test
    public void normPathLowerCases() {
        String norm = MusicLibraryStore.normPath("/Storage/SD/Music/Song.MP3");
        if (!"/storage/sd/music/song.mp3".equals(norm)) {
            throw new AssertionError("norm=" + norm);
        }
        if (!"".equals(MusicLibraryStore.normPath(null))) throw new AssertionError("null norm");
        if (!Locale.US.equals(Locale.US)) { /* keep javac happy */ }
    }

    /** 2026-07-06: Mirrors getFreshBatch year gate — 0 stale, -1/positive fresh. */
    @Test
    public void yearFreshnessRule() {
        if (yearCountsAsFresh(0)) throw new AssertionError("legacy 0 stale");
        if (!yearCountsAsFresh(MusicLibraryStore.YEAR_UNKNOWN_SCANNED)) {
            throw new AssertionError("scanned unknown fresh");
        }
        if (!yearCountsAsFresh(1999)) throw new AssertionError("tagged year fresh");
    }

    private static boolean yearCountsAsFresh(int year) {
        return year != 0;
    }
}
