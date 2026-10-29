// Host KAT for BROwnNode.h -- the own node's address, link, IPv4 test, lookup rule and health.
// Android's CustomNodeTest, OwnNodeUriTest and OwnNodeAddressTest, case for case and value for
// value (the label's display filter and cap are the platform's; here the label is checked raw),
// plus the edges: Kotlin's trim and toIntOrNull, Android's URLDecoder, repeated query keys.
// URLDecoder cases follow Android's libcore (a non-hex char after '%' throws, so the raw value
// is used), not desktop Java, which the Kotlin unit tests run on.
//
// Built five times by run.sh:
//   -DOWN_NODE_RED         a host name is looked up even with Tor on
//   -DOWN_NODE_RED_HEALTH  status 1 reads connecting with no time limit (the "Connecting..." forever bug)
//   -DOWN_NODE_RED_GAP     a gap between polls (a suspension) is counted as time spent dialling
//   -DOWN_NODE_RED_PIN     the watchdogs pin over the own node
//   (default)              the shipped rules; every case passes
// In each red arm exactly that arm's cases fail and every other case passes.

#include "BROwnNode.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static char H[BR_OWN_NODE_HOST_MAX];
static uint16_t P;

static int hp(const char *raw, const char *host, uint16_t port)
{
    return BROwnNodeParseHostPort(raw, 12024, H, sizeof(H), &P) && strcmp(H, host) == 0 && P == port;
}
static int hpNo(const char *raw) { return BROwnNodeParseHostPort(raw, 12024, H, sizeof(H), &P) == 0; }

static BROwnNodeNet N;
static char L[512];
static long LL;
static int uri(const char *raw) { return BROwnNodeUriParse(raw, 12024, H, sizeof(H), &P, &N, L, sizeof(L), &LL); }
static int label(const char *raw, const char *want, long wantLen)
{
    return uri(raw) && LL == wantLen && (wantLen < 0 || memcmp(L, want, (size_t)wantLen) == 0);
}

