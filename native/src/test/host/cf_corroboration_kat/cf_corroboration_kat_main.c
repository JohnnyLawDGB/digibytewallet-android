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
//     untouched by any of the above.
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

static void seqPush(char c) { if (g_seqLen + 1 < sizeof(g_seq)) g_seq[g_seqLen++] = c; g_seq[g_seqLen] = 0; }
static void seqReset(void) { g_seqLen = 0; g_seq[0] = 0; g_checkptPeer = NULL; g_checkptType = 0xFF; g_checkptStop = UINT256_ZERO; g_checkptCount = 0; }

static void kat_BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    seqPush('H');
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

int main(void)
{
    test_request_sent_on_connect();
    test_request_gated_at_reply_cap();
    test_agree();
    test_disagree();
    test_never();
    test_same_address();
    test_bounded();
    printf(g_fail ? "cf_corroboration_kat: %d FAILURE(S)\n" : "cf_corroboration_kat: all checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
