// orphan_set_limits_kat -- the set of parentless headers ("orphans") has a fixed upper
// limit, every header that leaves it has exactly one owner, its running byte total always
// equals what is resident, and its re-anchor request is spaced per peer.
//
// WHAT IS PROVED (BRPeerManager.c, the parentless-header set discipline):
//   1. LIMIT. manager->orphans never holds more than ORPHAN_SET_COUNT_MAX headers or
//      ORPHAN_SET_BYTES_MAX resident bytes, however many distinct parentless headers arrive.
//      Both limits are measured here by walking the set, never by trusting the running total.
//   2. ONE OWNER. A header that leaves the set -- displaced by an insert sharing its parent,
//      evicted by the limit, or connected to the chain -- has exactly one owner afterwards,
//      and manager->lastOrphan never names a header that has left the set.
//   3. EXACT TOTAL. manager->orphanBytes equals the sum over the resident set after every
//      kind of change: insert, displacement, eviction, and a header connecting.
//   4. SPACING. The re-anchor getheaders is authorized once per chain of parentless headers
//      and at most once per interval per peer; a chain whose first member arrived inside the
//      interval still gets its request from a later member; a clock that steps backwards
//      does not hold a peer's request back.
//
// HOW. This file #includes BRPeerManager.c, so it reaches the file-static helpers AND the
// real relay entry point:
//   * the "limits" scenario drives the helpers directly on a minimal manager (the same
//     BRSetNew calls BRPeerManagerNewEx makes): 100,000 parentless headers with recent
//     timestamps, half of them sharing one parent, none chained to another;
//   * the "relay" and "connect" scenarios drive the real _peerRelayedBlock on a real
//     BRPeerManager, so the store's first call site (a header whose parent is not resident)
//     and every way a header leaves the set are the production code, not a copy of it;
//   * the "rescan" scenario drives the store's SECOND call site the same way: a header whose
//     parent is resident above the tip, relayed while the tip is below the best height its
//     peer announced. The announcement reaches the peer through the real version-message
//     handler (orphan_set_limits_kat_peer.c). What holds at the first call site is asserted
//     there too: exact total, lastOrphan resident, a displaced header released exactly once,
//     the count limit, and the header connecting once the tip reaches its parent.
// Single-threaded apart from one fixed-stack worker: the ...Locked helpers need only mutual
// exclusion, which one caller at a time provides.
//
// CHECK TAGS. Every check line carries a tag. (R) went red first -- in the reference arm, or
// against the tree as it stood before the check was added. (G) guards a property that
// already held and must keep holding; a guard is never the red-then-green proof.
//
// MACRO CONVENTION (value form): run.sh passes -DORPHAN_SET_LIMITS_UNFIXED=1 for the
// reference arm and =0 for the fixed arm, and the code selects with `#if`, never `#ifdef`,
// so the =0 build really takes the fixed path. The reference arm keeps the earlier store,
// connect step and request rule, selected inside BRPeerManager.c by the same macro.
//
// LEAK DETECTION IS ON for this gate: single ownership is what it proves, so the fixed arm
// must finish clean under LeakSanitizer.
//
// SCENARIO SELECTION. argv[1] = "limits" | "relay" | "connect" | "rescan"; none runs them
// all. run.sh runs the reference arm once per scenario so that each one is reported for its
// own reason.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "BRPeerManager.c"

// orphan_set_limits_kat_peer.c: a version message announcing `bestHeight`, through the real handler.
int kat_peer_accept_version(BRPeer *peer, uint32_t bestHeight);

#define N_ORPHANS 100000

// Connect cycles driven through the real relay path. The default keeps the gate quick;
// a larger value (-DCONNECT_CYCLES=...) runs the same scenario for longer.
#ifndef CONNECT_CYCLES
#define CONNECT_CYCLES 2000
#endif

static int g_fail = 0;
static volatile uint64_t g_sink = 0;

static void check(int cond, const char *tag, const char *what) {
    printf("  [%s] %s %s\n", cond ? "PASS" : "FAIL", tag, what);
    if (! cond) g_fail = 1;
}

// Resident bytes measured by walking the set -- independent of manager->orphanBytes.
static size_t resident_bytes(BRSet *orphans) {
    size_t sum = 0;
    BRMerkleBlock *o = NULL;
    while ((o = BRSetIterate(orphans, o)) != NULL) sum += _BRMerkleBlockResidentBytes(o);
    return sum;
}

