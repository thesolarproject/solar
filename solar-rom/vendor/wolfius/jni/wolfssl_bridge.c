#include <jni.h>
#undef JNIEXPORT
#define JNIEXPORT __attribute__((visibility("default")))
#include <string.h>
#include <sys/socket.h>
#include <netdb.h>
#include <unistd.h>
#include <pthread.h>
#include <wolfssl/ssl.h>
#include <wolfssl/wolfcrypt/rsa.h>
#include <wolfssl/wolfcrypt/asn_public.h>
#include <wolfssl/wolfcrypt/random.h>
#include <netinet/in.h>
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WolfiusBridge", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WolfiusBridge", __VA_ARGS__)

#include <stdio.h>

/* Solar port — accept4() is API 21+ on Android; Y1 (API 17) / Y2 (API 19) need a shim.
 * Modern wolfSSL wc_port.c references accept4() unconditionally on __ANDROID__.
 * Defined only when the platform libc lacks it (android-17/19), resolving the linker
 * reference without polluting newer ABIs. Reversal: delete block when minSdk >= 21. */
#if defined(__ANDROID__) && (__ANDROID_API__ < 21)
#include <sys/syscall.h>
#include <fcntl.h>
int accept4(int sockfd, struct sockaddr* addr, socklen_t* addrlen, int flags) {
    int fd = (int)syscall(__NR_accept4, sockfd, addr, addrlen, flags);
    if (fd >= 0 && (flags & O_CLOEXEC)) {
        fcntl(fd, F_SETFD, FD_CLOEXEC);
    }
    return fd;
}
#endif

static void set_cert_alt_names(Cert* cert, const char* host) {
#ifdef WOLFSSL_ALT_NAMES
    int hostLen = (int)strlen(host);
    int is_ip = 1;
    for (int i = 0; i < hostLen; i++) {
        if (!((host[i] >= '0' && host[i] <= '9') || host[i] == '.')) {
            is_ip = 0;
            break;
        }
    }

    if (is_ip) {
        unsigned int a, b, c, d;
        if (sscanf(host, "%u.%u.%u.%u", &a, &b, &c, &d) == 4) {
            byte ipBytes[4] = { (byte)a, (byte)b, (byte)c, (byte)d };
            int seqLen = 2 + 4; // Tag 0x87 (1 byte) + Len (1 byte) + 4 bytes IP
            int idx = 0;

            cert->altNames[idx++] = 0x30; // ASN.1 SEQUENCE
            if (seqLen < 128) {
                cert->altNames[idx++] = (byte)seqLen;
            } else {
                cert->altNames[idx++] = 0x81;
                cert->altNames[idx++] = (byte)seqLen;
            }
            cert->altNames[idx++] = 0x87; // ASN.1 GeneralName tag: iPAddress
            cert->altNames[idx++] = 4;
            memcpy(cert->altNames + idx, ipBytes, 4);
            idx += 4;
            cert->altNamesSz = idx;
        }
    } else {
        char wildcardHost[256];
        const char* dot = strchr(host, '.');
        if (dot != NULL && strchr(dot + 1, '.') != NULL) {
            snprintf(wildcardHost, sizeof(wildcardHost), "*%s", dot);
        } else {
            snprintf(wildcardHost, sizeof(wildcardHost), "*.%s", host);
        }
        int wildcardLen = (int)strlen(wildcardHost);

        int dns1Len = 2 + hostLen;     //Tag 0x82 + Len + host
        int dns2Len = 2 + wildcardLen; //Tag 0x82 + Len + wildcardHost
        int seqLen = dns1Len + dns2Len;

        int idx = 0;
        cert->altNames[idx++] = 0x30; // ASN.1 SEQUENCE
        if (seqLen < 128) {
            cert->altNames[idx++] = (byte)seqLen;
        } else {
            cert->altNames[idx++] = 0x81; // ASN.1 Long form length header
            cert->altNames[idx++] = (byte)seqLen;
        }

        //tag 0x82 = dNSName in ASN.1 GeneralName
        cert->altNames[idx++] = 0x82;
        cert->altNames[idx++] = (byte)hostLen;
        memcpy(cert->altNames + idx, host, hostLen);
        idx += hostLen;

        cert->altNames[idx++] = 0x82;
        cert->altNames[idx++] = (byte)wildcardLen;
        memcpy(cert->altNames + idx, wildcardHost, wildcardLen);
        idx += wildcardLen;

        cert->altNamesSz = idx;
    }
#endif
}

