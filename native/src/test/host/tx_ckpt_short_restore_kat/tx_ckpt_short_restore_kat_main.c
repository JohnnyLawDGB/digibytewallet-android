//
//  tx_ckpt_short_restore_kat_main.c
//
//  Host KAT for the short-restore verdict in the core's BRSavedTransactions.h
//  (BRSavedTransactionsDeclaredCount, BRSavedTransactionsRestoreCheck), moved from Android's Kotlin
//  (core/.../sync/SavedTransactionsCheckpoint.kt declaredCount, isShort, recordRestore; WalletManager.kt
//  restoreFromDisk; app/.../service/CfRestorePreflight.kt TRANSACTION_CHECKPOINT_SHORT). The header must
//  answer what the Kotlin answers, so every expectation here is fixed OUTSIDE it, two ways:
//
//    1. BY HAND. The Kotlin tests' inputs and expected answers, value for value: SavedTransactionsCheckpointTest
//       (declared_*, short_*, record_*, sticky_record, lost_file, real_writer_blob, over_limit) and
//       TransactionCheckpointPreflightTest (preflight_*). Where a Kotlin test hands the restore a blob, the
//       case hands the core's reader (BRSavedTransactionsDeserialize) the same blob and judges by what it
//       loaded, as Android's loadSerializedTransactions does. The sticky record and the SyncService wiring
//       are the platform's; the case for the record holds only what the header answers (see LEFT TO THE
//       PLATFORM).
//    2. BY A MODEL. `mRecordRestore` below is the Kotlin written out line for line (WalletManager's choice of
//       inputs, then recordRestore over declaredCount and isShort). model_sweep holds the header to it over
//       every combination of a fixed set of inputs.
//
//  LEFT TO THE PLATFORM. SavedTransactionsCheckpointTest's source checks (the restore reads, loads, records,
//  then builds, in that order; the flag's commit carries the count) and TransactionCheckpointPreflightTest's
//  (SyncService checks the record first, the rebuild clears it in the frontier's commit, the checkpoint gate)
//  read Kotlin source. The iOS port's own tests must hold the same order. The sticky record itself (a
//  COMPLETE verdict never clears it) is the platform's store; sticky_record holds that the header writes no
//  record on COMPLETE.
//
//  One case per process: `tx_ckpt_short_restore_kat <case>` prints PASS:/FAIL: lines and
//  `RESULT <case> pass|fail`; `list` prints the case names. run.sh builds one binary per comparison arm and
//  holds each arm to failing exactly its cases.
//

#include "BRSavedTransactions.h"
#include "BRCFRestorePreflight.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
#define UNREADABLE BR_SAVED_TRANSACTIONS_UNREADABLE

// ---- the Kotlin, line for line -----------------------------------------------------------------

// SavedTransactionsCheckpoint.declaredCount: -2 for Kotlin null (no checkpoint).
#define M_NULL (-2)
static int64_t mDeclaredCount(const uint8_t *bytes, size_t len, int isNull)
{
    if (isNull) return M_NULL;
    if (len < 4) return UNREADABLE;
    return (int64_t)((uint64_t)bytes[0] | ((uint64_t)bytes[1] << 8) | ((uint64_t)bytes[2] << 16) | ((uint64_t)bytes[3] << 24));
}

// SavedTransactionsCheckpoint.isShort
static int mIsShort(int64_t declared, int64_t readerLoadedCount)
{
    if (declared == M_NULL) return 0;
    if (declared < 0) return 1;
    return readerLoadedCount < declared;
}

