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
                                foldersHtml.append("<option value=\"").append(f.getName()).append("\">📁 ").append(f.getName()).append("</option>");
                            }
                        }
                    }

                    String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Solar Music Server</title><style>" +
                            "body{font-family:sans-serif; background:#111; color:#fff; padding:20px; text-align:center;} " +
                            "input, select, button{font-size:16px; padding:10px; margin:5px 0; width:100%; max-width:400px; box-sizing:border-box;} " +
                            "button{background:#00ffff; color:#000; border:none; font-weight:bold; cursor:pointer;} " +
                            ".box{background:#222; padding:20px; border-radius:10px; margin:10px auto; max-width:400px;}" +
                            "</style></head><body>" +
                            "<h2>🎧 Solar Wireless Upload</h2>" +
                            "<p><a href='/deezer' style='color:#0ff'>Deezer account setup →</a></p>" +
                            "<div class='box'><h3>1. Create Folder</h3>" +
                            "<input type='text' id='fName' placeholder='e.g., Pop, Jazz'>" +
                            "<button onclick='createFolder()'>Create</button></div>" +
                            "<div class='box'><h3>2. Upload Music</h3>" +
                            "<select id='tFolder'>" + foldersHtml.toString() + "</select>" +
                            "<input type='file' id='fInput' multiple accept='.mp3,.flac,.wav,.ogg,.m4a,.aac,.ape,.wma,.jpg,.png'>" +
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
                    File newDir = new File(rootFolder, name);
                    newDir.mkdirs();
                    newDir.setReadable(true, false);
                    newDir.setExecutable(true, false);
                    try { Runtime.getRuntime().exec(new String[]{"chmod", "777", newDir.getAbsolutePath()}); } catch(Exception e){}

                    String response = "HTTP/1.1 200 OK\r\n\r\nOK";
                    os.write(response.getBytes("UTF-8"));
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
                            DeezerAccount.saveArl(prefs, arl.trim());
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
                                // #region agent log
                                try {
                                    org.json.JSONObject d2 = new org.json.JSONObject();
                                    d2.put("initOk", ok);
                                    d2.put("user", client.userName());
                                    com.solar.launcher.deezer.DeezerDebugLog.log(context,
                                            "SolarWebServer.deezer", "initSession", "B", d2);
                                } catch (Exception ignored) {}
                                // #endregion
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
            boolean hasArl = DeezerAccount.hasArl(prefs);
            String quality = DeezerAccount.loadQuality(prefs);
            String status = hasArl ? "Logged in" : "Not configured";
            String msgHtml = message != null
                    ? "<p style='color:" + (message.startsWith("✅") ? "#0f0" : "#f66") + "'>"
                    + htmlEscape(message) + "</p>" : "";
            String demoNote = DeezerAccount.isUsingDemoArl(prefs)
                    ? "<p style='color:#aaa;font-size:14px'>" + htmlEscape(
                    context.getString(R.string.deezer_demo_account_web_note)) + "</p>" : "";
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

        private String htmlEscape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}