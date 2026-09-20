/* publish_list_ownership_kat — every published transaction object has exactly one owner.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * The peer manager's publish list and the wallet each hold transaction objects. The rule: the
 * publish list releases only objects it OWNS, and the wallet's records are released only by the
 * wallet. An entry the list fetched from the wallet itself — a parent input walked from a send,
 * or a send re-added from the wallet by the relay path — is a wallet record: the list may drop
 * such an entry from its array but never releases the object. And when the wallet becomes the
 * owner of a listed object, the entry follows it: the wallet is then its one owner.
 *
 * THE RELEASE RULE AT THE CONFIRMATION AND INVALID-REQUEST SEAMS
 * -------------------------------------------------------------
 * Those two seams release an object iff the list OWNS it AND the wallet holds no record of its
 * hash. This is the conjunction of two conditions, so it releases only the list's own object and
 * only when the wallet keeps nothing equal — it never releases anything the wallet has an
 * equal-hash record of. The disconnect-cancellation seam releases iff the list owns the object.
 *
 * This KAT #includes BRPeerManager.c (and BRPeer.c) so it reaches the file-static owner paths and
 * drives the REAL publish, relay, request, confirm and disconnect functions. Same include shape as
 * relayed_header_lifetime_kat / orphan_set_limits_kat / tx_publish_ownership_kat.
 *
 * ARMS (run.sh builds all; convention: PRESENCE of a macro selects a comparison arm — the shipped
 * arm is built with NO -D at all, matching publish_cancel_survivor_kat):
 *   default:                          the shipped arm — the release rule above, and an entry's
 *                                     ownership follows its object into the wallet.
 *   -DPUBLISH_LIST_OWNERSHIP_UNFIXED: a shape without the rule at the release seams — release keyed
 *                                     off the wallet's knowledge of the hash instead of off ownership.
 *   -DPUBLISH_OWNED_FOLLOWS_UNFIXED:  a shape without the rule at the sites where the wallet becomes
 *                                     an object's owner — the entry does not follow the object.
 *
 * SCENARIOS (argv[1] selects one). Each header line states RED-THEN-GREEN (a comparison arm is
 * reported by AddressSanitizer while the shipped arm is clean) or GUARD (the shipped arm asserts the
 * guarantee directly; where a comparison arm cannot express the shape, run.sh's mutant proof does).
 *   survives  [RED-THEN-GREEN, PUBLISH_LIST_OWNERSHIP_UNFIXED] — a wallet record listed as a send's
 *             parent is released only by the wallet, so it is still serialisable after a broadcast
 *             times out.
 *   confirm_released [GUARD] — a list-owned object whose hash the wallet does NOT hold is released
 *             exactly once at confirmation (poison check). Agrees with the comparison arm.
 *   confirm_window   [GUARD; mutant] — with the wallet holding an equal-hash record, confirmation
 *             leaves the wallet's record intact, and an object the request path served stays live
 *             across confirmation. run.sh proves it red by mutating the seam.
 *   invalid_released [GUARD] — a list-owned object whose hash the wallet does NOT hold is released
 *             exactly once on an invalid request (poison check).
 *   invalid_survives [GUARD; mutant] — a wallet-owned listed record survives an invalid request and
 *             is still readable. run.sh proves it red by mutating the seam to release regardless of
 *             ownership.
 *   requested [RED-THEN-GREEN, PUBLISH_OWNED_FOLLOWS_UNFIXED] — a send the wallet takes back on a
 *             getdata request is the wallet's, even after a later publish adopts a callback and the
 *             broadcast times out.
 *   hastx     [RED-THEN-GREEN, PUBLISH_OWNED_FOLLOWS_UNFIXED] — the same guarantee reached through
 *             the inv (has-tx) path.
 *   relay     [RED-THEN-GREEN, PUBLISH_OWNED_FOLLOWS_UNFIXED] — a send re-added to the list from the
 *             wallet by the relay path is the wallet's, so it survives a broadcast timeout.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

/* ASan runtime query: nonzero when the byte at addr is in a poisoned (e.g. released) region. It
 * reads shadow memory and never dereferences addr, so it is safe to ask about a released object. */
int __asan_address_is_poisoned(void const volatile *addr);

/* Reaching BRPeerContext (no public status/verack setter) and BRPeerManagerStruct needs both .c
 * files in one translation unit. Both define a file-static _dummyThreadCleanup; BRPeer.c's copy is
 * renamed by a preprocessor substitution scoped to its #include only. run.sh drops both from the
 * link line. Same pattern as tx_publish_ownership_kat / publish_cancel_survivor_kat. */
