// Host KAT for BRCFRestorePreflight.h -- what a cold start does with the chain state the last
// session left (resume, or rebuild from the wallet's birth), and the height it arms the filter
// fetch at.
//
//   kt_*      CfRestorePreflightTest, case for case (its shouldMarkSynced cases are in
//             sync_completion_kat)
//   edge_*    the readers' bounds, the empty stored copy, the floor boundary, the arming margin
//   model_*   the header against kt_model_*, a line-for-line model of CfRestorePreflight.kt in
//             Kotlin's arithmetic (Long fields, a u32le that reads -1 past the end), over every
//             truncation, extension and byte flip of the case blobs and a seeded random corpus
//   parsers_* every ledger the header reads as whole, BRCFScanLedgerParse loads with the same
//             frontier; every window it reads as whole, BRSavedBlocksDeserialize walks to its last
//             entry (a stub block parser counts the calls)
//   vectors_* every line of the core's vectors/cf-restore-preflight/vectors.txt (argv[1]), the file
//             Android's host-JVM CfRestorePreflightVectorsTest holds the Kotlin to, agrees with the
//             model (run.sh separately checks the file is the generator's current output)
//
// Built five ways by run.sh: the shipped header, and one comparison arm per RED seam
// (CF_RESTORE_PREFLIGHT_RED, CF_RESTORE_FRONTIER_RED, CF_RESTORE_TRUNCATION_RED,
// CF_RESTORE_ARM_RED).

#include "BRCFRestorePreflight.h"
#include "BRCFScanLedger.h"
#include "BRSavedBlocks.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

// ---- The stub block parser BRSavedBlocksDeserialize calls (it reads no byte) ------------------

static size_t g_blockParses = 0;
BRMerkleBlock *BRMerkleBlockParse(const uint8_t *buf, size_t bufLen)
{
    (void)buf; (void)bufLen;
    g_blockParses++;
    return calloc(1, sizeof(BRMerkleBlock));
}
void BRMerkleBlockFree(BRMerkleBlock *block) { free(block); }

// ---- Line-for-line model of CfRestorePreflight.kt (Android 4.1.0) ---------------------------
//
// Kotlin's types: Long for every u32le read and the arithmetic on it, Int for offsets and sizes.
// A null ByteArray is has == 0. Returns the header's enum for the Kotlin reason (null = RESUME).

static int64_t kt_u32le(const uint8_t *d, int32_t size, int64_t offset)       // ByteArray.u32le
{
    if (offset < 0 || offset + 4 > size) return -1;
    return (int64_t)d[offset] | ((int64_t)d[offset + 1] << 8) | ((int64_t)d[offset + 2] << 16) |
           ((int64_t)d[offset + 3] << 24);
}

static int kt_model_parseCfLedgerState(const uint8_t *data, int32_t size, int64_t *scannedOut, int64_t *abandonedOut)
{
    if (size < 24 || kt_u32le(data, size, 0) != 0x43464c31LL) return 0;
    int64_t version = kt_u32le(data, size, 4);
    if (! (version >= 1 && version <= 3)) return 0;

    int64_t start = kt_u32le(data, size, 8);
    int64_t scannedThrough = kt_u32le(data, size, 12);
    int64_t requestedThrough = kt_u32le(data, size, 16);
    int64_t abandonedBelow;
    if (version >= 2) {
        if (size < 28) return 0;
        abandonedBelow = kt_u32le(data, size, 20);
    } else {
        abandonedBelow = 0;
    }
    if (scannedThrough + 1 < start || requestedThrough < scannedThrough) return 0;

    int32_t headerSize = version >= 2 ? 28 : 24;
    int64_t outstandingCount = kt_u32le(data, size, version >= 2 ? 24 : 20);
    int64_t outstandingSize = version >= 3 ? 6 : 5;
    if (! (outstandingCount >= 0 && outstandingCount <= 4096)) return 0;
    int64_t gaveUpCountOffset = (int64_t)headerSize + outstandingCount * outstandingSize;
    if (gaveUpCountOffset > (int64_t)size - 4) return 0;
    int64_t gaveUpCount = kt_u32le(data, size, (int32_t)gaveUpCountOffset);
    int64_t gaveUpSize = version >= 3 ? 6 : 4;
    if (! (gaveUpCount >= 0 && gaveUpCount <= 4096)) return 0;
    int64_t requiredSize = gaveUpCountOffset + 4 + gaveUpCount * gaveUpSize;
    if (requiredSize > (int64_t)size) return 0;

    *scannedOut = scannedThrough;
    *abandonedOut = abandonedBelow;
    return 1;
}

static int kt_model_parseSavedBlocksFloorHeight(const uint8_t *data, int32_t size, int64_t *floorOut)
{
    if (size < 4) return 0;
    int64_t count = kt_u32le(data, size, 0);
    if (! (count >= 1 && count <= 100000)) return 0;

    int32_t offset = 4;
    int64_t floor = INT64_MAX;
    for (int32_t it = 0; it < (int32_t)count; it++) {                    // repeat(count.toInt())
        if (offset + 8 > size) return 0;
        int64_t blockLength = kt_u32le(data, size, offset);
        int64_t height = kt_u32le(data, size, offset + 4);
        if (blockLength > INT32_MAX || blockLength > (int64_t)(size - offset - 8)) return 0;
        if (height < floor) floor = height;                                // minOf(floor, height)
        offset += 8 + (int32_t)blockLength;
    }
    if (floor == INT64_MAX) return 0;                                      // takeUnless
    *floorOut = floor;
    return 1;
}

