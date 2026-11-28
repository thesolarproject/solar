package com.solar.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code su} candidates that can actually be executed on this device.
 *
 * <p>Never pass a missing binary to {@code Runtime.exec}. On Android 4.2, when {@code execvp}
 * fails the forked child calls {@code exit()}, which runs this process's native destructors
 * (e.g. libchromium_net joining a thread that does not exist in the child). The child then
 * hangs forever, holding memory. Installs without {@code /system/bin/su} hit this every time
 * a root command returned non-zero.
 */
final class SuPaths {
    private static final String[] CANDIDATES = {"/system/xbin/su", "/system/bin/su", "su"};

    private SuPaths() {}

    /** Candidates in order of preference, without the ones that do not exist. May be empty. */
    static String[] available() {
        return filter(CANDIDATES, System.getenv("PATH"));
    }

    static String[] filter(String[] candidates, String searchPath) {
        List<String> out = new ArrayList<String>(candidates.length);
        for (String candidate : candidates) {
            boolean found = candidate.indexOf('/') >= 0
                    ? isExecutableFile(new File(candidate))
                    : isOnPath(candidate, searchPath);
            if (found) out.add(candidate);
        }
        return out.toArray(new String[out.size()]);
    }

    private static boolean isOnPath(String name, String searchPath) {
        if (searchPath == null) return false;
        for (String dir : searchPath.split(File.pathSeparator)) {
            if (dir.length() > 0 && isExecutableFile(new File(dir, name))) return true;
        }
        return false;
    }

    private static boolean isExecutableFile(File file) {
        return file.isFile() && file.canExecute();
    }
}
