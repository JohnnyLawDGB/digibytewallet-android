// Host KAT: BRWalletTxRows over real wallets -- the list as a platform reads it (BRTxRows.h).
//
// THE CONTRACT. BRWalletTxRows(wallet, now, rows, maxRows) writes the wallet's rows in the ORDER
// (shownTimestamp desc, blockHeight desc, walletIndex asc), the first maxRows of them, and returns how
// many it wrote; rows NULL returns how many there are and writes nothing; maxRows 0 writes nothing and
// returns 0. It copies the wallet's transactions in one lock hold, keeps no pointer into the wallet
// (the rows outlive the wallet), does no I/O and leaks nothing (run.sh's leak check). A platform
// lists exactly these rows in exactly this order and never re-sorts.
//
// The wallet here holds transactions whose dates disagree with their heights, a send just broadcast
// (no timestamp: it shows `now`) and a years-old transaction unconfirmed again (a restored wallet before
// its block is re-seen). The order each case expects is written out by hand, by txid tag.
//
// MACRO CONVENTION: BRTxRows.h tests the VALUE of TX_ROWS_UNFIXED (#if). run.sh builds the comparison
// arm with -DTX_ROWS_UNFIXED=1 (iOS's order: unconfirmed first, then height, then date; the raw
// timestamp) and the shipped arm with =0.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>

#include "BRTxRows.h"
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
#define U TX_UNCONFIRMED

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

// The fixture: five transactions, tagged by their first txid byte in the order the case expects.
//   A  h 100 ts 5000   (dates disagree with heights: A is the oldest block and the newest date)
//   B  h 200 ts 3000
//   C  h 300 ts 4000
//   D  unconfirmed, ts 0: a send just broadcast -> shows NOW
//   E  unconfirmed, ts 1000: restored history, its block not yet re-seen
// Shipped order: D (NOW), A (5000), C (4000), B (3000), E (1000).
// iOS's order: E, D (unconfirmed, by date), then C, B, A (by height).
typedef struct { BRWallet *w; UInt256 a, b, c, d, e; } Fixture;

static int fixture(Fixture *f)
{
    char dest[75];
    uint8_t spk[22] = { 0x00, 0x14 };
    memset(f, 0, sizeof(*f));
    f->w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(f->w, 1);
    BRTransaction *ta = coinTx(a.s, 500000000, 0xA1, 100, 5000);
    BRTransaction *tb = coinTx(a.s, 200000000, 0xB2, 200, 3000);
    BRTransaction *tc = coinTx(a.s, 300000000, 0xC3, 300, 4000);
    BRTransaction *te = coinTx(a.s, 100000000, 0xE5, U, 1000);
    f->a = ta->txHash; f->b = tb->txHash; f->c = tc->txHash; f->e = te->txHash;
    int ok = BRWalletRegisterTransaction(f->w, ta) & BRWalletRegisterTransaction(f->w, tb) &
             BRWalletRegisterTransaction(f->w, tc) & BRWalletRegisterTransaction(f->w, te);
    memset(spk + 2, 0x5a, 20);
    BRAddressFromScriptPubKey(dest, sizeof(dest), spk, sizeof(spk));
    BRTransaction *td = BRWalletCreateTransactionAtFeePerKb(f->w, 150000000, dest, DEFAULT_FEE_PER_KB);
    if (!td || !BRWalletSignTransaction(f->w, td, 0, g_seed, sizeof(g_seed))) { if (td) BRTransactionFree(td); return 0; }
    td->timestamp = 0;
    td->blockHeight = U;
    f->d = td->txHash;
    ok &= BRWalletRegisterTransaction(f->w, td);
    return ok && BRWalletTxRowCount(f->w) == 5;
}

static char tagOf(const Fixture *f, UInt256 h)
{
    if (UInt256Eq(h, f->a)) return 'A';
    if (UInt256Eq(h, f->b)) return 'B';
    if (UInt256Eq(h, f->c)) return 'C';
    if (UInt256Eq(h, f->d)) return 'D';
    if (UInt256Eq(h, f->e)) return 'E';
    return '?';
}

static void tags(const Fixture *f, const BRTxRow *rows, size_t n, char *out)
{
    for (size_t i = 0; i < n; i++) out[i] = tagOf(f, rows[i].txHash);
    out[n] = '\0';
}

// ---------------------------------------------------------------- cases ----