static BRCFRestoreReset kt_model_cfRestoreResetReason(const uint8_t *ledger, int32_t ledgerSize, int hasLedger,
                                                      const uint8_t *savedBlocks, int32_t savedSize, int hasSaved,
                                                      int wasSynced, int hasTransactionCheckpoint)
{
    if (wasSynced && ! hasTransactionCheckpoint) return BR_CF_RESTORE_MISSING_TRANSACTION_CHECKPOINT;
    if (! hasLedger) return (wasSynced && hasSaved) ? BR_CF_RESTORE_MISSING_LEDGER : BR_CF_RESTORE_RESUME;

    int64_t scanned, abandoned, blockFloor;
    if (! kt_model_parseCfLedgerState(ledger, ledgerSize, &scanned, &abandoned)) return BR_CF_RESTORE_INVALID_LEDGER;
    if (! hasSaved) return BR_CF_RESTORE_HEADER_WINDOW_MISSING;
    if (! kt_model_parseSavedBlocksFloorHeight(savedBlocks, savedSize, &blockFloor)) return BR_CF_RESTORE_INVALID_SAVED_BLOCKS;
    int64_t lowestNeeded = scanned + 1 > abandoned ? scanned + 1 : abandoned;   // maxOf
    return lowestNeeded < blockFloor ? BR_CF_RESTORE_HEADER_WINDOW_MISSING : BR_CF_RESTORE_RESUME;
}

static int64_t kt_model_compactFilterBirthHeight(int wasSynced, int64_t savedTip, int64_t walletBirth,
                                                 int hasPersistedBirth, int64_t persistedBirth)
{
    int64_t resumeDefault = savedTip > 0L ? (0L > savedTip - 100L ? 0L : savedTip - 100L) : walletBirth;
    return wasSynced ? resumeDefault : (hasPersistedBirth ? persistedBirth : resumeDefault);
}

// ---- Blobs ------------------------------------------------------------------------------------

static void putU32(uint8_t *b, size_t off, uint32_t v)
{
    b[off] = (uint8_t)v; b[off + 1] = (uint8_t)(v >> 8); b[off + 2] = (uint8_t)(v >> 16); b[off + 3] = (uint8_t)(v >> 24);
}

typedef struct { uint8_t *b; size_t len; } Blob;

// An exact-size heap copy, so ASan sees any read past the end. A zero-length copy is NULL, as an
// empty Swift Data hands over no base address.
static Blob blob_copy(const uint8_t *b, size_t len)
{
    Blob o = { NULL, len };
    if (len) { o.b = malloc(len); memcpy(o.b, b, len); }
    return o;
}

// CfRestorePreflightTest.ledger: a v3 ledger, no outstanding and no parked holes.
static Blob kt_ledger(uint32_t scannedThrough, uint32_t requestedThrough, uint32_t abandonedBelow, uint32_t start)
{
    uint8_t b[32] = { 0 };
    putU32(b, 0, 0x43464c31u); putU32(b, 4, 3); putU32(b, 8, start); putU32(b, 12, scannedThrough);
    putU32(b, 16, requestedThrough); putU32(b, 20, abandonedBelow); putU32(b, 24, 0); putU32(b, 28, 0);
    return blob_copy(b, sizeof(b));
}

// CfRestorePreflightTest.savedBlocks: one empty block entry per height.
static Blob kt_saved_blocks(int n, const uint32_t *heights)
{
    size_t len = 4 + (size_t)n * 8;
    uint8_t *b = calloc(1, len);
    putU32(b, 0, (uint32_t)n);
    for (int i = 0; i < n; i++) { putU32(b, 4 + (size_t)i * 8, 0); putU32(b, 8 + (size_t)i * 8, heights[i]); }
    Blob o = blob_copy(b, len);
    free(b);
    return o;
}
#define SAVED(...) kt_saved_blocks((int)(sizeof((uint32_t[]){ __VA_ARGS__ }) / sizeof(uint32_t)), (uint32_t[]){ __VA_ARGS__ })

// A ledger of any version with real entries: declared counts, entry bytes from a pattern.
static Blob make_ledger(uint32_t version, uint32_t start, uint32_t scanned, uint32_t requested, uint32_t abandoned,
                        uint32_t outCount, uint32_t parkedCount)
{
    size_t header = version >= 2 ? 28 : 24, outSize = version >= 3 ? 6 : 5, parkSize = version >= 3 ? 6 : 4;
    size_t len = header + (size_t)outCount * outSize + 4 + (size_t)parkedCount * parkSize;
    uint8_t *b = calloc(1, len);
    putU32(b, 0, 0x43464c31u); putU32(b, 4, version); putU32(b, 8, start); putU32(b, 12, scanned); putU32(b, 16, requested);
    if (version >= 2) putU32(b, 20, abandoned);
    putU32(b, version >= 2 ? 24 : 20, outCount);
    for (size_t i = 0; i < outCount; i++) {
        putU32(b, header + i * outSize, scanned + 1 + (uint32_t)i);
        b[header + i * outSize + 4] = (uint8_t)(i & 1);
        if (outSize == 6) b[header + i * outSize + 5] = (uint8_t)(i % 3);
    }
    size_t parkedAt = header + (size_t)outCount * outSize;
    putU32(b, parkedAt, parkedCount);
    for (size_t i = 0; i < parkedCount; i++) {
        putU32(b, parkedAt + 4 + i * parkSize, scanned + 100 + (uint32_t)i);
        if (parkSize == 6) { b[parkedAt + 4 + i * parkSize + 4] = 2; b[parkedAt + 4 + i * parkSize + 5] = 1; }
    }
    Blob o = blob_copy(b, len);
    free(b);
    return o;
}

