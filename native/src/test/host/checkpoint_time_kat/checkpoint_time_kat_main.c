// Host KAT for bridge/checkpoint_time.h against the REAL compiled checkpoint tables.
//
// Property: for any height h at or above the first checkpoint, handing the header-anchor rule
// a creation time equal to checkpoint_time_at_or_below(h) selects a checkpoint whose height is
// <= h. The anchor rule is mirrored from BRPeerManagerNewEx (and getWalletBirthCheckpointHeight):
// the LATEST checkpoint i with i == 0 || timestamp_i + 7 days < creationTime.
//
// Heights probed: every checkpoint height, height + 1, the height just below the next
// checkpoint, and a few far above the newest one, on both networks.
#include <stdio.h>
#include <stdint.h>
#include "BRChainParams.h"
#include "checkpoint_time.h"

static int failures = 0;

static uint32_t anchor_height_for_time(const BRCheckPoint *cp, size_t n, uint32_t t)
{
    uint32_t h = 0;
    for (size_t i = 0; i < n; i++) {
        if (i == 0 || cp[i].timestamp + 7*24*60*60 < t) h = cp[i].height;
    }
    return h;
}

static void probe(const char *net, const BRCheckPoint *cp, size_t n, uint64_t h)
{
    uint32_t t = checkpoint_time_at_or_below(cp, n, h);
    if (t == 0) { printf("FAIL %s h=%llu: no checkpoint time\n", net, (unsigned long long)h); failures++; return; }
    uint32_t a = anchor_height_for_time(cp, n, t);
    if ((uint64_t)a > h) {
        printf("FAIL %s h=%llu: time %u selects anchor %u above the height\n", net, (unsigned long long)h, t, a);
        failures++;
    }
}

static void run(const char *net, const BRCheckPoint *cp, size_t n)
{
    int before = failures;
    for (size_t i = 0; i < n; i++) {
        probe(net, cp, n, cp[i].height);
        probe(net, cp, n, (uint64_t)cp[i].height + 1);
        if (i + 1 < n && cp[i + 1].height > 0) probe(net, cp, n, (uint64_t)cp[i + 1].height - 1);
    }
    probe(net, cp, n, (uint64_t)cp[n - 1].height + 1000000);
    probe(net, cp, n, 0xffffffffULL);
    // the table itself: the height lookup returns that checkpoint's own time
    for (size_t i = 0; i < n; i++) {
        if (checkpoint_time_at_or_below(cp, n, cp[i].height) != cp[i].timestamp) {
            printf("FAIL %s: lookup at checkpoint %u does not return its own time\n", net, cp[i].height);
            failures++;
        }
    }
    printf("%s %s: %zu checkpoints probed\n", failures == before ? "PASS" : "FAIL", net, n);
}

int main(void)
{
    // The tables themselves, as checkpoint_staleness_kat reads them: the params structs pull in
    // verifier symbols the host does not link.
    run("mainnet", BRMainNetCheckpoints, sizeof(BRMainNetCheckpoints) / sizeof(*BRMainNetCheckpoints));
    run("testnet", BRTestNetCheckpoints, sizeof(BRTestNetCheckpoints) / sizeof(*BRTestNetCheckpoints));
    if (checkpoint_time_at_or_below(NULL, 0, 5) != 0) { printf("FAIL: empty table\n"); failures++; }
    return failures ? 1 : 0;
}
