package com.solar.launcher.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.ui.HardwareButtonGlyph;
import com.solar.launcher.ui.RowBusyChrome;
import com.solar.launcher.DebugAgentLog;
import com.solar.launcher.DebugF9ef0bLog;
import com.solar.launcher.ConnectivityHelper;
import com.solar.launcher.FocusScrollHelper;
import com.solar.launcher.MoveRibbonTouch;
import com.solar.launcher.PlayQueue;
import com.solar.launcher.PlaybackCoordinator;
import com.solar.launcher.R;
import com.solar.launcher.SettingsScreens;
import com.solar.launcher.photos.PhotoLibrary;
import com.solar.launcher.photos.PhotoViewer;
import com.solar.launcher.photos.PhotoWallpaperHelper;
import com.solar.launcher.radio.FmBandPlan;
import com.solar.launcher.radio.RadioScrubMapping;
import com.solar.launcher.radio.RadioScrubMode;
import com.solar.launcher.radio.RadioSettings;
import com.solar.launcher.radio.fm.FmAirplaneModeHelper;
import com.solar.launcher.radio.fm.FmEngine;
import com.solar.launcher.radio.fm.FmJjPresetImport;
import com.solar.launcher.radio.fm.FmPresetStore;
import com.solar.launcher.radio.fm.FmQueueSync;
import com.solar.launcher.radio.fm.FmRecorder;
import com.solar.launcher.radio.fm.FmRdsPoller;
import com.solar.launcher.radio.net.InternetRadioFavorites;
import com.solar.launcher.radio.net.InternetRadioPlayer;
import com.solar.launcher.radio.net.RadioBrowserClient;
import com.solar.launcher.video.VideoLibrary;
import com.solar.launcher.video.VideoPlayerController;
import com.solar.launcher.video.VideoSeekPolicy;
import com.solar.launcher.youtube.YouTubeClient;
import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.CreatorDownloadLinkExtractor;
import com.solar.launcher.youtube.YouTubeDownloader;
import com.solar.launcher.youtube.YouTubeAcquisitionPolicy;
import com.solar.launcher.youtube.YouTubeBookmarks;
import com.solar.launcher.youtube.YouTubeDiscoverFeedback;
import com.solar.launcher.youtube.YouTubeLocalLibrarySignals;
import com.solar.launcher.youtube.YouTubeDiscoverRanker;
import com.solar.launcher.youtube.YouTubeDiscoverSignals;
import com.solar.launcher.youtube.YouTubeProgressiveCache;
import com.solar.launcher.youtube.YouTubeRecentSearches;
import com.solar.launcher.youtube.YouTubeResultJson;
import com.solar.launcher.youtube.YouTubeSavePaths;
import com.solar.launcher.youtube.YouTubeVideo;
import com.solar.launcher.youtube.official.YouTubeDeviceAuth;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tv.danmaku.ijk.media.example.widget.media.SurfaceRenderView;

/**
 * 2026-07-05 — Radio/video/photo browse host; Reach-like screens use full width (no preview pane).
 * Show loading placeholder rows before async work; cancel stale work on navigate-away.
 * When changing: new browse screens — hide settings preview pane, force full screen width.
 * Reversal: extract screens back to MainActivity monolith without layout policy comments.
 */
public final class MediaSuiteHost {

    // --- Screen states (keep in sync with MainActivity wiring) ---
    public static final int STATE_RADIO = 17;
    public static final int STATE_RADIO_FM_BROWSE = 18;
    public static final int STATE_RADIO_NET_BROWSE = 19;
    public static final int STATE_VIDEOS = 20;
    public static final int STATE_VIDEO_PLAYER = 21;
    public static final int STATE_PHOTOS = 22;
    public static final int STATE_PHOTO_VIEWER = 23;
    public static final int STATE_VIDEO_HUB = 27;
    public static final int STATE_YOUTUBE_BROWSE = 28;
    public static final int STATE_RADIO_FM_PLAYER = 29;
    /** Video detail + comments (messaging-style list) — never shows notPipe UI. */
    public static final int STATE_YOUTUBE_DETAIL = 30;

    /** MainActivity player screen — not owned here but used for radio handoff. */
    public static final int STATE_PLAYER = 3;

    // --- Radio browse sub-modes ---
    public static final int RADIO_UI_HUB = 0;
    public static final int RADIO_NET_COUNTRY = 1;
    public static final int RADIO_NET_STATE = 2;
    public static final int RADIO_NET_TAG = 3;
    public static final int RADIO_NET_STATIONS = 4;
    public static final int RADIO_NET_FAVORITES = 5;
    public static final int RADIO_FM_SCAN = 6;
    public static final int RADIO_FM_PRESETS = 7;
    public static final int RADIO_FM_SETTINGS = 8;
    public static final int RADIO_FM_SAVED_CHANNELS = 9;

    /**
     * FM recordings dir for new captures — prefers Primary storage pref volume.
     * 2026-07-15 — Was first existing folder / hardcoded sdcard0; now getNewMediaRoot first.
     */
    public static File fmRecordingsDir() {
        // null ctx still applies smart default (MicroSD if healthy, else Internal).
        File preferred = new File(com.solar.launcher.DeviceFeatures.getNewMediaRoot(null),
                "FM Recordings");
        if (!preferred.exists()) preferred.mkdirs();
        if (preferred.isDirectory()) return preferred;
        for (File dir : com.solar.launcher.DeviceFeatures.getFmRecordingRoots()) {
            if (dir.isDirectory()) return dir;
        }
        java.util.List<File> roots = com.solar.launcher.DeviceFeatures.getFmRecordingRoots();
        return roots.isEmpty()
                ? preferred
                : roots.get(0);
    }

    private static final int NET_PAGE_SIZE = 40;
    private static final String[] FM_BAND_REGIONS = {"US", "EU", "JP", "AU", "KR", "RU"};

    /** Settings row keys — pair with {@link SettingsScreens}. */
    public static final String ROW_AUTO_DETECT = "radio.auto_detect";
    public static final String ROW_BUFFER_SD = "radio.buffer_sd";
    public static final String ROW_VIDEO_SLEEP = "video.sleep_during_playback";
    /** 2026-07-15 — Letterbox vs crop-to-4:3 preference row. */
    public static final String ROW_VIDEO_CROP = "video.crop_mode";

    /** Now-playing scrub state — read/written by MainActivity wheel handlers. */
    public RadioScrubMode radioScrubMode = RadioScrubMode.NONE;
    public int radioTuneFreqKhz;
    private boolean fmSettingsMode;
    private boolean fmTuningMode;
    private long lastFmPowerToggleMs;
    private final List<Integer> fmScanResults = new ArrayList<Integer>();

    /** Host callback — MainActivity implements view + navigation chrome. */
    public interface Host {
        Context context();
        Activity activity();
        SharedPreferences prefs();
        PlaybackCoordinator playback();

        Button createListButton(String label);
        void clickFeedback();
        boolean requireInternet(int toastRes);
        void runOnUiThread(Runnable r);

        void changeScreen(int state);
        int getCurrentScreenState();
        void setBrowserStatusTitle(String title);

        View layoutBrowserMode();
        View layoutPlayerMode();
        View layoutMainMenu();
        View layoutSettingsMode();
        LinearLayout containerBrowserItems();
        ListView listVirtualSongs();

        int getScreenWidthPx();
        int y1RowHeightPx();
        int messagingRowWidthPx();

        void applyReachBrowseLayoutMode();
        void showReachBrowseList(boolean show);

        void pauseMusicPlayback();

        /** Stop and reset file music so radio streams / FM can take audio. */
        void stopMusicPlayback();

        /**
         * 2026-07-15 — Silence music / Deezer / podcast / YouTube / video before FM owns audio.
         * Does not touch the FM chip (caller powers up next).
         */
        void stopNonFmPlayback();

        MediaTransportBar playerTransportBar();

        MediaTransportBar videoTransportBar();

        void resetBrowserListHost();

        void showVirtualSongList(boolean virtual);
        /** Hide/show Solar status bar (clock, battery, Wi‑Fi). */
        void setStatusBarVisible(boolean visible);

        void refreshPlayerUi();

        /** Show FM MHz scrub marker when manual-tune mode is active. */
        void syncFmTuneScrubUi();

        /** Leave media browse and return to Solar home menu (MainActivity STATE_MENU). */
        void exitToHomeMenu();

        /**
         * 2026-07-15 — Leave Music→YouTube browse back to Music hub (STATE_BROWSER).
         * Was: Back always went to Videos hub. Reversal: changeScreen(STATE_VIDEO_HUB) always.
         */
        void exitYouTubeAudioToMusic();

        /** Open wheel keyboard for YouTube search — result delivered via {@link #onYouTubeSearchSubmitted}. */
        void openYouTubeSearchKeyboard(String prefill);

        /**
         * 2026-07-19 — Open wheel keyboard to find a local video by filename (My Videos lists).
         * Layman: type part of the name instead of scrolling a long video folder.
         */
        void openVideoFileSearchKeyboard();

        /**
         * 2026-07-20 — Open wheel keyboard for Online Radio name search.
         * Layman: type a station name; we look it up online. Technical: KEYBOARD_RADIO_NET_SEARCH.
         * Reversal: Search row toast-only (no IME).
         */
        void openRadioNetSearchKeyboard(String prefill);

        /** Search the existing authorized Soulseek provider using YouTube metadata. */
        void searchSoulseekForYouTube(YouTubeVideo video);

        /** Hand a validated creator-provided direct audio URL to the separate download provider. */
        void openAuthorizedDirectAudioUrl(String url);

        /**
         * 2026-07-15 — Play a local audio file in music Now Playing (YouTube Audio path).
         * Layman: open the song player with this file. Technical: playTrackList singleton.
         */
        void playAudioFileInNowPlaying(java.io.File file);

        /**
         * 2026-07-19 — YouTube Audio with catalog title/artist (avoids NP “Failed” from empty tags).
         * Was: file-only overload. Reversal: call file-only and ignore title/artist.
         */
        void playAudioFileInNowPlaying(java.io.File file, String title, String artist);

        /** Title + subtitle row for virtual browse lists (YouTube, podcasts pattern). */
        View createTwoLineBrowseRow(String title, String subtitle);

        /** Layered fallback — stock MTK FM when native engine fails. 2026-07-06 */
        void offerFmMtkFallback(String errorMessage);

        void showThemedConfirm(
                String title,
                String message,
                String confirmLabel,
                String cancelLabel,
                Runnable onConfirm,
                Runnable onCancel);

        /**
         * 2026-07-15 — True while user is actively typing/scrolling (InputPriorityGate).
         * Background media suite work (RDS JNI, etc.) should yield.
         */
        boolean isInputPriorityBusy();

        /** Ms until input has been idle long enough for background work. */
        long msUntilInputIdle();

        String getString(int resId);
        String getString(int resId, Object arg);
        String getString(int resId, Object arg1, Object arg2);
        android.content.res.Resources getResources();

        <T extends View> T findViewById(int id);
    }

    /** Row descriptor for Settings integration via {@link #buildRadioSettingsRows()}. */
    public static final class SettingsRow {
        public final String rowKey;
        public final int labelResId;
        public final boolean submenu;

        public SettingsRow(String rowKey, int labelResId, boolean submenu) {
            this.rowKey = rowKey;
            this.labelResId = labelResId;
            this.submenu = submenu;
        }
    }

