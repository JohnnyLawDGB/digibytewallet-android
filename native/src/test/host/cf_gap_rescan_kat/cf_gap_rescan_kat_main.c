// Host KAT: a compact-filter scan re-checks the heights it already judged when the wallet's watched address set
// grows (owner checklist Q18).
//
// THE DEFECT
// ----------
// The BIP158 scan matches each filter against the wallet's address set as it is when the filter arrives. That set
// is the next gap + 100 addresses past the last one used, and it grows only when a transaction is registered:
// BRWalletRegisterTransaction extends a chain whose used address is within the gap limit of its end. Filters run
// ahead of the full blocks they ask for: the filter for block A matches and asks for A, and while A is in flight the
// filter for a later block B is matched against the OLD set. If B pays an address that only A's transaction brings
// into the set (beyond the original window), B is a clean miss, its height is marked scanned, and nothing ever
// looks at it again. Silent, permanent, and it is money: the merchant / exchange pattern, many addresses handed
// out and paid within one scan pass.
//
// WHAT THIS KAT ASSERTS
// ---------------------
//   beyond_window_found   (RED, then GREEN)  B pays receive index 112 of a fresh wallet (window 0..109); A pays index
//                         105. Filters for A and B are both judged before A's block arrives, so B misses. After A's
//                         block is delivered the scan must NOT count B's height as scanned, the forward-fetch cursor
//                         must be back at or below it, and when the drive re-fetches from that cursor the payment at B
//                         is found and confirmed at B's height.
//   in_window_no_rescan   (GUARD, both builds)  the same flow paying indices 5 and 8, well inside the window: the set
//                         does not grow, nothing is rewound, the cursor and the scanned height are exactly where they
//                         were, and both payments are confirmed. A wallet that never exceeds its window is untouched.
//
// The "re-fetch" is computed from the manager's own cursor (autoFetchCFiltersThrough + 1 .. the top height), the
// range the forward drive in _peerRelayedCFHeaders / BRPeerManagerKeepAlive would request; the KAT then hands in
// the filters and blocks a peer would answer with. In the unfixed build that range is empty.
//
// RED-BEFORE-GREEN: run.sh builds -DCF_GAP_RESCAN_UNFIXED (the detection in _peerRelayedTx compiled out) and that
// build MUST fail beyond_window_found at its crux lines and pass the guard; the shipped build must pass both.
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

// ---- BIP158 single-element filter (as in ../cf_block_completion_gate_kat) ---
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

