/* tipsweep_token_stake_kat — the tip sweep never removes a transaction that carries a token output the
 * wallet owns.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * At the chain tip the peer manager's unrelayed-transaction cleanup (_requestUnrelayedTxGetdataDone)
 * removes an unconfirmed record that no connected peer still has, when the wallet has no stake in it.
 * The stake was the DGB the record moves to or from the wallet (BRWalletAmountSentByTx,
 * BRWalletAmountReceivedFromTx). A DigiDollar output is zero-value by protocol -- its amount lives in
 * the "DD" OP_RETURN -- so a DigiDollar receive has no DGB in it at all, and the cleanup removed it.
 * The stake now also counts a token output the wallet owns: a DigiDollar output bound to an amount
 * slot, or an output holding DigiAsset units (BRWalletTxHasOwnedTokenOutput). Such a record is kept
 * the way a DGB receive is kept. A record with neither is still removed.
 *
 * WHY THE RECORD MATTERS
 * ----------------------
 * An unconfirmed DigiDollar receive is pending (its zero-value output trips the dust rule in
 * _BRWalletUpdateBalance) and its credit is withheld until the record learns its height. In
 * compact-filter mode a record can stay at TX_UNCONFIRMED after its block (the block's filter is not
 * asked for again once the scan has passed it), so the app runs a confirmation reconcile at sync
 * completion: for each record the wallet itself still holds as pending, it asks a node for the height
 * and promotes the record (NativeBridge_confirmTransaction), which releases the credit. A record the
 * cleanup has already removed is not in that list, so its credit stays withheld until a full rescan.
 *
 * THE PATH (real code at every step this file can compile)
 * -------------------------------------------------------
 *   session 1  the wallet holds the receive at TX_UNCONFIRMED and is saved by
 *              BRWalletSerializeTransactions -- what getSerializedTransactions persists, height included.
 *   relaunch   the bridge's own JNI-free loader (deserialize_saved_transactions_guarded), then
 *              BRWalletNewDual and BRWalletSetTaprootKey in recoverWalletFromBytes' order.
 *   the tip    the sync completes in compact-filter mode with the app's synced peer count (three):
 *              _BRPeerManagerLoadMempools pings every peer; each pong runs _postSyncDone, which runs
 *              _BRPeerManagerRequestUnrelayedTx (getdata for every unconfirmed record, then a ping);
 *              each peer answers through the REAL notfound / tx / pong handlers in BRPeer.c, and the
 *              last pong runs the cleanup with every gate open.
 *   reconcile  what NativeBridge_confirmTransaction does: a record still at TX_UNCONFIRMED is promoted
 *              with BRWalletUpdateTransactionsUnproven.
 * run.sh holds the bridge steps this file cannot compile (JNI) to their source, as sibling suites do.
 *
 * Same include shape as publish_list_ownership_kat: this file #includes BRPeer.c and BRPeerManager.c
 * (BRPeer.c's _dummyThreadCleanup renamed for its own #include) to reach the file-static paths.
 *
 * ARMS (presence of the macro selects the comparison arm; the shipped arm is built with no -D):
 *   default                          the shipped rule above.
 *   -DTIPSWEEP_TOKEN_STAKE_UNFIXED   the DGB-only stake.
 *
 * SCENARIOS (argv[1]; run.sh says for each what it requires of each arm):
 *   dd_receive              RED-THEN-GREEN (assertion). A $50.00 DigiDollar receive saved while
 *                           pending is kept at the tip, stays pending, holds no plain coin, and the
 *                           confirmation reconcile then releases its credit. A plain DGB receive is
 *                           kept and a plain record with no stake and no token is removed in the same
 *                           pass (the control that shows the cleanup ran).
 *   asset_zero_carrier      RED-THEN-GREEN (assertion). A DigiAsset receive whose carrier output holds
 *                           no DGB is kept; the same control is removed.
 *   asset_standard_carrier  GUARD, both arms. A DigiAsset receive on a standard 700-dsat carrier has DGB
 *                           stake and was never removed: no comparison arm can be red here.
 *   gates                   GUARD, both arms. The cleanup's own early returns hold in both arms: below
 *                           the tip nothing is removed, and a record a peer still relays is kept.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"
#include "BRDigiAsset.h"
#include "BRNetwork.h"
#include "saved_transactions_deserialize.h"   /* the bridge's loader, compiled here as it ships */

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
static const uint8_t kEmpty[1] = { 0 };

