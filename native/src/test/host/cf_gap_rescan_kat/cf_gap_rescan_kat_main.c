// Host KAT: a compact-filter scan re-checks the heights it already judged when the wallet's watched address set
// grows (owner checklist Q18).
//
// BEHAVIOUR
// ---------
// The BIP158 scan matches each filter against the wallet's address set as it is when the filter arrives. That set
// is the next gap + 100 addresses past the last one used, and it grows only when a transaction is registered:
// BRWalletRegisterTransaction extends a chain whose used address is within the gap limit of its end. Filters run
// ahead of the full blocks they ask for: the filter for block A matches and asks for A, and while A is in flight the
// filter for a later block B is matched against the earlier set. When A's transactions extend the set, every height
// from the scan frontier (scannedThrough + 1), or from A's own height if that is lower, up to the top requested
// height is fetched and matched again. Full blocks are not delivered in height order, so the start is the frontier,
// not the delivering block.
//
// CASES
// -----
// A fresh wallet's receive window is indices 0..109; paying index 104 or 105 extends it past 112.
//   beyond_window_found       (RED)  B (HB) pays index 112, A (HA < HB) pays 105. Filters for A and B are both
//                             judged before A's block arrives. After A's block HB is not counted as scanned, the
//                             cursor is back below it, and the re-fetch finds B's payment.
//   out_of_order_found        (RED, RED2)  X (H1) pays 104, B (H2) pays 112, A (H3) pays 105. All three filters are
//                             judged first; A's block arrives BEFORE X's and grows the set; X's then grows nothing.
//                             The scan goes back below H2 and finds B.
//   delivering_block_rescanned (RED)  one block at H1 holds two transactions: the first pays 112, the second pays 105.
//                             H1 itself is scanned again: the forward drive resumes AT H1 and H1's block is requested
//                             a second time. Whether the re-delivered 112 tx is credited is not asserted here.
//   abandoned_floor_respected (RED)  A (H1) pays 105, B (H3) pays 112. A's block is in flight when H1 is abandoned
//                             (the floor moves to H2), and only then arrives and grows the set. The rescan starts AT
//                             the floor and B is found.
//   frontier_start            (RED)  H1 pays a foreign script (judged, scanned), A (H2) pays 105, B (H3) pays 112.
//                             After A's block the scan resumes at H2, the frontier: H1 stays scanned and is not
//                             requested again, and the ledger saved during the hand-over already holds the new range.
//   completed_height_rescanned (RED)  A (H1) pays 105, B (H2) pays 112. H1 is counted as scanned before A's
//                             transactions are handed over (the block arrives after its height was completed). The
//                             scan resumes at H1 itself, not above it, and B is found.
//   top_height_rescanned      (RED)  the only requested block, at the top height, holds two transactions (112, then
//                             105). The rescan of that single height runs: the ledger and the forward-fetch cursor
//                             both go back below it, and its block is requested a second time.
//   below_start_clamped       (RED)  the scan starts at H2; B (H2) pays 112 and is judged first. A block below the
//                             start (H1, paying 105) is then handed over and grows the set. The rescan starts at the
//                             scan's start, which does not move, and B is found.
//   in_window_no_rescan       (GUARD, every build)  the same flow paying indices 5 and 8: the set does not grow,
//                             nothing is rewound, and both payments are confirmed.
//   unarmed_no_rescan         (GUARD, every build)  the set grows while the filter scan is not armed: the ledger and
//                             the cursor are left as they were.
//
// The "re-fetch" is computed from the manager's own cursor (autoFetchCFiltersThrough + 1, clamped up to
// autoFetchCFiltersStart, .. the top height), the range the forward drive in _peerRelayedCFHeaders /
// BRPeerManagerKeepAlive would request; the KAT then hands in the filters and blocks a peer would answer with. Each
// filter commits to every output script of its block (BIP158 basic filter, outputs only).
//
// RED-BEFORE-GREEN: run.sh builds two comparison arms. -DCF_GAP_RESCAN_UNFIXED (the detection in _peerRelayedTx
// compiled out) must fail every RED case; -DCF_GAP_RESCAN_FROM_HEIGHT_UNFIXED (the rescan starting at the delivering
// block) must fail out_of_order_found. Each arm passes every case it does not govern;
// the shipped build passes all.
//
// Approach: #include BRPeer.c and BRPeerManager.c to reach the file-static handlers and the peer context (the
// delivering-block flag BRPeer.c sets while it hands a verified block's transactions to relayedTx), the same pattern
// int_2026_10_09_d_kat and cf_block_completion_gate_kat use. Requests go through the production sender on a
// socketless peer. One case per process: `cf_gap_rescan_kat <case>` prints "RESULT <case> pass|fail".
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

