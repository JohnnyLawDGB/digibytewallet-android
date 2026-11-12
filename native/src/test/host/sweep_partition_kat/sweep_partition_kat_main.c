// Host KAT: which of a recovered seed's outputs a sweep may spend as plain DGB (BRSweepPartition.h).
//
// THE RULES (moved from Kotlin: SweepPartition.kt; ForeignUtxoAssetClassifier.kt's verdicts;
// RecoverySequence.kt sweepExclusions and assetsLeftBehind):
//   - the verdict: no bytes, bytes the core cannot parse, or bytes that are not exactly the transaction
//     with the txid asked for (BRRawTxId) -> UNKNOWN; the core's DigiAsset reader finds
//     a carrier on any output -> ASSET (every output of the transaction), else PLAIN; an asset's rule
//     state is UNKNOWN unless a lookup answered it;
//   - one fetch per transaction: rows grouped by txid, each group taking its first row's verdict;
//   - the split: "could we tell?" before "is it an asset?"; the quoted total is the sweepable rows only;
//   - hold everything (the iOS rule while iOS cannot move assets): any ASSET or UNCLASSIFIED row;
//   - sweep exclusions: every move's asset outpoint and its plan's inputs, broadcast or not;
//   - left behind: asset-bearing outpoints with no move that arrived.
// Nine comparison arms, one per bit of -DSWEEP_PARTITION_UNFIXED (see BRSweepPartition.h). Each fails
// exactly the cases run.sh lists for its bit.
//
// Cases carry Android's SweepPartitionTest, ForeignUtxoAssetClassifierTest, RecoverySequenceTest,
// AssetsLeftBehindTest and ForeignSweepSelectionTest case for case (DuplicateUtxoTest is recovery_utxo_kat's,
// against BRRecoveryUtxo.h). Kotlin's classifier took the asset predicate as a lambda; here the predicate
// is the core's own parser and DigiAsset reader, so each classifier case builds a real raw transaction.
// Every expectation is written here by hand.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <time.h>

#include "BRSweepPartition.h"
#include "BRRecoveryScan.h"

int sweep_partition_kat_tu2(void);

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

// ---- rows --------------------------------------------------------------------------------------

static BRRecoveryUtxo row(uint8_t tag, uint32_t vout, int64_t sat)
{
    BRRecoveryUtxo u;
    memset(&u, 0, sizeof(u));
    u.txid[0] = tag; u.txid[31] = tag;
    u.vout = vout; u.amount = sat; u.blockHeight = 24000000;
    return u;
}

static BRSweepOutpoint op(uint8_t tag, uint32_t vout)
{
    BRSweepOutpoint o;
    memset(&o, 0, sizeof(o));
    o.txid[0] = tag; o.txid[31] = tag;
    o.vout = vout;
    return o;
}

static BRSweepAssetVerdict verdict(int classified, int carries)
{
    BRSweepAssetVerdict v = BRSweepVerdictUnknown();
    v.classified = classified; v.carriesAsset = carries;
    return v;
}

// ---- raw transactions --------------------------------------------------------------------------
// version 1, one input spending prevout <tag>:0 (empty scriptSig), the given output scripts at 1000 sat
// each, lock time 0.

static size_t rawTxTagged(uint8_t *buf, size_t cap, uint8_t tag, const uint8_t *const scripts[], const size_t lens[],
                          size_t n)
{
    size_t off = 0, i, j;
#define PUT(b) do { if (off < cap) buf[off] = (uint8_t)(b); off++; } while (0)
    PUT(1); PUT(0); PUT(0); PUT(0);
    PUT(1);
    for (i = 0; i < 32; i++) PUT(i == 0 ? tag : 0);
    PUT(0); PUT(0); PUT(0); PUT(0);
    PUT(0);
    PUT(0xff); PUT(0xff); PUT(0xff); PUT(0xff);
    PUT(n);
    for (i = 0; i < n; i++) {
        PUT(0xe8); PUT(0x03); for (j = 0; j < 6; j++) PUT(0);
        PUT(lens[i]);
        for (j = 0; j < lens[i]; j++) PUT(scripts[i][j]);
    }
    PUT(0); PUT(0); PUT(0); PUT(0);
#undef PUT
    return off;
}

static size_t rawTx(uint8_t *buf, size_t cap, const uint8_t *const scripts[], const size_t lens[], size_t n)
{
    return rawTxTagged(buf, cap, 0x22, scripts, lens, n);
}

// The id the bytes report (BRRawTxId); zero when they report none.
static UInt256 idOf(const uint8_t *raw, size_t len)
{
    UInt256 id = UINT256_ZERO;
    if (!BRRawTxId(raw, len, &id)) id = UINT256_ZERO;
    return id;
}

static BRSweepRawTxClass classifyOwn(const uint8_t *raw, size_t len)
{
    UInt256 id = idOf(raw, len);
    return BRSweepClassifyRawTx(raw, len, &id);
}

static const uint8_t P2PKH[25] = { 0x76, 0xa9, 20, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
                                   19, 20, 0x88, 0xac };
static const uint8_t DA_CARRIER[6] = { 0x6a, 0x04, 'D', 'A', 0x03, 0x15 };   // a transfer carrier
static const uint8_t DA_SHORT[5] = { 0x6a, 0x03, 'D', 'A', 0x03 };           // tagged, too short to classify
static const uint8_t OTHER_OPRETURN[4] = { 0x6a, 0x02, 'x', 'y' };           // not a DigiAsset carrier

// ---- fixtures: real transactions, each with its own id --------------------------------------------
// A row's txid is the id of the fixture it came from (internal byte order), so the fetch below serves the
// bytes that really are that transaction -- and a test can serve the wrong ones.

