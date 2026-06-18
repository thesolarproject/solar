package com.solar.launcher;

import org.junit.Test;

public class RowKeysTest {
    @Test
    public void homeShortcut_resolvesLabel() {
        String key = RowKeys.homeShortcut(HomeMenuConfig.ID_MUSIC);
        if (!"home.shortcut.music".equals(key)) throw new AssertionError("key");
        if (RowKeys.labelResId(key) != R.string.home_menu_music) {
            throw new AssertionError("labelResId");
        }
        if (RowKeys.labelResId(RowKeys.SHUFFLE) != R.string.settings_shuffle_mode) {
            throw new AssertionError("shuffle");
        }
    }
}
