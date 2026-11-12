// Host KAT: how a reconcile scan reports what the node returned (BRScanPartition.h).
//
// THE RULES (moved from Kotlin: core/.../reconcile/ScanPartition.kt -- ScanClass, WalletOutpointCode,
// ClassTotal, HeldKind, heldKindOf, partitionScan, ScanPartition.matchesWallet / move, walletOnlyOf):
//   - each row's class from the wallet's code (BROutpointClassify.h) and, for a HELD row, why it is held;
//   - why a held row is held, from what the hold rule reads;
//   - no codes, a code count that is not the row count, or no wallet-only answer: no partition;
//   - the match: nothing missing, nothing wallet-only, and the spendable sum equal to the wallet's balance;
//   - move: members of one class whose outpoint is named, appended to another;
//   - the wallet-only answer read strictly.
// Six comparison arms, one per bit of -DSCAN_PARTITION_UNFIXED (see BRScanPartition.h). Each fails exactly
// the cases run.sh lists for its bit.
//
// The first eleven cases are Android's ScanPartitionTest case for case, on its fixture (12 spendable
// outputs, 13 asset carriers, 9 last outputs held for an unknown remainder, 12 rows listed twice). Its
// distinct-address and distinct-outpoint cases run against BRReconcilePlan.h, which owns those lists. Its
// heldKindOf case gives the hold rule's two targetsOutput answers for its fixture (one instruction, 1 unit
// to output 0, three outputs): output 2 is named only when the inputs' units are unknown, output 0 always.
// The rest are this header's own: the code table, a move onto its own class, a move that moves nothing,
// arguments, and the move's cost. Every expectation is written here by hand.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <time.h>

#include "BRScanPartition.h"
#include "BRReconcilePlan.h"

int scan_partition_kat_tu2(void);

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

#define N(a) (sizeof(a) / sizeof((a)[0]))

// ---- the fixture (ScanPartitionTest's) ---------------------------------------------------------

typedef struct {
    char txid[65];
    uint32_t vout;
    int64_t sat;
} Entry;   // UtxoEntry: what the test reads of it

static Entry entry(const char *tag, uint32_t vout, int64_t sat)
{
    Entry e;
    size_t n = strlen(tag);
    memset(e.txid, '0', 64);
    memcpy(e.txid, tag, n);
    e.txid[64] = '\0';
    e.vout = vout; e.sat = sat;
    return e;
}

static BRUTXO keyOf(const Entry *e)
{
    BRUTXO u;
    u.hash = UInt256Reverse(uint256(e->txid));
    u.n = e->vout;
    return u;
}

static BRScanRow rowOf(const Entry *e)
{
    BRScanRow r;
    r.outpoint = keyOf(e);
    r.amountSat = e->sat;
    return r;
}

static const int64_t kSpendable[12] = {
    1000000, 2000000, 4938200, 4984600, 5000000, 8984600, 10000000,
    12692700, 29945300, 48720617, 59725400, 141081483,
};
static const int64_t kRemainder[9] = { 3945300, 14945300, 4945300, 4945300, 4945300, 4945300, 6661, 6661, 6661 };
static const int64_t kWalletBalance = 329072900;

#define MAX_ROWS 64
static Entry spendable[12], carriers[13], remainders[9];
static Entry nodeRows[MAX_ROWS];
static size_t nodeRowCount;

static void fixture(void)
{
    size_t i, n = 0;
    char tag[4];
    for (i = 0; i < 12; i++) { snprintf(tag, sizeof(tag), "a%02zu", i); spendable[i] = entry(tag, 1, kSpendable[i]); }
    for (i = 0; i < 13; i++) { snprintf(tag, sizeof(tag), "b%02zu", i); carriers[i] = entry(tag, 0, i < 9 ? 6000 : 10000); }
    for (i = 0; i < 9; i++) { snprintf(tag, sizeof(tag), "c%02zu", i); remainders[i] = entry(tag, 2, kRemainder[i]); }
    for (i = 0; i < 12; i++) nodeRows[n++] = spendable[i];
    for (i = 0; i < 13; i++) nodeRows[n++] = carriers[i];
    for (i = 0; i < 9; i++) nodeRows[n++] = remainders[i];
    // the rows the node listed a second time: 5 spendable, 6 carriers, 1 remainder (other case)
    static const int rs[] = { 0, 1, 4, 6, 8 }, rc[] = { 0, 1, 2, 9, 10, 11 };
    for (i = 0; i < N(rs); i++) nodeRows[n++] = spendable[rs[i]];
    for (i = 0; i < N(rc); i++) nodeRows[n++] = carriers[rc[i]];
    Entry up = remainders[1];
    for (i = 0; i < 64; i++) if (up.txid[i] >= 'a' && up.txid[i] <= 'f') up.txid[i] = (char)(up.txid[i] - 'a' + 'A');
    nodeRows[n++] = up;
    nodeRowCount = n;
}

