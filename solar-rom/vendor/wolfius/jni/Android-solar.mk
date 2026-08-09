# Solar build of the Wolfius wolfSSL MITM core (see solar-rom/vendor/wolfius/README-SOLAR.md).
# Only the libwolfssl.so module is built — lwIP (VpnService/PPTP) is a later stage.
# Based on Wolfius app/src/main/jni/Android.mk (GPLv3, gohoski/Wolfius).
LOCAL_PATH := $(call my-dir)

# libwolfssl.so — wolfSSL TLS 1.0/1.2/1.3 + the Solar TLS-proxy JNI bridge.
include $(CLEAR_VARS)

LOCAL_MODULE := wolfssl
WOLFSSL_ROOT := wolfssl

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/$(WOLFSSL_ROOT) \
    $(LOCAL_PATH)/$(WOLFSSL_ROOT)/wolfssl \
    $(LOCAL_PATH)/

LOCAL_CFLAGS := -DWOLFSSL_USER_SETTINGS -std=c99 -O3 -fomit-frame-pointer
LOCAL_LDLIBS := -llog

LOCAL_SRC_FILES := \
    $(WOLFSSL_ROOT)/src/internal.c \
    $(WOLFSSL_ROOT)/src/ssl.c \
    $(WOLFSSL_ROOT)/src/tls.c \
    $(WOLFSSL_ROOT)/src/tls13.c \
    $(WOLFSSL_ROOT)/src/wolfio.c \
    $(WOLFSSL_ROOT)/src/keys.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/aes.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/asn.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/coding.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/ecc.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/error.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/hmac.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/integer.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/logging.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/random.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/rsa.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/sha.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/sha256.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/sha512.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/wc_port.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/memory.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/md5.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/des3.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/dh.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/dsa.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/hash.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/cpuid.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/sp_int.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/kdf.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/wolfmath.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/pwdbased.c \
    $(WOLFSSL_ROOT)/wolfcrypt/src/wc_encrypt.c \
    wolfssl_bridge.c

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_SRC_FILES += $(WOLFSSL_ROOT)/wolfcrypt/src/sp_arm32.c
endif
ifeq ($(TARGET_ARCH_ABI),x86)
    LOCAL_CFLAGS += -msse2
endif

include $(BUILD_SHARED_LIBRARY)