typedef struct { uint8_t tag; int isAsset; uint8_t raw[256]; size_t len; UInt256 id; } Fixture;
static Fixture g_fx[16];
static size_t g_fxCount = 0;

static const Fixture *fixture(uint8_t tag, int isAsset)
{
    const uint8_t *plain[] = { P2PKH, P2PKH }, *asset[] = { P2PKH, DA_CARRIER, P2PKH };
    size_t lp[] = { sizeof(P2PKH), sizeof(P2PKH) }, la[] = { sizeof(P2PKH), sizeof(DA_CARRIER), sizeof(P2PKH) }, i;
    Fixture *f;
    for (i = 0; i < g_fxCount; i++) if (g_fx[i].tag == tag && g_fx[i].isAsset == isAsset) return &g_fx[i];
    if (g_fxCount >= N(g_fx)) abort();
    f = &g_fx[g_fxCount++];
    f->tag = tag; f->isAsset = isAsset;
    f->len = isAsset ? rawTxTagged(f->raw, sizeof(f->raw), tag, asset, la, 3)
                     : rawTxTagged(f->raw, sizeof(f->raw), tag, plain, lp, 2);
    f->id = idOf(f->raw, f->len);
    if (UInt256IsZero(f->id)) abort();
    return f;
}

static BRRecoveryUtxo rowOf(const Fixture *f, uint32_t vout, int64_t sat)
{
    BRRecoveryUtxo u;
    memset(&u, 0, sizeof(u));
    memcpy(u.txid, f->id.u8, 32);
    u.vout = vout; u.amount = sat; u.blockHeight = 24000000;
    return u;
}

static const Fixture *fixtureFor(const BRRecoveryUtxo *u)
{
    size_t i;
    for (i = 0; i < g_fxCount; i++) if (memcmp(g_fx[i].id.u8, u->txid, 32) == 0) return &g_fx[i];
    return NULL;
}

// Kotlin's classify() written against the header: one "fetch" per distinct txid (fetch gives the raw
// bytes for the row's transaction, or NULL), checked against the row's txid; the rule lookup only for an
// asset transaction.
typedef struct { const uint8_t *raw; size_t len; } Fetched;
typedef Fetched (*FetchFn)(const BRRecoveryUtxo *u, int *fetches);
typedef int (*ResolveFn)(const BRRecoveryUtxo *u, int *state);   // nonzero: answered

static int classifyAll(const BRRecoveryUtxo *rows, size_t n, FetchFn fetch, ResolveFn resolve,
                       BRSweepAssetVerdict *out, int *fetches)
{
    size_t *work = malloc(sizeof(size_t) * (2 * n + 1)), *group = malloc(sizeof(size_t) * (n + 1)), i;
    int ok = work && group && BRSweepTxidGroups(rows, n, work, 2 * n, group) == BR_RECOVERY_UTXO_OK;

    for (i = 0; ok && i < n; i++) {
        if (group[i] == i) {
            Fetched f = fetch(&rows[i], fetches);
            UInt256 id;
            BRSweepRawTxClass c;
            memcpy(id.u8, rows[i].txid, 32);
            c = BRSweepClassifyRawTx(f.raw, f.len, &id);
            int state = -1, resolved = 0;
            if (c == BR_SWEEP_RAW_TX_ASSET && resolve) resolved = resolve(&rows[i], &state);
            out[i] = BRSweepVerdictOf(c, resolved, state);
        } else {
            out[i] = out[group[i]];   // group[i] < i: its verdict is already there
        }
    }
    free(work);
    free(group);
    return ok;
}

// Serves the row's own transaction (NULL for a txid no fixture has).
static Fetched fetchOwn(const BRRecoveryUtxo *u, int *f)
{
    const Fixture *x = fixtureFor(u);
    Fetched r = { NULL, 0 };
    (*f)++;
    if (x) { r.raw = x->raw; r.len = x->len; }
    return r;
}
static Fetched fetchNone(const BRRecoveryUtxo *u, int *f) { Fetched r = { NULL, 0 }; (void)u; (*f)++; return r; }
static Fetched fetchEmpty(const BRRecoveryUtxo *u, int *f) { Fetched r = { NULL, 0 }; const Fixture *x = fixtureFor(u);
    (*f)++; if (x) r.raw = x->raw; return r; }
static const uint8_t g_junk[3] = { 1, 2, 3 };
static Fetched fetchJunk(const BRRecoveryUtxo *u, int *f) { Fetched r = { g_junk, sizeof(g_junk) }; (void)u; (*f)++; return r; }
static Fetched fetchDeadNone(const BRRecoveryUtxo *u, int *f)
{
    const Fixture *x = fixtureFor(u);
    Fetched r = fetchOwn(u, f);
    if (x && x->tag == 0xde) { r.raw = NULL; r.len = 0; }
    return r;
}
// A provider answering every txid with one plain transaction (tag 0x01): the wrong bytes for any other.
static Fetched fetchSomePlain(const BRRecoveryUtxo *u, int *f)
{
    const Fixture *x = fixture(0x01, 0);
    Fetched r = { x->raw, x->len };
    (void)u; (*f)++;
    return r;
}
// The row's own transaction with one byte appended: it parses, but is not exactly that transaction.
static uint8_t g_trail[260];
static Fetched fetchTrailing(const BRRecoveryUtxo *u, int *f)
{
    const Fixture *x = fixtureFor(u);
    Fetched r = { NULL, 0 };
    (*f)++;
    if (x) { memcpy(g_trail, x->raw, x->len); g_trail[x->len] = 0x00; r.raw = g_trail; r.len = x->len + 1; }
    return r;
}
static int resolveNone(const BRRecoveryUtxo *u, int *s) { (void)u; *s = BR_SWEEP_RULE_NONE; return 1; }
static int resolveBound(const BRRecoveryUtxo *u, int *s) { (void)u; *s = BR_SWEEP_RULE_BOUND; return 1; }
static int resolveThrows(const BRRecoveryUtxo *u, int *s) { (void)u; (void)s; return 0; }

