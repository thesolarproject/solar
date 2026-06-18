package com.solar.launcher;

import android.content.Context;
import android.net.wifi.WifiManager;
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
                os.flush();
            } catch (Exception e) {}
            finally {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }
}