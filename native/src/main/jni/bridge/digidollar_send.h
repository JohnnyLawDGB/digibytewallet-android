#ifndef DIGIDOLLAR_SEND_H
#define DIGIDOLLAR_SEND_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRDigiDollar.h"

/*
 * The checks between a DigiDollar send's confirmation and its signature, kept free of JNI so that
 * dd_send_preview_kat runs them against the live core on the host.
 *
 * A DigiDollar transfer cannot be held across the credential prompt as bytes, the way a DGB send is:
 * the serializer leaves an input's amount out when it is zero, and DigiDollar inputs are zero-value,
 * so unsigned bytes do not parse back. So the bridge builds the transfer from the wallet twice. At
 * review it builds it, reads what it pays (dd_send_preview) and frees it: nothing native outlives the
 * prompt. After the prompt it builds it again and signs it only when the rebuilt transfer is the
 * approved send at the approved fee (dd_send_check); the wallet may have changed while the prompt
 * was up, and then the rebuilt transfer pays something else and is refused.
 *
 *   dd_send_preview  what the confirmation shows: the transfer's own fee -- its inputs minus its
 *                    outputs, looked up in the wallet's own transactions -- and what comes back to the
 *                    wallet (DigiDollar change, DGB change), and how many inputs it spends.
 *
 *   dd_send_check    only the approved send is signed: output 0 pays exactly the approved cents to the
 *                    approved key, every other output is the DigiDollar OP_RETURN or change to an
 *                    address of this wallet, and the transfer pays exactly the approved fee.
 *
 * MACRO CONVENTION: DIGIDOLLAR_SEND_UNFIXED is tested by VALUE (#if). =1 is dd_send_preview_kat's
 * comparison arm, the behaviour before this header existed: the confirmation showed the 0.1 DGB fee
 * floor whatever the transfer paid, and the transfer was signed as it was built, with nothing
 * checked. Unset or =0 is what ships.
 */

#ifndef DIGIDOLLAR_SEND_UNFIXED
#define DIGIDOLLAR_SEND_UNFIXED 0
#endif

/** The 0.1 DGB a DigiDollar transfer pays at least (BRWallet.c DD_MIN_FEE, a wallet policy copied from
 *  DigiByte Core's builder): the figure the confirmation showed before this header. */
#define DD_SEND_FEE_FLOOR_SATS 10000000ULL

typedef struct {
    /** The transfer's inputs minus its outputs, in satoshis. */
    uint64_t feeSats;
    /** DigiDollar cents the transfer pays back to this wallet: its DigiDollar change. */
    uint64_t ddChangeCents;
    /** DGB the transfer pays back to this wallet: its DGB change. */
    uint64_t dgbChangeSats;
    /** How many inputs the transfer spends (DigiDollar and DGB). */
    uint64_t inputCount;
} DdSendPreview;

typedef enum {
    DD_SEND_OK = 0,
    /** An input is not a transaction the wallet holds: the fee cannot be told. */
    DD_SEND_FEE_UNKNOWN = 1,
    /** The transfer does not pay exactly the approved fee. */
    DD_SEND_FEE_CHANGED = 2,
    /** Its outputs are not the approved payment plus the OP_RETURN and change to this wallet. */
    DD_SEND_NOT_APPROVED = 3,
} DdSendVerdict;


/* What a built transfer pays and gives back: 1 with *out set, or 0 when there is no answer (the fee is
 * unknown, or does not fit a signed 64-bit value). Reads the wallet; changes nothing. */
static inline int dd_send_preview(BRWallet *wallet, const BRTransaction *tx, DdSendPreview *out)
{
    /* STUB (red): today's behaviour in both arms -- the floor, whatever the transfer pays. The fix follows. */
    if (! tx || ! out) return 0;
    (void)wallet;
#if DIGIDOLLAR_SEND_UNFIXED
    out->feeSats = DD_SEND_FEE_FLOOR_SATS;
#else
    out->feeSats = DD_SEND_FEE_FLOOR_SATS;
#endif
    out->ddChangeCents = 0; out->dgbChangeSats = 0; out->inputCount = 0;
    return 1;
}

static inline DdSendVerdict dd_send_check(BRWallet *wallet, const BRTransaction *tx, const uint8_t key32[32],
                                          uint64_t cents, uint64_t approvedFee)
{
    /* STUB (red): today's behaviour -- signed as built, nothing checked. The fix follows. */
    (void)wallet; (void)tx; (void)key32; (void)cents; (void)approvedFee;
    return DD_SEND_OK;
}

#endif /* DIGIDOLLAR_SEND_H */
