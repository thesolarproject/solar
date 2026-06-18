package com.solar.launcher.soulseek;

import org.junit.Test;

public class SoulseekWireTest {
    @Test
    public void distribFrame_roundtrip() throws Exception {
        byte[] ping = SoulseekWire.distribMessage(0, new byte[0]);
        SoulseekWire.DistribFrame frame = SoulseekWire.readDistribFrame(
                new java.io.ByteArrayInputStream(ping));
        if (frame.code != 0 || frame.body.length != 0) {
            throw new AssertionError("distrib ping");
        }
    }

    @Test
    public void loginBody_matchesProtocolExample() throws Exception {
        byte[] body = SoulseekWire.loginBody("username", "password");
        if (body.length != 68) throw new AssertionError("login body length " + body.length);
        String hash = SoulseekWire.md5Hex("usernamepassword");
        if (!"d51c9a7e9353746a6020f9602d452929".equals(hash)) throw new AssertionError("md5");
    }

    @Test
    public void ipToHost_reversesWireBytes() {
        // 192.168.1.1 on wire as little-endian uint32: 01 01 A8 C0
        int wire = 0xC0A80101;
        if (!"192.168.1.1".equals(SoulseekWire.ipToHost(wire))) {
            throw new AssertionError("ip " + SoulseekWire.ipToHost(wire));
        }
    }

    @Test
    public void generateCredentials_areValid() {
        String u = SoulseekAccount.generateUsername();
        String p = SoulseekAccount.generatePassword();
        if (u.length() < 5 || u.length() > 30) throw new AssertionError("username len");
        if (p.length() < 8) throw new AssertionError("password len");
        if (!SoulseekAccount.isValidUsername(u)) throw new AssertionError("generated user");
    }

    @Test
    public void privateSearchResultsExcluded() throws Exception {
        byte[] body = buildSearchBody(1, new String[][]{{"public.mp3"}, {"private.mp3"}});
        byte[] zlib = zlibWrap(body);
        SoulseekWire.SearchResponse parsed = SoulseekWire.parseSearchResponse(zlib, 1);
        if (parsed.files.size() != 1) throw new AssertionError("count " + parsed.files.size());
        if (!"public.mp3".equals(parsed.files.get(0).filename)) throw new AssertionError("name");
    }

    @Test
    public void sanitizeDisplay_stripsControlAndRtl() {
        String in = "Superman\u0007Eminem\u202ename";
        String out = SoulseekWire.sanitizeDisplay(in);
        if (out.contains("\u0007") || out.contains("\u202e")) throw new AssertionError(out);
        if (!out.contains("Superman")) throw new AssertionError("stripped too much: " + out);
    }

    @Test
    public void sanitizeDisplay_truncatesLongNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        String out = SoulseekWire.sanitizeDisplay(sb.toString());
        if (out.length() > SoulseekWire.DISPLAY_MAX_LEN + 1) {
            throw new AssertionError("len " + out.length());
        }
    }

    @Test
    public void parseSearchResponse_readsUploadSpeedAttribute() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(SoulseekWire.packString("fastpeer"));
        bos.write(SoulseekWire.packUInt32(1));
        bos.write(SoulseekWire.packUInt32(1));
        writeSearchFile(bos, "song.mp3", 1, 512000);
        byte[] zlib = zlibWrap(bos.toByteArray());
        SoulseekWire.SearchResponse parsed = SoulseekWire.parseSearchResponse(zlib, 1);
        if (parsed.files.size() != 1) throw new AssertionError("count");
        SoulseekWire.SearchFile f = parsed.files.get(0);
        if (!f.freeSlot) throw new AssertionError("slot");
        if (f.speed != 512000) throw new AssertionError("speed " + f.speed);
    }

    @Test
    public void parseSearchResponse_capsHugeFileCount() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(SoulseekWire.packString("peer"));
        bos.write(SoulseekWire.packUInt32(1));
        bos.write(SoulseekWire.packUInt32(99999));
        bos.write(SoulseekWire.packUInt32(0));
        byte[] zlib = zlibWrap(bos.toByteArray());
        SoulseekWire.SearchResponse parsed = SoulseekWire.parseSearchResponse(zlib, 1);
        if (parsed.files.size() > SoulseekWire.MAX_FILES_PER_RESPONSE) {
            throw new AssertionError("uncapped " + parsed.files.size());
        }
    }

    private static byte[] zlibWrap(byte[] in) {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(in);
        d.finish();
        byte[] buf = new byte[in.length + 128];
        int n = d.deflate(buf);
        d.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    /** [0]=public names, [1]=private names (buddy-only; must be dropped). */
    private static byte[] buildSearchBody(int token, String[][] files) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(SoulseekWire.packString("peer"));
        bos.write(SoulseekWire.packUInt32(token));
        bos.write(SoulseekWire.packUInt32(files[0].length));
        for (String name : files[0]) writeSearchFile(bos, name);
        bos.write(SoulseekWire.packUInt32(files.length > 1 ? files[1].length : 0));
        if (files.length > 1) {
            for (String name : files[1]) writeSearchFile(bos, name);
        }
        return bos.toByteArray();
    }

    private static void writeSearchFile(java.io.ByteArrayOutputStream bos, String name) throws Exception {
        writeSearchFile(bos, name, 1, 0);
    }

    private static void writeSearchFile(java.io.ByteArrayOutputStream bos, String name, int slotFlag, int speed)
            throws Exception {
        bos.write(slotFlag);
        bos.write(SoulseekWire.packString(name));
        bos.write(SoulseekWire.packUInt64(1024));
        bos.write(SoulseekWire.packUInt32(3));
        bos.write("mp3".getBytes("UTF-8"));
        if (speed > 0) {
            bos.write(SoulseekWire.packUInt32(1));
            bos.write(SoulseekWire.packUInt32(6));
            bos.write(SoulseekWire.packUInt32(speed));
        } else {
            bos.write(SoulseekWire.packUInt32(0));
        }
    }
}
