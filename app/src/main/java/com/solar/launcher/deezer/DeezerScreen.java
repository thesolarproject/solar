package com.solar.launcher.deezer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.solar.launcher.ConnectivityHelper;
import com.solar.launcher.DebugAgentLog;
import com.solar.launcher.FocusScrollHelper;
import com.solar.launcher.GetMusicSearch;
import com.solar.launcher.MusicSearchEntry;
import com.solar.launcher.R;
import com.solar.launcher.net.TlsHelper;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.soulseek.SoulseekSearchSuggestions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deezer search / results / action / download UI — mirrors Reach flow.
 * MainActivity wires navigation, playback, and view chrome.
 */
public final class DeezerScreen {
    public static final int UI_SEARCH = 0;
    public static final int UI_RESULTS = 1;
    public static final int UI_ACTION = 2;
    public static final int UI_DOWNLOAD = 3;

    public static final int ACTION_PLAY = 1;
    public static final int ACTION_SAVE = 2;
    public static final int ACTION_QUEUE = 3;

    private static final int PAGE_SIZE = 25;
    private static final long RETRY_DELAY_MIN_MS = 3400;
    private static final long RETRY_DELAY_MAX_MS = 6200;

    public interface Host {
        Context context();
        SharedPreferences prefs();
        File rootFolder();
        File deezerCacheDir();
        LinearLayout containerBrowserItems();
        TextView tvBrowserPath();
        void clickFeedback();
        boolean requireInternet(int msgRes);
        Button createListButton(String label);
        void createBrowserSectionHeader(String title);
        void updateStatusBarTitle(String title);
        void prepareBrowserChrome();
        void applyReachBrowseLayoutMode();
        int y1RowHeightPx();
        int listRowWidthPx();
        int y1ActiveRowWidthPx();
        android.graphics.drawable.Drawable getY1RowBackground(boolean focused, int width, int rowKind);
        int y1RowTextColorNormal(int rowKind);
        int y1RowTextColorSelected(int rowKind);
        int y1RowKindForScreen();
        void showFastScrollLetter(String letter);
        void enableMarquee(TextView tv);
        void scanMediaLibraryAsync();
        void persistPlaybackQueue();
        void changeToPlayer();
        void playDeezerPartial(File f, String meta, long trackId);
        void queueDeezerPartial(File f, String meta, long trackId);
        void replaceDeezerInQueue(File oldF, File newF, String meta);
        void launchDeezerSearchFromSuggestion(String q, boolean openKeyboard);
        void launchReachSearchFromSuggestion(String q, boolean openKeyboard);
        void showSearchServicePicker(String q, boolean openKeyboard);
        void onDeezerBackFromSearch();
        void openDeezerSearchKeyboard();
        boolean soulseekReachAvailable();
        boolean deezerAvailable();
        String string(int res);
        String string(int res, Object arg);
        String string(int res, Object arg1, Object arg2);
        void runOnUi(Runnable r);
        void runOnBg(Runnable r);
        boolean getMusicEmbedded();
        void onGetMusicBackToResults();
        void onGetMusicBackToSearch();
        void onDeezerDownloadProgress(int percent, File growingFile, long doneBytes, long totalBytes);
        void onDeezerStreamDownloadComplete(File completeFile);
        void prefetchDeezerCover(File track);
        void onDeezerStreamDownloadFailed();
        void launchGetMusicSearchFromSuggestion(String q, boolean openKeyboard);
        /** True when Deezer search was opened from Settings (back label + restore). */
        boolean deezerOpenedFromSettings();
        /** Multi-track album save/queue chain; return true to stay on download screen. */
        boolean onDeezerBatchTransferStep(DeezerResult finished);
        /** Suppress per-track save/queue toasts while batching an album. */
        boolean isDeezerAlbumBatchTransfer();
    }

    private final Host host;
    private final DeezerClient client;
    private final DeezerSearch search;
    private DeezerDownloader downloader;