// The wallet's codes for the fixture: spendable SPENDABLE, carriers and remainders HELD, else NOT_HELD;
// `over` replaces the code of named outpoints.
typedef struct { Entry e; int32_t code; } Override;

static int32_t codeFor(const Entry *e, const Override *over, size_t nOver)
{
    BRUTXO k = keyOf(e);
    size_t i;
    for (i = 0; i < nOver; i++) { BRUTXO o = keyOf(&over[i].e); if (BRUTXOEq(&o, &k)) return over[i].code; }
    for (i = 0; i < 12; i++) { BRUTXO o = keyOf(&spendable[i]); if (BRUTXOEq(&o, &k)) return BR_OUTPOINT_SPENDABLE; }
    for (i = 0; i < 13; i++) { BRUTXO o = keyOf(&carriers[i]); if (BRUTXOEq(&o, &k)) return BR_OUTPOINT_HELD; }
    for (i = 0; i < 9; i++) { BRUTXO o = keyOf(&remainders[i]); if (BRUTXOEq(&o, &k)) return BR_OUTPOINT_HELD; }
    return BR_OUTPOINT_NOT_HELD;
}

// The test's heldKind: a txid starting with c (either case) is held for an unknown remainder, else an asset.
static int32_t heldKindFor(const Entry *e)
{
    return (e->txid[0] == 'c' || e->txid[0] == 'C') ? BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER : BR_SCAN_HELD_KIND_ASSET;
}

// The rows of one scan: distinct (BRReconcileDistinctOutpoints), with their codes and held kinds.
typedef struct {
    Entry e[MAX_ROWS];
    BRScanRow rows[MAX_ROWS];
    int32_t codes[MAX_ROWS], kinds[MAX_ROWS];
    size_t n;
} Scan;

static void distinctRows(const Entry *in, size_t inCount, const Override *over, size_t nOver, Scan *s)
{
    BRUTXO keys[MAX_ROWS];
    size_t keep[MAX_ROWS], kept = 0, i;
    for (i = 0; i < inCount; i++) keys[i] = keyOf(&in[i]);
    ck(BRReconcileDistinctOutpoints(keys, inCount, keep, MAX_ROWS, &kept) == BR_RECONCILE_OK, "distinct outpoints");
    s->n = kept;
    for (i = 0; i < kept; i++) {
        s->e[i] = in[keep[i]];
        s->rows[i] = rowOf(&s->e[i]);
        s->codes[i] = codeFor(&s->e[i], over, nOver);
        s->kinds[i] = heldKindFor(&s->e[i]);
    }
}

static int scan(const Entry *in, size_t inCount, const Override *over, size_t nOver, BRScanClassTotal walletOnly,
                Scan *s, BRScanPartition *p)
{
    distinctRows(in, inCount, over, nOver, s);
    return BRScanPartitionMake(s->rows, s->n, s->codes, s->n, s->kinds, &walletOnly, kWalletBalance, p);
}

static int totalIs(const BRScanPartition *p, BRScanClass c, int64_t count, int64_t sat)
{
    BRScanClassTotal t = BRScanPartitionTotal(p, c);
    return t.count == count && t.sat == sat;
}

static const BRScanClassTotal kNone = { 0, 0 };

// ---- ScanPartitionTest, case for case ----------------------------------------------------------

