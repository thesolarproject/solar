package com.solar.launcher.globalcontext;

import android.os.Build;
import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Before;
import org.junit.After;

public class CompanionDeviceFeaturesTest {

    private String originalModel;
    private String originalDevice;
    private String originalBoard;

    @Before
    public void setup() throws Exception {
        originalModel = Build.MODEL;
        originalDevice = Build.DEVICE;
        originalBoard = Build.BOARD;
    }

    @After
    public void teardown() throws Exception {
        setBuildField("MODEL", originalModel);
        setBuildField("DEVICE", originalDevice);
        setBuildField("BOARD", originalBoard);
    }

    @Test
    public void testIsY2_ModelY2() throws Exception {
        setBuildField("MODEL", "Some y2 phone");
        assertTrue(CompanionDeviceFeatures.isY2());
        assertFalse(CompanionDeviceFeatures.isY1());
    }

    @Test
    public void testIsY2_DeviceY2() throws Exception {
        setBuildField("MODEL", "Other");
        setBuildField("DEVICE", "some-y2-device");
        assertTrue(CompanionDeviceFeatures.isY2());
        assertFalse(CompanionDeviceFeatures.isY1());
    }

    @Test
    public void testIsY2_BoardMt6582() throws Exception {
        setBuildField("MODEL", "Other");
        setBuildField("DEVICE", "Other");
        setBuildField("BOARD", "MT6582-board");
        assertTrue(CompanionDeviceFeatures.isY2());
        assertFalse(CompanionDeviceFeatures.isY1());
    }

    @Test
    public void testIsY1() throws Exception {
        setBuildField("MODEL", "y1-model");
        setBuildField("DEVICE", "y1-device");
        setBuildField("BOARD", "y1-board");
        assertFalse(CompanionDeviceFeatures.isY2());
        assertTrue(CompanionDeviceFeatures.isY1());
    }

    private void setBuildField(String fieldName, String value) throws Exception {
        Field field = Build.class.getField(fieldName);
        field.setAccessible(true);
        // Remove 'final' modifier using reflection via setAccessible on modifiers
        // Note: For Java 12+ it's blocked, but older android / host JVMs might allow it
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        } catch (NoSuchFieldException e) {
            // JVM 12+ prevents modifiers access without add-opens
        }

        try {
            field.set(null, value);
        } catch (IllegalAccessException e) {
            // Using sun.misc.Unsafe to bypass final
            try {
                Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);

                java.lang.reflect.Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
                java.lang.reflect.Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
                java.lang.reflect.Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);

                Object base = staticFieldBase.invoke(unsafe, field);
                long offset = (long) staticFieldOffset.invoke(unsafe, field);
                putObject.invoke(unsafe, base, offset, value);
            } catch (Exception ex) {
                throw new RuntimeException("Could not set build field " + fieldName, ex);
            }
        }
    }
}
