// Host KAT for 4.1.4 item CF-LEDGER-GAPS, corroborated-rebuild cases. The other cases live in
// cf_ledger_gaps_kat_main.c; this unit is separate only because it needs the G5 quorum rig of
// cf_corroboration_kat (a filter-header chain above the top compiled checkpoint, a cfheaders
// source and two answering non-source peers), whose file statics clash with that unit's.
//
// Invariants pinned here, at _BRPeerManagerCFCheckptDisagreedLocked (a strict majority of answering
// non-source peers reports a different filter header at a 1000-multiple h, so the chain is dropped
// and rebuilt from h - 999):
//
//   (b) BAND KEPT.  A recorded abandoned band survives any ledger re-Init until a rescan clears it.
//       When the new start lands strictly INSIDE a band (scannedThrough + 1 < start < band), the
//       part below the new start stays recorded: abandonedBelow == start. Only a re-Init at or below
//       the band's bottom (scannedThrough + 1) is a rescan that carries nothing.
//
//   (c) CONTIGUOUS.  scannedThrough never advances over a height that was never requested and
//       evaluated. The rebuild's start comes from the disputed height, not from the scan frontier,
//       so with the frontier below h - 999 a plain re-Init at h - 999 would read [frontier .. h-1000]
//       as scanned. The rebuild starts at or below the frontier instead; what lies below the block
//       floor is surfaced as a band, never skipped.
//
// The rig is cf_corroboration_kat's, included whole with its main renamed. Each case is selected by
// name (argv[1]); with no argument the case names are listed (run.sh merges both units' lists).

// The rig's tests read _cfCkDisputeCount, which BRPeerManager.c compiles only for a KAT read-out
// (its cf_corroboration_kat run.sh passes the same flag). It adds a read-only helper, nothing else.
#define CF_CKPT_KAT_READOUT
#define main cf_corroboration_kat_main
#include "../cf_corroboration_kat/cf_corroboration_kat_main.c"
#undef main

