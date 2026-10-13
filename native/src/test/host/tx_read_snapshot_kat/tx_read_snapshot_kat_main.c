// Host KAT: a reader of the wallet's transactions keeps no BRTransaction pointer past the wallet lock.
//
// BRWalletTransactionForHash and BRWalletTransactions hand out the wallet's own BRTransaction objects
// and release wallet->lock before they return. BRTransaction has no reference count, and
// BRWalletRemoveTransaction (peer thread: unrelayed-at-tip cleanup; JNI: removeTransaction) frees the
// object once it is out of the wallet's set. A reader that goes on using a handed-out pointer after the
// lock is released reads memory the wallet may already have freed.
//
// The rule the bridge follows: a reader takes what it needs while the wallet holds the lock --
//   BRWalletCopyTransactionForHash  one transaction, deep-copied under the lock (caller frees)
//   BRWalletCopyTransactions        every transaction, deep-copied in one lock hold (caller frees each)
//   BRWalletTransactionHashes       every transaction's hash, copied in one lock hold
// and the wallet's own pointers are used only as a presence answer, never read through.
//
// Arms (argv[1]); each runs in its own process so a fault is attributed to exactly one arm:
//   lookup    one transaction looked up, then removed on another thread, then read
//   list      the whole list taken, one entry removed on another thread, then every entry read
//   hashes    the whole list's hashes taken, one entry removed on another thread, then read
//   race      a bounded two-thread loop: a reader looks up and reads while a peer-like thread removes
//             and re-registers (no fixed schedule)
//   contract  (fixed build only) what the three accessors return: copies equal to the wallet's own
//             record, independent of it, NULL for an unknown hash, the same order and count rules as
//             BRWalletTransactions, and the wallet's per-tx answers unchanged when asked about a copy.
// Built with -DTX_READ_SNAPSHOT_UNFIXED, lookup/list/hashes/race read the handed-out pointers the way
// the bridge readers did before (the comparison arm).
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRInt.h"

#define RACE_ITERS 3000

static BRWallet *g_wallet;
static uint8_t   g_spk[64];
static size_t    g_spkLen;
static int       g_fail;

static const uint8_t kPlaceholder[1] = {0};
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

#define CHECK(cond, ...) do { if (!(cond)) { g_fail++; printf("  FAIL: " __VA_ARGS__); printf("\n"); } } while (0)

static void finalizeTxHash(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(n);
    size_t len = BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

// Pays the wallet's own receive script (so the wallet keeps it) from a prevout the wallet never
// held; `nonce` makes every hash distinct.
static BRTransaction *makeTx(uint32_t nonce)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;
    memset(prevHash.u8, 0x5a, sizeof(prevHash.u8));
    BRTransactionAddInput(tx, prevHash, nonce, 0, g_spk, g_spkLen,
                          kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, 100000 + nonce, g_spk, g_spkLen);
    BRTransactionAddOutput(tx, 7000 + nonce, g_spk, g_spkLen);
    tx->timestamp = 1700000000u + nonce;
    finalizeTxHash(tx);
    return tx;
}

/* ---- removal on another thread, as the peer thread or a second JNI call does it ---- */
static void *remover(void *arg)
{
    BRWalletRemoveTransaction(g_wallet, *(UInt256 *)arg);
    return NULL;
}
static void removeElsewhere(UInt256 h)
{
    pthread_t t;
    pthread_create(&t, NULL, remover, &h);
    pthread_join(t, NULL);
}

