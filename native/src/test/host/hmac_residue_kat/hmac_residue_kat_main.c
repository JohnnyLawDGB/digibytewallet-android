//
//  hmac_residue_kat_main.c
//
//  Host KAT for what BRHMAC (BRCrypto.c) leaves on the stack. HMAC(key, data) = H((key ^ opad) || H((key ^ ipad) ||
//  data)), and BRHMAC builds both of H's inputs in stack arrays: kipad = (key ^ ipad) || data, and kopad = (key ^ opad)
//  || the inner hash. Until 2026-11-17 it cleared only each array's first block, the key's, so the data and the inner
//  hash stayed in its dead frame. That is where the secrets are:
//    - the BIP32 master key is HMAC-SHA512("Bitcoin seed" or "DigiByte seed", seed): the data is the wallet's seed, and
//      as everyone knows the key, the inner hash alone gives the master key and chain code (one more SHA-512);
//    - each hardened BIP32 step is HMAC-SHA512(chain code, 00 || parent private key || index): the data holds the
//      parent key.
//  (Review round 1 of the Coinomi Digi-ID step 1 found it, with a probe; it was older than that change and the same for
//  every root.)
//
//  Each case runs BRHMAC once, on a thread whose stack this program owns, at the bottom of a call 64 KiB deep, so that
//  nothing the thread runs while it ends reaches that far down. When the thread has ended, the whole stack is searched
//  for any 16 bytes in a row of what BRHMAC must not leave there: the data, the inner hash, the MAC, the key, and the
//  key's two pads where they hold key bytes. Every input and answer lives outside that stack (in this file's static
//  storage, and on the main thread's stack), so whatever the search finds, BRHMAC or a function it called put there.
//  The MAC is held to a published vector as well, so that the wipe is all that changes.
//
//    master     HMAC-SHA512("Bitcoin seed", BIP32 test vector 2's 64-byte seed): that vector's master key and chain code.
//    hardened   HMAC-SHA512(m's chain code, 00 || m's key || ser32(0x80000000)) for BIP32 test vector 1: IL and IR,
//               IR being m/0H's chain code (IL + m's key mod n is m/0H's key).
//    long_key   HMAC-SHA256(131 bytes 0xaa, "Test Using Larger Than Block-Size Key - Hash Key First"): RFC 4231 case 6,
//               SHA-256's 64-byte block, and a key longer than it, which BRHMAC hashes first.
//
//  `hmac_residue_kat <case>` prints PASS:/FAIL: lines, each naming its check (depth, mac, data, inner, answer, key), and
//  `RESULT <case> pass|fail`; `list` prints the cases; `arm` says which arm the binary was built as.
//  MACRO CONVENTION: PRESENCE of -DHMAC_PAD_RESIDUE_UNFIXED (BRCrypto.c) is the comparison arm.
//
//  No sanitizer: AddressSanitizer may move a function's locals to a fake stack of its own, which a search of the
//  thread's stack cannot see. derivation_vectors_kat, slip13_identity_kat and digiid_core_kat run BRHMAC under ASan.
//

#include "BRCrypto.h"
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STACK_SIZE (1u << 20)
#define WINDOW 16
#define DEPTH 65536

static int FAILED = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (! cond) FAILED = 1;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

typedef struct {
    const char *name;
    void (*hash)(void *, const void *, size_t);
    size_t hashLen, blockLen;
    const uint8_t *key;
    size_t keyLen;
    const uint8_t *data;
    size_t dataLen;
    const char *mac;   // hex: the vector's MAC, which Python's hmac gives again
} Case;

