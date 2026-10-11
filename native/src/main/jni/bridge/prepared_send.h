#ifndef PREPARED_SEND_H
#define PREPARED_SEND_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include "BRWallet.h"
#include "BRTransaction.h"

/*
 * The checks between a DGB send's review and its signature, kept free of JNI so that
 * fee_quote_kat runs them against the live core on the host.
 *
 * A send is built and quoted at review, before the credential prompt (createTransaction, then
 * quoteTransaction), and the SAME bytes are signed after it (signPreparedTransaction). The prepared
 * state is those bytes plus their quote -- never a native object -- so nothing native outlives the
 * prompt. The wallet can change while the prompt is up: another send can spend a coin, asset
 * detection can hold one out of the plain set, a coin's transaction can leave the wallet. So:
 *
 *   prepared_send_quote   the fee a confirmation shows: the transaction's inputs minus its outputs,
 *                         looked up in the wallet's own transactions (serialized bytes carry no input
 *                         amounts), and the virtual size it was built at. None when an input is not
 *                         a transaction the wallet holds: the fee is then unknown.
 *
 *   prepared_send_check   before signing: the bytes pay exactly the approved fee, and every coin
 *                         they spend is still one of the wallet's spendable plain coins. After
 *                         signing: the signed transaction still pays exactly the approved fee.
 *                         Signing changes no amount, so that holds unless something else is wrong
 *                         -- and then the signed bytes are not handed back to be broadcast.
 *
 * MACRO CONVENTION: PREPARED_SEND_UNFIXED is tested by VALUE (#if). =1 is fee_quote_kat's comparison
 * arm, the behaviour before this header existed: the typical 141-vbyte figure every confirmation
 * showed, and nothing checked before signing. Unset or =0 is what ships.
 */

#ifndef PREPARED_SEND_UNFIXED
#define PREPARED_SEND_UNFIXED 0
#endif

typedef enum {
    PREPARED_SEND_OK = 0,
    /** An input is not a transaction the wallet holds: the fee cannot be told. */
    PREPARED_SEND_FEE_UNKNOWN = 1,
    /** The transaction does not pay exactly the approved fee. */
    PREPARED_SEND_FEE_CHANGED = 2,
    /** A coin it spends is no longer one of the wallet's spendable plain coins. */
    PREPARED_SEND_COIN_UNAVAILABLE = 3,
} PreparedSendVerdict;

/* The quote for a transaction the wallet built: 1 with *feeOut and *vsizeOut set, or 0 when there is
 * none (the fee is unknown, or does not fit a signed 64-bit value). */
static inline int prepared_send_quote(BRWallet *wallet, const BRTransaction *tx,
                                      uint64_t *feeOut, uint64_t *vsizeOut)
{
#if PREPARED_SEND_UNFIXED
    (void)wallet; (void)tx;
    *feeOut = 141ULL * DEFAULT_FEE_PER_KB / 1000ULL;   /* a typical 1-input, 2-output send */
    *vsizeOut = 141;
    return 1;
#else
    uint64_t fee;
    size_t vsize;

    if (! wallet || ! tx || ! feeOut || ! vsizeOut || tx->inCount == 0) return 0;
    fee = BRWalletFeeForTx(wallet, tx);                  /* UINT64_MAX: an input the wallet does not hold */
    vsize = BRTransactionVSize(tx);
    if (fee == UINT64_MAX || fee > (uint64_t)INT64_MAX || vsize == 0) return 0;
    *feeOut = fee;
    *vsizeOut = (uint64_t)vsize;
    return 1;
#endif
}

#if ! PREPARED_SEND_UNFIXED
/* 1 when the outpoint (hash, n) is among utxos[0..count). */
static inline int prepared_send_has_coin(const BRUTXO *utxos, size_t count, UInt256 hash, uint32_t n)
{
    for (size_t i = 0; i < count; i++) {
        if (utxos[i].n == n && UInt256Eq(utxos[i].hash, hash)) return 1;
    }
    return 0;
}
#endif

/* Whether tx may be signed -- or, once signed, handed back to be broadcast -- under approvedFee. */
static inline PreparedSendVerdict prepared_send_check(BRWallet *wallet, const BRTransaction *tx,
                                                      uint64_t approvedFee)
{
#if PREPARED_SEND_UNFIXED
    (void)wallet; (void)tx; (void)approvedFee;
    return PREPARED_SEND_OK;
#else
    uint64_t fee;
    size_t count;
    BRUTXO *coins;
    PreparedSendVerdict verdict = PREPARED_SEND_OK;

    if (! wallet || ! tx) return PREPARED_SEND_FEE_UNKNOWN;
    fee = BRWalletFeeForTx(wallet, tx);
    if (fee == UINT64_MAX) return PREPARED_SEND_FEE_UNKNOWN;
    if (fee != approvedFee) return PREPARED_SEND_FEE_CHANGED;
    if (tx->inCount == 0) return PREPARED_SEND_COIN_UNAVAILABLE;

    /* The wallet's spendable plain coins now: spent coins, asset carriers and DigiDollar outputs are
     * not among them. One copy, taken under the wallet's lock. A coin that arrived between the two
     * calls is not in it, which can only refuse -- and reviewing again builds afresh. */
    count = BRWalletUTXOs(wallet, NULL, 0);
    coins = calloc(count ? count : 1, sizeof(*coins));
    if (! coins) return PREPARED_SEND_COIN_UNAVAILABLE;   /* cannot tell, so do not sign */
    count = BRWalletUTXOs(wallet, coins, count);
    for (size_t i = 0; i < tx->inCount && verdict == PREPARED_SEND_OK; i++) {
        if (! prepared_send_has_coin(coins, count, tx->inputs[i].txHash, tx->inputs[i].index))
            verdict = PREPARED_SEND_COIN_UNAVAILABLE;
    }
    free(coins);
    return verdict;
#endif
}

#endif /* PREPARED_SEND_H */
