/* peer_free_live_thread_kat — BRPeerManagerFree must not free under a running peer thread.
 *
 * THE DEFECT. startSync's recreate path runs, under PEER_GUARD:
 *
 *     BRPeerManagerDisconnect(g_peerManager);   // BOUNDED: gives up after PEER_DISCONNECT_WAIT_SECS
 *     BRPeerManagerFree(g_peerManager);         // freed everything regardless
 *
 * Peer threads are DETACHED and are never joined. When Disconnect's bounded wait gives up (the
 * 2026-08-06 wedge fix made that possible), threads still in dispatch keep their BRPeer and the
 * manager through every remaining callback AND their own teardown: the pong drain locks the
 * peer's pongLock, _peerDisconnected locks manager->lock, _peerThreadCleanup locks it again.
 * Free destroyed all of those mutexes underneath them. Measured on a Galaxy Ultra, v4.0.77,
 * 2026-09-01: two peer threads, two "pthread_mutex_lock called on a destroyed mutex" in the same
 * millisecond, SIGABRT inside _peerThreadRoutine's pong drain (BRPeer.c:1885 -> _BRPeerPongPop),
 * unclean death, tx cache lost.
 *
 * THE FIX. BRPeerManagerFree returns int. If peerThreadCount + dnsThreadCount > 0 it PARKS the
 * manager (freeDeferred=1, no dials, no reconnects) and returns 0; the last thread out --
 * _peerThreadCleanup or the dns routine -- observes the flag under manager->lock and runs the
 * real teardown (_BRPeerManagerFreeNow). Exactly one thread ever gets the 1.
 *
 * WHAT THIS PROVES. A pthread stands in for a live peer thread: it bumps peerThreadCount the way
 * _BRPeerManagerBeginConnect does, signals the main thread, and waits. The main thread calls
 * BRPeerManagerFree while that thread is alive, then releases it; the thread runs the REAL
 * _peerThreadCleanup, which is the last thing every peer thread does.
 *   FIXED:   Free returns 0, cleanup frees the manager, LeakSanitizer (ON for this KAT) is clean
 *            -- which is the proof the deferred free actually fired rather than leaking a parked
 *            manager forever.
 *   UNFIXED (-DPEER_FREE_UNDEFERRED): Free returns 1 and tears down immediately; the cleanup then
 *            locks manager->lock on freed memory -> ASan heap-use-after-free. That is the crash.
 *
 * A second, threadless scenario checks the ordinary path: no threads -> Free returns 1 at once.
 *
 * DETERMINISTIC: the two threads hand off through flags, never through timing.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>

#include "BRPeerManager.c"

static atomic_int g_threadUp;    /* 1 once the fake peer thread has bumped peerThreadCount */
static atomic_int g_freeCalled;  /* 1 once main has called BRPeerManagerFree */

static void spin_until(atomic_int *flag)
{
    struct timespec ts = { 0, 1000000 }; /* 1 ms */
    while (! atomic_load_explicit(flag, memory_order_acquire)) nanosleep(&ts, NULL);
}

static void *fakePeerThread(void *arg)
{
    BRPeerManager *m = arg;
    /* What _BRPeerManagerBeginConnect does before spawning the thread, and what the thread
     * hands to pthread_cleanup_push(_peerThreadCleanup, info). */
    BRPeerCallbackInfo *info = calloc(1, sizeof(*info));
    info->manager = m;

    MGR_LOCK(m);
    m->peerThreadCount++;
    MGR_UNLOCK(m);
    atomic_store_explicit(&g_threadUp, 1, memory_order_release);

    spin_until(&g_freeCalled);

    /* The last thing a real peer thread does. Under the red arm the manager is already gone. */
    _peerThreadCleanup(info);
    return NULL;
}

static BRPeerManager *newManager(BRWallet *wallet)
{
    return BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
}

int main(void)
{
    int fails = 0;
#ifdef PEER_FREE_UNDEFERRED
    printf("ARM: UNFIXED (-DPEER_FREE_UNDEFERRED) — expected heap-use-after-free\n");
#else
    printf("ARM: FIXED (deferred free)\n");
#endif
    fflush(stdout);

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;   /* non-zero: BRBIP32PubKey asserts against all-zero */
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("SKIP: wallet alloc failed\n"); return 0; }

    /* ---- Scenario A: no threads alive -> frees now, returns 1 ---- */
    BRPeerManager *a = newManager(wallet);
    if (! a) { printf("SKIP: manager alloc failed\n"); return 0; }
    int ra = BRPeerManagerFree(a);
    if (ra != 1) { printf("  [FAIL] A: Free with no threads returned %d, expected 1\n", ra); fails++; }
    else printf("  [PASS] A: no threads -> freed immediately (1)\n");

    /* ---- Scenario B: one peer thread alive across the Free ---- */
    BRPeerManager *b = newManager(wallet);
    if (! b) { printf("SKIP: manager alloc failed\n"); return 0; }

    pthread_t th;
    atomic_store(&g_threadUp, 0);
    atomic_store(&g_freeCalled, 0);
    if (pthread_create(&th, NULL, fakePeerThread, b) != 0) { printf("SKIP: pthread_create\n"); return 0; }
    spin_until(&g_threadUp);

    int rb = BRPeerManagerFree(b);      /* thread is alive: must park, not free */
    atomic_store_explicit(&g_freeCalled, 1, memory_order_release);
    pthread_join(th, NULL);              /* runs _peerThreadCleanup -> deferred FreeNow */

    if (rb != 0) {
        printf("  [FAIL] B: Free with a live peer thread returned %d, expected 0 (deferred)\n", rb);
        fails++;
    } else {
        printf("  [PASS] B: live thread -> parked (0); last thread out freed it\n");
    }

    BRWalletFree(wallet);

    if (fails) { printf("peer_free_live_thread_kat: FAIL\n"); return 1; }
    printf("peer_free_live_thread_kat: PASS (LeakSanitizer verdict follows -- a leaked manager "
           "means the deferred free never fired)\n");
    return 0;
}
