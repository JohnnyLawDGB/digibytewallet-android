// Host KAT for the BLOCK-CHECKPOINT TABLE in BRChainParams.h.
//
// WHY THIS GATE EXISTS
// --------------------
// The NEWEST mainnet block checkpoint sets a fresh wallet's BIP158 compact-filter
// SCAN FLOOR. SyncService.kt: on a fresh install there are no saved blocks, so
// `tip = getWalletBirthCheckpointHeight()` resolves to the newest checkpoint and
// the scan is armed at `tip - 100`. Every block between that checkpoint and the
// real chain tip becomes a cfilter the wallet downloads and evaluates (~700 bytes
// each) looking for transactions that CANNOT EXIST — the wallet was created after
// them.
//
// jni_wallet.c describes this anchor as "a short, bounded catch-up that
// self-updates as checkpoints ship and never goes stale". That property is
// conditional on the checkpoints actually shipping. In 2026-08 the table was found
// 298,962 blocks stale (newest checkpoint dated 2026-06-12), which had silently
// turned every newly created wallet into a ~290k-filter / ~200 MB scan: precisely
// the "ever-growing span … hunting for transactions that cannot exist before the
// wallet was created" that the anchor was written to prevent. Nothing caught it,
// because a stale constant table looks exactly like a fresh one.
//
// This KAT is the thing that catches it. It is DELIBERATELY TIME-DEPENDENT: it
// will go red on its own as the table ages, with no code change. That is the
// alarm, not a flake. Clear it with:  scripts/gen_block_checkpoints.sh
//
// RED-BEFORE-GREEN: this gate's subject is DATA, not code, so it needs no
// -D..._UNFIXED arm — it fails on the stale shipped table and passes on a
// regenerated one. Both directions are demonstrated by running it before and
// after the generator.
//
// Scope note: the staleness and density checks are MAINNET-ONLY. BRTestNetCheckpoints
// pins a dev network (testnet26) that is deliberately not tracked at release cadence;
// asserting recency there would be permanently and uselessly red. Testnet still gets
// the structural checks, which must hold for any checkpoint table.

#include <stdio.h>
#include <stdint.h>
#include <time.h>
#include "BRChainParams.h"

// ---- thresholds --------------------------------------------------------------
// DigiByte targets 15-second blocks => 5,760 blocks/day. A basic BIP158 filter is
// ~700 bytes on the wire, and a fresh wallet must fetch and evaluate one per block
// between the newest checkpoint and the tip. So the staleness budget is a DIRECT
// choice about how much pointless work a brand-new wallet does:
//
//     days |    blocks | cfilters to fetch | approx bytes
//        7 |    40,320 |            40,320 |    ~28 MB
//       14 |    80,640 |            80,640 |    ~56 MB
//       21 |   120,960 |           120,960 |    ~85 MB
//       60 |   345,600 |           345,600 |   ~242 MB   <-- what shipped in 2026-08
//
// FAIL at 21 days. It cannot usefully go lower: BRPeerManager.c:4754 only selects a
// checkpoint whose timestamp is at least 7 days older than the wallet's earliest key
// time, so ~7 days of span is structural and no table can beat it.
// WARN at 14 days so the table gets refreshed on a normal release cadence instead of
// first becoming visible as a red CI run.
#define CKPT_MAX_STALE_DAYS   21
#define CKPT_WARN_STALE_DAYS  14
#define DGB_BLOCKS_PER_DAY    5760   /* 86400s / 15s target spacing */

// MINIMUM age — the other side of the bound, and the more dangerous one.
//
// A checkpoint is not a hint. A relayed block whose hash differs from the checkpoint
// at its height fails _BRPeerManagerVerifyBlock (BRPeerManager.c:1715-1722) and the
// caller runs _BRPeerManagerPeerMisbehavin, which REMOVES that peer from
// manager->peers and DISCONNECTS it — and after 10 such events wipes the stored peer
// list entirely. So a checkpoint pinned to a block that later REORGS OUT does not
// degrade gracefully: every honest peer serving the canonical chain gets banned and
// the wallet takes itself off the network.
//
// DigiByte targets 15-second blocks across five mining algos, and short reorgs onto
// abandoned algo branches are a documented wedge class in this project. A checkpoint
// hours below the tip is inside that horizon.
//
// This costs nothing. getWalletBirthCheckpointHeight (jni_peer.c:1018-1034) only ever
// selects a checkpoint at least 7 days OLDER than the wallet's creation time, so an
// entry younger than that can never be chosen as a birth anchor — it would be pure
// reorg risk for zero reduction in scan span. 7 days is therefore both the safety
// floor and the point past which extra freshness is literally unusable.
#define CKPT_MIN_AGE_DAYS      7

