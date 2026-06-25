package com.solar.launcher.theme;

import android.graphics.Color;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * JJ Launcher theme engine (experimental): flat colors + optional {@code main_menu[]} layout.
 * Themes live under {@link ActiveThemeEngine#jjThemesRoot()}.
 */
public final class JjThemeManager {

    public static final class MenuElement {
        public final String id, type;
        public final int x, y, width, height;
        public final String textNormal, textFocused, textRight;
        public final String iconNormal, iconFocused, action;
        public final int radius, focusIndex, textSize;
        public final int focusOffsetX, focusOffsetY;
        public final float focusScale;

        public MenuElement(String id, String type, int x, int y, int width, int height,
                           String textNormal, String textFocused, String textRight,
                           String iconNormal, String iconFocused, String action,
                           int radius, int focusIndex, int textSize,
                           int focusOffsetX, int focusOffsetY, float focusScale) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.textNormal = textNormal;
            this.textFocused = textFocused;
            this.textRight = textRight;
            this.iconNormal = iconNormal;
            this.iconFocused = iconFocused;
            this.action = action;
            this.radius = radius;
            this.focusIndex = focusIndex;
            this.textSize = textSize;
            this.focusOffsetX = focusOffsetX;
            this.focusOffsetY = focusOffsetY;
            this.focusScale = focusScale;
        }
    }

    public static final class ThemeData {
        public final String folderPath;
        public final String name;
        public final Typeface customFont;
        public final int textPrimary, textSecondary;
        public final int bgOverlay, statusBarBg;
        public final int btnNormal, btnFocused, btnFocusedText, buttonRadius;
        public final List<MenuElement> menuElements;

        public ThemeData(String folderPath, String name, Typeface customFont,
                         int textPrimary, int textSecondary, int bgOverlay, int statusBarBg,
                         int btnNormal, int btnFocused, int btnFocusedText, int buttonRadius) {
            this.folderPath = folderPath;
            this.name = name;
            this.customFont = customFont;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.bgOverlay = bgOverlay;
            this.statusBarBg = statusBarBg;
            this.btnNormal = btnNormal;
            this.btnFocused = btnFocused;
            this.btnFocusedText = btnFocusedText;
            this.buttonRadius = buttonRadius;
            this.menuElements = new ArrayList<MenuElement>();
        }
    }

    public static final List<ThemeData> availableThemes = new ArrayList<ThemeData>();
    private static int currentThemeIndex;

    private JjThemeManager() {}

    private static int safeParseColor(String colorStr, int defaultColor) {
        try {
            if (colorStr != null && !colorStr.trim().isEmpty()) {
                return Color.parseColor(colorStr.trim());
            }
        } catch (Exception ignored) {}
        return defaultColor;
    }

    public static void loadThemesFromStorage(File themeFolder) {
        availableThemes.clear();
        ThemeData builtIn = buildBuiltinDefault();
        availableThemes.add(builtIn);

        if (themeFolder == null) return;
        if (!themeFolder.exists()) {
            themeFolder.mkdirs();
            return;
        }

        extractZipThemes(themeFolder);

        File[] folders = themeFolder.listFiles();
        if (folders == null) return;
        for (File subFolder : folders) {
            if (!subFolder.isDirectory()) continue;
            File configFile = new File(subFolder, "config.json");
            if (!configFile.isFile()) continue;
            try {
                FileInputStream fis = new FileInputStream(configFile);
                byte[] data = new byte[(int) configFile.length()];
                fis.read(data);
                fis.close();

                String jsonStr = new String(data, "UTF-8").replace("\uFEFF", "");
                JSONObject json = new JSONObject(jsonStr);

                int parsedOverlayBg = safeParseColor(json.optString("bgOverlay"), 0x88000000);
                int parsedStatusBarBg = safeParseColor(json.optString("statusBarBg"), parsedOverlayBg);
                Typeface parsedFont = Typeface.DEFAULT;
                if (json.has("font")) {
                    File fontFile = new File(subFolder, json.getString("font"));
                    if (fontFile.isFile()) {
                        try {
                            parsedFont = Typeface.createFromFile(fontFile);
                        } catch (Exception ignored) {}
                    }
                }

                ThemeData theme = new ThemeData(
                        subFolder.getAbsolutePath(),
                        json.optString("name", subFolder.getName()),
                        parsedFont,
                        safeParseColor(json.optString("textPrimary"), 0xFFFFFFFF),
                        safeParseColor(json.optString("textSecondary"), 0xFF888888),
                        parsedOverlayBg,
                        parsedStatusBarBg,
                        safeParseColor(json.optString("btnNormal"), 0x15FFFFFF),
                        safeParseColor(json.optString("btnFocused"), 0xDDFFFFFF),
                        safeParseColor(json.optString("btnFocusedText"), 0xFF000000),
                        json.optInt("button_radius", 15));

                if (json.has("main_menu")) {
                    JSONArray menuArray = json.getJSONArray("main_menu");
                    for (int i = 0; i < menuArray.length(); i++) {
                        JSONObject el = menuArray.getJSONObject(i);
                        theme.menuElements.add(new MenuElement(
                                el.optString("id", "item_" + i),
                                el.optString("type", "button"),
                                el.optInt("x", 0),
                                el.optInt("y", i * 60),
                                el.optInt("width", 200),
                                el.optInt("height", 50),
                                el.optString("text_normal", ""),
                                el.optString("text_focused", ""),
                                el.optString("text_right", ""),
                                el.optString("icon_normal", ""),
                                el.optString("icon_focused", ""),
                                el.optString("action", "NONE"),
                                el.optInt("radius", -1),
                                el.optInt("focus_index", i + 1),
                                el.optInt("text_size", -1),
                                el.optInt("focus_offset_x", 0),
                                el.optInt("focus_offset_y", 0),
                                (float) el.optDouble("focus_scale", 1.0)));
                    }
                }
                availableThemes.add(theme);
            } catch (Exception ignored) {}
        }
    }

    private static ThemeData buildBuiltinDefault() {
        ThemeData defaultTheme = new ThemeData("default", "JJ Default", Typeface.DEFAULT,
                0xFFFFFFFF, 0xFF888888, 0x88000000, 0x88000000,
                0x15FFFFFF, 0xDDFFFFFF, 0xFF000000, 15);
        defaultTheme.menuElements.add(new MenuElement("btn_now", "button", 0, 20, 250, 50,
                "Now Playing", "Now Playing", "", "icon_now_playing.png", "", "OPEN_PLAYER",
                -1, 1, 18, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_music", "button", 0, 80, 250, 50,
                "Music", "Music", "", "icon_music.png", "", "OPEN_BROWSER",
                -1, 2, 18, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_bt", "button", 0, 140, 250, 50,
                "Bluetooth", "Bluetooth", "", "icon_bluetooth.png", "", "OPEN_BLUETOOTH",
                -1, 3, 18, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_set", "button", 0, 200, 250, 50,
                "Settings", "Settings", "", "icon_setting.png", "", "OPEN_SETTINGS",
                -1, 4, 18, 0, 0, 1.0f));
        return defaultTheme;
    }

    private static void extractZipThemes(File themeFolder) {
        File[] allFiles = themeFolder.listFiles();
        if (allFiles == null) return;
        for (File file : allFiles) {
            if (!file.isFile() || !file.getName().toLowerCase().endsWith(".zip")) continue;
            try {
                String folderName = file.getName().substring(0, file.getName().lastIndexOf('.'));
                File extractDir = new File(themeFolder, folderName);
                if (!extractDir.exists()) extractDir.mkdirs();
                ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    File extractFile = new File(extractDir, ze.getName());
                    if (ze.isDirectory()) {
                        extractFile.mkdirs();
                    } else {
                        File parent = extractFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        FileOutputStream fout = new FileOutputStream(extractFile);
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                    }
                    zis.closeEntry();
                }
                zis.close();
                file.delete();
            } catch (Exception ignored) {}
        }
    }

    public static void setThemeIndex(int index) {
        if (index >= 0 && index < availableThemes.size()) currentThemeIndex = index;
        else currentThemeIndex = 0;
    }

    public static int getCurrentThemeIndex() {
        return currentThemeIndex;
    }

    public static ThemeData getCurrentTheme() {
        if (availableThemes.isEmpty()) return buildBuiltinDefault();
        int idx = currentThemeIndex;
        if (idx < 0 || idx >= availableThemes.size()) idx = 0;
        return availableThemes.get(idx);
    }

    public static Typeface getCustomFont() {
        return getCurrentTheme().customFont;
    }

    public static int getTextColorPrimary() {
        return getCurrentTheme().textPrimary;
    }

    public static int getTextColorSecondary() {
        return getCurrentTheme().textSecondary;
    }

    public static int getOverlayBackgroundColor() {
        return getCurrentTheme().bgOverlay;
    }

    public static int getStatusBarBackgroundColor() {
        return getCurrentTheme().statusBarBg;
    }

    public static int getListButtonNormalBg() {
        return getCurrentTheme().btnNormal;
    }

    public static int getListButtonFocusedBg() {
        return getCurrentTheme().btnFocused;
    }

    public static int getListButtonFocusedTextColor() {
        return getCurrentTheme().btnFocusedText;
    }

    public static int getButtonRadius() {
        return getCurrentTheme().buttonRadius;
    }
}
