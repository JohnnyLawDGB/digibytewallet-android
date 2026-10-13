// Host KAT: a wallet transaction is confirmed only into a block whose header the wallet judged. A header above a
// checkpoint that carries no difficulty context cannot be judged for its first blocks (MultiShield V4 has no history
// there), and every header after it is judged only against those; such a block is vouched for once the chain reaches
// the next checkpoint, whose hash pins everything below it. Until then a receive in it is not confirmed, and the
// header run is not persisted.
//
// WHAT IT PROVES.
//   RED (fail in the -DJUDGED_CONFIRMATION_UNFIXED build, pass in the shipped build):
//     unjudged_receive_not_confirmed     Real mainnet parameters. The wallet starts at the 9,100,000 checkpoint (no
//                                        difficulty context). Five headers at the network's easiest target extend it
//                                        (each meets its own target by proof of work, none can be judged); the third
//                                        commits to a transaction paying the wallet. That block is solicited and
//                                        delivered with the list its header commits to. The transaction stays
//                                        unconfirmed; the scan height is still completed (the scan does not stall).
//     held_receive_confirmed_at_checkpoint
//                                        Mainnet parameters whose table also holds a checkpoint ten blocks above
//                                        9,100,000 (the tenth header of the same run). The receive is held while the
//                                        run is unjudged, and confirmed at its block's height once the chain reaches
//                                        that checkpoint.
//     unjudged_delivery_not_trusted      Real mainnet parameters, the same five headers. The solicited block in the
//                                        unjudged run does not count as a trusted delivery
//                                        (_BRPeerManagerDeliveryIsTrustedLocked): its transactions take the checked
//                                        registration path.
//     unjudged_run_not_persisted         Same table. The chain reaching the estimated height inside the unjudged run
//                                        does not persist the run; reaching it at the checkpoint does.
//   GUARD (pass in both builds):
//     judged_receive_confirmed           testnet26 headers resident (as after a restart), a network that declares
//                                        checkpoint contexts. A header on the tip carrying exactly the target the
//                                        wallet computes, committing to a receive: judged, so the receive is
//                                        confirmed at once.
//     network_without_contexts_unchanged Mainnet parameters that declare no checkpoint context at all (the shape of
//                                        testnet26): the unjudged run confirms as before. Recorded as the scope of the
//                                        rule, not as an endorsement.
//
// ENTRY LAYER. Headers go through the real BRPeer.c dispatch (_BRPeerAcceptMessage, "headers") into the manager's
// own _peerRelayedBlock. The full block goes through _peerRelayedBlockTxns after this wallet's own solicitation is
// recorded (_BRPeerManagerRecordSolicitedBlockLocked) and its matched height recorded as requested in the scan
// ledger, as the compact-filter driver does; its transaction list hashes to the root its header commits to.
//
// FIXTURES. ../header_diff_v4_kat/header_diff_v4_vectors.inc (real headers: mainnet_algo_swap row 180 is the
// 9,100,000 checkpoint; testnet_clamps). judged_confirmation_vectors.inc holds the synthetic headers, written by
// this program's `gen` mode (run.sh gen); each meets its own target by proof of work, and the KAT checks every row
// it delivers.
//
// One case per process: `judged_confirmation_kat <case>` prints "RESULT <case> pass|fail".
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#include "header_diff_v4_vectors.inc"
#ifndef JUDGED_CONFIRMATION_GEN
#include "judged_confirmation_vectors.inc"
#endif

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};

#define CP_HEIGHT 9100000u
#define CP_ROW 180            // mainnet_algo_swap row of the 9,100,000 checkpoint
#define RUN_LEN 10            // the run that reaches the extra checkpoint
#define BRANCH_LEN 5          // the part of the run delivered first
#define RECEIVE_INDEX 2       // the header (0-based) that commits to the receive

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