// ---- BIP158 filter (SipHash and bit writer as in ../cf_block_completion_gate_kat) ---
#define TEST_ROTL64(x, b) (((x) << (b)) | ((x) >> (64 - (b))))
#define TEST_SIP_ROUND(v0, v1, v2, v3) \
    do { v0 += v1; v1 = TEST_ROTL64(v1, 13); v1 ^= v0; v0 = TEST_ROTL64(v0, 32); \
         v2 += v3; v3 = TEST_ROTL64(v3, 16); v3 ^= v2; \
         v0 += v3; v3 = TEST_ROTL64(v3, 21); v3 ^= v0; \
         v2 += v1; v1 = TEST_ROTL64(v1, 17); v1 ^= v2; v2 = TEST_ROTL64(v2, 32); } while (0)

static uint64_t test_siphash24(uint64_t k0, uint64_t k1, const uint8_t *data, size_t len)
{
    uint64_t v0 = 0x736f6d6570736575ULL ^ k0, v1 = 0x646f72616e646f6dULL ^ k1;
    uint64_t v2 = 0x6c7967656e657261ULL ^ k0, v3 = 0x7465646279746573ULL ^ k1;
    uint64_t m = 0;
    size_t i = 0;

    for (; i + 8 <= len; i += 8) {
        m = ((uint64_t)data[i]) | ((uint64_t)data[i+1] << 8) | ((uint64_t)data[i+2] << 16) |
            ((uint64_t)data[i+3] << 24) | ((uint64_t)data[i+4] << 32) | ((uint64_t)data[i+5] << 40) |
            ((uint64_t)data[i+6] << 48) | ((uint64_t)data[i+7] << 56);
        v3 ^= m;
        TEST_SIP_ROUND(v0, v1, v2, v3);
        TEST_SIP_ROUND(v0, v1, v2, v3);
        v0 ^= m;
    }

    m = ((uint64_t)(len & 0xff)) << 56;
    for (size_t j = 0; i + j < len; j++) m |= ((uint64_t)data[i + j]) << (8*j);
    v3 ^= m;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    v0 ^= m;
    v2 ^= 0xff;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    return v0 ^ v1 ^ v2 ^ v3;
}

// high 64 bits of hash*F, without a 128-bit type (the 32-bit build has none)
static uint64_t test_fastrange64(uint64_t hash, uint64_t F)
{
    uint64_t aL = hash & 0xffffffffu, aH = hash >> 32, bL = F & 0xffffffffu, bH = F >> 32;
    uint64_t ll = aL*bL, lh = aL*bH, hl = aH*bL, hh = aH*bH;
    uint64_t mid = (ll >> 32) + (lh & 0xffffffffu) + (hl & 0xffffffffu);

    return hh + (lh >> 32) + (hl >> 32) + (mid >> 32);
}

static void test_gcsWriteBit(uint8_t *out, size_t off, size_t *bitPos, unsigned bit)
{
    out[off + (*bitPos)/8] |= (uint8_t)((bit & 1u) << (7 - (*bitPos % 8)));
    (*bitPos)++;
}

// A BIP158 basic filter over `n` elements: hash each into [0, n*M), sort, Golomb-Rice code the deltas.
static int cmpU64(const void *a, const void *b)
{
    uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
    return (x > y) - (x < y);
}

static size_t buildFilter(UInt256 blockHash, const uint8_t *const *elems, const size_t *elemLens, size_t n,
                          uint8_t *out, size_t outCap)
{
    uint64_t k0 = UInt64GetLE(&blockHash.u8[0]);
    uint64_t k1 = UInt64GetLE(&blockHash.u8[8]);
    uint64_t F  = (uint64_t)n*BR_GCS_BASIC_FILTER_M;
    const uint8_t P = BR_GCS_BASIC_FILTER_P;
    uint64_t vals[4];
    size_t off = 1, bitPos = 0;

    assert(n >= 1 && n <= 4);
    for (size_t i = 0; i < n; i++) vals[i] = test_fastrange64(test_siphash24(k0, k1, elems[i], elemLens[i]), F);
    qsort(vals, n, sizeof(vals[0]), cmpU64);

    memset(out, 0, outCap);
    out[0] = (uint8_t)n; // CompactSize N
    uint64_t last = 0;
    for (size_t k = 0; k < n; k++) {
        uint64_t d = vals[k] - last, q = d >> P, r = d & ((((uint64_t)1) << P) - 1);
        last = vals[k];
        assert(off + (bitPos + q + 1 + P + 7)/8 <= outCap);
        for (uint64_t i = 0; i < q; i++) test_gcsWriteBit(out, off, &bitPos, 1);
        test_gcsWriteBit(out, off, &bitPos, 0);
        for (int i = (int)P - 1; i >= 0; i--) test_gcsWriteBit(out, off, &bitPos, (unsigned)((r >> i) & 1));
    }
    return off + (bitPos + 7)/8;
}


// ---- transactions -----------------------------------------------------------
static const uint8_t kSig[8] = { 0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58 };   // not a signature
static const uint8_t kWit[1] = { 0x00 };

