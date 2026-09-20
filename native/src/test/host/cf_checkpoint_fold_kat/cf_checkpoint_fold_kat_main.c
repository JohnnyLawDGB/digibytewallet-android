// cf_checkpoint_fold_kat — every pinned filter-header checkpoint is a filter header.
//
// INVARIANT. BIP157 defines the filter header at height h as
//     dSHA256( filterHash(h) || filterHeader(h-1) ).
// A row of BRMainNetCFCheckpoints is only meaningful if it equals that fold for the
// real chain, because the wallet compares each row against the very same fold computed
// from a relayed cfheaders batch (BRCompactFilterChainBatchViolatesCheckpoint). A row
// holding any other 32 bytes can never equal it.
//
// The vectors carry the two fold inputs for every pinned height, taken from a full
// node (gen_vectors.py). Each one is pushed through the wallet's own comparator against
// the REAL table — no synthetic checkpoints, no seam — so this proves the shipped data,
// not just the code that reads it.
//
// Coverage is explicit: a pinned row with no vector fails if it sits at or below the
// newest vector (a hole), and is counted out loud if it sits above (rows added by a
// later regeneration, which the generator proves by the same fold before writing).
#include <stdio.h>
#include <stdint.h>
#include "BRInt.h"
#include "BRCompactFilterChain.h"
#include "BRCompactFilterCheckpoints.h"
#include "cf_checkpoint_fold_vectors.h"

static int failures = 0;

int main(void) {
    size_t nv = sizeof(kFoldVectors) / sizeof(kFoldVectors[0]);
    uint32_t topVector = nv ? kFoldVectors[nv - 1].height : 0;
    size_t folded = 0;

    for (size_t i = 0; i < nv; i++) {
        uint32_t h = kFoldVectors[i].height;
        const BRCFCheckpoint *hit[1];
        if (BRCFCheckpointsInRange(h, h, hit, 1) != 1) {
            printf("FAIL: height %u has a vector but no pinned row\n", h);
            failures++;
            continue;
        }
        // A chain whose next height is h and whose tip header is filterHeader(h-1):
        // exactly the state the wallet is in when a batch reaches a pinned height.
        BRCompactFilterChain *chain = BRCompactFilterChainNew(0, h, kFoldVectors[i].prevHeader);
        uint32_t vh = 0; UInt256 vc = UINT256_ZERO;
        if (BRCompactFilterChainBatchViolatesCheckpoint(chain, &kFoldVectors[i].filterHash, 1, &vh, &vc)) {
            printf("FAIL: height %u: pinned row is not the fold of the chain's own data\n"
                   "        folded %s\n        pinned %s\n",
                   h, u256_hex_encode(vc), u256_hex_encode(hit[0]->filterHeader));
            failures++;
        } else {
            folded++;
        }
        BRCompactFilterChainFree(chain);
    }

    size_t above = 0;
    for (size_t c = 0; c < BRMainNetCFCheckpointsCount; c++) {
        uint32_t h = BRMainNetCFCheckpoints[c].height;
        if (h > topVector) { above++; continue; }
        int have = 0;
        for (size_t i = 0; i < nv && !have; i++) have = (kFoldVectors[i].height == h);
        if (!have) { printf("FAIL: pinned height %u has no vector\n", h); failures++; }
    }

    printf("pinned rows: %zu   vectors: %zu   folded to the pinned value: %zu\n",
           BRMainNetCFCheckpointsCount, nv, folded);
    if (above) printf("note: %zu pinned row(s) above the newest vector (%u) are not covered here — "
                      "rerun gen_vectors.py\n", above, topVector);
    if (nv == 0) { printf("FAIL: no vectors\n"); failures++; }
    if (failures) { printf("\n%d FAILURE(S)\n", failures); return 1; }
    printf("cf_checkpoint_fold_kat: ALL PASS\n");
    return 0;
}
