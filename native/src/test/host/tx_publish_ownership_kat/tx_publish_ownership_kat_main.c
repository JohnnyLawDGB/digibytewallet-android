/* tx_publish_ownership_kat — a published transaction has exactly one owner.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * When the wallet sends, two subsystems hold a transaction: the wallet keeps a registered
 * record of it (so it shows in the ledger and is serialized to disk), and the peer manager
 * takes one to broadcast. The rule: the wallet's record and the publisher's object are always
 * DISTINCT objects, and the publisher may release its object at any time. The send functions
 * in jni_transaction.c follow it by registering an independent COPY into the wallet and
 * handing the ORIGINAL to the peer manager.
 *
 * This KAT models that hand-off on the host (the JNI file includes Android headers and cannot
 * be compiled here), drives each of the peer manager's release paths, and checks after each
 * one that the publisher released its object exactly once and that the wallet's record still
 * serializes and round-trips. A host model says nothing about the real file, so run.sh
 * additionally greps the real jni_transaction.c: a green KAT cannot coexist with a send
 * function that hands one object to both the wallet and the publisher.
 *
 * TWO SHAPES (run.sh builds both; convention: PRESENCE of the macro selects the red arm — the
 * green arm is built with NO -D at all, matching the sibling publish_cancel_survivor_kat)
 *   default:                     the two-object shape — the wallet registers a COPY, the
 *                                publisher gets the ORIGINAL. MUST print ALL PASS, exit 0.
 *   -DPUBLISH_OWNERSHIP_UNFIXED: the single-object shape, which this test rules out — the
 *                                wallet registers the SAME object the publisher is handed.
 *                                MUST be reported by AddressSanitizer, in every arm.
 *
 * argv[1], when given, selects ONE arm by number (1-4), so that run.sh can show each arm on its
 * own is able to see the single-object shape. With no argument all four arms run.
 *
 * WHICH CHECKS ARE THE PROOF, AND WHICH ARE GUARDS
 *   RED-THEN-GREEN  in each arm, the sanitizer's verdict on the wallet's serialization, and the
 *                   check that the wallet's record is an object of its own: reported for the
 *                   single-object shape, clean for the two-object shape.
 *   GUARD           the checks on the callback, on the publish list and on "released exactly
 *                   once". They describe what the publisher does with ITS object and hold for
 *                   either shape, so they pin that behaviour and are never the proof.
 *
 * DETERMINISTIC — no sockets, no threads, no timing. Synthetic BRPeerNew() peers with their
 * private status/gotVerack forced, driven straight into the peer manager's release paths.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>

/* "Released exactly once" is checked with the sanitizer itself: after a release the object's
 * memory is no longer addressable (so it WAS released), and the sanitizer reports a second
 * release of the same object as an error (so it was released only once). That needs the
 * AddressSanitizer runtime, which run.sh always builds with. */
#if defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define KAT_BUILT_WITH_ASAN 1
#  endif
#endif
#if defined(__SANITIZE_ADDRESS__)
#  define KAT_BUILT_WITH_ASAN 1
#endif
#ifndef KAT_BUILT_WITH_ASAN
#  error "tx_publish_ownership_kat must be built with -fsanitize=address (see run.sh)"
#endif
#include <sanitizer/asan_interface.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

/* Included, not linked: reaching BRPeerContext (no public setter for connect status or
 * gotVerack) and BRPeerManagerStruct requires both .c files in one translation unit. Both
 * define a file-static _dummyThreadCleanup, so BRPeer.c's copy is renamed by a preprocessor
 * substitution scoped to its #include only — no production source is modified. Same pattern as
 * publish_cancel_survivor_kat / cf_checkpoint_quorum_kat; run.sh drops both from the link line. */
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
static int g_cbSentinel = 0xBEEF;

static void recordPublishResult(void *info, int error)
{
    if (info != &g_cbSentinel) { printf("   FAIL: callback fired with the wrong info pointer\n"); g_fail++; }
    g_cbCount++;
    g_cbError = error;
}

/* ---- fixtures ------------------------------------------------------------------------ */

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

/* BRWalletRegisterTransaction / BRPeerManagerPublishTx assert BRTransactionIsSigned(), which
 * only requires each input's signature+witness pointers to be non-NULL. A zero-length push of
 * a non-NULL placeholder satisfies that without a real signature. */
static const uint8_t kPlaceholder[1] = {0};

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

/* A fixture that could not be built is a FAILURE, never a silent pass. */
static int fixturesReady(BRWallet *w, BRPeerManager *m)
{
    if (w && m) return 1;
    check(0, "the wallet and peer-manager fixtures were allocated");
    return 0;
}

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

/* A wallet-owned, signed-shaped DigiDollar transfer: a zero-value DD token output paying our
 * own taproot receive address (so _BRWalletContainsTx recognises it and it is inserted into
 * wallet->transactions), the "DD" OP_RETURN, and one placeholder-signed input carrying a
 * distinctive prev-hash so each arm's transaction has its own identity. */