// a transaction paying `amount` to `spk` from a fabricated prevout, parsed back from its own bytes so txHash is
// exactly what the wire gives
static BRTransaction *payTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t seed)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;

    for (size_t i = 0; i < 32; i++) prev.u8[i] = (uint8_t)(seed*29u + i*11u + 1u);
    BRTransactionAddInput(tx, prev, seed, 0, NULL, 0, kSig, sizeof(kSig), kWit, sizeof(kWit), 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(len);
    len = BRTransactionSerialize(tx, d, len);
    BRTransaction *p = BRTransactionParse(d, len);
    free(d);
    BRTransactionFree(tx);
    assert(p != NULL);
    return p;
}

static BRTransaction *copyTx(const BRTransaction *tx)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(len);
    len = BRTransactionSerialize(tx, d, len);
    BRTransaction *p = BRTransactionParse(d, len);
    free(d);
    assert(p != NULL);
    return p;
}

// the receive (external, BIP84 P2WPKH) address at `index` of the wallet's key, derived independently of the wallet
static void receiveAddrAt(BRMasterPubKey mpk, uint32_t index, BRAddress *out, uint8_t *spk, size_t *spkLen)
{
    uint8_t pub[65];
    size_t pubLen = BRBIP32PubKey(pub, sizeof(pub), mpk, SEQUENCE_EXTERNAL_CHAIN, index);
    BRKey key;

    *out = BR_ADDRESS_NONE;
    assert(BRKeySetPubKey(&key, pub, pubLen));
    assert(BRKeySegwitAddress(&key, out->s, sizeof(out->s), OP_0) > 0);
    *spkLen = BRAddressScriptPubKey(spk, 64, out->s);
    assert(*spkLen > 0);
}


// ---- fixture ----------------------------------------------------------------
// Resident headers on one chain: H0 (empty) and blocks at H0+1 .. H0+nblk, each committing to one or two payments; a
// filter-header chain whose cfheaders commit to each block's filter (every output script it pays); and a scan armed
// at H0+1 with [H0+1 .. top] requested, as the forward drive leaves it.
#define H0 5300000u
#define MAX_BLK 3
#define MAX_TX  2
#define FOREIGN 0xFFFFFFFFu   // pays a script that is not the wallet's

typedef struct { size_t ntx; uint32_t idx[MAX_TX]; } BlkSpec;

typedef struct {
    BRMasterPubKey mpk;
    BRWallet *w;
    BRPeerManager *m;
    BRPeer *peer;
    BRPeerCallbackInfo info;
    size_t nblk;
    uint32_t top;
    BRAddress addr[MAX_BLK + 1][MAX_TX];
    BRTransaction *tx[MAX_BLK + 1][MAX_TX];   // fixture copies; the wallet gets parsed copies
    size_t ntx[MAX_BLK + 1];
    BRMerkleBlock *blk[MAX_BLK + 1];          // [0] = H0 (owned by m->blocks)
    uint8_t filter[MAX_BLK + 1][32];
    size_t filterLen[MAX_BLK + 1];
    unsigned delivered[MAX_BLK + 1];          // how many times each block was handed over
    unsigned savesInHandover;                 // saveCFLedger calls while a block's transactions are handed over
    uint32_t savedScanned, savedRequested;    // the last such save's scannedThrough and requestedThrough
} Fx;

static BRMerkleBlock *blockAt(uint32_t height, uint8_t seed, UInt256 prev, BRTransaction *const *txs, size_t ntx)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    UInt256 hashes[MAX_TX];

    memset(b->blockHash.u8, seed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = 1700300000u + (height - H0)*15u;
    b->prevBlock = prev;
    for (size_t i = 0; i < ntx; i++) hashes[i] = txs[i]->txHash;
    if (ntx > 0) assert(BRMerkleRootFromTxHashes(&b->merkleRoot, hashes, ntx));
    return b;
}

