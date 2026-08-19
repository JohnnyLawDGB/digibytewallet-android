// Host KAT: an in-process peer-manager RECREATE at height H must resume at ~H
// on BOTH the block side and the CF-scan side — Task 3 of the
// recreate-resumes-near-tip plan
// (.superpowers/sdd/2026-08-11-recreate-resumes-near-tip/task-3-brief.md).
// This is the plan's load-bearing BEHAVIORAL GATE: Tasks 2 and 4 carry the fix,
// this file is what says whether the fix works.
//
// ============================================================================
// THE BUG (empirically reproduced on a Galaxy Note 8 — these are OBSERVED
// values, not hypotheticals)
// ============================================================================
// A synced wallet goes through a background/Doze stretch, peers drop to 0, and
// the recovery path calls NativeBridge.forceReconnect() then
// NativeBridge.startSync(). The native manager is freed and rebuilt in-process.
//
//   BEFORE:  last block 23,060,000, 8 peers.
//   AFTER:   last block 22,690,000; CF scan frontier 22,657,000; and the
//            smoking gun in logcat:
//              "BIP158: recreate — re-armed auto-fetch from remembered start
//               22650000"       (22,650,000 == cf_birth_height)
//
// BOTH halves collapse to the wallet's birth region. The user sees "my wallet
// resynced from scratch".
//
// TWO mechanisms, both confirmed by source reading of bridge/jni_peer.c:
//
//   (1) BLOCK SIDE. startSync's ownership transfer NULLs the bridge's saved-
//       block array after handing its elements to the manager
//       (jni_peer.c:888-893: `free(g_savedBlocks); g_savedBlocks = NULL;
//       g_savedBlocksCount = 0;`). NOTHING refreshes it afterwards, so the
//       rebuild at jni_peer.c:850-857 passes blocks=NULL/count=0 to
//       BPPeerManagerMainNetNew. With no persisted run to sit on,
//       BRPeerManagerNewEx's lastBlock re-derives from the hardcoded
//       checkpoint table via earliestKeyTime (== g_walletCreationTime) —
//       i.e. the wallet's BIRTH checkpoint, hundreds of thousands of blocks
//       below the live tip.
//   (2) CF SIDE. _applyPendingBip158State's sticky re-arm
//       (jni_peer.c:1383-1387) calls BRPeerManagerEnableAutoCompactFilterFetch
//       at g_autoFetchStartRemembered (== cf_birth_height) and the recreate
//       path never restores the PERSISTED near-tip scan ledger. Enable calls
//       BRCFScanLedgerInit(&cfLedger, start), so the scan frontier is reset to
//       the birth floor and the whole birth->tip descent restarts.
//
// ============================================================================
// WHAT IS REAL CORE CODE HERE vs. WHAT IS MIRRORED
// ============================================================================
// REAL (this file #include-s BRPeerManager.c, the same pattern
// cf_scan_ledger_drive_kat / cf_checkpoint_enforce_kat / cf_confirm_kat use, so
// the file-static internals and BRPeerManagerStruct are visible):
//   * BRPeerManagerNewEx (via the real BPPeerManagerMainNetNew wrapper the
//     bridge itself calls) — the function that actually decides lastBlock. Both
//     arms drive the REAL one; the only difference between them is the blocks
//     array handed to it, which is exactly the production difference.
//   * The REAL BRMainNetParams checkpoint table — the birth checkpoint this
//     KAT expects is READ OUT of it at runtime, not hardcoded, so a checkpoint
//     regeneration cannot silently invalidate the gate.
//   * BRPeerManagerLastBlockHeight (the real cached-status accessor the UI and
//     the Kotlin watchdogs read).
//   * BRPeerManagerEnableAutoCompactFilterFetch, including its resolvability
//     clamp and its BRCFScanLedgerInit call.
//   * BRPeerManagerCFLedgerSerialize / BRPeerManagerCFLedgerRestore /
//     BRPeerManagerSnapAutoFetchThroughToScanFrontier /
//     BRPeerManagerLowestNeededHeight — the persisted-ledger round trip and the
//     documented CF fetch frontier.
//   * A real BRWallet (BRWalletNew over a BIP84 master pubkey), as
//     BRPeerManagerNewEx requires.
//
// MIRRORED (NOT #include-d) — bridge/jni_peer.c's orchestration, because
// jni_peer.c pulls in <jni.h> and <android/log.h> and cannot be host-compiled.
// This is the same documented trade-off as saveblocks_race_kat and the sibling
// saved_blocks_reentrant_kat (Task 1 of this plan). Mirrored verbatim in shape:
//   * the g_savedBlocks / g_savedBlocksCount / g_peerManagerNeedsRecreate /
//     g_autoFetchRemembered / g_autoFetchStartRemembered globals,
//   * forceReconnect (jni_peer.c:696-706),
//   * startSync's recreate branch + rebuild + ownership transfer
//     (jni_peer.c:764-786, 845-893),
//   * _applyPendingBip158State's SetSyncMode + sticky CF re-arm
//     (jni_peer.c:1319-1388),
//   * SyncService's reloadSavedBlocksNearTip / restoreCfLedgerAndSnab helpers
//     extracted by Task 2 (SyncService.kt), including their ORDERING contract:
//     the ledger restore + cursor snap must run AFTER startSync.
// => If any later task edits those jni_peer.c lines, re-verify BY SOURCE
//    READING; this KAT will not catch a drift in the mirrored half (the same
//    caveat the Task 1 report carried forward).
//
// The persisted saved-block snapshot is likewise modeled as a (tip, count)
// descriptor materialized into a real BRMerkleBlock run rather than a wire
// blob — the serialize/deserialize round trip is already covered by
// saved_blocks_kat and saved_blocks_reentrant_kat, and re-testing it here would
// only dilute what this gate is for.
//
// ============================================================================
// RED / GREEN
// ============================================================================
// Every assertion below is UNCONDITIONAL and asserts the FIXED outcome — the
// source is byte-identical between the two builds (same discipline as
// cf_checkpoint_enforce_kat). Only the MIRRORED orchestration branches:
//
//   default (GREEN)                the fixed sequence Tasks 2/4 wire up —
//                                  reloadSavedBlocksNearTip() repopulates
//                                  g_savedBlocks from the persisted near-tip
//                                  snapshot BEFORE the rebuild, and
//                                  restoreCfLedgerAndSnap() restores the
//                                  persisted CF ledger + snaps the cursor AFTER
//                                  startSync. Must resume at H / at the
//                                  persisted scan frontier, and exit 0.
//
//   -DRECREATE_NEARTIP_UNFIXED     the shipped pre-fix sequence — bare
//     (RED)                        forceReconnect() + startSync() with
//                                  g_savedBlocks still NULLed by the previous
//                                  ownership transfer, and no ledger restore.
//                                  Must FAIL both the block-side and the
//                                  CF-side assertions, and must do so by
//                                  landing exactly on the birth checkpoint /
//                                  cf_birth_height — which it announces on
//                                  the COLLAPSE-CONFIRMED lines run.sh greps.
//
// A gate that passes on both arms proves nothing, so run.sh asserts the RED arm
// failed on those SPECIFIC assertions, that its arm-independent controls still
// passed (harness sanity), and that it never printed ALL PASS.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>

