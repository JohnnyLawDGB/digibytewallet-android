// relayed_header_lifetime_kat -- nothing read from a relayed header outlives the lock that
// protects it.
//
// THE INVARIANT. _peerRelayedBlock (BRPeerManager.c) works on the relayed header while it
// holds manager->lock. Once the header is in the manager's sets, its lifetime is governed by
// that lock. Everything the code after the unlock needs from the header -- its height, for
// the txStatusUpdate gate -- is therefore copied into locals while the lock is still held,
// and the header is not dereferenced again.
//
// HOW IT IS PROVED. This file #includes BRPeerManager.c and drives the REAL _peerRelayedBlock
// from two threads that each deliver the same header (equal identity, separate objects), as
// two connected peers do. There are no sleeps and nothing depends on timing:
//   * the first delivery completes the chain download, so it triggers the save dispatch,
//     which runs after manager->lock has been released;
//   * the saveBlocks callback installed here holds the first thread at that point until the
//     second delivery has returned, then lets it continue to the gate;
//   * the manager keeps one resident copy per identity, so the second delivery becomes that
//     copy and the first object's lifetime ends under the lock, as the locking rules allow.
// The fixed arm must finish every round with both deliveries through the gate and no
// sanitizer report. The reference arm (the earlier form of the gate, selected inside
// BRPeerManager.c) must be reported by AddressSanitizer.
//
// run.sh adds a source gate over the same function: between its last MGR_UNLOCK and its
// closing self-call there is no dereference of `block` outside the reference arm.
//
// MACRO CONVENTION (value form): run.sh passes -DRELAYED_HEADER_LIFETIME_UNFIXED=1 for the
// reference arm and =0 for the fixed arm, and the code selects with `#if`, never `#ifdef`,
// so the =0 build really takes the fixed path.
//
// BOUNDED: a fixed number of rounds, and every wait has a time limit, so it cannot hang.
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifndef RELAYED_HEADER_LIFETIME_UNFIXED
#define RELAYED_HEADER_LIFETIME_UNFIXED 0
#endif

#include "BRPeerManager.c"

#define ROUNDS          25
#define WAIT_LIMIT_SECS 30

static int g_fail = 0;
static void check(int cond, const char *what) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail = 1;
}

// ---- rendezvous between the two deliveries ---------------------------------------------
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_cv = PTHREAD_COND_INITIALIZER;
static int g_firstPastUnlock, g_firstReturned, g_secondDone, g_timedOut;   // guarded by g_mu
static atomic_int g_saveCalls, g_statusCalls;

// Waits (g_mu held) until *a or *b is set, up to the time limit.
static void wait_for(const int *a, const int *b) {
    struct timespec limit;
    clock_gettime(CLOCK_REALTIME, &limit);
    limit.tv_sec += WAIT_LIMIT_SECS;
    while (! *a && ! (b && *b)) {
        if (pthread_cond_timedwait(&g_cv, &g_mu, &limit) == ETIMEDOUT) { g_timedOut = 1; return; }
    }
}

static void set_flag(int *flag) {
    pthread_mutex_lock(&g_mu);
    *flag = 1;
    pthread_cond_broadcast(&g_cv);
    pthread_mutex_unlock(&g_mu);
}

// Runs on the first delivery's thread, after manager->lock has been released. Holds that
// thread here until the second delivery has returned.
static void on_save_blocks(void *info, int replace, const uint8_t *bytes, size_t len, uint64_t *check_) {
    (void)info; (void)replace; (void)bytes; (void)len; (void)check_;
    atomic_fetch_add(&g_saveCalls, 1);
    pthread_mutex_lock(&g_mu);
    g_firstPastUnlock = 1;
    pthread_cond_broadcast(&g_cv);
    wait_for(&g_secondDone, NULL);
    pthread_mutex_unlock(&g_mu);
}

static void on_tx_status(void *info) { (void)info; atomic_fetch_add(&g_statusCalls, 1); }

typedef struct { BRPeerCallbackInfo info; BRMerkleBlock *header; int delivered; } Delivery;

static void *first_delivery(void *arg) {
    Delivery *d = arg;
    _peerRelayedBlock(&d->info, d->header);
    d->delivered = 1;
    set_flag(&g_firstReturned);
    return NULL;
}

static void *second_delivery(void *arg) {
    Delivery *d = arg;
    pthread_mutex_lock(&g_mu);
    wait_for(&g_firstPastUnlock, &g_firstReturned);
    int go = g_firstPastUnlock;
    pthread_mutex_unlock(&g_mu);
    if (go) { _peerRelayedBlock(&d->info, d->header); d->delivered = 1; }
    set_flag(&g_secondDone);
    return NULL;
}

