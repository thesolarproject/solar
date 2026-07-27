package com.solar.launcher.overlay;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class OverlayMenuClientTest {
    @Test
    public void testStartShellException() {
        Context mockContext = mock(Context.class);
        doThrow(new SecurityException("Mock exception")).when(mockContext).startService(any(Intent.class));
        boolean result = OverlayMenuClient.showPowerQuickMenu(mockContext);
        assertFalse("showPowerQuickMenu should return false when startService throws", result);
    }
}
