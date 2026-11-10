// Host KAT: BRReconcilePlan.h -- the planning rules of a node reconcile, moved from Android's Kotlin
// (ChainReconciliationService.kt, ScanPartition.kt) into the core.
//
// THE CONTRACT (the header's THE RULES):
//   KNOWN SET   every transaction the wallet holds, in one lock hold, sorted; a buffer that cannot be
//               shown to hold them all is INCOMPLETE, never read as the whole set. No truncated variant.
//   PLAN        drop known txids (and all-zero ones), one entry per txid at its HIGHEST height,
//               first-seen order.
//   STEP        fetched -> decoded -> is the transaction asked for -> registered; the first failure is
//               the outcome, and bytes that are not the transaction are never registered.
//   COVERAGE    covered iff the node answered for every address asked (and some were asked), nothing
//               unrecovered and every planned entry registered; a refused registration is unrecovered.
//   PENDING     blockHeight == TX_UNCONFIRMED.
//   DISTINCT    addresses trimmed, non-empty, once, first-seen; outpoints once, first row kept.
//
// The cases carry Android's AddressHistoryPlanTest and HistoryImportCoverageTest case for case (named
// after them), with the Kotlin's string inputs replaced by what the header reads: a real BRWallet for
// the known set, UInt256 txids, and the import step each Kotlin stub produces. The expectations are
// written out by hand here, independently of vectors/reconcile-plan/ (run.sh checks that file is current).
//
// MACRO CONVENTION: BRReconcilePlan.h tests the VALUE of RECONCILE_PLAN_UNFIXED (#if). run.sh builds the
// comparison arm with -DRECONCILE_PLAN_UNFIXED=1 (the known set truncated to the newest 100, a repeated
// txid at its lowest height, a refused registration read as recovered) and the shipped arm with =0.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>
#include <time.h>

#include "BRReconcilePlan.h"
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_failed = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_failed = 1;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84;
static const uint8_t kPlaceholder[1] = {0};

#define U TX_UNCONFIRMED
#define H 23900121

// A txid from a tag byte and a serial (what the Kotlin tests spell "wallet-tx-001" and so on).
static UInt256 tid(uint8_t tag, uint16_t serial)
{
    UInt256 h;
    memset(h.u8, tag, sizeof(h.u8));
    h.u8[0] = (uint8_t)(serial >> 8);
    h.u8[1] = (uint8_t)serial;
    return h;
}

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A transaction paying `sats` to `addr` from a foreign coin named by (tag, serial), at height.
static BRTransaction *coinTx(const char *addr, uint64_t sats, uint8_t tag, uint16_t serial, uint32_t height)
{
    uint8_t spk[64], foreign[22] = { 0x00, 0x14 };
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *c = BRTransactionNew();
    memset(foreign + 2, 0x33, 20);
    c->version = 1;
    BRTransactionAddInput(c, tid(tag, serial), 0, 0, foreign, sizeof(foreign), kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(c, sats, spk, spkLen);
    c->blockHeight = height;
    c->timestamp = (height == U) ? 0 : 1700000000u;
    finalizeTxHash(c);
    return c;
}

// A wallet holding n confirmed transactions (oldest first), their hashes written to hashes.
static BRWallet *wideWallet(size_t n, UInt256 *hashes)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    for (size_t i = 0; i < n; i++) {
        BRTransaction *t = coinTx(a.s, 1000000 + i, 0x77, (uint16_t)(i + 1), 1000 + (uint32_t)i);
        if (hashes) hashes[i] = t->txHash;
        if (!BRWalletRegisterTransaction(w, t)) BRTransactionFree(t);
    }
    return w;
}

// The known set of w, read as a platform must: size, then read with room to spare.
static UInt256 *knownOf(BRWallet *w, size_t *n)
{
    size_t count = 0;
    BRReconcileStatus s = BRReconcileKnownTxHashes(w, NULL, 0, &count);
    size_t cap = BRReconcileCapacity(count);
    UInt256 *k = calloc(cap ? cap : 1, sizeof(*k));
    *n = 0;
    if (s != BR_RECONCILE_INCOMPLETE || !k) { free(k); return NULL; }
    if (BRReconcileKnownTxHashes(w, k, cap, n) != BR_RECONCILE_OK) { free(k); *n = 0; return NULL; }
    return k;
}

static int contains(const UInt256 *set, size_t n, UInt256 h)
{
    for (size_t i = 0; i < n; i++) if (UInt256Eq(set[i], h)) return 1;
    return 0;
}

static BRReconcileImportOutcome tally(size_t planned, const BRReconcileImportStep *s, size_t n)
{
    BRReconcileImportOutcome o;
    BRReconcileImportBegin(&o, 3, 3, planned);   // the node answered for every address asked
    for (size_t i = 0; i < n; i++) BRReconcileImportRecord(&o, s[i]);
    return o;
}

static const BRReconcileImportStep R = BR_RECONCILE_STEP_REGISTERED, RF = BR_RECONCILE_STEP_REFUSED;

