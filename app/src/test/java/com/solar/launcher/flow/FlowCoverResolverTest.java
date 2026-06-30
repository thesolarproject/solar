package com.solar.launcher.flow;

import android.graphics.Bitmap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FlowCoverResolverTest {

    @Test
    public void initialsFromName() {
        assertEquals("R", FlowCoverResolver.initialsFor("Radiohead"));
        assertEquals("TC", FlowCoverResolver.initialsFor("The Cure"));
        assertEquals("?", FlowCoverResolver.initialsFor(""));
    }
}