/* ---- the reader's side: take, then (after the lock is gone) read ---- */
static BRTransaction *takeOne(UInt256 h)
{
#ifdef TX_READ_SNAPSHOT_UNFIXED
    return BRWalletTransactionForHash(g_wallet, h);
#else
    return BRWalletCopyTransactionForHash(g_wallet, h);
#endif
}
static void releaseOne(BRTransaction *tx)
{
#ifdef TX_READ_SNAPSHOT_UNFIXED
    (void)tx;
#else
    if (tx) BRTransactionFree(tx);
#endif
}
static size_t takeAll(BRTransaction **txs, size_t n)
{
#ifdef TX_READ_SNAPSHOT_UNFIXED
    return BRWalletTransactions(g_wallet, txs, n);
#else
    return BRWalletCopyTransactions(g_wallet, txs, n);
#endif
}
static void releaseAll(BRTransaction **txs, size_t n)
{
#ifdef TX_READ_SNAPSHOT_UNFIXED
    (void)txs; (void)n;
#else
    for (size_t i = 0; i < n; i++) if (txs[i]) BRTransactionFree(txs[i]);
#endif
}
static size_t takeHashes(UInt256 *out, size_t n)
{
#ifdef TX_READ_SNAPSHOT_UNFIXED
    BRTransaction **txs = calloc(n ? n : 1, sizeof(*txs));
    n = BRWalletTransactions(g_wallet, txs, n);
    removeElsewhere(*(UInt256 *)out);            /* out[0] carries the hash to remove (see arm) */
    for (size_t i = 0; i < n; i++) out[i] = txs[i]->txHash;
    free(txs);
    return n;
#else
    UInt256 victim = out[0];
    n = BRWalletTransactionHashes(g_wallet, out, n);
    removeElsewhere(victim);
    return n;
#endif
}

static int arm_lookup(void)
{
    BRTransaction *t = makeTx(1);
    UInt256 h = t->txHash;
    uint64_t want = t->outputs[0].amount + t->outputs[1].amount;
    BRWalletRegisterTransaction(g_wallet, t);

    BRTransaction *seen = takeOne(h);
    CHECK(seen != NULL, "lookup: the wallet does not hold the transaction it just registered");
    if (!seen) return 1;
    removeElsewhere(h);
    CHECK(BRWalletTransactionForHash(g_wallet, h) == NULL, "lookup: the removal did not take");
    uint64_t got = 0;
    for (size_t i = 0; i < seen->outCount; i++) got += seen->outputs[i].amount;
    CHECK(got == want, "lookup: read %llu, the transaction paid %llu", (unsigned long long)got, (unsigned long long)want);
    CHECK(UInt256Eq(seen->txHash, h), "lookup: read a different hash");
    releaseOne(seen);
    return 0;
}

static int arm_list(void)
{
    BRTransaction *anchor = makeTx(10), *victim = makeTx(11);
    uint64_t want = anchor->outputs[0].amount + anchor->outputs[1].amount +
                    victim->outputs[0].amount + victim->outputs[1].amount;
    UInt256 vh = victim->txHash;
    BRWalletRegisterTransaction(g_wallet, anchor);
    BRWalletRegisterTransaction(g_wallet, victim);

    size_t n = BRWalletTransactions(g_wallet, NULL, 0);
    CHECK(n == 2, "list: expected 2 transactions, the wallet has %zu", n);
    BRTransaction **txs = calloc(n ? n : 1, sizeof(*txs));
    n = takeAll(txs, n);
    removeElsewhere(vh);
    uint64_t got = 0;
    for (size_t i = 0; i < n; i++)
        for (size_t j = 0; j < txs[i]->outCount; j++) got += txs[i]->outputs[j].amount;
    CHECK(got == want, "list: read %llu, the two transactions paid %llu", (unsigned long long)got, (unsigned long long)want);
    releaseAll(txs, n);
    free(txs);
    return 0;
}

static int arm_hashes(void)
{
    BRTransaction *anchor = makeTx(20), *victim = makeTx(21);
    UInt256 ah = anchor->txHash, vh = victim->txHash;
    BRWalletRegisterTransaction(g_wallet, anchor);
    BRWalletRegisterTransaction(g_wallet, victim);

    UInt256 out[2];
    out[0] = vh;
    size_t n = takeHashes(out, 2);
    CHECK(n == 2, "hashes: expected 2, got %zu", n);
    int sawA = 0, sawV = 0;
    for (size_t i = 0; i < n; i++) { sawA |= UInt256Eq(out[i], ah); sawV |= UInt256Eq(out[i], vh); }
    CHECK(sawA && sawV, "hashes: the hashes taken are not the two the wallet held");
    return 0;
}

/* ---- race: no fixed schedule ---- */
static pthread_mutex_t g_victimLock = PTHREAD_MUTEX_INITIALIZER;
static UInt256 g_victim;
static volatile int g_mutatorDone;

