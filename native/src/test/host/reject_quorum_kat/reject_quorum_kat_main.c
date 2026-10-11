/* Host KAT: no single peer's message removes a transaction or unlocks its coins.
 *
 * THE INVARIANT. The stranded-send sweep removes a plain DGB send, and so gives its coins back, only
 * on evidence one peer cannot supply. A reject message is one peer's word: a peer can send a terminal
 * reject for a valid transaction that honest peers already relayed. BRPeerManagerTxRefusedByNetwork is
 * the core's answer to "has the network refused it": 1 only when peers in at least two network groups
 * (an IPv4 address's /16, any other address's /32) rejected it as invalid, non-standard or dust AND no
 * peer has relayed or announced it. The publish verdict a first rejection gives (EINVAL, answered
 * once) is unchanged: it says the send is not relayed; it does not say the network refused it.
 *
 * Drives the real BRPeerManager.c (with BRPeer.c, for BRPeerContext) through its own reject, relay
 * and has-tx handlers, with connected peers whose addresses place them in chosen network groups.
 *
 * Comparison arm (-DREJECT_QUORUM_UNFIXED, by PRESENCE, as BRPeerManager.c's other seams): one
 * peer's terminal rejection is taken as the network's -- the rule the sweep followed. Each RED case
 * must fail there; each GUARD case holds in both arms.
 *
 * One case per process (argv[1]); "list" names them. Each prints RESULT <case> pass|fail.
 */
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

/* BRPeerContext (no public status/verack setter) and BRPeerManagerStruct need both .c files in one
 * translation unit. Both define a file-static _dummyThreadCleanup; BRPeer.c's copy is renamed for its
 * #include only. run.sh leaves both off the link line. Same pattern as publish_list_ownership_kat. */
#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"

static int g_failed = 0;
static void check(int cond, const char *what)
{
    printf("%s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_failed = 1;
}

static int g_cbCount = 0, g_lastError = -1;
static void countingResult(void *info, int error) { g_cbCount++; g_lastError = error; free(info); }
static void *newCtx(void) { return calloc(1, 65); }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    return BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
}

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

/* A plain send paying `amt` to `spk`, spending `prev`:0; the same arguments give the same transaction. */
static BRTransaction *mkTx(const uint8_t *spk, size_t spkLen, UInt256 prev, uint64_t amt)
{
    BRTransaction *tx = BRTransactionNew();
    tx->version = 1;
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

/* A connected, handshaken peer at an IPv4 address (IPv4-mapped, as the core stores it). */
static BRPeer *addPeer4(BRPeerManager *m, uint8_t a, uint8_t b, uint8_t c, uint8_t d)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    memset(&p->address, 0, sizeof(p->address));
    p->address.u16[5] = 0xffff;
    p->address.u8[12] = a; p->address.u8[13] = b; p->address.u8[14] = c; p->address.u8[15] = d;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = 1;
    array_add(m->connectedPeers, p);
    return p;
}

/* A connected, handshaken peer at an IPv6 address whose first four bytes are given. */
static BRPeer *addPeer6(BRPeerManager *m, uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3, uint8_t last)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    memset(&p->address, 0, sizeof(p->address));
    p->address.u8[0] = b0; p->address.u8[1] = b1; p->address.u8[2] = b2; p->address.u8[3] = b3;
    p->address.u8[15] = last;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = 1;
    array_add(m->connectedPeers, p);
    return p;
}

static void rejectFrom(BRPeerManager *m, BRPeer *p, UInt256 h, uint8_t code)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerRejectedTx(&info, h, code);
}

/* The peer announces it has the transaction (inv), recorded by the has-tx path. */
static void announcedBy(BRPeerManager *m, BRPeer *p, UInt256 h)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerHasTx(&info, h);
}

/* The peer relays the transaction itself; the relay path takes the object. */
static void relayedBy(BRPeerManager *m, BRPeer *p, BRTransaction *tx)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerRelayedTx(&info, tx);
}

static void drainPongs(BRPeer *p)
{
    void (*cb)(void *, int) = NULL;
    void *info = NULL;
    while (_BRPeerPongPop((BRPeerContext *)p, &cb, &info)) { if (cb) cb(info, 0); cb = NULL; info = NULL; }
}

