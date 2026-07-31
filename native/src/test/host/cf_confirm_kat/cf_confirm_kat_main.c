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
// BRSet.c, BRMerkleBlock.c, BRCompactFilterChain.c,
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
    // Line-buffered: test4's RED build is EXPECTED to die on SIGSEGV, and a crash
    // discards block-buffered stdout. run.sh's gate greps this output to prove the
    // crash happens at test4's call and not somewhere earlier, so the checks printed
    // before it must survive the crash.
    setvbuf(stdout, NULL, _IOLBF, 0);

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

    // --- Test 4: REMOTE-DoS REGRESSION GATE. A `block` message naming a
    // resident block that sits BELOW everything the main-chain walk can reach
    // must not crash.
    //
    // BRPeerManagerNew seeds every hardcoded checkpoint as a stub into
    // manager->blocks (the checkpoints loop in BRPeerManagerNewEx) and never
    // sets their prevBlock, so each stub's prevBlock is the zero hash.
    // _peerRelayedBlockTxns resolves `b` from the named hash, then walks b2 back
    // from manager->lastBlock to b's height to decide "is this the main chain".
    // That walk hops via prevBlock, so it yields b2 == NULL long before reaching
    // a deep stub. BRMerkleBlockEq (BRMerkleBlock.h) dereferences BOTH arguments
    // and blockHash is at struct offset 0, so the pre-fix shape reads 0x0.
    //
    // Any peer the wallet dials can drive this: the handler is not
    // request-gated, so one unsolicited well-formed `block` message replaying a
    // public historic block at a checkpoint height is enough. ---
    UInt256 zeroHash = UINT256_ZERO;
    UInt256 stubHash = UInt256Reverse(BRMainNetParams.checkpoints[0].hash);
    BRMerkleBlock *stub = BRSetGet(manager->blocks, &stubHash);
    check(stub != NULL, "test4: checkpoint stub is resident in manager->blocks");
    if (!stub) { printf("\nFATAL: no resident stub -- test4 would not reach the walk\n"); return 1; }
    check(stub->height == BRMainNetParams.checkpoints[0].height,
          "test4: resident stub is the height-0 checkpoint");
    check(UInt256IsZero(stub->prevBlock),
          "test4: stub prevBlock is zero -- the walk's next hop cannot resolve");
    check(BRSetGet(manager->blocks, &zeroHash) == NULL,
          "test4: nothing is keyed at the zero hash -- the walk really does miss");
    check(manager->lastBlock != NULL && manager->lastBlock->height > stub->height,
          "test4: lastBlock is above the stub, so the walk loop body runs");

    BRTransaction *tx4 = payTx(spk, spkLen, 100000, 0x44);
    BRWalletRegisterTransaction(wallet, tx4);
    txStatusFired = 0;
    UInt256 txHashes4[1] = { tx4->txHash };
    _peerRelayedBlockTxns(&info1, stubHash, txHashes4, 1); // RED: SIGSEGV at 0x0 here

    BRTransaction *after4 = BRWalletTransactionForHash(wallet, tx4->txHash);
    check(after4 != NULL && after4->blockHeight == TX_UNCONFIRMED,
          "test4: unprovable main chain is a no-op, tx stays TX_UNCONFIRMED");
    check(txStatusFired == 0, "test4: txStatusUpdate NOT fired for an unprovable main chain");

    // --- Test 5: positive control, re-asserted AFTER the guard. A guard that
    // bailed out on everything would still pass test4; this must still confirm. ---
    BRTransaction *tx5 = payTx(spk, spkLen, 100000, 0x55);
    BRWalletRegisterTransaction(wallet, tx5);
    txStatusFired = 0;
    UInt256 txHashes5[1] = { tx5->txHash };
    _peerRelayedBlockTxns(&info1, block1->blockHash, txHashes5, 1); // block1 is still the tip

    BRTransaction *after5 = BRWalletTransactionForHash(wallet, tx5->txHash);
    check(after5 != NULL && after5->blockHeight == block1->height,
          "test5: positive control -- a main-chain tip block still confirms after the guard");
    check(txStatusFired == 1, "test5: positive control -- txStatusUpdate still fires");

    // --- Test 6: FUND-SAFETY GATE for the cached BIP 158 element set.
    //
    // _peerRelayedCFilter used to rebuild the wallet's element set for EVERY arriving
    // cfilter, which measured as 98.8% of the per-filter cost. It is now cached on the
    // manager and invalidated when the wallet's address COUNT changes.
    //
    // The failure mode that matters is not slowness, it is silence: if the cache goes
    // stale after the wallet derives a new address, a filter for a block paying that
    // address does not match, the block is never fetched, and the payment is NEVER SEEN.
    // So this asserts the cache observes an address added AFTER it was first built.
    //
    // It tests the element SET rather than a GCS match because the core has no filter
    // BUILDER (BRGCSFilter only parses and matches), so a filter containing a chosen
    // scriptPubKey cannot be synthesised here. The element set is exactly the input
    // BRGCSFilterMatchAny consumes, and staleness is what can break. ---
    BRWalletFilterElements *fe1 = _BRPeerManagerFilterElementsLocked(manager);
    check(fe1 != NULL && fe1->count > 0, "test6: element set builds");
    if (! fe1) { printf("\nFATAL: no element set\n"); return 1; }
    size_t count1 = fe1->count;

    // Cached, not rebuilt: with no mutation the SAME pointer must come back. This is what
    // stops a future refactor from quietly restoring the per-filter rebuild.
    check(_BRPeerManagerFilterElementsLocked(manager) == fe1,
          "test6: unchanged address set returns the cached list (no per-filter rebuild)");

    // Pin a brand-new address, the way the JNI watch-set path does. This mutates the
    // wallet WITHOUT going through the peer manager at all — the case that defeats
    // invalidating only at manager-visible sites.
    //
    // The address must be REAL. A hand-written string does not encode, so
    // BRAddressScriptPubKey returns 0, the element is legitimately `dropped`, and the
    // staleness assertion below would pass/fail for the wrong reason (measured: it did).
    // So derive genuine addresses from a SECOND wallet on a different seed — which is also
    // exactly what watching an external address means.
    uint8_t seed2[64];
    memcpy(seed2, seed, sizeof(seed2));
    seed2[0] ^= 0xFF;   // a different seed => different, valid, non-wallet-1 addresses
    BRWallet *other = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed2, sizeof(seed2)));
    check(other != NULL, "test6: second wallet built (source of real external addresses)");
    if (! other) { printf("\nFATAL\n"); return 1; }

    BRAddress freshAddr = BRWalletReceiveAddress(other, 1);
    uint8_t freshSpk[64];
    size_t freshSpkLen = BRAddressScriptPubKey(freshSpk, sizeof(freshSpk), freshAddr.s);
    check(freshSpkLen > 0, "test6: the new watched address has an encodable scriptPubKey");
    // It must not already be in wallet 1, or the assertion would be vacuous.
    int preExisting = 0;
    for (size_t i = 0; i < fe1->count; i++) {
        if (fe1->elementLens[i] == freshSpkLen && memcmp(fe1->elements[i], freshSpk, freshSpkLen) == 0)
            preExisting = 1;
    }
    check(preExisting == 0, "test6: the new address was NOT already in the cached set");

    BRWalletAddWatchedAddress(wallet, freshAddr.s);

    BRWalletFilterElements *fe2 = _BRPeerManagerFilterElementsLocked(manager);
    check(fe2 != NULL, "test6: element set still builds after the wallet changed");

    int foundFresh = 0, foundOriginal = 0;
    for (size_t i = 0; fe2 && i < fe2->count; i++) {
        if (fe2->elementLens[i] == freshSpkLen && memcmp(fe2->elements[i], freshSpk, freshSpkLen) == 0)
            foundFresh = 1;
        if (fe2->elementLens[i] == spkLen && memcmp(fe2->elements[i], spk, spkLen) == 0)
            foundOriginal = 1;
    }

    // RED here on the naive-cache shape (-DCF_ELEMS_CACHE_NOINVALIDATE): fe2 == fe1, so
    // the new element is absent and this fails by ASSERTION, not by crashing — the
    // production bug is a silent miss, and a gate that crashed instead would be proving
    // something else.
    check(foundFresh == 1, "test6: an address added AFTER the cache was built IS matched");
    // Positive control: a cache that rebuilt into an empty/garbage set would pass the
    // check above only if it also lost this one.
    check(foundOriginal == 1, "test6: positive control -- the pre-existing address is still matched");
    check(fe2 && fe2->count > count1, "test6: the element set grew by the new address");

    // Negative control: an address the wallet does not hold must NOT be present, so the
    // test cannot pass by matching indiscriminately.
    BRAddress foreignAddr = BRWalletReceiveAddress(other, 0); // legacy-form addr from wallet 2, never pinned
    uint8_t foreignSpk[64];
    size_t foreignSpkLen = BRAddressScriptPubKey(foreignSpk, sizeof(foreignSpk), foreignAddr.s);
    check(foreignSpkLen > 0, "test6: the foreign control address encodes");
    int foundForeign = 0;
    for (size_t i = 0; fe2 && i < fe2->count; i++) {
        if (fe2->elementLens[i] == foreignSpkLen &&
            memcmp(fe2->elements[i], foreignSpk, foreignSpkLen) == 0) foundForeign = 1;
    }
    check(foundForeign == 0, "test6: negative control -- a foreign address is NOT in the set");

    // --- Test 7: the cache key must include the NETWORK.
    //
    // The emitted element BYTES are a function of (address strings, network), not of the
    // address set alone: BRAddressScriptPubKey encodes per BRNetworkIsTestnet(). So a
    // network switch changes the elements while the address generation AND count both stay
    // identical. An adversarial probe measured exactly this: count 645 -> 645 unchanged,
    // elements 645 -> 0.
    //
    // This is what refuted a count-only key, and the dangerous variant is a PARTIAL drop:
    // some addresses stop encoding while others still do, so the set stays non-empty and
    // the build-failure retry never fires — a permanently wrong element set, silently.
    //
    // RED on -DCF_ELEMS_CACHE_COUNT_ONLY (and on -DCF_ELEMS_CACHE_NOINVALIDATE). ---
    BRWalletFilterElements *feMain = _BRPeerManagerFilterElementsLocked(manager);
    size_t mainCount = (feMain ? feMain->count : 0);
    check(mainCount > 0, "test7: mainnet element set is non-empty");

    uint64_t genBefore = 0; size_t cntBefore = 0;
    BRWalletAddrSetKey(wallet, &genBefore, &cntBefore);

    BRSetNetwork(1);   // switch to testnet: same addresses, different encoding rules

    uint64_t genAfter = 0; size_t cntAfter = 0;
    BRWalletAddrSetKey(wallet, &genAfter, &cntAfter);
    // The premise of the whole test: neither field moves, so a key without the network
    // cannot possibly notice. If this ever fails the test has stopped proving anything.
    check(genAfter == genBefore && cntAfter == cntBefore,
          "test7: the network switch leaves addrGen AND addrCount unchanged");

    BRWalletFilterElements *feTest = _BRPeerManagerFilterElementsLocked(manager);
    size_t testCount = (feTest ? feTest->count : 0);
    check(testCount != mainCount,
          "test7: the element set was rebuilt for the new network");

    BRSetNetwork(0);   // restore, so nothing after this observes testnet encoding
    BRWalletFilterElements *feBack = _BRPeerManagerFilterElementsLocked(manager);
    check(feBack != NULL && feBack->count == mainCount,
          "test7: switching back restores the mainnet element set");

    // --- Test 8: the cfilter drive must survive a NULL peer.
    //
    // The KeepAlive backstop calls _BRPeerManagerDriveCFiltersLocked(manager, NULL) — the
    // whole point of that path is to restart the pipeline when no peer has spoken recently,
    // so it has no peer to name. peer_log dereferences its argument (BRPeerHost(peer) and
    // (peer)->port), so any log on that path with a NULL peer is a segfault.
    //
    // This is not hypothetical: it crashed on device in Java_..._keepAlivePeers, SIGSEGV
    // fault addr 0x34, within seconds of the first tick that issued a request. Cheap to
    // assert, and it fixes the whole class rather than the one line that happened to fire.
    //
    // The drive is a no-op with no armed auto-fetch and no filter chain, so this exercises
    // the guard rather than the request path; the arm below makes it take the real path.
    // Production always runs COMPACT_FILTERS_ONLY; the manager defaults to BLOOM_ONLY, in
    // which the CF drive correctly does nothing at all. Without this the test would return
    // at the very first guard and prove nothing.
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    _BRPeerManagerDriveCFiltersLocked(manager, NULL);
    check(1, "test8: drive with a NULL peer (unarmed) did not crash");

    BRPeerManagerEnableAutoCompactFilterFetch(manager, block1->height - 10);
    _BRPeerManagerDriveCFiltersLocked(manager, NULL);
    check(1, "test8: drive with a NULL peer (armed) did not crash");

    // Force the RETIRE branch, which logs unconditionally. Without this the test is
    // VACUOUS for the defect: with no connected peers no send succeeds, so the drive never
    // reaches any log line and a missing NULL guard would sail through. Verified by
    // reverting the guard — this is the assertion that turns red.
    manager->cfFiltersInFlight = 1;
    manager->cfFiltersWindowStart = time(NULL) - (CF_FILTERS_WINDOW_TIMEOUT_SECS + 5);
    _BRPeerManagerDriveCFiltersLocked(manager, NULL);
    check(manager->cfFiltersInFlight == 0,
          "test8: a timed-out in-flight window retires, and logging it with no peer is safe");

    // And the public entry point, which also passes NULL down to the request path.
    BRPeerManagerRequestCompactFilters(manager, block1->height - 10, block1->height);
    check(1, "test8: public RequestCompactFilters with no peer did not crash");

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