static void full_list_order(void)
{
    Fixture f;
    BRTxRow rows[5];
    char t[8];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    size_t n = BRWalletTxRows(f.w, NOW, rows, 5);
    tags(&f, rows, n, t);
    ck(n == 5, "five rows written (got %zu)", n);
    ck(strcmp(t, "DACBE") == 0, "listed %s, want DACBE (by date, the fresh send at now on top, old pending last)", t);
    BRWalletFree(f.w);
}

static void max_rows_newest_first(void)
{
    Fixture f;
    BRTxRow rows[5];
    char t[8];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    memset(rows, 0xEE, sizeof(rows));
    size_t n = BRWalletTxRows(f.w, NOW, rows, 2);
    tags(&f, rows, n, t);
    ck(n == 2, "maxRows 2 of 5: two rows written (got %zu)", n);
    ck(strcmp(t, "DA") == 0, "the two newest by the ORDER: listed %s, want DA", t);
    const uint8_t *tail = (const uint8_t *)&rows[2];
    size_t untouched = 1;
    for (size_t i = 0; i < 3 * sizeof(BRTxRow); i++) if (tail[i] != 0xEE) untouched = 0;
    ck(untouched, "nothing is written past maxRows");
    BRWalletFree(f.w);
}

static void max_rows_exceeds_count(void)
{
    Fixture f;
    BRTxRow rows[8];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    memset(rows, 0xEE, sizeof(rows));
    size_t n = BRWalletTxRows(f.w, NOW, rows, 8);
    ck(n == 5, "maxRows 8 of 5: five rows written (got %zu)", n);
    const uint8_t *tail = (const uint8_t *)&rows[5];
    size_t untouched = 1;
    for (size_t i = 0; i < 3 * sizeof(BRTxRow); i++) if (tail[i] != 0xEE) untouched = 0;
    ck(untouched, "the rest of the buffer is untouched");
    BRWalletFree(f.w);
}

static void null_and_zero_buffer(void)
{
    Fixture f;
    BRTxRow one;
    ck(fixture(&f), "the fixture wallet holds five transactions");
    ck(BRWalletTxRows(f.w, NOW, NULL, 0) == 5, "rows NULL: returns the count, 5");
    ck(BRWalletTxRows(f.w, NOW, NULL, 3) == 5, "rows NULL, any maxRows: still the count");
    memset(&one, 0xEE, sizeof(one));
    ck(BRWalletTxRows(f.w, NOW, &one, 0) == 0, "maxRows 0: returns 0");
    const uint8_t *p = (const uint8_t *)&one;
    size_t untouched = 1;
    for (size_t i = 0; i < sizeof(one); i++) if (p[i] != 0xEE) untouched = 0;
    ck(untouched, "and writes nothing");
    ck(BRWalletTxRowCount(f.w) == 5, "BRWalletTxRowCount: 5");
    ck(BRWalletTxRows(NULL, NOW, &one, 1) == 0, "a NULL wallet: 0");
    BRWalletFree(f.w);
}

static void empty_wallet(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRTxRow one;
    ck(BRWalletTxRowCount(w) == 0 && BRWalletTxRows(w, NOW, NULL, 0) == 0, "an empty wallet has no rows");
    ck(BRWalletTxRows(w, NOW, &one, 1) == 0, "and writes none");
    BRWalletFree(w);
}

static void pending_row_shows_now(void)
{
    Fixture f;
    BRTxRow rows[5];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    size_t n = BRWalletTxRows(f.w, NOW, rows, 5);
    int seenD = 0, seenE = 0;
    for (size_t i = 0; i < n; i++) {
        char t = tagOf(&f, rows[i].txHash);
        if (t == 'D') {
            seenD = 1;
            ck(rows[i].timestamp == 0 && rows[i].shownTimestamp == NOW,
               "the fresh send: timestamp 0, shows now %u (got %u)", NOW, rows[i].shownTimestamp);
            ck(rows[i].blockHeight == U, "and is unconfirmed");
        }
        if (t == 'E') {
            seenE = 1;
            ck(rows[i].shownTimestamp == 1000, "the old unconfirmed row keeps its own date, 1000 (got %u)", rows[i].shownTimestamp);
        }
    }
    ck(seenD && seenE, "both pending rows are listed");
    BRWalletFree(f.w);
}

