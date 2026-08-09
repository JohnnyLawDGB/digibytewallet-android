// Host KAT for BRPeerServicesAllowedForSyncMode.
//
// POST-v4.0.0 TRUTH TABLE. Bloom (BIP37) was excised in v4.0.0 and BIP157/158 compact
// filters are the only sync path, so peer usability now has exactly one criterion: does the
// peer serve compact filters. syncMode is accepted but IGNORED (BRPeerServices.h:14-18) --
// the enum retains 3 values only as an ABI constant, and COMPACT_FILTERS_ONLY is the sole
// reachable mode (see syncModeFor in CustomNode.kt).
//
// This KAT previously asserted the bloom-era table ("bloom usable in BLOOM_ONLY", "cf-only
// NOT usable in BLOOM_ONLY") and had been RED since the excision -- the assertions were
// stale, not the implementation. Nothing runs the host KATs in CI, so it went unnoticed.
#include <stdio.h>
#include <stdint.h>
#include "BRPeer.h"
#include "BRPeerManager.h"
#include "BRPeerServices.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

int main(void) {
    const uint64_t BLOOM   = SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM;             // 0x05
    const uint64_t CFONLY  = SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS;   // 0x41
    const uint64_t BOTHSVC = SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM | SERVICES_NODE_COMPACT_FILTERS; // 0x45

    // A bloom-only peer is NEVER usable -- there is no bloom data path left to use it on.
    // This is the v4.0.0 excision: the wallet's address set can never leave the device.
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BLOOM_ONLY) == 0, "bloom-only peer NOT usable, even in BLOOM_ONLY");
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BOTH) == 0, "bloom-only peer NOT usable in BOTH");
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 0, "bloom-only peer NOT usable in COMPACT_FILTERS_ONLY");

    // A compact-filter peer is usable in EVERY mode: syncMode is ignored entirely.
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 1, "cf peer usable in COMPACT_FILTERS_ONLY");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BOTH) == 1, "cf peer usable in BOTH");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BLOOM_ONLY) == 1, "cf peer usable even in the vestigial BLOOM_ONLY mode");

    // Dual-capable peers are usable because of the CF bit, not the bloom bit.
    check(BRPeerServicesAllowedForSyncMode(BOTHSVC, BR_SYNC_MODE_BLOOM_ONLY) == 1, "bloom+cf peer usable (on the CF bit)");
    check(BRPeerServicesAllowedForSyncMode(BOTHSVC, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 1, "bloom+cf peer usable in COMPACT_FILTERS_ONLY");

    // A peer advertising neither is never usable.
    check(BRPeerServicesAllowedForSyncMode(SERVICES_NODE_NETWORK, BR_SYNC_MODE_BOTH) == 0, "network-only NOT usable");
    check(BRPeerServicesAllowedForSyncMode(0, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 0, "no services NOT usable");

    check(BRPeerShouldRequestMempool(CFONLY, 1) == 0,
          "CF-only sync never requests the full mempool");
    check(BRPeerShouldRequestMempool(CFONLY, 0) == 0,
          "a peer without NODE_BLOOM must not receive mempool");
    check(BRPeerShouldRequestMempool(BOTHSVC, 1) == 0,
          "CF-only sync does not request mempool even from a dual-capable peer");
    check(BRPeerShouldRequestMempool(BOTHSVC, 0) == 1,
          "legacy filtered sync may request mempool only from a NODE_BLOOM peer");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