static BRTransaction *makeOwnedSend(BRWallet *w, uint8_t tag)
{
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    UInt256 prev; memset(prev.u8, tag, 32);
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);                 /* DD token output (ours) */
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));            /* "DD" OP_RETURN, $50.00 */
    finalizeTxHash(tx);
    return tx;
}

/* Mirrors the register-then-publish hand-off of the send functions in jni_transaction.c: set
 * the timestamp, then decide which object the wallet's registered record is. Returns the
 * ORIGINAL, which the caller hands to the publisher exactly as the JNI function hands its tx
 * to BRPeerManagerPublishTx.
 *
 *   two-object shape:     the wallet keeps an independent COPY; the publisher owns the ORIGINAL.
 *   single-object shape:  the wallet keeps the SAME object the publisher will own — one object,
 *                         two owners — which is the shape this test rules out. */
static BRTransaction *registerAndHandOff(BRWallet *w, BRTransaction *tx)
{
    if (! tx->timestamp) tx->timestamp = (uint32_t)time(NULL);
#ifdef PUBLISH_OWNERSHIP_UNFIXED
    BRWalletRegisterTransaction(w, tx);
#else
    BRTransaction *copy = BRTransactionCopy(tx);
    if (copy) BRWalletRegisterTransaction(w, copy);
#endif
    return tx;
}

/* The publisher released the object it was handed — exactly once. `original` is only ever
 * passed to the sanitizer's query here, never read through. A second release would already
 * have been reported by the sanitizer as an error, ending the run. */
static void checkReleasedOnce(const BRTransaction *original, const char *label)
{
    check(__asan_address_is_poisoned(original) != 0, label);
}

/* The serialization the wallet performs after a send: every registered record is read, and the
 * first one is re-parsed — it must be the send the wallet kept. Then the record itself must be
 * an object of the wallet's own, not the one the publisher was handed. */
static void serializeAndCheck(BRWallet *w, UInt256 expectHash, const BRTransaction *original,
                              const char *label)
{
    size_t need = BRWalletSerializeTransactions(w, NULL, 0);   /* size pass reads every record */
    check(need > 4, label);

    if (need > 4) {
        uint8_t *buf = malloc(need);
        size_t wrote = BRWalletSerializeTransactions(w, buf, need);
        /* Skip the 4-byte count + 4-byte length + 4-byte height + 4-byte timestamp header and
         * re-parse the first record; its hash must be the send we registered. */
        int roundTrips = 0;
        if (wrote >= 16) {
            uint32_t txlen; memcpy(&txlen, buf + 4, 4);
            if (16 + txlen <= wrote) {
                BRTransaction *rt = BRTransactionParse(buf + 16, txlen);
                if (rt) { roundTrips = UInt256Eq(rt->txHash, expectHash); BRTransactionFree(rt); }
            }
        }
        check(roundTrips, "the wallet's registered record round-trips to the send it kept");
        free(buf);
    }

    const BRTransaction *record = BRWalletTransactionForHash(w, expectHash);
    check(record != NULL && record != original,
          "the wallet's record is an object of its own, distinct from the publisher's");
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
    BRPeerCallbackInfo info;
    memset(&info, 0, sizeof(info));
    info.peer = peer;
    info.manager = m;
    _peerDisconnected(&info, error);   /* frees peer; does not free info */
}

/* ---- the peer manager's four release paths -------------------------------------------- */

/* (1) Not connected: BRPeerManagerPublishTx releases its object synchronously and reports
 * ENOTCONN. */
static void arm_no_connection(void)
{
    printf("\n-- [1] no connection: the publisher releases its object, the wallet keeps its own --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! fixturesReady(w, m)) return;

    m->isConnected = 0;
    m->connectFailureCount = MAX_CONNECT_FAILURES;   /* selects the not-connected release path */

    BRTransaction *tx = makeOwnedSend(w, 0x11);
    UInt256 h = tx->txHash;
    g_cbCount = 0; g_cbError = 0;
    BRPeerManagerPublishTx(m, registerAndHandOff(w, tx), &g_cbSentinel, recordPublishResult);

    check(g_cbCount == 1 && g_cbError == ENOTCONN, "the broadcast reported ENOTCONN, once");
    check(array_count(m->publishedTx) == 0, "the publish list holds nothing");
    checkReleasedOnce(tx, "the publisher released the object it was handed, once");
    serializeAndCheck(w, h, tx, "the wallet serializes its records after a not-connected release");
}

/* (2) Duplicate: an equal send is already pending, so BRPeerManagerPublishTx keeps the object
 * its list already has, adopts the new callback, and releases the fresh object it was handed. */
static void arm_duplicate(void)
{
    printf("\n-- [2] duplicate: an equal send is already pending, the fresh object is released --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! fixturesReady(w, m)) return;

    m->isConnected = 1;                 /* past the not-connected branch; no peers => no inv I/O */

    BRTransaction *tx = makeOwnedSend(w, 0x22);
    UInt256 h = tx->txHash;
    /* Seed the publish list with an equal transaction (same hash) already pending. */
    BRTransaction *pending = BRTransactionCopy(tx);
    _BRPeerManagerAddTxToPublishList(m, pending, NULL, NULL);

    g_cbCount = 0; g_cbError = 0;
    BRPeerManagerPublishTx(m, registerAndHandOff(w, tx), &g_cbSentinel, recordPublishResult);

    check(g_cbCount == 0, "nothing is reported while the send is still pending");
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].tx == pending,
          "the publish list keeps the one object it already had");
    check(m->publishedTx[0].callback == recordPublishResult && m->publishedTx[0].info == &g_cbSentinel,
          "the pending entry adopted the new callback");
    checkReleasedOnce(tx, "the publisher released the fresh object it was handed, once");
    serializeAndCheck(w, h, tx, "the wallet serializes its records after a duplicate release");
}

