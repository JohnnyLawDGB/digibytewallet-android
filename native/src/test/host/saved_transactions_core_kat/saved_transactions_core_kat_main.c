// Host KAT for BRSavedTransactions.h -- the core reader of the saved-transactions blob
// that BRWalletSerializeTransactions writes.
//
// What it gates: the documented layout round-trips (transaction bytes, height and
// timestamp), and a malformed blob is rejected or cut short without reading outside it.
// The height matters: a confirmed transaction restored without it reads as unconfirmed,
// and the balance changes across a restart.
//
// Built twice by run.sh:
//   -DSAVED_TRANSACTIONS_METADATA_UNFIXED  a reader that drops height and timestamp;
//                                          every RED case fails
//   (default)                              the shipped reader; every case passes

#include "BRSavedTransactions.h"
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
