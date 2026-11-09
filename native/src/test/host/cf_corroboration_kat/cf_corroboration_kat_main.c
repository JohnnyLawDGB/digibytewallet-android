// Host KAT: second-source corroboration of the filter-header chain (observe-only).
//
// INVARIANT. On a filter-capable connect the manager asks the peer for its
// filter-header checkpoints (getcfcheckpt, stop = our best block) right after the
// first getcfheaders. The reply is compared with OUR chain at every 1000-multiple
// above the top compiled checkpoint:
//   * a match from a peer whose address differs from the current cfheaders source
//     raises cfCorroboratedThrough (never lowers it);
//   * a mismatch raises cfCheckptDisagreeCount and is logged once;
//   * the cfheaders source's own answer never counts as corroboration;
//   * the request stops at a 1000-multiple the peer holds (at or below the tip it
//     announced, less a margin) and is not sent when we hold no block there or when
//     the reply would exceed the entry count the parser accepts;
//   * a reply longer than the 1000-multiples at or below the stop height, or with
//     a stop block we do not hold, is refused whole;
//   * NOTHING ELSE MOVES: the chain, its anchor, the re-anchor counter, the
//     disagreer set, the peer's connect status and the misbehaving count are all
//     untouched by any of the above -- EXCEPT (4.1.3 G5) when a strict majority of
//     connected filter peers, at least two and none the source, report the same value
//     at the same height: then the source is banned and the chain rebuilt
//     (test_majority_disagrees; -DCF_CORROBORATE_ACT_UNFIXED keeps it observe-only).
//   * G5-QUORUM: that majority is taken over the non-source peers that ANSWERED (distinct
//     address and port, none at the source's address), at least CF_CKPT_QUORUM_MIN of them
//     reporting the same value at the same height. Silent peers and the source's address
//     count on neither side, nor do peers that have since disconnected; the agreeing
//     dissenters must span CF_CKPT_QUORUM_MIN distinct addresses (votes are per address and
//     port, but many ports on one host are one host's word); one replier never rebuilds; a
//     peer's later agreeing reply
//     withdraws its dispute (test_g5_*; -DCF_CKPT_QUORUM_UNFIXED restores the
//     every-connected-peer denominator).
//   * G5-QUORUM: checking is continuous. Each filter peer not at the source's address is
//     asked again once the stop crosses past the 1000-multiple it was last checked through
//     and our filter headers cover it -- once per crossing, never while they trail
//     (test_g5_recheck_cadence; -DCF_CKPT_RECHECK_UNFIXED restores ask-once-per-connect).
//     The re-check is one single-height getcfheaders (start h, stop = our block at h), tagged
//     per peer: its answer folds to the peer's header at h, is judged with the same (height,
//     value) semantics as a cfcheckpt entry, and is never appended or taken for a sync batch
//     (-DCF_CKPT_PROBE_UNFIXED re-checks with a full cfcheckpt instead).
//
// Rig: same "#include a .c for its statics" idiom as cf_checkpoint_quorum_kat --
// BRPeer.c (for the private BRPeerContext: connect status, and the real
// BRPeerSendGetCFCheckpt whose socket is -1 so the send safely no-ops) and
// BRPeerManager.c (for _peerRelayedCFCheckpt, the connect hook and the manager
// struct). The two request senders the connect hook calls are intercepted with a
// per-TU rename scoped to BRPeerManager.c's #include only (BRPeer.c keeps the real
// definitions, which the interceptors call through to), so the KAT can see WHAT
// was requested and in WHICH order.
//
// ==== RED-BEFORE-GREEN GATE ====
// main() runs every test with NO #ifdef branching; every check asserts the
// corroborating shape. run.sh builds this file twice:
//   RED   -DCF_CORROBORATION_UNFIXED restores the pre-corroboration shape in
//         BRPeerManager.c: the handler only logs, the connect hook sends no
//         getcfcheckpt. test_request_sent_on_connect and test_agree FAIL there
//         (no request; cfCorroboratedThrough never moves), and so does
//         test_disagree's counter check.
//   GREEN the production shape must pass every check and exit 0.
// The never / same-address / bounded cases assert that the fields stay put, and
// request_gated_at_reply_cap asserts that no request goes out; they are GUARD
// cases (identical outcome in both arms) and are labelled so in run.sh.
//
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

// Interceptors for the two requests the connect hook issues. Declared before the
// BRPeerManager.c include so the renamed call sites resolve to them; defined below.
static void kat_BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash);
static void kat_BRPeerSendGetCFCheckpt(BRPeer *peer, uint8_t filterType, UInt256 stopHash);
#define BRPeerSendGetCFHeaders kat_BRPeerSendGetCFHeaders
#define BRPeerSendGetCFCheckpt kat_BRPeerSendGetCFCheckpt
#include "BRPeerManager.c"
#undef BRPeerSendGetCFHeaders
#undef BRPeerSendGetCFCheckpt

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// ---- request log -----------------------------------------------------------
static char     g_seq[16];        // 'H' = getcfheaders, 'C' = getcfcheckpt, in send order
static size_t   g_seqLen = 0;
static BRPeer  *g_checkptPeer = NULL;
static uint8_t  g_checkptType = 0xFF;
static UInt256  g_checkptStop;
static unsigned g_checkptCount = 0;

// G5-QUORUM: every getcfheaders sent, so a test can tell a single-height re-check probe (start == stop height;
// blockHashFor encodes the height) from a sync batch.
#define KAT_HDR_LOG 64
static BRPeer  *g_hdrPeer[KAT_HDR_LOG];
static uint32_t g_hdrStart[KAT_HDR_LOG];
static UInt256  g_hdrStop[KAT_HDR_LOG];
static size_t   g_hdrCount = 0;

static void seqPush(char c) { if (g_seqLen + 1 < sizeof(g_seq)) g_seq[g_seqLen++] = c; g_seq[g_seqLen] = 0; }
static void seqReset(void) { g_hdrCount = 0; g_seqLen = 0; g_seq[0] = 0; g_checkptPeer = NULL; g_checkptType = 0xFF; g_checkptStop = UINT256_ZERO; g_checkptCount = 0; }

static void kat_BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    seqPush('H');
    if (g_hdrCount < KAT_HDR_LOG) {
        g_hdrPeer[g_hdrCount] = peer; g_hdrStart[g_hdrCount] = startHeight; g_hdrStop[g_hdrCount] = stopHash; g_hdrCount++;
    }
    BRPeerSendGetCFHeaders(peer, filterType, startHeight, stopHash);   // real sender, socket -1: no-op
}

static void kat_BRPeerSendGetCFCheckpt(BRPeer *peer, uint8_t filterType, UInt256 stopHash)
{
    seqPush('C');
    g_checkptPeer = peer; g_checkptType = filterType; g_checkptStop = stopHash; g_checkptCount++;
    BRPeerSendGetCFCheckpt(peer, filterType, stopHash);                // real sender, socket -1: no-op
}

// ---- rig -------------------------------------------------------------------
static UInt256 u256_fill(uint8_t b) { UInt256 h; memset(h.u8, b, sizeof(h.u8)); return h; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    return BRWalletNew(NULL, 0, mpk);
}

static void assertLockNotHeld(BRPeerManager *manager, const char *ctx)
{
    int r = pthread_mutex_trylock(&manager->lock);
    check(r == 0, ctx);
    if (r == 0) pthread_mutex_unlock(&manager->lock);
}

