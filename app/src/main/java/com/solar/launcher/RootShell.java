package com.solar.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Minimal root shell helper for UMS scripts (su 0 / su -c). */
public final class RootShell {
    private RootShell() {}

    public static boolean run(String command) {
        if (command == null || command.length() == 0) return false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", command});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            try {
                p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                return p.waitFor() == 0;
            } catch (Exception e2) {
                return false;
            }
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (Exception ignored) {}
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    public static String runCapture(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", command});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }
}
