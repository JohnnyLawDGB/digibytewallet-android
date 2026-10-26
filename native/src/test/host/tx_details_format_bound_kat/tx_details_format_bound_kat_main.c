// Host KAT: the transaction-details list is sized from the longest line its format can
// produce, and its write position never passes the buffer, whatever the values.
//
// THE CODE UNDER TEST is tx_details_format.h, the JNI-free core of getTransactionDetails
// (jni_wallet.c), included here as the bridge compiles it. jni_wallet.c reads each row from
// the wallet; here a row function reads it from a table.
//
// THE LINE is "<txid>|<amount>|<fee>|<blockHeight>|<timestamp>|<sent>|<received>\n". Its
// longest form is 171 characters: 64 for the txid, 20 for amount at INT64_MIN, 20 for each of
// fee, sent and received at UINT64_MAX, 10 for each of blockHeight and timestamp at
// UINT32_MAX, six separators and the newline. fee is at its widest for every receive
// (UINT64_MAX: an input the wallet does not hold).
//
// THE INVARIANT. The buffer holds TX_DETAILS_LINE_MAX for each line the list can carry (the
// 100 most recent transactions at most) and the NUL, and a line that does not fit is not
// written: the list ends at its last complete line and the write position stays inside the
// buffer.
//
// MACRO CONVENTION (presence): the comparison arm is -DTX_DETAILS_FORMAT_UNFIXED, which keeps
// the earlier sizing (160 per transaction, for every transaction) and the earlier advance (by
// snprintf's return value, unclamped). The shipped arm defines nothing.
//
// THE CASES (run.sh holds the comparison arm's expectation for each):
//   line_max         the widest value of each field prints at the width the header counts,
//                    and the line of widest values is exactly TX_DETAILS_LINE_MAX long
//   buffer_size      the size given for 0, 1, 15, 16, 99, 100, 101, 150 and SIZE_MAX txs
//   max_lines_15     15 lines of widest values, all complete. The earlier sizing cuts the
//                    15th short, inside its buffer
//   max_lines_16     16 lines of widest values: the fewest at which the earlier loop's position
//                    passes the end of its buffer with a line still to write
//   max_lines_99     99 lines of widest values
//   most_recent_101  101 transactions of widest values: the 100 most recent, complete, in order
//   most_recent_150  150 transactions of widest values: the same
//   append_stop      one line into a buffer one and a half lines long, then two more: neither
//                    is written and the position does not move. A buffer of one line and its
//                    NUL takes the line; a buffer one byte shorter takes nothing; a position
//                    already at the end of the buffer writes nothing
//   typical          100 transactions of everyday values, every fifth left out by the row function
//
// Usage: tx_details_format_bound_kat <case> | list
// Prints "RESULT <case> pass" or "RESULT <case> fail".
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "tx_details_format.h"

static int g_fail = 0;
static void check(int c, const char *d)
{
    printf(c ? "PASS: %s\n" : "FAIL: %s\n", d);
    fflush(stdout);   // a sanitizer abort must not take the lines before it with it
    if (!c) g_fail++;
}

// ============================ rows ============================

// The widest value of every field. The txid's first two bytes carry i, so order is visible.
static TxDetailsRow widest_row(size_t i)
{
    TxDetailsRow r;
    for (int j = 0; j < 32; j++) r.txHash[j] = (uint8_t)(0xa0 + j);
    r.txHash[0] = (uint8_t)i;
    r.txHash[1] = (uint8_t)(i >> 8);
    r.amount = INT64_MIN;
    r.fee = UINT64_MAX;
    r.blockHeight = UINT32_MAX;
    r.timestamp = UINT32_MAX;
    r.sent = UINT64_MAX;
    r.received = UINT64_MAX;
    return r;
}

// Everyday values: receives (fee unknown, nothing sent), sends with change and a known fee,
// self-sends, confirmed at heights of today's chain, the newest three still unconfirmed.
static TxDetailsRow typical_row(size_t i)
{
    TxDetailsRow r;
    for (int j = 0; j < 32; j++) r.txHash[j] = (uint8_t)(i * 37 + (size_t)j * 11);
    r.timestamp = 1760000000u + (uint32_t)i * 600u;
    r.blockHeight = (i >= 97) ? 2147483647u /* TX_UNCONFIRMED */ : 21000000u + (uint32_t)i * 40u;
    switch (i % 3) {
    case 0:  r.received = 1000000000ull * (i + 1); r.sent = 0; r.fee = UINT64_MAX; break;
    case 1:  r.sent = 5000000000ull + i; r.received = 2000000000ull; r.fee = 22600; break;
    default: r.sent = 300000000ull; r.received = 300000000ull - 22600; r.fee = 22600; break;
    }
    r.amount = (int64_t)r.received - (int64_t)r.sent;   // as jni_wallet.c computes it
    return r;
}

typedef struct {
    const TxDetailsRow *rows;
    size_t count;
    size_t leaveOutEvery;   // 0: none; else the row function leaves out i % n == n - 1
    size_t asked, firstAsked, askedPastEnd;
} RowTable;

