package com.solar.launcher.stem;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * IJK SoundTouch options for Stem tempo match (no chipmunk).
 * Layman: stretch time so other songs match Song 1’s pulse without going squeaky.
 * Technical: soundtouch=1 + audio-only before prepare; same idea as PodcastIjkPlayer.
 * Was: MediaPlayer @ 1.0 only. Reversal: skip applyStemPlayerOptions.
 * 2026-07-19
 */
public final class StemSoundTouch {
    private StemSoundTouch() {}

    /** Wire pitch-preserving rate options on an IJK player before setDataSource. */
    public static void applyStemPlayerOptions(IjkMediaPlayer player) {
        if (player == null) return;
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        // 2026-07-21 — OpenSL ES hardware audio track bypasses Java JNI / AudioTrack buffering on MTK.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1);
        // 2026-07-21 — Low latency packet buffering for responsive stem real-time mixing.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 131072);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1);
    }

    /** True when option list matches stem SoundTouch contract (unit-test hook). */
    public static boolean isSoundTouchEnabled(long soundtouchOptionValue) {
        return soundtouchOptionValue == 1L;
    }

    /**
     * DJ-style pitch factor shifting song's key onto the master's (Camelot matching).
     * Layman: nudge the slave's pitch so its key sits near Song 1's, like a harmonic DJ mix.
     * Technical: relative-minor treated as ±3 semitones; diff clamped to ±6; 2^(diff/12).
     * Ported verbatim from MixPlayerHost key-alignment (2026-08-02) so Mix and Stem mashup
     * agree on the same wheel math; returns 1.0 when either key is unknown.
     */
    public static float pitchFactorForKeys(int masterKeyRoot, boolean masterKeyMajor,
            int songKeyRoot, boolean songKeyMajor) {
        if (masterKeyRoot < 0 || songKeyRoot < 0) return 1f;
        int targetRoot = masterKeyRoot;
        if (masterKeyMajor != songKeyMajor) {
            if (masterKeyMajor) targetRoot -= 3;
            else targetRoot += 3;
        }
        int diff = (targetRoot - songKeyRoot) % 12;
        if (diff < -6) diff += 12;
        if (diff > 6) diff -= 12;
        return (float) Math.pow(2.0, diff / 12.0);
    }

    /** Wire native pitch shift (soundtouch-pitch) on a live IJK player. 2026-08-02 */
    public static void applyPitchOption(tv.danmaku.ijk.media.player.IjkMediaPlayer player,
            float pitchFactor) {
        if (player == null) return;
        try {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch-pitch",
                    String.valueOf(pitchFactor > 0.01f ? pitchFactor : 1f));
        } catch (Exception ignored) {}
    }
}