static BRMerkleBlock *dummyBlock(uint32_t height, uint8_t hashSeed)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    return b;
}

static BRPeer *addConnectedFilterPeer(BRPeerManager *manager, uint8_t addrByte, uint16_t port, uint32_t lastblock)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    // IPv4-mapped, one /16 per addrByte: each addrByte is its own host (network group), as on the real network.
    p->address.u16[5] = 0xffff;
    p->address.u8[12] = addrByte;
    p->address.u8[15] = addrByte;
    p->port = port;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    ((BRPeerContext *)p)->lastblock = lastblock;   // the tip the peer announced at handshake
    array_add(manager->connectedPeers, p);
    return p;
}

static void setPeerLastBlock(BRPeer *p, uint32_t lastblock) { ((BRPeerContext *)p)->lastblock = lastblock; }

// Deterministic hash for a synthetic block at `height` (so a test can name the
// block it expects a request to stop at without walking the manager's set).
static UInt256 blockHashFor(uint32_t height)
{
    UInt256 h; memset(h.u8, 0, sizeof(h.u8));
    h.u32[0] = height; h.u32[1] = 0xB10C0000u;
    return h;
}

// Adds blocks [from..to] to manager->blocks linked through prevBlock (the one at
// `from` points at `prevOfFrom`, zero for an unlinked base) and returns the top.
// The stop-hash resolver walks prevBlock links down from lastBlock, so a stop
// below `from` is "not held".
static BRMerkleBlock *linkBlocks(BRPeerManager *manager, uint32_t from, uint32_t to, UInt256 prevOfFrom)
{
    BRMerkleBlock *top = NULL;
    UInt256 prev = prevOfFrom;
    for (uint32_t h = from; h <= to; h++) {
        BRMerkleBlock *b = BRMerkleBlockNew();
        b->blockHash = blockHashFor(h);
        b->prevBlock = prev;
        b->height = h;
        BRSetAdd(manager->blocks, b);
        prev = b->blockHash;
        top = b;
    }
    return top;
}

// The stop the hook must request: highest 1000-multiple at or below
// min(our tip, the peer's announced tip) - CF_CORROBORATE_STOP_MARGIN.
static uint32_t expectedStop(uint32_t ours, uint32_t peers)
{
    uint32_t c = ours < peers ? ours : peers;
    return c > CF_CORROBORATE_STOP_MARGIN ? ((c - CF_CORROBORATE_STOP_MARGIN) / 1000u) * 1000u : 0;
}

typedef struct {
    BRWallet      *wallet;
    BRPeerManager *manager;
    BRMerkleBlock *tipBlock;
    uint32_t       topCompiled;   // highest compiled checkpoint height (from the real table)
    uint32_t       start;         // chain start = topCompiled + 1
    uint32_t       tip;           // chain tip = topCompiled + headersAboveTop
    BRPeer        *source;        // the peer whose cfheaders "built" the chain (cfHeadersPeerAddr)
    BRPeer        *other;         // a second filter peer at a different address
} Rig;

