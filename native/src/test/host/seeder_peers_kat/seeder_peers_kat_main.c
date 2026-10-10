// Host KAT for BRSeederPeers.h -- which seeder peers are added, with which services, and when
// the cached list is fetched again. Carries Android's parseSeederServicesHex behaviour and the
// JNI bridge's INJECT_DEFAULT_SERVICES default.
//
// BRPeerManagerAddPeer is replaced by a recording stub: the KAT checks what is handed to the
// manager, not the manager.
//
// Built twice by run.sh:
//   -DSEEDER_PEERS_RED  the "0x" prefix is not removed and unknown services are not defaulted;
//                       every RED case fails
//   (default)           the shipped rule; every case passes

#include "BRSeederPeers.h"
#include <stdio.h>

static int g_failed = 0;
static int g_calls = 0;
static UInt128 g_addr;
static uint16_t g_port;
static uint64_t g_services;

int BRPeerManagerAddPeer(BRPeerManager *manager, UInt128 address, uint16_t port, uint64_t services)
{
    (void)manager;
    g_calls++; g_addr = address; g_port = port; g_services = services;
    return 1;
}

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static uint64_t parse(const char *s) { return BRSeederServicesParse(s, strlen(s)); }

int main(void)
{
    char why[200];
    int ok;
    BRPeerManager *fake = (BRPeerManager *)(uintptr_t)0x1;   // never dereferenced (stub)

    // The seeder's shape ("0x44d") and Kotlin's removePrefix/toLongOrNull(16).
    ok = parse("0x44d") == 0x44d && parse("0X44D") == 0x44d && parse(" 0x40 ") == 0x40 && parse("44d") == 0x44d;
    snprintf(why, sizeof(why), "0x44d -> %llx", (unsigned long long)parse("0x44d"));
    result("services_hex_parsed", ok, why);

    ok = parse("") == 0 && parse("0x") == 0 && parse("0xzz") == 0 && parse("12345678901234567") == 0 &&
         parse("8000000000000000") == 0 && parse("7fffffffffffffff") == INT64_MAX && BRSeederServicesParse(NULL, 0) == 0 &&
         parse("-1") == 0;
    result("unreadable_services_are_unknown", ok, NULL);

    ok = BRSeederPeerServices(0) == (SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS) &&
         BRSeederPeerServices(0x44d) == 0x44d;
    result("unknown_services_tagged_as_the_canon", ok, NULL);

    g_calls = 0;
    ok = BRSeederPeerAdd(fake, "203.0.113.9", 12024, parse("")) == 1 && g_calls == 1 && g_port == 12024 &&
         g_services == (SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS) &&
         g_addr.u16[5] == 0xffff && g_addr.u8[12] == 203 && g_addr.u8[13] == 0 && g_addr.u8[14] == 113 && g_addr.u8[15] == 9 &&
         g_addr.u64[0] == 0 && g_addr.u16[4] == 0;
    snprintf(why, sizeof(why), "calls %d services %llx", g_calls, (unsigned long long)g_services);
    result("untagged_peer_added_filter_capable", ok, why);

    g_calls = 0;
    ok = BRSeederPeerAdd(fake, "203.0.113.9", 12024, parse("0x44d")) == 1 && g_services == 0x44d;
    result("tagged_peer_keeps_its_services", ok, NULL);

    g_calls = 0;
    ok = BRSeederPeerAdd(fake, "2001:db8::1", 12024, 0) == 0 && BRSeederPeerAdd(fake, "example.com", 12024, 0) == 0 &&
         BRSeederPeerAdd(fake, "203.0.113", 12024, 0) == 0 && BRSeederPeerAdd(NULL, "203.0.113.9", 12024, 0) == 0 &&
         BRSeederPeerAdd(fake, NULL, 12024, 0) == 0 && g_calls == 0;
    result("only_ipv4_literals_added", ok, NULL);

    ok = BRSeederShouldRefresh(0, 1790000000000LL, 5) && BRSeederShouldRefresh(1790000000000LL, 1790000000000LL, 0) &&
         ! BRSeederShouldRefresh(1790000000000LL, 1790000000000LL + BR_SEEDER_REFRESH_MS, 5) &&
         BRSeederShouldRefresh(1790000000000LL, 1790000000000LL + BR_SEEDER_REFRESH_MS + 1, 5) &&
         ! BRSeederShouldRefresh(1790000000000LL, 1790000000000LL - 5000, 5);   // clock went back: keep
    result("refresh_rule", ok, NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