#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"

static uint32_t newest_checkpoint_height_or(uint32_t fallback);

static int g_fail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

static int g_cbSentinel = 0xBEEF;
static void recordPublishResult(void *info, int error) { (void)info; (void)error; }

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

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

/* A wallet-recognised send paying `spk` (a script captured once so two builds are byte-identical:
 * registration advances the receive window, so building an equal send afterwards from a fresh
 * address would change its identity, and re-publishing the SAME send needs an identical object). */
static BRTransaction *makeOwnedSend(const uint8_t *spk, size_t spkLen, uint8_t tag)
{
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);                 /* token output (ours) */
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));           /* "DD" OP_RETURN */
    finalizeTxHash(tx);
    return tx;
}

/* A child whose only input names `parentHash`. */
static BRTransaction *makeChildOf(const uint8_t *spk, size_t spkLen, UInt256 parentHash)
{
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, parentHash, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

/* A plain send spending `prev`:0 and paying `amt` to `spk`. */
static BRTransaction *mkTx(const uint8_t *spk, size_t spkLen, UInt256 prev, uint64_t amt)
{
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amt, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

/* The register-then-hand-off of the send functions: register an independent COPY into the wallet,
 * return the ORIGINAL for the caller to hand to the publisher. */
static BRTransaction *registerCopyHandOff(BRWallet *w, BRTransaction *tx)
{
    if (! tx->timestamp) tx->timestamp = (uint32_t)time(NULL);
    BRTransaction *copy = BRTransactionCopy(tx);
    if (copy) BRWalletRegisterTransaction(w, copy);
    return tx;
}

static BRPeer *addPeer(BRPeerManager *m, uint8_t addrByte, int handshook)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = addrByte;
    p->port = 12024;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->gotVerack = handshook ? 1 : 0;
    array_add(m->connectedPeers, p);
    return p;
}

static void killPeer(BRPeerManager *m, BRPeer *peer, int error)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = peer; info.manager = m;
    _peerDisconnected(&info, error);   /* frees peer; does not free info */
}

/* Serialize every wallet record. This reads each object the wallet holds, so it is a live read of
 * a record's memory. Returns 1 when a record for `hash` is present and round-trips. */
static int walletRecordRoundTrips(BRWallet *w, UInt256 hash)
{
    size_t need = BRWalletSerializeTransactions(w, NULL, 0);
    if (need <= 16) return 0;
    uint8_t *buf = malloc(need);
    size_t wrote = BRWalletSerializeTransactions(w, buf, need);
    int ok = 0, off = 4;   /* 4-byte count header, then (len, height, timestamp, data) per tx */
    while (off + 12 <= (int)wrote) {
        uint32_t txlen; memcpy(&txlen, buf + off, 4);
        off += 12;
        if (off + (int)txlen > (int)wrote) break;
        BRTransaction *rt = BRTransactionParse(buf + off, txlen);
        if (rt) { if (UInt256Eq(rt->txHash, hash)) ok = 1; BRTransactionFree(rt); }
        off += txlen;
    }
    free(buf);
    return ok;
}

/* Serialize every wallet record purely to force a read of each held object; returns nonzero on
 * any output. Used where the record does not sort first, so the exact hash match is not the point. */
static int walletRecordsReadable(BRWallet *w)
{
    size_t need = BRWalletSerializeTransactions(w, NULL, 0);
    if (need == 0) return 0;
    uint8_t *buf = malloc(need);
    size_t wrote = BRWalletSerializeTransactions(w, buf, need);
    free(buf);
    return wrote > 0;
}

/* Read a served object: size-check, then serialise. A live read of tx's memory. */
static int readObjectLikePeer(BRTransaction *tx)
{
    if (! tx) return 0;
    size_t sz = BRTransactionSize(tx);
    if (sz >= TX_MAX_SIZE) return 0;
    uint8_t buf[BRTransactionSerialize(tx, NULL, 0)];
    size_t n = BRTransactionSerialize(tx, buf, sizeof(buf));
    (void)tx->is_dandelion;
    return n > 0;
}

