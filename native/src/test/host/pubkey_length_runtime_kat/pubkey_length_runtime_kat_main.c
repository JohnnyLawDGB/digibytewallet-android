// Host KAT: BRKeySetPubKey takes a public key only when it has a public key's length -- 33 bytes
// (compressed) or 65 (uncompressed) -- and that check exists at runtime, in every build type.
//
// BRKey.pubKey holds 65 bytes. An assert() is not a runtime check: every Release build defines NDEBUG,
// and assert() compiles to nothing there. run.sh builds these cases with -DNDEBUG (the Release reality)
// and, for the shipped arm, without it as well.
//
// argv[1] selects ONE case, so a case that ends the process cannot hide another:
//   valid     the generator point G (the public key of secret 1), 33 and 65 bytes, is taken; the stored
//             bytes, the compressed flag and BRKeyPubKey round-trip; a right-length input that is not a
//             point on the curve is refused. Known answers: G is fixed by the curve, not by this code.
//   sweep     every length 0..300, source buffer exactly that long on the heap: only 33 and 65 are taken;
//             every other length is refused and leaves the key all-zero.
//   len256    one 256-byte input into a key on the stack.
//   len_edge  the shortest length that reaches past the end of a BRKey:
//             sizeof(BRKey) - offsetof(BRKey, pubKey) + 1.
//
// Each case prints its answers one per line (run.sh compares them across builds) and exits 0 only if
// every check held. The len256 and len_edge cases print a "calling" line before the call and a
// "returned" line after it, so a run that never returns is visible as such.
#include "BRKey.h"
#include "BRInt.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const uint8_t G33[33] = {
    0x02, 0x79, 0xbe, 0x66, 0x7e, 0xf9, 0xdc, 0xbb, 0xac, 0x55, 0xa0, 0x62, 0x95, 0xce, 0x87, 0x0b, 0x07,
    0x02, 0x9b, 0xfc, 0xdb, 0x2d, 0xce, 0x28, 0xd9, 0x59, 0xf2, 0x81, 0x5b, 0x16, 0xf8, 0x17, 0x98
};

static const uint8_t G65[65] = {
    0x04, 0x79, 0xbe, 0x66, 0x7e, 0xf9, 0xdc, 0xbb, 0xac, 0x55, 0xa0, 0x62, 0x95, 0xce, 0x87, 0x0b, 0x07,
    0x02, 0x9b, 0xfc, 0xdb, 0x2d, 0xce, 0x28, 0xd9, 0x59, 0xf2, 0x81, 0x5b, 0x16, 0xf8, 0x17, 0x98,
    0x48, 0x3a, 0xda, 0x77, 0x26, 0xa3, 0xc4, 0x65, 0x5d, 0xa4, 0xfb, 0xfc, 0x0e, 0x11, 0x08, 0xa8,
    0xfd, 0x17, 0xb4, 0x48, 0xa6, 0x85, 0x54, 0x19, 0x9c, 0x47, 0xd0, 0x8f, 0xfb, 0x10, 0xd4, 0xb8
};

static int failures = 0;

static void check(int ok, const char *what)
{
    printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (! ok) failures++;
}

static int all_zero(const void *p, size_t n)
{
    const uint8_t *b = p;

    for (size_t i = 0; i < n; i++) if (b[i] != 0) return 0;
    return 1;
}

// A heap source of exactly len bytes: G33 for 33, otherwise G65's bytes, then 0xa5 past the 65th.
static uint8_t *source(size_t len)
{
    uint8_t *buf = malloc(len ? len : 1);

    if (! buf) { fprintf(stderr, "malloc(%zu) failed\n", len); exit(2); }
    for (size_t i = 0; i < len; i++) buf[i] = (len == 33) ? G33[i] : (i < sizeof(G65)) ? G65[i] : 0xa5;
    return buf;
}