/* (3) Terminal disconnect: the download peer drops at the connect-failure ceiling, so the
 * broadcast is cancelled (ENOTCONN) and the published object released. */
static void arm_terminal_disconnect(void)
{
    printf("\n-- [3] terminal disconnect: the last peer drops and the publisher releases its object --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! fixturesReady(w, m)) return;

    m->isConnected = 1;
    BRPeer *dl = addPeer(m, 0x01, 1);
    m->downloadPeer = dl;
    m->connectFailureCount = MAX_CONNECT_FAILURES - 1;   /* the disconnect ++ reaches the ceiling */

    BRTransaction *tx = makeOwnedSend(w, 0x33);
    UInt256 h = tx->txHash;
    _BRPeerManagerAddTxToPublishList(m, registerAndHandOff(w, tx), &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].tx == tx,
          "the publish list holds the object it was handed");

    g_cbCount = 0; g_cbError = 0;
    killPeer(m, dl, ECONNRESET);        /* non-timeout, non-protocol error => the sync-failed path */

    check(g_cbCount == 1 && g_cbError == ENOTCONN, "the broadcast was cancelled with ENOTCONN, once");
    check(array_count(m->publishedTx) == 0, "the publish list holds nothing");
    checkReleasedOnce(tx, "the publisher released the object it was handed, once");
    serializeAndCheck(w, h, tx, "the wallet serializes its records after a terminal disconnect");
}

/* (4) Timeout with no surviving publish peer: the one handshook peer times out and nothing is
 * left to carry the send, so the published object is released. */
static void arm_timeout_zero_survivors(void)
{
    printf("\n-- [4] broadcast timeout, no surviving peer: the publisher releases its object --\n");
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (! fixturesReady(w, m)) return;

    m->isConnected = 1;
    m->downloadPeer = NULL;
    m->connectFailureCount = MAX_CONNECT_FAILURES;   /* suppress the reconnect at the tail */
    BRPeer *only = addPeer(m, 0x02, 1);              /* the only handshook peer */

    BRTransaction *tx = makeOwnedSend(w, 0x44);
    UInt256 h = tx->txHash;
    _BRPeerManagerAddTxToPublishList(m, registerAndHandOff(w, tx), &g_cbSentinel, recordPublishResult);
    check(array_count(m->publishedTx) == 1 && m->publishedTx[0].tx == tx,
          "the publish list holds the object it was handed");

    g_cbCount = 0; g_cbError = 0;
    killPeer(m, only, ETIMEDOUT);

    check(g_cbCount == 1 && g_cbError == ETIMEDOUT, "the broadcast timed out with no survivor, once");
    check(array_count(m->publishedTx) == 0, "the publish list holds nothing");
    checkReleasedOnce(tx, "the publisher released the object it was handed, once");
    serializeAndCheck(w, h, tx, "the wallet serializes its records after a broadcast timeout");
}

int main(int argc, char **argv)
{
    static void (*const arms[])(void) = {
        arm_no_connection, arm_duplicate, arm_terminal_disconnect, arm_timeout_zero_survivors
    };
    const int armCount = (int)(sizeof(arms) / sizeof(arms[0]));
    int only = 0;   /* 0 = every arm */

    /* Unbuffered: a run the sanitizer stops must still show which arm it was in. */
    setvbuf(stdout, NULL, _IONBF, 0);

    if (argc > 1) {
        only = atoi(argv[1]);
        if (only < 1 || only > armCount) {
            printf("usage: %s [arm 1-%d]\n", argv[0], armCount);
            return 2;
        }
    }

#ifdef PUBLISH_OWNERSHIP_UNFIXED
    printf("SHAPE: single object (-DPUBLISH_OWNERSHIP_UNFIXED) — the wallet registers the same "
           "object the publisher is handed; the shape this test rules out\n");
#else
    printf("SHAPE: two objects — the wallet registers an independent copy; the publisher owns "
           "the original\n");
#endif

    for (int i = 0; i < armCount; i++) {
        if (only == 0 || only == i + 1) arms[i]();
    }

    printf("\ntx_publish_ownership_kat: %s\n", g_fail ? "FAIL" : "ALL PASS");
    return g_fail ? 1 : 0;
}