// the scan starts at scanStart (H0+1 .. top) with [scanStart .. top] requested
static void fxInitAt(Fx *f, const BlkSpec *spec, size_t nblk, uint32_t scanStart)
{
    memset(f, 0, sizeof(*f));
    assert(nblk >= 1 && nblk <= MAX_BLK);
    f->nblk = nblk;
    f->top = H0 + (uint32_t)nblk;

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    f->mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, f->mpk);   // fresh wallet: receive window is indices 0 .. gap + 100 - 1
    assert(f->w != NULL);

    uint8_t spk[MAX_BLK + 1][MAX_TX][64];
    size_t spkLen[MAX_BLK + 1][MAX_TX];
    for (size_t b = 1; b <= nblk; b++) {
        f->ntx[b] = spec[b - 1].ntx;
        assert(f->ntx[b] >= 1 && f->ntx[b] <= MAX_TX);
        for (size_t t = 0; t < f->ntx[b]; t++) {
            uint32_t idx = spec[b - 1].idx[t];
            if (idx == FOREIGN) {
                spkLen[b][t] = 22;   // P2WPKH to a hash no wallet key has
                spk[b][t][0] = 0x00; spk[b][t][1] = 0x14;
                memset(&spk[b][t][2], (int)(0xA0 + b*2 + t), 20);
                f->addr[b][t] = BR_ADDRESS_NONE;
            }
            else receiveAddrAt(f->mpk, idx, &f->addr[b][t], spk[b][t], &spkLen[b][t]);
            f->tx[b][t] = payTx(spk[b][t], spkLen[b][t], 100000000ULL*(b*MAX_TX + t), (uint8_t)(0x40 + b*MAX_TX + t));
        }
    }

    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    f->blk[0] = blockAt(H0, 0xD0, UINT256_ZERO, NULL, 0);
    for (size_t b = 1; b <= nblk; b++)
        f->blk[b] = blockAt(H0 + (uint32_t)b, (uint8_t)(0xD0 + b), f->blk[b - 1]->blockHash, f->tx[b], f->ntx[b]);
    for (size_t b = 0; b <= nblk; b++) BRSetAdd(f->m->blocks, f->blk[b]);
    f->m->lastBlock = f->blk[nblk];

    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H0, UINT256_ZERO);
    UInt256 fh;
    memset(fh.u8, 0x77, sizeof(fh.u8));   // H0's filter is never fetched; any header will do
    assert(BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain),
                                      &fh, 1) == 1);
    for (size_t b = 1; b <= nblk; b++) {
        const uint8_t *elems[MAX_TX];
        for (size_t t = 0; t < f->ntx[b]; t++) elems[t] = spk[b][t];
        f->filterLen[b] = buildFilter(f->blk[b]->blockHash, elems, spkLen[b], f->ntx[b], f->filter[b],
                                      sizeof(f->filter[b]));
        BRSHA256_2(fh.u8, f->filter[b], f->filterLen[b]);
        assert(BRCompactFilterChainAppend(f->m->compactFilterChain,
                                          BRCompactFilterChainTipHeader(f->m->compactFilterChain), &fh, 1) == 1);
        assert(BRCompactFilterChainVerifyFilter(f->m->compactFilterChain, f->blk[b]->height, f->filter[b],
                                                f->filterLen[b]) == 1);
    }

    // the scan, armed at scanStart and with [scanStart .. top] requested, as the forward drive leaves it
    assert(scanStart > H0 && scanStart <= f->top);
    f->m->autoFetchCFiltersEnabled = 1;
    f->m->autoFetchCFiltersStart = scanStart;
    f->m->autoFetchCFiltersThrough = f->top;
    BRCFScanLedgerInit(&f->m->cfLedger, scanStart);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, scanStart, f->top, UINT128_ZERO, 0, 1);

    f->peer = BRPeerNew(BRMainNetParams.magicNumber);
    f->peer->address.u8[15] = 0x01;
    f->peer->port = 12024;
    f->peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(f->m->connectedPeers, f->peer);
    f->info.peer = f->peer;
    f->info.manager = f->m;
}

static void fxInit(Fx *f, const BlkSpec *spec, size_t nblk) { fxInitAt(f, spec, nblk, H0 + 1); }

// saveCFLedger: records the ledger saved while a block's transactions are handed over (the serialized header holds
// start, scannedThrough and requestedThrough at offsets 8, 12 and 16)
static void onSaveCFLedger(void *info, const uint8_t *bytes, size_t len)
{
    Fx *f = info;
    if (! ((BRPeerContext *)f->peer)->deliveringBlockTxs || len < 20) return;
    f->savesInHandover++;
    f->savedScanned = UInt32GetLE(&bytes[12]);
    f->savedRequested = UInt32GetLE(&bytes[16]);
}

static void fxFree(Fx *f)
{
    MGR_LOCK(f->m);
    array_clear(f->m->connectedPeers);
    MGR_UNLOCK(f->m);
    BRPeerFree(f->peer);
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    for (size_t b = 1; b <= f->nblk; b++)
        for (size_t t = 0; t < f->ntx[b]; t++) BRTransactionFree(f->tx[b][t]);
}

static int solicited(Fx *f, size_t b)
{
    MGR_LOCK(f->m);
    int r = _BRPeerManagerFindSolicitedBlockLocked(f->m, f->blk[b]->blockHash, f->blk[b]->height);
    MGR_UNLOCK(f->m);
    return r >= 0;
}

static void deliverFilter(Fx *f, size_t b)
{
    _peerRelayedCFilter(&f->info, FILTER_TYPE_BASIC, f->blk[b]->blockHash, f->filter[b], f->filterLen[b]);
}

