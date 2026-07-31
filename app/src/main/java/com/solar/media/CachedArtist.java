package com.solar.media;

/**
 * 2026-07-30: Shared base for MediaArtist / MediaArtist / MediaArtist.
 * Keeps public fields identical to the original POJOs so all existing
 * direct-field call sites compile unchanged.
 */
public class CachedArtist {
    public String id = "";
    public String name = "";
    public int albumCount;
    public String coverArtId;
    public String indexLetter = "#";
}
