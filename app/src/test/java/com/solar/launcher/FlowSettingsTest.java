package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlowSettingsTest {

    @Test
    public void flowEnabledDefaultsTrueWhenPrefsNull() {
        assertTrue(FlowSettings.isEnabled(null));
    }

    @Test
    public void flowRowKeyMapsToFlowLabel() {
        assertEquals(R.string.settings_flow, RowKeys.labelResId(RowKeys.FLOW));
    }

    @Test
    public void flowExperimentsSubmenuLabel() {
        assertEquals(R.string.settings_sub_flow, RowKeys.labelResId(RowKeys.FLOW_SETTINGS));
    }

    @Test
    public void prefKeyMatchesLegacyDebugStorage() {
        assertEquals("debug_flow_enabled", FlowSettings.PREF_FLOW_ENABLED);
    }
}
