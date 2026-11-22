/* peer_count_handshaked_kat — the peer count a screen shows counts handshaken peers only
 * (PEER-COUNT-HANDSHAKED).
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * BRPeerManagerHandshakedPeerCount is the number of peers whose version/verack handshake has
 * completed. A peer that is still dialling or mid-handshake is never in it. It is the number the
 * apps show as "Connected · N peers".
 *
 * BRPeerManagerPeerCount keeps its meaning: every peer that is not disconnected, connecting ones
 * included. The apps' recovery decisions read it ("zero" = no socket open or opening), and a manager
 * with a dial in flight must not read as dead there, so this KAT pins that count as well: a change
 * that made it handshaken-only would fail the "in flight" checks below.
 *
 * Found by the pre-publish suite on 2026-11-21: the home screen read "Connected · N peers" from the
 * one count there was, which counted peers that were still connecting.
 *
 * HOW
 * ---
 * #includes BRPeer.c and BRPeerManager.c (same shape as download_peer_promote_kat) and drives the
 * REAL paths with unconnected sockets: a peer completes its handshake through _BRPeerDidConnect
 * (BRPeer.c: the one place a peer's status becomes Connected), which calls the manager's
 * _peerConnected; a peer leaves through _peerDisconnected. Both refresh the cached counts as their
 * last step under the manager lock, and the two accessors under test are the lock-free reads of
 * those caches. The first step has no handshake or disconnect to ride on, so it refreshes the way
 * every lock-holding path does (_BRPeerManagerRefreshCachedStatus).
 *
 * Each check prints "PASS <id>" or "FAIL <id>"; run.sh compares the failing ids of the comparison
 * arm (-DPEER_COUNT_HANDSHAKED_UNFIXED) with the exact list it expects.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>

#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int cond, const char *id, const char *what)
{
    printf("%s %s: %s\n", cond ? "PASS" : "FAIL", id, what);
    if (! cond) g_fail++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

typedef struct { BRPeer *peer; BRPeerCallbackInfo *info; } Dial;

/* A peer as BRPeerManagerConnect leaves it the moment it is dialled (_BRPeerManagerBeginConnect):
 * listed in connectedPeers, callbacks set, status Connecting. No thread and no socket. */