static RsaKey gCaKey;
static RsaKey gLeafKey;
static int gKeysInitialized = 0;

static pthread_mutex_t gCertGenMutex = PTHREAD_MUTEX_INITIALIZER;

static void android_wolfssl_log_cb(const int logLevel, const char* const logMessage) {
    __android_log_print(ANDROID_LOG_DEBUG, "WolfiusInternal", "%s", logMessage);
}

#ifndef SO_ORIGINAL_DST
#define SO_ORIGINAL_DST 80
#endif
JNIEXPORT jint JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativePeekFirstByte(JNIEnv *env, jclass clazz, jint fd) {
    unsigned char buf[1];
    ssize_t received = recv(fd, buf, 1, MSG_PEEK);
    if (received <= 0) {
        return -1;
    }
    return (jint)buf[0];
}

JNIEXPORT jstring JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativePeekAndParseSNI(JNIEnv *env, jclass clazz, jint fd) {
    unsigned char buf[2048];
    ssize_t received = recv(fd, buf, sizeof(buf), MSG_PEEK);
    if (received < 5) {
        return NULL;
    }

    int pos = 0;
    unsigned char record_type = buf[pos++];
    if (record_type != 0x16) return NULL;

    pos += 2; // skip version
    unsigned int record_len = (buf[pos] << 8) | buf[pos+1];
    pos += 2;

    if (received < pos + 4) return NULL;
    unsigned char handshake_type = buf[pos++];
    if (handshake_type != 0x01) return NULL;

    pos += 3;  // skip handshake length
    pos += 2;  // skip client version
    pos += 32; // skip random bytes

    if (pos >= received) return NULL;
    unsigned char session_id_len = buf[pos++];
    pos += session_id_len;

    if (pos + 2 > received) return NULL;
    unsigned int cipher_suites_len = (buf[pos] << 8) | buf[pos+1];
    pos += 2 + cipher_suites_len;

    if (pos >= received) return NULL;
    unsigned char compression_len = buf[pos++];
    pos += compression_len;

    if (pos + 2 > received) return NULL;
    unsigned int extensions_len = (buf[pos] << 8) | buf[pos+1];
    pos += 2;

    int extensions_end = pos + extensions_len;
    if (extensions_end > received) {
        extensions_end = received;
    }

    while (pos + 4 <= extensions_end) {
        unsigned int ext_type = (buf[pos] << 8) | buf[pos+1];
        unsigned int ext_len = (buf[pos+2] << 8) | buf[pos+3];
        pos += 4;

        int next_ext_pos = pos + ext_len;
        if (ext_type == 0x0000) {
            if (pos + 2 > extensions_end) return NULL;
            pos += 2; // Skip server list length
            while (pos + 3 <= next_ext_pos && pos + 3 <= extensions_end) {
                unsigned char name_type = buf[pos++];
                unsigned int name_len = (buf[pos] << 8) | buf[pos+1];
                pos += 2;
                if (name_type == 0x00) { // host_name
                    if (pos + name_len <= extensions_end) {
                        char host[256];
                        if (name_len >= sizeof(host)) {
                            name_len = sizeof(host) - 1;
                        }
                        memcpy(host, buf + pos, name_len);
                        host[name_len] = '\0';
                        return (*env)->NewStringUTF(env, host);
                    }
                }
                pos += name_len;
            }
        }
        pos = next_ext_pos;
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativeGetOriginalDst(JNIEnv *env, jclass clazz, jint fd) {
    struct sockaddr_in destaddr;
    socklen_t destlen = sizeof(destaddr);

    int status = getsockopt(fd, IPPROTO_IP, SO_ORIGINAL_DST, (struct sockaddr *)&destaddr, &destlen);
    if (status == 0) {
        char ipStr[64];
        unsigned char *ip = (unsigned char *)&destaddr.sin_addr.s_addr;
        unsigned short port = ntohs(destaddr.sin_port);
        sprintf(ipStr, "%d.%d.%d.%d:%d", ip[0], ip[1], ip[2], ip[3], port);
        return (*env)->NewStringUTF(env, ipStr);
    }
    return NULL;
}

static byte* gLeafKeyDer = NULL;
static int gLeafKeyDerLen = 0;

JNIEXPORT jint JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativeLoadKeys(
    JNIEnv *env, jclass clazz, jbyteArray caKeyArray, jbyteArray leafKeyArray, jbyteArray leafKeyPkcs8Array) {

    jbyte* caKeyBytes = (*env)->GetByteArrayElements(env, caKeyArray, NULL);
    jsize caKeyLen = (*env)->GetArrayLength(env, caKeyArray);

    jbyte* leafKeyBytes = (*env)->GetByteArrayElements(env, leafKeyArray, NULL);
    jsize leafKeyLen = (*env)->GetArrayLength(env, leafKeyArray);

    jbyte* leafKeyPkcs8Bytes = (*env)->GetByteArrayElements(env, leafKeyPkcs8Array, NULL);
    jsize leafKeyPkcs8Len = (*env)->GetArrayLength(env, leafKeyPkcs8Array);

    word32 caIdx = 0;
    word32 leafIdx = 0;
    int ret;

    if (gKeysInitialized) {
        wc_FreeRsaKey(&gCaKey);
        wc_FreeRsaKey(&gLeafKey);
        gKeysInitialized = 0;
    }

    if (gLeafKeyDer) {
        free(gLeafKeyDer);
        gLeafKeyDer = NULL;
        gLeafKeyDerLen = 0;
    }

    wc_InitRsaKey(&gCaKey, NULL);
    wc_InitRsaKey(&gLeafKey, NULL);

    ret = wc_RsaPrivateKeyDecode((const byte*)caKeyBytes, &caIdx, &gCaKey, caKeyLen);
    if (ret != 0) {
        LOGE("CA private key decoding failed with error: %d", ret);
        goto cleanup;
    }

    ret = wc_RsaPrivateKeyDecode((const byte*)leafKeyBytes, &leafIdx, &gLeafKey, leafKeyLen);
    if (ret != 0) {
        LOGE("Leaf PKCS#1 private key decoding failed with error: %d", ret);
        goto cleanup;
    }

    gLeafKeyDer = malloc(leafKeyPkcs8Len);
    if (!gLeafKeyDer) {
        LOGE("Failed to allocate native memory for PKCS#8 Leaf key buffer!!");
        ret = -1;
        goto cleanup;
    }
    memcpy(gLeafKeyDer, leafKeyPkcs8Bytes, leafKeyPkcs8Len);
    gLeafKeyDerLen = leafKeyPkcs8Len;

    gKeysInitialized = 1;
    LOGI("Native keys loaded and cached successfully");

cleanup:
    (*env)->ReleaseByteArrayElements(env, caKeyArray, caKeyBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, leafKeyArray, leafKeyBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, leafKeyPkcs8Array, leafKeyPkcs8Bytes, JNI_ABORT);
    return ret;
}

JNIEXPORT jbyteArray JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativeGenerateMitmCert(
    JNIEnv *env, jclass clazz, jstring hostStr, jbyteArray caCertArray, jint sigType) {

    const char *host;
    jbyte* caCertBytes;
    jsize caCertLen;
    jbyteArray result;
    Cert cert;
    byte derBuffer[4096];
    WC_RNG rng;
    int certSz;
    int ret;

    if (!gKeysInitialized) {
        return NULL;
    }

    host = (*env)->GetStringUTFChars(env, hostStr, 0);
    caCertBytes = (*env)->GetByteArrayElements(env, caCertArray, NULL);
    caCertLen = (*env)->GetArrayLength(env, caCertArray);
    result = NULL;

    wc_InitCert(&cert);
    strncpy(cert.subject.commonName, host, CTC_NAME_SIZE - 1);
    cert.subject.commonName[CTC_NAME_SIZE - 1] = '\0';
    cert.isCA = 0;
    cert.sigType = (sigType == 1) ? CTC_SHA256wRSA : CTC_SHAwRSA;

    ret = wc_InitRng(&rng);
    if (ret != 0) goto cleanup;

    wc_RNG_GenerateBlock(&rng, cert.serial, 4);
    cert.serial[0] &= 0x7F;
    if (cert.serial[0] == 0x00) cert.serial[0] = 0x01;
    cert.serialSz = 4;

    set_cert_alt_names(&cert, host);

    pthread_mutex_lock(&gCertGenMutex);

    ret = wc_SetIssuerBuffer(&cert, (const byte*)caCertBytes, caCertLen);
    if (ret != 0) {
        pthread_mutex_unlock(&gCertGenMutex);
        wc_FreeRng(&rng);
        goto cleanup;
    }

    certSz = wc_MakeCert(&cert, derBuffer, sizeof(derBuffer), &gLeafKey, NULL, &rng);
    if (certSz < 0) {
        pthread_mutex_unlock(&gCertGenMutex);
        wc_FreeRng(&rng);
        goto cleanup;
    }

    certSz = wc_SignCert(cert.bodySz, cert.sigType, derBuffer, sizeof(derBuffer), &gCaKey, NULL, &rng);

    pthread_mutex_unlock(&gCertGenMutex);
    wc_FreeRng(&rng);

    if (certSz < 0) goto cleanup;

    result = (*env)->NewByteArray(env, certSz);
    (*env)->SetByteArrayRegion(env, result, 0, certSz, (jbyte*)derBuffer);

cleanup:
    if (caCertBytes) (*env)->ReleaseByteArrayElements(env, caCertArray, caCertBytes, JNI_ABORT);
    if (host) (*env)->ReleaseStringUTFChars(env, hostStr, host);

    return result;
}

JNIEXPORT jlong JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateContext
  (JNIEnv *env, jclass clazz) {
    WOLFSSL_CTX* ctx = wolfSSL_CTX_new(wolfSSLv23_client_method());
    if (ctx == NULL) return 0;

    // modern cipher list that completely excludes 3DES, DES, and RC4
    const char* modern_ciphers =
        "TLS13-AES256-GCM-SHA384:"
        "TLS13-AES128-GCM-SHA256:"
        "ECDHE-ECDSA-AES256-GCM-SHA384:"
        "ECDHE-RSA-AES256-GCM-SHA384:"
        "ECDHE-ECDSA-AES128-GCM-SHA256:"
        "ECDHE-RSA-AES128-GCM-SHA256:"
        "DHE-RSA-AES256-GCM-SHA384:"
        "DHE-RSA-AES128-GCM-SHA256:"
        "ECDHE-ECDSA-AES256-SHA384:"
        "ECDHE-RSA-AES256-SHA384:"
        "ECDHE-ECDSA-AES128-SHA256:"
        "ECDHE-RSA-AES128-SHA256:"
        "DHE-RSA-AES256-SHA256:"
        "DHE-RSA-AES128-SHA256";

    int ret = wolfSSL_CTX_set_cipher_list(ctx, modern_ciphers);
    if (ret != WOLFSSL_SUCCESS) {
        LOGE("Failed to set modern cipher list on client context: %d", ret);
    }

    wolfSSL_CTX_set_verify(ctx, WOLFSSL_VERIFY_NONE, 0);
    return (jlong)(unsigned long)ctx;
}

JNIEXPORT jlong JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateSSL
  (JNIEnv *env, jclass clazz, jlong ctxPtr, jint fd, jstring hostStr) {
    WOLFSSL_CTX* ctx = (WOLFSSL_CTX*)(unsigned long)ctxPtr;
    WOLFSSL* ssl = wolfSSL_new(ctx);
    if (ssl == NULL) return 0;

    wolfSSL_set_fd(ssl, fd);

    const char *host = (*env)->GetStringUTFChars(env, hostStr, 0);
    if (host != NULL) {
        // Only append SNI if the host string is a domain name (contains non-numeric/non-dot characters)
        int is_ip = 1;
        int len = strlen(host);
        for (int i = 0; i < len; i++) {
            if (!((host[i] >= '0' && host[i] <= '9') || host[i] == '.' || host[i] == ':')) {
                is_ip = 0;
                break;
            }
        }
        if (!is_ip) {
            wolfSSL_UseSNI(ssl, WOLFSSL_SNI_HOST_NAME, host, (unsigned short)len);
        }
        (*env)->ReleaseStringUTFChars(env, hostStr, host);
    }

    return (jlong)(unsigned long)ssl;
}

JNIEXPORT jint JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeHandshake
  (JNIEnv *env, jclass clazz, jlong sslPtr) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    int ret = wolfSSL_connect(ssl);
    if (ret != WOLFSSL_SUCCESS) {
        return wolfSSL_get_error(ssl, ret);
    }
    return 1;
}

JNIEXPORT jint JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeRead
  (JNIEnv *env, jclass clazz, jlong sslPtr, jbyteArray array, jint off, jint len) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    jbyte* buf = (*env)->GetByteArrayElements(env, array, NULL);
    if (buf == NULL) return -1;

    int result = wolfSSL_read(ssl, buf + off, len);

    (*env)->ReleaseByteArrayElements(env, array, buf, 0);
    return result;
}