static size_t buildSingleElementFilter(UInt256 blockHash, const uint8_t *elem, size_t elemLen, uint8_t *out,
                                       size_t outCap)
{
    uint64_t k0 = UInt64GetLE(&blockHash.u8[0]);
    uint64_t k1 = UInt64GetLE(&blockHash.u8[8]);
    uint64_t F  = (uint64_t)BR_GCS_BASIC_FILTER_M; // N=1 -> F = N*M = M
    uint64_t val = test_fastrange64(test_siphash24(k0, k1, elem, elemLen), F);
    const uint8_t P = BR_GCS_BASIC_FILTER_P;
    uint64_t q = val >> P, r = val & ((((uint64_t)1) << P) - 1);
    size_t off = 1, bitPos = 0;

    memset(out, 0, outCap);
    out[0] = 0x01; // CompactSize N=1
    for (uint64_t i = 0; i < q; i++) test_gcsWriteBit(out, off, &bitPos, 1);
    test_gcsWriteBit(out, off, &bitPos, 0);
    for (int i = (int)P - 1; i >= 0; i--) test_gcsWriteBit(out, off, &bitPos, (unsigned)((r >> i) & 1));
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
// Three resident headers on one chain: H0 (empty), HA committing to {payA}, HB committing to {payB}; a filter-header
// chain whose cfheaders commit to a filter for HA holding payA's script and one for HB holding payB's; and a scan
// armed at HA with [HA..HB] requested.
#define H0 5300000u
#define HA (H0 + 1)
#define HB (H0 + 2)

typedef struct {
    BRMasterPubKey mpk;
    BRWallet *w;
    BRPeerManager *m;
    BRPeer *peer;
    BRPeerCallbackInfo info;
    BRAddress addrA, addrB;
    BRTransaction *payA, *payB;      // fixture copies; the wallet gets parsed copies
    BRMerkleBlock *blk[3];           // H0, HA, HB (owned by m->blocks)
    uint8_t filter[3][16];
    size_t filterLen[3];
} Fx;

static BRMerkleBlock *blockAt(uint32_t height, uint8_t seed, UInt256 prev, const BRTransaction *tx)
{
    BRMerkleBlock *b = BRMerkleBlockNew();

    memset(b->blockHash.u8, seed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = 1700300000u + (height - H0)*15u;
    b->prevBlock = prev;
    if (tx) assert(BRMerkleRootFromTxHashes(&b->merkleRoot, &tx->txHash, 1));
    return b;
}

static void fxInit(Fx *f, uint32_t idxA, uint32_t idxB)
{
    memset(f, 0, sizeof(*f));

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    f->mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, f->mpk);   // fresh wallet: receive window is indices 0 .. gap + 100 - 1
    assert(f->w != NULL);

    uint8_t spkA[64], spkB[64];
    size_t spkALen, spkBLen;
    receiveAddrAt(f->mpk, idxA, &f->addrA, spkA, &spkALen);
    receiveAddrAt(f->mpk, idxB, &f->addrB, spkB, &spkBLen);
    f->payA = payTx(spkA, spkALen, 100000000ULL, 0x41);
    f->payB = payTx(spkB, spkBLen, 250000000ULL, 0x42);

    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    f->blk[0] = blockAt(H0, 0xD0, UINT256_ZERO, NULL);
    f->blk[1] = blockAt(HA, 0xD1, f->blk[0]->blockHash, f->payA);
    f->blk[2] = blockAt(HB, 0xD2, f->blk[1]->blockHash, f->payB);
    for (int i = 0; i < 3; i++) BRSetAdd(f->m->blocks, f->blk[i]);
    f->m->lastBlock = f->blk[2];

    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H0, UINT256_ZERO);
    UInt256 fh;
    memset(fh.u8, 0x77, sizeof(fh.u8));   // H0's filter is never fetched; any header will do
    assert(BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain),
                                      &fh, 1) == 1);
    const uint8_t *spks[3] = { NULL, spkA, spkB };
    size_t spkLens[3] = { 0, spkALen, spkBLen };
    for (int i = 1; i < 3; i++) {
        f->filterLen[i] = buildSingleElementFilter(f->blk[i]->blockHash, spks[i], spkLens[i], f->filter[i],
                                                   sizeof(f->filter[i]));
        BRSHA256_2(fh.u8, f->filter[i], f->filterLen[i]);
        assert(BRCompactFilterChainAppend(f->m->compactFilterChain,
                                          BRCompactFilterChainTipHeader(f->m->compactFilterChain), &fh, 1) == 1);
        assert(BRCompactFilterChainVerifyFilter(f->m->compactFilterChain, f->blk[i]->height, f->filter[i],
                                                f->filterLen[i]) == 1);
    }

    // the scan, armed at HA and with [HA..HB] requested, as the forward drive leaves it
    f->m->autoFetchCFiltersEnabled = 1;
    f->m->autoFetchCFiltersStart = HA;
    f->m->autoFetchCFiltersThrough = HB;
    BRCFScanLedgerInit(&f->m->cfLedger, HA);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, HA, HB, UINT128_ZERO, 0, 1);

    f->peer = BRPeerNew(BRMainNetParams.magicNumber);
    f->peer->address.u8[15] = 0x01;
    f->peer->port = 12024;
    f->peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(f->m->connectedPeers, f->peer);
    f->info.peer = f->peer;
    f->info.manager = f->m;
}

static void fxFree(Fx *f)
{
    MGR_LOCK(f->m);
    array_clear(f->m->connectedPeers);
    MGR_UNLOCK(f->m);
    BRPeerFree(f->peer);
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    BRTransactionFree(f->payA);
    BRTransactionFree(f->payB);
}

static int solicited(Fx *f, int i)
{
    MGR_LOCK(f->m);
    int r = _BRPeerManagerFindSolicitedBlockLocked(f->m, f->blk[i]->blockHash, f->blk[i]->height);
    MGR_UNLOCK(f->m);
    return r >= 0;
}

static void deliverFilter(Fx *f, int i)
{
    _peerRelayedCFilter(&f->info, FILTER_TYPE_BASIC, f->blk[i]->blockHash, f->filter[i], f->filterLen[i]);
}

// what BRPeer.c does with a requested, merkle-verified block: hand each tx to relayedTx with the delivering flag set,
// then the tx list to relayedBlockTxns
static void deliverBlock(Fx *f, int i)
{
    BRPeerContext *ctx = (BRPeerContext *)f->peer;
    BRTransaction *tx = (i == 1) ? f->payA : f->payB;

    ctx->deliveringBlockHash = f->blk[i]->blockHash;
    ctx->deliveringBlockTxs = 1;
    _peerRelayedTx(&f->info, copyTx(tx));   // takes ownership
    ctx->deliveringBlockTxs = 0;
    ctx->deliveringBlockHash = UINT256_ZERO;
    _peerRelayedBlockTxns(&f->info, f->blk[i]->blockHash, f->blk[i]->merkleRoot, &tx->txHash, 1);
}

