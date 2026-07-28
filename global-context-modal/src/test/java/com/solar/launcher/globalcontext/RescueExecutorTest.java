package com.solar.launcher.globalcontext;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import com.solar.input.policy.GlobalInputPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;

public class RescueExecutorTest {

    @Before
    public void setup() throws Exception {
        resetExecAt();
    }

    @After
    public void teardown() throws Exception {
        resetExecAt();
    }

    private void resetExecAt() throws Exception {
        Field field = RescueExecutor.class.getDeclaredField("lastExecAt");
        field.setAccessible(true);
        field.set(null, 0L);
    }

    @Test
    public void testRunShell() throws Exception {
        try (MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {
            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);

            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(0);

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    capturedArgs.add(invocation.getArgument(0));
                    return process;
                }
            });

            Method method = RescueExecutor.class.getDeclaredMethod("runShell", String.class);
            method.setAccessible(true);
            boolean result = (Boolean) method.invoke(null, "echo test");
            assertTrue("runShell should return true on success", result);

            assertEquals(1, capturedArgs.size());
            String[] args = capturedArgs.get(0);
            assertEquals("sh", args[0]);
            assertEquals("-c", args[1]);
            assertEquals("echo test", args[2]);
        }
    }

    @Test
    public void testRunShell_fails() throws Exception {
        try (MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {
            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);

            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(1);
            when(runtime.exec(any(String[].class))).thenReturn(process);

            Method method = RescueExecutor.class.getDeclaredMethod("runShell", String.class);
            method.setAccessible(true);
            boolean result = (Boolean) method.invoke(null, "echo test");
            assertFalse("runShell should return false on non-zero exit code", result);
        }
    }

    @Test
    public void testRunShell_throwsException() throws Exception {
        try (MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {
            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);

            when(runtime.exec(any(String[].class))).thenThrow(new java.io.IOException("Test Exception"));

            Method method = RescueExecutor.class.getDeclaredMethod("runShell", String.class);
            method.setAccessible(true);
            boolean result = (Boolean) method.invoke(null, "echo test");
            assertFalse("runShell should return false on exception", result);
        }
    }

    @Test
    public void testExecute_debouncesCalls() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(1); // Fail shell commands to proceed to APK fallback

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    capturedArgs.add(invocation.getArgument(0));
                    return process;
                }
            });

            Context mockContext = mock(Context.class);
            PackageManager mockPm = mock(PackageManager.class);
            when(mockContext.getPackageManager()).thenReturn(mockPm);
            Intent mockIntent = mock(Intent.class);
            when(mockPm.getLaunchIntentForPackage(GlobalInputPolicy.SOLAR_PKG)).thenReturn(mockIntent);

            // First execution
            RescueExecutor.execute(mockContext, "some.app");

            // Should call signalRestarting exactly once
            rescueStateMock.verify(CompanionRescueHoldState::signalRestarting, times(1));

            int callsBefore = capturedArgs.size();
            assertTrue(callsBefore > 0);

            // Second execution immediately after, should be debounced
            clock.when(SystemClock::uptimeMillis).thenReturn(3500L); // Only 500ms passed
            RescueExecutor.execute(mockContext, "some.app");

            // Should not call signalRestarting again
            rescueStateMock.verify(CompanionRescueHoldState::signalRestarting, times(1));

            int callsAfter = capturedArgs.size();
            assertEquals("Should be debounced", callsBefore, callsAfter);
        }
    }

    @Test
    public void testExecute_firstShellScriptSucceeds() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);

            Process successProcess = mock(Process.class);
            when(successProcess.waitFor()).thenReturn(0);
            Process failProcess = mock(Process.class);
            when(failProcess.waitFor()).thenReturn(1);

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    String[] args = invocation.getArgument(0);
                    capturedArgs.add(args);
                    if (args[2].equals("sh /system/etc/solar/solar-rescue-exec.sh")) {
                        return successProcess;
                    }
                    return failProcess;
                }
            });

            RescueExecutor.execute(null, null);

            // It should have returned early, not calling CompanionRescueHoldState.disarm()
            rescueStateMock.verify(CompanionRescueHoldState::disarm, times(0));

            // And only 1 shell command should have been run (the first one)
            assertEquals(1, capturedArgs.size());
        }
    }

    @Test
    public void testExecute_secondShellScriptSucceeds() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);

            Process successProcess = mock(Process.class);
            when(successProcess.waitFor()).thenReturn(0);
            Process failProcess = mock(Process.class);
            when(failProcess.waitFor()).thenReturn(1);

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    String[] args = invocation.getArgument(0);
                    capturedArgs.add(args);
                    if (args[2].equals("sh /system/xbin/solar-rescue-exec.sh")) {
                        return successProcess;
                    }
                    return failProcess;
                }
            });

            RescueExecutor.execute(null, null);

            // It should have returned early, not calling CompanionRescueHoldState.disarm()
            rescueStateMock.verify(CompanionRescueHoldState::disarm, times(0));

            // Should have run 2 shell commands
            assertEquals(2, capturedArgs.size());
        }
    }

    @Test
    public void testExecute_apkFallbackDisablesRockboxIfForeground() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(1); // Fail all commands

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    capturedArgs.add(invocation.getArgument(0));
                    return process;
                }
            });

            Context mockContext = mock(Context.class);
            PackageManager mockPm = mock(PackageManager.class);
            when(mockContext.getPackageManager()).thenReturn(mockPm);
            Intent mockIntent = mock(Intent.class);
            when(mockPm.getLaunchIntentForPackage(GlobalInputPolicy.SOLAR_PKG)).thenReturn(mockIntent);

            RescueExecutor.execute(mockContext, GlobalInputPolicy.ROCKBOX_PKG);

            boolean foundDisable = false;
            boolean foundForceStop = false;
            for (String[] cmdArgs : capturedArgs) {
                if (cmdArgs.length >= 3) {
                    String cmd = cmdArgs[2];
                    if (cmd.equals("pm disable " + GlobalInputPolicy.ROCKBOX_PKG)) {
                        foundDisable = true;
                    }
                    if (cmd.equals("am force-stop " + GlobalInputPolicy.SOLAR_PKG)) {
                        foundForceStop = true;
                    }
                }
            }
            assertTrue("Should disable rockbox if foreground", foundDisable);
            assertTrue("Should force stop solar", foundForceStop);

            verify(mockContext).startActivity(mockIntent);

            rescueStateMock.verify(CompanionRescueHoldState::disarm, times(1));
        }
    }

    @Test
    public void testExecute_apkFallbackDoesNotDisableRockboxIfNotForeground() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(1); // Fail all commands

            final List<String[]> capturedArgs = new ArrayList<>();
            when(runtime.exec(any(String[].class))).thenAnswer(new Answer<Process>() {
                @Override
                public Process answer(InvocationOnMock invocation) {
                    capturedArgs.add(invocation.getArgument(0));
                    return process;
                }
            });

            Context mockContext = mock(Context.class);
            PackageManager mockPm = mock(PackageManager.class);
            when(mockContext.getPackageManager()).thenReturn(mockPm);
            Intent mockIntent = mock(Intent.class);
            when(mockPm.getLaunchIntentForPackage(GlobalInputPolicy.SOLAR_PKG)).thenReturn(mockIntent);

            RescueExecutor.execute(mockContext, "some.other.app");

            boolean foundDisable = false;
            boolean foundForceStop = false;
            for (String[] cmdArgs : capturedArgs) {
                if (cmdArgs.length >= 3) {
                    String cmd = cmdArgs[2];
                    if (cmd.equals("pm disable " + GlobalInputPolicy.ROCKBOX_PKG)) {
                        foundDisable = true;
                    }
                    if (cmd.equals("am force-stop " + GlobalInputPolicy.SOLAR_PKG)) {
                        foundForceStop = true;
                    }
                }
            }
            assertFalse("Should not disable rockbox if not foreground", foundDisable);
            assertTrue("Should force stop solar", foundForceStop);

            verify(mockContext).startActivity(mockIntent);
            rescueStateMock.verify(CompanionRescueHoldState::disarm, times(1));
        }
    }

    @Test
    public void testExecute_apkFallbackWithoutContextDoesNothing() throws Exception {
        try (MockedStatic<SystemClock> clock = Mockito.mockStatic(SystemClock.class);
             MockedStatic<CompanionRescueHoldState> rescueStateMock = Mockito.mockStatic(CompanionRescueHoldState.class);
             MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {

            clock.when(SystemClock::uptimeMillis).thenReturn(3000L);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(1); // Fail shell commands
            when(runtime.exec(any(String[].class))).thenReturn(process);

            RescueExecutor.execute(null, GlobalInputPolicy.ROCKBOX_PKG);

            // Should still disarm
            rescueStateMock.verify(CompanionRescueHoldState::disarm, times(1));
        }
    }
}