// WalletManager.restoreFromDisk's inputs and SavedTransactionsCheckpoint.recordRestore. fileState: 0 no
// file (SavedTransactionsStore.load returned null), 1 the file's bytes, 2 a read that threw (getOrElse:
// ByteArray(0)). loaded: loadSerializedTransactions' answer (only asked when the bytes are 4 or more).
// Returns 1 when a short restore is committed, with *record the value put under KEY_SHORT_RESTORE.
static int mRecordRestore(int fileState, const uint8_t *bytes, size_t len, int64_t loaded, int flag, int64_t keyCount,
                          int64_t *record)
{
    int64_t checkpointedCount = flag ? keyCount : 0;
    const uint8_t *savedTx = fileState == 1 ? bytes : NULL;
    size_t savedLen = fileState == 1 ? len : 0;
    int savedNull = fileState == 0;

    if (savedNull) {
        // if (savedTx == null && !rebuildInProgress && checkpointedCount > 0L) recordRestore(prefs, null, 0L, checkpointedCount)
        if (checkpointedCount > 0) {
            int64_t declared = checkpointedCount;   // bytes == null && checkpointedCount > 0
            if (mIsShort(declared, 0)) { *record = declared; return 1; }
        }
        return 0;
    }
    {
        int64_t readerLoaded = savedLen >= 4 ? loaded : 0;
        int64_t declared = mDeclaredCount(savedTx, savedLen, 0);   // recordRestore(prefs, savedTx, loaded) (count 0)
        if (! mIsShort(declared, readerLoaded)) return 0;
        *record = declared;   // declared ?: UNREADABLE (never null here)
        return 1;
    }
}

// The header on the same inputs, as a platform calls it.
static int hRecordRestore(int fileState, const uint8_t *bytes, size_t len, int64_t loaded, int flag, int64_t keyCount,
                          int64_t *record, BRSavedTransactionsRestore *verdictOut)
{
    int64_t declared = fileState == 1 ? BRSavedTransactionsDeclaredCount(bytes, len) : UNREADABLE;
    uint64_t readerLoaded = (fileState == 1 && len >= 4 && loaded > 0) ? (uint64_t)loaded : 0;
    BRSavedTransactionsRestore v = BRSavedTransactionsRestoreCheck(fileState != 0, fileState == 1, declared, readerLoaded,
                                                                   flag, keyCount, record);

    if (verdictOut) *verdictOut = v;
    return v != BR_SAVED_TX_RESTORE_COMPLETE;
}

// ---- fixtures ------------------------------------------------------------------------------------

// A blob declaring `count` records (framing only), with `tail` zero bytes after the count.
static size_t declaring(uint8_t *out, size_t cap, uint64_t count, size_t tail)
{
    size_t i;

    if (4 + tail > cap) { ck(0, "fixture too large"); return 0; }
    memset(out, 0, 4 + tail);
    for (i = 0; i < 4; i++) out[i] = (uint8_t)(count >> (8 * i));
    return 4 + tail;
}

// What the core's reader loads from a blob (Android's loadSerializedTransactions answer).
static int64_t readerLoads(const uint8_t *buf, size_t len)
{
    BRTransaction **txs = NULL;
    size_t n = BRSavedTransactionsDeserialize(buf, len, &txs), i;

    for (i = 0; txs && i < n; i++) BRTransactionFree(txs[i]);
    free(txs);
    return (int64_t)n;
}

// The judged restore of a blob that is there, through the core's reader: the header's verdict, and the
// value recorded (unchanged from `*record` on COMPLETE).
static BRSavedTransactionsRestore judgeBlob(const uint8_t *buf, size_t len, int64_t loaded, int64_t *record)
{
    return BRSavedTransactionsRestoreCheck(1, 1, BRSavedTransactionsDeclaredCount(buf, len), (uint64_t)loaded, 0, 0, record);
}

// ---- SavedTransactionsCheckpointTest --------------------------------------------------------------

static void declared_count(void)
{
    static uint8_t b[64];
    size_t n;

    n = declaring(b, sizeof(b), 10001, 8);
    ck(BRSavedTransactionsDeclaredCount(b, n) == 10001, "declared count is the leading little-endian u32 (10,001)");
    n = declaring(b, sizeof(b), 1, 0);
    ck(BRSavedTransactionsDeclaredCount(b, n) == 1, "a 4-byte blob declares its count (1)");
    n = declaring(b, sizeof(b), 0xFFFFFFFFu, 0);
    ck(BRSavedTransactionsDeclaredCount(b, n) == 0xFFFFFFFFLL, "0xFFFFFFFF reads as 4,294,967,295, not negative");
    n = declaring(b, sizeof(b), 10001, 0);
    ck(BRSavedTransactionsDeclaredCount(b, n) == 10001, "a 4-byte blob declaring 10,001 declares 10,001");
}

