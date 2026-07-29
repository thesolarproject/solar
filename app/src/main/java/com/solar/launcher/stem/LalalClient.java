package com.solar.launcher.stem;

import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Lalal.ai API v1 — one multistem (≤6 allowed ids) + on-device Melody premix.
 * Layman: cloud peels vocals/drums/bass + piano/guitars; leftover + those mix into Melody pad
 * so the Y1 only plays four streams.
 * Technical: multistem stem_list enum from OpenAPI MultistemSplitterPresetsV1 (not full
 * stem_separator list — synthesizer/strings/wind are invalid here → HTTP 422).
 * Residual track {@code no_multistem} folds into Melody. Premix → melody.wav.
 * Was: BATCH_A/B with synthesizer/strings/wind. Reversal: MULTISTEM_IDS only.
 * Docs: https://www.lalal.ai/api/v1/docs/
 * 2026-07-19
 */
public final class LalalClient {
    public static final String BASE = "https://www.lalal.ai";

    /** UI zone labels — Gen1 Stem Player four pads. */
    public static final String[] STEM_LABELS = { "Vocals", "Drums", "Bass", "Melody" };

    /** Required isolates — one MediaPlayer each (zones 0–2). */
    public static final String[] CORE_IDS = { "vocals", "drum", "bass" };

    /**
     * Multistem-allowed Melody parts (OpenAPI enum — guitars + piano only).
     * 2026-07-19
     */
    public static final String[] OTHER_IDS = {
            "piano", "electric_guitar", "acoustic_guitar"
    };

    /**
     * Requested isolates. Lalal returns the residual/back track for the Melody
     * pad, so requesting only the three core isolates avoids six simultaneous
     * downloads and duplicate Melody candidates on the Y1.
     */
    public static final String[] MULTISTEM_IDS = {
            "vocals", "drum", "bass"
    };

    /**
     * Multistem residual label ({@code type:back}) — source minus selected stems.
     * Captures synth/strings/wind/etc that multistem cannot name. Zone 3.
     * 2026-07-19
     */
    public static final String RESIDUAL_ID = "no_multistem";

    /**
     * Sidecar / stem_separator-only ids — NOT valid in multistem stem_list.
     * 2026-07-19
     */
    public static final String[] EXTRA_OTHER_IDS = {
            "synthesizer", "strings", "wind"
    };

    /** All Melody/Other file ids we accept on disk (API others + residual + sidecar extras). */
    public static final String[] ALL_OTHER_IDS = concat(
            OTHER_IDS, concat(new String[] { RESIDUAL_ID }, EXTRA_OTHER_IDS));

    /** @deprecated Prefer {@link #MULTISTEM_IDS}. */
    public static final String[] BATCH_A = MULTISTEM_IDS;

    /** @deprecated Multistem is one request now — empty sentinel for tests. */
    public static final String[] BATCH_B = new String[0];

    /** @deprecated Prefer MULTISTEM_IDS. */
    public static final String[] STEM_IDS = MULTISTEM_IDS;

    /** Cache layout — live multi Melody or experimental premix (path suffix). 2026-07-19 */
    public static final String CACHE_LAYOUT = "v6";

    /**
     * Older layout prefixes still scanned so upgrades keep local stems.
     * Was: only current CACHE_LAYOUT leaf. Reversal: drop LEGACY_CACHE_LAYOUTS loop.
     * 2026-07-19
     */
    public static final String[] LEGACY_CACHE_LAYOUTS = { "v5", "v4", "v3" };

    /** Sidecar in a stem leaf — basename (+ size) so remounts still find the folder. 2026-07-19 */
    public static final String TRACK_MARKER = ".solar_src";

    /**
     * Folder leaf for new publishes: {@code v6_live_…} keyed by basename+size (path-stable).
     * Layman: same song file keeps its stem folder even if the card path string changes.
     * Was: path|mtime|size hash (broke on remount / touch). Reversal: cacheKeyFor in leaf.
     * 2026-07-19
     */
    public static String cacheLeaf(File track, boolean premix) {
        return CACHE_LAYOUT + (premix ? "_premix_" : "_live_") + cacheKeyStable(track);
    }

    /**
     * Basename + length — survives path remount and mtime bumps.
     * 2026-07-19
     */
    public static String cacheKeyStable(File track) {
        if (track == null) return "unknown";
        String base = trackBaseName(track).toLowerCase();
        long sz = track.length();
        return Integer.toHexString((base + "|" + sz).hashCode());
    }

    /** Strip directory + extension for marker / stable key. 2026-07-19 */
    public static String trackBaseName(File track) {
        if (track == null) return "";
        String name = track.getName();
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    /**
     * All leaf names that may hold stems for this track+mode (current + legacy layouts + keys).
     * 2026-07-19
     */
    public static java.util.List<String> cacheLeafAliases(File track, boolean premix) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        String mode = premix ? "_premix_" : "_live_";
        String[] keys = new String[] {
                cacheKeyStable(track),
                cacheKeyFor(track)
        };
        String[] layouts = new String[1 + LEGACY_CACHE_LAYOUTS.length];
        layouts[0] = CACHE_LAYOUT;
        for (int i = 0; i < LEGACY_CACHE_LAYOUTS.length; i++) {
            layouts[i + 1] = LEGACY_CACHE_LAYOUTS[i];
        }
        for (int li = 0; li < layouts.length; li++) {
            for (int ki = 0; ki < keys.length; ki++) {
                String leaf = layouts[li] + mode + keys[ki];
                if (seen.contains(leaf)) continue;
                seen.add(leaf);
                out.add(leaf);
            }
        }
        return out;
    }

    /** Lalal multistem stem_list hard cap (API 422 if exceeded). */
    public static final int MULTISTEM_MAX = 6;

    private final String licenseKey;
    private final OkHttpClient client;
    private volatile java.util.concurrent.atomic.AtomicBoolean cancelled;

