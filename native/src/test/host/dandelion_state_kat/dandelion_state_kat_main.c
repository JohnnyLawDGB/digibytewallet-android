/*
 * dandelion_state_kat -- the bridge's remembered Dandelion state (bridge/dandelion_state.h).
 *
 * B234: SyncService applies the Dandelion setting and marks the seeder's capable peers at sync
 * start, BEFORE startSync creates the peer manager; the JNI setters dropped both when no manager
 * existed, and a recreated manager started from its own default (enabled, no capable peers). So a
 * stem was never possible after a restart. The bridge now remembers both and replays them onto
 * every manager it creates. This KAT pins the remembered state itself; run.sh pins the replay
 * wiring in jni_peer.c.
 */
#include <stdio.h>
#include <string.h>
#include "dandelion_state.h"

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else printf("ok: %s\n", msg); } while (0)

int main(void) {
    DandelionState s;
    dandelion_state_init(&s);
    CHECK(s.enabled == 0, "a fresh state is disabled (the user's setting is opt-in)");
    CHECK(s.count == 0, "a fresh state knows no capable peers");

    dandelion_state_set_enabled(&s, 1);
    CHECK(s.enabled == 1, "enabled is remembered");
    dandelion_state_set_enabled(&s, 0);
    CHECK(s.enabled == 0, "disabled is remembered");

    UInt128 a, b, bad;
    CHECK(dandelion_ipv4_mapped("203.0.113.7", &a) == 1, "an IPv4 literal parses");
    CHECK(a.u16[5] == 0xffff && a.u16[0] == 0 && a.u32[1] == 0, "it maps to ::ffff:x.x.x.x");
    const uint8_t want[4] = {203, 0, 113, 7};
    CHECK(memcmp(&a.u8[12], want, 4) == 0, "the address bytes are in network order");
    CHECK(dandelion_ipv4_mapped("not-an-ip", &bad) == 0, "a non-IPv4 string is refused");
    CHECK(dandelion_ipv4_mapped("2001:db8::1", &bad) == 0, "an IPv6 literal is refused (the seeder advertises IPv4)");

    CHECK(dandelion_state_add(&s, a) == 1, "a new address is remembered");
    CHECK(dandelion_state_add(&s, a) == 0, "a repeat is not remembered twice");
    CHECK(s.count == 1, "one address after a repeat");
    dandelion_ipv4_mapped("198.51.100.1", &b);
    dandelion_state_add(&s, b);
    CHECK(s.count == 2 && UInt128Eq(s.addrs[0], a) && UInt128Eq(s.addrs[1], b), "addresses kept in order for replay");

    /* Bounded: a hostile or broken seeder response cannot grow it without limit. */
    DandelionState full;
    dandelion_state_init(&full);
    int added = 0;
    for (int i = 0; i < DANDELION_STATE_MAX_ADDRS + 10; i++) {
        char ip[32];
        snprintf(ip, sizeof ip, "10.0.%d.%d", i / 256, i % 256);
        UInt128 x;
        dandelion_ipv4_mapped(ip, &x);
        if (dandelion_state_add(&full, x) == 1) added++;
    }
    CHECK(added == DANDELION_STATE_MAX_ADDRS && full.count == DANDELION_STATE_MAX_ADDRS, "capped at DANDELION_STATE_MAX_ADDRS");

    printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
