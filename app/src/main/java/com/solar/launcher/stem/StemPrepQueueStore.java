package com.solar.launcher.stem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Small durable FIFO for pending stem preparation paths.
 *
 * The active extraction remains single-threaded in MainActivity, while this file
 * makes queued work survive Activity recreation or process death. Absolute paths
 * are retained while storage is temporarily unavailable and retried after resume.
 */
public final class StemPrepQueueStore {
    private static final String ROOT = "tracks";
    private StemPrepQueueStore() {}

    public static List<File> load(File stateFile) {
        ArrayList<File> out = new ArrayList<File>();
        if (stateFile == null || !stateFile.isFile()) return out;
        try {
            StringBuilder raw = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(stateFile));
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
            reader.close();
            JSONArray tracks = new JSONObject(raw.toString()).optJSONArray(ROOT);
            if (tracks == null) return out;
            for (int i = 0; i < tracks.length(); i++) {
                String path = tracks.optString(i, "").trim();
                File file = path.length() > 0 ? new File(path) : null;
                // Keep temporarily unmounted paths in FIFO order. The worker defers them
                // instead of deleting work merely because storage is currently unavailable.
                if (file != null) addUnique(out, file);
            }
        } catch (Exception ignored) {
            // A corrupt queue must never block launcher startup.
        }
        return out;
    }

    public static void save(File stateFile, List<File> tracks) {
        if (stateFile == null || tracks == null) return;
        File parent = stateFile.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        File temporary = new File(stateFile.getAbsolutePath() + ".tmp");
        try {
            JSONArray values = new JSONArray();
            for (int i = 0; i < tracks.size(); i++) {
                File track = tracks.get(i);
                if (track != null) values.put(track.getAbsolutePath());
            }
            JSONObject root = new JSONObject();
            root.put(ROOT, values);
            BufferedWriter writer = new BufferedWriter(new FileWriter(temporary));
            writer.write(root.toString());
            writer.close();
            File backup = new File(stateFile.getAbsolutePath() + ".bak");
            if (backup.exists()) backup.delete();
            boolean movedOld = !stateFile.exists() || stateFile.renameTo(backup);
            if (!movedOld) {
                temporary.delete();
                return;
            }
            if (temporary.renameTo(stateFile)) {
                if (backup.exists()) backup.delete();
            } else {
                // Restore the previous queue if replacement failed.
                if (stateFile.exists()) stateFile.delete();
                if (backup.exists()) backup.renameTo(stateFile);
                temporary.delete();
            }
        } catch (Exception ignored) {
            temporary.delete();
        }
    }

    private static void addUnique(List<File> out, File file) {
        String path = file.getAbsolutePath();
        for (int i = 0; i < out.size(); i++) {
            if (path.equals(out.get(i).getAbsolutePath())) return;
        }
        out.add(file);
    }
}
