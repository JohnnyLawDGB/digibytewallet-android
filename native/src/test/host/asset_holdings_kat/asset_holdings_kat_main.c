//
//  asset_holdings_kat_main.c
//
//  Host KAT for BRAssetHoldings.h: which asset rows count, the held balance, the reconcile of a row's
//  spent flag, the prunes, the write made for a detected output, the indexer's answer for one output,
//  the prune gate, and a row's stored form. Moved from Android's Kotlin (core/.../asset/AssetManager.kt:
//  isHeldForDisplay, computeHeldAssetBalancesImpl, decideAssetSpent, reconcileAssetRowsLocallyImpl,
//  clearDeadAssetSend, pruneRemovedNativeAssetRowsImpl, pruneDeadBroadcastRowsImpl,
//  persistDetectedAssetOutput, settledRow, settleFromIndexer, isOutgoingUnconfirmedRow; AssetSums.add;
//  AssetMaintenanceGate.kt; network/DigiScopeAssetParsing.kt's txOutStack and notFound). The header
//  must answer exactly what those answer and refuse exactly what they refuse, so every expectation here
//  is fixed OUTSIDE the header, two ways:
//
//    1. BY HAND. The inputs and expected answers of the Kotlin tests, value for value:
//       HeldAssetBalancesTest (balances_*), AssetHeldMeansNativeHoldsItTest (held_*),
//       AssetConflictedSendTest (conflicted_*), TxOutStackParsingTest (stack_*), and beside them the
//       Kotlin tests of the other rules moved: AssetSpentReconcileTest, AssetMaintenanceGateTest,
//       PruneRemovedNativeAssetRowsTest, AssetDeadBroadcastRowTest, AssetLocalReconcileTest,
//       AssetProvenanceTaggingTest, AssetSourceFixTest and UnbackedAssetCreditTest's settle tests
//       (spent_*, gate_*, prune_*, reconcile_*, persist_*, settle_*). Cases without one of those
//       prefixes are this suite's own: edges, the stored form, capacity, arguments.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C: scripts as lower-case
//       hex text, string-keyed maps where the Kotlin has maps, a row table that deletes and updates by
//       outpoint the way Room's queries do, and Kotlin's Long arithmetic. model_sweep holds the header
//       to it over a fixed pseudo-random corpus, prune passes in sequence included, with rows that go by
//       other means between them and tables that are sometimes too small for a pass (CAPACITY exactly
//       when what Kotlin's map holds after the pass does not fit, and nothing written).
//
//  One case per process: `asset_holdings_kat <case>` prints PASS:/FAIL: lines and `RESULT <case>
//  pass|fail`; `list` prints the case names. run.sh builds one binary per ASSET_HOLDINGS_UNFIXED arm and
//  holds each comparison arm to failing exactly its cases.
//

#include "BRAssetHoldings.h"
#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_holdings_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice

static int FAILED = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (! cond) FAILED = 1;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

#define N(a) (sizeof(a) / sizeof((a)[0]))

// ---- the Kotlin tests' scripts, txids and ids ----------------------------------------------------

static const uint8_t OWN[] = { 1, 2, 3 };          // byteArrayOf(1, 2, 3), "010203"
static const uint8_t FOREIGN[] = { 9, 9, 9 };      // byteArrayOf(9, 9, 9)
static const uint8_t S9999[] = { 0x99, 0x99 };     // "9999"
static const uint8_t SDEAD[] = { 0xde, 0xad };     // "dead"
static const BRAssetScript OWNED[] = { { OWN, 3 } };

static char TXB[32][80];

// `prefix` padded with `fill` to 64 characters (Kotlin: prefix + fill.repeat(...), padEnd(64, fill)).
static const char *tx(int slot, const char *prefix, char fill)
{
    size_t n = strlen(prefix);

    memcpy(TXB[slot], prefix, n);
    while (n < 64) TXB[slot][n++] = fill;
    TXB[slot][64] = '\0';
    return TXB[slot];
}

static BRAssetRow row(const char *txid, int32_t vout, const uint8_t *script, size_t len, int64_t height,
                      const char *assetId, int64_t qty, int spent, BRAssetSource source, BRAssetCredit credit)
{
    BRAssetRow r;

    memset(&r, 0, sizeof(r));
    r.txid = txid;
    r.vout = vout;
    r.script = script;
    r.scriptLen = len;
    r.satoshis = 6000;
    r.blockHeight = height;
    r.isAsset = 1;
    r.assetId = assetId;
    r.assetQuantity = qty;
    r.spent = spent;
    r.source = source;
    r.credit = credit;
    return r;
}

#define NAT BR_ASSET_SOURCE_NATIVE
#define BAK BR_ASSET_SOURCE_BACKEND
#define VER BR_ASSET_CREDIT_VERIFIED
#define UNC BR_ASSET_CREDIT_UNCHECKED

// ---- showing answers -----------------------------------------------------------------------------

static size_t put(char *buf, size_t at, const char *fmt, ...)
{
    va_list ap;
    int n;

    if (at >= 4096) return at;
    va_start(ap, fmt);
    n = vsnprintf(buf + at, 4096 - at, fmt, ap);
    va_end(ap);
    return n < 0 ? at : at + (size_t)n;
}

static void showBalances(char *buf, BRAssetHoldingsStatus st, const BRAssetHeldBalance *b, size_t n)
{
    size_t i, at = 0;

    buf[0] = '\0';
    if (st == BR_ASSET_HOLDINGS_NO_OWNERSHIP) { put(buf, 0, "null"); return; }
    if (st != BR_ASSET_HOLDINGS_OK) { put(buf, 0, "status%d", (int)st); return; }
    for (i = 0; i < n; i++) at = put(buf, at, "%s%s=%lld/%zu", i ? " " : "", b[i].assetId, (long long)b[i].quantity, b[i].utxoCount);
}

static BRAssetHeldBalance HB[64];

static void balances(const char *what, const BRAssetRow *rows, size_t n, const int *states, const BRAssetScript *owned,
                     size_t ownedCount, const char *want)
{
    char got[4096];
    size_t count = 0;
    BRAssetHoldingsStatus st = BRAssetHeldBalances(rows, n, states, owned, ownedCount, HB, N(HB), &count);

    showBalances(got, st, HB, count);
    ck(strcmp(got, want) == 0, "%s: balances [%s] (want [%s])", what, got, want);
}

static int held(const uint8_t *s, size_t len, BRAssetSource src, int state)
{
    return BRAssetHeldForDisplay(s, len, OWNED, 1, src, state);
}

// held and BRAssetRowCounts on a VERIFIED, unspent asset row at the same script (the Kotlin's fixture).
static void heldIs(const char *what, const uint8_t *s, size_t len, BRAssetSource src, int state, int want)
{
    BRAssetRow r = row(tx(31, "", 'a'), 0, s, len, 700000, "La3", 1, 0, src, VER);

    ck(held(s, len, src, state) == want && BRAssetRowCounts(&r, OWNED, 1, state) == want,
       "%s: source %s state %d -> %s", what, src == NAT ? "NATIVE" : "BACKEND", state, want ? "held" : "not held");
}

// ---- HeldAssetBalancesTest ------------------------------------------------------------------------

static void balances_predicate_not_owned(void)   // predicate_not_owned_is_never_held
{
    heldIs("a recipient marker from a transfer we sent", S9999, 2, NAT, 1, 0);
    heldIs("a recipient marker from a transfer we sent", S9999, 2, BAK, 1, 0);
}

static void balances_predicate_owned_held(void)   // predicate_owned_and_native_held_is_held
{
    heldIs("owned and native holds it", OWN, 3, NAT, 1, 1);
}

static void balances_predicate_owned_spent(void)   // predicate_owned_but_native_spent_is_not_held
{
    heldIs("owned, native spent", OWN, 3, NAT, 0, 0);
    heldIs("owned, native spent", OWN, 3, BAK, 0, 0);
}

static void balances_predicate_dead_send(void)   // predicate_owned_native_unknown_native_source_is_dead_send_not_held
{
    heldIs("the 8-unit phantom: native detected then lost the tx", OWN, 3, NAT, -1, 0);
}

static void balances_predicate_no_record(void)   // predicate_owned_but_native_has_no_record_does_not_count
{
    heldIs("no record, BACKEND provenance", OWN, 3, BAK, -1, 0);
    heldIs("no record, NATIVE provenance", OWN, 3, NAT, -1, 0);
}

static void balances_predicate_probe_error(void)   // predicate_probe_error_does_not_count
{
    heldIs("probe error", OWN, 3, BAK, -99, 0);
    heldIs("probe error", OWN, 3, NAT, -99, 0);
}

static void balances_chang_shape(void)   // chang_shape_sums_to_true_supply
{
    BRAssetRow r[5];
    int st[5];

    r[0] = row(tx(0, "99", 'a'), 0, OWN, 3, 700000, "La3", 9, 0, NAT, VER);       st[0] = 1;
    r[1] = row(tx(1, "30", 'a'), 0, OWN, 3, 700000, "La3", 1, 0, NAT, VER);       st[1] = 1;
    r[2] = row(tx(2, "79", 'a'), 0, OWN, 3, 700000, "La3", 8, 0, NAT, VER);       st[2] = -1;
    r[3] = row(tx(3, "79", 'b'), 0, FOREIGN, 3, 700000, "La3", 2, 0, NAT, VER);   st[3] = -1;
    r[4] = row(tx(4, "15", 'a'), 0, OWN, 3, 700000, "La3", 10, 1, NAT, VER);      st[4] = -1;
    balances("21 across 9 rows resolves to 10 across 2", r, 5, st, OWNED, 1, "La3=10/2");
}

static void balances_no_record_row(void)   // a_row_native_has_no_record_of_is_not_counted
{
    BRAssetRow r[1];
    int st[1] = { -1 };

    r[0] = row(tx(0, "aa", 'a'), 0, OWN, 3, 700000, "La6", 5, 0, BAK, VER);
    balances("native never saw it", r, 1, st, OWNED, 1, "");
}

static void balances_empty_owned(void)   // empty_owned_set_returns_null_for_naive_fallback
{
    BRAssetRow r[1];
    int st[1] = { 1 };

    r[0] = row(tx(0, "aa", 'a'), 0, OWN, 3, 700000, "La6", 5, 0, NAT, VER);
    balances("ownership unknowable", r, 1, st, OWNED, 0, "null");
    balances("ownership unknowable, no rows", NULL, 0, NULL, NULL, 0, "null");
}

static void balances_fully_phantom(void)   // fully_phantom_asset_resolves_to_zero
{
    BRAssetRow r[2];
    int st[2] = { -1, -1 };
    size_t count = 9, i;
    BRAssetHoldingsStatus s;
    int ok = 1;

    r[0] = row(tx(0, "79", 'b'), 0, FOREIGN, 3, 700000, "LaPhantom", 5, 0, NAT, VER);
    r[1] = row(tx(1, "79", 'a'), 0, OWN, 3, 700000, "LaPhantom", 3, 0, NAT, VER);
    s = BRAssetHeldBalances(r, 2, st, OWNED, 1, HB, N(HB), &count);
    for (i = 0; i < count; i++) if (strcmp(HB[i].assetId, "LaPhantom") == 0 && HB[i].quantity != 0) ok = 0;
    ck(s == BR_ASSET_HOLDINGS_OK && ok, "an asset whose every row is a phantom is absent or zero");
}

// ---- AssetHeldMeansNativeHoldsItTest --------------------------------------------------------------

static void held_native_holds_it(void)   // native_holds_it_so_it_counts
{
    heldIs("native holds it", OWN, 3, NAT, BR_ASSET_STATE_HELD, 1);
    heldIs("native holds it", OWN, 3, BAK, BR_ASSET_STATE_HELD, 1);
}

static void held_no_record_any_provenance(void)   // no_record_does_not_count_whatever_the_provenance
{
    heldIs("no record", OWN, 3, BAK, BR_ASSET_STATE_UNDETECTED, 0);
    heldIs("no record", OWN, 3, NAT, BR_ASSET_STATE_UNDETECTED, 0);
}

static void held_probe_error(void)   // a_probe_error_does_not_count
{
    heldIs("a failed probe", OWN, 3, BAK, BR_ASSET_STATE_PROBE_ERROR, 0);
}

static void held_spent_and_conflicted(void)   // spent_and_conflicted_still_do_not_count
{
    heldIs("spent", OWN, 3, NAT, BR_ASSET_STATE_SPENT, 0);
    heldIs("conflicted", OWN, 3, NAT, BR_ASSET_STATE_CONFLICTED, 0);
}

static void held_unowned_address(void)   // an_address_we_do_not_own_never_counts
{
    heldIs("an address we do not own", SDEAD, 2, NAT, BR_ASSET_STATE_HELD, 0);
}

static void held_ultra_wallet_nine(void)   // the_ultra_wallet_reads_nine
{
    BRAssetRow r[3];
    int st[3] = { BR_ASSET_STATE_UNDETECTED, BR_ASSET_STATE_HELD, BR_ASSET_STATE_HELD };

    r[0] = row(tx(0, "79b27063eab3", '0'), 2, OWN, 3, 0, "La3t7Jdv", 8, 0, BAK, VER);   // phantom
    r[1] = row(tx(1, "3afac554fe4c", '0'), 2, OWN, 3, 0, "La3t7Jdv", 8, 0, NAT, VER);   // real change
    r[2] = row(tx(2, "30872f2c2462", '0'), 2, OWN, 3, 0, "La3t7Jdv", 1, 0, NAT, VER);   // real, sent to self
    balances("9 held, not 17", r, 3, st, OWNED, 1, "La3t7Jdv=9/2");
}

// ---- AssetConflictedSendTest ----------------------------------------------------------------------

static void conflicted_not_held(void)   // a_conflicted_transactions_output_is_not_held
{
    heldIs("a conflicted transaction's output", OWN, 3, NAT, BR_ASSET_STATE_CONFLICTED, 0);
}

static void conflicted_backend_not_rescued(void)   // backend_provenance_does_not_rescue_a_conflicted_output
{
    heldIs("BACKEND does not rescue a conflicted output", OWN, 3, BAK, BR_ASSET_STATE_CONFLICTED, 0);
}

static void conflicted_leaves_spent_flag(void)   // conflicted_leaves_the_persisted_spent_flag_alone
{
    int spent = 7;

    ck(BRAssetRowSpentDecide(BR_ASSET_STATE_CONFLICTED, &spent) == 0 && spent == 7, "CONFLICTED leaves the spent flag alone");
}

static void conflicted_stuck_attempt_excluded(void)   // the_stuck_attempts_change_is_excluded_from_the_balance
{
    BRAssetRow r[2];
    int st[2] = { BR_ASSET_STATE_CONFLICTED, BR_ASSET_STATE_HELD };

    r[0] = row("de00000000000000000000000000000000000000000000000000000000000000", 2, OWN, 3, 0, "La3t7Jdv", 9, 0, NAT, VER);
    r[1] = row("11ve000000000000000000000000000000000000000000000000000000000000", 2, OWN, 3, 0, "La3t7Jdv", 9, 0, NAT, VER);
    balances("the abandoned attempt's 9 and the real 9", r, 2, st, OWNED, 1, "La3t7Jdv=9/1");
}

static void conflicted_other_states_unchanged(void)   // the_other_states_are_unchanged
{
    int spent = 7;

    heldIs("HELD", OWN, 3, NAT, BR_ASSET_STATE_HELD, 1);
    heldIs("SPENT", OWN, 3, NAT, BR_ASSET_STATE_SPENT, 0);
    heldIs("UNDETECTED", OWN, 3, BAK, BR_ASSET_STATE_UNDETECTED, 0);
    ck(BRAssetRowSpentDecide(BR_ASSET_STATE_SPENT, &spent) == 1 && spent == 1, "SPENT sets the flag");
    ck(BRAssetRowSpentDecide(BR_ASSET_STATE_HELD, &spent) == 1 && spent == 0, "HELD clears it");
    spent = 7;
    ck(BRAssetRowSpentDecide(BR_ASSET_STATE_UNDETECTED, &spent) == 0 && spent == 7, "UNDETECTED leaves it");
}

// ---- TxOutStackParsingTest ------------------------------------------------------------------------

#define ASKED "abababababababababababababababababababababababababababababababab"   // "ab".repeat(32)
#define OTHER "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"

static BRAssetStackAnswer answer(int hasError, const char *txid, int hasVout, int32_t vout, int hasAssets,
                                 const BRAssetStackAnswerEntry *e, size_t n)
{
    BRAssetStackAnswer a;

    a.hasError = hasError; a.txid = txid; a.hasVout = hasVout; a.vout = vout; a.hasAssets = hasAssets;
    a.entries = e; a.entryCount = n;
    return a;
}

static BRAssetStackAnswerEntry entry(int isObject, const char *assetId, int whole, int64_t count)
{
    BRAssetStackAnswerEntry e;

    e.isObject = isObject; e.assetId = assetId; e.countIsWhole = whole; e.count = count;
    return e;
}

static BRAssetUnits SU[16];

static void showStack(char *buf, BRAssetHoldingsStatus st, BRAssetStackLookup lookup, const BRAssetUnits *u, size_t n)
{
    size_t i, at = 0;

    buf[0] = '\0';
    if (st != BR_ASSET_HOLDINGS_OK) { put(buf, 0, "status%d", (int)st); return; }
    if (lookup == BR_ASSET_STACK_UNAVAILABLE) { put(buf, 0, "Unavailable"); return; }
    if (lookup == BR_ASSET_STACK_NOT_UNSPENT) { put(buf, 0, "NotUnspent"); return; }
    at = put(buf, at, "Found(");
    for (i = 0; i < n; i++) at = put(buf, at, "%s%s %lld", i ? ", " : "", u[i].assetId, (long long)u[i].count);
    put(buf, at, ")");
}

static void stackIs(const char *what, const BRAssetStackAnswer *a, int32_t vout, const char *want)
{
    char got[4096];
    size_t n = 99;
    BRAssetStackLookup lookup = BR_ASSET_STACK_FOUND;
    BRAssetHoldingsStatus st = BRAssetStackAnswerRead(a, ASKED, vout, SU, N(SU), &n, &lookup);

    showStack(got, st, lookup, SU, n);
    ck(strcmp(got, want) == 0, "%s: %s (want %s)", what, got, want);
}

static void stack_read_in_order(void)   // the_stack_is_read_in_order
{
    BRAssetStackAnswerEntry e[2];
    BRAssetStackAnswer a;

    e[0] = entry(1, "La1", 1, 5);
    e[1] = entry(1, "Ua2", 1, 3000000000000LL);
    a = answer(0, ASKED, 1, 2, 1, e, 2);
    stackIs("{txid, vout 2, confirmations 4, assets [La1 x5, Ua2 x3000000000000]}", &a, 2, "Found(La1 5, Ua2 3000000000000)");
}

static void stack_empty_is_answer(void)   // an_empty_stack_is_an_answer
{
    BRAssetStackAnswer a = answer(0, ASKED, 1, 2, 1, NULL, 0);

    stackIs("{txid, vout 2, assets []}", &a, 2, "Found()");
}

static void stack_anything_else_no_answer(void)   // anything_else_is_no_answer
{
    BRAssetStackAnswerEntry e[1];
    BRAssetStackAnswer a;

    a = answer(1, NULL, 0, -1, 0, NULL, 0);
    stackIs("{error: indexer unavailable}", &a, 2, "Unavailable");
    a = answer(0, OTHER, 1, 2, 1, NULL, 0);
    stackIs("{txid: cd.., vout 2, assets []}", &a, 2, "Unavailable");
    a = answer(0, ASKED, 1, 3, 1, NULL, 0);
    stackIs("{txid, vout 3, assets []}", &a, 2, "Unavailable");
    a = answer(0, ASKED, 0, -1, 1, NULL, 0);
    stackIs("{txid, assets []}", &a, 2, "Unavailable");
    a = answer(0, ASKED, 1, 2, 0, NULL, 0);
    stackIs("{txid, vout 2}", &a, 2, "Unavailable");
    e[0] = entry(1, "", 1, 5);
    a = answer(0, ASKED, 1, 2, 1, e, 1);
    stackIs("{.., assets [{count 5}]}", &a, 2, "Unavailable");
    e[0] = entry(1, "La1", 0, 0);
    stackIs("{.., assets [{assetId La1}]}", &a, 2, "Unavailable");
    e[0] = entry(1, "La1", 0, 1);
    stackIs("{.., assets [{assetId La1, count 1.5}]}", &a, 2, "Unavailable");
    e[0] = entry(1, "La1", 1, -1);
    stackIs("{.., assets [{assetId La1, count -1}]}", &a, 2, "Unavailable");
    e[0] = entry(1, "La1", 0, 5);
    stackIs("{.., assets [{assetId La1, count \"5\"}]}", &a, 2, "Unavailable");
    e[0] = entry(1, "null", 1, 5);
    stackIs("{.., assets [{assetId null, count 5}]} (read as \"null\")", &a, 2, "Unavailable");
    e[0] = entry(1, NULL, 1, 5);
    stackIs("{.., assets [{assetId null, count 5}]} (read as absent)", &a, 2, "Unavailable");
}

