/* peer_disconnect_bounded_kat — BRPeerManagerDisconnect must not wait forever.
 *
 * THE DEFECT. The wait for peer/dns threads to exit had NO exit condition:
 *
 *     ts.tv_nsec = 1;                          // ONE nanosecond
 *     while (peerThreadCount > 0 || dnsThreadCount > 0) {
 *         nanosleep(&ts, NULL);
 *         MGR_LOCK(manager);  ...counts...  MGR_UNLOCK(manager);
 *     }
 *
 * Two problems compounding. It never gives up, and at 1 ns it is a busy-wait that re-acquires
 * manager->lock thousands of times a second — starving the very threads whose exit it waits for.
 *
 * WHY IT IS FATAL rather than merely slow: startSync calls BRPeerManagerDisconnect (then
 * BRPeerManagerFree) while holding PEER_GUARD, the global JNI mutex every bridge entry point
 * needs. So a peer thread that never decrements peerThreadCount wedges the ENTIRE wallet.
 * Measured on a Note 8, 2026-08-06:
 *
 *     07:57:45  startSync: recreating peer manager
 *     08:02:53  PEER_GUARD=308.7s startSync:762      <- climbing, never released
 *
 * ...while peer threads were still relaying blocks five minutes in, so the count was never going
 * to reach zero. 93 of 103 threads queued behind the guard; the CF ledger never persisted, so the
 * next launch abandoned 333,701 blocks from the birth height instead of resuming.
 *
 * WHAT THIS PROVES. Pin peerThreadCount at a value nothing will ever decrement — precisely the
 * on-device condition — and call BRPeerManagerDisconnect.
 *   FIXED:   returns after ~PEER_DISCONNECT_WAIT_SECS and logs that it gave up.
 *   UNFIXED (-DDISCONNECT_WAIT_UNBOUNDED): never returns; the runner's timeout is the red signal.
 *
 * DETERMINISTIC: no threads, no races. The count simply never moves, because nothing exists to
 * move it.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "BRPeerManager.c"

static double nowMs(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (double)tv.tv_sec * 1000.0 + (double)tv.tv_usec / 1000.0;
}

int main(void)
{
#ifdef DISCONNECT_WAIT_UNBOUNDED
    printf("ARM: UNFIXED (-DDISCONNECT_WAIT_UNBOUNDED) — expected to HANG\n");
#else
    printf("ARM: FIXED (bounded at %us)\n", (unsigned)PEER_DISCONNECT_WAIT_SECS);
#endif
    fflush(stdout);

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;   /* non-zero: BRBIP32PubKey asserts against all-zero */
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("SKIP: wallet alloc failed\n"); return 0; }

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) { printf("SKIP: manager alloc failed\n"); return 0; }

    /* THE ON-DEVICE CONDITION: a peer thread that never exits. No real thread is needed — what
     * matters is that the counter never reaches zero, which is exactly what was observed while
     * peers carried on relaying blocks through a teardown meant to stop them. */
    m->peerThreadCount = 1;

    printf("peerThreadCount pinned at 1 (nothing will ever decrement it)\n");
    fflush(stdout);

    double t0 = nowMs();
    BRPeerManagerDisconnect(m);
    double elapsed = (nowMs() - t0) / 1000.0;

    printf("BRPeerManagerDisconnect returned after %.1fs\n", elapsed);

    /* Upper bound generous for a loaded CI box; the point is that it RETURNS. */
    double limit = (double)PEER_DISCONNECT_WAIT_SECS * 3.0;
    if (elapsed > limit) {
        printf("  [FAIL] took %.1fs, expected to give up near %us\n",
               elapsed, (unsigned)PEER_DISCONNECT_WAIT_SECS);
        printf("peer_disconnect_bounded_kat: FAIL\n");
        return 1;
    }

    /* And it must actually have waited — an instant return would mean the loop never ran and the
     * gate proves nothing about the bound. */
    if (elapsed < (double)PEER_DISCONNECT_WAIT_SECS * 0.5) {
        printf("  [FAIL] returned in %.1fs without waiting — the wait loop did not execute, so "
               "this run says nothing about the deadline\n", elapsed);
        printf("peer_disconnect_bounded_kat: FAIL\n");
        return 1;
    }

    printf("  [PASS] bounded: gave up rather than holding the guard forever\n");
    printf("peer_disconnect_bounded_kat: PASS\n");
    return 0;
}