// --- CF abandonment WARN capture -------------------------------------------
// BRPeerManager.c #ifndef-guards CF_RETENTION_WLOG so a host KAT can capture the
// "[CF-SCAN] ABANDONED ..." line the C-1 surfacing path emits. Captured (and
// echoed) here purely as EVIDENCE in the log — no assertion keys on it. See the
// note on the surfacing path in test_recreate_resumes_near_tip.
static int  g_wlogCount = 0;
static int  test_cf_retention_wlog(const char *fmt, ...)
{
    char line[512];
    va_list ap; va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    g_wlogCount++;
    printf("WLOG> %s", line);
    return 0;
}
#define CF_RETENTION_WLOG(...) test_cf_retention_wlog(__VA_ARGS__)

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRCrypto.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// ---------------------------------------------------------------------------
// Scenario constants — the OBSERVED Note 8 values, used verbatim.
// ---------------------------------------------------------------------------

// Observed "last block" BEFORE the recreate (8 peers, wallet near tip).
#define H_NEAR_TIP            23060000u

// Observed cf_birth_height (dgb_settings) — also the value the sticky re-arm
// remembered and logged: "re-armed auto-fetch from remembered start 22650000".
#define CF_BIRTH_HEIGHT       22650000u

// How many headers the persisted near-tip snapshot carries. Only its DEPTH
// matters here (it sets the resumed manager's resolvable block floor); the real
// cap is SAVE_BLOCK_COUNT and materializing that many BRMerkleBlocks per arm
// would cost seconds for no additional coverage.
#define NEAR_TIP_RUN          300u
#define NEAR_TIP_FLOOR        (H_NEAR_TIP - (NEAR_TIP_RUN - 1))   // 23,059,701

