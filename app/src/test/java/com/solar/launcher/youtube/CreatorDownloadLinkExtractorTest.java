package com.solar.launcher.youtube;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class CreatorDownloadLinkExtractorTest {

    @Test
    public void returnsOnlyValidatedDirectAudioFilesFromOfficialDescriptionText() {
        String description = "Official download: https://artist.example/releases/My%20Song.flac\n"
                + "Mirror (https://cdn.example/My%20Song.mp3).\n"
                + "Website: https://artist.example/releases\n"
                + "Video: https://youtu.be/abc123\n"
                + "Unsupported: https://artist.example/readme.txt";

        List<CreatorDownloadLinkExtractor.Link> links =
                CreatorDownloadLinkExtractor.extract(description);

        assertEquals(2, links.size());
        assertEquals("My Song.flac", links.get(0).displayName);
        assertEquals("artist.example", links.get(0).host);
        assertEquals("My Song.mp3", links.get(1).displayName);
    }

    @Test
    public void deduplicatesAndBoundsDescriptionWork() {
        String direct = "https://artist.example/song.mp3";
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 20; i++) description.append(direct).append('\n');

        List<CreatorDownloadLinkExtractor.Link> links =
                CreatorDownloadLinkExtractor.extract(description.toString());

        assertEquals(1, links.size());
        assertEquals(direct, links.get(0).url);
        assertEquals(direct,
                CreatorDownloadLinkExtractor.compactForBookmark(
                        "page https://artist.example and file " + direct));
    }
}
