// Host KAT: which unspent outputs a Recover-funds scan counts and sweeps (BRRecoveryUtxo.h).
//
// THE RULES (moved from Kotlin: recovery/UtxoDedup.kt, RecoveryScanService.classifyDerived's P2TR
// filter, LegacySweepService.admit's non-positive refusal):
//   - one coin per outpoint, standing where it was first seen; the first confirmed copy (height > 0)
//     is kept, else the first copy;
//   - on a P2TR profile only a positive amount is a coin (a zero-value output there is a DigiDollar
//     token output); other profiles keep every row; applied after the one-coin rule;
//   - a profile is swept only when its lookup was answered and every selected row is positive; the
//     first non-positive row is reported.
// The comparison arm (-DRECOVERY_UTXO_UNFIXED=1) keeps the unconfirmed copy, keeps zero-value P2TR
// rows, and admits a zero amount.
//
// Cases carry Android's DuplicateUtxoTest (every test), RecoveryScanClassifyTest's taproot case and
// AmountProvenanceGateTest's amount cases, with their values. Every expectation is written here by hand.
// Not carried: AmountProvenanceGateTest's parent-transaction cases (RawTxBinding stays Kotlin; the
// header only promises the non-positive rule runs before it, which gate_unreachable_first and
// sweep_zero_after_select pin from this side).
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>

#include "BRRecoveryUtxo.h"

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

// A row whose txid is the hex prefix `hex` (as Kotlin's test txids are short) padded with zero bytes.
static BRRecoveryUtxo row(const char *hex, uint32_t vout, int64_t sats, int64_t height)
{
    BRRecoveryUtxo u;
    size_t i, n = strlen(hex) / 2;
    memset(&u, 0, sizeof(u));
    for (i = 0; i < n && i < 32; i++) {
        unsigned b = 0;
        sscanf(hex + 2 * i, "%2x", &b);
        u.txid[i] = (uint8_t)b;
    }
    u.vout = vout;
    u.amount = sats;
    u.blockHeight = height;
    return u;
}

#define N(a) (sizeof(a) / sizeof((a)[0]))

// The real pair (DuplicateUtxoTest), and the unrelated coin beside it.
#define CONFIRMED  row("6bd35c442896c27f", 0, 6000, 24106618)
#define UNCONF     row("6bd35c442896c27f", 0, 6000, 0)
#define OTHER      row("ac3cbb77c252e78d", 0, 500000000, 0)

// The rules' answer for in[0..n), copied to kept[0..k): the working space (2n) is the helper's.
static size_t dedup(const BRRecoveryUtxo *in, size_t n, size_t *kept)
{
    size_t k = 99, cap = BRRecoveryUtxoWorkCapacity(n), *work = malloc((cap ? cap : 1) * sizeof(size_t));
    BRRecoveryUtxoStatus s = BRRecoveryUtxoDedup(in, n, work, cap, &k);
    ck(s == BR_RECOVERY_UTXO_OK, "dedup status OK (got %d)", (int)s);
    if (s == BR_RECOVERY_UTXO_OK && k) memcpy(kept, work, k * sizeof(size_t));
    free(work);
    return k;
}

static int64_t total(const BRRecoveryUtxo *in, size_t n, const size_t *idx, size_t k)
{
    int64_t t = -1;
    BRRecoveryUtxoStatus s = BRRecoveryUtxoTotal(in, n, idx, k, &t);
    ck(s == BR_RECOVERY_UTXO_OK, "total status OK (got %d)", (int)s);
    return t;
}

static int sameRow(const BRRecoveryUtxo *a, const BRRecoveryUtxo *b)
{
    return memcmp(a, b, sizeof(*a)) == 0;
}

// ---- DuplicateUtxoTest -------------------------------------------------------------------------

static void same_outpoint_twice_collapses(void)
{
    BRRecoveryUtxo in[] = { CONFIRMED, UNCONF, OTHER };
    BRRecoveryUtxo pair = CONFIRMED;
    size_t kept[3], k = dedup(in, N(in), kept), i, c = 0;
    ck(k == 2, "three rows, two outpoints: %zu kept, want 2", k);
    for (i = 0; i < k; i++) if (BRRecoveryUtxoSameOutpoint(&in[kept[i]], &pair)) c++;
    ck(c == 1, "6bd35c442896c27f:0 kept once (%zu)", c);
}

