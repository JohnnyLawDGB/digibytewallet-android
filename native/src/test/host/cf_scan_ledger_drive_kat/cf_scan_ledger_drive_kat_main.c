// Host KAT harness for the CF scan-ledger's residual peek/commit re-request
// DRIVER (BRPeerManager.c, submodule commit 9e6e357 "feat(cf-ledger): residual
// peek/commit re-request driver"). Phase 2 Task 4 of
// .superpowers/sdd/2026-07-26-cf-scan-ledger-phase2-rerequest-driver/ --
// this file is the harness SKELETON plus a build/teardown smoke test only.
// The real driver assertions (does the driver re-issue getcfilters/getdata
// for the holes BRCFScanLedgerHoleRanges reports, does the buffered-drain
// credit path fire on BRPeerSendGetdataBlocks, etc.) land in Task 5 -- this
// task only proves the harness itself builds, links, and tears down
// LSan-clean.
//
// Approach (same #include-a-.c-for-statics pattern as cf_confirm_kat,
// bip341_signtx_kat, digidollar_send_kat): #include "BRPeerManager.c" directly
// to reach the file-static re-request driver plumbing and the otherwise-
// opaque BRPeerManagerStruct/BRPeerCallbackInfo definitions. A real
// BRPeerManager is built via the public BRPeerManagerNew() (so every internal
// array/mutex is correctly initialized), backed by a real BRWallet built via
// BRWalletNew() with a test mnemonic's derived master pubkey (same derivation
// pattern as digidollar_wallet_kat / cf_confirm_kat).
//
// Send-capture seam: the driver reaches out to peers through BRPeer.c's
// public API. Real BRPeer.c is linked in (every other BRPeer symbol used by
// the manager -- BRPeerNew, BRPeerSetCallbacks, etc. -- is the real one), but
// these four calls are intercepted at link time via GNU ld's `-Wl,--wrap=`
// (run.sh) and redirected here instead of touching a real socket:
//   - BRPeerConnectStatus  -> always reports BRPeerStatusConnected
//   - BRPeerIsSocketOpen   -> always reports an open (live) socket
//   - BRPeerSendGetCFilters   -> records into g_capStart / g_capCount
//   - BRPeerSendGetdataBlocks -> records into g_getdataCount / g_getdataHash
// (the buffered-drain credit assertion Task 5 exercises). This requires GNU
// ld's `--wrap`; host CI is Linux/clang -- NOT portable to a Darwin/ld64 host.
//
// LeakSanitizer stays LIVE for this KAT (run.sh does not set
// ASAN_OPTIONS=detect_leaks=0 the way the sibling KATs do) -- the scan-
// ledger's buffered raw filter bytes (BRCFScanLedger's filter-byte buffer,
// Phase 2 Task 2) are exactly the kind of allocation a silent leak would
// hide. Every KAT case must therefore end by calling BRPeerManagerFree(m)
// (which also now calls BRCFScanLedgerFree(&manager->cfLedger)) so LSan can
// prove the buffer and the manager are both actually freed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// --- link-wrap send-capture seam ------------------------------------------
//
// Verified against the live BRPeer.h signatures before writing these (do not
// hand-wave the types -- a mismatched --wrap shim silently fails to bind, or
// worse, corrupts the stack across the ABI mismatch):
//   BRPeerStatus BRPeerConnectStatus(BRPeer *peer);
//   int BRPeerIsSocketOpen(BRPeer *peer);
//   void BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash);
//   void BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount);
// All four matched the brief's assumed signatures exactly -- BRPeerStatus IS
// a real enum (not a bare int), and BRPeerSendGetdataBlocks's second
// parameter is an array-of-UInt256 (decays to `const UInt256 *`), same as
// BRPeerSendGetCFilters's stopHash is UInt256 by value.

static int g_capCount = 0;
static uint32_t g_capStart = 0;

BRPeerStatus __wrap_BRPeerConnectStatus(BRPeer *peer)
{
    (void)peer;
    return BRPeerStatusConnected;
}

int __wrap_BRPeerIsSocketOpen(BRPeer *peer)
{
    (void)peer;
    return 1;
}

void __wrap_BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)peer; (void)filterType; (void)stopHash;
    g_capStart = startHeight;
    g_capCount++;
}

static int g_getdataCount = 0;
static UInt256 g_getdataHash;

void __wrap_BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount)
{
    (void)peer;
    g_getdataCount += (int)blockCount;
    if (blockCount > 0) g_getdataHash = blockHashes[0];
}

// canonical all-zeros mnemonic, same one digidollar_wallet_kat/cf_confirm_kat
// use elsewhere in this tree
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

int main(void)
{
    // --- Smoke test: the compile-time gate the whole Phase 2 driver is
    // built behind is actually flipped ON for this KAT build (run.sh passes
    // -DCF_LEDGER_DRIVE_REREQUEST=1; production defaults it to 0 -- see
    // BRCFScanLedger.h -- until Task 6 flips it). ---
    check(CF_LEDGER_DRIVE_REREQUEST == 1, "smoke: CF_LEDGER_DRIVE_REREQUEST compiled in as 1 for this KAT");

    // --- Smoke test: the harness can build and tear down a real
    // BRPeerManager (backed by a real BRWallet) LSan-clean, proving out the
    // scaffolding Task 5's actual driver cases will reuse. No peers are
    // connected and no driver logic is exercised yet -- that's Task 5. ---
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    check(wallet != NULL, "smoke: wallet created");
    if (!wallet) { printf("\nFATAL\n"); return 1; }

    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(manager != NULL, "smoke: peer manager created");
    if (!manager) { BRWalletFree(wallet); printf("\nFATAL\n"); return 1; }

    // Referencing the capture globals here (rather than leaving them
    // write-only) keeps -Wunused-but-set-variable quiet under stricter
    // flags and documents that Task 5 reads exactly these four: the wrap
    // shims above are otherwise never called on this build/teardown-only
    // path (no BRPeerManagerConnect happens in this KAT).
    check(g_capCount == 0 && g_capStart == 0, "smoke: getcfilters capture seam untouched before any drive");
    check(g_getdataCount == 0, "smoke: getdata capture seam untouched before any drive");
    (void)g_getdataHash;

    // Every KAT case in this harness (Task 5's real cases included) must end
    // by freeing the manager -- this is what proves LSan-clean, i.e. that
    // BRCFScanLedgerFree(&manager->cfLedger) (BRPeerManager.c, Phase 2 Task 2)
    // actually releases any buffered raw filter bytes instead of leaking them.
    BRPeerManagerFree(manager);
    BRWalletFree(wallet);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