static BRMerkleBlock *make_header(uint32_t tag, UInt256 prevBlock, uint32_t timestamp) {
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, 0, sizeof(b->blockHash.u8));
    b->blockHash.u32[0] = tag + 1;     // distinct per tag, never all-zero
    b->blockHash.u32[7] = 0xFEEDu;
    b->prevBlock = prevBlock;
    b->timestamp = timestamp;
    b->height    = BLOCK_UNKNOWN_HEIGHT;   // the relay path stamps it from the parent
    return b;
}

static BRPeer *add_peer(BRPeerManager *m, uint8_t lastOctet, uint16_t port) {
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = lastOctet;
    p->port = port;
    array_add(m->connectedPeers, p);   // owned (and freed) by the manager from here
    return p;
}

int main(void)
{
    // Unbuffered: a sanitizer ends the process without flushing stdio, and the captured
    // output of the reference arm must stay complete.
    setvbuf(stdout, NULL, _IONBF, 0);
#if RELAYED_HEADER_LIFETIME_UNFIXED
    printf("ARM: REFERENCE (the gate takes its operands from the header)\n");
#else
    printf("ARM: FIXED (the gate uses locals copied while the lock is held)\n");
#endif

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;   // non-zero: wallet setup requires a usable master key
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("relayed_header_lifetime_kat: FAIL (setup: no wallet)\n"); return 1; }

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) { printf("relayed_header_lifetime_kat: FAIL (setup: no manager)\n"); return 1; }
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
    BRPeerManagerSetCallbacks(m, NULL, NULL, NULL, on_tx_status, on_save_blocks, NULL, NULL, NULL);

    // Hand-built tip above the hardcoded checkpoint table, so the headers relayed on top of
    // it do not run into a height that accepts one identity only.
    uint32_t now = (uint32_t)time(NULL);
    BRMerkleBlock *tip = make_header(0x0F000000u, UINT256_ZERO, now);
    tip->height = BRMainNetParams.checkpoints[BRMainNetParams.checkpointsCount - 1].height + 100;
    BRSetAdd(m->blocks, tip);
    m->lastBlock = tip;
    m->lastSpanClampLog = tip->height;   // keeps a once-per-manager retention notice out of the output

    BRPeer *peerA = add_peer(m, 0x51, 12051), *peerB = add_peer(m, 0x52, 12052);

    int roundsOk = 0;
    for (uint32_t round = 0; round < ROUNDS; round++) {
        g_firstPastUnlock = g_firstReturned = g_secondDone = 0;
        int saveBefore = atomic_load(&g_saveCalls), statusBefore = atomic_load(&g_statusCalls);

        UInt256 parent = m->lastBlock->blockHash;
        uint32_t height = m->lastBlock->height + 1;
        m->estimatedHeight = height;   // this header completes the download: the save dispatch runs

        Delivery a = { { peerA, m, UINT256_ZERO }, make_header(0x30000000u + round, parent, now), 0 };
        Delivery b = { { peerB, m, UINT256_ZERO }, make_header(0x30000000u + round, parent, now), 0 };
        UInt256 identity = b.header->blockHash;

        pthread_t ta, tb;
        pthread_create(&tb, NULL, second_delivery, &b);
        pthread_create(&ta, NULL, first_delivery, &a);
        pthread_join(ta, NULL);
        pthread_join(tb, NULL);
        if (! b.delivered) BRMerkleBlockFree(b.header);   // only when setup failed; reported below

        int ok = a.delivered && b.delivered && ! g_timedOut &&
                 atomic_load(&g_saveCalls) == saveBefore + 1 &&          // the hold really happened, once
                 atomic_load(&g_statusCalls) == statusBefore + 2 &&      // both deliveries passed the gate
                 BRSetGet(m->blocks, &identity) == b.header &&           // the second delivery is the resident copy
                 m->lastBlock == b.header && b.header->height == height;
        if (ok) roundsOk++;
        else printf("  round %u: delivered=%d/%d timedOut=%d saves=%d status=%d\n", round, a.delivered, b.delivered,
                    g_timedOut, atomic_load(&g_saveCalls) - saveBefore, atomic_load(&g_statusCalls) - statusBefore);
    }

    check(! g_timedOut, "setup: no wait reached its time limit");
    check(roundsOk == ROUNDS, "every round: the first delivery was held past the unlock, the second became the "
                              "resident copy, and both passed the gate");

    BRPeerManagerFree(m);
    BRWalletFree(wallet);

    if (g_fail) { printf("relayed_header_lifetime_kat: FAIL\n"); return 1; }
    printf("relayed_header_lifetime_kat: %d rounds, no sanitizer report\n", ROUNDS);
    return 0;
}
