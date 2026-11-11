#ifndef ASSET_SIGN_GATE_H
#define ASSET_SIGN_GATE_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include "BRWallet.h"
#include "BRTransaction.h"

/*
 * ASSET-SIGN-GATE: the checks buildAndSignAssetTransferTx makes on the transaction it assembled, before it
 * signs it and again on the signed transaction before it hands the bytes back. Kept free of JNI so that
 * asset_sign_gate_kat runs them against the live core on the host.
 *
 * Invariant. The DigiAsset sign path spends only wallet or asset coins, at the amounts the wallet holds,
 * and pays only the approved outputs and the approved fee:
 *
 *   every input   is an output of a transaction the wallet holds, paying one of the wallet's addresses,
 *                 listed at that output's own amount and script; and it is either one of the coins the
 *                 wallet's own selection would spend (BRWalletOutpointSelectable, the predicate of
 *                 BRWalletSelectableUTXOs asked of that coin) or an output the wallet holds as a
 *                 DigiAsset holding (BRWalletAssetUTXOs) that is still unspent, valid and of no other
 *                 token family (BRWalletOutpointAssetState == 1);
 *   the recipient is paid by exactly one output, at exactly the approved amount (the DigiAsset marker):
 *                 no second output to it and no other amount. When the recipient is one of the wallet's
 *                 own addresses (a send to oneself) its other outputs are the wallet's change, so only
 *                 one output at the approved amount is required;
 *   every other   output pays one of the wallet's addresses, or is the one zero-value data output
 *                 (OP_RETURN): at most one;
 *   the fee       -- the inputs, at the amounts the wallet holds, minus the outputs -- is exactly the fee
 *                 of the plan being signed, and that fee is no more than the fee the confirmation showed
 *                 (approvedMaxFee). The plan's fee alone would only check the plan against itself.
 *
 * The amounts are the wallet's, not the caller's: a legacy input's signature does not commit to its
 * amount, so an input listed below what it holds would still sign, and the difference would be paid as
 * fee. Checking the signed transaction too means that whatever the signer changed, nothing is handed back
 * to be broadcast unless it is still exactly what was approved.
 *
 * Not checked natively: the asset quantity the data output's transfer instructions assign. Kotlin encodes
 * them from the approved quantity; the native decoder is not wired to this path.
 *
 * MACRO CONVENTION: ASSET_SIGN_GATE_UNFIXED is tested by VALUE (#if). =1 is asset_sign_gate_kat's
 * comparison arm, the behaviour before this header: nothing checked. =2 is the first version of this
 * gate: the recipient checked by script alone, any number of data outputs, and the fee checked only
 * against the plan's own fee, never against the approved one. Unset or =0 is what ships.
 */

#ifndef ASSET_SIGN_GATE_UNFIXED
#define ASSET_SIGN_GATE_UNFIXED 0
#endif

typedef enum {
    ASSET_SIGN_OK = 0,
    /** An input is not an output of a transaction the wallet holds. */
    ASSET_SIGN_COIN_UNKNOWN = 1,
    /** An input is listed at another amount or script than the output the wallet holds. */
    ASSET_SIGN_AMOUNT_MISMATCH = 2,
    /** An input is neither a coin the wallet's selection would spend nor a held DigiAsset coin. */
    ASSET_SIGN_COIN_NOT_WALLETS = 3,
    /** An output pays neither the approved recipient nor the wallet and is not the one zero-value data
     *  output. */
    ASSET_SIGN_NOT_APPROVED = 4,
    /** The transaction does not pay exactly the plan's fee. */
    ASSET_SIGN_FEE_CHANGED = 5,
    /** The recipient is not paid by exactly one output at exactly the approved amount. */
    ASSET_SIGN_RECIPIENT_CHANGED = 6,
    /** The plan's fee is above the fee the confirmation showed. */
    ASSET_SIGN_FEE_ABOVE_APPROVED = 7,
} AssetSignVerdict;

#if ! (ASSET_SIGN_GATE_UNFIXED == 1)
/* 1 when the outpoint (hash, n) is among list[0..count). */
static inline int asset_sign_has_coin(const BRUTXO *list, size_t count, UInt256 hash, uint32_t n)
{
    for (size_t i = 0; i < count; i++) {
        if (list[i].n == n && UInt256Eq(list[i].hash, hash)) return 1;
    }
    return 0;
}

static inline int asset_sign_is_data_output(const BRTxOutput *o)
{
    return o->amount == 0 && o->script && o->scriptLen > 0 && o->script[0] == 0x6a;   /* OP_RETURN */
}
#endif

/* Whether tx may be signed -- or, once signed, handed back to be broadcast -- as the approved DigiAsset
 * send: recipSats to recipScript by one output, the wallet and one data output otherwise, at planFee, which
 * is no more than approvedMaxFee, from the wallet's coins. */