static Dial dial(BRPeerManager *m, uint8_t addrByte)
{
    Dial d;
    d.info = calloc(1, sizeof(*d.info));
    d.info->manager = m;
    d.info->peer = d.peer = BRPeerNew(m->params->magicNumber);
    d.peer->address.u16[5] = 0xffff;
    d.peer->address.u8[15] = addrByte;
    d.peer->port = m->params->standardPort;
    d.peer->services = m->params->services | SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM |
                       SERVICES_NODE_WITNESS | SERVICES_NODE_COMPACT_FILTERS;
    d.peer->timestamp = (uint64_t)time(NULL);
    array_add(m->connectedPeers, d.peer);
    BRPeerSetCallbacks(d.peer, d.info, _peerConnected, _peerDisconnected, _peerRelayedPeers,
                       _peerRelayedTx, _peerHasTx, _peerRejectedTx, _peerRelayedBlock, _peerRelayedBlockTxns,
                       _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                       _peerNetworkIsReachable, _peerThreadCleanup);
    BRPeerSetCompactFiltersOnly(d.peer, m->syncMode == BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    ((BRPeerContext *)d.peer)->status = BRPeerStatusConnecting;
    return d;
}

/* The version and verack have both gone each way: the real completion step, which sets the status
 * and calls the manager's _peerConnected. The peer reports the manager's own height, so the manager
 * keeps it ("already synced") and asks it for nothing that needs an answer to finish this test. */
static void handshake(BRPeerManager *m, Dial d)
{
    BRPeerContext *ctx = (BRPeerContext *)d.peer;
    ctx->version = 70019;
    ctx->lastblock = m->lastBlock->height;
    ctx->sentVerack = 1;
    ctx->gotVerack = 1;
    _BRPeerDidConnect(d.peer);
}

/* The peer's thread ends: status Disconnected, then the manager's _peerDisconnected (which frees the
 * peer). The failure count is held at its cap first so the manager does not start dialling real
 * addresses from a test (_peerDisconnected reconnects below the cap). */
static void drop(BRPeerManager *m, Dial d, int error)
{
    m->connectFailureCount = MAX_CONNECT_FAILURES;
    ((BRPeerContext *)d.peer)->status = BRPeerStatusDisconnected;
    _peerDisconnected(d.info, error);
    free(d.info);
}

static void refresh(BRPeerManager *m)
{
    MGR_LOCK(m);
    _BRPeerManagerRefreshCachedStatus(m);
    MGR_UNLOCK(m);
}

int main(void)
{
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    check(BRPeerManagerHandshakedPeerCount(m) == 0, "new.handshaked", "a new manager shows 0 peers");
    check(BRPeerManagerPeerCount(m) == 0, "new.inflight", "a new manager has nothing in flight");

    printf("\n-- three peers dialled, none has answered --\n");
    Dial a = dial(m, 0x01), b = dial(m, 0x02), c = dial(m, 0x03);
    refresh(m);
    check(BRPeerManagerHandshakedPeerCount(m) == 0, "dialled.handshaked", "3 connecting: the shown count is 0");
    check(BRPeerManagerPeerCount(m) == 3, "dialled.inflight", "3 connecting: 3 in flight (meaning kept)");

    printf("\n-- the first completes its handshake --\n");
    handshake(m, a);
    check(BRPeerConnectStatus(a.peer) == BRPeerStatusConnected, "one.fixture", "(fixture) the peer is Connected");
    check(BRPeerManagerHandshakedPeerCount(m) == 1, "one.handshaked", "1 handshaken + 2 connecting: the shown count is 1");
    check(BRPeerManagerPeerCount(m) == 3, "one.inflight", "1 handshaken + 2 connecting: 3 in flight");

    printf("\n-- the second completes its handshake --\n");
    handshake(m, b);
    check(BRPeerManagerHandshakedPeerCount(m) == 2, "two.handshaked", "2 handshaken + 1 connecting: the shown count is 2");
    check(BRPeerManagerPeerCount(m) == 3, "two.inflight", "2 handshaken + 1 connecting: 3 in flight");

    printf("\n-- a handshaken peer disconnects --\n");
    drop(m, a, ECONNRESET);
    check(BRPeerManagerHandshakedPeerCount(m) == 1, "dropped.handshaked", "1 handshaken + 1 connecting: the shown count is 1");
    check(BRPeerManagerPeerCount(m) == 2, "dropped.inflight", "1 handshaken + 1 connecting: 2 in flight");

    printf("\n-- the dial that never answered times out --\n");
    drop(m, c, ETIMEDOUT);
    check(BRPeerManagerHandshakedPeerCount(m) == 1, "timedout.handshaked", "1 handshaken: the shown count is 1");
    check(BRPeerManagerPeerCount(m) == 1, "timedout.inflight", "1 handshaken: 1 in flight");

    printf("\n-- a new dial while one peer is handshaken --\n");
    Dial d = dial(m, 0x04);
    refresh(m);
    check(BRPeerManagerHandshakedPeerCount(m) == 1, "redial.handshaked", "1 handshaken + 1 connecting: the shown count is 1");
    check(BRPeerManagerPeerCount(m) == 2, "redial.inflight", "1 handshaken + 1 connecting: 2 in flight");

    printf("\n-- every peer gone --\n");
    drop(m, d, ETIMEDOUT);
    drop(m, b, ECONNRESET);
    check(BRPeerManagerHandshakedPeerCount(m) == 0, "empty.handshaked", "no peer: the shown count is 0");
    check(BRPeerManagerPeerCount(m) == 0, "empty.inflight", "no peer: nothing in flight");

    BRPeerManagerFree(m);
    BRWalletFree(w);

    printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
