#include <stdio.h>

#include "BRPeerConnectPolicy.h"

static int failures = 0;

static void check(int condition, const char *description)
{
    if (condition) printf("PASS: %s\n", description);
    else {
        printf("FAIL: %s\n", description);
        failures++;
    }
}

int main(void)
{
    check(BRPeerManagerNeedsTopUp(8, 8, 1),
          "unchanged catch-up target tops up a one-of-eight pool");
    check(BRPeerManagerNeedsTopUp(8, 8, 7),
          "unchanged catch-up target fills its final free slot");
    check(!BRPeerManagerNeedsTopUp(8, 8, 8),
          "full pool does not reconnect on an unchanged target");
    check(BRPeerManagerNeedsTopUp(3, 8, 3),
          "increasing the target tops up the pool");
    check(BRPeerManagerNeedsTopUp(3, 3, 2),
          "unchanged synced target repairs a degraded pool");
    check(!BRPeerManagerNeedsTopUp(8, 3, 8),
          "reducing the target does not reconnect");

    if (failures == 0) {
        printf("ALL PASSED\n");
        return 0;
    }
    printf("SOME FAILED (%d failure(s))\n", failures);
    return 1;
}
