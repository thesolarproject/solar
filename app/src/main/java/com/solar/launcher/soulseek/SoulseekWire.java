package com.solar.launcher.soulseek;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** ponytail: Soulseek binary wire format (Nicotine+ / slskmessages.py subset). */
final class SoulseekWire {
    static final String SERVER_HOST = "server.slsknet.org";
    static final int SERVER_PORT = 2242;
    static final int CLIENT_MAJOR = 160;
    static final int CLIENT_MINOR = 1;

    static final int MSG_LOGIN = 1;
    static final int MSG_SET_WAIT_PORT = 2;
    static final int MSG_GET_PEER_ADDRESS = 3;
    static final int MSG_CONNECT_TO_PEER = 18;
    static final int MSG_FILE_SEARCH = 26;
    static final int MSG_SET_STATUS = 28;
    static final int MSG_HAVE_NO_PARENT = 71;
    static final int MSG_ACCEPT_CHILDREN = 100;
    static final int MSG_POSSIBLE_PARENTS = 102;
    static final int MSG_EMBEDDED_MESSAGE = 93;
    static final int MSG_CANT_CONNECT_TO_PEER = 1001;
    static final int DISTRIB_SEARCH = 3;

    static final int PEER_FILE_SEARCH_RESPONSE = 9;
    static final int PEER_UPLOAD_FAILED = 46;
    static final int PEER_TRANSFER_REQUEST = 40;
    static final int PEER_TRANSFER_RESPONSE = 41;
    static final int PEER_QUEUE_UPLOAD = 43;
    static final int PEER_PLACE_IN_QUEUE = 44;
    static final int PEER_UPLOAD_DENIED = 50;

    static final int PEER_INIT_PIERCE = 0;
    static final int PEER_INIT_CONNECT = 1;

    static final int STATUS_ONLINE = 2;
    static final int MSG_SHARED_FOLDER_FILES = 35;
    static final int PEER_SHARES_REQUEST = 4;
    static final int PEER_SHARES_REPLY = 5;
    static final int PEER_FOLDER_CONTENTS_REQUEST = 36;
    static final int PEER_FOLDER_CONTENTS_RESPONSE = 37;

    static final int TRANSFER_DOWNLOAD = 0;
    static final int TRANSFER_UPLOAD = 1;

    static final int MAX_FILES_PER_RESPONSE = 200;
    static final int DISPLAY_MAX_LEN = 120;

