package com.solar.launcher;

import com.solar.launcher.feature.home.HomeMenuConfig;
import com.solar.launcher.feature.settings.RowKeys;

import org.junit.Test;

public class RowKeysTest {
    @Test
    public void homeShortcut_keyFormat() {
        String key = RowKeys.homeShortcut(HomeMenuConfig.ID_MUSIC);
        if (!"home.shortcut.music".equals(key)) throw new AssertionError("key");
        // labelResId for home shortcuts comes from feature/home R; non-home rows return 0
        // (app passes R.string at call sites after module split).
        if (RowKeys.labelResId(RowKeys.SHUFFLE) != 0) {
            throw new AssertionError("non-home row labels live in app call sites");
        }
        int homeLabel = RowKeys.labelResId(key);
        if (homeLabel == 0) throw new AssertionError("home shortcut should resolve label");
    }
}