static int confirmedAt(Fx *f, const BRTransaction *tx, uint32_t height)
{
    BRTransaction *t = BRWalletTransactionForHash(f->w, tx->txHash);
    return t != NULL && t->blockHeight == height;
}

// The forward drive: request from the cursor up to the top of the filter-header chain, then answer each request the
// way a peer would (the filter, and the full block for any filter that asked for one).
static void driveForward(Fx *f)
{
    uint32_t from = f->m->autoFetchCFiltersThrough + 1;
    if (from < f->m->autoFetchCFiltersStart) from = f->m->autoFetchCFiltersStart;
    printf("NOTE: forward drive re-requests [%u..%u]%s\n", from, HB, from > HB ? " (nothing)" : "");
    if (from > HB) return;

    BRCFScanLedgerRecordRequested(&f->m->cfLedger, from, HB, f->peer->address, f->peer->port, 2);
    f->m->autoFetchCFiltersThrough = HB;
    for (uint32_t h = from; h <= HB; h++) {
        int i = (int)(h - H0);
        deliverFilter(f, i);
        if (solicited(f, i)) deliverBlock(f, i);
    }
}

// ---- cases ------------------------------------------------------------------
static int case_beyond_window_found(void)
{
    Fx f;
    fxInit(&f, 105, 112);

    check(BRWalletContainsAddress(f.w, f.addrA.s), "setup: index 105 is inside the fresh wallet's window");
    check(! BRWalletContainsAddress(f.w, f.addrB.s), "setup: index 112 is beyond the fresh wallet's window");

    deliverFilter(&f, 1);
    check(solicited(&f, 1), "setup: the filter for HA matched and asked for HA's block");
    deliverFilter(&f, 2);
    check(! solicited(&f, 2), "setup: the filter for HB was judged against the old set and missed (the defect's shape)");

    deliverBlock(&f, 1);
    check(confirmedAt(&f, f.payA, HA), "payment A is confirmed at HA");
    check(BRWalletContainsAddress(f.w, f.addrB.s), "A's registration brought index 112 into the set");
    printf("NOTE: after A's block: scannedThrough=%u cursor=%u requestedThrough=%u\n",
           BRCFScanLedgerScannedThrough(&f.m->cfLedger), f.m->autoFetchCFiltersThrough,
           f.m->cfLedger.requestedThrough);

    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) < HB,
          "CRUX: HB, judged against the set before it grew, is not counted as scanned");
    check(f.m->autoFetchCFiltersThrough < HB,
          "CRUX: the forward-fetch cursor is back below HB, so its filter is fetched again");

    driveForward(&f);

    check(confirmedAt(&f, f.payB, HB), "CRUX: the payment beyond the original window is found and confirmed at HB");
    check(BRWalletBalance(f.w) == 350000000ULL, "CRUX: the balance holds both payments");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == HB, "the scan completes through HB");
    check(BRCFScanLedgerOutstandingCount(&f.m->cfLedger) == 0, "nothing is left outstanding");
    check(confirmedAt(&f, f.payA, HA), "payment A is still confirmed at HA after the re-scan");

    fxFree(&f);
    return g_fail == 0;
}

static int case_in_window_no_rescan(void)
{
    Fx f;
    fxInit(&f, 5, 8);

    size_t countBefore = 0, countAfter = 0;
    BRWalletAddrSetKey(f.w, NULL, &countBefore);
    check(BRWalletContainsAddress(f.w, f.addrB.s), "setup: index 8 is inside the window");

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
    check(confirmedAt(&f, f.payA, HA) && confirmedAt(&f, f.payB, HB), "GUARD: both payments are confirmed");
    check(BRWalletBalance(f.w) == 350000000ULL, "GUARD: the balance holds both payments");

    fxFree(&f);
    return g_fail == 0;
}

int main(int argc, char *argv[])
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    const char *cases[] = { "beyond_window_found", "in_window_no_rescan" };
    int (*fns[])(void) = { case_beyond_window_found, case_in_window_no_rescan };

    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (size_t i = 0; i < 2; i++) printf("%s\n", cases[i]); return 0; }
    for (size_t i = 0; i < 2; i++) {
        if (strcmp(argv[1], cases[i]) != 0) continue;
        int ok = fns[i]();
        printf("RESULT %s %s\n", cases[i], ok ? "pass" : "fail");
        return ok ? 0 : 1;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