static UInt256 hashOf(const uint8_t hdr[80])
{
    UInt256 h;
    BRSHA256_2(&h, hdr, 80);
    return h;
}

static const DiffRange *range(const char *name)
{
    for (size_t i = 0; i < sizeof(kDiffRanges)/sizeof(*kDiffRanges); i++)
        if (strcmp(kDiffRanges[i].name, name) == 0) return &kDiffRanges[i];
    return NULL;
}

static void rangeRow(const DiffRange *r, size_t i, uint8_t out[80]) { hexBytes(out, r->hex[i], 80); }

// the receive transaction `seed` pays the wallet's first receive address (deterministic: fixed mnemonic, fixed input)
static BRTransaction *receiveTx(BRWallet *w, uint8_t seed)
{
    BRAddress addr = BRWalletReceiveAddress(w, 1);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr.s);
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;

    memset(prev.u8, seed, sizeof(prev.u8));
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, sizeof(kPlaceholder), kPlaceholder,
                          sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, 100000000000000ULL, spk, spkLen);

    uint8_t buf[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, buf, sizeof(buf));
    BRTransaction *t = BRTransactionParse(buf, len);
    tx->txHash = t->txHash;
    tx->wtxHash = t->wtxHash;
    BRTransactionFree(t);
    return tx;
}

static BRWallet *newWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    assert(w != NULL);
    return w;
}

// ---- fixture -------------------------------------------------------------------------------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeerCallbackInfo *info;
    BRPeer *peer;
} Fx;

static int g_saves = 0;
static uint32_t g_savedTop = 0;
static void saveBlocksCb(void *info, int replace, const uint8_t *bytes, size_t len, uint64_t *check)
{
    (void)info; (void)replace; (void)check;
    g_saves++;
    if (len >= 12) g_savedTop = UInt32GetLE(&bytes[8]);   // [count][serLen][height]... the first is the top
}

static void fxInit(Fx *f, const BRChainParams *params, int testnet, uint32_t earliestKeyTime,
                   BRMerkleBlock *blocks[], size_t blocksCount)
{
    memset(f, 0, sizeof(*f));
    BRSetNetwork(testnet);
    f->w = newWallet();
    f->m = BRPeerManagerNew(params, f->w, earliestKeyTime, blocks, blocksCount, NULL, 0);
    assert(f->m != NULL);
    BRPeerManagerSetCallbacks(f->m, NULL, NULL, NULL, NULL, saveBlocksCb, NULL, NULL, NULL);
    f->m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
    g_saves = 0;
    g_savedTop = 0;

    f->info = calloc(1, sizeof(*f->info));
    f->info->manager = f->m;
    f->info->peer = f->peer = BRPeerNew(f->m->params->magicNumber);
    f->peer->address = (UInt128) { .u8 = { 0,0,0,0,0,0,0,0,0,0,0xff,0xff, 198,51,100,9 } };
    f->peer->port = f->m->params->standardPort;
    BRPeerSetCallbacks(f->peer, f->info, _peerConnected, _peerDisconnected, _peerRelayedPeers,
                       _peerRelayedTx, _peerHasTx, _peerRejectedTx, _peerRelayedBlock, _peerRelayedBlockTxns,
                       _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                       _peerNetworkIsReachable, _peerThreadCleanup);
    BRPeerSetCompactFiltersOnly(f->peer, 1);
    MGR_LOCK(f->m);
    array_add(f->m->peers, *f->peer);
    array_add(f->m->connectedPeers, f->peer);
    f->m->downloadPeer = f->peer;
    MGR_UNLOCK(f->m);
}

static void fxFree(Fx *f)
{
    MGR_LOCK(f->m);
    for (size_t i = array_count(f->m->connectedPeers); i > 0; i--)
        if (f->m->connectedPeers[i - 1] == f->peer) array_rm(f->m->connectedPeers, i - 1);
    f->m->downloadPeer = NULL;
    MGR_UNLOCK(f->m);
    BRPeerFree(f->peer);
    free(f->info);
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
}

