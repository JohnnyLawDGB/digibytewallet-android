// Host KAT: the activity list getTransactionRows hands the app (bridge/tx_rows_list.h).
//
// THE CONTRACT. tx_rows_list(wallet, now, limit) is the wallet's newest `limit` rows as BRWalletTxRows
// computes and orders them, one "txid|amount|fee|blockHeight|shownTimestamp|sent|received|rule" line
// each, in that order: the bridge prints the core's figures and computes none (a send is its payee
// amount, an unknown fee is 18446744073709551615, a pending row shows `now`). The buffer is sized from
// the longest line, so the widest values fit; nothing leaks (run.sh's leak check). The app parses
// these lines (core/TransactionRows.kt; TransactionRowsVectorsTest checks it reads this format).
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>

#include "tx_rows_list.h"
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

#define NOW 1762560000u
#define PAYEE 150000000ULL

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A transaction paying `sats` to `addr` from a foreign coin (tag names it), at height/timestamp.
static BRTransaction *coinTx(const char *addr, uint64_t sats, uint8_t tag, uint32_t height, uint32_t ts)
{
    uint8_t spk[64], foreign[22] = { 0x00, 0x14 };
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *c = BRTransactionNew();
    UInt256 h;
    memset(h.u8, tag, sizeof(h.u8));
    memset(foreign + 2, 0x33, 20);
    c->version = 1;
    BRTransactionAddInput(c, h, 0, 0, foreign, sizeof(foreign), kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(c, sats, spk, spkLen);
    c->blockHeight = height;
    c->timestamp = ts;
    finalizeTxHash(c);
    return c;
}

static void hexOf(UInt256 h, char out[65])
{
    for (int j = 0; j < 32; j++) snprintf(&out[j * 2], 3, "%02x", h.u8[31 - j]);
    out[64] = '\0';
}

// The fixture: A, a receive (h 100, ts 5000, 5 DGB); B, a receive (h 200, ts 3000, 2 DGB); D, a send
// of PAYEE to an outside address just broadcast (unconfirmed, no timestamp). Both receives spend a
// foreign coin, so their fee is unknown. The core's order: D (shows NOW), A (5000), B (3000).
typedef struct { BRWallet *w; UInt256 a, b, d; uint64_t dFee; } Fixture;

static int fixture(Fixture *f)
{
    char dest[75];
    uint8_t spk[22] = { 0x00, 0x14 };
    memset(f, 0, sizeof(*f));
    f->w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(f->w, 1);
    BRTransaction *ta = coinTx(a.s, 500000000, 0xA1, 100, 5000);
    BRTransaction *tb = coinTx(a.s, 200000000, 0xB2, 200, 3000);
    f->a = ta->txHash; f->b = tb->txHash;
    int ok = BRWalletRegisterTransaction(f->w, ta) & BRWalletRegisterTransaction(f->w, tb);
    memset(spk + 2, 0x5a, 20);
    BRAddressFromScriptPubKey(dest, sizeof(dest), spk, sizeof(spk));
    BRTransaction *td = BRWalletCreateTransactionAtFeePerKb(f->w, PAYEE, dest, DEFAULT_FEE_PER_KB);
    if (!td || !BRWalletSignTransaction(f->w, td, 0, g_seed, sizeof(g_seed))) { if (td) BRTransactionFree(td); return 0; }
    td->timestamp = 0;
    td->blockHeight = TX_UNCONFIRMED;
    f->d = td->txHash;
    f->dFee = BRWalletFeeForTx(f->w, td);
    ok &= BRWalletRegisterTransaction(f->w, td);
    return ok && BRWalletTxRowCount(f->w) == 3;
}

static size_t lineCount(const char *s)
{
    size_t n = 0;
    for (; *s; s++) if (*s == '\n') n++;
    return n;
}

// ---------------------------------------------------------------- cases ----

// Every line is the core's row, printed, in the core's order.
static void lines_are_the_cores_rows(void)
{
    Fixture f;
    BRTxRow rows[8];
    char want[3 * TX_ROWS_LINE_MAX + 1], h[65];
    size_t pos = 0;

    ck(fixture(&f), "the fixture wallet holds three transactions");
    size_t n = BRWalletTxRows(f.w, NOW, rows, 8);
    ck(n == 3, "BRWalletTxRows gives three rows (%zu)", n);
    for (size_t i = 0; i < n; i++) {
        hexOf(rows[i].txHash, h);
        pos += (size_t)snprintf(want + pos, sizeof(want) - pos, "%s|%" PRId64 "|%" PRIu64 "|%u|%u|%" PRIu64 "|%" PRIu64 "|%u\n",
                                h, rows[i].amount, rows[i].fee, rows[i].blockHeight, rows[i].shownTimestamp,
                                rows[i].sent, rows[i].received, (unsigned)rows[i].rule);
    }
    char *got = tx_rows_list(f.w, NOW, 100);
    ck(got != NULL && strcmp(got, want) == 0, "the list is the core's rows, line for line:\n%s---- want\n%s", got ? got : "(null)", want);

    // And by hand: D first, a send of PAYEE with its fee apart and NOW as its date; A's fee unknown.
    char d[65], a[65], line[TX_ROWS_LINE_MAX + 1];
    hexOf(f.d, d); hexOf(f.a, a);
    snprintf(line, sizeof(line), "%s|-%llu|%" PRIu64 "|2147483647|%u|", d, PAYEE, f.dFee, NOW);
    ck(got && strncmp(got, line, strlen(line)) == 0, "the first line is the send: payee amount, its fee, pending, NOW (%s)", line);
    ck(f.dFee > 0 && f.dFee != UINT64_MAX, "the send's fee is known (%" PRIu64 ")", f.dFee);
    ck(got && strstr(got, d) && got[strlen(got) - 1] == '\n' && got[strlen(got) - 2] == '0', "the receive's rule is Net");
    snprintf(line, sizeof(line), "\n%s|500000000|18446744073709551615|100|5000|0|500000000|0\n", a);
    ck(got && strstr(got, line) != NULL, "the second line is A, its fee unknown, never 0 (%s)", line + 1);
    free(got);
    BRWalletFree(f.w);
}

// A limit lists the newest rows, in the core's order.
static void limit_lists_the_newest(void)
{
    Fixture f;
    ck(fixture(&f), "the fixture wallet holds three transactions");
    char *all = tx_rows_list(f.w, NOW, 100);
    char *two = tx_rows_list(f.w, NOW, 2);
    char *one = tx_rows_list(f.w, NOW, 1);
    ck(all && lineCount(all) == 3, "limit 100: three lines");
    ck(two && lineCount(two) == 2 && strncmp(all, two, strlen(two)) == 0, "limit 2: the first two of the list");
    ck(one && lineCount(one) == 1 && strncmp(all, one, strlen(one)) == 0, "limit 1: the first of the list");
    char d[65];
    hexOf(f.d, d);
    ck(one && strncmp(one, d, 64) == 0, "the newest is the pending send");
    free(all); free(two); free(one);
    BRWalletFree(f.w);
}

// Nothing to list: an empty string, never NULL and never a leak.
static void empty_lists(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    char *s = tx_rows_list(w, NOW, 100);
    ck(s && s[0] == '\0', "an empty wallet: \"\"");
    free(s);
    s = tx_rows_list(NULL, NOW, 100);
    ck(s && s[0] == '\0', "no wallet: \"\"");
    free(s);
    BRWalletFree(w);

    Fixture f;
    ck(fixture(&f), "the fixture wallet holds three transactions");
    s = tx_rows_list(f.w, NOW, 0);
    ck(s && s[0] == '\0', "limit 0: \"\"");
    free(s);
    size_t count = 99;
    BRTxRow *rows = tx_rows_list_read(f.w, NOW, 0, &count);
    ck(rows == NULL && count == 0, "read at limit 0: nothing");
    BRWalletFree(f.w);
}

// The widest values of every field fit the buffer, exactly at TX_ROWS_LINE_MAX each.
static void widest_lines_fit(void)
{
    BRTxRow rows[3];
    memset(rows, 0xff, sizeof(rows));
    for (int i = 0; i < 3; i++) {
        rows[i].amount = INT64_MIN;
        rows[i].rule = 255;
    }
    char *s = tx_rows_list_format(rows, 3);
    ck(s != NULL, "formatted");
    ck(s && strlen(s) == 3 * TX_ROWS_LINE_MAX, "three widest lines are 3 x %d bytes (%zu)", TX_ROWS_LINE_MAX, s ? strlen(s) : 0);
    ck(s && lineCount(s) == 3, "every line written");
    ck(s && strstr(s, "|-9223372036854775808|18446744073709551615|4294967295|4294967295|18446744073709551615|18446744073709551615|255\n"),
       "the extremes print whole");
    free(s);
    s = tx_rows_list_format(NULL, 0);
    ck(s && s[0] == '\0', "no rows: \"\"");
    free(s);
}

static const struct { const char *name; void (*run)(void); } kCases[] = {
    { "lines_are_the_cores_rows", lines_are_the_cores_rows },
    { "limit_lists_the_newest", limit_lists_the_newest },
    { "empty_lists", empty_lists },
    { "widest_lines_fit", widest_lines_fit },
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
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