static void fixture_adds_up(void)
{
    int64_t sum = 0, sp = 0, rep = 0;
    size_t i;
    for (i = 0; i < nodeRowCount; i++) sum += nodeRows[i].sat;
    for (i = 0; i < 12; i++) sp += kSpendable[i];
    for (i = 34; i < nodeRowCount; i++) rep += nodeRows[i].sat;
    ck(nodeRowCount == 46, "46 rows (%zu)", nodeRowCount);
    ck(sum == 430797283, "430,797,283 sat summed raw (%lld)", (long long)sum);
    ck(sp == kWalletBalance, "the spendable rows sum to the wallet's balance");
    ck(rep == 62938600, "the repeated rows hold 62,938,600 sat (%lld)", (long long)rep);
}

static void address_sent_once(void)
{
    const char *dump[] = { "dgb1qa", "dgb1qb", "dgb1qa", " dgb1qc ", "", "dgb1qb" };
    const char *want[] = { "dgb1qa", "dgb1qb", "dgb1qc" };
    BRReconcileAddressSpan out[N(dump)];
    size_t n = 0, i;
    ck(BRReconcileDistinctAddresses(dump, N(dump), out, N(out), &n) == BR_RECONCILE_OK, "distinct addresses");
    ck(n == 3, "three addresses (%zu)", n);
    for (i = 0; i < n && i < 3; i++)
        ck(out[i].length == strlen(want[i]) && memcmp(dump[out[i].index] + out[i].offset, want[i], out[i].length) == 0,
           "address %zu is %s", i, want[i]);
}

static void outpoint_once_any_case(void)
{
    Scan s;
    int64_t sum = 0;
    size_t i, j, dup = 0;
    distinctRows(nodeRows, nodeRowCount, NULL, 0, &s);
    for (i = 0; i < s.n; i++) sum += s.e[i].sat;
    for (i = 0; i < s.n; i++) for (j = i + 1; j < s.n; j++) if (BRUTXOEq(&s.rows[i].outpoint, &s.rows[j].outpoint)) dup++;
    ck(s.n == 34, "34 distinct outpoints (%zu)", s.n);
    ck(sum == 430797283 - 62938600, "their sum is the raw sum less the repeats (%lld)", (long long)sum);
    ck(dup == 0, "no outpoint twice");
}

static void headline_is_spendable_balance(void)
{
    Scan s;
    BRScanPartition p;
    int64_t cnt = 0, sat = 0;
    int c;
    ck(scan(nodeRows, nodeRowCount, NULL, 0, kNone, &s, &p), "a partition");
    ck(BRScanPartitionSpendableSat(&p) == kWalletBalance, "the headline is the wallet's balance");
    ck(totalIs(&p, BR_SCAN_SPENDABLE, 12, 329072900), "SPENDABLE (12, 329,072,900)");
    ck(totalIs(&p, BR_SCAN_HELD_ASSET, 13, 94000), "HELD_ASSET (13, 94,000)");
    ck(totalIs(&p, BR_SCAN_HELD_UNKNOWN, 9, 38691783), "HELD_UNKNOWN (9, 38,691,783)");
    ck(totalIs(&p, BR_SCAN_NOT_IN_WALLET, 0, 0), "NOT_IN_WALLET (0, 0)");
    ck(p.walletOnly.count == 0, "no wallet-only output");
    ck(BRScanPartitionMatchesWallet(&p), "matches the wallet");
    for (c = 0; c < BR_SCAN_CLASS_COUNT; c++) { cnt += BRScanPartitionTotal(&p, (BRScanClass)c).count; sat += BRScanPartitionTotal(&p, (BRScanClass)c).sat; }
    ck(cnt == 34, "the classes add up to the distinct rows (%lld)", (long long)cnt);
    ck(sat == 329072900 + 94000 + 38691783, "and their sums to the distinct sum");
    BRScanPartitionFree(&p);
}