// one "headers" message carrying rows[from..to) through the real dispatch; returns the peer layer's verdict
static int deliver(Fx *f, uint8_t (*rows)[80], size_t from, size_t to)
{
    size_t n = to - from, off = 0;
    uint8_t vi[9];
    size_t viLen = BRVarIntSet(vi, sizeof(vi), n);
    uint8_t *msg = malloc(viLen + 81*n);

    memcpy(msg, vi, viLen); off = viLen;
    for (size_t i = from; i < to; i++) { memcpy(&msg[off], rows[i], 80); msg[off + 80] = 0; off += 81; }
    int r = _BRPeerAcceptMessage(f->peer, msg, off, MSG_HEADERS);
    free(msg);
    return r;
}

static uint32_t tipHeight(Fx *f)
{
    MGR_LOCK(f->m);
    uint32_t h = f->m->lastBlock->height;
    MGR_UNLOCK(f->m);
    return h;
}

static int misbehavin(Fx *f)
{
    MGR_LOCK(f->m);
    int n = f->m->misbehavinCount;
    MGR_UNLOCK(f->m);
    return n;
}

static uint32_t txHeight(Fx *f, UInt256 txHash)
{
    BRTransaction *tx = BRWalletTransactionForHash(f->w, txHash);
    return tx ? tx->blockHeight : 0;
}

// the compact-filter driver's side of a match at `height`: the height is requested in the scan ledger and the
// block solicited from the peer; then the full block arrives with the transaction list its header commits to
static void deliverBlock(Fx *f, const uint8_t hdr[80], uint32_t height, BRTransaction *tx)
{
    UInt256 blockHash = hashOf(hdr), root = UInt256Get(&hdr[36]), txHashes[1] = { tx->txHash };

    MGR_LOCK(f->m);
    BRCFScanLedgerInit(&f->m->cfLedger, height);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, height, height, f->peer->address, f->peer->port, 1);
    _BRPeerManagerRecordSolicitedBlockLocked(f->m, blockHash, height);
    MGR_UNLOCK(f->m);
    BRWalletRegisterTransaction(f->w, tx);   // relayedTx hands each transaction of the block to the wallet first
    _peerRelayedBlockTxns(f->info, blockHash, root, txHashes, 1);
}

static uint32_t scannedThrough(Fx *f)
{
    MGR_LOCK(f->m);
    uint32_t h = BRCFScanLedgerScannedThrough(&f->m->cfLedger);
    MGR_UNLOCK(f->m);
    return h;
}

static int meetsOwnTarget(const uint8_t hdr[80])
{
    BRMerkleBlock *b = BRMerkleBlockParse(hdr, 80);
    uint32_t before = BRMerkleBlockPoWMismatchCount();
    int ok = BRMerkleBlockIsValid(b, (uint32_t)time(NULL)) && BRMerkleBlockPoWMismatchCount() == before;
    BRMerkleBlockFree(b);
    return ok;
}

// ---- parameters ----------------------------------------------------------------------------------------------------
static const BRCheckPoint *cpAt(uint32_t height)
{
    for (size_t i = 0; i < BRMainNetParams.checkpointsCount; i++)
        if (BRMainNetParams.checkpoints[i].height == height) return &BRMainNetParams.checkpoints[i];
    return NULL;
}

// a wallet whose keys are 7 days and a minute younger than the 9,100,000 checkpoint starts there
static uint32_t startAtCp(void) { return cpAt(CP_HEIGHT)->timestamp + 7*24*60*60 + 60; }

