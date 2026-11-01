#ifndef WALLET_SPEND_GATE_H
#define WALLET_SPEND_GATE_H
#include "BRTransaction.h"
/* signed_spend_gate.h against the loaded wallet (or the claim alone, with none), for a signer outside jni_wallet.c
 * whose transaction is already signed -- the recovery signers in jni_derive.c (4.1.3, review R3). Defined in
 * jni_wallet.c, which alone reads g_wallet. 1: it may leave. */
int wallet_signed_spend_gate(BRTransaction *tx, const char *who);
#endif /* WALLET_SPEND_GATE_H */