static void stack_not_found_own_404(void)   // only_the_endpoints_own_404_means_not_unspent
{
    ck(BRAssetStackNotFoundRead(1, 1) == BR_ASSET_STACK_NOT_UNSPENT, "{\"unspent\":false} -> NotUnspent");
    ck(BRAssetStackNotFoundRead(0, 0) == BR_ASSET_STACK_UNAVAILABLE, "null -> Unavailable");
    ck(BRAssetStackNotFoundRead(0, 0) == BR_ASSET_STACK_UNAVAILABLE, "\"\" -> Unavailable");
    ck(BRAssetStackNotFoundRead(0, 0) == BR_ASSET_STACK_UNAVAILABLE, "Cannot GET /api/digiassets/txout/x/0 -> Unavailable");
    ck(BRAssetStackNotFoundRead(1, 0) == BR_ASSET_STACK_UNAVAILABLE, "{\"error\":\"not found\"} -> Unavailable");
    ck(BRAssetStackNotFoundRead(1, 0) == BR_ASSET_STACK_UNAVAILABLE, "{\"unspent\":true} -> Unavailable");
    ck(BRAssetStackNotFoundRead(0, 0) == BR_ASSET_STACK_UNAVAILABLE, "<html>404</html> -> Unavailable");
    ck(BRAssetStackNotFoundRead(0, 1) == BR_ASSET_STACK_UNAVAILABLE, "a body that is not an object -> Unavailable");
}

// ---- AssetSpentReconcileTest, AssetMaintenanceGateTest, AssetSourceFixTest ------------------------

static void spent_decide(void)
{
    int spent = 7, i;
    static const int others[] = { -1, 99, -2, -99, 2, INT32_MIN, INT32_MAX };

    ck(BRAssetRowSpentDecide(0, &spent) == 1 && spent == 1, "spent_marksSpent");
    ck(BRAssetRowSpentDecide(1, &spent) == 1 && spent == 0, "held_marksUnspent");
    for (i = 0; i < (int)N(others); i++) {
        spent = 7;
        ck(BRAssetRowSpentDecide(others[i], &spent) == 0 && spent == 7, "state %d leaves the row unchanged", others[i]);
    }
    ck(BRAssetRowSpentDecide(1, NULL) == 1 && BRAssetRowSpentDecide(-1, NULL) == 0, "with nowhere to write, the answer still");
}

static void gate_rules(void)
{
    const int64_t tip = 24200000, atTip = tip + 1;

    ck(BRAssetPruneGateOpen(1, 1, 1.0f, 1, atTip, tip), "open when all conditions hold");
    ck(BRAssetPruneGateOpen(1, 1, 1.0f, 1, tip - 100, tip), "open with the scan 100 behind");
    ck(! BRAssetPruneGateOpen(0, 1, 1.0f, 1, atTip, tip), "closed: not synced this session");
    ck(! BRAssetPruneGateOpen(1, 0, 1.0f, 1, atTip, tip), "closed: no peer");
    ck(! BRAssetPruneGateOpen(1, 1, 0.99f, 1, atTip, tip), "closed: progress 0.99");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 0, atTip, tip), "closed: wallet not loaded");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 1, 19000000, tip), "closed: the filter scan far behind");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 1, tip - 101, tip), "closed: the scan 101 behind");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 1, 0, tip), "closed: the scan frontier unknown");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 1, atTip, 0), "closed: the tip unknown");
    // Edges of this suite's own.
    ck(! BRAssetPruneGateOpen(1, -1, 1.0f, 1, atTip, tip) && ! BRAssetPruneGateOpen(1, INT32_MIN, 1.0f, 1, atTip, tip),
       "closed: a negative peer count");
    ck(BRAssetPruneGateOpen(1, INT32_MAX, 2.5f, 1, INT64_MAX, INT64_MAX), "open at the int64 end");
    ck(BRAssetPruneGateOpen(1, 1, 1.0f, 1, INT64_MAX, 1), "open with the scan past the tip");
    ck(! BRAssetPruneGateOpen(1, 1, 1.0f, 1, 1, INT64_MAX) && ! BRAssetPruneGateOpen(1, 1, 1.0f, 1, -5, tip) &&
       ! BRAssetPruneGateOpen(1, 1, 1.0f, 1, atTip, -5), "closed: a negative frontier or tip, or the scan far behind at the end");
    ck(! BRAssetPruneGateOpen(1, 1, 0.0f / 0.0f, 1, atTip, tip), "closed: progress NaN");
    ck(BRAssetPruneGateOpen(5, 1, 1.0f, 5, atTip, tip), "any nonzero flag is true");
}

static void outgoing_unconfirmed_rows(void)
{
    const int64_t u = INT32_MAX;

    ck(BRAssetTxIsOutgoingUnconfirmed(700, u), "outgoing_and_unconfirmed_is_true");
    ck(! BRAssetTxIsOutgoingUnconfirmed(0, u), "incoming_receive_is_false_even_if_unconfirmed");
    ck(! BRAssetTxIsOutgoingUnconfirmed(700, 800000), "confirmed_outgoing_is_false");
    // buildOutgoingUnconfirmedMap's rows, by their sent and blockHeight fields.
    ck(BRAssetTxIsOutgoingUnconfirmed(700, u), "aa|-700|100|u|..|700|0 maps true");
    ck(! BRAssetTxIsOutgoingUnconfirmed(0, u), "bb|6000|0|u|..|0|6000 maps false");
    ck(! BRAssetTxIsOutgoingUnconfirmed(700, 800000), "cc|-700|100|800000|..|700|0 maps false");
    // Edges.
    ck(BRAssetTxIsOutgoingUnconfirmed(1, INT64_MAX) && BRAssetTxIsOutgoingUnconfirmed(INT64_MAX, u), "the ends are unconfirmed sends");
    ck(! BRAssetTxIsOutgoingUnconfirmed(-1, u) && ! BRAssetTxIsOutgoingUnconfirmed(INT64_MIN, u) &&
       ! BRAssetTxIsOutgoingUnconfirmed(700, u - 1) && ! BRAssetTxIsOutgoingUnconfirmed(700, INT64_MIN),
       "nothing sent, or a height below INT32_MAX, is not");
}

// ---- prunes (PruneRemovedNativeAssetRowsTest, AssetDeadBroadcastRowTest) ---------------------------

static BRAssetAbsence AB[64];
static BRAssetAbsenceTable TABLE;
static BRAssetRowChange CH[64];

static void tableFresh(void)
{
    memset(AB, 0, sizeof(AB));
    TABLE.entries = AB;
    TABLE.count = 0;
    TABLE.capacity = N(AB);
}

// One pass; the rows deleted are dropped from rows[0, *n), as the table drops them. Returns removed.
static size_t pass(int dead, BRAssetRow *rows, size_t *n, const BRAssetTxLookup *lookups, BRAssetTxLookup *lk)
{
    size_t removed = 99, i, k = 0;
    BRAssetHoldingsStatus st;

    st = dead ? BRAssetPruneDeadBroadcastRows(rows, *n, lookups, &TABLE, CH, &removed)
              : BRAssetPruneRemovedNativeRows(rows, *n, lookups, &TABLE, CH, &removed);
    if (st != BR_ASSET_HOLDINGS_OK) { ck(0, "a prune pass was refused (%d)", (int)st); return 0; }
    for (i = 0; i < *n; i++) {
        if (CH[i] != BR_ASSET_ROW_DELETE && CH[i] != BR_ASSET_ROW_KEEP) ck(0, "a prune wrote a spent flag");
        if (CH[i] != BR_ASSET_ROW_DELETE) { rows[k] = rows[i]; if (lk) lk[k] = lk[i]; k++; }
    }
    *n = k;
    return removed;
}

static void prune_native_debounce(void)   // native_row_gone_deleted_only_after_debounce
{
    BRAssetRow r[1];
    BRAssetTxLookup lk[1] = { BR_ASSET_TX_NOT_HELD };
    size_t n = 1, a, b;

    tableFresh();
    r[0] = row(tx(0, "", 'a'), 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    a = pass(0, r, &n, lk, NULL);
    ck(a == 0 && n == 1, "sweep 1: below the threshold, nothing deleted");
    b = pass(0, r, &n, lk, NULL);
    ck(b == 1 && n == 0, "sweep 2: deleted, once");
}

static void prune_native_two_outpoints(void)   // two_outpoints_same_txid_each_debounced_independently
{
    BRAssetRow r[2];
    BRAssetTxLookup lk[2] = { BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };
    size_t n = 2, a, b;

    tableFresh();
    r[0] = row(tx(0, "", 'b'), 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    r[1] = row(tx(0, "", 'b'), 1, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    a = pass(0, r, &n, lk, NULL);
    ck(a == 0 && n == 2, "pass 1: both below the threshold");
    b = pass(0, r, &n, lk, NULL);
    ck(b == 2 && n == 0, "pass 2: both deleted");
}

static void prune_native_present_resets(void)   // native_row_present_resets_debounce_before_threshold
{
    BRAssetRow r[1];
    BRAssetTxLookup gone[1] = { BR_ASSET_TX_NOT_HELD }, here[1] = { BR_ASSET_TX_HELD };
    size_t n = 1, a, b, c;

    tableFresh();
    r[0] = row(tx(0, "", 'c'), 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    a = pass(0, r, &n, gone, NULL);
    b = pass(0, r, &n, here, NULL);
    c = pass(0, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && c == 0 && n == 1, "absent, present, absent: never two in a row, nothing deleted");
    ck(TABLE.count == 1 && TABLE.entries[0].passes == 1, "the count is 1, not 2");
}

static void prune_native_malformed_txid(void)   // malformed_txid_never_queried_or_deleted
{
    BRAssetRow r[1];
    BRAssetTxLookup gone[1] = { BR_ASSET_TX_NOT_HELD };
    size_t n = 1, a, b;

    tableFresh();
    r[0] = row("short", 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    a = pass(0, r, &n, gone, NULL);
    b = pass(0, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "a txid that is not 64 characters is never counted or deleted");
    tableFresh();
    r[0] = row(tx(0, "", 'a'), 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    TXB[0][63] = '\0';   // 63
    a = pass(0, r, &n, gone, NULL);
    b = pass(0, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && TABLE.count == 0, "nor one of 63");
    r[0].txid = NULL;
    a = pass(0, r, &n, gone, NULL);
    ck(a == 0 && TABLE.count == 0, "nor none");
    tableFresh();
    memcpy(TXB[1], tx(0, "", 'a'), 64);
    TXB[1][64] = 'a'; TXB[1][65] = '\0';   // 65
    r[0] = row(TXB[1], 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    a = pass(0, r, &n, gone, NULL);
    b = pass(0, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "nor one of 65, whose first 64 are a txid");
    // The dead-broadcast prune reads the txid the same way.
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "the dead prune: never one of 65");
    r[0].txid = "short";
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "the dead prune: never a short one");
    TXB[1][63] = '\0';
    r[0].txid = TXB[1];
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "the dead prune: nor one of 63");
}

static void prune_native_backend_untouched(void)   // queries_only_native_source_backend_rows_untouched
{
    BRAssetRow r[1];
    BRAssetTxLookup gone[1] = { BR_ASSET_TX_NOT_HELD };
    size_t n = 0, a, b;

    tableFresh();
    a = pass(0, r, &n, gone, NULL);
    ck(a == 0, "no NATIVE rows: nothing deleted");
    // This suite's own: a BACKEND row (a holding native never scanned) is never taken, whatever the answer.
    r[0] = row(tx(0, "", 'd'), 0, NULL, 0, 0, "La1", 3, 0, BAK, UNC);
    n = 1;
    a = pass(0, r, &n, gone, NULL);
    b = pass(0, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "a BACKEND row is never counted or deleted by the native prune");
}

static void prune_dead_never_confirmed(void)   // a_never_confirmed_orphan_row_is_pruned_regardless_of_provenance
{
    BRAssetRow r[1];
    BRAssetTxLookup gone[1] = { BR_ASSET_TX_NOT_HELD };
    size_t n = 1, a, b;
    const char *dead = "eacb2f6de366c653869a46f93f7dd20daa69f8f9623f05b1e194da869bac570d";

    tableFresh();
    r[0] = row(dead, 2, OWN, 3, 0, "La3t7Jdv", 9, 0, BAK, UNC);
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 1 && n == 0, "deleted on the second pass, BACKEND or not");
}

static void prune_dead_confirmed_kept(void)   // a_confirmed_row_is_never_pruned_by_this_pass
{
    BRAssetRow r[1];
    BRAssetTxLookup gone[1] = { BR_ASSET_TX_NOT_HELD };
    size_t n = 1, a, b;

    tableFresh();
    r[0] = row(tx(0, "aa", 'a'), 2, OWN, 3, 23000000, "La3t7Jdv", 5, 0, BAK, UNC);
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1 && TABLE.count == 0, "a confirmed row is a below-floor holding: kept");
    r[0].blockHeight = 1;
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 0 && n == 1, "height 1 too");
    r[0].blockHeight = -1;
    a = pass(1, r, &n, gone, NULL);
    b = pass(1, r, &n, gone, NULL);
    ck(a == 0 && b == 1, "a height below zero never confirmed: deleted");
}

static void prune_dead_present_kept(void)   // a_row_whose_tx_is_still_present_is_never_pruned
{
    BRAssetRow r[1];
    BRAssetTxLookup here[1] = { BR_ASSET_TX_HELD }, failed[1] = { BR_ASSET_TX_LOOKUP_FAILED };
    size_t n = 1, k, total = 0;

    tableFresh();
    r[0] = row(tx(0, "bb", 'b'), 2, OWN, 3, 0, "La3t7Jdv", 9, 0, BAK, UNC);
    for (k = 0; k < 4; k++) total += pass(1, r, &n, here, NULL);
    ck(total == 0 && n == 1, "a transaction the wallet still knows: no deletion in four passes");
    for (k = 0; k < 4; k++) total += pass(1, r, &n, failed, NULL);
    ck(total == 0 && n == 1 && TABLE.count == 0, "a lookup that failed is not evidence of death");
}

static void prune_lookup_readings(void)
{
    BRAssetRow r[1];
    BRAssetTxLookup lk[1];
    size_t n = 1, a, b;

    tableFresh();
    r[0] = row(tx(0, "", 'e'), 0, NULL, 0, 0, "La1", 3, 0, NAT, UNC);
    lk[0] = BR_ASSET_TX_LOOKUP_FAILED;
    a = pass(0, r, &n, lk, NULL);
    b = pass(0, r, &n, lk, NULL);
    ck(a == 0 && b == 1, "native prune: a failed lookup reads as absent");
    tableFresh();
    n = 1;
    lk[0] = BR_ASSET_TX_NO_OUTPUTS;
    a = pass(0, r, &n, lk, NULL);
    b = pass(0, r, &n, lk, NULL);
    ck(a == 0 && b == 0 && TABLE.count == 0, "native prune: an empty output list is present");
    a = pass(1, r, &n, lk, NULL);
    b = pass(1, r, &n, lk, NULL);
    ck(a == 0 && b == 1, "dead prune: an empty output list is absent");
    tableFresh();
    n = 1;
    lk[0] = (BRAssetTxLookup)9;
    a = pass(0, r, &n, lk, NULL);
    b = pass(1, r, &n, lk, NULL);
    ck(a == 0 && b == 0 && TABLE.count == 0, "an answer of no defined kind is present to both");
    {   // A lookup nobody filled (0) is present to both: it forgets the counts there.
        BRAssetRow zr[2];
        BRAssetTxLookup zl[2] = { BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };
        size_t zn = 2, total = 0;

        tableFresh();
        zr[0] = row(tx(0, "", 'a'), 0, OWN, 3, 0, "La1", 3, 0, NAT, UNC);
        zr[1] = row(tx(1, "", 'b'), 1, OWN, 3, 0, "La1", 3, 0, NAT, UNC);
        total += pass(0, zr, &zn, zl, NULL);
        total += pass(1, zr, &zn, zl, NULL);
        ck(total == 0 && TABLE.count == 4, "one absent pass of each prune: four counts");
        memset(zl, 0, sizeof(zl));
        total += pass(1, zr, &zn, zl, NULL);
        ck(total == 0 && TABLE.count == 2 && TABLE.entries[0].kind == BR_ASSET_ABSENCE_NATIVE &&
           TABLE.entries[1].kind == BR_ASSET_ABSENCE_NATIVE, "a dead pass of zeros forgets its counts");
        total += pass(0, zr, &zn, zl, NULL);
        ck(total == 0 && zn == 2 && TABLE.count == 0, "a native pass of zeros forgets its own");
    }
}

static void prune_shared_table(void)
{
    BRAssetRow r[2];
    BRAssetTxLookup lk[2] = { BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };
    size_t n = 2, a, b, c;

    tableFresh();
    r[0] = row(tx(0, "", 'f'), 0, OWN, 3, 0, "La1", 3, 0, NAT, UNC);
    r[1] = row(tx(1, "", '7'), 1, OWN, 3, 0, "La1", 3, 0, BAK, UNC);
    a = pass(1, r, &n, lk, NULL);   // dead counts: both at 1
    ck(a == 0 && TABLE.count == 2, "a dead pass counts both never-confirmed rows");
    b = pass(0, r, &n, lk, NULL);   // native: counts row 0 at 1, keeps only its own
    ck(b == 0 && TABLE.count == 1 && TABLE.entries[0].kind == BR_ASSET_ABSENCE_NATIVE,
       "a native pass keeps only the counts of the NATIVE rows it saw");
    c = pass(1, r, &n, lk, NULL);   // dead counts start again
    ck(c == 0 && n == 2, "so the next dead pass deletes nothing");
    c = pass(1, r, &n, lk, NULL);
    ck(c == 2 && n == 0, "and the one after deletes both");
    tableFresh();
    r[0] = row(tx(0, "", 'f'), 0, OWN, 3, 0, "La1", 3, 0, NAT, UNC);
    n = 1;
    a = pass(0, r, &n, lk, NULL);
    b = pass(1, r, &n, lk, NULL);
    ck(a == 0 && b == 0 && TABLE.count == 2, "the two prunes count one outpoint apart");
    c = pass(0, r, &n, lk, NULL);
    ck(c == 1 && n == 0 && TABLE.count == 0, "the native pass deletes on its own second count, and forgets the dead one");
}

// This suite's own: a count left by a row that went by other means (the dead send cleared, the reconcile)
// never takes the room a native pass needs; the pass drops it, as it drops every count but those of the
// NATIVE rows it looks at. A table too small for the counts a pass keeps and adds is still CAPACITY.
static void prune_stale_counts(void)
{
    BRAssetRow d[1], r[2];
    BRAssetAbsence one[1];
    BRAssetAbsenceTable t;
    BRAssetTxLookup gone[2] = { BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };
    size_t removed = 9, k;
    BRAssetHoldingsStatus st;

    memset(one, 0, sizeof(one));
    t.entries = one; t.count = 0; t.capacity = 1;
    d[0] = row(tx(0, "", 'd'), 0, OWN, 3, 0, "La1", 3, 0, BAK, UNC);
    ck(BRAssetPruneDeadBroadcastRows(d, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 &&
       t.entries[0].kind == BR_ASSET_ABSENCE_DEAD, "a dead pass counts a never-confirmed BACKEND row, filling a table of one");
    // That row then goes by other means; its count stays.
    r[0] = row(tx(1, "", 'e'), 0, OWN, 3, 700000, "La1", 3, 0, NAT, VER);
    st = BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed);
    ck(st == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 && t.entries[0].kind == BR_ASSET_ABSENCE_NATIVE &&
       strcmp(t.entries[0].txid, r[0].txid) == 0 && t.entries[0].passes == 1,
       "a native pass into that table: the stale count makes room and the NATIVE row is counted (%d)", (int)st);
    st = BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed);
    ck(st == BR_ASSET_HOLDINGS_OK && removed == 1 && CH[0] == BR_ASSET_ROW_DELETE && t.count == 0, "and deleted on the second pass");

    // A stale NATIVE count (its row went by other means) makes room the same way.
    t.count = 0;
    ck(BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && t.count == 1, "a NATIVE row counted");
    r[1] = row(tx(2, "", 'f'), 1, OWN, 3, 700000, "La1", 3, 0, NAT, VER);
    ck(BRAssetPruneRemovedNativeRows(r + 1, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 &&
       strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].vout == 1 && t.entries[0].passes == 1,
       "its row gone, another NATIVE row takes the room");

    // Room is what the table holds once the pass is done. A count that ends this pass (its row deleted)
    // or is forgotten (its row present) makes room for a new one.
    t.count = 0;
    ck(BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && t.count == 1, "row 0 counted");
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 1 && CH[0] == BR_ASSET_ROW_DELETE &&
       CH[1] == BR_ASSET_ROW_KEEP && t.count == 1 && strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].passes == 1,
       "row 0 absent again, row 1 new, a table of one: row 0 deleted, row 1 counted");
    gone[0] = gone[1] = BR_ASSET_TX_HELD;
    r[0].txid = tx(3, "", 'g');   // a NATIVE row of its own from here
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 0,
       "row 1 present: its count is forgotten");
    gone[0] = BR_ASSET_TX_NOT_HELD; gone[1] = BR_ASSET_TX_HELD;
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && t.count == 1 &&
       strcmp(t.entries[0].txid, r[0].txid) == 0, "row 0 counted");
    gone[0] = BR_ASSET_TX_HELD; gone[1] = BR_ASSET_TX_NOT_HELD;
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 &&
       strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].passes == 1, "row 0 present, row 1 new: row 0 forgotten, row 1 counted");

    // Counts the table must still hold, beside the new ones, that it cannot: CAPACITY, nothing written.
    t.count = 0;
    gone[0] = gone[1] = BR_ASSET_TX_NOT_HELD;
    for (k = 0; k < 2; k++) CH[k] = BR_ASSET_ROW_MARK_SPENT;
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && removed == 0 && t.count == 0 &&
       CH[0] == BR_ASSET_ROW_MARK_SPENT && CH[1] == BR_ASSET_ROW_MARK_SPENT, "two new counts into a table of one: CAPACITY, nothing written");
    ck(BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && t.count == 1, "a NATIVE count");
    d[0].txid = r[1].txid;
    ck(BRAssetPruneDeadBroadcastRows(d, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && t.count == 1 &&
       t.entries[0].kind == BR_ASSET_ABSENCE_NATIVE && t.entries[0].passes == 1,
       "a dead pass keeps every count: the NATIVE one and a new dead one into a table of one is CAPACITY");
    // A count at 0 (a table the header did not fill) found absent again is kept, at 1.
    t.entries[0].passes = 0;
    ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && t.count == 1 && t.entries[0].passes == 0,
       "a count at 0 counted again and a new one into a table of one: CAPACITY");
    ck(BRAssetPruneRemovedNativeRows(r, 1, gone, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 &&
       t.entries[0].passes == 1, "alone, it fits: at 1, nothing deleted");
    {   // In a table of two: the count at 0 goes to 1, and the row after it is counted too.
        BRAssetAbsence *two = (BRAssetAbsence *)calloc(2, sizeof(BRAssetAbsence));
        BRAssetAbsenceTable t2;
        BRAssetRow dup[3];
        BRAssetTxLookup lk3[3] = { BR_ASSET_TX_HELD, BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };

        if (! two) { ck(0, "calloc"); return; }
        two[0] = t.entries[0];
        two[0].passes = 0;
        t2.entries = two; t2.count = 1; t2.capacity = 2;
        ck(BRAssetPruneRemovedNativeRows(r, 2, gone, &t2, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t2.count == 2 &&
           strcmp(two[0].txid, r[0].txid) == 0 && two[0].passes == 1 && strcmp(two[1].txid, r[1].txid) == 0 && two[1].passes == 1,
           "a count at 0 found absent goes to 1, and a new row after it is counted");
        // Rows sharing an outpoint (the table's never do) never write past the table: a table of exactly one,
        // its count forgotten by the first row, found again by the second, and a third row new.
        free(two);
        two = (BRAssetAbsence *)malloc(sizeof(BRAssetAbsence));
        if (! two) { ck(0, "malloc"); return; }
        dup[0] = r[0]; dup[1] = r[0]; dup[2] = r[1];
        t2.entries = two; t2.count = 0; t2.capacity = 1;
        ck(BRAssetPruneRemovedNativeRows(dup, 1, gone, &t2, CH, &removed) == BR_ASSET_HOLDINGS_OK && t2.count == 1, "one count");
        ck(BRAssetPruneRemovedNativeRows(dup, 3, lk3, &t2, CH, &removed) == BR_ASSET_HOLDINGS_OK && t2.count == 1,
           "rows that share an outpoint: OK, and the table of one holds one");
        free(two);
    }
}

// ---- reconcile (AssetLocalReconcileTest) and the dead send --------------------------------------

static void showChanges(char *buf, const BRAssetRowChange *c, size_t n)
{
    static const char *const name[] = { "keep", "delete", "spent", "unspent" };
    size_t i, at = 0;

    buf[0] = '\0';
    for (i = 0; i < n; i++) at = put(buf, at, "%s%s", i ? " " : "", (unsigned)c[i] < 4 ? name[c[i]] : "?");
}

static void reconcileIs(const char *what, const BRAssetRow *rows, size_t n, const int *states, size_t ownedCount, int prune,
                        size_t wantPruned, const char *want)
{
    char got[4096];
    size_t pruned = 99;
    BRAssetHoldingsStatus st = BRAssetReconcileRows(rows, n, OWNED, ownedCount, prune, states, CH, &pruned);

    showChanges(got, CH, n);
    ck(st == BR_ASSET_HOLDINGS_OK && pruned == wantPruned && strcmp(got, want) == 0, "%s: pruned %zu [%s] (want %zu [%s])",
       what, pruned, got, wantPruned, want);
}

static void reconcile_prunes_unowned(void)   // prunes_rows_at_addresses_we_do_not_own
{
    BRAssetRow r[2];
    int st[2] = { 1, 1 };

    r[0] = row("aa", 0, OWN, 3, 0, "asset1", 5, 0, NAT, UNC);
    r[1] = row("bb", 0, FOREIGN, 3, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("a row at an address we do not own is deleted", r, 2, st, 1, 1, 1, "keep delete");
}

static void reconcile_unknown_owned(void)   // an_unknown_owned_set_deletes_nothing
{
    BRAssetRow r[1];
    int st[1] = { 1 };

    r[0] = row("bb", 0, FOREIGN, 3, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("an empty owned set deletes nothing", r, 1, st, 0, 1, 0, "keep");
}

static void reconcile_scriptless_kept(void)   // a_row_with_no_script_is_never_pruned
{
    BRAssetRow r[1];
    int st[1] = { 1 };

    r[0] = row("cc", 0, NULL, 0, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("a row with no script", r, 1, st, 1, 1, 0, "keep");
}

static void reconcile_spent_flags(void)   // reconciles_the_spent_flag_from_the_native_wallet
{
    BRAssetRow r[2];
    int st[2] = { BR_ASSET_STATE_HELD, BR_ASSET_STATE_SPENT };

    r[0] = row("aa", 0, OWN, 3, 0, "asset1", 5, 1, NAT, UNC);
    r[1] = row("dd", 0, OWN, 3, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("aa held (was spent), dd spent", r, 2, st, 1, 1, 0, "unspent spent");
}

static void reconcile_undetected_left(void)   // an_undetected_outpoint_leaves_its_flag_alone
{
    BRAssetRow r[1];
    int st[1] = { BR_ASSET_STATE_UNDETECTED };

    r[0] = row("aa", 0, OWN, 3, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("UNDETECTED", r, 1, st, 1, 1, 0, "keep");
    r[0].spent = 1;
    reconcileIs("UNDETECTED, flagged spent", r, 1, st, 1, 1, 0, "keep");
}

static void reconcile_prune_waits(void)   // ownership_prune_waits_while_not_allowed
{
    BRAssetRow r[1];
    int st[1] = { BR_ASSET_STATE_SPENT };

    r[0] = row("bb", 0, FOREIGN, 3, 0, "asset1", 5, 0, NAT, UNC);
    reconcileIs("the gate is shut: the spent pass still runs", r, 1, st, 1, 0, 0, "spent");
}

static void reconcile_rules(void)
{
    BRAssetRow r[4];
    int st[4] = { BR_ASSET_STATE_CONFLICTED, BR_ASSET_STATE_HELD, BR_ASSET_STATE_SPENT, BR_ASSET_STATE_HELD };

    r[0] = row("aa", 0, OWN, 3, 0, "asset1", 5, 1, NAT, UNC);
    r[1] = row("bb", 0, FOREIGN, 3, 0, "asset1", 5, 1, NAT, UNC);
    r[2] = row("cc", 1, OWN, 3, 0, NULL, 0, 1, BAK, VER);
    r[3] = row("dd", 0, FOREIGN, 3, 0, "asset1", 5, 0, NAT, UNC);
    r[3].isAsset = 0;
    reconcileIs("conflicted kept, foreign deleted, already spent, a DGB row unseen", r, 4, st, 1, 1, 1, "keep delete keep keep");
}

static void clear_dead_send(void)
{
    BRAssetRow r[5];
    BRAssetTxOutput o[5];
    size_t removed = 99;
    char got[4096];
    BRAssetHoldingsStatus s;

    r[0] = row("dead", 0, FOREIGN, 3, 0, "La1", 5, 0, NAT, UNC);   // the recipient marker: not ours to delete
    r[1] = row("dead", 1, OWN, 3, 0, "La1", 4, 0, NAT, UNC);       // our asset change
    r[2] = row("live", 1, OWN, 3, 0, "La1", 4, 0, NAT, UNC);       // another transaction
    r[3] = row("dead", 2, OWN, 3, 0, NULL, 0, 0, NAT, UNC);        // a DGB output at the same txid
    r[3].isAsset = 0;
    r[4] = row("dead", 3, OWN, 3, 0, "La1", 0, 0, NAT, UNC);       // output 3, whose script line is empty
    o[0].vout = 0; o[0].script = FOREIGN; o[0].scriptLen = 3;
    o[1].vout = 1; o[1].script = OWN; o[1].scriptLen = 3;
    o[2].vout = 2; o[2].script = OWN; o[2].scriptLen = 3;
    o[3].vout = 3; o[3].script = OWN; o[3].scriptLen = 0;
    o[4].vout = 1; o[4].script = OWN; o[4].scriptLen = 3;          // the same output twice: deleted once
    s = BRAssetClearDeadSend(r, 5, "dead", o, 5, OWNED, 1, CH, &removed);
    showChanges(got, CH, 5);
    ck(s == BR_ASSET_HOLDINGS_OK && removed == 1 && strcmp(got, "keep delete keep keep keep") == 0,
       "a dead send: only our own asset row of it, once (%zu [%s])", removed, got);
    s = BRAssetClearDeadSend(r, 5, "dead", o, 5, OWNED, 0, CH, &removed);
    ck(s == BR_ASSET_HOLDINGS_OK && removed == 0, "no owned script: nothing");
    s = BRAssetClearDeadSend(r, 5, "DEAD", o, 5, OWNED, 1, CH, &removed);
    ck(s == BR_ASSET_HOLDINGS_OK && removed == 0, "the txid is matched as written");
}

// ---- persist (AssetProvenanceTaggingTest, AssetSourceFixTest, UnbackedAssetCreditTest) ------------

static BRAssetDetectedOutput detected(const char *txid, int32_t vout, int64_t claimed, int outgoing, const BRAssetOutputCredit *c)
{
    BRAssetDetectedOutput d;

    memset(&d, 0, sizeof(d));
    d.txid = txid; d.vout = vout; d.script = NULL; d.scriptLen = 0; d.satoshis = 6000; d.blockHeight = 0;
    d.placeholderAssetId = "La1234"; d.claimedQuantity = claimed; d.isOutgoingUnconfirmed = outgoing; d.credit = c;
    return d;
}

// The issuance of AssetProvenanceTaggingTest, decoded by the core's own reader: its totalQuantity.
static int64_t decodedIssuanceQuantity(void)
{
    static const uint8_t s[] = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x05, 0x0a, 0x10 };
    BRAssetHeader h;

    if (BRAssetDecodeHeader(s, sizeof(s), &h) != BR_ASSET_SCRIPT_ASSET || h.operation != DA_ISSUANCE || ! h.locked ||
        ! h.hasTotalQuantity) {
        ck(0, "the fixture 6a06444103050a10 decodes to a locked issuance");
        return 10;
    }
    return h.totalQuantity;
}

static void persist_new_native(void)   // new_owned_output_is_inserted_as_NATIVE
{
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 1, decodedIssuanceQuantity(), 0, NULL);
    BRAssetPersist p = BRAssetPersistDetectedOutput(&d, NULL);

    ck(p.action == BR_ASSET_PERSIST_INSERT && p.row.vout == 1 && p.row.source == NAT, "a new outpoint is inserted NATIVE");
    ck(p.row.isAsset && ! p.row.spent && p.row.assetQuantity == 10 && p.row.credit == UNC && strcmp(p.row.assetId, "La1234") == 0 &&
       p.row.txid == d.txid && p.row.satoshis == 6000 && p.row.blockHeight == 0 && ! p.unresolved,
       "at the claimed 10, UNCHECKED, under the placeholder");
}

static void persist_existing_retagged(void)   // existing_output_is_retagged_not_reinserted
{
    BRAssetRow e = row(tx(0, "", 'a'), 1, NULL, 0, 800000, "La1234", 10, 0, BAK, UNC);
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 1, decodedIssuanceQuantity(), 0, NULL);
    BRAssetPersist p = BRAssetPersistDetectedOutput(&d, &e);

    ck(p.action == BR_ASSET_PERSIST_UPDATE && p.row.source == NAT, "re-tagged NATIVE, not re-inserted");
    ck(p.row.assetQuantity == 10 && p.row.blockHeight == 800000 && ! p.row.spent && p.row.credit == UNC, "nothing else changes, nothing lowered");
}

static void persist_outgoing_unconfirmed(void)   // outgoing_unconfirmed_does_not_persist_owned_change, ..._retag_existing_row_either
{
    BRAssetRow e = row(tx(0, "", 'a'), 1, NULL, 0, 800000, "La1234", 10, 0, BAK, UNC);
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 1, 10, 1, NULL);
    BRAssetPersist p = BRAssetPersistDetectedOutput(&d, NULL);

    ck(p.action == BR_ASSET_PERSIST_NOTHING && ! p.unresolved, "our own unconfirmed send's change: nothing inserted");
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.action == BR_ASSET_PERSIST_NOTHING && ! p.unresolved, "nor an existing row re-tagged or raised");
}

static void persist_confirmed_and_incoming(void)   // confirmed_outgoing_persists_owned_output, incoming_unconfirmed_receive_still_persists
{
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 1, 10, 0, NULL);
    BRAssetPersist p = BRAssetPersistDetectedOutput(&d, NULL);

    ck(p.action == BR_ASSET_PERSIST_INSERT && p.row.vout == 1, "a confirmed send's output, or a receive, is written");
}