// Chain anchored just above the top compiled checkpoint (read from the real
// table), filled with `headersAboveTop` synthetic filter hashes; best block at the
// chain tip; two connected filter peers, one recorded as the cfheaders source.
static void rigInit(Rig *r, uint32_t headersAboveTop)
{
    memset(r, 0, sizeof(*r));
    r->wallet = makeWallet();
    r->manager = BRPeerManagerNew(&BRMainNetParams, r->wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(r->manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    r->topCompiled = BRCFTopCheckpointHeight();
    r->start = r->topCompiled + 1;
    r->tip = r->topCompiled + headersAboveTop;
    check(r->tip / 1000u > r->topCompiled / 1000u, "setup: the chain reaches at least one 1000-multiple above the compiled table");

    // Held blocks: a linked run from 2,500 below the tip up to the tip, so stops down
    // to tip-2000 resolve and tip-3000 does not.
    r->tipBlock = linkBlocks(r->manager, r->tip - 2500, r->tip, UINT256_ZERO);
    r->manager->lastBlock = r->tipBlock;

    UInt256 anchor = u256_fill(0xA1);
    r->manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, r->start, anchor);
    UInt256 *hashes = malloc(headersAboveTop * sizeof(UInt256));
    for (uint32_t i = 0; i < headersAboveTop; i++) {
        memset(hashes[i].u8, 0, sizeof(hashes[i].u8));
        hashes[i].u32[0] = i + 1;
    }
    check(BRCompactFilterChainAppend(r->manager->compactFilterChain, anchor, hashes, headersAboveTop) == 1,
          "setup: priming append above the top checkpoint succeeds");
    free(hashes);
    check(BRCompactFilterChainNextHeight(r->manager->compactFilterChain) == r->tip + 1, "setup: chain tip is where the rig put it");

    r->source = addConnectedFilterPeer(r->manager, 0x01, 10001, r->tip);
    r->other  = addConnectedFilterPeer(r->manager, 0x02, 10002, r->tip);
    r->manager->cfHeadersPeerAddr = r->source->address;
    seqReset();
}

static void rigFree(Rig *r)
{
    BRPeerManagerFree(r->manager);
    BRWalletFree(r->wallet);
}

// A cfcheckpt reply for stop height `stopHeight`: count = stopHeight/1000 entries,
// entry i = header at height (i+1)*1000 -- our own chain's header where the chain
// covers it, zero elsewhere (below the compiled table the handler must not look).
static UInt256 *buildReply(const Rig *r, uint32_t stopHeight, size_t *outCount)
{
    size_t count = stopHeight / 1000u;
    UInt256 *reply = calloc(count, sizeof(UInt256));
    for (size_t i = 0; i < count; i++) {
        uint32_t h = (uint32_t)(i + 1) * 1000u;
        if (h >= r->start && h <= r->tip) reply[i] = BRCompactFilterChainHeader(r->manager->compactFilterChain, h);
    }
    *outCount = count;
    return reply;
}

typedef struct {
    const BRCompactFilterChain *chain; size_t count; uint32_t start; UInt256 anchor, tipHeader;
    uint8_t reanchors, disagreers, misbehavin; uint32_t autoStart, requestedThrough; UInt128 sourceAddr;
} Snapshot;

static Snapshot snap(const Rig *r)
{
    Snapshot s;
    s.chain = r->manager->compactFilterChain;
    s.count = BRCompactFilterChainCount(s.chain);
    s.start = BRCompactFilterChainStartHeight(s.chain);
    s.anchor = BRCompactFilterChainHeader(s.chain, s.start - 1);
    s.tipHeader = BRCompactFilterChainTipHeader(s.chain);
    s.reanchors = r->manager->cfReanchorCount;
    s.disagreers = r->manager->cfDisagreedCount;
    s.misbehavin = (uint8_t)r->manager->misbehavinCount;
    s.autoStart = r->manager->autoFetchCFiltersStart;
    s.requestedThrough = r->manager->cfHeadersRequestedThrough;
    s.sourceAddr = r->manager->cfHeadersPeerAddr;
    return s;
}

static void checkNothingElseMoved(const Rig *r, const Snapshot *before, const char *tag)
{
    Snapshot after = snap(r);
    char buf[160];
    snprintf(buf, sizeof(buf), "%s: chain object, count and start untouched", tag);
    check(after.chain == before->chain && after.count == before->count && after.start == before->start, buf);
    snprintf(buf, sizeof(buf), "%s: anchor and tip header untouched", tag);
    check(UInt256Eq(after.anchor, before->anchor) && UInt256Eq(after.tipHeader, before->tipHeader), buf);
    snprintf(buf, sizeof(buf), "%s: no re-anchor, no disagreer recorded, nobody marked misbehaving", tag);
    check(after.reanchors == before->reanchors && after.disagreers == before->disagreers &&
          after.misbehavin == before->misbehavin, buf);
    snprintf(buf, sizeof(buf), "%s: auto-fetch start, in-flight marker and cfheaders source untouched", tag);
    check(after.autoStart == before->autoStart && after.requestedThrough == before->requestedThrough &&
          UInt128Eq(after.sourceAddr, before->sourceAddr), buf);
    snprintf(buf, sizeof(buf), "%s: both peers still connected", tag);
    check(BRPeerConnectStatus(r->source) == BRPeerStatusConnected &&
          BRPeerConnectStatus(r->other) == BRPeerStatusConnected, buf);
}

static uint32_t through(const Rig *r) { return BRPeerManagerCFCorroboratedThrough(r->manager); }
static uint32_t disagreed(const Rig *r) { return BRPeerManagerCFCheckptDisagreeCount(r->manager); }

static void relay(Rig *r, BRPeer *from, uint8_t type, UInt256 stop, const UInt256 *headers, size_t count)
{
    BRPeerCallbackInfo info = { .peer = from, .manager = r->manager, .hash = UINT256_ZERO };
    _peerRelayedCFCheckpt(&info, type, stop, headers, count);
}

// ---- tests -----------------------------------------------------------------

// RED/GREEN: the connect hook sends exactly one getcfcheckpt, after the first
// getcfheaders, for our filter type, with a stop the peer holds: the highest
// 1000-multiple at or below min(our tip, the peer's announced tip) minus the margin.
static void test_request_sent_on_connect(void)
{
    Rig r; rigInit(&r, 3000);
    // Give the cfheaders driver something to request so the ORDER is observable:
    // best block 2000 above the chain tip (linked onto the held run), peer level with us.
    BRMerkleBlock *higher = linkBlocks(r.manager, r.tip + 1, r.tip + 2000, r.tipBlock->blockHash);
    r.manager->lastBlock = higher;
    setPeerLastBlock(r.other, higher->height);
    r.manager->cfHeadersRequestedThrough = 0;
    BRPeerCallbackInfo info = { .peer = r.other, .manager = r.manager, .hash = UINT256_ZERO };

    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);

    uint32_t want = expectedStop(higher->height, higher->height);
    check(g_checkptCount == 1, "connect: exactly one getcfcheckpt is sent on a filter-capable connect");
    check(strcmp(g_seq, "HC") == 0, "connect: the getcfcheckpt follows the first getcfheaders");
    check(g_checkptPeer == r.other, "connect: the request goes to the peer that connected");
    check(g_checkptType == FILTER_TYPE_BASIC, "connect: the request names our chain's filter type");
    check(want == higher->height - 1000 && UInt256Eq(g_checkptStop, blockHashFor(want)),
          "connect: the stop is the highest 1000-multiple at or below our tip less the margin (one step below a 1000-multiple tip)");
    check(through(&r) == 0 && disagreed(&r) == 0, "connect: sending a request moves neither counter");
    assertLockNotHeld(r.manager, "connect: manager->lock released by the hook's caller");

    // A synced wallet (nothing for cfheaders to ask) still asks for the checkpoints.
    seqReset();
    r.manager->lastBlock = r.tipBlock;
    setPeerLastBlock(r.other, r.tip);
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(strcmp(g_seq, "C") == 0, "connect: with nothing to fetch, only the getcfcheckpt goes out");
    check(UInt256Eq(g_checkptStop, blockHashFor(expectedStop(r.tip, r.tip))), "connect: the stop follows the best block");

    // The peer announced a tip BELOW ours (admitted by the connect gate): the stop
    // clamps to a 1000-multiple at or below the PEER's tip, never above it.
    seqReset();
    setPeerLastBlock(r.other, r.tip - 1500);
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    want = expectedStop(r.tip, r.tip - 1500);
    check(g_checkptCount == 1 && want == r.tip - 2000 && want <= r.tip - 1500,
          "connect/peer-below: the stop height is at or below the peer's announced tip");
    check(UInt256Eq(g_checkptStop, blockHashFor(want)), "connect/peer-below: the stop hash is our held block at that height");

    // Nothing qualifies: the clamped stop falls below the blocks we hold -> no request.
    seqReset();
    setPeerLastBlock(r.other, r.tip - 2700);   // stop would be tip-3000, below the held run
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(g_checkptCount == 0, "connect/no-held-block: no getcfcheckpt when the stop is below the blocks we hold");

    seqReset();
    setPeerLastBlock(r.other, 0);              // peer announced nothing
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(g_checkptCount == 0, "connect/peer-at-zero: no getcfcheckpt when the peer announced no height");
    check(through(&r) == 0 && disagreed(&r) == 0, "connect: none of the above moved a counter");
    assertLockNotHeld(r.manager, "connect: manager->lock released after the skipped requests");

    rigFree(&r);
    printf("test_request_sent_on_connect: done\n");
}

// GUARD: the request is not sent once the reply would carry more entries than the
// parser accepts (MAX_CFCHECKPT_RESULTS) -- we must never solicit a reply we would
// refuse. At exactly the cap it still goes out.
static void test_request_gated_at_reply_cap(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeerCallbackInfo info = { .peer = r.other, .manager = r.manager, .hash = UINT256_ZERO };
    uint32_t capHeight = (uint32_t)MAX_CFCHECKPT_RESULTS * 1000u;   // 30,000,000

    // Tip one 1000-step past the cap: stop would be capHeight+1000 -> 30,001 entries -> no request.
    BRMerkleBlock *over = linkBlocks(r.manager, capHeight + 1000, capHeight + 1000 + CF_CORROBORATE_STOP_MARGIN, UINT256_ZERO);
    r.manager->lastBlock = over;
    setPeerLastBlock(r.other, over->height);
    seqReset();
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(g_checkptCount == 0, "cap: no getcfcheckpt when the stop would need more entries than MAX_CFCHECKPT_RESULTS");

    // Tip exactly at the cap (plus the margin): stop = capHeight -> 30,000 entries -> allowed.
    BRMerkleBlock *at = linkBlocks(r.manager, capHeight, capHeight + CF_CORROBORATE_STOP_MARGIN, UINT256_ZERO);
    r.manager->lastBlock = at;
    setPeerLastBlock(r.other, at->height);
    seqReset();
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(g_checkptCount == 1 && UInt256Eq(g_checkptStop, blockHashFor(capHeight)),
          "cap: at exactly MAX_CFCHECKPT_RESULTS entries the request still goes out");
    check(through(&r) == 0 && disagreed(&r) == 0, "cap: neither counter moved");
    assertLockNotHeld(r.manager, "cap: manager->lock released");

    rigFree(&r);
    printf("test_request_gated_at_reply_cap: done\n");
}

