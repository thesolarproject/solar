package com.solar.launcher.theme;

import android.content.Context;
import android.content.res.Configuration;

import com.solar.launcher.HomeMenuConfig;
import com.solar.launcher.RowKeys;

import java.util.Locale;

/**
 * Solar theme extensions on top of stock Y1 {@code config.json} blocks.
 * <p>
 * {@code solarConfig} keys are open-ended: {@code app{Name}} for home shortcuts and
 * {@code settings{Name}} for settings right-pane previews. {@code Name} is derived from the
 * English UI label (see {@link #englishString(Context, int)}), so keys stay stable when the
 * device locale changes.
 * <p>
 * Resolution order: active theme {@code solarConfig} → stock Y1 block ({@code homePageConfig} /
 * {@code settingConfig}) → Android drawable fallback (stock home rows only).
 */
public final class SolarTheming {
    public static final String APP_KEY_PREFIX = "app";
    public static final String SETTINGS_KEY_PREFIX = "settings";

    private SolarTheming() {}

    /** English string for a label resource — used for stable solarConfig key lookup. */
    public static String englishString(Context context, int stringResId) {
        if (context == null || stringResId == 0) return "";
        Configuration conf = new Configuration(context.getResources().getConfiguration());
        conf.setLocale(Locale.US);
        return context.createConfigurationContext(conf).getResources().getString(stringResId);
    }

    /** English settings row label for solarConfig {@code settings*} lookup. */
    public static String englishSettingsRowLabel(Context context, String rowKey) {
        if (context == null || rowKey == null) return "";
        if (rowKey.startsWith("home.shortcut.")) {
            HomeMenuConfig.Entry e = HomeMenuConfig.find(rowKey.substring("home.shortcut.".length()));
            return e != null ? e.englishLabel(context) : "";
        }
        int res = RowKeys.labelResId(rowKey);
        return res != 0 ? englishString(context, res) : "";
    }

    // ponytail: self-check — key rules must not drift silently
    static void selfCheck() {
        if (!"appMusic".equals(ThemeManager.solarAppConfigKey("Music"))) {
            throw new AssertionError("appMusic");
        }
        if (!"settingsAbout".equals(ThemeManager.solarSettingsConfigKey("About"))) {
            throw new AssertionError("settingsAbout");
        }
        if (!"appGet_Music".equals(ThemeManager.solarAppConfigKey("Get Music"))) {
            throw new AssertionError("appGet_Music");
        }
    }
}
