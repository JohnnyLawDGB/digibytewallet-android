// Force-included (-include) into every translation unit of wallet_copy_guards_kat.
//
// glibc declares memcpy, memmove and memcmp with __attribute__((nonnull)), so UBSan's nonnull-attribute
// check stops a call that passes NULL even for 0 bytes -- undefined behaviour in C either way. Apple's
// libc declares them without the attribute, so on macOS the same call goes unreported. These
// redeclarations add the attribute, so a Mac run sees what a Linux run sees. (Redeclaring a libc function
// with an added attribute is allowed; it changes nothing on glibc, which already carries it.)
//
// run.sh builds with _FORTIFY_SOURCE=0: Apple's fortified <string.h> turns these calls into
// __builtin___*_chk macros, which would bypass the declarations below.
#ifndef WALLET_COPY_GUARDS_GLIBC_NONNULL_H
#define WALLET_COPY_GUARDS_GLIBC_NONNULL_H

#include <string.h>

extern void *(memcpy)(void *, const void *, size_t) __attribute__((nonnull(1, 2)));
extern void *(memmove)(void *, const void *, size_t) __attribute__((nonnull(1, 2)));
extern int (memcmp)(const void *, const void *, size_t) __attribute__((nonnull(1, 2)));

#endif