// RED/GREEN: a second address agreeing with our chain raises cfCorroboratedThrough
// to the highest matched 1000-multiple above the compiled table.
static void test_agree(void)
{
    Rig r; rigInit(&r, 3000);
    Snapshot before = snap(&r);
    size_t n; UInt256 *reply = buildReply(&r, r.tip, &n);
    check(n == r.tip / 1000u, "agree: the reply carries one header per 1000-multiple at or below the stop");

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);

    check(through(&r) == r.tip, "agree: cfCorroboratedThrough advances to the highest matched 1000-multiple above the compiled table");
    check(disagreed(&r) == 0, "agree: no disagreement recorded");
    checkNothingElseMoved(&r, &before, "agree");
    assertLockNotHeld(r.manager, "agree: manager->lock released");

    // A later, shorter agreeing reply never lowers it.
    BRMerkleBlock *lower = dummyBlock(r.topCompiled + 1500, 0xC2);
    BRSetAdd(r.manager->blocks, lower);
    size_t n2; UInt256 *reply2 = buildReply(&r, lower->height, &n2);
    relay(&r, r.other, FILTER_TYPE_BASIC, lower->blockHash, reply2, n2);
    check(through(&r) == r.tip, "agree: a shorter agreeing reply does not lower cfCorroboratedThrough");
    check(disagreed(&r) == 0, "agree: still no disagreement");

    free(reply); free(reply2);
    rigFree(&r);
    printf("test_agree: done\n");
}

// RED/GREEN (counter) + GREEN (nothing destructive): one header off at the
// second 1000-multiple above the table.
static void test_disagree(void)
{
    Rig r; rigInit(&r, 3000);
    Snapshot before = snap(&r);
    size_t n; UInt256 *reply = buildReply(&r, r.tip, &n);
    uint32_t wrongAt = r.topCompiled + 2000;
    reply[wrongAt / 1000u - 1] = u256_fill(0xEE);

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);

    check(disagreed(&r) == 1, "disagree: cfCheckptDisagreeCount increments once for the mismatching reply");
    check(through(&r) == r.topCompiled + 1000, "disagree: corroboration covers only the agreeing prefix below the mismatch");
    checkNothingElseMoved(&r, &before, "disagree");
    assertLockNotHeld(r.manager, "disagree: manager->lock released");

    free(reply);
    rigFree(&r);
    printf("test_disagree: done\n");
}

// RED/GREEN (4.1.3 G5): two of three connected filter peers -- neither the source -- report the same value at the
// same height where our chain differs. Our chain is the outlier: the source is marked misbehaving, the chain is
// dropped for a rebuild from below the disagreement, the re-anchor budget is charged, and the window is cleared.
static void test_majority_disagrees(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    uint8_t reanchorsBefore = r.manager->cfReanchorCount;
    unsigned misbehavinBefore = r.manager->misbehavinCount;
    size_t n; UInt256 *reply = buildReply(&r, r.tip, &n);
    uint32_t wrongAt = r.topCompiled + 2000;
    reply[wrongAt / 1000u - 1] = u256_fill(0xEE);

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);
    check(r.manager->compactFilterChain != NULL && r.manager->cfReanchorCount == reanchorsBefore,
          "majority: one disagreeing peer of three changes nothing");
    relay(&r, third, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);

    check(r.manager->cfReanchorCount == reanchorsBefore + 1, "majority: the re-anchor budget is charged once");
    check(r.manager->compactFilterChain == NULL, "majority: the chain is rebuilt (the source's chain is dropped)");
    check(r.manager->misbehavinCount == misbehavinBefore + 1, "majority: the cfheaders source is marked misbehaving");
    check(r.manager->autoFetchCFiltersStart <= wrongAt && r.manager->autoFetchCFiltersStart >= r.start,
          "majority: the rebuild starts at or below the disagreeing height, never below the old chain's start");
    check(_cfCkDisputeCount(r.manager) == 0, "majority: the disagreement window is cleared");
    assertLockNotHeld(r.manager, "majority: manager->lock released");

    free(reply);
    rigFree(&r);
    printf("test_majority_disagrees: done\n");
}

// GUARD: two peers disagree with us but not with each other (different values): no majority, nothing moves.
static void test_split_disagreement(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    uint8_t reanchorsBefore = r.manager->cfReanchorCount;
    size_t n; UInt256 *a = buildReply(&r, r.tip, &n), *b = buildReply(&r, r.tip, &n);
    uint32_t wrongAt = r.topCompiled + 2000;
    a[wrongAt / 1000u - 1] = u256_fill(0xEE);
    b[wrongAt / 1000u - 1] = u256_fill(0xDD);

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, a, n);
    relay(&r, third, FILTER_TYPE_BASIC, r.tipBlock->blockHash, b, n);

    check(r.manager->compactFilterChain != NULL && r.manager->cfReanchorCount == reanchorsBefore,
          "split: disagreers that do not agree with each other change nothing");
    free(a); free(b);
    rigFree(&r);
    printf("test_split_disagreement: done\n");
}

// GUARD: no reply ever arrives -- both fields stay at their start values.
static void test_never(void)
{
    Rig r; rigInit(&r, 3000);
    Snapshot before = snap(&r);
    check(through(&r) == 0 && disagreed(&r) == 0, "never: both fields start at 0");
    checkNothingElseMoved(&r, &before, "never");
    rigFree(&r);
    printf("test_never: done\n");
}

// GUARD (GREEN-only): the cfheaders source answering its own chain is not corroboration.
static void test_same_address(void)
{
    Rig r; rigInit(&r, 3000);
    Snapshot before = snap(&r);
    size_t n; UInt256 *reply = buildReply(&r, r.tip, &n);

    relay(&r, r.source, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);

    check(through(&r) == 0, "same-address: the cfheaders source's own matching reply does not advance cfCorroboratedThrough");
    check(disagreed(&r) == 0, "same-address: an agreeing reply records no disagreement");
    checkNothingElseMoved(&r, &before, "same-address");

    // ...but its DISagreement still counts as an observation.
    reply[(r.topCompiled + 1000) / 1000u - 1] = u256_fill(0xEE);
    relay(&r, r.source, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);
    check(disagreed(&r) == 1 && through(&r) == 0, "same-address: a mismatch from the source is still counted, still not corroborating");
    checkNothingElseMoved(&r, &before, "same-address/mismatch");

    free(reply);
    rigFree(&r);
    printf("test_same_address: done\n");
}

// GUARD: over-long replies and unknown stop blocks are refused whole.
static void test_bounded(void)
{
    Rig r; rigInit(&r, 3000);
    Snapshot before = snap(&r);
    size_t n; UInt256 *reply = buildReply(&r, r.tip, &n);
    UInt256 *longer = calloc(n + 1, sizeof(UInt256));
    memcpy(longer, reply, n * sizeof(UInt256));      // a correct prefix, one entry too many

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, longer, n + 1);
    check(through(&r) == 0 && disagreed(&r) == 0,
          "bounded: a reply with more headers than 1000-multiples below the stop is refused whole");

    relay(&r, r.other, FILTER_TYPE_BASIC, u256_fill(0x77), reply, n);
    check(through(&r) == 0 && disagreed(&r) == 0, "bounded: a reply for a stop block we do not hold is ignored");

    relay(&r, r.other, (uint8_t)(FILTER_TYPE_BASIC + 1), r.tipBlock->blockHash, reply, n);
    check(through(&r) == 0 && disagreed(&r) == 0, "bounded: a reply for another filter type is ignored");

    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, 0);
    check(through(&r) == 0 && disagreed(&r) == 0, "bounded: an empty reply moves nothing");

    checkNothingElseMoved(&r, &before, "bounded");
    assertLockNotHeld(r.manager, "bounded: manager->lock released after every refusal");

    // The same reply, correctly sized, is accepted afterwards (the refusals left no state behind).
    relay(&r, r.other, FILTER_TYPE_BASIC, r.tipBlock->blockHash, reply, n);
    check(through(&r) == r.tip, "bounded: a well-formed reply after the refusals still corroborates");

    free(reply); free(longer);
    rigFree(&r);
    printf("test_bounded: done\n");
}


