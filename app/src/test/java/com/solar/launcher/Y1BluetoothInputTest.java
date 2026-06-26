package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Y1BluetoothInputTest {

    @Test
    public void hardwareNamesDenied() {
        Y1BluetoothInput.selfCheck();
        assertTrue(Y1BluetoothInput.isY1HardwareInputName("mtk-tpd-kpd"));
        assertTrue(Y1BluetoothInput.isY1HardwareInputName("mtk-kpd"));
        assertFalse(Y1BluetoothInput.isY1HardwareInputName("AVRCP"));
    }

    @Test
    public void wheelKeycodesDistinctFromAvrcpSkip() {
        Y1InputKeys.selfCheckWheelMapping();
        assertTrue(Y1InputKeys.isDiscreteMediaPlay(126));
        assertTrue(Y1InputKeys.isAvrcpSkipNext(87));
        assertFalse(Y1InputKeys.isAvrcpSkipNext(22));
    }

    @Test
    public void avrcpTrackInfoSchema() {
        AvrcpTrackInfoWriter.selfCheckSchema();
    }
}