/* ---- survives: a wallet record listed as a send's parent is the wallet's to release ---------- */
static void scenario_survives(void)
{
    printf("\n-- survives: a wallet record listed as a send's parent outlives a broadcast timeout --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }

    /* Capture the receive script once so A and its re-publish are byte-identical. */
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* send A: register a copy, publish the original, one handshook download peer */
    m->isConnected = 1;
    m->connectFailureCount = MAX_CONNECT_FAILURES - 1;
    BRPeer *dl = addPeer(m, 0x01, 1);
    m->downloadPeer = dl;
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), &g_cbSentinel, recordPublishResult);

    /* the download peer drops at the failure ceiling */
    killPeer(m, dl, ECONNRESET);
    check(BRWalletTransactionForHash(w, aHash) != NULL, "the wallet keeps its own A record after A is cancelled");
    check(array_count(m->publishedTx) == 0, "A left the publish list when it was cancelled");

    /* publish child B: its input names A, so the parent walk lists the wallet's A record owned=0 */
    m->isConnected = 1;
    m->connectFailureCount = MAX_CONNECT_FAILURES;   /* suppress the tail reconnect */
    m->downloadPeer = NULL;
    BRPeer *only = addPeer(m, 0x02, 1);
    BRTransaction *B = makeChildOf(spk, spkLen, aHash);
    BRPeerManagerPublishTx(m, B, &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 2, "publishing B lists B and the wallet's A record");

    /* re-publish A (byte-identical): the wallet-owned entry adopts a callback but keeps its owner */
    BRTransaction *A2 = makeOwnedSend(spk, spkLen, 0x11);
    BRPeerManagerPublishTx(m, A2, &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 2, "re-publishing A adopts the existing entry, adds no row");

    /* broadcast timeout, no surviving peer */
    killPeer(m, only, ETIMEDOUT);
    check(array_count(m->publishedTx) == 0, "the timeout clears the publish list");

    /* THE GUARANTEE: the wallet still owns its A record and it is still serialisable. */
    check(BRWalletTransactionForHash(w, aHash) != NULL, "the wallet STILL holds its A record");
    check(walletRecordRoundTrips(w, aHash), "the wallet's A record round-trips after the timeout");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- confirm_released: the list releases its own object exactly once at confirmation ---------- */
static void scenario_confirm_released(void)
{
    printf("\n-- confirm_released: a list-owned object the wallet has no equal record of is released once --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }

    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* The list owns A; the wallet holds NO record of its hash. */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet holds no record of A");
    check(array_count(m->publishedTx) == 1, "the send is on the publish list");

    /* A confirms. The wallet has nothing equal, so the list releases its own object here. */
    uint32_t confirmedHeight = newest_checkpoint_height_or(25000000) + 1000;
    _BRPeerManagerUpdateTx(m, &aHash, 1, confirmedHeight, (uint32_t)time(NULL));

    check(__asan_address_is_poisoned(A) != 0, "the list released its own object at confirmation");
    check(array_count(m->publishedTx) == 0, "the confirmed send left the publish list");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- confirm_window: an object the request path served stays live across confirmation while
 *      the wallet holds an equal record. -------------------------------------------------------- */
static void scenario_confirm_window(void)
{
    printf("\n-- confirm_window: with an equal wallet record, an object read after confirmation is live --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }

    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* Register the wallet's own copy; the list owns the ORIGINAL. The wallet holds an equal-hash
     * record that is a DISTINCT object. */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    (void)registerCopyHandOff(w, A);
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);
    check(BRWalletTransactionForHash(w, aHash) != A, "the wallet record and the list's object are distinct");

    /* Obtain the object from the real request path. The wallet already holds an equal record, so
     * it does not take the list's object; the list keeps it. */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *served = _peerRequestedTx(&info, aHash);
    check(served == A, "the request serves the list's own object");
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].owned == 1,
          "the list keeps ownership of its object when the wallet has an equal record");

    /* Then the confirmation runs. The wallet holds an equal record, so the object is NOT released. */
    uint32_t h = newest_checkpoint_height_or(25000000) + 1000;
    _BRPeerManagerUpdateTx(m, &aHash, 1, h, (uint32_t)time(NULL));

    /* Then the served object is read. It is still live. */
    check(__asan_address_is_poisoned(served) == 0, "the served object is live after confirmation");
    check(readObjectLikePeer(served), "the served object reads cleanly after confirmation");
    check(walletRecordRoundTrips(w, aHash), "the wallet's own record round-trips after confirmation");

    BRPeerManagerFree(m);
    BRWalletFree(w);
    /* This scenario runs with detect_leaks off. */
}