    public LalalClient(String licenseKey) {
        this.licenseKey = licenseKey != null ? licenseKey.trim() : "";
        TlsHelper.ensureSecurityProvider();
        // Allow every stem MP3 at once (≤6: core + other).
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequests(12);
        dispatcher.setMaxRequestsPerHost(6);
        this.client = TlsHelper.client().newBuilder()
                .dispatcher(dispatcher)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /** Wire host cancel flag so exit aborts poll/download. */
    public void setCancelled(java.util.concurrent.atomic.AtomicBoolean flag) {
        this.cancelled = flag;
    }

    private void throwIfCancelled() throws IOException {
        if (cancelled != null && cancelled.get()) {
            throw new IOException("Cancelled");
        }
    }

    public interface Progress {
        /** phase: upload|split|download|mix|publish|ready; detail optional human step. */
        void onProgress(String phase, int percent, String detail);
    }

    /**
     * One downloaded stem file mapped to a Stem Player zone (0..3).
     * Zone 3 may have many files (piano+guitars+…); mixer shares one gain.
     */
    public static final class StemFile {
        public final String id;
        public final String label;
        public final File file;
        /** 0=Vocals 1=Drums 2=Bass 3=Melody/Other. */
        public final int zone;

        public StemFile(String id, String label, File file, int zone) {
            this.id = id;
            this.label = label;
            this.file = file;
            this.zone = zone;
        }
    }

    /**
     * Upload → multistem → download on workDir → optional premix; play from work only.
     * Layman: peel the song into scratch stems and start playing — save later.
     * Technical: no {@link #publishStems} here; callers use {@link StemDeferredPublish}.
     * Was: copied to durableDir + cleared work before mixers opened (SD write hitch).
     * Reversal: restore publish block when workDir≠durableDir.
     * durableDir kept for API compat (ignored when deferred). 2026-07-19 / 2026-07-21
     */
    public List<StemFile> separateToMp3(File source, File workDir,
            @SuppressWarnings("unused") File durableDir,
            boolean premixExperimental, Progress progress) throws Exception {
        if (licenseKey.length() < 8) throw new IOException("Lalal license key missing");
        if (source == null || !source.isFile()) throw new IOException("Source track missing");
        if (workDir == null) throw new IOException("Work dir missing");
        workDir.mkdirs();

        throwIfCancelled();
        emit(progress, "upload", 0, "Connecting…");
        String sourceId = upload(source);
        throwIfCancelled();
        emit(progress, "upload", 8, "Uploaded");
        emit(progress, "split", 10, "Starting separation…");

        String taskId = startMultistem(sourceId, MULTISTEM_IDS);
        throwIfCancelled();
        Map<String, String> urls = pollUntilAllDone(new String[] { taskId }, progress);
        throwIfCancelled();
        emit(progress, "download", 70, "Fetching stems…");
        List<StemFile> downloaded = downloadStemsParallel(urls, workDir, progress);
        throwIfCancelled();

        List<StemFile> pads;
        if (premixExperimental) {
            emit(progress, "mix", 88, "Premix Melody (experimental)…");
            pads = premixToFourPads(downloaded, workDir, progress);
        } else {
            emit(progress, "mix", 90, "Keeping Melody stems live…");
            pads = downloaded;
            emit(progress, "mix", 96, "Live multi-player Melody");
        }
        throwIfCancelled();

        // durableDir ignored — durable copy via StemDeferredPublish after playback. 2026-07-21
        writeTrackMarker(workDir, source);
        emit(progress, "ready", 100, "Ready");
        return pads;
    }

    /** @deprecated Prefer work+durable+premix overload. */
    public List<StemFile> separateToMp3(File source, File workDir, File durableDir,
            Progress progress) throws Exception {
        return separateToMp3(source, workDir, durableDir, false, progress);
    }

    /** @deprecated Prefer work+durable overload. */
    public List<StemFile> separateToMp3(File source, File outDir, Progress progress)
            throws Exception {
        return separateToMp3(source, outDir, outDir, false, progress);
    }

    /**
     * 2026-07-19 — Fast vocals+instrumental via stem_separator (not full multistem).
     * Layman: one cloud job peels the voice and the band for Now Playing.
     * Technical: POST /split/stem_separator/ stem=vocals → download type stem + back.
     * Reversal: use separateToMp3 only.
     * 2026-07-21 — Downloads to solo work only; sibling publish via {@link StemDeferredPublish}.
     * Was: immediate {@link #publishSoloSiblings} before NP could play. Reversal: restore that call.
     */
    public void separateSoloToFiles(File source, File soloDir, Progress progress) throws Exception {
        if (licenseKey.length() < 8) throw new IOException("Lalal license key missing");
        if (source == null || !source.isFile()) throw new IOException("Source track missing");
        // Work scratch under soloDir (cache); durable siblings deferred. 2026-07-19 / 2026-07-21
        File work = soloDir != null ? soloDir : SoloStemPaths.ensureSiblingDir(source, SoloMode.INSTRUMENTAL);
        if (work == null) throw new IOException("Solo dir missing");
        work.mkdirs();

        throwIfCancelled();
        emit(progress, "upload", 0, "Connecting…");
        String sourceId = upload(source);
        throwIfCancelled();
        emit(progress, "upload", 8, "Uploaded");
        emit(progress, "split", 10, "Starting vocal split…");

        String taskId = startStemSeparator(sourceId, "vocals");
        throwIfCancelled();
        Map<String, String> typed = pollStemSeparatorTracks(taskId, progress);
        throwIfCancelled();
        emit(progress, "download", 70, "Fetching vocals + instrumental…");

        File vocalsWork = new File(work, "vocals.mp3");
        File instrWork = new File(work, "instrumental.mp3");
        String stemUrl = typed.get("stem");
        String backUrl = typed.get("back");
        if (stemUrl != null && stemUrl.length() > 0) {
            emit(progress, "download", 75, "Downloading acapella…");
            download(stemUrl, vocalsWork);
        }
        if (backUrl != null && backUrl.length() > 0) {
            emit(progress, "download", 88, "Downloading instrumental…");
            download(backUrl, instrWork);
        }
        boolean gotVocals = vocalsWork.isFile() && vocalsWork.length() >= 100;
        boolean gotInstr = instrWork.isFile() && instrWork.length() >= 100;
        if (!gotVocals && !gotInstr) {
            throw new IOException("Solo split returned no downloadable tracks");
        }
        // Marker only — sibling copy deferred so NP can start. 2026-07-21
        writeTrackMarker(work, source);
        emit(progress, "ready", 100, "Ready");
    }

    /**
     * Copy work vocals/instrumental into sibling .acapellas / .instrumentals folders.
     * Layman: put the peeled files next to the song under hidden folders.
     * 2026-07-19
     */
    public static void publishSoloSiblings(File source, File vocalsWork, File instrWork) {
        if (source == null || !source.isFile()) return;
        File acapDest = null;
        File instrDest = null;
        if (vocalsWork != null && vocalsWork.isFile() && vocalsWork.length() >= 100) {
            SoloStemPaths.ensureSiblingDir(source, SoloMode.ACAPELLA);
            File dest = SoloStemPaths.siblingSoloFile(source, SoloMode.ACAPELLA, "mp3");
            if (dest != null) {
                copyFileQuiet(vocalsWork, dest);
                acapDest = dest;
            }
        }
        if (instrWork != null && instrWork.isFile() && instrWork.length() >= 100) {
            SoloStemPaths.ensureSiblingDir(source, SoloMode.INSTRUMENTAL);
            String ext = instrWork.getName().toLowerCase(java.util.Locale.US).endsWith(".wav")
                    ? "wav" : "mp3";
            File dest = SoloStemPaths.siblingSoloFile(source, SoloMode.INSTRUMENTAL, ext);
            if (dest != null) {
                copyFileQuiet(instrWork, dest);
                instrDest = dest;
            }
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("source", source.getName());
            d.put("sourcePath", source.getAbsolutePath());
            d.put("vocalsWork", vocalsWork != null ? vocalsWork.getAbsolutePath() : "null");
            d.put("vocalsSize", vocalsWork != null ? vocalsWork.length() : -1L);
            d.put("instrWork", instrWork != null ? instrWork.getAbsolutePath() : "null");
            d.put("instrSize", instrWork != null ? instrWork.length() : -1L);
            d.put("acapDest", acapDest != null ? acapDest.getAbsolutePath() : "null");
            d.put("instrDest", instrDest != null ? instrDest.getAbsolutePath() : "null");
            d.put("dualOk", acapDest != null && instrDest != null);
            com.solar.launcher.Debug072d46Log.log(
                    "LalalClient.publishSoloSiblings", "published", "H-F", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /**
     * 2026-07-19 — Poll stem_separator until done; map {@code stem}/{@code back} → URL.
     * Layman: wait until Lalal finishes, then know which link is voice vs band.
     * Technical: check result tracks by type. Reversal: use pollUntilAllDone label map.
     */
    Map<String, String> pollStemSeparatorTracks(String taskId, Progress progress) throws Exception {
        Map<String, String> out = new HashMap<String, String>();
        long deadline = System.currentTimeMillis() + 20L * 60L * 1000L;
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled();
            JSONObject body = new JSONObject();
            JSONArray ids = new JSONArray();
            ids.put(taskId);
            body.put("task_ids", ids);
            Request req = new Request.Builder()
                    .url(BASE + "/api/v1/check/")
                    .header("X-License-Key", licenseKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"), body.toString()))
                    .build();
            Response resp = client.newCall(req).execute();
            String text;
            try {
                text = bodyString(resp);
                if (!resp.isSuccessful()) {
                    throw new IOException("Check HTTP " + resp.code() + ": " + text);
                }
            } finally {
                resp.close();
            }
            JSONObject root = new JSONObject(text);
            JSONObject resultMap = root.optJSONObject("result");
            if (resultMap == null) throw new IOException("Check missing result: " + text);
            JSONObject task = resultMap.optJSONObject(taskId);
            if (task == null) {
                Thread.sleep(2500);
                continue;
            }
            String status = task.optString("status", "");
            if ("progress".equals(status)) {
                int pct = task.optInt("progress", 10);
                if (progress != null) {
                    int band = 10 + Math.max(0, Math.min(pct, 100)) / 2;
                    emit(progress, "split", Math.min(69, band), "Separating… " + pct + "%");
                }
                Thread.sleep(2500);
                continue;
            }
            if ("success".equals(status)) {
                JSONObject payload = task.optJSONObject("result");
                if (payload == null) throw new IOException("Success without result");
                JSONArray tracks = payload.optJSONArray("tracks");
                if (tracks == null) throw new IOException("Success without tracks");
                for (int t = 0; t < tracks.length(); t++) {
                    JSONObject tr = tracks.getJSONObject(t);
                    String type = tr.optString("type", "");
                    String url = tr.optString("url", "");
                    if (url.isEmpty()) continue;
                    if ("stem".equals(type) || "back".equals(type)) {
                        out.put(type, url);
                    }
                }
                return out;
            }
            if ("error".equals(status) || "server_error".equals(status)
                    || "cancelled".equals(status)) {
                String err = task.optString("error", status);
                throw new IOException("Lalal " + status + ": " + err);
            }
            Thread.sleep(2500);
        }
        throw new IOException("Solo stem separation timed out");
    }

    /**
     * 2026-07-19 — Single-stem stem_separator job (OpenAPI StemSeparatorSplitterPresetsV1).
     * Layman: ask Lalal for just the vocals peel (and get the band as the leftover).
     * Technical: POST /api/v1/split/stem_separator/. Reversal: use startMultistem.
     */
    String startStemSeparator(String sourceId, String stem) throws Exception {
        if (stem == null || stem.length() == 0) {
            throw new IOException("Empty stem");
        }
        JSONObject presets = new JSONObject();
        presets.put("stem", stem);
        presets.put("encoder_format", "mp3");
        presets.put("splitter", "auto");
        presets.put("extraction_level", "deep_extraction");

        JSONObject body = new JSONObject();
        body.put("source_id", sourceId);
        body.put("presets", presets);

        Request req = new Request.Builder()
                .url(BASE + "/api/v1/split/stem_separator/")
                .header("X-License-Key", licenseKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"), body.toString()))
                .build();
        Response resp = client.newCall(req).execute();
        try {
            String text = bodyString(resp);
            if (!resp.isSuccessful()) {
                throw new IOException("Stem separator HTTP " + resp.code() + ": " + text);
            }
            JSONObject json = new JSONObject(text);
            String taskId = json.optString("task_id", "");
            if (taskId.isEmpty()) throw new IOException("No task_id: " + text);
            return taskId;
        } finally {
            resp.close();
        }
    }

    /**
     * 2026-07-19 — Solo NP cache leaf under stem_solo/lalal/v1_&lt;hex&gt;.
     * Layman: folder that holds just vocals.mp3 and instrumental.mp3 for a song.
     * Technical: stable basename|size key. Reversal: store beside lalal_stems only.
     */
    public static String soloCacheLeaf(File track) {
        return "v1_" + cacheKeyStable(track);
    }

    /** App-private solo root: {@code cache/stem_solo/lalal/}. 2026-07-19 */
    public static File soloProviderRoot(File appCache) {
        File base = appCache != null ? appCache : new File(".");
        return new File(new File(base, "stem_solo"), StemFeatures.PROVIDER_LALAL);
    }

    /** Solo leaf dir for this track (may not exist yet). 2026-07-19 */
    public static File soloDir(File appCache, File track) {
        return new File(soloProviderRoot(appCache), soloCacheLeaf(track));
    }

    /**
     * Local solo file if present — sibling folders first, then legacy stem_solo cache.
     * 2026-07-19 — Prefer …/.instrumentals|/.acapellas/&lt;basename&gt;.
     * Was: stem_solo leaf only. Reversal: skip sibling check.
     */
    public static File findReadySoloFile(android.content.Context ctx, File track, SoloMode mode,
            File appCache) {
        if (track == null || !track.isFile() || mode == null) return null;
        File sibling = SoloStemPaths.findReadySibling(track, mode);
        if (sibling != null) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("trackPath", track.getAbsolutePath());
                d.put("mode", mode.name());
                d.put("branch", "sibling");
                d.put("hit", sibling.getAbsolutePath());
                d.put("hitSize", sibling.length());
                d.put("stableKey", cacheKeyStable(track));
                com.solar.launcher.Debug072d46Log.log(
                        "LalalClient.findReadySoloFile", "hit", "H-B", d);
            } catch (Exception ignored) {}
            // #endregion
            return sibling;
        }
        File cache = appCache != null ? appCache : (ctx != null ? ctx.getCacheDir() : null);
        File dir = soloDir(cache, track);
        if (dir != null && dir.isDirectory() && stemDirOwnedByTrack(dir, track)) {
            File f = mode == SoloMode.ACAPELLA
                    ? new File(dir, "vocals.mp3")
                    : resolveInstrumentalFile(dir);
            if (f != null && f.isFile() && f.length() >= 100) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("track", track.getName());
                    d.put("mode", mode.name());
                    d.put("branch", "soloCache");
                    d.put("dir", dir.getAbsolutePath());
                    d.put("hit", f.getAbsolutePath());
                    d.put("markerOk", markerMatchesTrack(dir, track));
                    d.put("stableKey", cacheKeyStable(track));
                    com.solar.launcher.Debug072d46Log.log(
                            "LalalClient.findReadySoloFile", "hit", "H-B,H-E", d);
                } catch (Exception ignored) {}
                // #endregion
                return f;
            }
        }
        File fromFull = findSoloFromFullStems(ctx, track, mode, cache);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("track", track.getName());
            d.put("mode", mode.name());
            d.put("branch", fromFull != null ? "fullStems" : "miss");
            d.put("hit", fromFull != null ? fromFull.getAbsolutePath() : "null");
            d.put("stableKey", cacheKeyStable(track));
            com.solar.launcher.Debug072d46Log.log(
                    "LalalClient.findReadySoloFile",
                    fromFull != null ? "hit" : "miss", "H-E", d);
        } catch (Exception ignored) {}
        // #endregion
        return fromFull;
    }

    /**
     * 2026-07-20 — UI-safe solo presence: sibling folders + solo leaf only (no stem-root scan).
     * Layman: quick check that Instrumental/Acapella files sit next to the song or in cache.
     * Technical: skip {@link #findSoloFromFullStems} / {@link #findReadyStemDir}.
     * Was: menu used findReadySoloFile (could walk lalal_stems). Reversal: call that instead.
     */
    public static File findReadySoloFileFast(File track, SoloMode mode, File appCache) {
        if (track == null || !track.isFile() || mode == null) return null;
        File sibling = SoloStemPaths.findReadySibling(track, mode);
        if (sibling != null) return sibling;
        File dir = soloDir(appCache, track);
        if (dir == null || !dir.isDirectory()) return null;
        File f = mode == SoloMode.ACAPELLA
                ? new File(dir, "vocals.mp3")
                : resolveInstrumentalFile(dir);
        if (f != null && f.isFile() && f.length() >= 100) return f;
        return null;
    }

