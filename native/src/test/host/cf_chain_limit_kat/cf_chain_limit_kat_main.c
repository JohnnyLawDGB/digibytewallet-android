// Host KAT: the compact-filter header chain never stops at its size limit.
//
// INVARIANT. BR_COMPACT_FILTER_CHAIN_MAX (2^21 headers, about 364 days of DigiByte blocks) bounds what
// the chain keeps, not how far it can go. At the limit the chain keeps only the headers the filter
// scan still needs, anchored at the header just below them, and goes on appending. Reaching the limit
// is the wallet's own state: it is reported distinctly (BR_CF_APPEND_LIMIT), never as a peer
// disagreeing, and never costs a peer anything.
//
// CASES (argv[1]; "RESULT <case> PASS|FAIL")
//   append_limit_distinct            AppendEx reports LIMIT for a batch that continues the chain but does
//                                    not fit, MISMATCH for one that does not continue it (continuity is
//                                    checked first), and Append keeps its 0/1 contract.
//   limit_crossing_continues         a real manager whose chain starts at 21,400,000 (a wallet born
//                                    2025-06-01) crosses 21,400,000 + MAX through _peerRelayedCFHeaders:
//                                    it continues, keeps an honest anchor, records no disagreement, closes
//                                    no peer, and the scan (real _peerRelayedCFilter) reaches the target.
//   limit_held_while_scan_needs_all  at the limit with the scan so far behind that every header is still
//                                    needed: the batch is held (no disagreement, no penalty) and appends
//                                    once the scan has moved.
//   chain_unit_truncate_drop         Truncate / DropBelow keep the chain consistent: anchor, verify,
//                                    serialize round trip, refusals.
// The run.sh builds this file at a small limit (-DBR_COMPACT_FILTER_CHAIN_MAX=8192, every case) and at
// the real limit (limit_crossing_continues), in both word sizes.
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

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon "
                           "abandon abandon abandon about", NULL);
    return BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
}

static int g_fds[8]; static int g_nfds = 0;
static BRPeer *addPeer(BRPeerManager *m, uint8_t a)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[10] = 0xff; p->address.u8[11] = 0xff; p->address.u8[12] = 10; p->address.u8[15] = a;
    p->port = 12024;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0) {   // a live socket: the manager's sends go out
        ((BRPeerContext *)p)->socket = sv[0];
        if (g_nfds < 8) g_fds[g_nfds++] = sv[1];
    }
    array_add(m->connectedPeers, p);
    return p;
}
// Drain what the manager wrote to the peers, so a long run never blocks on a full socket buffer.
static void drainPeers(void)
{
    uint8_t buf[65536];
    for (int i = 0; i < g_nfds; i++) while (recv(g_fds[i], buf, sizeof(buf), MSG_DONTWAIT) > 0) {}
}

static UInt256 blockHashAt(uint32_t h)
{
    uint8_t buf[5] = { (uint8_t)h, (uint8_t)(h >> 8), (uint8_t)(h >> 16), (uint8_t)(h >> 24), 0x55 };
    UInt256 r; BRSHA256(&r, buf, sizeof(buf)); return r;
}
// Each block's filter: an empty BIP 158 filter (N = 0) followed by the block hash, so filters differ.
static void encodedAt(uint32_t h, uint8_t out[33]) { UInt256 b = blockHashAt(h); out[0] = 0x00; memcpy(out + 1, b.u8, 32); }
static UInt256 filterHashAt(uint32_t h) { uint8_t e[33]; encodedAt(h, e); UInt256 r; BRSHA256_2(&r, e, 33); return r; }

static uint32_t g_ts = 1790000000;
static BRMerkleBlock *mkBlock(uint32_t h, UInt256 prev)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->version = 0x20000000 | BLOCK_VERSION_SHA256D;
    b->prevBlock = prev;
    b->blockHash = blockHashAt(h);
    b->height = h;
    b->timestamp = g_ts += 15;
    b->target = 0x1a0fffff;
    return b;
}

// Append the honest headers for [from .. to] directly (fast path for the bulk of the chain).
static int appendRange(BRCompactFilterChain *c, uint32_t from, uint32_t to)
{
    static UInt256 hs[2000];
    for (uint32_t h = from; h <= to;) {
        uint32_t n = to - h + 1; if (n > 2000) n = 2000;
        for (uint32_t i = 0; i < n; i++) hs[i] = filterHashAt(h + i);
        if (! BRCompactFilterChainAppend(c, BRCompactFilterChainTipHeader(c), hs, n)) return 0;
        h += n;
    }
    return 1;
}

#define S_BIRTH 21400000u   // cf_birth_height of a wallet restored with birth 2025-06-01

