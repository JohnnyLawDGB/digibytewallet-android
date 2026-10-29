/* late_peer_callback_kat -- a callback queued on a peer after its thread's own disconnect drain is
 * still answered, once, before the peer is freed.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * A peer's thread answers every queued pong (BRPeerSendPing) and its mempool request
 * (BRPeerSendMempool) with success 0 as it exits, then calls _peerDisconnected, which frees the
 * peer. A sender that read the peer's status as connected under manager->lock just before the
 * thread marked it disconnected -- BRPeerManagerPublishTx, the stem and fluff paths, _peerConnected
 * -- can queue one more callback after that drain. _peerDisconnected must answer it (success 0,
 * exactly once, with manager->lock not held, since every callback takes the lock) before BRPeerFree;
 * otherwise the callback's context is never freed and the work it stands for never happens. For
 * BRPeerManagerPublishTx that context is the BRPeerCallbackInfo it allocates per peer
 * (_publishTxInvDone frees it), which LeakSanitizer reports as lost.
 *
 * The KAT #includes BRPeer.c and BRPeerManager.c (same shape as download_peer_promote_kat) and
 * stands in for the peer thread: a synthetic socket-less peer whose status is set by hand, and
 * _peerDisconnected called directly, as the thread calls it after its drain.
 *
 * RED ARM: -DPEER_LATE_CALLBACK_UNFIXED compiles the answer out of _peerDisconnected. Every
 * "answered" check then fails, the publish path's included (counted through a wrapper in front of its
 * _publishTxInvDone pong, so it fails on every platform), and on Linux LeakSanitizer also reports
 * the publish path's lost context.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

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

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

// A signed-looking send the wallet holds; the returned object is a distinct copy for the publisher.
static BRTransaction *makeSend(BRWallet *w, uint8_t tag)
{
    BRAddress a = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    uint8_t sig[72]; memset(sig, 0x30, sizeof(sig));
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransactionAddInput(tx, prev, 0, 100000, spk, spkLen, sig, sizeof(sig), kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 50000, spk, spkLen);
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t);
    BRWalletRegisterTransaction(w, BRTransactionCopy(tx));
    return tx;
}

static BRPeer *addPeer(BRPeerManager *m, uint8_t addrByte)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u16[5] = 0xffff;
    p->address.u8[15] = addrByte;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = 1;
    ((BRPeerContext *)p)->sentVerack = 1;
    array_add(m->connectedPeers, p);
    return p;
}

// What the peer thread does on its way out, after its own drain: mark the peer disconnected (done
// by the caller, before the late callback is queued) and hand it to _peerDisconnected, which frees it.
static void peerThreadExit(BRPeerManager *m, BRPeer *peer, int error)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = peer; info.manager = m;
    _peerDisconnected(&info, error);
}

// The thread's own drain in _peerThreadRoutine, as it runs before the thread calls _peerDisconnected.
static void threadDrain(BRPeer *peer)
{
    void (*cb)(void *, int) = NULL;
    void *info = NULL;

    while (_BRPeerPongPop((BRPeerContext *)peer, &cb, &info)) {
        if (cb) cb(info, 0);
        cb = NULL; info = NULL;
    }
}

typedef struct {
    BRPeerManager *manager;
    int calls, lastSuccess, lockFree;
} Answer;

static void answered(void *info, int success)
{
    Answer *a = info;
    a->calls++;
    a->lastSuccess = success;
    // Every real callback takes manager->lock: it must be free when the callback runs.
    if (pthread_mutex_trylock(&a->manager->lock) == 0) {
        a->lockFree = 1;
        pthread_mutex_unlock(&a->manager->lock);
    }
    else a->lockFree = 0;
}

// Stands in front of the publish path's own pong callback so the scenario can count its answer on
// every platform (LeakSanitizer reports the lost context only on Linux).
typedef struct {
    void (*cb)(void *, int);
    void *info;
    int calls, lastSuccess;
} Wrapped;

static void wrappedPong(void *info, int success)
{
    Wrapped *w = info;
    w->calls++;
    w->lastSuccess = success;
    w->cb(w->info, success);   // _publishTxInvDone: frees its BRPeerCallbackInfo
}

int main(void)
{
    // Unbuffered: LeakSanitizer ends a leaking run with _exit, which would drop buffered output, and
    // the red arm's gate reads that output.
    setvbuf(stdout, NULL, _IONBF, 0);

    printf("\n-- a pong queued after the thread's drain is answered once, with success 0 --\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRPeer *p = addPeer(m, 0x01), *keep = addPeer(m, 0x02);
        m->downloadPeer = keep;
        m->isConnected = 1;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* no reconnect: nothing here may dial */

        Answer early = { m, 0, -1, 0 }, late = { m, 0, -1, 0 };
        BRPeerSendPing(p, &early, answered);
        ((BRPeerContext *)p)->status = BRPeerStatusDisconnected;
        threadDrain(p);                                  // the thread's own drain
        check(early.calls == 1 && early.lastSuccess == 0, "(fixture) the thread's drain answered the earlier pong");
        BRPeerSendPing(p, &late, answered);              // queued by a sender that saw the peer connected
        peerThreadExit(m, p, ECONNRESET);

        check(late.calls == 1, "the late pong was answered");
        check(late.lastSuccess == 0, "the late pong was answered with success 0");
        check(late.calls != 1 || late.lockFree, "the late pong was answered with manager->lock not held");
        check(early.calls == 1, "the earlier pong was not answered a second time");

        BRPeerManagerFree(m);   // `keep` has nothing queued
        BRWalletFree(w);
    }

    printf("\n-- a mempool request made after the thread's drain is answered once, with success 0 --\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRPeer *p = addPeer(m, 0x01), *keep = addPeer(m, 0x02);
        m->downloadPeer = keep;
        m->isConnected = 1;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* no reconnect: nothing here may dial */

        Answer late = { m, 0, -1, 0 };
        ((BRPeerContext *)p)->status = BRPeerStatusDisconnected;
        BRPeerSendMempool(p, NULL, 0, &late, answered);
        check(((BRPeerContext *)p)->mempoolCallback != NULL, "(fixture) the mempool request is pending");
        peerThreadExit(m, p, ECONNRESET);

        check(late.calls == 1, "the late mempool request was answered");
        check(late.lastSuccess == 0, "the late mempool request was answered with success 0");
        check(late.calls != 1 || late.lockFree, "the late mempool request was answered with manager->lock not held");

        BRPeerManagerFree(m);
        BRWalletFree(w);
    }

    printf("\n-- BRPeerManagerPublishTx on the only peer, then its thread exits: nothing is left behind --\n");
    {
        // The publish pings the one connected peer with a BRPeerCallbackInfo of its own; the peer's
        // thread has already drained. Answered, _publishTxInvDone frees it; dropped, LeakSanitizer
        // reports it. The send itself is the wallet's and the list's to release (BRPeerManagerFree).
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRPeer *p = addPeer(m, 0x01);
        m->downloadPeer = p;
        m->isConnected = 1;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* no reconnect: nothing here may dial */

        BRPeerManagerPublishTx(m, makeSend(w, 0x11), NULL, NULL);
        size_t queued = _BRPeerPongPending((BRPeerContext *)p);
        check(queued == 1, "(fixture) the publish queued one pong on the peer");
        Wrapped pub = { NULL, NULL, 0, -1 };
        if (! _BRPeerPongPop((BRPeerContext *)p, &pub.cb, &pub.info)) pub.cb = NULL;
        check(pub.cb == _publishTxInvDone && pub.info != NULL, "(fixture) it is the publish's _publishTxInvDone pong");
        if (pub.cb) _BRPeerPongPush((BRPeerContext *)p, &pub, wrappedPong);
        ((BRPeerContext *)p)->status = BRPeerStatusDisconnected;
        peerThreadExit(m, p, ECONNRESET);
        check(array_count(m->connectedPeers) == 0, "(fixture) the peer is gone");
        check(pub.calls == 1, "the publish's pong was answered");
        check(pub.lastSuccess == 0, "the publish's pong was answered with success 0");

        BRPeerManagerFree(m);
        BRWalletFree(w);
    }

#ifdef PEER_LATE_CALLBACK_UNFIXED
    printf("\nARM: UNFIXED (-DPEER_LATE_CALLBACK_UNFIXED)\n");
#else
    printf("\nARM: FIXED\n");
#endif
    printf("%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
