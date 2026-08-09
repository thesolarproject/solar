package com.solar.launcher.stem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StemPreparationPolicyTest {
    @Test
    public void preparationOnlyNeverStartsPerformance() {
        assertFalse(StemPreparationPolicy.shouldStartPerformance(true));
        assertTrue(StemPreparationPolicy.shouldStartPerformance(false));
    }

}
