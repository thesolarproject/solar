package com.solar.launcher.soulseek;

import com.solar.launcher.DeviceFeatures;

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
public final class SoulseekWire {
    static final String SERVER_HOST = "server.slsknet.org";
    static final int SERVER_PORT = 2242;
    /**
     * Experimental major version reserved for new clients (SLSK protocol). Reach uses this
     * instead of impersonating Nicotine+ (160) or SoulseekQt (157) — login would reject
     * unknown majors, but peer browse/profile depend on TCP/NAT, not client id.
     */
    static final int CLIENT_MAJOR = 177;

    /** Upload permission codes in user-info response (peer code 16). */
    static final int UPLOAD_PERM_NO_ONE = 0;
    static final int UPLOAD_PERM_EVERYONE = 1;
    static final int UPLOAD_PERM_USERS_IN_LIST = 2;
    static final int UPLOAD_PERM_PERMITTED = 3;

    /** Bytes/sec advertised to the server (MSG_SEND_UPLOAD_SPEED) and peers. */
    static final int ADVERTISED_UPLOAD_SPEED = 10_485_760;

    static int clientMinor() {
        return DeviceFeatures.soulseekClientMinor();
    }

    static final int MSG_LOGIN = 1;
    static final int MSG_SET_WAIT_PORT = 2;
    static final int MSG_GET_PEER_ADDRESS = 3;
    static final int MSG_WATCH_USER = 5;
    static final int MSG_UNWATCH_USER = 6;
    static final int MSG_GET_USER_STATUS = 7;
    static final int MSG_CONNECT_TO_PEER = 18;
    static final int MSG_MESSAGE_USER = 22;
    static final int MSG_MESSAGE_ACKED = 23;
    static final int MSG_FILE_SEARCH = 26;
    static final int MSG_SET_STATUS = 28;
    static final int MSG_ADD_TO_IGNORELIST = 53;
    static final int MSG_ADD_TO_FAVORITES = 54;
    static final int MSG_REMOVE_FROM_FAVORITES = 55;
    static final int MSG_UNIGNORE_USER = 12;
    static final int MSG_ADD_THING_I_LIKE = 51;
    static final int MSG_REMOVE_THING_I_LIKE = 52;
    static final int MSG_USER_INTERESTS = 57;
    static final int MSG_ADD_THING_I_HATE = 117;
    static final int MSG_REMOVE_THING_I_HATE = 118;
    /** @deprecated use {@link #MSG_UNIGNORE_USER} */
    static final int MSG_REMOVE_FROM_IGNORELIST = MSG_UNIGNORE_USER;
    static final int MSG_HAVE_NO_PARENT = 71;
    static final int MSG_ACCEPT_CHILDREN = 100;
    static final int MSG_POSSIBLE_PARENTS = 102;
    static final int MSG_USER_SEARCH = 42;
    static final int MSG_ITEM_SIMILAR_USERS = 112;
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
    static final int MSG_GET_USER_STATS = 36;
    static final int MSG_SEND_UPLOAD_SPEED = 121;
    static final int MSG_SAY_CHATROOM = 13;
    static final int MSG_JOIN_ROOM = 14;
    static final int MSG_LEAVE_ROOM = 15;
    static final int MSG_USER_JOINED_ROOM = 16;
    static final int MSG_USER_LEFT_ROOM = 17;
    static final int MSG_ROOM_LIST = 64;
    static final int MSG_ROOM_TICKERS = 113;
    static final int MSG_ROOM_TICKER_ADDED = 114;
    static final int MSG_ROOM_TICKER_REMOVED = 115;
    static final int MSG_SET_ROOM_TICKER = 116;
    static final int PEER_SHARES_REQUEST = 4;
    static final int PEER_SHARES_REPLY = 5;
    static final int PEER_FOLDER_CONTENTS_REQUEST = 36;
    static final int PEER_FOLDER_CONTENTS_RESPONSE = 37;
    static final int PEER_USER_INFO_REQUEST = 15;
    static final int PEER_USER_INFO_RESPONSE = 16;

    static final int STATUS_OFFLINE = 0;
    static final int STATUS_AWAY = 1;
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
        bos.write(packUInt32(clientMinor()));
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

