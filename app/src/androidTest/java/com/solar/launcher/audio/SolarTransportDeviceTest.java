package com.solar.launcher.audio;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SolarTransportDeviceTest {

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
        SolarTransport tx = SolarTransport.get();
        if (tx != null) {
            tx.shutdown();
        }
    }

    @Test
    public void testSingleton() {
        SolarTransport tx1 = SolarTransport.get();
        SolarTransport tx2 = SolarTransport.get();
        assertNotNull(tx1);
        assertSame(tx1, tx2);
    }
}
