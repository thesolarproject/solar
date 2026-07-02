package com.solar.launcher.flow;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.launcher.ArtistBrowsePolicy;
import com.solar.launcher.AlbumCoverPipeline;
import com.solar.launcher.LibraryBrowsePrefs;
import com.solar.launcher.R;
import com.solar.launcher.DebugSessionLog;
import com.solar.launcher.DebugB8b871Log;
import com.solar.launcher.deezer.DeezerPlaylist;
import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.podcast.OpenRssClient;
import com.solar.launcher.theme.ThemeManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flow screen coordinator: picker, Cover Flow carousel, in-place flip tracklist.
 */
public final class FlowScreenHost implements FlowView.Callback, FlowCoverResolver.Host {

    public interface Actions {
        Activity activity();
        SharedPreferences prefs();
        File musicRoot();
        File getCoversFolder();
        File getAlbumArtCacheDir();
        File getFlowThumbCacheDir();
        File coverFileForTrack(File track);
        LibraryBrowsePrefs libraryBrowsePrefs();
        List<FlowCatalog.SongRow> libraryRows();
        List<ArtistBrowsePolicy.Track> policyTracks();
        List<DeezerPlaylist> deezerPlaylists();
        List<OpenRssClient.Podcast> podcastShows();
        void runOnUi(Runnable r);
        void clickFeedback();
        Button createListButton(String label);
        void configureListButton(Button btn);
        int rowHeightPx();
        void playTracks(List<File> tracks, int startIndex, String label);
        void playPodcastShow(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes, int index);
        void playTracksWithHandoff(List<File> tracks, int startIndex, String label, Bitmap cover,
                RectF fromRect, float fromRotY);
        void playTracksWithCrossfade(List<File> tracks, int startIndex, String label);
        /** Same track already playing — handoff to NP without restarting playback. */
        void resumePlayerWithHandoff(Bitmap cover, RectF fromRect, float fromRotY);
        void resumePlayerWithCrossfade();
        /** NP→Flow reverse morph — layout crossfade + opaque flyer (mirror of resumePlayerWithHandoff). */
        void launchReverseHandoff(Bitmap cover, RectF fromRect, float toRotY, Runnable onComplete);
        /** Live NP album bitmap for reverse handoff, or null. */
        Bitmap playerHandoffCoverBitmap();
        /** NP album slot screen rect — measured before slot is hidden. */
        RectF resolveNpHandoffFromScreenRect();
        /** Force Flow + carousel measure before reverse morph frame 0. */
        void ensureHandoffLayoutMeasured();
        void playPodcastWithHandoff(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes,
                int index, Bitmap cover, RectF fromRect, float fromRotY);
        void playPodcastWithCrossfade(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes,
                int index);
        void playDeezerTracks(List<DeezerResult> tracks, int startIndex);
        void fetchPodcastEpisodes(String feedUrl, EpisodeCallback callback);
        void fetchDeezerPlaylistTracks(long playlistId, PlaylistTracksCallback callback);
        void saveLastFlowIndex(FlowMode mode, int index);
        int loadLastFlowIndex(FlowMode mode);
        void saveLastFlowMatchKey(FlowMode mode, String matchKey);
        String loadLastFlowMatchKey(FlowMode mode);
        boolean isDebugFlowTheme();
        boolean isDebugFlowNoReflections();
        boolean isFlowOkOpensLibrary();
        void openLibraryBrowse(FlowItem item);
        boolean isNowPlaying3dAlbumArt();
        /** True when 3D handoff is allowed (3D on, LCD off, not both theme-forced conflict). */
        boolean isFlow3dHandoffEnabled();
        void exitFlowToMainMenu();
        boolean isNowPlayingLcdArt();
        void exitFlowScreen();
        void showFlowLoading(String text);
        void hideFlowLoading();
        int libraryScanGeneration();
        boolean flowMultiTrackAlbumsOnly();
        /** True when this file is the active music queue track. */
        boolean isSamePlayingTrack(File track);
        /** True when local music is actively playing (not podcast). */
        boolean isMusicPlaybackActive();
        /** Catalog-aligned rack key for the active music track, or empty. */
        String nowPlayingCarouselMatchKey(List<FlowItem> catalog);
        /** True during Flow↔NP 3D flyer morph. */
        boolean isFlowHandoffAnimating();
        /**
         * Flow Back → NP handoff: drop flip return snapshot so NP Back does not re-enter Flow.
         */
        void releaseFlowNpBackLoop();
        /** Debug session — USB/Wi-Fi/scan fields for perf logs. */
        void appendFlowDebugContext(org.json.JSONObject d) throws org.json.JSONException;
    }

    public interface EpisodeCallback {
        void onEpisodes(List<OpenRssClient.Episode> episodes, String error);
    }

    public interface PlaylistTracksCallback {
        void onTracks(List<DeezerResult> tracks, String error);
    }

    private static final int UI_PICKER = 0;
    private static final int UI_CAROUSEL = 1;
    /** Background disk warm, thumb bake, reverse-handoff prep — never block UI or onDraw. */
    private static final ExecutorService FLOW_WORKER = Executors.newSingleThreadExecutor();

    private final Actions actions;
    private final FlowCoverCache coverCache = new FlowCoverCache();
    private final FlowCatalogSessionCache catalogSessionCache = new FlowCatalogSessionCache();
    private final CoverLoadGovernor coverGovernor = new CoverLoadGovernor();
    private View root;
    private FlowView flowView;
    private ScrollView pickerScroll;
    private LinearLayout pickerContainer;

    private FlowLaunchRequest launchRequest;
    private FlowMode activeMode = FlowMode.UNSPECIFIED;
    private int uiMode = UI_PICKER;
    private int pickerIndex;
    private int coverGen;
    private int flipGen;
    private List<FlowItem> catalog = new ArrayList<FlowItem>();
    private FlowItem focusedItem;
    private FlowReturnState returnAfterPlayer;
    private boolean flowReturnPrepared;
    private int carouselLoadGen;
    private boolean flowLoadingVisible;
    private int catalogBuildGen;
    private int incrementalThumbCursor;
    private int lastLoggedGovernorDepth = -1;
    private long lastScrollPerfLogMs;
    private int scrollPerfLogCount;
    /** Pinned NP art for reverse handoff — never show placeholder on center until morph ends. */
    private Bitmap handoffPinnedCover;
    private String handoffPinnedCoverKey;
    private boolean reverseHandoffActive;
    /** Last measured center cover rect — NP→Flow landing when flip snapshot missing. */
    private RectF rememberedCenterHandoffRect;
    /**
     * NP→Flow entry: Back on carousel returns to Now Playing (scroll to playing album first).
     * Cleared on normal Flow exit or when flip play captures a new return snapshot.
     */
    private String nowPlayingBackMatchKey;

    public void enableNowPlayingBackReturn(String matchKey) {
        if (matchKey == null || matchKey.isEmpty()) {
            clearNowPlayingBackReturn();
            return;
        }
        nowPlayingBackMatchKey = matchKey;
    }

    public void clearNowPlayingBackReturn() {
        nowPlayingBackMatchKey = null;
        if (flowView != null) flowView.cancelGuidedScroll();
    }

    public boolean isNowPlayingBackReturnEnabled() {
        return nowPlayingBackMatchKey != null && !nowPlayingBackMatchKey.isEmpty();
    }

    public boolean isReverseHandoffActive() {
        return reverseHandoffActive;
    }

    /**
     * Copy NP cover for flyer + carousel center — RGB_565 at Flow thumb size.
     * @return pinned bitmap (may be same reference stored in cache)
     */
    public Bitmap pinHandoffCover(Bitmap source, String coverKey) {
        releaseHandoffPinBitmap();
        reverseHandoffActive = true;
        handoffPinnedCoverKey = coverKey;
        if (source == null || source.isRecycled()) {
            if (flowView != null) flowView.clearHandoffPin();
            return null;
        }
        int thumb = coverCache.thumbSizePx(480f, 360f);
        handoffPinnedCover = AlbumCoverPipeline.scaleForFlow(source, thumb, thumb);
        if (coverKey != null && !coverKey.isEmpty()) {
            coverCache.put(coverKey, handoffPinnedCover);
        }
        if (flowView != null) {
            flowView.setHandoffPin(coverKey, handoffPinnedCover);
        }
        return handoffPinnedCover;
    }

    public Bitmap getHandoffPinnedCover() {
        return handoffPinnedCover != null && !handoffPinnedCover.isRecycled()
                ? handoffPinnedCover : null;
    }

    public String getHandoffPinnedCoverKey() {
        return handoffPinnedCoverKey;
    }

    public void endReverseHandoff() {
        reverseHandoffActive = false;
        releaseHandoffPinBitmap();
        if (flowView != null) flowView.clearHandoffPin();
    }

    private void releaseHandoffPinBitmap() {
        handoffPinnedCoverKey = null;
        handoffPinnedCover = null;
    }

    public void rememberCenterHandoffRect(RectF rect) {
        if (rect != null && rect.width() > 0f) {
            if (rememberedCenterHandoffRect == null) {
                rememberedCenterHandoffRect = new RectF();
            }
            rememberedCenterHandoffRect.set(rect);
        }
    }

    public RectF getRememberedCenterHandoffRect() {
        return rememberedCenterHandoffRect != null && rememberedCenterHandoffRect.width() > 0f
                ? new RectF(rememberedCenterHandoffRect) : null;
    }

    public boolean hasPendingPlayerReturn() {
        return returnAfterPlayer != null;
    }

    /** True when Back from NP should restore a flipped album face (flip-select → play → Back). */
    public boolean hasFlipReturnSnapshot() {
        return returnAfterPlayer != null && returnAfterPlayer.flipSnapshot != null;
    }

    public void clearPlayerReturn() {
        returnAfterPlayer = null;
    }

    /** Bumped after library scan — session catalog must rebuild. */
    public void invalidateCatalogSession() {
        catalogSessionCache.clear();
        catalogBuildGen++;
    }