static UInt256 hash_for(uint32_t tag) {
    UInt256 h;
    memset(h.u8, 0, sizeof(h.u8));
    h.u32[0] = tag + 1;          // distinct per tag, never all-zero
    h.u32[7] = 0xFEEDu;
    return h;
}

// A header with a chosen identity, parent and timestamp. payloadHashes > 0 attaches a
// hashes payload so the byte limit can be reached with few headers.
static BRMerkleBlock *make_header(uint32_t tag, UInt256 prevBlock, uint32_t timestamp, size_t payloadHashes) {
    BRMerkleBlock *o = BRMerkleBlockNew();
    o->timestamp = timestamp;
    o->height    = BLOCK_UNKNOWN_HEIGHT;   // the relay path stamps the height once the parent is known
    o->blockHash = hash_for(tag);
    o->prevBlock = prevBlock;
    if (payloadHashes) {
        o->hashes      = calloc(payloadHashes, sizeof(UInt256));
        o->hashesCount = payloadHashes;
    }
    return o;
}

// ---- minimal manager for the helper-level scenario ------------------------------------
// Builds the two sets the discipline touches exactly as BRPeerManagerNewEx builds them
// (manager->orphans keyed by prevBlock, checkpoints keyed by height).
static BRPeerManager *make_min_manager(void) {
    BRPeerManager *m = calloc(1, sizeof(*m));
    m->orphans     = BRSetNew(_BRPrevBlockHash, _BRPrevBlockEq, 1024);
    m->checkpoints = BRSetNew(_BRBlockHeightHash, _BRBlockHeightEq, 100);
    pthread_mutex_init(&m->lock, NULL);
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
    return m;
}

static void free_min_manager(BRPeerManager *m) {
    BRSetApply(m->orphans, NULL, _setApplyFreeBlock);   // the set owns whatever is still resident
    BRSetFree(m->orphans);
    BRSetFree(m->checkpoints);
    pthread_mutex_destroy(&m->lock);
    free(m);
}

// A header with a parent nobody has delivered, and a header naming a given parent. Each
// takes its identities from *tag and advances it, so no two calls share one.
static BRMerkleBlock *unrelated_header(uint32_t *tag, uint32_t timestamp) {
    uint32_t id = (*tag)++, parent = (*tag)++;
    return make_header(id, hash_for(parent), timestamp, 0);
}

static BRMerkleBlock *child_of(uint32_t *tag, const BRMerkleBlock *parent, uint32_t timestamp) {
    uint32_t id = (*tag)++;
    return make_header(id, parent->blockHash, timestamp, 0);
}

static BRPeer make_peer_id(uint32_t n) {
    BRPeer p;
    memset(&p, 0, sizeof(p));
    p.address.u32[3] = 0x0a000000u + n;
    p.port = (uint16_t)(1000 + n);
    return p;
}

// ---- real manager + one peer for the relay-path scenarios -----------------------------
typedef struct {
    BRPeerManager *m;
    BRPeer *peer;
    BRPeerCallbackInfo info;
} Rig;

// The rig's chain starts from a hand-built tip at `tipHeight` rather than from the genesis
// checkpoint, so that headers relayed on top of it do not run into the heights of the
// hardcoded checkpoint table (each of those heights accepts one identity only).
static uint32_t newest_checkpoint_height(void) {
    return BRMainNetParams.checkpoints[BRMainNetParams.checkpointsCount - 1].height;
}

static int rig_open(Rig *r, BRWallet *wallet, uint32_t tipHeight) {
    r->m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! r->m) return 0;
    r->m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    BRMerkleBlock *tip = make_header(0x0F000000u, UINT256_ZERO, (uint32_t)time(NULL), 0);
    tip->height = tipHeight;
    BRSetAdd(r->m->blocks, tip);              // owned (and freed) by the manager from here
    r->m->lastBlock = tip;
    r->m->lastSpanClampLog = tipHeight;       // keeps a once-per-manager retention notice out of the output

    r->peer = BRPeerNew(BRMainNetParams.magicNumber);
    r->peer->address.u8[15] = 0x51;
    r->peer->port = 12051;
    array_add(r->m->connectedPeers, r->peer);   // owned (and freed) by the manager from here
    r->info.peer = r->peer;
    r->info.manager = r->m;
    r->info.hash = UINT256_ZERO;
    return 1;
}

static void rig_close(Rig *r) { BRPeerManagerFree(r->m); }

static int total_is_exact(BRPeerManager *m) { return m->orphanBytes == resident_bytes(m->orphans); }