static int case_valid(void)
{
    BRKey key, one;
    UInt256 secret = UINT256_ZERO;
    uint8_t out[65], bad[65];
    size_t n;

    // G really is the public key of secret 1 under this build's secp256k1 (checks the vector, not the code under test)
    secret.u8[31] = 1;
    check(BRKeySetSecret(&one, &secret, 1) == 1, "secret 1 is a valid secret");
    n = BRKeyPubKey(&one, out, sizeof(out));
    check(n == 33 && memcmp(out, G33, 33) == 0, "secret 1 gives G, compressed");
    check(BRKeySetSecret(&one, &secret, 0) == 1 && BRKeyPubKey(&one, out, sizeof(out)) == 65 &&
          memcmp(out, G65, 65) == 0, "secret 1 gives G, uncompressed");
    BRKeyClean(&one);

    memset(&key, 0x5a, sizeof(key));
    check(BRKeySetPubKey(&key, G33, sizeof(G33)) == 1, "33-byte G is taken");
    check(key.compressed == 1 && memcmp(key.pubKey, G33, 33) == 0 && all_zero(&key.pubKey[33], 65 - 33) &&
          UInt256IsZero(key.secret), "33-byte G is stored as given, compressed, with no secret");
    check(BRKeyPubKey(&key, out, sizeof(out)) == 33 && memcmp(out, G33, 33) == 0, "33-byte G reads back");

    memset(&key, 0x5a, sizeof(key));
    check(BRKeySetPubKey(&key, G65, sizeof(G65)) == 1, "65-byte G is taken");
    check(key.compressed == 0 && memcmp(key.pubKey, G65, 65) == 0 && UInt256IsZero(key.secret),
          "65-byte G is stored as given, uncompressed, with no secret");
    check(BRKeyPubKey(&key, out, sizeof(out)) == 65 && memcmp(out, G65, 65) == 0, "65-byte G reads back");

    memcpy(bad, G33, 33);
    bad[0] = 0x05;   // no such prefix
    check(BRKeySetPubKey(&key, bad, 33) == 0, "33 bytes that are not a point are refused");
    memcpy(bad, G65, 65);
    bad[64] ^= 0x01; // y no longer on the curve for this x
    check(BRKeySetPubKey(&key, bad, 65) == 0, "65 bytes that are not a point are refused");
    BRKeyClean(&key);
    return failures ? 1 : 0;
}

static int case_sweep(void)
{
    char taken[64] = "", line[128];
    size_t refused = 0, refusedDirty = 0, takenWrong = 0;

    for (size_t len = 0; len <= 300; len++) {
        BRKey key;
        uint8_t *src = source(len);
        int r;

        memset(&key, 0x5a, sizeof(key));
        r = BRKeySetPubKey(&key, src, len);

        if (r) {
            char s[8];

            snprintf(s, sizeof(s), " %zu", len);
            strncat(taken, s, sizeof(taken) - strlen(taken) - 1);
            if (memcmp(key.pubKey, src, len) != 0 || key.compressed != (len == 33)) takenWrong++;
        }
        else {
            refused++;
            if (! all_zero(&key, sizeof(key))) refusedDirty++;
        }

        free(src);
    }

    printf("taken at lengths:%s\n", taken);
    check(strcmp(taken, " 33 65") == 0, "only 33 and 65 are taken");
    snprintf(line, sizeof(line), "%zu other lengths refused, every refused key left all-zero", refused);
    check(refused == 299 && refusedDirty == 0, line);
    check(takenWrong == 0, "every taken key holds the bytes given");
    return failures ? 1 : 0;
}

// One call with a key on this frame's stack. The "calling" line is flushed first, so a run that ends
// inside the call shows which call it was.
static int case_len(const char *name, size_t len)
{
    BRKey key;
    uint8_t *src = source(len);
    char line[128];
    int r;

    memset(&key, 0x5a, sizeof(key));
    printf("%s: calling BRKeySetPubKey with %zu bytes (BRKey is %zu bytes, pubKey at offset %zu holds %zu)\n",
           name, len, sizeof(BRKey), offsetof(BRKey, pubKey), sizeof(key.pubKey));
    fflush(stdout);
    r = BRKeySetPubKey(&key, src, len);
    printf("%s: returned\n", name);
    snprintf(line, sizeof(line), "%zu bytes are refused (returned %d)", len, r);
    check(r == 0, line);
    check(all_zero(&key, sizeof(key)), "the refused key is left all-zero");
    free(src);
    return failures ? 1 : 0;
}

int main(int argc, char *argv[])
{
    const char *c = (argc > 1) ? argv[1] : "";

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (strcmp(c, "valid") == 0) return case_valid();
    if (strcmp(c, "sweep") == 0) return case_sweep();
    if (strcmp(c, "len256") == 0) return case_len("len256", 256);
    if (strcmp(c, "len_edge") == 0) return case_len("len_edge", sizeof(BRKey) - offsetof(BRKey, pubKey) + 1);
    fprintf(stderr, "usage: %s valid|sweep|len256|len_edge\n", argv[0]);
    return 2;
}
