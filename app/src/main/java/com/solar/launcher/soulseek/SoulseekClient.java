package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import com.solar.launcher.Debug843b96Log;
import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.ReachPeerConnectivity;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ponytail: minimal Soulseek client — login, search, single-file download.
 * Ceiling: one download at a time; best-effort NAT traversal via PierceFirewall.
 */
public final class SoulseekClient extends Thread {
  private static final int PEER_READ_TIMEOUT_MS = 30000;
  private static final int PEER_CONNECT_MS = 10000;
  private static final int SEARCH_COLLECT_MS = 13000;
  private static final int FALLBACK_COLLECT_MS = 6000;
  private static final int FALLBACK_MIN_RESULTS = 8;
  private static final int DOWNLOAD_TRANSFER_TIMEOUT_MS = 300000;
  private static final int DOWNLOAD_PEER_WAIT_MS = 45000;
  private static final int DOWNLOAD_FILE_CONNECT_MS = 45000;
  private static final int OUTBOUND_FILE_WAIT_MS = 12000;
  private static final int NATPMP_RENEW_MS = 30 * 60 * 1000;
  private static final int NATPMP_RETRY_MS = 12_000;
  private static final int NATPMP_RETRY_MAX = 8;
  private static final int MAX_SEARCH_RESULTS = 150;
  private static final int MAX_SEARCH_NOTIFY = 80;
  public static final int REACH_EARLY_PLAY_PERCENT = 20;
  private static final String CONN_FILE = "F";
  private static final String CONN_PEER = "P";
  private static final File DEBUG_LOG = new File("/storage/sdcard0/solar_soulseek_log.txt");

  public static final class Result {
    public final String username;
    public final String filename;
    public final long size;
    public final int bitrate;
    public final int duration;
    public final boolean livePeer;
    public final boolean freeSlot;
    public final int speed;
    public final int queueLength;
    public final int qualityScore;

    public Result(String username, String filename, long size, int bitrate, int duration,
                  boolean livePeer, boolean freeSlot, int speed, int queueLength) {
      this.username = username;
      this.filename = filename;
      this.size = size;
      this.bitrate = bitrate;
      this.duration = duration;
      this.livePeer = livePeer;
      this.freeSlot = freeSlot;
      this.speed = speed;
      this.queueLength = queueLength;
      this.qualityScore = computeQualityScore(filename, size, duration, livePeer, freeSlot, speed,
              queueLength, null);
    }

    public String title() {
      int slash = Math.max(filename.lastIndexOf('\\'), filename.lastIndexOf('/'));
      return slash >= 0 ? filename.substring(slash + 1) : filename;
    }

    public String qualityStars() {
      if (freeSlot && speed >= 200_000) return "[***]";
      if (freeSlot || speed >= 500_000) return "[** ]";
      return "[*  ]";
    }

    /** Weighted rank: free slot 40%, upload speed 30%, queue pressure 30%. */
    static int computeQualityScore(String filename, long size, int duration, boolean livePeer,
                                   boolean freeSlot, int speed, int queueLength, String query) {
      int score = freeSlot ? 400_000 : 0;
      if (speed > 0) {
        long speedPts = (speed / 1024L) * 300_000L / 800_000L;
        score += (int) Math.min(300_000, speedPts);
      }
      int queuePts = 300_000;
      if (queueLength > 0) {
        queuePts = Math.max(0, 300_000 - Math.min(queueLength, 50) * 300_000 / 50);
      } else if (!freeSlot && speed <= 0) {
        queuePts = 50_000;
      }
      score += queuePts;
      score += filenameRelevance(filename, query);
      score += formatBonus(filename);
      score += sizeDurationSanity(size, duration);
      if (livePeer) score += 50_000;
      return score;
    }

    static int rankedScore(Result r, String query, Set<String> historyPeers) {
      int score = computeQualityScore(r.filename, r.size, r.duration, r.livePeer, r.freeSlot,
              r.speed, r.queueLength, query);
      if (historyPeers != null && r.username != null) {
        if (historyPeers.contains(r.username.toLowerCase(Locale.US))) score += 150_000;
      }
      return score;
    }

    static int filenameRelevance(String filename, String query) {
      if (query == null || query.trim().isEmpty() || filename == null) return 0;
      String path = filename.toLowerCase(Locale.US);
      String[] tokens = query.toLowerCase(Locale.US).trim().split("\\s+");
      if (tokens.length == 0) return 0;
      int hits = 0;
      for (String token : tokens) {
        if (token.length() >= 2 && path.contains(token)) hits++;
      }
      return hits * 50_000;
    }

    static int sizeDurationSanity(long size, int duration) {
      if (size <= 0 || duration <= 0) return 0;
      long kbps = (size * 8L) / (duration * 1000L);
      if (kbps >= 32 && kbps <= 400) return 5_000;
      if (kbps > 2000 || kbps < 16) return -10_000;
      return 0;
    }

    /** No free slot, unknown speed, not live — likely queued or stale distributed hit. */
    public boolean isLikelySlowDownload() {
      return !freeSlot && speed <= 0 && !livePeer;
    }

    /** Negative if {@code a} should rank above {@code b} (more likely to download). */
    public static int compareByDownloadReliability(Result a, Result b) {
      return compareByDownloadReliability(a, b, null, null);
    }

    public static int compareByDownloadReliability(Result a, Result b, String query,
                                                   Set<String> historyPeers) {
      boolean slowA = a.isLikelySlowDownload();
      boolean slowB = b.isLikelySlowDownload();
      if (slowA != slowB) return slowA ? 1 : -1;
      int scoreA = rankedScore(a, query, historyPeers);
      int scoreB = rankedScore(b, query, historyPeers);
      if (scoreA != scoreB) return scoreB - scoreA;
      if (a.freeSlot != b.freeSlot) return a.freeSlot ? -1 : 1;
      if (a.queueLength != b.queueLength) return Integer.compare(a.queueLength, b.queueLength);
      if (a.speed != b.speed) return Integer.compare(b.speed, a.speed);
      if (a.livePeer != b.livePeer) return a.livePeer ? -1 : 1;
      return Integer.compare(formatBonus(b.filename), formatBonus(a.filename));
    }

    static int formatBonus(String filename) {
      String ext = extension(filename);
      if ("mp3".equals(ext) || "m4a".equals(ext) || "aac".equals(ext)) return 3_000;
      if ("ogg".equals(ext) || "opus".equals(ext)) return 2_000;
      if ("flac".equals(ext) || "wav".equals(ext)) return 500;
      return 0;
    }

    public static final int HIGH_BITRATE_THRESHOLD_KBPS = 320;

    /** True when this result should be hidden by the Reach high-bitrate filter. */
    public boolean isOverBitrateThreshold() {
      return effectiveBitrateKbps() > HIGH_BITRATE_THRESHOLD_KBPS;
    }

    int effectiveBitrateKbps() {
      if (bitrate > 0) return bitrate;
      if (duration > 0 && size > 0) {
        long kbps = (size * 8L) / (duration * 1000L);
        if (kbps > 0 && kbps <= Integer.MAX_VALUE) return (int) kbps;
      }
      String ext = extension(filename);
      if ("flac".equals(ext) || "wav".equals(ext) || "ape".equals(ext)) return 9999;
      return 0;
    }

    private static String extension(String filename) {
      String name = filename;
      int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
      if (slash >= 0) name = name.substring(slash + 1);
      int dot = name.lastIndexOf('.');
      return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }
  }

  private static final class PendingDownload {
    final String username;
    final String filename;
    final int connectToken;
    long size;
    int transferToken = -1;
    volatile boolean transferAccepted;
    volatile long transferAcceptedAt;
    String peerHost;
    int peerPort;

    PendingDownload(String username, String filename, long size, int connectToken) {
      this.username = username;
      this.filename = filename;
      this.size = size;
      this.connectToken = connectToken;
    }
  }

  public interface Listener {
    void onStatus(String message);
    void onConnected(int listenPort);
    void onLoginFailed(String reason);
    void onSearchResult(Result result);
    void onSearchFinished(int token, int count);
    void onDownloadProgress(String filename, long done, long total);
    void onDownloadPartialReady(File partialFile, long done, long total);
    void onDownloadPhase(String phase, String detail);
    void onDownloadComplete(File file);
    void onError(String message);
  }

  public interface SocialListener {
    void onPrivateMessage(int messageId, int timestamp, String fromUser, String text);
    void onUserStatusUpdate(String username, int status, boolean privileged);
    /** Server MSG_GET_USER_STATS (36) or explicit stats refresh for a watched user. */
    void onUserStatsUpdate(String username, int avgSpeed, int files, int dirs);
    void onRoomList(List<SoulseekWire.RoomEntry> rooms);
    void onRoomMessage(String room, String sender, String text, int timestamp);
    void onRoomJoined(String room);
    void onRoomLeft(String room);
    void onRoomUserJoined(String room, String username);
    void onRoomUserLeft(String room, String username);
    void onRoomTickers(String room, List<SoulseekWire.RoomTickerEntry> tickers);
    void onRoomTickerAdded(String room, String username, String text);
    void onRoomTickerRemoved(String room, String username);
  }

  public interface PeerOperation {
    void run(Socket peer) throws Exception;
    void onError(String reason);
  }

  public interface UserInterestsCallback {
    void onInterests(String username, java.util.List<String> likes, java.util.List<String> dislikes);
    void onError(String reason);
  }

  public interface InterestUsersCallback {
    void onUsers(java.util.List<String> users);
    void onError(String reason);
  }

  private final String username;
  private final String password;
  private final String clientDescription;
  /** Static fallback when {@link #appContext} is null (tests). */
  private final String userBio;
  private volatile boolean profileSharingEnabled = true;
  private volatile boolean profileMessagingEnabled = true;
  private final File downloadDir;
  private final Context appContext;
  private final Listener listener;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicInteger searchToken = new AtomicInteger(1);
  private final List<Result> pendingResults = new CopyOnWriteArrayList<Result>();
  private final Set<String> seenResultKeys = Collections.synchronizedSet(new HashSet<String>());
  private final ConcurrentHashMap<String, PeerEndpoint> peerAddresses = new ConcurrentHashMap<String, PeerEndpoint>();
  private final ConcurrentHashMap<String, Socket> peerPSockets = new ConcurrentHashMap<String, Socket>();
  private final ConcurrentHashMap<Integer, LinkedBlockingQueue<Socket>> pierceWaiters =
      new ConcurrentHashMap<Integer, LinkedBlockingQueue<Socket>>();
  private final Object downloadLock = new Object();
  private volatile File pendingDestDir;
  private volatile PendingDownload pendingDownload;
  private volatile boolean downloadFinished;
  private volatile boolean downloadCancelled;
  private volatile String downloadFailureReason;
  private volatile Socket downloadPeerSocket;
  private volatile Socket downloadFileSocket;
  private volatile Thread downloadThread;
  private volatile Thread searchThread;
  private volatile boolean searchCancelled;
  private volatile boolean xferInProgress;
  private volatile SoulseekNatpmp.Result lastNatpmp;
  private volatile Thread natpmpRenewThread;
  private volatile Thread natpmpRetryThread;
  private volatile int distribSearchIgnored;
  private final Object serverLock = new Object();

  private Socket serverSocket;
  private ServerSocket listenSocket;
  private Socket distribSocket;
  private int listenPort = 61000;
  private int reportedListenPort = 61000;
  private volatile String activeSearchQuery = "";
  private volatile String originalSearchQuery = "";
  private volatile boolean broadFallbackGateActive = false;
  private volatile boolean outboundFTried;
  private final Set<Integer> cantConnectTokens = Collections.synchronizedSet(new HashSet<Integer>());
  private volatile int activeSearchToken;
  private volatile boolean loggedIn;
  private volatile boolean hasDistribParent;
  private final AtomicInteger pierceInFlight = new AtomicInteger();
  private final AtomicInteger incomingPeerHandlers = new AtomicInteger();
  private static final int MAX_PIERCE = 8;
  private static final int MAX_INCOMING_PEERS = 12;
  /** Fixed upload slot cap advertised in peer user-info (nicotine uploadslots). */
  private static final int MAX_UPLOAD_SLOTS = 6;
  private final Set<String> deniedPeers = Collections.synchronizedSet(new HashSet<String>());
  private volatile SoulseekShareIndex shareIndex = SoulseekShareIndex.empty();
  private volatile SoulseekSharePolicy sharePolicy = new SoulseekSharePolicy();
  private final Object uploadLock = new Object();
  private int activeUploads = 0;
  private final java.util.LinkedList<QueuedUpload> uploadQueue = new java.util.LinkedList<QueuedUpload>();
  private volatile Thread shareAnnounceThread;
  private volatile boolean shareAnnounceCoalesce;
  private volatile long lastShareAnnounceMs;
  private volatile int lastAnnouncedDirs = -1;
  private volatile int lastAnnouncedFiles = -1;
  private static final long SHARE_ANNOUNCE_MIN_INTERVAL_MS = 30_000;
  private volatile SocialListener socialListener;
  private final Set<String> joinedRooms = Collections.synchronizedSet(new HashSet<String>());
  private volatile String pendingJoinRoom;
  private volatile SoulseekUserDirectory.Callback pendingUserWatch;
  private volatile String pendingWatchUser;
  private volatile boolean messagingEnabled;
  private final java.util.HashMap<String, UserInterestsCallback> pendingUserInterests =
          new java.util.HashMap<String, UserInterestsCallback>();
  private volatile InterestUsersCallback pendingInterestUsersCallback;
  /** Users we have MSG_WATCH_USER'd; server may push GetUserStats for these. */
  private final Set<String> watchedUsers = Collections.synchronizedSet(new HashSet<String>());
  /** Last known status per watched user (for offline→online stats refresh). */
  private final ConcurrentHashMap<String, Integer> peerLastStatus = new ConcurrentHashMap<String, Integer>();

  public void setSocialListener(SocialListener listener) {
    socialListener = listener;
  }

  public void setMessagingEnabled(boolean enabled) {
    messagingEnabled = enabled;
  }

