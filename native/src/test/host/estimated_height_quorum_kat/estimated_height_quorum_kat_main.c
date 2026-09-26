/* estimated_height_quorum_kat — the sync target is the height the connected peers agree on.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * The peer manager's estimatedHeight is the LOWER MEDIAN of the heights its connected, handshaken
 * peers reported, bounded above by how far the chain can plausibly have grown since the last
 * verified header (ESTIMATED_HEIGHT_GROWTH_FLOOR blocks at least, else 2 x elapsed seconds /
 * ESTIMATED_HEIGHT_BLOCK_SECS), never below the height already held, and recomputed -- up OR down
 * -- on every change of the peer set and on every KeepAlive tick. The value is always one of the
 * sampled reports, the bound, or the held tip, so `block->height == estimatedHeight` in
 * _peerRelayedBlock stays reachable. When a recompute moves the estimate DOWN onto the held tip
 * while a sync is in progress, the completion action (_BRPeerManagerLoadMempools) runs exactly once.
 *
 * RIG. Same shape as download_peer_promote_kat: this file #includes the real BRPeer.c and
 * BRPeerManager.c, fakes connected, handshaken peers with no socket (status / gotVerack /
 * sentVerack / lastblock set directly on the BRPeerContext), gives the manager a fabricated
 * lastBlock at a chosen height and timestamp, and drives _BRPeerManagerAdoptDownloadPeer,
 * _peerDisconnected and BRPeerManagerKeepAlive directly. _peerConnected is never driven: it
 * sends getheaders / mempool on the socket and needs services / version to pass its gates.
 * Every scenario that drives _peerDisconnected holds connectFailureCount at MAX_CONNECT_FAILURES
 * (the promote KAT's shape) so the manager never dials: this rig has no network, and a dial
 * would leave real peer / DNS threads alive past BRPeerManagerFree.
 *
 * TIME. `now` is read once at start; the recompute reads the clock at each call. The tip is
 * dated half an hour before `now`, so the growth term (2 x 1800 / 15 = 240) sits well inside the
 * 480 floor and the run may take minutes before the bound would move -- a tip dated exactly an
 * hour back puts the term AT the floor, where eight slow seconds (a 32-bit sanitizer build on a
 * loaded host) raise the bound by one block and the bounded cases miss by one.
 *
 * COMPARISON ARM. -DESTIMATED_HEIGHT_QUORUM_UNFIXED restores the single-peer assignment at the two
 * download-peer sites and removes the other recompute hooks. run.sh builds it and REQUIRES the
 * named checks below to fail there; the shipped arm must pass every one.
 *
 * Completion is observed through what _BRPeerManagerLoadMempools does: it registers _postSyncDone
 * on every connected peer (a pong callback, or the mempool completion callback), so the number of
 * such registrations across the remaining peers is the number of peers it reached; zero means it
 * did not run, and a second tick must not add any.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRMerkleBlock.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"

static int g_fail = 0, g_scenarioFail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) { g_fail++; g_scenarioFail++; }
}
static void beginScenario(const char *name) { printf("\n-- %s --\n", name); g_scenarioFail = 0; }
static void endScenario(const char *name)
{
    printf("RESULT %s %s\n", name, g_scenarioFail ? "FAIL" : "PASS");
}

static int g_lastError = -1;
static void recordResult(void *info, int error) { (void)info; g_lastError = error; }

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

/* A connected, handshaken peer with no socket, reporting `lastblock` as its chain height. */
static BRPeer *addPeer(BRPeerManager *m, uint8_t addrByte, uint32_t lastblock)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u16[5] = 0xffff;
    p->address.u8[15] = addrByte;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = 1;
    ((BRPeerContext *)p)->sentVerack = 1;
    ((BRPeerContext *)p)->lastblock = lastblock;
    array_add(m->connectedPeers, p);
    return p;
}

/* What the peer thread does: status goes to Disconnected, then the manager is told. Frees peer. */
static void killPeer(BRPeerManager *m, BRPeer *peer, int error)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    ((BRPeerContext *)peer)->status = BRPeerStatusDisconnected;
    info.peer = peer; info.manager = m;
    _peerDisconnected(&info, error);
}

/* The manager's last verified header, at a chosen height and time. Not in the block set, so the
 * caller frees it after BRPeerManagerFree. */
static BRMerkleBlock *fakeTip(BRPeerManager *m, uint32_t height, uint32_t timestamp)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->height = height;
    b->timestamp = timestamp;
    m->lastBlock = b;
    return b;
}

static int isConnectedPeer(BRPeerManager *m, BRPeer *p)
{
    for (size_t i = 0; i < array_count(m->connectedPeers); i++) if (m->connectedPeers[i] == p) return 1;
    return 0;
}

