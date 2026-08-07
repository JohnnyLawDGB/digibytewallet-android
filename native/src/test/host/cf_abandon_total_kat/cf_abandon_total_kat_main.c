/* cf_abandon_total_kat — the abandoned-heights counter must survive a ledger re-Init.
 *
 * WHY THIS EXISTS. Three retention fixes were designed and all three were refuted, and the
 * reason none of them could be judged is that the only native "how much did we write off"
 * number is not measurable. It reports ZERO immediately after the largest abandonment event
 * in the system. Every device measurement taken through it was therefore suspect, including
 * ones already reasoned from.
 *
 * THE DEFECT, in three lines of production code:
 *
 *   BRPeerManagerAbandonedCount()          -> (abandonedBelow - start)      BRPeerManager.c
 *   BRCFScanLedgerInit(l, floor)           -> memset(l,0); l->start = floor  BRCFScanLedger.c
 *   the cfheaders floor snap               -> Init(&cfLedger, floor) THEN surface(.., floor)
 *
 * The snap re-Inits at `floor`, so start == floor; surfacing then sets abandonedBelow ==
 * floor. abandonedBelow > start is false, and the accessor returns 0 — right after abandoning
 * a band that can be tens of thousands of heights wide. The same wipe happens at the CF chain
 * re-anchor and the arming clamp. The existing code comment at the snap even notes that Init
 * resets scannedThrough and abandonedBelow, and captures `lowestBefore` to compensate; what it
 * misses is that `start` moves too, which is what destroys the count.
 *
 * The accessor also over-reports the rest of the time — the span below the watermark includes
 * heights that were legitimately scanned — which is why the UI already refuses to render it as
 * a count (abandonedBandMessage renders a RANGE; AbandonedBandBannerTextTest pins that).
 *
 * WHAT IS FIXED. Not that accessor: its span semantics are pinned by cf_scan_ledger_drive_kat
 * and no consumer is harmed, so changing it would only break a gate. Instead
 * BRPeerManagerAbandonedHeightsTotal accumulates, on the MANAGER, the count the ledger itself
 * reports as dropped — the one trustworthy figure — so it cannot be reached by the ledger
 * memset.
 *
 * ==== WHAT EACH ARM PROVES ==================================================
 *   FIXED:   the total accumulates across events AND survives a re-Init at a higher floor.
 *   UNFIXED (-DABANDON_TOTAL_UNFIXED): the accumulation is compiled out, the total stays 0,
 *            and this KAT's central assertion FAILS. That is the red arm.
 *
 * Case 1 below is deliberately an assertion about the OLD accessor being BROKEN. It is written
 * as a characterisation of current behaviour, not as a wish: if someone later fixes
 * BRPeerManagerAbandonedCount properly, case 1 fails loudly and tells them to update this file
 * rather than silently passing and hiding the change.
 *
 * DETERMINISTIC: no threads, no timing, no network. Drives the real static surfacing funnel.
 */

#include <stdio.h>
#include <string.h>
#include <inttypes.h>

/* Reach the static surfacing funnel and the manager's private fields, exactly as
 * cf_scan_ledger_drive_kat / bip341_signtx_kat / digidollar_send_kat do. BRPeerManager.c is
 * therefore NOT also passed to the compiler as a separate translation unit. */
#include "BRPeerManager.c"

static int g_fail = 0;

