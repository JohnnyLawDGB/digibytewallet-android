// Host KAT for BRScanFloor.h (the scan-floor rules) and BRChainRebuild.h (the order of a rebuild
// from chain).
//
//   kt_*      the Kotlin tests, case for case: RecoveryBirthHeightTest (cfBirthHeightToPersist);
//             HistoryRebuildOnUpgradeTest's framing cases (summarize -> BRSavedTransactionsFloorMinima)
//             and its floor cases (lowerFloorTo, the run's floor step,
//             clearFloorHint, markNotNeeded, the pending marker, armRerun); HistoryRebuildWiringTest's
//             floorHintStep cases; WalletManagerHistoryRebuildTest's creationTimeForRestore case and
//             its rebuild cases (the floor time kept, recorded sends forgotten);
//             WalletManagerRescanClearsCfLedgerTest (ledger and band cleared). A Kotlin lookup
//             lambda becomes a checkpoint table that answers the same; a lookup that throws becomes
//             no table.
//   edge_*    the bounds: negative and huge heights, an unsorted table, the genesis anchor, NULL
//             records, the default creation time, the step and kept names
//   model_*   the header against kt_model_*, a line-for-line model of the Kotlin in its arithmetic
//             (Long), over a grid of heights and times and a seeded random corpus; the checkpoint
//             lookup against the bridge's checkpoint_time.h on the real tables
//   prop_*    on the real mainnet and testnet tables: a floor lowered to any height gives a time
//             whose header anchor (BRRecoveryBirthCheckpointHeight) is at or below that height
//   order_*   BRChainRebuild.h against the order of the calls in Android's source: WalletManager.kt
//             (argv[2]), HistoryRebuildOnUpgrade.kt (argv[3]), ReconcileScreen.kt (argv[4]),
//             SyncService.kt (argv[5]); and the rebuild has no early return or throw
//   vectors_* every line of the core's vectors/scan-floor/vectors.txt (argv[1]) agrees with the
//             model (run.sh separately checks the file is the generator's current output)
//
// Built by run.sh as the shipped headers and one comparison arm per RED seam (listed there).

#include "BRScanFloor.h"
#include "BRScanFloor.h"        // twice: the include guard
#include "BRChainRebuild.h"
#include "BRChainRebuild.h"
#include "BRCFRestorePreflight.h"
#include "BRSavedTransactions.h"
#include "BRSavedTransactions.h"
#include "BRRecoveryDate.h"
#include "checkpoint_time.h"     // the bridge's lookup (native/src/main/jni/bridge)

#include <ctype.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}
#define CHECK(c) do { if (! (c)) { printf("  check failed (%s:%d): %s\n", __func__, __LINE__, #c); ok = 0; } } while (0)

// ---- Line-for-line model of the Kotlin (Android develop, 4.1.3) ------------------------------

// HistoryRebuildOnUpgrade.lowerFloor
static int64_t kt_lowerFloor(int64_t existing, int64_t candidate)
{
    if (existing <= 0) return candidate < 0 ? 0 : candidate;          // candidate.coerceAtLeast(0L)
    if (candidate <= 0) return existing;
    return existing < candidate ? existing : candidate;               // minOf
}

// WalletManager.cfBirthHeightToPersist: rawBirth.takeIf { it > 0L }. Returns has, value in *v.
static int kt_cfBirthHeightToPersist(int64_t rawBirth, int64_t *v)
{
    if (rawBirth > 0) { *v = rawBirth; return 1; }
    *v = 0;
    return 0;
}

// The lookup the Kotlin is handed: NativeBridge.getCheckpointTimeAtOrBelow (negative -> 0, then
// checkpoint_time_at_or_below over the active table) wrapped in runCatching { }.getOrDefault(0L).
// A NULL table is the lookup that throws.
typedef struct { const BRCheckPoint *cp; size_t n; } Lookup;
static int64_t kt_lookup(Lookup l, int64_t height)
{
    if (! l.cp) return 0;                         // threw -> getOrDefault(0L)
    if (height < 0) return 0;
    return (int64_t)checkpoint_time_at_or_below(l.cp, l.n, (uint64_t)height);
}

typedef struct { int64_t hint, time; int pending; } KtState;   // floor_hint, floor_time, floor_pending

// HistoryRebuildOnUpgrade.lowerFloorTo
static int kt_lowerFloorTo(KtState *p, int64_t height, Lookup l)
{
    if (height <= 0) return 0;
    int64_t hint = kt_lowerFloor(p->hint, height);
    int64_t checkpointTime = kt_lookup(l, hint);
    int64_t time = kt_lowerFloor(p->time, checkpointTime);
    p->hint = hint; p->time = time; p->pending = 1;
    return 1;                                     // commit()
}

// HistoryRebuildOnUpgrade.run: the floor part of the in-progress commit
static void kt_runFloor(KtState *p, int64_t minConfirmedHeight, int64_t minRecordTime, Lookup l)
{
    int64_t hint = kt_lowerFloor(p->hint, minConfirmedHeight);
    int64_t checkpointTime = hint > 0 ? kt_lookup(l, hint) : 0;
    int64_t time = kt_lowerFloor(kt_lowerFloor(p->time, minRecordTime), checkpointTime);
    p->hint = hint; p->time = time; p->pending = hint > 0 || time > 0;
}

static void kt_clearFloorHint(KtState *p) { p->hint = 0; p->pending = 0; }                 // clearFloorHint
static void kt_forgetFloor(KtState *p) { p->hint = 0; p->time = 0; p->pending = 0; }       // markNotNeeded(forgetFloor = true)
static void kt_requestFloorAtAnchor(KtState *p) { p->pending = 1; }

// CfRestorePreflight.floorHintStep; persisted < 0 encodes null via hasPersisted
typedef struct { int hasSet; int64_t set; int remove, clear, above; } KtStep;
static KtStep kt_floorHintStep(int walletLoaded, int64_t anchor, int hasPersisted, int64_t persistedBirth,
                               int64_t floorHint, int floorPending)
{
    KtStep none = { 0, 0, 0, 0, 0 };
    if ((floorHint <= 0 && ! floorPending) || ! walletLoaded) return none;
    if (floorHint > 0 && anchor > floorHint) { KtStep s = { 0, 0, 0, 0, 1 }; return s; }
    if (! hasPersisted) {
        if (anchor > 0) { KtStep s = { 1, anchor, 0, 1, 0 }; return s; }
        KtStep s = { 0, 0, 0, 1, 0 }; return s;
    }
    if (persistedBirth <= anchor) { KtStep s = { 0, 0, 0, 1, 0 }; return s; }
    if (anchor > 0) { KtStep s = { 1, anchor, 0, 1, 0 }; return s; }
    KtStep s = { 0, 0, 1, 1, 0 }; return s;
}

// HistoryRebuildOnUpgrade.summarize (CacheSummary). bytes == NULL is a null ByteArray.
typedef struct { int64_t declaredCount; int32_t records; int64_t minHeight, minTime; } KtSummary;
static int64_t kt_u32le(const uint8_t *b, int32_t off)
{
    return (int64_t)b[off] | ((int64_t)b[off + 1] << 8) | ((int64_t)b[off + 2] << 16) | ((int64_t)b[off + 3] << 24);
}
static KtSummary kt_summarize(const uint8_t *bytes, int32_t size)
{
    KtSummary empty = { 0, 0, 0, 0 };
    const int64_t MAX_RECORDS = 10000, UNCONFIRMED_HEIGHT = 0x7fffffffLL;
    if (bytes == NULL || size <= 4) return empty;
    int64_t count = kt_u32le(bytes, 0);
    if (count == 0) return empty;
    if (count > MAX_RECORDS) { KtSummary s = { count, 0, 0, 0 }; return s; }
    int64_t pos = 4; int32_t records = 0; int64_t minHeight = 0, minTime = 0, len = size, i = 0;
    while (i < count && pos + 12 <= len) {
        int64_t sz = kt_u32le(bytes, (int32_t)pos), height = kt_u32le(bytes, (int32_t)pos + 4), time = kt_u32le(bytes, (int32_t)pos + 8);
        pos += 12;
        if (pos + sz > len) break;
        pos += sz;
        records++;
        if (height >= 1 && height < UNCONFIRMED_HEIGHT) { if (minHeight == 0 || height < minHeight) minHeight = height; }
        if (height >= 1 && height <= UNCONFIRMED_HEIGHT && time > 0 && (minTime == 0 || time < minTime)) minTime = time;
        i++;
    }
    KtSummary s = { count, records, minHeight, minTime };
    return s;
}

// WalletManager.creationTimeForRestore
static int64_t kt_creationTimeForRestore(int64_t stored, int64_t floorTime)
{
    int64_t base = stored > 0 ? stored : 1774252800LL;                 // DEFAULT_RESTORE_CREATION_TIME
    return floorTime > 0 ? (base < floorTime ? base : floorTime) : base;
}

// ---- Helpers ---------------------------------------------------------------------------------

static BRCheckPoint cpAt(uint32_t h, uint32_t t) { BRCheckPoint c; memset(&c, 0, sizeof(c)); c.height = h; c.timestamp = t; return c; }

static int stepEq(BRScanFloorStep s, int hasSet, int64_t set, int remove, int clear, int above)
{
    return s.setBirth == hasSet && s.birth == (hasSet ? set : 0) && s.removeBirth == remove &&
           s.clearHint == clear && s.anchorAboveHint == above;
}
static int stepMatchesKt(BRScanFloorStep s, KtStep k) { return stepEq(s, k.hasSet, k.set, k.remove, k.clear, k.above); }
static int floorEq(BRScanFloor f, int64_t h, int64_t t, int p) { return f.height == h && f.time == t && f.pending == p; }

// floorHintStep's named arguments: (loaded, anchor, persisted or NONE, hint, pending)
#define NONE INT64_MIN
static BRScanFloorStep st(int loaded, int64_t anchor, int64_t persisted, int64_t hint, int pending)
{
    return BRScanFloorHintStep(loaded, anchor, persisted != NONE, persisted == NONE ? 0 : persisted, hint, pending);
}

// ---- kt_: HistoryRebuildOnUpgradeTest's framing cases (summarize) -------------------------------

