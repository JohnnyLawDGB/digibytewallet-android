/*
 * saved_transactions_deserialize.h
 *
 * Pure (no-JNI) deserialization core of loadSerializedTransactions
 * (jni_transaction_persist.c), so a host KAT can drive it without a JVM --
 * the same split as saved_blocks_deserialize.h.
 *
 * The blob is the wallet's own saved transactions, written by
 * getSerializedTransactions / BRWalletSerializeTransactions:
 *   [4 bytes: LE tx count]
 *   repeated: [4 bytes LE txSize][4 bytes LE height][4 bytes LE timestamp][txSize bytes]
 *
 * Each record's length was checked as `pos + txSize > len`, which wraps on a
 * 32-bit size_t: a txSize near 2^32 passed, and BRTransactionParse was handed
 * that bogus length, so its own bounds checks bounded nothing and it read past
 * the blob (BB-2026-10-09-harley F4). The check is now `txSize > len - pos`,
 * which cannot wrap because pos <= len holds throughout.
 */
#ifndef SAVED_TRANSACTIONS_DESERIALIZE_H
#define SAVED_TRANSACTIONS_DESERIALIZE_H

#include <stdint.h>
#include <stdlib.h>
#include "BRInt.h"
#include "BRTransaction.h"

#define SAVED_TRANSACTIONS_MAX_COUNT 10000
#define SAVED_TRANSACTIONS_RECORD_HEADER_BYTES 12

/* Parses a saved-transactions blob. On a sane count, allocates *outTxs (caller
 * owns the array and each BRTransaction* in it) and returns the number of
 * transactions parsed. On a corrupt count (0 or > SAVED_TRANSACTIONS_MAX_COUNT)
 * or a failed allocation, sets *outTxs = NULL and returns 0. A record whose
 * length runs past the blob ends the walk; the records before it are kept. */
static inline size_t deserialize_saved_transactions_guarded(const uint8_t *buf, size_t len,
                                                            BRTransaction ***outTxs) {
    *outTxs = NULL;
    if (!buf || len < 4) return 0;

    size_t pos = 0;
    uint32_t txCount = UInt32GetLE(&buf[pos]); pos += 4;
    if (txCount == 0 || txCount > SAVED_TRANSACTIONS_MAX_COUNT) return 0;

    BRTransaction **txs = calloc(txCount, sizeof(BRTransaction *));
    if (!txs) return 0;

    size_t loaded = 0;
    for (uint32_t i = 0; i < txCount && pos + SAVED_TRANSACTIONS_RECORD_HEADER_BYTES <= len; i++) {
        uint32_t txSize = UInt32GetLE(&buf[pos]); pos += 4;
        uint32_t height = UInt32GetLE(&buf[pos]); pos += 4;
        uint32_t timestamp = UInt32GetLE(&buf[pos]); pos += 4;

#ifdef BB_2026_10_09_HARLEY_UNFIXED
        if (pos + txSize > len) break;   /* comparison shape: wraps on a 32-bit size_t */
#else
        if (txSize > len - pos) break;   /* pos <= len holds here (loop condition) */
#endif

        BRTransaction *tx = BRTransactionParse(&buf[pos], txSize);
        pos += txSize;

        if (tx) {
            tx->blockHeight = height;
            tx->timestamp = timestamp;
            txs[loaded++] = tx;
        }
    }

    *outTxs = txs;
    return loaded;
}

#endif /* SAVED_TRANSACTIONS_DESERIALIZE_H */
