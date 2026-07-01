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
        void playPodcastWithHandoff(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes,
                int index, Bitmap cover, RectF fromRect, float fromRotY);
        void playPodcastWithCrossfade(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes,
                int index);
        void playDeezerTracks(List<DeezerResult> tracks, int startIndex);
        void fetchPodcastEpisodes(String feedUrl, EpisodeCallback callback);
        void fetchDeezerPlaylistTracks(long playlistId, PlaylistTracksCallback callback);
        void saveLastFlowIndex(FlowMode mode, int index);
        int loadLastFlowIndex(FlowMode mode);
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
        FLOW_WORKER.execute(new Runnable() {
            @Override
            public void run() {
                final List<FlowItem> built = buildCatalog(FlowMode.ALBUM, libGen, optionsKey);
                catalogSessionCache.put(FlowMode.ALBUM, libGen, optionsKey, built);
                int center = actions.loadLastFlowIndex(FlowMode.ALBUM);
                if (center < 0 || center >= built.size()) center = 0;
                warmCatalogCoversSync(built, center, 2);
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

    /** Store resolved catalog for reuse — reverse handoff hot path. */
    public void ensureSessionCatalog(FlowMode mode, List<FlowItem> items) {
        if (mode == null || items == null || items.isEmpty()) return;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = actions.flowMultiTrackAlbumsOnly() ? 1 : 0;
        catalogSessionCache.put(mode, libGen, optionsKey, items);
    }

    /** Build + store session catalog on caller thread (worker-safe). */
    public List<FlowItem> buildAndCacheCatalog(FlowMode mode) {
        if (mode == null || mode == FlowMode.UNSPECIFIED) mode = FlowMode.ALBUM;
        int libGen = actions.libraryScanGeneration();
        int optionsKey = actions.flowMultiTrackAlbumsOnly() ? 1 : 0;
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
        flowView.resetFlip();
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
                flowView.setItems(cached);
                flowView.engine().setFocusIndex(idx);
                focusedItem = cached.get(idx);
                bound = true;
            }
        }
        if (!bound) {
            String key = coverKey != null ? coverKey : "";
            FlowItem shell = FlowItem.album(key, "", key,
                    java.util.Collections.<java.io.File>emptyList(), "");
            catalog = java.util.Collections.singletonList(shell);
            flowView.setItems(catalog);
            flowView.engine().setFocusIndex(0);
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
        flowView.setItems(catalog);
        int index = saved.carouselIndex;
        if (saved.carouselMatchKey != null && !saved.carouselMatchKey.isEmpty()) {
            int found = flowView.engine().findIndexForKey(catalog, saved.carouselMatchKey);
            if (found >= 0) index = found;
        }
        if (index < 0 || index >= catalog.size()) index = 0;
        flowView.engine().setFocusIndex(index);
        focusedItem = flowView.itemAt(index);
        flowView.restoreBackFace(null);
        syncCarouselToNowPlayingIfActive();
        warmCatalogCoversAsync(catalog, flowView.engine().getFocusIndex(), 2);
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
        int optionsKey = actions.flowMultiTrackAlbumsOnly() ? 1 : 0;
        return catalogSessionCache.peek(mode, libGen, optionsKey);
    }

    /** True when carousel can bind without a background catalog build. */
    public boolean hasCachedCatalog(FlowMode mode) {
        List<FlowItem> cached = peekSessionCatalog(mode);
        if (cached != null && !cached.isEmpty()) return true;
        return catalogSessionCache.peekStale(mode) != null;
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
        int optionsKey = actions.flowMultiTrackAlbumsOnly() ? 1 : 0;
        List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        if (cached != null) return cached;
        boolean multiTrack = optionsKey != 0;
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
     */
    public void performNpToFlowHandoffFromNowPlaying(Bitmap cover, String matchKey,
            FlowReturnState warmState, List<FlowItem> warmCatalog, RectF fromRect,
            Runnable onComplete) {
        if (flowView == null || cover == null || cover.isRecycled()) return;
        syncCarouselToNowPlayingIfActive();
        if (warmState != null) {
            prepareReturnForResolvedState(warmState, warmCatalog, cover);
        } else {
            prepareImmediateReverseHandoffShell(cover, matchKey);
        }
        ensureCarouselLayoutForHandoff();
        float toRotY = getHandoffStartRotationY();
        RectF npRect = fromRect;
        if (npRect == null || npRect.width() <= 0f) {
            npRect = actions.resolveNpHandoffFromScreenRect();
        }
        actions.launchReverseHandoff(cover, npRect, toRotY, onComplete);
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
        if (uiMode == UI_CAROUSEL && activeMode != FlowMode.UNSPECIFIED) {
            String key = focusedItem != null ? focusedItem.matchKey : null;
            showCarousel(activeMode, key);
        }
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
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        carouselLoadGen++;
        final int loadGen = carouselLoadGen;
        coverGen++;
        catalogBuildGen++;
        final int buildGen = catalogBuildGen;
        final long showCarouselStartMs = DebugSessionLog.ENABLED ? System.currentTimeMillis() : 0L;

        final int libGen = actions.libraryScanGeneration();
        final int optionsKey = actions.flowMultiTrackAlbumsOnly() ? 1 : 0;
        final List<FlowItem> cached = catalogSessionCache.peek(mode, libGen, optionsKey);
        if (cached != null && !cached.isEmpty()) {
            bindCarouselCatalog(cached, mode, focusKey, loadGen);
            logShowCarouselTiming(showCarouselStartMs, true, cached.size());
            return;
        }

        final List<FlowItem> stale = catalogSessionCache.peekStale(mode);
        if (stale != null && !stale.isEmpty()) {
            bindCarouselCatalog(stale, mode, focusKey, loadGen);
            logShowCarouselTiming(showCarouselStartMs, true, stale.size());
            scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen, buildGen, showCarouselStartMs);
            return;
        }

        showCarouselLoading();

        scheduleCatalogRebuild(mode, focusKey, libGen, optionsKey, loadGen, buildGen, showCarouselStartMs);
    }

    private void scheduleCatalogRebuild(final FlowMode mode, final String focusKey,
            final int libGen, final int optionsKey, final int loadGen, final int buildGen,
            final long showCarouselStartMs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<FlowItem> built = buildCatalog(mode, libGen, optionsKey);
                catalogSessionCache.put(mode, libGen, optionsKey, built);
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (buildGen != catalogBuildGen || loadGen != carouselLoadGen) return;
                        bindCarouselCatalog(built, mode, focusKey, loadGen);
                        logShowCarouselTiming(showCarouselStartMs, false, built.size());
                    }
                });
            }
        }, "FlowCatalog").start();
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
            boolean multiTrack = optionsKey != 0;
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

    private void bindCarouselCatalog(List<FlowItem> built, FlowMode mode, String focusKey, int loadGen) {
        if (loadGen != carouselLoadGen) return;
        syncReflectionPref();
        catalog = built;
        incrementalThumbCursor = 0;
        flowView.setItems(catalog);
        int index = 0;
        boolean syncedNp = syncCarouselToNowPlayingIfActive();
        if (!syncedNp) {
            if (focusKey != null && !focusKey.isEmpty()) {
                int found = flowView.engine().findIndexForKey(catalog, focusKey);
                if (found >= 0) index = found;
            } else {
                index = actions.loadLastFlowIndex(mode);
                if (index >= catalog.size()) index = 0;
            }
            flowView.engine().setFocusIndex(index);
            focusedItem = flowView.itemAt(index);
        } else {
            index = flowView.engine().getFocusIndex();
        }
        flowView.prefetchAround(index);
        flowView.invalidate();
        hideCarouselLoading();
        flowView.requestFocus();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("mode", mode.name());
            d.put("focusKey", focusKey != null ? focusKey : "");
            d.put("focusIndex", index);
            d.put("syncedNp", syncedNp);
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
        final int warmIndex = index;
        flowView.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (loadGen != carouselLoadGen) return;
                warmCatalogCoversAsync(catalog, warmIndex, 2);
                flowView.scheduleBakesAround(warmIndex, 2);
            }
        });
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
            com.solar.launcher.Debug1cf0c7Log.log(actions.activity(),
                    "FlowScreenHost.showCarouselLoading", "catalog loading", "H-B", d);
        } catch (Exception ignored) {}
        // #endregion
        actions.showFlowLoading(actions.activity().getString(R.string.flow_starting));
    }

    private void hideCarouselLoading() {
        if (!flowLoadingVisible) return;
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
                disk = FlowCoverResolver.resolve(item, this, thumbPx);
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
        if (flowView.engine().isCarouselScrolling()) return;
        warmCatalogCoversAsync(catalog, center, radius);
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
        for (int d = -radius; d <= radius; d++) {
            int idx = center + d;
            if (idx < 0 || idx >= n) continue;
            if (reverseHandoffActive && d == 0 && handoffPinnedCover != null) continue;
            FlowItem item = items.get(idx);
            if (item == null || item.coverKey == null || item.coverKey.isEmpty()) continue;
            if (coverCache.get(item.coverKey) != null) continue;
            Bitmap disk = FlowCoverResolver.resolveDiskCached(item, this, thumb);
            if (disk == null) {
                // Art-less albums get a cached ♪ placeholder — same key as Flow catalog.
                disk = FlowCoverResolver.resolve(item, this, thumb);
            }
            if (disk != null) {
                coverCache.put(item.coverKey, disk);
                warmed++;
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
}
