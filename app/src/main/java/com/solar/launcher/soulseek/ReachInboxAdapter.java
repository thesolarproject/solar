package com.solar.launcher.soulseek;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.solar.launcher.DebugAgentLog;
import com.solar.launcher.R;
import com.solar.launcher.soulseek.store.ReachDatabase;
import com.solar.launcher.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/** Virtualized Reach messages inbox (peer list). */
public final class ReachInboxAdapter extends BaseAdapter {
    private static final int TYPE_PEER = 0;
    private static final int TYPE_STATUS = 1;

    public interface Listener {
        void onPeerSelected(String peer);
        String countryCodeForPeer(String peer);
    }

    private final Activity activity;
    private final Listener listener;
    private List<ReachDatabase.InboxRow> rows = new ArrayList<ReachDatabase.InboxRow>();
    private int selectedPosition = -1;
    private int rowWidthPx;
    private int rowHeightPx;
    private boolean statusMode = false;
    private String statusText = "";

    public ReachInboxAdapter(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        rowWidthPx = activity.getResources().getDisplayMetrics().widthPixels;
        rowHeightPx = ReachMessageRow.measureListRowHeight(activity, true);
    }

    public void setRowWidthPx(int w) {
        rowWidthPx = w;
    }

    public void setLoading(String message) {
        statusMode = true;
        statusText = message != null ? message : "";
        rows.clear();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setStatus(String message) {
        statusMode = true;
        statusText = message != null ? message : "";
        rows.clear();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setInbox(List<ReachDatabase.InboxRow> inbox) {
        rows = inbox != null ? new ArrayList<ReachDatabase.InboxRow>(inbox) : new ArrayList<ReachDatabase.InboxRow>();
        statusMode = false;
        statusText = "";
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setSelectedPosition(int pos) {
        if (pos == selectedPosition) return;
        selectedPosition = pos;
        notifyDataSetChanged();
    }

    public String peerAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        return rows.get(position).peer;
    }

    public int getDataCount() {
        return statusMode ? 0 : rows.size();
    }

    @Override
    public int getCount() {
        if (statusMode) return 1;
        return rows.isEmpty() ? 1 : rows.size();
    }

    @Override
    public Object getItem(int position) {
        if (statusMode || rows.isEmpty()) return statusText;
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return !statusMode && !rows.isEmpty();
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return statusMode || rows.isEmpty() ? TYPE_STATUS : TYPE_PEER;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (getItemViewType(position) == TYPE_STATUS) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(activity);
                tv.setLayoutParams(new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT));
            }
            String text = statusText;
            if (text == null || text.isEmpty()) {
                text = activity.getString(R.string.soulseek_messages_loading);
            }
            tv.setText(text);
            ThemeManager.applyThemedTextStyle(tv,
                    statusMode
                            ? ThemeManager.getHintTextColor()
                            : ThemeManager.getTextColorSecondary());
            return tv;
        }

        final ReachDatabase.InboxRow row;
        try {
            row = rows.get(position);
        } catch (IndexOutOfBoundsException e) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("position", position);
                d.put("rows", rows.size());
                d.put("statusMode", statusMode);
                DebugAgentLog.log(activity, "ReachInboxAdapter.getView", "index oob", "H-D", d);
            } catch (Exception ignored) {}
            // #endregion
            TextView tv = new TextView(activity);
            tv.setText(statusText);
            return tv;
        }
        FrameLayout frame;
        if (convertView instanceof FrameLayout) {
            frame = (FrameLayout) convertView;
        } else {
            frame = ReachMessageRow.create(activity, rowHeightPx);
        }
        final String timestamp = SoulseekMessaging.formatTimestamp(row.timestamp);
        String preview = ReachMessageFormat.previewText(row.text);
        String note = SoulseekPeerNotes.getNoteSync(activity, row.peer);
        if (note != null && !note.trim().isEmpty()) {
            String line = note.trim().split("\n")[0];
            if (line.length() > 24) line = line.substring(0, 24) + "\u2026";
            preview = "\u2605 " + line + (preview.isEmpty() ? "" : " \u00b7 " + preview);
        }
        final String previewFinal = preview;
        final String cc = listener != null ? listener.countryCodeForPeer(row.peer) : null;
        final FrameLayout rowView = frame;
        frame.setTag(ReachMessageRow.TAG_PEER, row.peer);
        ReachMessageRow.attachFocusHighlight(frame, new ReachMessageRow.HighlightBind() {
            @Override
            public void bind(boolean highlighted) {
                boolean show = highlighted || position == selectedPosition;
                ReachMessageRow.bindInboxRow(activity, rowView, row.peer, previewFinal, timestamp,
                        show, rowWidthPx, rowHeightPx, cc);
            }
        });
        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onPeerSelected(row.peer);
            }
        });
        return frame;
    }
}
