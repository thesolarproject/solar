package com.solar.launcher.tlsproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;

/**
 * MITM CA + per-domain leaf certificate manager (port of Wolfius {@code MitmKeyStoreManager}, GPLv3).
 *
 * Loads the bundled CA/key material from assets, generates per-base-domain leaf certificates
 * natively (wolfSSL CERT_GEN), caches server contexts, and can install the CA into the
 * Android system trust store via root (needed so the device trusts the proxy's certs).
 */
public class MitmKeyStoreManager {
    private static final String TAG = "MitmKeyStoreManager";

    static final String PREFS = "SOLAR_TLS_PROXY";
    static final String KEY_CERT_SIG = "wolfius_cert_sig";
    static final String SIG_SHA1 = "sha1";
    static final String SIG_SHA256 = "sha256";

    private static MitmKeyStoreManager instance;

    private byte[] caCertDer;
    private PrivateKey leafPrivateKeyJava;
    private X509Certificate caCertificate;
    private CertificateFactory certFactory;
    private int sigType = 0; // 0 for SHA-1, 1 for SHA-256

    static final ThreadLocal<String> currentHost = new ThreadLocal<String>();

    private static final int MAX_CACHE_SIZE = 50;
    private final Map<String, X509Certificate[]> certChainCache =
            Collections.synchronizedMap(new LinkedHashMap<String, X509Certificate[]>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, X509Certificate[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    private static final int MAX_CONTEXT_CACHE_SIZE = 50;
    private final Map<String, Long> serverContextCache =
            Collections.synchronizedMap(new LinkedHashMap<String, Long>(MAX_CONTEXT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    if (size() > MAX_CONTEXT_CACHE_SIZE) {
                        WolfClient.nativeFreeContext(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            });

    private final Map<String, Object> pendingGenerations = new HashMap<String, Object>();

    private MitmKeyStoreManager(Context context) throws Exception {
        try {
            caCertDer = readAsset(context, "ca_cert.der");
            byte[] caKeyPkcs1Der = readAsset(context, "ca_key_pkcs1.der");
            byte[] leafKeyPkcs1Der = readAsset(context, "leaf_key_pkcs1.der");
            byte[] leafKeyPkcs8Der = readAsset(context, "leaf_key_pkcs8.der");

            int ret = WolfClient.nativeLoadKeys(caKeyPkcs1Der, leafKeyPkcs1Der, leafKeyPkcs8Der);
            if (ret != 0) {
                throw new Exception("Failed to decode keys natively. Error code: " + ret);
            }

            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(leafKeyPkcs8Der);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            leafPrivateKeyJava = kf.generatePrivate(spec);

            certFactory = CertificateFactory.getInstance("X.509");
            caCertificate = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(caCertDer));

            updateSigType(context);

            SSLContext sslContext = SSLContext.getInstance("TLSv1");
            sslContext.init(new KeyManager[] { new MitmKeyManager() }, null, null);

            Log.i(TAG, "MitmKeyStoreManager initialized successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Initialization failed!", e);
            throw e;
        }
    }

    public static synchronized void init(Context context) throws Exception {
        if (instance == null) {
            instance = new MitmKeyStoreManager(context);
        } else {
            instance.updateSigType(context);
        }
    }

    public static synchronized boolean isInitialized() {
        return instance != null;
    }

    public static synchronized MitmKeyStoreManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MitmKeyStoreManager not initialized.");
        }
        return instance;
    }

    public void updateSigType(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String defaultAlgo = (Build.VERSION.SDK_INT >= 16) ? SIG_SHA256 : SIG_SHA1;
        String sigAlgo = prefs.getString(KEY_CERT_SIG, defaultAlgo);
        int newSigType = SIG_SHA256.equals(sigAlgo) ? 1 : 0;
        if (this.sigType != newSigType) {
            this.sigType = newSigType;
            clearCaches();
        }
    }

    public void clearCaches() {
        synchronized (serverContextCache) {
            for (Long ctxPtr : serverContextCache.values()) {
                if (ctxPtr != null && ctxPtr != 0) {
                    WolfClient.nativeFreeContext(ctxPtr);
                }
            }
            serverContextCache.clear();
        }
        certChainCache.clear();
    }

    public X509Certificate getCaCertificate() {
        return caCertificate;
    }

    long getServerContext(String host) {
        if (host == null) return 0;

        String baseDomain = getBaseDomain(host);
        String certSubjectName;

        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            certSubjectName = host;
        } else if (host.equalsIgnoreCase(baseDomain)) {
            certSubjectName = baseDomain;
        } else {
            certSubjectName = "*." + baseDomain;
        }

        Long ctxPtr = serverContextCache.get(certSubjectName);
        if (ctxPtr != null) {
            return ctxPtr;
        }

        long startTime = System.currentTimeMillis();
        synchronized (serverContextCache) {
            ctxPtr = serverContextCache.get(certSubjectName);
            if (ctxPtr == null) {
                ctxPtr = WolfClient.nativeCreateServerContext(certSubjectName, caCertDer, sigType);
                if (ctxPtr != 0) {
                    serverContextCache.put(certSubjectName, ctxPtr);
                }
            }
        }
        Log.i(TAG, "Server SSL context for " + certSubjectName + " took " + (System.currentTimeMillis() - startTime) + " ms");
        return ctxPtr;
    }

