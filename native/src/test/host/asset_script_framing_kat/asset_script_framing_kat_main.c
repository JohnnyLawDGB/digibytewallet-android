// Host KAT: the native asset-script reader recognises both standard push framings, and reads
// only the payload the push declares.
//
// INVARIANT. A DigiAsset payload is framed by a script push. Up to 75 bytes it is a direct
// single-byte push; at 76 bytes and above the only valid framing is OP_PUSHDATA1 (0x4c). A
// canonical PUSHDATA1 carrier is a real asset script, and the output an instruction names is
// recognised as an asset output. A single length byte of 76 (== 0x4c, the PUSHDATA1 opcode) is
// not a valid framing and is not an asset script. And the instructions are the bytes the push
// DECLARES: whatever follows the declared payload in the script is not an instruction.
//
// RED arm (-DASSET_SCRIPT_FRAMING_UNFIXED) restores the earlier shape of the payload lookup:
// one length byte of any value, the header at a fixed offset, the whole script as the read
// bound. It must fail the three RED-THEN-GREEN checks below. GREEN passes every check.
//
// MACRO CONVENTION: PRESENCE of -DASSET_SCRIPT_FRAMING_UNFIXED (#ifdef) selects the red arm.
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

// Build a transaction of `normalCount` ordinary outputs (indices 0..normalCount-1) followed by
// the asset OP_RETURN, and ask whether output `queryVout` carries an asset.
static int classify(const uint8_t *opReturn, size_t opReturnLen, int normalCount, int queryVout) {
    BRTxOutput outs[16];
    memset(outs, 0, sizeof(outs));
    int n = 0;
    for (; n < normalCount; ++n) {
        outs[n].script = kNormalScript;
        outs[n].scriptLen = sizeof(kNormalScript);
        outs[n].amount = 100000;
    }
    outs[n].script = (uint8_t *)opReturn;
    outs[n].scriptLen = opReturnLen;
    outs[n].amount = 0;
    n++;

    BRTransaction tx;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = (size_t)n;
    return BRTxOutputIsAsset(&tx, &tx.outputs[queryVout]);
}

// A 76-byte transfer payload: "DA" + version + opcode 0x15 + 36 two-byte instructions. The
// first instruction assigns to output 3; the rest to output 7 (harmless filler). 76 bytes is
// exactly the size that forces PUSHDATA1 framing.
static size_t buildTransferPayload76(uint8_t *payload) {
    size_t k = 0;
    payload[k++] = 0x44; payload[k++] = 0x41;  // "DA"
    payload[k++] = 0x02;                        // version 2
    payload[k++] = 0x15;                        // opcode: transfer
    for (int i = 0; i < 36; ++i) {
        uint8_t outIdx = (i == 0) ? 3 : 7;      // skip=0,range=0,percent=0 in the top 3 bits
        payload[k++] = (uint8_t)(outIdx & 0x1F);
        payload[k++] = 0x01;                    // SFFC width 0, amount 1
    }
    return k;                                   // 76
}

int main(void) {
    uint8_t payload[80];
    size_t plen = buildTransferPayload76(payload);
    check(plen == 76, "constructed a 76-byte transfer payload");

    // Canonical PUSHDATA1 framing: OP_RETURN, 0x4c, length, payload.
    uint8_t pushdata1[3 + 80];
    pushdata1[0] = 0x6a;
    pushdata1[1] = 0x4c;
    pushdata1[2] = (uint8_t)plen;
    memcpy(pushdata1 + 3, payload, plen);

    // RED-THEN-GREEN: the output the first instruction names must be recognised as an asset.
    check(classify(pushdata1, 3 + plen, 4, 3) == 1,
          "a canonical PUSHDATA1 asset script marks the output its instruction names");
    // An output no instruction names is ordinary change, not an asset carrier.
    check(classify(pushdata1, 3 + plen, 4, 1) == 0,
          "a PUSHDATA1 asset script does not mark an output nothing names");

    // RED-THEN-GREEN: a single length byte of 76 is the PUSHDATA1 opcode, not a length, so
    // this framing is not a valid asset script and marks nothing.
    uint8_t oldframing[2 + 80];
    oldframing[0] = 0x6a;
    oldframing[1] = (uint8_t)plen;              // 76 == 0x4c
    memcpy(oldframing + 2, payload, plen);
    check(classify(oldframing, 2 + plen, 4, 3) == 0,
          "a 76-byte payload behind a single length byte is not a valid asset script");

    // RED-THEN-GREEN: the push declares 6 payload bytes (one instruction: output 0). The two
    // bytes after the declared payload are not part of it, so output 3 is not a carrier.
    static const uint8_t kTrailing[10] = { 0x6a,0x06,0x44,0x41,0x02,0x15,0x00,0x0a, 0x03,0x01 };
    check(classify(kTrailing, sizeof(kTrailing), 4, 3) == 0,
          "bytes after the declared payload are not read as instructions");
    // GUARD: the instruction inside the declared payload still marks its output.
    check(classify(kTrailing, sizeof(kTrailing), 4, 0) == 1,
          "the instruction inside the declared payload still marks its output");

    // GUARD: a valid short single-push transfer still recognised (unchanged behaviour).
    static const uint8_t kShort[8] = { 0x6a,0x06,0x44,0x41,0x02,0x15,0x00,0x0a };
    check(classify(kShort, sizeof(kShort), 1, 0) == 1,
          "a valid single-push transfer still marks its instructed output");

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