typedef struct { int64_t height, time; const uint8_t *body; uint32_t bodyLen; int64_t declaredSize; } Rec;
static const uint8_t BODY3[] = { 0xa0, 0xa1, 0xa2 }, BODY2[] = { 9, 9 }, BODY1[] = { 1 }, BODY7[] = { 7 }, BODY23[] = { 2, 3 };
#define R(h, t, b) { (h), (t), (b), sizeof(b), -1 }
static size_t blobOf(uint8_t *out, const Rec *r, size_t n, int64_t count)
{
    size_t pos = 0;
    #define PUT(v) do { uint32_t _v = (uint32_t)(v); out[pos++] = (uint8_t)_v; out[pos++] = (uint8_t)(_v >> 8); \
                        out[pos++] = (uint8_t)(_v >> 16); out[pos++] = (uint8_t)(_v >> 24); } while (0)
    PUT(count < 0 ? (int64_t)n : count);
    for (size_t i = 0; i < n; i++) {
        PUT(r[i].declaredSize < 0 ? (int64_t)r[i].bodyLen : r[i].declaredSize); PUT(r[i].height); PUT(r[i].time);
        memcpy(out + pos, r[i].body, r[i].bodyLen); pos += r[i].bodyLen;
    }
    #undef PUT
    return pos;
}
static BRSavedTransactionsFloor minima(const uint8_t *b, size_t len)
{
    BRSavedTransactionsFloor f;
    BRSavedTransactionsFloorMinima(b, len, &f);
    return f;
}
static int isEmpty(BRSavedTransactionsFloor f) { return f.declaredCount == 0; }

static void kt_summarize_empty_and_zero_count(void)
{
    int ok = 1; uint8_t b[64] = { 0 };
    const uint8_t three[] = { 1, 0, 0 }, four[] = { 1, 0, 0, 0 };
    CHECK(isEmpty(minima(NULL, 0)));
    CHECK(isEmpty(minima(b, 0)));
    CHECK(isEmpty(minima(three, 3)));
    CHECK(isEmpty(minima(four, 4)));
    size_t n = blobOf(b, NULL, 0, -1);
    CHECK(isEmpty(minima(b, n + 20)));
    result("kt_summarize_empty_and_zero_count", ok);
}
static void kt_summarize_one_real_record(void)
{
    int ok = 1; uint8_t b[64]; Rec r[] = { R(21000000, 1650000000, BODY3) };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 1, -1));
    CHECK(f.records == 1 && f.minConfirmedHeight == 21000000 && f.minRecordTime == 1650000000);
    result("kt_summarize_one_real_record", ok);
}
static void kt_summarize_mixed_heights_take_the_minimum_and_ignore_the_sentinel(void)
{
    int ok = 1; uint8_t b[256];
    Rec r[] = { R(23000000, 1700000000, BODY3), R(0x7fffffffLL, 1550000000, BODY2), R(19500000, 1600000000, BODY1),
                R(0, 500, BODY7), R(0xfffffff0LL, 400, BODY7), R(20000000, 0, BODY23) };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 6, -1));
    CHECK(f.records == 6 && f.minConfirmedHeight == 19500000);
    CHECK(f.minRecordTime == 1550000000);          // heights 0 and above the sentinel give no time
    result("kt_summarize_mixed_heights_take_the_minimum_and_ignore_the_sentinel", ok);
}
static void kt_summarize_all_unconfirmed_gives_no_height_but_a_time(void)
{
    int ok = 1; uint8_t b[64]; Rec r[] = { R(0x7fffffffLL, 1700000000, BODY3), R(0x7fffffffLL, 1690000000, BODY1) };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 2, -1));
    CHECK(! isEmpty(f) && f.records == 2 && f.minConfirmedHeight == 0 && f.minRecordTime == 1690000000);
    result("kt_summarize_all_unconfirmed_gives_no_height_but_a_time", ok);
}
static void kt_summarize_unconfirmed_time_below_lowers_only_the_time(void)
{
    int ok = 1; uint8_t b[64];
    Rec r[] = { R(22000000, 1690000000, BODY3), R(0x7fffffffLL, 1600000000, BODY1), R(0x7fffffffLL, 0, BODY23) };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 3, -1));
    CHECK(f.minConfirmedHeight == 22000000 && f.minRecordTime == 1600000000);
    result("kt_summarize_unconfirmed_time_below_lowers_only_the_time", ok);
}
static void kt_summarize_truncated_mid_record_keeps_whole_records(void)
{
    int ok = 1; uint8_t b[64]; Rec r[] = { R(22000000, 1690000000, BODY3), R(18000000, 1500000000, BODY3) };
    size_t n = blobOf(b, r, 2, -1);
    BRSavedTransactionsFloor f = minima(b, n - 2);
    CHECK(f.records == 1 && f.minConfirmedHeight == 22000000);
    CHECK(minima(b, 4 + 12 + sizeof(BODY3) + 6).minConfirmedHeight == 22000000);   // cut inside the second header
    result("kt_summarize_truncated_mid_record_keeps_whole_records", ok);
}
static void kt_summarize_size_past_the_end_stops_without_a_hint(void)
{
    int ok = 1; uint8_t b[64];
    Rec r[] = { R(22000000, 1690000000, BODY3), { 10000000, 1400000000, BODY2, sizeof(BODY2), 0xffffffffLL } };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 2, -1));
    CHECK(f.records == 1 && f.minConfirmedHeight == 22000000 && f.minRecordTime == 1690000000);
    result("kt_summarize_size_past_the_end_stops_without_a_hint", ok);
}
static void kt_summarize_count_above_the_cap_gives_no_hint(void)
{
    int ok = 1; uint8_t b[64]; Rec r[] = { R(1000, 1400000000, BODY3) };
    BRSavedTransactionsFloor f = minima(b, blobOf(b, r, 1, 10001));
    CHECK(! isEmpty(f) && f.records == 0 && f.minConfirmedHeight == 0);
    const uint8_t huge[] = { 0xff, 0xff, 0xff, 0xff, 0, 0, 0, 0 };
    f = minima(huge, sizeof(huge));
    CHECK(f.minConfirmedHeight == 0 && ! isEmpty(f));     // decide(): a non-empty cache runs
    result("kt_summarize_count_above_the_cap_gives_no_hint", ok);
}
static void kt_summarize_never_fails_on_arbitrary_bytes(void)
{
    int ok = 1; uint64_t x = 7;
    for (int it = 0; it < 2000; it++) {
        uint8_t *b; size_t n;
        x ^= x << 13; x ^= x >> 7; x ^= x << 17;
        n = (size_t)(x % 64);
        b = malloc(n ? n : 1);                            // exact-size buffer: ASan sees any overread
        for (size_t i = 0; i < n; i++) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; b[i] = (uint8_t)x; }
        if (n >= 4 && (x & 1)) { b[0] = 3; b[1] = 0; b[2] = 0; b[3] = 0; }
        BRSavedTransactionsFloor f = minima(b, n);
        KtSummary k = kt_summarize(b, (int32_t)n);
        CHECK(f.declaredCount == k.declaredCount && (int64_t)f.records == k.records &&
              f.minConfirmedHeight == k.minHeight && f.minRecordTime == k.minTime);
        free(b);
    }
    result("kt_summarize_never_fails_on_arbitrary_bytes", ok);
}

// ---- kt_: RecoveryBirthHeightTest ------------------------------------------------------------

static void kt_birth_real_checkpoint_height_persisted(void)
{
    int ok = 1; int64_t v;
    CHECK(BRScanFloorBirthToPersist(21500000, &v) == 1 && v == 21500000);
    CHECK(BRScanFloorBirthToPersist(23000000, &v) == 1 && v == 23000000);
    result("kt_birth_real_checkpoint_height_persisted", ok);
}
static void kt_birth_zero_never_persisted(void)
{
    int ok = 1; int64_t v = 7;
    CHECK(BRScanFloorBirthToPersist(0, &v) == 0 && v == 0);
    result("kt_birth_zero_never_persisted", ok);
}
static void kt_birth_negative_not_persisted(void)
{
    int ok = 1; int64_t v;
    CHECK(BRScanFloorBirthToPersist(-1, &v) == 0);
    CHECK(BRScanFloorBirthToPersist(INT64_MIN, &v) == 0);
    result("kt_birth_negative_not_persisted", ok);
}
static void kt_birth_smallest_positive_persisted(void)
{
    int ok = 1; int64_t v;
    CHECK(BRScanFloorBirthToPersist(1, &v) == 1 && v == 1);
    result("kt_birth_smallest_positive_persisted", ok);
}

// ---- kt_: HistoryRebuildOnUpgradeTest's floor cases --------------------------------------------

