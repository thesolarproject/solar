package com.solar.launcher.soulseek;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/** Virtualized Soulseek chat room list with pagination. */
public final class ReachChatRoomsAdapter extends BaseAdapter {
    public static final int PAGE_SIZE = 40;

    private static final int TYPE_ROOM = 0;
    private static final int TYPE_SHOW_MORE = 1;
    private static final int TYPE_STATUS = 2;

    public interface Listener {
        void onRoomSelected(SoulseekWire.RoomEntry room);
        void onShowMore();
    }

    private final Activity activity;
    private final Listener listener;
    private List<SoulseekWire.RoomEntry> allRooms = new ArrayList<SoulseekWire.RoomEntry>();
    private int visibleCount = PAGE_SIZE;
    private int selectedPosition = -1;
    private int rowWidthPx;
    private int rowHeightPx;
    private boolean statusMode = false;
    private String statusText = "";

    public ReachChatRoomsAdapter(Activity activity, Listener listener) {
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
        allRooms.clear();
        searchQuery = "";
        joinByNameFallback = null;
        visibleCount = PAGE_SIZE;
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setStatus(String message) {
        statusMode = true;
        statusText = message != null ? message : "";
        allRooms.clear();
        searchQuery = "";
        joinByNameFallback = null;
        visibleCount = PAGE_SIZE;
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setAwaitingSearch(String message) {
        setStatus(message != null ? message
                : activity.getString(R.string.soulseek_chat_rooms_empty));
    }

    public void setRooms(List<SoulseekWire.RoomEntry> rooms) {
        statusMode = false;
        statusText = "";
        searchQuery = "";
        joinByNameFallback = null;
        allRooms = rooms != null ? new ArrayList<SoulseekWire.RoomEntry>(rooms) : new ArrayList<SoulseekWire.RoomEntry>();
        if (visibleCount < PAGE_SIZE) visibleCount = PAGE_SIZE;
        if (visibleCount > allRooms.size()) visibleCount = allRooms.size();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    private String searchQuery = "";
    private String joinByNameFallback = null;

    public String getSearchQuery() {
        return searchQuery;
    }

    public void setSearchResults(String query, List<SoulseekWire.RoomEntry> rooms) {
        statusMode = false;
        statusText = "";
        searchQuery = query != null ? query.trim() : "";
        joinByNameFallback = null;
        allRooms = rooms != null ? new ArrayList<SoulseekWire.RoomEntry>(rooms) : new ArrayList<SoulseekWire.RoomEntry>();
        visibleCount = PAGE_SIZE;
        if (visibleCount > allRooms.size()) visibleCount = allRooms.size();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setJoinByNameFallback(String query) {
        statusMode = false;
        statusText = "";
        searchQuery = query != null ? query.trim() : "";
        joinByNameFallback = searchQuery.isEmpty() ? null : searchQuery;
        allRooms.clear();
        visibleCount = 0;
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public boolean hasJoinByNameFallback() {
        return joinByNameFallback != null && !joinByNameFallback.isEmpty();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setSelectedPosition(int pos) {
        if (pos == selectedPosition) return;
        selectedPosition = pos;
        notifyDataSetChanged();
    }

    public boolean hasShowMoreRow() {
        return !statusMode && visibleCount < allRooms.size();
    }

    public int getDataCount() {
        if (statusMode) return 0;
        if (joinByNameFallback != null) return 1;
        return Math.min(visibleCount, allRooms.size());
    }

    @Override
    public int getCount() {
        if (statusMode) return 1;
        int data = getDataCount();
        if (data == 0) return 0;
        return data + (hasShowMoreRow() ? 1 : 0);
    }

    @Override
    public Object getItem(int position) {
        if (statusMode || getDataCount() == 0) return statusText;
        if (position < 0 || position >= getDataCount()) return null;
        return roomAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        if (statusMode) return false;
        if (getDataCount() == 0) return false;
        return true;
    }

    public boolean isShowMorePosition(int position) {
        return !statusMode && getDataCount() > 0 && position >= getDataCount();
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        if (statusMode || getDataCount() == 0) return TYPE_STATUS;
        if (position >= getDataCount()) return TYPE_SHOW_MORE;
        return TYPE_ROOM;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        int type = getItemViewType(position);
        if (type == TYPE_STATUS) {
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
                text = activity.getString(R.string.soulseek_chat_rooms_loading);
            }
            tv.setText(text);
            ThemeManager.applyThemedTextStyle(tv,
                    statusMode
                            ? ThemeManager.getHintTextColor()
                            : ThemeManager.getTextColorSecondary());
            return tv;
        }
        if (type == TYPE_SHOW_MORE) {
            Button btn;
            if (convertView instanceof Button) {
                btn = (Button) convertView;
            } else {
                btn = new Button(activity);
                btn.setLayoutParams(new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT));
            }
            btn.setText(activity.getString(R.string.soulseek_show_more,
                    visibleCount, allRooms.size()));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    visibleCount = Math.min(visibleCount + PAGE_SIZE, allRooms.size());
                    notifyDataSetChanged();
                    if (listener != null) listener.onShowMore();
                }
            });
            return btn;
        }

        final SoulseekWire.RoomEntry entry = roomAt(position);
        if (entry == null) {
            return convertView != null ? convertView : new FrameLayout(activity);
        }
        FrameLayout row;
        if (convertView instanceof FrameLayout) {
            row = (FrameLayout) convertView;
        } else {
            row = ReachMessageRow.create(activity, rowHeightPx);
        }
        final boolean joinFallback = joinByNameFallback != null;
        final String subtitle = joinFallback
                ? activity.getString(R.string.soulseek_room_join_by_name)
                : activity.getString(R.string.soulseek_room_users, entry.userCount);
        final FrameLayout rowView = row;
        ReachMessageRow.attachFocusHighlight(row, new ReachMessageRow.HighlightBind() {
            @Override
            public void bind(boolean highlighted) {
                boolean show = highlighted || position == selectedPosition;
                ReachMessageRow.bind(activity, rowView, entry.name, subtitle, false, show,
                        null, rowWidthPx, rowHeightPx, false);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRoomSelected(entry);
            }
        });
        return row;
    }

    public SoulseekWire.RoomEntry roomAt(int position) {
        if (joinByNameFallback != null && position == 0) {
            return new SoulseekWire.RoomEntry(joinByNameFallback, 0);
        }
        if (position < 0 || position >= allRooms.size()) return null;
        return allRooms.get(position);
    }

    public void showMore() {
        if (!hasShowMoreRow()) return;
        visibleCount = Math.min(visibleCount + PAGE_SIZE, allRooms.size());
        notifyDataSetChanged();
    }
}
