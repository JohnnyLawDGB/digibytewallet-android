// Host KAT: the compact-filter header chain and the scan ledger follow the best chain through a
// reorg, and a peer is penalised only for filter data that contradicts a header the wallet holds.
//
// INVARIANTS
//   Reorg       after a header reorg the filter-header chain and the scan ledger describe the new
//               best chain; every block on the new branch above the fork is filter-scanned, in
//               particular the block that replaced an orphaned one. A fork below the chain's own
//               start rebuilds the chain from the fork, without spending the re-anchor budget.
//   No penalty  a cfilter at a height outside the wallet's filter-header chain (below its start or
//               above its tip), or for a block the best chain no longer holds at that height, is not
//               evidence against the peer. A cfilter that contradicts a header the wallet does hold
//               for that very block still is.
//   Budget      the re-anchor budget bounds a run of continuity failures: enough clean appends after
//               a re-anchor restore it; back-to-back failures still exhaust it.
//   Parking     the forward fetch never parks below the filter-header chain's start.
//   Gap         a band surfaced as abandoned never includes heights the scan already evaluated.
//   Peers       disagreeing peers are distinct by address AND port.
//
// HOW. Same "#include the .c" idiom as cf_checkpoint_neverbrick_kat: a real BRPeerManager and
// BRWallet; the real _peerRelayedBlock / _peerRelayedCFHeaders / _peerRelayedCFilter are driven
// with synthetic peers. Each peer holds one end of a local socket pair so the manager's own
// getcfilters / getcfheaders sends go out and the forward fetch records what it asked for. Filters
// are real BIP 158 encodings: an empty filter (N = 0) followed by the block hash, so every block has
// its own filter hash and the real verify -> parse -> MarkEvaluated path runs.
//
// One case per process: argv[1] names it; the binary prints "RESULT <case> PASS|FAIL". run.sh owns
// which arm each case must fail in (RED) or pass in (GUARD).
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup
#include "BRPeerManager.c"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }

// ---- fixture -------------------------------------------------------------------------------------

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon "
                           "abandon abandon abandon about", NULL);
    return BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
}

static int g_peerFds[16]; static int g_peerFdCount = 0;
static BRPeer *addPeerAt(BRPeerManager *m, uint8_t a, uint16_t port)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[10] = 0xff; p->address.u8[11] = 0xff; p->address.u8[12] = 10; p->address.u8[15] = a;
    p->port = port;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0) {
        ((BRPeerContext *)p)->socket = sv[0];
        if (g_peerFdCount < 16) g_peerFds[g_peerFdCount++] = sv[1];
    }
    array_add(m->connectedPeers, p);
    return p;
}
static BRPeer *addPeer(BRPeerManager *m, uint8_t a) { return addPeerAt(m, a, 12024); }

static UInt256 hashOf(uint32_t h, uint8_t tag)
{
    uint8_t buf[5] = { (uint8_t)h, (uint8_t)(h >> 8), (uint8_t)(h >> 16), (uint8_t)(h >> 24), tag };
    UInt256 r; BRSHA256(&r, buf, sizeof(buf)); return r;
}
static void encodedOf(UInt256 blockHash, uint8_t out[33]) { out[0] = 0x00; memcpy(out + 1, blockHash.u8, 32); }
static UInt256 filterHashOf(UInt256 blockHash)
{
    uint8_t e[33]; encodedOf(blockHash, e); UInt256 r; BRSHA256_2(&r, e, 33); return r;
}

static uint32_t g_ts = 1790000000;
static BRMerkleBlock *mkBlock(uint32_t h, UInt256 prev, uint8_t tag)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->version = 0x20000000 | BLOCK_VERSION_SHA256D;
    b->prevBlock = prev;
    b->blockHash = hashOf(h, tag);
    b->height = h;
    b->timestamp = g_ts += 15;
    b->target = 0x1a0fffff;
    return b;
}