// ---- SweepPartitionTest ------------------------------------------------------------------------

#define PLAIN_A row(0xaa, 0, 100000)
#define PLAIN_B row(0xbb, 1, 100000)
#define ASSET_C row(0xcc, 0, 6000)
#define ASSET_D row(0xdd, 2, 600)

static int splitOk(const BRRecoveryUtxo *u, const BRSweepAssetVerdict *v, size_t n, BRSweepBucket *b,
                   BRSweepPartitionSummary *s)
{
    return BRSweepPartitionSplit(u, v, n, b, s) == BR_RECOVERY_UTXO_OK;
}

static void sp_asset_never_swept(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, ASSET_C, PLAIN_B, ASSET_D };
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 1), verdict(1, 0), verdict(1, 1) };
    BRSweepBucket b[4];
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 4, b, &s), "split");
    ck(b[0] == BR_SWEEP_BUCKET_SWEEPABLE && b[2] == BR_SWEEP_BUCKET_SWEEPABLE && s.sweepable == 2,
       "sweepable is plainA, plainB (%zu)", s.sweepable);
    ck(b[1] != BR_SWEEP_BUCKET_SWEEPABLE && b[3] != BR_SWEEP_BUCKET_SWEEPABLE, "no asset outpoint is swept");
    ck(b[1] == BR_SWEEP_BUCKET_ASSET && b[3] == BR_SWEEP_BUCKET_ASSET && s.assetBearing == 2,
       "assetBearing is assetC, assetD");
}

static void sp_no_assets_sweeps_all(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, PLAIN_B };
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 0) };
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 2, NULL, &s) && s.sweepable == 2 && s.assetBearing == 0 && s.unclassified == 0,
       "a wallet with no assets sweeps entirely");
}

static void sp_only_assets_sweeps_nothing(void)
{
    BRRecoveryUtxo u[] = { ASSET_C, ASSET_D };
    BRSweepAssetVerdict v[] = { verdict(1, 1), verdict(1, 1) };
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 2, NULL, &s), "split");
    ck(s.sweepable == 0, "nothing may be swept (%zu)", s.sweepable);
    ck(s.assetBearing == 2, "both asset-bearing");
}

static void sp_unclassifiable_held(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, ASSET_C };
    // carriesAsset says "no" for both, but cccc was never classified.
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(0, 0) };
    BRSweepBucket b[2];
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 2, b, &s), "split");
    ck(b[0] == BR_SWEEP_BUCKET_SWEEPABLE && s.sweepable == 1, "sweepable is plainA only");
    ck(b[1] == BR_SWEEP_BUCKET_UNCLASSIFIED && s.unclassified == 1, "cccc is held back, unclassified");
    ck(s.assetBearing == 0, "unclassified is not an asset claim");
}

static void sp_classification_fails_entirely(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, PLAIN_B, ASSET_C };
    BRSweepAssetVerdict v[] = { verdict(0, 0), verdict(0, 0), verdict(0, 0) };
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 3, NULL, &s), "split");
    ck(s.sweepable == 0, "nothing is swept (%zu)", s.sweepable);
    ck(s.unclassified == 3, "all three unclassified");
}

static void sp_every_utxo_one_bucket(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, PLAIN_B, ASSET_C, ASSET_D };
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 0), verdict(1, 1), verdict(0, 0) };
    BRSweepBucket b[4] = { 9, 9, 9, 9 };
    BRSweepPartitionSummary s;
    size_t i, inOne = 0;
    ck(splitOk(u, v, 4, b, &s), "split");
    for (i = 0; i < 4; i++) inOne += (b[i] <= BR_SWEEP_BUCKET_UNCLASSIFIED);
    ck(inOne == 4 && s.sweepable + s.assetBearing + s.unclassified == 4, "every row in exactly one bucket");
}

static void sp_sweepable_total(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, PLAIN_B, ASSET_C };
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 0), verdict(1, 1) };
    BRSweepPartitionSummary s;
    ck(splitOk(u, v, 3, NULL, &s) && s.sweepableSat == 200000, "the quoted total is 200000 (%lld)",
       (long long)s.sweepableSat);
}

static void sp_empty_wallet(void)
{
    BRSweepPartitionSummary s;
    s.sweepable = 7; s.sweepableSat = 7;
    ck(BRSweepPartitionSplit(NULL, NULL, 0, NULL, &s) == BR_RECOVERY_UTXO_OK && s.sweepable == 0 &&
       s.sweepableSat == 0, "an empty wallet is not an error");
}

