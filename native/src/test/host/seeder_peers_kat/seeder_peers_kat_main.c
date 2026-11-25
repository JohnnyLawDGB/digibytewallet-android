// Host KAT for BRSeederPeers.h -- which seeder peers are added, with which services, and when
// the cached list is fetched again. Carries the cases of PeerServicesPolicyTest (the Kotlin
// parseSeederServicesHex that BRSeederServicesParse replaced -> jni_seeder_peers.c), the JNI
// bridge's old INJECT_DEFAULT_SERVICES default (now BRSeederPeerServices in injectPeerByIp), and
// the cases of SeederRefreshPolicyTest (the Kotlin isStale that BRSeederShouldRefresh replaced,
// Android #187 -> jni_seeder_peers.c).
//
// BRPeerManagerAddPeer is replaced by a recording stub: the KAT checks what is handed to the
// manager, not the manager.
//
// Built four times by run.sh:
//   -DSEEDER_PEERS_RED    the "0x" prefix is not removed and unknown services are not defaulted;
//                         the service cases fail, the refresh cases hold
//   -DSEEDER_SERVICES_RED the Kotlin parse's ASCII leniencies (a sign, leading zeros past 16
//                         digits, "0x" then "0X" removed); the three cases for those forms fail
//   -DSEEDER_REFRESH_RED  an empty cache waits out the hour and an hour-old list is refetched;
//                         those two refresh cases fail, every other case holds
//   -DSEEDER_CAP_RED -DSEEDER_CAP_UNFIXED
//                         no bound on an answer, on a cached pool or on the bridge's saved-peer
//                         array (BRSeederPeersAccepted, BRSeederPoolDrop,
//                         saved_peers_prepend_bounded); the four "bounded" cases fail
//   (default)             the shipped rule; every case passes