#define DD_TO_US_CENTS     5000    /* $50.00 */
#define DD_CHANGE_CENTS    2500
#define DGB_TO_US          100000000ULL
#define SYNCED_PEERS       3       /* SyncService SYNCED_PEER_COUNT: the app's target once synced */

/* The wallet as recoverWalletFromBytes builds it: BRWalletNewDual over the saved records (BIP84 and
 * legacy keys), then the BIP86 taproot key. */
static BRWallet *openWallet(BRTransaction *saved[], size_t savedCount)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRWallet *w = BRWalletNewDual(savedCount > 0 ? saved : NULL, savedCount, mpk84, mpkLegacy);
    if (w) BRWalletSetTaprootKey(w, mpk86);
    memset(seed, 0, sizeof(seed));
    return w;
}

/* This wallet's first taproot (P2TR) and segwit (P2WPKH) receive scripts, read before anything is
 * registered so every session derives the same ones. */
typedef struct { uint8_t tr[64]; size_t trLen; uint8_t wpkh[64]; size_t wpkhLen; } OwnScripts;
static void ownScripts(OwnScripts *s)
{
    BRWallet *w = openWallet(NULL, 0);
    BRAddress a = BRWalletReceiveAddress(w, 2), b = BRWalletReceiveAddress(w, 1);
    s->trLen = BRAddressScriptPubKey(s->tr, sizeof(s->tr), a.s);
    s->wpkhLen = BRAddressScriptPubKey(s->wpkh, sizeof(s->wpkh), b.s);
    BRWalletFree(w);
}

/* Witness stacks as BRTxInput stores them (each item: its varint length, then its bytes). Real
 * sizes, placeholder contents: nothing on this path checks a signature that spends no wallet coin. */
static size_t keyPathWitness(uint8_t w[65], uint8_t fill)
{
    w[0] = 64; memset(w + 1, fill, 64);
    return 65;
}
static size_t wpkhWitness(uint8_t w[106], uint8_t fill)
{
    w[0] = 71; memset(w + 1, fill, 71); w[1] = 0x30; w[71] = 0x01;          /* DER-shaped, SIGHASH_ALL */
    w[72] = 33; w[73] = 0x02; memset(w + 74, (uint8_t)(fill ^ 0x5a), 32);   /* compressed public key */
    return 106;
}

/* OP_RETURN "DD" <type 2> <cents for DD output 0> <cents for DD output 1> */
static size_t ddOpReturn(uint8_t *s, int64_t a0, int64_t a1)
{
    uint8_t num[9];
    size_t n = 0, l;
    s[n++] = OP_RETURN;
    s[n++] = 0x02; s[n++] = 0x44; s[n++] = 0x44;
    s[n++] = 0x01; s[n++] = DD_TYPE_TRANSFER;
    l = BRDigiDollarWriteScriptNum(a0, num); s[n++] = (uint8_t)l; memcpy(s + n, num, l); n += l;
    l = BRDigiDollarWriteScriptNum(a1, num); s[n++] = (uint8_t)l; memcpy(s + n, num, l); n += l;
    return n;
}

/* OP_RETURN "DA" v2 transfer: 1 unit to output 0 */
static size_t daTransferOpReturn(uint8_t *s)
{
    static const uint8_t payload[] = { 0x44, 0x41, 0x02, 0x15, 0x00, 0x01 };
    s[0] = OP_RETURN; s[1] = sizeof(payload); memcpy(s + 2, payload, sizeof(payload));
    return 2 + sizeof(payload);
}

enum { REC_DD, REC_DGB, REC_PLAIN, REC_ASSET_ZERO, REC_ASSET_STD };