// The fixture chain. Heights [LOW .. T] are resident and linked; the filter-header chain starts at
// CF_START with anchor g_anchor (the honest header at CF_START - 1); the ledger has scanned
// [CF_START .. T]. g_best[h - LOW] is the block the best chain holds at h; refHeader(h) is the
// honest filter header at h on it.
#define CF_START 24317280u           // above the top compiled CF checkpoint (24,250,000)
#define LOW      (CF_START - 20u)
#define MAXH     160
typedef struct {
    BRWallet *w; BRPeerManager *m;
    BRPeer *p[3]; BRPeerCallbackInfo inf[3];
    BRMerkleBlock *best[MAXH];
    UInt256 anchor;
    uint32_t T;
} Fx;

static UInt256 refHeader(Fx *f, uint32_t h)
{
    UInt256 x = f->anchor;
    for (uint32_t k = CF_START; k <= h; k++) x = BRGCSFilterHeader(filterHashOf(f->best[k - LOW]->blockHash), x);
    return x;
}
static UInt256 filterHashAt(Fx *f, uint32_t h) { return filterHashOf(f->best[h - LOW]->blockHash); }

static void deliverFilter(BRPeerCallbackInfo *inf, UInt256 blockHash)
{
    uint8_t e[33]; encodedOf(blockHash, e);
    _peerRelayedCFilter(inf, FILTER_TYPE_BASIC, blockHash, e, sizeof(e));
}
static int outstandingHas(BRPeerManager *m, uint32_t h)
{
    for (size_t k = 0; k < m->cfLedger.outstandingCount; k++) if (m->cfLedger.outstanding[k].height == h) return 1;
    return 0;
}
// Deliver the honest filter for every outstanding height (what peers do after getcfilters).
static void serveOutstanding(Fx *f, int via)
{
    uint32_t hs[CF_OUTSTANDING_MAX]; size_t n = f->m->cfLedger.outstandingCount;
    for (size_t k = 0; k < n; k++) hs[k] = f->m->cfLedger.outstanding[k].height;
    for (size_t k = 0; k < n; k++) {
        if (hs[k] < LOW || hs[k] - LOW >= MAXH || ! f->best[hs[k] - LOW]) continue;
        deliverFilter(&f->inf[via], f->best[hs[k] - LOW]->blockHash);
    }
}
// Honest cfheaders for [from .. to] on the best chain, stop = best[to], prev = honest header(from-1).
static void honestCFHeaders(Fx *f, int via, uint32_t from, uint32_t to)
{
    UInt256 hs[MAXH];
    for (uint32_t h = from; h <= to; h++) hs[h - from] = filterHashAt(f, h);
    UInt256 prev = (from == CF_START) ? f->anchor : refHeader(f, from - 1);
    _peerRelayedCFHeaders(&f->inf[via], FILTER_TYPE_BASIC, f->best[to - LOW]->blockHash, prev, hs, to - from + 1);
}

static void fxInit(Fx *f, uint32_t tip)
{
    memset(f, 0, sizeof(*f));
    f->w = makeWallet();
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    for (int i = 0; i < 3; i++) {
        f->p[i] = addPeer(f->m, (uint8_t)(i + 1));
        f->inf[i] = (BRPeerCallbackInfo){ .peer = f->p[i], .manager = f->m, .hash = UINT256_ZERO };
    }
    memset(f->anchor.u8, 0xA0, 32);
    UInt256 prev = hashOf(LOW - 1, 0);
    for (uint32_t h = LOW; h <= tip; h++) {
        f->best[h - LOW] = mkBlock(h, prev, 0);
        BRSetAdd(f->m->blocks, f->best[h - LOW]);
        prev = f->best[h - LOW]->blockHash;
    }
    f->T = tip;
    f->m->lastBlock = f->best[tip - LOW];
    f->m->estimatedHeight = tip;

    f->m->autoFetchCFiltersEnabled = 1;
    f->m->autoFetchCFiltersStart = CF_START;
    f->m->autoFetchCFiltersThrough = CF_START - 1;
    BRCFScanLedgerInit(&f->m->cfLedger, CF_START);
    // the chain and the scan reach the tip through the real paths
    honestCFHeaders(f, 0, CF_START, tip);
    serveOutstanding(f, 0);
}
static void fxFree(Fx *f)
{
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    for (int i = 0; i < g_peerFdCount; i++) close(g_peerFds[i]);
    g_peerFdCount = 0;
}