static void persist_unknown_unchecked(void)   // with_no_indexer_an_unknown_receipt_stays_unchecked_and_uncounted
{
    BRAssetOutputCredit unknown;
    BRAssetDetectedOutput d;
    BRAssetPersist p;
    int st[1] = { 1 };

    memset(&unknown, 0, sizeof(unknown));
    d = detected(tx(0, "", 'a'), 0, 5, 0, &unknown);
    d.script = OWN; d.scriptLen = 3;
    p = BRAssetPersistDetectedOutput(&d, NULL);
    ck(p.action == BR_ASSET_PERSIST_INSERT && p.row.credit == UNC && p.row.assetQuantity == 5, "an unknown credit: UNCHECKED at the claim");
    balances("and it counts for nothing", &p.row, 1, st, OWNED, 1, "");
}

static void persist_quantity_rules(void)
{
    BRAssetRow e = row(tx(0, "", 'a'), 0, OWN, 3, 0, "unresolved:t", 10, 0, BAK, UNC);
    BRAssetOutputCredit c;
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 0, 5, 0, NULL);
    BRAssetPersist p = BRAssetPersistDetectedOutput(&d, &e);

    ck(p.action == BR_ASSET_PERSIST_UPDATE && p.row.assetQuantity == 10 && p.unresolved, "a smaller claim never lowers an unsettled row");
    d.claimedQuantity = 12;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.row.assetQuantity == 12 && p.row.credit == UNC, "a strictly larger claim raises it");
    memset(&c, 0, sizeof(c));
    c.kind = BR_ASSET_OUTPUT_HOLDS; c.hasUnits = 1; c.assetId = "La9"; c.count = 3; c.label = "La9";
    d.credit = &c;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.row.assetQuantity == 3 && p.row.credit == BR_ASSET_CREDIT_BACKED && strcmp(p.row.assetId, "La9") == 0 && ! p.unresolved,
       "a decided credit settles it, lower or not, BACKED");
    e.assetId = NULL;
    d.credit = NULL;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.row.assetId == NULL && p.unresolved, "a row with no asset id is unresolved");
    e.isAsset = 0;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.action == BR_ASSET_PERSIST_INSERT && strcmp(p.row.assetId, "La1234") == 0, "a DGB row at the outpoint is no asset row: insert");
    d.placeholderAssetId = "unresolved:cc";
    p = BRAssetPersistDetectedOutput(&d, NULL);
    ck(p.unresolved, "a placeholder id is unresolved");
}

static void settledIs(const char *what, int kind, int hasUnits, const char *id, int64_t count, const char *label,
                      BRAssetCredit source, int want, const char *wantId, int64_t wantQty, BRAssetCredit wantCredit)
{
    BRAssetOutputCredit c;
    BRAssetSettledRow s;
    int got;

    memset(&c, 0, sizeof(c));
    memset(&s, 0, sizeof(s));
    c.kind = (BRAssetOutputCreditKind)kind; c.hasUnits = hasUnits; c.assetId = id; c.count = count; c.label = label;
    got = BRAssetSettledRowOf(&c, "PH", source, &s);
    ck(got == want && (! want || (s.assetId && strcmp(s.assetId, wantId) == 0 && s.quantity == wantQty && s.credit == wantCredit)),
       "%s", what);
}

static void settled_row_of(void)
{
    settledIs("holds units: their asset and count", BR_ASSET_OUTPUT_HOLDS, 1, "La1", 7, "La1", VER, 1, "La1", 7, VER);
    settledIs("holds nothing with a label: the label at zero", BR_ASSET_OUTPUT_HOLDS, 0, NULL, 0, "La2", BR_ASSET_CREDIT_BACKED,
              1, "La2", 0, BR_ASSET_CREDIT_BACKED);
    settledIs("holds nothing, no label: the placeholder", BR_ASSET_OUTPUT_HOLDS, 0, NULL, 0, NULL, VER, 1, "PH", 0, VER);
    settledIs("mixed: the placeholder, MIXED", BR_ASSET_OUTPUT_MIXED, 0, NULL, 0, NULL, VER, 1, "PH", 0, BR_ASSET_CREDIT_MIXED);
    settledIs("unknown: nothing", BR_ASSET_OUTPUT_UNKNOWN, 1, "La1", 7, "La1", VER, 0, "", 0, VER);
    settledIs("no defined kind: nothing", 9, 1, "La1", 7, "La1", VER, 0, "", 0, VER);
}

// ---- settle (UnbackedAssetCreditTest) -----------------------------------------------------------

static BRAssetDeliverable capOf(const char *hexScript, int32_t vout, int32_t outputCount)
{
    uint8_t s[64];
    size_t n = strlen(hexScript) / 2, i;
    BRAssetHeader h;
    BRAssetTransferInstruction insts[BR_ASSET_MAX_INSTRUCTIONS];
    BRAssetDeliverable none = { 0, 0 };
    unsigned v;

    for (i = 0; i < n; i++) { sscanf(hexScript + 2 * i, "%2x", &v); s[i] = (uint8_t)v; }
    if (BRAssetDecodeInstructions(s, n, &h, insts, N(insts)) != BR_ASSET_SCRIPT_ASSET) { ck(0, "%s decodes", hexScript); return none; }
    return BRAssetMaxDeliverable(&h, insts, h.instructionCount, vout, outputCount);
}

#define CONTROL "6a06444103150005"   // 5 units to output 0, three outputs

static BRAssetStackAcceptance acceptOne(const char *id, int64_t count, BRAssetDeliverable bound)
{
    BRAssetUnits u[1];

    u[0].assetId = id; u[0].count = count; u[0].ruleFree = 0;
    return BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 1, bound, "unresolved:t");
}

static void settle_recheck_interval(void)   // no_answer_is_asked_again_only_after_the_recheck_interval
{
    BRAssetRow r = row(tx(0, "", 'a'), 0, OWN, 3, 0, "unresolved:t", 5, 0, NAT, UNC);
    BRAssetStackAcceptance a;
    int st[1] = { 1 };

    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NOW, "first: asked (at 0)");
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_UNAVAILABLE, NULL, 0, capOf(CONTROL, 0, 3), "unresolved:t");
    ck(a.verdict == BR_ASSET_ACCEPT_NO_ANSWER, "Unavailable: no answer, the row stays");
    ck(BRAssetIndexerAskDecide(&r, 1, 0, 1000) == BR_ASSET_ASK_LATER, "at 1000: asked again too soon");
    ck(BRAssetIndexerAskDecide(&r, 1, 0, 600000) == BR_ASSET_ASK_NOW, "after the interval: asked");
    a = acceptOne("Laxx", 5, capOf(CONTROL, 0, 3));
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && strcmp(a.row.assetId, "Laxx") == 0 && a.row.quantity == 5 && a.row.credit == VER &&
       a.labelsHistory, "Found(X 5): settled VERIFIED, X x5");
    r.assetId = a.row.assetId; r.assetQuantity = a.row.quantity; r.credit = a.row.credit;
    balances("the balance", &r, 1, st, OWNED, 1, "Laxx=5/1");
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 1200000) == BR_ASSET_ASK_NO, "settled: never asked again");
}

static void settle_over_bound(void)   // an_indexer_count_above_what_the_instructions_deliver_is_not_believed
{
    BRAssetDeliverable b0 = capOf(CONTROL, 0, 3), b2 = capOf(CONTROL, 2, 3);
    BRAssetStackAcceptance a;
    BRAssetRow r[2];
    int st[2] = { 1, 1 };

    ck(! b0.unbounded && b0.max == 5, "the control's bound on output 0: 5");
    ck(b2.unbounded, "the last output also takes leftovers: no bound");
    a = acceptOne("Laxx", 1000000, b0);
    ck(a.verdict == BR_ASSET_ACCEPT_NOT_BELIEVED, "1,000,000 on output 0: not believed, the row stays UNCHECKED");
    r[0] = row(tx(0, "", 'a'), 0, OWN, 3, 0, "unresolved:t", 5, 0, NAT, UNC);
    balances("and counts for nothing", r, 1, st, OWNED, 1, "");
    a = acceptOne("Laxx", 5, b0);
    r[0].assetId = a.row.assetId; r[0].assetQuantity = a.row.quantity; r[0].credit = a.row.credit;
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED, "exactly the bound is believed");
    a = acceptOne("Laxx", 1000, b2);
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED, "the last output is not capped");
    r[1] = row(tx(0, "", 'a'), 2, OWN, 3, 0, a.row.assetId, a.row.quantity, 0, NAT, a.row.credit);
    balances("X 1005", r, 2, st, OWNED, 1, "Laxx=1005/2");
    // The bounds the Kotlin test pins through BRAssetMaxDeliverable, and what they let through.
    b0 = capOf(CONTROL, 1, 4);
    ck(! b0.unbounded && b0.max == 0, "output 1 of the control is named by nothing: 0");
    ck(acceptOne("Laxx", 1, b0).verdict == BR_ASSET_ACCEPT_NOT_BELIEVED, "so one unit there is not believed");
    b0 = capOf("6a0d444103155fffffffffffffffff", 2, 3);
    ck(! b0.unbounded && b0.max == 0, "uncountable instructions: 0, the last output included");
    a = acceptOne("Laxx", 0, b0);
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && a.row.quantity == 0 && strcmp(a.row.assetId, "unresolved:t") == 0 && ! a.labelsHistory,
       "under a bound of 0, an answer of nothing still settles, at zero, under the placeholder");
}