/* A receive as a peer or a block delivers it: built, serialized, and parsed back. Every input is
 * someone else's coin. *raw (optional) receives the wire bytes. */
static BRTransaction *makeRecord(int kind, const OwnScripts *own, uint8_t **raw, size_t *rawLen)
{
    BRTransaction *tx = BRTransactionNew();
    uint8_t ftr[34], fwpkh[22], w1[65], w2[106], opr[40];
    uint8_t tag = (uint8_t)(0x10 + kind);
    UInt256 prev0, prev1;
    size_t l1 = keyPathWitness(w1, tag), l2 = wpkhWitness(w2, tag);

    ftr[0] = 0x51; ftr[1] = 0x20; memset(ftr + 2, 0x77, 32);      /* the sender's taproot output */
    fwpkh[0] = 0x00; fwpkh[1] = 0x14; memset(fwpkh + 2, 0x66, 20); /* the sender's segwit output */
    memset(prev0.u8, tag, 32);
    memset(prev1.u8, (uint8_t)(tag ^ 0xc3), 32);
    tx->version = 2;
    tx->timestamp = (uint32_t)time(NULL) - 3600;

    switch (kind) {
    case REC_DD:        /* DigiDollar transfer: the sender's DD coin and DGB fee coin in; DD to us, DD change,
                         * the amounts by ordinal, DGB change */
        tx->version = ((uint32_t)DD_TYPE_TRANSFER << 24) | DD_VERSION_MARKER;
        BRTransactionAddInput(tx, prev0, 1, 0, NULL, 0, kEmpty, 0, w1, l1, 0xffffffff);
        BRTransactionAddInput(tx, prev1, 0, 0, NULL, 0, kEmpty, 0, w2, l2, 0xffffffff);
        BRTransactionAddOutput(tx, 0, own->tr, own->trLen);
        BRTransactionAddOutput(tx, 0, ftr, sizeof(ftr));
        BRTransactionAddOutput(tx, 0, opr, ddOpReturn(opr, DD_TO_US_CENTS, DD_CHANGE_CENTS));
        BRTransactionAddOutput(tx, 190000000, fwpkh, sizeof(fwpkh));
        break;
    case REC_DGB:       /* plain DGB receive */
        BRTransactionAddInput(tx, prev1, 0, 0, NULL, 0, kEmpty, 0, w2, l2, 0xffffffff);
        BRTransactionAddOutput(tx, DGB_TO_US, own->wpkh, own->wpkhLen);
        BRTransactionAddOutput(tx, 50000000, fwpkh, sizeof(fwpkh));
        break;
    case REC_PLAIN:     /* a record the wallet has no stake in that carries no token: a zero-value output to us */
        BRTransactionAddInput(tx, prev1, 0, 0, NULL, 0, kEmpty, 0, w2, l2, 0xffffffff);
        BRTransactionAddOutput(tx, 0, own->wpkh, own->wpkhLen);
        BRTransactionAddOutput(tx, 50000000, fwpkh, sizeof(fwpkh));
        break;
    case REC_ASSET_ZERO:
    case REC_ASSET_STD: /* DigiAsset transfer of 1 unit to our output 0: the sender's asset coin and fee coin in */
        BRTransactionAddInput(tx, prev0, 0, 0, NULL, 0, kEmpty, 0, w2, l2, 0xffffffff);
        BRTransactionAddInput(tx, prev1, 1, 0, NULL, 0, kEmpty, 0, w2, l2, 0xffffffff);
        BRTransactionAddOutput(tx, (kind == REC_ASSET_ZERO) ? 0 : DA_ASSET_DUST_AMOUNT, own->wpkh, own->wpkhLen);
        BRTransactionAddOutput(tx, 0, opr, daTransferOpReturn(opr));
        BRTransactionAddOutput(tx, 50000000, fwpkh, sizeof(fwpkh));
        break;
    }

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len);
    BRTransactionSerialize(tx, buf, len);
    BRTransaction *parsed = BRTransactionParse(buf, len);
    if (parsed) parsed->timestamp = tx->timestamp;
    BRTransactionFree(tx);
    if (raw) { *raw = buf; *rawLen = len; } else free(buf);
    return parsed;
}