// Relay a block that extends the best chain.
static BRMerkleBlock *extend(Fx *f, uint8_t tag)
{
    uint32_t h = f->T + 1;
    BRMerkleBlock *b = mkBlock(h, f->best[f->T - LOW]->blockHash, tag);
    UInt256 hash = b->blockHash;
    _peerRelayedBlock(&f->inf[0], b);
    f->best[h - LOW] = BRSetGet(f->m->blocks, &hash);
    f->T = h;
    return f->best[h - LOW];
}

// One cycle of the device-day shape: X at T+1 appended and scanned, then a 1-block reorg onto Y (T+1),
// Z (T+2). Returns X's hash. On return f->best / f->T describe the new branch (tip Z).
static UInt256 cycleReorg(Fx *f, int cycle)
{
    uint32_t T = f->T;
    BRMerkleBlock *X = extend(f, (uint8_t)(0x10 + cycle));
    UInt256 xHash = X->blockHash;
    honestCFHeaders(f, 0, T + 1, T + 1);
    serveOutstanding(f, 0);                                  // X is scanned
    BRMerkleBlock *Y = mkBlock(T + 1, f->best[T - LOW]->blockHash, (uint8_t)(0x20 + cycle));
    BRMerkleBlock *Z = mkBlock(T + 2, Y->blockHash, (uint8_t)(0x30 + cycle));
    UInt256 yHash = Y->blockHash, zHash = Z->blockHash;
    _peerRelayedBlock(&f->inf[0], Y);
    _peerRelayedBlock(&f->inf[0], Z);
    f->best[T + 1 - LOW] = BRSetGet(f->m->blocks, &yHash);
    f->best[T + 2 - LOW] = BRSetGet(f->m->blocks, &zHash);
    f->T = T + 2;
    return xHash;
}

// ---- cases: reorg ----------------------------------------------------------------------------------

static void case_one_block_reorg_rescans_replacement(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    uint32_t T = f.T;
    int mb = f.m->misbehavinCount;
    cycleReorg(&f, 1);
    check(UInt256Eq(f.m->lastBlock->blockHash, f.best[T + 2 - LOW]->blockHash), "the header chain reorganised onto Y, Z");
    check(BRCompactFilterChainNextHeight(f.m->compactFilterChain) == T + 1 &&
          UInt256Eq(BRCompactFilterChainTipHeader(f.m->compactFilterChain), refHeader(&f, T)),
          "the filter-header chain now ends at the join T (the orphaned X's header is gone)");
    check(BRCFScanLedgerLowestNeededHeight(&f.m->cfLedger) == T + 1 && f.m->autoFetchCFiltersThrough == T,
          "the scan ledger and the forward cursor are back at T+1");
    honestCFHeaders(&f, 1, T + 1, T + 2);
    check(BRCompactFilterChainNextHeight(f.m->compactFilterChain) == T + 3 &&
          UInt256Eq(BRCompactFilterChainTipHeader(f.m->compactFilterChain), refHeader(&f, T + 2)),
          "honest cfheaders for the new branch append from the join");
    check(outstandingHas(f.m, T + 1), "the replacement block Y (T+1) is filter-requested");
    serveOutstanding(&f, 1);
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) >= T + 2, "...and scanned (scannedThrough reaches Z)");
    check(f.m->cfReanchorCount == 0 && f.m->cfDisagreedCount == 0 && f.m->compactFilterChain != NULL,
          "no re-anchor, no disagreement");
    check(f.m->misbehavinCount == mb, "no peer closed as misbehaving");
    fxFree(&f);
}