static void total_stops_double_counting(void)
{
    BRRecoveryUtxo in[] = { CONFIRMED, UNCONF, OTHER };
    size_t kept[3], k;
    ck(total(in, N(in), NULL, N(in)) == 500012000, "the raw sum is inflated: 500012000");
    k = dedup(in, N(in), kept);
    ck(total(in, N(in), kept, k) == 500006000, "the real total: 500006000");
}

static void confirmed_copy_wins(void)
{
    BRRecoveryUtxo a[] = { UNCONF, CONFIRMED }, b[] = { CONFIRMED, UNCONF };
    size_t kept[2], k;
    k = dedup(a, 2, kept);
    ck(k == 1 && a[kept[0]].blockHeight == 24106618,
       "unconfirmed first: the kept copy is at 24106618 (got %" PRId64 ")", k ? a[kept[0]].blockHeight : -1);
    k = dedup(b, 2, kept);
    ck(k == 1 && b[kept[0]].blockHeight == 24106618,
       "confirmed first: the kept copy is at 24106618 (got %" PRId64 ")", k ? b[kept[0]].blockHeight : -1);
}

static void different_vouts_not_duplicates(void)
{
    BRRecoveryUtxo in[] = { row("aaaa", 0, 1000, 100), row("aaaa", 1, 2000, 100) };
    size_t kept[2];
    ck(dedup(in, 2, kept) == 2, "aaaa:0 and aaaa:1 are two coins");
}

static void order_preserved(void)
{
    BRRecoveryUtxo in[] = { OTHER, CONFIRMED, UNCONF };
    BRRecoveryUtxo other = OTHER;
    size_t kept[3], k = dedup(in, 3, kept);
    ck(k == 2 && sameRow(&in[kept[0]], &other), "ac3cbb77c252e78d stays first");
    ck(k == 2 && kept[0] == 0, "the first index is 0");
}

static void empty_list_stays_empty(void)
{
    size_t k = 99;
    ck(BRRecoveryUtxoDedup(NULL, 0, NULL, 0, &k) == BR_RECOVERY_UTXO_OK && k == 0, "empty in, empty out");
}

static void clean_list_unchanged(void)
{
    BRRecoveryUtxo in[] = { CONFIRMED, OTHER };
    size_t kept[2], k = dedup(in, 2, kept);
    ck(k == 2 && kept[0] == 0 && kept[1] == 1, "a list with no repeats comes back as it was");
}

// ---- the tie rules UtxoDedup.kt states --------------------------------------------------------

static void confirmed_copies_keep_first(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1000, 100), row("aa", 0, 1000, 200) };
    size_t kept[2], k = dedup(in, 2, kept);
    ck(k == 1 && kept[0] == 0, "two confirmed copies: the first is kept (index %zu)", k ? kept[0] : 99);
}

static void unconfirmed_copies_keep_first(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1000, 0), row("aa", 0, 2000, 0) };
    size_t kept[2], k = dedup(in, 2, kept);
    ck(k == 1 && kept[0] == 0, "two unconfirmed copies: the first is kept");
}

static void negative_height_is_unconfirmed(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1000, -1), row("aa", 0, 1000, 5) };
    size_t kept[2], k = dedup(in, 2, kept);
    ck(k == 1 && kept[0] == 1, "height -1 is not in a block: the copy at 5 is kept");
}

static void confirmed_later_takes_first_place(void)
{
    BRRecoveryUtxo in[] = { row("bb", 0, 1, 9), row("aa", 0, 10, 0), row("cc", 0, 1, 9),
                            row("aa", 0, 10, 0), row("aa", 0, 10, 7) };
    size_t kept[5], k = dedup(in, 5, kept);
    ck(k == 3 && kept[0] == 0 && kept[1] == 4 && kept[2] == 2,
       "aa stands second (where first seen), as its confirmed copy (index 4): got %zu [%zu %zu %zu]",
       k, kept[0], k > 1 ? kept[1] : 99, k > 2 ? kept[2] : 99);
}

static void txid_last_byte_differs(void)
{
    BRRecoveryUtxo a = row("aa", 0, 1, 1), b = row("aa", 0, 1, 1);
    BRRecoveryUtxo in[2];
    size_t kept[2];
    b.txid[31] = 1;
    in[0] = a; in[1] = b;
    ck(dedup(in, 2, kept) == 2, "txids differing in their last byte are two outpoints");
}

// ---- RecoveryScanClassifyTest: the P2TR filter ------------------------------------------------