// run_clearsTheCacheAndEveryDerivedStore_...: one record (21M, 1.65e9), the default lookup throws
static void kt_run_floor_from_the_discarded_cache(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRScanFloorFromDiscardedCache(&f, 21000000, 1650000000, NULL, 0);
    CHECK(floorEq(f, 21000000, 1650000000, 1));
    result("kt_run_floor_from_the_discarded_cache", ok);
}
// run_interruptedAfterTheInProgressCommit_...: the hint from the first attempt survives an empty cache
static void kt_run_interrupted_keeps_the_first_hint(void)
{
    int ok = 1; BRScanFloor f = { 21000000, 0, 0 };
    BRScanFloorFromDiscardedCache(&f, 0, 0, NULL, 0);
    CHECK(f.height == 21000000);
    result("kt_run_interrupted_keeps_the_first_hint", ok);
}
static void kt_run_never_raises_the_floor(void)
{
    int ok = 1; BRScanFloor f = { 20000000, 1600000000, 0 };
    BRScanFloorFromDiscardedCache(&f, 22000000, 1690000000, NULL, 0);
    CHECK(f.height == 20000000 && f.time == 1600000000);
    result("kt_run_never_raises_the_floor", ok);
}
static void kt_run_lowers_an_existing_hint(void)
{
    int ok = 1; BRScanFloor f = { 20000000, 1600000000, 0 };
    BRScanFloorFromDiscardedCache(&f, 18000000, 1500000000, NULL, 0);
    CHECK(f.height == 18000000 && f.time == 1500000000);
    result("kt_run_lowers_an_existing_hint", ok);
}
static void kt_lowerFloorTo_sets_hint_time_and_pending(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRCheckPoint cp[] = { cpAt(19000000, 1550000000) };          // { h -> if (h == 19M) 1.55e9 else 0 }
    CHECK(BRScanFloorLowerTo(&f, 19000000, cp, 1) == 1);
    CHECK(floorEq(f, 19000000, 1550000000, 1));
    result("kt_lowerFloorTo_sets_hint_time_and_pending", ok);
}
static void kt_lowerFloorTo_never_raises_an_existing_floor(void)
{
    int ok = 1; BRScanFloor f = { 18000000, 1500000000, 0 };
    BRCheckPoint cp[] = { cpAt(0, 1550000000) };                  // { 1.55e9 }
    CHECK(BRScanFloorLowerTo(&f, 19000000, cp, 1) == 1);
    CHECK(f.height == 18000000 && f.time == 1500000000);
    result("kt_lowerFloorTo_never_raises_an_existing_floor", ok);
}
static void kt_lowerFloorTo_ignores_a_non_positive_height(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRCheckPoint cp[] = { cpAt(0, 1550000000) };
    CHECK(BRScanFloorLowerTo(&f, 0, cp, 1) == 0);
    CHECK(f.height == 0 && ! f.pending);
    result("kt_lowerFloorTo_ignores_a_non_positive_height", ok);
}
// markNotNeeded_stampsDone_andForgetsTheFloorOnlyWhenAsked: same wallet keeps 5/6, forgetFloor drops both
static void kt_markNotNeeded_forgets_the_floor_only_when_asked(void)
{
    int ok = 1; BRScanFloor f = { 5, 6, 0 };
    // markNotNeeded(c), same wallet: the rebuild's own floor steps over an empty cache keep 5/6
    // (it is a different wallet only that forgets them), and the floor record is in the kept list
    BRScanFloorFromDiscardedCache(&f, 0, 0, NULL, 0);
    CHECK(floorEq(f, 5, 6, 1));
    int kept = 0;
    for (int i = 0; i < BR_CHAIN_REBUILD_KEPT_COUNT; i++) kept |= BRChainRebuildKept[i] == BR_CHAIN_REBUILD_KEEP_FLOOR_RECORD;
    CHECK(kept);
    BRScanFloorForget(&f);                                        // markNotNeeded(c, forgetFloor = true)
    CHECK(floorEq(f, 0, 0, 0));
    result("kt_markNotNeeded_forgets_the_floor_only_when_asked", ok);
}
static void kt_clearFloorHint_forgets_only_the_height(void)
{
    int ok = 1; BRScanFloor f = { 5, 6, 0 };
    BRScanFloorApplied(&f);
    CHECK(f.height == 0 && f.time == 6);
    result("kt_clearFloorHint_forgets_only_the_height", ok);
}
// run_floorTime_reachesTheCheckpointAtOrBelowTheHint_whateverTheRecordTimes: records (21M, 0) and
// (22M, 1.8e9): the cache's lowest height 21M, lowest non-zero time 1.8e9; the lookup answers 21M.
static void kt_run_floor_time_reaches_the_checkpoint_at_or_below_the_hint(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRCheckPoint cp[] = { cpAt(21000000, 1650000000) };
    BRScanFloorFromDiscardedCache(&f, 21000000, 1800000000, cp, 1);
    CHECK(f.height == 21000000 && f.time == 1650000000);
    result("kt_run_floor_time_reaches_the_checkpoint_at_or_below_the_hint", ok);
}
static void kt_run_floor_time_is_the_lower_of_record_and_checkpoint(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRCheckPoint cp[] = { cpAt(0, 1650000000) };                  // { 1.65e9 }
    BRScanFloorFromDiscardedCache(&f, 21000000, 1500000000, cp, 1);
    CHECK(f.time == 1500000000);
    result("kt_run_floor_time_is_the_lower_of_record_and_checkpoint", ok);
}
static void kt_run_lookup_that_throws_falls_back_to_the_record_time(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRScanFloorFromDiscardedCache(&f, 21000000, 1650000000, NULL, 0);
    CHECK(f.time == 1650000000);
    result("kt_run_lookup_that_throws_falls_back_to_the_record_time", ok);
}
// run_asksTheLookupForTheFinalHint_notARaisedOne: stored hint 20M, record 22M. The table answers
// differently at 20M and 22M, so asking the raised height shows in the time.
static void kt_run_asks_the_lookup_for_the_final_hint(void)
{
    int ok = 1; BRScanFloor f = { 20000000, 0, 0 };
    BRCheckPoint cp[] = { cpAt(20000000, 1600000000), cpAt(22000000, 1700000000) };
    BRScanFloorFromDiscardedCache(&f, 22000000, 1690000000, cp, 2);
    CHECK(f.height == 20000000 && f.time == 1600000000);
    result("kt_run_asks_the_lookup_for_the_final_hint", ok);
}
static void kt_run_only_unconfirmed_records_marks_the_floor_pending(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 };
    BRScanFloorFromDiscardedCache(&f, 0, 1600000000, NULL, 0);    // record at TX_UNCONFIRMED, time 1.6e9
    CHECK(floorEq(f, 0, 1600000000, 1));
    result("kt_run_only_unconfirmed_records_marks_the_floor_pending", ok);
}
static void kt_run_floor_height_marks_pending_and_no_floor_does_not(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 0 }, none = { 0, 0, 0 };
    BRScanFloorFromDiscardedCache(&f, 21000000, 1650000000, NULL, 0);
    CHECK(f.pending == 1);
    BRScanFloorFromDiscardedCache(&none, 0, 0, NULL, 0);          // one unconfirmed record, time 0
    CHECK(none.pending == 0);
    result("kt_run_floor_height_marks_pending_and_no_floor_does_not", ok);
}
static void kt_pending_marker_goes_with_the_hint_and_a_different_wallet(void)
{
    int ok = 1; BRScanFloor f = { 5, 0, 1 };
    BRScanFloorApplied(&f);
    CHECK(! f.pending);
    BRScanFloorRequestAtAnchor(&f);
    CHECK(f.pending);                                             // markNotNeeded(c): same wallet keeps it
    BRScanFloorForget(&f);
    CHECK(! f.pending);
    result("kt_pending_marker_goes_with_the_hint_and_a_different_wallet", ok);
}
// armRerun_makesTheNextStartRunAgain_keepingTheFloors: the rebuild keeps the floor record
static void kt_armRerun_keeps_the_floors(void)
{
    int ok = 1, kept = 0;
    for (int i = 0; i < BR_CHAIN_REBUILD_KEPT_COUNT; i++) kept |= BRChainRebuildKept[i] == BR_CHAIN_REBUILD_KEEP_FLOOR_RECORD;
    CHECK(kept);
    CHECK(BRChainRebuildIndexOf(0, BR_CHAIN_REBUILD_ARM_RERUN) >= 0);
    result("kt_armRerun_keeps_the_floors", ok);
}

// ---- kt_: HistoryRebuildWiringTest (floorHintStep) ---------------------------------------------

static void kt_step_no_hint_does_nothing(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 22650000, 0, 0), 0, 0, 0, 0, 0));
    result("kt_step_no_hint_does_nothing", ok);
}
static void kt_step_wallet_not_loaded_keeps_the_hint(void)
{
    int ok = 1;
    CHECK(stepEq(st(0, 0, 22650000, 19500000, 0), 0, 0, 0, 0, 0));
    result("kt_step_wallet_not_loaded_keeps_the_hint", ok);
}
static void kt_step_anchor_above_the_hint_keeps_it(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 22650000, 22650000, 19500000, 0), 0, 0, 0, 0, 1));
    CHECK(stepEq(st(1, 21500000, NONE, 19500000, 0), 0, 0, 0, 0, 1));
    result("kt_step_anchor_above_the_hint_keeps_it", ok);
}
static void kt_step_anchor_at_or_below_lowers_a_higher_floor(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 22650000, 19500000, 0), 1, 19000000, 0, 1, 0));
    CHECK(stepEq(st(1, 19500000, 22650000, 19500000, 0), 1, 19500000, 0, 1, 0));
    result("kt_step_anchor_at_or_below_lowers_a_higher_floor", ok);
}
static void kt_step_no_persisted_floor_persists_the_anchor(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, NONE, 19500000, 0), 1, 19000000, 0, 1, 0));
    result("kt_step_no_persisted_floor_persists_the_anchor", ok);
}
static void kt_step_floor_at_or_below_the_anchor_stays(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 15000000, 19500000, 0), 0, 0, 0, 1, 0));
    CHECK(stepEq(st(1, 19000000, 19000000, 19500000, 0), 0, 0, 0, 1, 0));
    result("kt_step_floor_at_or_below_the_anchor_stays", ok);
}
static void kt_step_floor_between_anchor_and_hint_goes_to_the_anchor(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 19500000, 19500000, 0), 1, 19000000, 0, 1, 0));
    result("kt_step_floor_between_anchor_and_hint_goes_to_the_anchor", ok);
}
static void kt_step_no_hint_but_pending_lowers_to_the_anchor(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 22650000, 0, 1), 1, 19000000, 0, 1, 0));
    CHECK(stepEq(st(1, 19000000, NONE, 0, 1), 1, 19000000, 0, 1, 0));
    CHECK(stepEq(st(1, 0, 22650000, 0, 1), 0, 0, 1, 1, 0));
    CHECK(stepEq(st(1, 19000000, 15000000, 0, 1), 0, 0, 0, 1, 0));
    result("kt_step_no_hint_but_pending_lowers_to_the_anchor", ok);
}
static void kt_step_no_hint_nothing_pending_does_nothing(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 19000000, 22650000, 0, 0), 0, 0, 0, 0, 0));
    result("kt_step_no_hint_nothing_pending_does_nothing", ok);
}
static void kt_step_pending_floor_waits_for_the_wallet(void)
{
    int ok = 1;
    CHECK(stepEq(st(0, 0, 22650000, 0, 1), 0, 0, 0, 0, 0));
    result("kt_step_pending_floor_waits_for_the_wallet", ok);
}
static void kt_step_genesis_anchor_is_a_valid_anchor(void)
{
    int ok = 1;
    CHECK(stepEq(st(1, 0, 22650000, 19500000, 0), 0, 0, 1, 1, 0));
    CHECK(stepEq(st(1, 0, NONE, 19500000, 0), 0, 0, 0, 1, 0));
    result("kt_step_genesis_anchor_is_a_valid_anchor", ok);
}
static void kt_step_result_feeds_the_birth_choice_at_or_below_the_hint(void)
{
    int ok = 1;
    BRScanFloorStep s = st(1, 19000000, 22650000, 19500000, 0);
    int has = ! s.removeBirth;
    int64_t persisted = s.setBirth ? s.birth : 22650000;
    int64_t start = BRCFRestoreArmHeight(0, 0, 19000000, has, persisted);
    CHECK(start <= 19500000);
    result("kt_step_result_feeds_the_birth_choice_at_or_below_the_hint", ok);
}