// ---- a manager with a chain near its limit ----------------------------------------------------------
typedef struct {
    BRWallet *w; BRPeerManager *m; BRPeer *p[3]; BRPeerCallbackInfo inf[3];
    uint32_t lowB, top;            // resident blocks [lowB .. top]
    BRMerkleBlock **blk;
} Mx;

static void mxInit(Mx *x, uint32_t chainTip, uint32_t top)
{
    memset(x, 0, sizeof(*x));
    x->w = makeWallet();
    x->m = BRPeerManagerNew(&BRMainNetParams, x->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(x->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    for (int i = 0; i < 3; i++) {
        x->p[i] = addPeer(x->m, (uint8_t)(i + 1));
        x->inf[i] = (BRPeerCallbackInfo){ .peer = x->p[i], .manager = x->m, .hash = UINT256_ZERO };
    }
    x->lowB = chainTip - 400; x->top = top;
    x->blk = calloc(top - x->lowB + 1, sizeof(*x->blk));
    UInt256 prev = blockHashAt(x->lowB - 1);
    for (uint32_t h = x->lowB; h <= top; h++) {
        x->blk[h - x->lowB] = mkBlock(h, prev);
        BRSetAdd(x->m->blocks, x->blk[h - x->lowB]);
        prev = x->blk[h - x->lowB]->blockHash;
    }
    x->m->lastBlock = x->blk[top - x->lowB];
    x->m->estimatedHeight = top;

    UInt256 anchor; memset(anchor.u8, 0xB0, 32);
    x->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, S_BIRTH, anchor);
    if (! appendRange(x->m->compactFilterChain, S_BIRTH, chainTip)) { printf("setup append failed\n"); exit(3); }
    x->m->autoFetchCFiltersEnabled = 1;
}
static void mxFree(Mx *x)
{
    BRPeerManagerFree(x->m);
    BRWalletFree(x->w);
    free(x->blk);
    for (int i = 0; i < g_nfds; i++) close(g_fds[i]);
    g_nfds = 0;
}
// The ledger has scanned [from .. through].
static void scanned(BRPeerManager *m, uint32_t from, uint32_t through)
{
    for (uint32_t h = from; h <= through;) {
        uint32_t e = h + 999; if (e > through) e = through;
        BRCFScanLedgerRecordRequested(&m->cfLedger, h, e, UINT128_ZERO, 0, 1);
        for (uint32_t k = h; k <= e; k++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, k);
        h = e + 1;
    }
}
// Peers answer every outstanding getcfilters with the honest filter.
static void serveOutstanding(Mx *x, int via)
{
    static uint32_t hs[CF_OUTSTANDING_MAX];
    size_t n = x->m->cfLedger.outstandingCount;
    for (size_t k = 0; k < n; k++) hs[k] = x->m->cfLedger.outstanding[k].height;
    for (size_t k = 0; k < n; k++) {
        uint8_t e[33]; encodedAt(hs[k], e);
        _peerRelayedCFilter(&x->inf[via], FILTER_TYPE_BASIC, blockHashAt(hs[k]), e, sizeof(e));
    }
    drainPeers();
}
static void sendBatch(Mx *x, int via, uint32_t from, uint32_t to, UInt256 prev)
{
    static UInt256 hs[2000];
    for (uint32_t h = from; h <= to; h++) hs[h - from] = filterHashAt(h);
    _peerRelayedCFHeaders(&x->inf[via], FILTER_TYPE_BASIC, blockHashAt(to), prev, hs, to - from + 1);
    drainPeers();
}
static UInt256 fold(UInt256 prev, uint32_t from, uint32_t to)
{
    for (uint32_t h = from; h <= to; h++) prev = BRGCSFilterHeader(filterHashAt(h), prev);
    return prev;
}

// ---- cases --------------------------------------------------------------------------------------------

static void case_append_limit_distinct(void)
{
    UInt256 anchor; memset(anchor.u8, 0xB1, 32);
    BRCompactFilterChain *c = BRCompactFilterChainNew(FILTER_TYPE_BASIC, S_BIRTH, anchor);
    check(appendRange(c, S_BIRTH, S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX - 2), "setup: MAX - 1 headers appended");
    UInt256 two[2] = { filterHashAt(S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX - 1), filterHashAt(S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX) };
    UInt256 tip = BRCompactFilterChainTipHeader(c), wrong; memset(wrong.u8, 0x01, 32);
    check(BRCompactFilterChainAppendEx(c, tip, two, 2) == BR_CF_APPEND_LIMIT,
          "a batch that continues the chain but does not fit is reported as the size limit");
    check(BRCompactFilterChainAppendEx(c, wrong, two, 2) == BR_CF_APPEND_MISMATCH,
          "a batch that does not continue the chain is a mismatch, limit or not (continuity first)");
    check(BRCompactFilterChainAppend(c, tip, two, 2) == 0, "Append keeps its 0/1 contract at the limit");
    check(BRCompactFilterChainAppendEx(c, tip, two, 1) == BR_CF_APPEND_OK &&
          BRCompactFilterChainCount(c) == BR_COMPACT_FILTER_CHAIN_MAX, "the last header that fits appends");
    BRCompactFilterChainFree(c);
}

static void case_limit_crossing_continues(void)
{
    // Three batches of 1000: the first crosses the limit, two more show the chain keeps going. At the
    // real limit this ends at 23,499,651, short of the compiled checkpoint at 23,500,000 (whose pinned
    // header this synthetic chain cannot match).
    const uint32_t BATCH = 1000, NBATCH = 3;
    const uint32_t preTip = S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX - BATCH / 2 - 1;  // the next batch crosses
    const uint32_t target = preTip + NBATCH * BATCH;
    printf("  limit %u: chain [%u..%u], last height that fits %u, target %u\n",
           (unsigned)BR_COMPACT_FILTER_CHAIN_MAX, S_BIRTH, preTip, S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX - 1, target);
    Mx x; mxInit(&x, preTip, target);
    BRPeerManager *m = x.m;
    // honest headers below the scan frontier, kept to check the anchor after the trim
    UInt256 ref[301];
    for (uint32_t h = preTip - 300; h <= preTip; h++) ref[h - (preTip - 300)] = BRCompactFilterChainHeader(m->compactFilterChain, h);
    // the scan has kept pace: it covers everything below preTip + 1
    BRCFScanLedgerInit(&m->cfLedger, preTip - 299);
    scanned(m, preTip - 299, preTip);
    m->autoFetchCFiltersStart = preTip - 299;
    m->autoFetchCFiltersThrough = preTip;

    int mb = m->misbehavinCount;
    UInt256 refTip = ref[300];
    for (uint32_t k = 0; k < NBATCH; k++) {
        uint32_t from = preTip + 1 + k * BATCH, to = from + BATCH - 1;
        sendBatch(&x, (int)(k % 3), from, to, refTip);
        refTip = fold(refTip, from, to);
        serveOutstanding(&x, (int)((k + 1) % 3));
    }
    BRCompactFilterChain *c = m->compactFilterChain;
    uint32_t start = BRCompactFilterChainStartHeight(c);
    printf("  after: chain [%u..%u] (%zu headers), scannedThrough %u\n", start,
           BRCompactFilterChainNextHeight(c) - 1, BRCompactFilterChainCount(c), BRCFScanLedgerScannedThrough(&m->cfLedger));
    check(c != NULL && BRCompactFilterChainNextHeight(c) == target + 1 && UInt256Eq(BRCompactFilterChainTipHeader(c), refTip),
          "the chain continues past start + MAX to the target, on the honest headers");
    check(start > S_BIRTH && start <= preTip + 1 && start - 1 >= preTip - 300 &&
          UInt256Eq(BRCompactFilterChainHeader(c, start - 1), ref[start - 1 - (preTip - 300)]),
          "it kept only what the scan needs, anchored at the honest header just below its new start");
    check(m->cfDisagreedCount == 0 && m->cfReanchorCount == 0, "reaching the limit recorded no disagreement and no re-anchor");
    check(m->misbehavinCount == mb, "no peer closed as misbehaving");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == target, "the scan reaches the target");
    mxFree(&x);
}

