#ifndef DIGIDOLLAR_TRANSFER_LAYOUT_H
#define DIGIDOLLAR_TRANSFER_LAYOUT_H

#include <stdint.h>
#include <string.h>

#include "BRDigiDollar.h"

/**
 * The consensus-significant shape of a DigiDollar TRANSFER.
 *
 * ## Why this is a header and not inline in the JNI
 *
 * Getting any of it wrong destroys dollars, and the interesting parts — the nVersion marker, the
 * OP_RETURN bytes, the order of the outputs — are cheap to check on a host and expensive to
 * discover on a device holding someone's money. Kept free of JNI so digidollar_transfer_kat can
 * pin them.
 *
 * Every value here is mirrored from BRWalletCreateDigiDollarTransfer, the builder DigiByte's own
 * wallet path uses, and cross-checked against a real mainnet DigiDollar transaction
 * (40a78f1306123354…) whose marker reads 6a 02 'DD' 01 02 01 64 — $1.00.
 *
 * ## The ordering rule
 *
 * That builder carries the comment "NO shuffle (output order is consensus-significant)". The
 * order is: recipient P2TR at value 0, then optional DD change, then optional DGB change, and the
 * OP_RETURN LAST. A transfer whose outputs are rearranged is not the same transfer.
 */

/** nVersion of a DD TRANSFER: 0x0770 marker in the low 16 bits, type 2 in the top byte. */
#define DD_TX_VERSION_TRANSFER 0x02000770u

/** Consensus fee floor for a DigiDollar transaction: 0.1 DGB. Not an estimate — below this the
 *  transfer is not accepted however well-formed. */
#define DD_MIN_FEE_SATS 10000000ULL

/** Per-output consensus bounds, in cents. */
#define DD_MIN_OUTPUT_CENTS 100LL          /* $1.00 */
#define DD_MAX_OUTPUT_CENTS 10000000LL     /* $100,000 */

/**
 * Write the P2TR scriptPubKey that pays a taproot output key: `OP_1 <push32> X(Q)`.
 * Always 34 bytes. The key is used verbatim — a DigiDollar recipient key is already tweaked, and
 * re-tweaking it would pay an address nobody holds.
 */
static inline size_t dd_recipient_script(const uint8_t key32[32], uint8_t out[34]) {
    out[0] = 0x51;  /* OP_1 */
    out[1] = 0x20;  /* push 32 */
    memcpy(out + 2, key32, 32);
    return 34;
}

/**
 * Write the DigiDollar OP_RETURN: `6a 02 'D' 'D' 01 02 <push cents> [<push ddChange>]`.
 *
 * The second push exists only when the transfer leaves change. Recovery never does — it moves the
 * whole balance — but the encoder supports it so the layout matches the reference builder exactly
 * rather than approximately.
 *
 * @param ddChangeCents 0 for no change output.
 * @return bytes written, or 0 if they would not fit.
 */
static inline size_t dd_op_return_script(int64_t cents, int64_t ddChangeCents,
                                         uint8_t *out, size_t outLen) {
    if (!out || outLen < 8) return 0;
    size_t ol = 0;
    out[ol++] = 0x6a;                       /* OP_RETURN */
    out[ol++] = 0x02; out[ol++] = 0x44; out[ol++] = 0x44;   /* push2 "DD" */
    out[ol++] = 0x01; out[ol++] = 0x02;     /* push1 type = 2 (TRANSFER) */

    uint8_t enc[9];
    size_t el = BRDigiDollarWriteScriptNum(cents, enc);
    if (el == 0 || ol + 1 + el > outLen) return 0;
    out[ol++] = (uint8_t)el; memcpy(out + ol, enc, el); ol += el;

    if (ddChangeCents > 0) {
        el = BRDigiDollarWriteScriptNum(ddChangeCents, enc);
        if (el == 0 || ol + 1 + el > outLen) return 0;
        out[ol++] = (uint8_t)el; memcpy(out + ol, enc, el); ol += el;
    }
    return ol;
}

/** Whether a cent amount is inside the per-output consensus range. */
static inline int dd_cents_in_range(int64_t cents) {
    return cents >= DD_MIN_OUTPUT_CENTS && cents <= DD_MAX_OUTPUT_CENTS;
}

#endif /* DIGIDOLLAR_TRANSFER_LAYOUT_H */