// what BRPeer.c does with a requested, merkle-verified block: hand each tx, in block order, to relayedTx with the
// delivering flag set, then the tx list to relayedBlockTxns
static void deliverBlock(Fx *f, size_t b)
{
    BRPeerContext *ctx = (BRPeerContext *)f->peer;
    UInt256 hashes[MAX_TX];

    f->delivered[b]++;
    ctx->deliveringBlockHash = f->blk[b]->blockHash;
    ctx->deliveringBlockTxs = 1;
    for (size_t t = 0; t < f->ntx[b]; t++) {
        hashes[t] = f->tx[b][t]->txHash;
        _peerRelayedTx(&f->info, copyTx(f->tx[b][t]));   // takes ownership
    }
    ctx->deliveringBlockTxs = 0;
    ctx->deliveringBlockHash = UINT256_ZERO;
    _peerRelayedBlockTxns(&f->info, f->blk[b]->blockHash, f->blk[b]->merkleRoot, hashes, f->ntx[b]);
}

static int confirmedAt(Fx *f, size_t b, size_t t)
{
    BRTransaction *x = BRWalletTransactionForHash(f->w, f->tx[b][t]->txHash);
    return x != NULL && x->blockHeight == f->blk[b]->height;
}

static uint64_t paid(size_t b, size_t t) { return 100000000ULL*(b*MAX_TX + t); }

static void noteState(Fx *f, const char *when)
{
    printf("NOTE: %s: scannedThrough=%u cursor=%u requestedThrough=%u abandonedBelow=%u\n", when,
           BRCFScanLedgerScannedThrough(&f->m->cfLedger), f->m->autoFetchCFiltersThrough,
           f->m->cfLedger.requestedThrough, BRCFScanLedgerAbandonedBelow(&f->m->cfLedger));
}

// The forward drive: request from the cursor (clamped up to the scan's start, as every production request site does)
// up to the top of the filter-header chain, then answer each request the way a peer would (the filter, and the full
// block for any filter that asked for one). Returns the first height requested (top + 1 if nothing).
static uint32_t driveForward(Fx *f)
{
    uint32_t from = f->m->autoFetchCFiltersThrough + 1;
    if (from < f->m->autoFetchCFiltersStart) from = f->m->autoFetchCFiltersStart;
    printf("NOTE: forward drive re-requests [%u..%u]%s\n", from, f->top, from > f->top ? " (nothing)" : "");
    if (from > f->top) return from;

    BRCFScanLedgerRecordRequested(&f->m->cfLedger, from, f->top, f->peer->address, f->peer->port, 2);
    f->m->autoFetchCFiltersThrough = f->top;
    for (uint32_t h = from; h <= f->top; h++) {
        size_t b = (size_t)(h - H0);
        deliverFilter(f, b);
        if (solicited(f, b)) deliverBlock(f, b);
    }
    return from;
}

// ---- cases ------------------------------------------------------------------
static int case_beyond_window_found(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 105 } }, { 1, { 112 } } };   // A at H0+1, B at H0+2
    fxInit(&f, spec, 2);
    const uint32_t HB = H0 + 2;

    check(BRWalletContainsAddress(f.w, f.addr[1][0].s), "setup: index 105 is inside the fresh wallet's window");
    check(! BRWalletContainsAddress(f.w, f.addr[2][0].s), "setup: index 112 is beyond the fresh wallet's window");

    deliverFilter(&f, 1);
    check(solicited(&f, 1), "setup: the filter for HA matched and asked for HA's block");
    deliverFilter(&f, 2);
    check(! solicited(&f, 2), "setup: the filter for HB was judged against the old set and missed");

    deliverBlock(&f, 1);
    check(confirmedAt(&f, 1, 0), "payment A is confirmed at HA");
    check(BRWalletContainsAddress(f.w, f.addr[2][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's block");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < HB,
          "CRUX: HB, judged against the set before it grew, is not counted as scanned");
    check(f.m->autoFetchCFiltersThrough < HB,
          "CRUX: the forward-fetch cursor is back below HB, so its filter is fetched again");

    driveForward(&f);

    check(confirmedAt(&f, 2, 0), "CRUX: the payment beyond the original window is found and confirmed at HB");
    check(BRWalletBalance(f.w) == paid(1, 0) + paid(2, 0), "CRUX: the balance holds both payments");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == HB, "the scan completes through HB");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");
    check(confirmedAt(&f, 1, 0), "payment A is still confirmed at HA after the re-scan");

    fxFree(&f);
    return g_fail == 0;
}