// A window of n entries with the given block lengths (bytes from a pattern) and heights.
static Blob make_window(uint32_t declared, int n, const uint32_t *blockLens, const uint32_t *heights)
{
    size_t len = 4;
    for (int i = 0; i < n; i++) len += 8 + blockLens[i];
    uint8_t *b = calloc(1, len);
    size_t off = 4;
    putU32(b, 0, declared);
    for (int i = 0; i < n; i++) {
        putU32(b, off, blockLens[i]); putU32(b, off + 4, heights[i]);
        memset(b + off + 8, 0x5a, blockLens[i]);
        off += 8 + blockLens[i];
    }
    Blob o = blob_copy(b, len);
    free(b);
    return o;
}

static Blob blob_resized(Blob src, size_t len, uint8_t fill)
{
    uint8_t *b = calloc(1, len ? len : 1);
    memcpy(b, src.b, src.len < len ? src.len : len);
    if (len > src.len) memset(b + src.len, fill, len - src.len);
    Blob o = blob_copy(b, len);
    free(b);
    return o;
}

static void blob_free(Blob *x) { free(x->b); x->b = NULL; }

static BRCFRestoreReset preflight(Blob *ledger, Blob *saved, int wasSynced, int hasCheckpoint)
{
    return BRCFRestorePreflight(ledger ? ledger->b : NULL, ledger ? ledger->len : 0, ledger != NULL,
                                saved ? saved->b : NULL, saved ? saved->len : 0, saved != NULL,
                                wasSynced, hasCheckpoint);
}

static BRCFRestoreReset kt_preflight(Blob *ledger, Blob *saved, int wasSynced, int hasCheckpoint)
{
    return kt_model_cfRestoreResetReason(ledger ? ledger->b : NULL, ledger ? (int32_t)ledger->len : 0, ledger != NULL,
                                         saved ? saved->b : NULL, saved ? (int32_t)saved->len : 0, saved != NULL,
                                         wasSynced, hasCheckpoint);
}

// ---- The corpus the model and parser properties run over --------------------------------------

#define CORPUS_MAX 200000
static Blob g_ledgers[CORPUS_MAX], g_windows[CORPUS_MAX];
static size_t g_nLedgers = 0, g_nWindows = 0;
static size_t g_baseLedgers[8], g_baseWindows[8], g_nBaseLedgers = 0, g_nBaseWindows = 0;

static void add_ledger(Blob x) { if (g_nLedgers < CORPUS_MAX) g_ledgers[g_nLedgers++] = x; else blob_free(&x); }
static void add_window(Blob x) { if (g_nWindows < CORPUS_MAX) g_windows[g_nWindows++] = x; else blob_free(&x); }

// Every truncation, a few extensions, and byte flips of one blob.
static void add_mutations(Blob base, void (*add)(Blob))
{
    static const uint8_t flips[] = { 0x01, 0x80, 0xff };
    for (size_t len = 0; len <= base.len; len++) add(blob_resized(base, len, 0));
    for (size_t extra = 1; extra <= 6; extra++) add(blob_resized(base, base.len + extra, (uint8_t)(extra * 37)));
    for (size_t i = 0; i < base.len; i++) {
        for (size_t f = 0; f < sizeof(flips); f++) {
            Blob m = blob_copy(base.b, base.len);
            m.b[i] ^= flips[f];
            add(m);
        }
        Blob z = blob_copy(base.b, base.len);
        z.b[i] = 0;
        add(z);
    }
}

static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;
static uint32_t rnd(void)
{
    g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17;
    return (uint32_t)(g_rng >> 16);
}

static uint32_t pick_count(void)
{
    static const uint32_t edge[] = { 0, 1, 2, 3, 4095, 4096, 4097, 0xffffffffu };
    uint32_t r = rnd() % 10;
    if (r < 6) return rnd() % 4;
    if (r < 8) return rnd() % 40;
    return edge[rnd() % (sizeof(edge) / sizeof(edge[0]))];
}

static Blob random_ledger(void)
{
    static const uint32_t versions[] = { 3, 3, 3, 3, 3, 2, 2, 1, 1, 0, 4 };
    uint32_t version = versions[rnd() % (sizeof(versions) / sizeof(versions[0]))];
    uint32_t start = 23000000u + rnd() % 1000;
    uint32_t scanned = start - 3 + rnd() % 8;                         // around start - 1 (nothing scanned)
    if (rnd() % 4 == 0) scanned = start + rnd() % 5000;
    uint32_t requested = scanned - 2 + rnd() % 6;
    if (rnd() % 4 == 0) requested = scanned + rnd() % 3000;
    uint32_t abandoned = rnd() % 2 ? 0 : scanned - 3 + rnd() % 8;
    if (rnd() % 16 == 0) { start = 0; scanned = 0xffffffffu; requested = 0xffffffffu; }
    uint32_t outCount = pick_count(), parkedCount = pick_count();
    Blob x;
    if (outCount <= 4097 && parkedCount <= 4097) {
        x = make_ledger(version, start, scanned, requested, abandoned, outCount, parkedCount);
    } else {
        // A count past any cap: the header and a few bytes are all that is needed.
        uint8_t b[40] = { 0 };
        putU32(b, 0, 0x43464c31u); putU32(b, 4, version); putU32(b, 8, start); putU32(b, 12, scanned);
        putU32(b, 16, requested); putU32(b, 20, version >= 2 ? abandoned : outCount);
        putU32(b, 24, outCount); putU32(b, 28, parkedCount); putU32(b, 32, parkedCount);
        x = blob_copy(b, sizeof(b));
    }
    uint32_t r = rnd() % 10;
    if (r < 2 && x.len) { Blob t = blob_resized(x, x.len - 1 - rnd() % (x.len < 8 ? x.len : 8), 0); blob_free(&x); x = t; }
    else if (r < 4) { Blob t = blob_resized(x, x.len + 1 + rnd() % 8, (uint8_t)rnd()); blob_free(&x); x = t; }
    else if (r < 5 && x.len) { Blob t = blob_resized(x, rnd() % x.len, 0); blob_free(&x); x = t; }
    if (rnd() % 25 == 0 && x.len >= 4) x.b[rnd() % 4] ^= (uint8_t)(1u << (rnd() % 8));
    return x;
}