// ---- G5-QUORUM -------------------------------------------------------------
// The rebuild decision counts only the non-source peers that ANSWERED (distinct address and port, none at the
// source's address): at least CF_CKPT_QUORUM_MIN must report the same value at the same height, and they must be a
// strict majority of those that answered. And checking is continuous: each filter peer is asked again once our chain
// crosses past the 1000-multiple it was last checked through, never twice for one crossing.

// A reply from `p` that differs from our chain at `wrongAt` with `bad`, agrees elsewhere.
static void relayDissent(Rig *r, BRPeer *p, uint32_t wrongAt, uint8_t bad)
{
    size_t n; UInt256 *reply = buildReply(r, r->tip, &n);
    reply[wrongAt / 1000u - 1] = u256_fill(bad);
    relay(r, p, FILTER_TYPE_BASIC, r->tipBlock->blockHash, reply, n);
    free(reply);
}

static void relayAgree(Rig *r, BRPeer *p)
{
    size_t n; UInt256 *reply = buildReply(r, r->tip, &n);
    relay(r, p, FILTER_TYPE_BASIC, r->tipBlock->blockHash, reply, n);
    free(reply);
}

// RED/GREEN (-DCF_CKPT_QUORUM_UNFIXED): two answering dissenters that agree with each other, beside three filter
// peers that never answer, rebuild the chain. The old denominator (every connected filter peer, the source and the
// silent ones included: 2 of 6) blocked it.
static void test_g5_silent_peers_do_not_block(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    for (uint8_t a = 0x10; a < 0x13; a++) addConnectedFilterPeer(r.manager, a, 10010, r.tip);   // silent
    uint32_t wrongAt = r.topCompiled + 2000;
    unsigned misbehavinBefore = r.manager->misbehavinCount;

    relayDissent(&r, r.other, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL, "g5-silent: one answering dissenter alone changes nothing");
    relayDissent(&r, third, wrongAt, 0xEE);

    check(r.manager->compactFilterChain == NULL,
          "g5-silent: two agreeing answering dissenters beside three silent peers rebuild the chain");
    check(r.manager->misbehavinCount == misbehavinBefore + 1, "g5-silent: the cfheaders source is marked misbehaving");
    check(_cfCkDisputeCount(r.manager) == 0 && r.manager->cfCkRepCount == 0 && r.manager->cfCkAskCount == 0,
          "g5-silent: the rebuild clears the dissent, replier and re-check windows");
    assertLockNotHeld(r.manager, "g5-silent: manager->lock released");
    rigFree(&r);
    printf("test_g5_silent_peers_do_not_block: done\n");
}

// RED/GREEN (-DCF_CKPT_QUORUM_UNFIXED): peers sharing the source's address (other ports) are not voters, so they
// cannot out-vote a dissent by agreeing with the source's chain. Old rule: 2 of 5 connected, blocked.
static void test_g5_source_address_peers_do_not_count(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    BRPeer *dupA = addConnectedFilterPeer(r.manager, 0x01, 20001, r.tip);   // the source's address, other ports
    BRPeer *dupB = addConnectedFilterPeer(r.manager, 0x01, 20002, r.tip);
    uint32_t wrongAt = r.topCompiled + 2000;

    relayAgree(&r, dupA);
    relayAgree(&r, dupB);
    check(r.manager->cfCkRepCount == 0, "g5-source-dup: replies from the source's address join no denominator");
    check(through(&r) == 0, "g5-source-dup: and they corroborate nothing");
    relayDissent(&r, r.other, wrongAt, 0xEE);
    relayDissent(&r, third, wrongAt, 0xEE);

    check(r.manager->compactFilterChain == NULL,
          "g5-source-dup: two agreeing dissenters are not out-voted by the source's own address");
    rigFree(&r);
    printf("test_g5_source_address_peers_do_not_count: done\n");
}

// GUARD: one replier can never rebuild, whatever the rest do -- a lone dissenter beside silent peers, a lone
// dissenter beside an agreeing replier, the source disputing its own chain, and a dissent its own author withdraws.
static void test_g5_lone_dissent_never_rebuilds(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    for (uint8_t a = 0x10; a < 0x13; a++) addConnectedFilterPeer(r.manager, a, 10010, r.tip);   // silent
    uint32_t wrongAt = r.topCompiled + 2000;
    uint8_t reanchors = r.manager->cfReanchorCount;
    unsigned misbehavin = r.manager->misbehavinCount;

    relayDissent(&r, r.source, wrongAt, 0xEE);
    check(_cfCkDisputeCount(r.manager) == 0 && r.manager->cfCkRepCount == 0,
          "g5-lone: the source disputing its own chain is neither a vote nor a replier");
    relayDissent(&r, r.other, wrongAt, 0xEE);
    relayDissent(&r, r.other, wrongAt, 0xEE);   // the same peer again is still one vote
    check(r.manager->compactFilterChain != NULL && r.manager->cfCkRepCount == 1 && _cfCkDisputeCount(r.manager) == 1,
          "g5-lone: a lone dissenter among silent peers is one vote of one replier and rebuilds nothing");
    relayAgree(&r, third);
    check(r.manager->compactFilterChain != NULL && r.manager->cfCkRepCount == 2,
          "g5-lone: beside an agreeing replier it is one of two, still no rebuild");
    relayAgree(&r, r.other);
    check(_cfCkDisputeCount(r.manager) == 0, "g5-lone: a later agreeing reply withdraws that peer's dispute");
    relayDissent(&r, third, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL,
          "g5-lone: a withdrawn dispute does not combine with a new one into a quorum");
    check(r.manager->cfReanchorCount == reanchors && r.manager->misbehavinCount == misbehavin,
          "g5-lone: no re-anchor charged, nobody marked misbehaving");
    rigFree(&r);
    printf("test_g5_lone_dissent_never_rebuilds: done\n");
}

// GUARD: the answering peers that agree with our chain count against a dissent. Two dissenters against two
// agreeing repliers are not a strict majority.
static void test_g5_answering_agreers_count_against(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    BRPeer *p4 = addConnectedFilterPeer(r.manager, 0x04, 10004, r.tip);
    BRPeer *p5 = addConnectedFilterPeer(r.manager, 0x05, 10005, r.tip);
    uint32_t wrongAt = r.topCompiled + 2000;

    relayAgree(&r, p4);
    relayAgree(&r, p5);
    relayDissent(&r, r.other, wrongAt, 0xEE);
    relayDissent(&r, p3, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL && r.manager->cfCkRepCount == 4,
          "g5-agreers: two dissenters of four answering peers are not a majority");
    rigFree(&r);
    printf("test_g5_answering_agreers_count_against: done\n");
}


