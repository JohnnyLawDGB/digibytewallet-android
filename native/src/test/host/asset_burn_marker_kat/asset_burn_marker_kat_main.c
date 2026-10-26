// Host KAT: output index 31 is the destroy marker only in a BURN.
//
// INVARIANT. A non-range transfer instruction to output 31 destroys its units only when the
// operation is BURN (opcode 0x25; DigiAsset_Core DigiByteTransaction.cpp decodeAssetTransfer:
// `type == DIGIASSET_BURN && !range && output == 31`). In a TRANSFER or an ISSUANCE, index 31 names
// a real output -- one that exists once a transaction has 32 or more -- and the reference credits
// the units there. So the native reader recognises that output as an asset carrier and it cannot
// be spent as plain DGB on the strength of this reader alone. In a BURN, output 31 is not a
// carrier, and the burn's other instructions still mark the outputs they name.
//
// RED arm (-DASSET_BURN_MARKER_UNFIXED) restores the earlier marker, index 31 read as a burn in
// every operation. It must fail the checks marked RED-THEN-GREEN. GREEN passes every check.
//
// MACRO CONVENTION: PRESENCE of -DASSET_BURN_MARKER_UNFIXED (#ifdef) selects the red arm.
//
// Exit code 0 = every check passed; nonzero = a check failed or a sanitizer report fired.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>
#include "BRTransaction.h"
#include "BRDigiAsset.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static uint8_t kNormalScript[25] = {
    0x76,0xa9,0x14, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x88,0xac
};

// 32 ordinary outputs (indices 0..31) then the asset OP_RETURN (index 32). The OP_RETURN script is
// copied into a heap block of exactly its own length, so the sanitizer sees any read that leaves it.
static int classify(const uint8_t *opReturn, size_t opReturnLen, int queryVout) {
    BRTxOutput outs[33];
    BRTransaction tx;
    uint8_t *exact = malloc(opReturnLen);
    int r;

    if (!exact) return -1;
    memcpy(exact, opReturn, opReturnLen);
    memset(outs, 0, sizeof(outs));
    for (int i = 0; i < 32; ++i) {
        outs[i].script = kNormalScript;
        outs[i].scriptLen = sizeof(kNormalScript);
        outs[i].amount = 100000;
    }
    outs[32].script = exact;
    outs[32].scriptLen = opReturnLen;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = 33;
    r = BRTxOutputIsAsset(&tx, &tx.outputs[queryVout]);
    free(exact);
    return r;
}

int main(void) {
    // TRANSFER (0x15): one instruction, 1 unit to output 31.
    static const uint8_t kTransfer31[8] = { 0x6a,0x06,0x44,0x41,0x02,0x15,0x1f,0x01 };
    // ISSUANCE (0x05, no metadata): amount 1, one instruction 1 unit to output 31, flags 0.
    static const uint8_t kIssuance31[10] = { 0x6a,0x08,0x44,0x41,0x02,0x05,0x01,0x1f,0x01,0x00 };
    // BURN (0x25): one instruction, 1 unit to output 31 -- the destroy marker.
    static const uint8_t kBurn31[8] = { 0x6a,0x06,0x44,0x41,0x02,0x25,0x1f,0x01 };
    // BURN (0x25): 1 unit destroyed at 31, and 1 unit delivered to output 1.
    static const uint8_t kBurnAndSend[10] = { 0x6a,0x08,0x44,0x41,0x02,0x25,0x1f,0x01,0x01,0x01 };

    // RED-THEN-GREEN
    check(classify(kTransfer31, sizeof(kTransfer31), 31) == 1,
          "a transfer to output 31 marks output 31");
    check(classify(kIssuance31, sizeof(kIssuance31), 31) == 1,
          "an issuance instruction to output 31 marks output 31");
    // GUARD
    check(classify(kTransfer31, sizeof(kTransfer31), 30) == 0,
          "a transfer to output 31 does not mark output 30");
    check(classify(kBurn31, sizeof(kBurn31), 31) == 0,
          "in a burn, output 31 is the destroy marker and is not marked");
    check(classify(kBurnAndSend, sizeof(kBurnAndSend), 1) == 1,
          "a burn's other instruction still marks the output it names");
    check(classify(kBurnAndSend, sizeof(kBurnAndSend), 31) == 0,
          "a burn's destroy marker still marks nothing");

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
