package com.solar.launcher.globalcontext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SysPropHelperTest {

    private static Map<String, String> mockProps = new HashMap<>();
    private static boolean setCalled = false;
    private static boolean simulateSetFailure = false;

    public static String mockGet(String key, String def) {
        return mockProps.containsKey(key) ? mockProps.get(key) : def;
    }

    public static void mockSet(String key, String val) {
        setCalled = true;
        if (!simulateSetFailure) {
            mockProps.put(key, val);
        }
    }

    private Method originalGet;
    private Method originalSet;

    @Before
    public void setup() throws Exception {
        mockProps.clear();
        setCalled = false;
        simulateSetFailure = false;

        Field getField = SysPropHelper.class.getDeclaredField("sGetMethod");
        getField.setAccessible(true);
        originalGet = (Method) getField.get(null);
        getField.set(null, SysPropHelperTest.class.getMethod("mockGet", String.class, String.class));

        Field setField = SysPropHelper.class.getDeclaredField("sSetMethod");
        setField.setAccessible(true);
        originalSet = (Method) setField.get(null);
        setField.set(null, SysPropHelperTest.class.getMethod("mockSet", String.class, String.class));

        // Interrupt any lingering SolarSysPropSet threads
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("SolarSysPropSet".equals(t.getName())) {
                t.interrupt();
            }
        }
        Thread.sleep(100);
    }

    @After
    public void teardown() throws Exception {
        Field getField = SysPropHelper.class.getDeclaredField("sGetMethod");
        getField.setAccessible(true);
        getField.set(null, originalGet);

        Field setField = SysPropHelper.class.getDeclaredField("sSetMethod");
        setField.setAccessible(true);
        setField.set(null, originalSet);

        // Let background threads finish before starting the next test
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("SolarSysPropSet".equals(t.getName())) {
                t.interrupt();
            }
        }
        Thread.sleep(100);
    }

    private boolean isSysPropThreadRunning() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("SolarSysPropSet".equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void set_nullKey_doesNothing() {
        SysPropHelper.set(null, "value");
        assertFalse("set should not be called for null key", setCalled);
        assertFalse("No thread should be spawned", isSysPropThreadRunning());
    }

    @Test
    public void set_sameValue_doesNothing() {
        mockProps.put("key1", "val1");
        SysPropHelper.set("key1", "val1");
        assertFalse("set should not be called when value is the same", setCalled);
        assertFalse("No thread should be spawned", isSysPropThreadRunning());
    }

    @Test
    public void set_newValue_systemPropertiesSetSucceeds_noThreadSpawned() {
        SysPropHelper.set("key2", "val2");
        assertTrue("set should be called", setCalled);
        assertEquals("val2", mockProps.get("key2"));
        assertFalse("No thread should be spawned because trySystemPropertiesSet succeeded", isSysPropThreadRunning());
    }

    @Test
    public void set_newValue_systemPropertiesSetFails_threadSpawned() throws InterruptedException {
        simulateSetFailure = true;

        SysPropHelper.set("key3", "val3");

        assertTrue("set should be called", setCalled);

        // Wait a bit to let the thread start
        Thread.sleep(50);

        boolean threadFound = isSysPropThreadRunning();
        assertTrue("Thread 'SolarSysPropSet' should be spawned", threadFound);
    }
}