    /**
     * 2026-07-20 — Offline menu gate without stem-root walks.
     * Layman: only look beside the song or in its tiny solo cache / user .stems folder.
     * Technical: sibling/leaf + {@link #userStemsReady} / vocals pad — never {@link #findReadyStemDir}.
     * Reversal: call {@link #hasOfflineSoloSource}.
     */
    public static boolean hasOfflineSoloSourceFast(File track, SoloMode mode, File appCache) {
        if (track == null || !track.isFile() || mode == null) return false;
        if (findReadySoloFileFast(track, mode, appCache) != null) return true;
        if (mode == SoloMode.ACAPELLA) {
            File vocals = resolveStemFile(userStemsDir(track), "vocals");
            return vocals != null && vocals.isFile() && vocals.length() >= 100;
        }
        // Instrumental can bake from full pads when user sidecar is complete. 2026-07-20
        return userStemsReady(track);
    }

    /** Prefer instrumental.mp3 then instrumental.wav. 2026-07-19 */
    public static File resolveInstrumentalFile(File dir) {
        if (dir == null) return null;
        File mp3 = new File(dir, "instrumental.mp3");
        if (mp3.isFile() && mp3.length() >= 100) return mp3;
        File wav = new File(dir, "instrumental.wav");
        if (wav.isFile() && wav.length() >= 100) return wav;
        return null;
    }

    /**
     * Use full Stem Player pads: vocals.mp3 (acapella) or null for instrumental until bake.
     * 2026-07-19
     */
    public static File findSoloFromFullStems(android.content.Context ctx, File track, SoloMode mode,
            File appCache) {
        if (track == null || mode == null) return null;
        File stemDir = findReadyStemDir(ctx, track, false, appCache);
        if (stemDir == null && ctx != null) {
            try {
                android.content.SharedPreferences prefs =
                        ctx.getSharedPreferences(LalalAccount.PREFS_NAME, 0);
                stemDir = findReadyStemDir(ctx, track,
                        LalalAccount.isPremixExperimental(prefs), appCache);
            } catch (Exception ignored) {}
        }
        if (stemDir == null) return null;
        if (mode == SoloMode.ACAPELLA) {
            return resolveStemFile(stemDir, "vocals");
        }
        File solo = soloDir(appCache, track);
        File baked = resolveInstrumentalFile(solo);
        if (baked != null && stemDirOwnedByTrack(solo, track)) return baked;
        return null;
    }

    /**
     * True when offline can still produce this solo without cloud (bake pads / full-stem vocals).
     * Layman: Stem Player files already on the player mean Instrumental can be mixed offline.
     * Technical: INSTRUMENTAL → findReadyStemDir; ACAPELLA → findSoloFromFullStems vocals.
     * Was: menus used opt-in alone offline. Reversal: ignore this helper in canOfferSoloMode.
     * 2026-07-19
     */
    public static boolean hasOfflineSoloSource(android.content.Context ctx, File track, SoloMode mode,
            File appCache) {
        if (track == null || !track.isFile() || mode == null) return false;
        File cache = appCache != null ? appCache : (ctx != null ? ctx.getCacheDir() : null);
        if (mode == SoloMode.INSTRUMENTAL) {
            if (findReadyStemDir(ctx, track, false, cache) != null) return true;
            if (ctx == null) return false;
            try {
                android.content.SharedPreferences prefs =
                        ctx.getSharedPreferences(LalalAccount.PREFS_NAME, 0);
                return findReadyStemDir(ctx, track,
                        LalalAccount.isPremixExperimental(prefs), cache) != null;
            } catch (Exception ignored) {
                return false;
            }
        }
        return findSoloFromFullStems(ctx, track, mode, cache) != null;
    }

    /**
     * Bake instrumental.wav from drum+bass+melody pads into solo leaf (CPU only).
     * 2026-07-19
     */
    public static File bakeInstrumentalFromFullStems(android.content.Context ctx, File track,
            File appCache, Progress progress) throws Exception {
        File cache = appCache != null ? appCache : (ctx != null ? ctx.getCacheDir() : null);
        File stemDir = findReadyStemDir(ctx, track, false, cache);
        if (stemDir == null && ctx != null) {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(LalalAccount.PREFS_NAME, 0);
            stemDir = findReadyStemDir(ctx, track, LalalAccount.isPremixExperimental(prefs), cache);
        }
        if (stemDir == null) throw new IOException("Full stems not ready");
        java.util.ArrayList<File> parts = new java.util.ArrayList<File>();
        File drum = resolveStemFile(stemDir, "drum");
        if (drum == null) drum = resolveStemFile(stemDir, "drums");
        File bass = resolveStemFile(stemDir, "bass");
        if (drum != null) parts.add(drum);
        if (bass != null) parts.add(bass);
        File melWav = new File(stemDir, StemOtherPremix.MELODY_WAV);
        if (melWav.isFile() && melWav.length() >= 100) {
            parts.add(melWav);
        } else {
            File mel = resolveMelodyFile(stemDir);
            if (mel != null) parts.add(mel);
            for (String id : ALL_OTHER_IDS) {
                File f = resolveStemFile(stemDir, id);
                if (f != null && (mel == null || !f.equals(mel))) parts.add(f);
            }
        }
        if (parts.isEmpty()) throw new IOException("No non-vocal stems to bake");
        // Bake into sibling .instrumentals; keep legacy solo leaf as work scratch. 2026-07-19
        File solo = soloDir(cache, track);
        solo.mkdirs();
        File workOut = new File(solo, "instrumental.wav");
        emit(progress, "mix", 50, "Baking instrumental…");
        StemOtherPremix.mixToMonoWav(parts, workOut, null, null);
        writeTrackMarker(solo, track);
        File vocals = resolveStemFile(stemDir, "vocals");
        File vocalsWork = null;
        if (vocals != null) {
            vocalsWork = new File(solo, "vocals.mp3");
            if (!vocalsWork.isFile() || vocalsWork.length() < 100) {
                copyFileQuiet(vocals, vocalsWork);
            }
        }
        emit(progress, "publish", 90, "Saving beside track…");
        publishSoloSiblings(track, vocalsWork, workOut);
        File sibling = SoloStemPaths.findReadySibling(track, SoloMode.INSTRUMENTAL);
        emit(progress, "ready", 100, "Ready");
        return sibling != null ? sibling : workOut;
    }