/* A rig: a wallet, a connected manager, and send A -- published by this wallet, its record held. */
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    uint8_t spk[64];
    size_t spkLen;
    UInt256 prev, aHash;
} Rig;

static int rigUp(Rig *r)
{
    memset(r, 0, sizeof(*r));
    g_cbCount = 0; g_lastError = -1;
    r->w = makeWallet();
    r->m = r->w ? BRPeerManagerNew(&BRMainNetParams, r->w, 0, NULL, 0, NULL, 0) : NULL;
    if (! r->w || ! r->m) return 0;
    BRAddress a = BRWalletReceiveAddress(r->w, 1);
    r->spkLen = BRAddressScriptPubKey(r->spk, sizeof(r->spk), a.s);
    memset(r->prev.u8, 0x21, sizeof(r->prev.u8));
    r->m->isConnected = 1; r->m->connectFailureCount = MAX_CONNECT_FAILURES; r->m->downloadPeer = NULL;

    BRTransaction *A = mkTx(r->spk, r->spkLen, r->prev, 40000);
    A->timestamp = (uint32_t)time(NULL);
    r->aHash = A->txHash;
    BRTransaction *copy = BRTransactionCopy(A);                 /* the wallet's own record (bridge shape) */
    if (copy) BRWalletRegisterTransactionTrusted(r->w, copy);
    BRPeerManagerPublishTx(r->m, A, newCtx(), countingResult);  /* the publisher owns the original */
    return BRWalletTransactionForHash(r->w, r->aHash) != NULL;
}

/* A fresh object of send A, as a peer's relay delivers it. */
static BRTransaction *wireCopy(Rig *r) { return mkTx(r->spk, r->spkLen, r->prev, 40000); }

static void rigDown(Rig *r)
{
    if (r->m) {
        for (size_t i = array_count(r->m->connectedPeers); i > 0; i--) drainPongs(r->m->connectedPeers[i - 1]);
        BRPeerManagerFree(r->m);
    }
    if (r->w) BRWalletFree(r->w);
}

static int refused(Rig *r) { return BRPeerManagerTxRefusedByNetwork(r->m, r->aHash); }

/* ---------------------------------------------------------------- cases ---- */

static void one_peer_rejects(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    check(! refused(&r), "one peer's invalid-rejection is not the network refusing the send");
    rigDown(&r);
}

static void one_peer_rejects_repeatedly(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, a, r.aHash, REJECT_DUST);
    rejectFrom(r.m, a, r.aHash, REJECT_NONSTANDARD);
    check(! refused(&r), "one peer rejecting three times is still one peer");
    rigDown(&r);
}

static void two_peers_one_group(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *b = addPeer4(r.m, 10, 1, 200, 7);    /* same /16 */
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, b, r.aHash, REJECT_INVALID);
    check(! refused(&r), "two peers in one /16 are one network group, not corroboration");
    rigDown(&r);
}

static void two_groups_no_relay(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);    /* another /16 */
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_NONSTANDARD);
    check(refused(&r), "rejections from two network groups, no peer having it: the network refused the send");
    check(BRPeerManagerRelayCount(r.m, r.aHash) == 0, "no peer relays it");
    rigDown(&r);
}

static void two_groups_then_relayed(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    BRPeer *d = addPeer4(r.m, 192, 168, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_INVALID);
    relayedBy(r.m, d, wireCopy(&r));
    check(BRPeerManagerRelayCount(r.m, r.aHash) == 1, "a third peer relays the send");
    check(! refused(&r), "rejected by two groups, but another peer relayed it: not refused");
    rigDown(&r);
}

static void announced_then_two_groups(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    BRPeer *d = addPeer4(r.m, 192, 168, 0, 1);
    announcedBy(r.m, d, r.aHash);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_DUST);
    check(! refused(&r), "a peer announced it before two groups rejected it: not refused");
    rigDown(&r);
}

static void rejecter_later_has_it(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_INVALID);
    announcedBy(r.m, a, r.aHash);
    check(! refused(&r), "a rejecting peer that then announces it has the send: not refused");
    rigDown(&r);
}