// BIP32 test vector 2's seed; test vector 1's m (key, then chain code) and the data of its m/0H step.
static const uint8_t TV2_SEED[64] = {
    0xff, 0xfc, 0xf9, 0xf6, 0xf3, 0xf0, 0xed, 0xea, 0xe7, 0xe4, 0xe1, 0xde, 0xdb, 0xd8, 0xd5, 0xd2,
    0xcf, 0xcc, 0xc9, 0xc6, 0xc3, 0xc0, 0xbd, 0xba, 0xb7, 0xb4, 0xb1, 0xae, 0xab, 0xa8, 0xa5, 0xa2,
    0x9f, 0x9c, 0x99, 0x96, 0x93, 0x90, 0x8d, 0x8a, 0x87, 0x84, 0x81, 0x7e, 0x7b, 0x78, 0x75, 0x72,
    0x6f, 0x6c, 0x69, 0x66, 0x63, 0x60, 0x5d, 0x5a, 0x57, 0x54, 0x51, 0x4e, 0x4b, 0x48, 0x45, 0x42,
};
static const uint8_t TV1_CHAIN[32] = {
    0x87, 0x3d, 0xff, 0x81, 0xc0, 0x2f, 0x52, 0x56, 0x23, 0xfd, 0x1f, 0xe5, 0x16, 0x7e, 0xac, 0x3a,
    0x55, 0xa0, 0x49, 0xde, 0x3d, 0x31, 0x4b, 0xb4, 0x2e, 0xe2, 0x27, 0xff, 0xed, 0x37, 0xd5, 0x08,
};
static const uint8_t TV1_HARDENED_DATA[37] = {
    0x00, 0xe8, 0xf3, 0x2e, 0x72, 0x3d, 0xec, 0xf4, 0x05, 0x1a, 0xef, 0xac, 0x8e, 0x2c, 0x93, 0xc9, 0xc5,
    0xb2, 0x14, 0x31, 0x38, 0x17, 0xcd, 0xb0, 0x1a, 0x14, 0x94, 0xb9, 0x17, 0xc8, 0x43, 0x6b, 0x35,
    0x80, 0x00, 0x00, 0x00,
};
static uint8_t LONG_KEY[131];   // 0xaa, set in main
static const char LONG_DATA[] = "Test Using Larger Than Block-Size Key - Hash Key First";

static const Case CASES[] = {
    { "master", BRSHA512, 64, 128, (const uint8_t *)"Bitcoin seed", 12, TV2_SEED, sizeof(TV2_SEED),
      "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e"
      "60499f801b896d83179a4374aeb7822aaeaceaa0db1f85ee3e904c4defbd9689" },
    { "hardened", BRSHA512, 64, 128, TV1_CHAIN, sizeof(TV1_CHAIN), TV1_HARDENED_DATA, sizeof(TV1_HARDENED_DATA),
      "04bfb2dd60fa8921c2a4085ec15507a921f49cdc839f27f0f280e9c1495d44b5"
      "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141" },
    { "long_key", BRSHA256, 32, 64, LONG_KEY, sizeof(LONG_KEY), (const uint8_t *)LONG_DATA, sizeof(LONG_DATA) - 1,
      "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54" },
};

#define N(a) (sizeof(a) / sizeof((a)[0]))

// The run: inputs and the MAC in static storage, never on the stack that is searched.
static const Case *g_case;
static uint8_t g_mac[64];
static uint8_t *g_stack;
static uintptr_t g_below;   // where deep()'s 64 KiB began, to check that they are on the searched stack

#if defined(__GNUC__) || defined(__clang__)
#define NOINLINE __attribute__((noinline))
#else
#define NOINLINE
#endif

static NOINLINE void hmacOnce(void)
{
    BRHMAC(g_mac, g_case->hash, g_case->hashLen, g_case->key, g_case->keyLen, g_case->data, g_case->dataLen);
}

// Writes both ends of p[0, n). Its own function, so that `below` has an address that leaves deep() and keeps its whole
// size: clang -O2 splits a local array that only deep() itself indexes into the few bytes it touches.
static NOINLINE void touch(volatile uint8_t *p, size_t n)
{
    p[0] = 1;
    p[n - 1] = 1;
}

static NOINLINE void deep(void)
{
    uint8_t below[DEPTH];   // BRHMAC's frame lies under these 64 KiB

    touch(below, sizeof(below));
    g_below = (uintptr_t)below;
    hmacOnce();
    touch(below, sizeof(below));
}

static void *thread(void *arg)
{
    (void)arg;
    deep();
    return NULL;
}

// Where `secret` has WINDOW bytes in a row in the searched stack: the first offset, or -1.
static long found(const uint8_t *secret, size_t len)
{
    size_t i, at;

    for (i = 0; i + WINDOW <= len; i++) {
        for (at = 0; at + WINDOW <= STACK_SIZE; at++) {
            if (g_stack[at] == secret[i] && memcmp(g_stack + at, secret + i, WINDOW) == 0) return (long)at;
        }
    }
    return -1;
}

