package com.solar.launcher.soulseek.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import com.solar.launcher.soulseek.ReachIntroMessage;
import com.solar.launcher.soulseek.SolarDeveloperAccounts;
import com.solar.launcher.soulseek.SoulseekChatRooms;
import com.solar.launcher.soulseek.SoulseekMessaging;
import com.solar.launcher.soulseek.SoulseekWire;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** SQLite store for Reach rooms, messages, and peer cache. */
public class ReachDatabase extends SolarDbHelper {
    private static final String DB_NAME = "reach.db";
    private static final int DB_VERSION = 3;
    private static final String PREF_MIGRATED = "reach_db_migrated_v1";
    private static final int MAX_ROOM_MESSAGES = 200;
    private static final int MAX_PM_MESSAGES = 500;
    private static final long PEER_CACHE_TTL_MS = 5L * 60L * 1000L;

    private static ReachDatabase instance;

    private ReachDatabase(Context ctx) {
        this(ctx, true);
    }

    private ReachDatabase(Context ctx, boolean walEnabled) {
        super(ctx.getApplicationContext(), DB_NAME, DB_VERSION, walEnabled);
    }

    public static synchronized ReachDatabase getInstance(Context ctx) {
        if (instance == null) {
            instance = new ReachDatabase(ctx.getApplicationContext());
        }
        return instance;
    }

    static void resetInstanceForTest() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /** In-memory database for unit tests. */
    public static ReachDatabase openForTest(Context ctx) {
        resetInstanceForTest();
        final SQLiteDatabase[] mem = new SQLiteDatabase[1];
        instance = new ReachDatabase(ctx.getApplicationContext(), false) {
            @Override
            public synchronized SQLiteDatabase getWritableDatabase() {
                if (mem[0] == null) {
                    mem[0] = SQLiteDatabase.create(null);
                    onCreate(mem[0]);
                }
                return mem[0];
            }

            @Override
            public synchronized SQLiteDatabase getReadableDatabase() {
                return getWritableDatabase();
            }
        };
        return instance;
    }

    @Override
    public void onCreate(SolarDatabase db) {
        db.execSQL("CREATE TABLE rooms (name TEXT PRIMARY KEY COLLATE NOCASE,"
                + " user_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_rooms_name ON rooms(name COLLATE NOCASE)");
        db.execSQL("CREATE TABLE room_messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " room TEXT NOT NULL, sender TEXT, text TEXT, ts INTEGER NOT NULL DEFAULT 0,"
                + " incoming INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE INDEX idx_room_messages_room_ts ON room_messages(room, ts)");
        db.execSQL("CREATE TABLE pm_messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " peer TEXT NOT NULL, text TEXT, ts INTEGER NOT NULL DEFAULT 0,"
                + " incoming INTEGER NOT NULL DEFAULT 1, msg_id INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_pm_messages_peer_ts ON pm_messages(peer, ts)");
        db.execSQL("CREATE TABLE peer_cache (username TEXT PRIMARY KEY COLLATE NOCASE,"
                + " country TEXT, files INTEGER NOT NULL DEFAULT 0,"
                + " online INTEGER NOT NULL DEFAULT 0, fetched_at INTEGER NOT NULL DEFAULT 0)");
        createPeerNotesTable(db);
        createRoomTickersTable(db);
    }

    private static void createRoomTickersTable(SolarDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_tickers (room TEXT NOT NULL,"
                + " username TEXT NOT NULL, text TEXT NOT NULL DEFAULT '',"
                + " updated_at INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY(room, username))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_room_tickers_room ON room_tickers(room)");
    }

    private static void createPeerNotesTable(SolarDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS peer_notes (username TEXT PRIMARY KEY COLLATE NOCASE,"
                + " note TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createPeerNotesTable(db);
        }
        if (oldVersion < 3) {
            createRoomTickersTable(db);
        }
    }