// =======================================================================================
// SCENARIO "limits": helper level. Limit by count, limit by bytes, one owner, spacing.
// =======================================================================================
static void scenario_limits(void)
{
    time_t now = time(NULL);
    printf("\n=== limits: two headers sharing one parent ===\n");
    {
        BRPeerManager *m = make_min_manager();
        UInt256 parent; memset(&parent, 0x11, sizeof(parent));
        BRMerkleBlock *x = make_header(1000000, parent, (uint32_t)now, 0);
        BRMerkleBlock *y = make_header(2000000, parent, (uint32_t)now, 0);
        _BRPeerManagerStoreOrphanLocked(m, x);
        check(total_is_exact(m), "(R)", "byte total equals the resident sum after an insert");
        _BRPeerManagerStoreOrphanLocked(m, y);   // same key (prevBlock): y takes x's place
        check(BRSetCount(m->orphans) == 1, "(G)", "two headers sharing a parent hold one resident entry");
        check(m->lastOrphan == y, "(G)", "lastOrphan names the resident header");
        check(total_is_exact(m), "(R)", "byte total equals the resident sum after a displacement");
        if (m->lastOrphan) g_sink += m->lastOrphan->timestamp;   // read through lastOrphan under ASan
        free_min_manager(m);   // x has its one owner already, so this finishes clean under LeakSanitizer
    }

    printf("\n=== limits: %d parentless headers, half sharing one parent ===\n", N_ORPHANS);
    {
        BRPeerManager *m = make_min_manager();
        BRPeer peerA = make_peer_id(1), peerB = make_peer_id(2);
        UInt256 sharedParent; memset(&sharedParent, 0xAB, sizeof(sharedParent));
        size_t worstCount = 0, worstBytes = 0;

        for (uint32_t i = 0; i < N_ORPHANS; i++) {
            UInt256 prev = (i % 2 == 1) ? sharedParent : hash_for(0x40000000u + i);
            uint32_t ts = (uint32_t)now - (i % 3600);            // recent: within the last hour
            BRMerkleBlock *o = make_header(i, prev, ts, 0);
            BRPeer *p = (i % 5 == 0) ? &peerB : &peerA;          // two peers share the deliveries

            _BRPeerManagerShouldReanchorForOrphanLocked(m, p, o, now);   // constant clock: all inside one interval
            _BRPeerManagerStoreOrphanLocked(m, o);

            if ((i % 1000) == 999) {   // sample the true resident size as the run proceeds
                size_t c = BRSetCount(m->orphans), b = resident_bytes(m->orphans);
                if (c > worstCount) worstCount = c;
                if (b > worstBytes) worstBytes = b;
            }
        }

        size_t count = BRSetCount(m->orphans), bytes = resident_bytes(m->orphans);
        if (count > worstCount) worstCount = count;
        if (bytes > worstBytes) worstBytes = bytes;
        printf("after %d headers: resident=%zu resident_bytes=%zu running_total=%zu requests=%llu "
               "(count limit=%u byte limit=%u)\n", N_ORPHANS, count, bytes, m->orphanBytes,
               (unsigned long long)m->orphanReanchorRequests,
               (unsigned)ORPHAN_SET_COUNT_MAX, (unsigned)ORPHAN_SET_BYTES_MAX);

        check(worstCount <= ORPHAN_SET_COUNT_MAX, "(R)", "parentless-header count stays within the fixed upper limit");
        check(worstBytes <= ORPHAN_SET_BYTES_MAX, "(R)", "resident bytes stay within the fixed upper limit");
        check(total_is_exact(m), "(R)", "byte total equals the resident sum after evictions");
        check(m->orphanReanchorRequests == 2, "(R)", "one interval authorizes one request per peer");
        if (m->lastOrphan) g_sink += m->lastOrphan->timestamp;
        free_min_manager(m);
    }

    printf("\n=== limits: the byte limit binds before the count limit ===\n");
    {
        // 120 headers of ~100 KB each: far below the count limit, above the byte limit.
        BRPeerManager *m = make_min_manager();
        const size_t payload = 3200;   // 3200 x 32 B
        size_t worstBytes = 0;
        for (uint32_t i = 0; i < 120; i++) {
            BRMerkleBlock *o = make_header(0x50000000u + i, hash_for(0x60000000u + i), (uint32_t)now - i, payload);
            _BRPeerManagerStoreOrphanLocked(m, o);
            size_t b = resident_bytes(m->orphans);
            if (b > worstBytes) worstBytes = b;
        }
        printf("after 120 large headers: resident=%zu worst_resident_bytes=%zu running_total=%zu\n",
               BRSetCount(m->orphans), worstBytes, m->orphanBytes);
        check(BRSetCount(m->orphans) < ORPHAN_SET_COUNT_MAX, "(G)", "setup: the count limit is not what binds here");
        check(worstBytes <= ORPHAN_SET_BYTES_MAX, "(R)", "resident bytes stay within the byte limit when it binds first");
        check(total_is_exact(m), "(R)", "byte total equals the resident sum after byte-limit evictions");
        check(m->lastOrphan && BRSetGet(m->orphans, m->lastOrphan) == m->lastOrphan, "(G)",
              "the header just stored is still resident after the eviction pass");
        free_min_manager(m);
    }

    printf("\n=== limits: request spacing ===\n");
    {
        BRPeerManager *m = make_min_manager();
        BRPeer pA = make_peer_id(1);
        const time_t t0 = now, gap = ORPHAN_REANCHOR_MIN_INTERVAL_SECS;
        uint32_t tag = 0x70000000u;
        int ask;

        // An unrelated header from a new peer asks; a second one inside the interval does not.
        BRMerkleBlock *x = unrelated_header(&tag, (uint32_t)now);
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, x, t0);
        _BRPeerManagerStoreOrphanLocked(m, x);
        check(ask == 1, "(G)", "first parentless header from a peer authorizes a request");

        BRMerkleBlock *a1 = unrelated_header(&tag, (uint32_t)now);   // head of a new chain
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, a1, t0 + 10);
        _BRPeerManagerStoreOrphanLocked(m, a1);
        check(ask == 0, "(R)", "an unrelated header inside the peer's interval does not authorize another");

        // The chain whose head arrived inside the interval still gets its request.
        BRMerkleBlock *a2 = child_of(&tag, a1, (uint32_t)now);
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, a2, t0 + gap + 15);
        _BRPeerManagerStoreOrphanLocked(m, a2);
        check(ask == 1, "(R)", "a chain whose head fell inside the interval is asked for by a later member");

        BRMerkleBlock *a3 = child_of(&tag, a2, (uint32_t)now);
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, a3, t0 + 10*gap);
        _BRPeerManagerStoreOrphanLocked(m, a3);
        check(ask == 0, "(G)", "a chain that has had its request is not asked for again");

        // Same peer, new chain, after the interval.
        BRMerkleBlock *b1 = unrelated_header(&tag, (uint32_t)now);
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, b1, t0 + 20*gap);
        _BRPeerManagerStoreOrphanLocked(m, b1);
        check(ask == 1, "(G)", "the same peer is authorized again once its interval has passed");

        // The clock steps back by a day: the peer's request is not held until it catches up.
        BRMerkleBlock *c1 = unrelated_header(&tag, (uint32_t)now);
        ask = _BRPeerManagerShouldReanchorForOrphanLocked(m, &pA, c1, t0 - 24*60*60);
        _BRPeerManagerStoreOrphanLocked(m, c1);
        check(ask == 1, "(R)", "a clock that stepped backwards does not hold the peer's request back");

        // More distinct peers than there are slots: each still gets its first request.
        int allAsked = 1;
        for (uint32_t n = 0; n < (uint32_t)PEER_MAX_CONNECTIONS + 1; n++) {
            BRPeer pn = make_peer_id(100 + n);
            BRMerkleBlock *o = unrelated_header(&tag, (uint32_t)now);
            if (! _BRPeerManagerShouldReanchorForOrphanLocked(m, &pn, o, t0 + 40*gap + (time_t)n)) allAsked = 0;
            _BRPeerManagerStoreOrphanLocked(m, o);
        }
        check(allAsked, "(G)", "a peer beyond the slot count reuses the least recently used slot");
        free_min_manager(m);
    }
}