static int case_out_of_order_found(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 104 } }, { 1, { 112 } }, { 1, { 105 } } };   // X at H1, B at H2, A at H3
    fxInit(&f, spec, 3);
    const uint32_t H2 = H0 + 2, H3 = H0 + 3;

    check(! BRWalletContainsAddress(f.w, f.addr[2][0].s), "setup: index 112 is beyond the fresh wallet's window");
    for (size_t b = 1; b <= 3; b++) deliverFilter(&f, b);
    check(solicited(&f, 1) && ! solicited(&f, 2) && solicited(&f, 3),
          "setup: X's and A's filters matched; B's was judged against the old set and missed");

    deliverBlock(&f, 3);   // A, the HIGHER block, arrives first and grows the set
    check(BRWalletContainsAddress(f.w, f.addr[2][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's block (H3)");
    size_t before = 0, after = 0;
    BRWalletAddrSetKey(f.w, NULL, &before);
    deliverBlock(&f, 1);   // X arrives second: its payment is inside the grown set, so it grows nothing
    BRWalletAddrSetKey(f.w, NULL, &after);
    check(after == before, "setup: X's block grows nothing (so X cannot ask for a rescan of its own)");
    noteState(&f, "after X's block (H1)");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < H2,
          "CRUX: H2, judged against the set before A grew it, is not counted as scanned");
    check(f.m->autoFetchCFiltersThrough < H2, "CRUX: the cursor is back below H2, so its filter is fetched again");

    driveForward(&f);

    check(confirmedAt(&f, 2, 0), "CRUX: the payment to index 112 at H2 is found");
    check(confirmedAt(&f, 1, 0) && confirmedAt(&f, 3, 0), "X and A stay confirmed at their heights");
    check(BRWalletBalance(f.w) == paid(1, 0) + paid(2, 0) + paid(3, 0), "CRUX: the balance holds all three payments");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H3, "the scan completes through H3");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");

    fxFree(&f);
    return g_fail == 0;
}

static int case_delivering_block_rescanned(void)
{
    Fx f;
    // one block at H1: tx 0 pays index 112 (not watched when it is handed over), tx 1 pays 105 (grows the set);
    // H2 pays a foreign script
    const BlkSpec spec[] = { { 2, { 112, 105 } }, { 1, { FOREIGN } } };
    fxInit(&f, spec, 2);
    const uint32_t H1 = H0 + 1, H2 = H0 + 2;

    check(! BRWalletContainsAddress(f.w, f.addr[1][0].s), "setup: index 112 is beyond the fresh wallet's window");
    deliverFilter(&f, 1);
    deliverFilter(&f, 2);
    check(solicited(&f, 1) && ! solicited(&f, 2), "setup: H1's filter matched (on 105) and H2's missed");

    deliverBlock(&f, 1);
    check(confirmedAt(&f, 1, 1), "the payment to 105 is confirmed at H1");
    check(BRWalletBalance(f.w) == paid(1, 1),
          "setup: the payment to 112, handed over before the set grew, is not counted after the first delivery");
    noteState(&f, "after H1's block");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < H1,
          "CRUX: H1 itself is not counted as scanned (a transaction earlier in it paid a newly added address)");
    check(f.m->autoFetchCFiltersThrough < H1, "CRUX: the cursor is back below H1, so H1 is fetched again");

    uint32_t from = driveForward(&f);

    check(from == H1, "CRUX: the forward drive resumes at H1, the delivering block itself");
    check(f.delivered[1] == 2, "CRUX: H1's filter matched the grown set and its block was requested again");
    check(confirmedAt(&f, 1, 1), "the payment to 105 stays confirmed at H1");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H2, "the scan completes through H2");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");

    fxFree(&f);
    return g_fail == 0;
}

static int case_abandoned_floor_respected(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 105 } }, { 1, { FOREIGN } }, { 1, { 112 } } };   // A at H1, B at H3
    fxInit(&f, spec, 3);
    const uint32_t H1 = H0 + 1, H2 = H0 + 2, H3 = H0 + 3;

    for (size_t b = 1; b <= 3; b++) deliverFilter(&f, b);
    check(solicited(&f, 1) && ! solicited(&f, 3), "setup: A's filter matched; B's was judged against the old set");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H1 - 1, "setup: H1 (A's block in flight) pins the frontier");

    // H1 is abandoned while A's block is in flight (the B2 valve's capped-outstanding path, or a retention floor
    // that moved above it): the hard floor rises to H2 and the frontier moves past the evaluated misses
    uint32_t cnt = 0;
    MGR_LOCK(f.m);
    BRCFScanLedgerAbandonUnscannableBelow(&f.m->cfLedger, H1, H2, &cnt);
    MGR_UNLOCK(f.m);
    check(cnt == 1 && BRCFScanLedgerAbandonedBelow(&f.m->cfLedger) == H2, "setup: H1 is abandoned, the floor is H2");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H3, "setup: the frontier sits at H3 (B counted as scanned)");

    deliverBlock(&f, 1);   // A's block arrives late, below the floor, and grows the set
    check(BRWalletContainsAddress(f.w, f.addr[3][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's late block");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < H3, "CRUX: H3 is no longer counted as scanned");
    check(f.m->autoFetchCFiltersThrough + 1 >= H2 && BRCFScanLedgerScannedThrough(&f.m->cfLedger) + 1 >= H2,
          "CRUX: the rescan starts at the hard floor: nothing below abandonedBelow is re-requested or un-scanned");
    check(BRCFScanLedgerAbandonedBelow(&f.m->cfLedger) == H2, "the floor itself is unchanged");

    uint32_t from = driveForward(&f);
    check(from == H2, "CRUX: the forward drive resumes exactly at the floor");
    check(confirmedAt(&f, 3, 0), "CRUX: the payment to index 112 at H3 is found");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H3, "the scan completes through H3");

    fxFree(&f);
    return g_fail == 0;
}

static int case_in_window_no_rescan(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 5 } }, { 1, { 8 } } };
    fxInit(&f, spec, 2);
    const uint32_t HB = H0 + 2;

    size_t countBefore = 0, countAfter = 0;
    BRWalletAddrSetKey(f.w, NULL, &countBefore);
    check(BRWalletContainsAddress(f.w, f.addr[2][0].s), "setup: index 8 is inside the window");

    deliverFilter(&f, 1);
    deliverFilter(&f, 2);
    check(solicited(&f, 1) && solicited(&f, 2), "both filters matched and asked for their blocks");

    deliverBlock(&f, 1);
    BRWalletAddrSetKey(f.w, NULL, &countAfter);
    check(countAfter == countBefore, "GUARD: the address set did not grow");
    check(f.m->autoFetchCFiltersThrough == HB, "GUARD: the forward-fetch cursor did not move");
    check(f.m->cfLedger.requestedThrough == HB, "GUARD: the ledger's requested range is untouched");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 1, "GUARD: only HB (block in flight) is outstanding");

    deliverBlock(&f, 2);
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == HB, "GUARD: the scan completes through HB with no re-scan");
    check(confirmedAt(&f, 1, 0) && confirmedAt(&f, 2, 0), "GUARD: both payments are confirmed");
    check(BRWalletBalance(f.w) == paid(1, 0) + paid(2, 0), "GUARD: the balance holds both payments");

    fxFree(&f);
    return g_fail == 0;
}

