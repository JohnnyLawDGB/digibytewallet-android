/* publish_cancel_survivor_kat — one peer's timeout must not cancel a publish that
 * other live peers are still holding.
 *
 * THE DEFECT. BRPeerManagerPublishTx announces a send to EVERY connected peer at once
 * (BRPeerManager.c, the `if (peer != manager->downloadPeer || count == 1)` loop), but
 * BRPublishedTx is `{ tx, info, callback }` — it records NO peer. So when _peerDisconnected
 * decides a timeout was a publish timeout, it cannot ask "was this publish sent to the peer
 * that just died?" It cancels EVERY pending publish and BRTransactionFree()s each one, while
 * the other peers that received the same inv are still connected and may still send getdata.
 *
 * v4.0.47 removed the loudest version of this — a peer that never completed a handshake, and
 * was therefore never sent an inv, could cancel a publish sitting on a healthy peer. Measured
 * on an S25 Ultra, 2026-08-23:
 *
 *   04:55:47.678  64.20.49.248  sending inv                       <- published to a HEALTHY peer
 *   04:55:49.685  192.42.116.14 CONNECT_FAIL err=110 handshake=0
 *   04:55:49.685  192.42.116.14 transaction canceled: Connection timed out
 *   04:55:51.855  64.20.49.248  got getdata with 1 item(s)        <- 2s too late, tx freed
 *
 * What v4.0.47 left behind is the same defect with a narrower trigger: a peer we genuinely DID
 * publish to, going quiet, still cancels every OTHER peer's copy of the send.
 *
 * THE RULE THIS PINS. A publish is dead only when every peer that could carry it is gone.
 * At disconnect time that is: are there other connected, handshook peers left?
 *
 *   >= 1 survivor : leave the publish pending. The 90s stranded-send sweep re-broadcasts it
 *                   (SyncService), and Kotlin reads -1/pending — never a false success.
 *   0  survivors  : cancel, exactly as before. There is genuinely nowhere left for it to go.
 *
 * The asymmetry is deliberate. Under-cancelling costs a delayed retry; over-cancelling frees a
 * transaction the network was about to ask for, which is the bug being fixed.
 *
 * HANDSHAKE IS LOAD-BEARING IN BOTH DIRECTIONS. A peer that never handshook was never sent an
 * inv, so it cannot be a survivor either — counting it would keep a genuinely dead publish
 * pending forever. test_only_unhandshook_peers_left pins that direction.
 *
 * DETERMINISTIC — no sockets, no threads, no timing. Synthetic BRPeerNew() peers with their
 * private status/gotVerack forced, driven straight into _peerDisconnected.
 */

#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>

/* Included, not linked: reaching BRPeerContext (there is no public setter for connect status
 * or gotVerack) and BRPeerManagerStruct requires both .c files in one translation unit. Both
 * define a file-static _dummyThreadCleanup, so BRPeer.c's copy is renamed by a preprocessor
 * substitution scoped to its #include only — no production source is modified. Same pattern as
 * cf_checkpoint_quorum_kat. run.sh drops both from the link line accordingly. */
#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

#include "BRPeerManager.c"

static int g_fail = 0;

static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

/* ---- the publish callback under observation ---------------------------------------- */

static int g_cbCount = 0;
static int g_cbError = 0;
static int g_cbSentinel = 0xCAFE;

static void recordPublishResult(void *info, int error)
{
    if (info != &g_cbSentinel) {
        printf("   FAIL: callback fired with the wrong info pointer\n");
        g_fail++;
    }
    g_cbCount++;
    g_cbError = error;
}

/* ---- fixtures ------------------------------------------------------------------------ */

static BRWallet *makeWallet(void)
{
    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;   /* non-zero: BRBIP32PubKey asserts against all-zero */
    return BRWalletNew(NULL, 0, mpk);
}

/* A synthetic connected peer. `handshook` decides whether it ever got a verack — i.e. whether
 * it was ever in a position to be sent a publish inv at all. */
static BRPeer *addPeer(BRPeerManager *manager, uint8_t addrByte, int handshook)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = addrByte;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = handshook ? 1 : 0;
    array_add(manager->connectedPeers, p);
    return p;
}

/* A manager holding exactly one pending publish with a live callback, and `connected` peers.
 * The first peer returned is the one the tests kill. */
static BRPeerManager *makeManagerWithPendingPublish(BRWallet *wallet, BRTransaction **txOut)
{
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) return NULL;

    /* A real wallet-shaped tx is unnecessary: the publish list only requires an unconfirmed
     * transaction, and zero inputs keeps _BRPeerManagerAddTxToPublishList's parent recursion
     * out of the picture. A distinctive hash makes post-disconnect identity checkable. */
    BRTransaction *tx = BRTransactionNew();
    memset(&tx->txHash, 0xA5, sizeof(tx->txHash));
    _BRPeerManagerAddTxToPublishList(m, tx, &g_cbSentinel, recordPublishResult);

    /* isConnected == 1 keeps the "sync failed -> ENOTCONN" branch out of the way; that branch
     * cancels regardless and is NOT what this gate measures. connectFailureCount at the ceiling
     * suppresses the reconnect attempt at the tail of _peerDisconnected, which would otherwise
     * spawn DNS threads and make the run non-deterministic. */
    m->isConnected = 1;
    m->connectFailureCount = MAX_CONNECT_FAILURES;
    m->downloadPeer = NULL;

    if (txOut) *txOut = tx;
    return m;
}

static void killPeerWithTimeout(BRPeerManager *m, BRPeer *peer)
{
    BRPeerCallbackInfo info;
    memset(&info, 0, sizeof(info));
    info.peer = peer;
    info.manager = m;
    _peerDisconnected(&info, ETIMEDOUT);   /* _peerDisconnected does not free info */
}