static Blob random_window(void)
{
    static const uint32_t edgeCounts[] = { 0, 100000, 100001, 0xffffffffu };
    static const uint32_t edgeLens[] = { 0x7fffffffu, 0x80000000u, 0xffffffffu };
    uint32_t lens[24], heights[24];
    int n = 1 + (int)(rnd() % 6);
    if (rnd() % 8 == 0) n = 1 + (int)(rnd() % 24);
    uint32_t declared = (uint32_t)n;
    uint32_t r = rnd() % 12;
    if (r == 0) declared = edgeCounts[rnd() % 4];
    else if (r == 1) declared = (uint32_t)n + 1 + rnd() % 3;              // more declared than written
    else if (r == 2 && n > 1) declared = (uint32_t)n - 1;                 // fewer: the rest is trailing
    for (int i = 0; i < n; i++) {
        lens[i] = rnd() % 3 == 0 ? 0 : rnd() % 4 == 0 ? 80 : rnd() % 120;
        heights[i] = 23000000u + rnd() % 2000;
        if (rnd() % 30 == 0) heights[i] = rnd() % 2 ? 0 : 0xffffffffu;
    }
    Blob x = make_window(declared, n, lens, heights);
    if (rnd() % 20 == 0 && x.len >= 12) putU32(x.b, 4, edgeLens[rnd() % 3]);
    r = rnd() % 10;
    if (r < 2 && x.len) { Blob t = blob_resized(x, x.len - 1 - rnd() % (x.len < 12 ? x.len : 12), 0); blob_free(&x); x = t; }
    else if (r < 4) { Blob t = blob_resized(x, x.len + 1 + rnd() % 12, (uint8_t)rnd()); blob_free(&x); x = t; }
    else if (r < 5 && x.len) { Blob t = blob_resized(x, rnd() % x.len, 0); blob_free(&x); x = t; }
    return x;
}

static void build_corpus(void)
{
    Blob bases[] = {
        kt_ledger(1000, 1200, 0, 900), kt_ledger(1000, 1200, 1100, 900),
        kt_ledger(23998330, 23998330, 0, 23996054),
        make_ledger(1, 900, 1000, 1200, 0, 2, 2), make_ledger(2, 900, 1000, 1200, 950, 2, 2),
        make_ledger(3, 900, 1000, 1200, 1100, 2, 2), make_ledger(3, 1001, 1000, 1000, 0, 0, 0),
    };
    for (size_t i = 0; i < sizeof(bases) / sizeof(bases[0]); i++) { add_mutations(bases[i], add_ledger); g_baseLedgers[g_nBaseLedgers++] = g_nLedgers; add_ledger(bases[i]); }

    uint32_t lens[3] = { 80, 0, 7 }, heights[3] = { 1200, 1000, 1100 };
    Blob windows[] = { SAVED(1200, 1000, 900), SAVED(1200, 1100), SAVED(1200), make_window(3, 3, lens, heights) };
    for (size_t i = 0; i < sizeof(windows) / sizeof(windows[0]); i++) { add_mutations(windows[i], add_window); g_baseWindows[g_nBaseWindows++] = g_nWindows; add_window(windows[i]); }

    while (g_nLedgers < 60000) add_ledger(random_ledger());
    while (g_nWindows < 60000) add_window(random_window());
}

// ---- Cases ------------------------------------------------------------------------------------

