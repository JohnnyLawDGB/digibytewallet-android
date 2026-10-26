#ifndef SPEND_GUARD_H
#define SPEND_GUARD_H

/*
 * spend_guard_inputs_unspent: a coin the wallet already holds as spent is never signed into another send
 * (4.1.2 row 3, review R3).
 *
 * The coin reservation (coin_reservation.h) covers the window between signing a send and the wallet
 * registering it; at registration the reservation is released, because from then on the wallet itself holds
 * the coin as spent. But a signer that takes the coins it is handed -- the asset send signs whatever outpoints
 * the app chose, before an indexer round-trip -- never asked the wallet. A DGB send that registered during
 * that round-trip left its coin unreserved and spent, and the asset send signed it again: two conflicting
 * sends, both reported as sent. This asks the wallet's authoritative spent set (BRWalletOutpointSpent:
 * DGB, DigiAsset and DigiDollar spends alike) about every input, before anything is signed.
 *
 * MACRO CONVENTION: SPEND_GUARD_UNFIXED is tested by VALUE (#if); =1 is spend_guard_kat's red arm.
 */

#include "BRWallet.h"
#include "BRTransaction.h"

#ifndef SPEND_GUARD_UNFIXED
#define SPEND_GUARD_UNFIXED 0
#endif

/* 1 when no input of tx spends an outpoint the wallet holds as spent; 0 otherwise, or when it cannot tell. */
static inline int spend_guard_inputs_unspent(BRWallet *wallet, const BRTransaction *tx)
{
#if SPEND_GUARD_UNFIXED
    (void)wallet; (void)tx;
    return 1;
#else
    if (! wallet || ! tx) return 0;
    for (size_t i = 0; i < tx->inCount; i++) {
        if (BRWalletOutpointSpent(wallet, tx->inputs[i].txHash, tx->inputs[i].index)) return 0;
    }
    return 1;
#endif
}

#endif /* SPEND_GUARD_H */
