package com.solar.launcher.tlsproxy;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Root shell helper for the TLS proxy service (port of Wolfius {@code ShellUtils}, GPLv3).
 * Used for the iptables DNAT rules and system-trust-store CA install.
 */
public final class ShellUtils {
    private static final String TAG = "ShellUtils";

    static boolean checkRoot() {
        File binSu = new File("/system/bin/su");
        File xbinSu = new File("/system/xbin/su");
        if (!binSu.exists() && !xbinSu.exists()) {
            return false;
        }

        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            int val = process.waitFor();
            return (val == 0);
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }

    public static boolean executeRoot(String commands) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "STDERR");
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "STDOUT");
            errorGobbler.start();
            outputGobbler.start();
            os.writeBytes(commands);
            os.writeBytes("exit\n");
            os.flush();
            int val = process.waitFor();
            errorGobbler.join(1000);
            outputGobbler.join(1000);
            return (val == 0);
        } catch (Exception e) {
            Log.e(TAG, "Root execution failed.", e);
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }

    private static class StreamGobbler extends Thread {
        private final InputStream is;
        private final String type;

        StreamGobbler(InputStream is, String type) {
            this.is = is;
            this.type = type;
        }

        @Override
        public void run() {
            try {
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr, 1024);
                String line;
                while ((line = br.readLine()) != null) {
                    Log.d(TAG, type + "> " + line);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error consuming stream " + type, e);
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }
}