static int left_out(const RowTable *t, size_t i)
{
    return t->leaveOutEvery && (i % t->leaveOutEvery) == t->leaveOutEvery - 1;
}

static int row_from_table(void *ctx, size_t i, TxDetailsRow *row)
{
    RowTable *t = (RowTable *)ctx;
    if (t->asked++ == 0) t->firstAsked = i;
    if (i >= t->count) { t->askedPastEnd++; return 0; }
    if (left_out(t, i)) return 0;
    *row = t->rows[i];
    return 1;
}

// The line a row must produce, written here independently of the header (its own hex, its
// own copy of the row format the app's parsers read).
static size_t expected_line(char *out, size_t cap, const TxDetailsRow *r)
{
    static const char digits[] = "0123456789abcdef";
    char hex[65];
    for (int j = 0; j < 32; j++) {
        hex[2 * j] = digits[r->txHash[31 - j] >> 4];
        hex[2 * j + 1] = digits[r->txHash[31 - j] & 15];
    }
    hex[64] = '\0';
    int n = snprintf(out, cap, "%s|%lld|%llu|%u|%u|%llu|%llu\n", hex, (long long)r->amount,
                     (unsigned long long)r->fee, (unsigned)r->blockHeight, (unsigned)r->timestamp,
                     (unsigned long long)r->sent, (unsigned long long)r->received);
    return (n > 0 && (size_t)n < cap) ? (size_t)n : 0;
}

// Builds the list over t and requires exactly the expected text: one complete line for each
// of the 100 most recent rows that are not left out, oldest first.
static void check_list(RowTable *t)
{
    size_t first = t->count > 100 ? t->count - 100 : 0;
    t->asked = t->firstAsked = t->askedPastEnd = 0;

    char *out = tx_details_format(t->count, row_from_table, t);
    check(out != NULL, "the list was built");
    if (!out) return;

    size_t cap = 100 * 256 + 1, want = 0, lines = 0;
    char *exp = (char *)malloc(cap);
    if (!exp) { check(0, "allocation for the expected text"); free(out); return; }
    exp[0] = '\0';
    for (size_t i = first; i < t->count; i++) {
        if (left_out(t, i)) continue;
        want += expected_line(exp + want, cap - want, &t->rows[i]);
        lines++;
    }

    size_t got = strlen(out), complete = 0;
    for (size_t k = 0; k < got; k++) complete += (out[k] == '\n');
    printf("NOTE: %zu transactions, %zu lines expected (%zu bytes); got %zu bytes: %zu complete "
           "lines%s\n", t->count, lines, want, got, complete,
           (got && out[got - 1] != '\n') ? " and one cut short" : "");

    char d[200];
    snprintf(d, sizeof d, "all %zu expected lines are present and complete", lines);
    check(complete == lines && got == want, d);
    check(got == want && memcmp(out, exp, want) == 0,
          "each line carries its row's fields: the 100 most recent, oldest first");
    check(got == 0 || out[got - 1] == '\n', "the list ends at the end of a line");
    snprintf(d, sizeof d, "rows asked for: indexes %zu to %zu, none past the end", first, t->count - 1);
    check(t->firstAsked == first && t->asked == t->count - first && t->askedPastEnd == 0, d);
    free(exp);
    free(out);
}

static void run_widest(size_t n)
{
    TxDetailsRow *rows = (TxDetailsRow *)malloc(n * sizeof *rows);
    if (!rows) { check(0, "allocation for the rows"); return; }
    for (size_t i = 0; i < n; i++) rows[i] = widest_row(i);
    RowTable t = { rows, n, 0, 0, 0, 0 };
    check_list(&t);
    free(rows);
}

// ============================ cases ============================

static void case_line_max(void)
{
    char d[200];
    int w = snprintf(NULL, 0, "%lld", (long long)INT64_MIN);
    snprintf(d, sizeof d, "amount at INT64_MIN prints %d characters; the header counts %zu", w,
             (size_t)TX_DETAILS_I64_CHARS);
    check(w == (int)TX_DETAILS_I64_CHARS, d);
    check(snprintf(NULL, 0, "%lld", (long long)INT64_MAX) < w, "amount at INT64_MAX prints fewer");
    w = snprintf(NULL, 0, "%llu", (unsigned long long)UINT64_MAX);
    snprintf(d, sizeof d, "fee, sent, received at UINT64_MAX print %d characters; the header "
             "counts %zu", w, (size_t)TX_DETAILS_U64_CHARS);
    check(w == (int)TX_DETAILS_U64_CHARS, d);
    w = snprintf(NULL, 0, "%u", (unsigned)UINT32_MAX);
    snprintf(d, sizeof d, "blockHeight, timestamp at UINT32_MAX print %d characters; the header "
             "counts %zu", w, (size_t)TX_DETAILS_U32_CHARS);
    check(w == (int)TX_DETAILS_U32_CHARS, d);
    check(TX_DETAILS_LINE_MAX == 171, "TX_DETAILS_LINE_MAX = 64 + 20 + 20 + 10 + 10 + 20 + 20 + 7 = 171");

    TxDetailsRow r = widest_row(0);
    char exp[256], buf[256];
    size_t want = expected_line(exp, sizeof exp, &r), pos = 0;
    buf[0] = '\0';
    int ok = tx_details_append_line(buf, sizeof buf, &pos, &r);
    printf("NOTE: the widest line, %zu characters: %s", strlen(buf), buf);
    check(ok == 1 && pos == want && strcmp(buf, exp) == 0, "the widest row's line is written whole");
    check(want == TX_DETAILS_LINE_MAX, "and is exactly TX_DETAILS_LINE_MAX characters long");
}