static void *reader_thread(void *arg)
{
    (void)arg;
    for (int i = 0; i < RACE_ITERS && !g_mutatorDone; i++) {
        pthread_mutex_lock(&g_victimLock);
        UInt256 h = g_victim;
        pthread_mutex_unlock(&g_victimLock);
        BRTransaction *seen = takeOne(h);
        if (!seen) continue;
        usleep(40);                                   /* the reader's own work between take and read */
        volatile uint64_t sum = 0;
        for (size_t j = 0; j < seen->outCount; j++) sum += seen->outputs[j].amount;
        (void)sum;
        releaseOne(seen);
    }
    return NULL;
}
static void *mutator_thread(void *arg)
{
    (void)arg;
    for (int i = 0; i < RACE_ITERS; i++) {
        pthread_mutex_lock(&g_victimLock);
        UInt256 old = g_victim;
        pthread_mutex_unlock(&g_victimLock);
        BRTransaction *t = makeTx(1000u + (uint32_t)i);
        BRWalletRegisterTransaction(g_wallet, t);
        pthread_mutex_lock(&g_victimLock);
        g_victim = t->txHash;
        pthread_mutex_unlock(&g_victimLock);
        usleep(20);
        BRWalletRemoveTransaction(g_wallet, old);
    }
    g_mutatorDone = 1;
    return NULL;
}
static int arm_race(void)
{
    BRTransaction *anchor = makeTx(900), *first = makeTx(901);
    BRWalletRegisterTransaction(g_wallet, anchor);
    BRWalletRegisterTransaction(g_wallet, first);
    g_victim = first->txHash;
    pthread_t r, m;
    pthread_create(&r, NULL, reader_thread, NULL);
    pthread_create(&m, NULL, mutator_thread, NULL);
    pthread_join(r, NULL);
    pthread_join(m, NULL);
    return 0;
}

#ifndef TX_READ_SNAPSHOT_UNFIXED
static int sameTx(const BRTransaction *a, const BRTransaction *b)
{
    if (!UInt256Eq(a->txHash, b->txHash) || !UInt256Eq(a->wtxHash, b->wtxHash)) return 0;
    if (a->version != b->version || a->lockTime != b->lockTime) return 0;
    if (a->blockHeight != b->blockHeight || a->timestamp != b->timestamp) return 0;
    if (a->inCount != b->inCount || a->outCount != b->outCount) return 0;
    for (size_t i = 0; i < a->inCount; i++) {
        const BRTxInput *x = &a->inputs[i], *y = &b->inputs[i];
        if (!UInt256Eq(x->txHash, y->txHash) || x->index != y->index || x->sequence != y->sequence) return 0;
        if (x->sigLen != y->sigLen || (x->sigLen && memcmp(x->signature, y->signature, x->sigLen))) return 0;
        if (x->scriptLen != y->scriptLen || (x->scriptLen && memcmp(x->script, y->script, x->scriptLen))) return 0;
    }
    for (size_t i = 0; i < a->outCount; i++) {
        const BRTxOutput *x = &a->outputs[i], *y = &b->outputs[i];
        if (x->amount != y->amount || strcmp(x->address, y->address)) return 0;
        if (x->scriptLen != y->scriptLen || memcmp(x->script, y->script, x->scriptLen)) return 0;
    }
    return 1;
}

