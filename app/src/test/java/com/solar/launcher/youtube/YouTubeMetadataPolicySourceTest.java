package com.solar.launcher.youtube;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevent accidental restoration of the removed scraping/stream backend. */
public class YouTubeMetadataPolicySourceTest {

    @Test
    public void appContainsNoLegacyYoutubeBackendPackage() throws Exception {
        File root = new File("app/src/main/java/com/solar/launcher/youtube");
        if (!root.isDirectory()) root = new File("src/main/java/com/solar/launcher/youtube");
        assertTrue(root.isDirectory());
        File legacy = new File(root, "api");
        File[] legacyJava = legacy.listFiles();
        assertTrue(legacyJava == null || legacyJava.length == 0);

        String client = read(new File(root, "YouTubeClient.java"));
        assertFalse(client.contains("Invidious"));
        assertFalse(client.contains("PipedBackend"));
        assertFalse(client.contains("direct_url"));
        assertTrue(client.contains("ACQUISITION_BLOCKED"));
    }

    private static String read(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        reader.close();
        return text.toString();
    }
}
