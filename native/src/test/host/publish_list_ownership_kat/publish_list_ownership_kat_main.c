/* publish_list_ownership_kat — every published transaction object has exactly one owner, and every
 * publish callback is answered exactly once.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * The peer manager's publish list and the wallet each hold transaction objects. The rule: the
 * publish list releases only objects it OWNS, and the wallet's records are released only by the
 * wallet. Ownership is decided exactly when an entry is added: an object the wallet already holds
 * as its record is the wallet's (the classic register-then-publish caller), a distinct object is the
 * list's. An entry the list fetched from the wallet itself — a parent input walked from a send, or a
 * send re-added from the wallet by the relay path — is a wallet record. When the wallet becomes the
 * owner of a listed object, the entry follows it. Every drop site (confirmation, an invalid request,
 * cancellation, a wallet-side removal, teardown) releases by ownership alone, and answers a pending
 * callback exactly once — after the manager lock is released. A publish of a transaction whose
 * earlier publish is still pending is answered EALREADY; a peer's rejection that every honest node
 * would give resolves a pending publish with EINVAL once, unless another peer has relayed it. The
 * relay path releases the parsed object it was handed when the wallet already holds the hash. The
 * stem decision lives on the entry and is stamped on the served copy only.
 *
 * This KAT #includes BRPeerManager.c (and BRPeer.c) so it reaches the file-static owner paths and
 * drives the REAL publish, relay, request, reject, confirm, remove and disconnect functions. Same
 * include shape as relayed_header_lifetime_kat / orphan_set_limits_kat / tx_publish_ownership_kat.
 *
 * ARMS (run.sh builds all; convention: PRESENCE of a macro selects a comparison arm — the shipped
 * arm is built with NO -D at all, matching publish_cancel_survivor_kat):
 *   default:                          the shipped arm — the rules above.
 *   -DPUBLISH_LIST_OWNERSHIP_UNFIXED: release keyed off the wallet's knowledge of the hash instead of
 *                                     off ownership (a cancellation released every entry's object).
 *   -DPUBLISH_OWNED_FOLLOWS_UNFIXED:  the entry does not follow its object when the wallet becomes
 *                                     the owner (nor at add time).
 *   -DPUBLISH_OWNED_AT_ADD_UNFIXED:   ownership is not decided at add time: a caller's owned = 1 is
 *                                     kept even for the wallet's own record.
 *   -DPUBLISH_REMOVE_PURGE_UNFIXED:   a wallet-side removal leaves the list entries that named the
 *                                     released records.
 *   -DPUBLISH_REMOVE_OWNED_SKIP_UNFIXED: a wallet-side removal keeps every entry the list owns — the
 *                                     wallet's own publish shape — so a removed send stays pending,
 *                                     is served again and comes back into the wallet.
 *   -DPUBLISH_SERVED_COPY_UNFIXED:    a getdata request returns the owned object, not a private copy.
 *   -DPUBLISH_ANSWER_ONCE_UNFIXED:    confirmation, a wallet-side removal and teardown drop entries
 *                                     without answering; a duplicate publish drops the new context.
 *   -DPUBLISH_RELAY_OBJECT_UNFIXED:   the relay path leaves the parsed object unreleased when the
 *                                     wallet already holds the hash.
 *   -DPUBLISH_STEM_TYPE_UNFIXED:      the stem type is written on the handed/listed object and a
 *                                     duplicate stem's object has no owner.
 *   -DPUBLISH_REJECT_UNFIXED:         a peer's rejection never resolves a pending publish.
 *   -DUNRELAYED_SWEEP_RELOOKUP_UNFIXED: the unrelayed-tx cleanup walks the wallet pointers it fetched
 *                                     before its removals, so a dependant released by one is read.
 *
 * SCENARIOS (argv[1] selects one). run.sh states for each whether it is RED-THEN-GREEN against a
 * comparison arm (reported by AddressSanitizer, by LeakSanitizer, or failing its own assertion),
 * or a GUARD (the shipped arm asserts the guarantee; a mutated seam proves it is not vacuous).
 * The LeakSanitizer oracle: every scenario but the two-thread race tears everything down, so the
 * shipped arm must be leak-clean, and an owner-less object or an unanswered heap context is a
 * leak report.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>

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

static int g_fail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

static int g_cbSentinel = 0xBEEF;
static void recordPublishResult(void *info, int error) { (void)info; (void)error; }

/* The answered-once oracle: a heap context released by the callback, a fire counter and the last
 * verdict. An unanswered context is a leak (LeakSanitizer); a second answer is a double release
 * (AddressSanitizer). */
static int g_cbCount = 0, g_lastError = -1;
static void countingResult(void *info, int error) { g_cbCount++; g_lastError = error; free(info); }
static void *newCtx(void) { return calloc(1, 65); }
static void resetCounter(void) { g_cbCount = 0; g_lastError = -1; }

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
/* Registration: these fixtures stand in for the wallet's own sends and their funding, with placeholder
 * signatures, so they register through BRWalletRegisterTransactionTrusted -- the path the bridge's
 * _registerWalletCopy and the peer manager use for a tx this wallet signed. (The checked path refuses
 * an unconfirmed spend of a wallet coin without a valid signature; unconfirmed_spend_sig_kat.) */
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
    if (copy) BRWalletRegisterTransactionTrusted(w, copy);
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

/* The fixture's peers have no thread. A live peer thread drains its queued pong callbacks with
 * success = 0 before it reports the disconnect (BRPeer.c, _peerThreadRoutine), which is what
 * releases the BRPeerCallbackInfo the publish and unrelayed-tx paths hand to BRPeerSendPing. Do the
 * same here, so what the leak checker reports is the core's, never the fixture's. */
static void drainPongs(BRPeer *p)
{
    void (*cb)(void *, int) = NULL;
    void *info = NULL;
    while (_BRPeerPongPop((BRPeerContext *)p, &cb, &info)) { if (cb) cb(info, 0); cb = NULL; info = NULL; }
}

static void drainAllPongs(BRPeerManager *m)
{
    for (size_t i = array_count(m->connectedPeers); i > 0; i--) drainPongs(m->connectedPeers[i - 1]);
}

static void killPeer(BRPeerManager *m, BRPeer *peer, int error)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = peer; info.manager = m;
    drainPongs(peer);
    _peerDisconnected(&info, error);   /* frees peer; does not free info */
}

/* Every scenario ends here, so the leak checker sees a complete lifetime. */
static void teardown(BRPeerManager *m, BRWallet *w)
{
    drainAllPongs(m);
    BRPeerManagerFree(m);
    BRWalletFree(w);
}

/* Entries naming `h`, by the cached hash (never by reading a listed object). */
static int listedCount(BRPeerManager *m, UInt256 h)
{
    int n = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++) if (UInt256Eq(m->publishedTxHashes[i], h)) n++;
    return n;
}

static int entryPending(BRPeerManager *m, UInt256 h)
{
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (UInt256Eq(m->publishedTxHashes[i], h)) return m->publishedTx[i].callback != NULL;
    return 0;
}

static int entryStemming(BRPeerManager *m, UInt256 h)
{
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (UInt256Eq(m->publishedTxHashes[i], h)) return m->publishedTx[i].stemming;
    return 0;
}

static uint32_t newest_checkpoint_height_or(uint32_t fallback);

/* A block confirms `h`: what the block paths do — the confirmation under the lock, then the
 * answer flush after the unlock. */