// ======================= AddressHistoryPlanTest ===============================================

// extracts_known_txids_from_details: a confirmed and a pending transaction are both known. Here the
// set comes from the wallet itself, not from a details string.
static void extracts_known_txids_from_details(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRTransaction *ta = coinTx(a.s, 100, 0xaa, 0, 500), *tb = coinTx(a.s, 200, 0xbb, 0, U);
    UInt256 ha = ta->txHash, hb = tb->txHash;
    ck(BRWalletRegisterTransaction(w, ta) && BRWalletRegisterTransaction(w, tb), "the wallet holds aaa (h 500) and bbb (pending)");
    size_t n = 0;
    UInt256 *k = knownOf(w, &n);
    ck(k && n == 2 && contains(k, n, ha) && contains(k, n, hb), "known = {aaa, bbb} (n=%zu)", n);
    ck(k && BRReconcileTxHashesSorted(k, n), "the known set is sorted");
    free(k);
    BRWalletFree(w);
}

// blank_lines_ignored: an empty wallet's known set is empty, and complete.
static void blank_lines_ignored(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    size_t n = 99;
    UInt256 one[1];
    ck(BRReconcileKnownTxHashes(w, NULL, 0, &n) == BR_RECONCILE_INCOMPLETE && n == 0, "a sizing call reports 0 (n=%zu)", n);
    ck(BRReconcileKnownTxHashes(w, one, 1, &n) == BR_RECONCILE_OK && n == 0, "read with room to spare: complete and empty");
    BRWalletFree(w);
}

// plan_drops_known_and_dedups_across_addresses
static void plan_drops_known_and_dedups_across_addresses(void)
{
    UInt256 known = tid(0x4b, 0), n1 = tid(0x31, 0), n2 = tid(0x32, 0);
    BRReconcileHistoryTx h[] = { {known, 10}, {n1, 20}, /* address b */ {n1, 20}, {n2, 30} };
    BRReconcilePlanTx p[4];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 4, &known, 1, p, 4, &n) == BR_RECONCILE_OK, "planned");
    ck(n == 2 && UInt256Eq(p[0].txHash, n1) && UInt256Eq(p[1].txHash, n2), "plan = [new1, new2] (n=%zu)", n);
    ck(n == 2 && p[0].height == 20 && p[1].height == 30 && p[0].firstIndex == 1 && p[1].firstIndex == 3,
       "heights 20, 30; first seen at 1, 3");
}

// plan_keeps_highest_height_for_duplicate_txid (and the highest anywhere in the list, not the last)
static void plan_keeps_highest_height_for_duplicate_txid(void)
{
    UInt256 t = tid(0x54, 0);
    BRReconcileHistoryTx h[] = { {t, 0}, {t, 99} };
    BRReconcilePlanTx p[3];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 2, NULL, 0, p, 2, &n) == BR_RECONCILE_OK && n == 1, "one entry (n=%zu)", n);
    ck(n == 1 && p[0].height == 99, "height 99 (got %" PRId64 ")", n ? p[0].height : -1);
    BRReconcileHistoryTx h2[] = { {t, 50}, {t, 99}, {t, 7} };
    ck(BRReconcilePlanHistoryImport(h2, 3, NULL, 0, p, 3, &n) == BR_RECONCILE_OK && n == 1 && p[0].height == 99,
       "50, 99, 7 -> 99 (got %" PRId64 ")", n ? p[0].height : -1);
}

// empty_history_yields_empty_plan
static void empty_history_yields_empty_plan(void)
{
    UInt256 x = tid(0x58, 0);
    size_t n = 7;
    ck(BRReconcilePlanHistoryImport(NULL, 0, &x, 1, NULL, 0, &n) == BR_RECONCILE_OK && n == 0, "empty plan");
}

// ======================= HistoryImportCoverageTest ============================================

static void every_planned_tx_registered_is_covered(void)
{
    BRReconcileImportStep s[] = { R, R, R };
    BRReconcileImportOutcome o = tally(3, s, 3);
    ck(o.imported == 3 && o.unrecovered == 0 && BRReconcileImportCovered(&o), "3 imported, 0 unrecovered, covered");
}

static void nothing_planned_is_covered(void)
{
    BRReconcileImportOutcome o = tally(0, NULL, 0);
    ck(BRReconcileImportCovered(&o) && o.imported == 0, "an empty plan is covered");
}

static void register_rejection_is_not_coverage(void)
{
    BRReconcileImportStep s[] = { BRReconcileImportStepFor(1, 1, 1, 0) };
    BRReconcileImportOutcome o = tally(1, s, 1);
    ck(s[0] == RF, "a refused registration is REFUSED");
    ck(o.imported == 0 && o.unrecovered == 1, "0 imported, 1 unrecovered (got %zu, %zu)", o.imported, o.unrecovered);
    ck(!BRReconcileImportCovered(&o), "a transaction the wallet refused is still missing: not covered");
}

