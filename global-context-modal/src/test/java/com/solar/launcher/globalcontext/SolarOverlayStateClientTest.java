package com.solar.launcher.globalcontext;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.solar.launcher.ISolarOverlayState;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

public class SolarOverlayStateClientTest {

    private Context mockApp;
    private PackageManager mockPm;

    @Before
    public void setup() throws Exception {
        mockApp = Mockito.mock(Context.class);
        mockPm = Mockito.mock(PackageManager.class);
        Mockito.when(mockApp.getApplicationContext()).thenReturn(mockApp);
        Mockito.when(mockApp.getPackageManager()).thenReturn(mockPm);

        // Reset sInstance using reflection
        java.lang.reflect.Field instance = SolarOverlayStateClient.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private void mockSolarInstalled(boolean installed) throws Exception {
        if (installed) {
            Mockito.when(mockPm.getApplicationInfo(Mockito.eq("com.solar.launcher"), Mockito.anyInt()))
                   .thenReturn(new ApplicationInfo());
        } else {
            Mockito.when(mockPm.getApplicationInfo(Mockito.eq("com.solar.launcher"), Mockito.anyInt()))
                   .thenThrow(new PackageManager.NameNotFoundException());
        }
    }

    @Test
    public void testNullApp() {
        assertNull(SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(null));
    }

    @Test
    public void testSolarNotInstalled() throws Exception {
        mockSolarInstalled(false);
        assertNull(SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp));
    }

    @Test
    public void testBindSuccess() throws Exception {
        mockSolarInstalled(true);

        Bundle expectedBundle = Mockito.mock(Bundle.class);
        ISolarOverlayState mockBinder = Mockito.mock(ISolarOverlayState.class);
        Mockito.when(mockBinder.getPowerMenuSnapshot()).thenReturn(expectedBundle);

        IBinder mockIBinder = Mockito.mock(IBinder.class);
        Mockito.when(mockIBinder.queryLocalInterface(Mockito.anyString())).thenReturn(mockBinder);

        Mockito.when(mockApp.bindService(Mockito.any(Intent.class), Mockito.any(ServiceConnection.class), Mockito.anyInt()))
               .thenAnswer(invocation -> {
                   ServiceConnection conn = invocation.getArgument(1);
                   conn.onServiceConnected(new ComponentName("com.solar.launcher", "StateService"), mockIBinder);
                   return true;
               });

        Bundle result = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertEquals(expectedBundle, result);

        // Test hot path (binder already connected)
        Bundle hotResult = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertEquals(expectedBundle, hotResult);
    }

    @Test
    public void testBindMiss() throws Exception {
        mockSolarInstalled(true);

        Mockito.when(mockApp.bindService(Mockito.any(Intent.class), Mockito.any(ServiceConnection.class), Mockito.anyInt()))
               .thenReturn(false);

        Bundle result = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertNull(result);
    }

    @Test
    public void testBindThrowsException() throws Exception {
        mockSolarInstalled(true);

        Mockito.when(mockApp.bindService(Mockito.any(Intent.class), Mockito.any(ServiceConnection.class), Mockito.anyInt()))
               .thenThrow(new SecurityException("Not allowed"));

        Bundle result = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertNull(result);
    }

    @Test
    public void testRemoteExceptionOnGetPowerMenuSnapshot() throws Exception {
        mockSolarInstalled(true);

        ISolarOverlayState mockBinder = Mockito.mock(ISolarOverlayState.class);
        Mockito.when(mockBinder.getPowerMenuSnapshot()).thenThrow(new RemoteException());

        IBinder mockIBinder = Mockito.mock(IBinder.class);
        Mockito.when(mockIBinder.queryLocalInterface(Mockito.anyString())).thenReturn(mockBinder);

        Mockito.when(mockApp.bindService(Mockito.any(Intent.class), Mockito.any(ServiceConnection.class), Mockito.anyInt()))
               .thenAnswer(invocation -> {
                   ServiceConnection conn = invocation.getArgument(1);
                   conn.onServiceConnected(new ComponentName("com.solar.launcher", "StateService"), mockIBinder);
                   return true;
               });

        Bundle result = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertNull(result);
    }

    @Test
    public void testRemoteExceptionOnHotGetPowerMenuSnapshot() throws Exception {
        mockSolarInstalled(true);

        Bundle expectedBundle = Mockito.mock(Bundle.class);
        ISolarOverlayState mockBinder = Mockito.mock(ISolarOverlayState.class);
        // First call succeeds, second call throws
        Mockito.when(mockBinder.getPowerMenuSnapshot())
               .thenReturn(expectedBundle)
               .thenThrow(new RemoteException());

        IBinder mockIBinder = Mockito.mock(IBinder.class);
        Mockito.when(mockIBinder.queryLocalInterface(Mockito.anyString())).thenReturn(mockBinder);

        Mockito.when(mockApp.bindService(Mockito.any(Intent.class), Mockito.any(ServiceConnection.class), Mockito.anyInt()))
               .thenAnswer(invocation -> {
                   ServiceConnection conn = invocation.getArgument(1);
                   conn.onServiceConnected(new ComponentName("com.solar.launcher", "StateService"), mockIBinder);
                   return true;
               });

        // Cold bind succeeds
        Bundle result = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertEquals(expectedBundle, result);

        // Hot fetch throws RemoteException, returns null
        Bundle hotResult = SolarOverlayStateClient.get().fetchPowerSnapshotKeepWarm(mockApp);
        assertNull(hotResult);
    }
}
