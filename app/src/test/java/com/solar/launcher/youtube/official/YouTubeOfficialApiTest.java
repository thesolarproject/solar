package com.solar.launcher.youtube.official;

import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class YouTubeOfficialApiTest {

    @Test
    public void preservesSearchOrderAndParsesMetadata() throws Exception {
        JSONObject response = new JSONObject("{\"items\":["
                + "{\"id\":\"second\",\"snippet\":{\"title\":\"B &amp; C\","
                + "\"channelTitle\":\"Channel 2\"},"
                + "\"contentDetails\":{\"duration\":\"PT1H2M3S\"}},"
                + "{\"id\":\"first\",\"snippet\":{\"title\":\"First\","
                + "\"channelTitle\":\"Channel 1\","
                + "\"description\":\"Direct: https://artist.example/first.mp3\"},"
                + "\"contentDetails\":{\"duration\":\"PT59S\"}}]}");
        List<YouTubeVideo> videos = YouTubeOfficialApi.parseVideoDetails(
                response, Arrays.asList("first", "second"));
        assertEquals(2, videos.size());
        assertEquals("first", videos.get(0).id);
        assertEquals("0:59", videos.get(0).duration);
        assertEquals("Direct: https://artist.example/first.mp3",
                videos.get(0).description);
        assertEquals("B & C", videos.get(1).title);
        assertEquals("62:03", videos.get(1).duration);
    }

    @Test
    public void extractsUniqueVideoIds() throws Exception {
        JSONObject response = new JSONObject("{\"items\":["
                + "{\"id\":{\"videoId\":\"one\"}},"
                + "{\"id\":{\"videoId\":\"one\"}},"
                + "{\"id\":{\"videoId\":\"two\"}}]}");
        assertEquals(Arrays.asList("one", "two"),
                YouTubeOfficialApi.parseSearchIds(response));
    }

    @Test
    public void parsesPlainTextTopLevelComments() throws Exception {
        JSONObject response = new JSONObject("{\"items\":[{\"snippet\":{"
                + "\"topLevelComment\":{\"snippet\":{"
                + "\"authorDisplayName\":\"A &amp; B\","
                + "\"textOriginal\":\"Useful comment\"}}}}]}");
        List<YouTubeComment> comments = YouTubeOfficialApi.parseComments(response);
        assertEquals(1, comments.size());
        assertEquals("A & B", comments.get(0).author);
        assertEquals("Useful comment", comments.get(0).content);
    }

    @Test
    public void parsesUniqueSubscriptionChannelTitles() throws Exception {
        JSONObject response = new JSONObject("{\"items\":["
                + "{\"snippet\":{\"title\":\"Channel &amp; One\"}},"
                + "{\"snippet\":{\"title\":\"Channel &amp; One\"}},"
                + "{\"snippet\":{\"title\":\"Channel Two\"}}]}");
        assertEquals(Arrays.asList("Channel & One", "Channel Two"),
                YouTubeOfficialApi.parseSubscriptions(response));
    }
}
