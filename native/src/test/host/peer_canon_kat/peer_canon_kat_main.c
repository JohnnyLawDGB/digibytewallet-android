// Host KAT: proves BRPeerCanon.h's two load-bearing properties -- every canon
// entry is an IPv4 literal (no resolver on the bootstrap path) and the table
// is what the wallet dials (counts, ports, tagging, ordering) -- and, the part
// a table test would miss, that the header's resolver-free parser produces
// EXACTLY the IPv4-mapped UInt128 that inet_pton + the peer manager's mapping
// produce, so the canon addresses compare equal to what BRPeerManager holds.
//
// Moved out of jni_peer.c (an Android-only compilation unit) so the iOS
// XCFramework carries the same canon instead of a second copy of it.
#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>

#include "BRPeerCanon.h"
#include "BRPeerCanon.h"      // include twice: the guard must hold
#include "BRPeerServices.h"   // the real CF accept predicate, for the tagging cross-check

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// The mapping jni_peer.c and BRPeerManager.c both build by hand:
// ::ffff:a.b.c.d with the IPv4 in network byte order in u32[3].
static int mapViaInetPton(const char *s, UInt128 *out)
{
    struct in_addr ip4;
    UInt128 addr = UINT128_ZERO;
    if (inet_pton(AF_INET, s, &ip4) != 1) return 0;
    addr.u16[5] = 0xffff;
    addr.u32[3] = ip4.s_addr;
    *out = addr;
    return 1;
}

static void checkSet(int testnet, const char *label, size_t expectedCount)
{
    char d[160];
    size_t count = 0, i, j;
    const char *const *ips = BRPeerCanonIPs(testnet, &count);
    UInt128 parsed[BR_PEER_CANON_MAX_COUNT];
    size_t written;

    snprintf(d, sizeof(d), "%s: count is %zu", label, expectedCount);
    check(count == expectedCount && BRPeerCanonCount(testnet) == expectedCount, d);
    snprintf(d, sizeof(d), "%s: count fits the MAX_COUNT stack bound", label);
    check(count <= BR_PEER_CANON_MAX_COUNT, d);

    // test1 -- every entry is an IPv4 LITERAL. This is the RED-gate check: the
    // pre-oracle-bootstrap wallet resolved a hostname here.
    for (i = 0; i < count; i++) {
        UInt128 mine, theirs;
        int ok = ips[i] != NULL && BRPeerCanonParseIPv4(ips[i], &mine);
        snprintf(d, sizeof(d), "test1: %s entry %zu (%s) is an IPv4 literal, not a hostname",
                 label, i, ips[i] ? ips[i] : "(null)");
        check(ok, d);
        if (! ok) continue;

        // test2 -- and the resolver-free parse equals inet_pton's mapping.
        snprintf(d, sizeof(d), "test2: %s entry %zu parses identically to inet_pton", label, i);
        check(mapViaInetPton(ips[i], &theirs) && UInt128Eq(mine, theirs), d);
        snprintf(d, sizeof(d), "test2: %s entry %zu is IPv4-mapped (::ffff:)", label, i);
        check(mine.u16[5] == 0xffff && mine.u64[0] == 0 && mine.u16[4] == 0, d);
        parsed[i] = mine;
    }

    // test3 -- no duplicates: a repeated IP is one fewer real peer than the
    // count claims, and the quorum maths counts entries.
    for (i = 0; i < count; i++) {
        for (j = i + 1; j < count; j++) {
            if (ips[i] && ips[j] && strcmp(ips[i], ips[j]) == 0) {
                snprintf(d, sizeof(d), "test3: %s entries %zu and %zu are duplicates (%s)",
                         label, i, j, ips[i]);
                check(0, d);
            }
        }
    }

    // test4 -- BRPeerCanonAddrs writes every entry (nothing skipped) and
    // respects the caller's bound.
    {
        UInt128 out[BR_PEER_CANON_MAX_COUNT];
        written = BRPeerCanonAddrs(testnet, out, BR_PEER_CANON_MAX_COUNT);
        snprintf(d, sizeof(d), "test4: %s Addrs writes all %zu entries", label, count);
        check(written == count, d);
        for (i = 0; i < written && i < count; i++) {
            if (! UInt128Eq(out[i], parsed[i])) {
                snprintf(d, sizeof(d), "test4: %s Addrs entry %zu preserves table order", label, i);
                check(0, d);
            }
        }
        written = BRPeerCanonAddrs(testnet, out, 2);
        snprintf(d, sizeof(d), "test4: %s Addrs honours max=2", label);
        check(written == 2, d);
        written = BRPeerCanonAddrs(testnet, out, 0);
        snprintf(d, sizeof(d), "test4: %s Addrs with max=0 writes nothing", label);
        check(written == 0, d);
    }

    // test5 -- Contains agrees with the table, in the peer manager's encoding.
    for (i = 0; i < count; i++) {
        UInt128 theirs;
        if (! mapViaInetPton(ips[i], &theirs)) continue;
        snprintf(d, sizeof(d), "test5: %s Contains(entry %zu) via the peer manager's mapping", label, i);
        check(BRPeerCanonContains(testnet, theirs), d);
    }
    {
        UInt128 stranger;
        mapViaInetPton("192.0.2.1", &stranger);   // TEST-NET-1, never a real peer
        snprintf(d, sizeof(d), "test5: %s does not Contain 192.0.2.1", label);
        check(! BRPeerCanonContains(testnet, stranger), d);
        snprintf(d, sizeof(d), "test5: %s does not Contain ::", label);
        check(! BRPeerCanonContains(testnet, UINT128_ZERO), d);
    }

    // test6 -- IPAt bounds.
    snprintf(d, sizeof(d), "test6: %s IPAt(0) is the first entry", label);
    check(BRPeerCanonIPAt(testnet, 0) == ips[0], d);
    snprintf(d, sizeof(d), "test6: %s IPAt(count) is NULL", label);
    check(BRPeerCanonIPAt(testnet, count) == NULL, d);
}

