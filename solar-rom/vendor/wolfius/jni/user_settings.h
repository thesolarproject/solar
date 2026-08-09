#ifndef WOLFSSL_USER_SETTINGS_H
#define WOLFSSL_USER_SETTINGS_H

//#define DEBUG_WOLFSSL

#define SIZEOF_LONG_LONG 8

#define NO_FILESYSTEM
#define NO_WRITEV
#define NO_DEV_RANDOM

#define WOLFSSL_TLS12
#define WOLFSSL_TLS13
#define WOLFSSL_ALLOW_TLSV10
#define WOLFSSL_CERT_GEN
#define WOLFSSL_KEY_GEN
#define WOLFSSL_ALT_NAMES
#define WOLFSSL_ASN_ALLOW_0_SERIAL

#define HAVE_HKDF
#define HAVE_AEAD
#define HAVE_FFDHE_2048
#define WC_RSA_PSS
#define HAVE_AESGCM
#define WOLFSSL_SHA384
#define WOLFSSL_SHA512
#define WOLFSSL_SP_MATH_ALL
#define WOLFSSL_ALLOW_TLS_SHA1
#define WOLFSSL_STATIC_RSA

#define HAVE_SUPPORTED_CURVES
#define HAVE_ECC

#define HAVE_TLS_EXTENSIONS
#define HAVE_SNI

#ifndef __ASSEMBLER__
#include <fcntl.h>
#include <unistd.h>
//Custom random seed function. We cannot rely on wolfSSL itself to read /dev/urandom—by removing NO_DEV_URANDOM, we're forced to
//remove NO_FILESYSTEM as well, and removing that will bloat the wolfSSL binary with libc filesystem dependencies
static inline int android_get_seed(unsigned char* output, unsigned int sz) {
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) {
        return -1;
    }

    unsigned int total_read = 0;
    while (total_read < sz) {
        int bytes_read = read(fd, output + total_read, sz - total_read);
        if (bytes_read <= 0) {
            close(fd);
            return -1;
        }
        total_read += bytes_read;
    }

    close(fd);
    return 0;
}
#endif /* __ASSEMBLER__ */

#define CUSTOM_RAND_GENERATE_SEED android_get_seed

// Architecture-Specific CPU Optimizations
#if defined(__x86_64__) || defined(__aarch64__)
    #define HAVE___UINT128_T
#endif

#if defined(__x86_64__) || defined(_M_X64)
    #define WOLFSSL_SP_X86_64_ASM
#elif defined(__i386__) || defined(_M_IX86)
    #define SP_WORD_SIZE 32
#elif defined(__mips__) || defined(__mips)
    #define SP_WORD_SIZE 32
#elif defined(__arm__) || defined(__thumb__)
    #define SP_WORD_SIZE 32
    #ifndef __ARM_ARCH
        #if defined(__ARM_ARCH_7A__) || defined(__ARM_ARCH_7__)
            #define __ARM_ARCH 7
        #elif defined(__ARM_ARCH_6__) || defined(__ARM_ARCH_6J__) || defined(__ARM_ARCH_6K__) || defined(__ARM_ARCH_6Z__) || defined(__ARM_ARCH_6ZK__)
            #define __ARM_ARCH 6
        #elif defined(__ARM_ARCH_5TE__) || defined(__ARM_ARCH_5E__) || defined(__ARM_ARCH_5T__) || defined(__ARM_ARCH_5__)
            #define __ARM_ARCH 5
        #else
            #define __ARM_ARCH 4
        #endif
    #endif
    #if defined(__ARM_ARCH) && (__ARM_ARCH >= 7)
        #define WOLFSSL_SP_ARM32_ASM
        #if defined(__thumb__) || defined(__thumb2__)
            #define WOLFSSL_SP_ARM_THUMB_ASM
        #endif
    #endif
#endif

#endif /* WOLFSSL_USER_SETTINGS_H */