static int case_frontier_start(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { FOREIGN } }, { 1, { 105 } }, { 1, { 112 } } };   // H1 foreign, A at H2, B at H3
    fxInit(&f, spec, 3);
    const uint32_t H1 = H0 + 1, H2 = H0 + 2, H3 = H0 + 3;

    for (size_t b = 1; b <= 3; b++) deliverFilter(&f, b);
    check(! solicited(&f, 1) && solicited(&f, 2) && ! solicited(&f, 3),
          "setup: H1's filter missed, A's matched, B's was judged against the old set and missed");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H1, "setup: the frontier is H1 (A's block in flight at H2)");

    f.m->saveCFLedgerInfo = &f;
    f.m->saveCFLedger = onSaveCFLedger;
    deliverBlock(&f, 2);
    check(BRWalletContainsAddress(f.w, f.addr[3][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's block (H2)");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H1, "CRUX: H1, below the frontier, stays scanned");
    check(f.m->autoFetchCFiltersThrough == H1, "CRUX: the cursor goes back to the frontier, not to the scan's start");
    printf("NOTE: saves during the hand-over: %u (last: scannedThrough=%u requestedThrough=%u)\n", f.savesInHandover,
           f.savedScanned, f.savedRequested);
    check(f.savesInHandover >= 1 && f.savedScanned == H1 && f.savedRequested < H2,
          "CRUX: the ledger saved during the hand-over holds the rewound range");

    uint32_t from = driveForward(&f);
    check(from == H2, "CRUX: the forward drive resumes at H2, the frontier");
    check(f.delivered[1] == 0, "H1's block is never requested");
    check(confirmedAt(&f, 3, 0), "CRUX: the payment to index 112 at H3 is found");
    check(BRWalletBalance(f.w) == paid(2, 0) + paid(3, 0), "the balance holds both payments");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H3, "the scan completes through H3");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");

    fxFree(&f);
    return g_fail == 0;
}

static int case_completed_height_rescanned(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 105 } }, { 1, { 112 } } };   // A at H1, B at H2
    fxInit(&f, spec, 2);
    const uint32_t H1 = H0 + 1, H2 = H0 + 2;

    deliverFilter(&f, 1);
    deliverFilter(&f, 2);
    check(solicited(&f, 1) && ! solicited(&f, 2), "setup: A's filter matched; B's was judged against the old set");

    // H1 is counted as scanned before A's transactions are handed over (the block arrives after its height was
    // completed), so the delivering block sits below the frontier
    MGR_LOCK(f.m);
    BRCFScanLedgerMarkEvaluated(&f.m->cfLedger, H1);
    MGR_UNLOCK(f.m);
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H2, "setup: the frontier is past H2");

    deliverBlock(&f, 1);
    check(BRWalletContainsAddress(f.w, f.addr[2][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's block (H1)");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < H1, "CRUX: H1 itself is no longer counted as scanned");
    check(f.m->autoFetchCFiltersThrough < H1, "CRUX: the cursor is back below H1");

    uint32_t from = driveForward(&f);
    check(from == H1, "CRUX: the forward drive resumes at H1, the delivering block");
    check(f.delivered[1] == 2, "H1's block is requested again");
    check(confirmedAt(&f, 2, 0), "CRUX: the payment to index 112 at H2 is found");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H2, "the scan completes through H2");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");

    fxFree(&f);
    return g_fail == 0;
}

static int case_top_height_rescanned(void)
{
    Fx f;
    const BlkSpec spec[] = { { 2, { 112, 105 } } };   // the only block, at the top requested height
    fxInit(&f, spec, 1);
    const uint32_t H1 = H0 + 1;

    check(f.m->cfLedger.requestedThrough == H1 && f.m->autoFetchCFiltersThrough == H1,
          "setup: H1 is the top requested height and the cursor sits on it");
    deliverFilter(&f, 1);
    check(solicited(&f, 1), "setup: H1's filter matched (on 105)");

    deliverBlock(&f, 1);
    check(confirmedAt(&f, 1, 1), "the payment to 105 is confirmed at H1");
    noteState(&f, "after H1's block");

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < H1, "CRUX: H1, the top height, is not counted as scanned");
    check(f.m->autoFetchCFiltersThrough < H1, "CRUX: the cursor, which sat on H1, is back below it");

    uint32_t from = driveForward(&f);
    check(from == H1, "CRUX: the forward drive resumes at H1");
    check(f.delivered[1] == 2, "CRUX: H1's block is requested a second time");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H1, "the scan completes through H1");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");

    fxFree(&f);
    return g_fail == 0;
}

