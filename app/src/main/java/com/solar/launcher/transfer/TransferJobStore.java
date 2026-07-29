package com.solar.launcher.transfer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small durable transfer journal shared by acquisition providers.
 *
 * <p>It deliberately does not own provider sockets. Existing Soulseek, podcast, HTTP, import, and
 * conversion implementations remain responsible for their work while reporting the same tested
 * state model here. The journal survives process death and lets the Downloads UI recover or retry
 * an operation without inventing a second worker.</p>
 */
public final class TransferJobStore {
    public static final String DETAIL_PAUSED_AFTER_RESTART = "Paused after restart";

    public enum Provider {
        SOULSEEK, PODCAST, DIRECT, IMPORT, CONVERSION, DEEZER
    }

    public enum State {
        QUEUED,
        CONNECTING,
        DOWNLOADING,
        PAUSED,
        RETRYING,
        VERIFYING,
        CONVERTING,
        INDEXING,
        COMPLETED,
        FAILED,
        CANCELED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELED;
        }

        public boolean isRunning() {
            return this == CONNECTING || this == DOWNLOADING || this == RETRYING
                    || this == VERIFYING || this == CONVERTING || this == INDEXING;
        }
    }

    public static final class Job {
        public final String id;
        public final Provider provider;
        public final String title;
        /** Peer/show/provider name. Never a password or token. */
        public final String sourceName;
        /** Provider-owned resume reference; UI must not render this value. */
        public final String remoteId;
        public final String targetPath;
        public final State state;
        public final String detail;
        public final String error;
        public final long doneBytes;
        public final long totalBytes;
        public final long speedBytesPerSecond;
        public final long etaSeconds;
        public final int attempt;
        public final int maxAttempts;
        public final boolean wifiOnly;
        public final long createdAtMs;
        public final long updatedAtMs;

        private Job(String id, Provider provider, String title, String sourceName,
                String remoteId, String targetPath, State state, String detail, String error,
                long doneBytes, long totalBytes, long speedBytesPerSecond, long etaSeconds,
                int attempt, int maxAttempts, boolean wifiOnly, long createdAtMs,
                long updatedAtMs) {
            this.id = id;
            this.provider = provider;
            this.title = title;
            this.sourceName = sourceName;
            this.remoteId = remoteId;
            this.targetPath = targetPath;
            this.state = state;
            this.detail = detail;
            this.error = error;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSecond = speedBytesPerSecond;
            this.etaSeconds = etaSeconds;
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.wifiOnly = wifiOnly;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
        }

        public int percent() {
            if (totalBytes <= 0) return -1;
            if (doneBytes <= 0) return 0;
            if (doneBytes >= totalBytes) return 100;
            if (doneBytes > Long.MAX_VALUE / 100L) {
                return (int) Math.max(0d, Math.min(99d,
                        ((double) doneBytes * 100d) / (double) totalBytes));
            }
            return (int) Math.max(0L, Math.min(99L, doneBytes * 100L / totalBytes));
        }

        private JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("id", id);
            value.put("provider", provider.name());
            value.put("title", title);
            value.put("source_name", sourceName);
            value.put("remote_id", remoteId);
            value.put("target_path", targetPath);
            value.put("state", state.name());
            value.put("detail", detail);
            value.put("error", error);
            value.put("done_bytes", doneBytes);
            value.put("total_bytes", totalBytes);
            value.put("speed_bps", speedBytesPerSecond);
            value.put("eta_seconds", etaSeconds);
            value.put("attempt", attempt);
            value.put("max_attempts", maxAttempts);
            value.put("wifi_only", wifiOnly);
            value.put("created_at_ms", createdAtMs);
            value.put("updated_at_ms", updatedAtMs);
            return value;
        }

        private static Job fromJson(JSONObject value) throws Exception {
            String id = required(value, "id", 160);
            Provider provider = Provider.valueOf(required(value, "provider", 32));
            State state = State.valueOf(required(value, "state", 32));
            long created = Math.max(0L, value.optLong("created_at_ms", 0L));
            long updated = Math.max(created, value.optLong("updated_at_ms", created));
            return new Job(
                    id,
                    provider,
                    limited(value.optString("title", ""), 300),
                    limited(value.optString("source_name", ""), 300),
                    limited(value.optString("remote_id", ""), 8192),
                    limited(value.optString("target_path", ""), 2048),
                    state,
                    limited(value.optString("detail", ""), 600),
                    limited(value.optString("error", ""), 1000),
                    Math.max(0L, value.optLong("done_bytes", 0L)),
                    Math.max(0L, value.optLong("total_bytes", 0L)),
                    Math.max(0L, value.optLong("speed_bps", 0L)),
                    Math.max(-1L, value.optLong("eta_seconds", -1L)),
                    Math.max(0, value.optInt("attempt", 0)),
                    Math.max(1, value.optInt("max_attempts", 3)),
                    value.optBoolean("wifi_only", true),
                    created,
                    updated);
        }
    }

    public static final class Aggregate {
        public final int activeCount;
        public final int pausedCount;
        public final int failedCount;
        public final long doneBytes;
        public final long totalBytes;
        public final long speedBytesPerSecond;

        Aggregate(int activeCount, int pausedCount, int failedCount, long doneBytes,
                long totalBytes, long speedBytesPerSecond) {
            this.activeCount = activeCount;
            this.pausedCount = pausedCount;
            this.failedCount = failedCount;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSecond = speedBytesPerSecond;
        }
    }

    interface Clock {
        long nowMs();
    }

    private static final int SCHEMA = 1;
    private static final int MAX_JOBS = 100;
    private static final long PROGRESS_PERSIST_MS = 1000L;
    private static final long PROGRESS_PERSIST_BYTES = 256L * 1024L;
    private static volatile TransferJobStore instance;

    private final File dataFile;
    private final File backupFile;
    private final File tempFile;
    private final Clock clock;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<String, Job>();
    private long lastPersistMs;
    private long bytesAtLastPersist;

    public static TransferJobStore get(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        TransferJobStore current = instance;
        if (current != null) return current;
        synchronized (TransferJobStore.class) {
            current = instance;
            if (current == null) {
                File file = new File(context.getApplicationContext().getFilesDir(),
                        "transfer-jobs-v1.json");
                current = new TransferJobStore(file, new Clock() {
                    @Override public long nowMs() {
                        return System.currentTimeMillis();
                    }
                }, true);
                instance = current;
            }
            return current;
        }
    }

    static TransferJobStore openForTest(File file, Clock clock, boolean recoverRunning) {
        return new TransferJobStore(file, clock, recoverRunning);
    }

    private TransferJobStore(File file, Clock clock, boolean recoverRunning) {
        if (file == null) throw new IllegalArgumentException("file");
        this.dataFile = file;
        this.backupFile = new File(file.getParentFile(), file.getName() + ".bak");
        this.tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        this.clock = clock;
        load();
        if (recoverRunning) recoverInterruptedJobs();
    }

    public synchronized Job create(Provider provider, String title, String sourceName,
            String remoteId, String targetPath, boolean wifiOnly, int maxAttempts) {
        if (provider == null) throw new IllegalArgumentException("provider");
        makeRoom();
        long now = clock.nowMs();
        String id = provider.name().toLowerCase(Locale.US) + "_" + now + "_"
                + UUID.randomUUID().toString();
        Job job = new Job(
                id,
                provider,
                limited(title, 300),
                limited(sourceName, 300),
                limited(remoteId, 8192),
                limited(targetPath, 2048),
                State.QUEUED,
                "Queued",
                "",
                0L,
                0L,
                0L,
                -1L,
                0,
                Math.max(1, maxAttempts),
                wifiOnly,
                now,
                now);
        jobs.put(id, job);
        persistOrThrow();
        return job;
    }

    public synchronized Job get(String id) {
        return id != null ? jobs.get(id) : null;
    }

    public synchronized List<Job> list() {
        List<Job> result = new ArrayList<Job>(jobs.values());
        Collections.sort(result, new Comparator<Job>() {
            @Override public int compare(Job left, Job right) {
                if (left.updatedAtMs == right.updatedAtMs) {
                    return right.id.compareTo(left.id);
                }
                return left.updatedAtMs < right.updatedAtMs ? 1 : -1;
            }
        });
        return result;
    }

    public synchronized Job transition(String id, State next, String detail, String error) {
        Job current = requireJob(id);
        if (next == null) throw new IllegalArgumentException("state");
        if (current.state != next && !canTransition(current.state, next)) {
            throw new IllegalStateException("Invalid transfer transition "
                    + current.state + " -> " + next);
        }
        long now = clock.nowMs();
        int attempt = current.attempt;
        if (next == State.CONNECTING
                && current.state != State.CONNECTING
                && current.state != State.DOWNLOADING) {
            attempt++;
        }
        Job changed = copy(current, next, detail, error, current.doneBytes, current.totalBytes,
                next.isRunning() ? current.speedBytesPerSecond : 0L,
                next.isRunning() ? current.etaSeconds : -1L, attempt, now);
        jobs.put(id, changed);
        persistOrThrow();
        return changed;
    }

    public synchronized Job progress(String id, long doneBytes, long totalBytes) {
        Job current = requireJob(id);
        if (current.state.isTerminal()) {
            throw new IllegalStateException("Cannot update a finished transfer");
        }
        long now = clock.nowMs();
        long done = Math.max(0L, doneBytes);
        long total = Math.max(0L, totalBytes);
        if (total > 0 && done > total) done = total;
        long elapsed = Math.max(1L, now - current.updatedAtMs);
        long delta = Math.max(0L, done - current.doneBytes);
        long instant = delta > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE : delta * 1000L / elapsed;
        long speed = instant > 0
                ? (current.speedBytesPerSecond > 0
                        ? (current.speedBytesPerSecond * 3L + instant) / 4L : instant)
                : current.speedBytesPerSecond;
        long eta = total > done && speed > 0 ? (total - done) / speed : -1L;
        State state = current.state == State.QUEUED || current.state == State.CONNECTING
                || current.state == State.RETRYING ? State.DOWNLOADING : current.state;
        Job changed = copy(current, state, "Downloading", "", done, total, speed, eta,
                current.attempt, now);
        jobs.put(id, changed);

        boolean finishedBytes = total > 0 && done >= total;
        boolean timeDue = now - lastPersistMs >= PROGRESS_PERSIST_MS;
        boolean bytesDue = done >= bytesAtLastPersist
                && done - bytesAtLastPersist >= PROGRESS_PERSIST_BYTES;
        if (finishedBytes || timeDue || bytesDue) {
            persistOrThrow();
            lastPersistMs = now;
            bytesAtLastPersist = done;
        }
        return changed;
    }

    public synchronized Job retry(String id) {
        Job current = requireJob(id);
        if (current.attempt >= current.maxAttempts) {
            throw new IllegalStateException("Retry limit reached");
        }
        return transition(id, State.RETRYING, "Waiting to retry", "");
    }

    public synchronized void remove(String id) {
        Job current = requireJob(id);
        if (!current.state.isTerminal() && current.state != State.PAUSED) {
            throw new IllegalStateException("Pause or cancel the transfer before removing it");
        }
        jobs.remove(id);
        persistOrThrow();
    }

    public synchronized int clearFinished() {
        int removed = 0;
        List<String> ids = new ArrayList<String>(jobs.keySet());
        for (String id : ids) {
            Job job = jobs.get(id);
            if (job != null && job.state.isTerminal()) {
                jobs.remove(id);
                removed++;
            }
        }
        if (removed > 0) persistOrThrow();
        return removed;
    }

    public synchronized Aggregate aggregate() {
        int active = 0;
        int paused = 0;
        int failed = 0;
        long done = 0L;
        long total = 0L;
        long speed = 0L;
        for (Job job : jobs.values()) {
            if (job.state.isRunning() || job.state == State.QUEUED) active++;
            if (job.state == State.PAUSED) paused++;
            if (job.state == State.FAILED) failed++;
            if (job.state.isRunning()) {
                done = safeAggregateAdd(done, job.doneBytes);
                total = safeAggregateAdd(total, job.totalBytes);
                speed = safeAggregateAdd(speed, job.speedBytesPerSecond);
            }
        }
        return new Aggregate(active, paused, failed, done, total, speed);
    }

    public synchronized int recoverInterruptedJobs() {
        int changedCount = 0;
        long now = clock.nowMs();
        List<Job> snapshot = new ArrayList<Job>(jobs.values());
        for (Job job : snapshot) {
            if (!job.state.isRunning()) continue;
            Job recovered = copy(job, State.PAUSED, DETAIL_PAUSED_AFTER_RESTART,
                    "Solar stopped before this transfer finished", job.doneBytes, job.totalBytes,
                    0L, -1L, job.attempt, now);
            jobs.put(job.id, recovered);
            changedCount++;
        }
        if (changedCount > 0) persistOrThrow();
        return changedCount;
    }

    public static boolean canTransition(State from, State to) {
        if (from == null || to == null) return false;
        if (from == to) return true;
        switch (from) {
            case QUEUED:
                return to == State.CONNECTING || to == State.DOWNLOADING
                        || to == State.PAUSED || to == State.FAILED || to == State.CANCELED;
            case CONNECTING:
                return to == State.DOWNLOADING || to == State.RETRYING
                        || to == State.PAUSED || to == State.FAILED || to == State.CANCELED;
            case DOWNLOADING:
                return to == State.PAUSED || to == State.RETRYING || to == State.VERIFYING
                        || to == State.INDEXING || to == State.COMPLETED
                        || to == State.FAILED || to == State.CANCELED;
            case PAUSED:
                return to == State.QUEUED || to == State.CONNECTING || to == State.RETRYING
                        || to == State.CANCELED || to == State.FAILED;
            case RETRYING:
                return to == State.CONNECTING || to == State.DOWNLOADING
                        || to == State.PAUSED || to == State.FAILED || to == State.CANCELED;
            case VERIFYING:
                return to == State.CONVERTING || to == State.INDEXING
                        || to == State.COMPLETED || to == State.FAILED || to == State.CANCELED;
            case CONVERTING:
                return to == State.PAUSED || to == State.INDEXING
                        || to == State.COMPLETED || to == State.FAILED || to == State.CANCELED;
            case INDEXING:
                return to == State.COMPLETED || to == State.FAILED || to == State.CANCELED;
            case FAILED:
                return to == State.QUEUED || to == State.RETRYING || to == State.CANCELED;
            case COMPLETED:
            case CANCELED:
            default:
                return false;
        }
    }

    private Job requireJob(String id) {
        Job job = id != null ? jobs.get(id) : null;
        if (job == null) throw new IllegalArgumentException("Unknown transfer job");
        return job;
    }

    private void makeRoom() {
        if (jobs.size() < MAX_JOBS) return;
        List<Job> oldest = new ArrayList<Job>(jobs.values());
        Collections.sort(oldest, new Comparator<Job>() {
            @Override public int compare(Job left, Job right) {
                return left.updatedAtMs == right.updatedAtMs ? 0
                        : (left.updatedAtMs < right.updatedAtMs ? -1 : 1);
            }
        });
        for (Job job : oldest) {
            if (!job.state.isTerminal()) continue;
            jobs.remove(job.id);
            if (jobs.size() < MAX_JOBS) return;
        }
        throw new IllegalStateException("Transfer history is full of unfinished jobs");
    }

    private static Job copy(Job current, State state, String detail, String error,
            long done, long total, long speed, long eta, int attempt, long updated) {
        return new Job(
                current.id,
                current.provider,
                current.title,
                current.sourceName,
                current.remoteId,
                current.targetPath,
                state,
                limited(detail, 600),
                limited(error, 1000),
                Math.max(0L, done),
                Math.max(0L, total),
                Math.max(0L, speed),
                Math.max(-1L, eta),
                Math.max(0, attempt),
                current.maxAttempts,
                current.wifiOnly,
                current.createdAtMs,
                Math.max(current.createdAtMs, updated));
    }

    private void load() {
        jobs.clear();
        if (readInto(dataFile)) return;
        if (readInto(backupFile)) {
            try {
                persist();
            } catch (Exception ignored) {}
        }
    }

    private boolean readInto(File file) {
        if (file == null || !file.isFile()) return false;
        try {
            JSONObject root = new JSONObject(readUtf8(file));
            if (root.optInt("schema", -1) != SCHEMA) return false;
            JSONArray array = root.optJSONArray("jobs");
            if (array == null) return false;
            LinkedHashMap<String, Job> loaded = new LinkedHashMap<String, Job>();
            for (int i = 0; i < array.length() && loaded.size() < MAX_JOBS; i++) {
                try {
                    Job job = Job.fromJson(array.getJSONObject(i));
                    loaded.put(job.id, job);
                } catch (Exception ignored) {}
            }
            jobs.clear();
            jobs.putAll(loaded);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void persistOrThrow() {
        try {
            persist();
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist transfer state", e);
        }
    }

    private void persist() throws IOException {
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create transfer-state directory");
        }
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put("schema", SCHEMA);
            for (Job job : jobs.values()) array.put(job.toJson());
            root.put("jobs", array);
        } catch (Exception e) {
            throw new IOException("Cannot encode transfer state", e);
        }
        writeUtf8(tempFile, root.toString());

        if (backupFile.exists() && !backupFile.delete()) {
            tempFile.delete();
            throw new IOException("Cannot rotate transfer-state backup");
        }
        boolean hadCurrent = dataFile.exists();
        if (hadCurrent && !dataFile.renameTo(backupFile)) {
            tempFile.delete();
            throw new IOException("Cannot back up transfer state");
        }
        if (!tempFile.renameTo(dataFile)) {
            if (hadCurrent && backupFile.exists()) backupFile.renameTo(dataFile);
            tempFile.delete();
            throw new IOException("Cannot publish transfer state");
        }
    }

    private static String required(JSONObject value, String key, int limit) throws Exception {
        String result = limited(value.optString(key, ""), limit);
        if (result.length() == 0) throw new Exception("Missing " + key);
        return result;
    }

    private static String limited(String value, int limit) {
        String safe = value != null ? value : "";
        if (safe.length() <= limit) return safe;
        return safe.substring(0, limit);
    }

    private static long safeAggregateAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String readUtf8(File file) throws Exception {
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 65536L));
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 1024 * 1024) {
                    throw new IOException("Transfer-state file is too large");
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        FileOutputStream raw = new FileOutputStream(file);
        BufferedOutputStream out = new BufferedOutputStream(raw);
        try {
            out.write(value.getBytes("UTF-8"));
            out.flush();
            try { raw.getFD().sync(); } catch (Exception ignored) {}
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }
}
