package com.solar.launcher.soulseek;

import android.content.Context;

import com.solar.launcher.Debug843b96Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Soulseek session for outbound PM (diag relay / developer support).
 * Uses Nicotine+ client id (160) so the server relays PMs to standard clients.
 * Persistent mode binds a separate listen port (61100+) with NAT-PMP, concurrent with
 * the main Reach {@link SoulseekClient} on 61000+.
 */
public final class SoulseekMessagingSession {
    private static final int LISTEN_PORT_MIN = 61100;
    private static final int LISTEN_PORT_MAX = 61199;

    private final Context appContext;
    private final String username;
    private final String password;
    private final boolean persistent;
    private final Object lock = new Object();
    private Socket serverSocket;
    private ServerSocket listenSocket;
    private int listenPort;
    private int reportedListenPort;
    private volatile boolean loggedIn;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread readerThread;

    public SoulseekMessagingSession(Context context, String username, String password,
            boolean persistent) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.username = username;
        this.password = password;
        this.persistent = persistent;
    }

    /** Legacy one-shot session (no listen/NAT) — prefer {@link SolarDiagSessionManager}. */
    SoulseekMessagingSession(String username, String password) {
        this(null, username, password, false);
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getUsername() {
        return username;
    }

    /** Connect on a background caller thread; throws on hard login failure. */
    public void ensureConnected() throws Exception {
        synchronized (lock) {
            if (loggedIn && serverSocket != null && !serverSocket.isClosed()) return;
            connectLocked();
        }
    }

    /** Send one PM per recipient; returns per-slot success (parallel index with recipients). */
    public boolean[] sendToRecipients(String[] recipients, String text) {
        if (recipients == null || recipients.length == 0 || text == null) {
            return new boolean[0];
        }
        boolean[] result = new boolean[recipients.length];
        try {
            ensureConnected();
            for (int i = 0; i < recipients.length; i++) {
                if (i > 0) {
                    try { Thread.sleep(75_000L); } catch (InterruptedException ignored) {}
                }
                String to = recipients[i];
                if (to == null || to.isEmpty()) {
                    result[i] = true;
                    continue;
                }
                try {
                    sendLocked(to, text);
                    result[i] = true;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("user", username);
                d.put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                Debug843b96Log.log(appContext, "SoulseekMessagingSession.sendToRecipients",
                        "connect fail", "F-G", d);
            } catch (Exception ignored) {}
            // #endregion
            if (!persistent) disconnect();
        }
        return result;
    }

    /** True only when every recipient slot succeeded. */
    public boolean sendToAll(String[] recipients, String text) {
        boolean[] per = sendToRecipients(recipients, text);
        if (per.length == 0) return false;
        for (boolean ok : per) {
            if (!ok) return false;
        }
        return true;
    }

    public void shutdown() {
        running.set(false);
        disconnect();
    }

    private void connectLocked() throws Exception {
        disconnectLocked();
        if (persistent && appContext != null) {
            startListenSocket();
        }
        serverSocket = new Socket();
        serverSocket.connect(new InetSocketAddress(SoulseekWire.SERVER_HOST, SoulseekWire.SERVER_PORT), 15000);
        serverSocket.setKeepAlive(true);
        OutputStream out = serverSocket.getOutputStream();
        out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_LOGIN,
                SoulseekWire.loginBodyPm(username, password)));
        out.flush();
        SoulseekWire.ServerFrame greet = SoulseekWire.readServerFrame(serverSocket.getInputStream());
        if (greet.code != SoulseekWire.MSG_LOGIN) throw new IOException("Unexpected login response");
        SoulseekWire.Reader r = new SoulseekWire.Reader(greet.body);
        if (!r.readBool()) {
            String reason = r.hasRemaining() ? r.readString() : "rejected";
            throw new IOException("Login rejected: " + reason);
        }
        if (r.hasRemaining()) r.readString();
        if (r.hasRemaining()) r.readUInt32();
        if (r.hasRemaining()) r.readString();
        if (r.hasRemaining()) r.readBool();

        int reported = listenPort > 0 ? listenPort : 0;
        boolean natMapped = false;
        if (persistent && appContext != null && listenPort > 0) {
            SoulseekNatpmp.Result nat = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
            if (!nat.mapped()) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
                nat = SoulseekNatpmp.tryMapTcpPort(appContext, listenPort);
            }
            reported = nat.publicPort > 0 ? nat.publicPort : listenPort;
            reportedListenPort = reported;
            natMapped = nat.mapped();
            out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_SET_WAIT_PORT,
                    SoulseekWire.packUInt32(reported)));
            out.flush();
        }
        out.write(SoulseekWire.serverMessage(SoulseekWire.MSG_SET_STATUS,
                SoulseekWire.packInt32(SoulseekWire.STATUS_ONLINE)));
        out.flush();
        loggedIn = true;
        startReader();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("user", username);
            d.put("clientMajor", SoulseekWire.CLIENT_MAJOR_PM);
            d.put("listen", listenPort);
            d.put("reported", reported);
            d.put("natMapped", natMapped);
            d.put("persistent", persistent);
            Debug843b96Log.log(appContext, "SoulseekMessagingSession.connectLocked",
                    "login ok", "F-G", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private void startListenSocket() throws IOException {
        closeQuietly(listenSocket);
        listenPort = 0;
        for (int p = LISTEN_PORT_MIN; p <= LISTEN_PORT_MAX; p++) {
            try {
                listenSocket = new ServerSocket(p);
                listenSocket.setReuseAddress(true);
                listenPort = p;
                break;
            } catch (IOException ignored) {}
        }
        if (listenSocket == null) throw new IOException("No diag listen port");
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get() && listenSocket != null && !listenSocket.isClosed()) {
                    try {
                        final Socket peer = listenSocket.accept();
                        peer.setSoTimeout(5000);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                drainPeer(peer);
                            }
                        }, "SoulseekDiagPeer").start();
                    } catch (IOException ignored) {}
                }
            }
        }, "SoulseekDiagListen").start();
    }

    /** ponytail: diag session ignores peer traffic — accept and close. */
    private static void drainPeer(Socket peer) {
        try {
            while (peer.getInputStream().read() >= 0) {}
        } catch (Exception ignored) {
        } finally {
            closeQuietly(peer);
        }
    }

    private void startReader() {
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                drainServerFrames();
            }
        }, "SoulseekMsgDrain");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void drainServerFrames() {
        try {
            while (running.get() && loggedIn && serverSocket != null && !serverSocket.isClosed()) {
                SoulseekWire.readServerFrame(serverSocket.getInputStream());
            }
        } catch (Exception ignored) {
        } finally {
            loggedIn = false;
        }
    }

    private void sendLocked(String peerUser, String text) throws Exception {
        synchronized (lock) {
            if (!loggedIn || serverSocket == null || serverSocket.isClosed()) {
                connectLocked();
            }
            try {
                byte[] a = SoulseekWire.packString(peerUser);
                byte[] b = SoulseekWire.packString(text);
                byte[] body = new byte[a.length + b.length];
                System.arraycopy(a, 0, body, 0, a.length);
                System.arraycopy(b, 0, body, a.length, b.length);
                serverSocket.getOutputStream().write(
                        SoulseekWire.serverMessage(SoulseekWire.MSG_MESSAGE_USER, body));
                serverSocket.getOutputStream().flush();
            } catch (Exception e) {
                loggedIn = false;
                throw e;
            }
        }
    }

    public void disconnect() {
        synchronized (lock) {
            disconnectLocked();
        }
    }

    private void disconnectLocked() {
        loggedIn = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(listenSocket);
        listenSocket = null;
        listenPort = 0;
        reportedListenPort = 0;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {}
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {}
    }
}