static int case_below_start_clamped(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 105 } }, { 1, { 112 } } };   // A at H1 (below the start), B at H2
    fxInitAt(&f, spec, 2, H0 + 2);
    const uint32_t H1 = H0 + 1, H2 = H0 + 2;

    deliverFilter(&f, 2);
    check(! solicited(&f, 2), "setup: B's filter was judged against the old set and missed");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H2, "setup: the scan completed through H2");

    deliverBlock(&f, 1);   // a block below the scan's start is handed over and grows the set
    check(BRWalletContainsAddress(f.w, f.addr[2][0].s), "A's registration brought index 112 into the set");
    noteState(&f, "after A's block (H1)");

    check(BRCFScanLedgerStartHeight(&f.m->cfLedger) == H2, "CRUX: the scan's start does not move");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H1 && f.m->autoFetchCFiltersThrough == H1,
          "CRUX: the rescan starts at the scan's start");

    uint32_t from = driveForward(&f);
    check(from == H2, "the forward drive resumes at H2");
    check(confirmedAt(&f, 2, 0), "CRUX: the payment to index 112 at H2 is found");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == H2, "the scan completes through H2");

    fxFree(&f);
    return g_fail == 0;
}

static int case_unarmed_no_rescan(void)
{
    Fx f;
    const BlkSpec spec[] = { { 1, { 105 } }, { 1, { 112 } } };
    fxInit(&f, spec, 2);
    const uint32_t HB = H0 + 2;

    deliverFilter(&f, 1);
    deliverFilter(&f, 2);
    check(solicited(&f, 1) && ! solicited(&f, 2), "setup: A's filter matched; B's missed");

    f.m->autoFetchCFiltersEnabled = 0;   // the filter scan is not armed when A's block arrives
    uint32_t scannedBefore = BRCFScanLedgerScannedThrough(&f.m->cfLedger);
    size_t countBefore = 0, countAfter = 0;
    BRWalletAddrSetKey(f.w, NULL, &countBefore);

    deliverBlock(&f, 1);
    BRWalletAddrSetKey(f.w, NULL, &countAfter);
    noteState(&f, "after A's block, scan not armed");
    check(countAfter > countBefore, "setup: A's registration grew the address set");
    check(f.m->autoFetchCFiltersThrough == HB, "GUARD: the forward-fetch cursor did not move");
    check(f.m->cfLedger.requestedThrough == HB, "GUARD: the ledger's requested range is untouched");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) >= scannedBefore, "GUARD: the scanned height did not go back");

    fxFree(&f);
    return g_fail == 0;
}

int main(int argc, char *argv[])
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    const char *cases[] = { "beyond_window_found", "out_of_order_found", "delivering_block_rescanned",
                            "abandoned_floor_respected", "frontier_start", "completed_height_rescanned",
                            "top_height_rescanned", "below_start_clamped", "in_window_no_rescan",
                            "unarmed_no_rescan" };
    int (*fns[])(void) = { case_beyond_window_found, case_out_of_order_found, case_delivering_block_rescanned,
                           case_abandoned_floor_respected, case_frontier_start, case_completed_height_rescanned,
                           case_top_height_rescanned, case_below_start_clamped, case_in_window_no_rescan,
                           case_unarmed_no_rescan };
    const size_t n = sizeof(cases)/sizeof(cases[0]);

    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (size_t i = 0; i < n; i++) printf("%s\n", cases[i]); return 0; }
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], cases[i]) != 0) continue;
        int ok = fns[i]();
        printf("RESULT %s %s\n", cases[i], ok ? "pass" : "fail");
        return ok ? 0 : 1;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
