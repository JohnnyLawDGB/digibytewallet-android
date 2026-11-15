// The fee rule of legacy_sweep_kat with its two masking rules taken out (DEVMERGE-KAT-NITS): BRLegacySweepFee.h on
// its own, compiled here with the rate floor (step 3) and the 5% refusal (step 5) removed. In the shipped order those
// two decide every sweep before the 1000-satoshi fee floor (step 2) or the dust refusal (step 6) could, so no sweep
// scenario sees either; on this unit the floor sets the fee and the dust refusal decides the verdict, and the
// fee_floor_masked and fee_dust_masked scenarios pin both. run.sh also builds this unit with each comparison arm's
// macro, so BR_LEGACY_SWEEP_RED_FLOOR / _DUST / _DUST_STRICT must fail exactly those scenarios.
#undef BR_LEGACY_SWEEP_RED_RATE_FLOOR
#define BR_LEGACY_SWEEP_RED_RATE_FLOOR 1
#undef BR_LEGACY_SWEEP_RED_RATIO
#define BR_LEGACY_SWEEP_RED_RATIO 1
#include "BRLegacySweepFee.h"

int kat_fee_masked(uint64_t totalIn, size_t inputCount, uint64_t feePerKb, size_t *estSize, uint64_t *fee)
{
    return (int)BRLegacySweepFeeCheck(totalIn, inputCount, feePerKb, estSize, fee);
}
