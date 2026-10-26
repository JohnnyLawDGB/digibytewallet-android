// Host KAT for BRPeerDialAddress.h -- the socket address the core dials for a peer.
//
// The rule: an IPv4 peer is resolved through getaddrinfo on its dotted-quad literal, so an
// IPv6-only network with DNS64/NAT64 can answer with a synthesized IPv6 address (Apple's
// documented path; App Review tests on such networks). On a network with IPv4 the answer, and
// so the dial, is the same AF_INET address as before. Until 2026-10 the core dialled the
// IPv4-mapped address directly, which routes nowhere on an IPv6-only network.
//
// The resolver is replaced by a stub that simulates the networks:
//   NAT64        answers like Apple's DNS64 resolver: 64:ff9b::/96 + the IPv4 address
//   PASSTHROUGH  the host's real getaddrinfo (a network with IPv4)
//   FAIL         the resolver fails
//   UNUSABLE     answers only with entries the dialler must skip
//
// Built twice by run.sh:
//   -DPEER_DIAL_ADDRESS_NAT64_UNFIXED  no resolver, as before; every RED case fails
//   (default)                          the shipped rule; every case passes

#include <netdb.h>
#include <stdlib.h>
#include <sys/un.h>

static int kat_getaddrinfo(const char *node, const char *service,
                           const struct addrinfo *hints, struct addrinfo **res);
static void kat_freeaddrinfo(struct addrinfo *res);
#define BR_PEER_DIAL_GETADDRINFO kat_getaddrinfo
#define BR_PEER_DIAL_FREEADDRINFO kat_freeaddrinfo
#include "BRPeerDialAddress.h"

#include <stdio.h>
#include <string.h>

enum { NAT64, PASSTHROUGH, FAIL, UNUSABLE };
static int g_mode = PASSTHROUGH;
static int g_calls = 0, g_frees = 0;
static char g_node[64], g_service[16];
static struct addrinfo g_hints;
static struct addrinfo *g_ours = NULL;   // the last answer the stub allocated itself

static int kat_getaddrinfo(const char *node, const char *service,
                           const struct addrinfo *hints, struct addrinfo **res)
{
    struct in_addr v4;
    g_calls++;
    snprintf(g_node, sizeof(g_node), "%s", node ? node : "(null)");
    snprintf(g_service, sizeof(g_service), "%s", service ? service : "(null)");
    if (hints) g_hints = *hints;
    *res = NULL;

    switch (g_mode) {
    case PASSTHROUGH:
        return getaddrinfo(node, service, hints, res);
    case FAIL:
        return EAI_NONAME;
    case NAT64: {
        // RFC 6052 well-known prefix. The port is deliberately left 0: the dialler must
        // set the peer's port itself rather than trust the resolver's.
        struct addrinfo *ai = calloc(1, sizeof(*ai));
        struct sockaddr_in6 *a6 = calloc(1, sizeof(*a6));
        if (! ai || ! a6 || inet_pton(AF_INET, node, &v4) != 1) { free(ai); free(a6); return EAI_NONAME; }
        a6->sin6_family = AF_INET6;
        a6->sin6_addr.s6_addr[0] = 0x00; a6->sin6_addr.s6_addr[1] = 0x64;
        a6->sin6_addr.s6_addr[2] = 0xff; a6->sin6_addr.s6_addr[3] = 0x9b;
        memcpy(&a6->sin6_addr.s6_addr[12], &v4, 4);
        ai->ai_family = AF_INET6; ai->ai_socktype = SOCK_STREAM;
        ai->ai_addr = (struct sockaddr *)a6; ai->ai_addrlen = sizeof(*a6);
        *res = g_ours = ai;
        return 0;
    }
    case UNUSABLE: {
        // An AF_UNIX entry, then an AF_INET6 entry too long for sockaddr_storage.
        struct addrinfo *a = calloc(1, sizeof(*a)), *b = calloc(1, sizeof(*b));
        struct sockaddr_un *un = calloc(1, sizeof(*un));
        uint8_t *big = calloc(1, sizeof(struct sockaddr_storage) + 16);
        if (! a || ! b || ! un || ! big) { free(a); free(b); free(un); free(big); return EAI_MEMORY; }
        un->sun_family = AF_UNIX;
        a->ai_family = AF_UNIX; a->ai_addr = (struct sockaddr *)un; a->ai_addrlen = sizeof(*un);
        ((struct sockaddr *)big)->sa_family = AF_INET6;
        b->ai_family = AF_INET6; b->ai_addr = (struct sockaddr *)big;
        b->ai_addrlen = sizeof(struct sockaddr_storage) + 16;
        a->ai_next = b;
        *res = g_ours = a;
        return 0;
    }
    }
    return EAI_FAIL;
}

static void kat_freeaddrinfo(struct addrinfo *res)
{
    g_frees++;
    if (res != g_ours) { freeaddrinfo(res); return; }
    while (res) {
        struct addrinfo *next = res->ai_next;
        free(res->ai_addr); free(res);
        res = next;
    }
    g_ours = NULL;
}

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static UInt128 mapped(const char *dotted)
{
    UInt128 a = UINT128_ZERO;
    a.u8[10] = a.u8[11] = 0xff;
    inet_pton(AF_INET, dotted, &a.u8[12]);
    return a;
}

static void reset(int mode) { g_mode = mode; g_calls = g_frees = 0; g_node[0] = g_service[0] = '\0'; memset(&g_hints, 0, sizeof(g_hints)); }