static void case_limit_held_while_scan_needs_all(void)
{
    const uint32_t BATCH = 1000;
    const uint32_t preTip = S_BIRTH + BR_COMPACT_FILTER_CHAIN_MAX - BATCH / 2 - 1;
    Mx x; mxInit(&x, preTip, preTip + 2 * BATCH);
    BRPeerManager *m = x.m;
    // the scan is still at the very start of the chain: every header is still needed
    BRCFScanLedgerInit(&m->cfLedger, S_BIRTH);
    scanned(m, S_BIRTH, S_BIRTH + 99);
    m->autoFetchCFiltersStart = S_BIRTH;
    m->autoFetchCFiltersThrough = S_BIRTH + 99;
    UInt256 tip = BRCompactFilterChainTipHeader(m->compactFilterChain);
    int mb = m->misbehavinCount;
    sendBatch(&x, 0, preTip + 1, preTip + BATCH, tip);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) == preTip + 1 &&
          BRCompactFilterChainStartHeight(m->compactFilterChain) == S_BIRTH,
          "with every header still needed the batch is held, the chain untouched");
    check(m->cfDisagreedCount == 0 && m->cfReanchorCount == 0 && m->misbehavinCount == mb &&
          m->cfHeadersRequestedThrough == 0,
          "...and that is not a disagreement, not a penalty; the batch will be asked for again");
    // the scan moves on; the same batch now appends
    scanned(m, S_BIRTH + 100, preTip);
    m->autoFetchCFiltersThrough = preTip;
    sendBatch(&x, 1, preTip + 1, preTip + BATCH, tip);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) == preTip + BATCH + 1 &&
          m->cfDisagreedCount == 0 && m->misbehavinCount == mb,
          "once the scan has moved, the batch appends");
    mxFree(&x);
}

