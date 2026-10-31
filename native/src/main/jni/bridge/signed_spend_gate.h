#ifndef SIGNED_SPEND_GATE_H
#define SIGNED_SPEND_GATE_H

/*
 * signed_spend_gate: the gate a signed transaction passes before it may leave the signer (4.1.3, review R3).
 *
 * seed_sign_transaction refuses a coin the wallet holds as spent (spend_guard.h) and claims a signed
 * send's coins (coin_reservation.h) so no other send is signed on them before it is published. The
 * recovery signers (jni_derive.c: the legacy sweep, the foreign asset and DigiDollar transfers) sign
 * the coins they are handed with keys of their own, and took neither: two sweeps of one phrase, or a
 * sweep of a profile the wallet also watches beside a send from the wallet, could both be signed on one
 * coin and both reported as sent. This applies the same two checks to a transaction already signed:
 * the spent set (when a wallet is loaded), the claim, and the spent set again once the claim is held
 * (a send that registered meanwhile released its claim at registration). A refused claim takes nothing;
 * a claim refused after it was granted is released. Returns 1 when the transaction may leave.
 *
 * MACRO CONVENTION: RECOVERY_SPEND_GATE_UNFIXED is tested by VALUE (#if); =1 is recovery_spend_gate_kat's red arm.
 */

#include "BRWallet.h"
#include "BRTransaction.h"
#include "spend_guard.h"
#include "coin_reservation.h"

#ifndef RECOVERY_SPEND_GATE_UNFIXED
#define RECOVERY_SPEND_GATE_UNFIXED 0
#endif

static inline int signed_spend_gate(BRWallet *wallet, BRTransaction *tx, double now)
{
#if RECOVERY_SPEND_GATE_UNFIXED
    (void)wallet; (void)tx; (void)now;
    return 1;
#else
    if (! tx || tx->inCount == 0) return 0;
    if (wallet && ! spend_guard_inputs_unspent(wallet, tx)) return 0;
    if (! coin_reservation_claim(tx->txHash, tx->inputs, tx->inCount, now)) return 0;
    if (wallet && ! spend_guard_inputs_unspent(wallet, tx)) {
        coin_reservation_release(tx->txHash);
        return 0;
    }
    return 1;
#endif
}

#endif /* SIGNED_SPEND_GATE_H */