static void kotlin_cases(void)
{
    {   Blob l = kt_ledger(1000, 1200, 0, 900), s = SAVED(1200, 1000, 900);
        result("kt_healthy_frontier_keeps_window", preflight(&l, &s, 1, 1) == BR_CF_RESTORE_RESUME);
        blob_free(&l); blob_free(&s);
    }
    {   Blob l = kt_ledger(1000, 1200, 1100, 900), s = SAVED(1200, 1100);
        result("kt_abandoned_covered_resumes", preflight(&l, &s, 0, 0) == BR_CF_RESTORE_RESUME);
        blob_free(&l); blob_free(&s);
    }
    {   Blob l = kt_ledger(1000, 1200, 1100, 900), s = SAVED(1200, 1150);
        result("kt_abandoned_not_covered_rebuilds", preflight(&l, &s, 0, 0) == BR_CF_RESTORE_HEADER_WINDOW_MISSING);
        blob_free(&l); blob_free(&s);
    }
    {   Blob l = kt_ledger(1000, 1200, 0, 900), s = SAVED(1200, 1100);
        result("kt_frontier_below_floor_rebuilds", preflight(&l, &s, 0, 0) == BR_CF_RESTORE_HEADER_WINDOW_MISSING);
        blob_free(&l); blob_free(&s);
    }
    {   Blob l = kt_ledger(1000, 1200, 0, 900);
        result("kt_ledger_without_blocks_rebuilds", preflight(&l, NULL, 0, 0) == BR_CF_RESTORE_HEADER_WINDOW_MISSING);
        blob_free(&l);
    }
    {   Blob s = SAVED(1200);
        result("kt_synced_without_ledger_rescans", preflight(NULL, &s, 1, 1) == BR_CF_RESTORE_MISSING_LEDGER);
        blob_free(&s);
    }
    {   Blob s = SAVED(1200);
        result("kt_fresh_without_ledger_starts", preflight(NULL, &s, 0, 0) == BR_CF_RESTORE_RESUME);
        blob_free(&s);
    }
    {   uint8_t zeros[28] = { 0 }, two[4] = { 2, 0, 0, 0 };
        Blob z = blob_copy(zeros, sizeof(zeros)), s = SAVED(1200), l = kt_ledger(1000, 1200, 0, 900), t = blob_copy(two, 4);
        result("kt_invalid_inputs_fail_safe", preflight(&z, &s, 1, 1) == BR_CF_RESTORE_INVALID_LEDGER &&
                                              preflight(&l, &t, 1, 1) == BR_CF_RESTORE_INVALID_SAVED_BLOCKS);
        blob_free(&z); blob_free(&s); blob_free(&l); blob_free(&t);
    }
    {   Blob l = kt_ledger(23998330, 23998330, 0, 23996054), s = SAVED(23998351, 23998052);
        result("kt_synced_without_tx_checkpoint_rebuilds",
               preflight(&l, &s, 1, 0) == BR_CF_RESTORE_MISSING_TRANSACTION_CHECKPOINT);
        blob_free(&l); blob_free(&s);
    }
    result("kt_synced_resume_ignores_stale_deep_birth",
           BRCFRestoreArmHeight(1, 23997321, 22650000, 1, 22650000) == 23997221);
    result("kt_unfinished_restore_keeps_floor",
           BRCFRestoreArmHeight(0, 23997321, 22650000, 1, 22650000) == 22650000);
}

