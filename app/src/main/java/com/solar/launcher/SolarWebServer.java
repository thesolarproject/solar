package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import com.solar.launcher.deezer.DeezerAccount;
import com.solar.launcher.deezer.DeezerClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;

public class SolarWebServer extends Thread {
    private ServerSocket serverSocket;
    private boolean running = true;
    private File rootFolder;
    private Context context;

    // 메인 화면에서 폴더 위치와 환경(Context)을 넘겨받음
    public SolarWebServer(Context context, File rootFolder) {
        this.context = context;
        this.rootFolder = rootFolder;
    }

    /** Audiobooks folder for Wi‑Fi Transfer — follows Primary storage pref. */
    private File audiobooksUploadRoot() {
        File base = context != null
                ? DeviceFeatures.getNewMediaRoot(context)
                : DeviceFeatures.getPrimaryStorageRoot();
        File ab = new File(base, "Audiobooks");
        if (!ab.exists()) ab.mkdirs();
        return ab;
    }

    public void run() {
        com.solar.launcher.net.TlsHelper.init(context);
        try {
            serverSocket = new ServerSocket(8080);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("port", 8080);
                d.put("ip", getLocalIpAddress());
                com.solar.launcher.deezer.DeezerDebugLog.log(context, "SolarWebServer.run",
                        "listening", "E", d);
            } catch (Exception ignored) {}
            // #endregion
            while (running) {
                Socket socket = serverSocket.accept();
                new Thread(new RequestHandler(socket)).start();
            }
        } catch (Exception e) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("error", e.getClass().getSimpleName());
                String msg = e.getMessage();
                if (msg != null && msg.length() > 120) msg = msg.substring(0, 120);
                d.put("msg", msg != null ? msg : "");
                com.solar.launcher.deezer.DeezerDebugLog.log(context, "SolarWebServer.run",
                        "bind failed", "E", d);
            } catch (Exception ignored) {}
            // #endregion
        }
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch(Exception e){}
    }

    // IP on any active interface (Wi-Fi, mobile/eth0, Ethernet)
    public String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipAddress = wm.getConnectionInfo().getIpAddress();
                if (ipAddress != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            (ipAddress & 0xff), (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
                }
            }
        } catch (Exception ex) { }
        return "Unknown IP";
    }

    private class RequestHandler implements Runnable {
        private Socket socket;
        public RequestHandler(Socket socket) { this.socket = socket; }

        private String readHeaderLine(InputStream is) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') break;
                sb.append((char) c);
            }
            return sb.toString();
        }

        public void run() {
            try {
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();

                String requestLine = readHeaderLine(is);
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts[1];

                int contentLength = 0;
                String host = "";
                String line;
                while (!(line = readHeaderLine(is)).isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                    if (line.toLowerCase().startsWith("host:")) {
                        host = line.substring(5).trim();
                    }
                }

                if (SolarStemPortalHandler.handleRequest(method, path, is, os, contentLength, context, rootFolder)) {
                    return;
                }

                if (method.equals("GET") && path.equals("/")) {
                    StringBuilder foldersHtml = new StringBuilder("<option value=\"ROOT\">[Root Folder] /Music</option>");
                    File[] files = rootFolder.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isDirectory()) {
                                foldersHtml.append("<option value=\"").append(f.getName()).append("\">📁 ").append(f.getName()).append("</option>");
                            }
                        }
                    }
                    // 2026-07-15 — Audiobooks upload target follows Primary storage pref.
                    File abRoot = audiobooksUploadRoot();
                    if (!abRoot.exists()) abRoot.mkdirs();
                    foldersHtml.append("<option value=\"AUDIOBOOKS\">📚 Audiobooks</option>");

                    String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Solar Music Server</title><style>" +
                            "body{font-family:sans-serif; background:#111; color:#fff; padding:20px; text-align:center;} " +
                            "input, select, button{font-size:16px; padding:10px; margin:5px 0; width:100%; max-width:400px; box-sizing:border-box;} " +
                            "button{background:#00ffff; color:#000; border:none; font-weight:bold; cursor:pointer;} " +
                            ".box{background:#222; padding:20px; border-radius:10px; margin:10px auto; max-width:400px;}" +
                            "</style></head><body>" +
                            "<h2>🎧 Solar Wireless Upload</h2>" +
                            "<p><a href='/stems' style='color:#0ff; font-weight:bold; font-size:18px; text-decoration:none; display:inline-block; margin:10px 0; padding:12px 24px; border:2px solid #00ffff; border-radius:30px; background:rgba(0,255,255,0.1);'>🚀 Solar Web Portal &amp; Stem Preparer (Deezer + YouTube + Library) →</a></p>" +
                            "<p><a href='/browse' style='color:#0ff'>Browse &amp; download files →</a></p>" +
                            "<p><a href='/deezer' style='color:#0ff'>Deezer account setup →</a></p>" +
                            "<p><a href='/lalal' style='color:#0ff'>Stem Player (Lalal.ai) API key →</a></p>" +
                            "<p><a href='/navidrome' style='color:#0ff'>Navidrome server setup →</a></p>" +
                            "<p><a href='/plex' style='color:#0ff'>Plex server setup →</a></p>" +
                            "<p><a href='/jellyfin' style='color:#0ff'>Jellyfin server setup →</a></p>" +
                            "<p><a href='/scrobbling' style='color:#0ff'>Scrobbling (Last.fm &amp; ListenBrainz) setup →</a></p>" +
                            "<p><a href='/scan_stats' style='color:#0ff'>Last library scan stats →</a></p>" +
                            "<div class='box'><h3>1. Create Folder</h3>" +
                            "<input type='text' id='fName' placeholder='e.g., Pop, Jazz'>" +
                            "<button onclick='createFolder()'>Create</button></div>" +
                            "<div class='box'><h3>2. Upload Music</h3>" +
                            "<select id='tFolder'>" + foldersHtml.toString() + "</select>" +
                            "<input type='file' id='fInput' multiple accept='.mp3,.flac,.wav,.ogg,.m4a,.m4b,.aac,.ape,.wma,.jpg,.png'>" +
                            "<button onclick='uploadAll()'>Upload All</button>" +
                            "<div id='status' style='margin-top:10px; color:#0f0;'></div></div>" +
                            "<script>" +
                            "function createFolder() { " +
                            "  var n = document.getElementById('fName').value; " +
                            "  if(!n) return;" +
                            "  fetch('/create_folder?name=' + encodeURIComponent(n)).then(() => location.reload()); " +
                            "}" +
                            "async function uploadAll() { " +
                            "  var files = document.getElementById('fInput').files; " +
                            "  var folder = document.getElementById('tFolder').value; " +
                            "  var st = document.getElementById('status'); " +
                            "  if(files.length === 0) return;" +
                            "  for(var i=0; i<files.length; i++) { " +
                            "    st.innerText = 'Uploading: ' + files[i].name + ' (' + (i+1) + '/' + files.length + ')'; " +
                            "    await fetch('/upload?folder=' + encodeURIComponent(folder) + '&name=' + encodeURIComponent(files[i].name), {method:'POST', body:files[i]}); " +
                            "  } " +
                            "  st.innerText = '✅ All uploads completed!'; " +
                            "}" +
                            "</script></body></html>";

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("GET") && path.startsWith("/create_folder")) {
                    String q = path.split("\\?")[1];
                    String name = URLDecoder.decode(q.split("=")[1], "UTF-8");
                    File newDir = SolarWebPaths.resolveUnder(rootFolder, name);
                    if (newDir == null) {
                        os.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                    } else {
                        newDir.mkdirs();
                        newDir.setReadable(true, false);
                        newDir.setWritable(true, false);
                        newDir.setExecutable(true, false);

                        String response = "HTTP/1.1 200 OK\r\n\r\nOK";
                        os.write(response.getBytes("UTF-8"));
                    }
                }
                else if (path.equals("/deezer") || path.startsWith("/deezer?")) {
                    if (method.equals("GET")) {
                        writeDeezerSetupPage(os, null);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        String arl = formValue(bodyStr, "arl");
                        String quality = formValue(bodyStr, "quality");
                        String msg = null;
                        if (arl == null || arl.trim().length() < 64) {
                            msg = "ARL cookie is too short. Log in at deezer.com, copy the arl cookie from DevTools.";
                        } else {
                            SharedPreferences prefs = context.getSharedPreferences(
                                    DeezerAccount.PREFS_NAME, Context.MODE_PRIVATE);
                            DeezerAccount.saveUserArl(prefs, arl.trim());
                            if (quality != null && !quality.isEmpty()) {
                                prefs.edit().putString(DeezerAccount.PREF_QUALITY, quality).commit();
                            }
                            // #region agent log
                            try {
                                String probeWww = com.solar.launcher.net.TlsHelper.probeProtocol(
                                        "https://www.deezer.com/");
                                String probeApi = com.solar.launcher.net.TlsHelper.probeProtocol(
                                        "https://api.deezer.com/");
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("arlLen", arl.trim().length());
                                d.put("tlsWww", probeWww != null ? probeWww : "fail");
                                d.put("tlsApi", probeApi != null ? probeApi : "fail");
                                com.solar.launcher.deezer.DeezerDebugLog.log(context,
                                        "SolarWebServer.deezer", "pre-test", "A", d);
                            } catch (Exception ignored) {}
                            // #endregion
                            DeezerClient client = new DeezerClient(prefs);
                            boolean ok = false;
                            try {
                                ok = client.initSession();
                                try {
                                    org.json.JSONObject d2 = new org.json.JSONObject();
                                    d2.put("initOk", ok);
                                    com.solar.launcher.deezer.DeezerDebugLog.log(context,
                                            "SolarWebServer.deezer", "initSession", "B", d2);
                                } catch (Exception ignored) {}
                            } catch (java.io.IOException e) {
                                // #region agent log
                                try {
                                    org.json.JSONObject d2 = new org.json.JSONObject();
                                    d2.put("error", e.getClass().getSimpleName());
                                    String errMsg = e.getMessage();
                                    if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200);
                                    d2.put("msg", errMsg != null ? errMsg : "");
                                    com.solar.launcher.deezer.DeezerDebugLog.log(context,
                                            "SolarWebServer.deezer", "initSession fail", "B", d2);
                                } catch (Exception ignored) {}
                                // #endregion
                            }
                            ConnectivityHelper.setDeezerLoginOk(ok);
                            msg = ok ? "✅ Deezer login verified!" : "❌ Saved ARL but login test failed. Check the cookie.";
                        }
                        writeDeezerSetupPage(os, msg);
                    }
                }
                else if (path.equals("/lalal") || path.startsWith("/lalal?")) {
                    // 2026-07-18 — Paste Lalal.ai license key from PC (Deezer-style).
                    if (method.equals("GET")) {
                        writeLalalSetupPage(os, null);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        String key = formValue(bodyStr, "key");
                        String premix = formValue(bodyStr, "premix");
                        String msg;
                        SharedPreferences prefs = context.getSharedPreferences(
                                com.solar.launcher.stem.LalalAccount.PREFS_NAME, Context.MODE_PRIVATE);
                        if (premix != null && premix.length() > 0) {
                            // Toggle experimental Melody premix from PC page.
                            boolean on = com.solar.launcher.stem.LalalAccount
                                    .togglePremixExperimental(prefs);
                            msg = on
                                    ? "Melody premix On (experimental)."
                                    : "Melody live multi-player (default).";
                        } else if (key == null || key.trim().length() < 8) {
                            com.solar.launcher.stem.LalalAccount.saveUserKey(prefs, "");
                            msg = "Using bundled demo key: Stem features On.";
                        } else {
                            com.solar.launcher.stem.LalalAccount.saveUserKey(prefs, key.trim());
                            msg = com.solar.launcher.stem.LalalAccount.isUserConfigured(prefs)
                                    ? "Saved your Lalal.ai API key."
                                    : "Using bundled demo key: Stem features On.";
                        }
                        writeLalalSetupPage(os, msg);
                    }
                }
                else if (method.equals("GET") && path.equals("/scan_stats")) {
                    ScanPerfLog.LastScan last = ScanPerfLog.last();
                    org.json.JSONObject d = new org.json.JSONObject();
                    try {
                        if (last != null) {
                            d.put("timestamp", last.timestamp);
                            d.put("trackCount", last.trackCount);
                            d.put("totalMs", last.totalMs);
                            d.put("phases", new org.json.JSONObject(last.phaseBreakdown));
                            d.put("tracksPerSecond",
                                    last.totalMs > 0 ? (last.trackCount * 1000f / last.totalMs) : 0f);
                        } else {
                            d.put("timestamp", org.json.JSONObject.NULL);
                            d.put("trackCount", 0);
                            d.put("totalMs", 0);
                            d.put("phases", new org.json.JSONObject());
                            d.put("tracksPerSecond", 0f);
                        }
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + d.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (path.equals("/navidrome") || path.startsWith("/navidrome?")) {
                    if (method.equals("GET")) {
                        writeNavidromeSetupPage(os, null);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        String navUrl = formValue(bodyStr, "url");
                        String navUser = formValue(bodyStr, "user");
                        String navPass = formValue(bodyStr, "pass");
                        SharedPreferences prefs = context.getSharedPreferences(
                                "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                        com.solar.launcher.navidrome.NavidromePrefs.save(context, prefs, navUrl, navUser, navPass);
                        String msg = com.solar.launcher.navidrome.NavidromePrefs.isConfigured(prefs)
                                ? "✅ Navidrome settings saved." : "Saved: enter URL and username.";
                        writeNavidromeSetupPage(os, msg);
                    }
                }
                else if (method.equals("GET") && path.equals("/api/navidrome-settings")) {
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    org.json.JSONObject d = new org.json.JSONObject();
                    try {
                        d.put("url", prefs.getString("navidrome_url", ""));
                        d.put("user", prefs.getString("navidrome_user", ""));
                        d.put("pass", prefs.getString("navidrome_pass", ""));
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + d.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("POST") && path.equals("/api/navidrome-settings")) {
                    byte[] body = readBody(is, contentLength);
                    String bodyStr = new String(body, "UTF-8");
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    com.solar.launcher.navidrome.NavidromePrefs.save(context, prefs,
                            formValue(bodyStr, "url"), formValue(bodyStr, "user"), formValue(bodyStr, "pass"));
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}";
                    os.write(response.getBytes("UTF-8"));
                }
                else if (path.equals("/plex") || path.startsWith("/plex?")) {
                    if (method.equals("GET")) {
                        writePlexSetupPage(os, null);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        SharedPreferences prefs = context.getSharedPreferences(
                                "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                        com.solar.launcher.plex.PlexPrefs.save(context, prefs,
                                formValue(bodyStr, "url"), formValue(bodyStr, "token"));
                        // 2026-07-14: Saving from Wi‑Fi transfer also arms the Debug experiment.
                        if (com.solar.launcher.plex.PlexPrefs.isConfigured(prefs)) {
                            com.solar.launcher.plex.PlexExperiment.setEnabled(prefs, true);
                        }
                        String msg = com.solar.launcher.plex.PlexPrefs.isConfigured(prefs)
                                ? "✅ Plex settings saved: Music → Plex is unlocked."
                                : "Saved: enter URL and token.";
                        writePlexSetupPage(os, msg);
                    }
                }
                else if (method.equals("GET") && path.equals("/api/plex-settings")) {
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    org.json.JSONObject d = new org.json.JSONObject();
                    try {
                        d.put("url", prefs.getString("plex_url", ""));
                        d.put("token", prefs.getString("plex_token", ""));
                        d.put("experiment", com.solar.launcher.plex.PlexExperiment.isEnabled(prefs));
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + d.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("POST") && path.equals("/api/plex-settings")) {
                    byte[] body = readBody(is, contentLength);
                    String bodyStr = new String(body, "UTF-8");
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    com.solar.launcher.plex.PlexPrefs.save(context, prefs,
                            formValue(bodyStr, "url"), formValue(bodyStr, "token"));
                    if (com.solar.launcher.plex.PlexPrefs.isConfigured(prefs)) {
                        com.solar.launcher.plex.PlexExperiment.setEnabled(prefs, true);
                    }
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}";
                    os.write(response.getBytes("UTF-8"));
                }
                else if (path.equals("/jellyfin") || path.startsWith("/jellyfin?")) {
                    if (method.equals("GET")) {
                        writeJellyfinSetupPage(os, null);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        SharedPreferences prefs = context.getSharedPreferences(
                                "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                        com.solar.launcher.jellyfin.JellyfinPrefs.save(context, prefs,
                                formValue(bodyStr, "url"), formValue(bodyStr, "user"),
                                formValue(bodyStr, "pass"));
                        // 2026-07-14: Saving from Wi‑Fi transfer also arms the Debug experiment.
                        if (com.solar.launcher.jellyfin.JellyfinPrefs.isConfigured(prefs)) {
                            com.solar.launcher.jellyfin.JellyfinExperiment.setEnabled(prefs, true);
                        }
                        String msg = com.solar.launcher.jellyfin.JellyfinPrefs.isConfigured(prefs)
                                ? "✅ Jellyfin settings saved: Music → Jellyfin is unlocked."
                                : "Saved: enter URL and username.";
                        writeJellyfinSetupPage(os, msg);
                    }
                }
                else if (method.equals("GET") && path.equals("/api/jellyfin-settings")) {
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    org.json.JSONObject d = new org.json.JSONObject();
                    try {
                        d.put("url", prefs.getString("jellyfin_url", ""));
                        d.put("user", prefs.getString("jellyfin_user", ""));
                        d.put("pass", prefs.getString("jellyfin_pass", ""));
                        d.put("experiment",
                                com.solar.launcher.jellyfin.JellyfinExperiment.isEnabled(prefs));
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + d.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("POST") && path.equals("/api/jellyfin-settings")) {
                    byte[] body = readBody(is, contentLength);
                    String bodyStr = new String(body, "UTF-8");
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    com.solar.launcher.jellyfin.JellyfinPrefs.save(context, prefs,
                            formValue(bodyStr, "url"), formValue(bodyStr, "user"), formValue(bodyStr, "pass"));
                    if (com.solar.launcher.jellyfin.JellyfinPrefs.isConfigured(prefs)) {
                        com.solar.launcher.jellyfin.JellyfinExperiment.setEnabled(prefs, true);
                    }
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}";
                    os.write(response.getBytes("UTF-8"));
                }
                else if (path.equals("/scrobbling") || path.startsWith("/scrobbling?")) {
                    if (method.equals("GET")) {
                        writeScrobbleSetupPage(os, null, host);
                    } else if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        SharedPreferences prefs = context.getSharedPreferences(
                                "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                        boolean lfmEnabled = "true".equalsIgnoreCase(formValue(bodyStr, "lastfm_enabled"));
                        boolean lbEnabled = "true".equalsIgnoreCase(formValue(bodyStr, "listenbrainz_enabled"));
                        String lbToken = formValue(bodyStr, "listenbrainz_token");

                        if (lbToken != null) lbToken = lbToken.trim(); else lbToken = "";

                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_ENABLED, lfmEnabled);
                        editor.putBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, lbEnabled);
                        if (!lbToken.isEmpty()) {
                            editor.putString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, lbToken);
                        }
                        editor.apply();

                        String msg = "✅ Scrobble settings saved.";
                        writeScrobbleSetupPage(os, msg, host);
                    }
                }
                else if (method.equals("GET") && path.startsWith("/lastfm_auth")) {
                    String query = "";
                    if (path.contains("?")) query = path.split("\\?")[1];
                    String token = "";
                    for (String p : query.split("&")) {
                        if (p.startsWith("token=")) token = java.net.URLDecoder.decode(p.substring(6), "UTF-8");
                    }
                    String msg = "";
                    if (token != null && !token.isEmpty()) {
                        String authResult = com.solar.launcher.scrobble.ScrobbleManager.getSessionLastFmSync(context, token);
                        if (authResult != null && authResult.startsWith("Connected")) {
                            msg = "✅ Last.fm Authenticated: " + authResult;
                        } else {
                            msg = "⚠️ Last.fm Auth Failed: " + authResult;
                        }
                    } else {
                        msg = "⚠️ No token provided by Last.fm.";
                    }
                    writeScrobbleSetupPage(os, msg, host);
                }
                else if (method.equals("GET") && path.equals("/api/scrobbling-settings")) {
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    org.json.JSONObject d = new org.json.JSONObject();
                    try {
                        d.put("lastfm_enabled", prefs.getBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_ENABLED, false));
                        d.put("lastfm_user", prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_USERNAME, ""));
                        d.put("lastfm_sk", prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_SK, ""));
                        d.put("listenbrainz_enabled", prefs.getBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, false));
                        d.put("listenbrainz_token", prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, ""));
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + d.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("POST") && path.equals("/api/scrobbling-settings")) {
                    byte[] body = readBody(is, contentLength);
                    String bodyStr = new String(body, "UTF-8");
                    SharedPreferences prefs = context.getSharedPreferences(
                            "SOLAR_SETTINGS", Context.MODE_PRIVATE);
                    boolean lfmEnabled = "true".equalsIgnoreCase(formValue(bodyStr, "lastfm_enabled"));
                    boolean lbEnabled = "true".equalsIgnoreCase(formValue(bodyStr, "listenbrainz_enabled"));
                    String lbToken = formValue(bodyStr, "listenbrainz_token");

                    if (lbToken != null) lbToken = lbToken.trim(); else lbToken = "";

                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_ENABLED, lfmEnabled);
                    editor.putBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, lbEnabled);
                    if (!lbToken.isEmpty()) editor.putString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, lbToken);
                    editor.apply();

                    org.json.JSONObject resp = new org.json.JSONObject();
                    try {
                        resp.put("ok", true);
                    } catch (Exception ignored) {}
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + resp.toString();
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("POST") && path.startsWith("/upload")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String folder = "ROOT", name = "unnamed.file";
                    for (String p : params) {
                        if (p.startsWith("folder=")) folder = URLDecoder.decode(p.substring(7), "UTF-8");
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8");
                    }

                    File targetDir;
                    if ("ROOT".equals(folder)) {
                        targetDir = rootFolder;
                    } else if ("AUDIOBOOKS".equals(folder)) {
                        targetDir = audiobooksUploadRoot();
                    } else {
                        targetDir = SolarWebPaths.resolveUnder(rootFolder, folder);
                    }
                    if (targetDir == null) {
                        os.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                        return;
                    }
                    String safeName = SolarWebPaths.safeUploadName(name);
                    if (safeName == null) {
                        os.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                        return;
                    }
                    name = safeName;
                    if (!targetDir.exists()) {
                        targetDir.mkdirs();
                        targetDir.setReadable(true, false);
                        targetDir.setWritable(true, false);
                        targetDir.setExecutable(true, false);
                    }
                    File outFile = new File(targetDir, name);

                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalRead = 0;
                    while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }

                    fos.flush();
                    try { fos.getFD().sync(); } catch(Exception e){}
                    fos.close();

                    outFile.setReadable(true, false);
                    outFile.setWritable(true, false);
                    outFile.setExecutable(true, false);

                    String response = "HTTP/1.1 200 OK\r\n\r\nOK";
                    os.write(response.getBytes("UTF-8"));
                } else if (method.equals("GET") && (path.equals("/browse") || path.startsWith("/browse?"))) {
                    // 2026-07-15 — PC download/browse listing under Music (and sibling Audiobooks).
                    writeBrowsePage(os, path);
                } else if (method.equals("GET") && path.startsWith("/download?")) {
                    writeDownload(os, path);
                } else {
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("method", method);
                        d.put("path", path);
                        com.solar.launcher.deezer.DeezerDebugLog.log(context,
                                "SolarWebServer.request", "404", "E", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    String response = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNot found";
                    os.write(response.getBytes("UTF-8"));
                }
                os.flush();
            } catch (Exception e) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("error", e.getClass().getSimpleName());
                    com.solar.launcher.deezer.DeezerDebugLog.log(context,
                            "SolarWebServer.request", "handler error", "E", d);
                } catch (Exception ignored) {}
                // #endregion
            } finally {
                try { socket.close(); } catch (Exception e) {}
            }
        }

        private byte[] readBody(InputStream is, int contentLength) throws java.io.IOException {
            if (contentLength <= 0) return new byte[0];
            byte[] buf = new byte[contentLength];
            int total = 0;
            while (total < contentLength) {
                int n = is.read(buf, total, contentLength - total);
                if (n < 0) break;
                total += n;
            }
            return buf;
        }

        /** 2026-07-15 — HTML directory listing with download links (Music / Audiobooks). */
        private void writeBrowsePage(OutputStream os, String path) throws Exception {
            String dirParam = "";
            int q = path.indexOf("dir=");
            if (q >= 0) {
                dirParam = URLDecoder.decode(path.substring(q + 4), "UTF-8");
                int amp = dirParam.indexOf('&');
                if (amp >= 0) dirParam = dirParam.substring(0, amp);
            }
            File abRoot = audiobooksUploadRoot();
            File base;
            boolean underAb = false;
            if ("AUDIOBOOKS".equals(dirParam) || dirParam.startsWith("AUDIOBOOKS/")) {
                base = SolarWebPaths.resolveAudiobooks(abRoot, dirParam);
                underAb = true;
            } else if (dirParam.length() > 0) {
                base = SolarWebPaths.resolveUnder(rootFolder, dirParam);
            } else {
                base = rootFolder;
            }
            if (base == null || !base.isDirectory()) {
                base = rootFolder;
                underAb = false;
            }
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("dirParam", dirParam);
                d.put("base", base.getAbsolutePath());
                d.put("underAb", underAb);
                com.solar.launcher.debug.SessionDebugLog.log(context, "SolarWebServer.writeBrowsePage",
                        "browse resolve", "W1", d);
            } catch (Exception ignored) {}
            // #endregion
            StringBuilder list = new StringBuilder();
            File[] kids = base.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    String rel;
                    if (underAb) {
                        String rest = f.getAbsolutePath().substring(abRoot.getAbsolutePath().length());
                        if (rest.startsWith("/")) rest = rest.substring(1);
                        rel = "AUDIOBOOKS" + (rest.isEmpty() ? "" : "/" + rest);
                    } else {
                        String rootPath = rootFolder.getAbsolutePath();
                        rel = f.getAbsolutePath().startsWith(rootPath)
                                ? f.getAbsolutePath().substring(rootPath.length())
                                : f.getName();
                        if (rel.startsWith("/")) rel = rel.substring(1);
                    }
                    if (f.isDirectory()) {
                        list.append("<li>📁 <a href='/browse?dir=")
                                .append(java.net.URLEncoder.encode(rel, "UTF-8"))
                                .append("'>").append(htmlEscape(f.getName())).append("</a></li>");
                    } else {
                        list.append("<li>🎵 <a href='/download?path=")
                                .append(java.net.URLEncoder.encode(rel, "UTF-8"))
                                .append("'>").append(htmlEscape(f.getName())).append("</a></li>");
                    }
                }
            }
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Browse</title>"
                    + "<style>body{font-family:sans-serif;background:#111;color:#fff;padding:20px}"
                    + "a{color:#0ff}</style></head><body>"
                    + "<h2>Browse &amp; download</h2>"
                    + "<p><a href='/browse'>Music</a> · <a href='/browse?dir=AUDIOBOOKS'>Audiobooks</a>"
                    + " · <a href='/'>← Upload</a></p><ul>"
                    + list + "</ul></body></html>";
            byte[] body = html.getBytes("UTF-8");
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes("UTF-8"));
            os.write(body);
        }

        /** 2026-07-15 — Stream a file from Music or Audiobooks to the PC (no whole-file RAM load). */
        private void writeDownload(OutputStream os, String path) throws Exception {
            String fileParam = "";
            int q = path.indexOf("path=");
            if (q >= 0) {
                fileParam = URLDecoder.decode(path.substring(q + 5), "UTF-8");
                int amp = fileParam.indexOf('&');
                if (amp >= 0) fileParam = fileParam.substring(0, amp);
            }
            File abRoot = audiobooksUploadRoot();
            File f;
            if (fileParam.startsWith("AUDIOBOOKS/") || "AUDIOBOOKS".equals(fileParam)) {
                f = SolarWebPaths.resolveAudiobooks(abRoot, fileParam);
            } else {
                f = SolarWebPaths.resolveUnder(rootFolder, fileParam);
            }
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("fileParam", fileParam);
                d.put("ok", f != null && f.isFile());
                d.put("len", f != null ? f.length() : -1);
                com.solar.launcher.debug.SessionDebugLog.log(context, "SolarWebServer.writeDownload",
                        "download resolve", "W2", d);
            } catch (Exception ignored) {}
            // #endregion
            if (f == null || !f.isFile()) {
                os.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes("UTF-8"));
                return;
            }
            long len = f.length();
            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Disposition: attachment; filename=\"" + f.getName().replace("\"", "") + "\"\r\n"
                    + "Content-Length: " + len + "\r\n\r\n";
            os.write(header.getBytes("UTF-8"));
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                fis.close();
            }
        }

        private String formValue(String body, String key) {
            if (body == null || key == null) return "";
            try {
                String[] pairs = body.split("&");
                for (String p : pairs) {
                    int eq = p.indexOf('=');
                    if (eq < 0) continue;
                    String k = URLDecoder.decode(p.substring(0, eq), "UTF-8");
                    if (key.equals(k)) {
                        return URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                    }
                }
            } catch (Exception ignored) {}
            return "";
        }

        private void writeDeezerSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    DeezerAccount.PREFS_NAME, Context.MODE_PRIVATE);
            boolean hasArl = DeezerAccount.isUserArlConfigured(prefs);
            String quality = DeezerAccount.loadQuality(prefs);
            String status = hasArl ? "Logged in" : "Not configured";
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String demoNote = "";
            String instructions =
                    "<div class='box' style='text-align:left;font-size:14px;line-height:1.5'>" +
                    "<p><b>What is the arl?</b> After you log in at deezer.com, your browser stores an " +
                    "<code>arl</code> cookie. Solar uses it to stream and download as your account.</p>" +
                    "<p style='color:#f88'><b>Keep it secret.</b> Paste it only on this page on your home " +
                    "network. Log out of Deezer on shared PCs when done.</p>" +
                    "<h3 style='margin-top:16px'>Google Chrome</h3>" +
                    "<ol style='padding-left:20px'>" +
                    "<li>Open <a href='https://www.deezer.com/login' target='_blank'>deezer.com</a> and sign in.</li>" +
                    "<li>Press <b>F12</b> (or menu → More tools → Developer tools).</li>" +
                    "<li>Open the <b>Application</b> tab (Chrome 96+: may be under »).</li>" +
                    "<li>Under <b>Storage → Cookies</b>, select <code>https://www.deezer.com</code>.</li>" +
                    "<li>Find the row named <b>arl</b> and copy its <b>Value</b> (long hex string).</li>" +
                    "</ol>" +
                    "<h3 style='margin-top:16px'>Mozilla Firefox</h3>" +
                    "<ol style='padding-left:20px'>" +
                    "<li>Open <a href='https://www.deezer.com/login' target='_blank'>deezer.com</a> and sign in.</li>" +
                    "<li>Press <b>F12</b> to open Developer Tools.</li>" +
                    "<li>Open the <b>Storage</b> tab.</li>" +
                    "<li>Expand <b>Cookies</b> → <code>https://www.deezer.com</code>.</li>" +
                    "<li>Click <b>arl</b> and copy the value from the pane on the right.</li>" +
                    "</ol>" +
                    "<p style='color:#aaa;margin-top:12px'>If you do not see <code>arl</code>, refresh deezer.com " +
                    "while logged in, or try logging out and back in.</p>" +
                    "</div>";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Solar Deezer Setup</title><style>" +
                    "body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;max-width:520px;margin:0 auto;}" +
                    "input,select,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "textarea{font-size:14px;padding:10px;width:100%;max-width:400px;height:80px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;cursor:pointer;}" +
                    ".box{background:#222;padding:20px;border-radius:10px;margin:10px auto;max-width:480px;}" +
                    "code{background:#333;padding:2px 4px;border-radius:3px;}" +
                    "a{color:#0ff;}</style></head><body>" +
                    "<h2>🎵 Deezer Account</h2>" +
                    "<p><a href='/stems' style='color:#0ff; font-weight:bold;'>🚀 Open Solar Web Portal &amp; Stem Preparer →</a></p>" +
                    demoNote +
                    instructions +
                    "<div class='box'><p>Status: <b>" + htmlEscape(status) + "</b></p>" +
                    msgHtml +
                    "<form method='POST' action='/deezer'>" +
                    "<textarea name='arl' placeholder='Paste arl cookie here'></textarea>" +
                    "<select name='quality'><option value='mp3'" + ("mp3".equals(quality) ? " selected" : "") + ">MP3</option>" +
                    "<option value='flac'" + ("flac".equals(quality) ? " selected" : "") + ">FLAC (Premium)</option></select>" +
                    "<button type='submit'>Save &amp; Test</button></form></div>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        /**
         * PC page to set Lalal.ai license for Stem Player.
         * Layman: paste your key here so the device can split songs into stems.
         * 2026-07-18
         */
        private void writeLalalSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    com.solar.launcher.stem.LalalAccount.PREFS_NAME, Context.MODE_PRIVATE);
            boolean configured = com.solar.launcher.stem.LalalAccount.isUserConfigured(prefs);
            boolean demoOn = !configured
                    && com.solar.launcher.stem.StemFeatures.isOptedIn(prefs);
            String status = configured ? "Configured (your key)"
                    : (demoOn ? "Using demo key (Stem features On)"
                            : "Stem features Off");
            String msgHtml = message != null
                    ? "<p style='color:#0f0'>" + htmlEscape(message) + "</p>" : "";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Solar Stem Player (Lalal.ai)</title><style>" +
                    "body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:left;max-width:560px;margin:0 auto;}" +
                    "h2,h3{text-align:center;} code,pre{background:#1a1a1a;padding:2px 6px;border-radius:4px;}" +
                    "pre{padding:12px;overflow:auto;font-size:13px;}" +
                    "input,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;cursor:pointer;}" +
                    ".box{background:#222;padding:20px;border-radius:10px;margin:10px auto;max-width:520px;}" +
                    "ul{color:#ccc;} a{color:#0ff;}</style></head><body>" +
                    "<h2>Stem Player · Lalal.ai</h2>" +
                    "<p><a href='/stems' style='color:#0ff; font-weight:bold; display:block; text-align:center; margin-bottom:10px;'>🚀 Open Solar Web Portal &amp; Stem Preparer →</a></p>" +
                    "<p style='color:#aaa;text-align:center'>Solar ships a demo key that unlocks Stem / Mix. Paste your own license from " +
                    "<a href='https://www.lalal.ai/' target='_blank'>lalal.ai</a> for your quota, or skip the cloud and use your own stem files (below).</p>" +
                    "<div class='box'><p>Status: <b>" + htmlEscape(status) + "</b></p>" +
                    msgHtml +
                    "<form method='POST' action='/lalal'>" +
                    "<input type='text' name='key' placeholder='Paste Lalal.ai API / license key' autocomplete='off'>" +
                    "<button type='submit'>Save</button></form>" +
                    "<form method='POST' action='/lalal' style='margin-top:8px'>" +
                    "<input type='hidden' name='key' value=''>" +
                    "<button type='submit'>Use demo key (enable Stem features)</button></form></div>" +
                    "<div class='box'>" +
                    "<h3>Prepare your own stems</h3>" +
                    "<p>Next to <code>My Song.mp3</code>, create folder <code>My Song.stems/</code> with MP3s:</p>" +
                    "<pre>My Song.mp3\nMy Song.stems/\n  vocals.mp3\n  drums.mp3   (or drum.mp3)\n  bass.mp3\n  melody.mp3  (or other.mp3)</pre>" +
                    "<p>Melody pad aliases: <code>melody</code>, <code>other</code>, <code>instruments</code>, <code>samples</code>.</p>" +
                    "<p>Or Lalal multistem names (API allows only these six): <code>vocals</code>, " +
                    "<code>drum</code>, <code>bass</code>, <code>piano</code>, " +
                    "<code>electric_guitar</code>, <code>acoustic_guitar</code>. " +
                    "Solar also keeps the leftover <code>no_multistem</code> residual on the Melody pad. " +
                    "Default: play Melody stems live (synced volumes/loops). " +
                    "Experimental premix (one WAV) is a Settings toggle. " +
                    "Sidecar-only extras: <code>synthesizer</code>, <code>strings</code>, <code>wind</code>.</p>" +
                    "<form method='POST' action='/lalal' style='margin-top:12px'>" +
                    "<input type='hidden' name='premix' value='" +
                    (com.solar.launcher.stem.LalalAccount.isPremixExperimental(prefs) ? "0" : "1") + "'>" +
                    "<button type='submit'>Toggle Melody premix (now: " +
                    (com.solar.launcher.stem.LalalAccount.isPremixExperimental(prefs) ? "On" : "Off") +
                    ")</button></form>" +
                    "<p>Open Stem Player on that track. Solar loads the folder first (no upload). " +
                    "Full guide: <code>docs/using-solar/stem-player.md</code> in the Solar repo.</p>" +
                    "</div>" +
                    "<p style='text-align:center'><a href='/'>← Back to upload</a></p></body></html>";
            // 2026-07-19 — User stem naming guide on /lalal. Was: key form only. Reversal: drop .box guide.
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        private void writeNavidromeSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    "SOLAR_SETTINGS", Context.MODE_PRIVATE);
            String url = prefs.getString("navidrome_url", "");
            String user = prefs.getString("navidrome_user", "");
            String pass = prefs.getString("navidrome_pass", "");
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Navidrome setup</title><style>body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;}" +
                    "input,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;}</style></head><body>" +
                    "<h2>Navidrome / Subsonic</h2>" + msgHtml +
                    "<form method='POST' action='/navidrome'>" +
                    "<input name='url' placeholder='https://music.example.com' value='" + htmlEscape(url) + "'>" +
                    "<input name='user' placeholder='Username' value='" + htmlEscape(user) + "'>" +
                    "<input name='pass' type='password' placeholder='Password' value='" + htmlEscape(pass) + "'>" +
                    "<button type='submit'>Save</button></form>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        /** 2026-07-14: PC companion form — Plex URL + X-Plex-Token. */
        private void writePlexSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    "SOLAR_SETTINGS", Context.MODE_PRIVATE);
            String url = prefs.getString("plex_url", "");
            String token = prefs.getString("plex_token", "");
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Plex setup</title><style>body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;}" +
                    "input,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;}</style></head><body>" +
                    "<h2>Plex Media Server</h2>" + msgHtml +
                    "<p style='color:#aaa;font-size:14px'>Server address + X-Plex-Token. Saving turns on the Plex experiment so Music → Plex appears.</p>" +
                    "<form method='POST' action='/plex'>" +
                    "<input name='url' placeholder='http://192.168.x.x:32400' value='" + htmlEscape(url) + "'>" +
                    "<input name='token' placeholder='X-Plex-Token' value='" + htmlEscape(token) + "'>" +
                    "<button type='submit'>Save</button></form>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        /** 2026-07-14: PC companion form — Jellyfin URL + user + password. */
        private void writeJellyfinSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    "SOLAR_SETTINGS", Context.MODE_PRIVATE);
            String url = prefs.getString("jellyfin_url", "");
            String user = prefs.getString("jellyfin_user", "");
            String pass = prefs.getString("jellyfin_pass", "");
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Jellyfin setup</title><style>body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;}" +
                    "input,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;}</style></head><body>" +
                    "<h2>Jellyfin</h2>" + msgHtml +
                    "<p style='color:#aaa;font-size:14px'>URL, username, password. Saving turns on the Jellyfin experiment so Music → Jellyfin appears.</p>" +
                    "<form method='POST' action='/jellyfin'>" +
                    "<input name='url' placeholder='http://192.168.x.x:8096' value='" + htmlEscape(url) + "'>" +
                    "<input name='user' placeholder='Username' value='" + htmlEscape(user) + "'>" +
                    "<input name='pass' type='password' placeholder='Password' value='" + htmlEscape(pass) + "'>" +
                    "<button type='submit'>Save</button></form>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        /** 2026-07-16: PC companion form — Scrobbling (Last.fm & ListenBrainz). */
        private void writeScrobbleSetupPage(OutputStream os, String message, String host) throws java.io.IOException {
            SharedPreferences prefs = context.getSharedPreferences(
                    "SOLAR_SETTINGS", Context.MODE_PRIVATE);
            boolean lastfmEnabled = prefs.getBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_ENABLED, false);
            String lastfmUser = prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_USERNAME, "");
            String lastfmSk = prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_SK, "");
            boolean listenbrainzEnabled = prefs.getBoolean(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, false);
            String listenbrainzToken = prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, "");
            String apiKey = prefs.getString(com.solar.launcher.scrobble.ScrobbleManager.PREF_LASTFM_API_KEY, com.solar.launcher.scrobble.ScrobbleManager.DEFAULT_LASTFM_API_KEY);

            String authUrl = "https://www.last.fm/api/auth/?api_key=" + apiKey + "&cb=http://" + host + "/lastfm_auth";

            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Scrobbling Setup</title><style>body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;}" +
                    "input,button,label,.btn-auth{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;display:inline-block;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;cursor:pointer;}" +
                    ".btn-auth{background:#f66;color:#fff;text-decoration:none;font-weight:bold;border-radius:5px;}" +
                    ".box{background:#222;padding:20px;border-radius:10px;margin:15px auto;max-width:400px;text-align:left;}" +
                    "h3{margin-top:0;color:#0ff;text-align:center;}</style></head><body>" +
                    "<h2>🎧 Scrobbling Setup</h2>" + msgHtml +
                    "<form method='POST' action='/scrobbling'>" +
                    "<div class='box'><h3>Last.fm</h3>" +
                    "<label><input type='checkbox' name='lastfm_enabled' value='true' style='width:auto;' " + (lastfmEnabled ? "checked" : "") + "> Enable Last.fm</label>" +
                    "<p style='color:#aaa;font-size:14px;margin:5px 0;'>Current User: " + (lastfmUser.isEmpty() ? "Not logged in" : htmlEscape(lastfmUser)) + "</p>" +
                    "<p style='color:#aaa;font-size:14px;margin:5px 0;'>Session Key: " + (lastfmSk.isEmpty() ? "Not Authenticated" : "Authenticated ✅") + "</p>" +
                    "<a class='btn-auth' href='" + htmlEscape(authUrl) + "'>Connect to Last.fm</a></div>" +
                    "<div class='box'><h3>ListenBrainz</h3>" +
                    "<label><input type='checkbox' name='listenbrainz_enabled' value='true' style='width:auto;' " + (listenbrainzEnabled ? "checked" : "") + "> Enable ListenBrainz</label>" +
                    "<input name='listenbrainz_token' placeholder='ListenBrainz User Token' value='" + htmlEscape(listenbrainzToken) + "'></div>" +
                    "<button type='submit'>Save Settings</button></form>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }

        private String htmlEscape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}