    public void ensureMigrated(final SharedPreferences prefs) {
        if (prefs == null || prefs.getBoolean(PREF_MIGRATED, false)) return;
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                if (prefs.getBoolean(PREF_MIGRATED, false)) return;
                migrateFromPrefs(prefs);
                prefs.edit().putBoolean(PREF_MIGRATED, true).commit();
            }
        });
    }

    private void migrateFromPrefs(SharedPreferences prefs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (SoulseekWire.RoomEntry e : SoulseekChatRooms.loadRoomListLegacy(prefs)) {
                db.execSQL("INSERT OR REPLACE INTO rooms(name,user_count,updated_at) VALUES(?,?,?)",
                        new Object[] { e.name, e.userCount, System.currentTimeMillis() });
            }
            migrateRoomMessages(prefs, db);
            migratePmMessages(prefs, db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void migrateRoomMessages(SharedPreferences prefs, SQLiteDatabase db) {
        try {
            JSONObject root = new JSONObject(prefs.getString("soulseek_room_messages", "{}"));
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String room = names.getString(i);
                JSONArray arr = root.optJSONArray(room);
                if (arr == null) continue;
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject o = arr.getJSONObject(j);
                    db.execSQL("INSERT INTO room_messages(room,sender,text,ts,incoming)"
                                    + " VALUES(?,?,?,?,?)",
                            new Object[] {
                                    room,
                                    o.optString("sender", ""),
                                    o.optString("text", ""),
                                    o.optInt("ts", 0),
                                    o.optBoolean("in", true) ? 1 : 0
                            });
                }
            }
        } catch (Exception ignored) {}
    }

    private void migratePmMessages(SharedPreferences prefs, SQLiteDatabase db) {
        for (SoulseekMessaging.Message m : SoulseekMessaging.loadLegacy(prefs)) {
            db.execSQL("INSERT INTO pm_messages(peer,text,ts,incoming,msg_id) VALUES(?,?,?,?,?)",
                    new Object[] { m.peer, m.text, m.timestamp, m.incoming ? 1 : 0, m.id });
        }
    }

    // --- Rooms ---

    public void replaceRooms(final List<SoulseekWire.RoomEntry> rooms) {
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                long now = System.currentTimeMillis();
                db.beginTransaction();
                try {
                    db.delete("rooms", null, null);
                    if (rooms != null) {
                        for (SoulseekWire.RoomEntry e : rooms) {
                            if (e.name == null || e.name.isEmpty()) continue;
                            db.execSQL("INSERT OR REPLACE INTO rooms(name,user_count,updated_at)"
                                            + " VALUES(?,?,?)",
                                    new Object[] { e.name, e.userCount, now });
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        });
    }

    public List<SoulseekWire.RoomEntry> loadRoomsSync() {
        final List<SoulseekWire.RoomEntry> out = new ArrayList<SoulseekWire.RoomEntry>();
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery("SELECT name,user_count FROM rooms ORDER BY name COLLATE NOCASE",
                        null);
                try {
                    while (c.moveToNext()) {
                        out.add(new SoulseekWire.RoomEntry(
                                c.getString(0), c.getInt(1)));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    public void loadRoomsAsync(final Callback<List<SoulseekWire.RoomEntry>> cb) {
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                final List<SoulseekWire.RoomEntry> list = loadRoomsSync();
                if (cb != null) cb.onResult(list);
            }
        });
    }

    // --- Room messages ---

    public void appendRoomMessage(final SoulseekChatRooms.RoomMessage msg) {
        if (msg == null || msg.room == null || msg.room.isEmpty()) return;
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("INSERT INTO room_messages(room,sender,text,ts,incoming)"
                                + " VALUES(?,?,?,?,?)",
                        new Object[] {
                                msg.room, msg.sender, msg.text, msg.timestamp,
                                msg.statusEvent ? 2 : (msg.incoming ? 1 : 0)
                        });
                trimRoomMessages(db, msg.room, MAX_ROOM_MESSAGES);
            }
        });
    }

    private void trimRoomMessages(SQLiteDatabase db, String room, int max) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM room_messages WHERE room=?",
                new String[] { room });
        try {
            if (c.moveToFirst() && c.getInt(0) > max) {
                int excess = c.getInt(0) - max;
                db.execSQL("DELETE FROM room_messages WHERE id IN ("
                        + "SELECT id FROM room_messages WHERE room=? ORDER BY ts ASC LIMIT ?)",
                        new Object[] { room, excess });
            }
        } finally {
            c.close();
        }
    }

    public List<SoulseekChatRooms.RoomMessage> messagesForRoomSync(String room) {
        final List<SoulseekChatRooms.RoomMessage> out = new ArrayList<SoulseekChatRooms.RoomMessage>();
        if (room == null || room.isEmpty()) return out;
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT sender,text,ts,incoming FROM room_messages WHERE room=?"
                                + " ORDER BY ts ASC", new String[] { room });
                try {
                    while (c.moveToNext()) {
                        int inc = c.getInt(3);
                        boolean status = inc == 2;
                        out.add(new SoulseekChatRooms.RoomMessage(
                                room,
                                c.getString(0),
                                c.getString(1),
                                c.getInt(2),
                                status || inc == 1,
                                status));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    // --- Room wall tickers ---

    public void replaceRoomTickers(final String room, final List<SoulseekWire.RoomTickerEntry> tickers) {
        if (room == null || room.isEmpty()) return;
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("DELETE FROM room_tickers WHERE room=?", new Object[] { room });
                int ts = (int) (System.currentTimeMillis() / 1000L);
                if (tickers == null) return;
                for (SoulseekWire.RoomTickerEntry t : tickers) {
                    if (t == null || t.username.isEmpty() || t.text.isEmpty()) continue;
                    db.execSQL("INSERT OR REPLACE INTO room_tickers(room,username,text,updated_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] { room, t.username, t.text, ts });
                }
            }
        });
    }

    public void upsertRoomTicker(final String room, final String username, final String text) {
        if (room == null || room.isEmpty() || username == null || username.isEmpty()) return;
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                int ts = (int) (System.currentTimeMillis() / 1000L);
                if (text == null || text.isEmpty()) {
                    db.execSQL("DELETE FROM room_tickers WHERE room=? AND username=?",
                            new Object[] { room, username });
                } else {
                    db.execSQL("INSERT OR REPLACE INTO room_tickers(room,username,text,updated_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] { room, username, text, ts });
                }
            }
        });
    }

    public void removeRoomTicker(final String room, final String username) {
        if (room == null || room.isEmpty() || username == null || username.isEmpty()) return;
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                getWritableDatabase().execSQL(
                        "DELETE FROM room_tickers WHERE room=? AND username=?",
                        new Object[] { room, username });
            }
        });
    }

    public List<SoulseekWire.RoomTickerEntry> tickersForRoomSync(String room) {
        final List<SoulseekWire.RoomTickerEntry> out = new ArrayList<SoulseekWire.RoomTickerEntry>();
        if (room == null || room.isEmpty()) return out;
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT username,text FROM room_tickers WHERE room=? ORDER BY username COLLATE NOCASE",
                        new String[] { room });
                try {
                    while (c.moveToNext()) {
                        out.add(new SoulseekWire.RoomTickerEntry(c.getString(0), c.getString(1)));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    // --- PM messages ---

    public void appendPmMessage(final SoulseekMessaging.Message msg) {
        if (msg == null) return;
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("INSERT INTO pm_messages(peer,text,ts,incoming,msg_id)"
                                + " VALUES(?,?,?,?,?)",
                        new Object[] {
                                msg.peer, msg.text, msg.timestamp,
                                msg.incoming ? 1 : 0, msg.id
                        });
                Cursor c = db.rawQuery("SELECT COUNT(*) FROM pm_messages", null);
                try {
                    if (c.moveToFirst() && c.getInt(0) > MAX_PM_MESSAGES) {
                        int excess = c.getInt(0) - MAX_PM_MESSAGES;
                        db.execSQL("DELETE FROM pm_messages WHERE id IN ("
                                + "SELECT id FROM pm_messages ORDER BY ts ASC LIMIT ?)",
                                new Object[] { excess });
                    }
                } finally {
                    c.close();
                }
            }
        });
    }

    public List<SoulseekMessaging.Message> loadPmMessagesSync() {
        final List<SoulseekMessaging.Message> out = new ArrayList<SoulseekMessaging.Message>();
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT msg_id,ts,peer,text,incoming FROM pm_messages ORDER BY ts ASC",
                        null);
                try {
                    while (c.moveToNext()) {
                        out.add(new SoulseekMessaging.Message(
                                c.getInt(0), c.getInt(1), c.getString(2),
                                c.getString(3), c.getInt(4) != 0));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    public static final class InboxRow {
        public final String peer;
        public final String text;
        public final int timestamp;

        public InboxRow(String peer, String text, int timestamp) {
            this.peer = peer != null ? peer : "";
            this.text = text != null ? text : "";
            this.timestamp = timestamp;
        }
    }

    /** Latest message per peer, newest conversations first. DB thread via runSync. */
    public List<InboxRow> loadInboxSync() {
        final List<InboxRow> out = new ArrayList<InboxRow>();
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT peer, text, ts FROM pm_messages"
                                + " WHERE rowid IN (SELECT MAX(rowid) FROM pm_messages"
                                + " GROUP BY peer COLLATE NOCASE)"
                                + " ORDER BY ts DESC",
                        null);
                try {
                    while (c.moveToNext()) {
                        String peer = c.getString(0);
                        if (peer == null || peer.isEmpty()) continue;
                        if (SolarDeveloperAccounts.hideFromReachUi(peer)) continue;
                        String text = c.getString(1);
                        int ts = c.getInt(2);
                        if (ReachIntroMessage.isIntro(text)
                                || SolarDeveloperAccounts.isAutoDiagnosticText(text)) {
                            SoulseekMessaging.Message visible = lastVisibleMessageForPeerSync(peer);
                            if (visible == null) continue;
                            text = visible.text;
                            ts = visible.timestamp;
                        }
                        out.add(new InboxRow(peer, text, ts));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    public List<String> conversationPeersSync() {
        List<InboxRow> rows = loadInboxSync();
        List<String> peers = new ArrayList<String>(rows.size());
        for (InboxRow row : rows) {
            peers.add(row.peer);
        }
        return peers;
    }

    public SoulseekMessaging.Message lastMessageForPeerSync(final String peer) {
        if (peer == null || peer.isEmpty()) return null;
        final SoulseekMessaging.Message[] holder = new SoulseekMessaging.Message[1];
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT msg_id,ts,peer,text,incoming FROM pm_messages"
                                + " WHERE peer=? COLLATE NOCASE ORDER BY ts DESC, id DESC LIMIT 1",
                        new String[] { peer });
                try {
                    if (c.moveToFirst()) {
                        holder[0] = new SoulseekMessaging.Message(
                                c.getInt(0), c.getInt(1), c.getString(2),
                                c.getString(3), c.getInt(4) != 0);
                    }
                } finally {
                    c.close();
                }
            }
        });
        return holder[0];
    }

    /** Last PM row visible in Reach browse UI — skips auto-intro lines. */
    public SoulseekMessaging.Message lastVisibleMessageForPeerSync(final String peer) {
        if (peer == null || peer.isEmpty()) return null;
        final SoulseekMessaging.Message[] holder = new SoulseekMessaging.Message[1];
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT msg_id,ts,peer,text,incoming FROM pm_messages"
                                + " WHERE peer=? COLLATE NOCASE ORDER BY ts DESC, id DESC LIMIT 32",
                        new String[] { peer });
                try {
                    while (c.moveToNext()) {
                        String text = c.getString(3);
                        if (ReachIntroMessage.isIntro(text)) continue;
                        if (SolarDeveloperAccounts.isAutoDiagnosticText(text)) continue;
                        holder[0] = new SoulseekMessaging.Message(
                                c.getInt(0), c.getInt(1), c.getString(2),
                                text, c.getInt(4) != 0);
                        break;
                    }
                } finally {
                    c.close();
                }
            }
        });
        return holder[0];
    }

    /** Delete entire PM thread for one peer. */
    public void deletePmThreadSync(final String peer) {
        if (peer == null || peer.trim().isEmpty()) return;
        final String key = peer.trim();
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.delete("pm_messages", "peer=? COLLATE NOCASE", new String[] { key });
            }
        });
    }

    public List<SoulseekMessaging.Message> threadSync(String peer) {
        final List<SoulseekMessaging.Message> out = new ArrayList<SoulseekMessaging.Message>();
        if (peer == null || peer.isEmpty()) return out;
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT msg_id,ts,peer,text,incoming FROM pm_messages"
                                + " WHERE peer=? COLLATE NOCASE ORDER BY ts ASC",
                        new String[] { peer });
                try {
                    while (c.moveToNext()) {
                        out.add(new SoulseekMessaging.Message(
                                c.getInt(0), c.getInt(1), c.getString(2),
                                c.getString(3), c.getInt(4) != 0));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    public List<SoulseekWire.RoomEntry> searchRoomsSync(String query, int limit, int offset) {
        final List<SoulseekWire.RoomEntry> out = new ArrayList<SoulseekWire.RoomEntry>();
        if (query == null || query.trim().isEmpty()) return out;
        final String q = query.trim();
        final int lim = limit > 0 ? limit : 40;
        final int off = Math.max(0, offset);
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT name, user_count FROM rooms"
                                + " WHERE name LIKE ? COLLATE NOCASE"
                                + " ORDER BY user_count DESC, name COLLATE NOCASE"
                                + " LIMIT ? OFFSET ?",
                        new String[] { "%" + q + "%", String.valueOf(lim), String.valueOf(off) });
                try {
                    while (c.moveToNext()) {
                        out.add(new SoulseekWire.RoomEntry(c.getString(0), c.getInt(1)));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    // --- Peer notes ---

    /** All saved peer notes — load once per inbox refresh (avoid main-thread getView DB hits). */
    public java.util.HashMap<String, String> loadAllPeerNotesSync() {
        final java.util.HashMap<String, String> out = new java.util.HashMap<String, String>();
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery("SELECT username, note FROM peer_notes", null);
                try {
                    while (c.moveToNext()) {
                        String user = c.getString(0);
                        String note = c.getString(1);
                        if (user != null && note != null && !note.trim().isEmpty()) {
                            out.put(user.toLowerCase(Locale.US), note);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        });
        return out;
    }

    public String getPeerNoteSync(String username) {
        if (username == null || username.trim().isEmpty()) return "";
        final String key = username.trim().toLowerCase(Locale.US);
        final String[] holder = new String[] { "" };
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT note FROM peer_notes WHERE username=?",
                        new String[] { key });
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        holder[0] = n != null ? n : "";
                    }
                } finally {
                    c.close();
                }
            }
        });
        return holder[0];
    }

    public void setPeerNoteSync(String username, String note) {
        if (username == null || username.trim().isEmpty()) return;
        final String key = username.trim().toLowerCase(Locale.US);
        final String text = note != null ? note.trim() : "";
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("INSERT OR REPLACE INTO peer_notes(username,note,updated_at)"
                                + " VALUES(?,?,?)",
                        new Object[] { key, text, System.currentTimeMillis() });
            }
        });
    }

    public void clearPeerNoteSync(String username) {
        if (username == null || username.trim().isEmpty()) return;
        final String key = username.trim().toLowerCase(Locale.US);
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.delete("peer_notes", "username=?", new String[] { key });
            }
        });
    }

    // --- Peer cache ---

    public static final class PeerCacheEntry {
        public final String username;
        public final String country;
        public final int files;
        public final boolean online;
        public final long fetchedAt;

        PeerCacheEntry(String username, String country, int files, boolean online, long fetchedAt) {
            this.username = username;
            this.country = country;
            this.files = files;
            this.online = online;
            this.fetchedAt = fetchedAt;
        }

        public boolean isFresh() {
            return System.currentTimeMillis() - fetchedAt < PEER_CACHE_TTL_MS;
        }
    }

    public PeerCacheEntry getPeerCacheSync(String username) {
        if (username == null || username.isEmpty()) return null;
        final String key = username.trim().toLowerCase(Locale.US);
        final PeerCacheEntry[] holder = new PeerCacheEntry[1];
        ReachDbExecutor.runSync(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getReadableDatabase();
                Cursor c = db.rawQuery(
                        "SELECT country,files,online,fetched_at FROM peer_cache WHERE username=?",
                        new String[] { key });
                try {
                    if (c.moveToFirst()) {
                        holder[0] = new PeerCacheEntry(
                                key,
                                c.getString(0),
                                c.getInt(1),
                                c.getInt(2) != 0,
                                c.getLong(3));
                    }
                } finally {
                    c.close();
                }
            }
        });
        return holder[0];
    }

    public void putPeerCache(final String username, final String country,
            final int files, final boolean online) {
        if (username == null || username.isEmpty()) return;
        final String key = username.trim().toLowerCase(Locale.US);
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("INSERT OR REPLACE INTO peer_cache"
                                + "(username,country,files,online,fetched_at) VALUES(?,?,?,?,?)",
                        new Object[] {
                                key,
                                country != null ? country : "",
                                files,
                                online ? 1 : 0,
                                System.currentTimeMillis()
                        });
            }
        });
    }

    public interface Callback<T> {
        void onResult(T result);
    }
}