static void only_not_credited_missing(void)
{
    Entry rows[MAX_ROWS];
    Scan s;
    BRScanPartition p;
    size_t n = nodeRowCount;
    memcpy(rows, nodeRows, n * sizeof(Entry));
    Entry stranger = entry("f0", 0, 25000000), watchedOnly = entry("f1", 3, 1000);
    rows[n++] = stranger; rows[n++] = watchedOnly;
    Override over[] = { { watchedOnly, BR_OUTPOINT_NOT_CREDITED } };
    ck(scan(rows, n, over, N(over), kNone, &s, &p), "a partition");
    ck(totalIs(&p, BR_SCAN_NOT_IN_WALLET, 2, 25001000), "NOT_IN_WALLET (2, 25,001,000)");
    ck(BRScanPartitionSpendableSat(&p) == kWalletBalance, "the headline is the wallet's balance");
    ck(! BRScanPartitionMatchesWallet(&p), "does not match the wallet");
    BRScanPartitionFree(&p);
}

static void dd_immature_pending_listed(void)
{
    Entry rows[MAX_ROWS];
    Scan s;
    BRScanPartition p;
    size_t n = nodeRowCount;
    memcpy(rows, nodeRows, n * sizeof(Entry));
    Entry dd = entry("d0", 0, 0), mined = entry("d1", 0, 72000000000LL), pending = entry("d2", 1, 5000);
    rows[n++] = dd; rows[n++] = mined; rows[n++] = pending;
    Override over[] = { { dd, BR_OUTPOINT_DIGIDOLLAR }, { mined, BR_OUTPOINT_IMMATURE }, { pending, BR_OUTPOINT_SPENT } };
    ck(scan(rows, n, over, N(over), kNone, &s, &p), "a partition");
    ck(BRScanPartitionTotal(&p, BR_SCAN_DIGIDOLLAR).count == 1, "one DIGIDOLLAR");
    ck(totalIs(&p, BR_SCAN_IMMATURE, 1, 72000000000LL), "IMMATURE (1, 72,000,000,000)");
    ck(totalIs(&p, BR_SCAN_PENDING, 1, 5000), "PENDING (1, 5,000)");
    ck(BRScanPartitionTotal(&p, BR_SCAN_NOT_IN_WALLET).count == 0, "nothing missing");
    ck(BRScanPartitionMatchesWallet(&p), "matches the wallet");
    BRScanPartitionFree(&p);
}

static void wallet_only_breaks_match(void)
{
    Scan s;
    BRScanPartition p;
    const int64_t answer[2] = { 1, 7000 };
    BRScanClassTotal wo;
    ck(BRScanWalletOnlyOf(answer, 2, &wo), "the answer reads");
    ck(scan(nodeRows, nodeRowCount, NULL, 0, wo, &s, &p), "a partition");
    ck(p.walletOnly.count == 1 && p.walletOnly.sat == 7000, "walletOnly (1, 7,000)");
    ck(! BRScanPartitionMatchesWallet(&p), "does not match the wallet");
    BRScanPartitionFree(&p);
}

static void no_wallet_no_partition(void)
{
    Scan s;
    BRScanPartition p;
    BRScanClassTotal wo;
    distinctRows(nodeRows, nodeRowCount, NULL, 0, &s);
    ck(! BRScanPartitionMake(s.rows, s.n, NULL, s.n, s.kinds, &kNone, kWalletBalance, &p), "no codes: none");
    ck(! BRScanPartitionMake(s.rows, s.n, s.codes, s.n - 1, s.kinds, &kNone, kWalletBalance, &p), "one code short: none");
    ck(! BRScanWalletOnlyOf(NULL, 0, &wo), "no wallet-only answer reads as none");
    ck(! BRScanPartitionMake(s.rows, s.n, s.codes, s.n, s.kinds, NULL, kWalletBalance, &p), "no wallet-only answer: none");
    BRScanPartitionFree(&p);
}

