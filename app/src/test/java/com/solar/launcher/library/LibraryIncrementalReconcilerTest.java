package com.solar.launcher.library;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibraryIncrementalReconcilerTest {

    private static final class Row {
        final String path;
        final String title;

        Row(String path, String title) {
            this.path = path;
            this.title = title;
        }
    }

    private static final LibraryIncrementalReconciler.PathKey<Row> PATH =
            new LibraryIncrementalReconciler.PathKey<Row>() {
                @Override public String pathOf(Row row) {
                    return row != null ? row.path : "";
                }
            };

    @Test
    public void mergesNewChangedAndMissingRowsWithoutDuplicatingUnchangedRows() {
        Row unchanged = new Row("/music/a.mp3", "A");
        List<Row> resident = new ArrayList<Row>(Arrays.asList(
                unchanged,
                new Row("/music/b.mp3", "Old B"),
                new Row("/music/gone.mp3", "Gone")));
        List<Row> updates = Arrays.asList(
                new Row("/music/b.mp3", "New B"),
                new Row("/music/c.flac", "C"));

        LibraryIncrementalReconciler.Result result =
                LibraryIncrementalReconciler.merge(
                        resident,
                        new HashSet<String>(Arrays.asList(
                                "/music/a.mp3", "/music/b.mp3", "/music/c.flac")),
                        updates,
                        PATH);

        assertEquals(1, result.added);
        assertEquals(1, result.replaced);
        assertEquals(1, result.removed);
        assertTrue(result.changed());
        assertEquals(3, resident.size());
        assertTrue(resident.get(0) == unchanged);
        assertEquals("New B", resident.get(1).title);
        assertEquals("/music/c.flac", resident.get(2).path);
    }

    @Test
    public void noChangesPreservesObjectIdentityAndOrder() {
        Row a = new Row("/music/a.mp3", "A");
        Row b = new Row("/music/b.mp3", "B");
        List<Row> resident = new ArrayList<Row>(Arrays.asList(a, b));

        LibraryIncrementalReconciler.Result result =
                LibraryIncrementalReconciler.merge(
                        resident,
                        new HashSet<String>(Arrays.asList(a.path, b.path)),
                        new ArrayList<Row>(),
                        PATH);

        assertFalse(result.changed());
        assertTrue(resident.get(0) == a);
        assertTrue(resident.get(1) == b);
    }

    @Test
    public void ignoresUpdateForPathNotSeenOnDisk() {
        List<Row> resident = new ArrayList<Row>();
        LibraryIncrementalReconciler.Result result =
                LibraryIncrementalReconciler.merge(
                        resident,
                        new HashSet<String>(),
                        Arrays.asList(new Row("/music/raced.mp3", "Raced")),
                        PATH);

        assertFalse(result.changed());
        assertTrue(resident.isEmpty());
    }
}
