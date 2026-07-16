package com.solar.launcher.net;

import org.junit.Test;

import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public class TlsHelperTest {
    @Test
    public void socketFactory_handshakesWithNprFeed() throws Exception {
        headOk("https://feeds.npr.org/510289/podcast.xml");
    }

    /** Let's Encrypt ISRG X1 chain — fails on stock API 17 without bundled roots. */
    @Test
    public void socketFactory_handshakesWithLetsEncryptHost() throws Exception {
        headOk("https://valid-isrgrootx1.letsencrypt.org/");
    }

    private static void headOk(String urlStr) throws Exception {
        TlsHelper.init(null);
        HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
        conn.setSSLSocketFactory(TlsHelper.socketFactory());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("HEAD");
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 400) throw new AssertionError("HTTP " + code + " for " + urlStr);
    }
}