// C-only: arguments, a missing verdict, an unknown flag value, overflow.
static void sp_arguments(void)
{
    BRRecoveryUtxo u[] = { PLAIN_A, PLAIN_B }, big[] = { row(1, 0, INT64_MAX), row(2, 0, 1) };
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 0) }, odd = verdict(7, 0);
    BRSweepBucket b[2] = { 9, 9 };
    BRSweepPartitionSummary s;
    ck(BRSweepPartitionSplit(u, v, 2, NULL, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL summary");
    ck(BRSweepPartitionSplit(NULL, v, 2, NULL, &s) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL rows");
    ck(splitOk(u, NULL, 2, b, &s) && s.unclassified == 2 && s.sweepable == 0 &&
       b[0] == BR_SWEEP_BUCKET_UNCLASSIFIED, "no verdicts at all: every row held, unclassified");
    ck(BRSweepBucketOf(NULL) == BR_SWEEP_BUCKET_UNCLASSIFIED, "a NULL verdict is unclassified");
    ck(BRSweepBucketOf(&odd) == BR_SWEEP_BUCKET_SWEEPABLE, "any nonzero classified counts as classified");
    ck(BRSweepPartitionSplit(big, v, 2, b, &s) == BR_RECOVERY_UTXO_OVERFLOW && s.sweepableSat == 0 &&
       s.sweepable == 0, "a total past INT64_MAX is reported, never wrapped");
    ck(b[0] == BR_SWEEP_BUCKET_SWEEPABLE && b[1] == BR_SWEEP_BUCKET_SWEEPABLE, "buckets still written");
}

// ---- ForeignUtxoAssetClassifierTest -------------------------------------------------------------

static void cl_plain_tx_plain(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xaa, 0), 0, 50000);
    ck(classifyAll(u, 1, fetchOwn, resolveNone, v, &f), "classify");
    ck(v[0].classified && !v[0].carriesAsset, "a plain transaction classifies as no asset");
}

static void cl_asset_tx_asset(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xbb, 1), 0, 50000);
    ck(classifyAll(u, 1, fetchOwn, resolveNone, v, &f), "classify");
    ck(v[0].classified && v[0].carriesAsset, "an asset transaction classifies as carrying an asset");
}

static void cl_every_output_held_change_included(void)
{
    const Fixture *x = fixture(0xcc, 1);
    BRRecoveryUtxo u[3];
    BRSweepAssetVerdict v[3];
    int f = 0;
    u[0] = rowOf(x, 0, 1); u[1] = rowOf(x, 1, 1); u[2] = rowOf(x, 2, 1);
    ck(classifyAll(u, 3, fetchOwn, resolveNone, v, &f), "classify");
    ck(v[0].carriesAsset && v[1].carriesAsset && v[2].carriesAsset, "all three outputs must be flagged");
}

static void cl_unfetchable_unknown(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xdd, 1), 0, 50000);
    ck(classifyAll(u, 1, fetchNone, resolveNone, v, &f), "classify");
    ck(!v[0].classified, "must not claim to have classified it");
    ck(!v[0].carriesAsset, "unknown is not an asset claim either");
}

static void cl_empty_body_unknown(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xee, 0), 0, 50000);
    ck(classifyAll(u, 1, fetchEmpty, resolveNone, v, &f) && !v[0].classified, "an empty body is unknown");
}

// Kotlin: "a throwing parser is unknown". Here the parser is the core's: bytes it cannot read.
static void cl_unparseable_unknown(void)
{
    const Fixture *x = fixture(0xff, 1);
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(x, 0, 50000);
    ck(classifyAll(u, 1, fetchJunk, resolveNone, v, &f), "classify");
    ck(!v[0].classified && !v[0].carriesAsset, "bytes the core cannot parse are unknown, not safe");
    // an asset transaction cut short, inside its last output, asked for under its own id
    ck(BRSweepClassifyRawTx(x->raw, x->len - 9, &x->id) == BR_SWEEP_RAW_TX_UNREADABLE, "a truncated asset tx is unreadable");
}

static void cl_one_unreachable_not_strand(void)
{
    BRRecoveryUtxo u[2];
    BRSweepAssetVerdict v[2];
    int f = 0;
    u[0] = rowOf(fixture(0xaa, 0), 0, 1); u[1] = rowOf(fixture(0xde, 0), 0, 1);
    ck(classifyAll(u, 2, fetchDeadNone, resolveNone, v, &f), "classify");
    ck(v[0].classified, "the reachable one still classifies");
    ck(!v[1].classified, "the unreachable one is unknown");
}

static void cl_parent_fetched_once(void)
{
    const Fixture *c = fixture(0xcc, 0), *a = fixture(0xaa, 0);
    BRRecoveryUtxo u[4];
    BRSweepAssetVerdict v[4];
    size_t work[8], group[4];
    int f = 0;
    u[0] = rowOf(c, 0, 1); u[1] = rowOf(c, 1, 1); u[2] = rowOf(c, 2, 1); u[3] = rowOf(a, 0, 1);
    ck(classifyAll(u, 4, fetchOwn, resolveNone, v, &f) && f == 2, "one fetch per distinct txid (%d)", f);
    ck(v[0].classified && v[3].classified, "each classified from its own transaction");
    ck(BRSweepTxidGroups(u, 4, work, 8, group) == BR_RECOVERY_UTXO_OK && group[0] == 0 && group[1] == 0 &&
       group[2] == 0 && group[3] == 3, "rows map to the first row naming their txid");
}

static void cl_empty_wallet(void)
{
    size_t g[1] = { 9 };
    ck(BRSweepTxidGroups(NULL, 0, NULL, 0, g) == BR_RECOVERY_UTXO_OK && g[0] == 9, "nothing to classify");
    ck(!BRSweepFoundAssetOrUnknown(NULL, 0), "and nothing to hold");
}

static void cl_rule_state_carried(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xcc, 1), 0, 1);
    ck(classifyAll(u, 1, fetchOwn, resolveBound, v, &f), "classify");
    ck(v[0].carriesAsset && v[0].ruleState == BR_SWEEP_RULE_BOUND, "the resolved rule state is carried");
}

static void cl_resolver_throws_unknown(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xdd, 1), 0, 1);
    ck(classifyAll(u, 1, fetchOwn, resolveThrows, v, &f), "classify");
    ck(v[0].carriesAsset, "still an asset");
    ck(v[0].ruleState == BR_SWEEP_RULE_UNKNOWN, "rule state UNKNOWN, not NONE (%d)", (int)v[0].ruleState);
}

