/* download_peer_promote_kat — losing the download peer does not disconnect a manager that still has
 * connected peers (B237).
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * When the download peer disconnects and other handshaken peers are still connected, one of them
 * becomes the download peer and the manager stays connected: a pending send is not cancelled, and a
 * new send is published, not refused with ENOTCONN. Only when no connected peer is left does the
 * manager report the network lost, as before.
 *
 * Measured on a Note 8 (2026-09-25): every disconnect-with-error increments connectFailureCount, and
 * nothing resets it while the pool is full, so it reached MAX_CONNECT_FAILURES. When the download
 * peer then dropped, _peerDisconnected cleared isConnected and took the "sync failed" branch — pending
 * sends cancelled with ENOTCONN, no reconnect — while three peers stayed connected. Every send after
 * that was refused with ENOTCONN (the send screen still said "accepted") until the app was restarted.
 *
 * This KAT #includes BRPeer.c and BRPeerManager.c (same shape as publish_list_ownership_kat) and
 * drives the REAL disconnect and publish paths with unconnected sockets.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>

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

static void killPeer(BRPeerManager *m, BRPeer *peer, int error)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = peer; info.manager = m;
    _peerDisconnected(&info, error);   /* frees peer */
}

static int isConnectedPeer(BRPeerManager *m, BRPeer *p)
{
    for (size_t i = 0; i < array_count(m->connectedPeers); i++) if (m->connectedPeers[i] == p) return 1;
    return 0;
}

int main(void)
{
    printf("\n-- the Note 8 shape: failure count at its cap, download peer drops, two peers remain --\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRPeer *dl = addPeer(m, 0x01), *a = addPeer(m, 0x02), *b = addPeer(m, 0x03);
        m->downloadPeer = dl;
        m->isConnected = 1;
        m->maxConnectCount = 3;
        m->connectFailureCount = MAX_CONNECT_FAILURES;   /* reached through ordinary peer churn */

        BRTransaction *pending = makeSend(w, 0x11);
        g_lastError = -1;
        BRPeerManagerPublishTx(m, pending, NULL, recordResult);
        check(g_lastError == -1, "(fixture) the send is pending, not refused");

        killPeer(m, dl, ECONNRESET);
        check(m->isConnected == 1, "the manager is still connected");
        check(m->downloadPeer != NULL && (m->downloadPeer == a || m->downloadPeer == b),
              "a remaining connected peer became the download peer");
        check(g_lastError != ENOTCONN, "the pending send was not cancelled with ENOTCONN");

        g_lastError = -1;
        BRPeerManagerPublishTx(m, makeSend(w, 0x12), NULL, recordResult);
        check(g_lastError != ENOTCONN, "a new send is published, not refused with ENOTCONN");
        check(isConnectedPeer(m, a) && isConnectedPeer(m, b), "the remaining peers are untouched");

        BRPeerManagerFree(m);
        BRWalletFree(w);
    }

    printf("\n-- the last peer drops: the network is lost, as before --\n");
    {
        BRWallet *w = makeWallet();
        BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
        BRPeer *dl = addPeer(m, 0x01);
        m->downloadPeer = dl;
        m->isConnected = 1;
        m->connectFailureCount = MAX_CONNECT_FAILURES;

        BRTransaction *pending = makeSend(w, 0x21);
        g_lastError = -1;
        BRPeerManagerPublishTx(m, pending, NULL, recordResult);
        killPeer(m, dl, ECONNRESET);
        check(m->isConnected == 0 && m->downloadPeer == NULL, "no peer left: not connected");
        check(g_lastError == ENOTCONN, "the pending send is cancelled with ENOTCONN, as before");

        BRPeerManagerFree(m);
        BRWalletFree(w);
    }

    printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
