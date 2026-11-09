// Host KAT: G5-QUORUM, part 3 -- no lone-peer re-anchor above the top compiled checkpoint.
//
// INVARIANT. When only one filter peer is connected and its cfheaders keep failing the continuity
// check against our chain at a height ABOVE the top compiled checkpoint (mainnet), the
// single-peer escape hatch in _peerRelayedCFHeaders does not re-anchor on that one peer's word.
// It records the stall (cfLoneStallCount) and keeps the chain until a second, distinct peer has
// claimed the same prevFilterHeader from a distinct address (ports on one host count once). Exempt, and unchanged: the user's own node in exclusive mode
// (BRPeerManagerSetPinnedPeer(..., exclusive=1) with the lone peer as the pin) -- trusted by the
// user and by design the only peer -- still re-anchors after CF_SINGLE_PEER_REANCHOR_ROUNDS.
// A pinned node that is NOT exclusive is not exempt (other peers can be dialled).
//
// Rig: the cf_checkpoint_veto_kat idiom. BRPeerManager.c is #include-d for its statics; synthetic
// BRPeerNew() peers that are never added to connectedPeers (so the connected filter-peer count is
// 0, the hatch branch), an unregistered stop hash (the alignment guard is a no-op), a chain
// anchored just above the top compiled checkpoint (read from the real table), and a resident
// floor block so a re-anchor has somewhere to go. No socket is touched.
//
// ==== RED-BEFORE-GREEN GATE ====
// main() runs every test with no #ifdef branching. run.sh builds twice:
//   RED   -DCF_LONE_PEER_REANCHOR_UNFIXED restores the earlier hatch (any lone peer re-anchors
//         after the round count). It must fail EXACTLY the lone / not-exclusive / split / one-host checks.
//   GREEN every check passes.
// GUARD (same outcome in both arms): own-node exclusive re-anchors; a divergence corroborated by
// a second distinct peer re-anchors.
//
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

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

typedef struct {
    BRWallet *wallet;
    BRPeerManager *manager;
    uint32_t start, floor;
} Rig;

static void rigInit(Rig *r)
{
    memset(r, 0, sizeof(*r));
    r->wallet = makeWallet();
    r->manager = BRPeerManagerNew(&BRMainNetParams, r->wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(r->manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    r->floor = 24000000;
    BRMerkleBlock *floorBlock = BRMerkleBlockNew();
    memset(floorBlock->blockHash.u8, 0xBB, sizeof(floorBlock->blockHash.u8));
    floorBlock->height = r->floor;
    BRSetAdd(r->manager->blocks, floorBlock);
    r->manager->lastBlock = floorBlock;

    r->start = BRCFTopCheckpointHeight() + 1;
    UInt256 anchor = u256_fill(0xC2);
    r->manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, r->start, anchor);
    UInt256 prime[5];
    for (int i = 0; i < 5; i++) prime[i] = u256_fill((uint8_t)(0x40 + i));
    check(BRCompactFilterChainAppend(r->manager->compactFilterChain, anchor, prime, 5) == 1,
          "setup: priming append above the top compiled checkpoint succeeds");
    check(BRCompactFilterChainNextHeight(r->manager->compactFilterChain) > BRCFTopCheckpointHeight(),
          "setup: the contested height lies above the top compiled checkpoint");
}

static void rigFree(Rig *r) { BRPeerManagerFree(r->manager); BRWalletFree(r->wallet); }

static BRPeer *lonePeer(uint8_t addrByte, uint16_t port)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = addrByte;
    p->port = port;
    return p;
}

// One diverged cfheaders round from `p` claiming `prev`.
static void diverge(Rig *r, BRPeer *p, UInt256 prev)
{
    UInt256 batch[5];
    for (int i = 0; i < 5; i++) batch[i] = u256_fill((uint8_t)(0x90 + i));
    BRPeerCallbackInfo info = { .peer = p, .manager = r->manager, .hash = UINT256_ZERO };
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, u256_fill(0xEF), prev, batch, 5);
}

static int chainIntact(const Rig *r)
{
    return r->manager->compactFilterChain != NULL &&
           BRCompactFilterChainStartHeight(r->manager->compactFilterChain) == r->start &&
           BRCompactFilterChainCount(r->manager->compactFilterChain) == 5 &&
           r->manager->cfReanchorCount == 0;
}

static int reanchored(const Rig *r)
{
    return r->manager->compactFilterChain == NULL && r->manager->cfReanchorCount == 1 &&
           r->manager->autoFetchCFiltersStart == r->floor;
}

// RED/GREEN: a lone ordinary peer diverging for twice the round count never re-anchors; each
// refused round past the threshold is recorded; nobody is banned.
static void test_lone_peer_keeps_chain(void)
{
    Rig r; rigInit(&r);
    BRPeer *p = lonePeer(0x01, 12024);
    for (int round = 0; round < 2 * CF_SINGLE_PEER_REANCHOR_ROUNDS; round++) diverge(&r, p, u256_fill(0xDF));
    check(chainIntact(&r), "lone: a lone peer above the compiled table never re-anchors the chain");
    check(r.manager->cfLoneStallCount == CF_SINGLE_PEER_REANCHOR_ROUNDS + 1,
          "lone: every refused round from the threshold on is recorded as a stall");
    check(r.manager->misbehavinCount == 0, "lone: the peer is not banned for it either");
    assertLockNotHeld(r.manager, "lone: manager->lock released");
    rigFree(&r); BRPeerFree(p);
    printf("test_lone_peer_keeps_chain: done\n");
}