// RED/GREEN (-DCF_CKPT_QUORUM_UNFIXED): a dissenter that has disconnected no longer votes. Old rule: its recorded
// dissent plus one live dissenter, 2 of 3 connected, rebuilt the chain.
static void test_g5_departed_dissenter_does_not_count(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *third = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    uint32_t wrongAt = r.topCompiled + 2000;

    relayDissent(&r, r.other, wrongAt, 0xEE);
    ((BRPeerContext *)r.other)->status = BRPeerStatusDisconnected;   // it answered, then left
    relayDissent(&r, third, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL,
          "g5-departed: a dissenter that has disconnected no longer counts toward the quorum");
    ((BRPeerContext *)r.other)->status = BRPeerStatusConnected;     // restore so rigFree's checks see a sane pool
    rigFree(&r);
    printf("test_g5_departed_dissenter_does_not_count: done\n");
}

// RED/GREEN (-DCF_CKPT_QUORUM_UNFIXED): any number of ports on one non-source address are one host's word and cannot
// reach the quorum alone. Old rule: 3 of 4 connected rebuilt the chain.
static void test_g5_ports_on_one_host_cannot_reach_quorum(void)
{
    Rig r; rigInit(&r, 3000);
    ((BRPeerContext *)r.other)->status = BRPeerStatusDisconnected;   // keep the pool to the source and one host
    BRPeer *a = addConnectedFilterPeer(r.manager, 0x07, 30001, r.tip);
    BRPeer *b = addConnectedFilterPeer(r.manager, 0x07, 30002, r.tip);
    BRPeer *c = addConnectedFilterPeer(r.manager, 0x07, 30003, r.tip);
    uint32_t wrongAt = r.topCompiled + 2000;

    relayDissent(&r, a, wrongAt, 0xEE);
    relayDissent(&r, b, wrongAt, 0xEE);
    relayDissent(&r, c, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL && r.manager->cfCkRepCount == 3,
          "g5-one-host: three ports on one non-source address cannot reach the quorum alone");
    ((BRPeerContext *)r.other)->status = BRPeerStatusConnected;
    rigFree(&r);
    printf("test_g5_ports_on_one_host_cannot_reach_quorum: done\n");
}

static unsigned countC(void) { unsigned c = 0; for (size_t i = 0; i < g_seqLen; i++) c += (g_seq[i] == 'C'); return c; }

// Single-height getcfheaders probes sent to `p` for height `h` (start h, stop = our block at h).
static unsigned probesTo(BRPeer *p, uint32_t h)
{
    unsigned n = 0;
    for (size_t i = 0; i < g_hdrCount; i++)
        n += (g_hdrPeer[i] == p && g_hdrStart[i] == h && UInt256Eq(g_hdrStop[i], blockHashFor(h)));
    return n;
}

// Every single-height probe sent, to anyone.
static unsigned probesAll(void)
{
    unsigned n = 0;
    for (size_t i = 0; i < g_hdrCount; i++) n += (g_hdrStop[i].u32[1] == 0xB10C0000u && g_hdrStop[i].u32[0] == g_hdrStart[i]);
    return n;
}

// Any re-check question at all: a single-height probe or a cfcheckpt.
static unsigned recheckAsks(void) { return probesAll() + countC(); }

// The rig's filter hash at height h (rigInit appends hash i+1 at height start+i).
static UInt256 rigFilterHash(const Rig *r, uint32_t h)
{
    UInt256 x; memset(x.u8, 0, sizeof(x.u8)); x.u32[0] = h - r->start + 1; return x;
}


// ---- G5-QUORUM, review round 2 (RED under -DCF_CKPT_G5R2_UNFIXED, the f795f60 shape) ------------------------------

// A connected filter peer at an address in `group`'s /16 with low byte `low` (same host as every other `group` peer).
static BRPeer *addPeerInGroup(BRPeerManager *m, uint8_t group, uint8_t low, uint16_t port, uint32_t lastblock)
{
    BRPeer *p = addConnectedFilterPeer(m, group, port, lastblock);
    p->address.u8[15] = low;
    return p;
}

// H1: one host cycling ports, each with a different value and then leaving, must not jam the dispute table: two
// honest hosts still rebuild. Run at half capacity (the f795f60 dispute cap) and at full capacity (the eviction path).
static void test_g5r2_departed_disputes_do_not_jam(void)
{
    for (int fill = 8; fill <= CF_CKPT_PEER_CAP; fill += CF_CKPT_PEER_CAP - 8) {
        Rig r; rigInit(&r, 3000);
        uint32_t wrongAt = r.topCompiled + 2000;
        for (int i = 0; i < fill; i++) {
            BRPeer *j = addConnectedFilterPeer(r.manager, 0x20, (uint16_t)(40001 + i), r.tip);
            relayDissent(&r, j, wrongAt, (uint8_t)(0x60 + i));
            ((BRPeerContext *)j)->status = BRPeerStatusDisconnected;
        }
        BRPeer *p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
        BRPeer *p4 = addConnectedFilterPeer(r.manager, 0x04, 10004, r.tip);
        relayDissent(&r, p3, wrongAt, 0xEE);
        relayDissent(&r, p4, wrongAt, 0xEE);
        check(r.manager->compactFilterChain == NULL,
              fill == 8 ? "g5r2-jam: two honest hosts rebuild after 8 departed one-host disputes"
                        : "g5r2-jam: two honest hosts rebuild after a full table of departed disputes (eviction)");
        rigFree(&r);
    }
    printf("test_g5r2_departed_disputes_do_not_jam: done\n");
}

// H2: one host agreeing with the source on three ports is one host in the denominator, so it cannot block two
// honest dissenting hosts (2 of 3 hosts).
static void test_g5r2_ports_do_not_inflate_denominator(void)
{
    Rig r; rigInit(&r, 3000);
    uint32_t wrongAt = r.topCompiled + 2000;
    for (int i = 0; i < 3; i++) relayAgree(&r, addConnectedFilterPeer(r.manager, 0x30, (uint16_t)(50001 + i), r.tip));
    BRPeer *p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    relayDissent(&r, r.other, wrongAt, 0xEE);
    relayDissent(&r, p3, wrongAt, 0xEE);
    check(r.manager->compactFilterChain == NULL,
          "g5r2-ports: one host agreeing on three ports does not out-vote two dissenting hosts");
    rigFree(&r);
    printf("test_g5r2_ports_do_not_inflate_denominator: done\n");
}

// M3: a host is a network group. Two addresses in one /16 are one host's word; a peer in the source's group is
// neither for nor against.
static void test_g5r2_netgroup_is_the_host(void)
{
    Rig r; rigInit(&r, 3000);
    ((BRPeerContext *)r.other)->status = BRPeerStatusDisconnected;
    uint32_t wrongAt = r.topCompiled + 2000;
    BRPeer *a = addPeerInGroup(r.manager, 0x40, 0x01, 10040, r.tip);
    BRPeer *b = addPeerInGroup(r.manager, 0x40, 0x02, 10040, r.tip);
    relayDissent(&r, a, wrongAt, 0xEE);
    relayDissent(&r, b, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL, "g5r2-netgroup: two addresses in one /16 cannot reach the quorum alone");
    rigFree(&r);

    rigInit(&r, 3000);
    ((BRPeerContext *)r.other)->status = BRPeerStatusDisconnected;
    BRPeer *srcKin = addPeerInGroup(r.manager, 0x01, 0x99, 10041, r.tip);   // the source's /16, another address
    BRPeer *c = addConnectedFilterPeer(r.manager, 0x05, 10005, r.tip);
    relayDissent(&r, srcKin, wrongAt, 0xEE);
    relayDissent(&r, c, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL,
          "g5r2-netgroup: a peer in the source's network group is not an independent dissent");
    rigFree(&r);
    printf("test_g5r2_netgroup_is_the_host: done\n");
}

