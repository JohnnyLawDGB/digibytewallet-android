// Host KAT: the order and the date of a transaction-list row (BRTxRows.h, the ORDER and DATE rules).
//
// THE RULE (owner decision D3 and the order, 2026-11-08):
//   - DATE: a row shows its transaction's timestamp, or the caller's `now` while it has none (0 until
//     its block is seen). The header never reads a clock: run.sh's source gate holds it to that;
//   - ORDER: shownTimestamp descending, then blockHeight descending (an unconfirmed row's
//     TX_UNCONFIRMED is the highest), then walletIndex ascending. A total order: only a row compares
//     equal to itself, so every platform's sort -- stable or not -- lists the same rows the same way,
//     and platforms never re-sort.
// Android sorted by date then height (WalletViewModel, a stable sort over the wallet's oldest-first
// list); iOS sorted unconfirmed first, then by height, then by date, and showed a pending row with no
// date. The comparison arm (-DTX_ROWS_UNFIXED=1) is iOS's: a restored wallet's years-old transaction,
// unconfirmed again until its block is re-seen, floats above all confirmed history, and two blocks whose
// timestamps disagree with their heights (DigiByte's five algorithms make that common) list by height.
//
// Every expectation is written here by hand, as the walletIndex list a person reading the case would
// expect; none is derived from the comparator. The cases are the same corpus vectors/tx-rows/ writes
// down, so a platform binding test holding itself to vectors.txt holds itself to these.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>

#include "BRTxRows.h"

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

#define U 2147483647u   // TX_UNCONFIRMED, written as the number (BRTransaction.h: INT32_MAX)
#define NOW 1762560000u

typedef struct { uint32_t ts, height, index; } R;   // timestamp (raw), blockHeight, walletIndex

// Sorts rows built from in[] with `now` and checks the walletIndex order is want[].
static void expectOrder(const char *what, const R *in, size_t n, uint32_t now, const uint32_t *want)
{
    BRTxRow rows[16];
    char got[128] = "", exp[128] = "";
    int same = 1;

    memset(rows, 0, sizeof(rows));
    for (size_t i = 0; i < n; i++) {
        rows[i].timestamp = in[i].ts;
        rows[i].shownTimestamp = BRTxRowShownTimestamp(in[i].ts, now);
        rows[i].blockHeight = in[i].height;
        rows[i].walletIndex = in[i].index;
    }
    qsort(rows, n, sizeof(rows[0]), _BRTxRowQsortCompare);
    for (size_t i = 0; i < n; i++) {
        snprintf(got + strlen(got), sizeof(got) - strlen(got), " %" PRIu32, rows[i].walletIndex);
        snprintf(exp + strlen(exp), sizeof(exp) - strlen(exp), " %" PRIu32, want[i]);
        if (rows[i].walletIndex != want[i]) same = 0;
    }
    ck(same, "%s: listed%s, want%s", what, got, exp);
}

static void date_desc_newest_first(void)
{
    const R in[] = { {100, 10, 0}, {300, 30, 1}, {200, 20, 2} };
    const uint32_t want[] = { 1, 2, 0 };
    expectOrder("newest date first", in, 3, NOW, want);
}

static void date_over_height(void)
{
    // Block 30 carries an earlier timestamp than block 10: the date decides.
    const R in[] = { {300, 10, 0}, {100, 30, 1} };
    const uint32_t want[] = { 0, 1 };
    expectOrder("the later date lists first even at the lower height", in, 2, NOW, want);
}

static void height_breaks_date_tie(void)
{
    const R in[] = { {100, 10, 0}, {100, 30, 1}, {100, 20, 2} };
    const uint32_t want[] = { 1, 2, 0 };
    expectOrder("same date: higher block first", in, 3, NOW, want);
}

static void index_breaks_full_tie(void)
{
    const R in[] = { {100, 10, 2}, {100, 10, 0}, {100, 10, 1} };
    const uint32_t want[] = { 0, 1, 2 };
    expectOrder("same date and height: the wallet's own order (oldest first)", in, 3, NOW, want);
}

static void pending_now_on_top(void)
{
    // A send just broadcast has no timestamp yet: it shows `now`, newer than every confirmed row.
    const R in[] = { {1700000000, 23000000, 0}, {0, U, 1}, {1700000100, 23000005, 2} };
    const uint32_t want[] = { 1, 2, 0 };
    expectOrder("a fresh pending send lists on top", in, 3, NOW, want);
}

