/* msg_len_kat -- MSG-LEN: a message signed by signMessage is encoded with a full compact size at every
 * length, so the bytes it is signed over are the bytes a verifier builds.
 *
 * Compiles bridge/signed_message.h (the payload jni_wallet_sign.c signs) in two arms:
 *   default                 the shipped encoder.
 *   -DMSG_LEN_UNFIXED=1     the encoder before this item (one byte above 0xffff).
 *
 * The reference is an independent compact-size writer below (DigiByte Core's WriteCompactSize), and an
 * independent reader that checks the payload parses back to exactly the message.
 *   [A] RED-THEN-GREEN. Lengths 0x10000, 70000 and 0xffffffff encode as 0xfe + four bytes; 2^32 as 0xff +
 *       eight bytes (encoder only: no 4 GiB message is built).
 *   [B] RED-THEN-GREEN. A 70,000-byte message's payload reads back to a 70,000-byte message.
 *   [C] GUARD, both arms. Lengths 0, 1, 0xfc, 0xfd, 0xfe, 0xffff encode as before, and a 300-byte
 *       message's payload is byte-identical in both arms (no existing signature changes).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "signed_message.h"

static int g_fail = 0;
static void check(int c, const char *what)
{
    printf("%s: %s\n", c ? "PASS" : "FAIL", what);
    if (!c) g_fail++;
}

/* DigiByte Core serialize.h WriteCompactSize, written out independently. */
static size_t ref_compact(uint8_t *b, uint64_t n)
{
    if (n < 253) { b[0] = (uint8_t)n; return 1; }
    if (n <= 0xffff) { b[0] = 253; b[1] = n & 0xff; b[2] = (n >> 8) & 0xff; return 3; }
    if (n <= 0xffffffffULL) { b[0] = 254; for (int i = 0; i < 4; i++) b[1 + i] = (n >> (8 * i)) & 0xff; return 5; }
    b[0] = 255; for (int i = 0; i < 8; i++) b[1 + i] = (n >> (8 * i)) & 0xff; return 9;
}

static int same_encoding(uint64_t n)
{
    uint8_t a[9] = {0}, r[9] = {0};
    size_t la = signed_message_compact_size(a, n), lr = ref_compact(r, n);
    return la == lr && memcmp(a, r, lr) == 0;
}

/* Reads the payload back as a verifier does: the header, a compact size, then exactly that many bytes. */
static int reads_back(const uint8_t *p, size_t len, size_t wantLen)
{
    const size_t h = 26;
    uint64_t n; size_t at;
    if (len < h + 1 || memcmp(p, "\x19" "DigiByte Signed Message:\n", h) != 0) return 0;
    switch (p[h]) {
    case 253: if (len < h + 3) return 0; n = p[h + 1] | ((uint64_t)p[h + 2] << 8); at = h + 3; break;
    case 254: if (len < h + 5) return 0; n = 0; for (int i = 0; i < 4; i++) n |= (uint64_t)p[h + 1 + i] << (8 * i); at = h + 5; break;
    case 255: return 0;
    default: n = p[h]; at = h + 1;
    }
    return n == wantLen && at + n == len;
}

int main(void)
{
    printf("ARM: %s\n", MSG_LEN_UNFIXED ? "comparison (MSG_LEN_UNFIXED=1)" : "shipped");
    char what[160];

    printf("[A] lengths above 0xffff\n");
    const uint64_t big[] = { 0x10000ULL, 70000ULL, 0xffffffffULL, 0x100000000ULL };
    for (size_t i = 0; i < sizeof(big) / sizeof(big[0]); i++) {
        if (!same_encoding(big[i])) { printf("FAIL: A: length %llu is not a full compact size\n", (unsigned long long)big[i]); g_fail++; }
        else { snprintf(what, sizeof(what), "A: length %llu is a full compact size", (unsigned long long)big[i]); check(1, what); }
    }

    printf("[B] a 70,000-byte message reads back\n");
    {
        size_t n = 70000, len = 0;
        uint8_t *msg = malloc(n);
        memset(msg, 'x', n);
        uint8_t *p = signed_message_payload(msg, n, &len);
        if (!p || !reads_back(p, len, n)) { printf("FAIL: B: a 70,000-byte message's payload does not read back to it\n"); g_fail++; }
        else check(1, "B: a 70,000-byte message's payload reads back to exactly it");
        free(p); free(msg);
    }

    printf("[C] guard: lengths up to 0xffff\n");
    const uint64_t small[] = { 0, 1, 0xfc, 0xfd, 0xfe, 0xffff };
    for (size_t i = 0; i < sizeof(small) / sizeof(small[0]); i++) {
        snprintf(what, sizeof(what), "C guard: length %llu encodes as before", (unsigned long long)small[i]);
        check(same_encoding(small[i]), what);
    }
    {
        uint8_t msg[300]; size_t len = 0;
        for (size_t i = 0; i < sizeof(msg); i++) msg[i] = (uint8_t)('a' + i % 26);
        uint8_t *p = signed_message_payload(msg, sizeof(msg), &len);
        static const uint8_t want_head[] = { 0x19, 'D','i','g','i','B','y','t','e',' ','S','i','g','n','e','d',' ',
                                             'M','e','s','s','a','g','e',':','\n', 0xfd, 0x2c, 0x01 };
        check(p && len == sizeof(want_head) + sizeof(msg) && memcmp(p, want_head, sizeof(want_head)) == 0 &&
              memcmp(p + sizeof(want_head), msg, sizeof(msg)) == 0,
              "C guard: a 300-byte message's payload is header, fd 2c 01, then the message");
        check(p && reads_back(p, len, sizeof(msg)), "C guard: and it reads back");
        free(p);
        uint8_t *e = signed_message_payload(NULL, 0, &len);
        check(e && len == 27 && e[26] == 0, "C guard: an empty message is the header and a zero length");
        free(e);
    }

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: msg_len_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