// The live session's CF scan frontier at the moment peers dropped. The scan
// trails the block tip (cfilters are fetched behind the header chain), so it
// sits just below H — inside the persisted window, which is what makes the
// resume servable at all.
#define LIVE_SCAN_FRONTIER    (H_NEAR_TIP - 50u)                  // 23,059,950

// ---------------------------------------------------------------------------
// Block fixtures — a real prevBlock-linked run, same idiom as
// cf_scan_ledger_drive_kat's rhUniqueHash chain.
// ---------------------------------------------------------------------------

// Unique, never-UINT256_ZERO hash keyed by height (u32[0] carries the height;
// the rest carry a fixed non-zero magic so no fixture hash can ever collide
// with the UINT256_ZERO "not found" sentinel the resolvers emit, nor with a
// real checkpoint hash from the params table).
static UInt256 katHash(uint32_t height)
{
    UInt256 h = UINT256_ZERO;
    h.u32[0] = height;
    h.u32[1] = 0x9E3779B9u ^ height;
    h.u32[2] = 0xC0FFEE11u;
    h.u32[7] = 0x5A5A5A5Au;
    return h;
}

static BRMerkleBlock *katBlock(uint32_t height)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->blockHash = katHash(height);
    b->prevBlock = katHash(height - 1);
    b->height    = height;
    // ~15 s/block on DGB, anchored so the run's timestamps are plausibly
    // "now" relative to the birth checkpoint. Only used for status mirrors.
    b->timestamp = 1772171620u + (height - 23050000u) * 15u;
    b->target    = 0x19051eafu;
    return b;
}

// ===========================================================================
// MIRROR of bridge/jni_peer.c — see the file header for why this is mirrored
// rather than #include-d, and for the exact line ranges each piece tracks.
// ===========================================================================

static BRWallet      *g_wallet                 = NULL;   // jni_wallet.c's
static BRPeerManager *g_peerManager            = NULL;   // jni_peer.c:~330
static BRMerkleBlock **g_savedBlocks           = NULL;   // jni_peer.c:~336
static size_t         g_savedBlocksCount       = 0;      // jni_peer.c:~337
static int            g_peerManagerNeedsRecreate = 0;    // jni_peer.c:~344
static uint32_t       g_walletCreationTime     = 0;      // set by recoverWallet
static int            g_pendingSyncMode        = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
static int            g_autoFetchRemembered    = 0;      // jni_peer.c:1315
static uint32_t       g_autoFetchStartRemembered = 0;    // jni_peer.c:1316

// ---- the two on-disk stores the fixed recovery path reads from -------------
// SavedBlockStore (filesDir/saved_blocks<net>.bin), modeled as the descriptor
// of the run it holds. CfScanLedgerStore, held as the REAL serialized blob
// BRPeerManagerCFLedgerSerialize produced from the live manager.
static uint32_t g_diskSavedBlocksTip   = 0;
static uint32_t g_diskSavedBlocksCount = 0;
static uint8_t  g_diskCfLedger[4096];
static size_t   g_diskCfLedgerLen      = 0;

// Mirrors loadSavedBlocks (jni_peer.c:1167-1223): free anything previously
// loaded (safe because startSync NULLs the pointer on ownership transfer — the
// invariant saved_blocks_reentrant_kat pins), then repopulate g_savedBlocks.
// The wire-blob deserialize is covered by saved_blocks_kat; here the store is
// materialized straight into the BRMerkleBlock run it encodes.
static void bridge_loadSavedBlocks(uint32_t tip, uint32_t count)
{
    if (g_savedBlocks) {
        for (size_t i = 0; i < g_savedBlocksCount; i++) {
            if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);
        }
        free(g_savedBlocks);
        g_savedBlocks = NULL;
        g_savedBlocksCount = 0;
    }
    if (count == 0) return;
    g_savedBlocks = calloc(count, sizeof(*g_savedBlocks));
    if (!g_savedBlocks) abort();
    // Ascending, so g_savedBlocks[count-1] is the tip — same order the
    // deserializer produces and the same order BRPeerManagerNewEx expects.
    for (uint32_t i = 0; i < count; i++) g_savedBlocks[i] = katBlock(tip - (count - 1) + i);
    g_savedBlocksCount = count;
}