static inline AssetSignVerdict asset_sign_check(BRWallet *wallet, const BRTransaction *tx,
                                                const uint8_t *recipScript, size_t recipScriptLen,
                                                uint64_t recipSats, uint64_t planFee, uint64_t approvedMaxFee)
{
#if ASSET_SIGN_GATE_UNFIXED == 1
    (void)wallet; (void)tx; (void)recipScript; (void)recipScriptLen; (void)recipSats; (void)planFee;
    (void)approvedMaxFee;
    return ASSET_SIGN_OK;
#else
    AssetSignVerdict verdict = ASSET_SIGN_OK;
    uint64_t totalIn = 0, totalOut = 0;
    size_t nAsset = 0, nData = 0, nRecip = 0, nRecipAt = 0;
    int recipIsWallet = 0;
    BRUTXO *assets;

    if (!wallet || !tx || tx->inCount == 0) return ASSET_SIGN_COIN_UNKNOWN;
    if (tx->outCount == 0 || !recipScript || recipScriptLen == 0) return ASSET_SIGN_NOT_APPROVED;
#if ASSET_SIGN_GATE_UNFIXED != 2
    if (recipSats == 0) return ASSET_SIGN_RECIPIENT_CHANGED;
    if (planFee > approvedMaxFee) return ASSET_SIGN_FEE_ABOVE_APPROVED;
#else
    (void)recipSats; (void)approvedMaxFee;
#endif

    /* Outputs first: nothing of the wallet is read for a transaction that pays someone not approved. */
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];
        int toRecipient = o->script && o->scriptLen == recipScriptLen &&
                          memcmp(o->script, recipScript, recipScriptLen) == 0;
        int toWallet = o->address[0] != '\0' && BRWalletContainsAddress(wallet, o->address);
        int isData = asset_sign_is_data_output(o);

        if (!isData && !toRecipient && !toWallet) return ASSET_SIGN_NOT_APPROVED;
        if (isData) nData++;
        if (toRecipient) {
            nRecip++;
            if (o->amount == recipSats) nRecipAt++;
            if (toWallet) recipIsWallet = 1;
        }
        if (o->amount > UINT64_MAX - totalOut) return ASSET_SIGN_FEE_CHANGED;
        totalOut += o->amount;
    }
#if ASSET_SIGN_GATE_UNFIXED != 2
    if (nData > 1) return ASSET_SIGN_NOT_APPROVED;
    if (recipIsWallet ? nRecipAt < 1 : (nRecip != 1 || nRecipAt != 1)) return ASSET_SIGN_RECIPIENT_CHANGED;
#else
    (void)nData; (void)nRecip; (void)nRecipAt; (void)recipIsWallet;
#endif

    /* One copy of the held DigiAsset outputs, taken under the wallet's lock; each plain coin is asked of
     * the wallet alone. A coin that changed between the two can only refuse -- and the send is planned
     * again. */
    {
        size_t count = BRWalletAssetUTXOs(wallet, NULL, 0);
        assets = (BRUTXO *)calloc(count ? count : 1, sizeof(*assets));
        if (!assets) return ASSET_SIGN_COIN_UNKNOWN;   /* cannot tell */
        nAsset = BRWalletAssetUTXOs(wallet, assets, count);
    }

    for (size_t i = 0; i < tx->inCount && verdict == ASSET_SIGN_OK; i++) {
        const BRTxInput *in = &tx->inputs[i];
        BRTransaction *parent = BRWalletCopyTransactionForHash(wallet, in->txHash);
        const BRTxOutput *held;

        if (!parent || in->index >= parent->outCount) {
            verdict = ASSET_SIGN_COIN_UNKNOWN;
        } else {
            held = &parent->outputs[in->index];
            if (in->amount != held->amount || !in->script || in->scriptLen != held->scriptLen ||
                memcmp(in->script, held->script, held->scriptLen) != 0) {
                verdict = ASSET_SIGN_AMOUNT_MISMATCH;
            } else if (held->address[0] == '\0' || !BRWalletContainsAddress(wallet, held->address)) {
                verdict = ASSET_SIGN_COIN_NOT_WALLETS;
            } else if (!BRWalletOutpointSelectable(wallet, in->txHash, in->index) &&
                       !(asset_sign_has_coin(assets, nAsset, in->txHash, in->index) &&
                         BRWalletOutpointAssetState(wallet, in->txHash, in->index) == 1)) {
                verdict = ASSET_SIGN_COIN_NOT_WALLETS;
            } else if (held->amount > UINT64_MAX - totalIn) {
                verdict = ASSET_SIGN_FEE_CHANGED;
            } else {
                totalIn += held->amount;
            }
        }
        if (parent) BRTransactionFree(parent);
    }
    free(assets);
    if (verdict != ASSET_SIGN_OK) return verdict;

    if (totalOut > totalIn || totalIn - totalOut != planFee) return ASSET_SIGN_FEE_CHANGED;
    return ASSET_SIGN_OK;
#endif
}

#endif /* ASSET_SIGN_GATE_H */