static size_t select_(int fmt, const BRRecoveryUtxo *in, size_t n, size_t *kept)
{
    size_t k = 99, cap = BRRecoveryUtxoWorkCapacity(n), *work = malloc((cap ? cap : 1) * sizeof(size_t));
    BRRecoveryUtxoStatus s = BRRecoveryUtxoSelect(fmt, in, n, work, cap, &k);
    ck(s == BR_RECOVERY_UTXO_OK, "select status OK (got %d)", (int)s);
    if (s == BR_RECOVERY_UTXO_OK && k) memcpy(kept, work, k * sizeof(size_t));
    free(work);
    return k;
}

static void taproot_keeps_dgb_drops_dollar_outputs(void)
{
    // classify_taprootProfile_findsDgbAndLeavesDollarOutputs
    BRRecoveryUtxo in[2] = { row("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 1, 500000000, 100),
                             row("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", 0, 0, 100) };
    size_t kept[2], k = select_(BR_RECOVERY_FORMAT_P2TR, in, 2, kept);
    ck(k == 1 && kept[0] == 0, "only the DGB coin is a coin on m/86' (kept %zu)", k);
    ck(total(in, 2, kept, k) == 500000000, "the profile quotes 500000000");
}

static void taproot_negative_dropped(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, -5, 100), row("bb", 0, 7, 100) };
    size_t kept[2], k = select_(BR_RECOVERY_FORMAT_P2TR, in, 2, kept);
    ck(k == 1 && kept[0] == 1, "a negative row is no coin on P2TR either");
}

static void non_taproot_zero_kept(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 0, 100), row("bb", 0, -1, 100) };
    size_t kept[2];
    int f;
    for (f = BR_RECOVERY_FORMAT_P2PKH; f <= BR_RECOVERY_FORMAT_P2SH_P2WPKH; f++) {
        ck(select_(f, in, 2, kept) == 2, "format %d keeps zero and negative rows for admission to refuse", f);
    }
}

static void unknown_format_keeps_zero(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 0, 100) };
    size_t kept[1];
    ck(select_(7, in, 1, kept) == 1, "a format code the header does not know is not P2TR");
    ck(select_(-1, in, 1, kept) == 1, "nor is -1");
}

static void dedup_before_filter(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 0, 100), row("aa", 0, 6000, 0) };
    size_t kept[2];
    ck(select_(BR_RECOVERY_FORMAT_P2TR, in, 2, kept) == 0,
       "a confirmed zero-value copy and an unconfirmed 6000 copy of one P2TR outpoint leave nothing");
}

// ---- AmountProvenanceGateTest: the amount cases -----------------------------------------------

static BRRecoverySweepAdmission admit(int reachable, const BRRecoveryUtxo *in, size_t n, size_t *off)
{
    return BRRecoverySweepAdmit(reachable, in, n, NULL, n, off);
}

#define P2PKH_ROW(sats) row("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0, (sats), 100)

static void gate_positive_reachable_admits(void)
{
    BRRecoveryUtxo in[] = { P2PKH_ROW(100000), P2PKH_ROW(250000) };
    size_t off = 0;
    ck(admit(1, in, 2, &off) == BR_RECOVERY_SWEEP_ADMIT && off == (size_t)-1, "positive amounts, answered: admitted");
}

static void gate_zero_refuses(void)
{
    BRRecoveryUtxo in[] = { P2PKH_ROW(0) };
    size_t off = 9;
    ck(admit(1, in, 1, &off) == BR_RECOVERY_SWEEP_REFUSE_NON_POSITIVE && off == 0, "a zero amount refuses the profile");
}

static void gate_negative_refuses(void)
{
    BRRecoveryUtxo in[] = { P2PKH_ROW(100000), P2PKH_ROW(-1) };
    size_t off = 9;
    ck(admit(1, in, 2, &off) == BR_RECOVERY_SWEEP_REFUSE_NON_POSITIVE && off == 1, "a negative amount refuses it, at index 1");
}

static void gate_unreachable_first(void)
{
    BRRecoveryUtxo ok[] = { P2PKH_ROW(100000) }, bad[] = { P2PKH_ROW(0) };
    size_t off = 9;
    ck(admit(0, ok, 1, &off) == BR_RECOVERY_SWEEP_REFUSE_UNREACHABLE && off == (size_t)-1, "unanswered lookup: refused");
    ck(admit(0, bad, 1, &off) == BR_RECOVERY_SWEEP_REFUSE_UNREACHABLE, "unanswered is reported before any row is read");
    ck(BRRecoverySweepAdmit(0, NULL, 0, NULL, 0, NULL) == BR_RECOVERY_SWEEP_REFUSE_UNREACHABLE, "even with no rows");
}

