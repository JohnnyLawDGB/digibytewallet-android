// Host KAT: OWNNODE-LIVE -- own-node health reports whether the paired node is serving compact
// filters NOW, not whether it ever did. A node whose filter request has stalled reads DARK (the
// existing banner with "Use public peers"); exclusive mode still never roams to public peers.
//   [A] served, then stopped answering cfheaders            -> CONNECTED_NOT_SERVING / DARK   (red unfixed)
//   [B] answers cfheaders, stopped answering cfilters        -> CONNECTED_NOT_SERVING / DARK   (red unfixed)
//       This is also the state a fresh (rebuilt) manager reaches: its first answered cfheaders batch
//       credits the node again, so a peer-manager rebuild alone does not surface it.
//   [C] guard: a request still inside its window            -> SERVING (no flicker)
//   [D] guard: one send aged by a device sleep, then a retry -> SERVING until the retry stalls too
//   [E] guard: stalled, then answered                       -> SERVING again
//   [F] guard: exclusive pin -- the lone node is kept, nothing else is dialled, the pin is unchanged
// Harness: cf_empty_batch_kat's (BRPeer.c and BRPeerManager.c #included). Time is advanced by
// ageing the served clocks, the only state the decision reads time from.
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
#include "BROwnNode.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }
// A required behaviour of the fix: the red arm must fail exactly these lines.
static void must(int c, const char *tag, const char *d)
{
    if (c) printf("  ok   %s\n", d); else { printf("FAIL: %s: %s\n", tag, d); g_fail++; }
}

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
    p->address.u8[10] = 0xff; p->address.u8[11] = 0xff; p->address.u8[12] = 192; p->address.u8[13] = 168;
    p->address.u8[15] = a;
    p->port = 12024;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0) {
        ((BRPeerContext *)p)->socket = sv[0];
        if (g_nfds < 8) g_fds[g_nfds++] = sv[1];
    }
    array_add(m->connectedPeers, p);
    return p;
}
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

#define S_BIRTH 21400000u

// A manager whose only peer is the user's own node, pinned exclusive and in the dial pool.
typedef struct {
    BRWallet *w; BRPeerManager *m; BRPeer *p; BRPeerCallbackInfo inf;
    uint32_t lowB, top; BRMerkleBlock **blk;
} Mx;