int main(void)
{
    char ip[BR_OWN_NODE_IPV4_MAX];

    // ---- CustomNodeTest ----
    result("bare_host_gets_default_port", hp("node.example.com", "node.example.com", 12024));
    result("host_with_port_parses", hp("  10.0.0.5:12099 ", "10.0.0.5", 12099));
    result("blank_is_null", hpNo("   "));
    result("empty_host_is_null", hpNo(":12024"));
    result("bad_port_is_null", hpNo("host:notaport"));
    result("out_of_range_port_is_null", hpNo("host:0") && hpNo("host:70000"));
    result("scheme_prefix_rejected", hpNo("http://host:12024"));
    result("ipv6_rejected", hpNo("2001:db8::1"));

    // ---- OwnNodeUriTest ----
    result("uri_parses_scheme_host_port", uri("dgbnode://10.0.0.5:12024") && strcmp(H, "10.0.0.5") == 0 && P == 12024 &&
           N == BROwnNodeNetUnspecified && LL == -1);
    result("uri_defaults_port", uri("dgbnode://node.example.com") && strcmp(H, "node.example.com") == 0 && P == 12024);
    result("uri_captures_net_and_label", label("dgbnode://10.0.0.5:12024?net=mainnet&label=My%20Node", "My Node", 7) &&
           N == BROwnNodeNetMainnet);
    result("uri_raw_host_port_falls_through", uri("10.0.0.5:12024") && strcmp(H, "10.0.0.5") == 0 && P == 12024);
    result("uri_rejects_onion", ! uri("dgbnode://abcd.onion:12024"));
    result("uri_rejects_bad_port", ! uri("dgbnode://host:70000"));
    {   char raw[128] = "dgbnode://h:1?label=";
        memset(raw + strlen(raw), 'x', 80);
        result("uri_label_is_returned_whole", uri(raw) && LL == 80);   // the 32-unit cap is the platform's display rule
    }
    result("uri_garbage_is_null", ! uri("\xEF\xBF\xBF not a uri  "));
    result("uri_blank_is_null", ! uri("   "));

    // ---- OwnNodeAddressTest ----
    {   const char *hosts[] = { "node.example.org", "abcdefghijklmnop.onion", "localhost" };
        int i, ok = 1;
        for (i = 0; i < 3; i++) ok &= BROwnNodeResolvePlan(hosts[i], 1, ip, sizeof(ip)) == BROwnNodeRefusedUnderTor;
        result("tor_on_host_name_refused_not_looked_up", ok);
    }
    result("ipv4_used_as_given_tor_on_or_off",
           BROwnNodeResolvePlan("203.0.113.5", 1, ip, sizeof(ip)) == BROwnNodeUseAddress && strcmp(ip, "203.0.113.5") == 0 &&
           BROwnNodeResolvePlan("203.0.113.5", 0, ip, sizeof(ip)) == BROwnNodeUseAddress && strcmp(ip, "203.0.113.5") == 0);
    result("tor_off_host_name_is_looked_up", BROwnNodeResolvePlan("node.example.org", 0, ip, sizeof(ip)) == BROwnNodeNeedsLookup);
    {   const char *bad[] = { "256.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.x", "0x7f.0.0.1", "-1.2.3.4", "1..2.3", "",
                              "node.example.org", "1234.1.1.1", "1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17.18.19.20" };
        int i, ok = BROwnNodeIPv4Literal("010.1.2.3", ip, sizeof(ip)) && strcmp(ip, "10.1.2.3") == 0 &&
                    BROwnNodeIPv4Literal(" 255.255.255.255 ", ip, sizeof(ip)) && strcmp(ip, "255.255.255.255") == 0;
        for (i = 0; i < (int)(sizeof(bad) / sizeof(bad[0])); i++) ok &= ! BROwnNodeIPv4Literal(bad[i], ip, sizeof(ip));
        result("only_a_dotted_quad_is_an_address", ok);
    }

    // ---- beyond Android's tests ----
    result("kotlin_trim_unicode_spaces",
           hp("\xC2\xA0" "10.0.0.5:12099\xE3\x80\x80", "10.0.0.5", 12099) && hp("\xE2\x80\xA8host\x1F", "host", 12024) &&
           hp("host :\xE2\x80\x80 7 \xE2\x81\x9F", "host", 7) &&
           hp("\xC2\x85host", "\xC2\x85host", 12024));                      // U+0085 is not Kotlin whitespace
    result("port_as_kotlin_to_int_or_null",
           hp("host:+12024", "host", 12024) && hp("host:012024", "host", 12024) && hp("host: 12024 ", "host", 12024) &&
           hpNo("host:-1") && hpNo("host:99999999999") && hpNo("host:+") && hpNo("host:") && hpNo("host:-0") &&
           hpNo("host:\xEF\xBC\x91") &&                                    // a fullwidth digit: refused (see header)
           hpNo("host:\xD9\xA1\xD9\xA2\xD9\xA0\xD9\xA2\xD9\xA4") &&           // Arabic-Indic 12024: refused (deliberate)
           hpNo("host:2147483648") && hpNo("host:-2147483649") &&
           hpNo("host:18446744073709563640"));                              // 2^64 + 12024: no number, never a wrap
    result("typed_host_accepts_any_other_bytes", hp("my node", "my node", 12024) && hp("n\xC3\xB6" "de:5", "n\xC3\xB6" "de", 5) &&
           hp("a/b", "a/b", 12024) && hp("/host/:7", "/host/", 7) &&      // a single '/' is no URL ("//" is)
           hpNo("a//b") && hpNo("//"));
    {   char big[400];
        memset(big, 'a', 300); big[300] = '\0';
        result("host_longer_than_the_buffer_refused", hpNo(big));
    }
    result("link_host_ascii_only", ! uri("n\xC3\xB6" "de:5") && ! uri("dgbnode://n\xC3\xB6" "de") && ! uri("dgbnode://my node"));
    result("url_decoder_quirks",
           label("dgbnode://h?label=%+F", "%+F", 3) && label("dgbnode://h?label=%-0x", "%-0x", 4) &&
           label("dgbnode://h?label=My%20Node%+5", "My%20Node%+5", 12) &&   // Android throws; desktop Java decodes
           label("dgbnode://h?label=%4g", "%4g", 3) && label("dgbnode://h?label=%G1", "%G1", 3) &&
           label("dgbnode://h?label=%\xD9\xA1\xD9\xA1", "%\xD9\xA1\xD9\xA1", 5) &&       // non-ASCII digits are no hex
           label("dgbnode://h?label=%-1", "%-1", 3) && label("dgbnode://h?label=%4", "%4", 2) &&
           label("dgbnode://h?label=a+b", "a b", 3) && label("dgbnode://h?label=%41%42", "AB", 2) &&
           label("dgbnode://h?label=%41%", "%41%", 4) && label("dgbnode://h?label=%C3%A9", "\xC3\xA9", 2));
    result("net_last_valid_wins",
           uri("dgbnode://h?net=testnet&net=bogus") && N == BROwnNodeNetTestnet &&
           uri("dgbnode://h?net=mainnet&net=testnet") && N == BROwnNodeNetTestnet &&
           uri("dgbnode://h?net=%6D%61%69%6E%6E%65%74") && N == BROwnNodeNetMainnet &&
           uri("dgbnode://h?net=main%6Eet") && N == BROwnNodeNetMainnet &&
           uri("dgbnode://h?NET=mainnet") && N == BROwnNodeNetUnspecified);
    result("label_last_wins_and_absence",
           label("dgbnode://h?label=a&label=b", "b", 1) && label("dgbnode://h", "", -1) &&
           label("dgbnode://h?label", "", -1) && label("dgbnode://h?label=", "", 0) && label("dgbnode://h?&&label=z&", "z", 1));
    result("onion_case_insensitive_before_the_port",
           ! uri("dgbnode://ABC.ONION") && ! uri("dgbnode://abc.Onion:1") && uri("dgbnode://abc.onion.example:1") &&
           uri("abc.onion:1"));                                              // the raw fallback has no onion rule (as Kotlin)
    result("health_mapping", BROwnNodeHealthFor(3) == BROwnNodeHealthServing && BROwnNodeHealthFor(1) == BROwnNodeHealthConnecting &&
           BROwnNodeHealthFor(2) == BROwnNodeHealthDark && BROwnNodeHealthFor(0) == BROwnNodeHealthDark &&
           BROwnNodeHealthFor(-1) == BROwnNodeHealthDark);

    {   // An exclusive own node that never answers: polled every 30 s, the pool shows it mid-dial
        // (1) or just dropped (0). Connecting until the run is 60 s old, dark from then on, and it
        // stays dark through later dials.
        BROwnNodeHealthTrack t = {0, 0, 0};
        result("health_connecting_has_a_time_limit",
               BROwnNodeHealthObserve(&t, 1, 1000) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 0, 1030) == BROwnNodeHealthDark &&
               BROwnNodeHealthObserve(&t, 1, 1059) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 1, 1060) == BROwnNodeHealthDark &&
               BROwnNodeHealthObserve(&t, 1, 1090) == BROwnNodeHealthDark &&
               BROwnNodeHealthObserve(&t, 1, 1150) == BROwnNodeHealthDark);
    }
    {   // A gap of exactly 120 s still belongs to the run (only a longer one restarts it).
        BROwnNodeHealthTrack t = {0, 0, 0};
        result("health_gap_edge_continues_the_run",
               BROwnNodeHealthObserve(&t, 1, 0) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 1, 120) == BROwnNodeHealthDark);
    }
    {   // After a suspension (a poll more than 120 s after the last) the node is dialling again on
        // resume: a new run, connecting, not dark at once. A clock that went back restarts it too.
        BROwnNodeHealthTrack t = {0, 0, 0}, u = {0, 0, 0};
        result("health_gap_starts_a_new_run",
               BROwnNodeHealthObserve(&t, 1, 0) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 1, 30) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 1, 151) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&u, 1, 1000) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&u, 1, 1050) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&u, 1, 990) == BROwnNodeHealthConnecting);
    }
    {   // A connection (2 or 3) ends the run: the next dial gets the full grace again. Status 2
        // and 3 read as BROwnNodeHealthFor reads them.
        BROwnNodeHealthTrack t = {0, 0, 0};
        result("health_connection_ends_the_run",
               BROwnNodeHealthObserve(&t, 1, 0) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 3, 50) == BROwnNodeHealthServing &&
               BROwnNodeHealthObserve(&t, 1, 80) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 2, 110) == BROwnNodeHealthDark &&
               BROwnNodeHealthObserve(&t, 1, 140) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(&t, 1, 170) == BROwnNodeHealthConnecting &&
               t.inRun == 1 && t.runStart == 140);
    }
    {   // A zeroed track is a fresh pairing; no track is the bare mapping.
        BROwnNodeHealthTrack t = {0, 0, 0};
        result("health_fresh_track_and_no_track",
               BROwnNodeHealthObserve(&t, 0, 5000) == BROwnNodeHealthDark && t.inRun == 1 && t.runStart == 5000 &&
               BROwnNodeHealthObserve(NULL, 1, 1000000) == BROwnNodeHealthConnecting &&
               BROwnNodeHealthObserve(NULL, 3, 0) == BROwnNodeHealthServing &&
               BROwnNodeHealthObserve(NULL, 0, 0) == BROwnNodeHealthDark);
    }

    result("watchdog_pin_yields_to_own_node", BROwnNodeWatchdogMayPin(1) == 0 && BROwnNodeWatchdogMayPin(0) == 1);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
