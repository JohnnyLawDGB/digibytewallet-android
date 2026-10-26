// Host KAT: an empty cfheaders batch is not progress (4.1.2 row 26, bobverga 1a163a2c).
//   [A] an empty batch from peer 0: cfTriedCount is not reset, the peer is not credited as serving
//       filters, the chain does not move, and the peer is dropped -- RED under CF_EMPTY_BATCH_UNFIXED,
//       where it counted as an OK batch and the same peer was asked again (a session-long stall);
//   [B] guard: an honest batch still extends the chain and credits the peer.
// Harness: cf_chain_limit_kat's (BRPeer.c and BRPeerManager.c #included).
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
static int served(BRPeerManager *m, BRPeer *p)
{
    for (size_t i = 0; i < m->cfServedCount && i < 16; i++)
        if (UInt128Eq(m->cfServedAddr[i], p->address) && m->cfServedPort[i] == p->port) return 1;
    return 0;
}

static int stored(BRPeerManager *m, BRPeer *p)
{
    for (size_t i = 0; i < array_count(m->peers); i++) if (BRPeerEq(&m->peers[i], p)) return 1;
    return 0;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    uint32_t tip = S_BIRTH + 1000;

    printf("[A] an empty batch\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        array_add(x.m->peers, *x.p[0]);                     // the peer is in the stored dial list
        x.m->cfTriedCount = 2;
        size_t countBefore = BRCompactFilterChainCount(x.m->compactFilterChain);
        _peerRelayedCFHeaders(&x.inf[0], FILTER_TYPE_BASIC, blockHashAt(tip + 50),
                              BRCompactFilterChainTipHeader(x.m->compactFilterChain), NULL, 0);
        drainPeers();
        int ok = x.m->cfTriedCount == 2 && ! served(x.m, x.p[0]) &&
                 BRCompactFilterChainCount(x.m->compactFilterChain) == countBefore && ! stored(x.m, x.p[0]);
        if (! ok) {
            printf("FAIL: A: the empty batch counted (tried %zu, served %d, stored %d)\n",
                   (size_t)x.m->cfTriedCount, served(x.m, x.p[0]), stored(x.m, x.p[0]));
            g_fail++;
        } else check(1, "no reset, no credit, no progress, and the peer was dropped");
        mxFree(&x);
    }

    printf("[B] an honest batch\n");
    {
        Mx x; mxInit(&x, tip, tip + 100);
        size_t countBefore = BRCompactFilterChainCount(x.m->compactFilterChain);
        sendBatch(&x, 1, tip + 1, tip + 50, BRCompactFilterChainTipHeader(x.m->compactFilterChain));
        check(BRCompactFilterChainCount(x.m->compactFilterChain) == countBefore + 50, "an honest batch extends the chain");
        check(served(x.m, x.p[1]), "and credits its peer");
        mxFree(&x);
    }

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: cf_empty_batch_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