static void case_four_reorgs_no_park(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    int mb = f.m->misbehavinCount;
    for (int c = 1; c <= 4; c++) {
        uint32_t T = f.T;
        cycleReorg(&f, c);
        honestCFHeaders(&f, c % 3, T + 1, T + 2);
        serveOutstanding(&f, (c + 1) % 3);
    }
    uint32_t cp = BRCFTopCheckpointHeight();
    check(f.m->cfReanchorCount == 0, "four reorgs in one manager's life spend no re-anchor budget");
    check(f.m->autoFetchCFiltersStart != cp && f.m->autoFetchCFiltersStart >= BRCompactFilterChainStartHeight(f.m->compactFilterChain),
          "the forward fetch is not parked (not at the checkpoint, not below the chain start)");
    check(BRPeerManagerAbandonedBelow(f.m) == 0, "no band is surfaced as abandoned");
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == f.T &&
          UInt256Eq(BRCompactFilterChainTipHeader(f.m->compactFilterChain), refHeader(&f, f.T)),
          "the chain and the scan are at the new tip, every replacement block scanned");
    check(f.m->misbehavinCount == mb, "no peer closed as misbehaving");
    fxFree(&f);
}

static void case_deep_reorg_below_chain_start_reanchors(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    uint32_t T = f.T, J = CF_START - 5;   // the fork joins below the chain's start (anchor at CF_START-1)
    int mb = f.m->misbehavinCount;
    UInt256 prev = f.best[J - LOW]->blockHash;
    for (uint32_t h = J + 1; h <= T + 1; h++) {   // a longer branch from J
        BRMerkleBlock *b = mkBlock(h, prev, 0x66);
        UInt256 hash = b->blockHash;
        prev = hash;
        _peerRelayedBlock(&f.inf[0], b);
        f.best[h - LOW] = BRSetGet(f.m->blocks, &hash);
    }
    f.T = T + 1;
    check(UInt256Eq(f.m->lastBlock->blockHash, f.best[f.T - LOW]->blockHash), "the header chain reorganised onto the deep branch");
    check(f.m->compactFilterChain == NULL && f.m->autoFetchCFiltersStart == J + 1 &&
          BRCFScanLedgerLowestNeededHeight(&f.m->cfLedger) == J + 1,
          "the filter-header chain is rebuilt from the fork: chain dropped, cursor and ledger at J+1");
    check(f.m->cfReanchorCount == 0, "following the reorg is not charged to the re-anchor budget");
    check(BRPeerManagerAbandonedBelow(f.m) == 0, "nothing surfaced as abandoned (the fork is above the block floor)");
    // honest peers rebuild from J+1: the TOFU anchor is the honest header at J (not computable here, any value)
    UInt256 hs[MAXH]; for (uint32_t h = J + 1; h <= f.T; h++) hs[h - J - 1] = filterHashAt(&f, h);
    UInt256 anchorJ; memset(anchorJ.u8, 0xC3, 32);
    _peerRelayedCFHeaders(&f.inf[1], FILTER_TYPE_BASIC, f.best[f.T - LOW]->blockHash, anchorJ, hs, f.T - J);
    check(f.m->compactFilterChain && BRCompactFilterChainStartHeight(f.m->compactFilterChain) == J + 1 &&
          BRCompactFilterChainNextHeight(f.m->compactFilterChain) == f.T + 1, "the rebuilt chain covers [J+1 .. tip]");
    serveOutstanding(&f, 2);
    check(BRCFScanLedgerScannedThrough(&f.m->cfLedger) == f.T, "every block of the new branch above the fork is scanned");
    check(f.m->misbehavinCount == mb, "no peer closed as misbehaving");
    fxFree(&f);
}

