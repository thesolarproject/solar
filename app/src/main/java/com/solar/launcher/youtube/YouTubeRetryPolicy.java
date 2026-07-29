package com.solar.launcher.youtube;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Random;

/**
 * Bounded retry policy for official YouTube metadata requests.
 *
 * Quota exhaustion, authorization failures, and malformed requests are never
 * retried. Temporary transport and 5xx failures receive two short retries on
 * the existing background executor.
 */
public final class YouTubeRetryPolicy {

    static final int MAX_ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 400L;
    private static final long MAX_DELAY_MS = 2_000L;

    private YouTubeRetryPolicy() {}

    public static boolean shouldRetry(Throwable error, int attempt) {
        if (error == null || attempt >= MAX_ATTEMPTS) return false;
        if (error instanceof SocketTimeoutException
                || error instanceof ConnectException
                || error instanceof UnknownHostException) {
            return true;
        }
        String reason = error.getMessage();
        if (reason == null) return false;
        String clean = reason.trim().toLowerCase(Locale.US);
        return clean.equals("network_error")
                || clean.equals("network_timeout")
                || clean.equals("network_unavailable")
                || clean.equals("backenderror")
                || clean.equals("internalerror")
                || clean.equals("ratelimitexceeded")
                || clean.equals("youtube_http_429")
                || clean.equals("youtube_http_500")
                || clean.equals("youtube_http_502")
                || clean.equals("youtube_http_503")
                || clean.equals("youtube_http_504");
    }

    static long delayMs(int attempt, Random random) {
        int retryNumber = Math.max(1, attempt);
        long exponential = BASE_DELAY_MS << Math.min(3, retryNumber - 1);
        long bounded = Math.min(MAX_DELAY_MS, exponential);
        int jitterBound = (int) Math.max(1L, bounded / 3L);
        int jitter = random != null ? random.nextInt(jitterBound) : 0;
        return bounded + jitter;
    }
}