static void mxInit(Mx *x, uint32_t chainTip, uint32_t top)
{
    memset(x, 0, sizeof(*x));
    x->w = makeWallet();
    x->m = BRPeerManagerNew(&BRMainNetParams, x->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(x->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    x->p = addPeer(x->m, 7);
    x->inf = (BRPeerCallbackInfo){ .peer = x->p, .manager = x->m, .hash = UINT256_ZERO };
    array_add(x->m->peers, *x->p);
    BRPeerManagerSetPinnedPeer(x->m, x->p->address, x->p->port, 1);
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
// The node answers getcfheaders for [from .. to].
static void answerCFHeaders(Mx *x, uint32_t from, uint32_t to)
{
    static UInt256 hs[2000];
    for (uint32_t h = from; h <= to; h++) hs[h - from] = filterHashAt(h);
    _peerRelayedCFHeaders(&x->inf, FILTER_TYPE_BASIC, blockHashAt(to),
                          BRCompactFilterChainTipHeader(x->m->compactFilterChain), hs, to - from + 1);
    drainPeers();
}
// The node answers getcfilters at height h.
static void answerCFilter(Mx *x, uint32_t h)
{
    uint8_t e[33]; encodedAt(h, e);
    _peerRelayedCFilter(&x->inf, FILTER_TYPE_BASIC, blockHashAt(h), e, sizeof(e));
    drainPeers();
}
// The cfheaders driver runs (its KeepAlive kick): it asks for the next batch, or -- with one in flight
// past its timeout -- asks again; with one filter peer, the lone node is asked again.
static void askCFHeaders(Mx *x)
{
    x->m->cfHeadersRequestTime -= 60;   // the in-flight request has outlived its timeout
    _BRPeerManagerRequestNextCFHeaders(x->m, x->p, 0);
    drainPeers();
}
static void askCFilters(Mx *x, uint32_t from, uint32_t to)
{
    _BRPeerManagerRequestCFiltersLocked(x->m, from, to, x->p);
    drainPeers();
}
// `secs` pass: every served clock is moved back by that much.
static void age(Mx *x, int64_t secs)
{
    for (int i = 0; i < 16; i++) for (int k = 0; k < 2; k++) {
        BRCFServedClock *c = &x->m->cfServedClock[i][k];
        if (c->sends) { c->firstSend -= secs; c->retrySend -= secs; }
    }
}
static int status(Mx *x) { return BRPeerManagerCompactFilterPeerStatus(x->m, x->p->address, x->p->port); }
static int dark(Mx *x) { return BROwnNodeHealthFor(status(x)) == BROwnNodeHealthDark; }
static int servingNow(Mx *x) { return status(x) == BR_CF_PEER_SERVING; }

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    uint32_t tip = S_BIRTH + 1000;

    printf("[A] served, then stopped answering cfheaders\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        check(status(&x) == BR_CF_PEER_CONNECTED_NOT_SERVING, "a node that never answered reads not serving");
        answerCFHeaders(&x, tip + 1, tip + 50);                 // served; the driver asks for the next batch
        check(servingNow(&x), "after a clean batch the node reads serving");
        askCFHeaders(&x);                                       // the next batch is asked for ...
        askCFHeaders(&x);                                       // ... and again; it is never answered
        check(x.m->cfServedClock[0][CF_SERVED_HDR].sends == 2, "two unanswered cfheaders sends on the clock");
        age(&x, CF_SERVED_STALE_SECS + 30);
        must(status(&x) == BR_CF_PEER_CONNECTED_NOT_SERVING, "A",
             "a node whose cfheaders request stalled reads connected-not-serving");
        must(dark(&x), "A", "and own-node health is DARK (the banner with \"Use public peers\")");
        mxFree(&x);
    }

    printf("[B] answers cfheaders, stopped answering cfilters (also a rebuilt manager's state)\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        answerCFHeaders(&x, tip + 1, tip + 50);                 // a fresh manager: credited by cfheaders
        answerCFilter(&x, tip + 1);                             // and once by a cfilter
        askCFilters(&x, tip + 2, tip + 50);
        askCFilters(&x, tip + 2, tip + 50);                     // the scan's re-request
        answerCFHeaders(&x, tip + 51, tip + 100);               // cfheaders still answered
        age(&x, CF_SERVED_STALE_SECS + 30);
        check(x.m->cfServedClock[0][CF_SERVED_FLT].sends >= 2, "the cfilter sends are on the cfilter clock");
        check(! BRCFServedClockStalled(&x.m->cfServedClock[0][CF_SERVED_HDR], (int64_t)time(NULL), 5),
              "and the cfheaders clock is not stalled (its answer stopped it)");
        must(status(&x) == BR_CF_PEER_CONNECTED_NOT_SERVING, "B",
             "a node that answers cfheaders but lets cfilters stall reads connected-not-serving");
        must(dark(&x), "B", "and own-node health is DARK");
        mxFree(&x);
    }

    printf("[C] a request still inside its window\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        answerCFHeaders(&x, tip + 1, tip + 50);
        askCFHeaders(&x); askCFHeaders(&x);
        check(servingNow(&x), "two sends, the first one young: still serving");
        age(&x, CF_SERVED_STALE_SECS - 10);
        check(servingNow(&x), "below the stale age: still serving");
        mxFree(&x);
    }

    printf("[D] one send aged by a device sleep, then a retry\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        answerCFHeaders(&x, tip + 1, tip + 50);
        askCFHeaders(&x);                                       // one request outstanding when the device slept
        age(&x, 3600);
        check(servingNow(&x), "one aged send alone does not read dark");
        askCFHeaders(&x);
        check(servingNow(&x), "the retry is younger than the request timeout: still serving");
        answerCFHeaders(&x, tip + 51, tip + 100);               // a healthy node answers the retry
        check(servingNow(&x), "answered: serving");
        check(x.m->cfServedClock[0][CF_SERVED_HDR].sends == 0, "and the cfheaders clock stopped");
        mxFree(&x);
    }

    printf("[E] stalled, then answered\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        answerCFHeaders(&x, tip + 1, tip + 50);
        askCFHeaders(&x); askCFHeaders(&x);
        age(&x, CF_SERVED_STALE_SECS + 30);
        check(BRCFServedClockStalled(&x.m->cfServedClock[0][CF_SERVED_HDR], (int64_t)time(NULL), 5),
              "the cfheaders clock reads stalled before the answer");
        answerCFHeaders(&x, tip + 51, tip + 100);
        check(servingNow(&x), "a stalled node that answers again reads serving");
        mxFree(&x);
    }

    printf("[F] exclusive mode never roams\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        answerCFHeaders(&x, tip + 1, tip + 50);
        for (int i = 0; i < 6; i++) { askCFHeaders(&x); (void)status(&x); }
        age(&x, CF_SERVED_STALE_SECS + 30);
        (void)status(&x);
        check(array_count(x.m->connectedPeers) == 1 && x.m->connectedPeers[0] == x.p, "the lone node is kept, no other peer");
        check(BRPeerConnectStatus(x.p) == BRPeerStatusConnected, "and it is not dropped");
        check(x.m->pinnedPort == x.p->port && UInt128Eq(x.m->pinnedAddr, x.p->address) && x.m->pinnedExclusive == 1,
              "the exclusive pin is unchanged (the user chooses \"Use public peers\")");
        mxFree(&x);
    }

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: ownnode_live_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
