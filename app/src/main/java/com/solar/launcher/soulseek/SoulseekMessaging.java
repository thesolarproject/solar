package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.soulseek.store.ReachDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists Reach private-message history in SQLite. */
public final class SoulseekMessaging {
    private static final String PREF_INBOX = "soulseek_pm_inbox";

    public static final class Message {
        public final int id;
        public final int timestamp;
        public final String peer;
        public final String text;
        public final boolean incoming;
        /** Join/leave line when bridged from {@link SoulseekChatRooms.RoomMessage}. */
        public final boolean statusEvent;

        public Message(int id, int timestamp, String peer, String text, boolean incoming) {
            this(id, timestamp, peer, text, incoming, false);
        }

        public Message(int id, int timestamp, String peer, String text, boolean incoming,
                boolean statusEvent) {
            this.id = id;
            this.timestamp = timestamp;
            this.peer = peer;
            this.text = text;
            this.incoming = incoming;
            this.statusEvent = statusEvent;
        }
    }

    private SoulseekMessaging() {}

    public static void append(Context ctx, SharedPreferences prefs, Message msg) {
        if (ctx == null || msg == null) return;
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        ReachDatabase.getInstance(ctx).appendPmMessage(msg);
    }

    public static List<Message> load(Context ctx, SharedPreferences prefs) {
        if (ctx == null) return Collections.emptyList();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).loadPmMessagesSync();
    }

    /** Legacy prefs read for one-time migration only. */
    public static List<Message> loadLegacy(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptyList();
        String raw = prefs.getString(PREF_INBOX, "[]");
        List<Message> out = new ArrayList<Message>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Message(
                        o.optInt("id", 0),
                        o.optInt("ts", 0),
                        o.optString("peer", ""),
                        o.optString("text", ""),
                        o.optBoolean("in", true)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<String> conversationPeers(Context ctx, SharedPreferences prefs) {
        if (ctx == null) return Collections.emptyList();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).conversationPeersSync();
    }

    public static List<ReachDatabase.InboxRow> loadInbox(Context ctx, SharedPreferences prefs) {
        if (ctx == null) return Collections.emptyList();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).loadInboxSync();
    }

    public static List<Message> thread(Context ctx, SharedPreferences prefs, String peer) {
        if (ctx == null) return Collections.emptyList();
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).threadSync(peer);
    }

    public static Message lastMessageForPeer(Context ctx, SharedPreferences prefs, String peer) {
        if (ctx == null || peer == null || peer.isEmpty()) return null;
        ReachDatabase.getInstance(ctx).ensureMigrated(prefs);
        return ReachDatabase.getInstance(ctx).lastMessageForPeerSync(peer);
    }

    public static String formatTimestamp(int unixSeconds) {
        if (unixSeconds <= 0) return "";
        try {
            java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
            return fmt.format(new java.util.Date((long) unixSeconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }
}
