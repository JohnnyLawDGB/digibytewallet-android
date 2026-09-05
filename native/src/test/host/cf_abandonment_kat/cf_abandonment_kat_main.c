// Host KAT: proves BRCFAbandonment.h's three decisions -- band folding,
// retirement, proven coverage -- against the cases CfAbandonmentStoreTest.kt
// and AbandonedBandRetirementTest.kt pin on the host JVM, and then, the part
// a scalar table test cannot do, against a ledger DRIVEN BY THE REAL
// BRCFScanLedger.c: a scan that climbs through a band, a ledger re-initialised
// above one, a live abandonment, a retirement.
//
// The direction that costs money: declaring a band covered by a ledger that
// never looked at it. That is the RED gate.
#include <stdio.h>
#include <string.h>

#include "BRCFAbandonment.h"
#include "BRCFAbandonment.h"   // include twice: the guard must hold

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static UInt128 peerAddr(uint8_t tag) { UInt128 p = UINT128_ZERO; p.u8[15] = tag; return p; }

// The Note 8 numbers from AbandonedBandRetirementTest.kt, so the two suites
// are visibly the same cases.
#define BAND_LOW   24050000u
#define BAND_HIGH  24066882u
#define START      24000000u
#define SCANNED    24074267u
#define FLOOR_LIVE 24070273u

static void bandNext(void)
{
    BRCFAbandonedBand b, b2;
    const BRCFAbandonedBand existing = { 10, 20, 1 };

    // test1 -- first abandonment with a pending hint records an exact range.
    check(BRCFAbandonedBandNext(NULL, 23900125u, 23900120u, &b) == 1
          && b.low == 23900120u && b.high == 23900124u && b.lowKnown == 1,
          "test1: first abandonment with a hint is [hint .. abandonedBelow-1], low known");

    // No hint: the bottom is UNKNOWN and must not be invented.
    check(BRCFAbandonedBandNext(NULL, 23900125u, 0, &b) == 1
          && b.high == 23900124u && b.lowKnown == 0 && b.low == 0,
          "test1: first abandonment without a hint marks the low UNKNOWN (stored as 0)");

    // A hint above the watermark is a stale poll, not a bottom.
    check(BRCFAbandonedBandNext(NULL, 1000u, 5000u, &b) == 1 && b.lowKnown == 0,
          "test1: a hint above the watermark is rejected");
    check(BRCFAbandonedBandNext(NULL, 1000u, 999u, &b) == 1 && b.lowKnown == 1 && b.low == 999u,
          "test1: a hint exactly at the top is accepted");
    check(BRCFAbandonedBandNext(NULL, 1000u, 1000u, &b) == 1 && b.lowKnown == 0,
          "test1: a hint equal to the watermark (one above the top) is rejected");

    // Later abandonment extends upward, keeping the original bottom.
    BRCFAbandonedBandNext(NULL, 1000u, 900u, &b);
    check(BRCFAbandonedBandNext(&b, 1500u, 1400u, &b2) == 1
          && b2.low == 900u && b2.high == 1499u && b2.lowKnown == 1,
          "test1: a later abandonment extends upward and keeps the original bottom");
    {
        BRCFAbandonedBand unknown = { 0, 999u, 0 };
        check(BRCFAbandonedBandNext(&unknown, 1500u, 1400u, &b2) == 1
              && b2.low == 0 && b2.lowKnown == 0 && b2.high == 1499u,
              "test1: extending an unknown-low band keeps it unknown (a late hint is not the bottom)");
    }

    // test2 -- nothing abandoned / same watermark: the record must NOT churn.
    // The caller resets "recovered" on a change, so churn makes the banner
    // un-clearable.
    b = existing; b.low = 77;   // sentinel: out must be untouched on 0
    check(BRCFAbandonedBandNext(NULL, 0, 5, &b) == 0 && b.low == 77,
          "test2: abandonedBelow == 0 with no band is unchanged, out untouched");
    check(BRCFAbandonedBandNext(&existing, 0, 5, &b) == 0 && b.low == 77,
          "test2: abandonedBelow == 0 with a band is unchanged");
    check(BRCFAbandonedBandNext(&existing, 21, 5, &b) == 0 && b.low == 77,
          "test2: a re-read of the SAME watermark (high == existing.high) is unchanged");
    check(BRCFAbandonedBandNext(&existing, 15, 5, &b) == 0 && b.low == 77,
          "test2: an OLDER watermark below the band top is unchanged");
    check(BRCFAbandonedBandNext(&existing, 22, 5, &b) == 1 && b.high == 21 && b.low == 10,
          "test2: one height higher is a change");
}