static void cl_no_resolver_unknown(void)
{
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(fixture(0xee, 1), 0, 1);
    ck(classifyAll(u, 1, fetchOwn, NULL, v, &f) && v[0].ruleState == BR_SWEEP_RULE_UNKNOWN,
       "without a resolver the rule state is UNKNOWN (%d)", (int)v[0].ruleState);
}

// C-only: the core's own reader decides, carrier by carrier; out-of-range enum values.
static void cl_core_reader(void)
{
    uint8_t buf[256];
    size_t len;
    const uint8_t *s1[] = { P2PKH, OTHER_OPRETURN }, *s2[] = { DA_SHORT }, *s3[] = { P2PKH, P2PKH, DA_CARRIER };
    size_t l1[] = { sizeof(P2PKH), sizeof(OTHER_OPRETURN) }, l2[] = { sizeof(DA_SHORT) },
           l3[] = { sizeof(P2PKH), sizeof(P2PKH), sizeof(DA_CARRIER) };
    BRSweepAssetVerdict w;

    len = rawTx(buf, sizeof(buf), s1, l1, 2);
    ck(classifyOwn(buf, len) == BR_SWEEP_RAW_TX_PLAIN, "an OP_RETURN without the DA tag is plain");
    len = rawTx(buf, sizeof(buf), s2, l2, 1);
    ck(classifyOwn(buf, len) == BR_SWEEP_RAW_TX_ASSET, "a tagged carrier it cannot classify is held");
    len = rawTx(buf, sizeof(buf), s3, l3, 3);
    ck(classifyOwn(buf, len) == BR_SWEEP_RAW_TX_ASSET, "a carrier on the last output counts");
    ck(BRSweepClassifyRawTx(NULL, 5, NULL) == BR_SWEEP_RAW_TX_ABSENT, "NULL bytes are absent");
    w = BRSweepVerdictOf((BRSweepRawTxClass)17, 1, BR_SWEEP_RULE_NONE);
    ck(!w.classified && !w.carriesAsset, "a class the header does not know is unknown");
    w = BRSweepVerdictOf(BR_SWEEP_RAW_TX_ASSET, 1, 9);
    ck(w.ruleState == BR_SWEEP_RULE_UNKNOWN, "a rule state the header does not know reads UNKNOWN");
    w = BRSweepVerdictOf(BR_SWEEP_RAW_TX_ASSET, 1, BR_SWEEP_RULE_NONE);
    ck(w.ruleState == BR_SWEEP_RULE_NONE, "an answered NONE is NONE");
    w = BRSweepVerdictOf(BR_SWEEP_RAW_TX_PLAIN, 1, BR_SWEEP_RULE_NONE);
    ck(w.classified && !w.carriesAsset && w.ruleState == BR_SWEEP_RULE_UNKNOWN, "plain carries no rule state");
}

// The txid check: the bytes must BE the transaction asked for.
static void cl_wrong_tx_served(void)
{
    const Fixture *asset = fixture(0xab, 1);
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(asset, 0, 6000);
    ck(classifyAll(u, 1, fetchSomePlain, resolveNone, v, &f), "classify");
    ck(!v[0].classified, "a plain transaction served for an asset's txid is unknown, never plain");
    ck(BRSweepClassifyRawTx(fixture(0x01, 0)->raw, fixture(0x01, 0)->len, &asset->id) == BR_SWEEP_RAW_TX_UNREADABLE,
       "the class is UNREADABLE");
    ck(BRSweepClassifyRawTx(asset->raw, asset->len, &asset->id) == BR_SWEEP_RAW_TX_ASSET, "its own bytes are ASSET");
}

static void cl_not_exact_bytes(void)
{
    const Fixture *plain = fixture(0xac, 0);
    BRRecoveryUtxo u[1];
    BRSweepAssetVerdict v[1];
    int f = 0;
    u[0] = rowOf(plain, 0, 6000);
    ck(classifyAll(u, 1, fetchTrailing, resolveNone, v, &f), "classify");
    ck(!v[0].classified, "the right transaction plus a trailing byte is unknown (it does not re-serialize exactly)");
    ck(BRSweepClassifyRawTx(plain->raw, plain->len, NULL) == BR_SWEEP_RAW_TX_UNREADABLE,
       "bytes with no txid to check against are UNREADABLE");
    ck(BRSweepClassifyRawTx(plain->raw, plain->len, &plain->id) == BR_SWEEP_RAW_TX_PLAIN, "its exact bytes are PLAIN");
}

static void cl_groups_arguments(void)
{
    BRRecoveryUtxo u[] = { row(1, 0, 1), row(2, 0, 1) };
    size_t work[4], g[2];
    ck(BRSweepTxidGroups(u, 2, work, 3, g) == BR_RECOVERY_UTXO_BUFFER_TOO_SMALL, "work too small");
    ck(BRSweepTxidGroups(u, 2, NULL, 4, g) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL work");
    ck(BRSweepTxidGroups(u, 2, work, 4, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL groups");
}

// ---- the iOS rule: hold everything -------------------------------------------------------------

static void ios_asset_holds(void)
{
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 1), verdict(1, 0) };
    ck(BRSweepFoundAssetOrUnknown(v, 3), "one asset row holds the whole seed");
}

static void ios_unknown_holds(void)
{
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(0, 0) };
    ck(BRSweepFoundAssetOrUnknown(v, 2), "one row that could not be classified holds the whole seed");
    ck(BRSweepFoundAssetOrUnknown(NULL, 2), "no verdicts at all hold");
}

static void ios_plain_does_not_hold(void)
{
    BRSweepAssetVerdict v[] = { verdict(1, 0), verdict(1, 0) };
    ck(!BRSweepFoundAssetOrUnknown(v, 2), "a plain seed sweeps");
}