static void settle_settled_final(void)   // a_settled_row_is_final
{
    BRAssetRow e = row(tx(0, "", 'a'), 0, OWN, 3, 0, "Laxx", 5, 0, NAT, VER);
    BRAssetOutputCredit unknown;
    BRAssetDetectedOutput d;
    BRAssetPersist p;

    memset(&unknown, 0, sizeof(unknown));
    d = detected(tx(0, "", 'a'), 0, 1000000, 0, &unknown);
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.action == BR_ASSET_PERSIST_UPDATE && strcmp(p.row.assetId, "Laxx") == 0 && p.row.assetQuantity == 5 && p.row.credit == VER,
       "re-detected with a different claim: nothing changes");
    ck(BRAssetIndexerAskDecide(&p.row, 0, 0, 600000) == BR_ASSET_ASK_NO, "and nobody is asked");
    e.credit = BR_ASSET_CREDIT_MIXED;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.row.assetQuantity == 5 && p.row.credit == BR_ASSET_CREDIT_MIXED, "MIXED is final too");
    e.credit = BR_ASSET_CREDIT_BACKED;
    unknown.kind = BR_ASSET_OUTPUT_HOLDS; unknown.hasUnits = 1; unknown.assetId = "Layy"; unknown.count = 9;
    p = BRAssetPersistDetectedOutput(&d, &e);
    ck(p.row.assetQuantity == 5 && strcmp(p.row.assetId, "Laxx") == 0, "and BACKED, whatever the new credit says");
}

static void settle_accept_rules(void)
{
    BRAssetUnits u[3];
    BRAssetDeliverable unb = { 1, 0 }, b3 = { 0, 3 };
    BRAssetStackAcceptance a;

    u[0].assetId = "Laxx"; u[0].count = 2; u[1].assetId = "Layy"; u[1].count = 1; u[2].assetId = "Laxx"; u[2].count = 0;
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 2, b3, "unresolved:t");
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && a.row.credit == BR_ASSET_CREDIT_MIXED && a.row.quantity == 0 &&
       strcmp(a.row.assetId, "unresolved:t") == 0 && ! a.labelsHistory, "two assets: MIXED under the placeholder");
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u + 2, 1, b3, "unresolved:t");
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && a.row.credit == VER && a.row.quantity == 0 && strcmp(a.row.assetId, "unresolved:t") == 0,
       "entries of zero only: holds nothing");
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, NULL, 0, b3, "La1");
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && ! a.labelsHistory && strcmp(a.row.assetId, "La1") == 0, "an empty stack: nothing, no label");
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_NOT_UNSPENT, u, 1, b3, "La1");
    ck(a.verdict == BR_ASSET_ACCEPT_NO_ANSWER, "not unspent: no answer");
    u[0].count = INT64_MAX; u[1].assetId = "Laxx"; u[1].count = 1;
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 2, unb, "La1");
    ck(a.verdict == BR_ASSET_ACCEPT_NO_ANSWER, "a sum past INT64_MAX: no answer");
    u[1].count = 0;
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 2, unb, "La1");
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && a.row.quantity == INT64_MAX && a.labelsHistory, "INT64_MAX unbounded: settled");
    b3.max = INT64_MAX;
    ck(BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 2, b3, "La1").verdict == BR_ASSET_ACCEPT_SETTLED, "within a bound of INT64_MAX");
    b3.max = INT64_MAX - 1;
    ck(BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 2, b3, "La1").verdict == BR_ASSET_ACCEPT_NOT_BELIEVED, "past INT64_MAX - 1");
    u[0].assetId = "unresolved:x"; u[0].count = 4;
    a = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 1, unb, "La1");
    ck(a.verdict == BR_ASSET_ACCEPT_SETTLED && ! a.labelsHistory, "an unresolved id is not listed in the history");
    ck(BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u, 1, unb, NULL).verdict == BR_ASSET_ACCEPT_NO_ANSWER, "no placeholder: no answer");
}

static void ask_decide_rules(void)
{
    BRAssetRow r = row(tx(0, "", 'a'), 0, OWN, 3, 0, "unresolved:t", 5, 0, NAT, UNC);

    ck(BRAssetIndexerAskDecide(&r, 1, 0, 59999) == BR_ASSET_ASK_LATER && BRAssetIndexerAskDecide(&r, 1, 0, 60000) == BR_ASSET_ASK_NOW,
       "the interval is 60000 ms");
    ck(BRAssetIndexerAskDecide(&r, 1, 1000, 0) == BR_ASSET_ASK_LATER, "a clock that went back: later");
    ck(BRAssetIndexerAskDecide(&r, 1, INT64_MIN, INT64_MAX) == BR_ASSET_ASK_LATER, "INT64_MAX - INT64_MIN wraps to -1: later");
    ck(BRAssetIndexerAskDecide(&r, 1, INT64_MAX, INT64_MIN) == BR_ASSET_ASK_LATER, "INT64_MIN - INT64_MAX wraps to 1: later");
    ck(BRAssetIndexerAskDecide(&r, 1, 0, INT64_MAX) == BR_ASSET_ASK_NOW, "INT64_MAX - 0: now");
    ck(BRAssetIndexerAskDecide(&r, 1, -1, INT64_MAX) == BR_ASSET_ASK_LATER, "INT64_MAX - (-1) wraps negative: later");
    ck(BRAssetIndexerAskDecide(&r, 1, INT64_MIN, -60001) == BR_ASSET_ASK_NOW, "a large positive difference: now");
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NOW, "never asked: now");
    r.spent = 1;
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NO, "a spent row: no");
    r.spent = 0; r.isAsset = 0;
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NO, "no asset row: no");
    r.isAsset = 1; r.credit = (BRAssetCredit)9;
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NOW, "a credit of no defined state is not settled: now");
    ck(BRAssetIndexerAskDecide(NULL, 0, 0, 0) == BR_ASSET_ASK_NO, "no row: no");
}

// ---- balances, this suite's own -------------------------------------------------------------------

static void balances_sums_saturate(void)
{
    BRAssetRow r[6];
    int st[6] = { 1, 1, 1, 1, 1, 1 };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", INT64_MAX, 0, NAT, VER);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, "La1", INT64_MAX, 0, NAT, VER);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 1, "La2", INT64_MIN, 0, NAT, VER);
    r[3] = row(tx(3, "", '4'), 0, OWN, 3, 1, "La2", -1, 0, NAT, VER);
    r[4] = row(tx(4, "", '5'), 0, OWN, 3, 1, "La1", -5, 0, NAT, VER);
    r[5] = row(tx(5, "", '6'), 0, OWN, 3, 1, "La2", 7, 0, NAT, VER);
    balances("past INT64_MAX stops there, below INT64_MIN stops there, and goes on from the end", r, 6, st, OWNED, 1,
             "La1=9223372036854775802/2 La2=-9223372036854775801/1");
    balances("two INT64_MAX", r, 2, st, OWNED, 1, "La1=9223372036854775807/2");
}

static void balances_zero_rows(void)
{
    BRAssetRow r[3];
    int st[3] = { 1, 1, 1 };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", 0, 0, NAT, VER);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, "La2", 4, 0, NAT, VER);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 1, "La2", 0, 0, NAT, BR_ASSET_CREDIT_BACKED);
    balances("a send's plain change holds none of the asset: listed at 0, no UTXO", r, 3, st, OWNED, 1, "La1=0/0 La2=4/1");
}

static void balances_unbacked(void)
{
    BRAssetRow r[4];
    int st[4] = { 1, 1, 1, 1 };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", 5, 0, NAT, UNC);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, "La1", 6, 0, NAT, BR_ASSET_CREDIT_MIXED);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 1, "La1", 7, 0, NAT, (BRAssetCredit)9);
    r[3] = row(tx(3, "", '4'), 0, OWN, 3, 1, "La2", 1, 0, NAT, BR_ASSET_CREDIT_BACKED);
    balances("UNCHECKED, MIXED and an undefined credit count for nothing", r, 4, st, OWNED, 1, "La2=1/1");
}

static void balances_spent_flag(void)
{
    BRAssetRow r[2];
    int st[2] = { 1, 1 };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", 5, 1, NAT, VER);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, "La1", 2, 0, NAT, VER);
    balances("a row flagged spent is skipped, whatever native says", r, 2, st, OWNED, 1, "La1=2/1");
}

static void balances_rows_seen(void)
{
    BRAssetRow r[5];
    int st[5] = { 1, 1, 1, 1, 1 };
    static char again[] = "La1";
    BRAssetScript two[2];
    static const uint8_t OWN2[] = { 0x51 };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", 5, 0, NAT, VER);
    r[0].isAsset = 0;
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, NULL, 2, 0, NAT, VER);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 1, again, 3, 0, BAK, VER);
    r[3] = row(tx(3, "", '4'), 0, OWN2, 1, 1, "La1", 4, 0, NAT, VER);
    r[4] = row(tx(4, "", '5'), 0, OWN, 3, 1, "", 1, 0, NAT, VER);
    balances("a DGB row and a row with no id are not seen; ids by their bytes", r, 5, st, OWNED, 1, "La1=3/1 =1/1");
    two[0].bytes = OWN2; two[0].len = 1; two[1].bytes = OWN; two[1].len = 3;
    balances("with a second owned script", r, 5, st, two, 2, "La1=7/2 =1/1");
    ck(HB[0].assetId == again, "a balance's id is the first counted row's pointer");
    two[0].bytes = NULL; two[0].len = 0;
    balances("an owned script of no bytes owns nothing", r, 5, st, two, 2, "La1=3/1 =1/1");
    r[2].script = NULL;
    balances("a row whose script is missing is not held", r, 5, st, two, 2, "=1/1");
}

// ---- the stored form ------------------------------------------------------------------------------

static uint8_t WB[BR_ASSET_ROW_WIRE_MAX + 16];

static int sameRow(const BRAssetRow *a, const BRAssetRow *b)
{
    if (strcmp(a->txid, b->txid) != 0 || a->vout != b->vout || a->scriptLen != b->scriptLen) return 0;
    if (a->scriptLen && memcmp(a->script, b->script, a->scriptLen) != 0) return 0;
    if ((a->assetId == NULL) != (b->assetId == NULL) || (a->assetId && strcmp(a->assetId, b->assetId) != 0)) return 0;
    return a->satoshis == b->satoshis && a->blockHeight == b->blockHeight && (a->isAsset != 0) == (b->isAsset != 0) &&
           a->assetQuantity == b->assetQuantity && (a->spent != 0) == (b->spent != 0) && a->source == b->source &&
           a->credit == b->credit;
}

static size_t hexTo(const char *hex, uint8_t *out)
{
    size_t n = 0;
    unsigned v;

    for (; hex[0] && hex[1]; hex += 2) {
        if (hex[0] == ' ') { hex--; continue; }
        sscanf(hex, "%2x", &v);
        out[n++] = (uint8_t)v;
    }
    return n;
}

static void wire_vectors(void)
{
    BRAssetRow r = row("ab", -2, OWN, 3, 700000, "La1", 5, 1, NAT, VER), d;
    uint8_t want[64];
    size_t wn = hexTo("01 07 01 02 feffffff 7017000000000000 60ae0a0000000000 0500000000000000 02 6162 00 03 4c6131 00 0300 010203",
                      want), w = 0;
    BRAssetHoldingsStatus s = BRAssetRowEncode(&r, WB, sizeof(WB), &w);

    ck(BRAssetRowWireLength(&r) == 46 && wn == 46, "a row of txid 2, id 3, script 3: 38 + 8 bytes");
    ck(s == BR_ASSET_HOLDINGS_OK && w == wn && memcmp(WB, want, wn) == 0, "its bytes, as the header's table lays them out");
    s = BRAssetRowDecode(want, wn, &d);
    ck(s == BR_ASSET_HOLDINGS_OK && sameRow(&r, &d) && d.txid == (const char *)want + 33 && d.script == want + 43,
       "read back, pointing into the bytes");
    r = row("", INT32_MIN, NULL, 0, INT64_MIN, NULL, INT64_MIN, 0, BAK, UNC);
    r.isAsset = 0; r.satoshis = INT64_MAX;
    wn = hexTo("01 00 00 00 00000080 ffffffffffffff7f 0000000000000080 0000000000000080 00 00 00 00 0000", want);
    s = BRAssetRowEncode(&r, WB, sizeof(WB), &w);
    ck(s == BR_ASSET_HOLDINGS_OK && w == 38 && wn == 38 && memcmp(WB, want, 38) == 0, "the int extremes, no id, no script: 38 bytes");
    s = BRAssetRowDecode(want, wn, &d);
    ck(s == BR_ASSET_HOLDINGS_OK && sameRow(&r, &d) && d.assetId == NULL && d.script == NULL && d.vout == INT32_MIN &&
       d.blockHeight == INT64_MIN && d.satoshis == INT64_MAX, "read back");
    r.assetId = "";
    s = BRAssetRowEncode(&r, WB, sizeof(WB), &w);
    ck(s == BR_ASSET_HOLDINGS_OK && w == 38 && WB[1] == 4 && BRAssetRowDecode(WB, w, &d) == BR_ASSET_HOLDINGS_OK && d.assetId &&
       d.assetId[0] == '\0', "an empty id is an id");
}

static void wire_limits(void)
{
    static char t255[256], a255[256], t256[257];
    static uint8_t s65535[65536];
    BRAssetRow r, d;
    size_t w = 0;

    memset(t255, 'f', 255); memset(a255, 'L', 255); memset(t256, 'f', 256);
    memset(s65535, 0xab, sizeof(s65535));
    r = row(t255, INT32_MAX, s65535, 65535, INT64_MAX, a255, INT64_MAX, 1, NAT, BR_ASSET_CREDIT_MIXED);
    ck(BRAssetRowWireLength(&r) == BR_ASSET_ROW_WIRE_MAX, "the longest row: BR_ASSET_ROW_WIRE_MAX");
    ck(BRAssetRowEncode(&r, WB, BR_ASSET_ROW_WIRE_MAX, &w) == BR_ASSET_HOLDINGS_OK && w == BR_ASSET_ROW_WIRE_MAX &&
       BRAssetRowDecode(WB, w, &d) == BR_ASSET_HOLDINGS_OK && sameRow(&r, &d), "stored and read back");
    ck(BRAssetRowEncode(&r, WB, BR_ASSET_ROW_WIRE_MAX - 1, &w) == BR_ASSET_HOLDINGS_CAPACITY && w == 0, "one byte short: CAPACITY");
    r.txid = t256;
    ck(BRAssetRowWireLength(&r) == 0 && BRAssetRowEncode(&r, WB, sizeof(WB), &w) == BR_ASSET_HOLDINGS_REFUSED, "a txid of 256: refused");
    r.txid = t255; r.assetId = t256;
    ck(BRAssetRowWireLength(&r) == 0, "an id of 256: refused");
    r.assetId = a255; r.scriptLen = 65536;
    ck(BRAssetRowWireLength(&r) == 0, "a script of 65536: refused");
    r.scriptLen = 3; r.script = NULL;
    ck(BRAssetRowWireLength(&r) == 0, "a script with no bytes: refused");
    r.script = s65535; r.source = (BRAssetSource)2;
    ck(BRAssetRowWireLength(&r) == 0, "a source of no defined kind: refused");
    r.source = NAT; r.credit = (BRAssetCredit)4;
    ck(BRAssetRowWireLength(&r) == 0, "a credit of no defined state: refused");
    r.credit = (BRAssetCredit)-1;
    ck(BRAssetRowWireLength(&r) == 0, "nor -1");
    r.credit = VER; r.txid = NULL;
    ck(BRAssetRowWireLength(&r) == 0, "no txid: refused");
    r.txid = t255; r.isAsset = 7; r.spent = -3;
    ck(BRAssetRowEncode(&r, WB, sizeof(WB), &w) == BR_ASSET_HOLDINGS_OK && WB[1] == 7 && BRAssetRowDecode(WB, w, &d) == BR_ASSET_HOLDINGS_OK &&
       d.isAsset == 1 && d.spent == 1, "any nonzero flag is stored as 1");
}

static void wire_refusals(void)
{
    BRAssetRow r = row("ab", 3, OWN, 3, 7, "La1", 5, 0, NAT, VER), d;
    uint8_t b[64];
    size_t w = 0, i;

    ck(BRAssetRowEncode(&r, b, sizeof(b), &w) == BR_ASSET_HOLDINGS_OK && w == 46, "the row");
    ck(BRAssetRowDecode(b, w, &d) == BR_ASSET_HOLDINGS_OK, "reads");
    ck(BRAssetRowDecode(b, w + 1, &d) == BR_ASSET_HOLDINGS_REFUSED && d.txid == NULL, "a byte after it: refused, the row zeroed");
    for (i = 0; i < w; i++) {
        if (BRAssetRowDecode(b, i, &d) != BR_ASSET_HOLDINGS_REFUSED) { ck(0, "a row cut to %zu bytes was read", i); return; }
    }
    ck(1, "every shorter prefix: refused");
#define FLIP(at, v, what) do { uint8_t o = b[at]; b[at] = (v); ck(BRAssetRowDecode(b, w, &d) == BR_ASSET_HOLDINGS_REFUSED, what); b[at] = o; } while (0)
    FLIP(0, 2, "version 2: refused");
    FLIP(0, 0, "version 0: refused");
    FLIP(1, 8 | 5, "an unknown flag: refused");
    FLIP(1, 1, "an id without its flag: refused");
    FLIP(2, 2, "source 2: refused");
    FLIP(3, 4, "credit 4: refused");
    FLIP(32, 3, "a txid length past its bytes: refused");
    FLIP(32, 1, "a txid length short of its terminator: refused");
    FLIP(34, 0, "a zero inside the txid: refused");
    FLIP(35, 1, "no txid terminator: refused");
    FLIP(36, 4, "an id length past its bytes: refused");
    FLIP(38, 0, "a zero inside the id: refused");
    FLIP(40, 1, "no id terminator: refused");
    FLIP(41, 4, "a script length past the bytes: refused");
    FLIP(41, 2, "a script length short of the bytes: refused");
    FLIP(42, 1, "a script length 256 too long: refused");
#undef FLIP
    ck(BRAssetRowDecode(NULL, 46, &d) == BR_ASSET_HOLDINGS_REFUSED && BRAssetRowDecode(b, w, NULL) == BR_ASSET_HOLDINGS_REFUSED,
       "no bytes, or nowhere to read them: refused");
    ck(BRAssetRowEncode(&r, NULL, 64, &w) == BR_ASSET_HOLDINGS_REFUSED && BRAssetRowEncode(&r, b, 64, NULL) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetRowEncode(NULL, b, 64, &w) == BR_ASSET_HOLDINGS_REFUSED, "no buffer, no count or no row: refused");
}

// ---- capacity, arguments, zero structs ----------------------------------------------------------

