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

    private static final int FLIP_MS = 340;
    /** ponytail: default until FlowView measures card; no hard cap at 7. */
    private static final int DEFAULT_VISIBLE_ROWS = 99;

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
    private int maxVisibleRows = DEFAULT_VISIBLE_ROWS;
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

  /** Set from {@link FlowView#drawBackFace} so scroll window matches drawn rows. */
    public void setMaxVisibleRows(int max) {
        maxVisibleRows = max < 1 ? 1 : max;
        visibleBackRowStart();
    }

    public int maxVisibleRows() {
        return maxVisibleRows;
    }

    public int visibleBackRowStart() {
        int window = Math.min(maxVisibleRows, backRows.size());
        if (backRows.size() <= window) return 0;
        int maxStart = backRows.size() - window;
        if (backIndex < scrollOffset) scrollOffset = backIndex;
        if (backIndex >= scrollOffset + window) scrollOffset = backIndex - window + 1;
        if (scrollOffset > maxStart) scrollOffset = maxStart;
        if (scrollOffset < 0) scrollOffset = 0;
        return scrollOffset;
    }

    public int visibleBackRowCount() {
        return Math.min(maxVisibleRows, backRows.size());
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
        setBackContent(title, subtitle, rows, 0);
    }

    public void setBackContent(String title, String subtitle, List<FlowScreenHost.FlowBackRow> rows,
            int selectedIndex) {
        backRows.clear();
        if (rows != null) backRows.addAll(rows);
        headerTitle = title != null ? title : "";
        headerSubtitle = subtitle != null ? subtitle : "";
        backIndex = selectedIndex >= 0 && selectedIndex < backRows.size() ? selectedIndex : 0;
        scrollOffset = 0;
        visibleBackRowStart();
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
        visibleBackRowStart();
        return true;
    }

    public FlowScreenHost.FlowBackRow selectedRow() {
        if (backIndex < 0 || backIndex >= backRows.size()) return null;
        return backRows.get(backIndex);
    }

    /** Side carousel dim factor during flip/back (~45–50% at STATE_BACK — visible shadow, not blackout). */
    public float sideDimAlpha() {
        if (state == STATE_IDLE) return 1f;
        if (state == STATE_BACK || state == STATE_HANDOFF) return 0.47f;
        return 1f - 0.53f * flipProgress;
    }

    /** Immutable flip-back snapshot for Now Playing → Flow restore. */
    public static final class Snapshot {
        public final String headerTitle;
        public final String headerSubtitle;
        public final List<FlowScreenHost.FlowBackRow> rows;
        public final int backIndex;
        public final List<BackLevel> stack;

        Snapshot(String headerTitle, String headerSubtitle, List<FlowScreenHost.FlowBackRow> rows,
                int backIndex, List<BackLevel> stack) {
            this.headerTitle = headerTitle != null ? headerTitle : "";
            this.headerSubtitle = headerSubtitle != null ? headerSubtitle : "";
            this.rows = copyRows(rows);
            this.backIndex = backIndex;
            this.stack = stack != null ? stack : Collections.<BackLevel>emptyList();
        }
    }

    public Snapshot captureSnapshot() {
        if (state != STATE_BACK || backRows.isEmpty()) return null;
        return new Snapshot(headerTitle, headerSubtitle, backRows, backIndex, backStack);
    }

    /** Restore flipped tracklist without animation (after returning from Now Playing). */
    public void restoreSnapshot(Snapshot snap) {
        if (snap == null || snap.rows.isEmpty()) {
            reset();
            return;
        }
        applySnapshotContent(snap);
        state = STATE_BACK;
        flipProgress = 1f;
        onFlipComplete = null;
        onHandoffReady = null;
    }

    /** Flip card back in after crossfade from Now Playing (plan 2c). */
    public void animateRestoreSnapshot(Snapshot snap) {
        if (snap == null || snap.rows.isEmpty()) {
            reset();
            return;
        }
        applySnapshotContent(snap);
        state = STATE_IDLE;
        flipProgress = 0f;
        onFlipComplete = null;
        onHandoffReady = null;
        startFlipToBack(null);
    }

    private void applySnapshotContent(Snapshot snap) {
        backRows.clear();
        backRows.addAll(snap.rows);
        headerTitle = snap.headerTitle;
        headerSubtitle = snap.headerSubtitle;
        backIndex = snap.backIndex >= 0 && snap.backIndex < backRows.size() ? snap.backIndex : 0;
        backStack.clear();
        if (snap.stack != null) {
            for (int i = 0; i < snap.stack.size(); i++) {
                backStack.push(snap.stack.get(i));
            }
        }
        scrollOffset = 0;
        visibleBackRowStart();
    }

    private static List<FlowScreenHost.FlowBackRow> copyRows(List<FlowScreenHost.FlowBackRow> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        ArrayList<FlowScreenHost.FlowBackRow> out = new ArrayList<FlowScreenHost.FlowBackRow>(src.size());
        for (FlowScreenHost.FlowBackRow r : src) {
            if (r == null) continue;
            out.add(new FlowScreenHost.FlowBackRow(r.title, r.subtitle, r.track, r.episode,
                    r.nestedItem, r.deezerTrack));
        }
        return out;
    }

    private static List<BackLevel> copyStack(Stack<BackLevel> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        BackLevel[] arr = src.toArray(new BackLevel[src.size()]);
        ArrayList<BackLevel> out = new ArrayList<BackLevel>(arr.length);
        for (int i = arr.length - 1; i >= 0; i--) {
            out.add(arr[i]);
        }
        return out;
    }

    /** ponytail: test hook. */
    static float easeOutQuad(float t) {
        float inv = 1f - t;
        return 1f - inv * inv;
    }
}