static void proven_remainder_moves(void)
{
    Scan s;
    BRScanPartition p, q;
    BRUTXO proven[MAX_ROWS];
    size_t nProven = 0, i, recorded = 0;
    ck(scan(nodeRows, nodeRowCount, NULL, 0, kNone, &s, &p), "a partition");
    // proveHeldRemainders: every HELD_UNKNOWN member is asked; 6,661-sat ones stay held, the rest are proven
    for (i = 0; i < p.memberCount[BR_SCAN_HELD_UNKNOWN]; i++) {
        size_t r = p.members[BR_SCAN_HELD_UNKNOWN][i];
        if (s.rows[r].amountSat != 6661) { proven[nProven++] = s.rows[r].outpoint; recorded++; }
    }
    ck(BRScanPartitionMove(&p, s.rows, s.n, proven, nProven, BR_SCAN_HELD_UNKNOWN, BR_SCAN_PROVEN_PLAIN, &q), "moved");
    ck(totalIs(&q, BR_SCAN_PROVEN_PLAIN, 6, 38691783 - 3 * 6661), "PROVEN_PLAIN (6, 38,691,783 - 3 x 6,661)");
    ck(totalIs(&q, BR_SCAN_HELD_UNKNOWN, 3, 3 * 6661), "HELD_UNKNOWN (3, 3 x 6,661)");
    ck(recorded == 6, "six recorded");
    ck(BRScanPartitionSpendableSat(&q) == kWalletBalance, "moving a class never changes the headline");
    ck(totalIs(&p, BR_SCAN_HELD_UNKNOWN, 9, 38691783), "the partition moved from is unchanged");
    BRScanPartitionFree(&p);
    BRScanPartitionFree(&q);
}

static BRScanHeldFacts facts(int heldNow /* 1, 0, -1 for null */, int hasRow, int64_t row, int vout)
{
    BRScanHeldFacts f;
    f.hasRowQuantity = hasRow; f.rowQuantity = row;
    f.namedAnyway = (vout == 0);   // the instruction names output 0
    f.namedIfUnknown = 1;          // output 0 by the instruction; output 2, the last, by an unknown remainder
    f.heldByRuleNow = heldNow;
    return f;
}

static void held_kind_of(void)
{
    BRScanHeldFacts f;
    f = facts(1, 1, 0, 2);  ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER, "held now, row 0: UNKNOWN_REMAINDER");
    f = facts(1, 0, 0, 2);  ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER, "held now, no row: UNKNOWN_REMAINDER");
    f = facts(-1, 1, 0, 2); ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER, "no answer: still UNKNOWN_REMAINDER");
    f = facts(0, 1, 0, 2);  ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_PROVEN_ZERO, "not held now: PROVEN_ZERO");
    f = facts(1, 1, 4, 2);  ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_ASSET, "a stored quantity: ASSET");
    f = facts(0, 1, 0, 0);  ck(BRScanHeldKindOf(&f) == BR_SCAN_HELD_KIND_ASSET, "named by an instruction: ASSET");
    ck(BRScanHeldKindOf(NULL) == BR_SCAN_HELD_KIND_ASSET, "unreadable: ASSET");
}

static void wallet_only_strict(void)
{
    BRScanClassTotal wo;
    const int64_t a[2] = { 2, 9000 }, one[1] = { 1 }, neg[2] = { -1, 0 };
    ck(BRScanWalletOnlyOf(a, 2, &wo) && wo.count == 2 && wo.sat == 9000, "(2, 9,000)");
    ck(! BRScanWalletOnlyOf(NULL, 2, &wo), "null: none");
    ck(! BRScanWalletOnlyOf(one, 1, &wo), "one value: none");
    ck(! BRScanWalletOnlyOf(neg, 2, &wo), "a negative count: none");
}

// ---- this header's own -------------------------------------------------------------------------

