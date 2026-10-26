// Host KAT: a transaction from the network is parsed in its signed format, whatever its scriptSig looks
// like (4.1.2 row 9, N2).
//   [A] a wire tx whose input scriptSig is OP_0 PUSH20 <20 bytes> (reads as a P2WPKH scriptPubKey):
//       BRTransactionParseSigned gives txid = SHA256d(wire), re-serializes to the same length, and keeps
//       the scriptSig as its signature -- RED under TX_PARSE_SIGNED_UNFIXED (read as the unsigned form);
//   [B] the same with OP_0 PUSH32 <32 bytes> (P2WSH-shaped);
//   [C] the two back to back, walked the way BRPeer.c walks a block's transactions: both delivered,
//       each consuming exactly its own bytes;
//   [D] guard: the wallet's own unsigned serialization still parses as unsigned through BRTransactionParse.
//   [E] guard: BRTransactionParseSigned cannot read that draft, so the send review must not use it (Wave 10).
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "BRTransaction.h"
#include "BRCrypto.h"
#include "BRAddress.h"

static int g = 0;
static void ck(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (!c) g++; }

// version | 1 input (outpoint, scriptSig = OP_0 PUSH<n> <n bytes>, seq) | 1 output (P2PKH) | locktime
static size_t wireTx(uint8_t *b, uint8_t pushLen, uint8_t fill)
{
    size_t o = 0;
    UInt32SetLE(&b[o], 1); o += 4;
    b[o++] = 1;
    memset(&b[o], 0x77, 32); o += 32; UInt32SetLE(&b[o], 3); o += 4;
    b[o++] = (uint8_t)(2 + pushLen); b[o++] = 0x00; b[o++] = pushLen; memset(&b[o], fill, pushLen); o += pushLen;
    UInt32SetLE(&b[o], 0xffffffff); o += 4;
    b[o++] = 1;
    UInt64SetLE(&b[o], 123456789); o += 8;
    const uint8_t spk[25] = { 0x76, 0xa9, 0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20, 0x88, 0xac };
    b[o++] = sizeof(spk); memcpy(&b[o], spk, sizeof(spk)); o += sizeof(spk);
    UInt32SetLE(&b[o], 0); o += 4;
    return o;
}

static void signedCase(const char *name, uint8_t pushLen)
{
    uint8_t b[256]; size_t len = wireTx(b, pushLen, (uint8_t)(0x40 + pushLen));
    UInt256 want; BRSHA256_2(&want, b, len);
    BRTransaction *tx = BRTransactionParseSigned(b, len);
    int ok = tx && UInt256Eq(tx->txHash, want) && BRTransactionSerialize(tx, NULL, 0) == len &&
             tx->inputs[0].sigLen == (size_t)(2 + pushLen) && tx->outCount == 1 && tx->outputs[0].amount == 123456789;
    if (! ok) { printf("FAIL: %s: the wire transaction was misparsed (parsed=%d)\n", name, tx != NULL); g++; }
    else ck(1, "parsed as signed: txid = SHA256d(wire), same length, scriptSig kept");
    if (tx) BRTransactionFree(tx);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("[A] OP_0 PUSH20 scriptSig\n"); signedCase("A", 20);
    printf("[B] OP_0 PUSH32 scriptSig\n"); signedCase("B", 32);

    printf("[C] two in a row, walked as a block's transactions\n");
    {
        uint8_t blk[512]; size_t l1 = wireTx(blk, 20, 0x11), l2 = wireTx(blk + l1, 32, 0x22), total = l1 + l2, off = 0;
        int delivered = 0, exact = 1;
        while (off < total) {
            BRTransaction *tx = BRTransactionParseSigned(&blk[off], total - off);
            if (! tx) break;
            size_t consumed = BRTransactionSerialize(tx, NULL, 0);
            UInt256 want; BRSHA256_2(&want, &blk[off], consumed);
            if (consumed != (delivered == 0 ? l1 : l2) || ! UInt256Eq(tx->txHash, want)) exact = 0;
            off += consumed; delivered++;
            BRTransactionFree(tx);
            if (consumed == 0) break;
        }
        if (delivered != 2 || ! exact || off != total) { printf("FAIL: C: the walk delivered %d of 2 (exact=%d)\n", delivered, exact); g++; }
        else ck(1, "both delivered, each consuming exactly its own bytes");
    }

    printf("[D] the wallet's own unsigned form\n");
    {
        const uint8_t spk[25] = { 0x76, 0xa9, 0x14, 9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9, 0x88, 0xac };
        UInt256 h; memset(h.u8, 0x31, 32);
        BRTransaction *u = BRTransactionNew();
        BRTransactionAddInput(u, h, 0, 5000000, spk, sizeof(spk), NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(u, 4000000, spk, sizeof(spk));
        uint8_t b[512]; size_t len = BRTransactionSerialize(u, b, sizeof(b));
        BRTransaction *p = BRTransactionParse(b, len);
        ck(p && p->inputs[0].scriptLen == sizeof(spk) && p->inputs[0].amount == 5000000,
           "BRTransactionParse reads the unsigned form: the input's script and amount");
        if (p) BRTransactionFree(p);

        // [E] Wave 10: the send review once read this draft with getRawTransactionOutputs, which row 9
        // made signed-only, and every DGB send stopped at review. The signed-only parser must not be
        // able to stand in for BRTransactionParse here: that is why getBuiltTransactionOutputs exists.
        BRTransaction *q = BRTransactionParseSigned(b, len);
        int faithful = q && q->inCount == 1 && q->inputs[0].amount == 5000000 &&
                       q->outCount == 1 && q->outputs[0].amount == 4000000;
        ck(!faithful, "E: BRTransactionParseSigned cannot read the draft -- the review's reader is BRTransactionParse");
        if (q) BRTransactionFree(q);
        BRTransactionFree(u);
    }

    printf(g ? "\n%d FAIL\n" : "\nPASS: tx_parse_signed_kat\n", g);
    return g ? 1 : 0;
}