/* Number of _postSyncDone registrations _BRPeerManagerLoadMempools left on the connected peers. */
static size_t postSyncRegistrations(BRPeerManager *m)
{
    size_t n = 0;
    for (size_t i = 0; i < array_count(m->connectedPeers); i++) {
        BRPeerContext *ctx = (BRPeerContext *)m->connectedPeers[i];
        n += array_count(ctx->pongCallback);
        if (ctx->mempoolCallback) n++;
    }
    return n;
}

/* Completion reachability by construction: the estimate is a sampled report, the bound, or the
 * tip -- never a height no chain passes through at the right moment (an average, say). */
static int isSampledOrBound(uint32_t est, const uint32_t *claims, size_t n, uint32_t tip, uint32_t cap)
{
    if (est == tip || est == cap) return 1;
    for (size_t i = 0; i < n; i++) if (claims[i] == est) return 1;
    return 0;
}

#define L    23000000u                 /* held tip                                              */
#define H    (L + 200u)                /* honest report, inside the bound                       */
#define CAP1 (L + ESTIMATED_HEIGHT_GROWTH_FLOOR) /* bound half an hour after the tip: the floor governs with margin */
#define FAR  (H + 10000000u)           /* a report far beyond plausible growth                  */

int main(void)
{
    uint32_t now = (uint32_t)time(NULL);

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("quorum_adopt");
    printf("   five peers, four report H, the download peer reports UINT32_MAX\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claims[5] = {H, H, H, H, UINT32_MAX};
        addPeer(m, 0x01, H); addPeer(m, 0x02, H); addPeer(m, 0x03, H); addPeer(m, 0x04, H);
        BRPeer *outlier = addPeer(m, 0x05, UINT32_MAX);

        _BRPeerManagerAdoptDownloadPeer(m, outlier);
        check(m->downloadPeer == outlier, "(fixture) the high reporter is the download peer");
        check(m->estimatedHeight == H, "the estimate is the agreed height H, not the download peer's report");
        check(m->estimatedHeight >= m->lastBlock->height, "never below the held tip");
        check(isSampledOrBound(m->estimatedHeight, claims, 5, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        check(postSyncRegistrations(m) == 0, "an upward move runs no completion action");

        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);
        BRWalletFree(w);
    }
    endScenario("quorum_adopt");

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("outlier_leaves");
    printf("   {H, H, H, FAR, UINT32_MAX}: the download peer leaves, then the next high reporter\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claims[5] = {H, H, H, FAR, UINT32_MAX};
        BRPeer *a = addPeer(m, 0x01, H), *b = addPeer(m, 0x02, H), *c = addPeer(m, 0x03, H);
        BRPeer *far = addPeer(m, 0x04, FAR);
        BRPeer *outlier = addPeer(m, 0x05, UINT32_MAX);
        m->maxConnectCount = 5;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* the promote KAT's shape: reached through churn */

        _BRPeerManagerAdoptDownloadPeer(m, outlier);
        check(m->estimatedHeight == H, "(fixture) the estimate is H with the high reporter connected");

        BRTransaction *pending = makeSend(w, 0x11);
        g_lastError = -1;
        BRPeerManagerPublishTx(m, pending, NULL, recordResult);
        check(g_lastError == -1, "(fixture) the send is pending, not refused");

        killPeer(m, outlier, ECONNRESET);
        check(m->estimatedHeight == H, "the estimate is the agreed height H after the high reporter left, not the next highest report");
        check(m->isConnected == 1 && m->downloadPeer != NULL && isConnectedPeer(m, m->downloadPeer),
              "a remaining connected peer became the download peer");
        check(g_lastError != ENOTCONN, "the pending send was not cancelled with ENOTCONN");
        check(isSampledOrBound(m->estimatedHeight, claims, 5, L, CAP1), "the estimate is a sampled report, the bound or the tip");

        killPeer(m, far, ECONNRESET);
        check(m->estimatedHeight == H, "the estimate stays H when the far reporter leaves too");
        check(m->downloadPeer == a || m->downloadPeer == b || m->downloadPeer == c, "an honest peer is the download peer");
        check(m->estimatedHeight >= m->lastBlock->height, "never below the held tip");

        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);
        BRWalletFree(w);
    }
    endScenario("outlier_leaves");

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("median_and_bound");
    printf("   two reports {H, FAR}; one report FAR; one report below the tip\n");
    {
        BRWallet *w = makeWallet();

        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claims2[2] = {H, FAR};
        addPeer(m, 0x01, H);
        BRPeer *far = addPeer(m, 0x02, FAR);
        _BRPeerManagerAdoptDownloadPeer(m, far);
        check(m->estimatedHeight == H, "two reports: the lower median H wins over the far report");
        check(isSampledOrBound(m->estimatedHeight, claims2, 2, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);

        m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        tip = fakeTip(m, L, now - 1800);
        uint32_t claims1[1] = {FAR};
        far = addPeer(m, 0x03, FAR);
        _BRPeerManagerAdoptDownloadPeer(m, far);
        check(m->estimatedHeight == CAP1, "one far report: bounded to the tip plus the growth floor");
        check(m->estimatedHeight >= L, "never below the held tip");
        check(isSampledOrBound(m->estimatedHeight, claims1, 1, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);

        m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        tip = fakeTip(m, L, now - 1800);
        uint32_t claimsLow[1] = {L - 50u};
        BRPeer *stale = addPeer(m, 0x04, L - 50u);
        _BRPeerManagerAdoptDownloadPeer(m, stale);
        check(m->estimatedHeight == L, "one report below the tip: the estimate is the held tip, never lower");
        check(isSampledOrBound(m->estimatedHeight, claimsLow, 1, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);

        BRWalletFree(w);
    }
    endScenario("median_and_bound");

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("downward_landing");
    printf("   sync in progress, {L, FAR, UINT32_MAX}: the high reporter leaves and the estimate lands on the tip\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claims[3] = {L, FAR, UINT32_MAX};
        addPeer(m, 0x01, L);
        addPeer(m, 0x02, FAR);
        BRPeer *outlier = addPeer(m, 0x03, UINT32_MAX);
        m->maxConnectCount = 3;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* no dial from this rig: it has no network */
        m->syncStartHeight = 1;   /* a sync is in progress */

        _BRPeerManagerAdoptDownloadPeer(m, outlier);
        check(m->estimatedHeight == CAP1, "(fixture) two of three report far ahead: the estimate is the bound, above the tip");
        check(postSyncRegistrations(m) == 0, "(fixture) no completion action while the estimate is above the tip");

        killPeer(m, outlier, ECONNRESET);
        check(m->estimatedHeight == L, "the estimate moved down onto the held tip");
        check(isSampledOrBound(m->estimatedHeight, claims, 3, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        check(postSyncRegistrations(m) == 2, "the completion action reached both remaining peers exactly once");
        check(m->downloadPeer != NULL && isConnectedPeer(m, m->downloadPeer), "a remaining peer is the download peer");

        BRPeerManagerKeepAlive(m);   /* the periodic recompute: same sample, same tip */
        check(m->estimatedHeight == L, "a tick with nothing changed keeps the estimate on the tip");
        check(postSyncRegistrations(m) == 2, "a tick with nothing changed does not repeat the completion action");
        check(atomic_load_explicit(&m->cachedEstimatedHeight, memory_order_relaxed) == L,
              "the tick mirrored the estimate for the lock-free readers");

        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);
        BRWalletFree(w);
    }
    endScenario("downward_landing");

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("single_peer");
    printf("   one peer reporting H: the estimate is H (completion at H is the relay path's), then the chain passes it\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claims[1] = {H};
        BRPeer *only = addPeer(m, 0x01, H);
        m->syncStartHeight = 1;

        _BRPeerManagerAdoptDownloadPeer(m, only);
        check(m->estimatedHeight == H, "one peer: the estimate is its report, which fits the bound");
        check(isSampledOrBound(m->estimatedHeight, claims, 1, L, CAP1), "the estimate is a sampled report, the bound or the tip");
        check(postSyncRegistrations(m) == 0, "no completion action from the recompute: the relayed block at H completes");

        tip->height = H;             /* the relay path brought the chain to H */
        BRPeerManagerKeepAlive(m);
        check(m->estimatedHeight == H, "at H the estimate is H: block->height == estimatedHeight fires there");
        check(postSyncRegistrations(m) == 0, "the recompute did not move the estimate, so it ran no completion action");

        tip->height = H + 300u;      /* the chain moved on; the peer's report is now stale */
        BRPeerManagerKeepAlive(m);
        check(m->estimatedHeight == H + 300u, "never below a height already reached: a stale report does not lower the estimate");

        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);
        BRWalletFree(w);
    }
    endScenario("single_peer");

    /* ---------------------------------------------------------------------------------------- */
    beginScenario("growth_bound");
    printf("   one peer reporting L + 5000: bounded half an hour after the tip, unbounded a month after it\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRMerkleBlock *tip = fakeTip(m, L, now - 1800);
        uint32_t claim = L + 5000u, claims[1] = {claim};
        BRPeer *only = addPeer(m, 0x01, claim);

        _BRPeerManagerAdoptDownloadPeer(m, only);
        check(m->estimatedHeight == CAP1, "half an hour after the tip the bound is the growth floor: the report is bounded");
        check(isSampledOrBound(m->estimatedHeight, claims, 1, L, CAP1), "the estimate is a sampled report, the bound or the tip");

        tip->timestamp = now - 30u*24u*3600u;   /* a deep restore: the tip is a month old */
        BRPeerManagerKeepAlive(m);
        check(m->estimatedHeight == claim, "a month after the tip the bound is loose: the report is taken as is");
        check(m->estimatedHeight >= m->lastBlock->height, "never below the held tip");

        BRPeerManagerFree(m);
        BRMerkleBlockFree(tip);
        BRWalletFree(w);
    }
    endScenario("growth_bound");

    printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "ALL PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
