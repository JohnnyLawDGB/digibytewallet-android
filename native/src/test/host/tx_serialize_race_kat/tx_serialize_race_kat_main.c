// Self-validating red-before-green KAT for finding I1: the tx-checkpoint
// LOCK-RELEASE-THEN-USE race in getSerializedTransactions
// (jni_transaction_persist.c) — SAME CLASS as the saveBlocks race, on the
// transaction-persist path instead of the block-persist path.
//
// It reproduces the DEFECT PATTERN of the shipped
// Java_..._getSerializedTransactions, driving the REAL BRWallet /
// BRTransaction primitives under a 2-thread race:
//   - a "checkpoint" thread pulls the wallet's tx pointers out via the public
//     BRWalletTransactions (which locks, COPIES the raw BRTransaction* pointers,
//     then UNLOCKS — BRWallet.c:870-881) and then serializes each snapshotted
//     pointer with NO lock held (BRTransactionSerialize + reads of
//     tx->blockHeight / tx->timestamp — exactly the JNI size-pass/write-pass);
//   - a "mutator" thread frees one of those very pointers via the public
//     BRWalletRemoveTransaction (BRWallet.c:1706 → BRTransactionFree at :1765),
//     modelling the at-tip cleanup (BRPeerManager.c:856) and the Kotlin-driven
//     removeTransaction (jni_transaction.c:323) that run on OTHER threads while
//     the every-20s writer tick is mid-serialize.
//
// TXSERIALIZE_FIXED selects the two code shapes:
//   * 0 (UNFIXED) -> snapshot-then-UNLOCK-then-serialize: the checkpoint reads a
//     tx the mutator can free in the window -> ASan heap-use-after-free (the real
//     bug; the read-after-free lands on tx->inCount / tx->inputs / tx->outputs
//     inside BRTransactionSerialize, or on the tx->blockHeight/timestamp reads).
//   * 1 (FIXED)   -> serialize UNDER wallet->lock via the new
//     BRWalletSerializeTransactions: the mutator's BRWalletRemoveTransaction
//     blocks on wallet->lock until the whole serialize completes, so no tx can be
//     freed mid-serialize -> no UAF, clean exit.
//
// This drives the REAL production functions on BOTH arms (the RED arm is the
// literal shipped jni_transaction_persist.c shape; the GREEN arm calls the real
// BRWalletSerializeTransactions the fix adds), so it is both a class gate AND a
// direct test of the fix.
//
// BOUNDED (fixed iteration count) so it can NEVER hang; ASan halts at the first
// UAF (ASAN_OPTIONS=halt_on_error). run.sh builds+runs BOTH shapes and requires
// UNFIXED=red AND FIXED=green, so a harness that can't detect the bug fails the KAT.
#include <pthread.h>
#include <sched.h>
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

#ifndef TXSERIALIZE_FIXED
#define TXSERIALIZE_FIXED 0
#endif

#define ITERS 3000

static BRWallet *g_wallet;
static uint8_t   g_spk[64];
static size_t    g_spkLen;
static volatile int g_stop;

// non-NULL, zero-length signature/witness placeholder — satisfies
// BRTransactionIsSigned's pointer-only check that BRWalletRegisterTransaction
// asserts. Same trick cf_confirm_kat / digidollar_wallet_kat use.
static const uint8_t kPlaceholder[1] = {0};

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// tx->txHash is only populated by BRTransactionParse (or BRTransactionSign's
// round trip), never by AddInput/AddOutput alone — round-trip serialize->parse
// to populate it, mirroring cf_confirm_kat's finalizeTxHash.
static void finalizeTxHash(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(n);
    size_t len = BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

// A tx paying our own watched scriptPubKey (so BRWalletRegisterTransaction keeps
// it), spending a fabricated never-registered prevout. `nonce` is woven into the
// prevout index + amount so every generated tx gets a UNIQUE txHash across all
// ITERS (a repeated hash would make BRWalletRemoveTransaction a no-op and
// silently defuse the race). The spent prevout isn't validated here.
static BRTransaction *makeTx(uint32_t nonce)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;
    memset(prevHash.u8, 0x5a, sizeof(prevHash.u8));
    BRTransactionAddInput(tx, prevHash, nonce, 0, g_spk, g_spkLen,
                          kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, 100000 + nonce, g_spk, g_spkLen);
    finalizeTxHash(tx);
    return tx;
}

