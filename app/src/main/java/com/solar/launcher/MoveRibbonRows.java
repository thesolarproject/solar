package com.solar.launcher;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;

/** Shared row shells for {@link MoveStripController} in settings editors and library lists. */
final class MoveRibbonRows {
    static final int TAG_TITLE = 0x70cb0010;
    static final int TAG_SUB = 0x70cb0011;
    static final int TAG_GRIP = 0x70cb0012;
    static final int TAG_PP = 0x70cb0013;
    static final int TAG_CONFIRM = 0x70cb0015;
    static final int TAG_DROP = 0x70cb0016;
    static final int TAG_STATE = 0x70cb0017;

    private MoveRibbonRows() {}

    static final class LibraryRowHolder {
        final TextView title;
        final TextView sub;
        final TextView grip;
        final ImageView playback;
        final ImageView confirm;
        final View drop;
        int lastTitleColor = Integer.MIN_VALUE;
        int lastSubColor = Integer.MIN_VALUE;
        int styleGeneration = Integer.MIN_VALUE;
        int backgroundGeneration = Integer.MIN_VALUE;

        LibraryRowHolder(TextView title, TextView sub, TextView grip, ImageView playback,
                ImageView confirm, View drop) {
            this.title = title;
            this.sub = sub;
            this.grip = grip;
            this.playback = playback;
            this.confirm = confirm;
            this.drop = drop;
        }
    }

    static FrameLayout createMenuMoveRow(Activity activity, int rowHeightPx, int rowWidthPx) {
        float density = activity.getResources().getDisplayMetrics().density;
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        int slotW = (int) (rowHeightPx * 0.55f);

        FrameLayout row = new FrameLayout(activity);
        TextView title = new TextView(activity);
        title.setTag(TAG_TITLE);
        title.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        titleLp.leftMargin = textPadLeft;
        titleLp.rightMargin = slotW + (int) (8 * density);
        titleLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(title, titleLp);

        FrameLayout rightSlot = new FrameLayout(activity);
        FrameLayout.LayoutParams slotLp = new FrameLayout.LayoutParams(slotW, rowHeightPx);
        slotLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rightSlot.setLayoutParams(slotLp);

        TextView state = new TextView(activity);
        state.setTag(TAG_STATE);
        state.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        state.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
        stateLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        rightSlot.addView(state, stateLp);

        TextView grip = new TextView(activity);
        grip.setTag(TAG_GRIP);
        grip.setText(activity.getString(R.string.home_screen_move_grip));
        grip.setGravity(Gravity.CENTER);
        grip.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        grip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        grip.setVisibility(View.GONE);
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
        gripLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rightSlot.addView(grip, gripLp);

        ImageView confirm = new ImageView(activity);
        confirm.setTag(TAG_CONFIRM);
        confirm.setScaleType(ImageView.ScaleType.FIT_CENTER);
        confirm.setImageResource(R.drawable.ic_check);
        confirm.setVisibility(View.GONE);
        int iconSz = (int) (rowHeightPx * 0.42f);
        rightSlot.addView(confirm, new FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER));

