package com.solar.launcher.eq;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

public class SolarEqControllerTest {

    private SolarEqController controller;

    @Before
    public void setup() {
        controller = SolarEqController.get();
        // ensure a flat active state before tests
        controller.setActive(null, new EqBandModel());
    }

    @Test
    public void testNeedsSoftwareEq_nullModel_returnsFalse() throws Exception {
        // Reflectively set active to null to test the null safety branch
        Field activeField = SolarEqController.class.getDeclaredField("active");
        activeField.setAccessible(true);
        EqBandModel oldActive = (EqBandModel) activeField.get(controller);

        try {
            activeField.set(controller, null);
            assertFalse("Null EQ active state should not need software eq", controller.needsSoftwareEq());
        } finally {
            // Restore state
            activeField.set(controller, oldActive);
        }
    }

    @Test
    public void testNeedsSoftwareEq_flat_returnsFalse() {
        EqBandModel model = new EqBandModel();
        controller.setActive(null, model);
        assertFalse("Flat EQ should not need software eq", controller.needsSoftwareEq());
    }

    @Test
    public void testNeedsSoftwareEq_preampAdjusted_returnsTrue() {
        EqBandModel model = new EqBandModel();
        model.setPreampDb(5.0f);
        controller.setActive(null, model);
        assertTrue("Adjusted preamp should need software eq", controller.needsSoftwareEq());
    }

    @Test
    public void testNeedsSoftwareEq_bandGainAdjusted_returnsTrue() {
        EqBandModel model = new EqBandModel();
        model.setGainDb(0, 5.0f);
        controller.setActive(null, model);
        assertTrue("Adjusted band gain should need software eq", controller.needsSoftwareEq());
    }

    @Test
    public void testNeedsSoftwareEq_disabled_returnsFalse() {
        EqBandModel model = new EqBandModel();
        model.setPreampDb(5.0f);
        model.setEnabled(false);
        controller.setActive(null, model);
        assertFalse("Disabled EQ should not need software eq even with gains", controller.needsSoftwareEq());
    }
}
