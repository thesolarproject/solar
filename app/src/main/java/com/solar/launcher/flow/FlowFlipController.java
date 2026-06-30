package com.solar.launcher.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * In-place center-card flip to tracklist (iPod Cover Flow back face).
 * ponytail: manual 16ms tick — no ValueAnimator dependency on API quirks.
 */
public final class FlowFlipController {

    public static final int STATE_IDLE = 0;
    public static final int STATE_FLIP_TO_BACK = 1;
    public static final int STATE_BACK = 2;
    public static final int STATE_FLIP_TO_FRONT = 3;
    public static final int STATE_HANDOFF = 4;

    private static final int FLIP_MS = 280;
    private static final int VISIBLE_BACK_ROWS = 8;

    /** Saved back-face for artist → album drill-down. */
    private static final class BackLevel {
        final String title;
        final String subtitle;
        final List<FlowScreenHost.FlowBackRow> rows;
        final int backIndex;

        BackLevel(String title, String subtitle, List<FlowScreenHost.FlowBackRow> rows, int backIndex) {
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.rows = new ArrayList<FlowScreenHost.FlowBackRow>(rows != null ? rows : Collections.<FlowScreenHost.FlowBackRow>emptyList());
            this.backIndex = backIndex;
        }
    }

    private int state = STATE_IDLE;
    private float flipProgress;
    private long flipStartMs;
    private int backIndex;
    private int scrollOffset;
    private String headerTitle = "";
    private String headerSubtitle = "";
    private final List<FlowScreenHost.FlowBackRow> backRows = new ArrayList<FlowScreenHost.FlowBackRow>();
    private final Stack<BackLevel> backStack = new Stack<BackLevel>();
    private Runnable onFlipComplete;
    private Runnable onHandoffReady;

    public int getState() {
        return state;
    }

    public boolean isFlippedOrFlipping() {
        return state != STATE_IDLE;
    }

    public boolean blocksCarouselScroll() {
        return state == STATE_FLIP_TO_BACK || state == STATE_FLIP_TO_FRONT || state == STATE_HANDOFF;
    }

    /** 0 = front, 1 = fully showing back. */
    public float flipProgress() {
        return flipProgress;
    }

    public int backIndex() {
        return backIndex;
    }

    public String headerTitle() {
        return headerTitle;
    }

    public String headerSubtitle() {
        return headerSubtitle;
    }

    public List<FlowScreenHost.FlowBackRow> backRows() {
        return backRows;
    }

    public boolean hasBackStack() {
        return !backStack.isEmpty();
    }

    public int visibleBackRowStart() {
        if (backRows.size() <= VISIBLE_BACK_ROWS) return 0;
        int maxStart = backRows.size() - VISIBLE_BACK_ROWS;
        if (backIndex < scrollOffset) scrollOffset = backIndex;
        if (backIndex >= scrollOffset + VISIBLE_BACK_ROWS) scrollOffset = backIndex - VISIBLE_BACK_ROWS + 1;
        if (scrollOffset > maxStart) scrollOffset = maxStart;
        if (scrollOffset < 0) scrollOffset = 0;
        return scrollOffset;
    }

    public int visibleBackRowCount() {
        return Math.min(VISIBLE_BACK_ROWS, backRows.size());
    }

    public void reset() {
        state = STATE_IDLE;
        flipProgress = 0f;
        backRows.clear();
        backStack.clear();
        backIndex = 0;
        scrollOffset = 0;
        headerTitle = "";
        headerSubtitle = "";
        onFlipComplete = null;
        onHandoffReady = null;
    }

    public void setBackContent(String title, String subtitle, List<FlowScreenHost.FlowBackRow> rows) {
        backRows.clear();
        if (rows != null) backRows.addAll(rows);
        headerTitle = title != null ? title : "";
        headerSubtitle = subtitle != null ? subtitle : "";
        backIndex = 0;
        scrollOffset = 0;
    }

    /** Push current back-face before drilling into album tracks. */
    public void pushBackLevel() {
        if (state != STATE_BACK || backRows.isEmpty()) return;
        backStack.push(new BackLevel(headerTitle, headerSubtitle, backRows, backIndex));
    }

    /** Pop to parent back-face (artist album list); returns true if restored. */
    public boolean popBackLevel() {
        if (backStack.isEmpty()) return false;
        BackLevel level = backStack.pop();
        backRows.clear();
        backRows.addAll(level.rows);
        headerTitle = level.title;
        headerSubtitle = level.subtitle;
        backIndex = level.backIndex;
        scrollOffset = 0;
        return true;
    }

    public void startFlipToBack(Runnable whenBack) {
        if (backRows.isEmpty()) return;
        onFlipComplete = whenBack;
        state = STATE_FLIP_TO_BACK;
        flipStartMs = System.currentTimeMillis();
        flipProgress = 0f;
    }

    public void startFlipToFront(Runnable whenFront) {
        onFlipComplete = whenFront;
        state = STATE_FLIP_TO_FRONT;
        flipStartMs = System.currentTimeMillis();
        flipProgress = 1f;
    }

    public void startHandoff(Runnable whenReady) {
        onHandoffReady = whenReady;
        state = STATE_HANDOFF;
        flipStartMs = System.currentTimeMillis();
        if (flipProgress < 0.01f) flipProgress = 0f;
    }

    /** Advance flip animation; returns true while still animating. */
    public boolean tick(long nowMs) {
        if (state == STATE_IDLE || state == STATE_BACK || state == STATE_HANDOFF) return false;
        float t = (nowMs - flipStartMs) / (float) FLIP_MS;
        if (t >= 1f) {
            if (state == STATE_FLIP_TO_BACK) {
                state = STATE_BACK;
                flipProgress = 1f;
            } else if (state == STATE_FLIP_TO_FRONT) {
                state = STATE_IDLE;
                flipProgress = 0f;
                backRows.clear();
                backStack.clear();
            }
            Runnable done = onFlipComplete;
            onFlipComplete = null;
            if (done != null) done.run();
            return false;
        }
        float eased = 1f - (1f - t) * (1f - t);
        if (state == STATE_FLIP_TO_BACK) flipProgress = eased;
        else flipProgress = 1f - eased;
        return true;
    }

    /** Clamp at list ends — no wrap on track/album rows. Returns true if index moved. */
    public boolean scrollBackBy(int delta) {
        if (state != STATE_BACK || backRows.isEmpty() || delta == 0) return false;
        int next = backIndex + delta;
        if (next < 0 || next >= backRows.size()) return false;
        backIndex = next;
        return true;
    }

    public FlowScreenHost.FlowBackRow selectedRow() {
        if (backIndex < 0 || backIndex >= backRows.size()) return null;
        return backRows.get(backIndex);
    }

    /** Side carousel dim factor during flip/back. */
    public float sideDimAlpha() {
        if (state == STATE_IDLE) return 1f;
        if (state == STATE_BACK) return 0.4f;
        return 0.4f + 0.6f * (1f - Math.abs(flipProgress * 2f - 1f));
    }

    /** ponytail: test hook. */
    static float easeOutQuad(float t) {
        float inv = 1f - t;
        return 1f - inv * inv;
    }
}
