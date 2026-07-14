// Host KAT for BRComputeCFPeerStatus — pure (inPool, connected, served) -> status.
#include <stdio.h>
#include <stdint.h>
#include "BRPeerCFStatus.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}
int main(void) {
    check(BRComputeCFPeerStatus(0,0,0) == BR_CF_PEER_UNKNOWN,               "absent -> UNKNOWN");
    check(BRComputeCFPeerStatus(1,0,0) == BR_CF_PEER_CONNECTING,           "in pool, not connected -> CONNECTING");
    check(BRComputeCFPeerStatus(1,1,0) == BR_CF_PEER_CONNECTED_NOT_SERVING,"connected, not served -> NOT_SERVING");
    check(BRComputeCFPeerStatus(1,1,1) == BR_CF_PEER_SERVING,             "connected + served -> SERVING");
    check(BRComputeCFPeerStatus(0,1,1) == BR_CF_PEER_UNKNOWN,             "not in pool dominates (defensive)");
    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
