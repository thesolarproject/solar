package com.solar.launcher;

import com.solar.launcher.soulseek.ReachCache;
import com.solar.launcher.soulseek.StreamTempCache;
import com.solar.launcher.soulseek.SoulseekAccount;
import com.solar.launcher.soulseek.SoulseekClient;
import com.solar.launcher.soulseek.SoulseekSearchHistory;
import com.solar.launcher.soulseek.SoulseekShareIndex;
import com.solar.launcher.soulseek.SoulseekSharePolicy;
import com.solar.launcher.soulseek.SoulseekSearchSuggestions;
import com.solar.launcher.theme.ThemeBrowser;
import com.solar.launcher.theme.ThemeDownloader;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.podcast.OpenRssClient;
import com.solar.launcher.podcast.PodcastCatalog;
import com.solar.launcher.podcast.PodcastLibrary;
import com.solar.launcher.podcast.PodcastResumeStore;

import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;

public class MainActivity extends Activity {
    private static final String OTA_UPDATES_URL = BuildConfig.OTA_UPDATES_URL;
    // 💡 [추가] 퀵 스크롤 (알파벳 인덱스) 관련 변수들
    private TextView tvFastScrollLetter;
    private Handler fastScrollHandler = new Handler();
    private Runnable hideFastScrollTask = new Runnable() {
        @Override
        public void run() {
            if (tvFastScrollLetter != null) {
                tvFastScrollLetter.setVisibility(View.GONE);
            }
        }
    };

    // 💡 [추가] 홈 스크린 위젯 관련 변수들
    private boolean isWidgetClockOn = false;
    private boolean isWidgetBatteryOn = false;
    private boolean isWidgetAlbumOn = false;

    private LinearLayout layoutWidgets;
    private TextView tvWidgetClock;
    // 🚀 [수정] 가로형 바(Bar) 클래스로 이름 변경!
    private WidgetBatteryBarView widgetBatteryView;
    private ImageView ivWidgetAlbum;

    // 🚀 [추가] 앨범 위젯 전용 제목/가수 변수
    private TextView tvWidgetAlbumTitle;
    private TextView tvWidgetAlbumArtist;
    // 💡 [추가] 고속 인덱스 점프(알파벳 스크롤) 전용 변수들
    private List<String> currentScrollIndexList = new ArrayList<>();
    private long lastWheelTime = 0;
    private int wheelFastCount = 0;
    public static MainActivity instance;
    private long lastTrackChangeTime = 0; // 🚀 기기의 중복 키 신호를 막아줄 방어막 변수
    // 💡 [추가] 오디오 스펙트럼 관련 변수들
    private android.media.audiofx.Visualizer audioVisualizer;
    private AudioVisualizerView visualizerView;
    private boolean isVisualizerShowing = false;
    private int currentAlbumColor = 0xFFFFFFFF; // 스펙트럼 바의 색상
    private static final int STATE_MENU = 1;
    static final int STATE_BROWSER = 2;
    static final int STATE_PLAYER = 3;
    static final int STATE_SETTINGS = 4;
    static final int STATE_BLUETOOTH = 5;
    static final int STATE_WIFI = 6;
    static final int STATE_WIFI_KEYBOARD = 7;
    static final int STATE_BRIGHTNESS = 8;
    static final int STATE_STORAGE = 9;
    static final int STATE_WEBSERVER = 10;
    static final int STATE_PODCASTS = 11;
    static final int STATE_SOULSEEK = 12;
    static final int STATE_APPS = 13;
    static final int STATE_MORE = 14;
    private static final int KEYBOARD_WIFI = 0;
    private static final int KEYBOARD_SOULSEEK_USER = 1;
    private static final int KEYBOARD_SOULSEEK_PASS = 2;
    private static final int KEYBOARD_SOULSEEK_SEARCH = 3;
    private static final int KEYBOARD_PODCAST_SEARCH = 4;
    /** ponytail: stock Y1 row art — home=itemConfig, settings/menu lists=menuConfig, file lists=itemConfig */
    private static final int Y1_ROW_HOME = 0;
    private static final int Y1_ROW_MENU = 1;
    private static final int Y1_ROW_ITEM = 2;
    // 💡 미디어 라이브러리 브라우저 상태 관리 변수들
    private static final int BROWSER_ROOT = 0;
    private static final int BROWSER_FOLDER = 1;
    private static final int BROWSER_ARTISTS = 2;
    private static final int BROWSER_ALBUMS = 3;
    private static final int BROWSER_VIRTUAL_SONGS = 4;
    private static final int BROWSER_ARTIST_ALBUMS = 5;
    private static final int BROWSER_GENRES = 6;
    private static final int BROWSER_PLAYLISTS = 7;
    // 💡 [추가] 손상되어 앱을 터뜨린 '독약 파일'들을 기억하는 블랙리스트
    private java.util.Set<String> blacklist = new java.util.HashSet<>();
    private int currentBrowserMode = BROWSER_ROOT;
    private String virtualQueryType = "";
    private String virtualQueryValue = "";
    private String virtualQueryArtist = "";
    private static final int LIB_SORT_TITLE = 0;
    private static final int LIB_SORT_ARTIST = 1;
    private static final int LIB_SORT_ALBUM = 2;
    private static final int LIB_SORT_DATE = 3;
    private int librarySortMode = LIB_SORT_TITLE;
    private int appsListGen = 0;
    private long suppressListClickUntil = 0;
    private List<PlaylistManager.Entry> libraryPlaylists = new ArrayList<PlaylistManager.Entry>();
    private List<File> virtualSongList = new ArrayList<>();
    // 💡 백그라운드 미디어 제어권(스크린 오프) 변수
    private Object mediaSessionShim;
    // 💡 [추가] OS 스캐너를 대체할 '자체 미디어 라이브러리 엔진' 변수들
    private static class SongItem {
        File file;
        String title;
        String artist;
        String album;
        String genre;

        public SongItem(File f, String t, String a, String al, String g) {
            file = f;
            title = t;
            artist = a;
            album = al;
            genre = g != null && g.trim().length() > 0 ? g.trim() : "Unknown Genre";
        }
    }
    // 💡 [초고속 엔진] 수천 곡을 버티기 위한 재활용 리스트뷰와 기존 스크롤뷰
    private android.widget.ListView listVirtualSongs;
    private android.widget.ListView listMusicQueue;
    private View settingsScrollView;
    private MusicQueueListAdapter musicQueueListAdapter;
    private android.widget.ListView listThemes;
    private ThemeUnifiedListAdapter themeUnifiedListAdapter;
    private final List<ThemeBrowser.Row> themeBrowserRows = new ArrayList<ThemeBrowser.Row>();
    private int themeFilterMode = ThemeBrowser.FILTER_ALL;
    private int themeSortMode = ThemeBrowser.SORT_NAME;
    private static final int THEME_BROWSER_MAIN = 0;
    private static final int THEME_BROWSER_VARIANT = 1;
    private static final int THEME_BROWSER_GET_MORE = 2;
    private int themeBrowserMode = THEME_BROWSER_MAIN;
    private int themeBrowserParentMode = THEME_BROWSER_MAIN;
    private ThemeDownloader.CatalogEntry themeVariantEntry;
    private List<ThemeDownloader.ThemeVariant> themeVariantList;
    private boolean themeCatalogLoading;
    private boolean themeCatalogAvailable;
    private String themeCatalogError;
    private final List<ThemeBrowser.Row> themeBrowserOnlineRows = new ArrayList<ThemeBrowser.Row>();
    private int themeBrowserFocus = 1;
    private final List<FolderBrowserEntry> folderBrowserEntries = new ArrayList<FolderBrowserEntry>();
    private View scrollViewBrowser;
    private boolean isScreenOffControlEnabled = false;
    private boolean isAutoFetchEnabled = true; // 🚀 [추가] 인터넷 자동 검색 스위치 기본값
    private static List<SongItem> customLibrary = new ArrayList<>();
    private boolean isCustomScanning = false;
    private int currentScreenState = STATE_MENU;
    // 💡 자체 날짜/시간 설정용 임시 변수
    private int dtYear = 2026, dtMonth = 1, dtDay = 1, dtHour = 12, dtMinute = 0;
    private View layoutMainMenu, layoutBrowserMode;
    private FrameLayout layoutSettingsMode;
    private View layoutBluetoothMode, layoutWifiMode, layoutWifiKeyboard;
    private View layoutPlayerMode, layoutVolumeOverlay;
    private View layoutBrightnessMode, layoutStorageMode, layoutWebServerMode;

    private LinearLayout containerBrowserItems, containerSettingsItems;
    private FrameLayout browserListHost;
    private LinearLayout podcastPreviewPane;
    private ImageView ivPodcastPreviewArt;
    private TextView tvPodcastPreviewShow, tvPodcastPreviewEpisode, tvPodcastPreviewMeta;
    private int podcastPreviewArtGen = 0;
    private final java.util.HashMap<String, Integer> podcastSavedDurationCache = new java.util.HashMap<String, Integer>();
    private LinearLayout containerBtItems, containerWifiItems;

    private TextView tvStatusClock, tvStatusBattery;
    private ImageView ivStatusBluetooth, ivStatusWifi, ivStatusHeadphone, ivStatusPlayback, ivMainBg, ivScreenMask;
    private TextView tvSettingsPreviewTitle, tvSettingsPreviewState;
    private ImageView ivSettingsPreviewIcon;
    private StoragePieView storagePieView;
    private LinearLayout playerVisualizerContainer, playerContentRow;
    private FrameLayout playerVisualizerSlot;
    private TextView tvVizTitle, tvVizArtist, tvVizTrackCount;

    private TextView tvBrowserPath, tvPlayerTitle, tvPlayerArtist, tvPlayerTimeCurrent, tvPlayerTimeTotal;
    private TextView tvPlayerTrackCount;
    private LinearLayout playerStatusRow;
    private ImageView ivPlayerShuffleStatus, ivPlayerRepeatStatus; // 💡 텍스트뷰에서 이미지뷰로 변경!
    private ProgressBar playerProgress, volumeProgress, pbBrightness, pbStorage;
    private TextView tvBrightnessVal, tvStorageDetails;

    private TextView tvServerStatus, tvServerIp, tvWebserverHint;
    private TextView tvKeyboardHint, tvBrightnessHint, tvStorageHint;
    private Button btnServerToggle;
    // 🚀 [추가] 화면 전체를 덮는 고급 로딩 인디케이터 오버레이
    private LinearLayout layoutLoadingOverlay;
    private TextView tvLoadingOverlayText;
    private ImageView ivMenuPreview, ivAlbumArt, ivPlayerBgBlur, ivPauseOverlay;

    private FrameLayout menuListHost;
    private android.widget.ScrollView menuScroll;
    private LinearLayout containerHomeMenuItems;
    private List<HomeMenuConfig.Entry> homeMenuEntries = new ArrayList<HomeMenuConfig.Entry>();
    private static final int HOME_MENU_TAG_LABEL = 0x7f0a0001;
    private static final int HOME_MENU_TAG_ARROW = 0x7f0a0002;
    private int focusedHomeMenuIndex = 0;
    private int homeScreenEditorMenuFocusIndex = 11;
    private int homeScreenEditorFocusIndex = 1;
    private int homeScreenOrderFocusIndex = 1;
    private String homeScreenMoveModeId = null;
    private String homeMoreMoveModeId = null;
    private int musicQueueEditorFocus = 1;
    private int musicQueueMoveFrom = -1;
    private int musicQueueReturnScreen = STATE_PLAYER;
    private static final int PODCAST_UI_SEARCH = 0;
    private static final int PODCAST_UI_SHOWS = 1;
    private static final int PODCAST_UI_EPISODES = 2;
    private static final int PODCAST_UI_SAVED = 3;
    private static final int PODCAST_UI_BROWSE_GENRE = 4;
    private static final int PODCAST_UI_BROWSE_COUNTRY = 5;
    private static final int PODCAST_UI_STOREFRONT = 6;
    private static final String PREF_PODCAST_STOREFRONT = "podcast_storefront";
    private static final int PODCAST_UI_RESTORE_NONE = -1;
    private int playerReturnScreen = STATE_MENU;
    private int playerReturnPodcastUiMode = PODCAST_UI_SEARCH;
    private int soulseekReturnPodcastUiMode = PODCAST_UI_SEARCH;
    /** When >= 0, next {@link #changeScreen} to podcasts restores this sub-screen instead of search root. */
    private int podcastUiModeOnReturn = PODCAST_UI_RESTORE_NONE;
    private int podcastUiMode = PODCAST_UI_SEARCH;
    private String podcastLastQuery = "technology";
    private String podcastStorefront = "US";
    private OpenRssClient.Podcast podcastSelected;
    private List<OpenRssClient.Podcast> podcastShows = new ArrayList<>();
    private List<OpenRssClient.Episode> podcastEpisodes = new ArrayList<>();
    private Button podcastProbeStatusRow = null;
    private int[] podcastEpisodeProbeState;
    private int podcastEpisodeFlushPtr = 0;
    private List<OpenRssClient.Episode> podcastEpisodeProbeSource;
    private String podcastSavedShowFolder = "";
    private int podcastUiGen = 0;
    private int themeDownloadGen = 0;
    private int libraryScanGen = 0;
    private int activeLibraryScanGen = 0;
    private final PlaybackCoordinator playback = new PlaybackCoordinator();
    private int podcastLoadGeneration = 0;
    private boolean podcastEpisodeLoading = false;
    private volatile boolean podcastPartialPlaybackStarted = false;
    private java.util.concurrent.atomic.AtomicBoolean podcastDownloadCancel =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private File podcastGrowingCacheFile = null;
    private File podcastGrowingCacheFinal = null;
    private volatile boolean podcastDownloadInProgress = false;
    private volatile boolean podcastDownloadPaused = false;
    private volatile long podcastDownloadBytesRead = 0;
    private volatile long podcastDownloadBytesTotal = 0;
    private long podcastGrowingPreparedBytes = 0;
    private int lastPodcastPlayPositionMs = 0;
    private int podcastGrowingSeekMs = 0;
    private volatile boolean podcastGrowingReprepareInFlight = false;
    private static final long PODCAST_GROWING_EXTEND_MS = 1800;
    private static final long PODCAST_GROWING_MIN_GROW_BYTES = 4096;
    private final Runnable podcastGrowingEdgePoll = new Runnable() {
        @Override
        public void run() {
            maybeExtendPodcastGrowingPlayback(playback.podcastIndex(), podcastLoadGeneration, false);
        }
    };
    private static final int SOULSEEK_UI_SEARCH = 0;
    private static final int SOULSEEK_UI_RESULTS = 1;
    private static final int SOULSEEK_UI_ACTION = 2;
    private static final int SOULSEEK_UI_DOWNLOAD = 3;
    private static final int SOULSEEK_ACTION_PLAY = 1;
    private static final int SOULSEEK_ACTION_SAVE = 2;
    private static final int SOULSEEK_ACTION_QUEUE = 3;
    private static final int SOULSEEK_PAGE_SIZE = 25;
    private static final int SOULSEEK_UI_FLUSH_MS_HEAVY = 300;
    private int soulseekResultsVisibleCount = SOULSEEK_PAGE_SIZE;
    private static final int SOULSEEK_UI_FLUSH_MS = 200;
    private static final int SOULSEEK_ROWS_PER_FLUSH = 8;
    private int soulseekUiMode = SOULSEEK_UI_SEARCH;
    private String soulseekLastQuery = "";
    private final List<SoulseekClient.Result> soulseekResults = new ArrayList<SoulseekClient.Result>();
    private final Set<String> soulseekResultKeys = new HashSet<String>();
    private boolean soulseekSearchInProgress = false;
    private final List<SoulseekClient.Result> soulseekPendingUi = new ArrayList<SoulseekClient.Result>();
    private final Handler soulseekUiHandler = new Handler();
    private boolean soulseekUiFlushScheduled = false;
    private int soulseekResultUiCount = 0;
    private Button soulseekSearchStatusRow = null;
    private long soulseekLastStatusUiMs = 0;
    private Button soulseekMoreRow = null;
    private Button soulseekFocusedResultRow = null;
    private long soulseekLastProgressUiMs = 0;
    private int soulseekPendingAction = 0;
    private SoulseekClient.Result soulseekActiveDownload = null;
    private Button soulseekDownloadStatusRow = null;
    private TextView soulseekDownloadDetailText = null;
    private ProgressBar soulseekDownloadProgressBar = null;
    private TextView soulseekDownloadPercentText = null;
    private long soulseekDownloadStartMs = 0;
    private long soulseekDownloadLastDone = 0;
    private long soulseekDownloadLastSpeedMs = 0;
    private String soulseekDownloadPhase = "";
    private String soulseekDownloadPhaseDetail = "";
    private boolean soulseekDownloadStalled = false;
    private boolean soulseekDownloadUiFailed = false;
    private String soulseekDownloadFailureReason = "";
    private int soulseekFailedPendingAction = 0;
    private Button soulseekTryAnotherRow = null;
    private boolean soulseekReSearchRowsShown = false;
    private SolarUpdateClient.ReleaseInfo otaActiveDownload = null;
    private java.util.concurrent.atomic.AtomicBoolean otaDownloadCancel = null;
    private Thread otaDownloadThread = null;
    private ProgressBar otaDownloadProgressBar = null;
    private TextView otaDownloadPercentText = null;
    private TextView otaDownloadDetailText = null;
    private Button otaDownloadStatusRow = null;
    private long otaDownloadStartMs = 0;
    private long otaDownloadLastDone = 0;
    private long otaDownloadLastSpeedMs = 0;
    private long otaDownloadLastProgressUiMs = 0;
    private int otaDownloadLocalCode = 0;
    private String otaDownloadLocalName = "";
    private final Handler otaDownloadUiHandler = new Handler();
    private final Runnable otaDownloadTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!SettingsScreens.SYSTEM_UPDATE_DOWNLOAD.equals(settingsSubScreenKey)
                    || otaActiveDownload == null) return;
            updateOtaDownloadStatusUi();
            otaDownloadUiHandler.postDelayed(this, 1000);
        }
    };
    private File reachGrowingCacheFile = null;
    private long reachGrowingPreparedBytes = 0;
    private long reachGrowingTotalBytes = 0;
    private volatile boolean reachPartialPlaybackStarted = false;
    private volatile boolean reachGrowingReprepareInFlight = false;
    private int reachGrowingSeekMs = 0;
    private File reachQueuePartialFile = null;
    private static final long REACH_GROWING_EXTEND_MS = 1800;
    private static final long REACH_GROWING_MIN_GROW_BYTES = 4096;
    private static final long SOULSEEK_ACTION_REFRESH_MS = 7000;
    private final List<String> soulseekActionSuggestionPool = new ArrayList<String>();
    private int soulseekActionSuggestionOffset = 0;
    private LinearLayout soulseekActionSuggestionHost = null;
    private final Runnable soulseekActionRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (soulseekUiMode != SOULSEEK_UI_ACTION) return;
            soulseekActionSuggestionOffset += 4;
            refreshSoulseekActionSuggestions();
            soulseekUiHandler.postDelayed(this, SOULSEEK_ACTION_REFRESH_MS);
        }
    };
    private final Runnable reachGrowingEdgePoll = new Runnable() {
        @Override
        public void run() {
            maybeExtendReachGrowingPlayback(false);
        }
    };
    private static final long SOULSEEK_STALL_MS = 12000;
    private final Runnable soulseekStallWatchRunnable = new Runnable() {
        @Override
        public void run() {
            if (soulseekUiMode != SOULSEEK_UI_DOWNLOAD || soulseekActiveDownload == null) return;
            if (soulseekDownloadLastDone > 0) return;
            soulseekDownloadStalled = true;
            updateSoulseekDownloadStatusUi();
            showSoulseekTryAnotherRow(true);
        }
    };
    private final Runnable soulseekDownloadTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (soulseekUiMode != SOULSEEK_UI_DOWNLOAD || soulseekActiveDownload == null) return;
            updateSoulseekDownloadStatusUi();
            soulseekUiHandler.postDelayed(this, 1000);
        }
    };
    private SoulseekClient.Result soulseekFailedResult = null;
    private SoulseekClient soulseekClient;
    private boolean soulseekCharging = false;
    private final SoulseekShareIndex soulseekShareIndex = new SoulseekShareIndex();
    private final SoulseekSharePolicy soulseekSharePolicy = new SoulseekSharePolicy();
    private volatile boolean soulseekShareScanRunning = false;
    private static final int PODCAST_DOWNLOAD_MAX_RETRIES = 3;
    private int podcastDownloadRetryCount = 0;
    private long podcastDownloadLastProgressMs = 0;
    private long podcastDownloadStallCheckBytes = 0;
    private boolean podcastRecoveryInFlight = false;
    private boolean soulseekAutoDownloadPending = false;
    private boolean soulseekHideFlac = true;
    private int soulseekListenPort = 0;
    private int keyboardPurpose = KEYBOARD_WIFI;
    private int keyboardReturnState = STATE_WIFI;
    private String keyboardReturnSettingsSubKey = null;
    private int soulseekReturnScreen = STATE_MENU;
    private int pendingScreenChange = -1;
    private String soulseekReturnSettingsSubKey = null;
    private int soulseekSettingsMenuFocusIndex = 15;
    private String pendingSoulseekUser = "";
    private int y1RowWidthPx;
    private int y1RowHeightPx;
    private int listRowWidthPx;
    private int y1ThemeCoverHeightPx;
    private int y1BatteryIconHeightPx;
    private int screenWidthPx;
    private int screenHeightPx;
    private int settingsMenuWidthPx;
    private int menuListHeightPx;
    private boolean statusBarShowsTitle = true;
    private boolean statusBarMatchFont = true;
    private String settingsParentKey = null;
    private boolean isFullWidthMenus = false;
    private String browserStatusTitle;
    private String settingsSubScreenKey = null;
    private String settingsSubScreenExtra = null;
    private List<ThemeDownloader.CatalogEntry> themeGalleryCatalog;
    private int themeGalleryPreviewGen = 0;
    private int unifiedThemesUiGen = 0;
    private ThemeDownloader.CatalogEntry themeGalleryInterstitialEntry;
    private ThemeDownloader.ThemeVariant themeGalleryInterstitialVariant;
    private FrameLayout themeGalleryInterstitial;
    private TextView tvThemeInterstitialTitle, tvThemeInterstitialAuthor;
    private ImageView ivThemeInterstitialCover;
    private Button btnThemeInterstitialDownload, btnThemeInterstitialBack;
    private FrameLayout settingsMenuHost;
    private View settingsPreviewPane;
    private boolean centerLongPressHandled = false;
    private long centerKeyDownTime = 0;
    private static final long CENTER_SLEEP_HOLD_MS = 300;
    private final Runnable centerSleepRunnable = new Runnable() {
        @Override
        public void run() {
            if (centerLongPressHandled || centerKeyDownTime == 0) return;
            centerLongPressHandled = true;
            performScreenSleep(false);
        }
    };
    private final Runnable keyboardDelRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentScreenState != STATE_WIFI_KEYBOARD || !isKeyboardDelSelected()) return;
            handleKeyboardMediaDel();
            clockHandler.postDelayed(this, 80);
        }
    };
    private long backKeyDownTime = 0;
    private boolean backLongPressHandled = false;
    private static final long BACK_LONG_PRESS_MS = 600;
    private static final long MEDIA_SKIP_LONG_PRESS_MS = 500;
    private static final int MEDIA_SCRUB_STEP_MS = 5000;
    private long mediaPrevKeyDownTime = 0;
    private long mediaNextKeyDownTime = 0;
    private boolean mediaPrevScrubActive = false;
    private boolean mediaNextScrubActive = false;
    private boolean playerScrubCursorActive = false;
    private int playerScrubCursorMs = 0;
    private FrameLayout playerProgressTrack;
    private View playerScrubMarker;
    private ThemedContextMenu themedContextMenu;
    private final java.util.ArrayList<String> contextMenuLabels = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> contextMenuIconKeys = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> contextMenuStateTexts = new java.util.ArrayList<String>();
    private final java.util.ArrayList<Boolean> contextMenuHeaders = new java.util.ArrayList<Boolean>();
    private final java.util.ArrayList<Runnable> contextMenuActions = new java.util.ArrayList<Runnable>();
    private final java.util.ArrayDeque<String> contextMenuTierStack = new java.util.ArrayDeque<String>();
    private boolean contextMenuInVolumeSlider = false;

    private Button btnScanBt, btnScanWifi;

    private TextView tvKeyboardSsid, tvKeyboardInput;
    private TextView tvKeyPprev, tvKeyPrev, tvKeyCurrent, tvKeyNext, tvKeyNnext;

    private final String[] KEYBOARD_CHARS = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
            "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
            "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "!", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", ".", "?",
            "[SPC]", "[DEL]", "[CONN]"
    };
    private int keyboardIndex = 0;
    private boolean keyboardPpLongDoCase = true;
    private long keyboardMediaSkipDownAt = 0;
    private long keyboardPpDownAt = 0;
    private boolean keyboardPpLongHandled = false;
    private String targetWifiSsid = "";
    private String typedPassword = "";
    private String keyboardPrefill = null;
    private boolean isTargetWifiOpen = false;
    // 💡 미디어 스캐너가 현재 작업 중인지 추적하는 변수
    private boolean isMediaScanning = false;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private File rootFolder = new File("/storage/sdcard0/Music");
    private File currentFolder = rootFolder;

    private File getStorageRoot() {
        return new File("/storage/sdcard0");
    }
    private boolean isPausedByHand = true;
    private String podcastResumeKey = "";
    private int podcastPendingResumeMs = 0;
    private long lastPodcastResumeSaveMs = 0;

    private java.io.FileInputStream currentFileInputStream = null;
    private TextView tvMenuPreviewTitle, tvMenuPreviewArtist;
    private SharedPreferences prefs;
    private static final String PREFS = "SOLAR_SETTINGS";
    private static final String BG_MODE_ALBUM = "album_art_blur";
    private static final String BG_MODE_THEME = "theme_wallpaper";
    private static final String BG_MODE_CUSTOM = "custom";
    private static final String PREF_PLAYER_ALBUM_BLUR = "player_album_blur";
    private static final String PREF_BG_HOME = "bg_home";
    private static final String PREF_BG_LIBRARY = "bg_library";
    private static final String PREF_BG_SETTINGS = "bg_settings";
    private static final String PREF_BG_NOW_PLAYING = "bg_now_playing";
    private static final String PREF_UPDATE_NUDGE_TS = "update_nudge_ts";
    private static final long REACH_ID3_MIN_BYTES = 32 * 1024L;
    private static final String PREF_BG_THEME_WALLPAPER = "bg_theme_wallpaper";
    private boolean playerAlbumBlurEnabled = false;
    private boolean isShuffleMode = false;
    private int repeatMode = 0; // 0: OFF, 1: ONE (Repeat One), 2: ALL (Repeat Folder/All)
    private boolean isSoundEffectEnabled = true;
    private boolean isVibrationEnabled = true;
    private boolean isPickingBackground = false;

    // 💡 마지막으로 재생된 앨범 아트를 기억하는 변수
    private byte[] lastAlbumArtBytes = null;
    // 💡 이퀄라이저 관련 변수 추가
    private Equalizer equalizer;
    private List<String> eqPresetNames = new ArrayList<String>();
    private int currentEqPresetIndex = 0;

    private int lastSettingsFocusIndex = 1;

    private boolean isScreenSleeping = false;
    private long lastScreenOnTime = 0;
    // 💡 [추가] 커스텀 배터리 뷰 변수
    private BatteryIconView batteryIconView;
    private ImageView ivStatusBatteryThemed;
    private int currentTimeoutIndex = 1;
    private final int[] TIMEOUT_VALUES = { 15000, 30000, 60000, 300000 };
    private final String[] TIMEOUT_NAMES = { "15 Sec", "30 Sec", "1 Min", "5 Min" };

    private int currentSystemBrightness = 255;
    private Random random = new Random();

    private List<String> foundBtDevices = new ArrayList<String>();
    private List<String> foundWifiNetworks = new ArrayList<String>();
    private String pairingDeviceAddress;
    private BluetoothA2dp bluetoothA2dp;
    private BluetoothDevice pendingA2dpDevice;
    private String connectedA2dpAddress;
    private static final String PREF_LAST_BT_AUDIO = "last_bt_audio_address";
    private final BluetoothProfile.ServiceListener a2dpProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.A2DP) return;
            bluetoothA2dp = (BluetoothA2dp) proxy;
            if (pendingA2dpDevice != null) {
                connectA2dpNow(pendingA2dpDevice);
                pendingA2dpDevice = null;
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) bluetoothA2dp = null;
        }
    };

    private SolarWebServer webServer;
    private boolean isServerRunning = false;

    private Handler clockHandler = new Handler();
    private Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            updateStatusBarTitle();
            if (isWidgetClockOn) refreshWidgets();
            clockHandler.postDelayed(this, 1000);
        }
    };

    private Handler progressHandler = new Handler();
    private Runnable updateProgressTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (podcastEpisodeLoading) {
                    progressHandler.postDelayed(this, 500);
                    return;
                }
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    if (playerScrubCursorActive) {
                        progressHandler.postDelayed(this, 500);
                        return;
                    }
                    int current = mediaPlayer.getCurrentPosition();
                    lastPodcastPlayPositionMs = current;
                    int duration;
                    if (podcastGrowingCacheFile != null && podcastDownloadInProgress) {
                        duration = podcastGrowingDisplayDurationMs();
                        if (duration <= 0) duration = mediaPlayer.getDuration();
                    } else {
                        duration = mediaPlayer.getDuration();
                    }
                    if (duration > 0) {
                        int progress = (int) (((float) current / duration) * 100);
                        if (progress > 100) progress = 100;
                        playerProgress.setProgress(progress);
                        tvPlayerTimeCurrent.setText(formatTime(current));
                        tvPlayerTimeTotal.setText(formatTime(duration));
                    }
                    if (playback.isPodcastActive() && !podcastResumeKey.isEmpty()
                            && System.currentTimeMillis() - lastPodcastResumeSaveMs > 5000) {
                        PodcastResumeStore.save(getApplicationContext(), podcastResumeKey, current,
                                podcastResumeDurationForSave());
                        lastPodcastResumeSaveMs = System.currentTimeMillis();
                    }
                    if (podcastGrowingCacheFile != null
                            && (podcastDownloadInProgress || podcastPartialPlaybackStarted)) {
                        int mpDur = mediaPlayer.getDuration();
                        if (mpDur > 0 && current >= mpDur - PODCAST_GROWING_EXTEND_MS) {
                            maybeExtendPodcastGrowingPlayback(playback.podcastIndex(),
                                    podcastLoadGeneration, false);
                        }
                        if (podcastDownloadInProgress && podcastPartialPlaybackStarted) {
                            long now = System.currentTimeMillis();
                            if (now - podcastDownloadLastProgressMs > 15000
                                    && currentScreenState == STATE_PLAYER && !podcastDownloadPaused) {
                                podcastDownloadLastProgressMs = now;
                                maybeRecoverPodcastStream(playback.podcastIndex(), podcastLoadGeneration);
                            }
                        }
                        if (podcastPartialPlaybackStarted && !podcastDownloadInProgress
                                && currentScreenState == STATE_PLAYER && !podcastDownloadPaused) {
                            int bufferedMs = podcastBufferedSeekLimitMs();
                            if (bufferedMs > 0 && current >= bufferedMs - 2500) {
                                maybeRecoverPodcastStream(playback.podcastIndex(), podcastLoadGeneration);
                            }
                        }
                    }
                    if (reachPartialPlaybackStarted && reachGrowingCacheFile != null) {
                        int displayDur = reachGrowingDisplayDurationMs();
                        if (displayDur > 0) {
                            int progress = Math.min(100, current * 100 / displayDur);
                            playerProgress.setProgress(progress);
                            tvPlayerTimeCurrent.setText(formatTime(current));
                            tvPlayerTimeTotal.setText(formatTime(displayDur));
                        }
                        int mpDur = mediaPlayer.getDuration();
                        if (mpDur > 0 && current >= mpDur - REACH_GROWING_EXTEND_MS) {
                            maybeExtendReachGrowingPlayback(false);
                        }
                        if (reachDownloadInProgress() && currentScreenState == STATE_PLAYER) {
                            updateReachPlayerBufferUi(
                                    reachGrowingTotalBytes > 0
                                            ? (int) (reachGrowingCacheFile.length() * 100 / reachGrowingTotalBytes)
                                            : 0,
                                    reachGrowingCacheFile.length(), reachGrowingTotalBytes);
                        }
                    }
                } else if (mediaPlayer != null && podcastGrowingCacheFile != null
                        && (podcastDownloadInProgress || podcastPartialPlaybackStarted)) {
                    updatePodcastGrowingTimeUi();
                } else if (mediaPlayer != null && reachPartialPlaybackStarted && reachGrowingCacheFile != null) {
                    updateReachGrowingTimeUi();
                }
            } catch (Exception e) {
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    private Handler volumeHandler = new Handler();
    private Runnable hideVolumeTask = new Runnable() {
        @Override
        public void run() {
            layoutVolumeOverlay.setVisibility(View.GONE);
        }
    };

    private BroadcastReceiver systemStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isScreenSleeping = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenSleeping = false;
                lastScreenOnTime = System.currentTimeMillis();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                updateBatteryUi(intent);
            } else if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                int state = intent.getIntExtra("state", -1);
                if (state == 1) {
                    ivStatusHeadphone.setVisibility(View.VISIBLE);
                    applyThemedStatusIcon(ivStatusHeadphone, "headsetWithMic", "headsetWithoutMic",
                            R.drawable.ic_headphone, 0xFFFFFFFF);
                } else {
                    ivStatusHeadphone.setVisibility(View.GONE);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    ivStatusBluetooth.setVisibility(View.VISIBLE);
                    applyThemedStatusIcon(ivStatusBluetooth, "blConnected", null,
                            R.drawable.ic_bluetooth, 0xFF5555FF);
                    ensureA2dpProfile();
                    reconnectLastBluetoothAudio();
                } else {
                    ivStatusBluetooth.setVisibility(View.GONE);
                    connectedA2dpAddress = null;
                }
                if (currentScreenState == STATE_SETTINGS)
                    buildSettingsUI();
                else if (currentScreenState == STATE_BLUETOOTH)
                    startBluetoothScan();
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    ivStatusWifi.setVisibility(View.VISIBLE);
                    refreshWifiStatusIcon();
                } else {
                    ivStatusWifi.setVisibility(View.GONE);
                }
                if (currentScreenState == STATE_SETTINGS)
                    buildSettingsUI();
                else if (currentScreenState == STATE_WIFI)
                    startWifiScan();
                onWifiConnectivityChanged();
            } else if (WifiManager.RSSI_CHANGED_ACTION.equals(action)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                    if (networkInfo != null && networkInfo.isConnected()) {
                        if (currentScreenState == STATE_WIFI)
                            startWifiScan();
                    }
                    onWifiConnectivityChanged();
                }
                refreshWifiStatusIcon();
            } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                onWifiConnectivityChanged();
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                if (currentScreenState != STATE_BLUETOOTH) return;
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String deviceName = device.getName();
                String deviceAddress = device.getAddress();
                if (deviceName != null && !foundBtDevices.contains(deviceAddress)) {
                    foundBtDevices.add(deviceAddress);
                    // 💡 새로 발견된 낯선 기기는 isPaired = false 로 보냅니다.
                    addBluetoothItemToUI(deviceName, device, false, false);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    prefs.edit().putString(PREF_LAST_BT_AUDIO, device.getAddress()).apply();
                    connectBluetoothAudio(device);
                }
                if (currentScreenState == STATE_BLUETOOTH) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Toast.makeText(MainActivity.this, getString(R.string.toast_paired, device.getName()), Toast.LENGTH_SHORT).show();
                        pairingDeviceAddress = null;
                        startBluetoothScan();
                    } else if (bondState == BluetoothDevice.BOND_NONE
                            && pairingDeviceAddress != null
                            && pairingDeviceAddress.equals(device.getAddress())) {
                        Toast.makeText(MainActivity.this, getString(R.string.toast_pairing_failed), Toast.LENGTH_SHORT).show();
                        pairingDeviceAddress = null;
                    }
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
                    if (handleBluetoothPairingRequest(device, variant)) {
                        abortBroadcast();
                    }
                }
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                if (device != null && state == BluetoothProfile.STATE_CONNECTED) {
                    connectedA2dpAddress = device.getAddress();
                    prefs.edit().putString(PREF_LAST_BT_AUDIO, device.getAddress()).apply();
                    Toast.makeText(MainActivity.this, getString(R.string.toast_audio_connected, device.getName()), Toast.LENGTH_SHORT).show();
                } else if (device != null && state == BluetoothProfile.STATE_DISCONNECTED
                        && device.getAddress().equals(connectedA2dpAddress)) {
                    connectedA2dpAddress = null;
                }
                if (currentScreenState == STATE_BLUETOOTH) startBluetoothScan();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                btnScanBt.setText(getString(R.string.bluetooth_scan_complete));
                updateStatusBarTitle();
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    List<ScanResult> results = wm.getScanResults();
                    btnScanWifi.setText(getString(R.string.bluetooth_scan_complete));
                    updateWifiUI(results);
                }
                updateStatusBarTitle();
            }
            // 🚀 [여기에 추가!] 시스템 미디어 스캐너 감지 센서
            else if (Intent.ACTION_MEDIA_SCANNER_STARTED.equals(action)) {
                isMediaScanning = true;
            } else if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
                isMediaScanning = false;

            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
// 🚀 앱이 켜지면 자기 자신을 변수에 등록합니다.
        instance = this;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    e.printStackTrace(pw);
                    java.io.File logFile = new java.io.File("/storage/sdcard0/solar_crash_log.txt");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
                    fos.write(("\n\n--- 💥 CRASH REPORT (" + new java.util.Date().toString() + ") ---\n").getBytes());
                    fos.write(sw.toString().getBytes());
                    fos.close();
                } catch (Exception ex) {
                }
                System.exit(1);
            }
        });

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        initY1LayoutMetrics();

        // 🚀 [여기서부터 새로 추가!] 메인 화면 위에 덮어씌울 '로딩 스피너 팝업창'을 생성합니다.
        android.view.ViewGroup root = findViewById(android.R.id.content);
        layoutLoadingOverlay = new LinearLayout(this);
        layoutLoadingOverlay.setOrientation(LinearLayout.VERTICAL);
        layoutLoadingOverlay.setGravity(android.view.Gravity.CENTER);
        layoutLoadingOverlay.setBackgroundColor(0xDD000000); // 짙은 반투명 검은색 배경으로 덮기
        layoutLoadingOverlay.setClickable(true); // 로딩 중 뒷배경 터치 방지
        layoutLoadingOverlay.setFocusable(true); // 로딩 중 휠 조작 방지
        layoutLoadingOverlay.setVisibility(View.GONE);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        layoutLoadingOverlay.addView(spinner);

        TextView tvLoading = new TextView(this);
        tvLoading.setText(getString(R.string.loading_library));
        tvLoading.setTextColor(0xFFFFFFFF); // 확실하고 선명한 흰색 글씨!
        tvLoading.setTextSize(20);
        tvLoading.setGravity(android.view.Gravity.CENTER);
        tvLoading.setPadding(0, 30, 0, 0);
        layoutLoadingOverlay.addView(tvLoading);
        tvLoadingOverlayText = tvLoading;

        root.addView(layoutLoadingOverlay, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));
        // 🚀 [여기까지 추가 끝!]
        themedContextMenu = new ThemedContextMenu(this);
        tvFastScrollLetter = new TextView(this);
        tvFastScrollLetter.setTextSize(50); // 글자 크기를 아주 큼직하게!
        tvFastScrollLetter.setGravity(android.view.Gravity.CENTER);
        tvFastScrollLetter.setVisibility(View.GONE);

        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                (int)(80 * getResources().getDisplayMetrics().density), // 가로 80dp
                (int)(80 * getResources().getDisplayMetrics().density)  // 세로 80dp
        );
        flp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT; // 오른쪽 가운데 정렬
        flp.rightMargin = (int)(30 * getResources().getDisplayMetrics().density); // 오른쪽에서 30dp 띄움
        root.addView(tvFastScrollLetter, flp);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // 🚀 [시스템 공식 등록] 화면이 꺼져도 버튼 신호를 받을 수 있도록 수신기를 장착합니다!
        ComponentName componentName = new ComponentName(getPackageName(), MediaBtnReceiver.class.getName());
        audioManager.registerMediaButtonEventReceiver(componentName);
// 🚀 [스크린 오프 완벽 제어 1단계] 시스템의 미디어/버튼 제어권을 앱이 뺏어옵니다!
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                mediaSessionShim = Class.forName("com.solar.launcher.MediaSessionShim")
                        .getMethod("create", MainActivity.class).invoke(null, this);
            }
        } catch (Exception e) {}

        migrateLegacyPrefs();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ensurePrivilegedPermissionsAsync();

        ThemeManager.ensureBundledDefault(this);
        ThemeManager.loadAllThemes(this);
        try {
            String savedPath = prefs.getString("app_theme_path", null);
            if (savedPath != null) {
                ThemeManager.setThemeByFolderPath(savedPath);
            } else {
                int savedThemeIndex = prefs.getInt("app_theme_index", 0);
                ThemeManager.setThemeIndex(savedThemeIndex);
            }
        } catch (Exception e) {}

        // (이하 블랙리스트 및 다른 설정 불러오기 코드 유지)
        // 💡 1. 블랙리스트 (안드로이드 내부 버그 방지를 위해 HashSet을 새로 감싸서 안전하게 로드)
        try {
            java.util.Set<String> savedBlacklist = prefs.getStringSet("blacklist", new java.util.HashSet<String>());
            blacklist = new java.util.HashSet<>(savedBlacklist);

            String poisonFile = prefs.getString("last_attempted_file", null);
            if (poisonFile != null) {
                blacklist.add(poisonFile);
                prefs.edit().putStringSet("blacklist", blacklist).remove("last_attempted_file").commit();
            }
        } catch (Exception e) {}

        // 💡 2. 설정값들을 각각 독립적으로 불러오기 (어떤 상황에서도 절대 스킵되지 않습니다!)
        try { isShuffleMode = prefs.getBoolean("shuffle", false); } catch (Exception e) {}

        try {
            if (prefs.contains("repeat_mode")) {
                repeatMode = prefs.getInt("repeat_mode", 0);
            } else {
                repeatMode = prefs.getBoolean("repeat", false) ? 1 : 0;
            }
        } catch (Exception e) {}

        try {
            isSoundEffectEnabled = prefs.getBoolean("sound", true);
            applySoundSetting();
        } catch (Exception e) {}

        try { isVibrationEnabled = prefs.getBoolean("vibrate", true); } catch (Exception e) {}
        try { isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false); } catch (Exception e) {}

        try { isAutoFetchEnabled = prefs.getBoolean("auto_fetch", true); } catch (Exception e) {} // 🚀 [추가]
        try { currentTimeoutIndex = prefs.getInt("timeout_idx", 1); } catch (Exception e) {}

        try {
            currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 255);
        } catch (Exception e) {}

        // 💡 [EQ 프리셋 목록 자동 로드] 기기가 지원하는 이퀄라이저 리스트를 가져옵니다.
        try {
            MediaPlayer dummyMp = new MediaPlayer();
            Equalizer dummyEq = new Equalizer(0, dummyMp.getAudioSessionId());
            short presets = dummyEq.getNumberOfPresets();
            for (short i = 0; i < presets; i++) {
                eqPresetNames.add(dummyEq.getPresetName(i));
            }
            dummyEq.release();
            dummyMp.release();
        } catch (Exception e) {
            eqPresetNames.add(getString(R.string.eq_normal_default));
        }

        currentEqPresetIndex = prefs.getInt("eq_preset", 0);
        if (currentEqPresetIndex >= eqPresetNames.size())
            currentEqPresetIndex = 0;

        if (!rootFolder.exists())
            rootFolder.mkdirs();

        // 🚀 [추가된 부분] 앱이 켜질 때(혹은 튕기고 재시작될 때) 조용히 자동 스캔을 돌려 리스트를 복구합니다!
        if (customLibrary.isEmpty() && !isCustomScanning) {
            isCustomScanning = true;
            final int gen = libraryScanGen;
            activeLibraryScanGen = gen;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    customLibrary.clear();
                    libraryScanPaths.clear();
                    libraryScanMetaKeys.clear();
                    buildCustomLibrary(rootFolder);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            isCustomScanning = false;
                            // 스캔이 끝났을 때 사용자가 이미 브라우저 화면에 진입해 있다면 화면을 새로고침해 줍니다.
                            if (currentScreenState == STATE_BROWSER) {
                                if (currentBrowserMode == BROWSER_ROOT) {
                                    buildFileBrowserUI();
                                } else if (currentBrowserMode == BROWSER_ARTISTS) {
                                    buildVirtualCategories("ARTIST");
                                } else if (currentBrowserMode == BROWSER_ALBUMS) {
                                    buildVirtualCategories("ALBUM");
                                } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                                    buildVirtualSongs();
                                }
                            }
                        }
                    });
                }
            }).start();
        }
        layoutMainMenu = findViewById(R.id.layout_main_menu);
        menuListHost = findViewById(R.id.menu_list_host);
        menuScroll = findViewById(R.id.menu_scroll);
        containerHomeMenuItems = findViewById(R.id.container_home_menu_items);
        if (menuScroll != null) {
            menuScroll.setFocusable(false);
            menuScroll.setFocusableInTouchMode(false);
        }
        if (containerHomeMenuItems != null) {
            containerHomeMenuItems.setFocusable(false);
            containerHomeMenuItems.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        ivMainBg = findViewById(R.id.iv_main_bg);
        ivScreenMask = findViewById(R.id.iv_screen_mask);
        layoutSettingsMode = findViewById(R.id.layout_settings_mode);
        settingsMenuHost = findViewById(R.id.settings_menu_host);
        settingsPreviewPane = findViewById(R.id.settings_preview_pane);
        tvSettingsPreviewTitle = findViewById(R.id.tv_settings_preview_title);
        tvSettingsPreviewState = findViewById(R.id.tv_settings_preview_state);
        ivSettingsPreviewIcon = findViewById(R.id.iv_settings_preview_icon);
        storagePieView = findViewById(R.id.storage_pie_view);
        themeGalleryInterstitial = findViewById(R.id.theme_gallery_interstitial);
        tvThemeInterstitialTitle = findViewById(R.id.tv_theme_interstitial_title);
        tvThemeInterstitialAuthor = findViewById(R.id.tv_theme_interstitial_author);
        ivThemeInterstitialCover = findViewById(R.id.iv_theme_interstitial_cover);
        btnThemeInterstitialDownload = findViewById(R.id.btn_theme_interstitial_download);
        btnThemeInterstitialBack = findViewById(R.id.btn_theme_interstitial_back);
        if (btnThemeInterstitialDownload != null) {
            configureY1ThemedButton(btnThemeInterstitialDownload, Y1_ROW_MENU);
        }
        if (btnThemeInterstitialBack != null) {
            configureY1ThemedButton(btnThemeInterstitialBack, Y1_ROW_MENU);
            btnThemeInterstitialBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    hideThemeGalleryInterstitial();
                }
            });
        }
        playerContentRow = findViewById(R.id.player_content_row);
        playerVisualizerContainer = findViewById(R.id.player_visualizer_container);
        playerVisualizerSlot = findViewById(R.id.player_visualizer_slot);
        tvVizTitle = findViewById(R.id.tv_viz_title);
        tvVizArtist = findViewById(R.id.tv_viz_artist);
        tvVizTrackCount = findViewById(R.id.tv_viz_track_count);
        ivMenuPreview = findViewById(R.id.iv_menu_preview);
        tvMenuPreviewTitle = findViewById(R.id.tv_menu_preview_title);
        tvMenuPreviewArtist = findViewById(R.id.tv_menu_preview_artist);
        tvMenuPreviewTitle.setSelected(true);

        // 🚀 1. 저장해둔 위젯 체크 상태 불러오기
        try { isWidgetClockOn = prefs.getBoolean("widget_clock", false); } catch (Exception e) {}
        try { isWidgetBatteryOn = prefs.getBoolean("widget_battery", false); } catch (Exception e) {}
        try { isWidgetAlbumOn = prefs.getBoolean("widget_album", false); } catch (Exception e) {}
        try { isFullWidthMenus = prefs.getBoolean("full_width_menus", false); } catch (Exception e) {}
        try { playerAlbumBlurEnabled = prefs.getBoolean(PREF_PLAYER_ALBUM_BLUR, false); } catch (Exception e) {}
        try { migrateBackgroundPrefs(); } catch (Exception e) {}
        try { restorePlaybackQueue(); } catch (Exception e) {}
        try { scheduleStartupUpdateNudge(); } catch (Exception e) {}
        try {
            String sf = prefs.getString(PREF_PODCAST_STOREFRONT, "US");
            if (sf != null && sf.length() == 2) podcastStorefront = sf.toUpperCase();
        } catch (Exception e) {}

        // 🚀 2. 우측 빈 공간에 위젯들을 담을 투명한 컨테이너(상자)를 자바 코드로 생성합니다!
        android.view.ViewGroup previewContainer = (android.view.ViewGroup) ivMenuPreview.getParent();
        layoutWidgets = new LinearLayout(this);
        layoutWidgets.setOrientation(LinearLayout.VERTICAL);
        layoutWidgets.setGravity(android.view.Gravity.CENTER);
        layoutWidgets.setVisibility(View.GONE);
        previewContainer.addView(layoutWidgets, new android.widget.FrameLayout.LayoutParams(
                (int) getResources().getDimension(R.dimen.y1_preview_width),
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        ));
        android.widget.FrameLayout.LayoutParams wlp =
                (android.widget.FrameLayout.LayoutParams) layoutWidgets.getLayoutParams();
        wlp.topMargin = (int) getResources().getDimension(R.dimen.y1_preview_margin_top);
        wlp.rightMargin = (int) getResources().getDimension(R.dimen.y1_preview_margin_right);
        layoutWidgets.setLayoutParams(wlp);
        layoutWidgets.setGravity(Gravity.CENTER_HORIZONTAL);

        // 🚀 3. 시계 위젯 부품 생성
        tvWidgetClock = new TextView(this);
        tvWidgetClock.setTextSize(26);
        tvWidgetClock.setGravity(android.view.Gravity.CENTER);
        // 🚀 [간격 조절] 시계와 배터리 사이의 여백을 살짝 줄입니다 (20 -> 10)
        tvWidgetClock.setPadding(0, 0, 0, 10);
        layoutWidgets.addView(tvWidgetClock);

        // 🚀 4. 배터리 위젯 부품 생성 (가로형 바)
        widgetBatteryView = new WidgetBatteryBarView(this);
        float d = getResources().getDisplayMetrics().density;

        // 🚀 [크기/간격 조절] 배터리 높이를 얄쌍하게 줄이고(30->18), 너비도 화면에 맞게 다듬습니다(140->110)
        LinearLayout.LayoutParams widgetBlp = new LinearLayout.LayoutParams((int)(110 * d), (int)(18 * d));
        // 배터리와 앨범 아트 사이의 여백도 타이트하게 줄입니다 (30 -> 15)
        widgetBlp.setMargins(0, 0, 0, 15);
        layoutWidgets.addView(widgetBatteryView, widgetBlp);

        // 🚀 5. 앨범 위젯 부품 생성
        ivWidgetAlbum = new ImageView(this);
        // 🚀 [크기 조절] 앨범 아트가 화면 끝에 닿아 답답해 보이지 않도록 크기를 줄여 숨통을 틔워줍니다 (140 -> 110)
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int)(110 * d), (int)(110 * d));
        layoutWidgets.addView(ivWidgetAlbum, alp);

        // 🚀 [여기에 10줄 새로 추가!!] 앨범 밑에 들어갈 제목과 가수 텍스트 부품 생성
        tvWidgetAlbumTitle = new TextView(this);
        tvWidgetAlbumTitle.setGravity(android.view.Gravity.CENTER);
        tvWidgetAlbumTitle.setSingleLine(true);
        tvWidgetAlbumTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvWidgetAlbumTitle.setPadding(0, (int)(8 * d), 0, 0); // 🚀 [추가!] 앨범 아트와 글자 사이에 숨통(8dp)을 틔워줍니다.
        layoutWidgets.addView(tvWidgetAlbumTitle);

        tvWidgetAlbumArtist = new TextView(this);
        tvWidgetAlbumArtist.setGravity(android.view.Gravity.CENTER);
        tvWidgetAlbumArtist.setSingleLine(true);
        tvWidgetAlbumArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        layoutWidgets.addView(tvWidgetAlbumArtist);

        updateMainMenuBackground(); // 💡 앱을 켜면 저장된 상태에 맞춰 배경 자동 적용

        layoutBrowserMode = findViewById(R.id.layout_browser_mode);
        layoutPlayerMode = findViewById(R.id.layout_player_mode);
        containerBrowserItems = findViewById(R.id.container_browser_items);
        browserListHost = findViewById(R.id.browser_list_host);
        podcastPreviewPane = findViewById(R.id.podcast_preview_pane);
        ivPodcastPreviewArt = findViewById(R.id.iv_podcast_preview_art);
        tvPodcastPreviewShow = findViewById(R.id.tv_podcast_preview_show);
        tvPodcastPreviewEpisode = findViewById(R.id.tv_podcast_preview_episode);
        tvPodcastPreviewMeta = findViewById(R.id.tv_podcast_preview_meta);
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setSelected(true);
            enableMarquee(tvPodcastPreviewShow);
        }
        if (tvPodcastPreviewEpisode != null) {
            tvPodcastPreviewEpisode.setSelected(true);
            enableMarquee(tvPodcastPreviewEpisode);
        }

        scrollViewBrowser = findViewById(R.id.scroll_view_browser);

        listVirtualSongs = new android.widget.ListView(this);
        listVirtualSongs.setDivider(null); // 못생긴 기본 구분선 제거
        listVirtualSongs.setSelector(new android.graphics.drawable.ColorDrawable(0)); // 기본 터치 효과 제거
        listVirtualSongs.setItemsCanFocus(true);
        listVirtualSongs.setVisibility(View.GONE); // 평소엔 숨겨둡니다.

        android.view.ViewGroup browserParent = (android.view.ViewGroup) scrollViewBrowser.getParent();
        browserParent.addView(listVirtualSongs, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));
        layoutVolumeOverlay = findViewById(R.id.layout_volume_overlay);
        volumeProgress = findViewById(R.id.volume_progress);
        volumeProgress.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

        layoutSettingsMode = findViewById(R.id.layout_settings_mode);
        containerSettingsItems = findViewById(R.id.container_settings_items);
        settingsScrollView = (View) containerSettingsItems.getParent();
        listMusicQueue = new android.widget.ListView(this);
        listMusicQueue.setDivider(null);
        listMusicQueue.setSelector(new android.graphics.drawable.ColorDrawable(0));
        listMusicQueue.setItemsCanFocus(true);
        listMusicQueue.setVisibility(View.GONE);
        settingsMenuHost.addView(listMusicQueue, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        listThemes = new android.widget.ListView(this);
        listThemes.setDivider(null);
        listThemes.setSelector(new android.graphics.drawable.ColorDrawable(0));
        listThemes.setItemsCanFocus(true);
        listThemes.setVisibility(View.GONE);
        settingsMenuHost.addView(listThemes, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        layoutBluetoothMode = findViewById(R.id.layout_bluetooth_mode);
        containerBtItems = findViewById(R.id.container_bt_items);
        btnScanBt = findViewById(R.id.btn_scan_bt);
        layoutWifiMode = findViewById(R.id.layout_wifi_mode);
        containerWifiItems = findViewById(R.id.container_wifi_items);
        btnScanWifi = findViewById(R.id.btn_scan_wifi);
        btnScanBt.setSoundEffectsEnabled(false);
        btnScanWifi.setSoundEffectsEnabled(false);
        layoutWifiKeyboard = findViewById(R.id.layout_wifi_keyboard);
        tvKeyboardSsid = findViewById(R.id.tv_keyboard_ssid);
        tvKeyboardInput = findViewById(R.id.tv_keyboard_input);
        tvKeyPprev = findViewById(R.id.tv_key_pprev);
        tvKeyPrev = findViewById(R.id.tv_key_prev);
        tvKeyCurrent = findViewById(R.id.tv_key_current);
        tvKeyNext = findViewById(R.id.tv_key_next);
        tvKeyNnext = findViewById(R.id.tv_key_nnext);

        layoutBrightnessMode = findViewById(R.id.layout_brightness_mode);
        pbBrightness = findViewById(R.id.pb_brightness);
        tvBrightnessVal = findViewById(R.id.tv_brightness_val);

        layoutStorageMode = findViewById(R.id.layout_storage_mode);
        pbStorage = findViewById(R.id.pb_storage);
        tvStorageDetails = findViewById(R.id.tv_storage_details);

        layoutWebServerMode = findViewById(R.id.layout_webserver_mode);
        // (기존 코드)
        tvServerStatus = findViewById(R.id.tv_server_status);
        tvServerIp = findViewById(R.id.tv_server_ip);
        tvWebserverHint = findViewById(R.id.tv_webserver_hint);
        btnServerToggle = findViewById(R.id.btn_server_toggle);
        tvKeyboardHint = findViewById(R.id.tv_keyboard_hint);
        tvBrightnessHint = findViewById(R.id.tv_brightness_hint);
        tvStorageHint = findViewById(R.id.tv_storage_hint);

        // Overlay screens: transparent when theme provides wallpaper images
        applyOverlayBackgrounds();

        btnServerToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                toggleWebServer();
                updateWebServerUI();
            }
        });

        tvStatusClock = findViewById(R.id.tv_status_clock);
        tvStatusBattery = findViewById(R.id.tv_status_battery);
        tvStatusClock.setShadowLayer(0, 0, 0, 0);
        // Themed battery icon sits beside percentage text; custom view is fallback only.
        ivStatusBatteryThemed = findViewById(R.id.iv_status_battery);
        batteryIconView = new BatteryIconView(this);
        android.view.ViewGroup statusParent = (android.view.ViewGroup) tvStatusBattery.getParent();
        int bIdx = statusParent.indexOfChild(tvStatusBattery);

        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                (int)(40 * density), (int)(20 * density)
        );
        blp.gravity = android.view.Gravity.CENTER_VERTICAL;
        blp.setMargins((int)(12 * density), 0, (int)(4 * density), 0);
        statusParent.addView(batteryIconView, bIdx, blp);
        batteryIconView.setVisibility(View.GONE);
        ivStatusBluetooth = findViewById(R.id.iv_status_bluetooth);
        ivStatusWifi = findViewById(R.id.iv_status_wifi);
        ivStatusHeadphone = findViewById(R.id.iv_status_headphone);
        ivStatusPlayback = new ImageView(this);
        ivStatusPlayback.setVisibility(View.GONE);
        android.view.ViewGroup rightStatusGroup = (android.view.ViewGroup) ivStatusBluetooth.getParent();
        android.widget.LinearLayout.LayoutParams playLp = new android.widget.LinearLayout.LayoutParams(
                (int) getResources().getDimension(R.dimen.y1_status_icon_size),
                (int) getResources().getDimension(R.dimen.y1_status_icon_size));
        playLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        playLp.setMargins(0, 0, (int) (6 * density), 0);
        rightStatusGroup.addView(ivStatusPlayback, 0, playLp);

        tvBrowserPath = findViewById(R.id.tv_browser_path);
        tvBrowserPath.setTextColor(ThemeManager.getTextColorPrimary());

        applyFullWidthMenusLayout();
        initY1ThemedActionButtons();
        buildHomeMenu();

        tvPlayerTitle = findViewById(R.id.tv_player_title);
        tvPlayerArtist = findViewById(R.id.tv_player_artist);
        tvPlayerTimeCurrent = findViewById(R.id.tv_player_time_current);
        tvPlayerTimeTotal = findViewById(R.id.tv_player_time_total);
        ivAlbumArt = findViewById(R.id.iv_album_art);

        LinearLayout playerInfoColumn = findViewById(R.id.player_info_column);
        visualizerView = new AudioVisualizerView(this);
        visualizerView.setVisibility(View.GONE);
        playerVisualizerSlot.addView(visualizerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ivPlayerBgBlur = findViewById(R.id.iv_player_bg_blur);
        ivPauseOverlay = findViewById(R.id.iv_pause_overlay);
        playerProgress = findViewById(R.id.player_progress);
        playerProgressTrack = findViewById(R.id.player_progress_track);
        playerScrubMarker = findViewById(R.id.player_scrub_marker);
        stylePlayerScrubMarker();
        if (playerProgressTrack != null) {
            playerProgressTrack.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            updatePlayerScrubMarkerPosition();
                        }
                    });
        }
        tvPlayerTrackCount = findViewById(R.id.tv_player_track_count);
        playerStatusRow = findViewById(R.id.player_status_row);

        // 🚀 [수정 후]
        ivPlayerShuffleStatus = findViewById(R.id.iv_player_shuffle_status);
        ivPlayerRepeatStatus = findViewById(R.id.iv_player_repeat_status);
        updatePlayerStatusIndicators();

        try { statusBarShowsTitle = prefs.getBoolean("status_bar_title", false); } catch (Exception e) {}
        try { statusBarMatchFont = prefs.getBoolean("status_bar_match_font", true); } catch (Exception e) {}
        ThemeManager.setStatusBarMatchItemText(statusBarMatchFont);
        try { soulseekHideFlac = prefs.getBoolean(SoulseekAccount.PREF_HIDE_FLAC, true); } catch (Exception e) {}
        updateStatusBarTitle();

        btnScanBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBluetoothScan();
            }
        });
        btnScanWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWifiScan();
            }
        });

        clockHandler.post(clockTask);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(systemStatusReceiver, filter);

        try {
            if (audioManager.isWiredHeadsetOn()) {
                ivStatusHeadphone.setVisibility(View.VISIBLE);
                applyThemedStatusIcon(ivStatusHeadphone, "headsetWithMic", "headsetWithoutMic",
                        R.drawable.ic_headphone, 0xFF00FFFF);
            }
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.isEnabled()) {
                ivStatusBluetooth.setVisibility(View.VISIBLE);
                applyThemedStatusIcon(ivStatusBluetooth, "blConnected", null,
                        R.drawable.ic_bluetooth, 0xFF5555FF);
                ensureA2dpProfile();
                reconnectLastBluetoothAudio();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                ivStatusWifi.setVisibility(View.VISIBLE);
                refreshWifiStatusIcon();
            }
        } catch (Exception e) {
        }

        requestFirstHomeMenuFocus();


        // 🚀 1. 메인 화면의 배경과 글자색도 테마 매니저에 맞춰 갈아입힙니다!
        applyThemeToMainMenu();

        triggerAutoReconnect();

        // 🚀 2. 테마를 바꾸고 화면이 새로고침(recreate)되었을 때, 메인 화면이 아닌 '테마 선택 리스트'로 돌아오게 만듭니다!
        boolean rebootToTheme = prefs.getBoolean("reboot_to_theme", false);
        if (rebootToTheme) {
            prefs.edit().remove("reboot_to_theme").commit(); // 기억을 사용했으니 지웁니다.
            changeScreen(STATE_SETTINGS);
            buildThemeSelectorUI(); // 테마 리스트 화면을 바로 띄워줍니다.
        } else {
            requestFirstHomeMenuFocus(); // 평소 앱을 켤 때는 원래대로 메인 메뉴 포커스
        }

        final String soulseekTest = getIntent().getStringExtra("soulseek_test");
        if (soulseekTest != null && soulseekTest.length() > 0) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    openSoulseekScreen();
                    fetchSoulseekResults(soulseekTest);
                }
            }, 4000);
        }
        final String soulseekAutoDownload = getIntent().getStringExtra("soulseek_auto_download");
        if (soulseekAutoDownload != null && soulseekAutoDownload.length() > 0) {
            soulseekAutoDownloadPending = true;
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    openSoulseekScreen();
                    fetchSoulseekResults(soulseekAutoDownload);
                }
            }, 4000);
        }
        final String themeAutoVariant = getIntent().getStringExtra("theme_auto_variant");
        if (themeAutoVariant != null && themeAutoVariant.contains("::")) {
            getIntent().removeExtra("theme_auto_variant");
            final String[] parts = themeAutoVariant.split("::", 2);
            final String themeName = parts[0].trim();
            final String variantSlug = parts[1].trim();
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    changeScreen(STATE_SETTINGS);
                    setSettingsSubScreen(SettingsScreens.THEMES);
                    updateStatusBarTitle();
                    prepareThemeGalleryPreviewPane();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                List<ThemeDownloader.CatalogEntry> catalog = ThemeDownloader.fetchCatalog();
                                ThemeDownloader.CatalogEntry entry = null;
                                for (ThemeDownloader.CatalogEntry e : catalog) {
                                    if (themeName.equalsIgnoreCase(e.name)
                                            || themeName.equalsIgnoreCase(e.folder)
                                            || themeName.replace(" ", "").equalsIgnoreCase(
                                                    e.name.replace(" ", ""))) {
                                        entry = e;
                                        break;
                                    }
                                }
                                if (entry == null) throw new Exception("theme not in catalog: " + themeName);
                                List<ThemeDownloader.ThemeVariant> variants =
                                        ThemeDownloader.fetchReachableVariants(entry);
                                ThemeDownloader.ThemeVariant pick = null;
                                for (ThemeDownloader.ThemeVariant v : variants) {
                                    if (variantSlug.equalsIgnoreCase(v.folderSlug)
                                            || variantSlug.equalsIgnoreCase(v.label)) {
                                        pick = v;
                                        break;
                                    }
                                }
                                if (pick == null) throw new Exception("variant not found: " + variantSlug);
                                final ThemeDownloader.CatalogEntry fe = entry;
                                final ThemeDownloader.ThemeVariant fv = pick;
                                runOnUiThreadSafe(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing()) return;
                                        applyThemeCatalog(catalog);
                                        buildUnifiedThemesUI();
                                        downloadAndApplyThemeVariant(fe, fv);
                                    }
                                });
                            } catch (final Exception e) {
                                ThemeDownloader.dlLogError("theme_auto_variant", e);
                            }
                        }
                    }).start();
                }
            }, 4000);
        }
    }
    // 💡 [추가] 화면 전체를 덮는 확실한 로딩 팝업 띄우기 함수
    private void showLoadingPopup() {
        if (layoutLoadingOverlay != null) {
            layoutLoadingOverlay.setVisibility(View.VISIBLE);

            // 🚀 스캔이 끝날 때까지 감시하다가, 끝나면 자동으로 팝업을 닫고 휠을 풀어줍니다!
            final Handler checker = new Handler();
            checker.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCustomScanning) {
                        layoutLoadingOverlay.setVisibility(View.GONE);
                    } else {
                        checker.postDelayed(this, 200); // 0.2초마다 검사
                    }
                }
            });
        }
    }

    private void setBlockingLoading(boolean visible) {
        if (layoutLoadingOverlay != null) {
            layoutLoadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setLoadingOverlayText(String text) {
        if (tvLoadingOverlayText != null) {
            tvLoadingOverlayText.setText(text);
        }
    }

    private void runOnUiThreadSafe(Runnable r) {
        if (isFinishing()) return;
        runOnUiThread(r);
    }
    // 💡 [반응형 업그레이드] 위젯 개수에 따라 크기와 여백이 스스로 변하는 스마트 위젯 엔진!
    private void refreshWidgets() {
        // 1. 현재 켜져 있는 위젯의 개수를 계산합니다.
        int activeCount = 0;
        if (isWidgetClockOn) activeCount++;
        if (isWidgetBatteryOn) activeCount++;
        if (isWidgetAlbumOn) activeCount++;

        boolean anyWidgetActive = activeCount > 0;

        if (anyWidgetActive) {
            ivMenuPreview.setVisibility(View.GONE);
            if (tvMenuPreviewTitle != null) tvMenuPreviewTitle.setVisibility(View.GONE);
            if (tvMenuPreviewArtist != null) tvMenuPreviewArtist.setVisibility(View.GONE);

            layoutWidgets.setVisibility(View.VISIBLE);

            float d = getResources().getDisplayMetrics().density;

            // 🚀 동적 레이아웃 변수들
            int clockPadding = 0, batteryWidth = 0, batteryHeight = 0, batteryMargin = 0;
            int albumSize = 0, titleSize = 0, artistSize = 0;
            float dateScale = 1.0f, timeScale = 1.0f;

            // 💡 프로필 1: 위젯이 1개일 때 (단독 표시 모드)
            if (activeCount == 1) {
                // 🚀 시계 크기를 이전보다 살짝 줄여서(2.6 -> 2.1) 부담스럽지 않게 다듬습니다.
                clockPadding = 0; dateScale = 1.0f; timeScale = 2.1f;

                batteryWidth = 180; batteryHeight = 40; batteryMargin = 0;

                // 🚀 앨범 아트와 글자 크기를 기본 화면(XML 기본 규격)과 완전히 동일하게 맞춥니다!
                albumSize = 140; titleSize = 18; artistSize = 14;
            }
            // 💡 프로필 2: 위젯이 2개일 때 (여유로운 중간 크기)
            else if (activeCount == 2) {
                clockPadding = (int)(20 * d); dateScale = 0.8f; timeScale = 1.6f;
                batteryWidth = 140; batteryHeight = 25; batteryMargin = (int)(20 * d);
                albumSize = 130; titleSize = 18; artistSize = 14;
            }
            // 💡 프로필 3: 위젯이 3개일 때 (서로 양보하는 아담한 크기)
            else {
                clockPadding = (int)(10 * d); dateScale = 0.6f; timeScale = 1.2f;
                batteryWidth = 100; batteryHeight = 16; batteryMargin = (int)(10 * d);
                albumSize = 85; titleSize = 14; artistSize = 12;
            }

            // --- [1. 시계 위젯 세팅] ---
            tvWidgetClock.setVisibility(isWidgetClockOn ? View.VISIBLE : View.GONE);
            if (isWidgetClockOn) {
                tvWidgetClock.setPadding(0, 0, 0, clockPadding);
                String dateStr = new java.text.SimpleDateFormat("EEE, MMM dd", Locale.US).format(new Date());
                String timeStr = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new Date());
                String fullStr = timeStr + "\n" + dateStr;

                android.text.SpannableString spannable = new android.text.SpannableString(fullStr);
                spannable.setSpan(new android.text.style.RelativeSizeSpan(timeScale), 0, timeStr.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.RelativeSizeSpan(dateScale), timeStr.length() + 1, fullStr.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                tvWidgetClock.setText(spannable);
                tvWidgetClock.setTextColor(ThemeManager.getTextColorPrimary());
                tvWidgetClock.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            }

            // --- [2. 배터리 위젯 세팅] ---
            widgetBatteryView.setVisibility(isWidgetBatteryOn ? View.VISIBLE : View.GONE);
            if (isWidgetBatteryOn) {
                LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) widgetBatteryView.getLayoutParams();
                blp.width = (int)(batteryWidth * d);
                blp.height = (int)(batteryHeight * d);
                blp.bottomMargin = batteryMargin;
                widgetBatteryView.setLayoutParams(blp);
                widgetBatteryView.setColor(ThemeManager.getTextColorPrimary());
            }

            // --- [3. 앨범 위젯 세팅] ---
            ivWidgetAlbum.setVisibility(isWidgetAlbumOn ? View.VISIBLE : View.GONE);
            tvWidgetAlbumTitle.setVisibility(isWidgetAlbumOn ? View.VISIBLE : View.GONE);
            tvWidgetAlbumArtist.setVisibility(isWidgetAlbumOn ? View.VISIBLE : View.GONE);

            if (isWidgetAlbumOn) {
                LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) ivWidgetAlbum.getLayoutParams();
                alp.width = (int)(albumSize * d);
                alp.height = (int)(albumSize * d);
                ivWidgetAlbum.setLayoutParams(alp);

                tvWidgetAlbumTitle.setTextSize(titleSize);
                tvWidgetAlbumTitle.setTextColor(ThemeManager.getTextColorPrimary());
                tvWidgetAlbumTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                if (tvPlayerTitle != null) tvWidgetAlbumTitle.setText(tvPlayerTitle.getText());

                tvWidgetAlbumArtist.setTextSize(artistSize);
                tvWidgetAlbumArtist.setTextColor(ThemeManager.getTextColorSecondary());
                tvWidgetAlbumArtist.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
                if (tvPlayerArtist != null) tvWidgetAlbumArtist.setText(tvPlayerArtist.getText());

                if (lastAlbumArtBytes != null) {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        Bitmap bmp = BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
                        ivWidgetAlbum.setImageBitmap(bmp);
                    } catch (Exception e) {}
                } else {
                    ivWidgetAlbum.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", this, R.drawable.default_album));
                }
            }
        } else {
            layoutWidgets.setVisibility(View.GONE);
            updateHomeMenuPreviewVisibility();
            if (!isFullWidthMenus) {
                View focused = getCurrentFocus();
                if (focused != null && currentScreenState == STATE_MENU) {
                    if (isNowPlayingHomeFocused()) refreshNowPlayingPreview();
                    else updateHomeMenuPreview(focusedHomeMenuIndex);
                }
            }
        }
        if (currentScreenState == STATE_MENU) {
            updateScreenBackground(STATE_MENU);
        }
    }

    private boolean anyHomeWidgetActive() {
        return isWidgetClockOn || isWidgetBatteryOn || isWidgetAlbumOn;
    }
    private void hideFastScrollLetter() {
        fastScrollHandler.removeCallbacks(hideFastScrollTask);
        if (tvFastScrollLetter != null) tvFastScrollLetter.setVisibility(View.GONE);
    }

    // 💡 [추가] 문자열에서 첫 글자를 뽑아내어 화면에 띄워주는 함수
    private void showFastScrollLetter(String rawText) {
        // 브라우저 모드(리스트 화면)가 아니면 띄우지 않습니다.
        if (tvFastScrollLetter == null || (currentScreenState != STATE_BROWSER && currentScreenState != STATE_APPS && currentScreenState != STATE_MORE)) return;

        // 버튼 텍스트 앞에 붙어있는 꾸밈용 이모지들을 싹 지우고 순수 제목만 남깁니다.
        String clean = rawText.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "")
                .replace("📦 [INSTALL] ", "").trim();

        if (clean.isEmpty()) return;

        // 첫 글자 1개만 추출 (무조건 대문자로 변환)
        String firstChar = clean.substring(0, 1).toUpperCase();

        // 🚀 [그래픽 과부하 방지] 이미 화면에 떠 있는 알파벳과 '똑같은' 알파벳이라면?
        // 무거운 박스 그리기 작업을 생략하고 글자가 사라지는 타이머만 연장해 줍니다!
        if (tvFastScrollLetter.getVisibility() == View.VISIBLE && tvFastScrollLetter.getText().toString().equals(firstChar)) {
            fastScrollHandler.removeCallbacks(hideFastScrollTask);
            fastScrollHandler.postDelayed(hideFastScrollTask, 800);
            return; // 여기서 함수를 멈춰버립니다.
        }

        tvFastScrollLetter.setText(firstChar);

        // 🚀 현재 적용된 테마의 강조 색상으로 박스를 예쁘게 색칠합니다!
        tvFastScrollLetter.setTextColor(ThemeManager.getTextColorPrimary());
        tvFastScrollLetter.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable letterBg = new android.graphics.drawable.GradientDrawable();
        letterBg.setColor(ThemeManager.getListButtonFocusedBg() | 0xDD000000); // 살짝 반투명하게 덮기
        letterBg.setCornerRadius(15 * getResources().getDisplayMetrics().density); // 둥근 모서리
        tvFastScrollLetter.setBackground(letterBg);

        tvFastScrollLetter.setVisibility(View.VISIBLE);

        // 0.8초 동안 휠 조작이 없으면 글자가 자동으로 스르륵 사라지도록 타이머 리셋
        fastScrollHandler.removeCallbacks(hideFastScrollTask);
        fastScrollHandler.postDelayed(hideFastScrollTask, 800);
    }

    private void refreshBatteryStatus() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) updateBatteryUi(intent);
        } catch (Exception ignored) {}
    }

    private void updateBatteryUi(Intent intent) {
        if (tvStatusBattery == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        int batteryPct = scale > 0 ? (int) ((level / (float) scale) * 100) : 0;
        tvStatusBattery.setText(batteryPct + "%");

        int idx = ThemeManager.batteryLevelIndex(batteryPct);
        Bitmap themedBattery = ThemeManager.getBatteryIcon(idx, isCharging);
        if (themedBattery != null && ivStatusBatteryThemed != null) {
            int batH = y1BatteryIconHeightPx > 0 ? y1BatteryIconHeightPx
                    : (int) (20 * getResources().getDisplayMetrics().density);
            int bw = (int) (themedBattery.getWidth() * (batH / (float) themedBattery.getHeight()));
            if (bw < 1) bw = 1;
            Bitmap scaled = Bitmap.createScaledBitmap(themedBattery, bw, batH, true);
            ivStatusBatteryThemed.setImageBitmap(scaled);
            ivStatusBatteryThemed.setVisibility(View.VISIBLE);
            tvStatusBattery.setVisibility(View.VISIBLE);
            ThemeManager.applyThemedTextStyle(tvStatusBattery, ThemeManager.getStatusBarTextColor());
            if (batteryIconView != null) batteryIconView.setVisibility(View.GONE);
        } else {
            if (ivStatusBatteryThemed != null) ivStatusBatteryThemed.setVisibility(View.GONE);
            tvStatusBattery.setVisibility(View.VISIBLE);
            ThemeManager.applyThemedTextStyle(tvStatusBattery, ThemeManager.getStatusBarTextColor());
            if (batteryIconView != null) {
                batteryIconView.setVisibility(View.VISIBLE);
                batteryIconView.setBatteryLevel(batteryPct, isCharging);
            }
        }
        if (widgetBatteryView != null) {
            widgetBatteryView.setBatteryLevel(batteryPct, isCharging);
        }
        if (soulseekCharging != isCharging) {
            soulseekCharging = isCharging;
            updateSoulseekSharePolicy();
        }
    }

    private void applyMenuPanelBackground(FrameLayout host, int widthPx, int heightPx, int defaultHeightPx) {
        if (host == null) return;
        int h = heightPx > 0 ? heightPx : defaultHeightPx;
        int w = widthPx > 0 ? widthPx : (int) getResources().getDimension(R.dimen.y1_menu_width);
        android.graphics.drawable.Drawable panel =
                ThemeManager.getMenuPanelBackgroundScaled(getResources(), w, h);
        if (panel != null) {
            host.setBackground(panel);
        } else {
            host.setBackgroundColor(0x00000000);
        }
    }

    /** [arrowW, arrowH, labelRightMargin] from scaled themed arrow bitmap */
    private int[] y1ArrowLayout(Bitmap arrowBmp) {
        int marginEnd = (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
        if (arrowBmp == null) {
            int fallback = (int) (12 * getResources().getDisplayMetrics().density);
            return new int[] { fallback, y1RowHeightPx, fallback + marginEnd };
        }
        int w = arrowBmp.getWidth();
        int h = arrowBmp.getHeight();
        if (h > y1RowHeightPx) {
            float scale = y1RowHeightPx / (float) h;
            w = Math.max(1, (int) (w * scale));
            h = y1RowHeightPx;
        }
        return new int[] { w, h, w + marginEnd };
    }

    private void enableMarquee(TextView tv) {
        if (tv == null) return;
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tv.setMarqueeRepeatLimit(-1);
        tv.setHorizontallyScrolling(true);
    }

    private int y1RowKindForScreen() {
        return currentScreenState == STATE_SETTINGS ? Y1_ROW_MENU : Y1_ROW_ITEM;
    }

    private int y1RowTextColorNormal(int rowKind) {
        if (rowKind == Y1_ROW_HOME) return ThemeManager.getHomeMenuTextColorNormal();
        if (rowKind == Y1_ROW_MENU) return ThemeManager.getSettingMenuTextColorNormal();
        return ThemeManager.getItemTextColorNormal();
    }

    private int y1RowTextColorSelected(int rowKind) {
        if (rowKind == Y1_ROW_HOME) return ThemeManager.getHomeMenuTextColorSelected();
        if (rowKind == Y1_ROW_MENU) return ThemeManager.getSettingMenuTextColorSelected();
        return ThemeManager.getItemTextColorSelected();
    }

    private int y1InlineStateColor(String text, boolean rowFocused) {
        if ("ON".equals(text) || "ONE".equals(text) || "ALL".equals(text) || "OFF".equals(text)) {
            return rowFocused ? y1RowTextColorSelected(Y1_ROW_MENU) : y1RowTextColorNormal(Y1_ROW_MENU);
        }
        return rowFocused ? y1RowTextColorSelected(Y1_ROW_MENU) : ThemeManager.getTextColorSecondary();
    }

    private void styleSecondaryLabel(TextView tv) {
        if (tv != null) ThemeManager.applyThemedTextStyle(tv, ThemeManager.getHintTextColor());
    }

    private static final String TAG_REARRANGE_LABEL = "rearrange_label";
    private static final String TAG_REARRANGE_GRIP = "rearrange_grip";
    private static final String TAG_REARRANGE_ARROW = "rearrange_arrow";

    private int rearrangeRightSlotWidthPx() {
        int[] arrowLayout = y1ArrowLayout(ThemeManager.getScaledItemRightArrow(y1RowHeightPx));
        return arrowLayout[0] + (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
    }

    /** Settings-style row with fixed-width grip/arrow slot — avoids layout shift in rearrange UIs. */
    private LinearLayout createRearrangeListRow(String rowKey, CharSequence label,
            final View.OnFocusChangeListener onFocusExtra) {
        final LinearLayout layout = new LinearLayout(this);
        if (rowKey != null) layout.setTag(rowKey);
        layout.setSoundEffectsEnabled(false);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setFocusable(true);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        layout.setPadding(hPad, 0, hPad, 0);
        final int rowKind = Y1_ROW_MENU;
        int rowW = y1ActiveRowWidthPx();
        layout.setBackground(getY1RowBackground(false, rowW, rowKind));

        TextView tvLeft = new TextView(this);
        tvLeft.setTag(TAG_REARRANGE_LABEL);
        tvLeft.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvLeft.setText(label);
        ThemeManager.applyThemedTextStyle(tvLeft, ThemeManager.getSettingMenuTextColorNormal());
        tvLeft.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        tvLeft.setFocusable(false);
        enableMarquee(tvLeft);
        tvLeft.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        layout.addView(tvLeft);

        android.widget.FrameLayout rightSlot = new android.widget.FrameLayout(this);
        int slotW = rearrangeRightSlotWidthPx();
        rightSlot.setLayoutParams(new LinearLayout.LayoutParams(slotW, y1RowHeightPx));

        TextView tvGrip = new TextView(this);
        tvGrip.setTag(TAG_REARRANGE_GRIP);
        tvGrip.setFocusable(false);
        tvGrip.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvGrip.setText(getString(R.string.home_screen_move_grip));
        tvGrip.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        tvGrip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        tvGrip.setVisibility(View.INVISIBLE);
        rightSlot.addView(tvGrip, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        final ImageView rowArrow = new ImageView(this);
        rowArrow.setTag(TAG_REARRANGE_ARROW);
        rowArrow.setFocusable(false);
        rowArrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(y1RowHeightPx);
        int[] arrowLayout = y1ArrowLayout(arrowBmp);
        if (arrowBmp != null) rowArrow.setImageBitmap(arrowBmp);
        android.widget.FrameLayout.LayoutParams arrowLp = new android.widget.FrameLayout.LayoutParams(
                arrowLayout[0], arrowLayout[1]);
        arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
        rowArrow.setVisibility(View.INVISIBLE);
        rightSlot.addView(rowArrow, arrowLp);
        layout.addView(rightSlot);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        layout.setLayoutParams(lp);

        layout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                int w = y1ActiveRowWidthPx();
                layout.setBackground(getY1RowBackground(hasFocus, w, rowKind));
                ThemeManager.applyThemedTextStyle(tvLeft, hasFocus
                        ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                tvLeft.setSelected(hasFocus);
                if (hasFocus) enableMarquee(tvLeft);
                if (onFocusExtra != null) onFocusExtra.onFocusChange(v, hasFocus);
            }
        });
        return layout;
    }

    private void bindRearrangeRowAdornment(LinearLayout layout, boolean moving, boolean focused) {
        if (layout == null) return;
        TextView grip = (TextView) layout.findViewWithTag(TAG_REARRANGE_GRIP);
        ImageView arrow = (ImageView) layout.findViewWithTag(TAG_REARRANGE_ARROW);
        if (moving) {
            if (grip != null) {
                grip.setVisibility(View.VISIBLE);
                ThemeManager.applyThemedTextStyle(grip, ThemeManager.getSettingMenuTextColorSelected());
            }
            if (arrow != null) arrow.setVisibility(View.INVISIBLE);
        } else {
            if (grip != null) grip.setVisibility(View.INVISIBLE);
            if (arrow != null) {
                arrow.setVisibility(focused && arrow.getDrawable() != null
                        ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void refreshHomeArrangeMoveUi(boolean moreMenu) {
        String moveId = moreMenu ? homeMoreMoveModeId : homeScreenMoveModeId;
        for (int i = 0; i < containerSettingsItems.getChildCount(); i++) {
            View v = containerSettingsItems.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            Object tag = v.getTag();
            if (!(tag instanceof String)) continue;
            String key = (String) tag;
            if (!key.startsWith("home.shortcut.")) continue;
            String id = key.substring("home.shortcut.".length());
            bindRearrangeRowAdornment((LinearLayout) v, id.equals(moveId), v.hasFocus());
        }
    }

    private void reorderHomeArrangeRows(boolean moreMenu) {
        List<String> ids = moreMenu ? HomeMenuConfig.loadMoreOrderIds(prefs)
                : HomeMenuConfig.loadHomeOrderIds(prefs);
        java.util.HashMap<String, View> rowByKey = new java.util.HashMap<String, View>();
        View moreRow = null;
        for (int i = 1; i < containerSettingsItems.getChildCount(); i++) {
            View v = containerSettingsItems.getChildAt(i);
            Object tag = v.getTag();
            if (!(tag instanceof String)) continue;
            String key = (String) tag;
            if (RowKeys.HOME_MORE.equals(key)) {
                moreRow = v;
            } else if (key.startsWith("home.shortcut.")) {
                rowByKey.put(key, v);
            }
        }
        while (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.removeViewAt(1);
        }
        for (String id : ids) {
            HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
            if (e == null) continue;
            View row = rowByKey.get(RowKeys.homeShortcut(id));
            if (row != null) containerSettingsItems.addView(row);
        }
        if (!moreMenu && moreRow != null) containerSettingsItems.addView(moreRow);
    }

    private int keyboardRowKind() {
        if (keyboardPurpose == KEYBOARD_WIFI || keyboardReturnState == STATE_SETTINGS) {
            return Y1_ROW_MENU;
        }
        return Y1_ROW_ITEM;
    }

    private void applyKeyboardTheme() {
        if (tvKeyboardSsid == null || tvKeyboardInput == null || tvKeyCurrent == null) return;
        ThemeManager.applyThemedTextStyle(tvKeyboardSsid, ThemeManager.getSectionHeaderTextColor());
        float menuTextPx = getResources().getDimension(R.dimen.y1_menu_text_size);
        int keyPad = (int) (6 * getResources().getDisplayMetrics().density);
        int layoutPad = (int) (15 * getResources().getDisplayMetrics().density);
        int inputRowW = listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx();
        if (isFullWidthMenus && screenWidthPx > layoutPad * 2) {
            inputRowW = screenWidthPx - layoutPad * 2;
        }
        tvKeyboardInput.setMinHeight(y1RowHeightPx);
        tvKeyboardInput.setPadding(keyPad, 0, keyPad, 0);
        tvKeyboardInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyboardInput.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvKeyboardInput.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.CENTER_HORIZONTAL);
        enableMarquee(tvKeyboardInput);
        int keyW = (int) (52 * getResources().getDisplayMetrics().density);
        tvKeyCurrent.setPadding(keyPad, keyPad / 2, keyPad, keyPad / 2);
        tvKeyCurrent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyCurrent.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvKeyPrev.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyNext.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyPprev.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyNnext.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        tvKeyPrev.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvKeyNext.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvKeyPprev.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvKeyNnext.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        ThemeManager.applyThemedTextStyle(tvKeyPrev, y1RowTextColorNormal(keyboardRowKind()));
        ThemeManager.applyThemedTextStyle(tvKeyNext, y1RowTextColorNormal(keyboardRowKind()));
        ThemeManager.applyThemedTextStyle(tvKeyPprev, ThemeManager.getDimmedTextColor(0x55));
        ThemeManager.applyThemedTextStyle(tvKeyNnext, ThemeManager.getDimmedTextColor(0x55));
        tvKeyPrev.setBackgroundColor(0x00000000);
        tvKeyNext.setBackgroundColor(0x00000000);
        tvKeyPprev.setBackgroundColor(0x00000000);
        tvKeyNnext.setBackgroundColor(0x00000000);
        if (tvKeyboardHint != null) {
            tvKeyboardHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.85f);
            tvKeyboardHint.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
            ThemeManager.applyThemedTextStyle(tvKeyboardHint, ThemeManager.getHintTextColor());
        }
        styleKeyboardInputField(typedPassword.length() == 0 && !isKeyboardInputPlaceholderForced());
        styleKeyboardCurrentKey();
        if (currentScreenState == STATE_WIFI_KEYBOARD) updateKeyboardUI();
    }

    private boolean isKeyboardInputPlaceholderForced() {
        return keyboardPurpose == KEYBOARD_WIFI && isTargetWifiOpen;
    }

    private void styleKeyboardInputField(boolean placeholder) {
        if (tvKeyboardInput == null) return;
        int rowKind = keyboardRowKind();
        int layoutPad = (int) (15 * getResources().getDisplayMetrics().density);
        int inputRowW = listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx();
        if (isFullWidthMenus && screenWidthPx > layoutPad * 2) {
            inputRowW = screenWidthPx - layoutPad * 2;
        }
        tvKeyboardInput.setBackground(getY1RowBackground(true, inputRowW, rowKind));
        tvKeyboardInput.setSelected(true);
        ThemeManager.applyThemedTextStyle(tvKeyboardInput, placeholder
                ? ThemeManager.getHintTextColor()
                : y1RowTextColorSelected(rowKind));
    }

    private void styleKeyboardCurrentKey() {
        if (tvKeyCurrent == null) return;
        int rowKind = keyboardRowKind();
        int keyW = (int) (52 * getResources().getDisplayMetrics().density);
        tvKeyCurrent.setBackground(getY1RowBackground(true, keyW, rowKind));
        tvKeyCurrent.setSelected(true);
        ThemeManager.applyThemedTextStyle(tvKeyCurrent, y1RowTextColorSelected(rowKind));
    }

    private void applyOverlayScreenTextThemes() {
        int hint = ThemeManager.getHintTextColor();
        int primary = ThemeManager.getTextColorPrimary();
        if (tvWebserverHint != null) ThemeManager.applyThemedTextStyle(tvWebserverHint, hint);
        if (tvKeyboardHint != null) ThemeManager.applyThemedTextStyle(tvKeyboardHint, hint);
        if (tvBrightnessHint != null) ThemeManager.applyThemedTextStyle(tvBrightnessHint, hint);
        if (tvStorageHint != null) ThemeManager.applyThemedTextStyle(tvStorageHint, hint);
        if (tvBrightnessVal != null) ThemeManager.applyThemedTextStyle(tvBrightnessVal, primary);
        if (tvStorageDetails != null) ThemeManager.applyThemedTextStyle(tvStorageDetails, primary);
    }

    private void applyThemeToMainMenu() {
        try {
            // 🚀 1. 가장 핵심! 전체 배경 이미지(ivMainBg) 위에 테마 색상을 셀로판지처럼 덮어씌웁니다.
            if (ivMainBg != null) {
                if (!hasEffectiveWallpaper()) {
                    int themeColor = ThemeManager.getOverlayBackgroundColor();
                    int softTint = (themeColor & 0x00FFFFFF) | 0x66000000;
                    ivMainBg.setColorFilter(softTint, android.graphics.PorterDuff.Mode.SRC_ATOP);
                } else {
                    ivMainBg.clearColorFilter();
                }
            }

            View statusBar = findViewById(R.id.layout_status_bar);
            applyStatusBarTheme();
            applyMenuPanelBackground(menuListHost, y1RowWidthPx, menuListHeightPx,
                    (int) getResources().getDimension(R.dimen.y1_menu_height));
            int settingsPanelH = isFullWidthMenus && screenHeightPx > 0
                    ? screenHeightPx - (int) getResources().getDimension(R.dimen.y1_status_bar_height)
                    : (int) getResources().getDimension(R.dimen.y1_settings_menu_height);
            applyMenuPanelBackground(settingsMenuHost, settingsMenuWidthPx, settingsPanelH,
                    (int) getResources().getDimension(R.dimen.y1_settings_menu_height));
            int primary = ThemeManager.getTextColorPrimary();
            int secondary = ThemeManager.getTextColorSecondary();
            int statusTextColor = ThemeManager.getStatusBarTextColor();

            applyAllHomeMenuRowStyles();
            if (currentScreenState == STATE_SETTINGS) applyAllSettingsRowStyles();
            refreshY1ThemedActionButtons();
            applyKeyboardTheme();
            applyOverlayScreenTextThemes();

            if (tvMenuPreviewTitle != null) {
                ThemeManager.applyThemedTextStyle(tvMenuPreviewTitle, primary);
                enableMarquee(tvMenuPreviewTitle);
                tvMenuPreviewTitle.setSelected(true);
            }
            if (tvMenuPreviewArtist != null) {
                ThemeManager.applyThemedTextStyle(tvMenuPreviewArtist, secondary);
                enableMarquee(tvMenuPreviewArtist);
                tvMenuPreviewArtist.setSelected(true);
            }
            if (tvStatusClock != null) ThemeManager.applyThemedTextStyle(tvStatusClock, statusTextColor);
            if (tvBrowserPath != null) {
                ThemeManager.applyThemedTextStyle(tvBrowserPath, primary);
                enableMarquee(tvBrowserPath);
                tvBrowserPath.setSelected(true);
            }
            if (tvPodcastPreviewShow != null) {
                ThemeManager.applyThemedTextStyle(tvPodcastPreviewShow, primary);
            }
            if (tvPodcastPreviewEpisode != null) {
                ThemeManager.applyThemedTextStyle(tvPodcastPreviewEpisode, secondary);
            }
            if (tvPodcastPreviewMeta != null) {
                ThemeManager.applyThemedTextStyle(tvPodcastPreviewMeta, secondary);
            }
            if (batteryIconView != null) batteryIconView.setColor(primary);
            updatePlaybackStatusIcon();
            refreshBatteryStatus();
            refreshWifiStatusIcon();
            updateScreenBackground(currentScreenState);
            int themeColor = ThemeManager.getProgressColor();
            int progressBgColor = ThemeManager.getProgressBackgroundColor();

            // 1. 플레이어 화면의 음악 재생 바
            if (playerProgress != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) playerProgress.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    android.graphics.drawable.Drawable bg = layer.findDrawableByLayerId(android.R.id.background);
                    if (progress != null) progress.setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                    if (bg != null) bg.setColorFilter(progressBgColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) {
                    playerProgress.getProgressDrawable().setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                }
                stylePlayerScrubMarker();
            }

            // 2. 휠 돌릴 때 나오는 볼륨 조절 바
            if (volumeProgress != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) volumeProgress.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) {
                    volumeProgress.getProgressDrawable().setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }

            // 3. 설정 화면의 화면 밝기 조절 바
            if (pbBrightness != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) pbBrightness.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) {
                    pbBrightness.getProgressDrawable().setColorFilter(themeColor, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
            android.view.ViewGroup root = findViewById(android.R.id.content);
            applyFontToAllViews(root, ThemeManager.getCustomFont());
            applyPlayerInfoStyle();
        } catch (Exception e) {}
    }

    /** ponytail: Now Playing title/artist bars — solarConfig.nowPlayingInfoBars + nowPlayingTextColour */
    private void applyPlayerInfoStyle() {
        try {
            int infoW = (int) getResources().getDimension(R.dimen.y1_player_info_width);
            int textColor = ThemeManager.getNowPlayingTextColor();
            int progressText = ThemeManager.getProgressTextColor();
            android.graphics.Typeface font = ThemeManager.getCustomFont();

            applyNowPlayingInfoRow(tvPlayerTitle, infoW, 42, textColor, font);
            applyNowPlayingInfoRow(tvPlayerArtist, infoW, 38, textColor, font);
            applyNowPlayingInfoRow(tvPlayerTrackCount, infoW, 38, textColor, font);
            if (playerStatusRow != null) {
                float d = getResources().getDisplayMetrics().density;
                int h = (int) (38 * d);
                playerStatusRow.setBackground(ThemeManager.getNowPlayingInfoBarBackground(getResources(), infoW, h));
            }
            applyNowPlayingInfoRow(tvVizTitle, infoW, 28, textColor, font);
            applyNowPlayingInfoRow(tvVizArtist, infoW, 24, textColor, font);
            applyNowPlayingInfoRow(tvVizTrackCount, infoW, 22, textColor, font);
            refreshPlayerMarquee();

            if (tvPlayerTimeCurrent != null) {
                ThemeManager.applyThemedTextStyle(tvPlayerTimeCurrent, progressText);
                tvPlayerTimeCurrent.setTypeface(font, android.graphics.Typeface.BOLD);
            }
            if (tvPlayerTimeTotal != null) {
                ThemeManager.applyThemedTextStyle(tvPlayerTimeTotal, progressText);
                tvPlayerTimeTotal.setTypeface(font, android.graphics.Typeface.BOLD);
            }
        } catch (Exception ignored) {}
    }

    private void refreshPlayerMarquee() {
        enableMarquee(tvPlayerTitle);
        enableMarquee(tvPlayerArtist);
        enableMarquee(tvPlayerTrackCount);
        if (tvPlayerTitle != null) tvPlayerTitle.setSelected(true);
        if (tvPlayerArtist != null) tvPlayerArtist.setSelected(true);
        if (tvPlayerTrackCount != null) tvPlayerTrackCount.setSelected(true);
    }

    private void applyNowPlayingInfoRow(TextView tv, int widthPx, int heightDp, int textColor,
                                        android.graphics.Typeface font) {
        if (tv == null) return;
        ThemeManager.applyThemedTextStyle(tv, textColor);
        if (font != null) tv.setTypeface(font, android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        int h = (int) (heightDp * d);
        tv.setBackground(ThemeManager.getNowPlayingInfoBarBackground(getResources(), widthPx, h));
        enableMarquee(tv);
        tv.setSelected(true);
    }
    // 💡 [추가] 테마 리스트를 쫙 보여주고 사용자가 고를 수 있게 하는 전용 화면
    private void buildThemeSelectorUI() {
        buildUnifiedThemesUI();
    }

    private void buildUnifiedThemesUI() {
        hideThemeGalleryInterstitial();
        clearThemeGalleryPreview();
        prepareThemeGalleryPreviewPane();
        themeBrowserMode = THEME_BROWSER_MAIN;
        themeBrowserParentMode = THEME_BROWSER_MAIN;
        themeVariantEntry = null;
        themeVariantList = null;
        setSettingsSubScreen(SettingsScreens.THEMES);
        updateStatusBarTitle();
        setThemesListVisible(true);
        containerSettingsItems.removeAllViews();
        if (listThemes != null) listThemes.bringToFront();
        final int gen = ++unifiedThemesUiGen;
        themeBrowserOnlineRows.clear();
        themeCatalogAvailable = false;
        themeCatalogLoading = false;
        themeCatalogError = null;
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        applySettingsListLayout();
        scrollThemesToListPos(Math.max(1, themeBrowserFocus));
        new Thread(new Runnable() {
            @Override
            public void run() {
                ThemeManager.rescanInstalled(MainActivity.this);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != unifiedThemesUiGen) return;
                        if (themeBrowserMode == THEME_BROWSER_MAIN) rebuildThemeBrowserRows();
                        else if (themeBrowserMode == THEME_BROWSER_GET_MORE) rebuildThemeBrowserRows();
                        refreshThemeBrowserList();
                    }
                });
            }
        }, "ThemesInstalledRescan").start();
    }

    private void openThemeGetMoreBrowser() {
        if (!requireInternet(R.string.theme_gallery_wifi_required)) return;
        themeBrowserMode = THEME_BROWSER_GET_MORE;
        themeBrowserOnlineRows.clear();
        themeCatalogAvailable = false;
        themeCatalogLoading = true;
        themeCatalogError = null;
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        applySettingsListLayout();
        scrollThemesToListPos(1);
        startThemeBrowserBackgroundLoad(++unifiedThemesUiGen);
    }

    private int themeListPageCapacityRows() {
        int rowH = y1RowHeightPx > 0 ? y1RowHeightPx : (int) (36 * getResources().getDisplayMetrics().density);
        int statusH = (int) getResources().getDimension(R.dimen.y1_status_bar_height);
        int listH = isFullWidthMenus && screenHeightPx > statusH
                ? screenHeightPx - statusH
                : (int) getResources().getDimension(R.dimen.y1_settings_menu_height);
        return Math.max(1, listH / Math.max(1, rowH));
    }

    private void startThemeBrowserBackgroundLoad(final int gen) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ThemeManager.rescanInstalled(MainActivity.this);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != unifiedThemesUiGen) return;
                        if (themeBrowserMode == THEME_BROWSER_MAIN) rebuildThemeBrowserRows();
                        else if (themeBrowserMode == THEME_BROWSER_GET_MORE) rebuildThemeBrowserRows();
                        refreshThemeBrowserList();
                    }
                });
                if (themeBrowserMode != THEME_BROWSER_GET_MORE
                        || !ConnectivityHelper.isOnline(MainActivity.this)) {
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            themeCatalogLoading = false;
                            if (themeBrowserMode == THEME_BROWSER_GET_MORE) rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                    return;
                }
                List<ThemeDownloader.CatalogEntry> cached = ThemeDownloader.loadCachedCatalog();
                if (cached != null && !cached.isEmpty()) {
                    final List<ThemeDownloader.CatalogEntry> cachedFinal = cached;
                    final List<ThemeBrowser.Row> onlineRows = ThemeBrowser.buildOnlineRows(
                            cachedFinal, ThemeManager.availableThemes, themeFilterMode, themeSortMode);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            themeGalleryCatalog = cachedFinal;
                            themeCatalogAvailable = true;
                            themeCatalogLoading = false;
                            themeBrowserOnlineRows.clear();
                            themeBrowserOnlineRows.addAll(onlineRows);
                            rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                }
                try {
                    final List<ThemeDownloader.CatalogEntry> catalog = ThemeDownloader.fetchCatalog();
                    final List<ThemeBrowser.Row> onlineRows = ThemeBrowser.buildOnlineRows(
                            catalog, ThemeManager.availableThemes, themeFilterMode, themeSortMode);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            applyThemeCatalog(catalog);
                            themeBrowserOnlineRows.clear();
                            themeBrowserOnlineRows.addAll(onlineRows);
                            rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                } catch (final Exception e) {
                    ThemeDownloader.dlLogError("fetchCatalog UI", e);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            themeCatalogLoading = false;
                            if (!themeCatalogAvailable) {
                                themeCatalogError = getString(R.string.loading_themes_failed);
                            }
                            rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                }
            }
        }, "UnifiedThemesLoad").start();
    }

    private void scheduleThemeOnlineRowRebuild() {
        if (themeBrowserMode != THEME_BROWSER_GET_MORE && themeBrowserMode != THEME_BROWSER_VARIANT) {
            return;
        }
        if (themeGalleryCatalog == null || !themeCatalogAvailable || themeCatalogLoading) return;
        final int gen = unifiedThemesUiGen;
        final List<ThemeDownloader.CatalogEntry> catalog = themeGalleryCatalog;
        final int filter = themeFilterMode;
        final int sort = themeSortMode;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ThemeBrowser.Row> onlineRows = ThemeBrowser.buildOnlineRows(
                        catalog, ThemeManager.availableThemes, filter, sort);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != unifiedThemesUiGen) return;
                        themeBrowserOnlineRows.clear();
                        themeBrowserOnlineRows.addAll(onlineRows);
                        rebuildThemeBrowserRows();
                        refreshThemeBrowserList();
                    }
                });
            }
        }, "ThemeOnlineRows").start();
    }

    private void applyThemeCatalog(List<ThemeDownloader.CatalogEntry> catalog) {
        themeGalleryCatalog = catalog;
        themeCatalogLoading = false;
        themeCatalogAvailable = catalog != null && !catalog.isEmpty();
        themeCatalogError = null;
    }

    private ThemeBrowser.UiText themeBrowserText() {
        ThemeBrowser.UiText t = new ThemeBrowser.UiText();
        t.filterTitle = themeFilterLine();
        t.sortSubtitle = themeSortLine();
        t.installedSection = getString(R.string.themes_section_installed);
        t.onlineSection = getString(R.string.themes_section_online);
        t.loading = getString(R.string.loading_theme_gallery);
        t.noInstalledMatch = getString(R.string.theme_no_installed_match);
        t.noOnlineMatch = getString(R.string.theme_no_online_match);
        t.noInternet = getString(R.string.theme_no_internet);
        t.noThemes = getString(R.string.theme_no_themes);
        t.noVariants = getString(R.string.theme_no_variants);
        t.getMore = getString(R.string.themes_get_more);
        return t;
    }

    private String themeFilterLine() {
        switch (themeFilterMode) {
            case ThemeBrowser.FILTER_INSTALLED: return getString(R.string.theme_filter_installed);
            case ThemeBrowser.FILTER_ONLINE: return getString(R.string.theme_filter_online);
            case ThemeBrowser.FILTER_UPDATES: return getString(R.string.theme_filter_updates);
            default: return getString(R.string.theme_filter_all);
        }
    }

    private String themeSortLine() {
        return themeSortMode == ThemeBrowser.SORT_AUTHOR
                ? getString(R.string.theme_sort_author) : getString(R.string.theme_sort_name);
    }

    private void rebuildThemeBrowserRows() {
        themeBrowserRows.clear();
        if (themeBrowserMode == THEME_BROWSER_VARIANT) {
            themeBrowserRows.addAll(ThemeBrowser.buildVariantRows(
                    themeVariantEntry, themeVariantList, themeBrowserText()));
        } else if (themeBrowserMode == THEME_BROWSER_GET_MORE) {
            themeBrowserRows.addAll(ThemeBrowser.buildGetMoreRows(
                    themeGalleryCatalog, ThemeManager.availableThemes,
                    themeFilterMode, themeSortMode,
                    themeCatalogLoading, themeCatalogAvailable, themeCatalogError,
                    themeBrowserOnlineRows.isEmpty() ? null : themeBrowserOnlineRows,
                    themeBrowserText()));
        } else {
            themeBrowserRows.addAll(ThemeBrowser.buildInstalledRows(
                    ThemeManager.availableThemes, ThemeManager.getCurrentThemeIndex(),
                    ConnectivityHelper.isOnline(this), themeListPageCapacityRows(),
                    themeBrowserText()));
        }
    }

    private void setThemesListVisible(boolean visible) {
        if (settingsScrollView != null) {
            settingsScrollView.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        if (listThemes != null) {
            listThemes.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) {
            ++unifiedThemesUiGen;
            themeCatalogLoading = false;
            themeBrowserOnlineRows.clear();
            themeBrowserMode = THEME_BROWSER_MAIN;
            themeVariantEntry = null;
            themeVariantList = null;
        }
    }

    private void refreshThemeBrowserList() {
        if (themeUnifiedListAdapter == null) {
            themeUnifiedListAdapter = new ThemeUnifiedListAdapter();
        }
        listThemes.setAdapter(themeUnifiedListAdapter);
        themeUnifiedListAdapter.notifyDataSetChanged();
    }

    private void scrollThemesToListPos(final int listPos) {
        if (listThemes == null || listPos < 0) return;
        listThemes.setSelection(listPos);
        listThemes.post(new Runnable() {
            @Override
            public void run() {
                listThemes.setSelection(listPos);
                for (int i = 0; i < listThemes.getChildCount(); i++) {
                    View v = listThemes.getChildAt(i);
                    if (listThemes.getPositionForView(v) == listPos && v.isFocusable()) {
                        v.requestFocus();
                        break;
                    }
                }
            }
        });
    }

    private int themeBrowserListPosition() {
        if (listThemes == null) return -1;
        View focused = listThemes.getFocusedChild();
        if (focused != null) return listThemes.getPositionForView(focused);
        return listThemes.getSelectedItemPosition();
    }

    private ThemeBrowser.Row themeBrowserFocusedRow() {
        int pos = themeBrowserListPosition();
        if (pos < 0 || pos >= themeBrowserRows.size()) return null;
        return themeBrowserRows.get(pos);
    }

    private void themeBrowserGoBack() {
        if (themeBrowserMode == THEME_BROWSER_VARIANT) {
            exitThemeVariantBrowser();
        } else if (themeBrowserMode == THEME_BROWSER_GET_MORE) {
            ++unifiedThemesUiGen;
            themeCatalogLoading = false;
            themeBrowserOnlineRows.clear();
            themeBrowserMode = THEME_BROWSER_MAIN;
            rebuildThemeBrowserRows();
            refreshThemeBrowserList();
            applySettingsListLayout();
            scrollThemesToListPos(Math.max(1, themeBrowserFocus));
        } else {
            hideThemeGalleryInterstitial();
            clearThemeGalleryPreview();
            setThemesListVisible(false);
            returnToSettingsParent();
        }
    }

    private void exitThemeVariantBrowser() {
        themeBrowserMode = themeBrowserParentMode;
        themeVariantEntry = null;
        themeVariantList = null;
        setSettingsSubScreen(SettingsScreens.THEMES);
        updateStatusBarTitle();
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        scrollThemesToListPos(Math.max(1, themeBrowserFocus));
    }

    private void cycleThemeFilter() {
        themeFilterMode = ThemeBrowser.nextFilter(themeFilterMode);
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        scheduleThemeOnlineRowRebuild();
    }

    private void toggleThemeSort() {
        themeSortMode = ThemeBrowser.toggleSort(themeSortMode);
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        scheduleThemeOnlineRowRebuild();
    }

    private void refreshThemeCatalogFromNetwork() {
        if (!ConnectivityHelper.isOnline(this)) {
            Toast.makeText(this, getString(R.string.theme_gallery_wifi_required), Toast.LENGTH_SHORT).show();
            return;
        }
        themeCatalogLoading = true;
        themeCatalogError = null;
        themeBrowserOnlineRows.clear();
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
        final int gen = ++unifiedThemesUiGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<ThemeDownloader.CatalogEntry> catalog = ThemeDownloader.fetchCatalog();
                    final List<ThemeBrowser.Row> onlineRows = ThemeBrowser.buildOnlineRows(
                            catalog, ThemeManager.availableThemes, themeFilterMode, themeSortMode);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            applyThemeCatalog(catalog);
                            themeBrowserOnlineRows.clear();
                            themeBrowserOnlineRows.addAll(onlineRows);
                            rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                } catch (final Exception e) {
                    ThemeDownloader.dlLogError("refreshThemeCatalog", e);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != unifiedThemesUiGen) return;
                            themeCatalogLoading = false;
                            themeCatalogError = getString(R.string.loading_themes_failed);
                            rebuildThemeBrowserRows();
                            refreshThemeBrowserList();
                        }
                    });
                }
            }
        }, "ThemeCatalogRefresh").start();
    }

    private void onThemeBrowserRowClick(final ThemeBrowser.Row row) {
        if (row == null) return;
        switch (row.kind) {
            case ThemeBrowser.KIND_BACK:
                themeBrowserGoBack();
                break;
            case ThemeBrowser.KIND_GET_MORE:
                openThemeGetMoreBrowser();
                break;
            case ThemeBrowser.KIND_FILTER:
                cycleThemeFilter();
                break;
            case ThemeBrowser.KIND_INSTALLED:
                if (row.themeIndex >= 0 && row.themeIndex < ThemeManager.availableThemes.size()) {
                    ThemeManager.ThemeEntry theme = ThemeManager.availableThemes.get(row.themeIndex);
                    applyThemeWithIntegrityCheck(row.themeIndex, theme, null);
                }
                break;
            case ThemeBrowser.KIND_CATALOG:
                if (row.catalog != null) {
                    if (row.catalog.hasVariants()) {
                        openThemeVariantBrowser(row.catalog);
                    } else {
                        startThemeGalleryActivation(row.catalog, null);
                    }
                }
                break;
            case ThemeBrowser.KIND_VARIANT:
                if (row.catalog != null && row.variant != null) {
                    startThemeGalleryActivation(row.catalog, row.variant);
                }
                break;
            default:
                break;
        }
    }

    private void openThemeVariantBrowser(final ThemeDownloader.CatalogEntry entry) {
        hideThemeGalleryInterstitial();
        prepareThemeGalleryPreviewPane();
        themeBrowserParentMode = themeBrowserMode;
        themeBrowserMode = THEME_BROWSER_VARIANT;
        themeVariantEntry = entry;
        themeVariantList = null;
        setSettingsSubScreen(SettingsScreens.THEME_VARIANT, entry.name);
        updateStatusBarTitle();
        setThemesListVisible(true);
        containerSettingsItems.removeAllViews();
        themeBrowserRows.clear();
        themeBrowserRows.add(ThemeBrowser.backRow());
        themeBrowserRows.add(ThemeBrowser.sectionRow(entry.name));
        themeBrowserRows.add(ThemeBrowser.statusRow(getString(R.string.loading_variants)));
        refreshThemeBrowserList();
        scrollThemesToListPos(1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ThemeDownloader.ThemeVariant> variants =
                        ThemeDownloader.fetchReachableVariants(entry);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (variants == null || variants.isEmpty()) {
                            if (!ThemeDownloader.hasVariantSubfolders(entry)) {
                                downloadAndApplyTheme(entry);
                                return;
                            }
                        }
                        themeVariantList = variants;
                        rebuildThemeBrowserRows();
                        refreshThemeBrowserList();
                    }
                });
            }
        }, "ThemeVariants").start();
    }

    private void updateInstalledThemePreview(final ThemeManager.ThemeEntry theme) {
        if (theme == null || isFullWidthMenus || tvSettingsPreviewTitle == null) return;
        tvSettingsPreviewTitle.setVisibility(View.VISIBLE);
        tvSettingsPreviewTitle.setText(theme.name);
        tvSettingsPreviewTitle.setSelected(true);
        enableMarquee(tvSettingsPreviewTitle);
        ThemeManager.applyThemedTextStyle(tvSettingsPreviewTitle, ThemeManager.getTextColorPrimary());
        String author = ThemeBrowser.installedAuthor(theme);
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setVisibility(View.VISIBLE);
            tvSettingsPreviewState.setText(author.isEmpty() ? " " : author);
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewState, ThemeManager.getTextColorSecondary());
        }
        if (storagePieView != null) storagePieView.setVisibility(View.GONE);
        if (ivSettingsPreviewIcon != null) {
            ivSettingsPreviewIcon.setVisibility(View.VISIBLE);
            ivSettingsPreviewIcon.setImageDrawable(null);
        }
        final int gen = ++themeGalleryPreviewGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap cover = ThemeManager.getScaledThemeCover(theme, themeGalleryCoverMaxPx());
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != themeGalleryPreviewGen || ivSettingsPreviewIcon == null) return;
                        if (cover != null) {
                            ivSettingsPreviewIcon.setImageBitmap(cover);
                            ivSettingsPreviewIcon.setVisibility(View.VISIBLE);
                        } else {
                            ivSettingsPreviewIcon.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "ThemeInstalledCover").start();
    }

    private void removeInstalledTheme(final ThemeManager.ThemeEntry theme) {
        if (theme == null || theme.folderName == null) return;
        if (ThemeManager.isBuiltInDefault(theme)) return;
        if (!ThemeDownloader.deleteInstalledTheme(theme.folderName)) return;
        ThemeManager.loadAllThemes(this);
        Toast.makeText(this, getString(R.string.theme_removed, theme.name), Toast.LENGTH_SHORT).show();
        rebuildThemeBrowserRows();
        refreshThemeBrowserList();
    }

    private void addThemeBrowserContextActions() {
        if (themeBrowserMode == THEME_BROWSER_GET_MORE || themeBrowserMode == THEME_BROWSER_VARIANT) {
            addContextAction(getString(R.string.theme_context_sort), null, themeSortLine(), new Runnable() {
                @Override
                public void run() { toggleThemeSort(); }
            });
            addContextAction(getString(R.string.theme_context_filter), null, themeFilterLine(), new Runnable() {
                @Override
                public void run() { cycleThemeFilter(); }
            });
        }
        if (themeBrowserMode == THEME_BROWSER_GET_MORE || themeBrowserMode == THEME_BROWSER_VARIANT) {
            addContextAction(getString(R.string.theme_context_refresh), new Runnable() {
                @Override
                public void run() { refreshThemeCatalogFromNetwork(); }
            });
        }
        final ThemeBrowser.Row row = themeBrowserFocusedRow();
        if (row == null) return;
        if (row.kind == ThemeBrowser.KIND_INSTALLED
                && row.themeIndex >= 0 && row.themeIndex < ThemeManager.availableThemes.size()) {
            final ThemeManager.ThemeEntry theme = ThemeManager.availableThemes.get(row.themeIndex);
            if (!row.active) {
                addContextAction(getString(R.string.theme_context_apply), new Runnable() {
                    @Override
                    public void run() {
                        applyThemeWithIntegrityCheck(row.themeIndex, theme, null);
                    }
                });
            }
            if (row.needsUpdate && row.catalog != null) {
                final ThemeDownloader.CatalogEntry cat = row.catalog;
                addContextAction(getString(R.string.theme_context_update), new Runnable() {
                    @Override
                    public void run() {
                        ThemeDownloader.ThemeVariant v =
                                ThemeDownloader.findVariantForInstalledFolder(cat, theme.folderName);
                        if (v != null) downloadAndApplyThemeVariant(cat, v);
                        else downloadAndApplyTheme(cat);
                    }
                });
            }
            if (row.catalog != null && row.catalog.hasVariants()) {
                final ThemeDownloader.CatalogEntry cat = row.catalog;
                addContextAction(getString(R.string.theme_context_change_variant), new Runnable() {
                    @Override
                    public void run() { openThemeVariantBrowser(cat); }
                });
            }
            if (!ThemeManager.isBuiltInDefault(theme)) {
                addContextAction(getString(R.string.theme_context_remove), new Runnable() {
                    @Override
                    public void run() { removeInstalledTheme(theme); }
                });
            }
        } else if (row.kind == ThemeBrowser.KIND_CATALOG && row.catalog != null) {
            final ThemeDownloader.CatalogEntry cat = row.catalog;
            if (row.needsUpdate) {
                addContextAction(getString(R.string.theme_context_update), new Runnable() {
                    @Override
                    public void run() { downloadAndApplyTheme(cat); }
                });
            } else if (!cat.isComplete()) {
                addContextAction(getString(R.string.theme_context_download), new Runnable() {
                    @Override
                    public void run() {
                        if (cat.hasVariants()) openThemeVariantBrowser(cat);
                        else startThemeGalleryActivation(cat, null);
                    }
                });
            }
            if (cat.hasVariants()) {
                addContextAction(getString(R.string.theme_context_change_variant), new Runnable() {
                    @Override
                    public void run() { openThemeVariantBrowser(cat); }
                });
            }
        } else if (row.kind == ThemeBrowser.KIND_VARIANT && row.catalog != null && row.variant != null) {
            final ThemeDownloader.CatalogEntry cat = row.catalog;
            final ThemeDownloader.ThemeVariant var = row.variant;
            if (row.needsUpdate) {
                addContextAction(getString(R.string.theme_context_update), new Runnable() {
                    @Override
                    public void run() { downloadAndApplyThemeVariant(cat, var); }
                });
            } else {
                addContextAction(getString(R.string.theme_context_download), new Runnable() {
                    @Override
                    public void run() { startThemeGalleryActivation(cat, var); }
                });
            }
        }
    }

    private int themeGalleryCoverMaxPx() {
        // ponytail: match home now-playing art slot (y1_preview_width × y1_setting_icon_max)
        return (int) getResources().getDimension(R.dimen.y1_setting_icon_max);
    }

    private void prepareThemeGalleryPreviewPane() {
        if (isFullWidthMenus || settingsPreviewPane == null) return;
        settingsPreviewPane.setVisibility(View.VISIBLE);
        if (storagePieView != null) storagePieView.setVisibility(View.GONE);
        if (ivSettingsPreviewIcon != null) {
            ivSettingsPreviewIcon.setVisibility(View.GONE);
            ivSettingsPreviewIcon.setImageDrawable(null);
        }
        if (tvSettingsPreviewTitle != null) {
            tvSettingsPreviewTitle.setVisibility(View.VISIBLE);
            tvSettingsPreviewTitle.setText(getString(R.string.theme_themes_title));
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewTitle, ThemeManager.getTextColorPrimary());
        }
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setVisibility(View.VISIBLE);
            tvSettingsPreviewState.setText(getString(R.string.settings_focus_theme));
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewState, ThemeManager.getTextColorSecondary());
        }
    }

    private boolean isThemeGalleryActive() {
        return SettingsScreens.THEMES.equals(settingsSubScreenKey);
    }

    private boolean isThemeVariantPickerActive() {
        return SettingsScreens.THEME_VARIANT.equals(settingsSubScreenKey);
    }

    private boolean handleSoulseekSettingsBack() {
        if (!SettingsScreens.isSoulseek(settingsSubScreenKey)) {
            return false;
        }
        if (SettingsScreens.SOULSEEK_CONNECTION.equals(settingsSubScreenKey)
                || SettingsScreens.SOULSEEK_ABOUT.equals(settingsSubScreenKey)) {
            buildSoulseekSettingsUI();
            return true;
        }
        if (SettingsScreens.SOULSEEK.equals(settingsSubScreenKey)) {
            lastSettingsFocusIndex = soulseekSettingsMenuFocusIndex;
            buildSettingsUI();
            return true;
        }
        return false;
    }

    private boolean handleHomeScreenEditorBack() {
        if (!SettingsScreens.isHome(settingsSubScreenKey)) {
            return false;
        }
        if (SettingsScreens.HOME_ARRANGE.equals(settingsSubScreenKey)) {
            if (homeScreenMoveModeId != null) {
                homeScreenMoveModeId = null;
                refreshHomeArrangeMoveUi(false);
                return true;
            }
            buildHomeScreenEditorUI();
            return true;
        }
        if (SettingsScreens.HOME_MORE_ARRANGE.equals(settingsSubScreenKey)) {
            if (homeMoreMoveModeId != null) {
                homeMoreMoveModeId = null;
                refreshHomeArrangeMoveUi(true);
                return true;
            }
            buildHomeScreenArrangeUI();
            return true;
        }
        if (SettingsScreens.HOME.equals(settingsSubScreenKey)) {
            lastSettingsFocusIndex = homeScreenEditorMenuFocusIndex;
            returnToSettingsParent();
            return true;
        }
        return false;
    }

    private boolean isHomeScreenArrangeScreen() {
        return SettingsScreens.HOME_ARRANGE.equals(settingsSubScreenKey);
    }

    private boolean isHomeMoreArrangeScreen() {
        return SettingsScreens.HOME_MORE_ARRANGE.equals(settingsSubScreenKey);
    }

    // ponytail: Y1 wheel key→move mapping may change if driver remaps axes
    private int mapWheelToMenuMove(int keyCode) {
        if (keyCode == 21) return -1;
        if (keyCode == 22) return 1;
        return 0;
    }

    private void restoreHomeScreenEditorFocus(final int targetFocusIndex) {
        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                int idx = Math.max(1, targetFocusIndex);
                View target = null;
                for (int i = idx; i < containerSettingsItems.getChildCount(); i++) {
                    View v = containerSettingsItems.getChildAt(i);
                    if (v != null && v.isFocusable()) {
                        target = v;
                        idx = i;
                        break;
                    }
                }
                if (target == null) {
                    for (int i = 1; i < containerSettingsItems.getChildCount(); i++) {
                        View v = containerSettingsItems.getChildAt(i);
                        if (v != null && v.isFocusable()) {
                            target = v;
                            idx = i;
                            break;
                        }
                    }
                }
                if (target != null) {
                    target.requestFocus();
                    if (containerSettingsItems.getParent() instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) containerSettingsItems.getParent())
                                .requestChildFocus(containerSettingsItems, target);
                    }
                    homeScreenEditorFocusIndex = idx;
                    homeScreenOrderFocusIndex = idx;
                    if (target instanceof LinearLayout) {
                        Object tag = target.getTag();
                        if (tag instanceof String) updateSettingsPreview((String) tag);
                    }
                }
            }
        }, 50);
    }

    private String homeScreenOrderFocusedId() {
        View focused = getCurrentFocus();
        if (focused instanceof LinearLayout) {
            Object tag = focused.getTag();
            if (tag instanceof String) {
                String s = (String) tag;
                if (s.startsWith("home.shortcut.")) {
                    return s.substring("home.shortcut.".length());
                }
                if (HomeMenuConfig.find(s) != null) return s;
            }
            LinearLayout row = (LinearLayout) focused;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof TextView && !"inline_state".equals(child.getTag())
                        && !"move_grip".equals(child.getTag())) {
                    String label = ((TextView) child).getText().toString();
                    for (HomeMenuConfig.Entry e : HomeMenuConfig.catalog()) {
                        if (getString(e.labelResId).equals(label)) return e.id;
                    }
                }
            }
        }
        return homeScreenMoveModeId;
    }

    private void restoreSoulseekSettingsScreen(String subKey) {
        if (SettingsScreens.SOULSEEK_CONNECTION.equals(subKey)) {
            buildSoulseekConnectionInfoUI();
        } else if (SettingsScreens.SOULSEEK_ABOUT.equals(subKey)) {
            buildSoulseekAboutInfoUI();
        } else {
            buildSoulseekSettingsUI();
        }
    }

    private String resolveSettingsSubTitle() {
        if (settingsSubScreenKey == null) return getString(R.string.status_settings);
        int res = SettingsScreens.titleResId(settingsSubScreenKey);
        if (res == 0) return getString(R.string.status_settings);
        if (SettingsScreens.EQ.equals(settingsSubScreenKey)
                || SettingsScreens.THEME_VARIANT.equals(settingsSubScreenKey)) {
            String extra = settingsSubScreenExtra != null ? settingsSubScreenExtra : "";
            return getString(res, extra);
        }
        return getString(res);
    }

    private void setSettingsSubScreen(String key) {
        settingsSubScreenKey = key;
        settingsSubScreenExtra = null;
    }

    private void setSettingsSubScreen(String key, String extra) {
        settingsSubScreenKey = key;
        settingsSubScreenExtra = extra;
    }

    private String formatWifiIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info == null) return getString(R.string.soulseek_wifi_ip_unknown);
            int ip = info.getIpAddress();
            if (ip == 0) return getString(R.string.soulseek_wifi_ip_unknown);
            return String.format(Locale.US, "%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Exception e) {
            return getString(R.string.soulseek_wifi_ip_unknown);
        }
    }

    private void addSettingsInfoParagraph(String text) {
        TextView tv = new TextView(this);
        tv.setFocusable(false);
        tv.setText(text);
        ThemeManager.applyThemedTextStyle(tv, ThemeManager.getTextColorSecondary());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 8);
        tv.setLayoutParams(lp);
        containerSettingsItems.addView(tv);
    }

    private int soulseekListenPortOrZero() {
        if (soulseekListenPort > 0) return soulseekListenPort;
        return soulseekClient != null ? soulseekClient.getListenPort() : 0;
    }

    private String soulseekListenPortLabel() {
        int port = soulseekListenPortOrZero();
        return port > 0
                ? getString(R.string.soulseek_listen_port_value, port)
                : getString(R.string.soulseek_listen_port_unknown);
    }

    private String soulseekNatStatusLabel() {
        return soulseekClient != null
                ? soulseekClient.getNatpmpStatusLine()
                : getString(R.string.soulseek_natpmp_unknown);
    }

    private boolean handleThemeGalleryBack() {
        if (themeGalleryInterstitial != null && themeGalleryInterstitial.getVisibility() == View.VISIBLE) {
            hideThemeGalleryInterstitial();
            return true;
        }
        if (isThemeVariantPickerActive()) {
            exitThemeVariantBrowser();
            return true;
        }
        if (isThemeGalleryActive()) {
            themeBrowserGoBack();
            return true;
        }
        if (SettingsScreens.THEME_PICKER.equals(settingsSubScreenKey)) {
            returnToSettingsParent();
            return true;
        }
        return false;
    }

    private void returnToSettingsParent() {
        if (SettingsScreens.APPEARANCE.equals(settingsParentKey)) {
            buildAppearanceSettingsUI();
        } else {
            buildSettingsUI();
        }
    }

    private boolean handleAppearanceSettingsBack() {
        if (SettingsScreens.APPEARANCE.equals(settingsSubScreenKey)) {
            settingsParentKey = null;
            buildSettingsUI();
            return true;
        }
        return false;
    }

    private void openAppearanceSubmenu(Runnable action) {
        settingsParentKey = SettingsScreens.APPEARANCE;
        action.run();
    }

    private void openThemesScreen(String parentKey) {
        settingsParentKey = parentKey;
        settingsSubScreenKey = SettingsScreens.THEMES;
        changeScreen(STATE_SETTINGS);
    }

    private boolean isThemeListActive() {
        return listThemes != null && listThemes.getVisibility() == View.VISIBLE
                && (isThemeGalleryActive() || isThemeVariantPickerActive());
    }

    private void dispatchThemeListKey(int keyCode) {
        if (keyCode == 21) {
            listThemes.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            listThemes.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
        } else if (keyCode == 22) {
            listThemes.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            listThemes.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        }
    }

    private void clearThemeGalleryPreview() {
        themeGalleryPreviewGen++;
        if (storagePieView != null) storagePieView.setVisibility(View.GONE);
        if (tvSettingsPreviewTitle != null) {
            tvSettingsPreviewTitle.setText("");
            tvSettingsPreviewTitle.setVisibility(View.GONE);
        }
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setText("");
            tvSettingsPreviewState.setVisibility(View.GONE);
        }
        if (ivSettingsPreviewIcon != null) {
            ivSettingsPreviewIcon.setImageDrawable(null);
            ivSettingsPreviewIcon.setVisibility(View.GONE);
        }
    }

    private void updateThemeGalleryPreview(final ThemeDownloader.CatalogEntry entry,
                                           final ThemeDownloader.ThemeVariant variant) {
        if (entry == null || isFullWidthMenus || tvSettingsPreviewTitle == null) return;
        final String title = variant != null ? variant.displayName(entry.name) : entry.name;
        final String author = entry.author != null && !entry.author.isEmpty()
                ? entry.author : "Unknown author";
        tvSettingsPreviewTitle.setVisibility(View.VISIBLE);
        tvSettingsPreviewTitle.setText(title);
        tvSettingsPreviewTitle.setSelected(true);
        enableMarquee(tvSettingsPreviewTitle);
        ThemeManager.applyThemedTextStyle(tvSettingsPreviewTitle, ThemeManager.getTextColorPrimary());
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setVisibility(View.VISIBLE);
            tvSettingsPreviewState.setText(author);
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewState, ThemeManager.getTextColorSecondary());
        }
        if (storagePieView != null) storagePieView.setVisibility(View.GONE);
        if (ivSettingsPreviewIcon != null) {
            ivSettingsPreviewIcon.setVisibility(View.VISIBLE);
            ivSettingsPreviewIcon.setImageDrawable(null);
        }
        final int gen = ++themeGalleryPreviewGen;
        final int maxH = themeGalleryCoverMaxPx();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap cover = ThemeDownloader.loadCoverBitmap(entry, variant, maxH);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != themeGalleryPreviewGen || ivSettingsPreviewIcon == null) return;
                        if (cover != null) {
                            ivSettingsPreviewIcon.setImageBitmap(cover);
                            ivSettingsPreviewIcon.setVisibility(View.VISIBLE);
                        } else {
                            ivSettingsPreviewIcon.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "ThemeCoverPreview").start();
    }

    private void bindThemeGalleryListButton(final Button btn, final ThemeDownloader.CatalogEntry entry,
                                            final ThemeDownloader.ThemeVariant variant,
                                            final View.OnClickListener onActivate) {
        btn.setTag(entry.folder + (variant != null ? "\0" + variant.gallerySubpath : ""));
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                int w = btn.getWidth() > 0 ? btn.getWidth()
                        : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
                btn.setBackground(getY1RowBackground(hasFocus, w, Y1_ROW_MENU));
                ThemeManager.applyThemedTextStyle(btn, hasFocus
                        ? y1RowTextColorSelected(Y1_ROW_MENU) : y1RowTextColorNormal(Y1_ROW_MENU));
                btn.setSelected(hasFocus);
                if (hasFocus) {
                    showFastScrollLetter(btn.getText().toString());
                    if (!isFullWidthMenus) updateThemeGalleryPreview(entry, variant);
                }
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                onActivate.onClick(v);
            }
        });
    }

    private void hideThemeGalleryInterstitial() {
        themeGalleryInterstitialEntry = null;
        themeGalleryInterstitialVariant = null;
        if (themeGalleryInterstitial != null) themeGalleryInterstitial.setVisibility(View.GONE);
    }

    private void showThemeGalleryInterstitial(final ThemeDownloader.CatalogEntry entry,
                                              final ThemeDownloader.ThemeVariant variant) {
        if (themeGalleryInterstitial == null) {
            if (variant != null) downloadAndApplyThemeVariant(entry, variant);
            else downloadAndApplyTheme(entry);
            return;
        }
        themeGalleryInterstitialEntry = entry;
        themeGalleryInterstitialVariant = variant;
        final String title = variant != null ? variant.displayName(entry.name) : entry.name;
        final String author = entry.author != null && !entry.author.isEmpty()
                ? entry.author : "Unknown author";
        if (tvThemeInterstitialTitle != null) {
            tvThemeInterstitialTitle.setText(title);
            tvThemeInterstitialTitle.setSelected(true);
            enableMarquee(tvThemeInterstitialTitle);
        }
        if (tvThemeInterstitialAuthor != null) tvThemeInterstitialAuthor.setText(author);
        if (ivThemeInterstitialCover != null) {
            ivThemeInterstitialCover.setVisibility(View.GONE);
            ivThemeInterstitialCover.setImageDrawable(null);
        }
        themeGalleryInterstitial.setVisibility(View.VISIBLE);
        final int maxH = themeGalleryCoverMaxPx();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap cover = ThemeDownloader.loadCoverBitmap(entry, variant, maxH);
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || themeGalleryInterstitial == null
                                || themeGalleryInterstitial.getVisibility() != View.VISIBLE) return;
                        if (ivThemeInterstitialCover == null) return;
                        if (cover != null) {
                            ivThemeInterstitialCover.setImageBitmap(cover);
                            ivThemeInterstitialCover.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "ThemeCoverInterstitial").start();
        if (btnThemeInterstitialDownload != null) {
            btnThemeInterstitialDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    hideThemeGalleryInterstitial();
                    if (variant != null) downloadAndApplyThemeVariant(entry, variant);
                    else downloadAndApplyTheme(entry);
                }
            });
            btnThemeInterstitialDownload.requestFocus();
        }
    }

    private void startThemeGalleryActivation(final ThemeDownloader.CatalogEntry entry,
                                             final ThemeDownloader.ThemeVariant variant) {
        if (isFullWidthMenus) {
            showThemeGalleryInterstitial(entry, variant);
        } else if (variant != null) {
            downloadAndApplyThemeVariant(entry, variant);
        } else {
            downloadAndApplyTheme(entry);
        }
    }

    private void buildThemeGalleryUI() {
        buildUnifiedThemesUI();
    }

    private void populateThemeGallery(final List<ThemeDownloader.CatalogEntry> catalog) {
        applyThemeCatalog(catalog);
        if (listThemes == null || listThemes.getVisibility() != View.VISIBLE) {
            buildUnifiedThemesUI();
        } else {
            rebuildThemeBrowserRows();
            refreshThemeBrowserList();
        }
    }

    private void buildThemeVariantPickerUI(final ThemeDownloader.CatalogEntry entry) {
        openThemeVariantBrowser(entry);
    }

    private void downloadAndApplyThemeVariant(final ThemeDownloader.CatalogEntry entry,
                                              final ThemeDownloader.ThemeVariant variant) {
        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.VISIBLE);
        final int gen = themeDownloadGen;
        ThemeDownloader.clearCancel();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String installFolder = ThemeDownloader.resolvedInstallFolder(entry, variant);
                    if (!variant.isComplete(entry.name)) {
                        ThemeDownloader.downloadThemeVariant(entry, variant,
                                new ThemeDownloader.ProgressListener() {
                                    @Override
                                    public void onProgress(final int done, final int total,
                                                           final String fileName) {
                                        runOnUiThreadSafe(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing()) return;
                                                setLoadingOverlayText("Downloading " + variant.displayName(entry.name)
                                                        + "\n" + done + " / " + total);
                                            }
                                        });
                                    }
                                });
                    }
                    if (gen != themeDownloadGen) return;
                    ThemeManager.loadAllThemes();
                    final String toastName = variant.displayName(entry.name);
                    final String path = new File(ThemeManager.PATH_THEMES, installFolder).getAbsolutePath();
                    int index = -1;
                    for (int i = 0; i < ThemeManager.availableThemes.size(); i++) {
                        if (path.equals(ThemeManager.availableThemes.get(i).folderPath)) {
                            index = i;
                            break;
                        }
                    }
                    if (index < 0) throw new Exception("Variant not found after download: " + installFolder);
                    final int themeIndex = index;
                    final ThemeManager.ThemeEntry theme = ThemeManager.availableThemes.get(themeIndex);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != themeDownloadGen) {
                                if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
                                return;
                            }
                            applyThemeWithIntegrityCheck(themeIndex, theme, toastName);
                        }
                    });
                } catch (final Exception e) {
                    ThemeDownloader.dlLogError("downloadAndApplyThemeVariant " + entry.folder, e);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != themeDownloadGen) return;
                            if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, getString(R.string.toast_download_failed), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void downloadAndApplyTheme(final ThemeDownloader.CatalogEntry entry) {
        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.VISIBLE);
        final int gen = themeDownloadGen;
        ThemeDownloader.clearCancel();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!entry.isComplete()) {
                        ThemeDownloader.downloadTheme(entry, new ThemeDownloader.ProgressListener() {
                            @Override
                            public void onProgress(final int done, final int total, final String fileName) {
                                runOnUiThreadSafe(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing()) return;
                                        setLoadingOverlayText("Downloading " + entry.name + "\n" + done + " / " + total);
                                    }
                                });
                            }
                        });
                    }
                    if (gen != themeDownloadGen) return;
                    ThemeManager.loadAllThemes();
                    final String installPath = new File(ThemeManager.PATH_THEMES,
                            ThemeDownloader.installedFolderForEntry(entry)).getAbsolutePath();
                    final String themeName = entry.name;
                    int index = -1;
                    for (int i = 0; i < ThemeManager.availableThemes.size(); i++) {
                        if (installPath.equals(ThemeManager.availableThemes.get(i).folderPath)) {
                            index = i;
                            break;
                        }
                    }
                    if (index < 0) throw new Exception("Theme not found after download");
                    final int themeIndex = index;
                    final ThemeManager.ThemeEntry theme = ThemeManager.availableThemes.get(themeIndex);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != themeDownloadGen) {
                                if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
                                return;
                            }
                            applyThemeWithIntegrityCheck(themeIndex, theme, themeName);
                        }
                    });
                } catch (final Exception e) {
                    ThemeDownloader.dlLogError("downloadAndApplyTheme " + entry.folder, e);
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || gen != themeDownloadGen) return;
                            if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, getString(R.string.toast_download_failed), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** ponytail: on apply, repair missing assets from catalog when Wi-Fi is up */
    private void applyThemeWithIntegrityCheck(final int index, final ThemeManager.ThemeEntry theme,
                                              final String toastName) {
        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ThemeManager.isBuiltInDefault(theme) && hasInternetConnection()) {
                        java.util.Set<String> missing = ThemeDownloader.missingAssets(theme.folderName);
                        if (!missing.isEmpty()) {
                            ThemeDownloader.repairThemeFolderIfInCatalog(theme.folderName, null);
                        }
                    }
                } catch (Exception e) {
                    ThemeDownloader.dlLogError("integrity repair " + theme.folderName, e);
                    android.util.Log.w("ThemeDownloader", "integrity repair: " + e.getMessage());
                }
                runOnUiThreadSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
                        ThemeManager.setThemeIndex(index);
                        try {
                            SharedPreferences.Editor ed = prefs.edit()
                                    .putInt("app_theme_index", index)
                                    .putString("app_theme_path", theme.folderPath)
                                    .putBoolean("reboot_to_theme", true)
                                    .putBoolean("status_bar_match_font", true);
                            if (ThemeManager.hasThemeWallpaper(theme)) {
                                ed.putString("background_mode", BG_MODE_THEME);
                            }
                            ed.commit();
                        } catch (Exception ignored) {}
                        if (toastName != null) {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_theme_applied, toastName), Toast.LENGTH_SHORT).show();
                        }
                        recreate();
                    }
                });
            }
        }).start();
    }

    private boolean isWifiConnected() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            return info != null && info.getNetworkId() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasInternetConnection() {
        return ConnectivityHelper.isOnline(this);
    }

    private boolean requireInternet(int toastResId) {
        if (hasInternetConnection()) return true;
        Toast.makeText(this, getString(toastResId), Toast.LENGTH_LONG).show();
        return false;
    }

    private void onNetworkConnectivityChanged() {
        refreshConnectivityGatedMenus();
    }

    private void refreshConnectivityGatedMenus() {
        if (currentScreenState == STATE_MENU) {
            buildHomeMenu();
        } else if (currentScreenState == STATE_MORE) {
            buildMoreMenuUI();
        } else if (currentScreenState == STATE_SETTINGS && settingsSubScreenKey == null) {
            buildSettingsUI();
        }
        if (currentScreenState == STATE_SETTINGS && SettingsScreens.THEMES.equals(settingsSubScreenKey)) {
            if (themeBrowserMode == THEME_BROWSER_MAIN || themeBrowserMode == THEME_BROWSER_GET_MORE) {
                rebuildThemeBrowserRows();
                refreshThemeBrowserList();
            }
        }
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_ROOT && !isPickingBackground) {
            buildFileBrowserUI();
        }
    }

    private void onWifiConnectivityChanged() {
        refreshConnectivityGatedMenus();
        updateSoulseekSharePolicy();
    }

    private void triggerAutoReconnect() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                wm.reconnect();
            }
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.isEnabled()) {
                java.util.Set<BluetoothDevice> pairedDevices = ba.getBondedDevices();
            }
        } catch (Exception e) {
        }
    }

    private void toggleWebServer() {
        if (isServerRunning) {
            if (webServer != null)
                webServer.stopServer();
            isServerRunning = false;
        } else {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !ConnectivityHelper.hasLocalNetwork(this)) {
                Toast.makeText(this, getString(R.string.toast_network_required), Toast.LENGTH_SHORT).show();
                return;
            }
            webServer = new SolarWebServer(getApplicationContext(), rootFolder);
            webServer.start();
            isServerRunning = true;
        }
    }

    private void updateWebServerUI() {
        if (isServerRunning) {
            tvServerStatus.setText(getString(R.string.webserver_running));
            ThemeManager.applyThemedTextStyle(tvServerStatus, ThemeManager.getItemTextColorNormal());
            tvServerIp.setText("http://" + webServer.getLocalIpAddress() + ":8080");
            ThemeManager.applyThemedTextStyle(tvServerIp, ThemeManager.getItemTextColorNormal());
            btnServerToggle.setText(getString(R.string.webserver_stop));
        } else {
            tvServerStatus.setText(getString(R.string.webserver_stopped));
            styleSecondaryLabel(tvServerStatus);
            tvServerIp.setText("http://---.---.---.---:8080");
            styleSecondaryLabel(tvServerIp);
            btnServerToggle.setText(getString(R.string.webserver_start));
        }
        if (tvWebserverHint != null) {
            ThemeManager.applyThemedTextStyle(tvWebserverHint, ThemeManager.getHintTextColor());
        }
    }

    private void updateMainMenuBackground() {
        if (currentScreenState == STATE_MENU) {
            updateScreenBackground(STATE_MENU);
        } else if (currentScreenState == STATE_PLAYER) {
            updateScreenBackground(STATE_PLAYER);
        }
    }

    private Bitmap loadSelectedThemeWallpaper(int screenState) {
        String pick = prefs.getString(PREF_BG_THEME_WALLPAPER, null);
        if (pick != null) {
            Bitmap picked = ThemeManager.loadWallpaperPick(pick);
            if (picked != null) return picked;
        }
        return loadThemeWallpaperForScreen(screenState);
    }

    private boolean hasEffectiveWallpaper() {
        if (BG_MODE_CUSTOM.equals(getBackgroundMode())) {
            return loadCustomBackgroundBitmap() != null;
        }
        String pick = prefs.getString(PREF_BG_THEME_WALLPAPER, null);
        if (pick != null && ThemeManager.loadWallpaperPick(pick) != null) return true;
        return ThemeManager.hasThemeWallpaper();
    }

    private Bitmap loadThemeWallpaperForScreen(int screenState) {
        String prefKey = null;
        if (screenState == STATE_MENU) prefKey = PREF_BG_HOME;
        else if (screenState == STATE_BROWSER) prefKey = PREF_BG_LIBRARY;
        else if (screenState == STATE_SETTINGS) prefKey = PREF_BG_SETTINGS;
        else if (screenState == STATE_PLAYER) prefKey = PREF_BG_NOW_PLAYING;
        if (prefKey != null) {
            String pick = prefs.getString(prefKey, null);
            if (pick != null) {
                Bitmap b = ThemeManager.loadWallpaperPick(pick);
                if (b != null) return b;
            }
        }
        Bitmap wall;
        if (screenState == STATE_MENU) {
            wall = ThemeManager.getWallpaper(true);
            if (wall == null) wall = ThemeManager.getWallpaper(false);
        } else {
            wall = ThemeManager.getWallpaper(false);
            if (wall == null) wall = ThemeManager.getWallpaper(true);
        }
        return wall;
    }

    private Bitmap loadThemeMaskForScreen(int screenState) {
        if (screenState == STATE_MENU) return ThemeManager.getDesktopMask();
        if (screenState == STATE_SETTINGS) return ThemeManager.getSettingMask();
        return null;
    }

    private boolean hasAlbumArtForBlur() {
        return lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0;
    }

    private void applyOverlayBackgrounds() {
        int overlay = hasEffectiveWallpaper()
                ? 0x00000000 : ThemeManager.getOverlayBackgroundColor();
        if (layoutBrowserMode != null) layoutBrowserMode.setBackgroundColor(overlay);
        if (layoutSettingsMode != null) layoutSettingsMode.setBackgroundColor(overlay);
        if (layoutBluetoothMode != null) layoutBluetoothMode.setBackgroundColor(overlay);
        if (layoutWifiMode != null) layoutWifiMode.setBackgroundColor(overlay);
        if (layoutWifiKeyboard != null) layoutWifiKeyboard.setBackgroundColor(overlay);
        if (layoutBrightnessMode != null) layoutBrightnessMode.setBackgroundColor(overlay);
        if (layoutStorageMode != null) layoutStorageMode.setBackgroundColor(overlay);
        if (layoutWebServerMode != null) layoutWebServerMode.setBackgroundColor(overlay);
    }

    private void updateScreenBackground(int screenState) {
        if (ivMainBg == null) return;
        try {
            applyOverlayBackgrounds();
            Bitmap wall = null;
            Bitmap mask = null;
            String mode = getBackgroundMode();

            if (BG_MODE_CUSTOM.equals(mode)) {
                wall = loadCustomBackgroundBitmap();
            } else {
                wall = loadSelectedThemeWallpaper(screenState);
                mask = loadThemeMaskForScreen(screenState);
            }

            if (screenState == STATE_PLAYER && ivPlayerBgBlur != null) {
                if (playerAlbumBlurEnabled && hasAlbumArtForBlur()) {
                    BitmapFactory.Options optsBg = new BitmapFactory.Options();
                    optsBg.inSampleSize = 4;
                    Bitmap sourceBg = BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, optsBg);
                    if (sourceBg != null) {
                        Bitmap blurredBg = applyGaussianBlur(sourceBg);
                        ivPlayerBgBlur.setImageBitmap(blurredBg);
                        if (sourceBg != blurredBg) sourceBg.recycle();
                    }
                } else {
                    Bitmap bg = BG_MODE_CUSTOM.equals(mode)
                            ? loadCustomBackgroundBitmap()
                            : loadSelectedThemeWallpaper(screenState);
                    if (bg != null) {
                        ivPlayerBgBlur.setImageBitmap(ThemeManager.centerCropBitmap(bg, screenWidthPx, screenHeightPx));
                    } else {
                        ivPlayerBgBlur.setImageResource(0);
                    }
                }
            }

            if (isFullWidthMenus) {
                mask = null;
            }

            if (screenState == STATE_MENU && anyHomeWidgetActive()) {
                mask = null;
            }

            if (wall != null) {
                ivMainBg.setImageBitmap(ThemeManager.centerCropBitmap(wall, screenWidthPx, screenHeightPx));
            } else if (screenState != STATE_PLAYER) {
                ivMainBg.setImageResource(R.drawable.default_back);
            }
            applyScreenMask(mask);
        } catch (Throwable t) {
            ivMainBg.setImageResource(R.drawable.default_back);
            applyScreenMask(null);
        }
    }

    private Bitmap loadCustomBackgroundBitmap() {
        String savedBgPath = prefs.getString("bg_path", null);
        if (savedBgPath == null || savedBgPath.isEmpty()) return null;
        File bgFile = new File(savedBgPath);
        if (!bgFile.exists()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(savedBgPath, opts);
        int scale = 1;
        int maxDim = Math.max(opts.outWidth, opts.outHeight);
        while (maxDim / scale > 800) scale *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = scale;
        return BitmapFactory.decodeFile(savedBgPath, opts);
    }

    private void applyScreenMask(Bitmap mask) {
        if (ivScreenMask == null) return;
        if (mask != null) {
            ivScreenMask.setImageBitmap(ThemeManager.centerCropBitmap(mask, screenWidthPx, screenHeightPx));
            ivScreenMask.setVisibility(View.VISIBLE);
        } else {
            ivScreenMask.setVisibility(View.GONE);
        }
    }


    // 💡 [추가] 테마 색상과 '둥글기(Radius)'를 혼합해서 버튼의 배경 디자인을 찍어내는 도구
    private android.graphics.drawable.GradientDrawable createButtonBackground(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color); // 테마 색상 주입
        // 테마에 설정된 둥글기(Radius) 값 주입 (dp 단위를 픽셀로 변환하여 적용)
        float radius = ThemeManager.getButtonRadius() * getResources().getDisplayMetrics().density;
        shape.setCornerRadius(radius);
        return shape;
    }

    /** ponytail: stock Y1 — home=item rows, settings=menu rows, lists=item rows */
    private void initY1LayoutMetrics() {
        float density = getResources().getDisplayMetrics().density;
        y1RowWidthPx = (int) (getResources().getDimension(R.dimen.y1_menu_width));
        y1RowHeightPx = (int) (getResources().getDimension(R.dimen.y1_menu_item_height));
        listRowWidthPx = (int) (getResources().getDimension(R.dimen.y1_screen_width) - 20 * density);
        y1ThemeCoverHeightPx = (int) (getResources().getDimension(R.dimen.y1_theme_cover_height));
        y1BatteryIconHeightPx = (int) (getResources().getDimension(R.dimen.y1_battery_icon_height));
        screenWidthPx = (int) getResources().getDimension(R.dimen.y1_screen_width);
        screenHeightPx = (int) getResources().getDimension(R.dimen.y1_screen_height);
        settingsMenuWidthPx = (int) getResources().getDimension(R.dimen.y1_settings_menu_width);
        menuListHeightPx = (int) getResources().getDimension(R.dimen.y1_menu_height);
    }

    private android.graphics.drawable.Drawable getY1RowBackground(boolean focused, int widthPx, int rowKind) {
        boolean selected = focused;
        android.graphics.drawable.Drawable scaled;
        if (rowKind == Y1_ROW_HOME || rowKind == Y1_ROW_ITEM) {
            scaled = ThemeManager.getItemRowBackgroundScaled(
                    getResources(), selected, widthPx, y1RowHeightPx);
        } else {
            scaled = ThemeManager.getMenuRowBackgroundScaled(
                    getResources(), selected, widthPx, y1RowHeightPx);
        }
        if (scaled != null) return scaled;
        boolean menuRows = rowKind == Y1_ROW_MENU;
        if (!selected && ThemeManager.y1UnselectedRowTransparent(menuRows)) {
            if (rowKind == Y1_ROW_HOME) {
                return new android.graphics.drawable.ColorDrawable(ThemeManager.getListButtonNormalBg());
            }
            return new android.graphics.drawable.ColorDrawable(0x00000000);
        }
        if (selected) return createButtonBackground(ThemeManager.getRowSelectionFillColor());
        return new android.graphics.drawable.ColorDrawable(ThemeManager.getListButtonNormalBg());
    }

    private android.graphics.drawable.Drawable getY1RowBackground(boolean focused, int widthPx) {
        return getY1RowBackground(focused, widthPx, y1RowKindForScreen());
    }

    private int y1ActiveRowWidthPx() {
        if (isFullWidthMenus && screenWidthPx > 0) return screenWidthPx;
        if (currentScreenState == STATE_SETTINGS) {
            return settingsMenuWidthPx > 0 ? settingsMenuWidthPx : y1RowWidthPx;
        }
        if (currentScreenState == STATE_MENU) return y1RowWidthPx;
        return listRowWidthPx;
    }

    private void applyStatusBarTheme() {
        View statusBar = findViewById(R.id.layout_status_bar);
        if (statusBar != null) {
            statusBar.setBackgroundColor(ThemeManager.getStatusBarBackgroundColor());
        }
        int statusTextColor = ThemeManager.getStatusBarTextColor();
        if (tvStatusClock != null) ThemeManager.applyThemedTextStyle(tvStatusClock, statusTextColor);
        if (tvStatusBattery != null) ThemeManager.applyThemedTextStyle(tvStatusBattery, statusTextColor);
        refreshWifiStatusIcon();
    }

    /** Status bar Wi-Fi tint: theme status text when match-font is on, else white. */
    private int statusBarWifiTintColor() {
        if (!statusBarMatchFont) return 0xFFFFFFFF;
        return ThemeManager.getStatusBarTextColor();
    }

    private void refreshWifiStatusIcon() {
        if (ivStatusWifi == null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                ivStatusWifi.setVisibility(View.GONE);
                return;
            }
            ivStatusWifi.setVisibility(View.VISIBLE);
            WifiInfo info = wm.getConnectionInfo();
            boolean connected = info != null && info.getNetworkId() != -1;
            int signalIdx = 0;
            if (connected && info != null) {
                signalIdx = ThemeManager.wifiSignalIndex(info.getRssi());
            }
            Bitmap themed = ThemeManager.getWifiIcon(signalIdx);
            if (themed != null) {
                ivStatusWifi.setImageBitmap(themed);
            } else {
                int res = R.drawable.ic_wifi_signal_1;
                if (signalIdx >= 2) res = R.drawable.ic_wifi_signal_3;
                else if (signalIdx == 1) res = R.drawable.ic_wifi_signal_2;
                ivStatusWifi.setImageResource(res);
            }
            ivStatusWifi.setColorFilter(statusBarWifiTintColor(), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception e) {
            ivStatusWifi.setVisibility(View.GONE);
        }
    }

    private View getHomeMenuRow(int index) {
        if (containerHomeMenuItems == null || index < 0
                || index >= containerHomeMenuItems.getChildCount()) return null;
        return containerHomeMenuItems.getChildAt(index);
    }

    private void requestFirstHomeMenuFocus() {
        scrollHomeMenuToIndex(focusedHomeMenuIndex);
    }

    /** Index-driven home menu move — wheel keys must not rely on View focus on API 17. */
    private boolean moveHomeMenuFocus(int delta) {
        if (containerHomeMenuItems == null || delta == 0) return false;
        int total = containerHomeMenuItems.getChildCount();
        if (total == 0) return false;
        int next = focusedHomeMenuIndex + delta;
        if (next < 0 || next >= total) return false;
        focusedHomeMenuIndex = next;
        scrollHomeMenuToIndex(next);
        return true;
    }

    private void refreshHomeMenuRowStyles() {
        if (containerHomeMenuItems == null) return;
        for (int i = 0; i < containerHomeMenuItems.getChildCount(); i++) {
            View row = containerHomeMenuItems.getChildAt(i);
            if (!(row instanceof FrameLayout)) continue;
            TextView label = (TextView) row.getTag(HOME_MENU_TAG_LABEL);
            ImageView arrow = (ImageView) row.getTag(HOME_MENU_TAG_ARROW);
            boolean focused = i == focusedHomeMenuIndex;
            applyY1ListRowStyle(row, focused, label, null, arrow, Y1_ROW_HOME);
        }
    }

    private boolean isNowPlayingHomeFocused() {
        return focusedHomeMenuIndex >= 0 && focusedHomeMenuIndex < homeMenuEntries.size()
                && HomeMenuConfig.ID_NOW_PLAYING.equals(homeMenuEntries.get(focusedHomeMenuIndex).id);
    }

    private Bitmap resolveHomeMenuPreviewIconForId(String id) {
        if (HomeMenuConfig.ID_THEMES.equals(id) || HomeMenuConfig.ID_GET_THEMES.equals(id)) {
            Bitmap themed = ThemeManager.getSettingIcon("theme");
            if (themed != null) return themed;
        }
        HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
        if (e == null) return null;
        if (e.stockIconKey != null) {
            return ThemeManager.getHomeIcon(this, e.stockIconKey, e.defaultResId);
        }
        if (e.solarAppName != null) {
            return ThemeManager.getSolarAppIcon(e.solarAppName);
        }
        return ThemeManager.getCustomIcon("icon_default_album.png", this, e.defaultResId);
    }

    private void applyAllHomeMenuRowStyles() {
        refreshHomeMenuRowStyles();
    }

    private void applyAllSettingsRowStyles() {
        if (containerSettingsItems == null) return;
        View focused = getCurrentFocus();
        for (int i = 0; i < containerSettingsItems.getChildCount(); i++) {
            View row = containerSettingsItems.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            boolean hasFocus = row == focused;
            TextView tvLeft = null;
            TextView tvRight = null;
            ImageView arrow = null;
            LinearLayout layout = (LinearLayout) row;
            for (int j = 0; j < layout.getChildCount(); j++) {
                View child = layout.getChildAt(j);
                if (child instanceof TextView) {
                    if ("inline_state".equals(child.getTag())) tvRight = (TextView) child;
                    else if (tvLeft == null) tvLeft = (TextView) child;
                } else if (child instanceof ImageView) {
                    arrow = (ImageView) child;
                }
            }
            if (tvLeft != null) {
                int rowW = row.getWidth() > 0 ? row.getWidth() : y1ActiveRowWidthPx();
                row.setBackground(getY1RowBackground(hasFocus, rowW, Y1_ROW_MENU));
                applyY1ListRowStyle(row, hasFocus, tvLeft, tvRight, arrow, Y1_ROW_MENU);
            }
        }
    }

    private void applyY1ListRowStyle(View row, boolean focused, TextView label, TextView value,
                                     ImageView arrow, int rowKind) {
        int w = row.getWidth() > 0 ? row.getWidth() : y1ActiveRowWidthPx();
        row.setBackground(getY1RowBackground(focused, w, rowKind));
        if (label != null) {
            label.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            ThemeManager.applyThemedTextStyle(label, focused
                    ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
            label.setSelected(focused);
            if (focused) enableMarquee(label);
            else {
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setHorizontallyScrolling(false);
            }
        }
        if (value != null) {
            String t = value.getText().toString();
            if (t.equals("ON") || t.equals("ONE") || t.equals("ALL") || t.equals("OFF"))
                ThemeManager.applyThemedTextStyle(value, focused
                        ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
            else if (!t.isEmpty())
                ThemeManager.applyThemedTextStyle(value, focused
                        ? y1RowTextColorSelected(rowKind) : ThemeManager.getTextColorSecondary());
            enableMarquee(value);
        }
        if (arrow != null) arrow.setVisibility(focused ? View.VISIBLE : View.GONE);
    }

    private void applyY1ListRowStyle(View row, boolean focused, TextView label, TextView value, ImageView arrow) {
        applyY1ListRowStyle(row, focused, label, value, arrow, y1RowKindForScreen());
    }

    private void buildHomeMenu() {
        if (containerHomeMenuItems == null) return;
        containerHomeMenuItems.removeAllViews();
        final boolean online = ConnectivityHelper.isOnline(this);
        final boolean onLan = ConnectivityHelper.hasLocalNetwork(this);
        homeMenuEntries = HomeMenuConfig.loadVisibleForDisplay(prefs, online, onLan);
        java.util.Iterator<HomeMenuConfig.Entry> homeIt = homeMenuEntries.iterator();
        while (homeIt.hasNext()) {
            if (HomeMenuConfig.ID_NOW_PLAYING.equals(homeIt.next().id) && !shouldShowNowPlayingHome()) {
                homeIt.remove();
            }
        }
        final boolean showMore = HomeMenuConfig.shouldShowMoreTile(prefs, online, onLan);
        int totalRows = homeMenuEntries.size() + (showMore ? 1 : 0);
        if (focusedHomeMenuIndex >= totalRows) {
            focusedHomeMenuIndex = Math.max(0, totalRows - 1);
        }
        for (int i = 0; i < homeMenuEntries.size(); i++) {
            addHomeMenuRow(homeMenuEntries.get(i), i);
        }
        if (showMore) {
            addHomeMoreTileRow(homeMenuEntries.size());
        }
        int panelH = menuListHeightPx > 0 ? menuListHeightPx
                : (int) getResources().getDimension(R.dimen.y1_menu_height);
        applyMenuPanelBackground(menuListHost, y1RowWidthPx, panelH,
                (int) getResources().getDimension(R.dimen.y1_menu_height));
        scrollHomeMenuToIndex(focusedHomeMenuIndex);
    }

    private void addHomeMenuRow(final HomeMenuConfig.Entry entry, final int idx) {
        FrameLayout row = createHomeMenuRowShell();
        TextView label = (TextView) row.getTag(HOME_MENU_TAG_LABEL);
        ImageView arrow = (ImageView) row.getTag(HOME_MENU_TAG_ARROW);
        label.setText(getString(entry.labelResId));
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyY1ListRowStyle(v, hasFocus, label, null, arrow, Y1_ROW_HOME);
                if (hasFocus) {
                    focusedHomeMenuIndex = idx;
                    updateStatusBarTitle();
                    updateHomeMenuPreview(idx);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                onHomeMenuActivate(entry.id);
            }
        });
        applyY1ListRowStyle(row, idx == focusedHomeMenuIndex, label, null, arrow, Y1_ROW_HOME);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                isFullWidthMenus ? LinearLayout.LayoutParams.MATCH_PARENT : y1ActiveRowWidthPx(),
                y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        containerHomeMenuItems.addView(row, lp);
    }

    private void addHomeMoreTileRow(final int idx) {
        FrameLayout row = createHomeMenuRowShell();
        TextView label = (TextView) row.getTag(HOME_MENU_TAG_LABEL);
        ImageView arrow = (ImageView) row.getTag(HOME_MENU_TAG_ARROW);
        label.setText(getString(R.string.home_menu_more));
        arrow.setVisibility(View.VISIBLE);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyY1ListRowStyle(v, hasFocus, label, null, arrow, Y1_ROW_HOME);
                if (hasFocus) {
                    focusedHomeMenuIndex = idx;
                    updateStatusBarTitle();
                    ivMenuPreview.setVisibility(View.GONE);
                    if (tvMenuPreviewTitle != null) tvMenuPreviewTitle.setVisibility(View.GONE);
                    if (tvMenuPreviewArtist != null) tvMenuPreviewArtist.setVisibility(View.GONE);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                onHomeMenuActivate(HomeMenuConfig.ID_MORE);
            }
        });
        applyY1ListRowStyle(row, idx == focusedHomeMenuIndex, label, null, arrow, Y1_ROW_HOME);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                isFullWidthMenus ? LinearLayout.LayoutParams.MATCH_PARENT : y1ActiveRowWidthPx(),
                y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        containerHomeMenuItems.addView(row, lp);
    }

    /** ponytail: ListView recycles rows — tag label/arrow on shell for getView */
    private FrameLayout createHomeMenuRowShell() {
        int textPadLeft = (int) getResources().getDimension(R.dimen.y1_menu_text_pad_left);
        float menuTextPx = getResources().getDimension(R.dimen.y1_menu_text_size);
        Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(y1RowHeightPx);
        int[] arrowLayout = y1ArrowLayout(arrowBmp);

        FrameLayout row = new FrameLayout(this);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setSoundEffectsEnabled(false);

        TextView label = new TextView(this);
        label.setFocusable(false);
        label.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        label.setIncludeFontPadding(false);
        label.setBackgroundColor(0x00000000);
        ThemeManager.applyThemedTextStyle(label, ThemeManager.getHomeMenuTextColorNormal());
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        labelLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        labelLp.leftMargin = textPadLeft;
        labelLp.rightMargin = arrowLayout[2];
        row.addView(label, labelLp);

        ImageView arrow = new ImageView(this);
        arrow.setFocusable(false);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (arrowBmp != null) arrow.setImageBitmap(arrowBmp);
        arrow.setVisibility(View.GONE);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(arrowLayout[0], arrowLayout[1]);
        arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
        arrowLp.rightMargin = (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
        row.addView(arrow, arrowLp);

        row.setTag(HOME_MENU_TAG_LABEL, label);
        row.setTag(HOME_MENU_TAG_ARROW, arrow);
        return row;
    }

    private void scrollHomeMenuToIndex(int index) {
        if (containerHomeMenuItems == null || containerHomeMenuItems.getChildCount() == 0) return;
        if (index < 0 || index >= containerHomeMenuItems.getChildCount()) return;
        final View row = containerHomeMenuItems.getChildAt(index);
        if (row == null) return;
        row.post(new Runnable() {
            @Override
            public void run() {
                View target = getHomeMenuRow(index);
                if (target != null) {
                    target.requestFocus();
                    if (menuScroll != null) {
                        menuScroll.requestChildFocus(containerHomeMenuItems, target);
                    }
                }
                refreshHomeMenuRowStyles();
                updateStatusBarTitle();
                updateHomeMenuPreview(focusedHomeMenuIndex);
            }
        });
    }

    private void updateHomeMenuPreview(int index) {
        if (isFullWidthMenus || index < 0 || index >= homeMenuEntries.size()) return;
        boolean anyWidgetActive = isWidgetClockOn || isWidgetBatteryOn || isWidgetAlbumOn;
        if (anyWidgetActive) return;

        String id = homeMenuEntries.get(index).id;
        if (HomeMenuConfig.ID_NOW_PLAYING.equals(id)) {
            if (!playback.hasAnyQueue()) {
                Bitmap previewIcon = resolveHomeMenuPreviewIconForId(id);
                if (previewIcon != null) {
                    ivMenuPreview.setImageBitmap(previewIcon);
                    ivMenuPreview.setVisibility(View.VISIBLE);
                } else {
                    ivMenuPreview.setVisibility(View.GONE);
                }
                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                    tvMenuPreviewTitle.setVisibility(View.GONE);
                    tvMenuPreviewArtist.setVisibility(View.GONE);
                }
            } else {
                if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        ivMenuPreview.setImageBitmap(BitmapFactory.decodeByteArray(
                                lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts));
                    } catch (Exception e) {
                        ivMenuPreview.setImageBitmap(ThemeManager.getCustomIcon(
                                "icon_default_album.png", this, R.drawable.default_album));
                    }
                } else {
                    ivMenuPreview.setImageBitmap(ThemeManager.getCustomIcon(
                            "icon_default_album.png", this, R.drawable.default_album));
                }
                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null && tvPlayerTitle != null) {
                    tvMenuPreviewTitle.setVisibility(View.VISIBLE);
                    tvMenuPreviewArtist.setVisibility(View.VISIBLE);
                    tvMenuPreviewTitle.setText(tvPlayerTitle.getText());
                    tvMenuPreviewArtist.setText(tvPlayerArtist != null ? tvPlayerArtist.getText() : "");
                }
            }
        } else {
            Bitmap previewIcon = resolveHomeMenuPreviewIconForId(id);
            if (previewIcon != null) {
                ivMenuPreview.setImageBitmap(previewIcon);
                ivMenuPreview.setVisibility(View.VISIBLE);
            } else {
                ivMenuPreview.setVisibility(View.GONE);
            }
            if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                tvMenuPreviewTitle.setVisibility(View.GONE);
                tvMenuPreviewArtist.setVisibility(View.GONE);
            }
        }
    }

    private boolean shouldShowNowPlayingHome() {
        if (playback.hasAnyQueue()) return true;
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void onHomeMenuActivate(String id) {
        if (HomeMenuConfig.ID_NOW_PLAYING.equals(id)) {
            if (hasActiveMediaPlayback()) {
                changeScreen(STATE_PLAYER);
            } else if (playback.hasAnyQueue()) {
                openMusicQueueEditor();
            } else {
                currentBrowserMode = BROWSER_ROOT;
                changeScreen(STATE_BROWSER);
            }
        } else if (HomeMenuConfig.ID_MUSIC.equals(id)) {
            currentBrowserMode = BROWSER_ROOT;
            changeScreen(STATE_BROWSER);
        } else if (HomeMenuConfig.ID_BLUETOOTH.equals(id)) {
            changeScreen(STATE_BLUETOOTH);
        } else if (HomeMenuConfig.ID_SETTINGS.equals(id)) {
            changeScreen(STATE_SETTINGS);
        } else if (HomeMenuConfig.ID_FM.equals(id)) {
            try {
                Intent fm = new Intent();
                fm.setClassName("com.innioasis.fm", "com.innioasis.fm.FMMainActivity");
                fm.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fm);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_fm_unavailable), Toast.LENGTH_SHORT).show();
            }
        } else if (HomeMenuConfig.ID_PC_UPLOAD.equals(id)) {
            if (!ConnectivityHelper.hasLocalNetwork(this)) {
                Toast.makeText(this, getString(R.string.toast_requires_wifi), Toast.LENGTH_SHORT).show();
                return;
            }
            changeScreen(STATE_WEBSERVER);
        } else if (HomeMenuConfig.ID_PODCASTS.equals(id)) {
            if (ConnectivityHelper.isOnline(this)) {
                changeScreen(STATE_PODCASTS);
            } else if (com.solar.launcher.podcast.PodcastLibrary.hasSavedContent()) {
                changeScreen(STATE_PODCASTS);
                buildPodcastSavedShowsUI();
            } else {
                Toast.makeText(this, getString(R.string.toast_internet_required), Toast.LENGTH_SHORT).show();
            }
        } else if (HomeMenuConfig.ID_SOULSEEK.equals(id)) {
            if (!requireInternet(R.string.toast_internet_required)) return;
            openSoulseekScreen();
        } else if (HomeMenuConfig.ID_THEMES.equals(id) || HomeMenuConfig.ID_GET_THEMES.equals(id)) {
            openThemesScreen(null);
        } else if (HomeMenuConfig.ID_MORE.equals(id)) {
            changeScreen(STATE_MORE);
        } else if (HomeMenuConfig.ID_VIDEOS.equals(id)) {
            Toast.makeText(this, getString(R.string.home_videos_coming_soon), Toast.LENGTH_LONG).show();
        } else if (HomeMenuConfig.ID_PHOTOS.equals(id)) {
            Toast.makeText(this, getString(R.string.home_photos_coming_soon), Toast.LENGTH_LONG).show();
        } else if (HomeMenuConfig.ID_APPS.equals(id)) {
            changeScreen(STATE_APPS);
        }
    }

    private void updateStatusBarTitle() {
        if (tvStatusClock == null) return;
        if (!statusBarShowsTitle && currentScreenState == STATE_MENU) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
            tvStatusClock.setText(sdf.format(new java.util.Date()));
        } else {
            tvStatusClock.setText(getStatusBarContextTitle());
        }
        tvStatusClock.setSelected(true);
    }

    private String getStatusBarContextTitle() {
        switch (currentScreenState) {
            case STATE_MENU:
                return getString(R.string.status_home);
            case STATE_BROWSER: return browserStatusTitle != null ? browserStatusTitle : getString(R.string.status_library_main);
            case STATE_PLAYER: return getString(R.string.status_now_playing);
            case STATE_SETTINGS: return resolveSettingsSubTitle();
            case STATE_BLUETOOTH: return getString(R.string.status_bluetooth_scan);
            case STATE_WIFI: return getString(R.string.status_wifi_networks);
            case STATE_WIFI_KEYBOARD: return getKeyboardStatusBarTitle();
            case STATE_BRIGHTNESS: return getString(R.string.status_brightness);
            case STATE_STORAGE: return getString(R.string.status_storage);
            case STATE_WEBSERVER: return getString(R.string.status_pc_upload);
            case STATE_PODCASTS: return getString(R.string.status_podcasts);
            case STATE_SOULSEEK: return getString(R.string.status_soulseek);
            case STATE_APPS: return getString(R.string.status_apps);
            case STATE_MORE: return getString(R.string.path_more);
            default: return getString(R.string.status_home);
        }
    }

    private String getKeyboardStatusBarTitle() {
        if (keyboardPurpose == KEYBOARD_WIFI) {
            return getString(R.string.status_wifi_keyboard, targetWifiSsid);
        }
        if (keyboardPurpose == KEYBOARD_SOULSEEK_USER) {
            return getString(R.string.keyboard_soulseek_username);
        }
        if (keyboardPurpose == KEYBOARD_SOULSEEK_SEARCH) {
            return getString(R.string.status_reach_search);
        }
        if (keyboardPurpose == KEYBOARD_PODCAST_SEARCH) {
            return getString(R.string.status_podcast_search);
        }
        return getString(R.string.keyboard_soulseek_password);
    }

    private void applyThemedStatusIcon(ImageView view, String primaryKey, String secondaryKey, int fallbackRes, int fallbackTint) {
        if (view == null) return;
        Bitmap icon = ThemeManager.getStatusIcon(primaryKey);
        if (icon == null && secondaryKey != null) icon = ThemeManager.getStatusIcon(secondaryKey);
        if (icon != null) {
            view.setImageBitmap(icon);
            view.clearColorFilter();
        } else {
            view.setImageResource(fallbackRes);
            view.setColorFilter(fallbackTint);
        }
    }

    private void updatePlaybackStatusIcon() {
        if (ivStatusPlayback == null) return;
        boolean playing = false;
        try {
            playing = mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception ignored) {}
        boolean hasQueue = playback.hasAnyQueue();
        if (!playing && !hasQueue) {
            ivStatusPlayback.setVisibility(View.GONE);
            return;
        }
        Bitmap icon = ThemeManager.getStatusIcon(playing ? "playing" : "pause");
        int fallbackRes = playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause;
        if (icon == null && !playing) {
            // ponytail: old themes may define stop but not pause.
            icon = ThemeManager.getStatusIcon("stop");
        }
        if (icon != null) {
            ivStatusPlayback.setImageBitmap(icon);
            ivStatusPlayback.clearColorFilter();
        } else {
            ivStatusPlayback.setImageResource(fallbackRes);
            ivStatusPlayback.setColorFilter(ThemeManager.getTextColorPrimary());
        }
        ivStatusPlayback.setVisibility(View.VISIBLE);
    }

    private void changeScreen(int state) {
        dismissThemedContextMenu();
        hideFastScrollLetter();
        if (currentScreenState == STATE_SOULSEEK && state != STATE_SOULSEEK && shouldConfirmReachDownloadLeave(state)) {
            if (soulseekBailWithoutConfirm()) {
                cancelSoulseekDownloadSilent();
                soulseekDownloadStalled = false;
                soulseekDownloadUiFailed = false;
                soulseekFailedResult = null;
                applyScreenChange(state);
                return;
            }
            pendingScreenChange = state;
            confirmStopReachDownload(new Runnable() {
                @Override
                public void run() {
                    int target = pendingScreenChange;
                    pendingScreenChange = -1;
                    if (target >= 0) applyScreenChange(target);
                }
            });
            return;
        }
        applyScreenChange(state);
    }

    private void applyScreenChange(int state) {
        if (currentScreenState == STATE_PLAYER && state != STATE_PLAYER) {
            flushPodcastResumeIfNeeded();
            pausePodcastBackgroundDownload();
            clearPlayerScrubCursorMode(false);
            if (!playback.isMusicActive() || state != STATE_SETTINGS
                    || !SettingsScreens.MUSIC_QUEUE.equals(settingsSubScreenKey)) {
                purgeStreamTempFiles();
            }
        }
        if (state != STATE_PLAYER) maybeRenamePodcastGrowingCache();

        SessionLifecycle.onLeaveScreen(this, currentScreenState, state);
        if (state == STATE_PLAYER && currentScreenState != STATE_PLAYER) {
            playerReturnScreen = currentScreenState;
            if (currentScreenState == STATE_PODCASTS) {
                playerReturnPodcastUiMode = podcastUiMode;
            }
        }
        if (state == STATE_PLAYER && playback.isPodcastActive() && podcastDownloadPaused) {
            maybeResumePodcastDownload();
        }

        int safeFocusIndex = lastSettingsFocusIndex;
        if (state != STATE_SETTINGS) {
            settingsSubScreenKey = null;
            settingsSubScreenExtra = null;
            hideThemeGalleryInterstitial();
            clearThemeGalleryPreview();
        }
        currentScreenState = state;
        updateSoulseekSharePolicy();
        layoutMainMenu.setVisibility(state == STATE_MENU ? View.VISIBLE : View.GONE);
        layoutBrowserMode.setVisibility((state == STATE_BROWSER || state == STATE_PODCASTS || state == STATE_SOULSEEK || state == STATE_APPS || state == STATE_MORE) ? View.VISIBLE : View.GONE);
        layoutPlayerMode.setVisibility(state == STATE_PLAYER ? View.VISIBLE : View.GONE);
        layoutSettingsMode.setVisibility(state == STATE_SETTINGS ? View.VISIBLE : View.GONE);
        layoutBluetoothMode.setVisibility(state == STATE_BLUETOOTH ? View.VISIBLE : View.GONE);
        layoutWifiMode.setVisibility(state == STATE_WIFI ? View.VISIBLE : View.GONE);
        layoutWifiKeyboard.setVisibility(state == STATE_WIFI_KEYBOARD ? View.VISIBLE : View.GONE);
        layoutBrightnessMode.setVisibility(state == STATE_BRIGHTNESS ? View.VISIBLE : View.GONE);
        layoutStorageMode.setVisibility(state == STATE_STORAGE ? View.VISIBLE : View.GONE);
        layoutWebServerMode.setVisibility(state == STATE_WEBSERVER ? View.VISIBLE : View.GONE);
        layoutVolumeOverlay.setVisibility(View.GONE);
        applyStatusBarTheme();
        if (state == STATE_MENU) {
            isPickingBackground = false;
            View c = getCurrentFocus();
            if (c == null) requestFirstHomeMenuFocus();
            updateHomeMenuPreview(focusedHomeMenuIndex);
        }
        updateStatusBarTitle();
        updatePlaybackStatusIcon();
        updateScreenBackground(state);
        if (state == STATE_BROWSER) {
            if (currentBrowserMode == BROWSER_ROOT || currentBrowserMode == BROWSER_FOLDER) {
                buildFileBrowserUI();
            } else if (currentBrowserMode == BROWSER_ARTISTS) {
                buildVirtualCategories("ARTIST");
            } else if (currentBrowserMode == BROWSER_ALBUMS) {
                buildVirtualCategories("ALBUM");
            } else if (currentBrowserMode == BROWSER_GENRES) {
                buildVirtualCategories("GENRE");
            } else if (currentBrowserMode == BROWSER_ARTIST_ALBUMS) {
                buildArtistAlbums();
            } else if (currentBrowserMode == BROWSER_PLAYLISTS) {
                buildPlaylistsUI();
            } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                buildVirtualSongs();
            } else {
                currentBrowserMode = BROWSER_ROOT;
                buildFileBrowserUI();
            }
        } else if (state == STATE_PODCASTS) {
            if (podcastUiModeOnReturn >= 0) {
                restorePodcastUi(podcastUiModeOnReturn);
                podcastUiModeOnReturn = PODCAST_UI_RESTORE_NONE;
            } else {
                buildPodcastSearchUI();
            }
        } else if (state == STATE_SOULSEEK) {
            buildSoulseekSearchUI();
        } else if (state == STATE_APPS) {
            buildAppsLauncherUI();
        } else if (state == STATE_MORE) {
            buildMoreMenuUI();
        }
        if (state == STATE_BROWSER || state == STATE_SOULSEEK || state == STATE_PODCASTS || state == STATE_APPS || state == STATE_MORE) {
            applyPodcastBrowserLayout();
        }
        refreshBrowserPathBreadcrumb();
        if (state == STATE_SETTINGS) {
            applyFullWidthMenusLayout();
            lastSettingsFocusIndex = safeFocusIndex;
            if (SettingsScreens.MUSIC_QUEUE.equals(settingsSubScreenKey)) {
                buildMusicQueueEditorUI();
            } else if (SettingsScreens.THEMES.equals(settingsSubScreenKey)) {
                buildUnifiedThemesUI();
            } else if (SettingsScreens.THEME_VARIANT.equals(settingsSubScreenKey) && themeVariantEntry != null) {
                openThemeVariantBrowser(themeVariantEntry);
            } else {
                buildSettingsUI();
            }

        } else if (state == STATE_BLUETOOTH) {
            startBluetoothScan();
        } else if (state == STATE_WIFI) {
            startWifiScan();
        } else if (state == STATE_WIFI_KEYBOARD) {
            openKeyboard();
        } else if (state == STATE_BRIGHTNESS) {
            loadBrightnessUI();
        } else if (state == STATE_STORAGE) {
            loadStorageUI();
        } else if (state == STATE_WEBSERVER) {
            updateWebServerUI();
            refreshY1ThemedActionButtons();
            btnServerToggle.requestFocus();
        }
    }

    private void loadBrightnessUI() {
        try {
            currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    255);
        } catch (Exception e) {
        }
        pbBrightness.setProgress(currentSystemBrightness);
        int percent = (int) (((float) currentSystemBrightness / 255.0f) * 100);
        tvBrightnessVal.setText(percent + "%");
    }

    private void updateBrightness(int newBrightness) {
        currentSystemBrightness = newBrightness;
        pbBrightness.setProgress(currentSystemBrightness);
        int percent = (int) (((float) currentSystemBrightness / 255.0f) * 100);
        tvBrightnessVal.setText(percent + "%");

        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, currentSystemBrightness);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = currentSystemBrightness / 255.0f;
            getWindow().setAttributes(layoutParams);
        } catch (Exception e) {
        }
    }

    // 💡 [수정] 기존 막대바를 숨기고 우리가 만든 원형 차트를 동적으로 띄워주는 로직
    // 💡 [수정] 스토리지 상세 정보 텍스트 적용
    // 💡 [완벽 수정] 스토리지 용량 계산 에러(오버플로우) 방지 및 진짜 테마 색상 적용
    private void loadStorageUI() {
        try {
            android.os.StatFs stat = new android.os.StatFs("/storage/sdcard0");

            // 🚀 [버그 1 해결] 기기 용량이 클 때 숫자가 폭발(오버플로우)해서 에러가 나는 것을 막기 위해 (long)으로 강제 변환하여 계산합니다!
            long blockSize = (long) stat.getBlockSize();
            long total = ((long) stat.getBlockCount() * blockSize) / (1024 * 1024);
            long free = ((long) stat.getAvailableBlocks() * blockSize) / (1024 * 1024);
            long used = total - free;

            if (pbStorage != null) pbStorage.setVisibility(View.GONE);

            LinearLayout storageLayout = findViewById(R.id.layout_storage_mode);
            PieChartView pieChart = (PieChartView) storageLayout.findViewWithTag("pie_chart");

            if (pieChart == null) {
                pieChart = new PieChartView(this);
                pieChart.setTag("pie_chart");

                // 🚀 [버그 2 해결] 차트가 너무 커서 아래 글씨를 화면 밖으로 밀어내지 않도록 크기를 140dp로 최적화합니다.
                int size = (int)(140 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(0, 0, 0, 30);
                pieChart.setLayoutParams(lp);

                storageLayout.addView(pieChart, 1);
            }

            // 🚀 [버그 3 해결] 밋밋한 흰색(글자색) 대신, 테마의 진짜 강조 색상(버튼 포커스 색상)을 뽑아와서 투명도를 뺀 원색으로 칠합니다!
            int themeColor = ThemeManager.getProgressColor();
            pieChart.setStorageData(used, total, themeColor);

            // 텍스트 정보 세팅 및 화면 강제 노출
            tvStorageDetails.setText("Total Capacity :  " + total + " MB\nUsed Space :  " + used + " MB\nFree Space :  " + free + " MB");
            tvStorageDetails.setGravity(android.view.Gravity.CENTER);
            tvStorageDetails.setLineSpacing(15f, 1f);
            tvStorageDetails.setVisibility(View.VISIBLE);

        } catch (Exception e) {
            tvStorageDetails.setText("Storage Error: Failed to calculate space.");
            tvStorageDetails.setVisibility(View.VISIBLE);
        }
    }
    private static final long CONTEXT_MENU_CLICK_SUPPRESS_MS = 1000;

    private void handleCenterShortClick() {
        if (themedContextMenu != null && themedContextMenu.isShowing()) return;
        if (System.currentTimeMillis() < suppressListClickUntil) return;
        if (currentScreenState == STATE_PLAYER) {
            if (playerScrubCursorActive) {
                commitPlayerScrubCursor();
            } else {
                enterPlayerScrubCursorMode();
            }
            clickFeedback();
        }
        else if (currentScreenState == STATE_WIFI_KEYBOARD) {
            handleKeyboardInput();
        } else if (currentScreenState == STATE_MENU) {
            clickFeedback();
            if (focusedHomeMenuIndex >= 0 && focusedHomeMenuIndex < homeMenuEntries.size()) {
                onHomeMenuActivate(homeMenuEntries.get(focusedHomeMenuIndex).id);
            }
        } else if (currentScreenState != STATE_BRIGHTNESS && currentScreenState != STATE_STORAGE
                && currentScreenState != STATE_PLAYER) {
            if (currentScreenState == STATE_SETTINGS && isHomeMoreArrangeScreen()) {
                clickFeedback();
                if (homeMoreMoveModeId == null) {
                    String id = homeScreenOrderFocusedId();
                    if (id != null) {
                        View focused = getCurrentFocus();
                        if (focused != null) {
                            homeScreenOrderFocusIndex = containerSettingsItems.indexOfChild(focused);
                        }
                        homeMoreMoveModeId = id;
                        refreshHomeArrangeMoveUi(true);
                    }
                } else {
                    homeMoreMoveModeId = null;
                    refreshHomeArrangeMoveUi(true);
                }
                return;
            }
            if (currentScreenState == STATE_SETTINGS && isHomeScreenArrangeScreen()) {
                clickFeedback();
                if (homeScreenMoveModeId == null) {
                    String id = homeScreenOrderFocusedId();
                    if (id != null) {
                        View focused = getCurrentFocus();
                        if (focused != null) {
                            homeScreenOrderFocusIndex = containerSettingsItems.indexOfChild(focused);
                        }
                        homeScreenMoveModeId = id;
                        refreshHomeArrangeMoveUi(false);
                    }
                } else {
                    homeScreenMoveModeId = null;
                    refreshHomeArrangeMoveUi(false);
                }
                return;
            }
            if (currentScreenState == STATE_SETTINGS && isThemeListActive()) {
                clickFeedback();
                ThemeBrowser.Row row = themeBrowserFocusedRow();
                if (row != null) onThemeBrowserRowClick(row);
                return;
            }
            if (currentScreenState == STATE_SETTINGS && isMusicQueueEditorScreen()) {
                clickFeedback();
                int listPos = musicQueueListPosition();
                if (listPos <= 0) {
                    if (musicQueueMoveFrom >= 0) {
                        musicQueueMoveFrom = -1;
                        refreshMusicQueueList();
                    } else {
                        setMusicQueueListVisible(false);
                        changeScreen(musicQueueReturnScreen);
                    }
                    return;
                }
                musicQueueEditorFocus = listPos;
                int idx = listPos - 1;
                if (musicQueueMoveFrom < 0) {
                    if (canPickMusicQueueMoveFrom(idx)) {
                        musicQueueMoveFrom = idx;
                        refreshMusicQueueList();
                    }
                } else {
                    if (idx == musicQueueMoveFrom) {
                        musicQueueMoveFrom = -1;
                        refreshMusicQueueList();
                    } else if (canDropMusicQueueMoveAt(idx)) {
                        applyMusicQueueMove(musicQueueMoveFrom, idx);
                        musicQueueMoveFrom = -1;
                    }
                }
                return;
            }
            View c = getCurrentFocus();
            if (c != null) {
                if (System.currentTimeMillis() < suppressListClickUntil) return;
                c.performClick();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // ponytail: intercept center before focused rows/ListView eat it (com.innioasis.y1 BaseActivity)
        if (isCenterKey(event.getKeyCode())) {
            if (isWakingKeyEvent(event)) return true;
            if (themedContextMenu != null && themedContextMenu.isShowing()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) return trackCenterKeyDown(event, true);
                if (event.getAction() == KeyEvent.ACTION_UP) return handleCenterKeyUp(event, true);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) return trackCenterKeyDown(event, false);
            if (event.getAction() == KeyEvent.ACTION_UP) return handleCenterKeyUp(event, false);
            return true;
        }
        if (themedContextMenu != null && themedContextMenu.isShowing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return onKeyDown(event.getKeyCode(), event);
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return onKeyUp(event.getKeyCode(), event);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isCenterKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private boolean isWakingKeyEvent(KeyEvent event) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20) isScreenOn = pm.isInteractive();
            else isScreenOn = pm.isScreenOn();
        } catch (Exception ignored) {}
        return !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - lastScreenOnTime < 500);
    }

    // 💡 [추가] 과부하 방지용 타이머 변수
    private long lastClickTime = 0;

    // 💡 앱 자체의 억지 소리 발생 코드를 완전히 삭제합니다! (기기 하드웨어 소리만 사용)
    private void clickFeedback() {
        long now = System.currentTimeMillis();

        // 🚀 [UI 멈춤 완벽 차단] 0.03초 이내에 연속으로 들어온 휠 신호는 진동 모터를 울리지 않고 생략합니다!
        // 이 방어막 하나가 빠른 휠 스크롤 시 화면 딜레이를 80% 이상 없애줍니다.
        if (now - lastClickTime < 30) return;
        lastClickTime = now;

        try {
            if (isVibrationEnabled) {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null)
                    v.vibrate(20); // 🚀 진동 길이도 30 -> 20으로 줄여서 모터가 더 빨리 쉬게 만듭니다.
            }
        } catch (Exception e) {
        }
    }

    private void openKeyboard() {
        typedPassword = keyboardPrefill != null ? keyboardPrefill : "";
        keyboardPrefill = null;
        keyboardIndex = 0;
        keyboardPpLongDoCase = true;
        if (keyboardPurpose == KEYBOARD_WIFI) {
            tvKeyboardSsid.setText(getString(R.string.keyboard_target_wifi, targetWifiSsid));
        } else if (keyboardPurpose == KEYBOARD_SOULSEEK_USER) {
            tvKeyboardSsid.setText(getString(R.string.keyboard_soulseek_username));
        } else if (keyboardPurpose == KEYBOARD_SOULSEEK_SEARCH) {
            tvKeyboardSsid.setText(getString(R.string.soulseek_search_hint));
        } else if (keyboardPurpose == KEYBOARD_PODCAST_SEARCH) {
            tvKeyboardSsid.setText(getString(R.string.podcasts_search_hint));
        } else {
            tvKeyboardSsid.setText(getString(R.string.keyboard_soulseek_password));
        }
        updateStatusBarTitle();
        applyKeyboardTheme();
        updateKeyboardUI();
    }

    private void openWifiKeyboard(String ssid) {
        keyboardPurpose = KEYBOARD_WIFI;
        keyboardReturnState = STATE_WIFI;
        targetWifiSsid = ssid;
        changeScreen(STATE_WIFI_KEYBOARD);
    }

    private boolean isSoulseekKeyboardPurpose() {
        return keyboardPurpose == KEYBOARD_SOULSEEK_USER
                || keyboardPurpose == KEYBOARD_SOULSEEK_PASS
                || keyboardPurpose == KEYBOARD_SOULSEEK_SEARCH;
    }

    private boolean isPodcastKeyboardPurpose() {
        return keyboardPurpose == KEYBOARD_PODCAST_SEARCH;
    }

    private boolean isTextEntryKeyboardPurpose() {
        return isSoulseekKeyboardPurpose() || isPodcastKeyboardPurpose();
    }

    private String keyboardDisplayChar(String ch) {
        if ("[CONN]".equals(ch)) return getString(R.string.keyboard_enter);
        return ch;
    }

    private void openPodcastSearchKeyboard() {
        if (!requireInternet(R.string.podcasts_wifi_required_search)) return;
        keyboardPurpose = KEYBOARD_PODCAST_SEARCH;
        keyboardReturnState = STATE_PODCASTS;
        keyboardPrefill = podcastLastQuery != null ? podcastLastQuery : "";
        changeScreen(STATE_WIFI_KEYBOARD);
    }

    private void openSoulseekSearchKeyboard() {
        openSoulseekSearchKeyboard(soulseekLastQuery != null ? soulseekLastQuery : "");
    }

    private void openSoulseekSearchKeyboard(String initialQuery) {
        if (!requireInternet(R.string.soulseek_wifi_required)) return;
        keyboardPurpose = KEYBOARD_SOULSEEK_SEARCH;
        keyboardReturnState = STATE_SOULSEEK;
        keyboardPrefill = initialQuery != null ? initialQuery : "";
        changeScreen(STATE_WIFI_KEYBOARD);
    }

    private void openSoulseekScreen() {
        openSoulseekScreen(false);
    }

    private void openReachFromLibrary() {
        if (!hasInternetConnection()) return;
        soulseekReturnScreen = STATE_BROWSER;
        soulseekReturnSettingsSubKey = null;
        changeScreen(STATE_SOULSEEK);
    }

    private void openSoulseekScreen(boolean preserveReturnState) {
        if (!preserveReturnState) {
            soulseekReturnScreen = currentScreenState;
            if (currentScreenState == STATE_PODCASTS) {
                soulseekReturnPodcastUiMode = podcastUiMode;
            }
            if (currentScreenState == STATE_SETTINGS && SettingsScreens.isSoulseek(settingsSubScreenKey)) {
                soulseekReturnSettingsSubKey = settingsSubScreenKey;
            } else {
                soulseekReturnSettingsSubKey = null;
            }
        }
        changeScreen(STATE_SOULSEEK);
    }

    private void returnFromSoulseek() {
        if (soulseekReturnScreen == STATE_MENU) {
            changeScreen(STATE_MENU);
        } else if (soulseekReturnScreen == STATE_PODCASTS) {
            podcastUiModeOnReturn = soulseekReturnPodcastUiMode;
            changeScreen(STATE_PODCASTS);
        } else if (soulseekReturnSettingsSubKey != null
                && SettingsScreens.isSoulseek(soulseekReturnSettingsSubKey)) {
            currentScreenState = STATE_SETTINGS;
            layoutMainMenu.setVisibility(View.GONE);
            layoutBrowserMode.setVisibility(View.GONE);
            layoutSettingsMode.setVisibility(View.VISIBLE);
            restoreSoulseekSettingsScreen(soulseekReturnSettingsSubKey);
            updateStatusBarTitle();
        } else {
            changeScreen(soulseekReturnScreen);
        }
        soulseekReturnSettingsSubKey = null;
    }

    private void restoreSettingsAfterSoulseekAccount() {
        if (keyboardReturnSettingsSubKey != null
                && SettingsScreens.isSoulseek(keyboardReturnSettingsSubKey)) {
            currentScreenState = STATE_SETTINGS;
            layoutWifiKeyboard.setVisibility(View.GONE);
            layoutSettingsMode.setVisibility(View.VISIBLE);
            restoreSoulseekSettingsScreen(keyboardReturnSettingsSubKey);
            updateStatusBarTitle();
        } else {
            changeScreen(STATE_SETTINGS);
        }
        keyboardReturnSettingsSubKey = null;
    }

    private void openSoulseekAccountKeyboard() {
        keyboardPurpose = KEYBOARD_SOULSEEK_USER;
        keyboardReturnState = STATE_SETTINGS;
        keyboardReturnSettingsSubKey = SettingsScreens.isSoulseek(settingsSubScreenKey)
                ? settingsSubScreenKey : null;
        pendingSoulseekUser = "";
        changeScreen(STATE_WIFI_KEYBOARD);
    }

    private void updateKeyboardUI() {
        int len = KEYBOARD_CHARS.length;
        int idxPprev = (keyboardIndex - 2 + len) % len;
        int idxPrev = (keyboardIndex - 1 + len) % len;
        int idxNext = (keyboardIndex + 1) % len;
        int idxNnext = (keyboardIndex + 2) % len;
        tvKeyPprev.setText(keyboardDisplayChar(KEYBOARD_CHARS[idxPprev]));
        tvKeyPrev.setText(keyboardDisplayChar(KEYBOARD_CHARS[idxPrev]));
        tvKeyCurrent.setText(keyboardDisplayChar(KEYBOARD_CHARS[keyboardIndex]));
        tvKeyNext.setText(keyboardDisplayChar(KEYBOARD_CHARS[idxNext]));
        tvKeyNnext.setText(keyboardDisplayChar(KEYBOARD_CHARS[idxNnext]));
        if (keyboardPurpose == KEYBOARD_WIFI && isTargetWifiOpen) {
            tvKeyboardInput.setText(getString(R.string.wifi_open_network));
            keyboardIndex = len - 1;
            tvKeyCurrent.setText(KEYBOARD_CHARS[keyboardIndex]);
        } else if (keyboardPurpose == KEYBOARD_WIFI) {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? getString(R.string.keyboard_enter_wifi_password) : typedPassword);
        } else if (keyboardPurpose == KEYBOARD_SOULSEEK_USER) {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? getString(R.string.keyboard_blank_auto) : typedPassword);
        } else if (keyboardPurpose == KEYBOARD_SOULSEEK_SEARCH) {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? getString(R.string.soulseek_type_search) : typedPassword);
        } else if (keyboardPurpose == KEYBOARD_PODCAST_SEARCH) {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? getString(R.string.podcasts_type_search) : typedPassword);
        } else {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? getString(R.string.keyboard_enter_password) : typedPassword);
        }
        boolean inputPlaceholder = typedPassword.length() == 0
                && keyboardPurpose != KEYBOARD_WIFI;
        if (keyboardPurpose == KEYBOARD_WIFI && isTargetWifiOpen) {
            inputPlaceholder = false;
        } else if (keyboardPurpose == KEYBOARD_WIFI) {
            inputPlaceholder = typedPassword.length() == 0;
        }
        styleKeyboardInputField(inputPlaceholder);
        styleKeyboardCurrentKey();
    }

    private void handleKeyboardInput() {
        String selectedChar = KEYBOARD_CHARS[keyboardIndex];
        clickFeedback();
        if (selectedChar.equals("[DEL]")) {
            if (typedPassword.length() > 0)
                typedPassword = typedPassword.substring(0, typedPassword.length() - 1);
        } else if (selectedChar.equals("[CONN]")) {
            handleKeyboardEnter();
        } else if (selectedChar.equals("[SPC]")) {
            typedPassword += " ";
        } else {
            typedPassword += selectedChar;
            if (selectedChar.length() == 1) {
                char ch = selectedChar.charAt(0);
                if (ch >= 'A' && ch <= 'Z') {
                    keyboardIndex = KeyboardCharset.lowercaseIndexForChar(ch);
                    keyboardPpLongDoCase = true;
                }
            }
        }
        updateKeyboardUI();
    }

    private void handleKeyboardMediaDel() {
        clickFeedback();
        if (typedPassword.length() > 0) {
            typedPassword = typedPassword.substring(0, typedPassword.length() - 1);
        }
        updateKeyboardUI();
    }

    private void handleKeyboardMediaSpace() {
        clickFeedback();
        typedPassword += " ";
        updateKeyboardUI();
    }

    private void handleKeyboardEnter() {
        if (keyboardPurpose == KEYBOARD_WIFI) connectToWifi();
        else if (keyboardPurpose == KEYBOARD_SOULSEEK_USER) finishSoulseekUserEntry();
        else if (keyboardPurpose == KEYBOARD_SOULSEEK_SEARCH) finishSoulseekSearchEntry();
        else if (keyboardPurpose == KEYBOARD_PODCAST_SEARCH) finishPodcastSearchEntry();
        else finishSoulseekPassEntry();
    }

    private int keyboardFlipCaseIndex(int index) {
        return KeyboardCharset.flipCaseIndex(index);
    }

    private int keyboardMapToNextCharset(int index) {
        return KeyboardCharset.mapToNextCharset(index);
    }

    private void handleKeyboardPlayPauseLongPress() {
        clickFeedback();
        if (keyboardPpLongDoCase) {
            int flipped = keyboardFlipCaseIndex(keyboardIndex);
            keyboardIndex = flipped != keyboardIndex ? flipped : keyboardMapToNextCharset(keyboardIndex);
        } else {
            keyboardIndex = keyboardMapToNextCharset(keyboardIndex);
        }
        keyboardPpLongDoCase = !keyboardPpLongDoCase;
        updateKeyboardUI();
    }

    private void connectToWifi() {
        Toast.makeText(this, getString(R.string.toast_wifi_connecting, targetWifiSsid), Toast.LENGTH_SHORT).show();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + targetWifiSsid + "\"";
            if (isTargetWifiOpen)
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            else
                conf.preSharedKey = "\"" + typedPassword + "\"";
            int netId = wm.addNetwork(conf);
            wm.disconnect();
            wm.enableNetwork(netId, true);
            wm.reconnect();
            wm.saveConfiguration();
        }
        changeScreen(STATE_WIFI);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startBluetoothScan() {
        updateStatusBarTitle();
        final BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean isOn = false;
        String statusText = "OFF";

        if (ba != null) {
            int state = ba.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                isOn = true;
                statusText = "ON";
            } else if (state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF) {
                statusText = "Wait...";
            }
        }

        View existingToggle = containerBtItems.findViewById(999991);
        if (existingToggle == null) {
            final LinearLayout btnToggle = createSettingRow(RowKeys.BT_POWER, R.string.bluetooth_power, statusText);
            btnToggle.setId(999991);
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (ba != null) {
                        boolean isCurrentlyOn = ba.isEnabled();
                        if (isCurrentlyOn) {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_bt_turning_off), Toast.LENGTH_SHORT).show();
                            ba.disable();
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_bt_turning_on), Toast.LENGTH_SHORT).show();
                            ba.enable();
                        }
                        TextView tvRight = (TextView) btnToggle.getChildAt(1);
                        tvRight.setText("Wait...");
                        if (!btnToggle.hasFocus())
                            ThemeManager.applyThemedTextStyle(tvRight, y1RowTextColorSelected(Y1_ROW_MENU));
                    }
                }
            });
            containerBtItems.addView(btnToggle, 0);

            if (btnScanBt.getParent() != null) {
                ((android.view.ViewGroup) btnScanBt.getParent()).removeView(btnScanBt);
            }
            containerBtItems.addView(btnScanBt);
        } else {
            LinearLayout btnToggle = (LinearLayout) existingToggle;
            TextView tvRight = (TextView) btnToggle.getChildAt(1);
            tvRight.setText(statusText);
            if (!btnToggle.hasFocus())
                ThemeManager.applyThemedTextStyle(tvRight, y1InlineStateColor(statusText, false));
            for (int i = containerBtItems.getChildCount() - 1; i > 0; i--) {
                View v = containerBtItems.getChildAt(i);
                if (v != btnScanBt) {
                    containerBtItems.removeViewAt(i);
                }
            }
        }

        if (!isOn) {
            btnScanBt.setText(getString(R.string.bluetooth_off));
            if (getCurrentFocus() == null && containerBtItems.getChildCount() > 0)
                containerBtItems.getChildAt(0).requestFocus();
            return;
        }

        btnScanBt.setText(getString(R.string.bluetooth_scanning));
        foundBtDevices.clear();

        // 🚀 1순위: 기기에 이미 페어링(저장)된 블루투스 기기들을 최상단에 먼저 깔아줍니다!
        try {
            java.util.Set<BluetoothDevice> pairedDevices = ba.getBondedDevices();
            if (pairedDevices != null && pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    foundBtDevices.add(device.getAddress());
                    addBluetoothItemToUI(device.getName() != null ? device.getName() : "Unknown Device", device, true, isBluetoothAudioConnected(device));
                }
            }
        } catch (Exception e) {
        }

        if (getCurrentFocus() == null && containerBtItems.getChildCount() > 0)
            containerBtItems.getChildAt(0).requestFocus();

        if (ba.isDiscovering())
            ba.cancelDiscovery();
        ba.startDiscovery();
    }

    // 💡 페어링 상태(isPaired)를 파라미터로 받아서 색상과 아이콘을 바꾸는 함수
    private void addBluetoothItemToUI(String name, final BluetoothDevice device, boolean isPaired, boolean isConnected) {
        String prefix = isConnected ? "♪ [CONNECTED] " : (isPaired ? "✔ [PAIRED] " : "🎧 ");
        final Button btnDevice = createListButton(prefix + name);

        if (isConnected || isPaired) {
            btnDevice.setTextColor(isConnected ? 0xFF00FFFF : 0xFF00FF00);
            btnDevice.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        btnDevice.setTag(device);
        btnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                pairBluetoothDevice(device);
            }
        });
        containerBtItems.addView(btnDevice);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void pairBluetoothDevice(BluetoothDevice device) {
        if (device == null) return;
        try {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                if (isBluetoothAudioConnected(device)) {
                    Toast.makeText(this, getString(R.string.toast_already_connected, device.getName()), Toast.LENGTH_SHORT).show();
                } else {
                    connectBluetoothAudio(device);
                }
                return;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            pairingDeviceAddress = device.getAddress();
            Toast.makeText(this, getString(R.string.toast_pairing, device.getName()), Toast.LENGTH_SHORT).show();
            if (!createBluetoothBond(device)) {
                pairingDeviceAddress = null;
                Toast.makeText(this, getString(R.string.toast_pairing_failed), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            pairingDeviceAddress = null;
            Toast.makeText(this, getString(R.string.toast_pairing_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean createBluetoothBond(BluetoothDevice device) {
        try {
            Method createBond = device.getClass().getMethod("createBond");
            Object result = createBond.invoke(device);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean handleBluetoothPairingRequest(BluetoothDevice device, int variant) {
        try {
            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                byte[] pin = bluetoothPinBytes("0000");
                device.getClass().getMethod("setPin", byte[].class).invoke(device, pin);
            } else if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                try {
                    device.getClass().getMethod("setPairingConfirmation", boolean.class).invoke(device, true);
                } catch (NoSuchMethodException ignored) {}
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] bluetoothPinBytes(String pin) {
        try {
            Method convert = BluetoothDevice.class.getMethod("convertPinToBytes", String.class);
            return (byte[]) convert.invoke(null, pin);
        } catch (Exception e) {
            return pin.getBytes();
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void ensureA2dpProfile() {
        if (bluetoothA2dp != null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        adapter.getProfileProxy(this, a2dpProfileListener, BluetoothProfile.A2DP);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void reconnectLastBluetoothAudio() {
        String addr = prefs.getString(PREF_LAST_BT_AUDIO, null);
        if (addr == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return;
        for (BluetoothDevice device : bonded) {
            if (addr.equals(device.getAddress())) {
                connectBluetoothAudio(device);
                return;
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void connectBluetoothAudio(BluetoothDevice device) {
        if (device == null) return;
        ensureA2dpProfile();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        pendingA2dpDevice = device;
        if (bluetoothA2dp != null) {
            connectA2dpNow(device);
            pendingA2dpDevice = null;
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void connectA2dpNow(BluetoothDevice device) {
        try {
            // ponytail: prefer privileged write via su in rooted env; fallback to app write if allowed.
            String key = "bluetooth_a2dp_sink_priority_" + device.getAddress();
            if (!putGlobalIntPrivileged(key, 1000)) {
                android.provider.Settings.Global.putInt(getContentResolver(), key, 1000);
            }
        } catch (Exception ignored) {}
        try {
            Method setPriority = bluetoothA2dp.getClass().getMethod("setPriority", BluetoothDevice.class, int.class);
            setPriority.invoke(bluetoothA2dp, device, 1000);
        } catch (Exception ignored) {}
        try {
            Method connect = bluetoothA2dp.getClass().getMethod("connect", BluetoothDevice.class);
            Object ok = connect.invoke(bluetoothA2dp, device);
            if (ok instanceof Boolean && !(Boolean) ok) {
                Toast.makeText(this, getString(R.string.toast_audio_connect_failed), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_audio_connect_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean putGlobalIntPrivileged(String key, int value) {
        if (key == null || key.trim().isEmpty()) return false;
        return runSuCommandSilently("settings put global " + shQuote(key) + " " + value);
    }

    private boolean runSuCommandSilently(String command) {
        java.io.DataOutputStream os = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new java.io.DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (os != null) {
                try { os.close(); } catch (Exception ignored) {}
            }
            if (process != null) process.destroy();
        }
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void ensurePrivilegedPermissionsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                tryGrantPrivilegedPermission("android.permission.WRITE_SECURE_SETTINGS");
                tryGrantPrivilegedPermission("android.permission.BLUETOOTH_PRIVILEGED");
            }
        }).start();
    }

    private void tryGrantPrivilegedPermission(String permission) {
        if (permission == null || permission.trim().isEmpty()) return;
        runSuCommandSilently("pm grant " + shQuote(getPackageName()) + " " + shQuote(permission));
    }

    @android.annotation.SuppressLint("MissingPermission")
    private boolean isBluetoothAudioConnected(BluetoothDevice device) {
        if (device == null) return false;
        if (device.getAddress().equals(connectedA2dpAddress)) return true;
        if (bluetoothA2dp != null) {
            try {
                return bluetoothA2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
            } catch (Exception ignored) {}
        }
        try {
            return audioManager != null && audioManager.isBluetoothA2dpOn();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startWifiScan() {
        updateStatusBarTitle();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isOn = wm != null && wm.isWifiEnabled();
        updateWifiUI(null);

        if (isOn) {
            btnScanWifi.setText(getString(R.string.bluetooth_scanning));
            foundWifiNetworks.clear();
            // 💡 무조건 최상단 전원 버튼으로 포커스 강제 이동!
            if (containerWifiItems.getChildCount() > 0)
                containerWifiItems.getChildAt(0).requestFocus();
            wm.startScan();
        } else {
            btnScanWifi.setText(getString(R.string.wifi_off));
            // 💡 무조건 최상단 전원 버튼으로 포커스 강제 이동!
            if (containerWifiItems.getChildCount() > 0)
                containerWifiItems.getChildAt(0).requestFocus();
        }
    }

    private void updateWifiUI(List<ScanResult> results) {
        final WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isOn = false;
        String statusText = "OFF";

        if (wm != null) {
            int state = wm.getWifiState();
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                isOn = true;
                statusText = "ON";
            } else if (state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_DISABLING) {
                statusText = "Wait...";
            }
        }

        View existingToggle = containerWifiItems.findViewById(999992);
        if (existingToggle == null) {
            final LinearLayout btnToggle = createSettingRow(RowKeys.WIFI_POWER, R.string.wifi_power, statusText);
            btnToggle.setId(999992);
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (wm != null) {
                        boolean isCurrentlyOn = wm.isWifiEnabled();
                        if (isCurrentlyOn) {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_wifi_turning_off), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(false);
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_wifi_turning_on), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(true);
                        }
                        TextView tvRight = (TextView) btnToggle.getChildAt(1);
                        tvRight.setText("Wait...");
                        if (!btnToggle.hasFocus())
                            ThemeManager.applyThemedTextStyle(tvRight, y1RowTextColorSelected(Y1_ROW_MENU));
                    }
                }
            });
            containerWifiItems.addView(btnToggle, 0);

            if (btnScanWifi.getParent() != null) {
                ((android.view.ViewGroup) btnScanWifi.getParent()).removeView(btnScanWifi);
            }
            containerWifiItems.addView(btnScanWifi);
        } else {
            LinearLayout btnToggle = (LinearLayout) existingToggle;
            TextView tvRight = (TextView) btnToggle.getChildAt(1);
            tvRight.setText(statusText);
            if (!btnToggle.hasFocus())
                ThemeManager.applyThemedTextStyle(tvRight, y1InlineStateColor(statusText, false));
            for (int i = containerWifiItems.getChildCount() - 1; i > 0; i--) {
                View v = containerWifiItems.getChildAt(i);
                if (v != btnScanWifi) {
                    containerWifiItems.removeViewAt(i);
                }
            }
        }

        if (!isOn)
            return;

        if (results != null) {
            foundWifiNetworks.clear();
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = manager.getConnectionInfo();
            String connectedSSID = "";
            if (wifiInfo != null && wifiInfo.getSSID() != null) {
                connectedSSID = wifiInfo.getSSID().replace("\"", "");
            }

            // 🚀 1순위: 현재 연결된 와이파이를 가장 먼저 찾아서 최상단에 배치!
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty()
                        && !WifiScanFilter.isHiddenSsid(result.SSID)
                        && !foundWifiNetworks.contains(result.SSID)) {
                    if (result.SSID.equals(connectedSSID)) {
                        foundWifiNetworks.add(result.SSID);
                        addWifiItemToUI(result.SSID, result.capabilities, true);
                    }
                }
            }

            // 🚀 2순위: 연결되지 않은 나머지 잡다한 와이파이들을 그 밑으로 나열
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty()
                        && !WifiScanFilter.isHiddenSsid(result.SSID)
                        && !foundWifiNetworks.contains(result.SSID)) {
                    foundWifiNetworks.add(result.SSID);
                    addWifiItemToUI(result.SSID, result.capabilities, false);
                }
            }
        }
    }

    // 💡 연결 상태(isConnected)를 파라미터로 직접 전달받도록 개조된 함수
    private void addWifiItemToUI(final String ssid, String capabilities, final boolean isConnected) {
        final boolean isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP");
        String lockIcon = isOpen ? "📶 " : "🔒 ";

        // 연결된 기기 앞에는 투박한 글씨 대신 애플처럼 예쁜 체크마크(✔) 부여!
        String prefix = isConnected ? "✔ " : "";

        Button btnWifi = createListButton(prefix + lockIcon + ssid);

        if (isConnected) {
            btnWifi.setTextColor(0xFF00FF00); // 눈에 확 띄는 초록색!
            btnWifi.setTypeface(null, android.graphics.Typeface.BOLD); // 굵은 글씨로 강조!
        }

        btnWifi.setTag(ssid);
        btnWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (isConnected) {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_wifi_already_connected), Toast.LENGTH_SHORT).show();
                    return;
                }

                WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                boolean isSaved = false;
                int savedNetId = -1;
                try {
                    List<WifiConfiguration> configuredNetworks = manager.getConfiguredNetworks();
                    if (configuredNetworks != null) {
                        for (WifiConfiguration conf : configuredNetworks) {
                            if (conf.SSID != null && conf.SSID.equals("\"" + ssid + "\"")) {
                                isSaved = true;
                                savedNetId = conf.networkId;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                }

                if (isSaved && savedNetId != -1) {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_wifi_saved_connecting), Toast.LENGTH_SHORT).show();
                    manager.disconnect();
                    manager.enableNetwork(savedNetId, true);
                    manager.reconnect();
                } else {
                    isTargetWifiOpen = isOpen;
                    openWifiKeyboard(ssid);
                }
            }
        });
        containerWifiItems.addView(btnWifi);
    }

    private void createBrowserSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setFocusable(false);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tv.setText(title);
        int headerColor = ThemeManager.getSectionHeaderTextColor();
        ThemeManager.applyThemedTextStyle(tv, headerColor);
        float menuTextPx = getResources().getDimension(R.dimen.y1_menu_text_size);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.85f);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        tv.setPadding(hPad, hPad, hPad, hPad / 4);
        containerBrowserItems.addView(tv);
    }

    private void createCategoryHeader(String title) {
        createCategoryHeader(title, null);
    }

    private void createCategoryHeader(String title, Object sectionTag) {
        TextView tv = new TextView(this);
        if (sectionTag != null) tv.setTag(sectionTag);
        tv.setFocusable(false);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tv.setText(title);
        int headerColor = ThemeManager.getSectionHeaderTextColor();
        ThemeManager.applyThemedTextStyle(tv, headerColor);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(10, 30, 10, 5);
        containerSettingsItems.addView(tv);
    }

    private LinearLayout createSettingRow(String rowKey, int labelResId, CharSequence rightText) {
        return createSettingRow(rowKey, labelResId, rightText, null);
    }

    private String rowLabel(String rowKey) {
        int res = RowKeys.labelResId(rowKey);
        return res != 0 ? getString(res) : rowKey;
    }

    private boolean isActiveStateText(String text) {
        return getString(R.string.common_on).equals(text)
                || getString(R.string.common_one).equals(text)
                || getString(R.string.common_all).equals(text);
    }

    /** Y1 settings list: title only on row; state/icon live in the right preview pane. */
    private LinearLayout createSettingsRow(String rowKey, int labelResId, boolean submenu) {
        final String title = getString(labelResId);
        final LinearLayout layout = new LinearLayout(this);
        layout.setTag(rowKey);
        layout.setSoundEffectsEnabled(false);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setFocusable(true);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        layout.setPadding(hPad, 0, hPad, 0);
        int rowW = y1ActiveRowWidthPx();
        layout.setBackground(getY1RowBackground(false, rowW, Y1_ROW_MENU));

        TextView tvLeft = new TextView(this);
        tvLeft.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvLeft.setText(title);
        ThemeManager.applyThemedTextStyle(tvLeft, ThemeManager.getSettingMenuTextColorNormal());
        tvLeft.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        enableMarquee(tvLeft);
        tvLeft.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        layout.addView(tvLeft);

        final TextView tvRight = new TextView(this);
        if (isFullWidthMenus && !submenu) {
            tvRight.setTag("inline_state");
            tvRight.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tvRight.setText(resolveSettingStateText(rowKey));
            tvRight.setGravity(android.view.Gravity.RIGHT);
            tvRight.setSingleLine(true);
            tvRight.setEllipsize(TextUtils.TruncateAt.END);
            String stateText = resolveSettingStateText(rowKey);
            boolean active = isActiveStateText(stateText);
            ThemeManager.applyThemedTextStyle(tvRight, active
                    ? ThemeManager.getSettingMenuTextColorNormal()
                    : ThemeManager.getTextColorSecondary());
            tvRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.y1_menu_text_size));
            tvRight.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            layout.addView(tvRight);
        }

        final ImageView rowArrow = new ImageView(this);
        if (submenu) {
            Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(y1RowHeightPx);
            int[] arrowLayout = y1ArrowLayout(arrowBmp);
            if (arrowBmp != null) rowArrow.setImageBitmap(arrowBmp);
            rowArrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rowArrow.setVisibility(View.GONE);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(arrowLayout[0], arrowLayout[1]);
            arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL;
            arrowLp.rightMargin = (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
            layout.addView(rowArrow, arrowLp);
        } else {
            rowArrow.setVisibility(View.GONE);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        layout.setLayoutParams(lp);

        layout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                int rowW = y1ActiveRowWidthPx();
                layout.setBackground(getY1RowBackground(hasFocus, rowW, Y1_ROW_MENU));
                applyY1ListRowStyle(layout, hasFocus, tvLeft,
                        (isFullWidthMenus && !submenu) ? tvRight : null,
                        submenu ? rowArrow : null, Y1_ROW_MENU);
                if (hasFocus && currentScreenState == STATE_SETTINGS) {
                    int idx = containerSettingsItems.indexOfChild(layout);
                    if (idx != -1) lastSettingsFocusIndex = idx;
                    if (isFullWidthMenus) {
                        refreshSettingsRowInline(rowKey);
                    } else {
                        updateSettingsPreview(rowKey);
                    }
                }
            }
        });
        return layout;
    }

    private void refreshSettingsPreview(String rowKey) {
        if (currentScreenState == STATE_SETTINGS) {
            if (isFullWidthMenus) {
                refreshSettingsRowInline(rowKey);
            } else {
                updateSettingsPreview(rowKey);
            }
        }
    }

    private void refreshSettingsRowInline(String rowKey) {
        if (containerSettingsItems == null) return;
        for (int i = 0; i < containerSettingsItems.getChildCount(); i++) {
            View row = containerSettingsItems.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            Object tag = row.getTag();
            if (!(tag instanceof String) || !rowKey.equals(tag)) continue;
            TextView stateView = (TextView) row.findViewWithTag("inline_state");
            if (stateView == null) return;
            String text = resolveSettingStateText(rowKey);
            stateView.setText(text != null ? text : "");
            boolean active = isActiveStateText(text);
            boolean rowFocused = row.hasFocus();
            if (active) {
                ThemeManager.applyThemedTextStyle(stateView, rowFocused
                        ? ThemeManager.getSettingMenuTextColorSelected()
                        : ThemeManager.getSettingMenuTextColorNormal());
            } else {
                ThemeManager.applyThemedTextStyle(stateView, y1InlineStateColor(text, rowFocused));
            }
            return;
        }
    }

    private void applyFullWidthMenusLayout() {
        int menuLeft = (int) getResources().getDimension(R.dimen.y1_menu_left);
        int defaultMenuW = (int) getResources().getDimension(R.dimen.y1_menu_width);
        int defaultSettingsW = (int) getResources().getDimension(R.dimen.y1_settings_menu_width);
        int defaultMenuH = (int) getResources().getDimension(R.dimen.y1_menu_height);
        int defaultSettingsH = (int) getResources().getDimension(R.dimen.y1_settings_menu_height);
        int statusH = (int) getResources().getDimension(R.dimen.y1_status_bar_height);
        int fullMenuH = screenHeightPx > statusH ? screenHeightPx - statusH : defaultMenuH;

        if (isFullWidthMenus) {
            y1RowWidthPx = screenWidthPx > 0 ? screenWidthPx : defaultMenuW;
            settingsMenuWidthPx = y1RowWidthPx;
            menuListHeightPx = fullMenuH;
            listRowWidthPx = y1RowWidthPx;
        } else {
            y1RowWidthPx = defaultMenuW;
            settingsMenuWidthPx = defaultSettingsW;
            menuListHeightPx = defaultMenuH;
            listRowWidthPx = (int) (getResources().getDimension(R.dimen.y1_screen_width)
                    - 20 * getResources().getDisplayMetrics().density);
        }

        if (menuListHost != null) {
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) menuListHost.getLayoutParams();
            mlp.width = isFullWidthMenus
                    ? FrameLayout.LayoutParams.MATCH_PARENT : y1RowWidthPx;
            mlp.height = isFullWidthMenus ? fullMenuH : defaultMenuH;
            mlp.leftMargin = isFullWidthMenus ? 0 : menuLeft;
            menuListHost.setLayoutParams(mlp);
        }
        if (settingsMenuHost != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) settingsMenuHost.getLayoutParams();
            slp.width = isFullWidthMenus
                    ? FrameLayout.LayoutParams.MATCH_PARENT : settingsMenuWidthPx;
            slp.height = isFullWidthMenus ? fullMenuH : defaultSettingsH;
            slp.leftMargin = isFullWidthMenus ? 0 : menuLeft;
            settingsMenuHost.setLayoutParams(slp);
        }
        if (settingsPreviewPane != null) {
            settingsPreviewPane.setVisibility(isFullWidthMenus ? View.GONE : View.VISIBLE);
        }
        updateHomeMenuPreviewVisibility();
        int settingsPanelH = isFullWidthMenus && screenHeightPx > 0
                ? screenHeightPx - (int) getResources().getDimension(R.dimen.y1_status_bar_height)
                : (int) getResources().getDimension(R.dimen.y1_settings_menu_height);
        applyMenuPanelBackground(menuListHost, y1RowWidthPx, menuListHeightPx,
                (int) getResources().getDimension(R.dimen.y1_menu_height));
        applyMenuPanelBackground(settingsMenuHost, settingsMenuWidthPx, settingsPanelH,
                (int) getResources().getDimension(R.dimen.y1_settings_menu_height));
        updateScreenBackground(currentScreenState);
        applyPodcastBrowserLayout();
        applySettingsListLayout();
    }

    private void applySettingsListLayout() {
        if (listThemes == null && listMusicQueue == null) return;
        int w = isFullWidthMenus && screenWidthPx > 0
                ? screenWidthPx : settingsMenuWidthPx;
        int statusH = (int) getResources().getDimension(R.dimen.y1_status_bar_height);
        int h = isFullWidthMenus && screenHeightPx > statusH
                ? screenHeightPx - statusH
                : (int) getResources().getDimension(R.dimen.y1_settings_menu_height);
        FrameLayout.LayoutParams themesLp = new FrameLayout.LayoutParams(w, h);
        FrameLayout.LayoutParams queueLp = new FrameLayout.LayoutParams(w, h);
        if (listThemes != null) listThemes.setLayoutParams(themesLp);
        if (listMusicQueue != null) listMusicQueue.setLayoutParams(queueLp);
    }

    private boolean podcastDualPaneActive() {
        if (currentScreenState != STATE_PODCASTS || isFullWidthMenus) return false;
        if (podcastUiMode == PODCAST_UI_SHOWS || podcastUiMode == PODCAST_UI_EPISODES) return true;
        return podcastUiMode == PODCAST_UI_SAVED && podcastSavedShowFolder != null
                && podcastSavedShowFolder.length() > 0;
    }

    private boolean appsDualPaneActive() {
        return currentScreenState == STATE_APPS && !isFullWidthMenus;
    }

    private void applyPodcastBrowserLayout() {
        if (browserListHost == null) return;
        int menuLeft = (int) getResources().getDimension(R.dimen.y1_menu_left);
        int narrowW = (int) getResources().getDimension(R.dimen.y1_settings_menu_width);
        int defaultMenuH = (int) getResources().getDimension(R.dimen.y1_menu_height);
        int statusH = (int) getResources().getDimension(R.dimen.y1_status_bar_height);
        int fullMenuH = screenHeightPx > statusH ? screenHeightPx - statusH : defaultMenuH;
        boolean podcastDual = podcastDualPaneActive();
        boolean appsDual = appsDualPaneActive();
        boolean dual = podcastDual || appsDual;
        boolean wideBrowser = isFullWidthMenus
                || currentScreenState == STATE_BROWSER
                || currentScreenState == STATE_SOULSEEK
                || (currentScreenState == STATE_PODCASTS && !podcastDual)
                || (currentScreenState == STATE_APPS && !appsDual);

        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) browserListHost.getLayoutParams();
        if (blp == null) {
            blp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        }
        if (wideBrowser) {
            blp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            blp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            blp.leftMargin = 0;
            if (!isFullWidthMenus && (currentScreenState == STATE_PODCASTS
                    || currentScreenState == STATE_BROWSER || currentScreenState == STATE_SOULSEEK)) {
                listRowWidthPx = (int) (getResources().getDimension(R.dimen.y1_screen_width)
                        - 20 * getResources().getDisplayMetrics().density);
            }
        } else {
            blp.width = narrowW;
            blp.height = defaultMenuH;
            blp.leftMargin = menuLeft;
            if (!isFullWidthMenus && currentScreenState == STATE_PODCASTS) listRowWidthPx = narrowW;
            if (!isFullWidthMenus && appsDual) listRowWidthPx = narrowW;
        }
        browserListHost.setLayoutParams(blp);

        if (podcastPreviewPane != null) {
            podcastPreviewPane.setVisibility(dual ? View.VISIBLE : View.GONE);
        }
        if (!dual) clearPodcastPreviewPane();
    }

    private void updateAppsPreview(AppLauncher.Entry app) {
        if (!appsDualPaneActive() || app == null) return;
        if (ivPodcastPreviewArt != null) {
            try {
                ivPodcastPreviewArt.setImageDrawable(
                        getPackageManager().getApplicationIcon(app.packageName));
                ivPodcastPreviewArt.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                ivPodcastPreviewArt.setVisibility(View.GONE);
            }
        }
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setText(app.label);
            tvPodcastPreviewShow.setVisibility(View.VISIBLE);
            ThemeManager.applyThemedTextStyle(tvPodcastPreviewShow, ThemeManager.getTextColorPrimary());
        }
        if (tvPodcastPreviewEpisode != null) {
            tvPodcastPreviewEpisode.setText(app.packageName);
            tvPodcastPreviewEpisode.setVisibility(View.VISIBLE);
            styleSecondaryLabel(tvPodcastPreviewEpisode);
        }
        if (tvPodcastPreviewMeta != null) tvPodcastPreviewMeta.setVisibility(View.GONE);
    }

    private View createAppLauncherRow(final AppLauncher.Entry app) {
        final int rowKind = y1RowKindForScreen();
        final View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (!AppLauncher.launch(MainActivity.this, app.packageName)) {
                    Toast.makeText(MainActivity.this, getString(R.string.apps_launch_failed),
                            Toast.LENGTH_SHORT).show();
                }
            }
        };
        final View.OnFocusChangeListener focusListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    showFastScrollLetter(app.label);
                    updateAppsPreview(app);
                }
            }
        };

        if (isFullWidthMenus) {
            float density = getResources().getDisplayMetrics().density;
            int textPadLeft = (int) getResources().getDimension(R.dimen.y1_menu_text_pad_left);
            float menuTextPx = getResources().getDimension(R.dimen.y1_menu_text_size);
            Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(y1RowHeightPx);
            int[] arrowLayout = y1ArrowLayout(arrowBmp);
            int iconSize = (int) (y1RowHeightPx * 0.72f);
            int iconGap = (int) (4 * density);

            final FrameLayout row = new FrameLayout(this);
            row.setFocusable(true);
            row.setSoundEffectsEnabled(false);
            row.setTag(app);

            ImageView icon = new ImageView(this);
            icon.setFocusable(false);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(app.packageName));
            } catch (Exception ignored) {}
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
            iconLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START;
            iconLp.leftMargin = textPadLeft;
            row.addView(icon, iconLp);

            TextView label = new TextView(this);
            label.setFocusable(false);
            label.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            label.setText(app.label);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, menuTextPx);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            label.setIncludeFontPadding(false);
            ThemeManager.applyThemedTextStyle(label, y1RowTextColorNormal(rowKind));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            labelLp.gravity = android.view.Gravity.CENTER_VERTICAL;
            labelLp.leftMargin = textPadLeft + iconSize + iconGap;
            labelLp.rightMargin = arrowLayout[2];
            row.addView(label, labelLp);

            ImageView arrow = new ImageView(this);
            arrow.setFocusable(false);
            arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (arrowBmp != null) arrow.setImageBitmap(arrowBmp);
            arrow.setVisibility(View.GONE);
            FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(arrowLayout[0], arrowLayout[1]);
            arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
            arrowLp.rightMargin = (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
            row.addView(arrow, arrowLp);

            row.setOnClickListener(click);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    applyY1ListRowStyle(row, hasFocus, label, null, arrow, rowKind);
                    focusListener.onFocusChange(v, hasFocus);
                }
            });
            applyY1ListRowStyle(row, false, label, null, arrow, rowKind);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
            lp.setMargins(0, 1, 0, 1);
            row.setLayoutParams(lp);
            return row;
        }

        FrameLayout row = createHomeMenuRowShell();
        row.setTag(app);
        TextView label = (TextView) row.getTag(HOME_MENU_TAG_LABEL);
        ImageView arrow = (ImageView) row.getTag(HOME_MENU_TAG_ARROW);
        label.setText(app.label);
        row.setOnClickListener(click);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyY1ListRowStyle(v, hasFocus, label, null, arrow, rowKind);
                focusListener.onFocusChange(v, hasFocus);
            }
        });
        applyY1ListRowStyle(row, false, label, null, arrow, rowKind);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx(), y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        row.setLayoutParams(lp);
        return row;
    }

    private void clearPodcastPreviewPane() {
        podcastPreviewArtGen++;
        if (ivPodcastPreviewArt != null) {
            ivPodcastPreviewArt.setImageDrawable(null);
            ivPodcastPreviewArt.setVisibility(View.GONE);
        }
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setText("");
            tvPodcastPreviewShow.setVisibility(View.GONE);
        }
        if (tvPodcastPreviewEpisode != null) {
            tvPodcastPreviewEpisode.setText("");
            tvPodcastPreviewEpisode.setVisibility(View.GONE);
        }
        if (tvPodcastPreviewMeta != null) {
            tvPodcastPreviewMeta.setText("");
            tvPodcastPreviewMeta.setVisibility(View.GONE);
        }
    }

    private void updatePodcastPreviewShow(final OpenRssClient.Podcast p) {
        if (!podcastDualPaneActive() || p == null) return;
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setText(p.title);
            tvPodcastPreviewShow.setVisibility(View.VISIBLE);
        }
        if (tvPodcastPreviewEpisode != null) {
            String pub = p.publisher != null && p.publisher.length() > 0 ? p.publisher : "";
            tvPodcastPreviewEpisode.setText(pub);
            tvPodcastPreviewEpisode.setVisibility(pub.length() > 0 ? View.VISIBLE : View.GONE);
        }
        if (tvPodcastPreviewMeta != null) {
            tvPodcastPreviewMeta.setText("");
            tvPodcastPreviewMeta.setVisibility(View.GONE);
        }
        loadPodcastPreviewArt(p.artworkUrl);
    }

    private void updatePodcastPreviewEpisode(final OpenRssClient.Episode ep, boolean saved, boolean resume) {
        if (!podcastDualPaneActive() || ep == null) return;
        String showName = podcastSelected != null ? podcastSelected.title : "";
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setText(ep.title);
            tvPodcastPreviewShow.setVisibility(View.VISIBLE);
        }
        if (tvPodcastPreviewEpisode != null) {
            tvPodcastPreviewEpisode.setText(getString(R.string.podcast_detail_show, showName));
            tvPodcastPreviewEpisode.setVisibility(showName.length() > 0 ? View.VISIBLE : View.GONE);
        }
        if (tvPodcastPreviewMeta != null) {
            String date = OpenRssClient.formatPodcastDate(ep.pubDate);
            if (date.length() == 0) date = getString(R.string.podcast_detail_unknown_date);
            String dur = OpenRssClient.formatDuration(ep.durationSec);
            if (dur.length() == 0) dur = getString(R.string.podcast_detail_unknown_duration);
            StringBuilder meta = new StringBuilder();
            meta.append(getString(R.string.podcast_detail_released, date));
            meta.append("\n").append(getString(R.string.podcast_detail_duration, dur));
            if (saved) meta.append("\n").append(getString(R.string.podcasts_saved_badge));
            if (resume) meta.append(" ").append(getString(R.string.podcasts_resume_badge));
            tvPodcastPreviewMeta.setText(meta.toString());
            tvPodcastPreviewMeta.setVisibility(View.VISIBLE);
        }
        if (podcastSelected != null) loadPodcastPreviewArt(podcastSelected.artworkUrl);
    }

    private void updatePodcastPreviewSavedFile(final File file, final String showFolder) {
        if (!podcastDualPaneActive() || file == null) return;
        String stem = file.getName();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        if (tvPodcastPreviewShow != null) {
            tvPodcastPreviewShow.setText(stem);
            tvPodcastPreviewShow.setVisibility(View.VISIBLE);
        }
        if (tvPodcastPreviewEpisode != null) {
            tvPodcastPreviewEpisode.setText(getString(R.string.podcast_detail_show, showFolder));
            tvPodcastPreviewEpisode.setVisibility(View.VISIBLE);
        }
        String date = OpenRssClient.formatPodcastDate(
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
                        .format(new java.util.Date(file.lastModified())));
        boolean resume = PodcastResumeStore.hasResume(getApplicationContext(),
                PodcastResumeStore.keyForFile(file));
        updatePodcastPreviewSavedMeta(file, showFolder, date, resume, getString(R.string.podcast_detail_unknown_duration));
        probeSavedPodcastDuration(file, showFolder, date, resume);
        clearPodcastPreviewArt();
    }

    private void updatePodcastPreviewSavedMeta(File file, String showFolder, String date,
            boolean resume, String durationLabel) {
        if (tvPodcastPreviewMeta == null) return;
        StringBuilder meta = new StringBuilder();
        meta.append(getString(R.string.podcast_detail_released, date));
        meta.append("\n").append(getString(R.string.podcast_detail_duration, durationLabel));
        if (resume) meta.append("\n").append(getString(R.string.podcasts_resume_badge));
        tvPodcastPreviewMeta.setText(meta.toString());
        tvPodcastPreviewMeta.setVisibility(View.VISIBLE);
    }

    private void clearPodcastPreviewArt() {
        podcastPreviewArtGen++;
        if (ivPodcastPreviewArt != null) {
            ivPodcastPreviewArt.setImageDrawable(null);
            ivPodcastPreviewArt.setVisibility(View.GONE);
        }
    }

    private void loadPodcastPreviewArt(final String url) {
        if (ivPodcastPreviewArt == null) return;
        if (url == null || url.trim().isEmpty()) {
            clearPodcastPreviewArt();
            return;
        }
        final int gen = ++podcastPreviewArtGen;
        final String artUrl = url.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = com.solar.launcher.net.SolarHttp.getBytes(artUrl, "image/*", OpenRssClient.UA);
                    if (raw == null || raw.length == 0) return;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    final android.graphics.Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                    if (bmp == null) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != podcastPreviewArtGen || !podcastDualPaneActive()) return;
                            ivPodcastPreviewArt.setImageBitmap(bmp);
                            ivPodcastPreviewArt.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void probeSavedPodcastDuration(final File file, final String showFolder,
            final String date, final boolean resume) {
        if (file == null) return;
        final String key = file.getAbsolutePath();
        Integer cached = podcastSavedDurationCache.get(key);
        if (cached != null && cached > 0) {
            updatePodcastPreviewSavedMeta(file, showFolder, date, resume,
                    OpenRssClient.formatDuration(cached));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int sec = 0;
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(file.getAbsolutePath());
                    String d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) {
                        long ms = Long.parseLong(d);
                        if (ms > 0) sec = (int) (ms / 1000);
                    }
                } catch (Exception ignored) {
                } finally {
                    try { mmr.release(); } catch (Exception ignored) {}
                }
                if (sec > 0) podcastSavedDurationCache.put(key, sec);
                final int durationSec = sec;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!podcastDualPaneActive()) return;
                        View focus = getCurrentFocus();
                        if (focus == null || !(focus.getTag() instanceof File)) return;
                        if (!file.equals(focus.getTag())) return;
                        String dur = durationSec > 0
                                ? OpenRssClient.formatDuration(durationSec)
                                : getString(R.string.podcast_detail_unknown_duration);
                        updatePodcastPreviewSavedMeta(file, showFolder, date, resume, dur);
                    }
                });
            }
        }).start();
    }

    private String podcastEpisodeInlineSubtitle(OpenRssClient.Episode ep) {
        String date = OpenRssClient.formatPodcastDate(ep.pubDate);
        if (date.length() == 0) date = getString(R.string.podcast_detail_unknown_date);
        String dur = OpenRssClient.formatDuration(ep.durationSec);
        if (dur.length() == 0) dur = getString(R.string.podcast_detail_unknown_duration);
        return date + " · " + dur;
    }

    private View createPodcastListRow(String title, String subtitle, View.OnClickListener click,
            View.OnFocusChangeListener focusListener) {
        final int rowKind = y1RowKindForScreen();
        if (isFullWidthMenus && subtitle != null && subtitle.length() > 0) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setFocusable(true);
            row.setSoundEffectsEnabled(false);
            int hPad = (int) (10 * getResources().getDisplayMetrics().density);
            row.setPadding(hPad, 4, hPad, 4);
            int rowW = listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx();
            row.setBackground(getY1RowBackground(false, rowW, rowKind));
            row.setOnClickListener(click);

            TextView tvTitle = new TextView(this);
            tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tvTitle.setText(title);
            ThemeManager.applyThemedTextStyle(tvTitle, y1RowTextColorNormal(rowKind));
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.y1_menu_text_size));
            enableMarquee(tvTitle);
            row.addView(tvTitle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvSub = new TextView(this);
            tvSub.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tvSub.setText(subtitle);
            ThemeManager.applyThemedTextStyle(tvSub, ThemeManager.getTextColorSecondary());
            tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f);
            enableMarquee(tvSub);
            row.addView(tvSub, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    int w = row.getWidth() > 0 ? row.getWidth()
                            : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
                    row.setBackground(getY1RowBackground(hasFocus, w, rowKind));
                    ThemeManager.applyThemedTextStyle(tvTitle, hasFocus
                            ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                    ThemeManager.applyThemedTextStyle(tvSub, hasFocus
                            ? y1RowTextColorSelected(rowKind) : ThemeManager.getTextColorSecondary());
                    tvTitle.setSelected(hasFocus);
                    tvSub.setSelected(hasFocus);
                    if (hasFocus) showFastScrollLetter(title);
                    if (focusListener != null) focusListener.onFocusChange(v, hasFocus);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 1, 0, 1);
            row.setLayoutParams(lp);
            return row;
        }

        Button b = createListButton(title);
        b.setOnClickListener(click);
        if (focusListener != null) {
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    configureY1ThemedButtonFocus(b, hasFocus, rowKind);
                    if (hasFocus) showFastScrollLetter(title);
                    focusListener.onFocusChange(v, hasFocus);
                }
            });
        }
        return b;
    }

    private void configureY1ThemedButtonFocus(Button btn, boolean hasFocus, int rowKind) {
        int w = btn.getWidth() > 0 ? btn.getWidth()
                : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
        btn.setBackground(getY1RowBackground(hasFocus, w, rowKind));
        ThemeManager.applyThemedTextStyle(btn, hasFocus
                ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
        btn.setSelected(hasFocus);
    }

    private void updateHomeMenuPreviewVisibility() {
        if (ivMenuPreview == null) return;
        boolean hidePreview = isFullWidthMenus
                || (layoutWidgets != null && layoutWidgets.getVisibility() == View.VISIBLE);
        ivMenuPreview.setVisibility(hidePreview ? View.GONE : View.VISIBLE);
        if (tvMenuPreviewTitle != null) {
            tvMenuPreviewTitle.setVisibility(hidePreview ? View.GONE : tvMenuPreviewTitle.getVisibility());
        }
        if (tvMenuPreviewArtist != null) {
            tvMenuPreviewArtist.setVisibility(hidePreview ? View.GONE : tvMenuPreviewArtist.getVisibility());
        }
    }

    private String screenTimeoutIconKey() {
        int ms = TIMEOUT_VALUES[currentTimeoutIndex];
        if (ms <= 10000) return "backlight_10";
        if (ms <= 15000) return "backlight_15";
        if (ms <= 30000) return "backlight_30";
        if (ms <= 45000) return "backlight_45";
        if (ms <= 60000) return "backlight_60";
        if (ms <= 120000) return "backlight_120";
        return "backlight_300";
    }

    private String screenTimeoutStateText() {
        int sec = TIMEOUT_VALUES[currentTimeoutIndex] / 1000;
        if (sec >= 60 && sec % 60 == 0) return getString(R.string.common_timeout_min, sec / 60);
        return getString(R.string.common_timeout_sec, sec);
    }

    private String repeatIconKey() {
        switch (repeatMode) {
            case 1: return "repeatOne";
            case 2: return "repeatAll";
            default: return "repeatOff";
        }
    }

    private String eqIconKey() {
        String n = eqPresetNames.get(currentEqPresetIndex).toLowerCase(Locale.US);
        if (n.contains("classical")) return "equalizer_classical";
        if (n.contains("dance")) return "equalizer_dance";
        if (n.contains("flat")) return "equalizer_flat";
        if (n.contains("folk")) return "equalizer_folk";
        if (n.contains("metal")) return "equalizer_heavymetal";
        if (n.contains("hip hop") || n.contains("hiphop")) return "equalizer_hiphop";
        if (n.contains("jazz")) return "equalizer_jazz";
        if (n.contains("pop")) return "equalizer_pop";
        if (n.contains("rock")) return "equalizer_rock";
        return "equalizer_normal";
    }

    private Bitmap resolveHomeMenuPreviewIconForSettings(String rowKey) {
        if (rowKey != null && rowKey.startsWith("home.shortcut.")) {
            return resolveHomeMenuPreviewIconForId(rowKey.substring("home.shortcut.".length()));
        }
        return null;
    }

    private String resolveSoulseekSolarConfigKey(String rowKey) {
        if (rowKey == null) return null;
        if (RowKeys.SOULSEEK_SEARCH.equals(rowKey)) return "soulseekSearch";
        if (RowKeys.SOULSEEK_ACCOUNT.equals(rowKey)) return "soulseekAccount";
        if (RowKeys.SOULSEEK_REGENERATE.equals(rowKey)) return "soulseekRegenerate";
        return null;
    }

    private boolean isAppearancePreviewRow(String rowKey) {
        return RowKeys.APPEARANCE.equals(rowKey) || RowKeys.HOME_SCREEN.equals(rowKey)
                || RowKeys.NOW_PLAYING.equals(rowKey)
                || RowKeys.STATUS_BAR_LEFT.equals(rowKey) || RowKeys.STATUS_BAR_MATCH_FONT.equals(rowKey)
                || RowKeys.FULL_WIDTH.equals(rowKey)
                || RowKeys.BACKGROUND.equals(rowKey) || RowKeys.THEMES.equals(rowKey)
                || RowKeys.GET_THEMES.equals(rowKey);
    }

    private String resolveSolarSettingAppName(String rowKey) {
        if (rowKey == null) return null;
        if (RowKeys.SOULSEEK.equals(rowKey)) return "Reach";
        if (RowKeys.WEB_SERVER.equals(rowKey)) return "PC Upload";
        if (RowKeys.GET_THEMES.equals(rowKey) || RowKeys.THEMES.equals(rowKey)) return "Themes";
        if (RowKeys.homeShortcut(HomeMenuConfig.ID_APPS).equals(rowKey)) return "Apps";
        return null;
    }

    private String resolveSettingIconKey(String rowKey) {
        if (RowKeys.SHUFFLE.equals(rowKey)) return isShuffleMode ? "shuffleOn" : "shuffleOff";
        if (RowKeys.REPEAT.equals(rowKey)) return repeatIconKey();
        if (RowKeys.EQ.equals(rowKey)) return eqIconKey();
        if (RowKeys.BUTTON_SOUND.equals(rowKey)) return isSoundEffectEnabled ? "keyToneOn" : "keyToneOff";
        if (RowKeys.BUTTON_VIBRATE.equals(rowKey)) return isVibrationEnabled ? "keyVibrationOn" : "keyVibrationOff";
        if (RowKeys.SCREEN_TIMEOUT.equals(rowKey)) return screenTimeoutIconKey();
        if (RowKeys.APP_THEME.equals(rowKey)) return "theme";
        if (RowKeys.BRIGHTNESS.equals(rowKey)) return "brightness";
        if (RowKeys.BACKGROUND.equals(rowKey)) return "wallpaper";
        if (RowKeys.NOW_PLAYING.equals(rowKey)) return "nowPlaying";
        if (RowKeys.DATETIME.equals(rowKey)) return "dateTime";
        return null;
    }

    private String resolveSettingStateText(String rowKey) {
        if (RowKeys.SHUFFLE.equals(rowKey)) return stateOnOff(isShuffleMode);
        if (RowKeys.REPEAT.equals(rowKey)) return getRepeatModeText(repeatMode);
        if (RowKeys.EQ.equals(rowKey)) return eqPresetNames.get(currentEqPresetIndex);
        if (RowKeys.BUTTON_SOUND.equals(rowKey)) return stateOnOff(isSoundEffectEnabled);
        if (RowKeys.BUTTON_VIBRATE.equals(rowKey)) return stateOnOff(isVibrationEnabled);
        if (RowKeys.SCREEN_OFF_CTRL.equals(rowKey)) return stateOnOff(isScreenOffControlEnabled);
        if (RowKeys.APP_THEME.equals(rowKey)) return ThemeManager.getCurrentTheme().name;
        if (RowKeys.STATUS_BAR_LEFT.equals(rowKey)) {
            return statusBarShowsTitle ? getString(R.string.settings_status_title) : getString(R.string.common_clock);
        }
        if (RowKeys.SCREEN_TIMEOUT.equals(rowKey)) return screenTimeoutStateText();
        if (RowKeys.FULL_WIDTH.equals(rowKey)) return stateOnOff(isFullWidthMenus);
        if (RowKeys.AUTO_FETCH.equals(rowKey)) return stateOnOff(isAutoFetchEnabled);
        if (RowKeys.NOW_PLAYING_ALBUM_BLUR.equals(rowKey)) return stateOnOff(playerAlbumBlurEnabled);
        if (RowKeys.WIDGET_CLOCK.equals(rowKey)) return stateOnOff(isWidgetClockOn);
        if (RowKeys.WIDGET_BATTERY.equals(rowKey)) return stateOnOff(isWidgetBatteryOn);
        if (RowKeys.WIDGET_ALBUM.equals(rowKey)) return stateOnOff(isWidgetAlbumOn);
        if (RowKeys.LANGUAGE.equals(rowKey)) {
            return LocaleHelper.displayLabel(this, prefs.getString(LocaleHelper.PREF_LOCALE, LocaleHelper.LOCALE_SYSTEM));
        }
        if (rowKey != null && rowKey.startsWith("home.shortcut.")) {
            String id = rowKey.substring("home.shortcut.".length());
            if (isHomeScreenArrangeScreen() || isHomeMoreArrangeScreen()) {
                List<String> orderIds = isHomeMoreArrangeScreen()
                        ? HomeMenuConfig.loadMoreOrderIds(prefs)
                        : HomeMenuConfig.loadHomeOrderIds(prefs);
                int idx = orderIds.indexOf(id);
                if (idx >= 0) {
                    return getString(R.string.common_position_format, idx + 1, orderIds.size());
                }
                if (homeScreenMoveModeId != null && id.equals(homeScreenMoveModeId)) {
                    return getString(R.string.home_screen_move_mode);
                }
                if (homeMoreMoveModeId != null && id.equals(homeMoreMoveModeId)) {
                    return getString(R.string.home_screen_move_mode);
                }
            }
            HomeMenuConfig.Entry e = HomeMenuConfig.find(id);
            if (e == null) return "";
            if (e.required) return getString(R.string.home_screen_move);
            if (SettingsScreens.HOME.equals(settingsSubScreenKey) && !isHomeScreenArrangeScreen()) {
                return stateOnOff(HomeMenuConfig.isShortcutEnabled(prefs, id));
            }
            if (HomeMenuConfig.ID_GET_THEMES.equals(id) || HomeMenuConfig.ID_THEMES.equals(id)) {
                return getString(R.string.home_screen_preview_themes);
            }
            if (HomeMenuConfig.ID_VIDEOS.equals(id)) return getString(R.string.home_screen_preview_videos);
            if (HomeMenuConfig.ID_PHOTOS.equals(id)) return getString(R.string.home_screen_preview_photos);
            return stateOnOff(HomeMenuConfig.isShortcutEnabled(prefs, id));
        }
        if (RowKeys.HOME_MORE.equals(rowKey)) return stateOnOff(HomeMenuConfig.isMoreEnabled(prefs));
        if (RowKeys.HOME_ARRANGE.equals(rowKey)) return "";
        if (SettingsScreens.isHome(settingsSubScreenKey)) {
            if (isHomeScreenArrangeScreen() && homeScreenMoveModeId != null
                    && RowKeys.homeShortcut(homeScreenMoveModeId).equals(rowKey)) {
                return getString(R.string.home_screen_move_mode);
            }
        }
        if (SettingsScreens.SOULSEEK.equals(settingsSubScreenKey)) {
            if (RowKeys.SOULSEEK_ACCOUNT.equals(rowKey)) {
                return SoulseekAccount.displayLabel(SoulseekAccount.load(prefs));
            }
            if (RowKeys.SOULSEEK_SEARCH.equals(rowKey)) return getString(R.string.soulseek_preview_search);
            if (RowKeys.SOULSEEK_CONNECTION.equals(rowKey)) return soulseekSharingStatusLabel();
            if (RowKeys.SOULSEEK_ABOUT.equals(rowKey)) return getString(R.string.soulseek_preview_about);
            if (RowKeys.SOULSEEK_REGENERATE.equals(rowKey)) return getString(R.string.soulseek_preview_regenerate);
            if (RowKeys.SOULSEEK_HIDE_FLAC.equals(rowKey)) return stateOnOff(soulseekHideFlac);
        }
        if (RowKeys.SOULSEEK_ACCOUNT.equals(rowKey) && SettingsScreens.isSoulseek(settingsSubScreenKey)) {
            return SoulseekAccount.displayLabel(SoulseekAccount.load(prefs));
        }
        if (RowKeys.DT_YEAR.equals(rowKey)) return String.valueOf(dtYear);
        if (RowKeys.DT_MONTH.equals(rowKey)) return String.format(Locale.US, "%02d", dtMonth);
        if (RowKeys.DT_DAY.equals(rowKey)) return String.format(Locale.US, "%02d", dtDay);
        if (RowKeys.DT_HOUR.equals(rowKey)) return String.format(Locale.US, "%02d", dtHour);
        if (RowKeys.DT_MINUTE.equals(rowKey)) return String.format(Locale.US, "%02d", dtMinute);
        if (RowKeys.LANG_SYSTEM.equals(rowKey)) return "";
        if (RowKeys.LANG_EN.equals(rowKey) || RowKeys.LANG_KO.equals(rowKey)) return "";
        if (RowKeys.STATUS_BAR_MATCH_FONT.equals(rowKey)) return stateOnOff(statusBarMatchFont);
        // ponytail: Reach blurb only in Reach settings sub-panes — home preview uses appReach icon
        if (RowKeys.SOULSEEK.equals(rowKey)) return "";
        return "";
    }

    private String homeShortcutConnectivityHint(String id) {
        if (id == null) return null;
        if (HomeMenuConfig.ID_PODCASTS.equals(id)) {
            if (!ConnectivityHelper.isOnline(this)
                    && !com.solar.launcher.podcast.PodcastLibrary.hasSavedContent()) {
                return getString(R.string.home_screen_hint_internet);
            }
            return null;
        }
        if (ConnectivityHelper.itemNeedsInternetForDiscovery(id) && !ConnectivityHelper.isOnline(this)) {
            return getString(R.string.home_screen_hint_internet);
        }
        if (ConnectivityHelper.itemNeedsLocalNetwork(id) && !ConnectivityHelper.hasLocalNetwork(this)) {
            return getString(R.string.home_screen_hint_network);
        }
        return null;
    }

    private void applyHomeShortcutConnectivityHint(View row, String rowKey, boolean hasFocus) {
        if (row == null || rowKey == null || !rowKey.startsWith("home.shortcut.")) return;
        String id = rowKey.substring("home.shortcut.".length());
        String hint = hasFocus ? homeShortcutConnectivityHint(id) : null;
        TextView tvHint = (TextView) row.findViewWithTag("connectivity_hint");
        if (tvHint == null) return;
        if (!isFullWidthMenus) {
            tvHint.setVisibility(View.GONE);
            return;
        }
        if (hint != null && !hint.isEmpty()) {
            tvHint.setText(hint);
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setSelected(hasFocus);
            if (hasFocus) enableMarquee(tvHint);
        } else {
            tvHint.setVisibility(View.GONE);
        }
    }

    private String stateOnOff(boolean on) {
        return getString(on ? R.string.common_on : R.string.common_off);
    }

    private LinearLayout createSettingRow(String rowKey, int labelResId, CharSequence rightText, String iconKey) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setTag(rowKey != null ? rowKey : iconKey);
        layout.setSoundEffectsEnabled(false);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setFocusable(true);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        layout.setPadding(hPad, 0, hPad, 0);
        int rowW = y1ActiveRowWidthPx();
        layout.setBackground(getY1RowBackground(false, rowW, Y1_ROW_MENU));

        TextView tvLeft = new TextView(this);
        tvLeft.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvLeft.setText(getString(labelResId));
        ThemeManager.applyThemedTextStyle(tvLeft, ThemeManager.getSettingMenuTextColorNormal());
        tvLeft.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        enableMarquee(tvLeft);

        TextView tvHint = new TextView(this);
        tvHint.setTag("connectivity_hint");
        tvHint.setFocusable(false);
        tvHint.setVisibility(View.GONE);
        tvHint.setSingleLine(true);
        tvHint.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tvHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size) * 0.78f);
        ThemeManager.applyThemedTextStyle(tvHint, ThemeManager.getHintTextColor());

        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        labelCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        labelCol.addView(tvLeft);
        labelCol.addView(tvHint);
        layout.addView(labelCol);

        TextView tvRight = new TextView(this);
        tvRight.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        final boolean showArrow = "〉 ".contentEquals(rightText) || ">".equals(String.valueOf(rightText).trim());
        if (!showArrow) {
            tvRight.setText(rightText);
            tvRight.setGravity(android.view.Gravity.RIGHT);
            String rt = String.valueOf(rightText);
            if (isActiveStateText(rt) || getString(R.string.common_off).equals(rt))
                ThemeManager.applyThemedTextStyle(tvRight, ThemeManager.getSettingMenuTextColorNormal());
            else
                ThemeManager.applyThemedTextStyle(tvRight, ThemeManager.getTextColorSecondary());
            tvRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.y1_menu_text_size));
            tvRight.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            layout.addView(tvRight);
        }

        final ImageView rowArrow = new ImageView(this);
        if (showArrow) {
            Bitmap arrowBmp = ThemeManager.getScaledItemRightArrow(y1RowHeightPx);
            int[] arrowLayout = y1ArrowLayout(arrowBmp);
            if (arrowBmp != null) rowArrow.setImageBitmap(arrowBmp);
            rowArrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rowArrow.setVisibility(View.GONE);
            int arrowMarginEnd = (int) getResources().getDimension(R.dimen.y1_arrow_margin_end);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(arrowLayout[0], arrowLayout[1]);
            arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL;
            arrowLp.rightMargin = arrowMarginEnd;
            layout.addView(rowArrow, arrowLp);
        } else {
            rowArrow.setVisibility(View.GONE);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        layout.setLayoutParams(lp);

        layout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                int rowW = y1ActiveRowWidthPx();
                layout.setBackground(getY1RowBackground(hasFocus, rowW, Y1_ROW_MENU));
                applyY1ListRowStyle(layout, hasFocus, tvLeft, showArrow ? null : tvRight,
                        showArrow ? rowArrow : null, Y1_ROW_MENU);
                if (hasFocus && currentScreenState == STATE_SETTINGS && rowKey != null) {
                    updateSettingsPreview(rowKey);
                    applyHomeShortcutConnectivityHint(layout, rowKey, true);
                } else if (!hasFocus) {
                    applyHomeShortcutConnectivityHint(layout, rowKey, false);
                }
            }
        });

        return layout;
    }

    private void updateSettingsPreview(String rowKey) {
        if (tvSettingsPreviewTitle == null) return;
        if (isThemeGalleryActive() || isThemeVariantPickerActive()) return;
        if (isFullWidthMenus) {
            if (rowKey != null && rowKey.startsWith("home.shortcut.")
                    && SettingsScreens.isHome(settingsSubScreenKey)) {
                View focused = getCurrentFocus();
                if (focused != null) applyHomeShortcutConnectivityHint(focused, rowKey, true);
            }
            return;
        }
        tvSettingsPreviewTitle.setText(rowLabel(rowKey));
        tvSettingsPreviewTitle.setSelected(true);
        tvSettingsPreviewTitle.setTextColor(ThemeManager.getSettingMenuTextColorSelected());

        boolean isStorage = RowKeys.STORAGE.equals(rowKey);
        if (storagePieView != null) storagePieView.setVisibility(isStorage ? View.VISIBLE : View.GONE);
        if (ivSettingsPreviewIcon != null) ivSettingsPreviewIcon.setVisibility(isStorage ? View.GONE : View.VISIBLE);

        if (isStorage) {
            try {
                android.os.StatFs stat = new android.os.StatFs("/storage/sdcard0");
                long total = (long) stat.getBlockCount() * stat.getBlockSize();
                long avail = (long) stat.getAvailableBlocks() * stat.getBlockSize();
                long used = total - avail;
                if (storagePieView != null && total > 0) {
                    storagePieView.setUsedFraction(used / (float) total);
                }
                if (tvSettingsPreviewState != null) {
                    tvSettingsPreviewState.setText(formatStorageLines(total, used, avail));
                }
            } catch (Exception e) {
                if (tvSettingsPreviewState != null) tvSettingsPreviewState.setText("");
            }
            return;
        }

        String solarKey = resolveSoulseekSolarConfigKey(rowKey);
        Bitmap icon = null;
        if (isAppearancePreviewRow(rowKey)) {
            icon = ThemeManager.getSettingIcon("theme");
        }
        if (icon == null) {
            icon = solarKey != null ? ThemeManager.getSolarConfigIcon(solarKey) : null;
        }
        if (icon == null) {
            String solarApp = resolveSolarSettingAppName(rowKey);
            icon = solarApp != null ? ThemeManager.getSolarAppIcon(solarApp) : null;
        }
        if (icon == null) {
            String iconKey = resolveSettingIconKey(rowKey);
            icon = iconKey != null ? ThemeManager.getSettingIcon(iconKey) : null;
        }
        if (icon == null && SettingsScreens.isHome(settingsSubScreenKey)) {
            icon = resolveHomeMenuPreviewIconForSettings(rowKey);
        }
        if (icon == null && RowKeys.APP_THEME.equals(rowKey)) {
            Bitmap cover = ThemeManager.getScaledThemeCover(ThemeManager.getCurrentTheme(), y1ThemeCoverHeightPx);
            if (cover != null) icon = cover;
        }
        if (ivSettingsPreviewIcon != null) {
            if (icon != null) {
                ivSettingsPreviewIcon.setImageBitmap(icon);
                ivSettingsPreviewIcon.setVisibility(View.VISIBLE);
            } else {
                ivSettingsPreviewIcon.setVisibility(View.GONE);
            }
        }
        String stateText = resolveSettingStateText(rowKey);
        if (rowKey != null && rowKey.startsWith("home.shortcut.")
                && SettingsScreens.isHome(settingsSubScreenKey)) {
            String id = rowKey.substring("home.shortcut.".length());
            String hint = homeShortcutConnectivityHint(id);
            if (hint != null) {
                stateText = hint;
            } else if (SettingsScreens.HOME.equals(settingsSubScreenKey) && !isHomeScreenArrangeScreen()) {
                if (HomeMenuConfig.ID_THEMES.equals(id) || HomeMenuConfig.ID_GET_THEMES.equals(id)) {
                    stateText = getString(R.string.home_screen_preview_themes);
                } else if (HomeMenuConfig.ID_VIDEOS.equals(id)) {
                    stateText = getString(R.string.home_screen_preview_videos);
                } else if (HomeMenuConfig.ID_PHOTOS.equals(id)) {
                    stateText = getString(R.string.home_screen_preview_photos);
                }
            }
        }
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setText(stateText != null ? stateText : "");
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewState, ThemeManager.getTextColorPrimary());
            tvSettingsPreviewState.setVisibility(stateText != null && !stateText.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
    }

    private String formatStorageLines(long total, long used, long avail) {
        return getString(R.string.common_storage_total, formatBytes(total), formatBytes(used), formatBytes(avail));
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    /** Center/OK hold ~0.3s — sleep while pressed or on release after threshold. */
    private boolean trackCenterKeyDown(KeyEvent event, boolean fromContextMenu) {
        if (fromContextMenu) {
            suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
        }
        if (event.getRepeatCount() == 0) {
            centerLongPressHandled = false;
            centerKeyDownTime = System.currentTimeMillis();
            clockHandler.removeCallbacks(centerSleepRunnable);
            clockHandler.removeCallbacks(keyboardDelRepeatRunnable);
            if (isKeyboardDelSelected()) {
                clockHandler.postDelayed(keyboardDelRepeatRunnable, 80);
            } else {
                clockHandler.postDelayed(centerSleepRunnable, CENTER_SLEEP_HOLD_MS);
            }
        }
        return true;
    }

    private boolean isKeyboardDelSelected() {
        return currentScreenState == STATE_WIFI_KEYBOARD
                && keyboardIndex >= 0 && keyboardIndex < KEYBOARD_CHARS.length
                && "[DEL]".equals(KEYBOARD_CHARS[keyboardIndex]);
    }

    private boolean handleCenterKeyUp(KeyEvent event, boolean fromContextMenu) {
        long heldMs = centerKeyDownTime > 0 ? System.currentTimeMillis() - centerKeyDownTime : 0;
        clockHandler.removeCallbacks(centerSleepRunnable);
        clockHandler.removeCallbacks(keyboardDelRepeatRunnable);
        centerKeyDownTime = 0;
        if (centerLongPressHandled) {
            centerLongPressHandled = false;
            return true;
        }
        if (heldMs >= CENTER_SLEEP_HOLD_MS) {
            performScreenSleep(false);
            return true;
        }
        if (fromContextMenu) {
            suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
            themedContextMenu.activateFocused();
            clickFeedback();
            return true;
        }
        try {
            handleCenterShortClick();
        } catch (Exception ignored) {}
        return true;
    }

    private void dismissThemedContextMenu() {
        contextMenuTierStack.clear();
        contextMenuInVolumeSlider = false;
        if (themedContextMenu != null) themedContextMenu.dismiss();
    }

    private ThemedContextMenu.QuickItem[] buildContextQuickBar() {
        boolean hasQueue = playback.hasAnyQueue();
        return new ThemedContextMenu.QuickItem[] {
            new ThemedContextMenu.QuickItem("keyLockOn", 0, getString(R.string.context_action_lock_screen), true),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_wifi, getString(R.string.wifi_power), true),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_bluetooth, getString(R.string.home_menu_bluetooth), true),
            new ThemedContextMenu.QuickItem(null, R.drawable.ic_headphone, getString(R.string.context_quick_volume), true),
            new ThemedContextMenu.QuickItem("shutdown", 0, getString(R.string.context_quick_power), true),
            new ThemedContextMenu.QuickItem("nowPlaying", 0, getString(R.string.context_quick_queue),
                    hasQueue)
        };
    }

    private void handleContextQuickBar(int index) {
        suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
        switch (index) {
            case 0:
                dismissThemedContextMenu();
                performScreenSleep(true);
                break;
            case 1:
                if (isWifiPowerOn()) pushContextWifiTier();
                else toggleWifiFromContextMenu();
                break;
            case 2:
                pushContextBluetoothTier();
                break;
            case 3:
                showContextVolumeSlider();
                break;
            case 4:
                dismissThemedContextMenu();
                showShutdownConfirm();
                break;
            case 5:
                dismissThemedContextMenu();
                if (playback.hasAnyQueue()) {
                    if (hasActiveMediaPlayback()) changeScreen(STATE_PLAYER);
                    else openMusicQueueEditor();
                }
                break;
            default:
                break;
        }
    }

    private void showShutdownConfirm() {
        showThemedConfirm(getString(R.string.context_shutdown_title),
                getString(R.string.context_shutdown_message),
                getString(R.string.context_shutdown_confirm),
                getString(R.string.common_cancel),
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Runtime.getRuntime().exec(new String[] {"su", "-c", "reboot -p"});
                        } catch (Exception ignored) {}
                    }
                });
    }

    private void showContextVolumeSlider() {
        if (themedContextMenu == null || audioManager == null) return;
        contextMenuInVolumeSlider = true;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        themedContextMenu.showSlider(getString(R.string.context_quick_volume), max, cur);
    }

    private void pushContextWifiTier() {
        contextMenuTierStack.push("wifi");
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<Runnable>();
        labels.add(getString(R.string.context_action_refresh_scan));
        actions.add(new Runnable() {
            @Override public void run() {
                startWifiScan();
                pushContextWifiTier();
            }
        });
        final String ssid = wifiFocusedSsid();
        if (ssid != null && isWifiNetworkSaved(ssid)) {
            labels.add(getString(R.string.context_action_forget_wifi));
            actions.add(new Runnable() {
                @Override public void run() {
                    forgetWifiNetwork(ssid);
                    pushContextWifiTier();
                }
            });
        }
        labels.add(getString(R.string.wifi_off_confirm));
        actions.add(new Runnable() {
            @Override public void run() { toggleWifiFromContextMenu(); }
        });
        showContextMenuTier(getString(R.string.wifi_power), labels, actions);
    }

    private void pushContextBluetoothTier() {
        contextMenuTierStack.push("bt");
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<Runnable>();
        labels.add(getString(R.string.context_action_refresh_scan));
        actions.add(new Runnable() {
            @Override public void run() {
                startBluetoothScan();
                pushContextBluetoothTier();
            }
        });
        final BluetoothDevice bt = bluetoothFocusedDevice();
        if (bt != null && bt.getBondState() == BluetoothDevice.BOND_BONDED) {
            labels.add(getString(R.string.context_action_forget_bluetooth));
            actions.add(new Runnable() {
                @Override public void run() {
                    forgetBluetoothDevice(bt);
                    pushContextBluetoothTier();
                }
            });
        }
        showContextMenuTier(getString(R.string.home_menu_bluetooth), labels, actions);
    }

    private void showContextMenuTier(String title, java.util.List<String> labels,
            final java.util.List<Runnable> actions) {
        String[] arr = labels.toArray(new String[labels.size()]);
        boolean[] headers = new boolean[arr.length];
        themedContextMenu.replaceListContent(title, arr, null, null, headers,
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
                        if (index >= 0 && index < actions.size()) {
                            final Runnable a = actions.get(index);
                            dismissThemedContextMenu();
                            if (a != null) a.run();
                        }
                    }
                });
    }

    private boolean popContextMenuTier() {
        if (contextMenuInVolumeSlider) {
            contextMenuInVolumeSlider = false;
            if (themedContextMenu != null) themedContextMenu.hideSlider();
            return true;
        }
        if (!contextMenuTierStack.isEmpty()) {
            contextMenuTierStack.clear();
            showThemedContextMenu();
            return true;
        }
        return false;
    }

    private void persistPlaybackQueue() {
        PlayQueueStore.save(getApplicationContext(), playback.unifiedQueue());
    }

    private void restorePlaybackQueue() {
        PlayQueue q = new PlayQueue();
        if (!PlayQueueStore.restore(getApplicationContext(), q) || q.isEmpty()) return;
        playback.unifiedQueue().setAll(q.items(), q.index());
        PlayQueue.QueueItem c = q.current();
        if (c != null && c.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
            playback.activatePodcast(q.podcastEpisodes(), q.index(), c.podcastShowTitle, c.podcastFromSaved);
        } else if (c != null && c.file != null) {
            playback.activateMusic(q.musicFiles(), playback.musicIndex(), false);
        }
    }

    private void showThemedContextMenu() {
        if (themedContextMenu == null) return;
        if (layoutLoadingOverlay != null && layoutLoadingOverlay.getVisibility() == View.VISIBLE) return;
        populateContextMenu();
        if (contextMenuActions.isEmpty()) return;
        String[] labels = contextMenuLabels.toArray(new String[contextMenuLabels.size()]);
        String[] iconKeys = contextMenuIconKeys.toArray(new String[contextMenuIconKeys.size()]);
        String[] stateTexts = contextMenuStateTexts.toArray(new String[contextMenuStateTexts.size()]);
        boolean[] headers = new boolean[contextMenuHeaders.size()];
        for (int i = 0; i < headers.length; i++) headers[i] = contextMenuHeaders.get(i);
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        boolean menuRows = currentScreenState == STATE_MENU || currentScreenState == STATE_SETTINGS;
        int margin = (int) (10 * getResources().getDisplayMetrics().density);
        int panelW = screenWidthPx > margin * 2 ? screenWidthPx - margin * 2 : y1ActiveRowWidthPx();
        themedContextMenu.show(root, getContextMenuTitle(), null, labels, iconKeys, stateTexts, headers,
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(final int index) {
                        suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
                        dismissThemedContextMenu();
                        final Runnable action = (index >= 0 && index < contextMenuActions.size())
                                ? contextMenuActions.get(index) : null;
                        if (action == null) return;
                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
                                if (action != null) action.run();
                            }
                        });
                    }
                }, y1RowHeightPx, panelW, menuRows, false,
                buildContextQuickBar(), new ThemedContextMenu.QuickBarListener() {
                    @Override
                    public void onQuickSelected(int index) {
                        handleContextQuickBar(index);
                    }
                });
        clickFeedback();
    }

    private String getContextMenuTitle() {
        if (currentScreenState == STATE_PODCASTS && podcastUiMode == PODCAST_UI_EPISODES) {
            return podcastSelected != null ? podcastSelected.title : getString(R.string.context_menu_title);
        }
        if (currentScreenState == STATE_PODCASTS && podcastUiMode == PODCAST_UI_SAVED
                && podcastSavedShowFolder != null && !podcastSavedShowFolder.isEmpty()) {
            return podcastSavedShowFolder;
        }
        if (currentScreenState == STATE_PLAYER && playback.isPodcastActive()) {
            return getString(R.string.context_menu_title);
        }
        if (currentScreenState == STATE_SOULSEEK && soulseekUiMode == SOULSEEK_UI_RESULTS) {
            return getString(R.string.context_menu_title);
        }
        if (currentScreenState == STATE_SOULSEEK && soulseekUiMode == SOULSEEK_UI_SEARCH) {
            String recent = soulseekFocusedRecentQuery();
            if (recent != null) return recent;
        }
        return getString(R.string.context_menu_title);
    }

    private void populateContextMenu() {
        contextMenuLabels.clear();
        contextMenuIconKeys.clear();
        contextMenuStateTexts.clear();
        contextMenuHeaders.clear();
        contextMenuActions.clear();
        if (currentScreenState == STATE_MENU && focusedHomeMenuIndex >= 0
                && focusedHomeMenuIndex < homeMenuEntries.size()) {
            final String homeId = homeMenuEntries.get(focusedHomeMenuIndex).id;
            if (HomeMenuConfig.ID_NOW_PLAYING.equals(homeId) && playback.hasAnyQueue()) {
                addContextAction(getString(R.string.context_action_open_player), new Runnable() {
                    @Override
                    public void run() {
                        changeScreen(STATE_PLAYER);
                    }
                });
            } else if (HomeMenuConfig.ID_MUSIC.equals(homeId)) {
                addContextAction(getString(R.string.context_action_open_music), new Runnable() {
                    @Override
                    public void run() {
                        currentBrowserMode = BROWSER_ROOT;
                        changeScreen(STATE_BROWSER);
                    }
                });
            } else if (HomeMenuConfig.ID_PODCASTS.equals(homeId)) {
                addContextAction(getString(R.string.context_action_open_podcasts), new Runnable() {
                    @Override
                    public void run() {
                        changeScreen(STATE_PODCASTS);
                    }
                });
            } else if (HomeMenuConfig.ID_SOULSEEK.equals(homeId)) {
                addContextAction(getString(R.string.context_action_open_reach), new Runnable() {
                    @Override
                    public void run() {
                        openSoulseekScreen();
                    }
                });
            } else if (HomeMenuConfig.ID_APPS.equals(homeId)) {
                addContextAction(getString(R.string.context_action_open_apps), new Runnable() {
                    @Override
                    public void run() {
                        changeScreen(STATE_APPS);
                    }
                });
            }
        }
        if (currentScreenState == STATE_PLAYER && playback.isPodcastActive()) {
            addContextAction(getString(R.string.context_action_back_episodes), new Runnable() {
                @Override
                public void run() {
                    navigateToPodcastUi(PODCAST_UI_EPISODES);
                }
            });
            if (playback.podcastIndex() >= 0 && playback.podcastIndex() < playback.podcastQueue().size()) {
                final OpenRssClient.Episode ep = playback.podcastQueue().get(playback.podcastIndex());
                addContextAction(getString(R.string.context_action_save_episode), new Runnable() {
                    @Override
                    public void run() {
                        savePodcastEpisodeToLibrary(ep);
                    }
                });
                if (PodcastResumeStore.hasResume(getApplicationContext(), podcastResumeKey)) {
                    addContextAction(getString(R.string.context_action_start_over), new Runnable() {
                        @Override
                        public void run() {
                            restartCurrentPodcast();
                        }
                    });
                    addContextAction(getString(R.string.context_action_mark_played), new Runnable() {
                        @Override
                        public void run() {
                            markCurrentPodcastPlayed();
                        }
                    });
                }
            }
            addContextAction(getString(R.string.context_action_play_pause), new Runnable() {
                @Override
                public void run() {
                    playOrPauseMusic();
                }
            });
        }
        if (currentScreenState == STATE_PLAYER && playback.isMusicActive()) {
            addContextAction(getString(R.string.context_action_back_library), null, null, new Runnable() {
                @Override
                public void run() {
                    changeScreen(STATE_BROWSER);
                }
            });
            if (!playback.musicPlaylist().isEmpty()) {
                final File currentTrack = playback.musicPlaylist().get(playback.musicIndex());
                if (ReachCache.isTempFile(getCacheDir(), currentTrack)) {
                    addContextAction(getString(R.string.soulseek_add_to_library), new Runnable() {
                        @Override
                        public void run() {
                            saveReachTrackToLibrary(currentTrack);
                        }
                    });
                }
            }
            addContextAction(getString(R.string.settings_shuffle_mode),
                    isShuffleMode ? "shuffleOn" : "shuffleOff", stateOnOff(isShuffleMode),
                    new Runnable() {
                @Override
                public void run() {
                    isShuffleMode = !isShuffleMode;
                    try {
                        prefs.edit().putBoolean("shuffle", isShuffleMode).commit();
                    } catch (Exception ignored) {}
                    playback.reshuffleMusic(isShuffleMode);
                    updatePlayerStatusIndicators();
                }
            });
            addContextAction(getString(R.string.settings_repeat_mode), repeatIconKey(),
                    getRepeatModeText(repeatMode), new Runnable() {
                @Override
                public void run() {
                    repeatMode = (repeatMode + 1) % 3;
                    try {
                        prefs.edit().putInt("repeat_mode", repeatMode).commit();
                    } catch (Exception ignored) {}
                    updatePlayerStatusIndicators();
                }
            });
            addContextAction(getString(R.string.context_action_play_pause), null, null, new Runnable() {
                @Override
                public void run() {
                    playOrPauseMusic();
                }
            });
        }
        if (currentScreenState == STATE_PLAYER && playback.isMusicActive() && !playback.musicPlaylist().isEmpty()) {
            addContextAction(getString(R.string.library_edit_queue), new Runnable() {
                @Override
                public void run() {
                    openMusicQueueEditor();
                }
            });
            addContextAction(getString(R.string.library_save_queue_m3u), new Runnable() {
                @Override
                public void run() {
                    saveMusicQueueAsM3u();
                }
            });
        }
        if (currentScreenState == STATE_PLAYER && hasActiveMediaPlayback()) {
            addContextAction(getString(R.string.context_action_show_visualizer), null,
                    stateOnOff(isVisualizerShowing), new Runnable() {
                @Override
                public void run() {
                    toggleVisualizer();
                }
            });
        }
        if (currentScreenState == STATE_SETTINGS && SettingsScreens.MUSIC_QUEUE.equals(settingsSubScreenKey)) {
            final int idx = musicQueueFocusedIndex();
            if (idx >= 0) {
                final File queueTrack = playback.musicPlaylist().get(idx);
                if (ReachCache.isTempFile(getCacheDir(), queueTrack)) {
                    addContextAction(getString(R.string.soulseek_add_to_library), new Runnable() {
                        @Override
                        public void run() {
                            saveReachTrackToLibrary(queueTrack);
                            refreshMusicQueueList();
                        }
                    });
                }
                addContextAction(getString(R.string.library_queue_remove), new Runnable() {
                    @Override
                    public void run() {
                        if (isMusicQueueNowPlayingSlot(idx)) return;
                        playback.removeMusicTrackAt(idx);
                        purgeStreamTempFiles();
                        updateMusicTrackCountUi();
                        Toast.makeText(MainActivity.this, getString(R.string.library_queue_removed),
                                Toast.LENGTH_SHORT).show();
                        if (playback.musicPlaylist().isEmpty()) {
                            setMusicQueueListVisible(false);
                            changeScreen(musicQueueReturnScreen);
                        } else {
                            refreshMusicQueueList();
                        }
                    }
                });
            }
        }
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_PLAYLISTS) {
            addContextAction(getString(R.string.library_save_queue_m3u), new Runnable() {
                @Override
                public void run() {
                    saveMusicQueueAsM3u();
                }
            });
        }
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_ROOT && !isPickingBackground) {
            addContextAction(getString(R.string.context_action_refresh_library), new Runnable() {
                @Override
                public void run() {
                    scanMediaLibraryAsync();
                }
            });
        }
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_FOLDER && !isPickingBackground) {
            final File audio = browserFocusedAudioFile();
            if (audio != null) {
                addContextAction(getString(R.string.context_action_play_now), new Runnable() {
                    @Override
                    public void run() {
                        setupFolderPlaylist(audio);
                    }
                });
                addContextAction(getString(R.string.context_action_add_to_queue), new Runnable() {
                    @Override
                    public void run() {
                        appendTrackToMusicQueue(audio);
                    }
                });
            }
            addContextAction(getString(R.string.context_action_play_folder), new Runnable() {
                @Override
                public void run() {
                    playCurrentFolderAll();
                }
            });
        }
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
            final File audio = virtualFocusedAudioFile();
            if (audio != null) {
                addContextAction(getString(R.string.context_action_play_now), new Runnable() {
                    @Override
                    public void run() {
                        playTrackList(virtualSongList, virtualSongList.indexOf(audio),
                                "PLAYLIST".equals(virtualQueryType) ? virtualQueryValue : null);
                    }
                });
                addContextAction(getString(R.string.context_action_add_to_queue), new Runnable() {
                    @Override
                    public void run() {
                        appendTrackToMusicQueue(audio);
                    }
                });
            }
            addContextAction(getString(R.string.library_sort_label, librarySortLabel()), new Runnable() {
                @Override
                public void run() {
                    cycleLibrarySort();
                }
            });
        }
        if (currentScreenState == STATE_BROWSER && (currentBrowserMode == BROWSER_ARTISTS
                || currentBrowserMode == BROWSER_ALBUMS || currentBrowserMode == BROWSER_GENRES
                || currentBrowserMode == BROWSER_ARTIST_ALBUMS)) {
            addContextAction(getString(R.string.library_sort_label, librarySortLabel()), new Runnable() {
                @Override
                public void run() {
                    cycleLibrarySort();
                    if (currentBrowserMode == BROWSER_ARTIST_ALBUMS) buildArtistAlbums();
                    else if (currentBrowserMode == BROWSER_ARTISTS) buildVirtualCategories("ARTIST");
                    else if (currentBrowserMode == BROWSER_GENRES) buildVirtualCategories("GENRE");
                    else buildVirtualCategories("ALBUM");
                }
            });
        }
        if (currentScreenState == STATE_PODCASTS && podcastUiMode == PODCAST_UI_SEARCH) {
            addContextAction(getString(R.string.context_action_open_saved), new Runnable() {
                @Override
                public void run() {
                    buildPodcastSavedShowsUI();
                }
            });
            addContextAction(getString(R.string.podcast_storefront_label, getPodcastStorefrontLabel()),
                    new Runnable() {
                @Override
                public void run() {
                    buildPodcastStorefrontPickerUI();
                }
            });
        }
        if (currentScreenState == STATE_PODCASTS && podcastUiMode == PODCAST_UI_EPISODES) {
            final int idx = podcastEpisodeFocusIndex();
            if (idx >= 0 && idx < podcastEpisodes.size()) {
                final OpenRssClient.Episode ep = podcastEpisodes.get(idx);
                addContextAction(getString(R.string.context_action_play_episode), new Runnable() {
                    @Override
                    public void run() {
                        startPodcastPlayback(podcastEpisodes, idx);
                    }
                });
                addContextAction(getString(R.string.context_action_save_episode), new Runnable() {
                    @Override
                    public void run() {
                        savePodcastEpisodeToLibrary(ep);
                    }
                });
                if (podcastSelected != null
                        && PodcastLibrary.findSaved(podcastSelected.title, ep.title, ep.audioUrl) != null) {
                    addContextAction(getString(R.string.context_action_play_saved_copy), new Runnable() {
                        @Override
                        public void run() {
                            startPodcastPlayback(podcastEpisodes, idx, false);
                        }
                    });
                }
                String rk = PodcastResumeStore.keyForEpisode(
                        podcastSelected != null ? podcastSelected.title : "", ep.title, ep.audioUrl,
                        PodcastLibrary.findSaved(
                                podcastSelected != null ? podcastSelected.title : "", ep.title, ep.audioUrl));
                if (PodcastResumeStore.hasResume(getApplicationContext(), rk)) {
                    addContextAction(getString(R.string.context_action_start_over), new Runnable() {
                        @Override
                        public void run() {
                            PodcastResumeStore.clear(getApplicationContext(), rk);
                            Toast.makeText(MainActivity.this, getString(R.string.context_action_mark_played),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }
        if (currentScreenState == STATE_PODCASTS && podcastUiMode == PODCAST_UI_SAVED
                && podcastSavedShowFolder != null && !podcastSavedShowFolder.isEmpty()) {
            final File savedFile = podcastSavedFileFocus();
            if (savedFile != null) {
                final int idx = PodcastLibrary.listSavedEpisodes(podcastSavedShowFolder).indexOf(savedFile);
                addContextAction(getString(R.string.context_action_play_episode), new Runnable() {
                    @Override
                    public void run() {
                        if (idx >= 0) startSavedPodcastPlayback(podcastSavedShowFolder, idx);
                    }
                });
                addContextAction(getString(R.string.context_action_delete_saved), new Runnable() {
                    @Override
                    public void run() {
                        deleteSavedPodcastFile(savedFile);
                    }
                });
                if (PodcastResumeStore.hasResume(getApplicationContext(),
                        PodcastResumeStore.keyForFile(savedFile))) {
                    addContextAction(getString(R.string.context_action_start_over), new Runnable() {
                        @Override
                        public void run() {
                            PodcastResumeStore.clear(getApplicationContext(),
                                    PodcastResumeStore.keyForFile(savedFile));
                        }
                    });
                }
            }
        }
        if (currentScreenState == STATE_SOULSEEK && soulseekUiMode == SOULSEEK_UI_SEARCH) {
            final String recent = soulseekFocusedRecentQuery();
            if (recent != null) {
                addContextAction(getString(R.string.soulseek_new_search_with, recent), new Runnable() {
                    @Override
                    public void run() {
                        openSoulseekSearchKeyboard(recent);
                    }
                });
            }
        }
        if (currentScreenState == STATE_SOULSEEK && soulseekUiMode == SOULSEEK_UI_RESULTS) {
            final SoulseekClient.Result r = soulseekFocusedResult();
            if (r != null) {
                addContextAction(getString(R.string.soulseek_play), new Runnable() {
                    @Override
                    public void run() {
                        startSoulseekTransfer(r, SOULSEEK_ACTION_PLAY);
                    }
                });
                addContextAction(getString(R.string.soulseek_save), new Runnable() {
                    @Override
                    public void run() {
                        startSoulseekTransfer(r, SOULSEEK_ACTION_SAVE);
                    }
                });
                addContextAction(getString(R.string.soulseek_add_to_queue), new Runnable() {
                    @Override
                    public void run() {
                        startSoulseekTransfer(r, SOULSEEK_ACTION_QUEUE);
                    }
                });
                addContextSectionHeader(getString(R.string.soulseek_find_more));
                List<String> suggestions = SoulseekSearchSuggestions.reSearchQueries(r);
                int shown = 0;
                for (final String q : suggestions) {
                    if (shown >= 4) break;
                    addContextAction(q, new Runnable() {
                        @Override
                        public void run() {
                            if (requireInternet(R.string.soulseek_wifi_required)) startSoulseekReSearch(q);
                        }
                    });
                    shown++;
                }
                addContextAction(getString(R.string.soulseek_find_other_copies), new Runnable() {
                    @Override
                    public void run() {
                        String q = SoulseekSearchSuggestions.findOtherCopies(r);
                        if (q.length() > 0 && requireInternet(R.string.soulseek_wifi_required)) {
                            fetchSoulseekResults(q);
                        }
                    }
                });
            }
        }
        if (currentScreenState == STATE_SOULSEEK && soulseekUiMode == SOULSEEK_UI_DOWNLOAD) {
            addContextAction(getString(R.string.context_action_cancel_download), new Runnable() {
                @Override
                public void run() {
                    cancelSoulseekDownload();
                }
            });
        }
        if (currentScreenState == STATE_BLUETOOTH) {
            addContextAction(getString(R.string.context_action_refresh_scan), new Runnable() {
                @Override
                public void run() {
                    startBluetoothScan();
                }
            });
            final BluetoothDevice btDevice = bluetoothFocusedDevice();
            if (btDevice != null && btDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                addContextAction(getString(R.string.context_action_forget_bluetooth), new Runnable() {
                    @Override
                    public void run() {
                        forgetBluetoothDevice(btDevice);
                    }
                });
            }
        }
        if (currentScreenState == STATE_WIFI) {
            addContextAction(getString(R.string.context_action_refresh_scan), new Runnable() {
                @Override
                public void run() {
                    startWifiScan();
                }
            });
            final String wifiSsid = wifiFocusedSsid();
            if (wifiSsid != null && isWifiNetworkSaved(wifiSsid)) {
                addContextAction(getString(R.string.context_action_forget_wifi), new Runnable() {
                    @Override
                    public void run() {
                        forgetWifiNetwork(wifiSsid);
                    }
                });
            }
        }
        if (currentScreenState == STATE_SETTINGS
                && (isThemeGalleryActive() || isThemeVariantPickerActive())
                && listThemes != null && listThemes.getVisibility() == View.VISIBLE) {
            addThemeBrowserContextActions();
        }
        File musicTrack = focusedMusicTrackForContext();
        if (musicTrack != null) {
            String plCtx = null;
            if (playback.musicActivePlaylistName() != null
                    && ((currentScreenState == STATE_PLAYER && playback.isMusicActive())
                    || isMusicQueueEditorScreen())) {
                plCtx = playback.musicActivePlaylistName();
            }
            addMusicBrowseContextActions(musicTrack, plCtx);
        }
    }

    /** Screen off — su power key first (Y1), then goToSleep; shared by hold-center and context menu. */
    private void performScreenSleep(boolean feedback) {
        if (feedback) clickFeedback();
        if (trySuScreenOff()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            java.lang.reflect.Method goToSleep = PowerManager.class.getMethod(
                    "goToSleep", long.class);
            goToSleep.invoke(pm, android.os.SystemClock.uptimeMillis());
            if (!isScreenInteractive()) return;
        } catch (Exception ignored) {}
        if (trySuScreenOff()) return;
        Toast.makeText(this, getString(R.string.context_action_lock_failed), Toast.LENGTH_SHORT).show();
    }

    private boolean isScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 20) return pm.isInteractive();
            return pm.isScreenOn();
        } catch (Exception e) {
            return true;
        }
    }

    /** ponytail: Y1 system APK is not android.uid.system — root power key is the fast path */
    private boolean trySuScreenOff() {
        for (String su : new String[] {"/system/xbin/su", "su"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[] {su, "-c", "input keyevent 26"});
                if (p.waitFor() != 0) continue;
                if (!isScreenInteractive()) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void restartCurrentPodcast() {
        PodcastResumeStore.clear(getApplicationContext(), podcastResumeKey);
        podcastPendingResumeMs = 0;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.seekTo(0);
                if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                isPausedByHand = false;
            }
        } catch (Exception ignored) {}
        updatePlayerUI();
    }

    private void markCurrentPodcastPlayed() {
        PodcastResumeStore.clear(getApplicationContext(), podcastResumeKey);
        podcastPendingResumeMs = 0;
        Toast.makeText(this, getString(R.string.context_action_mark_played), Toast.LENGTH_SHORT).show();
    }

    private void playCurrentFolderAll() {
        List<File> list = new ArrayList<File>();
        File[] files = currentFolder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (isAudioFile(f)) list.add(f);
            }
        }
        if (!list.isEmpty()) playTrackList(list, 0);
    }

    private void appendTrackToMusicQueue(File track) {
        if (track == null || !track.isFile()) return;
        List<File> one = new ArrayList<File>();
        one.add(track);
        playback.appendToMusicQueue(one);
        suppressListClickUntil = System.currentTimeMillis() + 400;
        Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show();
    }

    private int virtualListFocusPosition() {
        if (listVirtualSongs == null) return -1;
        View focused = listVirtualSongs.getFocusedChild();
        if (focused != null) {
            int pos = listVirtualSongs.getPositionForView(focused);
            if (pos >= 0) return pos;
        }
        return listVirtualSongs.getSelectedItemPosition();
    }

    private File virtualFocusedAudioFile() {
        if (listVirtualSongs == null) return null;
        int pos = virtualListFocusPosition();
        if (pos >= 0 && pos < virtualSongList.size()) return virtualSongList.get(pos);
        return null;
    }

    private File podcastSavedFileFocus() {
        View c = getCurrentFocus();
        if (c != null && c.getTag() instanceof File) {
            File f = (File) c.getTag();
            if (f.isFile()) return f;
        }
        return null;
    }

    private void deleteSavedPodcastFile(File file) {
        if (file == null) return;
        PodcastResumeStore.clear(getApplicationContext(), PodcastResumeStore.keyForFile(file));
        if (file.delete()) {
            Toast.makeText(this, getString(R.string.podcasts_deleted_saved), Toast.LENGTH_SHORT).show();
            if (podcastSavedShowFolder != null && !podcastSavedShowFolder.isEmpty()) {
                buildPodcastSavedEpisodesUI(podcastSavedShowFolder);
            } else {
                buildPodcastSavedShowsUI();
            }
        } else {
            Toast.makeText(this, getString(R.string.podcasts_delete_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void addContextWifiToggle() {
        if (currentScreenState == STATE_WIFI_KEYBOARD) return;
        addContextAction(getString(R.string.wifi_power), null, wifiPowerStateText(), new Runnable() {
            @Override
            public void run() {
                toggleWifiFromContextMenu();
            }
        });
    }

    private boolean isWifiPowerOn() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private String wifiPowerStateText() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return stateOnOff(false);
        int state = wm.getWifiState();
        if (state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_DISABLING) {
            return "Wait...";
        }
        return stateOnOff(wm.isWifiEnabled());
    }

    private boolean podcastStreamingNeedsWifi() {
        if (!playback.isPodcastActive()) return false;
        if (podcastDownloadInProgress) return true;
        if (!podcastPartialPlaybackStarted) return false;
        if (podcastGrowingCacheFinal != null && podcastGrowingCacheFinal.isFile()) return false;
        if (podcastGrowingCacheFile != null && podcastDownloadBytesTotal > 0
                && podcastGrowingCacheFile.length() >= podcastDownloadBytesTotal) {
            return false;
        }
        return true;
    }

    private boolean wifiDisableNeedsWarning() {
        return WifiOffPolicy.disableNeedsWarning(
                isWifiPowerOn(),
                soulseekSharePolicy.announceShares(),
                hasActiveReachDownload(),
                reachPartialPlaybackStarted && reachDownloadInProgress(),
                podcastStreamingNeedsWifi());
    }

    private void applyWifiPowerState(boolean enable) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        if (enable) {
            Toast.makeText(this, getString(R.string.toast_wifi_turning_on), Toast.LENGTH_SHORT).show();
            wm.setWifiEnabled(true);
        } else {
            Toast.makeText(this, getString(R.string.toast_wifi_turning_off), Toast.LENGTH_SHORT).show();
            wm.setWifiEnabled(false);
        }
    }

    private void toggleWifiFromContextMenu() {
        if (!isWifiPowerOn()) {
            applyWifiPowerState(true);
            return;
        }
        if (!wifiDisableNeedsWarning()) {
            applyWifiPowerState(false);
            return;
        }
        showThemedConfirm(
                getString(R.string.wifi_off_warning_title),
                getString(R.string.wifi_off_warning_message),
                getString(R.string.wifi_off_confirm),
                getString(R.string.common_cancel),
                new Runnable() {
                    @Override
                    public void run() {
                        applyWifiPowerState(false);
                    }
                });
    }

    private void showThemedConfirm(final String title, final String message,
            final String confirmLabel, final String cancelLabel, final Runnable onConfirm) {
        showThemedConfirm(title, message, confirmLabel, cancelLabel, onConfirm, null);
    }

    private void showThemedConfirm(final String title, final String message,
            final String confirmLabel, final String cancelLabel,
            final Runnable onConfirm, final Runnable onCancel) {
        dismissThemedContextMenu();
        String[] labels = new String[] { confirmLabel, cancelLabel };
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        int margin = (int) (10 * getResources().getDisplayMetrics().density);
        int panelW = screenWidthPx > margin * 2 ? screenWidthPx - margin * 2 : y1ActiveRowWidthPx();
        themedContextMenu.show(root, title, message, labels, null, null,
                new boolean[] { false, false },
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(final int index) {
                        suppressListClickUntil = System.currentTimeMillis() + CONTEXT_MENU_CLICK_SUPPRESS_MS;
                        dismissThemedContextMenu();
                        if (index == 0 && onConfirm != null) {
                            clickFeedback();
                            onConfirm.run();
                        } else if (index != 0 && onCancel != null) {
                            onCancel.run();
                        }
                    }
                }, y1RowHeightPx, panelW, false, true);
        clickFeedback();
    }

    private void addContextAction(String label, Runnable action) {
        addContextAction(label, null, null, action);
    }

    private void addContextAction(String label, String iconKey, String stateText, Runnable action) {
        contextMenuLabels.add(label);
        contextMenuIconKeys.add(iconKey);
        contextMenuStateTexts.add(stateText);
        contextMenuHeaders.add(Boolean.FALSE);
        contextMenuActions.add(action);
    }

    private void addContextSectionHeader(String label) {
        contextMenuLabels.add(label);
        contextMenuIconKeys.add(null);
        contextMenuStateTexts.add(null);
        contextMenuHeaders.add(Boolean.TRUE);
        contextMenuActions.add(null);
    }

    private File browserFocusedAudioFile() {
        if (listVirtualSongs != null && listVirtualSongs.getVisibility() == View.VISIBLE
                && currentBrowserMode == BROWSER_FOLDER && !folderBrowserEntries.isEmpty()) {
            int pos = listVirtualSongs.getSelectedItemPosition();
            if (pos >= 0 && pos < folderBrowserEntries.size()) {
                FolderBrowserEntry e = folderBrowserEntries.get(pos);
                if (e.kind == FolderBrowserEntry.KIND_AUDIO && e.file != null) return e.file;
            }
        }
        View c = getCurrentFocus();
        if (c != null && c.getTag() instanceof File) {
            File f = (File) c.getTag();
            if (f.isFile() && isAudioFile(f)) return f;
        }
        return null;
    }

    private SoulseekClient.Result soulseekFocusedResult() {
        View c = getCurrentFocus();
        if (c != null && c.getTag() instanceof SoulseekClient.Result) {
            return (SoulseekClient.Result) c.getTag();
        }
        return null;
    }

    private String soulseekFocusedRecentQuery() {
        if (currentScreenState != STATE_SOULSEEK || soulseekUiMode != SOULSEEK_UI_SEARCH) return null;
        View c = getCurrentFocus();
        if (c == null) return null;
        Object tag = c.getTag(R.id.tag_soulseek_recent_query);
        if (!(tag instanceof String)) return null;
        String q = ((String) tag).trim();
        return q.length() > 0 ? q : null;
    }

    private void handleBackShortPress() {
        if (currentScreenState == STATE_WIFI_KEYBOARD) {
            if (keyboardReturnState == STATE_SETTINGS && keyboardReturnSettingsSubKey != null) {
                restoreSettingsAfterSoulseekAccount();
            } else {
                changeScreen(keyboardReturnState);
            }
            return;
        }
        if (currentScreenState == STATE_PLAYER) {
            if (playerScrubCursorActive) {
                cancelPlayerScrubCursor();
                return;
            }
            returnFromPlayer();
            return;
        }
        if (currentScreenState == STATE_BRIGHTNESS) {
            changeScreen(STATE_SETTINGS);
            return;
        }
        if (currentScreenState == STATE_STORAGE) {
            changeScreen(STATE_SETTINGS);
            return;
        }
        if (currentScreenState == STATE_WEBSERVER) {
            if (isServerRunning) {
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(getString(R.string.webserver_dialog_title))
                        .setMessage(getString(R.string.webserver_dialog_message))
                        .setPositiveButton(getString(R.string.webserver_stop_exit), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                toggleWebServer();
                                changeScreen(STATE_SETTINGS);
                            }
                        })
                        .setNegativeButton(getString(R.string.webserver_keep_running), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                changeScreen(STATE_SETTINGS);
                            }
                        })
                        .show();
            } else {
                changeScreen(STATE_SETTINGS);
            }
            return;
        }
        if (currentScreenState == STATE_BROWSER) {
            if (isPickingBackground) {
                if (currentFolder.getAbsolutePath().equals(getStorageRoot().getAbsolutePath())) {
                    isPickingBackground = false;
                    changeScreen(STATE_SETTINGS);
                } else {
                    File parent = currentFolder.getParentFile();
                    if (parent != null) currentFolder = parent;
                    buildFileBrowserUI();
                }
            } else {
                if (currentBrowserMode == BROWSER_ROOT) {
                    changeScreen(STATE_MENU);
                } else if (currentBrowserMode == BROWSER_FOLDER) {
                    if (currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath())) {
                        currentBrowserMode = BROWSER_ROOT;
                        buildFileBrowserUI();
                    } else {
                        currentFolder = currentFolder.getParentFile();
                        buildFileBrowserUI();
                    }
                } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                    if ("ARTIST_ALBUM".equals(virtualQueryType)) {
                        currentBrowserMode = BROWSER_ARTIST_ALBUMS;
                        buildArtistAlbums();
                    } else if ("PLAYLIST".equals(virtualQueryType)) {
                        currentBrowserMode = BROWSER_PLAYLISTS;
                        buildPlaylistsUI();
                    } else if (virtualQueryType.equals("ALL")) {
                        currentBrowserMode = BROWSER_ROOT;
                        buildFileBrowserUI();
                    } else if ("GENRE".equals(virtualQueryType)) {
                        currentBrowserMode = BROWSER_GENRES;
                        buildVirtualCategories("GENRE");
                    } else if ("ARTIST".equals(virtualQueryType)) {
                        currentBrowserMode = BROWSER_ARTISTS;
                        buildVirtualCategories("ARTIST");
                    } else {
                        currentBrowserMode = BROWSER_ALBUMS;
                        buildVirtualCategories("ALBUM");
                    }
                } else if (currentBrowserMode == BROWSER_ARTIST_ALBUMS) {
                    currentBrowserMode = BROWSER_ARTISTS;
                    buildVirtualCategories("ARTIST");
                } else if (currentBrowserMode == BROWSER_GENRES
                        || currentBrowserMode == BROWSER_ARTISTS
                        || currentBrowserMode == BROWSER_ALBUMS) {
                    currentBrowserMode = BROWSER_ROOT;
                    buildFileBrowserUI();
                } else if (currentBrowserMode == BROWSER_PLAYLISTS) {
                    currentBrowserMode = BROWSER_ROOT;
                    buildFileBrowserUI();
                } else {
                    currentBrowserMode = BROWSER_ROOT;
                    buildFileBrowserUI();
                }
            }
            return;
        }
        if (currentScreenState == STATE_PODCASTS) {
            if (podcastUiMode == PODCAST_UI_EPISODES) {
                if (podcastShows.isEmpty()) buildPodcastSearchUI();
                else buildPodcastShowsUI();
            } else if (podcastUiMode == PODCAST_UI_SHOWS) {
                buildPodcastSearchUI();
            } else if (podcastUiMode == PODCAST_UI_SAVED) {
                if (podcastSavedShowFolder != null && !podcastSavedShowFolder.isEmpty()) {
                    buildPodcastSavedShowsUI();
                } else {
                    buildPodcastSearchUI();
                }
            } else if (podcastUiMode == PODCAST_UI_BROWSE_GENRE
                    || podcastUiMode == PODCAST_UI_BROWSE_COUNTRY
                    || podcastUiMode == PODCAST_UI_STOREFRONT) {
                buildPodcastSearchUI();
            } else {
                changeScreen(STATE_MENU);
            }
            return;
        }
        if (currentScreenState == STATE_SOULSEEK) {
            if (soulseekUiMode == SOULSEEK_UI_DOWNLOAD) {
                if (soulseekDownloadUiFailed) {
                    soulseekDownloadUiFailed = false;
                    soulseekFailedResult = null;
                    buildSoulseekResultsUI();
                } else if (soulseekBailWithoutConfirm()) {
                    cancelSoulseekDownloadSilent();
                    soulseekDownloadStalled = false;
                    buildSoulseekResultsUI();
                } else if (hasActiveReachDownload() && !reachPartialPlaybackStarted) {
                    confirmStopReachDownload(new Runnable() {
                        @Override
                        public void run() {
                            if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
                        }
                    });
                } else {
                    cancelSoulseekDownload();
                }
            } else if (soulseekUiMode == SOULSEEK_UI_ACTION) {
                buildSoulseekResultsUI();
            } else if (soulseekUiMode == SOULSEEK_UI_RESULTS) {
                buildSoulseekSearchUI();
            } else {
                returnFromSoulseek();
            }
            return;
        }
        if (currentScreenState == STATE_APPS) {
            changeScreen(STATE_MENU);
            return;
        }
        if (currentScreenState == STATE_BLUETOOTH || currentScreenState == STATE_WIFI) {
            changeScreen(STATE_SETTINGS);
            return;
        }
        if (currentScreenState == STATE_SETTINGS) {
            if (handleAppearanceSettingsBack()) return;
            if (SettingsScreens.BACKGROUND.equals(settingsSubScreenKey)) {
                returnToSettingsParent();
                return;
            }
            if (handleHomeScreenEditorBack()) return;
            if (handleMusicQueueEditorBack()) return;
            if (handleLanguageSettingsBack()) return;
            if (handleSoulseekSettingsBack()) return;
            if (handleThemeGalleryBack()) return;
            if (handleSystemUpdateBack()) return;
            changeScreen(STATE_MENU);
        }
    }

    private boolean handleBackKeyDown(KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            backKeyDownTime = System.currentTimeMillis();
            backLongPressHandled = false;
        } else if (!backLongPressHandled
                && System.currentTimeMillis() - backKeyDownTime >= BACK_LONG_PRESS_MS) {
            showThemedContextMenu();
            backLongPressHandled = true;
        }
        return true;
    }

    private int podcastEpisodeFocusIndex() {
        View c = getCurrentFocus();
        if (c == null || containerBrowserItems == null) return -1;
        int childIdx = containerBrowserItems.indexOfChild(c);
        if (childIdx <= 0) return -1;
        return childIdx - 1;
    }
    private Button createListButton(String text) {
        final Button btn = new Button(this);
        btn.setText(text);
        configureY1ThemedButton(btn, y1RowKindForScreen());
        return btn;
    }

    /** Theme row chrome for standalone XML buttons (scan, server toggle) and list rows. */
    private void configureY1ThemedButton(final Button btn, final int rowKind) {
        int rowW = listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx();
        btn.setBackground(getY1RowBackground(false, rowW, rowKind));
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        btn.setSoundEffectsEnabled(false);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        ThemeManager.applyThemedTextStyle(btn, y1RowTextColorNormal(rowKind));
        btn.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        btn.setPadding(hPad, 0, hPad, 0);
        btn.setFocusable(true);
        enableMarquee(btn);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                int w = btn.getWidth() > 0 ? btn.getWidth()
                        : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
                btn.setBackground(getY1RowBackground(hasFocus, w, rowKind));
                ThemeManager.applyThemedTextStyle(btn, hasFocus
                        ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                btn.setSelected(hasFocus);
                if (hasFocus) showFastScrollLetter(btn.getText().toString());
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        btn.setLayoutParams(lp);
    }

    private void initY1ThemedActionButtons() {
        if (btnScanBt != null) configureY1ThemedButton(btnScanBt, Y1_ROW_ITEM);
        if (btnScanWifi != null) configureY1ThemedButton(btnScanWifi, Y1_ROW_ITEM);
        if (btnServerToggle != null) {
            configureY1ThemedButton(btnServerToggle, Y1_ROW_ITEM);
            btnServerToggle.setGravity(android.view.Gravity.CENTER);
        }
    }

    private void refreshY1ThemedActionButtons() {
        refreshY1ThemedActionButton(btnScanBt, Y1_ROW_ITEM);
        refreshY1ThemedActionButton(btnScanWifi, Y1_ROW_ITEM);
        refreshY1ThemedActionButton(btnServerToggle, Y1_ROW_ITEM);
    }

    private void refreshY1ThemedActionButton(Button btn, int rowKind) {
        if (btn == null) return;
        int w = btn.getWidth() > 0 ? btn.getWidth()
                : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
        boolean focused = btn.hasFocus();
        btn.setBackground(getY1RowBackground(focused, w, rowKind));
        ThemeManager.applyThemedTextStyle(btn, focused
                ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
    }
    private void buildSettingsUI() {
        final int targetFocusIndex = lastSettingsFocusIndex;
        setMusicQueueListVisible(false);
        setThemesListVisible(false);
        clearThemeGalleryPreview();
        settingsSubScreenKey = null;
        settingsSubScreenExtra = null;
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        // createCategoryHeader("━ QUICK SETTINGS ━");

        final LinearLayout btnShuffle = createSettingsRow(RowKeys.SHUFFLE, R.string.settings_shuffle_mode, false);
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isShuffleMode = !isShuffleMode;
                updatePlayerStatusIndicators();
                try {
                    prefs.edit().putBoolean("shuffle", isShuffleMode).commit();
                } catch (Exception e) {
                }

                if (!playback.musicPlaylist().isEmpty() && !playback.musicOriginal().isEmpty()) {
                    File currentSong = playback.musicPlaylist().get(playback.musicIndex());
                    playback.reshuffleMusic(isShuffleMode);
                    if (playback.musicIndex() < 0 || !playback.musicPlaylist().contains(currentSong)) {
                        playback.setMusicIndex(playback.musicPlaylist().indexOf(currentSong));
                        if (playback.musicIndex() < 0) playback.setMusicIndex(0);
                    }
                }
                refreshSettingsPreview(RowKeys.SHUFFLE);
            }
        });
        containerSettingsItems.addView(btnShuffle);

        final LinearLayout btnRepeat = createSettingsRow(RowKeys.REPEAT, R.string.settings_repeat_mode, false);
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                repeatMode = (repeatMode + 1) % 3;
                updatePlayerStatusIndicators();
                try {
                    prefs.edit().putInt("repeat_mode", repeatMode).commit();
                } catch (Exception e) {
                }
                refreshSettingsPreview(RowKeys.REPEAT);
            }
        });
        containerSettingsItems.addView(btnRepeat);

        final LinearLayout btnEq = createSettingsRow(RowKeys.EQ, R.string.settings_equalizer, false);
        btnEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (eqPresetNames.size() > 1) {
                    currentEqPresetIndex = (currentEqPresetIndex + 1) % eqPresetNames.size();
                    try {
                        prefs.edit().putInt("eq_preset", currentEqPresetIndex).commit();
                    } catch (Exception e) {
                    }

                    if (equalizer != null) {
                        try {
                            equalizer.usePreset((short) currentEqPresetIndex);
                        } catch (Exception e) {
                        }
                    }
                    refreshSettingsPreview(RowKeys.EQ);
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_eq_unsupported), Toast.LENGTH_SHORT)
                            .show();
                }
            }
        });
        containerSettingsItems.addView(btnEq);

        final LinearLayout btnSound = createSettingsRow(RowKeys.BUTTON_SOUND, R.string.settings_button_sound, false);
        btnSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSoundEffectEnabled = !isSoundEffectEnabled;
                applySoundSetting();
                clickFeedback();
                try {
                    prefs.edit().putBoolean("sound", isSoundEffectEnabled).commit();
                } catch (Exception e) {
                }
                refreshSettingsPreview(RowKeys.BUTTON_SOUND);
            }
        });
        containerSettingsItems.addView(btnSound);

        final LinearLayout btnVibrate = createSettingsRow(RowKeys.BUTTON_VIBRATE, R.string.settings_button_vibrate, false);
        btnVibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isVibrationEnabled = !isVibrationEnabled;
                clickFeedback();
                try {
                    prefs.edit().putBoolean("vibrate", isVibrationEnabled).commit();
                } catch (Exception e) {
                }
                refreshSettingsPreview(RowKeys.BUTTON_VIBRATE);
            }
        });
        containerSettingsItems.addView(btnVibrate);

        final LinearLayout btnScreenOffCtrl = createSettingsRow(RowKeys.SCREEN_OFF_CTRL, R.string.settings_screen_off_control, false);
        btnScreenOffCtrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isScreenOffControlEnabled = !isScreenOffControlEnabled;
                try {
                    prefs.edit().putBoolean("screen_off_control", isScreenOffControlEnabled).commit();
                } catch (Exception e) {
                }
                refreshSettingsPreview(RowKeys.SCREEN_OFF_CTRL);
            }
        });
        containerSettingsItems.addView(btnScreenOffCtrl);

        LinearLayout btnAppearance = createSettingsRow(RowKeys.APPEARANCE, R.string.settings_appearance, true);
        btnAppearance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildAppearanceSettingsUI();
            }
        });
        containerSettingsItems.addView(btnAppearance);

        if (!HomeMenuConfig.isMoreEnabled(prefs)) {
            LinearLayout btnMoreMenu = createSettingsRow(RowKeys.MORE_MENU, R.string.home_menu_more, true);
            btnMoreMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    changeScreen(STATE_MORE);
                }
            });
            containerSettingsItems.addView(btnMoreMenu);
        }

        final LinearLayout btnTimeout = createSettingsRow(RowKeys.SCREEN_TIMEOUT, R.string.settings_screen_timeout, false);
        btnTimeout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                currentTimeoutIndex = (currentTimeoutIndex + 1) % TIMEOUT_VALUES.length;
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                            TIMEOUT_VALUES[currentTimeoutIndex]);
                } catch (Exception e) {
                }
                try {
                    prefs.edit().putInt("timeout_idx", currentTimeoutIndex).commit();
                } catch (Exception e) {
                }
                refreshSettingsPreview(RowKeys.SCREEN_TIMEOUT);
            }
        });
        containerSettingsItems.addView(btnTimeout);

        LinearLayout btnLanguage = createSettingsRow(RowKeys.LANGUAGE, R.string.settings_language, true);
        btnLanguage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildLanguageSettingsUI();
            }
        });
        containerSettingsItems.addView(btnLanguage);

        // (Home Screen, Background, Theme — under Settings → Appearance)

        // (그 아래에 이어지는 Power Off 메뉴 등 기존 코드 유지...)

        // createCategoryHeader("━ SYSTEM MENUS ━");

        LinearLayout btnPowerOff = createSettingsRow(RowKeys.POWER_OFF, R.string.settings_power_off, true);
        btnPowerOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(getString(R.string.dialog_power_off_title))
                        .setMessage(getString(R.string.dialog_power_off_message))
                        .setPositiveButton("Shut Down", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Process proc = Runtime.getRuntime().exec(new String[] { "su", "-c", "reboot -p" });
                                    proc.waitFor();
                                } catch (Exception e) {
                                    try {
                                        Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
                                        intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(intent);
                                    } catch (Exception ex) {
                                        Toast.makeText(MainActivity.this, getString(R.string.dialog_power_off_blocked),
                                                Toast.LENGTH_LONG).show();
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnPowerOff);
        // 🚀 [추가] 락박스(Rockbox) OS로 재부팅하는 버튼
       /* LinearLayout btnRockbox = createSettingRow("Reboot to Rockbox", "〉 ");
        btnRockbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(getString(R.string.dialog_rockbox_title))
                        .setMessage(getString(R.string.dialog_rockbox_message))
                        .setPositiveButton("Reboot", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Toast.makeText(MainActivity.this, getString(R.string.dialog_rockbox_rebooting), Toast.LENGTH_SHORT)
                                            .show();

                                    // 💡 락박스 진입 명령어 실행 (기기 파티션 구조에 따라 다를 수 있습니다)
                                    // 기본적으로 대부분의 듀얼 부팅 기기는 아래 명령어 중 하나를 사용합니다.
                                    Runtime.getRuntime().exec(new String[] { "su", "-c", "reboot alternate" });

                                    // 만약 위 명령어로 일반 안드로이드 재부팅이 된다면, 아래 명령어의 주석(//)을 해제하고 시도해 보세요.
                                    // Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot recovery"});

                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, getString(R.string.dialog_rockbox_failed),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnRockbox);
        */
        LinearLayout btnServerMenu = createSettingsRow(RowKeys.WEB_SERVER, R.string.settings_web_server, true);
        btnServerMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_WEBSERVER);
                clickFeedback();
            }
        });
        if (ConnectivityHelper.shouldShowMenuItem(this, HomeMenuConfig.ID_PC_UPLOAD)) {
            containerSettingsItems.addView(btnServerMenu);
        }

        LinearLayout btnWifiMenu = createSettingsRow(RowKeys.WIFI_SETUP, R.string.settings_wifi_setup, true);
        btnWifiMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_WIFI);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnWifiMenu);

        LinearLayout btnSoulseekMenu = createSettingsRow(RowKeys.SOULSEEK, R.string.settings_soulseek, true);
        soulseekSettingsMenuFocusIndex = containerSettingsItems.getChildCount();
        btnSoulseekMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekSettingsUI();
            }
        });
        if (ConnectivityHelper.shouldShowMenuItem(this, HomeMenuConfig.ID_SOULSEEK)) {
            containerSettingsItems.addView(btnSoulseekMenu);
        }

// 🚀 [추가 1] 인터넷에서 앨범 아트 및 곡 정보 자동 검색 켜기/끄기
        final LinearLayout btnAutoFetch = createSettingsRow(RowKeys.AUTO_FETCH, R.string.settings_auto_fetch, false);
        btnAutoFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isAutoFetchEnabled = !isAutoFetchEnabled;
                try {
                    prefs.edit().putBoolean("auto_fetch", isAutoFetchEnabled).commit();
                } catch (Exception e) {}
                refreshSettingsPreview(RowKeys.AUTO_FETCH);
            }
        });
        containerSettingsItems.addView(btnAutoFetch);

        if (BuildConfig.FEATURE_OTA_UPDATE) {
            LinearLayout btnAppVersion = createSettingsRow(RowKeys.SYSTEM_UPDATE, R.string.settings_app_version, true);
            btnAppVersion.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    buildUpdateSettingsUI();
                }
            });
            containerSettingsItems.addView(btnAppVersion);
        }



        LinearLayout btnBtMenu = createSettingsRow(RowKeys.BLUETOOTH_SETUP, R.string.settings_bluetooth_setup, true);
        btnBtMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_BLUETOOTH);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnBtMenu);

        LinearLayout btnBrightMenu = createSettingsRow(RowKeys.BRIGHTNESS, R.string.settings_display_brightness, true);
        btnBrightMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_BRIGHTNESS);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnBrightMenu);

        LinearLayout btnStorageMenu = createSettingsRow(RowKeys.STORAGE, R.string.settings_storage_info, true);
        btnStorageMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_STORAGE);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnStorageMenu);

        // (Background settings — under Settings → Appearance)

// 🚀 [추가 2] 기기에 쌓인 앨범 아트 캐시 파일들 한 번에 지우기 (용량 확보)
        LinearLayout btnClearCache = createSettingsRow(RowKeys.CLEAR_CACHE, R.string.settings_clear_cache, true);
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(getString(R.string.dialog_clear_cache_title))
                        .setMessage(getString(R.string.dialog_clear_cache_message))
                        .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    File coverFolder = getCoversFolder();
                                    int count = 0;
                                    if (coverFolder.exists()) {
                                        File[] files = coverFolder.listFiles();
                                        if (files != null) {
                                            for (File f : files) {
                                                if (f.isFile() && f.delete()) count++;
                                            }
                                        }
                                    }
                                    Toast.makeText(MainActivity.this, getString(R.string.dialog_clear_cache_ok, count), Toast.LENGTH_SHORT).show();

                                    // 메인 화면에 남아있는 이미지를 기본 아이콘으로 초기화합니다.
                                    ivAlbumArt.setImageResource(R.drawable.default_album);
                                    ivPlayerBgBlur.setImageResource(0);
                                    lastAlbumArtBytes = null;
                                    updateMainMenuBackground();
                                    refreshNowPlayingPreview();
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, getString(R.string.dialog_clear_cache_failed), Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnClearCache);
        LinearLayout btnTime = createSettingsRow(RowKeys.DATETIME, R.string.settings_datetime, true);
        btnTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();

                // 시스템 시간을 먼저 읽어와서 임시 변수에 저장합니다.
                java.util.Calendar c = java.util.Calendar.getInstance();
                dtYear = c.get(java.util.Calendar.YEAR);
                dtMonth = c.get(java.util.Calendar.MONTH) + 1;
                dtDay = c.get(java.util.Calendar.DAY_OF_MONTH);
                dtHour = c.get(java.util.Calendar.HOUR_OF_DAY);
                dtMinute = c.get(java.util.Calendar.MINUTE);

                // 우리가 새로 만든 예쁜 리스트 화면을 띄웁니다!
                buildDateTimeUI();
            }
        });
        containerSettingsItems.addView(btnTime);

        // 🚀 [수정] 오염되지 않은 안전한 백업 인덱스(targetFocusIndex)를 사용하여 정확한 위치로 강제 이동!
        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (targetFocusIndex >= 0 && targetFocusIndex < containerSettingsItems.getChildCount()) {
                    View target = containerSettingsItems.getChildAt(targetFocusIndex);
                    target.requestFocus();

                    // 스크롤 뷰가 해당 버튼 위치를 찾아서 화면을 쫙 내려주도록 강제 명령!
                    if (containerSettingsItems.getParent() instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) containerSettingsItems.getParent()).requestChildFocus(containerSettingsItems, target);
                    }

                    // 이동을 마친 후 변수 상태를 일치시켜 줍니다.
                    lastSettingsFocusIndex = targetFocusIndex;
                    if (target instanceof LinearLayout) {
                        Object tag = target.getTag();
                        if (tag instanceof String) updateSettingsPreview((String) tag);
                    }
                } else if (containerSettingsItems.getChildCount() > 1) {
                    containerSettingsItems.getChildAt(1).requestFocus();
                }
            }
        }, 50);
    } // buildSettingsUI 함수 끝/ buildSettingsUI 함수 끝

    private void buildAppearanceSettingsUI() {
        settingsParentKey = null;
        setSettingsSubScreen(SettingsScreens.APPEARANCE);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        LinearLayout btnMatchBar = createSettingsRow(RowKeys.STATUS_BAR_MATCH_FONT,
                R.string.settings_status_bar_match_font, false);
        btnMatchBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                statusBarMatchFont = !statusBarMatchFont;
                ThemeManager.setStatusBarMatchItemText(statusBarMatchFont);
                prefs.edit().putBoolean("status_bar_match_font", statusBarMatchFont).commit();
                applyStatusBarTheme();
                applyThemeToMainMenu();
                refreshSettingsPreview(RowKeys.STATUS_BAR_MATCH_FONT);
            }
        });
        containerSettingsItems.addView(btnMatchBar);

        final LinearLayout btnStatusText = createSettingsRow(RowKeys.STATUS_BAR_LEFT,
                R.string.settings_status_bar_text, false);
        btnStatusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                statusBarShowsTitle = !statusBarShowsTitle;
                try {
                    prefs.edit().putBoolean("status_bar_title", statusBarShowsTitle).commit();
                } catch (Exception e) {}
                updateStatusBarTitle();
                refreshSettingsPreview(RowKeys.STATUS_BAR_LEFT);
            }
        });
        containerSettingsItems.addView(btnStatusText);

        final LinearLayout btnFullWidth = createSettingsRow(RowKeys.FULL_WIDTH,
                R.string.settings_full_width_menus, false);
        btnFullWidth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isFullWidthMenus = !isFullWidthMenus;
                try {
                    prefs.edit().putBoolean("full_width_menus", isFullWidthMenus).commit();
                } catch (Exception e) {}
                applyFullWidthMenusLayout();
                buildHomeMenu();
                if (currentScreenState == STATE_MENU) {
                    requestFirstHomeMenuFocus();
                } else {
                    applyThemeToMainMenu();
                }
                buildAppearanceSettingsUI();
            }
        });
        containerSettingsItems.addView(btnFullWidth);

        LinearLayout btnHomeScreen = createSettingsRow(RowKeys.HOME_SCREEN, R.string.home_screen_editor, true);
        homeScreenEditorMenuFocusIndex = containerSettingsItems.getChildCount();
        btnHomeScreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenEditorFocusIndex = 2;
                openAppearanceSubmenu(new Runnable() {
                    @Override
                    public void run() {
                        buildHomeScreenEditorUI();
                    }
                });
            }
        });
        containerSettingsItems.addView(btnHomeScreen);

        LinearLayout btnNowPlaying = createSettingsRow(RowKeys.NOW_PLAYING, R.string.settings_now_playing, true);
        btnNowPlaying.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openAppearanceSubmenu(new Runnable() {
                    @Override
                    public void run() {
                        buildNowPlayingSettingsUI();
                    }
                });
            }
        });
        containerSettingsItems.addView(btnNowPlaying);

        LinearLayout btnBgMenu = createSettingsRow(RowKeys.BACKGROUND, R.string.settings_background, true);
        btnBgMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openAppearanceSubmenu(new Runnable() {
                    @Override
                    public void run() {
                        buildBackgroundSettingsUI();
                    }
                });
            }
        });
        containerSettingsItems.addView(btnBgMenu);

        LinearLayout btnThemes = createSettingsRow(RowKeys.THEMES, R.string.settings_themes, true);
        btnThemes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openAppearanceSubmenu(new Runnable() {
                    @Override
                    public void run() {
                        buildUnifiedThemesUI();
                    }
                });
            }
        });
        containerSettingsItems.addView(btnThemes);

        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }

    private void buildLanguageSettingsUI() {
        setSettingsSubScreen(SettingsScreens.LANGUAGE);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        final String current = prefs.getString(LocaleHelper.PREF_LOCALE, LocaleHelper.LOCALE_SYSTEM);
        addLanguageOption(RowKeys.LANG_SYSTEM, LocaleHelper.LOCALE_SYSTEM, current);
        addLanguageOption(RowKeys.LANG_EN, "en", current);
        // ponytail: Korean hidden until wheel keyboard supports Hangul input

        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }

    private void addLanguageOption(final String rowKey, final String localeTag, String current) {
        final LinearLayout row = createSettingsRow(rowKey, RowKeys.labelResId(rowKey), false);
        TextView stateView = row.findViewWithTag("inline_state") instanceof TextView
                ? (TextView) row.findViewWithTag("inline_state") : null;
        if (stateView != null) {
            stateView.setText(localeTag.equals(current) ? getString(R.string.common_on) : "");
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                prefs.edit().putString(LocaleHelper.PREF_LOCALE, localeTag).commit();
                recreate();
            }
        });
        containerSettingsItems.addView(row);
    }

    private boolean handleLanguageSettingsBack() {
        if (SettingsScreens.LANGUAGE.equals(settingsSubScreenKey)) {
            buildSettingsUI();
            return true;
        }
        return false;
    }

    private void buildSoulseekSettingsUI() {
        setSettingsSubScreen(SettingsScreens.SOULSEEK);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                lastSettingsFocusIndex = soulseekSettingsMenuFocusIndex;
                buildSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        LinearLayout btnSearch = createSettingsRow(RowKeys.SOULSEEK_SEARCH, R.string.soulseek_search_row, true);
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openSoulseekScreen();
            }
        });
        containerSettingsItems.addView(btnSearch);

        SoulseekAccount skAccount = SoulseekAccount.load(prefs);
        LinearLayout btnAccount = createSettingRow(RowKeys.SOULSEEK_ACCOUNT, R.string.soulseek_account_row, SoulseekAccount.displayLabel(skAccount));
        btnAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openSoulseekAccountKeyboard();
            }
        });
        containerSettingsItems.addView(btnAccount);

        LinearLayout btnConnection = createSettingsRow(RowKeys.SOULSEEK_CONNECTION, R.string.soulseek_menu_connection, true);
        btnConnection.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekConnectionInfoUI();
            }
        });
        containerSettingsItems.addView(btnConnection);

        LinearLayout btnAbout = createSettingsRow(RowKeys.SOULSEEK_ABOUT, R.string.soulseek_menu_about, true);
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekAboutInfoUI();
            }
        });
        containerSettingsItems.addView(btnAbout);

        LinearLayout btnHideFlac = createSettingsRow(RowKeys.SOULSEEK_HIDE_FLAC,
                R.string.soulseek_hide_flac, false);
        btnHideFlac.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                soulseekHideFlac = !soulseekHideFlac;
                prefs.edit().putBoolean(SoulseekAccount.PREF_HIDE_FLAC, soulseekHideFlac).commit();
                refreshSettingsPreview(RowKeys.SOULSEEK_HIDE_FLAC);
            }
        });
        containerSettingsItems.addView(btnHideFlac);

        Button btnSoulseekRegen = createListButton(getString(R.string.soulseek_regenerate_account));
        btnSoulseekRegen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                SoulseekAccount.resetToAuto(prefs);
                if (soulseekClient != null) {
                    soulseekClient.shutdown();
                    soulseekClient = null;
                }
                Toast.makeText(MainActivity.this, getString(R.string.soulseek_auto_account), Toast.LENGTH_LONG).show();
                buildSoulseekSettingsUI();
            }
        });
        containerSettingsItems.addView(btnSoulseekRegen);

        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }

    private void buildSoulseekConnectionInfoUI() {
        setSettingsSubScreen(SettingsScreens.SOULSEEK_CONNECTION);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_back_short));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        addSettingsInfoParagraph(getString(R.string.soulseek_info_device_ip, formatWifiIpAddress()));
        addSettingsInfoParagraph(soulseekListenPortLabel());
        addSettingsInfoParagraph(soulseekNatStatusLabel());
        addSettingsInfoParagraph(soulseekSharingStatusLabel());
        addSettingsInfoParagraph(getString(R.string.soulseek_sharing_hint));
        addSettingsInfoParagraph(getString(R.string.soulseek_port_forward_hint));
        addSettingsInfoParagraph(getString(R.string.soulseek_info_nat_note));

        btnBack.requestFocus();
    }

    private void buildSoulseekAboutInfoUI() {
        setSettingsSubScreen(SettingsScreens.SOULSEEK_ABOUT);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_back_short));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        addSettingsInfoParagraph(getString(R.string.soulseek_account_hint));
        addSettingsInfoParagraph(getString(R.string.soulseek_download_only_note));

        btnBack.requestFocus();
    }

    private void buildUpdateSettingsUI() {
        if (!BuildConfig.FEATURE_OTA_UPDATE) return;
        setSettingsSubScreen(SettingsScreens.SYSTEM_UPDATE);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        String myVersionName = BuildConfig.VERSION_NAME;
        int myVersionCode = BuildConfig.VERSION_CODE;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            myVersionName = pInfo.versionName;
            myVersionCode = pInfo.versionCode;
        } catch (Exception ignored) {}

        final int localCode = myVersionCode;
        final String localName = myVersionName;
        containerSettingsItems.addView(createSettingRow(RowKeys.UPDATE_CURRENT, R.string.update_current_version,
                "v" + localName));

        final Button loadingRow = createListButton(getString(R.string.update_checking));
        loadingRow.setEnabled(false);
        containerSettingsItems.addView(loadingRow);

        loadOtaReleaseList(localCode, localName, loadingRow);
        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }

    private void loadOtaReleaseList(final int localCode, final String localName, final View loadingView) {
        final String updatesUrl = OTA_UPDATES_URL != null && !OTA_UPDATES_URL.trim().isEmpty()
                ? OTA_UPDATES_URL.trim() : SolarUpdateClient.DEFAULT_UPDATES_URL;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<SolarUpdateClient.ReleaseInfo> releases =
                            SolarUpdateClient.fetchUpdates(updatesUrl);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (loadingView.getParent() != null) {
                                containerSettingsItems.removeView(loadingView);
                            }
                            if (releases.isEmpty()) {
                                Button empty = createListButton(getString(R.string.update_none_found));
                                empty.setEnabled(false);
                                containerSettingsItems.addView(empty);
                                return;
                            }
                            int insertAt = containerSettingsItems.getChildCount();
                            for (int i = 0; i < releases.size(); i++) {
                                final SolarUpdateClient.ReleaseInfo release = releases.get(i);
                                String label = release.listLabel();
                                if (release.matchesInstalled(localCode, localName)) {
                                    label += getString(R.string.update_installed_marker);
                                }
                                Button btn = createListButton(label);
                                if (release.matchesInstalled(localCode, localName)) {
                                    btn.setEnabled(false);
                                } else {
                                    btn.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            clickFeedback();
                                            downloadAndInstallApk(release);
                                        }
                                    });
                                }
                                containerSettingsItems.addView(btn, insertAt + i);
                            }
                            if (insertAt < containerSettingsItems.getChildCount()) {
                                containerSettingsItems.getChildAt(insertAt).requestFocus();
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (loadingView.getParent() != null) {
                                containerSettingsItems.removeView(loadingView);
                            }
                            Button err = createListButton(getString(R.string.update_network_error));
                            err.setEnabled(false);
                            containerSettingsItems.addView(err);
                        }
                    });
                }
            }
        }, "SolarUpdateCheck").start();
    }
    private void downloadAndInstallApk(final SolarUpdateClient.ReleaseInfo release) {
        if (!BuildConfig.FEATURE_OTA_UPDATE || release == null) return;
        if (release.apkUrl == null || release.apkUrl.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.update_url_missing), Toast.LENGTH_SHORT).show();
            return;
        }
        int localCode = BuildConfig.VERSION_CODE;
        String localName = BuildConfig.VERSION_NAME;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            localCode = pInfo.versionCode;
            localName = pInfo.versionName;
        } catch (Exception ignored) {}
        if (release.matchesInstalled(localCode, localName)) {
            Toast.makeText(this, getString(R.string.update_already_installed), Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentScreenState != STATE_SETTINGS) {
            changeScreen(STATE_SETTINGS);
        }
        beginOtaDownloadUi(release, localCode, localName);
        startOtaDownload(release);
    }

    private boolean handleSystemUpdateBack() {
        if (SettingsScreens.SYSTEM_UPDATE_DOWNLOAD.equals(settingsSubScreenKey)) {
            cancelOtaDownload();
            return true;
        }
        if (SettingsScreens.SYSTEM_UPDATE.equals(settingsSubScreenKey)) {
            buildSettingsUI();
            return true;
        }
        return false;
    }

    private void beginOtaDownloadUi(SolarUpdateClient.ReleaseInfo release, int localCode, String localName) {
        otaActiveDownload = release;
        otaDownloadLocalCode = localCode;
        otaDownloadLocalName = localName != null ? localName : "";
        otaDownloadStartMs = android.os.SystemClock.uptimeMillis();
        otaDownloadLastDone = 0;
        otaDownloadLastSpeedMs = otaDownloadStartMs;
        otaDownloadLastProgressUiMs = 0;
        setSettingsSubScreen(SettingsScreens.SYSTEM_UPDATE_DOWNLOAD);
        updateStatusBarTitle();
        buildOtaDownloadUI(release, localCode, localName);
        otaDownloadUiHandler.removeCallbacks(otaDownloadTickRunnable);
        otaDownloadUiHandler.postDelayed(otaDownloadTickRunnable, 1000);
    }

    private void buildOtaDownloadUI(SolarUpdateClient.ReleaseInfo release, int localCode, String localName) {
        if (!BuildConfig.FEATURE_OTA_UPDATE || release == null) return;
        containerSettingsItems.removeAllViews();
        if (tvSettingsPreviewTitle != null) {
            tvSettingsPreviewTitle.setVisibility(View.VISIBLE);
            tvSettingsPreviewTitle.setText(release.listLabel());
            tvSettingsPreviewTitle.setSelected(true);
            enableMarquee(tvSettingsPreviewTitle);
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewTitle, ThemeManager.getTextColorPrimary());
        }
        if (tvSettingsPreviewState != null) {
            tvSettingsPreviewState.setVisibility(View.VISIBLE);
            tvSettingsPreviewState.setText(getString(R.string.update_download_status_connecting));
            ThemeManager.applyThemedTextStyle(tvSettingsPreviewState, ThemeManager.getTextColorSecondary());
        }

        Button titleRow = createListButton(release.listLabel());
        titleRow.setEnabled(false);
        containerSettingsItems.addView(titleRow);

        Button relationRow = createListButton(formatOtaDownloadRelation(release, localCode, localName));
        relationRow.setEnabled(false);
        containerSettingsItems.addView(relationRow);

        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        int rowKind = y1RowKindForScreen();
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        progressRow.setPadding(hPad, hPad / 2, hPad, hPad / 2);
        progressRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx));

        otaDownloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        otaDownloadProgressBar.setMax(100);
        otaDownloadProgressBar.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, y1RowHeightPx / 2, 1f);
        barLp.rightMargin = hPad;
        otaDownloadProgressBar.setLayoutParams(barLp);
        progressRow.addView(otaDownloadProgressBar);

        otaDownloadPercentText = new TextView(this);
        otaDownloadPercentText.setTextColor(y1RowTextColorNormal(rowKind));
        otaDownloadPercentText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        otaDownloadPercentText.setText(getString(R.string.soulseek_download_progress, 0));
        progressRow.addView(otaDownloadPercentText);
        containerSettingsItems.addView(progressRow);

        otaDownloadDetailText = new TextView(this);
        otaDownloadDetailText.setTextColor(y1RowTextColorNormal(rowKind));
        otaDownloadDetailText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f);
        otaDownloadDetailText.setPadding(hPad, 0, hPad, hPad / 2);
        otaDownloadDetailText.setText(getString(R.string.soulseek_download_detail,
                "0 B", "?", getString(R.string.soulseek_download_speed_unknown),
                getString(R.string.soulseek_download_eta_pending)));
        containerSettingsItems.addView(otaDownloadDetailText);

        otaDownloadStatusRow = createListButton(getString(R.string.update_download_status_connecting));
        otaDownloadStatusRow.setEnabled(false);
        containerSettingsItems.addView(otaDownloadStatusRow);

        Button cancel = createListButton(getString(R.string.soulseek_cancel_download));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                cancelOtaDownload();
            }
        });
        containerSettingsItems.addView(cancel);
        cancel.requestFocus();
    }

    private String formatOtaDownloadRelation(SolarUpdateClient.ReleaseInfo release,
            int localCode, String localName) {
        String local = localName != null ? localName.trim() : "";
        SolarUpdateClient.InstallRelation relation = release.compareToInstalled(localCode, local);
        if (relation == SolarUpdateClient.InstallRelation.UPGRADE) {
            return getString(R.string.update_download_relation_upgrade, local);
        }
        if (relation == SolarUpdateClient.InstallRelation.DOWNGRADE) {
            return getString(R.string.update_download_relation_downgrade, local);
        }
        return getString(R.string.update_download_relation_sidegrade, local);
    }

    private void updateOtaDownloadStatusUi() {
        if (!SettingsScreens.SYSTEM_UPDATE_DOWNLOAD.equals(settingsSubScreenKey)
                || otaActiveDownload == null) return;
        if (tvSettingsPreviewState != null) {
            int pct = otaDownloadProgressBar != null ? otaDownloadProgressBar.getProgress() : 0;
            if (pct > 0) {
                tvSettingsPreviewState.setText(getString(R.string.soulseek_download_progress, pct));
            } else {
                tvSettingsPreviewState.setText(getString(R.string.update_download_status_connecting));
            }
        }
        if (otaDownloadStatusRow != null) {
            int pct = otaDownloadProgressBar != null ? otaDownloadProgressBar.getProgress() : 0;
            otaDownloadStatusRow.setText(pct > 0
                    ? getString(R.string.soulseek_downloading, otaActiveDownload.listLabel(), pct)
                    : getString(R.string.update_download_status_connecting));
        }
    }

    private void updateOtaDownloadProgress(int pct, long done, long total) {
        if (otaDownloadProgressBar != null) otaDownloadProgressBar.setProgress(pct);
        if (otaDownloadPercentText != null) {
            otaDownloadPercentText.setText(getString(R.string.soulseek_download_progress, pct));
        }
        if (otaDownloadDetailText != null) {
            long now = android.os.SystemClock.uptimeMillis();
            String speed = getString(R.string.soulseek_download_speed_unknown);
            String eta = getString(R.string.soulseek_download_eta_pending);
            if (done > otaDownloadLastDone && now > otaDownloadLastSpeedMs) {
                long dt = now - otaDownloadLastSpeedMs;
                long dd = done - otaDownloadLastDone;
                if (dt > 0 && dd > 0) {
                    long bps = dd * 1000 / dt;
                    speed = formatSoulseekSpeed(bps);
                    if (bps > 0 && total > done) {
                        long sec = (total - done) / bps;
                        eta = sec < 3600
                                ? getString(R.string.soulseek_download_eta, sec + "s")
                                : getString(R.string.soulseek_download_eta, (sec / 60) + "m");
                    } else if (done >= total && total > 0) {
                        eta = getString(R.string.soulseek_download_eta_done);
                    }
                }
                otaDownloadLastDone = done;
                otaDownloadLastSpeedMs = now;
            }
            String doneLabel = formatSoulseekSize(done);
            String totalLabel = total > 0 ? formatSoulseekSize(total) : "?";
            otaDownloadDetailText.setText(getString(R.string.soulseek_download_detail,
                    doneLabel, totalLabel, speed, eta));
        }
        if (tvSettingsPreviewState != null && pct > 0) {
            tvSettingsPreviewState.setText(getString(R.string.soulseek_download_progress, pct));
        }
        if (otaDownloadStatusRow != null && otaActiveDownload != null) {
            otaDownloadStatusRow.setText(getString(R.string.soulseek_downloading,
                    otaActiveDownload.listLabel(), pct));
        }
    }

    private void clearOtaDownloadUiRefs() {
        otaDownloadProgressBar = null;
        otaDownloadPercentText = null;
        otaDownloadDetailText = null;
        otaDownloadStatusRow = null;
    }

    private void cancelOtaDownload() {
        if (otaDownloadCancel != null) otaDownloadCancel.set(true);
        if (otaDownloadThread != null) otaDownloadThread.interrupt();
        otaDownloadUiHandler.removeCallbacks(otaDownloadTickRunnable);
        otaActiveDownload = null;
        otaDownloadCancel = null;
        otaDownloadThread = null;
        clearOtaDownloadUiRefs();
        buildUpdateSettingsUI();
    }

    private void startOtaDownload(final SolarUpdateClient.ReleaseInfo release) {
        if (otaDownloadThread != null && otaDownloadThread.isAlive()) return;
        otaDownloadCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        final String apkUrl = release.apkUrl;
        otaDownloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                File updateFile = null;
                try {
                    File dir = getDir("update", Context.MODE_PRIVATE);
                    updateFile = new File(dir, "Solar_Update.apk");
                    if (updateFile.exists()) updateFile.delete();
                    com.solar.launcher.net.SolarHttp.downloadToFile(apkUrl, updateFile,
                            new com.solar.launcher.net.SolarHttp.DownloadProgress() {
                                @Override
                                public void onProgress(final long bytesRead, final long totalBytes) {
                                    long now = android.os.SystemClock.uptimeMillis();
                                    if (now - otaDownloadLastProgressUiMs < 200 && totalBytes > 0) {
                                        int pct = (int) (bytesRead * 100 / totalBytes);
                                        if (pct < 100) return;
                                    }
                                    otaDownloadLastProgressUiMs = now;
                                    final int pct = totalBytes > 0
                                            ? (int) Math.min(100, bytesRead * 100 / totalBytes) : 0;
                                    final long done = bytesRead;
                                    final long total = totalBytes;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!SettingsScreens.SYSTEM_UPDATE_DOWNLOAD
                                                    .equals(settingsSubScreenKey)) return;
                                            updateOtaDownloadProgress(pct, done, total);
                                        }
                                    });
                                }
                            }, 0L, null, otaDownloadCancel);
                    final File ready = updateFile;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            otaDownloadUiHandler.removeCallbacks(otaDownloadTickRunnable);
                            otaActiveDownload = null;
                            otaDownloadCancel = null;
                            otaDownloadThread = null;
                            clearOtaDownloadUiRefs();
                            installApk(ready, release);
                            if (!isInstalledAsSystemApp()) {
                                buildUpdateSettingsUI();
                            }
                        }
                    });
                } catch (Exception e) {
                    if (updateFile != null && updateFile.exists()) updateFile.delete();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            boolean cancelled = otaDownloadCancel != null && otaDownloadCancel.get();
                            otaDownloadUiHandler.removeCallbacks(otaDownloadTickRunnable);
                            otaActiveDownload = null;
                            otaDownloadCancel = null;
                            otaDownloadThread = null;
                            clearOtaDownloadUiRefs();
                            if (cancelled) {
                                buildUpdateSettingsUI();
                                return;
                            }
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.update_download_failed), Toast.LENGTH_LONG).show();
                            buildUpdateSettingsUI();
                        }
                    });
                }
            }
        }, "SolarOtaDownload");
        otaDownloadThread.start();
    }

    private LinearLayout createHomeOrderRow(final HomeMenuConfig.Entry entry, boolean moving) {
        final String rowKey = RowKeys.homeShortcut(entry.id);
        final LinearLayout layout = createRearrangeListRow(rowKey, getString(entry.labelResId),
                new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (!(v instanceof LinearLayout)) return;
                        LinearLayout row = (LinearLayout) v;
                        bindRearrangeRowAdornment(row, entry.id.equals(
                                isHomeMoreArrangeScreen() ? homeMoreMoveModeId : homeScreenMoveModeId),
                                hasFocus);
                        if (hasFocus && currentScreenState == STATE_SETTINGS) {
                            int idx = containerSettingsItems.indexOfChild(row);
                            if (idx != -1) homeScreenOrderFocusIndex = idx;
                            updateSettingsPreview(rowKey);
                            applyHomeShortcutConnectivityHint(row, rowKey, true);
                        } else if (!hasFocus) {
                            applyHomeShortcutConnectivityHint(row, rowKey, false);
                        }
                    }
                });
        bindRearrangeRowAdornment(layout, moving, false);
        return layout;
    }

    private void openHomeArrangeForRequiredShortcut(String id, int editorRowIndex) {
        clickFeedback();
        homeScreenEditorFocusIndex = editorRowIndex;
        List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
        int idx = ids.indexOf(id);
        homeScreenMoveModeId = id;
        homeScreenOrderFocusIndex = idx >= 0 ? idx + 1 : 1;
        Toast.makeText(this, getString(R.string.home_screen_settings_move_only), Toast.LENGTH_SHORT).show();
        buildHomeScreenArrangeUI();
    }

    private void buildHomeScreenEditorUI() {
        setSettingsSubScreen(SettingsScreens.HOME);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();
        final int targetFocusIndex = homeScreenEditorFocusIndex;

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                lastSettingsFocusIndex = homeScreenEditorMenuFocusIndex;
                returnToSettingsParent();
            }
        });
        containerSettingsItems.addView(btnBack);

        createCategoryHeader(getString(R.string.home_screen_shortcuts));

        for (final HomeMenuConfig.Entry entry : HomeMenuConfig.catalog()) {
            String state = entry.required ? getString(R.string.home_screen_move)
                    : stateOnOff(HomeMenuConfig.isShortcutEnabled(prefs, entry.id));
            final LinearLayout row = createSettingRow(RowKeys.homeShortcut(entry.id), entry.labelResId, state);
            if (entry.required) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openHomeArrangeForRequiredShortcut(entry.id,
                                containerSettingsItems.indexOfChild(v));
                    }
                });
            } else {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        homeScreenEditorFocusIndex = containerSettingsItems.indexOfChild(v);
                        boolean nowOn = !HomeMenuConfig.isShortcutEnabled(prefs, entry.id);
                        HomeMenuConfig.setShortcutEnabled(prefs, entry.id, nowOn);
                        buildHomeMenu();
                        buildHomeScreenEditorUI();
                    }
                });
            }
            containerSettingsItems.addView(row);
        }

        LinearLayout btnMoreTile = createSettingRow(RowKeys.HOME_MORE, R.string.home_screen_more,
                stateOnOff(HomeMenuConfig.isMoreEnabled(prefs)));
        btnMoreTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenEditorFocusIndex = containerSettingsItems.indexOfChild(v);
                HomeMenuConfig.setMoreEnabled(prefs, !HomeMenuConfig.isMoreEnabled(prefs));
                buildHomeMenu();
                buildHomeScreenEditorUI();
            }
        });
        containerSettingsItems.addView(btnMoreTile);

        LinearLayout btnArrange = createSettingsRow(RowKeys.HOME_ARRANGE, R.string.home_screen_arrange, true);
        btnArrange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenOrderFocusIndex = 1;
                homeScreenMoveModeId = null;
                buildHomeScreenArrangeUI();
            }
        });
        containerSettingsItems.addView(btnArrange);

        createCategoryHeader(getString(R.string.settings_widgets_section));

        final LinearLayout btnClock = createSettingsRow(RowKeys.WIDGET_CLOCK, R.string.widget_clock, false);
        btnClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenEditorFocusIndex = containerSettingsItems.indexOfChild(v);
                isWidgetClockOn = !isWidgetClockOn;
                try { prefs.edit().putBoolean("widget_clock", isWidgetClockOn).commit(); } catch (Exception e) {}
                refreshWidgets();
                buildHomeScreenEditorUI();
            }
        });
        containerSettingsItems.addView(btnClock);

        final LinearLayout btnBattery = createSettingsRow(RowKeys.WIDGET_BATTERY, R.string.widget_battery, false);
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenEditorFocusIndex = containerSettingsItems.indexOfChild(v);
                isWidgetBatteryOn = !isWidgetBatteryOn;
                try { prefs.edit().putBoolean("widget_battery", isWidgetBatteryOn).commit(); } catch (Exception e) {}
                refreshWidgets();
                buildHomeScreenEditorUI();
            }
        });
        containerSettingsItems.addView(btnBattery);

        final LinearLayout btnAlbum = createSettingsRow(RowKeys.WIDGET_ALBUM, R.string.widget_album, false);
        btnAlbum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                homeScreenEditorFocusIndex = containerSettingsItems.indexOfChild(v);
                isWidgetAlbumOn = !isWidgetAlbumOn;
                try { prefs.edit().putBoolean("widget_album", isWidgetAlbumOn).commit(); } catch (Exception e) {}
                refreshWidgets();
                buildHomeScreenEditorUI();
            }
        });
        containerSettingsItems.addView(btnAlbum);

        restoreHomeScreenEditorFocus(targetFocusIndex);
    }

    private void buildHomeScreenArrangeUI() {
        setSettingsSubScreen(SettingsScreens.HOME_ARRANGE);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        int targetFocusIndex = homeScreenOrderFocusIndex;
        if (homeScreenMoveModeId != null) {
            List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
            int rowIdx = ids.indexOf(homeScreenMoveModeId);
            if (rowIdx >= 0) targetFocusIndex = rowIdx + 1;
        }

        Button btnBack = createListButton(getString(R.string.common_back_short));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (homeScreenMoveModeId != null) {
                    homeScreenMoveModeId = null;
                    refreshHomeArrangeMoveUi(false);
                } else {
                    buildHomeScreenEditorUI();
                }
            }
        });
        containerSettingsItems.addView(btnBack);

        final List<String> orderIds = HomeMenuConfig.loadHomeOrderIds(prefs);
        for (int i = 0; i < orderIds.size(); i++) {
            String id = orderIds.get(i);
            HomeMenuConfig.Entry entry = HomeMenuConfig.find(id);
            if (entry == null) continue;
            boolean moving = id.equals(homeScreenMoveModeId);
            containerSettingsItems.addView(createHomeOrderRow(entry, moving));
        }

        if (HomeMenuConfig.isMoreEnabled(prefs)) {
            LinearLayout moreRow = createSettingsRow(RowKeys.HOME_MORE, R.string.home_menu_more, true);
            moreRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    homeMoreMoveModeId = null;
                    buildHomeMoreArrangeUI();
                }
            });
            containerSettingsItems.addView(moreRow);
        }

        restoreHomeScreenEditorFocus(targetFocusIndex);
    }

    private void buildHomeMoreArrangeUI() {
        setSettingsSubScreen(SettingsScreens.HOME_MORE_ARRANGE);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        int targetFocusIndex = homeScreenOrderFocusIndex;
        if (homeMoreMoveModeId != null) {
            List<String> ids = HomeMenuConfig.loadMoreOrderIds(prefs);
            int rowIdx = ids.indexOf(homeMoreMoveModeId);
            if (rowIdx >= 0) targetFocusIndex = rowIdx + 1;
        }

        Button btnBack = createListButton(getString(R.string.common_back_short));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (homeMoreMoveModeId != null) {
                    homeMoreMoveModeId = null;
                    refreshHomeArrangeMoveUi(true);
                } else {
                    buildHomeScreenArrangeUI();
                }
            }
        });
        containerSettingsItems.addView(btnBack);

        final List<String> orderIds = HomeMenuConfig.loadMoreOrderIds(prefs);
        for (int i = 0; i < orderIds.size(); i++) {
            String id = orderIds.get(i);
            HomeMenuConfig.Entry entry = HomeMenuConfig.find(id);
            if (entry == null) continue;
            boolean moving = id.equals(homeMoreMoveModeId);
            containerSettingsItems.addView(createHomeOrderRow(entry, moving));
        }

        restoreHomeScreenEditorFocus(targetFocusIndex);
    }

    private void buildNowPlayingSettingsUI() {
        setSettingsSubScreen(SettingsScreens.NOW_PLAYING);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                returnToSettingsParent();
            }
        });
        containerSettingsItems.addView(btnBack);

        LinearLayout btnBlur = createSettingsRow(RowKeys.NOW_PLAYING_ALBUM_BLUR,
                R.string.settings_player_album_blur, false);
        btnBlur.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                playerAlbumBlurEnabled = !playerAlbumBlurEnabled;
                prefs.edit().putBoolean(PREF_PLAYER_ALBUM_BLUR, playerAlbumBlurEnabled).commit();
                updateScreenBackground(currentScreenState);
                refreshSettingsPreview(RowKeys.NOW_PLAYING_ALBUM_BLUR);
            }
        });
        containerSettingsItems.addView(btnBlur);

        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }

    private void buildBackgroundSettingsUI() {
        setSettingsSubScreen(SettingsScreens.BACKGROUND);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                lastSettingsFocusIndex = 18;
                returnToSettingsParent();
            }
        });
        containerSettingsItems.addView(btnBack);

        TextView section = new TextView(this);
        section.setText(R.string.bg_section_theme_wallpapers);
        styleSecondaryLabel(section);
        section.setFocusable(false);
        section.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        containerSettingsItems.addView(section);

        for (final ThemeManager.WallpaperPick pick : ThemeManager.listWallpaperPicks()) {
            Button b = createListButton(wallpaperPickLabel(pick));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    applyThemeWallpaperPick(pick);
                    buildBackgroundSettingsUI();
                }
            });
            containerSettingsItems.addView(b);
        }

        String customLabel = getString(R.string.bg_pick_custom_image);
        if (isCustomWallpaperSelected()) customLabel = "✔ " + customLabel;
        Button btnSelectBg = createListButton(customLabel);
        btnSelectBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isPickingBackground = true;
                currentFolder = getStorageRoot();
                currentBrowserMode = BROWSER_FOLDER;
                changeScreen(STATE_BROWSER);
                Toast.makeText(MainActivity.this, getString(R.string.toast_bg_wallpaper_size_hint),
                        Toast.LENGTH_SHORT).show();
            }
        });
        containerSettingsItems.addView(btnSelectBg);

        LinearLayout btnClearBg = createSettingsRow(RowKeys.BG_CLEAR, R.string.settings_clear_background, true);
        btnClearBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (prefs.contains("bg_path") || prefs.contains(PREF_BG_THEME_WALLPAPER)
                        || BG_MODE_CUSTOM.equals(getBackgroundMode())) {
                    prefs.edit()
                            .remove("bg_path")
                            .remove(PREF_BG_THEME_WALLPAPER)
                            .putString("background_mode", BG_MODE_THEME)
                            .commit();
                    Toast.makeText(MainActivity.this, getString(R.string.toast_bg_cleared), Toast.LENGTH_SHORT).show();
                    updateMainMenuBackground();
                    buildBackgroundSettingsUI();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_bg_none), Toast.LENGTH_SHORT).show();
                }
            }
        });
        containerSettingsItems.addView(btnClearBg);

        // 메뉴 진입 시 자동으로 첫 번째 버튼에 포커스!
        if (containerSettingsItems.getChildCount() > 1) {
            containerSettingsItems.getChildAt(1).requestFocus();
        }
    }
    private boolean isAudioFile(File f) {
        if (f == null || !f.isFile())
            return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".ogg")
                || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".ape") || name.endsWith(".wma");
    }

    private boolean isApkFile(File f) {
        if (f == null || !f.isFile())
            return false;
        return f.getName().toLowerCase().endsWith(".apk");
    }

    private boolean isImageFile(File f) {
        if (f == null || !f.isFile())
            return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    // 💡 하위 폴더까지 뒤져서 음악 파일의 '경로'만 모두 수집해 오는 함수
    private void collectAudioFiles(File file, List<String> paths) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    collectAudioFiles(f, paths); // 폴더면 파고들기
                }
            }
        } else if (isAudioFile(file)) {
            paths.add(file.getAbsolutePath()); // 음악 파일이면 명단에 추가!
        }
    }

    // 💡 1. 안드로이드 스캐너를 버리고, 앱이 직접 MP3 태그를 추출하여 분류하는 함수!
    // ponytail: dedupe by path; secondary by title+artist+duration hash — may collapse distinct files
    private final java.util.HashSet<String> libraryScanPaths = new java.util.HashSet<String>();
    private final java.util.HashSet<String> libraryScanMetaKeys = new java.util.HashSet<String>();

    private void buildCustomLibrary(File folder) {
        if (libraryScanGen != activeLibraryScanGen) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    buildCustomLibrary(f);
                } else if (isAudioFile(f)) {
                    if (blacklist.contains(f.getAbsolutePath())) continue;
                    String normPath = f.getAbsolutePath().toLowerCase(java.util.Locale.US);
                    if (!libraryScanPaths.add(normPath)) continue;
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        java.io.FileInputStream fis = new java.io.FileInputStream(f);
                        mmr.setDataSource(fis.getFD());

                        String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                        String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                        String genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                        String duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

                        if (title == null || title.isEmpty()) title = f.getName();
                        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";
                        if (album == null || album.isEmpty()) album = "Unknown Album";

                        String metaKey = (title + "\0" + artist + "\0" + duration).toLowerCase(java.util.Locale.US);
                        if (!libraryScanMetaKeys.add(metaKey)) {
                            fis.close();
                            mmr.release();
                            continue;
                        }

                        customLibrary.add(new SongItem(f, title, artist, album, genre));

                        fis.close();
                        mmr.release();
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    // 💡 2. 라이브러리 메인 라우터 (자체 스캔 버튼 적용)
    private void updateLibraryBreadcrumb() {
        if (tvBrowserPath == null || currentScreenState != STATE_BROWSER) return;
        tvBrowserPath.setText(buildLibraryBreadcrumb());
        tvBrowserPath.setVisibility(View.VISIBLE);
    }

    private void refreshBrowserPathBreadcrumb() {
        if (tvBrowserPath == null) return;
        if (currentScreenState == STATE_BROWSER) {
            updateLibraryBreadcrumb();
        } else if (currentScreenState == STATE_PODCASTS || currentScreenState == STATE_SOULSEEK) {
            tvBrowserPath.setVisibility(View.VISIBLE);
        } else if (currentScreenState == STATE_APPS) {
            tvBrowserPath.setText(getString(R.string.path_apps));
            tvBrowserPath.setVisibility(View.VISIBLE);
        } else {
            tvBrowserPath.setVisibility(View.GONE);
        }
    }

    private String buildLibraryBreadcrumb() {
        if (isPickingBackground) {
            String pathLabel = currentFolder.getAbsolutePath().replace("/storage/sdcard0", "");
            if (pathLabel.isEmpty()) pathLabel = "/";
            return getString(R.string.path_library_pick_image, pathLabel);
        }
        switch (currentBrowserMode) {
            case BROWSER_FOLDER: {
                String pathLabel = currentFolder.getAbsolutePath().replace("/storage/sdcard0", "");
                if (pathLabel.isEmpty()) pathLabel = "/";
                return getString(R.string.path_library_folders, pathLabel);
            }
            case BROWSER_ARTISTS:
                return getString(R.string.path_library_artists);
            case BROWSER_ARTIST_ALBUMS:
                return getString(R.string.path_library_artist_albums, virtualQueryArtist);
            case BROWSER_ALBUMS:
                return getString(R.string.path_library_albums);
            case BROWSER_GENRES:
                return getString(R.string.path_library_genres);
            case BROWSER_PLAYLISTS:
                return getString(R.string.path_library_playlists);
            case BROWSER_VIRTUAL_SONGS:
                if ("ALL".equals(virtualQueryType)) return getString(R.string.path_library_all_songs);
                if ("ARTIST_ALBUM".equals(virtualQueryType)) {
                    return getString(R.string.path_library_artist_album_tracks, virtualQueryArtist, virtualQueryValue);
                }
                if ("ARTIST".equals(virtualQueryType)) return getString(R.string.path_library_artist, virtualQueryValue);
                if ("ALBUM".equals(virtualQueryType)) return getString(R.string.path_library_album, virtualQueryValue);
                if ("GENRE".equals(virtualQueryType)) return getString(R.string.path_library_genre, virtualQueryValue);
                if ("PLAYLIST".equals(virtualQueryType)) return getString(R.string.path_library_playlist, virtualQueryValue);
                return getString(R.string.path_library_section, virtualQueryValue);
            default:
                return getString(R.string.path_library_root);
        }
    }

    private String librarySortLabel() {
        switch (librarySortMode) {
            case LIB_SORT_ARTIST: return getString(R.string.library_sort_artist);
            case LIB_SORT_ALBUM: return getString(R.string.library_sort_album);
            case LIB_SORT_DATE: return getString(R.string.library_sort_date);
            default: return getString(R.string.library_sort_title);
        }
    }

    private void cycleLibrarySort() {
        librarySortMode = (librarySortMode + 1) % 4;
        Toast.makeText(this, getString(R.string.library_sort_now, librarySortLabel()), Toast.LENGTH_SHORT).show();
        if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) buildVirtualSongs();
    }

    private void sortSongItems(List<SongItem> list) {
        java.util.Collections.sort(list, new java.util.Comparator<SongItem>() {
            @Override
            public int compare(SongItem a, SongItem b) {
                switch (librarySortMode) {
                    case LIB_SORT_ARTIST:
                        int c = a.artist.compareToIgnoreCase(b.artist);
                        return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
                    case LIB_SORT_ALBUM:
                        c = a.album.compareToIgnoreCase(b.album);
                        return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
                    case LIB_SORT_DATE:
                        long da = a.file.lastModified();
                        long db = b.file.lastModified();
                        return da == db ? a.title.compareToIgnoreCase(b.title) : Long.compare(db, da);
                    default:
                        return a.title.compareToIgnoreCase(b.title);
                }
            }
        });
    }

    private void buildMoreMenuUI() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();
        browserStatusTitle = getString(R.string.path_more);
        updateStatusBarTitle();
        if (tvBrowserPath != null) {
            tvBrowserPath.setText(getString(R.string.path_more));
            tvBrowserPath.setVisibility(View.VISIBLE);
        }

        Button btnBack = createListButton(getString(R.string.common_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                changeScreen(STATE_MENU);
            }
        });
        containerBrowserItems.addView(btnBack);

        final boolean online = ConnectivityHelper.isOnline(this);
        final boolean onLan = ConnectivityHelper.hasLocalNetwork(this);
        List<HomeMenuConfig.Entry> items = HomeMenuConfig.loadMoreVisible(prefs, online, onLan);
        if (items.isEmpty()) {
            Button empty = createListButton(getString(R.string.more_menu_empty));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (final HomeMenuConfig.Entry entry : items) {
                Button row = createListButton(getString(entry.labelResId));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        onHomeMenuActivate(entry.id);
                    }
                });
                containerBrowserItems.addView(row);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) {
            containerBrowserItems.getChildAt(0).requestFocus();
        }
    }

    private void buildAppsLauncherUI() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();
        browserStatusTitle = getString(R.string.status_apps);
        updateStatusBarTitle();
        if (tvBrowserPath != null) {
            tvBrowserPath.setText(getString(R.string.path_apps));
            tvBrowserPath.setVisibility(View.VISIBLE);
        }

        Button btnBack = createListButton(getString(R.string.common_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                changeScreen(STATE_MENU);
            }
        });
        containerBrowserItems.addView(btnBack);

        Button loading = createListButton(getString(R.string.apps_loading));
        loading.setEnabled(false);
        containerBrowserItems.addView(loading);

        applyPodcastBrowserLayout();
        final int gen = ++appsListGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppLauncher.Entry> apps = AppLauncher.load(
                        getPackageManager(), getPackageName());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentScreenState != STATE_APPS || gen != appsListGen) return;
                        populateAppsLauncherList(apps);
                    }
                });
            }
        }).start();
    }

    private void populateAppsLauncherList(final List<AppLauncher.Entry> apps) {
        containerBrowserItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                changeScreen(STATE_MENU);
            }
        });
        containerBrowserItems.addView(btnBack);

        if (apps == null || apps.isEmpty()) {
            Button empty = createListButton(getString(R.string.apps_empty));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
            clearPodcastPreviewPane();
        } else {
            currentScrollIndexList.clear();
            for (final AppLauncher.Entry app : apps) {
                currentScrollIndexList.add(app.label);
                containerBrowserItems.addView(createAppLauncherRow(app));
            }
        }
        applyPodcastBrowserLayout();
        if (containerBrowserItems.getChildCount() > 1) {
            containerBrowserItems.getChildAt(1).requestFocus();
            if (apps != null && !apps.isEmpty()) {
                updateAppsPreview(apps.get(0));
            }
        }
    }

    private void buildFileBrowserUI() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE); if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();

        if (isPickingBackground || currentBrowserMode == BROWSER_FOLDER) {
            buildFolderBrowserUI();
            return;
        }

        if (currentBrowserMode == BROWSER_ROOT) {
            browserStatusTitle = getString(R.string.status_library_main);
            updateStatusBarTitle();
            updateLibraryBreadcrumb();

            Button btnFolder = createListButton(getString(R.string.browser_folders));
            btnFolder.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_FOLDER;
                buildFileBrowserUI();
            });
            containerBrowserItems.addView(btnFolder);

            Button btnArtist = createListButton(getString(R.string.browser_artists));
            btnArtist.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_ARTISTS;
                buildVirtualCategories("ARTIST");
            });
            containerBrowserItems.addView(btnArtist);

            Button btnAlbum = createListButton(getString(R.string.browser_albums));
            btnAlbum.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_ALBUMS;
                buildVirtualCategories("ALBUM");
            });
            containerBrowserItems.addView(btnAlbum);

            Button btnAll = createListButton(getString(R.string.browser_all_songs));
            btnAll.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                virtualQueryType = "ALL";
                buildVirtualSongs();
            });
            containerBrowserItems.addView(btnAll);

            Button btnGenres = createListButton(getString(R.string.browser_genres));
            btnGenres.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_GENRES;
                buildVirtualCategories("GENRE");
            });
            containerBrowserItems.addView(btnGenres);

            Button btnPlaylists = createListButton(getString(R.string.browser_playlists));
            btnPlaylists.setOnClickListener(v -> {
                clickFeedback();
                currentBrowserMode = BROWSER_PLAYLISTS;
                buildPlaylistsUI();
            });
            containerBrowserItems.addView(btnPlaylists);

            // 🚀 시스템을 거치지 않는 '앱 자체 스캔 엔진' 버튼!
            Button btnScan = createListButton(isCustomScanning ? "⏳ Scanning Media..." : "🔄 Scan Media Library");
            btnScan.setTextColor(isCustomScanning ? 0xFF000000 : 0xFFFFFFFF);
            btnScan.setOnClickListener(v -> {
                clickFeedback();
                if (isCustomScanning)
                    return;

                isCustomScanning = true;
                btnScan.setText(getString(R.string.browser_scanning));
                btnScan.setTextColor(0xFF000000);
                final int gen = libraryScanGen;
                activeLibraryScanGen = gen;

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        customLibrary.clear();
                        buildCustomLibrary(rootFolder);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                isCustomScanning = false;
                                Toast.makeText(MainActivity.this,
                                                "Scan Complete! " + customLibrary.size() + " songs found.", Toast.LENGTH_SHORT)
                                        .show();
                                if (currentScreenState == STATE_BROWSER) {
                                    if (currentBrowserMode == BROWSER_ROOT) {
                                        buildFileBrowserUI();
                                    } else if (currentBrowserMode == BROWSER_ARTISTS) {
                                        buildVirtualCategories("ARTIST");
                                    } else if (currentBrowserMode == BROWSER_ALBUMS) {
                                        buildVirtualCategories("ALBUM");
                                    } else if (currentBrowserMode == BROWSER_GENRES) {
                                        buildVirtualCategories("GENRE");
                                    } else if (currentBrowserMode == BROWSER_ARTIST_ALBUMS) {
                                        buildArtistAlbums();
                                    } else if (currentBrowserMode == BROWSER_PLAYLISTS) {
                                        buildPlaylistsUI();
                                    } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                                        buildVirtualSongs();
                                    }
                                }
                            }
                        });
                    }
                }).start();
            });
            containerBrowserItems.addView(btnScan);
            if (hasInternetConnection()) {
                Button btnGetMore = createListButton(getString(R.string.browser_get_more));
                btnGetMore.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        openReachFromLibrary();
                    }
                });
                containerBrowserItems.addView(btnGetMore);
            }
            if (containerBrowserItems.getChildCount() > 0)
                containerBrowserItems.getChildAt(0).requestFocus();
        }
    }

    // 💡 3. 자체 DB에서 아티스트/앨범 카테고리 추출 (초고속 엔진 적용!)
    private void buildVirtualCategories(final String type) {
        if (isCustomScanning) {
            showLoadingPopup(); // 🚀 스캔 중이라면 멋진 로딩창 띄우기!
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }

        // 🚀 카테고리 탭도 느린 스크롤뷰를 끄고, 초고속 리스트뷰를 켭니다!
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);

        browserStatusTitle = "ARTIST".equals(type) ? getString(R.string.status_library_artists)
                : ("GENRE".equals(type) ? getString(R.string.status_library_genres) : getString(R.string.status_library_albums));
        updateStatusBarTitle();
        updateLibraryBreadcrumb();

        java.util.HashSet<String> uniqueCategories = new java.util.HashSet<>();
        for (SongItem song : customLibrary) {
            String val;
            if ("ARTIST".equals(type)) val = song.artist;
            else if ("GENRE".equals(type)) val = song.genre;
            else val = song.album;
            uniqueCategories.add(val);
        }

        List<String> categories = new ArrayList<>(uniqueCategories);
        java.util.Collections.sort(categories);
// 🚀 [추가] 점프를 위해 아티스트/앨범 이름 기억
        currentScrollIndexList.clear();
        currentScrollIndexList.addAll(categories);
        // 🚀 수백 개의 아티스트/앨범 데이터도 재활용 엔진(어댑터)에 밀어넣습니다.
        CategoryListAdapter adapter = new CategoryListAdapter(categories, type);
        listVirtualSongs.setAdapter(adapter);

        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) {
                    listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        });
    }
    // 💡 [추가] 이름에서 앞의 특수문자를 무시하고 순수 '첫 글자(알파벳)'만 뽑아내는 함수
    private char getInitialChar(String text) {
        if (text == null || text.isEmpty()) return '#';
        String clean = text.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "").trim().toUpperCase();
        if (clean.isEmpty()) return '#';
        return clean.charAt(0);
    }
    // 💡 [추가] 아티스트/앨범 리스트 전용 10개 돌려막기 어댑터!
    private class CategoryListAdapter extends android.widget.BaseAdapter {
        private List<String> items;
        private String type;

        public CategoryListAdapter(List<String> items, String type) {
            this.items = items;
            this.type = type;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, android.view.ViewGroup parent) {
            final Button btn;

            if (convertView == null) {
                btn = createListButton("");
                btn.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                        android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                ));
            } else {
                btn = (Button) convertView;
            }

            final String name = items.get(position);
            String prefix = "👤 ";
            if ("ALBUM".equals(type) || "ARTIST_ALBUM".equals(type)) prefix = "💿 ";
            else if ("GENRE".equals(type)) prefix = "🎸 ";
            btn.setText(prefix + name);

            final int rowKind = Y1_ROW_ITEM;
            final int rowW = y1ActiveRowWidthPx();
            btn.setBackground(getY1RowBackground(false, rowW, rowKind));
            ThemeManager.applyThemedTextStyle(btn, y1RowTextColorNormal(rowKind));
            btn.setSelected(false);
            btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    btn.setBackground(getY1RowBackground(hasFocus, rowW, rowKind));
                    ThemeManager.applyThemedTextStyle(btn, hasFocus
                            ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                    btn.setSelected(hasFocus);
                    if (hasFocus) showFastScrollLetter(name);
                }
            });

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if ("ARTIST".equals(type)) {
                        virtualQueryArtist = name;
                        currentBrowserMode = BROWSER_ARTIST_ALBUMS;
                        buildArtistAlbums();
                    } else if ("ARTIST_ALBUM".equals(type)) {
                        virtualQueryType = "ARTIST_ALBUM";
                        virtualQueryValue = name;
                        currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                        buildVirtualSongs();
                    } else {
                        virtualQueryType = type;
                        virtualQueryValue = name;
                        currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                        buildVirtualSongs();
                    }
                }
            });

            return btn;
        }
    }

    private void buildArtistAlbums() {
        if (isCustomScanning) {
            showLoadingPopup();
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);
        browserStatusTitle = getString(R.string.status_path, virtualQueryArtist);
        updateStatusBarTitle();
        updateLibraryBreadcrumb();

        java.util.HashSet<String> albums = new java.util.HashSet<String>();
        for (SongItem song : customLibrary) {
            if (song.artist.equals(virtualQueryArtist)) albums.add(song.album);
        }
        List<String> categories = new ArrayList<>(albums);
        java.util.Collections.sort(categories);
        currentScrollIndexList.clear();
        currentScrollIndexList.addAll(categories);
        CategoryListAdapter adapter = new CategoryListAdapter(categories, "ARTIST_ALBUM");
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) listVirtualSongs.getChildAt(0).requestFocus();
            }
        });
    }

    private void buildPlaylistsUI() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();
        browserStatusTitle = getString(R.string.status_library_playlists);
        updateStatusBarTitle();
        updateLibraryBreadcrumb();

        Button back = createListButton("〈 " + getString(R.string.browser_folders).replace("📁 ", ""));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                currentBrowserMode = BROWSER_ROOT;
                buildFileBrowserUI();
            }
        });
        containerBrowserItems.addView(back);

        libraryPlaylists = PlaylistManager.scan(rootFolder);
        if (libraryPlaylists.isEmpty()) {
            Button empty = createListButton(getString(R.string.library_playlists_empty));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (final PlaylistManager.Entry pl : libraryPlaylists) {
                Button b = createListButton("📋 " + pl.name + " (" + pl.tracks.size() + ")");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        virtualQueryType = "PLAYLIST";
                        virtualQueryValue = pl.name;
                        buildVirtualSongsFromPlaylist(pl);
                    }
                });
                containerBrowserItems.addView(b);
            }
        }
        Button saveQueue = createListButton(getString(R.string.library_save_queue_m3u));
        saveQueue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                saveMusicQueueAsM3u();
            }
        });
        containerBrowserItems.addView(saveQueue);
        if (containerBrowserItems.getChildCount() > 1) containerBrowserItems.getChildAt(1).requestFocus();
    }

    private void buildVirtualSongsFromPlaylist(final PlaylistManager.Entry pl) {
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);
        virtualQueryType = "PLAYLIST";
        virtualQueryValue = pl.name;
        browserStatusTitle = getString(R.string.status_path, pl.name);
        updateStatusBarTitle();
        updateLibraryBreadcrumb();
        virtualSongList.clear();
        virtualSongList.addAll(pl.tracks);
        currentScrollIndexList.clear();
        final List<SongItem> targetSongs = new ArrayList<SongItem>();
        for (File f : pl.tracks) {
            SongItem si = findSongItem(f);
            if (si != null) {
                targetSongs.add(si);
                currentScrollIndexList.add(si.title);
            } else {
                targetSongs.add(new SongItem(f, f.getName(), "", "", ""));
                currentScrollIndexList.add(f.getName());
            }
        }
        sortSongItems(targetSongs);
        virtualSongList.clear();
        for (SongItem s : targetSongs) virtualSongList.add(s.file);
        SongListAdapter adapter = new SongListAdapter(targetSongs);
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) listVirtualSongs.getChildAt(0).requestFocus();
            }
        });
    }

    private SongItem findSongItem(File f) {
        if (f == null) return null;
        for (SongItem s : customLibrary) {
            if (f.equals(s.file)) return s;
        }
        return null;
    }

    private boolean isPodcastMediaFile(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        String root = PodcastLibrary.ROOT.getAbsolutePath();
        return p.equals(root) || p.startsWith(root + File.separator);
    }

    private boolean isMusicBrowseContextFile(File f) {
        return f != null && f.isFile() && isAudioFile(f) && !isPodcastMediaFile(f)
                && !isReachTempFile(f);
    }

    private SongItem resolveSongMetadata(File f) {
        SongItem si = findSongItem(f);
        if (si != null) return si;
        if (!isMusicBrowseContextFile(f)) return null;
        String path = f.getAbsolutePath();
        String title = f.getName();
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        String artist = "";
        String album = "";
        String genre = "Unknown Genre";
        try {
            title = prefs.getString("meta_title_" + path, title);
            artist = prefs.getString("meta_artist_" + path, artist);
        } catch (Exception ignored) {}
        if (artist.isEmpty()) artist = "Unknown Artist";
        if (album.isEmpty()) album = "Unknown Album";
        return new SongItem(f, title, artist, album, genre);
    }

    private boolean isOnSameMusicListing(String type, String value, String artistForAlbum) {
        if (currentScreenState != STATE_BROWSER || currentBrowserMode != BROWSER_VIRTUAL_SONGS) return false;
        if (type == null || !type.equals(virtualQueryType)) return false;
        if (value == null || !value.equals(virtualQueryValue)) return false;
        if ("ARTIST_ALBUM".equals(type)) {
            return (artistForAlbum != null ? artistForAlbum : "").equals(virtualQueryArtist);
        }
        return true;
    }

    private void openMusicArtistListing(final String artist) {
        if (artist == null || artist.trim().isEmpty()) return;
        changeScreen(STATE_BROWSER);
        currentBrowserMode = BROWSER_VIRTUAL_SONGS;
        virtualQueryType = "ARTIST";
        virtualQueryValue = artist;
        virtualQueryArtist = "";
        buildVirtualSongs();
    }

    private void openMusicAlbumListing(final String artist, final String album) {
        if (album == null || album.trim().isEmpty()) return;
        changeScreen(STATE_BROWSER);
        currentBrowserMode = BROWSER_VIRTUAL_SONGS;
        if (artist != null && !artist.trim().isEmpty() && !"Unknown Artist".equals(artist)) {
            virtualQueryType = "ARTIST_ALBUM";
            virtualQueryValue = album;
            virtualQueryArtist = artist;
        } else {
            virtualQueryType = "ALBUM";
            virtualQueryValue = album;
            virtualQueryArtist = "";
        }
        buildVirtualSongs();
    }

    private void openMusicGenreListing(final String genre) {
        if (genre == null || genre.trim().isEmpty() || "Unknown Genre".equals(genre)) return;
        changeScreen(STATE_BROWSER);
        currentBrowserMode = BROWSER_VIRTUAL_SONGS;
        virtualQueryType = "GENRE";
        virtualQueryValue = genre;
        virtualQueryArtist = "";
        buildVirtualSongs();
    }

    private void openMusicPlaylistListing(final String name) {
        if (name == null || name.trim().isEmpty()) return;
        java.util.List<PlaylistManager.Entry> lists = libraryPlaylists;
        if (lists == null || lists.isEmpty()) lists = PlaylistManager.scan(rootFolder);
        for (PlaylistManager.Entry pl : lists) {
            if (name.equals(pl.name)) {
                changeScreen(STATE_BROWSER);
                currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                buildVirtualSongsFromPlaylist(pl);
                return;
            }
        }
    }

    private File focusedMusicTrackForContext() {
        if (currentScreenState == STATE_PLAYER && playback.isMusicActive()) {
            java.util.List<File> q = playback.musicPlaylist();
            int i = playback.musicIndex();
            if (i >= 0 && i < q.size()) return q.get(i);
        }
        if (currentScreenState == STATE_BROWSER) {
            if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) return virtualFocusedAudioFile();
            if (currentBrowserMode == BROWSER_FOLDER && !isPickingBackground) return browserFocusedAudioFile();
        }
        if (isMusicQueueEditorScreen()) {
            int idx = musicQueueFocusedIndex();
            java.util.List<File> q = playback.musicPlaylist();
            if (idx >= 0 && idx < q.size()) return q.get(idx);
        }
        return null;
    }

    private void addMusicBrowseContextActions(File trackFile, String playlistNameIfAny) {
        if (!isMusicBrowseContextFile(trackFile)) return;
        final SongItem si = resolveSongMetadata(trackFile);
        if (si == null) return;

        if (si.artist != null && !si.artist.trim().isEmpty() && !"Unknown Artist".equals(si.artist)
                && !isOnSameMusicListing("ARTIST", si.artist, null)) {
            addContextAction(getString(R.string.context_browse_artist, si.artist), new Runnable() {
                @Override
                public void run() {
                    openMusicArtistListing(si.artist);
                }
            });
        }
        if (si.album != null && !si.album.trim().isEmpty() && !"Unknown Album".equals(si.album)) {
            final String listingType = (si.artist != null && !si.artist.trim().isEmpty()
                    && !"Unknown Artist".equals(si.artist)) ? "ARTIST_ALBUM" : "ALBUM";
            if (!isOnSameMusicListing(listingType, si.album, si.artist)) {
                addContextAction(getString(R.string.context_browse_album, si.album), new Runnable() {
                    @Override
                    public void run() {
                        openMusicAlbumListing(si.artist, si.album);
                    }
                });
            }
        }
        if (si.genre != null && !si.genre.trim().isEmpty() && !"Unknown Genre".equals(si.genre)
                && !isOnSameMusicListing("GENRE", si.genre, null)) {
            addContextAction(getString(R.string.context_browse_genre, si.genre), new Runnable() {
                @Override
                public void run() {
                    openMusicGenreListing(si.genre);
                }
            });
        }
        if (playlistNameIfAny != null && !playlistNameIfAny.isEmpty()
                && !isOnSameMusicListing("PLAYLIST", playlistNameIfAny, null)) {
            addContextAction(getString(R.string.context_browse_playlist, playlistNameIfAny), new Runnable() {
                @Override
                public void run() {
                    openMusicPlaylistListing(playlistNameIfAny);
                }
            });
        }
        if (ConnectivityHelper.isOnline(this) && ConnectivityHelper.isReachLoginOk()) {
            List<String> findLike = SoulseekSearchSuggestions.suggestionsFromId3(
                    si.title, si.artist, si.album, si.genre);
            if (!findLike.isEmpty()) {
                addContextSectionHeader(getString(R.string.context_find_like_this));
                int shown = 0;
                for (final String q : findLike) {
                    if (shown >= 4) break;
                    addContextAction(q, new Runnable() {
                        @Override
                        public void run() {
                            if (requireInternet(R.string.soulseek_wifi_required)) {
                                if (ConnectivityHelper.shouldShowHomeShortcut(MainActivity.this,
                                        HomeMenuConfig.ID_SOULSEEK)) {
                                    fetchSoulseekResults(q);
                                    openSoulseekScreen();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            getString(R.string.soulseek_wifi_required), Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    });
                    shown++;
                }
            }
        }
    }

    private void saveMusicQueueAsM3u() {
        if (playback.musicPlaylist().isEmpty()) {
            Toast.makeText(this, getString(R.string.library_queue_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = PlaylistManager.playlistsDir(rootFolder);
            if (!dir.exists()) dir.mkdirs();
            String name = "Queue " + System.currentTimeMillis();
            File dest = new File(dir, name.replace(' ', '_') + ".m3u");
            PlaylistManager.saveM3u(PlaylistManager.fromTracks(name, playback.musicPlaylist()), dest);
            Toast.makeText(this, getString(R.string.library_playlist_saved, dest.getName()), Toast.LENGTH_SHORT).show();
            if (currentBrowserMode == BROWSER_PLAYLISTS) buildPlaylistsUI();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.library_playlist_save_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isMusicQueueEditorScreen() {
        return SettingsScreens.MUSIC_QUEUE.equals(settingsSubScreenKey);
    }

    private void openMusicQueueEditor() {
        if (!playback.isMusicActive() || playback.musicPlaylist().isEmpty()) {
            Toast.makeText(this, getString(R.string.library_queue_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        playback.clampMusicIndex();
        musicQueueMoveFrom = -1;
        musicQueueEditorFocus = Math.min(playback.musicIndex() + 1, playback.musicPlaylist().size());
        musicQueueReturnScreen = currentScreenState == STATE_PLAYER ? STATE_PLAYER : STATE_MENU;
        setSettingsSubScreen(SettingsScreens.MUSIC_QUEUE);
        changeScreen(STATE_SETTINGS);
    }

    private void setMusicQueueListVisible(boolean visible) {
        if (visible) {
            setThemesListVisible(false);
        }
        if (settingsScrollView != null) {
            settingsScrollView.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        if (listMusicQueue != null) {
            listMusicQueue.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) {
            musicQueueMoveFrom = -1;
        }
    }

    private boolean isMusicQueueNowPlayingSlot(int queueIdx) {
        return queueIdx >= 0 && queueIdx == playback.musicIndex();
    }

    private boolean canPickMusicQueueMoveFrom(int queueIdx) {
        return queueIdx >= 0 && queueIdx < playback.musicPlaylist().size()
                && !isMusicQueueNowPlayingSlot(queueIdx);
    }

    private boolean canDropMusicQueueMoveAt(int queueIdx) {
        return queueIdx >= 0 && queueIdx < playback.musicPlaylist().size()
                && !isMusicQueueNowPlayingSlot(queueIdx);
    }

    private int nextMusicQueueMoveIndex(int from, int delta) {
        int size = playback.musicPlaylist().size();
        int to = from + delta;
        while (to >= 0 && to < size && isMusicQueueNowPlayingSlot(to)) {
            to += delta;
        }
        if (to < 0 || to >= size) return from;
        return to;
    }

    private void applyMusicQueueMove(int from, int to) {
        if (!canPickMusicQueueMoveFrom(from) || !canDropMusicQueueMoveAt(to) || from == to) return;
        playback.moveMusicTrack(from, to);
        musicQueueMoveFrom = to;
        musicQueueEditorFocus = to + 1;
        updateMusicTrackCountUi();
        refreshMusicQueueList();
    }

    private int musicQueueListPosition() {
        if (listMusicQueue == null) return -1;
        View focused = listMusicQueue.getFocusedChild();
        if (focused != null) {
            return listMusicQueue.getPositionForView(focused);
        }
        return listMusicQueue.getSelectedItemPosition();
    }

    private int musicQueueFocusedIndex() {
        int listPos = musicQueueListPosition();
        if (listPos <= 0) return -1;
        int idx = listPos - 1;
        return idx < playback.musicPlaylist().size() ? idx : -1;
    }

    private void refreshMusicQueueList() {
        refreshMusicQueueListSoft();
    }

    private void refreshMusicQueueListSoft() {
        if (listMusicQueue == null || musicQueueListAdapter == null) return;
        final int firstVisible = listMusicQueue.getFirstVisiblePosition();
        musicQueueListAdapter.notifyDataSetChanged();
        listMusicQueue.post(new Runnable() {
            @Override
            public void run() {
                if (listMusicQueue == null) return;
                int target = Math.max(1, musicQueueEditorFocus);
                int size = playback.musicPlaylist().size();
                if (target > size) target = size;
                int first = listMusicQueue.getFirstVisiblePosition();
                int last = listMusicQueue.getLastVisiblePosition();
                if (target < first || target > last) {
                    int offset = target - firstVisible;
                    listMusicQueue.setSelectionFromTop(Math.max(1, offset + 1), 0);
                }
                int childIdx = target - listMusicQueue.getFirstVisiblePosition();
                if (childIdx >= 0 && childIdx < listMusicQueue.getChildCount()) {
                    View v = listMusicQueue.getChildAt(childIdx);
                    if (v != null && v.isFocusable()) v.requestFocus();
                }
            }
        });
    }

    private void scrollMusicQueueToListPos(final int listPos) {
        if (listMusicQueue == null || listPos < 0) return;
        musicQueueEditorFocus = listPos;
        refreshMusicQueueListSoft();
    }

    private void buildMusicQueueEditorUI() {
        if (!playback.isMusicActive() || playback.musicPlaylist().isEmpty()) {
            settingsSubScreenKey = null;
            setMusicQueueListVisible(false);
            buildSettingsUI();
            return;
        }
        playback.clampMusicIndex();
        setSettingsSubScreen(SettingsScreens.MUSIC_QUEUE);
        updateStatusBarTitle();
        setThemesListVisible(false);
        setMusicQueueListVisible(true);
        containerSettingsItems.removeAllViews();
        if (listMusicQueue == null) return;
        if (musicQueueListAdapter == null) {
            musicQueueListAdapter = new MusicQueueListAdapter();
        }
        listMusicQueue.setAdapter(musicQueueListAdapter);
        int focusListPos = musicQueueEditorFocus > 0 ? musicQueueEditorFocus
                : playback.musicIndex() + 1;
        focusListPos = Math.min(focusListPos, playback.musicPlaylist().size());
        scrollMusicQueueToListPos(focusListPos);
    }

    private BluetoothDevice bluetoothFocusedDevice() {
        View c = getCurrentFocus();
        if (c != null && c.getTag() instanceof BluetoothDevice) {
            return (BluetoothDevice) c.getTag();
        }
        return null;
    }

    private String wifiFocusedSsid() {
        View c = getCurrentFocus();
        if (c != null && c.getTag() instanceof String) {
            return (String) c.getTag();
        }
        return null;
    }

    private boolean isWifiNetworkSaved(String ssid) {
        if (ssid == null) return false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            List<WifiConfiguration> configs = wm != null ? wm.getConfiguredNetworks() : null;
            if (configs == null) return false;
            for (WifiConfiguration conf : configs) {
                if (conf.SSID != null && conf.SSID.equals("\"" + ssid + "\"")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void forgetWifiNetwork(String ssid) {
        if (ssid == null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            List<WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (configs == null) return;
            for (WifiConfiguration conf : configs) {
                if (conf.SSID != null && conf.SSID.equals("\"" + ssid + "\"")) {
                    wm.removeNetwork(conf.networkId);
                    wm.saveConfiguration();
                    Toast.makeText(this, getString(R.string.toast_wifi_forgotten, ssid), Toast.LENGTH_SHORT).show();
                    if (currentScreenState == STATE_WIFI) startWifiScan();
                    return;
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_wifi_forget_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void forgetBluetoothDevice(final BluetoothDevice device) {
        if (device == null) return;
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            Method removeBond = device.getClass().getMethod("removeBond");
            removeBond.invoke(device);
            Toast.makeText(this, getString(R.string.toast_bt_forgotten,
                    device.getName() != null ? device.getName() : device.getAddress()),
                    Toast.LENGTH_SHORT).show();
            if (currentScreenState == STATE_BLUETOOTH) startBluetoothScan();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_bt_forget_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleMusicQueueEditorBack() {
        if (!isMusicQueueEditorScreen()) return false;
        if (musicQueueMoveFrom >= 0) {
            musicQueueMoveFrom = -1;
            refreshMusicQueueList();
            return true;
        }
        setMusicQueueListVisible(false);
        changeScreen(musicQueueReturnScreen);
        return true;
    }

    private String musicTrackLabel(File f) {
        if (f == null) return "";
        SongItem si = findSongItem(f);
        if (si != null) return si.title + " · " + si.artist;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // 💡 4. 자체 DB에서 노래를 뽑아 '재활용 엔진'에 밀어넣는 함수
    private void buildVirtualSongs() {
        if (isCustomScanning) {
            showLoadingPopup(); // 🚀 잘 안보이는 텍스트 대신, 대형 스피너 팝업을 띄웁니다!
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }
        // 🚀 기존의 뚱뚱하고 느린 스크롤뷰를 끄고, 초고속 리스트뷰를 켭니다!
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);

        browserStatusTitle = virtualQueryType.equals("ALL") ? getString(R.string.status_library_all_songs) : getString(R.string.status_path, virtualQueryValue);
        updateStatusBarTitle();
        updateLibraryBreadcrumb();

        virtualSongList.clear();
        currentScrollIndexList.clear();
        final List<SongItem> targetSongs = new ArrayList<>();
        for (SongItem song : customLibrary) {
            boolean match = false;
            if (virtualQueryType.equals("ALL")) match = true;
            else if (virtualQueryType.equals("ARTIST") && song.artist.equals(virtualQueryValue)) match = true;
            else if (virtualQueryType.equals("ALBUM") && song.album.equals(virtualQueryValue)) match = true;
            else if (virtualQueryType.equals("GENRE") && song.genre.equals(virtualQueryValue)) match = true;
            else if (virtualQueryType.equals("ARTIST_ALBUM")
                    && song.artist.equals(virtualQueryArtist) && song.album.equals(virtualQueryValue)) match = true;
            if (match) {
                targetSongs.add(song);
                virtualSongList.add(song.file);
                currentScrollIndexList.add(song.title);
            }
        }
        sortSongItems(targetSongs);
        virtualSongList.clear();
        for (SongItem s : targetSongs) virtualSongList.add(s.file);
        SongListAdapter adapter = new SongListAdapter(targetSongs);
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) {
                    listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        });
    }
    private void buildFolderBrowserUI() {
        folderBrowserEntries.clear();
        currentScrollIndexList.clear();
        String pathLabel = currentFolder.getAbsolutePath().replace("/storage/sdcard0", "");
        if (pathLabel.isEmpty()) pathLabel = "/";
        browserStatusTitle = getString(R.string.status_path, pathLabel);
        updateStatusBarTitle();
        updateLibraryBreadcrumb();
        File[] files = currentFolder.listFiles();

        final File storageRoot = getStorageRoot();
        boolean showUp = isPickingBackground
                ? !currentFolder.getAbsolutePath().equals(storageRoot.getAbsolutePath())
                : !currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath());

        if (files == null || files.length == 0) {
            if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
            if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
            containerBrowserItems.removeAllViews();
            if (showUp) {
                Button btnUp = createListButton(getString(R.string.browser_up));
                btnUp.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        File parent = currentFolder.getParentFile();
                        if (parent != null) currentFolder = parent;
                        buildFileBrowserUI();
                    }
                });
                containerBrowserItems.addView(btnUp);
            }
            Button btnEmpty = createListButton(
                    files == null ? "⚠️ USB Disconnect Required (Tap to go back)" : "📂 Empty Folder (Tap to go back)");
            btnEmpty.setTextColor(0xFFFF5555);
            btnEmpty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (isPickingBackground) {
                        if (currentFolder.getAbsolutePath().equals(getStorageRoot().getAbsolutePath())) {
                            isPickingBackground = false;
                            changeScreen(STATE_SETTINGS);
                        } else {
                            File parent = currentFolder.getParentFile();
                            if (parent != null) currentFolder = parent;
                            buildFileBrowserUI();
                        }
                    } else if (currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath())) {
                        isPickingBackground = false;
                        changeScreen(STATE_MENU);
                    } else {
                        currentFolder = currentFolder.getParentFile();
                        buildFileBrowserUI();
                    }
                }
            });
            containerBrowserItems.addView(btnEmpty);
            return;
        }

        List<File> folders = new ArrayList<File>();
        List<File> audioFiles = new ArrayList<File>();
        List<File> apkFiles = new ArrayList<File>();
        List<File> imageFiles = new ArrayList<File>();

        for (File f : files) {
            if (f.isDirectory()) folders.add(f);
            else if (isPickingBackground && isImageFile(f)) imageFiles.add(f);
            else if (!isPickingBackground && isAudioFile(f)) audioFiles.add(f);
            else if (!isPickingBackground && isApkFile(f)) apkFiles.add(f);
        }
        java.util.Comparator<File> fileSorter = new java.util.Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        };
        java.util.Collections.sort(folders, fileSorter);
        java.util.Collections.sort(audioFiles, fileSorter);
        java.util.Collections.sort(apkFiles, fileSorter);
        java.util.Collections.sort(imageFiles, fileSorter);

        if (showUp) {
            folderBrowserEntries.add(FolderBrowserEntry.up(getString(R.string.browser_up)));
            currentScrollIndexList.add(getString(R.string.browser_up));
        }
        for (File folder : folders) {
            folderBrowserEntries.add(FolderBrowserEntry.folder(folder));
            currentScrollIndexList.add(folder.getName());
        }
        if (isPickingBackground) {
            for (File img : imageFiles) {
                folderBrowserEntries.add(FolderBrowserEntry.image(img));
                currentScrollIndexList.add(img.getName());
            }
        } else {
            for (File apk : apkFiles) {
                folderBrowserEntries.add(FolderBrowserEntry.apk(apk));
                currentScrollIndexList.add(apk.getName());
            }
            for (File audio : audioFiles) {
                folderBrowserEntries.add(FolderBrowserEntry.audio(audio));
                currentScrollIndexList.add(audio.getName());
            }
        }

        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);
        listVirtualSongs.setAdapter(new FolderBrowserAdapter());
        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) {
                    listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private void preparePodcastBrowserChrome() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        if (tvBrowserPath != null) tvBrowserPath.setVisibility(View.VISIBLE);
        applyPodcastBrowserLayout();
    }

    private void returnFromPlayer() {
        int target = playerReturnScreen;
        if (target == STATE_PODCASTS) {
            navigateToPodcastUi(playerReturnPodcastUiMode);
            return;
        }
        changeScreen(target);
    }

    private void navigateToPodcastUi(int mode) {
        podcastUiModeOnReturn = mode;
        changeScreen(STATE_PODCASTS);
    }

    private void restorePodcastUi(int mode) {
        switch (mode) {
            case PODCAST_UI_EPISODES:
                buildPodcastEpisodesUI();
                break;
            case PODCAST_UI_SHOWS:
                buildPodcastShowsUI();
                break;
            case PODCAST_UI_SAVED:
                if (podcastSavedShowFolder != null && !podcastSavedShowFolder.isEmpty()) {
                    buildPodcastSavedEpisodesUI(podcastSavedShowFolder);
                } else {
                    buildPodcastSavedShowsUI();
                }
                break;
            case PODCAST_UI_BROWSE_GENRE:
                buildPodcastGenreBrowseUI();
                break;
            case PODCAST_UI_BROWSE_COUNTRY:
                buildPodcastCountryBrowseUI();
                break;
            case PODCAST_UI_STOREFRONT:
                buildPodcastStorefrontPickerUI();
                break;
            case PODCAST_UI_SEARCH:
            default:
                buildPodcastSearchUI();
                break;
        }
    }

    private boolean isPodcastUiActive() {
        return !isFinishing() && currentScreenState == STATE_PODCASTS;
    }

    private void buildPodcastSearchUI() {
        podcastUiMode = PODCAST_UI_SEARCH;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_search));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_home));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                changeScreen(STATE_MENU);
            }
        });
        containerBrowserItems.addView(back);

        Button saved = createListButton(getString(R.string.podcasts_saved_on_device));
        saved.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSavedShowsUI();
            }
        });
        containerBrowserItems.addView(saved);

        if (!ConnectivityHelper.isOnline(this)) {
            if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
            return;
        }

        String typeLabel = getString(R.string.podcasts_type_search);
        if (podcastLastQuery != null && podcastLastQuery.length() > 0) {
            typeLabel += " (" + podcastLastQuery + ")";
        }
        Button typeSearch = createListButton(typeLabel);
        typeSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openPodcastSearchKeyboard();
            }
        });
        containerBrowserItems.addView(typeSearch);

        Button browseGenre = createListButton(getString(R.string.podcasts_browse_genre));
        browseGenre.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastGenreBrowseUI();
            }
        });
        containerBrowserItems.addView(browseGenre);

        Button browseCountry = createListButton(getString(R.string.podcasts_browse_country));
        browseCountry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastCountryBrowseUI();
            }
        });
        containerBrowserItems.addView(browseCountry);

        Button storefrontRow = createListButton(
                getString(R.string.podcast_storefront_label, getPodcastStorefrontLabel()));
        storefrontRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastStorefrontPickerUI();
            }
        });
        containerBrowserItems.addView(storefrontRow);

        for (PodcastCatalog.Category cat : PodcastCatalog.CATEGORIES) {
            final String q = cat.query;
            Button b = createListButton(getString(R.string.podcasts_search_category, getString(cat.labelResId)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    fetchPodcastShows(q);
                }
            });
            containerBrowserItems.addView(b);
        }

        createBrowserSectionHeader(getString(R.string.podcasts_browse_section));
        for (final OpenRssClient.Podcast p : PodcastCatalog.FEATURED) {
            Button b = createListButton(p.title);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    podcastShows.clear();
                    podcastLastQuery = p.title;
                    podcastSelected = p;
                    fetchPodcastEpisodes(p);
                }
            });
            containerBrowserItems.addView(b);
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private String getPodcastStorefront() {
        return podcastStorefront != null ? podcastStorefront : "US";
    }

    private void setPodcastStorefront(String code) {
        if (code == null || code.length() != 2) return;
        podcastStorefront = code.toUpperCase();
        try {
            prefs.edit().putString(PREF_PODCAST_STOREFRONT, podcastStorefront).commit();
        } catch (Exception ignored) {}
    }

    private String getPodcastStorefrontLabel() {
        for (PodcastCatalog.Country c : PodcastCatalog.COUNTRIES) {
            if (c.code.equals(getPodcastStorefront())) return getString(c.labelResId);
        }
        return getPodcastStorefront();
    }

    private void buildPodcastGenreBrowseUI() {
        podcastUiMode = PODCAST_UI_BROWSE_GENRE;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_browse_genre));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        for (final PodcastCatalog.Genre genre : PodcastCatalog.GENRES) {
            Button b = createListButton(getString(genre.labelResId));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    fetchPodcastShows(genre.searchTerm, getPodcastStorefront(), genre.genreId,
                            getString(genre.labelResId));
                }
            });
            containerBrowserItems.addView(b);
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void buildPodcastCountryBrowseUI() {
        podcastUiMode = PODCAST_UI_BROWSE_COUNTRY;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_browse_country));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        for (final PodcastCatalog.Country country : PodcastCatalog.COUNTRIES) {
            Button b = createListButton(getString(country.labelResId));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    fetchPodcastShows("podcast", country.code, null, getString(country.labelResId));
                }
            });
            containerBrowserItems.addView(b);
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void buildPodcastStorefrontPickerUI() {
        podcastUiMode = PODCAST_UI_STOREFRONT;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_storefront));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        for (final PodcastCatalog.Country country : PodcastCatalog.COUNTRIES) {
            final String label = getString(country.labelResId);
            String row = label;
            if (country.code.equals(getPodcastStorefront())) {
                row = getString(R.string.common_on) + " " + label;
            }
            Button b = createListButton(row);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    setPodcastStorefront(country.code);
                    buildPodcastSearchUI();
                }
            });
            containerBrowserItems.addView(b);
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void finishPodcastSearchEntry() {
        final String q = typedPassword.trim();
        podcastUiModeOnReturn = PODCAST_UI_RESTORE_NONE;
        changeScreen(STATE_PODCASTS);
        if (q.length() > 0 && requireInternet(R.string.podcasts_wifi_required_search)) fetchPodcastShows(q);
    }

    private void fetchPodcastShows(final String query) {
        fetchPodcastShows(query, getPodcastStorefront(), null, null);
    }

    private void fetchPodcastShows(final String query, final String country,
            final Integer genreId, final String displayLabel) {
        if (query == null || query.trim().isEmpty()) return;
        if (!requireInternet(R.string.podcasts_wifi_required_search)) return;
        podcastLastQuery = displayLabel != null && displayLabel.length() > 0
                ? displayLabel : query.trim();
        if (tvBrowserPath != null) {
            if (displayLabel == null && genreId == null) {
                tvBrowserPath.setText(getString(R.string.path_podcasts_search_query, podcastLastQuery));
            } else {
                tvBrowserPath.setText(getString(R.string.path_podcasts_results, podcastLastQuery));
            }
        }
        podcastShows.clear();
        buildPodcastShowsShell();
        final int gen = podcastUiGen;
        final String searchTerm = query.trim();
        final String searchCountry = country;
        final Integer searchGenre = genreId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<OpenRssClient.Podcast> raw = OpenRssClient.searchPodcasts(
                            searchTerm, searchCountry, searchGenre, 20);
                    if (raw.isEmpty()) {
                        runOnUiThreadSafe(new Runnable() {
                            @Override
                            public void run() {
                                if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                removePodcastProbeStatusRow();
                                Toast.makeText(MainActivity.this, getString(R.string.podcasts_no_results),
                                        Toast.LENGTH_SHORT).show();
                                buildPodcastSearchUI();
                            }
                        });
                        return;
                    }
                    OpenRssClient.probePodcastsPlayable(raw, new OpenRssClient.PodcastProbeCallback() {
                        @Override
                        public void onProbed(final OpenRssClient.Podcast podcast, final boolean playable) {
                            if (!playable || podcast == null) return;
                            runOnUiThreadSafe(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                    podcastShows.add(podcast);
                                    appendPodcastShowRow(podcast);
                                }
                            });
                        }

                        @Override
                        public void onComplete(final int playableCount, final int totalCount) {
                            runOnUiThreadSafe(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                    removePodcastProbeStatusRow();
                                    int hidden = totalCount - playableCount;
                                    if (hidden > 0) {
                                        Toast.makeText(MainActivity.this,
                                                getString(R.string.podcasts_filtered_unavailable, hidden),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                    if (playableCount == 0) {
                                        Toast.makeText(MainActivity.this, getString(R.string.podcasts_no_results),
                                                Toast.LENGTH_SHORT).show();
                                        buildPodcastSearchUI();
                                    }
                                }
                            });
                        }
                    });
                } catch (final Throwable e) {
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != podcastUiGen || !isPodcastUiActive()) return;
                            removePodcastProbeStatusRow();
                            Toast.makeText(MainActivity.this, getString(R.string.podcasts_search_failed),
                                    Toast.LENGTH_LONG).show();
                            buildPodcastSearchUI();
                        }
                    });
                }
            }
        }).start();
    }

    private void buildPodcastShowsShell() {
        podcastUiMode = PODCAST_UI_SHOWS;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts_results);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_results, podcastLastQuery));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);
        podcastProbeStatusRow = createListButton(getString(R.string.podcasts_checking_shows));
        podcastProbeStatusRow.setEnabled(false);
        containerBrowserItems.addView(podcastProbeStatusRow);
        back.requestFocus();
    }

    private void appendPodcastShowRow(final OpenRssClient.Podcast p) {
        String sub = p.publisher != null && p.publisher.length() > 0 ? p.publisher : null;
        View.OnFocusChangeListener focus = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) updatePodcastPreviewShow(p);
            }
        };
        View row = createPodcastListRow(p.title, sub, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                podcastSelected = p;
                fetchPodcastEpisodes(p);
            }
        }, focus);
        int insertAt = containerBrowserItems.getChildCount();
        if (podcastProbeStatusRow != null) {
            int idx = containerBrowserItems.indexOfChild(podcastProbeStatusRow);
            if (idx >= 0) insertAt = idx;
        }
        containerBrowserItems.addView(row, insertAt);
    }

    private void removePodcastProbeStatusRow() {
        if (podcastProbeStatusRow != null && containerBrowserItems != null) {
            containerBrowserItems.removeView(podcastProbeStatusRow);
            podcastProbeStatusRow = null;
        }
    }

    private void buildPodcastShowsUI() {
        podcastUiMode = PODCAST_UI_SHOWS;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts_results);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_results, podcastLastQuery));
        containerBrowserItems.removeAllViews();
        podcastProbeStatusRow = null;

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        if (podcastShows.isEmpty()) {
            Button empty = createListButton(getString(R.string.podcasts_empty_shows));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (final OpenRssClient.Podcast p : podcastShows) {
                appendPodcastShowRow(p);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void fetchPodcastEpisodes(final OpenRssClient.Podcast podcast) {
        if (podcast == null) return;
        if (!requireInternet(R.string.podcasts_wifi_required_episodes)) return;
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_show, podcast.title));
        podcastSelected = podcast;
        podcastEpisodes.clear();
        podcastEpisodeProbeState = null;
        podcastEpisodeFlushPtr = 0;
        podcastEpisodeProbeSource = null;
        buildPodcastEpisodesShell();
        final int gen = podcastUiGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<OpenRssClient.Episode> raw = OpenRssClient.fetchEpisodes(podcast.feedUrl, 40);
                    if (raw.isEmpty()) {
                        runOnUiThreadSafe(new Runnable() {
                            @Override
                            public void run() {
                                if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                removePodcastProbeStatusRow();
                                Toast.makeText(MainActivity.this, getString(R.string.podcasts_empty_episodes),
                                        Toast.LENGTH_LONG).show();
                                if (podcastShows.isEmpty()) buildPodcastSearchUI();
                                else buildPodcastShowsUI();
                            }
                        });
                        return;
                    }
                    podcastEpisodeProbeSource = raw;
                    podcastEpisodeProbeState = new int[raw.size()];
                    podcastEpisodeFlushPtr = 0;
                    OpenRssClient.probeEpisodesPlayable(raw, podcast.title,
                            new OpenRssClient.EpisodeProbeCallback() {
                                @Override
                                public void onProbed(final int index, final OpenRssClient.Episode episode,
                                        final boolean playable) {
                                    runOnUiThreadSafe(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                            onPodcastEpisodeProbed(index, playable);
                                        }
                                    });
                                }

                                @Override
                                public void onComplete(final int playableCount, final int totalCount) {
                                    runOnUiThreadSafe(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (gen != podcastUiGen || !isPodcastUiActive()) return;
                                            removePodcastProbeStatusRow();
                                            int hidden = totalCount - playableCount;
                                            if (hidden > 0) {
                                                Toast.makeText(MainActivity.this,
                                                        getString(R.string.podcasts_episodes_filtered, hidden),
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                            if (playableCount == 0) {
                                                Toast.makeText(MainActivity.this,
                                                        getString(R.string.podcasts_empty_episodes),
                                                        Toast.LENGTH_LONG).show();
                                                if (podcastShows.isEmpty()) buildPodcastSearchUI();
                                                else buildPodcastShowsUI();
                                            }
                                        }
                                    });
                                }
                            });
                } catch (final Throwable e) {
                    runOnUiThreadSafe(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != podcastUiGen || !isPodcastUiActive()) return;
                            removePodcastProbeStatusRow();
                            Toast.makeText(MainActivity.this, getString(R.string.podcasts_episode_failed),
                                    Toast.LENGTH_LONG).show();
                            buildPodcastShowsUI();
                        }
                    });
                }
            }
        }).start();
    }

    private void onPodcastEpisodeProbed(int index, boolean playable) {
        if (podcastEpisodeProbeState == null || podcastEpisodeProbeSource == null) return;
        if (index < 0 || index >= podcastEpisodeProbeState.length) return;
        podcastEpisodeProbeState[index] = playable ? 1 : 2;
        while (podcastEpisodeFlushPtr < podcastEpisodeProbeState.length
                && podcastEpisodeProbeState[podcastEpisodeFlushPtr] != 0) {
            int flushIdx = podcastEpisodeFlushPtr++;
            if (podcastEpisodeProbeState[flushIdx] == 1) {
                OpenRssClient.Episode ep = podcastEpisodeProbeSource.get(flushIdx);
                podcastEpisodes.add(ep);
                appendPodcastEpisodeRow(ep, podcastEpisodes.size() - 1);
            }
        }
    }

    private void buildPodcastEpisodesShell() {
        podcastUiMode = PODCAST_UI_EPISODES;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts_episodes);
        updateStatusBarTitle();
        String t = podcastSelected != null ? podcastSelected.title : "Episodes";
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_show, t));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_results));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (podcastShows.isEmpty()) buildPodcastSearchUI();
                else buildPodcastShowsUI();
            }
        });
        containerBrowserItems.addView(back);
        podcastProbeStatusRow = createListButton(getString(R.string.podcasts_checking_episodes));
        podcastProbeStatusRow.setEnabled(false);
        containerBrowserItems.addView(podcastProbeStatusRow);
        back.requestFocus();
    }

    private void appendPodcastEpisodeRow(final OpenRssClient.Episode ep, final int idx) {
        String label = ep.title;
        final boolean saved = podcastSelected != null
                && PodcastLibrary.findSaved(podcastSelected.title, ep.title, ep.audioUrl) != null;
        if (saved) {
            label = getString(R.string.podcasts_saved_badge) + " " + label;
        }
        String resumeKey = PodcastResumeStore.keyForEpisode(
                podcastSelected != null ? podcastSelected.title : "",
                ep.title, ep.audioUrl,
                PodcastLibrary.findSaved(
                        podcastSelected != null ? podcastSelected.title : "",
                        ep.title, ep.audioUrl));
        final boolean resume = PodcastResumeStore.hasResume(getApplicationContext(), resumeKey);
        if (resume) {
            label = getString(R.string.podcasts_resume_badge) + " " + label;
        }
        final String rowTitle = label;
        View.OnFocusChangeListener focus = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) updatePodcastPreviewEpisode(ep, saved, resume);
            }
        };
        View row = createPodcastListRow(rowTitle, podcastEpisodeInlineSubtitle(ep),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        startPodcastPlayback(podcastEpisodes, idx);
                    }
                }, focus);
        int insertAt = containerBrowserItems.getChildCount();
        if (podcastProbeStatusRow != null) {
            int statusIdx = containerBrowserItems.indexOfChild(podcastProbeStatusRow);
            if (statusIdx >= 0) insertAt = statusIdx;
        }
        containerBrowserItems.addView(row, insertAt);
    }

    private void buildPodcastEpisodesUI() {
        podcastUiMode = PODCAST_UI_EPISODES;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts_episodes);
        updateStatusBarTitle();
        String t = podcastSelected != null ? podcastSelected.title : "Episodes";
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_show, t));
        containerBrowserItems.removeAllViews();
        podcastProbeStatusRow = null;

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_results));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (podcastShows.isEmpty()) buildPodcastSearchUI();
                else buildPodcastShowsUI();
            }
        });
        containerBrowserItems.addView(back);

        if (podcastEpisodes.isEmpty()) {
            Button empty = createListButton(getString(R.string.podcasts_empty_episodes));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (int i = 0; i < podcastEpisodes.size(); i++) {
                appendPodcastEpisodeRow(podcastEpisodes.get(i), i);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void buildPodcastSavedShowsUI() {
        podcastUiMode = PODCAST_UI_SAVED;
        podcastSavedShowFolder = "";
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_podcasts_saved));
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        List<String> shows = PodcastLibrary.listSavedShows();
        if (shows.isEmpty()) {
            Button empty = createListButton(getString(R.string.podcasts_empty_saved));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (final String show : shows) {
                Button b = createListButton(show);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        buildPodcastSavedEpisodesUI(show);
                    }
                });
                containerBrowserItems.addView(b);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void buildPodcastSavedEpisodesUI(final String showFolder) {
        podcastUiMode = PODCAST_UI_SAVED;
        podcastSavedShowFolder = showFolder;
        preparePodcastBrowserChrome();
        browserStatusTitle = getString(R.string.status_podcasts);
        updateStatusBarTitle();
        if (tvBrowserPath != null) {
            tvBrowserPath.setText(getString(R.string.path_podcasts_saved_show, showFolder));
        }
        containerBrowserItems.removeAllViews();

        Button back = createListButton("〈 " + getString(R.string.podcasts_saved_on_device));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPodcastSavedShowsUI();
            }
        });
        containerBrowserItems.addView(back);

        final List<File> files = PodcastLibrary.listSavedEpisodes(showFolder);
        if (files.isEmpty()) {
            Button empty = createListButton(getString(R.string.podcasts_empty_saved));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            for (int i = 0; i < files.size(); i++) {
                final int idx = i;
                final File file = files.get(i);
                String label = file.getName();
                int dot = label.lastIndexOf('.');
                if (dot > 0) label = label.substring(0, dot);
                final boolean resume = PodcastResumeStore.hasResume(getApplicationContext(),
                        PodcastResumeStore.keyForFile(file));
                if (resume) {
                    label = getString(R.string.podcasts_resume_badge) + " " + label;
                }
                final String rowTitle = label;
                String date = OpenRssClient.formatPodcastDate(
                        new java.text.SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
                                .format(new java.util.Date(file.lastModified())));
                Integer cachedDur = podcastSavedDurationCache.get(file.getAbsolutePath());
                String sub = date + " · " + (cachedDur != null && cachedDur > 0
                        ? OpenRssClient.formatDuration(cachedDur)
                        : getString(R.string.podcast_detail_unknown_duration));
                View.OnFocusChangeListener focus = new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus) updatePodcastPreviewSavedFile(file, showFolder);
                    }
                };
                View row = createPodcastListRow(rowTitle, sub, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        startSavedPodcastPlayback(showFolder, idx);
                    }
                }, focus);
                row.setTag(file);
                containerBrowserItems.addView(row);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void startSavedPodcastPlayback(String showFolder, int fileIndex) {
        List<File> files = PodcastLibrary.listSavedEpisodes(showFolder);
        if (files.isEmpty()) return;
        List<OpenRssClient.Episode> eps = PodcastLibrary.episodesFromSavedFiles(showFolder);
        podcastSelected = new OpenRssClient.Podcast(showFolder, "", "", "");
        startPodcastPlayback(eps, fileIndex, true);
    }

    private void prepareSoulseekBrowserChrome() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        if (tvBrowserPath != null) tvBrowserPath.setVisibility(View.VISIBLE);
    }

    private boolean isSoulseekUiActive() {
        return !isFinishing() && currentScreenState == STATE_SOULSEEK;
    }

    private SoulseekClient ensureSoulseekClient() {
        if (soulseekClient == null) {
            SoulseekAccount account = SoulseekAccount.load(prefs);
            soulseekClient = new SoulseekClient(account.username, account.password, rootFolder,
                    MainActivity.this, soulseekListener);
        }
        soulseekClient.setSharePolicy(soulseekSharePolicy);
        soulseekClient.setShareIndex(soulseekShareIndex);
        return soulseekClient;
    }

    private void updateSoulseekSharePolicy() {
        boolean reachUi = currentScreenState == STATE_SOULSEEK || hasActiveReachDownload();
        boolean wifi = hasInternetConnection();
        soulseekSharePolicy.update(soulseekCharging, wifi, reachUi);
        if (!soulseekSharePolicy.announceShares() && soulseekClient == null) return;
        SoulseekClient client = soulseekSharePolicy.announceShares()
                ? ensureSoulseekClient() : soulseekClient;
        if (client == null) return;
        client.setSharePolicy(soulseekSharePolicy);
        if (soulseekSharePolicy.announceShares() && !soulseekShareScanRunning) {
            soulseekShareScanRunning = true;
            final SoulseekAccount account = SoulseekAccount.load(prefs);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        soulseekShareIndex.scan(account.username, rootFolder, PodcastLibrary.ROOT);
                        SoulseekClient c = soulseekClient;
                        if (c != null) {
                            c.setShareIndex(soulseekShareIndex);
                            c.refreshShareAnnouncement();
                        }
                    } finally {
                        soulseekShareScanRunning = false;
                    }
                }
            }, "ShareScan").start();
        } else {
            client.setShareIndex(soulseekShareIndex);
            client.refreshShareAnnouncement();
        }
    }

    private String soulseekSharingStatusLabel() {
        switch (soulseekSharePolicy.state()) {
            case ACTIVE:
                return getString(R.string.soulseek_sharing_on, soulseekShareIndex.fileCount());
            case DRAINING:
                return getString(R.string.soulseek_sharing_draining);
            default:
                return getString(R.string.soulseek_sharing_off);
        }
    }

    private final SoulseekClient.Listener soulseekListener = new SoulseekClient.Listener() {
        @Override
        public void onStatus(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - soulseekLastStatusUiMs < 200) return;
                    soulseekLastStatusUiMs = now;
                    if (soulseekUiMode == SOULSEEK_UI_DOWNLOAD && soulseekActiveDownload != null) {
                        return;
                    }
                    if (!isSoulseekUiActive()) return;
                    if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_soulseek, message));
                }
            });
        }

        @Override
        public void onDownloadPhase(final String phase, final String detail) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (soulseekUiMode != SOULSEEK_UI_DOWNLOAD || soulseekActiveDownload == null) return;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - soulseekLastStatusUiMs < 200) return;
                    soulseekLastStatusUiMs = now;
                    soulseekDownloadPhase = phase != null ? phase : "";
                    soulseekDownloadPhaseDetail = detail != null ? detail : "";
                    updateSoulseekDownloadStatusUi();
                }
            });
        }

        @Override
        public void onConnected(final int listenPort) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ConnectivityHelper.setReachLoginOk(true);
                    buildHomeMenu();
                    soulseekListenPort = listenPort;
                    if (isSoulseekUiActive() && tvBrowserPath != null && soulseekUiMode == SOULSEEK_UI_SEARCH) {
                        SoulseekAccount account = SoulseekAccount.load(prefs);
                        tvBrowserPath.setText(getString(R.string.path_soulseek, SoulseekAccount.displayLabel(account)
                                + " · " + getString(R.string.soulseek_connected_port, listenPort)));
                    }
                    if (!prefs.getBoolean("soulseek_port_warn_shown", false)) {
                        prefs.edit().putBoolean("soulseek_port_warn_shown", true).commit();
                        Toast.makeText(MainActivity.this, getString(R.string.soulseek_port_warning, listenPort),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @Override
        public void onLoginFailed(final String reason) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ConnectivityHelper.setReachLoginOk(false);
                    buildHomeMenu();
                    Toast.makeText(MainActivity.this,
                            getString(R.string.soulseek_login_failed_check_account, reason),
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void onSearchResult(final SoulseekClient.Result result) {
            if (soulseekClient != null && soulseekClient.isPeerDenied(result.username)) return;
            if (soulseekHideFlac && SoulseekClient.Result.isFlacFile(result.filename)) return;
            synchronized (soulseekPendingUi) {
                soulseekPendingUi.add(result);
                if (!soulseekUiFlushScheduled) {
                    soulseekUiFlushScheduled = true;
                    long delay = soulseekPendingUi.size() > 16 ? SOULSEEK_UI_FLUSH_MS_HEAVY : SOULSEEK_UI_FLUSH_MS;
                    soulseekUiHandler.postDelayed(soulseekUiFlushRunnable, delay);
                }
            }
        }

        @Override
        public void onSearchFinished(final int token, final int count) {
            runOnUiThreadSafe(new Runnable() {
                @Override
                public void run() {
                    soulseekUiHandler.removeCallbacks(soulseekUiFlushRunnable);
                    flushSoulseekResultsUi(true);
                    mergeSoulseekResultsFromSnapshot();
                    final List<SoulseekClient.Result> toSort =
                            new ArrayList<SoulseekClient.Result>(soulseekResults);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            sortSoulseekResultsByQuality(toSort);
                            runOnUiThreadSafe(new Runnable() {
                                @Override
                                public void run() {
                                    soulseekSearchInProgress = false;
                                    soulseekResults.clear();
                                    soulseekResultKeys.clear();
                                    for (SoulseekClient.Result r : toSort) {
                                        soulseekResultKeys.add(soulseekResultKey(r));
                                    }
                                    soulseekResults.addAll(toSort);
                                    soulseekResultsVisibleCount = Math.min(SOULSEEK_PAGE_SIZE, soulseekResults.size());
                                    if (!isSoulseekUiActive()) return;
                                    if (soulseekUiMode == SOULSEEK_UI_DOWNLOAD) {
                                        if (!soulseekDownloadUiFailed && tvBrowserPath != null) {
                                            tvBrowserPath.setText(getString(
                                                    R.string.path_soulseek_search_done_download,
                                                    soulseekLastQuery, toSort.size()));
                                        }
                                        if (toSort.isEmpty()) {
                                            Toast.makeText(MainActivity.this,
                                                    getString(R.string.soulseek_no_results),
                                                    Toast.LENGTH_SHORT).show();
                                        } else if (soulseekAutoDownloadPending && !soulseekDownloadUiFailed
                                                && soulseekActiveDownload == null) {
                                            soulseekAutoDownloadPending = false;
                                            startSoulseekTransfer(soulseekResults.get(0), SOULSEEK_ACTION_PLAY);
                                        }
                                        return;
                                    }
                                    if (soulseekUiMode == SOULSEEK_UI_ACTION) {
                                        return;
                                    }
                                    buildSoulseekResultsUI();
                                    finalizeSoulseekSearch(toSort.size());
                                    if (toSort.isEmpty()) {
                                        Toast.makeText(MainActivity.this,
                                                getString(R.string.soulseek_no_results),
                                                Toast.LENGTH_SHORT).show();
                                    } else if (soulseekAutoDownloadPending) {
                                        soulseekAutoDownloadPending = false;
                                        startSoulseekTransfer(soulseekResults.get(0), SOULSEEK_ACTION_PLAY);
                                    }
                                }
                            });
                        }
                    }, "SoulseekSort").start();
                }
            });
        }

        @Override
        public void onDownloadProgress(final String filename, final long done, final long total) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (soulseekActiveDownload == null) return;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - soulseekLastProgressUiMs < 150 && total > 0 && done < total) return;
                    soulseekLastProgressUiMs = now;
                    if (done > 0) {
                        soulseekDownloadStalled = false;
                        soulseekUiHandler.removeCallbacks(soulseekStallWatchRunnable);
                        showSoulseekTryAnotherRow(false);
                    }
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (total > 0) reachGrowingTotalBytes = total;
                    updateSoulseekDownloadProgress(pct, done, total);
                    if (reachPartialPlaybackStarted && currentScreenState == STATE_PLAYER) {
                        updateReachPlayerBufferUi(pct, done, total);
                    }
                }
            });
        }

        @Override
        public void onDownloadPartialReady(final File partialFile, final long done, final long total) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (soulseekActiveDownload == null || partialFile == null) return;
                    final int action = soulseekPendingAction;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (total > 0) reachGrowingTotalBytes = total;
                    updateSoulseekDownloadProgress(pct, done, total);
                    if (action == SOULSEEK_ACTION_PLAY && !reachPartialPlaybackStarted) {
                        reachPartialPlaybackStarted = true;
                        reachGrowingCacheFile = partialFile;
                        reachGrowingPreparedBytes = partialFile.length();
                        stopSoulseekDownloadUiRunnables();
                        startReachPlayFromPartial(partialFile);
                    } else if (action == SOULSEEK_ACTION_PLAY && reachPartialPlaybackStarted) {
                        reachGrowingCacheFile = partialFile;
                        reachGrowingPreparedBytes = partialFile.length();
                        updateReachGrowingDurationUi();
                        tryReachId3FromPartial(partialFile);
                    } else if (action == SOULSEEK_ACTION_QUEUE && reachQueuePartialFile == null) {
                        reachQueuePartialFile = partialFile;
                        appendTrackToMusicQueue(partialFile);
                        soulseekUiMode = SOULSEEK_UI_RESULTS;
                        buildSoulseekResultsUI();
                    }
                }
            });
        }

        @Override
        public void onDownloadComplete(final File file) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final int action = soulseekPendingAction;
                    final File queuePartial = reachQueuePartialFile;
                    final boolean reachStream = reachPartialPlaybackStarted;
                    stopSoulseekDownloadUiRunnables();
                    if (!reachStream) progressHandler.removeCallbacks(reachGrowingEdgePoll);
                    soulseekActiveDownload = null;
                    soulseekDownloadProgressBar = null;
                    soulseekDownloadPercentText = null;
                    soulseekDownloadDetailText = null;
                    soulseekDownloadStatusRow = null;
                    soulseekPendingAction = 0;
                    reachQueuePartialFile = null;
                    if (action == SOULSEEK_ACTION_PLAY) {
                        if (reachPartialPlaybackStarted) {
                            reachGrowingCacheFile = file;
                            reachGrowingTotalBytes = file.length();
                            clearReachLoadingArtistLabel();
                            updateReachGrowingDurationUi();
                            tryReachId3FromPartial(file);
                            maybeExtendReachGrowingPlayback(false);
                        } else {
                            progressHandler.removeCallbacks(reachGrowingEdgePoll);
                            soulseekUiMode = SOULSEEK_UI_SEARCH;
                            List<File> one = new ArrayList<File>();
                            one.add(file);
                            playTrackList(one, 0);
                        }
                    } else if (action == SOULSEEK_ACTION_QUEUE) {
                        soulseekUiMode = SOULSEEK_UI_RESULTS;
                        if (queuePartial != null && !queuePartial.equals(file)) {
                            replaceReachFileInQueue(queuePartial, file);
                        }
                        if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
                    } else {
                        soulseekUiMode = SOULSEEK_UI_SEARCH;
                        Toast.makeText(MainActivity.this, getString(R.string.soulseek_download_saved, file.getName()),
                                Toast.LENGTH_SHORT).show();
                        if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
                        scanMediaLibraryAsync();
                    }
                    if (!isSoulseekUiActive() && !shouldDeferSoulseekOffScreenCleanup()) {
                        soulseekOffScreenCleanup();
                    }
                }
            });
        }

        @Override
        public void onError(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if ("Download cancelled".equals(message)) {
                        if (!isSoulseekUiActive() && !shouldDeferSoulseekOffScreenCleanup()) {
                            soulseekOffScreenCleanup();
                        }
                        return;
                    }
                    String msg = humanizeSoulseekError(message);
                    final SoulseekClient.Result failed = soulseekActiveDownload != null
                            ? soulseekActiveDownload : soulseekFailedResult;
                    stopSoulseekDownloadUiRunnables();
                    soulseekDownloadProgressBar = null;
                    soulseekDownloadPercentText = null;
                    soulseekDownloadDetailText = null;
                    soulseekDownloadStatusRow = null;
                    soulseekTryAnotherRow = null;
                    soulseekActiveDownload = null;
                    soulseekPendingAction = 0;
                    reachPartialPlaybackStarted = false;
                    reachGrowingCacheFile = null;
                    reachQueuePartialFile = null;
                    progressHandler.removeCallbacks(reachGrowingEdgePoll);
                    setBlockingLoading(false);
                    if (currentScreenState == STATE_SOULSEEK && failed != null) {
                        showSoulseekDownloadFailure(failed, msg);
                    } else if (currentScreenState == STATE_SOULSEEK) {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        if (soulseekUiMode == SOULSEEK_UI_DOWNLOAD || soulseekDownloadUiFailed) {
                            soulseekDownloadUiFailed = false;
                            buildSoulseekResultsUI();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                    if (!isSoulseekUiActive() && !shouldDeferSoulseekOffScreenCleanup()) {
                        soulseekOffScreenCleanup();
                    }
                }
            });
        }
    };

    private final Runnable soulseekUiFlushRunnable = new Runnable() {
        @Override
        public void run() {
            flushSoulseekResultsUi(false);
        }
    };

    private static String soulseekResultKey(SoulseekClient.Result result) {
        return result.username.toLowerCase(Locale.US) + "\0" + result.filename;
    }

    private static void sortSoulseekResultsByQuality(List<SoulseekClient.Result> list) {
        Collections.sort(list, new Comparator<SoulseekClient.Result>() {
            @Override
            public int compare(SoulseekClient.Result a, SoulseekClient.Result b) {
                return SoulseekClient.Result.compareByDownloadReliability(a, b);
            }
        });
    }

    private int soulseekRankInsertIndex(SoulseekClient.Result r) {
        for (int i = 0; i < soulseekResults.size(); i++) {
            if (SoulseekClient.Result.compareByDownloadReliability(r, soulseekResults.get(i)) < 0) return i;
        }
        return soulseekResults.size();
    }

    private static String formatSoulseekSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private static String formatSoulseekBitrate(int kbps) {
        if (kbps <= 0) return "";
        return kbps + " kbps · ";
    }

    private static String formatSoulseekSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        if (bytesPerSec >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
    }

    private void flushSoulseekResultsUi(boolean force) {
        soulseekUiFlushScheduled = false;
        List<SoulseekClient.Result> batch;
        synchronized (soulseekPendingUi) {
            if (soulseekPendingUi.isEmpty()) return;
            int take = force ? soulseekPendingUi.size()
                    : Math.min(SOULSEEK_ROWS_PER_FLUSH, soulseekPendingUi.size());
            batch = new ArrayList<SoulseekClient.Result>(soulseekPendingUi.subList(0, take));
            soulseekPendingUi.subList(0, take).clear();
        }
        if (!isSoulseekUiActive()) return;

        for (SoulseekClient.Result r : batch) {
            if (soulseekClient != null && soulseekClient.isPeerDenied(r.username)) continue;
            if (!soulseekResultKeys.add(soulseekResultKey(r))) continue;
            soulseekResults.add(soulseekRankInsertIndex(r), r);
        }
        if (soulseekSearchInProgress) {
            appendSoulseekResultRowsInner();
        } else {
            refreshSoulseekVisibleResults(true);
        }
        updateSoulseekPathCount(force);
        synchronized (soulseekPendingUi) {
            if (!soulseekPendingUi.isEmpty()) {
                soulseekUiFlushScheduled = true;
                long delay = soulseekPendingUi.size() > 16 ? SOULSEEK_UI_FLUSH_MS_HEAVY : SOULSEEK_UI_FLUSH_MS;
                soulseekUiHandler.postDelayed(soulseekUiFlushRunnable, delay);
            }
        }
    }

    private void mergeSoulseekResultsFromSnapshot() {
        if (soulseekClient == null) return;
        for (SoulseekClient.Result r : soulseekClient.getResultsSnapshot()) {
            if (!soulseekResultAllowed(r)) continue;
            if (!soulseekResultKeys.add(soulseekResultKey(r))) continue;
            soulseekResults.add(soulseekRankInsertIndex(r), r);
        }
    }

    private int soulseekVisibleCap() {
        if (soulseekSearchInProgress) return SOULSEEK_PAGE_SIZE;
        return soulseekResultsVisibleCount;
    }

    private int soulseekRankedResultCount() {
        int n = 0;
        for (SoulseekClient.Result r : soulseekResults) {
            if (soulseekClient != null && soulseekClient.isPeerDenied(r.username)) continue;
            if (soulseekSearchInProgress && r.isLikelySlowDownload()) continue;
            n++;
        }
        return n;
    }

    private void appendSoulseekResultRowsInner() {
        if (!isSoulseekUiActive() || soulseekUiMode != SOULSEEK_UI_RESULTS) return;
        int cap = soulseekVisibleCap();
        int insertAt = soulseekResultInsertIndex();
        int shown = 0;
        int added = 0;
        for (SoulseekClient.Result r : soulseekResults) {
            if (soulseekClient != null && soulseekClient.isPeerDenied(r.username)) continue;
            if (soulseekSearchInProgress && r.isLikelySlowDownload()) continue;
            if (shown < soulseekResultUiCount) {
                shown++;
                continue;
            }
            if (soulseekResultUiCount >= cap) break;
            containerBrowserItems.addView(makeSoulseekResultButton(r), insertAt + added);
            soulseekResultUiCount++;
            added++;
        }
        if (soulseekSearchStatusRow != null && soulseekResultUiCount > 0) {
            containerBrowserItems.removeView(soulseekSearchStatusRow);
            soulseekSearchStatusRow = null;
        }
        updateSoulseekMoreRow(soulseekRankedResultCount());
    }

    /** Rebuild visible result rows from ranked soulseekResults — not arrival order. */
    private void refreshSoulseekVisibleResults(boolean fullRebuild) {
        if (!isSoulseekUiActive() || soulseekUiMode != SOULSEEK_UI_RESULTS) return;
        if (!fullRebuild) {
            appendSoulseekResultRowsInner();
            return;
        }
        java.util.ArrayList<View> toRemove = new java.util.ArrayList<View>();
        for (int i = 0; i < containerBrowserItems.getChildCount(); i++) {
            View v = containerBrowserItems.getChildAt(i);
            if (v.getTag() instanceof SoulseekClient.Result) toRemove.add(v);
        }
        for (View v : toRemove) containerBrowserItems.removeView(v);
        soulseekResultUiCount = 0;
        appendSoulseekResultRowsInner();
    }

    private int soulseekResultInsertIndex() {
        if (soulseekMoreRow != null && soulseekMoreRow.getParent() == containerBrowserItems) {
            return containerBrowserItems.indexOfChild(soulseekMoreRow);
        }
        return containerBrowserItems.getChildCount();
    }

    private void updateReachBrowserHint(int hintResId) {
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(hintResId));
    }

    private void buildSoulseekSearchUI() {
        soulseekUiMode = SOULSEEK_UI_SEARCH;
        prepareSoulseekBrowserChrome();
        browserStatusTitle = getString(R.string.status_soulseek);
        updateStatusBarTitle();
        updateReachBrowserHint(R.string.reach_hint_search);
        containerBrowserItems.removeAllViews();

        String backLabel = soulseekReturnScreen == STATE_MENU ? getString(R.string.soulseek_back_home) : getString(R.string.soulseek_back_settings);
        Button back = createListButton(backLabel);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                returnFromSoulseek();
            }
        });
        containerBrowserItems.addView(back);

        if (!ConnectivityHelper.isOnline(this)) {
            if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
            return;
        }

        String typeLabel = getString(R.string.soulseek_type_search);
        if (soulseekLastQuery != null && soulseekLastQuery.length() > 0) {
            typeLabel += " (" + soulseekLastQuery + ")";
        }
        Button typeSearch = createListButton(typeLabel);
        typeSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openSoulseekSearchKeyboard();
            }
        });
        containerBrowserItems.addView(typeSearch);

        List<String> recent = SoulseekSearchHistory.load(prefs);
        if (!recent.isEmpty()) {
            createBrowserSectionHeader(getString(R.string.soulseek_recent_searches));
            for (final String q : recent) {
                Button b = createListButton(q);
                b.setTag(R.id.tag_soulseek_recent_query, q);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        fetchSoulseekResults(q);
                    }
                });
                containerBrowserItems.addView(b);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void finishSoulseekSearchEntry() {
        final String q = typedPassword.trim();
        openSoulseekScreen(true);
        if (q.length() > 0 && requireInternet(R.string.soulseek_wifi_required)) fetchSoulseekResults(q);
    }

    private void fetchSoulseekResults(final String query) {
        if (query == null || query.trim().isEmpty()) return;
        if (!requireInternet(R.string.soulseek_wifi_required)) return;
        soulseekLastQuery = query.trim();
        SoulseekSearchHistory.remember(prefs, soulseekLastQuery);
        soulseekSearchInProgress = true;
        soulseekResultsVisibleCount = SOULSEEK_PAGE_SIZE;
        soulseekResults.clear();
        soulseekResultKeys.clear();
        synchronized (soulseekPendingUi) {
            soulseekPendingUi.clear();
        }
        soulseekUiHandler.removeCallbacks(soulseekUiFlushRunnable);
        soulseekUiFlushScheduled = false;
        buildSoulseekResultsShell();
        ensureSoulseekClient().search(soulseekLastQuery);
    }

    private void buildSoulseekResultsShell() {
        soulseekUiMode = SOULSEEK_UI_RESULTS;
        soulseekResultUiCount = 0;
        soulseekSearchStatusRow = null;
        soulseekMoreRow = null;
        prepareSoulseekBrowserChrome();
        browserStatusTitle = getString(R.string.status_soulseek_results);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_soulseek_searching, soulseekLastQuery));
        containerBrowserItems.removeAllViews();

        Button back = createListButton(getString(R.string.soulseek_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        soulseekSearchStatusRow = createListButton(getString(R.string.soulseek_searching));
        soulseekSearchStatusRow.setEnabled(false);
        containerBrowserItems.addView(soulseekSearchStatusRow);
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private Button makeSoulseekResultButton(final SoulseekClient.Result r) {
        String slotTag = r.freeSlot ? "+ " : "  ";
        String label = slotTag + r.qualityStars() + " " + r.title() + " · " + formatSoulseekBitrate(r.bitrate)
                + formatSoulseekSize(r.size) + " (" + r.username + ")";
        final Button b = new Button(this);
        b.setText(label);
        b.setTag(r);
        configureSoulseekResultButton(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (!requireInternet(R.string.soulseek_wifi_required)) return;
                buildSoulseekActionUI(r);
            }
        });
        return b;
    }

    private void configureSoulseekResultButton(final Button btn) {
        final int rowKind = Y1_ROW_ITEM;
        int rowW = listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx();
        btn.setBackground(getY1RowBackground(false, rowW, rowKind));
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        btn.setSoundEffectsEnabled(false);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        ThemeManager.applyThemedTextStyle(btn, y1RowTextColorNormal(rowKind));
        btn.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        btn.setPadding(hPad, 0, hPad, 0);
        btn.setFocusable(true);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setHorizontallyScrolling(false);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (soulseekFocusedResultRow != null && soulseekFocusedResultRow != btn) {
                    soulseekFocusedResultRow.setEllipsize(TextUtils.TruncateAt.END);
                    soulseekFocusedResultRow.setSelected(false);
                    soulseekFocusedResultRow.setHorizontallyScrolling(false);
                }
                int w = btn.getWidth() > 0 ? btn.getWidth()
                        : (listRowWidthPx > 0 ? listRowWidthPx : y1ActiveRowWidthPx());
                btn.setBackground(getY1RowBackground(hasFocus, w, rowKind));
                ThemeManager.applyThemedTextStyle(btn, hasFocus
                        ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                btn.setSelected(hasFocus);
                if (hasFocus) {
                    soulseekFocusedResultRow = btn;
                    enableMarquee(btn);
                } else {
                    btn.setEllipsize(TextUtils.TruncateAt.END);
                    btn.setHorizontallyScrolling(false);
                }
                if (hasFocus) showFastScrollLetter(btn.getText().toString());
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        lp.setMargins(0, 1, 0, 1);
        btn.setLayoutParams(lp);
    }

    private void buildSoulseekActionUI(final SoulseekClient.Result r) {
        stopSoulseekActionRefresh();
        soulseekUiMode = SOULSEEK_UI_ACTION;
        prepareSoulseekBrowserChrome();
        browserStatusTitle = r.title();
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_soulseek, r.title()));
        containerBrowserItems.removeAllViews();

        Button back = createListButton(getString(R.string.soulseek_back_results));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                stopSoulseekActionRefresh();
                buildSoulseekResultsUI();
            }
        });
        containerBrowserItems.addView(back);

        Button info = createListButton(r.qualityStars() + " " + r.title() + " · "
                + formatSoulseekBitrate(r.bitrate) + formatSoulseekSize(r.size)
                + (r.freeSlot ? " · " + getString(R.string.soulseek_slot_free) : ""));
        info.setEnabled(false);
        containerBrowserItems.addView(info);

        Button play = createListButton(getString(R.string.soulseek_play));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                stopSoulseekActionRefresh();
                startSoulseekTransfer(r, SOULSEEK_ACTION_PLAY);
            }
        });
        containerBrowserItems.addView(play);

        Button save = createListButton(getString(R.string.soulseek_save));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                stopSoulseekActionRefresh();
                startSoulseekTransfer(r, SOULSEEK_ACTION_SAVE);
            }
        });
        containerBrowserItems.addView(save);

        Button queue = createListButton(getString(R.string.soulseek_add_to_queue));
        queue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                stopSoulseekActionRefresh();
                startSoulseekTransfer(r, SOULSEEK_ACTION_QUEUE);
            }
        });
        containerBrowserItems.addView(queue);

        soulseekActionSuggestionPool.clear();
        soulseekActionSuggestionPool.addAll(SoulseekSearchSuggestions.similarFromResults(
                soulseekResults, soulseekLastQuery, 40));
        soulseekActionSuggestionOffset = 0;
        soulseekActionSuggestionHost = new LinearLayout(this);
        soulseekActionSuggestionHost.setOrientation(LinearLayout.VERTICAL);
        containerBrowserItems.addView(soulseekActionSuggestionHost);
        refreshSoulseekActionSuggestions();
        startSoulseekActionRefresh();
        play.requestFocus();
    }

    private void startSoulseekActionRefresh() {
        soulseekUiHandler.removeCallbacks(soulseekActionRefreshRunnable);
        if (soulseekActionSuggestionPool.size() > 4) {
            soulseekUiHandler.postDelayed(soulseekActionRefreshRunnable, SOULSEEK_ACTION_REFRESH_MS);
        }
    }

    private void stopSoulseekActionRefresh() {
        soulseekUiHandler.removeCallbacks(soulseekActionRefreshRunnable);
        soulseekActionSuggestionHost = null;
    }

    private void refreshSoulseekActionSuggestions() {
        if (soulseekActionSuggestionHost == null) return;
        soulseekActionSuggestionHost.removeAllViews();
        if (soulseekActionSuggestionPool.isEmpty()) return;
        TextView header = new TextView(this);
        header.setText(getString(R.string.soulseek_see_more_like_this));
        header.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        ThemeManager.applyThemedTextStyle(header, ThemeManager.getSettingMenuTextColorNormal());
        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        header.setPadding(hPad, hPad, hPad, hPad / 2);
        soulseekActionSuggestionHost.addView(header);
        List<String> slice = SoulseekSearchSuggestions.rotatedSlice(
                soulseekActionSuggestionPool, soulseekActionSuggestionOffset, 4);
        for (final String q : slice) {
            Button b = createListButton(q);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    stopSoulseekActionRefresh();
                    startSoulseekReSearch(q);
                }
            });
            soulseekActionSuggestionHost.addView(b);
        }
    }

    private File reachCacheDir() {
        return ReachCache.dir(getCacheDir());
    }

    private boolean isReachTempFile(File f) {
        return ReachCache.isTempFile(getCacheDir(), f);
    }

    private void purgeUnreferencedReachCache() {
        purgeStreamTempFiles();
    }

    private void purgeStreamTempFiles() {
        StreamTempCache.purgeReach(getCacheDir(), playback.musicPlaylist(),
                reachGrowingCacheFile, reachQueuePartialFile);
        StreamTempCache.purgePodcastStream(getCacheDir(),
                podcastGrowingCacheFile, podcastGrowingCacheFinal);
    }

    private void updateMusicTrackCountUi() {
        if (tvPlayerTrackCount == null) return;
        if (playback.isPodcastActive()) {
            int idx = playback.podcastIndex();
            int total = playback.podcastQueue().size();
            if (idx < 0 || total <= 0) {
                tvPlayerTrackCount.setText("— / —");
            } else {
                tvPlayerTrackCount.setText(PlaybackCoordinator.formatTrackPosition(idx, total));
            }
            return;
        }
        int total = playback.musicPlaylist().size();
        if (!playback.isMusicActive() || total <= 0) {
            tvPlayerTrackCount.setText("— / —");
            return;
        }
        tvPlayerTrackCount.setText(
                PlaybackCoordinator.formatTrackPosition(playback.musicIndex(), total));
    }

    private void replaceReachFileInQueue(File oldF, File newF) {
        List<File> pl = playback.musicPlaylist();
        for (int i = 0; i < pl.size(); i++) {
            if (oldF.equals(pl.get(i))) pl.set(i, newF);
        }
        List<File> orig = playback.musicOriginal();
        for (int i = 0; i < orig.size(); i++) {
            if (oldF.equals(orig.get(i))) orig.set(i, newF);
        }
    }

    private void saveReachTrackToLibrary(final File src) {
        if (!isReachTempFile(src) || !src.isFile()) return;
        File dest = SoulseekClient.uniqueFile(rootFolder, src.getName());
        boolean moved = src.renameTo(dest);
        if (!moved) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                in.close();
                out.close();
                src.delete();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.soulseek_save_failed), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        replaceReachFileInQueue(src, dest);
        purgeUnreferencedReachCache();
        scanMediaLibraryAsync();
        Toast.makeText(this, getString(R.string.soulseek_saved_to_library), Toast.LENGTH_SHORT).show();
    }

    private void startSoulseekTransfer(final SoulseekClient.Result r, final int action) {
        if (!requireInternet(R.string.soulseek_wifi_required)) return;
        stopSoulseekActionRefresh();
        reachPartialPlaybackStarted = false;
        reachGrowingCacheFile = null;
        reachQueuePartialFile = null;
        reachGrowingPreparedBytes = 0;
        reachGrowingTotalBytes = 0;
        reachGrowingSeekMs = 0;
        progressHandler.removeCallbacks(reachGrowingEdgePoll);
        soulseekDownloadUiFailed = false;
        soulseekDownloadFailureReason = "";
        soulseekFailedResult = null;
        soulseekPendingAction = action;
        soulseekActiveDownload = r;
        soulseekLastProgressUiMs = 0;
        buildSoulseekDownloadUI(r, action);
        File dest = action == SOULSEEK_ACTION_SAVE ? rootFolder : reachCacheDir();
        ensureSoulseekClient().download(r, dest);
        updateSoulseekSharePolicy();
    }

    private boolean isReachCacheAction(int action) {
        return action == SOULSEEK_ACTION_PLAY || action == SOULSEEK_ACTION_QUEUE;
    }

    private boolean soulseekBusyUiMode() {
        return soulseekUiMode == SOULSEEK_UI_DOWNLOAD
                || soulseekUiMode == SOULSEEK_UI_ACTION;
    }

    private String humanizeSoulseekError(String msg) {
        if (msg == null || msg.isEmpty()) return getString(R.string.soulseek_error_unknown);
        if (msg.contains("EMFILE") || msg.contains("Too many peer")) {
            return getString(R.string.reach_error_too_many_peers);
        }
        if (msg.contains("Peer not reachable")) {
            return getString(R.string.soulseek_error_peer_unreachable);
        }
        if (msg.contains("Peer cannot connect") || msg.contains("CantConnectToPeer")) {
            return getString(R.string.soulseek_error_nat);
        }
        if (msg.contains("Transfer request timed out")) {
            return getString(R.string.soulseek_error_transfer_timeout);
        }
        if (msg.contains("File transfer timed out")) {
            return getString(R.string.soulseek_error_file_timeout);
        }
        if (msg.startsWith("Upload denied:")) return msg;
        return msg;
    }

    /** Stay on download screen — show why the transfer failed instead of jumping to results. */
    private void showSoulseekDownloadFailure(final SoulseekClient.Result failed, String reason) {
        if (failed == null) return;
        stopSoulseekDownloadUiRunnables();
        soulseekFailedResult = failed;
        soulseekFailedPendingAction = soulseekPendingAction;
        soulseekDownloadFailureReason = humanizeSoulseekError(reason);
        soulseekDownloadUiFailed = true;
        soulseekActiveDownload = null;
        soulseekPendingAction = 0;
        setBlockingLoading(false);
        buildSoulseekDownloadFailureUI(failed, soulseekDownloadFailureReason);
    }

    private void buildSoulseekDownloadFailureUI(final SoulseekClient.Result failed, final String reason) {
        soulseekUiMode = SOULSEEK_UI_DOWNLOAD;
        prepareSoulseekBrowserChrome();
        browserStatusTitle = getString(R.string.status_download_failed);
        updateStatusBarTitle();
        if (tvBrowserPath != null) {
            tvBrowserPath.setText(reason);
        }
        containerBrowserItems.removeAllViews();
        soulseekDownloadProgressBar = null;
        soulseekDownloadPercentText = null;
        soulseekDownloadDetailText = null;
        soulseekDownloadStatusRow = null;
        soulseekTryAnotherRow = null;

        Button titleRow = createListButton(failed.title());
        titleRow.setEnabled(false);
        containerBrowserItems.addView(titleRow);

        Button peerRow = createListButton(getString(R.string.soulseek_download_from, failed.username)
                + (failed.size > 0 ? " · " + formatSoulseekSize(failed.size) : ""));
        peerRow.setEnabled(false);
        containerBrowserItems.addView(peerRow);

        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        TextView errorBanner = new TextView(this);
        errorBanner.setText(getString(R.string.soulseek_download_failure_banner, reason));
        errorBanner.setTextColor(0xFFFF6666);
        errorBanner.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        errorBanner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        errorBanner.setPadding(hPad, hPad, hPad, hPad / 2);
        errorBanner.setFocusable(false);
        containerBrowserItems.addView(errorBanner);

        if (reason != null && (reason.contains("forward TCP port")
                || reason.contains("NAT") || reason.contains("not reachable"))) {
            int port = soulseekListenPort > 0 ? soulseekListenPort
                    : (soulseekClient != null ? soulseekClient.getListenPort() : 0);
            if (port > 0) {
                TextView portHint = new TextView(this);
                portHint.setText(getString(R.string.soulseek_port_warning, port));
                portHint.setTextColor(y1RowTextColorNormal(y1RowKindForScreen()));
                portHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f);
                portHint.setPadding(hPad, 0, hPad, hPad / 2);
                portHint.setFocusable(false);
                containerBrowserItems.addView(portHint);
            }
        }

        final List<SoulseekClient.Result> sameFile = soulseekSameFileAlternatives(failed);
        if (!sameFile.isEmpty()) {
            createBrowserSectionHeader(getString(R.string.soulseek_recovery_same_file_count,
                    failed.title(), sameFile.size()));
            int shown = 0;
            for (final SoulseekClient.Result alt : sameFile) {
                if (shown >= 5) break;
                Button b = makeSoulseekResultButton(alt);
                containerBrowserItems.addView(b);
                shown++;
            }
        }

        appendSoulseekReSearchRows(failed);

        Button retry = createListButton(getString(R.string.soulseek_retry_download));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                startSoulseekTransfer(failed, soulseekFailedPendingAction != 0
                        ? soulseekFailedPendingAction : SOULSEEK_ACTION_SAVE);
            }
        });
        containerBrowserItems.addView(retry);

        Button backResults = createListButton(getString(R.string.soulseek_back_results));
        backResults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                soulseekDownloadUiFailed = false;
                soulseekFailedResult = null;
                buildSoulseekResultsUI();
            }
        });
        containerBrowserItems.addView(backResults);

        Button searchAgain = createListButton(getString(R.string.soulseek_search_again));
        searchAgain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                soulseekDownloadUiFailed = false;
                soulseekFailedResult = null;
                openSoulseekSearchKeyboard();
            }
        });
        containerBrowserItems.addView(searchAgain);
        retry.requestFocus();
    }

    private void buildSoulseekDownloadUI(final SoulseekClient.Result r, final int action) {
        soulseekUiMode = SOULSEEK_UI_DOWNLOAD;
        soulseekDownloadUiFailed = false;
        soulseekDownloadFailureReason = "";
        soulseekDownloadStartMs = android.os.SystemClock.uptimeMillis();
        soulseekDownloadLastDone = 0;
        soulseekDownloadLastSpeedMs = soulseekDownloadStartMs;
        soulseekDownloadStalled = false;
        soulseekDownloadPhase = "Finding peer";
        soulseekDownloadPhaseDetail = r.username;
        soulseekTryAnotherRow = null;
        soulseekReSearchRowsShown = false;
        stopSoulseekDownloadUiRunnables();
        prepareSoulseekBrowserChrome();
        browserStatusTitle = action == SOULSEEK_ACTION_PLAY ? getString(R.string.status_buffering)
                : getString(R.string.status_downloading);
        updateStatusBarTitle();
        if (tvBrowserPath != null) {
            tvBrowserPath.setText(getString(R.string.reach_hint_download));
        }
        containerBrowserItems.removeAllViews();

        Button titleRow = createListButton(r.title());
        titleRow.setEnabled(false);
        containerBrowserItems.addView(titleRow);

        Button peerRow = createListButton(getString(R.string.soulseek_download_from, r.username)
                + (r.size > 0 ? " · " + formatSoulseekSize(r.size) : "")
                + (r.freeSlot ? " · " + getString(R.string.soulseek_slot_free) : ""));
        peerRow.setEnabled(false);
        containerBrowserItems.addView(peerRow);

        int hPad = (int) (10 * getResources().getDisplayMetrics().density);
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        progressRow.setPadding(hPad, hPad / 2, hPad, hPad / 2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, y1RowHeightPx);
        progressRow.setLayoutParams(rowLp);

        soulseekDownloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        soulseekDownloadProgressBar.setMax(100);
        soulseekDownloadProgressBar.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, y1RowHeightPx / 2, 1f);
        barLp.rightMargin = hPad;
        soulseekDownloadProgressBar.setLayoutParams(barLp);
        progressRow.addView(soulseekDownloadProgressBar);

        soulseekDownloadPercentText = new TextView(this);
        soulseekDownloadPercentText.setTextColor(y1RowTextColorNormal(y1RowKindForScreen()));
        soulseekDownloadPercentText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size));
        soulseekDownloadPercentText.setText(action == SOULSEEK_ACTION_PLAY
                ? getString(R.string.soulseek_buffering, 0)
                : getString(R.string.soulseek_download_progress, 0));
        progressRow.addView(soulseekDownloadPercentText);
        containerBrowserItems.addView(progressRow);

        soulseekDownloadDetailText = new TextView(this);
        soulseekDownloadDetailText.setTextColor(y1RowTextColorNormal(y1RowKindForScreen()));
        soulseekDownloadDetailText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.y1_menu_text_size) * 0.85f);
        soulseekDownloadDetailText.setPadding(hPad, 0, hPad, hPad / 2);
        String totalLabel = r.size > 0 ? formatSoulseekSize(r.size) : "?";
        soulseekDownloadDetailText.setText(getString(R.string.soulseek_download_detail,
                "0 B", totalLabel, getString(R.string.soulseek_download_speed_unknown),
                getString(R.string.soulseek_download_eta_pending)));
        containerBrowserItems.addView(soulseekDownloadDetailText);

        soulseekDownloadStatusRow = createListButton(formatSoulseekDownloadPhase(
                soulseekDownloadPhase, soulseekDownloadPhaseDetail));
        soulseekDownloadStatusRow.setEnabled(false);
        containerBrowserItems.addView(soulseekDownloadStatusRow);

        soulseekTryAnotherRow = createListButton(getString(R.string.soulseek_try_another_result));
        soulseekTryAnotherRow.setVisibility(View.GONE);
        soulseekTryAnotherRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                cancelSoulseekDownload();
            }
        });
        containerBrowserItems.addView(soulseekTryAnotherRow);

        Button cancel = createListButton(getString(R.string.soulseek_cancel_download));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                cancelSoulseekDownload();
            }
        });
        containerBrowserItems.addView(cancel);
        cancel.requestFocus();

        soulseekUiHandler.postDelayed(soulseekStallWatchRunnable, SOULSEEK_STALL_MS);
        soulseekUiHandler.postDelayed(soulseekDownloadTickRunnable, 1000);
    }

    private void stopSoulseekDownloadUiRunnables() {
        soulseekUiHandler.removeCallbacks(soulseekStallWatchRunnable);
        soulseekUiHandler.removeCallbacks(soulseekDownloadTickRunnable);
    }

    private void showSoulseekTryAnotherRow(boolean show) {
        if (soulseekTryAnotherRow == null) return;
        soulseekTryAnotherRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && !soulseekReSearchRowsShown && soulseekActiveDownload != null) {
            appendSoulseekReSearchRows(soulseekActiveDownload);
            soulseekReSearchRowsShown = true;
        }
    }

    private String formatSoulseekElapsed(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m " + (sec % 60) + "s";
    }

    private String formatSoulseekDownloadPhase(String phase, String detail) {
        if (phase == null || phase.length() == 0) {
            return getString(R.string.soulseek_queued);
        }
        String d = detail != null ? detail : "";
        boolean peerDetail = soulseekActiveDownload != null
                && d.equalsIgnoreCase(soulseekActiveDownload.username);
        String peerLabel = peerDetail ? getString(R.string.soulseek_from_user, d) : d;
        if ("Finding peer".equals(phase)) {
            return getString(R.string.soulseek_phase_finding_peer, peerLabel);
        }
        if ("Connecting".equals(phase)) {
            return getString(R.string.soulseek_phase_connecting, peerLabel);
        }
        if ("Queued".equals(phase)) {
            return peerDetail
                    ? getString(R.string.soulseek_phase_queued_user, peerLabel)
                    : getString(R.string.soulseek_phase_queued, d);
        }
        if ("Receiving".equals(phase)) {
            return peerDetail
                    ? getString(R.string.soulseek_phase_receiving_user, peerLabel)
                    : getString(R.string.soulseek_phase_receiving, d);
        }
        if ("Retrying".equals(phase)) {
            return getString(R.string.soulseek_phase_retrying, peerDetail ? peerLabel : d);
        }
        if ("Complete".equals(phase)) {
            return getString(R.string.soulseek_phase_complete, d);
        }
        if (d.length() > 0) return phase + " · " + d;
        return phase;
    }

    private void updateSoulseekDownloadStatusUi() {
        if (soulseekUiMode != SOULSEEK_UI_DOWNLOAD || soulseekActiveDownload == null) return;
        long elapsed = android.os.SystemClock.uptimeMillis() - soulseekDownloadStartMs;
        String phaseLine = formatSoulseekDownloadPhase(soulseekDownloadPhase, soulseekDownloadPhaseDetail);
        if (soulseekDownloadLastDone <= 0) {
            phaseLine = phaseLine + " — " + getString(R.string.soulseek_waiting_elapsed,
                    formatSoulseekElapsed(elapsed));
        }
        if (soulseekDownloadStalled && soulseekDownloadLastDone <= 0) {
            phaseLine = getString(R.string.soulseek_stall_hint, formatSoulseekElapsed(elapsed))
                    + "\n" + phaseLine;
        }
        if (soulseekDownloadStatusRow != null) soulseekDownloadStatusRow.setText(phaseLine);
        if (tvBrowserPath != null) {
            int pct = soulseekDownloadProgressBar != null ? soulseekDownloadProgressBar.getProgress() : 0;
            String verb = soulseekPendingAction == SOULSEEK_ACTION_PLAY ? "Buffering" : "Downloading";
            if (pct > 0) {
                tvBrowserPath.setText(getString(R.string.path_soulseek_action,
                        verb, soulseekActiveDownload.title(), pct));
            } else {
                tvBrowserPath.setText(getString(R.string.path_soulseek, phaseLine.replace('\n', ' ')));
            }
        }
    }

    private void cancelSoulseekDownload() {
        if (soulseekDownloadUiFailed) {
            soulseekDownloadUiFailed = false;
            soulseekFailedResult = null;
            buildSoulseekResultsUI();
            return;
        }
        if (soulseekBailWithoutConfirm()) {
            cancelSoulseekDownloadSilent();
            soulseekDownloadStalled = false;
            if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
            return;
        }
        if (hasActiveReachDownload() && !reachPartialPlaybackStarted) {
            confirmStopReachDownload(new Runnable() {
                @Override
                public void run() {
                    if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
                }
            });
            return;
        }
        cancelSoulseekDownloadSilent();
        if (currentScreenState == STATE_SOULSEEK) buildSoulseekResultsUI();
    }

    private void cancelSoulseekDownloadSilent() {
        stopSoulseekDownloadUiRunnables();
        if (soulseekClient != null) soulseekClient.cancelDownload();
        soulseekActiveDownload = null;
        soulseekDownloadProgressBar = null;
        soulseekDownloadPercentText = null;
        soulseekDownloadDetailText = null;
        soulseekDownloadStatusRow = null;
        soulseekTryAnotherRow = null;
        soulseekReSearchRowsShown = false;
        soulseekPendingAction = 0;
        reachPartialPlaybackStarted = false;
        reachGrowingCacheFile = null;
        reachQueuePartialFile = null;
        reachGrowingPreparedBytes = 0;
        reachGrowingTotalBytes = 0;
        progressHandler.removeCallbacks(reachGrowingEdgePoll);
        purgeUnreferencedReachCache();
        updateSoulseekSharePolicy();
    }

    private boolean reachDownloadInProgress() {
        return soulseekActiveDownload != null
                && soulseekClient != null && soulseekClient.isTransferActive();
    }

    private void clearReachStreamAlbumArt() {
        lastAlbumArtBytes = null;
        if (ivAlbumArt != null) ivAlbumArt.setImageResource(R.drawable.default_album);
        if (ivPlayerBgBlur != null) ivPlayerBgBlur.setImageResource(0);
    }

    private void tryReachId3FromPartial(File partial) {
        if (partial == null || !partial.isFile() || partial.length() < REACH_ID3_MIN_BYTES) return;
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            java.io.FileInputStream fis = new java.io.FileInputStream(partial);
            mmr.setDataSource(fis.getFD());
            byte[] art = mmr.getEmbeddedPicture();
            fis.close();
            mmr.release();
            if (art != null && art.length > 0) {
                lastAlbumArtBytes = art;
                applyReachStreamCoverArt();
            }
        } catch (Exception ignored) {}
    }

    private void applyReachStreamCoverArt() {
        if (lastAlbumArtBytes == null || lastAlbumArtBytes.length == 0) return;
        try {
            BitmapFactory.Options optsCenter = new BitmapFactory.Options();
            optsCenter.inSampleSize = 2;
            Bitmap bmpCenter = BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, optsCenter);
            if (ivAlbumArt != null) ivAlbumArt.setImageBitmap(bmpCenter);
            if (playerAlbumBlurEnabled) {
                BitmapFactory.Options optsBg = new BitmapFactory.Options();
                optsBg.inSampleSize = 4;
                Bitmap sourceBg = BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, optsBg);
                if (sourceBg != null && ivPlayerBgBlur != null) {
                    Bitmap blurredBg = applyGaussianBlur(sourceBg);
                    ivPlayerBgBlur.setImageBitmap(blurredBg);
                    if (sourceBg != blurredBg) sourceBg.recycle();
                }
            }
            updateMainMenuBackground();
            refreshNowPlayingPreview();
        } catch (Exception ignored) {}
    }

    private void startReachPlayFromPartial(File partial) {
        saveCurrentPodcastResume();
        stopPodcastDownloadFully();
        clearReachStreamAlbumArt();
        List<File> one = new ArrayList<File>();
        one.add(partial);
        playback.activateMusic(one, 0, isShuffleMode);
        playback.setMusicActivePlaylistName(null);
        purgeUnreferencedReachCache();
        if (!playback.musicPlaylist().isEmpty()) {
            tvPlayerTitle.setText(partial.getName());
            tvPlayerArtist.setText(getString(R.string.reach_buffering_track));
            playerProgress.setProgress(0);
            tvPlayerTimeCurrent.setText("00:00");
            tvPlayerTimeTotal.setText("00:00");
            updateMusicTrackCountUi();
        }
        isPausedByHand = false;
        playerReturnScreen = STATE_SOULSEEK;
        applyScreenChange(STATE_PLAYER);
        startReachFromGrowingFile(partial, 0);
    }

    private void maybeExtendReachGrowingPlayback(final boolean force) {
        if (!playback.isMusicActive() || reachGrowingCacheFile == null || !reachGrowingCacheFile.isFile()) return;
        if (reachGrowingReprepareInFlight) {
            progressHandler.postDelayed(reachGrowingEdgePoll, 250);
            return;
        }
        final long fileLen = reachGrowingCacheFile.length();
        if (fileLen <= 0) {
            if (reachDownloadInProgress()) progressHandler.postDelayed(reachGrowingEdgePoll, 400);
            return;
        }
        boolean playing = false;
        int positionMs = 0;
        int durationMs = 0;
        try {
            if (mediaPlayer != null) {
                playing = mediaPlayer.isPlaying();
                positionMs = mediaPlayer.getCurrentPosition();
                durationMs = mediaPlayer.getDuration();
            }
        } catch (Exception ignored) {}
        long growth = fileLen - reachGrowingPreparedBytes;
        if (!force) {
            if (playing) {
                if (durationMs <= 0 || positionMs < durationMs - REACH_GROWING_EXTEND_MS) return;
                if (growth < REACH_GROWING_MIN_GROW_BYTES && reachDownloadInProgress()) {
                    progressHandler.postDelayed(reachGrowingEdgePoll, 300);
                    return;
                }
            } else if (growth < REACH_GROWING_MIN_GROW_BYTES && reachDownloadInProgress()) {
                progressHandler.postDelayed(reachGrowingEdgePoll, 300);
                return;
            }
        }
        reachGrowingSeekMs = positionMs;
        startReachFromGrowingFile(reachGrowingCacheFile, reachGrowingSeekMs);
    }

    private void startReachFromGrowingFile(File growingFile, int seekMs) {
        try {
            progressHandler.removeCallbacks(reachGrowingEdgePoll);
            reachGrowingReprepareInFlight = true;
            reachGrowingSeekMs = seekMs;
            reachGrowingPreparedBytes = growingFile.length();
            int previousSessionId = mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            else mediaPlayer.reset();
            if (previousSessionId != 0) {
                try { mediaPlayer.setAudioSessionId(previousSessionId); } catch (Exception ignored) {}
            }
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (reachDownloadInProgress() && reachGrowingCacheFile != null) {
                        progressHandler.postDelayed(reachGrowingEdgePoll, 200);
                        return;
                    }
                    nextTrack();
                }
            });
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    reachGrowingReprepareInFlight = false;
                    int dur = mp.getDuration();
                    if (reachPartialPlaybackStarted) {
                        int est = reachGrowingDisplayDurationMs();
                        if (est > dur) dur = est;
                    }
                    if (dur > 0) tvPlayerTimeTotal.setText(formatTime(dur));
                    updateReachGrowingDurationUi();
                    updateMusicTrackCountUi();
                    int growingSeek = reachGrowingSeekMs;
                    reachGrowingSeekMs = 0;
                    if (growingSeek > 0) {
                        mp.seekTo(growingSeek);
                        tvPlayerTimeCurrent.setText(formatTime(growingSeek));
                    }
                    if (!isPausedByHand) mp.start();
                    updatePlayerStatusIndicators();
                    clearReachLoadingArtistLabel();
                    if (reachDownloadInProgress()) {
                        progressHandler.postDelayed(reachGrowingEdgePoll, 300);
                    }
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    reachGrowingReprepareInFlight = false;
                    if (reachPartialPlaybackStarted && reachDownloadInProgress()) {
                        progressHandler.postDelayed(reachGrowingEdgePoll, 400);
                        return true;
                    }
                    return false;
                }
            });
            mediaPlayer.setDataSource(growingFile.getAbsolutePath());
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            reachGrowingReprepareInFlight = false;
            if (!reachPartialPlaybackStarted) {
                Toast.makeText(this, getString(R.string.toast_corrupted_file), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean soulseekBailWithoutConfirm() {
        return soulseekDownloadStalled || soulseekDownloadUiFailed;
    }

    private void startSoulseekReSearch(String query) {
        cancelSoulseekDownloadSilent();
        soulseekDownloadStalled = false;
        soulseekDownloadUiFailed = false;
        soulseekFailedResult = null;
        fetchSoulseekResults(query);
    }

    private void appendSoulseekReSearchRows(SoulseekClient.Result r) {
        if (r == null) return;
        List<String> queries = SoulseekSearchSuggestions.reSearchQueries(r);
        if (queries.isEmpty()) return;
        createBrowserSectionHeader(getString(R.string.soulseek_research_section));
        int shown = 0;
        for (final String q : queries) {
            if (shown >= 4) break;
            Button b = createListButton(q);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    startSoulseekReSearch(q);
                }
            });
            containerBrowserItems.addView(b);
            shown++;
        }
    }

    /** Keep Reach download + client alive while streaming on Now Playing (like podcast handoff). */
    boolean keepReachStreamHandoffForScreen(int targetState) {
        return targetState == STATE_PLAYER && reachPartialPlaybackStarted && hasActiveReachDownload();
    }

    void pauseSoulseekUiOnly() {
        soulseekUiHandler.removeCallbacks(soulseekUiFlushRunnable);
        stopSoulseekDownloadUiRunnables();
        soulseekUiFlushScheduled = false;
        if (soulseekClient != null) soulseekClient.cancelSearch();
    }

    boolean shouldDeferSoulseekOffScreenCleanup() {
        return reachPartialPlaybackStarted
                && (reachDownloadInProgress() || currentScreenState == STATE_PLAYER);
    }

    void finalizeReachStreamHandoff() {
        if (!reachPartialPlaybackStarted) return;
        reachPartialPlaybackStarted = false;
        reachGrowingCacheFile = null;
        reachGrowingPreparedBytes = 0;
        reachGrowingTotalBytes = 0;
        reachGrowingSeekMs = 0;
        reachQueuePartialFile = null;
        progressHandler.removeCallbacks(reachGrowingEdgePoll);
        purgeStreamTempFiles();
        if (!isSoulseekUiActive()) soulseekOffScreenCleanup();
    }

    void teardownSoulseekSession() {
        soulseekUiHandler.removeCallbacks(soulseekUiFlushRunnable);
        stopSoulseekDownloadUiRunnables();
        soulseekUiFlushScheduled = false;
        if (soulseekClient != null) soulseekClient.cancelSearch();
        cancelSoulseekDownloadSilent();
        shutdownSoulseekClient();
        soulseekSearchInProgress = false;
        soulseekPendingUi.clear();
    }

    void shutdownSoulseekClient() {
        if (soulseekClient != null) {
            soulseekClient.shutdown();
            soulseekClient = null;
        }
    }

    void soulseekOffScreenCleanup() {
        soulseekUiHandler.removeCallbacks(soulseekUiFlushRunnable);
        stopSoulseekDownloadUiRunnables();
        soulseekUiFlushScheduled = false;
        cancelSoulseekDownloadSilent();
        if (soulseekClient != null) {
            soulseekClient.cancelSearch();
            soulseekClient.shutdown();
            soulseekClient = null;
        }
    }

    void teardownBluetoothSession() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {}
    }

    void teardownWifiSession() {
        // ponytail: no persistent Wi-Fi thread; broadcast handlers gated by screen state
    }

    void teardownPodcastSession() {
        stopPodcastDownloadFully();
        StreamTempCache.purgePodcastStream(getCacheDir(), null, null);
        podcastGrowingCacheFile = null;
        podcastGrowingCacheFinal = null;
        podcastUiGen++;
        setBlockingLoading(false);
        podcastProbeStatusRow = null;
        podcastEpisodeProbeState = null;
        podcastEpisodeProbeSource = null;
        podcastEpisodeFlushPtr = 0;
    }

    void teardownSettingsSession() {
        ++unifiedThemesUiGen;
        themeCatalogLoading = false;
        themeBrowserOnlineRows.clear();
        themeDownloadGen++;
        com.solar.launcher.theme.ThemeDownloader.requestCancel();
        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);
    }

    void teardownBrowserSession() {
        libraryScanGen++;
        cancelReachDownloadIfAny(false);
    }

    void sessionSweepHandlers(int from, int to) {
        progressHandler.removeCallbacks(podcastGrowingEdgePoll);
        if (!keepReachStreamHandoffForScreen(to)) {
            progressHandler.removeCallbacks(reachGrowingEdgePoll);
        }
        fastScrollHandler.removeCallbacks(hideFastScrollTask);
        if (to == STATE_BROWSER) {
            cancelReachDownloadIfAny(false);
        }
    }

    private boolean hasActiveReachDownload() {
        if (soulseekDownloadUiFailed) return false;
        if (soulseekActiveDownload != null) return true;
        return soulseekClient != null && soulseekClient.isTransferActive();
    }

    /** Block leaving Reach while downloading — except hand-off to Now Playing during stream. */
    private boolean shouldConfirmReachDownloadLeave(int targetState) {
        if (!hasActiveReachDownload()) return false;
        if (targetState == STATE_PLAYER && reachPartialPlaybackStarted) return false;
        if (currentScreenState == STATE_PLAYER && reachPartialPlaybackStarted
                && targetState == STATE_SOULSEEK) {
            return false;
        }
        return true;
    }

    private void updateReachGrowingDurationUi() {
        updateReachGrowingTimeUi();
    }

    private int reachGrowingDisplayDurationMs() {
        try {
            if (mediaPlayer == null) return 0;
            int mpDur = mediaPlayer.getDuration();
            if (mpDur <= 0 || reachGrowingCacheFile == null) return mpDur;
            long fileLen = reachGrowingCacheFile.length();
            if (reachGrowingTotalBytes > fileLen && fileLen > 0) {
                return (int) Math.min(Integer.MAX_VALUE, (long) mpDur * reachGrowingTotalBytes / fileLen);
            }
            return mpDur;
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateReachGrowingTimeUi() {
        if (tvPlayerTimeTotal == null) return;
        int dur = reachGrowingDisplayDurationMs();
        if (dur <= 0) return;
        tvPlayerTimeTotal.setText(formatTime(dur));
        if (mediaPlayer != null) {
            try {
                int current = mediaPlayer.getCurrentPosition();
                if (playerProgress != null && dur > 0) {
                    playerProgress.setProgress(Math.min(100, current * 100 / dur));
                }
                if (tvPlayerTimeCurrent != null) tvPlayerTimeCurrent.setText(formatTime(current));
            } catch (Exception ignored) {}
        }
    }

    private void updateReachPlayerBufferUi(int pct, long done, long total) {
        if (tvPlayerArtist == null) return;
        if (pct >= 95 || !reachDownloadInProgress()) {
            clearReachLoadingArtistLabel();
        } else {
            tvPlayerArtist.setText(getString(R.string.reach_buffering_track));
        }
        updateReachGrowingDurationUi();
    }

    private void clearReachLoadingArtistLabel() {
        if (tvPlayerArtist == null) return;
        CharSequence t = tvPlayerArtist.getText();
        if (t == null || t.length() == 0) return;
        String s = t.toString();
        if (s.equals(getString(R.string.reach_loading_track))
                || s.equals(getString(R.string.reach_buffering_track))
                || s.startsWith("Buffering")) {
            tvPlayerArtist.setText("");
        }
    }

    private void confirmStopReachDownload(final Runnable onConfirmed) {
        showThemedConfirm(
                getString(R.string.reach_stop_download_title),
                getString(R.string.reach_stop_download_message),
                getString(R.string.reach_stop_download_confirm),
                getString(R.string.common_cancel),
                new Runnable() {
                    @Override
                    public void run() {
                        cancelReachDownloadIfAny(false);
                        if (onConfirmed != null) onConfirmed.run();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        pendingScreenChange = -1;
                    }
                });
    }

    /** @param confirm when true, ask before cancelling an active transfer */
    void cancelReachDownloadIfAny(boolean confirm) {
        if (!hasActiveReachDownload()) return;
        if (confirm) {
            confirmStopReachDownload(null);
            return;
        }
        cancelSoulseekDownloadSilent();
        soulseekDownloadUiFailed = false;
        soulseekFailedResult = null;
    }

    private void pausePodcastBackgroundDownload() {
        if (!podcastDownloadInProgress && !podcastPartialPlaybackStarted) return;
        if (podcastDownloadInProgress) {
            podcastDownloadCancel.set(true);
            podcastDownloadInProgress = false;
            podcastDownloadPaused = true;
        }
        progressHandler.removeCallbacks(podcastGrowingEdgePoll);
        saveCurrentPodcastResume();
        updatePodcastPartialDurationUi();
    }

    private void stopPodcastDownloadFully() {
        podcastDownloadCancel.set(true);
        podcastDownloadCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        podcastDownloadInProgress = false;
        podcastDownloadPaused = false;
        podcastPartialPlaybackStarted = false;
        podcastRecoveryInFlight = false;
        progressHandler.removeCallbacks(podcastGrowingEdgePoll);
        ++podcastLoadGeneration;
    }

    private void maybeResumePodcastDownload() {
        if (!podcastDownloadPaused || podcastDownloadInProgress || !playback.isPodcastActive()) return;
        if (!ConnectivityHelper.isOnline(this)) return;
        if (podcastGrowingCacheFile == null || !podcastGrowingCacheFile.isFile()) return;
        if (podcastGrowingCacheFinal != null && podcastGrowingCacheFinal.isFile()) return;
        long partial = podcastGrowingCacheFile.length();
        if (podcastDownloadBytesTotal > 0 && partial >= podcastDownloadBytesTotal) return;
        podcastDownloadPaused = false;
        podcastDownloadCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        resumePodcastDownload(playback.podcastIndex(),
                playback.podcastQueue().get(playback.podcastIndex()), podcastLoadGeneration);
    }

    private int podcastAvailableDurationMs() {
        long bytes = 0;
        if (podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()) {
            bytes = podcastGrowingCacheFile.length();
        } else if (podcastDownloadBytesRead > 0) {
            bytes = podcastDownloadBytesRead;
        }
        if (bytes <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, OpenRssClient.msFromBytes(bytes));
    }

    private void updatePodcastPartialDurationUi() {
        if (!playback.isPodcastActive() || tvPlayerTimeTotal == null) return;
        int avail = podcastAvailableDurationMs();
        if (avail <= 0) return;
        tvPlayerTimeTotal.setText(formatTime(avail));
        try {
            if (mediaPlayer != null && tvPlayerTimeCurrent != null) {
                int pos = Math.min(mediaPlayer.getCurrentPosition(), avail);
                tvPlayerTimeCurrent.setText(formatTime(pos));
                if (playerProgress != null) {
                    playerProgress.setProgress((int) ((long) pos * 100 / avail));
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateSoulseekDownloadProgress(int pct, long done, long total) {
        if (soulseekDownloadProgressBar != null) soulseekDownloadProgressBar.setProgress(pct);
        if (soulseekDownloadPercentText != null) {
            soulseekDownloadPercentText.setText(soulseekPendingAction == SOULSEEK_ACTION_PLAY
                    ? getString(R.string.soulseek_buffering, pct)
                    : getString(R.string.soulseek_download_progress, pct));
        }
        if (soulseekDownloadDetailText != null) {
            long now = android.os.SystemClock.uptimeMillis();
            String speed = getString(R.string.soulseek_download_speed_unknown);
            String eta = getString(R.string.soulseek_download_eta_pending);
            if (done > soulseekDownloadLastDone && now > soulseekDownloadLastSpeedMs) {
                long dt = now - soulseekDownloadLastSpeedMs;
                long dd = done - soulseekDownloadLastDone;
                if (dt > 0 && dd > 0) {
                    long bps = dd * 1000 / dt;
                    speed = formatSoulseekSpeed(bps);
                    if (bps > 0 && total > done) {
                        long sec = (total - done) / bps;
                        eta = sec < 3600
                                ? getString(R.string.soulseek_download_eta, sec + "s")
                                : getString(R.string.soulseek_download_eta, (sec / 60) + "m");
                    } else if (done >= total && total > 0) {
                        eta = getString(R.string.soulseek_download_eta_done);
                    }
                }
                soulseekDownloadLastDone = done;
                soulseekDownloadLastSpeedMs = now;
            }
            String doneLabel = formatSoulseekSize(done);
            String totalLabel = total > 0 ? formatSoulseekSize(total) : "?";
            soulseekDownloadDetailText.setText(getString(R.string.soulseek_download_detail,
                    doneLabel, totalLabel, speed, eta));
        }
        if (tvBrowserPath != null && soulseekActiveDownload != null) {
            String verb = soulseekPendingAction == SOULSEEK_ACTION_PLAY ? "Buffering" : "Downloading";
            tvBrowserPath.setText(getString(R.string.path_soulseek_action, verb, soulseekActiveDownload.title(), pct));
        }
    }

    private void updateSoulseekMoreRow(int totalRanked) {
        if (soulseekSearchInProgress) {
            if (soulseekMoreRow != null) {
                containerBrowserItems.removeView(soulseekMoreRow);
                soulseekMoreRow = null;
            }
            return;
        }
        if (soulseekResultUiCount >= totalRanked) {
            if (soulseekMoreRow != null) {
                containerBrowserItems.removeView(soulseekMoreRow);
                soulseekMoreRow = null;
            }
            return;
        }
        String label = getString(R.string.soulseek_show_more, soulseekResultUiCount, totalRanked);
        if (soulseekMoreRow == null) {
            soulseekMoreRow = createListButton(label);
            soulseekMoreRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    soulseekResultsVisibleCount += SOULSEEK_PAGE_SIZE;
                    refreshSoulseekVisibleResults(true);
                }
            });
            containerBrowserItems.addView(soulseekMoreRow);
        } else {
            soulseekMoreRow.setText(label);
        }
    }

    private void updateSoulseekPathCount(boolean finished) {
        if (tvBrowserPath == null) return;
        String phase = finished ? "Results" : "Searching";
        tvBrowserPath.setText(getString(R.string.path_soulseek_phase, phase, soulseekLastQuery, soulseekResults.size()));
    }

    private void finalizeSoulseekSearch(int count) {
        if (count == 0 && soulseekResultUiCount == 0) {
            if (soulseekSearchStatusRow != null) {
                soulseekSearchStatusRow.setText(getString(R.string.soulseek_no_results));
            }
        } else if (soulseekSearchStatusRow != null) {
            containerBrowserItems.removeView(soulseekSearchStatusRow);
            soulseekSearchStatusRow = null;
        }
        updateSoulseekMoreRow(soulseekRankedResultCount());
        updateSoulseekPathCount(true);
    }

    private boolean soulseekResultAllowed(SoulseekClient.Result r) {
        if (r == null) return false;
        if (soulseekClient != null && soulseekClient.isPeerDenied(r.username)) return false;
        if (soulseekHideFlac && SoulseekClient.Result.isFlacFile(r.filename)) return false;
        return true;
    }

    private List<SoulseekClient.Result> soulseekSameFileAlternatives(SoulseekClient.Result failed) {
        List<SoulseekClient.Result> out = new ArrayList<SoulseekClient.Result>();
        if (failed == null) return out;
        String base = failed.title().toLowerCase(Locale.US);
        for (SoulseekClient.Result r : soulseekResults) {
            if (!soulseekResultAllowed(r)) continue;
            if (failed.username.equalsIgnoreCase(r.username)
                    && failed.filename.equals(r.filename)) continue;
            if (!r.title().equalsIgnoreCase(base)) continue;
            out.add(r);
        }
        sortSoulseekResultsByQuality(out);
        return out;
    }

    private void buildSoulseekResultsUI() {
        soulseekUiMode = SOULSEEK_UI_RESULTS;
        soulseekResultUiCount = 0;
        soulseekSearchStatusRow = null;
        soulseekMoreRow = null;
        soulseekResultKeys.clear();
        for (SoulseekClient.Result r : soulseekResults) {
            soulseekResultKeys.add(soulseekResultKey(r));
        }
        prepareSoulseekBrowserChrome();
        browserStatusTitle = getString(R.string.status_soulseek_results);
        updateStatusBarTitle();
        if (tvBrowserPath != null) tvBrowserPath.setText(getString(R.string.path_soulseek_results, soulseekLastQuery));
        containerBrowserItems.removeAllViews();

        Button back = createListButton(getString(R.string.soulseek_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoulseekSearchUI();
            }
        });
        containerBrowserItems.addView(back);

        Button searchAgain = createListButton(getString(R.string.soulseek_search_again));
        searchAgain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                openSoulseekSearchKeyboard();
            }
        });
        containerBrowserItems.addView(searchAgain);

        if (soulseekResults.isEmpty()) {
            Button empty = createListButton(getString(R.string.soulseek_empty_results));
            empty.setEnabled(false);
            containerBrowserItems.addView(empty);
        } else {
            sortSoulseekResultsByQuality(soulseekResults);
            updateReachBrowserHint(R.string.reach_hint_results);
            soulseekResultUiCount = 0;
            appendSoulseekResultRowsInner();
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    private void finishSoulseekUserEntry() {
        pendingSoulseekUser = typedPassword.trim();
        if (pendingSoulseekUser.isEmpty()) {
            SoulseekAccount.resetToAuto(prefs);
            if (soulseekClient != null) {
                soulseekClient.shutdown();
                soulseekClient = null;
            }
            Toast.makeText(this, getString(R.string.soulseek_auto_account), Toast.LENGTH_LONG).show();
            restoreSettingsAfterSoulseekAccount();
            return;
        }
        if (!SoulseekAccount.isValidUsername(pendingSoulseekUser)) {
            Toast.makeText(this, getString(R.string.soulseek_invalid_username), Toast.LENGTH_LONG).show();
            return;
        }
        keyboardPurpose = KEYBOARD_SOULSEEK_PASS;
        typedPassword = "";
        openKeyboard();
    }

    private void finishSoulseekPassEntry() {
        if (pendingSoulseekUser.isEmpty()) {
            restoreSettingsAfterSoulseekAccount();
            return;
        }
        if (typedPassword.isEmpty()) {
            Toast.makeText(this, getString(R.string.soulseek_password_required), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SoulseekAccount.isValidUsername(pendingSoulseekUser)) {
            Toast.makeText(this, getString(R.string.soulseek_invalid_username), Toast.LENGTH_LONG).show();
            return;
        }
        final String user = pendingSoulseekUser;
        final String pass = typedPassword;
        Toast.makeText(this, getString(R.string.soulseek_testing_login), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = SoulseekClient.testLogin(user, pass);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            Toast.makeText(MainActivity.this, getString(R.string.soulseek_login_failed, err),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        SoulseekAccount.saveCustom(prefs, user, pass);
                        if (soulseekClient != null) {
                            soulseekClient.shutdown();
                            soulseekClient = null;
                        }
                        Toast.makeText(MainActivity.this, getString(R.string.soulseek_account_saved),
                                Toast.LENGTH_SHORT).show();
                        restoreSettingsAfterSoulseekAccount();
                    }
                });
            }
        }, "SoulseekLoginTest").start();
    }

    private void scanMediaLibraryAsync() {
        if (isCustomScanning) return;
        isCustomScanning = true;
        final int gen = libraryScanGen;
        activeLibraryScanGen = gen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                customLibrary.clear();
                libraryScanPaths.clear();
                libraryScanMetaKeys.clear();
                buildCustomLibrary(rootFolder);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isCustomScanning = false;
                    }
                });
            }
        }).start();
    }

    private boolean isWifiConnectedForStream() {
        return hasInternetConnection();
    }

    private void saveCurrentPodcastResume() {
        if (!playback.isPodcastActive() || mediaPlayer == null || podcastResumeKey.isEmpty()) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            if (pos <= 0) return;
            PodcastResumeStore.save(getApplicationContext(), podcastResumeKey, pos, podcastResumeDurationForSave());
        } catch (Exception ignored) {}
    }

    /** Duration for resume save/restore — capped to downloaded bytes when partial/offline. */
    private int podcastResumeDurationForSave() {
        try {
            if (mediaPlayer == null) return 0;
            int mpDur = mediaPlayer.getDuration();
            if (mpDur < 0) mpDur = 0;
            int avail = podcastAvailableDurationMs();
            if (podcastDownloadPaused || (avail > 0 && !podcastDownloadInProgress
                    && podcastGrowingCacheFile != null)) {
                if (avail > 0) return avail;
            }
            int est = 0;
            if (podcastGrowingCacheFile != null
                    && (podcastDownloadInProgress || podcastPartialPlaybackStarted)) {
                est = podcastGrowingDisplayDurationMs();
            } else if (podcastDownloadBytesTotal > 0) {
                est = (int) Math.min(Integer.MAX_VALUE,
                        OpenRssClient.msFromBytes(podcastDownloadBytesTotal));
            }
            if (est > 0 && mpDur > 0) return Math.max(mpDur, est);
            if (est > 0) return est;
            return mpDur;
        } catch (Exception e) {
            return 0;
        }
    }

    private void flushPodcastResumeIfNeeded() {
        saveCurrentPodcastResume();
        lastPodcastResumeSaveMs = System.currentTimeMillis();
    }

    private String podcastResumeKeyForEpisode(OpenRssClient.Episode ep, File savedFile) {
        String show = podcastSelected != null ? podcastSelected.title : playback.podcastShowTitle();
        return PodcastResumeStore.keyForEpisode(show, ep.title, ep.audioUrl, savedFile);
    }

    private boolean episodesAllOnDisk(List<OpenRssClient.Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) return false;
        String show = podcastSelected != null ? podcastSelected.title : playback.podcastShowTitle();
        for (OpenRssClient.Episode ep : episodes) {
            File f = PodcastLibrary.resolveEpisodeFile(ep, show);
            if (f == null) return false;
        }
        return true;
    }

    private void startPodcastPlayback(List<OpenRssClient.Episode> episodes, int index) {
        startPodcastPlayback(episodes, index, false);
    }

    private boolean episodeOnDisk(List<OpenRssClient.Episode> episodes, int index) {
        if (episodes == null || index < 0 || index >= episodes.size()) return false;
        String show = podcastSelected != null ? podcastSelected.title : playback.podcastShowTitle();
        return PodcastLibrary.resolveEpisodeFile(episodes.get(index), show) != null;
    }

    private void startPodcastPlayback(List<OpenRssClient.Episode> episodes, int index, boolean fromSavedLibrary) {
        if (episodes == null || episodes.isEmpty()) return;
        boolean offline = fromSavedLibrary || episodesAllOnDisk(episodes) || episodeOnDisk(episodes, index);
        if (!offline && !requireInternet(R.string.podcasts_wifi_required_stream)) return;
        saveCurrentPodcastResume();
        String showTitle = podcastSelected != null ? podcastSelected.title : playback.podcastShowTitle();
        playback.activatePodcast(episodes, index, showTitle, fromSavedLibrary);
        preparePodcastEpisode(playback.podcastIndex());
    }

    private void preparePodcastEpisode(final int index) {
        if (!playback.isPodcastActive() || playback.podcastQueue().isEmpty()) return;
        saveCurrentPodcastResume();
        final OpenRssClient.Episode ep = playback.podcastQueue().get(index);
        final int gen = ++podcastLoadGeneration;
        playback.setPodcastIndex(index);
        isPausedByHand = false;
        String showTitle = podcastSelected != null ? podcastSelected.title : playback.podcastShowTitle();
        tvPlayerTitle.setText(ep.title);
        tvPlayerArtist.setText(showTitle.isEmpty() ? "Podcast" : showTitle);
        tvPlayerTrackCount.setText(PlaybackCoordinator.formatTrackPosition(index, playback.podcastQueue().size()));
        tvPlayerTimeCurrent.setText("00:00");
        tvPlayerTimeTotal.setText(getString(R.string.podcasts_buffering));
        ivAlbumArt.setImageResource(R.drawable.default_album);
        ivPlayerBgBlur.setImageResource(0);
        updateMainMenuBackground();
        refreshNowPlayingPreview();
        podcastEpisodeLoading = true;
        podcastPartialPlaybackStarted = false;
        podcastGrowingCacheFile = null;
        podcastGrowingCacheFinal = null;
        podcastDownloadInProgress = false;
        podcastDownloadBytesRead = 0;
        podcastDownloadBytesTotal = 0;
        podcastGrowingPreparedBytes = 0;
        lastPodcastPlayPositionMs = 0;
        podcastGrowingSeekMs = 0;
        podcastGrowingReprepareInFlight = false;
        podcastDownloadPaused = false;
        podcastDownloadRetryCount = 0;
        podcastRecoveryInFlight = false;
        podcastDownloadLastProgressMs = 0;
        podcastDownloadStallCheckBytes = 0;
        progressHandler.removeCallbacks(podcastGrowingEdgePoll);
        podcastDownloadCancel.set(true);
        podcastDownloadCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        progressHandler.removeCallbacks(updateProgressTask);
        playerProgress.setProgress(0);
        changeScreen(STATE_PLAYER);

        File saved = PodcastLibrary.resolveEpisodeFile(ep, showTitle);
        podcastResumeKey = podcastResumeKeyForEpisode(ep, saved);
        podcastPendingResumeMs = PodcastResumeStore.restorePositionMs(
                getApplicationContext(), podcastResumeKey, 0);
        if (saved != null) {
            startPodcastFromFile(saved, index, gen);
            return;
        }
        if (playback.podcastFromSavedLibrary()) {
            podcastEpisodeLoading = false;
            Toast.makeText(this, getString(R.string.podcasts_stream_failed), Toast.LENGTH_LONG).show();
            return;
        }
        File cacheDir = new File(getCacheDir(), "podcast");
        String cacheKey = Integer.toHexString(ep.audioUrl.hashCode());
        File partial = new File(cacheDir, "pod_" + cacheKey + ".part");
        if (partial.isFile() && partial.length() > PODCAST_GROWING_MIN_GROW_BYTES) {
            podcastGrowingCacheFile = partial;
            podcastPartialPlaybackStarted = true;
            podcastGrowingPreparedBytes = partial.length();
            podcastDownloadBytesRead = partial.length();
            podcastGrowingCacheFinal = new File(cacheDir, "pod_" + cacheKey + ".audio");
            startPodcastFromGrowingFile(partial, index, gen, podcastPendingResumeMs);
            if (ConnectivityHelper.isOnline(this)
                    && (podcastGrowingCacheFinal == null || !podcastGrowingCacheFinal.isFile())) {
                resumePodcastDownload(index, ep, gen);
            }
            return;
        }
        tryPodcastStream(index, ep, gen, 0);
    }

    private void updatePodcastLoadUi(final int gen, final String status, final int percent) {
        if (gen != podcastLoadGeneration) return;
        if (status != null && tvPlayerTimeTotal != null) tvPlayerTimeTotal.setText(status);
        if (playerProgress != null && percent >= 0) playerProgress.setProgress(percent);
    }

    private String formatPodcastDownloadDetail(long read, long total) {
        if (total > 0) {
            return String.format(Locale.US, "%d%%", (int) (read * 100 / total));
        }
        return String.format(Locale.US, "%.1f MB", read / (1024f * 1024f));
    }

    private void tryPodcastStream(final int index, final OpenRssClient.Episode ep, final int gen,
            final int urlVariant) {
        if (gen != podcastLoadGeneration) return;
        String[] urls = PodcastLibrary.httpsThenHttpVariants(ep.audioUrl);
        if (urlVariant >= urls.length) {
            startPodcastDownload(index, ep, gen);
            return;
        }
        final String url = urls[urlVariant];
        updatePodcastLoadUi(gen, getString(R.string.podcasts_buffering), -1);
        try {
            int previousSessionId = mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            else mediaPlayer.reset();
            if (previousSessionId != 0) {
                try { mediaPlayer.setAudioSessionId(previousSessionId); } catch (Exception ignored) {}
            }
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            attachPodcastPlayerListeners(index, gen, true, urlVariant);
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            tryPodcastStream(index, ep, gen, urlVariant + 1);
        }
    }

    private int podcastGrowingDisplayDurationMs() {
        long bytes = podcastDownloadBytesRead;
        if (podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()) {
            bytes = Math.max(bytes, podcastGrowingCacheFile.length());
        }
        long ms = OpenRssClient.msFromBytes(bytes);
        if (podcastDownloadBytesTotal > 0) {
            long fullMs = OpenRssClient.msFromBytes(podcastDownloadBytesTotal);
            if (fullMs > ms) return (int) Math.min(Integer.MAX_VALUE, fullMs);
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    private void updatePodcastGrowingTimeUi() {
        if (tvPlayerTimeTotal == null) return;
        int dur = podcastGrowingDisplayDurationMs();
        if (dur <= 0) return;
        tvPlayerTimeTotal.setText(formatTime(dur));
        if (mediaPlayer != null) {
            try {
                int current = mediaPlayer.getCurrentPosition();
                lastPodcastPlayPositionMs = current;
                if (playerProgress != null && dur > 0) {
                    int progress = (int) (((float) current / dur) * 100);
                    if (progress > 100) progress = 100;
                    playerProgress.setProgress(progress);
                }
                if (tvPlayerTimeCurrent != null) tvPlayerTimeCurrent.setText(formatTime(current));
            } catch (Exception ignored) {}
        }
    }

    private void maybeRecoverPodcastStream(final int index, final int gen) {
        if (gen != podcastLoadGeneration || !playback.isPodcastActive() || playback.podcastIndex() != index) {
            return;
        }
        if (podcastRecoveryInFlight || podcastDownloadInProgress) return;
        if (!podcastPartialPlaybackStarted && podcastGrowingCacheFile == null) return;
        recoverPodcastStream(index, gen);
    }

    private void recoverPodcastStream(final int index, final int gen) {
        if (gen != podcastLoadGeneration || !playback.isPodcastActive() || playback.podcastIndex() != index) {
            return;
        }
        if (currentScreenState != STATE_PLAYER) return;
        if (podcastDownloadPaused) return;
        if (podcastDownloadRetryCount >= PODCAST_DOWNLOAD_MAX_RETRIES) {
            if (podcastPartialPlaybackStarted) {
                Toast.makeText(this, getString(R.string.podcasts_stream_error), Toast.LENGTH_LONG).show();
            }
            return;
        }
        podcastRecoveryInFlight = true;
        podcastDownloadRetryCount++;
        updatePodcastLoadUi(gen, getString(R.string.podcasts_recovering), -1);
        if (podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()
                && podcastGrowingCacheFile.length() > PODCAST_GROWING_MIN_GROW_BYTES) {
            try {
                if (mediaPlayer != null) {
                    lastPodcastPlayPositionMs = mediaPlayer.getCurrentPosition();
                }
            } catch (Exception ignored) {}
            maybeExtendPodcastGrowingPlayback(index, gen, true);
        }
        final OpenRssClient.Episode ep = playback.podcastQueue().get(index);
        if (!podcastDownloadInProgress) {
            resumePodcastDownload(index, ep, gen);
        }
        podcastRecoveryInFlight = false;
    }

    private void resumePodcastDownload(final int index, final OpenRssClient.Episode ep, final int gen) {
        if (gen != podcastLoadGeneration || podcastDownloadInProgress) return;
        startPodcastDownload(index, ep, gen, true);
    }

    private void startPodcastDownload(final int index, final OpenRssClient.Episode ep, final int gen) {
        startPodcastDownload(index, ep, gen, false);
    }

    private void startPodcastDownload(final int index, final OpenRssClient.Episode ep, final int gen,
            final boolean resume) {
        if (gen != podcastLoadGeneration) return;
        if (currentScreenState != STATE_PLAYER) return;
        podcastDownloadPaused = false;
        if (!resume) {
            updatePodcastLoadUi(gen, getString(R.string.podcasts_downloading), 0);
        } else {
            updatePodcastLoadUi(gen, getString(R.string.podcasts_recovering), -1);
        }
        podcastDownloadInProgress = true;
        podcastDownloadLastProgressMs = System.currentTimeMillis();
        podcastDownloadStallCheckBytes = podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()
                ? podcastGrowingCacheFile.length() : podcastDownloadBytesRead;
        if (!resume) {
            podcastDownloadBytesRead = 0;
            podcastDownloadBytesTotal = 0;
        } else if (podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()) {
            podcastDownloadBytesRead = podcastGrowingCacheFile.length();
        }
        final java.util.concurrent.atomic.AtomicBoolean cancel = podcastDownloadCancel;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File cacheDir = new File(getCacheDir(), "podcast");
                    final File audioFile = OpenRssClient.downloadAudio(cacheDir, ep.audioUrl,
                            new OpenRssClient.AudioDownloadListener() {
                                @Override
                                public boolean onPartialReady(final File partialFile, final long bytesRead) {
                                    if (gen != podcastLoadGeneration || cancel.get()) return false;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (gen != podcastLoadGeneration || podcastPartialPlaybackStarted
                                                    || !playback.isPodcastActive() || playback.podcastIndex() != index) {
                                                return;
                                            }
                                            podcastPartialPlaybackStarted = true;
                                            podcastGrowingCacheFile = partialFile;
                                            podcastGrowingPreparedBytes = partialFile.length();
                                            String name = partialFile.getName();
                                            if (name.endsWith(".part")) {
                                                podcastGrowingCacheFinal = new File(partialFile.getParent(),
                                                        name.substring(0, name.length() - 5) + ".audio");
                                            }
                                            updatePodcastLoadUi(gen, getString(R.string.podcasts_buffering), -1);
                                            startPodcastFromGrowingFile(partialFile, index, gen, 0);
                                        }
                                    });
                                    return true;
                                }

                                @Override
                                public void onProgress(final long bytesRead, final long totalBytes) {
                                    if (gen != podcastLoadGeneration || cancel.get()) return;
                                    podcastDownloadBytesRead = bytesRead;
                                    if (totalBytes > 0) podcastDownloadBytesTotal = totalBytes;
                                    if (bytesRead > podcastDownloadStallCheckBytes) {
                                        podcastDownloadStallCheckBytes = bytesRead;
                                        podcastDownloadLastProgressMs = System.currentTimeMillis();
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (gen != podcastLoadGeneration) return;
                                            if (podcastPartialPlaybackStarted) {
                                                updatePodcastGrowingTimeUi();
                                                return;
                                            }
                                            final String detail = formatPodcastDownloadDetail(bytesRead, totalBytes);
                                            updatePodcastLoadUi(gen,
                                                    getString(R.string.podcasts_downloading) + " " + detail,
                                                    totalBytes > 0 ? (int) (bytesRead * 100 / totalBytes) : -1);
                                        }
                                    });
                                }
                            }, OpenRssClient.PODCAST_EARLY_PLAY_BYTES, cancel);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != podcastLoadGeneration || !playback.isPodcastActive()
                                    || playback.podcastIndex() != index) return;
                            podcastDownloadInProgress = false;
                            podcastDownloadBytesRead = audioFile.length();
                            if (podcastDownloadBytesTotal <= 0) {
                                podcastDownloadBytesTotal = podcastDownloadBytesRead;
                            }
                            progressHandler.removeCallbacks(podcastGrowingEdgePoll);
                            if (!podcastPartialPlaybackStarted) {
                                startPodcastFromFile(audioFile, index, gen);
                            } else {
                                if (audioFile.isFile()) podcastGrowingCacheFile = audioFile;
                                maybeExtendPodcastGrowingPlayback(index, gen, true);
                            }
                        }
                    });
                } catch (final Exception e) {
                    if (cancel.get()) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != podcastLoadGeneration) return;
                            podcastDownloadInProgress = false;
                            progressHandler.removeCallbacks(podcastGrowingEdgePoll);
                            if (podcastPartialPlaybackStarted) {
                                recoverPodcastStream(index, gen);
                            } else {
                                podcastEpisodeLoading = false;
                                Toast.makeText(MainActivity.this, getString(R.string.podcasts_stream_failed),
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        }, "PodcastDl").start();
    }

    private void maybeRenamePodcastGrowingCache() {
        if (podcastGrowingCacheFile == null || podcastGrowingCacheFinal == null) return;
        if (!podcastGrowingCacheFile.isFile()) return;
        if (podcastGrowingCacheFinal.isFile()) {
            podcastGrowingCacheFile = null;
            podcastGrowingCacheFinal = null;
            return;
        }
        if (!podcastGrowingCacheFile.renameTo(podcastGrowingCacheFinal)) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(podcastGrowingCacheFile);
                java.io.FileOutputStream out = new java.io.FileOutputStream(podcastGrowingCacheFinal);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.close();
                podcastGrowingCacheFile.delete();
            } catch (Exception ignored) {}
        }
        podcastGrowingCacheFile = null;
        podcastGrowingCacheFinal = null;
    }

    /** Re-prepare growing cache when more audio is available — keeps partial download playback continuous. */
    private void maybeExtendPodcastGrowingPlayback(final int index, final int gen, final boolean force) {
        if (gen != podcastLoadGeneration || !playback.isPodcastActive() || playback.podcastIndex() != index) {
            return;
        }
        if (podcastGrowingCacheFile == null || !podcastGrowingCacheFile.isFile()) return;
        if (podcastEpisodeLoading || podcastGrowingReprepareInFlight) {
            progressHandler.postDelayed(podcastGrowingEdgePoll, 250);
            return;
        }
        final long fileLen = podcastGrowingCacheFile.length();
        if (fileLen <= 0) {
            if (podcastDownloadInProgress) progressHandler.postDelayed(podcastGrowingEdgePoll, 400);
            return;
        }
        boolean playing = false;
        int positionMs = lastPodcastPlayPositionMs;
        int durationMs = 0;
        try {
            if (mediaPlayer != null) {
                playing = mediaPlayer.isPlaying();
                positionMs = mediaPlayer.getCurrentPosition();
                durationMs = mediaPlayer.getDuration();
                lastPodcastPlayPositionMs = positionMs;
            }
        } catch (Exception ignored) {}
        long growth = fileLen - podcastGrowingPreparedBytes;
        if (!force) {
            if (playing) {
                if (durationMs <= 0 || positionMs < durationMs - PODCAST_GROWING_EXTEND_MS) return;
                if (growth < PODCAST_GROWING_MIN_GROW_BYTES && podcastDownloadInProgress) {
                    progressHandler.postDelayed(podcastGrowingEdgePoll, 300);
                    return;
                }
            } else if (growth < PODCAST_GROWING_MIN_GROW_BYTES && podcastDownloadInProgress) {
                progressHandler.postDelayed(podcastGrowingEdgePoll, 300);
                return;
            }
        }
        podcastGrowingSeekMs = positionMs;
        saveCurrentPodcastResume();
        startPodcastFromGrowingFile(podcastGrowingCacheFile, index, gen, podcastGrowingSeekMs);
    }

    private void startPodcastFromGrowingFile(File growingFile, final int index, final int gen) {
        startPodcastFromGrowingFile(growingFile, index, gen, podcastGrowingSeekMs);
    }

    private void startPodcastFromGrowingFile(File growingFile, final int index, final int gen, int seekMs) {
        try {
            progressHandler.removeCallbacks(podcastGrowingEdgePoll);
            podcastGrowingReprepareInFlight = true;
            podcastGrowingSeekMs = seekMs;
            podcastGrowingPreparedBytes = growingFile.length();
            int previousSessionId = mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            else mediaPlayer.reset();
            if (previousSessionId != 0) {
                try { mediaPlayer.setAudioSessionId(previousSessionId); } catch (Exception ignored) {}
            }
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            attachPodcastPlayerListeners(index, gen, false, 0);
            mediaPlayer.setDataSource(growingFile.getAbsolutePath());
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            podcastGrowingReprepareInFlight = false;
            if (isPodcastGrowingPlayback()) {
                recoverPodcastStream(index, gen);
            } else {
                podcastPartialPlaybackStarted = false;
                podcastEpisodeLoading = false;
                Toast.makeText(this, getString(R.string.podcasts_stream_failed), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void attachPodcastPlayerListeners(final int index, final int gen, final boolean streaming,
            final int streamUrlVariant) {
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (gen != podcastLoadGeneration || !playback.isPodcastActive()) return;
                podcastEpisodeLoading = false;
                podcastGrowingReprepareInFlight = false;
                try {
                    setupVisualizer();
                    int dur;
                    if (podcastGrowingCacheFile != null
                            && (podcastDownloadInProgress || podcastPartialPlaybackStarted)) {
                        dur = podcastGrowingDisplayDurationMs();
                        if (dur <= 0) dur = mp.getDuration();
                    } else {
                        dur = podcastResumeDurationForSave();
                        if (dur <= 0) dur = mp.getDuration();
                    }
                    tvPlayerTimeTotal.setText(formatTime(dur));
                    int resumeMs = podcastPendingResumeMs;
                    podcastPendingResumeMs = 0;
                    int growingSeek = podcastGrowingSeekMs;
                    podcastGrowingSeekMs = 0;
                    if (growingSeek > 0) {
                        mp.seekTo(growingSeek);
                        tvPlayerTimeCurrent.setText(formatTime(growingSeek));
                        lastPodcastPlayPositionMs = growingSeek;
                    } else if (resumeMs > 0) {
                        resumeMs = PodcastResumeStore.restorePositionMs(
                                getApplicationContext(), podcastResumeKey, dur);
                        if (resumeMs > 0) {
                            mp.seekTo(resumeMs);
                            tvPlayerTimeCurrent.setText(formatTime(resumeMs));
                            lastPodcastPlayPositionMs = resumeMs;
                        }
                    }
                    mp.start();
                    updatePlayerUI();
                    progressHandler.post(updateProgressTask);
                    if (podcastGrowingCacheFile != null
                            && (podcastDownloadInProgress || podcastPartialPlaybackStarted)) {
                        progressHandler.postDelayed(podcastGrowingEdgePoll, 500);
                    }
                } catch (Exception ignored) {}
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                maybeRenamePodcastGrowingCache();
                if (!playback.isPodcastActive()) return;
                if (podcastGrowingCacheFile != null && podcastGrowingCacheFile.isFile()) {
                    long fileLen = podcastGrowingCacheFile.length();
                    boolean moreAudio = fileLen > podcastGrowingPreparedBytes + 2048;
                    if (podcastDownloadInProgress || moreAudio) {
                        try {
                            lastPodcastPlayPositionMs = Math.max(lastPodcastPlayPositionMs,
                                    mp.getCurrentPosition() > 0 ? mp.getCurrentPosition() : lastPodcastPlayPositionMs);
                        } catch (Exception ignored) {}
                        progressHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                maybeExtendPodcastGrowingPlayback(playback.podcastIndex(),
                                        podcastLoadGeneration, true);
                            }
                        });
                        return;
                    }
                }
                PodcastResumeStore.clear(getApplicationContext(), podcastResumeKey);
                if (repeatMode == 1) {
                    preparePodcastEpisode(playback.podcastIndex());
                } else if (playback.podcastIndex() < playback.podcastQueue().size() - 1) {
                    preparePodcastEpisode(playback.podcastIndex() + 1);
                } else if (repeatMode == 2 && !playback.podcastQueue().isEmpty()) {
                    preparePodcastEpisode(0);
                } else {
                    stopPodcastDownloadFully();
                    isPausedByHand = true;
                    updatePlayerUI();
                }
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (gen != podcastLoadGeneration) return true;
                podcastGrowingReprepareInFlight = false;
                if (streaming) {
                    tryPodcastStream(index, playback.podcastQueue().get(index), gen, streamUrlVariant + 1);
                } else if (isPodcastGrowingPlayback()) {
                    recoverPodcastStream(index, gen);
                } else {
                    podcastEpisodeLoading = false;
                    Toast.makeText(MainActivity.this, getString(R.string.podcasts_stream_error),
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }

    private void startPodcastFromFile(File audioFile, final int index, final int gen) {
        try {
            int previousSessionId = mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            else mediaPlayer.reset();
            if (previousSessionId != 0) {
                try { mediaPlayer.setAudioSessionId(previousSessionId); } catch (Exception ignored) {}
            }
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            attachPodcastPlayerListeners(index, gen, false, 0);
            java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
            mediaPlayer.setDataSource(fis.getFD());
            fis.close();
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            podcastEpisodeLoading = false;
            Toast.makeText(this, getString(R.string.podcasts_stream_failed), Toast.LENGTH_LONG).show();
        }
    }

    private void savePodcastEpisodeToLibrary(final OpenRssClient.Episode ep) {
        if (podcastSelected == null || ep == null) return;
        if (!requireInternet(R.string.podcasts_wifi_required_stream)) return;
        final File dest = PodcastLibrary.destFile(podcastSelected.title, ep.title, ep.audioUrl);
        if (dest.isFile() && dest.length() > 0) {
            Toast.makeText(this, getString(R.string.podcasts_already_saved), Toast.LENGTH_SHORT).show();
            return;
        }
        setBlockingLoading(true);
        setLoadingOverlayText(getString(R.string.podcasts_saving));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PodcastLibrary.downloadTo(dest, ep.audioUrl, new com.solar.launcher.net.SolarHttp.DownloadProgress() {
                        @Override
                        public void onProgress(final long bytesRead, final long totalBytes) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    int pct = totalBytes > 0 ? (int) (bytesRead * 100 / totalBytes) : -1;
                                    if (pct >= 0) {
                                        setLoadingOverlayText(getString(R.string.podcasts_saving_progress, pct));
                                    } else {
                                        setLoadingOverlayText(getString(R.string.podcasts_saving) + " "
                                                + formatPodcastDownloadDetail(bytesRead, totalBytes));
                                    }
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBlockingLoading(false);
                            Toast.makeText(MainActivity.this, getString(R.string.podcasts_saved_to_library),
                                    Toast.LENGTH_LONG).show();
                            buildPodcastEpisodesUI();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBlockingLoading(false);
                            Toast.makeText(MainActivity.this, getString(R.string.podcasts_save_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static final String SYSTEM_APK_PATH = "/system/app/com.solar.launcher.apk";

    private boolean isInstalledAsSystemApp() {
        try {
            String src = getPackageManager().getApplicationInfo(getPackageName(), 0).sourceDir;
            return src != null && src.startsWith("/system/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installSystemApk(File apkFile) {
        if (installSystemApkViaBundledScript(apkFile)) return true;
        String cmd = "mount -o remount,rw /system && cp "
                + shQuote(apkFile.getAbsolutePath()) + " " + shQuote(SYSTEM_APK_PATH)
                + " && chmod 644 " + shQuote(SYSTEM_APK_PATH) + " && sync";
        return runSuCommandSilently(cmd);
    }

    private boolean installSystemApkViaBundledScript(File apkFile) {
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = getAssets().open("scripts/update-system-apk.sh");
            File script = new File(getCacheDir(), "update-system-apk.sh");
            out = new java.io.FileOutputStream(script);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            out = null;
            in.close();
            in = null;
            script.setExecutable(true, false);
            return runSuCommandSilently("sh " + shQuote(script.getAbsolutePath()) + " "
                    + shQuote(apkFile.getAbsolutePath()));
        } catch (Exception ignored) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void showOtaRebootModal() {
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        if (root == null) {
            Toast.makeText(this, getString(R.string.update_install_reboot), Toast.LENGTH_LONG).show();
            rebootDeviceSilently();
            return;
        }
        String[] labels = new String[] { getString(R.string.common_ok) };
        themedContextMenu.show(root, getString(R.string.update_installing_title),
                getString(R.string.update_install_reboot), labels, null, null,
                new boolean[] { false },
                new ThemedContextMenu.Listener() {
                    @Override
                    public void onSelected(int index) {
                        dismissThemedContextMenu();
                        rebootDeviceSilently();
                    }
                }, y1RowHeightPx, screenWidthPx - 20, false, true);
    }

    private void scheduleStartupUpdateNudge() {
        if (!BuildConfig.FEATURE_OTA_UPDATE) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong(PREF_UPDATE_NUDGE_TS, 0);
        if (now - last < 24L * 60 * 60 * 1000) return;
        if (!ConnectivityHelper.isOnline(this)) return;
        prefs.edit().putLong(PREF_UPDATE_NUDGE_TS, now).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<SolarUpdateClient.ReleaseInfo> releases =
                            SolarUpdateClient.fetchUpdates(SolarUpdateClient.DEFAULT_UPDATES_URL);
                    final SolarUpdateClient.ReleaseInfo latest = BuildConfig.VERSION_NAME != null
                            && BuildConfig.VERSION_NAME.startsWith("nightly-")
                            ? SolarUpdateClient.latestNightly(releases)
                            : SolarUpdateClient.latestStable(releases);
                    if (latest == null) return;
                    int localCode = BuildConfig.VERSION_CODE;
                    String localName = BuildConfig.VERSION_NAME;
                    try {
                        android.content.pm.PackageInfo pInfo =
                                getPackageManager().getPackageInfo(getPackageName(), 0);
                        localCode = pInfo.versionCode;
                        localName = pInfo.versionName;
                    } catch (Exception ignored) {}
                    if (!latest.isNewerThan(localCode, localName)) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (currentScreenState != STATE_MENU) return;
                            showThemedConfirm(
                                    getString(R.string.update_available_title),
                                    getString(R.string.update_available_message, latest.listLabel()),
                                    getString(R.string.update_install),
                                    getString(R.string.update_later),
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            downloadAndInstallApk(latest);
                                        }
                                    });
                        }
                    });
                } catch (Exception ignored) {}
            }
        }, "SolarStartupNudge").start();
    }

    private void rebootDeviceSilently() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                runSuCommandSilently("reboot");
            }
        }, 1500);
    }

    private boolean installViaPackageManager(File apkFile, boolean allowDowngrade) {
        if (apkFile == null || !apkFile.isFile()) return false;
        String cmd = allowDowngrade
                ? "pm install -r -d " + shQuote(apkFile.getAbsolutePath())
                : "pm install -r " + shQuote(apkFile.getAbsolutePath());
        return runSuCommandSilently(cmd);
    }

    private void installApk(File apkFile) {
        installApk(apkFile, null);
    }

    private void installApk(File apkFile, SolarUpdateClient.ReleaseInfo release) {
        try {
            if (apkFile == null || !apkFile.isFile()) {
                Toast.makeText(this, getString(R.string.toast_install_failed), Toast.LENGTH_SHORT).show();
                return;
            }
            int localCode = BuildConfig.VERSION_CODE;
            String localName = BuildConfig.VERSION_NAME;
            try {
                android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                localCode = pInfo.versionCode;
                localName = pInfo.versionName;
            } catch (Exception ignored) {}
            SolarUpdateClient.InstallRelation relation = release != null
                    ? release.compareToInstalled(localCode, localName)
                    : SolarUpdateClient.InstallRelation.UPGRADE;

            if (release != null && relation == SolarUpdateClient.InstallRelation.SAME) {
                Toast.makeText(this, getString(R.string.update_already_installed), Toast.LENGTH_SHORT).show();
                return;
            }

            boolean systemApp = isInstalledAsSystemApp();
            boolean tryPmFirst = !systemApp;
            boolean allowDowngrade = relation == SolarUpdateClient.InstallRelation.DOWNGRADE
                    || relation == SolarUpdateClient.InstallRelation.SIDEGRADE;

            if (tryPmFirst && installViaPackageManager(apkFile, allowDowngrade)) {
                Toast.makeText(this, getString(R.string.toast_install_ok), Toast.LENGTH_SHORT).show();
                return;
            }

            if (systemApp && installSystemApk(apkFile)) {
                showOtaRebootModal();
                return;
            }

            if (!systemApp && installViaPackageManager(apkFile, true)) {
                Toast.makeText(this, getString(R.string.toast_install_ok), Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_install_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void playTrackList(List<File> playlist, int startIndex) {
        playTrackList(playlist, startIndex, null);
    }

    private void playTrackList(List<File> playlist, int startIndex, String activePlaylistName) {
        saveCurrentPodcastResume();
        stopPodcastDownloadFully();
        finalizeReachStreamHandoff();
        playback.activateMusic(playlist, startIndex, isShuffleMode);
        playback.setMusicActivePlaylistName(activePlaylistName);
        purgeUnreferencedReachCache();

        if (!playback.musicPlaylist().isEmpty()) {
            File track = playback.musicPlaylist().get(playback.musicIndex());
            tvPlayerTitle.setText(track.getName());
            tvPlayerArtist.setText(getString(R.string.reach_loading_track));
            playerProgress.setProgress(0);
            tvPlayerTimeCurrent.setText("00:00");
            tvPlayerTimeTotal.setText("00:00");
            updateMusicTrackCountUi();
        }
        isPausedByHand = false;
        changeScreen(STATE_PLAYER);
        prepareMusicTrack(playback.musicIndex());
        persistPlaybackQueue();
    }
    private void setupFolderPlaylist(File selectedFile) {
        List<File> list = new ArrayList<>();
        File[] files = currentFolder.listFiles();
        int matchIndex = 0;
        if (files != null) {
            for (File f : files) {
                if (isAudioFile(f)) {
                    list.add(f);
                    if (f.getAbsolutePath().equals(selectedFile.getAbsolutePath()))
                        matchIndex = list.size() - 1;
                }
            }
        }
        playTrackList(list, matchIndex);
    }

    private void prepareMusicTrack(int index) {
        if (playback.musicPlaylist().isEmpty())
            return;
        if (index < 0 || index >= playback.musicPlaylist().size()) {
            playback.clampMusicIndex();
            index = playback.musicIndex();
        }
        playback.setMusicIndex(index);
        final File track = playback.musicPlaylist().get(index);
        lastAlbumArtBytes = null;
        currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
        // 🚀 [추가된 부분] 손상된 파일 방어막: 파일이 없거나 용량이 1KB(1024 bytes) 미만인 껍데기 파일일 경우
        if (!track.exists() || track.length() < 1024) {
            tvPlayerTitle.setText("Corrupted File");
            tvPlayerArtist.setText("Skipping...");
            ivAlbumArt.setImageResource(R.drawable.default_album);

            // 시스템이 뻗기 전에 경고창을 띄우고 1.5초 뒤에 다음 곡으로 자동으로 부드럽게 넘겨버립니다!
            Toast.makeText(this, getString(R.string.toast_corrupted_file), Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    nextTrack();
                }
            }, 1500);
            return;
        }
        tvPlayerTitle.setText(track.getName());
        tvPlayerArtist.setText("Loading...");
        ivAlbumArt.setImageResource(R.drawable.default_album);
        ivPlayerBgBlur.setImageResource(0);
        playerProgress.setProgress(0);
        tvPlayerTimeCurrent.setText("00:00");
        tvPlayerTimeTotal.setText("00:00");

        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            java.io.FileInputStream fisMmr = new java.io.FileInputStream(track);
            mmr.setDataSource(fisMmr.getFD());


            // 1. 파일에서 메타데이터(태그) 추출
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            lastAlbumArtBytes = mmr.getEmbeddedPicture();

            String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "").replace(".wav", "").replace(".m4a", "");
            File coverFile = new File(getCoversFolder(), safeFileName + ".jpg");

            if (prefs.contains("meta_title_" + track.getAbsolutePath())) {
                t = prefs.getString("meta_title_" + track.getAbsolutePath(), t);
                a = prefs.getString("meta_artist_" + track.getAbsolutePath(), a);
            }

            // 🚀 [핵심 판단 로직] 이 파일에 정말 멀쩡한 태그(가수+제목)가 들어있는지 검사합니다.
            boolean hasValidTags = (t != null && !t.trim().isEmpty() && a != null && !a.trim().isEmpty() && !a.equalsIgnoreCase("Unknown Artist"));

            // 제목 화면에 표시
            if (t != null && !t.trim().isEmpty()) tvPlayerTitle.setText(t);
            else tvPlayerTitle.setText(safeFileName);

            // 가수 화면에 표시
            if (a != null && !a.trim().isEmpty()) tvPlayerArtist.setText(a);
            else tvPlayerArtist.setText("Unknown Artist");

            // 2. 앨범 아트 세팅 및 인터넷 검색
            if (lastAlbumArtBytes != null) {
                // 원본 파일에 앨범 아트가 있으면 그대로 사용
                updateMainMenuBackground();
                refreshNowPlayingPreview();
                try {
                    android.graphics.BitmapFactory.Options optsCenter = new android.graphics.BitmapFactory.Options();
                    optsCenter.inSampleSize = 2;
                    android.graphics.Bitmap bmpCenter = android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, optsCenter);
                    ivAlbumArt.setImageBitmap(bmpCenter);
                    try {
                        int centerX = bmpCenter.getWidth() / 2;
                        int centerY = (int)(bmpCenter.getHeight() * 0.8);
                        currentAlbumColor = bmpCenter.getPixel(centerX, centerY) | 0xFF000000;
                    } catch (Exception e) {
                        currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
                    }
                    if (playerAlbumBlurEnabled) {
                        android.graphics.BitmapFactory.Options optsBg = new android.graphics.BitmapFactory.Options();
                        optsBg.inSampleSize = 4;
                        android.graphics.Bitmap sourceBg = android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, optsBg);
                        android.graphics.Bitmap blurredBg = applyGaussianBlur(sourceBg);
                        ivPlayerBgBlur.setImageBitmap(blurredBg);
                        if (sourceBg != blurredBg) sourceBg.recycle();
                    } else {
                        ivPlayerBgBlur.setImageResource(0);
                    }
                } catch (Throwable e) {}

            } else if (coverFile.exists()) {
                // 다운받아둔 앨범 아트가 있으면 사용
                applyCachedCoverArt(coverFile.getAbsolutePath());

            } else {

                ivAlbumArt.setImageResource(R.drawable.default_album);
                currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
                ivPlayerBgBlur.setImageResource(0); // 뒷배경 블러 비우기
                updateMainMenuBackground();
                refreshNowPlayingPreview();
                // 없으면 인터넷에서 검색 출동!
                if (isAutoFetchEnabled) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null && wm.isWifiEnabled() && wm.getConnectionInfo().getNetworkId() != -1) {

                        String searchQuery = "";
                        // 🚀 [스마트 쿼리 생성] 멀쩡한 태그가 있다면 그걸 합쳐서(가수+제목) 무조건 100% 일치하는 곡을 찾습니다!
                        if (hasValidTags) {
                            searchQuery = a + " " + t;
                        } else {
                            searchQuery = safeFileName.replace("-", " ").replace("_", " ");
                        }

                        // 태그가 있다는 사실과 기존 태그 내용을 같이 넘겨서, 정보가 덮어씌워지지 않게 막습니다.
                        fetchTrackInfoFromInternet(track, searchQuery, hasValidTags, t, a);
                    }
                }
            }

            fisMmr.close();
            mmr.release();
        } catch (Throwable t) {
        }

        try {
            // 🚀 [가장 우아하고 근본적인 해결책]
            // 1. 플레이어를 리셋하기 전에 현재 사용 중인 '오디오 회선 번호(Session ID)'를 기억해 둡니다.
            int previousSessionId = 0;
            if (mediaPlayer != null) {
                try { previousSessionId = mediaPlayer.getAudioSessionId(); } catch (Exception e) {}
            }

            // 🚀 [추가] 시각화 엔진(Visualizer)은 안드로이드 내부 버그로 인해 살려둔 채로 3곡 이상 넘기면
            // 메모리가 터져서 시스템을 다운시켜버립니다(3곡 프리징의 원인!).
            // 따라서 곡이 바뀔 때는 반드시 '완전히 파괴(release)' 해 주어야 합니다.
            if (audioVisualizer != null) {
                try {
                    audioVisualizer.setEnabled(false);
                    audioVisualizer.release(); // 🚀 숨통을 완전히 끊어버립니다!
                    audioVisualizer = null;
                } catch (Exception e) {}
            }

            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                mediaPlayer.reset();
            }

            // 2. 리셋된 플레이어에 방금 기억해둔 회선 번호를 다시 연결해 줍니다!
            // 이렇게 하면 이퀄라이저가 유지한 회선에 다시 탑승하게 되어, 볼륨이 리셋되는 버그가 원천 차단됩니다.
            if (previousSessionId != 0) {
                try { mediaPlayer.setAudioSessionId(previousSessionId); } catch (Exception e) {}
            }

            // 🚀 [버그 수정] 권한 누락으로 인해 음악 재생이 통째로 취소되는 것을 막는 방어막!
            try {
                mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            } catch (Exception e) { }
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    String err = "Audio Error: what=" + what + " extra=" + extra;
                    try {
                        java.io.File log = new java.io.File("/storage/sdcard0/solar_audio_error.txt");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(log, true);
                        fos.write((new java.util.Date().toString() + " - " + err + " File: " + track.getName() + "\n")
                                .getBytes());
                        fos.close();
                    } catch (Exception e) {
                    }
                    return true;
                }
            });

            if (currentFileInputStream != null) {
                try {
                    currentFileInputStream.close();
                } catch (Exception e) {
                }
            }
            currentFileInputStream = new java.io.FileInputStream(track);

            mediaPlayer.setDataSource(currentFileInputStream.getFD());
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    try {
                        setupVisualizer();
                        try {
                            if (equalizer == null) {
                                equalizer = new Equalizer(0, mediaPlayer.getAudioSessionId());
                                equalizer.setEnabled(true);
                            }
                            if (currentEqPresetIndex < equalizer.getNumberOfPresets()) {
                                equalizer.usePreset((short) currentEqPresetIndex);
                            }
                        } catch (Exception e) {
                        }
                        tvPlayerTimeTotal.setText(formatTime(mp.getDuration()));
                        updateMusicTrackCountUi();
                        if (!isPausedByHand) {
                            mp.start();
                        }
                        updatePlayerUI();
                    } catch (Exception ignored) {}
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    try {
                        if (repeatMode == 1) {
                            mediaPlayer.seekTo(0);
                            mediaPlayer.start();
                        } else if (repeatMode == 2) {
                            nextTrack();
                        } else {
                            if (playback.musicIndex() < playback.musicPlaylist().size() - 1) {
                                nextTrack();
                            } else {
                                playback.setMusicIndex(0);
                                prepareMusicTrack(playback.musicIndex());
                                isPausedByHand = true;
                                updatePlayerUI();
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            });
            mediaPlayer.prepareAsync();
        } catch (Throwable e) {
            tvPlayerTitle.setText("Load Failed: " + track.getName());
        }
    }
    // 💡 [수정] 액자 전체를 숨기도록 개조
    private void syncVisualizerMetadata() {
        if (tvVizTitle != null && tvPlayerTitle != null) {
            tvVizTitle.setText(tvPlayerTitle.getText());
            tvVizTitle.setSelected(true);
        }
        if (tvVizArtist != null && tvPlayerArtist != null) {
            tvVizArtist.setText(tvPlayerArtist.getText());
            tvVizArtist.setSelected(true);
        }
        if (tvVizTrackCount != null && tvPlayerTrackCount != null) {
            tvVizTrackCount.setText(tvPlayerTrackCount.getText());
        }
    }

    private void toggleVisualizer() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 101);
                Toast.makeText(this, getString(R.string.toast_audio_permission), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        isVisualizerShowing = !isVisualizerShowing;

        if (isVisualizerShowing) {
            syncVisualizerMetadata();
            if (playerContentRow != null) playerContentRow.setVisibility(View.GONE);
            if (playerVisualizerContainer != null) playerVisualizerContainer.setVisibility(View.VISIBLE);
            visualizerView.setVisibility(View.VISIBLE);
            visualizerView.invalidate();
            if (audioVisualizer != null) audioVisualizer.setEnabled(true);
        } else {
            visualizerView.setVisibility(View.GONE);
            if (playerVisualizerContainer != null) playerVisualizerContainer.setVisibility(View.GONE);
            if (playerContentRow != null) playerContentRow.setVisibility(View.VISIBLE);
            if (audioVisualizer != null) audioVisualizer.setEnabled(false);
        }
    }
    // 💡 [수정] 오디오 엔진에 빨대를 꽂아 주파수 데이터를 빼오는 함수
    private void setupVisualizer() {
        try {
            // 🚀 [완벽 해결] 매번 새롭게 엔진을 만들어서 장착합니다! (메모리 누수 원천 차단)
            if (audioVisualizer != null) {
                audioVisualizer.setEnabled(false);
                audioVisualizer.release();
                audioVisualizer = null;
            }

            audioVisualizer = new android.media.audiofx.Visualizer(mediaPlayer.getAudioSessionId());
            audioVisualizer.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[1]);
            audioVisualizer.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(android.media.audiofx.Visualizer visualizer, byte[] waveform, int samplingRate) {}

                @Override
                public void onFftDataCapture(android.media.audiofx.Visualizer visualizer, byte[] fft, int samplingRate) {
                    if (isVisualizerShowing && visualizerView != null && visualizerView.getVisibility() == View.VISIBLE) {
                        visualizerView.updateVisualizer(fft, currentAlbumColor);
                    }
                }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true);

            if (isVisualizerShowing) {
                audioVisualizer.setEnabled(true);
            }
        } catch (Exception e) {}
    }
    private String getRepeatModeText(int mode) {
        switch (mode) {
            case 1:
                return getString(R.string.common_one);
            case 2:
                return getString(R.string.common_all);
            default:
                return getString(R.string.common_off);
        }
    }

    //    private void updatePlayerStatusIndicators() {
//        try {
//            if (tvPlayerShuffleStatus != null) {
//                tvPlayerShuffleStatus.setVisibility(isShuffleMode ? View.VISIBLE : View.GONE);
//            }
//            if (tvPlayerRepeatStatus != null) {
//                if (repeatMode == 1) {
//                    tvPlayerRepeatStatus.setText("REPEAT ONE");
//                    tvPlayerRepeatStatus.setVisibility(View.VISIBLE);
//                } else if (repeatMode == 2) {
//                    tvPlayerRepeatStatus.setText("REPEAT ALL");
//                    tvPlayerRepeatStatus.setVisibility(View.VISIBLE);
//                } else {
//                    tvPlayerRepeatStatus.setVisibility(View.GONE);
//                }
//            }
//        } catch (Exception e) {
//        }
//    }
    private void updatePlayerStatusIndicators() {
        try {
            if (ivPlayerShuffleStatus != null) {
                if (isShuffleMode) {
                    applyThemedPlayerModeIcon(ivPlayerShuffleStatus, "shuffleOn", R.drawable.ic_shuffle);
                    ivPlayerShuffleStatus.setVisibility(View.VISIBLE);
                } else {
                    ivPlayerShuffleStatus.setVisibility(View.GONE);
                }
            }
            if (ivPlayerRepeatStatus != null) {
                if (repeatMode == 1) {
                    applyThemedPlayerModeIcon(ivPlayerRepeatStatus, "repeatOne", R.drawable.ic_repeat_one);
                    ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else if (repeatMode == 2) {
                    applyThemedPlayerModeIcon(ivPlayerRepeatStatus, "repeatAll", R.drawable.ic_repeat);
                    ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else {
                    ivPlayerRepeatStatus.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
        }
    }

    private void applyThemedPlayerModeIcon(ImageView iv, String iconKey, int fallbackResId) {
        android.graphics.Bitmap bmp = ThemeManager.getPlaybackModeIcon(iconKey);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.clearColorFilter();
        } else {
            iv.setImageResource(fallbackResId);
            iv.clearColorFilter();
        }
    }
    private void updatePlayerUI() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                ivAlbumArt.setAlpha(1.0f);
                ivPauseOverlay.setVisibility(View.GONE);
                progressHandler.post(updateProgressTask);

                // 🚀 [스크린 오프 완벽 제어 3단계] 재생 상태(PLAYING)를 시스템에 신고하여 제어권 유지
                if (mediaSessionShim != null && android.os.Build.VERSION.SDK_INT >= 21) {
                    mediaSessionShim.getClass().getMethod("setPlaying", int.class)
                            .invoke(mediaSessionShim, mediaPlayer.getCurrentPosition());
                }
            } else {
                ivAlbumArt.setAlpha(0.4f);
                ivPauseOverlay.setVisibility(View.VISIBLE);
                progressHandler.removeCallbacks(updateProgressTask);

                // 일시정지 상태(PAUSED) 신고
                if (mediaSessionShim != null && android.os.Build.VERSION.SDK_INT >= 21) {
                    int pos = mediaPlayer == null ? 0 : mediaPlayer.getCurrentPosition();
                    mediaSessionShim.getClass().getMethod("setPaused", int.class).invoke(mediaSessionShim, pos);
                }
            }
            updatePlayerStatusIndicators();
            updatePlaybackStatusIcon();
            if (isVisualizerShowing) syncVisualizerMetadata();
        } catch (Exception e) {
        }
    }

    /** Called from MediaSessionShim (API 21+) for screen-off transport keys. */
    public boolean handleMediaSessionKey(int keyCode) {
        if (!isScreenOffControlEnabled) return false;
        if (keyCode == 21) { adjustVolume(false); clickFeedback(); return true; }
        if (keyCode == 22) { adjustVolume(true); clickFeedback(); return true; }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {
            playOrPauseMusic(); clickFeedback(); return true;
        }
        return false;
    }

    private void playOrPauseMusic() {
        try {
            if (mediaPlayer == null) return;
            if (!playback.isPodcastActive() && playback.musicPlaylist().isEmpty()) return;
            if (playback.isPodcastActive() && playback.podcastQueue().isEmpty()) return;
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                isPausedByHand = true;
                flushPodcastResumeIfNeeded();
            } else {
                mediaPlayer.start();
                isPausedByHand = false;

            }
            updatePlayerUI();
        } catch (Throwable e) {
        }
    }
    private void nextTrack() {
        lastTrackChangeTime = System.currentTimeMillis();
        finalizeReachStreamHandoff();
        int next = playback.nextIndex(repeatMode > 0);
        if (next < 0) {
            if (repeatMode == 0) {
                PlayQueueStore.clear(getApplicationContext());
            }
            return;
        }
        playback.setQueueIndex(next);
        PlayQueue.QueueItem item = playback.currentItem();
        if (item == null) return;
        if (item.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
            preparePodcastEpisode(playback.podcastIndex());
        } else {
            prepareMusicTrack(playback.musicIndex());
        }
        persistPlaybackQueue();
        if (isMusicQueueEditorScreen()) refreshMusicQueueListSoft();
    }

    private void prevTrack() {
        lastTrackChangeTime = System.currentTimeMillis();
        finalizeReachStreamHandoff();
        int prev = playback.prevIndex(repeatMode > 0);
        if (prev < 0) return;
        playback.setQueueIndex(prev);
        PlayQueue.QueueItem item = playback.currentItem();
        if (item == null) return;
        if (item.kind == PlayQueue.ItemKind.PODCAST_EPISODE) {
            preparePodcastEpisode(playback.podcastIndex());
        } else {
            prepareMusicTrack(playback.musicIndex());
        }
        persistPlaybackQueue();
        if (isMusicQueueEditorScreen()) refreshMusicQueueListSoft();
    }

    private boolean isMediaNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87;
    }

    private boolean isMediaPrevKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88;
    }

    private boolean isMediaSkipKey(int keyCode) {
        return isMediaNextKey(keyCode) || isMediaPrevKey(keyCode);
    }

    private boolean isMediaPlayPauseKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86;
    }

    private boolean hasActiveMediaPlayback() {
        if (mediaPlayer == null) return false;
        return playback.hasAnyQueue();
    }

    private int playbackDurationForScrub() {
        if (playback.isPodcastActive()) {
            int est = podcastResumeDurationForSave();
            if (est > 0) return est;
        }
        try {
            if (mediaPlayer == null) return 0;
            int d = mediaPlayer.getDuration();
            return d > 0 ? d : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isPodcastGrowingPlayback() {
        return playback.isPodcastActive()
                && podcastGrowingCacheFile != null
                && podcastGrowingCacheFile.isFile()
                && (podcastDownloadInProgress || podcastPartialPlaybackStarted);
    }

    /** ponytail: 128 kbps byte estimate — cap seeks to downloaded bytes, not full-episode UI duration. */
    private int podcastBufferedSeekLimitMs() {
        if (!isPodcastGrowingPlayback()) return -1;
        long bytes = podcastDownloadBytesRead;
        bytes = Math.max(bytes, podcastGrowingCacheFile.length());
        if (bytes <= 0) return 0;
        int fromBytes = (int) Math.min(Integer.MAX_VALUE, OpenRssClient.msFromBytes(bytes));
        try {
            if (mediaPlayer != null) {
                int mpDur = mediaPlayer.getDuration();
                if (mpDur > 0) return Math.min(fromBytes, mpDur);
            }
        } catch (Exception ignored) {}
        return fromBytes;
    }

    /** Furthest position scrub/seek may target (buffer edge while downloading, else full duration). */
    private int playbackMaxSeekMs() {
        int buffered = podcastBufferedSeekLimitMs();
        if (buffered >= 0) return Math.max(0, buffered);
        int dur = playbackDurationForScrub();
        return dur > 0 ? dur : Integer.MAX_VALUE;
    }

    static int clampScrubPositionMs(int positionMs, int durationMs) {
        int pos = positionMs;
        if (durationMs > 0) pos = Math.max(0, Math.min(pos, durationMs));
        else pos = Math.max(0, pos);
        return pos;
    }

    private void stylePlayerScrubMarker() {
        if (playerScrubMarker == null) return;
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(ThemeManager.getProgressColor());
        int stroke = ThemeManager.getProgressBackgroundColor();
        dot.setStroke(2, stroke);
        playerScrubMarker.setBackground(dot);
    }

    private void enterPlayerScrubCursorMode() {
        if (!hasActiveMediaPlayback()) return;
        int dur = playbackDurationForScrub();
        if (dur <= 0) return;
        try {
            playerScrubCursorMs = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            playerScrubCursorMs = 0;
        }
        playerScrubCursorMs = clampScrubPositionMs(playerScrubCursorMs, playbackMaxSeekMs());
        playerScrubCursorActive = true;
        updatePlayerScrubUi();
    }

    private void commitPlayerScrubCursor() {
        if (!playerScrubCursorActive) return;
        seekMediaTo(playerScrubCursorMs);
        clearPlayerScrubCursorMode(false);
    }

    private void cancelPlayerScrubCursor() {
        clearPlayerScrubCursorMode(true);
    }

    private void clearPlayerScrubCursorMode(boolean restoreLiveUi) {
        playerScrubCursorActive = false;
        if (playerScrubMarker != null) playerScrubMarker.setVisibility(View.GONE);
        if (restoreLiveUi) refreshPlayerProgressFromPlayback();
    }

    private void refreshPlayerProgressFromPlayback() {
        try {
            if (mediaPlayer == null) return;
            int current = mediaPlayer.getCurrentPosition();
            int dur = playbackDurationForScrub();
            if (dur > 0 && playerProgress != null) {
                playerProgress.setProgress((int) (((float) current / dur) * 100));
            }
            if (tvPlayerTimeCurrent != null) tvPlayerTimeCurrent.setText(formatTime(current));
            if (tvPlayerTimeTotal != null && dur > 0) tvPlayerTimeTotal.setText(formatTime(dur));
        } catch (Exception ignored) {}
    }

    private void movePlayerScrubCursor(int deltaMs) {
        if (!playerScrubCursorActive || deltaMs == 0) return;
        playerScrubCursorMs = clampScrubPositionMs(
                playerScrubCursorMs + deltaMs, playbackMaxSeekMs());
        updatePlayerScrubUi();
    }

    private void updatePlayerScrubUi() {
        if (!playerScrubCursorActive) return;
        int dur = playbackDurationForScrub();
        if (dur > 0 && playerProgress != null) {
            playerProgress.setProgress((int) (((float) playerScrubCursorMs / dur) * 100));
        }
        if (tvPlayerTimeCurrent != null) tvPlayerTimeCurrent.setText(formatTime(playerScrubCursorMs));
        if (tvPlayerTimeTotal != null && dur > 0) tvPlayerTimeTotal.setText(formatTime(dur));
        updatePlayerScrubMarkerPosition();
    }

    private void updatePlayerScrubMarkerPosition() {
        if (playerScrubMarker == null || playerProgress == null) return;
        if (!playerScrubCursorActive) {
            playerScrubMarker.setVisibility(View.GONE);
            return;
        }
        int dur = playbackDurationForScrub();
        if (dur <= 0) {
            playerScrubMarker.setVisibility(View.GONE);
            return;
        }
        int trackW = playerProgress.getWidth();
        if (trackW <= 0) return;
        float frac = (float) playerScrubCursorMs / dur;
        int markerW = playerScrubMarker.getWidth();
        if (markerW <= 0) {
            markerW = (int) (10 * getResources().getDisplayMetrics().density);
        }
        int x = (int) (frac * trackW) - markerW / 2;
        x = Math.max(0, Math.min(x, trackW - markerW));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) playerScrubMarker.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(markerW, markerW);
        }
        lp.width = markerW;
        lp.height = markerW;
        lp.leftMargin = x;
        playerScrubMarker.setLayoutParams(lp);
        playerScrubMarker.setVisibility(View.VISIBLE);
    }

    private void seekMediaTo(int positionMs) {
        if (mediaPlayer == null) return;
        try {
            int dur = playbackDurationForScrub();
            int pos = clampScrubPositionMs(positionMs, playbackMaxSeekMs());
            mediaPlayer.seekTo(pos);
            lastPodcastPlayPositionMs = pos;
            if (playback.isPodcastActive() && !podcastResumeKey.isEmpty()) {
                PodcastResumeStore.save(getApplicationContext(), podcastResumeKey, pos,
                        podcastResumeDurationForSave());
            }
            if (playerProgress != null && dur > 0) {
                playerProgress.setProgress((int) (((float) pos / dur) * 100));
            }
            if (tvPlayerTimeCurrent != null) tvPlayerTimeCurrent.setText(formatTime(pos));
            if (tvPlayerTimeTotal != null && dur > 0) tvPlayerTimeTotal.setText(formatTime(dur));
            updatePlaybackStatusIcon();
        } catch (Exception ignored) {}
    }

    private void scrubMediaBy(int deltaMs) {
        if (mediaPlayer == null || deltaMs == 0) return;
        try {
            int pos = clampScrubPositionMs(
                    mediaPlayer.getCurrentPosition() + deltaMs, playbackMaxSeekMs());
            seekMediaTo(pos);
        } catch (Exception ignored) {}
    }

    /** Hold prev/next to scrub; short press skips track. */
    boolean handleMediaSkipKeyDown(int keyCode, KeyEvent event) {
        if (!isMediaSkipKey(keyCode)) return false;
        final boolean next = isMediaNextKey(keyCode);
        if (event.getRepeatCount() == 0) {
            if (next) {
                mediaNextKeyDownTime = System.currentTimeMillis();
                mediaNextScrubActive = false;
            } else {
                mediaPrevKeyDownTime = System.currentTimeMillis();
                mediaPrevScrubActive = false;
            }
            return true;
        }
        if (!hasActiveMediaPlayback()) return true;
        long downAt = next ? mediaNextKeyDownTime : mediaPrevKeyDownTime;
        boolean scrubbing = next ? mediaNextScrubActive : mediaPrevScrubActive;
        long now = System.currentTimeMillis();
        if (!scrubbing && now - downAt >= MEDIA_SKIP_LONG_PRESS_MS) {
            scrubbing = true;
            if (next) mediaNextScrubActive = true;
            else mediaPrevScrubActive = true;
            clickFeedback();
        }
        if (scrubbing) {
            scrubMediaBy(next ? MEDIA_SCRUB_STEP_MS : -MEDIA_SCRUB_STEP_MS);
        }
        return true;
    }

    boolean handleMediaSkipKeyUp(int keyCode, KeyEvent event) {
        if (!isMediaSkipKey(keyCode)) return false;
        final boolean next = isMediaNextKey(keyCode);
        final boolean scrubbing = next ? mediaNextScrubActive : mediaPrevScrubActive;
        if (next) mediaNextScrubActive = false;
        else mediaPrevScrubActive = false;
        if (scrubbing) {
            if (playback.isPodcastActive()) flushPodcastResumeIfNeeded();
            return true;
        }
        if (playerScrubCursorActive) clearPlayerScrubCursorMode(true);
        long downAt = next ? mediaNextKeyDownTime : mediaPrevKeyDownTime;
        if (System.currentTimeMillis() - downAt < MEDIA_SKIP_LONG_PRESS_MS) {
            clickFeedback();
            if (next) nextTrack();
            else prevTrack();
        }
        return true;
    }

    private void adjustVolume(boolean up) {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (up && currentVol < maxVol)
            currentVol++;
        else if (!up && currentVol > 0)
            currentVol--;
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol, 0);
        showDynamicVolumeOverlay();
    }

    private void showDynamicVolumeOverlay() {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        layoutVolumeOverlay.setVisibility(View.VISIBLE);
        volumeProgress.setProgress(currentVol);
        volumeHandler.removeCallbacks(hideVolumeTask);
        volumeHandler.postDelayed(hideVolumeTask, 2000);
    }

    private String formatTime(int ms) {
        int s = (ms / 1000) % 60;
        int m = (ms / (1000 * 60)) % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - lastScreenOnTime < 500);

        if (isWakingUp) {
            if (isCenterKey(keyCode)) {
                return true;
            }

            if (isScreenOffControlEnabled && currentScreenState == STATE_PLAYER) {
                if (keyCode == 21) {
                    // 🚀 방어막: 곡 넘김 직후 0.3초(300ms) 안에는 볼륨 조절을 차단합니다!
                    if (System.currentTimeMillis() - lastTrackChangeTime > 300) {
                        adjustVolume(false);
                        clickFeedback();
                    }
                    return true;
                }
                if (keyCode == 22) {
                    if (System.currentTimeMillis() - lastTrackChangeTime > 300) {
                        adjustVolume(true);
                        clickFeedback();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88
                        || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                    return handleMediaSkipKeyDown(keyCode, event);
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {
                    playOrPauseMusic();
                    clickFeedback();
                    return true;
                }
            }
            return true;
        }

        if (themedContextMenu != null && themedContextMenu.isShowing()) {
            if (keyCode == 21 || keyCode == 22) {
                if (themedContextMenu.focusZone() == ThemedContextMenu.FocusZone.SLIDER && audioManager != null) {
                    adjustVolume(keyCode == 22);
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    themedContextMenu.showSlider(getString(R.string.context_quick_volume), max, cur);
                    clickFeedback();
                    return true;
                }
                if (themedContextMenu.handleKeyHorizontal(keyCode)) {
                    clickFeedback();
                    return true;
                }
            }
            if (keyCode == 21) {
                themedContextMenu.moveFocus(-1);
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                themedContextMenu.moveFocus(1);
                clickFeedback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (popContextMenuTier()) return true;
                dismissThemedContextMenu();
                return true;
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return handleBackKeyDown(event);
        }

        if (currentScreenState == STATE_WIFI_KEYBOARD) {
            if (isMediaSkipKey(keyCode)) {
                if (event.getRepeatCount() == 0) {
                    keyboardMediaSkipDownAt = System.currentTimeMillis();
                }
                return true;
            }
            if (isMediaPlayPauseKey(keyCode)) {
                if (event.getRepeatCount() == 0) {
                    keyboardPpDownAt = System.currentTimeMillis();
                    keyboardPpLongHandled = false;
                } else if (!keyboardPpLongHandled
                        && System.currentTimeMillis() - keyboardPpDownAt >= MEDIA_SKIP_LONG_PRESS_MS) {
                    handleKeyboardPlayPauseLongPress();
                    keyboardPpLongHandled = true;
                }
                return true;
            }
            if (keyCode == 21) {
                if (event.getRepeatCount() > 0) {
                    handleKeyboardMediaDel();
                    return true;
                }
                keyboardIndex = (keyboardIndex - 1 + KEYBOARD_CHARS.length) % KEYBOARD_CHARS.length;
                updateKeyboardUI();
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                keyboardIndex = (keyboardIndex + 1) % KEYBOARD_CHARS.length;
                updateKeyboardUI();
                clickFeedback();
                return true;
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == 86) {
            if (event.getRepeatCount() == 0) {
                playOrPauseMusic();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            return handleMediaSkipKeyDown(keyCode, event);
        }

        if (currentScreenState == STATE_PLAYER) {
            if (keyCode == 21) {
                if (playerScrubCursorActive) {
                    movePlayerScrubCursor(-MEDIA_SCRUB_STEP_MS);
                } else {
                    adjustVolume(false);
                }
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                if (playerScrubCursorActive) {
                    movePlayerScrubCursor(MEDIA_SCRUB_STEP_MS);
                } else {
                    adjustVolume(true);
                }
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_BRIGHTNESS) {
            if (keyCode == 21) {
                currentSystemBrightness = Math.max(10, currentSystemBrightness - 15);
                updateBrightness(currentSystemBrightness);
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                currentSystemBrightness = Math.min(255, currentSystemBrightness + 15);
                updateBrightness(currentSystemBrightness);
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_STORAGE) {
            return true;
        }

        if (currentScreenState == STATE_WEBSERVER) {
            return true;
        }

        if (currentScreenState == STATE_MENU || currentScreenState == STATE_BROWSER
                || currentScreenState == STATE_SETTINGS || currentScreenState == STATE_BLUETOOTH
                || currentScreenState == STATE_WIFI || currentScreenState == STATE_PODCASTS
                || currentScreenState == STATE_SOULSEEK) {
            // 🚀 [여기서부터 덮어쓰기!] 초고속 리스트뷰가 켜져있을 때는, 시스템 본연의 부드러운 스크롤 엔진에 휠 신호를 넘깁니다!
            if (currentScreenState == STATE_BROWSER && listVirtualSongs != null && listVirtualSongs.getVisibility() == View.VISIBLE) {

                long now = System.currentTimeMillis();
                boolean isFastScroll = false;

                // 💡 [오토매틱 엔진] 0.05초(50ms) 이내에 연속으로 휠이 3칸 이상 돌아가면 '고속 점프 모드' 발동!
                if (now - lastWheelTime < 50) {
                    wheelFastCount++;
                    if (wheelFastCount >= 3) isFastScroll = true;
                } else {
                    wheelFastCount = 0; // 천천히 돌리면 즉시 초기화
                }
                lastWheelTime = now;

                if (isFastScroll && !currentScrollIndexList.isEmpty()) {
                    // 🚀🚀 [고속 점프 모드] 알파벳(첫 글자) 단위로 뭉텅뭉텅 스크롤!
                    int currentPos = listVirtualSongs.getSelectedItemPosition();
                    if (currentPos < 0) currentPos = 0;
                    char currentChar = getInitialChar(currentScrollIndexList.get(currentPos));
                    int targetPos = currentPos;

                    if (keyCode == 22) { // 휠 아래로 휙! 돌릴 때 (다음 알파벳 찾기)
                        for (int i = currentPos + 1; i < currentScrollIndexList.size(); i++) {
                            if (getInitialChar(currentScrollIndexList.get(i)) != currentChar) {
                                targetPos = i;
                                break;
                            }
                        }
                    } else if (keyCode == 21) { // 휠 위로 휙! 돌릴 때 (이전 알파벳 시작점 찾기)
                        char targetChar = currentChar;
                        boolean foundPrevChar = false;
                        for (int i = currentPos - 1; i >= 0; i--) {
                            char c = getInitialChar(currentScrollIndexList.get(i));
                            if (!foundPrevChar && c != currentChar) {
                                foundPrevChar = true;
                                targetChar = c;
                            }
                            if (foundPrevChar && c != targetChar) {
                                targetPos = i + 1;
                                break;
                            }
                            if (i == 0) targetPos = 0;
                        }
                    }
                    listVirtualSongs.setSelection(targetPos);
                    if (targetPos >= 0 && targetPos < currentScrollIndexList.size()) {
                        showFastScrollLetter(currentScrollIndexList.get(targetPos));
                    }
                    clickFeedback();
                    return true;
                } else {
                    // 🐢🐢 [일반 주행 모드] 평소처럼 천천히 정확하게 1곡씩 이동!
                    if (keyCode == 21) {
                        listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                        listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
                        clickFeedback();
                        return true;
                    }
                    if (keyCode == 22) {
                        listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                        listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                        clickFeedback();
                        return true;
                    }
                }
            }
            // 🚀 [여기까지 덮어쓰기 완료!]
            if (currentScreenState == STATE_SETTINGS && isHomeScreenArrangeScreen()
                    && homeScreenMoveModeId != null) {
                int delta = mapWheelToMenuMove(keyCode);
                if (delta != 0) {
                    List<String> ids = HomeMenuConfig.loadHomeOrderIds(prefs);
                    int idx = ids.indexOf(homeScreenMoveModeId);
                    int newIdx = idx + delta;
                    if (idx >= 0 && newIdx >= 0 && newIdx < ids.size()) {
                        HomeMenuConfig.move(prefs, idx, newIdx);
                        buildHomeMenu();
                        homeScreenOrderFocusIndex = newIdx + 1;
                        reorderHomeArrangeRows(false);
                        refreshHomeArrangeMoveUi(false);
                        restoreHomeScreenEditorFocus(homeScreenOrderFocusIndex);
                        clickFeedback();
                    }
                    return true;
                }
            }
            if (currentScreenState == STATE_SETTINGS && isHomeMoreArrangeScreen()
                    && homeMoreMoveModeId != null) {
                int delta = mapWheelToMenuMove(keyCode);
                if (delta != 0) {
                    List<String> ids = HomeMenuConfig.loadMoreOrderIds(prefs);
                    int idx = ids.indexOf(homeMoreMoveModeId);
                    int newIdx = idx + delta;
                    if (idx >= 0 && newIdx >= 0 && newIdx < ids.size()) {
                        HomeMenuConfig.moveMore(prefs, idx, newIdx);
                        buildHomeMenu();
                        homeScreenOrderFocusIndex = newIdx + 1;
                        reorderHomeArrangeRows(true);
                        refreshHomeArrangeMoveUi(true);
                        restoreHomeScreenEditorFocus(homeScreenOrderFocusIndex);
                        clickFeedback();
                    }
                    return true;
                }
            }
            if (currentScreenState == STATE_SETTINGS && isMusicQueueEditorScreen()
                    && musicQueueMoveFrom >= 0) {
                int delta = mapWheelToMenuMove(keyCode);
                if (delta != 0) {
                    int newIdx = nextMusicQueueMoveIndex(musicQueueMoveFrom, delta);
                    if (newIdx != musicQueueMoveFrom) {
                        applyMusicQueueMove(musicQueueMoveFrom, newIdx);
                        clickFeedback();
                    }
                    return true;
                }
            }
            if (currentScreenState == STATE_SETTINGS && isMusicQueueEditorScreen()
                    && musicQueueMoveFrom < 0 && listMusicQueue != null
                    && listMusicQueue.getVisibility() == View.VISIBLE
                    && (keyCode == 21 || keyCode == 22)) {
                if (keyCode == 21) {
                    listMusicQueue.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                    listMusicQueue.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
                } else {
                    listMusicQueue.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                    listMusicQueue.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                }
                clickFeedback();
                return true;
            }
            if (currentScreenState == STATE_SETTINGS && isThemeListActive()
                    && (keyCode == 21 || keyCode == 22)) {
                dispatchThemeListKey(keyCode);
                clickFeedback();
                return true;
            }
            if (currentScreenState == STATE_MENU && (keyCode == 21 || keyCode == 22)) {
                if (moveHomeMenuFocus(keyCode == 21 ? -1 : 1)) clickFeedback();
                return true;
            }
            View c = getCurrentFocus();
            if (c != null) {
                if (keyCode == 21) { // 휠 위로 돌릴 때 (UP)
                    // 🚀 [점프 완벽 차단] 좌표 검색(focusSearch)을 버리고 리스트 순서(Index)를 직접 조작합니다!
                    android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                    if (parent instanceof LinearLayout) {
                        int index = parent.indexOfChild(c);
                        // 무조건 바로 위(-1)의 곡으로만 이동
                        for (int i = index - 1; i >= 0; i--) {
                            View n = parent.getChildAt(i);
                            if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()
                                    && n.isEnabled()) {
                                if (n.requestFocus()) break;
                                continue;
                            }
                        }
                    } else {
                        View n = c.focusSearch(View.FOCUS_UP);
                        if (n != null) n.requestFocus();
                    }
                    clickFeedback();
                    return true;
                }
                if (keyCode == 22) { // 휠 아래로 돌릴 때 (DOWN)
                    android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                    if (parent instanceof LinearLayout) {
                        int index = parent.indexOfChild(c);
                        // 무조건 바로 아래(+1)의 곡으로만 이동
                        for (int i = index + 1; i < parent.getChildCount(); i++) {
                            View n = parent.getChildAt(i);
                            if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()
                                    && n.isEnabled()) {
                                if (n.requestFocus()) break;
                                continue;
                            }
                        }
                    } else {
                        View n = c.focusSearch(View.FOCUS_DOWN);
                        if (n != null) n.requestFocus();
                    }
                    clickFeedback();
                    return true;
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - lastScreenOnTime < 500);

        if (isWakingUp) {
            if (isMediaSkipKey(keyCode)) return handleMediaSkipKeyUp(keyCode, event);
            return true;
        }

        // 💡 [핵심 차단 구역] 휠 조작(21, 22)을 '뗄 때'
        if (keyCode == 21 || keyCode == 22) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long held = System.currentTimeMillis() - backKeyDownTime;
            if (themedContextMenu != null && themedContextMenu.isShowing()) {
                if (held < BACK_LONG_PRESS_MS) {
                    dismissThemedContextMenu();
                }
                return true;
            }
            if (held >= BACK_LONG_PRESS_MS) {
                if (!backLongPressHandled) {
                    showThemedContextMenu();
                    backLongPressHandled = true;
                }
            } else {
                clickFeedback();
                handleBackShortPress();
            }
            return true;
        }

        if (currentScreenState == STATE_WIFI_KEYBOARD) {
            if (isMediaSkipKey(keyCode)) {
                if (System.currentTimeMillis() - keyboardMediaSkipDownAt < MEDIA_SKIP_LONG_PRESS_MS) {
                    if (isMediaNextKey(keyCode)) handleKeyboardMediaSpace();
                    else handleKeyboardMediaDel();
                }
                return true;
            }
            if (isMediaPlayPauseKey(keyCode)) {
                if (!keyboardPpLongHandled) {
                    clickFeedback();
                    handleKeyboardEnter();
                }
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {
            return true;
        }
        if (isMediaSkipKey(keyCode)) {
            return handleMediaSkipKeyUp(keyCode, event);
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacks(clockTask);
        progressHandler.removeCallbacks(updateProgressTask);
        progressHandler.removeCallbacks(podcastGrowingEdgePoll);
        volumeHandler.removeCallbacks(hideVolumeTask);
        soulseekUiHandler.removeCallbacksAndMessages(null);
        fastScrollHandler.removeCallbacksAndMessages(null);

        if (isServerRunning && webServer != null) {
            webServer.stopServer();
            isServerRunning = false;
        }

        if (currentFileInputStream != null) {
            try {
                currentFileInputStream.close();
            } catch (Exception e) {
            }
        }

        // 💡 앱이 꺼질 때 엔진도 안전하게 종료
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        // 🚀 [스크린 오프 완벽 제어 4단계] 앱 종료 시 권한 반납
        if (mediaSessionShim != null && android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                mediaSessionShim.getClass().getMethod("release").invoke(mediaSessionShim);
            } catch (Exception e) {}
        }
        if (soulseekClient != null) {
            soulseekClient.shutdown();
            soulseekClient = null;
        }

        unregisterReceiver(systemStatusReceiver);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && bluetoothA2dp != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp);
            bluetoothA2dp = null;
        }
    }


    // 💡 안드로이드 시스템 자체의 하드웨어 삑 소리 스트림을 직접 차단/허용하는 함수
    private void applySoundSetting() {
        try {
            if (audioManager != null) {
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, !isSoundEffectEnabled);
            }
            // 💡 핵심: 기기 터치 패널의 하드웨어 삑 소리를 강제로 차단하는 시스템 설정 덮어쓰기!
            Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED,
                    isSoundEffectEnabled ? 1 : 0);
        } catch (Exception e) {
        }
    }

    private void migrateLegacyPrefs() {
        SharedPreferences solarPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (solarPrefs.contains("legacy_prefs_migrated")) return;
        SharedPreferences.Editor ed = solarPrefs.edit();
        copyPrefStore(getSharedPreferences("Y1_SETTINGS", MODE_PRIVATE), ed);
        if (ed != null) {
            ed.putBoolean("legacy_prefs_migrated", true);
            ed.commit();
        }
    }

    private void copyPrefStore(SharedPreferences from, SharedPreferences.Editor to) {
        for (java.util.Map.Entry<String, ?> e : from.getAll().entrySet()) {
            String key = e.getKey();
            if (to == null || key == null) continue;
            Object v = e.getValue();
            if (v instanceof String) to.putString(key, (String) v);
            else if (v instanceof Integer) to.putInt(key, (Integer) v);
            else if (v instanceof Boolean) to.putBoolean(key, (Boolean) v);
            else if (v instanceof Float) to.putFloat(key, (Float) v);
            else if (v instanceof Long) to.putLong(key, (Long) v);
        }
    }

    private void migrateBackgroundPrefs() {
        if (BG_MODE_ALBUM.equals(prefs.getString("background_mode", BG_MODE_THEME))) {
            prefs.edit().putString("background_mode", BG_MODE_THEME).apply();
        }
    }

    private String getBackgroundMode() {
        return prefs.getString("background_mode", BG_MODE_THEME);
    }

    private void applyThemeWallpaperPick(ThemeManager.WallpaperPick pick) {
        prefs.edit()
                .remove("bg_path")
                .putString(PREF_BG_THEME_WALLPAPER, pick.prefToken())
                .putString("background_mode", BG_MODE_THEME)
                .commit();
        updateMainMenuBackground();
        Toast.makeText(this, getString(R.string.toast_bg_applied), Toast.LENGTH_SHORT).show();
    }

    private boolean isWallpaperPickActive(ThemeManager.WallpaperPick pick) {
        if (BG_MODE_CUSTOM.equals(getBackgroundMode())) return false;
        String saved = prefs.getString(PREF_BG_THEME_WALLPAPER, null);
        if (saved != null) return saved.equals(pick.prefToken());
        ThemeManager.ThemeEntry cur = ThemeManager.getCurrentTheme();
        return pick.themeFolder.equalsIgnoreCase(cur.folderName)
                && ThemeManager.WallpaperPick.KEY_DESKTOP.equals(pick.configKey);
    }

    private boolean isCustomWallpaperSelected() {
        return BG_MODE_CUSTOM.equals(getBackgroundMode()) && prefs.contains("bg_path");
    }

    private String wallpaperPickLabel(ThemeManager.WallpaperPick pick) {
        String kind = ThemeManager.WallpaperPick.KEY_DESKTOP.equals(pick.configKey)
                ? getString(R.string.bg_wallpaper_desktop)
                : getString(R.string.bg_wallpaper_global);
        String label = getString(R.string.bg_wallpaper_theme_row, pick.themeName, kind);
        if (isWallpaperPickActive(pick)) label = "✔ " + label;
        return label;
    }

    private File getCoversFolder() {
        File covers = new File("/storage/sdcard0/Solar_Covers");
        if (!covers.exists()) covers.mkdirs();
        return covers;
    }

    private void applyDesktopMask() {
        updateScreenBackground(currentScreenState);
    }

    private android.graphics.drawable.Drawable createRowBackground(boolean focused) {
        return getY1RowBackground(focused, y1ActiveRowWidthPx());
    }

    // 💡 안드로이드 하드웨어 가속(RenderScript)을 이용한 고화질 가우시안 블러 함수!
    private Bitmap applyGaussianBlur(Bitmap original) {
        if (original == null)
            return null;
        try {
            Bitmap output = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
            RenderScript rs = RenderScript.create(this);
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            Allocation inAlloc = Allocation.createFromBitmap(rs, original, Allocation.MipmapControl.MIPMAP_NONE,
                    Allocation.USAGE_SCRIPT);
            Allocation outAlloc = Allocation.createFromBitmap(rs, output);

            script.setRadius(25f); // 💡 블러 강도 설정 (0.0 ~ 25.0 범위, 25가 최대)
            script.setInput(inAlloc);
            script.forEach(outAlloc);
            outAlloc.copyTo(output);
            rs.destroy();

            return output;
        } catch (Exception e) {
            return original;
        }
    }

    // 💡 1. 날짜/시간 설정 메인 화면 (시간 오류 및 포커스 락 버그 완벽 수정 버전)
    private void buildDateTimeUI() {
        setSettingsSubScreen(SettingsScreens.DATETIME);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        final LinearLayout rowYear = createSettingsRow(RowKeys.DT_YEAR, R.string.datetime_year, false);
        rowYear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Year", 2020, 2035, dtYear);
            }
        });
        containerSettingsItems.addView(rowYear);

        final LinearLayout rowMonth = createSettingsRow(RowKeys.DT_MONTH, R.string.datetime_month, false);
        rowMonth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Month", 1, 12, dtMonth);
            }
        });
        containerSettingsItems.addView(rowMonth);

        final LinearLayout rowDay = createSettingsRow(RowKeys.DT_DAY, R.string.datetime_day, false);
        rowDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Day", 1, 31, dtDay);
            }
        });
        containerSettingsItems.addView(rowDay);

        final LinearLayout rowHour = createSettingsRow(RowKeys.DT_HOUR, R.string.datetime_hour, false);
        rowHour.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Hour", 0, 23, dtHour);
            }
        });
        containerSettingsItems.addView(rowHour);

        final LinearLayout rowMinute = createSettingsRow(RowKeys.DT_MINUTE, R.string.datetime_minute, false);
        rowMinute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Minute", 0, 59, dtMinute);
            }
        });
        containerSettingsItems.addView(rowMinute);


        final Button btnApply = createListButton(getString(R.string.datetime_apply));
        btnApply.setTextColor(0xFFFFFFFF);
        btnApply.setTypeface(null, android.graphics.Typeface.BOLD);
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                try {
                    // 🚀 [시간 오류 영구 해결] 기기의 기존 타임존을 건드리지 않고, 시간을 설정합니다.
                    // 기존 안드로이드의 `date` 명령어는 기기에 내장된 쉘(Toolbox vs Toybox)에 따라 파싱 방식이 완전히 달라,
                    // 잘못된 포맷이 들어가면 무조건 1970년이나 1980년으로 초기화(리셋)해버리는 심각한 버그가 있습니다.
                    // 이를 완벽히 방지하기 위해, 하나의 포맷을 적용해본 후 ➡️ 제대로 연도/월/일이 적용되었는지 확인하고 ➡️ 실패했다면 다음 포맷을
                    // 시도하는 자동 검증(Self-Verifying) 스크립트를 작성합니다!

                    String cmd = "settings put global auto_time 0; settings put system auto_time 0; ";

                    // 목표 날짜를 YYYYMMDD 형태로 만듭니다 (검증용)
                    String targetYMD = String.format(java.util.Locale.US, "%04d%02d%02d", dtYear, dtMonth, dtDay);

                    // 포맷 1: 구형 안드로이드(Toolbox) 전용 포맷 -> YYYYMMDD.HHmmss
                    String dateToolbox = String.format(java.util.Locale.US, "%04d%02d%02d.%02d%02d%02d", dtYear,
                            dtMonth, dtDay, dtHour, dtMinute, 0);
                    // 포맷 2: POSIX 국제 표준 포맷 (Toybox/Busybox 호환) -> MMDDhhmmYYYY.ss
                    String datePosix = String.format(java.util.Locale.US, "%02d%02d%02d%02d%04d.00", dtMonth, dtDay,
                            dtHour, dtMinute, dtYear);
                    // 포맷 3: 최신 안드로이드(Toybox) 문자열 포맷 -> YYYY-MM-DD HH:MM:SS
                    String dateString = String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", dtYear,
                            dtMonth, dtDay, dtHour, dtMinute, 0);

                    // 💡 자체 검증 쉘 스크립트:
                    // 1. Toolbox 포맷을 먼저 시도합니다. (Toybox 기기에서는 에러가 나거나 시간이 뒤틀립니다)
                    // 2. 적용된 시간을 즉시 확인하여 목표 날짜와 다르면(1970년 등으로 초기화되었으면) POSIX 포맷을 시도합니다.
                    // 3. 그래도 안 되면 문자열 포맷을 시도합니다.
                    String executeCmd = cmd +
                            "date -s " + dateToolbox + "; " +
                            "if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then " +
                            "  date " + datePosix + "; " +
                            "  if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then " +
                            "    date -s \"" + dateString + "\"; " +
                            "  fi; " +
                            "fi; " +
                            "hwclock -w; sync";

                    Process proc = Runtime.getRuntime().exec(new String[] { "su", "-c", executeCmd });
                    proc.waitFor(); // 💡 시스템에 시간이 완벽하게 적용될 때까지 잠깐 기다립니다.

                    // 시스템 전역에 시간이 변경되었음을 강제로 방송하여 메인 페이지 시계와 시스템 앱들을 동기화시킵니다.
                    sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));

                    Toast.makeText(MainActivity.this, getString(R.string.toast_time_applied), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_time_failed), Toast.LENGTH_SHORT).show();
                }

                // 🚀 [포커스 버그 해결 1] 오염된 인덱스를 'Date & Time Settings' 메뉴 위치(14번째 항목)로 강제 정화
                lastSettingsFocusIndex = 20;
                buildSettingsUI();

                // 🚀 [포커스 버그 해결 2] 50ms의 미세한 안전 딜레이를 주어 UI 가 완벽히 배치된 후 포커스를 확실히 꽂아줍니다.
                containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (containerSettingsItems != null && containerSettingsItems.getChildCount() > 0) {
                            containerSettingsItems.getChildAt(containerSettingsItems.getChildCount() - 1)
                                    .requestFocus();
                        }
                    }
                }, 50);
            }
        });
        containerSettingsItems.addView(btnApply);

        final Button btnCancel = createListButton(getString(R.string.datetime_cancel));
        ThemeManager.applyThemedTextStyle(btnCancel, ThemeManager.getHintTextColor());
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();

                // 취소하고 나갈 때도 인덱스를 안전하게 복구하고 포커스를 인위적으로 매핑합니다.
                lastSettingsFocusIndex = 20;
                buildSettingsUI();
                containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (containerSettingsItems != null && containerSettingsItems.getChildCount() > 0) {
                            containerSettingsItems.getChildAt(containerSettingsItems.getChildCount() - 1)
                                    .requestFocus();
                        }
                    }
                }, 50);
            }
        });
        containerSettingsItems.addView(btnCancel);

        if (containerSettingsItems.getChildCount() > 1)
            containerSettingsItems.getChildAt(1).requestFocus();
    }

    // 💡 2. 숫자(년/월/일/시/분) 선택용 세로 리스트 화면
    private void buildDateTimeSelectorUI(final String type, int min, int max, int currentValue) {
        setSettingsSubScreen(SettingsScreens.EQ, type);
        updateStatusBarTitle();
        containerSettingsItems.removeAllViews();

        Button btnBack = createListButton(getString(R.string.common_cancel_back));
        styleSecondaryLabel(btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeUI();
            }
        });
        containerSettingsItems.addView(btnBack);

        Button focusBtn = null;
        for (int i = min; i <= max; i++) {
            final int val = i;
            String displayVal = (type.equals("Minute") || type.equals("Hour") || type.equals("Month")
                    || type.equals("Day")) ? String.format(java.util.Locale.US, "%02d", val) : String.valueOf(val);
            Button btn = createListButton(displayVal);
            btn.setGravity(android.view.Gravity.CENTER); // 가운데 정렬로 예쁘게!

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (type.equals("Year"))
                        dtYear = val;
                    else if (type.equals("Month"))
                        dtMonth = val;
                    else if (type.equals("Day"))
                        dtDay = val;
                    else if (type.equals("Hour"))
                        dtHour = val;
                    else if (type.equals("Minute"))
                        dtMinute = val;
                    buildDateTimeUI(); // 선택하면 자동으로 이전 화면으로 복귀!
                }
            });
            containerSettingsItems.addView(btn);
            if (val == currentValue)
                focusBtn = btn;
        }

        // 현재 설정되어 있는 시간으로 포커스 자동 이동
        if (focusBtn != null)
            focusBtn.requestFocus();
        else if (containerSettingsItems.getChildCount() > 1)
            containerSettingsItems.getChildAt(1).requestFocus();
    }
    // 💡 [화면 꺼짐 전용 수신기] 화면이 꺼진 상태에서 시스템이 버튼 신호를 여기로 쏴줍니다!
    public static class MediaBtnReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction()) && MainActivity.instance != null) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);

                if (event != null && MainActivity.instance.isScreenOffControlEnabled) {
                    int keyCode = event.getKeyCode();
                    if (MainActivity.instance.isMediaSkipKey(keyCode)) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            MainActivity.instance.handleMediaSkipKeyDown(keyCode, event);
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            MainActivity.instance.handleMediaSkipKeyUp(keyCode, event);
                        }
                        return;
                    }
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return;

                    // ⏯ 재생/일시정지 버튼
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {
                        MainActivity.instance.playOrPauseMusic();
                        MainActivity.instance.clickFeedback();
                    }
                    // 🔊 혹시 기기가 휠 조작(21, 22)을 미디어 신호로 보내줄 경우를 대비한 방어 코드
                    else if (keyCode == 21) {
                        MainActivity.instance.adjustVolume(false);
                        MainActivity.instance.clickFeedback();
                    }
                    else if (keyCode == 22) {
                        MainActivity.instance.adjustVolume(true);
                        MainActivity.instance.clickFeedback();
                    }
                }
            }
        }
    }
    // 💡 [수정] 메인 화면 미리보기도 재생 상태에 따라 아이콘을 똑똑하게 바꿉니다.
    private void refreshNowPlayingPreview() {
        boolean anyWidgetActive = isWidgetClockOn || isWidgetBatteryOn || isWidgetAlbumOn;
        if (anyWidgetActive) {
            if (tvMenuPreviewTitle != null) tvMenuPreviewTitle.setVisibility(View.GONE);
            if (tvMenuPreviewArtist != null) tvMenuPreviewArtist.setVisibility(View.GONE);
            refreshWidgets(); // 위젯 화면 즉시 새로고침!
            return; // 🚀 여기서 함수를 강제 종료하여 아래쪽의 기존 텍스트 부활 코드를 완전히 씹어버립니다.
        }
        if (isNowPlayingHomeFocused() && currentScreenState == STATE_MENU) {
            if (!playback.hasAnyQueue()) {
                ivMenuPreview.setImageResource(R.drawable.music_circle);
                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                    tvMenuPreviewTitle.setVisibility(View.GONE);
                    tvMenuPreviewArtist.setVisibility(View.GONE);
                }
            } else {
                if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
                    try {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
                        ivMenuPreview.setImageBitmap(bmp);
                    } catch (Exception e) {
                        ivMenuPreview.setImageResource(R.drawable.default_album);
                    }
                } else {
                    ivMenuPreview.setImageResource(R.drawable.default_album);
                }

                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                    tvMenuPreviewTitle.setVisibility(View.VISIBLE);
                    tvMenuPreviewArtist.setVisibility(View.VISIBLE);
                    tvMenuPreviewTitle.setText(tvPlayerTitle.getText());
                    tvMenuPreviewArtist.setText(tvPlayerArtist.getText());
                }
            }
        }
    }
    // 💡 [추가] 1. 인터넷에서 받아온 커버 이미지를 캐시 폴더에서 불러와 화면에 띄우는 함수
    private void applyCachedCoverArt(String imagePath) {
        try {
            // 중앙의 선명한 앨범 아트
            android.graphics.BitmapFactory.Options optsCenter = new android.graphics.BitmapFactory.Options();
            optsCenter.inSampleSize = 2;
            android.graphics.Bitmap bmpCenter = android.graphics.BitmapFactory.decodeFile(imagePath, optsCenter);
            ivAlbumArt.setImageBitmap(bmpCenter);

            // 🚀 [완벽 수정] 앨범 아트의 하단 중앙 색상을 스포이드로 정확히 뽑아냅니다!
            try {
                int centerX = bmpCenter.getWidth() / 2;
                int centerY = (int)(bmpCenter.getHeight() * 0.8); // 정중앙보다 약간 아래의 포인트 색상
                currentAlbumColor = bmpCenter.getPixel(centerX, centerY) | 0xFF000000;
            } catch (Exception e) {
                currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            }

            // 뒷배경 블러 처리
            android.graphics.BitmapFactory.Options optsBg = new android.graphics.BitmapFactory.Options();
            optsBg.inSampleSize = 4;
            android.graphics.Bitmap sourceBg = android.graphics.BitmapFactory.decodeFile(imagePath, optsBg);
            android.graphics.Bitmap blurredBg = applyGaussianBlur(sourceBg);
            ivPlayerBgBlur.setImageBitmap(blurredBg);
            if (sourceBg != blurredBg) sourceBg.recycle();
            if (!playerAlbumBlurEnabled) ivPlayerBgBlur.setImageResource(0);

            // 메인 메뉴 배경도 연동하기 위해 파일 데이터를 byte[]로 변환해서 lastAlbumArtBytes에 집어넣습니다!
            java.io.File file = new java.io.File(imagePath);
            int size = (int) file.length();
            byte[] bytes = new byte[size];
            java.io.BufferedInputStream buf = new java.io.BufferedInputStream(new java.io.FileInputStream(file));
            buf.read(bytes, 0, bytes.length);
            buf.close();

            lastAlbumArtBytes = bytes;
            updateMainMenuBackground();
            refreshNowPlayingPreview();

        } catch (Exception e) {}
    }

    // 💡 [수정] 정밀 검색 및 기존 태그(가수/제목) 보호 기능이 추가된 Deezer 스크래핑 엔진
    private void fetchTrackInfoFromInternet(final File track, final String originalQuery, final boolean hasValidTags, final String origTitle, final String origArtist) {
        // 찌꺼기 텍스트 청소기
        final String cleanQuery = originalQuery
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("^[0-9\\s\\-]+", "")
                .replaceAll("\\s[0-9]{2}\\s", " ")
                .trim();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, getString(R.string.toast_album_art_searching, cleanQuery), Toast.LENGTH_SHORT).show();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String query = java.net.URLEncoder.encode(cleanQuery, "UTF-8");
                    String urlString = "http://api.deezer.com/search?q=" + query;
                    java.net.URL url = new java.net.URL(urlString);

                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) throw new Exception("HTTP Response Code: " + responseCode);

                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();

                    org.json.JSONObject jsonResponse = new org.json.JSONObject(response.toString());
                    org.json.JSONArray dataArray = jsonResponse.optJSONArray("data");

                    if (dataArray != null && dataArray.length() > 0) {
                        org.json.JSONObject trackInfo = dataArray.getJSONObject(0);

                        // 서버에서 가져온 가수와 제목
                        final String fetchedTitle = trackInfo.getString("title");
                        final String fetchedArtist = trackInfo.getJSONObject("artist").getString("name");

                        // 🚀 [태그 보호 해제] 기존 파일에 잡다한 쓰레기 태그가 있더라도 무시하고, 인터넷의 정확한 공식 정보를 1순위로 강제 적용합니다!
                        final String finalTitle = fetchedTitle;
                        final String finalArtist = fetchedArtist;

                        String coverUrl = trackInfo.getJSONObject("album").getString("cover_xl").replace("https://", "http://");
                        java.net.URL imgUrl = new java.net.URL(coverUrl);
                        java.net.HttpURLConnection imgConn = (java.net.HttpURLConnection) imgUrl.openConnection();
                        java.io.InputStream in = imgConn.getInputStream();
                        final android.graphics.Bitmap coverBitmap = android.graphics.BitmapFactory.decodeStream(in);
                        in.close();

                        File coverFolder = getCoversFolder();
                        if (!coverFolder.exists()) coverFolder.mkdirs();
                        String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "");
                        final File coverFile = new File(coverFolder, safeFileName + ".jpg");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile);
                        coverBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                        fos.close();

                        // 🚀 [무조건 저장] 검색에 성공했다면 무조건 영구 저장소에 깔끔한 태그를 덮어씌웁니다.
                        prefs.edit()
                                .putString("meta_title_" + track.getAbsolutePath(), finalTitle)
                                .putString("meta_artist_" + track.getAbsolutePath(), finalArtist)
                                .commit();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (playback.musicPlaylist().isEmpty()) return;
                                Toast.makeText(MainActivity.this, getString(R.string.toast_album_art_updated), Toast.LENGTH_SHORT).show();
                                if (playback.musicPlaylist().get(playback.musicIndex()).getAbsolutePath().equals(track.getAbsolutePath())) {
                                    // 🚀 화면의 글씨도 즉각 공식 정보로 갈아치웁니다!
                                    tvPlayerTitle.setText(finalTitle);
                                    tvPlayerArtist.setText(finalArtist);
                                    applyCachedCoverArt(coverFile.getAbsolutePath());
                                }
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, getString(R.string.toast_album_art_none), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_connection_error), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
    // 💡 [수정] 속이 꽉 찬 리얼 파이(Pie) 차트 클래스
    public static class PieChartView extends View {
        private android.graphics.Paint paintBg;
        private android.graphics.Paint paintUsed;
        private float percentage = 0f;

        public PieChartView(Context context) {
            super(context);
            init();
        }

        private void init() {
            // 1. 남은 용량 (배경 원) - 은은한 반투명 흰색
            paintBg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paintBg.setStyle(android.graphics.Paint.Style.FILL); // 🚀 선(STROKE)에서 면(FILL)으로 변경!
            paintBg.setColor(0x33FFFFFF);

            // 2. 사용한 용량 (테마 색상 파이 조각)
            paintUsed = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paintUsed.setStyle(android.graphics.Paint.Style.FILL); // 🚀 면(FILL)으로 변경!
        }

        public void setStorageData(long used, long total, int themeColor) {
            if (total > 0) percentage = (float) used / total;
            paintUsed.setColor(themeColor);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float padding = 10f;
            android.graphics.RectF rect = new android.graphics.RectF(padding, padding, width - padding, height - padding);

            // 360도 전체 원 그리기 (남은 용량 베이스)
            canvas.drawArc(rect, 0, 360, true, paintBg);

            // 사용된 용량만큼 파이 조각 덮어 그리기 (시작점 -90도는 12시 방향)
            float sweepAngle = percentage * 360f;
            canvas.drawArc(rect, -90, sweepAngle, true, paintUsed); // 🚀 useCenter를 true로 하여 꽉 찬 조각을 만듭니다.
        }
    }
    // 💡 [추가] 화면에 존재하는 모든 글씨를 찾아내 테마 폰트로 갈아입히는 재귀 엔진!
    private void applyFontToAllViews(android.view.ViewGroup parent, android.graphics.Typeface font) {
        if (font == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);

            // 1. 만약 폴더(레이아웃)라면 안쪽으로 파고듭니다.
            if (child instanceof android.view.ViewGroup) {
                applyFontToAllViews((android.view.ViewGroup) child, font);
            }
            // 2. 만약 글씨(TextView, Button 등)라면 폰트를 즉시 교체합니다.
            else if (child instanceof android.widget.TextView) {
                // 기존에 굵은 글씨(Bold) 설정이 되어있었다면 그 특성은 유지해 줍니다!
                android.graphics.Typeface current = ((android.widget.TextView) child).getTypeface();
                int style = android.graphics.Typeface.NORMAL;
                if (current != null) style = current.getStyle();

                ((android.widget.TextView) child).setTypeface(font, style);
            }
        }
    }
    // 💡 [완벽 수정] 60fps 부드러운 애니메이션과 높이 제한이 적용된 와이드 스펙트럼 뷰!
    public static class AudioVisualizerView extends View {
        private byte[] fftData;
        private float[] currentHeights; // 🚀 부드러운 움직임을 위한 이전 높이 기억 장치
        private android.graphics.Paint paint;
        private int barCount = 40; // 🚀 막대기 개수를 늘려서 옆으로 쫙 퍼지게!

        public AudioVisualizerView(Context context) {
            super(context);
            paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            currentHeights = new float[barCount];
        }

        public void updateVisualizer(byte[] fft, int color) {
            this.fftData = fft;
            paint.setColor(color);
            // invalidate() 대신 onDraw 내부에서 무한 루프를 돌려 60fps를 방어합니다!
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            float barWidth = width / (float) barCount;
            paint.setStrokeWidth(barWidth * 0.4f); // 🚀 막대기 두께를 얇고 세련되게 (40%)

            if (fftData != null) {
                for (int i = 0; i < barCount && (i * 2 + 2) < fftData.length; i++) {
                    byte rfk = fftData[i * 2 + 2];
                    byte ifk = fftData[i * 2 + 3];
                    float magnitude = (float) Math.hypot(rfk, ifk);

                    // 🚀 1. 높이 제한: 아무리 소리가 커도 화면 높이의 85%를 넘지 못하게 캡을 씌웁니다.
                    float targetHeight = Math.min(height * 0.85f, (magnitude * height) / 100f);

                    // 🚀 2. 부드러운 보간: 목표 지점까지 한 번에 점프하지 않고 15%씩 스무스하게 따라갑니다.
                    currentHeights[i] += (targetHeight - currentHeights[i]) * 0.15f;
                }
            }

            // 그려내기
            for (int i = 0; i < barCount; i++) {
                float x = i * barWidth + (barWidth / 2f);
                canvas.drawLine(x, height, x, height - currentHeights[i], paint);
            }

            // 🚀 3. 화면에 보일 때는 초당 60번(16ms) 강제 새로고침하여 버벅임을 없앱니다.
            if (getVisibility() == View.VISIBLE) {
                postInvalidateDelayed(16);
            }
        }
    }
    // 💡 [위젯 전용] 모던하고 깔끔한 가로형 라운드 프로그레스 바(Pill 형태) 배터리 위젯!
    public static class WidgetBatteryBarView extends View {
        private android.graphics.Paint bgPaint, progressPaint, textPaint;
        private int level = 100;
        private boolean isCharging = false;
        private int baseColor = 0xFFFFFFFF;

        public WidgetBatteryBarView(Context context) {
            super(context);
            // 흐린 뒷배경 바
            bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(android.graphics.Paint.Style.FILL);

            // 테마 색상으로 채워지는 프로그레스 바
            progressPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStyle(android.graphics.Paint.Style.FILL);

            // 가운데 들어갈 숫자
            textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        public void setBatteryLevel(int level, boolean isCharging) {
            this.level = level;
            this.isCharging = isCharging;
            invalidate();
        }

        public void setColor(int color) {
            this.baseColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();

            // 🚀 스마트 컬러: 바가 채워지는 색상을 '테마의 포커스(하이라이트) 색상'으로 뽑아옵니다!
            int highlightColor;
            try { highlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000; }
            catch (Exception e) { highlightColor = baseColor; }

            if (isCharging) {
                progressPaint.setColor(0xFF44FF44);
            } else if (level <= 15) {
                progressPaint.setColor(0xFFFF4444);
            } else {
                progressPaint.setColor(highlightColor);
            }

            bgPaint.setColor(baseColor & 0x22FFFFFF); // 뒷배경 바는 아주 투명하게

            float radius = height / 2f; // 양끝을 완전히 둥글게(반원) 만듭니다.

            // 1. 흐린 배경 바 전체 그리기
            android.graphics.RectF bgRect = new android.graphics.RectF(0, 0, width, height);
            canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

            // 2. 테마 하이라이트 색상 게이지 그리기 (왼쪽에서 오른쪽으로 채워짐)
            float progressWidth = width * (level / 100f);
            android.graphics.RectF progressRect = new android.graphics.RectF(0, 0, progressWidth, height);
            canvas.drawRoundRect(progressRect, radius, radius, progressPaint);

            // 3. 한가운데에 배터리 숫자 딱 맞게 새기기
            textPaint.setColor(0xFFFFFFFF); // 글자는 묻히지 않게 항상 흰색으로 선명하게!
            textPaint.setTextSize(height * 0.6f);
            float textY = bgRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);

            // 보너스: 충전 중일 때는 예쁜 번개 아이콘을 추가합니다!
            String text = isCharging ? "⚡ " + level + "%" : level + "%";
            canvas.drawText(text, bgRect.centerX(), textY, textPaint);
        }
    }
    // 💡 [수정] 속이 꽉 찬 배터리 모양 안에 잔량(숫자)을 직관적으로 그려 넣는 뷰
    public static class BatteryIconView extends View {
        private android.graphics.Paint shellPaint, textPaint;
        private int level = 100;
        private boolean isCharging = false;
        private int color = 0xFFFFFFFF; // 기본 바탕색 (보통 흰색)

        public BatteryIconView(Context context) {
            super(context);

            // 배터리 바탕을 그리는 붓 (속을 꽉 채우기)
            shellPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            shellPaint.setStyle(android.graphics.Paint.Style.FILL);

            // 숫자를 그리는 붓 (검은색, 가운데 정렬, 굵게)
            textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(0xFF000000); // 🚀 검은색 글씨!
            textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        public void setBatteryLevel(int level, boolean isCharging) {
            this.level = level;
            this.isCharging = isCharging;
            invalidate(); // 화면 새로고침
        }

        public void setColor(int color) {
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();

            float pad = 2f;
            float terminalWidth = width * 0.08f;
            float shellWidth = width - terminalWidth - pad * 2;
            float shellHeight = height - pad * 2;

            // 🚀 스마트 컬러: 충전 중이면 초록색 바탕, 15% 이하면 빨간색 바탕, 평소엔 테마색(보통 흰색)
            if (isCharging) {
                shellPaint.setColor(0xFF44FF44);
            } else if (level <= 15) {
                shellPaint.setColor(0xFFFF4444);
            } else {
                shellPaint.setColor(color);
            }

            // 1. 꽉 찬 배터리 몸통 그리기
            android.graphics.RectF shell = new android.graphics.RectF(pad, pad, pad + shellWidth, pad + shellHeight);
            canvas.drawRoundRect(shell, 4f, 4f, shellPaint);

            // 2. 배터리 오른쪽 튀어나온 꼭지 그리기
            float terminalHeight = shellHeight * 0.4f;
            float terminalTop = pad + (shellHeight - terminalHeight) / 2;
            android.graphics.RectF terminal = new android.graphics.RectF(shell.right, terminalTop, shell.right + terminalWidth, terminalTop + terminalHeight);
            canvas.drawRoundRect(terminal, 2f, 2f, shellPaint);

            // 3. 배터리 몸통 정중앙에 숫자(잔량) 새기기
            textPaint.setTextSize(shellHeight * 0.95f); // 텍스트 크기를 배터리 높이에 꽉 차게 조절

            // 텍스트를 위아래 정중앙에 오도록 계산하는 공식
            float textY = shell.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
            String levelText = String.valueOf(level);

            // 검은색 숫자를 배터리 몸통 한가운데에 찍어냅니다.
            canvas.drawText(levelText, shell.centerX(), textY, textPaint);
        }
    }
    // ponytail: recycled ListView rows — queue + folder browser share library list pattern
    private static final class FolderBrowserEntry {
        static final int KIND_UP = 0;
        static final int KIND_FOLDER = 1;
        static final int KIND_AUDIO = 2;
        static final int KIND_APK = 3;
        static final int KIND_IMAGE = 4;

        final int kind;
        final File file;
        final String label;

        private FolderBrowserEntry(int kind, File file, String label) {
            this.kind = kind;
            this.file = file;
            this.label = label;
        }

        static FolderBrowserEntry up(String label) {
            return new FolderBrowserEntry(KIND_UP, null, label);
        }

        static FolderBrowserEntry folder(File f) {
            return new FolderBrowserEntry(KIND_FOLDER, f, f.getName());
        }

        static FolderBrowserEntry audio(File f) {
            return new FolderBrowserEntry(KIND_AUDIO, f, f.getName());
        }

        static FolderBrowserEntry apk(File f) {
            return new FolderBrowserEntry(KIND_APK, f, f.getName());
        }

        static FolderBrowserEntry image(File f) {
            return new FolderBrowserEntry(KIND_IMAGE, f, f.getName());
        }
    }

    private class FolderBrowserAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return folderBrowserEntries.size(); }
        @Override public Object getItem(int position) { return folderBrowserEntries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, android.view.ViewGroup parent) {
            final Button btn;
            if (convertView == null) {
                btn = createListButton("");
                btn.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                        android.widget.AbsListView.LayoutParams.WRAP_CONTENT));
            } else {
                btn = (Button) convertView;
            }
            final FolderBrowserEntry entry = folderBrowserEntries.get(position);
            String prefix = "📁 ";
            if (entry.kind == FolderBrowserEntry.KIND_AUDIO) prefix = "🎵 ";
            else if (entry.kind == FolderBrowserEntry.KIND_APK) prefix = "";
            else if (entry.kind == FolderBrowserEntry.KIND_IMAGE) prefix = "🖼 ";
            else if (entry.kind == FolderBrowserEntry.KIND_UP) prefix = "";
            if (entry.kind == FolderBrowserEntry.KIND_APK) {
                btn.setText(getString(R.string.browser_install_apk, entry.label));
                btn.setTextColor(0xFF00FFFF);
            } else {
                btn.setText(prefix + entry.label);
                ThemeManager.applyThemedTextStyle(btn, y1RowTextColorNormal(Y1_ROW_ITEM));
            }
            btn.setTag(entry.kind == FolderBrowserEntry.KIND_AUDIO ? entry.file : null);
            final int rowKind = Y1_ROW_ITEM;
            final int rowW = y1ActiveRowWidthPx();
            btn.setBackground(getY1RowBackground(false, rowW, rowKind));
            btn.setSelected(false);
            btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    btn.setBackground(getY1RowBackground(hasFocus, rowW, rowKind));
                    ThemeManager.applyThemedTextStyle(btn, hasFocus
                            ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                    btn.setSelected(hasFocus);
                    if (hasFocus) showFastScrollLetter(entry.label);
                }
            });
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (System.currentTimeMillis() < suppressListClickUntil) return;
                    clickFeedback();
                    if (entry.kind == FolderBrowserEntry.KIND_UP) {
                        File parentDir = currentFolder.getParentFile();
                        if (parentDir != null) currentFolder = parentDir;
                        buildFileBrowserUI();
                    } else if (entry.kind == FolderBrowserEntry.KIND_FOLDER && entry.file != null) {
                        currentFolder = entry.file;
                        buildFileBrowserUI();
                    } else if (entry.kind == FolderBrowserEntry.KIND_AUDIO && entry.file != null) {
                        setupFolderPlaylist(entry.file);
                    } else if (entry.kind == FolderBrowserEntry.KIND_APK && entry.file != null) {
                        installApk(entry.file);
                    } else if (entry.kind == FolderBrowserEntry.KIND_IMAGE && entry.file != null) {
                        try {
                            prefs.edit()
                                    .putString("bg_path", entry.file.getAbsolutePath())
                                    .putString("background_mode", BG_MODE_CUSTOM)
                                    .remove(PREF_BG_THEME_WALLPAPER)
                                    .commit();
                        } catch (Exception ignored) {}
                        updateMainMenuBackground();
                        Toast.makeText(MainActivity.this, getString(R.string.toast_bg_applied),
                                Toast.LENGTH_SHORT).show();
                        isPickingBackground = false;
                        changeScreen(STATE_MENU);
                    }
                }
            });
            return btn;
        }
    }

    private class ThemeUnifiedListAdapter extends android.widget.BaseAdapter {
        private static final int TYPE_BACK = 0;
        private static final int TYPE_FILTER = 1;
        private static final int TYPE_SECTION = 2;
        private static final int TYPE_STATUS = 3;
        private static final int TYPE_GET_MORE = 4;
        private static final int TYPE_ITEM = 5;

        @Override public int getViewTypeCount() { return 6; }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= themeBrowserRows.size()) return TYPE_ITEM;
            ThemeBrowser.Row row = themeBrowserRows.get(position);
            switch (row.kind) {
                case ThemeBrowser.KIND_BACK: return TYPE_BACK;
                case ThemeBrowser.KIND_FILTER: return TYPE_FILTER;
                case ThemeBrowser.KIND_SECTION: return TYPE_SECTION;
                case ThemeBrowser.KIND_STATUS: return TYPE_STATUS;
                case ThemeBrowser.KIND_GET_MORE: return TYPE_GET_MORE;
                default: return TYPE_ITEM;
            }
        }

        @Override public int getCount() { return themeBrowserRows.size(); }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, android.view.ViewGroup parent) {
            final ThemeBrowser.Row row = themeBrowserRows.get(position);
            final int kind = row.kind;

            if (kind == ThemeBrowser.KIND_BACK) {
                Button btn;
                if (convertView instanceof Button) {
                    btn = (Button) convertView;
                } else {
                    btn = createListButton(getString(R.string.common_back));
                    styleSecondaryLabel(btn);
                    btn.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT, y1RowHeightPx));
                }
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        clickFeedback();
                        onThemeBrowserRowClick(row);
                    }
                });
                bindThemeRowFocus(btn, position, row, Y1_ROW_MENU);
                return btn;
            }

            if (kind == ThemeBrowser.KIND_FILTER) {
                LinearLayout layout;
                if (convertView instanceof LinearLayout && "theme_filter".equals(convertView.getTag())) {
                    layout = (LinearLayout) convertView;
                } else {
                    layout = new LinearLayout(MainActivity.this);
                    layout.setTag("theme_filter");
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setFocusable(true);
                    layout.setSoundEffectsEnabled(false);
                    int hPad = (int) (10 * getResources().getDisplayMetrics().density);
                    layout.setPadding(hPad, hPad / 2, hPad, hPad / 2);
                    layout.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT, y1RowHeightPx));
                    TextView tvTitle = new TextView(MainActivity.this);
                    tvTitle.setTag("title");
                    tvTitle.setFocusable(false);
                    tvTitle.setSingleLine(true);
                    layout.addView(tvTitle);
                    TextView tvSub = new TextView(MainActivity.this);
                    tvSub.setTag("sub");
                    tvSub.setFocusable(false);
                    tvSub.setSingleLine(true);
                    layout.addView(tvSub);
                }
                TextView tvTitle = (TextView) layout.findViewWithTag("title");
                TextView tvSub = (TextView) layout.findViewWithTag("sub");
                tvTitle.setText(row.title);
                tvSub.setText(row.subtitle);
                ThemeManager.applyThemedTextStyle(tvTitle, ThemeManager.getTextColorPrimary());
                ThemeManager.applyThemedTextStyle(tvSub, ThemeManager.getTextColorSecondary());
                layout.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        clickFeedback();
                        onThemeBrowserRowClick(row);
                    }
                });
                bindThemeRowFocus(layout, position, row, Y1_ROW_MENU);
                return layout;
            }

            if (kind == ThemeBrowser.KIND_SECTION || kind == ThemeBrowser.KIND_STATUS) {
                TextView tv;
                String tag = kind == ThemeBrowser.KIND_SECTION ? "theme_section" : "theme_status";
                if (convertView instanceof TextView && tag.equals(convertView.getTag())) {
                    tv = (TextView) convertView;
                } else {
                    tv = new TextView(MainActivity.this);
                    tv.setTag(tag);
                    tv.setFocusable(kind == ThemeBrowser.KIND_STATUS);
                    tv.setSoundEffectsEnabled(false);
                    tv.setPadding(10, kind == ThemeBrowser.KIND_SECTION ? 16 : 8, 10, 4);
                    tv.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                            kind == ThemeBrowser.KIND_SECTION
                                    ? android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                                    : y1RowHeightPx));
                }
                tv.setText(row.title);
                ThemeManager.applyThemedTextStyle(tv, ThemeManager.getTextColorSecondary());
                if (kind == ThemeBrowser.KIND_SECTION) {
                    tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                }
                return tv;
            }

            if (kind == ThemeBrowser.KIND_GET_MORE) {
                Button btn;
                if (convertView instanceof Button && "theme_get_more".equals(convertView.getTag())) {
                    btn = (Button) convertView;
                } else {
                    btn = createListButton(getString(R.string.themes_get_more));
                    btn.setTag("theme_get_more");
                    btn.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT, y1RowHeightPx));
                }
                btn.setText(row.prefix + row.title);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        clickFeedback();
                        onThemeBrowserRowClick(row);
                    }
                });
                bindThemeRowFocus(btn, position, row, Y1_ROW_MENU);
                return btn;
            }

            LinearLayout layout;
            if (convertView instanceof LinearLayout && "theme_item".equals(convertView.getTag())) {
                layout = (LinearLayout) convertView;
            } else {
                layout = new LinearLayout(MainActivity.this);
                layout.setTag("theme_item");
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setFocusable(true);
                layout.setSoundEffectsEnabled(false);
                int hPad = (int) (10 * getResources().getDisplayMetrics().density);
                layout.setPadding(hPad, 0, hPad, 0);
                layout.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT, y1RowHeightPx));
                TextView tvMain = new TextView(MainActivity.this);
                tvMain.setTag("main");
                tvMain.setFocusable(false);
                tvMain.setSingleLine(true);
                tvMain.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                layout.addView(tvMain);
                TextView tvSub = new TextView(MainActivity.this);
                tvSub.setTag("sub");
                tvSub.setFocusable(false);
                tvSub.setSingleLine(true);
                tvSub.setVisibility(View.GONE);
                layout.addView(tvSub);
            }
            final TextView tvMain = (TextView) layout.findViewWithTag("main");
            final TextView tvSub = (TextView) layout.findViewWithTag("sub");
            tvMain.setText(row.prefix + row.title);
            if (row.subtitle != null && !row.subtitle.isEmpty()) {
                tvSub.setVisibility(View.VISIBLE);
                tvSub.setText(row.subtitle);
                ThemeManager.applyThemedTextStyle(tvSub, ThemeManager.getTextColorSecondary());
            } else {
                tvSub.setVisibility(View.GONE);
            }
            final boolean active = row.active;
            tvMain.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            if (active) {
                tvMain.setTypeface(null, android.graphics.Typeface.BOLD);
                tvMain.setTextColor(0xFF00FF00);
            } else {
                ThemeManager.applyThemedTextStyle(tvMain, y1RowTextColorNormal(Y1_ROW_ITEM));
            }
            layout.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    clickFeedback();
                    onThemeBrowserRowClick(row);
                }
            });
            bindThemeRowFocus(layout, position, row, Y1_ROW_ITEM);
            return layout;
        }

        private void bindThemeRowFocus(final View v, final int position, final ThemeBrowser.Row row, final int rowKind) {
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    int rowW = y1ActiveRowWidthPx();
                    v.setBackground(getY1RowBackground(hasFocus, rowW, rowKind));
                    if (rowKind == Y1_ROW_ITEM && v instanceof LinearLayout) {
                        TextView tvMain = (TextView) ((LinearLayout) v).findViewWithTag("main");
                        if (tvMain != null && !row.active) {
                            ThemeManager.applyThemedTextStyle(tvMain, hasFocus
                                    ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                        }
                        if (tvMain != null) {
                            tvMain.setSelected(hasFocus);
                            if (hasFocus) enableMarquee(tvMain);
                        }
                    } else if (v instanceof Button) {
                        ThemeManager.applyThemedTextStyle((Button) v, hasFocus
                                ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                    } else if (v instanceof LinearLayout && row.kind == ThemeBrowser.KIND_FILTER) {
                        TextView tvTitle = (TextView) ((LinearLayout) v).findViewWithTag("title");
                        if (tvTitle != null) {
                            ThemeManager.applyThemedTextStyle(tvTitle, hasFocus
                                    ? y1RowTextColorSelected(rowKind) : ThemeManager.getTextColorPrimary());
                        }
                    }
                    if (hasFocus) {
                        themeBrowserFocus = position;
                        if (row.kind == ThemeBrowser.KIND_INSTALLED && row.themeIndex >= 0
                                && row.themeIndex < ThemeManager.availableThemes.size()) {
                            updateInstalledThemePreview(ThemeManager.availableThemes.get(row.themeIndex));
                        } else if (row.catalog != null) {
                            updateThemeGalleryPreview(row.catalog, row.variant);
                        }
                    }
                }
            });
        }
    }

    private class MusicQueueListAdapter extends android.widget.BaseAdapter {
        private static final int TYPE_BACK = 0;
        private static final int TYPE_TRACK = 1;

        private java.util.List<File> snapshotQueue() {
            return new ArrayList<File>(playback.musicPlaylist());
        }

        @Override public int getViewTypeCount() { return 2; }
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_BACK : TYPE_TRACK;
        }
        @Override public int getCount() {
            return snapshotQueue().size() + 1;
        }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, android.view.ViewGroup parent) {
            try {
                return buildQueueRowView(position, convertView);
            } catch (Exception e) {
                Button err = createListButton("…");
                err.setEnabled(false);
                return err;
            }
        }

        private void applyMusicQueueListRowParams(View row) {
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT, y1RowHeightPx));
        }

        private View buildQueueRowView(final int position, View convertView) {
            if (position == 0) {
                Button btn;
                if (convertView instanceof Button) {
                    btn = (Button) convertView;
                } else {
                    btn = createListButton(getString(R.string.common_back_short));
                    applyMusicQueueListRowParams(btn);
                }
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        if (musicQueueMoveFrom >= 0) {
                            musicQueueMoveFrom = -1;
                            refreshMusicQueueList();
                        } else {
                            setMusicQueueListVisible(false);
                            changeScreen(musicQueueReturnScreen);
                        }
                    }
                });
                return btn;
            }

            final int queueIdx = position - 1;
            java.util.List<File> playlist = snapshotQueue();
            if (queueIdx < 0 || queueIdx >= playlist.size()) {
                View empty = new View(MainActivity.this);
                applyMusicQueueListRowParams(empty);
                return empty;
            }
            LinearLayout layout;
            if (convertView instanceof LinearLayout && "queue_row".equals(convertView.getTag())) {
                layout = (LinearLayout) convertView;
            } else {
                layout = createRearrangeListRow(null, "", null);
                layout.setTag("queue_row");
            }
            applyMusicQueueListRowParams(layout);
            layout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!(v instanceof LinearLayout)) return;
                    LinearLayout row = (LinearLayout) v;
                    boolean moving = musicQueueMoveFrom == queueIdx;
                    boolean nowPlaying = isMusicQueueNowPlayingSlot(queueIdx);
                    bindRearrangeRowAdornment(row, moving, hasFocus && !nowPlaying);
                    int rowW = y1ActiveRowWidthPx();
                    row.setBackground(getY1RowBackground(hasFocus, rowW, Y1_ROW_MENU));
                    TextView tvLeft = (TextView) row.findViewWithTag(TAG_REARRANGE_LABEL);
                    if (tvLeft != null) {
                        ThemeManager.applyThemedTextStyle(tvLeft, hasFocus
                                ? y1RowTextColorSelected(Y1_ROW_MENU) : y1RowTextColorNormal(Y1_ROW_MENU));
                        tvLeft.setSelected(hasFocus);
                        if (hasFocus) enableMarquee(tvLeft);
                    }
                    if (hasFocus) musicQueueEditorFocus = position;
                }
            });
            layout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (isMusicQueueNowPlayingSlot(queueIdx)) return;
                    if (musicQueueMoveFrom < 0) {
                        if (canPickMusicQueueMoveFrom(queueIdx)) {
                            musicQueueMoveFrom = queueIdx;
                            refreshMusicQueueList();
                        }
                    } else if (musicQueueMoveFrom == queueIdx) {
                        musicQueueMoveFrom = -1;
                        refreshMusicQueueList();
                    } else if (canDropMusicQueueMoveAt(queueIdx)) {
                        applyMusicQueueMove(musicQueueMoveFrom, queueIdx);
                    }
                }
            });

            final TextView tvLeft = (TextView) layout.findViewWithTag(TAG_REARRANGE_LABEL);
            final File track = playlist.get(queueIdx);
            if (tvLeft == null) return layout;
            final boolean nowPlaying = isMusicQueueNowPlayingSlot(queueIdx);
            final boolean moving = musicQueueMoveFrom == queueIdx;
            String num = String.format(Locale.US, "%02d · ", queueIdx + 1);
            tvLeft.setText(num + musicTrackLabel(track));
            tvLeft.setMaxLines(1);
            TextView tvSub = (TextView) layout.findViewWithTag("queue_sub");
            if (tvSub == null) {
                tvSub = new TextView(MainActivity.this);
                tvSub.setTag("queue_sub");
                tvSub.setTypeface(ThemeManager.getCustomFont());
                tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimension(R.dimen.y1_menu_text_size) * 0.78f);
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                subLp.leftMargin = (int) getResources().getDimension(R.dimen.y1_menu_text_pad_left);
                layout.addView(tvSub, 1, subLp);
                layout.setOrientation(LinearLayout.VERTICAL);
                applyMusicQueueListRowParams(layout);
                android.widget.AbsListView.LayoutParams lp = (android.widget.AbsListView.LayoutParams) layout.getLayoutParams();
                if (lp != null) lp.height = y1RowHeightPx * 2;
            }
            SongItem meta = resolveSongMetadata(track);
            String sub = meta != null ? (meta.artist + " · " + meta.album) : "";
            tvSub.setText(sub);
            ThemeManager.applyThemedTextStyle(tvSub, ThemeManager.getHintTextColor());
            ImageView pp = (ImageView) layout.findViewWithTag("queue_pp");
            if (nowPlaying) {
                if (pp == null) {
                    pp = new ImageView(MainActivity.this);
                    pp.setTag("queue_pp");
                    int sz = (int) (y1RowHeightPx * 0.45f);
                    LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(sz, sz);
                    ppLp.gravity = Gravity.CENTER_VERTICAL;
                    layout.addView(pp, ppLp);
                }
                boolean playing = false;
                try { playing = mediaPlayer != null && mediaPlayer.isPlaying(); } catch (Exception ignored) {}
                pp.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                pp.setColorFilter(ThemeManager.getItemTextColorSelected());
                pp.setVisibility(View.VISIBLE);
            } else if (pp != null) {
                pp.setVisibility(View.GONE);
            }
            tvLeft.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            if (nowPlaying) {
                tvLeft.setTypeface(null, android.graphics.Typeface.BOLD);
                tvLeft.setTextColor(0xFF00FF00);
            } else {
                ThemeManager.applyThemedTextStyle(tvLeft, layout.hasFocus()
                        ? y1RowTextColorSelected(Y1_ROW_MENU) : y1RowTextColorNormal(Y1_ROW_MENU));
            }
            bindRearrangeRowAdornment(layout, moving, layout.hasFocus() && !nowPlaying);
            int rowW = y1ActiveRowWidthPx();
            layout.setBackground(getY1RowBackground(layout.hasFocus(), rowW, Y1_ROW_MENU));
            return layout;
        }
    }
    private class SongListAdapter extends android.widget.BaseAdapter {
        private List<SongItem> items;

        public SongListAdapter(List<SongItem> items) { this.items = items; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, android.view.ViewGroup parent) {
            final Button btn;

            // 🚀 핵심 로직: 화면 밖으로 밀려난 기존 버튼(convertView)을 가져와서 재활용합니다!
            if (convertView == null) {
                btn = createListButton(""); // 처음 화면에 보이는 개수만큼만 새로 생성

                // 🚀 [버그 해결 1] 리스트뷰 전용 레이아웃 파라미터(규격)로 강제 변환합니다! (튕김 원천 차단)
                btn.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                        android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                ));
            } else {
                btn = (Button) convertView; // 나머지는 새로 만들지 않고 돌려쓰기!
            }

            final SongItem song = items.get(position);
            btn.setText("🎵 " + song.title); // 버튼 껍데기에 새 노래 이름만 덧칠합니다.

            // 포커스와 클릭 이벤트 재부여
            final int rowKind = Y1_ROW_ITEM;
            final int rowW = y1ActiveRowWidthPx();
            btn.setBackground(getY1RowBackground(false, rowW, rowKind));
            ThemeManager.applyThemedTextStyle(btn, y1RowTextColorNormal(rowKind));
            btn.setSelected(false);
            btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    btn.setBackground(getY1RowBackground(hasFocus, rowW, rowKind));
                    ThemeManager.applyThemedTextStyle(btn, hasFocus
                            ? y1RowTextColorSelected(rowKind) : y1RowTextColorNormal(rowKind));
                    btn.setSelected(hasFocus);
                    if (hasFocus) showFastScrollLetter(song.title);
                }
            });

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (System.currentTimeMillis() < suppressListClickUntil) return;
                    clickFeedback();
                    playTrackList(virtualSongList, position,
                            "PLAYLIST".equals(virtualQueryType) ? virtualQueryValue : null);
                }
            });

            return btn;
        }
    }
}