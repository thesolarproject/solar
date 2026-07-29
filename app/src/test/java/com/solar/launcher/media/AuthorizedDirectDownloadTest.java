package com.solar.launcher.media;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AuthorizedDirectDownloadTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void validatesAndSanitizesDirectAudioPath() throws Exception {
        File directory = temporary.newFolder("planned");
        AuthorizedDirectDownload.Plan plan = AuthorizedDirectDownload.prepare(
                server.url("/creator/My%20Song%3F.FLAC?download=1").toString(), directory);

        assertEquals("My Song_.FLAC", plan.displayName);
        assertEquals("My Song_.FLAC", plan.target.getName());
        assertTrue(plan.partial.getName().startsWith(".My Song_.FLAC."));
        assertTrue(plan.partial.getName().endsWith(".download.part"));
    }

    @Test
    public void rejectsCredentialsPlatformsAndUnsupportedFiles() throws Exception {
        File directory = temporary.newFolder("rejected");
        assertRejected("https://user:secret@example.com/song.mp3", directory, "username");
        assertRejected("https://music.youtube.com/watch/song.mp3", directory, "YouTube");
        assertRejected("https://example.com/song.xyz", directory, "must end");
        assertRejected("ftp://example.com/song.mp3", directory, "HTTP");
    }

    @Test
    public void downloadsPublishesAndRemovesPartial() throws Exception {
        byte[] audio = "ID3-real-audio".getBytes(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                new okio.Buffer().write(audio)));
        File directory = temporary.newFolder("published");
        AuthorizedDirectDownload.Plan plan = AuthorizedDirectDownload.prepare(
                server.url("/creator/song.mp3").toString(), directory);

        AuthorizedDirectDownload.Result result =
                AuthorizedDirectDownload.download(plan, null, null);

        assertEquals("song.mp3", result.file.getName());
        assertFalse(result.duplicate);
        assertEquals(audio.length, result.bytes);
        assertFalse(plan.partial.exists());
        assertEquals("ID3-real-audio",
                new String(Files.readAllBytes(result.file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void resumesStablePartialUsingValidatedRange() throws Exception {
        File directory = temporary.newFolder("resumed");
        String url = server.url("/creator/song.mp3").toString();
        AuthorizedDirectDownload.Plan initial =
                AuthorizedDirectDownload.prepare(url, directory);
        write(initial.partial, "ID3-");
        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-8/9")
                .setBody("audio"));

        AuthorizedDirectDownload.Plan resumed =
                AuthorizedDirectDownload.resume(url, initial.target, directory);
        AuthorizedDirectDownload.Result result =
                AuthorizedDirectDownload.download(resumed, null, null);

        RecordedRequest request = server.takeRequest();
        assertEquals("bytes=4-", request.getHeader("Range"));
        assertEquals("ID3-audio",
                new String(Files.readAllBytes(result.file.toPath()), StandardCharsets.UTF_8));
        assertFalse(resumed.partial.exists());
    }

    @Test
    public void cancellationKeepsPartialForAResume() throws Exception {
        byte[] audio = new byte[32 * 1024];
        audio[0] = 'I';
        audio[1] = 'D';
        audio[2] = '3';
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                new okio.Buffer().write(audio)));
        File directory = temporary.newFolder("paused");
        AuthorizedDirectDownload.Plan plan = AuthorizedDirectDownload.prepare(
                server.url("/creator/song.mp3").toString(), directory);
        final AtomicBoolean cancel = new AtomicBoolean(false);

        try {
            AuthorizedDirectDownload.download(plan,
                    new com.solar.launcher.net.SolarHttp.DownloadProgress() {
                        @Override public void onProgress(long done, long total) {
                            if (done >= 8192L) cancel.set(true);
                        }
                    }, cancel);
            fail("cancelled download should not publish");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        }
        assertTrue(plan.partial.isFile());
        assertTrue(plan.partial.length() >= 8192L);
        assertFalse(plan.target.exists());
    }

    @Test
    public void resumeRecognizesTargetPublishedBeforeJournalUpdate() throws Exception {
        File directory = temporary.newFolder("recovered-publish");
        String url = server.url("/creator/song.mp3").toString();
        AuthorizedDirectDownload.Plan initial =
                AuthorizedDirectDownload.prepare(url, directory);
        write(initial.target, "ID3-finished");

        AuthorizedDirectDownload.Plan resumed =
                AuthorizedDirectDownload.resume(url, initial.target, directory);
        AuthorizedDirectDownload.Result result =
                AuthorizedDirectDownload.download(resumed, null, null);

        assertEquals(initial.target.getCanonicalFile(), result.file.getCanonicalFile());
        assertTrue(result.duplicate);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void exactDuplicateReusesExistingLibraryFile() throws Exception {
        byte[] audio = "ID3-same".getBytes(StandardCharsets.UTF_8);
        File directory = temporary.newFolder("duplicate");
        File existing = new File(directory, "song.mp3");
        write(existing, "ID3-same");
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                new okio.Buffer().write(audio)));

        AuthorizedDirectDownload.Plan plan = AuthorizedDirectDownload.prepare(
                server.url("/creator/song.mp3").toString(), directory);
        AuthorizedDirectDownload.Result result =
                AuthorizedDirectDownload.download(plan, null, null);

        assertEquals(existing.getCanonicalFile(), result.file.getCanonicalFile());
        assertTrue(result.duplicate);
        assertFalse(plan.partial.exists());
        assertFalse(new File(directory, "song (2).mp3").exists());
    }

    @Test
    public void rejectsHtmlDisguisedAsAudioAndDoesNotPublishIt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("<!doctype html><title>not audio</title>"));
        File directory = temporary.newFolder("html");
        AuthorizedDirectDownload.Plan plan = AuthorizedDirectDownload.prepare(
                server.url("/creator/song.mp3").toString(), directory);

        try {
            AuthorizedDirectDownload.download(plan, null, null);
            fail("HTML response should not be published");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("web page"));
        }
        assertFalse(plan.target.exists());
        assertFalse(plan.partial.exists());
    }

    @Test
    public void resumeRejectsJournalPathOutsideDownloadDirectory() throws Exception {
        File directory = temporary.newFolder("safe");
        File outside = new File(temporary.getRoot(), "outside.mp3");
        try {
            AuthorizedDirectDownload.resume(
                    server.url("/creator/song.mp3").toString(), outside, directory);
            fail("outside target should fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("outside"));
        }
        assertFalse(outside.exists());
        assertEquals(0, server.getRequestCount());
    }

    private static void assertRejected(String url, File directory, String message)
            throws Exception {
        try {
            AuthorizedDirectDownload.prepare(url, directory);
            fail("URL should have been rejected: " + url);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }
}