    /** Safe label for UI: strip controls/bidi marks, truncate. */
    static String sanitizeDisplay(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u200e' || c == '\u200f' || c == '\u202a' || c == '\u202b'
                    || c == '\u202c' || c == '\u202d' || c == '\u202e' || c == '\ufeff') {
                continue;
            }
            if (c < 0x20 && c != '\t' || c == 0x7f) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            sb.append(c);
        }
        if (sb.length() > DISPLAY_MAX_LEN) {
            sb.setLength(DISPLAY_MAX_LEN);
            sb.append('…');
        }
        return sb.toString();
    }


    static byte[] serverMessage(int code, byte[] body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int len = 4 + (body != null ? body.length : 0);
        out.writeInt(Integer.reverseBytes(len));
        out.writeInt(Integer.reverseBytes(code));
        if (body != null && body.length > 0) out.write(body);
        return bos.toByteArray();
    }

    static byte[] peerMessage(int code, byte[] body) throws IOException {
        return serverMessage(code, body);
    }

    static byte[] peerInitMessage(int code, byte[] body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int len = 1 + (body != null ? body.length : 0);
        out.writeInt(Integer.reverseBytes(len));
        out.writeByte(code);
        if (body != null && body.length > 0) out.write(body);
        return bos.toByteArray();
    }

    static byte[] packString(String s) throws IOException {
        if (s == null) s = "";
        byte[] raw = s.getBytes("UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(Integer.reverseBytes(raw.length));
        out.write(raw);
        return bos.toByteArray();
    }

    static byte[] packInt32(int v) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new DataOutputStream(bos).writeInt(Integer.reverseBytes(v));
        return bos.toByteArray();
    }

    static byte[] packUInt32(int v) throws IOException {
        return packInt32(v);
    }

    static byte[] packBool(boolean v) throws IOException {
        return new byte[] {(byte) (v ? 1 : 0)};
    }

    static byte[] packSharedFolderCounts(int dirs, int files) throws IOException {
        return concat(packUInt32(dirs), packUInt32(files));
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static long readUInt64(InputStream in) throws IOException {
        byte[] buf = new byte[8];
        readFully(in, buf);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[i] & 0xff);
        }
        return v;
    }

    static byte[] packUInt64(long v) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(Integer.reverseBytes((int) (v & 0xFFFFFFFFL)));
        out.writeInt(Integer.reverseBytes((int) (v >>> 32)));
        return bos.toByteArray();
    }

    static byte[] loginBody(String username, String password) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(packString(username));
        bos.write(packString(password));
        bos.write(packUInt32(CLIENT_MAJOR));
        String hash = md5Hex(username + password);
        bos.write(packString(hash));
        bos.write(packUInt32(CLIENT_MINOR));
        return bos.toByteArray();
    }

    static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] dig = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) {
            this.data = data != null ? data : new byte[0];
        }

        boolean hasRemaining() {
            return offset < data.length;
        }

        int readUInt32() throws IOException {
            if (offset + 4 > data.length) throw new IOException("underrun");
            int v = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
            offset += 4;
            return v;
        }

        int readInt32() throws IOException {
            return readUInt32();
        }

        long readUInt64() throws IOException {
            long lo = readUInt32() & 0xffffffffL;
            long hi = readUInt32() & 0xffffffffL;
            return (hi << 32) | lo;
        }

        int readUInt8() throws IOException {
            if (offset >= data.length) throw new IOException("underrun");
            return data[offset++] & 0xff;
        }

        boolean readBool() throws IOException {
            if (offset >= data.length) throw new IOException("underrun");
            return data[offset++] != 0;
        }

        String readString() throws IOException {
            int len = readUInt32();
            if (len < 0 || offset + len > data.length) throw new IOException("bad string len");
            try {
                java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8")
                        .newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
                String s = dec.decode(java.nio.ByteBuffer.wrap(data, offset, len)).toString();
                offset += len;
                return s;
            } catch (Exception e) {
                offset += len;
                return "";
            }
        }

        void skip(int n) throws IOException {
            if (offset + n > data.length) throw new IOException("skip underrun");
            offset += n;
        }

        long readFileSize() throws IOException {
            if (offset + 8 > data.length) throw new IOException("filesize underrun");
            if (data[offset + 7] == (byte) 255) {
                long size = readUInt32() & 0xffffffffL;
                skip(4);
                return size;
            }
            return readUInt64();
        }
    }

    static final class SearchFile {
        final String filename;
        final long size;
        final int bitrate;
        final int duration;
        final boolean freeSlot;
        final int speed;

        SearchFile(String filename, long size, int bitrate, int duration, boolean freeSlot, int speed) {
            this.filename = filename;
            this.size = size;
            this.bitrate = bitrate;
            this.duration = duration;
            this.freeSlot = freeSlot;
            this.speed = speed;
        }
    }

    static final class SearchResponse {
        final String username;
        final List<SearchFile> files;

        SearchResponse(String username, List<SearchFile> files) {
            this.username = username;
            this.files = files;
        }
    }

    /** Soulseek IPv4: reverse the four wire bytes (Nicotine+ unpack_ip). */
    static String ipToHost(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                (ip >> 24) & 0xff, (ip >> 16) & 0xff, (ip >> 8) & 0xff, ip & 0xff);
    }

    static SearchResponse parseSearchResponse(byte[] msgBody, int activeToken) {
        SearchResponse staged = parseSearchResponseStaged(msgBody, activeToken);
        if (!staged.files.isEmpty()) return staged;
        return parseSearchResponseSingle(msgBody, activeToken);
    }

    private static SearchResponse parseSearchResponseStaged(byte[] msgBody, int activeToken) {
        List<SearchFile> out = new ArrayList<SearchFile>();
        String username = "";
        try {
            Inflater inf = new Inflater();
            inf.setInput(msgBody);
            byte[] stage1 = new byte[4];
            int n = inf.inflate(stage1);
            if (n < 4) return new SearchResponse(username, out);
            Reader r1 = new Reader(stage1);
            int usernameLen = r1.readUInt32();
            byte[] stage2 = new byte[usernameLen + 4];
            n = inf.inflate(stage2);
            if (n < usernameLen + 4) return new SearchResponse(username, out);
            Reader r2 = new Reader(stage2);
            username = sanitizeDisplay(r2.readString());
            int token = r2.readUInt32();
            if (token != activeToken) return new SearchResponse(username, out);
            byte[] rest = inflateTail(inf);
            Reader r = new Reader(rest);
            int count = r.readUInt32();
            readSearchFileEntries(r, count, out, true);
            if (r.hasRemaining()) {
                int privCount = r.readUInt32();
                readSearchFileEntries(r, privCount, out, false);
            }
        } catch (Exception ignored) {}
        return new SearchResponse(username, out);
    }

    private static void readSearchFileEntries(Reader r, int count, List<SearchFile> out, boolean include)
            throws IOException {
        if (count < 0) return;
        if (count > MAX_FILES_PER_RESPONSE) count = MAX_FILES_PER_RESPONSE;
        for (int i = 0; i < count; i++) {
            int slotFlag = r.readUInt8();
            String name = sanitizeDisplay(r.readString());
            long size = r.readFileSize();
            int extLen = r.readUInt32();
            if (extLen < 0 || extLen > 4096) throw new IOException("bad ext len");
            r.skip(extLen);
            int attrs = r.readUInt32();
            if (attrs < 0 || attrs > 32) attrs = 32;
            int bitrate = 0;
            int duration = 0;
            int speed = 0;
            for (int a = 0; a < attrs; a++) {
                int code = r.readUInt32();
                int val = r.readUInt32();
                if (code == 0) bitrate = val;
                else if (code == 1) duration = val;
                else if (code == 6) speed = val;
            }
            boolean freeSlot = slotFlag == 1;
            if (include && isAudio(name)) {
                out.add(new SearchFile(name, size, bitrate, duration, freeSlot, speed));
            }
        }
    }

    private static SearchResponse parseSearchResponseSingle(byte[] msgBody, int activeToken) {
        List<SearchFile> out = new ArrayList<SearchFile>();
        String username = "";
        try {
            Reader r = new Reader(inflateAll(msgBody));
            username = sanitizeDisplay(r.readString());
            int token = r.readUInt32();
            if (token != activeToken) return new SearchResponse(username, out);
            int count = r.readUInt32();
            readSearchFileEntries(r, count, out, true);
            if (r.hasRemaining()) {
                int privCount = r.readUInt32();
                readSearchFileEntries(r, privCount, out, false);
            }
        } catch (Exception ignored) {}
        return new SearchResponse(username, out);
    }

    private static byte[] inflateAll(byte[] in) throws DataFormatException, IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n <= 0 && inf.needsInput()) break;
            bos.write(buf, 0, n);
        }
        inf.end();
        return bos.toByteArray();
    }

    private static byte[] inflateTail(Inflater inf) throws DataFormatException, IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n <= 0 && inf.needsInput()) break;
            bos.write(buf, 0, n);
        }
        inf.end();
        return bos.toByteArray();
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".m4a")
                || n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".opus") || n.endsWith(".wma");
    }

    static ServerFrame readServerFrame(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int len = Integer.reverseBytes(din.readInt());
        if (len < 4) throw new IOException("bad frame len " + len);
        int code = Integer.reverseBytes(din.readInt());
        int bodyLen = len - 4;
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) readFully(din, body);
        return new ServerFrame(code, body);
    }

    static PeerFrame readPeerFrame(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int len = Integer.reverseBytes(din.readInt());
        if (len < 4) throw new IOException("bad peer frame len " + len);
        int code = Integer.reverseBytes(din.readInt());
        int bodyLen = len - 4;
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) readFully(din, body);
        return new PeerFrame(code, body);
    }

    static PeerInitFrame readPeerInitFrame(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int len = Integer.reverseBytes(din.readInt());
        if (len < 1) throw new IOException("bad peer init len " + len);
        int code = din.readUnsignedByte();
        int bodyLen = len - 1;
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) readFully(din, body);
        return new PeerInitFrame(code, body);
    }

    /** Distributed-network frame: length (uint32) + code (uint8) + body. */
    static byte[] distribMessage(int code, byte[] body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int len = 1 + (body != null ? body.length : 0);
        out.writeInt(Integer.reverseBytes(len));
        out.writeByte(code);
        if (body != null && body.length > 0) out.write(body);
        return bos.toByteArray();
    }

    static DistribFrame readDistribFrame(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int len = Integer.reverseBytes(din.readInt());
        if (len < 1) throw new IOException("bad distrib len " + len);
        int code = din.readUnsignedByte();
        int bodyLen = len - 1;
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) readFully(din, body);
        return new DistribFrame(code, body);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    static int readRawUInt32(InputStream in) throws IOException {
        byte[] buf = new byte[4];
        readFully(in, buf);
        return (buf[0] & 0xff) | ((buf[1] & 0xff) << 8) | ((buf[2] & 0xff) << 16) | ((buf[3] & 0xff) << 24);
    }

    static final class ServerFrame {
        final int code;
        final byte[] body;
        ServerFrame(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    static final class PeerFrame {
        final int code;
        final byte[] body;
        PeerFrame(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    static final class PeerInitFrame {
        final int code;
        final byte[] body;
        PeerInitFrame(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    static final class DistribFrame {
        final int code;
        final byte[] body;
        DistribFrame(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }
}