static void declared_unreadable(void)
{
    static const uint8_t three[3] = { 1, 2, 3 };

    ck(BRSavedTransactionsDeclaredCount(NULL, 0) == UNREADABLE, "no bytes are unreadable");
    ck(BRSavedTransactionsDeclaredCount(three, 0) == UNREADABLE, "an empty blob is unreadable");
    ck(BRSavedTransactionsDeclaredCount(three, 3) == UNREADABLE, "a 3-byte blob is unreadable");
    ck(UNREADABLE == -1, "UNREADABLE is the Kotlin's -1");
}

// isShort(declared, loaded): a file that is there, read, declaring `declared`, the reader loading `loaded`.
static int shortOf(int64_t declared, uint64_t loaded)
{
    int64_t r = 0;
    return BRSavedTransactionsRestoreCheck(1, declared >= 0, declared, loaded, 0, 0, &r) != BR_SAVED_TX_RESTORE_COMPLETE;
}

static void short_rule(void)
{
    int64_t r = 7;

    ck(shortOf(10001, 0), "10,001 declared, 0 loaded: short (the reader refused the whole blob)");
    ck(shortOf(10001, 10000), "10,001 declared, 10,000 loaded: short (one record lost)");
    ck(shortOf(UNREADABLE, 0), "an unreadable checkpoint is short");
    ck(! shortOf(10001, 10001), "10,001 of 10,001 is complete");
    ck(BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 0, 0, &r) == BR_SAVED_TX_RESTORE_COMPLETE && r == 7,
       "no checkpoint is not this rule's (complete, nothing recorded)");
    ck(! shortOf(0, 0), "0 declared, 0 loaded is complete");
    ck(! shortOf(3, 5), "a reader that loaded more than declared is not short");
}

static void record_restore(void)
{
    static uint8_t b[64];
    size_t n = declaring(b, sizeof(b), 10001, 0);
    int64_t r = 0;
    BRSavedTransactionsRestore v;

    ck(judgeBlob(b, n, 10001, &r) == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "a complete restore is not recorded");
    v = BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 0, 0, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "no file and no flag is not recorded");
    v = judgeBlob(b, n, 0, &r);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_LOADED && r == 10001, "a short restore is recorded with the declared count (%s, %lld)",
       BRSavedTransactionsRestoreName(v), (long long)r);
}

static void sticky_record(void)
{
    static uint8_t b[64];
    size_t n;
    int64_t r = 0;
    BRSavedTransactionsRestore v;

    // recordRestore(prefs, ByteArray(0), 0): unreadable, recorded -1
    v = BRSavedTransactionsRestoreCheck(1, 1, BRSavedTransactionsDeclaredCount(b, 0), 0, 0, 0, &r);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_UNREADABLE && r == UNREADABLE, "an empty checkpoint is short, recorded -1 (%s, %lld)",
       BRSavedTransactionsRestoreName(v), (long long)r);
    // a checkpoint rewritten from the short wallet, declaring 3 and loading 3: complete, and the record stands
    n = declaring(b, sizeof(b), 3, 0);
    v = judgeBlob(b, n, 3, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == UNREADABLE, "a later complete restore writes no record over it (r %lld)", (long long)r);
    // a file present but not read at all (Android: the read threw -> ByteArray(0))
    r = 0;
    v = BRSavedTransactionsRestoreCheck(1, 0, 12345, 12345, 1, 22, &r);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_UNREADABLE && r == UNREADABLE, "a file that could not be read is short, recorded -1");
}