static void case_stale_cfheaders_for_replaced_block_ignored(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    uint32_t T = f.T;
    BRMerkleBlock *X = extend(&f, 0x11);          // X announced; its cfheaders are still in flight
    UInt256 xHash = X->blockHash, fx = filterHashOf(xHash);
    BRMerkleBlock *Y = mkBlock(T + 1, f.best[T - LOW]->blockHash, 0x21);
    BRMerkleBlock *Z = mkBlock(T + 2, Y->blockHash, 0x31);
    UInt256 yHash = Y->blockHash, zHash = Z->blockHash;
    _peerRelayedBlock(&f.inf[0], Y);
    _peerRelayedBlock(&f.inf[0], Z);
    f.best[T + 1 - LOW] = BRSetGet(f.m->blocks, &yHash);
    f.best[T + 2 - LOW] = BRSetGet(f.m->blocks, &zHash);
    f.T = T + 2;
    int mb = f.m->misbehavinCount;
    // the in-flight answer for [T+1 .. stop X] arrives after the reorg
    _peerRelayedCFHeaders(&f.inf[0], FILTER_TYPE_BASIC, xHash, refHeader(&f, T), &fx, 1);
    check(BRCompactFilterChainNextHeight(f.m->compactFilterChain) == T + 1,
          "a batch ending on the replaced block X does not extend the chain");
    honestCFHeaders(&f, 1, T + 1, T + 2);
    check(UInt256Eq(BRCompactFilterChainTipHeader(f.m->compactFilterChain), refHeader(&f, T + 2)),
          "the chain follows the new branch");
    check(f.m->misbehavinCount == mb && f.m->cfDisagreedCount == 0, "no penalty, no disagreement");
    fxFree(&f);
}

// A wallet transaction the orphaned block confirmed is un-confirmed by the reorg (the existing
// BRWalletSetTxUnconfirmedAfter at the join), before the filter scan re-finds it on the new branch.
static void case_orphan_credited_tx_unconfirmed(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    uint32_t T = f.T;
    BRAddress a0 = BRWalletReceiveAddress(f.w, 0);
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    static const uint8_t ph[1] = { 0 };
    UInt256 prevOut; memset(prevOut.u8, 0x11, 32);
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prevOut, 0, 0, spk, spkLen, ph, 0, ph, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 5020000, spk, spkLen);
    {
        uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
        size_t len = BRTransactionSerialize(tx, data, sizeof(data));
        BRTransaction *t = BRTransactionParse(data, len);
        if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    }
    UInt256 txHash = tx->txHash;
    check(BRWalletRegisterTransaction(f.w, tx) != 0, "setup: a payment to the wallet is registered");
    BRMerkleBlock *X = extend(&f, 0x13);
    BRWalletUpdateTransactions(f.w, &txHash, 1, T + 1, X->timestamp);   // the orphan-to-be confirmed it
    check(BRWalletTransactionForHash(f.w, txHash)->blockHeight == T + 1, "setup: confirmed at X's height");
    BRMerkleBlock *Y = mkBlock(T + 1, f.best[T - LOW]->blockHash, 0x23);
    BRMerkleBlock *Z = mkBlock(T + 2, Y->blockHash, 0x33);
    _peerRelayedBlock(&f.inf[0], Y);
    _peerRelayedBlock(&f.inf[0], Z);
    check(BRWalletTransactionForHash(f.w, txHash)->blockHeight == TX_UNCONFIRMED,
          "after the reorg the payment is unconfirmed until the new branch confirms it");
    fxFree(&f);
}

// ---- cases: penalties --------------------------------------------------------------------------------

static void case_cfilter_below_chain_start_no_penalty(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    int mb = f.m->misbehavinCount;
    BRMerkleBlock *below = f.best[CF_START - 3 - LOW];            // resident, below the chain's start
    deliverFilter(&f.inf[1], below->blockHash);
    uint32_t cp = BRCFTopCheckpointHeight();                      // the device-day shape: a cfilter at the checkpoint
    BRMerkleBlock *cpb = mkBlock(cp, hashOf(cp - 1, 0), 0x77);
    UInt256 cpHash = cpb->blockHash;
    BRSetAdd(f.m->blocks, cpb);
    uint8_t empty[1] = { 0x00 };
    _peerRelayedCFilter(&f.inf[2], FILTER_TYPE_BASIC, cpHash, empty, sizeof(empty));
    check(f.m->misbehavinCount == mb, "honest cfilters below the chain start cost the peer nothing");
    check(BRPeerConnectStatus(f.p[1]) == BRPeerStatusConnected && BRPeerConnectStatus(f.p[2]) == BRPeerStatusConnected,
          "...and both peers stay connected");
    fxFree(&f);
}