/* ---- tests --------------------------------------------------------------------------- */

/* THE BUG. Two handshook peers both hold the inv; one times out. The other is still there and
 * may still ask for the transaction. */
static void test_survivor_keeps_publish_alive(void)
{
    printf("\n-- a publish survives one peer's timeout while another live peer holds it --\n");
    BRWallet *wallet = makeWallet();
    BRTransaction *tx = NULL;
    BRPeerManager *m = makeManagerWithPendingPublish(wallet, &tx);
    if (! m) { printf("   SKIP: manager alloc failed\n"); return; }

    BRPeer *dying    = addPeer(m, 0x01, 1);
    BRPeer *survivor = addPeer(m, 0x02, 1);
    (void)survivor;

    g_cbCount = 0; g_cbError = 0;
    killPeerWithTimeout(m, dying);

    check(g_cbCount == 0,
          "the publish callback did NOT fire — one peer's timeout is not the send's verdict");
    check(array_count(m->publishedTx) == 1,
          "the publish is still pending, not removed from the list");

    /* Reading the transaction back is itself the assertion: if the disconnect had freed it,
     * ASan would abort here on a use-after-free. Guarded on the count above so the red arm,
     * where the entry IS removed, never performs this read. */
    if (array_count(m->publishedTx) == 1) {
        check(m->publishedTx[0].tx == tx && m->publishedTx[0].tx->txHash.u8[0] == 0xA5,
              "the transaction object is intact — not freed out from under the live peer");
        check(m->publishedTx[0].callback == recordPublishResult,
              "the callback is still armed for the real verdict");
    }
}

/* THE OTHER DIRECTION. Nothing left to carry it — cancelling is correct, and must still happen.
 * A fix that simply stopped cancelling would pass the test above and strand sends forever. */
static void test_last_peer_still_cancels(void)
{
    printf("\n-- the LAST peer's timeout still cancels: nowhere left for the send to go --\n");
    BRWallet *wallet = makeWallet();
    BRTransaction *tx = NULL;
    BRPeerManager *m = makeManagerWithPendingPublish(wallet, &tx);
    if (! m) { printf("   SKIP: manager alloc failed\n"); return; }

    BRPeer *only = addPeer(m, 0x03, 1);

    g_cbCount = 0; g_cbError = 0;
    killPeerWithTimeout(m, only);

    check(g_cbCount == 1, "the publish callback fired exactly once");
    check(g_cbError == ETIMEDOUT, "and reported ETIMEDOUT, the real cause");
    check(array_count(m->publishedTx) == 0, "the publish was removed from the pending list");
}

/* A peer that never handshook was never sent an inv, so it cannot keep a publish alive either.
 * Without this, the survivor count would be "any peer in connectedPeers" and a send with only
 * half-open connections left would hang pending forever instead of reporting its failure. */
static void test_only_unhandshook_peers_left(void)
{
    printf("\n-- an un-handshook peer is not a survivor: it was never sent the inv --\n");
    BRWallet *wallet = makeWallet();
    BRTransaction *tx = NULL;
    BRPeerManager *m = makeManagerWithPendingPublish(wallet, &tx);
    if (! m) { printf("   SKIP: manager alloc failed\n"); return; }

    BRPeer *dying = addPeer(m, 0x04, 1);
    addPeer(m, 0x05, 0);   /* connected socket, no verack — never received a publish inv */

    g_cbCount = 0; g_cbError = 0;
    killPeerWithTimeout(m, dying);

    check(g_cbCount == 1, "the publish callback fired — the half-open peer cannot carry it");
    check(g_cbError == ETIMEDOUT, "and reported ETIMEDOUT");
}

/* Re-publishing a send that is still pending (exactly what the 90s stranded sweep does, and
 * newly reachable now that a timeout no longer clears the entry) must not silently drop the
 * fresh callback — that is how getPublishResult would stay -1 forever for a retried send. */
static void test_duplicate_publish_adopts_tracking(void)
{
    printf("\n-- re-publishing a still-pending send keeps it trackable --\n");
    BRWallet *wallet = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) { printf("   SKIP: manager alloc failed\n"); return; }

    /* First registration carries NO callback — the shape a parent-input tx is added with. */
    BRTransaction *tx = BRTransactionNew();
    memset(&tx->txHash, 0x5A, sizeof(tx->txHash));
    _BRPeerManagerAddTxToPublishList(m, tx, NULL, NULL);
    check(array_count(m->publishedTx) == 1, "registered once");

    /* Second registration of the SAME transaction, now with a real callback. */
    int adopted = _BRPeerManagerAddTxToPublishList(m, tx, &g_cbSentinel, recordPublishResult);

    check(array_count(m->publishedTx) == 1, "still exactly one entry — no duplicate row");
    check(m->publishedTx[0].callback == recordPublishResult,
          "the untracked entry adopted the fresh callback");
    check(adopted == 0,
          "and reported 'not newly added', so the caller knows it still owns its copy");
}

int main(void)
{
#ifdef PUBLISH_SURVIVOR_UNFIXED
    printf("ARM: UNFIXED (-DPUBLISH_SURVIVOR_UNFIXED) — any handshook peer's timeout cancels "
           "every pending publish\n");
#else
    printf("ARM: FIXED (cancellation gated on there being no surviving publish peer)\n");
#endif

    test_survivor_keeps_publish_alive();
    test_last_peer_still_cancels();
    test_only_unhandshook_peers_left();
    test_duplicate_publish_adopts_tracking();

    printf("\npublish_cancel_survivor_kat: %s\n", g_fail ? "FAIL" : "PASS");
    return g_fail ? 1 : 0;
}