static int unhex(uint8_t *out, size_t cap, const char *hex)
{
    size_t n = strlen(hex) / 2, i;

    if (n > cap) return -1;
    for (i = 0; i < n; i++) {
        unsigned v;
        if (sscanf(hex + 2 * i, "%2x", &v) != 1) return -1;
        out[i] = (uint8_t)v;
    }
    return (int)n;
}

static void run(const Case *c)
{
    uint8_t want[64], block[128], pads[2][128], inner[64], hashedKey[64], *innerIn;
    size_t keyLen = c->keyLen, i;
    const uint8_t *key = c->key;
    pthread_attr_t attr;
    pthread_t t;
    long at;
    int wantLen = unhex(want, sizeof(want), c->mac);

    // What BRHMAC computes on the way, worked out here on the main thread: the key, hashed when it is longer than a
    // block; its two pads; and the inner hash.
    if (keyLen > c->blockLen) {
        c->hash(hashedKey, key, keyLen);
        key = hashedKey;
        keyLen = c->hashLen;
    }
    memset(block, 0, sizeof(block));
    memcpy(block, key, keyLen);
    for (i = 0; i < c->blockLen; i++) {
        pads[0][i] = block[i] ^ 0x36;
        pads[1][i] = block[i] ^ 0x5c;
    }
    innerIn = malloc(c->blockLen + c->dataLen);
    if (! innerIn) { ck(0, "%s: malloc", c->name); return; }
    memcpy(innerIn, pads[0], c->blockLen);
    memcpy(innerIn + c->blockLen, c->data, c->dataLen);
    c->hash(inner, innerIn, c->blockLen + c->dataLen);
    free(innerIn);

    g_case = c;
    memset(g_mac, 0, sizeof(g_mac));
    if (posix_memalign((void **)&g_stack, 4096, STACK_SIZE) != 0) { ck(0, "%s: no stack", c->name); return; }
    memset(g_stack, 0x5a, STACK_SIZE);
    if (pthread_attr_init(&attr) != 0 || pthread_attr_setstack(&attr, g_stack, STACK_SIZE) != 0 ||
        pthread_create(&t, &attr, thread, NULL) != 0 || pthread_join(t, NULL) != 0) {
        ck(0, "%s: the thread on this program's own stack did not run", c->name);
        free(g_stack);
        return;
    }
    pthread_attr_destroy(&attr);

    ck(g_below >= (uintptr_t)g_stack && g_below + DEPTH <= (uintptr_t)g_stack + STACK_SIZE,
       "%s depth: the HMAC ran under 64 KiB of the thread's own stack, the one searched", c->name);
    ck(wantLen == (int)c->hashLen && memcmp(g_mac, want, c->hashLen) == 0, "%s mac: the vector's", c->name);
    at = found(c->data, c->dataLen);
    ck(at < 0, "%s data: none of the %zu data bytes is left on the stack (%ld)", c->name, c->dataLen, at);
    at = found(inner, c->hashLen);
    ck(at < 0, "%s inner: none of the inner hash is left on the stack (%ld)", c->name, at);
    at = found(g_mac, c->hashLen);
    ck(at < 0, "%s answer: none of the MAC is left on the stack (%ld)", c->name, at);
    at = keyLen >= WINDOW ? found(key, keyLen) : -1;
    if (at < 0 && key != c->key && c->keyLen >= WINDOW) at = found(c->key, c->keyLen);
    if (at < 0 && keyLen >= WINDOW) at = found(pads[0], keyLen);
    if (at < 0 && keyLen >= WINDOW) at = found(pads[1], keyLen);
    ck(at < 0, "%s key: none of the key%s or its pads' key bytes is left on the stack (%ld)", c->name,
       key != c->key ? ", its hash" : "", at);
    mem_clean(inner, sizeof(inner));
    free(g_stack);
}

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    memset(LONG_KEY, 0xaa, sizeof(LONG_KEY));
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list|arm\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    if (strcmp(argv[1], "arm") == 0) {
#ifdef HMAC_PAD_RESIDUE_UNFIXED
        printf("arm: comparison, %d-bit\n", (int)(8 * sizeof(void *)));
#else
        printf("arm: shipped, %d-bit\n", (int)(8 * sizeof(void *)));
#endif
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        run(&CASES[i]);
        printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
        return FAILED ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