// Slot eviction when every entry's peer is still connected (more peers than the table, which the connection cap
// rules out in production): the newcomer still gets a vote, deterministically.
static void test_g5r2_full_table_of_live_peers_still_admits(void)
{
    Rig r; rigInit(&r, 3000);
    for (int i = 0; i < CF_CKPT_PEER_CAP; i++) relayAgree(&r, addConnectedFilterPeer(r.manager, (uint8_t)(0x50 + i), 10050, r.tip));
    check(r.manager->cfCkRepCount == CF_CKPT_PEER_CAP, "g5r2-evict: setup -- the voter table is full of live peers");
    BRPeer *late = addConnectedFilterPeer(r.manager, 0x70, 10070, r.tip);
    relayDissent(&r, late, r.topCompiled + 2000, 0xEE);
    int e = _cfCkFind(r.manager, late->address, late->port);
    check(e >= 0 && r.manager->cfCkRepDisputes[e], "g5r2-evict: a full table of live peers still admits the newcomer's vote");
    rigFree(&r);
    printf("test_g5r2_full_table_of_live_peers_still_admits: done\n");
}

// L6 and reconnect: only Connected peers vote, and a vote belongs to the connection that cast it.
static void test_g5r2_connection_state(void)
{
    Rig r; rigInit(&r, 3000);
    BRPeer *p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    BRPeer *p4 = addConnectedFilterPeer(r.manager, 0x04, 10004, r.tip);
    uint32_t wrongAt = r.topCompiled + 2000;
    relayDissent(&r, p3, wrongAt, 0xEE);
    ((BRPeerContext *)p3)->status = BRPeerStatusConnecting;   // dropped and redialling: not Connected
    relayDissent(&r, p4, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL, "g5r2-state: a Connecting peer's earlier dispute does not vote");
    rigFree(&r);

    rigInit(&r, 3000);
    p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, r.tip);
    relayDissent(&r, r.other, wrongAt, 0xEE);
    BRPeerCallbackInfo info = { .peer = r.other, .manager = r.manager, .hash = UINT256_ZERO };
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);   // reconnected at the same address+port
    MGR_UNLOCK(r.manager);
    relayDissent(&r, p3, wrongAt, 0xEE);
    check(r.manager->compactFilterChain != NULL,
          "g5r2-state: a peer reconnecting at the same address and port starts with no vote");
    rigFree(&r);
    printf("test_g5r2_connection_state: done\n");
}

// L5: a probe answer whose tag a rebuild dropped is consumed, not handed to the sync path.
static void test_g5r2_late_probe_answer_after_rebuild(void)
{
    Rig r; rigInit(&r, 3000u - (BRCFTopCheckpointHeight() % 1000u));
    BRPeerCallbackInfo info = { .peer = r.other, .manager = r.manager, .hash = UINT256_ZERO };
    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    BRMerkleBlock *higher = linkBlocks(r.manager, r.tip + 1, r.tip + CF_CORROBORATE_STOP_MARGIN, r.tipBlock->blockHash);
    r.manager->lastBlock = higher;
    seqReset();
    MGR_LOCK(r.manager);
    _cfCkNotePeerHeightLocked(r.manager, r.other, higher->height);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    uint32_t h = expectedStop(higher->height, higher->height);
    check(probesTo(r.other, h) == 1, "g5r2-late: setup -- the probe went out");

    // Two other hosts dispute and the chain is rebuilt: the rebuild drops every probe tag.
    BRPeer *p3 = addConnectedFilterPeer(r.manager, 0x03, 10003, higher->height);
    BRPeer *p4 = addConnectedFilterPeer(r.manager, 0x04, 10004, higher->height);
    relayDissent(&r, p3, r.topCompiled + 2000, 0xEE);
    relayDissent(&r, p4, r.topCompiled + 2000, 0xEE);
    check(r.manager->compactFilterChain == NULL, "g5r2-late: setup -- the chain was rebuilt");
    r.manager->cfHeadersRequestedThrough = 4242u;   // whatever the sync path has in flight
    UInt256 fh = u256_fill(0x33), prev = u256_fill(0x34);
    seqReset();
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, blockHashFor(h), prev, &fh, 1);
    check(r.manager->cfHeadersRequestedThrough == 4242u && r.manager->compactFilterChain == NULL && g_seqLen == 0,
          "g5r2-late: a probe answer arriving after a rebuild is consumed, not taken for a sync batch");
    r.manager->cfHeadersRequestedThrough = 0;
    rigFree(&r);
    printf("test_g5r2_late_probe_answer_after_rebuild: done\n");
}