static void lost_file(void)
{
    static uint8_t b[64];
    size_t n;
    int64_t r = 0;
    BRSavedTransactionsRestore v;

    v = BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 1, 22, &r);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_FILE_LOST && r == 22, "a checkpointed count of 22 with no file is short, recorded 22 (%s, %lld)",
       BRSavedTransactionsRestoreName(v), (long long)r);
    r = 0;
    v = BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 1, 0, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "an empty wallet's checkpoint (count 0) with no file is not short");
    n = declaring(b, sizeof(b), 3, 0);
    v = BRSavedTransactionsRestoreCheck(1, 1, BRSavedTransactionsDeclaredCount(b, n), 3, 1, 22, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "a file that is there is judged by what it declares, not by the stored count");
    v = BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 0, 22, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "a stored count without the flag is not read (WalletManager reads it only under the flag)");
}

static int hexDigit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void real_writer_blob(void)
{
    const char *path = getenv("TX_CKPT_VECTOR");
    FILE *f = path ? fopen(path, "r") : NULL;
    static char hex[1 << 16];
    static uint8_t blob[1 << 15];
    size_t hn, n = 0, i;
    int64_t loaded, r = 0;
    BRSavedTransactionsRestore v;

    if (! f) { ck(0, "TX_CKPT_VECTOR (%s) cannot be read", path ? path : "unset"); return; }
    hn = fread(hex, 1, sizeof(hex) - 1, f);
    fclose(f);
    hex[hn] = '\0';
    for (i = 0; i + 1 < hn && hexDigit(hex[i]) >= 0 && hexDigit(hex[i + 1]) >= 0 && n < sizeof(blob); i += 2)
        blob[n++] = (uint8_t)(hexDigit(hex[i]) * 16 + hexDigit(hex[i + 1]));
    ck(n > 4 + 3 * 12, "real_writer_3.hex is a blob of 3 records (%zu bytes)", n);
    ck(BRSavedTransactionsDeclaredCount(blob, n) == 3, "it declares 3");
    loaded = readerLoads(blob, n);
    ck(loaded == 3, "the core's reader loads all 3 (%lld)", (long long)loaded);
    v = judgeBlob(blob, n, loaded, &r);
    ck(v == BR_SAVED_TX_RESTORE_COMPLETE && r == 0, "the real writer's blob with the real reader's count is not short");
    v = judgeBlob(blob, n, 2, &r);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_LOADED && r == 3, "the same blob with a reader that dropped one record is short, recorded 3");
    // the blob cut inside its last record: the reader keeps the records before it, and the restore is short
    loaded = readerLoads(blob, n - 1);
    v = judgeBlob(blob, n - 1, loaded, &r);
    ck(loaded == 2 && v == BR_SAVED_TX_RESTORE_SHORT_LOADED && r == 3, "cut one byte short: 2 loaded of 3, short (%lld loaded)", (long long)loaded);
}

static void over_limit(void)
{
    static uint8_t b[256];
    size_t n = declaring(b, sizeof(b), (uint64_t)BR_SAVED_TRANSACTIONS_MAX_COUNT + 1, 64);
    int64_t loaded = readerLoads(b, n), r = 0;
    BRSavedTransactionsRestore v = judgeBlob(b, n, loaded, &r);

    ck(loaded == 0, "the reader refuses a checkpoint above the limit whole (%lld loaded)", (long long)loaded);
    ck(v == BR_SAVED_TX_RESTORE_SHORT_LOADED && r == (int64_t)BR_SAVED_TRANSACTIONS_MAX_COUNT + 1,
       "an earlier build's checkpoint above the limit is recorded short, with its count (%lld)", (long long)r);
}

// ---- TransactionCheckpointPreflightTest --------------------------------------------------------

static void putU32(uint8_t *b, size_t off, uint32_t v)
{
    b[off] = (uint8_t)v; b[off + 1] = (uint8_t)(v >> 8); b[off + 2] = (uint8_t)(v >> 16); b[off + 3] = (uint8_t)(v >> 24);
}