// =======================================================================================
// SCENARIO "relay": the real _peerRelayedBlock. Store call sites, connect, full-set pass.
// =======================================================================================
typedef struct { Rig *rig; uint32_t now; int ok; uint32_t baseHeight; } CascadeJob;

// Stores a contiguous chain of ORPHAN_SET_COUNT_MAX headers, then relays the header that
// connects it, so the whole set connects in one pass. Runs on a worker with a 1 MiB stack
// (the default for a peer thread on the target platform).
static void *cascade_worker(void *arg)
{
    CascadeJob *job = arg;
    Rig *r = job->rig;
    const uint32_t n = ORPHAN_SET_COUNT_MAX, base = 0x20000000u;

    job->baseHeight = r->m->lastBlock->height;
    for (uint32_t i = 1; i <= n; i++)   // member i names member i-1 as its parent; member 0 is not known yet
        _peerRelayedBlock(&r->info, make_header(base + i, hash_for(base + i - 1), job->now, 0));
    job->ok = (BRSetCount(r->m->orphans) == n);

    _peerRelayedBlock(&r->info, make_header(base, r->m->lastBlock->blockHash, job->now, 0));
    return NULL;
}

static void scenario_relay(BRWallet *wallet)
{
    uint32_t now = (uint32_t)time(NULL);

    printf("\n=== relay: the same parentless header delivered twice ===\n");
    {
        Rig r;
        if (! rig_open(&r, wallet, newest_checkpoint_height() + 100)) { check(0, "(G)", "setup: manager allocated"); return; }
        UInt256 unknownParent = hash_for(0x10000001u);
        BRMerkleBlock *first  = make_header(0x10000002u, unknownParent, now, 0);
        BRMerkleBlock *second = make_header(0x10000002u, unknownParent, now, 0);   // same identity, new object
        _peerRelayedBlock(&r.info, first);
        check(BRSetCount(r.m->orphans) == 1 && r.m->lastOrphan == first, "(G)", "setup: a parentless header is held in the set");
        _peerRelayedBlock(&r.info, second);
        check(BRSetCount(r.m->orphans) == 1, "(G)", "the second delivery holds one resident entry");
        check(r.m->lastOrphan == second, "(G)", "lastOrphan names the resident header");
        check(total_is_exact(r.m), "(R)", "byte total equals the resident sum after a displacement on the relay path");
        if (r.m->lastOrphan) g_sink += r.m->lastOrphan->timestamp;
        rig_close(&r);   // `first` has its one owner already: LeakSanitizer confirms it at exit
    }

    printf("\n=== relay: a parentless header, then the header that connects it ===\n");
    {
        Rig r;
        if (! rig_open(&r, wallet, newest_checkpoint_height() + 100)) { check(0, "(G)", "setup: manager allocated"); return; }
        uint32_t h0 = r.m->lastBlock->height;
        BRMerkleBlock *child  = make_header(0x11000002u, hash_for(0x11000001u), now, 0);
        _peerRelayedBlock(&r.info, child);
        check(BRSetCount(r.m->orphans) == 1 && total_is_exact(r.m), "(R)", "held, with an exact byte total");

        BRMerkleBlock *parent = make_header(0x11000001u, r.m->lastBlock->blockHash, now, 0);
        _peerRelayedBlock(&r.info, parent);
        check(r.m->lastBlock == child && child->height == h0 + 2, "(G)", "the held header connected behind its parent");
        check(BRSetCount(r.m->orphans) == 0, "(G)", "the set is empty once its header has connected");
        check(r.m->orphanBytes == 0, "(R)", "byte total returns to zero when a header leaves the set by connecting");
        check(r.m->lastOrphan == NULL, "(R)", "lastOrphan names nothing once its header has left the set");

        // Many such cycles: the total must be exact after every one of them.
        uint32_t tag = 0x12000000u;
        size_t inexact = 0;
        for (uint32_t i = 0; i < CONNECT_CYCLES; i++, tag += 2) {
            _peerRelayedBlock(&r.info, make_header(tag + 1, hash_for(tag), now, 0));
            _peerRelayedBlock(&r.info, make_header(tag, r.m->lastBlock->blockHash, now, 0));
            if (! total_is_exact(r.m) || BRSetCount(r.m->orphans) != 0) inexact++;
        }
        printf("after %d connect cycles: resident=%zu running_total=%zu inexact_cycles=%zu\n",
               CONNECT_CYCLES, BRSetCount(r.m->orphans), r.m->orphanBytes, inexact);
        check(inexact == 0, "(R)", "byte total is exact after every connect cycle");

        // Ten unrelated parentless headers must then all be resident together.
        for (uint32_t i = 0; i < 10; i++)
            _peerRelayedBlock(&r.info, make_header(0x13000000u + 2*i + 1, hash_for(0x13000000u + 2*i), now - i, 0));
        check(BRSetCount(r.m->orphans) == 10, "(R)", "ten unrelated parentless headers are all resident after the cycles");
        check(total_is_exact(r.m), "(R)", "byte total equals the resident sum afterwards");
        rig_close(&r);
    }

    printf("\n=== relay: a full set connects in one pass on a 1 MiB stack ===\n");
    {
        Rig r;
        if (! rig_open(&r, wallet, newest_checkpoint_height() + 100)) { check(0, "(G)", "setup: manager allocated"); return; }
        CascadeJob job = { &r, now, 0, 0 };
        pthread_attr_t attr;
        pthread_t worker;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 1024*1024);
        int started = (pthread_create(&worker, &attr, cascade_worker, &job) == 0);
        pthread_attr_destroy(&attr);
        check(started, "(G)", "setup: worker started");
        if (started) pthread_join(worker, NULL);

        check(job.ok, "(G)", "setup: a contiguous chain the size of the limit is held in full");
        check(r.m->lastBlock->height == job.baseHeight + 1 + ORPHAN_SET_COUNT_MAX, "(G)",
              "every held header connected in the one pass");
        check(BRSetCount(r.m->orphans) == 0 && r.m->orphanBytes == 0, "(R)", "the set and its byte total are empty afterwards");
        check(r.m->lastOrphan == NULL, "(R)", "lastOrphan names nothing afterwards");
        rig_close(&r);
    }

    printf("\n=== relay: a header resident in both sets leaves the parentless set ===\n");
    {
        // Hand-built state (it does not arise from relayed traffic): one header object resident
        // in manager->blocks AND in manager->orphans. A second delivery of the same identity
        // replaces it in manager->blocks; the set and its total must follow.
        Rig r;
        if (! rig_open(&r, wallet, newest_checkpoint_height() + 100)) { check(0, "(G)", "setup: manager allocated"); return; }
        BRMerkleBlock *tip0 = r.m->lastBlock;
        BRMerkleBlock *h1 = make_header(0x14000001u, tip0->blockHash, now, 0);
        _peerRelayedBlock(&r.info, h1);
        BRMerkleBlock *h2 = make_header(0x14000002u, h1->blockHash, now, 0);
        _peerRelayedBlock(&r.info, h2);                       // tip is now h2, so h1 is a resident non-tip header
        _BRPeerManagerStoreOrphanLocked(r.m, h1);             // the hand-built part: h1 is now in both sets
        check(BRSetCount(r.m->orphans) == 1 && BRSetGet(r.m->blocks, &h1->blockHash) == h1, "(G)",
              "setup: the header is resident in both sets");
        check(total_is_exact(r.m), "(R)", "byte total equals the resident sum before the second delivery");

        BRMerkleBlock *h1again = make_header(0x14000001u, tip0->blockHash, now, 0);
        _peerRelayedBlock(&r.info, h1again);
        check(BRSetGet(r.m->blocks, &h1again->blockHash) == h1again, "(G)", "the second delivery is the resident copy");
        check(BRSetCount(r.m->orphans) == 0, "(G)", "the replaced object is no longer in the parentless set");
        check(r.m->orphanBytes == 0, "(R)", "byte total follows a header removed as a duplicate");
        check(r.m->lastOrphan == NULL, "(G)", "lastOrphan names nothing afterwards");
        rig_close(&r);
    }
}

