// Host KAT for the seed-buffer ownership rule — security cycle v4.0.58, finding 1.
//
// THE BUG THIS GUARDS
// -------------------
// Every seed-taking function in jni_derive.c used to derive straight from the
// pointer `GetByteArrayElements` handed back, then call
// `secure_zero(seedRaw, seedLen)` before releasing with JNI_ABORT. `isCopy` was
// passed NULL, so the code never learned whether that pointer addressed a private
// copy or the live Java array. JNI_ABORT discards writes to a *copy*; it cannot
// discard writes that already landed on the Java heap.
//
// LegacySweepService is where that turns into lost funds rather than a curiosity:
// it loads the seed ONCE (`sweepFromSeed`) and calls `sweepOneProfile(seedBytes, …)`
// per derivation profile in a loop, each reaching `buildAndSignLegacySweep`. On a
// VM that hands back a direct pointer, profile #1 wipes the shared array and every
// later profile derives from 64 zero bytes — signing with the wrong keys, silently.
//
// WHAT IS AND IS NOT PROVEN HERE
// ------------------------------
// This KAT does NOT settle whether ART returns a copy or a direct pointer. That
// question is the whole problem: it is unspecified, so it must not be built on in
// either direction. What it proves is that the code no longer *depends* on the
// answer — the source buffer survives regardless, because it is never written to.
// The KAT therefore models the adverse case (direct pointer) and requires the
// production shape to hold up under it.
//
// TWO ARMS (run.sh builds both; see the red-before-green rationale there)
//   default:                production jni_seed_buffer.h — copies, wipes only the
//                           copy. MUST print ALL PASS and exit 0.
//   -DSEED_BUFFER_UNFIXED:  the v4.0.58 shape — aliases the source and wipes it.
//                           MUST fail test2/test4 and exit non-zero.
//
// Compiler: clang, -fsanitize=address, same conventions as the sibling
// saved_blocks_reentrant_kat.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "jni_seed_buffer.h"

static int g_fail = 0;
static void check(int c, const char *d) {
    printf(c ? "PASS: %s\n" : "FAIL: %s\n", d);
    if (!c) g_fail++;
}

/* A recognisable 64-byte seed. Deliberately contains no zero byte, so "was it
 * wiped?" is answerable byte-for-byte and a partial wipe cannot hide. */
#define SEED_LEN 64u
static void make_seed(uint8_t *out) {
    for (unsigned i = 0; i < SEED_LEN; i++) out[i] = (uint8_t)(0x11u + i);
}

/* Counts matching bytes rather than asking "did anything differ".
 * A gate that only ever asserts absence passes just as happily when it is
 * observing nothing at all, so every check below lands on a POSITIVE count. */
static unsigned bytes_matching(const uint8_t *a, const uint8_t *b, size_t n) {
    unsigned m = 0;
    for (size_t i = 0; i < n; i++) if (a[i] == b[i]) m++;
    return m;
}
static unsigned bytes_zero(const uint8_t *a, size_t n) {
    unsigned z = 0;
    for (size_t i = 0; i < n; i++) if (a[i] == 0) z++;
    return z;
}

int main(void) {
    printf("=== seed_buffer_ownership_kat ===\n");
#ifdef SEED_BUFFER_UNFIXED
    printf("arm: UNFIXED (models the v4.0.58 shape)\n");
#else
    printf("arm: production\n");
#endif

    /* ---- test1: the copy carries the seed --------------------------------- */
    uint8_t src[SEED_LEN], pristine[SEED_LEN];
    make_seed(src);
    make_seed(pristine);

    SeedBuffer sb;
    int took = seed_buffer_take(&sb, src, SEED_LEN);
    check(took == 1, "test1: take() accepts a 64-byte seed");
    check(sb.len == SEED_LEN, "test1: take() records the length");
    check(bytes_matching(sb.bytes, pristine, SEED_LEN) == SEED_LEN,
          "test1: all 64 bytes reached the private copy");

    /* ---- test2: the source is untouched by the copy ----------------------- */
    check(bytes_matching(src, pristine, SEED_LEN) == SEED_LEN,
          "test2: take() left all 64 source bytes intact");

    /* ---- test3: release wipes the copy ------------------------------------ */
    seed_buffer_release(&sb);
    check(bytes_zero(sb.bytes, SEED_BUFFER_MAX) == SEED_BUFFER_MAX,
          "test3: release() zeroed every byte of the private copy");
    check(sb.len == 0, "test3: release() cleared the length");

    /* ---- test4: release did NOT wipe the caller's buffer -------------------
     * This is the assertion the pre-fix shape cannot satisfy. */
    check(bytes_matching(src, pristine, SEED_LEN) == SEED_LEN,
          "test4: release() left all 64 source bytes intact");

    /* ---- test5: the LegacySweepService shape ------------------------------
     * One seed array, one sweep per derivation profile, in a loop. Mirrors
     * sweepFromSeed -> sweepOneProfile(seedBytes, …) -> buildAndSignLegacySweep.
     * Profile #1 is not the interesting one; #2 and #3 are. */
    uint8_t shared[SEED_LEN];
    make_seed(shared);
    unsigned profiles_with_good_seed = 0;
    const unsigned PROFILE_COUNT = 3u;

    for (unsigned p = 0; p < PROFILE_COUNT; p++) {
        SeedBuffer per_profile;
        if (!seed_buffer_take(&per_profile, shared, SEED_LEN)) continue;
        /* stand-in for BRBIP32PrivKeyArrayPath(&keys[i], seed, len, …) */
        if (bytes_matching(per_profile.bytes, pristine, SEED_LEN) == SEED_LEN)
            profiles_with_good_seed++;
        seed_buffer_release(&per_profile);
    }
    printf("       (profiles that derived from an intact seed: %u of %u)\n",
           profiles_with_good_seed, PROFILE_COUNT);
    check(profiles_with_good_seed == PROFILE_COUNT,
          "test5: every profile in the sweep loop saw an intact seed");

    /* ---- test6: bounds ---------------------------------------------------- */
    SeedBuffer b;
    uint8_t big[SEED_BUFFER_MAX + 1];
    memset(big, 0x5A, sizeof(big));
    check(seed_buffer_take(&b, big, sizeof(big)) == 0,
          "test6: take() refuses a seed longer than 64 bytes");
    check(bytes_zero(b.bytes, SEED_BUFFER_MAX) == SEED_BUFFER_MAX,
          "test6: a refused take() leaves the buffer zeroed, not partly filled");
    check(seed_buffer_take(&b, src, 0u) == 0, "test6: take() refuses a zero length");
    check(seed_buffer_take(&b, NULL, SEED_LEN) == 0, "test6: take() refuses a NULL source");
    check(seed_buffer_take(&b, src, SEED_BUFFER_MAX) == 1,
          "test6: take() accepts exactly SEED_BUFFER_MAX");
    seed_buffer_release(&b);

    if (g_fail) {
        printf("FAILURES: %d\n", g_fail);
        return 1;
    }
    printf("ALL PASS\n");
    return 0;
}