// SyncService's CF preflight: transactionCheckpointResetReason(shortRestoreRecorded) ?: cfRestoreResetReason(...,
// hasTransactionCheckpoint = true). The header gives the reset's name and decides nothing else here.
static const char *preflight(int shortRecorded, const uint8_t *ledger, size_t ledgerLen, const uint8_t *blocks, size_t blocksLen,
                             int wasSynced)
{
    if (shortRecorded) return BR_SAVED_TRANSACTIONS_SHORT_RESET_NAME;
    return BRCFRestoreResetName(BRCFRestorePreflight(ledger, ledgerLen, ledger != NULL, blocks, blocksLen, blocks != NULL, wasSynced, 1));
}

static uint8_t LEDGER[32], WINDOW[4 + 2 * 8];

static void healthy(void)
{
    memset(LEDGER, 0, sizeof(LEDGER));
    putU32(LEDGER, 0, 0x43464c31); putU32(LEDGER, 4, 3); putU32(LEDGER, 8, 23999000);
    putU32(LEDGER, 12, 24000000); putU32(LEDGER, 16, 24000100);
    memset(WINDOW, 0, sizeof(WINDOW));
    putU32(WINDOW, 0, 2); putU32(WINDOW, 8, 24000150); putU32(WINDOW, 16, 23999900);
}

static void preflight_complete_resumes(void)
{
    healthy();
    ck(strcmp(preflight(0, LEDGER, sizeof(LEDGER), WINDOW, sizeof(WINDOW), 1), "") == 0,
       "a complete restore on a healthy frontier resumes near the tip");
}

static void preflight_short_rebuilds(void)
{
    healthy();
    ck(strcmp(preflight(1, LEDGER, sizeof(LEDGER), WINDOW, sizeof(WINDOW), 1), "TRANSACTION_CHECKPOINT_SHORT") == 0,
       "a short restore on the same frontier rebuilds from the birth floor (TRANSACTION_CHECKPOINT_SHORT)");
    ck(strcmp(preflight(1, NULL, 0, NULL, 0, 0), "TRANSACTION_CHECKPOINT_SHORT") == 0,
       "...whatever else the stored state says: the shortfall is decided first");
}

static void preflight_old_reader_limit(void)
{
    static uint8_t b[64];
    size_t n = declaring(b, sizeof(b), 10001, 16);   // byteArrayOf(0x11, 0x27, 0, 0) + ByteArray(16)
    int64_t r = 0;
    int shortZero, shortAll;

    ck(b[0] == 0x11 && b[1] == 0x27 && BRSavedTransactionsDeclaredCount(b, n) == 10001, "the blob declares 10,001");
    shortZero = judgeBlob(b, n, 0, &r) != BR_SAVED_TX_RESTORE_COMPLETE;
    ck(shortZero, "10,001 declared, 0 loaded is short");
    healthy();
    ck(strcmp(preflight(shortZero, LEDGER, sizeof(LEDGER), WINDOW, sizeof(WINDOW), 1), "TRANSACTION_CHECKPOINT_SHORT") == 0,
       "a checkpoint past the old reader limit that loaded nothing rebuilds");
    shortAll = judgeBlob(b, n, 10001, &r) != BR_SAVED_TX_RESTORE_COMPLETE;
    ck(strcmp(preflight(shortAll, LEDGER, sizeof(LEDGER), WINDOW, sizeof(WINDOW), 1), "") == 0, "with all 10,001 loaded it resumes");
}

// ---- the model sweep -----------------------------------------------------------------------------