static void case_cfilter_above_chain_tip_no_penalty(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    int mb = f.m->misbehavinCount;
    BRMerkleBlock *nb = extend(&f, 0x41);        // header known, its cfheader not yet
    size_t buffered0 = BRCFScanLedgerBufferedCount(&f.m->cfLedger);
    deliverFilter(&f.inf[1], nb->blockHash);
    check(f.m->misbehavinCount == mb, "a cfilter above the chain's tip costs the peer nothing");
    check(BRCFScanLedgerBufferedCount(&f.m->cfLedger) == buffered0 + 1, "...and its bytes are held for when the cfheader arrives");
    fxFree(&f);
}

static void case_cfilter_for_replaced_block_no_penalty(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    uint32_t T = f.T;
    UInt256 xHash = cycleReorg(&f, 1);
    honestCFHeaders(&f, 1, T + 1, T + 2);        // the chain now holds Y's header at T+1
    int mb = f.m->misbehavinCount;
    deliverFilter(&f.inf[2], xHash);              // a late answer for the orphaned X
    check(f.m->misbehavinCount == mb, "a cfilter for a block the best chain no longer holds costs the peer nothing");
    fxFree(&f);
}

static void case_cfilter_contradicting_held_header_penalised(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    int mb = f.m->misbehavinCount;
    UInt256 h = f.best[CF_START + 2 - LOW]->blockHash;
    uint8_t wrong[33]; encodedOf(h, wrong); wrong[32] ^= 0x01;   // not the filter our header commits to
    _peerRelayedCFilter(&f.inf[1], FILTER_TYPE_BASIC, h, wrong, sizeof(wrong));
    check(f.m->misbehavinCount == mb + 1, "a cfilter that contradicts a header we hold for that block is still penalised");
    fxFree(&f);
}

// ---- cases: budget, park, gap, peers -------------------------------------------------------------

// Three connected filter peers all claim the same prevFilterHeader that is not our tip: a quorum.
static void quorumDisagree(Fx *f, uint8_t salt)
{
    uint32_t next = BRCompactFilterChainNextHeight(f->m->compactFilterChain);
    UInt256 alt; memset(alt.u8, salt, 32);
    UInt256 fh = filterHashAt(f, next);
    for (int i = 0; i < 3; i++)
        _peerRelayedCFHeaders(&f->inf[i], FILTER_TYPE_BASIC, f->best[next - LOW]->blockHash, alt, &fh, 1);
}
// After a re-anchor the chain is NULL; honest peers rebuild it from the restart height (TOFU anchor).
static void rebuildAfterReanchor(Fx *f, int via)
{
    uint32_t rs = f->m->autoFetchCFiltersStart;
    UInt256 hs[MAXH]; for (uint32_t h = rs; h <= f->T; h++) hs[h - rs] = filterHashAt(f, h);
    UInt256 tofu = refHeader(f, rs - 1);
    _peerRelayedCFHeaders(&f->inf[via], FILTER_TYPE_BASIC, f->best[f->T - LOW]->blockHash, tofu, hs, f->T - rs + 1);
}

static void case_reanchor_budget_restored_after_clean_appends(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    extend(&f, 0x51);
    quorumDisagree(&f, 0xD1);
    check(f.m->cfReanchorCount == 1 && f.m->compactFilterChain == NULL, "setup: a quorum of disagreeing peers spends one re-anchor");
    rebuildAfterReanchor(&f, 0);
    check(f.m->cfReanchorCount == 1, "the batch that anchors the rebuilt chain does not refund anything");
    for (int k = 0; k < CF_REANCHOR_REFUND_CLEAN_APPENDS; k++) {
        uint32_t h = f.T + 1;
        extend(&f, (uint8_t)(0x52 + k));
        honestCFHeaders(&f, k % 3, h, h);
    }
    check(f.m->cfReanchorCount == 0, "CF_REANCHOR_REFUND_CLEAN_APPENDS clean appends restore the re-anchor budget");
    fxFree(&f);
}

