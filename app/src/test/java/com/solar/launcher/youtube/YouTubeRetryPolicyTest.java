package com.solar.launcher.youtube;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YouTubeRetryPolicyTest {

    @Test
    public void retriesOnlyTemporaryFailuresWithinBound() {
        assertTrue(YouTubeRetryPolicy.shouldRetry(
                new SocketTimeoutException("timeout"), 1));
        assertTrue(YouTubeRetryPolicy.shouldRetry(
                new IOException("youtube_http_503"), 2));
        assertTrue(YouTubeRetryPolicy.shouldRetry(
                new IOException("rateLimitExceeded"), 1));

        assertFalse(YouTubeRetryPolicy.shouldRetry(
                new IOException("quotaExceeded"), 1));
        assertFalse(YouTubeRetryPolicy.shouldRetry(
                new IOException("invalidCredentials"), 1));
        assertFalse(YouTubeRetryPolicy.shouldRetry(
                new IOException("youtube_http_503"), 3));
    }

    @Test
    public void backoffIsBoundedAndHasDeterministicTestJitter() {
        long first = YouTubeRetryPolicy.delayMs(1, new Random(7L));
        long second = YouTubeRetryPolicy.delayMs(2, new Random(7L));
        assertTrue(first >= 400L && first < 534L);
        assertTrue(second >= 800L && second < 1_067L);
    }
}