// =======================================================================================
// SCENARIO "connect": a held header connects and is not kept by the same pass.
// =======================================================================================
static void scenario_connect(BRWallet *wallet)
{
    uint32_t now = (uint32_t)time(NULL);
    printf("\n=== connect: lastOrphan after its header has left the set ===\n");

    // The rig's hand-built tip sits two below the newest checkpoint height, so that the held
    // header lands exactly ON that height with a different identity: the relay path does not
    // keep it.
    uint32_t cp = newest_checkpoint_height();
    Rig r;
    if (! rig_open(&r, wallet, cp - 2)) { check(0, "(G)", "setup: manager allocated"); return; }
    BRMerkleBlock *tip = r.m->lastBlock;

    BRMerkleBlock *held = make_header(0x15000002u, hash_for(0x15000001u), now, 0);
    _peerRelayedBlock(&r.info, held);
    check(BRSetCount(r.m->orphans) == 1 && r.m->lastOrphan == held, "(G)", "setup: the header is held and named by lastOrphan");

    _peerRelayedBlock(&r.info, make_header(0x15000001u, tip->blockHash, now, 0));   // connects `held`, which is not kept
    check(r.m->lastBlock->height == cp - 1, "(G)", "setup: the connecting header is the tip and the held one was not kept");
    check(BRSetCount(r.m->orphans) == 0 && r.m->orphanBytes == 0, "(R)", "the set and its byte total are empty afterwards");
    check(r.m->lastOrphan == NULL, "(R)", "lastOrphan names nothing once its header has left the set");

    // The next parentless header is compared against lastOrphan by the request rule.
    _peerRelayedBlock(&r.info, make_header(0x15000004u, hash_for(0x15000003u), now, 0));
    check(BRSetCount(r.m->orphans) == 1, "(G)", "the next parentless header is held normally");
    check(total_is_exact(r.m), "(R)", "byte total equals the resident sum afterwards");
    rig_close(&r);
}