// ---- kt_: WalletManagerHistoryRebuildTest, WalletManagerRescanClearsCfLedgerTest, HistoryRebuildOnUpgradeTest

static void kt_creationTimeForRestore_lowers_to_the_floor_time_only(void)
{
    int ok = 1;
    CHECK(BRScanFloorRestoreCreationTime(1700000000, 0) == 1700000000);
    CHECK(BRScanFloorRestoreCreationTime(1700000000, 1600000000) == 1600000000);
    CHECK(BRScanFloorRestoreCreationTime(1700000000, 1800000000) == 1700000000);   // never raised
    CHECK(BRScanFloorRestoreCreationTime(0, 0) == 1774252800);                     // the existing default
    CHECK(BRScanFloorRestoreCreationTime(0, 1600000000) == 1600000000);
    result("kt_creationTimeForRestore_lowers_to_the_floor_time_only", ok);
}
// rebuildFromChainRescan_armsTheRebuild_...: the floor time stays across the rebuild and its rerun
static void kt_rebuild_arms_the_rerun_and_keeps_the_floor_time(void)
{
    // floor_time 8; writeCache() puts oneRecordHex back (one record: height 0x014c4b40, time
    // 0x62b8e080) before the rebuild and again between its clear and the exit; lookup { 0L }.
    static const uint8_t oneRecord[] = { 1, 0, 0, 0, 2, 0, 0, 0, 0x40, 0x4b, 0x4c, 0x01, 0x80, 0xe0, 0xb8, 0x62, 0xab, 0xcd };
    int ok = 1; BRScanFloor f = { 0, 8, 0 };
    BRSavedTransactionsFloor m = minima(oneRecord, sizeof(oneRecord));
    CHECK(m.records == 1 && m.minConfirmedHeight == 21777216 && m.minRecordTime == 1656283264);
    CHECK(BRChainRebuildMustPrecede(0, BR_CHAIN_REBUILD_ARM_RERUN, BR_CHAIN_REBUILD_CLEAR_TX_CACHE));
    CHECK(BRChainRebuildMustPrecede(0, BR_CHAIN_REBUILD_FLOOR_FROM_CACHE, BR_CHAIN_REBUILD_CLEAR_TX_CACHE));
    BRScanFloorFromDiscardedCache(&f, m.minConfirmedHeight, m.minRecordTime, NULL, 0);   // FLOOR_FROM_CACHE
    CHECK(floorEq(f, 21777216, 8, 1));
    BRScanFloorFromDiscardedCache(&f, m.minConfirmedHeight, m.minRecordTime, NULL, 0);   // RERUN_ON_LOAD
    CHECK(floorEq(f, 21777216, 8, 1));                                                   // same wallet: time 8 stays
    BRScanFloor e = { 0, 8, 0 };
    BRScanFloorFromDiscardedCache(&e, 0, 0, NULL, 0);                                     // no cache written back
    CHECK(floorEq(e, 0, 8, 1));
    result("kt_rebuild_arms_the_rerun_and_keeps_the_floor_time", ok);
}
static void kt_rebuild_still_forgets_recorded_sends(void)
{
    int ok = 1;
    CHECK(BRChainRebuildIndexOf(0, BR_CHAIN_REBUILD_CLEAR_OUTGOING_STORE) >= 0);
    CHECK(BRChainRebuildIndexOf(1, BR_CHAIN_REBUILD_CLEAR_OUTGOING_STORE) >= 0);
    result("kt_rebuild_still_forgets_recorded_sends", ok);
}
static void kt_rebuild_deletes_the_ledger_and_the_band(void)
{
    int ok = 1;
    CHECK(BRChainRebuildIndexOf(0, BR_CHAIN_REBUILD_CLEAR_SCAN_LEDGER) >= 0);
    CHECK(BRChainRebuildIndexOf(0, BR_CHAIN_REBUILD_CLEAR_ABANDONMENT) >= 0);
    result("kt_rebuild_deletes_the_ledger_and_the_band", ok);
}
// clear_removesTheDigiDollarBalanceSnapshot and the derived-store list of run_clearsTheCache...
static void kt_rebuild_clears_every_derived_store(void)
{
    int ok = 1;
    static const BRChainRebuildStep need[] = {
        BR_CHAIN_REBUILD_CLEAR_TX_CACHE, BR_CHAIN_REBUILD_CLEAR_FILTER_CHAIN, BR_CHAIN_REBUILD_CLEAR_SAVED_BLOCKS,
        BR_CHAIN_REBUILD_CLEAR_SYNC_STATE, BR_CHAIN_REBUILD_CLEAR_SCAN_LEDGER, BR_CHAIN_REBUILD_CLEAR_ABANDONMENT,
    };
    for (size_t i = 0; i < sizeof(need) / sizeof(*need); i++) CHECK(BRChainRebuildIndexOf(0, need[i]) >= 0);
    int peers = 0;
    for (int i = 0; i < BR_CHAIN_REBUILD_KEPT_COUNT; i++) peers |= BRChainRebuildKept[i] == BR_CHAIN_REBUILD_KEEP_SAVED_PEERS;
    CHECK(peers);                                                 // "keys the manual rebuild keeps are kept"
    result("kt_rebuild_clears_every_derived_store", ok);
}

// ---- edge_ -------------------------------------------------------------------------------------

static void edge_lower_bounds(void)
{
    int ok = 1;
    CHECK(BRScanFloorLower(0, 0) == 0);
    CHECK(BRScanFloorLower(0, -5) == 0);                          // never a negative floor
    CHECK(BRScanFloorLower(INT64_MIN, INT64_MIN) == 0);
    CHECK(BRScanFloorLower(-3, 7) == 7);
    CHECK(BRScanFloorLower(7, -3) == 7);
    CHECK(BRScanFloorLower(7, INT64_MAX) == 7);
    CHECK(BRScanFloorLower(INT64_MAX, 7) == 7);
    CHECK(BRScanFloorLower(7, 7) == 7);
    result("edge_lower_bounds", ok);
}
static void edge_birth_null_out_and_huge(void)
{
    int ok = 1; int64_t v;
    CHECK(BRScanFloorBirthToPersist(5, NULL) == 1);
    CHECK(BRScanFloorBirthToPersist(0, NULL) == 0);
    CHECK(BRScanFloorBirthToPersist(INT64_MAX, &v) == 1 && v == INT64_MAX);
    result("edge_birth_null_out_and_huge", ok);
}
static void edge_checkpoint_lookup_bounds(void)
{
    int ok = 1;
    BRCheckPoint cp[] = { cpAt(300, 3), cpAt(100, 1), cpAt(200, 2) };   // unsorted
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, 99) == 0);    // none at or below
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, 100) == 1);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, 250) == 2);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, 300) == 3);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, INT64_MAX) == 3);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, -1) == 0);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 3, INT64_MIN) == 0);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(NULL, 3, 250) == 0);
    CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, 0, 250) == 0);
    result("edge_checkpoint_lookup_bounds", ok);
}
static void edge_lowerTo_lookup_miss_keeps_the_time(void)
{
    int ok = 1; BRScanFloor f = { 0, 1500000000, 0 };
    BRCheckPoint cp[] = { cpAt(500, 1400000000) };
    CHECK(BRScanFloorLowerTo(&f, 100, cp, 1) == 1);               // nothing at or below 100
    CHECK(floorEq(f, 100, 1500000000, 1));
    CHECK(BRScanFloorLowerTo(NULL, 100, cp, 1) == 0);
    f.pending = 0;
    CHECK(BRScanFloorLowerTo(&f, -1, cp, 1) == 0 && f.pending == 0);
    result("edge_lowerTo_lookup_miss_keeps_the_time", ok);
}
static void edge_cache_clears_a_pending_flag_with_no_floor(void)
{
    int ok = 1; BRScanFloor f = { 0, 0, 1 };                      // requestFloorAtAnchor, then a run with no floor
    BRScanFloorFromDiscardedCache(&f, 0, 0, NULL, 0);
    CHECK(f.pending == 0);
    BRScanFloorFromDiscardedCache(NULL, 1, 1, NULL, 0);           // no crash
    BRScanFloorApplied(NULL); BRScanFloorForget(NULL); BRScanFloorRequestAtAnchor(NULL);
    result("edge_cache_clears_a_pending_flag_with_no_floor", ok);
}
static void edge_step_never_writes_zero_or_raises(void)
{
    int ok = 1;
    static const int64_t v[] = { -1, 0, 1, 15000000, 19000000, 19500000, 22650000 };
    for (int loaded = 0; loaded <= 1; loaded++)
        for (size_t a = 0; a < 7; a++)
            for (size_t p = 0; p <= 7; p++)
                for (size_t h = 0; h < 7; h++)
                    for (int pend = 0; pend <= 1; pend++) {
                        BRScanFloorStep s = st(loaded, v[a], p < 7 ? v[p] : NONE, v[h], pend);
                        if (s.setBirth) {
                            CHECK(s.birth > 0);                   // 0 is never written
                            if (v[h] > 0) CHECK(s.birth <= v[h]); // never set above the record's height
                            if (p < 7) CHECK(s.birth < v[p]);     // a stored floor is only lowered
                        }
                        CHECK(! (s.setBirth && s.removeBirth));
                        if (s.anchorAboveHint) CHECK(! s.clearHint && ! s.setBirth && ! s.removeBirth);
                    }
    result("edge_step_never_writes_zero_or_raises", ok);
}
static void edge_restore_time_bounds(void)
{
    int ok = 1;
    CHECK(BRScanFloorRestoreCreationTime(-5, -5) == BR_SCAN_FLOOR_DEFAULT_RESTORE_CREATION_TIME);
    CHECK(BRScanFloorRestoreCreationTime(0, BR_SCAN_FLOOR_DEFAULT_RESTORE_CREATION_TIME + 1) == BR_SCAN_FLOOR_DEFAULT_RESTORE_CREATION_TIME);
    CHECK(BRScanFloorRestoreCreationTime(5, 1) == 1);
    result("edge_restore_time_bounds", ok);
}
static void edge_rebuild_names_and_bounds(void)
{
    int ok = 1;
    for (int k = 0; k < BR_CHAIN_REBUILD_STEP_KINDS; k++) {
        const char *n = BRChainRebuildStepName((BRChainRebuildStep)k);
        CHECK(n != NULL && n[0]);
        for (int j = 0; j < k; j++) CHECK(strcmp(n, BRChainRebuildStepName((BRChainRebuildStep)j)) != 0);
    }
    CHECK(BRChainRebuildStepName((BRChainRebuildStep)BR_CHAIN_REBUILD_STEP_KINDS) == NULL);
    CHECK(BRChainRebuildStepName((BRChainRebuildStep)-1) == NULL);
    for (int k = 0; k < BR_CHAIN_REBUILD_KEPT_COUNT; k++) CHECK(BRChainRebuildKeepName(BRChainRebuildKept[k]) != NULL);
    CHECK(BRChainRebuildKeepName((BRChainRebuildKeep)BR_CHAIN_REBUILD_KEPT_COUNT) == NULL);
    CHECK(BRChainRebuildStepAt(0, -1) == -1 && BRChainRebuildStepAt(0, BR_CHAIN_REBUILD_STEP_COUNT) == -1);
    CHECK(BRChainRebuildStepAt(1, -1) == -1 && BRChainRebuildStepAt(1, BR_CHAIN_REBUILD_FROM_COUNT) == -1);
    CHECK(BRChainRebuildStepCount(0) == (int)(sizeof(BRChainRebuildSteps) / sizeof(*BRChainRebuildSteps)));
    CHECK(BRChainRebuildStepCount(1) == (int)(sizeof(BRChainRebuildFromSteps) / sizeof(*BRChainRebuildFromSteps)));
    // each step once; the plain rebuild has every step but LOWER_FLOOR_TO, the other every step
    for (int k = 0; k < BR_CHAIN_REBUILD_STEP_KINDS; k++) {
        int c0 = 0, c1 = 0;
        for (int i = 0; i < BRChainRebuildStepCount(0); i++) c0 += BRChainRebuildStepAt(0, i) == k;
        for (int i = 0; i < BRChainRebuildStepCount(1); i++) c1 += BRChainRebuildStepAt(1, i) == k;
        CHECK(c0 == (k == BR_CHAIN_REBUILD_LOWER_FLOOR_TO ? 0 : 1));
        CHECK(c1 == 1);
    }
    CHECK(BRChainRebuildIndexOf(0, BR_CHAIN_REBUILD_LOWER_FLOOR_TO) == -1);
    CHECK(! BRChainRebuildMustPrecede(0, BR_CHAIN_REBUILD_LOWER_FLOOR_TO, BR_CHAIN_REBUILD_STOP_SYNC));
    result("edge_rebuild_names_and_bounds", ok);
}

