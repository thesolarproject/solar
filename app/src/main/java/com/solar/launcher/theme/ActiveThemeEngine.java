package com.solar.launcher.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.solar.launcher.theme.ThemeBrowser.Row;
import com.solar.launcher.theme.ThemeBrowser.UiText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes theming between Y1 {@link ThemeManager} and experimental {@link JjThemeManager}.
 */
public final class ActiveThemeEngine {

    public static final String PREF_JJ_THEMES = "experimental_jj_themes";
    public static final String PREF_JJ_THEME_INDEX = "jj_theme_index";
  private static final String JJ_THEMES_DIR = "JJ_Themes";

    private static Context appContext;

    private ActiveThemeEngine() {}

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static boolean isJjMode() {
        if (appContext == null) return false;
        return prefs().getBoolean(PREF_JJ_THEMES, false);
    }

    public static void setJjMode(Context context, boolean enabled) {
        init(context);
        prefs().edit().putBoolean(PREF_JJ_THEMES, enabled).commit();
    }

    public static String jjThemesRoot() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                return new File(ext, JJ_THEMES_DIR).getAbsolutePath();
            }
        } catch (Exception ignored) {}
        if (appContext != null) {
            return new File(appContext.getFilesDir(), JJ_THEMES_DIR).getAbsolutePath();
        }
        return "/storage/sdcard0/" + JJ_THEMES_DIR;
    }

    public static void loadThemes(Context context) {
        init(context);
        if (isJjMode()) {
            JjThemeManager.loadThemesFromStorage(new File(jjThemesRoot()));
            int idx = prefs().getInt(PREF_JJ_THEME_INDEX, 0);
            JjThemeManager.setThemeIndex(idx);
        } else {
            ThemeManager.loadAllThemes(context);
        }
    }

    public static void applyJjThemeIndex(Context context, int index) {
        init(context);
        JjThemeManager.setThemeIndex(index);
        prefs().edit().putInt(PREF_JJ_THEME_INDEX, index).commit();
    }

    public static List<Row> buildJjInstalledRows(UiText text) {
        UiText t = text != null ? text : new UiText();
        List<Row> out = new ArrayList<Row>();
        out.add(Row.back());
        int current = JjThemeManager.getCurrentThemeIndex();
        if (JjThemeManager.availableThemes.isEmpty()) {
            out.add(Row.status(t.noInstalledMatch));
            return out;
        }
        for (int i = 0; i < JjThemeManager.availableThemes.size(); i++) {
            JjThemeManager.ThemeData theme = JjThemeManager.availableThemes.get(i);
            String prefix = (i == current) ? "✔ " : "   ";
            out.add(new Row(ThemeBrowser.KIND_INSTALLED, theme.name, "", prefix, i,
                    null, null, i == current, false));
        }
        return out;
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
    }
}