static void edge_cases(void)
{
    BRCFScanLedger *core = calloc(1, sizeof(BRCFScanLedger));
    uint32_t s = 0, a = 0, f = 0;

    {   // The parser loads both of these; the check calls them unreadable. A fresh ledger (nothing
        // scanned: scanned + 1 == start) is whole.
        Blob behind = make_ledger(3, 1000, 998, 1200, 0, 0, 0), over = make_ledger(3, 900, 1000, 999, 0, 0, 0);
        Blob fresh = make_ledger(3, 1000, 999, 999, 0, 0, 0);
        int coreLoads = BRCFScanLedgerParse(core, behind.b, behind.len) && BRCFScanLedgerParse(core, over.b, over.len);
        result("edge_inconsistent_frontier_is_unreadable", coreLoads &&
               ! BRCFRestoreLedgerFrontier(behind.b, behind.len, &s, &a) &&
               ! BRCFRestoreLedgerFrontier(over.b, over.len, &s, &a) &&
               BRCFRestoreLedgerFrontier(fresh.b, fresh.len, &s, &a) && s == 999);
        blob_free(&behind); blob_free(&over); blob_free(&fresh);
    }
    {   Blob v0 = make_ledger(3, 900, 1000, 1200, 0, 1, 1), v4 = make_ledger(3, 900, 1000, 1200, 0, 1, 1);
        Blob v1 = make_ledger(1, 900, 1000, 1200, 0, 3, 2), v2 = make_ledger(2, 900, 1000, 1200, 950, 3, 2);
        Blob v3 = make_ledger(3, 900, 1000, 1200, 1100, 3, 2);
        putU32(v0.b, 4, 0); putU32(v4.b, 4, 4);
        uint32_t s1 = 0, a1 = 7, s2 = 0, a2 = 0, s3 = 0, a3 = 0;
        result("edge_ledger_versions_and_strides",
               ! BRCFRestoreLedgerFrontier(v0.b, v0.len, &s, &a) && ! BRCFRestoreLedgerFrontier(v4.b, v4.len, &s, &a) &&
               BRCFRestoreLedgerFrontier(v1.b, v1.len, &s1, &a1) && s1 == 1000 && a1 == 0 && v1.len == 24 + 15 + 4 + 8 &&
               BRCFRestoreLedgerFrontier(v2.b, v2.len, &s2, &a2) && s2 == 1000 && a2 == 950 && v2.len == 28 + 15 + 4 + 8 &&
               BRCFRestoreLedgerFrontier(v3.b, v3.len, &s3, &a3) && s3 == 1000 && a3 == 1100 && v3.len == 28 + 18 + 4 + 12);
        blob_free(&v0); blob_free(&v4); blob_free(&v1); blob_free(&v2); blob_free(&v3);
    }
    {   Blob o1 = make_ledger(3, 900, 1000, 1200, 0, 4096, 0), o2 = make_ledger(3, 900, 1000, 1200, 0, 4097, 0);
        Blob p1 = make_ledger(3, 900, 1000, 1200, 0, 0, 4096), p2 = make_ledger(3, 900, 1000, 1200, 0, 0, 4097);
        result("edge_ledger_entry_caps",
               BRCFRestoreLedgerFrontier(o1.b, o1.len, &s, &a) && ! BRCFRestoreLedgerFrontier(o2.b, o2.len, &s, &a) &&
               BRCFRestoreLedgerFrontier(p1.b, p1.len, &s, &a) && ! BRCFRestoreLedgerFrontier(p2.b, p2.len, &s, &a));
        blob_free(&o1); blob_free(&o2); blob_free(&p1); blob_free(&p2);
    }
    {   Blob v3 = make_ledger(3, 900, 1000, 1200, 1100, 2, 2), v1 = make_ledger(1, 900, 1000, 1200, 0, 2, 2);
        int ok = 1;
        for (size_t len = 0; len < v3.len; len++) {
            Blob t = blob_resized(v3, len, 0);
            ok = ok && ! BRCFRestoreLedgerFrontier(t.b, t.len, &s, &a);
            blob_free(&t);
        }
        for (size_t len = 0; len < v1.len; len++) {
            Blob t = blob_resized(v1, len, 0);
            ok = ok && ! BRCFRestoreLedgerFrontier(t.b, t.len, &s, &a);
            blob_free(&t);
        }
        Blob longer = blob_resized(v3, v3.len + 9, 0xee);
        result("edge_ledger_truncation_is_unreadable", ok && BRCFRestoreLedgerFrontier(v3.b, v3.len, &s, &a) &&
               BRCFRestoreLedgerFrontier(longer.b, longer.len, &s, &a));
        blob_free(&v3); blob_free(&v1); blob_free(&longer);
    }
    {   // Every cut inside an entry: the parser reads the window short, the check calls it unreadable.
        uint32_t lens[3] = { 80, 0, 7 }, heights[3] = { 1200, 1000, 1100 };
        Blob w = make_window(3, 3, lens, heights);
        int ok = 1, shortReads = 0;
        for (size_t len = 4; len < w.len; len++) {
            Blob t = blob_resized(w, len, 0);
            BRMerkleBlock **blocks = NULL;
            size_t n = BRSavedBlocksDeserialize(t.b, t.len, &blocks);
            shortReads += n > 0 && n < 3;
            for (size_t i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
            free(blocks);
            ok = ok && ! BRCFRestoreSavedBlocksFloor(t.b, t.len, &f);
            blob_free(&t);
        }
        result("edge_window_truncation_is_unreadable", ok && shortReads > 0 &&
               BRCFRestoreSavedBlocksFloor(w.b, w.len, &f) && f == 1000);
        blob_free(&w);
    }
    {   uint32_t one = 0, h = 1200;
        Blob zero = make_window(0, 0, NULL, NULL), over = make_window(100001, 1, &one, &h);
        Blob big = make_window(1, 1, &one, &h), trailing = SAVED(1300, 1250);
        putU32(big.b, 4, 0x80000000u);
        Blob t2 = blob_resized(trailing, trailing.len + 5, 0x33);
        uint32_t *zl = calloc(100000, sizeof(uint32_t)), *hs = malloc(100000 * sizeof(uint32_t));
        for (int i = 0; i < 100000; i++) hs[i] = 2000000u + (uint32_t)(i * 7 % 100000);
        Blob cap = make_window(100000, 100000, zl, hs);
        uint32_t fc = 0, ft = 0;
        result("edge_window_bounds",
               ! BRCFRestoreSavedBlocksFloor(zero.b, zero.len, &f) && ! BRCFRestoreSavedBlocksFloor(over.b, over.len, &f) &&
               ! BRCFRestoreSavedBlocksFloor(big.b, big.len, &f) &&
               BRCFRestoreSavedBlocksFloor(cap.b, cap.len, &fc) && fc == 2000000u &&
               BRCFRestoreSavedBlocksFloor(t2.b, t2.len, &ft) && ft == 1250);
        free(zl); free(hs);
        blob_free(&zero); blob_free(&over); blob_free(&big); blob_free(&trailing); blob_free(&t2); blob_free(&cap);
    }
    {   // An empty stored copy is present: unreadable, never "absent". Its pointer may be NULL.
        Blob empty = { NULL, 0 }, l = kt_ledger(1000, 1200, 0, 900), s = SAVED(1200, 1000);
        result("edge_empty_stored_copies_are_unreadable",
               preflight(&empty, &s, 0, 0) == BR_CF_RESTORE_INVALID_LEDGER &&
               preflight(&empty, NULL, 1, 1) == BR_CF_RESTORE_INVALID_LEDGER &&
               preflight(&l, &empty, 0, 0) == BR_CF_RESTORE_INVALID_SAVED_BLOCKS);
        blob_free(&l); blob_free(&s);
    }
    {   Blob at = kt_ledger(999, 1200, 0, 900), below = kt_ledger(998, 1200, 0, 900), s = SAVED(1200, 1000);
        Blob abandonedAt = kt_ledger(500, 1200, 1000, 400), abandonedBelow = kt_ledger(500, 1200, 999, 400);
        result("edge_floor_boundary",
               preflight(&at, &s, 1, 1) == BR_CF_RESTORE_RESUME &&
               preflight(&below, &s, 1, 1) == BR_CF_RESTORE_HEADER_WINDOW_MISSING &&
               preflight(&abandonedAt, &s, 1, 1) == BR_CF_RESTORE_RESUME &&
               preflight(&abandonedBelow, &s, 1, 1) == BR_CF_RESTORE_HEADER_WINDOW_MISSING);
        blob_free(&at); blob_free(&below); blob_free(&s); blob_free(&abandonedAt); blob_free(&abandonedBelow);
    }
    {   // scanned + 1 is 2^32 here, in Kotlin's Long; a 32-bit sum would wrap to 0 and rebuild.
        Blob l = kt_ledger(0xffffffffu, 0xffffffffu, 0, 0), s = SAVED(0xfffffff0u);
        result("edge_frontier_does_not_wrap", preflight(&l, &s, 1, 1) == BR_CF_RESTORE_RESUME);
        blob_free(&l); blob_free(&s);
    }
    result("edge_arm_heights",
           BRCFRestoreArmHeight(0, 0, 22650000, 0, 0) == 22650000 &&          // fresh: the birth checkpoint
           BRCFRestoreArmHeight(1, 0, 22650000, 1, 5) == 22650000 &&          // synced, no window
           BRCFRestoreArmHeight(0, 50, 22650000, 0, 0) == 0 &&                // a tip under the margin
           BRCFRestoreArmHeight(0, 100, 22650000, 0, 0) == 0 &&
           BRCFRestoreArmHeight(0, 101, 22650000, 0, 0) == 1 &&
           BRCFRestoreArmHeight(0, 0, 0, 0, 0) == 0 &&                        // no wallet
           BRCFRestoreArmHeight(0, 23997321, 22650000, 0, 0) == 23997221 &&   // no floor stored
           BRCFRestoreArmHeight(1, 23997321, 22650000, 1, 21000000) == 23997221);
    result("edge_reset_names",
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_RESUME), "") &&
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_MISSING_LEDGER), "MISSING_LEDGER") &&
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_MISSING_TRANSACTION_CHECKPOINT), "MISSING_TRANSACTION_CHECKPOINT") &&
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_INVALID_LEDGER), "INVALID_LEDGER") &&
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_INVALID_SAVED_BLOCKS), "INVALID_SAVED_BLOCKS") &&
           ! strcmp(BRCFRestoreResetName(BR_CF_RESTORE_HEADER_WINDOW_MISSING), "HEADER_WINDOW_MISSING"));
    result("edge_window_cap_is_the_parsers", BR_CF_RESTORE_SAVED_BLOCKS_MAX == BR_SAVED_BLOCKS_MAX_COUNT &&
                                             BR_CF_RESTORE_LEDGER_ENTRIES_MAX == CF_OUTSTANDING_MAX);
    free(core);
}

