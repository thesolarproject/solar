package com.solar.launcher.soulseek;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Reach private-message wire formats (replies, reactions) for display. */
public final class ReachMessageFormat {
  private static final Pattern REPLY =
      Pattern.compile("^Reply to \"(.+)\":\\s*(.+)$", Pattern.DOTALL);
  private static final Pattern ROOM_REPLY =
      Pattern.compile("^Reply to @(.+?) \"(.+)\":\\s*(.+)$", Pattern.DOTALL);
  private static final Pattern REACT_WIRE =
      Pattern.compile("^reacted to \"(.+)\" with (.+)$", Pattern.DOTALL);
  private static final Pattern REACT_DISPLAY =
      Pattern.compile("^(.+?) reacted: (.+)$", Pattern.DOTALL);

  public static final class Reply {
    public final String quote;
    public final String body;
    public final String quoteAuthor;

    Reply(String quote, String body) {
      this(quote, body, null);
    }

    Reply(String quote, String body, String quoteAuthor) {
      this.quote = quote;
      this.body = body;
      this.quoteAuthor = quoteAuthor;
    }
  }

  public static final class ReactionWire {
    public final String quote;
    public final String emoji;

    ReactionWire(String quote, String emoji) {
      this.quote = quote;
      this.emoji = emoji;
    }
  }

  public static final class ReactionDisplay {
    public final String username;
    public final String emoji;

    ReactionDisplay(String username, String emoji) {
      this.username = username;
      this.emoji = emoji;
    }
  }

  private ReachMessageFormat() {}

  public static Reply parseReply(String text) {
    if (text == null) return null;
    String t = ReachIntroMessage.strip(text.trim());
    Matcher room = ROOM_REPLY.matcher(t);
    if (room.matches()) {
      return new Reply(room.group(2).trim(), room.group(3).trim(), room.group(1).trim());
    }
    Matcher m = REPLY.matcher(t);
    if (!m.matches()) return null;
    return new Reply(m.group(1).trim(), m.group(2).trim());
  }

  public static ReactionWire parseReactionWire(String text) {
    if (text == null) return null;
    Matcher m = REACT_WIRE.matcher(ReachIntroMessage.strip(text.trim()));
    if (!m.matches()) return null;
    return new ReactionWire(m.group(1).trim(), m.group(2).trim());
  }

  public static ReactionDisplay parseReactionDisplay(String text) {
    if (text == null) return null;
    Matcher m = REACT_DISPLAY.matcher(text.trim());
    if (!m.matches()) return null;
    return new ReactionDisplay(m.group(1).trim(), m.group(2).trim());
  }

  /** User-visible body (strips reply prefix, intro, reactions return empty). */
  public static String displayText(String text) {
    if (text == null) return "";
    if (ReachIntroMessage.isIntro(text)) return "";
    String t = ReachIntroMessage.strip(text.trim());
    Reply reply = parseReply(t);
    if (reply != null) return reply.body;
    if (parseReactionWire(t) != null || parseReactionDisplay(t) != null) return "";
    return t;
  }

  public static boolean isReactionMessage(String text) {
    if (text == null) return false;
    String t = text.trim();
    return parseReactionWire(t) != null || parseReactionDisplay(t) != null;
  }

  public static boolean isIntroOrReaction(String text) {
    return ReachIntroMessage.isIntro(text) || isReactionMessage(text);
  }

  public static String formatReplyWire(String quote, String body) {
    return "Reply to \"" + ReachIntroMessage.stripFromQuote(quote) + "\": " + body;
  }

  public static String formatRoomReplyWire(String quoteAuthor, String quote, String body) {
    String author = quoteAuthor != null ? quoteAuthor.trim() : "";
    String q = ReachIntroMessage.stripFromQuote(quote);
    if (author.isEmpty()) return formatReplyWire(q, body);
    return "Reply to @" + author + " \"" + q + "\": " + body;
  }

  public static String formatReactionWire(String quote, String emoji) {
    return "reacted to \"" + ReachIntroMessage.stripFromQuote(quote) + "\" with " + emoji;
  }

  public static String formatReactionDisplay(String username, String emoji) {
    if (username == null || username.isEmpty()) {
      return "You reacted: " + emoji;
    }
    if ("You".equals(username)) {
      return "You reacted: " + emoji;
    }
    return username + " reacted: " + emoji;
  }

