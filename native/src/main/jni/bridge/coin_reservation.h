/*
 * coin_reservation.h — a coin signed for one send is never signed into another (4.1.2 row 3, N8).
 *
 * Every send is checked against the wallet's spendable coins before and after it is signed, and the
 * wallet marks its coins spent when the transaction is registered at publish. Between the signature
 * and that registration nothing held the coins: a second send (another review, the DigiDollar or
 * asset path, a sweep) checked in that window still saw them as spendable and was signed too -- two
 * transactions spending one coin, both reported as sent, one never confirming.
 *
 * So the signer claims a signed transaction's inputs for its txid, and refuses a transaction one of
 * whose inputs another txid holds. The publish releases the claim once the wallet's record holds the
 * coins as spent. A claim whose transaction is never published (the broadcast failed, the app died)
 * lapses after COIN_RESERVATION_TTL_S, so a coin is never held out for good.
 *
 * Pure: no JNI, no seed, the clock passed in -- native/src/test/host/coin_reservation_kat drives it.
 * COIN_RESERVATION_UNFIXED=1 is that KAT's comparison arm: every claim is granted (the 4.1.1 path).
 */
#ifndef COIN_RESERVATION_H
#define COIN_RESERVATION_H

#include <stddef.h>
#include "BRTransaction.h"

/** Seconds a claim lasts when its transaction is never published. */
#define COIN_RESERVATION_TTL_S 60.0
/** Outpoints held at once; a claim that would not fit is refused (the send is not signed). */
#define COIN_RESERVATION_MAX 512

/* Claim inputs[0..inCount) for owner at time now (seconds, monotonic). 1: granted (a claim the owner
 * already holds is renewed); 0: refused -- one of them is held by another owner, or the table is full.
 * A refused claim takes nothing. */
int coin_reservation_claim(UInt256 owner, const BRTxInput *inputs, size_t inCount, double now);

/* Release every outpoint owner holds. */
void coin_reservation_release(UInt256 owner);

/* Drop every claim (a test, or a wallet torn down). */
void coin_reservation_clear(void);

/* Monotonic seconds, for callers. */
double coin_reservation_now(void);

#endif /* COIN_RESERVATION_H */
