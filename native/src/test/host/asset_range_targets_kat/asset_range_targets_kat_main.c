// Host KAT: a RANGE instruction names every output up to its endpoint, and the 13-bit
// endpoint is assembled from its two halves in the right order.
//
// INVARIANT. A range transfer instruction credits its amount to EVERY output from 0 up to and
// including its endpoint (RenzoDD/digiasset-core: startI = range ? 0 : output). The 13-bit
// endpoint is (highFiveBits << 8) | lowEightBits: the five bits in the flag byte are the HIGH
// bits, the following byte the low eight. So every output an instruction names is recognised as
// an asset carrier and cannot be spent as plain DGB.
//
// RED arm (-DASSET_RANGE_TARGETS_UNFIXED) restores the earlier shape of the range step and must
// fail the checks below. GREEN marks 0..endpoint.
//
// MACRO CONVENTION: PRESENCE of -DASSET_RANGE_TARGETS_UNFIXED (#ifdef) selects the red arm.
//
// Exit code 0 = every check passed; nonzero = a check failed or a sanitizer report fired.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include "BRTransaction.h"
#include "BRDigiAsset.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static uint8_t kNormalScript[25] = {
    0x76,0xa9,0x14, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x88,0xac
};

// 5 ordinary outputs (indices 0..4) then the asset OP_RETURN (index 5).
static int classify(const uint8_t *opReturn, size_t opReturnLen, int queryVout) {
    BRTxOutput outs[6];
    memset(outs, 0, sizeof(outs));
    int i = 0;
    for (; i < 5; ++i) {
        outs[i].script = kNormalScript;
        outs[i].scriptLen = sizeof(kNormalScript);
        outs[i].amount = 100000;
    }
    outs[5].script = (uint8_t *)opReturn;
    outs[5].scriptLen = opReturnLen;
    outs[5].amount = 0;

    BRTransaction tx;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = 6;
    return BRTxOutputIsAsset(&tx, &tx.outputs[queryVout]);
}

// A single range instruction whose endpoint is `endpoint`.
static size_t buildRangeOpReturn(uint8_t *script, int endpoint) {
    uint8_t payload[7];
    size_t k = 0;
    payload[k++] = 0x44; payload[k++] = 0x41;          // "DA"
    payload[k++] = 0x02;                                // version 2
    payload[k++] = 0x15;                                // opcode: transfer
    uint8_t highFive = (uint8_t)((endpoint >> 8) & 0x1F);
    uint8_t lowEight = (uint8_t)(endpoint & 0xFF);
    payload[k++] = (uint8_t)(0x40 | highFive);          // range bit set, high 5 endpoint bits
    payload[k++] = lowEight;                             // low 8 endpoint bits
    payload[k++] = 0x01;                                // SFFC width 0, amount 1
    script[0] = 0x6a;
    script[1] = (uint8_t)k;                             // single-byte push (7 bytes)
    memcpy(script + 2, payload, k);
    return k + 2;
}

// For an endpoint, every output 0..min(endpoint,4) must be marked and the rest (0..4) not.
static void assertEndpoint(int endpoint) {
    uint8_t script[16];
    size_t len = buildRangeOpReturn(script, endpoint);
    char msg[96];
    for (int v = 0; v <= 4; ++v) {
        int expect = (v <= endpoint) ? 1 : 0;
        int got = classify(script, len, v);
        snprintf(msg, sizeof(msg), "endpoint %d: output %d %s", endpoint, v,
                 expect ? "is a carrier" : "is not a carrier");
        check(got == expect, msg);
    }
}

int main(void) {
    // The smallest multi-output case: endpoint 2 names outputs 0, 1 and 2, and not 3.
    assertEndpoint(2);

    // The full endpoint set, crossing the 5-bit flag/second-byte boundary.
    assertEndpoint(0);
    assertEndpoint(1);
    assertEndpoint(31);
    assertEndpoint(32);
    assertEndpoint(255);
    assertEndpoint(8191);

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
