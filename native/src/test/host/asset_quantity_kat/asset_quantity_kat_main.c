// Host KAT: proves BRAssetQuantity.h -- how many DigiAsset token units land on
// one transaction output.
//
// Two defects are gated here, one historical and one latent:
//
//   RANGE DROPPED (historical). A range instruction credits `amount` to EVERY
//   output in 0..outputIndex (RenzoDD/digiasset-core DigiByteTransaction.cpp,
//   `startI = range ? 0 : output`). The shipped code dropped range entirely, so
//   every range receive counted 0 -- a silent under-count of a real holding.
//
//   OVERFLOW UNGUARDED (latent, and still live in the Kotlin mirror). `amount`
//   comes off an attacker-chosen OP_RETURN through a fixed-precision decoder
//   that multiplies a 42-bit mantissa by up to 10^7, so it can already be any
//   64-bit value; a range instruction then multiplies it by up to 8192. Without
//   overflow checks the assigned total wraps NEGATIVE and inputUnits - assigned
//   becomes a credit for units that do not exist.
//
// Ported from core/asset/AssetTxQuantity.kt. Header-only under test -- no core
// .c files, no linking beyond libc.
#include <stdio.h>
#include <stdint.h>

#include "BRAssetQuantity.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// Build one instruction without a 6-field positional call at every site: the
// KAT's own vectors are where a transposed `range`/`percent` pair would hide.
static BRAssetTransferInstruction inst(int range, int percent, int isBurn,
                                       int32_t outputIndex, int64_t amount)
{
    BRAssetTransferInstruction i;
    i.skip = 0;
    i.range = range;
    i.percent = percent;
    i.isBurn = isBurn;
    i.outputIndex = outputIndex;
    i.amount = amount;
    return i;
}

// An INDEPENDENT restatement of the reference implementation's crediting rule,
// written as a different shape from the header's (an explicit inner loop over
// the credited outputs rather than a membership test), so test9 is not an
// assertion derived from the declaration it tests. Small values only -- this
// one deliberately has no overflow handling to disagree about.
static int64_t reference_credit(const BRAssetTransferInstruction *insts, size_t n, int32_t vout)
{
    int64_t total = 0;
    size_t k;

    for (k = 0; k < n; k++) {
        int32_t startI = insts[k].range ? 0 : insts[k].outputIndex;
        int32_t o;

        if (insts[k].percent || insts[k].isBurn) continue;
        for (o = startI; o <= insts[k].outputIndex; o++) {
            if (o == vout) total += insts[k].amount;
        }
    }
    return total;
}

