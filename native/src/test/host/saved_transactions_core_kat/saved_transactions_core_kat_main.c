// Host KAT for BRSavedTransactions.h -- the core reader of the saved-transactions blob
// that BRWalletSerializeTransactions writes.
//
// What it gates: the documented layout round-trips (transaction bytes, height and
// timestamp), and a malformed blob is rejected or cut short without reading outside it.
// The height matters: a confirmed transaction restored without it reads as unconfirmed,
// and the balance changes across a restart.
//
// A saved record is a signed transaction, so it must come back exactly as written even when
// an input's scriptSig also reads as a scriptPubKey (OP_0 PUSH20 / PUSH32). The inferring
// BRTransactionParse takes such an input for the wallet's unsigned form and misparses the
// rest; the Android bridge reader is signed-only since 4.1.2, and the core reader the iOS
// port uses must load the same bytes the same way. The vector is tx_parse_signed_kat's.
//
// Built three times by run.sh:
//   -DSAVED_TRANSACTIONS_METADATA_UNFIXED        a reader that drops height and timestamp;
//                                                every METADATA RED case fails
//   -DSAVED_TRANSACTIONS_UNSIGNED_PARSE_UNFIXED  a reader on the inferring parser;
//                                                every PARSE RED case fails
//   (default)                                    the shipped reader; every case passes

#include "BRSavedTransactions.h"
#include "BRCrypto.h"
#include <stdio.h>
#include <string.h>

static int g_failed = 0;

static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static void putLE32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

// An exact-size heap copy, so ASan sees any read past the blob.
static uint8_t *exact(const uint8_t *src, size_t len)
{
    uint8_t *b = malloc(len ? len : 1);
    memcpy(b, src, len);
    return b;
}

static BRTransaction *sample_tx(uint8_t seed)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev = UINT256_ZERO;
    uint8_t script[25] = { 0x76, 0xa9, 0x14 }, sig[8] = { 0x07, 1, 2, 3, 4, 5, 6, 7 };

    memset(&script[3], seed, 20);
    script[23] = 0x88; script[24] = 0xac;
    prev.u8[0] = seed;
    BRTransactionAddInput(tx, prev, 1, 0, NULL, 0, sig, sizeof(sig), NULL, 0, 0xffffffffu);
    BRTransactionAddOutput(tx, 100000000ULL + seed, script, sizeof(script));
    return tx;
}

static void free_all(BRTransaction **txs, size_t n)
{
    for (size_t i = 0; i < n; i++) BRTransactionFree(txs[i]);
    free(txs);
}

// tx_parse_signed_kat's wire vector: version | 1 input (outpoint, scriptSig = OP_0 PUSH<n>
// <n bytes>, seq) | 1 output (P2PKH, 123456789) | locktime. Signed, network format.
static size_t wire_tx(uint8_t *b, uint8_t pushLen, uint8_t fill)
{
    static const uint8_t spk[25] = { 0x76, 0xa9, 0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,
                                     0x88, 0xac };
    size_t o = 0;

    UInt32SetLE(&b[o], 1); o += 4;
    b[o++] = 1;
    memset(&b[o], 0x77, 32); o += 32; UInt32SetLE(&b[o], 3); o += 4;
    b[o++] = (uint8_t)(2 + pushLen); b[o++] = 0x00; b[o++] = pushLen; memset(&b[o], fill, pushLen); o += pushLen;
    UInt32SetLE(&b[o], 0xffffffff); o += 4;
    b[o++] = 1;
    UInt64SetLE(&b[o], 123456789); o += 8;
    b[o++] = sizeof(spk); memcpy(&b[o], spk, sizeof(spk)); o += sizeof(spk);
    UInt32SetLE(&b[o], 0); o += 4;
    return o;
}

// One saved record holding the wire transaction; the loaded transaction must have the same
// txid, the same output, the scriptSig as its signature, and re-serialize to the same bytes.
static int signed_record_restored(uint8_t pushLen)
{
    uint8_t w[256], r[4 + 12 + 256], back[256];
    size_t wl = wire_tx(w, pushLen, (uint8_t)(0x40 + pushLen)), rl = 0, n;
    BRTransaction **txs = NULL;
    UInt256 want;
    uint8_t *buf;
    int ok;

    BRSHA256_2(&want, w, wl);
    putLE32(&r[rl], 1); rl += 4;
    putLE32(&r[rl], (uint32_t)wl); rl += 4;
    putLE32(&r[rl], 24000100u); rl += 4;
    putLE32(&r[rl], 1760000100u); rl += 4;
    memcpy(&r[rl], w, wl); rl += wl;

    buf = exact(r, rl);
    n = BRSavedTransactionsDeserialize(buf, rl, &txs);
    ok = (n == 1);
    if (ok) {
        BRTransaction *t = txs[0];
        size_t bl = BRTransactionSerialize(t, back, sizeof(back));
        ok = UInt256Eq(t->txHash, want) && bl == wl && memcmp(back, w, wl) == 0 &&
             t->inCount == 1 && t->inputs[0].sigLen == (size_t)(2 + pushLen) &&
             t->outCount == 1 && t->outputs[0].amount == 123456789ULL;
    }
    if (! ok) printf("  pushLen %u: loaded %zu record(s), not the transaction that was saved\n", pushLen, n);
    free_all(txs, n); free(buf);
    return ok;
}