    private int uiMode = UI_SEARCH;
    private int returnScreen = -1;
    private String lastQuery = "";
    private boolean searchInProgress;
    private int searchGen;
    private final List<DeezerResult> results = new ArrayList<DeezerResult>();
    private final List<DeezerSearch.DeezerArtist> searchArtists = new ArrayList<DeezerSearch.DeezerArtist>();
    private final List<MusicSearchEntry> organizedResults = new ArrayList<MusicSearchEntry>();
    private MusicSearchEntry browseContainer = null;
    private int containerLoadGen;
    private int resultsVisible = PAGE_SIZE;
    private DeezerResult actionResult;
    private DeezerResult activeDownload;
    private int pendingAction;
    private boolean downloadFailed;
    private String downloadFailureReason = "";
    private int downloadPercent;
    private File growingFile;
    private boolean partialPlaybackStarted;
    private long transferStartMs;
    private boolean downloadAutoRetryUsed;
    private boolean downloadAutoRetryPending;
    private int lastFailedAction = ACTION_PLAY;
    private final Handler downloadRetryHandler = new Handler(Looper.getMainLooper());
    private Runnable downloadAutoRetryRunnable;
    private Button searchStatusRow;

    public DeezerScreen(Host host) {
        this.host = host;
        this.client = new DeezerClient(host.prefs());
        this.search = new DeezerSearch(client);
    }

    public int uiMode() { return uiMode; }
    public int returnScreen() { return returnScreen; }
    public void setReturnScreen(int s) { returnScreen = s; }
    public boolean searchInProgress() { return searchInProgress; }
    public boolean isDownloadActive() { return activeDownload != null; }

    /** Clears stale results/action state when opening Deezer from Settings. */
    public void resetToSearch() {
        cancelSearch();
        actionResult = null;
        activeDownload = null;
        downloadFailed = false;
        results.clear();
        searchArtists.clear();
        organizedResults.clear();
        browseContainer = null;
        lastQuery = "";
        uiMode = UI_SEARCH;
    }

    /** Rebuild the visible tier after rotation or screen restore. */
    public void rebuildVisibleUi() {
        host.applyReachBrowseLayoutMode();
        if (uiMode == UI_DOWNLOAD && activeDownload != null) {
            buildDownloadUi(activeDownload, pendingAction != 0 ? pendingAction : ACTION_PLAY);
        } else if (uiMode == UI_ACTION && actionResult != null) {
            buildActionUi(actionResult);
        } else if (searchInProgress || uiMode == UI_RESULTS) {
            buildResultsUi();
        } else {
            buildSearchUi();
        }
    }

