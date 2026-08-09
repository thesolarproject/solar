package com.solar.launcher.stem;

/**
 * Map NP Instrumentals/Vocals toggles onto StemMixer zone gains.
 * Layman: Vocals dial = voice pad; Instrumentals dial = drums+bass+melody together.
 * Technical: zone0 ↔ vocals; zones 1–3 ↔ instrumentals group; Melody stays in mix when instr on.
 * Was: TransportLayerPair 2-file gains. Reversal: ignore — use solo layer pair again.
 * 2026-07-21
 */
public final class NpStemPadGains {

    /** Vocals pad index in StemMixer — StemFM compass (N). 2026-07-21 / 2026-08-02 */
    public static final int ZONE_VOCALS = 0;
    /** Bass pad — StemFM compass (W). 2026-08-02 */
    public static final int ZONE_BASS = 1;
    /** Melody pad — StemFM compass (E). 2026-08-02 */
    public static final int ZONE_MELODY = 2;
    /** Drums pad — StemFM compass (S). 2026-08-02 */
    public static final int ZONE_DRUMS = 3;

    private NpStemPadGains() {}

    /**
     * Four zone targets for Vocals ✓ / Instrumentals ✓.
     * Whole song when both on (Melody included). Silent vocals when Instrumentals only.
     * 2026-07-21
     */
    public static float[] targets(boolean wantVocals, boolean wantInstr) {
        float v = SoloLayerGains.targetGain(wantVocals);
        float i = SoloLayerGains.targetGain(wantInstr);
        return new float[] {v, i, i, i};
    }

    /**
     * Gain for one zone under current layer prefs.
     * 2026-07-21
     */
    public static float targetForZone(int zone, boolean wantVocals, boolean wantInstr) {
        float[] t = targets(wantVocals, wantInstr);
        if (zone < 0 || zone >= t.length) return 0f;
        return t[zone];
    }

    /**
     * True when all four pads at full = reconstituted whole song.
     * 2026-07-21
     */
    public static boolean isFullSongMix(boolean wantVocals, boolean wantInstr) {
        return wantVocals && wantInstr;
    }
}
