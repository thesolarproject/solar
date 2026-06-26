package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.soulseek.store.ReachDatabase;
import com.solar.launcher.soulseek.store.ReachDbExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists Soulseek chat room list cache and per-room message history (SQLite). */
public final class SoulseekChatRooms {
    private static final String PREF_LIST = "soulseek_room_list";
    private static final String PREF_MESSAGES = "soulseek_room_messages";

    public static final class RoomMessage {
        public final String room;
        public final String sender;
        public final String text;
        public final int timestamp;
        public final boolean incoming;
        /** Join/leave/status line — rendered muted, no sender flag. */
        public final boolean statusEvent;

        public RoomMessage(String room, String sender, String text, int timestamp, boolean incoming) {
            this(room, sender, text, timestamp, incoming, false);
        }

        public RoomMessage(String room, String sender, String text, int timestamp,
                boolean incoming, boolean statusEvent) {
            this.room = room != null ? room : "";
            this.sender = sender != null ? sender : "";
            this.text = text != null ? text : "";
            this.timestamp = timestamp;
            this.incoming = incoming;
            this.statusEvent = statusEvent;
        }
    }

    private SoulseekChatRooms() {}

    public static void saveRoomList(Context ctx, SharedPreferences prefs,
            List<SoulseekWire.RoomEntry> rooms) {
        if (ctx == null) return;
        if (prefs != null) ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        ReachDatabase.getInstance(ctx).replaceRooms(rooms);
    }

    public static List<SoulseekWire.RoomEntry> loadRoomList(Context ctx, SharedPreferences prefs) {
        if (ctx == null) return Collections.emptyList();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).loadRoomsSync();
    }

    public static void loadRoomListAsync(final Context ctx, final SharedPreferences prefs,
            final ReachDatabase.Callback<java.util.List<SoulseekWire.RoomEntry>> cb) {
        if (ctx == null) {
            if (cb != null) cb.onResult(Collections.emptyList());
            return;
        }
        ReachDbExecutor.run(new Runnable() {
            @Override
            public void run() {
                java.util.List<SoulseekWire.RoomEntry> list = Collections.emptyList();
                try {
                    ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
                    list = ReachDatabase.getInstance(ctx).loadRoomsSync();
                } catch (Exception e) {
                    // logged by caller
                }
                final java.util.List<SoulseekWire.RoomEntry> result = list;
                if (cb == null) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(result);
                    }
                });
            }
        });
    }

    /** Legacy prefs read for one-time migration only. */
    public static List<SoulseekWire.RoomEntry> loadRoomListLegacy(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptyList();
        String raw = prefs.getString(PREF_LIST, "[]");
        List<SoulseekWire.RoomEntry> out = new ArrayList<SoulseekWire.RoomEntry>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new SoulseekWire.RoomEntry(o.optString("name", ""),
                        o.optInt("users", 0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void appendMessage(Context ctx, SharedPreferences prefs, RoomMessage msg) {
        if (ctx == null || msg == null) return;
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        ReachDatabase.getInstance(ctx).appendRoomMessage(msg);
    }

    public static List<RoomMessage> messagesForRoom(Context ctx, SharedPreferences prefs, String room) {
        if (ctx == null) return new ArrayList<RoomMessage>();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).messagesForRoomSync(room);
    }

    public static String formatTimestamp(int unixSeconds) {
        if (unixSeconds <= 0) return "";
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MMM d HH:mm",
                    java.util.Locale.getDefault());
            return fmt.format(new java.util.Date(unixSeconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    public static String formatStatusTimestamp(int unixSeconds) {
        if (unixSeconds <= 0) return "";
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.US);
            return fmt.format(new java.util.Date(unixSeconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    public static void appendRoomStatus(Context ctx, SharedPreferences prefs, String room,
            String username, boolean joined) {
        if (ctx == null || room == null || username == null) return;
        int ts = (int) (System.currentTimeMillis() / 1000L);
        String line = formatStatusTimestamp(ts) + " " + username
                + (joined ? " has joined the room" : " has left the room");
        appendMessage(ctx, prefs, new RoomMessage(room, "", line, ts, true, true));
    }

    /** Bridge room history into the shared PM conversation pipeline. */
    public static List<SoulseekMessaging.Message> toThreadMessages(List<RoomMessage> roomMessages) {
        List<SoulseekMessaging.Message> out = new ArrayList<SoulseekMessaging.Message>();
        if (roomMessages == null) return out;
        for (int i = 0; i < roomMessages.size(); i++) {
            RoomMessage rm = roomMessages.get(i);
            if (rm == null) continue;
            out.add(new SoulseekMessaging.Message(i, rm.timestamp,
                    rm.sender != null ? rm.sender : "",
                    rm.text != null ? rm.text : "",
                    rm.incoming, rm.statusEvent));
        }
        return out;
    }
}