    private final Host host;
    private final FmEngine fmEngine;
    private final FmRdsPoller fmRdsPoller;
    private final FmRecorder fmRecorder;
    private final Handler fmUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable fmRecordUiTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!fmRecorder.isRecording()) return;
                    host.refreshPlayerUi();
                    fmUiHandler.postDelayed(this, 1000L);
                }
            };
    /** MHz before manual tune scrub — Back reverts without leaving NP. 2026-07-06 */
    private int fmTuneRevertKhz;
    /** 2026-07-15 — Headset plug → re-route FM to headphones unless Speaker chosen. */
    private BroadcastReceiver fmHeadsetReceiver;
    private boolean fmHeadsetRegistered;
    private final RadioBrowserClient radioBrowser;
    private final InternetRadioFavorites netFavorites;
    private final FmPresetStore fmPresets;
    private final InternetRadioPlayer internetRadioPlayer;
    private final PhotoViewer photoViewer = new PhotoViewer();

    private int radioSubMode = RADIO_UI_HUB;
    private int netLoadGen;
    private String netCountryCode = "";
    private String netCountryName = "";
    private String netStateName = "";
    private String netTagName = "";
    private List<RadioBrowserClient.Country> netCountries = new ArrayList<RadioBrowserClient.Country>();
    private List<RadioBrowserClient.State> netStates = new ArrayList<RadioBrowserClient.State>();
    private List<RadioBrowserClient.Tag> netTags = new ArrayList<RadioBrowserClient.Tag>();
    private List<RadioBrowserClient.Station> netStations = new ArrayList<RadioBrowserClient.Station>();
    private boolean netLoading;
    /** 2026-07-20 — Name-search query; empty means country/tag browse. Reversal: always empty. */
    private String netSearchQuery = "";
    /** 2026-07-20 — True when station list came from IME search (Back → country hub). */
    private boolean netFromSearch;
    /** 2026-07-20 — Last page was full (NET_PAGE_SIZE) so Show more is offered. */
    private boolean netStationsHasMore;

    private File videoBrowseFolder;
    private List<File> videoFiles = new ArrayList<File>();
    private int videoIndex;
    private boolean videoPlaybackYoutube;
    private String youtubeStreamUrl;
    private final List<YouTubeVideo> youtubeVideos = new ArrayList<YouTubeVideo>();
    private int youtubeLoadGen;
    private int youtubeProbeGen;
    private boolean youtubeLoading;
    /** 2026-07-14 — Play resolve in progress (detail Play row subtitle); not browse list load. */
    private boolean youtubeResolvingStream;
    /**
     * 2026-07-15 — Human phase for resolve/save (“Getting 480p stream…”) instead of flat Resolving.
     * Empty when idle.
     */
    private String youtubeResolveStatus = "";
    /** 2026-07-14 — Quality used for current/last RESOLVE_STREAM (ladder retries). */
    private String youtubeStreamQuality;
    /** 2026-07-14 — Prevent double quality-fallback from rapid IJK error callbacks. */
    private boolean youtubeIjkFallbackPending;
    /**
     * 2026-07-19 — Already tried SolarHttp progressive download for this stream (avoid loops).
     * Layman: only download-to-file once per play attempt. Reversal: always false.
     */
    private boolean youtubeTriedProgDownload;
    /** 2026-07-19 — Cancel in-flight progressive download when leaving the player. */
    private final java.util.concurrent.atomic.AtomicBoolean youtubeProgCancel =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private String youtubePendingSearch;
    private String youtubeNextPageToken = "";
    private boolean youtubeAppending;
    private boolean youtubeShowingBookmarks;
    private boolean youtubeShowingDiscover;
    /** True when visible official metadata came from an expired offline cache entry. */
    private boolean youtubeMetadataStale;
    private boolean youtubeDiscoverSignalsLoading;
    private boolean youtubeDiscoverLocalSignalsLoading;
    private boolean youtubeDiscoverPopularStale;
    private final List<YouTubeVideo> youtubeDiscoverPopular =
            new ArrayList<YouTubeVideo>();
    private final List<String> youtubeDiscoverReasons = new ArrayList<String>();
    private YouTubeDiscoverSignals youtubeDiscoverSignals =
            new YouTubeDiscoverSignals(null, null, false, false);
    private YouTubeLocalLibrarySignals youtubeLocalLibrarySignals =
            YouTubeLocalLibrarySignals.empty();
    private String youtubeNowPlayingTitle;
    private String youtubeNowPlayingId;
    /**
     * 2026-07-15 — True when opened from Music hub / home YouTube Audio (music NP, not video).
     * Was: always video path from Videos hub. Reversal: force false; Play always video.
     */
    private boolean youtubeAudioMode;
    /** Focused video on detail/comments screen (Solar-only; notPipe never shown). */
    private YouTubeVideo youtubeDetailVideo;
    private final List<YouTubeComment> youtubeComments = new ArrayList<YouTubeComment>();
    private boolean youtubeCommentsLoading;
    private boolean youtubeCommentsStale;
    private int youtubeCommentsGen;
    private YouTubeBookmarks youtubeBookmarks;
    private YouTubeDiscoverFeedback youtubeDiscoverFeedback;
    private YouTubeDeviceAuth youtubeAuth;
    private final Handler youtubeAuthHandler = new Handler(Looper.getMainLooper());
    private final Runnable youtubeAuthTick = new Runnable() {
        @Override
        public void run() {
            if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
            rebuildYouTubeVirtualRows();
            notifyVirtualDataChangedPreserveFocus();
            YouTubeDeviceAuth.Snapshot current = youtubeAuth().snapshot();
            if (current.isActive()) youtubeAuthHandler.postDelayed(this, 1000L);
        }
    };
    private final List<YoutubeDetailRow> youtubeDetailRows = new ArrayList<YoutubeDetailRow>();
    private VideoPlayerController videoController;
    private SurfaceRenderView videoSurface;
    private final Handler videoProgressHandler = new Handler(Looper.getMainLooper());
    private boolean videoScrubActive;
    private long videoScrubMs;
    /**
     * 2026-07-18 — Last onBufferingUpdate percent (0–100) for stream scrub past edge.
     * Layman: how far the video has downloaded so far.
     */
    private int videoBufferPercent;
    /** Last target submitted to IJK/MediaPlayer; negative once completion or timeout is observed. */
    private long videoPendingSeekMs = -1L;
    private long videoSeekRequestedAtMs;

    private File photoBrowseFolder;
    private List<File> photoFiles = new ArrayList<File>();
    private List<File> photoFolders = new ArrayList<File>();
    private int photoLoadGen;

    private final List<String> virtualLabels = new ArrayList<String>();
    private final List<String> virtualSubtitles = new ArrayList<String>();
    /** Parallel row actions for YouTube virtual list — see {@link #rebuildYouTubeVirtualRows}. */
    private final List<YoutubeBrowseRow> youtubeBrowseRows = new ArrayList<YoutubeBrowseRow>();
    private SimpleListAdapter virtualAdapter;

    /** One YouTube browse list row — maps wheel position to action without fragile index math. */
    private static final class YoutubeBrowseRow {
        static final int KIND_BACK = 0;
        static final int KIND_SEARCH = 1;
        static final int KIND_CLEAR = 2;
        static final int KIND_RECENT = 3;
        static final int KIND_STATUS = 4;
        static final int KIND_VIDEO = 5;
        static final int KIND_ACCOUNT = 6;
        static final int KIND_MORE = 7;
        static final int KIND_BOOKMARKS = 8;
        static final int KIND_DISCOVER = 9;

        final int kind;
        final String recentQuery;
        final int videoIndex;

        YoutubeBrowseRow(int kind) {
            this(kind, null, -1);
        }

        YoutubeBrowseRow(int kind, String recentQuery, int videoIndex) {
            this.kind = kind;
            this.recentQuery = recentQuery;
            this.videoIndex = videoIndex;
        }
    }

    /**
     * Detail screen rows — Play / Save + comment thread (Soulseek messaging feel).
     * Layman: pick a video → chat-style comments, then Play stays in Solar.
     */
    private static final class YoutubeDetailRow {
        static final int KIND_BACK = 0;
        static final int KIND_PLAY = 1;
        static final int KIND_SAVE_VIDEO = 2;
        static final int KIND_SAVE_AUDIO = 3;
        static final int KIND_HEADER = 4;
        static final int KIND_COMMENT = 5;
        static final int KIND_STATUS = 6;
        static final int KIND_BOOKMARK = 7;
        static final int KIND_SOULSEEK = 8;
        static final int KIND_COPY_LINK = 9;
        static final int KIND_NOT_INTERESTED = 10;
        static final int KIND_MORE_LIKE = 11;
        static final int KIND_LESS_FROM_CHANNEL = 12;
        static final int KIND_CREATOR_DOWNLOAD = 13;

        final int kind;
        final int commentIndex;
        final String directUrl;

        YoutubeDetailRow(int kind) {
            this(kind, -1, null);
        }

        YoutubeDetailRow(int kind, int commentIndex) {
            this(kind, commentIndex, null);
        }

        YoutubeDetailRow(int kind, String directUrl) {
            this(kind, -1, directUrl);
        }

        YoutubeDetailRow(int kind, int commentIndex, String directUrl) {
            this.kind = kind;
            this.commentIndex = commentIndex;
            this.directUrl = directUrl;
        }
    }

    public MediaSuiteHost(Host host) {
        this.host = host;
        Context ctx = host.context();
        fmEngine = new FmEngine(ctx);
        fmRdsPoller = new FmRdsPoller(fmEngine);
        // 2026-07-15 — RDS JNI yields while the user is typing / wheeling elsewhere.
        fmRdsPoller.setDefer(
                new FmRdsPoller.Defer() {
                    @Override
                    public boolean shouldDefer() {
                        // Keep polling when FM NP is on screen — user is looking at the station.
                        int st = host.getCurrentScreenState();
                        if (st == STATE_PLAYER || st == STATE_RADIO_FM_PLAYER) {
                            return false;
                        }
                        return host.isInputPriorityBusy();
                    }

                    @Override
                    public long msUntilAllowed() {
                        return host.msUntilInputIdle();
                    }
                });
        fmRecorder = new FmRecorder(ctx);
        fmRecorder.setListener(
                new FmRecorder.Listener() {
                    @Override
                    public void onStateChanged(int state) {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (state == FmRecorder.STATE_RECORDING) {
                                            fmUiHandler.removeCallbacks(fmRecordUiTick);
                                            fmUiHandler.post(fmRecordUiTick);
                                        } else {
                                            fmUiHandler.removeCallbacks(fmRecordUiTick);
                                        }
                                        host.refreshPlayerUi();
                                    }
                                });
                    }

                    @Override
                    public void onError(final String message) {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(
                                                        host.context(),
                                                        message,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                        host.refreshPlayerUi();
                                    }
                                });
                    }
                });
        fmRdsPoller.setListener(
                new FmRdsPoller.Listener() {
                    @Override
                    public void onRdsChanged(String ps, String rt) {
                        cachedRdsPs = ps;
                        cachedRdsRt = rt;
                        if (ps != null && !ps.isEmpty() && host.playback().isFmActive()) {
                            host.playback()
                                    .updateCurrentFmMeta(currentFmFreqKhz(), ps);
                        }
                        // 2026-07-15 — Light bind only (not full refreshPlayerUi — was freezing NP).
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (host.playback().isFmActive()) {
                                            bindRadioNowPlayingUi();
                                        }
                                    }
                                });
                    }
                });
        radioBrowser = new RadioBrowserClient(ctx);
        netFavorites = InternetRadioFavorites.getInstance(ctx);
        fmPresets = FmPresetStore.getInstance(ctx);
        internetRadioPlayer = new InternetRadioPlayer(ctx);
        internetRadioPlayer.setListener(
                new InternetRadioPlayer.Listener() {
                    @Override
                    public void onPrepared() {}

                    @Override
                    public void onPlaying() {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        host.refreshPlayerUi();
                                    }
                                });
                    }

                    @Override
                    public void onStopped() {}

                    @Override
                    public void onError(final String reason) {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        // #region agent log
                                        try {
                                            DebugAgentLog.log(
                                                    host.context(),
                                                    "InternetRadioPlayer",
                                                    "error",
                                                    "E",
                                                    new org.json.JSONObject().put("reason", reason));
                                        } catch (Exception ignored) {}
                                        // #endregion
                                        if (host.playback().isInternetRadioActive()) {
                                            Toast.makeText(
                                                            host.context(),
                                                            R.string.radio_net_play_error,
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                        }
                                    }
                                });
                    }
                });
        radioTuneFreqKhz = defaultFmKhz();
    }

    public static boolean isMediaSuiteState(int state) {
        return (state >= STATE_RADIO && state <= STATE_PHOTO_VIEWER)
                || state == STATE_VIDEO_HUB || state == STATE_YOUTUBE_BROWSE
                || state == STATE_YOUTUBE_DETAIL
                || state == STATE_RADIO_FM_PLAYER;
    }

    /** Browse/list screens that share MainActivity browser wheel + focus (not full-screen player/viewer). */
    public static boolean isMediaListBrowseState(int state) {
        return state == STATE_RADIO || state == STATE_RADIO_FM_BROWSE
                || state == STATE_RADIO_FM_PLAYER
                || state == STATE_RADIO_NET_BROWSE || state == STATE_VIDEOS
                || state == STATE_VIDEO_HUB || state == STATE_YOUTUBE_BROWSE
                || state == STATE_YOUTUBE_DETAIL
                || state == STATE_PHOTOS;
    }

    public int radioSubMode() {
        return radioSubMode;
    }

    public InternetRadioPlayer internetRadioPlayer() {
        return internetRadioPlayer;
    }

    public FmEngine fmEngine() {
        return fmEngine;
    }

    // --- Lifecycle ---

    public void onScreenEnter(int state) {
        hideVideoAndPhotoLayers();
        switch (state) {
            case STATE_RADIO:
                radioSubMode = RADIO_UI_HUB;
                buildRadioHubUi();
                break;
            case STATE_RADIO_FM_BROWSE:
                radioSubMode = RADIO_UI_HUB;
                buildFmBrowseUi();
                break;
            case STATE_RADIO_FM_PLAYER:
                fmSettingsMode = false;
                fmTuningMode = false;
                buildFmPlayerUi();
                break;
            case STATE_RADIO_NET_BROWSE:
                if (radioSubMode == RADIO_UI_HUB) radioSubMode = RADIO_NET_COUNTRY;
                buildNetBrowseUi();
                break;
            case STATE_VIDEO_HUB:
                buildVideoHubUi();
                break;
            case STATE_VIDEOS:
                buildVideosUi();
                break;
            case STATE_YOUTUBE_BROWSE:
                buildYouTubeBrowseUi();
                break;
            case STATE_YOUTUBE_DETAIL:
                buildYouTubeDetailUi();
                break;
            case STATE_VIDEO_PLAYER:
                showVideoPlayerLayer(true);
                beginVideoForceLandscapeSession();
                startVideoPlayback();
                break;
            case STATE_PHOTOS:
                buildPhotosUi();
                break;
            case STATE_PHOTO_VIEWER:
                showPhotoViewerLayer(true);
                bindPhotoViewerImage();
                break;
            default:
                break;
        }
    }

    public void onScreenExit(int state) {
        switch (state) {
            case STATE_RADIO_NET_BROWSE:
                netLoadGen++;
                netLoading = false;
                break;
            case STATE_YOUTUBE_BROWSE:
                youtubeLoadGen++;
                youtubeProbeGen++;
                youtubeLoading = false;
                youtubeAppending = false;
                youtubeResolvingStream = false;
                youtubeAuthHandler.removeCallbacks(youtubeAuthTick);
                break;
            case STATE_YOUTUBE_DETAIL:
                youtubeCommentsGen++;
                youtubeCommentsLoading = false;
                youtubeResolvingStream = false;
                break;
            case STATE_VIDEO_PLAYER:
                releaseVideoPlayer();
                showVideoPlayerLayer(false);
                onVideoPlaybackStopped();
                endVideoForceLandscapeSession();
                break;
            case STATE_PHOTO_VIEWER:
                showPhotoViewerLayer(false);
                break;
            case STATE_RADIO_FM_BROWSE:
                if (radioSubMode == RADIO_FM_SCAN) {
                    fmEngine.stopScan();
                    radioSubMode = RADIO_UI_HUB;
                }
                break;
            case STATE_RADIO_FM_PLAYER:
                if (radioSubMode == RADIO_FM_SCAN) {
                    fmEngine.stopScan();
                }
                break;
            default:
                break;
        }
        clearVirtualList();
    }

    /** Rebuild visible tier after rotation or state restore. */
    public void rebuildUi(int state) {
        onScreenEnter(state);
    }

    public String statusTitleForState(int state) {
        switch (state) {
            case STATE_RADIO:
                return host.getString(R.string.status_radio);
            case STATE_RADIO_FM_BROWSE:
                return host.getString(R.string.status_radio_fm);
            case STATE_RADIO_FM_PLAYER:
                return host.getString(R.string.status_radio_fm);
            case STATE_RADIO_NET_BROWSE:
                return host.getString(R.string.status_radio_internet);
            case STATE_VIDEO_HUB:
                return host.getString(R.string.status_videos);
            case STATE_VIDEOS:
                return host.getString(R.string.status_videos);
            case STATE_YOUTUBE_BROWSE:
                return host.getString(youtubeShowingDiscover
                        ? R.string.youtube_discover_title
                        : R.string.status_youtube);
            case STATE_YOUTUBE_DETAIL:
                if (youtubeDetailVideo != null && youtubeDetailVideo.title.length() > 0) {
                    return youtubeDetailVideo.title;
                }
                return host.getString(R.string.status_youtube_detail);
            case STATE_VIDEO_PLAYER:
                if (videoPlaybackYoutube && youtubeNowPlayingTitle != null
                        && youtubeNowPlayingTitle.length() > 0) {
                    return youtubeNowPlayingTitle;
                }
                return host.getString(R.string.status_video_player);
            case STATE_PHOTOS:
                return host.getString(R.string.status_photos);
            case STATE_PHOTO_VIEWER:
                return host.getString(R.string.status_photo_viewer);
            default:
                return "";
        }
    }

    // --- Back navigation ---

    public boolean handleBack() {
        int state = host.getCurrentScreenState();
        switch (state) {
            case STATE_PHOTO_VIEWER:
                host.changeScreen(STATE_PHOTOS);
                return true;
            case STATE_PHOTOS:
                if (photoBrowseFolder != null) {
                    photoBrowseFolder = null;
                    buildPhotosUi();
                    return true;
                }
                host.exitToHomeMenu();
                return true;
            case STATE_VIDEO_PLAYER:
                if (videoScrubActive) {
                    cancelVideoScrub();
                    return true;
                }
                leaveVideoPlayerToBrowse();
                return true;
            case STATE_YOUTUBE_DETAIL:
                youtubeDetailVideo = null;
                youtubeComments.clear();
                host.changeScreen(STATE_YOUTUBE_BROWSE);
                return true;
            case STATE_YOUTUBE_BROWSE:
                // 2026-07-15 — Music entry returns to Music hub; Videos entry to video hub.
                if (youtubeAudioMode) {
                    host.exitYouTubeAudioToMusic();
                } else {
                    host.changeScreen(STATE_VIDEO_HUB);
                }
                return true;
            case STATE_VIDEOS:
                if (videoBrowseFolder == null) {
                    videoBrowseFolder = VideoLibrary.ROOT;
                }
                File parent = VideoLibrary.browseParent(videoBrowseFolder);
                if (parent != null) {
                    videoBrowseFolder = parent;
                    buildVideosUi();
                    return true;
                }
                videoBrowseFolder = null;
                host.changeScreen(STATE_VIDEO_HUB);
                return true;
            case STATE_VIDEO_HUB:
                host.exitToHomeMenu();
                return true;
            case STATE_RADIO_FM_BROWSE:
                if (radioSubMode == RADIO_FM_SCAN) {
                    fmEngine.stopScan();
                    radioSubMode = RADIO_UI_HUB;
                    buildFmBrowseUi();
                    return true;
                }
                if (radioSubMode == RADIO_FM_PRESETS) {
                    radioSubMode = RADIO_UI_HUB;
                    buildFmBrowseUi();
                    return true;
                }
                // 2026-07-15 — Leave FM domain only after Exit confirm when hardware is live.
                requestExitFmThen(new Runnable() {
                    @Override
                    public void run() {
                        if (!com.solar.launcher.radio.RadioExperiment.isInternetRadioEnabled(
                                host.prefs())) {
                            host.exitToHomeMenu();
                        } else {
                            host.changeScreen(STATE_RADIO);
                        }
                    }
                });
                return true;
            case STATE_RADIO_FM_PLAYER:
                if (radioSubMode == RADIO_FM_SAVED_CHANNELS) {
                    radioSubMode = RADIO_FM_SETTINGS;
                    fmSettingsMode = true;
                    buildFmSettingsSubmenuUi();
                    return true;
                }
                if (radioSubMode == RADIO_FM_SCAN) {
                    fmEngine.stopScan();
                    radioSubMode = RADIO_FM_SETTINGS;
                    fmSettingsMode = true;
                    buildFmSettingsSubmenuUi();
                    return true;
                }
                if (fmSettingsMode) {
                    fmSettingsMode = false;
                    fmTuningMode = false;
                    buildFmPlayerUi();
                    return true;
                }
                // 2026-07-15 — Root of FM shell: Exit confirm so chip powers down cleanly.
                requestExitFmThen(new Runnable() {
                    @Override
                    public void run() {
                        host.exitToHomeMenu();
                    }
                });
                return true;
            case STATE_RADIO_NET_BROWSE:
                return handleNetBrowseBack();
            case STATE_RADIO:
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("action", "exitToHomeMenu");
                    DebugAgentLog.log(host.context(), "MediaSuiteHost.handleBack", "radio root back", "H-BACK", d);
                } catch (Exception ignored) {}
                // #endregion
                host.exitToHomeMenu();
                return true;
            default:
                return false;
        }
    }

    private boolean handleNetBrowseBack() {
        switch (radioSubMode) {
            case RADIO_NET_STATIONS:
                if (netTagName != null && !netTagName.isEmpty()) {
                    radioSubMode = RADIO_NET_TAG;
                } else if (netStateName != null && !netStateName.isEmpty()) {
                    radioSubMode = RADIO_NET_STATE;
                } else {
                    radioSubMode = RADIO_NET_COUNTRY;
                }
                buildNetBrowseUi();
                return true;
            case RADIO_NET_TAG:
                if (netStateName != null && !netStateName.isEmpty()) {
                    radioSubMode = RADIO_NET_STATE;
                } else {
                    radioSubMode = RADIO_NET_COUNTRY;
                }
                buildNetBrowseUi();
                return true;
            case RADIO_NET_STATE:
                radioSubMode = RADIO_NET_COUNTRY;
                buildNetBrowseUi();
                return true;
            case RADIO_NET_FAVORITES:
            case RADIO_NET_COUNTRY:
                host.changeScreen(STATE_RADIO);
                return true;
            default:
                host.changeScreen(STATE_RADIO);
                return true;
        }
    }

    // --- Radio hub ---

    private void buildRadioHubUi() {
        prepareScrollBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(false);
        host.setBrowserStatusTitle(host.getString(R.string.status_radio));
        addBackRow(host.getString(R.string.radio_back_home));

        addActionRow(host.getString(R.string.radio_fm_row), new Runnable() {
            @Override
            public void run() {
                host.changeScreen(STATE_RADIO_FM_BROWSE);
            }
        });
        if (com.solar.launcher.radio.RadioExperiment.isInternetRadioEnabled(host.prefs())) {
            addActionRow(host.getString(R.string.radio_internet_row), new Runnable() {
                @Override
                public void run() {
                    if (!host.requireInternet(R.string.toast_internet_required)) return;
                    radioSubMode = RADIO_NET_COUNTRY;
                    host.changeScreen(STATE_RADIO_NET_BROWSE);
                }
            });
        }
        focusFirstBrowserChild();
    }

    // --- FM browse ---

    private void buildFmBrowseUi() {
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.status_radio_fm));
        addBackRow(host.getString(R.string.common_back_short));

        if (!fmEngine.isAvailable()) {
            addStatusRow(host.getString(R.string.radio_fm_unavailable));
            focusFirstBrowserChild();
            return;
        }

        addActionRow(host.getString(R.string.radio_fm_tune_manual), new Runnable() {
            @Override
            public void run() {
                FmBandPlan plan = currentFmPlan();
                radioTuneFreqKhz = plan.clampKhz(radioTuneFreqKhz > 0 ? radioTuneFreqKhz : defaultFmKhz());
                startFmStation(radioTuneFreqKhz, FmBandPlan.khzToFraction(radioTuneFreqKhz, plan));
            }
        });
        addActionRow(host.getString(R.string.radio_fm_scan), new Runnable() {
            @Override
            public void run() {
                startFmScan();
            }
        });
        addActionRow(host.getString(R.string.radio_fm_presets), new Runnable() {
            @Override
            public void run() {
                buildFmPresetsUi();
            }
        });
        if (host.playback().isFmActive() && fmEngine.isPowerUp()) {
            addActionRow(
                    fmRecorder.isRecording()
                            ? host.getString(R.string.radio_fm_record_stop)
                            : host.getString(R.string.radio_fm_record_start),
                    new Runnable() {
                        @Override
                        public void run() {
                            toggleFmRecording();
                        }
                    });
        }
        addActionRow(host.getString(R.string.radio_fm_recordings), new Runnable() {
            @Override
            public void run() {
                openFmRecordingsFolder();
            }
        });
        if (isFmSessionLive()) {
            addActionRow(host.getString(R.string.radio_fm_exit_row), new Runnable() {
                @Override
                public void run() {
                    promptExitFmToHome();
                }
            });
        }
        focusFirstBrowserChild();
    }

    /**
     * Home menu FM — import JJ presets once, open NP, auto-start last frequency.
     * 2026-07-15 — Prefer last tuned kHz (then session dial, then first preset, then band default).
     * Was: always jumped to first preset when any existed. Reversal: presets.get(0) wins again.
     */
    public void openFmFromHome() {
        // 2026-07-20 — Phone chrome: block non-MTK; warn then allow on MTK phones.
        // Was: always tried FM path. Reversal: delete gate block.
        try {
            boolean chrome = com.solar.launcher.phone.PhoneChromePolicy.active(host.context());
            com.solar.launcher.phone.PhoneFmGate.Decision gate =
                    com.solar.launcher.phone.PhoneFmGate.decideLive(chrome);
            if (gate == com.solar.launcher.phone.PhoneFmGate.Decision.BLOCK_NON_MTK) {
                host.offerFmMtkFallback(host.getString(R.string.radio_fm_phone_needs_mtk));
                return;
            }
            if (gate == com.solar.launcher.phone.PhoneFmGate.Decision.WARN_THEN_ALLOW) {
                host.showThemedConfirm(
                        host.getString(R.string.radio_fm_phone_mtk_warn_title),
                        host.getString(R.string.radio_fm_phone_mtk_warn_message),
                        host.getString(R.string.common_ok),
                        host.getString(R.string.common_cancel),
                        new Runnable() {
                            @Override
                            public void run() {
                                openFmFromHomeAfterGate();
                            }
                        },
                        null);
                return;
            }
        } catch (Throwable ignored) {}
        openFmFromHomeAfterGate();
    }

    /**
     * 2026-07-20 — Existing FM home entry after phone chrome gate (or when chrome inactive).
     * Home menu FM — import JJ presets once, open NP, auto-start last frequency.
     * 2026-07-15 — Prefer last tuned kHz (then session dial, then first preset, then band default).
     * Was: always jumped to first preset when any existed. Reversal: presets.get(0) wins again.
     */
    private void openFmFromHomeAfterGate() {
        FmJjPresetImport.importIfEmpty(host.context());
        if (!fmEngine.isAvailable()) {
            host.offerFmMtkFallback(host.getString(R.string.radio_fm_unavailable));
            return;
        }
        if (host.playback().isFmActive()) {
            host.changeScreen(STATE_PLAYER);
            host.refreshPlayerUi();
            return;
        }
        int khz = resolvePreferredFmKhz();
        startFmStation(khz, null);
    }

    /**
     * 2026-07-15 — True when FM chip/session is live (user must Exit to leave Solar FM).
     * Layman: radio is on or Solar is holding the FM RF session.
     */
    public boolean isFmSessionLive() {
        return host.playback().isFmActive()
                || fmEngine.isPowerUp()
                || FmAirplaneModeHelper.isSessionActive();
    }

    /**
     * 2026-07-15 — Back from shared Now Playing while FM is active → FM shell (not home).
     * Keeps hardware up; Exit from shell powers down.
     */
    public void leaveFmNowPlayingToShell() {
        fmSettingsMode = false;
        fmTuningMode = false;
        radioSubMode = RADIO_UI_HUB;
        host.changeScreen(STATE_RADIO_FM_PLAYER);
        buildFmPlayerUi();
    }

    /**
     * 2026-07-15 — Power down FM hardware + clear radio queue (no UI).
     * Layman: turn the radio off and free Wi‑Fi/airplane snapshot.
     * Technical: stop record/RDS/headset, powerDown (ends airplane session), stopRadio.
     */
    public void shutdownFmSession() {
        try {
            stopFmRecordingQuiet();
        } catch (Throwable ignored) {}
        try {
            stopFmRdsPolling();
        } catch (Throwable ignored) {}
        try {
            releaseFmHeadsetRouting();
        } catch (Throwable ignored) {}
        try {
            if (fmEngine.isPowerUp() || FmAirplaneModeHelper.isSessionActive()) {
                fmEngine.powerDown();
            }
        } catch (Throwable ignored) {}
        try {
            host.playback().stopRadio();
        } catch (Throwable ignored) {}
        fmMuted = false;
        fmTuningMode = false;
        radioScrubMode = RadioScrubMode.NONE;
        cachedRdsPs = null;
        cachedRdsRt = null;
    }

    /**
     * 2026-07-15 — If FM is live, show "Exit FM Radio?" then run {@code afterExit}; else run now.
     * Used for Back from FM menus/player and the Exit row.
     */
    public void requestExitFmThen(final Runnable afterExit) {
        if (!isFmSessionLive()) {
            if (afterExit != null) afterExit.run();
            return;
        }
        host.showThemedConfirm(
                host.getString(R.string.radio_fm_exit_title),
                host.getString(R.string.radio_fm_exit_message),
                host.getString(R.string.radio_fm_exit_confirm),
                host.getString(R.string.common_cancel),
                new Runnable() {
                    @Override
                    public void run() {
                        shutdownFmSession();
                        if (afterExit != null) afterExit.run();
                    }
                },
                null);
    }

    /** Explicit Exit row / context action — confirm then home. */
    public void promptExitFmToHome() {
        requestExitFmThen(new Runnable() {
            @Override
            public void run() {
                host.exitToHomeMenu();
            }
        });
    }

    /**
     * 2026-07-15 — Best FM dial for cold start: last saved → session → first preset → band mid.
     * Layman: reopen radio where you left it.
     */
    private int resolvePreferredFmKhz() {
        FmBandPlan plan = currentFmPlan();
        int last = RadioSettings.getLastFmKhz(host.context());
        if (last > 0) return plan.clampKhz(last);
        if (radioTuneFreqKhz > 0) return plan.clampKhz(radioTuneFreqKhz);
        List<FmPresetStore.Preset> presets = fmPresets.listAll();
        if (!presets.isEmpty()) return plan.clampKhz(presets.get(0).freqKhz);
        return defaultFmKhz();
    }

    /**
     * 2026-07-15 — FM player shell inspired by JJ: neon dial, candy presets, bottom settings.
     * Layman: big station number, saved channels, then power / fine-tune / audio / settings.
     * Technical: theme tokens only; wheel keeps list; candy strip for quick presets (JJ).
     * Fine tune = TUNE_FM scrub on wheel (same as NP).
     */
    private void buildFmPlayerUi() {
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.status_radio_fm));
        if (radioSubMode != RADIO_FM_SETTINGS && radioSubMode != RADIO_FM_SAVED_CHANNELS
                && radioSubMode != RADIO_FM_SCAN) {
            fmSettingsMode = false;
        }

        if (!fmEngine.isAvailable()) {
            addStatusRow(host.getString(R.string.radio_fm_unavailable));
            focusFirstBrowserChild();
            return;
        }

        // Headphone jack → assert headphone route whenever we paint the shell.
        if (fmEngine.isPowerUp()) {
            fmEngine.onHeadsetPlug(fmEngine.isWiredHeadsetOn());
        }

        final FmBandPlan plan = currentFmPlan();
        int khz = host.playback().isFmActive() ? currentFmFreqKhz() : radioTuneFreqKhz;
        if (khz <= 0) khz = resolvePreferredFmKhz();
        if (fmTuningMode && radioTuneFreqKhz > 0) khz = radioTuneFreqKhz;
        final boolean powered = fmEngine.isPowerUp() || host.playback().isFmActive();
        final float mhzF = khz / 1000f;

        // JJ layout order: dial → RDS → candy presets → actions → settings last.
        addFmDialPanel(mhzF, powered || fmTuningMode);

        if (powered) {
            String ps = cachedRdsPs;
            if (ps != null && !ps.isEmpty()) {
                addStatusRow(ps);
            } else if (fmEngine.isPowerUp()) {
                addStatusRow(host.getString(
                        fmEngine.isStereo() ? R.string.radio_fm_stereo : R.string.radio_fm_mono));
            }
            if (!fmEngine.isAudioPlaying() && fmEngine.isPowerUp()) {
                addStatusRow(host.getString(R.string.radio_fm_audio_silent_hint));
            }
        } else if (!fmTuningMode) {
            addStatusRow(host.getString(R.string.radio_fm_power_off_hint));
        }

        final List<FmPresetStore.Preset> presets = fmPresets.listAll();
        // JJ always shows candy when stations exist (touch + visual; wheel uses list below).
        if (!presets.isEmpty()) {
            addFmPresetCandyStrip(presets, plan, khz);
        }

        if (!powered) {
            addActionRow(host.getString(R.string.radio_fm_power_on_row), new Runnable() {
                @Override
                public void run() {
                    startFmStation(resolvePreferredFmKhz(), null);
                }
            });
        }

        final String tuneLabel = fmTuningMode
                ? host.getString(R.string.radio_fm_fine_tune_active)
                : host.getString(R.string.radio_fm_fine_tune);
        addActionRow(tuneLabel, new Runnable() {
            @Override
            public void run() {
                toggleFmFineTuneFromPlayer();
            }
        });

        // Audio: Wired / Bluetooth / Speaker — always visible on main shell (JJ speaker toggle).
        addActionRow(
                host.getString(R.string.radio_fm_audio_output_row) + ": " + fmAudioOutputLabel(),
                new Runnable() {
                    @Override
                    public void run() {
                        fmEngine.cycleAudioOutput();
                        // If jack is in, cycling away from Speaker still lands on headphones.
                        if (fmEngine.isWiredHeadsetOn()
                                && fmEngine.audioOutput()
                                        != com.solar.launcher.radio.fm.FmAudioRouter.Output.SPEAKER) {
                            fmEngine.setAudioOutput(
                                    com.solar.launcher.radio.fm.FmAudioRouter.Output.WIRED);
                        }
                        buildFmPlayerUi();
                        if (host.playback().isFmActive()) host.refreshPlayerUi();
                    }
                });

        addActionRow(host.getString(R.string.radio_fm_scan), new Runnable() {
            @Override
            public void run() {
                if (!fmEngine.isPowerUp()) {
                    startFmStationThenScan();
                    return;
                }
                startFmScanReplacePresets();
            }
        });

        if (presets.isEmpty()) {
            addStatusRow(host.getString(R.string.radio_fm_no_presets));
        } else {
            addStatusRow(host.getString(R.string.radio_fm_presets_header, presets.size()));
            for (final FmPresetStore.Preset p : presets) {
                String label =
                        p.label != null && !p.label.isEmpty()
                                ? p.label
                                : FmBandPlan.khzToFraction(p.freqKhz, plan) + " MHz";
                boolean onAir = Math.abs(p.freqKhz - khz) < 50 && powered;
                if (onAir) label = "▶ " + label;
                addActionRow(label, new Runnable() {
                    @Override
                    public void run() {
                        fmTuningMode = false;
                        radioScrubMode = RadioScrubMode.NONE;
                        startFmStation(p.freqKhz, p.label, true);
                    }
                });
            }
        }

        // 2026-07-15 — Explicit Exit so hardware power-down is intentional (Wi‑Fi restore).
        if (powered) {
            addActionRow(host.getString(R.string.radio_fm_exit_row), new Runnable() {
                @Override
                public void run() {
                    promptExitFmToHome();
                }
            });
        }

        // JJ: settings button sits at bottom of player shell.
        addActionRow(host.getString(R.string.radio_fm_settings_row), new Runnable() {
            @Override
            public void run() {
                fmSettingsMode = true;
                radioSubMode = RADIO_FM_SETTINGS;
                buildFmSettingsSubmenuUi();
            }
        });

        // JJ focuses the bottom controls after paint.
        LinearLayout box = host.containerBrowserItems();
        if (box != null && box.getChildCount() > 0) {
            final View last = box.getChildAt(box.getChildCount() - 1);
            box.post(new Runnable() {
                @Override
                public void run() {
                    if (last != null) last.requestFocus();
                }
            });
        } else {
            focusFirstBrowserChild();
        }
    }

    /**
     * 2026-07-15 — Enter/exit fine-tune from the FM list (same TUNE_FM scrub as Now Playing).
     * Layman: wheel becomes a dial; OK again saves the frequency.
     */
    private void toggleFmFineTuneFromPlayer() {
        fmTuningMode = !fmTuningMode;
        if (fmTuningMode) {
            radioScrubMode = RadioScrubMode.TUNE_FM;
            radioTuneFreqKhz = currentFmFreqKhz();
            if (radioTuneFreqKhz <= 0) radioTuneFreqKhz = resolvePreferredFmKhz();
            fmTuneRevertKhz = radioTuneFreqKhz;
            if (!fmEngine.isPowerUp()) {
                // Need chip live to hear while scrubbing.
                startFmStation(radioTuneFreqKhz, null);
            }
            if (host.playback().isFmActive()) {
                host.syncFmTuneScrubUi();
            }
        } else {
            radioScrubMode = RadioScrubMode.NONE;
            commitFmTuneScrub();
            if (host.playback().isFmActive()) {
                host.refreshPlayerUi();
            }
        }
        buildFmPlayerUi();
    }

    /** Power on then replace-presets scan (browse path). */
    private void startFmStationThenScan() {
        final int khz = resolvePreferredFmKhz();
        final FmBandPlan plan = currentFmPlan();
        radioTuneFreqKhz = plan.clampKhz(khz);
        if (!fmEngine.isAvailable()) {
            Toast.makeText(host.context(), R.string.toast_fm_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        stopOtherRadioPlayback(true);
        host.stopNonFmPlayback();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = fmEngine.playStation(radioTuneFreqKhz);
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            String err = fmEngine.lastError();
                            if (err == null || err.isEmpty()) {
                                err = host.getString(R.string.radio_fm_play_error);
                            }
                            Toast.makeText(host.context(), err, Toast.LENGTH_LONG).show();
                            host.offerFmMtkFallback(err);
                            return;
                        }
                        finishFmStationStart(radioTuneFreqKhz, null, plan);
                        startFmScanReplacePresets();
                    }
                });
            }
        }, "FmStartScan").start();
    }

    /**
     * 2026-07-15 — JJ neon dial: large MHz readout using theme focus colour when powered.
     * Layman: big station number that lights up when the radio is on.
     * Technical: ThemeManager list-focus colours + GradientDrawable panel; no hard-coded brand palette.
     */
    private void addFmDialPanel(float mhz, boolean powered) {
        Context ctx = host.context();
        float density = ctx.getResources().getDisplayMetrics().density;
        FrameLayout freqPanel = new FrameLayout(ctx);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Tighter on A5 240p; roomier on Y1/Y2 480×360.
        int side = com.solar.launcher.DeviceFeatures.isA5()
                ? Math.round(8 * density) : Math.round(14 * density);
        int vPad = com.solar.launcher.DeviceFeatures.isA5()
                ? Math.round(10 * density) : Math.round(28 * density);
        panelLp.setMargins(side, Math.round(10 * density), side, Math.round(8 * density));
        freqPanel.setLayoutParams(panelLp);

        int themeHighlight = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadius(12 * density);
        if (powered) {
            int backlit = (themeHighlight & 0x00FFFFFF) | 0x42000000;
            panelBg.setColor(backlit);
            panelBg.setStroke(Math.max(1, Math.round(3 * density)), themeHighlight);
        } else {
            // Dim chrome when off — still themed secondary, not pure black invent.
            int dim = ThemeManager.getListButtonNormalBg();
            if ((dim & 0xFF000000) == 0) dim = 0x22FFFFFF;
            panelBg.setColor(dim);
            panelBg.setStroke(Math.max(1, Math.round(1 * density)), 0x33FFFFFF);
        }
        freqPanel.setBackground(panelBg);

        TextView tvFreq = new TextView(ctx);
        tvFreq.setTag("fm_dial_freq");
        tvFreq.setText(String.format(java.util.Locale.US, "%.1f MHz", mhz));
        tvFreq.setTextColor(powered ? themeHighlight : ThemeManager.getTextColorSecondary());
        // JJ uses ~54sp on Y1; A5 240p stays smaller.
        tvFreq.setTextSize(com.solar.launcher.DeviceFeatures.isA5() ? 28f : 50f);
        tvFreq.setGravity(Gravity.CENTER);
        try {
            tvFreq.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        } catch (Throwable ignored) {
            tvFreq.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        tvFreq.setPadding(0, vPad, 0, vPad);
        // Dial is chrome, not a focus trap — wheel keeps list rows.
        tvFreq.setFocusable(false);
        tvFreq.setClickable(false);
        freqPanel.setFocusable(false);
        freqPanel.addView(tvFreq);
        host.containerBrowserItems().addView(freqPanel);
    }

    /**
     * 2026-07-15 — JJ candy presets: horizontal themed pills for A5 touch.
     * Layman: swipe/tap saved stations like JJ.
     * Technical: HorizontalScrollView; each pill starts FM; current freq uses focus colours.
     */
    private void addFmPresetCandyStrip(
            List<FmPresetStore.Preset> presets, final FmBandPlan plan, int currentKhz) {
        Context ctx = host.context();
        float density = ctx.getResources().getDisplayMetrics().density;
        android.widget.HorizontalScrollView hzScroll = new android.widget.HorizontalScrollView(ctx);
        hzScroll.setHorizontalScrollBarEnabled(false);
        hzScroll.setClipChildren(false);
        hzScroll.setClipToPadding(false);
        hzScroll.setFillViewport(true);
        hzScroll.setPadding(0, Math.round(6 * density), 0, Math.round(6 * density));
        hzScroll.setFocusable(false);

        LinearLayout candyContainer = new LinearLayout(ctx);
        candyContainer.setOrientation(LinearLayout.HORIZONTAL);
        candyContainer.setGravity(Gravity.CENTER_VERTICAL);

        int themeHighlight = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
        int focusedText = ThemeManager.getListButtonFocusedTextColor();
        int normalBg = ThemeManager.getListButtonNormalBg();
        int secondary = ThemeManager.getTextColorSecondary();

        View targetScrollChild = null;
        for (int i = 0; i < presets.size(); i++) {
            final FmPresetStore.Preset p = presets.get(i);
            final int pkhz = p.freqKhz;
            String label =
                    p.label != null && !p.label.isEmpty()
                            ? p.label
                            : FmBandPlan.khzToFraction(pkhz, plan);
            TextView tvCandy = new TextView(ctx);
            tvCandy.setText(label);
            tvCandy.setTextSize(com.solar.launcher.DeviceFeatures.isA5() ? 14f : 16f);
            tvCandy.setGravity(Gravity.CENTER);
            tvCandy.setPadding(
                    Math.round(12 * density), Math.round(5 * density),
                    Math.round(12 * density), Math.round(5 * density));
            try {
                tvCandy.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            } catch (Throwable ignored) {}
            tvCandy.setFocusable(true);
            tvCandy.setClickable(true);

            GradientDrawable candyBg = new GradientDrawable();
            candyBg.setCornerRadius(16 * density);
            boolean selected = Math.abs(currentKhz - pkhz) < 50;
            if (selected) {
                candyBg.setColor(themeHighlight);
                tvCandy.setTextColor(focusedText);
                targetScrollChild = tvCandy;
            } else {
                candyBg.setColor(normalBg);
                tvCandy.setTextColor(secondary);
            }
            tvCandy.setBackground(candyBg);

            LinearLayout.LayoutParams candyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            candyLp.setMargins(Math.round(4 * density), 0, Math.round(4 * density), 0);
            tvCandy.setLayoutParams(candyLp);
            tvCandy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.clickFeedback();
                    startFmStation(pkhz, p.label, true);
                    buildFmPlayerUi();
                }
            });
            candyContainer.addView(tvCandy);
        }

        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hzScroll.addView(candyContainer, containerLp);
        host.containerBrowserItems().addView(hzScroll);

        if (targetScrollChild != null) {
            final View focusChild = targetScrollChild;
            final android.widget.HorizontalScrollView scroll = hzScroll;
            hzScroll.post(new Runnable() {
                @Override
                public void run() {
                    int scrollX = focusChild.getLeft() - (scroll.getWidth() / 2)
                            + (focusChild.getWidth() / 2);
                    if (scrollX < 0) scrollX = 0;
                    scroll.scrollTo(scrollX, 0);
                }
            });
        }
    }

    /** FM settings submenu — power, tune, save, scan, speaker (JJ parity). */
    private void buildFmSettingsSubmenuUi() {
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.radio_fm_settings_title));
        addBackRow(host.getString(R.string.common_back_short));

        final FmBandPlan plan = currentFmPlan();
        int khz = currentFmFreqKhz();
        if (khz <= 0) khz = radioTuneFreqKhz > 0 ? radioTuneFreqKhz : defaultFmKhz();

        final boolean powered = fmEngine.isPowerUp();
        addActionRow(
                host.getString(R.string.radio_fm_power_row)
                        + ": "
                        + host.getString(powered ? R.string.common_on : R.string.common_off),
                new Runnable() {
                    @Override
                    public void run() {
                        long now = System.currentTimeMillis();
                        if (now - lastFmPowerToggleMs < 1500L) {
                            Toast.makeText(
                                            host.context(),
                                            R.string.radio_fm_power_wait,
                                            Toast.LENGTH_SHORT)
                                    .show();
                            return;
                        }
                        lastFmPowerToggleMs = now;
                        if (fmEngine.isPowerUp() || host.playback().isFmActive()) {
                            // Power off = full session exit (Wi‑Fi restore); stay on settings shell.
                            shutdownFmSession();
                        } else {
                            int f = radioTuneFreqKhz > 0 ? radioTuneFreqKhz : defaultFmKhz();
                            startFmStation(f, null);
                        }
                        buildFmSettingsSubmenuUi();
                    }
                });

        String tuneHint =
                fmTuningMode
                        ? host.getString(R.string.radio_fm_tuning_active)
                        : host.getString(R.string.radio_fm_tune_click);
        // 2026-07-18 — Settings row stays prose (String API); NP track line uses OK glyph.
        addActionRow(host.getString(R.string.radio_fm_tune_row) + ": " + tuneHint, new Runnable() {
            @Override
            public void run() {
                fmTuningMode = !fmTuningMode;
                if (fmTuningMode) {
                    radioScrubMode = RadioScrubMode.TUNE_FM;
                    radioTuneFreqKhz = currentFmFreqKhz();
                    fmTuneRevertKhz = radioTuneFreqKhz;
                } else {
                    radioScrubMode = RadioScrubMode.NONE;
                }
                buildFmSettingsSubmenuUi();
                // 2026-07-06 — NP transport scrub mirrors settings tune toggle when FM is foreground.
                if (host.playback().isFmActive()) {
                    if (fmTuningMode) {
                        host.syncFmTuneScrubUi();
                    } else {
                        host.refreshPlayerUi();
                    }
                }
            }
        });

        final int saveKhz = khz;
        final boolean isSaved = fmPresets.containsFreq(saveKhz);
        addActionRow(
                host.getString(isSaved ? R.string.radio_fm_channel_saved : R.string.radio_fm_save_channel),
                new Runnable() {
                    @Override
                    public void run() {
                        if (isSaved) {
                            fmPresets.delete(saveKhz);
                            Toast.makeText(host.context(), R.string.radio_fm_channel_removed, Toast.LENGTH_SHORT)
                                    .show();
                        } else {
                            fmPresets.upsert(
                                    saveKhz, FmBandPlan.khzToFraction(saveKhz, plan));
                            Toast.makeText(host.context(), R.string.radio_ctx_preset_saved, Toast.LENGTH_SHORT)
                                    .show();
                        }
                        buildFmSettingsSubmenuUi();
                    }
                });

        addActionRow(host.getString(R.string.radio_fm_saved_channels_row), new Runnable() {
            @Override
            public void run() {
                radioSubMode = RADIO_FM_SAVED_CHANNELS;
                buildFmSavedChannelsUi();
            }
        });

        addActionRow(host.getString(R.string.radio_fm_scan_all_row), new Runnable() {
            @Override
            public void run() {
                if (!fmEngine.isPowerUp()) {
                    Toast.makeText(host.context(), R.string.radio_fm_scan_power_first, Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                startFmScanReplacePresets();
            }
        });

        // 2026-07-15 — Wired / Bluetooth / Speaker (stock MTK force-use + user pick).
        addActionRow(
                host.getString(R.string.radio_fm_audio_output_row)
                        + ": "
                        + fmAudioOutputLabel(),
                new Runnable() {
                    @Override
                    public void run() {
                        fmEngine.cycleAudioOutput();
                        Toast.makeText(
                                        host.context(),
                                        host.getString(R.string.radio_fm_output_cycle_hint),
                                        Toast.LENGTH_SHORT)
                                .show();
                        buildFmSettingsSubmenuUi();
                        if (host.playback().isFmActive()) {
                            host.refreshPlayerUi();
                        }
                    }
                });

        addStatusRow(host.getString(R.string.radio_fm_onboarding_hint));
        // 2026-07-20 — Show auto-detect band + Scan-vs-streams note (Settings parity).
        addStatusRow(detectedFmBandSummary());
        addStatusRow(host.getString(R.string.radio_fm_stations_from_scan));
        focusFirstBrowserChild();
    }

    /**
     * Human line for auto-detected FM limits, e.g. Detected: EU (87.5–108).
     * 2026-07-20 — From locale/SIM/geo; not the manual override. Layman: what dial Solar guessed.
     */
    public String detectedFmBandSummary() {
        String region = RadioSettings.detectFmBandFromLocale(host.context());
        FmBandPlan plan = FmBandPlan.fromRegionCode(region);
        // Host getString max 2 args — build Detected line without a 3-arg overload.
        return host.getString(R.string.radio_fm_detected_band, region,
                FmBandPlan.formatMhz(plan.minMhz) + "–" + FmBandPlan.formatMhz(plan.maxMhz));
    }

    /** Label for current FM output mode (Wired / Bluetooth / Speaker). */
    private String fmAudioOutputLabel() {
        com.solar.launcher.radio.fm.FmAudioRouter.Output o = fmEngine.audioOutput();
        if (o == com.solar.launcher.radio.fm.FmAudioRouter.Output.SPEAKER) {
            return host.getString(R.string.radio_fm_output_speaker);
        }
        if (o == com.solar.launcher.radio.fm.FmAudioRouter.Output.BLUETOOTH) {
            return host.getString(R.string.radio_fm_output_bluetooth);
        }
        return host.getString(R.string.radio_fm_output_wired);
    }

    private void buildFmSavedChannelsUi() {
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        final List<FmPresetStore.Preset> presets = fmPresets.listAll();
        final FmBandPlan plan = currentFmPlan();
        for (int i = 0; i < presets.size(); i++) {
            FmPresetStore.Preset p = presets.get(i);
            String label =
                    p.label != null && !p.label.isEmpty()
                            ? p.label
                            : FmBandPlan.khzToFraction(p.freqKhz, plan);
            if (fmPresetMoveFrom >= 0 && i == fmPresetMoveFrom) {
                label = "↕ " + label;
            }
            virtualLabels.add(label);
        }
        if (presets.isEmpty()) {
            virtualLabels.add(host.getString(R.string.radio_fm_no_presets));
        }
        bindVirtualAdapter(
                new VirtualClickHandler() {
                    @Override
                    public void onClick(int position) {
                        if (position == 0) {
                            radioSubMode = RADIO_FM_SETTINGS;
                            buildFmSettingsSubmenuUi();
                            return;
                        }
                        if (presets.isEmpty()) return;
                        int idx = position - 1;
                        if (idx < 0 || idx >= presets.size()) return;
                        final FmPresetStore.Preset p = presets.get(idx);
                        startFmStation(p.freqKhz, p.label, true);
                        fmSettingsMode = false;
                        buildFmPlayerUi();
                    }
                });
    }

    /**
     * Tune ±step while fine-tune is active (player list or settings).
     * 2026-07-15 — Was settings-only; player Fine tune now uses the same path + NP scrub.
     */
    public boolean handleFmPlayerWheelTune(boolean up) {
        if (!fmTuningMode) return false;
        FmBandPlan plan = currentFmPlan();
        int khz = radioTuneFreqKhz > 0 ? radioTuneFreqKhz : currentFmFreqKhz();
        if (khz <= 0) khz = resolvePreferredFmKhz();
        khz = up ? khz + plan.stepKhz() : khz - plan.stepKhz();
        khz = plan.clampKhz(khz);
        radioTuneFreqKhz = khz;
        radioScrubMode = RadioScrubMode.TUNE_FM;
        if (fmEngine.isPowerUp()) {
            fmEngine.tune(khz);
        }
        // Live dial label without full list rebuild (keeps focus on Fine tune row).
        updateFmDialLabel(khz);
        if (host.playback().isFmActive()) {
            host.syncFmTuneScrubUi();
            host.refreshPlayerUi();
        }
        return true;
    }

    /** Update JJ dial MHz text in place during fine-tune wheel steps. */
    private void updateFmDialLabel(int khz) {
        LinearLayout container = host.containerBrowserItems();
        if (container == null) return;
        String text = String.format(java.util.Locale.US, "%.1f MHz", khz / 1000f);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            TextView dial = findTaggedTextView(child, "fm_dial_freq");
            if (dial != null) {
                dial.setText(text);
                return;
            }
        }
    }

    private static TextView findTaggedTextView(View root, String tag) {
        if (root == null) return null;
        if (root instanceof TextView && tag.equals(root.getTag())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView found = findTaggedTextView(vg.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void startFmScanReplacePresets() {
        radioSubMode = RADIO_FM_SCAN;
        fmScanResults.clear();
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.radio_fm_scanning));
        addBackRow(host.getString(R.string.common_cancel_back));
        final Button status = addStatusButton(host.getString(R.string.radio_fm_scan_starting));
        fmEngine.startScan(
                new FmEngine.ScanCallback() {
                    @Override
                    public void onStationFound(int freqKhz, int signal, boolean stereo) {
                        fmScanResults.add(freqKhz);
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText(
                                                host.getString(
                                                        R.string.radio_fm_scan_found,
                                                        FmBandPlan.khzToFraction(freqKhz, currentFmPlan())));
                                    }
                                });
                    }

                    @Override
                    public void onScanComplete() {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (fmScanResults.isEmpty()) {
                                            Toast.makeText(
                                                            host.context(),
                                                            R.string.radio_fm_scan_none,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                            radioSubMode = RADIO_FM_SETTINGS;
                                            buildFmSettingsSubmenuUi();
                                            return;
                                        }
                                        List<FmPresetStore.Preset> next = new ArrayList<FmPresetStore.Preset>();
                                        FmBandPlan plan = currentFmPlan();
                                        for (int khz : fmScanResults) {
                                            next.add(
                                                    new FmPresetStore.Preset(
                                                            0,
                                                            khz,
                                                            FmBandPlan.khzToFraction(khz, plan)));
                                        }
                                        fmPresets.replaceAll(next);
                                        int first = fmScanResults.get(0);
                                        FmQueueSync.syncQueueFromPresets(
                                                host.playback(), fmPresets, first);
                                        // Scan already found live hits — land exact on first.
                                        startFmStation(first, null, true);
                                        fmSettingsMode = false;
                                        buildFmPlayerUi();
                                        Toast.makeText(
                                                        host.context(),
                                                        host.getString(
                                                                R.string.radio_fm_scan_saved,
                                                                fmScanResults.size()),
                                                        Toast.LENGTH_LONG)
                                                .show();
                                    }
                                });
                    }

                    @Override
                    public void onError(final String reason) {
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText(
                                                host.getString(R.string.radio_fm_scan_error, reason));
                                    }
                                });
                    }
                });
        focusFirstBrowserChild();
    }

    private void buildFmPresetsUi() {
        radioSubMode = RADIO_FM_PRESETS;
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        final List<FmPresetStore.Preset> presets = fmPresets.listAll();
        for (int i = 0; i < presets.size(); i++) {
            FmPresetStore.Preset p = presets.get(i);
            String label = p.label != null && !p.label.isEmpty()
                    ? p.label : FmBandPlan.khzToFraction(p.freqKhz, currentFmPlan());
            if (fmPresetMoveFrom >= 0 && i == fmPresetMoveFrom) {
                label = "↕ " + label;
            }
            virtualLabels.add(label);
        }
        if (presets.isEmpty()) {
            virtualLabels.add(host.getString(R.string.radio_fm_no_presets));
        }
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    radioSubMode = RADIO_UI_HUB;
                    buildFmBrowseUi();
                    return;
                }
                if (presets.isEmpty()) return;
                int idx = position - 1;
                if (idx < 0 || idx >= presets.size()) return;
                FmPresetStore.Preset p = presets.get(idx);
                startFmStation(p.freqKhz, p.label, true);
            }
        });
    }

    /**
     * Browse-path band scan — collect hits, then show a pickable list (does not wipe presets).
     * 2026-07-15 — Was status-only with no selectable results. Reversal: drop fmScanResults usage here.
     */
    private void startFmScan() {
        radioSubMode = RADIO_FM_SCAN;
        fmScanResults.clear();
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.radio_fm_scanning));
        addBackRow(host.getString(R.string.common_cancel_back));
        final Button status = addStatusButton(host.getString(R.string.radio_fm_scan_starting));
        // #region agent log
        try {
            DebugF9ef0bLog.log(
                    host.context(),
                    "MediaSuiteHost.startFmScan",
                    "scan requested",
                    "H4",
                    new org.json.JSONObject().put("available", fmEngine.isAvailable()));
        } catch (Exception ignored) {}
        // #endregion
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            fmEngine.startScan(
                                    new FmEngine.ScanCallback() {
                                        @Override
                                        public void onStationFound(final int freqKhz, int signal, boolean stereo) {
                                            fmScanResults.add(freqKhz);
                                            host.runOnUiThread(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (host.getCurrentScreenState()
                                                                            != STATE_RADIO_FM_BROWSE
                                                                    || radioSubMode != RADIO_FM_SCAN)
                                                                return;
                                                            status.setText(
                                                                    host.getString(
                                                                            R.string.radio_fm_scan_found,
                                                                            FmBandPlan.khzToFraction(
                                                                                    freqKhz, currentFmPlan())));
                                                        }
                                                    });
                                        }

                                        @Override
                                        public void onScanComplete() {
                                            host.runOnUiThread(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (host.getCurrentScreenState()
                                                                            != STATE_RADIO_FM_BROWSE
                                                                    || radioSubMode != RADIO_FM_SCAN)
                                                                return;
                                                            showFmScanResultsUi();
                                                        }
                                                    });
                                        }

                                        @Override
                                        public void onError(final String reason) {
                                            // #region agent log
                                            try {
                                                DebugF9ef0bLog.log(
                                                        host.context(),
                                                        "MediaSuiteHost.startFmScan",
                                                        "scan error",
                                                        "H4",
                                                        new org.json.JSONObject().put("reason", reason));
                                            } catch (Exception ignored) {}
                                            // #endregion
                                            host.runOnUiThread(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (host.getCurrentScreenState()
                                                                    != STATE_RADIO_FM_BROWSE) return;
                                                            status.setText(
                                                                    host.getString(
                                                                            R.string.radio_fm_scan_error,
                                                                            reason));
                                                        }
                                                    });
                                        }
                                    });
                        } catch (Throwable t) {
                            // #region agent log
                            try {
                                DebugF9ef0bLog.log(
                                        host.context(),
                                        "MediaSuiteHost.startFmScan",
                                        "scan crash",
                                        "H4",
                                        new org.json.JSONObject()
                                                .put("err", t.getClass().getSimpleName()));
                            } catch (Exception ignored) {}
                            // #endregion
                            host.runOnUiThread(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            status.setText(
                                                    host.getString(
                                                            R.string.radio_fm_scan_error,
                                                            t.getClass().getSimpleName()));
                                        }
                                    });
                        }
                    }
                },
                "FmScan")
                .start();
        focusFirstBrowserChild();
    }

    /**
     * 2026-07-15 — After browse scan: list found MHz for OK-to-play (presets unchanged).
     * Layman: pick a station the scan just found without wiping your saved list.
     */
    private void showFmScanResultsUi() {
        prepareScrollBrowse();
        host.setBrowserStatusTitle(host.getString(R.string.radio_fm_scan_results_title));
        addBackRow(host.getString(R.string.common_back_short));
        if (fmScanResults.isEmpty()) {
            addStatusRow(host.getString(R.string.radio_fm_scan_none));
            focusFirstBrowserChild();
            return;
        }
        addStatusRow(host.getString(R.string.radio_fm_scan_done));
        final FmBandPlan plan = currentFmPlan();
        for (final Integer khzObj : fmScanResults) {
            final int khz = khzObj != null ? khzObj.intValue() : 0;
            if (khz <= 0) continue;
            final String label = FmBandPlan.khzToFraction(khz, plan) + " MHz";
            addActionRow(label, new Runnable() {
                @Override
                public void run() {
                    startFmStation(khz, label, true);
                }
            });
        }
        focusFirstBrowserChild();
    }

    private void openFmRecordingsFolder() {
        File dir = fmRecordingsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(host.context(), R.string.radio_fm_recordings_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        final File openDir = dir;
        // 2026-07-15 — Videos browse is outside FM; Exit first so hardware shuts down.
        requestExitFmThen(new Runnable() {
            @Override
            public void run() {
                videoBrowseFolder = openDir;
                host.changeScreen(STATE_VIDEOS);
            }
        });
    }

    /** Stop the other radio path before starting FM or internet playback. */
    private void stopOtherRadioPlayback(boolean startingFm) {
        if (startingFm) {
            internetRadioPlayer.stop();
        } else {
            // Internet radio takes over — full FM shutdown (chip + session).
            shutdownFmSession();
        }
    }

    /**
     * 2026-07-15 — Listen for earphone plug so FM re-routes off the speaker (stock behaviour).
     * Layman: plug headphones in → sound leaves the speaker unless you chose Speaker.
     */
    private void ensureFmHeadsetRouting() {
        if (fmHeadsetRegistered) return;
        fmHeadsetReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || !Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                            return;
                        }
                        int state = intent.getIntExtra("state", 0);
                        boolean in = state == 1;
                        fmEngine.onHeadsetPlug(in);
                        if (host.playback().isFmActive()) {
                            host.refreshPlayerUi();
                        }
                        if (host.getCurrentScreenState() == STATE_RADIO_FM_PLAYER) {
                            buildFmPlayerUi();
                        }
                    }
                };
        try {
            host.context()
                    .registerReceiver(fmHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            fmHeadsetRegistered = true;
        } catch (Throwable ignored) {
            fmHeadsetRegistered = false;
        }
    }

    private void releaseFmHeadsetRouting() {
        if (!fmHeadsetRegistered || fmHeadsetReceiver == null) return;
        try {
            host.context().unregisterReceiver(fmHeadsetReceiver);
        } catch (Throwable ignored) {}
        fmHeadsetRegistered = false;
        fmHeadsetReceiver = null;
    }

    /**
     * Power on and play. Auto-seeks to a live station when {@code exactStation} is false
     * (cold start / power button) — car-stereo behaviour.
     */
    private void startFmStation(final int freqKhz, final String label) {
        startFmStation(freqKhz, label, false /* exact — auto-seek if dead air */);
    }

    /**
     * @param exactStation true = user/preset/queue picked this MHz (do not auto-seek away)
     */
    private void startFmStation(final int freqKhz, final String label, final boolean exactStation) {
        final FmBandPlan plan = currentFmPlan();
        final int clampedKhz = plan.clampKhz(freqKhz);
        radioTuneFreqKhz = clampedKhz;
        if (!fmEngine.isAvailable()) {
            Toast.makeText(host.context(), R.string.toast_fm_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        // 2026-07-15 — FM exclusive: stop internet radio + music/Deezer/YouTube/video/podcast first.
        stopOtherRadioPlayback(true);
        host.stopNonFmPlayback();
        // 2026-07-06 — MTK bind/tune off UI thread; avoids ANR and browse crash on slow FMRadioService.
        // 2026-07-15 — After power, auto-seek if dead air (car radio / handheld auto-seek).
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = fmEngine.playStation(clampedKhz);
                        int landed = clampedKhz;
                        if (ok && !exactStation) {
                            int sought = fmEngine.seekFirstStationIfWeak(clampedKhz, plan);
                            if (sought > 0) landed = sought;
                        }
                        final int finalKhz = landed;
                        final String finalLabel =
                                (label != null && !label.isEmpty())
                                        ? label
                                        : FmBandPlan.khzToFraction(finalKhz, plan);
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!ok) {
                                            String err = fmEngine.lastError();
                                            if (err == null || err.isEmpty()) {
                                                err = host.getString(R.string.radio_fm_play_error);
                                            }
                                            Toast.makeText(host.context(), err, Toast.LENGTH_LONG).show();
                                            host.offerFmMtkFallback(err);
                                            return;
                                        }
                                        radioTuneFreqKhz = finalKhz;
                                        finishFmStationStart(finalKhz, finalLabel, plan);
                                    }
                                });
                    }
                },
                "FmStart")
                .start();
    }

    /** UI thread — FM powered; land on Now Playing with volume wheel (OK enters tune). 2026-07-06 */
    private void finishFmStationStart(int freqKhz, String label, FmBandPlan plan) {
        fmMuted = false;
        cachedRdsPs = null;
        cachedRdsRt = null;
        fmRdsPoller.invalidateCache();
        if (label == null || label.isEmpty()) {
            label = FmBandPlan.khzToFraction(freqKhz, plan);
        }
        // 2026-07-15 — Remember dial for next cold start (last station restore).
        RadioSettings.setLastFmKhz(host.context(), freqKhz);
        host.playback().startRadioStation(PlayQueue.QueueItem.fmStation(freqKhz, label));
        if (!fmPresets.listAll().isEmpty()) {
            FmQueueSync.syncQueueFromPresets(host.playback(), fmPresets, freqKhz);
        }
        radioScrubMode = RadioScrubMode.NONE;
        fmTuneRevertKhz = freqKhz;
        ensureFmRdsPolling();
        ensureFmHeadsetRouting();
        // Jack in → headphones unless user locked Speaker.
        fmEngine.onHeadsetPlug(fmEngine.isWiredHeadsetOn());
        primeFmRdsCacheAsync();
        if (host.getCurrentScreenState() == STATE_RADIO_FM_PLAYER) {
            buildFmPlayerUi();
        } else {
            host.changeScreen(STATE_PLAYER);
        }
        host.refreshPlayerUi();
        // #region agent log
        try {
            DebugF9ef0bLog.log(
                    host.context(),
                    "MediaSuiteHost.startFmStation",
                    "fm np ready volume wheel",
                    "H5",
                    new org.json.JSONObject()
                            .put("freqKhz", freqKhz)
                            .put("scrubMode", radioScrubMode.name()));
        } catch (Exception ignored) {}
        // #endregion
    }

    /** First RDS read off UI thread — avoids ANR right after tune. 2026-07-06 */
    private void primeFmRdsCacheAsync() {
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        final String ps = fmEngine.getRdsPs();
                        final String rt = fmEngine.getRdsRt();
                        fmRdsPoller.primeCache(ps, rt);
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        cachedRdsPs = ps;
                                        cachedRdsRt = rt;
                                        if (ps != null && !ps.isEmpty() && host.playback().isFmActive()) {
                                            host.playback().updateCurrentFmMeta(currentFmFreqKhz(), ps);
                                        }
                                        host.refreshPlayerUi();
                                    }
                                });
                    }
                },
                "FmRdsPrime")
                .start();
    }

    /** Live FM frequency for NP scrub UI — tune mode uses wheel scratch MHz, not queue row. 2026-07-06 */
    public int fmFreqKhz() {
        if (radioScrubMode == RadioScrubMode.TUNE_FM && radioTuneFreqKhz > 0) {
            return radioTuneFreqKhz;
        }
        return currentFmFreqKhz();
    }

    public FmBandPlan fmBandPlan() {
        return currentFmPlan();
    }

    // --- Internet radio browse ---

    /**
     * Online Radio browse shell — country → state → tag → stations (or IME search).
     * 2026-07-20 — Pin/focus home country; Search uses Solar IME; Show more pages.
     */
    private void buildNetBrowseUi() {
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        switch (radioSubMode) {
            case RADIO_NET_FAVORITES:
                buildNetFavoritesUi();
                break;
            case RADIO_NET_STATIONS:
                showNetStationsUi();
                break;
            case RADIO_NET_TAG:
                loadNetTagsAsync();
                break;
            case RADIO_NET_STATE:
                loadNetStatesAsync();
                break;
            case RADIO_NET_COUNTRY:
            default:
                buildNetCountryHubUi();
                break;
        }
    }

    /**
     * Country hub chrome: Back / Favorites / Search + country rows.
     * 2026-07-20 — Search opens IME (was toast). Layman: pick a country or type a name.
     */
    private void buildNetCountryHubUi() {
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.radio_net_favorites));
        virtualLabels.add(host.getString(R.string.radio_net_search));
        virtualLabels.add(host.getString(R.string.radio_net_loading_countries));
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    host.changeScreen(STATE_RADIO);
                    return;
                }
                if (position == 1) {
                    radioSubMode = RADIO_NET_FAVORITES;
                    buildNetFavoritesUi();
                    return;
                }
                if (position == 2) {
                    // 2026-07-20 — Was toast; now Solar IME → searchByName. Reversal: toast-only.
                    if (!host.requireInternet(R.string.toast_internet_required)) return;
                    host.openRadioNetSearchKeyboard(netSearchQuery);
                    return;
                }
                int countryIdx = position - 3;
                if (countryIdx >= 0 && countryIdx < netCountries.size()) {
                    RadioBrowserClient.Country c = netCountries.get(countryIdx);
                    netCountryCode = c.isoCode;
                    netCountryName = c.name;
                    netStateName = "";
                    netTagName = "";
                    netSearchQuery = "";
                    netFromSearch = false;
                    // 2026-07-20 — New country clears remembered state until user picks again.
                    RadioSettings.setInternetRadioState(host.context(), "");
                    radioSubMode = RADIO_NET_STATE;
                    loadNetStatesAsync();
                }
            }
        });
        loadNetCountriesAsync();
    }

    /**
     * Fetch countries, pin effective country first, focus that row (do not auto-enter).
     * 2026-07-20 — Geo/pref pin so home country sits under Search chrome.
     */
    private void loadNetCountriesAsync() {
        final int gen = ++netLoadGen;
        netLoading = true;
        final String pinIso = RadioSettings.effectiveInternetRadioCountry(host.context());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.Country> loaded = new ArrayList<RadioBrowserClient.Country>();
                String err = null;
                try {
                    loaded = radioBrowser.listCountries();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                // 2026-07-20 — Move detected/saved country to top; rest keep API order.
                final List<RadioBrowserClient.Country> ordered = pinCountryFirst(loaded, pinIso);
                final String fErr = err;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen || radioSubMode != RADIO_NET_COUNTRY) return;
                        netLoading = false;
                        netCountries = ordered;
                        virtualLabels.clear();
                        virtualSubtitles.clear();
                        virtualLabels.add(host.getString(R.string.common_back_short));
                        virtualLabels.add(host.getString(R.string.radio_net_favorites));
                        virtualLabels.add(host.getString(R.string.radio_net_search));
                        if (fErr != null) {
                            virtualLabels.add(host.getString(R.string.radio_net_load_error, fErr));
                            if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                            return;
                        }
                        if (ordered.isEmpty()) {
                            virtualLabels.add(host.getString(R.string.radio_net_no_countries));
                            if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                            return;
                        }
                        for (RadioBrowserClient.Country c : ordered) {
                            virtualLabels.add(c.name + " (" + c.stationcount + ")");
                        }
                        if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                        // 2026-07-20 — Focus pinned country (index 3 after Back/Fav/Search); OK still required.
                        focusVirtualListAt(3);
                    }
                });
            }
        }).start();
    }

    /**
     * Put matching ISO country first when present; otherwise leave list as returned.
     * 2026-07-20 — Pin only; never invent a fake country row.
     */
    private static List<RadioBrowserClient.Country> pinCountryFirst(
            List<RadioBrowserClient.Country> src, String iso) {
        List<RadioBrowserClient.Country> out = new ArrayList<RadioBrowserClient.Country>();
        if (src == null || src.isEmpty()) return out;
        String want = iso == null ? "" : iso.trim().toUpperCase(Locale.US);
        RadioBrowserClient.Country pinned = null;
        for (RadioBrowserClient.Country c : src) {
            if (c == null) continue;
            if (pinned == null && want.length() == 2
                    && want.equalsIgnoreCase(c.isoCode != null ? c.isoCode.trim() : "")) {
                pinned = c;
            } else {
                out.add(c);
            }
        }
        if (pinned != null) {
            out.add(0, pinned);
        }
        return out;
    }

    /**
     * Load Radio Browser states for the chosen country; focus saved state if any.
     * 2026-07-20 — Persist on pick; focus remembered row without auto-enter.
     */
    private void loadNetStatesAsync() {
        final int gen = ++netLoadGen;
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.radio_net_loading_states));
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    radioSubMode = RADIO_NET_COUNTRY;
                    buildNetCountryHubUi();
                    return;
                }
                int idx = position - 2;
                if (idx >= 0 && idx < netStates.size()) {
                    netStateName = netStates.get(idx).name;
                    // 2026-07-20 — Remember region so next visit can focus this row.
                    RadioSettings.setInternetRadioState(host.context(), netStateName);
                    radioSubMode = RADIO_NET_TAG;
                    loadNetTagsAsync();
                } else if (netStates.isEmpty() && position == 2) {
                    radioSubMode = RADIO_NET_TAG;
                    loadNetTagsAsync();
                }
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.State> loaded = new ArrayList<RadioBrowserClient.State>();
                String err = null;
                try {
                    loaded = radioBrowser.listStates(netCountryCode);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<RadioBrowserClient.State> fLoaded = loaded;
                final String fErr = err;
                final String savedState = RadioSettings.getInternetRadioState(host.context());
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen) return;
                        netStates = fLoaded;
                        virtualLabels.clear();
                        virtualSubtitles.clear();
                        virtualLabels.add(host.getString(R.string.common_back_short));
                        virtualLabels.add(host.getString(R.string.radio_net_country_header, netCountryName));
                        if (fErr != null) {
                            virtualLabels.add(host.getString(R.string.radio_net_load_error, fErr));
                            if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                            return;
                        }
                        if (fLoaded.isEmpty()) {
                            virtualLabels.add(host.getString(R.string.radio_net_skip_states));
                            if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                            radioSubMode = RADIO_NET_TAG;
                            loadNetTagsAsync();
                            return;
                        }
                        int focusIdx = -1;
                        for (int i = 0; i < fLoaded.size(); i++) {
                            RadioBrowserClient.State s = fLoaded.get(i);
                            virtualLabels.add(s.name + " (" + s.stationcount + ")");
                            if (focusIdx < 0 && savedState.length() > 0
                                    && savedState.equalsIgnoreCase(s.name)) {
                                focusIdx = i;
                            }
                        }
                        if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                        // 2026-07-20 — Focus matching saved state (list index = 2 + i); still need OK.
                        if (focusIdx >= 0) {
                            focusVirtualListAt(2 + focusIdx);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * Genre tags for the country; first action row is All genres (tag=null stations).
     * 2026-07-20 — Explicit All genres; was tags-only. Reversal: drop All genres row.
     */
    private void loadNetTagsAsync() {
        final int gen = ++netLoadGen;
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.radio_net_loading_tags));
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    if (netStates.isEmpty()) {
                        radioSubMode = RADIO_NET_COUNTRY;
                        buildNetCountryHubUi();
                    } else {
                        radioSubMode = RADIO_NET_STATE;
                        loadNetStatesAsync();
                    }
                    return;
                }
                // 2026-07-20 — pos 1 header; 2 All genres (when present); 3+ tag rows.
                if (position == 2) {
                    if (position < virtualLabels.size()
                            && host.getString(R.string.radio_net_all_genres)
                                    .equals(virtualLabels.get(2))) {
                        netTagName = "";
                        netFromSearch = false;
                        netSearchQuery = "";
                        radioSubMode = RADIO_NET_STATIONS;
                        loadNetStationsAsync();
                    }
                    return;
                }
                int idx = position - 3;
                if (idx >= 0 && idx < netTags.size()) {
                    netTagName = netTags.get(idx).name;
                    netFromSearch = false;
                    netSearchQuery = "";
                    radioSubMode = RADIO_NET_STATIONS;
                    loadNetStationsAsync();
                }
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.Tag> loaded = new ArrayList<RadioBrowserClient.Tag>();
                String err = null;
                try {
                    loaded = radioBrowser.listTags(60);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<RadioBrowserClient.Tag> fLoaded = loaded;
                final String fErr = err;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen) return;
                        netTags = fLoaded;
                        virtualLabels.clear();
                        virtualSubtitles.clear();
                        virtualLabels.add(host.getString(R.string.common_back_short));
                        virtualLabels.add(host.getString(R.string.radio_net_tags_header, netCountryName));
                        if (fErr != null) {
                            virtualLabels.add(host.getString(R.string.radio_net_load_error, fErr));
                        } else {
                            // 2026-07-20 — Always offer All genres even when tag list is empty.
                            virtualLabels.add(host.getString(R.string.radio_net_all_genres));
                            if (fLoaded.isEmpty()) {
                                // Keep All genres; no per-tag rows.
                            } else {
                                for (RadioBrowserClient.Tag t : fLoaded) {
                                    virtualLabels.add(t.name + " (" + t.stationcount + ")");
                                }
                            }
                        }
                        if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    /**
     * First page of stations for country/state/tag (or empty tag = all genres).
     * 2026-07-20 — Offset 0; Show more appends. Loading stays on this screen.
     */
    private void loadNetStationsAsync() {
        final int gen = ++netLoadGen;
        netFromSearch = false;
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.radio_net_loading_stations));
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                handleNetStationsClick(position);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.Station> loaded = new ArrayList<RadioBrowserClient.Station>();
                String err = null;
                try {
                    loaded = radioBrowser.searchStations(netCountryCode,
                            netStateName.isEmpty() ? null : netStateName,
                            netTagName.isEmpty() ? null : netTagName,
                            NET_PAGE_SIZE, 0);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<RadioBrowserClient.Station> fLoaded = loaded;
                final String fErr = err;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen) return;
                        netStations = fLoaded != null ? fLoaded
                                : new ArrayList<RadioBrowserClient.Station>();
                        netStationsHasMore = netStations.size() == NET_PAGE_SIZE;
                        if (fErr != null) {
                            prepareVirtualListBrowse();
                            virtualLabels.clear();
                            virtualSubtitles.clear();
                            virtualLabels.add(host.getString(R.string.common_back_short));
                            virtualLabels.add(host.getString(R.string.radio_net_load_error, fErr));
                            bindVirtualAdapter(new VirtualClickHandler() {
                                @Override
                                public void onClick(int position) {
                                    if (position == 0) handleNetStationsBack();
                                }
                            });
                            return;
                        }
                        showNetStationsUi();
                    }
                });
            }
        }).start();
    }

    /**
     * IME name search → station list (country filter from effective/current country).
     * 2026-07-20 — Cancels via netLoadGen when user leaves. Reversal: toast Search only.
     */
    public void onRadioNetSearchSubmitted(String query) {
        if (query == null || query.trim().isEmpty()) return;
        if (!host.requireInternet(R.string.toast_internet_required)) return;
        netSearchQuery = query.trim();
        netFromSearch = true;
        netTagName = "";
        if (netCountryCode == null || netCountryCode.isEmpty()) {
            netCountryCode = RadioSettings.effectiveInternetRadioCountry(host.context());
        }
        radioSubMode = RADIO_NET_STATIONS;
        if (host.getCurrentScreenState() != STATE_RADIO_NET_BROWSE) {
            host.changeScreen(STATE_RADIO_NET_BROWSE);
        }
        loadNetStationsFromSearchAsync(false);
    }

    /**
     * Run searchByName page 0 or append Show more pages.
     * 2026-07-20 — append=true keeps existing rows; bump gen only on fresh search.
     */
    private void loadNetStationsFromSearchAsync(final boolean append) {
        final int gen = append ? netLoadGen : ++netLoadGen;
        if (!append) {
            prepareVirtualListBrowse();
            virtualLabels.clear();
            virtualSubtitles.clear();
            virtualLabels.add(host.getString(R.string.common_back_short));
            virtualLabels.add(host.getString(R.string.radio_net_loading_stations));
            bindVirtualAdapter(new VirtualClickHandler() {
                @Override
                public void onClick(int position) {
                    handleNetStationsClick(position);
                }
            });
        }
        netLoading = true;
        final int offset = append ? netStations.size() : 0;
        final String q = netSearchQuery;
        final String country = netCountryCode;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.Station> loaded = new ArrayList<RadioBrowserClient.Station>();
                String err = null;
                try {
                    loaded = radioBrowser.searchByName(q, country, NET_PAGE_SIZE, offset);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<RadioBrowserClient.Station> fLoaded = loaded;
                final String fErr = err;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen) return;
                        netLoading = false;
                        if (fErr != null && !append) {
                            prepareVirtualListBrowse();
                            virtualLabels.clear();
                            virtualSubtitles.clear();
                            virtualLabels.add(host.getString(R.string.common_back_short));
                            virtualLabels.add(host.getString(R.string.radio_net_load_error, fErr));
                            bindVirtualAdapter(new VirtualClickHandler() {
                                @Override
                                public void onClick(int position) {
                                    if (position == 0) handleNetStationsBack();
                                }
                            });
                            return;
                        }
                        if (!append) {
                            netStations = fLoaded != null ? fLoaded
                                    : new ArrayList<RadioBrowserClient.Station>();
                        } else if (fLoaded != null) {
                            netStations.addAll(fLoaded);
                        }
                        netStationsHasMore = fLoaded != null && fLoaded.size() == NET_PAGE_SIZE;
                        showNetStationsUi();
                    }
                });
            }
        }).start();
    }

    /**
     * Append next offset page for browse or search when Show more is tapped.
     * 2026-07-20 — Does not bump netLoadGen so a fresh search can still cancel us.
     */
    private void loadMoreNetStationsAsync() {
        if (netLoading || !netStationsHasMore) return;
        if (netFromSearch) {
            loadNetStationsFromSearchAsync(true);
            return;
        }
        final int gen = netLoadGen;
        netLoading = true;
        final int offset = netStations.size();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RadioBrowserClient.Station> loaded = new ArrayList<RadioBrowserClient.Station>();
                try {
                    loaded = radioBrowser.searchStations(netCountryCode,
                            netStateName.isEmpty() ? null : netStateName,
                            netTagName.isEmpty() ? null : netTagName,
                            NET_PAGE_SIZE, offset);
                } catch (Exception ignored) {}
                final List<RadioBrowserClient.Station> fLoaded = loaded;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != netLoadGen) return;
                        netLoading = false;
                        if (fLoaded != null) netStations.addAll(fLoaded);
                        netStationsHasMore = fLoaded != null && fLoaded.size() == NET_PAGE_SIZE;
                        showNetStationsUi();
                    }
                });
            }
        }).start();
    }

    /**
     * Bind station list with codec·bitrate subtitles and optional Show more.
     * 2026-07-20 — Subtitles + pagination. Reversal: name-only rows, offset stuck at 0.
     */
    private void showNetStationsUi() {
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualSubtitles.add("");
        String header;
        if (netFromSearch && netSearchQuery != null && netSearchQuery.length() > 0) {
            header = netSearchQuery;
        } else if (netTagName != null && !netTagName.isEmpty()) {
            header = netTagName;
        } else if (netTagName != null && netTagName.isEmpty() && !netFromSearch) {
            header = host.getString(R.string.radio_net_all_genres);
        } else {
            header = netCountryName;
        }
        virtualLabels.add(host.getString(R.string.radio_net_stations_header, header));
        virtualSubtitles.add("");
        if (netStations.isEmpty()) {
            virtualLabels.add(host.getString(R.string.radio_net_no_stations));
            virtualSubtitles.add("");
        } else {
            for (RadioBrowserClient.Station s : netStations) {
                virtualLabels.add(s.name);
                virtualSubtitles.add(stationMetaSubtitle(s));
            }
            if (netStationsHasMore) {
                virtualLabels.add(host.getString(R.string.radio_net_show_more));
                virtualSubtitles.add("");
            }
        }
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                handleNetStationsClick(position);
            }
        });
    }

    /** Back from stations: search → country hub; browse → tags. 2026-07-20 */
    private void handleNetStationsBack() {
        if (netFromSearch) {
            netFromSearch = false;
            netSearchQuery = "";
            radioSubMode = RADIO_NET_COUNTRY;
            buildNetCountryHubUi();
        } else {
            radioSubMode = RADIO_NET_TAG;
            loadNetTagsAsync();
        }
    }

    /** Station / Show more / Back clicks on the stations list. 2026-07-20 */
    private void handleNetStationsClick(int position) {
        if (position == 0) {
            handleNetStationsBack();
            return;
        }
        int idx = position - 2;
        if (idx >= 0 && idx < netStations.size()) {
            startInternetStation(netStations.get(idx));
            return;
        }
        // Show more sits after the last station when hasMore.
        if (netStationsHasMore && position == 2 + netStations.size()) {
            loadMoreNetStationsAsync();
        }
    }

    /**
     * Codec · bitrate subtitle when either is present; empty otherwise.
     * 2026-07-20 — Prefer combined string; fail-open blank. Layman: shows stream quality under the name.
     */
    private String stationMetaSubtitle(RadioBrowserClient.Station s) {
        if (s == null) return "";
        boolean hasCodec = s.codec != null && s.codec.trim().length() > 0;
        boolean hasBr = s.bitrate > 0;
        if (hasCodec && hasBr) {
            return host.getString(R.string.radio_net_station_meta, s.codec.trim(), s.bitrate);
        }
        if (hasCodec) return host.getString(R.string.radio_net_station_codec, s.codec.trim());
        if (hasBr) return host.getString(R.string.radio_net_station_bitrate, s.bitrate);
        return "";
    }

    /** Focus a virtual list row after async fill (country/state pin). 2026-07-20 */
    private void focusVirtualListAt(final int position) {
        final ListView lv = host.listVirtualSongs();
        if (lv == null || position < 0) return;
        lv.post(new Runnable() {
            @Override
            public void run() {
                if (virtualAdapter == null) return;
                if (position >= virtualAdapter.getCount()) return;
                FocusScrollHelper.focusListPosition(lv, position);
            }
        });
    }

    /**
     * Saved favorites list — play on OK; long-OK removes via context.
     * 2026-07-20 — Same play path as browse (click-report on start).
     */
    private void buildNetFavoritesUi() {
        prepareVirtualListBrowse();
        virtualLabels.clear();
        virtualSubtitles.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        final List<InternetRadioFavorites.Favorite> favs = netFavorites.listAll();
        if (favs.isEmpty()) {
            virtualLabels.add(host.getString(R.string.radio_net_no_favorites));
        } else {
            for (InternetRadioFavorites.Favorite f : favs) {
                virtualLabels.add(f.name);
            }
        }
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    radioSubMode = RADIO_NET_COUNTRY;
                    buildNetCountryHubUi();
                    return;
                }
                if (favs.isEmpty()) return;
                int idx = position - 1;
                if (idx < 0 || idx >= favs.size()) return;
                InternetRadioFavorites.Favorite f = favs.get(idx);
                RadioBrowserClient.Station s = new RadioBrowserClient.Station(
                        f.stationuuid, f.name, f.url, f.countrycode, "", "");
                startInternetStation(s);
            }
        });
    }

    /**
     * Focused Online Radio station for long-OK context (browse or favorites).
     * 2026-07-20 — Mirrors YouTube getFocusedYouTubeVideo. Reversal: NP-only favorites.
     */
    public RadioBrowserClient.Station getFocusedNetStation() {
        if (host.getCurrentScreenState() != STATE_RADIO_NET_BROWSE) return null;
        ListView lv = host.listVirtualSongs();
        if (lv == null) return null;
        int pos = lv.getSelectedItemPosition();
        if (pos < 0) return null;
        if (radioSubMode == RADIO_NET_STATIONS) {
            int idx = pos - 2;
            if (idx >= 0 && idx < netStations.size()) return netStations.get(idx);
            return null;
        }
        if (radioSubMode == RADIO_NET_FAVORITES) {
            List<InternetRadioFavorites.Favorite> favs = netFavorites.listAll();
            int idx = pos - 1;
            if (idx < 0 || idx >= favs.size()) return null;
            InternetRadioFavorites.Favorite f = favs.get(idx);
            return new RadioBrowserClient.Station(
                    f.stationuuid, f.name, f.url, f.countrycode, "", "");
        }
        return null;
    }

    /**
     * Toggle favorite for a browse/NP station; returns true if now favorited.
     * 2026-07-20 — Shared by context menu (browse + NP).
     */
    public boolean toggleNetFavorite(RadioBrowserClient.Station station) {
        if (station == null || station.stationuuid == null || station.stationuuid.isEmpty()) {
            return false;
        }
        if (netFavorites.isFavorite(station.stationuuid)) {
            netFavorites.remove(station.stationuuid);
            return false;
        }
        netFavorites.add(station);
        return true;
    }

    /** True when stationuuid is in the favorites DB. 2026-07-20 */
    public boolean isNetFavorite(String stationuuid) {
        return netFavorites.isFavorite(stationuuid);
    }

    /**
     * Start stream + queue + click-report, then open Now Playing.
     * 2026-07-20 — Keep reportClick on every play (directory health).
     */
    private void startInternetStation(final RadioBrowserClient.Station station) {
        if (station == null || station.urlResolved == null || station.urlResolved.isEmpty()) {
            Toast.makeText(host.context(), R.string.radio_net_play_error, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!host.requireInternet(R.string.toast_internet_required)) return;
        stopOtherRadioPlayback(false);
        host.stopNonFmPlayback();
        // #region agent log
        try {
            DebugAgentLog.log(
                    host.context(),
                    "MediaSuiteHost.startInternetStation",
                    "play",
                    "E",
                    new org.json.JSONObject()
                            .put("name", station.name)
                            .put("urlLen", station.urlResolved.length()));
        } catch (Exception ignored) {}
        // #endregion
        try {
            internetRadioPlayer.play(station.urlResolved);
        } catch (Exception e) {
            Toast.makeText(host.context(), R.string.radio_net_play_error, Toast.LENGTH_SHORT).show();
            // #region agent log
            try {
                DebugAgentLog.log(
                        host.context(),
                        "MediaSuiteHost.startInternetStation",
                        "play threw",
                        "E",
                        new org.json.JSONObject().put("err", e.getClass().getSimpleName()));
            } catch (Exception ignored2) {}
            // #endregion
            return;
        }
        host.playback().startRadioStation(PlayQueue.QueueItem.internetRadio(
                station.stationuuid, station.name, station.urlResolved,
                station.countrycode, station.favicon));
        // 2026-07-20 — Directory click-report (popularity); keep on every successful play start.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    radioBrowser.reportClick(station.stationuuid);
                } catch (Exception ignored) {}
            }
        }).start();
        host.changeScreen(STATE_PLAYER);
        host.refreshPlayerUi();
    }

    // --- Radio now playing helpers ---

    /** Cached NP strings — avoid setText/JNI when nothing changed (FM NP perf). */
    private String npBoundTitle = "";
    private String npBoundArtist = "";
    private String npBoundAlbum = "";
    private String npBoundTrack = "";
    private int npBoundKhz = -1;
    private boolean npBoundPause;
    private boolean npFmArtSet;
    private int npBoundVol = -1;
    private int npBoundVolMax = -1;

    /**
     * Bind Now Playing title/artist lines for FM or internet radio.
     * 2026-07-15 — Skip setText / isStereo / art reload when unchanged (was freezing wheel on FM NP).
     */
    public void bindRadioNowPlayingUi() {
        PlaybackCoordinator playback = host.playback();
        if (!playback.isRadioActive()) return;
        PlayQueue.QueueItem cur = playback.unifiedQueue().current();
        if (cur == null) return;

        android.widget.TextView title = host.findViewById(R.id.tv_player_title);
        android.widget.TextView artist = host.findViewById(R.id.tv_player_artist);
        android.widget.TextView album = host.findViewById(R.id.tv_player_album);
        android.widget.TextView trackCount = host.findViewById(R.id.tv_player_track_count);
        android.widget.TextView vizTitle = host.findViewById(R.id.tv_viz_title);
        android.widget.TextView vizArtist = host.findViewById(R.id.tv_viz_artist);
        android.widget.TextView vizAlbum = host.findViewById(R.id.tv_viz_album);
        android.widget.ImageView pauseOverlay = host.findViewById(R.id.iv_pause_overlay);

        String titleText = cur.streamMeta();
        String artistText = "";
        String albumText = "";
        CharSequence trackCountText = "";
        boolean showPause = false;
        int khz = 0;

        if (playback.isFmActive()) {
            String ps = cachedRdsPs;
            if (ps != null && !ps.isEmpty()) titleText = ps;
            String rt = cachedRdsRt;
            if (rt != null && !rt.isEmpty()) {
                artistText = rt;
            }
            // 2026-07-06 — tune wheel MHz beats queue row until user commits (MTK live dial).
            khz = (radioScrubMode == RadioScrubMode.TUNE_FM && radioTuneFreqKhz > 0)
                    ? radioTuneFreqKhz
                    : fmFreqKhz();
            String mhz = FmBandPlan.formatMhz(khz / 1000f);
            // isStereo is JNI — only re-query when MHz changes (not every RDS/volume tick).
            if (fmEngine.isPowerUp()) {
                if (khz != npBoundKhz || npBoundAlbum.isEmpty()) {
                    albumText = host.getString(
                            fmEngine.isStereo()
                                    ? R.string.radio_fm_mhz_stereo
                                    : R.string.radio_fm_mhz_mono,
                            mhz);
                } else {
                    albumText = npBoundAlbum;
                }
            } else {
                albumText = mhz;
            }
            if (fmRecorder.isRecording()) {
                trackCountText = formatFmRecordingStatus();
            } else if (fmSeekBusy) {
                trackCountText = host.getString(R.string.radio_fm_seeking);
            } else if (radioScrubMode == RadioScrubMode.TUNE_FM) {
                // 2026-07-18 — OK glyph + save; was radio_fm_tuning_hint prose.
                trackCountText = HardwareButtonGlyph.tuningPressOkSave(host.context());
            }
            showPause = fmMuted;
            // Placeholder art once per FM session — not every bind/RDS tick.
            if (!npFmArtSet) {
                android.widget.ImageView albumArt = host.findViewById(R.id.iv_album_art);
                if (albumArt != null) {
                    albumArt.setImageResource(R.drawable.radio_fm_np_placeholder);
                    albumArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                }
                npFmArtSet = true;
            }
        } else if (playback.isInternetRadioActive()) {
            npFmArtSet = false;
            if (cur.radioSubtitle != null && !cur.radioSubtitle.isEmpty()) {
                artistText = cur.radioSubtitle;
            } else {
                artistText = host.getString(R.string.status_radio_internet);
            }
            showPause = !internetRadioPlayer.isPlaying();
        }

        setTextIfChanged(title, titleText);
        setTextIfChanged(artist, artistText);
        setTextIfChanged(album, albumText);
        if (trackCount != null
                && (!playback.isFmActive()
                        || radioScrubMode == RadioScrubMode.TUNE_FM
                        || fmRecorder.isRecording()
                        || fmSeekBusy)) {
            setTextIfChanged(trackCount, trackCountText);
        }
        setTextIfChanged(vizTitle, titleText);
        setTextIfChanged(vizArtist, artistText);
        setTextIfChanged(vizAlbum, albumText);
        if (pauseOverlay != null && showPause != npBoundPause) {
            pauseOverlay.setVisibility(showPause ? View.VISIBLE : View.GONE);
        }
        android.widget.ImageView albumArt = host.findViewById(R.id.iv_album_art);
        if (albumArt != null && showPause != npBoundPause) {
            albumArt.setAlpha(showPause ? 0.4f : 1.0f);
        }
        npBoundTitle = titleText != null ? titleText : "";
        npBoundArtist = artistText != null ? artistText : "";
        npBoundAlbum = albumText != null ? albumText : "";
        npBoundTrack = trackCountText != null ? trackCountText.toString() : "";
        npBoundKhz = khz;
        npBoundPause = showPause;
        // Progress/volume only when scrubbing or volume may have changed — not full JNI path.
        updateRadioPlayerProgress();
    }

    private static void setTextIfChanged(android.widget.TextView tv, String text) {
        setTextIfChanged(tv, (CharSequence) text);
    }

    /** 2026-07-18 — CharSequence overload so FM tune can show OK glyph. */
    private static void setTextIfChanged(android.widget.TextView tv, CharSequence text) {
        if (tv == null) return;
        CharSequence t = text != null ? text : "";
        CharSequence cur = tv.getText();
        if (cur != null && t.toString().contentEquals(cur.toString())
                && !(t instanceof android.text.Spanned)) {
            return;
        }
        // 2026-07-20 — Glyph spans need system typeface (NP theme font → □).
        // Was: tv.setText(t) only. Reversal: drop bindGlyphText branch.
        if (HardwareButtonGlyph.hasGlyphSpans(t)) {
            HardwareButtonGlyph.bindGlyphText(tv, t);
        } else {
            tv.setText(t);
        }
    }

    /** Start RDS polls while FM is on Now Playing — idempotent. */
    public void ensureFmRdsPolling() {
        if (!fmRdsPoller.isRunning()) {
            fmRdsPoller.start();
        }
    }

    public boolean isFmRdsPolling() {
        return fmRdsPoller.isRunning();
    }

    /** @deprecated use {@link #ensureFmRdsPolling()} — kept for callers outside updatePlayerUI. */
    public void startFmRdsPolling() {
        ensureFmRdsPolling();
    }

    /** Stop RDS polls when leaving FM NP or powering down. */
    public void stopFmRdsPolling() {
        fmRdsPoller.stop();
        cachedRdsPs = null;
        cachedRdsRt = null;
        npFmArtSet = false;
        npBoundVol = -1;
        npBoundKhz = -1;
    }

    public void updateRadioPlayerProgress() {
        MediaTransportBar transport = host.playerTransportBar();
        ProgressBar bar = transport != null ? transport.progressBar() : null;
        if (bar == null) return;
        PlaybackCoordinator playback = host.playback();
        if (playback.isFmActive()) {
            FmBandPlan plan = currentFmPlan();
            int khz = (radioScrubMode == RadioScrubMode.TUNE_FM && radioTuneFreqKhz > 0)
                    ? radioTuneFreqKhz
                    : fmFreqKhz();
            if (khz <= 0) khz = defaultFmKhz();
            // 2026-07-06 — tune mode: keep MHz header + circle knob aligned with wheel.
            if (radioScrubMode == RadioScrubMode.TUNE_FM) {
                float pos = RadioScrubMapping.khzToPosition(khz, plan);
                int prog = Math.round(pos * 100f);
                if (bar.getProgress() != prog) bar.setProgress(prog);
                host.syncFmTuneScrubUi();
            } else if (transport != null) {
                // Idle FM: only volume strip — skip if level unchanged (RDS was re-entering every 2s).
                android.media.AudioManager am =
                        (android.media.AudioManager)
                                host.context().getSystemService(Context.AUDIO_SERVICE);
                int stream =
                        fmEngine != null && fmEngine.audioStreamType() > 0
                                ? fmEngine.audioStreamType()
                                : android.media.AudioManager.STREAM_MUSIC;
                int cur = am != null ? am.getStreamVolume(stream) : 0;
                int max = am != null ? am.getStreamMaxVolume(stream) : 1;
                if (max <= 0) max = 1;
                if (cur != npBoundVol || max != npBoundVolMax) {
                    npBoundVol = cur;
                    npBoundVolMax = max;
                    transport.showFmNormalBar(cur, max);
                }
            }
            return;
        }
        if (playback.isInternetRadioActive()) {
            long buffered = internetRadioPlayer.getBufferedDurationMs();
            long live = internetRadioPlayer.getLivePositionMs();
            float pos = radioScrubMode == RadioScrubMode.REWIND_BUFFER
                    ? RadioScrubMapping.bufferMsToPosition(live, buffered)
                    : 1f;
            bar.setProgress(Math.round(pos * 100f));
        }
    }

    public void handleRadioCenterOk() {
        PlaybackCoordinator playback = host.playback();
        if (playback.isFmActive()) {
            RadioScrubMode before = radioScrubMode;
            radioScrubMode = radioScrubMode.toggleFmTuneOnCenterOk();
            if (radioScrubMode == RadioScrubMode.TUNE_FM) {
                radioTuneFreqKhz = currentFmFreqKhz();
                fmTuneRevertKhz = radioTuneFreqKhz;
            } else if (before == RadioScrubMode.TUNE_FM) {
                commitFmTuneScrub();
            }
            // 2026-07-15 — One light bind (was bind + full refreshPlayerUi thrash).
            bindRadioNowPlayingUi();
            host.syncFmTuneScrubUi();
            return;
        }
        if (playback.isInternetRadioActive()) {
            if (radioScrubMode == RadioScrubMode.REWIND_BUFFER) {
                radioScrubMode = RadioScrubMode.NONE;
            } else if (internetRadioPlayer.getBufferedDurationMs() > 0) {
                radioScrubMode = RadioScrubMode.REWIND_BUFFER;
            }
        }
    }

    /** @param next true for next, false for previous; longPress for MHz hold-step (not station scan) */
    public void handleRadioPrevNext(boolean next, boolean longPress) {
        PlaybackCoordinator playback = host.playback();
        FmBandPlan plan = currentFmPlan();

        if (playback.isFmActive()) {
            if (radioScrubMode == RadioScrubMode.TUNE_FM) {
                // 2026-07-06 — Fine MHz scrub: wheel/transport only; faster step when held.
                int mult = longPress ? 10 : 1;
                int khz = radioTuneFreqKhz > 0 ? radioTuneFreqKhz : currentFmFreqKhz();
                khz = next ? khz + plan.stepKhz() * mult : khz - plan.stepKhz() * mult;
                khz = plan.clampKhz(khz);
                radioTuneFreqKhz = khz;
                host.syncFmTuneScrubUi();
                return;
            }
            if (longPress) {
                // 2026-07-06 — Hold = fast MHz stepping without full station restart.
                int mult = 8;
                int khz = currentFmFreqKhz();
                khz = next ? khz + plan.stepKhz() * mult : khz - plan.stepKhz() * mult;
                khz = plan.clampKhz(khz);
                radioTuneFreqKhz = khz;
                tuneFmAsync(khz, false);
                host.playback().updateCurrentFmMeta(khz, FmBandPlan.khzToFraction(khz, plan));
                return;
            }
            // 2026-07-06 — Single tap = preset skip or auto-scan (not slow MHz step).
            List<FmPresetStore.Preset> presets = fmPresets.listAll();
            if (presets.size() >= 2) {
                PlayQueue.QueueItem item = playback.fmItemAtWrappedIndex(next ? 1 : -1);
                if (item != null) {
                    startFmStation(item.fmFreqKhz, item.fmLabel, true);
                    return;
                }
            }
            fmSeekScanAsync(next);
            return;
        }

        if (playback.isInternetRadioActive() && radioScrubMode == RadioScrubMode.REWIND_BUFFER) {
            long buffered = internetRadioPlayer.getBufferedDurationMs();
            if (buffered <= 0) return;
            long offset = internetRadioPlayer.getLivePositionMs();
            long delta = longPress ? 30_000L : 10_000L;
            offset = next ? offset + delta : offset - delta;
            if (offset < 0) offset = 0;
            if (offset > buffered) offset = buffered;
            internetRadioPlayer.seekBufferedMs(offset);
            updateRadioPlayerProgress();
        }
    }

    /** 2026-07-06 — NP prev/next single tap: seek next valid station off UI thread. */
    private void fmSeekScanAsync(final boolean forward) {
        if (fmSeekBusy || !host.playback().isFmActive() || !fmEngine.isPowerUp()) return;
        fmSeekBusy = true;
        host.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        bindRadioNowPlayingUi();
                    }
                });
        final int startKhz = currentFmFreqKhz();
        final FmBandPlan plan = currentFmPlan();
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        final int found = fmEngine.seekStationKhz(startKhz, forward, plan);
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        fmSeekBusy = false;
                                        if (!host.playback().isFmActive()) return;
                                        if (found > 0 && found != startKhz) {
                                            finishFmSeekFound(found, plan);
                                        } else if (found > 0) {
                                            host.refreshPlayerUi();
                                        } else {
                                            Toast.makeText(
                                                            host.context(),
                                                            R.string.radio_fm_scan_none,
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                            host.refreshPlayerUi();
                                        }
                                    }
                                });
                    }
                },
                "FmSeekScan")
                .start();
    }

    /** Tune after seek without full power cycle — refresh RDS + queue row. 2026-07-06 */
    private void finishFmSeekFound(int freqKhz, FmBandPlan plan) {
        radioTuneFreqKhz = freqKhz;
        // 2026-07-15 — Seek lands also update last-station memory.
        RadioSettings.setLastFmKhz(host.context(), freqKhz);
        cachedRdsPs = null;
        cachedRdsRt = null;
        fmRdsPoller.invalidateCache();
        String label = FmBandPlan.khzToFraction(freqKhz, plan);
        host.playback().updateCurrentFmMeta(freqKhz, label);
        tuneFmAsync(freqKhz, false);
        primeFmRdsCacheAsync();
        host.refreshPlayerUi();
    }

    /** Play/pause for FM mute or internet stream pause. */
    public void toggleRadioPlayPause() {
        if (host.playback().isInternetRadioActive()) {
            if (internetRadioPlayer.isPlaying()) internetRadioPlayer.pause();
            else internetRadioPlayer.resume();
            return;
        }
        if (host.playback().isFmActive()) {
            fmMuted = !fmMuted;
            tuneFmAsync(currentFmFreqKhz(), true);
        }
    }

    /** 2026-07-06 — Chip tune off UI thread; mute-only skips hardware tune. */
    private void tuneFmAsync(final int freqKhz, final boolean muteOnly) {
        final int khz = freqKhz;
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (muteOnly) {
                            fmEngine.mute(fmMuted);
                        } else {
                            fmEngine.tune(khz);
                        }
                        host.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!host.playback().isFmActive()) return;
                                        host.refreshPlayerUi();
                                        host.syncFmTuneScrubUi();
                                        updateRadioPlayerProgress();
                                    }
                                });
                    }
                },
                muteOnly ? "FmMute" : "FmTune")
                .start();
    }

    /** Second OK in tune mode — persist MHz + RDS title on the queue row. */
    private void commitFmTuneScrub() {
        int khz = radioTuneFreqKhz > 0 ? radioTuneFreqKhz : currentFmFreqKhz();
        String label = cachedRdsPs;
        if (label == null || label.isEmpty()) {
            label = FmBandPlan.khzToFraction(khz, currentFmPlan());
        }
        // 2026-07-15 — Commit also updates last-station restore.
        RadioSettings.setLastFmKhz(host.context(), khz);
        host.playback().updateCurrentFmMeta(khz, label);
        fmTuneRevertKhz = khz;
        tuneFmAsync(khz, false);
    }

    /** Back during tune scrub — restore MHz before tune mode began. */
    public void revertFmTuneScrub() {
        if (radioScrubMode != RadioScrubMode.TUNE_FM) return;
        radioScrubMode = RadioScrubMode.NONE;
        int revert = fmTuneRevertKhz > 0 ? fmTuneRevertKhz : currentFmFreqKhz();
        radioTuneFreqKhz = revert;
        tuneFmAsync(revert, false);
        bindRadioNowPlayingUi();
    }

    public boolean isFmRecording() {
        return fmRecorder.isRecording();
    }

    /** Now Playing track-count line while REC is active. */
    public String fmRecordingStatusLabel() {
        return formatFmRecordingStatus();
    }

    /** Context menu / NP — start or stop FM capture. */
    public void toggleFmRecording() {
        if (!host.playback().isFmActive() || !fmEngine.isPowerUp()) {
            Toast.makeText(host.context(), R.string.radio_fm_record_power_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (fmRecorder.isRecording()) {
            fmRecorder.stopRecording();
            Toast.makeText(host.context(), R.string.radio_fm_record_saved, Toast.LENGTH_SHORT).show();
        } else {
            fmRecorder.startRecording();
            Toast.makeText(host.context(), R.string.radio_fm_record_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopFmRecordingQuiet() {
        fmUiHandler.removeCallbacks(fmRecordUiTick);
        if (fmRecorder.isRecording()) {
            fmRecorder.stopRecording();
        } else {
            fmRecorder.release();
        }
    }

    private String formatFmRecordingStatus() {
        long ms = fmRecorder.recordDurationMs();
        int totalSec = (int) (ms / 1000L);
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return host.getString(R.string.radio_fm_recording_status, min, sec);
    }

    private boolean fmMuted;
    private String cachedRdsPs;
    private String cachedRdsRt;
    /** 2026-07-06 — Blocks overlapping NP prev/next auto-scan threads. */
    private volatile boolean fmSeekBusy;

    /** OK-hold move in FM presets / saved-channels virtual lists. 2026-07-06 */
    private int fmPresetMoveFrom = -1;
    private java.util.ArrayList<FmPresetStore.Preset> fmPresetMoveSnapshot;

    /** True on FM preset browse screens that support reorder. */
    public boolean isFmPresetListActive() {
        return radioSubMode == RADIO_FM_PRESETS || radioSubMode == RADIO_FM_SAVED_CHANNELS;
    }

    public boolean isFmPresetMoveActive() {
        return fmPresetMoveFrom >= 0 && isFmPresetListActive();
    }

    public int fmPresetMoveFrom() {
        return fmPresetMoveFrom;
    }

    /** Preset list index (0..n-1) from virtual list position (row 0 = Back). */
    public int fmPresetDataIndexFromVirtualPosition(int position) {
        return position - 1;
    }

    public void handleFmPresetListCenterActivate(int virtualPosition, boolean longPress) {
        if (!isFmPresetListActive()) return;
        if (virtualPosition <= 0) return;
        int idx = fmPresetDataIndexFromVirtualPosition(virtualPosition);
        List<FmPresetStore.Preset> presets = fmPresets.listAll();
        if (idx < 0 || idx >= presets.size()) return;
        if (fmPresetMoveFrom >= 0) {
            if (fmPresetMoveFrom == idx) {
                confirmFmPresetMove();
            } else {
                applyFmPresetMove(fmPresetMoveFrom, idx);
            }
            return;
        }
        if (longPress) {
            beginFmPresetMove(idx);
            return;
        }
        FmPresetStore.Preset p = presets.get(idx);
        startFmStation(p.freqKhz, p.label, true);
    }

    public boolean handleFmPresetMoveWheel(int delta) {
        if (!isFmPresetMoveActive() || delta == 0) return false;
        List<FmPresetStore.Preset> presets = fmPresets.listAll();
        int count = presets.size();
        if (count <= 1) return false;
        int newIdx = fmPresetMoveFrom + delta;
        if (newIdx < 0) newIdx = 0;
        if (newIdx >= count) newIdx = count - 1;
        if (newIdx == fmPresetMoveFrom) return true;
        applyFmPresetMove(fmPresetMoveFrom, newIdx);
        return true;
    }

    public void cancelFmPresetMove() {
        if (fmPresetMoveSnapshot != null) {
            fmPresets.replaceAll(fmPresetMoveSnapshot);
        }
        fmPresetMoveFrom = -1;
        fmPresetMoveSnapshot = null;
        rebuildFmPresetListUi();
    }

    private void beginFmPresetMove(int pickIndex) {
        fmPresetMoveFrom = pickIndex;
        fmPresetMoveSnapshot = new java.util.ArrayList<FmPresetStore.Preset>(fmPresets.listAll());
        rebuildFmPresetListUi();
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                FocusScrollHelper.focusListPosition(host.listVirtualSongs(), pickIndex + 1);
            }
        });
    }

    private void applyFmPresetMove(int from, int to) {
        if (from == to) return;
        fmPresets.reorder(from, to);
        fmPresetMoveFrom = to;
        if (host.playback().isFmActive()) {
            FmQueueSync.syncQueueFromPresets(host.playback(), fmPresets, currentFmFreqKhz());
        }
        rebuildFmPresetListUi();
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                FocusScrollHelper.focusListPosition(host.listVirtualSongs(), to + 1);
            }
        });
    }

    private void confirmFmPresetMove() {
        fmPresetMoveFrom = -1;
        fmPresetMoveSnapshot = null;
        if (host.playback().isFmActive()) {
            FmQueueSync.syncPresetsFromQueue(host.playback(), fmPresets);
            FmQueueSync.syncQueueFromPresets(host.playback(), fmPresets, currentFmFreqKhz());
        }
        rebuildFmPresetListUi();
    }

    private void rebuildFmPresetListUi() {
        if (radioSubMode == RADIO_FM_PRESETS) buildFmPresetsUi();
        else if (radioSubMode == RADIO_FM_SAVED_CHANNELS) buildFmSavedChannelsUi();
    }

    /** Tune to frequency — queue sync after power-on. 2026-07-06 */
    public void playFmStation(int freqKhz, String label) {
        // Exact MHz from queue/external — do not car-seek away.
        startFmStation(freqKhz, label, true);
    }

    public String[] getRadioContextMenuLabels() {
        PlaybackCoordinator playback = host.playback();
        if (playback.isFmActive()) {
            boolean saved = fmPresets.containsFreq(currentFmFreqKhz());
            String recordLabel =
                    fmRecorder.isRecording()
                            ? host.getString(R.string.radio_fm_record_stop)
                            : host.getString(R.string.radio_fm_record_start);
            // 2026-07-15 — Context: cycle audio destination (Wired / BT / Speaker).
            String audioLabel =
                    host.getString(R.string.radio_ctx_audio_output, fmAudioOutputLabel());
            return new String[] {
                recordLabel,
                host.getString(R.string.radio_ctx_save_preset),
                audioLabel,
                saved ? host.getString(R.string.radio_ctx_remove_preset)
                        : host.getString(R.string.radio_ctx_scan),
                host.getString(saved ? R.string.radio_ctx_scan : R.string.radio_ctx_open_fm_browse),
                // 2026-07-15 — Context Exit path (same confirm as shell Back).
                host.getString(R.string.radio_fm_exit_row)
            };
        }
        if (playback.isInternetRadioActive()) {
            PlayQueue.QueueItem cur = playback.unifiedQueue().current();
            boolean fav = cur != null && netFavorites.isFavorite(cur.radioStationUuid);
            return new String[] {
                fav ? host.getString(R.string.radio_ctx_remove_favorite)
                        : host.getString(R.string.radio_ctx_add_favorite),
                host.getString(R.string.radio_ctx_open_net_browse)
            };
        }
        return new String[0];
    }

    public boolean handleRadioContextAction(int index) {
        PlaybackCoordinator playback = host.playback();
        if (playback.isFmActive()) {
            boolean saved = fmPresets.containsFreq(currentFmFreqKhz());
            switch (index) {
                case 0:
                    toggleFmRecording();
                    return true;
                case 1:
                    int khz = currentFmFreqKhz();
                    fmPresets.upsert(khz, FmBandPlan.khzToFraction(khz, currentFmPlan()));
                    FmQueueSync.syncQueueFromPresets(playback, fmPresets, khz);
                    Toast.makeText(host.context(), R.string.radio_ctx_preset_saved, Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    // 2026-07-15 — Cycle Wired → Bluetooth → Speaker and re-route live audio.
                    fmEngine.cycleAudioOutput();
                    host.refreshPlayerUi();
                    Toast.makeText(
                                    host.context(),
                                    host.getString(R.string.radio_ctx_audio_output, fmAudioOutputLabel()),
                                    Toast.LENGTH_SHORT)
                            .show();
                    return true;
                case 3:
                    if (saved) {
                        fmPresets.delete(currentFmFreqKhz());
                        FmQueueSync.syncQueueFromPresets(playback, fmPresets, currentFmFreqKhz());
                        Toast.makeText(host.context(), R.string.radio_fm_channel_removed, Toast.LENGTH_SHORT)
                                .show();
                    } else {
                        host.changeScreen(STATE_RADIO_FM_BROWSE);
                        startFmScan();
                    }
                    return true;
                case 4:
                    if (saved) {
                        host.changeScreen(STATE_RADIO_FM_BROWSE);
                        startFmScan();
                    } else {
                        host.changeScreen(STATE_RADIO_FM_BROWSE);
                    }
                    return true;
                case 5:
                    promptExitFmToHome();
                    return true;
                default:
                    return false;
            }
        }
        if (playback.isInternetRadioActive()) {
            PlayQueue.QueueItem cur = playback.unifiedQueue().current();
            if (cur == null) return false;
            switch (index) {
                case 0:
                    // 2026-07-20 — Same toggle as browse long-OK.
                    RadioBrowserClient.Station st = new RadioBrowserClient.Station(
                            cur.radioStationUuid, cur.radioName, cur.radioUrl,
                            cur.radioSubtitle, "", "");
                    boolean nowFav = toggleNetFavorite(st);
                    Toast.makeText(host.context(),
                            nowFav ? R.string.radio_ctx_favorite_added
                                    : R.string.radio_ctx_favorite_removed,
                            Toast.LENGTH_SHORT).show();
                    return true;
                case 1:
                    if (!host.requireInternet(R.string.toast_internet_required)) return true;
                    radioSubMode = RADIO_NET_COUNTRY;
                    host.changeScreen(STATE_RADIO_NET_BROWSE);
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    // --- Video hub + YouTube ---

    /**
     * 2026-07-15 — Open Solar YouTube browse (native Invidious/Piped backends).
     * Layman: go straight into Solar’s YouTube list; no NotPipe app needed.
     * Was: wake NotPipe + probe bridge. Now: native YouTubeClient soft probe.
     * Reversal: restore NotPipePmRegistrar + openYouTubeAfterNotPipeReady.
     */
    private void openYouTubeBrowse() {
        youtubeAudioMode = false;
        if (youtubeShowingDiscover) {
            youtubeVideos.clear();
            youtubeDiscoverReasons.clear();
        }
        youtubeShowingDiscover = false;
        openYouTubeBrowseInternal();
    }

    /**
     * 2026-07-15 — Music hub / home tile entry: same browse UI, audio plays in music Now Playing.
     * Layman: YouTube as songs, not videos. Technical: youtubeAudioMode + resolveAudioStream.
     * Reversal: call openYouTubeBrowse() (video mode).
     */
    public void openYouTubeAudioBrowse() {
        youtubeAudioMode = true;
        if (youtubeShowingDiscover) {
            youtubeVideos.clear();
            youtubeDiscoverReasons.clear();
        }
        youtubeShowingDiscover = false;
        openYouTubeBrowseInternal();
    }

    /** Get Music entry for Solar's transparent local recommendation mode. */
    public void openYouTubeDiscoverBrowse() {
        youtubeAudioMode = true;
        youtubeShowingDiscover = true;
        youtubeShowingBookmarks = false;
        youtubePendingSearch = null;
        youtubeNextPageToken = "";
        youtubeVideos.clear();
        youtubeDiscoverReasons.clear();
        openYouTubeBrowseInternal();
    }

    private void openYouTubeBrowseInternal() {
        host.changeScreen(STATE_YOUTUBE_BROWSE);
        bindYouTubeAuthListener();
        if (!ConnectivityHelper.isOnline(host.context())) return;
        final int probeGen = ++youtubeProbeGen;
        YouTubeClient.getInstance(host.context()).probe(new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                // Backend pool ready — popular load already triggered by browse enter.
            }

            @Override
            public void onError(String message) {
                if (probeGen != youtubeProbeGen) return;
                Toast.makeText(host.context(),
                        "youtube_setup_required".equals(message)
                                ? R.string.youtube_setup_required
                                : R.string.youtube_backend_not_ready,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void buildVideoHubUi() {
        prepareScrollBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(false);
        host.setBrowserStatusTitle(host.getString(R.string.status_videos));
        addBackRow(host.getString(R.string.radio_back_home));

        addActionRow(host.getString(R.string.video_my_videos_row), new Runnable() {
            @Override
            public void run() {
                videoBrowseFolder = VideoLibrary.ROOT;
                host.changeScreen(STATE_VIDEOS);
            }
        });
        // 2026-07-14 — Hub row when kill switch on (default); Debug can hide for A/B.
        if (com.solar.launcher.youtube.YouTubeExperiment.isEnabled(host.prefs())) {
            addActionRow(host.getString(R.string.video_youtube_row), new Runnable() {
                @Override
                public void run() {
                    if (!host.requireInternet(R.string.toast_internet_required)) return;
                    openYouTubeBrowse();
                }
            });
        }
        focusFirstBrowserChild();
    }

    private void buildYouTubeBrowseUi() {
        bindYouTubeAuthListener();
        prepareVirtualListBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        updateYouTubeStatusPath();
        rebuildYouTubeVirtualRows();
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                handleYouTubeRowClick(position);
            }
        });
        if (!youtubeShowingBookmarks && youtubeVideos.isEmpty() && !youtubeLoading
                && (youtubePendingSearch == null || youtubePendingSearch.isEmpty())) {
            if (youtubeShowingDiscover) loadYouTubeDiscover();
            else loadYouTubePopular();
        }
    }

    private void updateYouTubeStatusPath() {
        if (youtubeShowingBookmarks) {
            host.setBrowserStatusTitle(host.getString(R.string.youtube_bookmarks_title));
        } else if (youtubeShowingDiscover) {
            host.setBrowserStatusTitle(host.getString(R.string.youtube_discover_title));
        } else if (youtubePendingSearch != null && !youtubePendingSearch.isEmpty()) {
            host.setBrowserStatusTitle(host.getString(R.string.status_youtube_results,
                    youtubePendingSearch));
        } else {
            // Music audio mode and Videos mode share the "YouTube" status label.
            host.setBrowserStatusTitle(host.getString(R.string.status_youtube));
        }
    }

    /** Layman: builds Back, Search, suggestions, and video rows for the wheel list. */
    private void rebuildYouTubeVirtualRows() {
        virtualLabels.clear();
        virtualSubtitles.clear();
        youtubeBrowseRows.clear();

        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualSubtitles.add("");
        youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_BACK));

        virtualLabels.add(host.getString(R.string.youtube_search_row));
        if (youtubePendingSearch != null && !youtubePendingSearch.isEmpty()) {
            virtualSubtitles.add(host.getString(R.string.youtube_search_query_subtitle,
                    youtubePendingSearch));
        } else {
            virtualSubtitles.add(host.getString(R.string.youtube_search_hint));
        }
        youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_SEARCH));

        appendYouTubeAccountRow();

        if (!youtubeShowingBookmarks) {
            int saved = youtubeBookmarks().list().size();
            virtualLabels.add(host.getString(R.string.youtube_bookmarks_row));
            virtualSubtitles.add(host.getString(R.string.youtube_bookmarks_count, saved));
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_BOOKMARKS));
        }

        if (!youtubeShowingDiscover && !youtubeShowingBookmarks
                && (youtubePendingSearch == null || youtubePendingSearch.isEmpty())) {
            virtualLabels.add(host.getString(R.string.youtube_discover_row));
            virtualSubtitles.add(host.getString(R.string.youtube_discover_row_sub));
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_DISCOVER));
        }

        if (youtubeShowingDiscover || youtubeShowingBookmarks
                || (youtubePendingSearch != null && !youtubePendingSearch.isEmpty())) {
            virtualLabels.add(host.getString(R.string.youtube_show_popular));
            virtualSubtitles.add(youtubeShowingDiscover
                    ? host.getString(R.string.youtube_leave_discover_sub)
                    : youtubeShowingBookmarks
                    ? host.getString(R.string.youtube_leave_bookmarks_sub)
                    : host.getString(R.string.youtube_show_popular_sub));
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_CLEAR));
        } else {
            List<String> recent = YouTubeRecentSearches.get(host.context());
            for (String q : recent) {
                virtualLabels.add(q);
                virtualSubtitles.add(host.getString(R.string.youtube_recent_subtitle));
                youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_RECENT, q, -1));
            }
        }

        if (youtubeLoading && youtubeVideos.isEmpty()) {
            virtualLabels.add(host.getString(R.string.youtube_loading));
            virtualSubtitles.add("");
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_STATUS));
            return;
        }

        if (youtubeVideos.isEmpty()) {
            virtualLabels.add(youtubeShowingBookmarks
                    ? host.getString(R.string.youtube_bookmarks_empty)
                    : youtubeShowingDiscover
                    ? host.getString(R.string.youtube_discover_empty)
                    : (youtubePendingSearch != null && !youtubePendingSearch.isEmpty()
                    ? host.getString(R.string.youtube_empty)
                    : host.getString(R.string.youtube_popular_empty)));
            virtualSubtitles.add(youtubeMetadataStale
                    ? host.getString(R.string.youtube_offline_cache)
                    : "");
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_STATUS));
            return;
        }

        if (youtubeShowingBookmarks) {
            virtualLabels.add(host.getString(R.string.youtube_bookmarks_header));
        } else if (youtubeShowingDiscover) {
            virtualLabels.add(host.getString(R.string.youtube_discover_header));
        } else if (youtubePendingSearch == null || youtubePendingSearch.isEmpty()) {
            virtualLabels.add(host.getString(R.string.youtube_popular_header));
        } else {
            virtualLabels.add(host.getString(R.string.youtube_results_header));
        }
        if (youtubeShowingDiscover
                && (youtubeDiscoverSignalsLoading
                        || youtubeDiscoverLocalSignalsLoading)) {
            virtualSubtitles.add(host.getString(R.string.youtube_discover_personalizing));
        } else if (youtubeShowingDiscover
                && (youtubeDiscoverSignals.partial
                        || youtubeLocalLibrarySignals.partial)) {
            virtualSubtitles.add(host.getString(R.string.youtube_discover_partial));
        } else {
            virtualSubtitles.add(youtubeMetadataStale
                    ? host.getString(R.string.youtube_offline_cache)
                    : "");
        }
        youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_STATUS));

        for (int i = 0; i < youtubeVideos.size(); i++) {
            YouTubeVideo v = youtubeVideos.get(i);
            virtualLabels.add(v.title);
            if (youtubeShowingDiscover && i < youtubeDiscoverReasons.size()) {
                String metadata = v.subtitle();
                String reason = youtubeDiscoverReasons.get(i);
                virtualSubtitles.add(metadata.length() > 0
                        ? reason + " · " + metadata : reason);
            } else {
                virtualSubtitles.add(v.subtitle());
            }
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_VIDEO, null, i));
        }

        if (youtubeAppending) {
            virtualLabels.add(host.getString(R.string.youtube_loading_more));
            virtualSubtitles.add("");
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_STATUS));
        } else if (!youtubeShowingDiscover && !youtubeShowingBookmarks
                && youtubeNextPageToken.length() > 0) {
            virtualLabels.add(host.getString(R.string.youtube_show_more));
            virtualSubtitles.add(host.getString(R.string.youtube_show_more_sub));
            youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_MORE));
        }
    }

    private void handleYouTubeRowClick(int position) {
        if (position < 0 || position >= youtubeBrowseRows.size()) return;
        YoutubeBrowseRow row = youtubeBrowseRows.get(position);
        switch (row.kind) {
            case YoutubeBrowseRow.KIND_BACK:
                handleBack();
                break;
            case YoutubeBrowseRow.KIND_SEARCH:
                host.openYouTubeSearchKeyboard(
                        youtubePendingSearch != null ? youtubePendingSearch : "");
                break;
            case YoutubeBrowseRow.KIND_CLEAR:
                youtubeShowingBookmarks = false;
                youtubeShowingDiscover = false;
                youtubePendingSearch = null;
                youtubeNextPageToken = "";
                youtubeVideos.clear();
                loadYouTubePopular();
                break;
            case YoutubeBrowseRow.KIND_RECENT:
                if (row.recentQuery != null && row.recentQuery.length() > 0) {
                    youtubeShowingBookmarks = false;
                    youtubeShowingDiscover = false;
                    youtubePendingSearch = row.recentQuery;
                    loadYouTubeSearch(row.recentQuery);
                }
                break;
            case YoutubeBrowseRow.KIND_ACCOUNT:
                handleYouTubeAccountClick();
                break;
            case YoutubeBrowseRow.KIND_BOOKMARKS:
                showYouTubeBookmarks();
                break;
            case YoutubeBrowseRow.KIND_DISCOVER:
                loadYouTubeDiscover();
                break;
            case YoutubeBrowseRow.KIND_MORE:
                loadMoreYouTube();
                break;
            case YoutubeBrowseRow.KIND_STATUS:
                break;
            case YoutubeBrowseRow.KIND_VIDEO:
                if (row.videoIndex >= 0 && row.videoIndex < youtubeVideos.size()) {
                    // Detail + comments first (messaging-style); Play is an action there.
                    openYouTubeDetail(youtubeVideos.get(row.videoIndex));
                }
                break;
            default:
                break;
        }
    }

    private YouTubeBookmarks youtubeBookmarks() {
        if (youtubeBookmarks == null) {
            youtubeBookmarks = new YouTubeBookmarks(host.context());
        }
        return youtubeBookmarks;
    }

    private YouTubeDiscoverFeedback youtubeDiscoverFeedback() {
        if (youtubeDiscoverFeedback == null) {
            youtubeDiscoverFeedback = new YouTubeDiscoverFeedback(host.context());
        }
        return youtubeDiscoverFeedback;
    }

    private YouTubeDeviceAuth youtubeAuth() {
        if (youtubeAuth == null) {
            youtubeAuth = YouTubeDeviceAuth.getInstance(host.context());
        }
        return youtubeAuth;
    }

    private void bindYouTubeAuthListener() {
        youtubeAuth().setListener(new YouTubeDeviceAuth.Listener() {
            @Override
            public void onAuthStateChanged(YouTubeDeviceAuth.Snapshot snapshot) {
                if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
                youtubeAuthHandler.removeCallbacks(youtubeAuthTick);
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
                if (snapshot != null
                        && snapshot.state == YouTubeDeviceAuth.State.AUTHORIZED
                        && youtubeShowingDiscover) {
                    loadYouTubeDiscover();
                    return;
                }
                if (snapshot != null && snapshot.isActive()) {
                    youtubeAuthHandler.postDelayed(youtubeAuthTick, 1000L);
                }
            }
        });
    }

    private void appendYouTubeAccountRow() {
        YouTubeDeviceAuth auth = youtubeAuth();
        YouTubeDeviceAuth.Snapshot snapshot = auth.snapshot();
        String label;
        String subtitle;
        if (auth.hasAccount() || snapshot.state == YouTubeDeviceAuth.State.AUTHORIZED) {
            label = host.getString(R.string.youtube_account_connected);
            subtitle = host.getString(R.string.youtube_account_sign_out_hint);
        } else if (snapshot.state == YouTubeDeviceAuth.State.REQUESTING_CODE) {
            label = host.getString(R.string.youtube_account_sign_in);
            subtitle = host.getString(R.string.youtube_account_requesting_code);
        } else if ((snapshot.state == YouTubeDeviceAuth.State.WAITING_FOR_USER
                || snapshot.state == YouTubeDeviceAuth.State.SLOW_DOWN)
                && snapshot.userCode.length() > 0) {
            label = host.getString(R.string.youtube_account_code, snapshot.userCode);
            subtitle = host.getString(R.string.youtube_account_verify,
                    snapshot.verificationUrl,
                    snapshot.remainingSeconds(System.currentTimeMillis()));
        } else if (!auth.isConfigured()
                || snapshot.state == YouTubeDeviceAuth.State.SETUP_REQUIRED) {
            label = host.getString(R.string.youtube_account_setup);
            subtitle = host.getString(R.string.youtube_account_setup_hint);
        } else {
            label = host.getString(R.string.youtube_account_sign_in);
            subtitle = snapshot.safeReason.length() > 0
                    ? host.getString(R.string.youtube_account_retry_reason, snapshot.safeReason)
                    : host.getString(R.string.youtube_account_sign_in_hint);
        }
        if (!snapshot.isActive()) {
            int quota = YouTubeClient.getInstance(host.context()).estimatedQuotaToday();
            subtitle = subtitle + " · "
                    + host.getString(R.string.youtube_quota_today, quota);
        }
        virtualLabels.add(label);
        virtualSubtitles.add(subtitle);
        youtubeBrowseRows.add(new YoutubeBrowseRow(YoutubeBrowseRow.KIND_ACCOUNT));
    }

    private void handleYouTubeAccountClick() {
        final YouTubeDeviceAuth auth = youtubeAuth();
        if (auth.hasAccount()) {
            host.showThemedConfirm(
                    host.getString(R.string.youtube_account_connected),
                    host.getString(R.string.youtube_account_sign_out_confirm),
                    host.getString(R.string.youtube_account_sign_out),
                    host.getString(R.string.common_cancel),
                    new Runnable() {
                        @Override
                        public void run() {
                            auth.signOut(new Runnable() {
                                @Override
                                public void run() {
                                    YouTubeClient.getInstance(host.context())
                                            .clearMetadataCache();
                                    youtubeDiscoverSignals =
                                            new YouTubeDiscoverSignals(
                                                    null, null, false, false);
                                    applyYouTubeDiscoverRanking();
                                    rebuildYouTubeVirtualRows();
                                    notifyVirtualDataChangedPreserveFocus();
                                    Toast.makeText(host.context(),
                                            R.string.youtube_account_signed_out,
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    },
                    null);
            return;
        }
        if (auth.snapshot().isActive()) {
            auth.cancel();
            youtubeAuthHandler.removeCallbacks(youtubeAuthTick);
            rebuildYouTubeVirtualRows();
            notifyVirtualDataChangedPreserveFocus();
            return;
        }
        if (!host.requireInternet(R.string.toast_internet_required)) return;
        auth.start();
        youtubeAuthHandler.removeCallbacks(youtubeAuthTick);
        youtubeAuthHandler.post(youtubeAuthTick);
    }

    private void showYouTubeBookmarks() {
        youtubeLoadGen++;
        youtubeLoading = false;
        youtubeAppending = false;
        youtubeShowingBookmarks = true;
        youtubeShowingDiscover = false;
        youtubeMetadataStale = false;
        youtubePendingSearch = null;
        youtubeNextPageToken = "";
        youtubeVideos.clear();
        youtubeVideos.addAll(youtubeBookmarks().list());
        updateYouTubeStatusPath();
        rebuildYouTubeVirtualRows();
        notifyVirtualDataChangedPreserveFocus();
    }

    private void loadMoreYouTube() {
        if (youtubeShowingBookmarks || youtubeAppending || youtubeLoading
                || youtubeNextPageToken.length() == 0) {
            return;
        }
        final String token = youtubeNextPageToken;
        final String query = youtubePendingSearch;
        final int gen = ++youtubeLoadGen;
        youtubeAppending = true;
        rebuildYouTubeVirtualRows();
        notifyVirtualDataChangedPreserveFocus();
        YouTubeClient.Callback callback = new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeAppending = false;
                int firstAdded = youtubeVideos.size();
                youtubeMetadataStale = youtubeMetadataStale
                        || YouTubeResultJson.parseCacheState(payloadJson).stale;
                try {
                    List<YouTubeVideo> next = YouTubeResultJson.parseVideos(payloadJson);
                    for (YouTubeVideo video : next) {
                        if (video != null && !containsYouTubeId(video.id)) {
                            youtubeVideos.add(video);
                        }
                    }
                    String nextToken = YouTubeResultJson.parseNextPageToken(payloadJson);
                    youtubeNextPageToken = token.equals(nextToken) ? "" : nextToken;
                } catch (Exception error) {
                    youtubeNextPageToken = "";
                }
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
                if (youtubeVideos.size() > firstAdded) {
                    focusYouTubeVideoIndex(firstAdded);
                }
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeAppending = false;
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
                Toast.makeText(host.context(), R.string.youtube_load_more_error,
                        Toast.LENGTH_SHORT).show();
            }
        };
        YouTubeClient client = YouTubeClient.getInstance(host.context());
        if (query != null && query.length() > 0) {
            client.search(query, token, callback);
        } else {
            client.fetchPopular(token, callback);
        }
    }

    private boolean containsYouTubeId(String videoId) {
        if (videoId == null || videoId.length() == 0) return false;
        for (YouTubeVideo current : youtubeVideos) {
            if (videoId.equals(current.id)) return true;
        }
        return false;
    }

    private void focusYouTubeVideoIndex(final int videoIndex) {
        final ListView list = host.listVirtualSongs();
        if (list == null) return;
        int position = -1;
        for (int i = 0; i < youtubeBrowseRows.size(); i++) {
            YoutubeBrowseRow row = youtubeBrowseRows.get(i);
            if (row.kind == YoutubeBrowseRow.KIND_VIDEO
                    && row.videoIndex == videoIndex) {
                position = i;
                break;
            }
        }
        if (position < 0) return;
        final int target = position;
        list.post(new Runnable() {
            @Override
            public void run() {
                list.setSelection(target);
                list.requestFocus();
            }
        });
    }

    public boolean toggleYouTubeBookmark(YouTubeVideo video) {
        boolean saved = youtubeBookmarks().toggle(video);
        Toast.makeText(host.context(),
                saved ? R.string.youtube_bookmark_added : R.string.youtube_bookmark_removed,
                Toast.LENGTH_SHORT).show();
        return saved;
    }

    public void searchYouTubeOnSoulseek(YouTubeVideo video) {
        host.searchSoulseekForYouTube(video);
    }

    public void copyYouTubeLink(YouTubeVideo video) {
        String url = YouTubeAcquisitionPolicy.canonicalUrl(video);
        if (url.length() == 0) return;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) host.context()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "YouTube URL", url));
        }
        Toast.makeText(host.context(),
                host.getString(R.string.youtube_link_copied, url),
                Toast.LENGTH_LONG).show();
    }

    /** Open detail/comments for a video — Solar list only, notPipe invisible. */
    private void openYouTubeDetail(YouTubeVideo video) {
        if (video == null || video.id.isEmpty()) return;
        youtubeDetailVideo = video;
        youtubeComments.clear();
        youtubeCommentsLoading = true;
        youtubeCommentsStale = false;
        host.changeScreen(STATE_YOUTUBE_DETAIL);
        loadYouTubeComments(video.id);
    }

    private void buildYouTubeDetailUi() {
        prepareVirtualListBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        if (youtubeDetailVideo != null) {
            host.setBrowserStatusTitle(youtubeDetailVideo.title);
        } else {
            host.setBrowserStatusTitle(host.getString(R.string.status_youtube_detail));
        }
        rebuildYouTubeDetailRows();
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                handleYouTubeDetailRowClick(position);
            }
        });
    }

    /**
     * Messaging-style layout: actions on top, then a Comments header, then author/body rows.
     * Same wheel list pattern as Soulseek conversations (title + subtitle).
     */
    private void rebuildYouTubeDetailRows() {
        virtualLabels.clear();
        virtualSubtitles.clear();
        youtubeDetailRows.clear();

        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualSubtitles.add("");
        youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_BACK));

        if (youtubeDetailVideo != null) {
            boolean bookmarked = youtubeBookmarks().contains(youtubeDetailVideo.id);
            virtualLabels.add(host.getString(bookmarked
                    ? R.string.youtube_detail_remove_bookmark
                    : R.string.youtube_detail_bookmark));
            virtualSubtitles.add(host.getString(R.string.youtube_detail_bookmark_sub));
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_BOOKMARK));

            virtualLabels.add(host.getString(R.string.youtube_detail_search_soulseek));
            virtualSubtitles.add(host.getString(R.string.youtube_detail_search_soulseek_sub));
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_SOULSEEK));

            virtualLabels.add(host.getString(R.string.youtube_detail_copy_link));
            virtualSubtitles.add(YouTubeAcquisitionPolicy.canonicalUrl(youtubeDetailVideo));
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_COPY_LINK));

            List<CreatorDownloadLinkExtractor.Link> creatorLinks =
                    CreatorDownloadLinkExtractor.extract(
                            youtubeDetailVideo.description);
            for (CreatorDownloadLinkExtractor.Link link : creatorLinks) {
                virtualLabels.add(host.getString(
                        R.string.youtube_detail_creator_download,
                        link.displayName));
                virtualSubtitles.add(host.getString(
                        R.string.youtube_detail_creator_download_sub,
                        link.host));
                youtubeDetailRows.add(new YoutubeDetailRow(
                        YoutubeDetailRow.KIND_CREATOR_DOWNLOAD, link.url));
            }

            if (youtubeShowingDiscover) {
                virtualLabels.add(host.getString(R.string.youtube_discover_more_like));
                virtualSubtitles.add(host.getString(
                        R.string.youtube_discover_more_like_sub));
                youtubeDetailRows.add(new YoutubeDetailRow(
                        YoutubeDetailRow.KIND_MORE_LIKE));

                virtualLabels.add(host.getString(
                        R.string.youtube_discover_less_channel));
                virtualSubtitles.add(host.getString(
                        R.string.youtube_discover_less_channel_sub,
                        youtubeDetailVideo.author));
                youtubeDetailRows.add(new YoutubeDetailRow(
                        YoutubeDetailRow.KIND_LESS_FROM_CHANNEL));

                virtualLabels.add(host.getString(
                        R.string.youtube_discover_not_interested));
                virtualSubtitles.add(host.getString(
                        R.string.youtube_discover_not_interested_sub));
                youtubeDetailRows.add(new YoutubeDetailRow(
                        YoutubeDetailRow.KIND_NOT_INTERESTED));
            }
        }

        virtualLabels.add(host.getString(R.string.youtube_comments_header));
        virtualSubtitles.add(youtubeCommentsStale
                ? host.getString(R.string.youtube_offline_cache)
                : "");
        youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_HEADER));

        if (youtubeCommentsLoading) {
            virtualLabels.add(host.getString(R.string.youtube_comments_loading));
            virtualSubtitles.add("");
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_STATUS));
            return;
        }

        if (youtubeComments.isEmpty()) {
            virtualLabels.add(host.getString(R.string.youtube_comments_empty));
            virtualSubtitles.add("");
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_STATUS));
            return;
        }

        for (int i = 0; i < youtubeComments.size(); i++) {
            YouTubeComment c = youtubeComments.get(i);
            String author = c.author.length() > 0 ? c.author : "…";
            virtualLabels.add(author);
            virtualSubtitles.add(c.preview(120));
            youtubeDetailRows.add(new YoutubeDetailRow(YoutubeDetailRow.KIND_COMMENT, i));
        }
    }

    private void handleYouTubeDetailRowClick(int position) {
        if (position < 0 || position >= youtubeDetailRows.size()) return;
        YoutubeDetailRow row = youtubeDetailRows.get(position);
        switch (row.kind) {
            case YoutubeDetailRow.KIND_BACK:
                handleBack();
                break;
            case YoutubeDetailRow.KIND_BOOKMARK:
                if (youtubeDetailVideo != null) {
                    toggleYouTubeBookmark(youtubeDetailVideo);
                    rebuildYouTubeDetailRows();
                    notifyVirtualDataChangedPreserveFocus();
                }
                break;
            case YoutubeDetailRow.KIND_SOULSEEK:
                if (youtubeDetailVideo != null) {
                    host.searchSoulseekForYouTube(youtubeDetailVideo);
                }
                break;
            case YoutubeDetailRow.KIND_COPY_LINK:
                if (youtubeDetailVideo != null) {
                    copyYouTubeLink(youtubeDetailVideo);
                }
                break;
            case YoutubeDetailRow.KIND_CREATOR_DOWNLOAD:
                if (row.directUrl != null && row.directUrl.length() > 0) {
                    host.openAuthorizedDirectAudioUrl(row.directUrl);
                }
                break;
            case YoutubeDetailRow.KIND_MORE_LIKE:
                if (youtubeDetailVideo != null) {
                    youtubeDiscoverFeedback().moreLike(youtubeDetailVideo);
                    applyYouTubeDiscoverRanking();
                    Toast.makeText(host.context(),
                            R.string.youtube_discover_feedback_saved,
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case YoutubeDetailRow.KIND_LESS_FROM_CHANNEL:
                if (youtubeDetailVideo != null) {
                    youtubeDiscoverFeedback().lessFromChannel(youtubeDetailVideo);
                    applyYouTubeDiscoverRanking();
                    Toast.makeText(host.context(),
                            R.string.youtube_discover_feedback_saved,
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case YoutubeDetailRow.KIND_NOT_INTERESTED:
                if (youtubeDetailVideo != null) {
                    youtubeDiscoverFeedback().notInterested(youtubeDetailVideo);
                    applyYouTubeDiscoverRanking();
                    handleBack();
                }
                break;
            case YoutubeDetailRow.KIND_COMMENT:
                // Full comment body as toast — keeps list wheel-friendly (like long message peek).
                if (row.commentIndex >= 0 && row.commentIndex < youtubeComments.size()) {
                    YouTubeComment c = youtubeComments.get(row.commentIndex);
                    String body = c.content;
                    if (body != null && body.length() > 0) {
                        Toast.makeText(host.context(), body, Toast.LENGTH_LONG).show();
                    }
                }
                break;
            default:
                break;
        }
    }

    private void loadYouTubeComments(final String videoId) {
        youtubeCommentsLoading = true;
        final int gen = ++youtubeCommentsGen;
        if (host.getCurrentScreenState() == STATE_YOUTUBE_DETAIL) {
            rebuildYouTubeDetailRows();
            if (virtualAdapter != null) virtualAdapter.notifyDataSetChanged();
        }
        YouTubeClient.getInstance(host.context()).fetchComments(videoId, new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeCommentsGen) return;
                youtubeCommentsLoading = false;
                youtubeComments.clear();
                youtubeCommentsStale =
                        YouTubeResultJson.parseCacheState(payloadJson).stale;
                try {
                    youtubeComments.addAll(YouTubeResultJson.parseComments(payloadJson));
                } catch (Exception e) {
                    youtubeComments.clear();
                }
                if (host.getCurrentScreenState() == STATE_YOUTUBE_DETAIL) {
                    buildYouTubeDetailUi();
                }
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeCommentsGen) return;
                youtubeCommentsLoading = false;
                youtubeComments.clear();
                youtubeCommentsStale = false;
                if (host.getCurrentScreenState() == STATE_YOUTUBE_DETAIL) {
                    buildYouTubeDetailUi();
                    Toast.makeText(host.context(), R.string.youtube_comments_error,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadYouTubeDiscover() {
        youtubeShowingBookmarks = false;
        youtubeShowingDiscover = true;
        youtubePendingSearch = null;
        youtubeNextPageToken = "";
        youtubeAppending = false;
        youtubeLoading = true;
        youtubeDiscoverSignalsLoading = true;
        youtubeDiscoverLocalSignalsLoading = true;
        youtubeLocalLibrarySignals = YouTubeLocalLibrarySignals.empty();
        youtubeMetadataStale = false;
        youtubeDiscoverPopularStale = false;
        youtubeDiscoverPopular.clear();
        youtubeDiscoverReasons.clear();
        youtubeVideos.clear();
        final int gen = ++youtubeLoadGen;
        updateYouTubeStatusPath();
        rebuildYouTubeVirtualRows();
        notifyVirtualDataChangedPreserveFocus();

        final YouTubeClient client = YouTubeClient.getInstance(host.context());
        client.fetchPopular(new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeLoading = false;
                youtubeDiscoverPopular.clear();
                try {
                    youtubeDiscoverPopular.addAll(
                            YouTubeResultJson.parseVideos(payloadJson));
                    youtubeDiscoverPopularStale =
                            YouTubeResultJson.parseCacheState(payloadJson).stale;
                } catch (Exception ignored) {
                    youtubeDiscoverPopularStale = false;
                }
                applyYouTubeDiscoverRanking();
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeLoading = false;
                youtubeDiscoverPopular.clear();
                applyYouTubeDiscoverRanking();
            }
        });

        client.fetchDiscoverSignals(new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeDiscoverSignalsLoading = false;
                try {
                    youtubeDiscoverSignals =
                            YouTubeDiscoverSignals.parse(payloadJson);
                } catch (Exception ignored) {
                    youtubeDiscoverSignals = new YouTubeDiscoverSignals(
                            null, null, false, false, true);
                }
                applyYouTubeDiscoverRanking();
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen
                        || host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
                    return;
                }
                youtubeDiscoverSignalsLoading = false;
                youtubeDiscoverSignals = new YouTubeDiscoverSignals(
                        null, null, youtubeAuth().hasAccount(), false, true);
                applyYouTubeDiscoverRanking();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {}
                final YouTubeLocalLibrarySignals local =
                        YouTubeLocalLibrarySignals.load(host.context());
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != youtubeLoadGen
                                || host.getCurrentScreenState()
                                        != STATE_YOUTUBE_BROWSE) {
                            return;
                        }
                        youtubeDiscoverLocalSignalsLoading = false;
                        youtubeLocalLibrarySignals = local;
                        applyYouTubeDiscoverRanking();
                    }
                });
            }
        }, "YouTubeLocalSignals").start();
    }

    private void applyYouTubeDiscoverRanking() {
        if (!youtubeShowingDiscover) return;
        int minSeconds = host.prefs().getInt(
                YouTubeDiscoverRanker.PREF_MIN_DURATION_SECONDS, 0);
        int maxSeconds = host.prefs().getInt(
                YouTubeDiscoverRanker.PREF_MAX_DURATION_SECONDS, 0);
        YouTubeDiscoverRanker.Signals signals =
                new YouTubeDiscoverRanker.Signals(
                        youtubeBookmarks().list(),
                        youtubeDiscoverSignals.likedVideos,
                        youtubeDiscoverSignals.subscribedChannels,
                        YouTubeRecentSearches.get(host.context()),
                        youtubeLocalLibrarySignals.artists,
                        youtubeLocalLibrarySignals.genres,
                        youtubeDiscoverFeedback().snapshot(),
                        minSeconds,
                        maxSeconds);
        List<YouTubeDiscoverRanker.Recommendation> ranked =
                YouTubeDiscoverRanker.rank(youtubeDiscoverPopular, signals, 50);
        youtubeVideos.clear();
        youtubeDiscoverReasons.clear();
        for (YouTubeDiscoverRanker.Recommendation item : ranked) {
            youtubeVideos.add(item.video);
            youtubeDiscoverReasons.add(youtubeDiscoverReason(item));
        }
        youtubeNextPageToken = "";
        youtubeMetadataStale = youtubeDiscoverPopularStale
                || youtubeDiscoverSignals.stale;
        if (host.getCurrentScreenState() == STATE_YOUTUBE_BROWSE) {
            updateYouTubeStatusPath();
            rebuildYouTubeVirtualRows();
            notifyVirtualDataChangedPreserveFocus();
        }
    }

    private String youtubeDiscoverReason(
            YouTubeDiscoverRanker.Recommendation item) {
        if (item == null || item.reason == null) {
            return host.getString(R.string.youtube_discover_reason_popular);
        }
        switch (item.reason) {
            case MORE_LIKE:
                return host.getString(R.string.youtube_discover_reason_more_like);
            case SUBSCRIBED_CHANNEL:
                return host.getString(R.string.youtube_discover_reason_subscribed);
            case LIKED_VIDEO:
                return host.getString(R.string.youtube_discover_reason_liked);
            case LOCAL_LIBRARY_ARTIST:
                return host.getString(
                        R.string.youtube_discover_reason_local_artist,
                        item.detail);
            case LOCAL_LIBRARY_GENRE:
                return host.getString(
                        R.string.youtube_discover_reason_local_genre,
                        item.detail);
            case RECENT_SEARCH:
                return item.detail.length() > 0
                        ? host.getString(R.string.youtube_discover_reason_search,
                                item.detail)
                        : host.getString(R.string.youtube_discover_reason_research);
            case RESEARCH_LIST:
                return host.getString(R.string.youtube_discover_reason_research);
            case POPULAR_REGION:
            default:
                return host.getString(R.string.youtube_discover_reason_popular);
        }
    }

    private void loadYouTubePopular() {
        youtubeShowingBookmarks = false;
        youtubeShowingDiscover = false;
        youtubePendingSearch = null;
        youtubeNextPageToken = "";
        youtubeAppending = false;
        youtubeLoading = true;
        youtubeMetadataStale = false;
        final int gen = ++youtubeLoadGen;
        updateYouTubeStatusPath();
        rebuildYouTubeVirtualRows();
        if (virtualAdapter == null) {
            buildYouTubeBrowseUi();
        } else {
            notifyVirtualDataChangedPreserveFocus();
        }
        YouTubeClient.getInstance(host.context()).fetchPopular(new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen) return;
                if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
                youtubeLoading = false;
                youtubeMetadataStale =
                        YouTubeResultJson.parseCacheState(payloadJson).stale;
                try {
                    youtubeVideos.clear();
                    youtubeVideos.addAll(YouTubeResultJson.parseVideos(payloadJson));
                    youtubeNextPageToken =
                            YouTubeResultJson.parseNextPageToken(payloadJson);
                } catch (Exception e) {
                    youtubeVideos.clear();
                    youtubeNextPageToken = "";
                }
                updateYouTubeStatusPath();
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen) return;
                if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
                youtubeLoading = false;
                youtubeVideos.clear();
                youtubeNextPageToken = "";
                youtubeMetadataStale = false;
                updateYouTubeStatusPath();
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
                Toast.makeText(host.context(),
                        host.getString(R.string.youtube_error, message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadYouTubeSearch(final String query) {
        youtubeShowingBookmarks = false;
        youtubeShowingDiscover = false;
        youtubeNextPageToken = "";
        youtubeAppending = false;
        youtubeLoading = true;
        youtubeMetadataStale = false;
        final int gen = ++youtubeLoadGen;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("query", query != null ? query : "");
            d.put("gen", gen);
            com.solar.launcher.Debug712c71Log.log(host.context(),
                    "MediaSuiteHost.loadYouTubeSearch", "ui search start", "F", d);
        } catch (Exception ignored) {}
        // #endregion
        updateYouTubeStatusPath();
        rebuildYouTubeVirtualRows();
        // Keep focus on Search/Back while results load (was always reset to row 0).
        notifyVirtualDataChangedPreserveFocus();
        YouTubeClient.getInstance(host.context()).search(query, new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen) {
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("gen", gen);
                        d.put("curGen", youtubeLoadGen);
                        d.put("jsonLen", payloadJson != null ? payloadJson.length() : 0);
                        com.solar.launcher.Debug712c71Log.log(host.context(),
                                "MediaSuiteHost.loadYouTubeSearch",
                                "success stale gen", "D", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    return;
                }
                if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
                youtubeLoading = false;
                youtubeMetadataStale =
                        YouTubeResultJson.parseCacheState(payloadJson).stale;
                int parsed = 0;
                try {
                    youtubeVideos.clear();
                    youtubeVideos.addAll(YouTubeResultJson.parseVideos(payloadJson));
                    youtubeNextPageToken =
                            YouTubeResultJson.parseNextPageToken(payloadJson);
                    parsed = youtubeVideos.size();
                } catch (Exception e) {
                    youtubeVideos.clear();
                    youtubeNextPageToken = "";
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("err", e.getMessage() != null ? e.getMessage() : "");
                        d.put("jsonLen", payloadJson != null ? payloadJson.length() : 0);
                        com.solar.launcher.Debug712c71Log.log(host.context(),
                                "MediaSuiteHost.loadYouTubeSearch",
                                "parse failed", "C", d);
                    } catch (Exception ignored) {}
                    // #endregion
                }
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("query", query != null ? query : "");
                    d.put("parsed", parsed);
                    d.put("jsonLen", payloadJson != null ? payloadJson.length() : 0);
                    com.solar.launcher.Debug712c71Log.log(host.context(),
                            "MediaSuiteHost.loadYouTubeSearch",
                            "ui search success", "B", d);
                } catch (Exception ignored) {}
                // #endregion
                // Prefer incremental notify over full rebind (keeps selection).
                updateYouTubeStatusPath();
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen) return;
                if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return;
                youtubeLoading = false;
                youtubeVideos.clear();
                youtubeNextPageToken = "";
                youtubeMetadataStale = false;
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("query", query != null ? query : "");
                    d.put("err", message != null ? message : "");
                    com.solar.launcher.Debug712c71Log.log(host.context(),
                            "MediaSuiteHost.loadYouTubeSearch",
                            "ui search error", "A", d);
                } catch (Exception ignored) {}
                // #endregion
                updateYouTubeStatusPath();
                rebuildYouTubeVirtualRows();
                notifyVirtualDataChangedPreserveFocus();
                Toast.makeText(host.context(),
                        host.getString(R.string.youtube_error, message),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Solar-native playback — resolve stream via YouTubeClient, play in Solar IJK player.
     * 2026-07-15 — Was notPipe bridge IPC; now native Invidious/Piped/YtApiLegacy.
     * Layman: ask Solar’s backends for a playable link; try lower quality if the first fails.
     * Reversal: single resolveStream(id) then openUrl with no fallback.
     */
    private void playYouTubeVideo(final YouTubeVideo video) {
        if (video == null || video.id.isEmpty()) return;
        playYouTubeVideoAtQuality(video, YouTubeClient.preferredVideoQuality(), false);
    }

    /**
     * 2026-07-15 — Music YouTube Audio: resolve → music STATE_PLAYER (not video IJK).
     * 2026-07-16 — Play uses app play-cache only; permanent Music/YouTube is Save only.
     * Layman: Play buffers for the queue; Save keeps a library copy.
     * Technical: YouTubeDownloader.cacheAudioForPlay then playTrackList; purged off-queue.
     * Reversal: saveAudio + findSavedAudio so Play wrote permanent library files again.
     */
    private void playYouTubeAudio(final YouTubeVideo video) {
        if (video == null || video.id == null || video.id.isEmpty()) return;
        youtubeNowPlayingTitle = video.title;
        youtubeNowPlayingId = video.id;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("id", video.id);
            d.put("title", video.title != null ? video.title : "");
            d.put("author", video.author != null ? video.author : "");
            d.put("titleLen", video.title != null ? video.title.length() : 0);
            com.solar.launcher.Debug0f5debLog.log(host.context(),
                    "MediaSuiteHost.playYouTubeAudio", "play audio start", "YT-A", d);
        } catch (Exception ignored) {}
        // #endregion
        final int gen = ++youtubeLoadGen;
        youtubeResolvingStream = true;
        setYoutubeResolveStatus(host.getString(R.string.youtube_resolve_looking_up));
        refreshYouTubeResolveUi();
        // Prefer prior explicit Save only — not a leftover Play download under Music/YouTube.
        // (Legacy Play wrote permanent files; do not treat those as intentional library saves.)
        YouTubeDownloader.cacheAudioForPlay(host.context(), video, new YouTubeDownloader.Callback() {
            @Override
            public void onProgress(String phase, int percent, long doneBytes, long totalBytes) {
                if (gen != youtubeLoadGen) return;
                // 2026-07-15 — Live % while audio file is written (not a frozen “Resolving…”).
                int pct = Math.max(0, Math.min(100, percent));
                setYoutubeResolveStatus(host.getString(R.string.youtube_resolve_saving, pct));
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != youtubeLoadGen) return;
                        refreshYouTubeResolveUi();
                    }
                });
            }

            @Override
            public void onComplete(final File savedFile) {
                if (gen != youtubeLoadGen) return;
                youtubeResolvingStream = false;
                youtubeLoading = false;
                setYoutubeResolveStatus("");
                clearYouTubeResolveUi();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("path", savedFile != null ? savedFile.getAbsolutePath() : "");
                    d.put("name", savedFile != null ? savedFile.getName() : "");
                    d.put("len", savedFile != null ? savedFile.length() : 0L);
                    d.put("npTitle", youtubeNowPlayingTitle != null ? youtubeNowPlayingTitle : "");
                    com.solar.launcher.Debug0f5debLog.log(host.context(),
                            "MediaSuiteHost.playYouTubeAudio.onComplete", "cache ready", "YT-B", d);
                } catch (Exception ignored) {}
                // #endregion
                if (savedFile != null && savedFile.isFile()) {
                    // 2026-07-19 — Pass browse title/author so NP never shows “Failed” from empty m4a tags.
                    host.playAudioFileInNowPlaying(savedFile, video.title, video.author);
                } else {
                    toastYouTubePlayError(null);
                }
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen) return;
                youtubeResolvingStream = false;
                youtubeLoading = false;
                setYoutubeResolveStatus("");
                clearYouTubeResolveUi();
                toastYouTubePlayError(message);
            }
        });
    }

    /**
     * 2026-07-14 — Resolve + open player at quality; optionally silent when retrying from IJK error.
     */
    private void playYouTubeVideoAtQuality(final YouTubeVideo video, final String quality,
            final boolean fromIjkFallback) {
        if (video == null || video.id.isEmpty()) return;
        youtubeNowPlayingTitle = video.title;
        youtubeNowPlayingId = video.id;
        youtubeStreamQuality = quality;
        youtubeIjkFallbackPending = false;
        final int gen = ++youtubeLoadGen;
        youtubeResolvingStream = true;
        String qLabel = quality != null && quality.length() > 0 ? quality : "stream";
        setYoutubeResolveStatus(host.getString(R.string.youtube_resolve_getting_stream, qLabel));
        refreshYouTubeResolveUi();
        YouTubeClient.getInstance(host.context()).resolveStream(video.id, quality,
                new YouTubeClient.Callback() {
            @Override
            public void onSuccess(String payloadJson) {
                if (gen != youtubeLoadGen) return;
                youtubeResolvingStream = false;
                youtubeLoading = false;
                setYoutubeResolveStatus("");
                try {
                    youtubeStreamUrl = YouTubeResultJson.parseStreamUrl(payloadJson);
                } catch (Exception e) {
                    youtubeStreamUrl = null;
                }
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("quality", quality != null ? quality : "");
                    d.put("fromIjkFallback", fromIjkFallback);
                    d.put("urlPrefix", youtubeStreamUrl != null && youtubeStreamUrl.length() > 96
                            ? youtubeStreamUrl.substring(0, 96) : youtubeStreamUrl);
                    d.put("emptyUrl", youtubeStreamUrl == null || youtubeStreamUrl.isEmpty());
                    d.put("isDirectUrlApi", youtubeStreamUrl != null
                            && youtubeStreamUrl.indexOf("/direct_url") >= 0);
                    com.solar.launcher.Debug9d82a5Log.log(host.context(),
                            "MediaSuiteHost.resolve.onSuccess", "ui has stream url", "C", d);
                } catch (Exception ignored) {}
                // #endregion
                if (youtubeStreamUrl == null || youtubeStreamUrl.isEmpty()) {
                    String next = YouTubeClient.fallbackVideoQuality(quality);
                    if (next != null) {
                        playYouTubeVideoAtQuality(video, next, fromIjkFallback);
                        return;
                    }
                    clearYouTubeResolveUi();
                    toastYouTubePlayError(null);
                    return;
                }
                videoPlaybackYoutube = true;
                videoFiles.clear();
                host.changeScreen(STATE_VIDEO_PLAYER);
            }

            @Override
            public void onError(String message) {
                if (gen != youtubeLoadGen) return;
                youtubeResolvingStream = false;
                youtubeLoading = false;
                setYoutubeResolveStatus("");
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("quality", quality != null ? quality : "");
                    d.put("message", message != null ? message : "");
                    com.solar.launcher.Debug9d82a5Log.log(host.context(),
                            "MediaSuiteHost.resolve.onError", "resolve failed", "A", d);
                } catch (Exception ignored) {}
                // #endregion
                String next = YouTubeClient.fallbackVideoQuality(quality);
                if (next != null) {
                    playYouTubeVideoAtQuality(video, next, fromIjkFallback);
                    return;
                }
                // Final quality exhausted — high-impact stream failure (buffered resolve already waited).
                try {
                    com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                            host.context(),
                            com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                    .of("youtube")
                                    .id(video != null ? video.id : youtubeNowPlayingId)
                                    .title(video != null ? video.title : youtubeNowPlayingTitle)
                                    .artist(video != null ? video.author : "")
                                    .quality(quality)
                                    .reason(message != null ? message : "stream resolve failed"));
                } catch (Throwable ignored) {}
                clearYouTubeResolveUi();
                toastYouTubePlayError(message);
            }
        });
    }

    /** 2026-07-15 — Staged status line for Play row / future HUD. */
    private void setYoutubeResolveStatus(String status) {
        String next = status != null ? status : "";
        boolean wasBusy = youtubeResolveStatus != null && youtubeResolveStatus.length() > 0;
        youtubeResolveStatus = next;
        boolean nowBusy = next.length() > 0;
        // 2026-07-18 — Status throbber while resolving / saving YouTube stream.
        // Layman: spinner while Solar finds the stream URL. Technical: REASON_YOUTUBE_RESOLVE.
        if (nowBusy && !wasBusy) {
            com.solar.launcher.ui.UiBusy.beginAutoEnd(
                    com.solar.launcher.ui.UiBusy.REASON_YOUTUBE_RESOLVE, 90_000L);
        } else if (!nowBusy && wasBusy) {
            com.solar.launcher.ui.UiBusy.clear(com.solar.launcher.ui.UiBusy.REASON_YOUTUBE_RESOLVE);
        }
    }

    private String youtubeResolveStatusText() {
        if (youtubeResolveStatus != null && youtubeResolveStatus.length() > 0) {
            return youtubeResolveStatus;
        }
        return host.getString(R.string.youtube_resolve_looking_up);
    }

    /** Rebuild list so Play subtitle shows current resolve phase. */
    private void refreshYouTubeResolveUi() {
        int state = host.getCurrentScreenState();
        if (state == STATE_YOUTUBE_BROWSE) {
            youtubeLoading = youtubeResolvingStream;
            rebuildYouTubeVirtualRows();
            notifyVirtualDataChangedPreserveFocus();
            updateYouTubeStatusPath(); // also refreshes status-bar search throbber
        } else if (state == STATE_YOUTUBE_DETAIL) {
            rebuildYouTubeDetailRows();
            notifyVirtualDataChangedPreserveFocus();
        }
    }

    /** 2026-07-14 — Refresh browse/detail after resolve failure without yanking screen. */
    private void clearYouTubeResolveUi() {
        setYoutubeResolveStatus("");
        int state = host.getCurrentScreenState();
        if (state == STATE_YOUTUBE_BROWSE) {
            buildYouTubeBrowseUi();
        } else if (state == STATE_YOUTUBE_DETAIL) {
            rebuildYouTubeDetailRows();
            notifyVirtualDataChangedPreserveFocus();
        }
    }

    /**
     * 2026-07-14 — Short toast; append bridge reason when short enough for 2.4" display.
     */
    private void toastYouTubePlayError(String bridgeMessage) {
        if (bridgeMessage != null && bridgeMessage.length() > 0
                && bridgeMessage.length() <= 48
                && !"timeout".equals(bridgeMessage)) {
            Toast.makeText(host.context(),
                    host.getString(R.string.youtube_play_error_detail, bridgeMessage),
                    Toast.LENGTH_SHORT).show();
        } else if ("timeout".equals(bridgeMessage)) {
            Toast.makeText(host.context(), R.string.youtube_play_timeout, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(host.context(), R.string.youtube_play_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Wheel keyboard submitted a YouTube search query. */
    public void onYouTubeSearchSubmitted(String query) {
        if (query == null || query.trim().isEmpty()) return;
        youtubeShowingBookmarks = false;
        youtubePendingSearch = query.trim();
        YouTubeRecentSearches.remember(host.context(), youtubePendingSearch);
        if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) {
            host.changeScreen(STATE_YOUTUBE_BROWSE);
        }
        loadYouTubeSearch(youtubePendingSearch);
    }

    /** Context menu — focused wheel row on YouTube browse, or null. */
    public YouTubeVideo getFocusedYouTubeVideo() {
        if (host.getCurrentScreenState() != STATE_YOUTUBE_BROWSE) return null;
        android.widget.ListView lv = host.listVirtualSongs();
        if (lv == null) return null;
        int pos = lv.getSelectedItemPosition();
        if (pos < 0 || pos >= youtubeBrowseRows.size()) return null;
        YoutubeBrowseRow row = youtubeBrowseRows.get(pos);
        if (row.kind != YoutubeBrowseRow.KIND_VIDEO || row.videoIndex < 0) return null;
        if (row.videoIndex >= youtubeVideos.size()) return null;
        return youtubeVideos.get(row.videoIndex);
    }

    /** Context menu — currently streaming YouTube item in Solar player. */
    public YouTubeVideo getYouTubeNowPlayingVideo() {
        if (!videoPlaybackYoutube || youtubeNowPlayingId == null
                || youtubeNowPlayingId.isEmpty()) {
            return null;
        }
        return new YouTubeVideo(youtubeNowPlayingId,
                youtubeNowPlayingTitle != null ? youtubeNowPlayingTitle : "", "", "");
    }

    /**
     * 2026-07-15 — True when browse/detail came from Music→YouTube (audio Now Playing path).
     * Layman: Music hub YouTube, not Videos. Technical: youtubeAudioMode flag for labels/ctx.
     */
    public boolean isYouTubeAudioMode() {
        return youtubeAudioMode;
    }

    public boolean isYouTubePlaybackActive() {
        return videoPlaybackYoutube;
    }

    public boolean isVideoPlaying() {
        return (videoController != null && videoController.isPlaying()) || videoPlaybackYoutube;
    }

    /**
     * 2026-07-15 — Silence video / live YouTube stream before music or Deezer takes over.
     * Layman: starting a song must kill the video player if it was still making noise.
     * Technical: bump youtubeLoadGen so in-flight resolve/saveAudio callbacks no-op;
     * release VideoPlayerController; clear stream flags. Does not change screen.
     * Was: only music MediaPlayer was reset — YouTube video IJK kept playing under music.
     * Reversal: empty method body.
     */
    public void stopVideoAndYoutubeStream() {
        youtubeLoadGen++;
        youtubeResolvingStream = false;
        youtubeLoading = false;
        setYoutubeResolveStatus("");
        youtubeStreamUrl = null;
        videoPlaybackYoutube = false;
        youtubeIjkFallbackPending = false;
        // 2026-07-18 — Drop YT/video busy throbbers when engines stop.
        com.solar.launcher.ui.UiBusy.clear(com.solar.launcher.ui.UiBusy.REASON_YOUTUBE_RESOLVE);
        com.solar.launcher.ui.UiBusy.clear(com.solar.launcher.ui.UiBusy.REASON_MEDIA_BUFFER);
        releaseVideoPlayer();
        showVideoPlayerLayer(false);
    }

    /** Context action — play a YouTube row without OK tap. */
    public void playYouTubeFromContext(YouTubeVideo video) {
        // 2026-07-15 — Audio mode from Music hub keeps context Play on music NP.
        if (youtubeAudioMode) {
            playYouTubeAudio(video);
        } else {
            playYouTubeVideo(video);
        }
    }

    /** Context — open detail/comments for a browse row. */
    public void openYouTubeDetailFromContext(YouTubeVideo video) {
        openYouTubeDetail(video);
    }

    /** Context — video currently shown on detail screen. */
    public YouTubeVideo getYouTubeDetailVideo() {
        return youtubeDetailVideo;
    }

    // --- Videos ---

    private void buildVideosUi() {
        prepareVirtualListBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        host.setBrowserStatusTitle(host.getString(R.string.status_videos));
        if (videoBrowseFolder == null) {
            videoBrowseFolder = VideoLibrary.ROOT;
        }
        videoFiles.clear();
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        // 2026-07-19 — Search… opens wheel keyboard for filename filter (skip long folder scrolls).
        virtualLabels.add(host.getString(R.string.browser_search_ellipsis));
        virtualLabels.add(host.getString(R.string.video_folder_header, videoBrowseFolder.getName()));

        List<File> folders = VideoLibrary.listChildFoldersWithVideos(videoBrowseFolder);
        videoFiles = VideoLibrary.listInFolder(videoBrowseFolder);
        for (File f : folders) {
            virtualLabels.add(host.getString(R.string.video_folder_row, f.getName()));
        }
        final boolean atVideosRoot = videoBrowseFolder.equals(VideoLibrary.ROOT);
        final boolean showAllVideosRow = atVideosRoot && (!folders.isEmpty() || !videoFiles.isEmpty()
                || !VideoLibrary.scanAll().isEmpty());
        if (showAllVideosRow) {
            virtualLabels.add(host.getString(R.string.video_all_videos));
        }
        if (videoFiles.isEmpty() && folders.isEmpty()) {
            virtualLabels.add(host.getString(
                    atVideosRoot ? R.string.video_none_found : R.string.video_none_in_folder));
        } else {
            for (File f : videoFiles) virtualLabels.add(f.getName());
        }

        final int folderStart = 3;
        final int folderCount = folders.size();
        final int allVideosPos = showAllVideosRow ? folderStart + folderCount : -1;
        final int fileStart = folderStart + folderCount + (showAllVideosRow ? 1 : 0);

        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    handleBack();
                    return;
                }
                if (position == 1) {
                    host.openVideoFileSearchKeyboard();
                    return;
                }
                if (position == 2) return;
                if (position >= folderStart && position < folderStart + folderCount) {
                    videoBrowseFolder = folders.get(position - folderStart);
                    buildVideosUi();
                    return;
                }
                if (allVideosPos >= 0 && position == allVideosPos) {
                    showAllVideosFlatList();
                    return;
                }
                int fileIdx = position - fileStart;
                if (fileIdx >= 0 && fileIdx < videoFiles.size()) {
                    videoIndex = fileIdx;
                    host.changeScreen(STATE_VIDEO_PLAYER);
                }
            }
        });
    }

    private void showAllVideosFlatList() {
        final List<File> all = VideoLibrary.scanAll();
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.browser_search_ellipsis));
        if (all.isEmpty()) {
            virtualLabels.add(host.getString(R.string.video_none_found));
        } else {
            for (File f : all) virtualLabels.add(f.getName());
        }
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    buildVideosUi();
                    return;
                }
                if (position == 1) {
                    host.openVideoFileSearchKeyboard();
                    return;
                }
                int idx = position - 2;
                if (idx >= 0 && idx < all.size()) {
                    videoFiles = all;
                    videoIndex = idx;
                    host.changeScreen(STATE_VIDEO_PLAYER);
                }
            }
        });
    }

    /**
     * 2026-07-19 — Re-open My Videos folder UI after empty video search.
     * Layman: Back out of a blank search to the normal video list.
     */
    public void buildVideosUiPublic() {
        buildVideosUi();
    }

    /**
     * 2026-07-19 — Filtered flat list of local videos matching {@code query} (filename).
     * Layman: only show videos whose names contain what you typed.
     * Technical: substring match on name; reuse video player path. Reversal: delete call sites.
     */
    public void showVideoSearchResults(String query) {
        final String q = query != null ? query.trim().toLowerCase(java.util.Locale.US) : "";
        final List<File> all = VideoLibrary.scanAll();
        final List<File> matched = new ArrayList<File>();
        if (q.length() > 0) {
            for (File f : all) {
                if (f == null || f.getName() == null) continue;
                if (f.getName().toLowerCase(java.util.Locale.US).contains(q)) {
                    matched.add(f);
                }
            }
        }
        prepareVirtualListBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        host.setBrowserStatusTitle(host.getString(R.string.status_videos));
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));
        virtualLabels.add(host.getString(R.string.browser_search_ellipsis));
        if (matched.isEmpty()) {
            virtualLabels.add(host.getString(R.string.video_search_none));
        } else {
            for (File f : matched) virtualLabels.add(f.getName());
        }
        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    buildVideosUi();
                    return;
                }
                if (position == 1) {
                    host.openVideoFileSearchKeyboard();
                    return;
                }
                int idx = position - 2;
                if (idx >= 0 && idx < matched.size()) {
                    videoFiles = matched;
                    videoIndex = idx;
                    host.changeScreen(STATE_VIDEO_PLAYER);
                }
            }
        });
    }

    private void showVideoPlayerLayer(boolean show) {
        FrameLayout layout = host.findViewById(R.id.layout_video_mode);
        if (layout != null) {
            layout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                layout.bringToFront();
            }
        }
        MediaTransportBar transport = host.videoTransportBar();
        if (transport != null) {
            if (show) {
                transport.styleVideoChrome();
                transport.setVideoOverlayMode(true);
                transport.setVideoOverlayPersistent(true);
                transport.showScrubTrack();
                transport.setVisible(true);
            } else {
                transport.setVideoOverlayPersistent(false);
                transport.setVideoOverlayMode(false);
                transport.hideVolumePulse();
                transport.setVisible(false);
            }
        }
        if (host.layoutBrowserMode() != null) {
            host.layoutBrowserMode().setVisibility(show ? View.GONE : View.VISIBLE);
        }
        if (show) {
            host.setStatusBarVisible(false);
        }
    }

    private final Runnable videoProgressTick =
            new Runnable() {
                @Override
                public void run() {
                    if (videoController == null || !videoController.isPrepared()) {
                        videoProgressHandler.postDelayed(this, 500);
                        return;
                    }
                    if (!videoScrubActive) {
                        long actualMs = videoController.getCurrentPosition();
                        if (videoPendingSeekMs >= 0L) {
                            if (VideoSeekPolicy.isComplete(videoPendingSeekMs, actualMs)
                                    || VideoSeekPolicy.hasTimedOut(
                                            videoSeekRequestedAtMs, SystemClock.uptimeMillis())) {
                                completeVideoSeek(actualMs);
                            } else {
                                updateVideoProgressUi(videoPendingSeekMs);
                            }
                        } else {
                            updateVideoProgressUi(actualMs);
                        }
                    }
                    videoProgressHandler.postDelayed(this, 500);
                }
            };

    public boolean isVideoScrubActive() {
        return videoScrubActive;
    }

    /** Back during fine scrub — discard cursor, keep playback position. */
    public void cancelVideoScrub() {
        clearVideoScrubMode(true);
    }

    /** Show iPod-style video transport overlay (volume / scrub / skip). */
    public void pulseVideoTransport() {
        MediaTransportBar transport = host.videoTransportBar();
        if (transport != null) transport.pulseVideoOverlay();
    }

    /** Center OK — enter scrub cursor or commit seek (matches Now Playing progress bar). */
    public void handleVideoCenterOk() {
        if (videoController == null || !videoController.isPrepared()) return;
        if (videoScrubActive) {
            commitVideoScrub();
        } else {
            enterVideoScrubMode();
        }
    }

    /** Begin a visible side-button fast-forward/rewind session. */
    public boolean beginVideoSkipScrub() {
        if (videoScrubActive) return true;
        enterVideoScrubMode();
        return videoScrubActive;
    }

    /** Commit the side-button scrub cursor as one engine seek on key release. */
    public void commitVideoSkipScrub() {
        commitVideoScrub();
    }

    public void moveVideoScrubCursor(int deltaMs) {
        if (!videoScrubActive || videoController == null) return;
        long dur = videoDurationMs();
        if (dur <= 0) return;
        videoScrubMs = clampVideoScrubMs(videoScrubMs + deltaMs, dur);
        updateVideoProgressUi(videoScrubMs);
        updateVideoScrubMarker();
        pulseVideoTransport();
    }

    private void enterVideoScrubMode() {
        if (videoController == null || !videoController.isPrepared()) return;
        long dur = videoDurationMs();
        if (dur <= 0) return;
        videoScrubMs = clampVideoScrubMs(videoController.getCurrentPosition(), dur);
        videoScrubActive = true;
        updateVideoProgressUi(videoScrubMs);
        updateVideoScrubMarker();
        pulseVideoTransport();
    }

    private void commitVideoScrub() {
        if (!videoScrubActive || videoController == null) return;
        // 2026-07-18 — Allow target past buffer; pending seek + buffering UI until catch-up.
        seekVideoToTarget(videoScrubMs);
        clearVideoScrubMode(false);
    }

    private void clearVideoScrubMode(boolean restoreLive) {
        videoScrubActive = false;
        View marker = host.videoTransportBar() != null ? host.videoTransportBar().scrubMarker() : null;
        if (marker != null) marker.setVisibility(View.GONE);
        if (restoreLive && videoController != null && videoController.isPrepared()) {
            updateVideoProgressUi(videoController.getCurrentPosition());
        }
    }

    private void updateVideoProgressUi(long positionMs) {
        long dur = videoDurationMs();
        MediaTransportBar transport = host.videoTransportBar();
        ProgressBar bar = transport != null ? transport.progressBar() : null;
        TextView cur = transport != null ? transport.timeCurrent() : null;
        TextView tot = transport != null ? transport.timeTotal() : null;
        if (dur > 0 && bar != null) {
            int pct = (int) Math.min(100, (positionMs * 100L) / dur);
            bar.setProgress(pct);
            // Secondary = buffered fraction (IJK/MediaPlayer percent).
            int buf = Math.max(0, Math.min(100, videoBufferPercent));
            try {
                bar.setSecondaryProgress(buf);
            } catch (Throwable ignored) {}
        }
        if (cur != null) cur.setText(formatVideoTime(positionMs));
        if (tot != null) {
            tot.setText(dur > 0 ? formatVideoTime(dur) : "00:00");
        }
    }

    /**
     * Submit every target directly to the engine. IJK/MediaPlayer perform their own network Range
     * and buffering work; waiting for onBufferingUpdate first can deadlock proxy-backed streams.
     */
    private void seekVideoToTarget(long targetMs) {
        if (videoController == null || !videoController.isPrepared()) return;
        long dur = videoDurationMs();
        long pos = VideoSeekPolicy.clampTarget(targetMs, dur);
        videoPendingSeekMs = pos;
        videoSeekRequestedAtMs = SystemClock.uptimeMillis();
        com.solar.launcher.ui.UiBusy.beginAutoEnd(
                com.solar.launcher.ui.UiBusy.REASON_SEEK_BUFFER,
                VideoSeekPolicy.SEEK_TIMEOUT_MS);
        setVideoStatusText(host.getString(R.string.video_seek_target, formatVideoTime(pos)));
        if (!videoController.seekTo(pos)) {
            clearVideoSeekState();
            Toast.makeText(host.context(), R.string.video_seek_failed, Toast.LENGTH_SHORT).show();
            updateVideoProgressUi(videoController.getCurrentPosition());
            return;
        }
        updateVideoProgressUi(pos);
        pulseVideoTransport();
    }

    private void completeVideoSeek(long actualMs) {
        clearVideoSeekState();
        updateVideoProgressUi(actualMs);
        pulseVideoTransport();
    }

    private void clearVideoSeekState() {
        videoPendingSeekMs = -1L;
        videoSeekRequestedAtMs = 0L;
        com.solar.launcher.ui.UiBusy.clear(com.solar.launcher.ui.UiBusy.REASON_SEEK_BUFFER);
        clearVideoStatusText();
    }

    private void updateVideoScrubMarker() {
        MediaTransportBar transport = host.videoTransportBar();
        if (transport == null) return;
        View marker = transport.scrubMarker();
        ProgressBar bar = transport.progressBar();
        if (marker == null || bar == null) return;
        if (!videoScrubActive) {
            marker.setVisibility(View.GONE);
            return;
        }
        long dur = videoDurationMs();
        if (dur <= 0) {
            marker.setVisibility(View.GONE);
            return;
        }
        int trackW = bar.getWidth();
        if (trackW <= 0) {
            bar.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            updateVideoScrubMarker();
                        }
                    });
            return;
        }
        float frac = (float) videoScrubMs / (float) dur;
        float density = host.getResources().getDisplayMetrics().density;
        int markerW = marker.getWidth() > 0 ? marker.getWidth() : (int) (10 * density);
        int x = (int) (frac * trackW) - markerW / 2;
        x = Math.max(0, Math.min(x, trackW - markerW));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) marker.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(markerW, markerW);
        }
        lp.width = markerW;
        lp.height = markerW;
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        lp.leftMargin = x;
        marker.setLayoutParams(lp);
        marker.setVisibility(View.VISIBLE);
    }

    private long videoDurationMs() {
        return videoController != null ? videoController.getDuration() : 0L;
    }

    private static long clampVideoScrubMs(long ms, long dur) {
        return VideoSeekPolicy.clampTarget(ms, dur);
    }

    private static String formatVideoTime(long ms) {
        return VideoSeekPolicy.formatTime(ms);
    }

    private void startVideoProgressUpdates() {
        videoProgressHandler.removeCallbacks(videoProgressTick);
        videoProgressHandler.post(videoProgressTick);
    }

    private void stopVideoProgressUpdates() {
        videoProgressHandler.removeCallbacks(videoProgressTick);
    }

    /**
     * 2026-07-15 — Exit video player the same way Back does.
     * Layman: leave the watching screen and go back to the list.
     */
    private void leaveVideoPlayerToBrowse() {
        boolean wasYt = videoPlaybackYoutube;
        releaseVideoPlayer();
        showVideoPlayerLayer(false);
        endVideoForceLandscapeSession();
        if (wasYt) {
            videoPlaybackYoutube = false;
            if (youtubeDetailVideo != null) {
                host.changeScreen(STATE_YOUTUBE_DETAIL);
            } else {
                host.changeScreen(STATE_YOUTUBE_BROWSE);
            }
        } else {
            host.changeScreen(STATE_VIDEOS);
        }
    }

    /**
     * 2026-07-15 — Natural end with nothing next → leave player (no wrap).
     * Layman: when the clip finishes and there is no later file, go back.
     * Was: freeze on last frame until Back.
     */
    private void handleVideoPlaybackEnded() {
        if (videoPlaybackYoutube) {
            leaveVideoPlayerToBrowse();
            return;
        }
        if (videoFiles.isEmpty() || videoIndex < 0) {
            leaveVideoPlayerToBrowse();
            return;
        }
        if (videoIndex + 1 < videoFiles.size()) {
            videoIndex = videoIndex + 1;
            pulseVideoTransport();
            startVideoPlayback();
            return;
        }
        leaveVideoPlayerToBrowse();
    }

    /**
     * 2026-07-15 — Force landscape for non-portrait video on A5 / portrait experiment.
     * Layman: turn the device on its side to watch wide videos.
     */
    private void beginVideoForceLandscapeSession() {
        com.solar.launcher.LandscapeOrientationGuard.setForceLandscapeVideoSession(true);
        applyOrientationGuard();
    }

    /** 2026-07-15 — Clear forced landscape when leaving the player. */
    private void endVideoForceLandscapeSession() {
        com.solar.launcher.LandscapeOrientationGuard.setForceLandscapeVideoSession(false);
        applyOrientationGuard();
    }

    /** 2026-07-15 — Re-run activity orientation after video session flag flips. */
    private void applyOrientationGuard() {
        Context ctx = host.context();
        if (ctx instanceof Activity) {
            com.solar.launcher.LandscapeOrientationGuard.enforceForDevice((Activity) ctx);
        }
    }

    /**
     * MATCH_PARENT surface with CENTER gravity — letterbox bars even top/bottom (and sides).
     * 2026-07-15 — Was plain MATCH_PARENT (FrameLayout defaults to top-left → picture sat high).
     */
    private static FrameLayout.LayoutParams centeredVideoSurfaceLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    /** Show / hide centered load-buffer label over the video panel. */
    private void setVideoStatusText(String text) {
        TextView tv = host.findViewById(R.id.tv_video_status);
        if (tv == null) return;
        if (text == null || text.isEmpty()) {
            tv.setVisibility(View.GONE);
            tv.setText("");
            return;
        }
        tv.setText(text);
        tv.setVisibility(View.VISIBLE);
    }

    private void clearVideoStatusText() {
        setVideoStatusText(null);
    }

    /** Buffering % from IJK/MediaPlayer for load status and secondary progress. */
    private VideoPlayerController.BufferingListener videoBufferingListener() {
        return new VideoPlayerController.BufferingListener() {
            @Override
            public void onBuffering(final int percent) {
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (host.getCurrentScreenState() != STATE_VIDEO_PLAYER) return;
                        // Secondary progress remains useful even though the engine now owns seeking.
                        videoBufferPercent = Math.max(0, Math.min(100, percent));
                        if (videoPendingSeekMs >= 0L) {
                            updateVideoProgressUi(videoPendingSeekMs);
                            return;
                        }
                        if (percent >= 100 || (videoController != null && videoController.isPlaying())) {
                            clearVideoStatusText();
                            // 2026-07-18 — Video filled enough to play — drop media buffer throbber.
                            com.solar.launcher.ui.UiBusy.clear(
                                    com.solar.launcher.ui.UiBusy.REASON_MEDIA_BUFFER);
                            // Still refresh secondary progress while playing.
                            if (videoController != null && videoController.isPrepared()) {
                                updateVideoProgressUi(videoController.getCurrentPosition());
                            }
                            return;
                        }
                        // Mid-buffer / first fill — keep status spinner on.
                        com.solar.launcher.ui.UiBusy.beginAutoEnd(
                                com.solar.launcher.ui.UiBusy.REASON_MEDIA_BUFFER, 60_000L);
                        setVideoStatusText(host.getString(R.string.youtube_resolve_buffering, percent));
                    }
                });
            }

            @Override
            public void onReadyToPlay() {
                if (videoPlaybackYoutube) {
                    try {
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaOk(
                                host.context(),
                                com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                        .of("youtube")
                                        .id(youtubeNowPlayingId)
                                        .title(youtubeNowPlayingTitle)
                                        .quality(youtubeStreamQuality)
                                        .reason("playback started")
                                        .ok(true));
                    } catch (Throwable ignored) {}
                }
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        com.solar.launcher.ui.UiBusy.clear(
                                com.solar.launcher.ui.UiBusy.REASON_MEDIA_BUFFER);
                        if (videoPendingSeekMs >= 0L) {
                            updateVideoProgressUi(videoPendingSeekMs);
                            return;
                        }
                        String key = getVideoResumeKey();
                        if (key != null) {
                            long savedPos = host.prefs().getLong(key, 0L);
                            if (savedPos > 0) {
                                seekVideoToTarget(savedPos);
                                host.prefs().edit().remove(key).apply();
                                return;
                            }
                        }
                        clearVideoStatusText();
                    }
                });
            }
        };
    }

    private VideoPlayerController.SeekListener videoSeekListener() {
        return new VideoPlayerController.SeekListener() {
            @Override
            public void onSeekComplete(final long positionMs) {
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (videoPendingSeekMs < 0L) return;
                        completeVideoSeek(positionMs);
                    }
                });
            }
        };
    }

    /**
     * 2026-07-15 — After decode size known: letterbox/crop surface; drop force if source is tall.
     */
    private void onVideoDecodedSize(int width, int height) {
        if (videoSurface != null && width > 0 && height > 0) {
            videoSurface.setVideoSize(width, height);
            videoSurface.setAspectRatio(
                    com.solar.launcher.video.VideoSettings.ijkAspectRatio(host.context()));
            // Re-assert center gravity after size-driven requestLayout.
            videoSurface.setLayoutParams(centeredVideoSurfaceLp());
            if (videoPendingSeekMs < 0L) clearVideoStatusText();
        }
        boolean portraitSource = height > width;
        if (portraitSource) {
            if (com.solar.launcher.LandscapeOrientationGuard.isForceLandscapeVideoSession()) {
                com.solar.launcher.LandscapeOrientationGuard.setForceLandscapeVideoSession(false);
                applyOrientationGuard();
            }
        } else if (host.getCurrentScreenState() == STATE_VIDEO_PLAYER) {
            if (!com.solar.launcher.LandscapeOrientationGuard.isForceLandscapeVideoSession()) {
                com.solar.launcher.LandscapeOrientationGuard.setForceLandscapeVideoSession(true);
                applyOrientationGuard();
            }
        }
    }

    /** Apply crop pref + shared completion/size listener for local + YouTube players. */
    private VideoPlayerController.PlaybackListener videoPlaybackListener() {
        return new VideoPlayerController.PlaybackListener() {
            @Override
            public void onError(int what, int extra) {
                if (!videoPlaybackYoutube) return;
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("what", what);
                    d.put("extra", extra);
                    d.put("quality", youtubeStreamQuality != null ? youtubeStreamQuality : "");
                    com.solar.launcher.Debug9d82a5Log.log(host.context(),
                            "MediaSuiteHost.videoListener.onError", "ijk error → fallback",
                            "E", d);
                } catch (Exception ignored) {}
                // #endregion
                // During playback/buffering — natural wait window; damped one-liner to SolarDev.
                try {
                    com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                            host.context(),
                            com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                    .of("youtube")
                                    .id(youtubeNowPlayingId)
                                    .title(youtubeNowPlayingTitle)
                                    .quality(youtubeStreamQuality)
                                    .reason("ijk what=" + what + " extra=" + extra));
                } catch (Throwable ignored) {}
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleYoutubeIjkError();
                    }
                });
            }

            @Override
            public void onCompletion() {
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleVideoPlaybackEnded();
                    }
                });
            }

            @Override
            public void onVideoSize(final int width, final int height) {
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onVideoDecodedSize(width, height);
                    }
                });
            }
        };
    }

    private void startVideoPlayback() {
        if (videoPlaybackYoutube) {
            startYoutubeStreamPlayback();
            return;
        }
        if (videoFiles.isEmpty() || videoIndex < 0 || videoIndex >= videoFiles.size()) {
            Toast.makeText(host.context(), R.string.video_play_error, Toast.LENGTH_SHORT).show();
            host.changeScreen(STATE_VIDEOS);
            return;
        }
        // 2026-07-15 — One activity only: video owns the speaker (stop music/FM, not pause).
        // Layman: starting a video mutes any song that was playing.
        // Was: pauseMusicPlayback → music IJK could resume under video.
        host.stopMusicPlayback();
        host.stopNonFmPlayback();
        releaseVideoPlayer();
        // stopNonFmPlayback() also tears down the shared video layer. Restore it only after
        // that cleanup so the persistent progress/scrub controls remain visible for local files.
        showVideoPlayerLayer(true);
        FrameLayout surfaceHost = host.findViewById(R.id.video_surface_host);
        if (surfaceHost == null) {
            Toast.makeText(host.context(), R.string.video_play_error, Toast.LENGTH_SHORT).show();
            return;
        }
        // 2026-07-15 — Clear OS ~80% headphone lock so volume wheel can climb full range.
        com.solar.launcher.HearingSafetyVolume.ensureFullVolumeRange(host.context());
        videoSurface = new SurfaceRenderView(host.context());
        videoSurface.setAspectRatio(
                com.solar.launcher.video.VideoSettings.ijkAspectRatio(host.context()));
        // Letterbox must sit in the middle of the black panel (FrameLayout default is top-left).
        surfaceHost.addView(videoSurface, centeredVideoSurfaceLp());
        videoController = new VideoPlayerController(host.context());
        videoController.setPlaybackListener(videoPlaybackListener());
        videoController.setBufferingListener(videoBufferingListener());
        videoController.setSeekListener(videoSeekListener());
        videoController.attachHolder(videoSurface.getHolder());
        try {
            setVideoStatusText(host.getString(R.string.youtube_resolve_opening));
            videoController.open(videoFiles.get(videoIndex));
            videoController.play();
            startVideoProgressUpdates();
            updateVideoProgressUi(0);
        } catch (Exception e) {
            clearVideoStatusText();
            Toast.makeText(host.context(), R.string.video_play_error, Toast.LENGTH_SHORT).show();
            host.changeScreen(STATE_VIDEOS);
        }
    }

    /**
     * YouTube playback — notPipe-aligned: progressive download then local MediaPlayer is the
     * reliable path (SolarHttp TLS); JIT proxy is a fast path that must fail-open to download.
     * 2026-07-16 — CRITICAL: do NOT call stopNonFmPlayback() here (wipes youtubeStreamUrl).
     * 2026-07-19 — Y2/A5 prefer download-first (API 19 IJK/proxy fragile); Y1 tries proxy then download.
     * Was: proxy-only with no download. Reversal: remove preferDownload / downloadYoutubeThenPlay.
     */
    private void startYoutubeStreamPlayback() {
        final String url = youtubeStreamUrl;
        final String vid = youtubeNowPlayingId != null ? youtubeNowPlayingId : "yt";
        final String q = youtubeStreamQuality != null ? youtubeStreamQuality : "360";
        if (url == null || url.isEmpty()) {
            android.util.Log.e("SolarYouTube", "start play aborted: empty stream url id=" + vid);
            Toast.makeText(host.context(), R.string.youtube_play_error, Toast.LENGTH_SHORT).show();
            leaveYouTubePlayerOnError();
            return;
        }
        youtubeStreamUrl = url;
        videoPlaybackYoutube = true;
        youtubeTriedProgDownload = false;
        youtubeProgCancel.set(false);
        android.util.Log.i("SolarYouTube", "start play q=" + q
                + " url=" + (url.length() > 120 ? url.substring(0, 120) : url));
        host.stopMusicPlayback();
        releaseVideoPlayer();
        com.solar.launcher.HearingSafetyVolume.ensureFullVolumeRange(host.context());
        FrameLayout surfaceHost = host.findViewById(R.id.video_surface_host);
        if (surfaceHost == null) {
            Toast.makeText(host.context(), R.string.video_play_error, Toast.LENGTH_SHORT).show();
            return;
        }
        videoSurface = new SurfaceRenderView(host.context());
        videoSurface.setAspectRatio(
                com.solar.launcher.video.VideoSettings.ijkAspectRatio(host.context()));
        surfaceHost.addView(videoSurface, centeredVideoSurfaceLp());
        videoController = new VideoPlayerController(host.context());
        videoController.setPlaybackListener(videoPlaybackListener());
        videoController.setBufferingListener(videoBufferingListener());
        videoController.setSeekListener(videoSeekListener());
        videoController.attachHolder(videoSurface.getHolder());

        final int gen = ++youtubeLoadGen;
        final File cached = YouTubeProgressiveCache.cacheFile(host.context(), vid, q);
        if (YouTubeProgressiveCache.isUsable(cached) && cached.length() > 1024L * 1024L) {
            openYoutubeCachedFile(cached);
            return;
        }
        // Y2/A5: download via SolarHttp then open(file) — same stack that works for podcasts/OTA.
        // Y1: try loopback proxy first for instant start; download if open throws.
        boolean preferDownload = com.solar.launcher.DeviceFeatures.isY2()
                || com.solar.launcher.DeviceFeatures.isA5();
        if (preferDownload) {
            downloadYoutubeThenPlay(url, vid, q, gen);
            return;
        }
        setVideoStatusText(host.getString(R.string.youtube_resolve_opening));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.solar.launcher.net.SolarStreamProxy.ensureStarted(host.context());
                    final String proxyUrl = com.solar.launcher.net.SolarStreamProxy.proxyUrl(url);
                    if (gen != youtubeLoadGen) return;
                    host.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != youtubeLoadGen) return;
                            try {
                                if (videoController == null) {
                                    downloadYoutubeThenPlay(url, vid, q, gen);
                                    return;
                                }
                                setVideoStatusText(host.getString(R.string.youtube_resolve_opening));
                                videoController.openUrl(proxyUrl);
                                videoController.play();
                                startVideoProgressUpdates();
                                updateVideoProgressUi(0);
                            } catch (Exception e) {
                                android.util.Log.w("SolarYouTube", "proxy open failed → download", e);
                                downloadYoutubeThenPlay(url, vid, q, gen);
                            }
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("SolarYouTube", "proxy start failed → download", e);
                    if (gen != youtubeLoadGen) return;
                    host.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != youtubeLoadGen) return;
                            downloadYoutubeThenPlay(url, vid, q, gen);
                        }
                    });
                }
            }
        }, "YouTubeProxyPlay").start();
    }

    /**
     * 2026-07-19 — Download progressive MP4 with SolarHttp (Conscrypt), then MediaPlayer open(file).
     * Layman: save the video briefly, then play the file like a local clip (works when live stream fails).
     * Technical: YouTubeProgressiveCache.download; cancels on leave via youtubeProgCancel.
     * Reversal: toast + leaveYouTubePlayerOnError on proxy failure only.
     */
    private void downloadYoutubeThenPlay(final String url, final String vid, final String q,
            final int gen) {
        if (youtubeTriedProgDownload) {
            toastYouTubePlayError(null);
            leaveYouTubePlayerOnError();
            return;
        }
        youtubeTriedProgDownload = true;
        youtubeProgCancel.set(false);
        setVideoStatusText(host.getString(R.string.youtube_resolve_saving, 0));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File playFile = YouTubeProgressiveCache.download(
                            host.context(), url, vid, q, youtubeProgCancel,
                            new YouTubeProgressiveCache.Progress() {
                                @Override
                                public void onProgress(final int percent, long done, long total) {
                                    if (gen != youtubeLoadGen) return;
                                    host.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (gen != youtubeLoadGen) return;
                                            setVideoStatusText(host.getString(
                                                    R.string.youtube_resolve_saving, percent));
                                        }
                                    });
                                }
                            });
                    if (gen != youtubeLoadGen || youtubeProgCancel.get()) return;
                    host.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != youtubeLoadGen) return;
                            // Rebuild surface/controller if release raced during download.
                            if (videoController == null || videoSurface == null) {
                                FrameLayout surfaceHost = host.findViewById(R.id.video_surface_host);
                                if (surfaceHost == null) {
                                    toastYouTubePlayError(null);
                                    leaveYouTubePlayerOnError();
                                    return;
                                }
                                surfaceHost.removeAllViews();
                                videoSurface = new SurfaceRenderView(host.context());
                                videoSurface.setAspectRatio(
                                        com.solar.launcher.video.VideoSettings.ijkAspectRatio(
                                                host.context()));
                                surfaceHost.addView(videoSurface, centeredVideoSurfaceLp());
                                videoController = new VideoPlayerController(host.context());
                                videoController.setPlaybackListener(videoPlaybackListener());
                                videoController.setBufferingListener(videoBufferingListener());
                                videoController.setSeekListener(videoSeekListener());
                                videoController.attachHolder(videoSurface.getHolder());
                            }
                            openYoutubeCachedFile(playFile);
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("SolarYouTube", "progressive download failed", e);
                    if (gen != youtubeLoadGen) return;
                    host.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != youtubeLoadGen) return;
                            // Quality ladder after download miss (CDN / format).
                            handleYoutubeIjkError();
                        }
                    });
                }
            }
        }, "YouTubeProgDownload").start();
    }

    /**
     * ADB/automated play: resolve + play a video id (Videos YouTube path, not audio-only).
     * Usage: am start … --ez solar_adb_play_youtube true --es solar_adb_youtube_id VIDEO_ID
     */
    public void adbPlayYouTubeVideo(String videoId, String title) {
        if (videoId == null || videoId.trim().isEmpty()) {
            android.util.Log.e("SolarYouTube", "adb play: empty id");
            return;
        }
        youtubeAudioMode = false;
        String t = title != null && title.length() > 0 ? title : videoId;
        playYouTubeVideo(new YouTubeVideo(videoId.trim(), t, "", ""));
    }

    /**
     * Play cached progressive MP4 with MediaPlayer (local path — notPipe style).
     * Layman: file is already on disk with Solar TLS; stock player reads the file.
     */
    private void openYoutubeCachedFile(File playFile) {
        if (playFile == null || !playFile.isFile() || playFile.length() < 64 * 1024L) {
            toastYouTubePlayError(null);
            leaveYouTubePlayerOnError();
            return;
        }
        try {
            setVideoStatusText(host.getString(R.string.youtube_resolve_opening));
            if (videoController == null) {
                videoController = new VideoPlayerController(host.context());
                videoController.setPlaybackListener(videoPlaybackListener());
                videoController.setBufferingListener(videoBufferingListener());
                videoController.setSeekListener(videoSeekListener());
                if (videoSurface != null) {
                    videoController.attachHolder(videoSurface.getHolder());
                }
            }
            // Surface may still be settling after long download — open then force play-on-ready.
            videoController.open(playFile);
            videoController.play();
            startVideoProgressUpdates();
            updateVideoProgressUi(0);
            clearVideoStatusText();
            android.util.Log.i("SolarYouTube", "playing cached " + playFile.getName()
                    + " bytes=" + playFile.length());
        } catch (Exception e) {
            android.util.Log.e("SolarYouTube", "open cached failed", e);
            toastYouTubePlayError(e.getMessage());
            leaveYouTubePlayerOnError();
        }
    }

    /**
     * 2026-07-14 — IJK failed current URL; try download then lower quality.
     * 2026-07-19 — Progressive download before quality ladder (Y2/A5 stream TLS/IJK miss).
     * Layman: if live play breaks, download the file; if that fails, try a smaller stream.
     * Was: quality ladder only. Reversal: skip downloadYoutubeThenPlay block.
     */
    private void handleYoutubeIjkError() {
        if (!videoPlaybackYoutube) return;
        if (youtubeIjkFallbackPending) return;
        final String url = youtubeStreamUrl;
        final String vid = youtubeNowPlayingId != null ? youtubeNowPlayingId : "yt";
        final String q = youtubeStreamQuality != null ? youtubeStreamQuality : "360";
        // First miss: SolarHttp download → local MediaPlayer (bypasses IJK HTTPS).
        if (!youtubeTriedProgDownload && url != null && url.length() > 0) {
            youtubeIjkFallbackPending = true;
            final int gen = youtubeLoadGen;
            host.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    youtubeIjkFallbackPending = false;
                    downloadYoutubeThenPlay(url, vid, q, gen);
                }
            });
            return;
        }
        final String next = YouTubeClient.fallbackVideoQuality(youtubeStreamQuality);
        if (next != null && youtubeNowPlayingId != null && youtubeNowPlayingId.length() > 0) {
            youtubeIjkFallbackPending = true;
            final YouTubeVideo retry = new YouTubeVideo(youtubeNowPlayingId,
                    youtubeNowPlayingTitle != null ? youtubeNowPlayingTitle : "", "", "");
            releaseVideoPlayer();
            videoPlaybackYoutube = false;
            youtubeStreamUrl = null;
            youtubeTriedProgDownload = false;
            if (host.getCurrentScreenState() == STATE_VIDEO_PLAYER) {
                if (youtubeDetailVideo != null
                        && youtubeNowPlayingId.equals(youtubeDetailVideo.id)) {
                    host.changeScreen(STATE_YOUTUBE_DETAIL);
                } else {
                    host.changeScreen(STATE_YOUTUBE_BROWSE);
                }
            }
            playYouTubeVideoAtQuality(retry, next, true);
            return;
        }
        toastYouTubePlayError(null);
        leaveYouTubePlayerOnError();
    }

    /** 2026-07-14 — Exit video player after YouTube stream failure. */
    private void leaveYouTubePlayerOnError() {
        videoPlaybackYoutube = false;
        youtubeStreamUrl = null;
        youtubeResolvingStream = false;
        releaseVideoPlayer();
        endVideoForceLandscapeSession();
        if (youtubeDetailVideo != null
                && youtubeNowPlayingId != null
                && youtubeNowPlayingId.equals(youtubeDetailVideo.id)) {
            host.changeScreen(STATE_YOUTUBE_DETAIL);
        } else {
            host.changeScreen(STATE_YOUTUBE_BROWSE);
        }
    }

    private String getVideoResumeKey() {
        if (videoPlaybackYoutube && youtubeNowPlayingId != null && !youtubeNowPlayingId.isEmpty()) {
            return "resume_yt_" + youtubeNowPlayingId;
        } else if (!videoPlaybackYoutube && videoFiles != null && videoIndex >= 0 && videoIndex < videoFiles.size()) {
            return "resume_file_" + videoFiles.get(videoIndex).getAbsolutePath().hashCode();
        }
        return null;
    }

    private void releaseVideoPlayer() {
        if (videoController != null && videoController.isPrepared()) {
            String key = getVideoResumeKey();
            if (key != null) {
                long pos = videoController.getCurrentPosition();
                long dur = videoController.getDuration();
                if (dur > 0 && pos > dur - 5000) {
                    host.prefs().edit().remove(key).apply();
                } else if (pos > 5000) {
                    host.prefs().edit().putLong(key, pos).apply();
                }
            }
        }
        // 2026-07-19 — Abort progressive download when leaving so we do not open a stale file.
        youtubeProgCancel.set(true);
        stopVideoProgressUpdates();
        clearVideoScrubMode(false);
        clearVideoSeekState();
        if (videoController != null) {
            if (videoSurface != null) {
                videoController.detachHolder(videoSurface.getHolder());
            }
            videoController.release();
            videoController = null;
        }
        FrameLayout surfaceHost = host.findViewById(R.id.video_surface_host);
        if (surfaceHost != null) surfaceHost.removeAllViews();
        videoSurface = null;
    }

    public void toggleVideoPlayPause() {
        if (videoController != null) videoController.togglePlayPause();
    }

    public void onVideoPlaybackStopped() {
        stopVideoProgressUpdates();
        clearVideoScrubMode(false);
        clearVideoSeekState();
        videoBufferPercent = 0;
        host.setStatusBarVisible(true);
    }

    /**
     * 2026-07-15 — Short Prev/Next: flip file list, or ±5s seek when streaming (YouTube).
     * Was: empty videoFiles return only — YT short press was a silent no-op.
     * Reversal: restore early return when videoFiles.isEmpty().
     */
    public void seekVideoFile(boolean next) {
        if (videoFiles.isEmpty()) {
            // Stream-only session (YouTube): treat short side press like one scrub step.
            seekVideoMs(next ? 5000L : -5000L);
            return;
        }
        videoIndex = next ? videoIndex + 1 : videoIndex - 1;
        if (videoIndex < 0) videoIndex = videoFiles.size() - 1;
        if (videoIndex >= videoFiles.size()) videoIndex = 0;
        pulseVideoTransport();
        startVideoPlayback();
    }

    public void seekVideoMs(long deltaMs) {
        if (videoController == null || !videoController.isPrepared()) return;
        if (videoScrubActive) {
            moveVideoScrubCursor((int) deltaMs);
            return;
        }
        long base = videoPendingSeekMs >= 0L
                ? videoPendingSeekMs
                : videoController.getCurrentPosition();
        long dur = videoDurationMs();
        long pos = VideoSeekPolicy.steppedTarget(base, deltaMs, dur);
        seekVideoToTarget(pos);
        pulseVideoTransport();
    }

    // --- Photos ---

    private void buildPhotosUi() {
        prepareVirtualListBrowse();
        host.applyReachBrowseLayoutMode();
        host.showReachBrowseList(true);
        host.setBrowserStatusTitle(host.getString(R.string.status_photos));
        virtualLabels.clear();
        virtualLabels.add(host.getString(R.string.common_back_short));

        if (photoBrowseFolder == null) {
            photoFolders = PhotoLibrary.listFolders();
            if (photoFolders.isEmpty()) {
                virtualLabels.add(host.getString(R.string.photo_no_folders));
            } else {
                for (File f : photoFolders) virtualLabels.add(f.getName());
            }
        } else {
            virtualLabels.add(host.getString(R.string.photo_folder_header, photoBrowseFolder.getName()));
            photoFiles = PhotoLibrary.listImagesInFolder(photoBrowseFolder);
            if (photoFiles.isEmpty()) {
                virtualLabels.add(host.getString(R.string.photo_none_in_folder));
            } else {
                for (File f : photoFiles) virtualLabels.add(f.getName());
            }
        }

        bindVirtualAdapter(new VirtualClickHandler() {
            @Override
            public void onClick(int position) {
                if (position == 0) {
                    handleBack();
                    return;
                }
                if (photoBrowseFolder == null) {
                    int idx = position - 1;
                    if (idx >= 0 && idx < photoFolders.size()) {
                        photoBrowseFolder = photoFolders.get(idx);
                        buildPhotosUi();
                    }
                } else {
                    int idx = position - 2;
                    if (idx >= 0 && idx < photoFiles.size()) {
                        photoViewer.setFolder(photoFiles, idx);
                        host.changeScreen(STATE_PHOTO_VIEWER);
                    }
                }
            }
        });
    }

    private void showPhotoViewerLayer(boolean show) {
        FrameLayout layout = host.findViewById(R.id.layout_photo_viewer);
        if (layout != null) {
            layout.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (host.layoutBrowserMode() != null) {
            host.layoutBrowserMode().setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void hideVideoAndPhotoLayers() {
        showVideoPlayerLayer(false);
        showPhotoViewerLayer(false);
    }

    private void bindPhotoViewerImage() {
        final ImageView iv = host.findViewById(R.id.iv_photo_viewer);
        if (iv == null) return;
        final File file = photoViewer.currentFile();
        if (file == null) {
            iv.setImageBitmap(null);
            return;
        }
        final int gen = ++photoLoadGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = decodeSampled(file, host.getScreenWidthPx(), host.getScreenWidthPx());
                final Bitmap fBmp = bmp;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != photoLoadGen || host.getCurrentScreenState() != STATE_PHOTO_VIEWER) return;
                        iv.setImageBitmap(fBmp);
                    }
                });
            }
        }).start();
    }

    public void photoViewerNext() {
        if (!photoViewer.hasNext()) return;
        photoViewer.next();
        bindPhotoViewerImage();
    }

    public void photoViewerPrev() {
        if (!photoViewer.hasPrev()) return;
        photoViewer.prev();
        bindPhotoViewerImage();
    }

    public void setPhotoAsWallpaper() {
        File file = photoViewer.currentFile();
        if (file == null) return;
        if (PhotoWallpaperHelper.applyAsBackground(host.context(), file, host.prefs())) {
            Toast.makeText(host.context(), R.string.toast_bg_applied, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(host.context(), R.string.photo_wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    public String photoViewerPositionLabel() {
        return String.format(Locale.US, "%d / %d",
                photoViewer.getIndex() + 1, Math.max(1, photoViewer.getCount()));
    }

    // --- Settings rows ---

    public List<SettingsRow> buildFmSettingsRows() {
        List<SettingsRow> rows = new ArrayList<SettingsRow>();
        rows.add(new SettingsRow(SettingsScreens.RADIO_FM_BAND, R.string.radio_settings_fm_band, true));
        rows.add(new SettingsRow(ROW_AUTO_DETECT, R.string.radio_settings_auto_region, false));
        return rows;
    }

    public List<SettingsRow> buildRadioSettingsRows() {
        List<SettingsRow> rows = new ArrayList<SettingsRow>(buildFmSettingsRows());
        rows.add(new SettingsRow(SettingsScreens.RADIO_INTERNET_COUNTRY,
                R.string.radio_settings_internet_country, true));
        rows.add(new SettingsRow(ROW_BUFFER_SD, R.string.radio_settings_buffer_sd, false));
        return rows;
    }

    public List<SettingsRow> buildVideoSettingsRows() {
        List<SettingsRow> rows = new ArrayList<SettingsRow>();
        rows.add(new SettingsRow(ROW_VIDEO_SLEEP, R.string.video_settings_sleep_during_playback, false));
        // 2026-07-15 — Letterbox (default) vs crop-to-fill for 4:3 panels.
        rows.add(new SettingsRow(ROW_VIDEO_CROP, R.string.video_settings_crop_mode, false));
        return rows;
    }

    public boolean toggleSleepDuringPlayback() {
        Context ctx = host.context();
        boolean next = !com.solar.launcher.video.VideoSettings.getSleepDuringPlayback(ctx);
        com.solar.launcher.video.VideoSettings.setSleepDuringPlayback(ctx, next);
        return next;
    }

    public boolean isSleepDuringPlaybackEnabled() {
        return com.solar.launcher.video.VideoSettings.getSleepDuringPlayback(host.context());
    }

    /** 2026-07-15 — Cycle letterbox ↔ crop 4:3; returns new mode key. */
    public String cycleVideoCropMode() {
        return com.solar.launcher.video.VideoSettings.cycleCropMode(host.context());
    }

    /** Short label for settings state column. */
    public String videoCropModeLabel() {
        return com.solar.launcher.video.VideoSettings.cropModeLabel(host.context());
    }

    public List<SettingsRow> buildFmBandSettingsRows() {
        List<SettingsRow> rows = new ArrayList<SettingsRow>();
        for (String region : FM_BAND_REGIONS) {
            rows.add(new SettingsRow("radio.fm_band." + region, labelResForRegion(region), false));
        }
        return rows;
    }

    /**
     * User picked a band in Settings — remember it and stop auto-detect.
     * 2026-07-15 — Clearing auto-detect keeps manual choice when getFmBandRegion honors locale.
     * Reversal: set region only (old); auto-detect stayed on and overwrote dial limits.
     */
    public void applyFmBandRegion(String region) {
        RadioSettings.setAutoDetectRegion(host.context(), false);
        RadioSettings.setFmBandRegion(host.context(), region);
        radioTuneFreqKhz = currentFmPlan().clampKhz(radioTuneFreqKhz);
    }

    public void applyInternetCountry(String isoCode) {
        RadioSettings.setInternetRadioCountry(host.context(), isoCode);
        netCountryCode = isoCode;
    }

    public boolean toggleAutoDetectRegion() {
        Context ctx = host.context();
        boolean next = !RadioSettings.getAutoDetectRegion(ctx);
        RadioSettings.setAutoDetectRegion(ctx, next);
        if (next) {
            // Cache detected band without clearing auto-detect (applyFmBandRegion turns it off).
            RadioSettings.setFmBandRegion(ctx, RadioSettings.detectFmBandFromLocale(ctx));
            radioTuneFreqKhz = currentFmPlan().clampKhz(radioTuneFreqKhz);
        }
        return next;
    }

    public boolean toggleBufferOnSd() {
        Context ctx = host.context();
        boolean next = !RadioSettings.getBufferOnSd(ctx);
        RadioSettings.setBufferOnSd(ctx, next);
        return next;
    }

    public String fmBandRegionLabel() {
        return labelForRegion(RadioSettings.getFmBandRegion(host.context()));
    }

    public String internetCountryLabel() {
        return RadioSettings.getInternetRadioCountry(host.context());
    }

    public boolean isAutoDetectRegionEnabled() {
        return RadioSettings.getAutoDetectRegion(host.context());
    }

    public boolean isBufferOnSdEnabled() {
        return RadioSettings.getBufferOnSd(host.context());
    }

    public void release() {
        netLoadGen++;
        stopFmRdsPolling();
        fmEngine.release();
        internetRadioPlayer.stop();
        releaseVideoPlayer();
    }

    // --- Browser chrome helpers ---

    private void prepareScrollBrowse() {
        host.resetBrowserListHost();
        host.showVirtualSongList(false);
        View scroll = host.findViewById(R.id.scroll_view_browser);
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
    }

    private void prepareVirtualListBrowse() {
        host.resetBrowserListHost();
        host.showVirtualSongList(true);
        View scroll = host.findViewById(R.id.scroll_view_browser);
        if (scroll != null) scroll.setVisibility(View.GONE);
    }

    private void clearVirtualList() {
        host.listVirtualSongs().setVisibility(View.GONE);
        host.listVirtualSongs().setAdapter(null);
        host.containerBrowserItems().removeAllViews();
        virtualLabels.clear();
        virtualSubtitles.clear();
        youtubeBrowseRows.clear();
    }

    private void addBackRow(String label) {
        Button back = host.createListButton(label);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.clickFeedback();
                handleBack();
            }
        });
        host.containerBrowserItems().addView(back);
    }

    private void addActionRow(String label, final Runnable action) {
        Button row = host.createListButton(label);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.clickFeedback();
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("label", label);
                    d.put("screen", host.getCurrentScreenState());
                    DebugAgentLog.log(host.context(), "MediaSuiteHost.actionRow",
                            "scroll row click", "H-D", d);
                } catch (Exception ignored) {}
                // #endregion
                action.run();
            }
        });
        host.containerBrowserItems().addView(row);
    }

    private void addStatusRow(String text) {
        addStatusButton(text);
    }

    private Button addStatusButton(String text) {
        Button row = host.createListButton(text);
        row.setEnabled(false);
        row.setFocusable(false);
        host.containerBrowserItems().addView(row);
        return row;
    }

    private void focusFirstBrowserChild() {
        if (host.containerBrowserItems().getChildCount() > 0) {
            host.containerBrowserItems().getChildAt(0).requestFocus();
        }
    }

    private interface VirtualClickHandler {
        void onClick(int position);
    }

    private void bindVirtualAdapter(final VirtualClickHandler handler) {
        final int rowW = host.messagingRowWidthPx();
        virtualAdapter = new SimpleListAdapter(rowW, handler);
        host.listVirtualSongs().setAdapter(virtualAdapter);
        host.listVirtualSongs().post(new Runnable() {
            @Override
            public void run() {
                if (host.listVirtualSongs().getChildCount() > 0) {
                    host.listVirtualSongs().getChildAt(0).requestFocus();
                } else {
                    FocusScrollHelper.focusListPosition(host.listVirtualSongs(), 0);
                }
            }
        });
    }

    /**
     * 2026-07-15 — Refresh virtual list without yanking focus to row 0 (search streaming).
     * Layman: keep the blue bar on the row you were on while results fill in.
     * Only re-focus when selection was lost after notify (never fight live DPAD/wheel).
     */
    private void notifyVirtualDataChangedPreserveFocus() {
        final ListView lv = host.listVirtualSongs();
        if (lv == null || virtualAdapter == null) return;
        int pos = lv.getSelectedItemPosition();
        if (pos < 0) {
            View foc = host.activity() != null ? host.activity().getCurrentFocus() : null;
            if (foc != null) {
                for (int i = 0; i < lv.getChildCount(); i++) {
                    View child = lv.getChildAt(i);
                    if (child == foc || isDescendant(child, foc)) {
                        pos = lv.getFirstVisiblePosition() + i;
                        break;
                    }
                }
            }
        }
        final int restore = pos;
        virtualAdapter.notifyDataSetChanged();
        if (restore < 0) return;
        lv.post(new Runnable() {
            @Override
            public void run() {
                if (virtualAdapter == null) return;
                int count = virtualAdapter.getCount();
                if (count <= 0) return;
                // If focus already sits on a list child, leave A5 face/wheel alone.
                View foc = host.activity() != null ? host.activity().getCurrentFocus() : null;
                if (foc != null) {
                    for (int i = 0; i < lv.getChildCount(); i++) {
                        if (isDescendant(lv.getChildAt(i), foc) || lv.getChildAt(i) == foc) {
                            return;
                        }
                    }
                }
                int selected = lv.getSelectedItemPosition();
                if (selected >= 0 && selected < count) return;
                int target = restore >= count ? count - 1 : restore;
                FocusScrollHelper.focusListPosition(lv, target);
            }
        });
    }

    private static boolean isDescendant(View root, View child) {
        if (root == null || child == null) return false;
        View p = child;
        while (p != null) {
            if (p == root) return true;
            android.view.ViewParent vp = p.getParent();
            p = vp instanceof View ? (View) vp : null;
        }
        return false;
    }

    /** True while YouTube browse is fetching search/popular results. */
    public boolean isYoutubeBrowseLoading() {
        int st = host.getCurrentScreenState();
        return (st == STATE_YOUTUBE_BROWSE || st == STATE_YOUTUBE_DETAIL) && youtubeLoading;
    }

    /**
     * 2026-07-18 — Host asks if video/YouTube work should keep the status spinner up.
     * Layman: resolving a stream still counts as loading.
     * Technical: youtubeResolvingStream / non-empty resolve status (video buffer uses UiBusy).
     */
    public boolean isMediaBusyForStatusThrobber() {
        if (youtubeResolvingStream) return true;
        if (youtubeResolveStatus != null && youtubeResolveStatus.length() > 0) return true;
        return false;
    }

    private final class SimpleListAdapter extends BaseAdapter {
        private final int rowWidth;
        private final VirtualClickHandler handler;

        SimpleListAdapter(int rowWidth, VirtualClickHandler handler) {
            this.rowWidth = rowWidth;
            this.handler = handler;
        }

        @Override
        public int getCount() {
            return virtualLabels.size();
        }

        @Override
        public Object getItem(int position) {
            return virtualLabels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final String title = virtualLabels.get(position);
            final String subtitle = position < virtualSubtitles.size()
                    ? virtualSubtitles.get(position) : "";
            // 2026-07-20 — Status-only rows from the *current* screen’s kind list (not stale browse).
            final boolean statusRow = isYoutubeStatusOnlyRow(position);
            // 2026-07-20 — Busy title rows: browse loading / comments / Play while resolving.
            // Layman: little spinner next to the busy line. Technical: RowBusyChrome; UiBusy stays status.
            // Reversal: ignore rowBusy; Button/two-line only.
            final boolean rowBusy = isYoutubeTitleRowBusy(position);

            if (subtitle != null && subtitle.length() > 0) {
                android.widget.LinearLayout row;
                android.widget.TextView tvTitle;
                android.widget.TextView tvSub;
                ProgressBar spin = null;
                if (convertView instanceof android.widget.LinearLayout
                        && "solar_two_line_busy_row".equals(convertView.getTag())
                        && ((android.widget.LinearLayout) convertView).getChildCount() >= 2) {
                    row = (android.widget.LinearLayout) convertView;
                    android.widget.LinearLayout titleLine =
                            (android.widget.LinearLayout) row.getChildAt(0);
                    tvTitle = (android.widget.TextView) titleLine.getChildAt(0);
                    spin = (ProgressBar) titleLine.findViewWithTag(RowBusyChrome.TAG_SPIN);
                    tvSub = (android.widget.TextView) row.getChildAt(1);
                    tvTitle.setText(title);
                    tvSub.setText(subtitle);
                } else if (convertView instanceof android.widget.LinearLayout
                        && "solar_two_line_row".equals(convertView.getTag())
                        && ((android.widget.LinearLayout) convertView).getChildCount() >= 2
                        && !rowBusy) {
                    row = (android.widget.LinearLayout) convertView;
                    tvTitle = (android.widget.TextView) row.getChildAt(0);
                    tvSub = (android.widget.TextView) row.getChildAt(1);
                    tvTitle.setText(title);
                    tvSub.setText(subtitle);
                } else {
                    // 2026-07-15 — Guard cast: createTwoLineBrowseRow must be LinearLayout
                    // (used to return Button when full_width_menus off → crash).
                    View created = host.createTwoLineBrowseRow(title, subtitle);
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("createdClass", created != null
                                ? created.getClass().getName() : "null");
                        d.put("isLinear", created instanceof android.widget.LinearLayout);
                        com.solar.launcher.Debug9d82a5Log.log(host.context(),
                                "SimpleListAdapter.getView", "two-line create", "F", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    if (!(created instanceof android.widget.LinearLayout)
                            || ((android.widget.LinearLayout) created).getChildCount() < 2) {
                        // Safe degrade: single Button with title (never ClassCast).
                        Button btn = host.createListButton(title);
                        btn.setLayoutParams(new ListView.LayoutParams(
                                rowWidth, host.y1RowHeightPx()));
                        btn.setEnabled(!statusRow);
                        if (!statusRow) {
                            btn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    host.clickFeedback();
                                    handler.onClick(position);
                                }
                            });
                        }
                        if (rowBusy) {
                            return RowBusyChrome.wrapTitleWithSpinner(
                                    host.context(), btn, true);
                        }
                        return btn;
                    }
                    row = (android.widget.LinearLayout) created;
                    tvTitle = (android.widget.TextView) row.getChildAt(0);
                    tvSub = (android.widget.TextView) row.getChildAt(1);
                    if (rowBusy) {
                        // Promote to title+spinner | subtitle so Play shows inline busy.
                        row.removeView(tvTitle);
                        android.widget.LinearLayout titleLine =
                                RowBusyChrome.wrapTitleWithSpinner(
                                        host.context(), tvTitle, true);
                        row.addView(titleLine, 0);
                        row.setTag("solar_two_line_busy_row");
                        spin = (ProgressBar) titleLine.findViewWithTag(RowBusyChrome.TAG_SPIN);
                    } else {
                        row.setTag("solar_two_line_row");
                    }
                }
                if (spin != null) {
                    spin.setVisibility(rowBusy ? View.VISIBLE : View.GONE);
                }
                // 2026-07-14 — A5 two-tap on two-line YouTube/browse rows (was raw one-tap).
                if (statusRow) {
                    row.setOnClickListener(null);
                    row.setFocusable(false);
                    row.setEnabled(false);
                } else {
                    com.solar.launcher.A5FocusConfirm.setOnClickListener(row, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            host.clickFeedback();
                            handler.onClick(position);
                        }
                    });
                    row.setFocusable(true);
                    row.setEnabled(true);
                    attachFmPresetTouchReorder(row, position);
                }
                return row;
            }

            // Single-line status / action — wrap loading titles with RowBusyChrome.
            if (rowBusy) {
                if (convertView instanceof LinearLayout
                        && convertView.findViewWithTag(RowBusyChrome.TAG_SPIN) != null
                        && ((LinearLayout) convertView).getChildCount() >= 1
                        && ((LinearLayout) convertView).getChildAt(0) instanceof Button) {
                    LinearLayout wrapped = (LinearLayout) convertView;
                    Button btn = (Button) wrapped.getChildAt(0);
                    btn.setText(title);
                    btn.setEnabled(!statusRow);
                    ProgressBar spin = (ProgressBar) wrapped.findViewWithTag(RowBusyChrome.TAG_SPIN);
                    if (spin != null) spin.setVisibility(View.VISIBLE);
                    return wrapped;
                }
                Button btn = host.createListButton(title);
                btn.setLayoutParams(new ListView.LayoutParams(rowWidth, host.y1RowHeightPx()));
                btn.setEnabled(!statusRow);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (statusRow) return;
                        host.clickFeedback();
                        handler.onClick(position);
                    }
                });
                if (!statusRow) attachFmPresetTouchReorder(btn, position);
                return RowBusyChrome.wrapTitleWithSpinner(host.context(), btn, true);
            }

            Button btn;
            if (convertView instanceof Button) {
                btn = (Button) convertView;
            } else {
                btn = host.createListButton("");
                btn.setLayoutParams(new ListView.LayoutParams(rowWidth, host.y1RowHeightPx()));
            }
            btn.setText(title);
            btn.setEnabled(!statusRow);
            // createListButton already wraps; keep assign for recycle rebinds.
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (statusRow) return;
                    host.clickFeedback();
                    handler.onClick(position);
                }
            });
            if (!statusRow) attachFmPresetTouchReorder(btn, position);
            return btn;
        }
    }

    /**
     * 2026-07-20 — Non-clickable status / header chrome for the active YouTube screen.
     * Layman: “Loading…” and section headers are not OK targets.
     * Was: only youtubeBrowseRows KIND_STATUS (wrong on detail). Reversal: browse-only check.
     */
    private boolean isYoutubeStatusOnlyRow(int position) {
        int st = host.getCurrentScreenState();
        if (st == STATE_YOUTUBE_BROWSE) {
            if (position < 0 || position >= youtubeBrowseRows.size()) return false;
            int k = youtubeBrowseRows.get(position).kind;
            return k == YoutubeBrowseRow.KIND_STATUS;
        }
        if (st == STATE_YOUTUBE_DETAIL) {
            if (position < 0 || position >= youtubeDetailRows.size()) return false;
            int k = youtubeDetailRows.get(position).kind;
            return k == YoutubeDetailRow.KIND_STATUS || k == YoutubeDetailRow.KIND_HEADER;
        }
        return false;
    }

    /**
     * 2026-07-20 — True when this virtual row should show an inline busy spinner.
     * Layman: loading / resolving lines get a little wheel next to the title.
     * Technical: browse KIND_STATUS while loading, detail Play while resolving, comments load.
     */
    private boolean isYoutubeTitleRowBusy(int position) {
        int st = host.getCurrentScreenState();
        if (st == STATE_YOUTUBE_BROWSE) {
            if (!youtubeLoading) return false;
            if (position < 0 || position >= youtubeBrowseRows.size()) return false;
            return youtubeBrowseRows.get(position).kind == YoutubeBrowseRow.KIND_STATUS;
        }
        if (st == STATE_YOUTUBE_DETAIL) {
            if (position < 0 || position >= youtubeDetailRows.size()) return false;
            YoutubeDetailRow row = youtubeDetailRows.get(position);
            if (row.kind == YoutubeDetailRow.KIND_PLAY && youtubeResolvingStream) return true;
            if (row.kind == YoutubeDetailRow.KIND_STATUS && youtubeCommentsLoading) return true;
            return false;
        }
        return false;
    }

    /**
     * 2026-07-15 — Touch long-press / drag for FM preset reorder (OK-hold unchanged).
     * Reversal: no-op method body.
     */
    private void attachFmPresetTouchReorder(final View row, final int virtualPosition) {
        if (row == null || !isFmPresetListActive() || virtualPosition <= 0) return;
        if (!MoveRibbonTouch.touchReorderEnabled()) return;
        final int dataIdx = fmPresetDataIndexFromVirtualPosition(virtualPosition);
        if (dataIdx < 0) return;
        if (fmPresetMoveFrom >= 0 && dataIdx == fmPresetMoveFrom) {
            MoveRibbonTouch.attachActiveDrag(row, host.y1RowHeightPx() + 2,
                    new MoveRibbonTouch.Callbacks() {
                        @Override
                        public void onLift() {}

                        @Override
                        public void onStep(int delta) {
                            handleFmPresetMoveWheel(delta);
                        }

                        @Override
                        public void onConfirm() {
                            confirmFmPresetMove();
                        }
                    });
            return;
        }
        if (fmPresetMoveFrom >= 0) return;
        MoveRibbonTouch.attachBrowseLift(row, MoveRibbonTouch.LIFT_HOLD_MS,
                new MoveRibbonTouch.Callbacks() {
                    @Override
                    public void onLift() {
                        beginFmPresetMove(dataIdx);
                        host.clickFeedback();
                    }

                    @Override
                    public void onStep(int delta) {}

                    @Override
                    public void onConfirm() {}
                });
    }

    // --- Utility ---

    private String lastFmBandDebugKey = "";

    private FmBandPlan currentFmPlan() {
        // #region agent log
        try {
            android.content.Context c = host.context();
            boolean auto = RadioSettings.getAutoDetectRegion(c);
            String effective = RadioSettings.getFmBandRegion(c);
            String detected = RadioSettings.detectFmBandFromLocale(c);
            String key = auto + "|" + effective + "|" + detected;
            if (!key.equals(lastFmBandDebugKey)) {
                lastFmBandDebugKey = key;
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("auto", auto);
                d.put("effective", effective);
                d.put("detected", detected);
                com.solar.launcher.debug.SessionDebugLog.log(c, "MediaSuiteHost.currentFmPlan",
                        "band resolve", "F2", d);
            }
        } catch (Exception ignored) {}
        // #endregion
        return FmBandPlan.fromRegionCode(RadioSettings.getFmBandRegion(host.context()));
    }

    /**
     * Band default when nothing is remembered — last dial if any, else 101.1 MHz.
     * 2026-07-15 — Was always 101.1. Reversal: hardcode 101.1 again.
     */
    private int defaultFmKhz() {
        FmBandPlan plan = currentFmPlan();
        int last = RadioSettings.getLastFmKhz(host.context());
        if (last > 0) return plan.clampKhz(last);
        return plan.clampKhz(Math.round(101.1f * 1000f));
    }

    private int currentFmFreqKhz() {
        PlayQueue.QueueItem cur = host.playback().unifiedQueue().current();
        if (cur != null && cur.fmFreqKhz > 0) return cur.fmFreqKhz;
        if (radioTuneFreqKhz > 0) return radioTuneFreqKhz;
        return defaultFmKhz();
    }

    private static int presetIndexForFreq(List<FmPresetStore.Preset> presets, int khz) {
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).freqKhz == khz) return i;
        }
        return 0;
    }

    private static int labelResForRegion(String region) {
        if ("EU".equals(region)) return R.string.radio_band_eu;
        if ("JP".equals(region)) return R.string.radio_band_jp;
        if ("AU".equals(region)) return R.string.radio_band_au;
        if ("KR".equals(region)) return R.string.radio_band_kr;
        if ("RU".equals(region)) return R.string.radio_band_ru;
        return R.string.radio_band_us;
    }

    private static String labelForRegion(String region) {
        if (region == null) return "US";
        return region.toUpperCase(Locale.US);
    }

    private static Bitmap decodeSampled(File file, int reqWidth, int reqHeight) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