int main(void)
{
    BRTransaction *src[3] = { sample_tx(0x21), sample_tx(0x22), sample_tx(0x23) };
    uint8_t ser[3][256], raw[4 + 3 * (12 + 256)];
    size_t serLen[3], pos = 0, n;
    BRTransaction **txs = NULL;
    uint8_t *buf;

    for (int k = 0; k < 3; k++) serLen[k] = BRTransactionSerialize(src[k], ser[k], sizeof(ser[k]));

    // The documented layout. Two confirmed transactions and one unconfirmed.
    putLE32(&raw[pos], 3); pos += 4;
    for (int k = 0; k < 3; k++) {
        putLE32(&raw[pos], (uint32_t)serLen[k]); pos += 4;
        putLE32(&raw[pos], k < 2 ? 24000000u + (uint32_t)k : TX_UNCONFIRMED); pos += 4;
        putLE32(&raw[pos], 1760000000u + (uint32_t)k); pos += 4;
        memcpy(&raw[pos], ser[k], serLen[k]); pos += serLen[k];
    }

    // ---- RED: the record's metadata comes back ------------------------------------------
    buf = exact(raw, pos);
    n = BRSavedTransactionsDeserialize(buf, pos, &txs);
    {
        int bytes = (n == 3), heights = (n == 3), times = (n == 3);
        for (size_t i = 0; i < n && i < 3; i++) {
            uint8_t back[256];
            size_t bl = BRTransactionSerialize(txs[i], back, sizeof(back));
            if (bl != serLen[i] || memcmp(back, ser[i], bl) != 0) bytes = 0;
            if (txs[i]->blockHeight != (i < 2 ? 24000000u + (uint32_t)i : TX_UNCONFIRMED)) heights = 0;
            if (txs[i]->timestamp != 1760000000u + (uint32_t)i) times = 0;
        }
        result("confirmed_heights_restored", heights);
        result("timestamps_restored", times);
        result("bytes_round_trip", bytes);   // GUARD
    }
    free_all(txs, n); free(buf);

    // ---- PARSE RED: a signed record whose scriptSig reads as a scriptPubKey --------------
    result("signed_push20_scriptsig_restored", signed_record_restored(20));
    result("signed_push32_scriptsig_restored", signed_record_restored(32));

    // ---- GUARD: bounds ------------------------------------------------------------------
    buf = exact(raw, pos - 1);
    n = BRSavedTransactionsDeserialize(buf, pos - 1, &txs);
    result("record_past_end_ends_walk", n == 2);
    free_all(txs, n); free(buf);

    {
        uint8_t hdr[4];
        putLE32(hdr, 0);
        buf = exact(hdr, 4);
        n = BRSavedTransactionsDeserialize(buf, 4, &txs);
        result("zero_count_rejected", n == 0 && txs == NULL);
        free(buf);
        putLE32(hdr, BR_SAVED_TRANSACTIONS_MAX_COUNT + 1);
        buf = exact(hdr, 4);
        n = BRSavedTransactionsDeserialize(buf, 4, &txs);
        result("count_over_max_rejected", n == 0 && txs == NULL);
        free(buf);
    }
    n = BRSavedTransactionsDeserialize(NULL, 0, &txs);
    result("null_blob_rejected", n == 0 && txs == NULL);
    n = BRSavedTransactionsDeserialize(raw, 3, &txs);
    result("short_blob_rejected", n == 0 && txs == NULL);

    {   // A huge declared length in a small blob: nothing parsed, nothing read past it.
        uint8_t r[20];
        putLE32(&r[0], 1); putLE32(&r[4], 0xFFFFFFF1u); putLE32(&r[8], 123);
        putLE32(&r[12], 1700000000u); putLE32(&r[16], 1);
        buf = exact(r, sizeof(r));
        n = BRSavedTransactionsDeserialize(buf, sizeof(r), &txs);
        result("huge_length_parses_nothing", n == 0);
        free_all(txs, n); free(buf);
    }

    for (int k = 0; k < 3; k++) BRTransactionFree(src[k]);
    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
