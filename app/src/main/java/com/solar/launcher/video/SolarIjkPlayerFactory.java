package com.solar.launcher.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * Y1/Y2 (MT6572, API 17) IJK defaults — distilled from upstream IjkVideoView.createPlayer().
 * ponytail: native/Java must stay paired with stock 0.8.8 .so from vendor-ijk-from-stock.sh.
 */
public final class SolarIjkPlayerFactory {

    /** One player option before native apply. */
    public static final class Option {
        public final int category;
        public final String name;
        public final long longValue;
        public final String stringValue;

        private Option(int category, String name, long longValue) {
            this.category = category;
            this.name = name;
            this.longValue = longValue;
            this.stringValue = null;
        }

        private Option(int category, String name, String stringValue) {
            this.category = category;
            this.name = name;
            this.longValue = 0;
            this.stringValue = stringValue;
        }
    }

    private SolarIjkPlayerFactory() {}

    /**
     * MT6572 / API 17 HW decode on, conservative MediaCodec flags, framedrop for weak CPU.
     * 2026-07-14 — Extra HTTP timeouts / reconnect / UA so YouTube CDN progressive MP4 is less fragile.
     * Layman: give the player more patience on wifi and tell servers we are a normal browser.
     * Reversal: drop the FORMAT options below http-detect-range-support.
     */
    public static List<Option> y1PlayerOptions() {
        List<Option> out = new ArrayList<Option>();
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48));
        // Range headers for long seeks / reconnections on progressive CDN MP4.
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 1));
        // Cold CDN / Piped proxy — microseconds for ffurl / analyzeduration.
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30000000L));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 15000000L));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent",
                "Mozilla/5.0 (Linux; Android 4.2) AppleWebKit/537.36"));
        return Collections.unmodifiableList(out);
    }

    /** Cached native-lib availability probe (negative cache after first failure). 2026-08-01 */
    private static volatile Boolean ijkNativeAvailable;

    /**
     * True when the IJK native libraries can actually be loaded on this device.
     * Layman: check the player engine is really installed before using it.
     * Technical: probes System.loadLibrary("ijkffmpeg") once and caches. KitKat
     * system-app installs do not extract APK-embedded .so unless also present in
     * /system/lib, so callers must degrade to MediaPlayer instead of crashing with
     * UnsatisfiedLinkError (see the mashup two-song crash on Y2).
     * 2026-08-01
     */
    public static boolean isIjkAvailable() {
        Boolean cached = ijkNativeAvailable;
        if (cached != null) return cached.booleanValue();
        synchronized (SolarIjkPlayerFactory.class) {
            if (ijkNativeAvailable == null) {
                boolean ok = false;
                try {
                    System.loadLibrary("ijkffmpeg");
                    System.loadLibrary("ijksdl");
                    System.loadLibrary("ijkplayer");
                    ok = true;
                } catch (Throwable t) {
                    ok = false;
                }
                ijkNativeAvailable = Boolean.valueOf(ok);
            }
            return ijkNativeAvailable.booleanValue();
        }
    }

    public static IjkMediaPlayer create() {
        IjkMediaPlayer player = new IjkMediaPlayer();
        applyY1Options(player);
        return player;
    }

    /** Apply Y1-tuned options to an existing player (before setDataSource / prepare). */
    public static void applyY1Options(IjkMediaPlayer player) {
        if (player == null) return;
        for (Option opt : y1PlayerOptions()) {
            if (opt.stringValue != null) {
                player.setOption(opt.category, opt.name, opt.stringValue);
            } else {
                player.setOption(opt.category, opt.name, opt.longValue);
            }
        }
    }

    /** JVM self-check — option keys/values without loading native code. */
    static void selfCheck() {
        List<Option> opts = y1PlayerOptions();
        if (opts.size() != 16) throw new AssertionError("option count");
        expect(opts, 0, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
        expect(opts, 1, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0);
        expect(opts, 2, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0);
        expect(opts, 3, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        expect(opts, 4, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        expect(opts, 5, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        expect(opts, 6, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        expect(opts, 7, IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        expect(opts, 8, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 1);
        expect(opts, 9, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30000000L);
        expect(opts, 10, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        expect(opts, 11, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 15000000L);
        expect(opts, 12, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 1);
        expect(opts, 13, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1);
        expect(opts, 14, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5);
        Option ua = opts.get(15);
        if (ua.category != IjkMediaPlayer.OPT_CATEGORY_FORMAT
                || !"user_agent".equals(ua.name)
                || ua.stringValue == null
                || ua.stringValue.indexOf("Android") < 0) {
            throw new AssertionError("option 15 user_agent");
        }
    }

    private static void expect(List<Option> opts, int index, int category, String name, long value) {
        Option o = opts.get(index);
        if (o.category != category || !name.equals(o.name) || o.longValue != value || o.stringValue != null) {
            throw new AssertionError("option " + index + " " + name);
        }
    }
}
