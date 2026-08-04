/* Host KAT: a peer's known-tx set must survive concurrent add and probe.
 *
 * THE DEFECT. BRPeerContext::knownTxHashes is a BRArray and ::knownTxHashSet is a BRSet whose
 * entries are INTERIOR POINTERS into that array. So any array_add() that reallocates invalidates
 * every entry in the set -- the two are only meaningful as a unit, and the old code guarded
 * neither.
 *
 * Worse, the old _BRPeerAddKnownTxHashes actively made it unsafe:
 *
 *     UInt256 *knownTxHashes = ctx->knownTxHashes;          // snapshot on entry
 *     ...
 *     array_add(knownTxHashes, txHashes[i]);                // realloc frees the snapshot
 *     if (ctx->knownTxHashes != knownTxHashes) {            // "did it move?"
 *         ctx->knownTxHashes = knownTxHashes;               // STALE POINTER written back
 *
 * Single-threaded that move-detection is correct. Concurrently it is worse than nothing: another
 * thread's realloc frees the snapshot, this thread stores the freed pointer back into the
 * context, and then rebuilds knownTxHashSet out of interior pointers into freed memory. Every
 * later BRSetContains dereferences them.
 *
 * REACHABILITY -- why this is hotter than the pong race. _BRPeerManagerLoadMempools
 * (BRPeerManager.c:803) iterates EVERY connected peer and mutates their contexts from ONE peer's
 * thread; BRPeerManagerPublishTx does the same from the Kotlin/JNI broadcast thread. Meanwhile
 * each peer's own read thread walks the same pair in _BRPeerAcceptInvMessage, which never takes
 * manager->lock. And knownTxHashes is never trimmed (contrast knownBlockHashes), so it grows
 * without bound and keeps hitting realloc.
 *
 * WHAT THIS PROVES. One thread plays the manager/JNI side (add) while another plays the peer's
 * inv handler (probe, then add). FIXED: every distinct hash ends up present exactly once, the
 * array and the set agree on their size, and ASan is silent. UNFIXED (-DTXHASH_LOCK_UNFIXED):
 * ASan reports heap-use-after-free / heap-buffer-overflow, or the invariants below break.
 *
 * WHY THE SIZE-AGREEMENT ASSERTION EXISTS ALONGSIDE ASan. The stale-pointer write-back can leave
 * the array and the set describing DIFFERENT memory while both are still mapped -- a silent
 * divergence ASan cannot see. array_count == BRSetCount is the cheapest thing that catches it.
 *
 * #includes BRPeer.c directly to reach the file-static helpers and the opaque BRPeerContext, so
 * BRPeer.c must NOT also be passed as a separate compilation unit.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include "BRPeer.c"

#ifndef KAT_HASHES
#define KAT_HASHES 20000
#endif

static BRPeerContext *g_ctx;
static UInt256       *g_pool;
static volatile int   g_addDone;
static long           g_probes;

/* Deterministic distinct hashes -- no Math.random equivalent needed, and a fixed pool means the
 * final invariants are exact rather than statistical. */
static void _fillPool(void)
{
    g_pool = calloc(KAT_HASHES, sizeof(*g_pool));
    for (long i = 0; i < KAT_HASHES; i++) {
        for (int b = 0; b < 8; b++) g_pool[i].u8[b] = (uint8_t)((i >> (b * 8)) & 0xff);
        g_pool[i].u8[31] = 0xA5;
    }
}

/* The manager / JNI-broadcast side: walks the pool forwards, adding in small batches the way
 * BRPeerSendInv and BRPeerSendMempool do. */
static void *_adder(void *arg)
{
    (void)arg;

    for (long i = 0; i < KAT_HASHES; i += 4) {
        size_t n = (KAT_HASHES - i) < 4 ? (size_t)(KAT_HASHES - i) : 4;
        _BRPeerAddKnownTxHashes(&g_ctx->peer, &g_pool[i], n);
    }

    g_addDone = 1;
    return NULL;
}

/* The peer read-thread side: probes membership (the BRSetContains at BRPeer.c:601) and then adds
 * the ones it did not know (the _BRPeerAddKnownTxHashes at :607) -- walking BACKWARDS so the two
 * threads collide across the whole range rather than trailing each other. */
static void *_prober(void *arg)
{
    (void)arg;

    while (! g_addDone) {
        for (long i = KAT_HASHES - 1; i >= 0; i--) {
            if (! _BRPeerKnowsTxHash(g_ctx, &g_pool[i])) {
                _BRPeerAddKnownTxHashes(&g_ctx->peer, &g_pool[i], 1);
            }
            g_probes++;
        }
    }

    return NULL;
}

int main(void)
{
    pthread_t add, probe;
    BRPeer *peer = BRPeerNew(0xd9b4bef9);
    int failures = 0;

    g_ctx = (BRPeerContext *)peer;
    _fillPool();

#ifdef TXHASH_LOCK_UNFIXED
    printf("ARM: UNFIXED (-DTXHASH_LOCK_UNFIXED) -- the lock is compiled out\n");
#else
    printf("ARM: FIXED\n");
#endif
    printf("hashes=%d\n", KAT_HASHES);

    if (pthread_create(&add, NULL, _adder, NULL) != 0)   { printf("pthread_create failed\n"); return 2; }
    if (pthread_create(&probe, NULL, _prober, NULL) != 0) { printf("pthread_create failed\n"); return 2; }
    pthread_join(add, NULL);
    pthread_join(probe, NULL);

    size_t arrCount = array_count(g_ctx->knownTxHashes);
    size_t setCount = BRSetCount(g_ctx->knownTxHashSet);

    printf("probes=%ld array=%zu set=%zu\n", g_probes, arrCount, setCount);

    /* The array and the set describe the same thing; they cannot disagree. */
    if (arrCount != setCount) {
        printf("ASSERTION FAILED: array and set disagree (%zu vs %zu) -- "
               "the two views diverged\n", arrCount, setCount);
        failures++;
    }

    /* Both threads draw from the same fixed pool, and every add is dedup'd against the set, so
     * exactly KAT_HASHES distinct entries must exist -- no duplicates, none lost. */
    if (arrCount != (size_t)KAT_HASHES) {
        printf("ASSERTION FAILED: expected %d distinct hashes, got %zu "
               "(dedup lost or duplicated entries)\n", KAT_HASHES, arrCount);
        failures++;
    }

    /* And every one of them must still be findable -- which dereferences every interior pointer
     * the set holds, so a stale rebuild shows up here. */
    long missing = 0;
    for (long i = 0; i < KAT_HASHES; i++) {
        if (! BRSetContains(g_ctx->knownTxHashSet, &g_pool[i])) missing++;
    }
    if (missing != 0) {
        printf("ASSERTION FAILED: %ld of %d hashes are no longer in the set\n", missing, KAT_HASHES);
        failures++;
    }

    BRPeerFree(peer);
    free(g_pool);

    if (failures) {
        printf("cf_peer_txhash_race_kat: FAIL (%d)\n", failures);
        return 1;
    }

    printf("cf_peer_txhash_race_kat: PASS\n");
    return 0;
}