static void gate_reports_first_offending(void)
{
    BRRecoveryUtxo in[] = { P2PKH_ROW(100), P2PKH_ROW(0), P2PKH_ROW(-1) };
    size_t idx[] = { 2, 1, 0 }, off = 9;
    ck(admit(1, in, 3, &off) == BR_RECOVERY_SWEEP_REFUSE_NON_POSITIVE && off == 1, "the first non-positive row is index 1 (got %zu)", off);
    ck(BRRecoverySweepAdmit(1, in, 3, idx, 3, &off) == BR_RECOVERY_SWEEP_REFUSE_NON_POSITIVE && off == 2,
       "in the order given (2, 1, 0) the first is index 2 (got %zu)", off);
}

static void sweep_zero_after_select(void)
{
    // sweepFromSeed_zeroAmountUtxo_refusesWithoutSigning: the legacy profile keeps the zero row, and
    // admission refuses it.
    BRRecoveryUtxo in[] = { P2PKH_ROW(0) };
    size_t kept[1], k = select_(BR_RECOVERY_FORMAT_P2PKH, in, 1, kept), off = 9;
    ck(k == 1, "the legacy profile keeps the zero row");
    ck(BRRecoverySweepAdmit(1, in, 1, kept, k, &off) == BR_RECOVERY_SWEEP_REFUSE_NON_POSITIVE && off == 0,
       "and its sweep is refused");
}

static void end_to_end_observed(void)
{
    BRRecoveryUtxo in[] = { CONFIRMED, UNCONF, OTHER };
    size_t kept[3], k = select_(BR_RECOVERY_FORMAT_P2PKH, in, 3, kept), off = 9;
    ck(k == 2 && kept[0] == 0 && kept[1] == 2, "the observed answer selects rows 0 and 2");
    ck(total(in, 3, kept, k) == 500006000, "quotes 500006000");
    ck(BRRecoverySweepAdmit(1, in, 3, kept, k, &off) == BR_RECOVERY_SWEEP_ADMIT, "and is admitted");
}

// ---- the API's own contract --------------------------------------------------------------------

static void buffer_too_small(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1, 1), row("bb", 0, 1, 1), row("aa", 0, 1, 1) };
    size_t kept[6] = { 0 }, k = 99;
    ck(BRRecoveryUtxoWorkCapacity(3) == 6, "three rows need room for 6 indices");
    ck(BRRecoveryUtxoWorkCapacity(SIZE_MAX / 2 + 1) == 0, "a count whose working space overflows needs an impossible 0");
    ck(BRRecoveryUtxoDedup(in, 3, kept, 5, &k) == BR_RECOVERY_UTXO_BUFFER_TOO_SMALL && k == 0, "room for 5 of the 6 is too small");
    ck(BRRecoveryUtxoDedup(in, 3, kept, 6, &k) == BR_RECOVERY_UTXO_OK && k == 2 && kept[0] == 0 && kept[1] == 1,
       "room for 6 is enough: two outpoints, rows 0 and 1");
    k = 99;
    ck(BRRecoveryUtxoSelect(BR_RECOVERY_FORMAT_P2TR, in, 3, kept, 5, &k) == BR_RECOVERY_UTXO_BUFFER_TOO_SMALL && k == 0,
       "select reports it too, with a count of 0");
    ck(BRRecoveryUtxoSelect(BR_RECOVERY_FORMAT_P2TR, in, 3, kept, 6, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT,
       "select: no count pointer");
}