  public boolean isUploadInProgress() {
    synchronized (uploadLock) {
      return activeUploads > 0;
    }
  }

  public int getActiveUploadCount() {
    synchronized (uploadLock) {
      return activeUploads;
    }
  }

  public int getUploadQueueDepth() {
    synchronized (uploadLock) {
      return uploadQueue.size();
    }
  }

  public boolean isBusy() {
    return isTransferActive() || isUploadInProgress() || isSearchActive()
            || sharePolicy.shouldKeepClientAlive();
  }

  public String getUsername() {
    return username;
  }

  public void setShareIndex(SoulseekShareIndex index) {
    shareIndex = index != null ? index : SoulseekShareIndex.empty();
  }

  public void setSharePolicy(SoulseekSharePolicy policy) {
    sharePolicy = policy != null ? policy : new SoulseekSharePolicy();
  }

  public SoulseekSharePolicy getSharePolicy() {
    return sharePolicy;
  }

  public int getSharedFileCount() {
    return shareIndex.fileCount();
  }

  public void refreshShareAnnouncement() {
    if (!running.get()) return;
    shareAnnounceCoalesce = true;
    if (isSearchActive()) return;
    int dirs = shareIndex.dirCount();
    int files = shareIndex.fileCount();
    long now = System.currentTimeMillis();
    if (lastShareAnnounceMs > 0
            && now - lastShareAnnounceMs < SHARE_ANNOUNCE_MIN_INTERVAL_MS
            && dirs == lastAnnouncedDirs && files == lastAnnouncedFiles) {
      // #region agent log
      try {
        agentLog("SoulseekClient.refreshShareAnnouncement", "debounced", "H1",
            new JSONObject().put("dirs", dirs).put("files", files)
                .put("ageMs", now - lastShareAnnounceMs));
      } catch (Exception ignored) {}
      // #endregion
      return;
    }
    if (shareAnnounceThread != null && shareAnnounceThread.isAlive()) return;
    shareAnnounceThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          do {
            shareAnnounceCoalesce = false;
            runShareAnnouncementOnce();
          } while (shareAnnounceCoalesce);
        } finally {
          shareAnnounceThread = null;
        }
      }
    }, "ShareAnnounce");
    shareAnnounceThread.setDaemon(true);
    shareAnnounceThread.start();
  }

  private void runShareAnnouncementOnce() {
    if (!running.get()) return;
    if (isSearchActive()) {
      shareAnnounceCoalesce = true;
      return;
    }
    try {
      if (!sharePolicy.announceShares()) {
        announceShareCountsIfConnected(0, 0);
        // #region agent log
        agentLog("SoulseekClient.runShareAnnouncementOnce", "skip sharing off", "H1",
            new JSONObject().put("announceShares", false));
        // #endregion
        return;
      }
      ensureConnected();
      if (!running.get()) return;
      synchronized (serverLock) {
        if (!loggedIn || !running.get()) return;
        int dirs = shareIndex.dirCount();
        int files = shareIndex.fileCount();
        announceShareCountsLocked(dirs, files);
        lastShareAnnounceMs = System.currentTimeMillis();
        lastAnnouncedDirs = dirs;
        lastAnnouncedFiles = files;
      }
    } catch (Exception e) {
      debugLog("share announce fail: " + e);
      // #region agent log
      try {
        agentLog("SoulseekClient.runShareAnnouncementOnce", "fail", "H1",
            new JSONObject().put("err", e.getClass().getSimpleName())
                .put("msg", e.getMessage() != null ? e.getMessage() : "")
                .put("running", running.get()));
      } catch (Exception ignored) {}
      // #endregion
      if (isTransientConnectError(e)) {
        synchronized (serverLock) {
          invalidateServerSessionLocked("share announce");
        }
      }
    }
  }

  private void announceShareCountsIfConnected(int dirs, int files) {
    synchronized (serverLock) {
      if (!loggedIn || serverSocket == null || serverSocket.isClosed()) return;
      try {
        announceShareCountsLocked(dirs, files);
      } catch (Exception e) {
        debugLog("share announce fail: " + e);
      }
    }
  }

  private void announceShareCountsLocked(int dirs, int files) throws IOException {
    // ponytail: remote peers only see non-zero counts when announceShares() is true (Wi‑Fi + NAT-PMP).
    sendServerLocked(SoulseekWire.MSG_SHARED_FOLDER_FILES,
        SoulseekWire.packSharedFolderCounts(dirs, files));
    debugLog("share announce dirs=" + dirs + " files=" + files);
    // #region agent log
    try {
      agentLog("SoulseekClient.runShareAnnouncementOnce", "announced", "H1",
          new JSONObject().put("dirs", dirs).put("files", files));
    } catch (Exception ignored) {}
    // #endregion
  }

  public int getListenPort() {
    return reportedListenPort > 0 ? reportedListenPort : listenPort;
  }

  public boolean isNatMapped() {
    SoulseekNatpmp.Result r = lastNatpmp;
    return r != null && r.mapped();
  }

  public String getNatpmpStatusLine() {
    SoulseekNatpmp.Result r = lastNatpmp;
    if (r == null) return "NAT-PMP: not tried";
    if (r.mapped()) {
      if (r.externalIp != null && r.externalIp.length() > 0) {
        return "NAT-PMP: port " + r.publicPort + " · WAN " + r.externalIp;
      }
      return "NAT-PMP: port " + r.publicPort + " mapped";
    }
    if ("no_gateway".equals(r.status)) return "NAT-PMP: no gateway — check Wi‑Fi";
    if ("no_local_ip".equals(r.status)) return "NAT-PMP: no local IP — connect Wi‑Fi";
    if ("map_failed".equals(r.status)) {
      if (r.gatewayIp != null) return "NAT-PMP: router declined (gw " + r.gatewayIp + ")";
      return "NAT-PMP: router declined — forward manually";
    }
    return "NAT-PMP: unavailable — forward port " + listenPort;
  }

  public boolean isPeerDenied(String peerUser) {
    return deniedPeers.contains(peerUser.toLowerCase(Locale.US));
  }

  public void applyBlockedPeers(java.util.Collection<String> blocked) {
    deniedPeers.clear();
    if (blocked != null) {
      for (String p : blocked) {
        if (p != null && !p.isEmpty()) deniedPeers.add(p.toLowerCase(Locale.US));
      }
    }
  }

  public void blockPeer(final String peerUser) {
    banPeer(peerUser);
  }

  /** Upload deny only — peer can still message; does not add server ignore list. */
  public void banPeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    deniedPeers.add(peerUser.toLowerCase(Locale.US));
  }

  /** Server ignore list — suppresses PM notifications; does not deny uploads locally. */
  public void ignorePeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    runServerOp("IgnorePeer", new Runnable() {
      @Override
      public void run() {
        try {
          sendServerLocked(SoulseekWire.MSG_ADD_TO_IGNORELIST, SoulseekWire.packString(peerUser));
        } catch (Exception ignored) {}
      }
    });
  }

  public void unignorePeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    runServerOp("UnignorePeer", new Runnable() {
      @Override
      public void run() {
        try {
          sendServerLocked(SoulseekWire.MSG_UNIGNORE_USER, SoulseekWire.packString(peerUser));
        } catch (Exception ignored) {}
      }
    });
  }

  public void unbanPeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    deniedPeers.remove(peerUser.toLowerCase(Locale.US));
  }

  public void unblockPeer(final String peerUser) {
    unbanPeer(peerUser);
  }

  public void addFavoritePeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    runServerOp("AddFavorite", new Runnable() {
      @Override
      public void run() {
        try {
          sendServerLocked(SoulseekWire.MSG_ADD_TO_FAVORITES, SoulseekWire.packString(peerUser));
        } catch (Exception ignored) {}
      }
    });
  }

  public void removeFavoritePeer(final String peerUser) {
    if (peerUser == null || peerUser.trim().isEmpty()) return;
    runServerOp("RemoveFavorite", new Runnable() {
      @Override
      public void run() {
        try {
          sendServerLocked(SoulseekWire.MSG_REMOVE_FROM_FAVORITES, SoulseekWire.packString(peerUser));
        } catch (Exception ignored) {}
      }
    });
  }

  private void runServerOp(String name, Runnable op) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          op.run();
        } catch (Exception ignored) {}
      }
    }, name).start();
  }

  /** Lightweight login probe — no listen socket or background reader. */
    public static String testLogin(String user, String pass) {
        return testLoginWithMajor(user, pass, SoulseekWire.CLIENT_MAJOR, SoulseekWire.clientMinor());
    }

    /** Probe/register PM identities with Nicotine+ client id (same as diag relay session). */
    public static String testLoginPm(String user, String pass) {
        return testLoginWithMajor(user, pass, SoulseekWire.CLIENT_MAJOR_PM, SoulseekWire.CLIENT_MINOR_PM);
    }

    private static String testLoginWithMajor(String user, String pass, int major, int minor) {
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(SoulseekWire.SERVER_HOST, SoulseekWire.SERVER_PORT), 15000);
            OutputStream out = sock.getOutputStream();
            out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_LOGIN,
                    SoulseekWire.loginBody(user, pass, major, minor)));
            out.flush();
            SoulseekWire.ServerFrame greet = SoulseekWire.readServerFrame(sock.getInputStream());
            if (greet.code != SoulseekWire.MSG_LOGIN) return "Unexpected login response";
            SoulseekWire.Reader r = new SoulseekWire.Reader(greet.body);
            if (!r.readBool()) {
                return r.hasRemaining() ? r.readString() : "rejected";
            }
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "Login failed";
        } finally {
            closeQuietly(sock);
        }
    }

  private void markPeerDenied(String peerUser, String reason) {
    if (peerUser == null) return;
    String lower = reason != null ? reason.toLowerCase(Locale.US) : "";
    if (lower.contains("not shared") || lower.contains("banned")
            || lower.contains("not sharing") || lower.contains("upload denied")) {
      deniedPeers.add(peerUser.toLowerCase(Locale.US));
      debugLog("peer denied " + peerUser + " (" + reason + ")");
    }
  }

  private void notifyLoginFailed(String reason) {
    if (listener != null) listener.onLoginFailed(reason);
  }

  private void notifyConnected() {
    if (listener != null) listener.onConnected(listenPort);
  }

  public SoulseekClient(String username, String password, File downloadDir, Context context,
                        Listener listener) {
    this(username, password, downloadDir, context, listener, "Reach");
  }

  public SoulseekClient(String username, String password, File downloadDir, Context context,
                        Listener listener, String clientDescription) {
    this(username, password, downloadDir, context, listener, clientDescription, clientDescription);
  }

  public SoulseekClient(String username, String password, File downloadDir, Context context,
                        Listener listener, String clientDescription, String userBio) {
    super("SoulseekClient");
    this.username = username;
    this.password = password;
    this.clientDescription = clientDescription != null ? clientDescription : "Reach";
    this.userBio = userBio != null ? userBio : this.clientDescription;
    this.downloadDir = downloadDir;
    this.appContext = context != null ? context.getApplicationContext() : null;
    this.listener = listener;
    setDaemon(true);
  }

  public void shutdown() {
    running.set(false);
    shareAnnounceCoalesce = false;
    Thread announce = shareAnnounceThread;
    shareAnnounceThread = null;
    if (announce != null) announce.interrupt();
    Thread natRetry = natpmpRetryThread;
    natpmpRetryThread = null;
    if (natRetry != null) natRetry.interrupt();
    Thread natRenew = natpmpRenewThread;
    natpmpRenewThread = null;
    if (natRenew != null) natRenew.interrupt();
    cancelSearch();
    cancelDownload();
    synchronized (serverLock) {
      invalidateServerSessionLocked("shutdown");
    }
    closeQuietly(listenSocket);
    interrupt();
  }

  public void cancelSearch() {
    searchCancelled = true;
    Thread t = searchThread;
    if (t != null) t.interrupt();
  }

  public boolean isTransferActive() {
    Thread dt = downloadThread;
    return xferInProgress || isUploadInProgress() || (dt != null && dt.isAlive());
  }

  public boolean isSearchActive() {
    Thread t = searchThread;
    return t != null && t.isAlive();
  }

  /** Shutdown listen/server threads when no download, upload, search, or sharing duty. */
  public void pauseWhenIdle() {
    if (!isBusy()) shutdown();
  }

  public boolean isShutDown() {
    return !running.get();
  }

  /** Connect to Soulseek server in background (PMs, rooms — no peer NAT required). */
  public void warmConnectionAsync() {
    if (!running.get()) return;
    // #region agent log
    try {
      agentLog("SoulseekClient.warmConnectionAsync", "start", "H1",
          new JSONObject().put("running", running.get()).put("loggedIn", loggedIn)
              .put("listenPort", listenPort));
    } catch (Exception ignored) {}
    // #endregion
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          // #region agent log
          try {
            agentLog("SoulseekClient.warmConnectionAsync", "ok", "H1",
                new JSONObject().put("loggedIn", loggedIn).put("listenPort", listenPort)
                    .put("nat", lastNatpmp != null ? lastNatpmp.status : "null"));
          } catch (Exception ignored) {}
          // #endregion
        } catch (Exception e) {
          // #region agent log
          try {
            agentLog("SoulseekClient.warmConnectionAsync", "fail", "H1",
                new JSONObject().put("err", formatError(e)));
          } catch (Exception ignored) {}
          // #endregion
        }
      }
    }, "SoulseekWarm").start();
  }

  public void search(final String query) {
    cancelSearch();
    searchCancelled = false;
    final Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          runSearchQuery(query);
        } catch (InterruptedException e) {
          if (!searchCancelled) Thread.currentThread().interrupt();
        } catch (final Throwable err) {
          if (searchCancelled) return;
          if (isTransientConnectError(err)) {
            try {
              synchronized (serverLock) {
                invalidateServerSessionLocked("search retry");
              }
              sleepQuiet(600);
              if (!searchCancelled && running.get()) runSearchQuery(query);
              return;
            } catch (Throwable retryErr) {
              if (searchCancelled) return;
              reportSearchFailure(retryErr);
              return;
            }
          }
          reportSearchFailure(err);
        } finally {
          if (shareAnnounceCoalesce && !isSearchActive()) {
            refreshShareAnnouncement();
          }
          if (searchThread == Thread.currentThread()) searchThread = null;
        }
      }
    }, "SoulseekSearch");
    searchThread = t;
    t.start();
  }

  private void runSearchQuery(String query) throws Exception {
    ensureConnected();
    if (searchCancelled) return;
    originalSearchQuery = query.trim();
    broadFallbackGateActive = false;
    pendingResults.clear();
    seenResultKeys.clear();
    final int token = searchToken.getAndIncrement();
    activeSearchToken = token;
    synchronized (serverLock) {
      sendSearchLocked(token, query);
    }
    notifyStatus("Waiting for peers…");
    collectSearchWindow(SEARCH_COLLECT_MS);
    if (searchCancelled) return;

    if (pendingResults.size() < FALLBACK_MIN_RESULTS) {
      for (String fallbackQ : SoulseekSearchRanking.fallbackQueries(query)) {
        if (searchCancelled) break;
        if (pendingResults.size() >= FALLBACK_MIN_RESULTS) break;
        broadFallbackGateActive = SoulseekSearchRanking.isBroadFallbackQuery(query, fallbackQ);
        notifyStatus("Widening search…");
        final int fbToken = searchToken.getAndIncrement();
        activeSearchToken = fbToken;
        synchronized (serverLock) {
          sendSearchLocked(fbToken, fallbackQ);
        }
        collectSearchWindow(FALLBACK_COLLECT_MS);
        broadFallbackGateActive = false;
      }
    }
    broadFallbackGateActive = false;

    if (searchCancelled) return;
    if (listener != null) listener.onSearchFinished(activeSearchToken, pendingResults.size());
    // #region agent log
    try {
      agentLog("SoulseekClient.search", "search finished", "H3",
          new JSONObject().put("token", activeSearchToken).put("results", pendingResults.size())
              .put("q", originalSearchQuery));
    } catch (Exception ignored) {}
    // #endregion
  }

  private void collectSearchWindow(int collectMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + collectMs;
    while (System.currentTimeMillis() < deadline && !searchCancelled) {
      Thread.sleep(200);
    }
  }

  private void reportSearchFailure(Throwable err) {
    String msg = formatError(err);
    debugLog("search fail: " + err.getClass().getSimpleName() + " " + msg);
    // #region agent log
    try {
      agentLog("SoulseekClient.search", "search fail", "H2",
          new JSONObject().put("err", err.getClass().getSimpleName()).put("msg", msg));
    } catch (Exception ignored) {}
    // #endregion
    if (msg.startsWith("Login rejected:")) {
      notifyLoginFailed(msg.substring("Login rejected:".length()).trim());
    } else {
      notifyError(msg);
    }
    if (listener != null) {
      listener.onSearchFinished(activeSearchToken, pendingResults.size());
    }
  }

  private static boolean isTransientConnectError(Throwable err) {
    if (err == null) return false;
    String msg = err.getMessage();
    if (msg != null) {
      String lower = msg.toLowerCase(Locale.US);
      if (lower.contains("econnreset") || lower.contains("disconnected")
              || lower.contains("eof") || lower.contains("socket closed")
              || lower.contains("not connected")) {
        return true;
      }
    }
    return err instanceof java.net.SocketException || err instanceof java.io.EOFException;
  }

  public void cancelDownload() {
    downloadCancelled = true;
    closeQuietly(downloadPeerSocket);
    closeQuietly(downloadFileSocket);
    synchronized (downloadLock) {
      pendingDownload = null;
      downloadFinished = false;
      downloadFailureReason = null;
      downloadLock.notifyAll();
    }
    if (downloadThread != null) downloadThread.interrupt();
  }

  public void download(final Result result) {
    download(result, downloadDir);
  }

  public void download(final Result result, final File destDir) {
    final File target = destDir != null ? destDir : downloadDir;
    downloadThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          downloadCancelled = false;
          pendingDestDir = target;
          ensureConnected();
          queueDownload(result.username, result.filename);
        } catch (final Throwable t) {
          if (!downloadCancelled) {
            String msg = t.getMessage() != null ? t.getMessage() : "Download failed";
            if (msg.startsWith("Login rejected:")) {
              notifyLoginFailed(msg.substring("Login rejected:".length()).trim());
            } else {
              notifyError(msg);
            }
          }
        } finally {
          downloadThread = null;
          downloadPeerSocket = null;
          downloadFileSocket = null;
        }
      }
    }, "SoulseekDownload");
    downloadThread.start();
  }

  @Override
  public void run() {
    while (running.get()) {
      Socket sock = null;
      try {
        synchronized (serverLock) {
          while (running.get() && (serverSocket == null || serverSocket.isClosed())) {
            serverLock.wait(500);
          }
          if (!running.get()) break;
          sock = serverSocket;
        }
        InputStream in = sock.getInputStream();
        SoulseekWire.ServerFrame frame = SoulseekWire.readServerFrame(in);
        synchronized (serverLock) {
          if (sock != serverSocket) continue;
          handleServerFrameLocked(frame);
        }
      } catch (InterruptedException e) {
        break;
      } catch (Exception e) {
        if (sock == null) break;
        synchronized (serverLock) {
          if (sock != serverSocket) {
            debugLog("server read stale: " + e);
            continue;
          }
          invalidateServerSessionLocked("server read");
        }
        debugLog("server read error: " + e);
        // #region agent log
        try {
          agentLog("SoulseekClient.run", "server read error", "H2",
              new JSONObject().put("err", e.getClass().getSimpleName())
                  .put("msg", formatError(e)).put("running", running.get()));
        } catch (Exception ignored) {}
        // #endregion
        if (running.get()) notifyError(formatError(e));
        sleepQuiet(500);
      }
    }
  }

  private void sendServerLocked(int code, byte[] body) throws IOException {
    synchronized (serverLock) {
      if (serverSocket == null || serverSocket.isClosed()) throw new IOException("Not connected");
      serverSocket.getOutputStream().write(SoulseekWire.serverMessage(code, body));
      serverSocket.getOutputStream().flush();
    }
  }

  private void ensureConnected() throws Exception {
    if (!running.get()) throw new IOException("Client shutdown");
    synchronized (serverLock) {
      if (!running.get()) throw new IOException("Client shutdown");
      if (!isServerSessionAlive()) {
        connectAndLoginLocked();
      }
    }
    if (!running.get()) throw new IOException("Client shutdown");
    if (!isAlive()) start();
  }

  private boolean isServerSessionAlive() {
    return loggedIn && serverSocket != null && !serverSocket.isClosed() && serverSocket.isConnected();
  }

  /** True when the Reach server session is connected — safe for outbound PM fan-out. */
  public boolean isLoggedIn() {
    return isServerSessionAlive();
  }

  private void invalidateServerSessionLocked(String reason) {
    loggedIn = false;
    hasDistribParent = false;
    closeQuietly(serverSocket);
    serverSocket = null;
    closeQuietly(distribSocket);
    distribSocket = null;
    // #region agent log
    try {
      agentLog("SoulseekClient.invalidateServerSessionLocked", "session cleared", "H2",
          new JSONObject().put("reason", reason != null ? reason : ""));
    } catch (Exception ignored) {}
    // #endregion
  }

  private void connectAndLoginLocked() throws Exception {
    if (!running.get()) throw new IOException("Client shutdown");
    invalidateServerSessionLocked("reconnect");
    serverSocket = new Socket();
    serverSocket.connect(new InetSocketAddress(SoulseekWire.SERVER_HOST, SoulseekWire.SERVER_PORT), 15000);
    serverSocket.setKeepAlive(true);
    OutputStream out = serverSocket.getOutputStream();
    out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_LOGIN, SoulseekWire.loginBody(username, password)));
    out.flush();

    SoulseekWire.ServerFrame greet = SoulseekWire.readServerFrame(serverSocket.getInputStream());
    if (greet.code != SoulseekWire.MSG_LOGIN) throw new IOException("Unexpected login response");
    SoulseekWire.Reader r = new SoulseekWire.Reader(greet.body);
    if (!r.readBool()) {
      String reason = r.hasRemaining() ? r.readString() : "rejected";
      notifyLoginFailed(reason);
      throw new IOException("Login rejected: " + reason);
    }
    if (r.hasRemaining()) r.readString();
    if (r.hasRemaining()) r.readUInt32();
    if (r.hasRemaining()) r.readString();
    if (r.hasRemaining()) r.readBool();

    startListenSocket();
    SoulseekNatpmp.Result nat = mapListenPortWithRetry();
    lastNatpmp = nat;
    // #region agent log
    try {
      agentLog("SoulseekClient.connectAndLoginLocked", "nat mapped", "H5",
          new JSONObject().put("listen", listenPort).put("mapped", nat.mapped())
              .put("status", nat.status != null ? nat.status : ""));
    } catch (Exception ignored) {}
    // #endregion
    int reported = nat.publicPort > 0 ? nat.publicPort : listenPort;
    reportedListenPort = reported;
    if (nat.mapped()) {
      debugLog("natpmp ok " + listenPort + " -> " + reported
          + (nat.externalIp != null ? " wan=" + nat.externalIp : "")
          + (nat.gatewayIp != null ? " gw=" + nat.gatewayIp : ""));
    } else {
      debugLog("natpmp fail " + listenPort + " status=" + nat.status
          + (nat.gatewayIp != null ? " gw=" + nat.gatewayIp : ""));
      startNatpmpRetryUntilMapped();
    }
    startNatpmpRenewal();
    out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_SET_WAIT_PORT, SoulseekWire.packUInt32(reported)));
    out.flush();
    out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_SET_STATUS, SoulseekWire.packInt32(SoulseekWire.STATUS_ONLINE)));
    out.flush();
    out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_HAVE_NO_PARENT, SoulseekWire.packBool(true)));
    out.flush();
    out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_ACCEPT_CHILDREN, SoulseekWire.packBool(false)));
    out.flush();
    loggedIn = true;
    serverLock.notifyAll();
    syncLocalInterestsLocked();
    debugLog("login ok user=" + username + " listen=" + listenPort + " reported=" + reportedListenPort);
    phaseLog("dl0", true, "login listen=" + reportedListenPort);
    notifyStatus("Connected as " + username);
    notifyConnected();
    ReachPeerConnectivity.onServerLoginOk(appContext, nat);
    // #region agent log
    try {
      agentLog("SoulseekClient.connectAndLoginLocked", "login ok", "H2",
          new JSONObject().put("listen", listenPort).put("reported", reportedListenPort)
              .put("nat", lastNatpmp != null ? lastNatpmp.status : "null")
              .put("hasParent", hasDistribParent));
    } catch (Exception ignored) {}
    // #endregion
    if (running.get() && sharePolicy.announceShares()) refreshShareAnnouncement();
    announceUploadSpeedLocked();
  }

  /** Tell the server our upload speed (updates avgspeed in user watch / room lists). */
  private void announceUploadSpeedLocked() throws IOException {
    sendServerLocked(SoulseekWire.MSG_SEND_UPLOAD_SPEED,
        SoulseekWire.packUInt32(SoulseekWire.ADVERTISED_UPLOAD_SPEED));
    debugLog("upload speed announced " + SoulseekWire.ADVERTISED_UPLOAD_SPEED);
  }

  private void announceUploadSpeedAsync() {
    if (!running.get()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          synchronized (serverLock) {
            if (loggedIn) announceUploadSpeedLocked();
          }
        } catch (Exception e) {
          debugLog("upload speed announce fail: " + e);
        }
      }
    }, "SoulseekUploadSpeed").start();
  }

  private SoulseekNatpmp.Result mapListenPortWithRetry() {
    SoulseekNatpmp.Result nat = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
    if (nat.mapped()) return nat;
    sleepQuiet(600);
    SoulseekNatpmp.Result again = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
    return again.mapped() ? again : nat;
  }

  private void applyNatpmpResult(SoulseekNatpmp.Result nat) {
    lastNatpmp = nat;
    if (!nat.mapped()) return;
    int reported = nat.publicPort > 0 ? nat.publicPort : listenPort;
    if (reported == reportedListenPort) return;
    reportedListenPort = reported;
    try {
      synchronized (serverLock) {
        if (serverSocket != null && !serverSocket.isClosed()) {
          serverSocket.getOutputStream().write(SoulseekWire.serverMessage(
              SoulseekWire.MSG_SET_WAIT_PORT, SoulseekWire.packUInt32(reportedListenPort)));
          serverSocket.getOutputStream().flush();
          debugLog("natpmp updated wait port " + reportedListenPort);
        }
      }
    } catch (Exception ignored) {}
  }

  private void startNatpmpRetryUntilMapped() {
    if (natpmpRetryThread != null) return;
    natpmpRetryThread = new Thread(new Runnable() {
      @Override
      public void run() {
        for (int i = 0; i < NATPMP_RETRY_MAX && running.get() && loggedIn; i++) {
          sleepQuiet(NATPMP_RETRY_MS);
          if (!running.get() || !loggedIn) break;
          SoulseekNatpmp.Result nat = lastNatpmp;
          if (nat != null && nat.mapped()) break;
          nat = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
          if (nat.mapped()) {
            applyNatpmpResult(nat);
            debugLog("natpmp retry ok " + listenPort + " -> " + nat.publicPort);
            break;
          }
          lastNatpmp = nat;
        }
        if (appContext != null) {
          SoulseekNatpmp.Result fin = lastNatpmp;
          // #region agent log
          try {
            agentLog("SoulseekClient.startNatpmpRetryUntilMapped", "exhausted", "H5",
                new JSONObject().put("mapped", fin != null && fin.mapped())
                    .put("status", fin != null ? fin.status : "null"));
          } catch (Exception ignored) {}
          // #endregion
          if (fin == null || !fin.mapped()) {
            ReachPeerConnectivity.onNatRetriesExhausted(appContext, fin);
          }
        }
        natpmpRetryThread = null;
      }
    }, "SoulseekNatpmpRetry");
    natpmpRetryThread.setDaemon(true);
    natpmpRetryThread.start();
  }

  private void startNatpmpRenewal() {
    if (natpmpRenewThread != null) return;
    natpmpRenewThread = new Thread(new Runnable() {
      @Override
      public void run() {
        while (running.get() && loggedIn) {
          sleepQuiet(NATPMP_RENEW_MS);
          if (!running.get() || !loggedIn) break;
          SoulseekNatpmp.Result nat = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
          applyNatpmpResult(nat);
          if (!nat.mapped()) lastNatpmp = nat;
          if (appContext != null) {
            ReachPeerConnectivity.onNatRenewal(appContext, nat);
          }
        }
        natpmpRenewThread = null;
      }
    }, "SoulseekNatpmp");
    natpmpRenewThread.setDaemon(true);
    natpmpRenewThread.start();
  }

  private void notifyDownloadPhase(String phase, String detail) {
    if (listener != null) listener.onDownloadPhase(phase, detail);
    notifyStatus(phase + (detail != null && detail.length() > 0 ? " · " + detail : ""));
  }

  private void startListenSocket() throws IOException {
    closeQuietly(listenSocket);
    for (int p = 61000; p < 61100; p++) {
      try {
        listenSocket = new ServerSocket(p);
        listenSocket.setReuseAddress(true);
        listenPort = p;
        break;
      } catch (IOException ignored) {}
    }
    if (listenSocket == null) throw new IOException("No listen port");
    new Thread(new Runnable() {
      @Override
      public void run() {
        while (running.get() && listenSocket != null && !listenSocket.isClosed()) {
          try {
            final Socket peer = listenSocket.accept();
            if (incomingPeerHandlers.get() >= MAX_INCOMING_PEERS) {
              debugLog("incoming peer dropped (limit)");
              closeQuietly(peer);
              continue;
            }
            peer.setSoTimeout(PEER_READ_TIMEOUT_MS);
            debugLog("incoming peer " + peer.getRemoteSocketAddress());
            incomingPeerHandlers.incrementAndGet();
            new Thread(new Runnable() {
              @Override
              public void run() {
                try {
                  handleIncomingPeer(peer);
                } finally {
                  incomingPeerHandlers.decrementAndGet();
                }
              }
            }, "SoulseekPeerIn").start();
          } catch (IOException ignored) {}
        }
      }
    }, "SoulseekListen").start();
  }

  private void sendSearchLocked(int token, String query) throws IOException {
    activeSearchQuery = SoulseekSearchQuery.sanitize(query);
    byte[] body = concat(SoulseekWire.packUInt32(token), SoulseekWire.packString(activeSearchQuery));
    sendServerLocked(SoulseekWire.MSG_FILE_SEARCH, body);
    debugLog("search token=" + token + " q=" + activeSearchQuery);
    phaseLog("dl1", true, "FileSearch token=" + token);
    notifyStatus("Searching: " + activeSearchQuery);
    // #region agent log
    try {
      agentLog("SoulseekClient.sendSearchLocked", "search sent", "H3",
          new JSONObject().put("token", token).put("q", activeSearchQuery)
              .put("loggedIn", loggedIn).put("hasParent", hasDistribParent));
    } catch (Exception ignored) {}
    // #endregion
  }

  private void failDownload(String reason) {
    synchronized (downloadLock) {
      downloadFailureReason = reason;
      downloadLock.notifyAll();
    }
    debugLog("download fail: " + reason);
  }

  void checkDownloadFailure() throws IOException {
    synchronized (downloadLock) {
      if (downloadFailureReason != null) {
        throw new IOException(downloadFailureReason);
      }
    }
  }

  private void queueDownload(String peerUser, String filename) throws Exception {
    debugLog("download start " + peerUser + " " + basenameForSave(filename));
    WifiManager.WifiLock wifiLock = null;
    PowerManager.WakeLock wakeLock = null;
    if (appContext != null) {
      try {
        WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
          wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SoulseekDL");
          wifiLock.acquire();
        }
      } catch (Exception e) {
        debugLog("wifi lock fail: " + e);
      }
      try {
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoulseekDL");
          wakeLock.acquire();
        }
      } catch (Exception e) {
        debugLog("wake lock fail: " + e);
      }
    }
    final int connectToken = (int) (System.currentTimeMillis() & 0x7fffffff);
    final String peerKey = peerUser.toLowerCase(Locale.US);
    closeIdlePeerSockets(peerKey);
    synchronized (downloadLock) {
      pendingDownload = new PendingDownload(peerUser, filename, 0, connectToken);
      downloadFinished = false;
      downloadCancelled = false;
      downloadFailureReason = null;
    }
    final LinkedBlockingQueue<Socket> pierceQueue = new LinkedBlockingQueue<Socket>(1);
    pierceWaiters.put(connectToken, pierceQueue);
    cantConnectTokens.remove(connectToken);
    outboundFTried = false;
    try {
      notifyDownloadPhase("Finding peer", peerUser);
      peerAddresses.remove(peerKey);
      sendServerLocked(SoulseekWire.MSG_CONNECT_TO_PEER,
          concat(SoulseekWire.packUInt32(connectToken), SoulseekWire.packString(peerUser),
              SoulseekWire.packString(CONN_PEER)));
      sendServerLocked(SoulseekWire.MSG_GET_PEER_ADDRESS, SoulseekWire.packString(peerUser));
      debugLog("sent peer lookup " + peerUser);

      PeerEndpoint ep = waitPeerAddress(peerKey, 15000);
      if (ep == null) {
        debugLog("peer address timeout " + peerUser);
        phaseLog("dl2", false, "GetPeerAddress timeout");
        throw new IOException("Peer not reachable");
      }
      phaseLog("dl2", true, ep.host + ":" + ep.port);
      synchronized (downloadLock) {
        if (pendingDownload != null) {
          pendingDownload.peerHost = ep.host;
          pendingDownload.peerPort = ep.port;
        }
      }
      peerAddresses.put(peerKey, ep);

      notifyDownloadPhase("Connecting", peerUser);
      Socket peer = peerPSockets.get(peerKey);
      boolean sharedReader = peer != null && !peer.isClosed();
      if (sharedReader) {
        debugLog("reuse peer P " + peerUser);
        phaseLog("dl3", true, "reused P");
      } else {
        peer = acquirePeerSocket(peerUser, ep, connectToken, pierceQueue);
        if (peer != null) {
          phaseLog("dl3", true, "P connected");
        }
      }
      if (downloadCancelled) throw new IOException("Download cancelled");
      if (peer == null) {
        phaseLog("dl3", false, "P connect failed");
        throw new IOException("Peer not reachable");
      }

      final boolean closePeer = !sharedReader;
      downloadPeerSocket = peer;
      try {
        if (!sharedReader) {
          peer.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_CONNECT,
              concat(SoulseekWire.packString(username), SoulseekWire.packString(CONN_PEER),
                  SoulseekWire.packUInt32(0))));
          peer.getOutputStream().flush();
        }
        peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_QUEUE_UPLOAD,
            SoulseekWire.packString(filename)));
        peer.getOutputStream().flush();
        debugLog("queued upload " + peerUser + " " + basenameForSave(filename));
        phaseLog("dl4", true, "QueueUpload");
        notifyDownloadPhase("Queued", basenameForSave(filename));

        waitForTransferRequest(peer, sharedReader);
        if (downloadCancelled) throw new IOException("Download cancelled");
        waitForDownloadComplete();
      } finally {
        downloadPeerSocket = null;
        if (closePeer) closeQuietly(peer);
      }
    } finally {
      pierceWaiters.remove(connectToken);
      if (wifiLock != null && wifiLock.isHeld()) {
        try { wifiLock.release(); } catch (Exception ignored) {}
      }
      if (wakeLock != null && wakeLock.isHeld()) {
        try { wakeLock.release(); } catch (Exception ignored) {}
      }
    }
  }

  private Socket acquirePeerSocket(final String peerUser, final PeerEndpoint ep,
                                   final int connectToken,
                                   final LinkedBlockingQueue<Socket> pierceQueue) throws Exception {
    final LinkedBlockingQueue<Socket> directWinner = new LinkedBlockingQueue<Socket>(1);
    Thread directThread = new Thread(new Runnable() {
      @Override
      public void run() {
        Socket direct = null;
        try {
          direct = new Socket();
          direct.connect(new InetSocketAddress(ep.host, ep.port), PEER_CONNECT_MS);
          direct.setSoTimeout(PEER_READ_TIMEOUT_MS);
          directWinner.offer(direct);
          debugLog("direct download ok " + peerUser + " " + ep.host + ":" + ep.port);
        } catch (Exception e) {
          closeQuietly(direct);
        }
      }
    }, "SoulseekDirect");
    directThread.setDaemon(true);
    directThread.start();

    long deadline = System.currentTimeMillis() + DOWNLOAD_PEER_WAIT_MS;
    while (System.currentTimeMillis() < deadline && !downloadCancelled) {
      if (cantConnectTokens.contains(connectToken)) {
        phaseLog("dl3", false, "CantConnectToPeer");
        throw new IOException("Peer cannot connect — check NAT / port forward");
      }
      Socket peer = directWinner.poll();
      if (peer != null) return peer;
      peer = pierceQueue.poll(150, TimeUnit.MILLISECONDS);
      if (peer != null) {
        peer.setSoTimeout(PEER_READ_TIMEOUT_MS);
        debugLog("pierce download ok " + peerUser);
        return peer;
      }
    }
    return directWinner.poll();
  }

  private void waitForTransferRequest(Socket peer, boolean sharedReader) throws Exception {
    long deadline = System.currentTimeMillis() + DOWNLOAD_PEER_WAIT_MS;
    peer.setSoTimeout(500);
    InputStream in = sharedReader ? null : peer.getInputStream();
    while (running.get() && !downloadCancelled && System.currentTimeMillis() < deadline) {
      checkDownloadFailure();
      if (isTransferAccepted()) return;
      if (!sharedReader) {
        try {
          SoulseekWire.PeerFrame frame = SoulseekWire.readPeerFrame(in);
          if (frame.code == SoulseekWire.PEER_TRANSFER_REQUEST) {
            acceptTransferRequest(peer, frame.body);
            return;
          }
          if (frame.code == SoulseekWire.PEER_UPLOAD_DENIED) {
            SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
            r.readString();
            String reason = r.hasRemaining() ? r.readString() : "denied";
            synchronized (downloadLock) {
              if (pendingDownload != null) markPeerDenied(pendingDownload.username, reason);
            }
            phaseLog("dl5", false, "UploadDenied");
            throw new IOException("Upload denied: " + reason);
          }
          if (frame.code == SoulseekWire.PEER_PLACE_IN_QUEUE) {
            debugLog("peer queue position");
          }
        } catch (java.net.SocketTimeoutException ignored) {
        }
      } else {
        synchronized (downloadLock) {
          if (!isTransferAccepted()) {
            downloadLock.wait(200);
          }
        }
      }
    }
    if (isTransferAccepted()) return;
    phaseLog("dl5", false, "TransferRequest timeout");
    throw new IOException("Transfer request timed out");
  }

  private boolean isTransferAccepted() {
    synchronized (downloadLock) {
      return pendingDownload != null && pendingDownload.transferAccepted;
    }
  }

  private void acceptTransferRequest(Socket peer, byte[] body) throws Exception {
    SoulseekWire.Reader r = new SoulseekWire.Reader(body);
    int direction = r.readUInt32();
    int token = r.readUInt32();
    r.readString();
    long size = direction == SoulseekWire.TRANSFER_UPLOAD ? r.readUInt64() : 0;

    String progressName;
    synchronized (downloadLock) {
      if (pendingDownload == null) throw new IOException("No pending download");
      pendingDownload.transferToken = token;
      pendingDownload.transferAccepted = true;
      pendingDownload.transferAcceptedAt = System.currentTimeMillis();
      if (size > 0) pendingDownload.size = size;
      progressName = basenameForSave(pendingDownload.filename);
      downloadLock.notifyAll();
    }

    byte[] resp = concat(SoulseekWire.packUInt32(token), SoulseekWire.packBool(true));
    if (peer != null && !peer.isClosed()) {
      peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_TRANSFER_RESPONSE, resp));
      peer.getOutputStream().flush();
    }
    debugLog("transfer accepted token=" + token + " size=" + size);
    phaseLog("dl5", true, "TransferRequest token=" + token);
    phaseLog("dl6", true, "TransferResponse sent");
    notifyDownloadPhase("Receiving", formatSize(size));
    if (listener != null && size > 0) {
      listener.onDownloadProgress(progressName, 0, size);
    }
  }

  private void waitForDownloadComplete() throws Exception {
    synchronized (downloadLock) {
      long deadline = System.currentTimeMillis() + DOWNLOAD_TRANSFER_TIMEOUT_MS;
      while (!downloadFinished && pendingDownload != null && !downloadCancelled
          && System.currentTimeMillis() < deadline) {
        if (downloadFailureReason != null) {
          throw new IOException(downloadFailureReason);
        }
        if (pendingDownload.transferAccepted && pendingDownload.transferAcceptedAt > 0
            && !downloadFinished && !outboundFTried
            && System.currentTimeMillis() - pendingDownload.transferAcceptedAt > OUTBOUND_FILE_WAIT_MS) {
          outboundFTried = true;
          tryOutboundFileTransfer(pendingDownload);
        }
        if (pendingDownload.transferAccepted && pendingDownload.transferAcceptedAt > 0
            && System.currentTimeMillis() - pendingDownload.transferAcceptedAt > DOWNLOAD_FILE_CONNECT_MS
            && !downloadFinished && !xferInProgress) {
          pendingDownload = null;
          phaseLog("dl7", false, "inbound F timeout");
          throw new IOException("File transfer timed out — forward TCP port " + reportedListenPort);
        }
        downloadLock.wait(500);
      }
      if (downloadFailureReason != null) {
        throw new IOException(downloadFailureReason);
      }
      if (downloadCancelled) throw new IOException("Download cancelled");
      if (!downloadFinished) {
        pendingDownload = null;
        phaseLog("dl9", false, "transfer timeout");
        throw new IOException("Transfer timed out");
      }
    }
  }

  private void tryOutboundFileTransfer(final PendingDownload pd) {
    if (pd.peerHost == null || pd.peerPort <= 0 || pd.transferToken < 0) return;
    debugLog("outbound F attempt " + pd.username + " " + pd.peerHost + ":" + pd.peerPort);
    new Thread(new Runnable() {
      @Override
      public void run() {
        Socket f = null;
        try {
          f = new Socket();
          f.connect(new InetSocketAddress(pd.peerHost, pd.peerPort), 8000);
          f.setSoTimeout(0);
          f.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_CONNECT,
              concat(SoulseekWire.packString(username), SoulseekWire.packString(CONN_FILE),
                  SoulseekWire.packUInt32(pd.transferToken))));
          f.getOutputStream().flush();
          phaseLog("dl7", true, "outbound F connected");
          notifyDownloadPhase("Receiving", "direct link");
          handleIncomingFileTransfer(f, pd.username);
          f = null;
        } catch (Exception e) {
          debugLog("outbound F fail: " + e);
        } finally {
          closeQuietly(f);
        }
      }
    }, "SoulseekFOut").start();
  }

  private void markDownloadComplete() {
    synchronized (downloadLock) {
      downloadFinished = true;
      pendingDownload = null;
      downloadLock.notifyAll();
    }
  }

  private static final class PeerEndpoint {
    final String host;
    final int port;
    PeerEndpoint(String host, int port) { this.host = host; this.port = port; }
  }

  private PeerEndpoint waitPeerAddress(String peerKey, int ms) throws Exception {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) {
      PeerEndpoint ep = peerAddresses.remove(peerKey);
      if (ep != null) return ep;
      Thread.sleep(40);
    }
    return null;
  }

  private void stashPeerAddress(String peerUser, int ip, int port) {
    peerAddresses.put(peerUser.toLowerCase(Locale.US), new PeerEndpoint(SoulseekWire.ipToHost(ip), port));
  }

  private static String resultKey(String user, String filename) {
    return user.toLowerCase(Locale.US) + "\0" + filename;
  }

  private void addSearchResult(String resultUser, SoulseekWire.SearchFile f, boolean livePeer) {
    String user = SoulseekWire.sanitizeDisplay(resultUser);
    String file = SoulseekWire.sanitizeDisplay(f.filename);
    if (user.isEmpty() || file.isEmpty()) return;
    if (isPeerDenied(user)) return;
    if (pendingResults.size() >= MAX_SEARCH_RESULTS) return;
    String key = resultKey(user, file);
    if (!seenResultKeys.add(key)) return;
    if (broadFallbackGateActive
            && !SoulseekSearchRanking.passesBroadFallbackGate(originalSearchQuery, file)) {
      return;
    }
    Result res = new Result(user, file, f.size, f.bitrate, f.duration, livePeer,
            f.freeSlot, f.speed, f.queueLength);
    pendingResults.add(res);
    if (pendingResults.size() <= MAX_SEARCH_NOTIFY && listener != null) {
      listener.onSearchResult(res);
    }
  }

  private void handleServerFrameLocked(SoulseekWire.ServerFrame frame) throws IOException {
    if (frame.code == SoulseekWire.MSG_HAVE_NO_PARENT) {
      sendServerLocked(SoulseekWire.MSG_HAVE_NO_PARENT, SoulseekWire.packBool(!hasDistribParent));
    } else if (frame.code == SoulseekWire.MSG_POSSIBLE_PARENTS) {
      tryConnectDistribParentLocked(frame.body);
    } else if (frame.code == SoulseekWire.MSG_EMBEDDED_MESSAGE) {
      // ponytail: branch-root only; no children to forward to
      debugLog("embedded distrib msg len=" + frame.body.length);
    } else if (frame.code == SoulseekWire.MSG_CANT_CONNECT_TO_PEER) {
      SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
      int token = r.readUInt32();
      if (r.hasRemaining()) r.readString();
      cantConnectTokens.add(token);
      debugLog("cantConnect token=" + token);
    } else if (frame.code == SoulseekWire.MSG_GET_PEER_ADDRESS) {
      SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
      String user = r.readString();
      int ip = r.readUInt32();
      int port = r.readUInt32();
      stashPeerAddress(user, ip, port);
      debugLog("peer address " + user + " " + SoulseekWire.ipToHost(ip) + ":" + port);
    } else if (frame.code == SoulseekWire.MSG_CONNECT_TO_PEER) {
      SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
      String user = r.readString();
      String type = r.readString();
      int ip = r.readUInt32();
      int port = r.readUInt32();
      int token = r.readUInt32();
      if ("P".equals(type) || "F".equals(type)) {
        if (pierceInFlight.get() >= MAX_PIERCE) return;
        final String host = SoulseekWire.ipToHost(ip);
        debugLog("connectToPeer " + user + " " + type + " " + host + ":" + port + " tok=" + token);
        connectIndirectPeer(user, host, port, token, type);
      }
    } else if (frame.code == SoulseekWire.MSG_WATCH_USER) {
      handleWatchUserResponse(frame.body);
    } else if (frame.code == SoulseekWire.MSG_GET_USER_STATS) {
      handleGetUserStatsResponse(frame.body);
    } else if (frame.code == SoulseekWire.MSG_GET_USER_STATUS) {
      handleGetUserStatus(frame.body);
    } else if (frame.code == SoulseekWire.MSG_MESSAGE_USER) {
      handleIncomingPrivateMessage(frame.body);
    } else if (frame.code == SoulseekWire.MSG_ROOM_LIST) {
      handleRoomList(frame.body);
    } else if (frame.code == SoulseekWire.MSG_SAY_CHATROOM) {
      handleSayChatroom(frame.body);
    } else if (frame.code == SoulseekWire.MSG_JOIN_ROOM) {
      handleJoinRoomResponse(frame.body);
    } else if (frame.code == SoulseekWire.MSG_LEAVE_ROOM) {
      handleLeaveRoomResponse(frame.body);
    } else if (frame.code == SoulseekWire.MSG_USER_JOINED_ROOM) {
      handleUserJoinedRoom(frame.body);
    } else if (frame.code == SoulseekWire.MSG_USER_LEFT_ROOM) {
      handleUserLeftRoom(frame.body);
    } else if (frame.code == SoulseekWire.MSG_ROOM_TICKERS) {
      handleRoomTickers(frame.body);
    } else if (frame.code == SoulseekWire.MSG_ROOM_TICKER_ADDED) {
      handleRoomTickerAdded(frame.body);
    } else if (frame.code == SoulseekWire.MSG_ROOM_TICKER_REMOVED) {
      handleRoomTickerRemoved(frame.body);
    } else if (frame.code == SoulseekWire.MSG_USER_INTERESTS) {
      handleUserInterestsResponse(frame.body);
    } else if (frame.code == SoulseekWire.MSG_ITEM_SIMILAR_USERS) {
      handleItemSimilarUsersResponse(frame.body);
    }
  }

  private void handleWatchUserResponse(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String user = r.readString();
      boolean exists = r.readBool();
      if (!exists) {
        deliverUserWatch(new SoulseekUserDirectory.UserInfo(user, false, 0, 0, 0, 0, 0, "", false));
        return;
      }
      int status = r.readUInt32();
      int avgspeed = r.readUInt32();
      int uploadnum = r.readUInt32();
      r.readUInt32();
      int files = r.readUInt32();
      int dirs = r.readUInt32();
      String country = "";
      if (r.hasRemaining()) country = r.readString();
      deliverUserWatch(new SoulseekUserDirectory.UserInfo(user, true, status, avgspeed, uploadnum,
              files, dirs, country, false));
    } catch (Exception e) {
      deliverUserWatchError(e.getMessage() != null ? e.getMessage() : "Watch failed");
    }
  }

  private void handleGetUserStatsResponse(byte[] body) {
    try {
      SoulseekWire.GetUserStatsResponse stats = SoulseekWire.parseGetUserStats(body);
      if (stats == null || stats.username == null || stats.username.isEmpty()) return;
      if (socialListener != null) {
        socialListener.onUserStatsUpdate(stats.username, stats.avgSpeed, stats.files, stats.dirs);
      }
    } catch (Exception ignored) {}
  }

  private void handleGetUserStatus(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String user = r.readString();
      int status = r.readUInt32();
      boolean priv = r.readBool();
      String key = user != null ? user.toLowerCase(Locale.US) : "";
      if (!key.isEmpty() && watchedUsers.contains(key)) {
        Integer prev = peerLastStatus.get(key);
        // nicotine users._user_status: server won't resend stats after reconnect — request them.
        if (status != SoulseekWire.STATUS_OFFLINE && prev != null
                && prev == SoulseekWire.STATUS_OFFLINE) {
          requestUserStatsAsync(user);
        }
        peerLastStatus.put(key, status);
      }
      if (socialListener != null) {
        socialListener.onUserStatusUpdate(user, status, priv);
      }
    } catch (Exception ignored) {}
  }

  private void requestUserStatsAsync(final String username) {
    if (username == null || username.trim().isEmpty()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            requestUserStatsLocked(username.trim());
          }
        } catch (Exception e) {
          debugLog("requestUserStats fail: " + e.getMessage());
        }
      }
    }, "SoulseekUserStats").start();
  }

  private void requestUserStatsLocked(String username) throws IOException {
    sendServerLocked(SoulseekWire.MSG_GET_USER_STATS,
        SoulseekWire.packGetUserStatsRequest(username));
  }

  private void handleRoomList(byte[] body) {
    final List<SoulseekWire.RoomEntry> rooms = SoulseekWire.parseRoomList(body);
    if (socialListener != null) {
      socialListener.onRoomList(rooms);
    }
  }

  private void handleSayChatroom(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String room = r.readString();
      String sender = r.readString();
      String text = r.readString();
      int ts = (int) (System.currentTimeMillis() / 1000L);
      if (socialListener != null) {
        socialListener.onRoomMessage(room, sender, text, ts);
      }
    } catch (Exception ignored) {}
  }

  private void handleJoinRoomResponse(byte[] body) {
    try {
      String room = pendingJoinRoom;
      if (room == null || room.isEmpty()) {
        SoulseekWire.Reader r = new SoulseekWire.Reader(body);
        if (r.hasRemaining()) {
          int n = r.readUInt32();
          if (n > 0) return;
        }
        return;
      }
      joinedRooms.add(room);
      pendingJoinRoom = null;
      if (socialListener != null) socialListener.onRoomJoined(room);
    } catch (Exception ignored) {
      pendingJoinRoom = null;
    }
  }

  private void handleLeaveRoomResponse(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String room = r.readString();
      joinedRooms.remove(room);
      if (socialListener != null) socialListener.onRoomLeft(room);
    } catch (Exception ignored) {}
  }

  private void handleUserJoinedRoom(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String room = r.readString();
      String user = r.readString();
      debugLog("room join " + room + " " + user);
      if (socialListener != null) socialListener.onRoomUserJoined(room, user);
    } catch (Exception ignored) {}
  }

  private void handleUserLeftRoom(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      String room = r.readString();
      String user = r.readString();
      debugLog("room left " + room + " " + user);
      if (socialListener != null) socialListener.onRoomUserLeft(room, user);
    } catch (Exception ignored) {}
  }

  private void handleRoomTickers(byte[] body) {
    SoulseekWire.RoomTickersMessage msg = SoulseekWire.parseRoomTickers(body);
    if (msg == null || socialListener == null) return;
    socialListener.onRoomTickers(msg.room, msg.tickers);
  }

  private void handleRoomTickerAdded(byte[] body) {
    String room = SoulseekWire.parseRoomTickerAddedRoom(body);
    SoulseekWire.RoomTickerEntry entry = SoulseekWire.parseRoomTickerAdded(body);
    if (entry == null || socialListener == null) return;
    socialListener.onRoomTickerAdded(room, entry.username, entry.text);
  }

  private void handleRoomTickerRemoved(byte[] body) {
    String room = SoulseekWire.parseRoomTickerRemovedRoom(body);
    String username = SoulseekWire.parseRoomTickerRemovedUser(body);
    if (username == null || username.isEmpty() || socialListener == null) return;
    socialListener.onRoomTickerRemoved(room, username);
  }

  private static String parseVersionDescription(String text) {
    if (text == null || !text.startsWith("\u0001VERSION ")) return null;
    int end = text.lastIndexOf('\u0001');
    if (end > 10) return text.substring(10, end).trim();
    return text.substring(10).trim();
  }

  private void handleIncomingPrivateMessage(byte[] body) {
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      int msgId = r.readUInt32();
      int timestamp = r.readUInt32();
      String from = r.readString();
      String text = r.readString();
      r.readBool();
      try {
        sendServerLocked(SoulseekWire.MSG_MESSAGE_ACKED, SoulseekWire.packUInt32(msgId));
      } catch (Exception ignored) {}
      if (text != null && text.startsWith("\u0001VERSION ")) {
        try {
          String peerDesc = parseVersionDescription(text);
          if (appContext != null && peerDesc != null) {
            ReachPeerCapabilities.markFromVersion(
                    appContext.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE),
                    from, peerDesc);
          }
          sendPrivateMessageInternal(from, "\u0001VERSION " + clientDescription + "\u0001");
        } catch (Exception ignored) {}
        return;
      }
      if (socialListener != null) {
        // Developer support PMs when experiment is on — even if Reach messaging is off.
        SharedPreferences reachPrefs = appContext.getSharedPreferences(
                "SOLAR_SETTINGS", Context.MODE_PRIVATE);
        boolean devSupport = SolarDeveloperAccounts.isDeveloper(from)
                && SolarDeveloperAccounts.isExperimentEnabled(reachPrefs);
        boolean deliver = messagingEnabled || devSupport;
        if (deliver) {
          socialListener.onPrivateMessage(msgId, timestamp, from, text);
        }
      }
    } catch (Exception e) {
      debugLog("pm parse fail: " + e);
    }
  }

  private void deliverUserWatch(SoulseekUserDirectory.UserInfo info) {
    SoulseekUserDirectory.Callback cb = pendingUserWatch;
    pendingUserWatch = null;
    pendingWatchUser = null;
    if (info != null && info.exists && info.username != null) {
      String key = info.username.toLowerCase(Locale.US);
      watchedUsers.add(key);
      peerLastStatus.put(key, info.status);
    }
    if (cb != null) cb.onUserInfo(info);
  }

  private void deliverUserWatchError(String reason) {
    SoulseekUserDirectory.Callback cb = pendingUserWatch;
    pendingUserWatch = null;
    pendingWatchUser = null;
    if (cb != null) cb.onError(reason);
  }

  public void watchUser(final String peerUser, final SoulseekUserDirectory.Callback callback) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          pendingUserWatch = callback;
          pendingWatchUser = peerUser;
          sendServerLocked(SoulseekWire.MSG_WATCH_USER, SoulseekWire.packString(peerUser));
          Thread.sleep(8000);
          if (pendingUserWatch != null) deliverUserWatchError("User lookup timed out");
        } catch (Exception e) {
          deliverUserWatchError(e.getMessage() != null ? e.getMessage() : "Watch failed");
        }
      }
    }, "SoulseekWatch").start();
  }

  public void requestRoomList() {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          sendServerLocked(SoulseekWire.MSG_ROOM_LIST, new byte[0]);
        } catch (Exception ignored) {}
      }
    }, "SoulseekRoomList").start();
  }

  public void joinRoom(final String room) {
    if (room == null || room.trim().isEmpty()) return;
    final String name = room.trim();
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          pendingJoinRoom = name;
          sendServerLocked(SoulseekWire.MSG_JOIN_ROOM, SoulseekWire.packJoinRoom(name));
        } catch (Exception e) {
          pendingJoinRoom = null;
        }
      }
    }, "SoulseekJoinRoom").start();
  }

  public void leaveRoom(final String room) {
    if (room == null || room.trim().isEmpty()) return;
    final String name = room.trim();
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          sendServerLocked(SoulseekWire.MSG_LEAVE_ROOM, SoulseekWire.packLeaveRoom(name));
          joinedRooms.remove(name);
        } catch (Exception ignored) {}
      }
    }, "SoulseekLeaveRoom").start();
  }

  public void sayInRoom(final String room, final String text) {
    if (room == null || room.trim().isEmpty() || text == null || text.trim().isEmpty()) return;
    final String roomName = room.trim();
    final String body = text.trim();
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          sendServerLocked(SoulseekWire.MSG_SAY_CHATROOM,
                  SoulseekWire.packSayChatroom(roomName, body));
        } catch (Exception ignored) {}
      }
    }, "SoulseekSayRoom").start();
  }

  public void setRoomTicker(final String room, final String ticker) {
    if (room == null || room.trim().isEmpty()) return;
    final String roomName = room.trim();
    final String text = ticker != null ? ticker.trim() : "";
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          ensureConnected();
          sendServerLocked(SoulseekWire.MSG_SET_ROOM_TICKER,
                  SoulseekWire.packSetRoomTicker(roomName, text));
        } catch (Exception ignored) {}
      }
    }, "SoulseekSetRoomTicker").start();
  }

  public interface MessageSendCallback {
    void onSent();
    void onError(String reason);
  }

  public void sendPrivateMessage(final String peerUser, final String text,
                                 final MessageSendCallback callback) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          sendPrivateMessageInternal(peerUser, text);
          if (callback != null) callback.onSent();
        } catch (Exception e) {
          if (callback != null) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "Send failed");
          }
        }
      }
    }, "SoulseekPM").start();
  }

  /** Blocking PM send for multi-recipient batches — call from a worker thread only. */
  void sendPrivateMessageSync(String peerUser, String text) throws Exception {
    // #region agent log
    try {
      org.json.JSONObject d = new org.json.JSONObject();
      d.put("peer", peerUser);
      d.put("textLen", text != null ? text.length() : 0);
      d.put("loggedIn", loggedIn);
      d.put("running", running.get());
      d.put("sender", username);
      agentLog("SoulseekClient.sendPrivateMessageSync", "enter", "C", d);
      Debug843b96Log.log(appContext, "SoulseekClient.sendPrivateMessageSync", "enter", "C-F", d);
    } catch (Exception ignored) {}
    // #endregion
    sendPrivateMessageInternal(peerUser, text);
  }

  private void sendPrivateMessageInternal(String peerUser, String text) throws Exception {
    ensureConnected();
    sendServerLocked(SoulseekWire.MSG_MESSAGE_USER,
            concat(SoulseekWire.packString(peerUser), SoulseekWire.packString(text)));
  }

  public void runPeerOperation(final String peerUser, final PeerOperation op) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        Socket peer = null;
        try {
          ensureConnected();
          peer = openOutboundPeerSocket(peerUser);
          if (peer == null) throw new IOException("Peer not reachable");
          op.run(peer);
        } catch (Exception e) {
          // #region agent log
          try {
            agentLog("SoulseekClient.runPeerOperation", "peer op failed", "H3",
                new JSONObject().put("peerUser", peerUser)
                    .put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
          } catch (Exception ignored) {}
          // #endregion
          if (op != null) op.onError(e.getMessage() != null ? e.getMessage() : "Peer failed");
        } finally {
          closeQuietly(peer);
        }
      }
    }, "SoulseekPeerOp").start();
  }

  private Socket openOutboundPeerSocket(String peerUser) throws Exception {
    final int connectToken = (int) (System.currentTimeMillis() & 0x7fffffff);
    final String peerKey = peerUser.toLowerCase(Locale.US);
    final LinkedBlockingQueue<Socket> pierceQueue = new LinkedBlockingQueue<Socket>(1);
    pierceWaiters.put(connectToken, pierceQueue);
    try {
      peerAddresses.remove(peerKey);
      sendServerLocked(SoulseekWire.MSG_CONNECT_TO_PEER,
              concat(SoulseekWire.packUInt32(connectToken), SoulseekWire.packString(peerUser),
                      SoulseekWire.packString(CONN_PEER)));
      sendServerLocked(SoulseekWire.MSG_GET_PEER_ADDRESS, SoulseekWire.packString(peerUser));
      PeerEndpoint ep = waitPeerAddress(peerKey, 15000);
      if (ep == null) return null;
      Socket peer = acquirePeerSocket(peerUser, ep, connectToken, pierceQueue);
      if (peer == null) return null;
      peer.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_CONNECT,
              concat(SoulseekWire.packString(username), SoulseekWire.packString(CONN_PEER),
                      SoulseekWire.packUInt32(0))));
      peer.getOutputStream().flush();
      return peer;
    } finally {
      pierceWaiters.remove(connectToken);
    }
  }

  void notifyBrowseResult(final SoulseekBrowseSession.Callback callback,
                          final List<SoulseekWire.BrowseFolder> folders) {
    if (callback == null) return;
    if (listener != null) {
      // no-op; callback on worker thread is fine for UI layer to post
    }
    callback.onFolders(folders);
  }

  void notifyBrowseError(final SoulseekBrowseSession.Callback callback, final String reason) {
    if (callback != null) callback.onError(reason);
  }

  void notifyProfile(final SoulseekProfileSession.ProfileCallback callback,
          final SoulseekWire.UserInfoResponse info) {
    if (callback != null) callback.onProfile(info);
  }

  void notifyProfileError(final SoulseekProfileSession.ProfileCallback callback,
          final String reason) {
    if (callback != null) callback.onError(reason);
  }

  /** Outbound peer user-info description — follows Sharing / Messaging toggles when live. */
  private String buildOutboundUserBio() {
    if (appContext != null) {
      return DeviceFeatures.reachUserBio(appContext, profileSharingEnabled, profileMessagingEnabled);
    }
    return userBio != null ? userBio : clientDescription;
  }

  /** Keep Soulseek profile text aligned with Reach settings toggles. */
  public void setProfileToggles(boolean sharingEnabled, boolean messagingEnabled) {
    profileSharingEnabled = sharingEnabled;
    profileMessagingEnabled = messagingEnabled;
  }

  public void addLike(final String item) {
    final String norm = SoulseekInterests.normalizeItem(item);
    if (norm.isEmpty()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            sendServerLocked(SoulseekWire.MSG_ADD_THING_I_LIKE,
                    SoulseekWire.packInterestString(norm));
          }
        } catch (Exception e) {
          debugLog("addLike failed: " + e.getMessage());
        }
      }
    }, "SoulseekLike").start();
  }

  public void removeLike(final String item) {
    final String norm = SoulseekInterests.normalizeItem(item);
    if (norm.isEmpty()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            sendServerLocked(SoulseekWire.MSG_REMOVE_THING_I_LIKE,
                    SoulseekWire.packInterestString(norm));
          }
        } catch (Exception e) {
          debugLog("removeLike failed: " + e.getMessage());
        }
      }
    }, "SoulseekLike").start();
  }

  public void addHate(final String item) {
    final String norm = SoulseekInterests.normalizeItem(item);
    if (norm.isEmpty()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            sendServerLocked(SoulseekWire.MSG_ADD_THING_I_HATE,
                    SoulseekWire.packInterestString(norm));
          }
        } catch (Exception e) {
          debugLog("addHate failed: " + e.getMessage());
        }
      }
    }, "SoulseekHate").start();
  }

  public void removeHate(final String item) {
    final String norm = SoulseekInterests.normalizeItem(item);
    if (norm.isEmpty()) return;
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            sendServerLocked(SoulseekWire.MSG_REMOVE_THING_I_HATE,
                    SoulseekWire.packInterestString(norm));
          }
        } catch (Exception e) {
          debugLog("removeHate failed: " + e.getMessage());
        }
      }
    }, "SoulseekHate").start();
  }

  public void requestUserInterests(final String username, final UserInterestsCallback callback) {
    if (username == null || username.trim().isEmpty()) {
      if (callback != null) callback.onError("No user");
      return;
    }
    final String key = username.trim().toLowerCase(Locale.US);
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            ensureConnected();
            if (callback != null) pendingUserInterests.put(key, callback);
            sendServerLocked(SoulseekWire.MSG_USER_INTERESTS,
                    SoulseekWire.packUserInterestsRequest(username.trim()));
          }
        } catch (Exception e) {
          pendingUserInterests.remove(key);
          if (callback != null) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "Interests failed");
          }
        }
      }
    }, "SoulseekInterests").start();
  }

  private void syncLocalInterestsLocked() {
    try {
      android.content.SharedPreferences prefs =
              appContext.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
      for (String like : SoulseekInterests.loadLikes(prefs)) {
        if (!like.isEmpty()) {
          sendServerLocked(SoulseekWire.MSG_ADD_THING_I_LIKE, SoulseekWire.packInterestString(like));
        }
      }
      for (String hate : SoulseekInterests.loadDislikes(prefs)) {
        if (!hate.isEmpty()) {
          sendServerLocked(SoulseekWire.MSG_ADD_THING_I_HATE, SoulseekWire.packInterestString(hate));
        }
      }
      for (String sys : SoulseekInterests.systemLikes(appContext)) {
        sendServerLocked(SoulseekWire.MSG_ADD_THING_I_LIKE, SoulseekWire.packInterestString(sys));
      }
    } catch (Exception e) {
      debugLog("interest sync failed: " + e.getMessage());
    }
  }

  private void handleUserInterestsResponse(byte[] body) {
    UserInterestsCallback cb = null;
    String userKey = "";
    try {
      SoulseekWire.UserInterestsResponse resp = SoulseekWire.parseUserInterestsResponse(body);
      userKey = resp.username != null ? resp.username.toLowerCase(Locale.US) : "";
      synchronized (serverLock) {
        cb = pendingUserInterests.remove(userKey);
      }
      if (cb != null) {
        cb.onInterests(resp.username, resp.likes, resp.dislikes);
      }
    } catch (Exception e) {
      synchronized (serverLock) {
        cb = pendingUserInterests.remove(userKey);
      }
      if (cb != null) {
        cb.onError(e.getMessage() != null ? e.getMessage() : "Parse failed");
      }
    }
  }

  private void handleItemSimilarUsersResponse(byte[] body) {
    InterestUsersCallback cb;
    synchronized (serverLock) {
      cb = pendingInterestUsersCallback;
      pendingInterestUsersCallback = null;
    }
    if (cb == null) return;
    try {
      SoulseekWire.ItemSimilarUsersResponse resp =
              SoulseekWire.parseItemSimilarUsersResponse(body);
      cb.onUsers(resp.users);
    } catch (Exception e) {
      cb.onError(e.getMessage() != null ? e.getMessage() : "Parse failed");
    }
  }

  public void searchUsersByInterest(final String interest, final InterestUsersCallback callback) {
    if (callback == null) return;
    final String item = SoulseekInterests.normalizeItem(interest);
    if (item.isEmpty()) {
      callback.onError("Empty interest");
      return;
    }
    runServerOp("InterestUsers", new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (serverLock) {
            pendingInterestUsersCallback = callback;
            sendServerLocked(SoulseekWire.MSG_ITEM_SIMILAR_USERS,
                    SoulseekWire.packItemSimilarUsersRequest(item));
          }
        } catch (Exception e) {
          synchronized (serverLock) {
            if (pendingInterestUsersCallback == callback) {
              pendingInterestUsersCallback = null;
            }
          }
          callback.onError(e.getMessage() != null ? e.getMessage() : "Request failed");
        }
      }
    });
  }

  private void tryConnectDistribParentLocked(byte[] body) {
    if (hasDistribParent || distribSocket != null) return;
    try {
      SoulseekWire.Reader r = new SoulseekWire.Reader(body);
      int count = r.readUInt32();
      if (count <= 0) return;
      final String parentUser = r.readString();
      final int ip = r.readUInt32();
      final int port = r.readUInt32();
      final String host = SoulseekWire.ipToHost(ip);
      debugLog("distrib parent " + parentUser + " " + host + ":" + port);
      new Thread(new Runnable() {
        @Override
        public void run() {
          connectDistribParentAsync(parentUser, host, port);
        }
      }, "SoulseekDistrib").start();
    } catch (Exception e) {
      debugLog("distrib parent fail: " + e);
    }
  }

  private void connectDistribParentAsync(String parentUser, String host, int port) {
    try {
      Socket parent = new Socket();
      parent.connect(new InetSocketAddress(host, port), 10000);
      parent.setSoTimeout(PEER_READ_TIMEOUT_MS);
      parent.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_CONNECT,
          concat(SoulseekWire.packString(username), SoulseekWire.packString("D"), SoulseekWire.packUInt32(0))));
      parent.getOutputStream().flush();
      synchronized (serverLock) {
        if (hasDistribParent || distribSocket != null) {
          closeQuietly(parent);
          return;
        }
        distribSocket = parent;
        hasDistribParent = true;
      }
      sendServerLocked(SoulseekWire.MSG_HAVE_NO_PARENT, SoulseekWire.packBool(false));
      new Thread(new Runnable() {
        @Override
        public void run() {
          readDistribParent(parent);
        }
      }, "SoulseekDistribRead").start();
    } catch (Exception e) {
      debugLog("distrib parent fail: " + e);
    }
  }

  private void readDistribParent(Socket parent) {
    try {
      InputStream in = parent.getInputStream();
      OutputStream out = parent.getOutputStream();
      while (running.get() && !parent.isClosed()) {
        SoulseekWire.DistribFrame frame = SoulseekWire.readDistribFrame(in);
        if (frame.code == 0) {
          out.write(SoulseekWire.distribMessage(0, new byte[0]));
          out.flush();
        } else if (frame.code == SoulseekWire.DISTRIB_SEARCH) {
          // ponytail: leaf node — do NOT re-send FileSearch on server (kills session; 7k+ forwards observed)
          try {
            SoulseekWire.Reader dr = new SoulseekWire.Reader(frame.body);
            dr.readUInt32();
            if (dr.hasRemaining()) dr.readString();
            distribSearchIgnored++;
            if (distribSearchIgnored % 500 == 1) {
              debugLog("distrib search ignored (leaf) count=" + distribSearchIgnored);
            }
          } catch (Exception ignored) {}
        } else {
          debugLog("distrib code=" + frame.code + " len=" + frame.body.length);
        }
      }
    } catch (Exception e) {
      debugLog("distrib closed: " + e);
    } finally {
      hasDistribParent = false;
      closeQuietly(parent);
      if (distribSocket == parent) distribSocket = null;
    }
  }

  private void connectIndirectPeer(final String user, final String host, final int port,
                                   final int token, final String type) {
    if (pierceInFlight.incrementAndGet() > MAX_PIERCE) {
      pierceInFlight.decrementAndGet();
      return;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        Socket peer = null;
        try {
          peer = new Socket();
          peer.connect(new InetSocketAddress(host, port), PEER_CONNECT_MS);
          peer.setSoTimeout(PEER_READ_TIMEOUT_MS);
          peer.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_PIERCE,
              SoulseekWire.packUInt32(token)));
          peer.getOutputStream().flush();
          debugLog("pierce ok " + user + " " + host + ":" + port + " type=" + type);
          if (CONN_FILE.equals(type) && pendingDownload != null) {
            peer.setSoTimeout(0);
            notifyDownloadPhase("Receiving", "relay link");
            handleIncomingFileTransfer(peer, user);
            peer = null;
          } else {
            handlePeerSocket(peer, user);
          }
        } catch (Exception e) {
          debugLog("pierce fail " + user + " " + host + ":" + port + " " + e);
        } finally {
          pierceInFlight.decrementAndGet();
          closeQuietly(peer);
        }
      }
    }, "SoulseekPierce").start();
  }

  private void closeIdlePeerSockets(String keepUserKey) {
    for (java.util.Map.Entry<String, Socket> e : peerPSockets.entrySet()) {
      if (keepUserKey != null && keepUserKey.equals(e.getKey())) continue;
      closeQuietly(e.getValue());
    }
    if (keepUserKey == null) {
      peerPSockets.clear();
    } else {
      for (String k : new ArrayList<String>(peerPSockets.keySet())) {
        if (!keepUserKey.equals(k)) peerPSockets.remove(k);
      }
    }
  }

  private void handleIncomingPeer(Socket peer) {
    // #region agent log
    try {
      agentLog("SoulseekClient.handleIncomingPeer", "inbound peer connect", "H1",
          new JSONObject().put("remote", String.valueOf(peer.getRemoteSocketAddress())));
    } catch (Exception ignored) {}
    // #endregion
    try {
      SoulseekWire.PeerInitFrame init = SoulseekWire.readPeerInitFrame(peer.getInputStream());
      if (init.code == SoulseekWire.PEER_INIT_CONNECT) {
        SoulseekWire.Reader r = new SoulseekWire.Reader(init.body);
        String peerUser = r.readString();
        String connType = r.readString();
        r.readUInt32();
        if (CONN_FILE.equals(connType)) {
          handleIncomingFileTransfer(peer, peerUser);
          return;
        }
        handlePeerSocket(peer, peerUser);
      } else if (init.code == SoulseekWire.PEER_INIT_PIERCE) {
        SoulseekWire.Reader r = new SoulseekWire.Reader(init.body);
        int token = r.readUInt32();
        LinkedBlockingQueue<Socket> waiter = pierceWaiters.get(token);
        if (waiter != null && waiter.offer(peer)) {
          debugLog("pierce waiter filled token=" + token);
          return;
        }
        closeQuietly(peer);
      }
    } catch (Throwable e) {
      debugLog("incoming peer fail: " + e);
      closeQuietly(peer);
    }
  }

  /** Whether inbound P socket stays open for async upload after queue accept. */
  enum UploadHandoffResult { DENIED, QUEUED, ACTIVE }

  static boolean shouldRetainPeerSocketAfterQueueUpload(UploadHandoffResult result) {
    return result == UploadHandoffResult.QUEUED || result == UploadHandoffResult.ACTIVE;
  }

  private void handlePeerSocket(Socket peer, String peerUser) {
    final String key = peerUser.toLowerCase(Locale.US);
    peerPSockets.put(key, peer);
    boolean handoffPeer = false;
    try {
      InputStream in = peer.getInputStream();
      while (running.get() && !peer.isClosed()) {
        SoulseekWire.PeerFrame frame = SoulseekWire.readPeerFrame(in);
        if (frame.code == SoulseekWire.PEER_SHARES_REQUEST) {
          // #region agent log
          try {
            agentLog("SoulseekClient.handlePeerSocket", "PEER_SHARES_REQUEST", "H1-H2",
                new JSONObject().put("peerUser", peerUser));
          } catch (Exception ignored) {}
          // #endregion
          replySharesList(peer);
        } else if (frame.code == SoulseekWire.PEER_FOLDER_CONTENTS_REQUEST) {
          replyFolderContents(peer, frame.body);
        } else if (frame.code == SoulseekWire.PEER_USER_INFO_REQUEST) {
          // #region agent log
          try {
            agentLog("SoulseekClient.handlePeerSocket", "PEER_USER_INFO_REQUEST", "H1-H4",
                new JSONObject().put("peerUser", peerUser));
          } catch (Exception ignored) {}
          // #endregion
          replyUserInfo(peer);
        } else if (frame.code == SoulseekWire.PEER_QUEUE_UPLOAD) {
          SoulseekWire.Reader rq = new SoulseekWire.Reader(frame.body);
          String filename = rq.readString();
          UploadHandoffResult handoff = handleQueueUpload(peer, peerUser, filename);
          handoffPeer = shouldRetainPeerSocketAfterQueueUpload(handoff);
          return;
        } else if (frame.code == SoulseekWire.PEER_TRANSFER_REQUEST) {
          SoulseekWire.Reader tr = new SoulseekWire.Reader(frame.body);
          int direction = tr.readUInt32();
          if (direction == SoulseekWire.TRANSFER_DOWNLOAD && tr.hasRemaining()) {
            tr.readUInt32();
            String filename = tr.readString();
            UploadHandoffResult handoff = handleQueueUpload(peer, peerUser, filename);
            handoffPeer = shouldRetainPeerSocketAfterQueueUpload(handoff);
            return;
          }
          if (direction == SoulseekWire.TRANSFER_UPLOAD && pendingDownload != null) {
            debugLog("transfer request on shared P " + peerUser);
            acceptTransferRequest(peer, frame.body);
          }
        } else if (frame.code == SoulseekWire.PEER_FILE_SEARCH_RESPONSE) {
          if (pendingDownload != null) {
            debugLog("ignore search response during download " + peerUser);
            continue;
          }
          SoulseekWire.SearchResponse parsed = SoulseekWire.parseSearchResponse(frame.body, activeSearchToken);
          String resultUser = parsed.username != null && parsed.username.length() > 0
              ? parsed.username : peerUser;
          for (SoulseekWire.SearchFile f : parsed.files) {
            if (pendingResults.size() >= MAX_SEARCH_RESULTS) break;
            addSearchResult(resultUser, f, true);
          }
        } else if (frame.code == SoulseekWire.PEER_PLACE_IN_QUEUE && pendingDownload != null) {
          debugLog("peer queue position " + peerUser);
        } else if (frame.code == SoulseekWire.PEER_UPLOAD_DENIED && pendingDownload != null) {
          SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
          r.readString();
          String reason = r.hasRemaining() ? r.readString() : "denied";
          markPeerDenied(peerUser, reason);
          synchronized (downloadLock) {
            if (pendingDownload != null && peerUser.equalsIgnoreCase(pendingDownload.username)) {
              failDownload("Upload denied: " + reason);
            }
          }
        } else if (frame.code == SoulseekWire.PEER_UPLOAD_FAILED && pendingDownload != null) {
          SoulseekWire.Reader r = new SoulseekWire.Reader(frame.body);
          r.readString();
          String reason = r.hasRemaining() ? r.readString() : "upload failed";
          synchronized (downloadLock) {
            if (pendingDownload != null && peerUser.equalsIgnoreCase(pendingDownload.username)) {
              failDownload("Upload failed: " + reason);
            }
          }
        }
      }
    } catch (Exception e) {
      debugLog("peer socket end " + peerUser + ": " + e);
    } finally {
      peerPSockets.remove(key);
      // runUpload owns P until transfer handshake completes; queued uploads keep socket too.
      if (!handoffPeer) {
        closeQuietly(peer);
      }
    }
  }

  private void replyUserInfo(Socket peer) throws Exception {
    int active;
    int queued;
    synchronized (uploadLock) {
      active = activeUploads;
      queued = uploadQueue.size();
    }
    boolean slots = sharePolicy.acceptNewUploads() && active < MAX_UPLOAD_SLOTS;
    int permitted = sharePolicy.acceptNewUploads()
            ? SoulseekWire.UPLOAD_PERM_EVERYONE : SoulseekWire.UPLOAD_PERM_NO_ONE;
    byte[] pic = ReachProfilePicture.load(appContext);
    byte[] body = SoulseekWire.packUserInfoResponse(buildOutboundUserBio(), pic,
            slots, queued, MAX_UPLOAD_SLOTS, permitted);
    // #region agent log
    try {
      agentLog("SoulseekClient.replyUserInfo", "user info reply", "H4",
          new JSONObject().put("bioLen", userBio != null ? userBio.length() : 0)
              .put("picLen", pic != null ? pic.length : 0)
              .put("bodyLen", body.length));
    } catch (Exception ignored) {}
    // #endregion
    peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_USER_INFO_RESPONSE, body));
    peer.getOutputStream().flush();
  }

  private void replySharesList(Socket peer) throws Exception {
    boolean serve = sharePolicy.serveSharesToPeer();
    boolean announce = sharePolicy.announceShares();
    int files = shareIndex.fileCount();
    int dirs = shareIndex.dirCount();
    // #region agent log
    try {
      agentLog("SoulseekClient.replySharesList", "shares request", "H4",
          new JSONObject().put("serve", serve).put("announce", announce)
              .put("files", files).put("dirs", dirs));
    } catch (Exception ignored) {}
    // #endregion
    byte[] raw = serve
        ? shareIndex.buildShareListUncompressed()
        : SoulseekShareIndex.empty().buildShareListUncompressed();
    byte[] body = SoulseekShareIndex.zlibCompress(raw);
    // #region agent log
    try {
      agentLog("SoulseekClient.replySharesList", "shares reply sent", "H2-H3",
          new JSONObject().put("announce", announce).put("files", files).put("dirs", dirs)
              .put("rawLen", raw.length).put("zlibLen", body.length));
    } catch (Exception ignored) {}
    // #endregion
    peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_SHARES_REPLY, body));
    peer.getOutputStream().flush();
  }

  private void replyFolderContents(Socket peer, byte[] body) throws Exception {
    SoulseekWire.Reader r = new SoulseekWire.Reader(body);
    int token = r.readUInt32();
    String folder = r.readString();
    byte[] raw = sharePolicy.serveSharesToPeer()
        ? shareIndex.buildFolderContentsUncompressed(token, folder)
        : shareIndex.buildFolderContentsUncompressed(token, "");
    byte[] compressed = SoulseekShareIndex.zlibCompress(raw);
    peer.getOutputStream().write(
        SoulseekWire.peerMessage(SoulseekWire.PEER_FOLDER_CONTENTS_RESPONSE, compressed));
    peer.getOutputStream().flush();
  }

  private UploadHandoffResult handleQueueUpload(Socket peer, String peerUser, String filename) {
    try {
      if (!sharePolicy.acceptNewUploads()) {
        sendUploadDenied(peer, filename, "Not sharing");
        closeQuietly(peer);
        return UploadHandoffResult.DENIED;
      }
      final File file = shareIndex.resolve(filename);
      if (file == null) {
        sendUploadDenied(peer, filename, "File not shared");
        closeQuietly(peer);
        return UploadHandoffResult.DENIED;
      }
      QueuedUpload deferred = null;
      synchronized (uploadLock) {
        if (activeUploads < MAX_UPLOAD_SLOTS) {
          activeUploads++;
        } else if (sharePolicy.processUploadQueue()) {
          deferred = new QueuedUpload(peer, peerUser, filename, file);
          uploadQueue.add(deferred);
        } else {
          sendUploadDenied(peer, filename, "Busy");
          closeQuietly(peer);
          return UploadHandoffResult.DENIED;
        }
      }
      int place;
      synchronized (uploadLock) {
        place = activeUploads + uploadQueue.size();
      }
      sendPlaceInQueue(peer, filename, place);
      if (deferred == null) {
        runUpload(peer, peerUser, filename, file);
        return UploadHandoffResult.ACTIVE;
      }
      return UploadHandoffResult.QUEUED;
    } catch (Exception e) {
      debugLog("queue upload fail: " + e);
      finishUploadSlot(null);
      closeQuietly(peer);
      return UploadHandoffResult.DENIED;
    }
  }

  private void sendUploadDenied(Socket peer, String filename, String reason) throws IOException {
    byte[] body = concat(SoulseekWire.packString(filename), SoulseekWire.packString(reason));
    peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_UPLOAD_DENIED, body));
    peer.getOutputStream().flush();
  }

  private void sendPlaceInQueue(Socket peer, String filename, int place) throws IOException {
    byte[] body = concat(SoulseekWire.packString(filename), SoulseekWire.packUInt32(place));
    peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_PLACE_IN_QUEUE, body));
    peer.getOutputStream().flush();
  }

  private void finishUploadSlot(QueuedUpload failedQueued) {
    QueuedUpload next = null;
    synchronized (uploadLock) {
      if (failedQueued != null) {
        uploadQueue.remove(failedQueued);
      } else if (activeUploads > 0) {
        activeUploads--;
      }
      if (activeUploads < MAX_UPLOAD_SLOTS && !uploadQueue.isEmpty()
              && sharePolicy.processUploadQueue()) {
        next = uploadQueue.pollFirst();
        if (next != null) {
          activeUploads++;
        }
      }
    }
    refreshShareAnnouncement();
    if (next != null) {
      try {
        sendPlaceInQueue(next.peer, next.filename, 1);
        runUpload(next.peer, next.peerUser, next.filename, next.file);
      } catch (Exception e) {
        debugLog("dequeued upload fail: " + e);
        closeQuietly(next.peer);
        finishUploadSlot(next);
      }
      return;
    }
    synchronized (uploadLock) {
      if (activeUploads == 0 && uploadQueue.isEmpty()) {
        sharePolicy.onUploadQueueEmpty();
      }
    }
  }

  private void finishUploadSlot() {
    finishUploadSlot(null);
  }

  private static final class QueuedUpload {
    final Socket peer;
    final String peerUser;
    final String filename;
    final File file;

    QueuedUpload(Socket peer, String peerUser, String filename, File file) {
      this.peer = peer;
      this.peerUser = peerUser;
      this.filename = filename;
      this.file = file;
    }
  }

  private void runUpload(final Socket peer, final String peerUser, final String filename, final File file) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        Socket f = null;
        PowerManager.WakeLock wakeLock = null;
        if (appContext != null) {
          try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
              wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoulseekUL");
              wakeLock.acquire();
            }
          } catch (Exception ignored) {}
        }
        try {
          int token = (int) (System.currentTimeMillis() & 0x7fffffff);
          long size = file.length();
          byte[] reqBody = concat(
              SoulseekWire.packUInt32(SoulseekWire.TRANSFER_UPLOAD),
              SoulseekWire.packUInt32(token),
              SoulseekWire.packString(filename),
              SoulseekWire.packUInt64(size));
          peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_TRANSFER_REQUEST, reqBody));
          peer.getOutputStream().flush();
          peer.setSoTimeout(PEER_READ_TIMEOUT_MS);
          SoulseekWire.PeerFrame resp = SoulseekWire.readPeerFrame(peer.getInputStream());
          if (resp.code != SoulseekWire.PEER_TRANSFER_RESPONSE) {
            throw new IOException("bad transfer response");
          }
          SoulseekWire.Reader rr = new SoulseekWire.Reader(resp.body);
          int respToken = rr.readUInt32();
          boolean allowed = rr.readBool();
          if (!allowed || respToken != token) {
            throw new IOException("upload rejected");
          }
          PeerEndpoint ep = peerAddresses.get(peerUser.toLowerCase(Locale.US));
          if (ep == null) {
            sendServerLocked(SoulseekWire.MSG_GET_PEER_ADDRESS, SoulseekWire.packString(peerUser));
            ep = waitPeerAddress(peerUser.toLowerCase(Locale.US), 8000);
          }
          if (ep == null) throw new IOException("no peer address");
          f = new Socket();
          f.connect(new InetSocketAddress(ep.host, ep.port), 8000);
          f.setSoTimeout(0);
          f.getOutputStream().write(SoulseekWire.peerInitMessage(SoulseekWire.PEER_INIT_CONNECT,
              concat(SoulseekWire.packString(username), SoulseekWire.packString(CONN_FILE),
                  SoulseekWire.packUInt32(token))));
          f.getOutputStream().flush();
          long offset = SoulseekWire.readUInt64(f.getInputStream());
          sendFileToPeer(f.getOutputStream(), file, offset, size);
          debugLog("upload complete " + basenameForSave(filename) + " -> " + peerUser);
          announceUploadSpeedAsync();
        } catch (Exception e) {
          debugLog("upload fail " + peerUser + ": " + e);
          try {
            peer.getOutputStream().write(SoulseekWire.peerMessage(SoulseekWire.PEER_UPLOAD_FAILED,
                SoulseekWire.packString(filename)));
            peer.getOutputStream().flush();
          } catch (Exception ignored) {}
        } finally {
          if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
          }
          closeQuietly(f);
          closeQuietly(peer);
          finishUploadSlot();
        }
      }
    }, "SoulseekUp").start();
  }

  private void sendFileToPeer(OutputStream out, File file, long offset, long size) throws IOException {
    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
    try {
      if (offset > 0) raf.seek(offset);
      byte[] buf = new byte[16384];
      long sent = offset;
      while (sent < size) {
        int want = (int) Math.min(buf.length, size - sent);
        int n = raf.read(buf, 0, want);
        if (n < 0) break;
        out.write(buf, 0, n);
        sent += n;
      }
      out.flush();
    } finally {
      raf.close();
    }
  }

  private void handleIncomingFileTransfer(Socket peer, String peerUser) {
    if (xferInProgress) {
      debugLog("file xfer busy, reject extra F from " + peerUser);
      closeQuietly(peer);
      return;
    }
    xferInProgress = true;
    downloadFileSocket = peer;
    PendingDownload pd;
    try {
      peer.setSoTimeout(0);
      InputStream in = peer.getInputStream();
      int token = SoulseekWire.readRawUInt32(in);
      synchronized (downloadLock) {
        pd = pendingDownload;
        if (pd == null || pd.transferToken != token) {
          debugLog("file xfer bad token " + token + " want="
              + (pd != null ? pd.transferToken : -1) + " from " + peerUser);
          failDownload("File transfer rejected (bad token)");
          return;
        }
      }
      peer.getOutputStream().write(SoulseekWire.packUInt64(0));
      peer.getOutputStream().flush();

      String base = basenameForSave(pd.filename);
      File dir = pendingDestDir != null ? pendingDestDir : downloadDir;
      File outFile = uniqueFile(dir, base);
      receiveRawFile(in, pd.size, outFile, base);
      debugLog("download complete " + outFile.getName());
      phaseLog("dl8", true, "FileOffset/receive");
      phaseLog("dl9", true, outFile.getName());
      markDownloadComplete();
      if (listener != null) listener.onDownloadComplete(outFile);
      notifyDownloadPhase("Complete", outFile.getName());
    } catch (Exception e) {
      debugLog("file xfer fail " + peerUser + ": " + e);
      synchronized (downloadLock) {
        pd = pendingDownload;
      }
      if (!downloadCancelled && pd != null && !outboundFTried) {
        outboundFTried = true;
        notifyDownloadPhase("Retrying", "direct file link");
        tryOutboundFileTransfer(pd);
        return;
      }
      String msg = e.getMessage() != null ? e.getMessage() : "Download failed";
      if (msg.contains("Incomplete")) {
        msg = "Transfer interrupted — forward TCP port " + reportedListenPort;
      }
      failDownload(msg);
      if (!downloadCancelled) notifyError(msg);
    } finally {
      xferInProgress = false;
      downloadFileSocket = null;
      closeQuietly(peer);
    }
  }

  public static boolean shouldFirePartialReady(long done, long total, boolean alreadyFired) {
    return !alreadyFired && total > 0 && done * 100 / total >= REACH_EARLY_PLAY_PERCENT;
  }

  private void receiveRawFile(InputStream in, long size, File outFile, String progressName) throws Exception {
    BufferedOutputStream out = null;
    try {
      out = new BufferedOutputStream(new FileOutputStream(outFile), 16384);
      byte[] buf = new byte[16384];
      long done = 0;
      long lastNotifyDone = 0;
      int lastNotifyPct = -1;
      boolean partialFired = false;
      while (!downloadCancelled && (size <= 0 || done < size)) {
        int want = size > 0 ? (int) Math.min(buf.length, size - done) : buf.length;
        int n = in.read(buf, 0, want);
        if (n < 0) break;
        out.write(buf, 0, n);
        done += n;
        if (listener != null) {
          final long total = size > 0 ? size : done;
          final int pct = size > 0 ? (int) (done * 100 / size) : -1;
          if (shouldFirePartialReady(done, total, partialFired)) {
            partialFired = true;
            out.flush();
            listener.onDownloadPartialReady(outFile, done, total);
          }
          if (done >= total || pct != lastNotifyPct && (pct % 5 == 0 || done - lastNotifyDone >= 65536)) {
            listener.onDownloadProgress(progressName, done, total);
            lastNotifyDone = done;
            lastNotifyPct = pct;
          }
        }
        if (size <= 0 && n == 0) break;
      }
      out.flush();
      if (downloadCancelled) throw new IOException("Download cancelled");
      if (size > 0 && done < size) throw new IOException("Incomplete download");
      if (listener != null) {
        listener.onDownloadProgress(progressName, done, size > 0 ? size : done);
      }
    } catch (Exception e) {
      if (outFile.exists() && !outFile.delete()) {
        debugLog("partial delete fail " + outFile.getName());
      }
      throw e;
    } finally {
      if (out != null) {
        try { out.close(); } catch (Exception ignored) {}
      }
    }
  }

  /** ponytail: same-package test hook */
  void armDownloadForTest(String peerUser) {
    synchronized (downloadLock) {
      pendingDownload = new PendingDownload(peerUser, "test.mp3", 0, 1);
      downloadFinished = false;
      downloadFailureReason = null;
    }
  }

  void signalUploadDeniedForTest(String peerUser, String reason) {
    markPeerDenied(peerUser, reason);
    synchronized (downloadLock) {
      if (pendingDownload != null && peerUser.equalsIgnoreCase(pendingDownload.username)) {
        failDownload("Upload denied: " + reason);
      }
    }
  }

  boolean hasDownloadFailureForTest() {
    synchronized (downloadLock) {
      return downloadFailureReason != null;
    }
  }

  static String basenameForSave(String filename) {
    if (filename == null || filename.isEmpty()) return "download.bin";
    int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    String base = slash >= 0 ? filename.substring(slash + 1) : filename;
    base = base.replace('/', '_').replace('\\', '_').replace(':', '_');
    if (base.trim().isEmpty()) return "download.bin";
    return base;
  }

  public static File uniqueFile(File dir, String name) {
    File f = new File(dir, name);
    if (!f.exists()) return f;
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name;
    String ext = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; i < 1000; i++) {
      f = new File(dir, stem + "_" + i + ext);
      if (!f.exists()) return f;
    }
    return new File(dir, stem + "_" + System.currentTimeMillis() + ext);
  }

  private static byte[] concat(byte[]... parts) throws IOException {
    int n = 0;
    for (byte[] p : parts) n += p.length;
    byte[] out = new byte[n];
    int off = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, off, p.length);
      off += p.length;
    }
    return out;
  }

  private void notifyStatus(String msg) {
    if (listener != null) listener.onStatus(msg);
  }

  private static String formatError(Throwable err) {
    if (err == null) return "Unknown error";
    String msg = err.getMessage();
    if (msg == null || msg.trim().isEmpty()) {
      return err.getClass().getSimpleName();
    }
    return msg;
  }

  private void agentLog(String location, String message, String hypothesisId, JSONObject data) {
    ReachDebugLog.log(appContext, location, message, hypothesisId, data);
  }

  private void notifyError(String msg) {
    if (msg != null && (msg.contains("EMFILE") || msg.contains("Too many open files"))) {
      msg = "Too many peer connections — try again in a moment";
    }
    debugLog("error: " + msg);
    if (listener != null) listener.onError(msg);
  }

  private static void phaseLog(String step, boolean ok, String detail) {
    debugLog("phase " + step + " " + (ok ? "OK" : "FAIL") + " " + detail);
  }

  private static void debugLog(String msg) {
    // ponytail: sync append per call; keep off hot paths (distrib/search frames)
    try {
      PrintWriter pw = new PrintWriter(new FileOutputStream(DEBUG_LOG, true));
      pw.println(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg);
      pw.close();
    } catch (Exception ignored) {}
  }

  private static String formatSize(long bytes) {
    if (bytes <= 0) return "";
    if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
    return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  private static void sleepQuiet(int ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
  }

  private static void closeQuietly(ServerSocket s) {
    if (s == null) return;
    try { s.close(); } catch (Exception ignored) {}
  }

  private static void closeQuietly(Socket s) {
    if (s == null) return;
    try { s.close(); } catch (Exception ignored) {}
  }

  public List<Result> getResultsSnapshot() {
    return Collections.unmodifiableList(new ArrayList<Result>(pendingResults));
  }
}