// =======================================================================================
// SCENARIO "rescan": the store's second call site, through the real _peerRelayedBlock.
// =======================================================================================
// The state a rescan leaves behind: manager->lastBlock has moved back while the headers above
// it stay resident in manager->blocks, and the peer has announced a best height above the tip.
// A header relayed on top of that resident chain has a known parent and a known height, is
// not the next header after the tip, and is held in the parentless-header set until the tip
// reaches its parent. The chain is built through the real relay path; the move back itself is
// by hand (BRPeerManagerRescan needs a connected manager, and moving the tip pointer is all it
// does to these two sets).
typedef struct { Rig rig; BRMerkleBlock *tip0, *top; int ok; } RescanRig;

#define RESCAN_ANNOUNCED_AHEAD 1000u   // how far above the chain's top the announced best height sits

static void rig_move_tip(Rig *r, BRMerkleBlock *to) {
    r->m->lastBlock = to;
    r->m->floorMemoValid = 0;   // the floor memo is keyed by the tip
}

// members (optional) receives the chainLen headers of the resident chain, lowest first.
static int rescan_rig_open(RescanRig *rr, BRWallet *wallet, uint32_t tagBase, uint32_t chainLen,
                           uint32_t now, BRMerkleBlock **members)
{
    if (! rig_open(&rr->rig, wallet, newest_checkpoint_height() + 100)) return 0;
    Rig *r = &rr->rig;

    rr->tip0 = r->m->lastBlock;
    for (uint32_t i = 0; i < chainLen; i++) {
        BRMerkleBlock *h = make_header(tagBase + i, r->m->lastBlock->blockHash, now, 0);
        _peerRelayedBlock(&r->info, h);
        if (members) members[i] = h;
    }
    rr->top = r->m->lastBlock;
    rr->ok  = (rr->top->height == rr->tip0->height + chainLen) && BRSetCount(r->m->orphans) == 0;

    uint32_t announced = rr->top->height + RESCAN_ANNOUNCED_AHEAD;
    rr->ok = rr->ok && kat_peer_accept_version(r->peer, announced) && BRPeerLastBlock(r->peer) == announced;

    rig_move_tip(r, rr->tip0);   // the tip moves back; the chain above it stays resident
    return 1;
}