static void null_arguments(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1, 1) };
    size_t kept[2], k = 99;
    int64_t t = 5;
    ck(BRRecoveryUtxoDedup(in, 1, kept, 2, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "no count pointer");
    ck(BRRecoveryUtxoDedup(NULL, 1, kept, 2, &k) == BR_RECOVERY_UTXO_NULL_ARGUMENT && k == 0, "no rows with a count");
    ck(BRRecoveryUtxoDedup(in, 1, NULL, 2, &k) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "no buffer with a count");
    ck(BRRecoveryUtxoTotal(NULL, 0, NULL, 1, &t) == BR_RECOVERY_UTXO_NULL_ARGUMENT && t == 0, "total: no rows");
    ck(BRRecoveryUtxoTotal(in, 1, NULL, 1, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "total: no out pointer");
    ck(BRRecoverySweepAdmit(1, NULL, 0, NULL, 1, NULL) == BR_RECOVERY_SWEEP_REFUSE_BAD_ARGUMENT, "admit: no rows with a count");
    ck(BRRecoverySweepAdmit(1, NULL, 0, NULL, 0, NULL) == BR_RECOVERY_SWEEP_ADMIT, "admit: nothing to refuse");
}

static void total_overflow(void)
{
    BRRecoveryUtxo up[] = { row("aa", 0, INT64_MAX, 1), row("bb", 0, 1, 1) };
    BRRecoveryUtxo down[] = { row("aa", 0, INT64_MIN, 1), row("bb", 0, -1, 1) };
    BRRecoveryUtxo edge[] = { row("aa", 0, INT64_MAX, 1), row("bb", 0, INT64_MIN, 1) };
    int64_t t = 5;
    ck(BRRecoveryUtxoTotal(up, 2, NULL, 2, &t) == BR_RECOVERY_UTXO_OVERFLOW && t == 0, "INT64_MAX + 1 is reported, not wrapped");
    ck(BRRecoveryUtxoTotal(down, 2, NULL, 2, &t) == BR_RECOVERY_UTXO_OVERFLOW && t == 0, "INT64_MIN - 1 is reported");
    ck(BRRecoveryUtxoTotal(edge, 2, NULL, 2, &t) == BR_RECOVERY_UTXO_OK && t == -1, "INT64_MAX + INT64_MIN = -1");
}

static void bad_index(void)
{
    BRRecoveryUtxo in[] = { row("aa", 0, 1, 1) };
    size_t idx[] = { 1 }, off = 9;
    int64_t t = 5;
    ck(BRRecoveryUtxoTotal(in, 1, idx, 1, &t) == BR_RECOVERY_UTXO_BAD_INDEX && t == 0, "total: index 1 of 1 row");
    ck(BRRecoverySweepAdmit(1, in, 1, idx, 1, &off) == BR_RECOVERY_SWEEP_REFUSE_BAD_ARGUMENT, "admit: index 1 of 1 row");
}


// ---- at the size the server can send -----------------------------------------------------------
// The server decides the row count, so the rule must not be quadratic. Expectations are built from the
// construction, not from the header. The bound is generous for ASan + UBSan on a slow runner (about 0.35 s
// on an M-series Mac); the earlier quadratic fold took 33 s here.
#include <time.h>
#define BIG_BOUND_SECONDS 5.0

static BRRecoveryUtxo bigRow(size_t g, int64_t height)
{
    BRRecoveryUtxo u;
    size_t t = g / 3;   // three outpoints per txid: vout 0, 1, 2
    memset(&u, 0, sizeof(u));
    u.txid[0] = (uint8_t)(t >> 16); u.txid[1] = (uint8_t)(t >> 8); u.txid[31] = (uint8_t)t;
    u.txid[2] = 0x5a;
    u.vout = (uint32_t)(g % 3);
    u.amount = (int64_t)g + 1;
    u.blockHeight = height;
    return u;
}

// 200,000 rows: 20,000 outpoints seen 10 times each, row i naming outpoint i % 20000 for its (i / 20000)th
// time. Outpoint g's confirmed copy is its (g % 11)th sighting; g % 11 == 10 has none. So the answer
// lists outpoints 0..19999 in order (rows 0..19999 are their first sightings), and outpoint g's kept row
// is (g % 11) * 20000 + g, or g when it has no confirmed copy.
static void many_rows_many_duplicates(void)
{
    const size_t G = 20000, D = 10, n = G * D;
    BRRecoveryUtxo *in = malloc(n * sizeof(*in));
    size_t cap = BRRecoveryUtxoWorkCapacity(n), *kept = malloc(cap * sizeof(size_t)), k = 0, i, bad = 0;
    clock_t start;
    double secs;
    BRRecoveryUtxoStatus s;

    for (i = 0; i < n; i++) {
        size_t g = i % G, o = i / G;
        in[i] = bigRow(g, (o == g % (D + 1)) ? (int64_t)(1000 + o) : 0);
    }
    start = clock();
    s = BRRecoveryUtxoDedup(in, n, kept, cap, &k);
    secs = (double)(clock() - start) / CLOCKS_PER_SEC;
    ck(s == BR_RECOVERY_UTXO_OK && k == G, "200000 rows, 20000 outpoints kept (got %zu)", k);
    for (i = 0; i < k && i < G; i++) {
        size_t c = i % (D + 1), want = (c < D) ? c * G + i : i;
        if (kept[i] != want && bad++ < 3) ck(0, "outpoint %zu: kept row %zu, want %zu", i, kept[i], want);
    }
    ck(bad == 0, "every outpoint in first-seen order, as its confirmed copy (%zu wrong)", bad);
    ck(secs < BIG_BOUND_SECONDS, "in %.3f s (bound %.1f s)", secs, BIG_BOUND_SECONDS);
    free(kept);
    free(in);
}

// One outpoint seen 2,500 times among 2,500 other coins (rows 1, 3, 5, ...), confirmed only at its
// 1,001st sighting (row 2001); the unconfirmed ones on either side change nothing. It stands second, as
// row 2001; every other coin keeps its own row, in order.
static void confirmed_after_many_unconfirmed(void)
{
    const size_t n = 5000, A = 1;   // outpoint A's index into bigRow; the others use 2 + i
    BRRecoveryUtxo *in = malloc(n * sizeof(*in));
    size_t cap = BRRecoveryUtxoWorkCapacity(n), *kept = malloc(cap * sizeof(size_t)), k = 0, i, bad = 0;
    BRRecoveryUtxoStatus s;

    for (i = 0; i < n; i++) in[i] = (i % 2) ? bigRow(A, i == 2001 ? 24106618 : 0) : bigRow(2 + i, 100);
    s = BRRecoveryUtxoDedup(in, n, kept, cap, &k);
    ck(s == BR_RECOVERY_UTXO_OK && k == 2501, "2501 outpoints (got %zu)", k);
    ck(k > 1 && kept[0] == 0 && kept[1] == 2001, "the repeated outpoint stands second, as row 2001 (got %zu)", k > 1 ? kept[1] : 0);
    for (i = 2; i < k; i++) if (kept[i] != 2 * (i - 1)) bad++;
    ck(bad == 0, "the other coins keep their rows in order (%zu wrong)", bad);
    free(kept);
    free(in);
}

static const struct { const char *name; void (*run)(void); } kCases[] = {
    { "same_outpoint_twice_collapses", same_outpoint_twice_collapses },
    { "total_stops_double_counting", total_stops_double_counting },
    { "confirmed_copy_wins", confirmed_copy_wins },
    { "different_vouts_not_duplicates", different_vouts_not_duplicates },
    { "order_preserved", order_preserved },
    { "empty_list_stays_empty", empty_list_stays_empty },
    { "clean_list_unchanged", clean_list_unchanged },
    { "confirmed_copies_keep_first", confirmed_copies_keep_first },
    { "unconfirmed_copies_keep_first", unconfirmed_copies_keep_first },
    { "negative_height_is_unconfirmed", negative_height_is_unconfirmed },
    { "confirmed_later_takes_first_place", confirmed_later_takes_first_place },
    { "txid_last_byte_differs", txid_last_byte_differs },
    { "taproot_keeps_dgb_drops_dollar_outputs", taproot_keeps_dgb_drops_dollar_outputs },
    { "taproot_negative_dropped", taproot_negative_dropped },
    { "non_taproot_zero_kept", non_taproot_zero_kept },
    { "unknown_format_keeps_zero", unknown_format_keeps_zero },
    { "dedup_before_filter", dedup_before_filter },
    { "gate_positive_reachable_admits", gate_positive_reachable_admits },
    { "gate_zero_refuses", gate_zero_refuses },
    { "gate_negative_refuses", gate_negative_refuses },
    { "gate_unreachable_first", gate_unreachable_first },
    { "gate_reports_first_offending", gate_reports_first_offending },
    { "sweep_zero_after_select", sweep_zero_after_select },
    { "end_to_end_observed", end_to_end_observed },
    { "buffer_too_small", buffer_too_small },
    { "null_arguments", null_arguments },
    { "total_overflow", total_overflow },
    { "bad_index", bad_index },
    { "many_rows_many_duplicates", many_rows_many_duplicates },
    { "confirmed_after_many_unconfirmed", confirmed_after_many_unconfirmed },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]), i;
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", RECOVERY_UTXO_UNFIXED ? "comparison (RECOVERY_UTXO_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
