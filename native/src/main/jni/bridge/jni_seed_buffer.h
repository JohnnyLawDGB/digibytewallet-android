/**
 * jni_seed_buffer.h — own a private copy of seed material handed in through JNI.
 *
 * WHY THIS EXISTS
 * ---------------
 * `GetByteArrayElements` may return either a pointer to a private copy or a
 * pointer to the live Java array; which one you get is up to the VM, and the
 * `isCopy` out-param is the only way to find out. `JNI_ABORT` on release means
 * "discard changes" — but it can only discard changes made to a *copy*. If the
 * VM handed back a direct pointer, anything already written through it has
 * landed on the Java heap and cannot be taken back.
 *
 * So native code must not write through a JNI array pointer it does not own.
 * Zeroing seed material is the case where that rule is easiest to break, because
 * zeroing feels unambiguously like the careful thing to do.
 *
 * It is not, when the buffer belongs to the caller. `SeedProvider` (Kotlin) states
 * the contract explicitly — "the returned array is owned by the caller, who MUST
 * fill(0) it in a finally" — and `LegacySweepService` relies on it: it loads the
 * seed once and passes the same array to one sweep per derivation profile. Native
 * code that wipes that array in place empties the caller's seed after profile #1,
 * and every later profile derives from 64 zero bytes: wrong keys, silently, on a
 * funds path.
 *
 * `mnemonicToSeed` in jni_derive.c already got this right for its phrase argument,
 * and said why — "copy to an on-stack buffer so we can NUL-terminate without
 * mutating the caller's array". This header is that same reasoning, generalised so
 * the seed paths can share it and so it can be tested on a host build, where
 * <jni.h> does not exist.
 *
 * USE
 * ---
 *     jbyte *raw = (*env)->GetByteArrayElements(env, seedBytes, NULL);
 *     if (!raw) return NULL;
 *     SeedBuffer seed;
 *     int took = seed_buffer_take(&seed, raw, (size_t)seedLen);
 *     (*env)->ReleaseByteArrayElements(env, seedBytes, raw, JNI_ABORT);
 *     if (!took) return NULL;
 *     ... derive from seed.bytes / seed.len ...
 *     seed_buffer_release(&seed);
 *
 * Releasing the JNI array immediately, on the line after the copy, is deliberate:
 * it means no later branch *can* reach for `raw`, so the error paths cannot drift
 * back into the shape this header exists to prevent.
 *
 * Covered by native/src/test/host/seed_buffer_ownership_kat.
 */
#ifndef JNI_SEED_BUFFER_H
#define JNI_SEED_BUFFER_H

#include <stdint.h>
#include <string.h>

/* A BIP39 seed is 64 bytes. Sizing to that also bounds a length that otherwise
 * arrives from Java unchecked and is handed straight to BRBIP32PrivKeyArrayPath. */
#define SEED_BUFFER_MAX 64u

typedef struct {
    uint8_t bytes[SEED_BUFFER_MAX];
    size_t  len;
#ifdef SEED_BUFFER_UNFIXED
    void   *alias;   /* red arm only — see below */
#endif
} SeedBuffer;

/* volatile so the zeroing survives optimisation; the compiler cannot prove the
 * writes are unobservable and drop them. */
static inline void seed_buffer_wipe(volatile void *p, size_t n) {
    volatile uint8_t *v = (volatile uint8_t *)p;
    while (n--) *v++ = 0;
}

/**
 * Copy `len` bytes from `src` into `sb`.
 *
 * @return 1 on success, 0 if `len` is zero or larger than SEED_BUFFER_MAX (in
 *         which case `sb` is left zeroed and nothing is copied).
 *
 * Never writes through `src`.
 */
static inline int seed_buffer_take(SeedBuffer *sb, const void *src, size_t len) {
    if (!sb) return 0;
    seed_buffer_wipe(sb->bytes, SEED_BUFFER_MAX);
    sb->len = 0;
#ifdef SEED_BUFFER_UNFIXED
    sb->alias = NULL;
#endif
    if (!src || len == 0u || len > SEED_BUFFER_MAX) return 0;

#ifdef SEED_BUFFER_UNFIXED
    /* ---- RED ARM ONLY: models the shape that shipped in v4.0.58 ----------
     * The pre-fix code never copied. It derived straight from the JNI pointer
     * and then zeroed *that*, so this arm keeps the source pointer and wipes it
     * on release. Present so the KAT has a genuine pre-fix shape to go red on,
     * rather than a hand-written imitation that could drift away from what the
     * bug actually was. Never compiled into the app. */
    sb->alias = (void *)src;
#endif
    memcpy(sb->bytes, src, len);
    sb->len = len;
    return 1;
}

/** Zero the private copy. Never touches whatever `src` was. */
static inline void seed_buffer_release(SeedBuffer *sb) {
    if (!sb) return;
#ifdef SEED_BUFFER_UNFIXED
    /* RED ARM ONLY: the pre-fix shape wiped the caller's buffer. */
    if (sb->alias) seed_buffer_wipe(sb->alias, sb->len);
#endif
    seed_buffer_wipe(sb->bytes, SEED_BUFFER_MAX);
    sb->len = 0;
}

#endif /* JNI_SEED_BUFFER_H */