    static String getBaseDomain(String host) {
        if (host == null) return null;
        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            return host;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;
        if (parts.length >= 3) {
            String mid = parts[parts.length - 2].toLowerCase();
            if (mid.equals("co") || mid.equals("com") || mid.equals("org") || mid.equals("net") || mid.equals("edu") || mid.equals("gov")) {
                return parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private byte[] readAsset(Context context, String name) throws Exception {
        InputStream is = context.getAssets().open(name);
        try {
            int size = is.available();
            byte[] buffer = new byte[size];
            int read = 0;
            while (read < size) {
                int r = is.read(buffer, read, size - read);
                if (r == -1) break;
                read += r;
            }
            return buffer;
        } finally {
            is.close();
        }
    }

    static String getSystemBlockDevice() {
        BufferedReader br = null;
        try {
            File mountsFile = new File("/proc/mounts");
            if (mountsFile.exists()) {
                br = new BufferedReader(new FileReader(mountsFile), 1024);
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String dev = parts[0];
                        String path = parts[1];
                        if (path.equals("/system") || path.equals("/system/")) {
                            return dev;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading /proc/mounts directly", e);
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }

        Process process = null;
        BufferedReader sReader = null;
        try {
            process = Runtime.getRuntime().exec("mount");
            sReader = new BufferedReader(new InputStreamReader(process.getInputStream()), 1024);
            String line;
            while ((line = sReader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals("/system") || parts[i].equals("/system/")) {
                        if (i > 0) {
                            String maybeDev = parts[i - 1];
                            if (maybeDev.equals("on") && i > 1) {
                                maybeDev = parts[i - 2];
                            }
                            if (maybeDev.startsWith("/dev/")) {
                                return maybeDev;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error execution fallback parse from mount CLI", e);
        } finally {
            if (sReader != null) {
                try { sReader.close(); } catch (Exception ignored) {}
            }
            if (process != null) {
                process.destroy();
            }
        }
        return null;
    }

    static String getFileSha256(File file) {
        if (file == null || !file.exists()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] byteArray = new byte[1024];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
            fis.close();
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error calculating SHA-256 hash", e);
            return "";
        }
    }

    static String getSubjectHashOld(X509Certificate cert) {
        try {
            javax.security.auth.x500.X500Principal principal = cert.getSubjectX500Principal();
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = digest.digest(principal.getEncoded());
            int val = (((hashBytes[0] & 0xff) << 0) |
                    ((hashBytes[1] & 0xff) << 8) |
                    ((hashBytes[2] & 0xff) << 16) |
                    ((hashBytes[3] & 0xff) << 24));
            return String.format("%08x", val);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute subject hash old", e);
            return null;
        }
    }

    static String getBytesSha256(byte[] bytes) {
        if (bytes == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error calculating SHA-256 hash of bytes", e);
            return "";
        }
    }

    /**
     * Install the MITM CA into the system trust store ({@code /system/etc/security/cacerts/})
     * via root remount. On the Solar ROM the CA is pre-installed at build time
     * (see solar-rom/scripts/inject-wolfius-ca.sh) — this runtime path covers updates onto
     * stock-based ROMs and is safe to call repeatedly.
     */
    public static boolean installRootCa(Context context) {
        try {
            init(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MitmKeyStoreManager prior to CA installation", e);
            return false;
        }

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = context.getAssets().open("ca_cert.der");
            X509Certificate caCert = (X509Certificate) cf.generateCertificate(certInput);
            certInput.close();

            String hash = getSubjectHashOld(caCert);
            if (hash == null) {
                Log.e(TAG, "Failed to compute Old Subject Hash for the certificate.");
                return false;
            }

            File targetFile = new File("/system/etc/security/cacerts/" + hash + ".0");

            byte[] pemBytes;
            InputStream pemIs = context.getAssets().open("ca_cert.pem");
            try {
                int size = pemIs.available();
                pemBytes = new byte[size];
                int read = 0;
                while (read < size) {
                    int r = pemIs.read(pemBytes, read, size - read);
                    if (r == -1) break;
                    read += r;
                }
            } finally {
                pemIs.close();
            }

            File tempPemFile = new File(context.getFilesDir(), hash + ".0.temp");
            FileOutputStream fos = new FileOutputStream(tempPemFile);
            fos.write(pemBytes);
            fos.close();

            String blockDevice = getSystemBlockDevice();
            if (blockDevice == null) {
                Log.e(TAG, "Unable to resolve the block device for /system");
                if (tempPemFile.exists()) tempPemFile.delete();
                return false;
            }

            StringBuilder script = new StringBuilder();
            script.append("mount -o remount,rw ").append(blockDevice).append(" /system\n");
            script.append("mount -o rw,remount ").append(blockDevice).append(" /system\n");
            script.append("dd if=").append(tempPemFile.getAbsolutePath())
                    .append(" of=/system/etc/security/cacerts/").append(hash).append(".0\n");
            script.append("chmod 644 /system/etc/security/cacerts/").append(hash).append(".0\n");
            script.append("mount -o remount,ro ").append(blockDevice).append(" /system\n");
            script.append("mount -o ro,remount ").append(blockDevice).append(" /system\n");

            boolean executeStatus = ShellUtils.executeRoot(script.toString());
            if (tempPemFile.exists()) {
                tempPemFile.delete();
            }
            return executeStatus;
        } catch (Exception e) {
            Log.e(TAG, "Trust store installation failed for Android 4.0+", e);
            return false;
        }
    }

    private class MitmKeyManager implements X509KeyManager {
        public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return null; }
        public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[] { "mitm" }; }

        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "mitm";
        }

        public X509Certificate[] getCertificateChain(String alias) {
            String host = currentHost.get();
            if (host == null) {
                Log.e(TAG, "getCertificateChain invoked but currentHost ThreadLocal is empty!");
                return null;
            }

            String baseDomain = getBaseDomain(host);

            X509Certificate[] chain = certChainCache.get(baseDomain);
            if (chain != null) {
                return chain;
            }

            Object domainLock;
            boolean isGenerator = false;

            synchronized (pendingGenerations) {
                chain = certChainCache.get(baseDomain);
                if (chain != null) {
                    return chain;
                }

                domainLock = pendingGenerations.get(baseDomain);
                if (domainLock == null) {
                    domainLock = new Object();
                    pendingGenerations.put(baseDomain, domainLock);
                    isGenerator = true;
                }
            }

            if (isGenerator) {
                try {
                    byte[] certDer;
                    long startTime = System.currentTimeMillis();
                    synchronized (WolfClient.class) {
                        certDer = WolfClient.nativeGenerateMitmCert(baseDomain, caCertDer, sigType);
                    }
                    Log.i(TAG, "Cert gen for " + baseDomain + " took " + (System.currentTimeMillis() - startTime) + " ms");

                    if (certDer == null) {
                        throw new Exception("Native certificate generator returned null.");
                    }

                    X509Certificate leafCert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certDer));
                    chain = new X509Certificate[] { leafCert, caCertificate };

                    certChainCache.put(baseDomain, chain);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to dynamically compile certificate chain", e);
                } finally {
                    synchronized (pendingGenerations) {
                        pendingGenerations.remove(baseDomain);
                    }
                    synchronized (domainLock) {
                        domainLock.notifyAll();
                    }
                }
                return chain;
            } else {
                synchronized (domainLock) {
                    while (true) {
                        X509Certificate[] cachedChain = certChainCache.get(baseDomain);
                        if (cachedChain != null) {
                            return cachedChain;
                        }
                        synchronized (pendingGenerations) {
                            if (!pendingGenerations.containsKey(baseDomain)) {
                                break;
                            }
                        }
                        try {
                            domainLock.wait(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }
                return certChainCache.get(baseDomain);
            }
        }

        public PrivateKey getPrivateKey(String alias) {
            return leafPrivateKeyJava;
        }
    }

    public byte[] getCaCertDer() {
        return caCertDer;
    }

    /**
     * True when the bundled CA is present in the Android system trust store — either from a
     * runtime root install or a ROM pre-install (solar-rom/scripts/inject-wolfius-ca.sh).
     * The service only intercepts traffic when this holds, so existing TLS never breaks.
     */
    public static boolean isSystemCaInstalled(Context context) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream in = context.getAssets().open("ca_cert.der");
            X509Certificate cert;
            try {
                cert = (X509Certificate) cf.generateCertificate(in);
            } finally {
                in.close();
            }
            String hash = getSubjectHashOld(cert);
            if (hash == null) return false;
            return new File("/system/etc/security/cacerts/" + hash + ".0").isFile();
        } catch (Exception e) {
            Log.w(TAG, "isSystemCaInstalled check failed", e);
            return false;
        }
    }
}