static void case_chain_unit_truncate_drop(void)
{
    UInt256 anchor; memset(anchor.u8, 0xA7, 32);
    BRCompactFilterChain *c = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 1000, anchor);
    check(appendRange(c, 1000, 1009), "setup: [1000..1009]");
    UInt256 h1004 = BRCompactFilterChainHeader(c, 1004), h1003 = BRCompactFilterChainHeader(c, 1003);
    check(BRCompactFilterChainTruncate(c, 1005) == 1 && BRCompactFilterChainNextHeight(c) == 1005 &&
          UInt256Eq(BRCompactFilterChainTipHeader(c), h1004), "Truncate(1005): the chain ends at 1004");
    check(BRCompactFilterChainTruncate(c, 1006) == 0 && BRCompactFilterChainNextHeight(c) == 1005,
          "Truncate above the tip is refused");
    check(BRCompactFilterChainTruncate(c, 999) == 0 && BRCompactFilterChainNextHeight(c) == 1005,
          "Truncate below the start (the anchor's own block replaced) is refused");
    check(BRCompactFilterChainTruncate(c, 1005) == 1, "Truncate to the current next height is a no-op success");
    check(BRCompactFilterChainTruncate(c, 1000) == 1 && BRCompactFilterChainCount(c) == 0 &&
          UInt256Eq(BRCompactFilterChainTipHeader(c), anchor), "Truncate to the start leaves the anchor as the tip");
    check(appendRange(c, 1000, 1009), "the truncated chain appends again from its tip");

    check(BRCompactFilterChainDropBelow(c, 1004) == 4 && BRCompactFilterChainStartHeight(c) == 1004 &&
          BRCompactFilterChainNextHeight(c) == 1010 && UInt256Eq(BRCompactFilterChainHeader(c, 1003), h1003) &&
          UInt256Eq(BRCompactFilterChainHeader(c, 1004), h1004),
          "DropBelow(1004): start 1004, the header at 1003 is the anchor, the rest unchanged");
    uint8_t e[33]; encodedAt(1004, e);
    check(BRCompactFilterChainVerifyFilter(c, 1004, e, sizeof(e)) == 1, "a filter at the new start verifies against the anchor");
    check(BRCompactFilterChainDropBelow(c, 1004) == 0 && BRCompactFilterChainDropBelow(c, 1011) == 0,
          "DropBelow at/below the start or above the next height is refused");
    size_t len = BRCompactFilterChainSerialize(c, NULL, 0);
    uint8_t *buf = malloc(len);
    BRCompactFilterChainSerialize(c, buf, len);
    BRCompactFilterChain *d = BRCompactFilterChainDeserialize(buf, len);
    check(d && BRCompactFilterChainStartHeight(d) == 1004 && BRCompactFilterChainNextHeight(d) == 1010 &&
          UInt256Eq(BRCompactFilterChainHeader(d, 1003), h1003) &&
          UInt256Eq(BRCompactFilterChainTipHeader(d), BRCompactFilterChainTipHeader(c)),
          "a trimmed chain survives serialize / deserialize");
    check(BRCompactFilterChainDropBelow(c, 1010) == 6 && BRCompactFilterChainCount(c) == 0 &&
          UInt256Eq(BRCompactFilterChainTipHeader(c), BRCompactFilterChainTipHeader(d)),
          "DropBelow(next) keeps only the anchor, which is the old tip");
    free(buf);
    BRCompactFilterChainFree(d);
    BRCompactFilterChainFree(c);
}

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "append_limit_distinct",           case_append_limit_distinct },
    { "limit_crossing_continues",        case_limit_crossing_continues },
    { "limit_held_while_scan_needs_all", case_limit_held_while_scan_needs_all },
    { "chain_unit_truncate_drop",        case_chain_unit_truncate_drop },
};

int main(int argc, char **argv)
{
    if (argc < 2) {
        for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) printf("%s\n", CASES[i].name);
        return 2;
    }
    for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("case %s (BR_COMPACT_FILTER_CHAIN_MAX %u)\n", CASES[i].name, (unsigned)BR_COMPACT_FILTER_CHAIN_MAX);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail ? "FAIL" : "PASS");
        return g_fail ? 1 : 0;
    }
    printf("unknown case %s\n", argv[1]);
    return 2;
}