static void one_rejection_among_many_successes_sinks_coverage(void)
{
    BRReconcileImportStep s[] = { R, RF, R };
    BRReconcileImportOutcome o = tally(3, s, 3);
    ck(o.imported == 2 && o.unrecovered == 1 && !BRReconcileImportCovered(&o),
       "2 imported, 1 unrecovered, not covered (got %zu, %zu)", o.imported, o.unrecovered);
}

static void fetch_failure_is_not_coverage(void)
{
    BRReconcileImportStep s[] = { BRReconcileImportStepFor(0, 1, 1, 1) };
    BRReconcileImportOutcome o = tally(1, s, 1);
    ck(s[0] == BR_RECONCILE_STEP_NOT_FETCHED && o.unrecovered == 1 && !BRReconcileImportCovered(&o), "not fetched: not covered");
}

static void hex_decode_failure_is_not_coverage(void)
{
    BRReconcileImportStep s[] = { BRReconcileImportStepFor(1, 0, 1, 1) };
    BRReconcileImportOutcome o = tally(1, s, 1);
    ck(s[0] == BR_RECONCILE_STEP_NOT_DECODED && o.unrecovered == 1 && !BRReconcileImportCovered(&o), "not decoded: not covered");
}

static void all_three_failure_modes_counted_together(void)
{
    BRReconcileImportStep s[] = { BRReconcileImportStepFor(0, 0, 0, 0), BRReconcileImportStepFor(1, 0, 1, 1),
                                  BRReconcileImportStepFor(1, 1, 1, 0), BRReconcileImportStepFor(1, 1, 1, 1) };
    BRReconcileImportOutcome o = tally(4, s, 4);
    ck(o.imported == 1 && o.unrecovered == 3 && !BRReconcileImportCovered(&o),
       "1 imported, 3 unrecovered, not covered (got %zu, %zu)", o.imported, o.unrecovered);
}

// knownSetIsUncapped_soTruncatedOutOlderTxidsNeverEnterThePlan: a 150-transaction wallet.
static void known_set_is_uncapped(void)
{
    UInt256 wtx[150];
    BRWallet *w = wideWallet(150, wtx);
    size_t n = 0, i, missingFromKnown = 0, pn = 0;
    UInt256 *k = knownOf(w, &n);
    ck(BRWalletTransactions(w, NULL, 0) == 150, "the wallet holds 150 transactions");
    for (i = 0; k && i < 150; i++) if (!BRReconcileKnownContains(k, n, wtx[i])) missingFromKnown++;
    ck(k && n == 150 && missingFromKnown == 0, "every wallet transaction is in the known set (n=%zu, %zu absent)", n, missingFromKnown);

    BRReconcileHistoryTx h[151];
    BRReconcilePlanTx p[151];
    UInt256 missing = tid(0x6d, 0);
    for (i = 0; i < 150; i++) { h[i].txHash = wtx[i]; h[i].height = H; }
    h[150].txHash = missing; h[150].height = H;
    ck(BRReconcilePlanHistoryImport(h, 151, k, n, p, 151, &pn) == BR_RECONCILE_OK, "planned");
    ck(pn == 1 && UInt256Eq(p[0].txHash, missing), "plan = [missing-from-the-band] (n=%zu)", pn);
    free(k);
    BRWalletFree(w);
}

// wideWallet_reconcileCanStillAchieveCoverage: end to end through the plan, with the wallet's own
// answer to a registration (a duplicate is refused), on a 150-transaction wallet.
static void wide_wallet_can_still_achieve_coverage(void)
{
    UInt256 wtx[150];
    BRWallet *w = wideWallet(150, wtx);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRTransaction *missingTx = coinTx(a.s, 4242, 0x6d, 0, U);
    size_t n = 0, pn = 0, i;
    UInt256 *k = knownOf(w, &n);
    BRReconcileHistoryTx h[151];
    BRReconcilePlanTx p[151];
    BRReconcileImportOutcome o;

    for (i = 0; i < 150; i++) { h[i].txHash = wtx[i]; h[i].height = H; }
    h[150].txHash = missingTx->txHash; h[150].height = H;
    ck(k && BRReconcilePlanHistoryImport(h, 151, k, n, p, 151, &pn) == BR_RECONCILE_OK, "planned (%zu entries)", pn);
    BRReconcileImportBegin(&o, 1, 1, pn);
    for (i = 0; i < pn; i++) {
        int registered;
        if (BRWalletTransactionForHash(w, p[i].txHash)) registered = 0;   // the wallet's duplicate refusal
        else if (UInt256Eq(p[i].txHash, missingTx->txHash)) {
            missingTx->blockHeight = (uint32_t)p[i].height;
            missingTx->timestamp = 1700000000u;
            registered = BRWalletRegisterTransaction(w, missingTx);
            if (registered) missingTx = NULL;
        }
        else registered = 0;
        BRReconcileImportRecord(&o, BRReconcileImportStepFor(1, 1, 1, registered));
    }
    ck(o.imported == 1 && o.unrecovered == 0, "1 imported, 0 unrecovered (got %zu, %zu)", o.imported, o.unrecovered);
    ck(BRReconcileImportCovered(&o), "a 150-transaction wallet can be covered");
    if (missingTx) BRTransactionFree(missingTx);
    free(k);
    BRWalletFree(w);
}

