#ifndef DIGIDOLLAR_EVENT_H
#define DIGIDOLLAR_EVENT_H

#include <stdint.h>
#include <stddef.h>
#include "BRTransaction.h"
#include "BRDigiDollar.h"

/*
 * What a DigiDollar transaction did for this wallet, for its row in the activity list. Kept free of
 * JNI so that digidollar_realtx_kat runs it on the host, against real and synthesised transactions.
 *
 * A row says what the transaction IS -- a mint, a transfer sent, a transfer received, a transfer to
 * this wallet's own address, a redemption -- read from the transaction's DigiDollar type and from
 * whose DigiDollar it spends and pays. The DGB a DigiDollar transaction moves says nothing of that:
 * a mint locks DGB as collateral (DGB leaves) while dollars arrive, a transfer to yourself spends
 * only its fee, and a redemption returns collateral (DGB arrives) while dollars are burned.
 *
 *   MINTED         a mint: `cents` is what it created (paid to this wallet when any was).
 *   SENT           a transfer this wallet paid for, to someone else: `cents` is what they were paid,
 *                  `changeCents` what came back.
 *   RECEIVED       a transfer this wallet did not pay for: `cents` is what it was paid.
 *   SELF_TRANSFER  a transfer this wallet paid for in which every dollar came back to it: `cents` is its
 *                  payment (its first DigiDollar output, where a transfer pays its recipient), `changeCents`
 *                  the rest.
 *   REDEEMED       a redemption: `cents` is what was burned -- the wallet's dollars it spent less the
 *                  change back -- and `changeCents` the change.
 *   NONE           not a DigiDollar transaction, or one that moved none of this wallet's dollars.
 *
 * The wallet is asked two things through DdEventWallet: whether an output pays it, and whether an
 * input spends one of its outputs (and if so how many cents of DigiDollar that output held).
 *
 * MACRO CONVENTION: DIGIDOLLAR_EVENT_UNFIXED is tested by VALUE (#if). =1 is digidollar_realtx_kat's
 * comparison arm: the rule the activity list used before this header, which read the direction from
 * the sign of the transaction's DGB movement (dgbNet) and then counted the dollars paid to others
 * (when DGB left) or to the wallet (when DGB arrived). Unset or =0 is what ships.
 */

#ifndef DIGIDOLLAR_EVENT_UNFIXED
#define DIGIDOLLAR_EVENT_UNFIXED 0
#endif

typedef enum {
    DD_EVENT_NONE = 0,
    DD_EVENT_MINTED = 1,
    DD_EVENT_SENT = 2,
    DD_EVENT_RECEIVED = 3,
    DD_EVENT_SELF_TRANSFER = 4,
    DD_EVENT_REDEEMED = 5,
} DdEventKind;

typedef struct {
    DdEventKind kind;
    /** The cents the row shows: minted, sent, received, moved to the wallet itself, or burned. */
    int64_t cents;
    /** DigiDollar paid back to this wallet as change (sent transfers and redemptions), else 0. */
    int64_t changeCents;
} DdEvent;

typedef struct {
    void *ctx;
    /** 1 when `output` pays an address of this wallet. */
    int (*paysWallet)(void *ctx, const BRTxOutput *output);
    /** 1 when `input` spends an output of this wallet; *ddCents is then the DigiDollar cents that
     *  output held, or -1 when it held none (a DGB coin) or they cannot be told. */
    int (*spendsWallet)(void *ctx, const BRTxInput *input, int64_t *ddCents);
    /** Only DIGIDOLLAR_EVENT_UNFIXED reads it: the transaction's net DGB movement for the wallet. */
    int64_t (*dgbNet)(void *ctx, const BRTransaction *tx);
} DdEventWallet;

static inline DdEvent dd_event_of(const BRTransaction *tx, const DdEventWallet *w)
{
    DdEvent ev = { DD_EVENT_NONE, 0, 0 };
    int64_t own = 0, other = 0, first = 0;
    int type;

    if (! tx || ! w || ! w->paysWallet || ! w->spendsWallet) return ev;
    type = BRDigiDollarTxType(tx);
    if (type == 0) return ev;

    /* The DigiDollar each output carries, by whose it is. */
    for (size_t i = 0; i < tx->outCount; i++) {
        int64_t c = BRDigiDollarOutputAmount(tx, i);
        if (c <= 0) continue;   /* not a DigiDollar token output */
        if (first == 0) first = c;
        if (w->paysWallet(w->ctx, &tx->outputs[i])) own += c;
        else other += c;
    }

#if DIGIDOLLAR_EVENT_UNFIXED
    {
        int isSend = w->dgbNet && w->dgbNet(w->ctx, tx) < 0;
        ev.kind = isSend ? DD_EVENT_SENT : DD_EVENT_RECEIVED;
        ev.cents = isSend ? other : own;
        return ev;
    }
#else
    {
        /* STUB (red): today's behaviour -- the direction from the sign of the DGB movement. The fix follows. */
        int isSend = w->dgbNet && w->dgbNet(w->ctx, tx) < 0;
        (void)first;
        ev.kind = isSend ? DD_EVENT_SENT : DD_EVENT_RECEIVED;
        ev.cents = isSend ? other : own;
        return ev;
    }
#endif
}

#endif /* DIGIDOLLAR_EVENT_H */