// ---- RecoverySequenceTest ----------------------------------------------------------------------

static int inSet(const BRSweepOutpoint *set, size_t n, BRSweepOutpoint o) { return BRSweepOutpointIn(set, n, &o); }

static int exclusions(const BRSweepMoveRecord *r, size_t n, BRSweepOutpoint *out, size_t cap, size_t *count)
{
    return BRSweepExclusions(r, n, out, cap, count) == BR_RECOVERY_UTXO_OK;
}

static BRSweepMoveRecord rec(BRSweepOutpoint asset, const BRSweepOutpoint *spent, size_t k, int broadcast)
{
    BRSweepMoveRecord r;
    r.outpoint = asset; r.spentInputs = spent; r.spentCount = k; r.broadcast = broadcast;
    return r;
}

static void rs_spent_withheld(void)
{
    BRSweepOutpoint spent[] = { op(0xa1, 0), op(0xf1, 0) }, out[8];
    BRSweepMoveRecord r[] = { rec(op(0xa1, 0), spent, 2, 1) };
    size_t n = 0;
    ck(exclusions(r, 1, out, 8, &n), "exclusions");
    ck(inSet(out, n, op(0xa1, 0)), "the asset input is excluded");
    ck(inSet(out, n, op(0xf1, 0)), "the fee input it spent is excluded");
}

static void rs_broadcast_unavailable(void)
{
    BRSweepOutpoint spent[] = { op(0xa0, 0), op(0xf0, 0) }, out[8];
    BRSweepMoveRecord r[] = { rec(op(0xa0, 0), spent, 2, 1) };
    size_t n = 0;
    ck(exclusions(r, 1, out, 8, &n) && n == 2 && inSet(out, n, op(0xa0, 0)) && inSet(out, n, op(0xf0, 0)),
       "exactly {a, f} (%zu)", n);
}

static void rs_failed_move_holds_plan(void)
{
    BRSweepOutpoint spent[] = { op(0xa1, 0), op(0xf1, 0) }, out[8];
    BRSweepMoveRecord r[] = { rec(op(0xa1, 0), spent, 2, 0) };
    size_t n = 0;
    ck(exclusions(r, 1, out, 8, &n), "exclusions");
    ck(inSet(out, n, op(0xa1, 0)), "the asset stays put");
    ck(inSet(out, n, op(0xf1, 0)), "the DGB its retry needs stays put");
}

static void rs_no_plan_holds_outpoint(void)
{
    BRSweepOutpoint out[4];
    BRSweepMoveRecord r[] = { rec(op(0xa1, 0), NULL, 0, 0) };
    size_t n = 0;
    ck(exclusions(r, 1, out, 4, &n) && n == 1 && inSet(out, n, op(0xa1, 0)), "only its own asset outpoint");
}

static void rs_disjoint(void)
{
    BRSweepOutpoint all[] = { op(0xa0, 0), op(0xf1, 0), op(0xf2, 0), op(0xd1, 0), op(0xd2, 0) };
    BRSweepOutpoint spent[] = { op(0xa0, 0), op(0xf1, 0) }, out[8], sweep[5];
    BRSweepMoveRecord r[] = { rec(op(0xa0, 0), spent, 2, 1) };
    size_t n = 0, m = 0, i, both = 0;
    ck(exclusions(r, 1, out, 8, &n), "exclusions");
    for (i = 0; i < N(all); i++) if (!inSet(out, n, all[i])) sweep[m++] = all[i];
    ck(m == 3 && BRSweepOutpointCompare(&sweep[0], &all[2]) == 0 && BRSweepOutpointCompare(&sweep[1], &all[3]) == 0 &&
       BRSweepOutpointCompare(&sweep[2], &all[4]) == 0, "sweepable is f2, d1, d2 (%zu)", m);
    for (i = 0; i < m; i++) both += inSet(out, n, sweep[i]);
    ck(both == 0, "no outpoint appears in both transactions");
}

static void rs_several_moves(void)
{
    BRSweepOutpoint s1[] = { op(0xa1, 0), op(0xf1, 0) }, s2[] = { op(0xa2, 0), op(0xf2, 0) }, out[8];
    BRSweepMoveRecord r[] = { rec(op(0xa1, 0), s1, 2, 1), rec(op(0xa2, 0), s2, 2, 0) };
    size_t n = 0;
    ck(exclusions(r, 2, out, 8, &n) && n == 4 && inSet(out, n, op(0xa1, 0)) && inSet(out, n, op(0xf1, 0)) &&
       inSet(out, n, op(0xa2, 0)) && inSet(out, n, op(0xf2, 0)), "a1, f1, a2, f2 (%zu)", n);
}

static void rs_no_moves(void)
{
    size_t n = 9;
    ck(BRSweepExclusions(NULL, 0, NULL, 0, &n) == BR_RECOVERY_UTXO_OK && n == 0, "nothing is withheld");
}