static void confirmHash(BRPeerManager *m, UInt256 h)
{
    uint32_t height = newest_checkpoint_height_or(25000000) + 1000;
    _BRPeerManagerUpdateTx(m, &h, 1, height, (uint32_t)time(NULL));
    _BRPeerManagerFlushAnswers(m);
}

/* A getdata from `p` for `h`, answered from the real request path; the served copy is returned to
 * the caller, who releases it (as BRPeer.c's handler does). */
static BRTransaction *serve(BRPeerManager *m, BRPeer *p, UInt256 h)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    return _peerRequestedTx(&info, h);
}

static void relayFrom(BRPeerManager *m, BRPeer *p, BRTransaction *parsed)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerRelayedTx(&info, parsed);
}

static void rejectFrom(BRPeerManager *m, BRPeer *p, UInt256 h, uint8_t code)
{
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    _peerRejectedTx(&info, h, code);
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

    teardown(m, w);
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

    teardown(m, w);
}

/* ---- confirm_released_copy: the wallet holds its copy, the list owns the original — confirmation
 *      releases the list's object exactly once and answers the publish with 0. ------------------ */
static void scenario_confirm_released_copy(void)
{
    printf("\n-- confirm_released_copy: with an equal wallet record, the list's own object is released at confirmation --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();

    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* Register the wallet's own copy; the list owns the ORIGINAL, with a pending callback. */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    (void)registerCopyHandOff(w, A);
    _BRPeerManagerAddTxToPublishList(m, A, newCtx(), countingResult);
    check(BRWalletTransactionForHash(w, aHash) != A, "the wallet record and the list's object are distinct");
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].owned == 1,
          "the list owns its distinct object (owned = 1)");

    /* A getdata is answered from a private copy; the list keeps its object. */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRTransaction *served = serve(m, p, aHash);
    check(served != NULL && served != A, "the request serves a private copy, not the list's object");
    if (served) { check(readObjectLikePeer(served), "the served private copy reads cleanly"); BRTransactionFree(served); }
    check(g_cbCount == 1 && g_lastError == 0, "the request answered the publish once with 0");

    /* Confirmation: the entry leaves the list, the list's own object is released exactly once, the
     * wallet's record is untouched. run.sh's mut_conjunct mutant (release only when the wallet
     * holds no equal record) leaves A owner-less here — a LeakSanitizer report. */
    confirmHash(m, aHash);
    check(__asan_address_is_poisoned(A) != 0, "the list released its own object at confirmation");
    check(array_count(m->publishedTx) == 0, "the confirmed send left the publish list");
    check(array_count(m->pendingAnswers) == 0, "no answer is left queued after the flush");
    check(walletRecordRoundTrips(w, aHash), "the wallet's own record round-trips after confirmation");

    teardown(m, w);
    check(g_cbCount == 1, "the publish was answered exactly once in all");
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
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WB = mkTx(spk, spkLen, F->txHash, 40000);
    WB->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, WB);

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

    teardown(m, w);
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
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WB = mkTx(spk, spkLen, F->txHash, 40000);
    WB->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, WB);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 30000);
    WA->timestamp = (uint32_t)time(NULL) + 1;
    BRWalletRegisterTransactionTrusted(w, WA);
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
    BRTransaction *served = _peerRequestedTx(&info, aHash);
    if (served) BRTransactionFree(served);   /* the served private copy is the handler's to release */

    /* THE GUARANTEE: the wallet's record is untouched and still readable. run.sh's mut_invalid
     * mutant releases regardless of ownership, freeing WA here; the read below then faults. */
    check(__asan_address_is_poisoned(WA) == 0, "the wallet's record was not released");
    check(BRWalletTransactionForHash(w, aHash) == WA, "the wallet still holds WA");
    check(readObjectLikePeer(WA), "the wallet's record reads cleanly after the invalid request");

    teardown(m, w);
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
    check(served != NULL && served != A, "the request serves a private copy, not the list's object");
    if (served) BRTransactionFree(served);   /* the getdata handler owns the served copy */
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

    teardown(m, w);
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

    teardown(m, w);
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
    BRWalletRegisterTransactionTrusted(w, F);                       /* funding, wallet-owned */
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000); /* the wallet's record of send A */
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);

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

    teardown(m, w);
}

/* ---- remove_relay: a wallet record listed by the relay path is dropped from the list when the
 *      wallet removes it through the one manager-locked removal, so no reader touches a released
 *      record. ------------------------------------------------------------------------------- */
static void scenario_remove_relay(void)
{
    printf("\n-- remove_relay: a relay-listed record removed through the manager leaves the list in agreement --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x21, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->blockHeight = 1000;                                   /* confirmed funding, so the parent walk skips it */
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);                       /* funding, wallet-owned */
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000); /* the wallet's record of send A */
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *R = mkTx(spk, spkLen, F->txHash, 40000);  /* the wire copy the relay path delivers */
    _peerRelayedTx(&info, R);

    int listed = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (m->publishedTx[i].tx == WA) { listed = 1; check(m->publishedTx[i].owned == 0,
            "the relay listed the wallet's record of A (owned = 0)"); }
    check(listed, "the relay path listed the wallet's record of A");

    /* the wallet removes A through the one manager-locked removal */
    BRPeerManagerRemoveTransaction(m, aHash);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet released its A record");
    int aStillListed = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (UInt256Eq(m->publishedTxHashes[i], aHash)) aStillListed = 1;
    check(! aStillListed, "the removed record left the publish list");

    /* the readers after the removal — a getdata for the hash (which reads the entry's object to
     * judge it) and the confirmation loop — must touch no released record */
    BRTransaction *served = serve(m, p, aHash);
    if (served) BRTransactionFree(served);
    confirmHash(m, aHash);
    check(1, "a request and a confirmation after the removal read no released record");

    teardown(m, w);
}

/* ---- remove_parent: removing a parent frees it and its listed dependant child; the one
 *      manager-locked removal drops BOTH list entries. ---------------------------------------- */
