// The core side of legacy_sweep_kat: BRLegacySweep.h in its own unit, so run.sh can build it with a comparison
// arm's macro (the bridge's unit never sees one) and route its allocations through the recorder in the _main.c
// (-Dcalloc=kat_calloc -Dmalloc=kat_malloc -Dfree=kat_free on this unit only), which looks for key material in
// every block the header frees.
#include <stdlib.h>
#include <string.h>
#include "BRLegacySweep.h"
#include "BRLegacySweep.h"   // the include guard holds

// The signed transaction serialized (malloc'd, the caller frees with kat_real_free) on SIGNED; NULL otherwise.
void kat_real_free(void *p);
void *kat_real_malloc(size_t n);

BRLegacySweepResult kat_core_sweep(const uint8_t *seed, size_t seedLen, const char *hmacKey,
                                   const uint32_t *prefix, size_t prefixLen,
                                   const BRLegacySweepInput *inputs, size_t count,
                                   const char *dest, uint64_t feePerKb, BRLegacySweepFee *fee,
                                   uint8_t **serOut, size_t *serLenOut, BRTransaction **txOut)
{
    BRTransaction *tx = NULL;
    BRLegacySweepResult r = BRLegacySweepBuildAndSign(&tx, seed, seedLen, hmacKey, prefix, prefixLen, inputs, count,
                                                      dest, feePerKb, fee);
    *serOut = NULL;
    *serLenOut = 0;
    if (r == BR_LEGACY_SWEEP_SIGNED && tx) {
        size_t n = BRTransactionSerialize(tx, NULL, 0);
        uint8_t *b = kat_real_malloc(n);
        BRTransactionSerialize(tx, b, n);
        *serOut = b;
        *serLenOut = n;
    }
    if (txOut) *txOut = tx;
    else if (tx) BRTransactionFree(tx);
    return r;
}

// A NULL txOut (BAD_ARGUMENT) and the out-parameter contract.
BRLegacySweepResult kat_core_sweep_null_out(const uint8_t *seed, size_t seedLen)
{
    BRLegacySweepInput in;
    memset(&in, 0, sizeof(in));
    return BRLegacySweepBuildAndSign(NULL, seed, seedLen, "Bitcoin seed", NULL, 0, &in, 1, "x", 0, NULL);
}

int kat_core_from_proof(BRLegacySweepInput *in, const BRRawTxProof *proof, const BRRawTxBindingParent *parents,
                        size_t parentCount)
{
    return BRLegacySweepInputFromProof(in, proof, parents, parentCount);
}
