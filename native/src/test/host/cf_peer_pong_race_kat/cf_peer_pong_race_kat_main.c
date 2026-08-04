/* Host KAT: the pong-callback queue must survive concurrent push and drain.
 *
 * THE DEFECT (Note 8, 2026-08-03 19:45:16 CDT, SIGSEGV at BRPeer.c:1487).
 * BRPeerContext::pongInfo / ::pongCallback are BRArray buffers. Two properties make them
 * hostile to unsynchronized sharing:
 *   1. array_add() grows via array_set_capacity() -> realloc(), so the BASE POINTER MOVES
 *      and the old block is freed.
 *   2. array_count(a) is ((size_t *)(a))[-1] -- a header immediately before the data -- and
 *      array_rm()'s shift loop RE-READS IT ON EVERY ITERATION:
 *          array_count(array)--;
 *          while (_array_i < array_count(array)) (array)[_array_i] = (array)[_array_i + 1], ...
 *
 * They were appended from the manager/keepalive thread (BRPeerSendPing /
 * BRPeerSendPingProbe, e.g. BRPeerManagerKeepAlive at BRPeerManager.c:4409) and drained from
 * the peer thread (_BRPeerAcceptPongMessage, and the disconnect teardown loop at :1487) with
 * no mutex -- only a `volatile` qualifier, which orders nothing and makes nothing atomic.
 *
 * So a push could realloc the buffer out from under a concurrent shift loop. The tombstone
 * shows the consequence: x9/x10 held ~235,708 (a bogus count read from freed memory) and the
 * loop walked forward until it hit an unmapped page -- fault addr 0x74be200000, exactly
 * page-aligned, with x8 = fault-8 (the last successful 8-byte copy). The same corruption
 * accounts for the three allocator-side deaths the same day (ifree, je_tcache_bin_flush_small,
 * __fortify_fatal): sometimes you clobber a neighbouring allocation and jemalloc dies later.
 *
 * WHAT THIS PROVES. Two threads hammer the queue -- one pushing, one popping -- for a fixed
 * number of operations, under ASan. FIXED: every pop returns a callback/info pair that was
 * actually pushed, the queue drains to empty, and ASan is silent. UNFIXED
 * (-DPONG_LOCK_UNFIXED): ASan reports heap-use-after-free / heap-buffer-overflow, or the
 * pairing check fails, or it crashes outright.
 *
 * WHY THE PAIRING CHECK EXISTS ALONGSIDE ASan. A torn read is not always an invalid access:
 * the two arrays are indexed in lockstep by every reader, so a drain that saw pongCallback
 * grown but pongInfo not yet would hand back a MISMATCHED pair while touching only live
 * memory. Each pushed item therefore carries a self-describing token whose callback and info
 * must agree -- that catches the silent-corruption arm ASan cannot see.
 *
 * This file #includes BRPeer.c directly (the same amalgamation idiom BRKey.c uses for
 * secp256k1) to reach the file-static _BRPeerPongPush/_BRPeerPongPop and the opaque
 * BRPeerContext. BRPeer.c must therefore NOT also be passed as a separate compilation unit.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include "BRPeer.c"

#ifndef KAT_OPS
#define KAT_OPS 200000
#endif

/* Each queued entry is (cb, info) where info encodes the same sequence number the callback
 * slot does. A mismatch means the two arrays were observed out of step. */
static void _katCb0(void *info, int success) { (void)info; (void)success; }
static void _katCb1(void *info, int success) { (void)info; (void)success; }
static void _katCb2(void *info, int success) { (void)info; (void)success; }
static void _katCb3(void *info, int success) { (void)info; (void)success; }

static void (*const KAT_CBS[4])(void *, int) = { _katCb0, _katCb1, _katCb2, _katCb3 };

static BRPeerContext *g_ctx;
static volatile int   g_pushDone;
static long           g_popped;
static long           g_mismatched;

static void *_pusher(void *arg)
{
    (void)arg;

    for (long i = 0; i < KAT_OPS; i++) {
        /* info carries the sequence number; the callback slot carries i % 4. Any pop that
         * returns a pair where they disagree saw the two arrays out of step. */
        _BRPeerPongPush(g_ctx, (void *)(uintptr_t)i, KAT_CBS[i % 4]);
    }

    g_pushDone = 1;
    return NULL;
}

static void *_popper(void *arg)
{
    (void)arg;

    for (;;) {
        void (*cb)(void *, int) = NULL;
        void *info = NULL;

        if (_BRPeerPongPop(g_ctx, &cb, &info)) {
            long seq = (long)(uintptr_t)info;

            g_popped++;
            if (cb != KAT_CBS[seq % 4]) g_mismatched++;
        }
        else if (g_pushDone) {
            break;
        }
    }

    return NULL;
}

int main(void)
{
    pthread_t push, pop;
    BRPeer *peer = BRPeerNew(0xd9b4bef9);
    int failures = 0;

    g_ctx = (BRPeerContext *)peer;

#ifdef PONG_LOCK_UNFIXED
    printf("ARM: UNFIXED (-DPONG_LOCK_UNFIXED) -- the lock is compiled out\n");
#else
    printf("ARM: FIXED\n");
#endif
    printf("ops=%d\n", KAT_OPS);

    if (pthread_create(&push, NULL, _pusher, NULL) != 0) { printf("pthread_create failed\n"); return 2; }
    if (pthread_create(&pop, NULL, _popper, NULL) != 0) { printf("pthread_create failed\n"); return 2; }
    pthread_join(push, NULL);
    pthread_join(pop, NULL);

    printf("popped=%ld mismatched=%ld residual=%zu\n",
           g_popped, g_mismatched, array_count(g_ctx->pongCallback));

    /* Every push must come back out exactly once. */
    if (g_popped != KAT_OPS) {
        printf("ASSERTION FAILED: pong queue lost or duplicated entries "
               "(popped %ld, pushed %d)\n", g_popped, KAT_OPS);
        failures++;
    }

    /* ...as a coherent (callback, info) pair. */
    if (g_mismatched != 0) {
        printf("ASSERTION FAILED: pong queue returned %ld torn (callback, info) pairs\n",
               g_mismatched);
        failures++;
    }

    /* ...leaving nothing behind. */
    if (array_count(g_ctx->pongCallback) != 0 || array_count(g_ctx->pongInfo) != 0) {
        printf("ASSERTION FAILED: pong queue did not drain "
               "(cb=%zu info=%zu)\n",
               array_count(g_ctx->pongCallback), array_count(g_ctx->pongInfo));
        failures++;
    }

    BRPeerFree(peer);

    if (failures) {
        printf("cf_peer_pong_race_kat: FAIL (%d)\n", failures);
        return 1;
    }

    printf("cf_peer_pong_race_kat: PASS\n");
    return 0;
}
