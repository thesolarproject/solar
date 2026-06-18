package com.solar.launcher.soulseek;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ponytail: NAT-PMP TCP port map (RFC 6886); ceiling: Linux /proc route + Wi-Fi local IP only. */
final class SoulseekNatpmp {
  private static final int NATPMP_PORT = 5351;
  private static final int LEASE_SEC = 3600;
  private static final int ATTEMPTS = 2;
  private static final int INIT_TIMEOUT_MS = 250;

  static final class Result {
    final int privatePort;
    final int publicPort;
    final String externalIp;
    final String gatewayIp;
    final String status;

    Result(int privatePort, int publicPort, String externalIp, String gatewayIp, String status) {
      this.privatePort = privatePort;
      this.publicPort = publicPort;
      this.externalIp = externalIp;
      this.gatewayIp = gatewayIp;
      this.status = status;
    }

    boolean mapped() {
      return "ok".equals(status);
    }
  }

  private SoulseekNatpmp() {}

  static Result tryMapTcpPort(Context ctx, int privatePort) {
    if (privatePort <= 0 || privatePort > 65535) {
      return new Result(privatePort, privatePort, null, null, "bad_port");
    }
    try {
      InetAddress gateway = gatewayFromProcRoute();
      if (gateway == null) return new Result(privatePort, privatePort, null, null, "no_gateway");
      InetAddress local = localIpFromWifi(ctx);
      if (local == null) return new Result(privatePort, privatePort, null, gateway.getHostAddress(), "no_local_ip");

      String externalIp = requestExternalIp(local, gateway);
      int mapped = requestTcpMap(local, gateway, privatePort);
      if (mapped <= 0) {
        return new Result(privatePort, privatePort, externalIp, gateway.getHostAddress(), "map_failed");
      }
      return new Result(privatePort, mapped, externalIp, gateway.getHostAddress(), "ok");
    } catch (Exception e) {
      return new Result(privatePort, privatePort, null, null, "error");
    }
  }

  private static String requestExternalIp(InetAddress local, InetAddress gateway) {
    byte[] req = new byte[] {0, 0};
    byte[] resp = udpExchange(local, gateway, req, 12);
    if (resp == null || resp.length < 12 || resp[1] != (byte) 128 || readU16(resp, 2) != 0) return null;
    return String.format(java.util.Locale.US,
        "%d.%d.%d.%d", resp[8] & 0xff, resp[9] & 0xff, resp[10] & 0xff, resp[11] & 0xff);
  }

  private static int requestTcpMap(InetAddress local, InetAddress gateway, int privatePort) {
    byte[] req = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .put((byte) 0)
        .put((byte) 2)
        .putShort((short) 0)
        .putShort((short) privatePort)
        .putShort((short) privatePort)
        .putInt(LEASE_SEC)
        .array();
    byte[] resp = udpExchange(local, gateway, req, 16);
    if (resp == null || resp.length < 12) return -1;
    if (resp[1] != (byte) (2 + 128) || readU16(resp, 2) != 0) return -1;
    return readU16(resp, 10);
  }

  private static byte[] udpExchange(InetAddress local, InetAddress gateway, byte[] req, int expectLen) {
    DatagramSocket sock = null;
    try {
      sock = new DatagramSocket(0, local);
      int timeout = INIT_TIMEOUT_MS;
      for (int i = 0; i < ATTEMPTS; i++) {
        sock.setSoTimeout(timeout);
        sock.send(new DatagramPacket(req, req.length, gateway, NATPMP_PORT));
        byte[] buf = new byte[Math.max(expectLen, 16)];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        try {
          sock.receive(resp);
          byte[] out = new byte[resp.getLength()];
          System.arraycopy(buf, 0, out, 0, resp.getLength());
          return out;
        } catch (java.net.SocketTimeoutException ignored) {
          timeout *= 2;
        }
      }
    } catch (Exception ignored) {
    } finally {
      if (sock != null) sock.close();
    }
    return null;
  }

  private static int readU16(byte[] b, int off) {
    return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
  }

  private static InetAddress localIpFromWifi(Context ctx) {
    if (ctx == null) return null;
    try {
      WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
      if (info == null) return null;
      int ip = info.getIpAddress();
      if (ip == 0) return null;
      return InetAddress.getByAddress(new byte[] {
          (byte) (ip & 0xff), (byte) ((ip >> 8) & 0xff),
          (byte) ((ip >> 16) & 0xff), (byte) ((ip >> 24) & 0xff)
      });
    } catch (Exception ignored) {
      return null;
    }
  }

  private static InetAddress gatewayFromProcRoute() {
    try {
      java.io.BufferedReader br = new java.io.BufferedReader(
          new java.io.FileReader("/proc/net/route"));
      String line;
      br.readLine();
      while ((line = br.readLine()) != null) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 3) continue;
        if (!"00000000".equals(p[1])) continue;
        int hex = (int) Long.parseLong(p[2], 16);
        br.close();
        return InetAddress.getByAddress(new byte[] {
            (byte) (hex & 0xff), (byte) ((hex >> 8) & 0xff),
            (byte) ((hex >> 16) & 0xff), (byte) ((hex >> 24) & 0xff)
        });
      }
      br.close();
    } catch (Exception ignored) {}
    return null;
  }
}
