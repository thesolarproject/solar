package com.solar.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppLauncherTest {
    @Test
    public void entrySortsAlphabetically() {
        List<AppLauncher.Entry> list = new ArrayList<AppLauncher.Entry>();
        list.add(new AppLauncher.Entry("Zebra", "z"));
        list.add(new AppLauncher.Entry("Alpha", "a"));
        Collections.sort(list);
        if (!"Alpha".equals(list.get(0).label)) throw new AssertionError("sort order");
    }
}
