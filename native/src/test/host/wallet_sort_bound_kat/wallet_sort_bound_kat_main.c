// Host KAT: the sorted insertion of an unconfirmed transaction cannot be driven
// superlinear in the length of a peer-relayed input chain.
//
// A connected peer can relay unconfirmed transactions that pay the wallet; each is
// registered (BRWalletRegisterTransaction -> _BRWalletInsertTx) and kept sorted by an
// insertion sort whose comparison, _BRWalletTxCompare, calls _BRWalletTxIsAscending.
// That comparison walks tx1's input chain transitively. A reverse-ordered chain of N
// such transactions makes every one of the O(N^2) comparisons walk O(N) links, so the
// whole insertion is cubic and runs under the wallet lock (a multi-second UI freeze /
// Android ANR for N in the low thousands).
//
// The shipped code bounds the links any one comparison walks (WALLET_MAX_ASCENDING_STEPS),
// which is far above the ancestor depth of any honest unconfirmed chain, so the walk no
// longer scales with the chain length. The comparison arm (-DWALLET_ASCENDING_DEPTH_UNFIXED)
// leaves the walk unbounded.
//
// The gate is deterministic, not a wall-clock race: the KAT build defines
// KAT_ASCENDING_COUNTER, so BRWallet.c records the most links any single comparison
// walked (_kat_asc_max). The shipped arm keeps that at or below the per-comparison
// bound no matter how long the chain is; the comparison arm lets it grow with the
// chain, which is the superlinear behaviour the bound removes.
//
// Usage:
//   wallet_sort_bound_kat bound <N>    build+register a reverse-ordered N-chain, then
//                                      print "registered <N> maxsteps <m>"
//   wallet_sort_bound_kat honest       register a small, mixed set of unconfirmed txs and
//                                      print the resulting order; both arms must match

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRTransaction.h"

extern size_t _kat_asc_steps, _kat_asc_max;

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon abandon abandon abandon abandon about";

static const uint8_t kPlaceholder[1] = {0};

// BRWalletRegisterTransaction asserts BRTransactionIsSigned (non-NULL sig/witness
// pointers); these synthetic txs carry placeholder stand-ins and a real hash.
static void finalizeTxHash(BRTransaction *tx) {
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = (uint8_t *)malloc(n ? n : 1);
    size_t len = BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

// A one-in one-out unconfirmed tx that spends prevHash:prevN and pays spk.
static BRTransaction *mkTx(const uint8_t *spk, size_t spkLen, UInt256 prevHash, uint32_t prevN) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 1;
    BRTransactionAddInput(tx, prevHash, prevN, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 100000000, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

static BRWallet *newWallet(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

// Build a chain tx[0] <- tx[1] <- ... <- tx[N-1] (tx[k] spends tx[k+1] vout0), all
// paying our address, and register them youngest-first so each insertion must sort
// against a chain that is already present -- the reverse-ordered feed in the report.
static int run_bound(size_t N) {
    BRWallet *w = newWallet();
    if (! w) { printf("FATAL: no wallet\n"); return 2; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    BRTransaction **txs = (BRTransaction **)calloc(N, sizeof(*txs));
    UInt256 root; memset(root.u8, 0xab, 32);
    // tx[N-1] is the oldest (spends the external root); tx[k] spends tx[k+1].
    for (size_t k = N; k > 0; k--) {
        size_t i = k - 1;
        UInt256 prev = (i + 1 < N) ? txs[i + 1]->txHash : root;
        txs[i] = mkTx(spk, spkLen, prev, 0);
    }
    // Register youngest-first (tx[0] .. tx[N-1]): reverse of the dependency order.
    for (size_t i = 0; i < N; i++) BRWalletRegisterTransaction(w, txs[i]);

    printf("registered %zu maxsteps %zu\n", N, _kat_asc_max);
    BRWalletFree(w);
    free(txs);
    return 0;
}

// A small, mixed set: a short real chain plus independent txs, registered in a fixed
// order. The resulting wallet order must be identical in both arms (the bound never
// changes the order of an honest set).
static int run_honest(void) {
    BRWallet *w = newWallet();
    if (! w) { printf("FATAL: no wallet\n"); return 2; }
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);

    enum { M = 8 };
    BRTransaction *txs[M];
    // A 5-long chain c0<-c1<-c2<-c3<-c4 plus 3 independent txs from distinct roots.
    UInt256 r0; memset(r0.u8, 0x01, 32);
    txs[4] = mkTx(spk, spkLen, r0, 0);
    txs[3] = mkTx(spk, spkLen, txs[4]->txHash, 0);
    txs[2] = mkTx(spk, spkLen, txs[3]->txHash, 0);
    txs[1] = mkTx(spk, spkLen, txs[2]->txHash, 0);
    txs[0] = mkTx(spk, spkLen, txs[1]->txHash, 0);
    for (int k = 0; k < 3; k++) {
        UInt256 r; memset(r.u8, (uint8_t)(0x40 + k), 32);
        txs[5 + k] = mkTx(spk, spkLen, r, 0);
    }
    // Register in a deliberately scrambled order.
    int order[M] = { 2, 0, 5, 4, 7, 1, 6, 3 };
    for (int i = 0; i < M; i++) BRWalletRegisterTransaction(w, txs[order[i]]);

    size_t n = BRWalletTransactions(w, NULL, 0);
    BRTransaction **out = (BRTransaction **)calloc(n ? n : 1, sizeof(*out));
    n = BRWalletTransactions(w, out, n);
    printf("order:");
    for (size_t i = 0; i < n; i++) printf(" %02x", out[i]->txHash.u8[0] ^ out[i]->txHash.u8[31]);
    printf("\n");
    free(out);
    BRWalletFree(w);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "honest") == 0) return run_honest();
    if (argc >= 3 && strcmp(argv[1], "bound") == 0) return run_bound((size_t)strtoul(argv[2], NULL, 10));
    fprintf(stderr, "usage: %s bound <N> | honest\n", argv[0]);
    return 2;
}