        int remaining() {
            return data.length - offset;
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
        final int queueLength;

        SearchFile(String filename, long size, int bitrate, int duration, boolean freeSlot,
                   int speed, int queueLength) {
            this.filename = filename;
            this.size = size;
            this.bitrate = bitrate;
            this.duration = duration;
            this.freeSlot = freeSlot;
            this.speed = speed;
            this.queueLength = queueLength;
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
            int queueLength = 0;
            for (int a = 0; a < attrs; a++) {
                int code = r.readUInt32();
                int val = r.readUInt32();
                if (code == 0) bitrate = val;
                else if (code == 1) duration = val;
                else if (code == 7 || code == 2) queueLength = val;
                else if (code == 6) speed = val;
            }
            boolean freeSlot = slotFlag == 1;
            if (include && isAudio(name)) {
                out.add(new SearchFile(name, size, bitrate, duration, freeSlot, speed, queueLength));
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

    public static final class BrowseFile {
        public final String folder;
        public final String name;
        public final long size;
        public final String ext;

        public BrowseFile(String folder, String name, long size, String ext) {
            this.folder = folder;
            this.name = name;
            this.size = size;
            this.ext = ext;
        }

        public String virtualPath() {
            if (folder == null || folder.isEmpty()) return name;
            return folder + "\\" + name;
        }
    }

    public static final class BrowseFolder {
        public final String path;
        public final List<BrowseFile> files;

        public BrowseFolder(String path, List<BrowseFile> files) {
            this.path = path;
            this.files = files;
        }
    }

    static byte[] packUserInfoResponse(String descr, boolean slotsAvail, int queueSize,
            int uploadTotal, int uploadAllowed) throws IOException {
        return packUserInfoResponse(descr, null, slotsAvail, queueSize, uploadTotal, uploadAllowed);
    }

    static byte[] packUserInfoResponse(String descr, byte[] picture, boolean slotsAvail, int queueSize,
            int uploadTotal, int uploadAllowed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        writeString(out, descr != null ? descr : "");
        if (picture != null && picture.length > 0) {
            out.write(1);
            out.writeInt(Integer.reverseBytes(picture.length));
            out.write(picture);
        } else {
            out.write(0);
        }
        out.writeInt(Integer.reverseBytes(uploadTotal));
        out.writeInt(Integer.reverseBytes(queueSize));
        out.write(slotsAvail ? 1 : 0);
        out.writeInt(Integer.reverseBytes(uploadAllowed));
        return bos.toByteArray();
    }

    static byte[] packInterestString(String item) throws IOException {
        return packString(item != null ? item : "");
    }

    static byte[] packItemSimilarUsersRequest(String item) throws IOException {
        return packInterestString(item);
    }

    public static final class ItemSimilarUsersResponse {
        public final String item;
        public final List<String> users;

        ItemSimilarUsersResponse(String item, List<String> users) {
            this.item = item != null ? item : "";
            this.users = users != null ? users : new ArrayList<String>();
        }
    }

    static ItemSimilarUsersResponse parseItemSimilarUsersResponse(byte[] body) throws IOException {
        Reader r = new Reader(body);
        String item = r.readString();
        int num = r.readUInt32();
        if (num < 0) num = 0;
        if (num > 5000) num = 5000;
        List<String> users = new ArrayList<String>(num);
        for (int i = 0; i < num; i++) {
            users.add(r.readString());
        }
        return new ItemSimilarUsersResponse(item, users);
    }

    static byte[] packUserInterestsRequest(String username) throws IOException {
        return packString(username != null ? username : "");
    }

    public static final class UserInterestsResponse {
        public final String username;
        public final List<String> likes;
        public final List<String> dislikes;

        UserInterestsResponse(String username, List<String> likes, List<String> dislikes) {
            this.username = username != null ? username : "";
            this.likes = likes != null ? likes : new ArrayList<String>();
            this.dislikes = dislikes != null ? dislikes : new ArrayList<String>();
        }
    }

    static UserInterestsResponse parseUserInterestsResponse(byte[] body) throws IOException {
        Reader r = new Reader(body);
        String user = r.readString();
        int likesNum = r.readUInt32();
        if (likesNum < 0) likesNum = 0;
        if (likesNum > 500) likesNum = 500;
        List<String> likes = new ArrayList<String>(likesNum);
        for (int i = 0; i < likesNum; i++) likes.add(r.readString());
        int hatesNum = r.readUInt32();
        if (hatesNum < 0) hatesNum = 0;
        if (hatesNum > 500) hatesNum = 500;
        List<String> hates = new ArrayList<String>(hatesNum);
        for (int i = 0; i < hatesNum; i++) hates.add(r.readString());
        return new UserInterestsResponse(user, likes, hates);
    }

    public static final class UserInfoResponse {
        public final String description;
        public final int totalUploadSlots;
        public final int queueSize;
        public final boolean slotsAvailable;
        public final int uploadAllowed;

        UserInfoResponse(String description, int totalUploadSlots, int queueSize,
                boolean slotsAvailable, int uploadAllowed) {
            this.description = description != null ? description : "";
            this.totalUploadSlots = totalUploadSlots;
            this.queueSize = queueSize;
            this.slotsAvailable = slotsAvailable;
            this.uploadAllowed = uploadAllowed;
        }
    }

    /** Server code 36 response body (also used when client requests stats). */
    public static final class GetUserStatsResponse {
        public final String username;
        public final int avgSpeed;
        public final int uploadNum;
        public final int files;
        public final int dirs;

        GetUserStatsResponse(String username, int avgSpeed, int uploadNum, int files, int dirs) {
            this.username = username != null ? username : "";
            this.avgSpeed = avgSpeed;
            this.uploadNum = uploadNum;
            this.files = files;
            this.dirs = dirs;
        }
    }

    static byte[] packGetUserStatsRequest(String username) throws IOException {
        return packString(username != null ? username : "");
    }

    static GetUserStatsResponse parseGetUserStats(byte[] body) throws IOException {
        Reader r = new Reader(body);
        String user = r.readString();
        int avgspeed = r.readUInt32();
        int uploadnum = r.readUInt32();
        if (r.hasRemaining()) {
            r.readUInt32(); // unknown field
        }
        int files = r.hasRemaining() ? r.readUInt32() : 0;
        int dirs = r.hasRemaining() ? r.readUInt32() : 0;
        return new GetUserStatsResponse(user, avgspeed, uploadnum, files, dirs);
    }

    static UserInfoResponse parseUserInfoResponse(byte[] body) throws IOException {
        Reader r = new Reader(body);
        String descr = r.readString();
        if (r.hasRemaining()) {
            boolean hasPic = r.readBool();
            if (hasPic && r.hasRemaining()) {
                int picLen = r.readUInt32();
                if (picLen > 0 && picLen < 10_000_000) {
                    r.skip(picLen);
                }
            }
        }
        int totalUploadSlots = 0;
        int queueSize = 0;
        boolean slotsAvailable = false;
        int uploadAllowed = 0;
        if (r.hasRemaining()) {
            totalUploadSlots = r.readUInt32();
        }
        if (r.hasRemaining()) {
            queueSize = r.readUInt32();
        }
        if (r.hasRemaining()) {
            slotsAvailable = r.readBool();
        }
        // nicotine: Museek+ may leave <4 bytes — guard before uploadallowed
        if (r.remaining() >= 4) {
            uploadAllowed = r.readUInt32();
        }
        return new UserInfoResponse(descr, totalUploadSlots, queueSize, slotsAvailable, uploadAllowed);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) s = "";
        byte[] raw = s.getBytes("UTF-8");
        out.writeInt(Integer.reverseBytes(raw.length));
        out.write(raw);
    }

    private static void skipBrowseFileAttributes(Reader r) throws IOException {
        int attrs = r.readUInt32();
        if (attrs < 0) attrs = 0;
        if (attrs > 32) attrs = 32;
        for (int a = 0; a < attrs; a++) {
            r.readUInt32();
            r.readUInt32();
        }
    }

    private static BrowseFile readBrowseFile(Reader r, String dir) throws IOException {
        r.readUInt8();
        String name = r.readString();
        long size = r.readFileSize();
        int extLen = r.readUInt32();
        if (extLen > 0 && extLen < 256) {
            r.skip(extLen);
        }
        skipBrowseFileAttributes(r);
        if (isAudio(name)) {
            return new BrowseFile(dir, name, size, SoulseekShareIndex.extension(name));
        }
        return null;
    }

    static List<BrowseFolder> parseShareList(byte[] compressed) {
        List<BrowseFolder> folders = new ArrayList<BrowseFolder>();
        try {
            byte[] raw = inflateAll(compressed);
            Reader r = new Reader(raw);
            int dirCount = r.readUInt32();
            for (int d = 0; d < dirCount && d < 10000; d++) {
                String dir = r.readString();
                int fileCount = r.readUInt32();
                List<BrowseFile> files = new ArrayList<BrowseFile>();
                for (int f = 0; f < fileCount && f < MAX_FILES_PER_RESPONSE; f++) {
                    BrowseFile file = readBrowseFile(r, dir);
                    if (file != null) files.add(file);
                }
                folders.add(new BrowseFolder(dir, files));
            }
        } catch (Exception ignored) {}
        return folders;
    }

    static List<BrowseFolder> parseFolderContents(byte[] compressed) {
        List<BrowseFolder> folders = new ArrayList<BrowseFolder>();
        try {
            byte[] raw = inflateAll(compressed);
            Reader r = new Reader(raw);
            r.readUInt32(); // token
            r.readString(); // folder
            int dirCount = r.readUInt32();
            for (int d = 0; d < dirCount && d < 10000; d++) {
                String dir = r.readString();
                int fileCount = r.readUInt32();
                List<BrowseFile> files = new ArrayList<BrowseFile>();
                for (int f = 0; f < fileCount && f < MAX_FILES_PER_RESPONSE; f++) {
                    BrowseFile file = readBrowseFile(r, dir);
                    if (file != null) files.add(file);
                }
                folders.add(new BrowseFolder(dir, files));
            }
        } catch (Exception ignored) {}
        return folders;
    }

    static String parentFolder(String virtualPath) {
        if (virtualPath == null) return "";
        int slash = Math.max(virtualPath.lastIndexOf('\\'), virtualPath.lastIndexOf('/'));
        return slash > 0 ? virtualPath.substring(0, slash) : "";
    }

    public static final class RoomEntry {
        public final String name;
        public final int userCount;

        public RoomEntry(String name, int userCount) {
            this.name = name != null ? name : "";
            this.userCount = userCount;
        }
    }

    static byte[] packJoinRoom(String room) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(packString(room));
        bos.write(packUInt32(0));
        return bos.toByteArray();
    }

    static byte[] packLeaveRoom(String room) throws IOException {
        return packString(room);
    }

    static byte[] packSayChatroom(String room, String message) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(packString(room));
        bos.write(packString(message));
        return bos.toByteArray();
    }

