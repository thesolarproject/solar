package io.github.gohoski.wolfius;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.net.Socket;
import java.net.DatagramSocket;

/**
 * Created by Gleb on 12.07.2026.
 * Utilizes reflection to prevent VerifyError / NoClassDefFoundError on Android <4.0
 */

class VpnCompatHelper {
    interface SocketProtector {
        void protect(Socket socket);
        void protect(DatagramSocket socket);
        void protect(int fd);
    }

    private static SocketProtector protector;

    static void registerProtector(SocketProtector p) {
        protector = p;
    }

    public static void protect(Socket socket) {
        if (protector != null && socket != null) {
            protector.protect(socket);
        }
    }

    public static void protect(DatagramSocket socket) {
        if (protector != null && socket != null) {
            protector.protect(socket);
        }
    }

    public static void protect(int fd) {
        if (protector != null && fd >= 0) {
            protector.protect(fd);
        }
    }

    static Intent prepareVpn(Context context) {
        try {
            Class<?> vpnServiceClass = Class.forName("android.net.VpnService");
            java.lang.reflect.Method prepareMethod = vpnServiceClass.getMethod("prepare", Context.class);
            return (Intent) prepareMethod.invoke(null, context);
        } catch (Throwable t) { // Catch Throwable to capture ClassNotFoundException
            Log.d("VpnCompatHelper", "VpnService not available on this platform: " + t.getMessage());
            return null;
        }
    }

    static void startVpnService(Context context) {
        try {
            Intent intent = new Intent(context, Class.forName("io.github.gohoski.wolfius.WolfiusVpnService"));
            context.startService(intent);
        } catch (Throwable t) { // Catch LinkageError / NoClassDefFoundError on Android <4.0
            Log.e("VpnCompatHelper", "Could not start VPN Service dynamically", t);
        }
    }

    static void stopVpnService(Context context) {
        try {
            Intent intent = new Intent(context, Class.forName("io.github.gohoski.wolfius.WolfiusVpnService"));
            intent.setAction("io.github.gohoski.wolfius.ACTION_DISCONNECT");
            context.startService(intent);
        } catch (Throwable t) { // Catch LinkageError / NoClassDefFoundError on Android <4.0
            Log.e("VpnCompatHelper", "Could not stop VPN Service dynamically", t);
        }
    }
}