package com.solar.launcher.youtube.official;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class YouTubeOAuthProtocolTest {

    @Test
    public void parsesDeviceCodeAndServerInterval() throws Exception {
        YouTubeOAuthProtocol.DeviceCode code = YouTubeOAuthProtocol.parseDeviceCode(
                "{\"device_code\":\"device-secret\",\"user_code\":\"ABCD-EFGH\","
                        + "\"verification_url\":\"https://google.com/device\","
                        + "\"expires_in\":1800,\"interval\":7}");
        assertEquals("device-secret", code.deviceCode);
        assertEquals("ABCD-EFGH", code.userCode);
        assertEquals("https://google.com/device", code.verificationUrl);
        assertEquals(1800, code.expiresInSec);
        assertEquals(7, code.intervalSec);
    }

    @Test
    public void mapsPendingSlowDownAndTerminalErrors() {
        assertEquals(YouTubeOAuthProtocol.PollState.AUTHORIZATION_PENDING,
                YouTubeOAuthProtocol.parsePollResponse(
                        400, "{\"error\":\"authorization_pending\"}").state);
        assertEquals(YouTubeOAuthProtocol.PollState.SLOW_DOWN,
                YouTubeOAuthProtocol.parsePollResponse(
                        400, "{\"error\":\"slow_down\"}").state);
        assertEquals(12, YouTubeOAuthProtocol.nextPollIntervalSec(
                7, YouTubeOAuthProtocol.PollState.SLOW_DOWN));
        assertEquals(YouTubeOAuthProtocol.PollState.ACCESS_DENIED,
                YouTubeOAuthProtocol.parsePollResponse(
                        400, "{\"error\":\"access_denied\"}").state);
        assertEquals(YouTubeOAuthProtocol.PollState.EXPIRED,
                YouTubeOAuthProtocol.parsePollResponse(
                        400, "{\"error\":\"expired_token\"}").state);
    }

    @Test
    public void parsesGrantedTokenWithoutLoggingOrTransformingIt() {
        YouTubeOAuthProtocol.PollResult result = YouTubeOAuthProtocol.parsePollResponse(
                200,
                "{\"access_token\":\"access-value\",\"refresh_token\":\"refresh-value\","
                        + "\"expires_in\":3600,\"scope\":\"youtube.readonly\"}");
        assertEquals(YouTubeOAuthProtocol.PollState.GRANTED, result.state);
        assertEquals("access-value", result.accessToken);
        assertEquals("refresh-value", result.refreshToken);
        assertEquals(3600, result.expiresInSec);
    }
}