/* Session 1: the wallet holds `recs` at TX_UNCONFIRMED -- registered through the checked path, as a
 * relayed receive, or a block-delivered one before its height lands, is -- and is saved the way
 * getSerializedTransactions saves it. Returns the saved blob (the wallet and its records are freed). */
static uint8_t *saveSession(BRTransaction *recs[], size_t n, size_t *blobLen)
{
    BRWallet *w = openWallet(NULL, 0);
    int ok = (w != NULL);

    for (size_t i = 0; ok && i < n; i++) {
        UInt256 h = recs[i]->txHash;
        ok = BRWalletRegisterTransaction(w, recs[i]) && BRWalletTransactionForHash(w, h) == recs[i] &&
             recs[i]->blockHeight == TX_UNCONFIRMED;
    }
    check(ok, "session 1: the wallet holds every record at TX_UNCONFIRMED");

    size_t need = BRWalletSerializeTransactions(w, NULL, 0);
    uint8_t *blob = malloc(need);
    *blobLen = BRWalletSerializeTransactions(w, blob, need);
    BRWalletFree(w);
    return blob;
}

typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeer *peers[SYNCED_PEERS];
    BRPeerCallbackInfo *infos[SYNCED_PEERS];
    size_t peerCount;
    int syncStoppedSeen;
} Fx;

static void onSyncStopped(void *info, int error)
{
    (void)error;
    ((Fx *)info)->syncStoppedSeen++;
}

/* The relaunch: the bridge's loader over the saved blob, then the wallet in recoverWalletFromBytes'
 * order. Records the wallet did not take stay the caller's (as in the bridge). */
static void relaunch(Fx *f, const uint8_t *blob, size_t blobLen, size_t expect)
{
    BRTransaction **saved = NULL;
    size_t n = deserialize_saved_transactions_guarded(blob, blobLen, &saved);

    f->w = openWallet(saved, n);
    for (size_t i = 0; i < n; i++) {
        if (! f->w || BRWalletTransactionForHash(f->w, saved[i]->txHash) != saved[i]) BRTransactionFree(saved[i]);
    }
    free(saved);
    check(n == expect && f->w != NULL, "relaunch: the bridge's loader parsed every saved record and the wallet took them");
}

/* The peer manager at the tip: compact-filter mode, three connected handshaken filter peers that all
 * report the held tip, the app's synced target, and a sync that is completing now. */
static void startAtTip(Fx *f)
{
    const BRCheckPoint *cp = &BRMainNetParams.checkpoints[BRMainNetParams.checkpointsCount - 1];
    uint32_t keyTime = cp->timestamp + 8*24*60*60;   /* born after the newest checkpoint: it is the held tip */

    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, keyTime, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    BRPeerManagerSetCallbacks(f->m, f, NULL, onSyncStopped, NULL, NULL, NULL, NULL, NULL);
    f->m->isConnected = 1;
    f->m->connectFailureCount = MAX_CONNECT_FAILURES;   /* this rig has no network: never dial */
    f->m->maxConnectCount = SYNCED_PEERS;

    uint32_t tip = f->m->lastBlock->height;
    for (size_t k = 0; k < SYNCED_PEERS; k++) {
        BRPeerCallbackInfo *info = calloc(1, sizeof(*info));
        BRPeer *p = BRPeerNew(f->m->params->magicNumber);
        BRPeerContext *ctx = (BRPeerContext *)p;

        p->address.u16[5] = 0xffff;
        p->address.u8[15] = (uint8_t)(k + 1);
        p->port = 12024;
        p->services = SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS;
        info->peer = p;
        info->manager = f->m;
        BRPeerSetCallbacks(p, info, _peerConnected, _peerDisconnected, _peerRelayedPeers, _peerRelayedTx,
                           _peerHasTx, _peerRejectedTx, _peerRelayedBlock, _peerRelayedBlockTxns,
                           _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                           _peerNetworkIsReachable, _peerThreadCleanup);
        ctx->status = BRPeerStatusConnected;
        ctx->gotVerack = 1;
        ctx->sentVerack = 1;
        ctx->lastblock = tip;
        array_add(f->m->connectedPeers, p);
        f->peers[k] = p;
        f->infos[k] = info;
    }
    f->peerCount = SYNCED_PEERS;
    f->m->downloadPeer = f->peers[0];
    f->m->syncStartHeight = tip - 1000;   /* a sync in progress */

    MGR_LOCK(f->m);
    _BRPeerManagerRecomputeEstimatedHeight(f->m);
    MGR_UNLOCK(f->m);
}

