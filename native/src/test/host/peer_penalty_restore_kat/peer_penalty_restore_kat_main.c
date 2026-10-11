// Host KAT for BRPeerPenaltyRestore.h -- restoring a persisted peer-penalty set.
//
// The rule: on restore, lapsed entries are dropped and NO entry against the peer canon
// survives, so a cold start can always dial the peers it needs before it has any peers.
// Until 2026-10 that rule lived in the Android JNI bridge (jni_peer.c,
// _loadPenaltiesExceptPriority); a platform restoring the blob as-is dials a different
// peer set after every restart.
//
// Built twice by run.sh:
//   -DPEER_PENALTY_RESTORE_CANON_UNFIXED  the blob restored as-is; every RED case fails
//   (default)                             the shipped rule; every case passes

#include "BRPeerPenaltyRestore.h"
#include <stdio.h>
#include <string.h>

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static UInt128 ipv4(const char *s)
{
    UInt128 a = UINT128_ZERO;
    BRPeerCanonParseIPv4(s, &a);   // the same IPv4-mapped form the manager stores
    return a;
}

// Decodes a restore blob and reports whether it holds addr.
static int holds(const uint8_t *blob, size_t len, time_t now, UInt128 addr, size_t *countOut)
{
    UInt128 a[64]; uint16_t p[64]; time_t u[64];
    size_t n = BRPeerPenaltyDeserialize(blob, len, now, a, p, u, 64), i;
    int found = 0;

    for (i = 0; i < n; i++) if (UInt128Eq(a[i], addr)) found = 1;
    if (countOut) *countOut = n;
    return found;
}

int main(void)
{
    const time_t now = 1760000000;   // fixed, so the run is reproducible
    UInt128 addrs[48]; uint16_t ports[48]; time_t until[48];
    uint8_t stored[4 + 48 * BR_PEER_PENALTY_ENTRY_BYTES], out[BR_PEER_PENALTY_RESTORE_BLOB_MAX];
    size_t storedLen, outLen, n, dropped;
    char why[160];

    // A mainnet canon peer, a TESTNET-ONLY canon peer, an ordinary peer and a lapsed
    // one. Some operators run both networks from one address, and exemption is by
    // address, so the testnet peer is chosen from those NOT also in the mainnet canon.
    UInt128 canonMain = ipv4(BRPeerCanonIPAt(0, 0));
    UInt128 canonTest = UINT128_ZERO;
    for (size_t i = 0; i < BRPeerCanonCount(1); i++) {
        UInt128 a = ipv4(BRPeerCanonIPAt(1, i));
        if (! BRPeerCanonContains(0, a)) { canonTest = a; break; }
    }
    if (UInt128IsZero(canonTest)) { printf("FAIL: no testnet-only canon peer to test with\n"); return 1; }
    UInt128 ordinary = ipv4("203.0.113.7");            // TEST-NET-3, never in a canon
    UInt128 lapsed = ipv4("198.51.100.9");             // TEST-NET-2

    addrs[0] = canonMain; ports[0] = 12024; until[0] = now + 3600;
    addrs[1] = canonTest; ports[1] = 12026; until[1] = now + 3600;
    addrs[2] = ordinary;  ports[2] = 12024; until[2] = now + 3600;
    addrs[3] = lapsed;    ports[3] = 12024; until[3] = now + 3600;   // lapses below
    storedLen = BRPeerPenaltySerialize(addrs, ports, until, 4, now, stored, sizeof(stored));
    // make entry 3 lapsed in the stored bytes: deadline at `now`
    {
        uint8_t *u3 = &stored[BR_PEER_PENALTY_HEADER_BYTES + 3 * BR_PEER_PENALTY_ENTRY_BYTES + 18];
        UInt64SetLE(u3, (uint64_t)now);
    }

    // ---- RED: the canon must never come back penalised -------------------------------
    outLen = BRPeerPenaltyRestoreBlob(stored, storedLen, now, 0, out, sizeof(out), &dropped);
    result("mainnet_canon_exempt", outLen > 0 && ! holds(out, outLen, now, canonMain, NULL), NULL);
    snprintf(why, sizeof(why), "dropped=%zu", dropped);
    result("mainnet_drop_count_reported", dropped == 1, why);

    outLen = BRPeerPenaltyRestoreBlob(stored, storedLen, now, 1, out, sizeof(out), &dropped);
    result("testnet_canon_exempt", outLen > 0 && ! holds(out, outLen, now, canonTest, NULL), NULL);

    // ---- GUARD: true of both shapes ------------------------------------------------------
    outLen = BRPeerPenaltyRestoreBlob(stored, storedLen, now, 0, out, sizeof(out), NULL);
    result("ordinary_peer_kept", outLen > 0 && holds(out, outLen, now, ordinary, NULL), NULL);
    result("lapsed_entry_dropped", outLen > 0 && ! holds(out, outLen, now, lapsed, NULL), NULL);

    {
        uint8_t empty[4];
        size_t emptyLen = BRPeerPenaltySerialize(addrs, ports, until, 0, now, empty, sizeof(empty));
        result("empty_blob_restores_nothing",
               emptyLen == BR_PEER_PENALTY_HEADER_BYTES &&
               BRPeerPenaltyRestoreBlob(empty, emptyLen, now, 0, out, sizeof(out), NULL) == 0, NULL);
    }
    result("null_blob_restores_nothing", BRPeerPenaltyRestoreBlob(NULL, 0, now, 0, out, sizeof(out), NULL) == 0, NULL);
    result("short_blob_restores_nothing", BRPeerPenaltyRestoreBlob(stored, 3, now, 0, out, sizeof(out), NULL) == 0, NULL);

    // 40 live ordinary entries: at most BR_PEER_PENALTY_RESTORE_MAX come back.
    for (size_t i = 0; i < 40; i++) {
        char ip[32];
        snprintf(ip, sizeof(ip), "203.0.113.%zu", 10 + i);
        addrs[i] = ipv4(ip); ports[i] = 12024; until[i] = now + 600;
    }
    storedLen = BRPeerPenaltySerialize(addrs, ports, until, 40, now, stored, sizeof(stored));
    outLen = BRPeerPenaltyRestoreBlob(stored, storedLen, now, 0, out, sizeof(out), NULL);
    holds(out, outLen, now, ordinary, &n);
    snprintf(why, sizeof(why), "restored=%zu", n);
    result("bounded_to_restore_max", n == BR_PEER_PENALTY_RESTORE_MAX, why);

    // An output buffer too small for the result yields nothing, never a truncated blob.
    result("small_output_never_truncates",
           BRPeerPenaltyRestoreBlob(stored, storedLen, now, 0, out, 30, NULL) == 0, NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