static void retired(void)
{
    // test4 -- floor at or below the band low: retired.
    check(BRCFAbandonedBandIsRetired(BAND_LOW, BAND_LOW), "test4: floor AT the band low is retired");
    check(BRCFAbandonedBandIsRetired(BAND_LOW, 0),        "test4: floor 0 is retired");
    check(BRCFAbandonedBandIsRetired(BAND_LOW, 23000000u),
          "test4: a deeper band still clamping below ours does not make ours unrecovered");
    // Floor inside the band: not retired. Partial retirement is not recovery.
    check(! BRCFAbandonedBandIsRetired(BAND_LOW, 24060000u), "test4: floor inside the band is not retired");
    check(! BRCFAbandonedBandIsRetired(BAND_LOW, FLOOR_LIVE), "test4: the live Note 8 floor is not retired");
    check(! BRCFAbandonedBandIsRetired(BAND_LOW, 24055000u), "test4: half-retired is still a gap");
    check(! BRCFAbandonedBandIsRetired(BAND_LOW, BAND_LOW + 1), "test4: one height still clamped is still a gap");
}

static void coverage(void)
{
    // test3 -- proven coverage over scalars (AbandonedBandRetirementTest.kt).
    check(BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, SCANNED, 0, 0),
          "test3: a band below a contiguous scan is proven covered");
    // THE false all-clear. RED gate.
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, 24070000u, SCANNED, 0, 0),
          "test3: a ledger started above the band proves nothing about it");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, 24060000u, 0, 0),
          "test3: a scan that has not reached the band top proves nothing");
    check(BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, BAND_HIGH, 0, 0),
          "test3: scannedThrough exactly at the band top counts (high itself was evaluated)");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, BAND_HIGH - 1, 0, 0),
          "test3: one short of the band top does not count");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, SCANNED, FLOOR_LIVE, 0),
          "test3: a floor still clamping is not proven coverage");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, SCANNED, 0, 12),
          "test3: heights given up block the claim");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, 0, SCANNED, 0, 0),
          "test3: a zero ledgerStart is a failed read, never evidence");
    check(! BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, START, 0, 0, 0),
          "test3: a zero scannedThrough is a failed read, never evidence");
    check(BRCFAbandonedBandCoverageIsProven(BAND_LOW, BAND_HIGH, 1, BAND_LOW, SCANNED, 0, 0),
          "test3: a ledger started exactly at the band low still proves it");

    // Unknown low: 0 means unknown, the effective low is the ledger start.
    check(BRCFAbandonedBandCoverageIsProven(0, BAND_HIGH, 0, START, SCANNED, 0, 0),
          "test3: an unknown low is treated as the ledger start, not as zero");
    check(! BRCFAbandonedBandCoverageIsProven(23000000u, BAND_HIGH, 1, START, SCANNED, 0, 0),
          "test3: a KNOWN low below the ledger start is still refused");
    check(! BRCFAbandonedBandCoverageIsProven(0, BAND_HIGH, 0, START, 24060000u, 0, 0),
          "test3: an unknown low still needs the scan past the band top");
}

