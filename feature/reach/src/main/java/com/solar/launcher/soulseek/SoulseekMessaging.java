package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Prefs-backed private message history for Reach. */
public final class SoulseekMessaging {
    private static final String PREF_INBOX = "soulseek_pm_inbox_v2";

    public static final class Message {
        public final int id;
        public final int timestamp;
        public final String peer;
        public final String text;
        public final boolean incoming;

        public Message(int id, int timestamp, String peer, String text, boolean incoming) {
            this.id = id;
            this.timestamp = timestamp;
            this.peer = peer != null ? peer : "";
            this.text = text != null ? text : "";
            this.incoming = incoming;
        }
    }

    public static final class InboxRow {
        public final String peer;
        public final String preview;
        public final int timestamp;

        public InboxRow(String peer, String preview, int timestamp) {
            this.peer = peer != null ? peer : "";
            this.preview = preview != null ? preview : "";
            this.timestamp = timestamp;
        }
    }

    private SoulseekMessaging() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
    }

    public static void append(Context ctx, Message msg) {
        if (ctx == null || msg == null) return;
        List<Message> all = loadAll(ctx);
        all.add(msg);
        // Cap total history for Y1 RAM
        while (all.size() > 2000) all.remove(0);
        saveAll(ctx, all);
    }

    public static List<Message> thread(Context ctx, String peer) {
        if (ctx == null || peer == null) return Collections.emptyList();
        String key = peer.trim();
        List<Message> out = new ArrayList<Message>();
        for (Message m : loadAll(ctx)) {
            if (m.peer.equalsIgnoreCase(key)) out.add(m);
        }
        return out;
    }

    public static Message lastMessageForPeer(Context ctx, String peer) {
        List<Message> t = thread(ctx, peer);
        if (t.isEmpty()) return null;
        return t.get(t.size() - 1);
    }

    public static List<InboxRow> loadInbox(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        List<Message> all = loadAll(ctx);
        // peer → last message index
        java.util.LinkedHashMap<String, Message> last = new java.util.LinkedHashMap<String, Message>();
        for (Message m : all) {
            if (m.peer.isEmpty()) continue;
            if (SolarDeveloperAccounts.isDiagHandle(m.peer)) continue;
            if (SolarDeveloperAccounts.isAutoDiagnosticText(m.text)) continue;
            String k = m.peer;
            // normalize developer peers
            if (SolarDeveloperAccounts.isDeveloper(k)
                    || SolarDeveloperAccounts.isLegacyVirtualPeer(k)) {
                k = SolarDeveloperAccounts.SOLAR_DEVELOPER;
            }
            last.put(k.toLowerCase(Locale.US), new Message(m.id, m.timestamp, k, m.text, m.incoming));
        }
        List<InboxRow> rows = new ArrayList<InboxRow>();
        for (Message m : last.values()) {
            String preview = SolarDeveloperAccounts.stripDiagnosticText(m.text);
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
            rows.add(new InboxRow(m.peer, preview, m.timestamp));
        }
        // newest first
        Collections.sort(rows, new java.util.Comparator<InboxRow>() {
            @Override
            public int compare(InboxRow a, InboxRow b) {
                return b.timestamp - a.timestamp;
            }
        });
        return rows;
    }

    public static List<String> conversationPeers(Context ctx) {
        Set<String> peers = new LinkedHashSet<String>();
        for (InboxRow r : loadInbox(ctx)) peers.add(r.peer);
        return new ArrayList<String>(peers);
    }

    public static void deleteConversation(Context ctx, String peer) {
        if (ctx == null || peer == null) return;
        String key = peer.trim();
        List<Message> all = loadAll(ctx);
        List<Message> kept = new ArrayList<Message>();
        for (Message m : all) {
            String p = m.peer;
            if (SolarDeveloperAccounts.isDeveloper(p)
                    || SolarDeveloperAccounts.isLegacyVirtualPeer(p)) {
                p = SolarDeveloperAccounts.SOLAR_DEVELOPER;
            }
            if (!p.equalsIgnoreCase(key)) kept.add(m);
        }
        saveAll(ctx, kept);
    }

    /** One-time remap of legacy virtual / wire peer keys into SolarDeveloper. */
    public static void migrateDeveloperPeers(Context ctx) {
        if (ctx == null) return;
        List<Message> all = loadAll(ctx);
        boolean changed = false;
        List<Message> out = new ArrayList<Message>();
        for (Message m : all) {
            String peer = m.peer;
            if (SolarDeveloperAccounts.isDeveloper(peer)
                    || SolarDeveloperAccounts.isLegacyVirtualPeer(peer)
                    || SolarDeveloperAccounts.isDiagHandle(peer)) {
                peer = SolarDeveloperAccounts.SOLAR_DEVELOPER;
                if (!peer.equals(m.peer)) changed = true;
            }
            out.add(new Message(m.id, m.timestamp, peer, m.text, m.incoming));
        }
        if (changed) saveAll(ctx, out);
    }

    public static void updateWelcomeTimestamp(Context ctx, int newTs) {
        if (ctx == null || newTs <= 0) return;
        List<Message> all = loadAll(ctx);
        String welcome = SolarDeveloperAccounts.welcomeMessageBody();
        boolean changed = false;
        List<Message> out = new ArrayList<Message>();
        for (Message m : all) {
            if (m.incoming
                    && SolarDeveloperAccounts.SOLAR_DEVELOPER.equalsIgnoreCase(m.peer)
                    && welcome.equals(m.text)
                    && m.timestamp < 946684800) {
                out.add(new Message(m.id, newTs, m.peer, m.text, true));
                changed = true;
            } else {
                out.add(m);
            }
        }
        if (changed) saveAll(ctx, out);
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

    private static List<Message> loadAll(Context ctx) {
        String raw = prefs(ctx).getString(PREF_INBOX, "[]");
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

    private static void saveAll(Context ctx, List<Message> messages) {
        JSONArray arr = new JSONArray();
        try {
            for (Message m : messages) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("ts", m.timestamp);
                o.put("peer", m.peer);
                o.put("text", m.text);
                o.put("in", m.incoming);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        prefs(ctx).edit().putString(PREF_INBOX, arr.toString()).commit();
    }
}
