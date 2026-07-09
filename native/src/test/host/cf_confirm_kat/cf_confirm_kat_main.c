// Host KAT: proves _peerRelayedBlockTxns (BRPeerManager.c, wired in submodule
// commits 61d41da "add relayedBlockTxns callback for CF full-block confirmation"
// and 3dc107de "confirm wallet txs from CF full-block relayedBlockTxns
// callback") actually stamps a wallet tx's blockHeight when a compact-filter
// -downloaded full "block" message's txs are delivered. This is the
// behavioral core that flips a COMPACT_FILTERS_ONLY wallet's own sends from
// TX_UNCONFIRMED to confirmed, independent of the regular headers/
// merkleblock chain-extension path.
//
// Approach A (full handler, not just the underlying BRWalletUpdateTransactions
// primitive): #include "BRPeerManager.c" directly to reach the file-static
// _peerRelayedBlockTxns and the otherwise-opaque BRPeerManagerStruct /
// BRPeerCallbackInfo definitions -- same #include-a-.c-for-statics pattern
// bip341_signtx_kat and digidollar_send_kat use to reach
// _BRTransactionTaprootSighash. A real BRPeerManager is built via the public
// BRPeerManagerNew() (so every internal array/mutex is correctly
// initialized -- hand-rolling the struct risks a missed init that only
// crashes at runtime), a real BRWallet is built via BRWalletNew() with a
// test mnemonic's derived master pubkey (same derivation pattern as
// digidollar_wallet_kat), and a synthetic BRMerkleBlock is inserted directly
// into manager->blocks and set as manager->lastBlock. BRMerkleBlockHash/Eq
// (BRMerkleBlock.h) only compare ->blockHash, so a hand-set height + an
// arbitrary distinct hash stands in for a real CF-downloaded block header
// for this handler's purposes -- no byte-valid header or real PoW needed.
//
// Because main.c #include-s BRPeerManager.c, BRPeerManager.c must NOT also
// be passed as a separate compilation unit on the clang command line
// (run.sh) -- every other BRPeerManager.c dependency (BRPeer.c, BRWallet.c,
// BRSet.c, BRMerkleBlock.c, BRBloomFilter.c, BRCompactFilterChain.c,
// BRGCSFilter.c, BRWalletFilterElements.c, BRNetwork.c, plus the whole
// address/key/crypto chain those pull in) IS linked separately, same "why so
// many .c files" rationale as bip341_signtx_kat/run.sh.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// non-NULL, zero-length signature/witness placeholder -- satisfies
// BRTransactionIsSigned's pointer-only check (BRWalletRegisterTransaction
// asserts it). These synthetic KAT txs aren't cryptographically signed
// (the confirmation bookkeeping under test doesn't touch signatures), same
// trick digidollar_wallet_kat uses.
static const uint8_t kPlaceholder[1] = {0};

// tx->txHash is only ever computed by BRTransactionParse (from serialized
// bytes) or by BRTransactionSign's post-sign round trip -- never by
// AddInput/AddOutput alone. Round-trip serialize->parse to populate it
// before using the hash as a lookup key, mirroring digidollar_wallet_kat's
// finalizeTxHash.
static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// canonical all-zeros mnemonic, same one digidollar_wallet_kat/taproot KATs use
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// builds a tx paying `spk` (our wallet's own scriptPubKey) with `amount`,
// spending a fabricated (never-registered) prevout -- fine, because
// BRWalletRegisterTransaction/_BRWalletContainsTx only need the tx to *pay*
// an address the wallet watches; the spent prevout isn't validated for this
// confirmation-bookkeeping test.
static BRTransaction *payTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t prevoutSeed)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;
    memset(prevHash.u8, prevoutSeed, sizeof(prevHash.u8));
    BRTransactionAddInput(tx, prevHash, 0, 0, spk, spkLen, kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

// dummy BRMerkleBlock: BRMerkleBlockHash/Eq (BRMerkleBlock.h) only look at
// ->blockHash, so a hand-set height + arbitrary distinct hash byte pattern
// is sufficient to stand in for a real CF-downloaded block header for
// _peerRelayedBlockTxns's purposes.
static BRMerkleBlock *dummyBlock(uint32_t height, uint8_t hashSeed, uint32_t timestamp)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = timestamp;
    return b;
}

static void txStatusUpdateCb(void *info)
{
    int *fired = (int *)info;
    *fired = 1;
}

