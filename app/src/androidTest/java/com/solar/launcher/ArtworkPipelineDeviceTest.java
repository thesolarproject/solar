package com.solar.launcher;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solar.launcher.flow.AlbumBackdropCache;
import com.solar.launcher.flow.ArtworkBitmapPool;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArtworkPipelineDeviceTest {
    @Test public void exactDecodeLeaseAndBackdropCacheRoundTrip() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File sourceFile = new File(context.getCacheDir(), "artwork-pool-test.jpg");
        Bitmap source = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        source.eraseColor(0xff336699);
        FileOutputStream out = new FileOutputStream(sourceFile);
        source.compress(Bitmap.CompressFormat.JPEG, 90, out);
        out.close();

        ArtworkBitmapPool.Lease first = ArtworkBitmapPool.decodeExact(sourceFile, 240, 240);
        assertNotNull(first);
        assertEquals(240, first.bitmap().getWidth());
        first.release();

        File backdropDir = AlbumBackdropCache.cacheDir(context);
        String key = "device-test-" + System.nanoTime();
        AlbumBackdropCache.put(backdropDir, key, source);
        Bitmap backdrop = AlbumBackdropCache.get(backdropDir, key);
        assertNotNull(backdrop);
        assertEquals(AlbumBackdropCache.SIZE_PX, backdrop.getWidth());
        assertEquals(AlbumBackdropCache.SIZE_PX, backdrop.getHeight());

        backdrop.recycle();
        source.recycle();
        sourceFile.delete();
        File cached = AlbumBackdropCache.fileForKey(backdropDir, key);
        if (cached != null) cached.delete();
    }
}