  /** One-line preview for inbox subtitles. */
  public static String previewText(String text) {
    String body = displayText(text);
    if (!body.isEmpty()) return body.replace('\n', ' ').trim();
    ReactionWire wire = parseReactionWire(text);
    if (wire != null) return wire.emoji;
    ReactionDisplay disp = parseReactionDisplay(text);
    if (disp != null) return disp.emoji;
    return "";
  }

  public static List<String> splitDisplayLines(String text, int maxLines) {
    List<String> out = new ArrayList<String>();
    if (text == null || text.isEmpty()) return out;
    String[] parts = text.split("\n", maxLines + 1);
    for (int i = 0; i < parts.length && i < maxLines; i++) {
      String p = parts[i].trim();
      if (!p.isEmpty()) out.add(p);
    }
    if (parts.length > maxLines && !out.isEmpty()) {
      int last = out.size() - 1;
      out.set(last, out.get(last) + "…");
    }
    if (out.isEmpty()) out.add(text.trim());
    return out;
  }

  /** Match a reply/reaction quote to an earlier message in the thread. */
  public static int findQuotedMessageIndex(List<SoulseekMessaging.Message> messages, String quote,
      int beforeIndex) {
    return findQuotedMessageIndex(messages, quote, null, beforeIndex);
  }

  public static int findQuotedMessageIndex(List<SoulseekMessaging.Message> messages, String quote,
      String quoteAuthor, int beforeIndex) {
    if (quote == null || quote.isEmpty() || messages == null) return -1;
    String q = ReachIntroMessage.stripFromQuote(quote.trim());
    String author = quoteAuthor != null ? quoteAuthor.trim() : "";
    int last = Math.min(beforeIndex - 1, messages.size() - 1);
    for (int i = last; i >= 0; i--) {
      SoulseekMessaging.Message m = messages.get(i);
      if (m == null || m.text == null || m.statusEvent) continue;
      if (isIntroOrReaction(m.text)) continue;
      if (!author.isEmpty() && m.peer != null && !m.peer.equalsIgnoreCase(author)) continue;
      String raw = m.text.trim();
      String display = displayText(raw);
      if (q.equals(raw) || q.equals(display)) return i;
      int nl = display.indexOf('\n');
      String first = nl < 0 ? display : display.substring(0, nl).trim();
      if (q.equals(first)) return i;
    }
    for (int i = last; i >= 0; i--) {
      SoulseekMessaging.Message m = messages.get(i);
      if (m == null || m.text == null || m.statusEvent) continue;
      if (isIntroOrReaction(m.text)) continue;
      if (!author.isEmpty() && m.peer != null && !m.peer.equalsIgnoreCase(author)) continue;
      String raw = m.text.trim();
      String display = displayText(raw);
      if (display.startsWith(q) || raw.startsWith(q)) return i;
    }
    return -1;
  }

  public static int findQuotedRoomMessageIndex(List<SoulseekChatRooms.RoomMessage> messages,
      String quote, String quoteAuthor, int beforeIndex) {
    if (quote == null || quote.isEmpty() || messages == null) return -1;
    String q = ReachIntroMessage.stripFromQuote(quote.trim());
    String author = quoteAuthor != null ? quoteAuthor.trim() : "";
    int last = Math.min(beforeIndex - 1, messages.size() - 1);
    for (int i = last; i >= 0; i--) {
      SoulseekChatRooms.RoomMessage m = messages.get(i);
      if (m == null || m.text == null || m.statusEvent) continue;
      if (isIntroOrReaction(m.text)) continue;
      if (!author.isEmpty() && m.sender != null && !m.sender.equalsIgnoreCase(author)) continue;
      String raw = m.text.trim();
      String display = displayText(raw);
      if (q.equals(raw) || q.equals(display)) return i;
      int nl = display.indexOf('\n');
      String first = nl < 0 ? display : display.substring(0, nl).trim();
      if (q.equals(first)) return i;
    }
    for (int i = last; i >= 0; i--) {
      SoulseekChatRooms.RoomMessage m = messages.get(i);
      if (m == null || m.text == null || m.statusEvent) continue;
      if (isIntroOrReaction(m.text)) continue;
      if (!author.isEmpty() && m.sender != null && !m.sender.equalsIgnoreCase(author)) continue;
      String raw = m.text.trim();
      String display = displayText(raw);
      if (display.startsWith(q) || raw.startsWith(q)) return i;
    }
    return -1;
  }
}