static void model_cases(void)
{
    size_t compared = 0, mismatches = 0;

    // Every ledger against the base windows and none, and every window against the base ledgers
    // and none, under all four flag pairs; then random pairs.
    for (size_t i = 0; i < g_nLedgers; i++) {
        for (size_t w = 0; w <= g_nBaseWindows; w++) {
            Blob *saved = w < g_nBaseWindows ? &g_windows[g_baseWindows[w]] : NULL;
            for (int flags = 0; flags < 4; flags++) {
                compared++;
                if (preflight(&g_ledgers[i], saved, flags & 1, flags >> 1) != kt_preflight(&g_ledgers[i], saved, flags & 1, flags >> 1)) {
                    if (mismatches++ < 5) printf("  model mismatch: ledger %zu (len %zu), window %zu, flags %d\n", i, g_ledgers[i].len, w, flags);
                }
            }
        }
    }
    for (size_t i = 0; i < g_nWindows; i++) {
        for (size_t l = 0; l <= g_nBaseLedgers; l++) {
            Blob *ledger = l < g_nBaseLedgers ? &g_ledgers[g_baseLedgers[l]] : NULL;
            for (int flags = 0; flags < 4; flags++) {
                compared++;
                if (preflight(ledger, &g_windows[i], flags & 1, flags >> 1) != kt_preflight(ledger, &g_windows[i], flags & 1, flags >> 1)) {
                    if (mismatches++ < 5) printf("  model mismatch: window %zu (len %zu), ledger %zu, flags %d\n", i, g_windows[i].len, l, flags);
                }
            }
        }
    }
    for (int k = 0; k < 400000; k++) {
        Blob *ledger = rnd() % 10 ? &g_ledgers[rnd() % g_nLedgers] : NULL;
        Blob *saved = rnd() % 10 ? &g_windows[rnd() % g_nWindows] : NULL;
        int synced = rnd() & 1, checkpoint = rnd() & 1;
        compared++;
        if (preflight(ledger, saved, synced, checkpoint) != kt_preflight(ledger, saved, synced, checkpoint)) mismatches++;
    }
    printf("  model: %zu preflight verdicts compared, %zu differ\n", compared, mismatches);
    result("model_agrees_preflight", mismatches == 0 && compared > 2000000);

    size_t armCompared = 0, armMismatches = 0;
    static const int64_t tips[] = { 0, 1, 99, 100, 101, 23997321, 0xffffffffLL };
    for (int k = 0; k < 400000; k++) {
        int synced = rnd() & 1, hasFloor = rnd() & 1;
        int64_t tip = rnd() % 3 ? (int64_t)rnd() * 2 % 0x100000000LL : tips[rnd() % 7];
        int64_t birth = rnd() % 4 ? (int64_t)(rnd() % 24000000u) : 0;
        int64_t floor = rnd() % 4 ? (int64_t)(rnd() % 24000000u) : (int64_t)((uint64_t)rnd() << 32 | rnd());
        armCompared++;
        if (BRCFRestoreArmHeight(synced, tip, birth, hasFloor, floor) != kt_model_compactFilterBirthHeight(synced, tip, birth, hasFloor, floor)) armMismatches++;
    }
    printf("  model: %zu arming heights compared, %zu differ\n", armCompared, armMismatches);
    result("model_agrees_arm", armMismatches == 0);
}

