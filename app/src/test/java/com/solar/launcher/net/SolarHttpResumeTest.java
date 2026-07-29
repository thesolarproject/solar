package com.solar.launcher.net;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SolarHttpResumeTest {
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
    public void resumesOnlyFromMatchingContentRange() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"));
        File target = partial("abc");
        final List<Long> finalProgress = new ArrayList<Long>();

        SolarHttp.downloadToFile(server.url("/track").toString(), target,
                new SolarHttp.DownloadProgress() {
                    @Override public void onProgress(long read, long total) {
                        finalProgress.clear();
                        finalProgress.add(read);
                        finalProgress.add(total);
                    }
                }, 0L, null, null, target.length());

        RecordedRequest request = server.takeRequest();
        assertEquals("bytes=3-", request.getHeader("Range"));
        assertEquals("abcdef", read(target));
        assertEquals(Long.valueOf(6L), finalProgress.get(0));
        assertEquals(Long.valueOf(6L), finalProgress.get(1));
    }

    @Test
    public void ignoredRangeOverwritesInsteadOfDuplicating() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("abcdef"));
        File target = partial("abc");

        SolarHttp.downloadToFile(server.url("/track").toString(), target);

        assertEquals("abcdef", read(target));
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"));
    }

    @Test
    public void mismatchedRangeRetriesFresh() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 1-5/6")
                .setBody("bcdef"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("abcdef"));
        File target = partial("abc");

        SolarHttp.downloadToFile(server.url("/track").toString(), target);

        assertEquals("abcdef", read(target));
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"));
        assertNull(server.takeRequest().getHeader("Range"));
    }

    @Test
    public void rangeNotSatisfiableAtExactLengthIsAlreadyComplete() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */6"));
        File target = partial("abcdef");

        SolarHttp.downloadToFile(server.url("/track").toString(), target);

        assertEquals("abcdef", read(target));
        assertTrue(server.getRequestCount() == 1);
    }

    @Test
    public void contentRangeParserRejectsUnsafeOffsets() {
        SolarHttp.ContentRange valid = SolarHttp.parseContentRange("bytes 10-19/20");
        assertEquals(10L, valid.start);
        assertEquals(19L, valid.end);
        assertEquals(20L, valid.total);
        assertTrue(SolarHttp.parseContentRange("bytes 20-10/30") == null);
        assertTrue(SolarHttp.parseContentRange("bytes 10-30/20") == null);
        assertTrue(SolarHttp.parseContentRange("garbage") == null);
    }

    @Test
    public void quickProbeAcceptsSuccessfulHeadResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        assertTrue(SolarHttp.probeAnyReachableQuick(
                new String[] {server.url("/health").toString()}, 1, 1));

        RecordedRequest request = server.takeRequest();
        assertEquals("HEAD", request.getMethod());
        assertEquals("/health", request.getPath());
    }

    private File partial(String value) throws Exception {
        File file = temporary.newFile("track.part");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return file;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
