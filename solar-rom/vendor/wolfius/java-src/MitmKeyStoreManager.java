package io.github.gohoski.wolfius;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Created by Gleb on 23.06.2026.
 */

class MitmKeyStoreManager {
    private static final String TAG = "MitmKeyStoreManager";
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
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String defaultAlgo = (SettingsActivity.SDK >= 16) ? SettingsActivity.SIG_SHA256 : SettingsActivity.SIG_SHA1;
        String sigAlgo = prefs.getString(SettingsActivity.KEY_CERT_SIG, defaultAlgo);
        int newSigType = SettingsActivity.SIG_SHA256.equals(sigAlgo) ? 1 : 0;
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
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
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
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
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

    static boolean installRootCa(Context context) {
        try {
            init(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MitmKeyStoreManager prior to CA installation", e);
            return false;
        }

        if (SettingsActivity.SDK >= 14) {
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
                String originalHash = targetFile.exists() ? getFileSha256(targetFile) : "";

                SharedPreferences prefs = context.getSharedPreferences("Wolfius", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("original_hash", originalHash);
                editor.putBoolean("installation_pending_reboot", true);
                editor.commit();
                SettingsBackup.backup(context);

                byte[] pemBytes = getInstance().readAsset(context, "ca_cert.pem");

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
                script.append("dd if=").append(tempPemFile.getAbsolutePath()).append(" of=/system/etc/security/cacerts/").append(hash).append(".0\n");
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
                SharedPreferences prefs = context.getSharedPreferences("Wolfius", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("installation_pending_reboot", false).commit();
                SettingsBackup.backup(context);
                return false;
            }
        } else {
            File bksFile = new File("/system/etc/security/cacerts.bks");
            if (!bksFile.exists()) {
                Log.e(TAG, "cacerts.bks truststore file could not be found at /system/etc/security/cacerts.bks");
                return false;
            }
            try {
                String originalHash = getFileSha256(bksFile);
                SharedPreferences prefs = context.getSharedPreferences("Wolfius", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("original_hash", originalHash);
                editor.putBoolean("installation_pending_reboot", true);
                editor.commit();
                SettingsBackup.backup(context);

                KeyStore keystore;
                try {
                    keystore = KeyStore.getInstance("BKS", "BC");
                } catch (Exception e) {
                    keystore = KeyStore.getInstance("BKS");
                }
                FileInputStream fis = new FileInputStream(bksFile);
                try {
                    keystore.load(fis, "changeit".toCharArray());
                } catch (Exception e) {
                    fis.close();
                    fis = new FileInputStream(bksFile);
                    keystore.load(fis, "".toCharArray());
                } finally {
                    fis.close();
                }

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                InputStream certInput = context.getAssets().open("ca_cert.der");
                X509Certificate caCert = (X509Certificate) cf.generateCertificate(certInput);
                certInput.close();

                keystore.setCertificateEntry("wolfius", caCert);

                File tempBks = new File(context.getFilesDir(), "cacerts.bks.temp");
                FileOutputStream fos = new FileOutputStream(tempBks);
                keystore.store(fos, "changeit".toCharArray());
                fos.close();

                String blockDevice = getSystemBlockDevice();
                if (blockDevice == null) return false;

                String sdCardPath = "/sdcard";
                try {
                    File sdDir = Environment.getExternalStorageDirectory();
                    if (sdDir != null && Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                        sdCardPath = sdDir.getAbsolutePath();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resolve external storage directory", e);
                }

                StringBuilder script = new StringBuilder();
                script.append("mount -o remount,rw ").append(blockDevice).append(" /system\n");
                script.append("mount -o rw,remount ").append(blockDevice).append(" /system\n");
                script.append("dd if=/system/etc/security/cacerts.bks of=").append(sdCardPath).append("/cacerts.bks.bak\n");
                script.append("dd if=/system/etc/security/cacerts.bks of=/system/etc/security/cacerts.bks.bak\n");
                script.append("dd if=").append(tempBks.getAbsolutePath()).append(" of=").append(sdCardPath).append("/cacerts.bks\n");
                script.append("dd if=").append(tempBks.getAbsolutePath()).append(" of=/system/etc/security/cacerts.bks\n");
                script.append("chmod 644 /system/etc/security/cacerts.bks\n");
                script.append("mount -o remount,ro ").append(blockDevice).append(" /system\n");
                script.append("mount -o ro,remount ").append(blockDevice).append(" /system\n");

                boolean executeStatus = ShellUtils.executeRoot(script.toString());
                if (tempBks.exists()) tempBks.delete();
                return executeStatus;
            } catch (Exception e) {
                Log.e(TAG, "Trust store installation encountered an exception", e);
                SharedPreferences prefs = context.getSharedPreferences("Wolfius", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("installation_pending_reboot", false).commit();
                SettingsBackup.backup(context);
                return false;
            }
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
}