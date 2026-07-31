package com.solar.media;

import android.app.Activity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-30: Abstract base consolidating ~400 lines of shared browse-screen logic
 * from NavidromeScreenHost, JellyfinScreenHost, and PlexScreenHost.
 *
 * <p>Concrete subclasses only supply server-specific resource IDs, client calls,
 * and model-type accessors — roughly 30 one-liner abstract methods.
 *
 * 
 * @param <AL> Album type
 * @param <S>  Song type
 * @param <P>  Playlist type
 * @param <R>  BrowseRow type (implements {@link MediaBrowseRow})
 * @param <SR> SearchResult type
 */
public abstract class MediaScreenHost  <AL, S, P,
                                      R extends MediaBrowseRow, SR> {

    // ── Shared UI contract (18 non-typed Actions methods) ──────────

    public interface Ui {
        Activity activity();
        void clickFeedback();
        Button createListButton(String label);
        void configureListButton(Button btn);
        void showScrollBrowse();
        void showFastListBrowse();
        void addScrollRow(View row);
        void setFastListAdapter(BaseAdapter adapter);
        void focusBrowse();
        void setScrollIndexNames(List<String> names);
        void setStatusTitle(String title);
        void updateStatusBar();
        void setBreadcrumb(String path);
        boolean requireInternet(int messageRes);
        void openSearchKeyboard(String prefill);
        int getListSelectedPosition();
        void applyListRowParams(View row, int heightPx);
        int rowHeightPx();
    }

    // ── Generic async callback ────────────────────────────────────

    protected interface LoadCallback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    // ── UI mode constants ─────────────────────────────────────────

    protected static final int UI_ROOT     = 0;
    protected static final int UI_ARTISTS  = 1;
    protected static final int UI_ALBUMS   = 2;
    protected static final int UI_PLAYLISTS = 3;
    protected static final int UI_SONGS    = 4;
    protected static final int UI_SEARCH   = 5;
    protected static final int UI_TRACKS   = 6;

    // ── Shared state ──────────────────────────────────────────────

    protected final Ui ui;
    protected final MediaBrowseAdapter<R> adapter;
    protected int uiMode = UI_ROOT;
    protected List<MediaArtist>  artists   = new ArrayList<MediaArtist>();
    protected List<AL> albums    = new ArrayList<AL>();
    protected List<S>  songs     = new ArrayList<S>();
    protected List<P>  playlists = new ArrayList<P>();
    protected MediaArtist  selectedArtist;
    protected AL selectedAlbum;
    protected P  selectedPlaylist;
    protected String searchQuery = "";

    // ── Constructor ───────────────────────────────────────────────

    protected MediaScreenHost(Ui ui) {
        this.ui = ui;
        this.adapter = new MediaBrowseAdapter<R>(new MediaBrowseAdapter.RowUi<R>() {
            @Override public Button createListButton(String label) {
                return ui.createListButton(label);
            }
            @Override public void bindListButton(Button btn, boolean focused, String label) {
                ui.configureListButton(btn);
            }
            @Override public void applyListRowParams(View row, int heightPx) {
                ui.applyListRowParams(row, heightPx);
            }
            @Override public int rowHeightPx() { return ui.rowHeightPx(); }
            @Override public void onRowClick(R row) {
                MediaScreenHost.this.onRowClick(row);
            }
            @Override public void onRowFocused(R row, boolean hasFocus) {
                if (hasFocus) MediaScreenHost.this.onRowFocused(row);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  Abstract template methods — one-liners in concrete subclasses
    // ═══════════════════════════════════════════════════════════════

    // ── Resource IDs (server-specific R.string keys) ──────────────

    protected abstract int menuTitleRes();
    protected abstract int artistsTitleRes();
    protected abstract int pathRootRes();
    protected abstract int pathArtistsRes();
    protected abstract int pathAlbumsRes();
    protected abstract int pathAllAlbumsRes();
    protected abstract int pathPlaylistsRes();
    protected abstract int pathSongsRes();
    protected abstract int pathAllSongsRes();
    protected abstract int pathSearchRes();
    protected abstract int loadingRes();
    protected abstract int searchTitleRes();
    protected abstract int searchEmptyRes();
    protected abstract int wifiRequiredRes();

    // ── Client calls (delegated to server-specific singletons) ────

    protected abstract boolean isConfigured();
    protected abstract void loadArtistsAsync(LoadCallback<List<MediaArtist>> cb);
    protected abstract void loadAlbumsAsync(LoadCallback<List<AL>> cb);
    protected abstract void loadAllTracksAsync(LoadCallback<List<S>> cb);
    protected abstract void loadPlaylistsAsync(LoadCallback<List<P>> cb);
    protected abstract void openAlbumsAsync(MediaArtist artist, LoadCallback<List<AL>> cb);
    protected abstract void openSongsAsync(AL album, LoadCallback<List<S>> cb);
    protected abstract void openPlaylistSongsAsync(P playlist, LoadCallback<List<S>> cb);

    // ── Row construction & access ─────────────────────────────────

    /** Create a fresh empty row instance. */
    protected abstract R newRow();

    /** Populate a row from an artist model. */
    protected abstract void fillArtistRow(R row, MediaArtist artist);

    /** Populate a row from an album model. */
    protected abstract void fillAlbumRow(R row, AL album);

    /** Populate a row from a playlist model. */
    protected abstract void fillPlaylistRow(R row, P playlist);

    /** Populate a row from a song model (album may be null). */
    protected abstract void fillSongRow(R row, S song, AL album);

    /** Extract artist from typed row for navigation callbacks. */
    protected abstract MediaArtist artistFromRow(R row);

    /** Extract album from typed row for navigation callbacks. */
    protected abstract AL albumFromRow(R row);

    /** Extract playlist from typed row for navigation callbacks. */
    protected abstract P playlistFromRow(R row);

    // ── Cover-art ID resolution ──────────────────────────────────

    protected abstract String artIdForSong(S song, AL album);
    protected abstract String artIdForAlbum(AL album);

    // ── Song index lookup ────────────────────────────────────────

    protected abstract int indexOfSongRow(S song);

    // ── Debug tag ─────────────────────────────────────────────────

    protected abstract String debugTag();

    // ── Row-focus callback (typed, dispatched by concrete class) ──

    protected abstract void onRowFocused(R row);

    // ═══════════════════════════════════════════════════════════════
    //  Shared logic — root / navigation helpers
    // ═══════════════════════════════════════════════════════════════

    protected void showRoot() {
        uiMode = UI_ROOT;
        selectedArtist = null;
        selectedAlbum = null;
        selectedPlaylist = null;
        ui.showScrollBrowse();
        ui.setStatusTitle(ui.activity().getString(menuTitleRes()));
        ui.setBreadcrumb(ui.activity().getString(pathRootRes()));
        addRootRow(com.solar.launcher.R.string.browser_artists, new Runnable() {
            @Override public void run() { loadArtists(); }
        });
        addRootRow(com.solar.launcher.R.string.browser_albums, new Runnable() {
            @Override public void run() { loadAlbums(); }
        });
        addRootRow(com.solar.launcher.R.string.browser_playlists, new Runnable() {
            @Override public void run() { loadPlaylists(); }
        });
        addRootRow(com.solar.launcher.R.string.browser_all_songs, new Runnable() {
            @Override public void run() { loadAllTracks(); }
        });
        addRootRow(com.solar.launcher.R.string.browser_search, new Runnable() {
            @Override public void run() {
                ui.openSearchKeyboard(searchQuery);
            }
        });
        ui.focusBrowse();
        ui.updateStatusBar();
    }

    private void addRootRow(int labelRes, final Runnable action) {
        Button row = ui.createListButton(ui.activity().getString(labelRes));
        ui.configureListButton(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ui.clickFeedback();
                action.run();
            }
        });
        ui.addScrollRow(row);
    }

    protected Activity activity() { return ui.activity(); }

    // ═══════════════════════════════════════════════════════════════
    //  Shared logic — load / open flow
    // ═══════════════════════════════════════════════════════════════

    protected void loadArtists() {
        uiMode = UI_ARTISTS;
        ui.setStatusTitle(activity().getString(artistsTitleRes()));
        showLoadingList();
        loadArtistsAsync(new LoadCallback<List<MediaArtist>>() {
            @Override public void onSuccess(List<MediaArtist> result) {
                artists = result != null ? result : new ArrayList<MediaArtist>();
                showArtistRows();
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void loadAlbums() {
        uiMode = UI_ALBUMS;
        selectedArtist = null;
        ui.setStatusTitle(activity().getString(com.solar.launcher.R.string.status_library_albums));
        showLoadingList();
        loadAlbumsAsync(new LoadCallback<List<AL>>() {
            @Override public void onSuccess(List<AL> result) {
                albums = result != null ? result : new ArrayList<AL>();
                showAlbumRows(null);
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void loadAllTracks() {
        uiMode = UI_TRACKS;
        selectedArtist = null;
        selectedAlbum = null;
        selectedPlaylist = null;
        ui.setStatusTitle(activity().getString(com.solar.launcher.R.string.status_library_all_songs));
        showLoadingList();
        loadAllTracksAsync(new LoadCallback<List<S>>() {
            @Override public void onSuccess(List<S> result) {
                songs = result != null ? result : new ArrayList<S>();
                showSongRows(activity().getString(com.solar.launcher.R.string.browser_all_songs));
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void loadPlaylists() {
        uiMode = UI_PLAYLISTS;
        ui.setStatusTitle(activity().getString(com.solar.launcher.R.string.status_library_playlists));
        showLoadingList();
        loadPlaylistsAsync(new LoadCallback<List<P>>() {
            @Override public void onSuccess(List<P> result) {
                playlists = result != null ? result : new ArrayList<P>();
                showPlaylistRows();
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void openAlbums(final MediaArtist artist) {
        selectedArtist = artist;
        uiMode = UI_ALBUMS;
        ui.setStatusTitle(artist.name);
        showLoadingList();
        openAlbumsAsync(artist, new LoadCallback<List<AL>>() {
            @Override public void onSuccess(List<AL> result) {
                albums = result != null ? result : new ArrayList<AL>();
                showAlbumRows(artist.name);
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void openSongs(final AL album) {
        selectedAlbum = album;
        selectedPlaylist = null;
        uiMode = UI_SONGS;
        showLoadingList();
        openSongsAsync(album, new LoadCallback<List<S>>() {
            @Override public void onSuccess(List<S> result) {
                songs = result != null ? result : new ArrayList<S>();
                showSongRows(albumName(album));
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    protected void openPlaylistSongs(final P playlist) {
        selectedPlaylist = playlist;
        selectedAlbum = null;
        uiMode = UI_SONGS;
        showLoadingList();
        openPlaylistSongsAsync(playlist, new LoadCallback<List<S>>() {
            @Override public void onSuccess(List<S> result) {
                songs = result != null ? result : new ArrayList<S>();
                showSongRows(playlistName(playlist));
            }
            @Override public void onError(String message) { showError(message); }
        });
    }

    // ── Subclass accessor for album/playlist name (used in openSongs/openPlaylistSongs) ──

    /** Return album.name — AL has no common base, so concrete class must provide. */
    protected abstract String albumName(AL album);

    /** Return playlist.name — P has no common base, so concrete class must provide. */
    protected abstract String playlistName(P playlist);

    // ═══════════════════════════════════════════════════════════════
    //  Shared logic — row builders (show*Rows)
    // ═══════════════════════════════════════════════════════════════

    protected void showArtistRows() {
        ui.setBreadcrumb(activity().getString(pathArtistsRes()));
        List<R> rows = new ArrayList<R>();
        List<String> index = new ArrayList<String>();
        for (MediaArtist a : artists) {
            R row = newRow();
            row.setKind(MediaBrowseRow.Kind.ARTIST);
            fillArtistRow(row, a);
            rows.add(row);
            index.add(a.name);
        }
        bindFastList(rows, index);
    }

    protected void showAlbumRows(String artistName) {
        if (artistName != null) {
            ui.setBreadcrumb(activity().getString(pathAlbumsRes(), artistName));
        } else {
            ui.setBreadcrumb(activity().getString(pathAllAlbumsRes()));
        }
        List<R> rows = new ArrayList<R>();
        List<String> index = new ArrayList<String>();
        for (AL al : albums) {
            R row = newRow();
            row.setKind(MediaBrowseRow.Kind.ALBUM);
            fillAlbumRow(row, al);
            rows.add(row);
            index.add(albumName(al));
        }
        bindFastList(rows, index);
    }

    protected void showPlaylistRows() {
        ui.setBreadcrumb(activity().getString(pathPlaylistsRes()));
        List<R> rows = new ArrayList<R>();
        List<String> index = new ArrayList<String>();
        for (P p : playlists) {
            R row = newRow();
            row.setKind(MediaBrowseRow.Kind.PLAYLIST);
            fillPlaylistRow(row, p);
            rows.add(row);
            index.add(playlistName(p));
        }
        bindFastList(rows, index);
    }

    protected void showSongRows(String title) {
        if (uiMode == UI_TRACKS) {
            ui.setBreadcrumb(activity().getString(pathAllSongsRes()));
        } else {
            ui.setBreadcrumb(activity().getString(pathSongsRes(), title));
        }
        List<R> rows = new ArrayList<R>();
        List<String> index = new ArrayList<String>();
        for (S s : songs) {
            R row = newRow();
            row.setKind(MediaBrowseRow.Kind.SONG);
            fillSongRow(row, s, selectedAlbum);
            rows.add(row);
            index.add(songTitle(s));
        }
        bindFastList(rows, index);
    }

    protected void showSearchResults(SR result) {
        ui.setBreadcrumb(activity().getString(pathSearchRes(), searchQuery));
        songs = new ArrayList<S>();
        List<R> rows = new ArrayList<R>();
        List<String> index = new ArrayList<String>();
        if (result != null) {
            for (MediaArtist a : searchResultArtists(result)) {
                R row = newRow();
                row.setKind(MediaBrowseRow.Kind.ARTIST);
                fillArtistRow(row, a);
                rows.add(row);
                index.add(a.name);
            }
            for (AL al : searchResultAlbums(result)) {
                R row = newRow();
                row.setKind(MediaBrowseRow.Kind.ALBUM);
                fillAlbumRow(row, al);
                rows.add(row);
                index.add(albumName(al));
            }
            for (S s : searchResultSongs(result)) {
                songs.add(s);
                R row = newRow();
                row.setKind(MediaBrowseRow.Kind.SONG);
                fillSongRow(row, s, null);
                rows.add(row);
                index.add(songTitle(s));
            }
        }
        if (rows.isEmpty()) {
            showScrollBrowseEmpty(activity().getString(searchEmptyRes()));
            return;
        }
        bindFastList(rows, index);
    }

    // ── SearchResult accessors ───────────────────────────────────

    protected abstract List<MediaArtist>  searchResultArtists(SR result);
    protected abstract List<AL> searchResultAlbums(SR result);
    protected abstract List<S>  searchResultSongs(SR result);

    // ── Song title accessor (S has no common base) ───────────────

    protected abstract String songTitle(S song);

    // ═══════════════════════════════════════════════════════════════
    //  Shared logic — UI helpers
    // ═══════════════════════════════════════════════════════════════

    protected void bindFastList(List<R> rows, List<String> indexNames) {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("uiMode", uiMode);
            d.put("rowCount", rows.size());
            com.solar.launcher.DebugSessionLog.log(
                    debugTag() + ".bindFastList", "list bind", "53fa55", d);
        } catch (Exception ignored) {}
        // #endregion
        ui.showFastListBrowse();
        ui.setScrollIndexNames(indexNames);
        adapter.setRows(rows);
        ui.setFastListAdapter(adapter);
        ui.focusBrowse();
        ui.updateStatusBar();
    }

    protected void showLoadingList() {
        ui.showScrollBrowse();
        Button loading = ui.createListButton(activity().getString(loadingRes()));
        loading.setEnabled(false);
        ui.addScrollRow(loading);
        ui.focusBrowse();
    }

    protected void showError(String message) {
        showScrollBrowseEmpty(message != null ? message : "Error");
    }

    protected void showScrollBrowseEmpty(String message) {
        ui.showScrollBrowse();
        Button err = ui.createListButton(message);
        err.setEnabled(false);
        ui.addScrollRow(err);
        ui.focusBrowse();
        ui.updateStatusBar();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shared row-click dispatch (navigation part only)
    // ═══════════════════════════════════════════════════════════════

    protected void onRowClick(R row) {
        if (row == null) return;
        ui.clickFeedback();
        if (row.getKind() == MediaBrowseRow.Kind.ARTIST) {
            MediaArtist artist = artistFromRow(row);
            if (artist != null) openAlbums(artist);
        } else if (row.getKind() == MediaBrowseRow.Kind.ALBUM) {
            AL album = albumFromRow(row);
            if (album != null) openSongs(album);
        } else if (row.getKind() == MediaBrowseRow.Kind.PLAYLIST) {
            P playlist = playlistFromRow(row);
            if (playlist != null) openPlaylistSongs(playlist);
        }
        // Song click handled by subclass (needs typed playSongs via Actions)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shared cover-art helpers (Artist-only: uses MediaArtist directly)
    // ═══════════════════════════════════════════════════════════════

    protected static String artIdForArtist(MediaArtist artist) {
        if (artist == null) return "";
        if (artist.coverArtId != null && !artist.coverArtId.isEmpty()) return artist.coverArtId;
        return artist.id != null ? artist.id : "";
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shared back-stack handler
    // ═══════════════════════════════════════════════════════════════

    /** Returns true if back was consumed (stay in screen), false to exit. */
    protected boolean handleBackImpl() {
        switch (uiMode) {
            case UI_ROOT:
                return false;
            case UI_ARTISTS:
            case UI_ALBUMS:
            case UI_PLAYLISTS:
            case UI_TRACKS:
            case UI_SEARCH:
                showRoot();
                return true;
            case UI_SONGS:
                if (selectedPlaylist != null) {
                    loadPlaylists();
                } else if (selectedArtist != null) {
                    openAlbums(selectedArtist);
                } else {
                    loadAlbums();
                }
                return true;
            default:
                showRoot();
                return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shared public accessor helpers
    // ═══════════════════════════════════════════════════════════════

    protected boolean isAlbumListVisibleImpl() { return uiMode == UI_ALBUMS; }
    protected boolean isSongListVisibleImpl()  { return uiMode == UI_SONGS || uiMode == UI_TRACKS; }

    protected R browseRowAt(int position) { return adapter.rowAt(position); }
}