    private static void copyFileQuiet(File src, File dest) {
        if (src == null || dest == null) return;
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static void emit(Progress progress, String phase, int percent, String detail) {
        if (progress == null) return;
        try {
            progress.onProgress(phase, percent, detail);
        } catch (Exception ignored) {}
    }

    private static boolean sameDir(File a, File b) {
        if (a == null || b == null) return a == b;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /**
     * Work scratch — always app internal cache (faster than MicroSD).
     * 2026-07-19
     */
    public static File workStemDir(android.content.Context ctx, File track, boolean premix) {
        if (ctx == null) return null;
        return new File(new File(ctx.getCacheDir(), "lalal_work"), cacheLeaf(track, premix));
    }

    /** @deprecated Prefer overload with premix flag. */
    public static File workStemDir(android.content.Context ctx, File track) {
        return workStemDir(ctx, track, false);
    }

    /**
     * Durable stem vault leaf — internal MMC first, MicroSD next, app cache last.
     * Layman: long-term stem shelf prefers the phone chip over the SD card.
     * Technical: {@link StemDurableRoots#pick}; was getNewMediaRoot (Primary=MicroSD bias).
     * Reversal: restore app-cache-first + getNewMediaRoot overflow.
     * 2026-07-19 / 2026-07-21
     */
    public static File durableStemDir(android.content.Context ctx, File track, boolean premix) {
        if (ctx == null) return null;
        long need = StemDurableRoots.needBytes(premix);
        File appVault = new File(ctx.getCacheDir(), "lalal_stems");
        File internalVault = StemDurableRoots.volumeVault(
                com.solar.launcher.DeviceFeatures.getInternalStorageRoot(),
                ctx.getPackageName());
        File microVault = StemDurableRoots.volumeVault(
                com.solar.launcher.DeviceFeatures.getMicroSdRoot(),
                ctx.getPackageName());
        File vault = StemDurableRoots.pick(internalVault, microVault, appVault, need);
        if (vault == null) vault = appVault;
        File leaf = new File(vault, cacheLeaf(track, premix));
        leaf.mkdirs();
        return leaf;
    }

    /** @deprecated Prefer overload with premix flag. */
    public static File durableStemDir(android.content.Context ctx, File track) {
        return durableStemDir(ctx, track, false);
    }

    /** Copy stem files into durableDir; return StemFiles pointing there. 2026-07-19 */
    public static List<StemFile> publishStems(List<StemFile> stems, File durableDir)
            throws IOException {
        return publishStems(stems, durableDir, null);
    }

    /**
     * Publish stems and stamp {@link #TRACK_MARKER} when source track is known.
     * 2026-07-19
     */
    public static List<StemFile> publishStems(List<StemFile> stems, File durableDir, File sourceTrack)
            throws IOException {
        if (stems == null || durableDir == null) throw new IOException("publish missing args");
        durableDir.mkdirs();
        List<StemFile> out = new ArrayList<StemFile>(stems.size());
        for (int i = 0; i < stems.size(); i++) {
            StemFile s = stems.get(i);
            if (s == null || s.file == null || !s.file.isFile()) continue;
            File dest = new File(durableDir, s.file.getName());
            if (!sameDir(s.file.getParentFile(), durableDir)) {
                copyFile(s.file, dest);
            } else {
                dest = s.file;
            }
            out.add(new StemFile(s.id, s.label, dest, s.zone));
        }
        if (out.size() < 4) throw new IOException("publish incomplete (" + out.size() + ")");
        if (sourceTrack != null) writeTrackMarker(durableDir, sourceTrack);
        // Tip callers: MainActivity can refresh Has Stems bit via path. 2026-07-19
        return out;
    }

    /**
     * Write basename + size into a stem leaf so later opens find it after path/mtime drift.
     * Layman: sticky note on the stem folder naming the song.
     * 2026-07-19
     */
    public static void writeTrackMarker(File dir, File track) {
        if (dir == null || track == null) return;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            File marker = new File(dir, TRACK_MARKER);
            FileOutputStream out = new FileOutputStream(marker);
            try {
                String line = trackBaseName(track) + "\n" + track.length() + "\n";
                out.write(line.getBytes("UTF-8"));
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * True when marker names this track AND the leaf belongs to it (stable key, path key, or
     * remounted path-drift with matching basename+size marker).
     * Blocks poisoned markers on unrelated leaves without a matching marker.
     * Was: stable key in leaf only — missed legacy path-keyed caches after remount.
     * Reversal: require leaf.indexOf(cacheKeyStable) only.
     * 2026-07-20
     */
    public static boolean stemDirOwnedByTrack(File dir, File track) {
        if (dir == null || track == null) return false;
        if (!markerMatchesTrack(dir, track)) return false;
        String leaf = dir.getName();
        if (leaf != null && leaf.endsWith(".stems")) return true; // user sidecar
        String key = cacheKeyStable(track);
        if (leaf != null && key != null && leaf.indexOf(key) >= 0) return true;
        String pathKey = cacheKeyFor(track);
        if (leaf != null && pathKey != null && leaf.indexOf(pathKey) >= 0) return true;
        // Path-drifted legacy leaf: marker already matched basename+size. 2026-07-20
        return leaf != null
                && (leaf.indexOf("_live_") >= 0 || leaf.indexOf("_premix_") >= 0);
    }

    /**
     * True when {@link #TRACK_MARKER} names this track (basename; size soft-check).
     * 2026-07-19
     */
    public static boolean markerMatchesTrack(File dir, File track) {
        if (dir == null || track == null) return false;
        File marker = new File(dir, TRACK_MARKER);
        if (!marker.isFile() || marker.length() < 1) return false;
        try {
            FileInputStream in = new FileInputStream(marker);
            byte[] buf = new byte[(int) Math.min(marker.length(), 512)];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            String text = new String(buf, 0, n, "UTF-8");
            String[] lines = text.split("\n");
            if (lines.length < 1) return false;
            String base = lines[0].trim();
            if (!base.equalsIgnoreCase(trackBaseName(track))) return false;
            if (lines.length >= 2) {
                try {
                    long sz = Long.parseLong(lines[1].trim());
                    // Soft: size match preferred; basename-only still ok if size line junk. 2026-07-19
                    if (sz > 0 && track.length() > 0 && sz != track.length()) return false;
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Delete files inside a stem leaf and the folder itself (best-effort).
     * Layman: throw away a scratch stem folder quietly.
     * 2026-07-21
     */
    public static void clearDirQuiet(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isFile()) kids[i].delete();
        }
        dir.delete();
    }

    /**
     * Collapse many zone-3 files into melody.wav; keep vocals/drum/bass MP3s.
     * Y1 plays four streams only. 2026-07-19
     */
    public List<StemFile> premixToFourPads(List<StemFile> downloaded, File outDir,
            Progress progress) throws Exception {
        return premixToFourPadsStatic(downloaded, outDir, cancelled, progress);
    }

    public List<StemFile> premixToFourPads(List<StemFile> downloaded, File outDir)
            throws Exception {
        return premixToFourPadsStatic(downloaded, outDir, cancelled, null);
    }

    /** Static so user-sidecar load can premix without a client instance. */
    public static List<StemFile> premixToFourPadsStatic(List<StemFile> downloaded, File outDir,
            AtomicBoolean cancelled) throws Exception {
        return premixToFourPadsStatic(downloaded, outDir, cancelled, null);
    }

    public static List<StemFile> premixToFourPadsStatic(List<StemFile> downloaded, File outDir,
            AtomicBoolean cancelled, final Progress progress) throws Exception {
        if (downloaded == null || downloaded.isEmpty()) {
            throw new IOException("No stems to premix");
        }
        StemFile vocals = null;
        StemFile drum = null;
        StemFile bass = null;
        List<File> others = new ArrayList<File>();
        for (int i = 0; i < downloaded.size(); i++) {
            StemFile s = downloaded.get(i);
            if (s == null || s.file == null || !s.file.isFile()) continue;
            if (s.zone == 0) vocals = s;
            else if (s.zone == 1) drum = s;
            else if (s.zone == 2) bass = s;
            else others.add(s.file);
        }
        if (vocals == null || drum == null || bass == null) {
            throw new IOException("Missing core stems for premix");
        }
        if (others.isEmpty()) {
            throw new IOException("No Melody/Other stems to premix");
        }
        List<StemFile> out = new ArrayList<StemFile>(4);
        out.add(vocals);
        out.add(drum);
        out.add(bass);
        if (others.size() == 1) {
            File only = others.get(0);
            String id = only.getName().toLowerCase().endsWith(".wav") ? "melody" : stripExt(only.getName());
            out.add(new StemFile(id, "Melody", only, 3));
            return out;
        }
        File melodyWav = new File(outDir, StemOtherPremix.MELODY_WAV);
        StemOtherPremix.MixProgress mixCb = progress == null ? null
                : new StemOtherPremix.MixProgress() {
                    @Override
                    public void onMixProgress(int within0to100, String detail) {
                        // Map premix 0–100 → overall 88–96.
                        int pct = 88 + (within0to100 * 8) / 100;
                        if (pct > 96) pct = 96;
                        emit(progress, "mix", pct, detail != null ? detail : "Mixing…");
                    }
                };
        StemOtherPremix.mixToMonoWav(others, melodyWav, cancelled, mixCb);
        for (int i = 0; i < others.size(); i++) {
            File f = others.get(i);
            if (f != null && !f.equals(melodyWav) && f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        out.add(new StemFile("melody", "Melody", melodyWav, 3));
        return out;
    }

    /** Strip trailing .mp3 / .wav for StemFile id. 2026-07-19 */
    private static String stripExt(String name) {
        if (name == null) return "melody";
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.isEmpty() ? "melody" : n;
    }

    /**
     * Fetch every returned stem MP3 in parallel; skip missing optional “other” URLs.
     * Core vocals/drum/bass must succeed. 2026-07-19
     */
    List<StemFile> downloadStemsParallel(Map<String, String> urls, File outDir, Progress progress)
            throws Exception {
        final List<String> want = new ArrayList<String>();
        for (String id : CORE_IDS) {
            if (urls == null || urls.get(id) == null || urls.get(id).isEmpty()) {
                throw new IOException("Missing stem URL for " + id);
            }
            want.add(id);
        }
        for (String id : OTHER_IDS) {
            if (urls != null && urls.get(id) != null && !urls.get(id).isEmpty()) {
                want.add(id);
            }
        }
        // Residual = “everything else” (synth/strings/wind…) for Melody premix.
        if (urls != null && urls.get(RESIDUAL_ID) != null && !urls.get(RESIDUAL_ID).isEmpty()) {
            want.add(RESIDUAL_ID);
        }
        // Sidecar-style extras if somehow present in the map.
        for (String id : EXTRA_OTHER_IDS) {
            if (urls != null && urls.get(id) != null && !urls.get(id).isEmpty()) {
                want.add(id);
            }
        }
        if (want.size() <= CORE_IDS.length) {
            throw new IOException("No Melody/Other stems returned (need piano/guitar/residual)");
        }

        final int n = want.size();
        final StemFile[] slots = new StemFile[n];
        final AtomicInteger finished = new AtomicInteger(0);
        final AtomicReference<Exception> firstErr = new AtomicReference<Exception>();
        final CountDownLatch latch = new CountDownLatch(n);
        final long t0 = System.currentTimeMillis();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("stemCount", n);
            d.put("otherCount", n - CORE_IDS.length);
            d.put("parallel", true);
            com.solar.launcher.Debug543e15Log.log(
                    "LalalClient.downloadStemsParallel:begin",
                    "parallel stem download start",
                    "STEM_DL",
                    d);
        } catch (Exception ignored) {}
        // #endregion
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 6));
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            throwIfCancelled();
                            if (firstErr.get() != null) return;
                            String id = want.get(idx);
                            String url = urls.get(id);
                            File dest = new File(outDir, id + ".mp3");
                            long stemT0 = System.currentTimeMillis();
                            download(url, dest);
                            int zone = zoneForId(id);
                            slots[idx] = new StemFile(id, labelForStemId(id), dest, zone);
                            int done = finished.incrementAndGet();
                            // #region agent log
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("id", id);
                                d.put("zone", zone);
                                d.put("ms", System.currentTimeMillis() - stemT0);
                                d.put("bytes", dest.length());
                                d.put("done", done);
                                com.solar.launcher.Debug543e15Log.log(
                                        "LalalClient.downloadStemsParallel:one",
                                        "stem downloaded",
                                        "STEM_DL",
                                        d);
                            } catch (Exception ignored) {}
                            // #endregion
                            if (progress != null) {
                                // Download band 70–87.
                                int pct = 70 + (done * 17) / n;
                                if (pct > 87) pct = 87;
                                emit(progress, "download", pct,
                                        "Downloaded " + done + "/" + n + " · " + id);
                            }
                        } catch (Exception e) {
                            firstErr.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }
            if (!latch.await(10, TimeUnit.MINUTES)) {
                throw new IOException("Stem download timed out");
            }
        } finally {
            pool.shutdownNow();
        }
        Exception err = firstErr.get();
        if (err != null) throw err;
        List<StemFile> out = new ArrayList<StemFile>(n);
        for (int i = 0; i < n; i++) {
            if (slots[i] == null) throw new IOException("Missing stem file for " + want.get(i));
            out.add(slots[i]);
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("totalMs", System.currentTimeMillis() - t0);
            d.put("count", out.size());
            StringBuilder ids = new StringBuilder();
            StringBuilder sizes = new StringBuilder();
            for (int i = 0; i < out.size(); i++) {
                StemFile s = out.get(i);
                if (ids.length() > 0) {
                    ids.append(',');
                    sizes.append(',');
                }
                ids.append(s != null ? s.id : "?");
                sizes.append(s != null && s.file != null ? s.file.length() : -1);
            }
            d.put("ids", ids.toString());
            d.put("sizes", sizes.toString());
            d.put("hasAcoustic", ids.toString().contains("acoustic_guitar"));
            d.put("hasResidual", ids.toString().contains(RESIDUAL_ID));
            com.solar.launcher.Debug75a361Log.log(
                    "LalalClient.downloadStemsParallel:end",
                    "downloaded stem set",
                    "B",
                    d);
        } catch (Exception ignored) {}
        // #endregion
        return out;
    }

    /**
     * ASCII-safe name for Content-Disposition only — bytes stay the real file.
     * Layman: titles with ’ or " used to break Lalal upload headers (“unexpected char”).
     * Technical: strip quotes/controls/non-ASCII; keep letters, digits, ._- ; empty → track.mp3.
     * Reversal: pass {@code file.getName()} raw into the header.
     * 2026-07-20
     */
    public static String uploadFileNameForHeader(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "track.mp3";
        String name = rawName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ' || c == '\'' || c == '\u2019' || c == '\u2018') {
                // 2026-07-20 — Spaces and apostrophes (ASCII + curly) → underscore (header-safe).
                sb.append('_');
            } else if (c > 127) {
                sb.append('_');
            }
            // Drop quotes, backslash, controls, and other punctuation.
        }
        String out = sb.toString();
        while (out.contains("..")) out = out.replace("..", ".");
        // 2026-07-20 — All-punctuation input (e.g. ''') must not ship as "___".
        boolean hasAlnum = false;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                hasAlnum = true;
                break;
            }
        }
        if (!hasAlnum || out.isEmpty() || out.equals(".") || out.equals("..")) {
            return "track.mp3";
        }
        if (out.charAt(0) == '.') out = "t" + out;
        return out;
    }

    public static class LalalQuotaException extends IOException {
        public LalalQuotaException(String msg) {
            super(msg);
        }
    }

    private void checkLalalError(okhttp3.Response resp, String text, String contextMsg) throws IOException {
        if (resp.isSuccessful()) return;
        int code = resp.code();
        if (code == 402 || code == 429 || (text != null && (text.contains("insufficient") || text.contains("quota") || text.contains("Not enough processing time") || text.contains("limit")))) {
            throw new LalalQuotaException(contextMsg + " API Limit Reached (" + code + "): " + text);
        }
        throw new IOException(contextMsg + " HTTP " + code + ": " + text);
    }

    /** Binary upload — Content-Disposition filename; returns source id. */
    String upload(File file) throws Exception {
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), file);
        // 2026-07-20 — Sanitize header name; apostrophes/' broke Lalal (“unexpected char”).
        String name = uploadFileNameForHeader(file != null ? file.getName() : null);
        Request req = new Request.Builder()
                .url(BASE + "/api/v1/upload/")
                .header("X-License-Key", licenseKey)
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .post(body)
                .build();
        Response resp = client.newCall(req).execute();
        try {
            String text = bodyString(resp);
            checkLalalError(resp, text, "Upload");
            JSONObject json = new JSONObject(text);
            String id = json.optString("id", "");
            if (id.isEmpty()) throw new IOException("Upload missing id: " + text);
            return id;
        } finally {
            resp.close();
        }
    }

    /**
     * Start multistem job; stem_list must be OpenAPI enum only (≤ MULTISTEM_MAX).
     * 2026-07-19
     */
    String startMultistem(String sourceId, String[] stemList) throws Exception {
        if (stemList == null || stemList.length == 0) {
            throw new IOException("Empty stem list");
        }
        if (stemList.length > MULTISTEM_MAX) {
            throw new IOException("stem_list has " + stemList.length
                    + " items; Lalal allows at most " + MULTISTEM_MAX);
        }
        JSONObject presets = new JSONObject();
        JSONArray list = new JSONArray();
        StringBuilder listCsv = new StringBuilder();
        for (int i = 0; i < stemList.length; i++) {
            list.put(stemList[i]);
            if (i > 0) listCsv.append(',');
            listCsv.append(stemList[i]);
        }
        presets.put("stem_list", list);
        presets.put("encoder_format", "mp3");
        presets.put("splitter", "auto");
        presets.put("extraction_level", "clear_cut");

        JSONObject body = new JSONObject();
        body.put("source_id", sourceId);
        body.put("presets", presets);

        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("stem_list", listCsv.toString());
            d.put("count", stemList.length);
            d.put("sourceIdLen", sourceId != null ? sourceId.length() : 0);
            com.solar.launcher.Debug543e15Log.log(
                    "LalalClient.startMultistem:request",
                    "multistem stem_list about to POST",
                    "H-A",
                    d);
        } catch (Exception ignored) {}
        // #endregion

        Request req = new Request.Builder()
                .url(BASE + "/api/v1/split/multistem/")
                .header("X-License-Key", licenseKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"), body.toString()))
                .build();
        Response resp = client.newCall(req).execute();
        try {
            String text = bodyString(resp);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("http", resp.code());
                d.put("stem_list", listCsv.toString());
                d.put("bodyHead", text != null && text.length() > 240
                        ? text.substring(0, 240) : text);
                com.solar.launcher.Debug543e15Log.log(
                        "LalalClient.startMultistem:response",
                        resp.isSuccessful() ? "multistem accepted" : "multistem rejected",
                        resp.code() == 422 ? "H-A" : "H-C",
                        d);
            } catch (Exception ignored) {}
            // #endregion
            checkLalalError(resp, text, "Multistem");
            JSONObject json = new JSONObject(text);
            String taskId = json.optString("task_id", "");
            if (taskId.isEmpty()) throw new IOException("No task_id: " + text);
            return taskId;
        } finally {
            resp.close();
        }
    }

    /** @deprecated Use {@link #startMultistem(String, String[])}. */
    String startMultistem(String sourceId) throws Exception {
        return startMultistem(sourceId, MULTISTEM_IDS);
    }

    /**
     * Poll until every task succeeds; merge stem→url maps.
     * 2026-07-19
     */
    Map<String, String> pollUntilAllDone(String[] taskIds, Progress progress) throws Exception {
        if (taskIds == null || taskIds.length == 0) {
            throw new IOException("No stem tasks");
        }
        Map<String, String> merged = new HashMap<String, String>();
        boolean[] done = new boolean[taskIds.length];
        int finished = 0;
        long deadline = System.currentTimeMillis() + 20L * 60L * 1000L;
        while (finished < taskIds.length && System.currentTimeMillis() < deadline) {
            throwIfCancelled();
            JSONObject body = new JSONObject();
            JSONArray ids = new JSONArray();
            for (int i = 0; i < taskIds.length; i++) {
                if (!done[i]) ids.put(taskIds[i]);
            }
            body.put("task_ids", ids);

            Request req = new Request.Builder()
                    .url(BASE + "/api/v1/check/")
                    .header("X-License-Key", licenseKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"), body.toString()))
                    .build();
            Response resp = client.newCall(req).execute();
            String text;
            try {
                text = bodyString(resp);
                checkLalalError(resp, text, "Check");
            } finally {
                resp.close();
            }

            JSONObject root = new JSONObject(text);
            JSONObject resultMap = root.optJSONObject("result");
            if (resultMap == null) throw new IOException("Check missing result: " + text);

            int minPct = 89;
            for (int i = 0; i < taskIds.length; i++) {
                if (done[i]) continue;
                JSONObject task = resultMap.optJSONObject(taskIds[i]);
                if (task == null) continue;
                String status = task.optString("status", "");
                if ("progress".equals(status)) {
                    int pct = task.optInt("progress", 10);
                    if (pct < minPct) minPct = pct;
                    continue;
                }
                if ("success".equals(status)) {
                    JSONObject payload = task.optJSONObject("result");
                    if (payload == null) throw new IOException("Success without result");
                    JSONArray tracks = payload.optJSONArray("tracks");
                    if (tracks == null) throw new IOException("Success without tracks");
                    for (int t = 0; t < tracks.length(); t++) {
                        JSONObject tr = tracks.getJSONObject(t);
                        String type = tr.optString("type", "");
                        String label = tr.optString("label", "");
                        String url = tr.optString("url", "");
                        if (label.isEmpty() || url.isEmpty()) continue;
                        // Stems + residual back track (no_multistem) for Melody premix.
                        if ("stem".equals(type) || "back".equals(type)) {
                            merged.put(label, url);
                        }
                    }
                    done[i] = true;
                    finished++;
                    continue;
                }
                if ("error".equals(status) || "server_error".equals(status)
                        || "cancelled".equals(status)) {
                    String err = task.optString("error", status);
                    throw new IOException("Lalal " + status + ": " + err);
                }
            }
            if (finished < taskIds.length) {
                if (progress != null) {
                    // Split band 10–69.
                    int pct = 10 + (finished * 30) / taskIds.length + Math.max(0, minPct) / 3;
                    if (pct < 10) pct = 10;
                    if (pct > 69) pct = 69;
                    emit(progress, "split", pct, "Separating… " + minPct + "%");
                }
                Thread.sleep(2500);
            }
        }
        if (finished < taskIds.length) {
            throw new IOException("Stem separation timed out");
        }
        return merged;
    }

    /** Poll /check until success; map stem label → download URL. */
    Map<String, String> pollUntilDone(String taskId, Progress progress) throws Exception {
        return pollUntilAllDone(new String[] { taskId }, progress);
    }

    void download(String url, File dest) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("X-License-Key", licenseKey)
                .get()
                .build();
        Response resp = client.newCall(req).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Download HTTP " + resp.code());
            }
            InputStream in = resp.body().byteStream();
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    throwIfCancelled();
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            resp.close();
        }
    }

    private static String bodyString(Response resp) throws IOException {
        if (resp.body() == null) return "";
        return resp.body().string();
    }

    /** Cache folder name from track path + mtime. */
    public static String cacheKeyFor(File track) {
        if (track == null) return "unknown";
        String path = track.getAbsolutePath();
        long mt = track.lastModified();
        long sz = track.length();
        return Integer.toHexString((path + "|" + mt + "|" + sz).hashCode());
    }

    /** Stem cache under layout + live/premix mode. 2026-07-19 */
    public static File stemCacheDir(File appCache, File track, boolean premix) {
        return new File(new File(appCache, "lalal_stems"), cacheLeaf(track, premix));
    }

    /** @deprecated Prefer overload with premix flag. */
    public static File stemCacheDir(File appCache, File track) {
        return stemCacheDir(appCache, track, false);
    }

    /**
     * Ready when core three MP3s + at least one Melody/Other file (wav or live MP3s).
     * 2026-07-19
     */
    public static boolean cacheReady(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        for (String id : CORE_IDS) {
            File f = new File(dir, id + ".mp3");
            if (!f.isFile() || f.length() < 100) return false;
        }
        File mel = new File(dir, StemOtherPremix.MELODY_WAV);
        if (mel.isFile() && mel.length() >= 100) return true;
        return countOtherStemFiles(dir) >= 1;
    }

    /** How many Melody/Other files are on disk (paths de-duped). 2026-07-19 */
    static int countOtherStemFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File mel = new File(dir, StemOtherPremix.MELODY_WAV);
        if (mel.isFile() && mel.length() >= 100) return 1;
        java.util.HashSet<String> paths = new java.util.HashSet<String>();
        File alias = resolveMelodyFile(dir);
        if (alias != null) paths.add(alias.getAbsolutePath());
        for (String id : ALL_OTHER_IDS) {
            File f = resolveStemFile(dir, id);
            if (f != null) paths.add(f.getAbsolutePath());
        }
        return paths.size();
    }

    /**
     * Stage stem files from MicroSD / external storage into internal flash storage for stutter-free playback.
     * Keeps most recently played stems in internal storage and clears oldest stem folders when space is needed.
     * ponytail: user requested internal storage cache for stem mixing sessions to eliminate MicroSD read bottlenecks.
     */
    public static File ensureInternalPlaybackCache(android.content.Context ctx, File track,
            boolean premix, File readyDir) {
        if (ctx == null || readyDir == null || !readyDir.isDirectory() || track == null) return readyDir;
        try {
            File appCache = ctx.getCacheDir();
            if (appCache == null) return readyDir;
            String path = readyDir.getAbsolutePath();
            if (path.startsWith(appCache.getAbsolutePath())) {
                touchStemDirLru(readyDir);
                return readyDir;
            }
            File internalRoot = com.solar.launcher.DeviceFeatures.getInternalStorageRoot();
            if (internalRoot != null && path.startsWith(internalRoot.getAbsolutePath())) {
                touchStemDirLru(readyDir);
                return readyDir;
            }
            File internalVault = new File(appCache, "lalal_stems");
            if (!internalVault.exists()) internalVault.mkdirs();
            String leaf = cacheLeaf(track, premix);
            File internalTarget = new File(internalVault, leaf);
            if (cacheReadyFlexible(internalTarget) || cacheReady(internalTarget)) {
                touchStemDirLru(internalTarget);
                return internalTarget;
            }
            long needBytes = StemDurableRoots.needBytes(premix);
            clearInternalStemGarbage(internalVault, needBytes, internalTarget);
            if (!com.solar.launcher.StreamCacheRoot.hasSpace(internalVault, needBytes)) {
                return readyDir;
            }
            if (!internalTarget.exists()) internalTarget.mkdirs();
            File[] files = readyDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (f != null && f.isFile()) {
                        copyFile(f, new File(internalTarget, f.getName()));
                    }
                }
            }
            writeTrackMarker(internalTarget, track);
            touchStemDirLru(internalTarget);
            if (cacheReadyFlexible(internalTarget) || cacheReady(internalTarget)) {
                return internalTarget;
            }
        } catch (Exception ignored) {}
        return readyDir;
    }

    private static void touchStemDirLru(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            long now = System.currentTimeMillis();
            dir.setLastModified(now);
            File marker = new File(dir, TRACK_MARKER);
            if (marker.exists()) marker.setLastModified(now);
        } catch (Exception ignored) {}
    }

    public static void clearInternalStemGarbage(File internalVault, long needBytes, File skipDir) {
        if (internalVault == null || !internalVault.isDirectory()) return;
        while (!com.solar.launcher.StreamCacheRoot.hasSpace(internalVault, needBytes)) {
            File[] kids = internalVault.listFiles();
            if (kids == null || kids.length == 0) break;
            File oldest = null;
            long oldestTime = Long.MAX_VALUE;
            int validCount = 0;
            for (int i = 0; i < kids.length; i++) {
                File d = kids[i];
                if (d == null || !d.isDirectory() || sameDir(d, skipDir)) continue;
                validCount++;
                long mod = d.lastModified();
                File m = new File(d, TRACK_MARKER);
                if (m.exists()) mod = Math.max(mod, m.lastModified());
                if (mod < oldestTime) {
                    oldestTime = mod;
                    oldest = d;
                }
            }
            if (oldest == null || validCount <= 0) break;
            clearDirQuiet(oldest);
        }
    }

    /**
     * Load stem files for playback, staging from MicroSD to internal flash storage (`lalal_stems`) if needed
     * to eliminate MicroSD controller contention and audio stutter during stem mixing sessions.
     */
    public static List<StemFile> resolveStemsFromReadyDir(android.content.Context ctx, File track,
            boolean premix, File readyDir) {
        if (readyDir == null) return null;
        File stagedDir = ensureInternalPlaybackCache(ctx, track, premix, readyDir);
        List<StemFile> cached = null;
        if (stagedDir != null && (cacheReadyFlexible(stagedDir) || cacheReady(stagedDir))) {
            cached = loadCached(stagedDir, premix);
            if (cached == null || cached.isEmpty()) {
                cached = loadStemDirFlexible(stagedDir);
            }
        }
        if (cached != null && !cached.isEmpty()) return cached;
        if (userStemsReady(track) && readyDir.equals(userStemsDir(track))) {
            cached = loadUserStems(track, premix);
        } else {
            cached = loadCached(readyDir, premix);
            if (cached == null || cached.isEmpty()) {
                cached = loadStemDirFlexible(readyDir);
            }
        }
        return cached;
    }

    /**
     * Load cache for playback. Premix blends Melody; otherwise collapse to one pad/zone.
     * Layman: Y1 only juggles four streams — many Melody files made 3-song mashups crawl.
     * Was: live multi-Melody returned every other MP3 (7+ players/song). Reversal: return flex raw.
     * 2026-07-19
     */
    public static List<StemFile> loadCached(File dir, boolean premixExperimental) {
        List<StemFile> flex = loadStemDirFlexible(dir);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("dir", dir != null ? dir.getName() : "");
            d.put("premix", premixExperimental);
            d.put("flexCount", flex != null ? flex.size() : -1);
            int others = 0;
            StringBuilder ids = new StringBuilder();
            if (flex != null) {
                for (int i = 0; i < flex.size(); i++) {
                    StemFile s = flex.get(i);
                    if (s == null) continue;
                    if (ids.length() > 0) ids.append(',');
                    ids.append(s.id).append(':').append(s.zone);
                    if (s.zone == 3) others++;
                }
            }
            d.put("flexIds", ids.toString());
            d.put("otherCount", others);
            com.solar.launcher.Debug75a361Log.log(
                    "LalalClient.loadCached",
                    premixExperimental ? "premix path" : "live collapse path",
                    premixExperimental ? "C" : "A",
                    d);
        } catch (Exception ignored) {}
        // #endregion
        if (premixExperimental) {
            int others = 0;
            for (int i = 0; i < flex.size(); i++) {
                if (flex.get(i).zone == 3) others++;
            }
            if (others <= 1) return flex;
            try {
                return premixToFourPadsStatic(flex, dir, null, null);
            } catch (Exception e) {
                return collapseToOnePadPerZone(flex);
            }
        }
        return collapseToOnePadPerZone(flex);
    }

    /**
     * Keep ≤1 MediaPlayer per Stem pad (zones 0–3). Prefer melody.wav / aliases for zone 3.
     * Layman: one file per pad so mashups stay playable on a small chip.
     * Technical: first hit wins for 0–2; zone 3 prefers an explicit Melody alias,
     * then the largest usable named/residual candidate.
     * Was: all OTHER_IDS as separate zone-3 players. Reversal: return input list unchanged.
     * 2026-07-19
     */
    public static List<StemFile> collapseToOnePadPerZone(List<StemFile> stems) {
        List<StemFile> out = new ArrayList<StemFile>();
        if (stems == null || stems.isEmpty()) return out;
        StemFile[] byZone = new StemFile[4];
        StemFile melodyAlias = null;
        StemFile melodyResidual = null;
        StemFile melodyOther = null;
        // #region agent log
        StringBuilder z3Ids = new StringBuilder();
        StringBuilder z3Sizes = new StringBuilder();
        // #endregion
        for (int i = 0; i < stems.size(); i++) {
            StemFile s = stems.get(i);
            if (s == null || s.file == null || !s.file.isFile()) continue;
            int z = s.zone;
            if (z < 0 || z > 3) z = 3;
            if (z < 3) {
                if (byZone[z] == null) byZone[z] = s;
                continue;
            }
            String id = s.id != null ? s.id : "";
            // #region agent log
            if (z3Ids.length() > 0) {
                z3Ids.append(',');
                z3Sizes.append(',');
            }
            z3Ids.append(id);
            z3Sizes.append(s.file.length());
            // #endregion
            if ("melody".equals(id) || "other".equals(id)
                    || "instruments".equals(id) || "samples".equals(id)
                    || (s.file.getName() != null
                            && s.file.getName().toLowerCase().startsWith("melody"))) {
                melodyAlias = largerStem(melodyAlias, s);
            } else if (RESIDUAL_ID.equals(id)) {
                melodyResidual = largerStem(melodyResidual, s);
            } else {
                melodyOther = largerStem(melodyOther, s);
            }
        }
        // Explicit user-produced Melody aliases win. Otherwise use the strongest
        // named/residual candidate instead of allowing a tiny residual scrap to
        // discard a complete piano or guitar stem.
        String pickReason;
        if (melodyAlias != null) {
            byZone[3] = melodyAlias;
            pickReason = "alias";
        } else if (melodyOther != null && (melodyResidual == null
                || melodyOther.file.length() > melodyResidual.file.length())) {
            byZone[3] = melodyOther;
            pickReason = "largest_named";
        } else if (melodyResidual != null) {
            byZone[3] = melodyResidual;
            pickReason = "largest_residual";
        } else {
            byZone[3] = melodyOther;
            pickReason = "named_other";
        }
        for (int z = 0; z < 4; z++) {
            if (byZone[z] != null) out.add(byZone[z]);
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("z3Candidates", z3Ids.toString());
            d.put("z3Sizes", z3Sizes.toString());
            d.put("pickReason", pickReason);
            StemFile mel = byZone[3];
            d.put("pickedId", mel != null ? mel.id : "");
            d.put("pickedBytes", mel != null && mel.file != null ? mel.file.length() : -1);
            d.put("hadAcoustic", z3Ids.toString().contains("acoustic_guitar"));
            d.put("hadElectric", z3Ids.toString().contains("electric_guitar"));
            d.put("hadPiano", z3Ids.toString().contains("piano"));
            d.put("hadResidual", z3Ids.toString().contains(RESIDUAL_ID));
            d.put("discardedNamed", "largest_residual".equals(pickReason)
                    && (z3Ids.toString().contains("acoustic_guitar")
                    || z3Ids.toString().contains("electric_guitar")
                    || z3Ids.toString().contains("piano")));
            com.solar.launcher.Debug75a361Log.log(
                    "LalalClient.collapseToOnePadPerZone",
                    "melody pad pick",
                    "A",
                    d);
        } catch (Exception ignored) {}
        // #endregion
        return out;
    }

    private static StemFile largerStem(StemFile current, StemFile candidate) {
        if (candidate == null || candidate.file == null) return current;
        if (current == null || current.file == null) return candidate;
        return candidate.file.length() > current.file.length() ? candidate : current;
    }

    /** @deprecated Prefer overload with premix flag (defaults live). */
    public static List<StemFile> loadCached(File dir) {
        return loadCached(dir, false);
    }

    public static String labelForZone(int zone) {
        if (zone < 0 || zone >= STEM_LABELS.length) return "";
        return STEM_LABELS[zone];
    }

    /**
     * Map Lalal stem id → Stem Player zone (0..3). Unknown → Melody/Other.
     * 2026-07-19
     */
    public static int zoneForId(String id) {
        if ("vocals".equals(id)) return 0;
        if ("drum".equals(id) || "drums".equals(id)) return 1;
        if ("bass".equals(id)) return 2;
        return 3;
    }

    /**
     * User-prepared stems folder next to the track: {@code Song.mp3} → {@code Song.stems/}.
     * Layman: drop MP3s beside the song so Stem Player skips the cloud split.
     * 2026-07-19
     */
    public static File userStemsDir(File track) {
        if (track == null) return null;
        File parent = track.getParentFile();
        if (parent == null) return null;
        String name = track.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(parent, base + ".stems");
    }

    /**
     * True when a file or folder belongs to Stem Player downloads — never library-ingest.
     * Layman: vocals/drums under Song.stems or lalal_stems are pads, not songs.
     * Tech: path segment ends with {@code .stems}, or equals {@code lalal_stems}/{@code lalal_work}.
     * Was: Music walk indexed stem MP3s as tracks. Reversal: remove this gate.
     * 2026-07-19
     */
    public static boolean isStemLibraryArtifact(File f) {
        if (f == null) return false;
        // 2026-07-19 — Sibling .instrumentals / .acapellas never enter the music library.
        if (SoloStemPaths.isSiblingSoloPath(f)) return true;
        File cur = f;
        // Walk parents so Song.stems/vocals.mp3 and Song.stems itself both match.
        while (cur != null) {
            String name = cur.getName();
            if (name != null && name.length() > 0) {
                if (name.endsWith(".stems")) return true;
                // 2026-07-19 — Solo NP cache + work/stems leaves are pads, not library songs.
                if ("lalal_stems".equals(name) || "lalal_work".equals(name)
                        || "lalal_solo".equals(name) || "stem_solo".equals(name)
                        || SoloStemPaths.DIR_INSTRUMENTALS.equals(name)
                        || SoloStemPaths.DIR_ACAPELLAS.equals(name)) {
                    return true;
                }
            }
            File parent = cur.getParentFile();
            if (parent == null || parent.equals(cur)) break;
            cur = parent;
        }
        return false;
    }

    /**
     * True when this library track has stems on disk (never for pad/sidecar files).
     * Layman: song is ready for Stem Player without uploading again.
     * Technical: reject {@link #isStemLibraryArtifact} then {@link #trackStemsReady}.
     * 2026-07-19
     */
    public static boolean originatingTrackHasStems(android.content.Context ctx, File track,
            boolean premix, File appCache) {
        if (track == null || !track.isFile()) return false;
        if (isStemLibraryArtifact(track)) return false;
        return trackStemsReady(ctx, track, premix, appCache);
    }

    /**
     * Fast Has Stems index — invert from disk + cheap sidecar checks (background thread).
     * Layman: find songs that already have stem folders without poking every track deeply.
     * Was: per-track {@link #trackStemsReady} on UI (froze large libs). Reversal: that loop.
     * 2026-07-19
     */
    public static java.util.HashSet<String> indexReadyOriginatingPaths(
            android.content.Context ctx, java.util.List<File> libraryTracks, File appCache) {
        // 2026-07-20 — Build path→size then share SEGMENTED map path.
        java.util.HashMap<String, Long> pathToSize = new java.util.HashMap<String, Long>();
        if (libraryTracks != null) {
            for (int i = 0; i < libraryTracks.size(); i++) {
                File t = libraryTracks.get(i);
                if (t == null || !t.isFile() || isStemLibraryArtifact(t)) continue;
                long sz = t.length();
                if (sz <= 0L) continue;
                pathToSize.put(t.getAbsolutePath(), Long.valueOf(sz));
            }
        }
        return indexReadyOriginatingPaths(ctx, pathToSize, appCache);
    }

    /**
     * 2026-07-20 — Has Stems index from SQLite path→size (SEGMENTED empty customLibrary).
     * Layman: match stem folders to library songs using DB sizes, not a full in-RAM song list.
     * Technical: basename|size map + sidecar probe + cache/work leaf scan.
     * Was: require List&lt;File&gt; from customLibrary (empty under SEGMENTED). Reversal: File-list API only.
     */
    public static java.util.HashSet<String> indexReadyOriginatingPaths(
            android.content.Context ctx, java.util.Map<String, Long> pathToSize, File appCache) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        if (pathToSize == null || pathToSize.isEmpty()) return out;
        // basename|size → absolute path for marker match (size required). 2026-07-19/20
        java.util.HashMap<String, String> byBaseSize = new java.util.HashMap<String, String>();
        for (java.util.Map.Entry<String, Long> e : pathToSize.entrySet()) {
            if (e == null) continue;
            String path = e.getKey();
            Long sizeObj = e.getValue();
            if (path == null || path.length() == 0 || sizeObj == null) continue;
            long size = sizeObj.longValue();
            if (size <= 0L) continue;
            File t = new File(path);
            if (isStemLibraryArtifact(t)) continue;
            String base = trackBaseName(t).toLowerCase();
            byBaseSize.put(base + "|" + size, path);
            // Cheap sidecar: Song.stems next to track (needs file on disk). 2026-07-19
            if (t.isFile() && userStemsReady(t)) out.add(path);
        }
        java.util.List<File> roots = stemCacheRoots(ctx, appCache);
        for (int ri = 0; ri < roots.size(); ri++) {
            File root = roots.get(ri);
            if (root == null || !root.isDirectory()) continue;
            File[] kids = root.listFiles();
            if (kids == null) continue;
            for (int ki = 0; ki < kids.length; ki++) {
                File d = kids[ki];
                if (d == null || !d.isDirectory()) continue;
                if (!cacheReadyFlexible(d) && !cacheReady(d)) continue;
                String matched = matchLibraryPathFromStemDir(d, byBaseSize);
                if (matched != null) out.add(matched);
            }
        }
        // Work dir scratch leaves. 2026-07-19
        if (ctx != null) {
            File workRoot = new File(ctx.getCacheDir(), "lalal_work");
            File[] kids = workRoot.isDirectory() ? workRoot.listFiles() : null;
            if (kids != null) {
                for (int ki = 0; ki < kids.length; ki++) {
                    File d = kids[ki];
                    if (d == null || !d.isDirectory()) continue;
                    if (!cacheReadyFlexible(d) && !cacheReady(d)) continue;
                    String matched = matchLibraryPathFromStemDir(d, byBaseSize);
                    if (matched != null) out.add(matched);
                }
            }
        }
        return out;
    }

    /**
     * Resolve stem leaf → library only when marker basename+size match AND leaf hash owns track.
     * 2026-07-19
     */
    private static String matchLibraryPathFromStemDir(File dir,
            java.util.HashMap<String, String> byBaseSize) {
        if (dir == null) return null;
        File marker = new File(dir, TRACK_MARKER);
        if (!marker.isFile() || marker.length() < 1) return null;
        try {
            FileInputStream in = new FileInputStream(marker);
            byte[] buf = new byte[(int) Math.min(marker.length(), 512)];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            String text = new String(buf, 0, n, "UTF-8");
            String[] lines = text.split("\n");
            if (lines.length < 2) return null;
            String base = lines[0].trim().toLowerCase();
            long sz = -1L;
            try { sz = Long.parseLong(lines[1].trim()); } catch (Exception ignored) {}
            if (sz <= 0) return null;
            String path = byBaseSize.get(base + "|" + sz);
            if (path == null) return null;
            File track = new File(path);
            if (!stemDirOwnedByTrack(dir, track)) return null;
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * True when a sidecar {@code *.stems} folder has vocals + drums + bass + Melody/Other.
     * Melody may be one file ({@code melody.mp3} / {@code other.mp3} / …) or Lalal other ids.
     * 2026-07-19
     */
    public static boolean userStemsReady(File track) {
        return cacheReadyFlexible(userStemsDir(track));
    }

    /**
     * True when this track already has playable stems on disk (skip Lalal).
     * Layman: user folder or any previous download — live or premix, internal or card.
     * Technical: probe user + every cache home for live AND premix leaves (space-flip safe).
     * Was: only durableStemDir(premix) + legacy (missed overflow when space recovered).
     * Reversal: check single durableStemDir(ctx,track,premix) only.
     * 2026-07-19
     */
    /**
     * Check if original track playback is forced, disabling Stems fallback. 
     * Controlled via the .useoriginal marker file.
     */
    public static boolean isOriginalForced(File track) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            return new File(stemsDir, ".useoriginal").exists();
        }
        return false;
    }

    public static void setOriginalForced(File track, boolean forced) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            stemsDir.mkdirs();
            File marker = new File(stemsDir, ".useoriginal");
            if (forced) {
                try { marker.createNewFile(); } catch (IOException ignored) {}
            } else {
                marker.delete();
            }
        }
    }

    public static boolean isVocalsMuted(File track) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            return new File(stemsDir, ".mutevocals").exists();
        }
        return false;
    }

    public static void setVocalsMuted(File track, boolean muted) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            stemsDir.mkdirs();
            File marker = new File(stemsDir, ".mutevocals");
            if (muted) {
                try { marker.createNewFile(); } catch (IOException ignored) {}
            } else {
                marker.delete();
            }
        }
    }

    public static boolean isInstrumentsMuted(File track) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            return new File(stemsDir, ".muteinstr").exists();
        }
        return false;
    }

    public static void setInstrumentsMuted(File track, boolean muted) {
        File stemsDir = userStemsDir(track);
        if (stemsDir != null) {
            stemsDir.mkdirs();
            File marker = new File(stemsDir, ".muteinstr");
            if (muted) {
                try { marker.createNewFile(); } catch (IOException ignored) {}
            } else {
                marker.delete();
            }
        }
    }

    public static boolean trackStemsReady(android.content.Context ctx, File track,
            boolean premix, File appCache) {
        return findReadyStemDir(ctx, track, premix, appCache) != null;
    }

    /**
     * First ready stem folder for this track (prefer requested premix mode, then the other).
     * Probe order: user sidecar → exact/alias leaves → basename marker scan → duration heuristic.
     * @return null if none — caller may Lalal
     * Was: only current cacheLeaf under a few roots. Reversal: drop scanStemRootsForTrack.
     * 2026-07-19
     */
    /**
     * Stamp marker only when this leaf already belongs to the track.
     * Was: write on any cache hit — duration false-positives rewrote Glue as Headlock.
     * Reversal: unconditional writeTrackMarker after hit.
     * 2026-07-19
     */
    public static void writeTrackMarkerIfOwned(File dir, File track) {
        if (dir == null || track == null) return;
        String leaf = dir.getName();
        String key = cacheKeyStable(track);
        boolean leafOurs = leaf != null && key != null && leaf.indexOf(key) >= 0;
        if (leafOurs || (leaf != null && leaf.endsWith(".stems") && markerMatchesTrack(dir, track))) {
            writeTrackMarker(dir, track);
        }
    }

    public static File findReadyStemDir(android.content.Context ctx, File track,
            boolean preferPremix, File appCache) {
        if (track == null || !track.isFile()) return null;
        if (userStemsReady(track)) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "userSidecar");
                d.put("readyDir", userStemsDir(track).getAbsolutePath());
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "hit", "H-A", d);
            } catch (Exception ignored) {}
            // #endregion
            return userStemsDir(track);
        }
        // Prefer matching mode, then opposite — never re-upload when either exists. 2026-07-19
        File hit = firstReadyAmong(stemCacheCandidates(ctx, track, preferPremix, appCache));
        if (hit != null) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "candidatesPrefer");
                d.put("readyDir", hit.getAbsolutePath());
                d.put("markerOk", markerMatchesTrack(hit, track));
                d.put("stableKey", cacheKeyStable(track));
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "hit", "H-B", d);
            } catch (Exception ignored) {}
            // #endregion
            writeTrackMarkerIfOwned(hit, track);
            return hit;
        }
        hit = firstReadyAmong(stemCacheCandidates(ctx, track, !preferPremix, appCache));
        if (hit != null) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "candidatesOther");
                d.put("readyDir", hit.getAbsolutePath());
                d.put("markerOk", markerMatchesTrack(hit, track));
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "hit", "H-B", d);
            } catch (Exception ignored) {}
            // #endregion
            writeTrackMarkerIfOwned(hit, track);
            return hit;
        }
        hit = scanStemRootsForTrack(ctx, track, appCache, preferPremix);
        if (hit != null) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "scanPrefer");
                d.put("readyDir", hit.getAbsolutePath());
                d.put("markerOk", markerMatchesTrack(hit, track));
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "hit", "H-C,H-D", d);
            } catch (Exception ignored) {}
            // #endregion
            writeTrackMarkerIfOwned(hit, track);
            return hit;
        }
        hit = scanStemRootsForTrack(ctx, track, appCache, !preferPremix);
        if (hit != null) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "scanOther");
                d.put("readyDir", hit.getAbsolutePath());
                d.put("markerOk", markerMatchesTrack(hit, track));
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "hit", "H-C,H-D", d);
            } catch (Exception ignored) {}
            // #endregion
            writeTrackMarkerIfOwned(hit, track);
        } else {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("track", track.getName());
                d.put("path", track.getAbsolutePath());
                d.put("branch", "miss");
                com.solar.launcher.Debug8b0481Log.log(
                        "LalalClient.findReadyStemDir", "miss will Lalal", "H-E", d);
            } catch (Exception ignored) {}
            // #endregion
        }
        return hit;
    }

    private static File firstReadyAmong(java.util.List<File> dirs) {
        if (dirs == null) return null;
        for (int i = 0; i < dirs.size(); i++) {
            File d = dirs.get(i);
            if (cacheReady(d) || cacheReadyFlexible(d)) return d;
        }
        return null;
    }

    /**
     * All places we may have published stems for this track+mode (deduped).
     * Includes every layout×key alias leaf under app/cache/work/overflow homes.
     * Always includes overflow media path — do not gate on free space (read path).
     * 2026-07-19
     */
    public static java.util.List<File> stemCacheCandidates(android.content.Context ctx,
            File track, boolean premix, File appCache) {
        java.util.ArrayList<File> out = new java.util.ArrayList<File>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        java.util.List<String> leaves = cacheLeafAliases(track, premix);
        java.util.List<File> roots = stemCacheRoots(ctx, appCache);
        for (int ri = 0; ri < roots.size(); ri++) {
            File root = roots.get(ri);
            if (root == null) continue;
            for (int li = 0; li < leaves.size(); li++) {
                addCandidate(out, seen, new File(root, leaves.get(li)));
            }
        }
        // Work dir uses same leaves (scratch that may still hold ready pads). 2026-07-19
        if (ctx != null) {
            File workRoot = new File(ctx.getCacheDir(), "lalal_work");
            for (int li = 0; li < leaves.size(); li++) {
                addCandidate(out, seen, new File(workRoot, leaves.get(li)));
            }
        }
        return out;
    }

    /**
     * Parent folders that may contain stem leaf dirs (app + internal MMC before MicroSD).
     * Layman: look for saved stems on the chip first, then the card.
     * Was: getNewMediaRoot then micro then internal. Reversal: restore that order.
     * 2026-07-19 / 2026-07-21
     */
    public static java.util.List<File> stemCacheRoots(android.content.Context ctx, File appCache) {
        java.util.ArrayList<File> roots = new java.util.ArrayList<File>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        addRoot(roots, seen, appCache != null ? new File(appCache, "lalal_stems") : null);
        if (ctx != null) {
            File cache = ctx.getCacheDir();
            addRoot(roots, seen, cache != null ? new File(cache, "lalal_stems") : null);
            try {
                String pkg = ctx.getPackageName();
                // Internal volume before MicroSD (matches durable pick). 2026-07-21
                addRoot(roots, seen, StemDurableRoots.volumeVault(
                        com.solar.launcher.DeviceFeatures.getInternalStorageRoot(), pkg));
                addRoot(roots, seen, StemDurableRoots.volumeVault(
                        com.solar.launcher.DeviceFeatures.getMicroSdRoot(), pkg));
                // Legacy Primary-pref vault if still different from the two above. 2026-07-21
                File media = com.solar.launcher.DeviceFeatures.getNewMediaRoot(ctx);
                addRoot(roots, seen, StemDurableRoots.volumeVault(media, pkg));
            } catch (Exception ignored) {}
        }
        return roots;
    }

    private static void addRoot(java.util.ArrayList<File> roots, java.util.HashSet<String> seen,
            File root) {
        if (root == null) return;
        String p = root.getAbsolutePath();
        if (seen.contains(p)) return;
        seen.add(p);
        roots.add(root);
    }

    /**
     * Walk stem roots for a ready leaf matching this track by {@link #TRACK_MARKER} only.
     * Layman: only reuse a stem folder if it is labeled for this song.
     * Was: also matched by vocals duration (±2.5s) — cross-linked Headlock↔Glue.
     * Reversal: restore duration block below.
     * 2026-07-19
     */
    public static File scanStemRootsForTrack(android.content.Context ctx, File track,
            File appCache, boolean premixHint) {
        if (track == null) return null;
        String wantMode = premixHint ? "_premix_" : "_live_";
        File bestMarked = null;
        long bestMarkedMt = -1L;
        java.util.List<File> roots = stemCacheRoots(ctx, appCache);
        // Also scan lalal_work. 2026-07-19
        if (ctx != null && ctx.getCacheDir() != null) {
            File wr = new File(ctx.getCacheDir(), "lalal_work");
            String p = wr.getAbsolutePath();
            boolean have = false;
            for (int i = 0; i < roots.size(); i++) {
                if (p.equals(roots.get(i).getAbsolutePath())) { have = true; break; }
            }
            if (!have) roots.add(wr);
        }
        for (int ri = 0; ri < roots.size(); ri++) {
            File root = roots.get(ri);
            if (root == null || !root.isDirectory()) continue;
            File[] kids = root.listFiles();
            if (kids == null) continue;
            for (int ki = 0; ki < kids.length; ki++) {
                File d = kids[ki];
                if (d == null || !d.isDirectory()) continue;
                String leaf = d.getName();
                boolean modeOk = leaf != null && leaf.indexOf(wantMode) >= 0;
                if (!cacheReady(d) && !cacheReadyFlexible(d)) continue;
                if (stemDirOwnedByTrack(d, track)) {
                    long mt = d.lastModified();
                    if (modeOk || bestMarked == null) {
                        if (mt >= bestMarkedMt) {
                            bestMarked = d;
                            bestMarkedMt = mt;
                        }
                    }
                    // #region agent log
                    try {
                        org.json.JSONObject dlog = new org.json.JSONObject();
                        dlog.put("track", track.getName());
                        dlog.put("leaf", leaf);
                        dlog.put("how", "ownedMarker");
                        dlog.put("modeOk", modeOk);
                        com.solar.launcher.Debug8b0481Log.log(
                                "LalalClient.scanStemRootsForTrack", "candidate", "H-C", dlog);
                    } catch (Exception ignored) {}
                    // #endregion
                }
                // Duration fallback removed 2026-07-19 (H-D false positives).
            }
        }
        return bestMarked;
    }

    /**
     * True when a stem leaf’s vocals duration is within ~2.5s of the source track.
     * 2026-07-19
     */
    static boolean stemDirDurationMatches(File dir, int trackMs) {
        if (dir == null || trackMs < 1000) return false;
        File vocals = resolveStemFile(dir, "vocals");
        if (vocals == null) return false;
        int vMs = probeDurationMsQuiet(vocals);
        if (vMs < 1000) return false;
        return Math.abs(vMs - trackMs) <= 2500;
    }

    /** Best-effort duration; 0 on failure (unit tests / missing MMR). 2026-07-19 */
    static int probeDurationMsQuiet(File f) {
        if (f == null || !f.isFile()) return 0;
        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                int ms = Integer.parseInt(d);
                if (ms > 0) return ms;
            }
        } catch (Throwable ignored) {
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static void addCandidate(java.util.ArrayList<File> out,
            java.util.HashSet<String> seen, File dir) {
        if (dir == null) return;
        String p = dir.getAbsolutePath();
        if (seen.contains(p)) return;
        seen.add(p);
        out.add(dir);
    }

    /**
     * Load sidecar user stems. Premix only when experimental flag is on.
     * Otherwise collapse to one pad/zone for Y1 player budget. 2026-07-19
     */
    public static List<StemFile> loadUserStems(File track, boolean premixExperimental) {
        File dir = userStemsDir(track);
        List<StemFile> raw = loadStemDirFlexible(dir);
        if (raw.isEmpty()) return raw;
        if (!premixExperimental) return collapseToOnePadPerZone(raw);
        int otherCount = 0;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).zone == 3) otherCount++;
        }
        if (otherCount <= 1) return raw;
        try {
            return premixToFourPadsStatic(raw, dir, null, null);
        } catch (Exception e) {
            return collapseToOnePadPerZone(raw);
        }
    }

    /** @deprecated Prefer overload with premix flag. */
    public static List<StemFile> loadUserStems(File track) {
        return loadUserStems(track, false);
    }

    /**
     * Ready check that accepts {@code drums.mp3} and a single {@code melody.mp3} (or aliases)
     * instead of requiring Lalal {@code OTHER_IDS}. Used for user sidecars and v2 cache.
     * 2026-07-19
     */
    public static boolean cacheReadyFlexible(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        
        boolean hasVocals = false;
        boolean hasDrums = false;
        boolean hasBass = false;
        boolean hasMelody = false;
        
        for (File f : files) {
            if (!f.isFile() || f.length() < 100) continue;
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".mp3") && !name.endsWith(".wav") && !name.endsWith(".flac") 
                    && !name.endsWith(".m4a") && !name.endsWith(".aac") && !name.endsWith(".ogg")) {
                continue;
            }
            if (name.contains("vocals") || name.contains("vocal")) {
                hasVocals = true;
            } else if (name.contains("drums") || name.contains("drum")) {
                hasDrums = true;
            } else if (name.contains("bass")) {
                hasBass = true;
            } else {
                hasMelody = true;
            }
        }
        return hasVocals && hasDrums && hasBass && hasMelody;
    }

    /** Load every recognised stem file in a folder (core + other + melody aliases). 2026-07-19 */
    public static List<StemFile> loadStemDirFlexible(File dir) {
        List<StemFile> out = new java.util.ArrayList<StemFile>();
        if (dir == null || !dir.isDirectory()) return out;
        
        File[] files = dir.listFiles();
        if (files == null) return out;
        
        for (File f : files) {
            if (!f.isFile() || f.length() < 100) continue;
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".mp3") && !name.endsWith(".wav") && !name.endsWith(".flac") 
                    && !name.endsWith(".m4a") && !name.endsWith(".aac") && !name.endsWith(".ogg")) {
                continue;
            }
            String id = stripMp3(f.getName());
            if (name.contains("vocals") || name.contains("vocal")) {
                out.add(new StemFile(id, "Vocals", f, 0));
            } else if (name.contains("drums") || name.contains("drum")) {
                out.add(new StemFile(id, "Drums", f, 1));
            } else if (name.contains("bass")) {
                out.add(new StemFile(id, "Bass", f, 2));
            } else {
                out.add(new StemFile(id, "Melody", f, 3));
            }
        }
        return out;
    }

    private static boolean addIfPresent(List<StemFile> out, File dir, String id, int zone) {
        File f = resolveStemFile(dir, id);
        if (f == null) return false;
        out.add(new StemFile(id, labelForStemId(id), f, zone));
        return true;
    }

    /** {@code id.mp3} if present and non-tiny. */
    public static File resolveStemFile(File dir, String id) {
        if (dir == null || id == null) return null;
        File f = new File(dir, id + ".mp3");
        if (f.isFile() && f.length() >= 100) return f;
        return null;
    }

    /**
     * Single pre-mixed Melody/Other pad: melody, other, instruments, or samples.
     * 2026-07-19
     */
    public static File resolveMelodyFile(File dir) {
        String[] aliases = { "melody", "other", "instruments", "samples" };
        for (int i = 0; i < aliases.length; i++) {
            File f = resolveStemFile(dir, aliases[i]);
            if (f != null) return f;
        }
        return null;
    }

    private static String stripMp3(String name) {
        if (name == null) return "";
        if (name.length() > 4 && name.toLowerCase().endsWith(".mp3")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    /** Short label for status / logs. */
    public static String labelForStemId(String id) {
        if ("vocals".equals(id)) return "Vocals";
        if ("drum".equals(id) || "drums".equals(id)) return "Drums";
        if ("bass".equals(id)) return "Bass";
        if ("melody".equals(id) || "other".equals(id)
                || "instruments".equals(id) || "samples".equals(id)) {
            return "Melody";
        }
        if ("piano".equals(id)) return "Piano";
        if ("synthesizer".equals(id)) return "Synth";
        if ("electric_guitar".equals(id)) return "E.Guitar";
        if ("acoustic_guitar".equals(id)) return "A.Guitar";
        if ("strings".equals(id)) return "Strings";
        if ("wind".equals(id)) return "Wind";
        if ("no_multistem".equals(id)) return "Residual";
        return id != null ? id : "";
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
