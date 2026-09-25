/*
 * dandelion_state.h
 *
 * The bridge's remembered Dandelion state: the user's setting and the addresses the seeder
 * advertised as Dandelion-capable. Pure (no JNI) so a host KAT can exercise it
 * (native/src/test/host/dandelion_state_kat).
 *
 * SyncService applies both at sync start, before startSync creates the peer manager, and a
 * manager can be freed and rebuilt in-process. The JNI setters used to hand them straight to
 * the manager and drop them when there was none, so after any app restart the manager ran on
 * its own default (enabled, no capable peers) and never stemmed (B234). jni_peer.c now records
 * both here unconditionally and replays them onto every manager it creates.
 */
#ifndef DANDELION_STATE_H
#define DANDELION_STATE_H

#include <stddef.h>
#include <string.h>
#include <arpa/inet.h>
#include "BRInt.h"

/* The seeder advertises a handful; the cap only bounds a broken or hostile response. */
#define DANDELION_STATE_MAX_ADDRS 64

typedef struct {
    int enabled;                                  /* opt-in: 0 until the user's setting says so */
    size_t count;
    UInt128 addrs[DANDELION_STATE_MAX_ADDRS];     /* first-seen order, no repeats */
} DandelionState;

static inline void dandelion_state_init(DandelionState *s) {
    memset(s, 0, sizeof *s);
}

static inline void dandelion_state_set_enabled(DandelionState *s, int enabled) {
    s->enabled = (enabled != 0);
}

/* 1 = remembered, 0 = already known, -1 = full. */
static inline int dandelion_state_add(DandelionState *s, UInt128 addr) {
    for (size_t i = 0; i < s->count; i++) {
        if (UInt128Eq(s->addrs[i], addr)) return 0;
    }
    if (s->count >= DANDELION_STATE_MAX_ADDRS) return -1;
    s->addrs[s->count++] = addr;
    return 1;
}

/* An IPv4 literal as the IPv4-mapped IPv6 address (::ffff:x.x.x.x) the peer manager keys peers
 * by. Returns 1 on success, 0 if [ip] is not an IPv4 literal. */
static inline int dandelion_ipv4_mapped(const char *ip, UInt128 *out) {
    struct in_addr ip4;
    if (inet_pton(AF_INET, ip, &ip4) != 1) return 0;
    UInt128 addr = UINT128_ZERO;
    addr.u16[5] = 0xffff;
    addr.u32[3] = ip4.s_addr;
    *out = addr;
    return 1;
}

#endif /* DANDELION_STATE_H */
