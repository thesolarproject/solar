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
import com.solar.launcher.LibraryBrowsePrefs;
import com.solar.launcher.R;
import com.solar.launcher.DebugSessionLog;
import com.solar.launcher.deezer.DeezerPlaylist;
import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.podcast.OpenRssClient;
import com.solar.launcher.theme.ThemeManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Flow screen coordinator: picker, Cover Flow carousel, in-place flip tracklist.
 */
public final class FlowScreenHost implements FlowView.Callback, FlowCoverResolver.Host {

    public interface Actions {
        Activity activity();
        SharedPreferences prefs();
        File musicRoot();
        File getCoversFolder();
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
        void playTracksWithHandoff(List<File> tracks, int startIndex, String label, Bitmap cover, RectF fromRect);
        void playPodcastWithHandoff(OpenRssClient.Podcast show, List<OpenRssClient.Episode> episodes,
                int index, Bitmap cover, RectF fromRect);
        void playDeezerTracks(List<DeezerResult> tracks, int startIndex);
        void fetchPodcastEpisodes(String feedUrl, EpisodeCallback callback);
        void fetchDeezerPlaylistTracks(long playlistId, PlaylistTracksCallback callback);
        void saveLastFlowIndex(FlowMode mode, int index);
        int loadLastFlowIndex(FlowMode mode);
        boolean isDebugFlowTheme();
        boolean isFlowOkOpensLibrary();
        void openLibraryBrowse(FlowItem item);
        boolean isNowPlaying3dAlbumArt();
    }

    public interface EpisodeCallback {
        void onEpisodes(List<OpenRssClient.Episode> episodes, String error);
    }

    public interface PlaylistTracksCallback {
        void onTracks(List<DeezerResult> tracks, String error);
    }

    private static final int UI_PICKER = 0;
    private static final int UI_CAROUSEL = 1;

    private final Actions actions;
    private final FlowCoverCache coverCache = new FlowCoverCache();
    private View root;
    private FlowView flowView;
    private ScrollView pickerScroll;
    private LinearLayout pickerContainer;