    static List<RoomEntry> parseRoomList(byte[] body) {
        List<RoomEntry> out = new ArrayList<RoomEntry>();
        if (body == null || body.length == 0) return out;
        try {
            Reader r = new Reader(body);
            appendRoomBlock(out, r, true);
            if (r.hasRemaining()) appendRoomBlock(out, r, true);
            if (r.hasRemaining()) appendRoomBlock(out, r, true);
            if (r.hasRemaining()) {
                int n = r.readUInt32();
                for (int i = 0; i < n && i < 10000; i++) {
                    String name = r.readString();
                    if (name != null && !name.isEmpty()) {
                        out.add(new RoomEntry(name, 0));
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void appendRoomBlock(List<RoomEntry> out, Reader r, boolean withCounts)
            throws IOException {
        int n = r.readUInt32();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) names[i] = r.readString();
        if (!withCounts) {
            for (String name : names) {
                if (name != null && !name.isEmpty()) out.add(new RoomEntry(name, 0));
            }
            return;
        }
        int nc = r.readUInt32();
        for (int i = 0; i < n && i < nc; i++) {
            int users = r.readUInt32();
            if (names[i] != null && !names[i].isEmpty()) {
                out.add(new RoomEntry(names[i], users));
            }
        }
    }

    public static final class RoomTickerEntry {
        public final String username;
        public final String text;

        public RoomTickerEntry(String username, String text) {
            this.username = username != null ? username : "";
            this.text = text != null ? text : "";
        }
    }

    public static final class RoomTickersMessage {
        public final String room;
        public final List<RoomTickerEntry> tickers;

        RoomTickersMessage(String room, List<RoomTickerEntry> tickers) {
            this.room = room != null ? room : "";
            this.tickers = tickers != null ? tickers : new ArrayList<RoomTickerEntry>();
        }
    }

    static byte[] packSetRoomTicker(String room, String ticker) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(packString(room));
        bos.write(packString(ticker != null ? ticker : ""));
        return bos.toByteArray();
    }

    static RoomTickersMessage parseRoomTickers(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            Reader r = new Reader(body);
            String room = r.readString();
            int n = r.readUInt32();
            List<RoomTickerEntry> tickers = new ArrayList<RoomTickerEntry>();
            for (int i = 0; i < n && i < 10000; i++) {
                String user = r.readString();
                String msg = r.readString();
                if (user != null && !user.isEmpty() && msg != null && !msg.isEmpty()) {
                    tickers.add(new RoomTickerEntry(user, msg));
                }
            }
            return new RoomTickersMessage(room, tickers);
        } catch (Exception e) {
            return null;
        }
    }

    static RoomTickerEntry parseRoomTickerAdded(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            Reader r = new Reader(body);
            r.readString(); // room
            String user = r.readString();
            String msg = r.readString();
            if (user == null || user.isEmpty()) return null;
            return new RoomTickerEntry(user, msg != null ? msg : "");
        } catch (Exception e) {
            return null;
        }
    }

    static String parseRoomTickerAddedRoom(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            Reader r = new Reader(body);
            return r.readString();
        } catch (Exception e) {
            return "";
        }
    }

    static String parseRoomTickerRemovedRoom(byte[] body) {
        return parseRoomTickerAddedRoom(body);
    }

    static String parseRoomTickerRemovedUser(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            Reader r = new Reader(body);
            r.readString();
            return r.readString();
        } catch (Exception e) {
            return "";
        }
    }
}
