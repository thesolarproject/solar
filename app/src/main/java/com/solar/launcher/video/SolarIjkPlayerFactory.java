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

    /** MT6572 / API 17 HW decode on, conservative MediaCodec flags, framedrop for weak CPU. */
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
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0));
        return Collections.unmodifiableList(out);
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
        if (opts.size() != 9) throw new AssertionError("option count");
        expect(opts, 0, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
        expect(opts, 1, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0);
        expect(opts, 2, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0);
        expect(opts, 3, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        expect(opts, 4, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        expect(opts, 5, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        expect(opts, 6, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        expect(opts, 7, IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        expect(opts, 8, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
    }

    private static void expect(List<Option> opts, int index, int category, String name, long value) {
        Option o = opts.get(index);
        if (o.category != category || !name.equals(o.name) || o.longValue != value || o.stringValue != null) {
            throw new AssertionError("option " + index + " " + name);
        }
    }
}
