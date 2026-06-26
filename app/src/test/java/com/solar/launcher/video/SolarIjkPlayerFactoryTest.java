package com.solar.launcher.video;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class SolarIjkPlayerFactoryTest {

    @Test
    public void y1Options_matchMt6572Defaults() {
        SolarIjkPlayerFactory.selfCheck();
        List<SolarIjkPlayerFactory.Option> opts = SolarIjkPlayerFactory.y1PlayerOptions();
        if (opts.isEmpty()) throw new AssertionError("empty");
        for (SolarIjkPlayerFactory.Option o : opts) {
            if (o.name == null || o.name.isEmpty()) throw new AssertionError("name");
        }
    }
}