// Mirrors _applyPendingBip158State (jni_peer.c:1319-1388), reduced to the two
// pieces this scenario turns on: the ALWAYS-applied sync mode (without it the
// fresh manager sits on its calloc default BLOOM_ONLY and every CF path is
// gated off), and the sticky re-arm. The pending-filter-chain / pending-
// auto-fetch blocks are cold-start-only and are not part of a recreate.
static void bridge_applyPendingBip158State(void)
{
    if (!g_peerManager) return;
    BRPeerManagerSetSyncMode(g_peerManager, (BRSyncMode)g_pendingSyncMode);

    // jni_peer.c:1383-1387. The ==0 gate uniquely identifies a fresh manager
    // (BRPeerManagerNewEx calloc-zeroes autoFetchCFiltersStart).
    if (g_autoFetchRemembered &&
        (BRSyncMode)g_pendingSyncMode == BR_SYNC_MODE_COMPACT_FILTERS_ONLY &&
        BRPeerManagerGetAutoFetchCFiltersStart(g_peerManager) == 0) {
        BRPeerManagerEnableAutoCompactFilterFetch(g_peerManager, g_autoFetchStartRemembered);
        printf("LOGI> BIP158: recreate — re-armed auto-fetch from remembered start %u\n",
               g_autoFetchStartRemembered);
    }
}

// Mirrors forceReconnect (jni_peer.c:696-706).
static void bridge_forceReconnect(void)
{
    if (!g_peerManager) return;
    g_peerManagerNeedsRecreate = 1;
}

// Mirrors startSync's manager lifecycle (jni_peer.c:764-786 recreate branch,
// 845-857 rebuild, 862-893 ownership transfer, 898 flag clear, then the
// callback + BIP158 apply). BRPeerManagerDisconnect/Connect are omitted: this
// KAT never opens a socket, and neither call touches lastBlock or the ledger.
static void bridge_startSync(void)
{
    if (g_peerManager && g_peerManagerNeedsRecreate) {
        g_peerManagerNeedsRecreate = 0;
        BRPeerManagerFree(g_peerManager);
        g_peerManager = NULL;
    }
    if (g_peerManager) return;   // already live and connected -> nothing to do

    uint32_t syncFromTime = g_walletCreationTime;
    printf("LOGI> startSync: creating peer manager (syncFromTime=%u, savedBlocks=%zu)\n",
           syncFromTime, g_savedBlocksCount);
    g_peerManager = BPPeerManagerMainNetNew(g_wallet, syncFromTime,
                                            g_savedBlocks, g_savedBlocksCount,
                                            NULL, 0);
    if (!g_peerManager) abort();

    // OWNERSHIP TRANSFER (jni_peer.c:888-893): the manager adopted the
    // elements; free ONLY the array and NULL the globals. THIS is why a later
    // recreate has nothing to rebuild from unless something reloads them.
    if (g_savedBlocks) {
        free(g_savedBlocks);
        g_savedBlocks = NULL;
        g_savedBlocksCount = 0;
    }
    g_peerManagerNeedsRecreate = 0;
    bridge_applyPendingBip158State();
}

// ===========================================================================
// MIRROR of SyncService.kt's Task-2 helpers.
// ===========================================================================

// reloadSavedBlocksNearTip(blockBytes = SavedBlockStore.load(this)): repopulate
// the bridge's saved-block array from the last PERSISTED snapshot so the rebuild
// has a near-tip run to sit on. Must run BEFORE startSync.
static void svc_reloadSavedBlocksNearTip(void)
{
    bridge_loadSavedBlocks(g_diskSavedBlocksTip, g_diskSavedBlocksCount);
}

// restoreCfLedgerAndSnap(savedLedger = CfScanLedgerStore.load(this)): restore
// the persisted scan ledger and reconcile the forward-fetch cursor to its
// frontier. Must run AFTER startSync — the native ledger is Init'd inside
// startSync's re-arm, so an earlier restore would be overwritten. The snap runs
// unconditionally, including when no ledger was persisted.
static void svc_restoreCfLedgerAndSnap(void)
{
    if (!g_peerManager) return;
    if (g_diskCfLedgerLen > 0) {
        BRPeerManagerCFLedgerRestore(g_peerManager, g_diskCfLedger, g_diskCfLedgerLen);
    }
    BRPeerManagerSnapAutoFetchThroughToScanFrontier(g_peerManager);
}

// ===========================================================================
// The scenario.
// ===========================================================================