        row.addView(rightSlot);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx + 2);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);
        return row;
    }

    static FrameLayout createLibraryMoveRow(Activity activity, int rowHeightPx) {
        float density = activity.getResources().getDisplayMetrics().density;
        int textPadLeft = (int) activity.getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = activity.getResources().getDimension(R.dimen.y1_menu_text_size);
        float subTextPx = menuTextPx * 0.78f;
        int slotW = (int) (rowHeightPx * 0.55f);

        FrameLayout row = new FrameLayout(activity);
        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        colLp.leftMargin = textPadLeft;
        colLp.rightMargin = slotW + (int) (8 * density);
        colLp.gravity = Gravity.CENTER_VERTICAL;

        TextView title = new TextView(activity);
        title.setTag(TAG_TITLE);
        title.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        textCol.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(activity);
        sub.setTag(TAG_SUB);
        sub.setTypeface(ThemeManager.getCustomFont());
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, subTextPx);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        sub.setMarqueeRepeatLimit(-1);
        ThemeManager.applyThemedTextStyle(sub,
                ThemeManager.contextMenuMutedText(ThemeManager.getSubtitleTextColor()));
        textCol.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(textCol, colLp);

        FrameLayout rightSlot = new FrameLayout(activity);
        FrameLayout.LayoutParams slotLp = new FrameLayout.LayoutParams(slotW, rowHeightPx);
        slotLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rightSlot.setLayoutParams(slotLp);

        TextView grip = new TextView(activity);
        grip.setTag(TAG_GRIP);
        grip.setText(activity.getString(R.string.home_screen_move_grip));
        grip.setGravity(Gravity.CENTER);
        grip.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        grip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        grip.setVisibility(View.GONE);
        rightSlot.addView(grip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView pp = new ImageView(activity);
        pp.setTag(TAG_PP);
        pp.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pp.setVisibility(View.GONE);
        int ppSz = (int) (rowHeightPx * 0.42f);
        rightSlot.addView(pp, new FrameLayout.LayoutParams(ppSz, ppSz, Gravity.CENTER));

        ImageView confirm = new ImageView(activity);
        confirm.setTag(TAG_CONFIRM);
        confirm.setScaleType(ImageView.ScaleType.FIT_CENTER);
        confirm.setImageResource(R.drawable.ic_check);
        confirm.setVisibility(View.GONE);
        rightSlot.addView(confirm, new FrameLayout.LayoutParams(ppSz, ppSz, Gravity.CENTER));

        row.addView(rightSlot);
        View drop = new View(activity);
        drop.setTag(TAG_DROP);
        drop.setVisibility(View.GONE);
        FrameLayout.LayoutParams dropLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        dropLp.gravity = Gravity.BOTTOM;
        row.addView(drop, dropLp);
        row.setTag(R.id.tag_library_row_holder,
                new LibraryRowHolder(title, sub, grip, pp, confirm, drop));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx + 2);
        rowLp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(rowLp);
        return row;
    }

    static void bindMenuMoveRow(Activity activity, FrameLayout row, String label, boolean moving,
            boolean highlighted, int rowWidthPx, int rowHeightPx) {
        if (row == null) return;
        row.setBackground(ThemeManager.getMenuRowBackgroundScaled(
                activity.getResources(), highlighted, rowWidthPx, rowHeightPx));
        TextView title = (TextView) row.findViewWithTag(TAG_TITLE);
        if (title != null) {
            title.setText(label != null ? label : "");
            ThemeManager.applyThemedTextStyle(title, highlighted
                    ? ThemeManager.getSettingMenuTextColorSelected()
                    : ThemeManager.getSettingMenuTextColorNormal());
            title.setSelected(highlighted);
        }
        TextView grip = (TextView) row.findViewWithTag(TAG_GRIP);
        if (grip != null) {
            grip.setVisibility(moving ? View.VISIBLE : View.GONE);
            if (moving) {
                ThemeManager.applyThemedTextStyle(grip, ThemeManager.getSettingMenuTextColorSelected());
            }
        }
        ensureDropLine(row, moving, ThemeManager.getSettingMenuTextColorSelected());
    }

    /** ponytail: cached row backgrounds — skip scaled-bitmap work on every ribbon bind. */
    static void bindLibraryMoveRow(Activity activity, FrameLayout row, String titleText, String subText,
            boolean moving, boolean highlighted, boolean nowPlaying, boolean playing,
            int rowWidthPx, int rowHeightPx,
            android.graphics.drawable.Drawable bgSelected,
            android.graphics.drawable.Drawable bgNormal) {
        if (row == null) return;
        LibraryRowHolder holder = (LibraryRowHolder) row.getTag(R.id.tag_library_row_holder);
        int generation = ThemeManager.getStyleGeneration();
        if (!(row.getBackground() instanceof android.graphics.drawable.StateListDrawable)
                || holder != null && holder.backgroundGeneration != generation) {
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.Drawable sel = bgSelected != null ? bgSelected : new android.graphics.drawable.ColorDrawable(ThemeManager.getRowSelectionFillColor());
            android.graphics.drawable.Drawable norm = bgNormal != null ? bgNormal : new android.graphics.drawable.ColorDrawable(ThemeManager.getListButtonNormalBg());
            states.addState(new int[] { android.R.attr.state_focused }, sel);
            states.addState(new int[] { android.R.attr.state_pressed }, sel);
            states.addState(new int[] {}, norm);
            row.setBackground(states);
            if (holder != null) holder.backgroundGeneration = generation;
        }
        bindLibraryMoveRowContent(row, titleText, subText, moving, highlighted, nowPlaying, playing);
    }

    static void bindLibraryMoveRow(Activity activity, FrameLayout row, String titleText, String subText,
            boolean moving, boolean highlighted, boolean nowPlaying, boolean playing,
            int rowWidthPx, int rowHeightPx) {
        if (row == null) return;
        LibraryRowHolder holder = (LibraryRowHolder) row.getTag(R.id.tag_library_row_holder);
        int generation = ThemeManager.getStyleGeneration();
        if (!(row.getBackground() instanceof android.graphics.drawable.StateListDrawable)
                || holder != null && holder.backgroundGeneration != generation) {
            row.setBackground(ThemeManager.createLibraryRowStateBackground(
                    activity.getResources(), rowWidthPx, rowHeightPx));
            if (holder != null) holder.backgroundGeneration = generation;
        }
        bindLibraryMoveRowContent(row, titleText, subText, moving, highlighted, nowPlaying, playing);
    }

    private static void bindLibraryMoveRowContent(FrameLayout row, String titleText, String subText,
            boolean moving, boolean highlighted, boolean nowPlaying, boolean playing) {
        if (row == null) return;
        LibraryRowHolder holder = (LibraryRowHolder) row.getTag(R.id.tag_library_row_holder);
        int generation = ThemeManager.getStyleGeneration();
        if (holder != null && holder.styleGeneration != generation) {
            holder.lastTitleColor = Integer.MIN_VALUE;
            holder.lastSubColor = Integer.MIN_VALUE;
            holder.styleGeneration = generation;
        }
        TextView title = holder != null ? holder.title : (TextView) row.findViewWithTag(TAG_TITLE);
        if (title != null) {
            title.setText(titleText != null ? titleText : "");
            int titleColor = highlighted ? ThemeManager.getItemTextColorSelected()
                    : ThemeManager.getItemTextColorNormal();
            if (holder == null || holder.lastTitleColor != titleColor) {
                ThemeManager.applyThemedTextStyle(title, titleColor);
                if (holder != null) holder.lastTitleColor = titleColor;
            }
            title.setSelected(highlighted);
        }
        TextView sub = holder != null ? holder.sub : (TextView) row.findViewWithTag(TAG_SUB);
        if (sub != null) {
            sub.setText(subText != null ? subText : "");
            sub.setSelected(highlighted && subText != null && !subText.isEmpty());
            int subColor = highlighted ? ThemeManager.getItemTextColorSelected()
                    : ThemeManager.getSubtitleTextColor();
            if (holder == null || holder.lastSubColor != subColor) {
                ThemeManager.applyThemedTextStyle(sub, subColor);
                if (holder != null) holder.lastSubColor = subColor;
            }
        }
        TextView grip = holder != null ? holder.grip : (TextView) row.findViewWithTag(TAG_GRIP);
        ImageView pp = holder != null ? holder.playback : (ImageView) row.findViewWithTag(TAG_PP);
        if (grip != null) {
            grip.setVisibility(moving ? View.VISIBLE : View.GONE);
            if (moving) {
                ThemeManager.applyThemedTextStyle(grip, ThemeManager.getItemTextColorSelected());
            }
        }
        if (pp != null) {
            if (moving) {
                pp.setVisibility(View.GONE);
            } else if (nowPlaying) {
                pp.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                pp.setColorFilter(ThemeManager.getItemTextColorNormal(), PorterDuff.Mode.SRC_ATOP);
                pp.setVisibility(View.VISIBLE);
            } else {
                pp.setVisibility(View.GONE);
            }
        }
        if (holder != null) {
            holder.drop.setBackgroundColor(ThemeManager.getItemTextColorSelected());
            holder.drop.setVisibility(moving ? View.VISIBLE : View.GONE);
        } else {
            ensureDropLine(row, moving, ThemeManager.getItemTextColorSelected());
        }
    }

    static void bindEmptySlot(View row) {
        if (row == null) return;
        row.setVisibility(View.INVISIBLE);
        row.setAlpha(0.35f);
        LibraryRowHolder holder = row instanceof FrameLayout
                ? (LibraryRowHolder) row.getTag(R.id.tag_library_row_holder) : null;
        TextView title = holder != null ? holder.title : (TextView) row.findViewWithTag(TAG_TITLE);
        if (title != null) title.setText("");
        TextView sub = holder != null ? holder.sub : (TextView) row.findViewWithTag(TAG_SUB);
        if (sub != null) sub.setText("");
        TextView grip = holder != null ? holder.grip : (TextView) row.findViewWithTag(TAG_GRIP);
        if (grip != null) grip.setVisibility(View.GONE);
        ImageView pp = holder != null ? holder.playback : (ImageView) row.findViewWithTag(TAG_PP);
        if (pp != null) pp.setVisibility(View.GONE);
        ImageView confirm = holder != null ? holder.confirm : (ImageView) row.findViewWithTag(TAG_CONFIRM);
        if (confirm != null) confirm.setVisibility(View.GONE);
        View drop = holder != null ? holder.drop : row.findViewWithTag(TAG_DROP);
        if (drop != null) drop.setVisibility(View.GONE);
    }

    private static void ensureDropLine(FrameLayout row, boolean visible, int color) {
        View drop = row.findViewWithTag(TAG_DROP);
        if (visible) {
            if (drop == null) {
                drop = new View(row.getContext());
                drop.setTag(TAG_DROP);
                float density = row.getResources().getDisplayMetrics().density;
                int h = Math.max(1, (int) (2 * density));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, h);
                lp.gravity = Gravity.BOTTOM;
                row.addView(drop, lp);
            }
            drop.setBackgroundColor(color);
            drop.setVisibility(View.VISIBLE);
        } else if (drop != null) {
            drop.setVisibility(View.GONE);
        }
    }
}
