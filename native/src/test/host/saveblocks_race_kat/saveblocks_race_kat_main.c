// Self-validating red-before-green KAT for the saveBlocks LOCK-RELEASE-THEN-USE
// race (docs/superpowers/plans/2026-07-26-saveblocks-lockrelease-race.md).
//
// It reproduces the DEFECT PATTERN of _peerRelayedBlock's save dispatch, driving
// the REAL BRMerkleBlockSerialize under a 2-thread race:
//   - a "relay" thread grabs a live BRMerkleBlock* under a lock (like saveBlocks[]
//     grabs pointers into manager->blocks), then serializes it;
//   - a "mutator" thread frees + recreates that block under the same lock (like a
//     concurrent reorg's BRSetRemove + BRMerkleBlockFree).
// SAVEBLOCKS_FIXED selects the two code shapes:
//   * 0 (UNFIXED) -> unlock BEFORE serialize: the relay reads a block the mutator
//     can free in the window -> ASan heap-use-after-free (the real bug; the agent's
//     first RED symbolized to BRMerkleBlock.c:194, block->totalTx read).
//   * 1 (FIXED)   -> serialize UNDER the lock: the mutator can't free until the
//     serialize completes -> no UAF, clean exit.
//
// This is a PATTERN gate (self-contained; drives BRMerkleBlockSerialize, not
// _peerRelayedBlock itself) — it proves the defect class is real AND that
// serialize-under-lock closes it. The REAL-code fix (Task 2) is additionally
// gated by the native build + the on-device acceptance (the actual crash gone).
// A faithful #include-BRPeerManager.c TSan harness is a documented follow-up.
//
// BOUNDED (fixed iteration count) so it can NEVER hang; ASan halts at the first
// UAF (ASAN_OPTIONS=halt_on_error). run.sh builds+runs BOTH shapes and requires
// UNFIXED=red AND FIXED=green, so a harness that can't detect the bug fails the KAT.
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "BRMerkleBlock.h"

#ifndef SAVEBLOCKS_FIXED
#define SAVEBLOCKS_FIXED 0
#endif

#define ITERS  2000
#define HASHES 64            // totalTx>0 with malloc'd hashes/flags -> the vulnerable memcpy path

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static BRMerkleBlock  *g_block;      // the shared, concurrently-freed block (guarded by g_lock)
static volatile int    g_stop;

// A full merkle block (totalTx>0) with heap hashes/flags — the only shape whose
// BRMerkleBlockSerialize does the hashes/flags memcpy, and the shape the crash hit.
static BRMerkleBlock *make_block(uint32_t seed)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, (int)(seed & 0xff), sizeof(b->blockHash.u8));
    b->height      = seed;
    b->totalTx     = HASHES;
    b->hashesCount = HASHES;
    b->hashes      = (UInt256 *)calloc(HASHES, sizeof(UInt256));
    b->flagsLen    = 8;
    b->flags       = (uint8_t *)calloc(b->flagsLen, 1);
    return b;
}

static void *relay_thread(void *arg)
{
    (void)arg;
    uint8_t buf[HASHES * sizeof(UInt256) + 256];
    for (int i = 0; i < ITERS && !g_stop; i++) {
        pthread_mutex_lock(&g_lock);
        BRMerkleBlock *b = g_block;          // grab the live pointer under the lock (== saveBlocks[] fill)
#if !SAVEBLOCKS_FIXED
        pthread_mutex_unlock(&g_lock);       // UNFIXED: lock released BEFORE the serialize (BRPeerManager.c:1581)
        usleep(80);                          // widen the unlock->use window: the mutator reliably frees `b` in it
#endif
        if (b) BRMerkleBlockSerialize(b, buf, sizeof buf);  // reads b->totalTx + memcpy b->hashes — UAF if freed
#if SAVEBLOCKS_FIXED
        pthread_mutex_unlock(&g_lock);       // FIXED: serialize happened UNDER the lock; release now
#endif
    }
    return NULL;
}

static void *mutator_thread(void *arg)
{
    (void)arg;
    for (int i = 0; i < ITERS && !g_stop; i++) {
        pthread_mutex_lock(&g_lock);
        if (g_block) BRMerkleBlockFree(g_block);   // free the block a relay may be mid-serialize on (== reorg free)
        g_block = make_block((uint32_t)(i + 1));
        pthread_mutex_unlock(&g_lock);
    }
    return NULL;
}

int main(void)
{
    g_block = make_block(0);
    pthread_t relay, mutator;
    pthread_create(&relay,   NULL, relay_thread,   NULL);
    pthread_create(&mutator, NULL, mutator_thread, NULL);
    pthread_join(relay,   NULL);
    pthread_join(mutator, NULL);
    if (g_block) BRMerkleBlockFree(g_block);
    printf("saveblocks_race_kat: %d iters, SAVEBLOCKS_FIXED=%d, no fault\n", ITERS, SAVEBLOCKS_FIXED);
    return 0;
}