// The dial is AF_INET to dotted:port with the sockaddr_in length.
static int isIPv4Dial(int family, const struct sockaddr_storage *ss, socklen_t len, const char *dotted, uint16_t port)
{
    const struct sockaddr_in *a4 = (const struct sockaddr_in *)ss;
    struct in_addr want;
    inet_pton(AF_INET, dotted, &want);
    return family == AF_INET && a4->sin_family == AF_INET && len == (socklen_t)sizeof(*a4) &&
           a4->sin_addr.s_addr == want.s_addr && a4->sin_port == htons(port);
}

int main(void)
{
    const char *peer = "203.0.113.7";   // RFC 5737 documentation range: never dialled
    const uint16_t port = 12024;
    struct sockaddr_storage ss;
    socklen_t len = 0;
    char why[200], got[INET6_ADDRSTRLEN];
    int family;

    // 1. IPv6-only network with DNS64/NAT64: the dial is the synthesized IPv6 address.
    reset(NAT64);
    family = BRPeerDialAddress(mapped(peer), port, &ss, &len);
    {
        const struct sockaddr_in6 *a6 = (const struct sockaddr_in6 *)&ss;
        struct in6_addr want;
        inet_pton(AF_INET6, "64:ff9b::cb00:7107", &want);
        inet_ntop(AF_INET6, &a6->sin6_addr, got, sizeof(got));
        snprintf(why, sizeof(why), "family %d, address %s, port %u, len %u",
                 family, family == AF_INET6 ? got : "-", ntohs(a6->sin6_port), (unsigned)len);
        result("nat64_dials_synthesized_ipv6",
               family == AF_INET6 && a6->sin6_family == AF_INET6 && len == (socklen_t)sizeof(*a6) &&
               memcmp(&a6->sin6_addr, &want, sizeof(want)) == 0, why);
        result("nat64_dial_carries_the_peers_port",
               family == AF_INET6 && a6->sin6_port == htons(port), why);
    }

    // 2. What the resolver is asked: the dotted literal, the port, any family, a stream, the
    //    platform's flags (AI_DEFAULT on Apple, as its NAT64 guide does). Its answer is freed.
    snprintf(why, sizeof(why), "calls %d, frees %d, node \"%s\", service \"%s\", family %d, socktype %d, flags %d",
             g_calls, g_frees, g_node, g_service, g_hints.ai_family, g_hints.ai_socktype, g_hints.ai_flags);
    result("resolver_asked_for_the_literal",
           g_calls == 1 && strcmp(g_node, peer) == 0 && strcmp(g_service, "12024") == 0 &&
           g_hints.ai_family == AF_UNSPEC && g_hints.ai_socktype == SOCK_STREAM &&
           g_hints.ai_flags == BR_PEER_DIAL_AI_FLAGS, why);
    result("resolver_answer_freed", g_calls == 1 && g_frees == 1, why);

    // 3. A network with IPv4, through this host's REAL resolver: exactly the old dial.
    reset(PASSTHROUGH);
    family = BRPeerDialAddress(mapped(peer), port, &ss, &len);
    snprintf(why, sizeof(why), "family %d, len %u, calls %d, frees %d", family, (unsigned)len, g_calls, g_frees);
    result("ipv4_network_dials_the_same_address",
           isIPv4Dial(family, &ss, len, peer, port) && g_calls == g_frees, why);

    // 4. The resolver fails: the old dial.
    reset(FAIL);
    family = BRPeerDialAddress(mapped(peer), port, &ss, &len);
    snprintf(why, sizeof(why), "family %d, len %u", family, (unsigned)len);
    result("resolver_failure_dials_ipv4", isIPv4Dial(family, &ss, len, peer, port), why);

    // 5. Only unusable answers (AF_UNIX; an address longer than sockaddr_storage): skipped,
    //    never copied, freed; the old dial.
    reset(UNUSABLE);
    family = BRPeerDialAddress(mapped(peer), port, &ss, &len);
    snprintf(why, sizeof(why), "family %d, len %u, calls %d, frees %d", family, (unsigned)len, g_calls, g_frees);
    result("unusable_answers_dial_ipv4",
           isIPv4Dial(family, &ss, len, peer, port) && g_calls == g_frees, why);

    // 6. A native IPv6 peer is dialled as it is; the resolver is not asked.
    reset(NAT64);
    {
        UInt128 v6 = UINT128_ZERO;
        const struct sockaddr_in6 *a6 = (const struct sockaddr_in6 *)&ss;
        inet_pton(AF_INET6, "2001:db8::1", &v6);
        family = BRPeerDialAddress(v6, port, &ss, &len);
        snprintf(why, sizeof(why), "family %d, len %u, calls %d", family, (unsigned)len, g_calls);
        result("native_ipv6_peer_dialled_directly",
               family == AF_INET6 && a6->sin6_family == AF_INET6 && len == (socklen_t)sizeof(*a6) &&
               memcmp(&a6->sin6_addr, &v6, 16) == 0 && a6->sin6_port == htons(port) && g_calls == 0, why);
    }

    // 7. What counts as an IPv4 peer: only the IPv4-mapped form the manager stores.
    {
        UInt128 compat = UINT128_ZERO, v6 = UINT128_ZERO;
        inet_pton(AF_INET6, "::203.0.113.7", &compat);   // IPv4-compatible (deprecated), not mapped
        inet_pton(AF_INET6, "2001:db8::1", &v6);
        result("ipv4_mapped_detection",
               BRPeerAddressIsIPv4Mapped(mapped(peer)) && ! BRPeerAddressIsIPv4Mapped(compat) &&
               ! BRPeerAddressIsIPv4Mapped(v6), NULL);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