static void parser_cases(void)
{
    BRCFScanLedger *core = calloc(1, sizeof(BRCFScanLedger));
    size_t whole = 0, loaded = 0, coreOnly = 0;
    for (size_t i = 0; i < g_nLedgers; i++) {
        uint32_t s = 0, a = 0;
        int mine = BRCFRestoreLedgerFrontier(g_ledgers[i].b, g_ledgers[i].len, &s, &a);
        int theirs = BRCFScanLedgerParse(core, g_ledgers[i].b, g_ledgers[i].len);
        if (mine) {
            whole++;
            loaded += theirs && BRCFScanLedgerScannedThrough(core) == s && BRCFScanLedgerAbandonedBelow(core) == a;
        } else if (theirs) {
            coreOnly++;
        }
    }
    printf("  parsers: %zu ledgers whole, %zu loaded alike by BRCFScanLedgerParse; %zu it loads that the check refuses\n",
           whole, loaded, coreOnly);
    result("parsers_load_every_valid_ledger", whole > 1000 && loaded == whole);
    free(core);

    size_t windows = 0, walked = 0;
    for (size_t i = 0; i < g_nWindows; i++) {
        uint32_t f = 0;
        if (! BRCFRestoreSavedBlocksFloor(g_windows[i].b, g_windows[i].len, &f)) continue;
        BRMerkleBlock **blocks = NULL;
        size_t before = g_blockParses;
        size_t n = BRSavedBlocksDeserialize(g_windows[i].b, g_windows[i].len, &blocks);
        uint32_t lowest = UINT32_MAX;
        for (size_t k = 0; k < n; k++) { if (blocks[k]->height < lowest) lowest = blocks[k]->height; BRMerkleBlockFree(blocks[k]); }
        free(blocks);
        windows++;
        walked += g_blockParses - before == (size_t)(g_windows[i].b[0] | g_windows[i].b[1] << 8 | g_windows[i].b[2] << 16 |
                                                     (uint32_t)g_windows[i].b[3] << 24) && lowest == f;
    }
    printf("  parsers: %zu windows whole, %zu walked to the last entry by BRSavedBlocksDeserialize with the same floor\n",
           windows, walked);
    result("parsers_walk_every_valid_window", windows > 1000 && walked == windows);
}

static int hexval(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; }

static int parse_hex(const char *h, Blob *out)
{
    size_t n = strlen(h), i;
    if (! strcmp(h, "empty")) { out->b = NULL; out->len = 0; return 1; }
    if (n % 2) return 0;
    out->len = n / 2;
    out->b = malloc(out->len ? out->len : 1);
    for (i = 0; i < out->len; i++) {
        int hi = hexval(h[2 * i]), lo = hexval(h[2 * i + 1]);
        if (hi < 0 || lo < 0) return 0;
        out->b[i] = (uint8_t)(hi << 4 | lo);
    }
    return 1;
}

static const char *reason_name(BRCFRestoreReset r)
{
    return r == BR_CF_RESTORE_RESUME ? "RESUME" : BRCFRestoreResetName(r);
}

// The vector file, line by line, against the model of the Kotlin (not the header: the header's
// agreement with the file is the generator's, and run.sh diffs that).
static void vector_cases(const char *path)
{
    static Blob ledgers[8192], windows[8192];
    char line[1 << 16];
    size_t resets = 0, arms = 0, bad = 0, n = 0;
    FILE *f = path ? fopen(path, "r") : NULL;

    if (! f) { printf("  vectors: cannot open %s\n", path ? path : "(no path)"); result("vectors_match_the_model", 0); return; }
    while (fgets(line, sizeof(line), f)) {
        char a[16], b[16], hex[1 << 16], want[64];
        int id, synced, checkpoint;
        long long tip, birth, height;
        n++;
        line[strcspn(line, "\n")] = 0;
        if (line[0] == '#' || ! line[0]) continue;
        if (sscanf(line, "blob L%d %65535s", &id, hex) == 2 && id >= 0 && id < 8192) {
            if (! parse_hex(hex, &ledgers[id])) bad++;
        } else if (sscanf(line, "blob W%d %65535s", &id, hex) == 2 && id >= 0 && id < 8192) {
            if (! parse_hex(hex, &windows[id])) bad++;
        } else if (sscanf(line, "reset %15s %15s %d %d %63s", a, b, &synced, &checkpoint, want) == 5) {
            Blob *l = a[0] == 'L' ? &ledgers[atoi(a + 1)] : NULL, *w = b[0] == 'W' ? &windows[atoi(b + 1)] : NULL;
            const char *got = reason_name(kt_preflight(l, w, synced, checkpoint));
            resets++;
            if (strcmp(got, want)) { if (bad++ < 5) printf("  vectors line %zu: the model says %s, the file %s\n", n, got, want); }
        } else if (sscanf(line, "arm %d %lld %lld %15s %lld", &synced, &tip, &birth, a, &height) == 5) {
            int hasFloor = strcmp(a, "-") != 0;
            arms++;
            if (kt_model_compactFilterBirthHeight(synced, tip, birth, hasFloor, hasFloor ? atoll(a) : 0) != height) {
                if (bad++ < 5) printf("  vectors line %zu: the model's arming height differs\n", n);
            }
        } else {
            if (bad++ < 5) printf("  vectors line %zu: unreadable\n", n);
        }
    }
    fclose(f);
    printf("  vectors: %zu reset and %zu arming lines, %zu disagree with the model\n", resets, arms, bad);
    result("vectors_match_the_model", bad == 0 && resets > 2000 && arms > 100);
    for (size_t i = 0; i < 8192; i++) { free(ledgers[i].b); free(windows[i].b); }
}

int main(int argc, char **argv)
{
    kotlin_cases();
    edge_cases();
    build_corpus();
    printf("  corpus: %zu ledgers, %zu windows\n", g_nLedgers, g_nWindows);
    model_cases();
    parser_cases();
    vector_cases(argc > 1 ? argv[1] : NULL);
    for (size_t i = 0; i < g_nLedgers; i++) blob_free(&g_ledgers[i]);
    for (size_t i = 0; i < g_nWindows; i++) blob_free(&g_windows[i]);
    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
