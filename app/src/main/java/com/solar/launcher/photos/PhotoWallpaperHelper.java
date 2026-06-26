package com.solar.launcher.photos;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Writes custom wallpaper prefs — same keys as MainActivity background picker. */
public final class PhotoWallpaperHelper {
    public static final String PREF_BG_PATH = "bg_path";
    public static final String PREF_BACKGROUND_MODE = "background_mode";
    public static final String PREF_BG_THEME_WALLPAPER = "bg_theme_wallpaper";
    public static final String BG_MODE_CUSTOM = "custom";

    private PhotoWallpaperHelper() {}

    /**
     * Persist custom image wallpaper. Caller should refresh menu background after commit.
     * ponytail: context reserved for future ThemeManager hooks — prefs only today.
     */
    public static boolean applyAsBackground(Context context, File imageFile, SharedPreferences prefs) {
        if (context == null || imageFile == null || !imageFile.isFile() || prefs == null) return false;
        if (!PhotoLibrary.isImageFile(imageFile.getName())) return false;
        try {
            return prefs.edit()
                    .putString(PREF_BG_PATH, imageFile.getAbsolutePath())
                    .putString(PREF_BACKGROUND_MODE, BG_MODE_CUSTOM)
                    .remove(PREF_BG_THEME_WALLPAPER)
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }
}