static void case_reanchor_budget_bounds_a_run_of_failures(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    for (int r = 1; r <= CF_CONTINUITY_REANCHOR_MAX; r++) {
        extend(&f, (uint8_t)(0x60 + r));
        quorumDisagree(&f, (uint8_t)(0xE0 + r));
        rebuildAfterReanchor(&f, r % 3);
    }
    check(f.m->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX, "back-to-back failures spend the whole budget");
    extend(&f, 0x6F);
    quorumDisagree(&f, 0xEF);
    check(f.m->compactFilterChain != NULL && f.m->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX,
          "...and a further failure in the same run re-anchors no more");
    fxFree(&f);
}

// Budget exhausted, a corroborated divergence: the never-brick park runs.
static void exhaustAndDiverge(Fx *f)
{
    f->m->cfReanchorCount = CF_CONTINUITY_REANCHOR_MAX;
    quorumDisagree(f, 0xF5);
}

static void case_park_never_below_chain_start(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    extend(&f, 0x71);
    uint32_t chainStart = BRCompactFilterChainStartHeight(f.m->compactFilterChain);
    exhaustAndDiverge(&f);
    printf("  park: autoFetchCFiltersStart=%u chainStart=%u checkpoint=%u\n",
           f.m->autoFetchCFiltersStart, chainStart, BRCFTopCheckpointHeight());
    check(f.m->autoFetchCFiltersStart >= chainStart && f.m->autoFetchCFiltersThrough + 1 >= chainStart,
          "the forward fetch is never parked below the filter-header chain's start");
    fxFree(&f);
}

static void case_abandoned_band_excludes_scanned_heights(void)
{
    Fx f; fxInit(&f, CF_START + 5);     // scanned through the tip
    extend(&f, 0x81);
    exhaustAndDiverge(&f);
    check(BRPeerManagerAbandonedBelow(f.m) == 0, "a park over heights the scan already evaluated surfaces no gap");

    // Ledger level: only the unevaluated part of a band is surfaced.
    BRCFScanLedger *l = calloc(1, sizeof(*l)), *l2 = calloc(1, sizeof(*l2));
    BRCFScanLedgerInit(l, 1000);
    BRCFScanLedgerRecordRequested(l, 1000, 1199, UINT128_ZERO, 0, 1);
    for (uint32_t h = 1000; h <= 1149; h++) BRCFScanLedgerMarkEvaluated(l, h);   // scanned through 1149
    uint32_t cnt = 0;
    BRCFScanLedgerAbandonUnscannableBelow(l, 1000, 1200, &cnt);     // a low edge inside the scanned range
    check(cnt == 50 && BRCFScanLedgerAbandonedBelow(l) == 1200,
          "a band [1000..1199] over a ledger scanned through 1149 surfaces only [1150..1199]");
    BRCFScanLedgerInit(l2, 1000);
    BRCFScanLedgerRecordRequested(l2, 1000, 1199, UINT128_ZERO, 0, 1);
    for (uint32_t h = 1000; h <= 1199; h++) BRCFScanLedgerMarkEvaluated(l2, h);
    BRCFScanLedgerAbandonUnscannableBelow(l2, 1000, 1200, &cnt);
    check(cnt == 0 && BRCFScanLedgerAbandonedBelow(l2) == 0, "a band the ledger fully evaluated surfaces nothing");
    BRCFScanLedgerFree(l); BRCFScanLedgerFree(l2); free(l); free(l2);
    fxFree(&f);
}

static void case_disagreers_distinct_by_address_and_port(void)
{
    Fx f; fxInit(&f, CF_START + 5);
    BRPeer *a = addPeerAt(f.m, 9, 12024), *b = addPeerAt(f.m, 9, 13024);   // one host, two nodes
    BRPeerCallbackInfo ia = { .peer = a, .manager = f.m, .hash = UINT256_ZERO };
    BRPeerCallbackInfo ib = { .peer = b, .manager = f.m, .hash = UINT256_ZERO };
    extend(&f, 0x91);
    uint32_t next = BRCompactFilterChainNextHeight(f.m->compactFilterChain);
    UInt256 alt; memset(alt.u8, 0xAB, 32);
    UInt256 fh = filterHashAt(&f, next);
    _peerRelayedCFHeaders(&ia, FILTER_TYPE_BASIC, f.best[next - LOW]->blockHash, alt, &fh, 1);
    _peerRelayedCFHeaders(&ib, FILTER_TYPE_BASIC, f.best[next - LOW]->blockHash, alt, &fh, 1);
    check(f.m->cfDisagreedCount == 2, "two nodes on one IP (ports 12024, 13024) count as two disagreeing peers");
    fxFree(&f);
}