static void case_buffer_size(void)
{
    static const struct { size_t txCount, bytes; } kat[] = {
        { 0, 1 }, { 1, 172 }, { 15, 2566 }, { 16, 2737 }, { 99, 16930 },
        { 100, 17101 }, { 101, 17101 }, { 150, 17101 }, { SIZE_MAX, 17101 },
    };
    for (size_t k = 0; k < sizeof kat / sizeof kat[0]; k++) {
        size_t got = tx_details_buffer_size(kat[k].txCount);
        char d[200];
        snprintf(d, sizeof d, "txCount %zu: %zu bytes (%zu lines of 171 and the NUL); given %zu",
                 kat[k].txCount, kat[k].bytes, (kat[k].bytes - 1) / 171, got);
        check(got == kat[k].bytes, d);
    }
}

static void case_max_lines_15(void)    { run_widest(15); }
static void case_max_lines_16(void)    { run_widest(16); }
static void case_max_lines_99(void)    { run_widest(99); }
static void case_most_recent_101(void) { run_widest(101); }
static void case_most_recent_150(void) { run_widest(150); }

static void case_append_stop(void)
{
    TxDetailsRow r = widest_row(7);
    char exp[256];
    size_t want = expected_line(exp, sizeof exp, &r);

    size_t size = want + want / 2, pos = 0;   // one and a half lines
    char *buf = (char *)malloc(size);
    if (!buf) { check(0, "allocation"); return; }
    buf[0] = '\0';
    check(tx_details_append_line(buf, size, &pos, &r) == 1 && pos == want,
          "a buffer one and a half lines long takes the first line");
    int second = tx_details_append_line(buf, size, &pos, &r);
    check(second == 0 && pos == want, "the second does not fit: not written, the position does not move");
    int third = tx_details_append_line(buf, size, &pos, &r);
    check(third == 0 && pos == want, "nor the third");
    check(pos < size && strlen(buf) == want && memcmp(buf, exp, want) == 0,
          "the buffer holds the first line whole and ends after it");
    free(buf);

    size = want + 1; pos = 0;                  // one line and its NUL
    buf = (char *)malloc(size);
    if (!buf) { check(0, "allocation"); return; }
    buf[0] = '\0';
    check(tx_details_append_line(buf, size, &pos, &r) == 1 && pos == want && strcmp(buf, exp) == 0,
          "a buffer of one line and its NUL takes the line");
    check(tx_details_append_line(buf, size, &pos, &r) == 0 && pos == want && strcmp(buf, exp) == 0,
          "and nothing after it");
    free(buf);

    size = want; pos = 0;                      // one byte short of the line and its NUL
    buf = (char *)malloc(size);
    if (!buf) { check(0, "allocation"); return; }
    buf[0] = '\0';
    check(tx_details_append_line(buf, size, &pos, &r) == 0 && pos == 0 && buf[0] == '\0',
          "a buffer one byte short of the line and its NUL takes nothing");
    free(buf);

    size = want + 1; pos = size;               // a position already at the end of the buffer
    buf = (char *)malloc(size);
    if (!buf) { check(0, "allocation"); return; }
    memcpy(buf, exp, want + 1);
    check(tx_details_append_line(buf, size, &pos, &r) == 0 && pos == size && strcmp(buf, exp) == 0,
          "a position at the end of the buffer: nothing is written, the position does not move");
    free(buf);
}

static void case_typical(void)
{
    enum { N = 100 };
    TxDetailsRow rows[N];
    for (size_t i = 0; i < N; i++) rows[i] = typical_row(i);
    RowTable t = { rows, N, 5, 0, 0, 0 };
    check_list(&t);
}

// ============================ driver ============================

static const struct { const char *name; void (*fn)(void); } CASES[] = {
    { "line_max",        case_line_max },
    { "buffer_size",     case_buffer_size },
    { "max_lines_15",    case_max_lines_15 },
    { "max_lines_16",    case_max_lines_16 },
    { "max_lines_99",    case_max_lines_99 },
    { "most_recent_101", case_most_recent_101 },
    { "most_recent_150", case_most_recent_150 },
    { "append_stop",     case_append_stop },
    { "typical",         case_typical },
};

int main(int argc, char **argv)
{
    size_t nCases = sizeof(CASES) / sizeof(CASES[0]);

    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < nCases; i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (size_t i = 0; i < nCases; i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("NOTE: %zu-bit size_t\n", sizeof(size_t) * 8);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail == 0 ? "pass" : "fail");
        return g_fail == 0 ? 0 : 1;
    }
    fprintf(stderr, "unknown case: %s\n", argv[1]);
    return 2;
}