// The order's invariants, read from the sequence (not the enum values).
static void edge_rebuild_invariants(void)
{
    int ok = 1;
    for (int from = 0; from <= 1; from++) {
        static const BRChainRebuildStep clears[] = {
            BR_CHAIN_REBUILD_CLEAR_TX_CACHE, BR_CHAIN_REBUILD_CLEAR_FILTER_CHAIN, BR_CHAIN_REBUILD_CLEAR_SAVED_BLOCKS,
            BR_CHAIN_REBUILD_CLEAR_SYNC_STATE, BR_CHAIN_REBUILD_CLEAR_SCAN_LEDGER, BR_CHAIN_REBUILD_CLEAR_ABANDONMENT,
            BR_CHAIN_REBUILD_CLEAR_OUTGOING_STORE,
        };
        for (size_t i = 0; i < sizeof(clears) / sizeof(*clears); i++) {
            CHECK(BRChainRebuildMustPrecede(from, BR_CHAIN_REBUILD_STOP_SYNC, clears[i]));
            CHECK(BRChainRebuildMustPrecede(from, BR_CHAIN_REBUILD_ARM_RERUN, clears[i]));
        }
        CHECK(BRChainRebuildMustPrecede(from, BR_CHAIN_REBUILD_STOP_SYNC, BR_CHAIN_REBUILD_ARM_RERUN));
        CHECK(BRChainRebuildMustPrecede(from, BR_CHAIN_REBUILD_CLEAR_SCAN_LEDGER, BR_CHAIN_REBUILD_CLEAR_ABANDONMENT));
        CHECK(BRChainRebuildStepAt(from, BRChainRebuildStepCount(from) - 1) == BR_CHAIN_REBUILD_RELOAD);
    }
    CHECK(BRChainRebuildStepAt(1, 0) == BR_CHAIN_REBUILD_LOWER_FLOOR_TO);
    for (int i = 0; i < BRChainRebuildStepCount(0); i++) CHECK(BRChainRebuildStepAt(1, i + 1) == BRChainRebuildStepAt(0, i));
    result("edge_rebuild_invariants", ok);
}


static void edge_failure_rules(void)
{
    int ok = 1;
    for (int k = 0; k < BR_CHAIN_REBUILD_STEP_KINDS; k++) {
        int stops = k == BR_CHAIN_REBUILD_LOWER_FLOOR_TO || k == BR_CHAIN_REBUILD_STOP_SYNC ||
                    k == BR_CHAIN_REBUILD_ARM_RERUN || k == BR_CHAIN_REBUILD_FLOOR_FROM_CACHE;
        CHECK(BRChainRebuildContinuesAfterFailure((BRChainRebuildStep)k) == ! stops);
    }
    // every step that stops the rebuild comes before every clear
    for (int from = 0; from <= 1; from++)
        for (int i = 0; i < BRChainRebuildStepCount(from); i++) {
            int k = BRChainRebuildStepAt(from, i);
            if (BRChainRebuildContinuesAfterFailure((BRChainRebuildStep)k)) continue;
            CHECK(BRChainRebuildMustPrecede(from, (BRChainRebuildStep)k, BR_CHAIN_REBUILD_CLEAR_TX_CACHE));
        }
    result("edge_failure_rules", ok);
}
static void edge_reload_steps(void)
{
    int ok = 1;
    for (int k = 0; k < BR_CHAIN_REBUILD_RELOAD_COUNT; k++) {
        const char *n = BRChainRebuildReloadStepName((BRChainRebuildReloadStep)k);
        CHECK(n != NULL);
        for (int j = 0; n && j < k; j++) CHECK(strcmp(n, BRChainRebuildReloadStepName((BRChainRebuildReloadStep)j)) != 0);
        int c = 0;
        for (int i = 0; i < BR_CHAIN_REBUILD_RELOAD_COUNT; i++) c += BRChainRebuildReloadStepAt(i) == k;
        CHECK(c == 1);
    }
    CHECK(BRChainRebuildReloadStepAt(-1) == -1 && BRChainRebuildReloadStepAt(BR_CHAIN_REBUILD_RELOAD_COUNT) == -1);
    CHECK(BRChainRebuildReloadStepName((BRChainRebuildReloadStep)BR_CHAIN_REBUILD_RELOAD_COUNT) == NULL);
    CHECK(BRChainRebuildReloadStepAt(0) == BR_CHAIN_REBUILD_RERUN_ON_LOAD);
    CHECK(BRChainRebuildReloadStepAt(BR_CHAIN_REBUILD_RELOAD_COUNT - 1) == BR_CHAIN_REBUILD_SYNC_START_FLOOR_STEP);
    result("edge_reload_steps", ok);
}

// A rebuild run as the sequence says, on the real mainnet table, reaches a confirmed transaction
// the cache held below the wallet's birth anchor (a default creation time), with the record's time
// later than its block. Steps the model does not need are no-ops.
static void prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor(void)
{
    int ok = 1;
    const BRCheckPoint *cp = BRMainNetCheckpoints;
    size_t n = sizeof(BRMainNetCheckpoints) / sizeof(*BRMainNetCheckpoints);
    for (int from = 0; from <= 1; from++)
        for (size_t c = 1; c + 1 < n; c += 7) {
            int64_t txHeight = (int64_t)cp[c].height + 10;
            int64_t storedCreation = BR_SCAN_FLOOR_DEFAULT_RESTORE_CREATION_TIME;
            int64_t birth = BRRecoveryBirthCheckpointHeight(cp, n, (uint32_t)storedCreation);
            if (txHeight >= birth) continue;
            uint8_t cache[64]; Rec r[] = { R(txHeight, 1800000000, BODY3) };
            size_t cacheLen = blobOf(cache, r, 1, -1);
            BRScanFloor rec = { 0, 0, 0 };
            int hasStored = 1, synced = 1; int64_t stored = birth, arm = -1, anchor = birth;
            for (int i = 0; i < BRChainRebuildStepCount(from); i++) {
                BRSavedTransactionsFloor m;
                switch (BRChainRebuildStepAt(from, i)) {
                    case BR_CHAIN_REBUILD_LOWER_FLOOR_TO: BRScanFloorLowerTo(&rec, birth - 1, cp, n); break;
                    case BR_CHAIN_REBUILD_FLOOR_FROM_CACHE:
                        BRSavedTransactionsFloorMinima(cache, cacheLen, &m);
                        BRScanFloorFromDiscardedCache(&rec, m.minConfirmedHeight, m.minRecordTime, cp, n);
                        break;
                    case BR_CHAIN_REBUILD_CLEAR_TX_CACHE: cacheLen = 0; break;
                    case BR_CHAIN_REBUILD_CLEAR_SYNC_STATE: synced = 0; break;
                    case BR_CHAIN_REBUILD_SET_FLOOR_AT_BIRTH: hasStored = BRScanFloorBirthToPersist(birth, &stored); break;
                    case BR_CHAIN_REBUILD_RELOAD:
                        for (int j = 0; j < BR_CHAIN_REBUILD_RELOAD_COUNT; j++) {
                            switch (BRChainRebuildReloadStepAt(j)) {
                                case BR_CHAIN_REBUILD_RERUN_ON_LOAD:
                                    BRSavedTransactionsFloorMinima(cache, cacheLen, &m);
                                    BRScanFloorFromDiscardedCache(&rec, m.minConfirmedHeight, m.minRecordTime, cp, n);
                                    break;
                                case BR_CHAIN_REBUILD_RESTORE_AT_FLOOR_TIME:
                                    anchor = BRRecoveryBirthCheckpointHeight(cp, n, (uint32_t)BRScanFloorRestoreCreationTime(storedCreation, rec.time));
                                    break;
                                case BR_CHAIN_REBUILD_SYNC_START_FLOOR_STEP: {
                                    BRScanFloorStep s = BRScanFloorHintStep(1, anchor, hasStored, stored, rec.height, rec.pending);
                                    if (s.setBirth) { hasStored = 1; stored = s.birth; }
                                    if (s.removeBirth) hasStored = 0;
                                    if (s.clearHint) BRScanFloorApplied(&rec);
                                    arm = BRCFRestoreArmHeight(synced, 0, anchor, hasStored, stored);
                                    break;
                                }
                                default: break;
                            }
                        }
                        break;
                    default: break;
                }
            }
            if (! (arm >= 0 && arm <= txHeight))
                printf("  from=%d tx at %lld (birth %lld): the scan arms at %lld\n", from, (long long)txHeight, (long long)birth, (long long)arm);
            CHECK(arm >= 0 && arm <= txHeight);
        }
    result("prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor", ok);
}