static void scenario_rescan(BRWallet *wallet)
{
    uint32_t now = (uint32_t)time(NULL);

    printf("\n=== rescan: two headers above the tip sharing one resident parent, then the tip catches up ===\n");
    {
        const uint32_t tagBase = 0x16000000u, chainLen = 3;
        RescanRig rr;
        if (! rescan_rig_open(&rr, wallet, tagBase, chainLen, now, NULL)) { check(0, "(G)", "setup: manager allocated"); return; }
        Rig *r = &rr.rig;
        check(rr.ok, "(G)", "setup: resident chain built through the relay path, best height announced above it, tip moved back");

        const uint32_t topHeight  = rr.top->height;
        const UInt256  topParent  = rr.top->prevBlock;   // copied now: the top is delivered again below
        const UInt256  topHash    = rr.top->blockHash;

        BRMerkleBlock *first = make_header(0x16100001u, topHash, now, 0);
        _peerRelayedBlock(&r->info, first);
        check(BRSetCount(r->m->orphans) == 1 && r->m->lastOrphan == first, "(G)", "setup: the header is held in the set");
        check(first->height == topHeight + 1, "(G)",
              "setup: it is held with its height known (the second call site; the first holds a header of unknown height)");
        check(r->m->lastBlock == rr.tip0 && ! BRSetContains(r->m->blocks, first), "(G)",
              "setup: it neither moved the tip nor joined the chain set");
        check(total_is_exact(r->m), "(R)", "byte total equals the resident sum after an insert at the second call site");

        BRMerkleBlock *second = make_header(0x16100002u, topHash, now, 0);   // another identity, same parent
        _peerRelayedBlock(&r->info, second);
        check(BRSetCount(r->m->orphans) == 1, "(G)", "two headers sharing a resident parent hold one resident entry");
        check(r->m->lastOrphan == second && BRSetGet(r->m->orphans, second) == second, "(G)", "lastOrphan names the resident header");
        check(total_is_exact(r->m), "(R)", "byte total equals the resident sum after a displacement at the second call site");
        if (r->m->lastOrphan) g_sink += r->m->lastOrphan->timestamp;   // read through lastOrphan under ASan
        // `first` has its one owner already: a second release is an AddressSanitizer report
        // right here, and no release at all is a LeakSanitizer report at exit.

        // The tip returns to the top of the resident chain (the counterpart of the move back),
        // and the top is delivered again: the held header connects behind it.
        rig_move_tip(r, rr.top);
        _peerRelayedBlock(&r->info, make_header(tagBase + chainLen - 1, topParent, now, 0));
        check(r->m->lastBlock == second && second->height == topHeight + 1, "(G)",
              "the held header connected once the tip reached its parent");
        check(BRSetCount(r->m->orphans) == 0 && r->m->orphanBytes == 0, "(G)", "the set and its byte total are empty afterwards");
        check(r->m->lastOrphan == NULL, "(R)", "lastOrphan names nothing once its header has left the set");
        rig_close(r);
    }

    printf("\n=== rescan: more such headers than the count limit ===\n");
    {
        enum { CHAIN = ORPHAN_SET_COUNT_MAX + 50 };
        BRMerkleBlock **members = calloc(CHAIN, sizeof(*members));
        RescanRig rr;
        if (! members || ! rescan_rig_open(&rr, wallet, 0x17000000u, CHAIN, now, members)) {
            free(members);
            check(0, "(G)", "setup: manager allocated");
            return;
        }
        Rig *r = &rr.rig;
        check(rr.ok, "(G)", "setup: resident chain built through the relay path, best height announced above it, tip moved back");

        // One header on top of each member of the resident chain, newest last: each has its own
        // resident parent and a known height above the one after the tip.
        size_t worstCount = 0, notResident = 0, inexact = 0, heightUnknown = 0;
        for (uint32_t i = 0; i < CHAIN; i++) {
            BRMerkleBlock *o = make_header(0x17100000u + i, members[i]->blockHash, now - (CHAIN - i), 0);
            _peerRelayedBlock(&r->info, o);
            size_t c = BRSetCount(r->m->orphans);
            if (c > worstCount) worstCount = c;
            if (r->m->lastOrphan != o || BRSetGet(r->m->orphans, o) != o) notResident++;
            else if (o->height != members[i]->height + 1) heightUnknown++;
            if (! total_is_exact(r->m)) inexact++;
        }
        printf("after %d headers above the tip: resident=%zu worst_resident=%zu running_total=%zu inexact=%zu (count limit=%u)\n",
               (int)CHAIN, BRSetCount(r->m->orphans), worstCount, r->m->orphanBytes, inexact, (unsigned)ORPHAN_SET_COUNT_MAX);
        check(r->m->lastBlock == rr.tip0 && heightUnknown == 0, "(G)", "setup: every one was held with its height known, and none moved the tip");
        check(worstCount <= ORPHAN_SET_COUNT_MAX, "(R)", "count stays within the fixed upper limit at the second call site");
        check(inexact == 0, "(R)", "byte total equals the resident sum after every store and eviction at the second call site");
        check(notResident == 0, "(G)", "the header just stored is resident, and named by lastOrphan, after every eviction pass");
        if (r->m->lastOrphan) g_sink += r->m->lastOrphan->timestamp;
        free(members);
        rig_close(r);
    }
}