// RED/GREEN: a pinned node that is not exclusive is an ordinary peer here.
static void test_pinned_not_exclusive_is_not_exempt(void)
{
    Rig r; rigInit(&r);
    BRPeer *p = lonePeer(0x02, 12024);
    BRPeerManagerSetPinnedPeer(r.manager, p->address, p->port, /*exclusive=*/0);
    for (int round = 0; round < CF_SINGLE_PEER_REANCHOR_ROUNDS; round++) diverge(&r, p, u256_fill(0xDF));
    check(chainIntact(&r), "not-exclusive: a pinned but non-exclusive lone peer does not re-anchor");
    rigFree(&r); BRPeerFree(p);
    printf("test_pinned_not_exclusive_is_not_exempt: done\n");
}

// RED/GREEN: two lone peers in turn, describing DIFFERENT alternate chains, corroborate nothing.
static void test_split_lone_peers_do_not_corroborate(void)
{
    Rig r; rigInit(&r);
    BRPeer *a = lonePeer(0x03, 12024), *b = lonePeer(0x04, 12024);
    diverge(&r, a, u256_fill(0xD1));
    for (int round = 1; round < CF_SINGLE_PEER_REANCHOR_ROUNDS + 1; round++) diverge(&r, b, u256_fill(0xD2));
    check(chainIntact(&r), "split: lone peers that disagree with each other do not re-anchor");
    rigFree(&r); BRPeerFree(a); BRPeerFree(b);
    printf("test_split_lone_peers_do_not_corroborate: done\n");
}

// GUARD: the user's own node in exclusive mode keeps the hatch, at the same round.
static void test_own_node_exclusive_keeps_hatch(void)
{
    Rig r; rigInit(&r);
    BRPeer *p = lonePeer(0x05, 12024);
    BRPeerManagerSetPinnedPeer(r.manager, p->address, p->port, /*exclusive=*/1);
    for (int round = 0; round < CF_SINGLE_PEER_REANCHOR_ROUNDS - 1; round++) diverge(&r, p, u256_fill(0xDF));
    check(chainIntact(&r), "own-node: no re-anchor before the round count");
    diverge(&r, p, u256_fill(0xDF));
    check(reanchored(&r), "own-node: the exclusive own node re-anchors at the round count, as before");
    check(r.manager->cfLoneStallCount == 0, "own-node: no stall recorded");
    assertLockNotHeld(r.manager, "own-node: manager->lock released");
    rigFree(&r); BRPeerFree(p);
    printf("test_own_node_exclusive_keeps_hatch: done\n");
}

// GUARD: a second distinct peer claiming the same prevFilterHeader corroborates the divergence.
static void test_corroborated_divergence_reanchors(void)
{
    Rig r; rigInit(&r);
    BRPeer *a = lonePeer(0x06, 12024), *b = lonePeer(0x08, 12024);   // two hosts
    diverge(&r, a, u256_fill(0xD3));
    for (int round = 1; round < CF_SINGLE_PEER_REANCHOR_ROUNDS; round++) diverge(&r, b, u256_fill(0xD3));
    check(reanchored(&r), "corroborated: two distinct hosts on the same alternate chain re-anchor at the round count");
    rigFree(&r); BRPeerFree(a); BRPeerFree(b);
    printf("test_corroborated_divergence_reanchors: done\n");
}

// RED/GREEN: ports on one host are one host's word -- two ports of one address in turn do not corroborate.
static void test_ports_on_one_host_do_not_corroborate(void)
{
    Rig r; rigInit(&r);
    BRPeer *a = lonePeer(0x09, 12024), *b = lonePeer(0x09, 12025);   // one host, two ports
    diverge(&r, a, u256_fill(0xD4));
    for (int round = 1; round < CF_SINGLE_PEER_REANCHOR_ROUNDS + 1; round++) diverge(&r, b, u256_fill(0xD4));
    check(chainIntact(&r), "one-host: two ports of one address do not corroborate a lone divergence");
    rigFree(&r); BRPeerFree(a); BRPeerFree(b);
    printf("test_ports_on_one_host_do_not_corroborate: done\n");
}

int main(void)
{
    test_lone_peer_keeps_chain();
    test_pinned_not_exclusive_is_not_exempt();
    test_split_lone_peers_do_not_corroborate();
    test_own_node_exclusive_keeps_hatch();
    test_corroborated_divergence_reanchors();
    test_ports_on_one_host_do_not_corroborate();
    printf(g_fail ? "cf_lone_peer_quorum_kat: %d FAILURE(S)\n" : "cf_lone_peer_quorum_kat: all checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