int main(void)
{
    BRAssetTransferInstruction insts[4];
    BRAssetQuantityStatus st;
    int64_t v;

    // test1 -- ISSUANCE credits the whole supply to the first non-OP_RETURN
    // output (the issuer's marker) and nothing to anyone else.
    st = BRAssetQuantityForOutput(DA_ISSUANCE, 5000, 1, 1, NULL, 0, &v);
    check(st == BRAssetQuantityOK && v == 5000, "test1: issuance credits the first non-OP_RETURN output");
    st = BRAssetQuantityForOutput(DA_ISSUANCE, 5000, 2, 1, NULL, 0, &v);
    check(st == BRAssetQuantityOK && v == 0, "test1: issuance credits no other output");
    // No non-OP_RETURN output at all: nobody is credited, rather than output 0
    // being credited by a -1 that compares equal to nothing.
    st = BRAssetQuantityForOutput(DA_ISSUANCE, 5000, 0, -1, NULL, 0, &v);
    check(st == BRAssetQuantityOK && v == 0, "test1: issuance with no eligible output credits nobody");

    // test2 -- a FIXED transfer instruction lands on its outputIndex only.
    insts[0] = inst(0, 0, 0, 1, 700);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 1, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 700, "test2: a fixed instruction credits its own output");
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 0, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test2: a fixed instruction credits no other output");

    // test3 -- RANGE credits every output in 0..outputIndex. THE historical
    // defect: dropping range makes a real receive read as 0. RED GATE 1.
    insts[0] = inst(1, 0, 0, 2, 40);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 0, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 40, "test3: a range instruction credits every output in 0..outputIndex");
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 2, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 40, "test3: a range instruction credits its top output");
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 3, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test3: a range instruction credits nothing above its top");

    // test4 -- BURN as an OPERATION destroys everything: 0 to every output.
    insts[0] = inst(0, 0, 0, 1, 700);
    st = BRAssetQuantityForOutput(DA_BURN, 0, 1, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test4: a burn operation credits no output");

    // test5 -- a burn INSTRUCTION (the reserved non-range index 31) credits
    // nobody, but its units are still consumed from the inputs; see test7.
    insts[0] = inst(0, 0, 1, 31, 700);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 31, 0, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test5: a burn instruction credits nobody");

    // test6 -- percent is SKIPPED for crediting (an underestimate over a fake
    // number) but makes the remainder UNKNOWN, which is the answer that
    // protects the balance.
    insts[0] = inst(0, 1, 0, 1, 50);
    insts[1] = inst(0, 0, 0, 1, 200);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 1, 0, insts, 2, &v);
    check(st == BRAssetQuantityOK && v == 200, "test6: a percent instruction is skipped, not refused");
    st = BRAssetImplicitChange(DA_TRANSFER, 1, 1000, insts, 2, &v);
    check(st == BRAssetQuantityUnknown && v == 0, "test6: a percent instruction makes the remainder UNKNOWN");

    // test7 -- CONSUMPTION IS NOT CREDITING. A range instruction credits
    // `amount` to each of outputs 0..2 (120 credited in total) but consumes
    // (2 + 1) * 40 = 120; a burn consumes its units while crediting nobody.
    // Getting this wrong is how a remainder gets invented.
    insts[0] = inst(1, 0, 0, 2, 40);   // consumes 120
    insts[1] = inst(0, 0, 1, 31, 30);  // burn: consumes 30, credits nobody
    st = BRAssetImplicitChange(DA_TRANSFER, 1, 1000, insts, 2, &v);
    check(st == BRAssetQuantityOK && v == 850, "test7: range consumes (outputIndex + 1) * amount and a burn consumes too");

    // test8 -- unresolved input units are UNKNOWN, never 0. Reading them as 0
    // would credit the entire input balance as change.
    insts[0] = inst(0, 0, 0, 1, 700);
    st = BRAssetImplicitChange(DA_TRANSFER, 0, 0, insts, 1, &v);
    check(st == BRAssetQuantityUnknown && v == 0, "test8: unresolved input units are UNKNOWN, not zero");

    // ISSUANCE has no remainder to compute: the supply is already credited by
    // the first-non-OP_RETURN convention, so a leftover here would double-count.
    st = BRAssetImplicitChange(DA_ISSUANCE, 1, 1000, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test8: issuance has no implicit remainder");

    // test9 -- the header agrees with an independently-written restatement of
    // the reference's crediting rule across a mixed instruction set.
    {
        int32_t vout;
        int ok = 1;

        insts[0] = inst(1, 0, 0, 3, 11);   // range to 0..3
        insts[1] = inst(0, 0, 0, 2, 5);    // fixed at 2
        insts[2] = inst(0, 1, 0, 1, 90);   // percent -- skipped by both
        insts[3] = inst(0, 0, 1, 31, 7);   // burn -- credited to nobody by both
        for (vout = 0; vout <= 4; vout++) {
            int64_t ref = reference_credit(insts, 4, vout);
            st = BRAssetQuantityForOutput(DA_TRANSFER, 0, vout, 0, insts, 4, &v);
            if (st != BRAssetQuantityOK || v != ref) ok = 0;
        }
        check(ok, "test9: crediting matches an independent restatement of the reference rule");
    }

    // test10 -- the remainder lands on the LAST output, verbatim, and
    // forOutputTotal adds it there and nowhere else.
    check(BRAssetImplicitChangeVout(3) == 2, "test10: the remainder lands on the last output");
    // A failed read (outputCount 0) must match no real vout rather than
    // crediting output 0.
    check(BRAssetImplicitChangeVout(0) == -1, "test10: an empty output list credits the remainder to nobody");

    insts[0] = inst(0, 0, 0, 0, 300);
    st = BRAssetQuantityForOutputTotal(DA_TRANSFER, 0, 1, 0, 1, 1000, 2, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 700, "test10: the last output gets explicit + remainder");
    st = BRAssetQuantityForOutputTotal(DA_TRANSFER, 0, 0, 0, 1, 1000, 2, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 300, "test10: a non-last output gets the explicit units only");
    // An UNKNOWN remainder contributes nothing and the total stays OK: the
    // balance under-states rather than invents. Mirrors the Kotlin exactly.
    st = BRAssetQuantityForOutputTotal(DA_TRANSFER, 0, 1, 0, 0, 0, 2, insts, 1, &v);
    check(st == BRAssetQuantityOK && v == 0, "test10: an unknown remainder credits nothing to the last output");

    // test11 -- crafted arithmetic cannot fabricate a remainder. RED GATE 2.
    //
    // Two instructions each assigning INT64_MAX: the honest answer is that the
    // header is malformed (it assigns far more than the 1000 units available).
    // Unguarded, the sum wraps to -2 and the wallet credits 1002 units that do
    // not exist to the last output.
    insts[0] = inst(0, 0, 0, 0, INT64_MAX);
    insts[1] = inst(0, 0, 0, 1, INT64_MAX);
    st = BRAssetImplicitChange(DA_TRANSFER, 1, 1000, insts, 2, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test11: a crafted instruction pair cannot fabricate a remainder");

    // The multiply is the wider door: a 13-bit range index scales `amount` by
    // up to 8192 before anything is summed.
    insts[0] = inst(1, 0, 0, 8191, (int64_t)1 << 50);
    st = BRAssetImplicitChange(DA_TRANSFER, 1, 1000, insts, 1, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test11: a range multiply that overflows is UNSOUND");

    // The same guard on the crediting side.
    insts[0] = inst(0, 0, 0, 1, INT64_MAX);
    insts[1] = inst(0, 0, 0, 1, 1);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 1, 0, insts, 2, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test11: an overflowing credit sum is UNSOUND");

    // test12 -- the decoder can already yield a negative amount (mantissa *
    // 10^exponent wraps), and a negative unit count is meaningless. Refuse it
    // rather than subtracting it into a larger balance.
    insts[0] = inst(0, 0, 0, 1, -5);
    st = BRAssetQuantityForOutput(DA_TRANSFER, 0, 1, 0, insts, 1, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test12: a negative amount is UNSOUND, not a credit");
    st = BRAssetImplicitChange(DA_TRANSFER, 1, 1000, insts, 1, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test12: a negative amount makes the remainder UNSOUND");
    insts[0] = inst(0, 0, 0, 1, 5);
    st = BRAssetImplicitChange(DA_TRANSFER, 1, -1, insts, 1, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test12: negative input units are UNSOUND");

    // test13 -- C-ONLY. DA_UNDEFINED is a real member of the core's operation
    // enum (BRAssetData.h has carried it since 2019) and a C enum is an int
    // besides, so an out-of-range value can arrive from a future protocol
    // version or a bad cast. Kotlin's exhaustive `when` made both unreachable;
    // here the answer must be defined, and it must never be a credit.
    st = BRAssetQuantityForOutput(DA_UNDEFINED, 5000, 0, 0, NULL, 0, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test13: DA_UNDEFINED credits nothing");
    st = BRAssetImplicitChange(DA_UNDEFINED, 1, 1000, NULL, 0, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test13: DA_UNDEFINED has no remainder");
    st = BRAssetQuantityForOutput((BRAssetOperation)9999, 5000, 0, 0, NULL, 0, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test13: an unknown operation credits nothing");
    st = BRAssetImplicitChange((BRAssetOperation)9999, 1, 1000, NULL, 0, &v);
    check(st == BRAssetQuantityUnsound && v == 0, "test13: an unknown operation has no remainder");

    // test13b -- the enum values are NOT the Kotlin ordinals. Pinned here so a
    // parity test written from `.ordinal` fails on the Mac instead of silently
    // reading every TRANSFER as an ISSUANCE on a device.
    check(DA_UNDEFINED == 0 && DA_ISSUANCE == 1 && DA_TRANSFER == 2 && DA_BURN == 3,
          "test13b: the core operation enum is 1-based with DA_UNDEFINED at 0");

    // test14 -- the FAIL-CLOSED spending decision, which is the one that can
    // destroy an asset if it goes the wrong way. It excludes on a positive
    // remainder AND on every answer that is not a confident zero.
    insts[0] = inst(0, 0, 0, 0, 300);
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 1, 2, 1, 1000, insts, 1) == 1,
          "test14: a positive remainder excludes the outpoint from plain-DGB spending");
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 0, 2, 1, 1000, insts, 1) == 0,
          "test14: an output that is not the last one is not excluded");
    // Unknown must exclude: the spending decision does NOT wait on the quantity
    // being knowable.
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 1, 2, 0, 0, insts, 1) == 1,
          "test14: an unknown remainder still excludes the outpoint");
    insts[0] = inst(0, 1, 0, 0, 50);
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 1, 2, 1, 1000, insts, 1) == 1,
          "test14: a percent instruction still excludes the outpoint");
    // A hostile header is not waved through as "not an asset".
    insts[0] = inst(0, 0, 0, 0, -1);
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 1, 2, 1, 1000, insts, 1) == 1,
          "test14: an unsound header still excludes the outpoint");
    // A confident zero remainder is the only answer that releases the output.
    insts[0] = inst(0, 0, 0, 0, 1000);
    check(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 1, 2, 1, 1000, insts, 1) == 0,
          "test14: a proven-zero remainder releases the outpoint");

    printf(g_fail ? "\nasset_quantity_kat: FAILED (%d)\n" : "\nasset_quantity_kat: OK\n", g_fail);
    return g_fail ? 1 : 0;
}
