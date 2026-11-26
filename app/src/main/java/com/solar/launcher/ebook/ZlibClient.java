package com.solar.launcher.ebook;

import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 2026-11-26 — Z-Library mobile EAPI client.
 * Layman: search Z-Library and pull a book straight onto the player.
 * Tech: the mobile endpoints (/eapi/...) are unofficial and unversioned, so every
 *   call walks the mirror list until one answers with success:1. Auth is the
 *   remix_userid/remix_userkey cookie pair (login endpoint is often refused).
 *   Reversal: delete this class + the E-Book tab's Z-Library rows.
 */
public final class ZlibClient {
    private static final MediaType FORM =
            MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") Solar";

    public static final class Book {
        public final String id;
        public final String hash;
        public final String title;
        public final String author;
        public final String year;
        public final String language;
        public final String extension;
        public final long filesize;

        Book(String id, String hash, String title, String author, String year,
             String language, String extension, long filesize) {
            this.id = id;
            this.hash = hash;
            this.title = title != null && title.trim().length() > 0 ? title.trim() : "Untitled";
            this.author = author != null ? author.trim() : "";
            this.year = year != null ? year.trim() : "";
            this.language = language != null ? language.trim() : "";
            this.extension = extension != null ? extension.trim().toLowerCase(Locale.US) : "epub";
            this.filesize = filesize;
        }

        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (author.length() > 0) sb.append(author);
            if (year.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(year);
            }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(extension.toUpperCase(Locale.US));
            if (filesize > 0) sb.append(" · ").append(EbookLibrary.formatSize(filesize));
            return sb.toString();
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private final SharedPreferences prefs;
    private final OkHttpClient http;
    private String domain;

    public ZlibClient(SharedPreferences prefs) {
        this.prefs = prefs;
        this.domain = ZlibAccount.domain(prefs);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    private String cookieHeader() {
        return "remix_userid=" + ZlibAccount.userId(prefs)
                + "; remix_userkey=" + ZlibAccount.userKey(prefs);
    }

    /** Mirrors to try, configured domain first. */
    private List<String> mirrors() {
        List<String> out = new ArrayList<String>();
        if (domain != null && domain.length() > 0) out.add(domain);
        for (String d : ZlibAccount.DOMAINS) {
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    public boolean profile() {
        try {
            JSONObject json = requestAny("/eapi/user/profile", null);
            return json != null && json.optInt("success", 0) == 1;
        } catch (IOException e) {
            return false;
        }
    }

    public List<Book> popular() throws IOException {
        JSONObject json = requestAny("/eapi/book/most-popular?limit=20&page=1", null);
        return books(json);
    }

    public List<Book> search(String query) throws IOException {
        String body = "message=" + urlEncode(query) + "&limit=20&page=1";
        JSONObject json = requestAny("/eapi/book/search", body);
        return books(json);
    }

    public String downloadLink(Book book) throws IOException {
        JSONObject json = requestAny("/eapi/book/" + book.id + "/" + book.hash + "/file", null);
        if (json == null) throw new IOException("No answer from Z-Library");
        // 2026-11-26 — Live EAPI answers {"file":{"downloadLink":"..."}}. Reading only the
        // older {"download":{"link":...}} shape made every tap fail as "Download unavailable".
        String link = "";
        JSONObject file = json.optJSONObject("file");
        if (file != null) link = file.optString("downloadLink", "");
        if (link.length() == 0) {
            JSONObject download = json.optJSONObject("download");
            if (download != null) link = download.optString("link", "");
        }
        if (link.length() == 0) link = json.optString("downloadLink", "");
        if (link.length() == 0) {
            String error = json.optString("error", "");
            throw new IOException(error.length() > 0 ? error : "Download unavailable");
        }
        return link.startsWith("http") ? link : (domain != null ? domain : "") + link;
    }

    /**
     * 2026-11-26 — Free accounts get about ten downloads a day and the profile is the only place
     * the server says how many are left. Returns -1 when it cannot be read (cookies/network).
     */
    public int downloadsLeft() {
        try {
            JSONObject json = requestAny("/eapi/user/profile", null);
            if (json == null || json.optInt("success", 0) != 1) return -1;
            JSONObject user = json.optJSONObject("user");
            if (user == null) return -1;
            int limit = user.optInt("downloads_limit", 10);
            return Math.max(0, limit - user.optInt("downloads_today", 0));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Streams the book into {@code target}; .part + rename keeps a half file out of the library. */
    public void download(Book book, File target, Progress progress) throws IOException {
        String link = downloadLink(book);
        File part = new File(target.getAbsolutePath() + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            Request req = new Request.Builder().url(link)
                    .header("Cookie", cookieHeader())
                    .header("User-Agent", USER_AGENT)
                    .build();
            Response res = http.newCall(req).execute();
            if (!res.isSuccessful()) {
                throw new IOException("Download failed (HTTP " + res.code() + ")");
            }
            in = res.body().byteStream();
            long total = res.body().contentLength();
            out = new FileOutputStream(part);
            byte[] buf = new byte[16 * 1024];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100L / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        if (progress != null) progress.onProgress(pct);
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            res.close();
            if (!part.renameTo(target)) {
                throw new IOException("Could not save the book");
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (part.exists() && !target.exists()) part.delete();
        }
    }

    private List<Book> books(JSONObject json) throws IOException {
        if (json == null) throw new IOException("No answer from Z-Library");
        if (json.optInt("success", 0) != 1) {
            String error = json.optString("error", "Z-Library refused the request");
            throw new IOException(error);
        }
        List<Book> out = new ArrayList<Book>();
        JSONArray arr = json.optJSONArray("books");
        if (arr == null) arr = json.optJSONArray("result");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String hash = o.optString("hash", "");
            if (id.length() == 0 || hash.length() == 0) continue;
            out.add(new Book(id, hash, o.optString("title", ""), o.optString("author", ""),
                    o.optString("year", ""), o.optString("language", ""),
                    o.optString("extension", "epub"), o.optLong("filesize", 0L)));
        }
        return out;
    }

    /** POST when {@code body} is non-null, GET otherwise; walks mirrors until success:1. */
    private JSONObject requestAny(String path, String body) throws IOException {
        IOException last = null;
        for (String mirror : mirrors()) {
            try {
                JSONObject json = request(mirror, path, body);
                if (json.optInt("success", 0) == 1) {
                    domain = mirror;
                    ZlibAccount.saveDomain(prefs, mirror);
                    return json;
                }
                // Reached the server but it refused (auth/quota) — mirror is fine.
                domain = mirror;
                return json;
            } catch (IOException e) {
                last = e;
            } catch (RuntimeException e) {
                last = new IOException(e);
            }
        }
        throw last != null ? last : new IOException("No Z-Library mirror reachable");
    }

    private JSONObject request(String mirror, String path, String body) throws IOException {
        Request.Builder b = new Request.Builder().url(mirror + path)
                .header("Cookie", cookieHeader())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");
        if (body != null) {
            b.post(RequestBody.create(FORM, body));
        }
        Response res = http.newCall(b.build()).execute();
        String text = res.body() != null ? res.body().string() : "";
        res.close();
        if (text.trim().length() == 0) {
            throw new IOException("Empty response (HTTP " + res.code() + ")");
        }
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("Unexpected answer from " + mirror);
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