// nullHashArray_fallsBackToTheDetailsDerivedSet: there is no fallback set here. No wallet: no set,
// reported, never an empty one read as complete.
static void null_wallet_has_no_known_set(void)
{
    UInt256 buf[4];
    size_t n = 9;
    ck(BRReconcileKnownTxHashes(NULL, buf, 4, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL wallet: NULL_ARGUMENT, count 0");
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    ck(BRReconcileKnownTxHashes(w, buf, 4, NULL) == BR_RECONCILE_NULL_ARGUMENT, "NULL count: NULL_ARGUMENT");
    BRWalletFree(w);
}

// blankHashEntriesAreIgnored / nullHashElementsAreSkipped_notThrown: an all-zero txid is no txid; a
// NULL set with a count is refused, not read.
static void zero_and_null_txids_are_ignored(void)
{
    UInt256 a = tid(0xa1, 0), z = UINT256_ZERO;
    BRReconcileHistoryTx h[] = { {z, 5}, {a, 1}, {z, 7} };
    BRReconcilePlanTx p[3];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 3, NULL, 0, p, 3, &n) == BR_RECONCILE_OK && n == 1 && UInt256Eq(p[0].txHash, a),
       "zero txids dropped (n=%zu)", n);
    ck(BRReconcilePlanHistoryImport(h, 3, NULL, 2, p, 3, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL known with a count refused");
    ck(BRReconcilePlanHistoryImport(NULL, 3, NULL, 0, p, 3, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL history with a count refused");
    ck(!BRReconcileKnownContains(NULL, 5, a), "a NULL set holds nothing");
}

// allNullHashArray_degradesToTheDetailsSet and the capped-fallback group (nullArrayWithTransactionsPresent,
// nullArrayWithNoTransactions, presentArray_isNeverTheFallbackCase, fallbackStillProducesTheCappedKnownSet):
// a buffer that cannot hold every transaction is INCOMPLETE, never a truncated set; only a read with room
// to spare is the set.
static void truncated_read_is_incomplete_never_capped(void)
{
    UInt256 wtx[5], buf[8];
    BRWallet *w = wideWallet(5, wtx);
    size_t n = 0;
    ck(BRReconcileKnownTxHashes(w, buf, 3, &n) == BR_RECONCILE_INCOMPLETE && n == 5, "cap 3 of 5: INCOMPLETE, count 5 (n=%zu)", n);
    ck(BRReconcileKnownTxHashes(w, buf, 5, &n) == BR_RECONCILE_INCOMPLETE && n == 5, "cap 5 of 5 (full): INCOMPLETE");
    ck(BRReconcileKnownTxHashes(w, buf, BRReconcileCapacity(5), &n) == BR_RECONCILE_OK && n == 5, "cap 6: OK, 5");
    BRWalletFree(w);
    BRWallet *e = BRWalletNew(NULL, 0, g_mpk84);
    ck(BRReconcileKnownTxHashes(e, NULL, 0, &n) == BR_RECONCILE_INCOMPLETE && n == 0, "an empty wallet sizes to 0");
    BRWalletFree(e);
}

// A 300-transaction wallet: the set is every one of them (the truncated arm keeps the newest 100).
static void long_history_known_set_complete(void)
{
    UInt256 *wtx = calloc(300, sizeof(*wtx));
    BRWallet *w = wideWallet(300, wtx);
    size_t n = 0, absent = 0;
    UInt256 *k = knownOf(w, &n);
    for (size_t i = 0; k && i < 300; i++) if (!BRReconcileKnownContains(k, n, wtx[i])) absent++;
    ck(k && n == 300 && absent == 0, "all 300 known (n=%zu, %zu absent, the oldest %s)", n, absent,
       (k && BRReconcileKnownContains(k, n, wtx[0])) ? "present" : "absent");
    free(k); free(wtx);
    BRWalletFree(w);
}

// registersWithTheNodesConfirmingHeight: the height planned is the one registered.
static void registers_with_the_nodes_confirming_height(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRTransaction *t = coinTx(a.s, 5000, 0xa1, 0, U);
    UInt256 th = t->txHash;
    BRReconcileHistoryTx h[] = { {th, 23900123} };
    BRReconcilePlanTx p[1];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 1, NULL, 0, p, 1, &n) == BR_RECONCILE_OK && n == 1 && p[0].height == 23900123, "planned at 23900123");
    t->blockHeight = (uint32_t)p[0].height;
    t->timestamp = 1712000000u;
    int ok = BRWalletRegisterTransaction(w, t);
    if (!ok) BRTransactionFree(t);
    BRTransaction *c = BRWalletCopyTransactionForHash(w, th);
    ck(ok && c && c->blockHeight == 23900123 && c->timestamp == 1712000000u, "registered at 23900123 / 1712000000");
    if (c) BRTransactionFree(c);
    BRWalletFree(w);
}

// bytesOfAnotherTransaction_areNotRegistered_andNotCovered
static void bytes_of_another_transaction_not_registered(void)
{
    ck(!BRReconcileImportMayRegister(1, 1, 0), "bytes that are not the transaction may not be registered");
    ck(BRReconcileImportMayRegister(1, 1, 1), "the transaction asked for may be");
    BRReconcileImportStep s[] = { BRReconcileImportStepFor(1, 1, 0, 1), BRReconcileImportStepFor(1, 1, 1, 1) };
    BRReconcileImportOutcome o = tally(2, s, 2);
    ck(s[0] == BR_RECONCILE_STEP_NOT_THE_TX, "NOT_THE_TX whatever a registration would have said");
    ck(o.imported == 1 && o.unrecovered == 1 && !BRReconcileImportCovered(&o), "1 imported, 1 unrecovered, not covered");
}

// theCheckIsGivenTheRequestedTxid: each planned entry carries the txid it was planned for, in order.
static void the_check_is_given_the_requested_txid(void)
{
    UInt256 a = tid(0xa1, 0), b = tid(0xb2, 0);
    BRReconcileHistoryTx h[] = { {a, 1}, {b, 1} };
    BRReconcilePlanTx p[2];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 2, NULL, 0, p, 2, &n) == BR_RECONCILE_OK && n == 2 &&
       UInt256Eq(p[0].txHash, a) && UInt256Eq(p[1].txHash, b), "plan = [a, b]");
}