static void check(int cond, const char *what)
{
    printf("%s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

int main(void)
{
#ifdef ABANDON_TOTAL_UNFIXED
    printf("ARM: UNFIXED (-DABANDON_TOTAL_UNFIXED) — accumulation compiled out\n");
#else
    printf("ARM: FIXED\n");
#endif

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    /* Non-zero fingerprint: BRBIP32PubKey asserts the key is not BR_MASTER_PUBKEY_NONE, so an
     * all-zero mpk aborts in wallet setup — which would look like "died in setup", not a red
     * arm. That exact mistake cost a gate run on 2026-08-06. */
    mpk.fingerPrint = 0x11223344;
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("SKIP: wallet alloc failed\n"); return 0; }

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "setup: peer manager created");
    if (! m) { printf("SKIP: manager alloc failed\n"); return 1; }

    check(BRPeerManagerAbandonedHeightsTotal(m) == 0,
          "setup: a fresh manager has written off nothing");

    /* ---- EVENT 1 -----------------------------------------------------------
     * A band [22'761'486 .. 22'779'999] goes unscannable — the shape of the real
     * 18,514-height event in the 08-02 field log. Drive the production funnel, not a
     * hand-rolled equivalent, so the count under test is the one production computes. */
    BRCFScanLedgerInit(&m->cfLedger, 22761486);           /* start = 22761486 */
    m->cfLedger.scannedThrough = 22761485;
    _BRPeerManagerSurfaceUnscannableLocked(m, 22761486, 22780000, "KAT: event 1");

    const size_t EVENT1 = 22780000u - 22761486u;          /* 18,514 */
    check(BRPeerManagerAbandonedBelow(m) == 22780000,
          "event 1: watermark advanced to the new floor");
#ifndef ABANDON_TOTAL_UNFIXED
    check(BRPeerManagerAbandonedHeightsTotal(m) == EVENT1,
          "event 1: total == 18,514 heights actually dropped");
#endif
    /* The old accessor happens to be RIGHT here, because start has not moved yet. That is
     * what makes case 2 the interesting one. */
    check(BRPeerManagerAbandonedCount(m) == EVENT1,
          "event 1: the OLD span accessor agrees while start is still below the watermark");

    /* ---- EVENT 2: the floor snap — re-Init THEN surface ---------------------
     * Exactly the production order at the cfheaders floor snap: Init at the new floor,
     * then surface the skipped band. Init memsets the ledger and sets start = floor. */
    const uint32_t SNAP_FLOOR = 22800000u;
    BRCFScanLedgerInit(&m->cfLedger, SNAP_FLOOR);         /* start = abandonedBelow-to-be */
    _BRPeerManagerSurfaceUnscannableLocked(m, 22780000, SNAP_FLOOR, "KAT: cfheaders floor snap");

    const size_t EVENT2 = SNAP_FLOOR - 22780000u;         /* 20,000 */

    /* CASE 1 — characterise the DEFECT in the old accessor. This assertion documents broken
     * behaviour on purpose; see the file header. */
    check(BRPeerManagerAbandonedBelow(m) == SNAP_FLOOR,
          "event 2: watermark did advance — the abandonment really happened");
    check(BRPeerManagerAbandonedCount(m) == 0,
          "event 2 DEFECT (characterised, not endorsed): the OLD span accessor reads ZERO right "
          "after a 20,000-height abandonment, because Init moved start up to the same floor");

    /* CASE 2 — the fix. The total must survive the memset AND include both events. */
#ifndef ABANDON_TOTAL_UNFIXED
    check(BRPeerManagerAbandonedHeightsTotal(m) == EVENT1 + EVENT2,
          "event 2 FIX: the total SURVIVES the ledger re-Init and accumulates (18,514 + 20,000 "
          "= 38,514)");
#else
    /* The red arm asserts the same target and is expected to miss it, so the gate fails for
     * the RIGHT reason rather than on an exit code. */
    check(BRPeerManagerAbandonedHeightsTotal(m) == EVENT1 + EVENT2,
          "event 2 FIX: the total SURVIVES the ledger re-Init and accumulates (18,514 + 20,000 "
          "= 38,514)");
#endif

    /* ---- EVENT 3: a no-op surfacing must not inflate the total --------------
     * cnt == 0 means nothing was dropped, so nothing may be counted. Without this, a fix that
     * incremented per CALL rather than per DROPPED HEIGHT would still pass everything above. */
    const size_t beforeNoop = BRPeerManagerAbandonedHeightsTotal(m);
    _BRPeerManagerSurfaceUnscannableLocked(m, SNAP_FLOOR, SNAP_FLOOR, "KAT: no-op");
    check(BRPeerManagerAbandonedHeightsTotal(m) == beforeNoop,
          "event 3: a surfacing that drops NOTHING leaves the total unchanged (counts heights, "
          "not calls)");

    /* ---- A NEW MANAGER STARTS AT ZERO ---------------------------------------
     * The counter must reset for a genuinely fresh wallet/wipe/rescan — that is the whole
     * reason it lives on the manager rather than in a global. */
    BRPeerManager *m2 = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (m2) {
        check(BRPeerManagerAbandonedHeightsTotal(m2) == 0,
              "a NEW manager (fresh wallet / wipe / rescan) starts the total at zero");
        BRPeerManagerFree(m2);
    }

    BRPeerManagerFree(m);
    BRWalletFree(wallet);

    printf("\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