static void model_sweep(void)
{
    static uint8_t b[64];
    static const int64_t counts[] = { 0, 1, 3, 22, 10000, 10001, 100000, 100001, 0x7fffffffLL, 0xFFFFFFFFLL };
    static const size_t tails[] = { 0, 1, 8 };
    static const size_t shortLens[] = { 0, 1, 2, 3 };
    static const int64_t loadeds[] = { 0, 1, 2, 3, 21, 22, 10000, 10001, 100001, 0x7fffffffLL };
    static const int64_t keyCounts[] = { -1, 0, 1, 22, 100001 };
    int compared = 0, shorts = 0, bad = 0, fs, flag;
    size_t ci, li, ki, si;

    for (fs = 0; fs <= 2; fs++)
    for (flag = 0; flag <= 1; flag++)
    for (ki = 0; ki < N(keyCounts); ki++)
    for (li = 0; li < N(loadeds); li++) {
        size_t nBlobs = fs == 1 ? N(counts) * N(tails) + N(shortLens) : 1;
        for (ci = 0; ci < nBlobs; ci++) {
            size_t n = 0;
            int64_t mr = 99, hr = 99;
            int ms, hs;
            BRSavedTransactionsRestore v;

            if (fs == 1) {
                if (ci < N(counts) * N(tails)) n = declaring(b, sizeof(b), (uint64_t)counts[ci / N(tails)], tails[ci % N(tails)]);
                else { si = ci - N(counts) * N(tails); n = shortLens[si]; memset(b, 0x5a, n); }
            }
            ms = mRecordRestore(fs, b, n, loadeds[li], flag, keyCounts[ki], &mr);
            hs = hRecordRestore(fs, b, n, loadeds[li], flag, keyCounts[ki], &hr, &v);
            compared++;
            if (ms) shorts++;
            if (ms != hs || (ms && mr != hr) || (! hs && hr != 99)) {
                if (bad++ < 3)
                    ck(0, "file %d (%zu bytes, declares %lld), loaded %lld, flag %d, count %lld: Kotlin %s %lld, header %s %lld", fs, n,
                       (long long)(fs == 1 ? mDeclaredCount(b, n, 0) : M_NULL), (long long)loadeds[li], flag, (long long)keyCounts[ki],
                       ms ? "short" : "complete", (long long)mr, hs ? BRSavedTransactionsRestoreName(v) : "complete", (long long)hr);
            }
        }
    }
    ck(bad == 0, "the header's verdict and record are the Kotlin's on %d restores (%d short)", compared, shorts);
    ck(shorts > compared / 10 && shorts < compared - compared / 10, "the sweep reaches both answers");
}

static void names(void)
{
    ck(strcmp(BRSavedTransactionsRestoreName(BR_SAVED_TX_RESTORE_COMPLETE), "") == 0 &&
       strcmp(BRSavedTransactionsRestoreName(BR_SAVED_TX_RESTORE_SHORT_LOADED), "SHORT_LOADED") == 0 &&
       strcmp(BRSavedTransactionsRestoreName(BR_SAVED_TX_RESTORE_SHORT_UNREADABLE), "SHORT_UNREADABLE") == 0 &&
       strcmp(BRSavedTransactionsRestoreName(BR_SAVED_TX_RESTORE_SHORT_FILE_LOST), "SHORT_FILE_LOST") == 0,
       "the verdicts' names");
    ck(BR_SAVED_TX_RESTORE_COMPLETE == 0, "COMPLETE is 0");
    ck(strcmp(BR_SAVED_TRANSACTIONS_SHORT_RESET_NAME, "TRANSACTION_CHECKPOINT_SHORT") == 0, "the reset's name is Android's CfRestoreResetReason");
    {
        int64_t r = 5;
        BRSavedTransactionsRestore v = BRSavedTransactionsRestoreCheck(1, 1, 3, 0, 0, 0, NULL);
        ck(v == BR_SAVED_TX_RESTORE_SHORT_LOADED, "a NULL record pointer is allowed");
        (void)r;
    }
}

// ---- driver ------------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;

static const Case CASES[] = {
    { "declared_count", declared_count },
    { "declared_unreadable", declared_unreadable },
    { "short_rule", short_rule },
    { "record_restore", record_restore },
    { "sticky_record", sticky_record },
    { "lost_file", lost_file },
    { "real_writer_blob", real_writer_blob },
    { "over_limit", over_limit },
    { "preflight_complete_resumes", preflight_complete_resumes },
    { "preflight_short_rebuilds", preflight_short_rebuilds },
    { "preflight_old_reader_limit", preflight_old_reader_limit },
    { "model_sweep", model_sweep },
    { "names", names },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name); return 0; }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
            return FAILED ? 1 : 0;
        }
    }
    fprintf(stderr, "no case %s\n", argv[1]);
    return 2;
}
