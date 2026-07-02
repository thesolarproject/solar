package com.solar.launcher.flow;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlowViewReflectionTest {

    @Test
    public void reflectionsOnUnlessDebugPref() {
        assertFalse(FlowView.shouldSkipCoverReflection(false));
        assertTrue(FlowView.shouldSkipCoverReflection(true));
    }

    @Test
    public void scrollingDoesNotSuppressReflections() {
        assertFalse(FlowView.shouldSkipCoverReflection(false, false, true, true));
        assertFalse(FlowView.shouldSkipCoverReflection(false, false, true, false));
    }

    @Test
    public void handoffDoesNotSuppressReflections() {
        assertFalse(FlowView.shouldSkipCoverReflection(false, true, false, true));
        assertFalse(FlowView.shouldSkipCoverReflection(false, true, false, false));
        assertFalse(FlowView.shouldSkipCoverReflection(false, false, false, true, 0.5f));
    }

    @Test
    public void noReflectionsOverridesAllCovers() {
        assertTrue(FlowView.shouldSkipCoverReflection(true, false, false, true));
        assertTrue(FlowView.shouldSkipCoverReflection(true, false, true, false));
    }
}
