// Host KAT: the two-sided fee guard that stands between a foreign-seed asset transfer and
// a silent, irreversible burn.
//
// WHY THIS GUARD EXISTS. buildAndSignLegacySweep computes `out = totalIn - fee`, so it
// cannot leak value however wrong the caller is. buildAndSignForeignAssetTransfer cannot:
// the caller states the outputs, and whatever is left over becomes the miner's fee. A
// transfer that spends a 6,000-sat asset marker plus a 200,000-sat fee UTXO and forgets its
// change output writes ~200,000 sats to a miner on a transaction that should cost ~55,000.
// Nothing on the network rejects that — it is a perfectly valid transaction. The user simply
// loses the difference, with no error anywhere.
//
// So the implied fee is bounded on BOTH sides before signing:
//   too LOW  -> below what the size needs; it would never relay.
//   too HIGH -> far above what the size justifies; the caller lost track of value.
//               Refused rather than signed, because the loss is silent and permanent.
//
// WHY A HOST KAT AND NOT AN INSTRUMENTED TEST. The band is pure arithmetic on the boundary
// between "will not relay" and "burns the user's coins", and the interesting cases are the
// exact edges. Those are cheap to pin here and expensive to provoke on a device, where
// reaching the guard at all means holding a real asset and a real fee UTXO.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <stdint.h>
#include <stddef.h>

#include "foreign_tx_fee_guard.h"

static int failures = 0;

static const char *verdict_name(ForeignTxFeeVerdict v) {
    switch (v) {
        case FOREIGN_TX_FEE_OK:        return "OK";
        case FOREIGN_TX_FEE_TOO_LOW:   return "TOO_LOW";
        case FOREIGN_TX_FEE_TOO_HIGH:  return "TOO_HIGH";
        case FOREIGN_TX_FEE_OVERSPEND: return "OVERSPEND";
    }
    return "?";
}

static void check(const char *what, ForeignTxFeeVerdict got, ForeignTxFeeVerdict want) {
    if (got == want) {
        printf("  ok   %-56s %s\n", what, verdict_name(got));
    } else {
        printf("  FAIL %-56s got %s want %s\n", what, verdict_name(got), verdict_name(want));
        failures++;
    }
}

/* The DGB min-relay rate: 100,000 sat/kB == 100 sat/byte. */
#define FEE_PER_KB 100000ULL

int main(void) {
    printf("foreign_tx_fee_guard_kat\n");

    uint64_t implied = 0, expected = 0;

    /* ---- the shape ForeignAssetTransferPlan actually produces --------------------------
     * 2 inputs (asset marker 6,000 + fee UTXO 200,000), 3 outputs (marker 6,000,
     * OP_RETURN 0, change 145,100). Implied fee 54,900; size-implied expectation 43,200. */
    check("real plan: 2-in 3-out, change present",
          foreign_tx_fee_check(206000, 151100, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OK);
    if (implied != 54900) { printf("  FAIL implied fee %llu, want 54900\n",
                                   (unsigned long long)implied); failures++; }
    if (expected != 43200) { printf("  FAIL expected fee %llu, want 43200\n",
                                    (unsigned long long)expected); failures++; }

    /* The same transaction with the change output dropped — the exact mistake the guard is
     * here to catch. 200,000 sats to a miner instead of 54,900. */
    check("change output forgotten: the whole reserve becomes fee",
          foreign_tx_fee_check(206000, 6000, 2, 2, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_TOO_HIGH);

    /* Four inputs, three outputs — several small reserved UTXOs combined. */
    check("4-in 3-out: multiple small fee inputs",
          foreign_tx_fee_check(126000, 41100, 4, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OK);

    /* ---- the edges ------------------------------------------------------------------- */

    /* expected for 2-in/3-out is 43,200. Exactly at the floor must pass: a fee equal to
     * what the size needs relays fine, and rejecting it would refuse a correct transaction. */
    check("fee exactly at the relay floor",
          foreign_tx_fee_check(100000, 100000 - 43200, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OK);
    check("one satoshi under the floor",
          foreign_tx_fee_check(100000, 100000 - 43199, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_TOO_LOW);

    /* The ceiling is 3x. At exactly 3x we still sign — the bound is "over 3x", and a
     * transaction paying triple is wasteful but not evidence of lost value. */
    check("fee exactly at 3x the ceiling",
          foreign_tx_fee_check(200000, 200000 - 43200 * 3, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OK);
    check("one satoshi over 3x",
          foreign_tx_fee_check(200000, 200000 - (43200 * 3 + 1), 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_TOO_HIGH);

    /* ---- the impossible ---------------------------------------------------------------
     * Outputs above inputs is not a fee question at all; it must be caught before the
     * subtraction, or the unsigned arithmetic wraps to an enormous "fee" that reads as
     * TOO_HIGH by accident rather than by check. */
    check("outputs exceed inputs",
          foreign_tx_fee_check(50000, 60000, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OVERSPEND);
    check("outputs exceed inputs by one satoshi",
          foreign_tx_fee_check(50000, 50001, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_OVERSPEND);

    /* Zero fee: valid arithmetic, unrelayable transaction. */
    check("zero implied fee",
          foreign_tx_fee_check(50000, 50000, 2, 3, FEE_PER_KB, &implied, &expected),
          FOREIGN_TX_FEE_TOO_LOW);

    /* ---- the floor --------------------------------------------------------------------
     * A fee rate low enough that the size-implied expectation falls under the 1,000-sat
     * absolute minimum must still be held at 1,000 — otherwise a caller passing feePerKb=0
     * would have every fee, including zero, accepted as "at least what the size needs". */
    check("feePerKb 0 still floors the expectation at 1000 sats",
          foreign_tx_fee_check(50000, 50000, 2, 3, 0, &implied, &expected),
          FOREIGN_TX_FEE_TOO_LOW);
    if (expected != 1000) { printf("  FAIL floor expected %llu, want 1000\n",
                                   (unsigned long long)expected); failures++; }
    check("feePerKb 0: a 1000-sat fee clears the floor",
          foreign_tx_fee_check(51000, 50000, 2, 3, 0, &implied, &expected),
          FOREIGN_TX_FEE_OK);

    /* ---- arithmetic safety -------------------------------------------------------------
     * An absurd fee rate must not overflow the 3x ceiling computation into a small number,
     * which would turn the upper bound off precisely when it matters most. */
    check("absurd feePerKb does not wrap the ceiling",
          foreign_tx_fee_check(206000, 151100, 2, 3, UINT64_MAX, &implied, &expected),
          FOREIGN_TX_FEE_TOO_LOW);

    printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
