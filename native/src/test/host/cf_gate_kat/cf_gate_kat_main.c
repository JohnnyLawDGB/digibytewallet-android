// Host KAT for BRPeerServicesAllowedForSyncMode — the sync-mode-gated
// generalization of the former testnet-only compact-filter accept exception.
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

    // Bloom peers are always usable, in every mode (bloom-only path unchanged).
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BLOOM_ONLY) == 1, "bloom usable in BLOOM_ONLY");
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BOTH) == 1, "bloom usable in BOTH");

    // CF-only peers: usable in CF and BOTH, NOT in BLOOM_ONLY. This is the change.
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 1, "cf-only usable in COMPACT_FILTERS_ONLY");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BOTH) == 1, "cf-only usable in BOTH (mainnet accepts now)");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BLOOM_ONLY) == 0, "cf-only NOT usable in BLOOM_ONLY");

    // Dual-capable peers usable everywhere.
    check(BRPeerServicesAllowedForSyncMode(BOTHSVC, BR_SYNC_MODE_BLOOM_ONLY) == 1, "bloom+cf usable in BLOOM_ONLY");

    // A peer advertising neither bloom nor CF is never usable.
    check(BRPeerServicesAllowedForSyncMode(SERVICES_NODE_NETWORK, BR_SYNC_MODE_BOTH) == 0, "network-only NOT usable");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