int main(void)
{
    UInt128 a;

    checkSet(0, "mainnet", BR_PEER_CANON_MAINNET_COUNT);
    checkSet(1, "testnet26", BR_PEER_CANON_TESTNET_COUNT);

    // test7 -- ordering facts the injection order depends on. digiscope.me is
    // one-of-N but FIRST; the verified testnet26 filter node is first.
    check(strcmp(BRPeerCanonIPAt(0, 0), "134.199.198.90") == 0,
          "test7: mainnet[0] is 134.199.198.90 (digiscope.me, demoted to one-of-N)");
    check(strcmp(BRPeerCanonIPAt(1, 0), "95.111.238.51") == 0,
          "test7: testnet26[0] is 95.111.238.51 (the verified CF node)");

    // (No port check: the header deliberately does not restate the P2P port.
    // It is BRChainParams.h's standardPort, and callers dial the canon there.)

    // test9 -- tagging: the canon must pass the REAL CF accept predicate and
    // must not be bloom-tagged.
    check(BRPeerServicesAllowedForSyncMode(BR_PEER_CANON_SERVICES, 0) == 1,
          "test9: canon services pass BRPeerServicesAllowedForSyncMode");
    check((BR_PEER_CANON_SERVICES & SERVICES_NODE_NETWORK) != 0,
          "test9: canon services carry NODE_NETWORK");
    check((BR_PEER_CANON_SERVICES & SERVICES_NODE_BLOOM) == 0,
          "test9: canon services are not bloom-tagged");
    check(BR_PEER_CANON_SERVICES == 0x41,
          "test9: canon services are 0x41 -- what SyncService.kt used to hardcode");

    // test10 -- the parser rejects what inet_pton rejects, and a hostname.
    check(! BRPeerCanonParseIPv4("digiscope.me", &a),      "test10: rejects a hostname");
    check(! BRPeerCanonParseIPv4("", &a),                  "test10: rejects empty");
    check(! BRPeerCanonParseIPv4(NULL, &a),                "test10: rejects NULL");
    check(! BRPeerCanonParseIPv4("1.2.3", &a),             "test10: rejects three octets");
    check(! BRPeerCanonParseIPv4("1.2.3.4.5", &a),         "test10: rejects five octets");
    check(! BRPeerCanonParseIPv4("256.1.1.1", &a),         "test10: rejects an octet over 255");
    check(! BRPeerCanonParseIPv4("01.2.3.4", &a),          "test10: rejects a leading zero (as inet_pton does)");
    check(! BRPeerCanonParseIPv4(" 1.2.3.4", &a),          "test10: rejects leading whitespace");
    check(! BRPeerCanonParseIPv4("1.2.3.4 ", &a),          "test10: rejects trailing whitespace");
    check(! BRPeerCanonParseIPv4("1.2.3.4:12024", &a),     "test10: rejects a trailing port");
    check(! BRPeerCanonParseIPv4("1..2.3", &a),            "test10: rejects an empty octet");
    check(! BRPeerCanonParseIPv4("1.2.3.", &a),            "test10: rejects a trailing dot");
    check(! BRPeerCanonParseIPv4("::ffff:1.2.3.4", &a),    "test10: rejects IPv6 notation");
    check(BRPeerCanonParseIPv4("0.0.0.0", &a) && a.u16[5] == 0xffff && a.u32[3] == 0,
          "test10: accepts 0.0.0.0 (single zeros are not leading zeros)");
    check(BRPeerCanonParseIPv4("255.255.255.255", &a) && a.u32[3] == 0xffffffffu,
          "test10: accepts 255.255.255.255");
    {
        UInt128 theirs;
        check(BRPeerCanonParseIPv4("10.200.3.40", &a) && mapViaInetPton("10.200.3.40", &theirs)
              && UInt128Eq(a, theirs),
              "test10: byte order matches inet_pton on an asymmetric address");
    }

    // test11 -- the two networks are distinct tables (a shared entry is fine;
    // an identical table would mean testnet was dialling mainnet).
    check(BRPeerCanonIPs(0, NULL) != BRPeerCanonIPs(1, NULL),
          "test11: mainnet and testnet26 are different tables");

    if (g_fail) { printf("%d check(s) FAILED\n", g_fail); return 1; }
    printf("peer_canon_kat: all checks passed\n");
    return 0;
}
