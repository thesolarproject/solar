package com.solar.launcher.podcast;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PodcastIndexClientTest {
    @Test
    public void authHash_isLowerHexSha1() throws Exception {
        String hash = PodcastIndexClient.authHash("key", "secret", 1700000000L);
        if (hash.length() != 40) throw new AssertionError("sha1 hex length");
        if (!hash.equals(hash.toLowerCase())) throw new AssertionError("lowercase");
        if (!hash.matches("[0-9a-f]+")) throw new AssertionError("hex");
        String again = PodcastIndexClient.authHash("key", "secret", 1700000000L);
        if (!hash.equals(again)) throw new AssertionError("deterministic");
    }

    @Test
    public void isConfigured_falseWhenKeysEmpty() {
        if (PodcastIndexClient.isConfigured()) throw new AssertionError("empty build keys");
    }
}
