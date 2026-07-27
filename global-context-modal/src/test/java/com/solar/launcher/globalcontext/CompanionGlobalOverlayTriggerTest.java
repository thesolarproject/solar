package com.solar.launcher.globalcontext;

import android.content.Context;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;
import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class CompanionGlobalOverlayTriggerTest {

    @Before
    public void setUp() throws Exception {
        resetStartedFlag();
    }

    @After
    public void tearDown() throws Exception {
        resetStartedFlag();
    }

    private void resetStartedFlag() throws Exception {
        Field startedField = CompanionGlobalOverlayTrigger.class.getDeclaredField("started");
        startedField.setAccessible(true);
        startedField.set(null, false);
    }

    @Test
    public void testEnsureStartedThreadSpawnAndMocking() throws Exception {
        Context ctx = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        when(ctx.getPackageManager()).thenReturn(pm);
        when(ctx.getPackageCodePath()).thenReturn("/mock/app.apk");

        // Throw NameNotFoundException to fallback to getPackageCodePath()
        // This avoids instantiating ApplicationInfo which could throw a Stub! exception
        when(pm.getApplicationInfo(anyString(), anyInt())).thenThrow(new PackageManager.NameNotFoundException());

        int initialThreadCount = getThreadCountByName("CompanionOverlayTrigBoot");

        CompanionGlobalOverlayTrigger.ensureStarted(ctx);

        Field startedField = CompanionGlobalOverlayTrigger.class.getDeclaredField("started");
        startedField.setAccessible(true);
        boolean started = (boolean) startedField.get(null);
        assertTrue("started flag should be set to true", started);

        // Explicitly verify background thread is spawned
        long deadline = System.currentTimeMillis() + 5000;
        int currentCount = initialThreadCount;
        while (System.currentTimeMillis() < deadline) {
            currentCount = getThreadCountByName("CompanionOverlayTrigBoot");
            if (currentCount > initialThreadCount) {
                break;
            }
            Thread.sleep(10);
        }
        assertTrue("CompanionOverlayTrigBoot thread should be spawned", currentCount > initialThreadCount);

        verify(ctx, times(2)).getPackageManager(); // Once for companion, once for solar
        verify(ctx, times(2)).getPackageCodePath(); // Fetches fallback path twice
        verify(pm, times(1)).getApplicationInfo(eq("com.solar.launcher.globalcontext"), eq(0));

        // Reset mocks to test idempotency
        reset(ctx);
        reset(pm);

        // Second call should return immediately due to idempotency
        CompanionGlobalOverlayTrigger.ensureStarted(ctx);

        // Should not interact with Context/PackageManager again
        verifyNoInteractions(ctx);
        verifyNoInteractions(pm);
    }

    private int getThreadCountByName(String name) {
        int count = 0;
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        ThreadGroup topGroup = group;
        while (group != null) {
            topGroup = group;
            group = group.getParent();
        }

        int estimatedSize = topGroup.activeCount() * 2;
        Thread[] slackList = new Thread[estimatedSize];
        int actualSize = topGroup.enumerate(slackList);
        for (int i = 0; i < actualSize; i++) {
            if (slackList[i] != null && name.equals(slackList[i].getName())) {
                count++;
            }
        }
        return count;
    }
}
