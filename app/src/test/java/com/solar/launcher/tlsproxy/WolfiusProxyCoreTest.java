package com.solar.launcher.tlsproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM checks for the Wolfius proxy core port (no Android APIs invoked). */
public class WolfiusProxyCoreTest {

    @Test
    public void baseDomainHandlesStandardDomains() {
        assertEquals("deezer.com", MitmKeyStoreManager.getBaseDomain("www.deezer.com"));
        assertEquals("deezer.com", MitmKeyStoreManager.getBaseDomain("api.deezer.com"));
        assertEquals("example.org", MitmKeyStoreManager.getBaseDomain("example.org"));
        assertEquals("example.co.uk", MitmKeyStoreManager.getBaseDomain("streams.example.co.uk"));
        assertEquals("example.com.au", MitmKeyStoreManager.getBaseDomain("cdn.example.com.au"));
    }

    @Test
    public void baseDomainLeavesIpsAndBareHostsAlone() {
        assertEquals("192.168.1.5", MitmKeyStoreManager.getBaseDomain("192.168.1.5"));
        assertEquals("localhost", MitmKeyStoreManager.getBaseDomain("localhost"));
        assertNull(MitmKeyStoreManager.getBaseDomain(null));
    }

    @Test
    public void dnsForwarderParsesBasicQuery() {
        // Minimal DNS header (12 bytes) + one label "example.com" + QTYPE/QCLASS.
        byte[] packet = new byte[] {
                0x12, 0x34, // id
                0x01, 0x00, // flags: RD
                0x00, 0x01, // QDCOUNT = 1
                0x00, 0x00, // ANCOUNT
                0x00, 0x00, // NSCOUNT
                0x00, 0x00, // ARCOUNT
                7, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                3, 'c', 'o', 'm',
                0,
                0x00, 0x01, // QTYPE A
                0x00, 0x01  // QCLASS IN
        };
        String domain = DnsForwarder.parseDomain(packet);
        assertEquals("example.com", domain);
    }

    @Test
    public void dnsForwarderCachesIpMapping() {
        // Build a DNS response with an A record for example.com → 93.184.216.34.
        byte[] response = new byte[] {
                0x12, 0x34, // id
                (byte) 0x81, (byte) 0x80, // flags: QR RD RA
                0x00, 0x01, // QDCOUNT
                0x00, 0x01, // ANCOUNT
                0x00, 0x00, // NSCOUNT
                0x00, 0x00, // ARCOUNT
                7, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                3, 'c', 'o', 'm',
                0,
                0x00, 0x01, 0x00, 0x01, // QTYPE/QCLASS
                (byte) 0xC0, 0x0C,       // name pointer
                0x00, 0x01,             // TYPE A
                0x00, 0x01,             // CLASS IN
                0x00, 0x00, 0x00, 0x3C, // TTL 60
                0x00, 0x04,             // RDLENGTH 4
                93, (byte) 184, (byte) 216, 34 // 93.184.216.34
        };
        DnsForwarder.cacheDns(response, response.length, "example.com");
        assertEquals("example.com", DnsForwarder.getHostForIp("93.184.216.34"));
    }

    @Test
    public void proxyFacadeHasIptablesMethodName() {
        assertNotNull(WolfiusProxy.METHOD_IPTABLES);
        assertTrue(WolfiusProxy.METHOD_IPTABLES.length() > 0);
        assertNull(WolfiusProxy.currentMethod); // default: proxy off
    }
}
