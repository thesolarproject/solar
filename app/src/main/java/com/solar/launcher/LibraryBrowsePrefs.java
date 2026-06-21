package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted music library browse preferences (artist split, filters, sort). */
public final class LibraryBrowsePrefs {
    public static final int GUEST_BROWSE_AUTO = 0;
    public static final int GUEST_BROWSE_ALWAYS_ALBUMS = 1;
    public static final int GUEST_BROWSE_ALWAYS_SONGS = 2;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_OWNERS_ONLY = 1;
    public static final int FILTER_HIDE_GUEST_ONLY = 2;
    public static final int FILTER_MIN_TWO_TRACKS = 3;

    public static final int ARTIST_SORT_NAME = 0;
    public static final int ARTIST_SORT_TRACK_COUNT = 1;
    public static final int ARTIST_SORT_RECENT = 2;

    public static final int SONG_SORT_TITLE = 0;
    public static final int SONG_SORT_ARTIST = 1;
    public static final int SONG_SORT_ALBUM = 2;
    public static final int SONG_SORT_DATE = 3;

    private static final String PREFS = "SOLAR_SETTINGS";
    private static final String KEY_SPLIT = "lib_split_credits";
    private static final String KEY_NORM_ALBUM = "lib_normalize_album_case";
    private static final String KEY_NORM_HONOR = "lib_normalize_honorifics";
    private static final String KEY_GUEST_MODE = "lib_guest_browse_mode";
    private static final String KEY_ARTIST_FILTER = "lib_artist_filter";
    private static final String KEY_ARTIST_SORT = "lib_artist_sort";
    private static final String KEY_ALBUM_SUB = "lib_album_owner_subtitle";
    private static final String KEY_GUEST_SUB = "lib_guest_song_subtitle";
    private static final String KEY_SONG_SORT = "lib_song_sort";

    private final SharedPreferences prefs;

    public LibraryBrowsePrefs(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Package-visible for unit tests with an in-memory {@link SharedPreferences}. */
    LibraryBrowsePrefs(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean splitCredits() {
        return prefs.getBoolean(KEY_SPLIT, true);
    }

    public void setSplitCredits(boolean on) {
        prefs.edit().putBoolean(KEY_SPLIT, on).commit();
    }

    public boolean normalizeAlbumCase() {
        return prefs.getBoolean(KEY_NORM_ALBUM, true);
    }

    public void setNormalizeAlbumCase(boolean on) {
        prefs.edit().putBoolean(KEY_NORM_ALBUM, on).commit();
    }

    public boolean normalizeHonorifics() {
        return prefs.getBoolean(KEY_NORM_HONOR, true);
    }

    public void setNormalizeHonorifics(boolean on) {
        prefs.edit().putBoolean(KEY_NORM_HONOR, on).commit();
    }

    public int guestBrowseMode() {
        return prefs.getInt(KEY_GUEST_MODE, GUEST_BROWSE_AUTO);
    }

    public int cycleGuestBrowseMode() {
        int next = (guestBrowseMode() + 1) % 3;
        prefs.edit().putInt(KEY_GUEST_MODE, next).commit();
        return next;
    }

    public int artistFilter() {
        return prefs.getInt(KEY_ARTIST_FILTER, FILTER_ALL);
    }

    public int cycleArtistFilter() {
        int next = (artistFilter() + 1) % 4;
        prefs.edit().putInt(KEY_ARTIST_FILTER, next).commit();
        return next;
    }

    public int artistSort() {
        return prefs.getInt(KEY_ARTIST_SORT, ARTIST_SORT_NAME);
    }

    public int cycleArtistSort() {
        int next = (artistSort() + 1) % 3;
        prefs.edit().putInt(KEY_ARTIST_SORT, next).commit();
        return next;
    }

    public boolean albumOwnerSubtitles() {
        return prefs.getBoolean(KEY_ALBUM_SUB, true);
    }

    public void setAlbumOwnerSubtitles(boolean on) {
        prefs.edit().putBoolean(KEY_ALBUM_SUB, on).commit();
    }

    public boolean guestSongSubtitles() {
        return prefs.getBoolean(KEY_GUEST_SUB, true);
    }

    public void setGuestSongSubtitles(boolean on) {
        prefs.edit().putBoolean(KEY_GUEST_SUB, on).commit();
    }

    public int songSort() {
        return prefs.getInt(KEY_SONG_SORT, SONG_SORT_TITLE);
    }

    public int cycleSongSort() {
        int next = (songSort() + 1) % 4;
        prefs.edit().putInt(KEY_SONG_SORT, next).commit();
        return next;
    }

    public static int guestBrowseModeLabelRes(int mode) {
        switch (mode) {
            case GUEST_BROWSE_ALWAYS_ALBUMS: return R.string.lib_guest_browse_always_albums;
            case GUEST_BROWSE_ALWAYS_SONGS: return R.string.lib_guest_browse_always_songs;
            default: return R.string.lib_guest_browse_auto;
        }
    }

    public static int artistFilterLabelRes(int filter) {
        switch (filter) {
            case FILTER_OWNERS_ONLY: return R.string.lib_artist_filter_owners;
            case FILTER_HIDE_GUEST_ONLY: return R.string.lib_artist_filter_hide_guests;
            case FILTER_MIN_TWO_TRACKS: return R.string.lib_artist_filter_min_tracks;
            default: return R.string.lib_artist_filter_all;
        }
    }

    public static int artistSortLabelRes(int sort) {
        switch (sort) {
            case ARTIST_SORT_TRACK_COUNT: return R.string.lib_artist_sort_tracks;
            case ARTIST_SORT_RECENT: return R.string.lib_artist_sort_recent;
            default: return R.string.lib_artist_sort_name;
        }
    }

    public static int songSortLabelRes(int sort) {
        switch (sort) {
            case SONG_SORT_ARTIST: return R.string.library_sort_artist;
            case SONG_SORT_ALBUM: return R.string.library_sort_album;
            case SONG_SORT_DATE: return R.string.library_sort_date;
            default: return R.string.library_sort_title;
        }
    }
}