// C-only: the set is sorted and distinct; arguments.
static void rs_set_and_arguments(void)
{
    BRSweepOutpoint s1[] = { op(0x30, 1), op(0x10, 0), op(0x30, 0) }, s2[] = { op(0x10, 0), op(0x30, 1) }, out[8];
    BRSweepMoveRecord r[] = { rec(op(0x30, 1), s1, 3, 1), rec(op(0x20, 0), s2, 2, 1) }, bad = rec(op(1, 0), NULL, 2, 1);
    size_t n = 0, need = 0, i, sorted = 1;
    ck(BRSweepExclusionsCapacity(r, 2, &need) == BR_RECOVERY_UTXO_OK && need == 7, "capacity 3+1 + 2+1 (%zu)", need);
    ck(BRSweepExclusions(r, 2, out, 6, &n) == BR_RECOVERY_UTXO_BUFFER_TOO_SMALL && n == 0, "too small");
    ck(exclusions(r, 2, out, 8, &n) && n == 4, "distinct: 10:0 20:0 30:0 30:1 (%zu)", n);
    for (i = 1; i < n; i++) sorted &= BRSweepOutpointCompare(&out[i - 1], &out[i]) < 0;
    ck(sorted, "sorted ascending, distinct");
    ck(!inSet(out, n, op(0x30, 2)) && !inSet(out, n, op(0x40, 0)), "a different vout or txid is not in it");
    ck(BRSweepExclusions(&bad, 1, out, 8, &n) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "spentCount without inputs");
    ck(BRSweepExclusions(r, 2, out, 8, NULL) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL count");
    ck(!BRSweepOutpointIn(NULL, 3, &out[0]) && !BRSweepOutpointIn(out, n, NULL), "NULL set or outpoint");
}

// ---- AssetsLeftBehindTest ----------------------------------------------------------------------

static BRSweepMoveOutcome mv(BRSweepOutpoint o, int moved) { BRSweepMoveOutcome m; m.outpoint = o; m.moved = moved; return m; }

static int leftBehind(const BRSweepOutpoint *a, size_t n, const BRSweepMoveOutcome *m, size_t k, size_t *out, size_t *count)
{
    BRSweepOutpoint work[8];
    return BRSweepAssetsLeftBehind(a, n, m, k, work, 8, out, 8, count) == BR_RECOVERY_UTXO_OK;
}

#define LB_A op(0xaa, 0)
#define LB_B op(0xbb, 0)

static void lb_moved_not_left(void)
{
    BRSweepOutpoint a[] = { LB_A, LB_B };
    BRSweepMoveOutcome m[] = { mv(LB_A, 1), mv(LB_B, 1) };
    size_t out[8], n = 9;
    ck(leftBehind(a, 2, m, 2, out, &n) && n == 0, "both moved, so nothing was left behind (%zu)", n);
}

static void lb_failed_left(void)
{
    BRSweepOutpoint a[] = { LB_A, LB_B };
    BRSweepMoveOutcome m[] = { mv(LB_A, 1), mv(LB_B, 0) };
    size_t out[8], n = 9;
    ck(leftBehind(a, 2, m, 2, out, &n) && n == 1 && out[0] == 1, "[b] (%zu)", n);
}

static void lb_never_reported_left(void)
{
    BRSweepOutpoint a[] = { LB_A, LB_B };
    BRSweepMoveOutcome m[] = { mv(LB_A, 1) };
    size_t out[8], n = 9;
    ck(leftBehind(a, 2, m, 1, out, &n) && n == 1 && out[0] == 1, "[b] (%zu)", n);
}

static void lb_no_assets(void)
{
    size_t n = 9;
    ck(BRSweepAssetsLeftBehind(NULL, 0, NULL, 0, NULL, 0, NULL, 0, &n) == BR_RECOVERY_UTXO_OK && n == 0,
       "no assets means nothing to report");
}

static void lb_order_preserved(void)
{
    BRSweepOutpoint a[] = { LB_A, LB_B, op(0xcc, 0) };
    BRSweepMoveOutcome m[] = { mv(LB_B, 1) };
    size_t out[8], n = 9;
    ck(leftBehind(a, 3, m, 1, out, &n) && n == 2 && out[0] == 0 && out[1] == 2, "[a, c] in order (%zu)", n);
}

// C-only: a move to a different vout of the same txid is a different asset; arguments.
static void lb_arguments(void)
{
    BRSweepOutpoint a[] = { LB_A, LB_A, op(0xaa, 1) }, work[1];
    BRSweepMoveOutcome m[] = { mv(op(0xaa, 1), 1), mv(LB_B, 1) };
    size_t out[8], n = 9;
    ck(leftBehind(a, 3, m, 2, out, &n) && n == 2 && out[0] == 0 && out[1] == 1,
       "vout 1 arrived, vout 0 (listed twice) did not (%zu)", n);
    ck(BRSweepAssetsLeftBehind(a, 3, m, 2, work, 1, out, 8, &n) == BR_RECOVERY_UTXO_BUFFER_TOO_SMALL && n == 0,
       "work too small");
    ck(BRSweepAssetsLeftBehind(a, 3, m, 2, NULL, 0, out, 1, &n) == BR_RECOVERY_UTXO_NULL_ARGUMENT, "NULL work");
}

// ---- ForeignSweepSelectionTest (the profiles a sweep takes, before the split) ---------------------

static void fs_foreign_includes_native_own_excludes(void)
{
    BRRecoveryScanProfileResult r[2];
    size_t out[2] = { 9, 9 }, n = 9;
    memset(r, 0, sizeof(r));
    r[0].addressCount = 1; r[0].reachable = 1; r[0].isNative = 1; r[0].totalSat = 200000000;
    r[1].addressCount = 1; r[1].reachable = 1; r[1].isNative = 0; r[1].totalSat = 100000000;
    ck(BRRecoveryScanSweepSet(r, 2, 1, out, 2, &n) == BR_RECOVERY_SCAN_OK && n == 2 && out[0] == 0 && out[1] == 1,
       "foreign: native + legacy both swept (%zu)", n);
    ck(BRRecoveryScanSweepSet(r, 2, 0, out, 2, &n) == BR_RECOVERY_SCAN_OK && n == 1 && out[0] == 1,
       "own: native left in place (%zu)", n);
}

// ---- cost --------------------------------------------------------------------------------------
// The server decides the row count. Time each loop at n and 16n, best of three: an O(n log n) loop grows
// about 20x, a quadratic one 256x. The bound is a ratio, never an absolute time.

static double timeIt(int which, size_t n)
{
    double best = 1e30;
    int rep;
    for (rep = 0; rep < 3; rep++) {
        size_t i, cnt = 0;
        clock_t t0;
        double dt;
        BRRecoveryUtxo *u = NULL;
        BRSweepOutpoint *ops = NULL, *work = NULL;
        BRSweepMoveOutcome *mvs = NULL;
        BRSweepMoveRecord rr;
        size_t *w = NULL, *g = NULL;

        if (which == 0) {
            u = malloc(n * sizeof(*u)); w = malloc(2 * n * sizeof(*w)); g = malloc(n * sizeof(*g));
            for (i = 0; i < n; i++) { u[i] = row(0, 0, 1); u[i].txid[1] = (uint8_t)(i % 251);
                u[i].txid[2] = (uint8_t)((i / 251) % 251); u[i].txid[3] = (uint8_t)(i / 63001); }
        } else {
            ops = malloc(n * sizeof(*ops)); work = malloc(n * sizeof(*work)); mvs = malloc(n * sizeof(*mvs));
            w = malloc(n * sizeof(*w));
            for (i = 0; i < n; i++) { ops[i] = op((uint8_t)(i * 7), (uint32_t)(n - i)); mvs[i] = mv(ops[i], (int)(i & 1)); }
        }
        t0 = clock();
        if (which == 0) {
            if (BRSweepTxidGroups(u, n, w, 2 * n, g) == BR_RECOVERY_UTXO_OK) cnt = g[n - 1];
        } else if (which == 1) {
            rr = rec(ops[0], ops, n - 1, 0);
            if (BRSweepExclusions(&rr, 1, work, n, &cnt) != BR_RECOVERY_UTXO_OK) cnt = 0;
        } else {
            if (BRSweepAssetsLeftBehind(ops, n, mvs, n, work, n, w, n, &cnt) != BR_RECOVERY_UTXO_OK) cnt = 0;
        }
        dt = (double)(clock() - t0) / CLOCKS_PER_SEC + (cnt == (size_t)-1);
        if (dt < best) best = dt;
        free(u); free(w); free(g); free(ops); free(work); free(mvs);
    }
    return best < 1e-6 ? 1e-6 : best;
}

static void cost_n_log_n(void)
{
    static const char *names[] = { "txid groups", "exclusions", "left behind" };
    int which;
    for (which = 0; which < 3; which++) {
        double a = timeIt(which, 20000), b = timeIt(which, 320000);
        ck(b / a < 64.0, "%s: 16x the rows took %.1fx the time (O(n log n) ~20x, quadratic 256x)", names[which], b / a);
    }
    ck(sweep_partition_kat_tu2() == 1, "a second translation unit links");
}

// ---- driver ------------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "sp_asset_never_swept", sp_asset_never_swept },
    { "sp_no_assets_sweeps_all", sp_no_assets_sweeps_all },
    { "sp_only_assets_sweeps_nothing", sp_only_assets_sweeps_nothing },
    { "sp_unclassifiable_held", sp_unclassifiable_held },
    { "sp_classification_fails_entirely", sp_classification_fails_entirely },
    { "sp_every_utxo_one_bucket", sp_every_utxo_one_bucket },
    { "sp_sweepable_total", sp_sweepable_total },
    { "sp_empty_wallet", sp_empty_wallet },
    { "sp_arguments", sp_arguments },
    { "cl_plain_tx_plain", cl_plain_tx_plain },
    { "cl_asset_tx_asset", cl_asset_tx_asset },
    { "cl_every_output_held_change_included", cl_every_output_held_change_included },
    { "cl_unfetchable_unknown", cl_unfetchable_unknown },
    { "cl_empty_body_unknown", cl_empty_body_unknown },
    { "cl_unparseable_unknown", cl_unparseable_unknown },
    { "cl_one_unreachable_not_strand", cl_one_unreachable_not_strand },
    { "cl_parent_fetched_once", cl_parent_fetched_once },
    { "cl_empty_wallet", cl_empty_wallet },
    { "cl_rule_state_carried", cl_rule_state_carried },
    { "cl_resolver_throws_unknown", cl_resolver_throws_unknown },
    { "cl_no_resolver_unknown", cl_no_resolver_unknown },
    { "cl_core_reader", cl_core_reader },
    { "cl_wrong_tx_served", cl_wrong_tx_served },
    { "cl_not_exact_bytes", cl_not_exact_bytes },
    { "cl_groups_arguments", cl_groups_arguments },
    { "ios_asset_holds", ios_asset_holds },
    { "ios_unknown_holds", ios_unknown_holds },
    { "ios_plain_does_not_hold", ios_plain_does_not_hold },
    { "rs_spent_withheld", rs_spent_withheld },
    { "rs_broadcast_unavailable", rs_broadcast_unavailable },
    { "rs_failed_move_holds_plan", rs_failed_move_holds_plan },
    { "rs_no_plan_holds_outpoint", rs_no_plan_holds_outpoint },
    { "rs_disjoint", rs_disjoint },
    { "rs_several_moves", rs_several_moves },
    { "rs_no_moves", rs_no_moves },
    { "rs_set_and_arguments", rs_set_and_arguments },
    { "lb_moved_not_left", lb_moved_not_left },
    { "lb_failed_left", lb_failed_left },
    { "lb_never_reported_left", lb_never_reported_left },
    { "lb_no_assets", lb_no_assets },
    { "lb_order_preserved", lb_order_preserved },
    { "lb_arguments", lb_arguments },
    { "fs_foreign_includes_native_own_excludes", fs_foreign_includes_native_own_excludes },
    { "cost_n_log_n", cost_n_log_n },
};

int main(int argc, char **argv)
{
    size_t i;
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
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
