package com.solar.media;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-29: Consolidated virtualized browse adapter — replaces NavidromeBrowseAdapter,
 * JellyfinBrowseAdapter, and PlexBrowseAdapter (all 80-line copies of identical logic).
 *
 * @param <T> the server-specific row type that implements {@link MediaBrowseRow}
 */
public final class MediaBrowseAdapter<T extends MediaBrowseRow> extends BaseAdapter {

    public interface RowUi<T> {
        Button createListButton(String label);
        void bindListButton(Button btn, boolean focused, String label);
        void applyListRowParams(View row, int heightPx);
        int rowHeightPx();
        void onRowClick(T row);
        void onRowFocused(T row, boolean hasFocus);
    }

    private final RowUi<T> ui;
    private final List<T> rows = new ArrayList<T>();

    public MediaBrowseAdapter(RowUi<T> ui) {
        this.ui = ui;
    }

    public void setRows(List<T> next) {
        rows.clear();
        if (next != null) rows.addAll(next);
        notifyDataSetChanged();
    }

    public T rowAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        return rows.get(position);
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Object getItem(int position) { return rowAt(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final T row = rows.get(position);
        Button btn;
        if (convertView instanceof Button) {
            btn = (Button) convertView;
        } else {
            btn = ui.createListButton("");
        }
        ui.applyListRowParams(btn, ui.rowHeightPx());
        String text = prefixFor(row.getKind()) + row.getLabel();
        String subtitle = row.getSubtitle();
        if (subtitle != null && !subtitle.isEmpty()) {
            text += " · " + subtitle;
        }
        btn.setText(text);
        final Button bound = btn;
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                ui.bindListButton(bound, hasFocus, row.getLabel());
                if (hasFocus) ui.onRowFocused(row, true);
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ui.onRowClick(row);
            }
        });
        ui.bindListButton(bound, bound.hasFocus(), row.getLabel());
        return btn;
    }

    private static String prefixFor(MediaBrowseRow.Kind kind) {
        if (kind == MediaBrowseRow.Kind.ALBUM) return "\uD83D\uDCBF ";   // 💿
        if (kind == MediaBrowseRow.Kind.PLAYLIST) return "\uD83D\uDCCB "; // 📋
        if (kind == MediaBrowseRow.Kind.SONG) return "\uD83C\uDFB5 ";     // 🎵
        return "\uD83D\uDC64 ";                                           // 👤
    }
}