/* What the chain-download completion sites do (_peerRelayedBlock at block->height == estimatedHeight). */
static void completeSync(Fx *f)
{
    MGR_LOCK(f->m);
    _BRPeerManagerLoadMempools(f->m);
    MGR_UNLOCK(f->m);
}

typedef void (*PongFn)(void *, int);
static PongFn queuedPong(BRPeer *p)
{
    BRPeerContext *ctx = (BRPeerContext *)p;
    return (array_count(ctx->pongCallback) > 0) ? ctx->pongCallback[0] : NULL;
}

/* A peer's answers, through BRPeer.c's own message handlers. */
static void answerPong(BRPeer *p)
{
    uint8_t msg[sizeof(uint64_t)];
    UInt64SetLE(msg, ((BRPeerContext *)p)->nonce);
    _BRPeerAcceptPongMessage(p, msg, sizeof(msg));
}
static void answerNotfound(BRPeer *p, const UInt256 hashes[], size_t n)
{
    uint8_t msg[9 + 36*8];
    size_t off = BRVarIntSet(msg, sizeof(msg), n);
    for (size_t i = 0; i < n && i < 8; i++) {
        UInt32SetLE(&msg[off], inv_tx); off += sizeof(uint32_t);
        UInt256Set(&msg[off], hashes[i]); off += sizeof(UInt256);
    }
    _BRPeerAcceptNotfoundMessage(p, msg, off);
}
static void answerTx(BRPeer *p, const uint8_t *raw, size_t len)
{
    _BRPeerAcceptTxMessage(p, raw, len, 0);
}

static size_t peerCountFor(BRTxPeerList *list, UInt256 h) { return _BRTxPeerListCount(list, h); }

/* The sync completes and every peer takes the cleanup's first round trip (pong -> _postSyncDone ->
 * getdata + ping). Checks the cleanup's ping is what every peer now holds. */
static void reachCleanupRound(Fx *f, UInt256 watched)
{
    completeSync(f);
    int ok = 1;
    for (size_t k = 0; k < f->peerCount; k++) ok = ok && queuedPong(f->peers[k]) == _postSyncDone;
    check(ok, "the completion pinged every peer with _postSyncDone (compact-filter mode: no mempool request)");

    for (size_t k = 0; k < f->peerCount; k++) answerPong(f->peers[k]);
    ok = 1;
    for (size_t k = 0; k < f->peerCount; k++) ok = ok && queuedPong(f->peers[k]) == _requestUnrelayedTxGetdataDone;
    check(ok && f->syncStoppedSeen == 1, "each pong asked its peer for every unconfirmed record and queued the cleanup; "
                                         "sync completion reached the app once");
    check(peerCountFor(f->m->txRequests, watched) == f->peerCount, "every peer was asked for the receive");
}

static int utxoListed(BRWallet *w, UInt256 h)
{
    size_t n = BRWalletUTXOs(w, NULL, 0);
    BRUTXO *u = calloc(n ? n : 1, sizeof(*u));
    int found = 0;
    n = BRWalletUTXOs(w, u, n);
    for (size_t i = 0; i < n; i++) if (UInt256Eq(u[i].hash, h)) found = 1;
    free(u);
    return found;
}

/* NativeBridge_confirmTransaction's core step: promote a record the wallet still holds at
 * TX_UNCONFIRMED to the height a node reported. */