// RED/GREEN (-DCF_CKPT_RECHECK_UNFIXED): a peer checked at connect is asked again after our chain crosses the next
// 1000-multiple, once, and not before. The source's address is never re-asked; nothing goes out while our filter
// headers trail the new stop.
static void test_g5_recheck_cadence(void)
{
    // The chain tip is put on a 1000-multiple so every boundary below is exact.
    Rig r; rigInit(&r, 3000u - (BRCFTopCheckpointHeight() % 1000u));
    check(r.tip % 1000u == 0, "g5-recheck: setup -- the chain tip sits on a 1000-multiple");
    BRPeerCallbackInfo info = { .peer = r.other, .manager = r.manager, .hash = UINT256_ZERO };
    uint32_t stop0 = expectedStop(r.tip, r.tip);

    MGR_LOCK(r.manager);
    _BRPeerManagerOnFilterCapablePeerConnected(r.manager, &info, r.other);
    MGR_UNLOCK(r.manager);
    check(g_checkptCount == 1 && UInt256Eq(g_checkptStop, blockHashFor(stop0)), "g5-recheck: asked once at connect");

    seqReset();
    MGR_LOCK(r.manager);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    check(recheckAsks() == 0, "g5-recheck: not re-asked while nothing has moved");

    // Up to one block short of the next stop (the tip plus the margin, less one): no new question.
    BRMerkleBlock *mid = linkBlocks(r.manager, r.tip + 1, r.tip + CF_CORROBORATE_STOP_MARGIN - 1, r.tipBlock->blockHash);
    r.manager->lastBlock = mid;
    MGR_LOCK(r.manager);
    _cfCkNotePeerHeightLocked(r.manager, r.other, mid->height);
    _cfCkNotePeerHeightLocked(r.manager, r.source, mid->height);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    check(expectedStop(mid->height, mid->height) == stop0, "g5-recheck: setup -- the stop has not moved yet");
    check(recheckAsks() == 0, "g5-recheck: not re-asked before the tip crosses the next 1000-multiple");

    // One more block: the stop crosses to the next 1000-multiple, which our chain already covers.
    seqReset();
    BRMerkleBlock *higher = linkBlocks(r.manager, mid->height + 1, mid->height + 1, mid->blockHash);
    r.manager->lastBlock = higher;
    MGR_LOCK(r.manager);
    _cfCkNotePeerHeightLocked(r.manager, r.other, higher->height);
    _cfCkNotePeerHeightLocked(r.manager, r.source, higher->height);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    uint32_t stop1 = expectedStop(higher->height, higher->height);
    check(stop1 == stop0 + 1000, "g5-recheck: setup -- the next stop is one 1000-multiple higher");
    check(recheckAsks() == 1 && (probesTo(r.other, stop1) == 1 ||
                                 (g_checkptPeer == r.other && UInt256Eq(g_checkptStop, blockHashFor(stop1)))),
          "g5-recheck: after the crossing the peer is re-asked exactly once, at the new stop (the source never)");
    check(probesTo(r.other, stop1) == 1 && countC() == 0,
          "g5-probe: the re-check is one single-height getcfheaders at the crossed height, not a cfcheckpt");

    // The peer answers the probe, agreeing: the header at h - 1 and our filter hash at h. It is judged, never
    // appended and never taken for a sync batch.
    {
        size_t countBefore = BRCompactFilterChainCount(r.manager->compactFilterChain);
        uint32_t inFlight = r.manager->cfHeadersRequestedThrough = 4242u;   // a sentinel the sync path would clear
        UInt256 fh = rigFilterHash(&r, stop1);
        check(UInt256Eq(BRGCSFilterHeader(fh, BRCompactFilterChainHeader(r.manager->compactFilterChain, stop1 - 1)),
                        BRCompactFilterChainHeader(r.manager->compactFilterChain, stop1)),
              "g5-probe: setup -- the rig's hash at h folds onto our header at h");
        seqReset();
        _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, blockHashFor(stop1),
                              BRCompactFilterChainHeader(r.manager->compactFilterChain, stop1 - 1), &fh, 1);
        check(BRCompactFilterChainCount(r.manager->compactFilterChain) == countBefore &&
              r.manager->cfHeadersRequestedThrough == inFlight && g_seqLen == 0,
              "g5-probe: the probe's answer is not appended, not taken for a sync batch, and sends nothing");
        check(through(&r) == stop1 && r.manager->cfCkRepCount == 1 && disagreed(&r) == 0,
              "g5-probe: an agreeing answer corroborates height h and counts its peer as a replier");
        r.manager->cfHeadersRequestedThrough = 0;
        assertLockNotHeld(r.manager, "g5-probe: manager->lock released after the probe answer");
    }

    // Another 1000 blocks, but our filter headers trail the new stop: nothing goes out until they catch up.
    seqReset();
    BRMerkleBlock *top = linkBlocks(r.manager, higher->height + 1, higher->height + 1000, higher->blockHash);
    r.manager->lastBlock = top;
    MGR_LOCK(r.manager);
    _cfCkNotePeerHeightLocked(r.manager, r.other, top->height);
    _cfCkNotePeerHeightLocked(r.manager, r.source, top->height);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    check(recheckAsks() == 0, "g5-recheck: nothing is asked while our filter headers trail the new stop");

    // They catch up through the real cfheaders handler (a clean append from the source), which re-checks itself.
    uint32_t add = top->height - r.tip;
    UInt256 *hashes = malloc(add * sizeof(UInt256));
    for (uint32_t i = 0; i < add; i++) { memset(hashes[i].u8, 0, sizeof(hashes[i].u8)); hashes[i].u32[0] = 0x50000 + i; }
    UInt256 prev = BRCompactFilterChainTipHeader(r.manager->compactFilterChain);
    BRPeerCallbackInfo srcInfo = { .peer = r.source, .manager = r.manager, .hash = UINT256_ZERO };
    seqReset();
    _peerRelayedCFHeaders(&srcInfo, FILTER_TYPE_BASIC, top->blockHash, prev, hashes, add);
    free(hashes);
    check(BRCompactFilterChainNextHeight(r.manager->compactFilterChain) == top->height + 1,
          "g5-recheck: setup -- the clean append reached the new tip");
    uint32_t stop2 = expectedStop(top->height, top->height);
    check(recheckAsks() == 1 && (probesTo(r.other, stop2) == 1 ||
                                 (g_checkptPeer == r.other && UInt256Eq(g_checkptStop, blockHashFor(stop2)))),
          "g5-recheck: a clean cfheaders append re-asks the peer once its chain covers the new stop");
    assertLockNotHeld(r.manager, "g5-recheck: manager->lock released after the append");

    // This time the peer's answer differs from our header at h: a dissent at (h, its header), same as a cfcheckpt.
    {
        size_t countBefore = BRCompactFilterChainCount(r.manager->compactFilterChain);
        UInt256 fh = u256_fill(0x5A);
        UInt256 prevH = BRCompactFilterChainHeader(r.manager->compactFilterChain, stop2 - 1);
        _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, blockHashFor(stop2), prevH, &fh, 1);
        int e = _cfCkFind(r.manager, r.other->address, r.other->port);
        check(disagreed(&r) == 1 && _cfCkDisputeCount(r.manager) == 1 && e >= 0 && r.manager->cfCkRepDisputes[e] &&
              r.manager->cfCkRepDisHeights[e] == stop2 &&
              UInt256Eq(r.manager->cfCkRepDisValues[e], BRGCSFilterHeader(fh, prevH)) &&
              BRCompactFilterChainCount(r.manager->compactFilterChain) == countBefore,
              "g5-probe: a differing answer is recorded as a dissent at (h, the peer's header) and appends nothing");
        // A repeat of the same answer is no longer the probe's (the tag is spent): the sync path sees it and drops it.
        _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, blockHashFor(stop2), prevH, &fh, 1);
        check(disagreed(&r) == 1, "g5-probe: the tag is spent by the first answer");
    }

    seqReset();
    MGR_LOCK(r.manager);
    _BRPeerManagerCFCheckptRecheckLocked(r.manager);
    MGR_UNLOCK(r.manager);
    check(recheckAsks() == 0, "g5-recheck: not asked a second time for the same crossing");
    rigFree(&r);
    printf("test_g5_recheck_cadence: done\n");
}

int main(void)
{
    test_request_sent_on_connect();
    test_request_gated_at_reply_cap();
    test_agree();
    test_disagree();
    test_majority_disagrees();
    test_split_disagreement();
    test_never();
    test_same_address();
    test_bounded();
    test_g5_silent_peers_do_not_block();
    test_g5_source_address_peers_do_not_count();
    test_g5_lone_dissent_never_rebuilds();
    test_g5_answering_agreers_count_against();
    test_g5_departed_dissenter_does_not_count();
    test_g5_ports_on_one_host_cannot_reach_quorum();
    test_g5_recheck_cadence();
    test_g5r2_departed_disputes_do_not_jam();
    test_g5r2_ports_do_not_inflate_denominator();
    test_g5r2_netgroup_is_the_host();
    test_g5r2_full_table_of_live_peers_still_admits();
    test_g5r2_connection_state();
    test_g5r2_late_probe_answer_after_rebuild();
    printf(g_fail ? "cf_corroboration_kat: %d FAILURE(S)\n" : "cf_corroboration_kat: all checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
