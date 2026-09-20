// Host KAT: the native asset-script reader stays inside the script it was given.
//
// INVARIANT. Every read the reader makes lies inside the payload the push declares, and the
// 3-bit header of a compact amount always selects one of the seven rows of the decode table.
// Header values 0..5 select the 1..6-byte forms. Values 6 and 7 both select the 7-byte form:
// its header is two bits wide, and the third bit is the top bit of the 54-bit mantissa -- the
// reference decoder and the Kotlin BitReader read it the same way. An amount the payload ends
// in the middle of, a flags byte with no amount after it, and a header that declares metadata
// the payload is too short to hold each end the read where the payload ends.
//
// Each case runs as its own process (argv[1] selects it), so every read site has its own
// red-then-green proof. The RED arm (-DASSET_SCRIPT_BOUNDS_UNFIXED) restores the earlier shape
// of the reader and must fault on every case. The GREEN arm must answer every case and exit
// clean, both with asserts on and with them compiled out (-DNDEBUG).
//
// MACRO CONVENTION: this test uses PRESENCE of -DASSET_SCRIPT_BOUNDS_UNFIXED (#ifdef) to
// select the red arm. The green arm is built with the flag absent.
//
// Exit code 0 = every check of the selected case passed; nonzero = a check failed, the process
// aborted, or a sanitizer report fired. Exit code 2 = unknown case name.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include "BRTransaction.h"
#include "BRDigiAsset.h"

static int g_fail = 0;
// A plain check that stays active even when asserts are compiled out (-DNDEBUG): the green
// arm must verify its answers in BOTH the assert-on and assert-off builds.
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static uint8_t kNormalScript[25] = {
    0x76,0xa9,0x14, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x88,0xac
};

// A transaction of `normalCount` ordinary outputs (indices 0..normalCount-1) followed by the
// asset OP_RETURN; asks whether output `queryVout` carries an asset. The OP_RETURN script is
// copied into a heap block of EXACTLY its own length -- the shape BRTxOutputSetScript gives a
// real output -- so the sanitizer sees any read that leaves the script. No wallet, no signing:
// BRTxOutputIsAsset reads only outputs/outCount and the output scripts.
static int classify(const uint8_t *opReturn, size_t opReturnLen, int normalCount, int queryVout) {
    BRTxOutput outs[8];
    BRTransaction tx;
    uint8_t *exact = malloc(opReturnLen);
    int n = 0, r;

    if (!exact || normalCount > 7) { free(exact); return -1; }
    memcpy(exact, opReturn, opReturnLen);
    memset(outs, 0, sizeof(outs));
    for (; n < normalCount; ++n) {
        outs[n].script = kNormalScript;
        outs[n].scriptLen = sizeof(kNormalScript);
        outs[n].amount = 100000;
    }
    outs[n].script = exact;
    outs[n].scriptLen = opReturnLen;
    outs[n].amount = 0;
    n++;

    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = (size_t)n;
    r = BRTxOutputIsAsset(&tx, &tx.outputs[queryVout]);
    free(exact);
    return r;
}

// Issuance (opcode 0x05, no metadata) whose amount header selects the 7-byte form, on a payload
// that ends after the first amount byte. The amount is incomplete and no instruction follows,
// so nothing is marked.
static void caseAmount7Truncated(void) {
    static const uint8_t s[7] = { 0x6a,0x05,0x44,0x41,0x02,0x05,0xe0 };
    check(classify(s, sizeof(s), 1, 0) == 0,
          "an incomplete 7-byte amount marks nothing, and the read ends with the payload");
}

// Transfer whose instruction carries a COMPLETE 7-byte amount with all three header bits set
// (a value of 2^53 or more). The instruction names output 3, so output 3 is a carrier; and the
// instruction after it is still read, so the output IT names is a carrier too.
static void caseAmount7Complete(void) {
    static const uint8_t one[14] = { 0x6a,0x0c,0x44,0x41,0x02,0x15,
                                     0x03, 0xe0,0x00,0x00,0x00,0x00,0x00,0x01 };
    static const uint8_t two[16] = { 0x6a,0x0e,0x44,0x41,0x02,0x15,
                                     0x03, 0xe0,0x00,0x00,0x00,0x00,0x00,0x01,
                                     0x02, 0x01 };
    static const uint8_t low[14] = { 0x6a,0x0c,0x44,0x41,0x02,0x15,
                                     0x03, 0xc0,0x00,0x00,0x00,0x00,0x00,0x01 };
    check(classify(one, sizeof(one), 4, 3) == 1,
          "a complete 7-byte amount with all three header bits set marks the output it names");
    check(classify(one, sizeof(one), 4, 1) == 0,
          "a complete 7-byte amount does not mark an output nothing names");
    check(classify(two, sizeof(two), 4, 2) == 1,
          "the instruction after a 7-byte amount is still read and marks the output it names");
    check(classify(low, sizeof(low), 4, 3) == 1,
          "GUARD: a complete 7-byte amount with header bits 110 marks the output it names");
}

// Issuance whose opcode declares metadata (opcode 0x01) on a payload far too short to hold the
// 52 metadata bytes. The skip is bounded by the bytes that remain: not an asset carrier.
static void caseShortMetadata(void) {
    static const uint8_t s[7] = { 0x6a,0x05,0x44,0x41,0x02,0x01,0x00 };
    check(classify(s, sizeof(s), 1, 0) == 0,
          "an issuance that declares metadata the payload cannot hold marks nothing");
}

// Transfer whose instruction flags byte is the LAST byte of the payload: there is no amount
// byte to read, so the read ends there and nothing is marked.
static void caseFlagsLastByte(void) {
    static const uint8_t s[7] = { 0x6a,0x05,0x44,0x41,0x02,0x15,0x03 };
    check(classify(s, sizeof(s), 4, 3) == 0,
          "a flags byte with no amount after it marks nothing, and the read ends with the payload");
}

int main(int argc, char **argv) {
    const char *which = (argc > 1) ? argv[1] : "";

    if      (!strcmp(which, "amount7-truncated")) caseAmount7Truncated();
    else if (!strcmp(which, "amount7-complete"))  caseAmount7Complete();
    else if (!strcmp(which, "short-metadata"))    caseShortMetadata();
    else if (!strcmp(which, "flags-last-byte"))   caseFlagsLastByte();
    else {
        fprintf(stderr, "usage: %s amount7-truncated|amount7-complete|short-metadata|flags-last-byte\n",
                argv[0]);
        return 2;
    }

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