static void scenario_remove_parent(void)
{
    printf("\n-- remove_parent: removing a parent drops it and its listed dependant child from the list --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x31, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->blockHeight = 1000;                                    /* confirmed funding, so the parent walk skips it */
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *P = mkTx(spk, spkLen, F->txHash, 40000);   /* wallet's parent send */
    P->timestamp = (uint32_t)time(NULL);
    UInt256 pHash = P->txHash;
    BRWalletRegisterTransactionTrusted(w, P);
    BRTransaction *C = mkTx(spk, spkLen, pHash, 30000);       /* wallet's child, spends P:0 */
    C->timestamp = (uint32_t)time(NULL);
    UInt256 cHash = C->txHash;
    BRWalletRegisterTransactionTrusted(w, C);

    /* the relay path lists the wallet's child record (owned = 0), and its parent walk lists the
     * wallet's parent record (owned = 0). The confirmed funding F is skipped by the parent walk. */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x08, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *R = mkTx(spk, spkLen, pHash, 30000);       /* wire copy of the child */
    _peerRelayedTx(&info, R);
    check(array_count(m->publishedTx) == 2, "the relay listed the wallet's child and its parent");

    /* remove the parent: the wallet frees P and its dependant child C; the list drops both entries */
    BRPeerManagerRemoveTransaction(m, pHash);
    check(BRWalletTransactionForHash(w, pHash) == NULL, "the wallet released the parent");
    check(BRWalletTransactionForHash(w, cHash) == NULL, "the wallet released the dependant child");
    int anyListed = 0;
    for (size_t i = 0; i < array_count(m->publishedTx); i++)
        if (UInt256Eq(m->publishedTxHashes[i], pHash) || UInt256Eq(m->publishedTxHashes[i], cHash)) anyListed = 1;
    check(! anyListed, "both removed records left the publish list");

    /* the readers after the removal — a getdata for each hash and the confirmation loop */
    BRTransaction *served = serve(m, p, cHash);
    if (served) BRTransactionFree(served);
    served = serve(m, p, pHash);
    if (served) BRTransactionFree(served);
    confirmHash(m, pHash);
    check(1, "requests and a confirmation after the parent removal read no released record");

    teardown(m, w);
}

/* ---- remove_readers: a send published with no wallet copy, taken back by the wallet on a getdata
 *      request, then removed — every reader (publish, confirm, inv, getdata, fluff) must touch no
 *      released record. ------------------------------------------------------------------------ */
static void scenario_remove_readers(void)
{
    printf("\n-- remove_readers: a send taken back on getdata then removed leaves every reader clean --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* publish A with no wallet copy: the list owns it, the wallet holds nothing equal */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    _BRPeerManagerAddTxToPublishList(m, A, &g_cbSentinel, recordPublishResult);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet holds no record of A");

    /* a getdata request: the wallet takes the listed object, the entry follows it to owned = 0 */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x07, 1);
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info)); info.peer = p; info.manager = m;
    BRTransaction *served = _peerRequestedTx(&info, aHash);
    if (served) BRTransactionFree(served);   /* the served object is a private copy */
    check(BRWalletTransactionForHash(w, aHash) == A, "the wallet took A on the request");
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].owned == 0,
          "the entry followed the object to the wallet's ownership");

    /* the wallet removes A through the one manager-locked removal */
    BRPeerManagerRemoveTransaction(m, aHash);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet released A");
    check(array_count(m->publishedTx) == 0, "the removed record left the publish list");

    /* every reader must touch no released record: inv, getdata, publish (duplicate scan), fluff,
     * confirm. On the shipped arm the list is empty, so each reader runs clean; a shape that left
     * the entry behind faults at the first reader that judges the entry's object (inv registers it,
     * getdata validates it). The hash-keyed readers come last: they would otherwise drop a stale
     * entry by its cached hash without reading it, hiding the shape from the readers after them. */
    _peerHasTx(&info, aHash);
    BRTransaction *served2 = _peerRequestedTx(&info, aHash);
    if (served2) BRTransactionFree(served2);
    BRTransaction *B = makeOwnedSend(spk, spkLen, 0x22);
    BRPeerManagerPublishTx(m, B, &g_cbSentinel, recordPublishResult);
    BRPeerManagerFluffTx(m, aHash);
    confirmHash(m, aHash);
    check(1, "inv / getdata / publish / fluff / confirm read no released record after the removal");

    teardown(m, w);
}

/* ---- remove_getdata_race: an object served on a getdata request and read after the manager lock
 *      is dropped must outlive a concurrent wallet removal. Two threads, tx_serialize_race_kat
 *      style. The server models the BRPeer.c getdata handler; the mutator models the wallet-side
 *      removal a user tap or the automatic dead-send drop drives. ----------------------------- */
#define RGR_SLOTS 8
#define RGR_ITERS 5000
static BRWallet       *g_rgrWallet;
static BRPeerManager  *g_rgrMgr;
static BRPeer         *g_rgrPeer;
static uint8_t         g_rgrSpk[64];
static size_t          g_rgrSpkLen;
static UInt256         g_rgrFund[RGR_SLOTS];   /* funding hash per slot */
static UInt256         g_rgrHash[RGR_SLOTS];   /* current send hash per slot */
static volatile int    g_rgrStop;

static BRTransaction *rgrMakeSend(int slot, uint32_t nonce)
{
    /* spend the slot's funding:0, unique amount so each send has a unique hash */
    return mkTx(g_rgrSpk, g_rgrSpkLen, g_rgrFund[slot], 20000 + (uint64_t)slot * 1000 + nonce);
}

static void *rgr_server(void *arg)
{
    (void)arg;
    BRPeerCallbackInfo info; memset(&info, 0, sizeof(info));
    info.peer = g_rgrPeer; info.manager = g_rgrMgr;
    for (int i = 0; i < RGR_ITERS && ! g_rgrStop; i++) {
        UInt256 h = g_rgrHash[i % RGR_SLOTS];
        BRTransaction *served = _peerRequestedTx(&info, h);   /* a private copy on the shipped arm */
        usleep(20);                                           /* the read window BRPeer.c has */
        (void)readObjectLikePeer(served);
#ifndef PUBLISH_SERVED_COPY_UNFIXED
        if (served) BRTransactionFree(served);                /* the handler owns the copy */
#endif
    }
    return NULL;
}

static void *rgr_mutator(void *arg)
{
    (void)arg;
    for (int i = 0; i < RGR_ITERS && ! g_rgrStop; i++) {
        int slot = i % RGR_SLOTS;
        BRPeerManagerRemoveTransaction(g_rgrMgr, g_rgrHash[slot]);  /* frees the send, purges the list */
        BRTransaction *fresh = rgrMakeSend(slot, (uint32_t)i + 1);
        fresh->timestamp = (uint32_t)time(NULL);
        BRWalletRegisterTransactionTrusted(g_rgrWallet, fresh);
        MGR_LOCK(g_rgrMgr);
        _BRPeerManagerAddTxToPublishListOwned(g_rgrMgr, fresh, NULL, NULL, 0);
        MGR_UNLOCK(g_rgrMgr);
        g_rgrHash[slot] = fresh->txHash;
    }
    return NULL;
}

static void scenario_remove_getdata_race(void)
{
    printf("\n-- remove_getdata_race: a served object outlives a concurrent wallet removal (2 threads) --\n");
    g_rgrWallet = makeWallet();
    g_rgrMgr = BRPeerManagerNew(&BRMainNetParams, g_rgrWallet, 0, NULL, 0, NULL, 0);
    if (! g_rgrWallet || ! g_rgrMgr) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(g_rgrWallet, 2);
    g_rgrSpkLen = BRAddressScriptPubKey(g_rgrSpk, sizeof(g_rgrSpk), ta.s);
    g_rgrMgr->isConnected = 1; g_rgrMgr->connectFailureCount = MAX_CONNECT_FAILURES; g_rgrMgr->downloadPeer = NULL;
    g_rgrPeer = addPeer(g_rgrMgr, 0x33, 1);   /* created before the threads start, not raced */

    for (int s = 0; s < RGR_SLOTS; s++) {
        UInt256 prev; memset(prev.u8, (uint8_t)(0x50 + s), 32);
        BRTransaction *F = mkTx(g_rgrSpk, g_rgrSpkLen, prev, 5000000);
        F->blockHeight = 1000;                 /* confirmed funding, so the parent walk skips it */
        F->timestamp = (uint32_t)time(NULL);
        BRWalletRegisterTransactionTrusted(g_rgrWallet, F);
        g_rgrFund[s] = F->txHash;
        BRTransaction *WA = rgrMakeSend(s, 0);
        WA->timestamp = (uint32_t)time(NULL);
        BRWalletRegisterTransactionTrusted(g_rgrWallet, WA);
        _BRPeerManagerAddTxToPublishListOwned(g_rgrMgr, WA, NULL, NULL, 0);
        g_rgrHash[s] = WA->txHash;
    }

    g_rgrStop = 0;
    pthread_t server, mutator;
    pthread_create(&server, NULL, rgr_server, NULL);
    pthread_create(&mutator, NULL, rgr_mutator, NULL);
    pthread_join(server, NULL);
    pthread_join(mutator, NULL);
    check(1, "no released object was read across the getdata / removal window");

    teardown(g_rgrMgr, g_rgrWallet);
}