static int confirmLikeBridge(BRWallet *w, UInt256 h, uint32_t height, uint32_t ts)
{
    BRTransaction *existing = BRWalletTransactionForHash(w, h);
    if (! existing || existing->blockHeight != TX_UNCONFIRMED) return 0;
    BRWalletUpdateTransactionsUnproven(w, &h, 1, height, ts);
    return 1;
}

static void drainPongs(BRPeer *p)
{
    PongFn cb = NULL;
    void *info = NULL;
    while (_BRPeerPongPop((BRPeerContext *)p, &cb, &info)) { if (cb) cb(info, 0); cb = NULL; info = NULL; }
}

static void teardown(Fx *f)
{
    for (size_t k = 0; k < f->peerCount; k++) drainPongs(f->peers[k]);
    BRPeerManagerFree(f->m);   /* releases the connected peers */
    for (size_t k = 0; k < f->peerCount; k++) free(f->infos[k]);
    BRWalletFree(f->w);
}

static int held(Fx *f, UInt256 h) { return BRWalletTransactionForHash(f->w, h) != NULL; }

/* ---- dd_receive ----------------------------------------------------------------------------- */
static void scenario_dd_receive(void)
{
    printf("\n-- dd_receive: a DigiDollar receive saved while pending is kept by the tip cleanup --\n");
    OwnScripts own;
    ownScripts(&own);
    BRTransaction *D = makeRecord(REC_DD, &own, NULL, NULL);
    BRTransaction *P = makeRecord(REC_DGB, &own, NULL, NULL);
    BRTransaction *S = makeRecord(REC_PLAIN, &own, NULL, NULL);
    UInt256 dH = D->txHash, pH = P->txHash, sH = S->txHash;
    check(BRDigiDollarOutputAmount(D, 0) == DD_TO_US_CENTS && BRDigiDollarOutputAmount(D, 1) == DD_CHANGE_CENTS,
          "fixture: a DigiDollar transfer binding $50.00 to its output 0, which pays this wallet's taproot address");

    BRTransaction *recs[] = { P, S, D };
    size_t blobLen = 0;
    uint8_t *blob = saveSession(recs, 3, &blobLen);

    Fx f;
    memset(&f, 0, sizeof(f));
    relaunch(&f, blob, blobLen, 3);
    free(blob);
    BRTransaction *d = BRWalletTransactionForHash(f.w, dH);
    check(d && d->blockHeight == TX_UNCONFIRMED, "relaunch: the receive comes back at TX_UNCONFIRMED");
    check(d && BRWalletAmountSentByTx(f.w, d) == 0 && BRWalletAmountReceivedFromTx(f.w, d) == 0,
          "the receive moves no DGB to or from the wallet (sent 0, received 0)");
    check(d && BRWalletTransactionIsPending(f.w, d) && ! utxoListed(f.w, dH) && BRWalletDigiDollarBalance(f.w) == 0,
          "it is pending: no plain coin, no DigiDollar credit");
    uint64_t dgb = BRWalletBalance(f.w);
    check(dgb == DGB_TO_US, "the DGB receive is credited");

    startAtTip(&f);
    check(f.m->estimatedHeight == f.m->lastBlock->height, "the peers agree on the held tip: the manager is at the tip");
    reachCleanupRound(&f, dH);

    UInt256 asked[3] = { pH, sH, dH };
    for (size_t k = 0; k < f.peerCount; k++) {
        answerNotfound(f.peers[k], asked, 3);   /* no peer has any of them in its mempool */
        answerPong(f.peers[k]);                 /* the last one runs the cleanup with every gate open */
        if (k + 1 < f.peerCount) {
            check(held(&f, sH) && held(&f, dH), "nothing is removed before every peer has answered");
        }
    }
    check(peerCountFor(f.m->txRequests, dH) == 0 && peerCountFor(f.m->txRelays, dH) == 0,
          "no request is outstanding and no peer relays the receive");
    check(! held(&f, sH), "control: the plain record with no stake and no token was removed -- the cleanup ran");
    check(held(&f, pH), "the DGB receive is kept");
    d = BRWalletTransactionForHash(f.w, dH);
    check(d != NULL, "INVARIANT: the DigiDollar receive is kept (it carries a token output the wallet owns)");
    check(! d || (BRWalletTransactionIsPending(f.w, d) && ! utxoListed(f.w, dH)),
          "it is still pending and its output is in no plain-coin list");
    check(BRWalletBalance(f.w) == dgb && BRWalletDigiDollarBalance(f.w) == 0,
          "the DGB balance is unchanged and there is still no DigiDollar credit before it confirms");

    /* the app's confirmation reconcile: a node reports the height the receive confirmed at */
    uint32_t height = f.m->lastBlock->height - 20;
    check(height >= BRNetworkDigiDollarActivationHeight(), "fixture: that height is above the DigiDollar activation floor");
    check(confirmLikeBridge(f.w, dH, height, (uint32_t)time(NULL) - 300),
          "the confirmation reconcile finds the record still pending and promotes it");
    check(BRWalletDigiDollarBalance(f.w) == DD_TO_US_CENTS, "INVARIANT: the $50.00 credit is released");
    check(! utxoListed(f.w, dH) && BRWalletBalance(f.w) == dgb,
          "the DigiDollar output is held as DigiDollar, never as a plain coin");

    teardown(&f);
}