// ---- model_ ------------------------------------------------------------------------------------

static uint64_t g_rng = 0x5ca9f100d5eedULL;
static uint64_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static const int64_t GRID[] = { INT64_MIN, -2, -1, 0, 1, 2, 99, 100, 101, 199, 200, 201, 300, 301, 19000000, 19500000,
                                20000000, 21000000, 22650000, 0x7fffffffLL, 0xffffffffLL, 0x100000000LL, INT64_MAX };
#define NGRID (sizeof(GRID) / sizeof(*GRID))
static int64_t pick(void)
{
    switch (rnd() % 4) {
        case 0: return GRID[rnd() % NGRID];
        case 1: return (int64_t)(rnd() % 400);
        case 2: return (int64_t)(rnd() % 30000000);
        default: return (int64_t)rnd();
    }
}

static void model_agrees_floor_rules(void)
{
    int ok = 1;
    BRCheckPoint cp[] = { cpAt(200, 1500000000), cpAt(0, 1389388394), cpAt(100, 1450000000), cpAt(300, 1600000000),
                          cpAt(20000000, 1610000000), cpAt(19000000, 1550000000) };
    const size_t ncp = sizeof(cp) / sizeof(*cp);
    for (int round = 0; round < 200000 && ok; round++) {
        int64_t a = round < (int)(NGRID * NGRID) ? GRID[round / NGRID] : pick();
        int64_t b = round < (int)(NGRID * NGRID) ? GRID[round % NGRID] : pick();
        int64_t c = pick(), d = pick();
        int table = (int)(rnd() % 3);                                 // 0: none (the lookup threw)
        const BRCheckPoint *t = table ? cp : NULL; size_t tn = table == 2 ? ncp : (table == 1 ? 3 : 0);
        Lookup l = { t, tn };
        int64_t v1, v2;

        CHECK(BRScanFloorLower(a, b) == kt_lowerFloor(a, b));
        CHECK(BRScanFloorBirthToPersist(a, &v1) == kt_cfBirthHeightToPersist(a, &v2) && v1 == v2);
        CHECK(BRScanFloorRestoreCreationTime(a, b) == kt_creationTimeForRestore(a, b));

        BRScanFloor f = { a, b, (int)(rnd() & 1) }; KtState k = { a, b, f.pending };
        CHECK(BRScanFloorLowerTo(&f, c, t, tn) == kt_lowerFloorTo(&k, c, l));
        CHECK(floorEq(f, k.hint, k.time, k.pending));

        BRScanFloor g = { a, b, (int)(rnd() & 1) }; KtState m = { a, b, g.pending };
        BRScanFloorFromDiscardedCache(&g, c, d, t, tn); kt_runFloor(&m, c, d, l);
        CHECK(floorEq(g, m.hint, m.time, m.pending));

        BRScanFloorApplied(&g); kt_clearFloorHint(&m); CHECK(floorEq(g, m.hint, m.time, m.pending));
        BRScanFloorRequestAtAnchor(&g); kt_requestFloorAtAnchor(&m); CHECK(floorEq(g, m.hint, m.time, m.pending));
        BRScanFloorForget(&g); kt_forgetFloor(&m); CHECK(floorEq(g, m.hint, m.time, m.pending));

        int loaded = (int)(rnd() & 1), hasP = (int)(rnd() & 1), pend = (int)(rnd() & 1);
        CHECK(stepMatchesKt(BRScanFloorHintStep(loaded, a, hasP, b, c, pend), kt_floorHintStep(loaded, a, hasP, b, c, pend)));
        if (! ok) printf("  at a=%lld b=%lld c=%lld d=%lld table=%d\n", (long long)a, (long long)b, (long long)c, (long long)d, table);
    }
    result("model_agrees_floor_rules", ok);
}

static void model_agrees_minima(void)
{
    int ok = 1;
    static const int64_t HS[] = { 0, 1, 1000, 19500000, 0x7ffffffeLL, 0x7fffffffLL, 0x80000000LL, 0xfffffff0LL, 0xffffffffLL };
    static const int64_t TS[] = { 0, 1, 500, 1550000000, 0xffffffffLL };
    for (int round = 0; round < 20000 && ok; round++) {
        uint8_t b[512]; Rec r[8]; size_t nr = (size_t)(rnd() % 8);
        for (size_t i = 0; i < nr; i++) {
            r[i].height = HS[rnd() % 9]; r[i].time = TS[rnd() % 5];
            r[i].body = BODY3; r[i].bodyLen = (uint32_t)(rnd() % 4);
            r[i].declaredSize = (rnd() % 8) ? -1 : (int64_t)(rnd() % 6);
        }
        int64_t count = (rnd() % 5) ? -1 : (int64_t)(rnd() % 12) + ((rnd() & 1) ? 9995 : 0);
        size_t len = blobOf(b, r, nr, count);
        if (rnd() & 1) len = (size_t)(rnd() % (len + 1));
        uint8_t *exact = malloc(len ? len : 1);
        memcpy(exact, b, len);
        BRSavedTransactionsFloor f = minima(exact, len);
        KtSummary k = kt_summarize(exact, (int32_t)len);
        CHECK(f.declaredCount == k.declaredCount && (int64_t)f.records == k.records &&
              f.minConfirmedHeight == k.minHeight && f.minRecordTime == k.minTime);
        free(exact);
    }
    result("model_agrees_minima", ok);
}

static void model_agrees_checkpoint_time_on_the_real_tables(void)
{
    int ok = 1;
    struct { const BRCheckPoint *cp; size_t n; } nets[] = {
        { BRMainNetCheckpoints, sizeof(BRMainNetCheckpoints) / sizeof(*BRMainNetCheckpoints) },
        { BRTestNetCheckpoints, sizeof(BRTestNetCheckpoints) / sizeof(*BRTestNetCheckpoints) },
    };
    for (int net = 0; net < 2; net++) {
        const BRCheckPoint *cp = nets[net].cp; size_t n = nets[net].n;
        for (size_t i = 0; i < n; i++) {
            int64_t hs[] = { (int64_t)cp[i].height - 1, cp[i].height, (int64_t)cp[i].height + 1 };
            for (int j = 0; j < 3; j++) {
                Lookup l = { cp, n };
                CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, n, hs[j]) == kt_lookup(l, hs[j]));
            }
        }
        for (int r = 0; r < 20000; r++) {
            int64_t h = pick(); Lookup l = { cp, n };
            CHECK(BRScanFloorCheckpointTimeAtOrBelow(cp, n, h) == kt_lookup(l, h));
        }
    }
    result("model_agrees_checkpoint_time_on_the_real_tables", ok);
}

// ---- prop_ -------------------------------------------------------------------------------------

static void prop_lowered_floor_time_anchors_at_or_below_the_height(void)
{
    int ok = 1;
    struct { const BRCheckPoint *cp; size_t n; } nets[] = {
        { BRMainNetCheckpoints, sizeof(BRMainNetCheckpoints) / sizeof(*BRMainNetCheckpoints) },
        { BRTestNetCheckpoints, sizeof(BRTestNetCheckpoints) / sizeof(*BRTestNetCheckpoints) },
    };
    for (int net = 0; net < 2; net++) {
        const BRCheckPoint *cp = nets[net].cp; size_t n = nets[net].n;
        for (size_t i = 0; i < n; i++) {
            int64_t hs[] = { cp[i].height, (int64_t)cp[i].height + 1, (int64_t)cp[i].height + 5000 };
            for (int j = 0; j < 3; j++) {
                if (hs[j] <= 0) continue;
                BRScanFloor f = { 0, 0, 0 };
                BRScanFloorLowerTo(&f, hs[j], cp, n);
                uint32_t creation = (uint32_t)BRScanFloorRestoreCreationTime(1800000000, f.time);
                CHECK(f.time > 0);
                CHECK((int64_t)BRRecoveryBirthCheckpointHeight(cp, n, creation) <= hs[j]);
            }
        }
    }
    result("prop_lowered_floor_time_anchors_at_or_below_the_height", ok);
}

// ---- order_: the sequence against Android's source ---------------------------------------------

static char *slurp(const char *path)
{
    FILE *f = path ? fopen(path, "rb") : NULL;
    if (! f) return NULL;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc((size_t)n + 1);
    if (fread(b, 1, (size_t)n, f) != (size_t)n) { free(b); fclose(f); return NULL; }
    b[n] = 0; fclose(f);
    return b;
}

// The [start, end) span of a function body: from `start` to the next `end` marker (or the end).
static const char *span(const char *text, const char *start, const char *end, const char **endOut)
{
    const char *s = text ? strstr(text, start) : NULL;
    if (! s) return NULL;
    const char *e = end ? strstr(s + strlen(start), end) : NULL;
    *endOut = e ? e : s + strlen(s);
    return s;
}
static long posIn(const char *s, const char *e, const char *token)
{
    const char *p = s ? strstr(s, token) : NULL;
    return (p && p < e) ? (long)(p - s) : -1;
}