// Each row is what BRWalletTxRowFill gives for that transaction's copy, at its index in the wallet's
// own oldest-first list.
static void rows_are_fill(void)
{
    Fixture f;
    BRTxRow rows[5];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    size_t n = BRWalletTxRows(f.w, NOW, rows, 5);
    BRTransaction *txs[5] = { 0 };
    size_t m = BRWalletCopyTransactions(f.w, txs, 5);
    ck(m == 5, "five copies");
    size_t matched = 0;
    for (size_t i = 0; i < n; i++) {
        uint32_t k = rows[i].walletIndex;
        if (k >= m || !txs[k] || !UInt256Eq(txs[k]->txHash, rows[i].txHash)) continue;
        BRTxRow r;
        BRWalletTxRowFill(f.w, txs[k], k, NOW, &r);
        if (memcmp(&r, &rows[i], sizeof(r)) == 0) matched++;
    }
    ck(matched == 5, "every row equals BRWalletTxRowFill of the transaction at its walletIndex (%zu of 5)", matched);
    for (size_t i = 0; i < m; i++) if (txs[i]) BRTransactionFree(txs[i]);
    BRWalletFree(f.w);
}

// The rows are values: they outlive the wallet that produced them.
static void rows_outlive_wallet(void)
{
    Fixture f;
    BRTxRow rows[5];
    ck(fixture(&f), "the fixture wallet holds five transactions");
    size_t n = BRWalletTxRows(f.w, NOW, rows, 5);
    Fixture keep = f;
    BRWalletFree(f.w);
    char t[8];
    tags(&keep, rows, n, t);   // reads the rows after the wallet is gone (ASan would see a stale pointer)
    int64_t sum = 0;
    for (size_t i = 0; i < n; i++) sum += rows[i].amount;
    ck(n == 5 && t[0] != '?', "five rows still readable after BRWalletFree (%s)", t);
    printf("NOTE: amounts sum to %" PRId64 "\n", sum);
}

// A long history: the first maxRows of a 300-transaction list are exactly the first maxRows of the
// whole list, and the whole list is sorted by the ORDER. Dates are a shuffle (multiplicative, mod a
// prime), heights ascend.
static void long_history_prefix(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    const size_t N = 300;
    for (size_t i = 0; i < N; i++) {
        uint32_t ts = 1600000000u + (uint32_t)((i * 7919u) % 307u) * 60u;
        BRTransaction *t = coinTx(a.s, 1000000 + i, (uint8_t)(i % 250 + 1), 1000 + (uint32_t)i, ts);
        t->inputs[0].txHash.u8[1] = (uint8_t)(i / 250);   // distinct parents
        finalizeTxHash(t);
        BRWalletRegisterTransaction(w, t);
    }
    size_t count = BRWalletTxRowCount(w);
    ck(count == N, "the wallet holds %zu transactions (got %zu)", N, count);
    BRTxRow *all = calloc(count ? count : 1, sizeof(*all)), *top = calloc(100, sizeof(*top));
    ck(all && top, "the row buffers are allocated");
    if (!all || !top) { free(all); free(top); BRWalletFree(w); return; }
    size_t na = BRWalletTxRows(w, NOW, all, count), nt = BRWalletTxRows(w, NOW, top, 100);
    ck(na == count && nt == 100, "the whole list and its first 100 (got %zu, %zu)", na, nt);
    ck(memcmp(all, top, 100 * sizeof(*top)) == 0, "the first 100 are the whole list's first 100");
    size_t bad = 0;
    for (size_t i = 1; i < na; i++) {
        if (all[i - 1].shownTimestamp < all[i].shownTimestamp) bad++;
        else if (all[i - 1].shownTimestamp == all[i].shownTimestamp && all[i - 1].blockHeight < all[i].blockHeight) bad++;
    }
    ck(bad == 0, "date descending, then height descending, down the whole list (%zu out of order)", bad);
    free(all); free(top);
    BRWalletFree(w);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "full_list_order", full_list_order },
    { "max_rows_newest_first", max_rows_newest_first },
    { "max_rows_exceeds_count", max_rows_exceeds_count },
    { "null_and_zero_buffer", null_and_zero_buffer },
    { "empty_wallet", empty_wallet },
    { "pending_row_shows_now", pending_row_shows_now },
    { "rows_are_fill", rows_are_fill },
    { "rows_outlive_wallet", rows_outlive_wallet },
    { "long_history_prefix", long_history_prefix },
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
        printf("ARM: %s\n", TX_ROWS_UNFIXED ? "comparison (TX_ROWS_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