static void ipv6_one_group(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer6(r.m, 0x20, 0x01, 0x0d, 0xb8, 0x01);
    BRPeer *b = addPeer6(r.m, 0x20, 0x01, 0x0d, 0xb8, 0x02);   /* same /32 */
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, b, r.aHash, REJECT_INVALID);
    check(! refused(&r), "two IPv6 peers in one /32 are one network group");
    rigDown(&r);
}

static void ipv6_two_groups(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer6(r.m, 0x20, 0x01, 0x0d, 0xb8, 0x01);
    BRPeer *b = addPeer6(r.m, 0x2a, 0x02, 0x01, 0x80, 0x02);   /* another /32 */
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, b, r.aHash, REJECT_INVALID);
    check(refused(&r), "two IPv6 /32s, no peer having it: refused");
    rigDown(&r);
}

static void policy_rejects_only(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_LOWFEE);
    rejectFrom(r.m, c, r.aHash, REJECT_SPENT);
    rejectFrom(r.m, c, r.aHash, 0x7f);   /* an unknown code */
    check(! refused(&r), "policy and unknown rejections are no refusal of the transaction");
    rigDown(&r);
}

static void unknown_hash_unrecorded(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    UInt256 other; memset(other.u8, 0x5e, sizeof(other.u8));
    size_t before = array_count(r.m->txRejects);
    rejectFrom(r.m, a, other, REJECT_INVALID);
    rejectFrom(r.m, c, other, REJECT_INVALID);
    check(array_count(r.m->txRejects) == before, "rejections naming a transaction this wallet neither holds nor publishes are not recorded");
    check(! BRPeerManagerTxRefusedByNetwork(r.m, other), "and refuse nothing");
    rigDown(&r);
}

static void publish_answer_unchanged(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    check(g_cbCount == 1 && g_lastError == EINVAL, "the first rejection still answers the publish once with EINVAL: the send is not relayed");
    rejectFrom(r.m, c, r.aHash, REJECT_INVALID);
    check(g_cbCount == 1, "a second rejection answers nothing more");
    rigDown(&r);
}

static void confirmed_forgets(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_INVALID);
    uint32_t height = BRMainNetParams.checkpointsCount
        ? BRMainNetParams.checkpoints[BRMainNetParams.checkpointsCount - 1].height + 1000 : 25000000;
    _BRPeerManagerUpdateTx(r.m, &r.aHash, 1, height, (uint32_t)time(NULL));
    _BRPeerManagerFlushAnswers(r.m);
    check(! refused(&r), "a confirmed send is not refused, whatever peers said of it");
    rigDown(&r);
}

static void removed_forgets(void)
{
    Rig r; check(rigUp(&r), "rig: send A is published and held");
    BRPeer *a = addPeer4(r.m, 10, 1, 0, 1);
    BRPeer *c = addPeer4(r.m, 172, 20, 0, 1);
    rejectFrom(r.m, a, r.aHash, REJECT_INVALID);
    rejectFrom(r.m, c, r.aHash, REJECT_INVALID);
    BRPeerManagerRemoveTransaction(r.m, r.aHash);
    size_t left = 0;
    for (size_t i = 0; i < array_count(r.m->txRejects); i++) if (UInt256Eq(r.m->txRejects[i].txHash, r.aHash)) left++;
    check(left == 0, "the wallet removing the send drops what peers said of it");
    rigDown(&r);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "one_peer_rejects", one_peer_rejects },
    { "one_peer_rejects_repeatedly", one_peer_rejects_repeatedly },
    { "two_peers_one_group", two_peers_one_group },
    { "two_groups_no_relay", two_groups_no_relay },
    { "two_groups_then_relayed", two_groups_then_relayed },
    { "announced_then_two_groups", announced_then_two_groups },
    { "rejecter_later_has_it", rejecter_later_has_it },
    { "ipv6_one_group", ipv6_one_group },
    { "ipv6_two_groups", ipv6_two_groups },
    { "policy_rejects_only", policy_rejects_only },
    { "unknown_hash_unrecorded", unknown_hash_unrecorded },
    { "publish_answer_unchanged", publish_answer_unchanged },
    { "confirmed_forgets", confirmed_forgets },
    { "removed_forgets", removed_forgets },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
#ifdef REJECT_QUORUM_UNFIXED
        printf("ARM: comparison (REJECT_QUORUM_UNFIXED)\n");
#else
        printf("ARM: shipped\n");
#endif
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