static void order_matches_android_source(const char *wmPath, const char *hrPath, const char *rsPath)
{
    int ok = 1;
    char *wm = slurp(wmPath), *hr = slurp(hrPath), *rs = slurp(rsPath);
    CHECK(wm && hr && rs);
    if (wm && hr && rs) {
        const char *wmEnd, *fromEnd, *clrEnd;
        const char *body = span(wm, "fun rebuildFromChainRescan() {", "fun rebuildFromChainRescanFrom(", &wmEnd);
        const char *from = span(wm, "fun rebuildFromChainRescanFrom(", "\n    }\n", &fromEnd);
        const char *clr = span(hr, "fun clearTransactionCacheForRebuild(", "\n}\n", &clrEnd);
        CHECK(body && from && clr);
        if (body && from && clr) {
            long clearCall = posIn(body, wmEnd, "clearTransactionCacheForRebuild(context)");
            // (major, minor): the call's place in rebuildFromChainRescan, and inside the shared clear
            long key[BR_CHAIN_REBUILD_STEP_KINDS][2];
            const char *tok[BR_CHAIN_REBUILD_STEP_KINDS] = { 0 };
            tok[BR_CHAIN_REBUILD_STOP_SYNC] = "NativeBridge.stopSync()";
            tok[BR_CHAIN_REBUILD_ARM_RERUN] = "HistoryRebuildOnUpgrade.armRerun(context)";
            tok[BR_CHAIN_REBUILD_CLEAR_OUTGOING_STORE] = "OutgoingTxStore(context).clearAll()";
            tok[BR_CHAIN_REBUILD_SET_FLOOR_AT_BIRTH] = "cfBirthHeightToPersist(birth)";
            const char *ctok[BR_CHAIN_REBUILD_STEP_KINDS] = { 0 };
            ctok[BR_CHAIN_REBUILD_CLEAR_TX_CACHE] = ".remove(\"saved_transactions\")";
            ctok[BR_CHAIN_REBUILD_CLEAR_FILTER_CHAIN] = ".remove(\"saved_filter_headers\")";
            ctok[BR_CHAIN_REBUILD_CLEAR_SAVED_BLOCKS] = ".remove(\"saved_blocks\")";
            ctok[BR_CHAIN_REBUILD_CLEAR_SYNC_STATE] = ".remove(\"has_synced\")";
            ctok[BR_CHAIN_REBUILD_CLEAR_SCAN_LEDGER] = "CfScanLedgerStore.delete(context)";
            ctok[BR_CHAIN_REBUILD_CLEAR_ABANDONMENT] = "CfAbandonmentStore.clear(context)";
            CHECK(clearCall >= 0);
            for (int k = 0; k < BR_CHAIN_REBUILD_STEP_KINDS; k++) {
                key[k][0] = key[k][1] = -1;
                if (tok[k]) { key[k][0] = posIn(body, wmEnd, tok[k]); key[k][1] = 0; CHECK(key[k][0] >= 0); }
                if (ctok[k]) { key[k][0] = clearCall; key[k][1] = posIn(clr, clrEnd, ctok[k]); CHECK(key[k][1] >= 0); }
            }
            // the rest of the derived stores the shared clear removes, after the step they belong to
            CHECK(posIn(clr, clrEnd, ".remove(\"last_dd_balance\")") > key[BR_CHAIN_REBUILD_CLEAR_SYNC_STATE][1]);
            CHECK(posIn(clr, clrEnd, "FilterHeaderStore.delete(context)") >= 0);
            CHECK(posIn(clr, clrEnd, "SavedBlockStore.delete(context)") >= 0);
            key[BR_CHAIN_REBUILD_RELOAD][0] = LONG_MAX; key[BR_CHAIN_REBUILD_RELOAD][1] = 0;
            key[BR_CHAIN_REBUILD_LOWER_FLOOR_TO][0] = -2; key[BR_CHAIN_REBUILD_LOWER_FLOOR_TO][1] = 0;

            // FLOOR_FROM_CACHE has no call in Android's source yet: its adoption PR adds the step and
            // its token here. Until then the order is checked without it.
            printf("  floorFromCache: not in Android's source yet; order checked without it\n");
            for (int fromHeight = 0; fromHeight <= 1; fromHeight++) {
                int seq[BR_CHAIN_REBUILD_STEP_KINDS], m = 0;
                for (int i = 0; i < BRChainRebuildStepCount(fromHeight); i++)
                    if (BRChainRebuildStepAt(fromHeight, i) != BR_CHAIN_REBUILD_FLOOR_FROM_CACHE) seq[m++] = BRChainRebuildStepAt(fromHeight, i);
                for (int i = 1; i < m; i++) {
                    int a = seq[i - 1], b = seq[i];
                    int inOrder = key[a][0] < key[b][0] || (key[a][0] == key[b][0] && key[a][1] < key[b][1]);
                    if (! inOrder) printf("  the header runs %s before %s; Android's source does not\n",
                                          BRChainRebuildStepName((BRChainRebuildStep)a), BRChainRebuildStepName((BRChainRebuildStep)b));
                    CHECK(inOrder);
                }
            }
            // the rebuild from a height lowers the floor, then runs the rebuild
            long lower = posIn(from, fromEnd, "HistoryRebuildOnUpgrade.lowerFloorTo(context, height)");
            long then = posIn(from, fromEnd, "rebuildFromChainRescan()");
            CHECK(lower >= 0 && then > lower);
            // the reload: each rebuild call in ReconcileScreen is followed by the process restart
            int calls = 0;
            for (const char *p = strstr(rs, "walletManager.rebuildFromChainRescan"); p; p = strstr(p + 1, "walletManager.rebuildFromChainRescan")) {
                const char *exitAt = strstr(p, "Runtime.getRuntime().exit(0)");
                const char *next = strstr(p + 1, "walletManager.rebuildFromChainRescan");
                CHECK(exitAt && (! next || exitAt < next));
                calls++;
            }
            CHECK(calls == 2);
        }
    }
    free(wm); free(hr); free(rs);
    result("order_matches_android_source", ok);
}


// The reload's sub-steps against Android's next start: the rerun, then the load that skips the
// cache while in progress, then the restore time, all in restoreFromDisk; the floor step before
// the scan start in SyncService.
static void order_reload_matches_android_source(const char *wmPath, const char *ssPath)
{
    int ok = 1;
    char *wm = slurp(wmPath), *ss = slurp(ssPath);
    CHECK(wm && ss);
    if (wm && ss) {
        const char *e1, *e2;
        const char *load = span(wm, "fun restoreFromDisk(", "\n    fun ", &e1);
        const char *sync = span(ss, "HistoryRebuildOnUpgrade.floorHint(this@SyncService)", NULL, &e2);
        CHECK(load && sync);
        if (load && sync) {
            long at[BR_CHAIN_REBUILD_RELOAD_COUNT];
            at[BR_CHAIN_REBUILD_RERUN_ON_LOAD] = posIn(load, e1, "HistoryRebuildOnUpgrade.beforeWalletLoad(context)");
            at[BR_CHAIN_REBUILD_LOAD_SKIPS_CACHE] = posIn(load, e1, "if (HistoryRebuildOnUpgrade.isInProgress(context))");
            at[BR_CHAIN_REBUILD_RESTORE_AT_FLOOR_TIME] = posIn(load, e1, "creationTimeForRestore(creationTime, HistoryRebuildOnUpgrade.floorTime(context))");
            at[BR_CHAIN_REBUILD_SYNC_START_FLOOR_STEP] = (long)(e1 - load) + 1;   // SyncService, after the load
            for (int i = 0; i < BR_CHAIN_REBUILD_RELOAD_COUNT; i++) CHECK(at[i] >= 0);
            for (int i = 1; i < BR_CHAIN_REBUILD_RELOAD_COUNT; i++) {
                int a = BRChainRebuildReloadStepAt(i - 1), b = BRChainRebuildReloadStepAt(i);
                if (! (at[a] < at[b])) printf("  the header runs %s before %s; Android's source does not\n",
                    BRChainRebuildReloadStepName((BRChainRebuildReloadStep)a), BRChainRebuildReloadStepName((BRChainRebuildReloadStep)b));
                CHECK(at[a] < at[b]);
            }
            long step = posIn(sync, e2, "floorHintStep(");
            long arm = posIn(sync, e2, "compactFilterBirthHeight(");
            CHECK(step >= 0 && arm > step);
        }
    }
    free(wm); free(ss);
    result("order_reload_matches_android_source", ok);
}

// Once the first clear has run, rebuildFromChainRescan and rebuildFromChainRescanFrom run to the
// end: no return, throw, check, require, error or !! (outside comments and strings) after the first
// clear call, so every later step still runs. Before it, an early stop is allowed (the steps the
// clears depend on stop the rebuild; BRChainRebuildContinuesAfterFailure). The first clear call is
// clearTransactionCacheForRebuild in the rebuild, and the call to the rebuild in the "from" variant.
static void stripCommentsAndStrings(char *b, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        if (b[i] == '/' && i + 1 < n && b[i + 1] == '/') { while (i < n && b[i] != '\n') b[i++] = ' '; }
        else if (b[i] == '/' && i + 1 < n && b[i + 1] == '*') {
            while (i + 1 < n && ! (b[i] == '*' && b[i + 1] == '/')) b[i++] = ' ';
            if (i + 1 < n) { b[i] = b[i + 1] = ' '; i++; }
        } else if (b[i] == '"') {
            b[i++] = ' ';
            while (i < n && b[i] != '"') { if (b[i] == '\\' && i + 1 < n) b[i++] = ' '; b[i++] = ' '; }
            if (i < n) b[i] = ' ';
        }
    }
}
static int hasWord(const char *s, const char *e, const char *w)
{
    size_t wl = strlen(w);
    for (const char *p = s; p && p + wl <= e; p++) {
        if (strncmp(p, w, wl) != 0) continue;
        int before = p == s || ! (isalnum((unsigned char)p[-1]) || p[-1] == '_');
        int after = p + wl >= e || ! (isalnum((unsigned char)p[wl]) || p[wl] == '_') || w[wl - 1] == '(' || w[wl - 1] == '!';
        if (before && after) return 1;
    }
    return 0;
}
static void order_rebuild_has_no_early_exit(const char *wmPath)
{
    int ok = 1;
    char *wm = slurp(wmPath);
    CHECK(wm != NULL);
    if (wm) {
        stripCommentsAndStrings(wm, strlen(wm));
        const char *names[] = { "fun rebuildFromChainRescan() {", "fun rebuildFromChainRescanFrom(height: Long) {" };
        const char *firstClear[] = { "clearTransactionCacheForRebuild(context)", "rebuildFromChainRescan()" };
        for (int f = 0; f < 2; f++) {
            const char *e;
            const char *b = span(wm, names[f], "\n    }\n", &e);
            CHECK(b != NULL);
            if (! b) continue;
            const char *from = strstr(b + strlen(names[f]), firstClear[f]);
            CHECK(from != NULL && from < e);
            if (! from || from >= e) continue;
            static const char *exits[] = { "return", "throw", "check(", "require(", "error(", "!!" };
            for (size_t i = 0; i < sizeof(exits) / sizeof(*exits); i++) {
                int found = hasWord(from, e, exits[i]);
                if (found) printf("  %s has `%s` after its first clear\n", names[f], exits[i]);
                CHECK(! found);
            }
        }
    }
    free(wm);
    result("order_rebuild_has_no_early_exit", ok);
}