// Density bound. Staleness alone only protects NEWLY CREATED wallets. A RECOVERY
// wallet floors at the newest checkpoint at least 7 days older than its birth time
// (BRPeerManager.c:4754-4755), so the worst-case over-scan for a recovery is the
// largest GAP between adjacent checkpoints — which in the 2026-08 table reached
// 1,565,000 blocks — the gap [1,435,000..3,000,000], ~1 GB of unmatchable filters.
// (41 of that table's 58 gaps exceeded 50k; the 313k figure an earlier draft of this
// comment carried was the largest gap NEAR THE TIP, not the largest in the table.)
// Holding the grid at 50k caps that over-scan at ~35 MB.
#define CKPT_MAX_GAP_BLOCKS   50000

static int g_failures = 0;

static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

// Structural invariants that must hold for ANY checkpoint table on any network.
static void check_structure(const char *net, const BRCheckPoint *cp, size_t n)
{
    char buf[256];

    snprintf(buf, sizeof buf, "%s: table is non-empty", net);
    check(n > 0, buf);
    if (n == 0) return;

    // BRPeerManager.c:68  genesis_block_hash(params) == UInt256Reverse(checkpoints[0].hash)
    // If [0] is not genesis, the manager's notion of the genesis block is silently wrong.
    snprintf(buf, sizeof buf, "%s: checkpoints[0] is genesis (height 0) — genesis_block_hash depends on it", net);
    check(cp[0].height == 0, buf);

    // Ascending + unique. BRPeerManager.c:1925 uses checkpoints[count-1].height as "the
    // newest checkpoint", and :4754 walks the array assuming increasing time/height.
    int ascending = 1, ts_sane = 1;
    for (size_t i = 1; i < n; i++) {
        if (cp[i].height <= cp[i - 1].height) ascending = 0;
        // Block timestamps are only median-time-past monotonic, so adjacent BLOCKS may
        // step backwards; across a 50k-block gap they cannot. Non-decreasing is the
        // honest bound — it still catches transposed or garbage rows.
        if (cp[i].timestamp < cp[i - 1].timestamp) ts_sane = 0;
    }
    snprintf(buf, sizeof buf, "%s: heights strictly ascending (and therefore unique)", net);
    check(ascending, buf);
    snprintf(buf, sizeof buf, "%s: timestamps non-decreasing", net);
    check(ts_sane, buf);

    // A zero target would make the seeded BRMerkleBlock (BRPeerManager.c:2333) nonsense.
    int targets_set = 1;
    for (size_t i = 0; i < n; i++) if (cp[i].target == 0) targets_set = 0;
    snprintf(buf, sizeof buf, "%s: every checkpoint carries a non-zero target (nBits)", net);
    check(targets_set, buf);
}