JNIEXPORT jint JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeWrite
  (JNIEnv *env, jclass clazz, jlong sslPtr, jbyteArray array, jint off, jint len) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    jbyte* buf = (*env)->GetByteArrayElements(env, array, NULL);
    if (buf == NULL) return -1;

    int result = wolfSSL_write(ssl, buf + off, len);

    (*env)->ReleaseByteArrayElements(env, array, buf, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeFreeSSL
  (JNIEnv *env, jclass clazz, jlong sslPtr) {
    if (sslPtr != 0) wolfSSL_free((WOLFSSL*)(unsigned long)sslPtr);
}

JNIEXPORT void JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeFreeContext
  (JNIEnv *env, jclass clazz, jlong ctxPtr) {
    if (ctxPtr != 0) wolfSSL_CTX_free((WOLFSSL_CTX*)(unsigned long)ctxPtr);
}

JNIEXPORT jlong JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateServerContext(
    JNIEnv *env, jclass clazz, jstring hostStr, jbyteArray caCertArray, jint sigType) {

    const char *host = hostStr ? (*env)->GetStringUTFChars(env, hostStr, 0) : "unknown";
    jbyte* caCertBytes = (*env)->GetByteArrayElements(env, caCertArray, NULL);
    jsize caCertLen = (*env)->GetArrayLength(env, caCertArray);

    Cert cert;
    byte derBuffer[4096];
    WC_RNG rng;
    int certSz = -1;
    int ret;

    if (!gKeysInitialized || !gLeafKeyDer) {
        LOGE("Keys or PKCS#8 buffer not initialized!!");
        goto error;
    }

    wc_InitCert(&cert);
    strncpy(cert.subject.commonName, host, CTC_NAME_SIZE - 1);
    cert.subject.commonName[CTC_NAME_SIZE - 1] = '\0';
    cert.isCA = 0;
    cert.sigType = (sigType == 1) ? CTC_SHA256wRSA : CTC_SHAwRSA;

    ret = wc_InitRng(&rng);
    if (ret != 0) {
        LOGE("wc_InitRng failed: %d", ret);
        goto error;
    }

    wc_RNG_GenerateBlock(&rng, cert.serial, 4);
    cert.serial[0] &= 0x7F;
    if (cert.serial[0] == 0x00) cert.serial[0] = 0x01;
    cert.serialSz = 4;

    set_cert_alt_names(&cert, host);

    pthread_mutex_lock(&gCertGenMutex);

    ret = wc_SetIssuerBuffer(&cert, (const byte*)caCertBytes, caCertLen);
    if (ret != 0) {
        LOGE("wc_SetIssuerBuffer failed: %d", ret);
        pthread_mutex_unlock(&gCertGenMutex);
        wc_FreeRng(&rng);
        goto error;
    }

    certSz = wc_MakeCert(&cert, derBuffer, sizeof(derBuffer), &gLeafKey, NULL, &rng);
    if (certSz < 0) {
        LOGE("wc_MakeCert failed: %d", certSz);
        pthread_mutex_unlock(&gCertGenMutex);
        wc_FreeRng(&rng);
        goto error;
    }

    certSz = wc_SignCert(cert.bodySz, cert.sigType, derBuffer, sizeof(derBuffer), &gCaKey, NULL, &rng);

    pthread_mutex_unlock(&gCertGenMutex);
    wc_FreeRng(&rng);

    if (certSz < 0) {
        LOGE("wc_SignCert failed: %d", certSz);
        goto error;
    }

    WOLFSSL_CTX* local_ctx = wolfSSL_CTX_new(wolfSSLv23_server_method());
    if (local_ctx == NULL) {
        LOGE("wolfSSL_CTX_new failed for local server connection context!!");
        goto error;
    }

    ret = wolfSSL_CTX_use_PrivateKey_buffer(local_ctx, gLeafKeyDer, gLeafKeyDerLen, WOLFSSL_FILETYPE_ASN1);
    if (ret != WOLFSSL_SUCCESS) {
        LOGE("wolfSSL_CTX_use_PrivateKey_buffer failed: %d", ret);
        wolfSSL_CTX_free(local_ctx);
        goto error;
    }

    ret = wolfSSL_CTX_use_certificate_buffer(local_ctx, derBuffer, certSz, WOLFSSL_FILETYPE_ASN1);
    if (ret != WOLFSSL_SUCCESS) {
        LOGE("wolfSSL_CTX_use_certificate_buffer failed: %d", ret);
        wolfSSL_CTX_free(local_ctx);
        goto error;
    }

    (*env)->ReleaseByteArrayElements(env, caCertArray, caCertBytes, JNI_ABORT);
    if (hostStr) (*env)->ReleaseStringUTFChars(env, hostStr, host);

    return (jlong)(unsigned long)local_ctx;

error:
    if (caCertBytes) (*env)->ReleaseByteArrayElements(env, caCertArray, caCertBytes, JNI_ABORT);
    if (hostStr) (*env)->ReleaseStringUTFChars(env, hostStr, host);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateServerSSLFromContext(
    JNIEnv *env, jclass clazz, jint fd, jlong ctxPtr) {

    WOLFSSL_CTX* ctx = (WOLFSSL_CTX*)(unsigned long)ctxPtr;
    if (ctx == NULL) return 0;

    WOLFSSL* ssl = wolfSSL_new(ctx);
    if (ssl == NULL) return 0;

    wolfSSL_set_fd(ssl, fd);
    return (jlong)(unsigned long)ssl;
}

JNIEXPORT jint JNICALL Java_com_solar_launcher_tlsproxy_WolfClient_nativeAccept
  (JNIEnv *env, jclass clazz, jlong sslPtr) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    int ret = wolfSSL_accept(ssl);
    if (ret != WOLFSSL_SUCCESS) {
        return wolfSSL_get_error(ssl, ret);
    }
    return 1;
}

static const JNINativeMethod gMethods[] = {
    { "nativeCreateContext", "()J", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateContext },
    { "nativeCreateSSL", "(JILjava/lang/String;)J", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateSSL },
    { "nativeCreateServerContext", "(Ljava/lang/String;[BI)J", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateServerContext },
    { "nativeCreateServerSSLFromContext", "(IJ)J", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeCreateServerSSLFromContext },
    { "nativeHandshake", "(J)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeHandshake },
    { "nativeAccept", "(J)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeAccept },
    { "nativeRead", "(J[BII)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeRead },
    { "nativeWrite", "(J[BII)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeWrite },
    { "nativeFreeSSL", "(J)V", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeFreeSSL },
    { "nativeFreeContext", "(J)V", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeFreeContext },
    { "nativeLoadKeys", "([B[B[B)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeLoadKeys },
    { "nativeGenerateMitmCert", "(Ljava/lang/String;[BI)[B", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeGenerateMitmCert },
    { "nativePeekFirstByte", "(I)I", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativePeekFirstByte },
    { "nativePeekAndParseSNI", "(I)Ljava/lang/String;", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativePeekAndParseSNI },
    { "nativeGetOriginalDst", "(I)Ljava/lang/String;", (void*)Java_com_solar_launcher_tlsproxy_WolfClient_nativeGetOriginalDst }
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = (*env)->FindClass(env, "com/solar/launcher/tlsproxy/WolfClient");
    if (clazz == NULL) return JNI_ERR;

    if ((*env)->RegisterNatives(env, clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        return JNI_ERR;
    }

    wolfSSL_Init();

    wolfSSL_SetLoggingCb(android_wolfssl_log_cb);

    return JNI_VERSION_1_4;
}