static void realLedger(void)
{
    static BRCFScanLedger l;   // static: the struct is large
    BRCFAbandonedBand band, next;
    UInt128 A = peerAddr(1);
    uint32_t h, count = 0;

    // test5 -- a scan driven through the real ledger climbs through a band.
    BRCFScanLedgerInit(&l, 100);
    BRCFScanLedgerRecordRequested(&l, 100, 140, A, 12024, 1000);
    for (h = 100; h <= 140; h++) BRCFScanLedgerMarkEvaluated(&l, h);
    check(BRCFScanLedgerScannedThrough(&l) == 140 && l.start == 100 && l.abandonedBelow == 0
          && l.gaveUpCount == 0,
          "test5: the real ledger reads start=100 scannedThrough=140 floor=0 gaveUp=0");

    band.low = 110; band.high = 130; band.lowKnown = 1;
    check(BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: a band inside [start..scannedThrough] is proven by the real ledger");
    check(BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band)
          == BRCFAbandonedBandCoverageIsProven(band.low, band.high, band.lowKnown,
                                               BRCFScanLedgerStartHeight(&l),
                                               BRCFScanLedgerScannedThrough(&l),
                                               BRCFScanLedgerAbandonedBelow(&l),
                                               BRCFScanLedgerGaveUpCount(&l)),
          "test5: ByLedger agrees with the scalar form fed by the public accessors");

    band.low = 90; band.high = 130; band.lowKnown = 1;
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: a band starting below the ledger's start is refused by the real ledger");
    band.low = 0; band.lowKnown = 0;
    check(BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: the same band with an UNKNOWN low is proven (effective low = start)");

    band.low = 110; band.high = 141; band.lowKnown = 1;
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: a band whose top is one past scannedThrough is not proven");

    // A hole: request 141..150, evaluate all but 145. scannedThrough must stop
    // at 144 (the real ledger's contiguity), so a band [110..146] is NOT proven.
    BRCFScanLedgerRecordRequested(&l, 141, 150, A, 12024, 1001);
    for (h = 141; h <= 150; h++) if (h != 145) BRCFScanLedgerMarkEvaluated(&l, h);
    check(BRCFScanLedgerScannedThrough(&l) == 144, "test5: a hole at 145 pins the real scannedThrough at 144");
    band.low = 110; band.high = 146; band.lowKnown = 1;
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: a band spanning an unevaluated hole is not proven, even though heights above it were");
    BRCFScanLedgerMarkEvaluated(&l, 145);
    check(BRCFScanLedgerScannedThrough(&l) == 150 && BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: filling the hole advances the real ledger and the band becomes proven");

    // A ledger re-initialised ABOVE the band: the scan sails on, the band is
    // never proven. This is the Note 8 geometry.
    BRCFScanLedgerInit(&l, 200);
    BRCFScanLedgerRecordRequested(&l, 200, 260, A, 12024, 1002);
    for (h = 200; h <= 260; h++) BRCFScanLedgerMarkEvaluated(&l, h);
    band.low = 110; band.high = 130; band.lowKnown = 1;
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band),
          "test5: a ledger re-Init'd above the band never proves it, however far it scans");

    // test6 -- a live abandonment through the real valve API, folded into a
    // band, then retired through the real lowering path.
    BRCFScanLedgerInit(&l, 300);
    BRCFScanLedgerRecordRequested(&l, 300, 320, A, 12024, 1003);
    BRCFScanLedgerAbandonUnscannableBelow(&l, 300, 311, &count);
    check(BRCFScanLedgerAbandonedBelow(&l) == 311,
          "test6: AbandonUnscannableBelow(300, floor 311) sets the real floor to 311");
    check(BRCFAbandonedBandNext(NULL, BRCFScanLedgerAbandonedBelow(&l), 300, &next) == 1
          && next.low == 300 && next.high == 310 && next.lowKnown == 1,
          "test6: folding the real floor yields the band [300..310]");
    check(! BRCFAbandonedBandIsRetired(next.low, BRCFScanLedgerAbandonedBelow(&l)),
          "test6: while the floor stands the band is not retired");
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &next),
          "test6: and coverage is not proven while the floor stands");
    check(BRCFAbandonedBandNext(&next, BRCFScanLedgerAbandonedBelow(&l), 300, &band) == 0,
          "test6: re-polling the same real floor does not churn the band");
    check(BRCFScanLedgerRetireAbandonedTo(&l, 305) == 6
          && ! BRCFAbandonedBandIsRetired(next.low, BRCFScanLedgerAbandonedBelow(&l)),
          "test6: retiring only [305..310] is partial -- the band is still not retired");
    check(BRCFScanLedgerRetireAbandonedTo(&l, 300) == 5
          && BRCFAbandonedBandIsRetired(next.low, BRCFScanLedgerAbandonedBelow(&l)),
          "test6: retiring down to the band low retires the band");

    // test7 -- a given-up hole blocks the claim through the real struct.
    BRCFScanLedgerInit(&l, 400);
    BRCFScanLedgerRecordRequested(&l, 400, 410, A, 12024, 1004);
    for (h = 400; h <= 410; h++) BRCFScanLedgerMarkEvaluated(&l, h);
    band.low = 402; band.high = 405; band.lowKnown = 1;
    check(BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band), "test7: proven before any give-up");
    l.gaveUpCount = 1;
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, &band), "test7: a non-zero gaveUpCount blocks the claim");
    l.gaveUpCount = 0;

    check(! BRCFAbandonedBandCoverageIsProvenByLedger(NULL, &band), "test7: a NULL ledger is never evidence");
    check(! BRCFAbandonedBandCoverageIsProvenByLedger(&l, NULL), "test7: a NULL band is never evidence");
}

int main(void)
{
    bandNext();
    retired();
    coverage();
    realLedger();
    if (g_fail) { printf("%d check(s) FAILED\n", g_fail); return 1; }
    printf("cf_abandonment_kat: all checks passed\n");
    return 0;
}