    /**
     * Background precook after library scan — ALBUM catalog + disk cover warm for last focus index.
     * ponytail: ALBUM mode only; other modes build on first open.
     */
    public void precookCatalogAfterLibraryScan(final int libGen, final int optionsKey) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("libGen", libGen);
            d.put("optionsKey", optionsKey);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.precookCatalogAfterLibraryScan",
                    "precook queued", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.currentTimeMillis();
                final List<FlowItem> built = buildCatalog(FlowMode.ALBUM, libGen, optionsKey);
                catalogSessionCache.put(FlowMode.ALBUM, libGen, optionsKey, built);
                // Worker-safe focus — matchKey before stale numeric index.
                int center = new FlowEngine().findIndexForKey(built,
                        actions.loadLastFlowMatchKey(FlowMode.ALBUM));
                if (center < 0) {
                    int last = actions.loadLastFlowIndex(FlowMode.ALBUM);
                    center = last >= 0 && last < built.size() ? last : 0;
                }
                warmCatalogCoversSync(built, center, 2);
                final int bakeCenter = center;
                final long buildMs = System.currentTimeMillis() - t0;
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("libGen", libGen);
                    d.put("itemCount", built != null ? built.size() : -1);
                    d.put("buildMs", buildMs);
                    com.solar.launcher.Debug898913Log.log("FlowScreenHost.precookCatalogAfterLibraryScan",
                            "precook built", "H2", d);
                } catch (Exception ignored) {}
                // #endregion
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (flowView != null) flowView.scheduleBakesAround(bakeCenter, 2);
                        // Single rebind after scan — avoids duplicate FLOW_WORKER rebuild from MainActivity.
                        if (uiMode == UI_CAROUSEL && activeMode == FlowMode.ALBUM) {
                            rebindCarouselFromPrefs(FlowMode.ALBUM,
                                    focusedItem != null ? focusedItem.matchKey : null, false);
                        }
                    }
                });
            }
        });
    }

    /** Store resolved catalog for reuse — reverse handoff hot path. */
    public void ensureSessionCatalog(FlowMode mode, List<FlowItem> items) {
        if (mode == null || items == null || items.isEmpty()) return;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = catalogOptionsKey();
        catalogSessionCache.put(mode, libGen, optionsKey, items);
    }

    /** Build + store session catalog on caller thread (worker-safe). */
    public List<FlowItem> buildAndCacheCatalog(FlowMode mode) {
        if (mode == null || mode == FlowMode.UNSPECIFIED) mode = FlowMode.ALBUM;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = catalogOptionsKey();
        List<FlowItem> built = buildCatalog(mode, libGen, optionsKey);
        catalogSessionCache.put(mode, libGen, optionsKey, built);
        return built;
    }

    /** Debug pref — clears bake cache and rebakes when reflections re-enabled. */
    public void applyNoReflectionsPref(boolean on) {
        if (flowView != null) flowView.setNoReflections(on);
    }

    /** Disk warm on worker — safe during reverse handoff morph. */
    public void warmCatalogCoversOnWorker(List<FlowItem> items, int center, int radius) {
        if (items == null || items.isEmpty()) return;
        warmCatalogCoversSync(items, center, radius);
    }

    /**
     * Flip-return prep on worker when catalog cold — morph can start before this finishes.
     */
    public void preparePlayerReturnForHandoffAsync(final Bitmap handoffCover) {
        if (returnAfterPlayer == null || flowView == null) return;
        if (applyReturnStateIfResident(returnAfterPlayer, false)) {
            finishPlayerReturnHandoffPrep(handoffCover);
            return;
        }
        final FlowReturnState saved = returnAfterPlayer;
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                final List<FlowItem> built = catalogForReturn(saved);
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (returnAfterPlayer != saved) return;
                        applyReturnState(saved, false, built);
                        finishPlayerReturnHandoffPrep(handoffCover);
                    }
                });
            }
        });
    }

    private void finishPlayerReturnHandoffPrep(Bitmap handoffCover) {
        flowView.snapCarouselToVisualCenter();
        FlowItem item = flowView.itemAt(flowView.engine().getFocusIndex());
        String key = handoffPinnedCoverKey;
        if (key == null || key.isEmpty()) {
            key = item != null ? item.coverKey : null;
        }
        if (handoffCover != null && !handoffCover.isRecycled()) {
            pinHandoffCover(handoffCover, key);
            if (item != null && item.coverKey != null && !item.coverKey.isEmpty()
                    && key != null && !key.equals(item.coverKey) && handoffPinnedCover != null) {
                coverCache.put(item.coverKey, handoffPinnedCover);
            }
        } else if (key != null && handoffPinnedCover != null) {
            coverCache.put(key, handoffPinnedCover);
        }
        flowReturnPrepared = true;
        flowView.prepareHandoffFlyerOnly();
    }

    /** Disk warm for side covers after reverse handoff — center already pinned during morph. */
    public void warmSidesAfterReverseHandoff() {
        if (catalog == null || catalog.isEmpty() || flowView == null) return;
        final int center = flowView.engine().getFocusIndex();
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                warmCatalogCoversSync(catalog, center, 2);
                final int bakeCenter = center;
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (flowView != null) flowView.scheduleBakesAround(bakeCenter, 2);
                    }
                });
            }
        });
    }

    @Override
    public void scheduleCenterBakes(int center, int radius) {
        if (flowView != null) flowView.scheduleBakesAround(center, radius);
    }

    @Override
    public void onCarouselScrollStarted() {
        // NP handoff pin can desync neighbor slots — catalog art only once user scrolls.
        if (handoffPinnedCover != null || reverseHandoffActive) {
            reverseHandoffActive = false;
            releaseHandoffPinBitmap();
            if (flowView != null) flowView.clearHandoffPin();
        }
    }

    public static final class FlowBackRow {
        public final String title;
        public final String subtitle;
        public final File track;
        public final OpenRssClient.Episode episode;
        /** Album drill-down from artist flip-back. */
        public final FlowItem nestedItem;
        public final DeezerResult deezerTrack;

        public FlowBackRow(String title, String subtitle, File track, OpenRssClient.Episode episode) {
            this(title, subtitle, track, episode, null, null);
        }

        public FlowBackRow(String title, String subtitle, File track, OpenRssClient.Episode episode,
                FlowItem nestedItem, DeezerResult deezerTrack) {
            this.title = title;
            this.subtitle = subtitle;
            this.track = track;
            this.episode = episode;
            this.nestedItem = nestedItem;
            this.deezerTrack = deezerTrack;
        }
    }

    public FlowScreenHost(Actions actions) {
        this.actions = actions;
        coverGovernor.setCallback(new CoverLoadGovernor.Callback() {
            @Override
            public void onDecoded(String coverKey, Bitmap bitmap) {
                if (bitmap == null) return;
                coverCache.put(coverKey, bitmap);
                if (flowView != null) flowView.postInvalidateOnAnimation();
            }
        });
        coverGovernor.setFrameKick(new Runnable() {
            @Override
            public void run() {
                if (flowView != null) flowView.scheduleCarouselFrame();
            }
        });
        startIncrementalThumbBakeLoop();
    }

    /** Rockbox incremental_albumart_cache — off anim tick, low-priority background thread. */
    private void startIncrementalThumbBakeLoop() {
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                boolean backlog = incrementalFlowThumbBakeOne();
                try {
                    Thread.sleep(backlog ? 50L : 200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                FLOW_WORKER.execute(this);
            }
        });
    }

    public void bind(View rootView, FlowView view, ScrollView picker, LinearLayout pickerRows,
            LinearLayout backHostLayout, ScrollView backListScroll, LinearLayout backList, TextView backHeader) {
        root = rootView;
        flowView = view;
        pickerScroll = picker;
        pickerContainer = pickerRows;
        flowView.setCoverCache(coverCache);
        flowView.setCallback(this);
        flowView.setDebugTheme(actions.isDebugFlowTheme());
        flowView.setNoReflections(actions.isDebugFlowNoReflections());
    }

    public void open(FlowLaunchRequest req) {
        launchRequest = req;
        returnAfterPlayer = null;
        coverGen++;
        coverGovernor.cancelAll();
        flowView.resetFlip();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("focusKey", req.focusKey != null ? req.focusKey : "");
            d.put("enteredFromSection", req.enteredFromSection);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.open", "open", "H-FLOW", d);
        } catch (Exception ignored) {}
        // #endregion
        flowView.resetHandoffRevealForDisplay();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("skipPicker", req.skipPicker());
            d.put("mode", req.mode != null ? req.mode.name() : "null");
            d.put("libRows", actions.libraryRows().size());
            DebugSessionLog.log("FlowScreenHost.open", "host open", "H1-H3", d);
        } catch (Exception ignored) {}
        // #endregion
        showCarousel(FlowMode.ALBUM, req.focusKey);
    }

    /** Back from Now Playing — restore carousel on played rack item (front cover). */
    public void restoreAfterPlayer() {
        if (launchRequest == null) return;
        FlowReturnState saved = returnAfterPlayer;
        returnAfterPlayer = null;
        if (saved == null) {
            restoreCarouselSession(launchRequest);
            return;
        }
        if (!applyReturnStateIfResident(saved, true)) {
            applyReturnState(saved, true);
        }
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("index", flowView.engine().getFocusIndex());
            d.put("matchKey", saved.carouselMatchKey);
            d.put("backIndex", saved.flipSnapshot != null ? saved.flipSnapshot.backIndex : -1);
            d.put("flipped", saved.flipSnapshot != null);
            DebugSessionLog.log("FlowScreenHost.restoreAfterPlayer", "flow restored", "H-FLOW-BACK", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Carousel under hidden Flow layout before reverse handoff — front cover, no flip-in. */
    public void preparePlayerReturnForHandoff() {
        preparePlayerReturnForHandoff(null);
    }

    public void preparePlayerReturnForHandoff(Bitmap handoffCover) {
        if (returnAfterPlayer == null || flowView == null) return;
        // #region agent log
        long prepStartMs = System.currentTimeMillis();
        try {
            JSONObject d = new JSONObject();
            d.put("matchKey", returnAfterPlayer.carouselMatchKey);
            DebugSessionLog.log("FlowScreenHost.preparePlayerReturnForHandoff", "prep start", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
        if (!applyReturnStateIfResident(returnAfterPlayer, false)) {
            applyReturnState(returnAfterPlayer, false);
        }
        finishPlayerReturnHandoffPrep(handoffCover);
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("prepMs", System.currentTimeMillis() - prepStartMs);
            DebugSessionLog.log("FlowScreenHost.preparePlayerReturnForHandoff", "prep done", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Library-origin return — synthesize carousel pose before reverse handoff. */
    public void prepareReturnForResolvedState(FlowReturnState state) {
        prepareReturnForResolvedState(state, null);
    }

    public void prepareReturnForResolvedState(FlowReturnState state, List<FlowItem> catalogHint) {
        prepareReturnForResolvedState(state, catalogHint, null);
    }

    /**
     * @param handoffCover NP album bitmap — seed center slot so reveal crossfade matches flyer art.
     */
    public void prepareReturnForResolvedState(FlowReturnState state, List<FlowItem> catalogHint,
            Bitmap handoffCover) {
        if (state == null || flowView == null) return;
        returnAfterPlayer = state;
        applyReturnState(state, false, catalogHint);
        flowView.snapCarouselToVisualCenter();
        FlowItem item = flowView.itemAt(flowView.engine().getFocusIndex());
        // Keep pin key stable — matchKey from return state, not item.coverKey after catalog align.
        String pinKey = handoffPinnedCoverKey;
        if (pinKey == null || pinKey.isEmpty()) {
            pinKey = state.carouselMatchKey;
        }
        if ((pinKey == null || pinKey.isEmpty()) && item != null) {
            pinKey = item.coverKey;
        }
        if (handoffCover != null && !handoffCover.isRecycled()) {
            pinHandoffCover(handoffCover, pinKey);
            if (item != null && item.coverKey != null && !item.coverKey.isEmpty()
                    && !item.coverKey.equals(pinKey) && handoffPinnedCover != null) {
                coverCache.put(item.coverKey, handoffPinnedCover);
            }
        }
        flowView.prepareHandoffFlyerOnly();
        flowReturnPrepared = true;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("catalogSize", catalog != null ? catalog.size() : 0);
            d.put("reverseActive", reverseHandoffActive);
            d.put("matchKey", state != null ? state.carouselMatchKey : "");
            DebugSessionLog.log(
                    "FlowScreenHost.prepareReturnForResolvedState", "carousel prep", "H-D", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /**
     * Cold NP→Flow — bind carousel (minimal shell if needed) so flyer has a real landing rect.
     */
    public void prepareImmediateReverseHandoffShell(Bitmap handoffCover, String coverKey) {
        if (flowView == null) return;
        uiMode = UI_CAROUSEL;
        activeMode = FlowMode.ALBUM;
        if (pickerScroll != null) pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        List<FlowItem> cached = peekSessionCatalog(FlowMode.ALBUM);
        boolean bound = false;
        if (cached != null && !cached.isEmpty() && coverKey != null && !coverKey.isEmpty()) {
            int idx = flowView.engine().findIndexForKey(cached, coverKey);
            if (idx >= 0) {
                catalog = cached;
                flowView.setItemsAndFocus(cached, idx);
                focusedItem = cached.get(idx);
                bound = true;
            }
        }
        if (!bound) {
            String key = coverKey != null ? coverKey : "";
            FlowItem shell = FlowItem.album(key, "", key,
                    java.util.Collections.<java.io.File>emptyList(), "");
            catalog = java.util.Collections.singletonList(shell);
            flowView.setItemsAndFocus(catalog, 0);
            focusedItem = shell;
        }
        if (handoffCover != null && !handoffCover.isRecycled()) {
            pinHandoffCover(handoffCover, coverKey);
        }
        flowView.prepareHandoffFlyerOnly();
        flowView.invalidate();
        flowReturnPrepared = true;
    }

    /** True when carousel has items and a measured center-cover landing rect. */
    public boolean hasLiveHandoffLandingRect() {
        if (flowView == null || catalog == null || catalog.isEmpty()) return false;
        RectF rect = flowView.getHandoffCoverScreenRect();
        return rect != null && rect.width() > 0f;
    }

    /** Force FlowView measure so handoff landing rect is valid before reverse morph frame 0. */
    public void ensureCarouselLayoutForHandoff() {
        if (flowView == null) return;
        flowView.setVisibility(View.VISIBLE);
        View parent = (View) flowView.getParent();
        int w = parent != null ? parent.getWidth() : 0;
        int h = parent != null ? parent.getHeight() : 0;
        if (w <= 0 || h <= 0) {
            w = flowView.getWidth();
            h = flowView.getHeight();
        }
        if (w <= 0 || h <= 0) return;
        flowView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        flowView.layout(0, 0, w, h);
        flowView.invalidate();
    }

    /** Stagger album title fade with reverse handoff reveal crossfade. */
    public void beginHandoffReveal() {
        if (flowView != null) flowView.beginHandoffReveal();
    }

    public void setHandoffRevealProgress(float eased) {
        if (flowView != null) flowView.setHandoffRevealProgress(eased);
    }

    public void setHandoffLandingCrossfade(float flyerAlpha, float carouselAlpha) {
        if (flowView != null) {
            flowView.setHandoffCenterRevealAlpha(carouselAlpha);
            // Measured morph keeps labels visible — only unmeasured swap fades title chrome.
            if (flyerAlpha < 1f) {
                flowView.setHandoffRevealProgress(carouselAlpha);
            }
        }
    }

    public void setHandoffSideRevealAlpha(float alpha) {
        if (flowView != null) flowView.setHandoffSideRevealAlpha(alpha);
    }

    public void finishHandoffChromeReveal() {
        if (flowView != null) flowView.finishHandoffChromeReveal();
    }

    public void finishHandoffLanding() {
        if (flowView != null) flowView.finishHandoffLanding();
        endReverseHandoff();
    }

    public void finishHandoffReveal() {
        finishHandoffChromeReveal();
        finishHandoffLanding();
    }

    /** @deprecated use {@link #beginHandoffReveal()} */
    public void beginHandoffTitleReveal() {
        beginHandoffReveal();
    }

    /**
     * Back from player without saved return state — carousel, never the mode picker.
     */
    public void restoreCarouselSession(FlowLaunchRequest req) {
        if (req == null) return;
        launchRequest = req;
        FlowMode mode = activeMode != FlowMode.UNSPECIFIED ? activeMode : req.mode;
        if (mode == FlowMode.UNSPECIFIED) {
            mode = FlowMode.ALBUM;
        }
        String focusKey = focusedItem != null ? focusedItem.matchKey : req.focusKey;
        uiMode = UI_CAROUSEL;
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        if (!catalog.isEmpty() && activeMode == mode) {
            hideCarouselLoading();
            flowView.requestFocus();
            return;
        }
        showCarousel(mode, focusKey);
    }

    /** Record carousel pose before leaving Flow for Now Playing. */
    public void capturePlayerReturn(RectF handoffFromRect, float handoffFromRotY,
            RectF handoffReturnRect, float handoffReturnRotY) {
        clearNowPlayingBackReturn();
        returnAfterPlayer = captureReturnState(handoffFromRect, handoffFromRotY,
                handoffReturnRect, handoffReturnRotY);
        rememberCenterHandoffRect(handoffReturnRect);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hasState", returnAfterPlayer != null);
            d.put("matchKey", returnAfterPlayer != null ? returnAfterPlayer.carouselMatchKey : "");
            d.put("returnRectW", handoffReturnRect != null ? handoffReturnRect.width() : 0f);
            DebugSessionLog.log("FlowScreenHost.capturePlayerReturn", "captured", "H-E", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    public void finishPlayerReturnAfterHandoff() {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hadState", returnAfterPlayer != null);
            DebugSessionLog.log("FlowScreenHost.finishPlayerReturnAfterHandoff", "cleared", "H-E", d);
        } catch (Exception ignored) {}
        // #endregion
        returnAfterPlayer = null;
        // flowReturnPrepared cleared by changeScreen → consumeReturnPrepared()
    }

    /** True when reverse handoff already restored carousel — skip open() on changeScreen. */
    public boolean consumeReturnPrepared() {
        if (!flowReturnPrepared) return false;
        flowReturnPrepared = false;
        return true;
    }

    public void requestCarouselFocus() {
        if (flowView != null) flowView.requestFocus();
    }

    public String getPendingReturnCoverKey() {
        return returnAfterPlayer != null ? returnAfterPlayer.carouselMatchKey : null;
    }

    public RectF getHandoffCoverScreenRect() {
        RectF rect = flowView != null ? flowView.getHandoffCoverScreenRect() : null;
        if (rect != null && rect.width() > 0f) {
            rememberCenterHandoffRect(rect);
        }
        return rect;
    }

    public RectF getHandoffFromScreenRect() {
        return flowView != null ? flowView.getHandoffFromScreenRect() : null;
    }

    public Bitmap getHandoffCoverBitmap() {
        return flowView != null ? flowView.getCenterCoverBitmap() : null;
    }

    public float getHandoffStartRotationY() {
        return flowView != null ? flowView.getHandoffStartRotationY() : 0f;
    }

    private boolean applyReturnStateIfResident(FlowReturnState saved, boolean requestFocus) {
        if (saved == null || flowView == null || catalog == null || catalog.isEmpty()) {
            return false;
        }
        if (activeMode != saved.mode) return false;
        int index = saved.carouselIndex;
        if (saved.carouselMatchKey != null && !saved.carouselMatchKey.isEmpty()) {
            int found = flowView.engine().findIndexForKey(catalog, saved.carouselMatchKey);
            if (found < 0) return false;
            index = found;
        }
        if (index < 0 || index >= catalog.size()) return false;
        uiMode = UI_CAROUSEL;
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        flowView.engine().setFocusIndex(index);
        focusedItem = flowView.itemAt(index);
        flowView.restoreBackFace(null);
        flowView.prefetchAround(index);
        flowView.invalidate();
        if (requestFocus) flowView.requestFocus();
        return true;
    }

    private void applyReturnState(FlowReturnState saved, boolean requestFocus) {
        applyReturnState(saved, requestFocus, null);
    }

    private void applyReturnState(FlowReturnState saved, boolean requestFocus, List<FlowItem> catalogHint) {
        // #region agent log
        long applyStartMs = System.currentTimeMillis();
        // #endregion
        uiMode = UI_CAROUSEL;
        activeMode = saved.mode;
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        if (catalogHint != null && !catalogHint.isEmpty()) {
            catalog = catalogHint;
            ensureSessionCatalog(saved.mode, catalogHint);
        } else {
            catalog = catalogForReturn(saved);
        }
        int index = saved.carouselIndex;
        if (saved.carouselMatchKey != null && !saved.carouselMatchKey.isEmpty()) {
            int found = flowView.engine().findIndexForKey(catalog, saved.carouselMatchKey);
            if (found >= 0) index = found;
        }
        if (index < 0 || index >= catalog.size()) index = 0;
        flowView.setItemsAndFocus(catalog, index);
        focusedItem = flowView.itemAt(index);
        flowView.restoreBackFace(null);
        syncCarouselToNowPlayingIfActive();
        warmCatalogCoversAsync(catalog, carouselWarmCenter(), 3);
        flowView.invalidate();
        if (requestFocus) flowView.requestFocus();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("durationMs", System.currentTimeMillis() - applyStartMs);
            d.put("catalogSize", catalog != null ? catalog.size() : 0);
            d.put("requestFocus", requestFocus);
            d.put("catalogHint", catalogHint != null && !catalogHint.isEmpty());
            d.put("libRows", actions.libraryRows().size());
            DebugSessionLog.log("FlowScreenHost.applyReturnState", "applyReturnState done", "H-PERF", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private FlowReturnState captureReturnState(RectF handoffFromRect, float handoffFromRotY,
            RectF handoffReturnRect, float handoffReturnRotY) {
        if (uiMode != UI_CAROUSEL || flowView == null) return null;
        FlowItem item = flowView.itemAt(flowView.engine().getFocusIndex());
        FlowFlipController.Snapshot flip = flowView.isFlipped()
                ? flowView.flipController().captureSnapshot() : null;
        return new FlowReturnState(activeMode,
                item != null ? item.matchKey : null,
                flowView.engine().getFocusIndex(),
                flip, handoffFromRect, handoffFromRotY,
                handoffReturnRect, handoffReturnRotY);
    }

    public RectF getSavedHandoffReturnRect() {
        return returnAfterPlayer != null ? returnAfterPlayer.handoffReturnRect : null;
    }

    public float getSavedHandoffReturnRotY() {
        return returnAfterPlayer != null ? returnAfterPlayer.handoffReturnRotY : 0f;
    }

    public void teardown() {
        coverGen++;
        hideCarouselLoading();
        coverGovernor.cancelAll();
        catalogSessionCache.clear();
        coverCache.clear();
        if (flowView != null) flowView.resetFlip();
    }

    public void invalidateCatalogCache() {
        catalogSessionCache.clear();
    }

    /** Drop in-memory carousel bitmaps after disk art cache wipe (Settings reset). */
    public void invalidateCoverCaches() {
        coverGen++;
        coverGovernor.cancelAll();
        coverCache.clear();
        if (flowView != null) flowView.postInvalidateOnAnimation();
    }

    /** Session-precooked carousel — hot path for NP→Flow reverse handoff. */
    public List<FlowItem> peekSessionCatalog(FlowMode mode) {
        if (mode == null || mode == FlowMode.UNSPECIFIED) mode = FlowMode.ALBUM;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = catalogOptionsKey();
        return catalogSessionCache.peek(mode, libGen, optionsKey);
    }

    /** Stale session catalog — only when libGen/options still match last rack build. */
    public List<FlowItem> peekStaleSessionCatalog(FlowMode mode) {
        if (mode == null || mode == FlowMode.UNSPECIFIED) mode = FlowMode.ALBUM;
        return catalogSessionCache.peekStale(mode, actions.libraryScanGeneration(), catalogOptionsKey());
    }

    /**
     * NP→Flow crossfade prep — restore carousel pose, warm center art, pin NP cover for seamless fade.
     * @return true when carousel items are bound and ready to draw under the crossfade
     */
    public boolean prepareNpToFlowCrossfade(String focusKey, Bitmap npCover) {
        if (flowView == null) return false;
        flowView.resetHandoffRevealForDisplay();
        uiMode = UI_CAROUSEL;
        activeMode = FlowMode.ALBUM;
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);

        String effectiveKey = focusKey;
        boolean bound = false;
        // Back from NP after Flow→NP — restore saved carousel index/pose before crossfade.
        if (returnAfterPlayer != null) {
            if (returnAfterPlayer.carouselMatchKey != null
                    && !returnAfterPlayer.carouselMatchKey.isEmpty()) {
                effectiveKey = returnAfterPlayer.carouselMatchKey;
            }
            if (applyReturnStateIfResident(returnAfterPlayer, false)) {
                bound = true;
            } else {
                // ponytail: session/stale cache only — never FlowCatalog.build on UI thread.
                List<FlowItem> items = peekSessionCatalog(FlowMode.ALBUM);
                if (items == null || items.isEmpty()) {
                    items = peekStaleSessionCatalog(FlowMode.ALBUM);
                }
                if (items != null && !items.isEmpty()) {
                    carouselLoadGen++;
                    bindCarouselCatalog(items, FlowMode.ALBUM, effectiveKey, carouselLoadGen);
                    bound = true;
                }
            }
        }
        if (!bound) {
            List<FlowItem> items = peekSessionCatalog(FlowMode.ALBUM);
            if (items == null || items.isEmpty()) {
                items = peekStaleSessionCatalog(FlowMode.ALBUM);
            }
            if (items != null && !items.isEmpty()) {
                carouselLoadGen++;
                bindCarouselCatalog(items, FlowMode.ALBUM, effectiveKey, carouselLoadGen);
                bound = true;
            } else if (!catalog.isEmpty() && activeMode == FlowMode.ALBUM) {
                if (!syncCarouselToNowPlayingIfActive()
                        && effectiveKey != null && !effectiveKey.isEmpty()) {
                    int found = flowView.engine().findIndexForKey(catalog, effectiveKey);
                    if (found >= 0) {
                        flowView.engine().setFocusIndex(found);
                        focusedItem = flowView.itemAt(found);
                    }
                }
                bound = true;
            }
        }
        if (!bound) {
            return false;
        }
        int center = carouselWarmCenter();
        if (catalog != null && !catalog.isEmpty()) {
            warmCatalogCoversAsync(catalog, center, 3);
        }
        // NP cover visible on player — seed center slot so crossfade never flashes grey placeholder.
        if (npCover != null && !npCover.isRecycled()) {
            FlowItem item = flowView.itemAt(center);
            String pinKey = item != null && item.coverKey != null && !item.coverKey.isEmpty()
                    ? item.coverKey : effectiveKey;
            Bitmap pinned = pinHandoffCover(npCover, pinKey);
            if (pinned != null && item != null) {
                if (item.matchKey != null && !item.matchKey.isEmpty()
                        && !item.matchKey.equals(pinKey)) {
                    coverCache.put(item.matchKey, pinned);
                }
            }
        }
        // changeScreen must not rebuild carousel — already bound for crossfade.
        flowReturnPrepared = true;
        flowView.invalidate();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("focusKey", effectiveKey != null ? effectiveKey : "");
            d.put("catalogSize", catalog != null ? catalog.size() : 0);
            d.put("hadReturn", returnAfterPlayer != null);
            d.put("pinned", npCover != null);
            d.put("centerReady", flowView.centerCoverReadyForRevealDebug());
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.prepareNpToFlowCrossfade",
                    "carousel pre-bound for crossfade", "H-C,H-BACK", d);
        } catch (Exception ignored) {}
        // #endregion
        return catalog != null && !catalog.isEmpty();
    }

    /**
     * Crossfade landed — keep NP pin until cache serves center; no reflection fade choreography.
     */
    public void completeNpToFlowCrossfade() {
        if (flowView == null) return;
        flowView.resetHandoffRevealForDisplay();
        final int center = carouselWarmCenter();
        flowView.scheduleBakesAround(center, 2);
        flowView.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (flowView != null && flowView.centerCoverReadyForRevealDebug()) {
                    endReverseHandoff();
                }
                if (flowView != null) flowView.invalidate();
            }
        });
    }

    /** True when carousel can bind without a background catalog build. */
    public boolean hasCachedCatalog(FlowMode mode) {
        List<FlowItem> cached = peekSessionCatalog(mode);
        if (cached != null && !cached.isEmpty()) return true;
        return catalogSessionCache.peekStale(mode, actions.libraryScanGeneration(), catalogOptionsKey()) != null;
    }

    /** True when resident carousel can resume without cold {@link #open}. */
    public boolean hasResidentCarousel(FlowLaunchRequest req) {
        if (req == null || flowView == null || uiMode != UI_CAROUSEL) return false;
        FlowMode mode = req.mode != null && req.mode != FlowMode.UNSPECIFIED
                ? req.mode : FlowMode.ALBUM;
        return !catalog.isEmpty() && activeMode == mode;
    }

    /**
     * Hot re-enter Flow — keep carousel state, skip {@link #open} coverGen bump.
     */
    public void resumeCarouselSession(FlowLaunchRequest req) {
        if (req == null || flowView == null) return;
        launchRequest = req;
        syncReflectionPref();
        FlowMode mode = req.mode != null && req.mode != FlowMode.UNSPECIFIED
                ? req.mode : FlowMode.ALBUM;
        if (!catalog.isEmpty() && activeMode == mode) {
            uiMode = UI_CAROUSEL;
            pickerScroll.setVisibility(View.GONE);
            flowView.setVisibility(View.VISIBLE);
            flowView.resetHandoffRevealForDisplay();
            if (!syncCarouselToNowPlayingIfActive()) {
                if (req.focusKey != null && !req.focusKey.isEmpty()) {
                    int found = flowView.engine().findIndexForKey(catalog, req.focusKey);
                    if (found >= 0) {
                        flowView.engine().setFocusIndex(found);
                        focusedItem = flowView.itemAt(found);
                    }
                }
            }
            hideCarouselLoading();
            flowView.requestFocus();
            flowView.invalidate();
            return;
        }
        showCarousel(mode, req.focusKey);
    }

    /**
     * Center carousel on the now-playing album when music is active.
     * Skipped while flipped or during guided scroll-back.
     * @return true when focus moved to the playing rack item
     */
    public boolean syncCarouselToNowPlayingIfActive() {
        if (flowView == null || catalog == null || catalog.isEmpty()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("skip", "no_catalog");
                d.put("catalogSize", catalog != null ? catalog.size() : 0);
                DebugB8b871Log.log(actions.activity(), "FlowScreenHost.syncCarouselToNowPlayingIfActive",
                        "skipped", "H-B", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        if (!actions.isMusicPlaybackActive()) return false;
        if (flowView.isFlipped() || flowView.isGuidedScrolling()) return false;
        String key = actions.nowPlayingCarouselMatchKey(catalog);
        if (key == null || key.isEmpty()) return false;
        int index = flowView.engine().findIndexForKey(catalog, key);
        if (index < 0) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("matchKey", key);
                d.put("catalogSize", catalog.size());
                DebugB8b871Log.log(actions.activity(), "FlowScreenHost.syncCarouselToNowPlayingIfActive",
                        "key not in catalog", "H-B", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        flowView.engine().setFocusIndex(index);
        focusedItem = flowView.itemAt(index);
        flowView.prefetchAround(index);
        updateLaunchFocusKey(key);
        flowView.invalidate();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("matchKey", key);
            d.put("index", index);
            d.put("itemTitle", focusedItem != null ? focusedItem.title : "");
            DebugB8b871Log.log(actions.activity(), "FlowScreenHost.syncCarouselToNowPlayingIfActive",
                    "synced", "H-B", d);
            com.solar.launcher.Debug1cf0c7Log.log(actions.activity(),
                    "FlowScreenHost.syncCarouselToNowPlayingIfActive", "synced", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
        return true;
    }

    /** Keep launch request focus key aligned with carousel center. */
    private void updateLaunchFocusKey(String key) {
        if (launchRequest == null || key == null || key.isEmpty()) return;
        launchRequest = new FlowLaunchRequest(launchRequest.mode, key, launchRequest.returnScreen,
                launchRequest.returnBrowserMode, launchRequest.returnPodcastUiMode,
                launchRequest.enteredFromSection);
    }

    private void syncReflectionPref() {
        if (flowView != null) {
            flowView.setNoReflections(actions.isDebugFlowNoReflections());
        }
    }

    private List<FlowItem> catalogForReturn(FlowReturnState saved) {
        FlowMode mode = saved.mode != null ? saved.mode : FlowMode.ALBUM;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = catalogOptionsKey();
        List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        if (cached != null) return cached;
        boolean multiTrack = actions.flowMultiTrackAlbumsOnly();
        List<FlowItem> built;
        if (mode == FlowMode.ALBUM) {
            built = FlowCatalog.buildAlbums(actions.libraryRows(), actions.libraryBrowsePrefs(),
                    actions.policyTracks(), multiTrack);
        } else {
            built = FlowCatalog.build(mode, actions.libraryRows(), actions.libraryBrowsePrefs(),
                    actions.policyTracks(), actions.musicRoot(), actions.deezerPlaylists(),
                    actions.podcastShows());
        }
        catalogSessionCache.put(mode, libGen, optionsKey, built);
        return built;
    }

    public FlowItem getFocusedItem() {
        if (uiMode == UI_CAROUSEL && flowView != null) {
            FlowEngine engine = flowView.engine();
            int idx = engine.isAnimating() ? engine.getVisualCenterIndex() : engine.getFocusIndex();
            return flowView.itemAt(idx);
        }
        return focusedItem;
    }

    public boolean isFlipped() {
        return flowView != null && flowView.isFlipped();
    }

    /** Active carousel section — Albums, Artists, etc. */
    public FlowMode getActiveFlowMode() {
        return activeMode;
    }

    /** Focused flip-back row when album/artist face is showing. */
    public FlowBackRow getSelectedBackRow() {
        if (flowView == null || !flowView.isFlipped()) return null;
        return flowView.flipController().selectedRow();
    }

    public boolean isHandoffOrFlipAnimating() {
        if (flowView == null) return false;
        FlowFlipController fc = flowView.flipController();
        return fc.blocksCarouselScroll() || fc.getState() == FlowFlipController.STATE_HANDOFF;
    }

    public boolean handleWheel(int delta) {
        if (uiMode == UI_PICKER) {
            movePicker(delta);
            return true;
        }
        if (uiMode == UI_CAROUSEL) {
            boolean moved = flowView.scrollWheel(delta);
            if (moved && !flowView.isFlipped()) actions.clickFeedback();
            return true;
        }
        return false;
    }

    public boolean handleCenterOk() {
        if (uiMode == UI_PICKER) {
            enterPickerMode();
            return true;
        }
        if (uiMode == UI_CAROUSEL && flowView.isFlipped()) {
            return flowView.handleCenterOk();
        }
        if (uiMode == UI_CAROUSEL) {
            FlowItem item = flowView.itemAt(flowView.engine().getVisualCenterIndex());
            if (item == null) return true;
            flowView.snapCarouselToVisualCenter();
            item = flowView.itemAt(flowView.engine().getFocusIndex());
            if (item == null) return true;
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("kind", item.kind.name());
                d.put("title", item.title);
                d.put("trackCount", item.tracks != null ? item.tracks.size() : 0);
                d.put("okLibrary", actions.isFlowOkOpensLibrary());
                d.put("flipped", flowView.isFlipped());
                DebugSessionLog.log("FlowScreenHost.handleCenterOk", "center ok", "H-OK", d);
            } catch (Exception ignored) {}
            // #endregion
            if (actions.isFlowOkOpensLibrary() && !flowView.isFlipped()) {
                actions.openLibraryBrowse(item);
                return true;
            }
            if (item.kind == FlowItem.Kind.PODCAST) {
                flipPodcast(item);
            } else {
                flipItem(item);
            }
            return true;
        }
        return false;
    }

    public boolean handleBack() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("uiMode", uiMode);
            d.put("flipped", flowView != null && flowView.isFlipped());
            d.put("npBackEnabled", isNowPlayingBackReturnEnabled());
            d.put("npBackKey", nowPlayingBackMatchKey != null ? nowPlayingBackMatchKey : "");
            d.put("guidedScroll", flowView != null && flowView.isGuidedScrolling());
            d.put("handoffAnim", actions.isFlowHandoffAnimating());
            d.put("flipHandoff", isHandoffOrFlipAnimating());
            d.put("musicActive", actions.isMusicPlaybackActive());
            FlowBackDebugLog.log("FlowScreenHost.handleBack", "entry", "H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (uiMode == UI_PICKER) {
            // #region agent log
            FlowBackDebugLog.log("FlowScreenHost.handleBack", "picker→menu", "H4", null);
            // #endregion
            actions.exitFlowToMainMenu();
            return true;
        }
        if (uiMode == UI_CAROUSEL && flowView.isFlipped()) {
            if (flowView.flipController().popBackLevel()) {
                // #region agent log
                FlowBackDebugLog.log("FlowScreenHost.handleBack", "flip pop level", "H5", null);
                // #endregion
                flowView.invalidate();
                return true;
            }
            if (flowView.flipToFront()) {
                // #region agent log
                FlowBackDebugLog.log("FlowScreenHost.handleBack", "flip to front", "H5", null);
                // #endregion
                return true;
            }
        }
        // Back from carousel: NP return path scrolls to playing album then handoff; else exit Flow.
        if (uiMode == UI_CAROUSEL) {
            boolean npReturn = tryReturnToNowPlayingOnBack();
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("npReturnConsumed", npReturn);
                FlowBackDebugLog.log("FlowScreenHost.handleBack", "carousel back", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            if (npReturn) return true;
            // #region agent log
            FlowBackDebugLog.log("FlowScreenHost.handleBack", "exitFlowScreen", "H4", null);
            // #endregion
            actions.exitFlowScreen();
            return true;
        }
        // #region agent log
        FlowBackDebugLog.log("FlowScreenHost.handleBack", "unhandled false", "H4", null);
        // #endregion
        return false;
    }

    /**
     * NP→Flow session: scroll carousel to the playing album (if needed), then forward handoff.
     * @return true when Back was consumed (including in-progress scroll / handoff)
     */
    private boolean tryReturnToNowPlayingOnBack() {
        if (!isNowPlayingBackReturnEnabled() || !actions.isMusicPlaybackActive()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("enabled", isNowPlayingBackReturnEnabled());
                d.put("musicActive", actions.isMusicPlaybackActive());
                FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "skip disabled", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        if (actions.isFlowHandoffAnimating() || isHandoffOrFlipAnimating()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("handoffAnim", actions.isFlowHandoffAnimating());
                d.put("flipHandoff", isHandoffOrFlipAnimating());
                FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "blocked animating", "H3", d);
            } catch (Exception ignored) {}
            // #endregion
            return true;
        }
        if (flowView.isGuidedScrolling()) {
            // #region agent log
            FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "blocked guided scroll", "H2", null);
            // #endregion
            return true;
        }
        if (catalog.isEmpty() || flowView == null) return false;

        int playingIdx = flowView.engine().findIndexForKey(catalog, nowPlayingBackMatchKey);
        if (playingIdx < 0) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("matchKey", nowPlayingBackMatchKey);
                FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "key not in catalog", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            clearNowPlayingBackReturn();
            return false;
        }

        int current = flowView.engine().isAnimating()
                ? flowView.engine().getVisualCenterIndex()
                : flowView.engine().getFocusIndex();
        if (current != playingIdx || flowView.engine().isAnimating()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("current", current);
                d.put("playingIdx", playingIdx);
                FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "guided scroll start", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            flowView.animateScrollToIndexForHandoff(playingIdx, new Runnable() {
                @Override
                public void run() {
                    performNowPlayingHandoffFromCarousel();
                }
            });
            return true;
        }
        // #region agent log
        FlowBackDebugLog.log("FlowScreenHost.tryReturnToNowPlayingOnBack", "handoff from center", "H1", null);
        // #endregion
        performNowPlayingHandoffFromCarousel();
        actions.clickFeedback();
        return true;
    }

    /**
     * Reverse 3D morph from Now Playing — mirror of {@link #performNowPlayingHandoffFromCarousel()}.
     * Prep carousel (warm or cold shell), measure landing rect, launch flyer morph immediately.
     * @return false when prep cannot start (caller should fall back to crossfade)
     */
    public boolean performNpToFlowHandoffFromNowPlaying(Bitmap cover, String matchKey,
            FlowReturnState warmState, List<FlowItem> warmCatalog, RectF fromRect,
            Runnable onComplete) {
        if (flowView == null || cover == null || cover.isRecycled()) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("flowViewNull", flowView == null);
                d.put("coverNull", cover == null);
                d.put("coverRecycled", cover != null && cover.isRecycled());
                com.solar.launcher.Debug898913Log.log(
                        "FlowScreenHost.performNpToFlowHandoffFromNowPlaying",
                        "ABORT — morph cannot start", "H-D", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("warm", warmState != null);
            d.put("matchKey", matchKey != null ? matchKey : "");
            d.put("fromRectW", fromRect != null ? fromRect.width() : -1f);
            com.solar.launcher.Debug898913Log.log(
                    "FlowScreenHost.performNpToFlowHandoffFromNowPlaying", "morph prep start", "H-D", d);
        } catch (Exception ignored) {}
        // #endregion
        syncCarouselToNowPlayingIfActive();
        if (warmState != null) {
            prepareReturnForResolvedState(warmState, warmCatalog, cover);
        } else {
            prepareImmediateReverseHandoffShell(cover, matchKey);
        }
        ensureCarouselLayoutForHandoff();
        actions.ensureHandoffLayoutMeasured();
        float toRotY = getHandoffStartRotationY();
        RectF npRect = fromRect;
        if (npRect == null || npRect.width() <= 0f) {
            npRect = actions.resolveNpHandoffFromScreenRect();
        }
        actions.launchReverseHandoff(cover, npRect, toRotY, onComplete);
        return true;
    }

    /** Center cover for handoff — disk/memory hit, or generated ♪ placeholder for art-less albums. */
    private Bitmap resolveCarouselHandoffCover() {
        if (flowView == null) return null;
        Bitmap cover = flowView.getCenterCoverBitmap();
        if (cover != null) return cover;
        FlowItem item = flowView.itemAt(flowView.engine().getVisualCenterIndex());
        if (item == null) return null;
        int thumb = coverCache.thumbSizePx(480f, 360f);
        cover = FlowCoverResolver.resolve(item, this, thumb);
        if (cover != null) coverCache.put(item.coverKey, cover);
        return cover;
    }

    /** Forward 3D morph to Now Playing — same path for direct Back and guided-scroll finish. */
    private void performNowPlayingHandoffFromCarousel() {
        if (flowView == null || !actions.isMusicPlaybackActive()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("flowViewNull", flowView == null);
                d.put("musicActive", actions.isMusicPlaybackActive());
                FlowBackDebugLog.log("FlowScreenHost.performNowPlayingHandoffFromCarousel",
                        "early return no-op", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            return;
        }
        flowView.snapCarouselToVisualCenter();
        syncCarouselToNowPlayingIfActive();
        Bitmap cover = resolveCarouselHandoffCover();
        RectF fromRect = flowView.getHandoffFromScreenRect();
        float fromRotY = flowView.getHandoffStartRotationY();
        // Back→NP ends the NP↔Flow ping-pong — do not capture a new flip return snapshot.
        actions.releaseFlowNpBackLoop();
        boolean handoff = actions.isFlow3dHandoffEnabled() && cover != null && fromRect != null;
        if (handoff) {
            actions.resumePlayerWithHandoff(cover, fromRect, fromRotY);
        } else {
            actions.resumePlayerWithCrossfade();
        }
    }

    public void refreshCatalogIfNeeded() {
        if (uiMode != UI_CAROUSEL || activeMode == FlowMode.UNSPECIFIED) return;
        // Silent rebind — caller invalidates cache; never flash full-screen loader over a live carousel.
        rebindCarouselFromPrefs(activeMode,
                focusedItem != null ? focusedItem.matchKey : null, false);
    }

    /**
     * Rebuild carousel after browse-pref / library changes.
     * @param forceLoadingOverlay true only for first open with no resident catalog
     */
    private void rebindCarouselFromPrefs(final FlowMode mode, final String focusKey,
            boolean forceLoadingOverlay) {
        final int libGen = actions.libraryScanGeneration();
        final int optionsKey = catalogOptionsKey();
        final List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        carouselLoadGen++;
        coverGen++;
        coverGovernor.cancelAll();
        final int loadGen = carouselLoadGen;
        if (cached != null && !cached.isEmpty()) {
            bindCarouselWithWorkerWarm(cached, mode, focusKey, loadGen, 0L);
            return;
        }
        final List<FlowItem> stale = catalogSessionCache.peekStale(mode, libGen, optionsKey);
        if (stale != null && !stale.isEmpty()) {
            bindCarouselWithWorkerWarm(stale, mode, focusKey, loadGen, 0L);
            catalogBuildGen++;
            scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen,
                    catalogBuildGen, 0L);
            return;
        }
        catalogBuildGen++;
        final int buildGen = catalogBuildGen;
        if (forceLoadingOverlay || catalog.isEmpty()) {
            showCarouselLoading();
        }
        scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen, buildGen, 0L);
    }

    private void showPicker() {
        uiMode = UI_PICKER;
        activeMode = FlowMode.UNSPECIFIED;
        pickerIndex = 0;
        pickerScroll.setVisibility(View.VISIBLE);
        flowView.setVisibility(View.GONE);
        pickerContainer.removeAllViews();
        String[] labels = {
                actions.activity().getString(R.string.status_home),
                actions.activity().getString(R.string.flow_mode_albums),
                actions.activity().getString(R.string.flow_mode_artists),
                actions.activity().getString(R.string.flow_mode_playlists),
                actions.activity().getString(R.string.flow_mode_podcasts)
        };
        for (int i = 0; i < labels.length; i++) {
            Button btn = actions.createListButton(labels[i]);
            actions.configureListButton(btn);
            final int idx = i;
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    actions.clickFeedback();
                    pickerIndex = idx;
                    enterPickerMode();
                }
            });
            pickerContainer.addView(btn);
        }
        if (pickerContainer.getChildCount() > 0) {
            pickerContainer.getChildAt(0).requestFocus();
        }
    }

    private void enterPickerMode() {
        if (pickerIndex == 0) {
            actions.clickFeedback();
            actions.exitFlowToMainMenu();
            return;
        }
        FlowMode mode = FlowMode.values()[pickerIndex];
        showCarousel(mode, null);
    }

    private void showCarousel(final FlowMode mode, final String focusKey) {
        uiMode = UI_CAROUSEL;
        activeMode = mode;
        syncReflectionPref();
        flowView.resetFlip();
        flowView.resetHandoffRevealForDisplay();
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        carouselLoadGen++;
        final int loadGen = carouselLoadGen;
        coverGen++;
        coverGovernor.cancelAll();
        catalogBuildGen++;
        final int buildGen = catalogBuildGen;
        final long showCarouselStartMs = DebugSessionLog.ENABLED ? System.currentTimeMillis() : 0L;

        final int libGen = actions.libraryScanGeneration();
        final int optionsKey = catalogOptionsKey();
        final List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        if (cached != null && !cached.isEmpty()) {
            bindCarouselWithWorkerWarm(cached, mode, focusKey, loadGen, showCarouselStartMs);
            return;
        }

        final List<FlowItem> stale = catalogSessionCache.peekStale(mode, libGen, optionsKey);
        if (stale != null && !stale.isEmpty()) {
            bindCarouselWithWorkerWarm(stale, mode, focusKey, loadGen, showCarouselStartMs);
            scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen, buildGen, showCarouselStartMs);
            return;
        }

        showCarouselLoading();

        scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen, buildGen, showCarouselStartMs);
    }

    /** Small racks: bind immediately, then disk-warm on worker (never block first paint). */
    private void bindCarouselWithWorkerWarm(final List<FlowItem> items, final FlowMode mode,
            final String focusKey, final int loadGen, final long showCarouselStartMs) {
        if (items == null || items.isEmpty()) return;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("size", items.size());
            d.put("loadGen", loadGen);
            d.put("flowViewNull", flowView == null);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.bindCarouselWithWorkerWarm",
                    "warm bind", "H3", d);
        } catch (Exception ignored) {}
        // #endregion
        bindCarouselCatalog(items, mode, focusKey, loadGen);
        logShowCarouselTiming(showCarouselStartMs, true, items.size());
    }

    /** Bind first, warm covers on worker — never block the first scrollable frame on decode. */
    private void schedulePostBindCoverWarm(final List<FlowItem> items, final int warmFocus,
            final int loadGen) {
        if (items == null || items.isEmpty() || flowView == null) return;
        final int warmRadius = warmRadiusForCatalog(items.size());
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    warmCatalogCoversSync(items, warmFocus, warmRadius);
                } catch (Throwable ignored) {
                    // Fail-open — carousel already bound; governor fills misses on scroll.
                }
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != carouselLoadGen || flowView == null) return;
                        flowView.prefetchAround(warmFocus);
                        flowView.scheduleBakesAround(warmFocus, 2);
                        flowView.invalidate();
                    }
                });
            }
        });
    }

    private void scheduleCatalogRebuild(final FlowMode mode, final String focusKey,
            final int libGen, final int optionsKey, final int loadGen, final int buildGen,
            final long showCarouselStartMs) {
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("mode", mode != null ? mode.name() : "null");
                    d.put("buildGen", buildGen);
                    d.put("catalogBuildGen", catalogBuildGen);
                    d.put("loadGen", loadGen);
                    com.solar.launcher.Debug898913Log.log("FlowScreenHost.scheduleCatalogRebuild",
                            "rebuild worker start", "H2", d);
                } catch (Exception ignored) {}
                // #endregion
                if (buildGen != catalogBuildGen) {
                    return;
                }
                List<FlowItem> built = null;
                Throwable failure = null;
                try {
                    built = buildCatalog(mode, libGen, optionsKey);
                    if (buildGen == catalogBuildGen) {
                        catalogSessionCache.put(mode, libGen, optionsKey, built);
                    }
                } catch (Throwable t) {
                    failure = t;
                }
                final List<FlowItem> result = built;
                final Throwable err = failure;
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("mode", mode != null ? mode.name() : "null");
                                d.put("error", err.getClass().getName());
                                d.put("message", err.getMessage() != null ? err.getMessage() : "");
                                com.solar.launcher.Debug898913Log.log(
                                        "FlowScreenHost.scheduleCatalogRebuild",
                                        "catalog build failed", "H-LOAD", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            hideCarouselLoading();
                            return;
                        }
                        if (buildGen != catalogBuildGen || loadGen != carouselLoadGen) {
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("loadGen", loadGen);
                                d.put("carouselLoadGen", carouselLoadGen);
                                d.put("buildGen", buildGen);
                                d.put("catalogBuildGen", catalogBuildGen);
                                d.put("flowLoadingVisible", flowLoadingVisible);
                                com.solar.launcher.Debug898913Log.log(
                                        "FlowScreenHost.scheduleCatalogRebuild",
                                        "stale rebuild discarded", "H-LOAD", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            recoverOrphanedCarouselLoader(mode, focusKey, libGen, optionsKey);
                            return;
                        }
                        bindCarouselCatalog(result, mode, focusKey, loadGen);
                        logShowCarouselTiming(showCarouselStartMs, false,
                                result != null ? result.size() : 0);
                    }
                });
            }
        });
    }

    /** Stale async build — bind peeked cache or dismiss loader so Flow never hangs. */
    private void recoverOrphanedCarouselLoader(FlowMode mode, String focusKey,
            int libGen, int optionsKey) {
        List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        if (cached == null || cached.isEmpty()) {
            cached = catalogSessionCache.peekStale(mode, libGen, optionsKey);
        }
        if (cached != null && !cached.isEmpty()) {
            bindCarouselCatalog(cached, mode, focusKey, carouselLoadGen);
        } else {
            hideCarouselLoading();
        }
    }

    private void logShowCarouselTiming(long startMs, boolean cacheHit, int catalogSize) {
        if (startMs <= 0L) return;
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("ms", System.currentTimeMillis() - startMs);
            d.put("cacheHit", cacheHit);
            d.put("catalogSize", catalogSize);
            DebugSessionLog.log("FlowScreenHost.showCarousel", "carousel enter", "H-PERF", d);
        } catch (Exception ignored) {}
    }

    private List<FlowItem> buildCatalog(FlowMode mode, int libGen, int optionsKey) {
        // #region agent log
        final long buildStartMs = DebugSessionLog.ENABLED ? System.currentTimeMillis() : 0L;
        // #endregion
        try {
            boolean multiTrack = actions.flowMultiTrackAlbumsOnly();
            List<FlowItem> built;
            if (mode == FlowMode.ALBUM) {
                built = FlowCatalog.buildAlbums(actions.libraryRows(), actions.libraryBrowsePrefs(),
                        actions.policyTracks(), multiTrack);
            } else {
                built = FlowCatalog.build(mode, actions.libraryRows(), actions.libraryBrowsePrefs(),
                        actions.policyTracks(), actions.musicRoot(), actions.deezerPlaylists(),
                        actions.podcastShows());
            }
            // #region agent log
            if (buildStartMs > 0L) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("mode", mode != null ? mode.name() : "null");
                    d.put("buildMs", System.currentTimeMillis() - buildStartMs);
                    d.put("itemCount", built != null ? built.size() : -1);
                    d.put("libGen", libGen);
                    DebugSessionLog.log("FlowScreenHost.buildCatalog", "built", "H1-H4", d);
                } catch (Exception ignored) {}
            }
            // #endregion
            return built;
        } catch (Throwable t) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("mode", mode != null ? mode.name() : "null");
                d.put("buildMs", buildStartMs > 0L ? System.currentTimeMillis() - buildStartMs : -1L);
                d.put("exception", t.getClass().getName());
                d.put("message", t.getMessage() != null ? t.getMessage() : "");
                DebugSessionLog.log("FlowScreenHost.buildCatalog", "build failed", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            throw t;
        }
    }

    /** Cache key for session catalog — includes library filter/sort prefs. */
    private int catalogOptionsKey() {
        return FlowCatalog.catalogOptionsKey(actions.libraryBrowsePrefs(),
                actions.flowMultiTrackAlbumsOnly());
    }

    /**
     * Resolve carousel index after catalog (re)bind — matchKey beats stale numeric index.
     */
    /** Bind-time check: side slots must be catalog[N±1] for wheel predictability. */
    private void logNeighborInvariant(int index) {
        if (catalog == null || catalog.isEmpty()) return;
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("focusIndex", index);
            d.put("centerTitle", index >= 0 && index < catalog.size()
                    ? catalog.get(index).title : "");
            if (index > 1) d.put("slotMinus2", catalog.get(index - 2).title);
            if (index > 0) d.put("slotMinus1", catalog.get(index - 1).title);
            if (index + 1 < catalog.size()) d.put("slotPlus1", catalog.get(index + 1).title);
            if (index + 2 < catalog.size()) d.put("slotPlus2", catalog.get(index + 2).title);
            if (index > 0) d.put("leftTitle", catalog.get(index - 1).title);
            if (index + 1 < catalog.size()) d.put("rightTitle", catalog.get(index + 1).title);
            if (index + 2 < catalog.size()) d.put("right2Title", catalog.get(index + 2).title);
            if (index > 1) d.put("left2Title", catalog.get(index - 2).title);
            if (flowView != null && flowView.getWidth() > 0) {
                float vw = flowView.getWidth();
                float vh = flowView.getHeight();
                FlowEngine eng = flowView.engine();
                org.json.JSONArray slots = new org.json.JSONArray();
                for (int dIdx = -2; dIdx <= 2; dIdx++) {
                    int idx = index + dIdx;
                    if (idx < 0 || idx >= catalog.size()) continue;
                    FlowEngine.SlotTransform t = eng.slotTransform(idx, vw, vh, 0f);
                    org.json.JSONObject slot = new org.json.JSONObject();
                    slot.put("d", dIdx);
                    slot.put("idx", idx);
                    slot.put("title", catalog.get(idx).title);
                    slot.put("rel", idx - eng.getVisualOffset());
                    slot.put("alpha", t.alpha);
                    slot.put("cx", t.centerX);
                    slots.put(slot);
                }
                d.put("bindSlots", slots);
            }
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.bindCarouselCatalog",
                    "neighbor invariant", "H-CATALOG", d);
        } catch (Exception ignored) {}
    }

    private int resolveCarouselFocusIndex(List<FlowItem> items, FlowMode mode, String focusKey) {
        boolean fromSection = launchRequest != null && launchRequest.enteredFromSection;
        String resident = focusedItem != null ? focusedItem.matchKey : null;
        return FlowCarouselFocus.resolveIndex(items, mode, focusKey, fromSection, resident,
                actions.loadLastFlowMatchKey(mode), actions.loadLastFlowIndex(mode),
                flowView.engine());
    }

    /** Worker-thread focus resolve — same policy as bind, no live engine required. */
    private int resolveFocusIndexOnList(List<FlowItem> items, FlowMode mode, String focusKey) {
        boolean fromSection = launchRequest != null && launchRequest.enteredFromSection;
        String resident = focusedItem != null ? focusedItem.matchKey : null;
        return FlowCarouselFocus.resolveIndex(items, mode, focusKey, fromSection, resident,
                actions.loadLastFlowMatchKey(mode), actions.loadLastFlowIndex(mode),
                new FlowEngine());
    }

    /** Pre-bind warm radius — tiny racks need every neighbor drawable on frame 0. */
    private static int warmRadiusForCatalog(int catalogSize) {
        return catalogSize <= 5 ? catalogSize : 3;
    }

    private void bindCarouselCatalog(List<FlowItem> built, FlowMode mode, String focusKey, int loadGen) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("flowViewNull", flowView == null);
            d.put("catalogSize", built != null ? built.size() : -1);
            d.put("loadGen", loadGen);
            d.put("carouselLoadGen", carouselLoadGen);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.bindCarouselCatalog",
                    "bind entry", "H1,H3", d);
        } catch (Exception ignored) {}
        // #endregion
        if (flowView == null) {
            // #region agent log
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.bindCarouselCatalog",
                    "abort flowView null", "H1", null);
            // #endregion
            hideCarouselLoading();
            return;
        }
        if (loadGen != carouselLoadGen) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("loadGen", loadGen);
                d.put("carouselLoadGen", carouselLoadGen);
                d.put("flowLoadingVisible", flowLoadingVisible);
                com.solar.launcher.Debug898913Log.log("FlowScreenHost.bindCarouselCatalog",
                        "stale bind skipped", "H-LOAD", d);
            } catch (Exception ignored) {}
            // #endregion
            // Orphan loader when a newer rebind superseded this bind.
            if (flowLoadingVisible) hideCarouselLoading();
            return;
        }
        syncReflectionPref();
        coverGen++;
        coverGovernor.cancelAll();
        android.graphics.Bitmap playingCover = actions.playerHandoffCoverBitmap();
        String playingKey = actions.nowPlayingCarouselMatchKey(built);
        coverCache.clear();
        if (playingCover != null && !playingCover.isRecycled() && playingKey != null && !playingKey.isEmpty()) {
            coverCache.put(playingKey, playingCover);
        }
        flowView.resetHandoffRevealForDisplay();
        catalog = built;
        incrementalThumbCursor = 0;
        int index = resolveCarouselFocusIndex(catalog, mode, focusKey);
        flowView.setItemsAndFocus(catalog, index);
        focusedItem = flowView.itemAt(index);
        if (syncCarouselToNowPlayingIfActive()) {
            index = flowView.engine().getFocusIndex();
            focusedItem = flowView.itemAt(index);
        }
        flowView.prefetchAround(index);
        flowView.invalidate();
        logNeighborInvariant(index);
        hideCarouselLoading();
        ensureFlowBlockingOverlayDismissed();
        flowView.requestFocus();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("mode", mode.name());
            d.put("focusKey", focusKey != null ? focusKey : "");
            d.put("focusIndex", index);
            d.put("syncedNp", actions.isMusicPlaybackActive());
            d.put("centerTitle", focusedItem != null ? focusedItem.title : "");
            d.put("flowViewAlpha", flowView.getAlpha());
            DebugB8b871Log.log(actions.activity(), "FlowScreenHost.bindCarouselCatalog",
                    "bound", "H-A", d);
            float layoutAlpha = actions.activity() != null
                    && actions.activity().findViewById(com.solar.launcher.R.id.layout_flow_mode) != null
                    ? actions.activity().findViewById(com.solar.launcher.R.id.layout_flow_mode).getAlpha()
                    : -1f;
            d.put("layoutFlowAlpha", layoutAlpha);
            d.put("centerCoverReady", flowView.centerCoverReadyForRevealDebug());
            com.solar.launcher.Debug1cf0c7Log.log(actions.activity(),
                    "FlowScreenHost.bindCarouselCatalog", "bound", "H-A-H-B", d);
        } catch (Exception ignored) {}
        // #endregion
        schedulePostBindCoverWarm(catalog, index, loadGen);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("mode", mode.name());
            d.put("catalogSize", catalog.size());
            d.put("focusIndex", index);
            d.put("loadingOverlayUsed", flowLoadingVisible);
            DebugSessionLog.log("FlowScreenHost.showCarousel", "carousel ready", "H1-H4", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private void showCarouselLoading() {
        flowLoadingVisible = true;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("asyncBuild", true);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.showCarouselLoading",
                    "catalog loading shown", "H-LOAD", d);
            com.solar.launcher.Debug1cf0c7Log.log(actions.activity(),
                    "FlowScreenHost.showCarouselLoading", "catalog loading", "H-B", d);
        } catch (Exception ignored) {}
        // #endregion
        actions.showFlowLoading(actions.activity().getString(R.string.flow_starting));
    }

    private void hideCarouselLoading() {
        if (!flowLoadingVisible) return;
        flowLoadingVisible = false;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("catalogSize", catalog != null ? catalog.size() : 0);
            com.solar.launcher.Debug898913Log.log("FlowScreenHost.hideCarouselLoading",
                    "catalog loading hidden", "H-LOAD", d);
        } catch (Exception ignored) {}
        // #endregion
        actions.hideFlowLoading();
    }

    /** Dismiss Flow blocking overlay even when flowLoadingVisible flag desynced. */
    private void ensureFlowBlockingOverlayDismissed() {
        flowLoadingVisible = false;
        actions.hideFlowLoading();
    }

    private void flipItem(FlowItem item) {
        focusedItem = item;
        flipGen++;
        if (item.kind == FlowItem.Kind.PLAYLIST && item.tracks.isEmpty()
                && item.id != null && item.id.startsWith("deezer:")) {
            flipDeezerPlaylist(item);
            return;
        }
        List<FlowBackRow> rows = new ArrayList<FlowBackRow>();
        LibraryBrowsePrefs prefs = actions.libraryBrowsePrefs();
        List<FlowCatalog.SongRow> lib = actions.libraryRows();
        List<ArtistBrowsePolicy.Track> policy = actions.policyTracks();
        if (item.kind == FlowItem.Kind.ALBUM) {
            List<File> tracks = item.tracks.isEmpty()
                    ? FlowCatalog.tracksForAlbum(item, lib, prefs) : item.tracks;
            addTrackRows(rows, tracks, lib);
        } else if (item.kind == FlowItem.Kind.ARTIST) {
            if (ArtistBrowsePolicy.shouldSkipAlbumPicker(item.title, prefs, policy)) {
                addTrackRows(rows, FlowCatalog.tracksForArtist(item.title, lib, prefs, policy), lib);
            } else {
                for (FlowItem album : FlowCatalog.albumsForArtist(item.title, lib, prefs, policy)) {
                    rows.add(new FlowBackRow(album.title, album.subtitle, null, null, album, null));
                }
            }
        } else if (item.kind == FlowItem.Kind.PLAYLIST) {
            addTrackRows(rows, item.tracks, lib);
        }
        if (rows.isEmpty()) {
            rows.add(new FlowBackRow(
                    actions.activity().getString(R.string.flow_empty), "", null, null));
        }
        flowView.flipToBack(item.title, item.subtitle, rows);
    }

    private void flipDeezerPlaylist(final FlowItem item) {
        flipGen++;
        final int gen = flipGen;
        long plId;
        try {
            plId = Long.parseLong(item.id.substring("deezer:".length()));
        } catch (Exception e) {
            return;
        }
        List<FlowBackRow> loading = new ArrayList<FlowBackRow>();
        loading.add(new FlowBackRow(
                actions.activity().getString(R.string.flow_loading), "", null, null));
        flowView.flipToBack(item.title, item.subtitle, loading);
        actions.fetchDeezerPlaylistTracks(plId, new PlaylistTracksCallback() {
            @Override
            public void onTracks(List<DeezerResult> tracks, String error) {
                if (gen != flipGen) return;
                List<FlowBackRow> rows = new ArrayList<FlowBackRow>();
                if (tracks == null || tracks.isEmpty()) {
                    rows.add(new FlowBackRow(
                            error != null ? error : actions.activity().getString(R.string.flow_empty),
                            "", null, null));
                } else {
                    for (DeezerResult tr : tracks) {
                        rows.add(new FlowBackRow(tr.displayTitle(), "", null, null, null, tr));
                    }
                }
                flowView.flipController().setBackContent(item.title, item.subtitle, rows);
                flowView.invalidate();
            }
        });
    }

    private void flipPodcast(final FlowItem item) {
        focusedItem = item;
        flipGen++;
        final int gen = flipGen;
        List<FlowBackRow> loading = new ArrayList<FlowBackRow>();
        loading.add(new FlowBackRow(
                actions.activity().getString(R.string.flow_loading_episodes), "", null, null));
        flowView.flipToBack(item.title, item.subtitle, loading);
        actions.fetchPodcastEpisodes(item.podcastFeedUrl, new EpisodeCallback() {
            @Override
            public void onEpisodes(List<OpenRssClient.Episode> episodes, String error) {
                if (gen != flipGen) return;
                List<FlowBackRow> rows = new ArrayList<FlowBackRow>();
                if (episodes == null || episodes.isEmpty()) {
                    rows.add(new FlowBackRow(
                            error != null ? error : actions.activity().getString(R.string.flow_empty),
                            "", null, null));
                } else {
                    for (OpenRssClient.Episode ep : episodes) {
                        rows.add(new FlowBackRow(ep.title, ep.pubDate, null, ep));
                    }
                }
                flowView.flipController().setBackContent(item.title, item.subtitle, rows);
                flowView.invalidate();
            }
        });
    }

    private void addTrackRows(List<FlowBackRow> rows, List<File> tracks,
            List<FlowCatalog.SongRow> library) {
        if (tracks == null) return;
        for (File f : tracks) {
            String label = FlowCatalog.trackDisplayLabel(f, library);
            rows.add(new FlowBackRow(label, "", f, null));
        }
    }

    private void drillToAlbumTracks(FlowItem album) {
        if (album == null) return;
        LibraryBrowsePrefs prefs = actions.libraryBrowsePrefs();
        List<File> tracks = album.tracks.isEmpty()
                ? FlowCatalog.tracksForAlbum(album, actions.libraryRows(), prefs) : album.tracks;
        List<FlowBackRow> rows = new ArrayList<FlowBackRow>();
        addTrackRows(rows, tracks, actions.libraryRows());
        if (rows.isEmpty()) return;
        flowView.flipController().pushBackLevel();
        flowView.flipController().setBackContent(album.title, album.subtitle, rows);
        flowView.invalidate();
    }

    private void playBackRow(FlowScreenHost.FlowBackRow row, int index) {
        if (row == null) return;
        actions.clickFeedback();

        if (row.nestedItem != null && row.track == null && row.deezerTrack == null && row.episode == null) {
            drillToAlbumTracks(row.nestedItem);
            return;
        }

        // Already playing — same handoff animation, no playlist/prepare restart.
        if (row.track != null && actions.isSamePlayingTrack(row.track)) {
            Bitmap cover = resolveCarouselHandoffCover();
            RectF fromRect = flowView.getHandoffFromScreenRect();
            float fromRotY = flowView.getHandoffStartRotationY();
            RectF returnRect = flowView.getHandoffCoverScreenRect();
            capturePlayerReturn(fromRect, fromRotY, returnRect, 0f);
            boolean handoff = actions.isFlow3dHandoffEnabled() && cover != null && fromRect != null;
            if (handoff) {
                actions.resumePlayerWithHandoff(cover, fromRect, fromRotY);
            } else {
                actions.resumePlayerWithCrossfade();
            }
            return;
        }

        Bitmap cover = resolveCarouselHandoffCover();
        RectF fromRect = flowView.getHandoffFromScreenRect();
        float fromRotY = flowView.getHandoffStartRotationY();
        RectF returnRect = flowView.getHandoffCoverScreenRect();
        capturePlayerReturn(fromRect, fromRotY, returnRect, 0f);
        boolean handoff = actions.isFlow3dHandoffEnabled() && cover != null && fromRect != null;

        if (row.track != null) {
            List<File> list = new ArrayList<File>();
            for (FlowScreenHost.FlowBackRow r : flowView.flipController().backRows()) {
                if (r.track != null) list.add(r.track);
            }
            int start = list.indexOf(row.track);
            if (start < 0) start = 0;
            final int startIdx = start;
            final List<File> playlist = list;
            final String label = focusedItem != null ? focusedItem.title : null;
            if (handoff) {
                actions.playTracksWithHandoff(playlist, startIdx, label, cover, fromRect, fromRotY);
            } else {
                actions.playTracksWithCrossfade(playlist, startIdx, label);
            }
            return;
        }
        if (row.deezerTrack != null) {
            List<DeezerResult> list = new ArrayList<DeezerResult>();
            for (FlowScreenHost.FlowBackRow r : flowView.flipController().backRows()) {
                if (r.deezerTrack != null) list.add(r.deezerTrack);
            }
            int start = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == row.deezerTrack.id) { start = i; break; }
            }
            final int startIdx = start;
            if (flowView.isFlipped()) flowView.flipToFront();
            actions.playDeezerTracks(list, startIdx);
            return;
        }
        if (row.episode != null && focusedItem != null) {
            List<OpenRssClient.Episode> eps = new ArrayList<OpenRssClient.Episode>();
            for (FlowScreenHost.FlowBackRow r : flowView.flipController().backRows()) {
                if (r.episode != null) eps.add(r.episode);
            }
            int start = 0;
            for (int i = 0; i < eps.size(); i++) {
                if (eps.get(i) == row.episode) { start = i; break; }
            }
            final OpenRssClient.Podcast show = new OpenRssClient.Podcast(
                    focusedItem.title, focusedItem.subtitle, focusedItem.podcastFeedUrl,
                    focusedItem.podcastArtUrl);
            final int startFinal = start;
            if (handoff) {
                actions.playPodcastWithHandoff(show, eps, startFinal, cover, fromRect, fromRotY);
            } else {
                actions.playPodcastWithCrossfade(show, eps, startFinal);
            }
        }
    }

    private void movePicker(int delta) {
        int count = pickerContainer.getChildCount();
        if (count <= 0) return;
        pickerIndex = (pickerIndex + delta + count) % count;
        View child = pickerContainer.getChildAt(pickerIndex);
        if (child != null) child.requestFocus();
        actions.clickFeedback();
    }

    @Override
    public void onBackRowSelected(FlowBackRow row, int index) {
        playBackRow(row, index);
    }

    @Override
    public void onFlipDismissed() {
        // carousel restored
    }

    @Override
    public void onCarouselFrameTick() {
        if (DebugSessionLog.ENABLED) {
            int depth = coverGovernor.pendingWorkCount();
            if (depth != lastLoggedGovernorDepth && depth > 0) {
                lastLoggedGovernorDepth = depth;
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("decodeQueueDepth", depth);
                    if (flowView != null) {
                        d.put("scrolling", flowView.engine().isCarouselScrolling());
                    }
                    DebugSessionLog.log("CoverLoadGovernor", "queue depth", "H3", d);
                } catch (Exception ignored) {}
            } else if (depth == 0) {
                lastLoggedGovernorDepth = -1;
            }
            if (flowView != null && flowView.engine().isCarouselScrolling()) {
                long now = System.currentTimeMillis();
                if (now - lastScrollPerfLogMs >= 400L) {
                    lastScrollPerfLogMs = now;
                    scrollPerfLogCount++;
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("scrollSample", scrollPerfLogCount);
                        d.put("decodeQueueDepth", depth);
                        d.put("catalogSize", catalog != null ? catalog.size() : 0);
                        actions.appendFlowDebugContext(d);
                        DebugSessionLog.log("FlowScreenHost.onCarouselFrameTick",
                                "scroll perf", "H2-H5", d);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public boolean onIdleThumbBakeTick() {
        return false;
    }

    @Override
    public void onCoverBakeReady(String bakeKey) {
        if (flowView != null) flowView.postInvalidateOnAnimation();
    }

    @Override
    public void prefetchCarouselCovers(int visualCenter, int radius) {
        if (catalog == null || catalog.isEmpty()) return;
        warmCatalogCoversAsync(catalog, visualCenter, radius);
        int n = catalog.size();
        for (int d = -radius; d <= radius; d++) {
            int idx = visualCenter + d;
            if (idx < 0 || idx >= n) continue;
            FlowItem item = catalog.get(idx);
            if (item == null || item.coverKey == null || item.coverKey.isEmpty()) continue;
            if (coverCache.get(item.coverKey) != null) continue;
            requestCover(idx, item.coverKey, item);
        }
    }

    @Override
    public boolean hasPendingCoverDecodes() {
        // Keep anim tick alive while decodes are still in-flight (UI apply is immediate).
        return coverGovernor.pendingWorkCount() > 0;
    }

    /**
     * Rockbox incremental_albumart_cache — one exact-thumb JPEG per idle carousel frame.
     * ponytail: only promotes from 240px AlbumArtCache; misses resolve on scroll via governor.
     */
    private boolean incrementalFlowThumbBakeOne() {
        if (uiMode != UI_CAROUSEL || catalog == null || catalog.isEmpty()) return false;
        File flowDir = actions.getFlowThumbCacheDir();
        File artDir = actions.getAlbumArtCacheDir();
        if (flowDir == null || artDir == null) return false;
        int thumbPx = coverCache.thumbSizePx(480f, 360f);
        int n = catalog.size();
        boolean anyMissing = false;
        for (int attempt = 0; attempt < n; attempt++) {
            int idx = (incrementalThumbCursor + attempt) % n;
            FlowItem item = catalog.get(idx);
            if (item == null || item.coverKey == null || item.coverKey.isEmpty()) continue;
            if (FlowThumbCache.has(flowDir, item.coverKey, thumbPx)) continue;
            anyMissing = true;
            incrementalThumbCursor = idx + 1;
            android.graphics.Bitmap disk = AlbumArtCache.get(artDir, item.coverKey);
            if (disk == null) {
                disk = FlowCoverResolver.resolveFromTracks(
                        item.tracks, this, thumbPx, item.coverKey, artDir, flowDir);
            }
            if (disk != null) {
                FlowThumbCache.put(flowDir, item.coverKey, disk, thumbPx);
                if (!disk.isRecycled()) disk.recycle();
            }
            return anyMissing;
        }
        return false;
    }

    @Override
    public void warmCarouselCovers(int center, int radius) {
        if (catalog == null || catalog.isEmpty() || flowView == null) return;
        warmCatalogCoversAsync(catalog, center, radius);
    }

    /** On-screen carousel center for warm/prefetch — visual index mid-scroll. */
    private int carouselWarmCenter() {
        if (flowView == null) return 0;
        FlowEngine engine = flowView.engine();
        return engine.isCarouselScrolling()
                ? engine.getVisualCenterIndex() : engine.getFocusIndex();
    }

    private void warmCatalogCoversAsync(final List<FlowItem> items, final int center, final int radius) {
        if (items == null || items.isEmpty()) return;
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                warmCatalogCoversSync(items, center, radius);
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (flowView != null) flowView.postInvalidateOnAnimation();
                    }
                });
            }
        });
    }

    /** Disk-resident cover warm — worker thread only. */
    private void warmCatalogCoversSync(List<FlowItem> items, int center, int radius) {
        if (items == null || items.isEmpty()) return;
        // #region agent log
        final long warmStartMs = DebugSessionLog.ENABLED ? System.currentTimeMillis() : 0L;
        // #endregion
        int thumb = coverCache.thumbSizePx(480f, 360f);
        int n = items.size();
        int warmed = 0;
        // Center slot first — focused cover should land before side neighbors.
        for (int ring = 0; ring <= radius; ring++) {
            if (ring == 0) {
                warmed += warmCatalogCoverAt(items, center, center, thumb, n);
            } else {
                warmed += warmCatalogCoverAt(items, center, center + ring, thumb, n);
                warmed += warmCatalogCoverAt(items, center, center - ring, thumb, n);
            }
        }
        // #region agent log
        if (warmStartMs > 0L) {
            long warmMs = System.currentTimeMillis() - warmStartMs;
            if (warmMs > 8L || warmed > 0) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("center", center);
                    d.put("radius", radius);
                    d.put("warmMs", warmMs);
                    d.put("warmed", warmed);
                    DebugSessionLog.log("FlowScreenHost.warmCatalogCoversSync", "disk warm", "H3-H4", d);
                } catch (Exception ignored) {}
            }
        }
        // #endregion
    }

    private int warmCatalogCoverAt(List<FlowItem> items, int center, int idx, int thumb, int n) {
        if (idx < 0 || idx >= n) return 0;
        if (idx == center && reverseHandoffActive && handoffPinnedCover != null) return 0;
        FlowItem item = items.get(idx);
        if (item == null || item.coverKey == null || item.coverKey.isEmpty()) return 0;
        if (coverCache.get(item.coverKey) != null) return 0;
        Bitmap disk = FlowCoverResolver.resolveDiskCached(item, this, thumb);
        if (disk == null) {
            // Decode on worker — placeholders stay in memory only.
            disk = FlowCoverResolver.resolveFromTracks(
                    item.tracks, this, thumb, item.coverKey,
                    actions.getAlbumArtCacheDir(), actions.getFlowThumbCacheDir());
        }
        if (disk != null) {
            coverCache.put(item.coverKey, disk);
            return 1;
        }
        return 0;
    }

    @Override
    public void requestCover(int itemIndex, String coverKey, FlowItem item) {
        if (coverKey == null || coverCache.get(coverKey) != null) return;
        if (reverseHandoffActive && handoffPinnedCover != null
                && coverKey.equals(handoffPinnedCoverKey)) {
            return;
        }
        if (coverGovernor.isPending(coverKey)) return;
        // ponytail: draw path never sync-loads — governor handles true misses only.
        final int gen = coverGen;
        final int thumb = coverCache.thumbSizePx(480f, 360f);
        int focus = flowView != null ? flowView.engine().getVisualCenterIndex() : itemIndex;
        final int distance = Math.abs(itemIndex - focus);
        coverGovernor.request(coverKey, distance, new CoverLoadGovernor.DecodeTask() {
            @Override
            public Bitmap run() {
                Bitmap bmp = FlowCoverResolver.resolve(item, FlowScreenHost.this, thumb);
                if (bmp == null || gen != coverGen) return null;
                return bmp;
            }
        });
    }

    @Override
    public void onFocusIndexChanged(int index) {
        focusedItem = flowView.itemAt(index);
        if (activeMode != FlowMode.UNSPECIFIED) {
            actions.saveLastFlowIndex(activeMode, index);
            if (focusedItem != null && focusedItem.matchKey != null
                    && !focusedItem.matchKey.isEmpty()) {
                actions.saveLastFlowMatchKey(activeMode, focusedItem.matchKey);
            }
        }
    }

    @Override
    public File coverFileForTrack(File track) {
        return actions.coverFileForTrack(track);
    }

    @Override
    public File getCoversFolder() {
        return actions.getCoversFolder();
    }

    @Override
    public File getAlbumArtCacheDir() {
        return actions.getAlbumArtCacheDir();
    }

    @Override
    public File getFlowThumbCacheDir() {
        return actions.getFlowThumbCacheDir();
    }

    @Override
    public SharedPreferences prefs() {
        return actions.prefs();
    }

    @Override
    public Typeface labelFont() {
        return ThemeManager.getCustomFont();
    }

    /** adb harness — carousel focus after automated wheel input. */
    public int adbCarouselFocusIndex() {
        return flowView != null ? flowView.engine().getFocusIndex() : -1;
    }

    public int adbCarouselItemCount() {
        return catalog != null ? catalog.size() : 0;
    }

    public boolean adbCarouselScrollWheel(int delta) {
        return flowView != null && flowView.scrollWheel(delta);
    }

    /**
     * Fan-out probe: {focus, count, centerCx, rightCx, rightRotY, rightWidth}.
     */
    public float[] adbCarouselGeometry() {
        if (flowView == null || catalog == null || catalog.isEmpty()) return null;
        int focus = flowView.engine().getFocusIndex();
        float w = flowView.getWidth() > 0 ? flowView.getWidth() : 480f;
        float h = flowView.getHeight() > 0 ? flowView.getHeight() : 360f;
        FlowEngine.SlotTransform center = flowView.engine().slotTransform(focus, w, h, 0f);
        float rightCx = center.centerX;
        float rightRotY = 0f;
        float rightW = 0f;
        if (focus + 1 < catalog.size()) {
            FlowEngine.SlotTransform right = flowView.engine().slotTransform(focus + 1, w, h, 0f);
            rightCx = right.centerX;
            rightRotY = right.rotationYDeg;
            rightW = right.width;
        }
        return new float[] { focus, catalog.size(), center.centerX, rightCx, rightRotY, rightW };
    }
}
