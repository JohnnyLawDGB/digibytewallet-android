/* dandelion_stem_announce_kat — a stemmed transaction leaves this wallet through its stem peer only.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * While a transaction is in its stem phase (BRPeerManagerStemPublishTx, until BRPeerManagerFluffTx),
 * no peer but the stem peer hears of it from us: it is not announced by inv to any other peer, and its
 * hash is not asked for by getdata from any other peer. A getdata from the stem peer is the stem
 * being taken, not the network relaying it back, so it does not count as a relay. After the fluff, and
 * for an ordinary flood, every peer is told as before.
 *
 * Measured on mainnet (Note 8, 2026-09-25): after a stem the wallet announced the "stemmed"
 * transaction by inv to six more peers as they connected, and asked three more for it by getdata, so
 * the stem hid nothing. And the stem peer's own getdata made the relay count 1, so the wallet's
 * embargo never fluffed a stem the stem peer then dropped: the send sat unconfirmed until the app
 * was restarted.
 *
 * This KAT #includes BRPeer.c and BRPeerManager.c (same shape as publish_list_ownership_kat) and
 * drives the REAL stem, connect-time publish, unrelayed-tx request, getdata answer and fluff paths.
 * Peers are unconnected sockets; what a peer "was told" is read from its known-hash set, which
 * BRPeerSendInv fills with exactly the hashes it announces.
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

static void ignoreResult(void *info, int error) { (void)info; (void)error; }
static int g_cb = 0;

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

/* A signed-looking send the wallet recognises: BRTransactionIsSigned only checks for signatures. */
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
    tx->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, BRTransactionCopy(tx));   /* the wallet's own unconfirmed record */
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
    array_add(m->connectedPeers, p);
    return p;
}

static int told(BRPeer *p, UInt256 hash) { return _BRPeerKnowsTxHash((BRPeerContext *)p, &hash); }

static void peerAsksFor(BRPeerManager *m, BRPeer *p, UInt256 hash)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = p; info.manager = m;
    BRTransaction *served = _peerRequestedTx(&info, hash);
    if (served) BRTransactionFree(served);   /* the answer is a private copy (see publish_list_ownership_kat) */
}

int main(void)
{
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { printf("fixtures failed\n"); return 1; }
    m->isConnected = 1;
    m->connectFailureCount = MAX_CONNECT_FAILURES;   /* no reconnect attempts from the harness */

    BRPeer *stem = addPeer(m, 0x05);
    BRPeerManagerAddDandelionPeer(m, stem->address);
    BRPeerManagerSetDandelionEnabled(m, 1);

    printf("\n-- stem: the stemmed transaction is announced to the stem peer only --\n");
    BRTransaction *s = makeSend(w, 0x21);
    UInt256 sHash = s->txHash;
    check(BRPeerManagerStemPublishTx(m, s, &g_cb, ignoreResult) == 1, "the send is stemmed");
    check(told(stem, sHash), "the stem peer was told");

    BRPeer *late = addPeer(m, 0x06);          /* a peer that connects during the stem phase */
    _BRPeerManagerPublishPendingTx(m, late);  /* what the connect / post-sync paths call */
    check(! told(late, sHash), "a peer that connects during the stem phase is NOT told");

    printf("\n-- no getdata leak: other peers are not asked for the stemmed hash --\n");
    check(BRWalletTxUnconfirmedBefore(w, NULL, 0, TX_UNCONFIRMED) >= 1, "(fixture) the wallet holds the send as unconfirmed");
    _BRPeerManagerRequestUnrelayedTx(m, late);
    check(! _BRTxPeerListHasPeer(m->txRequests, sHash, late), "the stemmed hash is not requested from another peer");

    printf("\n-- relay signal: the stem peer taking the stem is not a relay --\n");
    peerAsksFor(m, stem, sHash);
    check(BRPeerManagerRelayCount(m, sHash) == 0, "the stem peer's getdata leaves the relay count at 0");
    _BRPeerManagerPublishPendingTx(m, late);
    check(! told(late, sHash), "still hidden from other peers after the stem peer took it");

    printf("\n-- fluff: after the embargo every peer is told --\n");
    BRPeerManagerFluffTx(m, sHash);
    check(told(late, sHash), "after the fluff the other peer is told");

    printf("\n-- recovery flood: an explicit flood of a stemming tx ends its stem phase --\n");
    BRTransaction *r = makeSend(w, 0x41);
    UInt256 rHash = r->txHash;
    BRTransaction *rAgain = BRTransactionCopy(r);   /* the wallet's re-publish of the same send */
    check(BRPeerManagerStemPublishTx(m, r, &g_cb, ignoreResult) == 1, "a second send is stemmed");
    _BRPeerManagerPublishPendingTx(m, late);
    check(! told(late, rHash), "hidden while stemming");
    BRPeerManagerPublishTx(m, rAgain, &g_cb, ignoreResult);
    check(told(late, rHash), "the recovery flood reaches the other peer");

    printf("\n-- flood: an ordinary publish is announced to every peer, as before --\n");
    BRTransaction *f = makeSend(w, 0x31);
    UInt256 fHash = f->txHash;
    BRPeer *other = addPeer(m, 0x07);
    BRPeerManagerPublishTx(m, f, &g_cb, ignoreResult);
    check(told(other, fHash) && told(late, fHash), "a flood reaches every connected peer");
    BRPeer *later = addPeer(m, 0x08);
    _BRPeerManagerPublishPendingTx(m, later);
    check(told(later, fHash), "a flood is announced to a peer that connects later");
    peerAsksFor(m, later, fHash);
    check(BRPeerManagerRelayCount(m, fHash) == 1, "a getdata for a flood still counts as that peer taking it");

    printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
