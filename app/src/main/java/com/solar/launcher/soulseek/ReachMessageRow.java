package com.solar.launcher.soulseek;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;

/** Two-line list rows and conversation thread rows for Reach messaging. */
public final class ReachMessageRow {
    public static final int TAG_LINE1 = 0x70cb0020;
    public static final int TAG_LINE2 = 0x70cb0021;
    public static final int TAG_LINE3 = 0x70cb0022;
    public static final int TAG_REACTIONS = 0x70cb0025;
    public static final int TAG_SUB = 0x70cb0023;
    public static final int TAG_DOT = 0x70cb0024;
    public static final int TAG_MARQUEE = 0x70cb0026;
    public static final int TAG_PEER = 0x70cb0027;
    public static final int MAX_BODY_LINES = 3;

    private static final int VERTICAL_MARQUEE_MS = 2200;
    private static final Handler MARQUEE_HANDLER = new Handler(Looper.getMainLooper());

    private ReachMessageRow() {}

    public static FrameLayout create(Activity activity, int rowHeightPx) {
        float density = activity.getResources().getDisplayMetrics().density;
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float lineTextPx = menuTextPx * 0.88f;
        float subTextPx = menuTextPx * 0.72f;
        int dotW = (int) (10 * density);
        int flagW = (int) (18 * density);
        int flagH = (int) (12 * density);
        int leadW = Math.max(dotW, flagW);
        int lineH = lineHeightPx(lineTextPx);
        int subH = sublineHeightPx(subTextPx);

        FrameLayout row = new FrameLayout(activity);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setSoundEffectsEnabled(false);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = textPadLeft + leadW + (int) (6 * density);
        colLp.rightMargin = (int) (8 * density);
        colLp.gravity = Gravity.CENTER_VERTICAL;
        colLp.topMargin = (int) (3 * density);
        colLp.bottomMargin = (int) (3 * density);

        for (int i = 0; i < MAX_BODY_LINES; i++) {
            TextView line = new TextView(activity);
            line.setTag(i == 0 ? TAG_LINE1 : (i == 1 ? TAG_LINE2 : TAG_LINE3));
            line.setFocusable(false);
            line.setTypeface(ThemeManager.getCustomFont(),
                    i == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, lineTextPx);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            line.setMarqueeRepeatLimit(-1);
            line.setHorizontallyScrolling(true);
            line.setVisibility(View.GONE);
            textCol.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, lineH));
        }

        TextView reactions = new TextView(activity);
        reactions.setTag(TAG_REACTIONS);
        reactions.setFocusable(false);
        reactions.setTypeface(ThemeManager.getCustomFont());
        reactions.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, subTextPx);
        reactions.setSingleLine(true);
        reactions.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        reactions.setMarqueeRepeatLimit(-1);
        reactions.setHorizontallyScrolling(true);
        ThemeManager.applyThemedTextStyle(reactions,
                ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
        reactions.setVisibility(View.GONE);
        textCol.addView(reactions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, subH));

        TextView sub = new TextView(activity);
        sub.setTag(TAG_SUB);
        sub.setFocusable(false);
        sub.setTypeface(ThemeManager.getCustomFont());
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, subTextPx);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        sub.setMarqueeRepeatLimit(-1);
        sub.setHorizontallyScrolling(true);
        ThemeManager.applyThemedTextStyle(sub,
                ThemeManager.contextMenuMutedText(ThemeManager.getHintTextColor()));
        sub.setVisibility(View.GONE);
        textCol.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, subH));

        row.addView(textCol, colLp);

        ImageView dot = new ImageView(activity);
        dot.setTag(TAG_DOT);
        dot.setFocusable(false);
        dot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(flagW, flagH);
        dotLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        dotLp.leftMargin = textPadLeft;
        row.addView(dot, dotLp);

        int minH = rowHeightPx > 0 ? rowHeightPx : measureListRowHeight(activity);
        row.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, minH + 2));
        return row;
    }

    /** Focus-driven highlight rebinding — matches library list row behavior. */
    public interface HighlightBind {
        void bind(boolean highlighted);
    }

    public static void attachFocusHighlight(FrameLayout row, final HighlightBind binder) {
        if (row == null || binder == null) return;
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                binder.bind(hasFocus);
            }
        });
        binder.bind(row.hasFocus());
    }

    public static int lineHeightPx(float lineTextPx) {
        return (int) (lineTextPx * 1.15f);
    }

    private static int sublineHeightPx(float subTextPx) {
        return (int) (subTextPx * 1.2f);
    }

    /** Standard peer / inbox row: title + subtitle. */
    public static int measureListRowHeight(Activity activity) {
        return measureListRowHeight(activity, false);
    }

    /** Inbox / peer list: title + optional preview line + timestamp subtitle. */
    public static int measureListRowHeight(Activity activity, boolean withPreviewLine) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float lineTextPx = menuTextPx * 0.88f;
        float subTextPx = menuTextPx * 0.72f;
        int densityPad = (int) (8 * activity.getResources().getDisplayMetrics().density);
        int h = lineHeightPx(lineTextPx) + sublineHeightPx(subTextPx);
        if (withPreviewLine) {
            h += lineHeightPx(lineTextPx * 0.82f);
        }
        return h + densityPad;
    }

    /** Inbox row: title, optional preview on second line, timestamp always on subtitle. */
    public static void bindInboxRow(Activity activity, FrameLayout row, String title,
            String previewLine, String timestamp, boolean selected, int rowWidthPx, int rowHeightPx) {
        bindInboxRow(activity, row, title, previewLine, timestamp, selected, rowWidthPx, rowHeightPx, null);
    }

    public static void bindInboxRow(Activity activity, FrameLayout row, String title,
            String previewLine, String timestamp, boolean selected, int rowWidthPx, int rowHeightPx,
            String countryCode) {
        if (row == null) return;
        stopVerticalMarquee(row);
        applyRowBackground(activity, row, selected, rowWidthPx, rowHeightPx);

        TextView line1 = (TextView) row.findViewWithTag(TAG_LINE1);
        TextView line2 = (TextView) row.findViewWithTag(TAG_LINE2);
        TextView line3 = (TextView) row.findViewWithTag(TAG_LINE3);
        TextView reactions = (TextView) row.findViewWithTag(TAG_REACTIONS);
        TextView sub = (TextView) row.findViewWithTag(TAG_SUB);

        if (reactions != null) reactions.setVisibility(View.GONE);
        hideLine(line3);

        int textColor = conversationTextColor(true, selected);
        String titleText = title != null ? title : "";
        bindLine(line1, titleText, textColor, selected);

        boolean showPreview = previewLine != null && !previewLine.isEmpty();
        if (line2 != null) {
            if (showPreview) {
                bindLine(line2, previewLine, ThemeManager.getHintTextColor(), selected);
            } else {
                hideLine(line2);
            }
        }

        if (sub != null) {
            String ts = timestamp != null ? timestamp : "";
            sub.setText(ts);
            sub.setVisibility(ts.isEmpty() ? View.GONE : View.VISIBLE);
            sub.setSelected(selected);
        }

        bindLeadIcon(activity, row, true, null, countryCode);
        alignTextColumn(row, true);
        row.setSelected(selected);
        updateRowLayoutHeight(row, rowHeightPx);
    }

    public static int conversationBodyMaxHeightPx(Activity activity) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float lineTextPx = menuTextPx * 0.88f;
        return lineHeightPx(lineTextPx) * 3;
    }

    public static int measureConversationEntryHeight(Activity activity,
            ConversationDisplayBuilder.Entry entry, boolean selected) {
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float lineTextPx = menuTextPx * 0.88f;
        float subTextPx = menuTextPx * 0.72f;
        int lineH = lineHeightPx(lineTextPx);
        int subH = sublineHeightPx(subTextPx);
        int maxBodyH = conversationBodyMaxHeightPx(activity);
        int densityPad = (int) (8 * activity.getResources().getDisplayMetrics().density);

        int bodyH = lineH;
        if (selected && entry.isLongBody()) {
            bodyH = Math.min(maxBodyH, lineH * 2);
        }
        int h = bodyH + subH;
        if (!entry.reactionLines.isEmpty()) {
            h += sublineHeightPx(subTextPx);
        }
        return h + densityPad;
    }

    public static void bind(Activity activity, FrameLayout row, String body, String subtitle,
            boolean incoming, boolean selected, Boolean peerOnline, int rowWidthPx, int rowHeightPx) {
        bind(activity, row, body, subtitle, incoming, selected, peerOnline, rowWidthPx, rowHeightPx, true);
    }

    public static void bind(Activity activity, FrameLayout row, String body, String subtitle,
            boolean incoming, boolean selected, Boolean peerOnline, int rowWidthPx, int rowHeightPx,
            boolean showReplyHint) {
        if (row == null) return;
        stopVerticalMarquee(row);
        applyRowBackground(activity, row, selected, rowWidthPx, rowHeightPx);

        String bodyText = body != null ? body : "";
        java.util.List<String> lines = ReachMessageFormat.splitDisplayLines(bodyText, MAX_BODY_LINES);
        TextView line1 = (TextView) row.findViewWithTag(TAG_LINE1);
        TextView line2 = (TextView) row.findViewWithTag(TAG_LINE2);
        TextView line3 = (TextView) row.findViewWithTag(TAG_LINE3);
        TextView reactions = (TextView) row.findViewWithTag(TAG_REACTIONS);
        TextView sub = (TextView) row.findViewWithTag(TAG_SUB);

        if (reactions != null) reactions.setVisibility(View.GONE);

        int textColor = conversationTextColor(incoming, selected);

        if (selected) {
            bindLine(line1, lines.size() > 0 ? lines.get(0) : bodyText, textColor, true);
            bindLine(line2, lines.size() > 1 ? lines.get(1) : "", textColor, lines.size() > 1);
            bindLine(line3, lines.size() > 2 ? lines.get(2) : "", textColor, lines.size() > 2);
        } else {
            bindLine(line1, previewLine(bodyText), textColor, false);
            hideLine(line2);
            hideLine(line3);
        }
        if (line2 != null && selected) {
            line2.setVisibility(lines.size() > 1 ? View.VISIBLE : View.GONE);
        }
        if (line3 != null && selected) {
            line3.setVisibility(lines.size() > 2 ? View.VISIBLE : View.GONE);
        }

        if (sub != null) {
            String subText = subtitle != null ? subtitle : "";
            if (selected && showReplyHint) {
                if (subText.isEmpty()) {
                    subText = activity.getString(R.string.soulseek_reply);
                } else {
                    subText = subText + " · " + activity.getString(R.string.soulseek_reply);
                }
            }
            sub.setText(subText);
            sub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE);
            sub.setSelected(selected);
        }

        bindPeerDot(row, incoming, peerOnline);
        alignTextColumn(row, incoming);
        row.setSelected(selected);
        updateRowLayoutHeight(row, rowHeightPx);
    }

    public static void bindConversationEntry(Activity activity, FrameLayout row,
            ConversationDisplayBuilder.Entry entry, boolean incoming, boolean selected,
            Boolean peerOnline, int rowWidthPx, int rowHeightPx) {
        bindConversationEntry(activity, row, entry, incoming, selected, peerOnline,
                rowWidthPx, rowHeightPx, null);
    }

    public static void bindConversationEntry(Activity activity, FrameLayout row,
            ConversationDisplayBuilder.Entry entry, boolean incoming, boolean selected,
            Boolean peerOnline, int rowWidthPx, int rowHeightPx, String subtitleOverride) {
        if (row == null || entry == null) return;
        stopVerticalMarquee(row);
        applyRowBackground(activity, row, selected, rowWidthPx, rowHeightPx);

        TextView line1 = (TextView) row.findViewWithTag(TAG_LINE1);
        TextView line2 = (TextView) row.findViewWithTag(TAG_LINE2);
        TextView line3 = (TextView) row.findViewWithTag(TAG_LINE3);
        TextView reactions = (TextView) row.findViewWithTag(TAG_REACTIONS);
        TextView sub = (TextView) row.findViewWithTag(TAG_SUB);

        int textColor = conversationTextColor(incoming, selected);
        String prefix = entry.isReply ? "↳ " : "";
        java.util.List<String> allLines = ReachMessageFormat.splitDisplayLines(
                prefix + entry.displayText, 12);

        if (selected && entry.isLongBody() && allLines.size() > 2) {
            startVerticalMarquee(row, allLines, line1, line2, textColor);
            hideLine(line3);
        } else if (selected && entry.isLongBody() && allLines.size() > 1) {
            bindLine(line1, allLines.get(0), textColor, true);
            bindLine(line2, allLines.get(1), textColor, true);
            hideLine(line3);
        } else {
            bindLine(line1, allLines.isEmpty() ? "" : allLines.get(0), textColor, selected);
            hideLine(line2);
            hideLine(line3);
        }

        if (reactions != null) {
            if (!entry.reactionLines.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < entry.reactionLines.size(); i++) {
                    if (i > 0) sb.append("  ");
                    sb.append(entry.reactionLines.get(i));
                }
                reactions.setText(sb.toString());
                reactions.setVisibility(View.VISIBLE);
                reactions.setSelected(selected);
            } else {
                reactions.setVisibility(View.GONE);
            }
        }

        if (sub != null) {
            String subText = subtitleOverride != null ? subtitleOverride
                    : (entry.timestamp != null ? entry.timestamp : "");
            sub.setText(subText);
            sub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE);
            sub.setSelected(selected);
        }

        bindPeerDot(row, incoming, peerOnline);
        alignTextColumn(row, incoming);
        row.setSelected(selected);
        updateRowLayoutHeight(row, rowHeightPx);
    }

    public static void bindConversationMessage(Activity activity, FrameLayout row, String body,
            String subtitle, boolean incoming, boolean selected, Boolean peerOnline,
            int rowWidthPx, int rowHeightPx) {
        bind(activity, row, body, subtitle, incoming, selected, peerOnline, rowWidthPx, rowHeightPx, false);
    }

    public static void bindRoomMessage(Activity activity, FrameLayout row, String body,
            String senderSubtitle, String timestamp, boolean isSelf, boolean selected,
            int rowWidthPx, int rowHeightPx) {
        bindRoomMessage(activity, row, body, senderSubtitle, timestamp, isSelf, selected,
                rowWidthPx, rowHeightPx, null);
    }

    public static void bindRoomMessage(Activity activity, FrameLayout row, String body,
            String senderSubtitle, String timestamp, boolean isSelf, boolean selected,
            int rowWidthPx, int rowHeightPx, String countryCode) {
        String subtitle = senderSubtitle != null ? senderSubtitle : "";
        if (timestamp != null && !timestamp.isEmpty()) {
            subtitle = subtitle.isEmpty() ? timestamp : subtitle + " · " + timestamp;
        }
        bind(activity, row, body, subtitle, !isSelf, selected, null, rowWidthPx, rowHeightPx, false);
        bindLeadIcon(activity, row, !isSelf, null, countryCode);
    }

    public static void bindRoomConversationEntry(Activity activity, FrameLayout row,
            RoomConversationDisplayBuilder.Entry entry, boolean incoming, boolean selected,
            String sender, int rowWidthPx, int rowHeightPx, String countryCode) {
        if (row == null || entry == null) return;
        stopVerticalMarquee(row);
        applyRowBackground(activity, row, selected, rowWidthPx, rowHeightPx);

        TextView line1 = (TextView) row.findViewWithTag(TAG_LINE1);
        TextView line2 = (TextView) row.findViewWithTag(TAG_LINE2);
        TextView line3 = (TextView) row.findViewWithTag(TAG_LINE3);
        TextView reactions = (TextView) row.findViewWithTag(TAG_REACTIONS);
        TextView sub = (TextView) row.findViewWithTag(TAG_SUB);

        int textColor = conversationTextColor(incoming, selected);
        String prefix = entry.isReply ? "↳ " : "";
        java.util.List<String> allLines = ReachMessageFormat.splitDisplayLines(
                prefix + entry.displayText, 12);

        if (selected && entry.isLongBody() && allLines.size() > 2) {
            startVerticalMarquee(row, allLines, line1, line2, textColor);
            hideLine(line3);
        } else if (selected && entry.isLongBody() && allLines.size() > 1) {
            bindLine(line1, allLines.get(0), textColor, true);
            bindLine(line2, allLines.get(1), textColor, true);
            hideLine(line3);
        } else {
            bindLine(line1, allLines.isEmpty() ? "" : allLines.get(0), textColor, selected);
            hideLine(line2);
            hideLine(line3);
        }

        if (reactions != null) {
            if (!entry.reactionLines.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < entry.reactionLines.size(); i++) {
                    if (i > 0) sb.append("  ");
                    sb.append(entry.reactionLines.get(i));
                }
                reactions.setText(sb.toString());
                reactions.setVisibility(View.VISIBLE);
                reactions.setSelected(selected);
            } else {
                reactions.setVisibility(View.GONE);
            }
        }

        if (sub != null) {
            String subText = sender != null ? sender : "";
            if (entry.timestamp != null && !entry.timestamp.isEmpty()) {
                subText = subText.isEmpty() ? entry.timestamp : subText + " · " + entry.timestamp;
            }
            sub.setText(subText);
            sub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE);
            sub.setSelected(selected);
        }

        bindPeerDot(row, incoming, null);
        bindLeadIcon(activity, row, incoming, null, countryCode);
        alignTextColumn(row, incoming);
        row.setSelected(selected);
        updateRowLayoutHeight(row, rowHeightPx);
    }

    private static void bindLeadIcon(Activity activity, FrameLayout row, boolean incoming,
            Boolean peerOnline, String countryCode) {
        ImageView dot = (ImageView) row.findViewWithTag(TAG_DOT);
        if (dot == null) return;
        android.graphics.drawable.Drawable flag = SoulseekCountryFlags.loadFlagDrawable(
                activity, countryCode);
        if (flag != null) {
            dot.setImageDrawable(flag);
            dot.setVisibility(View.VISIBLE);
            return;
        }
        bindPeerDot(row, incoming, peerOnline);
    }

    private static GradientDrawable peerDotOnline;
    private static GradientDrawable peerDotOffline;

    private static GradientDrawable peerDotDrawable(boolean online) {
        if (online) {
            if (peerDotOnline == null) {
                peerDotOnline = new GradientDrawable();
                peerDotOnline.setShape(GradientDrawable.OVAL);
                peerDotOnline.setColor(0xFF44CC44);
            }
            return peerDotOnline;
        }
        if (peerDotOffline == null) {
            peerDotOffline = new GradientDrawable();
            peerDotOffline.setShape(GradientDrawable.OVAL);
            peerDotOffline.setColor(0xFFCC4444);
        }
        return peerDotOffline;
    }

    private static void bindPeerDot(FrameLayout row, boolean incoming, Boolean peerOnline) {
        ImageView dot = (ImageView) row.findViewWithTag(TAG_DOT);
        if (dot != null) {
            if (incoming && peerOnline != null) {
                dot.setImageDrawable(peerDotDrawable(peerOnline.booleanValue()));
                dot.setVisibility(View.VISIBLE);
            } else {
                dot.setVisibility(View.GONE);
            }
        }
    }

    private static void applyRowBackground(Activity activity, FrameLayout row, boolean selected,
            int rowWidthPx, int rowHeightPx) {
        android.graphics.drawable.Drawable bg = ThemeManager.getItemRowBackgroundScaled(
                activity.getResources(), selected, rowWidthPx, rowHeightPx);
        if (bg != null) {
            row.setBackground(bg);
        } else if (selected) {
            row.setBackgroundColor(ThemeManager.getRowSelectionFillColor());
        } else {
            row.setBackgroundColor(ThemeManager.getListButtonNormalBg());
        }
    }

    private static int conversationTextColor(boolean incoming, boolean selected) {
        int normalColor = incoming
                ? ThemeManager.getTextColorSecondary()
                : ThemeManager.getItemTextColorNormal();
        int selectedColor = incoming
                ? ThemeManager.getTextColorPrimary()
                : ThemeManager.getItemTextColorSelected();
        return selected ? selectedColor : normalColor;
    }

    private static void alignTextColumn(FrameLayout row, boolean incoming) {
        TextView line1 = (TextView) row.findViewWithTag(TAG_LINE1);
        if (line1 != null && line1.getParent() instanceof LinearLayout) {
            LinearLayout textCol = (LinearLayout) line1.getParent();
            textCol.setGravity(incoming ? (Gravity.CENTER_VERTICAL | Gravity.START)
                    : (Gravity.CENTER_VERTICAL | Gravity.END));
        }
    }

    private static void updateRowLayoutHeight(FrameLayout row, int rowHeightPx) {
        if (row.getLayoutParams() != null) {
            row.getLayoutParams().height = rowHeightPx;
            row.requestLayout();
        }
    }

    private static void bindLine(TextView line, String text, int color, boolean marquee) {
        if (line == null) return;
        boolean show = text != null && !text.isEmpty();
        line.setVisibility(show ? View.VISIBLE : View.GONE);
        line.setText(text != null ? text : "");
        ThemeManager.applyThemedTextStyle(line, color);
        line.setSelected(marquee && show);
        line.setHorizontallyScrolling(marquee && show);
        if (marquee && show) {
            line.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            line.setMarqueeRepeatLimit(-1);
        } else {
            line.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    private static void hideLine(TextView line) {
        if (line != null) line.setVisibility(View.GONE);
    }

    private static String previewLine(String body) {
        if (body == null || body.isEmpty()) return "";
        int nl = body.indexOf('\n');
        if (nl < 0) return body;
        return body.substring(0, nl).trim();
    }

    private static void stopVerticalMarquee(FrameLayout row) {
        Object token = row.getTag(TAG_MARQUEE);
        if (token instanceof Runnable) {
            MARQUEE_HANDLER.removeCallbacks((Runnable) token);
        }
        row.setTag(TAG_MARQUEE, null);
    }

    private static void startVerticalMarquee(final FrameLayout row,
            final java.util.List<String> allLines, final TextView line1, final TextView line2,
            final int textColor) {
        stopVerticalMarquee(row);
        final int[] offset = new int[] {0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (allLines.isEmpty()) return;
                int maxOffset = Math.max(0, allLines.size() - 2);
                int o = offset[0];
                bindLine(line1, allLines.get(o), textColor, true);
                if (allLines.size() > o + 1) {
                    bindLine(line2, allLines.get(o + 1), textColor, true);
                } else {
                    hideLine(line2);
                }
                offset[0] = o >= maxOffset ? 0 : o + 1;
                MARQUEE_HANDLER.postDelayed(this, VERTICAL_MARQUEE_MS);
            }
        };
        row.setTag(TAG_MARQUEE, tick);
        tick.run();
    }
}