    public void initSessionAsync() {
        host.runOnBg(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    if (DeezerAccount.hasArl(host.prefs())) {
                        ok = client.initSession();
                    }
                } catch (Exception ignored) {}
                final boolean fOk = ok;
                ConnectivityHelper.setDeezerLoginOk(fOk);
            }
        });
    }

    public void buildSearchUi() {
        uiMode = UI_SEARCH;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        host.updateStatusBarTitle(host.string(R.string.status_deezer));
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(host.string(R.string.deezer_hint_search));
        }
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();

        Button back = host.createListButton(host.string(
                host.deezerOpenedFromSettings() ? R.string.deezer_back_settings
                        : R.string.deezer_back_home));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                host.onDeezerBackFromSearch();
            }
        });
        container.addView(back);

        if (!ConnectivityHelper.isOnline(host.context())) {
            if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
            return;
        }

        String typeLabel = host.string(R.string.deezer_type_search);
        Button typeSearch = host.createListButton(typeLabel);
        typeSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                host.openDeezerSearchKeyboard();
            }
        });
        typeSearch.setTag("deezer_open_keyboard");
        container.addView(typeSearch);

        List<String> recent = DeezerSearchHistory.load(host.prefs());
        if (!recent.isEmpty()) {
            host.createBrowserSectionHeader(host.string(R.string.deezer_recent_searches));
            for (final String q : recent) {
                Button b = host.createListButton(q);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.clickFeedback();
                        if (host.requireInternet(R.string.deezer_wifi_required)) fetchResults(q);
                    }
                });
                container.addView(b);
            }
        }
        if (container.getChildCount() > 1) container.getChildAt(1).requestFocus();
        else if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
    }

    public void fetchResults(final String query) {
        if (query == null || query.trim().isEmpty()) return;
        if (!host.requireInternet(R.string.deezer_wifi_required)) return;
        if (!ConnectivityHelper.isDeezerLoginOk() && !DeezerAccount.hasArl(host.prefs())) {
            Toast.makeText(host.context(), host.string(R.string.deezer_not_configured), Toast.LENGTH_LONG).show();
            return;
        }
        lastQuery = query.trim();
        DeezerSearchHistory.remember(host.prefs(), lastQuery);
        searchInProgress = true;
        resultsVisible = PAGE_SIZE;
        results.clear();
        searchArtists.clear();
        organizedResults.clear();
        browseContainer = null;
        final int gen = ++searchGen;
        buildResultsShell();
        host.runOnBg(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!client.isSessionValid()) client.initSession();
                    final List<DeezerSearch.DeezerArtist> foundArtists = search.searchArtists(lastQuery);
                    final List<DeezerResult> found = search.searchTracks(lastQuery);
                    host.runOnUi(new Runnable() {
                        @Override public void run() {
                            if (gen != searchGen) return;
                            searchInProgress = false;
                            results.clear();
                            results.addAll(found);
                            searchArtists.clear();
                            searchArtists.addAll(foundArtists);
                            organizedResults.clear();
                            organizedResults.addAll(
                                    GetMusicSearch.organizeDeezerResults(searchArtists, results));
                            buildResultsUi();
                        }
                    });
                } catch (final Exception e) {
                    host.runOnUi(new Runnable() {
                        @Override public void run() {
                            if (gen != searchGen) return;
                            searchInProgress = false;
                            results.clear();
                            searchArtists.clear();
                            organizedResults.clear();
                            buildResultsUi();
                            if (searchStatusRow != null) {
                                searchStatusRow.setText(host.string(R.string.deezer_search_error,
                                        e.getMessage() != null ? e.getMessage() : "Error"));
                            }
                        }
                    });
                }
            }
        });
    }

    private void buildResultsShell() {
        uiMode = UI_RESULTS;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        host.updateStatusBarTitle(host.string(R.string.status_deezer_results));
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(host.string(R.string.path_deezer_searching, lastQuery));
        }
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();
        Button back = host.createListButton(host.string(R.string.deezer_back_search));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                buildSearchUi();
            }
        });
        container.addView(back);
        searchStatusRow = host.createListButton(host.string(R.string.deezer_searching));
        searchStatusRow.setEnabled(false);
        container.addView(searchStatusRow);
        if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
    }

    public void buildResultsUi() {
        uiMode = UI_RESULTS;
        LinearLayout container = host.containerBrowserItems();
        int headerRows = browseContainer != null ? 1 : 2;
        while (container.getChildCount() > headerRows) container.removeViewAt(headerRows);
        final List<MusicSearchEntry> display = resultsForDisplay();
        if (searchStatusRow != null) {
            if (searchInProgress) {
                searchStatusRow.setText(host.string(R.string.deezer_searching));
            } else if (display.isEmpty()) {
                searchStatusRow.setText(host.string(R.string.deezer_no_results));
            } else {
                container.removeView(searchStatusRow);
                searchStatusRow = null;
            }
        }
        int end = Math.min(resultsVisible, display.size());
        for (int i = 0; i < end; i++) {
            container.addView(makeEntryButton(display.get(i)));
        }
        if (display.size() > resultsVisible) {
            Button more = host.createListButton(host.string(R.string.deezer_show_more,
                    display.size() - resultsVisible));
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.clickFeedback();
                    resultsVisible += PAGE_SIZE;
                    buildResultsUi();
                }
            });
            container.addView(more);
        }
    }

    private List<MusicSearchEntry> resultsForDisplay() {
        if (browseContainer != null) return browseContainer.children;
        if (!organizedResults.isEmpty()) return organizedResults;
        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        for (DeezerResult r : results) {
            if (r != null) flat.add(MusicSearchEntry.deezer(r));
        }
        return flat;
    }

    private Button makeEntryButton(final MusicSearchEntry e) {
        if (e.isContainer()) {
            return makeContainerButton(e);
        }
        if (e.deezer != null) {
            return makeResultButton(e.deezer);
        }
        return host.createListButton("");
    }

    private Button makeContainerButton(final MusicSearchEntry container) {
        String label;
        if (container.kind == MusicSearchEntry.RowKind.DEEZER_ARTIST) {
            label = host.string(R.string.get_music_artist_row, container.containerLabel);
        } else if (container.kind == MusicSearchEntry.RowKind.DEEZER_ALBUM) {
            label = GetMusicSearch.formatDeezerReleaseLabel(
                    container.deezerRecordType, container.containerLabel);
            if (!container.children.isEmpty()) {
                label += " · " + host.string(R.string.get_music_container_tracks,
                        container.children.size());
            }
        } else {
            label = host.string(R.string.get_music_folder_row, container.containerLabel);
        }
        final Button b = host.createListButton(label);
        b.setTag(container);
        styleResultButton(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                if (!host.requireInternet(R.string.deezer_wifi_required)) return;
                openContainer(container);
            }
        });
        return b;
    }

    private void openContainer(final MusicSearchEntry container) {
        if (container == null || !container.isContainer()) return;
        if (container.needsLazyLoad()) {
            beginLazyContainerLoad(container);
            return;
        }
        browseContainer = container;
        resultsVisible = PAGE_SIZE;
        LinearLayout list = host.containerBrowserItems();
        list.removeAllViews();
        Button back = host.createListButton(host.string(R.string.get_music_back_containers));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                browseContainer = null;
                buildResultsShell();
                if (host.tvBrowserPath() != null) {
                    host.tvBrowserPath().setText(host.string(R.string.path_deezer_results, lastQuery));
                }
                buildResultsUi();
            }
        });
        list.addView(back);
        buildResultsUi();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void beginLazyContainerLoad(final MusicSearchEntry container) {
        containerLoadGen++;
        final int gen = containerLoadGen;
        browseContainer = container;
        LinearLayout list = host.containerBrowserItems();
        list.removeAllViews();
        Button back = host.createListButton(host.string(R.string.get_music_back_containers));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                browseContainer = null;
                buildResultsUi();
            }
        });
        list.addView(back);
        Button loading = host.createListButton(host.string(R.string.get_music_loading_container));
        loading.setEnabled(false);
        list.addView(loading);
        host.runOnBg(new Runnable() {
            @Override public void run() {
                List<MusicSearchEntry> children = new ArrayList<MusicSearchEntry>();
                try {
                    if (container.kind == MusicSearchEntry.RowKind.DEEZER_ARTIST) {
                        List<DeezerSearch.DeezerAlbum> albums =
                                search.listArtistAlbums(container.deezerContainerId);
                        java.util.Collections.sort(albums, new java.util.Comparator<DeezerSearch.DeezerAlbum>() {
                            @Override
                            public int compare(DeezerSearch.DeezerAlbum a, DeezerSearch.DeezerAlbum b) {
                                int ta = recordTypeOrder(a.recordType);
                                int tb = recordTypeOrder(b.recordType);
                                if (ta != tb) return ta - tb;
                                return a.title.compareToIgnoreCase(b.title);
                            }
                        });
                        for (DeezerSearch.DeezerAlbum a : albums) {
                            if (a == null || a.id <= 0) continue;
                            children.add(MusicSearchEntry.deezerAlbumBrowse(
                                    a.id, a.title, a.recordType, null));
                        }
                    } else if (container.kind == MusicSearchEntry.RowKind.DEEZER_ALBUM) {
                        List<DeezerResult> tracks = search.listAlbumTracks(container.deezerContainerId);
                        for (DeezerResult t : tracks) {
                            if (t != null) children.add(MusicSearchEntry.deezer(t));
                        }
                    }
                } catch (Exception ignored) {}
                final List<MusicSearchEntry> loaded = children;
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (gen != containerLoadGen) return;
                        browseContainer = MusicSearchEntry.withChildren(container, loaded);
                        openContainer(browseContainer);
                    }
                });
            }
        });
    }

    private static int recordTypeOrder(String recordType) {
        if (recordType == null) return 0;
        String t = recordType.toLowerCase(Locale.US);
        if ("album".equals(t)) return 0;
        if ("ep".equals(t)) return 1;
        if ("single".equals(t)) return 2;
        return 3;
    }

    private Button makeResultButton(final DeezerResult r) {
        String dur = DeezerClient.formatDuration(r.durationSec);
        String qual = DeezerClient.formatQualityLabel(client.soundFormat());
        String label = r.displayTitle() + " · " + r.album
                + (dur.isEmpty() ? "" : " · " + dur) + " · " + qual;
        final Button b = host.createListButton(label);
        b.setTag(r);
        styleResultButton(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                if (!host.requireInternet(R.string.deezer_wifi_required)) return;
                buildActionUi(r);
            }
        });
        return b;
    }

    private void styleResultButton(final Button btn) {
        final int rowKind = 1;
        int rowW = host.listRowWidthPx() > 0 ? host.listRowWidthPx() : host.y1ActiveRowWidthPx();
        btn.setBackground(host.getY1RowBackground(false, rowW, rowKind));
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        btn.setSoundEffectsEnabled(false);
        btn.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        int hPad = (int) (10 * host.context().getResources().getDisplayMetrics().density);
        btn.setPadding(hPad, 0, hPad, 0);
        btn.setFocusable(true);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, host.y1RowHeightPx());
        lp.setMargins(0, 1, 0, 1);
        btn.setLayoutParams(lp);
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                int w = btn.getWidth() > 0 ? btn.getWidth()
                        : (host.listRowWidthPx() > 0 ? host.listRowWidthPx() : host.y1ActiveRowWidthPx());
                btn.setBackground(host.getY1RowBackground(hasFocus, w, rowKind));
                ThemeManager.applyThemedTextStyle(btn, hasFocus
                        ? host.y1RowTextColorSelected(rowKind) : host.y1RowTextColorNormal(rowKind));
                btn.setSelected(hasFocus);
                if (hasFocus) {
                    host.enableMarquee(btn);
                    host.showFastScrollLetter(btn.getText().toString());
                }
            }
        });
    }

    public void popToResults() {
        buildResultsShell();
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(host.string(R.string.path_deezer_results, lastQuery));
        }
        searchInProgress = false;
        buildResultsUi();
    }

    public void handleBackPress() {
        if (uiMode == UI_DOWNLOAD) {
            if (downloadFailed) {
                if (actionResult != null) popToResults();
                else if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
                else buildSearchUi();
            } else if (downloader != null) {
                downloader.cancel();
                activeDownload = null;
                if (actionResult != null) buildActionUi(actionResult);
                else if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
                else popToResults();
            }
        } else if (uiMode == UI_ACTION) {
            if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
            else popToResults();
        } else if (uiMode == UI_RESULTS) {
            if (browseContainer != null) {
                browseContainer = null;
                buildResultsShell();
                if (host.tvBrowserPath() != null) {
                    host.tvBrowserPath().setText(host.string(R.string.path_deezer_results, lastQuery));
                }
                buildResultsUi();
            } else if (host.getMusicEmbedded()) host.onGetMusicBackToSearch();
            else buildSearchUi();
        } else if (uiMode == UI_SEARCH) {
            if (host.getMusicEmbedded()) host.onGetMusicBackToSearch();
            else host.onDeezerBackFromSearch();
        }
    }

    public void buildActionUi(final DeezerResult r) {
        actionResult = r;
        uiMode = UI_ACTION;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        host.updateStatusBarTitle(r.displayTitle());
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(host.string(R.string.path_deezer, r.displayTitle()));
        }
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();

        Button back = host.createListButton(host.getMusicEmbedded()
                ? host.string(R.string.get_music_back_results)
                : host.string(R.string.deezer_back_results));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
                else popToResults();
            }
        });
        container.addView(back);

        String dur = DeezerClient.formatDuration(r.durationSec);
        String qual = DeezerClient.formatQualityLabel(client.soundFormat());
        Button info = host.createListButton(r.displayTitle() + " · " + r.album
                + (dur.isEmpty() ? "" : " · " + dur) + " · " + qual);
        info.setEnabled(false);
        container.addView(info);

        addAction(container, host.string(R.string.deezer_play), r, ACTION_PLAY);
        addAction(container, host.string(R.string.deezer_save), r, ACTION_SAVE);
        addAction(container, host.string(R.string.deezer_add_to_queue), r, ACTION_QUEUE);

        List<String> suggestions = SoulseekSearchSuggestions.reSearchQueries(r.displayTitle() + ".mp3");
        if (!suggestions.isEmpty()) {
            host.createBrowserSectionHeader(host.string(R.string.context_find_like_this));
            int shown = 0;
            for (final String q : suggestions) {
                if (shown >= 4) break;
                Button sb = host.createListButton(q);
                sb.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.clickFeedback();
                        launchSuggestion(q);
                    }
                });
                container.addView(sb);
                shown++;
            }
        }
        if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
    }

    private void launchSuggestion(String q) {
        host.launchGetMusicSearchFromSuggestion(q, true);
    }

    private void addAction(LinearLayout container, String label, final DeezerResult r, final int action) {
        Button b = host.createListButton(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                startTransfer(r, action);
            }
        });
        container.addView(b);
    }

    public void startTransfer(final DeezerResult r, final int action) {
        if (!host.requireInternet(R.string.deezer_wifi_required)) return;
        if (!DeezerAccount.hasArl(host.prefs())) {
            Toast.makeText(host.context(), host.string(R.string.deezer_not_configured), Toast.LENGTH_LONG).show();
            return;
        }
        final boolean autoRetry = downloadAutoRetryPending;
        downloadAutoRetryPending = false;
        cancelAutoRetrySchedule();
        if (!autoRetry) downloadAutoRetryUsed = false;
        partialPlaybackStarted = false;
        growingFile = null;
        downloadFailed = false;
        downloadFailureReason = "";
        pendingAction = action;
        activeDownload = r;
        downloadPercent = 0;
        transferStartMs = System.currentTimeMillis();
        buildDownloadUi(r, action);
        if (downloader != null) downloader.cancel();
        downloader = new DeezerDownloader(client);
        final File dest = action == ACTION_SAVE ? host.rootFolder() : host.deezerCacheDir();
        final String ext = client.fileExtension();
        downloader.download(r, dest, ext, new DeezerDownloader.Listener() {
            @Override public void onProgress(final long done, final long total) {
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (activeDownload == null || activeDownload.id != r.id) return;
                        if (total > 0) downloadPercent = (int) (done * 100 / total);
                        else if (done > 0) downloadPercent = Math.min(99, (int) (done / 50000));
                        refreshDownloadUi();
                        host.onDeezerDownloadProgress(downloadPercent, growingFile, done, total);
                    }
                });
            }

            @Override public void onPartialReady(final File destFile, long bytesRead) {
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (activeDownload == null || activeDownload.id != r.id) return;
                        if (partialPlaybackStarted) return;
                        partialPlaybackStarted = true;
                        growingFile = destFile;
                        DeezerMetadata.saveForResult(host.context(), destFile, r);
                        host.prefetchDeezerCover(destFile);
                        String meta = r.displayTitle();
                        // #region agent log
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("bytesRead", bytesRead);
                            d.put("msSinceTransfer", System.currentTimeMillis() - transferStartMs);
                            d.put("action", pendingAction);
                            DebugAgentLog.log(host.context(), "DeezerScreen.onPartialReady",
                                    "partial ready", "H-PARTIAL", d);
                        } catch (Exception ignored) {}
                        // #endregion
                        host.onDeezerDownloadProgress(downloadPercent, destFile, bytesRead, 0);
                        if (pendingAction == ACTION_PLAY) {
                            host.playDeezerPartial(destFile, meta, r.id);
                        } else if (pendingAction == ACTION_QUEUE) {
                            host.queueDeezerPartial(destFile, meta, r.id);
                            if (!host.isDeezerAlbumBatchTransfer()) {
                                Toast.makeText(host.context(), host.string(R.string.deezer_queued), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                });
            }

            @Override public void onComplete(final File destFile, final DeezerTrackData track) {
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (activeDownload == null || activeDownload.id != r.id) return;
                        cancelAutoRetrySchedule();
                        downloadAutoRetryUsed = false;
                        DeezerMetadata.saveForTrackComplete(host.context(), destFile, r, track);
                        host.prefetchDeezerCover(destFile);
                        downloadPercent = 100;
                        activeDownload = null;
                        if (pendingAction == ACTION_SAVE) {
                            host.scanMediaLibraryAsync();
                            if (!host.isDeezerAlbumBatchTransfer()) {
                                Toast.makeText(host.context(), host.string(R.string.deezer_saved), Toast.LENGTH_SHORT).show();
                            }
                            afterDownloadComplete(r);
                        } else if (pendingAction == ACTION_QUEUE && growingFile != null) {
                            host.replaceDeezerInQueue(growingFile, destFile, r.displayTitle());
                            host.persistPlaybackQueue();
                            if (!host.isDeezerAlbumBatchTransfer()) {
                                Toast.makeText(host.context(), host.string(R.string.deezer_queued), Toast.LENGTH_SHORT).show();
                            }
                            afterDownloadComplete(r);
                        } else if (pendingAction == ACTION_PLAY) {
                            if (growingFile != null && !growingFile.equals(destFile)) {
                                host.replaceDeezerInQueue(growingFile, destFile, r.displayTitle());
                            }
                            host.onDeezerStreamDownloadComplete(destFile);
                            host.persistPlaybackQueue();
                            afterDownloadComplete(r);
                        } else {
                            afterDownloadComplete(r);
                        }
                        pendingAction = 0;
                        growingFile = null;
                    }
                });
            }

            @Override public void onError(final String message) {
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (activeDownload == null || activeDownload.id != r.id) return;
                        if (downloader != null) downloader.cancel();
                        final int failedAction = pendingAction != 0 ? pendingAction : ACTION_PLAY;
                        downloadFailureReason = message != null ? message : host.string(R.string.deezer_error_unknown);
                        activeDownload = null;
                        pendingAction = 0;
                        host.onDeezerStreamDownloadFailed();
                        if (!downloadAutoRetryUsed) {
                            downloadAutoRetryUsed = true;
                            lastFailedAction = failedAction;
                            scheduleAutoRetry(r);
                        } else {
                            downloadFailed = true;
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("reason", downloadFailureReason);
                                d.put("trackId", r.id);
                                DebugAgentLog.log(host.context(), "DeezerScreen.onError",
                                        "auto retry failed", "H-RETRY", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            buildDownloadFailureUi(r, humanizeDownloadError(downloadFailureReason));
                        }
                    }
                });
            }
        });
    }

    private void afterDownloadComplete(DeezerResult r) {
        if (host.onDeezerBatchTransferStep(r)) return;
        if (host.getMusicEmbedded()) {
            host.onGetMusicBackToResults();
        } else {
            buildActionUi(r);
        }
    }

    private void buildDownloadUi(DeezerResult r, int action) {
        uiMode = UI_DOWNLOAD;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        host.updateStatusBarTitle(host.string(R.string.status_deezer_download));
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(r.displayTitle());
        }
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();
        Button title = host.createListButton(r.displayTitle());
        title.setEnabled(false);
        container.addView(title);
        searchStatusRow = host.createListButton(host.string(R.string.deezer_downloading, downloadPercent));
        searchStatusRow.setEnabled(false);
        container.addView(searchStatusRow);
        Button cancel = host.createListButton(host.string(R.string.deezer_cancel));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                cancelAutoRetrySchedule();
                downloadAutoRetryUsed = false;
                if (downloader != null) downloader.cancel();
                activeDownload = null;
                if (actionResult != null) buildActionUi(actionResult);
                else if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
                else popToResults();
            }
        });
        container.addView(cancel);
        if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
    }

    public int downloadPercent() { return downloadPercent; }
    public File growingFile() { return growingFile; }

    private void refreshDownloadUi() {
        if (searchStatusRow != null) {
            searchStatusRow.setText(host.string(R.string.deezer_downloading, downloadPercent));
        }
    }

    private void buildDownloadFailureUi(final DeezerResult r, String reason) {
        uiMode = UI_DOWNLOAD;
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();
        Button err = host.createListButton(host.string(R.string.deezer_download_failed, reason));
        err.setEnabled(false);
        container.addView(err);
        final int retryAction = lastFailedAction != 0 ? lastFailedAction : ACTION_PLAY;
        Button retry = host.createListButton(host.string(R.string.deezer_retry));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                downloadAutoRetryUsed = false;
                startTransfer(r, retryAction);
            }
        });
        container.addView(retry);
        Button back = host.createListButton(host.getMusicEmbedded()
                ? host.string(R.string.get_music_back_results)
                : host.string(R.string.deezer_back_results));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                if (host.getMusicEmbedded()) host.onGetMusicBackToResults();
                else popToResults();
            }
        });
        container.addView(back);
        if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
    }

    private void scheduleAutoRetry(final DeezerResult r) {
        showRetryingUi(r);
        long delayMs = RETRY_DELAY_MIN_MS
                + (long) (Math.random() * (RETRY_DELAY_MAX_MS - RETRY_DELAY_MIN_MS));
        downloadAutoRetryPending = true;
        downloadAutoRetryRunnable = new Runnable() {
            @Override public void run() {
                downloadAutoRetryRunnable = null;
                TlsHelper.init(host.context());
                TlsHelper.ensureSecurityProvider();
                startTransfer(r, lastFailedAction);
            }
        };
        downloadRetryHandler.postDelayed(downloadAutoRetryRunnable, delayMs);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("delayMs", delayMs);
            d.put("trackId", r.id);
            d.put("reason", downloadFailureReason);
            DebugAgentLog.log(host.context(), "DeezerScreen.scheduleAutoRetry",
                    "auto retry scheduled", "H-RETRY", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private void showRetryingUi(DeezerResult r) {
        uiMode = UI_DOWNLOAD;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        if (host.tvBrowserPath() != null) {
            host.tvBrowserPath().setText(r.displayTitle());
        }
        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();
        Button title = host.createListButton(r.displayTitle());
        title.setEnabled(false);
        container.addView(title);
        searchStatusRow = host.createListButton(host.string(R.string.deezer_download_retrying));
        searchStatusRow.setEnabled(false);
        container.addView(searchStatusRow);
    }

    private void cancelAutoRetrySchedule() {
        if (downloadAutoRetryRunnable != null) {
            downloadRetryHandler.removeCallbacks(downloadAutoRetryRunnable);
            downloadAutoRetryRunnable = null;
        }
        downloadAutoRetryPending = false;
    }

    private static String humanizeDownloadError(String reason) {
        if (reason == null || reason.isEmpty()) return reason != null ? reason : "";
        String lower = reason.toLowerCase(Locale.US);
        if (lower.contains("cert") || lower.contains("ssl") || lower.contains("handshake")) {
            return "Secure connection failed. Check Wi‑Fi and device date/time, then retry.";
        }
        if (reason.length() > 96) return reason.substring(0, 93) + "…";
        return reason;
    }

    public void cancelSearch() {
        searchGen++;
        searchInProgress = false;
    }

    /** Cancel downloads and clear UI state when leaving Deezer screen. */
    public void teardown() {
        cancelSearch();
        cancelAutoRetrySchedule();
        downloadAutoRetryUsed = false;
        if (downloader != null) {
            downloader.cancel();
            downloader = null;
        }
        activeDownload = null;
        pendingAction = 0;
        growingFile = null;
    }
}