static void test_recreate_resumes_near_tip(void)
{
    printf("\n=== test_recreate_resumes_near_tip ===\n");

    // ---- controls: pin the fixture against the REAL params table -----------
    // The birth checkpoint is READ OUT of BRMainNetCheckpoints (never
    // hardcoded) so a checkpoint regeneration can't quietly invalidate the
    // gate, and earliestKeyTime is then DERIVED from it, which makes
    // BRPeerManagerNewEx's selection rule land on it by construction rather
    // than by restating that rule here.
    const BRChainParams *params = &BRMainNetParams;
    size_t birthIdx = 0;
    int tsAscending = 1;
    for (size_t i = 0; i < params->checkpointsCount; i++) {
        if (i > 0 && params->checkpoints[i].timestamp <= params->checkpoints[i - 1].timestamp) tsAscending = 0;
        if (params->checkpoints[i].height <= CF_BIRTH_HEIGHT) birthIdx = i;
    }
    const uint32_t BIRTH_CHECKPOINT = params->checkpoints[birthIdx].height;
    g_walletCreationTime = params->checkpoints[birthIdx].timestamp + 7u * 24u * 60u * 60u + 1u;

    check(tsAscending, "control: BRMainNetCheckpoints timestamps ascend (birth-checkpoint selection is well-defined)");
    check(BIRTH_CHECKPOINT > 0 && BIRTH_CHECKPOINT <= CF_BIRTH_HEIGHT,
          "control: birth checkpoint resolved from the real BRMainNetParams table");
    check(H_NEAR_TIP > BIRTH_CHECKPOINT + 100000u,
          "control: the near-tip height H is >100k blocks above the birth checkpoint");
    printf("FIXTURE> H=%u cf_birth=%u birthCheckpoint=%u walletCreationTime=%u liveScanFrontier=%u\n",
           H_NEAR_TIP, CF_BIRTH_HEIGHT, BIRTH_CHECKPOINT, g_walletCreationTime, LIVE_SCAN_FRONTIER);

    // ---- PHASE A: the live, near-tip session (identical in both arms) -------
    // Cold start: Kotlin loads the persisted saved blocks, enables auto-fetch
    // at cf_birth_height (which is what makes the re-arm sticky), then
    // startSync builds the manager. Production's cold start reaches
    // BRPeerManagerEnableAutoCompactFilterFetch through the PENDING auto-fetch
    // block (jni_peer.c:1358-1368) rather than the sticky re-arm below it; both
    // call the same Enable with the same start, and the pending block is
    // cold-start-only, so only the sticky path is mirrored. That is why the
    // "recreate — re-armed" line appears twice in this log.
    g_diskSavedBlocksTip   = H_NEAR_TIP;
    g_diskSavedBlocksCount = NEAR_TIP_RUN;
    g_autoFetchRemembered      = 1;
    g_autoFetchStartRemembered = CF_BIRTH_HEIGHT;

    bridge_loadSavedBlocks(g_diskSavedBlocksTip, g_diskSavedBlocksCount);
    bridge_startSync();

    check(BRPeerManagerLastBlockHeight(g_peerManager) == H_NEAR_TIP,
          "control: live session is at the near-tip height H before the recreate");

    // The live scan frontier. Arming the REAL Enable at LIVE_SCAN_FRONTIER is
    // the shortest legitimate way to put the REAL ledger in the state a session
    // that has scanned up to there is in: the height is resolvable inside the
    // resident run, so Enable does not clamp and BRCFScanLedgerInit sets
    // start/scannedThrough exactly there. (Driving the frontier forward through
    // RecordRequested/MarkEvaluated is cf_scan_ledger_drive_kat's job.)
    BRPeerManagerEnableAutoCompactFilterFetch(g_peerManager, LIVE_SCAN_FRONTIER);
    check(BRPeerManagerLowestNeededHeight(g_peerManager) == LIVE_SCAN_FRONTIER,
          "control: live session's CF scan frontier is near-tip before the recreate");

    // What the two stores hold when the peers drop — the CF ledger through the
    // REAL serializer, exactly as bridge_saveCFLedger -> CfScanLedgerStore does.
    g_diskCfLedgerLen = BRPeerManagerCFLedgerSerialize(g_peerManager, g_diskCfLedger, sizeof g_diskCfLedger);
    check(g_diskCfLedgerLen > 0, "control: live CF scan ledger serialized to the persisted store");

    // ---- PHASE B: peers drop to 0; the recovery path recreates the manager --
    bridge_forceReconnect();
    check(g_peerManagerNeedsRecreate == 1, "control: forceReconnect flagged the manager for recreate");
    check(g_savedBlocks == NULL && g_savedBlocksCount == 0,
          "control: the cold-start ownership transfer left g_savedBlocks NULL (nothing to rebuild from)");

#ifndef RECREATE_NEARTIP_UNFIXED
    svc_reloadSavedBlocksNearTip();      // Task 2 helper — BEFORE startSync
#endif
    bridge_startSync();
#ifndef RECREATE_NEARTIP_UNFIXED
    svc_restoreCfLedgerAndSnap();        // Task 2 helper — AFTER startSync
#endif

    // ---- the property under test -------------------------------------------
    const uint32_t lastH    = BRPeerManagerLastBlockHeight(g_peerManager);
    const uint32_t frontier = BRPeerManagerLowestNeededHeight(g_peerManager);
    const uint32_t cfStart  = BRPeerManagerGetAutoFetchCFiltersStart(g_peerManager);
    printf("RECREATE-RESULT> lastBlock=%u cfFrontier=%u cfStart=%u | H=%u birthCheckpoint=%u cf_birth=%u\n",
           lastH, frontier, cfStart, H_NEAR_TIP, BIRTH_CHECKPOINT, CF_BIRTH_HEIGHT);

    // Collapse detectors: printed ONLY when the observed bug reproduces, so
    // run.sh can require them present on RED and absent on GREEN. Identical
    // source in both arms — the arms differ only in the mirrored orchestration.
    if (lastH == BIRTH_CHECKPOINT) {
        printf("COLLAPSE-CONFIRMED> block: lastBlockHeight floored to the birth checkpoint %u "
               "(H was %u — %u blocks lost)\n", BIRTH_CHECKPOINT, H_NEAR_TIP, H_NEAR_TIP - BIRTH_CHECKPOINT);
    }
    if (frontier == CF_BIRTH_HEIGHT) {
        printf("COLLAPSE-CONFIRMED> cf: scan frontier floored to cf_birth_height %u "
               "(frontier was %u — %u heights lost)\n",
               CF_BIRTH_HEIGHT, LIVE_SCAN_FRONTIER, LIVE_SCAN_FRONTIER - CF_BIRTH_HEIGHT);
    }

    check(lastH == H_NEAR_TIP,
          "block side: recreate resumed at the near-tip snapshot's top (lastBlockHeight == H)");
    check(lastH != BIRTH_CHECKPOINT,
          "block side: recreate did NOT floor to the wallet's birth checkpoint");
    check(frontier == LIVE_SCAN_FRONTIER,
          "CF side: scan frontier resumed at the persisted near-tip frontier");
    check(frontier != CF_BIRTH_HEIGHT,
          "CF side: scan frontier did NOT floor to cf_birth_height");

    // Both halves, stated as the one property the plan exists for — plus the
    // opposite-direction guard. Resuming ABOVE the persisted frontier is the
    // silent-skip failure this subsystem exists to prevent (those heights are
    // never scanned and nothing ever says so), and it is exactly what happens
    // if the block snapshot is refreshed but the ledger restore is dropped:
    // Enable's resolvability clamp then anchors the fresh ledger on the saved
    // TIP, sailing the frontier over everything the live session had not yet
    // scanned. Bound BOTH directions.
    check(lastH >= NEAR_TIP_FLOOR && frontier >= NEAR_TIP_FLOOR && frontier <= LIVE_SCAN_FRONTIER,
          "recreate at height H resumed near H on BOTH sides, without skipping past the persisted scan frontier");

    BRPeerManagerFree(g_peerManager);
    g_peerManager = NULL;
}

int main(void)
{
    // Unbuffered: run.sh greps the redirected log to prove WHERE the RED arm
    // failed, and stdout is fully buffered when it is a file.
    setvbuf(stdout, NULL, _IONBF, 0);

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    g_wallet = BRWalletNew(NULL, 0, mpk);
    check(g_wallet != NULL, "control: wallet created");
    if (!g_wallet) { printf("\nFATAL\n"); return 1; }

    test_recreate_resumes_near_tip();

    if (g_savedBlocks) {
        for (size_t i = 0; i < g_savedBlocksCount; i++) {
            if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);
        }
        free(g_savedBlocks);
        g_savedBlocks = NULL;
        g_savedBlocksCount = 0;
    }
    BRWalletFree(g_wallet);

    printf("\n(CF abandonment WARNs emitted: %d)\n", g_wlogCount);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
