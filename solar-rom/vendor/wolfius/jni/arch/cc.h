#ifndef LWIP_ARCH_CC_H
#define LWIP_ARCH_CC_H

// 1. Temporarily clear lwIP's definitions so system headers don't collide
#undef LITTLE_ENDIAN
#undef BIG_ENDIAN

// 2. Include standard system/NDK headers
#include <stdint.h>
#include <sys/types.h>
#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h> // Provides standard system htons, htonl, etc.

// 3. Clear system definitions to avoid namespace pollution
#undef LITTLE_ENDIAN
#undef BIG_ENDIAN

// 4. Restore lwIP's strictly expected values
#define LITTLE_ENDIAN 1234
#define BIG_ENDIAN    4321

// Primitive types used by lwIP
typedef uint8_t u8_t;
typedef int8_t s8_t;
typedef uint16_t u16_t;
typedef int16_t s16_t;
typedef uint32_t u32_t;
typedef int32_t s32_t;
typedef uintptr_t mem_ptr_t;

// Provide a pseudo-random number generator macro for lwIP DNS transaction security
#define LWIP_RAND() ((u32_t)rand())

// Endianness detection utilizing compiler built-in macros
#ifndef BYTE_ORDER
  #if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__)
    #if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
      #define BYTE_ORDER LITTLE_ENDIAN
    #else
      #define BYTE_ORDER BIG_ENDIAN
    #endif
  #else
    #define BYTE_ORDER LITTLE_ENDIAN
  #endif
#endif

// Compiler hints for struct packing (GCC/Clang attributes)
#define PACK_STRUCT_BEGIN
#define PACK_STRUCT_STRUCT __attribute__((packed))
#define PACK_STRUCT_END
#define PACK_STRUCT_FIELD(x) x

// Platform specific diagnostic output mapping to standard output
#define LWIP_PLATFORM_DIAG(x) do { printf x; } while(0)
#define LWIP_PLATFORM_ASSERT(x) do { \
    printf("Assertion \"%s\" failed at line %d in %s\n", x, __LINE__, __FILE__); \
    fflush(NULL); \
    abort(); \
} while(0)

// Standard integer formatting flags for printf/scanf
#define U16_F "u"
#define S16_F "d"
#define X16_F "x"
#define U32_F "u"
#define S32_F "d"
#define X32_F "x"
#define SZT_F "zu"

#endif /* LWIP_ARCH_CC_H */