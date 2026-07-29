package com.solar.launcher.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AuthorizedMediaImporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void publishesSupportedAudioAndRemovesPartial() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4};
        File destination = temporary.newFolder("imports");

        AuthorizedMediaImporter.Result result = AuthorizedMediaImporter.copyToLibrary(
                new ByteArrayInputStream(audio), "../../Song.FLAC", audio.length,
                destination, null);

        assertEquals("Song.FLAC", result.file.getName());
        assertFalse(result.duplicate);
        assertEquals(audio.length, result.bytes);
        assertArrayEquals(audio, java.nio.file.Files.readAllBytes(result.file.toPath()));
        assertNoPartial(destination);
    }

    @Test
    public void exactDuplicateReusesExistingFile() throws Exception {
        byte[] audio = new byte[] {9, 8, 7};
        File destination = temporary.newFolder("duplicates");
        AuthorizedMediaImporter.Result first = AuthorizedMediaImporter.copyToLibrary(
                new ByteArrayInputStream(audio), "Song.mp3", audio.length, destination, null);
        AuthorizedMediaImporter.Result second = AuthorizedMediaImporter.copyToLibrary(
                new ByteArrayInputStream(audio), "Song.mp3", audio.length, destination, null);

        assertEquals(first.file, second.file);
        assertTrue(second.duplicate);
        assertEquals(1, destination.listFiles().length);
        assertNoPartial(destination);
    }

    @Test
    public void sameNameWithDifferentContentGetsUniqueDestination() throws Exception {
        File destination = temporary.newFolder("collisions");
        AuthorizedMediaImporter.Result first = AuthorizedMediaImporter.copyToLibrary(
                new ByteArrayInputStream(new byte[] {1}), "Song.mp3", 1L, destination, null);
        AuthorizedMediaImporter.Result second = AuthorizedMediaImporter.copyToLibrary(
                new ByteArrayInputStream(new byte[] {2}), "Song.mp3", 1L, destination, null);

        assertEquals("Song.mp3", first.file.getName());
        assertEquals("Song (2).mp3", second.file.getName());
        assertFalse(second.duplicate);
    }

    @Test
    public void rejectsUnknownFormatsWithoutLeavingFiles() throws Exception {
        File destination = temporary.newFolder("unsupported");
        try {
            AuthorizedMediaImporter.copyToLibrary(
                    new ByteArrayInputStream(new byte[] {1}), "Song.xyz", 1L,
                    destination, null);
            fail("unsupported format should fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
        }
        assertEquals(0, destination.listFiles().length);
    }

    @Test
    public void sizeMismatchRemovesPartialAndPublishedFile() throws Exception {
        File destination = temporary.newFolder("changed");
        try {
            AuthorizedMediaImporter.copyToLibrary(
                    new ByteArrayInputStream(new byte[] {1, 2}), "Song.mp3", 3L,
                    destination, null);
            fail("changed input should fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("changed"));
        }
        assertEquals(0, destination.listFiles().length);
    }

    @Test
    public void sanitizesFatNamesAndChecksStorageMath() {
        assertEquals("_CON.mp3", AuthorizedMediaImporter.safeBasename("../CON.mp3"));
        assertEquals("bad_name_.wma",
                AuthorizedMediaImporter.safeBasename("folder\\bad:name?.wma"));
        assertTrue(AuthorizedMediaImporter.hasEnoughSpace(0L, Long.MAX_VALUE, 1L));
        assertTrue(AuthorizedMediaImporter.hasEnoughSpace(20L, 12L, 8L));
        assertFalse(AuthorizedMediaImporter.hasEnoughSpace(19L, 12L, 8L));
        assertFalse(AuthorizedMediaImporter.hasEnoughSpace(
                Long.MAX_VALUE, Long.MAX_VALUE, 1L));
    }

    private static void assertNoPartial(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            assertFalse(file.getName(), file.getName().endsWith(".import.part"));
        }
    }
}