/* ---- invalid_released: the list releases its own object exactly once on an invalid request ----- */
static void scenario_invalid_released(void)
{
    printf("\n-- invalid_released: a list-owned object the wallet has no equal record of is released once --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* fund F, spend F:0 via WB (registered) so F:0 is a spent output */
    UInt256 prev; memset(prev.u8, 0x31, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, F);
    BRTransaction *WB = mkTx(spk, spkLen, F->txHash, 40000);
    WB->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, WB);

    /* A spends the same F:0 but is NOT a wallet record — so it is invalid, and the list owns it */
    BRTransaction *A = mkTx(spk, spkLen, F->txHash, 30000);
    A->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = A->txHash;
    check(! BRWalletTransactionIsValid(w, A), "A is an invalid double-spend");
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet holds no record of A");

    /* an invalid getdata request reaches the invalid-request seam */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *served = _peerRequestedTx(&info, aHash);
    check(served == NULL, "an invalid request serves nothing");
    check(__asan_address_is_poisoned(A) != 0, "the list released its own object on the invalid request");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- invalid_survives: a wallet-owned listed record survives an invalid request --------------- */
static void scenario_invalid_survives(void)
{
    printf("\n-- invalid_survives: a wallet-owned listed record is the wallet's on an invalid request --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* fund F; register WB (the winner) then WA (the loser) both spending F:0, so WA is a wallet
     * record the wallet marks invalid */
    UInt256 prev; memset(prev.u8, 0x41, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, F);
    BRTransaction *WB = mkTx(spk, spkLen, F->txHash, 40000);
    WB->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, WB);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 30000);
    WA->timestamp = (uint32_t)time(NULL) + 1;
    BRWalletRegisterTransaction(w, WA);
    UInt256 aHash = WA->txHash;
    check(BRWalletTransactionForHash(w, aHash) == WA, "WA is the wallet's own record");
    check(! BRWalletTransactionIsValid(w, WA), "WA is an invalid double-spend");

    /* WA is listed as the wallet's record (owned = 0) — the state a relay-add, or a wallet taking a
     * listed object on a request, leaves. */
    _BRPeerManagerAddTxToPublishListOwned(m, WA, NULL, NULL, 0);
    int found = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (m->publishedTx[i].tx == WA) { found = 1; check(m->publishedTx[i].owned == 0,
            "the listed record is the wallet's (owned = 0)"); }
    check(found, "WA is on the publish list as the wallet's record");

    /* an invalid getdata request reaches the invalid-request seam */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    (void)_peerRequestedTx(&info, aHash);

    /* THE GUARANTEE: the wallet's record is untouched and still readable. */
    check(__asan_address_is_poisoned(WA) == 0, "the wallet's record was not released");
    check(BRWalletTransactionForHash(w, aHash) == WA, "the wallet still holds WA");
    check(readObjectLikePeer(WA), "the wallet's record reads cleanly after the invalid request");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- requested: a send the wallet takes back on a getdata request is the wallet's -------------- */
static void scenario_requested(void)
{
    printf("\n-- requested: a send the wallet takes back on a getdata request is the wallet's --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* wallet holds its own copy; the list owns object A */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    (void)registerCopyHandOff(w, A);
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);

    /* the wallet drops its own record; the list still owns A */
    BRWalletRemoveTransaction(w, aHash);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet no longer holds a record for A");

    /* a getdata request drives the real _peerRequestedTx, which registers the list's object into
     * the wallet — the wallet becomes A's owner and the entry follows it */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *served = _peerRequestedTx(&info, aHash);
    check(served == A, "the request serves the list's object");
    check(BRWalletTransactionForHash(w, aHash) == A, "the wallet now holds A");
    check(m->publishedTx[0].owned == 0, "the entry follows the object to the wallet's ownership");

    /* a later publish of the same send; the pending entry adopts a callback but keeps its owner */
    BRTransaction *A2 = makeOwnedSend(spk, spkLen, 0x11);
    BRPeerManagerPublishTx(m, A2, &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 1, "the re-publish adopts the existing entry");

    /* broadcast timeout: the disconnect-cancellation seam must leave the wallet's record alone */
    killPeer(m, p, ETIMEDOUT);
    check(array_count(m->publishedTx) == 0, "the timeout clears the publish list");
    check(__asan_address_is_poisoned(A) == 0, "the wallet's record was not released");
    check(BRWalletTransactionForHash(w, aHash) != NULL, "the wallet still holds its A record");
    check(walletRecordRoundTrips(w, aHash), "the wallet's A record round-trips after the timeout");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- hastx: the same guarantee reached through the inv (has-tx) path -------------------------- */
static void scenario_hastx(void)
{
    printf("\n-- hastx: a send the wallet takes back on an inv is the wallet's --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    (void)registerCopyHandOff(w, A);
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);
    BRWalletRemoveTransaction(w, aHash);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet no longer holds a record for A");

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerHasTx(&info, aHash);
    check(BRWalletTransactionForHash(w, aHash) == A, "the wallet now holds A");
    check(m->publishedTx[0].owned == 0, "the entry follows the object to the wallet's ownership");

    BRTransaction *A2 = makeOwnedSend(spk, spkLen, 0x11);
    BRPeerManagerPublishTx(m, A2, &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 1, "the re-publish adopts the existing entry");

    killPeer(m, p, ETIMEDOUT);
    check(array_count(m->publishedTx) == 0, "the timeout clears the publish list");
    check(__asan_address_is_poisoned(A) == 0, "the wallet's record was not released");
    check(walletRecordRoundTrips(w, aHash), "the wallet's A record round-trips after the timeout");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* ---- relay: a send re-added from the wallet by the relay path is the wallet's ----------------- */
static void scenario_relay(void)
{
    printf("\n-- relay: a send re-added from the wallet by the relay path is the wallet's --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x21, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(w, F);                       /* funding, wallet-owned */
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000); /* the wallet's record of send A */
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransaction(w, WA);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *R = mkTx(spk, spkLen, F->txHash, 40000);  /* the wire copy the relay path delivers */
    _peerRelayedTx(&info, R);

    int found = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (m->publishedTx[i].tx == WA) { found = 1; check(m->publishedTx[i].owned == 0,
            "the relayed send is listed as the wallet's record"); }
    check(found, "the relay path listed the wallet's record of A");

    /* re-publish the same send so the entry adopts a callback, then time the broadcast out */
    BRTransaction *WA2 = mkTx(spk, spkLen, F->txHash, 40000);
    WA2->timestamp = (uint32_t)time(NULL);
    BRPeerManagerPublishTx(m, WA2, &g_cbSentinel, recordPublishResult);

    killPeer(m, p, ETIMEDOUT);
    check(__asan_address_is_poisoned(WA) == 0, "the wallet's record was not released");
    check(BRWalletTransactionForHash(w, aHash) == WA, "the wallet still holds WA");
    check(walletRecordsReadable(w), "the wallet's records still read cleanly after the timeout");

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* The rig's confirmed height must clear the hardcoded checkpoint table so the wallet accepts it. */
static uint32_t newest_checkpoint_height_or(uint32_t fallback)
{
    if (BRMainNetParams.checkpointsCount == 0) return fallback;
    return BRMainNetParams.checkpoints[BRMainNetParams.checkpointsCount - 1].height;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);

#if defined(PUBLISH_LIST_OWNERSHIP_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_LIST_OWNERSHIP_UNFIXED) — a shape without the rule at the "
           "release seams\n");
#elif defined(PUBLISH_OWNED_FOLLOWS_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_OWNED_FOLLOWS_UNFIXED) — a shape without the rule where the "
           "wallet becomes an object's owner\n");
#else
    printf("ARM: shipped — the publish list releases only objects it owns and the wallet keeps no "
           "equal record of\n");
#endif

    const char *which = (argc > 1) ? argv[1] : "all";
    int ran = 0;
    if (! strcmp(which, "survives")         || ! strcmp(which, "all")) { scenario_survives();         ran = 1; }
    if (! strcmp(which, "confirm_released") || ! strcmp(which, "all")) { scenario_confirm_released(); ran = 1; }
    if (! strcmp(which, "confirm_window")   || ! strcmp(which, "all")) { scenario_confirm_window();   ran = 1; }
    if (! strcmp(which, "invalid_released") || ! strcmp(which, "all")) { scenario_invalid_released(); ran = 1; }
    if (! strcmp(which, "invalid_survives") || ! strcmp(which, "all")) { scenario_invalid_survives(); ran = 1; }
    if (! strcmp(which, "requested")        || ! strcmp(which, "all")) { scenario_requested();        ran = 1; }
    if (! strcmp(which, "hastx")            || ! strcmp(which, "all")) { scenario_hastx();            ran = 1; }
    if (! strcmp(which, "relay")            || ! strcmp(which, "all")) { scenario_relay();            ran = 1; }
    if (! ran) {
        printf("usage: %s [survives|confirm_released|confirm_window|invalid_released|"
               "invalid_survives|requested|hastx|relay]\n", argv[0]);
        return 2;
    }

    printf("\npublish_list_ownership_kat[%s]: %s\n", which, g_fail ? "FAIL" : "ALL PASS");
    return g_fail ? 1 : 0;
}
