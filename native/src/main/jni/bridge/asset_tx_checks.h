/*
 * asset_tx_checks.h
 *
 * Two checks the asset paths make on transactions, kept free of JNI so the host
 * known-answer suite (native/src/test/host/parent_txid_binding_kat) compiles and runs
 * exactly this code against the live core parser.
 *
 *   raw_tx_id          - the id of a raw transaction, as the network names it.
 *   tx_inputs_distinct - no outpoint is listed twice among a transaction's inputs.
 */
#ifndef ASSET_TX_CHECKS_H
#define ASSET_TX_CHECKS_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "BRInt.h"
#include "BRTransaction.h"

/*
 * The id of the transaction serialized in buf[0..len), written to *txHashOut in internal
 * byte order. Returns 1 when an id is reported, 0 otherwise.
 *
 * Invariant: an id is reported only when the bytes are exactly one complete signed
 * transaction -- the core parser reads them, and serializing what it read gives back the
 * same bytes, no more and no fewer -- and the id reported is the one the core parser
 * computes over the serialization WITHOUT witness data (BRTransactionParse). That is the id
 * every txid on the network refers to; for a transaction with no witness it equals the hash
 * of the whole serialization, for one with a witness it does not.
 *
 * Returns 0 for bytes the parser refuses, for a transaction it reads as unsigned (it
 * computes no id for those), and for bytes that carry anything beyond the transaction.
 */
static inline int raw_tx_id(const uint8_t *buf, size_t len, UInt256 *txHashOut)
{
    if (!buf || len == 0 || !txHashOut) return 0;

    BRTransaction *tx = BRTransactionParse(buf, len);
    if (!tx) return 0;

    int ok = !UInt256IsZero(tx->txHash);
    if (ok) {
        size_t n = BRTransactionSerialize(tx, NULL, 0);
        ok = (n == len);
        if (ok) {
            uint8_t *again = (uint8_t *)malloc(n);
            ok = again != NULL
                && BRTransactionSerialize(tx, again, n) == n
                && memcmp(again, buf, n) == 0;
            free(again);
        }
    }
    if (ok) *txHashOut = tx->txHash;

    BRTransactionFree(tx);
    return ok;
}

/*
 * 1 when no two inputs of tx name the same outpoint (previous txid and output index),
 * 0 otherwise. Inputs that share a txid but differ in index are distinct outpoints.
 */
static inline int tx_inputs_distinct(const BRTransaction *tx)
{
    if (!tx) return 0;
    for (size_t i = 0; i < tx->inCount; i++) {
        for (size_t j = i + 1; j < tx->inCount; j++) {
            if (tx->inputs[i].index == tx->inputs[j].index &&
                UInt256Eq(tx->inputs[i].txHash, tx->inputs[j].txHash)) {
                return 0;
            }
        }
    }
    return 1;
}

#endif /* ASSET_TX_CHECKS_H */