// ======================= beyond the Kotlin tests ==============================================

static void first_seen_order(void)
{
    UInt256 a = tid(0xa1, 0), b = tid(0xb2, 0), c = tid(0xc3, 0);
    BRReconcileHistoryTx h[] = { {b, 1}, {a, 2}, {b, 3}, {c, 0}, {a, 1} };
    BRReconcilePlanTx p[5];
    size_t n = 0;
    ck(BRReconcilePlanHistoryImport(h, 5, NULL, 0, p, 5, &n) == BR_RECONCILE_OK && n == 3, "three txids");
    ck(n == 3 && UInt256Eq(p[0].txHash, b) && UInt256Eq(p[1].txHash, a) && UInt256Eq(p[2].txHash, c), "b, a, c");
    ck(n == 3 && p[0].height == 3 && p[1].height == 2 && p[2].height == 0, "at 3, 2, 0");
}

static void plan_buffer_and_known_order(void)
{
    UInt256 a = tid(0xa1, 0), b = tid(0xb2, 0), k[2] = { b, a };
    BRReconcileHistoryTx h[] = { {a, 1}, {a, 2}, {b, 3} };
    BRReconcilePlanTx p[3];
    size_t n = 5;
    ck(BRReconcilePlanHistoryImport(h, 3, k, 2, p, 3, &n) == BR_RECONCILE_KNOWN_UNSORTED && n == 0, "an unsorted known set is refused");
    ck(BRReconcilePlanHistoryImport(h, 3, NULL, 0, p, 2, &n) == BR_RECONCILE_BUFFER_TOO_SMALL && n == 0, "3 survivors, cap 2: too small");
    ck(BRReconcilePlanHistoryImport(h, 3, NULL, 0, p, 3, &n) == BR_RECONCILE_OK && n == 2, "cap = history count suffices");
    BRReconcileSortTxHashes(k, 2);
    ck(BRReconcilePlanHistoryImport(h, 3, k, 2, NULL, 0, &n) == BR_RECONCILE_OK && n == 0, "everything known: no buffer needed");
    ck(BRReconcilePlanHistoryImport(h, 3, NULL, 0, p, 3, NULL) == BR_RECONCILE_NULL_ARGUMENT, "NULL planCount refused");
}

static void coverage_counts_the_plan(void)
{
    BRReconcileImportStep s[] = { R, R };
    BRReconcileImportOutcome o = tally(2, s, 1);
    ck(!BRReconcileImportCovered(&o), "a pass that stopped short of its plan is not covered");
    o = tally(1, s, 2);
    ck(!BRReconcileImportCovered(&o), "more recorded than planned is not covered");
    BRReconcileImportStep bad[] = { (BRReconcileImportStep)99 };
    o = tally(1, bad, 1);
    ck(o.unrecovered == 1 && !BRReconcileImportCovered(&o), "an unknown step value is not recovered");
    ck(!BRReconcileImportCovered(NULL), "NULL is not covered");
    BRReconcileImportBegin(NULL, 1, 1, 3);
    BRReconcileImportRecord(NULL, R);
    o.planned = SIZE_MAX; o.imported = SIZE_MAX; o.unrecovered = 0;
    BRReconcileImportRecord(&o, R);
    ck(o.imported == SIZE_MAX, "counts saturate");
}