#include "BRSeederPeers.h"
#include "saved_peers_deserialize.h"   // the bridge's saved-peer array (SEEDER-PEER-CAP)
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

    // The seeder's shape ("0x44d") and the forms Kotlin's removePrefix/toLongOrNull(16) read alike.
    ok = parse("0x44d") == 0x44d && parse("0X44D") == 0x44d && parse(" 0x40 ") == 0x40 && parse("44d") == 0x44d &&
         parse("  0X44D  ") == 0x44d;
    snprintf(why, sizeof(why), "0x44d -> %llx", (unsigned long long)parse("0x44d"));
    result("services_hex_parsed", ok, why);

    // PeerServicesPolicyTest: a bloom-only peer (0x40d) carries no compact-filter bit; 0x44d does.
    ok = parse("0x40d") == 0x40d && (parse("0x40d") & SERVICES_NODE_COMPACT_FILTERS) == 0 &&
         (parse("0x44d") & SERVICES_NODE_COMPACT_FILTERS) == SERVICES_NODE_COMPACT_FILTERS;
    result("bloom_only_peer_has_no_filter_bit", ok, NULL);

    // Odd length, either case, ASCII space of every kind, up to 16 digits.
    ok = parse("4") == 4 && parse("AbCdEf") == 0xabcdef && parse("\t\v44d\f\r\n") == 0x44d &&
         parse("000000000000044d") == 0x44d && parse("7FFFFFFFFFFFFFFF") == INT64_MAX;
    result("odd_length_and_either_case_read", ok, NULL);

    ok = parse("") == 0 && parse("   ") == 0 && parse("not-hex") == 0 && parse("0x") == 0 && parse("0xzz") == 0 &&
         parse("12345678901234567") == 0 && parse("8000000000000000") == 0 && parse("7fffffffffffffff") == INT64_MAX &&
         BRSeederServicesParse(NULL, 0) == 0 && parse("-") == 0 && parse("0x 44d") == 0;
    result("unreadable_services_are_unknown", ok, NULL);

    // What the Kotlin read and the core does not (each is 0, "unknown", so tagged as the canon).
    // Kotlin's toLongOrNull took a sign: "-1" was -1, every service bit, which skipped the default
    // (unknown_services_tagged_as_the_canon checks the default the 0 now gets).
    ok = parse("-1") == 0 && parse("+44d") == 0 && parse("-8000000000000000") == 0 && parse("0x-1") == 0 &&
         parse("-0") == 0;
    snprintf(why, sizeof(why), "-1 -> %llx, +44d -> %llx", (unsigned long long)parse("-1"), (unsigned long long)parse("+44d"));
    result("signed_services_are_unknown", ok, why);

    // Kotlin read any number of leading zeros; the core reads at most 16 digits.
    ok = parse("0000000000000000044d") == 0 && parse("0x00000000000000000") == 0;
    snprintf(why, sizeof(why), "19 digits -> %llx", (unsigned long long)parse("0000000000000000044d"));
    result("leading_zeros_past_16_digits_are_unknown", ok, why);

    // Kotlin removed "0x" and then "0X"; the core removes one prefix.
    ok = parse("0x0X44d") == 0 && parse("0x0x44d") == 0 && parse("0X0x44d") == 0;
    snprintf(why, sizeof(why), "0x0X44d -> %llx", (unsigned long long)parse("0x0X44d"));
    result("second_prefix_is_unknown", ok, why);

    // Kotlin's trim() and Character.digit were Unicode-wide. The JNI hands the core modified UTF-8
    // and iOS hands it UTF-8; either way these bytes are not ASCII hex or space.
    ok = parse("\xef\xbc\x94\xef\xbc\x94" "d") == 0 &&       /* fullwidth "44" + d */
         parse("\xd9\xa4\xd9\xa4" "d") == 0 &&               /* Arabic-Indic "44" + d */
         parse("\xc2\xa0" "0x44d") == 0 &&                     /* NO-BREAK SPACE before */
         parse("0x44d\xe3\x80\x80") == 0 &&                /* IDEOGRAPHIC SPACE after */
         parse("\x1c" "0x44d") == 0 &&                        /* FILE SEPARATOR, which trim() removed */
         parse("\xc0\x80") == 0 &&                          /* U+0000 in modified UTF-8 */
         BRSeederServicesParse("44d\0", 4) == 0;              /* U+0000 as iOS hands it */
    result("non_ascii_forms_are_unknown", ok, NULL);

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
    {   // injectPeerByIp keeps a peer for the next manager with the same address rule.
        UInt128 a;
        ok = ok && BRSeederPeerAddress("203.0.113.9", &a) && a.u16[5] == 0xffff && a.u8[15] == 9 &&
             ! BRSeederPeerAddress("example.com", &a) && ! BRSeederPeerAddress("203.0.113", &a) &&
             ! BRSeederPeerAddress("2001:db8::1", &a) && ! BRSeederPeerAddress(NULL, &a);
    }
    result("only_ipv4_literals_added", ok, NULL);

    // The refresh decision. now and the cases are SeederRefreshPolicyTest's.
    {
        const int64_t now = 1800000000000LL, hour = BR_SEEDER_REFRESH_MS;

        ok = hour == 3600000LL;                     // the interval every Kotlin caller passed
        result("refresh_interval_is_one_hour", ok, NULL);

        ok = BRSeederShouldRefresh(now, now, 0) && BRSeederShouldRefresh(now - 1000, now, 0);
        result("empty_cache_refreshes", ok, NULL);

        ok = ! BRSeederShouldRefresh(now - hour, now, 5);
        result("one_hour_old_is_kept", ok, NULL);

        ok = BRSeederShouldRefresh(now - hour - 1, now, 5);
        result("over_the_hour_refreshes", ok, NULL);

        ok = BRSeederShouldRefresh(0, now, 5);      // last_fetch absent reads as 0
        result("never_fetched_refreshes", ok, NULL);

        ok = ! BRSeederShouldRefresh(now - 1000, now, 5) && ! BRSeederShouldRefresh(now, now - 5000, 5) &&
             ! BRSeederShouldRefresh(now, now, 1);  // clock went back: keep
        result("fresh_list_kept", ok, NULL);

        // Kotlin's nowMs - lastFetchMs wraps: MIN - 1 is MAX (stale), MIN - MAX is 1 (kept).
        ok = BRSeederShouldRefresh(1, INT64_MIN, 5) && ! BRSeederShouldRefresh(INT64_MAX, INT64_MIN, 5);
        result("differences_wrap_as_kotlin_long", ok, NULL);
    }

    // SEEDER-PEER-CAP: how much of an answer is taken, how a pool makes room, and the bridge's
    // saved-peer array. The seeder serves a few dozen peers; these are the limits when it does not.
    {
        const size_t max = BR_SEEDER_MAX_PEERS, poolMax = BR_SEEDER_POOL_MAX_PEERS;

        ok = max == 1024 && poolMax == 4096 && max <= poolMax && poolMax > (size_t)SAVED_PEERS_MAX_COUNT;
        result("cap_values", ok, NULL);

        ok = BRSeederPeersAccepted(0) == 0 && BRSeederPeersAccepted(1) == 1 && BRSeederPeersAccepted(37) == 37 &&
             BRSeederPeersAccepted(max) == max;
        result("an_ordinary_answer_is_taken_whole", ok, NULL);

        ok = BRSeederPeersAccepted(max + 1) == max && BRSeederPeersAccepted(14000) == max &&
             BRSeederPeersAccepted(SIZE_MAX) == max;
        snprintf(why, sizeof(why), "14000 listed -> %zu taken", BRSeederPeersAccepted(14000));
        result("a_long_answer_is_bounded", ok, why);

        ok = BRSeederPoolDrop(0, 0) == 0 && BRSeederPoolDrop(0, max) == 0 && BRSeederPoolDrop(100, 37) == 0 &&
             BRSeederPoolDrop(poolMax - max, max) == 0;
        result("a_pool_with_room_drops_nothing", ok, NULL);

        // Whatever the pool held, what is kept plus what is added fits the limit, and never
        // more is dropped than the pool holds.
        ok = BRSeederPoolDrop(poolMax, 1) == 1 && BRSeederPoolDrop(poolMax, max) == max &&
             BRSeederPoolDrop(4000, 200) == 104 && BRSeederPoolDrop(poolMax + 5000, max) == 5000 + max &&
             BRSeederPoolDrop(10, poolMax + 7) == 10 && BRSeederPoolDrop(SIZE_MAX, max) == SIZE_MAX - (poolMax - max) &&
             BRSeederPoolDrop(SIZE_MAX, SIZE_MAX) == SIZE_MAX;
        snprintf(why, sizeof(why), "4000 pooled + 200 -> drop %zu", BRSeederPoolDrop(4000, 200));
        result("a_full_pool_drops_its_oldest", ok, why);
    }
    {
        BRPeer *arr = NULL;
        size_t n = 0, i;
        UInt128 a = UINT128_ZERO;
        int added;

        // A new address goes to the front; the same address again changes nothing but its time
        // and its services, which are OR-ed.
        a.u16[5] = 0xffff; a.u32[3] = 1;
        added = saved_peers_prepend_bounded(&arr, &n, a, 12024, 0x09, 100);
        ok = added == 1 && n == 1 && arr && arr[0].port == 12024 && arr[0].services == 0x09 && arr[0].timestamp == 100;
        a.u32[3] = 2;
        added = saved_peers_prepend_bounded(&arr, &n, a, 12025, 0x40, 101);
        ok = ok && added == 1 && n == 2 && arr[0].port == 12025 && arr[1].port == 12024;
        a.u32[3] = 1;
        added = saved_peers_prepend_bounded(&arr, &n, a, 12024, 0x40, 102);
        ok = ok && added == 0 && n == 2 && arr[1].services == 0x49 && arr[1].timestamp == 102 && arr[0].port == 12025;
        result("a_known_address_is_not_added_twice", ok, NULL);

        // 14,000 distinct addresses, as one long answer could carry: the array stops at its
        // limit, the newest is at the front, and the first ones added are the ones that went.
        for (i = 0; i < 14000; i++) {
            a.u32[3] = (uint32_t)(1000 + i);
            saved_peers_prepend_bounded(&arr, &n, a, (uint16_t)(i & 0x7fff), 0x40, 200 + i);
        }
        ok = n == (size_t)SAVED_PEERS_MAX_COUNT && arr[0].address.u32[3] == 1000 + 13999 &&
             arr[n - 1].address.u32[3] == 1000 + 14000 - (uint32_t)SAVED_PEERS_MAX_COUNT;
        snprintf(why, sizeof(why), "array holds %zu after 14002 distinct addresses (limit %d)", n, SAVED_PEERS_MAX_COUNT);
        result("the_saved_peer_array_is_bounded", ok, why);
        free(arr);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