/* ==== Answered-once scenarios: every publish callback fires exactly once ======================= */

/* ---- answered_confirm: a pending publish is answered 0 when its transaction confirms ----------- */
static void scenario_answered_confirm(void)
{
    printf("\n-- answered_confirm: confirmation answers the pending publish once with 0 --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    (void)p;
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);
    check(g_cbCount == 0 && entryPending(m, aHash), "the publish is pending");

    /* The transaction confirms in a block before any peer echoed it. */
    confirmHash(m, aHash);
    check(g_cbCount == 1, "the publish callback fired exactly once");
    check(g_lastError == 0, "with the verdict 0: the transaction is in a block");
    check(array_count(m->pendingAnswers) == 0, "the answer queue is empty after the flush");
    check(listedCount(m, aHash) == 0, "the confirmed send left the publish list");
    check(__asan_address_is_poisoned(A) != 0, "the list released its own object once");
    check(walletRecordRoundTrips(w, aHash), "the wallet's own record round-trips");

    teardown(m, w);
    check(g_cbCount == 1, "teardown did not answer it a second time");
}

/* ---- answered_teardown: a still-pending publish is answered ENOTCONN when the manager goes ---- */
static void scenario_answered_teardown(void)
{
    printf("\n-- answered_teardown: teardown answers every pending publish once with ENOTCONN --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    addPeer(m, 0x01, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);
    /* a second, unrelated pending send the wallet holds no copy of: the list owns it outright */
    BRTransaction *B = makeOwnedSend(spk, spkLen, 0x22);
    BRPeerManagerPublishTx(m, B, newCtx(), countingResult);
    check(g_cbCount == 0 && entryPending(m, aHash) && entryPending(m, B->txHash), "both publishes are pending");

    teardown(m, w);
    check(g_cbCount == 2, "both publish callbacks fired exactly once each at teardown");
    check(g_lastError == ENOTCONN, "with ENOTCONN, the verdict a disconnect-cancellation gives");
    check(__asan_address_is_poisoned(A) != 0, "the list's distinct object A was released at teardown");
    check(__asan_address_is_poisoned(B) != 0, "the list's object B was released at teardown");
}

/* ---- answered_duplicate: a publish while the earlier one is pending is answered EALREADY ------ */
static void scenario_answered_duplicate(void)
{
    printf("\n-- answered_duplicate: a re-publish while pending is answered EALREADY; the first carries the verdict --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);
    check(g_cbCount == 0 && entryPending(m, aHash), "the first publish is pending");

    /* the same send published again (the 90-second sweep's re-publish) with its own context */
    BRTransaction *A2 = makeOwnedSend(spk, spkLen, 0x11);
    BRPeerManagerPublishTx(m, A2, newCtx(), countingResult);
    check(g_cbCount == 1, "the second publish was answered at once");
    check(g_lastError == EALREADY, "with EALREADY: the earlier publish is still pending");
    check(listedCount(m, aHash) == 1, "exactly one entry names the send");
    check(entryPending(m, aHash), "the first publish's callback is still armed");
    check(__asan_address_is_poisoned(A2) != 0, "the second object was released: the list keeps the one it has");
    check(__asan_address_is_poisoned(A) == 0, "the list's object is untouched");

    /* a peer relays the send back: the first publish gets its verdict */
    relayFrom(m, p, makeOwnedSend(spk, spkLen, 0x11));
    check(g_cbCount == 2, "the first publish fired once when the send was relayed back");
    check(g_lastError == 0, "with the verdict 0");
    check(! entryPending(m, aHash), "the entry has no pending callback left");

    teardown(m, w);
    check(g_cbCount == 2, "nothing was answered a second time");
}

/* ---- answered_duplicate_legacy: the classic caller registers and publishes the SAME object, then
 *      publishes it again while pending — the wallet's record is never released by the list. --- */
static void scenario_answered_duplicate_legacy(void)
{
    printf("\n-- answered_duplicate_legacy: same-object caller re-publishes while pending; the wallet's record survives --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRTransaction *t = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 h = t->txHash;
    t->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, t);                          /* the wallet's own record ... */
    BRPeerManagerPublishTx(m, t, newCtx(), countingResult);    /* ... handed to the publisher as-is */
    check(BRWalletTransactionForHash(w, h) == t, "the wallet holds the very object that was published");
    check(listedCount(m, h) == 1 && m->publishedTx[0].owned == 0,
          "ownership was decided at add time: the entry is the wallet's (owned = 0)");

    /* the classic caller publishes the SAME pointer again while it is pending */
    BRPeerManagerPublishTx(m, t, newCtx(), countingResult);
    check(g_cbCount == 1 && g_lastError == EALREADY, "the second publish was answered EALREADY");
    check(__asan_address_is_poisoned(t) == 0, "the wallet's own object was not released by the duplicate");

    /* and a fresh equal object with a third context */
    BRTransaction *t2 = makeOwnedSend(spk, spkLen, 0x11);
    BRPeerManagerPublishTx(m, t2, newCtx(), countingResult);
    check(g_cbCount == 2 && g_lastError == EALREADY, "the third publish was answered EALREADY");
    check(__asan_address_is_poisoned(t2) != 0, "the fresh duplicate object was released");

    /* the broadcast times out: the first publish is cancelled, the wallet's record is kept.
     * run.sh's mut_release mutant (owned kept at 1 for the wallet's own object) releases t here. */
    killPeer(m, p, ETIMEDOUT);
    check(g_cbCount == 3 && g_lastError == ETIMEDOUT, "the first publish was answered once, ETIMEDOUT");
    check(listedCount(m, h) == 0, "the cancelled entry left the list");
    check(__asan_address_is_poisoned(t) == 0, "the wallet's record was not released");
    check(walletRecordRoundTrips(w, h), "the wallet's record round-trips");

    teardown(m, w);
    check(g_cbCount == 3, "nothing was answered a second time");
}

/* ---- answered_removed: a wallet-side removal answers a pending publish ECANCELED -------------- */
static void scenario_answered_removed(void)
{
    printf("\n-- answered_removed: the wallet removing the send answers its pending publish once with ECANCELED --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x21, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->blockHeight = 1000; F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000);   /* the wallet's record of send A */
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);

    /* the relay path lists the wallet's record (owned = 0, no callback) ... */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    relayFrom(m, p, mkTx(spk, spkLen, F->txHash, 40000));
    check(listedCount(m, aHash) == 1 && ! entryPending(m, aHash), "the relay listed the wallet's record, untracked");
    /* ... and a re-publish adopts a callback onto it */
    BRTransaction *WA2 = mkTx(spk, spkLen, F->txHash, 40000);
    WA2->timestamp = (uint32_t)time(NULL);
    BRPeerManagerPublishTx(m, WA2, newCtx(), countingResult);
    check(g_cbCount == 0 && entryPending(m, aHash), "the re-publish is pending on the wallet's entry");
    check(__asan_address_is_poisoned(WA2) != 0, "the re-publish's own object was released (duplicate)");

    /* the wallet removes the send through the one manager-locked removal */
    BRPeerManagerRemoveTransaction(m, aHash);
    check(g_cbCount == 1, "the pending publish was answered exactly once");
    check(g_lastError == ECANCELED, "with ECANCELED: the wallet itself removed the transaction");
    check(listedCount(m, aHash) == 0, "the removed record left the publish list");
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the wallet released its record");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ---- answered_removed_bridge: the wallet's own publish shape — the wallet holds its copy, the list
 *      owns the original — removed by the wallet: answered ECANCELED once, gone from the list, never
 *      served or brought back; a dependant goes with it. ---------------------------------------- */
static void scenario_answered_removed_bridge(void)
{
    printf("\n-- answered_removed_bridge: removing a send the list holds its own copy of answers ECANCELED and ends it --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);
    BRTransaction *rec = BRWalletTransactionForHash(w, aHash);
    check(rec != NULL && rec != A && listedCount(m, aHash) == 1 && entryPending(m, aHash),
          "the wallet holds its copy, the list its own original, the publish is pending");
    /* a dependant, published the same way */
    BRTransaction *B = makeChildOf(spk, spkLen, aHash);
    UInt256 bHash = B->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, B), newCtx(), countingResult);
    check(listedCount(m, bHash) == 1 && entryPending(m, bHash) && BRWalletTransactionForHash(w, bHash) != NULL,
          "the dependant is listed, pending, and held by the wallet");

    /* the user discards the send: the one manager-locked removal */
    BRPeerManagerRemoveTransaction(m, aHash);
    check(g_cbCount == 2, "both pending publishes were answered exactly once");
    check(g_lastError == ECANCELED, "with ECANCELED: the wallet removed the transaction");
    check(listedCount(m, aHash) == 0 && listedCount(m, bHash) == 0, "the removed send and its dependant left the publish list");
    check(__asan_address_is_poisoned(A) != 0 && __asan_address_is_poisoned(B) != 0, "the list's own copies were released once");
    check(BRWalletTransactionForHash(w, aHash) == NULL && BRWalletTransactionForHash(w, bHash) == NULL, "the wallet holds neither");

    /* a peer asks for the discarded send: nothing is served and nothing comes back */
    BRTransaction *served = serve(m, p, aHash);
    check(served == NULL, "a request for the removed send is not answered");
    if (served) BRTransactionFree(served);
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the request did not bring the send back into the wallet");
    check(g_cbCount == 2, "nothing more was answered");
    BRPeerCallbackInfo hi; memset(&hi, 0, sizeof(hi)); hi.peer = p; hi.manager = m;
    _peerHasTx(&hi, aHash);
    check(BRWalletTransactionForHash(w, aHash) == NULL && listedCount(m, aHash) == 0, "an inv for it changes nothing either");

    teardown(m, w);
    check(g_cbCount == 2, "teardown answered nothing more");
}

/* ==== Exact ownership at add time ============================================================= */

/* A funding F with two wallet-registered spenders of F:0 — WB the winner, WA the loser (invalid).
 * Returns WA's hash via *out and leaves F, WB, WA as the wallet's records. */
static BRTransaction *makeInvalidWalletSend(BRWallet *w, const uint8_t *spk, size_t spkLen, uint8_t tag, UInt256 *fHash)
{
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WB = mkTx(spk, spkLen, F->txHash, 40000);
    WB->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, WB);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 30000);
    WA->timestamp = (uint32_t)time(NULL) + 1;
    BRWalletRegisterTransactionTrusted(w, WA);
    *fHash = F->txHash;
    return WA;
}

