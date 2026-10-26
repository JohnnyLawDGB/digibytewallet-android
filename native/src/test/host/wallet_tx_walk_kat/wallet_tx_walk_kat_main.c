// Host KAT: the wallet's own validity walkers read no parent through a pointer the lock no longer holds
// (4.1.2 row 5, K2).
//
// BRWalletTransactionIsValid / IsPending / IsVerified and BRWalletOutpointAssetState look a parent up and
// then read it -- and recurse into it -- after BRWalletTransactionForHash has released the wallet lock.
// BRWalletRemoveTransaction on another thread (the at-tip cleanup, a JNI remove) frees the parent in
// that gap. The core is built with -DWALLET_TX_READ_HOOK: wallet_tx_read_hook runs at exactly the point
// a lookup has returned, and here removes the parent on another thread, deterministically.
//
// Arms (argv[1]), each in its own process:  valid, pending, verified  -- on a copy of the child P2,
// whose parent P is removed mid-walk;  asset -- BRWalletOutpointAssetState on P itself.
// RED   -DWALLET_TX_WALK_UNFIXED: every arm reads the freed parent (AddressSanitizer heap-use-after-free).
// GREEN every arm runs clean and answers.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRInt.h"

static BRWallet *g_wallet;
static uint8_t g_spk[64];
static size_t g_spkLen;
static const uint8_t kPlaceholder[1] = {0};

static UInt256 g_victim;
static int g_armed = 0, g_fired = 0;

static void *remover(void *arg) { BRWalletRemoveTransaction(g_wallet, *(UInt256 *)arg); return NULL; }

void wallet_tx_read_hook(BRWallet *wallet, UInt256 txHash)
{
    (void)wallet;
    if (! g_armed || ! UInt256Eq(txHash, g_victim)) return;
    g_armed = 0;
    UInt256 h = txHash;
    pthread_t t;
    pthread_create(&t, NULL, remover, &h);
    pthread_join(t, NULL);
    g_fired = 1;
}

static void finalizeTxHash(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(n);
    size_t len = BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

static BRTransaction *payWallet(UInt256 prevHash, uint32_t prevN, uint32_t nonce)
{
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prevHash, prevN, 0, g_spk, g_spkLen, kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, 10000000ULL + nonce, g_spk, g_spkLen);
    BRTransactionAddOutput(tx, 20000000ULL + nonce, g_spk, g_spkLen);
    tx->timestamp = 1700000000u + nonce;
    finalizeTxHash(tx);
    return tx;
}

int main(int argc, char **argv)
{
    const char *arm = argc > 1 ? argv[1] : "";
    uint8_t seed[64];
    setvbuf(stdout, NULL, _IONBF, 0);
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    g_wallet = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    BRAddress addr = BRWalletReceiveAddress(g_wallet, 1);
    g_spkLen = BRAddressScriptPubKey(g_spk, sizeof(g_spk), addr.s);

    UInt256 foreign; memset(foreign.u8, 0x5a, sizeof(foreign.u8));
    BRTransaction *P = payWallet(foreign, 0, 1);
    UInt256 pHash = P->txHash;
    BRTransaction *P2 = payWallet(pHash, 0, 2);
    UInt256 p2Hash = P2->txHash;
    if (! BRWalletRegisterTransactionTrusted(g_wallet, P) || ! BRWalletRegisterTransactionTrusted(g_wallet, P2)) {
        printf("FAIL: setup: the wallet did not take P and P2\n"); return 2;
    }
    BRTransaction *child = BRWalletCopyTransactionForHash(g_wallet, p2Hash);   // the caller's own copy
    g_victim = pHash;
    g_armed = 1;

    int answer = -99;
    if (! strcmp(arm, "valid")) answer = BRWalletTransactionIsValid(g_wallet, child);
    else if (! strcmp(arm, "pending")) answer = BRWalletTransactionIsPending(g_wallet, child);
    else if (! strcmp(arm, "verified")) answer = BRWalletTransactionIsVerified(g_wallet, child);
    else if (! strcmp(arm, "asset")) answer = BRWalletOutpointAssetState(g_wallet, pHash, 1);
    else { printf("unknown arm '%s'\n", arm); return 2; }

    if (! g_fired) { printf("FAIL: %s: the walk never looked the parent up; the arm proves nothing\n", arm); return 1; }
    if (BRWalletTransactionForHash(g_wallet, pHash)) { printf("FAIL: %s: the parent was not removed\n", arm); return 1; }
    BRTransactionFree(child);
    BRWalletFree(g_wallet);
    printf("wallet_tx_walk_kat %s: answered %d with the parent removed mid-walk\n", arm, answer);
    return 0;
}
