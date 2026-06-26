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
        String u = SoulseekAccount.generateUsername(null);
        String p = SoulseekAccount.generateAutoPassword();
        if (!SoulseekAccount.isFriendCode(u)) throw new AssertionError("friend code " + u);
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
    public void parseUserInfoResponse_readsDescription() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(SoulseekWire.packString("Hello bio\nLine two"));
        bos.write(new byte[] {0}); // no picture
        bos.write(SoulseekWire.packUInt32(6));
        bos.write(SoulseekWire.packUInt32(2));
        bos.write(new byte[] {1}); // slots avail
        bos.write(SoulseekWire.packUInt32(1));
        SoulseekWire.UserInfoResponse info =
                SoulseekWire.parseUserInfoResponse(bos.toByteArray());
        if (!info.description.startsWith("Hello bio")) {
            throw new AssertionError("descr: " + info.description);
        }
        if (info.totalUploadSlots != 6) throw new AssertionError("slots " + info.totalUploadSlots);
        if (info.queueSize != 2) throw new AssertionError("queue " + info.queueSize);
        if (!info.slotsAvailable) throw new AssertionError("slotsAvail");
        if (info.uploadAllowed != 1) throw new AssertionError("perm " + info.uploadAllowed);
    }

    @Test
    public void packUserInfoResponse_roundtripWithPicture() throws Exception {
        byte[] pic = new byte[] { (byte) 0xff, (byte) 0xd8, 0x01, 0x02 };
        byte[] packed = SoulseekWire.packUserInfoResponse("Reach bio", pic, true, 3, 6, 1);
        SoulseekWire.UserInfoResponse info = SoulseekWire.parseUserInfoResponse(packed);
        if (!"Reach bio".equals(info.description)) throw new AssertionError("descr");
        if (info.totalUploadSlots != 6) throw new AssertionError("total");
        if (info.queueSize != 3) throw new AssertionError("queue");
        if (!info.slotsAvailable) throw new AssertionError("avail");
        if (info.uploadAllowed != 1) throw new AssertionError("perm");
    }

    @Test
    public void parseGetUserStats_readsFields() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(SoulseekWire.packString("peeruser"));
        bos.write(SoulseekWire.packUInt32(512000));
        bos.write(SoulseekWire.packUInt32(42));
        bos.write(SoulseekWire.packUInt32(0)); // unknown
        bos.write(SoulseekWire.packUInt32(1200));
        bos.write(SoulseekWire.packUInt32(15));
        SoulseekWire.GetUserStatsResponse stats =
                SoulseekWire.parseGetUserStats(bos.toByteArray());
        if (!"peeruser".equals(stats.username)) throw new AssertionError("user");
        if (stats.avgSpeed != 512000) throw new AssertionError("speed");
        if (stats.uploadNum != 42) throw new AssertionError("uploads");
        if (stats.files != 1200) throw new AssertionError("files");
        if (stats.dirs != 15) throw new AssertionError("dirs");
    }

    @Test
    public void packGetUserStatsRequest_isUsernameString() throws Exception {
        byte[] req = SoulseekWire.packGetUserStatsRequest("someone");
        SoulseekWire.Reader r = new SoulseekWire.Reader(req);
        if (!"someone".equals(r.readString())) throw new AssertionError("name");
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
    public void parseShareList_readsFilesWithAttributes() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        out.writeInt(Integer.reverseBytes(1));
        writeWireString(out, "@@peer\\Music");
        out.writeInt(Integer.reverseBytes(2));
        writeBrowseShareFile(out, "a.mp3", 1024, "mp3", 2);
        writeBrowseShareFile(out, "b.flac", 2048, "flac", 1);
        out.writeInt(Integer.reverseBytes(0));
        out.writeInt(Integer.reverseBytes(0));
        java.util.List<SoulseekWire.BrowseFolder> folders =
                SoulseekWire.parseShareList(zlibWrap(bos.toByteArray()));
        int files = 0;
        for (SoulseekWire.BrowseFolder folder : folders) files += folder.files.size();
        if (files != 2) throw new AssertionError("files=" + files);
    }

    @Test
    public void parseShareList_roundtripFromShareIndex() throws Exception {
        java.io.File music = new java.io.File(System.getProperty("java.io.tmpdir"), "wire_share_music");
        deleteTree(music);
        new java.io.File(music, "Artist").mkdirs();
        java.io.File track = new java.io.File(music, "Artist/song.mp3");
        track.getParentFile().mkdirs();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(track);
        fos.write(new byte[128]);
        fos.close();

        SoulseekShareIndex idx = new SoulseekShareIndex();
        idx.scan("peer", music, null);
        byte[] zlib = SoulseekShareIndex.zlibCompress(idx.buildShareListUncompressed());
        java.util.List<SoulseekWire.BrowseFolder> folders = SoulseekWire.parseShareList(zlib);
        int files = 0;
        for (SoulseekWire.BrowseFolder folder : folders) files += folder.files.size();
        if (files != 1) throw new AssertionError("roundtrip files=" + files);
        deleteTree(music);
    }

    private static void writeWireString(java.io.DataOutputStream out, String s) throws Exception {
        byte[] raw = s.getBytes("UTF-8");
        out.writeInt(Integer.reverseBytes(raw.length));
        out.write(raw);
    }

    private static void writeBrowseShareFile(java.io.DataOutputStream out, String name, long size,
            String ext, int attrCount) throws Exception {
        out.writeByte(1);
        writeWireString(out, name);
        out.writeInt(Integer.reverseBytes((int) size));
        out.writeInt(Integer.reverseBytes(0xffffffff));
        writeWireString(out, ext);
        out.writeInt(Integer.reverseBytes(attrCount));
        for (int i = 0; i < attrCount; i++) {
            out.writeInt(Integer.reverseBytes(i == 0 ? 0 : 1));
            out.writeInt(Integer.reverseBytes(320000));
        }
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteTree(k);
        }
        f.delete();
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