// ---- vectors_ ----------------------------------------------------------------------------------

static void vectors_match_the_model(const char *path)
{
    int ok = 1;
    FILE *f = path ? fopen(path, "r") : NULL;
    CHECK(f != NULL);
    BRCheckPoint cp[64]; size_t ncp = 0;
    long lines = 0, seen[16] = { 0 };
    char line[512];
    int rebuildIdx = 0, fromIdx = 0, keptIdx = 0, reloadIdx = 0;
    while (f && fgets(line, sizeof(line), f)) {
        long long a, b, c, d, e, r1, r2, r3; int p, w, q;
        char s1[64], s2[64];
        if (line[0] == '#' || line[0] == '\n') continue;
        lines++;
        if (sscanf(line, "checkpoint %lld %lld", &a, &b) == 2) {
            if (ncp < 64) cp[ncp++] = cpAt((uint32_t)a, (uint32_t)b);
            seen[0]++;
        } else if (sscanf(line, "lower %lld %lld %lld", &a, &b, &r1) == 3) {
            CHECK(kt_lowerFloor(a, b) == r1); seen[1]++;
        } else if (sscanf(line, "birth %lld %63s %lld", &a, s1, &r1) == 3) {
            int64_t v; int has = kt_cfBirthHeightToPersist(a, &v);
            CHECK(strcmp(s1, has ? "persist" : "remove") == 0 && v == r1); seen[2]++;
        } else if (sscanf(line, "cptime-none %lld %lld", &a, &r1) == 2) {
            Lookup l = { NULL, 0 }; CHECK(kt_lookup(l, a) == r1); seen[3]++;
        } else if (sscanf(line, "cptime %lld %lld", &a, &r1) == 2) {
            Lookup l = { cp, ncp }; CHECK(kt_lookup(l, a) == r1); seen[4]++;
        } else if (sscanf(line, "lowerto %lld %lld %d %lld -> %d %lld %lld %d", &a, &b, &p, &c, &w, &r1, &r2, &q) == 8) {
            KtState k = { a, b, p }; Lookup l = { cp, ncp };
            CHECK(kt_lowerFloorTo(&k, c, l) == w && k.hint == r1 && k.time == r2 && k.pending == q); seen[5]++;
        } else if (sscanf(line, "cache %lld %lld %d %lld %lld -> %lld %lld %d", &a, &b, &p, &c, &d, &r1, &r2, &q) == 8) {
            KtState k = { a, b, p }; Lookup l = { cp, ncp };
            kt_runFloor(&k, c, d, l);
            CHECK(k.hint == r1 && k.time == r2 && k.pending == q); seen[6]++;
        } else if (strncmp(line, "step ", 5) == 0) {
            char ps[32], sb[32]; int loaded, pend, rem, clr, above;
            if (sscanf(line, "step %d %lld %31s %lld %d -> %31s %d %d %d", &loaded, &a, ps, &c, &pend, sb, &rem, &clr, &above) == 9) {
                int hasP = strcmp(ps, "-") != 0; e = hasP ? atoll(ps) : 0;
                KtStep k = kt_floorHintStep(loaded, a, hasP, e, c, pend);
                int hasS = strcmp(sb, "-") != 0;
                CHECK(k.hasSet == hasS && (! hasS || k.set == atoll(sb)) && k.remove == rem && k.clear == clr && k.above == above);
                seen[7]++;
            } else CHECK(0);
        } else if (sscanf(line, "restoretime %lld %lld %lld", &a, &b, &r3) == 3) {
            CHECK(kt_creationTimeForRestore(a, b) == r3); seen[8]++;
        } else if (sscanf(line, "rebuild-from %lld %63s", &a, s2) == 2) {
            CHECK(a == fromIdx && strcmp(s2, BRChainRebuildStepName((BRChainRebuildStep)BRChainRebuildStepAt(1, fromIdx))) == 0);
            fromIdx++; seen[9]++;
        } else if (sscanf(line, "rebuild %lld %63s", &a, s2) == 2) {
            CHECK(a == rebuildIdx && strcmp(s2, BRChainRebuildStepName((BRChainRebuildStep)BRChainRebuildStepAt(0, rebuildIdx))) == 0);
            rebuildIdx++; seen[10]++;
        } else if (strncmp(line, "minima ", 7) == 0) {
            char hex[1024]; long long dc, rc, mh, mt;
            if (sscanf(line, "minima %1023s %lld %lld %lld %lld", hex, &dc, &rc, &mh, &mt) == 5) {
                uint8_t bb[512]; size_t bl = 0;
                if (strcmp(hex, "empty") != 0) for (; hex[2 * bl] && bl < sizeof(bb); bl++) { unsigned v; sscanf(hex + 2 * bl, "%2x", &v); bb[bl] = (uint8_t)v; }
                KtSummary k = kt_summarize(bl ? bb : NULL, (int32_t)bl);
                CHECK(k.declaredCount == dc && k.records == rc && k.minHeight == mh && k.minTime == mt);
                seen[12]++;
            } else CHECK(0);
        } else if (sscanf(line, "reload %lld %63s", &a, s2) == 2) {
            CHECK(a == reloadIdx && strcmp(s2, BRChainRebuildReloadStepName((BRChainRebuildReloadStep)BRChainRebuildReloadStepAt(reloadIdx))) == 0);
            reloadIdx++; seen[13]++;
        } else if (sscanf(line, "kept %lld %63s", &a, s2) == 2) {
            CHECK(a == keptIdx && keptIdx < BR_CHAIN_REBUILD_KEPT_COUNT && strcmp(s2, BRChainRebuildKeepName(BRChainRebuildKept[keptIdx])) == 0);
            keptIdx++; seen[11]++;
        } else {
            printf("  unreadable vector line: %s", line); ok = 0;
        }
    }
    if (f) fclose(f);
    CHECK(rebuildIdx == BRChainRebuildStepCount(0) && fromIdx == BRChainRebuildStepCount(1) && keptIdx == BR_CHAIN_REBUILD_KEPT_COUNT);
    CHECK(reloadIdx == BR_CHAIN_REBUILD_RELOAD_COUNT);
    for (int i = 0; i < 14; i++) CHECK(seen[i] > 0);
    printf("  %ld vector lines\n", lines);
    result("vectors_match_the_model", ok);
}

int main(int argc, char **argv)
{
    kt_summarize_empty_and_zero_count();
    kt_summarize_one_real_record();
    kt_summarize_mixed_heights_take_the_minimum_and_ignore_the_sentinel();
    kt_summarize_all_unconfirmed_gives_no_height_but_a_time();
    kt_summarize_unconfirmed_time_below_lowers_only_the_time();
    kt_summarize_truncated_mid_record_keeps_whole_records();
    kt_summarize_size_past_the_end_stops_without_a_hint();
    kt_summarize_count_above_the_cap_gives_no_hint();
    kt_summarize_never_fails_on_arbitrary_bytes();
    kt_birth_real_checkpoint_height_persisted();
    kt_birth_zero_never_persisted();
    kt_birth_negative_not_persisted();
    kt_birth_smallest_positive_persisted();
    kt_run_floor_from_the_discarded_cache();
    kt_run_interrupted_keeps_the_first_hint();
    kt_run_never_raises_the_floor();
    kt_run_lowers_an_existing_hint();
    kt_lowerFloorTo_sets_hint_time_and_pending();
    kt_lowerFloorTo_never_raises_an_existing_floor();
    kt_lowerFloorTo_ignores_a_non_positive_height();
    kt_markNotNeeded_forgets_the_floor_only_when_asked();
    kt_clearFloorHint_forgets_only_the_height();
    kt_run_floor_time_reaches_the_checkpoint_at_or_below_the_hint();
    kt_run_floor_time_is_the_lower_of_record_and_checkpoint();
    kt_run_lookup_that_throws_falls_back_to_the_record_time();
    kt_run_asks_the_lookup_for_the_final_hint();
    kt_run_only_unconfirmed_records_marks_the_floor_pending();
    kt_run_floor_height_marks_pending_and_no_floor_does_not();
    kt_pending_marker_goes_with_the_hint_and_a_different_wallet();
    kt_armRerun_keeps_the_floors();
    kt_step_no_hint_does_nothing();
    kt_step_wallet_not_loaded_keeps_the_hint();
    kt_step_anchor_above_the_hint_keeps_it();
    kt_step_anchor_at_or_below_lowers_a_higher_floor();
    kt_step_no_persisted_floor_persists_the_anchor();
    kt_step_floor_at_or_below_the_anchor_stays();
    kt_step_floor_between_anchor_and_hint_goes_to_the_anchor();
    kt_step_no_hint_but_pending_lowers_to_the_anchor();
    kt_step_no_hint_nothing_pending_does_nothing();
    kt_step_pending_floor_waits_for_the_wallet();
    kt_step_genesis_anchor_is_a_valid_anchor();
    kt_step_result_feeds_the_birth_choice_at_or_below_the_hint();
    kt_creationTimeForRestore_lowers_to_the_floor_time_only();
    kt_rebuild_arms_the_rerun_and_keeps_the_floor_time();
    kt_rebuild_still_forgets_recorded_sends();
    kt_rebuild_deletes_the_ledger_and_the_band();
    kt_rebuild_clears_every_derived_store();
    edge_lower_bounds();
    edge_birth_null_out_and_huge();
    edge_checkpoint_lookup_bounds();
    edge_lowerTo_lookup_miss_keeps_the_time();
    edge_cache_clears_a_pending_flag_with_no_floor();
    edge_step_never_writes_zero_or_raises();
    edge_restore_time_bounds();
    edge_rebuild_names_and_bounds();
    edge_rebuild_invariants();
    edge_failure_rules();
    edge_reload_steps();
    model_agrees_floor_rules();
    model_agrees_minima();
    model_agrees_checkpoint_time_on_the_real_tables();
    prop_lowered_floor_time_anchors_at_or_below_the_height();
    prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor();
    order_matches_android_source(argc > 2 ? argv[2] : NULL, argc > 3 ? argv[3] : NULL, argc > 4 ? argv[4] : NULL);
    order_reload_matches_android_source(argc > 2 ? argv[2] : NULL, argc > 5 ? argv[5] : NULL);
    order_rebuild_has_no_early_exit(argc > 2 ? argv[2] : NULL);
    vectors_match_the_model(argc > 1 ? argv[1] : NULL);
    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
