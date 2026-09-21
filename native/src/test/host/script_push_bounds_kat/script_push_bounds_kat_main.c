// Host KAT: a data push in a script never extends past the script.
//
// INVARIANT. BRScriptElements walks a script element by element. A push opcode declares how
// many data bytes follow it; the walk compares that length with the bytes that remain in the
// script BEFORE it advances, so the running offset never passes the end of the script and the
// sum that advances it cannot wrap in any word size. A script whose push does not fit has no
// elements: the function returns 0 for it, in a 32-bit build exactly as in a 64-bit one.
//
// TWO CASES.
//   walk    prints BRScriptElements' answer for a fixed corpus of scripts: the standard output
//           forms, every push encoding, and pushes whose length bytes or data are cut short or
//           declared far larger than the script. run.sh requires the 64-bit answers to be the
//           same with and without the bound (the bound changes nothing a 64-bit build could
//           observe) and the 32-bit answers to equal the 64-bit ones.
//   parse   hands BRTransactionParse a transaction whose output script is a PUSHDATA4 with a
//           declared length just under 2^32, so the walk is reached the way the parser reaches
//           it. The call must return. run.sh time-limits every run and treats "stopped at the
//           limit" as the comparison arm's expected result; there is no sanitizer report to
//           look for in this KAT.
//
// MACRO CONVENTION: PRESENCE. The comparison arm is -DSCRIPT_PUSH_BOUND_UNFIXED; the shipped
// arm is built with no -D at all.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRAddress.h"
#include "BRTransaction.h"

// Exact-length heap copy, so the sanitizer's redzones bracket the bytes.
static uint8_t *dup_exact(const uint8_t *bytes, size_t len)
{
    uint8_t *p = (uint8_t *)malloc(len ? len : 1);
    if (len) memcpy(p, bytes, len);
    return p;
}

static void walk(const char *name, const uint8_t *bytes, size_t len)
{
    uint8_t *s = dup_exact(bytes, len);
    printf("%-34s elements=%zu\n", name, BRScriptElements(NULL, 0, s, len));
    free(s);
}

#define WALK(name, ...) do { static const uint8_t b_[] = { __VA_ARGS__ }; walk(name, b_, sizeof(b_)); } while (0)
#define H20 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20
#define H32 H20,21,22,23,24,25,26,27,28,29,30,31,32

static int case_walk(void)
{
    WALK("p2pkh",                       0x76, 0xa9, 20, H20, 0x88, 0xac);
    WALK("p2sh",                        0xa9, 20, H20, 0x87);
    WALK("witness v0 keyhash",          0x00, 20, H20);
    WALK("witness v1 32-byte program",  0x51, 32, H32);
    WALK("op_return + direct push",     0x6a, 4, 0xde, 0xad, 0xbe, 0xef);
    WALK("pushdata1, data present",     0x4c, 3, 7, 8, 9);
    WALK("pushdata2, data present",     0x4d, 3, 0, 7, 8, 9);
    WALK("pushdata4, data present",     0x4e, 3, 0, 0, 0, 7, 8, 9);
    WALK("direct push cut short",       0x05, 1, 2);
    WALK("pushdata1 without its length", 0x01, 0xaa, 0x4c);
    WALK("pushdata2 with half a length", 0x4d, 0x03);
    WALK("pushdata4 with half a length", 0x4e, 0x03, 0x00);
    WALK("pushdata1 longer than script", 0x4c, 200, 1, 2, 3);
    WALK("pushdata2 longer than script", 0x4d, 0xff, 0xff, 1, 2, 3);
    WALK("pushdata4 of 2^32-1",          0x4e, 0xff, 0xff, 0xff, 0xff, 1);
    WALK("stale length after a push",    0x03, 1, 2, 3, 0x4d, 0x09);
    return 0;
}

static int case_walk_wide(void)   // the script of case_parse, walked directly
{
    WALK("op_return + pushdata4 of 2^32-6", 0x6a, 0x4e, 0xfa, 0xff, 0xff, 0xff, 0x00);
    return 0;
}

static int case_parse(void)
{
    static const uint8_t script[] = { 0x6a, 0x4e, 0xfa, 0xff, 0xff, 0xff, 0x00 };
    uint8_t tx[4 + 1 + 41 + 1 + 8 + 1 + sizeof(script) + 4];
    size_t off = 0;
    memset(tx, 0, sizeof(tx));
    tx[off] = 1; off += 4;                           // version
    tx[off++] = 1;                                   // one input
    tx[off] = 0x11; off += 32 + 4;                   // previous output
    tx[off++] = 0;                                   // empty input script
    memset(&tx[off], 0xff, 4); off += 4;             // sequence
    tx[off++] = 1;                                   // one output
    tx[off] = 0x10; off += 8;                        // amount
    tx[off++] = (uint8_t)sizeof(script);
    memcpy(&tx[off], script, sizeof(script)); off += sizeof(script);
    off += 4;                                        // lock time

    uint8_t *buf = dup_exact(tx, off);
    BRTransaction *t = BRTransactionParse(buf, off);
    printf("RESULT parse returned (%s)\n", t ? "a transaction" : "NULL");
    if (t) BRTransactionFree(t);
    free(buf);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 2) { fprintf(stderr, "usage: %s <walk|walk_wide|parse>\n", argv[0]); return 2; }
    if (strcmp(argv[1], "walk") == 0)      return case_walk();
    if (strcmp(argv[1], "walk_wide") == 0) return case_walk_wide();
    if (strcmp(argv[1], "parse") == 0)     return case_parse();
    fprintf(stderr, "unknown case: %s\n", argv[1]);
    return 2;
}