#ifndef JUDGED_CONFIRMATION_GEN
// mainnet's table through 9,100,000, then a checkpoint at the tenth header of the run (kRun[RUN_LEN - 1])
static const BRChainParams *paramsWithRunCheckpoint(void)
{
    static BRChainParams params;
    static BRCheckPoint table[1024];
    size_t n = 0;
    uint8_t top[80];

    params = BRMainNetParams;
    while (n < params.checkpointsCount && params.checkpoints[n].height <= CP_HEIGHT) { table[n] = params.checkpoints[n]; n++; }
    hexBytes(top, kRun[RUN_LEN - 1], 80);
    table[n].height = CP_HEIGHT + RUN_LEN;
    table[n].hash = UInt256Reverse(hashOf(top));   // the table holds display byte order
    table[n].timestamp = UInt32GetLE(&top[68]);
    table[n].target = UInt32GetLE(&top[72]);
    params.checkpoints = table;
    params.checkpointsCount = n + 1;
    return &params;
}

// mainnet parameters that declare no checkpoint context
static const BRChainParams *paramsWithoutContexts(void)
{
    static BRChainParams params;

    params = BRMainNetParams;
    params.checkpointContexts = NULL;
    params.checkpointContextsCount = 0;
    return &params;
}

// testnet26 parameters that declare a checkpoint context (one that does not verify, so none is used)
static const BRChainParams *testnetDeclaringContexts(void)
{
    static BRChainParams params;
    static const BRCheckPointContext none[1] = { { 80000, 0, NULL } };

    params = BRTestNetParams;
    params.checkpointContexts = none;
    params.checkpointContextsCount = 1;
    return &params;
}

static void loadRun(uint8_t (*rows)[80], const char *const *hex, size_t n)
{
    for (size_t i = 0; i < n; i++) hexBytes(rows[i], hex[i], 80);
}

static int setupRun(uint8_t (*rows)[80], const char *const *hex, size_t n, UInt256 anchorHash)
{
    int ok = 1;
    loadRun(rows, hex, n);
    for (size_t i = 0; i < n; i++) {
        ok &= meetsOwnTarget(rows[i]);
        ok &= UInt256Eq(UInt256Get(&rows[i][4]), i ? hashOf(rows[i - 1]) : anchorHash);
    }
    return ok;
}

// =============================================================================
// Cases
// =============================================================================

static UInt256 cpHash(void)
{
    uint8_t row[80];
    rangeRow(range("mainnet_algo_swap"), CP_ROW, row);
    return hashOf(row);
}