static void code_table(void)
{
    static const struct { int32_t code; int32_t kind; BRScanClass want; } t[] = {
        { BR_OUTPOINT_SPENDABLE, 0, BR_SCAN_SPENDABLE },
        { BR_OUTPOINT_DIGIDOLLAR, 0, BR_SCAN_DIGIDOLLAR },
        { BR_OUTPOINT_IMMATURE, 0, BR_SCAN_IMMATURE },
        { BR_OUTPOINT_SPENT, 0, BR_SCAN_PENDING },
        { BR_OUTPOINT_PENDING, 0, BR_SCAN_PENDING },
        { BR_OUTPOINT_HELD, BR_SCAN_HELD_KIND_ASSET, BR_SCAN_HELD_ASSET },
        { BR_OUTPOINT_HELD, BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER, BR_SCAN_HELD_UNKNOWN },
        { BR_OUTPOINT_HELD, BR_SCAN_HELD_KIND_PROVEN_ZERO, BR_SCAN_PROVEN_PLAIN },
        { BR_OUTPOINT_NOT_CREDITED, 0, BR_SCAN_NOT_IN_WALLET },
        { BR_OUTPOINT_NOT_HELD, 0, BR_SCAN_NOT_IN_WALLET },
        { BR_OUTPOINT_CONFLICTED, 0, BR_SCAN_NOT_IN_WALLET },
        { BR_OUTPOINT_UNREADABLE, 0, BR_SCAN_NOT_IN_WALLET },
        { 7, 0, BR_SCAN_NOT_IN_WALLET },
        { -4, 0, BR_SCAN_NOT_IN_WALLET },
        { INT32_MAX, 0, BR_SCAN_NOT_IN_WALLET },
    };
    size_t i;
    for (i = 0; i < N(t); i++)
        ck(BRScanClassForCode(t[i].code, (BRScanHeldKind)t[i].kind) == t[i].want, "code %d (held kind %d) -> class %d",
           (int)t[i].code, (int)t[i].kind, (int)t[i].want);
    ck(BRScanClassForCode(BR_OUTPOINT_HELD, (BRScanHeldKind)9) == BR_SCAN_HELD_ASSET, "an unknown held kind: HELD_ASSET");
    // a HELD row with an unknown held kind, or none, is no partition; a HELD row is the only one read
    BRScanRow r[2];
    int32_t codes[2] = { BR_OUTPOINT_HELD, BR_OUTPOINT_SPENDABLE }, kinds[2] = { 3, 99 };
    BRScanPartition p;
    Entry a = entry("e0", 0, 10), b = entry("e1", 0, 20);
    r[0] = rowOf(&a); r[1] = rowOf(&b);
    ck(! BRScanPartitionMake(r, 2, codes, 2, kinds, &kNone, 0, &p), "an unknown held kind: none");
    ck(! BRScanPartitionMake(r, 2, codes, 2, NULL, &kNone, 0, &p), "no held kinds for a HELD row: none");
    kinds[0] = BR_SCAN_HELD_KIND_ASSET;
    ck(BRScanPartitionMake(r, 2, codes, 2, kinds, &kNone, 20, &p), "a non-HELD row's held kind is not read");
    ck(totalIs(&p, BR_SCAN_HELD_ASSET, 1, 10) && totalIs(&p, BR_SCAN_SPENDABLE, 1, 20), "one each");
    BRScanPartitionFree(&p);
    codes[0] = BR_OUTPOINT_SPENT;
    ck(BRScanPartitionMake(r, 2, codes, 2, NULL, &kNone, 20, &p), "no HELD row needs no held kinds");
    BRScanPartitionFree(&p);
}

static void move_onto_itself(void)
{
    Scan s;
    BRScanPartition p, q;
    BRUTXO k[1];
    ck(scan(nodeRows, nodeRowCount, NULL, 0, kNone, &s, &p), "a partition");
    if (p.memberCount[BR_SCAN_HELD_UNKNOWN] < 1) { ck(0, "HELD_UNKNOWN has members"); BRScanPartitionFree(&p); return; }
    k[0] = s.rows[p.members[BR_SCAN_HELD_UNKNOWN][0]].outpoint;
    ck(BRScanPartitionMove(&p, s.rows, s.n, k, 1, BR_SCAN_HELD_UNKNOWN, BR_SCAN_HELD_UNKNOWN, &q), "moved");
    ck(totalIs(&q, BR_SCAN_HELD_UNKNOWN, 10, 38691783 + 3945300), "as Kotlin: the member is appended again (10)");
    ck(q.members[BR_SCAN_HELD_UNKNOWN][9] == q.members[BR_SCAN_HELD_UNKNOWN][0], "at the end");
    BRScanPartitionFree(&p);
    BRScanPartitionFree(&q);
}