int main(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    check(wallet != NULL, "wallet created");
    if (!wallet) { printf("\nFATAL\n"); return 1; }

    BRAddress addr = BRWalletReceiveAddress(wallet, 1); // native-segwit external addr[0]
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr.s);
    check(spkLen > 0, "wallet address scriptPubKey resolves");

    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(manager != NULL, "peer manager created");
    if (!manager) { printf("\nFATAL\n"); return 1; }

    int txStatusFired = 0;
    BRPeerManagerSetCallbacks(manager, &txStatusFired, NULL, NULL, txStatusUpdateCb, NULL, NULL, NULL, NULL);

    // --- Test 1: block IS in manager->blocks and IS the main-chain tip -> the
    // wallet tx it names is confirmed at the block's height. This is the
    // core behavior: unconfirmed -> confirmed via the CF full-block path,
    // with no headers/merkleblock chain-extension call involved. ---
    BRTransaction *tx1 = payTx(spk, spkLen, 100000, 0x11);
    BRWalletRegisterTransaction(wallet, tx1);
    BRTransaction *found1 = BRWalletTransactionForHash(wallet, tx1->txHash);
    check(found1 != NULL, "test1: tx registered in wallet");
    check(found1 && found1->blockHeight == TX_UNCONFIRMED, "test1: tx starts TX_UNCONFIRMED");

    BRMerkleBlock *block1 = dummyBlock(500000, 0xAA, 1700000000);
    BRSetAdd(manager->blocks, block1);
    manager->lastBlock = block1; // block1 IS the tip -> trivially the main chain

    BRPeerCallbackInfo info1 = { .peer = NULL, .manager = manager, .hash = UINT256_ZERO };
    UInt256 txHashes1[1] = { tx1->txHash };
    _peerRelayedBlockTxns(&info1, block1->blockHash, txHashes1, 1);

    BRTransaction *after1 = BRWalletTransactionForHash(wallet, tx1->txHash);
    check(after1 != NULL && after1->blockHeight == block1->height,
          "test1: tx confirmed at block height after _peerRelayedBlockTxns");
    check(txStatusFired == 1, "test1: txStatusUpdate callback fired on confirmation");

    // --- Test 2: blockHash NOT present in manager->blocks -> no-op, the
    // named tx stays TX_UNCONFIRMED (header hasn't synced yet; production
    // comment: "the block will be re-requested/re-relayed once it is"). ---
    BRTransaction *tx2 = payTx(spk, spkLen, 100000, 0x22);
    BRWalletRegisterTransaction(wallet, tx2);
    UInt256 unknownBlockHash;
    memset(unknownBlockHash.u8, 0xFF, sizeof(unknownBlockHash.u8));
    txStatusFired = 0;
    UInt256 txHashes2[1] = { tx2->txHash };
    _peerRelayedBlockTxns(&info1, unknownBlockHash, txHashes2, 1);
    BRTransaction *after2 = BRWalletTransactionForHash(wallet, tx2->txHash);
    check(after2 != NULL && after2->blockHeight == TX_UNCONFIRMED,
          "test2: unknown blockHash is a no-op, tx stays TX_UNCONFIRMED");
    check(txStatusFired == 0, "test2: txStatusUpdate NOT fired on the no-op");

    // --- Test 3: a tx hash the wallet doesn't hold is filtered out by
    // BRWalletTransactionForHash inside the handler -- no crash, no
    // confirmation fires, and the unrelated already-unconfirmed tx2 stays
    // untouched by a call that names only this foreign hash. ---
    UInt256 nonWalletHash;
    memset(nonWalletHash.u8, 0x99, sizeof(nonWalletHash.u8));
    check(BRWalletTransactionForHash(wallet, nonWalletHash) == NULL, "test3: sanity -- hash truly not in wallet");
    txStatusFired = 0;
    UInt256 txHashes3[1] = { nonWalletHash };
    _peerRelayedBlockTxns(&info1, block1->blockHash, txHashes3, 1); // block1 IS in the set/main chain this time
    check(txStatusFired == 0, "test3: non-wallet tx hash filtered out -- no confirmation fired");
    BRTransaction *tx2Again = BRWalletTransactionForHash(wallet, tx2->txHash);
    check(tx2Again != NULL && tx2Again->blockHeight == TX_UNCONFIRMED,
          "test3: unrelated wallet tx unaffected by a call naming only a non-wallet hash");

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