// RED. Five unjudged headers on a checkpoint without a context; a receive in the third is not confirmed.
static const char *case_unjudged_receive_not_confirmed(void)
{
    Fx f;
    uint8_t run[RUN_LEN][80];

    fxInit(&f, &BRMainNetParams, 0, startAtCp(), NULL, 0);
    check(f.m->lastBlock->height == CP_HEIGHT && UInt256Eq(f.m->lastBlock->blockHash, cpHash()),
          "setup: the wallet starts at the 9,100,000 checkpoint (real mainnet parameters)");
    check(setupRun(run, kRun, RUN_LEN, cpHash()), "setup: the run chains on the checkpoint, each header meets its own target");
    BRTransaction *tx = receiveTx(f.w, 0x31);
    check(UInt256Eq(UInt256Get(&run[RECEIVE_INDEX][36]), tx->txHash), "setup: the third header commits to the receive");

    uint32_t skip0 = BRMerkleBlockDiffCount(BR_DIFF_SKIP), match0 = BRMerkleBlockDiffCount(BR_DIFF_MATCH);
    int v = deliver(&f, run, 0, BRANCH_LEN);
    uint32_t skipped = BRMerkleBlockDiffCount(BR_DIFF_SKIP) - skip0, judged = BRMerkleBlockDiffCount(BR_DIFF_MATCH) - match0;
    check(v == 1 && tipHeight(&f) == CP_HEIGHT + BRANCH_LEN, "setup: the five headers are the tip (they extend it)");
    check(skipped == BRANCH_LEN && judged == 0, "setup: none of them can be judged (no history above the checkpoint)");

    uint32_t h = CP_HEIGHT + RECEIVE_INDEX + 1;
    deliverBlock(&f, run[RECEIVE_INDEX], h, tx);
    uint32_t got = txHeight(&f, tx->txHash);

    printf("NOTE: receive in unjudged block %" PRIu32 " (tip %" PRIu32 "): tx height %s%" PRIu32 ", scanned through %"
           PRIu32 ", misbehavin %d (DGB_HEADER_DIFF_CHECK=%d DGB_HEADER_POW_CHECK=%d)\n", h, tipHeight(&f),
           got == TX_UNCONFIRMED ? "unconfirmed/" : "", got, scannedThrough(&f), misbehavin(&f),
           DGB_HEADER_DIFF_CHECK, DGB_HEADER_POW_CHECK);
    check(got == TX_UNCONFIRMED, "the receive is not confirmed");
    check(scannedThrough(&f) == h, "the scan height is completed (the scan does not stall on it)");
    check(misbehavin(&f) == 0, "the peer is not penalised");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// RED. The held receive is confirmed once the chain reaches the next checkpoint.
static const char *case_held_receive_confirmed_at_checkpoint(void)
{
    const BRChainParams *params = paramsWithRunCheckpoint();
    Fx f;
    uint8_t run[RUN_LEN][80];

    fxInit(&f, params, 0, startAtCp(), NULL, 0);
    check(f.m->lastBlock->height == CP_HEIGHT, "setup: the wallet starts at the 9,100,000 checkpoint");
    check(setupRun(run, kRun, RUN_LEN, cpHash()), "setup: the run chains on the checkpoint, each header meets its own target");
    BRTransaction *tx = receiveTx(f.w, 0x31);
    uint32_t h = CP_HEIGHT + RECEIVE_INDEX + 1;

    check(deliver(&f, run, 0, BRANCH_LEN) == 1, "setup: the first five headers are accepted");
    deliverBlock(&f, run[RECEIVE_INDEX], h, tx);
    uint32_t held = txHeight(&f, tx->txHash);
    check(deliver(&f, run, BRANCH_LEN, RUN_LEN) == 1 && tipHeight(&f) == CP_HEIGHT + RUN_LEN,
          "setup: the rest of the run reaches the checkpoint at its tenth header");
    uint32_t after = txHeight(&f, tx->txHash);

    printf("NOTE: receive at %" PRIu32 ": %s before the checkpoint, height %" PRIu32 " after it (tip %" PRIu32 ")\n", h,
           held == TX_UNCONFIRMED ? "unconfirmed" : "confirmed", after, tipHeight(&f));
    check(held == TX_UNCONFIRMED, "the receive is held while its block is unjudged");
    check(after == h, "it is confirmed at its block's height once the checkpoint pins the run");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// RED. A solicited main-chain block in the unjudged run is not a trusted delivery.
static const char *case_unjudged_delivery_not_trusted(void)
{
    Fx f;
    uint8_t run[RUN_LEN][80];

    fxInit(&f, &BRMainNetParams, 0, startAtCp(), NULL, 0);
    check(setupRun(run, kRun, RUN_LEN, cpHash()), "setup: the run chains on the checkpoint");
    check(deliver(&f, run, 0, BRANCH_LEN) == 1 && tipHeight(&f) == CP_HEIGHT + BRANCH_LEN, "setup: five headers are the tip");

    uint32_t h = CP_HEIGHT + RECEIVE_INDEX + 1;
    UInt256 blockHash = hashOf(run[RECEIVE_INDEX]);
    MGR_LOCK(f.m);
    _BRPeerManagerRecordSolicitedBlockLocked(f.m, blockHash, h);
    int onMain = UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(f.m, h), blockHash);
    int trusted = _BRPeerManagerDeliveryIsTrustedLocked(f.m, blockHash);
    MGR_UNLOCK(f.m);

    printf("NOTE: solicited block %" PRIu32 " on the main chain=%d: trusted delivery=%d\n", h, onMain, trusted);
    check(onMain, "setup: the block is solicited and on the main chain");
    check(! trusted, "it is not a trusted delivery");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// RED. An unjudged run is not persisted; the run is once the checkpoint pins it.
static const char *case_unjudged_run_not_persisted(void)
{
    const BRChainParams *params = paramsWithRunCheckpoint();
    Fx f;
    uint8_t run[RUN_LEN][80];

    fxInit(&f, params, 0, startAtCp(), NULL, 0);
    check(setupRun(run, kRun, RUN_LEN, cpHash()), "setup: the run chains on the checkpoint");
    f.m->estimatedHeight = CP_HEIGHT + BRANCH_LEN;   // chain download "complete" inside the unjudged run
    check(deliver(&f, run, 0, BRANCH_LEN) == 1 && tipHeight(&f) == CP_HEIGHT + BRANCH_LEN, "setup: five headers, the estimated height");
    int savesUnjudged = g_saves;
    f.m->estimatedHeight = CP_HEIGHT + RUN_LEN;
    check(deliver(&f, run, BRANCH_LEN, RUN_LEN) == 1 && tipHeight(&f) == CP_HEIGHT + RUN_LEN, "setup: the run reaches the checkpoint");
    int savesPinned = g_saves - savesUnjudged;

    printf("NOTE: saves at the estimated height inside the unjudged run: %d; at the checkpoint: %d (top %" PRIu32 ")\n",
           savesUnjudged, savesPinned, g_savedTop);
    check(savesUnjudged == 0, "the unjudged run is not persisted");
    check(savesPinned == 1 && g_savedTop == CP_HEIGHT + RUN_LEN, "the run is persisted once the checkpoint pins it");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// GUARD. A judged header on a resident testnet26 history: its receive is confirmed at once.
static const char *case_judged_receive_confirmed(void)
{
    const DiffRange *r = range("testnet_clamps");
    BRMerkleBlock **blocks = calloc(r->count, sizeof(*blocks));
    Fx f;
    uint8_t hdr[1][80], row[80];

    BRSetNetwork(1);
    for (size_t i = 0; i < r->count; i++) {
        rangeRow(r, i, row);
        blocks[i] = BRMerkleBlockParse(row, 80);
        blocks[i]->height = r->firstHeight + (uint32_t)i;
    }
    fxInit(&f, testnetDeclaringContexts(), 1, 0, blocks, r->count);
    free(blocks);
    hexBytes(hdr[0], kJudgedHeader, 80);
    rangeRow(r, r->count - 1, row);
    BRTransaction *tx = receiveTx(f.w, 0x33);
    uint32_t h = r->firstHeight + (uint32_t)r->count;

    check(tipHeight(&f) == h - 1, "setup: the resident testnet26 headers end at the range's tip");
    check(UInt256Eq(UInt256Get(&hdr[0][4]), hashOf(row)) && meetsOwnTarget(hdr[0]) &&
          UInt256Eq(UInt256Get(&hdr[0][36]), tx->txHash),
          "setup: the header extends the tip, meets its own target and commits to the receive");

    uint32_t match0 = BRMerkleBlockDiffCount(BR_DIFF_MATCH);
    check(deliver(&f, hdr, 0, 1) == 1 && tipHeight(&f) == h, "the header is accepted as the tip");
    check(BRMerkleBlockDiffCount(BR_DIFF_MATCH) - match0 == 1, "it is judged and its target matches");
    deliverBlock(&f, hdr[0], h, tx);
    uint32_t got = txHeight(&f, tx->txHash);

    printf("NOTE: receive in judged block %" PRIu32 ": tx height %" PRIu32 "\n", h, got);
    check(got == h, "the receive is confirmed at once");
    fxFree(&f);
    BRSetNetwork(0);
    return g_fail ? "fail" : "pass";
}

// GUARD. A network that declares no checkpoint context: an unjudged run confirms as before.
static const char *case_network_without_contexts_unchanged(void)
{
    Fx f;
    uint8_t run[RUN_LEN][80];

    fxInit(&f, paramsWithoutContexts(), 0, startAtCp(), NULL, 0);
    check(setupRun(run, kRun, RUN_LEN, cpHash()), "setup: the run chains on the checkpoint");
    BRTransaction *tx = receiveTx(f.w, 0x31);
    uint32_t h = CP_HEIGHT + RECEIVE_INDEX + 1;

    check(deliver(&f, run, 0, BRANCH_LEN) == 1, "setup: the first five headers are accepted");
    deliverBlock(&f, run[RECEIVE_INDEX], h, tx);
    printf("NOTE: no declared context: receive at %" PRIu32 " -> tx height %" PRIu32 "\n", h, txHeight(&f, tx->txHash));
    check(txHeight(&f, tx->txHash) == h, "the receive is confirmed (the rule applies to networks that ship contexts)");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}
#endif // JUDGED_CONFIRMATION_GEN

#ifdef JUDGED_CONFIRMATION_GEN
// ---- vector writer (gen mode) --------------------------------------------------------------------------------------
static void findNonce(uint8_t hdr[80])
{
    BRMerkleBlock *b = BRMerkleBlockParse(hdr, 80);

    for (uint32_t n = 0;; n++) {
        UInt32SetLE(&hdr[76], n);
        b->nonce = n;
        UInt256 pow, t = UINT256_ZERO;
        BRMerkleBlockPoWHash(b, &pow);
        uint32_t size = b->target >> 24, tv = b->target & 0x00ffffff;
        if (size > 3) UInt32SetLE(&t.u8[size - 3], tv); else UInt32SetLE(t.u8, tv >> (3 - size)*8);
        int meets = 1;
        for (int i = 31; i >= 0; i--) { if (pow.u8[i] < t.u8[i]) break; if (pow.u8[i] > t.u8[i]) { meets = 0; break; } }
        if (meets) break;
    }
    BRMerkleBlockFree(b);
}

static void printHex(FILE *o, const char *pre, const uint8_t hdr[80], const char *post)
{
    fprintf(o, "%s\"", pre);
    for (int i = 0; i < 80; i++) fprintf(o, "%02x", hdr[i]);
    fprintf(o, "\"%s\n", post);
}

// a run of sha256d headers at the easiest target on `parent`, the RECEIVE_INDEX one committing to `receive`
static void genRun(uint8_t (*rows)[80], size_t n, const uint8_t parent[80], UInt256 receive, uint8_t fill)
{
    for (size_t i = 0; i < n; i++) {
        const uint8_t *p = i ? rows[i - 1] : parent;
        memset(rows[i], 0, 80);
        UInt32SetLE(&rows[i][0], 0x20000202);
        UInt256Set(&rows[i][4], hashOf(p));
        if (i == RECEIVE_INDEX) UInt256Set(&rows[i][36], receive);
        else memset(&rows[i][36], fill + (int)i, 32);
        UInt32SetLE(&rows[i][68], UInt32GetLE(&p[68]) + 15);
        UInt32SetLE(&rows[i][72], 0x1e0fffff);
        findNonce(rows[i]);
    }
}

static int gen(const char *path)
{
    uint8_t cp[80], run[RUN_LEN][80], judged[80], row[80];

    BRSetNetwork(0);
    BRWallet *w = newWallet();
    BRTransaction *t1 = receiveTx(w, 0x31);
    rangeRow(range("mainnet_algo_swap"), CP_ROW, cp);
    genRun(run, RUN_LEN, cp, t1->txHash, 0x40);
    BRTransactionFree(t1);
    BRWalletFree(w);

    // testnet26: one odo header on testnet_clamps' tip at the target the wallet computes, committing to a receive
    const DiffRange *r = range("testnet_clamps");
    BRSetNetwork(1);
    w = newWallet();
    BRTransaction *t3 = receiveTx(w, 0x33);
    BRMerkleBlock **blocks = calloc(r->count, sizeof(*blocks));
    for (size_t i = 0; i < r->count; i++) {
        rangeRow(r, i, row);
        blocks[i] = BRMerkleBlockParse(row, 80);
        blocks[i]->height = r->firstHeight + (uint32_t)i;
    }
    BRPeerManager *m = BRPeerManagerNew(&BRTestNetParams, w, 0, blocks, r->count, NULL, 0);
    free(blocks);
    rangeRow(r, r->count - 1, row);
    memcpy(judged, row, 80);
    UInt32SetLE(&judged[0], 0x20000e02);
    UInt256Set(&judged[4], hashOf(row));
    UInt256Set(&judged[36], t3->txHash);
    UInt32SetLE(&judged[68], UInt32GetLE(&row[68]) + 15);
    MGR_LOCK(m);
    BRMerkleBlock *prev = m->lastBlock, *b = BRMerkleBlockParse(judged, 80);
    b->height = prev->height + 1;
    uint32_t expected = 0;
    int ok = _BRPeerManagerDiffV4ExpectedLocked(m, b, prev, &expected);
    BRMerkleBlockFree(b);
    MGR_UNLOCK(m);
    assert(ok);
    UInt32SetLE(&judged[72], expected);
    findNonce(judged);
    BRPeerManagerFree(m);
    BRTransactionFree(t3);
    BRWalletFree(w);

    FILE *o = fopen(path, "w");
    if (! o) return 1;
    fprintf(o, "// AUTO-GENERATED by `run.sh gen` (this program built with -DJUDGED_CONFIRMATION_GEN). DO NOT HAND-EDIT.\n");
    fprintf(o, "// Each header meets its own target by proof of work.\n\n");
    fprintf(o, "// on the 9,100,000 checkpoint: sha256d at 0x1e0fffff; row %d commits to receive 0x31\n", RECEIVE_INDEX);
    fprintf(o, "static const char *const kRun[%d] = {\n", RUN_LEN);
    for (size_t i = 0; i < RUN_LEN; i++) printHex(o, "    ", run[i], ",");
    fprintf(o, "};\n\n// testnet_clamps' tip + 1: odo at the target the wallet computes; commits to receive 0x33\n");
    printHex(o, "static const char *kJudgedHeader = ", judged, ";");
    fclose(o);
    return 0;
}
#endif

typedef struct { const char *name; const char *(*fn)(void); } Case;

static const Case kCases[] = {
#ifndef JUDGED_CONFIRMATION_GEN
    { "unjudged_receive_not_confirmed", case_unjudged_receive_not_confirmed },
    { "held_receive_confirmed_at_checkpoint", case_held_receive_confirmed_at_checkpoint },
    { "unjudged_delivery_not_trusted", case_unjudged_delivery_not_trusted },
    { "unjudged_run_not_persisted", case_unjudged_run_not_persisted },
    { "judged_receive_confirmed", case_judged_receive_confirmed },
    { "network_without_contexts_unchanged", case_network_without_contexts_unchanged },
#endif
    { NULL, NULL },
};

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc != 2) {
        fprintf(stderr, "usage: %s <case>|list\n", argv[0]);
        return 2;
    }
#ifdef JUDGED_CONFIRMATION_GEN
    if (strcmp(argv[1], "gen") == 0) return gen(getenv("JC_KAT_VECTORS_OUT") ? getenv("JC_KAT_VECTORS_OUT") : "vectors.inc");
#endif
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; kCases[i].name; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; kCases[i].name; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("---- %s ----\n", kCases[i].name);
        const char *v = kCases[i].fn();
        printf("RESULT %s %s\n", kCases[i].name, v);
        return strcmp(v, "pass") == 0 ? 0 : 1;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
