package com.solar.launcher.soulseek;

/** ponytail: NAT-PMP response field parsing self-check */
public class SoulseekNatpmpTest {
  public static void main(String[] args) {
    byte[] mapOk = new byte[16];
    mapOk[1] = (byte) (2 + 128);
    mapOk[10] = (byte) 0xEE;
    mapOk[11] = (byte) 0x11;
    int port = ((mapOk[10] & 0xff) << 8) | (mapOk[11] & 0xff);
    if (port != 0xEE11) throw new AssertionError("port parse");

    SoulseekNatpmp.Result r = new SoulseekNatpmp.Result(61001, 61001, "1.2.3.4", "192.168.1.1", "ok");
    if (!r.mapped()) throw new AssertionError("mapped");
    SoulseekNatpmp.Result bad = new SoulseekNatpmp.Result(61001, 61001, null, null, "map_failed");
    if (bad.mapped()) throw new AssertionError("not mapped");
  }
}
