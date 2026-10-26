// Host KAT (GUARD): a plain OP_RETURN placed before the DigiAsset carrier does not hide the carrier
// (4.1.2 row 13, K5 / DA-014). The native reader picks the carrier by its "DA" tag, not by position, so
// for [plain OP_RETURN, DA carrier, ours, ours] it still holds the output the instruction names. (The
// remainder that may ride to the LAST output is held by the Kotlin pre-spend pass, which registers it
// with native as an asset outpoint.) The Kotlin side read the FIRST OP_RETURN and saw nothing; it now
// chooses as native does -- AssetPreSpendPassTest is its red-then-green gate. This KAT pins the native
// half so the two cannot drift apart. No red arm: native was right.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRTransaction.h"
#include "BRDigiAsset.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static uint8_t kOurs[25] = { 0x76,0xa9,0x14, 7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7, 0x88,0xac };
static const uint8_t kPlain[6] = { 0x6a, 0x04, 0xde, 0xad, 0xbe, 0xef };
static const uint8_t kCarrier[8] = { 0x6a, 0x06, 0x44, 0x41, 0x02, 0x15, 0x02, 0x0a };   // transfer 10 units to output 2

int main(void)
{
    BRTxOutput outs[4];
    BRTransaction tx;
    memset(outs, 0, sizeof(outs));
    outs[0].script = (uint8_t *)kPlain;   outs[0].scriptLen = sizeof(kPlain);
    outs[1].script = (uint8_t *)kCarrier; outs[1].scriptLen = sizeof(kCarrier);
    outs[2].script = kOurs; outs[2].scriptLen = sizeof(kOurs); outs[2].amount = 50000;
    outs[3].script = kOurs; outs[3].scriptLen = sizeof(kOurs); outs[3].amount = 50000;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs; tx.outCount = 4;

    check(BRTxOutputIsAsset(&tx, &tx.outputs[2]) == 1, "the output the carrier names is held, with a plain OP_RETURN first");
    check(BRTxOutputIsAsset(&tx, &tx.outputs[0]) == 0, "the plain OP_RETURN is not read as the carrier");

    // [unclassifiable carrier, readable carrier, ours, ours]: one carrier that cannot be classified holds
    // every output, whatever a readable carrier beside it names (the Kotlin side matches this).
    static const uint8_t kPushdata2[10] = { 0x6a, 0x4d, 0x06, 0x00, 0x44, 0x41, 0x02, 0x15, 0x02, 0x0a };
    BRTxOutput mixed[4];
    memcpy(mixed, outs, sizeof(mixed));
    mixed[0].script = (uint8_t *)kPushdata2; mixed[0].scriptLen = sizeof(kPushdata2);
    BRTransaction tx3; memset(&tx3, 0, sizeof(tx3)); tx3.outputs = mixed; tx3.outCount = 4;
    check(BRTxOutputIsAsset(&tx3, &tx3.outputs[3]) == 1, "an unclassifiable carrier beside a readable one holds an output neither names");

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: asset_plain_opreturn_first_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
