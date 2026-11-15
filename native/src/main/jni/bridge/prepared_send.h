#ifndef PREPARED_SEND_H
#define PREPARED_SEND_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
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
 *   prepared_send_check   only what was approved is signed: before signing, the bytes' outputs are
 *                         exactly the approved payment (that amount to that script) plus at most
 *                         one change output to an address of this wallet, they pay exactly the
 *                         approved fee, and every coin they spend is still one the wallet's own
 *                         selection would spend (SEND-GATE-SET: BRWalletSelectableUTXOs -- never an
 *                         unconfirmed coin someone else sent, an asset-held, ONE-FAMILY-held or
 *                         unsignable output). After signing: the same check on the signed
 *                         transaction. Signing changes no output or amount, so that holds unless
 *                         something else is wrong -- and then the signed bytes are not handed back
 *                         to be broadcast.
 *
 * MACRO CONVENTION: PREPARED_SEND_UNFIXED is tested by VALUE (#if). =1 is fee_quote_kat's comparison
 * arm, the behaviour before this header existed: the typical 141-vbyte figure every confirmation
 * showed, and nothing checked before signing. Unset or =0 is what ships.
 *
 * SEND_GATE_SET_UNFIXED, also tested by VALUE: fee_quote_kat's SEND-GATE-SET comparison arms. =1 is the
 * membership set before that item (BRWalletUTXOs, the raw set the balance sums); =2 is the selectable set
 * enumerated whole (BRWalletSelectableUTXOs), whose one walk budget can run out before the gate reaches a
 * coin selection took. Unset or =0 ships: each input asked alone (BRWalletOutpointSelectable).
 */

#ifndef PREPARED_SEND_UNFIXED
#define PREPARED_SEND_UNFIXED 0
#endif
#ifndef SEND_GATE_SET_UNFIXED
#define SEND_GATE_SET_UNFIXED 0
#endif

/* SEND-GATE-SET: 1 when the coin (hash, n) is one a prepared send may spend: one the wallet's own
 * selection would spend. Asked of that outpoint alone (BRWalletOutpointSelectable, its own walk budget),
 * so a wallet with many unconfirmed coins cannot exhaust a shared budget before the gate reaches a coin
 * selection already took. */
static inline int prepared_send_coin_ok(BRWallet *wallet, UInt256 hash, uint32_t n)
{
#if SEND_GATE_SET_UNFIXED == 1
    /* comparison: the raw set the balance sums */
    size_t count = BRWalletUTXOs(wallet, NULL, 0), i;
    BRUTXO *u = calloc(count ? count : 1, sizeof(*u));
    int r = 0;
    if (! u) return 0;
    count = BRWalletUTXOs(wallet, u, count);
    for (i = 0; i < count && ! r; i++) r = u[i].n == n && UInt256Eq(u[i].hash, hash);
    free(u);
    return r;
#elif SEND_GATE_SET_UNFIXED == 2
    /* comparison: the whole selectable set enumerated, one walk budget shared by every coin */
    size_t count = BRWalletSelectableUTXOs(wallet, NULL, 0), i;
    BRUTXO *u = calloc(count ? count : 1, sizeof(*u));
    int r = 0;
    if (! u) return 0;
    count = BRWalletSelectableUTXOs(wallet, u, count);
    for (i = 0; i < count && ! r; i++) r = u[i].n == n && UInt256Eq(u[i].hash, hash);
    free(u);
    return r;
#else
    return BRWalletOutpointSelectable(wallet, hash, n);
#endif
}

typedef enum {
    PREPARED_SEND_OK = 0,
    /** An input is not a transaction the wallet holds: the fee cannot be told. */
    PREPARED_SEND_FEE_UNKNOWN = 1,
    /** The transaction does not pay exactly the approved fee. */
    PREPARED_SEND_FEE_CHANGED = 2,
    /** A coin it spends is not one the wallet's own selection would spend (SEND-GATE-SET). */
    PREPARED_SEND_COIN_UNAVAILABLE = 3,
    /** Its outputs are not the approved payment plus change to a wallet address. */
    PREPARED_SEND_NOT_APPROVED = 4,
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
/* 1 when tx's outputs are exactly the approved payment -- payAmount to payScript -- plus at most one
 * change output paying an address of this wallet; 0 for anything else: another recipient, another
 * amount, an output nobody approved, change to an address that is not the wallet's. */
static inline int prepared_send_pays_as_approved(BRWallet *wallet, const BRTransaction *tx,
                                                 const uint8_t *payScript, size_t payScriptLen,
                                                 uint64_t payAmount)
{
    size_t payments = 0, change = 0;

    if (! payScript || payScriptLen == 0 || payAmount == 0 || tx->outCount == 0 || tx->outCount > 2) return 0;
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];

        if (payments == 0 && o->amount == payAmount && o->script && o->scriptLen == payScriptLen &&
            memcmp(o->script, payScript, payScriptLen) == 0) payments++;
        else if (o->amount > 0 && o->address[0] != '\0' && BRWalletContainsAddress(wallet, o->address)) change++;
        else return 0;
    }
    return payments == 1 && change <= 1;
}

#endif

/* Whether tx may be signed -- or, once signed, handed back to be broadcast -- as the approved send:
 * payAmount to payScript (plus change to the wallet), at approvedFee, from coins still the wallet's. */
static inline PreparedSendVerdict prepared_send_check(BRWallet *wallet, const BRTransaction *tx,
                                                      const uint8_t *payScript, size_t payScriptLen,
                                                      uint64_t payAmount, uint64_t approvedFee)
{
#if PREPARED_SEND_UNFIXED
    (void)wallet; (void)tx; (void)payScript; (void)payScriptLen; (void)payAmount; (void)approvedFee;
    return PREPARED_SEND_OK;
#else
    uint64_t fee;
    PreparedSendVerdict verdict = PREPARED_SEND_OK;

    if (! wallet || ! tx) return PREPARED_SEND_FEE_UNKNOWN;
    fee = BRWalletFeeForTx(wallet, tx);
    if (fee == UINT64_MAX) return PREPARED_SEND_FEE_UNKNOWN;
    if (fee != approvedFee) return PREPARED_SEND_FEE_CHANGED;
    if (! prepared_send_pays_as_approved(wallet, tx, payScript, payScriptLen, payAmount)) return PREPARED_SEND_NOT_APPROVED;
    if (tx->inCount == 0) return PREPARED_SEND_COIN_UNAVAILABLE;

    /* Each coin must be one the wallet's own selection would spend now (SEND-GATE-SET): spent coins,
     * asset carriers, DigiDollar and ONE-FAMILY-held outputs, unsignable outputs and unconfirmed coins
     * someone else sent are not. Each is asked alone, under the wallet's lock. */
    for (size_t i = 0; i < tx->inCount && verdict == PREPARED_SEND_OK; i++) {
        if (! prepared_send_coin_ok(wallet, tx->inputs[i].txHash, tx->inputs[i].index))
            verdict = PREPARED_SEND_COIN_UNAVAILABLE;
    }
    return verdict;
#endif
}

#endif /* PREPARED_SEND_H */
