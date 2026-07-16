package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import com.solar.launcher.scrobble.ScrobbleManager;

import org.json.JSONObject;

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

    public SolarWebServer(Context context, File rootFolder) {
        this.context = context;
        this.rootFolder = rootFolder;
    }

    public void run() {
        try {
            serverSocket = new ServerSocket(8080);
            while (running) {
                Socket socket = serverSocket.accept();
                new Thread(new RequestHandler(socket)).start();
            }
        } catch (Exception e) {}
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

    private SharedPreferences solarPrefs() {
        return context.getSharedPreferences(ScrobbleManager.PREFS_NAME, Context.MODE_PRIVATE);
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
                String line;
                while (!(line = readHeaderLine(is)).isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }

                if (method.equals("GET") && path.equals("/")) {
                    StringBuilder foldersHtml = new StringBuilder("<option value=\"ROOT\">[Root Folder] /Music</option>");
                    File[] files = rootFolder.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isDirectory()) {
                                foldersHtml.append("<option value=\"").append(f.getName()).append("\">")
                                        .append(f.getName()).append("</option>");
                            }
                        }
                    }

                    String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Solar Music Server</title><style>" +
                            "body{font-family:sans-serif; background:#111; color:#fff; padding:20px; text-align:center;} " +
                            "input, select, button{font-size:16px; padding:10px; margin:5px 0; width:100%; max-width:400px; box-sizing:border-box;} " +
                            "button{background:#e8e4dc; color:#111; border:none; font-weight:600; cursor:pointer;} " +
                            ".box{background:#1a1a1a; padding:20px; border-radius:8px; margin:10px auto; max-width:400px; border:1px solid #333;}" +
                            "a{color:#c4b8a0;}</style></head><body>" +
                            "<h2>Solar Wireless Upload</h2>" +
                            "<div class='box'><h3>1. Create Folder</h3>" +
                            "<input type='text' id='fName' placeholder='e.g., Pop, Jazz'>" +
                            "<button onclick='createFolder()'>Create</button></div>" +
                            "<div class='box'><h3>2. Upload Music</h3>" +
                            "<select id='tFolder'>" + foldersHtml.toString() + "</select>" +
                            "<input type='file' id='fInput' multiple accept='.mp3,.flac,.wav,.ogg,.m4a,.aac,.ape,.wma,.jpg,.png'>" +
                            "<button onclick='uploadAll()'>Upload All</button>" +
                            "<div id='status' style='margin-top:10px; color:#9c9;'></div></div>" +
                            "<div class='box'><h3>3. Scrobbling</h3>" +
                            "<p style='color:#888;font-size:14px;margin:0 0 8px'>Configure Last.fm &amp; ListenBrainz from your PC (same as Settings → Scrobbling).</p>" +
                            "<p><a href='/scrobbling'>Last.fm / ListenBrainz setup →</a></p></div>" +
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
                            "  st.innerText = 'All uploads completed.'; " +
                            "}" +
                            "</script></body></html>";

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
                    os.write(response.getBytes("UTF-8"));
                }
                else if (method.equals("GET") && path.startsWith("/create_folder")) {
                    String q = path.split("\\?")[1];
                    String name = URLDecoder.decode(q.split("=")[1], "UTF-8");
                    File newDir = new File(rootFolder, name);
                    newDir.mkdirs();
                    newDir.setReadable(true, false);
                    newDir.setExecutable(true, false);
                    try { Runtime.getRuntime().exec(new String[]{"chmod", "777", newDir.getAbsolutePath()}); } catch(Exception e){}

                    String response = "HTTP/1.1 200 OK\r\n\r\nOK";
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

                    File targetDir = folder.equals("ROOT") ? rootFolder : new File(rootFolder, folder);
                    if (!targetDir.exists()) {
                        targetDir.mkdirs();
                        targetDir.setReadable(true, false);
                        targetDir.setExecutable(true, false);
                        try { Runtime.getRuntime().exec(new String[]{"chmod", "777", targetDir.getAbsolutePath()}); } catch(Exception e){}
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
                    try { Runtime.getRuntime().exec(new String[]{"chmod", "777", outFile.getAbsolutePath()}); } catch(Exception e){}

                    String response = "HTTP/1.1 200 OK\r\n\r\nOK";
                    os.write(response.getBytes("UTF-8"));
                }
                // ── Scrobbling (Last.fm / ListenBrainz) ────────────────────
                else if (path.equals("/scrobbling") || path.startsWith("/scrobbling?")) {
                    String msg = null;
                    if (method.equals("POST")) {
                        byte[] body = readBody(is, contentLength);
                        String bodyStr = new String(body, "UTF-8");
                        msg = ScrobbleManager.applyFromWeb(context, solarPrefs(),
                                formValue(bodyStr, "lastfm_user"),
                                formValue(bodyStr, "lastfm_pass"),
                                formValue(bodyStr, "lastfm_enabled"),
                                formValue(bodyStr, "listenbrainz_token"),
                                formValue(bodyStr, "listenbrainz_enabled"));
                    }
                    writeScrobblingSetupPage(os, msg);
                }
                else if (method.equals("GET") && path.equals("/api/scrobble-settings")) {
                    JSONObject d = ScrobbleManager.toWebJson(solarPrefs());
                    byte[] body = d.toString().getBytes("UTF-8");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: "
                            + body.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(body);
                }
                else if (method.equals("POST") && path.equals("/api/scrobble-settings")) {
                    byte[] body = readBody(is, contentLength);
                    String bodyStr = new String(body, "UTF-8");
                    // Accept form-urlencoded or JSON-ish form fields
                    String msg = ScrobbleManager.applyFromWeb(context, solarPrefs(),
                            formValue(bodyStr, "lastfm_user"),
                            formValue(bodyStr, "lastfm_pass"),
                            formValue(bodyStr, "lastfm_enabled"),
                            formValue(bodyStr, "listenbrainz_token"),
                            formValue(bodyStr, "listenbrainz_enabled"));
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("message", msg);
                    out.put("settings", ScrobbleManager.toWebJson(solarPrefs()));
                    byte[] respBody = out.toString().getBytes("UTF-8");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: "
                            + respBody.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(respBody);
                }
                os.flush();
            } catch (Exception e) {}
            finally {
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
            if (total == contentLength) return buf;
            byte[] trimmed = new byte[total];
            System.arraycopy(buf, 0, trimmed, 0, total);
            return trimmed;
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

        private String htmlEscape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private void writeScrobblingSetupPage(OutputStream os, String message) throws java.io.IOException {
            SharedPreferences prefs = solarPrefs();
            String user = prefs.getString(ScrobbleManager.PREF_LASTFM_USERNAME, "");
            boolean lastfmOn = prefs.getBoolean(ScrobbleManager.PREF_LASTFM_ENABLED, false);
            boolean lastfmSignedIn = ScrobbleManager.isLastFmConfigured(prefs);
            boolean lbOn = prefs.getBoolean(ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, false);
            boolean lbTokenSet = ScrobbleManager.isListenBrainzConfigured(prefs);
            String lbHint = "";
            if (lbTokenSet) {
                String t = prefs.getString(ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, "");
                lbHint = t.length() > 4 ? t.substring(t.length() - 4) : "";
            }
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String lastfmStatus = lastfmSignedIn
                    ? (lastfmOn ? "Signed in · scrobbling on" : "Signed in · scrobbling off")
                    : (lastfmOn ? "Enabled (not signed in yet)" : "Off");
            String lbStatus = lbTokenSet
                    ? (lbOn ? "Token set (…" + htmlEscape(lbHint) + ") · on" : "Token set · off")
                    : "No token";

            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Scrobbling setup</title><style>" +
                    "body{font-family:sans-serif;background:#111;color:#fff;padding:20px;text-align:center;}" +
                    "input,button{font-size:16px;padding:10px;margin:5px 0;width:100%;max-width:400px;box-sizing:border-box;}" +
                    "button{background:#00ffff;color:#000;border:none;font-weight:bold;cursor:pointer;}" +
                    ".box{background:#222;padding:20px;border-radius:10px;margin:10px auto;max-width:480px;text-align:left;}" +
                    "label{display:block;margin:12px 0 4px;color:#aaa;font-size:13px;}" +
                    ".chk{display:flex;align-items:center;gap:8px;margin:10px 0;}" +
                    ".chk input{width:auto;margin:0;}" +
                    "a{color:#0ff;}" +
                    "code{background:#333;padding:2px 6px;border-radius:3px;font-size:12px;}" +
                    "</style></head><body>" +
                    "<h2>Scrobbling</h2>" +
                    msgHtml +
                    "<p style='color:#888;font-size:14px;max-width:480px;margin:0 auto 12px'>" +
                    "Same preferences as <b>Settings → Scrobbling</b> on the device. " +
                    "Leave password/token blank to keep the value already stored on the player.</p>" +
                    "<form method='POST' action='/scrobbling' class='box'>" +
                    "<h3 style='margin-top:0'>Last.fm</h3>" +
                    "<p style='color:#ccc;font-size:13px'>Status: <b>" + htmlEscape(lastfmStatus) + "</b></p>" +
                    "<div class='chk'><input type='checkbox' name='lastfm_enabled' value='1' id='lfm_en'" +
                    (lastfmOn ? " checked" : "") + "><label for='lfm_en' style='margin:0'>Enable Last.fm scrobbling</label></div>" +
                    "<label>Username</label>" +
                    "<input name='lastfm_user' autocomplete='username' placeholder='Last.fm username' value='" + htmlEscape(user) + "'>" +
                    "<label>Password" + (prefs.getString(ScrobbleManager.PREF_LASTFM_PASSWORD, "").length() > 0
                        ? " <span style='color:#666'>(leave blank to keep current)</span>" : "") + "</label>" +
                    "<input name='lastfm_pass' type='password' autocomplete='current-password' placeholder='Last.fm password'>" +
                    "<h3>ListenBrainz</h3>" +
                    "<p style='color:#ccc;font-size:13px'>Status: <b>" + lbStatus + "</b></p>" +
                    "<div class='chk'><input type='checkbox' name='listenbrainz_enabled' value='1' id='lb_en'" +
                    (lbOn ? " checked" : "") + "><label for='lb_en' style='margin:0'>Enable ListenBrainz</label></div>" +
                    "<label>User token" + (lbTokenSet
                        ? " <span style='color:#666'>(leave blank to keep …" + htmlEscape(lbHint) + ")</span>"
                        : "") + "</label>" +
                    "<input name='listenbrainz_token' type='password' autocomplete='off' placeholder='ListenBrainz user token'>" +
                    "<p style='color:#888;font-size:12px'>Get a token at <a href='https://listenbrainz.org/settings/' target='_blank' rel='noopener'>listenbrainz.org/settings</a></p>" +
                    "<button type='submit'>Save</button></form>" +
                    "<p><a href='/'>← Back to upload</a></p></body></html>";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
            os.write(response.getBytes("UTF-8"));
        }
    }
}