static void capacity(void)
{
    BRAssetRow r[3];
    int st[3] = { 1, 1, 1 };
    size_t n = 9, removed = 9;
    BRAssetStackAnswerEntry e[3];
    BRAssetStackAnswer a;
    BRAssetStackLookup lookup;
    BRAssetAbsence few[2];
    BRAssetAbsenceTable t;
    BRAssetTxLookup lk[3] = { BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NOT_HELD };

    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 0, "La1", 5, 0, NAT, VER);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 0, "La2", 5, 0, NAT, VER);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 0, "La1", 5, 0, NAT, VER);
    HB[0].assetId = "untouched";
    ck(BRAssetHeldBalances(r, 3, st, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_CAPACITY && n == 0 && strcmp(HB[0].assetId, "untouched") == 0,
       "two assets into one balance: CAPACITY, nothing written");
    ck(BRAssetHeldBalances(r, 3, st, OWNED, 1, HB, 2, &n) == BR_ASSET_HOLDINGS_OK && n == 2, "into two: OK");
    ck(BRAssetHeldBalances(r, 3, st, OWNED, 1, NULL, 0, &n) == BR_ASSET_HOLDINGS_CAPACITY, "into none: CAPACITY");
    st[0] = st[1] = st[2] = 0;
    ck(BRAssetHeldBalances(r, 3, st, OWNED, 1, NULL, 0, &n) == BR_ASSET_HOLDINGS_OK && n == 0, "nothing counts: none is room enough");

    e[0] = entry(1, "La1", 1, 1); e[1] = entry(1, "La2", 1, 2); e[2] = entry(1, "", 1, 3);
    a = answer(0, ASKED, 1, 0, 1, e, 2);
    ck(BRAssetStackAnswerRead(&a, ASKED, 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_CAPACITY && n == 0 &&
       lookup == BR_ASSET_STACK_UNAVAILABLE, "a stack of two into one: CAPACITY");
    ck(BRAssetStackAnswerRead(&a, ASKED, 0, SU, 2, &n, &lookup) == BR_ASSET_HOLDINGS_OK && n == 2 && lookup == BR_ASSET_STACK_FOUND, "into two");
    a.entryCount = 3;
    ck(BRAssetStackAnswerRead(&a, ASKED, 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK && lookup == BR_ASSET_STACK_UNAVAILABLE,
       "a malformed entry is no answer before it is a capacity matter");

    memset(few, 0, sizeof(few));
    t.entries = few; t.count = 0; t.capacity = 2;
    ck(BRAssetPruneRemovedNativeRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && t.count == 0 && removed == 0,
       "three counts into a table of two: CAPACITY, the table untouched");
    t.capacity = 0;
    lk[0] = lk[1] = lk[2] = BR_ASSET_TX_HELD;
    ck(BRAssetPruneRemovedNativeRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK, "nothing absent: a table of none is enough");
    lk[0] = BR_ASSET_TX_NOT_HELD;
    t.capacity = 1;
    ck(BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && t.count == 1, "one absent into one");
    lk[1] = BR_ASSET_TX_NOT_HELD;
    ck(BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 1 && CH[0] == BR_ASSET_ROW_DELETE &&
       t.count == 1 && strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].passes == 1,
       "a second, new, into a full table whose one count ends this pass: the first deleted, the second counted");
    lk[1] = BR_ASSET_TX_HELD; lk[2] = BR_ASSET_TX_NOT_HELD;
    ck(BRAssetPruneDeadBroadcastRows(r, 1, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && removed == 0 && t.count == 1 &&
       strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].passes == 1,
       "a dead pass keeps the count of a row it does not see: with a new one, CAPACITY, nothing counted");
    ck(BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_CAPACITY && t.count == 1,
       "row 1 seen present, rows 0 and 2 new: two counts at the end, into a table of one: CAPACITY");
    t.capacity = 2;
    ck(BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, CH, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 2 &&
       strcmp(t.entries[0].txid, r[1].txid) != 0 && strcmp(t.entries[1].txid, r[1].txid) != 0, "into two: row 1 forgotten, rows 0 and 2 counted");
}

static void arguments(void)
{
    BRAssetRow r[1];
    int st[1] = { 1 };
    size_t n = 9;
    BRAssetStackLookup lookup;
    BRAssetStackAnswer a = answer(0, ASKED, 1, 0, 1, NULL, 0);
    BRAssetTxLookup lk[1] = { BR_ASSET_TX_NOT_HELD };
    BRAssetAbsenceTable t;
    BRAssetPersist p;
    BRAssetDetectedOutput d = detected(tx(0, "", 'a'), 0, 1, 0, NULL);

    r[0] = row(tx(0, "", 'a'), 0, OWN, 3, 0, "La1", 5, 0, NAT, VER);
    ck(BRAssetHeldBalances(r, 1, st, OWNED, 1, HB, 1, NULL) == BR_ASSET_HOLDINGS_REFUSED, "balances: no count");
    ck(BRAssetHeldBalances(NULL, 1, st, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_REFUSED && n == 0, "balances: no rows");
    ck(BRAssetHeldBalances(r, 1, NULL, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_REFUSED, "balances: no states");
    ck(BRAssetHeldBalances(r, 1, st, NULL, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_REFUSED, "balances: no owned set");
    ck(BRAssetHeldBalances(r, 1, st, OWNED, 1, NULL, 1, &n) == BR_ASSET_HOLDINGS_REFUSED, "balances: room and no buffer");
    ck(BRAssetHeldBalances(NULL, 0, NULL, OWNED, 1, NULL, 0, &n) == BR_ASSET_HOLDINGS_OK && n == 0, "balances: no rows at all");
    ck(BRAssetReconcileRows(r, 1, OWNED, 1, 1, st, CH, NULL) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetReconcileRows(NULL, 1, OWNED, 1, 1, st, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetReconcileRows(r, 1, OWNED, 1, 1, NULL, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetReconcileRows(r, 1, OWNED, 1, 1, st, NULL, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetReconcileRows(r, 1, NULL, 1, 1, st, CH, &n) == BR_ASSET_HOLDINGS_REFUSED, "reconcile: each NULL refused");
    ck(BRAssetReconcileRows(NULL, 0, NULL, 0, 1, NULL, NULL, &n) == BR_ASSET_HOLDINGS_OK && n == 0, "reconcile: nothing");
    ck(BRAssetClearDeadSend(r, 1, NULL, NULL, 0, OWNED, 1, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetClearDeadSend(r, 1, "x", NULL, 1, OWNED, 1, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetClearDeadSend(r, 1, "x", NULL, 0, OWNED, 1, CH, NULL) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetClearDeadSend(r, 1, "x", NULL, 0, NULL, 1, CH, &n) == BR_ASSET_HOLDINGS_REFUSED, "clear: each NULL refused");
    ck(BRAssetPruneRemovedNativeRows(r, 1, lk, NULL, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetPruneRemovedNativeRows(r, 1, NULL, &TABLE, CH, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetPruneRemovedNativeRows(r, 1, lk, &TABLE, NULL, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetPruneRemovedNativeRows(r, 1, lk, &TABLE, CH, NULL) == BR_ASSET_HOLDINGS_REFUSED, "prune: each NULL refused");
    t.entries = NULL; t.count = 1; t.capacity = 4;
    ck(BRAssetPruneDeadBroadcastRows(r, 1, lk, &t, CH, &n) == BR_ASSET_HOLDINGS_REFUSED, "prune: a table that says it holds one in none");
    t.count = 5;
    ck(BRAssetPruneDeadBroadcastRows(r, 1, lk, &t, CH, &n) == BR_ASSET_HOLDINGS_REFUSED, "prune: a table past its capacity");
    t.count = 0;
    ck(BRAssetPruneDeadBroadcastRows(r, 1, lk, &t, CH, &n) == BR_ASSET_HOLDINGS_REFUSED, "prune: room and no entries");
    ck(BRAssetStackAnswerRead(&a, NULL, 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetStackAnswerRead(NULL, ASKED, 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetStackAnswerRead(&a, ASKED, 0, NULL, 1, &n, &lookup) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetStackAnswerRead(&a, ASKED, 0, SU, 1, NULL, &lookup) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetStackAnswerRead(&a, ASKED, 0, SU, 1, &n, NULL) == BR_ASSET_HOLDINGS_REFUSED, "stack read: each NULL refused");
    a.entryCount = 1;
    ck(BRAssetStackAnswerRead(&a, ASKED, 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_REFUSED, "stack read: entries counted and none given");
    a.entryCount = 0; a.txid = NULL;
    ck(BRAssetStackAnswerRead(&a, "", 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK && lookup == BR_ASSET_STACK_FOUND,
       "an absent txid reads as \"\", which only an empty asked txid matches");
    p = BRAssetPersistDetectedOutput(NULL, NULL);
    ck(p.action == BR_ASSET_PERSIST_NOTHING, "persist: no output");
    d.placeholderAssetId = NULL;
    ck(BRAssetPersistDetectedOutput(&d, NULL).action == BR_ASSET_PERSIST_NOTHING, "persist: no placeholder");
    d.placeholderAssetId = "La1"; d.txid = NULL;
    ck(BRAssetPersistDetectedOutput(&d, NULL).action == BR_ASSET_PERSIST_NOTHING, "persist: no txid");
    ck(! BRAssetSettledRowOf(NULL, "x", VER, NULL), "settled row: nothing");
    ck(! BRAssetHeldForDisplay(OWN, 3, NULL, 1, NAT, 1) && ! BRAssetHeldForDisplay(NULL, 3, OWNED, 1, NAT, 1) &&
       ! BRAssetRowCounts(NULL, OWNED, 1, 1), "held: no owned set, no script, no row");
    ck(BRAssetIdIsUnresolved(NULL) && BRAssetIdIsUnresolved("unresolved:") && BRAssetIdIsUnresolved("unresolved:abc") &&
       ! BRAssetIdIsUnresolved("unresolved") && ! BRAssetIdIsUnresolved("Unresolved:x") && ! BRAssetIdIsUnresolved(""),
       "unresolved: no id, or the prefix exactly");
}

// Edges the mutation pass over the header asked for: exact-size buffers, one-byte scripts, rows whose
// script or txid is missing, duplicate outpoints, and outputs left as the header wrote them.
static void edges(void)
{
    static const uint8_t A1[] = { 0x51 }, B1[] = { 0x52 };
    BRAssetScript one[1] = { { A1, 1 } }, zero[1] = { { OWN, 0 } };
    BRAssetRow r[4], x;
    int st[4] = { 1, 1, 1, 1 };
    size_t n = 9, i;
    BRAssetRowChange ch2[2], *heapCh;
    BRAssetTxOutput o[2];
    BRAssetAbsence dirty[4];
    BRAssetAbsenceTable t;
    BRAssetTxLookup lk[3];
    BRAssetStackAnswer a;
    BRAssetStackAnswerEntry e[1];
    BRAssetStackLookup lookup;
    uint8_t *exact, full[64], big[128];
    BRAssetDetectedOutput d;
    BRAssetPersist p;

    // Scripts of one byte are told apart by their byte.
    ck(BRAssetHeldForDisplay(A1, 1, one, 1, NAT, 1) && ! BRAssetHeldForDisplay(B1, 1, one, 1, NAT, 1), "one-byte scripts by their byte");
    ck(! BRAssetHeldForDisplay(OWN, 3, zero, 1, NAT, 1), "an owned entry of no bytes owns nothing");
    {   // A script is owned only whole: neither a prefix of an owned script nor one that extends it.
        static const uint8_t O4[] = { 1, 2, 3, 4 };
        BRAssetScript four[1] = { { O4, 4 } };

        ck(! BRAssetHeldForDisplay(OWN, 3, four, 1, NAT, 1) && ! BRAssetHeldForDisplay(O4, 4, OWNED, 1, NAT, 1) &&
           BRAssetHeldForDisplay(O4, 4, four, 1, NAT, 1), "01 02 03 against an owned 01 02 03 04, and the other way: not held");
        r[0] = row("pp", 0, OWN, 3, 0, "La1", 5, 0, NAT, VER);
        ck(BRAssetReconcileRows(r, 1, four, 1, 1, st, CH, &n) == BR_ASSET_HOLDINGS_OK && n == 1 && CH[0] == BR_ASSET_ROW_DELETE,
           "the reconcile deletes a row whose script is only a prefix of an owned one");
        o[0].vout = 0; o[0].script = OWN; o[0].scriptLen = 3;
        ck(BRAssetClearDeadSend(r, 1, "pp", o, 1, four, 1, CH, &n) == BR_ASSET_HOLDINGS_OK && n == 0 && CH[0] == BR_ASSET_ROW_KEEP,
           "the dead send: an output whose script is a prefix of an owned one deletes nothing");
        // A script of no bytes at a real pointer, against an owned entry of no bytes: never held.
        ck(! BRAssetHeldForDisplay(OWN, 0, zero, 1, NAT, 1), "an empty script is not held, even where an owned entry is empty");
        x = row(tx(0, "", 'a'), 0, OWN, 0, 1, "La1", 5, 0, NAT, VER);
        ck(! BRAssetRowCounts(&x, zero, 1, 1), "nor does its row count");
        ck(BRAssetHeldBalances(&x, 1, st, zero, 1, HB, N(HB), &n) == BR_ASSET_HOLDINGS_OK && n == 0, "nor reach a balance");
    }

    // BRAssetRowCounts on its own.
    x = row(tx(0, "", 'a'), 0, OWN, 3, 1, "La1", 5, 0, NAT, VER);
    ck(BRAssetRowCounts(&x, OWNED, 1, 1), "an asset row that counts");
    x.isAsset = 0;
    ck(! BRAssetRowCounts(&x, OWNED, 1, 1), "a DGB row never counts");
    x.isAsset = 1; x.assetId = NULL;
    ck(! BRAssetRowCounts(&x, OWNED, 1, 1), "a row with no id never counts");

    // Balances: capacity counts each asset once, whatever lies between.
    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 1, "La1", 5, 0, NAT, VER);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 1, "La2", 5, 0, NAT, VER);
    ck(BRAssetHeldBalances(r, 2, st, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_CAPACITY, "two assets, room for one: CAPACITY");
    r[1].assetId = "La1";
    ck(BRAssetHeldBalances(r, 2, st, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_OK && n == 1 && HB[0].quantity == 10, "one asset twice, room for one");
    r[1] = row(tx(1, "", '2'), 0, FOREIGN, 3, 1, "La9", 5, 0, NAT, VER);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 1, "La2", 5, 0, NAT, VER);
    ck(BRAssetHeldBalances(r, 3, st, OWNED, 1, HB, 1, &n) == BR_ASSET_HOLDINGS_CAPACITY, "a row that does not count between them: still CAPACITY");

    // Reconcile: a row with no txid, a row whose script is missing, a DGB row, duplicate outpoints.
    r[0] = row(NULL, 0, FOREIGN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[1] = row("bb", 0, NULL, 3, 0, "La1", 5, 0, NAT, UNC);
    r[2] = row(NULL, 1, FOREIGN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[2].isAsset = 0;
    r[3] = row("cc", 0, FOREIGN, 3, 0, "La1", 5, 0, NAT, UNC);
    reconcileIs("no txid: deleted alone; a missing script: kept; a DGB row: unseen; then a foreign row", r, 4, st, 1, 1, 2,
                "delete keep keep delete");
    r[1] = row("bb", 0, NULL, 1, 0, "La1", 5, 0, NAT, UNC);
    ck(BRAssetReconcileRows(r + 1, 1, one, 1, 1, st, CH, &n) == BR_ASSET_HOLDINGS_OK && n == 0 && CH[0] == BR_ASSET_ROW_KEEP,
       "a one-byte script that is missing is kept");
    r[0] = row("dd", 0, FOREIGN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[1] = row("dd", 0, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    reconcileIs("the table deletes by outpoint: both rows at it", r, 2, st, 1, 1, 2, "delete delete");
    heapCh = (BRAssetRowChange *)malloc(2 * sizeof(BRAssetRowChange));
    ck(heapCh && BRAssetReconcileRows(r, 2, OWNED, 1, 1, st, heapCh, &n) == BR_ASSET_HOLDINGS_OK, "changes of exactly the rows' size");
    free(heapCh);

    // The dead send: arguments, exact sizes, outputs with no bytes.
    ck(BRAssetClearDeadSend(NULL, 0, "x", NULL, 0, NULL, 0, NULL, &n) == BR_ASSET_HOLDINGS_OK && n == 0, "clear: nothing at all");
    ck(BRAssetClearDeadSend(r, 2, "x", NULL, 0, OWNED, 1, NULL, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetClearDeadSend(NULL, 1, "x", NULL, 0, OWNED, 1, ch2, &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetClearDeadSend(r, 1, "x", NULL, 0, OWNED, 1, NULL, &n) == BR_ASSET_HOLDINGS_REFUSED, "clear: rows without changes, or none");
    r[0] = row("dd", 0, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[1] = row("dd", 1, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    o[0].vout = 0; o[0].script = NULL; o[0].scriptLen = 3;
    o[1].vout = 1; o[1].script = OWN; o[1].scriptLen = 0;
    heapCh = (BRAssetRowChange *)malloc(2 * sizeof(BRAssetRowChange));
    ck(heapCh && BRAssetClearDeadSend(r, 2, "dd", o, 2, zero, 1, heapCh, &n) == BR_ASSET_HOLDINGS_OK && n == 0 &&
       heapCh[0] == BR_ASSET_ROW_KEEP && heapCh[1] == BR_ASSET_ROW_KEEP, "outputs whose script is missing or empty delete nothing");
    free(heapCh);

    // The prunes: arguments, a table with no room and no entries, capacity counted past a present row,
    // the txid as stored in a dirty table.
    t.entries = NULL; t.count = 0; t.capacity = 0;
    lk[0] = lk[1] = lk[2] = BR_ASSET_TX_HELD;
    r[0] = row(tx(0, "", '1'), 0, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[1] = row(tx(1, "", '2'), 0, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    r[2] = row(tx(2, "", '3'), 0, OWN, 3, 0, "La1", 5, 0, NAT, UNC);
    ck(BRAssetPruneRemovedNativeRows(r, 3, lk, &t, CH, &n) == BR_ASSET_HOLDINGS_OK && n == 0, "a table of nothing, nothing absent: OK");
    ck(BRAssetPruneRemovedNativeRows(NULL, 0, NULL, &t, NULL, &n) == BR_ASSET_HOLDINGS_OK, "no rows: OK");
    memset(dirty, 0xff, sizeof(dirty));
    t.entries = dirty; t.count = 0; t.capacity = 1;
    lk[1] = lk[2] = BR_ASSET_TX_NOT_HELD;
    ck(BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, CH, &n) == BR_ASSET_HOLDINGS_CAPACITY && t.count == 0,
       "a present row first, then two absent into room for one: CAPACITY");
    t.capacity = 4;
    heapCh = (BRAssetRowChange *)malloc(3 * sizeof(BRAssetRowChange));
    ck(heapCh && BRAssetPruneDeadBroadcastRows(r, 3, lk, &t, heapCh, &n) == BR_ASSET_HOLDINGS_OK && t.count == 2 &&
       strlen(t.entries[0].txid) == 64 && strcmp(t.entries[0].txid, r[1].txid) == 0 && t.entries[0].passes == 1,
       "a count's txid is stored whole and terminated, in a table that was not zeroed");
    free(heapCh);

    // The indexer's reply: ASCII case through Z, the count zeroed on no answer, entries rule-free unknown.
    e[0] = entry(1, "La1", 1, 5);
    a = answer(0, "AZAZ", 1, 0, 1, e, 1);
    SU[0].ruleFree = 7;
    ck(BRAssetStackAnswerRead(&a, "azaz", 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK && lookup == BR_ASSET_STACK_FOUND && n == 1 &&
       SU[0].ruleFree == 0, "A..Z match a..z; an indexer entry is not known to be rule-free");
    a.txid = "AZA[";
    n = 9;
    ck(BRAssetStackAnswerRead(&a, "aza{", 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK && lookup == BR_ASSET_STACK_UNAVAILABLE && n == 0,
       "[ is not { : no answer, and the count is 0");
    a.txid = "AZAZ"; a.vout = 1;
    n = 9;
    ck(BRAssetStackAnswerRead(&a, "azaz", 0, SU, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK && n == 0, "another vout: the count is 0");

    // Persist: an inserted row carries the output's script.
    d = detected(tx(0, "", 'a'), 0, 5, 0, NULL);
    d.script = OWN; d.scriptLen = 3; d.satoshis = 777; d.blockHeight = 9;
    p = BRAssetPersistDetectedOutput(&d, NULL);
    ck(p.row.script == OWN && p.row.scriptLen == 3 && p.row.satoshis == 777 && p.row.blockHeight == 9 && p.row.spent == 0,
       "an inserted row is the output's: script, value, height, unspent");

    // The stored form: exact buffers, a missing one-byte script, an id of one byte without its flag.
    x = row("", 0, NULL, 0, 0, NULL, 0, 0, BAK, UNC);
    ck(BRAssetRowWireLength(&x) == 38, "no txid bytes, no id, no script: 38");
    exact = (uint8_t *)malloc(38);
    ck(exact && BRAssetRowEncode(&x, exact, 38, &n) == BR_ASSET_HOLDINGS_OK && n == 38, "into exactly 38 bytes");
    free(exact);
    x.scriptLen = 1;
    ck(BRAssetRowWireLength(&x) == 0, "a one-byte script that is missing: refused");
    x = row(tx(5, "", 'e'), 3, OWN, 3, 7, "La1", 5, 0, NAT, VER);
    ck(BRAssetRowEncode(&x, big, sizeof(big), &n) == BR_ASSET_HOLDINGS_OK && n == 108, "a row with a txid of 64");
    for (i = 0; i < 108; i++) {
        BRAssetRow dd;
        uint8_t *cut = (uint8_t *)malloc(i ? i : 1);

        if (! cut) break;
        memcpy(cut, big, i);
        if (BRAssetRowDecode(cut, i, &dd) != BR_ASSET_HOLDINGS_REFUSED) ck(0, "a prefix of %zu bytes, in a buffer of its size, was read", i);
        free(cut);
    }
    ck(1, "with a txid of 64 too");
    x = row("ab", 3, OWN, 3, 7, "La1", 5, 0, NAT, VER);
    ck(BRAssetRowEncode(&x, full, sizeof(full), &n) == BR_ASSET_HOLDINGS_OK && n == 46, "the row of the refusals");
    for (i = 0; i < 46; i++) {
        BRAssetRow dd;
        uint8_t *cut = (uint8_t *)malloc(i ? i : 1);

        if (! cut) break;
        memcpy(cut, full, i);
        if (BRAssetRowDecode(cut, i, &dd) != BR_ASSET_HOLDINGS_REFUSED) ck(0, "a prefix of %zu bytes, in a buffer of its size, was read", i);
        free(cut);
    }
    ck(1, "every prefix in a buffer of its own size: refused, nothing read past it");
    x = row("ab", 3, OWN, 3, 7, "L", 5, 0, NAT, VER);
    ck(BRAssetRowEncode(&x, full, sizeof(full), &n) == BR_ASSET_HOLDINGS_OK && n == 44, "an id of one byte");
    full[1] &= (uint8_t)~4u;
    ck(BRAssetRowDecode(full, n, &x) == BR_ASSET_HOLDINGS_REFUSED, "an id of one byte without its flag: refused");
}

static int isZero(const void *p, size_t len)
{
    const uint8_t *b = (const uint8_t *)p;
    size_t i;

    for (i = 0; i < len; i++) if (b[i]) return 0;
    return 1;
}

static void zero_structs(void)
{
    BRAssetRow r;
    BRAssetPersist p;
    BRAssetStackAcceptance a;
    BRAssetStackAnswer s;
    BRAssetDetectedOutput d;
    BRAssetAbsence e;
    size_t n = 9;
    BRAssetStackLookup lookup;

    memset(&r, 0, sizeof(r)); memset(&p, 0, sizeof(p)); memset(&a, 0, sizeof(a)); memset(&s, 0, sizeof(s));
    memset(&d, 0, sizeof(d)); memset(&e, 0, sizeof(e));
    ck(r.source == BAK && r.credit == UNC && ! r.isAsset && ! BRAssetRowCounts(&r, OWNED, 1, 1), "a row nobody filled: no asset row, counts nothing");
    ck(p.action == BR_ASSET_PERSIST_NOTHING && a.verdict == BR_ASSET_ACCEPT_NO_ANSWER, "a write or an acceptance nobody filled does nothing");
    ck((int)BR_ASSET_HOLDINGS_REFUSED == 0 && (int)BR_ASSET_ROW_KEEP == 0 && (int)BR_ASSET_ASK_NO == 0 &&
       (int)BR_ASSET_TX_HELD == 0 && (int)BR_ASSET_ABSENCE_NATIVE == 0, "the zero of each enum");
    {   // A lookups array nobody filled reads as present to both prunes: nothing deleted, nothing counted.
        BRAssetRow zr[2];
        BRAssetTxLookup zl[2];
        size_t zn = 2, k, total = 0;

        tableFresh();
        memset(zl, 0, sizeof(zl));
        zr[0] = row(tx(0, "", 'a'), 0, OWN, 3, 0, "La1", 3, 0, NAT, UNC);
        zr[1] = row(tx(1, "", 'b'), 1, OWN, 3, 0, "La1", 3, 0, BAK, UNC);
        for (k = 0; k < 4; k++) { total += pass(0, zr, &zn, zl, NULL); total += pass(1, zr, &zn, zl, NULL); }
        ck(total == 0 && zn == 2 && TABLE.count == 0, "lookups nobody filled: four native and four dead passes delete and count nothing");
    }
    ck(BRAssetIndexerAskDecide(&r, 0, 0, 0) == BR_ASSET_ASK_NO, "a row nobody filled is not asked about");
    ck(BRAssetStackAnswerRead(&s, "", 0, NULL, 0, &n, &lookup) == BR_ASSET_HOLDINGS_OK && lookup == BR_ASSET_STACK_UNAVAILABLE,
       "an answer nobody filled has no vout: no answer");
    ck(BRAssetPersistDetectedOutput(&d, NULL).action == BR_ASSET_PERSIST_NOTHING, "an output nobody filled writes nothing");
    ck(e.passes == 0 && isZero(&e, sizeof(e)), "an absence nobody filled counts nothing");
    ck(BRAssetRowEncode(&r, WB, sizeof(WB), &n) == BR_ASSET_HOLDINGS_REFUSED, "a row nobody filled has no txid to store");
    ck(BR_ASSET_ABSENCE_PASSES == 2 && BR_ASSET_RECEIPT_RECHECK_MS == 60000 && BR_ASSET_TX_UNCONFIRMED == INT32_MAX &&
       BR_ASSET_ROW_WIRE_FIXED == 38 && BR_ASSET_ROW_WIRE_MAX == 66083 && BR_ASSET_STATE_PROBE_ERROR == -99 &&
       BR_ASSET_STATE_CONFLICTED == -2 && BR_CF_BEHIND_THRESHOLD == 100, "the constants");
}

// =================================================================================================
// THE MODEL: the Kotlin, line for line.
// =================================================================================================

#define MR 16        // rows a corpus holds
#define MS 8         // script bytes

typedef struct {
    char txid[80];
    int vout;
    char scriptHex[2 * MS + 1];   // scriptPubKey.toHex(): lower case
    int64_t height;
    int isAsset;
    int hasId;
    char assetId[80];
    int64_t qty;
    int spent;
    char source[16];              // "NATIVE" / "BACKEND" / any other text
    char credit[16];              // "UNCHECKED" / "BACKED" / "VERIFIED" / "MIXED" / any other text
    int live;                     // still in the table
} MRow;

static int mCounts(const char *credit) { return strcmp(credit, "BACKED") == 0 || strcmp(credit, "VERIFIED") == 0; }
static int mIsSettled(const char *c) { return mCounts(c) || strcmp(c, "MIXED") == 0; }
static int mStartsWith(const char *s, const char *p) { return strncmp(s, p, strlen(p)) == 0; }

// AssetSums.add: the sum left the Long range iff both operands have the sign the result lacks.
static int64_t mAdd(int64_t a, int64_t b)
{
    int64_t r = (int64_t)((uint64_t)a + (uint64_t)b);

    return (((a ^ r) & (b ^ r)) < 0) ? (a < 0 ? INT64_MIN : INT64_MAX) : r;
}

static void mHex(const uint8_t *b, size_t n, char *out)
{
    size_t i;

    for (i = 0; i < n; i++) snprintf(out + 2 * i, 3, "%02x", b[i]);
    out[2 * n] = '\0';
}

static void mLower(const char *s, char *out)
{
    for (; *s; s++) *out++ = (char)tolower((unsigned char)*s);
    *out = '\0';
}

typedef struct { int n; char s[8][2 * MS + 1]; } MSet;

static int mIn(const MSet *set, const char *s)
{
    int i;

    for (i = 0; i < set->n; i++) if (strcmp(set->s[i], s) == 0) return 1;
    return 0;
}

static void mAddTo(MSet *set, const char *s) { if (! mIn(set, s)) strcpy(set->s[set->n++], s); }

static int mIsHeldForDisplay(const char *scriptHexLower, const MSet *ownedLower, const char *assetSource, int nativeSpentState)
{
    (void)assetSource;
    if (! *scriptHexLower || ! mIn(ownedLower, scriptHexLower)) return 0;
    switch (nativeSpentState) {
    case 0: return 0;     // SPENT
    case 1: return 1;     // HELD
    case -2: return 0;    // CONFLICTED
    default: return 0;
    }
}

typedef struct { int n; char id[MR][80]; int64_t qty[MR]; int cnt[MR]; } MBalances;

// computeHeldAssetBalancesImpl: 0 for null.
static int mComputeHeld(const MSet *ownedScriptHexes, const MRow *rows, int n, const int *states, MBalances *out)
{
    MSet owned;
    int i, j;

    owned.n = 0;
    for (i = 0; i < ownedScriptHexes->n; i++) { char l[2 * MS + 1]; mLower(ownedScriptHexes->s[i], l); mAddTo(&owned, l); }
    if (owned.n == 0) return 0;
    out->n = 0;
    for (i = 0; i < n; i++) {
        char scriptHex[2 * MS + 1];
        int keep;

        if (! rows[i].live || ! rows[i].isAsset) continue;   // getAllAssetUtxosNow: is_asset = 1
        if (rows[i].spent) continue;
        if (! rows[i].hasId) continue;
        mLower(rows[i].scriptHex, scriptHex);
        keep = mCounts(rows[i].credit) && mIsHeldForDisplay(scriptHex, &owned, rows[i].source, states[i]);
        if (! keep) continue;
        for (j = 0; j < out->n && strcmp(out->id[j], rows[i].assetId) != 0; j++) {}
        if (j == out->n) { strcpy(out->id[j], rows[i].assetId); out->qty[j] = 0; out->cnt[j] = 0; out->n++; }
        out->qty[j] = mAdd(out->qty[j], rows[i].qty);
        if (rows[i].qty > 0) out->cnt[j]++;
    }
    return 1;
}

static int mDecideAssetSpent(int state, int *v)   // 0 for null
{
    if (state == 0) { *v = 1; return 1; }
    if (state == 1) { *v = 0; return 1; }
    return 0;
}

// The table's deleteAssetUtxo: DELETE FROM utxos WHERE is_asset = 1 AND txid = :txid AND vout = :vout.
static int mDelete(MRow *rows, int n, const char *txid, int vout)
{
    int i, k = 0;

    for (i = 0; i < n; i++) {
        if (rows[i].live && rows[i].isAsset && strcmp(rows[i].txid, txid) == 0 && rows[i].vout == vout) { rows[i].live = 0; k++; }
    }
    return k;
}

// reconcileAssetRowsLocallyImpl; *setSpentTo[i] is -1 when setSpent was not called for row i.
static int mReconcile(const MSet *ownedScriptHexes, MRow *rows, int n, const int *states, int pruneUnowned, int *setSpentTo)
{
    int pruned = 0, i, phantoms[MR], np = 0;

    for (i = 0; i < n; i++) setSpentTo[i] = -1;
    if (pruneUnowned && ownedScriptHexes->n > 0) {
        for (i = 0; i < n; i++) {
            if (rows[i].live && rows[i].isAsset && rows[i].scriptHex[0] && ! mIn(ownedScriptHexes, rows[i].scriptHex)) phantoms[np++] = i;
        }
        for (i = 0; i < np; i++) pruned += mDelete(rows, n, rows[phantoms[i]].txid, rows[phantoms[i]].vout);
    }
    for (i = 0; i < n; i++) {
        int newSpent;

        if (! rows[i].live || ! rows[i].isAsset) continue;
        if (! mDecideAssetSpent(states[i], &newSpent)) continue;
        if (rows[i].spent != newSpent) setSpentTo[i] = newSpent;
    }
    return pruned;
}

// Kotlin's String.toIntOrNull, for the dead send's lines.
static int mToIntOrNull(const char *s, size_t len, int *out)
{
    int64_t v = 0;
    size_t i = 0;
    int neg = 0;

    if (len == 0) return 0;
    if (s[0] == '-' || s[0] == '+') { neg = s[0] == '-'; i = 1; if (len == 1) return 0; }
    for (; i < len; i++) {
        if (s[i] < '0' || s[i] > '9') return 0;
        v = v * 10 + (s[i] - '0');
        if (v > (int64_t)INT32_MAX + 1) return 0;
    }
    if (neg) v = -v;
    if (v < INT32_MIN || v > INT32_MAX) return 0;
    *out = (int)v;
    return 1;
}

// clearDeadAssetSend over "vout|sats|scriptHex" lines.
static int mClearDeadAssetSend(MRow *rows, int n, const char *txid, const MSet *ownedScriptHexes, char lines[][64], int nl)
{
    MSet ownedLower;
    int deleted = 0, i;

    ownedLower.n = 0;
    for (i = 0; i < ownedScriptHexes->n; i++) { char l[2 * MS + 1]; mLower(ownedScriptHexes->s[i], l); mAddTo(&ownedLower, l); }
    for (i = 0; i < nl; i++) {
        const char *p1 = strchr(lines[i], '|'), *p2 = p1 ? strchr(p1 + 1, '|') : NULL;
        char scriptHex[64], lower[64];
        int vout;

        if (! p1 || ! p2) continue;                                   // parts.size < 3
        if (! mToIntOrNull(lines[i], (size_t)(p1 - lines[i]), &vout)) continue;
        strcpy(scriptHex, p2 + 1);
        if (strchr(scriptHex, '|')) *strchr(scriptHex, '|') = '\0';
        if (! scriptHex[0]) continue;
        mLower(scriptHex, lower);
        if (! mIn(&ownedLower, lower)) continue;
        deleted += mDelete(rows, n, txid, vout);
    }
    return deleted;
}

// nativeAbsenceCounts: a string-keyed map.
typedef struct { int n; char key[64][160]; int v[64]; } MMap;

static int mMapFind(const MMap *m, const char *k)
{
    int i;

    for (i = 0; i < m->n; i++) if (strcmp(m->key[i], k) == 0) return i;
    return -1;
}

static void mMapRemove(MMap *m, const char *k)
{
    int i = mMapFind(m, k);

    if (i < 0) return;
    m->n--;
    if (i == m->n) return;
    strcpy(m->key[i], m->key[m->n]);
    m->v[i] = m->v[m->n];
}

static void mMapSet(MMap *m, const char *k, int v)
{
    int i = mMapFind(m, k);

    if (i < 0) { i = m->n++; strcpy(m->key[i], k); }
    m->v[i] = v;
}

static MMap MABS;
static int MBROKE;

// The lookup as each prune's lambda reads it. 0 FAILED (throws), 1 null, 2 empty, 3 outputs.
static int mNativeGone(int lookup) { return lookup == 0 || lookup == 1; }   // runCatching{..}.getOrNull() == null
static int mDeadGone(int lookup) { return lookup == 1 || lookup == 2; }     // runCatching{isNullOrEmpty()}.getOrDefault(false)

static int mPruneRemovedNative(MRow *rows, int n, const int *lookupOfRow)
{
    char live[MR][160];
    int nl = 0, deleted = 0, i, j, snap[MR], ns = 0;

    for (i = 0; i < n; i++) if (rows[i].live && rows[i].isAsset && strcmp(rows[i].source, "NATIVE") == 0) snap[ns++] = i;
    for (j = 0; j < ns; j++) {
        char key[160];
        int k, cur, g;

        i = snap[j];
        if (strlen(rows[i].txid) != 64) continue;
        snprintf(key, sizeof(key), "%s:%d", rows[i].txid, rows[i].vout);
        strcpy(live[nl++], key);
        g = mNativeGone(lookupOfRow[i]);
        if (! g) { mMapRemove(&MABS, key); continue; }
        k = mMapFind(&MABS, key);
        cur = (k < 0 ? 0 : MABS.v[k]) + 1;
        if (MABS.n >= 64 && k < 0) { MBROKE = 1; continue; }
        mMapSet(&MABS, key, cur);
        if (cur >= 2) { deleted += mDelete(rows, n, rows[i].txid, rows[i].vout); mMapRemove(&MABS, key); }
    }
    for (i = MABS.n - 1; i >= 0; i--) {   // keys.retainAll(liveKeys)
        for (j = 0; j < nl && strcmp(live[j], MABS.key[i]) != 0; j++) {}
        if (j == nl) mMapRemove(&MABS, MABS.key[i]);
    }
    return deleted;
}

static int mPruneDeadBroadcast(MRow *rows, int n, const int *lookupOfRow)
{
    int deleted = 0, i, j, snap[MR], ns = 0;

    for (i = 0; i < n; i++) if (rows[i].live && rows[i].isAsset) snap[ns++] = i;
    for (j = 0; j < ns; j++) {
        char key[160];
        int k, cur;

        i = snap[j];
        if (strlen(rows[i].txid) != 64) continue;
        if (rows[i].height > 0) continue;
        snprintf(key, sizeof(key), "dead:%s:%d", rows[i].txid, rows[i].vout);
        if (! mDeadGone(lookupOfRow[i])) { mMapRemove(&MABS, key); continue; }
        k = mMapFind(&MABS, key);
        cur = (k < 0 ? 0 : MABS.v[k]) + 1;
        if (MABS.n >= 64 && k < 0) { MBROKE = 1; continue; }
        mMapSet(&MABS, key, cur);
        if (cur >= 2) { deleted += mDelete(rows, n, rows[i].txid, rows[i].vout); mMapRemove(&MABS, key); }
    }
    return deleted;
}

static int mGate(int synced, int peers, float progress, int loaded, int64_t frontier, int64_t tip)
{
    return synced && peers > 0 && progress >= 1.0f && loaded && frontier > 0 && tip > 0 && (tip - frontier) <= 100;
}

typedef struct { int kind; int hasUnits; char id[80]; int64_t count; int hasLabel; char label[80]; } MCredit;   // 0 Unknown 1 Holds 2 Mixed
typedef struct { char id[80]; int64_t qty; char credit[16]; } MSettled;

static int mSettledRow(const MCredit *c, const char *placeholder, const char *source, MSettled *s)
{
    if (c->kind == 1) {
        if (c->hasUnits) { strcpy(s->id, c->id); s->qty = c->count; strcpy(s->credit, source); }
        else { strcpy(s->id, c->hasLabel ? c->label : placeholder); s->qty = 0; strcpy(s->credit, source); }
        return 1;
    }
    if (c->kind == 2) { strcpy(s->id, placeholder); s->qty = 0; strcpy(s->credit, "MIXED"); return 1; }
    return 0;
}

// persistDetectedAssetOutput over a one-row table: *ex is the row at the outpoint (live = 0 for none).
// Returns the unresolved answer; *wrote 0 nothing, 1 inserted, 2 updated, into *ex.
static int mPersist(MRow *ex, const char *txid, int vout, const char *scriptHex, int64_t height, const char *placeholder,
                    int64_t computedQty, int outgoing, const MCredit *credit, int *wrote)
{
    MSettled settled;
    int hasSettled;

    *wrote = 0;
    if (outgoing) return 0;
    hasSettled = mSettledRow(credit, placeholder, "BACKED", &settled);
    if (ex->live && ex->isAsset) {   // getAssetUtxoAt: is_asset = 1
        int idNull;

        strcpy(ex->source, "NATIVE");
        *wrote = 2;
        if (! mIsSettled(ex->credit)) {
            if (hasSettled) { ex->hasId = 1; strcpy(ex->assetId, settled.id); ex->qty = settled.qty; strcpy(ex->credit, settled.credit); }
            else if (computedQty > ex->qty) ex->qty = computedQty;
        }
        idNull = ! ex->hasId;
        return idNull || mStartsWith(ex->assetId, "unresolved:");
    }
    memset(ex, 0, sizeof(*ex));
    strcpy(ex->txid, txid);
    ex->vout = vout;
    strcpy(ex->scriptHex, scriptHex);
    ex->height = height;
    ex->isAsset = 1;
    ex->hasId = 1;
    strcpy(ex->assetId, hasSettled ? settled.id : placeholder);
    ex->qty = hasSettled ? settled.qty : computedQty;
    ex->spent = 0;
    strcpy(ex->source, "NATIVE");
    strcpy(ex->credit, hasSettled ? settled.credit : "UNCHECKED");
    ex->live = 1;
    *wrote = 1;
    return mStartsWith(hasSettled ? settled.id : placeholder, "unresolved:");
}

// AssetCreditRules.fromIndexer: 0 for null.
static int mFromIndexer(int lookupFound, char ids[][80], const int64_t *counts, int n, MCredit *c)
{
    char distinct[16][80];
    int nd = 0, i, j;
    int64_t sum = 0;

    memset(c, 0, sizeof(*c));
    if (! lookupFound) return 0;
    for (i = 0; i < n; i++) {
        if (counts[i] <= 0) continue;
        for (j = 0; j < nd && strcmp(distinct[j], ids[i]) != 0; j++) {}
        if (j == nd) strcpy(distinct[nd++], ids[i]);
    }
    if (nd == 0) { c->kind = 1; return 1; }
    if (nd > 1) { c->kind = 2; return 1; }
    for (i = 0; i < n; i++) {
        if (counts[i] <= 0) continue;
        if (counts[i] > 0 && sum > INT64_MAX - counts[i]) return 0;   // Math.addExact threw
        sum += counts[i];
    }
    c->kind = 1; c->hasUnits = 1; strcpy(c->id, distinct[0]); c->count = sum; c->hasLabel = 1; strcpy(c->label, distinct[0]);
    return 1;
}

// String.equals(other, ignoreCase = true), as Java's regionMatches compares chars.
static int mEqualsIgnoreCase(const char *a, const char *b)
{
    if (strlen(a) != strlen(b)) return 0;
    for (; *a; a++, b++) {
        unsigned char x = (unsigned char)*a, y = (unsigned char)*b;

        if (x == y) continue;
        if (toupper(x) == toupper(y)) continue;
        if (tolower(x) == tolower(y)) continue;
        return 0;
    }
    return 1;
}

// DigiScopeAssetParsing.txOutStack over the reader's view of the JSON. 0 Unavailable, 1 Found.
static int mTxOutStack(const BRAssetStackAnswer *json, const char *txid, int vout, char ids[][80], int64_t *counts, int *n)
{
    size_t i;

    *n = 0;
    if (json->hasError) return 0;
    if (! mEqualsIgnoreCase(json->txid ? json->txid : "", txid)) return 0;
    if (! json->hasVout || json->vout != vout) return 0;
    if (! json->hasAssets) return 0;
    for (i = 0; i < json->entryCount; i++) {
        const BRAssetStackAnswerEntry *a = &json->entries[i];
        const char *id;

        if (! a->isObject) return 0;
        id = a->assetId ? a->assetId : "";
        if (! (id[0] && strcmp(id, "null") != 0)) return 0;
        if (! a->countIsWhole) return 0;
        if (a->count < 0) return 0;
        strcpy(ids[*n], id);
        counts[*n] = a->count;
        (*n)++;
    }
    return 1;
}

// settleFromIndexer for one row: what it did. *asked: the lookup ran; returns 1 when it settled into *s.
typedef struct { int hasLast; int64_t last; } MKeyTime;

static int mSettleFromIndexer(const MRow *r, MKeyTime *kt, int64_t nowMs, int lookupFound, char ids[][80], const int64_t *counts,
                              int nIds, int hasMax, int64_t maxUnits, const char *placeholder, int *asked, MSettled *s, int *labels,
                              int *notBelieved)
{
    MCredit credit;
    int64_t reported;

    *asked = 0; *labels = 0; *notBelieved = 0;
    if (! r->live || ! r->isAsset) return 0;
    if (r->spent || mIsSettled(r->credit)) return 0;
    if (kt->hasLast && (int64_t)((uint64_t)nowMs - (uint64_t)kt->last) < 60000) return 0;
    kt->hasLast = 1; kt->last = nowMs;
    *asked = 1;
    if (! mFromIndexer(lookupFound, ids, counts, nIds, &credit)) return 0;
    reported = (credit.kind == 1 && credit.hasUnits) ? credit.count : 0;
    if (hasMax && reported > maxUnits) { *notBelieved = 1; return 0; }
    if (! mSettledRow(&credit, placeholder, "VERIFIED", s)) return 0;
    kt->hasLast = 0;
    *labels = mCounts(s->credit) && s->qty > 0 && ! mStartsWith(s->id, "unresolved:");
    return 1;
}

// ---- the model's stored form: an encoder and a reader of its own ----------------------------------

static size_t mEncode(const BRAssetRow *r, uint8_t *b)
{
    size_t n = 0, t = strlen(r->txid), a = r->assetId ? strlen(r->assetId) : 0, i;
    uint64_t f[4];
    int k;

    b[n++] = 1;
    b[n++] = (uint8_t)((r->isAsset ? 1 : 0) + (r->spent ? 2 : 0) + (r->assetId ? 4 : 0));
    b[n++] = (uint8_t)r->source;
    b[n++] = (uint8_t)r->credit;
    for (k = 0; k < 4; k++) b[n++] = (uint8_t)(((uint32_t)r->vout >> (8 * k)) & 0xff);
    f[0] = (uint64_t)r->satoshis; f[1] = (uint64_t)r->blockHeight; f[2] = (uint64_t)r->assetQuantity;
    for (i = 0; i < 3; i++) for (k = 0; k < 8; k++) b[n++] = (uint8_t)((f[i] >> (8 * k)) & 0xff);
    b[n++] = (uint8_t)t;
    for (i = 0; i < t; i++) b[n++] = (uint8_t)r->txid[i];
    b[n++] = 0;
    b[n++] = (uint8_t)a;
    for (i = 0; i < a; i++) b[n++] = (uint8_t)r->assetId[i];
    b[n++] = 0;
    b[n++] = (uint8_t)(r->scriptLen & 0xff);
    b[n++] = (uint8_t)(r->scriptLen >> 8);
    for (i = 0; i < r->scriptLen; i++) b[n++] = r->script[i];
    return n;
}

// 1 when b[0, len) is exactly one stored row, with its fields.
static int mDecode(const uint8_t *b, size_t len, BRAssetRow *r, char *txid, char *id)
{
    size_t p = 32, t, a, s, i;

    if (len < 38) return 0;
    if (b[0] != 1 || b[1] > 7 || b[2] > 1 || b[3] > 3) return 0;
    t = b[p++];
    if (p + t + 1 + 1 > len) return 0;
    for (i = 0; i < t; i++) { if (! b[p + i]) return 0; txid[i] = (char)b[p + i]; }
    txid[t] = '\0';
    p += t;
    if (b[p++] != 0) return 0;
    a = b[p++];
    if (a && ! (b[1] & 4)) return 0;
    if (p + a + 1 + 2 > len) return 0;
    for (i = 0; i < a; i++) { if (! b[p + i]) return 0; id[i] = (char)b[p + i]; }
    id[a] = '\0';
    p += a;
    if (b[p++] != 0) return 0;
    s = (size_t)b[p] | ((size_t)b[p + 1] << 8);
    p += 2;
    if (p + s != len) return 0;
    memset(r, 0, sizeof(*r));
    r->isAsset = b[1] & 1; r->spent = (b[1] >> 1) & 1;
    r->source = (BRAssetSource)b[2]; r->credit = (BRAssetCredit)b[3];
    r->vout = (int32_t)((uint32_t)b[4] | ((uint32_t)b[5] << 8) | ((uint32_t)b[6] << 16) | ((uint32_t)b[7] << 24));
    {
        uint64_t v[3] = { 0, 0, 0 };
        int k, j;

        for (j = 0; j < 3; j++) for (k = 7; k >= 0; k--) v[j] = (v[j] << 8) | b[8 + 8 * j + k];
        r->satoshis = (int64_t)v[0]; r->blockHeight = (int64_t)v[1]; r->assetQuantity = (int64_t)v[2];
    }
    r->txid = txid;
    r->assetId = (b[1] & 4) ? id : NULL;
    r->scriptLen = s;
    r->script = s ? b + len - s : NULL;   // the last s bytes
    return 1;
}

// =================================================================================================
// THE SWEEP
// =================================================================================================

static uint64_t RNG;

static uint32_t rnd(void)   // xorshift64: the same stream on every machine
{
    RNG ^= RNG << 13; RNG ^= RNG >> 7; RNG ^= RNG << 17;
    return (uint32_t)(RNG >> 24);
}

static uint32_t below(uint32_t n) { return rnd() % n; }

static int DIFFS;
static int REACH[64];

static void diff(const char *part, int round, const char *got, const char *model)
{
    if (DIFFS++ < 8) printf("  %s round %d: header [%s], model [%s]\n", part, round, got, model);
}

// 01 02 03 is a prefix of 01 02 03 04: neither owns the other.
static const uint8_t SCRIPTS[5][4] = { { 1, 2, 3 }, { 0x51 }, { 9, 9, 9 }, { 0xAB, 0xcd, 0xef }, { 1, 2, 3, 4 } };
static const size_t SCRIPT_LEN[5] = { 3, 1, 3, 3, 4 };
static char TXIDS[7][80];
static char IDS[7][80];
static const int STATES[] = { 0, 1, -1, -2, -99, 5, 2 };
static const int64_t QTYS[] = { 0, 1, 5, 9, -3, INT64_MAX, INT64_MAX - 1, INT64_MIN, (int64_t)1 << 62, 1000000 };

static BRAssetRow CR[MR];   // the header's rows
static MRow MRS[MR];        // the model's
static int CN;
static uint8_t CSCRIPT[MR][MS];

static const char *creditText(int c)
{
    static const char *const t[] = { "UNCHECKED", "BACKED", "VERIFIED", "MIXED" };
    return (c >= 0 && c < 4) ? t[c] : "OTHER";
}

// A corpus of rows with distinct outpoints (the table's primary key).
static void rndRows(int maxRows)
{
    int i, j, tries;

    CN = (int)below((uint32_t)maxRows + 1);
    for (i = 0; i < CN; i++) {
        int s = (int)below(7), id = (int)below(7), cr = (int)below(9), src = (int)below(7);
        BRAssetRow *r = &CR[i];

        memset(r, 0, sizeof(*r));
        for (tries = 0; tries < 50; tries++) {
            r->txid = TXIDS[below(7)];
            r->vout = (int32_t)below(3) - (below(20) == 0 ? 5 : 0);
            for (j = 0; j < i && ! (strcmp(CR[j].txid, r->txid) == 0 && CR[j].vout == r->vout); j++) {}
            if (j == i) break;
        }
        if (tries == 50) { CN = i; break; }
        if (s < 5) { memcpy(CSCRIPT[i], SCRIPTS[s], SCRIPT_LEN[s]); r->script = CSCRIPT[i]; r->scriptLen = SCRIPT_LEN[s]; }
        else if (s == 5) { r->script = CSCRIPT[i]; r->scriptLen = 0; }   // no bytes, at a real pointer
        r->satoshis = 600;
        r->blockHeight = (int64_t[]){ 0, 0, 1, 700000, -1, INT64_MAX }[below(6)];
        r->isAsset = below(6) != 0;
        r->assetId = id < 6 ? IDS[id] : NULL;
        r->assetQuantity = QTYS[below(N(QTYS))];
        r->spent = below(4) == 0;
        r->source = src < 3 ? BAK : src < 6 ? NAT : (BRAssetSource)5;
        r->credit = cr < 2 ? UNC : cr < 4 ? BR_ASSET_CREDIT_BACKED : cr < 7 ? VER : cr < 8 ? BR_ASSET_CREDIT_MIXED : (BRAssetCredit)7;

        memset(&MRS[i], 0, sizeof(MRS[i]));
        strcpy(MRS[i].txid, r->txid);
        MRS[i].vout = r->vout;
        mHex(r->script ? r->script : (const uint8_t *)"", r->scriptLen, MRS[i].scriptHex);
        MRS[i].height = r->blockHeight;
        MRS[i].isAsset = r->isAsset;
        MRS[i].hasId = r->assetId != NULL;
        if (r->assetId) strcpy(MRS[i].assetId, r->assetId);
        MRS[i].qty = r->assetQuantity;
        MRS[i].spent = r->spent;
        strcpy(MRS[i].source, r->source == NAT ? "NATIVE" : r->source == BAK ? "BACKEND" : "OTHER");
        strcpy(MRS[i].credit, creditText((int)r->credit));
        MRS[i].live = 1;
    }
}

static BRAssetScript COWNED[6];
static MSet MOWNED;
static size_t COWNEDN;

static void rndOwned(void)
{
    int i;

    COWNEDN = 0;
    MOWNED.n = 0;
    if (below(8) == 0) return;   // the wallet could not say
    for (i = 0; i < 5; i++) {
        if (below(2) == 0) continue;
        COWNED[COWNEDN].bytes = SCRIPTS[i];
        COWNED[COWNEDN].len = SCRIPT_LEN[i];
        COWNEDN++;
        { char h[2 * MS + 1]; mHex(SCRIPTS[i], SCRIPT_LEN[i], h); mAddTo(&MOWNED, h); }
    }
    if ((COWNEDN == 0 && below(3) == 0) || below(6) == 0) {   // the script of no bytes, at no pointer or at a real one
        COWNED[COWNEDN].bytes = below(2) ? NULL : SCRIPTS[0]; COWNED[COWNEDN].len = 0; COWNEDN++; mAddTo(&MOWNED, "");
    }
}

enum { R_NULL, R_BALANCES, R_SATURATED, R_EMPTY_ROW, R_HELD, R_PRUNED, R_SET_SPENT, R_CLEARED, R_NATIVE_DELETE, R_DEAD_DELETE,
       R_RETAINED, R_INSERT, R_UPDATE, R_RAISED, R_SETTLED_WRITE, R_FOUND, R_UNAVAILABLE, R_ACCEPT, R_NOT_BELIEVED, R_LATER,
       R_WIRE_OK, R_WIRE_REFUSED, R_GATE_OPEN, R_TABLE_FULL, R_STALE_FREED,
       R_PREFIX, R_EMPTY_AT_POINTER, R_LONG_TXID };

static void sweepBalances(void)
{
    int round, i;

    for (round = 0; round < 7000; round++) {
        int states[MR], setSpent[MR], mpruned, prune;
        char got[4096], model[4096];
        size_t count = 0, pruned = 0, at;
        BRAssetHoldingsStatus st;
        MBalances mb;

        rndRows(MR);
        rndOwned();
        for (i = 0; i < CN; i++) states[i] = below(2) ? BR_ASSET_STATE_HELD : STATES[below(N(STATES))];

        st = BRAssetHeldBalances(CR, (size_t)CN, states, COWNED, COWNEDN, HB, N(HB), &count);
        showBalances(got, st, HB, count);
        if (! mComputeHeld(&MOWNED, MRS, CN, states, &mb)) { strcpy(model, "null"); REACH[R_NULL]++; }
        else {
            at = 0; model[0] = '\0';
            for (i = 0; i < mb.n; i++) {
                at = put(model, at, "%s%s=%lld/%d", i ? " " : "", mb.id[i], (long long)mb.qty[i], mb.cnt[i]);
                if (mb.qty[i] == INT64_MAX || mb.qty[i] == INT64_MIN) REACH[R_SATURATED]++;
                if (mb.qty[i] == 0) REACH[R_EMPTY_ROW]++;
            }
            if (mb.n) REACH[R_BALANCES]++;
        }
        if (strcmp(got, model) != 0) diff("balances", round, got, model);
        for (i = 0; i < CN; i++) {
            char l[2 * MS + 1];
            MSet ol;
            int j, mh;

            ol.n = 0;
            for (j = 0; j < MOWNED.n; j++) { mLower(MOWNED.s[j], l); mAddTo(&ol, l); }
            mLower(MRS[i].scriptHex, l);
            mh = mIsHeldForDisplay(l, &ol, MRS[i].source, states[i]);
            if (mh) REACH[R_HELD]++;
            if (states[i] == BR_ASSET_STATE_HELD && CR[i].scriptLen > 0) {   // a script that is a prefix of an owned one
                size_t k;
                for (k = 0; k < COWNEDN; k++) {
                    if (COWNED[k].len > CR[i].scriptLen && memcmp(COWNED[k].bytes, CR[i].script, CR[i].scriptLen) == 0) REACH[R_PREFIX]++;
                }
            }
            if (states[i] == BR_ASSET_STATE_HELD && CR[i].script && CR[i].scriptLen == 0) {   // no bytes at a pointer, an empty owned entry
                size_t k;
                for (k = 0; k < COWNEDN && COWNED[k].len != 0; k++) {}
                if (k < COWNEDN) REACH[R_EMPTY_AT_POINTER]++;
            }
            if (BRAssetHeldForDisplay(CR[i].script, CR[i].scriptLen, COWNED, COWNEDN, CR[i].source, states[i]) != mh) {
                diff("held", round, "", "");
            }
        }

        prune = (int)below(2);
        st = BRAssetReconcileRows(CR, (size_t)CN, COWNED, COWNEDN, prune, states, CH, &pruned);
        mpruned = mReconcile(&MOWNED, MRS, CN, states, prune, setSpent);
        if (st != BR_ASSET_HOLDINGS_OK || (int)pruned != mpruned) {
            snprintf(got, sizeof(got), "%d pruned %zu", (int)st, pruned); snprintf(model, sizeof(model), "pruned %d", mpruned);
            diff("reconcile", round, got, model);
        }
        if (mpruned) REACH[R_PRUNED]++;
        for (i = 0; i < CN; i++) {
            BRAssetRowChange want = ! MRS[i].live ? BR_ASSET_ROW_DELETE : setSpent[i] == 1 ? BR_ASSET_ROW_MARK_SPENT :
                                    setSpent[i] == 0 ? BR_ASSET_ROW_MARK_UNSPENT : BR_ASSET_ROW_KEEP;
            if (setSpent[i] >= 0) REACH[R_SET_SPENT]++;
            if (CH[i] != want) { snprintf(got, sizeof(got), "row %d %d", i, (int)CH[i]); snprintf(model, sizeof(model), "%d", (int)want); diff("reconcile", round, got, model); }
        }
    }
}

static void sweepClear(void)
{
    int round, i;

    for (round = 0; round < 4500; round++) {
        BRAssetTxOutput outs[6];
        char lines[6][64];
        int no = (int)below(6), m;
        size_t removed = 0;
        const char *txid;

        rndRows(MR);
        rndOwned();
        txid = TXIDS[below(7)];
        for (i = 0; i < no; i++) {
            int s = (int)below(7);
            char h[2 * MS + 1];

            outs[i].vout = (int32_t)below(4) - (below(10) == 0 ? 5 : 0);
            outs[i].script = s < 5 ? SCRIPTS[s] : s == 5 ? SCRIPTS[0] : NULL;   // 5: no bytes, at a real pointer
            outs[i].scriptLen = s < 5 ? SCRIPT_LEN[s] : 0;
            mHex(s < 5 ? SCRIPTS[s] : (const uint8_t *)"", outs[i].scriptLen, h);
            if (below(2)) { size_t k; for (k = 0; h[k]; k++) h[k] = (char)toupper((unsigned char)h[k]); }   // the bridge's case
            snprintf(lines[i], sizeof(lines[i]), "%d|600|%s", outs[i].vout, h);
        }
        if (BRAssetClearDeadSend(CR, (size_t)CN, txid, outs, (size_t)no, COWNED, COWNEDN, CH, &removed) != BR_ASSET_HOLDINGS_OK) {
            diff("clear", round, "refused", "");
            continue;
        }
        m = mClearDeadAssetSend(MRS, CN, txid, &MOWNED, lines, no);
        if (m) REACH[R_CLEARED]++;
        if ((int)removed != m) diff("clear", round, "count", "");
        for (i = 0; i < CN; i++) if ((CH[i] == BR_ASSET_ROW_DELETE) != ! MRS[i].live) diff("clear", round, "row", "");
    }
}

static int byKey(const void *a, const void *b) { return strcmp((const char *)a, (const char *)b); }

static void compareTables(int round)
{
    static char got[64][200], model[64][200];
    size_t i;
    int j;

    if (TABLE.count != (size_t)MABS.n) { diff("prune table", round, "size", ""); return; }
    for (i = 0; i < TABLE.count; i++) {
        const BRAssetAbsence *e = &TABLE.entries[i];
        snprintf(got[i], 200, "%s%s:%d=%d", e->kind == BR_ASSET_ABSENCE_DEAD ? "dead:" : "", e->txid, e->vout, e->passes);
    }
    for (j = 0; j < MABS.n; j++) snprintf(model[j], 200, "%s=%d", MABS.key[j], MABS.v[j]);
    qsort(got, TABLE.count, 200, byKey);
    qsort(model, (size_t)MABS.n, 200, byKey);
    for (i = 0; i < TABLE.count; i++) if (strcmp(got[i], model[i]) != 0) { diff("prune table", round, got[i], model[i]); return; }
}

// The model's lookup kinds (0 FAILED, 1 null, 2 empty, 3 outputs) as the header names them.
static const BRAssetTxLookup LOOKUP_OF[4] = { BR_ASSET_TX_LOOKUP_FAILED, BR_ASSET_TX_NOT_HELD, BR_ASSET_TX_NO_OUTPUTS, BR_ASSET_TX_HELD };

// The counts a pass adds, from the model's map: a row it looks at, found absent, and not yet counted.
static int mNewCounts(int dead, const MRow *rows, int n, const int *lookupOfRow)
{
    int i, added = 0;
    char key[160];

    for (i = 0; i < n; i++) {
        if (! rows[i].live || ! rows[i].isAsset || strlen(rows[i].txid) != 64) continue;
        if (dead && rows[i].height > 0) continue;
        if (! dead && strcmp(rows[i].source, "NATIVE") != 0) continue;
        snprintf(key, sizeof(key), dead ? "dead:%s:%d" : "%s:%d", rows[i].txid, rows[i].vout);
        if ((dead ? mDeadGone(lookupOfRow[i]) : mNativeGone(lookupOfRow[i])) && mMapFind(&MABS, key) < 0) added++;
    }
    return added;
}

static void sweepPrunes(void)
{
    static BRAssetAbsence saved[N(AB)];
    int round, i;

    for (round = 0; round < 2500; round++) {
        int passes = 3 + (int)below(6), p, lookupOfTx[7], lookups[MR];
        BRAssetTxLookup clk[MR];

        rndRows(MR);
        tableFresh();
        MABS.n = 0;
        for (p = 0; p < passes; p++) {
            int dead = (int)below(2), m;
            size_t removed = 0;
            BRAssetHoldingsStatus st;

            size_t before = TABLE.count, cap = below(3) ? N(AB) : below(5);
            int room, added;

            for (i = 0; i < 7; i++) lookupOfTx[i] = (int)below(4);
            for (i = 0; i < CN; i++) {
                int t;
                for (t = 0; t < 7 && CR[i].txid != TXIDS[t]; t++) {}
                lookups[i] = lookupOfTx[t % 7];
                clk[i] = LOOKUP_OF[lookups[i]];
                if (CR[i].isAsset && strlen(CR[i].txid) == 65 && (dead ? mDeadGone(lookups[i]) : mNativeGone(lookups[i]))) REACH[R_LONG_TXID]++;
            }
            // Sometimes a table with less room than the pass may need: CAPACITY exactly when the counts it keeps
            // and adds do not fit, with nothing written; then the table grows and the pass runs again.
            // The room the pass needs is what Kotlin's map holds when the pass is done.
            TABLE.capacity = cap < before ? before : cap;
            added = mNewCounts(dead, MRS, CN, lookups);
            m = dead ? mPruneDeadBroadcast(MRS, CN, lookups) : mPruneRemovedNative(MRS, CN, lookups);
            room = MABS.n;
            memcpy(saved, AB, sizeof(AB));
            st = dead ? BRAssetPruneDeadBroadcastRows(CR, (size_t)CN, clk, &TABLE, CH, &removed)
                      : BRAssetPruneRemovedNativeRows(CR, (size_t)CN, clk, &TABLE, CH, &removed);
            if (st == BR_ASSET_HOLDINGS_CAPACITY) {
                REACH[R_TABLE_FULL]++;
                if ((size_t)room <= TABLE.capacity) diff("prune room", round, "CAPACITY", "room enough");
                if (TABLE.count != before || memcmp(saved, AB, sizeof(AB)) != 0) diff("prune room", round, "table written", "");
                TABLE.capacity = N(AB);
                st = dead ? BRAssetPruneDeadBroadcastRows(CR, (size_t)CN, clk, &TABLE, CH, &removed)
                          : BRAssetPruneRemovedNativeRows(CR, (size_t)CN, clk, &TABLE, CH, &removed);
            }
            else if (st == BR_ASSET_HOLDINGS_OK && (size_t)room > TABLE.capacity) diff("prune room", round, "OK", "no room");
            else if (st == BR_ASSET_HOLDINGS_OK && before + (size_t)added > TABLE.capacity) REACH[R_STALE_FREED]++;
            if (st != BR_ASSET_HOLDINGS_OK) { diff("prune", round, "refused", ""); break; }
            if ((int)removed != m) { char g[32], w[32]; snprintf(g, 32, "%zu", removed); snprintf(w, 32, "%d", m); diff("prune count", round, g, w); }
            if (m) REACH[dead ? R_DEAD_DELETE : R_NATIVE_DELETE]++;
            if (MABS.n) REACH[R_RETAINED]++;
            for (i = 0; i < CN; i++) if ((CH[i] == BR_ASSET_ROW_DELETE) != ! MRS[i].live) diff("prune row", round, "", "");
            compareTables(round);
            // Now and then a row goes by other means (the dead send cleared, the reconcile), its counts left.
            if (CN > 0 && below(4) == 0) MRS[below((uint32_t)CN)].live = 0;
            // Both drop the rows deleted.
            {
                int k = 0;
                for (i = 0; i < CN; i++) if (MRS[i].live) { CR[k] = CR[i]; MRS[k] = MRS[i]; k++; }
                CN = k;
            }
        }
    }
    ck(! MBROKE, "the model's map held every count");
}

static void sweepPersist(void)
{
    int round;

    for (round = 0; round < 6000; round++) {
        BRAssetRow ex;
        MRow mex;
        BRAssetOutputCredit c;
        MCredit mc;
        BRAssetDetectedOutput d;
        BRAssetPersist p;
        int wrote, mu, hasEx = (int)below(3) != 0, k = (int)below(4);
        const char *placeholders[] = { "La1234", "unresolved:t", "", "Lazz" };
        char got[512], model[512];

        rndRows(1);
        if (CN == 0) { rndRows(1); }
        if (CN == 0) continue;
        ex = CR[0];
        mex = MRS[0];
        memset(&c, 0, sizeof(c));
        memset(&mc, 0, sizeof(mc));
        c.kind = (BRAssetOutputCreditKind)(k < 3 ? k : 0);
        mc.kind = (int)c.kind;
        if (c.kind == BR_ASSET_OUTPUT_HOLDS) {
            c.hasUnits = (int)below(2); mc.hasUnits = c.hasUnits;
            if (c.hasUnits) { c.assetId = IDS[below(5)]; c.count = QTYS[below(N(QTYS))]; strcpy(mc.id, c.assetId); mc.count = c.count; }
            if (below(2)) { c.label = IDS[below(5)]; mc.hasLabel = 1; strcpy(mc.label, c.label); }
        }
        memset(&d, 0, sizeof(d));
        d.txid = ex.txid; d.vout = ex.vout; d.script = SCRIPTS[0]; d.scriptLen = 3; d.satoshis = 600; d.blockHeight = 7;
        d.placeholderAssetId = placeholders[below(4)];
        d.claimedQuantity = QTYS[below(N(QTYS))];
        d.isOutgoingUnconfirmed = below(5) == 0;
        d.credit = k == 3 ? NULL : &c;
        if (! hasEx) mex.live = 0;
        p = BRAssetPersistDetectedOutput(&d, hasEx ? &ex : NULL);
        mu = mPersist(&mex, d.txid, d.vout, "010203", 7, d.placeholderAssetId, d.claimedQuantity, d.isOutgoingUnconfirmed, &mc, &wrote);
        if (wrote == 1) REACH[R_INSERT]++;
        if (wrote == 2) REACH[R_UPDATE]++;
        if (wrote == 2 && mex.qty == d.claimedQuantity && mex.qty != ex.assetQuantity && strcmp(mex.credit, "UNCHECKED") == 0) REACH[R_RAISED]++;
        if (wrote == 2 && strcmp(mex.credit, creditText((int)ex.credit)) != 0) REACH[R_SETTLED_WRITE]++;
        snprintf(got, sizeof(got), "%d %d %s %d %s %lld %d %s %s %d %d %lld", (int)p.action, p.unresolved,
                 p.action ? p.row.txid : "", p.action ? p.row.vout : 0, p.action && p.row.assetId ? p.row.assetId : "(none)",
                 p.action ? (long long)p.row.assetQuantity : 0LL, p.action ? p.row.isAsset : 0,
                 p.action ? (p.row.source == NAT ? "NATIVE" : p.row.source == BAK ? "BACKEND" : "OTHER") : "",
                 p.action ? creditText((int)p.row.credit) : "", p.action ? p.row.spent : 0,
                 p.action ? (int)p.row.scriptLen : 0, p.action ? (long long)p.row.blockHeight : 0LL);
        snprintf(model, sizeof(model), "%d %d %s %d %s %lld %d %s %s %d %d %lld", wrote, mu, wrote ? mex.txid : "", wrote ? mex.vout : 0,
                 wrote && mex.hasId ? mex.assetId : "(none)", wrote ? (long long)mex.qty : 0LL, wrote ? mex.isAsset : 0,
                 wrote ? mex.source : "", wrote ? mex.credit : "", wrote ? mex.spent : 0,
                 wrote ? (int)strlen(mex.scriptHex) / 2 : 0, wrote ? (long long)mex.height : 0LL);
        if (strcmp(got, model) != 0) diff("persist", round, got, model);
    }
}

static void sweepIndexer(void)
{
    static const char *const txids[] = { ASKED, "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB", OTHER, "", NULL,
                                         "abababababababababababababababababababababababababababababababa" };
    static const char *const ids[] = { "La1", "Ua2", "", "null", NULL, "La1", "NULL" };
    static const int64_t counts[] = { 0, 1, 5, -1, INT64_MAX, INT64_MAX - 3, 1000000, INT64_MIN };
    int round, i;

    for (round = 0; round < 8000; round++) {
        BRAssetStackAnswerEntry e[5];
        BRAssetStackAnswer a;
        BRAssetStackLookup lookup;
        size_t n = 0;
        char mids[8][80], got[4096], model[4096];
        int64_t mcounts[8];
        int mn = 0, found, vout = (int)below(3), allGood = 1;
        BRAssetHoldingsStatus st;

        a.hasError = below(12) == 0;
        a.txid = txids[below(N(txids))];
        if (below(3)) a.txid = ASKED;
        a.hasVout = below(10) != 0;
        a.vout = below(4) ? vout : (int32_t)below(3) - 1;
        a.hasAssets = below(10) != 0;
        a.entryCount = below(5);
        for (i = 0; i < (int)a.entryCount; i++) {
            e[i].isObject = below(15) != 0;
            e[i].assetId = below(3) ? ids[below(2)] : ids[below(N(ids))];
            e[i].countIsWhole = below(12) != 0;
            e[i].count = counts[below(N(counts))];
            if (! (e[i].isObject && e[i].assetId && e[i].assetId[0] && strcmp(e[i].assetId, "null") != 0 && e[i].countIsWhole && e[i].count >= 0)) allGood = 0;
        }
        if (below(2) && ! allGood) for (i = 0; i < (int)a.entryCount; i++) { e[i].isObject = 1; e[i].assetId = ids[below(2)]; e[i].countIsWhole = 1; if (e[i].count < 0) e[i].count = 2; }
        a.entries = e;

        st = BRAssetStackAnswerRead(&a, ASKED, vout, SU, N(SU), &n, &lookup);
        showStack(got, st, lookup, SU, n);
        found = mTxOutStack(&a, ASKED, vout, mids, mcounts, &mn);
        {
            size_t at = 0;
            if (! found) put(model, 0, "Unavailable");
            else {
                at = put(model, at, "Found(");
                for (i = 0; i < mn; i++) at = put(model, at, "%s%s %lld", i ? ", " : "", mids[i], (long long)mcounts[i]);
                put(model, at, ")");
            }
        }
        REACH[found ? R_FOUND : R_UNAVAILABLE]++;
        if (strcmp(got, model) != 0) diff("stack read", round, got, model);

        // Settling, from the answer read, under a random bound, for a random row and time.
        {
            BRAssetDeliverable bound;
            BRAssetRow r;
            MRow mr;
            MKeyTime kt;
            BRAssetIndexerAsk ask;
            BRAssetStackAcceptance acc;
            MSettled ms;
            int asked, labels, notBelieved, msettled, hasLast = (int)below(2);
            int64_t last = (int64_t[]){ 0, 1000, -5, INT64_MIN, INT64_MAX, 600000 }[below(6)];
            int64_t now = (int64_t[]){ 0, 59999, 60000, 61000, INT64_MAX, INT64_MIN, 660000 }[below(7)];
            const char *ph = below(2) ? "unresolved:t" : "Lplace";

            bound.unbounded = below(3) == 0;
            bound.max = bound.unbounded ? 0 : counts[below(3)] + (below(2) ? 4 : 0);
            rndRows(1);
            if (CN == 0) continue;
            if (below(4)) {   // mostly a row that waits for an answer
                CR[0].isAsset = MRS[0].isAsset = 1;
                CR[0].spent = MRS[0].spent = 0;
                CR[0].credit = UNC; strcpy(MRS[0].credit, "UNCHECKED");
            }
            r = CR[0]; mr = MRS[0];
            kt.hasLast = hasLast; kt.last = last;
            msettled = mSettleFromIndexer(&mr, &kt, now, found, mids, mcounts, mn, ! bound.unbounded, bound.max, ph, &asked, &ms, &labels,
                                          &notBelieved);
            ask = BRAssetIndexerAskDecide(&r, hasLast, last, now);
            if (ask == BR_ASSET_ASK_LATER) REACH[R_LATER]++;
            if ((ask == BR_ASSET_ASK_NOW) != asked) { diff("ask", round, "", ""); continue; }
            if (! asked) continue;
            acc = BRAssetStackAnswerAccept(lookup, SU, n, bound, ph);
            if (acc.verdict == BR_ASSET_ACCEPT_SETTLED) REACH[R_ACCEPT]++;
            if (acc.verdict == BR_ASSET_ACCEPT_NOT_BELIEVED) REACH[R_NOT_BELIEVED]++;
            snprintf(got, sizeof(got), "%d %d %s %lld %s %d", acc.verdict == BR_ASSET_ACCEPT_SETTLED,
                     acc.verdict == BR_ASSET_ACCEPT_NOT_BELIEVED, acc.verdict == BR_ASSET_ACCEPT_SETTLED ? acc.row.assetId : "",
                     acc.verdict == BR_ASSET_ACCEPT_SETTLED ? (long long)acc.row.quantity : 0LL,
                     acc.verdict == BR_ASSET_ACCEPT_SETTLED ? creditText((int)acc.row.credit) : "", acc.labelsHistory);
            snprintf(model, sizeof(model), "%d %d %s %lld %s %d", msettled, notBelieved, msettled ? ms.id : "",
                     msettled ? (long long)ms.qty : 0LL, msettled ? ms.credit : "", labels);
            if (strcmp(got, model) != 0) diff("settle", round, got, model);
            if (msettled == kt.hasLast) diff("settle time", round, "", "");
        }
    }
}

static void sweepWire(void)
{
    int round, i;
    static uint8_t b[512], mb[512];
    static char txid[300], id[300], mt[300], mi[300];
    static uint8_t script[200];

    for (round = 0; round < 4000; round++) {
        BRAssetRow r, d, md;
        size_t w = 0, mw, tl = below(4) ? 64 : below(70), il = below(80), sl = below(5) ? below(40) : below(200);
        int ok, mok;

        for (i = 0; i < (int)tl; i++) txid[i] = (char)('0' + below(40));
        txid[tl] = '\0';
        for (i = 0; i < (int)il; i++) id[i] = (char)(1 + below(255));
        id[il] = '\0';
        for (i = 0; i < (int)sl; i++) script[i] = (uint8_t)rnd();
        memset(&r, 0, sizeof(r));
        r.txid = txid; r.vout = (int32_t)rnd(); r.script = sl ? script : NULL; r.scriptLen = sl;
        r.satoshis = (int64_t)(((uint64_t)rnd() << 32) | rnd()); r.blockHeight = QTYS[below(N(QTYS))];
        r.isAsset = (int)below(2); r.assetId = below(4) ? id : NULL; r.assetQuantity = QTYS[below(N(QTYS))];
        r.spent = (int)below(2); r.source = (BRAssetSource)below(2); r.credit = (BRAssetCredit)below(4);
        if (BRAssetRowEncode(&r, b, sizeof(b), &w) != BR_ASSET_HOLDINGS_OK) { diff("wire", round, "refused", ""); continue; }
        mw = mEncode(&r, mb);
        if (w != mw || memcmp(b, mb, w) != 0) diff("wire bytes", round, "", "");
        if (BRAssetRowDecode(b, w, &d) != BR_ASSET_HOLDINGS_OK || ! sameRow(&r, &d)) diff("wire round trip", round, "", "");
        // One byte changed, or the length changed: the header reads what the model reads.
        if (below(2)) b[below((uint32_t)w)] = (uint8_t)(below(3) ? below(8) : rnd());
        if (below(4) == 0) w = w + below(3) - 1;
        ok = BRAssetRowDecode(b, w, &d) == BR_ASSET_HOLDINGS_OK;
        mok = mDecode(b, w, &md, mt, mi);
        REACH[ok ? R_WIRE_OK : R_WIRE_REFUSED]++;
        if (ok != mok || (ok && ! sameRow(&d, &md))) { char g[16], m[16]; snprintf(g, 16, "%d", ok); snprintf(m, 16, "%d", mok); diff("wire decode", round, g, m); }
    }
}

static void sweepSmall(void)
{
    int round;
    static const int64_t heights[] = { 0, 1, 100, 101, 24200000, INT64_MAX, -1, INT32_MAX, (int64_t)INT32_MAX + 1, INT64_MIN };
    static const float progress[] = { 0.0f, 0.99f, 1.0f, 1.5f, -1.0f };
    static const int32_t peers[] = { 0, 1, -1, 8, INT32_MAX, INT32_MIN };

    for (round = 0; round < 20000; round++) {
        int synced = (int)below(4), loaded = (int)below(4), mv, cv, st = STATES[below(N(STATES))], s1 = 5, s2 = 5;
        int32_t pc = peers[below(N(peers))];
        float pr = progress[below(N(progress))];
        int64_t tip = heights[below(N(heights))], fr = heights[below(N(heights))];
        int64_t sent = QTYS[below(N(QTYS))], h = heights[below(N(heights))];

        if (below(2) && tip > INT64_MIN + 400 && tip < INT64_MAX - 400) fr = tip - (int64_t)below(300) + 150;
        cv = BRAssetPruneGateOpen(synced, pc, pr, loaded, fr, tip);
        mv = (fr > 0 && tip > 0) ? mGate(synced, pc, pr, loaded, fr, tip) : 0;
        if (cv) REACH[R_GATE_OPEN]++;
        if (cv != mv) diff("gate", round, "", "");
        if (BRAssetTxIsOutgoingUnconfirmed(sent, h) != (sent > 0 && h >= (int64_t)INT32_MAX)) diff("outgoing", round, "", "");
        if (BRAssetRowSpentDecide(st, &s1) != mDecideAssetSpent(st, &s2) || s1 != s2) diff("spent", round, "", "");
    }
}

static void model_sweep(void)
{
    static const struct { int at; int min; const char *what; } reach[] = {
        { R_NULL, 200, "an unknown owned set" }, { R_BALANCES, 2000, "balances" }, { R_SATURATED, 100, "balances at an end of the range" },
        { R_EMPTY_ROW, 200, "balances at zero" }, { R_HELD, 3000, "held outputs" }, { R_PRUNED, 1000, "ownership prunes" },
        { R_SET_SPENT, 3000, "spent flags written" }, { R_CLEARED, 500, "dead sends cleared" },
        { R_NATIVE_DELETE, 300, "native prune deletions" }, { R_DEAD_DELETE, 300, "dead-broadcast deletions" },
        { R_RETAINED, 1000, "counts carried to the next pass" }, { R_INSERT, 1000, "rows inserted" }, { R_UPDATE, 1500, "rows updated" },
        { R_RAISED, 50, "claims raised" }, { R_SETTLED_WRITE, 300, "rows settled on detection" }, { R_FOUND, 1500, "stacks found" },
        { R_UNAVAILABLE, 1500, "replies refused" }, { R_ACCEPT, 500, "answers settled" }, { R_NOT_BELIEVED, 50, "answers not believed" },
        { R_LATER, 300, "asks deferred" }, { R_WIRE_OK, 1000, "stored rows read" }, { R_WIRE_REFUSED, 500, "stored rows refused" },
        { R_GATE_OPEN, 100, "gates open" }, { R_TABLE_FULL, 300, "prune passes refused for room" },
        { R_STALE_FREED, 100, "passes that fit only because counts are dropped, forgotten or ended first" },
        { R_PREFIX, 1000, "held rows whose script is a prefix of an owned one" },
        { R_EMPTY_AT_POINTER, 300, "held rows of no script bytes at a pointer, beside an empty owned entry" },
        { R_LONG_TXID, 3000, "absent rows of a 65-character txid" },
    };
    size_t i;

    for (i = 0; i < N(TXIDS); i++) {
        static const char fills[] = "abc1e";
        if (i < 5) tx(10 + (int)i, i == 4 ? "AB" : "", fills[i]);
        strcpy(TXIDS[i], i < 5 ? TXB[10 + i] : i == 5 ? "short" : TXB[10]);
        if (i == 6) strcat(TXIDS[i], "a");   // 65: a txid and one character more
    }
    strcpy(IDS[0], "La1"); strcpy(IDS[1], "La1"); strcpy(IDS[2], "Ua2"); strcpy(IDS[3], "unresolved:t"); strcpy(IDS[4], "");
    strcpy(IDS[5], "Lb3"); strcpy(IDS[6], "x");

    RNG = 0x2026111100000008ULL;
    DIFFS = 0;
    MBROKE = 0;
    memset(REACH, 0, sizeof(REACH));
    sweepBalances();
    sweepClear();
    sweepPrunes();
    sweepPersist();
    sweepIndexer();
    sweepWire();
    sweepSmall();
    for (i = 0; i < N(reach); i++) ck(REACH[reach[i].at] >= reach[i].min, "the corpus reaches %s: %d", reach[i].what, REACH[reach[i].at]);
    ck(DIFFS == 0, "the header agrees with the model over the corpus: %d differences", DIFFS);
}

static void second_unit(void)
{
    ck(asset_holdings_kat_tu2() == 1, "a second translation unit that includes the header twice links and answers");
}

// ---- main -------------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "balances_predicate_not_owned", balances_predicate_not_owned },
    { "balances_predicate_owned_held", balances_predicate_owned_held },
    { "balances_predicate_owned_spent", balances_predicate_owned_spent },
    { "balances_predicate_dead_send", balances_predicate_dead_send },
    { "balances_predicate_no_record", balances_predicate_no_record },
    { "balances_predicate_probe_error", balances_predicate_probe_error },
    { "balances_chang_shape", balances_chang_shape },
    { "balances_no_record_row", balances_no_record_row },
    { "balances_empty_owned", balances_empty_owned },
    { "balances_fully_phantom", balances_fully_phantom },
    { "held_native_holds_it", held_native_holds_it },
    { "held_no_record_any_provenance", held_no_record_any_provenance },
    { "held_probe_error", held_probe_error },
    { "held_spent_and_conflicted", held_spent_and_conflicted },
    { "held_unowned_address", held_unowned_address },
    { "held_ultra_wallet_nine", held_ultra_wallet_nine },
    { "conflicted_not_held", conflicted_not_held },
    { "conflicted_backend_not_rescued", conflicted_backend_not_rescued },
    { "conflicted_leaves_spent_flag", conflicted_leaves_spent_flag },
    { "conflicted_stuck_attempt_excluded", conflicted_stuck_attempt_excluded },
    { "conflicted_other_states_unchanged", conflicted_other_states_unchanged },
    { "stack_read_in_order", stack_read_in_order },
    { "stack_empty_is_answer", stack_empty_is_answer },
    { "stack_anything_else_no_answer", stack_anything_else_no_answer },
    { "stack_not_found_own_404", stack_not_found_own_404 },
    { "spent_decide", spent_decide },
    { "gate_rules", gate_rules },
    { "outgoing_unconfirmed_rows", outgoing_unconfirmed_rows },
    { "prune_native_debounce", prune_native_debounce },
    { "prune_native_two_outpoints", prune_native_two_outpoints },
    { "prune_native_present_resets", prune_native_present_resets },
    { "prune_native_malformed_txid", prune_native_malformed_txid },
    { "prune_native_backend_untouched", prune_native_backend_untouched },
    { "prune_dead_never_confirmed", prune_dead_never_confirmed },
    { "prune_dead_confirmed_kept", prune_dead_confirmed_kept },
    { "prune_dead_present_kept", prune_dead_present_kept },
    { "prune_lookup_readings", prune_lookup_readings },
    { "prune_shared_table", prune_shared_table },
    { "prune_stale_counts", prune_stale_counts },
    { "reconcile_prunes_unowned", reconcile_prunes_unowned },
    { "reconcile_unknown_owned", reconcile_unknown_owned },
    { "reconcile_scriptless_kept", reconcile_scriptless_kept },
    { "reconcile_spent_flags", reconcile_spent_flags },
    { "reconcile_undetected_left", reconcile_undetected_left },
    { "reconcile_prune_waits", reconcile_prune_waits },
    { "reconcile_rules", reconcile_rules },
    { "clear_dead_send", clear_dead_send },
    { "persist_new_native", persist_new_native },
    { "persist_existing_retagged", persist_existing_retagged },
    { "persist_outgoing_unconfirmed", persist_outgoing_unconfirmed },
    { "persist_confirmed_and_incoming", persist_confirmed_and_incoming },
    { "persist_unknown_unchecked", persist_unknown_unchecked },
    { "persist_quantity_rules", persist_quantity_rules },
    { "settled_row_of", settled_row_of },
    { "settle_recheck_interval", settle_recheck_interval },
    { "settle_over_bound", settle_over_bound },
    { "settle_settled_final", settle_settled_final },
    { "settle_accept_rules", settle_accept_rules },
    { "ask_decide_rules", ask_decide_rules },
    { "balances_sums_saturate", balances_sums_saturate },
    { "balances_zero_rows", balances_zero_rows },
    { "balances_unbacked", balances_unbacked },
    { "balances_spent_flag", balances_spent_flag },
    { "balances_rows_seen", balances_rows_seen },
    { "wire_vectors", wire_vectors },
    { "wire_limits", wire_limits },
    { "wire_refusals", wire_refusals },
    { "capacity", capacity },
    { "arguments", arguments },
    { "zero_structs", zero_structs },
    { "edges", edges },
    { "second_unit", second_unit },
    { "model_sweep", model_sweep },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        CASES[i].run();
        printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
        return FAILED ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
