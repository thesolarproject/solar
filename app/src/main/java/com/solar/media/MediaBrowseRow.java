package com.solar.media;

/**
 * 2026-07-29: Shared browse-row interface — Navidrome, Jellyfin, and Plex all have
 * identical row structures (Kind enum + label/subtitle/coverArtId strings), so the
 * generic {@link MediaBrowseAdapter} can work with any server's concrete row type
 * without touching MainActivity or the per-server Actions interfaces.
 */
public interface MediaBrowseRow {

    enum Kind { ARTIST, ALBUM, SONG, PLAYLIST }

    Kind getKind();
    void setKind(Kind kind);
    String getLabel();
    String getSubtitle();
    String getCoverArtId();
}
