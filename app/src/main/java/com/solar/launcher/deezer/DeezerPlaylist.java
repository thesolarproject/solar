package com.solar.launcher.deezer;

/** User playlist summary from api.deezer.com. */
public final class DeezerPlaylist {
    public final long id;
    public final String title;
    public final int trackCount;
    public final String pictureUrl;

    public DeezerPlaylist(long id, String title, int trackCount, String pictureUrl) {
        this.id = id;
        this.title = title != null ? title : "";
        this.trackCount = trackCount;
        this.pictureUrl = pictureUrl != null ? pictureUrl : "";
    }

    public String safeFileName() {
        String base = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "playlist_" + id;
        return base;
    }
}
