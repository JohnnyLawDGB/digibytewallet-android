// Host KAT for BRPeerCapPolicy.h -- full peers while catching up, three once stably at the tip.
// Cases written from SyncService.runPeerCapController (no Kotlin unit test covers it).
//
// Built twice by run.sh:
//   -DPEER_CAP_RED  drops on the first at-tip poll with no grace; every RED case fails
//   (default)       the shipped rule; every case passes

#include "BRPeerCapPolicy.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define T0 1790000000000LL

int main(void)
{
    {   // stably at the tip: full for the first minute, then three
        int64_t since = 0; int a, b, c;
        a = BRPeerCapStep(&since, 1, 24000000, 24000010, 8, T0);
        b = BRPeerCapStep(&since, 1, 24000001, 24000010, 8, T0 + BR_PEER_CAP_REDUCE_GRACE_MS - 1);
        c = BRPeerCapStep(&since, 1, 24000002, 24000010, 8, T0 + BR_PEER_CAP_REDUCE_GRACE_MS);
        result("reduces_after_a_stable_minute", a == 8 && b == 8 && c == 3 && since == T0);
    }
    {   // one poll off the tip resets the clock
        int64_t since = 0; int a, b, c;
        BRPeerCapStep(&since, 1, 24000000, 24000010, 8, T0);
        a = BRPeerCapStep(&since, 1, 24000000, 24000100, 8, T0 + 30000);           // 100 behind
        b = BRPeerCapStep(&since, 1, 24000100, 24000100, 8, T0 + 45000);
        c = BRPeerCapStep(&since, 1, 24000100, 24000100, 8, T0 + 45000 + BR_PEER_CAP_REDUCE_GRACE_MS);
        result("falling_behind_resets_the_clock", a == 8 && b == 8 && c == 3);
    }
    {   // the tip delta is inclusive
        int64_t since = 0, s2 = 0;
        BRPeerCapStep(&since, 1, 24000000, 24000025, 8, T0);
        BRPeerCapStep(&s2, 1, 24000000, 24000026, 8, T0);
        result("tip_delta_inclusive",
               BRPeerCapStep(&since, 1, 24000000, 24000025, 8, T0 + BR_PEER_CAP_REDUCE_GRACE_MS) == 3 &&
               BRPeerCapStep(&s2, 1, 24000000, 24000026, 8, T0 + BR_PEER_CAP_REDUCE_GRACE_MS) == 8 && s2 == 0);
    }
    {   // never synced, too few peers, or an unknown height: never at the tip
        int64_t a = 0, b = 0, c = 0, d = 0;
        int ok = 1;
        for (int i = 0; i < 8; i++) {
            int64_t t = T0 + i * BR_PEER_CAP_POLL_MS * 2;
            ok = ok && BRPeerCapStep(&a, 0, 24000000, 24000000, 8, t) == 8;
            ok = ok && BRPeerCapStep(&b, 1, 24000000, 24000000, 2, t) == 8;
            ok = ok && BRPeerCapStep(&c, 1, 24000000, 0, 8, t) == 8;
            ok = ok && BRPeerCapStep(&d, 1, 0, 24000000, 8, t) == 8;
        }
        result("never_at_tip_without_sync_peers_or_heights", ok && a == 0 && b == 0 && c == 0 && d == 0);
    }
    result("counts", BR_PEER_CAP_SYNCED_COUNT == 3 && PEER_MAX_CONNECTIONS == 8);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
