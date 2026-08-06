/* checkpoint_alias_uaf_kat — pruning must never free a block manager->checkpoints still holds.
 *
 * THE DEFECT. BRPeerManagerNewEx puts the SAME BRMerkleBlock * into two sets:
 *
 *     block = BRMerkleBlockNew();
 *     ...
 *     BRSetAdd(manager->checkpoints, block);
 *     BRSetAdd(manager->blocks, block);        // same object, two containers
 *
 * Only manager->blocks governs its lifetime: _BRPeerManagerClearMemory removes from `blocks` and
 * calls BRMerkleBlockFree, without consulting `checkpoints`. The comment above that function
 * records the assumption that made this safe once —
 *
 *     "checkpoints will remain in the blocks-Set, until we are ahead of them."
 *
 * — which held while pruning trailed the chain TIP. It is false under CF-era pruning, where the
 * retention floor is anchored to the compact-filter SCAN frontier: once the scan advances past a
 * checkpoint height, that checkpoint block is pruned and freed and manager->checkpoints is left
 * holding a dangling pointer. The next relayed header runs
 * BRSetGet(manager->checkpoints, block) and _BRBlockHeightEq dereferences ->height on freed
 * memory.
 *
 * Confirmed on-device by ASan, 2026-08-06, on a FRESH install within three minutes of first sync:
 *     _peerThreadRoutine -> _BRPeerAcceptMessage -> _BRPeerAcceptHeadersMessage
 *     -> _peerRelayedBlock -> _BRPeerManagerVerifyBlock (BRPeerManager.c:2114)
 *     -> BRSetGet -> _BRBlockHeightEq (BRPeerManager.c:227)     heap-use-after-free
 *
 * This is NOT restore-specific. It fires from ordinary header processing for every user.
 *
 * WHAT THIS PROVES. Build a manager whose real checkpoint blocks sit below the CF scan floor, so
 * pruning is entitled to remove them, then prune and walk manager->checkpoints exactly the way
 * _BRPeerManagerVerifyBlock does.
 *   FIXED:   every checkpoint entry is still readable; heights match what was inserted.
 *   UNFIXED: ASan reports heap-use-after-free in _BRBlockHeightEq — the on-device stack, reproduced
 *            deterministically on the host.
 *
 * DETERMINISTIC: no threads, no timing. It fails 100% of the time when unfixed.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int cond, const char *what) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail = 1;
}

int main(void)
{
#ifdef CHECKPOINT_ALIAS_UNFIXED
    printf("ARM: UNFIXED (-DCHECKPOINT_ALIAS_UNFIXED)\n");
#else
    printf("ARM: FIXED\n");
#endif

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    /* Non-zero fingerprint: BRBIP32PubKey asserts the key is not BR_MASTER_PUBKEY_NONE, so an
     * all-zero mpk aborts in wallet setup — which the gate correctly rejects as "died in setup,
     * not a red arm" rather than mistaking it for the defect. */
    mpk.fingerPrint = 0x11223344;
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("SKIP: wallet alloc failed\n"); return 0; }

    /* A real manager, so manager->checkpoints is populated by the production path — the aliasing
     * under test is created by BRPeerManagerNewEx itself, not by the harness. */
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) { printf("SKIP: manager alloc failed\n"); return 0; }
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    size_t cpCount = BRSetCount(m->checkpoints);
    printf("checkpoints=%zu resident-blocks=%zu\n", cpCount, BRSetCount(m->blocks));
    if (cpCount == 0) { printf("SKIP: no checkpoints for this chain\n"); return 0; }

    /* Record every checkpoint pointer and the height it should still report, BEFORE pruning. */
    BRMerkleBlock **cps = calloc(cpCount, sizeof(*cps));
    uint32_t *heights = calloc(cpCount, sizeof(*heights));
    BRSetAll(m->checkpoints, (const void **)cps, cpCount);
    for (size_t i = 0; i < cpCount; i++) heights[i] = cps[i]->height;

    /* Drive the resident set far above every checkpoint and anchor the CF scan there too, so the
     * scan-anchored floor is ABOVE the checkpoint heights and pruning is entitled to free them.
     * That is exactly the on-device regime: the scan frontier had advanced past them. */
    uint32_t top = 0;
    BRMerkleBlock *topCp = NULL;
    for (size_t i = 0; i < cpCount; i++) {
        if (heights[i] > top) { top = heights[i]; topCp = cps[i]; }
    }
    uint32_t base = top + 1, tip = base + CLEAR_MEM_BLOCKS_COUNT_TRIGGER + 1024;

    /* THE CHAIN MUST CONNECT TO THE CHECKPOINTS. _BRPeerManagerClearMemory descends by prevBlock
     * from lastBlock; if the synthetic run does not link into the real checkpoint blocks the
     * descent simply stops at the join and never reaches them — which is what happened on the
     * first attempt here: the red arm PASSED because nothing was ever freed. So the lowest
     * synthetic block's prevBlock is the top checkpoint's actual blockHash. */
    BRMerkleBlock *last = NULL;
    for (uint32_t h = base; h <= tip; h++) {
        BRMerkleBlock *b = BRMerkleBlockNew();
        b->height = h;
        b->blockHash = UINT256_ZERO;
        b->blockHash.u32[0] = h;           /* distinct hash per height */
        if (h == base) {
            b->prevBlock = topCp->blockHash;   /* join the real checkpoint chain */
        } else {
            b->prevBlock = UINT256_ZERO;
            b->prevBlock.u32[0] = h - 1;
        }
        BRSetAdd(m->blocks, b);
        last = b;
    }
    m->lastBlock = last;

    /* Anchor the CF chain and the scan ledger well ABOVE every checkpoint height, so the
     * scan-anchored retention floor sits above them and pruning is entitled to free them. That is
     * the on-device regime: the scan frontier had advanced past the checkpoints. */
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, tip - 512, UINT256_ZERO);
    BRCFScanLedgerInit(&m->cfLedger, tip - 512);

    _BRPeerManagerClearMemory(m);

    printf("after prune: resident-blocks=%zu checkpoints=%zu\n",
           BRSetCount(m->blocks), BRSetCount(m->checkpoints));

    /* THE READ THAT CRASHES ON DEVICE. _BRPeerManagerVerifyBlock does exactly this on every
     * relayed header: a BRSetGet against manager->checkpoints, which invokes _BRBlockHeightEq on
     * the stored entries. If pruning freed one, this dereferences freed memory. */
    int mismatched = 0;
    for (size_t i = 0; i < cpCount; i++) {
        BRMerkleBlock probe;
        memset(&probe, 0, sizeof(probe));
        probe.height = heights[i];
        BRMerkleBlock *found = BRSetGet(m->checkpoints, &probe);   /* -> _BRBlockHeightEq */
        if (! found || found->height != heights[i]) mismatched++;
    }

    check(mismatched == 0, "every checkpoint still readable after a prune past its height");
    check(BRSetCount(m->checkpoints) == cpCount, "checkpoint set was not emptied by pruning");

    free(cps);
    free(heights);

    if (g_fail) { printf("checkpoint_alias_uaf_kat: FAIL\n"); return 1; }
    printf("checkpoint_alias_uaf_kat: PASS\n");
    return 0;
}