int main(void)
{
    const size_t nMain = sizeof(BRMainNetCheckpoints) / sizeof(BRMainNetCheckpoints[0]);
    const size_t nTest = sizeof(BRTestNetCheckpoints) / sizeof(BRTestNetCheckpoints[0]);

    printf("mainnet checkpoints: %zu\ntestnet checkpoints: %zu\n\n", nMain, nTest);

    check_structure("mainnet", BRMainNetCheckpoints, nMain);
    check_structure("testnet", BRTestNetCheckpoints, nTest);
    printf("\n");

    if (nMain == 0) {
        printf("\n%d FAILURE(S)\n", g_failures ? g_failures : 1);
        return 1;
    }

    // ---- density (mainnet): bounds a RECOVERY wallet's over-scan ----
    uint32_t maxGap = 0, gapLo = 0, gapHi = 0;
    for (size_t i = 1; i < nMain; i++) {
        uint32_t gap = BRMainNetCheckpoints[i].height - BRMainNetCheckpoints[i - 1].height;
        if (gap > maxGap) {
            maxGap = gap;
            gapLo  = BRMainNetCheckpoints[i - 1].height;
            gapHi  = BRMainNetCheckpoints[i].height;
        }
    }
    printf("largest mainnet checkpoint gap: %u blocks [%u..%u] (cap %u)\n",
           maxGap, gapLo, gapHi, (unsigned)CKPT_MAX_GAP_BLOCKS);
    if (maxGap > CKPT_MAX_GAP_BLOCKS) {
        printf("  -> a recovery wallet born just after height %u floors at %u and\n"
               "     over-scans up to %u cfilters (~%u MB) it can never match.\n",
               gapLo, gapLo, maxGap, (unsigned)((uint64_t)maxGap * 700 / (1024 * 1024)));
    }
    check(maxGap <= CKPT_MAX_GAP_BLOCKS,
          "mainnet: no gap between adjacent checkpoints exceeds CKPT_MAX_GAP_BLOCKS");

    // ---- staleness (mainnet): bounds a NEW wallet's over-scan ----
    const BRCheckPoint *newest = &BRMainNetCheckpoints[nMain - 1];
    const time_t now = time(NULL);
    const int64_t ageSec = (int64_t)now - (int64_t)newest->timestamp;
    const int64_t ageDays = ageSec / (24 * 60 * 60);

    printf("\nnewest mainnet checkpoint: height %u, timestamp %u (%lld days old)\n",
           newest->height, newest->timestamp, (long long)ageDays);

    // A checkpoint timestamped in the future means bad data, not a fresh table.
    check(ageSec >= 0, "mainnet: newest checkpoint is not timestamped in the future");

    // TOO FRESH — the reorg hazard. See CKPT_MIN_AGE_DAYS.
    if (ageDays < CKPT_MIN_AGE_DAYS) {
        printf("\n"
               "  The newest block checkpoint is only %lld days old — INSIDE the reorg\n"
               "  horizon. If that block is ever reorged out, every honest peer relaying\n"
               "  the canonical chain fails the checkpoint compare and is DISCONNECTED and\n"
               "  REMOVED (_BRPeerManagerPeerMisbehavin); 10 of those wipe the peer list\n"
               "  and the wallet takes itself off the network.\n"
               "\n"
               "  It also buys nothing: getWalletBirthCheckpointHeight only selects\n"
               "  checkpoints at least 7 days older than the wallet's creation time, so\n"
               "  this entry can never be chosen as a birth anchor.\n"
               "\n"
               "  FIX:  scripts/gen_block_checkpoints.sh   (its MIN_DEPTH stops the grid\n"
               "        ~50,000 blocks / ~8.7 days below the tip)\n"
               "\n", (long long)ageDays);
    }
    check(ageDays >= CKPT_MIN_AGE_DAYS,
          "mainnet: newest checkpoint is at least CKPT_MIN_AGE_DAYS old (outside the reorg horizon)");

    if (ageDays >= CKPT_WARN_STALE_DAYS && ageDays < CKPT_MAX_STALE_DAYS) {
        printf("WARN: checkpoint table is %lld days old (warn at %d, fail at %d).\n"
               "      Refresh it on the next release: scripts/gen_block_checkpoints.sh\n",
               (long long)ageDays, CKPT_WARN_STALE_DAYS, CKPT_MAX_STALE_DAYS);
    }

    if (ageDays > CKPT_MAX_STALE_DAYS) {
        int64_t blocks = ageDays * DGB_BLOCKS_PER_DAY;
        printf("\n"
               "  The newest block checkpoint is %lld days old (~%lld blocks behind the\n"
               "  chain tip). Every NEW wallet created against this build will arm its\n"
               "  BIP158 scan at that checkpoint and fetch ~%lld cfilters (~%lld MB)\n"
               "  searching for transactions that cannot exist.\n"
               "\n"
               "  FIX:  scripts/gen_block_checkpoints.sh\n"
               "        (regenerates BRMainNetCheckpoints[] from the operator full node;\n"
               "         it refuses to write unless the node reproduces every shipped\n"
               "         checkpoint byte-for-byte)\n"
               "\n",
               (long long)ageDays, (long long)blocks, (long long)blocks,
               (long long)((uint64_t)blocks * 700 / (1024 * 1024)));
    }
    check(ageDays <= CKPT_MAX_STALE_DAYS,
          "mainnet: newest checkpoint is within CKPT_MAX_STALE_DAYS of now");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