// The checkpoint thread — models Java_..._getSerializedTransactions exactly.
static void *checkpoint_thread(void *arg)
{
    (void)arg;
    for (int i = 0; i < ITERS && !g_stop; i++) {
#if TXSERIALIZE_FIXED
        // FIXED: the fix's single locked entry point. Both the size pass and the
        // write pass run under wallet->lock; a concurrent free cannot land between
        // them. Two-call idiom: size, then fill.
        size_t n = BRWalletSerializeTransactions(g_wallet, NULL, 0);
        uint8_t *buf = malloc(n ? n : 1);
        size_t w = BRWalletSerializeTransactions(g_wallet, buf, n);
        (void)w;
        free(buf);
#else
        // UNFIXED: the literal shipped jni_transaction_persist.c shape.
        size_t txCount = BRWalletTransactions(g_wallet, NULL, 0); // locks/unlocks
        if (txCount) {
            BRTransaction **txs = malloc(txCount * sizeof(*txs));
            txCount = BRWalletTransactions(g_wallet, txs, txCount); // COPIES ptrs, UNLOCKS
            usleep(60); // widen the unlock->use window: the mutator frees a snapshot tx here
            // size pass + write pass with NO lock held — reads txs[k]->* after free = UAF
            size_t totalSize = 4;
            for (size_t k = 0; k < txCount; k++)
                totalSize += 4 + 4 + 4 + BRTransactionSerialize(txs[k], NULL, 0);
            uint8_t *buf = malloc(totalSize);
            size_t pos = 0;
            UInt32SetLE(&buf[pos], (uint32_t)txCount); pos += 4;
            for (size_t k = 0; k < txCount; k++) {
                size_t len = BRTransactionSerialize(txs[k], NULL, 0);
                UInt32SetLE(&buf[pos], (uint32_t)len);            pos += 4;
                UInt32SetLE(&buf[pos], txs[k]->blockHeight);      pos += 4;
                UInt32SetLE(&buf[pos], txs[k]->timestamp);        pos += 4;
                BRTransactionSerialize(txs[k], &buf[pos], len);   pos += len;
            }
            free(buf);
            free(txs);
        }
#endif
    }
    return NULL;
}

// The mutator thread — models the at-tip cleanup / Kotlin-driven remove that
// frees a wallet tx on a peer/other thread while the checkpoint is mid-serialize.
static void *mutator_thread(void *arg)
{
    UInt256 victim = *(UInt256 *)arg; // the pre-registered initial victim
    for (int i = 0; i < ITERS && !g_stop; i++) {
        BRWalletRemoveTransaction(g_wallet, victim); // frees the previous victim
        BRTransaction *t = makeTx((uint32_t)i + 1);  // fresh unique replacement
        BRWalletRegisterTransaction(g_wallet, t);
        victim = t->txHash;                          // next iteration frees this one
    }
    return NULL;
}

int main(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) { printf("FATAL: wallet not created\n"); return 1; }

    BRAddress addr = BRWalletReceiveAddress(g_wallet, 1); // native-segwit external addr[0]
    g_spkLen = BRAddressScriptPubKey(g_spk, sizeof(g_spk), addr.s);
    if (g_spkLen == 0) { printf("FATAL: scriptPubKey did not resolve\n"); return 1; }

    // A stable anchor tx (never removed) so the snapshot is never empty, plus the
    // initial victim the mutator will free on its first iteration.
    BRTransaction *anchor = makeTx(0xA0000000u);
    BRWalletRegisterTransaction(g_wallet, anchor);
    BRTransaction *victim0 = makeTx(0xB0000000u);
    BRWalletRegisterTransaction(g_wallet, victim0);
    UInt256 victimHash = victim0->txHash;

    pthread_t checkpoint, mutator;
    pthread_create(&checkpoint, NULL, checkpoint_thread, NULL);
    pthread_create(&mutator,    NULL, mutator_thread,    &victimHash);
    pthread_join(checkpoint, NULL);
    pthread_join(mutator,    NULL);

    printf("tx_serialize_race_kat: %d iters, TXSERIALIZE_FIXED=%d, no fault\n",
           ITERS, TXSERIALIZE_FIXED);
    return 0;
}