static int arm_contract(void)
{
    BRTransaction *a = makeTx(30), *b = makeTx(31), *c = makeTx(32);
    a->timestamp = 1700000100u; b->timestamp = 1700000200u; c->timestamp = 1700000300u;
    finalizeTxHash(a); finalizeTxHash(b); finalizeTxHash(c);
    BRWalletRegisterTransaction(g_wallet, a);
    BRWalletRegisterTransaction(g_wallet, b);
    BRWalletRegisterTransaction(g_wallet, c);
    UInt256 zero = UINT256_ZERO, unknown;
    memset(unknown.u8, 0x11, sizeof(unknown.u8));

    /* one */
    CHECK(BRWalletCopyTransactionForHash(g_wallet, unknown) == NULL, "contract: a copy for a hash the wallet does not hold");
    CHECK(BRWalletCopyTransactionForHash(g_wallet, zero) == NULL, "contract: a copy for the zero hash");
    BRTransaction *own = BRWalletTransactionForHash(g_wallet, b->txHash);
    BRTransaction *cp = BRWalletCopyTransactionForHash(g_wallet, b->txHash);
    CHECK(cp != NULL && cp != own, "contract: the copy is not an object of its own");
    if (!cp) return 1;
    CHECK(sameTx(cp, own), "contract: the copy differs from the wallet's record");
    CHECK(BRWalletAmountReceivedFromTx(g_wallet, cp) == BRWalletAmountReceivedFromTx(g_wallet, own), "contract: received differs on the copy");
    CHECK(BRWalletAmountSentByTx(g_wallet, cp) == BRWalletAmountSentByTx(g_wallet, own), "contract: sent differs on the copy");
    CHECK(BRWalletFeeForTx(g_wallet, cp) == BRWalletFeeForTx(g_wallet, own), "contract: fee differs on the copy");
    CHECK(BRWalletTransactionIsValid(g_wallet, cp) == BRWalletTransactionIsValid(g_wallet, own), "contract: validity differs on the copy");
    cp->blockHeight = 12345; cp->outputs[0].amount = 1;
    CHECK(own->blockHeight != 12345 && own->outputs[0].amount != 1, "contract: writing the copy changed the wallet's record");
    BRTransactionFree(cp);
    CHECK(BRWalletTransactionForHash(g_wallet, b->txHash) == own, "contract: freeing the copy touched the wallet's record");

    /* all */
    size_t n = BRWalletTransactions(g_wallet, NULL, 0);
    CHECK(BRWalletCopyTransactions(g_wallet, NULL, 0) == n, "contract: the copy count query differs from BRWalletTransactions");
    CHECK(BRWalletTransactionHashes(g_wallet, NULL, 0) == n, "contract: the hash count query differs from BRWalletTransactions");
    BRTransaction *ref[3], *cps[3];
    UInt256 hs[3];
    CHECK(BRWalletTransactions(g_wallet, ref, 3) == 3, "contract: the wallet does not hold three");
    CHECK(BRWalletCopyTransactions(g_wallet, cps, 3) == 3, "contract: three copies not written");
    CHECK(BRWalletTransactionHashes(g_wallet, hs, 3) == 3, "contract: three hashes not written");
    for (size_t i = 0; i < 3; i++) {
        CHECK(cps[i] && cps[i] != ref[i] && sameTx(cps[i], ref[i]), "contract: copy %zu is not the wallet's entry %zu", i, i);
        CHECK(UInt256Eq(hs[i], ref[i]->txHash), "contract: hash %zu is not the wallet's entry %zu", i, i);
        if (cps[i]) BRTransactionFree(cps[i]);
    }
    /* a smaller array takes the first entries, as BRWalletTransactions does */
    BRTransaction *two[2] = { NULL, NULL };
    UInt256 h2[2];
    CHECK(BRWalletCopyTransactions(g_wallet, two, 2) == 2, "contract: a two-slot copy did not write two");
    CHECK(BRWalletTransactionHashes(g_wallet, h2, 2) == 2, "contract: a two-slot hash read did not write two");
    for (size_t i = 0; i < 2; i++) {
        CHECK(two[i] && UInt256Eq(two[i]->txHash, ref[i]->txHash), "contract: two-slot copy %zu out of order", i);
        CHECK(UInt256Eq(h2[i], ref[i]->txHash), "contract: two-slot hash %zu out of order", i);
        if (two[i]) BRTransactionFree(two[i]);
    }
    return 0;
}
#endif

int main(int argc, char **argv)
{
    const char *arm = argc > 1 ? argv[1] : "";
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) { printf("FATAL: wallet not created\n"); return 2; }
    BRAddress addr = BRWalletReceiveAddress(g_wallet, 1);
    g_spkLen = BRAddressScriptPubKey(g_spk, sizeof(g_spk), addr.s);
    if (g_spkLen == 0) { printf("FATAL: scriptPubKey did not resolve\n"); return 2; }

    int r;
    if (!strcmp(arm, "lookup")) r = arm_lookup();
    else if (!strcmp(arm, "list")) r = arm_list();
    else if (!strcmp(arm, "hashes")) r = arm_hashes();
    else if (!strcmp(arm, "race")) r = arm_race();
#ifndef TX_READ_SNAPSHOT_UNFIXED
    else if (!strcmp(arm, "contract")) r = arm_contract();
#endif
    else { printf("unknown arm '%s'\n", arm); return 2; }

    BRWalletFree(g_wallet);
    if (r || g_fail) { printf("tx_read_snapshot_kat %s: %d check(s) failed\n", arm, g_fail); return 1; }
    printf("tx_read_snapshot_kat %s: ok\n", arm);
    return 0;
}
