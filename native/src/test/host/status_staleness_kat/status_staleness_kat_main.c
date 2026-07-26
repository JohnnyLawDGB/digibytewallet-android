// Host KAT for bridge_status_is_stale — pure (lastMs, nowMs, boundMs) -> stale?
// The staleness predicate behind NativeBridge.isStatusStale(): a UI/watchdog
// consumer uses it to tell a real, fresh "0 peers" sample from "no fresh
// sample" (the frozen-loop signature). Pure/header-only, no BRPeerManager, so
// it is testable standalone on the host.
#include <stdio.h>
#include <stdint.h>
#include "bridge_status_stale.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

int main(void) {
    const int64_t bound = STATUS_STALE_MS; // 10000

    // never refreshed (last == 0) -> stale, regardless of now
    check(bridge_status_is_stale(0, 0,      bound) == 1, "never-refreshed (last=0) -> stale");
    check(bridge_status_is_stale(0, 999999, bound) == 1, "never-refreshed (last=0), large now -> stale");

    // within the bound -> fresh
    check(bridge_status_is_stale(1000, 1000,             bound) == 0, "same instant -> fresh");
    check(bridge_status_is_stale(1000, 6000,             bound) == 0, "5s age (< bound) -> fresh");
    check(bridge_status_is_stale(1000, 1000 + bound - 1, bound) == 0, "one ms under bound -> fresh");

    // exactly AT the bound boundary -> fresh (strict `>` comparison)
    check(bridge_status_is_stale(1000, 1000 + bound, bound) == 0, "exactly at bound -> fresh (strict >)");

    // past the bound -> stale
    check(bridge_status_is_stale(1000, 1000 + bound + 1, bound) == 1, "one ms past bound -> stale");
    check(bridge_status_is_stale(1000, 1000 + 60000,     bound) == 1, "60s age (>> bound) -> stale");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