// ---- ledger rewind unit (GUARD) ---------------------------------------------------------------------

static void case_ledger_rewind_unit(void)
{
    BRCFScanLedger *l = calloc(1, sizeof(*l));
    BRCFScanLedgerInit(l, 1000);
    BRCFScanLedgerRecordRequested(l, 1000, 1099, UINT128_ZERO, 0, 1);
    for (uint32_t h = 1000; h <= 1079; h++) BRCFScanLedgerMarkEvaluated(l, h);   // 1080..1099 outstanding
    l->gaveUp[0] = 1090; l->gaveUpCount = 1;                                     // and one given-up hole
    check(BRCFScanLedgerRewindTo(l, 1050) == 1, "rewind reports a change");
    check(BRCFScanLedgerScannedThrough(l) == 1049 && l->requestedThrough == 1049 &&
          BRCFScanLedgerOutstandingCount(l) == 0 && BRCFScanLedgerGaveUpCount(l) == 0 &&
          BRCFScanLedgerLowestNeededHeight(l) == 1050,
          "rewind to 1050: scanned/requested through 1049, the old branch's holes are gone");
    check(BRCFScanLedgerRewindTo(l, 1200) == 0 && BRCFScanLedgerScannedThrough(l) == 1049,
          "a rewind above the requested range changes nothing");
    check(BRCFScanLedgerRewindTo(l, 990) == 1 && BRCFScanLedgerStartHeight(l) == 990 &&
          BRCFScanLedgerLowestNeededHeight(l) == 990, "a rewind below the start moves the start down");
    check(BRCFScanLedgerRewindTo(l, 0) == 0, "genesis cannot be rewound to");
    BRCFScanLedgerFree(l); free(l);
}

// ---- dispatch ---------------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "one_block_reorg_rescans_replacement",            case_one_block_reorg_rescans_replacement },
    { "four_reorgs_no_park",                            case_four_reorgs_no_park },
    { "deep_reorg_below_chain_start_reanchors",         case_deep_reorg_below_chain_start_reanchors },
    { "stale_cfheaders_for_replaced_block_ignored",     case_stale_cfheaders_for_replaced_block_ignored },
    { "orphan_credited_tx_unconfirmed",                 case_orphan_credited_tx_unconfirmed },
    { "cfilter_below_chain_start_no_penalty",           case_cfilter_below_chain_start_no_penalty },
    { "cfilter_above_chain_tip_no_penalty",             case_cfilter_above_chain_tip_no_penalty },
    { "cfilter_for_replaced_block_no_penalty",          case_cfilter_for_replaced_block_no_penalty },
    { "cfilter_contradicting_held_header_penalised",    case_cfilter_contradicting_held_header_penalised },
    { "reanchor_budget_restored_after_clean_appends",   case_reanchor_budget_restored_after_clean_appends },
    { "reanchor_budget_bounds_a_run_of_failures",       case_reanchor_budget_bounds_a_run_of_failures },
    { "park_never_below_chain_start",                   case_park_never_below_chain_start },
    { "abandoned_band_excludes_scanned_heights",        case_abandoned_band_excludes_scanned_heights },
    { "disagreers_distinct_by_address_and_port",        case_disagreers_distinct_by_address_and_port },
    { "ledger_rewind_unit",                             case_ledger_rewind_unit },
};

int main(int argc, char **argv)
{
    if (argc < 2) {
        for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) printf("%s\n", CASES[i].name);
        return 2;
    }
    for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("case %s\n", CASES[i].name);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail ? "FAIL" : "PASS");
        return g_fail ? 1 : 0;
    }
    printf("unknown case %s\n", argv[1]);
    return 2;
}