static void shown_timestamp_pending_is_now(void)
{
    ck(BRTxRowShownTimestamp(0, NOW) == NOW, "a row with no timestamp shows now (%u), got %u", NOW, BRTxRowShownTimestamp(0, NOW));
    ck(BRTxRowShownTimestamp(0, 1) == 1, "whatever now the caller passes (1)");
}

static void shown_timestamp_own(void)
{
    ck(BRTxRowShownTimestamp(1700000000, NOW) == 1700000000, "a row with a timestamp shows its own");
    ck(BRTxRowShownTimestamp(1, 0) == 1 && BRTxRowShownTimestamp(UINT32_MAX, 5) == UINT32_MAX, "at the edges too");
    ck(BRTxRowShownTimestamp(0, 0) == 0, "no timestamp and a now of 0: 0");
}

static void old_pending_not_on_top(void)
{
    // A restored wallet loads a 2017 transaction unconfirmed until its block is seen again: it keeps
    // its own date and stays below newer confirmed history.
    const R in[] = { {1500000000, U, 0}, {1700000000, 23000000, 1} };
    const uint32_t want[] = { 1, 0 };
    expectOrder("an old unconfirmed row stays at its date", in, 2, NOW, want);
}

static void pending_tie_above_confirmed(void)
{
    const R in[] = { {NOW, 23000000, 0}, {NOW, U, 1} };
    const uint32_t want[] = { 1, 0 };
    expectOrder("same date: the unconfirmed row (highest height) first", in, 2, NOW, want);
}

static void pending_tie_by_index(void)
{
    const R in[] = { {0, U, 1}, {0, U, 0} };
    const uint32_t want[] = { 0, 1 };
    expectOrder("two fresh pending rows: the wallet's own order", in, 2, NOW, want);
}

static void edges(void)
{
    const R in[] = { {0, 0, 0}, {UINT32_MAX, 0, 1}, {0, UINT32_MAX, 2}, {UINT32_MAX, UINT32_MAX, 3}, {0, U, 4} };
    const uint32_t want[] = { 3, 1, 2, 4, 0 };
    expectOrder("extreme values, now 0", in, 5, 0, want);
}

// The comparator is a strict total order over rows with distinct walletIndex: antisymmetric,
// transitive, and 0 only for the same index. Checked exhaustively over a small grid.
static void total_order(void)
{
    const uint32_t ts[] = { 0, 5, 9 }, hs[] = { 1, 7, U };
    BRTxRow g[27];
    size_t n = 0, bad = 0;
    memset(g, 0, sizeof(g));
    for (size_t i = 0; i < 3; i++) for (size_t j = 0; j < 3; j++) for (size_t k = 0; k < 3; k++) {
        g[n].timestamp = ts[i]; g[n].shownTimestamp = BRTxRowShownTimestamp(ts[i], 9);
        g[n].blockHeight = hs[j]; g[n].walletIndex = (uint32_t)(k * 9 + i * 3 + j); n++;
    }
    for (size_t a = 0; a < n; a++) for (size_t b = 0; b < n; b++) {
        int ab = BRTxRowCompare(&g[a], &g[b]), ba = BRTxRowCompare(&g[b], &g[a]);
        if ((a == b) != (ab == 0)) bad++;
        if ((ab < 0) != (ba > 0)) bad++;
        for (size_t c = 0; c < n; c++)
            if (ab < 0 && BRTxRowCompare(&g[b], &g[c]) < 0 && BRTxRowCompare(&g[a], &g[c]) >= 0) bad++;
    }
    ck(bad == 0, "a strict total order over %zu rows (%zu violations)", n, bad);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "date_desc_newest_first", date_desc_newest_first },
    { "date_over_height", date_over_height },
    { "height_breaks_date_tie", height_breaks_date_tie },
    { "index_breaks_full_tie", index_breaks_full_tie },
    { "pending_now_on_top", pending_now_on_top },
    { "shown_timestamp_pending_is_now", shown_timestamp_pending_is_now },
    { "shown_timestamp_own", shown_timestamp_own },
    { "old_pending_not_on_top", old_pending_not_on_top },
    { "pending_tie_above_confirmed", pending_tie_above_confirmed },
    { "pending_tie_by_index", pending_tie_by_index },
    { "edges", edges },
    { "total_order", total_order },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", TX_ROWS_UNFIXED ? "comparison (TX_ROWS_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