    private FlowLaunchRequest launchRequest;
    private FlowMode activeMode = FlowMode.UNSPECIFIED;
    private int uiMode = UI_PICKER;
    private int pickerIndex;
    private int coverGen;
    private List<FlowItem> catalog = new ArrayList<FlowItem>();
    private FlowItem focusedItem;

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
    }

    public void open(FlowLaunchRequest req) {
        launchRequest = req;
        coverGen++;
        flowView.resetFlip();
        if (req.skipPicker()) {
            showCarousel(req.mode, req.focusKey);
        } else {
            showPicker();
        }
    }

    public void teardown() {
        coverGen++;
        coverCache.clear();
        if (flowView != null) flowView.resetFlip();
    }

    public FlowItem getFocusedItem() {
        if (uiMode == UI_CAROUSEL && flowView != null) {
            return flowView.itemAt(flowView.engine().getFocusIndex());
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
            FlowItem item = flowView.itemAt(flowView.engine().getFocusIndex());
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
        if (uiMode == UI_CAROUSEL && flowView.isFlipped()) {
            if (flowView.flipController().popBackLevel()) {
                flowView.invalidate();
                return true;
            }
            if (flowView.flipToFront()) return true;
        }
        if (uiMode == UI_CAROUSEL && launchRequest != null && launchRequest.enteredFromSection) {
            return false;
        }
        if (uiMode == UI_CAROUSEL && launchRequest != null && !launchRequest.skipPicker()) {
            showPicker();
            return true;
        }
        return false;
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
        FlowMode mode = FlowMode.values()[pickerIndex + 1];
        showCarousel(mode, null);
    }

    private void showCarousel(FlowMode mode, String focusKey) {
        uiMode = UI_CAROUSEL;
        activeMode = mode;
        flowView.resetFlip();
        pickerScroll.setVisibility(View.GONE);
        flowView.setVisibility(View.VISIBLE);
        catalog = FlowCatalog.build(mode, actions.libraryRows(), actions.libraryBrowsePrefs(),
                actions.policyTracks(), actions.musicRoot(), actions.deezerPlaylists(),
                actions.podcastShows());
        flowView.setItems(catalog);
        int index = 0;
        if (focusKey != null && !focusKey.isEmpty()) {
            int found = flowView.engine().findIndexForKey(catalog, focusKey);
            if (found >= 0) index = found;
        } else {
            index = actions.loadLastFlowIndex(mode);
            if (index >= catalog.size()) index = 0;
        }
        flowView.engine().setFocusIndex(index);
        focusedItem = flowView.itemAt(index);
        flowView.prefetchAround(index);
        flowView.invalidate();
    }

    private void flipItem(FlowItem item) {
        focusedItem = item;
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
            addTrackRows(rows, tracks);
        } else if (item.kind == FlowItem.Kind.ARTIST) {
            if (ArtistBrowsePolicy.shouldSkipAlbumPicker(item.title, prefs, policy)) {
                addTrackRows(rows, FlowCatalog.tracksForArtist(item.title, lib, prefs, policy));
            } else {
                for (FlowItem album : FlowCatalog.albumsForArtist(item.title, lib, prefs, policy)) {
                    rows.add(new FlowBackRow(album.title, album.subtitle, null, null, album, null));
                }
            }
        } else if (item.kind == FlowItem.Kind.PLAYLIST) {
            addTrackRows(rows, item.tracks);
        }
        if (rows.isEmpty()) {
            rows.add(new FlowBackRow(
                    actions.activity().getString(R.string.flow_empty), "", null, null));
        }
        flowView.flipToBack(item.title, item.subtitle, rows);
    }

    private void flipDeezerPlaylist(final FlowItem item) {
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
        List<FlowBackRow> loading = new ArrayList<FlowBackRow>();
        loading.add(new FlowBackRow(
                actions.activity().getString(R.string.flow_loading_episodes), "", null, null));
        flowView.flipToBack(item.title, item.subtitle, loading);
        actions.fetchPodcastEpisodes(item.podcastFeedUrl, new EpisodeCallback() {
            @Override
            public void onEpisodes(List<OpenRssClient.Episode> episodes, String error) {
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

    private void addTrackRows(List<FlowBackRow> rows, List<File> tracks) {
        if (tracks == null) return;
        for (File f : tracks) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            rows.add(new FlowBackRow(name, "", f, null));
        }
    }

    private void drillToAlbumTracks(FlowItem album) {
        if (album == null) return;
        LibraryBrowsePrefs prefs = actions.libraryBrowsePrefs();
        List<File> tracks = album.tracks.isEmpty()
                ? FlowCatalog.tracksForAlbum(album, actions.libraryRows(), prefs) : album.tracks;
        List<FlowBackRow> rows = new ArrayList<FlowBackRow>();
        addTrackRows(rows, tracks);
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

        Bitmap cover = flowView.getCenterCoverBitmap();
        RectF fromRect = flowView.getCenterCoverScreenRect();
        boolean handoff = actions.isNowPlaying3dAlbumArt() && cover != null;

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
            Runnable play = new Runnable() {
                @Override
                public void run() {
                    actions.playTracks(playlist, startIdx, label);
                }
            };
            if (handoff) {
                actions.playTracksWithHandoff(playlist, startIdx, label, cover, fromRect);
            } else {
                if (flowView.isFlipped()) flowView.flipToFront();
                play.run();
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
                actions.playPodcastWithHandoff(show, eps, startFinal, cover, fromRect);
            } else {
                if (flowView.isFlipped()) flowView.flipToFront();
                actions.playPodcastShow(show, eps, startFinal);
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
    public void requestCover(int itemIndex, String coverKey, FlowItem item) {
        if (coverKey == null || coverCache.get(coverKey) != null) return;
        final int gen = coverGen;
        final int thumb = coverCache.thumbSizePx(480f, 360f);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = FlowCoverResolver.resolve(item, FlowScreenHost.this, thumb);
                if (bmp == null || gen != coverGen) return;
                coverCache.put(coverKey, bmp);
                actions.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (gen == coverGen) flowView.invalidate();
                    }
                });
            }
        }, "FlowCover").start();
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
    public SharedPreferences prefs() {
        return actions.prefs();
    }

    @Override
    public Typeface labelFont() {
        return ThemeManager.getCustomFont();
    }
}