// The rig's own check() prints "PASS: "/"FAIL: " (its setup checks use it and still count in
// g_fail); the cases here print in run.sh's "  PASS "/"  FAIL " format.
static void chk(int c, const char *d) { printf(c ? "  PASS %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }

typedef struct { Rig r; BRPeer *third; BRPeerManager *m; uint32_t S; } RebuildFx;

// The G5 rig (chain [top+1 .. top+3000], resident blocks [top+500 .. top+3000], so the block floor
// is top+500), a third answering filter peer, and the scan armed with the ledger started at the
// chain's start and [start .. start + evaluated - 1] requested and evaluated.
static int rebuildFxInit(RebuildFx *f, uint32_t evaluated)
{
    memset(f, 0, sizeof(*f));
    rigInit(&f->r, 3000);
    f->m = f->r.manager;
    f->third = addConnectedFilterPeer(f->m, 0x03, 10003, f->r.tip);
    BRPeerManager *m = f->m;
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = f->r.start;
    BRCFScanLedgerInit(&m->cfLedger, f->r.start);
    uint32_t last = f->r.start + evaluated - 1;
    BRCFScanLedgerRecordRequested(&m->cfLedger, f->r.start, last, UINT128_ZERO, 0, 1);
    for (uint32_t h = f->r.start; h <= last; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);
    m->autoFetchCFiltersThrough = last;
    f->S = BRCFScanLedgerScannedThrough(&m->cfLedger);
    chk(g_fail == 0, "setup: the G5 rig is built");
    chk(f->S == last, "setup: the ledger has requested and evaluated exactly the fetched range");
    MGR_LOCK(m);
    uint32_t floor = _BRPeerManagerBlockFloor(m);
    MGR_UNLOCK(m);
    chk(floor == f->r.topCompiled + 500, "setup: the resident block floor is top + 500");
    return g_fail == 0;
}

// Two of two answering non-source peers report the same different header at top + 2000: the quorum
// holds and the chain is rebuilt from top + 1001.
static void rebuildByQuorum(RebuildFx *f)
{
    uint8_t before = f->m->cfReanchorCount;
    uint32_t wrongAt = f->r.topCompiled + 2000;
    relayDissent(&f->r, f->r.other, wrongAt, 0xEE);
    relayDissent(&f->r, f->third, wrongAt, 0xEE);
    chk(f->m->compactFilterChain == NULL && f->m->cfReanchorCount == before + 1,
        "the quorum rebuilt the chain (the source's chain is dropped, the budget charged once)");
}

// probe_rebuild: a band surfaced earlier ([S+1 .. top+1499]) that the rebuild's start lands inside.
static void case_rebuild_band_inside_kept(void)
{
    RebuildFx f;
    if (! rebuildFxInit(&f, 100)) { rigFree(&f.r); return; }
    uint32_t cnt = 0, band = f.r.topCompiled + 1500;
    BRCFScanLedgerAbandonUnscannableBelow(&f.m->cfLedger, f.S + 1, band, &cnt);
    chk(f.m->cfLedger.abandonedBelow == band, "setup: a band is recorded below top + 1500");
    rebuildByQuorum(&f);
    uint32_t st = BRCFScanLedgerStartHeight(&f.m->cfLedger);
    uint32_t below = f.m->cfLedger.abandonedBelow;
    chk(st > f.S + 1 && st < band, "setup: the rebuild's start lands inside the band");
    chk(below == st,
        "rebuild inside a band: the part of the band below the new start stays recorded (abandonedBelow == start)");
    rigFree(&f.r);
}

// probe_noband: no band at all; the frontier (top+101) lies below the block floor (top+500), which
// lies below the rebuild's start (top+1001).
static void case_rebuild_never_marks_unrequested_scanned(void)
{
    RebuildFx f;
    if (! rebuildFxInit(&f, 100)) { rigFree(&f.r); return; }
    uint32_t floor = f.r.topCompiled + 500;
    rebuildByQuorum(&f);
    uint32_t scanned = BRCFScanLedgerScannedThrough(&f.m->cfLedger);
    uint32_t below = f.m->cfLedger.abandonedBelow;
    chk(scanned <= f.S || below > scanned,
        "rebuild: scannedThrough never covers a height above the old frontier that was never requested, "
        "unless it is recorded in a band");
    chk(below == floor && BRCFScanLedgerStartHeight(&f.m->cfLedger) == floor,
        "the heights between the frontier and the block floor are surfaced; the rebuild starts at the floor");
    rigFree(&f.r);
}

// The frontier (top+701) lies above the block floor: the rebuild starts at the frontier, no band.
static void case_rebuild_resumes_at_frontier_above_floor(void)
{
    RebuildFx f;
    if (! rebuildFxInit(&f, 700)) { rigFree(&f.r); return; }
    rebuildByQuorum(&f);
    uint32_t scanned = BRCFScanLedgerScannedThrough(&f.m->cfLedger);
    chk(scanned == f.S && BRCFScanLedgerStartHeight(&f.m->cfLedger) == f.S + 1,
        "rebuild above the floor: the ledger restarts at the scan frontier, nothing above it reads as scanned");
    chk(f.m->autoFetchCFiltersStart == f.S + 1 && f.m->autoFetchCFiltersThrough == f.S,
        "the fetch cursor resumes at the scan frontier");
    chk(f.m->cfLedger.abandonedBelow == 0, "no band is invented when nothing was skipped");
    rigFree(&f.r);
}

// GUARD (every arm): a frontier above h - 999 still rebuilds from h - 999, so the blocks the
// disputed filters "cleared" are scanned again.
static void case_rebuild_rescans_disputed_range(void)
{
    RebuildFx f;
    if (! rebuildFxInit(&f, 1500)) { rigFree(&f.r); return; }
    uint32_t from = f.r.topCompiled + 1001;
    rebuildByQuorum(&f);
    chk(BRCFScanLedgerStartHeight(&f.m->cfLedger) == from && BRCFScanLedgerScannedThrough(&f.m->cfLedger) == from - 1,
        "a frontier above the disputed range still rebuilds from h - 999 (those heights are scanned again)");
    chk(f.m->cfLedger.abandonedBelow == 0, "nothing is surfaced when nothing was skipped");
    rigFree(&f.r);
}

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "rebuild_band_inside_kept",                case_rebuild_band_inside_kept },
    { "rebuild_never_marks_unrequested_scanned", case_rebuild_never_marks_unrequested_scanned },
    { "rebuild_resumes_at_frontier_above_floor", case_rebuild_resumes_at_frontier_above_floor },
    { "rebuild_rescans_disputed_range",          case_rebuild_rescans_disputed_range },
};

int main(int argc, char **argv)
{
    if (argc < 2) {
        for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) printf("%s\n", CASES[i].name);
        return 2;
    }
    for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("case %s\n", CASES[i].name);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail ? "FAIL" : "PASS");
        return g_fail ? 1 : 0;
    }
    printf("unknown case %s\n", argv[1]);
    return 2;
}
