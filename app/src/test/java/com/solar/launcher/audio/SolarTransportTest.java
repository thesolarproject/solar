package com.solar.launcher.audio;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.solar.launcher.SolarApplication;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Host checks for SolarTransport intent flags (no Robolectric).
 * Layman: menus know immediately if play/pause was pressed, before audio thread responds.
 */
public class SolarTransportTest {

    @Before
    public void setUp() throws Exception {
        Application mockApp = Mockito.mock(Application.class);
        Context mockAppContext = Mockito.mock(Context.class);
        Mockito.when(mockApp.getApplicationContext()).thenReturn(mockAppContext);

        java.lang.reflect.Field field = SolarApplication.class.getDeclaredField("sApp");
        field.setAccessible(true);
        field.set(null, mockApp);
    }

    @After
    public void tearDown() {
        SolarTransport tx = SolarTransport.get();
        if (tx != null) {
            tx.shutdown();
        }
    }

    @Test
    public void get_returnsSingleton() {
        SolarTransport tx1 = SolarTransport.get();
        SolarTransport tx2 = SolarTransport.get();
        assertNotNull(tx1);
        assertSame(tx1, tx2);
    }

    @Test
    public void playFile_setsIntentSynchronously() {
        SolarTransport tx = SolarTransport.get();
        File fakeFile = new File("/fake/music.mp3");

        tx.playFile(fakeFile, 0, false, true);

        assertTrue("ownsPlayback should be true immediately after playFile", tx.ownsPlayback());
        assertTrue("isPlaying should be true immediately after playFile with autoStart", tx.isPlaying());
        assertFalse("layerMode should be false", tx.isLayerMode());
    }

    @Test
    public void playUrl_setsIntentSynchronously() {
        SolarTransport tx = SolarTransport.get();

        tx.playUrl("http://fake.stream", 0, true);

        assertTrue("ownsPlayback should be true immediately after playUrl", tx.ownsPlayback());
        assertTrue("isPlaying should be true immediately after playUrl with autoStart", tx.isPlaying());
        assertFalse("layerMode should be false", tx.isLayerMode());
    }

    @Test
    public void pause_setsIntentSynchronously() {
        SolarTransport tx = SolarTransport.get();

        tx.playFile(new File("/fake/music.mp3"), 0, false, true);
        assertTrue(tx.isPlaying());

        tx.pause();
        assertFalse("isPlaying should be false immediately after pause", tx.isPlaying());
        assertTrue("ownsPlayback should remain true after pause", tx.ownsPlayback());
    }

    @Test
    public void resume_setsIntentSynchronously() {
        SolarTransport tx = SolarTransport.get();

        tx.playFile(new File("/fake/music.mp3"), 0, false, false);
        assertFalse(tx.isPlaying());

        tx.resume();
        assertTrue("isPlaying should be true immediately after resume", tx.isPlaying());
        assertTrue("ownsPlayback should remain true", tx.ownsPlayback());
    }

    @Test
    public void stop_dropsOwnershipSynchronously() {
        SolarTransport tx = SolarTransport.get();

        tx.playFile(new File("/fake/music.mp3"), 0, false, true);
        assertTrue(tx.isPlaying());

        tx.stop();
        assertFalse("isPlaying should be false immediately after stop", tx.isPlaying());
        assertFalse("ownsPlayback should be false immediately after stop", tx.ownsPlayback());
        assertFalse("layerMode should be false", tx.isLayerMode());
    }

    @Test
    public void playLayers_setsIntentSynchronously() {
        SolarTransport tx = SolarTransport.get();
        File vocals = new File("/fake/vocals.mp3");
        File instr = new File("/fake/instr.mp3");

        tx.playLayers(vocals, instr, 1f, 1f, 0, true);

        assertTrue("ownsPlayback should be true immediately after playLayers", tx.ownsPlayback());
        assertTrue("isPlaying should be true immediately after playLayers with autoStart", tx.isPlaying());
    }

    @Test
    public void shutdown_clearsSingletonInstance() {
        SolarTransport tx1 = SolarTransport.get();
        tx1.shutdown();

        SolarTransport tx2 = SolarTransport.get();
        assertNotSame("shutdown should clear the instance, get should return a new one", tx1, tx2);
    }
}