static void move_nothing_and_order(void)
{
    Scan s;
    BRScanPartition p, q;
    BRUTXO k[3];
    Entry stranger = entry("ff", 9, 1);
    size_t i;
    ck(scan(nodeRows, nodeRowCount, NULL, 0, kNone, &s, &p), "a partition");
    ck(BRScanPartitionMove(&p, s.rows, s.n, NULL, 0, BR_SCAN_HELD_UNKNOWN, BR_SCAN_PROVEN_PLAIN, &q), "nothing to move");
    for (i = 0; i < BR_SCAN_CLASS_COUNT; i++)
        ck(q.memberCount[i] == p.memberCount[i] && (q.memberCount[i] == 0 ||
           memcmp(q.members[i], p.members[i], p.memberCount[i] * sizeof(size_t)) == 0), "class %zu unchanged", i);
    BRScanPartitionFree(&q);
    k[0] = keyOf(&stranger);
    ck(BRScanPartitionMove(&p, s.rows, s.n, k, 1, BR_SCAN_HELD_UNKNOWN, BR_SCAN_PROVEN_PLAIN, &q), "nothing of from named");
    ck(totalIs(&q, BR_SCAN_HELD_UNKNOWN, 9, 38691783) && totalIs(&q, BR_SCAN_PROVEN_PLAIN, 0, 0), "unchanged");
    BRScanPartitionFree(&q);
    // moved named in reverse; they arrive in from's order, after to's own
    if (p.memberCount[BR_SCAN_HELD_UNKNOWN] < 5) { ck(0, "HELD_UNKNOWN has five members"); BRScanPartitionFree(&p); return; }
    size_t a = p.members[BR_SCAN_HELD_UNKNOWN][0], b = p.members[BR_SCAN_HELD_UNKNOWN][4];
    k[0] = s.rows[b].outpoint; k[1] = keyOf(&stranger); k[2] = s.rows[a].outpoint;
    ck(BRScanPartitionMove(&p, s.rows, s.n, k, 3, BR_SCAN_HELD_UNKNOWN, BR_SCAN_HELD_ASSET, &q), "moved two");
    ck(q.memberCount[BR_SCAN_HELD_ASSET] == 15 && q.members[BR_SCAN_HELD_ASSET][13] == a &&
       q.members[BR_SCAN_HELD_ASSET][14] == b, "appended after to's own, in from's order");
    ck(q.memberCount[BR_SCAN_HELD_UNKNOWN] == 7 && q.members[BR_SCAN_HELD_UNKNOWN][0] == p.members[BR_SCAN_HELD_UNKNOWN][1],
       "from keeps the rest in order");
    BRScanPartitionFree(&q);
    BRScanPartitionFree(&p);
}

static void arguments(void)
{
    BRScanPartition p, q;
    BRScanClassTotal wo;
    BRUTXO k[1];
    memset(k, 0, sizeof(k));
    ck(! BRScanPartitionMake(NULL, 0, NULL, 0, NULL, &kNone, 0, &p), "no codes, even for no rows: none");
    int32_t none[1] = { 0 };
    ck(BRScanPartitionMake(NULL, 0, none, 0, NULL, &kNone, 5, &p), "no rows: an empty partition");
    ck(! BRScanPartitionMatchesWallet(&p), "and it does not match a wallet holding 5");
    ck(! BRScanPartitionMove(&p, NULL, 0, k, 1, (BRScanClass)8, BR_SCAN_SPENDABLE, &q), "a class out of range");
    ck(! BRScanPartitionMove(&p, NULL, 0, NULL, 1, BR_SCAN_SPENDABLE, BR_SCAN_SPENDABLE, &q), "moved NULL with a count");
    ck(! BRScanPartitionMove(NULL, NULL, 0, k, 1, BR_SCAN_SPENDABLE, BR_SCAN_SPENDABLE, &q), "no partition");
    BRScanPartitionFree(&p);
    BRScanPartitionFree(NULL);
    ck(! BRScanPartitionMatchesWallet(NULL), "no partition does not match");
    ck(BRScanPartitionTotal(NULL, BR_SCAN_SPENDABLE).count == 0, "no partition totals nothing");
    ck(! BRScanWalletOnlyOf((const int64_t[]){ 1, 2 }, 2, NULL), "nowhere to write");
    const int64_t big[2] = { (int64_t)INT32_MAX + 5, 1 };
    ck(BRScanWalletOnlyOf(big, 2, &wo) && wo.count == INT32_MAX, "a count above Int.MAX reads as Int.MAX");
    // wrapping sums, as a Long fold
    BRScanRow r[2];
    int32_t codes[2] = { BR_OUTPOINT_SPENDABLE, BR_OUTPOINT_SPENDABLE };
    Entry a = entry("e0", 0, INT64_MAX), b = entry("e1", 0, 1);
    r[0] = rowOf(&a); r[1] = rowOf(&b);
    ck(BRScanPartitionMake(r, 2, codes, 2, NULL, &kNone, 0, &p), "a partition");
    ck(BRScanPartitionSpendableSat(&p) == INT64_MIN, "a sum wraps as a Long");
    // a member index out of the rows given is refused
    ck(! BRScanPartitionMove(&p, r, 1, k, 1, BR_SCAN_SPENDABLE, BR_SCAN_PENDING, &q), "rows shorter than the partition");
    BRScanPartitionFree(&p);
    ck(scan_partition_kat_tu2() == 1, "a second translation unit links");
}