int main(int argc, char **argv)
{
    // Unbuffered: a sanitizer ends the process without flushing stdio, and the captured
    // output of the reference arm must stay complete.
    setvbuf(stdout, NULL, _IONBF, 0);
    const char *only = (argc > 1) ? argv[1] : "";
#if defined(ORPHAN_SET_LIMITS_UNFIXED) && ORPHAN_SET_LIMITS_UNFIXED
    printf("ARM: REFERENCE (earlier store, connect step and request rule) scenario=%s\n", *only ? only : "all");
#else
    printf("ARM: FIXED (limited set, one owner on exit, exact total, spaced request) scenario=%s\n", *only ? only : "all");
#endif

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;   // non-zero: wallet setup requires a usable master key
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("orphan_set_limits_kat: FAIL (setup: no wallet)\n"); return 1; }

    if (! *only || strcmp(only, "limits") == 0)  scenario_limits();
    if (! *only || strcmp(only, "relay") == 0)   scenario_relay(wallet);
    if (! *only || strcmp(only, "connect") == 0) scenario_connect(wallet);
    if (! *only || strcmp(only, "rescan") == 0)  scenario_rescan(wallet);

    BRWalletFree(wallet);

    if (g_fail) { printf("\norphan_set_limits_kat: FAIL (sink=%llu)\n", (unsigned long long)g_sink); return 1; }
    printf("\norphan_set_limits_kat: PASS (sink=%llu)\n", (unsigned long long)g_sink);
    return 0;
}