// reconcileAddressHistory's early returns: no addresses, or a history request that failed, is never
// covered, though its plan is empty.
static void coverage_needs_every_address_answered(void)
{
    BRReconcileImportOutcome o;
    BRReconcileImportBegin(&o, 0, 0, 0);
    ck(!BRReconcileImportCovered(&o), "no addresses asked: not covered");
    BRReconcileImportBegin(&o, 5, 0, 0);
    ck(!BRReconcileImportCovered(&o), "the history request failed (0 of 5 answered), empty plan: not covered");
    BRReconcileImportBegin(&o, 600, 500, 1);
    BRReconcileImportRecord(&o, R);
    ck(!BRReconcileImportCovered(&o), "one request of two failed (500 of 600 answered): not covered");
    BRReconcileImportBegin(&o, 2, 3, 1);
    BRReconcileImportRecord(&o, R);
    ck(!BRReconcileImportCovered(&o), "more answered than asked: not covered");
    BRReconcileImportBegin(&o, 600, 600, 0);
    ck(BRReconcileImportCovered(&o), "every address answered, nothing missing: covered");
}

// One txid repeated dozens of times among others: the plan's order is where each txid was FIRST seen,
// which only the index tie-break in the plan's txid sort keeps (the sort is not stable).
static void plan_first_seen_with_many_repeats(void)
{
    BRReconcileHistoryTx h[200];
    BRReconcilePlanTx p[200];
    size_t n = 0, i, k = 0, bad = 0;
    UInt256 t = tid(0x54, 0);
    // 40 others, each once, and t at positions 3, 7, 11, ... (every fourth from 3): 3 + 4 * 39 = 159.
    for (i = 0; i < 160; i++) {
        if (i % 4 == 3) { h[i].txHash = t; h[i].height = 500; }
        else { h[i].txHash = tid((uint8_t)(i % 7 + 1), (uint16_t)(i + 1)); h[i].height = (int64_t)i; }
    }
    ck(BRReconcilePlanHistoryImport(h, 160, NULL, 0, p, 200, &n) == BR_RECONCILE_OK && n == 121, "121 txids (n=%zu)", n);
    for (i = 0; i < 160 && k < n; i++) {   // the plan is the history with every repeat of t after its first dropped
        if (UInt256Eq(h[i].txHash, t) && i != 3) continue;
        if (!UInt256Eq(p[k].txHash, h[i].txHash) || p[k].firstIndex != i) bad++;
        k++;
    }
    ck(n == 121 && k == 121 && bad == 0, "every txid where it was first seen, t at position 3 (%zu out of place)", bad);
    ck(n > 3 && UInt256Eq(p[3].txHash, t) && p[3].firstIndex == 3, "t is fourth, first seen at 3");
}

static double cpuSeconds(clock_t since) { return (double)(clock() - since) / CLOCKS_PER_SEC; }

// Inputs the node chooses the size of, at 100000 rows: each list in well under the bound (O(n log n)).
// The bound is CPU seconds under ASan + UBSan; the quadratic version took over 4 s for the outpoints alone.
#define LARGE_N 100000u
#define LARGE_BOUND_S 2.0
static void large_inputs_are_n_log_n(void)
{
    BRUTXO *u = calloc(LARGE_N, sizeof(*u));
    size_t *keep = calloc(LARGE_N, sizeof(*keep));
    char (*store)[24] = calloc(LARGE_N, sizeof(*store));
    const char **a = calloc(LARGE_N, sizeof(*a));
    BRReconcileAddressSpan *spans = calloc(LARGE_N, sizeof(*spans));
    BRReconcileHistoryTx *h = calloc(LARGE_N, sizeof(*h));
    BRReconcilePlanTx *p = calloc(LARGE_N, sizeof(*p));
    size_t n = 0, i, bad = 0;
    clock_t t0;
    double s;

    ck(u && keep && store && a && spans && h && p, "buffers allocated");
    if (!(u && keep && store && a && spans && h && p)) goto done;
    // Outpoints: 50000 distinct (txid serial i / 2, output i % 2), each row twice: rows i and i + 50000.
    for (i = 0; i < LARGE_N; i++) {
        size_t j = i % (LARGE_N / 2);
        u[i].hash = tid(0x61, (uint16_t)(j / 2));
        u[i].hash.u8[2] = (uint8_t)(j >> 16);
        u[i].n = (uint32_t)(j % 2);
    }
    t0 = clock();
    BRReconcileStatus st = BRReconcileDistinctOutpoints(u, LARGE_N, keep, LARGE_N, &n);
    s = cpuSeconds(t0);
    for (i = 0; st == BR_RECONCILE_OK && i < n; i++) if (keep[i] != i) bad++;
    ck(st == BR_RECONCILE_OK && n == LARGE_N / 2 && bad == 0, "outpoints: the first 50000 rows kept, in order (n=%zu)", n);
    ck(s < LARGE_BOUND_S, "outpoints: %.3f s < %.1f s", s, LARGE_BOUND_S);

    // Addresses: 50000 distinct, each twice (the repeat padded with whitespace).
    for (i = 0; i < LARGE_N; i++) {
        size_t j = i % (LARGE_N / 2);
        snprintf(store[i], sizeof(store[i]), i < LARGE_N / 2 ? "dgb1q%06zu" : " dgb1q%06zu\t", j);
        a[i] = store[i];
    }
    t0 = clock();
    st = BRReconcileDistinctAddresses(a, LARGE_N, spans, LARGE_N, &n);
    s = cpuSeconds(t0);
    for (i = 0, bad = 0; st == BR_RECONCILE_OK && i < n; i++) if (spans[i].index != i || spans[i].offset != 0) bad++;
    ck(st == BR_RECONCILE_OK && n == LARGE_N / 2 && bad == 0, "addresses: the first 50000 kept, in order (n=%zu)", n);
    ck(s < LARGE_BOUND_S, "addresses: %.3f s < %.1f s", s, LARGE_BOUND_S);

    // History: 50000 txids, each twice (at one height: this case times the plan, the height rule has its own).
    for (i = 0; i < LARGE_N; i++) {
        size_t j = i % (LARGE_N / 2);
        h[i].txHash = tid(0x62, (uint16_t)j);
        h[i].txHash.u8[2] = (uint8_t)(j >> 16);
        h[i].height = 20;
    }
    t0 = clock();
    st = BRReconcilePlanHistoryImport(h, LARGE_N, NULL, 0, p, LARGE_N, &n);
    s = cpuSeconds(t0);
    for (i = 0, bad = 0; st == BR_RECONCILE_OK && i < n; i++) if (p[i].firstIndex != i || p[i].height != 20) bad++;
    ck(st == BR_RECONCILE_OK && n == LARGE_N / 2 && bad == 0, "history: 50000 planned, in first-seen order (n=%zu)", n);
    ck(s < LARGE_BOUND_S, "history: %.3f s < %.1f s", s, LARGE_BOUND_S);
done:
    free(u); free(keep); free(store); free(a); free(spans); free(h); free(p);
}