static double timeMove(size_t n)
{
    double best = 1e30;
    int rep;
    for (rep = 0; rep < 3; rep++) {
        BRScanRow *rows = malloc(n * sizeof(*rows));
        int32_t *codes = malloc(n * sizeof(*codes)), *kinds = malloc(n * sizeof(*kinds));
        BRUTXO *moved = malloc(n / 2 * sizeof(*moved));
        BRScanPartition p, q;
        size_t i, m = 0;
        for (i = 0; i < n; i++) {
            memset(&rows[i], 0, sizeof(rows[i]));
            rows[i].outpoint.hash.u8[0] = (uint8_t)(i * 31); rows[i].outpoint.hash.u8[1] = (uint8_t)(i >> 8);
            rows[i].outpoint.hash.u8[2] = (uint8_t)(i >> 16); rows[i].outpoint.n = (uint32_t)i;
            rows[i].amountSat = 1;
            codes[i] = BR_OUTPOINT_HELD; kinds[i] = BR_SCAN_HELD_KIND_UNKNOWN_REMAINDER;
            if (i & 1) moved[m++] = rows[i].outpoint;
        }
        BRScanPartitionMake(rows, n, codes, n, kinds, &kNone, 0, &p);
        clock_t t0 = clock();
        BRScanPartitionMove(&p, rows, n, moved, m, BR_SCAN_HELD_UNKNOWN, BR_SCAN_PROVEN_PLAIN, &q);
        double dt = (double)(clock() - t0) / CLOCKS_PER_SEC;
        if (dt < best) best = dt;
        BRScanPartitionFree(&p); BRScanPartitionFree(&q);
        free(rows); free(codes); free(kinds); free(moved);
    }
    return best > 1e-6 ? best : 1e-6;
}

static void cost_n_log_n(void)
{
    double a = timeMove(20000), b = timeMove(320000);
    ck(b / a < 64.0, "move: 16x the rows took %.1fx the time (O(n log n) ~20x, quadratic 256x)", b / a);
}

// ---- driver ------------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "fixture_adds_up", fixture_adds_up },
    { "address_sent_once", address_sent_once },
    { "outpoint_once_any_case", outpoint_once_any_case },
    { "headline_is_spendable_balance", headline_is_spendable_balance },
    { "only_not_credited_missing", only_not_credited_missing },
    { "dd_immature_pending_listed", dd_immature_pending_listed },
    { "wallet_only_breaks_match", wallet_only_breaks_match },
    { "no_wallet_no_partition", no_wallet_no_partition },
    { "proven_remainder_moves", proven_remainder_moves },
    { "held_kind_of", held_kind_of },
    { "wallet_only_strict", wallet_only_strict },
    { "code_table", code_table },
    { "move_onto_itself", move_onto_itself },
    { "move_nothing_and_order", move_nothing_and_order },
    { "arguments", arguments },
    { "cost_n_log_n", cost_n_log_n },
};

int main(int argc, char **argv)
{
    size_t i;
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    fixture();
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            printf("RESULT %s %s\n", argv[1], g_failed ? "fail" : "pass");
            return g_failed ? 1 : 0;
        }
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
