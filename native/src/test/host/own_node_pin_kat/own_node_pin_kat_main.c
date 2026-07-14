// Host KAT for BRPeerIsPinned — the pure pinned-peer match predicate.
#include <stdio.h>
#include <stdint.h>
#include "BRInt.h"
#include "BRPeerPin.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}
static UInt128 make_addr(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    UInt128 r = UINT128_ZERO; r.u16[5] = 0xffff;
    r.u8[12] = a; r.u8[13] = b; r.u8[14] = c; r.u8[15] = d;   // network-order low word
    return r;
}
int main(void) {
    UInt128 node = make_addr(10,0,0,5);
    UInt128 other = make_addr(1,2,3,4);
    UInt128 none = UINT128_ZERO;

    check(BRPeerIsPinned(node, 12024, node, 12024) == 1, "exact addr+port match is pinned");
    check(BRPeerIsPinned(node, 12024, node, 12099) == 0, "same addr different port not pinned");
    check(BRPeerIsPinned(node, 12024, other, 12024) == 0, "different addr not pinned");
    check(BRPeerIsPinned(none, 0, node, 12024) == 0, "no pin set (zero addr/port) never matches");
    check(BRPeerIsPinned(node, 12024, none, 0) == 0, "zero candidate never matches a set pin");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
