package com.solar.launcher.youtube;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class YouTubeResultJsonTest {

    @Test
    public void parsePopularPayload() throws Exception {
        String json = "[{\"id\":\"abc123\",\"title\":\"Test Song\",\"author\":\"Channel One\",\"length\":\"3:45\"}]";
        List<YouTubeVideo> rows = YouTubeResultJson.parseVideos(json);
        if (rows.size() != 1) throw new AssertionError("expected one row");
        YouTubeVideo v = rows.get(0);
        assertEquals("abc123", v.id);
        assertEquals("Test Song", v.title);
        assertEquals("Channel One", v.author);
        assertEquals("3:45", v.duration);
    }

    @Test
    public void parseStreamUrlPayload() throws Exception {
        String json = "{\"url\":\"https://example.com/stream.m3u8\",\"title\":\"Clip\"}";
        assertEquals("https://example.com/stream.m3u8", YouTubeResultJson.parseStreamUrl(json));
    }

    @Test
    public void parseStreamResultWithExt() throws Exception {
        String json = "{\"url\":\"https://example.com/audio.m4a\",\"ext\":\"m4a\",\"videoId\":\"x\"}";
        YouTubeResultJson.StreamResult r = YouTubeResultJson.parseStreamResult(json);
        assertNotNull(r);
        assertEquals("https://example.com/audio.m4a", r.url);
        assertEquals("m4a", r.ext);
    }

    @Test
    public void parseEmptyStreamReturnsNull() throws Exception {
        assertNull(YouTubeResultJson.parseStreamUrl("{\"url\":\"\"}"));
    }

    @Test
    public void parseEmptyArray() throws Exception {
        List<YouTubeVideo> rows = YouTubeResultJson.parseVideos("[]");
        assertNotNull(rows);
        assertEquals(0, rows.size());
    }

    @Test
    public void parseOfficialPageAndPaginationToken() throws Exception {
        String json = "{\"items\":[{\"id\":\"page1\",\"title\":\"Paged\","
                + "\"author\":\"Channel\",\"length\":\"4:02\"}],"
                + "\"nextPageToken\":\"NEXT_2\"}";
        List<YouTubeVideo> rows = YouTubeResultJson.parseVideos(json);
        assertEquals(1, rows.size());
        assertEquals("page1", rows.get(0).id);
        assertEquals("NEXT_2", YouTubeResultJson.parseNextPageToken(json));
        assertEquals("", YouTubeResultJson.parseNextPageToken("[]"));
    }

    @Test
    public void parseCommentsPayload() throws Exception {
        String json = "[{\"author\":\"Alice\",\"content\":\"Great video!\"},"
                + "{\"author\":\"Bob\",\"content\":\"Thanks\"}]";
        List<YouTubeComment> rows = YouTubeResultJson.parseComments(json);
        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).author);
        assertEquals("Great video!", rows.get(0).content);
        assertEquals("Bob", rows.get(1).author);
    }

    @Test
    public void parseEmptyComments() throws Exception {
        List<YouTubeComment> rows = YouTubeResultJson.parseComments("[]");
        assertNotNull(rows);
        assertEquals(0, rows.size());
    }
}
