/*
 * one_family_gate.h
 *
 * ONE-FAMILY at the asset-send signer: the last check before buildAndSignAssetTransferTx signs the
 * inputs Kotlin listed. Kept free of JNI so the host known-answer suite
 * (native/src/test/host/one_family_kat) compiles and runs exactly this code against the live wallet.
 *
 * Invariant: no input of an asset send is an output the wallet holds as DigiDollar, or an output of a
 * transaction marked both DigiDollar and DigiAsset (BRWalletOutpointIsOneFamilyHeld). DigiByte Core lets
 * a transaction without the DigiDollar marker spend a DigiDollar output, so signing one burns the dollars
 * on it. An input the wallet does not hold is not judged here (the Kotlin selection judges it).
 */
#ifndef ONE_FAMILY_GATE_H
#define ONE_FAMILY_GATE_H

#include "BRTransaction.h"
#include "BRWallet.h"

/* 1 when every input of tx may be spent by an asset send, 0 when any may not. */
static inline int tx_inputs_one_family_ok(BRWallet *wallet, const BRTransaction *tx)
{
    if (!wallet || !tx) return 0;
#ifdef ONE_FAMILY_SIGNER_UNFIXED
    return 1;   /* comparison arm, built only by the host KAT: the signer signs whatever is listed */
#else
    for (size_t i = 0; i < tx->inCount; i++) {
        if (BRWalletOutpointIsOneFamilyHeld(wallet, tx->inputs[i].txHash, tx->inputs[i].index)) return 0;
    }
    return 1;
#endif
}

#endif /* ONE_FAMILY_GATE_H */
