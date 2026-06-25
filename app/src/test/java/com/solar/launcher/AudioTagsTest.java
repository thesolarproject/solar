package com.solar.launcher;

/** ponytail: self-check for tag fallbacks — fails if filename / album-artist logic breaks. */
public class AudioTagsTest {
    public static void main(String[] args) {
        albumArtistFallback();
        filenameParse();
        validTags();
        System.out.println("AudioTagsTest OK");
    }

    static void albumArtistFallback() {
        AudioTags.Info info = new AudioTags.Info();
        info.albumArtist = "Led Zeppelin";
        AudioTags.resolveArtist(info, "03 Dazed And Confused.mp3");
        if (!"Led Zeppelin".equals(info.artist)) {
            throw new AssertionError("albumArtist fallback: " + info.artist);
        }
        if (!"albumArtist".equals(info.artistSource)) {
            throw new AssertionError("source: " + info.artistSource);
        }
    }

    static void filenameParse() {
        AudioTags.ParsedName p = AudioTags.parseArtistTitleFromFileName(
                "Led Zeppelin - Dazed and Confused.mp3");
        if (!"Led Zeppelin".equals(p.artist) || !"Dazed and Confused".equals(p.title)) {
            throw new AssertionError("parse: " + p.artist + " / " + p.title);
        }
        AudioTags.Info info = new AudioTags.Info();
        AudioTags.resolveArtist(info, "Led Zeppelin - Dazed and Confused.flac");
        if (!"Led Zeppelin".equals(info.artist)) {
            throw new AssertionError("filename artist: " + info.artist);
        }
    }

    static void validTags() {
        AudioTags.Info ok = new AudioTags.Info();
        ok.title = "Dazed and Confused";
        ok.artist = "Led Zeppelin";
        if (!AudioTags.hasValidTags(ok)) throw new AssertionError("expected valid");
        AudioTags.Info bad = new AudioTags.Info();
        bad.title = "Dazed and Confused";
        bad.artist = "Unknown Artist";
        if (AudioTags.hasValidTags(bad)) throw new AssertionError("expected invalid");
    }
}