static void pending_rule(void)
{
    ck(BRReconcileIsPending(TX_UNCONFIRMED) && BRReconcileIsPending(2147483647u), "TX_UNCONFIRMED is pending");
    ck(!BRReconcileIsPending(0) && !BRReconcileIsPending(2147483646u) && !BRReconcileIsPending(2147483648u) &&
       !BRReconcileIsPending(UINT32_MAX), "nothing else is");
}

// The wallet's pending transactions: every one, oldest first, from a wallet longer than 100.
static void pending_txids_from_wallet(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    UInt256 p0 = UINT256_ZERO, p1 = UINT256_ZERO;
    for (uint16_t i = 0; i < 150; i++) {
        uint32_t height = (i == 3 || i == 140) ? U : 1000u + i;
        BRTransaction *t = coinTx(a.s, 1000 + i, 0x55, i, height);
        if (i == 3) p0 = t->txHash;
        if (i == 140) p1 = t->txHash;
        if (!BRWalletRegisterTransaction(w, t)) BRTransactionFree(t);
    }
    size_t n = 0;
    ck(BRReconcilePendingTxHashes(w, NULL, 0, &n) == BR_RECONCILE_INCOMPLETE && n == 150, "sizing: 150 transactions");
    UInt256 *buf = calloc(BRReconcileCapacity(n), sizeof(*buf));
    ck(buf && BRReconcilePendingTxHashes(w, buf, BRReconcileCapacity(n), &n) == BR_RECONCILE_OK, "read");
    ck(buf && n == 2, "two pending (n=%zu)", n);
    ck(buf && n == 2 && UInt256Eq(buf[0], p0) && UInt256Eq(buf[1], p1), "both, the old one included, oldest first");
    ck(BRReconcilePendingTxHashes(NULL, buf, 4, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL wallet refused");
    free(buf);
    BRWalletFree(w);
}

static void distinct_outpoints(void)
{
    UInt256 a = tid(0xa1, 0), b = tid(0xb2, 0);
    BRUTXO u[] = { {a, 0}, {a, 0}, {b, 1}, {a, 1}, {b, 1} };
    size_t keep[5], n = 0;
    ck(BRReconcileDistinctOutpoints(u, 5, keep, 5, &n) == BR_RECONCILE_OK && n == 3 && keep[0] == 0 && keep[1] == 2 && keep[2] == 3,
       "rows 0, 2, 3 kept (n=%zu)", n);
    ck(BRReconcileDistinctOutpoints(u, 5, keep, 2, &n) == BR_RECONCILE_BUFFER_TOO_SMALL && n == 0, "cap 2 of 3: too small");
    ck(BRReconcileDistinctOutpoints(NULL, 2, keep, 5, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL rows with a count refused");
    ck(BRReconcileDistinctOutpoints(NULL, 0, NULL, 0, &n) == BR_RECONCILE_OK && n == 0, "nothing in, nothing out");
}

static void distinct_addresses(void)
{
    const char *a[] = { "  dgb1qaaa", NULL, "dgb1qaaa\r", "", " \t ", "\x1c" "dgb1qbbb" "\x1f", "dgb1qbbb", "dgb1q aaa", "DGB1QAAA" };
    BRReconcileAddressSpan out[9];
    size_t n = 0;
    ck(BRReconcileDistinctAddresses(a, 9, out, 9, &n) == BR_RECONCILE_OK && n == 4, "four addresses (n=%zu)", n);
    ck(n == 4 && out[0].index == 0 && out[0].offset == 2 && out[0].length == 8, "the first, trimmed: \"dgb1qaaa\"");
    ck(n == 4 && out[1].index == 5 && out[1].offset == 1 && out[1].length == 8, "separators trimmed: \"dgb1qbbb\"");
    ck(n == 4 && out[2].index == 7 && out[3].index == 8, "an interior space and a case difference are other addresses");
    ck(BRReconcileDistinctAddresses(a, 9, out, 3, &n) == BR_RECONCILE_BUFFER_TOO_SMALL && n == 0, "cap 3 of 4: too small");
    ck(BRReconcileDistinctAddresses(NULL, 1, out, 9, &n) == BR_RECONCILE_NULL_ARGUMENT && n == 0, "NULL list refused");
    size_t off = 9, len = 9;
    BRReconcileAddressTrim(NULL, &off, &len);
    ck(off == 0 && len == 0, "trim of NULL is empty");
}

static void sizes_overflow_checked(void)
{
    ck(BRReconcileCapacity(0) == 1 && BRReconcileCapacity(7) == 8 && BRReconcileCapacity(SIZE_MAX) == 0, "capacity");
    ck(BRReconcileArrayBytes(3, sizeof(UInt256)) == 96 && BRReconcileArrayBytes(SIZE_MAX / 2, sizeof(UInt256)) == 0 &&
       BRReconcileArrayBytes(0, 32) == 0, "bytes, 0 on overflow");
    UInt256 x = tid(1, 0), y = tid(2, 0);
    ck(BRReconcileTxHashCompare(x, y) < 0 && BRReconcileTxHashCompare(y, x) > 0 && BRReconcileTxHashCompare(x, x) == 0, "txid order");
    ck(BRReconcileTxHashesSorted(NULL, 0) && !BRReconcileTxHashesSorted(NULL, 1), "sortedness, NULL-safe");
    BRReconcileSortTxHashes(NULL, 5);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "extracts_known_txids_from_details", extracts_known_txids_from_details },
    { "blank_lines_ignored", blank_lines_ignored },
    { "plan_drops_known_and_dedups_across_addresses", plan_drops_known_and_dedups_across_addresses },
    { "plan_keeps_highest_height_for_duplicate_txid", plan_keeps_highest_height_for_duplicate_txid },
    { "empty_history_yields_empty_plan", empty_history_yields_empty_plan },
    { "every_planned_tx_registered_is_covered", every_planned_tx_registered_is_covered },
    { "nothing_planned_is_covered", nothing_planned_is_covered },
    { "register_rejection_is_not_coverage", register_rejection_is_not_coverage },
    { "one_rejection_among_many_successes_sinks_coverage", one_rejection_among_many_successes_sinks_coverage },
    { "fetch_failure_is_not_coverage", fetch_failure_is_not_coverage },
    { "hex_decode_failure_is_not_coverage", hex_decode_failure_is_not_coverage },
    { "all_three_failure_modes_counted_together", all_three_failure_modes_counted_together },
    { "known_set_is_uncapped", known_set_is_uncapped },
    { "wide_wallet_can_still_achieve_coverage", wide_wallet_can_still_achieve_coverage },
    { "null_wallet_has_no_known_set", null_wallet_has_no_known_set },
    { "zero_and_null_txids_are_ignored", zero_and_null_txids_are_ignored },
    { "truncated_read_is_incomplete_never_capped", truncated_read_is_incomplete_never_capped },
    { "long_history_known_set_complete", long_history_known_set_complete },
    { "registers_with_the_nodes_confirming_height", registers_with_the_nodes_confirming_height },
    { "bytes_of_another_transaction_not_registered", bytes_of_another_transaction_not_registered },
    { "the_check_is_given_the_requested_txid", the_check_is_given_the_requested_txid },
    { "first_seen_order", first_seen_order },
    { "plan_buffer_and_known_order", plan_buffer_and_known_order },
    { "coverage_counts_the_plan", coverage_counts_the_plan },
    { "coverage_needs_every_address_answered", coverage_needs_every_address_answered },
    { "plan_first_seen_with_many_repeats", plan_first_seen_with_many_repeats },
    { "large_inputs_are_n_log_n", large_inputs_are_n_log_n },
    { "pending_rule", pending_rule },
    { "pending_txids_from_wallet", pending_txids_from_wallet },
    { "distinct_outpoints", distinct_outpoints },
    { "distinct_addresses", distinct_addresses },
    { "sizes_overflow_checked", sizes_overflow_checked },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", RECONCILE_PLAN_UNFIXED ? "comparison (RECONCILE_PLAN_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