/* ---- invalid_window: the wallet holds an equal record, the list owns a distinct object — an
 *      invalid request releases the list's object once and leaves the wallet's alone. ---------- */
static void scenario_invalid_window(void)
{
    printf("\n-- invalid_window: with an equal wallet record, an invalid request releases only the list's object --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 fHash;
    BRTransaction *WA = makeInvalidWalletSend(w, spk, spkLen, 0x41, &fHash);
    UInt256 aHash = WA->txHash;
    check(! BRWalletTransactionIsValid(w, WA), "WA is an invalid double-spend");

    /* the list owns a DISTINCT equal object with a pending callback */
    BRTransaction *A = mkTx(spk, spkLen, fHash, 30000);
    A->timestamp = WA->timestamp;
    _BRPeerManagerAddTxToPublishList(m, A, newCtx(), countingResult);
    check(listedCount(m, aHash) == 1 && m->publishedTx[0].owned == 1, "the list owns its distinct object");

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRTransaction *served = serve(m, p, aHash);
    check(served == NULL, "an invalid request serves nothing");
    check(g_cbCount == 1 && g_lastError == EINVAL, "the publish was answered once with EINVAL");
    check(listedCount(m, aHash) == 0, "the entry left the list");
    /* run.sh's mut_conjunct mutant (release only when the wallet holds no equal record) leaves A
     * owner-less here — a LeakSanitizer report. */
    check(__asan_address_is_poisoned(A) != 0, "the list's own object was released once");
    check(__asan_address_is_poisoned(WA) == 0 && BRWalletTransactionForHash(w, aHash) == WA, "the wallet's record is untouched");
    check(readObjectLikePeer(WA), "the wallet's record reads cleanly");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ---- invalid_window_legacy: the SAME object registered and published — an invalid request never
 *      releases the wallet's object. ---------------------------------------------------------- */
static void scenario_invalid_window_legacy(void)
{
    printf("\n-- invalid_window_legacy: same-object caller; an invalid request leaves the wallet's object alone --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 fHash;
    BRTransaction *WA = makeInvalidWalletSend(w, spk, spkLen, 0x42, &fHash);
    UInt256 aHash = WA->txHash;
    /* the classic caller hands the publisher the object it registered: owned = 1 requested ... */
    _BRPeerManagerAddTxToPublishList(m, WA, newCtx(), countingResult);
    check(listedCount(m, aHash) == 1, "the wallet's own object is listed");
    /* ... and ownership follows the object: the wallet's. (The comparison arm keeps owned = 1.) */

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES;
    BRPeer *p = addPeer(m, 0x05, 1);
    BRTransaction *served = serve(m, p, aHash);
    check(served == NULL, "an invalid request serves nothing");
    check(g_cbCount == 1 && g_lastError == EINVAL, "the publish was answered once with EINVAL");
    check(__asan_address_is_poisoned(WA) == 0, "the wallet's object was NOT released");
    check(BRWalletTransactionForHash(w, aHash) == WA, "the wallet still holds it");
    check(readObjectLikePeer(WA), "and it reads cleanly");
    check(walletRecordsReadable(w), "every wallet record reads cleanly");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ---- confirm_legacy: the SAME object registered and published survives its confirmation ------- */
static void scenario_confirm_legacy(void)
{
    printf("\n-- confirm_legacy: same-object caller; confirmation answers 0 and leaves the wallet's object alone --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    addPeer(m, 0x01, 1);
    BRTransaction *t = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 h = t->txHash;
    t->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, t);
    BRPeerManagerPublishTx(m, t, newCtx(), countingResult);
    check(listedCount(m, h) == 1 && entryPending(m, h), "the same-object publish is pending");

    confirmHash(m, h);
    check(g_cbCount == 1 && g_lastError == 0, "the publish was answered once with 0");
    check(listedCount(m, h) == 0, "the confirmed entry left the list");
    check(__asan_address_is_poisoned(t) == 0, "the wallet's object was NOT released");
    check(walletRecordRoundTrips(w, h), "the wallet's record round-trips");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ---- cancel_legacy: the SAME object registered and published survives a broadcast timeout ----- */
static void scenario_cancel_legacy(void)
{
    printf("\n-- cancel_legacy: same-object caller; the cancellation leaves the wallet's object alone --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRTransaction *t = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 h = t->txHash;
    t->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, t);
    BRPeerManagerPublishTx(m, t, newCtx(), countingResult);

    killPeer(m, p, ETIMEDOUT);
    check(g_cbCount == 1 && g_lastError == ETIMEDOUT, "the publish was answered once with ETIMEDOUT");
    check(listedCount(m, h) == 0, "the cancelled entry left the list");
    check(__asan_address_is_poisoned(t) == 0, "the wallet's object was NOT released");
    check(walletRecordRoundTrips(w, h), "the wallet's record round-trips");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ==== The relay path's own object ============================================================= */

/* ---- relay_known: a peer relays a send the wallet already holds — the parsed object is released,
 *      the wallet's record untouched, and repeating it grows nothing. -------------------------- */
static void scenario_relay_known(void)
{
    printf("\n-- relay_known: relaying a known send releases the parsed object; the wallet's record is untouched --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x21, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000);
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    int allReleased = 1;
    for (int i = 0; i < 5; i++) {
        BRTransaction *R = mkTx(spk, spkLen, F->txHash, 40000);   /* the wire object one relay delivers */
        relayFrom(m, p, R);
        if (! __asan_address_is_poisoned(R)) allReleased = 0;
    }
    check(allReleased, "each relay's parsed object was released (the wallet already held the hash)");
    check(BRWalletTransactionForHash(w, aHash) == WA && __asan_address_is_poisoned(WA) == 0, "the wallet's record is untouched");
    check(readObjectLikePeer(WA), "the wallet's record reads cleanly");
    check(listedCount(m, aHash) == 1 && m->publishedTx[0].tx == WA && m->publishedTx[0].owned == 0,
          "the relay listed the wallet's record once, as the wallet's");

    teardown(m, w);
}

/* ---- relay_block_known: the block-delivery shape — a known send arrives as a block's transaction
 *      (the same relay callback), then the block confirms it. ---------------------------------- */
static void scenario_relay_block_known(void)
{
    printf("\n-- relay_block_known: a known send delivered from a block: parsed object released, then confirmed --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    UInt256 prev; memset(prev.u8, 0x21, 32);
    BRTransaction *F = mkTx(spk, spkLen, prev, 50000);
    F->blockHeight = 1000; F->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransactionTrusted(w, F);
    BRTransaction *WA = mkTx(spk, spkLen, F->txHash, 40000);
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);

    /* the send is pending in the list as the list's own distinct object */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x06, 1);
    BRTransaction *A = mkTx(spk, spkLen, F->txHash, 40000);
    A->timestamp = WA->timestamp;
    BRPeerManagerPublishTx(m, A, newCtx(), countingResult);
    check(listedCount(m, aHash) == 1 && m->publishedTx[0].owned == 1 && entryPending(m, aHash), "the send is pending, list-owned");

    /* a full block delivers the send: BRPeer.c hands each parsed transaction to the relay callback */
    BRTransaction *R = mkTx(spk, spkLen, F->txHash, 40000);
    relayFrom(m, p, R);
    check(__asan_address_is_poisoned(R) != 0, "the block's parsed object was released (the wallet already held the hash)");
    check(g_cbCount == 1 && g_lastError == 0, "the relay answered the publish once with 0");
    check(listedCount(m, aHash) == 1, "still one entry");

    /* ... then the block confirms it */
    confirmHash(m, aHash);
    check(listedCount(m, aHash) == 0, "the confirmed entry left the list");
    check(__asan_address_is_poisoned(A) != 0, "the list's own object was released at confirmation");
    check(BRWalletTransactionForHash(w, aHash) == WA && readObjectLikePeer(WA), "the wallet's record is intact");

    teardown(m, w);
    check(g_cbCount == 1, "nothing was answered a second time");
}

/* ==== The stem decision lives on the entry ==================================================== */

/* ---- stem_duplicate: a stem of a pending send releases the handed object and answers EALREADY;
 *      the served copy carries the stem type; no listed object's byte is ever written. --------- */
static void scenario_stem_duplicate(void)
{
    printf("\n-- stem_duplicate: the stem decision is on the entry and stamped on the served copy only --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *stem = addPeer(m, 0x05, 1);
    stem->address.u16[5] = 0xffff;
    BRPeerManagerAddDandelionPeer(m, stem->address);
    BRPeerManagerSetDandelionEnabled(m, 1);

    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    int byteBefore = A->is_dandelion;
    check(BRPeerManagerStemPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult) == 1, "the send is stemmed");
    check(entryStemming(m, aHash) && entryPending(m, aHash), "the entry is in its stem phase, pending");
    check(A->is_dandelion == byteBefore, "the handed object's type byte was not written by the stem");

    /* the stem peer asks for it: the served copy is a Dandelion transaction */
    BRTransaction *served = serve(m, stem, aHash);
    check(served != NULL && served != A && served->is_dandelion == 1, "the served copy carries the stem type");
    if (served) BRTransactionFree(served);
    check(g_cbCount == 1 && g_lastError == 0, "the stem peer's request answered the publish once with 0");

    /* the same send is stemmed again while listed */
    BRTransaction *A2 = makeOwnedSend(spk, spkLen, 0x11);
    check(BRPeerManagerStemPublishTx(m, A2, newCtx(), countingResult) == 1, "the duplicate stem is accepted");
    check(g_cbCount == 1, "the duplicate's callback was adopted (the entry had been answered) — not answered twice");
    check(__asan_address_is_poisoned(A2) != 0, "the duplicate's handed object was released: the list keeps the one it has");
    check(listedCount(m, aHash) == 1 && entryStemming(m, aHash), "still one entry, still stemming");
    check(A->is_dandelion == byteBefore, "the listed object's type byte is still not written");

    /* a third stem while the adopted callback is pending is answered EALREADY */
    BRTransaction *A3 = makeOwnedSend(spk, spkLen, 0x11);
    check(BRPeerManagerStemPublishTx(m, A3, newCtx(), countingResult) == 1, "the third stem is accepted");
    check(g_cbCount == 2 && g_lastError == EALREADY, "the third stem was answered EALREADY at once");
    check(__asan_address_is_poisoned(A3) != 0, "its handed object was released");

    /* fluff: an ordinary transaction from here on, decided on the entry */
    BRPeerManagerFluffTx(m, aHash);
    check(! entryStemming(m, aHash), "the stem phase ended");
    served = serve(m, stem, aHash);
    check(served != NULL && served->is_dandelion == 0, "the served copy is an ordinary transaction after the fluff");
    if (served) BRTransactionFree(served);
    check(g_cbCount == 3 && g_lastError == 0, "that request answered the adopted publish once with 0");
    check(A->is_dandelion == byteBefore, "the listed object's type byte was never written");

    teardown(m, w);
    check(g_cbCount == 3, "teardown answered nothing more; the list's object was released once");
    check(__asan_address_is_poisoned(A) != 0, "the list's own object was released at teardown");
}

/* ==== Release-site (b): the manager's own unrelayed-transaction cleanup ======================== */

/* ---- remove_unrelayed_sweep: a record the sweep removes from the wallet leaves no entry -------- */
static void scenario_remove_unrelayed_sweep(void)
{
    printf("\n-- remove_unrelayed_sweep: the unrelayed-tx cleanup's removal leaves no entry naming the record --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* a wallet record with no stake (zero-value output to us, foreign input): the sweep's target */
    BRTransaction *WA = makeOwnedSend(spk, spkLen, 0x11);
    WA->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = WA->txHash;
    BRWalletRegisterTransactionTrusted(w, WA);
    check(BRWalletTransactionForHash(w, aHash) == WA, "the wallet holds the record");
    check(BRWalletAmountSentByTx(w, WA) == 0 && BRWalletAmountReceivedFromTx(w, WA) == 0, "the wallet has no stake in it");

    /* listed as the wallet's record with no callback (the relay-add shape) */
    _BRPeerManagerAddTxToPublishListOwned(m, WA, NULL, NULL, 0);
    check(listedCount(m, aHash) == 1, "the record is listed");

    /* one synced peer at the ceiling, at the chain tip: the sweep may remove it */
    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    m->maxConnectCount = 1;
    m->estimatedHeight = 0;
    BRPeer *p = addPeer(m, 0x07, 1);
    BRPeerCallbackInfo *info = calloc(1, sizeof(*info));
    info->peer = p; info->manager = m;
    _requestUnrelayedTxGetdataDone(info, 1);   /* frees info */
    check(BRWalletTransactionForHash(w, aHash) == NULL, "the sweep removed the record from the wallet");
    check(listedCount(m, aHash) == 0, "no entry names the released record");

    /* every reader after the removal touches no released record */
    BRTransaction *served = serve(m, p, aHash);
    if (served) BRTransactionFree(served);
    BRPeerCallbackInfo hi; memset(&hi, 0, sizeof(hi)); hi.peer = p; hi.manager = m;
    _peerHasTx(&hi, aHash);
    check(1, "getdata / inv after the sweep's removal read no released record");

    teardown(m, w);
}

/* ---- sweep_dependant: the unrelayed-tx cleanup removes a no-stake record whose dependant sits later in
 *      its own walk — the dependant is released with its parent, and the walk must not read it. ---- */
static void scenario_sweep_dependant(void)
{
    printf("\n-- sweep_dependant: the cleanup's walk survives a removal that releases a dependant later in the walk --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    /* A: a zero-value output to our address, foreign input — a record the wallet has no stake in;
     * B: spends A:0, zero-value output to us — the same, and a dependant of A. Both unverified. */
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    A->timestamp = (uint32_t)time(NULL);
    UInt256 aHash = A->txHash;
    BRWalletRegisterTransactionTrusted(w, A);
    BRTransaction *B = makeChildOf(spk, spkLen, aHash);
    B->timestamp = (uint32_t)time(NULL) + 1;
    UInt256 bHash = B->txHash;
    check(BRWalletAmountSentByTx(w, A) == 0 && BRWalletAmountReceivedFromTx(w, A) == 0, "the wallet has no stake in A");

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    m->maxConnectCount = 1; m->estimatedHeight = 0;
    BRPeer *p = addPeer(m, 0x07, 1);
    /* A is listed as the wallet's record with no callback (the relay-add shape: the cleanup may remove
     * it); B is a pending publish of the app's shape (wallet copy + list-owned original) — the cleanup
     * never removes a pending publish itself, but B's wallet copy goes with its parent. */
    _BRPeerManagerAddTxToPublishListOwned(m, A, NULL, NULL, 0);
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, B), newCtx(), countingResult);
    check(BRWalletTransactionForHash(w, bHash) != NULL && entryPending(m, bHash), "B's copy is the wallet's and B's publish is pending");
    BRTransaction *order[4];
    size_t n = BRWalletTxUnconfirmedBefore(w, order, 4, TX_UNCONFIRMED);
    check(n == 2 && order[0] == A, "the cleanup walks A first, then B");

    /* the cleanup runs: A is removed (no stake, unrelayed), and the wallet releases B with it */
    BRPeerCallbackInfo *info = calloc(1, sizeof(*info));
    info->peer = p; info->manager = m;
    _requestUnrelayedTxGetdataDone(info, 1);   /* frees info; reads no released record */
    check(BRWalletTransactionForHash(w, aHash) == NULL && BRWalletTransactionForHash(w, bHash) == NULL,
          "the wallet released A and its dependant B");
    check(listedCount(m, aHash) == 0 && listedCount(m, bHash) == 0, "neither entry is left on the publish list");
    check(g_cbCount == 1 && g_lastError == ECANCELED, "B's pending publish was answered once with ECANCELED");
    check(__asan_address_is_poisoned(B) != 0, "the list's own copy of B was released");

    teardown(m, w);
    check(g_cbCount == 1, "teardown answered nothing more");
}

/* ==== A peer's rejection resolves a pending publish ============================================ */

/* ---- answered_reject: an INVALID rejection answers the pending publish EINVAL, once ------------ */
static void scenario_answered_reject(void)
{
    printf("\n-- answered_reject: a peer's invalid-rejection answers the pending publish once with EINVAL --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRPeer *q = addPeer(m, 0x02, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);
    check(g_cbCount == 0 && entryPending(m, aHash), "the publish is pending");

    rejectFrom(m, p, aHash, REJECT_INVALID);
    check(g_cbCount == 1, "the rejection answered the publish exactly once");
    check(g_lastError == EINVAL, "with EINVAL");
    check(array_count(m->pendingAnswers) == 0, "the answer queue is empty after the flush");
    check(listedCount(m, aHash) == 1 && ! entryPending(m, aHash), "the entry stays, its callback taken");
    check(__asan_address_is_poisoned(A) == 0 && m->publishedTx[0].owned == 1, "nothing was released; ownership untouched");

    /* a second rejection, from the same and from another peer: nothing more fires */
    rejectFrom(m, p, aHash, REJECT_INVALID);
    rejectFrom(m, q, aHash, REJECT_NONSTANDARD);
    check(g_cbCount == 1, "further rejections answer nothing");

    /* the entry still serves a getdata */
    BRTransaction *served = serve(m, q, aHash);
    check(served != NULL, "the entry still answers a request");
    if (served) BRTransactionFree(served);
    check(g_cbCount == 1, "the request answered nothing more");

    teardown(m, w);
    check(g_cbCount == 1, "teardown answered nothing more; the list's object was released once");
    check(__asan_address_is_poisoned(A) != 0, "the list's own object was released at teardown");
}

/* ---- reject_policy_pending: a policy rejection (fee) leaves the publish pending --------------- */
static void scenario_reject_policy_pending(void)
{
    printf("\n-- reject_policy_pending: a fee/spent rejection is one peer's policy — the publish stays pending --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);

    rejectFrom(m, p, aHash, REJECT_LOWFEE);
    rejectFrom(m, p, aHash, REJECT_SPENT);
    rejectFrom(m, p, aHash, 0x7f);   /* an unknown code */
    check(g_cbCount == 0, "policy and unknown rejections answered nothing");
    check(entryPending(m, aHash), "the publish is still pending");

    teardown(m, w);
    check(g_cbCount == 1 && g_lastError == ENOTCONN, "the publish was answered once, at teardown");
}

/* ---- reject_relayed_pending: another peer relayed it — one peer's rejection is not a verdict --- */
static void scenario_reject_relayed_pending(void)
{
    printf("\n-- reject_relayed_pending: a rejection while another peer relays it leaves the publish pending --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *p = addPeer(m, 0x01, 1);
    BRPeer *other = addPeer(m, 0x02, 1);
    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    BRPeerManagerPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult);

    /* another peer already has it (relayed back), recorded the way the relay/inv paths record it */
    _BRTxPeerListAddPeer(&m->txRelays, aHash, other);
    rejectFrom(m, p, aHash, REJECT_INVALID);
    check(g_cbCount == 0, "the rejection answered nothing: the network has the transaction");
    check(entryPending(m, aHash), "the publish is still pending");
    check(_BRTxPeerListCount(m->txRelays, aHash) == 1, "the other peer's relay is still counted");

    teardown(m, w);
    check(g_cbCount == 1 && g_lastError == ENOTCONN, "the publish was answered once, at teardown");
}

/* ---- reject_stem: a stem-phase entry rejected by its stem peer is answered EINVAL once --------- */
static void scenario_reject_stem(void)
{
    printf("\n-- reject_stem: the stem peer's invalid-rejection answers the stem publish once with EINVAL --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! w || ! m) { check(0, "fixtures allocated"); return; }
    resetCounter();
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    m->isConnected = 1; m->connectFailureCount = MAX_CONNECT_FAILURES; m->downloadPeer = NULL;
    BRPeer *stem = addPeer(m, 0x05, 1);
    stem->address.u16[5] = 0xffff;
    BRPeerManagerAddDandelionPeer(m, stem->address);
    BRPeerManagerSetDandelionEnabled(m, 1);

    BRTransaction *A = makeOwnedSend(spk, spkLen, 0x11);
    UInt256 aHash = A->txHash;
    check(BRPeerManagerStemPublishTx(m, registerCopyHandOff(w, A), newCtx(), countingResult) == 1, "the send is stemmed");
    check(entryStemming(m, aHash) && entryPending(m, aHash), "the stem entry is pending");

    rejectFrom(m, stem, aHash, REJECT_INVALID);
    check(g_cbCount == 1 && g_lastError == EINVAL, "the stem peer's rejection answered the publish once with EINVAL");
    check(listedCount(m, aHash) == 1 && entryStemming(m, aHash), "the entry stays, still marked as stemming");
    rejectFrom(m, stem, aHash, REJECT_INVALID);
    check(g_cbCount == 1, "a repeat answers nothing");

    teardown(m, w);
    check(g_cbCount == 1, "teardown answered nothing more");
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
    printf("ARM: comparison (-DPUBLISH_LIST_OWNERSHIP_UNFIXED) — release keyed off the wallet's knowledge of the hash\n");
#elif defined(PUBLISH_OWNED_FOLLOWS_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_OWNED_FOLLOWS_UNFIXED) — the entry does not follow its object\n");
#elif defined(PUBLISH_OWNED_AT_ADD_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_OWNED_AT_ADD_UNFIXED) — ownership not decided at add time\n");
#elif defined(PUBLISH_REMOVE_PURGE_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_REMOVE_PURGE_UNFIXED) — a removal leaves the entries that named released records\n");
#elif defined(PUBLISH_REMOVE_OWNED_SKIP_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_REMOVE_OWNED_SKIP_UNFIXED) — a removal keeps every list-owned entry\n");
#elif defined(PUBLISH_SERVED_COPY_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_SERVED_COPY_UNFIXED) — a getdata answer returns an owned object\n");
#elif defined(PUBLISH_ANSWER_ONCE_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_ANSWER_ONCE_UNFIXED) — drop sites lose the callback\n");
#elif defined(PUBLISH_RELAY_OBJECT_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_RELAY_OBJECT_UNFIXED) — the relay path keeps the parsed object unreleased\n");
#elif defined(PUBLISH_STEM_TYPE_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_STEM_TYPE_UNFIXED) — the stem type written on the listed object\n");
#elif defined(PUBLISH_REJECT_UNFIXED)
    printf("ARM: comparison (-DPUBLISH_REJECT_UNFIXED) — a rejection never resolves a publish\n");
#elif defined(UNRELAYED_SWEEP_RELOOKUP_UNFIXED)
    printf("ARM: comparison (-DUNRELAYED_SWEEP_RELOOKUP_UNFIXED) — the cleanup walks released pointers\n");
#else
    printf("ARM: shipped — one owner per object decided at add time, every callback answered once after the unlock\n");
#endif

    static const struct { const char *name; void (*fn)(void); } scenarios[] = {
        { "survives",                  scenario_survives },
        { "confirm_released",          scenario_confirm_released },
        { "confirm_released_copy",     scenario_confirm_released_copy },
        { "invalid_released",          scenario_invalid_released },
        { "invalid_survives",          scenario_invalid_survives },
        { "requested",                 scenario_requested },
        { "hastx",                     scenario_hastx },
        { "relay",                     scenario_relay },
        { "remove_relay",              scenario_remove_relay },
        { "remove_parent",             scenario_remove_parent },
        { "remove_readers",            scenario_remove_readers },
        { "remove_getdata_race",       scenario_remove_getdata_race },
        { "answered_confirm",          scenario_answered_confirm },
        { "answered_teardown",         scenario_answered_teardown },
        { "answered_duplicate",        scenario_answered_duplicate },
        { "answered_duplicate_legacy", scenario_answered_duplicate_legacy },
        { "answered_removed",          scenario_answered_removed },
        { "answered_removed_bridge",   scenario_answered_removed_bridge },
        { "invalid_window",            scenario_invalid_window },
        { "invalid_window_legacy",     scenario_invalid_window_legacy },
        { "confirm_legacy",            scenario_confirm_legacy },
        { "cancel_legacy",             scenario_cancel_legacy },
        { "relay_known",               scenario_relay_known },
        { "relay_block_known",         scenario_relay_block_known },
        { "stem_duplicate",            scenario_stem_duplicate },
        { "remove_unrelayed_sweep",    scenario_remove_unrelayed_sweep },
        { "sweep_dependant",           scenario_sweep_dependant },
        { "answered_reject",           scenario_answered_reject },
        { "reject_policy_pending",     scenario_reject_policy_pending },
        { "reject_relayed_pending",    scenario_reject_relayed_pending },
        { "reject_stem",               scenario_reject_stem },
    };
    const size_t n = sizeof(scenarios) / sizeof(scenarios[0]);
    const char *which = (argc > 1) ? argv[1] : "all";
    int ran = 0;
    for (size_t i = 0; i < n; i++) {
        if (! strcmp(which, scenarios[i].name) || ! strcmp(which, "all")) { scenarios[i].fn(); ran = 1; }
    }
    if (! ran) {
        printf("usage: %s [all", argv[0]);
        for (size_t i = 0; i < n; i++) printf("|%s", scenarios[i].name);
        printf("]\n");
        return 2;
    }

    printf("\npublish_list_ownership_kat[%s]: %s\n", which, g_fail ? "FAIL" : "ALL PASS");
    return g_fail ? 1 : 0;
}