/* ---- asset receives ------------------------------------------------------------------------- */
static void assetScenario(int kind, int expectRed)
{
    OwnScripts own;
    ownScripts(&own);
    BRTransaction *A = makeRecord(kind, &own, NULL, NULL);
    BRTransaction *S = makeRecord(REC_PLAIN, &own, NULL, NULL);
    UInt256 aH = A->txHash, sH = S->txHash;
    check(BRTxOutputIsAsset(A, &A->outputs[0]) != 0, "fixture: output 0 carries the transferred DigiAsset unit");

    BRTransaction *recs[] = { S, A };
    size_t blobLen = 0;
    uint8_t *blob = saveSession(recs, 2, &blobLen);

    Fx f;
    memset(&f, 0, sizeof(f));
    relaunch(&f, blob, blobLen, 2);
    free(blob);
    BRTransaction *a = BRWalletTransactionForHash(f.w, aH);
    uint64_t stake = a ? BRWalletAmountReceivedFromTx(f.w, a) + BRWalletAmountSentByTx(f.w, a) : 0;
    if (expectRed) check(a && stake == 0, "the receive moves no DGB to or from the wallet");
    else check(a && stake == DA_ASSET_DUST_AMOUNT, "the receive pays the wallet its 700-dsat carrier: it has DGB stake");

    startAtTip(&f);
    reachCleanupRound(&f, aH);
    UInt256 asked[2] = { sH, aH };
    for (size_t k = 0; k < f.peerCount; k++) {
        answerNotfound(f.peers[k], asked, 2);
        answerPong(f.peers[k]);
    }
    check(! held(&f, sH), "control: the plain record with no stake and no token was removed -- the cleanup ran");
    check(held(&f, aH), expectRed ? "INVARIANT: the DigiAsset receive is kept (it carries a token output the wallet owns)"
                                  : "the DigiAsset receive is kept");
    teardown(&f);
}

static void scenario_asset_zero_carrier(void)
{
    printf("\n-- asset_zero_carrier: a DigiAsset receive on a carrier with no DGB is kept by the tip cleanup --\n");
    assetScenario(REC_ASSET_ZERO, 1);
}

static void scenario_asset_standard_carrier(void)
{
    printf("\n-- asset_standard_carrier: a DigiAsset receive on a standard carrier has DGB stake (both arms) --\n");
    assetScenario(REC_ASSET_STD, 0);
}

/* ---- gates ---------------------------------------------------------------------------------- */
static void scenario_gates(void)
{
    printf("\n-- gates: the cleanup's early returns hold in both arms --\n");
    OwnScripts own;
    ownScripts(&own);

    {   /* below the tip: a peer reports a newer block before the answers arrive */
        BRTransaction *D = makeRecord(REC_DD, &own, NULL, NULL), *S = makeRecord(REC_PLAIN, &own, NULL, NULL);
        UInt256 dH = D->txHash, sH = S->txHash;
        BRTransaction *recs[] = { S, D };
        size_t blobLen = 0;
        uint8_t *blob = saveSession(recs, 2, &blobLen);
        Fx f;
        memset(&f, 0, sizeof(f));
        relaunch(&f, blob, blobLen, 2);
        free(blob);
        startAtTip(&f);
        reachCleanupRound(&f, dH);
        for (size_t k = 0; k < f.peerCount; k++) ((BRPeerContext *)f.peers[k])->lastblock = f.m->lastBlock->height + 5;
        MGR_LOCK(f.m);
        _BRPeerManagerRecomputeEstimatedHeight(f.m);
        MGR_UNLOCK(f.m);
        check(f.m->estimatedHeight > f.m->lastBlock->height, "a newer block is reported: the manager is below the tip");
        UInt256 asked[2] = { sH, dH };
        for (size_t k = 0; k < f.peerCount; k++) { answerNotfound(f.peers[k], asked, 2); answerPong(f.peers[k]); }
        check(held(&f, sH) && held(&f, dH), "below the tip the cleanup removes nothing, not even the plain record");
        teardown(&f);
    }

    {   /* relayed: one peer still has the receive in its mempool and returns it */
        uint8_t *raw = NULL;
        size_t rawLen = 0;
        BRTransaction *D = makeRecord(REC_DD, &own, &raw, &rawLen), *S = makeRecord(REC_PLAIN, &own, NULL, NULL);
        UInt256 dH = D->txHash, sH = S->txHash;
        BRTransaction *recs[] = { S, D };
        size_t blobLen = 0;
        uint8_t *blob = saveSession(recs, 2, &blobLen);
        Fx f;
        memset(&f, 0, sizeof(f));
        relaunch(&f, blob, blobLen, 2);
        free(blob);
        startAtTip(&f);
        reachCleanupRound(&f, dH);
        UInt256 both[2] = { sH, dH };
        for (size_t k = 0; k < f.peerCount; k++) {
            if (k == 1) { answerTx(f.peers[k], raw, rawLen); answerNotfound(f.peers[k], both, 1); }
            else answerNotfound(f.peers[k], both, 2);
            answerPong(f.peers[k]);
        }
        check(peerCountFor(f.m->txRelays, dH) == 1, "one peer relays the receive");
        check(! held(&f, sH), "control: the plain record was removed -- the cleanup ran");
        check(held(&f, dH), "a record a peer still relays is kept");
        free(raw);
        teardown(&f);
    }
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
#ifdef TIPSWEEP_TOKEN_STAKE_UNFIXED
    printf("ARM: comparison (-DTIPSWEEP_TOKEN_STAKE_UNFIXED) -- the stake is DGB alone\n");
#else
    printf("ARM: shipped -- the stake counts a token output the wallet owns\n");
#endif
    static const struct { const char *name; void (*fn)(void); } scenarios[] = {
        { "dd_receive",             scenario_dd_receive },
        { "asset_zero_carrier",     scenario_asset_zero_carrier },
        { "asset_standard_carrier", scenario_asset_standard_carrier },
        { "gates",                  scenario_gates },
    };
    const char *want = (argc > 1) ? argv[1] : NULL;
    int ran = 0;

    for (size_t i = 0; i < sizeof(scenarios)/sizeof(*scenarios); i++) {
        if (want && strcmp(want, scenarios[i].name) != 0) continue;
        scenarios[i].fn();
        ran++;
    }
    if (! ran) { printf("unknown scenario: %s\n", want ? want : "(none)"); return 2; }

    printf(g_fail ? "\ntipsweep_token_stake_kat[%s]: %d CHECK(S) FAILED\n" : "\ntipsweep_token_stake_kat[%s]: ALL PASS\n",
           want ? want : "all", g_fail);
    return g_fail ? 1 : 0;
}
